"""STRAD-INDEX rules: signals, P&L of all 2,520 pre-registered variants, statistics, walk-forward, holdout.

    python3 -I research/hunt/strad_index/rules.py design     # pre-holdout only; writes chosen.json
    python3 -I research/hunt/strad_index/rules.py holdout    # ONCE, after design
"""
from __future__ import annotations

import json
import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.stats import norm, t as tdist  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs  # noqa: E402
from obuy.overfit import bh, spa  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/strad_index")
HOLD = pd.Timestamp("2025-10-01")
OOS0 = pd.Timestamp("2022-01-01")
HN = ["15", "30", "60", "120", "eod"]
HMIN = {"15": 15, "30": 30, "60": 60, "120": 120}
EN = ["T", "PT20", "PT40", "SL25", "SL40", "PT20SL25", "PT40SL40"]
LOT = {"NIFTY": 65, "BANKNIFTY": 30}
HSP = {0: 0.0016, 1: 0.0020}          # half-spread per leg: straddle (ATM), strangle (1-OTM)
CAP = 100_000
QS = (0.7, 0.8, 0.9)
COST = Costs("app")


def filters_list():
    f = [("A", "ALWAYS")]
    f += [("B", f"SIZE{int(q*100)}") for q in QS]
    f += [("C", f"CHEAP{int(q*100)}") for q in QS]
    f += [("D", f"SIZE{int(a*100)}+CHEAP{int(b*100)}") for a in QS for b in QS]
    f += [("E", "R>=1"), ("E", "R>=1+SIZE80")]
    return f


FILTERS = filters_list()


# ---------------------------------------------------------------------------------------------- data
def load_feat():
    F = pd.concat([pd.read_parquet(os.path.join(OUT, f"feat_{u}.parquet")) for u in ("NIFTY", "BANKNIFTY")],
                  ignore_index=True)
    F["day"] = pd.to_datetime(F["day"])
    fc = pd.read_parquet(os.path.join(OUT, "fc.parquet"))
    F = F.merge(fc, on=["und", "day", "s"], how="left")
    F = F.sort_values(["und", "s", "day"]).reset_index(drop=True)
    for h in HN:
        hmin = HMIN[h] if h in HMIN else (355 - F["s"])
        F[f"irv_{h}"] = F["strad"] / (0.7979 * F["spot"]) * np.sqrt(hmin / F["N_rem"])
        F[f"R_{h}"] = F[f"fc_{h}"] / F[f"irv_{h}"]
        g = F.groupby(["und", "s"])
        for q in QS:
            for v in ("fc", "R"):
                F[f"thr_{v}_{h}_{int(q*100)}"] = g[f"{v}_{h}"].transform(
                    lambda x: x.rolling(60, min_periods=20).quantile(q).shift(1))
    return F.sort_values(["und", "day", "s"]).reset_index(drop=True)


def signals(F, h):
    out = {}
    fcv, R = F[f"fc_{h}"].values, F[f"R_{h}"].values
    with np.errstate(invalid="ignore"):
        big = {q: fcv >= F[f"thr_fc_{h}_{int(q*100)}"].values for q in QS}
        chp = {q: R >= F[f"thr_R_{h}_{int(q*100)}"].values for q in QS}
        out["ALWAYS"] = np.ones(len(F), bool)
        for q in QS:
            out[f"SIZE{int(q*100)}"] = big[q]
            out[f"CHEAP{int(q*100)}"] = chp[q]
        for a in QS:
            for b in QS:
                out[f"SIZE{int(a*100)}+CHEAP{int(b*100)}"] = big[a] & chp[b]
        out["R>=1"] = R >= 1.0
        out["R>=1+SIZE80"] = (R >= 1.0) & big[0.8]
    return out


