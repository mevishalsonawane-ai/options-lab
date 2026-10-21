"""R45 data: the lake's rolling option files -> per-session 5-minute bars (rolling ATM call and fixed contracts).

Lake: gdrive:IraAlgo-data-lake/options_rolling_1m (opt_<UND>_<WEEK|MONTH>_<YEAR>.parquet), copied to
$IRAALGO_LAKE (default ~/.cache/iraalgo/lake) with rclone; never committed. Rows are 1-minute bars stamped at the
minute's start (IST); `offset` 0 is the contract that was ATM at that minute, so the `strike` of offset 0 moves with spot.
A fixed contract (one strike, same nearest expiry) is rebuilt from the rows of every offset that carried that strike.
"""
from __future__ import annotations

import os
import pickle
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

LAKE = Path(os.environ.get("IRAALGO_LAKE", Path.home() / ".cache" / "iraalgo" / "lake"))
CACHE = Path(os.environ.get("R45_CACHE", Path.home() / ".cache" / "iraalgo" / "r45"))
OPEN_MIN = 9 * 60 + 15
CLOSE_MIN = 15 * 60 + 29


def bucket5(minute):
    """5-minute bar start (minutes since midnight) of a minute, bars anchored at 09:15."""
    m = np.asarray(minute)
    return OPEN_MIN + (m - OPEN_MIN) // 5 * 5


def resample5(minute, o, h, l, c):
    """1-minute bars (sorted by minute) -> 5-minute bars: start, first open, max high, min low, last close. Empty
    5-minute buckets do not appear."""
    minute = np.asarray(minute)
    if len(minute) == 0:
        z = np.array([], dtype=np.float64)
        return np.array([], dtype=np.int64), z, z, z, z
    b = bucket5(minute)
    starts = np.r_[0, np.flatnonzero(np.diff(b)) + 1]
    ends = np.r_[starts[1:], len(b)]
    o, h, l, c = (np.asarray(x, dtype=np.float64) for x in (o, h, l, c))
    return (b[starts].astype(np.int64), o[starts], np.maximum.reduceat(h, starts), np.minimum.reduceat(l, starts),
            c[ends - 1])


@dataclass
class Session:
    day: date
    t: np.ndarray                 # rolling ATM call 5-minute bar starts (minutes)
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    rmin: np.ndarray              # rolling ATM call 1-minute minutes and strikes
    rstrike: np.ndarray
    calls: dict = field(default_factory=dict)   # strike -> (minute, o, h, l, c) 1-minute rows of that call

    def strike_at(self, minute: int):
        """Strike of the rolling ATM call at the first 1-minute row at or after `minute` (None if none)."""
        k = np.searchsorted(self.rmin, minute)
        return None if k >= len(self.rmin) else float(self.rstrike[k])

    def contract5(self, strike):
        """5-minute bars of one fixed call strike (start, o, h, l, c); empty arrays if never seen in the lake rows."""
        if strike not in self.calls:
            z = np.array([], dtype=np.float64)
            return np.array([], dtype=np.int64), z, z, z, z
        return resample5(*self.calls[strike])

    def strikes_between(self, m0: int, m1: int) -> int:
        """Distinct rolling ATM strikes on the 1-minute rows from minute m0 to m1 inclusive."""
        a, b = np.searchsorted(self.rmin, m0), np.searchsorted(self.rmin, m1, side="right")
        return int(len(np.unique(self.rstrike[a:b])))


def on_grid(t, ts, *cols):
    """Align 5-minute bars (ts, cols) to the grid t; None where the grid bar has no bar."""
    pos = {int(x): k for k, x in enumerate(ts)}
    out = []
    for col in cols:
        out.append([float(col[pos[int(x)]]) if int(x) in pos else None for x in t])
    return out


def _sessions_from(df: pd.DataFrame) -> list[Session]:
    df = df[df.side == "C"].copy()
    ts = df.ts.dt.tz_localize(None) if df.ts.dt.tz is not None else df.ts
    df["day"] = ts.dt.date
    df["m"] = (ts.dt.hour * 60 + ts.dt.minute).astype(np.int64)
    df = df[(df.m >= OPEN_MIN) & (df.m <= CLOSE_MIN)]
    out = []
    for d, g in df.groupby("day", sort=True):
        r = g[g.offset == 0].sort_values("m").drop_duplicates("m")
        if r.empty:
            continue
        t, o, h, l, c = resample5(r.m.values, r.open.values, r.high.values, r.low.values, r.close.values)
        calls = {}
        for k, gk in g.sort_values("m").drop_duplicates(["strike", "m"]).groupby("strike"):
            calls[float(k)] = (gk.m.values.astype(np.int32), *(gk[x].values.astype(np.float32) for x in ("open", "high", "low", "close")))
        out.append(Session(d, t, o, h, l, c, r.m.values.astype(np.int64), r.strike.values.astype(np.float64), calls))
    return out


def load(und: str, series: str, years=range(2021, 2027), cache: bool = True) -> list[Session]:
    """Every session of opt_<und>_<series>_<year> files (all dates; the study splits design / holdout)."""
    key = CACHE / f"{und}_{series}.pkl"
    if cache and key.exists():
        return pickle.loads(key.read_bytes())
    out = []
    for y in years:
        p = LAKE / f"opt_{und}_{series}_{y}.parquet"
        if not p.exists():
            continue
        df = pd.read_parquet(p, columns=["ts", "offset", "side", "open", "high", "low", "close", "strike"])
        out += _sessions_from(df)
    out.sort(key=lambda s: s.day)
    if cache:
        CACHE.mkdir(parents=True, exist_ok=True)
        key.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def expiry_days(und: str, series: str, years=range(2021, 2027)) -> set[date]:
    """Real expiry days of a series from its own rows (R25 / brain expiry_infer method: the ATM straddle's time value
    near the close is < 0.6% of spot and the next session's is more than twice it)."""
    from research.brain.expiry_infer import close_time_value, expiry_days as _ed
    key = CACHE / f"exp_{und}_{series}.pkl"
    if key.exists():
        return pickle.loads(key.read_bytes())
    tvs = []
    for y in years:
        p = LAKE / f"opt_{und}_{series}_{y}.parquet"
        if not p.exists():
            continue
        o = pd.read_parquet(p, columns=["ts", "offset", "side", "close", "strike", "spot"])
        o = o[o.offset == 0]
        o["ts"] = o.ts.dt.tz_localize(None)
        tvs.append(close_time_value(o))
    tv = pd.concat(tvs).sort_index()
    tv = tv[~tv.index.duplicated()]
    out = set(_ed(tv))
    CACHE.mkdir(parents=True, exist_ok=True)
    key.write_bytes(pickle.dumps(out))
    return out
