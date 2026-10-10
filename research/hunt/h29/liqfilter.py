"""h29 test 2b: option-pricing features as filters on Liquidity 15+5's own trades (h4 trades_real: liq_bnfin +
liq_ext, 1 lot, app fills/costs) with the real half-spread added (h24), 1.5x stress too. See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h29/cache python3 -I research/hunt/h29/liqfilter.py pre
    OBUY_CACHE=<scratch>/hunt/h29/cache python3 -I research/hunt/h29/liqfilter.py hold
Features at the signal bar (m = sig_min, the bar whose close fires the arm; entry is the next minute).
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.CACHE, "h29")
HOLD = pd.Timestamp("2025-10-01")
SPLIT = pd.Timestamp("2024-01-01")
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "SENSEX": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042}
pd.set_option("display.width", 250)
FEATS = [("cheap1", "low"), ("cheap2", "low"), ("iv", "low"), ("a_dbz", "high"), ("a_drz", "high"), ("a_rr", "high")]


def load():
    t = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/trades_real.parquet"))
    t = t[t.comp.isin(["liq_bnfin", "liq_ext"])].copy()
    t["day"] = pd.to_datetime(t.day)
    hs = t.und.map(HS).values
    leg = (t.entry.values + t.exit.values) * t.qty.values
    t["net_s"] = t.net - np.maximum(hs - 0.0005, 0) * leg
    t["net_s15"] = t.net - np.maximum(1.5 * hs - 0.0005, 0) * leg
    parts = []
    for u, g in t.groupby("und"):
        F = pd.read_parquet(os.path.join(OUT, f"feat_{u}.parquet"),
                            columns=["day", "m", "cheap1", "cheap2", "iv", "dbz", "drz", "rr"])
        g = g.merge(F.rename(columns={"m": "sig_min"}).astype({"sig_min": int}), on=["day", "sig_min"], how="left")
        parts.append(g)
    t = pd.concat(parts, ignore_index=True)
    t["a_dbz"] = t.side * t.dbz
    t["a_drz"] = t.side * t.drz
    t["a_rr"] = t.side * t.rr
    return t


def edges(t):
    p = t[t.day < HOLD]
    return {f: p.groupby("und")[f].quantile([1 / 3, 2 / 3]).unstack() for f, _ in FEATS}


def terc(t, f, E):
    e = E[f].reindex(t.und.values)
    x = t[f].values
    out = (x > e[1 / 3].values).astype(float) + (x > e[2 / 3].values).astype(float)
    out[~np.isfinite(x)] = np.nan
    return out


def rules(t, E):
    R = {}
    for f, good in FEATS:
        tt = terc(t, f, E)
        if good == "low":
            R[f"{f}:keep_T1"] = tt == 0
            R[f"{f}:drop_T3"] = (tt == 0) | (tt == 1)
        else:
            R[f"{f}:keep_T3"] = tt == 2
            R[f"{f}:drop_T1"] = (tt == 1) | (tt == 2)
    return R


def perm_p(x, keep, B=5000, seed=11):
    rng = np.random.default_rng(seed)
    n = int(keep.sum())
    obs = x[keep].mean()
    null = np.array([x[rng.choice(len(x), n, replace=False)].mean() for _ in range(B)])
    return (1 + (null >= obs).sum()) / (B + 1)


def bh(p):
    p = np.asarray(p, float)
    m = len(p)
    o = np.argsort(p)
    q = p[o] * m / np.arange(1, m + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(m)
    out[o] = np.minimum(q, 1)
    return out


def summarize(x, days):
    return dict(n=len(x), net_tr=x.net_s.mean(), gross_tr=x.gross.mean(), net15_tr=x.net_s15.mean(),
                net_day=x.net_s.sum() / days, gross_day=x.gross.sum() / days, win=(x.net_s > 0).mean())


def main(mode):
    t = load()
    E = edges(t)
    print("trades", len(t), "with features", t.cheap1.notna().sum(), "by und:", t.groupby("und").size().to_dict())
    print("feature coverage:", {f: round(t[f].notna().mean(), 3) for f, _ in FEATS})
    if mode == "pre":
        p = t[t.day < HOLD].reset_index(drop=True)
        R = rules(p, E)
        halves = [("2021-23", p.day < SPLIT), ("2024-25.09", p.day >= SPLIT)]
        ndays = {nm: p[s].day.nunique() for nm, s in halves}
        ndays_all = p.day.nunique()
        base = {nm: summarize(p[s], ndays[nm]) for nm, s in halves}
        rows = [dict(rule="ALL (unfiltered)", **{f"{nm}_{k}": v for nm in base for k, v in base[nm].items() if k in ("n", "net_tr")},
                     **summarize(p, ndays_all), p=np.nan)]
        for nm, keep in R.items():
            keep = np.asarray(keep)
            r = dict(rule=nm)
            for hn, s in halves:
                x = p[s.values & keep]
                r[f"{hn}_n"] = len(x)
                r[f"{hn}_net_tr"] = x.net_s.mean()
            r.update(summarize(p[keep], ndays_all))
            r["p"] = perm_p(p.net_s.values, keep)
            r["both_halves_up"] = all(r[f"{hn}_net_tr"] > base[hn]["net_tr"] for hn, _ in halves)
            rows.append(r)
        T = pd.DataFrame(rows)
        T.loc[1:, "q_bh"] = bh(T.p.values[1:])
        T["adopt"] = T.both_halves_up.fillna(False).astype(bool) & (T.q_bh < 0.05)
        print("\n=== Liquidity 15+5 trades, pre-holdout, 1 lot, net incl. real spread (Rs) ===")
        print(T.round(3).to_string())
        print("\nper und x tercile, net/trade (real spread):")
        for f, _ in FEATS:
            p[f + "_t"] = terc(p, f, E)
            print(f, "\n", p.pivot_table(index="und", columns=f + "_t", values="net_s", aggfunc="mean").round(0).to_string())
        ad = T[T.adopt].rule.tolist()
        json.dump(dict(adopted=ad), open(os.path.join(OUT, "liqfilter_choice.json"), "w"))
        print("\nADOPTED:", ad)
    else:
        ad = json.load(open(os.path.join(OUT, "liqfilter_choice.json")))["adopted"]
        h = t[t.day >= HOLD].reset_index(drop=True)
        nd = h.day.nunique()
        rows = [dict(rule="ALL", **summarize(h, nd))]
        R = rules(h, E)
        for nm in ad:
            rows.append(dict(rule=nm, **summarize(h[np.asarray(R[nm])], nd), p=perm_p(h.net_s.values, np.asarray(R[nm]))))
        print("\n=== HOLDOUT (run once): adopted filters ===")
        print(pd.DataFrame(rows).round(3).to_string())
        # post-hoc, all 12 (not used for anything)
        rows = []
        for nm, keep in R.items():
            rows.append(dict(rule=nm, **summarize(h[np.asarray(keep)], nd)))
        print("\npost-hoc, all 12 rules in the holdout (NOT used for choosing):")
        print(pd.DataFrame(rows).round(2).to_string())


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "pre")