def load_paths(F):
    P = pd.concat([pd.read_parquet(os.path.join(OUT, f"path_{u}.parquet")) for u in ("NIFTY", "BANKNIFTY")],
                  ignore_index=True)
    P["day"] = pd.to_datetime(P["day"])
    key = F[["und", "day", "s"]].reset_index().rename(columns={"index": "fi"})
    P = P.merge(key, on=["und", "day", "s"], how="inner")
    P = P.sort_values(["und", "st", "day", "s"]).reset_index(drop=True)
    lot = P["und"].map(LOT).values.astype(float)
    hs = P["st"].map(HSP).values
    ec, ep = P["e_c"].values.astype(float), P["e_p"].values.astype(float)
    capok = (ec + ep) * (1 + hs) * lot <= CAP
    buyc = COST.charge(True, ec, lot) + COST.charge(True, ep, lot)
    pnl = {}
    for h in HN:
        for e in EN:
            k = f"{h}_{e}"
            if f"x_{k}" not in P:
                continue
            xc, xp = P[f"c_{k}"].values.astype(float), P[f"p_{k}"].values.astype(float)
            ok = capok & np.isfinite(xc) & np.isfinite(xp) & np.isfinite(P[f"x_{k}"].values)
            gross = ((xc + xp) - (ec + ep)) * lot
            sellc = COST.charge(False, np.nan_to_num(xc), lot) + COST.charge(False, np.nan_to_num(xp), lot)
            net = ((xc + xp) * (1 - hs) - (ec + ep) * (1 + hs)) * lot - buyc - sellc
            net2 = ((xc + xp) * (1 - 2 * hs) - (ec + ep) * (1 + 2 * hs)) * lot - buyc - sellc
            pnl[k] = dict(ok=ok, gross=gross, net=net, net2=net2, x=P[f"x_{k}"].values)
    return P, pnl


def greedy(dayn, s, X):
    keep = []
    ld, lx = -1, -1
    for i in range(len(s)):
        if dayn[i] != ld or s[i] >= lx:
            keep.append(i)
            ld, lx = dayn[i], X[i]
    return np.array(keep, dtype=np.int64)


# ---------------------------------------------------------------------------------------------- stats
def stats(tr, ndays, months_all=None):
    """tr: DataFrame(day, gross, net, net2)."""
    n = len(tr)
    if n == 0:
        return dict(n=0)
    d = tr.groupby("day")[["gross", "net", "net2"]].sum()
    eq = d["net"].cumsum()
    dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min())
    mo = tr.groupby(tr["day"].dt.to_period("M"))["net"].sum()
    sd = tr["net"].std(ddof=1) if n > 1 else np.nan
    tstat = tr["net"].mean() / (sd / np.sqrt(n)) if n > 1 and sd > 0 else np.nan
    return dict(n=n, hit=float((tr["net"] > 0).mean()), gross_tr=tr["gross"].mean(), net_tr=tr["net"].mean(),
                net2_tr=tr["net2"].mean(), gross_day=tr["gross"].sum() / ndays, net_day=tr["net"].sum() / ndays,
                net2_day=tr["net2"].sum() / ndays, net_tot=tr["net"].sum(), maxdd=dd,
                green_m=float((mo > 0).mean()), months=len(mo),
                p_t=float(1 - tdist.cdf(tstat, n - 1)) if np.isfinite(tstat) else 1.0)


def rand_p(pool, n, m, B=2000, seed=7):
    """p = P(mean of n random grid trades >= m). Exact draws (without replacement) for n <= 500, else CLT."""
    N = len(pool)
    if n == 0 or N == 0:
        return np.nan
    if n <= 500:
        rng = np.random.default_rng(seed)
        idx = rng.integers(0, N, (B, n))       # with replacement (n << N)
        return float((pool[idx].mean(1) >= m - 1e-9).mean())
    sd = pool.std(ddof=1) / np.sqrt(n) * np.sqrt(max(N - n, 1) / max(N - 1, 1))
    return float(1 - norm.cdf((m - pool.mean()) / sd)) if sd > 0 else float(m <= pool.mean())


# ---------------------------------------------------------------------------------------------- core
def all_variant_trades(F, P, pnl, lo, hi):
    """{variant_id: trade index array into P} for days in [lo, hi)."""
    dayn = P["day"].values.astype("datetime64[D]").astype(np.int64)
    s = P["s"].values
    inper = (P["day"] >= lo).values & (P["day"] < hi).values
    sig = {h: signals(F, h) for h in HN}
    fi = P["fi"].values
    out = {}
    for u in ("NIFTY", "BANKNIFTY"):
        for st in (0, 1):
            base = inper & (P["und"].values == u) & (P["st"].values == st)
            for h in HN:
                for e in EN:
                    k = f"{h}_{e}"
                    if k not in pnl:
                        continue
                    m0 = base & pnl[k]["ok"]
                    for fam, fn in FILTERS:
                        m = m0 & sig[h][fn][fi]
                        idx = np.nonzero(m)[0]
                        keep = idx[greedy(dayn[idx], s[idx], pnl[k]["x"][idx])] if len(idx) else idx
                        out[(u, st, h, e, fam, fn)] = keep
    return out


