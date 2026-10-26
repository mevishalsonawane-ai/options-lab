"""R105B direction biases (PREREG section 2). Pure functions over plain arrays: +1 = calls only, -1 = puts only,
0 = both (no filter). Every function takes only data known before the entry minute (the caller passes daily bars that
end at the PREVIOUS session; intraday arrays are cut by the caller or by the function at the entry row).
"""
from __future__ import annotations

import math
from datetime import date

import numpy as np

# ------------------------------------------------------------------------------------------------ helpers


def _fin(*xs) -> bool:
    return all(x is not None and np.isfinite(x) for x in xs)


def keep(side: str, bias: int) -> bool:
    """Does a bias keep a signal of `side` ('C' call / 'P' put)?"""
    if bias > 0:
        return side == "C"
    if bias < 0:
        return side == "P"
    return True


def fractal_swings(h, l) -> tuple:
    """3-bar fractals: index lists of swing highs / lows (high[i] > high[i-1] and > high[i+1]); a swing at i needs bar
    i+1, so the last bar is never a swing (it is confirmed only when the next bar closes)."""
    h, l = np.asarray(h, float), np.asarray(l, float)
    hi, lo = [], []
    for i in range(1, len(h) - 1):
        if _fin(h[i - 1], h[i], h[i + 1]) and h[i] > h[i - 1] and h[i] > h[i + 1]:
            hi.append(i)
        if _fin(l[i - 1], l[i], l[i + 1]) and l[i] < l[i - 1] and l[i] < l[i + 1]:
            lo.append(i)
    return hi, lo


def structure(h, l) -> int:
    """Dow structure from completed bars: the last 2 swing highs and the last 2 swing lows both rising -> +1, both
    falling -> -1, else 0 (also 0 with fewer than 2 of each)."""
    hi, lo = fractal_swings(h, l)
    if len(hi) < 2 or len(lo) < 2:
        return 0
    h, l = np.asarray(h, float), np.asarray(l, float)
    up = h[hi[-1]] > h[hi[-2]] and l[lo[-1]] > l[lo[-2]]
    dn = h[hi[-1]] < h[hi[-2]] and l[lo[-1]] < l[lo[-2]]
    return 1 if up else (-1 if dn else 0)


# ------------------------------------------------------------------------------------------------ day biases
# Each takes `prev`: dict of arrays (o, h, l, c) of the sessions BEFORE the day (oldest first), and for D1 / D10 the
# day's own open `o_today` (known at 09:15).

def d1_gaphl(prev: dict, o_today: float) -> int:
    if len(prev["c"]) < 1 or not _fin(o_today, prev["h"][-1], prev["l"][-1]):
        return 0
    if o_today > prev["h"][-1]:
        return 1
    if o_today < prev["l"][-1]:
        return -1
    return 0


def d10_gap25(prev: dict, o_today: float, thr: float = 0.0025) -> int:
    if len(prev["c"]) < 1 or not _fin(o_today, prev["c"][-1]) or prev["c"][-1] <= 0:
        return 0
    g = o_today / prev["c"][-1] - 1.0
    return 1 if g >= thr - 1e-12 else (-1 if g <= -thr + 1e-12 else 0)


def weeks(dates, h, l, c) -> list:
    """[(week key, high, low, close)] of ISO weeks, oldest first (the last close of the week)."""
    out = []
    for d, hh, ll, cc in zip(dates, h, l, c):
        k = d.isocalendar()[:2]
        if out and out[-1][0] == k:
            _, h0, l0, _ = out[-1]
            out[-1] = (k, max(h0, hh), min(l0, ll), cc)
        else:
            out.append((k, hh, ll, cc))
    return out


def completed_weeks(dates, h, l, c, day: date) -> list:
    """The weeks strictly before the week of `day` (the current week is never complete before its last session)."""
    k = day.isocalendar()[:2]
    return [w for w in weeks(dates, h, l, c) if w[0] < k]


def d2_wk3(wk: list) -> int:
    """Trader Tom's 3-bar swing state on completed weekly bars."""
    state = 0
    for i in range(3, len(wk)):
        hh = max(w[1] for w in wk[i - 3:i])
        ll = min(w[2] for w in wk[i - 3:i])
        up, dn = wk[i][1] > hh, wk[i][2] < ll
        if up and dn:                     # an outside week (not in the PREREG): its close vs the 3-week midpoint
            state = 1 if wk[i][3] >= (hh + ll) / 2 else -1
        elif up:
            state = 1
        elif dn:
            state = -1
    return state


def d3_wk89(wk: list, n: int = 89) -> int:
    if len(wk) < n:
        return 0
    sma = float(np.mean([w[3] for w in wk[-n:]]))
    _, h, l, _ = wk[-1]
    return 1 if l > sma else (-1 if h < sma else 0)


def d4_ddow(prev: dict) -> int:
    return structure(prev["h"], prev["l"])


