import json
import os

import numpy as np
import pandas as pd


def read(path, **kw):
    if not os.path.exists(path):
        return None
    df = pd.read_csv(path, index_col=0, **kw)
    df.index = pd.to_datetime(df.index, utc=True)
    return df.sort_index()


def daily_index(df):
    """Same frame keyed by UTC calendar day (last row per day)."""
    if df is None:
        return None
    df = df.copy()
    df.index = df.index.normalize()
    return df.groupby(level=0).last()


class _Enc(json.JSONEncoder):
    def default(self, o):
        if isinstance(o, (np.integer,)):
            return int(o)
        if isinstance(o, (np.floating,)):
            return None if not np.isfinite(o) else float(o)
        if isinstance(o, (pd.Timestamp,)):
            return str(o)
        return super().default(o)


def _clean(o):
    if isinstance(o, float) and not np.isfinite(o):
        return None
    if isinstance(o, dict):
        return {k: _clean(v) for k, v in o.items()}
    if isinstance(o, list):
        return [_clean(v) for v in o]
    return o


def write_json(obj, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(_clean(obj), f, cls=_Enc, separators=(",", ":"))
