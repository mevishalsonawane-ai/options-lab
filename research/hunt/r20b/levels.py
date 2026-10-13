"""R20b support / resistance levels, each strictly point-in-time (PREREG.md "The levels").

Grid: a session's index minutes are R17's `Day.ix` (375 rows, row r = the minute labelled 09:15 + r). 5-minute bar k
covers rows 5k .. 5k+4 and is known once row 5k+4 is over (the decision moment of bar k). 15-minute bar j covers rows
15j .. 15j+14; it is COMPLETE at a decision on 5-minute bar k when 15(j+1) <= 5(k+1).

    piv_levels(prev)            PDH, PDL, PDC, P, R1, S1 from the previous session (known at 09:15)
    LiqDay(hist, today)         liquidity zones on the 15-minute chart, recomputed on the completed bars only
    oi_walls / oii_walls        open-interest walls (previous day's bhavcopy / the release's minute OI at 10:04)
    atr14(days, i)              ShadowRules.atr14: mean true range of the 14 sessions before day i
"""
from __future__ import annotations

import os
import sys
from datetime import timedelta

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
for p in (ROOT, os.path.join(ROOT, "research"), HERE):
    if p not in sys.path:
        sys.path.insert(0, p)

P0_ROW = 49                  # the last minute of the 5-minute bar labelled 10:00: P0 is its close, known at 10:05
HIST_DAYS = 10               # liquidity history: sessions of the previous 10 calendar days


# The Python port of LiquidityRules.swingZones / poolZones / markBreaks, copied verbatim from research/hunt/h4/comps.py
# (itself from research/liquidity_exits_3060.py; that module imports the whole obuy stack, so it is copied, not imported).
# test_r20b.py checks this copy against h4's, zone for zone.
L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300


class Zone:
    __slots__ = ("kind", "side", "top", "bottom", "origin", "known", "broken")

    def __init__(self, kind, side, top, bottom, origin, known):
        self.kind, self.side, self.top, self.bottom, self.origin, self.known, self.broken = kind, side, top, bottom, origin, known, -1

    @property
    def edge(self):
        return self.top if self.side > 0 else self.bottom


def mark_breaks(zones, Cl):
    for z in zones:
        seg = Cl[z.known:]
        hit = (seg > z.top) if z.side > 0 else (seg < z.bottom)
        z.broken = int(z.known + np.argmax(hit)) if hit.any() else -1


def swing_zones(H, Lo, Cl):
    n = len(H)
    out = []
    if n > 2 * L:
        from numpy.lib.stride_tricks import sliding_window_view as sw
        lmax = np.full(n, np.inf); rmax = np.full(n, np.inf); lmin = np.full(n, -np.inf); rmin = np.full(n, -np.inf)
        wh = sw(H, L).max(axis=1); wl = sw(Lo, L).min(axis=1)
        js = np.arange(L, n - L)
        lmax[js] = wh[js - L]; rmax[js] = wh[js + 1]; lmin[js] = wl[js - L]; rmin[js] = wl[js + 1]
        for i in range(2 * L, n):
            j = i - L
            if lmax[j] < H[j] and rmax[j] < H[j]:
                out.append(Zone("swing", 1, H[j], Lo[j], j, i))
            if lmin[j] > Lo[j] and rmin[j] > Lo[j]:
                out.append(Zone("swing", -1, H[j], Lo[j], j, i))
    mark_breaks(out, Cl)
    return out


def pool_zones(O, H, Lo, Cl):
    out = []
    cand = []
    for i in range(len(Cl)):
        o, h, lo, c = O[i], H[i], Lo[i], Cl[i]
        keep = []
        for z in cand:
            side, top, bot, origin = z[0], z[1], z[2], z[3]
            if i - origin > MAX_AGE:
                continue
            if (side > 0 and c > top) or (side < 0 and c < bot):
                continue
            touched = (h >= bot and c < top) if side > 0 else (lo <= top and c > bot)
            if touched and i - z[5] >= GAP and z[4] < CONTACTS:
                z[4] += 1; z[5] = i
            if z[4] >= CONTACTS and i - z[5] >= CONFIRM:
                dup = any(q.side == side and q.bottom <= top and bot <= q.top for q in out[-50:])
                if not dup:
                    out.append(Zone("pool", side, top, bot, origin, i))
                continue
            keep.append(z)
        cand = keep
        bh, bl = max(o, c), min(o, c)
        if h > bh:
            cand.append([1, h, bh, i, 1, i])
        if lo < bl:
            cand.append([-1, bl, lo, i, 1, i])
    mark_breaks(out, Cl)
    return out


# ------------------------------------------------------------------------------------------------ daily
def daily_bar(ix: np.ndarray) -> tuple[float, float, float]:
    """(high, low, close) of one session's 375 index minutes."""
    return float(np.nanmax(ix[:, 1])), float(np.nanmin(ix[:, 2])), float(ix[-1, 3])


