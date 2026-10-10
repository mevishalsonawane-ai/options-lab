"""Helpers for signal functions: per-day index arrays, candles, daily ATR, VIX, chain features (PCR, OI)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C


def day_arrays(ix, d):
    """Dense W-arrays o, h, l, c of one day (NaN = no bar)."""
    M = ix.mat()
    p = ix.pos[d]
    return M["o"][p], M["h"][p], M["l"][p], M["c"][p]


def candles(o, h, l, c, tf):
    """tf-minute candles anchored 09:15 from dense W-arrays: list of (start_col, end_col_exclusive, o, h, l, c)."""
    out = []
    for s in range(0, C.W, tf):
        e = min(s + tf, C.W)
        cc = c[s:e]
        ok = ~np.isnan(cc)
        if not ok.any():
            continue
        idx = np.nonzero(ok)[0]
        out.append((s, e, o[s + idx[0]], np.nanmax(h[s:e]), np.nanmin(l[s:e]), cc[idx[-1]]))
    return out


def daily_atr(ix, n=14):
    """ATR(n) known BEFORE each day (mean true range of the previous n days), as {day: atr}."""
    dl = ix.daily()
    pc = dl.close.shift(1)
    tr = pd.concat([dl.high - dl.low, (dl.high - pc).abs(), (dl.low - pc).abs()], axis=1).max(axis=1)
    tr.iloc[0] = dl.high.iloc[0] - dl.low.iloc[0]
    atr = tr.rolling(n).mean().shift(1)
    return atr.to_dict()


def prev_day(ix):
    """{day: (prev open, high, low, close)}."""
    dl = ix.daily()
    p = dl[["open", "high", "low", "close"]].shift(1)
    return {d: tuple(r) for d, r in zip(p.index, p.values)}


def vix_prev(mk):
    s = mk.vix.daily.close
    return s.shift(1).to_dict(), s


def pcr(chain, col, n=5):
    """Put/call OI ratio over the ATM+-n strikes at column col (None if missing)."""
    sp = chain.spot[: col + 1]
    ok = ~np.isnan(sp)
    if not ok.any():
        return None
    s = sp[ok][-1]
    i0 = int(np.argmin(np.abs(chain.K - s)))
    sl = slice(max(i0 - n, 0), i0 + n + 1)
    def last(a):
        x = a[sl, : col + 1]
        v = np.full(x.shape[0], np.nan)
        for i, row in enumerate(x):
            r = row[~np.isnan(row)]
            if len(r):
                v[i] = r[-1]
        return np.nansum(v)
    ce, pe = last(chain.oi["C"]), last(chain.oi["P"])
    return pe / ce if ce > 0 else None
