"""Derivatives: perp basis, premium index, funding, open interest, long/short ratios, liquidation proxies.

Funding here is the last SETTLED rate (cube: from the settlement's minute on). The premium-index average over the last
8 hours is the live estimate of the next rate (Binance's formula averages the premium over the interval, plus an
interest term clamped near 0.01%); it is a proxy, not the published prediction.
"""
from __future__ import annotations

import numpy as np

from .registry import feature


def _z(ctx, a, k, min_frac=0.8):
    m = ctx.roll(a, k, "mean", min_frac)
    s = ctx.roll(a, k, "std", min_frac)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(s > 0, (a - m) / s, np.nan)


@feature("basis_bps", "derivs", 1, ["minutes.basis_bps"], "1m", "Perp close vs spot close this minute, bps (both bars ok).")
def basis(ctx):
    return ctx.col("basis_bps")


@feature("basis_60m_bps", "derivs", 1, ["minutes.basis_bps"], "60m", "Mean perp-spot basis over the last 60 minutes, bps.")
def basis60(ctx):
    return ctx.memo("b60", lambda: ctx.roll(ctx.col("basis_bps"), 60, "mean", 0.5))


@feature("basis_z_7d", "derivs", 1, ["minutes.basis_bps"], "7d",
         "z-score of the 60-minute mean basis against the last 7 days of minutes.")
def basis_z(ctx):
    return _z(ctx, basis60(ctx), 7 * 1440, 0.7)


@feature("basis_chg_60m_bps", "derivs", 1, ["minutes.basis_bps"], "120m", "Change of the 60-minute mean basis over 60 minutes, bps.")
def basis_chg(ctx):
    b = basis60(ctx)
    return b - ctx.shift(b, 60)


@feature("premium_8h_bps", "derivs", 1, ["minutes.premium_close"], "480m",
         "Mean premium index (perp vs index) over the last 8 hours, bps: the live estimate of the next funding.")
def prem8h(ctx):
    return ctx.roll(ctx.col("premium_close") * 1e4, 480, "mean", 0.5)


@feature("mark_premium_bps", "derivs", 1, ["minutes.mark_premium_bps"], "1m", "Mark price vs index price, bps.")
def mark_prem(ctx):
    return ctx.col("mark_premium_bps")


@feature("funding_bps", "derivs", 1, ["minutes.funding_rate"], "540m", "Last settled funding rate, bps per 8 hours.")
def funding(ctx):
    return ctx.col("funding_rate") * 1e4


@feature("funding_ann_pct", "derivs", 1, ["minutes.funding_rate", "minutes.funding_interval_h"], "540m",
         "Last settled funding rate annualised (rate x settlements per year), %.")
def funding_ann(ctx):
    iv = ctx.col("funding_interval_h")
    with np.errstate(invalid="ignore", divide="ignore"):
        return ctx.col("funding_rate") * (24 / iv) * 365 * 100


@feature("funding_z_30d", "derivs", 1, ["minutes.funding_rate", "minutes.funding_age_min"], "31d",
         "z-score of the last settled funding rate against the settlements of the previous 30 days.")
def funding_z(ctx):
    def make():
        r = ctx.col("funding_rate")
        age = ctx.col("funding_age_min")
        ev = np.flatnonzero(age == 0)                    # the minute each settlement appears
        out = np.full(ctx.n, np.nan)
        if not len(ev):
            return out
        t_ev = ctx.ts[ev]
        vals = r[ev]
        z = np.full(len(ev), np.nan)
        for i in range(len(ev)):
            lo = np.searchsorted(t_ev.values, (t_ev[i] - np.timedelta64(30, "D")).to_datetime64(), side="left")
            w = vals[lo:i]                                # strictly earlier settlements in the last 30 days
            w = w[np.isfinite(w)]
            if len(w) >= 60 and w.std() > 0:
                z[i] = (vals[i] - w.mean()) / w.std()
        lastev = np.maximum.accumulate(np.where(age == 0, np.arange(ctx.n), -1))
        pos = np.searchsorted(ev, lastev)
        ok = (lastev >= 0) & np.isfinite(r)
        out[ok] = z[np.minimum(pos[ok], len(ev) - 1)]
        return out
    return ctx.memo("fz", make)