def trades_df(P, pnl, vid, idx):
    k = f"{vid[2]}_{vid[3]}"
    return pd.DataFrame(dict(day=P["day"].values[idx], s=P["s"].values[idx], x=pnl[k]["x"][idx],
                             gross=pnl[k]["gross"][idx], net=pnl[k]["net"][idx], net2=pnl[k]["net2"][idx],
                             prem=(P["e_c"].values[idx] + P["e_p"].values[idx]) * LOT[vid[0]]))


def vname(v):
    u, st, h, e, fam, fn = v
    return f"{u[:2]}|{'STRAD' if st == 0 else 'STRANG'}|h{h}|{e}|{fn}"


def ndays_of(F, u, lo, hi):
    x = F[(F.und == u) & (F.day >= lo) & (F.day < hi)]
    return x.day.nunique()


def design():
    F = load_feat()
    P, pnl = load_paths(F)
    lo21, hi = pd.Timestamp("2021-01-01"), HOLD
    V = all_variant_trades(F, P, pnl, lo21, hi)
    print("variants", len(V), flush=True)
    nd = {(u, y): ndays_of(F, u, pd.Timestamp(f"{y}-01-01"), min(pd.Timestamp(f"{y+1}-01-01"), HOLD))
          for u in LOT for y in range(2021, 2026)}
    nd_oos = {u: ndays_of(F, u, OOS0, HOLD) for u in LOT}
    rows, yearly, daily = [], [], {}
    dayn = P["day"].values
    for v, idx in V.items():
        tr = trades_df(P, pnl, v, idx)
        oos = tr[tr.day >= OOS0]
        st_ = stats(oos, nd_oos[v[0]])
        k = f"{v[2]}_{v[3]}"
        poolm = (P["und"].values == v[0]) & (P["st"].values == v[1]) & pnl[k]["ok"] & (dayn >= OOS0) & (dayn < HOLD)
        pool = pnl[k]["net"][poolm]
        st_["p_rand"] = rand_p(pool, st_["n"], st_.get("net_tr", np.nan)) if st_["n"] else np.nan
        st_["pool_net_tr"] = pool.mean()
        rows.append(dict(vid=vname(v), und=v[0], st=v[1], h=v[2], exit=v[3], fam=v[4], filt=v[5], **st_))
        tr["year"] = tr.day.dt.year
        for y, g in tr.groupby("year"):
            yearly.append(dict(vid=vname(v), year=y, n=len(g), net=g.net.sum(), net_day=g.net.sum() / nd[(v[0], y)],
                               gross=g.gross.sum()))
        daily[vname(v)] = oos.groupby("day")["net"].sum()
    R = pd.DataFrame(rows)
    R["q_t"] = bh(R["p_t"].fillna(1).values)
    R["q_rand"] = bh(R["p_rand"].fillna(1).values)
    Y = pd.DataFrame(yearly)
    # SPA over all variants, daily net P&L (0 on days without a trade), union of OOS days
    alld = sorted(F[(F.day >= OOS0) & (F.day < HOLD)].day.unique())
    X = np.column_stack([daily[v].reindex(alld).fillna(0).values for v in R.vid])
    sp = spa(X, B=1000)
    # walk-forward per index
    wf = []
    piv = Y.pivot_table(index="vid", columns="year", values="net_day", aggfunc="sum").reindex(R.vid).fillna(0)
    ntr = Y.pivot_table(index="vid", columns="year", values="n", aggfunc="sum").reindex(R.vid).fillna(0)
    for u in LOT:
        mu = (R.und == u).values
        for y in (2022, 2023, 2024, 2025):
            if y == 2022:
                cand = mu & (R.fam == "A").values & (ntr[2021].values >= 30)
                score = piv[2021].values
            else:
                prev = [c for c in range(2022, y)]
                w = np.array([nd[(u, c)] for c in prev], float)
                score = (piv[prev].values * w).sum(1) / w.sum()
                cand = mu & (ntr[prev].sum(1).values >= 30)
            sc = np.where(cand, score, -np.inf)
            j = int(np.argmax(sc))
            res = piv.iloc[j][y] * nd[(u, y)]
            wf.append(dict(und=u, year=y, pick=R.vid.iloc[j], score_day=sc[j], test_net=res,
                           test_net_day=piv.iloc[j][y], test_n=int(ntr.iloc[j][y]),
                           standaside=bool(sc[j] <= 0), primary_net=0.0 if sc[j] <= 0 else res))
    WF = pd.DataFrame(wf)
    # chosen rules for the holdout
    chosen = {}
    for u in LOT:
        r = R[(R.und == u) & (R.n >= 100)]
        chosen[f"{u}|PRIMARY"] = r.sort_values("net_day", ascending=False).iloc[0].vid
        for fam in "ABCDE":
            rr = r[r.fam == fam]
            if len(rr):
                chosen[f"{u}|{fam}"] = rr.sort_values("net_day", ascending=False).iloc[0].vid
    R.to_csv(os.path.join(OUT, "design_variants.csv"), index=False)
    Y.to_csv(os.path.join(OUT, "design_yearly.csv"), index=False)
    WF.to_csv(os.path.join(OUT, "design_wf.csv"), index=False)
    json.dump(dict(chosen=chosen, spa=sp), open(os.path.join(OUT, "chosen.json"), "w"), indent=1, default=float)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 30)
    print("SPA", sp)
    print(WF)
    print("chosen", json.dumps(chosen, indent=1))
    cols = ["vid", "n", "hit", "gross_tr", "net_tr", "net_day", "gross_day", "maxdd", "green_m", "p_t", "q_t", "p_rand", "q_rand"]
    print(R.sort_values("net_day", ascending=False)[cols].head(25).to_string())
    print("share net>0:", (R.net_tr > 0).mean(), " gross>0:", (R.gross_tr > 0).mean(), " min q_t:", R.q_t.min(),
          " min q_rand:", R.q_rand.min())
    print(R.groupby(["und", "fam"])[["net_tr", "gross_tr", "net_day"]].median())
    print(R.groupby(["und", "h"])[["net_tr", "gross_tr", "net_day"]].median())
    print(R.groupby(["und", "st"])[["net_tr", "gross_tr"]].median())


