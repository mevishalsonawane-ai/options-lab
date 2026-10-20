"""MCX labels (MB1): what happens AFTER minute t, kept apart from features (every column starts with y_).

Close-only (PREREG section 1), bounded by the session of one contract segment (NaN when the window runs past the
session's last row). The option-spot series misses some minutes (no option print), so a window needs >= 80% of its
closes, not all of them (the India rule):
  y_range_<h>m     (max - min of the closes at t+1..t+h) / close_t x 100
  y_logrange_<h>m  its log (floor 0.001%)
  y_ret_<h>m       log(end / close_t) x 100, end = the last close in t+h-4 .. t+h (NaN if none)
Labels for the embargo and the holdout are not built by `design()`; the holdout step reads them once.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import config

PREFIX = "y_"
HORIZONS = (15, 30, 60)
MAX_HORIZON_MIN = max(HORIZONS)
MIN_PRESENT = 0.8


def _fwd(a: np.ndarray, grp: np.ndarray, h: int, how: str, min_n: int) -> np.ndarray:
    """how(a[t+1 .. t+h]) inside the group, with >= min_n finite values; NaN if the window leaves the group."""
    s = pd.Series(a[::-1])
    r = s.groupby(grp[::-1]).rolling(h, min_periods=min_n)
    rv = getattr(r, how)().reset_index(level=0, drop=True).sort_index().to_numpy()[::-1]   # rv[t] = how(a[t..t+h-1])
    pos = pd.Series(np.arange(len(a))).groupby(grp).cumcount(ascending=False).to_numpy()   # rows left in the group
    out = np.full(len(a), np.nan)
    out[:-1] = rv[1:]
    out[pos < h] = np.nan
    return out


def compute(m: pd.DataFrame, horizons=HORIZONS) -> pd.DataFrame:
    m = m.sort_values("ts").reset_index(drop=True)
    good = m.flag.isin(["ok", "stale"]).to_numpy()
    c = np.where(good, m.close.to_numpy(float), np.nan)
    grp = pd.factorize(m.seg.astype(str) + "|" + m.ts.dt.date.astype(str))[0]
    out = {"ts": m.ts.values}
    for h in horizons:
        n = int(np.ceil(MIN_PRESENT * h))
        hi, lo = _fwd(c, grp, h, "max", n), _fwd(c, grp, h, "min", n)
        end = pd.Series(c).groupby(grp).shift(-h).to_numpy()
        for k in range(1, 5):                     # the last close in t+h-4 .. t+h
            alt = pd.Series(c).groupby(grp).shift(-(h - k)).to_numpy()
            end = np.where(np.isfinite(end), end, alt)
        end[np.isnan(hi)] = np.nan
        with np.errstate(invalid="ignore", divide="ignore"):
            rng = (hi - lo) / c * 100
            out[f"y_range_{h}m"] = rng
            out[f"y_logrange_{h}m"] = np.where(np.isfinite(rng), np.log(np.fmax(rng, 1e-3)), np.nan)
            out[f"y_ret_{h}m"] = np.log(end / c) * 100
    return pd.DataFrame(out)


def design(m: pd.DataFrame, horizons=HORIZONS) -> pd.DataFrame:
    """Labels of design sessions only (windows stay inside one session, so no design label reads a later day)."""
    return compute(m[m.ts.dt.date <= config.DESIGN_END], horizons)
