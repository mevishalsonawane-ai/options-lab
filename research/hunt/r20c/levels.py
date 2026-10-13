"""R20c support / resistance levels, each strictly point-in-time (PREREG.md part A2 "Levels").

Grid: a session's index minutes are R17's `Day.ix` (375 rows, row r = the minute labelled 09:15 + r). A candle of tf
minutes, bar k, covers rows tf*k .. tf*k + tf - 1 and is known once its last row is over.

A level is (price, side, key, known_bar): side +1 = resistance (an up break), -1 = support; known_bar = the first candle
of the day that may CROSS it (the level is known before that candle starts). Daily levels are fixed for the day; LIQ
levels come and go with the zone map, so they are served per candle by `LiqMap.active(b)`.

    daily_bar(ix), atr14(days, i)          the previous-day bar and the app's ShadowRules.atr14
    pdhl(prev_ix), piv(prev_ix)            PDH / PDL and the classic floor pivots R1 / S1 (known 09:15)
    or_edges(ix, tf)                       the ORB opening range 09:15-10:04 (first candle 10:05 / 10:15)
    LiqMap(hist, today_ix, tf)             LiquidityRules zones on the tf chart, one pass, filtered by known / taken
    mirror(level, ref, sign)               the random-level twin
"""
from __future__ import annotations

from datetime import timedelta

import numpy as np

N_MIN = 375
OR_ROWS = 50                 # the ORB range: minutes 09:15 .. 10:04 (5-minute bars labelled 09:15 .. 10:00)
HIST_DAYS = 10               # liquidity history: sessions of the previous 10 calendar days


# The Python port of LiquidityRules.swingZones / poolZones / markBreaks, copied verbatim from research/hunt/h4/comps.py
# (R20b copied the same block; h4's module imports the whole obuy stack). test_r20c.py checks it against h4's copy.
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


# ------------------------------------------------------------------------------------------------ candles
def candles(ix: np.ndarray, tf: int, n: int | None = None) -> np.ndarray:
    """The session's tf-minute OHLC candles (375 / tf rows); only the first n when given."""
    m = N_MIN // tf if n is None else n
    out = np.empty((m, 4))
    for j in range(m):
        s = ix[tf * j:tf * j + tf]
        out[j] = (s[0, 0], s[:, 1].max(), s[:, 2].min(), s[-1, 3])
    return out


# ------------------------------------------------------------------------------------------------ daily levels
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


def pdhl(prev_ix: np.ndarray) -> list[tuple]:
    h, l, _ = daily_bar(prev_ix)
    return [(h, 1, "pdh", 0), (l, -1, "pdl", 0)]


def piv(prev_ix: np.ndarray) -> list[tuple]:
    h, l, c = daily_bar(prev_ix)
    p = (h + l + c) / 3.0
    return [(2 * p - l, 1, "r1", 0), (2 * p - h, -1, "s1", 0)]


def or_known_bar(tf: int) -> int:
    """The first tf candle that starts at or after 10:05 (5-minute: 10:05; 15-minute: 10:15)."""
    return -(-OR_ROWS // tf)


def or_edges(ix: np.ndarray, tf: int) -> list[tuple]:
    k = or_known_bar(tf)
    return [(float(ix[:OR_ROWS, 1].max()), 1, "orh", k), (float(ix[:OR_ROWS, 2].min()), -1, "orl", k)]


def oi_levels(walls) -> list[tuple]:
    """[support, resistance] strikes from oi.walls (NaN = missing) -> levels known at 09:15."""
    sup, res = walls
    out = []
    if np.isfinite(res):
        out.append((float(res), 1, "oi_call", 0))
    if np.isfinite(sup):
        out.append((float(sup), -1, "oi_put", 0))
    return out


def mirror(level: float, ref: float, sign: int) -> float:
    """The random-level twin: the same distance from the reference price, on a random side."""
    return ref + sign * (level - ref)


# ------------------------------------------------------------------------------------------------ liquidity zones
def liq_history(days, i: int, tf: int) -> np.ndarray:
    """The tf candles of the sessions in the previous HIST_DAYS calendar days (at most 15 sessions back), stacked."""
    d0 = days[i].day
    hs = [candles(days[j].ix, tf) for j in range(max(0, i - 15), i) if days[j].day >= d0 - timedelta(days=HIST_DAYS)]
    return np.vstack(hs) if hs else np.empty((0, 4))


class LiqMap:
    """LiquidityRules zones on the tf chart for one session (history + today), computed ONCE on all bars.

    Every zone rule is causal (a swing at j is confirmed at j + L from bars <= j + L; a pool is confirmed from bars <= its
    known bar; the duplicate check reads earlier zones only; `broken` is the first close beyond after `known`), so
    filtering the one-pass map by known <= b - 1 and broken >= b gives exactly the zones a recompute on the bars up to
    b - 1 would show (test_r20c checks this against a truncated recompute).

    active(b) -> [(edge, side, key, ref)]: zones usable by today's candle b (known at today's b - 1 or earlier, not taken
    by a close up to b - 1). key = (kind, side, origin relative to today) identifies the zone for the day; ref = the close
    of the bar that confirmed it (the random-level twin mirrors around it).
    mirrored(signs): the same map with each zone's edge mirrored around ref by signs[key] and its taken bar recomputed."""

    def __init__(self, hist: np.ndarray, today_ix: np.ndarray, tf: int):
        self.nh = len(hist)
        self.bars = np.vstack([hist, candles(today_ix, tf)])
        O, H, Lo, Cl = (np.ascontiguousarray(self.bars[:, j]) for j in range(4))
        self.cl = Cl
        zs = swing_zones(H, Lo, Cl) + pool_zones(O, H, Lo, Cl)
        self.zones = [(float(z.edge), int(z.side), (z.kind, int(z.side), int(z.origin) - self.nh), int(z.known),
                       int(z.broken), float(Cl[z.known])) for z in zs]

    def keys(self):
        return [z[2] for z in self.zones]

    def mirrored(self, signs: dict) -> "LiqMap":
        m = object.__new__(LiqMap)
        m.nh, m.bars, m.cl = self.nh, self.bars, self.cl
        zs = []
        for edge, side, key, known, broken, ref in self.zones:
            e2 = mirror(edge, ref, signs.get(key, 1))
            seg = self.cl[known:]
            hit = (seg > e2) if side > 0 else (seg < e2)
            zs.append((e2, side, key, known, int(known + np.argmax(hit)) if hit.any() else -1, ref))
        m.zones = zs
        return m

    def active(self, b: int) -> list[tuple]:
        g = self.nh + b
        return [(e, s, k, r) for e, s, k, kn, br, r in self.zones if kn <= g - 1 and (br < 0 or br >= g)]
