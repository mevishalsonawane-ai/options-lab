"""Momentum oscillators."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import ema, highest, indicator, linreg, lowest, rma, sma, stdev, true_range, typical, wma

C = "Momentum"


def _rsi(s: pd.Series, n: int) -> pd.Series:
    d = s.diff()
    up = rma(d.clip(lower=0), n)
    dn = rma(-d.clip(upper=0), n)
    return _ratio_index(up, dn)


def _ratio_index(up: pd.Series, dn: pd.Series) -> pd.Series:
    """100 - 100/(1 + up/dn), with TradingView's ends: 100 when nothing fell, 0 when nothing rose."""
    r = 100 - 100 / (1 + up / dn.replace(0, np.nan))
    return r.mask((dn == 0) & up.notna(), 100.0).mask((up == 0) & (dn > 0), 0.0)


@indicator(C, "Relative Strength Index (Wilder), Wilder smoothing, 0-100.")
def RSI(df, n=14, src="close"):
    return _rsi(df[src].astype(float), n)


@indicator(C, "Stochastic (Lane): %K = (close - n-low)/(n-high - n-low), slowed by k, %D = SMA(d).")
def Stochastic(df, n=14, k=3, d=3):
    lo, hi = lowest(df.low, n), highest(df.high, n)
    fast = 100 * (df.close - lo) / (hi - lo).replace(0, np.nan)
    kk = sma(fast, k)
    return pd.DataFrame({"k": kk, "d": sma(kk, d)})


@indicator(C, "Stochastic RSI (Chande & Kroll): the stochastic of RSI, smoothed.")
def StochRSI(df, rsi_n=14, n=14, k=3, d=3):
    r = _rsi(df.close, rsi_n)
    s = 100 * (r - r.rolling(n).min()) / (r.rolling(n).max() - r.rolling(n).min()).replace(0, np.nan)
    kk = sma(s, k)
    return pd.DataFrame({"k": kk, "d": sma(kk, d)})


@indicator(C, "Williams %R: (n-high - close)/(n-high - n-low) * -100, 0 to -100.")
def WilliamsR(df, n=14):
    hi, lo = highest(df.high, n), lowest(df.low, n)
    return -100 * (hi - df.close) / (hi - lo).replace(0, np.nan)


@indicator(C, "Commodity Channel Index (Lambert): (TP - SMA(TP))/(0.015 * mean deviation).")
def CCI(df, n=20):
    tp = typical(df)
    m = sma(tp, n)
    md = tp.rolling(n).apply(lambda x: np.abs(x - x.mean()).mean(), raw=True)
    return (tp - m) / (0.015 * md)


@indicator(C, "Rate of change: % change over n bars.")
def ROC(df, n=10):
    return df.close.pct_change(n) * 100


@indicator(C, "Momentum: close - close n bars ago.")
def Momentum(df, n=10):
    return df.close.diff(n)


@indicator(C, "Chande Momentum Oscillator: (sum up - sum down)/(sum up + sum down)*100 over n.")
def CMO(df, n=14):
    d = df.close.diff()
    up, dn = d.clip(lower=0).rolling(n).sum(), (-d.clip(upper=0)).rolling(n).sum()
    return 100 * (up - dn) / (up + dn).replace(0, np.nan)


@indicator(C, "Ultimate Oscillator (Williams): buying pressure over 7/14/28 bars, weighted 4:2:1.")
def UltimateOscillator(df, a=7, b=14, c=28):
    pc = df.close.shift()
    bp = df.close - pd.concat([df.low, pc], axis=1).min(axis=1)
    tr = pd.concat([df.high, pc], axis=1).max(axis=1) - pd.concat([df.low, pc], axis=1).min(axis=1)
    avg = lambda n: bp.rolling(n).sum() / tr.rolling(n).sum()  # noqa: E731
    return 100 * (4 * avg(a) + 2 * avg(b) + avg(c)) / 7


@indicator(C, "Awesome Oscillator (Williams): SMA5(hl2) - SMA34(hl2).")
def AwesomeOscillator(df, fast=5, slow=34):
    m = (df.high + df.low) / 2
    return sma(m, fast) - sma(m, slow)


@indicator(C, "Accelerator Oscillator (Williams): AO - SMA5(AO).")
def AcceleratorOscillator(df):
    ao = AwesomeOscillator(df)
    return ao - sma(ao, 5)


@indicator(C, "True Strength Index (Blau): double-smoothed momentum / double-smoothed |momentum|, signal EMA.")
def TSI(df, long=25, short=13, signal=13):
    m = df.close.diff()
    t = 100 * ema(ema(m, long), short) / ema(ema(m.abs(), long), short)
    return pd.DataFrame({"tsi": t, "signal": ema(t, signal)})


@indicator(C, "Relative Vigor Index (Ehlers): symmetric-weighted (close-open)/(high-low), with signal.")
def RVGI(df, n=10):
    co, hl = df.close - df.open, df.high - df.low
    sym = lambda x: (x + 2 * x.shift(1) + 2 * x.shift(2) + x.shift(3)) / 6  # noqa: E731
    r = sym(co).rolling(n).sum() / sym(hl).rolling(n).sum()
    return pd.DataFrame({"rvgi": r, "signal": sym(r)})


