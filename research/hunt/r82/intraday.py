"""R82 intraday book (PREREG.md section 4): L1-L3, E1 (Liquidity 1h on MCX hours), O1 (overnight COMEX move with
gold+silver agreement), U2 / U3 (the US open hour with agreement; U3 on US data days). Signal source: COMEX GC=F / SI=F
1-hour bars (a proxy for the MCX GOLDM / SILVERM future); the trade is the future, 1 lot, never held overnight.

Bars are UTC starts. MCX window (PREREG section 3): fills from the bar opening 04:00 UTC (09:30 IST); everything is flat
at the open of the bar starting flat_hour(day) (17:00 UTC in US summer time, 18:00 in winter).
"""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass

import numpy as np
import pandas as pd

from research.hunt.r82 import r82lib as L

sys.path.insert(0, str(L.HERE.parents[2]))                   # the repo root, for indicator.liquidity
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

METALS = ("gold", "silver")

FOMC = """2024-01-31 2024-03-20 2024-05-01 2024-06-12 2024-07-31 2024-09-18 2024-11-07 2024-12-18 2025-01-29 2025-03-19
2025-05-07 2025-06-18 2025-07-30 2025-09-17 2025-10-29 2025-12-10 2026-01-28 2026-03-18 2026-04-29 2026-06-17 2026-07-29
2026-09-16 2026-10-28"""
# CPI / NFP: R2's lists (hunt/r2/events.py, bls.gov) Jun 2024 - Sep 2026, plus research/data/xau/us_events.csv's Oct 2026.
CPI = """2024-06-12 2024-07-11 2024-08-14 2024-09-11 2024-10-10 2024-11-13 2024-12-11 2025-01-15 2025-02-12 2025-03-12
2025-04-10 2025-05-13 2025-06-11 2025-07-15 2025-08-12 2025-09-11 2025-10-24 2025-12-18 2026-01-13 2026-02-13 2026-03-11
2026-04-10 2026-05-12 2026-06-10 2026-07-14 2026-08-12 2026-09-11 2026-10-14"""
NFP = """2024-06-07 2024-07-05 2024-08-02 2024-09-06 2024-10-04 2024-11-01 2024-12-06 2025-01-10 2025-02-07 2025-03-07
2025-04-04 2025-05-02 2025-06-06 2025-07-03 2025-08-01 2025-09-05 2025-11-20 2025-12-16 2026-01-09 2026-02-11 2026-03-06
2026-04-03 2026-05-08 2026-06-05 2026-07-02 2026-08-07 2026-09-04 2026-10-02"""
EVENT_DAYS = {pd.Timestamp(x).date() for x in (FOMC + " " + CPI + " " + NFP).split()}


def ist_date(ts) -> object:
    return (pd.Timestamp(ts) + pd.Timedelta(hours=5, minutes=30)).date()


def in_entry_window(ts) -> bool:
    """A fill at the open of the bar starting ts is allowed: weekday, 04:00 UTC <= start < flat hour."""
    t = pd.Timestamp(ts)
    return t.weekday() < 5 and L.ENTRY_FIRST_HOUR <= t.hour < L.flat_hour(t)


@dataclass
class Hourly:
    df: pd.DataFrame
    zones: list
    atr14: np.ndarray
    ema20: np.ndarray

    @classmethod
    def build(cls, df: pd.DataFrame, confirm: int = 15) -> "Hourly":
        z = swing_zones(df, 20, "full") + pool_zones(df, 2, 5, confirm)
        return cls(df, z, L.wilder_atr(df.high.values, df.low.values, df.close.values, 14), L.ema(df.close.values, 20))


def build_all(frames: dict) -> dict:
    return {m: Hourly.build(frames[m]) for m in METALS}


