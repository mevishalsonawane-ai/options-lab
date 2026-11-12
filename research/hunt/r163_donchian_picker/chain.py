"""R163 option-chain context (PREREG section 4, F4): the lake's options_rolling_1m WEEK files of one index, per session.

For each session: grids (375, 21, 2) of OI, IV, close and volume by minute row, offset -10..+10 and side (C, P), and a
per-contract lookup of the same rows for the bought strike. Rows outside 09:15 - 15:29, mislabelled rows (strike more
than 12 steps from spot, R25) and duplicate prints are dropped. IV 0 means "not computed" and is read as missing.
"""
from __future__ import annotations

from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

NROWS, OPEN_MIN = 375, 9 * 60 + 15
COLS = ["ts", "offset", "side", "strike", "spot", "high", "low", "close", "volume", "oi", "iv"]


def read(lake: Path, und: str, years=(2024, 2025, 2026), first: date = date(2024, 11, 1), step: float = 50.0) -> pd.DataFrame:
    parts = []
    for y in years:
        p = Path(lake) / f"opt_{und}_WEEK_{y}.parquet"
        if p.exists():
            parts.append(pd.read_parquet(p, columns=COLS))
    if not parts:
        return pd.DataFrame(columns=COLS + ["day", "r"])
    o = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(o.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    o["day"] = ts.dt.date
    o["r"] = (ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).astype(np.int32)
    o = o[(o.r >= 0) & (o.r < NROWS) & (o.day >= first)].drop(columns=["ts"])
    o["side"] = o.side.astype(str).str[0].str.upper()
    o = o[(o.strike - o.spot).abs() <= 12 * step]
    o.loc[o.iv <= 0, "iv"] = np.nan
    return o.drop_duplicates(["day", "r", "offset", "side"], keep="first")


class Day:
    """One session's chain."""

    def __init__(self, g: pd.DataFrame):
        self.g = g
        si = np.where(g.side.values == "C", 0, 1)
        off = g.offset.values.astype(int) + 10
        ok = (off >= 0) & (off <= 20)
        r = g.r.values
        self.grid = {}
        for k in ("oi", "iv", "close", "volume"):
            a = np.full((NROWS, 21, 2), np.nan)
            a[r[ok], off[ok], si[ok]] = g[k].values[ok].astype(float)
            self.grid[k] = a
        self._k: dict = {}

    def contract(self, side: str, strike: float) -> dict | None:
        key = (side, float(strike))
        if key not in self._k:
            h = self.g[(self.g.side == side) & (self.g.strike == float(strike))].drop_duplicates("r", keep="first")
            if h.empty:
                self._k[key] = None
            else:
                out = {}
                for k in ("oi", "volume", "high", "low", "close"):
                    a = np.full(NROWS, np.nan)
                    a[h.r.values] = h[k].values.astype(float)
                    out[k] = a
                self._k[key] = out
        return self._k[key]


def days_of(o: pd.DataFrame, wanted: set | None = None) -> dict:
    return {d: Day(g) for d, g in o.groupby("day", sort=True) if wanted is None or d in wanted}
