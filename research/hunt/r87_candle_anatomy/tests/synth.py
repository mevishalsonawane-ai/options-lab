"""Synthetic minute data for the R87 tests (a seeded random walk with activity, OI and a gamma proxy)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import cfg


def minutes(days: int = 30, seed: int = 1, start: str = "2024-01-01", chain: bool = True) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    ds = pd.bdate_range(start, periods=days)
    rows = []
    px = 20000.0
    for d in ds:
        px *= 1 + rng.normal(0, 0.004)                    # overnight gap
        for r in range(cfg.NROWS):
            if rng.random() < 0.01:                       # a missing minute now and then
                continue
            o = px
            c = o * (1 + rng.normal(0, 0.0006))
            h = max(o, c) * (1 + abs(rng.normal(0, 0.0003)))
            l = min(o, c) * (1 - abs(rng.normal(0, 0.0003)))
            px = c
            ts = d + pd.Timedelta(minutes=cfg.OPEN_MIN + r)
            row = dict(ts=ts, o=o, h=h, l=l, c=c)
            if chain:
                row.update(act=float(rng.integers(1000, 50000)), coi=1e6 + rng.normal(0, 1e4),
                           poi=1e6 + rng.normal(0, 1e4), gex_ratio=rng.normal(0, 0.3))
            rows.append(row)
    return pd.DataFrame(rows)


def bars(rows) -> pd.DataFrame:
    """Hand-made candles (o, h, l, c) on one day, 5-minute slots; ATR is set by the caller when needed."""
    d = pd.Timestamp("2024-01-02")
    out = []
    for i, (o, h, l, c) in enumerate(rows):
        ts = d + pd.Timedelta(minutes=cfg.OPEN_MIN + 5 * i)
        out.append(dict(ts=ts, tl=ts + pd.Timedelta(minutes=4), day=d, slot=5 * i, o=o, h=h, l=l, c=c, n=5))
    return pd.DataFrame(out)