def all_vids():
    return {vname(v): v for v in ((u, st, h, e, fam, fn) for u in LOT for st in (0, 1) for h in HN for e in EN
                                  for fam, fn in FILTERS)}


def one_variant(F, P, pnl, v, lo, hi):
    u, st, h, e, fam, fn = v
    k = f"{h}_{e}"
    sig = signals(F, h)[fn][P["fi"].values]
    m = (P["day"] >= lo).values & (P["day"] < hi).values & (P["und"].values == u) & (P["st"].values == st) \
        & pnl[k]["ok"] & sig
    idx = np.nonzero(m)[0]
    dayn = P["day"].values.astype("datetime64[D]").astype(np.int64)
    return idx[greedy(dayn[idx], P["s"].values[idx], pnl[k]["x"][idx])] if len(idx) else idx


def combined(trs):
    """Both indices' trades together under the Rs 1 lakh cap: skip an entry while open premium + new > CAP."""
    allt = pd.concat([t.assign(u=u) for u, t in trs.items()]).sort_values(["day", "s"]).reset_index(drop=True)
    keep, open_ = [], []
    for i, r in allt.iterrows():
        open_ = [(x, p, d) for (x, p, d) in open_ if d == r.day and x > r.s + 1]
        if sum(p for _, p, _ in open_) + r.prem <= CAP:
            keep.append(i)
            open_.append((r.x, r.prem, r.day))
    return allt.loc[keep]


