"""h12: filters / regime sizing for Liquidity 15+5 (see PREREG.md).

    S=<scratch>; OBUY_CACHE=$S/hunt/h12/cache flock $S/obuy.lock python3 -I research/hunt/h12/run.py pre   # choose (< 2025-10-01)
    ... run.py plan    # h10 capacity plan sized to same risk, pre-holdout only
    ... run.py hold    # ONE holdout test of the choice + plan
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
import feat  # noqa: E402
from feat import cap, sim  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import overfit as OF  # noqa: E402
import portfolio as PF  # noqa: E402

HOLD = pd.Timestamp("2025-10-01")
REG = pd.Timestamp("2024-12-01")
START = pd.Timestamp("2021-01-01")
CAL = pd.read_csv(os.path.join(sim.C.SCRATCH, "hunt/h4/cache/h4/daily_real.csv"), index_col=0, parse_dates=True).index
OUT = os.path.join(sim.C.CACHE, "h12")
UNDS = feat.UNDS
PLAN = dict(BANKNIFTY=26, FINNIFTY=3, MIDCPNIFTY=11)
CAPN = dict(BANKNIFTY=287, FINNIFTY=3, MIDCPNIFTY=11)
L_D, L_M = 75000.0, 250000.0
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def load():
    os.makedirs(OUT, exist_ok=True)
    fp = os.path.join(OUT, "trades.pkl")
    P = sim.load()
    tr = sim.base_trades(P["real"][0], cap.EXE)
    pool = sim.base_trades(P["real"][1], cap.EXE, pos=False)
    if os.path.exists(fp):
        tr, pool = pd.read_pickle(fp)
    else:
        for t, pk in ((tr, P["real"][0]), (pool, P["real"][1])):
            b = cap.Book(pk, t)
            o = cap.simulate(b, np.ones(len(t)), 0.0, cap.CAP, 0.0)     # 1 lot, no impact
            t["g1"] = o.gross.values
            t["n1"] = o.net.values
            F = feat.features(t)
            for c in F.columns:
                if c not in t:
                    t[c] = F[c].values
                else:
                    t["f_" + c] = F[c].values
        pd.to_pickle((tr, pool), fp)
    B = {u: cap.Book(P["real"][0], tr[tr.und == u]) for u in UNDS}
    return tr, pool, B


def featframe(t):
    F = t[["side", "room", "dtrend", "m60", "intra", "vixhigh", "vixup", "dte", "gap", "rng"]].copy()
    F["tod"] = t.sig_min.values
    F["dow"] = pd.to_datetime(t.day.values).dayofweek
    return F


def daily(t, w, col="n1", lo=START, hi=HOLD):
    s = (t[col] * w).groupby(t.day).sum().reindex(CAL, fill_value=0.0)
    return s[(s.index >= lo) & (s.index < hi)]


def sharpe(d):
    sd = d.std()
    return d.mean() / sd * np.sqrt(250) if sd > 0 else np.nan


def per_lot(t, w, col="n1", lo=START, hi=HOLD):
    m = ((t.day >= lo) & (t.day < hi)).values
    u = w[m]
    return (t[col].values[m] * u).sum() / max(u.sum(), 1e-9), int((u > 0).sum())


def boot_p(x, B=4000, block=5, seed=7):
    x = np.asarray(x, float)
    T = len(x)
    rng = np.random.default_rng(seed)
    nb = int(np.ceil(T / block))
    st = rng.integers(0, T, size=(B, nb))
    idx = (st[:, :, None] + np.arange(block)[None, None, :]).reshape(B, -1)[:, :T] % T
    mb = x[idx].mean(axis=1)
    return float(((mb - mb.mean() + 0) >= x.mean()).mean()) if x.std() > 0 else 1.0


def table(tr, pool, R, RP, lo, hi):
    db = daily(tr, R["BASE"], lo=lo, hi=hi)
    base_pl, _ = per_lot(tr, R["BASE"], lo=lo, hi=hi)
    pbase, _ = per_lot(pool, RP["BASE"], lo=lo, hi=hi)
    rows, X = [], {}
    for k, w in R.items():
        d = daily(tr, w, lo=lo, hi=hi)
        g = daily(tr, w, "g1", lo=lo, hi=hi)
        pl, n = per_lot(tr, w, lo=lo, hi=hi)
        gpl, _ = per_lot(tr, w, "g1", lo=lo, hi=hi)
        ppl, pn = per_lot(pool, RP[k], lo=lo, hi=hi)
        sc = db.std() / d.std() if d.std() > 0 else 0.0
        ex = d * sc - db
        X[k] = ex
        rows.append(dict(rule=k, trades=n, net_per_lot_trade=pl, gross_per_lot_trade=gpl, uplift=pl - base_pl,
                         rand_net_per_lot_trade=ppl, rand_uplift=ppl - pbase, edge_vs_rand=(pl - base_pl) - (ppl - pbase),
                         net_day=d.mean(), gross_day=g.mean(), sharpe=sharpe(d), riskmatched_net_day=d.mean() * sc,
                         excess_day=ex.mean()))
    return pd.DataFrame(rows).set_index("rule"), X


def pre():
    tr, pool, _ = load()
    R = feat.rules(featframe(tr))
    RP = feat.rules(featframe(pool))
    print("trades", len(tr), "pool", len(pool), "pre trades", int((tr.day < HOLD).sum()))
    print("feature NaN share (real, pre):", tr[tr.day < HOLD][["dtrend", "m60", "vixhigh", "vixup", "dte", "gap", "rng"]].isna().mean().round(3).to_dict())
    T, X = table(tr, pool, R, RP, START, HOLD)
    names = [k for k in R if k != "BASE"]
    Xm = np.column_stack([X[k].values for k in names])
    S = OF.spa(Xm, B=4000)
    T["boot_p"] = [np.nan] + [boot_p(X[k].values) for k in names]
    T["bh_q"] = [np.nan] + list(OF.bh(T.boot_p.values[1:]))
    print("PRE-HOLDOUT (Oct 2021 .. Sep 2025), 1 lot per unit, real fills (net) / bar prints (gross):")
    print(T.round(3).to_string())
    print("SPA / White RC over 28 risk-matched excess series:", S)
    Treg, _ = table(tr, pool, R, RP, REG, HOLD)
    print("REGIME WINDOW (Dec 2024 .. Sep 2025), report only:")
    print(Treg[["trades", "net_per_lot_trade", "uplift", "rand_uplift", "net_day", "sharpe", "riskmatched_net_day"]].round(2).to_string())
    # by index (pre), per lot-trade net for each rule
    byu = {}
    for u in UNDS:
        m = (tr.und == u).values
        byu[u] = {k: per_lot(tr[m], w[m])[0] for k, w in R.items()}
    print("pre net per lot-trade by index:")
    print(pd.DataFrame(byu).round(0).to_string())
    # walk-forward, anchored by year
    wf = []
    for y in (2023, 2024, 2025):
        a, b = pd.Timestamp(f"{y}-01-01"), min(pd.Timestamp(f"{y + 1}-01-01"), HOLD)
        trn = {k: daily(tr, w, lo=START, hi=a) for k, w in R.items()}
        sh = {k: sharpe(v) for k, v in trn.items()}
        pick = max(sh, key=lambda k: sh[k])
        sc = trn["BASE"].std() / trn[pick].std()
        dt = daily(tr, R[pick], lo=a, hi=b) * sc
        dbt = daily(tr, R["BASE"], lo=a, hi=b)
        pl, _ = per_lot(tr, R[pick], lo=a, hi=b)
        bpl, _ = per_lot(tr, R["BASE"], lo=a, hi=b)
        wf.append(dict(year=y, pick=pick, train_sharpe=sh[pick], oos_rm_net_day=dt.mean(), oos_base_net_day=dbt.mean(),
                       oos_excess_day=dt.mean() - dbt.mean(), oos_per_lot=pl, oos_base_per_lot=bpl))
    WF = pd.DataFrame(wf)
    print("walk-forward:"); print(WF.round(3).to_string())
    # per-year per-lot-trade for top rules
    yrs = {}
    for k in T.sort_values("sharpe", ascending=False).index[:8].tolist() + ["BASE"]:
        yrs[k] = {y: per_lot(tr, R[k], lo=pd.Timestamp(f"{y}-01-01"), hi=min(pd.Timestamp(f"{y + 1}-01-01"), HOLD))[0]
                  for y in range(2021, 2026)}
    print("per-year net per lot-trade (top-8 by Sharpe):"); print(pd.DataFrame(yrs).T.round(0).to_string())
    top = T.drop("BASE").sharpe.idxmax()
    r = T.loc[top]
    ywins = int((WF.oos_excess_day > 0).sum())
    ok = (WF.oos_excess_day.sum() > 0) and ywins >= 2 and S["spa_p"] < 0.10 and r.edge_vs_rand > 0 and r.sharpe > T.loc["BASE", "sharpe"]
    choice = top if ok else "BASE"
    print(f"top-Sharpe rule {top}: sharpe {r.sharpe:.2f} vs BASE {T.loc['BASE','sharpe']:.2f}; WF years>0 {ywins}/3, "
          f"WF sum {WF.oos_excess_day.sum():.1f}; SPA p {S['spa_p']:.3f}; edge vs rand {r.edge_vs_rand:.1f} -> CHOICE {choice}")
    T.to_csv(os.path.join(OUT, "pre_rules.csv"))
    Treg.to_csv(os.path.join(OUT, "regime_rules.csv"))
    WF.to_csv(os.path.join(OUT, "wf.csv"), index=False)
    json.dump(dict(choice=choice, top=top, spa=S, wf=wf, n_rules=len(names)), open(os.path.join(OUT, "choice.json"), "w"),
              indent=1, default=float)


# ---------------------------------------------------------------- h10 capacity plan at the same risk
def plan_trades(tr, B, units, lots, kappa=cap.KAPPA, c=cap.CAP):
    parts = []
    for u in UNDS:
        m = (tr.und == u).values
        w = units[m]
        keep = w > 0
        b = B[u]
        n0 = w * lots[u]
        if u != "BANKNIFTY":
            n0 = np.minimum(n0, 2 * CAPN[u])
        o = cap.simulate(b, np.where(keep, n0, 1.0), 0.0, c, kappa)
        t = b.tr[["cand", "und", "book", "day", "entry_min", "exit_min"]].copy()
        for col in o.columns:
            t[col] = o[col].values
        parts.append(t[keep])
    return pd.concat(parts, ignore_index=True)


def pdaily(t, lo, hi=None, col="net"):
    s = t.groupby("day")[col].sum().reindex(CAL, fill_value=0.0)
    s = s[s.index >= lo]
    return s[s.index < hi] if hi is not None else s


def pstats(t, lo, hi=None):
    d = pdaily(t, lo, hi)
    st = PF.stats_row(d.values, d.index)
    m = (t.day >= lo) & ((t.day < hi) if hi is not None else True)
    x = t[m]
    return dict(net_day=d.mean(), gross_day=pdaily(t, lo, hi, "gross").mean(), sd_day=d.std(), sharpe=st["sharpe"],
                worst_day=st["worst_day"], worst_month=st["worst_month"], losing_months=st["losing_months"],
                maxdd=st["maxdd"], P_lose_month=PF.p_losing_month(d.values, B=4000), trades=len(x),
                net_per_lot_trade=x.net.sum() / max(x.lots.sum(), 1), lots_mean=x.lots.mean())


def solve(tr, B, units, sd_target, grid):
    best = None
    for v in grid:
        t = plan_trades(tr, B, units, dict(PLAN, BANKNIFTY=v))
        sd = pdaily(t, REG, HOLD).std()
        if best is None or abs(sd - sd_target) < abs(best[1] - sd_target):
            best = (v, sd)
    return best[0]


def plan(hold=False):
    tr, pool, B = load()
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    R = feat.rules(featframe(tr))
    rules_ = ["BASE"] + sorted({ch["choice"], ch["top"]} - {"BASE"})
    base_t = plan_trades(tr, B, R["BASE"], PLAN)
    sd0 = pdaily(base_t, REG, HOLD).std()
    rows = []
    sizes = {}
    grid = list(range(4, 41)) + list(range(42, 81, 2))
    for k in rules_:
        if k == "BASE":
            bn = PLAN["BANKNIFTY"]
        else:
            bn = solve(tr, B, R[k], sd0, grid)
        sizes[k] = bn
        for kap in (0.02, 0.01, 0.04):
            t = plan_trades(tr, B, R[k], dict(PLAN, BANKNIFTY=bn), kappa=kap)
            wins = [("regime-pre", REG, HOLD), ("2023-06..2025-09", pd.Timestamp("2023-06-01"), HOLD)]
            if hold:
                wins.append(("HOLDOUT", HOLD, None))
            for wl, lo, hi in wins:
                r = dict(rule=k, bn_lots_per_unit=bn, kappa=kap, window=wl)
                r.update(pstats(t, lo, hi))
                rows.append(r)
            if hold and kap == 0.02:
                keep = cap.gate(t, L_D, L_M)
                r = dict(rule=k + "+limits", bn_lots_per_unit=bn, kappa=kap, window="HOLDOUT")
                r.update(pstats(t[keep].reset_index(drop=True), HOLD))
                rows.append(r)
    Rr = pd.DataFrame(rows)
    print("regime-window daily sd target (BASE plan BN26/FIN3/MIDCP11):", round(sd0))
    print(Rr.round(3).to_string())
    Rr.to_csv(os.path.join(OUT, "plan_hold.csv" if hold else "plan_pre.csv"), index=False)
    json.dump(sizes, open(os.path.join(OUT, "plan_sizes.json"), "w"))


def hold():
    tr, pool, B = load()
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    R = feat.rules(featframe(tr))
    RP = feat.rules(featframe(pool))
    T, X = table(tr, pool, R, RP, HOLD, pd.Timestamp("2100-01-01"))
    print("HOLDOUT (2025-10-01 ..), 1 lot per unit. choice:", ch["choice"], "top:", ch["top"])
    print(T.round(3).to_string())
    T.to_csv(os.path.join(OUT, "hold_rules.csv"))
    for k in sorted({"BASE", ch["choice"], ch["top"]}):
        m = tr.day >= HOLD
        mr = (m & (R[k] > 0)).values
        mp = ((pool.day >= HOLD) & (RP[k] > 0)).values
        rb = OF.random_baseline(tr[mr].assign(net=tr.n1[mr]), pool[mp].assign(net=pool.n1[mp]))
        print(k, "holdout random-entry baseline (per trade, 1 lot):", {a: round(v, 4) if isinstance(v, float) else v for a, v in rb.items()})
    plan(hold=True)


if __name__ == "__main__":
    {"pre": pre, "plan": lambda: plan(False), "hold": hold}[sys.argv[1]]()
