"""Moving averages. Each returns one Series; the source is the close unless [src] names another column."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import ema, indicator, linreg, need_volume, rma, sma, wma

C = "Moving averages"


def _s(df, src):
    return df[src].astype(float)


@indicator(C, "Simple moving average: the plain mean of the last n closes.")
def SMA(df, n=20, src="close"):
    return sma(_s(df, src), n)


@indicator(C, "Exponential moving average, alpha = 2/(n+1).")
def EMA(df, n=20, src="close"):
    return ema(_s(df, src), n)


@indicator(C, "Weighted moving average: linear weights 1..n, newest heaviest.")
def WMA(df, n=20, src="close"):
    return wma(_s(df, src), n)


@indicator(C, "Wilder's smoothed moving average (RMA / SMMA), alpha = 1/n.")
def RMA(df, n=14, src="close"):
    return rma(_s(df, src), n)


@indicator(C, "Double EMA (Mulloy): 2*EMA - EMA(EMA), less lag.")
def DEMA(df, n=20, src="close"):
    e = ema(_s(df, src), n)
    return 2 * e - ema(e, n)


@indicator(C, "Triple EMA (Mulloy): 3*E1 - 3*E2 + E3.")
def TEMA(df, n=20, src="close"):
    e1 = ema(_s(df, src), n)
    e2 = ema(e1, n)
    return 3 * e1 - 3 * e2 + ema(e2, n)


@indicator(C, "Triangular moving average: an SMA of an SMA (weights peak mid-window).")
def TRIMA(df, n=20, src="close"):
    a = (n + 1) // 2
    return sma(sma(_s(df, src), a), n - a + 1)


@indicator(C, "Hull moving average: WMA(2*WMA(n/2) - WMA(n), sqrt(n)).")
def HMA(df, n=20, src="close"):
    s = _s(df, src)
    return wma(2 * wma(s, max(n // 2, 1)) - wma(s, n), max(int(np.sqrt(n)), 1))


@indicator(C, "Zero-lag EMA (Ehlers): EMA of close + (close - close[lag]), lag = (n-1)/2.")
def ZLEMA(df, n=20, src="close"):
    s = _s(df, src)
    lag = (n - 1) // 2
    return ema(s + (s - s.shift(lag)), n)


@indicator(C, "Kaufman adaptive MA: speed set by the efficiency ratio (net move / path length).")
def KAMA(df, n=10, fast=2, slow=30, src="close"):
    s = _s(df, src).values
    out = np.full(len(s), np.nan)
    fsc, ssc = 2 / (fast + 1), 2 / (slow + 1)
    for i in range(n, len(s)):
        change = abs(s[i] - s[i - n])
        vol = np.abs(np.diff(s[i - n:i + 1])).sum()
        er = change / vol if vol else 0.0
        sc = (er * (fsc - ssc) + ssc) ** 2
        prev = out[i - 1] if not np.isnan(out[i - 1]) else s[i - 1]
        out[i] = prev + sc * (s[i] - prev)
    return pd.Series(out, index=df.index)


@indicator(C, "Arnaud Legoux MA: Gaussian weights centred at [offset] of the window, width n/sigma.")
def ALMA(df, n=9, offset=0.85, sigma=6.0, src="close"):
    m = offset * (n - 1)
    s_ = n / sigma
    w = np.exp(-((np.arange(n) - m) ** 2) / (2 * s_ * s_))
    w /= w.sum()
    return _s(df, src).rolling(n, min_periods=n).apply(lambda x: np.dot(x, w), raw=True)


@indicator(C, "Least-squares MA: the end point of the n-bar linear regression line.")
def LSMA(df, n=25, src="close"):
    return linreg(_s(df, src), n)


@indicator(C, "Volume-weighted MA: sum(close*volume)/sum(volume) over n bars.", needs_volume=True)
def VWMA(df, n=20, src="close"):
    v = need_volume(df)
    s = _s(df, src)
    return (s * v).rolling(n).sum() / v.rolling(n).sum()


@indicator(C, "Tillson T3: six chained EMAs with volume factor v (default 0.7).")
def T3(df, n=5, v=0.7, src="close"):
    e1 = ema(_s(df, src), n)
    e2 = ema(e1, n); e3 = ema(e2, n); e4 = ema(e3, n); e5 = ema(e4, n); e6 = ema(e5, n)
    c1 = -v ** 3
    c2 = 3 * v ** 2 + 3 * v ** 3
    c3 = -6 * v ** 2 - 3 * v - 3 * v ** 3
    c4 = 1 + 3 * v + v ** 3 + 3 * v ** 2
    return c1 * e6 + c2 * e5 + c3 * e4 + c4 * e3


@indicator(C, "McGinley Dynamic: MD += (close - MD) / (k*n*(close/MD)^4), tracks price speed.")
def McGinley(df, n=14, k=0.6, src="close"):
    s = _s(df, src).values
    out = np.full(len(s), np.nan)
    out[0] = s[0]
    for i in range(1, len(s)):
        md = out[i - 1]
        out[i] = md + (s[i] - md) / (k * n * (s[i] / md) ** 4)
    return pd.Series(out, index=df.index)


@indicator(C, "VIDYA (Chande): an EMA whose alpha is scaled by |CMO(9)|.")
def VIDYA(df, n=14, cmo_n=9, src="close"):
    s = _s(df, src)
    d = s.diff()
    up = d.clip(lower=0).rolling(cmo_n).sum()
    dn = (-d.clip(upper=0)).rolling(cmo_n).sum()
    k = ((up - dn) / (up + dn)).abs().fillna(0).values
    a = 2 / (n + 1)
    v = s.values
    out = np.full(len(v), np.nan)
    out[0] = v[0]
    for i in range(1, len(v)):
        out[i] = a * k[i] * v[i] + (1 - a * k[i]) * out[i - 1]
    return pd.Series(out, index=df.index)


@indicator(C, "Fractal adaptive MA (Ehlers): alpha from the fractal dimension of the window (n even).")
def FRAMA(df, n=16, src="close"):
    s = _s(df, src).values
    h, l = df.high.values, df.low.values
    half = n // 2
    out = np.full(len(s), np.nan)
    for i in range(n - 1, len(s)):
        h1, l1 = h[i - n + 1:i - half + 1].max(), l[i - n + 1:i - half + 1].min()
        h2, l2 = h[i - half + 1:i + 1].max(), l[i - half + 1:i + 1].min()
        h3, l3 = h[i - n + 1:i + 1].max(), l[i - n + 1:i + 1].min()
        n1, n2, n3 = (h1 - l1) / half, (h2 - l2) / half, (h3 - l3) / n
        dim = (np.log(n1 + n2) - np.log(n3)) / np.log(2) if n1 > 0 and n2 > 0 and n3 > 0 else 1.0
        alpha = float(np.clip(np.exp(-4.6 * (dim - 1)), 0.01, 1.0))
        prev = out[i - 1] if not np.isnan(out[i - 1]) else s[i]
        out[i] = alpha * s[i] + (1 - alpha) * prev
    return pd.Series(out, index=df.index)


@indicator(C, "Jurik-style smoothing is proprietary; this is the published JMA approximation (phase 0, power 2).")
def JMA(df, n=7, phase=0, power=2, src="close"):
    s = _s(df, src).values
    pr = 0.5 if phase < -100 else 2.5 if phase > 100 else phase / 100 + 1.5
    beta = 0.45 * (n - 1) / (0.45 * (n - 1) + 2)
    alpha = beta ** power
    e0 = e1 = e2 = 0.0
    jma = s[0]
    out = np.full(len(s), np.nan)
    for i, x in enumerate(s):
        e0 = (1 - alpha) * x + alpha * e0
        e1 = (x - e0) * (1 - beta) + beta * e1
        e2 = (e0 + pr * e1 - jma) * (1 - alpha) ** 2 + alpha ** 2 * e2
        jma = e2 + jma
        out[i] = jma
    out[:n] = np.nan
    return pd.Series(out, index=df.index)


@indicator(C, "Guppy multiple MAs: 6 short (3..15) and 6 long (30..60) EMAs.")
def GMMA(df, src="close"):
    s = _s(df, src)
    return pd.DataFrame({f"ema{n}": ema(s, n) for n in (3, 5, 8, 10, 12, 15, 30, 35, 40, 45, 50, 60)})


@indicator(C, "Moving-average ribbon: EMAs 8..89 (Fibonacci) and whether they are stacked up (+1) / down (-1).")
def MARibbon(df, src="close"):
    s = _s(df, src)
    ns = (8, 13, 21, 34, 55, 89)
    out = pd.DataFrame({f"ema{n}": ema(s, n) for n in ns})
    v = out.values
    up = np.all(np.diff(v, axis=1) < 0, axis=1)
    dn = np.all(np.diff(v, axis=1) > 0, axis=1)
    out["stack"] = np.where(up, 1, np.where(dn, -1, 0))
    return out


@indicator(C, "Envelopes: SMA(n) +/- pct %.")
def Envelope(df, n=20, pct=2.5, src="close"):
    m = sma(_s(df, src), n)
    return pd.DataFrame({"mid": m, "upper": m * (1 + pct / 100), "lower": m * (1 - pct / 100)})
