"""R101 features (PREREG section 2), all point-in-time. Pure functions over plain arrays / dicts, plus loaders.

Every feature read for a signal whose entry row is `er` (the first minute after the signal bar) uses only:
  - VIX minutes strictly before `er` (rows 0 .. er-1 of the day);
  - the 5-minute bar that ENDS at minute er-1 (bar (er-1)//5) of each index;
  - daily data of sessions strictly BEFORE the day (the day's own open for the gap: known at 09:15 < er).
test_r101.py checks each.
"""
from __future__ import annotations

import math
from datetime import date
from functools import lru_cache

import numpy as np
import pandas as pd

import r101cfg as K

OPEN_MIN = 9 * 60 + 15
NROWS = 375


# ------------------------------------------------------------------------------------------------ VIX intraday
def _years():
    return range(K.START.year, K.END.year + 1)


@lru_cache(maxsize=1)
def vix_minutes() -> dict:
    """{day: (opens (375,), closes (375,))} of India VIX, NaN where the lake has no minute."""
    out = {}
    for y in _years():
        p = K.LAKE / f"index_1m_INDIAVIX_{y}.parquet"
        if not p.exists():
            continue
        df = pd.read_parquet(p, columns=["ts", "open", "close"])
        ts = pd.to_datetime(df.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        r = (ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).to_numpy()
        days = ts.dt.date.to_numpy()
        ok = (r >= 0) & (r < NROWS)
        for d in np.unique(days[ok]):
            m = ok & (days == d)
            o = np.full(NROWS, np.nan); c = np.full(NROWS, np.nan)
            o[r[m]] = df.open.to_numpy()[m]; c[r[m]] = df.close.to_numpy()[m]
            out[d] = (o, c)
    return out


def vix_change(day_min: tuple | None, er: int) -> float:
    """VIX at the minute before `er` against the day's 09:15 open: closes[er-1] / opens[0] - 1; NaN if unknown. Pure."""
    if day_min is None or er < 1 or er > NROWS:
        return math.nan
    o, c = day_min
    if not (np.isfinite(o[0]) and np.isfinite(c[er - 1])) or o[0] <= 0:
        return math.nan
    return float(c[er - 1] / o[0] - 1.0)


def vix_confirms(chg: float, side: str, thr: float = K.VIX_INTRADAY) -> bool:
    """L2: a put needs VIX up >= thr, a call VIX down >= thr; unknown never confirms. Pure."""
    if not np.isfinite(chg):
        return False
    return chg >= thr if side == "P" else chg <= -thr


# ------------------------------------------------------------------------------------------------ daily
@lru_cache(maxsize=1)
def daily() -> dict:
    """{symbol: DataFrame(date, open, close) sorted} from the lake's index_daily."""
    df = pd.read_parquet(K.LAKE / "index_daily.parquet", columns=["symbol", "date", "open", "close"])
    df["date"] = pd.to_datetime(df.date).dt.date
    return {s: g.sort_values("date").reset_index(drop=True) for s, g in df.groupby("symbol")}


def _before(df: pd.DataFrame, day: date) -> pd.DataFrame:
    return df[df.date < day]


def vix_regime(df_vix: pd.DataFrame, day: date, n: int = K.VIX_MEDIAN_N) -> str | None:
    """'LO' when the previous session's close <= the median of the n sessions before it, else 'HI'; None if unknown. Pure."""
    b = _before(df_vix, day)
    if len(b) < n + 1:
        return None
    prev = float(b.close.iloc[-1])
    med = float(np.median(b.close.iloc[-n - 1:-1].to_numpy()))
    if not (np.isfinite(prev) and np.isfinite(med)):
        return None
    return "LO" if prev <= med else "HI"


def rv_rank(df_idx: pd.DataFrame, day: date, n: int = K.RV_N, rank_n: int = K.RV_RANK_N) -> float:
    """The index's n-session realised vol (std of daily log returns, ending at the previous session) ranked among the
    previous rank_n sessions' values: the share of those at or below it (0..1); NaN if unknown. Pure."""
    b = _before(df_idx, day)
    c = b.close.to_numpy(dtype=float)
    if len(c) < n + rank_n + 1:
        return math.nan
    r = np.diff(np.log(c))
    rv = np.array([r[i - n:i].std(ddof=1) for i in range(n, len(r) + 1)])      # rv[k] ends at return index n+k-1
    cur = rv[-1]
    hist = rv[-rank_n - 1:-1]
    if not np.isfinite(cur) or len(hist) < rank_n:
        return math.nan
    return float((hist <= cur).mean())


def gap_pct(df_idx: pd.DataFrame, day: date) -> float:
    """(today's open - previous session's close) / previous close from the daily file; NaN if unknown. Pure."""
    b = _before(df_idx, day)
    t = df_idx[df_idx.date == day]
    if b.empty or t.empty:
        return math.nan
    pc, o = float(b.close.iloc[-1]), float(t.open.iloc[0])
    if not (np.isfinite(pc) and np.isfinite(o)) or pc <= 0:
        return math.nan
    return o / pc - 1.0


# ------------------------------------------------------------------------------------------------ bar breadth
def bar_colours(bars5_by_und: dict) -> dict:
    """{(und, day, bar): +1 green / -1 red / 0 flat} from fam_r44.bars5 tuples (d, b, o, h, l, c). Pure."""
    out = {}
    for u, (d, b, o, h, l, c) in bars5_by_und.items():
        for i in range(len(d)):
            out[(u, d[i], int(b[i]))] = int(np.sign(c[i] - o[i]))
    return out


def breadth_agree(colours: dict, unds, day: date, er: int, side: str) -> int:
    """How many of `unds` had the 5-minute bar ending at minute er-1 (bar (er-1)//5) close the signal's way. A missing
    bar does not agree. Pure."""
    if er < 1:
        return 0
    b = (er - 1) // 5
    want = 1 if side == "C" else -1
    return sum(1 for u in unds if colours.get((u, day, b), 0) == want)


# ------------------------------------------------------------------------------------------------ OOV
def oov_far(lev, open_px: float, side: str) -> float:
    """How far the open sits beyond the value edge, in value-area widths; NaN if unknown. Pure."""
    if lev is None or not np.isfinite(lev).all() or not np.isfinite(open_px):
        return math.nan
    _, vah, val = lev
    w = vah - val
    if w <= 0:
        return math.nan
    return (open_px - vah) / w if side == "C" else (val - open_px) / w


# ------------------------------------------------------------------------------------------------ weekday (L4)
def worst_weekday(trades: list, before: date) -> int | None:
    """The weekday (0 = Monday) with the lowest total net over the trades whose day is before `before`; None if no
    trade. Pure."""
    tot = {}
    for t in trades:
        if t["day"] < before:
            tot[t["day"].weekday()] = tot.get(t["day"].weekday(), 0.0) + t["net"]
    if not tot:
        return None
    return min(sorted(tot), key=lambda k: tot[k])


def dow_drop_for(day: date, picks: dict) -> int | None:
    """The weekday dropped on `day`: the pick of its walk-forward quarter (None before the window = no filter), or the
    'final' pick. picks: {(lo, hi): weekday or None}. Pure."""
    for (lo, hi), wd in picks.items():
        if lo <= day <= hi:
            return wd
    return None
