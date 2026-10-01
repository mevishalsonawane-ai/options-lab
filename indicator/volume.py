"""Volume and money-flow indicators. All need a volume column (use BANKNIFTY futures, not the index)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import ema, indicator, need_volume, rma, sma, stdev, typical

C = "Volume"


@indicator(C, "On-Balance Volume (Granville): volume added on up closes, subtracted on down closes.", True)
def OBV(df):
    v = need_volume(df)
    return (np.sign(df.close.diff()).fillna(0) * v).cumsum()


@indicator(C, "Accumulation/Distribution line (Chaikin): cumulative close-location value x volume.", True)
def ADLine(df):
    v = need_volume(df)
    clv = ((df.close - df.low) - (df.high - df.close)) / (df.high - df.low).replace(0, np.nan)
    return (clv.fillna(0) * v).cumsum()


@indicator(C, "Chaikin Money Flow: sum(CLV x volume)/sum(volume) over n.", True)
def CMF(df, n=20):
    v = need_volume(df)
    clv = ((df.close - df.low) - (df.high - df.close)) / (df.high - df.low).replace(0, np.nan)
    return (clv.fillna(0) * v).rolling(n).sum() / v.rolling(n).sum()


@indicator(C, "Chaikin Oscillator: EMA(3) - EMA(10) of the A/D line.", True)
def ChaikinOscillator(df, fast=3, slow=10):
    ad = ADLine(df)
    return ema(ad, fast) - ema(ad, slow)


@indicator(C, "Money Flow Index (Quong & Soudack): volume-weighted RSI of the typical price.", True)
def MFI(df, n=14):
    v = need_volume(df)
    tp = typical(df)
    mf = tp * v
    pos = mf.where(tp.diff() > 0, 0.0).rolling(n).sum()
    neg = mf.where(tp.diff() < 0, 0.0).rolling(n).sum()
    from .momentum import _ratio_index
    return _ratio_index(pos, neg)


@indicator(C, "Force Index (Elder): EMA(13) of price change x volume.", True)
def ForceIndex(df, n=13):
    return ema(df.close.diff() * need_volume(df), n)


@indicator(C, "Ease of Movement (Arms): midpoint move / (volume / range), smoothed.", True)
def EOM(df, n=14, scale=1e8):
    v = need_volume(df)
    mid = (df.high + df.low) / 2
    box = (v / scale) / (df.high - df.low).replace(0, np.nan)
    return sma(mid.diff() / box, n)


@indicator(C, "Volume Price Trend: cumulative volume x % change.", True)
def VPT(df):
    return (need_volume(df) * df.close.pct_change().fillna(0)).cumsum()


@indicator(C, "Negative Volume Index (Fosback): moves only on bars where volume fell.", True)
def NVI(df):
    v = need_volume(df)
    r = df.close.pct_change().fillna(0).where(v.diff() < 0, 0.0)
    return 1000 * (1 + r).cumprod()


@indicator(C, "Positive Volume Index: moves only on bars where volume rose.", True)
def PVI(df):
    v = need_volume(df)
    r = df.close.pct_change().fillna(0).where(v.diff() > 0, 0.0)
    return 1000 * (1 + r).cumprod()


@indicator(C, "Klinger Volume Oscillator: EMA(34) - EMA(55) of signed volume force, signal EMA(13).", True)
def Klinger(df, fast=34, slow=55, signal=13):
    v = need_volume(df)
    tp = df.high + df.low + df.close
    trend = np.where(tp.diff() > 0, 1, -1)
    vf = pd.Series(v.values * trend, index=df.index)
    k = ema(vf, fast) - ema(vf, slow)
    return pd.DataFrame({"kvo": k, "signal": ema(k, signal)})


@indicator(C, "Session VWAP with +/- 1 and 2 standard-deviation bands, reset each day.", True)
def VWAP(df):
    v = need_volume(df)
    tp = typical(df)
    day = df.index.normalize() if isinstance(df.index, pd.DatetimeIndex) else pd.Series(0, index=df.index)
    cv = v.groupby(day).cumsum()
    vw = (tp * v).groupby(day).cumsum() / cv
    var = (v * (tp - vw) ** 2).groupby(day).cumsum() / cv
    sd = np.sqrt(var)
    return pd.DataFrame({"vwap": vw, "up1": vw + sd, "dn1": vw - sd, "up2": vw + 2 * sd, "dn2": vw - 2 * sd})


@indicator(C, "Volume oscillator: % difference of fast and slow volume EMAs.", True)
def VolumeOscillator(df, fast=5, slow=10):
    v = need_volume(df)
    return 100 * (ema(v, fast) - ema(v, slow)) / ema(v, slow)


@indicator(C, "Percentage Volume Oscillator: MACD applied to volume, in %.", True)
def PVO(df, fast=12, slow=26, signal=9):
    v = need_volume(df)
    p = 100 * (ema(v, fast) - ema(v, slow)) / ema(v, slow)
    return pd.DataFrame({"pvo": p, "signal": ema(p, signal)})


@indicator(C, "Relative volume: volume / its n-bar average.", True)
def RVOL(df, n=20):
    v = need_volume(df)
    return v / sma(v, n)


@indicator(C, "Twiggs Money Flow: CMF with true range and Wilder smoothing.", True)
def TwiggsMoneyFlow(df, n=21):
    v = need_volume(df)
    pc = df.close.shift()
    th = pd.concat([df.high, pc], axis=1).max(axis=1)
    tl = pd.concat([df.low, pc], axis=1).min(axis=1)
    adv = v * ((df.close - tl) - (th - df.close)) / (th - tl).replace(0, np.nan)
    return rma(adv.fillna(0), n) / rma(v, n)


@indicator(C, "Volume Zone Oscillator (Khalil): EMA of signed volume / EMA of volume, %.", True)
def VZO(df, n=14):
    v = need_volume(df)
    sv = v * np.sign(df.close.diff()).fillna(0)
    return 100 * ema(sv, n) / ema(v, n)


@indicator(C, "Volume z-score: how unusual this bar's volume is vs the last n.", True)
def VolumeZ(df, n=20):
    v = need_volume(df)
    return (v - sma(v, n)) / stdev(v, n)


@indicator(C, "Volume profile: volume per price bin over the frame, with the point of control and 70% value area.",
           True)
def VolumeProfile(df, bins=40):
    v = need_volume(df)
    tp = typical(df)
    edges = np.linspace(df.low.min(), df.high.max(), bins + 1)
    idx = np.clip(np.digitize(tp, edges) - 1, 0, bins - 1)
    vol = np.bincount(idx, weights=v.values, minlength=bins)
    mid = (edges[:-1] + edges[1:]) / 2
    poc = int(np.argmax(vol))
    lo = hi = poc
    total, got = vol.sum(), vol[poc]
    while got < 0.7 * total and (lo > 0 or hi < bins - 1):
        a = vol[lo - 1] if lo > 0 else -1
        b = vol[hi + 1] if hi < bins - 1 else -1
        if a >= b:
            lo -= 1; got += vol[lo]
        else:
            hi += 1; got += vol[hi]
    out = pd.DataFrame({"price": mid, "volume": vol})
    out["poc"] = out.index == poc
    out["value_area"] = (out.index >= lo) & (out.index <= hi)
    return out
