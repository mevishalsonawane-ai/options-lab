"""R63 data: the lake's 1-minute INDEX bars of every session (1 Sep 2024 warm-up .. the lake's end) and the trend rules'
act rows, cached per index. Only the underlying is read here; no option price enters a trend rule.
"""
from __future__ import annotations

import pickle
from datetime import date

import numpy as np
import pandas as pd

import r63cfg as K
import r63rules as RR


def index_minutes(und: str) -> dict:
    """{date: (4, 375) O H L C} from the lake's index_1m files, NaN where there is no minute."""
    parts = []
    for y in range(K.WARM_START.year, K.END.year + 1):
        p = K.LAKE / f"index_1m_{und}_{y}.parquet"
        if p.exists():
            parts.append(pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"]))
    if not parts:
        raise FileNotFoundError(f"no index_1m files for {und} in {K.LAKE}")
    x = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(x.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"] = ts.dt.date
    x["r"] = (ts.dt.hour * 60 + ts.dt.minute - K.OPEN_MIN).astype(np.int32)
    x = x[(x.r >= 0) & (x.r < K.NROWS) & (x.day >= K.WARM_START) & (x.day <= K.END) & (x.close > 0)]
    x = x.drop_duplicates(["day", "r"])
    out = {}
    for d, g in x.groupby("day", sort=True):
        a = np.full((4, K.NROWS), np.nan)
        for i, c in enumerate(("open", "high", "low", "close")):
            a[i, g.r.values] = g[c].values
        out[d] = a
    return out


def turns(und: str) -> dict:
    """{rule: {date: {'C': [...], 'P': [...], 'Cf': [...], 'Pf': [...]}}} (r63rules.turn_rows), cached."""
    p = K.OUT / f"turns_{und}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    m = index_minutes(und)
    days = sorted(m)
    out = {r: RR.turn_rows(r, days, [m[d] for d in days]) for r in RR.RULES}
    K.OUT.mkdir(parents=True, exist_ok=True)
    p.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def trend_row(T: dict, rule: str, d: date, side: str, ef: int) -> int:
    """The act row at which `rule` sells a `side` ('C' / 'P') position filled at row ef on day d; -1 if none."""
    t = T[rule].get(d)
    return RR.exit_row(t, side, ef, RR.TF[rule], rule) if t else -1
