"""R53 crypto data loaders (cache outside git, $IRAALGO_CACHE/r53; see fetch.py and ../fetch_crypto.py).

All timestamps are tz-aware UTC; a bar's t is its START. Prices in USD(T).
"""
from __future__ import annotations

import functools

import numpy as np
import pandas as pd

from .. import common as RC

COINS = ("BTC", "ETH")


def _p(name: str):
    return RC.cache_dir() / name


@functools.lru_cache(maxsize=None)
def perp_1m(coin: str) -> pd.DataFrame:
    """Binance USD-M perp 1m bars on a gap-free minute grid (missing minutes as NaN rows). Index t (bar start)."""
    d = pd.read_parquet(_p(f"um_{coin}_1m.parquet"))
    return to_grid(d)


def to_grid(d: pd.DataFrame) -> pd.DataFrame:
    d = d.copy()
    d["t"] = pd.to_datetime(d.t, utc=True)
    d = d.drop_duplicates("t").set_index("t").sort_index()[["o", "h", "l", "c"]].astype(float)
    if len(d):
        d = d.reindex(pd.date_range(d.index[0], d.index[-1], freq="min", name="t"))
    return d


def resample(m1: pd.DataFrame, rule: str) -> pd.DataFrame:
    """Bars of `rule` (e.g. '5min', '4h') from 1m bars, label = start; a bar needs at least one 1m bar."""
    r = m1.resample(rule, label="left", closed="left").agg({"o": "first", "h": "max", "l": "min", "c": "last"})
    return r.dropna(subset=["c"])


@functools.lru_cache(maxsize=None)
def index_1m(coin: str) -> pd.Series:
    """Delta India index close (.DEXBTUSD / .DEETHUSD) by 1m bar start."""
    d = pd.read_parquet(_p(f"delta_idx_{coin}_1m.parquet"))
    return d.drop_duplicates("t").set_index("t").c.sort_index()


@functools.lru_cache(maxsize=None)
def dvol_1h(coin: str) -> pd.Series:
    d = pd.read_parquet(_p(f"dvol_{coin}.parquet"))
    return d.drop_duplicates("t").set_index("t").c.sort_index()


@functools.lru_cache(maxsize=None)
def metrics(coin: str) -> pd.DataFrame:
    return pd.read_parquet(_p(f"metrics_{coin}.parquet"))


@functools.lru_cache(maxsize=None)
def funding(coin: str) -> pd.Series:
    """Binance settled funding rate by settlement time (8-hourly). The archive ends 30 Sep 2026: the last print is
    carried forward for October 2026 (NOTES.md)."""
    d = pd.read_parquet(_p(f"funding_{coin}.parquet"))
    s = d.assign(t=pd.to_datetime(d.t, utc=True).dt.floor("min")).drop_duplicates("t").set_index("t").rate.sort_index()
    return s


@functools.lru_cache(maxsize=None)
def usdinr() -> pd.Series:
    d = pd.read_parquet(_p("usdinr.parquet"))
    return d.assign(t=pd.to_datetime(d.t, utc=True)).set_index("t").usdinr.sort_index()


def fx_at(ts) -> np.ndarray:
    """ECB USD/INR known at ts: the last daily fix dated before ts's UTC day (no same-day look-ahead)."""
    s = usdinr()
    t = pd.DatetimeIndex(ts).floor("D") - pd.Timedelta(seconds=1)
    return asof(s, t)


def asof(s: pd.Series, t) -> np.ndarray:
    """The last value of s at or before each t (NaN before the first)."""
    i = s.index.searchsorted(pd.DatetimeIndex(t), side="right") - 1
    v = s.to_numpy(float)
    return np.where(i >= 0, v[np.maximum(i, 0)], np.nan)


def funding_rate_at(coin: str, ts) -> np.ndarray:
    """The last settled funding rate known at ts (F1C charges this rate on each settlement held)."""
    return asof(funding(coin), ts)
