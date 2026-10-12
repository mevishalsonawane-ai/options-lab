"""Options family, from the cube's chain (nearest expiry, ATM-10..ATM+10 around the index at that minute).

OI is the last OI the exchange reported at or before the minute; LTP is the last real trade at or before it; IV is
solved only on rows that traded that minute (cube flag "ok"). Short carry-forwards (ffill within the session, limit
in the description) use only earlier rows.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config
from .registry import feature

Z = -config.CHAIN_OFFSETS.start          # column of offset 0


def _ffill_day(ctx, a, limit):
    return pd.Series(a).groupby(ctx.dayid).ffill(limit=limit).values


def _sum(a, cols=slice(None)):
    x = a[:, cols]
    s = np.nansum(x, axis=1)
    return np.where(np.isfinite(x).any(axis=1), s, np.nan)


@feature("pcr_oi", "options", 1, ["chain.oi"], "0m", "Put-call ratio of open interest over ATM-10..ATM+10 (last reported OI).")
def pcr_oi(ctx):
    p, c = _sum(ctx.chain_arr("oi", "P")), _sum(ctx.chain_arr("oi", "C"))
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(c > 0, p / c, np.nan)


@feature("pcr_oi_near", "options", 1, ["chain.oi"], "0m", "Put-call OI ratio over ATM-3..ATM+3 only.")
def pcr_near(ctx):
    sl = slice(Z - 3, Z + 4)
    p, c = _sum(ctx.chain_arr("oi", "P"), sl), _sum(ctx.chain_arr("oi", "C"), sl)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(c > 0, p / c, np.nan)


def _build(ctx, side, w=5):
    sl = slice(Z - w, Z + w + 1)
    oi, chg = ctx.chain_arr("oi", side)[:, sl], ctx.chain_arr("oi_chg_day", side)[:, sl]
    base = oi - chg
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(_sum(base) > 0, _sum(chg) / _sum(base) * 100, np.nan)


@feature("oi_build_ce_pct", "options", 1, ["chain.oi", "chain.oi_chg_day"], "day",
         "Call OI build-up today over ATM-5..ATM+5: sum of OI change since each contract's first OI today / that "
         "first OI, %. Positive = writing/adding, negative = unwinding.")
def build_ce(ctx):
    return _build(ctx, "C")


@feature("oi_build_pe_pct", "options", 1, ["chain.oi", "chain.oi_chg_day"], "day",
         "Put OI build-up today over ATM-5..ATM+5, % (as oi_build_ce_pct).")
def build_pe(ctx):
    return _build(ctx, "P")


@feature("oi_net_build", "options", 1, ["chain.oi", "chain.oi_chg_day"], "day",
         "(put OI added today - call OI added today) / total OI over ATM-5..ATM+5: >0 puts being added faster.")
def net_build(ctx):
    sl = slice(Z - 5, Z + 6)
    cp, cc = _sum(ctx.chain_arr("oi_chg_day", "P"), sl), _sum(ctx.chain_arr("oi_chg_day", "C"), sl)
    tot = _sum(ctx.chain_arr("oi", "P"), sl) + _sum(ctx.chain_arr("oi", "C"), sl)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(tot > 0, (cp - cc) / tot, np.nan)


def _iv_at(ctx, side, off):
    return ctx.chain_arr("iv", side)[:, Z + off]


def _atm_iv(ctx):
    def make():
        a = np.vstack([_iv_at(ctx, "C", 0), _iv_at(ctx, "P", 0)])
        with np.errstate(invalid="ignore"):
            m = np.where(np.isfinite(a).any(axis=0), np.nanmean(np.where(np.isfinite(a), a, np.nan), axis=0), np.nan)
        return _ffill_day(ctx, m * 100, 5)
    return ctx.memo("atm_iv", make)


@feature("atm_iv", "options", 1, ["chain.iv"], "5m",
         "ATM implied volatility, % a year: mean of the ATM call and put IV that traded this minute; else the last "
         "value from up to 5 minutes earlier in the session.")
def atm_iv(ctx):
    import warnings
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        return _atm_iv(ctx)


@feature("atm_iv_chg_15m", "options", 1, ["chain.iv"], "20m", "Change in atm_iv over the last 15 minutes, IV points.")
def atm_iv_chg(ctx):
    with np.errstate(invalid="ignore"):
        a = atm_iv(ctx)
    return a - ctx.shift_in_day(a, 15)


@feature("iv_skew_3", "options", 1, ["chain.iv"], "5m",
         "Skew: IV of the put 3 strikes below ATM minus IV of the call 3 strikes above ATM, IV points (ffill <= 5 min).")
def skew(ctx):
    p = _ffill_day(ctx, _iv_at(ctx, "P", -3) * 100, 5)
    c = _ffill_day(ctx, _iv_at(ctx, "C", 3) * 100, 5)
    return p - c


@feature("iv_smile_3", "options", 1, ["chain.iv"], "5m",
         "Smile: mean IV of the 3-strike OTM put and call minus atm_iv, IV points.")
def smile(ctx):
    p = _ffill_day(ctx, _iv_at(ctx, "P", -3) * 100, 5)
    c = _ffill_day(ctx, _iv_at(ctx, "C", 3) * 100, 5)
    with np.errstate(invalid="ignore"):
        return (p + c) / 2 - atm_iv(ctx)


def _straddle(ctx):
    return ctx.chain_arr("ltp", "C")[:, Z] + ctx.chain_arr("ltp", "P")[:, Z]


@feature("straddle_pct", "options", 1, ["chain.ltp", "minutes.close"], "0m",
         "ATM straddle (call + put last traded price) as % of the index.")
def straddle(ctx):
    return _straddle(ctx) / ctx.c * 100


@feature("straddle_vs_0920", "options", 1, ["chain.ltp", "minutes.close"], "day",
         "ATM straddle % of index now / its value at 09:20, minus 1 (decay or expansion since the open).")
def straddle_decay(ctx):
    s = _straddle(ctx) / ctx.c
    ref = np.where(ctx.minute == 5, s, np.nan)
    ref = pd.Series(ref).groupby(ctx.dayid).ffill().values
    return s / ref - 1


@feature("max_pain_dist_pct", "options", 1, ["chain.oi", "chain.strike", "minutes.close"], "0m",
         "Max-pain strike over the chain's ATM-10..ATM+10 strikes (last reported OI) minus the close, % of the close.")
def max_pain(ctx):
    oc, op = ctx.chain_arr("oi", "C"), ctx.chain_arr("oi", "P")
    K = ctx.chain_arr("strike", "C")
    Kp = ctx.chain_arr("strike", "P")
    K = np.where(np.isfinite(K), K, Kp)
    occ, opp = np.nan_to_num(oc), np.nan_to_num(op)
    # pain at candidate X = sum_K oiC*max(X-K,0) + oiP*max(K-X,0); candidates are the same strikes
    X = K[:, :, None]
    Kk = K[:, None, :]
    pain = (np.nan_to_num(np.maximum(X - Kk, 0)) * occ[:, None, :]
            + np.nan_to_num(np.maximum(Kk - X, 0)) * opp[:, None, :]).sum(axis=2)
    pain = np.where(np.isfinite(K), pain, np.inf)
    valid = (np.isfinite(oc) | np.isfinite(op)).sum(axis=1) >= 10
    j = np.argmin(pain, axis=1)
    mp = K[np.arange(ctx.n), j]
    return np.where(valid, (mp / ctx.c - 1) * 100, np.nan)


@feature("chain_ok_share", "options", 1, ["minutes.chain_ok_share"], "0m",
         "Share of the 42 chain rows that traded this minute (data quality: low = stale/illiquid chain).")
def chain_ok(ctx):
    return ctx.m.chain_ok_share.values.astype(float)
