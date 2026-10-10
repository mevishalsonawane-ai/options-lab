"""h25 stage 4: the ONE look at the locked holdout (2025-10-01 ..) for every gate passer, plus (information only) the
pre-registered top 20 by pre-holdout t-stat. Also full pre-holdout detail and real B = 1000 random-entry draws.

    OBUY_CACHE=<scratch>/hunt/h25/cache python3 -I research/hunt/h25/final.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402
import evaluate as EV  # noqa: E402

OUT = EV.OUT
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 60)


def rand_p(tr, tb, k, B=1000, coin=False, seed=11):
    if len(tr) < 5:
        return np.nan
    rng = np.random.default_rng(seed)
    r = tb[f"r_{k}"]
    di = tr.di.values
    sx = (tr.side.values > 0).astype(np.int64)
    n = len(tr)
    means = np.empty(B)
    for b in range(B):
        s = rng.integers(0, 2, n) if coin else sx
        m = rng.integers(0, EV.MW, n)
        v = r[di, m, s]
        for _ in range(6):
            bad = np.isnan(v)
            if not bad.any():
                break
            m[bad] = rng.integers(0, EV.MW, bad.sum())
            v = r[di, m, s]
        means[b] = np.nanmean(v)
    real = tr.net.mean()
    return (1 + (means >= real).sum()) / (B + 1)


def stats(tr, ndays):
    if not len(tr):
        return dict(n=0)
    d = tr.groupby("ord").net.sum()
    eq = d.cumsum().values
    dd = float((eq - np.maximum.accumulate(np.r_[0, eq])[1:]).min()) if len(eq) else 0.0
    mon = tr.assign(m=[pd.Timestamp.fromordinal(int(o)).strftime("%Y-%m") for o in tr.ord]).groupby("m").net.sum()
    w = tr.net[tr.net > 0].sum()
    lo = -tr.net[tr.net < 0].sum()
    return dict(n=len(tr), net=tr.net.sum(), gross=tr.gross.sum(), stress=tr.stress.sum(), per_tr=tr.net.mean(),
                win=(tr.net > 0).mean(), pf=w / lo if lo > 0 else np.inf, rs_day=tr.net.sum() / ndays,
                gross_day=tr.gross.sum() / ndays, stress_day=tr.stress.sum() / ndays, worst_day=d.min(),
                worst_month=mon.min(), months_neg=(mon < 0).mean(), max_dd=dd, prem_lot=tr.prem.mean(),
                vs_rand_tr=(tr.net - tr.m0).mean())


def main():
    mk = market()
    v = pd.read_parquet(os.path.join(OUT, "variants_pre.parquet"))
    top = pd.read_csv(os.path.join(OUT, os.environ.get("H25_TOP", "top20_new.csv")))
    pas = pd.read_csv(os.path.join(OUT, "passers.csv"))
    old = os.path.join(OUT, "run7/passers.csv")          # AMENDMENT 1: the 7-exit passers were already looked at once
    if os.path.exists(old):
        o = pd.read_csv(old)[["und", "tf", "sig", "orient", "exit"]].assign(seen=1)
        pas = pas.merge(o, how="left", on=["und", "tf", "sig", "orient", "exit"])
        pas = pas[pas.seen.isna()].drop(columns="seen")
    fp = pd.read_csv(os.path.join(OUT, "fam_passers.csv"))
    lst = [("top20", r) for r in top.itertuples()] + [("variant_gate", r) for r in pas.itertuples()]
    for r in fp.itertuples():          # a passing family trades its best pre-holdout variant in the holdout
        g = v[(v.sig == r.sig) & (v.orient == r.orient) & (v.n >= 30)]
        lst.append(("family_gate", g.loc[g.net.idxmax()]))
    rows = []
    for why, r in lst:
        u, tf, sig, orient, k = r.und, int(r.tf), r.sig, r.orient, r.exit
        tr = EV.trades_for(u, tf, sig, orient, k, mk)
        tb = EV._TAB[u]
        ix = mk.index(u)
        allord = np.array([d.toordinal() for d in ix.days])
        nd_pre = int((allord < EV.HOLD).sum())
        nd_hold = int((allord >= EV.HOLD).sum())
        row = dict(why=why, und=u, tf=tf, sig=sig, orient=orient, exit=k, q=getattr(r, "q", np.nan))
        for per, m, nd in (("pre", tr.pre.values, nd_pre), ("hold", ~tr.pre.values, nd_hold)):
            t = tr[m]
            s = stats(t, nd)
            s["p_rand"] = rand_p(t, tb, k)
            s["p_coin"] = rand_p(t, tb, k, coin=True)
            row.update({f"{per}_{a}": b for a, b in s.items()})
        rows.append(row)
        print(why, u, tf, sig, orient, k, {a: (round(b, 3) if isinstance(b, float) else b) for a, b in row.items()
                                           if a.startswith(("pre_n", "pre_net", "pre_rs_day", "pre_p_rand", "hold_n",
                                                            "hold_net", "hold_rs_day", "hold_gross_day", "hold_p_rand"))},
              flush=True)
    df = pd.DataFrame(rows)
    df.to_csv(os.path.join(OUT, os.environ.get("H25_FINAL", "final_new.csv")), index=False)
    print(df.to_string())


if __name__ == "__main__":
    main()
