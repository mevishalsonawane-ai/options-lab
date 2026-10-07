"""Shared helpers for the ga_* catalog strategies (opening range, time of day, levels). Not a strategy module."""
from __future__ import annotations

import numpy as np

from .. import config as C

SQ = 15 * 60 + 15            # the catalog's 15:15 time exit
UNDS4 = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")
UNDS2 = ("NIFTY", "BANKNIFTY")


def closes(tf, start, end):
    """Columns (0 = 09:15) whose 1-min close is a tf-minute candle close (candles anchored 09:15), start <= col < end."""
    return [x for x in range(start, end) if (x + 1) % tf == 0]


def first_break(h, l, c, up, dn, start, end, tf=5, trigger="close", sides=(1, -1)):
    """First (col, side) from column `start` (inclusive) to `end` (exclusive) where the index breaks `up` (side +1) or
    `dn` (side -1): on a tf-minute candle CLOSE beyond the level, or with trigger 'touch' on a 1-minute high / low through
    it. up / dn may be scalars or W-arrays (a level that moves with time); NaN levels never trigger."""
    U = np.broadcast_to(np.asarray(up, float), (C.W,))
    D = np.broadcast_to(np.asarray(dn, float), (C.W,))
    cols = range(max(start, 0), min(end, C.W)) if (trigger == "touch" or tf == 1) else closes(tf, max(start, 0), min(end, C.W))
    for col in cols:
        if np.isnan(c[col]):
            continue
        if trigger == "touch":
            u_, d_ = h[col] > U[col], l[col] < D[col]
        else:
            u_, d_ = c[col] > U[col], c[col] < D[col]
        u_ = bool(u_) and 1 in sides
        d_ = bool(d_) and -1 in sides
        if u_ and d_:
            continue
        if u_:
            return col, 1
        if d_:
            return col, -1
    return None, 0


def close_exit(c, lvl, side, start, tf=5, end=C.W):
    """exit_at minute for a close-confirmed stop: the first tf-minute candle close at/after column `start` beyond `lvl`
    against `side`; the position is closed at the next minute's open. NaN if never."""
    for col in closes(tf, max(start, 0), end):
        x = c[col]
        if np.isfinite(x) and (x - lvl) * side < 0:
            return col + 1 + C.OPEN_M
    return np.nan


def twap(h, l, c):
    """Time-weighted 'VWAP' (no index volume): running mean of the 1-minute typical price (h + l + c) / 3."""
    tp = (h + l + c) / 3
    ok = ~np.isnan(tp)
    cs = np.cumsum(np.where(ok, tp, 0.0))
    n = np.cumsum(ok)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(n > 0, cs / np.maximum(n, 1), np.nan)


def day_open(o, c):
    if np.isfinite(o[0]):
        return float(o[0])
    ok = np.nonzero(~np.isnan(c))[0]
    return float(c[ok[0]]) if len(ok) else np.nan


def last_valid(a, col):
    x = a[: col + 1]
    ok = np.nonzero(~np.isnan(x))[0]
    return float(x[ok[-1]]) if len(ok) else np.nan
