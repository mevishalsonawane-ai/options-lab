"""Is a long wick's level really 'defended', or is it just farther away? (descriptive, labelled post-hoc: added after
the design atlas showed big-tail lows broken less often than matched candles' lows).

For each family, the share of candles whose LOW (or HIGH) is traded through within the next 10 candles of the session,
beside the same share for ALL candles with the same distance from the close to that level (distance bins of 0.1 ATR,
same instrument and timeframe): the distance-matched baseline. Design window only (or the period passed).
"""
from __future__ import annotations

import json
import sys

import numpy as np
import pandas as pd

from . import build as B
from . import cfg

FAMS = ("big_lower_wick", "big_upper_wick", "both_big_wicks", "small_both_wicks", "hammer", "shooting_star",
        "dragonfly_doji", "gravestone_doji", "doji", "marubozu_green", "marubozu_red", "fp_stop_run_down",
        "fp_stop_run_up", "fp_heavy_lower_wick", "fp_heavy_upper_wick")


def matched_rate(dist: np.ndarray, hit: np.ndarray, sel: np.ndarray) -> tuple[float, float, int]:
    ok = np.isfinite(dist) & np.isfinite(hit)
    b = np.clip(np.floor(np.nan_to_num(dist, nan=-1.0) / 0.1), 0, 40).astype(int)
    tab = pd.DataFrame(dict(b=b[ok], h=hit[ok])).groupby("b").h.mean()
    s = sel & ok
    exp = tab.reindex(b[s]).to_numpy()
    return float(np.nanmean(hit[s])), float(np.nanmean(exp)), int(s.sum())


def run(frm, to) -> list:
    out = []
    for u in cfg.UNDS:
        for tf in (5, 15):
            k = B.load(u, tf, frm, to)
            atr = k.atr.to_numpy(float)
            dl = (k.c - k.l).to_numpy(float) / atr
            dh = (k.h - k.c).to_numpy(float) / atr
            for f in FAMS:
                sel = k[f].to_numpy(bool)
                if sel.sum() < cfg.MIN_N:
                    continue
                lo, lo_m, n = matched_rate(dl, k.f_lobrk10.to_numpy(float), sel)
                hi, hi_m, _ = matched_rate(dh, k.f_hibrk10.to_numpy(float), sel)
                out.append(dict(und=u, tf=tf, family=f, n=n, low_broken=round(lo, 3), low_broken_same_distance=round(lo_m, 3),
                                high_broken=round(hi, 3), high_broken_same_distance=round(hi_m, 3)))
    return out


if __name__ == "__main__":
    tag = sys.argv[1] if len(sys.argv) > 1 else "design"
    frm, to = (cfg.START, cfg.DESIGN_END) if tag == "design" else (cfg.FINAL_START, cfg.END)
    if tag != "design" and not cfg.FINAL_FLAG.exists():
        raise SystemExit("the holdout is read by final.py first")
    r = run(frm, to)
    (cfg.RESULTS / f"wick_check_{tag}.json").write_text(json.dumps(r, indent=1))
    print(pd.DataFrame(r).to_string())
