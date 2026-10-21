"""R99 step B (descriptive, DESIGN only): which index conditions make buying the ATM NIFTY option for +100 premium
points (stop -40, else square-off) pay? One-feature quintile buckets (flags by value) of the net rupees a lot, pooled net with a
day-clustered p, BH over every bucket. Writes results/scan.json.

    python research/hunt/r99_nifty100/r99scan.py
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
from scipy import stats

import r99cfg as K
from research.hunt.r96_mcx_buy.study import bh

LABEL = "net_100"
NOT_FEATURES = {"day", "row", "idx", "E"}


def feature_cols(df):
    return [c for c in df.columns if c not in NOT_FEATURES and not c.startswith(("net_", "pts_", "why_"))
            and df[c].dtype != object and df[c].nunique() > 1]


def clustered(g, col):
    """Pooled mean of the rows, with a day-clustered (cluster-robust) standard error: entries 5 minutes apart on the
    same day are not independent, so each session's summed residual is one draw."""
    y = g[col].astype(float)
    ok = y.notna()
    y, day = y[ok], g.day[ok]
    n, nd = len(y), day.nunique()
    if nd < 10:
        return np.nan, np.nan, nd
    m = float(y.mean())
    e = (y - m).groupby(day).sum()
    se = float(np.sqrt((e ** 2).sum() * nd / (nd - 1)) / n)
    if se == 0:
        return m, np.nan, nd
    return m, float(2 * stats.t.sf(abs(m / se), nd - 1)), nd


def scan(df, label=LABEL):
    out = []
    for c in feature_cols(df):
        x = df[c]
        if x.nunique() <= 6:
            groups = [(f"={v:g}", df[x == v]) for v in sorted(x.dropna().unique())]
        else:
            qs = np.unique(np.nanquantile(x, [0.2, 0.4, 0.6, 0.8]))
            lab = np.digitize(x, qs)
            groups = [((("-inf" if k == 0 else f"{qs[k - 1]:.4g}") + ".." + ("inf" if k == len(qs) else f"{qs[k]:.4g}")),
                       df[(lab == k) & x.notna()]) for k in range(len(qs) + 1)]
        for name, g in groups:
            if len(g) < 300:
                continue
            m, p, nd = clustered(g, label)
            out.append(dict(feature=c, bucket=name, n=int(len(g)), days=nd, net_mean_clustered=m, p=p,
                            net_mean=float(g[label].mean()), target_share=float((g["why_100"] == "target").mean())))
    for r, q in zip(out, bh([r["p"] for r in out])):
        r["q"] = q
    return out


def main():
    df = pd.read_parquet(K.CACHE / "dataset_design.parquet")
    rows = scan(df.dropna(subset=[LABEL]))
    rows.sort(key=lambda r: -(r["net_mean_clustered"] if np.isfinite(r["net_mean_clustered"]) else -1e9))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "scan.json").write_text(json.dumps(dict(label=LABEL, rows=int(len(df)), buckets=len(rows), table=rows),
                                                    indent=1, default=str))
    return rows


if __name__ == "__main__":
    rs = main()
    for r in rs[:25]:
        print(f"{r['feature']:16s} {r['bucket']:20s} n {r['n']:5d} days {r['days']:3d} net {r['net_mean_clustered']:+6.0f} "
              f"pooled {r['net_mean']:+6.0f} tgt {r['target_share']:.3f} p {r['p']:.4f} q {r['q']:.3f}")
    print("buckets", len(rs), "positive with q<=0.10:", sum(r["q"] <= 0.10 and r["net_mean_clustered"] > 0 for r in rs))
