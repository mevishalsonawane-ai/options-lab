"""R17 data: one grid per session (index, VIX, futures volume, the option chain by strike), from the source chosen in
config.py. Goes to config.GRIDS (scratch, never committed).

    python3 research/hunt/r17/data.py                 # both indices
    R17_SOURCE=lake python3 research/hunt/r17/data.py # the same from the Dhan lake when it is published

Reads through research.brain.adapters, so the release and the lake give the same shape (DayData). Nothing is filled
or invented: a minute without a trade stays NaN; the replay reads the last printed price only where the app would.
"""
from __future__ import annotations

import os
import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, ROOT)
sys.path.insert(0, HERE)

import config as K  # noqa: E402
from research.brain.adapters.lake import LakeAdapter  # noqa: E402
from research.brain.adapters.release import ReleaseAdapter  # noqa: E402

N = 375
OPEN = 9 * 60 + 15


def adapter():
    if K.SOURCE == "lake":
        a = LakeAdapter(K.LAKE)
    else:
        a = ReleaseAdapter(K.RAW)
    return a


def _minute(ts: pd.Series) -> np.ndarray:
    ts = pd.to_datetime(ts)
    return (ts.dt.hour * 60 + ts.dt.minute - OPEN).values


def _grid(df: pd.DataFrame, cols) -> np.ndarray:
    out = np.full((N, len(cols)), np.nan)
    if df is None or len(df) == 0:
        return out
    m = _minute(df.ts)
    ok = (m >= 0) & (m < N)
    d = df[ok].assign(_m=m[ok]).drop_duplicates("_m")
    out[d._m.values] = d[list(cols)].astype(float).values
    return out


def chain(opts: pd.DataFrame, expiry: date):
    o = opts[opts.expiry == expiry]
    ks = np.sort(o.strike.unique()).astype(float)
    arr = np.full((N, len(ks), 2, 6), np.nan, dtype=np.float32)     # O H L C V OI ; side 0 = CE, 1 = PE
    for (k, s), g in o.groupby(["strike", "side"]):
        j = int(np.searchsorted(ks, k))
        arr[:, j, 0 if s == "C" else 1, :] = _grid(g, ["open", "high", "low", "close", "volume", "oi"])
    # The release pads untraded minutes with volume-0 flat copies: those are NOT trades (brain README, flags).
    pad = (arr[..., 4] == 0) & (arr[..., 0] == arr[..., 3]) & (arr[..., 1] == arr[..., 2])
    for c in range(4):
        arr[..., c][pad] = np.nan
    return ks, arr


def build(und: str):
    a = adapter()
    if not a.available(und):
        print(f"{und}: source {K.SOURCE} not available")
        return
    ss = a.sessions(und)
    days = []
    for dd in a.load(und, ss[0], ss[-1]):
        ix = _grid(dd.index, ["open", "high", "low", "close"])
        if np.isfinite(ix[:, 3]).sum() < 300:
            continue
        exps = sorted(e for e in dd.options.expiry.dropna().unique() if e >= dd.day) if len(dd.options) else []
        if not exps:
            continue
        ch = {}
        for e in exps[:2]:
            ks, arr = chain(dd.options, e)
            if len(ks):
                ch[e] = (ks, arr)
        fut = _grid(dd.fut, ["close", "volume"]) if len(dd.fut) else np.full((N, 2), np.nan)
        vix = _grid(dd.vix, ["close"])[:, 0] if len(dd.vix) else np.full(N, np.nan)
        days.append(dict(day=dd.day, ix=ix.astype(np.float64), vix=vix, fut_vol=fut[:, 1], chains=ch,
                         expiries=list(ch.keys()), src=dd.sources))
    os.makedirs(K.GRIDS, exist_ok=True)
    with open(K.GRIDS / f"{und}.pkl", "wb") as f:
        pickle.dump(days, f, protocol=4)
    print(und, len(days), "sessions", days[0]["day"], days[-1]["day"], flush=True)


if __name__ == "__main__":
    for u in sys.argv[1:] or K.UNDS:
        build(u)
