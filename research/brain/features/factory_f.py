"""F1 Strategy Factory additions (14 Oct 2026, Boss: "use gamma exposure too ... EMA, VWAP, OI and all other things").

Only what the brain did not have yet; the rest (EMA 9/20/50/200 levels and distances, VWAP and its bands, Supertrend,
RSI, MACD, ADX/DI, Bollinger, ATR, pivots, previous-day levels, opening ranges, inside day, PCR, max pain, ATM IV,
IV rank, skew, straddle, VIX, basis, calendar) is in the other family modules and is reused as it is.

- EMA crosses and slopes, the 5-minute versions of the chart indicators (EMA 9/21, Supertrend, RSI, MACD), the
  Keltner squeeze and its release, range expansion (ATR 5 vs 50, the day's range vs the last 10 sessions).
- CPR width and position, NR7.
- An option-volume-weighted VWAP of the index (the index has no volume and the futures minutes in the lake start in
  2026; the traded volume of the ATM-10..ATM+10 chain is the activity weight), and an equal-weight average anchored at
  the previous session's open.
- OI build-up type per strike over the last 15 minutes, on the SAME strike (the chain's offsets move with the index):
  long build-up (price up, OI up), short build-up (price down, OI up), short covering (price up, OI down), long
  unwinding (price down, OI down); per side for ATM and as shares of the OI change over ATM-5..ATM+5.
- PCR change, call/put OI walls (distance and shift since the open), straddle and skew change, futures OI build-up,
  VIX percentile.
- A gamma-exposure PROXY from the chain (Black-Scholes gamma x OI, dealers assumed long calls and short puts, the
  usual public convention). R28's GEX feature was not pushed when F1 ran; when it is, it replaces these two.

Every value at minute t uses cube rows with ts <= t only (tests/test_lookahead.py checks every registered feature).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config
from .bars import bars, compress, ema, scatter, to_rows, true_range, wilder
from .indicators import _bb, _ema_c, _hlc
from .registry import feature

OHLC = ["minutes.open", "minutes.high", "minutes.low", "minutes.close"]
LB = "10d"
Z = -config.CHAIN_OFFSETS.start
NOFF = len(config.CHAIN_OFFSETS)
BU_WIN = 15                              # build-up window, minutes
BU_NAMES = {1: "long build-up", 2: "short build-up", 3: "short covering", 4: "long unwinding"}


# ------------------------------------------------------------------------------------------------ EMA crosses, slopes
def _spread(a, b):
    def f(ctx):
        return (_ema_c(ctx, a) - _ema_c(ctx, b)) / ctx.c * 100
    return f


for _a, _b in ((9, 20), (20, 50), (50, 200)):
    feature(f"ema{_a}_{_b}_spread_pct", "indicators", 1, OHLC, LB,
            f"EMA({_a}) minus EMA({_b}) of the 1-minute close, % of the close (> 0: the fast EMA is above).")(_spread(_a, _b))


@feature("ema9_20_cross_age", "indicators", 1, OHLC, LB,
         "1-minute bars (with prices) since EMA(9) last crossed EMA(20), counting the crossing bar as 1, signed: + while EMA9 is above, - below; "
         "capped at 375.")
def cross_age(ctx):
    idx, _, _, c = _hlc(ctx)
    s = np.sign(ema(c, span=9) - ema(c, span=20))
    age = np.ones(len(s))                                          # 1 on the bar of the cross itself
    for i in range(1, len(s)):
        age[i] = age[i - 1] + 1 if s[i] == s[i - 1] else 1
    return scatter(ctx.n, idx, np.where(s != 0, s * np.minimum(age, 375), 0.0))


def _slope(n, k):
    def f(ctx):
        e = _ema_c(ctx, n)
        return (e - ctx.shift_in_day(e, k)) / ctx.c * 100
    return f


for _n, _k in ((20, 5), (50, 15)):
    feature(f"ema{_n}_slope_{_k}m_pct", "indicators", 1, OHLC, LB,
            f"Change of EMA({_n}) over the last {_k} minutes (same session), % of the close.")(_slope(_n, _k))


@feature("di_spread14", "indicators", 1, OHLC, LB, "+DI(14) minus -DI(14) on 1-minute bars (> 0: up-moves dominate).")
def di_spread(ctx):
    from .indicators import _adx
    _, p, m = _adx(ctx)
    return p - m


# ------------------------------------------------------------------------------------------------ 5-minute indicators
def _supertrend_arr(h, l, c, n=10, mult=3.0):
    k = len(c)
    if k == 0:
        return np.zeros(0)
    atr = wilder(true_range(h, l, c), n)
    hl2 = (h + l) / 2
    ub, lb = hl2 + mult * atr, hl2 - mult * atr
    fu, fl = ub.copy(), lb.copy()
    d = np.ones(k)
    for i in range(1, k):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
        if d[i - 1] < 0 and c[i] > fu[i - 1]:
            d[i] = 1.0
        elif d[i - 1] > 0 and c[i] < fl[i - 1]:
            d[i] = -1.0
        else:
            d[i] = d[i - 1]
    return np.where(np.arange(k) >= n - 1, d, np.nan)


def _rsi_arr(c, n=14):
    if len(c) < 2:
        return np.full(len(c), np.nan)
    ch = np.diff(c)
    up, dn = wilder(np.clip(ch, 0, None), n), wilder(np.clip(-ch, 0, None), n)
    with np.errstate(invalid="ignore", divide="ignore"):
        r = np.where(dn > 0, 100 - 100 / (1 + up / dn), np.where(up > 0, 100.0, 50.0))
    return np.r_[np.nan, np.where(np.arange(len(ch)) >= n - 1, r, np.nan)]


def _b5(ctx):
    return bars(ctx, 5)


@feature("c5_ema9_21_spread_pct", "indicators", 1, OHLC, LB,
         "5-minute bars (session-anchored, continuous across sessions): EMA(9) minus EMA(21) of the closes, % of the "
         "last complete bar's close.")
def c5_ema(ctx):
    b = _b5(ctx)
    c = b.close.values
    return to_rows(ctx, b, (ema(c, span=9) - ema(c, span=21)) / c * 100 if len(c) else c)


@feature("c5_supertrend_dir", "indicators", 1, OHLC, LB,
         "Supertrend(10, 3) direction on 5-minute bars (last complete bar): +1 up, -1 down.")
def c5_st(ctx):
    b = _b5(ctx)
    return to_rows(ctx, b, _supertrend_arr(b.high.values, b.low.values, b.close.values))


@feature("c5_rsi14", "indicators", 1, OHLC, LB, "RSI(14), Wilder, of 5-minute closes (last complete bar).")
def c5_rsi(ctx):
    b = _b5(ctx)
    return to_rows(ctx, b, _rsi_arr(b.close.values))


@feature("c5_macd_hist_pct", "indicators", 1, OHLC, LB,
         "MACD(12, 26, 9) histogram of 5-minute closes, % of the close (last complete bar).")
def c5_macd(ctx):
    b = _b5(ctx)
    c = b.close.values
    if not len(c):
        return np.full(ctx.n, np.nan)
    m = ema(c, span=12) - ema(c, span=26)
    return to_rows(ctx, b, (m - ema(m, span=9)) / c * 100)


# ------------------------------------------------------------------------------------------------ squeeze, expansion
def _kc(ctx, n=20, mult=1.5):
    def make():
        idx, h, l, c = _hlc(ctx)
        mid = ema(c, span=n)
        atr = wilder(true_range(h, l, c), n)
        return scatter(ctx.n, idx, mid), scatter(ctx.n, idx, mult * atr)
    return ctx.memo(("kc", n, mult), make)


def _squeeze(ctx):
    def make():
        m, sd = _bb(ctx)
        mid, w = _kc(ctx)
        on = np.where(np.isfinite(sd) & np.isfinite(w), ((m + 2 * sd) < (mid + w)) & ((m - 2 * sd) > (mid - w)), False)
        return on, np.isfinite(sd) & np.isfinite(w)
    return ctx.memo("squeeze", make)


@feature("kc_pos", "indicators", 1, OHLC, LB,
         "Where the close sits in the Keltner channel (EMA20 +- 1.5 x ATR20, 1-minute): 0 lower, 1 upper.")
def kc_pos(ctx):
    mid, w = _kc(ctx)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(w > 0, (ctx.c - (mid - w)) / (2 * w), np.nan)


@feature("squeeze_on", "indicators", 1, OHLC, LB,
         "1 when the Bollinger bands (20, 2) sit inside the Keltner channel (20, 1.5) on 1-minute bars (TTM squeeze).")
def squeeze_on(ctx):
    on, ok = _squeeze(ctx)
    return np.where(ok, on.astype(float), np.nan)


@feature("squeeze_run", "indicators", 1, OHLC, LB,
         "How many 1-minute bars in a row (with prices) the squeeze has been on, 0 when off; capped at 375.")
def squeeze_run(ctx):
    on, ok = _squeeze(ctx)
    idx = np.flatnonzero(ok)
    r = np.zeros(len(idx))
    o = on[idx]
    for i in range(len(idx)):
        r[i] = (r[i - 1] + 1 if i else 1) if o[i] else 0
    return scatter(ctx.n, idx, np.minimum(r, 375))


@feature("c5_squeeze_fire", "indicators", 1, OHLC, LB,
         "5-minute squeeze release: +1 (-1) when the squeeze (BB 20,2 inside KC 20,1.5 of 5-minute bars) was on at one "
         "of the 3 bars before the last complete bar and is off now with the close above (below) the EMA20; else 0.")
def c5_fire(ctx):
    b = _b5(ctx)
    k = len(b)
    if k < 21:
        return to_rows(ctx, b, np.full(k, np.nan))
    h, l, c = b.high.values, b.low.values, b.close.values
    w = np.lib.stride_tricks.sliding_window_view(c, 20)
    m, sd = np.full(k, np.nan), np.full(k, np.nan)
    m[19:], sd[19:] = w.mean(axis=1), w.std(axis=1)
    mid, atr = ema(c, span=20), 1.5 * wilder(true_range(h, l, c), 20)
    on = ((m + 2 * sd) < (mid + atr)) & ((m - 2 * sd) > (mid - atr))
    out = np.zeros(k)
    for i in range(23, k):
        if not on[i] and on[i - 3:i].any():
            out[i] = 1.0 if c[i] > mid[i] else -1.0
    out[:23] = np.nan
    return to_rows(ctx, b, out)


@feature("atr_ratio_5_50", "indicators", 1, OHLC, LB,
         "Range expansion: Wilder ATR(5) / ATR(50) of 1-minute bars (> 1: bars are getting bigger).")
def atr_ratio(ctx):
    idx, h, l, c = _hlc(ctx)
    tr = true_range(h, l, c)
    with np.errstate(invalid="ignore", divide="ignore"):
        return scatter(ctx.n, idx, wilder(tr, 5) / wilder(tr, 50))


@feature("day_range_vs_adr10", "price", 1, OHLC, "11d",
         "The session's high-low range so far / the mean full high-low range of the previous 10 sessions (needs 8).")
def range_vs_adr(ctx):
    hi = ctx.groupby_day(ctx.h).cummax().values
    lo = ctx.groupby_day(ctx.l).cummin().values
    full = ctx.day_complete_agg(ctx.h, "max") - ctx.day_complete_agg(ctx.l, "min")
    adr = full.rolling(10, min_periods=8).mean()
    return (hi - lo) / ctx.prev_session(adr)


# ------------------------------------------------------------------------------------------------ CPR, NR7
def _cpr(ctx):
    def make():
        H = ctx.day_complete_agg(ctx.h, "max")
        L = ctx.day_complete_agg(ctx.l, "min")
        C = ctx.day_complete_agg(ctx.c, "last")
        p = (H + L + C) / 3
        bc = (H + L) / 2
        tc = 2 * p - bc
        return ctx.prev_session(p), ctx.prev_session(np.maximum(tc, bc)), ctx.prev_session(np.minimum(tc, bc))
    return ctx.memo("cpr", make)


@feature("cpr_width_pct", "structure", 1, OHLC, "1d",
         "Width of today's central pivot range (TC - BC from the previous session's H, L, C), % of the pivot.")
def cpr_width(ctx):
    p, top, bot = _cpr(ctx)
    return (top - bot) / p * 100


@feature("cpr_state", "structure", 1, OHLC, "1d",
         "+1 close above the CPR's top, -1 below its bottom, 0 inside.")
def cpr_state(ctx):
    _, top, bot = _cpr(ctx)
    out = np.where(ctx.c > top, 1.0, np.where(ctx.c < bot, -1.0, 0.0))
    return np.where(np.isfinite(ctx.c) & np.isfinite(top), out, np.nan)


@feature("prev_nr7", "structure", 1, OHLC, "8d",
         "1 if the previous session's high-low range was the narrowest of the last 7 sessions (NR7), else 0.")
def nr7(ctx):
    rng = ctx.day_complete_agg(ctx.h, "max") - ctx.day_complete_agg(ctx.l, "min")
    mn = rng.rolling(7, min_periods=7).min()
    v = (rng <= mn).astype(float)
    v[mn.isna()] = np.nan
    return ctx.prev_session(v)


# ------------------------------------------------------------------------------------------------ VWAP proxies
def _ovwap(ctx):
    def make():
        vol = np.nansum(np.nan_to_num(ctx.chain_arr("volume", "C")) + np.nan_to_num(ctx.chain_arr("volume", "P")), axis=1)
        tp = (ctx.h + ctx.l + ctx.c) / 3
        ok = np.isfinite(tp) & (vol > 0)
        w = np.where(ok, vol, 0.0)
        x = np.where(ok, tp, 0.0)
        g = ctx.groupby_day
        sw = g(w).cumsum().values
        swx = g(w * x).cumsum().values
        swx2 = g(w * x * x).cumsum().values
        with np.errstate(invalid="ignore", divide="ignore"):
            vw = np.where(sw > 0, swx / sw, np.nan)
            sd = np.sqrt(np.maximum(np.where(sw > 0, swx2 / sw, np.nan) - vw ** 2, 0))
        return vw, sd
    return ctx.memo("ovwap", make)


@feature("ovwap_dist_pct", "price", 1, OHLC + ["chain.volume"], "day",
         "Index close vs its session VWAP weighted by the option chain's traded volume (ATM-10..ATM+10, calls + puts) "
         "each minute, %. The index has no volume of its own.")
def ovwap_dist(ctx):
    vw, _ = _ovwap(ctx)
    return (ctx.c / vw - 1) * 100


@feature("ovwap_z", "price", 1, OHLC + ["chain.volume"], "day",
         "Index close minus the option-volume VWAP in band units (weighted standard deviation of the typical price): "
         "+1 / +2 are the +1 / +2 sigma bands. NaN while sigma is 0.")
def ovwap_z(ctx):
    vw, sd = _ovwap(ctx)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(sd > 0, (ctx.c - vw) / sd, np.nan)


@feature("tpavg_2d_dist_pct", "price", 1, OHLC, "1d",
         "Close vs the equal-weight mean typical price anchored at the previous session's open (that session + today so "
         "far), %. An anchored-VWAP proxy for the index, which has no volume.")
def tpavg_2d(ctx):
    tp = (ctx.h + ctx.l + ctx.c) / 3
    ok = np.isfinite(tp)
    s = ctx.groupby_day(np.where(ok, tp, 0.0)).cumsum().values
    n = ctx.groupby_day(ok.astype(float)).cumsum().values
    S = ctx.prev_session(ctx.day_complete_agg(np.where(ok, tp, 0.0), "sum"))
    N = ctx.prev_session(ctx.day_complete_agg(ok.astype(float), "sum"))
    with np.errstate(invalid="ignore", divide="ignore"):
        avg = np.where((N > 0) & (n + N > 0), (s + S) / (n + N), np.nan)
    return (ctx.c / avg - 1) * 100


# ------------------------------------------------------------------------------------------------ OI build-up types
def _atm_strike(ctx):
    def make():
        k = ctx.chain_arr("strike", "C")[:, Z]
        return np.where(np.isfinite(k), k, ctx.chain_arr("strike", "P")[:, Z])
    return ctx.memo("atm_k", make)


def same_strike_lag(ctx, col: str, side: str, k: int) -> np.ndarray:
    """n x 21: the value of `col` k minutes earlier (same session) for the strike that sits at each offset NOW."""
    def make():
        a = ctx.chain_arr(col, side)
        atm = _atm_strike(ctx)
        step = config.STRIKE_STEP.get(ctx.und, 50.0)
        out = np.full_like(a, np.nan)
        if ctx.n <= k:
            return out
        prev_atm = ctx.shift_in_day(atm, k)
        sh = np.rint((atm - prev_atm) / step)                    # offsets the ATM moved by since t-k
        rows = np.flatnonzero(np.isfinite(sh))
        rows = rows[rows >= k]
        for o in range(NOFF):
            src = o + sh[rows]
            ok = (src >= 0) & (src < NOFF)
            r = rows[ok]
            out[r, o] = a[r - k, src[ok].astype(int)]
        return out
    return ctx.memo(("lag", col, side, k), make)


def _ltp_ff(ctx, side):
    return ctx.memo(("ltpff", side), lambda: ctx.chain_arr("ltp", side))


def buildup_codes(dp: np.ndarray, doi: np.ndarray) -> np.ndarray:
    """1 long build-up (dp > 0, doi > 0), 2 short build-up (dp < 0, doi > 0), 3 short covering (dp > 0, doi < 0),
    4 long unwinding (dp < 0, doi < 0); 0 when either did not move; NaN when either is unknown."""
    code = np.where(dp > 0, np.where(doi > 0, 1.0, np.where(doi < 0, 3.0, 0.0)),
                    np.where(dp < 0, np.where(doi > 0, 2.0, np.where(doi < 0, 4.0, 0.0)), 0.0))
    return np.where(np.isfinite(dp) & np.isfinite(doi), code, np.nan)


def _bu(ctx, side):
    def make():
        oi, p = ctx.chain_arr("oi", side), _ltp_ff(ctx, side)
        doi = oi - same_strike_lag(ctx, "oi", side, BU_WIN)
        dp = p - same_strike_lag(ctx, "ltp", side, BU_WIN)
        return buildup_codes(dp, doi), doi
    return ctx.memo(("bu", side), make)


for _s, _sn in (("C", "ce"), ("P", "pe")):
    def _mk_atm(s):
        def f(ctx):
            return _bu(ctx, s)[0][:, Z]
        return f
    feature(f"bu_atm_{_sn}_15m", "options", 1, ["chain.oi", "chain.ltp", "chain.strike"], "20m",
            f"OI build-up type of the ATM {'call' if _s == 'C' else 'put'} over the last 15 minutes (same strike): "
            "1 long build-up, 2 short build-up, 3 short covering, 4 long unwinding, 0 no change.")(_mk_atm(_s))
    for _code, _nm in BU_NAMES.items():
        def _mk_share(s, code):
            def f(ctx):
                c, doi = _bu(ctx, s)
                c, a = c[:, Z - 5:Z + 6], np.abs(doi[:, Z - 5:Z + 6])
                tot = np.nansum(np.where(np.isfinite(c), a, 0), axis=1)
                part = np.nansum(np.where(c == code, a, 0), axis=1)
                return np.where(tot > 0, part / np.where(tot > 0, tot, 1), np.nan)
            return f
        feature(f"bu_{_sn}_{_nm.replace(' ', '_').replace('-', '')}_share", "options", 1,
                ["chain.oi", "chain.ltp", "chain.strike"], "20m",
                f"Share of the {'call' if _s == 'C' else 'put'} OI change of the last 15 minutes over ATM-5..ATM+5 "
                f"(same strikes) that is {_nm}.")(_mk_share(_s, _code))


@feature("bu_writing_bias_15m", "options", 1, ["chain.oi", "chain.ltp", "chain.strike"], "20m",
         "(put short build-up OI - call short build-up OI) / all |OI change| over ATM-5..ATM+5 in the last 15 minutes: "
         "> 0 puts being written (read as support), < 0 calls being written.")
def writing_bias(ctx):
    cc, dc = _bu(ctx, "C")
    cp, dp = _bu(ctx, "P")
    sl = slice(Z - 5, Z + 6)
    tot = np.nansum(np.abs(np.nan_to_num(dc[:, sl])), axis=1) + np.nansum(np.abs(np.nan_to_num(dp[:, sl])), axis=1)
    pw = np.nansum(np.where(cp[:, sl] == 2, dp[:, sl], 0), axis=1)
    cw = np.nansum(np.where(cc[:, sl] == 2, dc[:, sl], 0), axis=1)
    return np.where(tot > 0, (pw - cw) / np.where(tot > 0, tot, 1), np.nan)


# ------------------------------------------------------------------------------------------------ PCR, walls, straddle
def _pcr(ctx):
    from .options import pcr_oi
    return ctx.memo("pcr", lambda: pcr_oi(ctx))


@feature("pcr_oi_chg_15m", "options", 1, ["chain.oi"], "15m", "pcr_oi now minus 15 minutes earlier (same session).")
def pcr_chg15(ctx):
    p = _pcr(ctx)
    return p - ctx.shift_in_day(p, 15)


@feature("pcr_oi_chg_day", "options", 1, ["chain.oi"], "day", "pcr_oi now minus its first value of the session.")
def pcr_chgday(ctx):
    p = _pcr(ctx)
    return p - ctx.day_first(p)


def _wall(ctx, col):
    return ctx.m[col].values.astype(float) if col in ctx.m else np.full(ctx.n, np.nan)


for _side, _col in (("call", "oi_max_call_strike"), ("put", "oi_max_put_strike")):
    def _mk_wd(col):
        def f(ctx):
            return (_wall(ctx, col) / ctx.c - 1) * 100
        return f

    def _mk_ws(col):
        def f(ctx):
            w = _wall(ctx, col)
            return (w - ctx.day_first(w)) / ctx.c * 100
        return f
    feature(f"oi_{_side}_wall_dist_pct", "options", 1, [f"minutes.{_col}"] + OHLC, "0m",
            f"The {_side} OI wall (strike with the most {_side} OI over ATM-10..ATM+10) vs the close, %.")(_mk_wd(_col))
    feature(f"oi_{_side}_wall_shift_day", "options", 1, [f"minutes.{_col}"] + OHLC, "day",
            f"How far the {_side} OI wall has moved since its first value today, % of the close (+ = moved up).")(_mk_ws(_col))


@feature("straddle_chg_15m_pct", "options", 1, ["chain.ltp", "minutes.close"], "15m",
         "ATM straddle (% of the index) now vs 15 minutes earlier, % change (decay < 0, expansion > 0).")
def strad15(ctx):
    from .options import _straddle
    s = _straddle(ctx) / ctx.c
    return (s / ctx.shift_in_day(s, 15) - 1) * 100


@feature("iv_skew_chg_15m", "options", 1, ["chain.iv"], "20m", "iv_skew_3 now minus 15 minutes earlier, IV points.")
def skew15(ctx):
    from .options import skew
    s = ctx.memo("skew", lambda: skew(ctx))
    return s - ctx.shift_in_day(s, 15)


# ------------------------------------------------------------------------------------------------ GEX proxy
def _gex(ctx):
    def make():
        def g(side):
            x = pd.DataFrame(ctx.chain_arr("gamma", side)).groupby(ctx.dayid).ffill(limit=5).values
            return x * np.nan_to_num(ctx.chain_arr("oi", side))
        gc, gp = g("C"), g("P")
        ok = np.isfinite(gc) | np.isfinite(gp)
        gc, gp = np.nan_to_num(gc), np.nan_to_num(gp)
        net = (gc - gp).sum(axis=1)
        tot = (gc + gp).sum(axis=1)
        near = (gc + gp)[:, Z - 2:Z + 3].sum(axis=1)
        enough = ok.sum(axis=1) >= 10                             # as max pain: 10 strikes (of 42 rows) known
        with np.errstate(invalid="ignore", divide="ignore"):
            return (np.where(enough & (tot > 0), net / tot, np.nan), np.where(enough & (tot > 0), near / tot, np.nan))
    return ctx.memo("gex", make)


@feature("gex_net_norm", "options", 1, ["chain.gamma", "chain.oi"], "5m",
         "Gamma-exposure PROXY: (sum call gamma x OI - sum put gamma x OI) / (sum of both) over ATM-10..ATM+10, gamma "
         "carried <= 5 min. Dealers assumed long calls / short puts: > 0 dealers long gamma (moves damped), < 0 short "
         "gamma (moves amplified). Needs gamma on 10 of the 42 rows.")
def gex_net(ctx):
    return _gex(ctx)[0]


@feature("gex_near_share", "options", 1, ["chain.gamma", "chain.oi"], "5m",
         "Share of the chain's gamma x OI that sits at ATM-2..ATM+2 (gamma concentrated near the money: pinning).")
def gex_near(ctx):
    return _gex(ctx)[1]


# ------------------------------------------------------------------------------------------------ futures, VIX
def _fut_oi(ctx):
    m = ctx.m
    if "fut_oi" not in m:
        return np.full(ctx.n, np.nan)
    ok = m.fut_flag.isin(["ok", "stale"]).values
    return np.where(ok, m.fut_oi.values.astype(float), np.nan)


@feature("fut_oi_chg_day_pct", "market", 1, ["minutes.fut_oi", "minutes.fut_flag"], "day",
         "Near-month futures OI now vs its first value today, % (lake futures minutes start in 2026; NaN before).")
def fut_oi_day(ctx):
    o = _fut_oi(ctx)
    return (o / ctx.day_first(o) - 1) * 100


@feature("fut_buildup_15m", "market", 1, ["minutes.fut_close", "minutes.fut_oi", "minutes.fut_flag"], "15m",
         "Futures OI build-up type over the last 15 minutes: 1 long build-up, 2 short build-up, 3 short covering, "
         "4 long unwinding, 0 no change.")
def fut_bu(ctx):
    o = _fut_oi(ctx)
    c = ctx.fut()["c"]
    return buildup_codes(c - ctx.shift_in_day(c, 15), o - ctx.shift_in_day(o, 15))


@feature("vix_pct_252", "market", 1, ["minutes.vix"], "252d",
         "India VIX percentile: share of the previous 252 session-close VIX values below VIX now, % (needs 200).")
def vix_pct(ctx):
    from .market import _vix
    v = _vix(ctx)
    daily = ctx.day_complete_agg(v, "last").reindex(range(len(ctx.days))).values
    out = np.full(ctx.n, np.nan)
    for d in range(200, len(daily)):
        w = daily[max(0, d - 252):d]
        w = np.sort(w[np.isfinite(w)])
        if len(w) < 200:
            continue
        r = ctx.dayid == d
        x = v[r]
        out[r] = np.where(np.isfinite(x), np.searchsorted(w, x, side="left") / len(w) * 100, np.nan)
    return out
