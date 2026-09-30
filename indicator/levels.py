"""Support / resistance levels, pivots and market structure."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import atr_wilder, indicator

C = "Levels & structure"


def _prev_day(df):
    """Previous session's high, low, close and today's open for every bar (intraday frames, grouped by date)."""
    d = df.groupby(df.index.normalize()).agg(high=("high", "max"), low=("low", "min"), close=("close", "last"),
                                              open=("open", "first"))
    p = d.shift()
    key = df.index.normalize()
    return (p.high.reindex(key).values, p.low.reindex(key).values, p.close.reindex(key).values,
            d.open.reindex(key).values)


@indicator(C, "Classic floor pivots from the previous day: P, R1-R3, S1-S3.")
def PivotClassic(df):
    H, L, Cl, _ = _prev_day(df)
    P = (H + L + Cl) / 3
    return pd.DataFrame({"P": P, "R1": 2 * P - L, "S1": 2 * P - H, "R2": P + (H - L), "S2": P - (H - L),
                         "R3": H + 2 * (P - L), "S3": L - 2 * (H - P)}, index=df.index)


@indicator(C, "Fibonacci pivots: P +/- 0.382 / 0.618 / 1.0 of the previous day's range.")
def PivotFibonacci(df):
    H, L, Cl, _ = _prev_day(df)
    P, R = (H + L + Cl) / 3, H - L
    return pd.DataFrame({"P": P, "R1": P + .382 * R, "R2": P + .618 * R, "R3": P + R,
                         "S1": P - .382 * R, "S2": P - .618 * R, "S3": P - R}, index=df.index)


@indicator(C, "Camarilla pivots (Nick Scott): close +/- range x 1.1/12, /6, /4, /2.")
def PivotCamarilla(df):
    H, L, Cl, _ = _prev_day(df)
    R = H - L
    out = {"P": (H + L + Cl) / 3}
    for i, f in enumerate((12, 6, 4, 2), 1):
        out[f"R{i}"] = Cl + R * 1.1 / f
        out[f"S{i}"] = Cl - R * 1.1 / f
    return pd.DataFrame(out, index=df.index)


@indicator(C, "Woodie pivots: P = (H + L + 2*today's open)/4.")
def PivotWoodie(df):
    H, L, _, O = _prev_day(df)
    P = (H + L + 2 * O) / 4
    return pd.DataFrame({"P": P, "R1": 2 * P - L, "S1": 2 * P - H, "R2": P + H - L, "S2": P - H + L}, index=df.index)


@indicator(C, "DeMark pivots: X from the previous open/close relation; R1 = X/2 - L, S1 = X/2 - H.")
def PivotDeMark(df):
    d = df.groupby(df.index.normalize()).agg(high=("high", "max"), low=("low", "min"), close=("close", "last"),
                                              open=("open", "first")).shift()
    x = np.where(d.close < d.open, d.high + 2 * d.low + d.close,
                 np.where(d.close > d.open, 2 * d.high + d.low + d.close, d.high + d.low + 2 * d.close))
    lv = pd.DataFrame({"P": x / 4, "R1": x / 2 - d.low, "S1": x / 2 - d.high}, index=d.index)
    return lv.reindex(df.index.normalize()).set_axis(df.index)


@indicator(C, "Central Pivot Range: pivot, top and bottom central levels, and width % (narrow = trend day).")
def CPR(df):
    H, L, Cl, _ = _prev_day(df)
    P = (H + L + Cl) / 3
    bc = (H + L) / 2
    tc = 2 * P - bc
    top, bot = np.maximum(tc, bc), np.minimum(tc, bc)
    return pd.DataFrame({"pivot": P, "tc": top, "bc": bot, "width_pct": (top - bot) / P * 100}, index=df.index)


@indicator(C, "Previous day high / low / close and today's open, for every bar.")
def PreviousDayLevels(df):
    H, L, Cl, O = _prev_day(df)
    return pd.DataFrame({"pdh": H, "pdl": L, "pdc": Cl, "open": O}, index=df.index)


@indicator(C, "Opening range: the first n minutes' high and low each day (the ORB levels).")
def OpeningRange(df, minutes=15):
    day = df.index.normalize()
    first = df.index - day < pd.Timedelta(hours=9, minutes=15 + minutes)
    hi = df.high.where(first).groupby(day).transform("max")
    lo = df.low.where(first).groupby(day).transform("min")
    return pd.DataFrame({"or_high": hi, "or_low": lo})


@indicator(C, "Williams fractals: a high with 2 lower highs each side (bearish), and the mirror for lows.")
def Fractals(df, n=2):
    h, l = df.high, df.low
    up = pd.Series(True, index=df.index)
    dn = pd.Series(True, index=df.index)
    for k in range(1, n + 1):
        up &= (h > h.shift(k)) & (h > h.shift(-k))
        dn &= (l < l.shift(k)) & (l < l.shift(-k))
    return pd.DataFrame({"fractal_high": h.where(up), "fractal_low": l.where(dn)})


@indicator(C, "Swing pivots (ta.pivothigh / pivotlow): confirmed after [right] bars, placed at the pivot bar.")
def SwingPivots(df, left=5, right=5):
    w = left + right + 1
    ph = df.high.rolling(w).apply(lambda x: x[left] if x[left] == x.max() else np.nan, raw=True).shift(-right)
    pl = df.low.rolling(w).apply(lambda x: x[left] if x[left] == x.min() else np.nan, raw=True).shift(-right)
    return pd.DataFrame({"pivot_high": ph, "pivot_low": pl})


@indicator(C, "ZigZag: swing points where price reverses by at least pct % (uses highs/lows).")
def ZigZag(df, pct=0.5):
    h, l = df.high.values, df.low.values
    out = np.full(len(h), np.nan)
    trend, piv_i, piv = 0, 0, h[0]
    lo_i, lo = 0, l[0]
    for i in range(1, len(h)):
        if trend >= 0:
            if h[i] >= piv:
                piv, piv_i = h[i], i
            elif l[i] <= piv * (1 - pct / 100):
                out[piv_i] = piv
                trend, piv, piv_i = -1, l[i], i
        if trend < 0:
            if l[i] <= piv:
                piv, piv_i = l[i], i
            elif h[i] >= piv * (1 + pct / 100):
                out[piv_i] = piv
                trend, piv, piv_i = 1, h[i], i
    out[piv_i] = piv
    return pd.Series(out, index=df.index)


@indicator(C, "Market structure: break of structure (BOS, with the trend) and change of character (CHoCH, "
              "against it) on closes through the last confirmed swing high / low; +1 bullish / -1 bearish.")
def MarketStructure(df, swing=3):
    sw = SwingPivots(df, swing, swing)
    # pivots are only known [swing] bars later
    ph, pl = sw.pivot_high.shift(swing).values, sw.pivot_low.shift(swing).values
    c = df.close.values
    last_h = last_l = np.nan
    trend = 0
    bos = np.zeros(len(c)); choch = np.zeros(len(c)); tr = np.zeros(len(c))
    for i in range(len(c)):
        if not np.isnan(ph[i]):
            last_h = ph[i]
        if not np.isnan(pl[i]):
            last_l = pl[i]
        if not np.isnan(last_h) and c[i] > last_h:
            (choch if trend < 0 else bos)[i] = 1
            trend, last_h = 1, np.nan
        elif not np.isnan(last_l) and c[i] < last_l:
            (choch if trend > 0 else bos)[i] = -1
            trend, last_l = -1, np.nan
        tr[i] = trend
    return pd.DataFrame({"bos": bos, "choch": choch, "trend": tr}, index=df.index)


@indicator(C, "Fair value gaps: a 3-candle gap (low > high two bars back = bullish; the mirror bearish).")
def FairValueGap(df, min_size=0.0):
    bull = (df.low - df.high.shift(2)) > min_size
    bear = (df.low.shift(2) - df.high) > min_size
    return pd.DataFrame({"bull_top": df.low.where(bull), "bull_bottom": df.high.shift(2).where(bull),
                         "bear_top": df.low.shift(2).where(bear), "bear_bottom": df.high.where(bear)})


@indicator(C, "Order blocks (common SMC definition): the last opposite candle before a move of k x ATR.")
def OrderBlocks(df, k=2.0, n=14, look=3):
    a = atr_wilder(df, n)
    up = (df.close.shift(-look) - df.close) > k * a
    dn = (df.close - df.close.shift(-look)) > k * a
    bull = up & (df.close < df.open)
    bear = dn & (df.close > df.open)
    return pd.DataFrame({"bull_ob_high": df.high.where(bull), "bull_ob_low": df.low.where(bull),
                         "bear_ob_high": df.high.where(bear), "bear_ob_low": df.low.where(bear)})


@indicator(C, "Fibonacci retracements of the last n bars' swing: 0, 23.6, 38.2, 50, 61.8, 78.6, 100 %.")
def FibRetracement(df, n=100):
    hi, lo = df.high.rolling(n).max(), df.low.rolling(n).min()
    return pd.DataFrame({f"fib_{int(r * 1000)}": hi - (hi - lo) * r for r in (0, .236, .382, .5, .618, .786, 1.0)})


@indicator(C, "Higher highs / higher lows count: +1 per bar that makes both, -1 for lower highs and lower lows.")
def HHHL(df, n=5):
    hh = (df.high > df.high.shift()) & (df.low > df.low.shift())
    ll = (df.high < df.high.shift()) & (df.low < df.low.shift())
    return (hh.astype(int) - ll.astype(int)).rolling(n).sum()
