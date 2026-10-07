"""h23 stage 2: per-index stats, random-entry baseline, fixed-1-lot capital walk at Rs 1 lakh, PREREG selection.

    python3 -I research/hunt/h23/run.py pre     # selection on data before 2025-10-01 only
    python3 -I research/hunt/h23/run.py hold    # the holdout, ONCE (reads choice.json written by `pre`)
    python3 -I research/hunt/h23/run.py val     # h17 reproduction on h15's (no-mask) table
"""
from __future__ import annotations

import json
import os
import pickle
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = os.path.join(SCR, "hunt/h23")
CAL = pd.read_csv(os.path.join(SCR, "hunt/h4/cache/h4/daily_real.csv"), index_col=0, parse_dates=True).index
E0 = 100_000.0
START, REG, HOLD, END = (pd.Timestamp(x) for x in ("2021-10-01", "2024-12-01", "2025-10-01", "2030-01-01"))
BASE = ("BANKNIFTY", "MIDCPNIFTY")
CANDS = ("BANKEX", "NIFTYNXT50", "FINNIFTY", "NIFTY", "SENSEX")
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def load():
    T = pd.read_parquet(os.path.join(OUT, "trades.parquet"))
    P = pd.read_parquet(os.path.join(OUT, "pool.parquet"))
    for d in (T, P):
        d["day"] = pd.to_datetime(d["day"])
    return T, P


def cal(lo, hi):
    return CAL[(CAL >= lo) & (CAL < hi)]


def daily(t, k, lo, hi, col="net"):
    c = cal(lo, hi)
    return t.groupby("day")[f"{col}_{k}"].sum().reindex(c, fill_value=0.0)


def boot_p(x, B=5000, block=10, seed=23):
    """day-block bootstrap of the mean of a daily series: P(mean <= 0) under the centred... (percentile method)."""
    x = np.asarray(x, float)
    n = len(x)
    if n < 5:
        return np.nan
    rng = np.random.default_rng(seed)
    nb = int(np.ceil(n / block))
    st = rng.integers(0, n, size=(B, nb))
    idx = (st[:, :, None] + np.arange(block)[None, None, :]) % n
    m = x[idx.reshape(B, -1)[:, :n]].mean(axis=1)
    return float((m <= 0).mean())


def series_stats(dn, dg=None):
    eq = dn.cumsum()
    peak = np.maximum.accumulate(np.r_[0.0, eq.values])[1:]
    mon = dn.groupby(dn.index.to_period("M")).sum()
    sd = dn.std(ddof=1)
    return dict(days=len(dn), net=float(dn.sum()), gross=float(dg.sum()) if dg is not None else np.nan,
                net_day=float(dn.mean()), gross_day=float(dg.mean()) if dg is not None else np.nan,
                t=float(dn.mean() / sd * np.sqrt(len(dn))) if sd > 0 else np.nan, boot_p=boot_p(dn.values),
                maxdd=float((eq - peak).min()), worst_day=float(dn.min()),
                green_months=f"{int((mon > 0).sum())}/{len(mon)}", pct_green=float((mon > 0).mean()) if len(mon) else np.nan)


def rand_p(t, P, k, D=2000, seed=23):
    """random-entry baseline: one alternative per real trade (same exits/fills/costs), D draws."""
    t = t[t[f"lots_{k}"] > 0]
    P = P[P.parent.isin(t.cand.values)]
    if t.empty or P.empty:
        return np.nan, np.nan, np.nan
    g = {c: v[f"net_{k}"].values for c, v in P.groupby("parent")}
    tt = t[t.cand.isin(list(g))]
    real = tt[f"net_{k}"].mean()
    rng = np.random.default_rng(seed)
    alts = [g[c] for c in tt.cand.values]
    draws = np.array([np.mean([a[rng.integers(len(a))] for a in alts]) for _ in range(D)])
    return float(real), float(draws.mean()), float((1 + (draws >= real).sum()) / (1 + D))


