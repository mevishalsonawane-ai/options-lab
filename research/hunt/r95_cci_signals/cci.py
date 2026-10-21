"""R95 indicator (PREREG section 3): the "Mo3ty CCI Signals" Pine script, bar for bar, on the UNDERLYING.

Every function works on plain arrays so the tests can feed hand-made bars. Pine semantics kept:
    bars()         bars of tf minutes anchored at the session open, never across sessions (TradingView intraday bars)
    sma()          ta.sma: NaN until n values, NaN if any value in the window is NaN
    cci()          ta.cci: (src - sma) / (0.015 x mean |src - sma|) over the same n bars; NaN when that is 0
    atr()          ta.atr: RMA of the true range (the first TR = high - low), seeded with the SMA of the first n
    pulse()        the Volatility Pulse: ATR above its 20-bar SMA and the body >= 0.25 ATR
    signals()      BUY / SELL: the CCI was at / beyond a level on the previous bar and closes back inside on this one
    pivots()       the app's Pine engine's ta.pivotlow / ta.pivothigh (strict on the right, non-strict on the left)
    divergences()  BULL / BEAR, each known at its confirming bar (pivot + right bars), never at the pivot bar
    episode_start()  first bar of the stretch (the run of bars at / beyond the level) that a signal ends
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numpy.lib.stride_tricks import sliding_window_view

from . import cfg as K


# ------------------------------------------------------------------------------------------------ bars
def bars(mday, mt, mo, mh, ml, mc, anchor: int, tf: int) -> dict:
    """Bars of tf minutes anchored at the session open: day, o, h, l, c, s, e (first / last global minute). A bar is
    known at the close of its last minute e."""
    mday = np.asarray(mday)
    b = (np.asarray(mt, np.int64) - anchor) // tf
    key = mday.astype(np.int64) * 100000 + b
    brk = np.r_[0, np.flatnonzero(key[1:] != key[:-1]) + 1].astype(np.int64)
    end = (np.r_[brk[1:], len(key)] - 1).astype(np.int64)
    return dict(day=mday[brk].astype(np.int64), o=np.asarray(mo, float)[brk], h=np.maximum.reduceat(mh, brk),
                l=np.minimum.reduceat(ml, brk), c=np.asarray(mc, float)[end], s=brk, e=end)


# ------------------------------------------------------------------------------------------------ Pine built-ins
def sma(x: np.ndarray, n: int) -> np.ndarray:
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if n < 1 or len(x) < n:
        return out
    w = sliding_window_view(x, n)
    out[n - 1:] = w.mean(axis=1)            # a NaN anywhere in the window gives NaN, as Pine
    return out


def cci(src: np.ndarray, n: int) -> np.ndarray:
    src = np.asarray(src, float)
    out = np.full(len(src), np.nan)
    if n < 1 or len(src) < n:
        return out
    w = sliding_window_view(src, n)
    m = w.mean(axis=1)
    md = np.abs(w - m[:, None]).mean(axis=1)
    with np.errstate(divide="ignore", invalid="ignore"):
        v = (src[n - 1:] - m) / (0.015 * md)
    v[~(md > 0)] = np.nan
    out[n - 1:] = v
    return out


def mean_dev(src: np.ndarray, n: int) -> np.ndarray:
    """The CCI's denominator before the 0.015: mean |src - SMA| over the n bars (points)."""
    src = np.asarray(src, float)
    out = np.full(len(src), np.nan)
    if len(src) < n:
        return out
    w = sliding_window_view(src, n)
    out[n - 1:] = np.abs(w - w.mean(axis=1)[:, None]).mean(axis=1)
    return out


def std_dev(src: np.ndarray, n: int) -> np.ndarray:
    """Population standard deviation over the n bars (Pine ta.stdev, biased)."""
    src = np.asarray(src, float)
    out = np.full(len(src), np.nan)
    if len(src) < n:
        return out
    out[n - 1:] = sliding_window_view(src, n).std(axis=1)
    return out


def true_range(h, l, c) -> np.ndarray:
    h, l, c = (np.asarray(x, float) for x in (h, l, c))
    pc = np.r_[np.nan, c[:-1]]
    tr = np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc)))
    tr[0] = h[0] - l[0]                     # ta.tr(true): the first bar has no previous close
    return tr


def rma(x: np.ndarray, n: int) -> np.ndarray:
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if len(x) < n:
        return out
    out[n - 1] = x[:n].mean()
    a = 1.0 / n
    for i in range(n, len(x)):
        out[i] = a * x[i] + (1 - a) * out[i - 1]
    return out


def atr(h, l, c, n: int = K.ATR_N) -> np.ndarray:
    return rma(true_range(h, l, c), n)


