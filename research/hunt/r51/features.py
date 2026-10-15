"""Brain feature rows for R51 (research/brain/features store: <cache>/features/<UND>/<YYYY>/<day>.parquet), built
from the lake for Jun 2023 - Oct 2026 (warm-up; nothing before 20 Nov 2024 is traded). Every value at a minute uses
only data up to that minute (the brain's look-ahead guard)."""
from __future__ import annotations

from datetime import date
from functools import lru_cache

import numpy as np
import pandas as pd

import r51cfg as K

ROOT = K.CACHE_ROOT / "features"


@lru_cache(maxsize=4096)
def day(und: str, d: date) -> pd.DataFrame | None:
    p = ROOT / und / str(d.year) / f"{d}.parquet"
    if not p.exists():
        return None
    x = pd.read_parquet(p)
    if "ts" in x.columns:
        ts = pd.to_datetime(x.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        x.index = (ts.dt.hour * 60 + ts.dt.minute - K.OPEN_MIN).values
    return x


def value(und: str, d: date, row: int, name: str):
    x = day(und, d)
    if x is None or name not in x.columns or row not in x.index:
        return None
    v = x.at[row, name]
    return None if v is None or not np.isfinite(v) else float(v)