# ------------------------------------------------------------------------------------------------ the shared exit loop
def _exit_loop(hd: Hourly, metal, side, j, entry, stop, target=None, level=None, new_level_side=None, known=None,
               max_bars=None, last_ok=None):
    """Walk bars from the entry bar j. Exits, in order: the stop (bar low / high), the target (touch), close-based exits
    (a failed break of [level], a new level of [new_level_side]), the flat time (the open of the flat bar, or the last
    bar's close before a gap), [max_bars] bars held. Returns (exit index, exit price before slippage, why, stop fill?)."""
    df = hd.df
    o, h, l, c = df.open.values, df.high.values, df.low.values, df.close.values
    idx = df.index
    day = idx[j].normalize()
    fh = L.flat_hour(idx[j])
    n = len(df)
    for i in range(j, n):
        if i > j and (idx[i].normalize() != day or idx[i].hour >= fh):
            return i, o[i] if (idx[i].normalize() == day and idx[i].hour == fh) else c[i - 1], "flat", False
        if side > 0 and l[i] <= stop:
            return i, min(stop, o[i]), "stop", True
        if side < 0 and h[i] >= stop:
            return i, max(stop, o[i]), "stop", True
        if target is not None and ((side > 0 and h[i] >= target) or (side < 0 and l[i] <= target)):
            return i, (max(target, o[i]) if side > 0 else min(target, o[i])), "target", False
        if level is not None and side * (c[i] - level) < 0:
            return i, c[i], "failed_break", False
        if new_level_side is not None and known is not None and i in known and any(z.side == new_level_side for z in known[i]):
            return i, c[i], "new_level", False
        if max_bars is not None and i - j + 1 >= max_bars:
            return i, c[i], "bars", False
        if last_ok is not None and i >= last_ok:
            return i, c[i], "window_end", False
    return n - 1, c[n - 1], "data_end", False


def _trade(metal, side, hd, j, entry_px, xi, xpx, why, stop_fill, stop_dist) -> L.Trade:
    units = L.LOT[metal][1]
    e = L.fill(entry_px, side)
    x = L.fill(xpx, -side, stop_fill)
    return L.Trade(metal, side, hd.df.index[j], hd.df.index[xi], e, x, why, units, 0.0, bars=xi - j + 1,
                   stop_dist=stop_dist)


# ------------------------------------------------------------------------------------------------ L1-L3, E1
def liquidity(hs: dict, lo, hi, both_sides=False, agree=False, skip_events=False) -> list[L.Trade]:
    out = []
    lo, hi = pd.Timestamp(lo), pd.Timestamp(hi) + pd.Timedelta(days=1)
    for m in METALS:
        hd = hs[m]
        other = hs["silver" if m == "gold" else "gold"]
        df = hd.df
        idx, o, c = df.index, df.open.values, df.close.values
        breaks, known = {}, {}
        for z in hd.zones:
            known.setdefault(z.known, []).append(z)
            if z.broken >= 0:
                breaks.setdefault(z.broken, []).append(z)
        swings = [z for z in hd.zones if z.kind == "swing"]
        oidx = other.df.index
        busy_until = -1
        for i in range(len(df) - 1):
            j = i + 1
            if j <= busy_until or not (lo <= idx[j] < hi) or not in_entry_window(idx[j]):
                continue
            if idx[j] - idx[i] > pd.Timedelta(hours=1):      # the next bar must follow directly
                continue
            if skip_events and ist_date(idx[j]) in EVENT_DAYS:
                continue
            cand = [p for p in breaks.get(i, []) if p.kind == "pool" and (p.side > 0 or both_sides)
                    and any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                            and (s.broken < 0 or s.broken >= i) for s in swings)]
            if not cand or not np.isfinite(hd.atr14[i]):
                continue
            z = cand[0]
            side = z.side
            if agree:
                k = oidx.searchsorted(idx[i], side="right") - 1      # the other metal's bar that closed with bar i
                if k < 0 or oidx[k] != idx[i] or side * (other.df.close.values[k] - other.ema20[k]) <= 0:
                    continue
            px = o[j]
            ahead = sorted({q.edge for q in hd.zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i)
                            and side * (q.edge - px) > 0}, key=lambda e: side * e)
            target = ahead[1] if len(ahead) > 1 else (ahead[0] if ahead else None)
            stop = px - side * hd.atr14[i]
            xi, xpx, why, sf = _exit_loop(hd, m, side, j, px, stop, target, z.edge, side, known)
            out.append(_trade(m, side, hd, j, px, xi, xpx, why, sf, hd.atr14[i] / px))
            busy_until = xi
    return out