@indicator(C, "Connors RSI: mean of RSI(3), RSI(2) of the up/down streak, and the 100-bar percentile rank of ROC(1).")
def ConnorsRSI(df, rsi_n=3, streak_n=2, rank_n=100):
    c = df.close.values
    streak = np.zeros(len(c))
    for i in range(1, len(c)):
        if c[i] > c[i - 1]:
            streak[i] = streak[i - 1] + 1 if streak[i - 1] > 0 else 1
        elif c[i] < c[i - 1]:
            streak[i] = streak[i - 1] - 1 if streak[i - 1] < 0 else -1
    roc = df.close.pct_change()
    rank = roc.rolling(rank_n + 1).apply(lambda x: 100 * (x[:-1] < x[-1]).mean(), raw=True)
    return (_rsi(df.close, rsi_n) + _rsi(pd.Series(streak, index=df.index), streak_n) + rank) / 3


@indicator(C, "Fisher Transform (Ehlers): Gaussian-normalised price position in the n-bar range, with trigger.")
def FisherTransform(df, n=9):
    hl2 = ((df.high + df.low) / 2).values
    hi = pd.Series(hl2).rolling(n).max().values
    lo = pd.Series(hl2).rolling(n).min().values
    v = np.zeros(len(hl2))
    f = np.full(len(hl2), np.nan)
    for i in range(len(hl2)):
        if np.isnan(hi[i]):
            continue
        r = hi[i] - lo[i]
        x = 0.66 * ((hl2[i] - lo[i]) / r - 0.5) if r else 0.0
        v[i] = np.clip(x + 0.67 * (v[i - 1] if i else 0), -0.999, 0.999)
        prev = f[i - 1] if i and not np.isnan(f[i - 1]) else 0.0
        f[i] = 0.5 * np.log((1 + v[i]) / (1 - v[i])) + 0.5 * prev
    fs = pd.Series(f, index=df.index)
    return pd.DataFrame({"fisher": fs, "trigger": fs.shift()})


@indicator(C, "Inverse Fisher Transform of RSI (Vervoort): -1..+1, sharp turns at extremes.")
def InverseFisherRSI(df, n=5, smooth=9):
    v = 0.1 * (_rsi(df.close, n) - 50)
    w = wma(v, smooth)
    return (np.exp(2 * w) - 1) / (np.exp(2 * w) + 1)


@indicator(C, "KDJ: stochastic K and D with J = 3K - 2D (common in Asian markets).")
def KDJ(df, n=9, m1=3, m2=3):
    lo, hi = lowest(df.low, n), highest(df.high, n)
    rsv = 100 * (df.close - lo) / (hi - lo).replace(0, np.nan)
    k = rsv.ewm(alpha=1 / m1, adjust=False).mean()
    d = k.ewm(alpha=1 / m2, adjust=False).mean()
    return pd.DataFrame({"k": k, "d": d, "j": 3 * k - 2 * d})


@indicator(C, "Stochastic Momentum Index (Blau): close vs the range midpoint, double-smoothed, -100..100.")
def SMI(df, n=10, s1=3, s2=3, signal=10):
    mid = (highest(df.high, n) + lowest(df.low, n)) / 2
    rng = highest(df.high, n) - lowest(df.low, n)
    num = ema(ema(df.close - mid, s1), s2)
    den = ema(ema(rng, s1), s2) / 2
    smi = 100 * num / den
    return pd.DataFrame({"smi": smi, "signal": ema(smi, signal)})


@indicator(C, "Laguerre RSI (Ehlers): a four-element Laguerre filter RSI, gamma 0.5, 0-1.")
def LaguerreRSI(df, gamma=0.5):
    c = df.close.values
    l0 = l1 = l2 = l3 = c[0]
    out = np.zeros(len(c))
    for i, x in enumerate(c):
        p0, p1, p2 = l0, l1, l2
        l0 = (1 - gamma) * x + gamma * l0
        l1 = -gamma * l0 + p0 + gamma * l1
        l2 = -gamma * l1 + p1 + gamma * l2
        l3 = -gamma * l2 + p2 + gamma * l3
        cu = max(l0 - l1, 0) + max(l1 - l2, 0) + max(l2 - l3, 0)
        cd = max(l1 - l0, 0) + max(l2 - l1, 0) + max(l3 - l2, 0)
        out[i] = cu / (cu + cd) if cu + cd else 0
    return pd.Series(out, index=df.index)


@indicator(C, "Balance of Power (Livshin): (close - open)/(high - low), smoothed.")
def BOP(df, n=14):
    return sma((df.close - df.open) / (df.high - df.low).replace(0, np.nan), n)


@indicator(C, "DeMarker (DeMark): up-pressure / (up + down pressure) over n, 0-1.")
def DeMarker(df, n=14):
    dmax = (df.high - df.high.shift()).clip(lower=0)
    dmin = (df.low.shift() - df.low).clip(lower=0)
    a, b = sma(dmax, n), sma(dmin, n)
    return a / (a + b).replace(0, np.nan)