@feature("min_to_funding", "derivs", 1, ["minutes.min_to_next_funding"], "1m",
         "Minutes from this bar's end to the next funding settlement (00, 08, 16 UTC).")
def min_to_funding(ctx):
    return ctx.col("min_to_next_funding")


def _oi(ctx):
    return ctx.col("oi")


def _pct_chg(ctx, a, k):
    with np.errstate(invalid="ignore", divide="ignore"):
        return (a / ctx.shift(a, k) - 1) * 100


@feature("oi_chg_60m_pct", "derivs", 1, ["minutes.oi"], "75m", "Open interest change over 60 minutes, %.")
def oi_chg60(ctx):
    return ctx.memo("oi60", lambda: _pct_chg(ctx, _oi(ctx), 60))


@feature("oi_chg_24h_pct", "derivs", 1, ["minutes.oi"], "1455m", "Open interest change over 24 hours, %.")
def oi_chg24(ctx):
    return _pct_chg(ctx, _oi(ctx), 1440)


@feature("top_ls_positions", "derivs", 1, ["minutes.top_ls_positions"], "15m", "Top traders' long/short ratio by position size.")
def top_ls(ctx):
    return ctx.col("top_ls_positions")


@feature("ls_accounts_z_7d", "derivs", 1, ["minutes.ls_accounts"], "7d",
         "z-score of the all-accounts long/short ratio against the last 7 days of minutes.")
def ls_z(ctx):
    return _z(ctx, ctx.col("ls_accounts"), 7 * 1440, 0.7)


@feature("taker_ls_vol", "derivs", 1, ["minutes.taker_ls_vol"], "15m", "Perp taker buy / sell volume ratio of the last 5-minute snapshot.")
def taker_ls(ctx):
    return ctx.col("taker_ls_vol")


@feature("perp_taker_imb_60m", "derivs", 1, ["minutes.perp_taker_buy_volume", "minutes.perp_volume"], "60m",
         "Perp taker imbalance over 60 minutes: 2 x taker-buy share - 1 (-1 all sells, +1 all buys).")
def perp_imb(ctx):
    g = np.isfinite(ctx.pc)
    tb = np.where(g, ctx.col("perp_taker_buy_volume"), np.nan)
    v = np.where(g, ctx.col("perp_volume"), np.nan)
    with np.errstate(invalid="ignore", divide="ignore"):
        return 2 * ctx.roll(tb, 60, "sum") / ctx.roll(v, 60, "sum") - 1


@feature("liq_proxy_60m", "derivs", 1, ["minutes.oi", "minutes.close"], "75m",
         "Liquidation proxy: OI fall over 60 minutes (%, 0 if OI rose) x |60-minute return| (%). Large = a move that "
         "forced positions shut. A proxy: the archive has no liquidation prints.")
def liq_proxy(ctx):
    with np.errstate(invalid="ignore", divide="ignore"):
        r = np.abs(np.log(ctx.c / ctx.shift(ctx.c, 60)) * 100)
    return np.maximum(0, -oi_chg60(ctx)) * r


@feature("liq_flush_60m", "derivs", 1, ["minutes.oi", "minutes.close", "minutes.quote_volume"], "8d",
         "1 when, over the last 60 minutes, OI fell > 1%, price moved > 1% and volume was > 2x the usual for the hour.")
def liq_flush(ctx):
    from .volume import rvol_60m
    with np.errstate(invalid="ignore", divide="ignore"):
        r = np.abs(np.log(ctx.c / ctx.shift(ctx.c, 60)) * 100)
    oi = oi_chg60(ctx)
    rv = rvol_60m(ctx)
    ok = np.isfinite(oi) & np.isfinite(r) & np.isfinite(rv)
    return np.where(ok, ((oi < -1) & (r > 1) & (rv > 2)).astype(float), np.nan)
