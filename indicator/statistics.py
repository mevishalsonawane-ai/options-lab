"""Statistical measures on price."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import indicator, sma, stdev

C = "Statistics"


@indicator(C, "Z-score of the close vs its n-bar mean, in standard deviations.")
def ZScore(df, n=20):
    return (df.close - sma(df.close, n)) / stdev(df.close, n)


@indicator(C, "Percentile rank of the close within the last n bars (0-100).")
def PercentRank(df, n=100):
    return df.close.rolling(n).apply(lambda x: 100 * (x[:-1] <= x[-1]).mean(), raw=True)


@indicator(C, "Rolling correlation of this close with another series [other] (e.g. NIFTY) over n.")
def Correlation(df, other: pd.Series, n=20):
    return df.close.pct_change().rolling(n).corr(other.reindex(df.index).pct_change())


@indicator(C, "Rolling beta of this close's returns against [other]'s over n.")
def Beta(df, other: pd.Series, n=60):
    r = df.close.pct_change()
    o = other.reindex(df.index).pct_change()
    return r.rolling(n).cov(o) / o.rolling(n).var()


@indicator(C, "Hurst exponent (rescaled range) over n bars: > 0.5 trending, < 0.5 mean-reverting.")
def Hurst(df, n=100):
    def h(x):
        r = np.diff(np.log(x))
        lags = [l for l in (4, 8, 16, 32) if l < len(r)]
        if len(lags) < 2:
            return np.nan
        rs = []
        for l in lags:
            chunks = [r[i:i + l] for i in range(0, len(r) - l + 1, l)]
            v = []
            for c in chunks:
                y = np.cumsum(c - c.mean())
                s = c.std()
                if s > 0:
                    v.append((y.max() - y.min()) / s)
            rs.append(np.mean(v) if v else np.nan)
        ok = ~np.isnan(rs)
        return np.polyfit(np.log(np.array(lags)[ok]), np.log(np.array(rs)[ok]), 1)[0] if ok.sum() >= 2 else np.nan
    return df.close.rolling(n).apply(h, raw=True)


@indicator(C, "Efficiency ratio (Kaufman): net move / sum of absolute moves over n (1 = straight line).")
def EfficiencyRatio(df, n=10):
    return df.close.diff(n).abs() / df.close.diff().abs().rolling(n).sum()


@indicator(C, "Rolling skewness of returns over n.")
def Skew(df, n=50):
    return df.close.pct_change().rolling(n).skew()


@indicator(C, "Rolling excess kurtosis of returns over n.")
def Kurtosis(df, n=50):
    return df.close.pct_change().rolling(n).kurt()


@indicator(C, "Shannon entropy of up/down closes over n (1 = random, 0 = one-way).")
def Entropy(df, n=20):
    up = (df.close.diff() > 0).rolling(n).mean()
    p = up.clip(1e-9, 1 - 1e-9)
    return -(p * np.log2(p) + (1 - p) * np.log2(1 - p))


@indicator(C, "Drawdown from the running peak, %.")
def Drawdown(df):
    return 100 * (df.close / df.close.cummax() - 1)
