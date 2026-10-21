"""R23 range definitions (PREREG.md part A3). Research only, 13 Oct 2026.

Grid: R17's Day.ix, 375 rows, row r = the minute labelled 09:15 + r. A range is a dict
    hi, lo        the edges
    end           the LAST row the range reads (it is known once that minute has closed)
    p_end         the close of row `end` (the random-level twin mirrors around it)
Nothing after row `end` is read to build a range (the look-ahead guard in test_r23.py checks it).

    time_range(ix, T)                  rows 0 .. T-1
    vol_range(ix, atr, k)              grows from 09:15; closes at the first 5-min bar end from 09:30 where hi - lo >= k ATR
    va_range(ix, T)                    TPO value area of rows 0 .. T-1
    pd_range(ix, T, pdh, pdl)          rows 0 .. T-1 stretched to the previous day's high / low
    gap_range(ix, T, pclose)           rows 0 .. T-1 stretched to include the previous close
    bars(ix, tf), window(end, tf)      candles aligned to 09:15; decision candles after the range, closing by 14:00
    first_breaks(ix, rg, tf)           [(side, k, row)] the first close beyond each edge inside the window
"""
from __future__ import annotations

import numpy as np

LAST_ROW = 284               # the minute labelled 13:59: a decision candle must close by 14:00
VOL_MIN_END, VOL_MAX_END = 14, 59      # vol range: closes at a 5-min bar end from 09:29 (row 14) to 10:14 (row 59)
VA_SHARE = 0.70
VA_BINS = 30
TICK = 0.05


def _mk(ix, hi, lo, end):
    return dict(hi=float(hi), lo=float(lo), end=int(end), p_end=float(ix[end, 3]))


def time_range(ix: np.ndarray, T: int) -> dict:
    m = ix[:T]
    return _mk(ix, np.max(m[:, 1]), np.min(m[:, 2]), T - 1)


def vol_range(ix: np.ndarray, atr: float, k: float) -> dict:
    """The range closes at the first 5-min bar end e (rows 14, 19, .., 59) where high - low of rows 0 .. e >= k x ATR14;
    at row 59 (10:14) at the latest."""
    hi = np.maximum.accumulate(ix[:, 1])
    lo = np.minimum.accumulate(ix[:, 2])
    for e in range(VOL_MIN_END, VOL_MAX_END + 1, 5):
        if hi[e] - lo[e] >= k * atr:
            return _mk(ix, hi[e], lo[e], e)
    return _mk(ix, hi[VOL_MAX_END], lo[VOL_MAX_END], VOL_MAX_END)


def value_area(closes: np.ndarray, hi: float, lo: float, share: float = VA_SHARE, nbins: int = VA_BINS):
    """TPO value area of 1-minute closes: bins of max((hi - lo) / nbins, TICK) from lo; POC = the fullest bin (ties: the
    one nearest the middle, then the lower); grow by the fuller neighbour (ties: the upper) until >= share of the minutes.
    Returns (VAH, VAL) = the outer edges of the area's bins, clipped to [lo, hi]."""
    w = max((hi - lo) / nbins, TICK)
    n = max(int(np.ceil((hi - lo) / w - 1e-9)), 1)
    idx = np.clip(((closes - lo) / w).astype(int), 0, n - 1)
    cnt = np.bincount(idx, minlength=n)
    mid = (n - 1) / 2.0
    poc = max(range(n), key=lambda i: (cnt[i], -abs(i - mid), -i))
    a = b = poc
    have = cnt[poc]
    need = share * cnt.sum()
    while have < need - 1e-9 and (a > 0 or b < n - 1):
        up = cnt[b + 1] if b < n - 1 else -1
        dn = cnt[a - 1] if a > 0 else -1
        if up >= dn:
            b += 1
            have += up
        else:
            a -= 1
            have += dn
    return min(lo + (b + 1) * w, hi), max(lo + a * w, lo)


def va_range(ix: np.ndarray, T: int = 60) -> dict:
    m = ix[:T]
    hi, lo = float(np.max(m[:, 1])), float(np.min(m[:, 2]))
    vah, val = value_area(m[:, 3], hi, lo)
    return _mk(ix, vah, val, T - 1)


def pd_range(ix: np.ndarray, T: int, pdh: float, pdl: float) -> dict:
    r = time_range(ix, T)
    return _mk(ix, max(r["hi"], pdh), min(r["lo"], pdl), T - 1)


def gap_range(ix: np.ndarray, T: int, pclose: float) -> dict:
    r = time_range(ix, T)
    return _mk(ix, max(r["hi"], pclose), min(r["lo"], pclose), T - 1)


def mirror(rg: dict) -> dict:
    """The random-level twin: x -> 2 p_end - x (twin high = 2 p_end - low, twin low = 2 p_end - high)."""
    p = rg["p_end"]
    return dict(rg, hi=2 * p - rg["lo"], lo=2 * p - rg["hi"])


def bars(ix: np.ndarray, tf: int):
    """(open, high, low, close) of the tf-minute candles aligned to 09:15 (candle k = rows tf k .. tf k + tf - 1)."""
    n = ix.shape[0] // tf
    o = ix[0:n * tf:tf, 0]
    h = ix[:n * tf, 1].reshape(n, tf).max(axis=1)
    lo = ix[:n * tf, 2].reshape(n, tf).min(axis=1)
    c = ix[tf - 1:n * tf:tf, 3]
    return o, h, lo, c


def row_of(k: int, tf: int) -> int:
    return (k + 1) * tf - 1


def window(end: int, tf: int) -> tuple[int, int]:
    """(k0, k1): the first candle starting after row `end`, the last closing by 14:00 (its last row <= LAST_ROW)."""
    k0 = -(-(end + 1) // tf)
    k1 = (LAST_ROW + 1) // tf - 1
    return k0, k1


def first_breaks(ix: np.ndarray, rg: dict, tf: int) -> list[tuple[int, int, int]]:
    """[(side, k, decision row)]: the first tf-candle in the window closing strictly above hi (side +1) / below lo (-1)."""
    _, _, _, c = bars(ix, tf)
    k0, k1 = window(rg["end"], tf)
    out = []
    for side in (1, -1):
        edge = rg["hi"] if side > 0 else rg["lo"]
        for k in range(k0, min(k1, len(c) - 1) + 1):
            if side * (c[k] - edge) > 0:
                out.append((side, k, row_of(k, tf)))
                break
    return out


def first_break_any(ix: np.ndarray, rg: dict, tf: int = 5):
    """The day's first break of either edge (the earlier; None if none): (side, k, row)."""
    b = first_breaks(ix, rg, tf)
    return min(b, key=lambda x: x[1]) if b else None
