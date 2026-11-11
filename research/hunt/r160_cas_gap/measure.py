"""R160 measures (PREREG 4). Pure functions: no file is read here.

Day t's late level L, basis b and late move M use only bars up to 15:27 of t; the official close C and the next open O
are outcomes. Everything is returned in basis points (1e4 x log ratio).
"""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd

from . import cfg as K


def implied_minutes(opt: pd.DataFrame) -> pd.DataFrame:
    """Option-implied index per minute: the median over ATM offsets -2..+2 of strike + call - put (put-call parity).

    opt: columns ts (naive IST), offset, side ('C'/'P'), close, strike. An offset counts only when both sides have a close
    above 0 at the same strike in that minute; a minute needs K.MIN_OFFSETS offsets. Returns columns d, r, F, n.
    """
    x = opt[opt.offset.isin(K.OFFSETS) & (opt.close > 0)]
    if x.empty:
        return pd.DataFrame(columns=["d", "r", "F", "n"])
    w = x.pivot_table(index=["ts", "offset", "strike"], columns="side", values="close", aggfunc="last", observed=True)
    w = w.dropna(subset=[c for c in ("C", "P") if c in w.columns])
    if not {"C", "P"} <= set(w.columns):
        return pd.DataFrame(columns=["d", "r", "F", "n"])
    w = w.reset_index()
    w["f"] = w.strike + w.C - w.P
    g = w.groupby("ts").f.agg(["median", "size"]).reset_index()
    g = g[g["size"] >= K.MIN_OFFSETS]
    out = pd.DataFrame({"d": g.ts.dt.date, "r": g.ts.dt.hour * 60 + g.ts.dt.minute - (9 * 60 + 15),
                        "F": g["median"].to_numpy(float), "n": g["size"].to_numpy(int)})
    return out[(out.r >= 0) & (out.r < K.N)].reset_index(drop=True)


def to_grid(df: pd.DataFrame, col: str) -> dict:
    """{day: array of K.N minutes} from columns d, r and col (NaN where a minute is missing)."""
    out = {}
    for d, g in df.groupby("d"):
        a = np.full(K.N, np.nan)
        a[g.r.to_numpy(int)] = g[col].to_numpy(float)
        out[d] = a
    return out


def basis(f: np.ndarray, idx: np.ndarray) -> float:
    """Median of derivative-implied minus index over 14:30-14:59 (needs K.MIN_BASIS_MIN valid minutes)."""
    lo, hi = K.BASIS_ROWS
    d = f[lo: hi + 1] - idx[lo: hi + 1]
    d = d[np.isfinite(d)]
    return float(np.median(d)) if d.size >= K.MIN_BASIS_MIN else np.nan


def late_level(f: np.ndarray, b: float) -> float:
    """Median derivative-implied index over 15:20-15:27, minus the basis (needs K.MIN_LATE_MIN valid minutes)."""
    lo, hi = K.LATE_ROWS
    v = f[lo: hi + 1]
    v = v[np.isfinite(v)]
    if v.size < K.MIN_LATE_MIN or not np.isfinite(b):
        return np.nan
    return float(np.median(v) - b)


def bps(a: float, b: float) -> float:
    if not (np.isfinite(a) and np.isfinite(b) and a > 0 and b > 0):
        return np.nan
    return float(1e4 * np.log(a / b))


def day_measures(L: float, C: float, O: float, i1514: float, i0920: float) -> dict:
    """D, G, R, M, G20 (PREREG 4)."""
    D, G = bps(L, C), bps(O, C)
    return dict(D=D, G=G, R=bps(O, L), M=bps(L, i1514), G20=bps(i0920, C))


def expiry_days(days: list, weekday: int, monthly: bool) -> set:
    """Expiry days from the trading calendar: the weekday of each week (or the last such weekday of each month),
    moved back to the previous trading day when it is a holiday."""
    days = sorted(days)
    if not days:
        return set()
    have = set(days)
    out = set()
    d0, d1 = days[0], days[-1]
    cur = d0 - timedelta(days=(d0.weekday() - weekday) % 7)
    targets = []
    while cur <= d1:                       # a target after the data's end is unknown (holiday or not): skipped
        targets.append(cur)
        cur += timedelta(days=7)
    if monthly:
        last = {}
        for t in targets:
            last[(t.year, t.month)] = max(last.get((t.year, t.month), t), t)
        # keep a month only when its last such weekday is known to be the last (the next one falls in another month)
        targets = sorted(t for t in last.values() if (t + timedelta(days=7)).month != t.month)
    for t in targets:
        x = t
        while x not in have and x >= d0:
            x -= timedelta(days=1)
        if x in have and x <= d1:
            out.add(x)
    return out


def in_period(d: date, p: tuple) -> bool:
    return p[0] <= d <= p[1]
