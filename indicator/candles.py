"""Candlestick patterns (Nison's definitions, with plain thresholds). +1 bullish, -1 bearish, 0 none."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .core import indicator, sma

C = "Candlestick patterns"


def _parts(df):
    body = (df.close - df.open).abs()
    rng = (df.high - df.low).replace(0, np.nan)
    upper = df.high - df[["open", "close"]].max(axis=1)
    lower = df[["open", "close"]].min(axis=1) - df.low
    return body, rng, upper, lower


def _sig(bull, bear, index):
    return pd.Series(np.where(bull, 1, np.where(bear, -1, 0)), index=index)


@indicator(C, "Doji: body under 10% of the range.")
def Doji(df, frac=0.1):
    body, rng, _, _ = _parts(df)
    return (body <= frac * rng).astype(int)


@indicator(C, "Hammer (+1) after a fall / hanging man (-1) after a rise: long lower shadow, small body at the top.")
def Hammer(df, trend_n=5):
    body, rng, up, lo = _parts(df)
    shape = (lo >= 2 * body) & (up <= 0.3 * body.clip(lower=rng * 0.05)) & (body > 0)
    t = df.close - sma(df.close, trend_n)
    return _sig(shape & (t < 0), shape & (t > 0), df.index)


@indicator(C, "Inverted hammer (+1 after a fall) / shooting star (-1 after a rise): long upper shadow.")
def ShootingStar(df, trend_n=5):
    body, rng, up, lo = _parts(df)
    shape = (up >= 2 * body) & (lo <= 0.3 * body.clip(lower=rng * 0.05)) & (body > 0)
    t = df.close - sma(df.close, trend_n)
    return _sig(shape & (t < 0), shape & (t > 0), df.index)


@indicator(C, "Engulfing: this body fully covers the previous opposite-colour body.")
def Engulfing(df):
    o, c, po, pc = df.open, df.close, df.open.shift(), df.close.shift()
    bull = (pc < po) & (c > o) & (c >= po) & (o <= pc)
    bear = (pc > po) & (c < o) & (o >= pc) & (c <= po)
    return _sig(bull, bear, df.index)


@indicator(C, "Harami: a small body inside the previous large opposite body.")
def Harami(df):
    o, c, po, pc = df.open, df.close, df.open.shift(), df.close.shift()
    inside = (df[["open", "close"]].max(axis=1) < pd.concat([po, pc], axis=1).max(axis=1)) & \
             (df[["open", "close"]].min(axis=1) > pd.concat([po, pc], axis=1).min(axis=1))
    return _sig(inside & (pc < po) & (c > o), inside & (pc > po) & (c < o), df.index)


@indicator(C, "Piercing line (+1) / dark cloud cover (-1): opens beyond the previous close, closes past its midpoint.")
def PiercingDarkCloud(df):
    o, c, po, pc = df.open, df.close, df.open.shift(), df.close.shift()
    mid = (po + pc) / 2
    bull = (pc < po) & (o < pc) & (c > mid) & (c < po)
    bear = (pc > po) & (o > pc) & (c < mid) & (c > po)
    return _sig(bull, bear, df.index)


@indicator(C, "Morning star (+1) / evening star (-1): big candle, small star, big opposite candle past the midpoint.")
def Star(df):
    body, rng, _, _ = _parts(df)
    o, c = df.open, df.close
    b2, b1, b0 = body.shift(2), body.shift(1), body
    big = sma(body, 10)
    mid2 = (o.shift(2) + c.shift(2)) / 2
    bull = (c.shift(2) < o.shift(2)) & (b2 > big) & (b1 < 0.5 * big) & (c > o) & (c > mid2)
    bear = (c.shift(2) > o.shift(2)) & (b2 > big) & (b1 < 0.5 * big) & (c < o) & (c < mid2)
    return _sig(bull, bear, df.index)


@indicator(C, "Three white soldiers (+1) / three black crows (-1): three strong candles in a row, each closing further.")
def ThreeSoldiersCrows(df):
    g = df.close > df.open
    r = df.close < df.open
    up = g & g.shift(1, fill_value=False) & g.shift(2, fill_value=False) & (df.close > df.close.shift()) & \
        (df.close.shift() > df.close.shift(2))
    dn = r & r.shift(1, fill_value=False) & r.shift(2, fill_value=False) & (df.close < df.close.shift()) & \
        (df.close.shift() < df.close.shift(2))
    return _sig(up, dn, df.index)


@indicator(C, "Inside bar (1): the whole range inside the previous bar's range.")
def InsideBar(df):
    return ((df.high <= df.high.shift()) & (df.low >= df.low.shift())).astype(int)


@indicator(C, "Outside bar: range covers the previous bar's; +1 if it closes up, -1 down.")
def OutsideBar(df):
    out = (df.high > df.high.shift()) & (df.low < df.low.shift())
    return _sig(out & (df.close > df.open), out & (df.close < df.open), df.index)


@indicator(C, "Marubozu: body at least 95% of the range; +1 green, -1 red.")
def Marubozu(df, frac=0.95):
    body, rng, _, _ = _parts(df)
    m = body >= frac * rng
    return _sig(m & (df.close > df.open), m & (df.close < df.open), df.index)


@indicator(C, "Tweezer bottom (+1) / top (-1): two bars with matching lows / highs (within tol of the range).")
def Tweezer(df, tol=0.05):
    rng = (df.high - df.low).rolling(10).mean()
    bot = ((df.low - df.low.shift()).abs() <= tol * rng) & (df.close.shift() < df.open.shift()) & (df.close > df.open)
    top = ((df.high - df.high.shift()).abs() <= tol * rng) & (df.close.shift() > df.open.shift()) & (df.close < df.open)
    return _sig(bot, top, df.index)


@indicator(C, "Spinning top: small body (<30% of range) with both shadows longer than the body.")
def SpinningTop(df):
    body, rng, up, lo = _parts(df)
    return ((body < 0.3 * rng) & (up > body) & (lo > body)).astype(int)
