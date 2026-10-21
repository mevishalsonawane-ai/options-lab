"""R48 data: the lake's rolling option rows -> held contracts, one compact block per session (15 Oct 2026).

Lake (never committed): $R48_LAKE (default ~/lake) holding the Drive folders `options_rolling_1m/`, `index_1m/`,
`index_daily/` copied with rclone from gdrive:IraAlgo-data-lake.

Dhan's rolling rows give, each minute, the contracts that were ATM-10 .. ATM+10 of the nearest expiry. R45 showed that
pricing a trade on the rolling series (whose strike moves) is wrong, so every leg here is ONE held contract: a strike and
a side, looked up in the rows of every offset that carried it. The nearest-expiry ("WEEK") file is used for all five
indices; for BANKNIFTY, FINNIFTY and MIDCPNIFTY it follows the monthly contract after Nov 2024 (the WEEK and MONTH files
are then the same contract, so only one is read and nothing is counted twice). NIFTY / SENSEX MONTH files are not used:
an entry 0-4 sessions before a monthly expiry is the same contract as the WEEK file's.

A session block (Day) holds, per minute 09:15 .. 15:29 (375 minutes, index 0 = 09:15):
  K           sorted strikes seen that session (either side)
  C, O, V     [side 0 = call / 1 = put, strike, minute] close, open, volume (units); NaN / 0 where no row
  lo, hi      the lowest / highest strike in the window each minute (the rolling ATM-10 / ATM+10)
  spot        the index each minute (index_1m close, forward-filled; the option rows' `spot` where the index file
              has no row) - the SIGNAL source
A held contract's minute with no row is "not seen": while its strike is still inside the window the row is simply
missing (rare; the last close is carried); when it has left the window, r48sim bounds its price (see there).
"""
from __future__ import annotations

import os
import pickle
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

LAKE = Path(os.environ.get("R48_LAKE", Path.home() / "lake"))
CACHE = Path(os.environ.get("R48_CACHE", Path.home() / ".cache" / "iraalgo" / "r48"))
OPEN_MIN = 9 * 60 + 15
NMIN = 375                                   # 09:15 .. 15:29
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")


def mi(h: int, m: int) -> int:
    """Minute index of hh:mm (0 = 09:15)."""
    return h * 60 + m - OPEN_MIN


@dataclass
class Day:
    day: date
    K: np.ndarray            # (nK,) float64
    C: np.ndarray            # (2, nK, NMIN) float32
    O: np.ndarray
    V: np.ndarray
    lo: np.ndarray           # (NMIN,) window bounds (NaN where the minute has no rows)
    hi: np.ndarray
    spot: np.ndarray         # (NMIN,) float64
    step: float              # strike step that session

    def kidx(self, strike: float) -> int:
        """Index of a strike in K, or -1."""
        k = int(np.searchsorted(self.K, strike))
        return k if k < len(self.K) and abs(self.K[k] - strike) < 1e-6 else -1


def build_days(df: pd.DataFrame, spot1m: dict | None = None) -> list[Day]:
    """Lake option rows (one file or more) -> Day blocks. spot1m: date -> (NMIN,) index closes (NaN = no row)."""
    ts = df.ts.dt.tz_localize(None) if getattr(df.ts.dt, "tz", None) is not None else df.ts
    m = (ts.dt.hour * 60 + ts.dt.minute).to_numpy() - OPEN_MIN
    keep = (m >= 0) & (m < NMIN)
    df = df.loc[keep].assign(_m=m[keep], _d=ts[keep].dt.date.to_numpy(),
                             _s=(df.side.loc[keep].astype(str).str[0] == "P").astype(np.int8).to_numpy())
    out = []
    for d, g in df.groupby("_d", sort=True):
        g = g.drop_duplicates(["_s", "strike", "_m"], keep="last")
        K = np.unique(g.strike.to_numpy(np.float64))
        ki = np.searchsorted(K, g.strike.to_numpy(np.float64))
        s, mm = g._s.to_numpy(), g._m.to_numpy()
        C = np.full((2, len(K), NMIN), np.nan, np.float32)
        O = np.full_like(C, np.nan)
        V = np.zeros_like(C)
        C[s, ki, mm] = g.close.to_numpy(np.float32)
        O[s, ki, mm] = g.open.to_numpy(np.float32)
        V[s, ki, mm] = g.volume.to_numpy(np.float32)
        lo = np.full(NMIN, np.nan)
        hi = np.full(NMIN, np.nan)
        w = g.groupby("_m").strike.agg(["min", "max"])
        lo[w.index.to_numpy()] = w["min"].to_numpy()
        hi[w.index.to_numpy()] = w["max"].to_numpy()
        sp = np.full(NMIN, np.nan)
        if spot1m is not None and d in spot1m:
            sp = np.asarray(spot1m[d], dtype=np.float64).copy()
        os_ = g.groupby("_m").spot.last()
        miss = np.isnan(sp)
        miss_idx = np.flatnonzero(miss)
        fill = os_.reindex(miss_idx).to_numpy()
        sp[miss_idx] = fill
        sp = pd.Series(sp).ffill().bfill().to_numpy()
        steps = np.diff(K)
        step = float(np.median(steps[steps > 0])) if len(steps) else np.nan
        # a step is the smallest common gap; the window may skip strikes on odd days, so take the mode of small gaps
        if len(steps):
            vals, cnt = np.unique(np.round(steps, 2), return_counts=True)
            step = float(vals[np.argmax(cnt)])
        out.append(Day(d, K, C, O, V, lo, hi, sp, step))
    return out


