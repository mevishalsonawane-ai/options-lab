"""h18: GEX / OI features as filters on Liquidity 15+5's own trades (h4 trades_real.parquet, 1 lot, app costs).

    OBUY_CACHE=<scratch>/hunt/h18/cache python3 -I research/hunt/h18/liqfilter.py pre|hold
Features are read at the last 5-min panel point strictly BEFORE the signal minute's close (col <= sig_min-555-1).
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.CACHE, "h18")
HOLD = pd.Timestamp("2025-10-01")
SPLIT = pd.Timestamp("2024-01-01")
pd.set_option("display.width", 250)


def load():
    t = pd.read_parquet(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/trades_real.parquet"))
    t = t[t.comp.isin(["liq_bnfin", "liq_ext"])].copy()
    t["day"] = pd.to_datetime(t.day)
    P = pd.read_parquet(os.path.join(OUT, "panel_feat.parquet"))
    P = P[["und", "day", "col", "A_share", "A_pos", "B_lvl", "flip_abs", "gexA", "spot", "maxoi", "step"]]
    t["col"] = ((t.sig_min - 555 - 1) // 5) * 5           # last 5-min point strictly before the signal close
    t = t.merge(P, on=["und", "day", "col"], how="left")
    # orientation: is the trade moving TOWARD the max-OI strike (pin magnet) or away?
    t["toward_maxoi"] = np.sign(t.maxoi - t.spot) == t.side
    return t


def per_day(t, lo, hi):
    x = t[(t.day >= lo) & (t.day < hi)]
    return x.net.sum() / max(x.day.nunique(), 1)


def main(mode):
    t = load()
    t = t[t.day < HOLD] if mode == "pre" else t[t.day >= HOLD]
    print(f"trades {len(t)}, with features {t.B_lvl.notna().sum()}  by und:\n", t.groupby("und").size().to_string())
    t = t[t.B_lvl.notna()]
    halves = [("2021-23", pd.Timestamp("2000-01-01"), SPLIT), ("2024-25.09", SPLIT, HOLD)] if mode == "pre" else \
        [("holdout", HOLD, pd.Timestamp("2100-01-01"))]
    rows = []
    # pre-registered filter family: tercile of a feature (edges from the choice window, per underlying)
    E = {}
    tp = load()
    tp = tp[(tp.day < HOLD) & tp.B_lvl.notna()]
    for f in ("B_lvl", "A_share", "flip_abs"):
        E[f] = tp.groupby("und")[f].quantile([1 / 3, 2 / 3]).unstack()
    for f in ("B_lvl", "A_share", "flip_abs"):
        e = E[f].reindex(t.und.values)
        terc = (t[f].values > e[1 / 3].values).astype(int) + (t[f].values > e[2 / 3].values).astype(int)
        t[f + "_t"] = terc
        for k in range(3):
            for nm, lo, hi in halves:
                x = t[(t[f + "_t"] == k) & (t.day >= lo) & (t.day < hi)]
                rows.append(dict(feat=f, tercile=k, half=nm, n=len(x), net_per_trade=x.net.mean(),
                                 win=(x.net > 0).mean(), t=x.net.mean() / (x.net.std() / np.sqrt(len(x))) if len(x) > 2 else np.nan))
    for v in (True, False):
        for nm, lo, hi in halves:
            x = t[(t.toward_maxoi == v) & (t.day >= lo) & (t.day < hi)]
            rows.append(dict(feat="toward_maxoi", tercile=int(v), half=nm, n=len(x), net_per_trade=x.net.mean(),
                             win=(x.net > 0).mean(), t=x.net.mean() / (x.net.std() / np.sqrt(len(x)))))
    R = pd.DataFrame(rows)
    print("\n=== Liquidity 15+5 trades (1 lot, net Rs, app costs) by feature tercile (0 = lowest) ===")
    print(R.round(2).to_string())
    print("\nby und x B_lvl tercile, net/trade:")
    print(t.pivot_table(index="und", columns="B_lvl_t", values="net", aggfunc=["mean", "size"]).round(0).to_string())
    return t, R


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "pre")
