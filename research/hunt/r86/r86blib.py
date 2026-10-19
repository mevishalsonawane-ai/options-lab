"""R86b pure functions (no files, no network). Pre-registration: research/hunt/r86/PREREG_B.md.

Footprints of big-money trading read from one day of index minutes and the constituents' minute prices/volume.
Every function reads only rows up to its decision time (test_r86b.py checks this with a look-ahead guard).
"""
from __future__ import annotations

import numpy as np
import pandas as pd


def efficiency(o0: float, closes: np.ndarray) -> float:
    """abs(last - o0) / sum of abs minute changes (o0 -> c0 -> c1 ...). 1 = a straight line; ~0 = chop."""
    c = np.asarray(closes, float)
    if len(c) == 0 or not np.isfinite(o0):
        return np.nan
    path = np.abs(np.diff(np.r_[o0, c])).sum()
    return float(abs(c[-1] - o0) / path) if path > 0 else np.nan


def basket_minutes(eq: pd.DataFrame, symbols: set[str] | None = None) -> pd.DataFrame:
    """eq: symbol, day, mod, close, volume (minute rows). Per (day, mod): turnover, stocks up / down vs their previous
    minute's close the same day, and how many printed. The first minute of a stock's day has no previous close and is
    counted in turnover only."""
    e = eq if symbols is None else eq[eq.symbol.isin(symbols)]
    e = e.sort_values(["symbol", "day", "mod"])
    prev = e.groupby(["symbol", "day"]).close.shift(1)
    d = np.sign(e.close.to_numpy() - prev.to_numpy())
    has = ~np.isnan(d)
    t = pd.DataFrame({"day": e.day.to_numpy(), "mod": e["mod"].to_numpy(),
                      "tv": e.close.to_numpy() * e.volume.to_numpy(),
                      "up": (d > 0).astype(np.int16), "dn": (d < 0).astype(np.int16), "n": has.astype(np.int16)})
    return t.groupby(["day", "mod"], as_index=False).sum()


def burst_flags(up, dn, n, tv, tv_med, n_min: int, share: float = 0.8, mult: float = 3.0) -> np.ndarray:
    """+1 / -1 where >= `share` of the printing stocks moved one way and turnover >= mult x its median; else 0."""
    up, dn, n = (np.asarray(a, float) for a in (up, dn, n))
    tv, tv_med = np.asarray(tv, float), np.asarray(tv_med, float)
    ok = (n >= n_min) & (n > 0) & np.isfinite(tv_med) & (tv_med > 0) & (tv >= mult * tv_med)
    s = np.where(up >= share * n, 1, np.where(dn >= share * n, -1, 0))
    return np.where(ok, s, 0)


def same_slot_median(df: pd.DataFrame, col: str, lookback: int = 20, min_obs: int = 10) -> pd.Series:
    """For rows (day, slot, col): the median of `col` in the same slot over the previous `lookback` sessions
    (the current day excluded)."""
    w = df.pivot(index="day", columns="slot", values=col).sort_index()
    med = w.shift(1).rolling(lookback, min_periods=min_obs).median()
    s = med.stack(future_stack=True).rename("med").reset_index()
    return df[["day", "slot"]].merge(s, on=["day", "slot"], how="left")["med"].to_numpy()


def prev_quantile(x: pd.Series, q: float, window: int = 250, min_obs: int = 120) -> pd.Series:
    """Quantile of the previous `window` values (today excluded)."""
    return x.shift(1).rolling(window, min_periods=min_obs).quantile(q)


def five_min_bars(mod, o, h, l, c, tv) -> pd.DataFrame:
    """Aggregate one day's minutes into 5-minute bars aligned at 09:15 (slot = (mod - 555) // 5)."""
    mod = np.asarray(mod)
    slot = (mod - 555) // 5
    d = pd.DataFrame({"slot": slot, "o": o, "h": h, "l": l, "c": c, "tv": tv, "i": np.arange(len(mod))})
    g = d.groupby("slot")
    return pd.DataFrame({"o": g.o.first(), "h": g.h.max(), "l": g.l.min(), "c": g.c.last(), "tv": g.tv.sum(),
                         "i_last": g.i.max(), "nmin": g.i.count()}).reset_index()


def absorption_bars(bars: pd.DataFrame, tv_med, rng_med, start_slot: int = 12, tv_mult: float = 2.0,
                    rng_mult: float = 0.5) -> list[tuple[int, int]]:
    """(bar row, side) for completed 5-minute bars from `start_slot` (12 = 10:15) that make a new day high (side -1,
    fade) or low (side +1) with turnover >= tv_mult x and range <= rng_mult x the slot medians. A bar is a new high if
    its high exceeds every earlier bar's high that day."""
    out = []
    hi, lo = -np.inf, np.inf
    for r in range(len(bars)):
        b = bars.iloc[r]
        new_hi, new_lo = b.h > hi, b.l < lo
        if r > 0 and b.slot >= start_slot and np.isfinite(tv_med[r]) and np.isfinite(rng_med[r]) and rng_med[r] > 0:
            quiet = (b.h - b.l) / b.c <= rng_mult * rng_med[r]
            heavy = b.tv >= tv_mult * tv_med[r]
            if quiet and heavy and new_hi != new_lo:
                out.append((r, -1 if new_hi else 1))
        hi, lo = max(hi, b.h), min(lo, b.l)
    return out


def sim_overnight(entry: float, side: int, stop_dist: float, next_open: float, next_lows, next_highs,
                  exit_close: float):
    """Overnight hold: a next-day open beyond the stop fills at the open; then the morning minutes' lows/highs; else
    the exit close. Returns (price, stopped)."""
    st = entry - side * stop_dist
    if (next_open - st) * side <= 0:
        return next_open, True
    for lo, hi in zip(next_lows, next_highs):
        if (lo <= st) if side > 0 else (hi >= st):
            return st, True
    return exit_close, False
