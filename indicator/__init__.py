"""IraAlgo's indicator library: the standard technical indicators, written from their published formulas.

    import pandas as pd
    import indicator as ind
    df = pd.read_csv("banknifty_recent.csv", parse_dates=["ts"], index_col="ts")   # open high low close [volume]
    ind.RSI(df, 14); ind.Supertrend(df, 10, 3); ind.CPR(df)
    ind.catalog()            # every indicator, its category and what it measures
    ind.compute_all(df)      # every indicator that runs on this frame, as one wide DataFrame

See CATALOG.md for the full list.
"""
from __future__ import annotations

import pandas as pd

from . import candles, levels, momentum, moving_averages, statistics, trend, volatility, volume  # noqa: F401
from .candles import *  # noqa: F401,F403
from .core import REGISTRY
from .levels import *  # noqa: F401,F403
from .momentum import *  # noqa: F401,F403
from .moving_averages import *  # noqa: F401,F403
from .statistics import *  # noqa: F401,F403
from .trend import *  # noqa: F401,F403
from .volatility import *  # noqa: F401,F403
from .volume import *  # noqa: F401,F403

# Take these out of the "run them all" pass: they need another series, or return a table rather than a column.
NOT_PER_BAR = {"Correlation", "Beta", "VolumeProfile"}


def catalog() -> pd.DataFrame:
    return pd.DataFrame([(e.category, e.name, e.summary, e.needs_volume) for e in REGISTRY.values()],
                        columns=["category", "name", "what it is", "needs volume"]).sort_values(["category", "name"])


def compute_all(df: pd.DataFrame, skip_volume: bool | None = None) -> pd.DataFrame:
    """Every per-bar indicator with default settings, columns named "<indicator>" or "<indicator>.<output>"."""
    if skip_volume is None:
        skip_volume = "volume" not in df or (df.volume.fillna(0) == 0).all()
    cols = {}
    for name, e in REGISTRY.items():
        if name in NOT_PER_BAR or (e.needs_volume and skip_volume):
            continue
        r = e.fn(df)
        if isinstance(r, pd.DataFrame):
            for c in r.columns:
                cols[f"{name}.{c}"] = r[c]
        else:
            cols[name] = r
    return pd.DataFrame(cols, index=df.index)