@indicator(C, "Psychological line: % of up closes in the last n bars.")
def PsychologicalLine(df, n=12):
    return 100 * (df.close.diff() > 0).rolling(n).mean()


@indicator(C, "Relative Momentum Index (Altman): RSI on the m-bar change instead of 1-bar.")
def RMI(df, n=20, m=5):
    d = df.close.diff(m)
    up, dn = rma(d.clip(lower=0), n), rma(-d.clip(upper=0), n)
    return _ratio_index(up, dn)


@indicator(C, "TTM-style squeeze: Bollinger(20,2) inside Keltner(20,1.5) = squeeze on; momentum = linreg of "
              "close minus the midpoint of the Donchian/SMA mean (the widely published formula).")
def Squeeze(df, n=20, bb_k=2.0, kc_k=1.5):
    m = sma(df.close, n)
    sd = stdev(df.close, n)
    rng = sma(true_range(df), n)
    on = ((m - bb_k * sd) > (m - kc_k * rng)) & ((m + bb_k * sd) < (m + kc_k * rng))
    mid = ((highest(df.high, n) + lowest(df.low, n)) / 2 + m) / 2
    mom = linreg(df.close - mid, n)
    return pd.DataFrame({"squeeze_on": on.astype(int), "momentum": mom})


@indicator(C, "WaveTrend oscillator (the widely published channel-index formula): wt1 and wt2 = SMA4(wt1).")
def WaveTrend(df, n1=10, n2=21):
    ap = typical(df)
    esa = ema(ap, n1)
    d = ema((ap - esa).abs(), n1)
    ci = (ap - esa) / (0.015 * d)
    wt1 = ema(ci, n2)
    return pd.DataFrame({"wt1": wt1, "wt2": sma(wt1, 4)})


@indicator(C, "QQE (Quantitative Qualitative Estimation): smoothed RSI with a trailing band of 4.236 x its "
              "smoothed ATR.")
def QQE(df, rsi_n=14, smooth=5, factor=4.236):
    r = ema(_rsi(df.close, rsi_n), smooth)
    atr_rsi = (r - r.shift()).abs()
    dar = ema(ema(atr_rsi, 2 * rsi_n - 1), 2 * rsi_n - 1) * factor
    rv, dv = r.values, dar.values
    line = np.full(len(rv), np.nan)
    for i in range(1, len(rv)):
        if np.isnan(rv[i]) or np.isnan(dv[i]):
            continue
        lo, hi = rv[i] - dv[i], rv[i] + dv[i]
        prev = line[i - 1]
        if np.isnan(prev):
            line[i] = lo
        elif rv[i - 1] > prev and rv[i] > prev:
            line[i] = max(prev, lo)
        elif rv[i - 1] < prev and rv[i] < prev:
            line[i] = min(prev, hi)
        else:
            line[i] = lo if rv[i] > prev else hi
    return pd.DataFrame({"rsi_smooth": r, "trail": line}, index=df.index)


@indicator(C, "Elder Impulse: +1 when EMA(13) and the MACD histogram both rise, -1 when both fall, else 0.")
def ElderImpulse(df, n=13):
    e = ema(df.close, n)
    m = ema(df.close, 12) - ema(df.close, 26)
    h = m - ema(m, 9)
    up = (e.diff() > 0) & (h.diff() > 0)
    dn = (e.diff() < 0) & (h.diff() < 0)
    return pd.Series(np.where(up, 1, np.where(dn, -1, 0)), index=df.index)


@indicator(C, "Price Momentum Oscillator (Swenlin): double-smoothed ROC(1) x 10, signal EMA(10).")
def PMO(df, a=35, b=20, signal=10):
    roc = df.close.pct_change() * 100
    p = 10 * ema(ema(roc, a), b)
    return pd.DataFrame({"pmo": p, "signal": ema(p, signal)})


@indicator(C, "Chande Forecast Oscillator: % distance of the close from the n-bar regression forecast.")
def CFO(df, n=14):
    return 100 * (df.close - linreg(df.close, n)) / df.close


@indicator(C, "Random Walk Index (Poulos): trend strength vs a random walk, high and low lines.")
def RWI(df, n=14):
    atr = rma(true_range(df), n)
    rh = pd.concat([(df.high - df.low.shift(k)) / (atr * np.sqrt(k)) for k in range(2, n + 1)], axis=1).max(axis=1)
    rl = pd.concat([(df.high.shift(k) - df.low) / (atr * np.sqrt(k)) for k in range(2, n + 1)], axis=1).max(axis=1)
    return pd.DataFrame({"rwi_high": rh, "rwi_low": rl})


@indicator(C, "Intraday Momentum Index (Chande): RSI of candle bodies (close vs open) over n.")
def IMI(df, n=14):
    body = df.close - df.open
    up, dn = body.clip(lower=0).rolling(n).sum(), (-body.clip(upper=0)).rolling(n).sum()
    return 100 * up / (up + dn).replace(0, np.nan)
