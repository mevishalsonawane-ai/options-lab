"""Per-row quality flags. A flag describes a row; it never changes a value. Missing stays NaN.

  ok       a real bar for this minute that passed the checks
  stale    no new information this minute: the STALE_RUN-th (and later) flat bar in a row repeating the previous close
           (index; flagged from that bar on, never backwards), or
           an option that did not trade this minute (its LTP is the last real trade, carried with its age)
  gap      no data for this minute (values NaN)
  suspect  a bar that fails a sanity check (OHLC inconsistent, non-positive, an outsized 1-minute jump, an option
           below intrinsic value) -- kept as is so it can be inspected, but features treat it as missing
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import config


def series_flags(o, h, l, c, jump=config.SUSPECT_JUMP, stale_run=config.STALE_RUN) -> np.ndarray:
    """Flags for one OHLC series already on the grid (NaN rows are gaps). o/h/l may equal c for close-only series."""
    o, h, l, c = (np.asarray(a, float) for a in (o, h, l, c))
    n = len(c)
    flag = np.full(n, "ok", dtype=object)
    gap = ~np.isfinite(c)
    flag[gap] = "gap"
    prev = pd.Series(c).ffill().shift(1).values
    with np.errstate(invalid="ignore", divide="ignore"):
        bad = (c <= 0) | (h < np.fmax(o, c) - 1e-9) | (l > np.fmin(o, c) + 1e-9) | (np.abs(c / prev - 1) > jump)
    # stale: a flat bar equal to the previous close that ends a run of at least stale_run such bars. Only the bars from
    # the stale_run-th on are marked: marking the start of the run would need later bars (look-ahead).
    flat = (o == h) & (h == l) & (l == c) & (c == prev)
    run = np.zeros(n, int)
    for i in range(n):
        run[i] = (run[i - 1] + 1 if i else 1) if flat[i] else 0
    stale = run >= stale_run
    flag[stale & ~gap] = "stale"
    flag[bad & ~gap] = "suspect"
    return flag


def counts(flags) -> dict:
    s = pd.Series(np.asarray(flags).ravel())
    return {f: int((s == f).sum()) for f in config.FLAGS}