def d6_pday(prev: dict) -> int:
    o, c = prev["o"], prev["c"]
    if len(c) < 2 or not _fin(o[-1], c[-1], c[-2]):
        return 0
    if c[-1] > o[-1] and c[-1] > c[-2]:
        return 1
    if c[-1] < o[-1] and c[-1] < c[-2]:
        return -1
    return 0


def d7_sma200(prev: dict, n: int = 200, band: float = 0.01) -> int:
    c = np.asarray(prev["c"], float)
    if len(c) < n or not np.isfinite(c[-n:]).all():
        return 0
    s = float(c[-n:].mean())
    return 1 if c[-1] > s * (1 + band) else (-1 if c[-1] < s * (1 - band) else 0)


def adx(h, l, c, n: int = 20) -> tuple:
    """Wilder's ADX, +DI, -DI at the last bar (NaN if too short)."""
    h, l, c = (np.asarray(x, float) for x in (h, l, c))
    if len(c) < 2 * n + 1:
        return math.nan, math.nan, math.nan
    up, dn = h[1:] - h[:-1], l[:-1] - l[1:]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = np.maximum.reduce([h[1:] - l[1:], np.abs(h[1:] - c[:-1]), np.abs(l[1:] - c[:-1])])

    def wild(x):
        out = np.empty(len(x))
        out[n - 1] = x[:n].sum()
        for i in range(n, len(x)):
            out[i] = out[i - 1] - out[i - 1] / n + x[i]
        return out[n - 1:]
    atr, p, m = wild(tr), wild(pdm), wild(mdm)
    pdi, mdi = 100 * p / atr, 100 * m / atr
    dx = 100 * np.abs(pdi - mdi) / np.where(pdi + mdi > 0, pdi + mdi, np.nan)
    dx = np.nan_to_num(dx)
    a = np.empty(len(dx))
    a[n - 1] = dx[:n].mean()
    for i in range(n, len(dx)):
        a[i] = (a[i - 1] * (n - 1) + dx[i]) / n
    return float(a[-1]), float(pdi[-1]), float(mdi[-1])


def d8_adx30(prev: dict, n: int = 20, thr: float = 30.0) -> int:
    a, p, m = adx(prev["h"], prev["l"], prev["c"], n)
    if not _fin(a, p, m) or a <= thr:
        return 0
    return 1 if p > m else -1


# ------------------------------------------------------------------------------------------------ intraday biases
def d9_f15(idx_day, er: int) -> int:
    """idx_day: (4, 375) O H L C of today. Entries at row >= 15 (09:30) only; the 09:15-09:29 candle's direction."""
    if er < 15:
        return 0
    o, c = idx_day[0, :15], idx_day[3, :15]
    oi, ci = np.flatnonzero(np.isfinite(o)), np.flatnonzero(np.isfinite(c))
    if not len(oi) or not len(ci):
        return 0
    op, cl = o[oi[0]], c[ci[-1]]
    return 1 if cl > op else (-1 if cl < op else 0)


H60_EDGES = tuple(range(0, 375, 60))          # bar k = rows 60k .. min(60k+59, 374)


def h60_bars(idx_day) -> list:
    """[(end row, high, low)] of the day's 60-minute bars that have any minute."""
    out = []
    for a in H60_EDGES:
        b = min(a + 59, 374)
        hh, ll = idx_day[1, a:b + 1], idx_day[2, a:b + 1]
        if np.isfinite(hh).any():
            out.append((b, float(np.nanmax(hh)), float(np.nanmin(ll))))
    return out


def d5_h60(hist_bars: list, today_bars: list, er: int) -> int:
    """hist_bars: [(high, low)] of earlier days' 60-minute bars (oldest first); today_bars: h60_bars(today); only today's
    bars that ended at or before er - 1 are used."""
    hs = [b[0] for b in hist_bars] + [b[1] for b in today_bars if b[0] <= er - 1]
    ls = [b[1] for b in hist_bars] + [b[2] for b in today_bars if b[0] <= er - 1]
    return structure(hs, ls)


RULES = ("D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8", "D9", "D10")
NAMES = {
    "D1": "GAPHL: trade the gap's side (open beyond yesterday's high / low) (post-hoc)",
    "D2": "WK3: weekly 3-bar swing state (Trader Tom)",
    "D3": "WK89: weekly 89 SMA (Trader Tom)",
    "D4": "DDOW: daily Dow structure HH/HL (Trader Tom, Douglas)",
    "D5": "H60: 60-minute Dow structure (Woods)",
    "D6": "PDAY: prior-day trend (Porwal)",
    "D7": "SMA200: daily 200 SMA with a 1% band (Fabozzi, Aziz)",
    "D8": "ADX30: daily ADX(20) > 30 with DI (Larry Swing)",
    "D9": "F15: first 15-minute candle, entries from 09:30 (Heitkoetter)",
    "D10": "GAP25: trade the gap's side, open vs close +-0.25% (post-hoc)",
}
DAY_RULES = ("D1", "D2", "D3", "D4", "D6", "D7", "D8", "D10")