def index_minutes(und: str) -> dict:
    """date -> (NMIN,) index 1-minute closes (NaN where no row)."""
    out = {}
    for p in sorted((LAKE / "index_1m").glob(f"index_1m_{und}_*.parquet")):
        x = pd.read_parquet(p, columns=["ts", "close"])
        ts = x.ts.dt.tz_localize(None)
        m = (ts.dt.hour * 60 + ts.dt.minute).to_numpy() - OPEN_MIN
        k = (m >= 0) & (m < NMIN)
        x = x.loc[k].assign(_m=m[k], _d=ts[k].dt.date.to_numpy())
        for d, g in x.groupby("_d"):
            a = np.full(NMIN, np.nan)
            a[g._m.to_numpy()] = g.close.to_numpy()
            out[d] = a
    return out


def load(und: str, cache: bool = True) -> list[Day]:
    """Every session of the nearest-expiry option file of `und` (all dates; the study splits design / holdout)."""
    key = CACHE / f"days_{und}.pkl"
    if cache and key.exists():
        return pickle.loads(key.read_bytes())
    spot = index_minutes(und)
    out = []
    for p in sorted((LAKE / "options_rolling_1m").glob(f"opt_{und}_WEEK_*.parquet")):
        df = pd.read_parquet(p, columns=["ts", "side", "open", "close", "volume", "strike", "spot"])
        out += build_days(df, spot)
    out.sort(key=lambda d: d.day)
    if cache:
        CACHE.mkdir(parents=True, exist_ok=True)
        key.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def daily_index() -> pd.DataFrame:
    """index_daily rows (symbol, date, open, high, low, close)."""
    x = pd.read_parquet(LAKE / "index_daily" / "index_daily.parquet")
    x["date"] = pd.to_datetime(x.date).dt.date
    return x


def expiry_days(days: list[Day]) -> list[date]:
    """Real expiry days of the nearest-expiry series from its own rows (R25 / brain expiry_infer method: the ATM
    straddle's time value near the close (15:00-15:20) is < 0.6% of spot and the next session's is more than twice it).
    Expiries are scheduled weeks ahead, so knowing them is not look-ahead; this only recovers the dates."""
    tv = {}
    for d in days:
        a, b = mi(15, 0), mi(15, 20) + 1
        sp = d.spot[a:b]
        best = None
        for t in range(b - 1, a - 1, -1):
            k = int(np.argmin(np.abs(d.K - d.spot[t])))
            c, p = d.C[0, k, t], d.C[1, k, t]
            if np.isfinite(c) and np.isfinite(p):
                best = (c + p - abs(d.spot[t] - d.K[k])) / d.spot[t] * 100
                break
        if best is not None and len(sp):
            tv[d.day] = best
    s = pd.Series(tv).sort_index()
    nxt = s.shift(-1)
    ok = (s < 0.6) & (nxt > 2.0 * s.clip(lower=1e-6)) & nxt.notna()
    return [d for d, v in ok.items() if v]
