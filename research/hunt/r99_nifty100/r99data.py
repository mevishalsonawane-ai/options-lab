"""R99 data: R51's cleaned NIFTY day stores (built by research/hunt/r51/r51data.py with R51_CLEAN=1 R51_STALE=1),
India VIX minutes, and per-day helpers (previous session, sessions to expiry)."""
from __future__ import annotations

import numpy as np
import pandas as pd

import r99cfg as K  # noqa: F401  (sets sys.path and the R51 cleaning switches first)
import r51cfg as K51
import r51data as RD


def days():
    return RD.load(K.UND)


def vix_minutes() -> dict:
    """{date: (375,) India VIX close on R51's grid}."""
    out = {}
    for y in range(K51.WARM_START.year, K51.END.year + 1):
        p = K51.LAKE / f"index_1m_INDIAVIX_{y}.parquet"
        if not p.exists():
            continue
        x = RD._read(p, ["ts", "close"])
        for d, g in x.groupby("day"):
            a = np.full(K51.NROWS, np.nan)
            a[g.r.values] = g.close.values
            out[d] = pd.Series(a).ffill().values
    return out


def sessions_to_expiry(all_days: dict) -> dict:
    """{date: sessions until the next expiry day (0 = expiry day itself)}; large when none follows in the data."""
    ds = sorted(all_days)
    out, nxt = {}, None
    for d in reversed(ds):
        if all_days[d].expiry:
            nxt = d
        out[d] = (ds.index(nxt) - ds.index(d)) if nxt is not None else 99
    return out