def index_stats(T, P, u, k, lo, hi):
    t = T[(T.und == u) & (T.day >= lo) & (T.day < hi)]
    if t.empty:
        return dict(und=u, kappa=k, trades=0)
    a = max(lo, t.day.min())
    dn = daily(t, k, a, hi)
    dg = daily(t, k, a, hi, "gross")
    dn = dn[dn.index <= t.day.max()]
    dg = dg[dg.index <= t.day.max()]
    filled = t[t[f"lots_{k}"] > 0]
    r_real, r_rand, p = rand_p(t, P, k)
    s = dict(und=u, kappa=k, span=f"{a.date()}..{t.day.max().date()}", signals_taken=len(t), trades=len(filled),
             missed_fill=int((t[f"lots_{k}"] == 0).sum()), per_trade=float(filled[f"net_{k}"].mean()),
             spread_extra_per_trade=float(filled[f"spread_extra_{k}"].mean()),
             median_hs_pct=float(100 * t.hs.median()), rand_real=r_real, rand_mean=r_rand, rand_p=p)
    s.update(series_stats(dn, dg))
    return s


def walk(T, unds, k, lo, hi, start=E0):
    """Fixed 1 lot per index, one position per book (already), free-cash check. Returns stats + daily series."""
    t = T[T.und.isin(unds) & (T.day >= lo) & (T.day < hi) & (T[f"lots_{k}"] > 0)]
    t = t.sort_values(["day", "entry_min", "und", "book"], kind="stable")
    c = cal(lo, hi)
    dn = pd.Series(0.0, index=c)
    dg = pd.Series(0.0, index=c)
    E = start
    minE = E
    taken = skipped = 0
    DAY = t.day.values
    EM, PR, EN, NE, GR = (t[c].values for c in ("entry_min", f"prem_{k}", f"end_{k}", f"net_{k}", f"gross_{k}"))
    i = 0
    N = len(t)
    while i < N:
        j = i
        while j < N and DAY[j] == DAY[i]:
            j += 1
        open_ = []          # (end, prem, net)
        dayn = dayg = 0.0
        for r in range(i, j):
            keep = []
            for x in open_:
                if x[0] <= EM[r]:
                    E += x[2]
                    dayn += x[2]
                    minE = min(minE, E)
                else:
                    keep.append(x)
            open_ = keep
            if PR[r] + 100.0 > E - sum(x[1] for x in open_):
                skipped += 1
                continue
            taken += 1
            open_.append((EN[r], PR[r], NE[r]))
            dayg += GR[r]
        for x in open_:
            E += x[2]
            dayn += x[2]
            minE = min(minE, E)
        d = pd.Timestamp(DAY[i])
        if d in dn.index:
            dn[d] += dayn
            dg[d] += dayg
        i = j
    s = dict(book=("+".join(unds)), kappa=k, taken=taken, skipped_cash=skipped, end_cap=E, min_cap=minE)
    s.update(series_stats(dn, dg))
    return s, dn


def bh(p):
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = np.empty(n)
    q[o] = np.minimum.accumulate((p[o] * n / np.arange(1, n + 1))[::-1])[::-1]
    return np.minimum(q, 1.0)


def fmt(df):
    return df.to_string(float_format=lambda x: f"{x:,.2f}")


