"""Volatility measures, bands and channels."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import atr_wilder, ema, highest, indicator, lowest, rma, sma, stdev, true_range

C = "Volatility & bands"


@indicator(C, "True range: the largest of high-low, |high-prev close|, |low-prev close|.")
def TrueRange(df):
    return true_range(df)


@indicator(C, "Average True Range (Wilder), Wilder smoothing.")
def ATR(df, n=14):
    return atr_wilder(df, n)


@indicator(C, "Normalised ATR: ATR as % of the close.")
def NATR(df, n=14):
    return 100 * atr_wilder(df, n) / df.close


@indicator(C, "Bollinger Bands (Bollinger): SMA(20) +/- 2 population std devs, %B and bandwidth.")
def Bollinger(df, n=20, k=2.0):
    m = sma(df.close, n)
    sd = stdev(df.close, n)
    up, lo = m + k * sd, m - k * sd
    return pd.DataFrame({"mid": m, "upper": up, "lower": lo, "pct_b": (df.close - lo) / (up - lo),
                         "bandwidth": (up - lo) / m * 100})


@indicator(C, "Keltner Channels (Raschke form): EMA(20) +/- 2 x ATR(10).")
def Keltner(df, n=20, k=2.0, atr_n=10):
    m = ema(df.close, n)
    a = atr_wilder(df, atr_n)
    return pd.DataFrame({"mid": m, "upper": m + k * a, "lower": m - k * a})


@indicator(C, "Donchian Channels: n-bar highest high / lowest low and the midline.")
def Donchian(df, n=20):
    hi, lo = highest(df.high, n), lowest(df.low, n)
    return pd.DataFrame({"upper": hi, "lower": lo, "mid": (hi + lo) / 2})


@indicator(C, "ATR bands: close +/- k x ATR.")
def ATRBands(df, n=14, k=2.0):
    a = atr_wilder(df, n)
    return pd.DataFrame({"upper": df.close + k * a, "lower": df.close - k * a})


@indicator(C, "Standard deviation of the close over n (population).")
def StdDev(df, n=20):
    return stdev(df.close, n)


@indicator(C, "Historical volatility: annualised std dev of log returns (bars_per_year: 252 daily, 252*375 1-min).")
def HistoricalVolatility(df, n=20, bars_per_year=252):
    r = np.log(df.close / df.close.shift())
    return r.rolling(n).std() * np.sqrt(bars_per_year) * 100


@indicator(C, "Parkinson volatility: from the high-low range, annualised %.")
def ParkinsonVolatility(df, n=20, bars_per_year=252):
    x = np.log(df.high / df.low) ** 2
    return np.sqrt(x.rolling(n).mean() / (4 * np.log(2)) * bars_per_year) * 100


@indicator(C, "Garman-Klass volatility: uses open, high, low and close, annualised %.")
def GarmanKlassVolatility(df, n=20, bars_per_year=252):
    x = 0.5 * np.log(df.high / df.low) ** 2 - (2 * np.log(2) - 1) * np.log(df.close / df.open) ** 2
    return np.sqrt(x.rolling(n).mean() * bars_per_year) * 100


@indicator(C, "Rogers-Satchell volatility: drift-independent OHLC estimator, annualised %.")
def RogersSatchellVolatility(df, n=20, bars_per_year=252):
    x = np.log(df.high / df.close) * np.log(df.high / df.open) + np.log(df.low / df.close) * np.log(df.low / df.open)
    return np.sqrt(x.rolling(n).mean() * bars_per_year) * 100


@indicator(C, "Yang-Zhang volatility: overnight + open-close + Rogers-Satchell parts, annualised %.")
def YangZhangVolatility(df, n=20, bars_per_year=252):
    o = np.log(df.open / df.close.shift())
    c = np.log(df.close / df.open)
    rs = np.log(df.high / df.close) * np.log(df.high / df.open) + np.log(df.low / df.close) * np.log(df.low / df.open)
    k = 0.34 / (1.34 + (n + 1) / (n - 1))
    v = o.rolling(n).var() + k * c.rolling(n).var() + (1 - k) * rs.rolling(n).mean()
    return np.sqrt(v * bars_per_year) * 100


@indicator(C, "Chaikin Volatility: % change over n of the EMA(10) of the high-low range.")
def ChaikinVolatility(df, n=10, roc=10):
    e = ema(df.high - df.low, n)
    return (e - e.shift(roc)) / e.shift(roc) * 100


@indicator(C, "Ulcer Index (Martin): RMS of the % drawdown from the n-bar high.")
def UlcerIndex(df, n=14):
    dd = 100 * (df.close - highest(df.close, n)) / highest(df.close, n)
    return np.sqrt((dd ** 2).rolling(n).mean())


@indicator(C, "Relative Volatility Index (Dorsey): RSI computed on the std dev instead of price change.")
def RVI(df, n=10, sd_n=10):
    sd = stdev(df.close, sd_n)
    d = df.close.diff()
    up = rma(sd.where(d > 0, 0.0), n)
    dn = rma(sd.where(d < 0, 0.0), n)
    return 100 * up / (up + dn).replace(0, np.nan)


@indicator(C, "Bollinger/Keltner squeeze ratio: Bollinger width / Keltner width (< 1 = squeeze).")
def SqueezeRatio(df, n=20):
    b = Bollinger(df, n)
    k = Keltner(df, n, 1.5)
    return (b.upper - b.lower) / (k.upper - k.lower)


@indicator(C, "Average Day Range: mean of (high - low) over n bars.")
def ADR(df, n=14):
    return sma(df.high - df.low, n)
