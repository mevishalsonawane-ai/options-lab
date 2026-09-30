"""Trend and direction indicators."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import atr_wilder, ema, highest, indicator, linreg, linreg_slope, lowest, rma, sma, stdev, true_range

C = "Trend"


@indicator(C, "MACD (Appel): EMA(12)-EMA(26), signal EMA(9), histogram.")
def MACD(df, fast=12, slow=26, signal=9):
    m = ema(df.close, fast) - ema(df.close, slow)
    s = ema(m, signal)
    return pd.DataFrame({"macd": m, "signal": s, "hist": m - s})


@indicator(C, "Percentage price oscillator: MACD as % of the slow EMA.")
def PPO(df, fast=12, slow=26, signal=9):
    p = (ema(df.close, fast) - ema(df.close, slow)) / ema(df.close, slow) * 100
    s = ema(p, signal)
    return pd.DataFrame({"ppo": p, "signal": s, "hist": p - s})


@indicator(C, "Absolute price oscillator: fast SMA - slow SMA.")
def APO(df, fast=10, slow=20):
    return sma(df.close, fast) - sma(df.close, slow)


@indicator(C, "ADX / DMI (Wilder): +DI, -DI and ADX with Wilder smoothing.")
def ADX(df, n=14):
    up = df.high.diff()
    dn = -df.low.diff()
    plus = pd.Series(np.where((up > dn) & (up > 0), up, 0.0), index=df.index)
    minus = pd.Series(np.where((dn > up) & (dn > 0), dn, 0.0), index=df.index)
    tr = rma(true_range(df), n)
    pdi = 100 * rma(plus, n) / tr
    mdi = 100 * rma(minus, n) / tr
    dx = 100 * (pdi - mdi).abs() / (pdi + mdi).replace(0, np.nan)
    return pd.DataFrame({"plus_di": pdi, "minus_di": mdi, "adx": rma(dx, n)})


@indicator(C, "ADX rating (ADXR): average of ADX now and n bars ago.")
def ADXR(df, n=14):
    a = ADX(df, n).adx
    return (a + a.shift(n)) / 2


@indicator(C, "Aroon (Chande): bars since the n-bar high / low as 0-100, and the oscillator.")
def Aroon(df, n=25):
    up = df.high.rolling(n + 1).apply(lambda x: 100 * np.argmax(x) / n, raw=True)
    dn = df.low.rolling(n + 1).apply(lambda x: 100 * np.argmin(x) / n, raw=True)
    return pd.DataFrame({"up": up, "down": dn, "osc": up - dn})


@indicator(C, "Supertrend: hl2 -/+ mult*ATR bands that flip with the close; direction +1 up / -1 down.")
def Supertrend(df, n=10, mult=3.0):
    atr = atr_wilder(df, n).values
    hl2 = ((df.high + df.low) / 2).values
    c = df.close.values
    up, dn = hl2 - mult * atr, hl2 + mult * atr
    lo, hi = up.copy(), dn.copy()
    d = np.ones(len(c))
    st = np.full(len(c), np.nan)
    for i in range(1, len(c)):
        if np.isnan(atr[i]):
            continue
        lo[i] = up[i] if (np.isnan(lo[i - 1]) or up[i] > lo[i - 1] or c[i - 1] < lo[i - 1]) else lo[i - 1]
        hi[i] = dn[i] if (np.isnan(hi[i - 1]) or dn[i] < hi[i - 1] or c[i - 1] > hi[i - 1]) else hi[i - 1]
        if d[i - 1] == -1:
            d[i] = 1 if c[i] > hi[i] else -1
        else:
            d[i] = -1 if c[i] < lo[i] else 1
        st[i] = lo[i] if d[i] == 1 else hi[i]
    return pd.DataFrame({"supertrend": st, "direction": d}, index=df.index)


@indicator(C, "Parabolic SAR (Wilder): step 0.02, max 0.2; trend +1 / -1.")
def PSAR(df, step=0.02, max_af=0.2):
    h, l = df.high.values, df.low.values
    n = len(h)
    sar = np.full(n, np.nan)
    trend = np.zeros(n)
    if n < 2:
        return pd.DataFrame({"sar": sar, "trend": trend}, index=df.index)
    up = h[1] >= h[0]
    ep = h[1] if up else l[1]
    s = l[0] if up else h[0]
    af = step
    for i in range(1, n):
        s = s + af * (ep - s)
        if up:
            s = min(s, l[i - 1], l[i - 2] if i >= 2 else l[i - 1])
            if l[i] < s:
                up, s, ep, af = False, ep, l[i], step
            elif h[i] > ep:
                ep, af = h[i], min(af + step, max_af)
        else:
            s = max(s, h[i - 1], h[i - 2] if i >= 2 else h[i - 1])
            if h[i] > s:
                up, s, ep, af = True, ep, h[i], step
            elif l[i] < ep:
                ep, af = l[i], min(af + step, max_af)
        sar[i] = s
        trend[i] = 1 if up else -1
    return pd.DataFrame({"sar": sar, "trend": trend}, index=df.index)


@indicator(C, "Ichimoku (Hosoda): tenkan 9, kijun 26, spans A/B shifted 26 ahead, chikou 26 back.")
def Ichimoku(df, tenkan=9, kijun=26, senkou=52):
    t = (highest(df.high, tenkan) + lowest(df.low, tenkan)) / 2
    k = (highest(df.high, kijun) + lowest(df.low, kijun)) / 2
    a = ((t + k) / 2).shift(kijun)
    b = ((highest(df.high, senkou) + lowest(df.low, senkou)) / 2).shift(kijun)
    return pd.DataFrame({"tenkan": t, "kijun": k, "span_a": a, "span_b": b, "chikou": df.close.shift(-kijun)})


@indicator(C, "Vortex (Botes & Siepman): VI+ and VI- from up/down trend movement over n.")
def Vortex(df, n=14):
    vp = (df.high - df.low.shift()).abs().rolling(n).sum()
    vm = (df.low - df.high.shift()).abs().rolling(n).sum()
    tr = true_range(df).rolling(n).sum()
    return pd.DataFrame({"vi_plus": vp / tr, "vi_minus": vm / tr})


@indicator(C, "TRIX (Hutson): 1-bar % change of a triple EMA, with signal.")
def TRIX(df, n=15, signal=9):
    e = ema(ema(ema(df.close, n), n), n)
    t = e.pct_change() * 100
    return pd.DataFrame({"trix": t, "signal": ema(t, signal)})


@indicator(C, "Know Sure Thing (Pring): weighted sum of four smoothed ROCs, signal SMA(9).")
def KST(df):
    roc = lambda n: df.close.pct_change(n) * 100  # noqa: E731
    k = sma(roc(10), 10) + 2 * sma(roc(15), 10) + 3 * sma(roc(20), 10) + 4 * sma(roc(30), 15)
    return pd.DataFrame({"kst": k, "signal": sma(k, 9)})


@indicator(C, "Detrended price oscillator: close[n/2+1 ago] - SMA(n) (cycle, not trend).")
def DPO(df, n=20):
    return df.close.shift(n // 2 + 1) - sma(df.close, n)


@indicator(C, "Mass Index (Dorsey): sum of EMA9(range)/EMA9(EMA9(range)) over 25; >27 then <26.5 = reversal bulge.")
def MassIndex(df, n=9, total=25):
    r = df.high - df.low
    e1 = ema(r, n)
    return (e1 / ema(e1, n)).rolling(total).sum()


@indicator(C, "Coppock curve: WMA(10) of ROC(14) + ROC(11) (monthly in the original).")
def Coppock(df, r1=14, r2=11, n=10):
    from .core import wma
    return wma(df.close.pct_change(r1) * 100 + df.close.pct_change(r2) * 100, n)


@indicator(C, "Schaff Trend Cycle: a double stochastic of the MACD line, 0-100.")
def STC(df, fast=23, slow=50, cycle=10, smooth=0.5):
    m = ema(df.close, fast) - ema(df.close, slow)

    def stoch_smooth(x):
        lo, hi = x.rolling(cycle).min(), x.rolling(cycle).max()
        k = 100 * (x - lo) / (hi - lo).replace(0, np.nan)
        return k.ffill().ewm(alpha=smooth, adjust=False).mean()
    return stoch_smooth(stoch_smooth(m))


@indicator(C, "QStick (Chande): SMA of close - open; > 0 buying pressure.")
def QStick(df, n=14):
    return sma(df.close - df.open, n)


@indicator(C, "Choppiness Index (Dreiss): 100*log10(sum ATR / range)/log10(n); >61.8 choppy, <38.2 trending.")
def Choppiness(df, n=14):
    s = true_range(df).rolling(n).sum()
    rng = highest(df.high, n) - lowest(df.low, n)
    return 100 * np.log10(s / rng) / np.log10(n)


@indicator(C, "Elder Ray: bull power = high - EMA(13), bear power = low - EMA(13).")
def ElderRay(df, n=13):
    e = ema(df.close, n)
    return pd.DataFrame({"bull": df.high - e, "bear": df.low - e})


@indicator(C, "Williams Alligator: SMMA(hl2) jaw 13/8, teeth 8/5, lips 5/3 (period/shift).")
def Alligator(df):
    m = (df.high + df.low) / 2
    return pd.DataFrame({"jaw": rma(m, 13).shift(8), "teeth": rma(m, 8).shift(5), "lips": rma(m, 5).shift(3)})


@indicator(C, "Linear regression slope of the close over n bars (points per bar).")
def LinRegSlope(df, n=14):
    return linreg_slope(df.close, n)


@indicator(C, "Linear regression channel: the n-bar line +/- k standard deviations of the residual.")
def LinRegChannel(df, n=100, k=2.0):
    mid = linreg(df.close, n)
    sd = stdev(df.close - mid, n)
    return pd.DataFrame({"mid": mid, "upper": mid + k * sd, "lower": mid - k * sd})


@indicator(C, "Heikin Ashi candles (smoothed open/high/low/close).")
def HeikinAshi(df):
    hc = (df.open + df.high + df.low + df.close) / 4
    ho = np.empty(len(df))
    ho[0] = (df.open.iloc[0] + df.close.iloc[0]) / 2
    hcv = hc.values
    for i in range(1, len(df)):
        ho[i] = (ho[i - 1] + hcv[i - 1]) / 2
    ho = pd.Series(ho, index=df.index)
    return pd.DataFrame({"open": ho, "high": pd.concat([df.high, ho, hc], axis=1).max(axis=1),
                         "low": pd.concat([df.low, ho, hc], axis=1).min(axis=1), "close": hc})


@indicator(C, "Chande Kroll stop: ATR stops off the n-bar extremes, smoothed over q bars.")
def ChandeKrollStop(df, p=10, x=1.0, q=9):
    a = atr_wilder(df, p)
    hs = highest(df.high, p) - x * a
    ls = lowest(df.low, p) + x * a
    # As published: stop short = highest(first high stop, q), stop long = lowest(first low stop, q).
    return pd.DataFrame({"stop_short": highest(hs, q), "stop_long": lowest(ls, q)})


@indicator(C, "Chandelier exit (Le Beau): highest high - k*ATR for longs, lowest low + k*ATR for shorts.")
def ChandelierExit(df, n=22, k=3.0):
    a = atr_wilder(df, n)
    return pd.DataFrame({"long_stop": highest(df.high, n) - k * a, "short_stop": lowest(df.low, n) + k * a})


@indicator(C, "Directional trend: +1 when EMA(fast) > EMA(slow) and the close is above both, -1 the opposite, else 0.")
def EMATrend(df, fast=9, slow=21):
    f, s = ema(df.close, fast), ema(df.close, slow)
    up = (f > s) & (df.close > f)
    dn = (f < s) & (df.close < f)
    return pd.Series(np.where(up, 1, np.where(dn, -1, 0)), index=df.index)


@indicator(C, "Half Trend (Alex Orekhov's published logic, simplified): trend flips when the close crosses the "
              "running high-low midline of amplitude 2; returns the trend line and direction.")
def HalfTrend(df, amplitude=2, dev=2.0):
    h, l, c = df.high.values, df.low.values, df.close.values
    hma = df.high.rolling(amplitude).mean().values
    lma = df.low.rolling(amplitude).mean().values
    hh = df.high.rolling(amplitude).max().values
    ll = df.low.rolling(amplitude).min().values
    n = len(c)
    trend = np.zeros(n)
    line = np.full(n, np.nan)
    max_low, min_high = l[0], h[0]
    up, down = np.nan, np.nan
    t = 0
    for i in range(amplitude, n):
        if t == 0:
            max_low = max(ll[i], max_low)
            if hma[i] < max_low and c[i] < l[i - 1]:
                t, min_high = 1, hh[i]
        else:
            min_high = min(hh[i], min_high)
            if lma[i] > min_high and c[i] > h[i - 1]:
                t, max_low = 0, ll[i]
        if t == 0:
            up = max_low if np.isnan(up) else max(max_low, up)
            line[i], trend[i] = up, 1
            down = np.nan
        else:
            down = min_high if np.isnan(down) else min(min_high, down)
            line[i], trend[i] = down, -1
            up = np.nan
    return pd.DataFrame({"line": line, "direction": trend}, index=df.index)