def pre():
    T, P = load()
    T = T[T.day < HOLD]
    P = P[P.day < HOLD]
    f = pd.read_csv(os.path.join(OUT, "funnel.csv"))
    print("FUNNEL (all dates; counts only)\n", f.to_string())
    print("COVERAGE\n", pd.read_csv(os.path.join(OUT, "coverage.csv")).to_string())
    roll = pd.read_csv(os.path.join(OUT, "roll.csv"))
    print("ROLL half-spread % by index (pre months):\n",
          (roll[roll.month < "2025-10"].assign(hs=lambda x: 50 * x.spread).groupby("und").hs.describe()).to_string())
    rows = []
    for u in BASE + CANDS:
        for k in (0.02, 0.04):
            rows.append(index_stats(T, P, u, k, START, HOLD))
            rows.append(dict(index_stats(T, P, u, k, REG, HOLD), und=u + " [eraM]"))
    S = pd.DataFrame(rows)
    S.to_csv(os.path.join(OUT, "pre_index.csv"), index=False)
    print("\nPER INDEX, 1 lot, pre-holdout\n", fmt(S[["und", "kappa", "span", "trades", "missed_fill", "net", "gross",
                                                     "net_day", "per_trade", "spread_extra_per_trade", "median_hs_pct",
                                                     "t", "boot_p", "rand_real", "rand_mean", "rand_p", "maxdd",
                                                     "worst_day", "green_months"]]))
    # selection
    base_s, _ = walk(T, BASE, 0.02, START, HOLD)
    sel = []
    s02 = S[S.kappa == 0.02].set_index("und")
    ps = []
    for u in CANDS:
        a = s02.loc[u] if u in s02.index else None
        m = s02.loc[u + " [eraM]"] if (u + " [eraM]") in s02.index else None
        n = int(a["trades"]) if a is not None and np.isfinite(a.get("trades", np.nan)) else 0
        w, _ = walk(T, BASE + (u,), 0.02, START, HOLD)
        sel.append(dict(und=u, trades=n, net_pre=float(a["net"]) if n else np.nan,
                        net_eraM=float(m["net"]) if m is not None and m["trades"] > 0 else np.nan,
                        rand_p=float(a["rand_p"]) if n else np.nan, port_day=w["net_day"], base_day=base_s["net_day"]))
        ps.append(sel[-1]["rand_p"] if n else 1.0)
    Q = bh(ps)
    for s, q in zip(sel, Q):
        s["bh_q"] = q
        s["a"] = s["trades"] >= 30
        s["b"] = bool(s["net_pre"] > 0 and s["net_eraM"] > 0) if s["trades"] else False
        s["c"] = bool(q < 0.05)
        s["d"] = bool(s["port_day"] > s["base_day"])
        s["eligible"] = s["a"] and s["b"] and s["c"] and s["d"]
    SEL = pd.DataFrame(sel)
    print("\nSELECTION (kappa 0.02, pre-holdout)\n", fmt(SEL))
    plan = list(BASE)
    elig = [s["und"] for s in sel if s["eligible"]]
    cur = base_s["net_day"]
    while elig:
        gains = {u: walk(T, tuple(plan) + (u,), 0.02, START, HOLD)[0]["net_day"] for u in elig}
        u = max(gains, key=gains.get)
        if gains[u] <= cur:
            break
        plan.append(u)
        cur = gains[u]
        elig.remove(u)
    print("\nPLAN:", plan, "pre Rs/day", round(cur, 2))
    # portfolio table pre (info): base, base+X each, plan, kappa .02/.04, full pre and era M
    W = []
    combos = [BASE] + [BASE + (u,) for u in CANDS] + ([tuple(plan)] if len(plan) > 2 else [])
    for cb in combos:
        for k in (0.02, 0.04):
            for lo, nm in ((START, "pre 2021-10..2025-09"), (REG, "eraM 2024-12..2025-09")):
                s, _ = walk(T, cb, k, lo, HOLD)
                W.append(dict(window=nm, **s))
    W = pd.DataFrame(W)
    W.to_csv(os.path.join(OUT, "pre_portfolio.csv"), index=False)
    print("\nPORTFOLIOS (fixed 1 lot each, Rs 1 L, free-cash check)\n",
          fmt(W[["window", "book", "kappa", "taken", "skipped_cash", "net_day", "gross_day", "t", "boot_p", "end_cap",
                 "min_cap", "maxdd", "worst_day", "green_months"]]))
    # per-year net/day of the base and each candidate (kappa 0.02, 1 lot, alone)
    Y = {}
    for u in BASE + CANDS:
        t = T[T.und == u]
        if t.empty:
            continue
        Y[u] = {y: round(float(daily(t, 0.02, max(pd.Timestamp(f"{y}-01-01"), START), min(pd.Timestamp(f"{y+1}-01-01"), HOLD)).mean()), 1)
                for y in range(2021, 2026) if (t.day.dt.year == y).any()}
    print("\nPER YEAR net Rs/day, 1 lot alone, kappa 0.02 (years with trades)\n", pd.DataFrame(Y).T.to_string())
    json.dump(dict(plan=plan, pre_day=cur, base_pre_day=base_s["net_day"], selection=SEL.to_dict("records")),
              open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=str)


