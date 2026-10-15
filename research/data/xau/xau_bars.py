"""Gold lab pack, step 2: multi-timeframe bars from the clean 1-minute base (15 Oct 2026).

5m, 15m, 1h and 4h bars are UTC clock buckets (floor of ts_utc); 1d bars are trading days (the day rolls at 17:00
New York, xau_clean.trading_day). A bar is labelled by its OPEN time `ts_utc`; `ts_end` is when it is complete
(label + timeframe for clock bars; the close of the day's last minute for 1d). Only minutes that exist are used, so a
bucket with no rows has no bar.

Columns: ts_utc, ts_end, open high low close (mid), open/close _bid _ask, spread_mean spread_min spread_max
spread_last (per-minute close spreads inside the bar, USD), volume_bid volume_ask volume (Dukascopy tick volume,
summed), n_minutes, n_flagged (minutes with any quality flag except flag_out_of_hours).
"""
from __future__ import annotations

import pandas as pd

TIMEFRAMES = {"5m": "5min", "15m": "15min", "1h": "1h", "4h": "4h", "1d": "1d"}
TF_MINUTES = {"1m": 1, "5m": 5, "15m": 15, "1h": 60, "4h": 240}
QUALITY = ["flag_zero_vol_side", "flag_stale", "flag_crossed", "flag_wide_spread", "flag_bad_tick"]


def bar_key(df, tf):
    """Bar label (open time, UTC) of every row for timeframe tf ("1d" = the trading day's first row's time)."""
    if tf == "1d":
        return df.groupby("trading_day").ts_utc.transform("min")
    return df.ts_utc.dt.floor(TIMEFRAMES[tf])


def make_bars(df, tf):
    key = bar_key(df, tf)
    flagged = df[[c for c in QUALITY if c in df]].any(axis=1)
    x = df.assign(_k=key, _f=flagged)
    g = x.groupby("_k", sort=True)
    out = pd.DataFrame({
        "open": g.open.first(), "high": g.high.max(), "low": g.low.min(), "close": g.close.last(),
        "open_bid": g.open_bid.first(), "open_ask": g.open_ask.first(),
        "close_bid": g.close_bid.last(), "close_ask": g.close_ask.last(),
        "spread_mean": g.spread.mean(), "spread_min": g.spread.min(), "spread_max": g.spread.max(),
        "spread_last": g.spread.last(),
        "volume_bid": g.volume_bid.sum(), "volume_ask": g.volume_ask.sum(), "volume": g.volume.sum(),
        "n_minutes": g.size().astype("int32"), "n_flagged": g._f.sum().astype("int32"),
    })
    out.index.name = "ts_utc"
    out = out.reset_index()
    if tf == "1d":
        out.insert(1, "ts_end", g.ts_utc.max().to_numpy() + pd.Timedelta(minutes=1))
        out.insert(2, "trading_day", g.trading_day.first().to_numpy())
    else:
        out.insert(1, "ts_end", out.ts_utc + pd.Timedelta(TIMEFRAMES[tf]))
    return out


def all_bars(df):
    return {tf: make_bars(df, tf) for tf in TIMEFRAMES}