def atr14(days, i: int) -> float:
    """The app's ShadowRules.atr14 for day i: the mean true range of the 14 sessions before it; NaN with < 15 earlier."""
    if i < 15:
        return np.nan
    tr = []
    for j in range(i - 14, i):
        h, l, _ = daily_bar(days[j].ix)
        pc = daily_bar(days[j - 1].ix)[2]
        tr.append(max(h - l, abs(h - pc), abs(l - pc)))
    return float(np.mean(tr))


def piv_levels(prev_ix: np.ndarray) -> list[float]:
    """PDH, PDL, PDC and the classic floor pivots P, R1, S1 of the previous session."""
    h, l, c = daily_bar(prev_ix)
    p = (h + l + c) / 3.0
    return [h, l, c, p, 2 * p - l, 2 * p - h]


# ------------------------------------------------------------------------------------------------ liquidity zones
def bars15(ix: np.ndarray, n: int | None = None) -> np.ndarray:
    """The session's 15-minute OHLC bars (25 rows); only the first n when given."""
    m = 25 if n is None else n
    out = np.empty((m, 4))
    for j in range(m):
        s = ix[15 * j:15 * j + 15]
        out[j] = (s[0, 0], s[:, 1].max(), s[:, 2].min(), s[-1, 3])
    return out


def completed15(k: int) -> int:
    """How many of today's 15-minute bars are complete when 5-minute bar k has closed."""
    return min(25, (5 * (k + 1)) // 15)


class LiqDay:
    """Liquidity-zone edges for one session, as known at each 5-minute decision bar.

    hist: the previous sessions' 15-minute bars stacked (within HIST_DAYS calendar days); today: today's index minutes.
    edges(k) -> list of (level, key): the edge of every zone ACTIVE on the last completed 15-minute bar, computed only from
    the bars completed by the close of 5-minute bar k. key identifies the zone for the day (the random-level twin keeps
    one sign per zone)."""

    def __init__(self, hist: np.ndarray, today: np.ndarray):
        self.hist = hist
        self.today = today
        self._cache: dict[int, list] = {}

    def at15(self, n: int) -> list:
        if n in self._cache:
            return self._cache[n]
        b = np.vstack([self.hist, bars15(self.today, n)]) if n else self.hist
        out = []
        if len(b):
            O, H, Lo, Cl = (np.ascontiguousarray(b[:, j]) for j in range(4))
            zs = swing_zones(H, Lo, Cl) + pool_zones(O, H, Lo, Cl)
            i = len(b) - 1
            nh = len(self.hist)
            for z in zs:
                if z.known <= i and (z.broken < 0 or z.broken >= i):
                    out.append((float(z.edge), (z.kind, z.side, int(z.origin) - nh)))
        self._cache[n] = out
        return out

    def edges(self, k: int) -> list:
        return self.at15(completed15(k))


def liq_history(days, i: int) -> np.ndarray:
    d0 = days[i].day
    hs = [bars15(days[j].ix) for j in range(max(0, i - 15), i) if days[j].day >= d0 - timedelta(days=HIST_DAYS)]
    return np.vstack(hs) if hs else np.empty((0, 4))


# ------------------------------------------------------------------------------------------------ OI walls
def oi_walls(und: str, day, prev_session) -> list[float]:
    """The previous trading day's bhavcopy walls [support, resistance] for `day` ([] when that file is missing: no
    fallback to an older file, PREREG.md)."""
    import oi as OI
    if prev_session is None:
        return []
    return [x for x in OI.walls(OI.load(und, prev_session), day) if np.isfinite(x)]


def oii_walls(options, day, row: int = P0_ROW) -> list[float]:
    """Intraday OI walls [support, resistance] from the adapter's option minutes (OPTION_COLS: ts, expiry, strike, side,
    oi ...): the nearest expiry AFTER the day; each contract's last OI printed (> 0) in a minute labelled at or before
    09:15 + row (10:04). [] when nothing printed."""
    import pandas as pd
    if options is None or not len(options):
        return []
    o = options[(options.expiry > day) & (options.oi > 0)]
    t_end = pd.Timestamp(day) + pd.Timedelta(minutes=9 * 60 + 15 + row)
    o = o[pd.to_datetime(o.ts) <= t_end]
    if not len(o):
        return []
    o = o[o.expiry == o.expiry.min()].sort_values("ts")
    last = o.groupby(["strike", "side"]).oi.last().reset_index()
    out = []
    for side in ("P", "C"):
        x = last[last.side == side]
        out.append(float(x.loc[x.oi.idxmax(), "strike"]) if len(x) else np.nan)
    return [v for v in out if np.isfinite(v)]


# ------------------------------------------------------------------------------------------------ helpers
def nearest_above(levels, x):
    a = [v for v in levels if v > x]
    return min(a) if a else np.nan


def nearest_below(levels, x):
    b = [v for v in levels if v < x]
    return max(b) if b else np.nan


def mirror(level: float, p0: float, sign: int) -> float:
    """The random-level twin: the same distance from P0, on a random side."""
    return p0 + sign * (level - p0)