# ------------------------------------------------------------------------------------------------ O1
def overnight(hs: dict, lo, hi, thr=0.002) -> list[L.Trade]:
    out = []
    g, s = hs["gold"].df, hs["silver"].df
    days = pd.bdate_range(pd.Timestamp(lo), pd.Timestamp(hi))
    for d in days:
        t0 = d + pd.Timedelta(hours=L.ENTRY_FIRST_HOUR)
        prev = d - pd.offsets.BDay(1)
        ref = prev + pd.Timedelta(hours=L.flat_hour(prev) - 1)     # the bar ending at the previous flat time
        sig = {}
        for m, df in (("gold", g), ("silver", s)):
            if t0 not in df.index or ref not in df.index:
                break
            sig[m] = np.log(df.loc[t0, "open"] / df.loc[ref, "close"])
        if len(sig) < 2:
            continue
        sg, ss = sig["gold"], sig["silver"]
        if np.sign(sg) != np.sign(ss) or min(abs(sg), abs(ss)) < thr:
            continue
        side = int(np.sign(sg))
        for m in METALS:
            hd = hs[m]
            j = hd.df.index.get_loc(t0)
            a = hd.atr14[j - 1]
            if not np.isfinite(a):
                continue
            px = hd.df.open.values[j]
            xi, xpx, why, sf = _exit_loop(hd, m, side, j, px, px - side * a)
            out.append(_trade(m, side, hd, j, px, xi, xpx, why, sf, a / px))
    return out


# ------------------------------------------------------------------------------------------------ U2 / U3
def us_hour(hs: dict, lo, hi, events_only=False) -> list[L.Trade]:
    out = []
    days = pd.bdate_range(pd.Timestamp(lo), pd.Timestamp(hi))
    for d in days:
        if events_only and d.date() not in EVENT_DAYS:
            continue
        u = d + pd.Timedelta(hours=13 if L.us_dst(d) else 14)
        nxt = u + pd.Timedelta(hours=1)
        dirs = {}
        for m in METALS:
            df = hs[m].df
            if u not in df.index or nxt not in df.index:
                break
            dirs[m] = int(np.sign(df.loc[u, "close"] - df.loc[u, "open"]))
        if len(dirs) < 2 or dirs["gold"] == 0 or dirs["gold"] != dirs["silver"]:
            continue
        side = dirs["gold"]
        for m in METALS:
            hd = hs[m]
            k = hd.df.index.get_loc(u)
            j = k + 1
            if not in_entry_window(hd.df.index[j]):
                continue
            px = hd.df.open.values[j]
            stop = hd.df.low.values[k] if side > 0 else hd.df.high.values[k]
            if side * (px - stop) <= 0:
                continue                                           # already through the stop at the fill
            xi, xpx, why, sf = _exit_loop(hd, m, side, j, px, stop)
            out.append(_trade(m, side, hd, j, px, xi, xpx, why, sf, abs(px - stop) / px))
    return out


TESTS = {
    "L1": lambda hs, lo, hi: liquidity(hs, lo, hi),
    "L2": lambda hs, lo, hi: liquidity(hs, lo, hi, agree=True),
    "L3": lambda hs, lo, hi: liquidity(hs, lo, hi, both_sides=True),
    "E1": lambda hs, lo, hi: liquidity(hs, lo, hi, skip_events=True),
    "O1": lambda hs, lo, hi: overnight(hs, lo, hi),
    "U2": lambda hs, lo, hi: us_hour(hs, lo, hi),
    "U3": lambda hs, lo, hi: us_hour(hs, lo, hi, events_only=True),
}


# ------------------------------------------------------------------------------------------------ twins
def twin_books(trades: list[L.Trade], hs: dict, lo, hi, n=L.N_TWINS, seed=L.SEED) -> np.ndarray:
    """Same metal and side, the same UTC entry hour, the same stop distance (% of price), a random weekday of lo..hi that
    has that bar inside the MCX entry window; exit at the stop, at the flat time, or after the real trade's bar count."""
    rng = np.random.default_rng(seed)
    books = np.zeros(n)
    lo, hi = pd.Timestamp(lo), pd.Timestamp(hi) + pd.Timedelta(days=1)
    pools = {}
    for t in trades:
        key = (t.metal, t.entry_t.hour)
        if key not in pools:
            idx = hs[t.metal].df.index
            ok = [k for k, ts in enumerate(idx) if lo <= ts < hi and ts.hour == t.entry_t.hour and in_entry_window(ts)]
            pools[key] = np.array(ok)
        cand = pools[key]
        if len(cand) == 0:
            continue
        hd = hs[t.metal]
        picks = cand[rng.integers(0, len(cand), size=n)]
        for b, j in enumerate(picks):
            px = hd.df.open.values[j]
            stop = px * (1 - t.side * t.stop_dist)
            xi, xpx, why, sf = _exit_loop(hd, t.metal, t.side, j, px, stop, max_bars=t.bars)
            books[b] += _trade(t.metal, t.side, hd, j, px, xi, xpx, why, sf, t.stop_dist).net
    return books
