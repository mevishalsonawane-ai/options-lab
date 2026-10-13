"""RC2 data loaders. Raw files live in config.RAW (never in git):
  delta_und/{COIN}_idx5m.parquet       Delta India index (.DEXBTUSD / .DEETHUSD), 5-minute bars, t = bar start (UTC)
  delta_opt/{COIN}_{YYYYMMDD}.parquet  Delta India option MARK (and some traded) 5-minute candles for the daily expiry
                                       settling that day at 12:00 UTC, over the 30 hours before settlement
  deribit/{COIN}_dvol1h.parquet, {COIN}_funding1h.parquet   Deribit DVOL and perp funding, hourly, t = hour start
"""
import functools
import os

import numpy as np
import pandas as pd

from . import config as C


def index5m(coin):
    d = pd.read_parquet(os.path.join(C.RAW, "delta_und", f"{coin}_idx5m.parquet"))
    d = d.rename(columns={"open": "o", "high": "h", "low": "l", "close": "c"}).set_index("t").sort_index()
    full = pd.date_range(d.index.min(), d.index.max(), freq="5min")
    d = d.reindex(full)
    d["gap"] = d.c.isna()
    d[["c"]] = d[["c"]].ffill()
    for k in ("o", "h", "l"):
        d[k] = d[k].fillna(d.c)
    d.index.name = "t"
    return d


def dvol1h(coin):
    d = pd.read_parquet(os.path.join(C.RAW, "deribit", f"{coin}_dvol1h.parquet")).set_index("t").sort_index()
    return d.c


def funding1h(coin):
    d = pd.read_parquet(os.path.join(C.RAW, "deribit", f"{coin}_funding1h.parquet")).set_index("t").sort_index()
    return d.interest_8h


class Panel:
    """One coin's daily Delta expiry: dense 5-minute MARK arrays per (type, strike) on the 30 hours before settlement.
    times[i] is the START of bar i. A bar with no candle is carried forward from the last one (none before listing)."""

    def __init__(self, coin, expiry, df):
        self.coin, self.expiry = coin, expiry
        self.times = pd.date_range(expiry - pd.Timedelta(hours=30), expiry - C.BAR, freq="5min")
        self.t0 = self.times[0]
        self.n = len(self.times)
        self.arr = {}
        m = df[df.kind == "mark"]
        for (typ, K), g in m.groupby(["typ", "K"]):
            idx = ((pd.to_datetime(g.time, unit="s", utc=True) - self.t0) // C.BAR).to_numpy()
            ok = (idx >= 0) & (idx < self.n)
            a = np.full((self.n, 4), np.nan)
            a[idx[ok]] = g[["open", "high", "low", "close"]].to_numpy(float)[ok]
            a = pd.DataFrame(a).ffill().to_numpy()   # carry forward missing bars (no backfill: no look-ahead)
            self.arr[(typ, int(K))] = a
        self.strikes = {t: sorted(k for (tt, k) in self.arr if tt == t) for t in ("C", "P")}

    def bar(self, ts):
        i = int((ts - self.t0) // C.BAR)
        return i if 0 <= i < self.n else None


@functools.lru_cache(maxsize=96)
def panel(coin, expiry):
    fn = os.path.join(C.RAW, "delta_opt", f"{coin}_{expiry:%Y%m%d}.parquet")
    if not os.path.exists(fn):
        return None
    df = pd.read_parquet(fn)
    if df.empty:
        return None
    return Panel(coin, expiry, df)


def expiries_available(coin):
    out = []
    for f in sorted(os.listdir(os.path.join(C.RAW, "delta_opt"))):
        if f.startswith(coin + "_") and os.path.getsize(os.path.join(C.RAW, "delta_opt", f)) > 2000:
            d = pd.Timestamp(f[len(coin) + 1:len(coin) + 9] + " 12:00", tz="UTC")
            out.append(d)
    return out