def holdout():
    ch = json.load(open(os.path.join(OUT, "chosen.json")))["chosen"]
    F = load_feat()
    P, pnl = load_paths(F)
    VID = all_vids()
    end = F.day.max() + pd.Timedelta(days=1)
    nd = {u: ndays_of(F, u, HOLD, end) for u in LOT}
    dayn = P["day"].values
    rows, trs, monthly = [], {}, []
    for key, vid in ch.items():
        v = VID[vid]
        idx = one_variant(F, P, pnl, v, HOLD, end)
        tr = trades_df(P, pnl, v, idx)
        st_ = stats(tr, nd[v[0]])
        k = f"{v[2]}_{v[3]}"
        poolm = (P["und"].values == v[0]) & (P["st"].values == v[1]) & pnl[k]["ok"] & (dayn >= HOLD)
        pool = pnl[k]["net"][poolm]
        st_["p_rand"] = rand_p(pool, st_["n"], st_.get("net_tr", np.nan)) if st_["n"] else np.nan
        st_["pool_net_tr"] = pool.mean()
        rows.append(dict(key=key, vid=vid, **st_))
        if key.endswith("PRIMARY"):
            trs[v[0]] = tr
            mo = tr.groupby(tr.day.dt.to_period("M"))[["gross", "net"]].sum()
            mo["n"] = tr.groupby(tr.day.dt.to_period("M")).size()
            mo["und"] = v[0]
            monthly.append(mo)
    H = pd.DataFrame(rows)
    cb = combined(trs)
    ndc = F[F.day >= HOLD].day.nunique()
    cst = stats(cb, ndc)
    H = pd.concat([H, pd.DataFrame([dict(key="BOTH|PRIMARY (cap 1L)", vid="both primaries", **cst)])])
    H.to_csv(os.path.join(OUT, "holdout_results.csv"), index=False)
    pd.concat(monthly).to_csv(os.path.join(OUT, "holdout_monthly.csv"))
    pd.set_option("display.width", 250)
    pd.set_option("display.max_columns", 30)
    print("holdout", HOLD.date(), "to", F.day.max().date(), "days", nd)
    print(H.to_string())
    print(pd.concat(monthly).to_string())


def gap(period):
    """Descriptive: realised vs implied, by index / horizon / slot / event / year."""
    F = load_feat()
    F = F[(F.day >= OOS0) & (F.day < HOLD)] if period == "design" else F[F.day >= HOLD]
    F = F[F.strad.notna() & (F.strad > 0)]
    ev = np.select([F.expday > 0, F.budget > 0, F.rbi > 0, F.election > 0, F.post_fomc > 0, F.post_cpi > 0,
                    F.down1 > 0], ["expiry day", "budget", "RBI", "election", "post-FOMC", "post-CPI",
                                   "after 1% down day"], "normal day")
    F = F.assign(event=ev, slot=(C.OPEN_M + F.s).map(lambda m: f"{m//60:02d}:{m%60:02d}"), year=F.day.dt.year)
    out = []
    for h in HN:
        hmin = HMIN[h] if h in HMIN else (355 - F["s"])
        G = F[F[f"rv_{h}"].notna()].copy()
        hm = hmin[G.index] if not isinstance(hmin, int) else hmin
        G["mv"], G["im"] = G[f"mv_{h}"], 0.7979 * G[f"irv_{h}"]
        G["rv"], G["irv"], G["fc"] = G[f"rv_{h}"], G[f"irv_{h}"], G[f"fc_{h}"]
        G["rv_ann"] = G["rv"] * np.sqrt(252 * 375 / hm)
        G["iv"] = G["iv_atm"] / 100
        for by in ("und", "slot", "event", "year"):
            for keys, g in G.groupby(["und", by] if by != "und" else ["und"]):
                keys = keys if isinstance(keys, tuple) else (keys,)
                out.append(dict(period=period, h=h, by=by, und=keys[0], group=keys[-1], n=len(g),
                                move_vs_implied=g.mv.mean() / g.im.mean(), rv_vs_irv=g.rv.mean() / g.irv.mean(),
                                fc_vs_irv=g.fc.mean() / g.irv.mean(), rv_vs_fc=g.rv.mean() / g.fc.mean(),
                                rvann_vs_iv=g.rv_ann.mean() / g.iv.mean(),
                                share_move_gt_implied=float((g.mv > g.im).mean())))
    O = pd.DataFrame(out)
    O.to_csv(os.path.join(OUT, f"gap_{period}.csv"), index=False)
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 400)
    for by in ("und", "event", "year"):
        print(O[O.by == by].round(3).to_string())
    print(O[(O.by == "slot") & O.h.isin(["30", "eod"])].round(3).to_string())


if __name__ == "__main__":
    a = sys.argv[1]
    if a == "design":
        design()
    elif a == "holdout":
        holdout()
    elif a == "gap":
        gap(sys.argv[2])
