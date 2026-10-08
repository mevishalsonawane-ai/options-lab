"""h28 Part A: index direction / range / intraday timing on calendar-flag days (PRE only, < 2025-10-01).

    OBUY_CACHE=<scratch>/hunt/h28/cache python3 -I research/hunt/h28/index_study.py

Writes <scratch>/hunt/h28/: daily_study.csv (flag x index: n, mean open->close, gap, close->close, % up, range %,
range ratio, Welch t and BH q), timing_study.csv (flag x index x hour bucket: mean |move| %, ratio to non-flag days,
signed mean %), priors.csv (the walk-forward side per flag / index / year used by Part B).
"""
from __future__ import annotations

import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.overfit import bh  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h28")
HOLD = date(2025, 10, 1)
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
BASE = ("und", "day", "open", "high", "low", "close", "opt_era")
BUCKETS = [("09:15-10", 555, 600), ("10-11", 600, 660), ("11-12", 660, 720), ("12-13", 720, 780),
           ("13-14", 780, 840), ("14-15:30", 840, 930)]
PRIOR_SRC = {"MIDCPNIFTY": "NIFTY"}
YEARS = range(2020, 2027)


def welch(a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    a, b = a[np.isfinite(a)], b[np.isfinite(b)]
    if len(a) < 3 or len(b) < 3:
        return np.nan
    se = np.sqrt(a.var(ddof=1) / len(a) + b.var(ddof=1) / len(b))
    return (a.mean() - b.mean()) / se if se > 0 else np.nan


def tp(t, n):
    from math import erf, sqrt
    if not np.isfinite(t):
        return 1.0
    return float(2 * (1 - 0.5 * (1 + erf(abs(t) / sqrt(2)))))


def flags():
    fl = pd.read_parquet(os.path.join(OUT, "flags.parquet"))
    fl["day"] = pd.to_datetime(fl.day).dt.date
    fcols = [c for c in fl.columns if c not in BASE]
    return fl, fcols


def daily_metrics(g):
    g = g.sort_values("day").copy()
    g["oc"] = (g.close / g.open - 1) * 100
    g["gap"] = (g.open / g.close.shift(1) - 1) * 100
    g["cc"] = (g.close / g.close.shift(1) - 1) * 100
    g["rng"] = (g.high - g.low) / g.open * 100
    g["rr"] = g.rng / g.rng.shift(1).rolling(20, min_periods=10).mean()
    return g


def priors(fl, fcols):
    """Walk-forward side per (flag, und, year): sign of the mean open->close on flag days before Jan 1 of the year."""
    rows = []
    for und in UNDS:
        src = PRIOR_SRC.get(und, und)
        g = daily_metrics(fl[fl.und == src])
        for f in fcols:
            for y in YEARS:
                h = g[(g.day < date(y, 1, 1)) & g[f]]
                n = int(h.oc.notna().sum())
                m = float(h.oc.mean()) if n else np.nan
                side = int(np.sign(m)) if n >= 8 and np.isfinite(m) and m != 0 else 0
                rows.append(dict(flag=f, und=und, year=y, n_hist=n, mean_oc_hist=m, side=side))
    return pd.DataFrame(rows)


def daily_study(fl, fcols):
    rows = []
    for und in UNDS:
        g = daily_metrics(fl[fl.und == und])
        g = g[g.day < HOLD]
        for f in fcols:
            a, b = g[g[f]], g[~g[f]]
            if len(a) < 5:
                continue
            r = dict(flag=f, und=und, n=len(a), first=str(g.day.iloc[0]))
            for k in ("oc", "gap", "cc", "rng", "rr"):
                r[f"{k}_flag"] = a[k].mean()
                r[f"{k}_rest"] = b[k].mean()
                r[f"t_{k}"] = welch(a[k], b[k])
            r["up_pct"] = (a.oc > 0).mean() * 100
            r["up_rest"] = (b.oc > 0).mean() * 100
            # recent (2016+) open->close, to show stability
            a2, b2 = a[a.day >= date(2016, 1, 1)], b[b.day >= date(2016, 1, 1)]
            r["oc_flag_16"] = a2.oc.mean() if len(a2) else np.nan
            r["t_oc_16"] = welch(a2.oc, b2.oc)
            rows.append(r)
    df = pd.DataFrame(rows)
    for k in ("oc", "gap", "cc", "rng", "rr"):
        df[f"p_{k}"] = [tp(t, 0) for t in df[f"t_{k}"]]
    allp = np.concatenate([df[f"p_{k}"].values for k in ("oc", "gap", "cc", "rng", "rr")])
    q = bh(allp)
    n = len(df)
    for i, k in enumerate(("oc", "gap", "cc", "rng", "rr")):
        df[f"q_{k}"] = q[i * n:(i + 1) * n]
    return df


def timing_study(fl, fcols):
    from obuy.data import Index
    rows = []
    for und in UNDS:
        ix = Index(und)
        M = ix.mat()
        days = np.array(ix.days)
        pre = np.array([d < HOLD for d in days])
        c = M["c"]
        # open of the day and bucket edges: move = |close at bucket end - close at bucket start| / open
        o0 = np.array([M["o"][i][np.isfinite(M["o"][i])][0] if np.isfinite(M["o"][i]).any() else np.nan
                       for i in range(len(days))])
        cf = pd.DataFrame(c).ffill(axis=1).values
        prevc = np.r_[np.nan, cf[:-1, -1]]
        g = fl[fl.und == und].set_index("day")
        mv = {}
        for nm, a, b in BUCKETS:
            start = prevc if a == 555 else cf[:, a - 555 - 1]
            if a == 555:
                start = o0          # first bucket from the open (the gap is separate)
            end = cf[:, min(b, 930) - 555 - 1]
            mv[nm] = (end - start) / o0 * 100
        hl = (np.nanmax(M["h"], axis=1) - np.nanmin(M["l"], axis=1)) / o0 * 100
        # minute of the day's high / low (when the range is made)
        hi_m = np.nanargmax(np.where(np.isfinite(M["h"]), M["h"], -np.inf), axis=1)
        lo_m = np.nanargmin(np.where(np.isfinite(M["l"]), M["l"], np.inf), axis=1)
        for f in fcols:
            fm = np.array([bool(g[f].get(d, False)) for d in days]) & pre
            rest = (~fm) & pre
            if fm.sum() < 5:
                continue
            r = dict(flag=f, und=und, n=int(fm.sum()), range_flag=np.nanmean(hl[fm]), range_rest=np.nanmean(hl[rest]))
            for nm, _, _ in BUCKETS:
                x = mv[nm]
                r[f"abs_{nm}"] = np.nanmean(np.abs(x[fm]))
                r[f"ratio_{nm}"] = np.nanmean(np.abs(x[fm])) / np.nanmean(np.abs(x[rest]))
                r[f"signed_{nm}"] = np.nanmean(x[fm])
                r[f"t_signed_{nm}"] = welch(x[fm], x[rest])
            r["hi_before_11"] = np.mean(hi_m[fm] < 105) * 100
            r["lo_before_11"] = np.mean(lo_m[fm] < 105) * 100
            r["extreme_after_14"] = np.mean((hi_m[fm] >= 285) | (lo_m[fm] >= 285)) * 100
            r["extreme_after_14_rest"] = np.mean((hi_m[rest] >= 285) | (lo_m[rest] >= 285)) * 100
            rows.append(r)
    return pd.DataFrame(rows)


def main():
    fl, fcols = flags()
    pr = priors(fl, fcols)
    pr.to_csv(os.path.join(OUT, "priors.csv"), index=False)
    ds = daily_study(fl, fcols)
    ds.to_csv(os.path.join(OUT, "daily_study.csv"), index=False)
    ts = timing_study(fl, fcols)
    ts.to_csv(os.path.join(OUT, "timing_study.csv"), index=False)
    pd.set_option("display.width", 250)
    print("daily study rows", len(ds), "| BH q<0.05 on oc:", int((ds.q_oc < 0.05).sum()), "rr:", int((ds.q_rr < 0.05).sum()),
          "cc:", int((ds.q_cc < 0.05).sum()), "gap:", int((ds.q_gap < 0.05).sum()))
    cols = ["flag", "und", "n", "oc_flag", "oc_rest", "t_oc", "q_oc", "oc_flag_16", "t_oc_16", "gap_flag", "q_gap",
            "cc_flag", "q_cc", "rr_flag", "rr_rest", "q_rr", "up_pct"]
    print(ds.sort_values("p_oc")[cols].head(30).round(3).to_string())
    print(ds.sort_values("p_rr")[cols].head(25).round(3).to_string())


if __name__ == "__main__":
    main()
