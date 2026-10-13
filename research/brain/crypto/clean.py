"""L2 clean: Binance archive rows -> one naive-UTC 1-minute grid, values never invented.

  ts units     Binance's archive switched spot klines from milliseconds to MICROseconds on 1 Jan 2025 (futures stay in
               ms; funding and metrics differ again). `to_utc` decides the unit per value from its size, so a file, or a
               concat of months, that mixes units still lands on the right minute.
  headers      futures files have a header row since 2022, spot files none: non-numeric rows are dropped.
  duplicates   exact duplicates collapse; conflicting duplicates keep the first row and mark the minute suspect
               (research/brain/cube._on_grid, the Indian rule).
  gaps         a minute with no bar stays NaN with flag "gap" (an exchange outage is a gap, not a flat line).
  flags        research/brain/quality.series_flags with the crypto jump limit; a bar with zero volume is "stale" (no
               trade: Binance prints the previous close).
  slow series  funding, OI metrics and DVOL are placed on the minute they become known (`place_asof`) and carried
               forward with their age, up to config.MAX_AGE; older than that they are NaN.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import quality
from ..cube import _on_grid
from . import config

KLINE_COLS = ["open_time", "open", "high", "low", "close", "volume", "close_time", "quote_volume", "trades",
              "taker_buy_volume", "taker_buy_quote_volume", "ignore"]
BAR = ["open", "high", "low", "close", "volume", "quote_volume", "trades", "taker_buy_volume"]


def to_utc(x) -> pd.DatetimeIndex:
    """Epoch numbers in s, ms, us or ns (decided per value by magnitude) or date strings -> naive UTC timestamps."""
    s = pd.Series(x)
    num = pd.to_numeric(s, errors="coerce")
    if num.notna().all() and len(s):
        v = num.to_numpy(np.float64)
        a = np.abs(v)
        mult = np.where(a < 1e11, 1e9, np.where(a < 1e14, 1e6, np.where(a < 1e17, 1e3, 1.0)))
        ns = np.round(v * mult).astype("int64")
        return pd.DatetimeIndex(pd.to_datetime(ns, unit="ns"))
    t = pd.to_datetime(s, utc=True, format="mixed")
    return pd.DatetimeIndex(t.dt.tz_convert("UTC").dt.tz_localize(None))


def read_klines(path_or_buf) -> pd.DataFrame:
    """A Binance kline CSV (zip or plain, with or without a header) -> ts (bar start) + BAR columns."""
    d = pd.read_csv(path_or_buf, header=None, names=KLINE_COLS, dtype=str)
    d = d[pd.to_numeric(d.open_time, errors="coerce").notna()]
    out = pd.DataFrame({"ts": to_utc(d.open_time.astype(np.float64)).values})
    for c in BAR:
        out[c] = pd.to_numeric(d[c], errors="coerce").values
    return out


def read_funding(path_or_buf) -> pd.DataFrame:
    """fundingRate CSV (calc_time, funding_interval_hours, last_funding_rate) -> ts (settlement time), rate, interval_h."""
    d = pd.read_csv(path_or_buf)
    return pd.DataFrame({"ts": to_utc(d.calc_time).values, "rate": d.last_funding_rate.astype(float).values,
                         "interval_h": pd.to_numeric(d.get("funding_interval_hours", config.FUNDING_INTERVAL_H)).values})


METRIC_MAP = {"sum_open_interest": "oi", "sum_open_interest_value": "oi_value",
              "count_toptrader_long_short_ratio": "top_ls_accounts", "sum_toptrader_long_short_ratio": "top_ls_positions",
              "count_long_short_ratio": "ls_accounts", "sum_taker_long_short_vol_ratio": "taker_ls_vol"}


def read_metrics(path_or_buf) -> pd.DataFrame:
    """metrics CSV (create_time = the snapshot time) -> ts + oi, oi_value and the long/short ratios."""
    d = pd.read_csv(path_or_buf)
    out = pd.DataFrame({"ts": to_utc(d.create_time).values})
    for k, v in METRIC_MAP.items():
        out[v] = pd.to_numeric(d[k], errors="coerce").values if k in d else np.nan
    return out


def grid(frm: pd.Timestamp, to: pd.Timestamp) -> pd.DatetimeIndex:
    """Every UTC minute from frm to to inclusive (24x7: no sessions, no holidays)."""
    return pd.date_range(pd.Timestamp(frm).floor("min"), pd.Timestamp(to).floor("min"), freq="min", name="ts")


def bars_on_grid(df: pd.DataFrame | None, g: pd.DatetimeIndex, cols=BAR, jump=config.SUSPECT_JUMP,
                 prices=True) -> tuple[pd.DataFrame, np.ndarray, dict]:
    """1-minute bars on the grid with flags. prices=False for series that may be <= 0 (the premium index)."""
    src = df if df is not None else pd.DataFrame(columns=["ts"])
    have = [c for c in cols if c in src]
    B, info = _on_grid(src, g, have or ["close"])
    for c in cols:
        if c not in B:
            B[c] = np.nan
    if prices:
        flag = quality.series_flags(B.open, B.high, B.low, B.close, jump=jump)
        if "volume" in B:
            nov = (B.volume.to_numpy(float) == 0) & np.isfinite(B.close.to_numpy(float))
            flag[nov & (flag == "ok")] = "stale"
    else:
        flag = np.where(np.isfinite(B.close.to_numpy(float)), "ok", "gap").astype(object)
    if B.attrs.get("conflict"):
        # g.isin, not np.isin: np.isin(DatetimeIndex, [Timestamp, ...]) never matches (the Indian cube.build_day has
        # that bug, so its conflicting duplicates are not flagged; reported 13 Oct 2026, not changed here)
        flag[np.asarray(g.isin(list(B.attrs["conflict"])))] = "suspect"
    return B[cols], flag, info


def place_asof(ts_avail, values: np.ndarray, g: pd.DatetimeIndex) -> np.ndarray:
    """Put each value on the grid row that CONTAINS the instant it becomes available: row = floor(avail to the minute).
    That row closes after the value exists, even when avail falls exactly on a minute (a settlement at 08:00:00.000 is
    on the 08:00 row, known at 08:01). Later values win when two land on one row. Rows outside g are dropped."""
    t = pd.DatetimeIndex(ts_avail).floor("min")
    pos = g.get_indexer(t)
    out = np.full(len(g), np.nan)
    ok = pos >= 0
    order = np.argsort(np.asarray(t[ok]), kind="stable")
    out[pos[ok][order]] = np.asarray(values, float)[ok][order]
    return out


def ffill_age(a: np.ndarray, max_age: int) -> tuple[np.ndarray, np.ndarray]:
    """Carry the last finite value forward; return (value, age in minutes). Older than max_age -> NaN (both)."""
    a = np.asarray(a, float)
    n = len(a)
    idx = np.where(np.isfinite(a), np.arange(n), -1)
    last = np.maximum.accumulate(idx) if n else idx
    age = np.where(last >= 0, np.arange(n) - last, -1).astype(float)
    val = np.where(last >= 0, a[np.maximum(last, 0)], np.nan)
    stale = (last < 0) | (age > max_age)
    val[stale] = np.nan
    age[stale] = np.nan
    return val, age


def minutes_to_next(g: pd.DatetimeIndex, hours=config.FUNDING_HOURS) -> np.ndarray:
    """Minutes from the END of each bar (t + 1 min) to the next funding settlement on the fixed UTC schedule."""
    end = g + pd.Timedelta(minutes=1)
    m = (end.hour * 60 + end.minute).to_numpy()
    sched = np.array(sorted(h * 60 for h in hours) + [hours[0] * 60 + 1440])
    nxt = sched[np.searchsorted(sched, m, side="left")]
    return (nxt - m).astype(float)
