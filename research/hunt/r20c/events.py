"""R20c event logic (PREREG.md part A2 "The event") and the measurements after each event.

    first_break(C, O, prev_close, side, levels_at, n, last_bar)  the first N-close confirmed break of one side
    measure(ix, row, pe, d, level, atr)                          forward moves, fake-out, MFE / MAE (index points)

A candle CROSSES a resistance R when its close is > R and the previous candle's close (the previous session's last close
for candle 0) was <= R (support mirrored). An N-close break is confirmed when the crossing candle and the next N - 1
candles all close strictly beyond the level. levels_at(b) gives the (price, side, key, ...) levels the candle b may cross
(known before b starts); for several crossed levels of one side the farthest one is the event's level.
"""
from __future__ import annotations

import numpy as np

HORIZONS = (15, 30, 60)
LAST_EVENT_ROW = 359          # an event's candle must close by 15:15 (row 359 is the minute 15:14 - 15:15)
FAKE_MIN = 30


def crossed(c_prev: float, c: float, levels, side: int):
    """The farthest level of `side` the close c crossed from c_prev, or None."""
    best = None
    for lv in levels:
        p = lv[0]
        if lv[1] != side:
            continue
        if side > 0 and c_prev <= p < c and (best is None or p > best[0]):
            best = lv
        if side < 0 and c_prev >= p > c and (best is None or p < best[0]):
            best = lv
    return best


def first_break(C: np.ndarray, O: np.ndarray, prev_close: float, side: int, levels_at, n: int, tf: int):
    """The first confirmed n-close break of `side` in the day's candles C (closes) / O (opens).

    Returns dict(b = crossing candle, e = confirming candle, level, key, gap) or None. The confirming candle must close by
    15:15 (row (e + 1) * tf - 1 <= LAST_EVENT_ROW). levels_at(b) must only return levels known before candle b starts."""
    nb = len(C)
    for b in range(nb):
        e = b + n - 1
        if (e + 1) * tf - 1 > LAST_EVENT_ROW:
            return None
        cp = prev_close if b == 0 else C[b - 1]
        lv = crossed(cp, C[b], levels_at(b), side)
        if lv is None:
            continue
        p = lv[0]
        if all((C[j] > p) if side > 0 else (C[j] < p) for j in range(b, e + 1)):
            gap = bool((O[b] > p) if side > 0 else (O[b] < p))
            return dict(b=b, e=e, level=float(p), key=lv[2], gap=gap)
    return None


def measure(ix: np.ndarray, row: int, pe: float, d: int, level: float, atr: float) -> dict:
    """Moves after an event at minute row (its close = pe), direction d (+1 up / -1 down), index points; '_a' = / ATR14."""
    C, H, Lo = ix[:, 3], ix[:, 1], ix[:, 2]
    last = len(C) - 1
    out = {}
    for h in HORIZONS:
        out[f"mv{h}"] = d * (C[row + h] - pe) if row + h <= last else np.nan
    out["mvc"] = d * (C[last] - pe)
    seg = C[row + 1:min(row + FAKE_MIN, last) + 1]
    out["fake30"] = float(bool(np.any(d * (seg - level) < 0))) if len(seg) else np.nan
    fav, adv = (H, Lo) if d > 0 else (Lo, H)
    for tag, end in (("60", min(row + 60, last)), ("c", last)):
        if end > row:
            out[f"mfe{tag}"] = float(max(0.0, np.max(d * (fav[row + 1:end + 1] - pe))))
            out[f"mae{tag}"] = float(min(0.0, np.min(d * (adv[row + 1:end + 1] - pe))))
        else:
            out[f"mfe{tag}"] = out[f"mae{tag}"] = np.nan
    for k in list(out):
        if k != "fake30":
            out[k + "_a"] = out[k] / atr if atr and np.isfinite(atr) else np.nan
    return out