def hold():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    plan = tuple(ch["plan"])
    T, P = load()
    rows = []
    for u in BASE + CANDS:
        for k in (0.02, 0.04):
            rows.append(index_stats(T, P, u, k, HOLD, END))
    S = pd.DataFrame(rows)
    S.to_csv(os.path.join(OUT, "hold_index.csv"), index=False)
    print("PER INDEX, 1 lot, HOLDOUT\n", fmt(S[["und", "kappa", "span", "trades", "missed_fill", "net", "gross", "net_day",
                                               "per_trade", "spread_extra_per_trade", "median_hs_pct", "t", "boot_p",
                                               "rand_real", "rand_mean", "rand_p", "maxdd", "worst_day", "green_months"]]))
    W = []
    combos = [BASE, plan] + [BASE + (u,) for u in CANDS if u not in plan]
    seen = set()
    for cb in combos:
        if cb in seen:
            continue
        seen.add(cb)
        for k in (0.02, 0.04, 0.01):
            s, dn = walk(T, cb, k, HOLD, END)
            W.append(dict(role="PLAN" if cb == plan else ("base" if cb == BASE else "info"), **s))
            if cb == plan and k == 0.02:
                print("PLAN monthly net (k .02):\n", dn.groupby(dn.index.to_period("M")).sum().round(0).to_string())
    W = pd.DataFrame(W)
    W.to_csv(os.path.join(OUT, "hold_portfolio.csv"), index=False)
    print("\nPORTFOLIOS HOLDOUT (from Rs 1 L)\n",
          fmt(W[["role", "book", "kappa", "taken", "skipped_cash", "net_day", "gross_day", "t", "boot_p", "end_cap",
                 "min_cap", "maxdd", "worst_day", "green_months"]]))
    # all-index (info) portfolio
    s, _ = walk(T, BASE + CANDS, 0.02, HOLD, END)
    print("\nINFO all 7 indices at 1 lot, k .02:", {k: (round(v, 1) if isinstance(v, float) else v) for k, v in s.items()})


def val():
    """h17 reproduction: BN1+M1 on h15's no-mask table through this walk (expect pre Rs 111/day)."""
    with open(os.path.join(SCR, "hunt/h15/cache/h15/tab_real.pkl"), "rb") as f:
        res = pickle.load(f)
    rows = []
    for u in BASE:
        meta, d = res[u]
        a = d["E2_k02"]
        t = meta[["und", "book", "day", "entry_min"]].copy()
        t["day"] = pd.to_datetime(t.day)
        t["net_0.02"], t["gross_0.02"], t["prem_0.02"] = a["net"][:, 1], a["gross"][:, 1], a["prem"][:, 1]
        t["end_0.02"], t["lots_0.02"] = a["end"][:, 1], np.where(a["lots"][:, 1] > 0, 1, 1)
        rows.append(t)
    T = pd.concat(rows, ignore_index=True)
    T["net_0.02"] = T["net_0.02"].fillna(0.0)
    T["gross_0.02"] = T["gross_0.02"].fillna(0.0)
    s, _ = walk(T, BASE, 0.02, START, HOLD)
    print("h17 reproduction BN1+M1 pre (h17: 111/day net, 239 gross):", {k: s[k] for k in ("net_day", "gross_day", "taken", "skipped_cash", "end_cap")})
    s, _ = walk(T, ("BANKNIFTY",), 0.02, START, HOLD)
    print("BN1 pre (h17: 82/day; 81,322 total):", round(s["net_day"], 1), round(s["net"]))


if __name__ == "__main__":
    {"pre": pre, "hold": hold, "val": val, "sens": lambda: None}[sys.argv[1]]()


def sens():
    """POST-HOC sensitivities (info only, no re-choice): which modelling choice drives the result."""
    rows = []
    for nm in ("trades", "trades_noblock", "trades_hs0"):
        T = pd.read_parquet(os.path.join(OUT, nm + ".parquet"))
        T["day"] = pd.to_datetime(T["day"])
        for lo, hi, w in ((START, HOLD, "pre"), (REG, HOLD, "eraM"), (HOLD, END, "hold")):
            r = dict(file=nm, window=w)
            for u in BASE + CANDS:
                t = T[(T.und == u) & (T.day >= lo) & (T.day < hi)]
                r[u] = round(float(t["net_0.02"].sum()))
            for cb in (BASE, BASE + ("FINNIFTY",), BASE + ("BANKEX",)):
                s, _ = walk(T, cb, 0.02, lo, hi)
                r["Rs/day " + "+".join(x[:4] for x in cb)] = round(s["net_day"], 1)
            rows.append(r)
    D = pd.DataFrame(rows)
    D.to_csv(os.path.join(OUT, "sens.csv"), index=False)
    print("net Rs per index (1 lot alone, k .02) and portfolio Rs/day from Rs 1 L\n", D.to_string())


if __name__ == "__main__" and sys.argv[1] == "sens":
    sens()
