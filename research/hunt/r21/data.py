"""R21 data: one record per EXPIRY day (the expiring contract's minutes), through research/brain/adapters.

    IRAALGO_CACHE=<cache> python3 research/hunt/r21/data.py           # builds <OUT>/expiry_<SOURCE>.pkl

A day counts when the source has the contract that expires that day (expiry == day). Everything is on the 375-minute
grid of bar STARTS (09:15 = 0 ... 15:29 = 374); row t is known once that minute is over (at t + 1). Nothing is filled:
a minute without a bar is NaN, and a bar with volume 0 (the release pads untraded minutes with flat copies) is
"not traded".
"""
from __future__ import annotations

import pickle
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd

import config as K
from research.brain.adapters import pick

N = 375
STEP = {"BANKNIFTY": 100, "NIFTY": 50}
LOTS = {  # NSE lot in force (research/obuy/config.py LOT_SCHEDULE)
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)],
}
O, H, L, C, V, OI = range(6)


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


def minute(hh: int, mm: int) -> int:
    """Grid row of the bar that STARTS at hh:mm."""
    return hh * 60 + mm - (9 * 60 + 15)


@dataclass
class Day:
    day: date
    und: str
    ix: np.ndarray                              # (375, 4) index O H L C (NaN: no bar)
    opts: dict = field(default_factory=dict)    # (strike, 'C'/'P') -> (375, 6) O H L C V OI of TODAY's expiry
    vix: np.ndarray | None = None               # (375,) VIX close (NaN: none)
    prev_close: float = np.nan                  # previous session's index close
    vix_prev: float = np.nan                    # previous session's last VIX close
    lot: int = 0

    @property
    def strikes(self) -> np.ndarray:
        return np.array(sorted({k for k, _ in self.opts}), dtype=float)


def _grid(df: pd.DataFrame, d: date, cols) -> np.ndarray:
    t0 = pd.Timestamp(datetime.combine(d, datetime.min.time())) + pd.Timedelta(hours=9, minutes=15)
    m = ((pd.to_datetime(df.ts) - t0).dt.total_seconds() // 60).astype(int).values
    ok = (m >= 0) & (m < N)
    a = np.full((N, len(cols)), np.nan)
    a[m[ok]] = df[cols].values[ok].astype(float)
    return a


def to_day(dd, prev_close: float, vix_prev: float) -> Day | None:
    """One adapter DayData -> Day, or None when the source lacks today's expiring contract."""
    o = dd.options
    if o is None or o.empty:
        return None
    o = o[pd.to_datetime(o.expiry).dt.date == dd.day]
    if len(o) < 300:                             # stray rows only: not a usable expiring chain
        return None
    ix = _grid(dd.index, dd.day, ["open", "high", "low", "close"])
    opts = {}
    for (k, s), g in o.groupby(["strike", "side"]):
        a = _grid(g, dd.day, ["open", "high", "low", "close", "volume", "oi"])
        opts[(float(k), str(s))] = a
    vix = _grid(dd.vix, dd.day, ["close"])[:, 0] if len(dd.vix) else np.full(N, np.nan)
    return Day(dd.day, dd.und, ix, opts, vix, prev_close, vix_prev, lot(dd.und, dd.day))


def load(und: str, source: str = K.SOURCE) -> list[Day]:
    ads = pick(K.CACHE, und, source)
    if not ads:
        return []
    ad = ads[0]
    sess = ad.sessions(und)
    out = []
    prev_close, vix_prev = np.nan, np.nan
    # walk all sessions in monthly chunks: the previous close / VIX need the day before each expiry
    if not sess:
        return out
    start = sess[0]
    while start <= sess[-1]:
        end = min(start + timedelta(days=31), sess[-1])
        for dd in ad.load(und, start, end):
            d = to_day(dd, prev_close, vix_prev)
            if d is not None:
                out.append(d)
            c = dd.index.close.dropna()
            prev_close = float(c.iloc[-1]) if len(c) else np.nan
            v = dd.vix.close.dropna() if len(dd.vix) else pd.Series(dtype=float)
            vix_prev = float(v.iloc[-1]) if len(v) else np.nan
        start = end + timedelta(days=1)
    return out


def cached(source: str = K.SOURCE) -> dict[str, list[Day]]:
    p = K.OUT / f"expiry_{source}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    K.OUT.mkdir(parents=True, exist_ok=True)
    out = {u: load(u, source) for u in K.UNDS}
    p.write_bytes(pickle.dumps(out))
    return out


def holdout_cut(und: str, days: list[Day], source: str = K.SOURCE) -> date:
    if source == "release":
        return K.RELEASE_HOLDOUT[und]
    last = max(d.day for d in days)
    return last - timedelta(days=K.LAKE_HOLDOUT_DAYS)


if __name__ == "__main__":
    import data as _self             # pickle the Day class under its module name, not __main__
    out = _self.cached()
    for u, ds in out.items():
        print(u, len(ds), ds[0].day if ds else "-", ds[-1].day if ds else "-")
