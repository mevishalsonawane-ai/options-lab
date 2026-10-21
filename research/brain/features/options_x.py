"""Options extras: IV rank / percentile, Greeks at ATM and ATM±2, PCR by volume, expected move.

IV rank and percentile compare the current ATM IV with the ATM IV at the close of each of the previous 252 sessions
(only past sessions; NaN until 252 of them are loaded and at least 200 have an IV). They read the cube's per-minute
`atm_iv` (minutes table), so they do not need 252 sessions of the chain.

Greeks: research/brain/bs.py (Black-Scholes, the cube's r, q and expiry time) at the chain's strike for ATM, ATM-2
and ATM+2, with that strike's IV: the mean of its call and put IV that traded (each carried for up to 5 minutes within
the session). Spot is the index close; T runs from the end of the bar to 15:30 on the expiry date.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import bs, config
from .registry import feature

Z = -config.CHAIN_OFFSETS.start
RANK_N, RANK_MIN = 252, 200
MIN_T = 1 / (365 * 24)          # an hour: below this a per-day expected move from the straddle means nothing


def _ffill_day(ctx, a, limit):
    return pd.Series(a).groupby(ctx.dayid).ffill(limit=limit).values


def _atm_iv_min(ctx):
    """Cube minute ATM IV (%), carried for up to 5 minutes within the session."""
    return ctx.memo("atm_iv_min", lambda: _ffill_day(ctx, ctx.m.atm_iv.values.astype(float) * 100, 5)
                    if "atm_iv" in ctx.m else np.full(ctx.n, np.nan))


def _rank(ctx):
    def make():
        nd = len(ctx.days)
        daily = pd.Series(ctx.m.atm_iv.values.astype(float) * 100 if "atm_iv" in ctx.m else np.full(ctx.n, np.nan)
                          ).groupby(ctx.dayid).last().reindex(range(nd)).values
        cur = _atm_iv_min(ctx)
        rank, pct = np.full(ctx.n, np.nan), np.full(ctx.n, np.nan)
        for d in range(RANK_N, nd):
            w = daily[d - RANK_N:d]
            w = np.sort(w[np.isfinite(w)])
            if len(w) < RANK_MIN or w[-1] <= w[0]:
                continue
            r = np.flatnonzero(ctx.dayid == d)
            x = cur[r]
            rank[r] = (x - w[0]) / (w[-1] - w[0]) * 100
            pct[r] = np.where(np.isfinite(x), np.searchsorted(w, x, side="left") / len(w) * 100, np.nan)
        return rank, pct
    return ctx.memo("iv_rank", make)


@feature("iv_rank_252", "options", 1, ["minutes.atm_iv"], f"{RANK_N}d",
         "IV rank: (ATM IV now - lowest of the previous 252 session-close ATM IVs) / (highest - lowest), 0-100 "
         "(can leave 0-100 when IV makes a new extreme).")
def iv_rank(ctx):
    return _rank(ctx)[0]


@feature("iv_pct_252", "options", 1, ["minutes.atm_iv"], f"{RANK_N}d",
         "IV percentile: share of the previous 252 session-close ATM IVs below the ATM IV now, %.")
def iv_pct(ctx):
    return _rank(ctx)[1]


def _T(ctx):
    def make():
        h, m = config.EXPIRY_CLOSE
        end = pd.DatetimeIndex(pd.to_datetime(ctx.m.expiry).values) + pd.Timedelta(hours=h, minutes=m)
        T = (end - (ctx.ts + pd.Timedelta(minutes=1))).total_seconds().values / (365 * 86400)
        return np.where(T > 0, T, np.nan)
    return ctx.memo("T", make)


def _greeks(ctx, off):
    def make():
        K = ctx.chain_arr("strike", "C")[:, Z + off]
        K = np.where(np.isfinite(K), K, ctx.chain_arr("strike", "P")[:, Z + off])
        ivc = _ffill_day(ctx, ctx.chain_arr("iv", "C")[:, Z + off], 5)
        ivp = _ffill_day(ctx, ctx.chain_arr("iv", "P")[:, Z + off], 5)
        with np.errstate(invalid="ignore"):
            sig = np.where(np.isfinite(ivc) & np.isfinite(ivp), (ivc + ivp) / 2, np.where(np.isfinite(ivc), ivc, ivp))
        T = _T(ctx)
        dc, g, tc, v = bs.greeks(ctx.c, K, T, sig, True, config.RISK_FREE, config.DIV_YIELD)
        dp, _, tp, _ = bs.greeks(ctx.c, K, T, sig, False, config.RISK_FREE, config.DIV_YIELD)
        ok = np.isfinite(ctx.c) & np.isfinite(K) & np.isfinite(T) & np.isfinite(sig) & (sig > 0)
        return {k: np.where(ok, x, np.nan) for k, x in
                dict(delta_c=dc, delta_p=dp, theta_c=tc, theta_p=tp, gamma=g, vega=v).items()}
    return ctx.memo(("greeks", off), make)


GDESC = dict(delta_c="call delta", delta_p="put delta", theta_c="call theta (price per calendar day)",
             theta_p="put theta (price per calendar day)", gamma="gamma (per index point)", vega="vega (per IV point)")


def _greek_feats(off):
    tag = "atm" if off == 0 else f"atm{'p' if off > 0 else 'm'}{abs(off)}"
    where = "ATM" if off == 0 else f"ATM{off:+d}"
    for g, desc in GDESC.items():
        @feature(f"greek_{tag}_{g}", "options", 1, ["chain.iv", "chain.strike", "minutes.close", "minutes.expiry"],
                 "5m", f"Black-Scholes {desc} at the {where} strike, with that strike's IV (bs.py).")
        def f(ctx, g=g):
            return _greeks(ctx, off)[g]


for _o in (-2, 0, 2):
    _greek_feats(_o)


def _vol_side(ctx, side):
    a = ctx.chain_arr("volume", side)
    s = np.nansum(a, axis=1)
    return np.where(np.isfinite(a).any(axis=1), s, np.nan)


@feature("pcr_vol_day", "options", 1, ["chain.volume"], "day",
         "Put-call ratio of traded volume over ATM-10..ATM+10, summed over the session so far.")
def pcr_vol_day(ctx):
    p, c = (ctx.groupby_day(np.nan_to_num(_vol_side(ctx, s))).cumsum().values for s in ("P", "C"))
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(c > 0, p / c, np.nan)


@feature("pcr_vol_15m", "options", 1, ["chain.volume"], "15m",
         "Put-call ratio of traded volume over ATM-10..ATM+10 in the last 15 minutes of the session.")
def pcr_vol_15m(ctx):
    def roll(a):
        return ctx.groupby_day(np.nan_to_num(a)).rolling(15, min_periods=1).sum().reset_index(level=0, drop=True) \
            .sort_index().values
    p, c = roll(_vol_side(ctx, "P")), roll(_vol_side(ctx, "C"))
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(c > 0, p / c, np.nan)


def _straddle(ctx):
    return ctx.chain_arr("ltp", "C")[:, Z] + ctx.chain_arr("ltp", "P")[:, Z]


def _vix(ctx):
    v = ctx.m.vix.values.astype(float)
    return np.where(ctx.m.vix_flag.isin(["ok", "stale"]).values, v, np.nan)


@feature("em_straddle_1d_pct", "options", 1, ["chain.ltp", "minutes.close", "minutes.expiry"], "0m",
         "Expected 1-sigma move over one calendar day implied by the ATM straddle, % of the index: straddle / "
         "(index x sqrt(2/pi)) scaled from the time to expiry to one day. NaN within an hour of expiry.")
def em_straddle(ctx):
    T = _T(ctx)
    with np.errstate(invalid="ignore", divide="ignore"):
        s = _straddle(ctx) / ctx.c / np.sqrt(2 / np.pi) * np.sqrt((1 / 365) / T) * 100
    return np.where(T >= MIN_T, s, np.nan)


@feature("em_vix_1d_pct", "options", 1, ["minutes.vix"], "0m",
         "Expected 1-sigma move over one calendar day implied by India VIX: VIX / sqrt(365), %.")
def em_vix(ctx):
    return _vix(ctx) / np.sqrt(365)


@feature("em_vix_expiry_pct", "options", 1, ["minutes.vix", "minutes.expiry"], "0m",
         "Expected 1-sigma move from now to the expiry implied by India VIX: VIX x sqrt(T), % (compare straddle_pct, "
         "which is about 0.8 of the straddle's own 1-sigma move).")
def em_vix_exp(ctx):
    return _vix(ctx) * np.sqrt(_T(ctx))


@feature("em_straddle_vs_vix", "options", 1, ["chain.ltp", "minutes.close", "minutes.expiry", "minutes.vix"], "0m",
         "em_straddle_1d_pct / em_vix_1d_pct: >1 the chain prices more movement than VIX.")
def em_ratio(ctx):
    with np.errstate(invalid="ignore", divide="ignore"):
        return em_straddle(ctx) / em_vix(ctx)
