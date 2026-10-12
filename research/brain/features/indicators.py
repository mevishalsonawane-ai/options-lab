"""Indicators family: trend and momentum indicators on the index, per minute, point in time.

1-minute indicators run continuously across sessions, like a chart (the overnight gap is part of the series), on the
rows that have index prices: a missing minute is skipped, never filled, and its value is NaN. Recursive indicators
(EMA, Wilder smoothing, Supertrend) carry state from far back, so their declared lookback (10 sessions, 3,750 bars) is
where an EMA-200 of 1-minute bars has forgotten its start below float precision (tests/test_dictionary.py checks it);
the store's warm-up is longer anyway.

Higher-timeframe trend uses session-anchored 15-minute and 60-minute bars (used once complete) and daily closes.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .bars import bars, compress, ema, scatter, to_rows, true_range, wilder
from .registry import feature

OHLC = ["minutes.open", "minutes.high", "minutes.low", "minutes.close"]
LB = "10d"
DAILY_EMA, DAILY_WINDOW = 20, 60


def _hlc(ctx):
    def make():
        idx, (h, l, c) = compress(ctx.h, ctx.l, ctx.c)
        return idx, h, l, c
    return ctx.memo("hlc", make)


def _ema_c(ctx, n):
    def make():
        idx, _, _, c = _hlc(ctx)
        return scatter(ctx.n, idx, ema(c, span=n))
    return ctx.memo(("ema", n), make)


def _ema_feats(n):
    @feature(f"ema{n}", "indicators", 1, OHLC, LB, f"EMA({n}) of the 1-minute index close, index points.")
    def e(ctx):
        return _ema_c(ctx, n)

    @feature(f"ema{n}_dist_pct", "indicators", 1, OHLC, LB, f"Index close vs its EMA({n}), %.")
    def d(ctx):
        return (ctx.c / _ema_c(ctx, n) - 1) * 100


for _n in (9, 20, 50, 200):
    _ema_feats(_n)


def _atr(ctx, n):
    def make():
        idx, h, l, c = _hlc(ctx)
        return idx, wilder(true_range(h, l, c), n)
    return ctx.memo(("atr", n), make)


@feature("atr14_pct", "indicators", 1, OHLC, LB,
         "ATR(14), Wilder's smoothing of the 1-minute true range across sessions, % of the close.")
def atr14(ctx):
    idx, a = _atr(ctx, 14)
    return scatter(ctx.n, idx, a) / ctx.c * 100


def _supertrend(ctx, n=10, mult=3.0):
    def make():
        idx, h, l, c = _hlc(ctx)
        _, atr = _atr(ctx, n)
        k = len(c)
        hl2 = (h + l) / 2
        ub, lb = hl2 + mult * atr, hl2 - mult * atr
        fu, fl = ub.copy(), lb.copy()
        d = np.ones(k)
        st = np.full(k, np.nan)
        for i in range(k):
            if i == 0:
                d[i] = 1.0
            else:
                fu[i] = ub[i] if (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
                fl[i] = lb[i] if (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
                if d[i - 1] < 0 and c[i] > fu[i - 1]:
                    d[i] = 1.0
                elif d[i - 1] > 0 and c[i] < fl[i - 1]:
                    d[i] = -1.0
                else:
                    d[i] = d[i - 1]
            st[i] = fl[i] if d[i] > 0 else fu[i]
        warm = np.arange(k) >= n - 1
        return scatter(ctx.n, idx, np.where(warm, d, np.nan)), scatter(ctx.n, idx, np.where(warm, st, np.nan))
    return ctx.memo("supertrend", make)


@feature("supertrend_dir", "indicators", 1, OHLC, LB, "Supertrend(10, 3) direction on 1-minute bars: +1 up, -1 down.")
def st_dir(ctx):
    return _supertrend(ctx)[0]


@feature("supertrend_dist_pct", "indicators", 1, OHLC, LB, "Index close vs the Supertrend(10, 3) line, %.")
def st_dist(ctx):
    return (ctx.c / _supertrend(ctx)[1] - 1) * 100


@feature("rsi14", "indicators", 1, OHLC, LB, "RSI(14), Wilder, of the 1-minute close across sessions (0-100).")
def rsi14(ctx):
    idx, _, _, c = _hlc(ctx)
    if len(c) < 2:
        return np.full(ctx.n, np.nan)
    ch = np.diff(c)
    up, dn = wilder(np.clip(ch, 0, None), 14), wilder(np.clip(-ch, 0, None), 14)
    with np.errstate(invalid="ignore", divide="ignore"):
        r = np.where(dn > 0, 100 - 100 / (1 + up / dn), np.where(up > 0, 100.0, 50.0))
    r = np.r_[np.nan, np.where(np.arange(len(ch)) >= 13, r, np.nan)]
    return scatter(ctx.n, idx, r)


def _macd(ctx):
    def make():
        idx, _, _, c = _hlc(ctx)
        m = ema(c, span=12) - ema(c, span=26)
        s = ema(m, span=9)
        return scatter(ctx.n, idx, m), scatter(ctx.n, idx, s)
    return ctx.memo("macd", make)


@feature("macd_pct", "indicators", 1, OHLC, LB, "MACD(12, 26): EMA12 - EMA26 of the 1-minute close, % of the close.")
def macd(ctx):
    return _macd(ctx)[0] / ctx.c * 100


@feature("macd_signal_pct", "indicators", 1, OHLC, LB, "MACD signal line (EMA9 of MACD), % of the close.")
def macd_sig(ctx):
    return _macd(ctx)[1] / ctx.c * 100


@feature("macd_hist_pct", "indicators", 1, OHLC, LB, "MACD histogram (MACD - signal), % of the close.")
def macd_hist(ctx):
    m, s = _macd(ctx)
    return (m - s) / ctx.c * 100


def _adx(ctx, n=14):
    def make():
        idx, h, l, c = _hlc(ctx)
        if len(c) < 2:
            e = np.full(ctx.n, np.nan)
            return e, e, e
        up, dn = np.diff(h), -np.diff(l)
        pdm = np.where((up > dn) & (up > 0), up, 0.0)
        mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
        tr = true_range(h, l, c)[1:]
        a = wilder(tr, n)
        with np.errstate(invalid="ignore", divide="ignore"):
            pdi = np.where(a > 0, 100 * wilder(pdm, n) / a, np.nan)
            mdi = np.where(a > 0, 100 * wilder(mdm, n) / a, np.nan)
            dx = np.where(pdi + mdi > 0, 100 * np.abs(pdi - mdi) / (pdi + mdi), 0.0)
        adx = wilder(np.nan_to_num(dx), n)
        warm = np.arange(len(tr)) >= n - 1
        warm2 = np.arange(len(tr)) >= 2 * n - 2
        f = lambda x, w: scatter(ctx.n, idx, np.r_[np.nan, np.where(w, x, np.nan)])
        return f(adx, warm2), f(pdi, warm), f(mdi, warm)
    return ctx.memo("adx", make)


@feature("adx14", "indicators", 1, OHLC, LB, "ADX(14), Wilder, on 1-minute bars across sessions (0-100).")
def adx14(ctx):
    return _adx(ctx)[0]


@feature("di_plus14", "indicators", 1, OHLC, LB, "+DI(14), Wilder, 1-minute bars.")
def dip(ctx):
    return _adx(ctx)[1]


@feature("di_minus14", "indicators", 1, OHLC, LB, "-DI(14), Wilder, 1-minute bars.")
def dim(ctx):
    return _adx(ctx)[2]


def _bb(ctx, n=20, k=2.0):
    def make():
        idx, _, _, c = _hlc(ctx)
        m, sd = np.full(len(c), np.nan), np.full(len(c), np.nan)
        if len(c) >= n:
            # each window computed on its own (pandas' running sums carry rounding from wherever the data starts)
            w = np.lib.stride_tricks.sliding_window_view(c, n)
            m[n - 1:], sd[n - 1:] = w.mean(axis=1), w.std(axis=1)
        return scatter(ctx.n, idx, m), scatter(ctx.n, idx, sd)
    return ctx.memo("bb", make)


@feature("bb_pctb", "indicators", 1, OHLC, LB,
         "Bollinger %B (20, 2) on 1-minute closes: 0 at the lower band, 1 at the upper band.")
def bb_pctb(ctx):
    m, sd = _bb(ctx)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(sd > 0, (ctx.c - (m - 2 * sd)) / (4 * sd), np.nan)


@feature("bb_width_pct", "indicators", 1, OHLC, LB, "Bollinger band width (20, 2): (upper - lower) / middle, %.")
def bb_width(ctx):
    m, sd = _bb(ctx)
    return 4 * sd / m * 100


def _donchian(ctx, n=20):
    def make():
        idx, h, l, _ = _hlc(ctx)
        up = pd.Series(h).rolling(n, min_periods=n).max().values
        lo = pd.Series(l).rolling(n, min_periods=n).min().values
        return scatter(ctx.n, idx, up), scatter(ctx.n, idx, lo)
    return ctx.memo("donchian", make)


@feature("donchian20_pos", "indicators", 1, OHLC, LB,
         "Where the close sits in the Donchian(20) channel of 1-minute bars (incl. this one): 0 low, 1 high.")
def dc_pos(ctx):
    up, lo = _donchian(ctx)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(up > lo, (ctx.c - lo) / (up - lo), np.nan)


@feature("donchian20_width_pct", "indicators", 1, OHLC, LB, "Donchian(20) channel width, % of the close.")
def dc_width(ctx):
    up, lo = _donchian(ctx)
    return (up - lo) / ctx.c * 100


def _htf_trend(ctx, tf):
    b = bars(ctx, tf)
    if not len(b):
        return np.full(ctx.n, np.nan)
    e = ema(b.close.values, span=20)
    s = np.sign(np.r_[np.nan, np.diff(e)])
    s = np.where(np.arange(len(e)) >= 20, s, np.nan)              # 20 bars before the EMA means anything
    return to_rows(ctx, b, s)


@feature("trend_15m", "indicators", 1, OHLC, LB,
         "Sign of the slope of EMA(20) of 15-minute closes (last complete bar): +1 rising, -1 falling.")
def trend15(ctx):
    return _htf_trend(ctx, 15)


@feature("trend_1h", "indicators", 1, OHLC, "20d",
         "Sign of the slope of EMA(20) of session-anchored 60-minute closes (last complete bar).")
def trend1h(ctx):
    return _htf_trend(ctx, 60)


@feature("trend_1d", "indicators", 1, OHLC, f"{DAILY_WINDOW + 1}d",
         f"Sign of the slope of EMA({DAILY_EMA}) of daily closes up to the previous session (EMA run over the last "
         f"{DAILY_WINDOW} sessions, so it has a fixed memory).")
def trend1d(ctx):
    C = ctx.day_complete_agg(ctx.c, "last").reindex(range(len(ctx.days))).values
    nd = len(C)
    s = np.full(nd, np.nan)
    for d in range(DAILY_EMA + 1, nd):
        w = C[max(0, d - DAILY_WINDOW):d]
        w = w[np.isfinite(w)]
        if len(w) < DAILY_EMA + 1:
            continue
        e = ema(w, span=DAILY_EMA)
        s[d] = np.sign(e[-1] - e[-2])                              # closes of sessions before d only
    return s[ctx.dayid]
