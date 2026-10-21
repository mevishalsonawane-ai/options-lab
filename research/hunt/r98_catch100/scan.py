"""R98 step B (descriptive, DESIGN only): which conditions tilt the 100-point coin?

For every feature, quintile buckets (flags: True / False) of the future bracket label (target +100 / stop -100, hold
60 min; gross points before costs) and of the option bracket (+100 / -50 premium points, 60 min, net after costs).
Uncertainty: day-clustered (each session's rows are averaged first; t = mean of day means / its standard error),
because entries 5 minutes apart overlap. BH over every bucket tested.

    python -m research.hunt.r98_catch100.scan
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
from scipy import stats

from research.hunt.r96_mcx_buy.study import bh
from research.hunt.r98_catch100 import cfg as K

LABEL = "fp_100_60"
SKIP = {"day", "tod_", "side", "i_sig", "F", "fut_cost", "mfe60", "mae60", "oo", "onet", "omin", "ofill"}


def feature_cols(df):
    return [c for c in df.columns if c not in SKIP and not c.startswith(("fo_", "fp_")) and df[c].dtype != object
            and df[c].nunique() > 1]


def clustered(g: pd.DataFrame, col: str):
    dm = g.groupby("day")[col].mean().dropna()
    if len(dm) < 10:
        return np.nan, np.nan, len(dm)
    t = dm.mean() / (dm.std(ddof=1) / np.sqrt(len(dm))) if dm.std(ddof=1) > 0 else np.nan
    return float(dm.mean()), float(2 * stats.t.sf(abs(t), len(dm) - 1)) if np.isfinite(t) else np.nan, len(dm)


def scan(df: pd.DataFrame) -> list:
    out = []
    for c in feature_cols(df):
        x = df[c]
        if x.nunique() <= 3:
            groups = [(f"={v:g}", df[x == v]) for v in sorted(x.dropna().unique())]
        else:
            qs = np.unique(np.nanquantile(x, [0.2, 0.4, 0.6, 0.8]))
            lab = np.digitize(x, qs)
            groups = []
            for k in range(len(qs) + 1):
                lo = "-inf" if k == 0 else f"{qs[k - 1]:.4g}"
                hi = "inf" if k == len(qs) else f"{qs[k]:.4g}"
                groups.append((f"{lo}..{hi}", df[(lab == k) & x.notna()]))
        for name, g in groups:
            if len(g) < 500:
                continue
            m, p, nd = clustered(g, LABEL)
            om = g.onet.dropna()
            out.append(dict(feature=c, bucket=name, n=int(len(g)), days=nd, fut_gross=m, p=p,
                            hit=float((g["fo_100_60"] == 1).mean()), opt_n=int(len(om)),
                            opt_net=float(om.mean()) if len(om) else np.nan,
                            opt_hit=float((g.oo == 1).mean()) if g.oo.notna().any() else np.nan))
    q = bh([r["p"] for r in out])
    for r, x in zip(out, q):
        r["q"] = x
    return out


def main():
    df = pd.read_parquet(K.CACHE / "dataset_design.parquet")
    df["tod_bucket"] = (df.tod // 60).astype(int)
    rows = scan(df)
    rows.sort(key=lambda r: -abs(r["fut_gross"]) if np.isfinite(r["fut_gross"]) else 0)
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "scan.json").write_text(json.dumps(dict(label=LABEL, rows=len(df), buckets=len(rows),
                                                          table=rows), indent=1, default=str))
    return rows


if __name__ == "__main__":
    rs = main()
    for r in rs[:40]:
        print(f"{r['feature']:18s} {r['bucket']:22s} n {r['n']:6d} fut {r['fut_gross']:+6.1f} hit {r['hit']:.3f} "
              f"p {r['p']:.4f} q {r['q']:.3f} | opt n {r['opt_n']:5d} net {r['opt_net']:+6.1f} hit {r['opt_hit']:.3f}")
    print("buckets", len(rs), "with q<=0.10:", sum(r["q"] <= 0.10 for r in rs))