def pulse(o, h, l, c, n: int = K.ATR_N, avg_n: int = K.ATR_AVG_N, body: float = K.MIN_BODY_ATR) -> np.ndarray:
    a = atr(h, l, c, n)
    av = sma(a, avg_n)
    with np.errstate(invalid="ignore"):
        return (a > av) & (np.abs(np.asarray(c, float) - np.asarray(o, float)) >= a * body)


def signals(x: np.ndarray, level: float) -> tuple:
    """(buy, sell) booleans per bar. NaN compares false (Pine: na and ... is false)."""
    x = np.asarray(x, float)
    prev = np.r_[np.nan, x[:-1]]
    with np.errstate(invalid="ignore"):
        sell = (prev >= level) & (x < level)
        buy = (prev <= -level) & (x > -level)
    return buy, sell


def episode_start(x: np.ndarray, i: int, side: int, level: float) -> int:
    """First bar of the run of bars ending at i - 1 with CCI >= +level (side -1, a SELL) or <= -level (side +1)."""
    j = i - 1
    while j - 1 >= 0 and np.isfinite(x[j - 1]) and (x[j - 1] >= level if side < 0 else x[j - 1] <= -level):
        j -= 1
    return j


# ------------------------------------------------------------------------------------------------ pivots, divergence
def pivots(h: np.ndarray, l: np.ndarray, left: int = K.PIV_L, right: int = K.PIV_R) -> tuple:
    """(ph, pl) booleans AT the pivot bar p. The app's engine: the pivot is strictly beyond each of the `right` bars
    after it and at least as extreme as each of the `left` bars before it. Usable only from bar p + right."""
    h, l = np.asarray(h, float), np.asarray(l, float)
    m = len(h)
    ph = np.zeros(m, bool)
    pl = np.zeros(m, bool)
    if m < left + right + 1:
        return ph, pl
    a, b = left, m - right
    ph[a:b] = True
    pl[a:b] = True
    for j in range(1, right + 1):
        ph[a:b] &= h[a:b] > h[a + j:b + j]
        pl[a:b] &= l[a:b] < l[a + j:b + j]
    for j in range(1, left + 1):
        ph[a:b] &= h[a:b] >= h[a - j:b - j]
        pl[a:b] &= l[a:b] <= l[a - j:b - j]
    ph[a:b] &= np.isfinite(h[a:b])
    pl[a:b] &= np.isfinite(l[a:b])
    return ph, pl


@dataclass
class Div:
    side: int          # +1 BULL, -1 BEAR
    p: int             # the pivot bar (where the chart draws the label)
    q: int             # the confirming bar p + right (when it is known)
    prev: int          # the previous pivot of the same kind


def divergences(h, l, x, level: float = K.LEVEL, left: int = K.PIV_L, right: int = K.PIV_R) -> list:
    """Divergences in order of their confirming bar. BULL: pivot low below the previous pivot low, CCI at it above the
    CCI at the previous one, either CCI <= -level. BEAR mirrored. NaN CCI never qualifies."""
    h, l, x = np.asarray(h, float), np.asarray(l, float), np.asarray(x, float)
    ph, pl = pivots(h, l, left, right)
    out = []
    last_lo = last_hi = -1
    for p in range(len(h)):
        if pl[p]:
            if last_lo >= 0 and l[p] < l[last_lo] and np.isfinite(x[p]) and np.isfinite(x[last_lo]) \
                    and x[p] > x[last_lo] and (x[p] <= -level or x[last_lo] <= -level):
                out.append(Div(1, p, p + right, last_lo))
            last_lo = p
        if ph[p]:
            if last_hi >= 0 and h[p] > h[last_hi] and np.isfinite(x[p]) and np.isfinite(x[last_hi]) \
                    and x[p] < x[last_hi] and (x[p] >= level or x[last_hi] >= level):
                out.append(Div(-1, p, p + right, last_hi))
            last_hi = p
    out.sort(key=lambda d: (d.q, d.side))
    return out


# ------------------------------------------------------------------------------------------------ one instrument's arrays
@dataclass
class Ind:
    """The indicator on one bar series for one (length, level)."""
    B: dict
    tp: np.ndarray
    cci: np.ndarray
    buy: np.ndarray
    sell: np.ndarray
    pulse: np.ndarray
    atr: np.ndarray
    divs: list
    level: float
    n: int


def indicator(B: dict, n: int = K.CCI_N, level: float = K.LEVEL) -> Ind:
    tp = (B["h"] + B["l"] + B["c"]) / 3.0
    x = cci(tp, n)
    buy, sell = signals(x, level)
    return Ind(B, tp, x, buy, sell, pulse(B["o"], B["h"], B["l"], B["c"]), atr(B["h"], B["l"], B["c"]),
               divergences(B["h"], B["l"], x, level), level, n)
