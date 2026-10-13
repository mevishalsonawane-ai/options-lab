"""R17 data: per-session index minutes, option minutes, VIX previous close, lot, expiries, events. Cached in OUT.

    IRAALGO_CACHE=<cache> python3 research/hunt/r17/data.py          # builds <OUT>/days_<UND>.pkl

Everything comes through research/brain/adapters (config.SOURCE), so the 6-year lake needs no code change. VIX: the
adapter's INDIAVIX minutes give the previous close; where they are missing (the release's extra_series ends 27 Feb 2026)
Yahoo's daily ^INDIAVIX close fills the previous close only (a daily value known before the open: no look-ahead).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
import urllib.request
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone

import numpy as np
import pandas as pd

import config as K

from research.brain import calendar as CAL  # noqa: E402

N = 375
STEP = {"BANKNIFTY": 100, "NIFTY": 50}
LOTS = {  # NSE lot in force (research/obuy/config.py LOT_SCHEDULE)
    "NIFTY": [("2000-01-01", 75), ("2021-07-23", 50), ("2024-04-26", 25), ("2024-11-28", 75), ("2025-12-31", 65)],
    "BANKNIFTY": [("2000-01-01", 25), ("2023-07-21", 15), ("2024-11-28", 30), ("2025-06-27", 35)],
}


def lot(und: str, d: date) -> int:
    out = 0
    for s, n in LOTS[und]:
        if d >= date.fromisoformat(s):
            out = n
    return out


@dataclass
class Day:
    day: date
    und: str
    ix: np.ndarray                     # (375, 4) OHLC on the minute grid, forward-filled
    opts: dict = field(default_factory=dict)   # (expiry, strike, side 'C'/'P') -> (375, 5) O H L C V (NaN: no trade)
    vix_open: float = np.nan           # VIX first minute close (VIX divergence)
    vix_min: np.ndarray | None = None  # (375,) VIX close per minute, forward-filled (NaN where none)
    vix_prev: float = np.nan           # previous session's VIX close
    lot: int = 0
    expiry: date | None = None         # nearest expiry ON or after the day (from the calendar)
    events: tuple = ()


def _grid(df: pd.DataFrame, d: date, cols) -> np.ndarray:
    t0 = pd.Timestamp(datetime.combine(d, datetime.min.time())) + pd.Timedelta(hours=9, minutes=15)
    m = ((pd.to_datetime(df.ts) - t0).dt.total_seconds() // 60).astype(int).values
    ok = (m >= 0) & (m < N)
    a = np.full((N, len(cols)), np.nan)
    a[m[ok]] = df[cols].values[ok].astype(float)
    return a


def yahoo_vix() -> dict[date, float]:
    p = K.CACHE.root / "vix_yahoo.json"
    if not p.exists():
        url = ("https://query1.finance.yahoo.com/v8/finance/chart/%5EINDIAVIX?period1=1262304000&period2="
               f"{int(datetime.now(timezone.utc).timestamp())}&interval=1d")
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"}), timeout=60) as r:
            p.write_bytes(r.read())
    j = json.loads(p.read_text())["chart"]["result"][0]
    ist = timezone(timedelta(hours=5, minutes=30))
    out = {}
    for t, c in zip(j["timestamp"], j["indicators"]["quote"][0]["close"]):
        if c is not None:
            out[datetime.fromtimestamp(t, ist).date()] = float(c)
    return out


def build(und: str) -> list[Day]:
    ad = K.adapter()
    ss = ad.sessions(und)
    yv = yahoo_vix()
    days: list[Day] = []
    prev_vix = np.nan
    for dd in ad.load(und, ss[0], ss[-1]):
        ix = _grid(dd.index, dd.day, ["open", "high", "low", "close"])
        if np.isfinite(ix[:, 3]).sum() < 300:
            continue
        c = pd.Series(ix[:, 3]).ffill().bfill().values
        for j in range(3):
            ix[:, j] = np.where(np.isfinite(ix[:, j]), ix[:, j], c)
        ix[:, 3] = c
        opts = {}
        o = dd.options
        if len(o):
            o = o.copy()
            for (e, k, s), g in o.groupby(["expiry", "strike", "side"]):
                a = _grid(g, dd.day, ["open", "high", "low", "close", "volume"]).astype(np.float32)
                if np.isfinite(a[:, 3]).sum() >= 5:
                    opts[(e, float(k), s)] = a
        vmin = None
        vopen = np.nan
        vclose = np.nan
        if len(dd.vix):
            v = _grid(dd.vix, dd.day, ["close"])[:, 0]
            if np.isfinite(v).any():
                vopen = float(v[np.isfinite(v)][0])
                vmin = pd.Series(v).ffill().values
                vclose = float(v[np.isfinite(v)][-1])
        vp = prev_vix
        if not np.isfinite(vp):                  # previous session's close from Yahoo's daily series
            back = [x for x in yv if x < dd.day]
            vp = yv[max(back)] if back else np.nan
        exp, _ = CAL.next_expiry(und, dd.day)
        days.append(Day(dd.day, und, ix, opts, vopen, vmin, vp, lot(und, dd.day), exp, tuple(CAL.events_on(und, dd.day))))
        prev_vix = vclose if np.isfinite(vclose) else yv.get(dd.day, np.nan)
        if len(days) % 50 == 0:
            print(und, len(days), dd.day, flush=True)
    return days


def load(und: str) -> list[Day]:
    p = K.OUT / f"days_{K.SOURCE}_{und}.pkl"
    if p.exists():
        with open(p, "rb") as f:
            return pickle.load(f)
    K.OUT.mkdir(parents=True, exist_ok=True)
    days = build(und)
    with open(p, "wb") as f:
        pickle.dump(days, f, protocol=4)
    return days


if __name__ == "__main__":
    import data as _self                          # pickle the Day class as data.Day, not __main__.Day
    for u in (sys.argv[1:] or ["BANKNIFTY", "NIFTY"]):
        ds = _self.load(u)
        print(u, len(ds), ds[0].day, ds[-1].day, "opts/day", int(np.mean([len(d.opts) for d in ds])),
              "vix prev missing", sum(not np.isfinite(d.vix_prev) for d in ds))
