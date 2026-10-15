"""R46 data: BTC spot / perp 1-minute bars and Delta India BTC option candles from the lake copy (never in git), and
the holdout lock.

Option candles are TRADES: a minute without a trade has no row or repeats the last price with volume 0 (options lake
README). `OptDay` puts one expiry day's contracts on a dense minute grid: prices carried forward inside a contract,
volume 0 where nothing traded. Only volume > 0 minutes count as a real trade for an entry.
"""
from __future__ import annotations

import json
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from . import r46cfg as K


class HoldoutError(RuntimeError):
    pass


def holdout_reads() -> dict:
    return json.loads(K.HOLD_LOG.read_text()) if K.HOLD_LOG.exists() else {}


def open_holdout(idea: str = "r46") -> None:
    """Record the one holdout read; a second read raises (the log is committed in results/)."""
    reads = holdout_reads()
    if idea in reads:
        raise HoldoutError(f"holdout already read for {idea} on {reads[idea]}")
    reads[idea] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    K.HOLD_LOG.parent.mkdir(parents=True, exist_ok=True)
    K.HOLD_LOG.write_text(json.dumps(reads, indent=1))


def load_1m(kind: str, end: pd.Timestamp) -> pd.DataFrame:
    """kind 'spot' or 'perp': 1-minute bars [DATA_START, end) on the full minute grid (missing minutes NaN)."""
    files = sorted((K.LAKE / f"{kind}_BTC").glob("*/*.parquet"))
    keep = [f for f in files if pd.Timestamp(f.stem[-7:] + "-01") < end]
    if not keep:
        return pd.DataFrame(columns=["open", "high", "low", "close", "volume"])
    df = pd.concat([pd.read_parquet(f, columns=["open_time", "open", "high", "low", "close", "volume"]) for f in keep],
                   ignore_index=True)
    df["ts"] = pd.to_datetime(df.open_time, unit="ms")
    df = df.drop_duplicates("ts").set_index("ts").sort_index()
    df = df[(df.index >= K.DATA_START) & (df.index < end)]
    for c in ("open", "high", "low", "close"):
        df.loc[~(df[c] > 0), c] = np.nan
    if df.empty:
        return df[["open", "high", "low", "close", "volume"]]
    grid = pd.date_range(df.index[0].floor("D"), end - pd.Timedelta(minutes=1), freq="1min")
    grid = grid[grid <= df.index[-1]]
    return df.reindex(grid)[["open", "high", "low", "close", "volume"]]


def expiry_of(entry: pd.Timestamp) -> pd.Timestamp:
    """The nearest daily expiry (12:00 UTC) with at least MIN_LEFT left at the entry minute."""
    e = entry.normalize() + pd.Timedelta(hours=K.EXPIRY_H_UTC)
    if e - entry < K.MIN_LEFT:
        e += pd.Timedelta(days=1)
    return e


def entries_for_expiry(E: pd.Timestamp) -> pd.DatetimeIndex:
    """Entry minutes (5-minute starts) that use expiry E: (E - 1 day, E] shifted by MIN_LEFT."""
    lo, hi = E - pd.Timedelta(days=1) - K.MIN_LEFT, E - K.MIN_LEFT
    t = pd.date_range(lo + pd.Timedelta(minutes=K.BAR_MIN), hi, freq=f"{K.BAR_MIN}min")
    return t


def fee_rule(expiry: pd.Timestamp) -> tuple[float, float]:
    """(rate x notional, cap x premium) for an expiry date: the dearest schedule covering it."""
    d = str(expiry.date())
    best = None
    for a, b, rate, cap in K.FEE_SCHEDULES:
        if a <= d <= b and (best is None or rate > best[0]):
            best = (rate, cap)
    return best or (K.FEE_SCHEDULES[-1][2], K.FEE_SCHEDULES[-1][3])


class OptDay:
    """One expiry's candles on the minute grid [first entry - 5 min, expiry - 10 min]."""

    def __init__(self, E: pd.Timestamp, frame: pd.DataFrame | None = None, spot_lohi=None):
        self.E = E
        ent = entries_for_expiry(E)
        self.grid = pd.date_range(ent[0] - pd.Timedelta(minutes=K.BAR_MIN), E - K.LAST_EXIT_BEFORE_EXPIRY,
                                  freq="1min")
        self.t0 = self.grid[0]
        self.arr = {"C": {}, "P": {}}
        if frame is None:
            p = K.LAKE / "delta_india" / "candles" / "BTC" / str(E.year) / f"{E.date()}.parquet"
            if not p.exists():
                self.ok = False
                return
            frame = pd.read_parquet(p, columns=["timestamp", "option_type", "strike", "expiry_ts", "open", "high",
                                                "low", "close", "volume"])
            frame = frame[frame.expiry_ts == int(E.value // 10**6)]
        D = frame.copy()
        D["ts"] = pd.to_datetime(D.timestamp, unit="ms")
        D = D[(D.ts >= self.grid[0]) & (D.ts <= self.grid[-1])]
        if spot_lohi is not None:
            lo, hi = spot_lohi
            D = D[(D.strike >= lo * (1 - 2 * K.ATM_BAND)) & (D.strike <= hi * (1 + 2 * K.ATM_BAND))]
        self.ok = not D.empty
        for (typ, k), g in D.groupby(["option_type", "strike"]):
            g = g.sort_values("ts").drop_duplicates("ts", keep="last").set_index("ts").reindex(self.grid)
            v = g.volume.fillna(0.0).to_numpy(float)
            px = g[["open", "high", "low", "close"]].copy()
            px[~(px > 0)] = np.nan
            px = px.ffill().to_numpy(float)
            self.arr[typ][float(k)] = (px, v)

    def idx(self, t: pd.Timestamp) -> int:
        return int((t - self.t0) / pd.Timedelta(minutes=1))

    def pick(self, typ: str, spot: float, i0: int):
        """Nearest strike within ATM_BAND of spot that has a real trade in minutes i0 .. i0 + FILL_WINDOW - 1.
        Returns (strike, first trade minute index) or (None, -1). Ties go to the lower strike."""
        best, bf = None, -1
        for k, (px, v) in self.arr[typ].items():
            if abs(k - spot) > K.ATM_BAND * spot:
                continue
            w = v[i0:i0 + K.FILL_WINDOW_MIN]
            hit = np.flatnonzero(w > 0)
            if not len(hit):
                continue
            if best is None or abs(k - spot) < abs(best - spot) or (abs(k - spot) == abs(best - spot) and k < best):
                best, bf = k, i0 + int(hit[0])
        return best, bf
