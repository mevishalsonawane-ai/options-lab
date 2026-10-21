"""R74 context data (PREREG.md section 2), on the same 5-minute grid as the index bars (bar b = minutes 5b .. 5b+4 after
09:15; known at the close of minute 5b+4):

    vix      India VIX: its 5-minute close and the day's first print (index_1m_INDIAVIX_<year>.parquet)
    stocks   the lake's 59 large-cap stocks (equity_1m_<year>.parquet): per 5-minute bar, the share of the stocks whose
             last price is above their own session VWAP (09:15 to the bar's end) and the stocks' total volume in the bar

Both read the Drive lake copy ($IRAALGO_LAKE, never committed) and are cached in $R74_OUT. The pure functions below
(`vix5_of_day`, `stock5_of_day`) see only the minutes up to each bar's end: test_r74.py's look-ahead guard proves it.
"""
from __future__ import annotations

import pickle
import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
NB = 75                                         # 5-minute bars in a session (09:15-15:29)


# ------------------------------------------------------------------------------------------------ pure, one day
def vix5_of_day(close_1m: np.ndarray) -> tuple[np.ndarray, float]:
    """close_1m (375,) VIX closes (NaN = no print). Returns (5-minute close per bar (75,) = the last print at or before
    the bar's end, NaN before the first print; the day's first print)."""
    c = np.asarray(close_1m, float)
    out = np.full(NB, np.nan)
    last = np.nan
    first = np.nan
    for m in range(min(len(c), 5 * NB)):
        if np.isfinite(c[m]):
            last = c[m]
            if not np.isfinite(first):
                first = c[m]
        if m % 5 == 4:
            out[m // 5] = last
    return out, float(first)


def stock5_of_day(close: np.ndarray, high: np.ndarray, low: np.ndarray, vol: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """(n_stocks, 375) minute arrays of one day (NaN / 0 where a stock has no minute). Returns (share of the stocks with
    a price so far whose last close is above their session VWAP at the bar's end (75,), NaN when none; the stocks' total
    volume in each bar (75,))."""
    c = np.asarray(close, float)
    tp = (np.asarray(high, float) + np.asarray(low, float) + c) / 3.0
    v = np.nan_to_num(np.asarray(vol, float))
    ok = np.isfinite(c) & np.isfinite(tp)
    pv = np.cumsum(np.where(ok, tp * v, 0.0), axis=1)
    vv = np.cumsum(np.where(ok, v, 0.0), axis=1)
    n, T = c.shape
    lastc = np.full(n, np.nan)
    share = np.full(NB, np.nan)
    vol5 = np.zeros(NB)
    for m in range(min(T, 5 * NB)):
        lastc = np.where(ok[:, m], c[:, m], lastc)
        if m % 5 == 4:
            vw = np.where(vv[:, m] > 0, pv[:, m] / np.where(vv[:, m] > 0, vv[:, m], 1.0), np.nan)
            have = np.isfinite(lastc) & np.isfinite(vw)
            if have.any():
                share[m // 5] = float((lastc[have] > vw[have]).mean())
            vol5[m // 5] = float(v[:, m - 4:m + 1].sum())
    return share, vol5


# ------------------------------------------------------------------------------------------------ lake readers
def _k():
    """R51's settings and reader (the lake path, the session grid)."""
    p = HERE.parent / "r51"
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
    import r51cfg as K
    import r51data
    return K, r51data


def _cache(name: str):
    import r74cfg as R
    return R.OUT / f"{name}.pkl"


def load_vix() -> dict:
    """{day: (vix 5-min closes (75,), first print)}, warm-up days included."""
    f = _cache("vix5")
    if f.exists():
        return pickle.loads(f.read_bytes())
    import pandas as pd
    K, r51data = _k()
    parts = [r51data._read(p, ["ts", "close"]) for y in r51data._years()
             if (p := K.LAKE / f"index_1m_INDIAVIX_{y}.parquet").exists()]
    x = pd.concat(parts, ignore_index=True)
    x = x[(x.day >= K.WARM_START) & (x.day <= K.END) & (x.close > 0)].drop_duplicates(["day", "r"])
    out = {}
    for d, g in x.groupby("day", sort=True):
        a = np.full(K.NROWS, np.nan)
        a[g.r.values] = g.close.values
        out[d] = vix5_of_day(a)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out


def load_stocks() -> dict:
    """{day: (share above VWAP (75,), total volume (75,), stocks present)}."""
    f = _cache("stocks5")
    if f.exists():
        return pickle.loads(f.read_bytes())
    import pandas as pd
    K, r51data = _k()
    out = {}
    for y in r51data._years():
        p = K.LAKE / f"equity_1m_{y}.parquet"
        if not p.exists():
            continue
        x = r51data._read(p, ["symbol", "ts", "high", "low", "close", "volume"])
        x = x[(x.day >= K.WARM_START) & (x.day <= K.END) & (x.close > 0)].drop_duplicates(["symbol", "day", "r"])
        x["symbol"] = x.symbol.astype(str)
        for d, g in x.groupby("day", sort=True):
            syms = sorted(g.symbol.unique())
            si = {s: i for i, s in enumerate(syms)}
            n = len(syms)
            arr = {c: np.full((n, K.NROWS), np.nan) for c in ("close", "high", "low")}
            vol = np.zeros((n, K.NROWS))
            rows = g.symbol.map(si).values
            for c in arr:
                arr[c][rows, g.r.values] = g[c].values
            vol[rows, g.r.values] = g.volume.values
            share, v5 = stock5_of_day(arr["close"], arr["high"], arr["low"], vol)
            out[d] = (share, v5, n)
    f.parent.mkdir(parents=True, exist_ok=True)
    f.write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return out
