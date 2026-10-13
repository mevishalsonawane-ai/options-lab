"""Volume, VWAP and the session-free volume profile (rolling 24 hours). Spot volume, precision "estimate" (1-minute
bars; exact values would need trades)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from ... import profile as vp
from .registry import feature


def _qv(ctx):
    return ctx.memo("qv", lambda: np.where(np.isfinite(ctx.c), ctx.col("quote_volume"), np.nan))


@feature("qvol_60m_log", "volume", 1, ["minutes.quote_volume"], "60m", "log10 of spot USD volume in the last 60 minutes.")
def qvol_60m(ctx):
    with np.errstate(divide="ignore"):
        return np.log10(ctx.roll(_qv(ctx), 60, "sum"))


@feature("rvol_60m", "volume", 1, ["minutes.quote_volume"], "8d",
         "Relative volume: last 60 minutes' USD volume / the average of the same clock hour on the previous 7 days.")
def rvol_60m(ctx):
    v = ctx.roll(_qv(ctx), 60, "sum")
    acc, cnt = np.zeros(ctx.n), np.zeros(ctx.n)
    for k in range(1, 8):
        x = ctx.shift(v, 1440 * k)
        acc += np.nan_to_num(x)
        cnt += np.isfinite(x)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(cnt >= 5, v / (acc / np.maximum(cnt, 1)), np.nan)


@feature("taker_buy_share_60m", "volume", 1, ["minutes.taker_buy_volume", "minutes.volume"], "60m",
         "Spot taker-buy volume / all volume over the last 60 minutes (0.5 = balanced).")
def taker_share(ctx):
    tb = np.where(np.isfinite(ctx.c), ctx.col("taker_buy_volume"), np.nan)
    with np.errstate(invalid="ignore", divide="ignore"):
        return ctx.roll(tb, 60, "sum") / ctx.roll(ctx.v, 60, "sum")


def _vwap_roll(ctx, k):
    def make():
        tp = (ctx.h + ctx.l + ctx.c) / 3
        v = ctx.v
        pv = ctx.roll(tp * v, k, "sum")
        vv = ctx.roll(v, k, "sum")
        p2 = ctx.roll(tp * tp * v, k, "sum")
        with np.errstate(invalid="ignore", divide="ignore"):
            w = pv / vv
            sd = np.sqrt(np.maximum(p2 / vv - w * w, 0))
        return w, sd
    return ctx.memo(("vwap", k), make)


@feature("vwap24_dist_pct", "volume", 1, ["minutes.high", "minutes.low", "minutes.close", "minutes.volume"], "1440m",
         "Close vs the rolling 24-hour VWAP (typical price weighted by volume), %.")
def vwap24_dist(ctx):
    w, _ = _vwap_roll(ctx, 1440)
    with np.errstate(invalid="ignore", divide="ignore"):
        return (ctx.c / w - 1) * 100


@feature("vwap24_z", "volume", 1, ["minutes.high", "minutes.low", "minutes.close", "minutes.volume"], "1440m",
         "(close - 24-hour VWAP) / the volume-weighted standard deviation of price over the same 24 hours.")
def vwap24_z(ctx):
    w, sd = _vwap_roll(ctx, 1440)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(sd > 0, (ctx.c - w) / sd, np.nan)


@feature("vwap_day_dist_pct", "volume", 1, ["minutes.high", "minutes.low", "minutes.close", "minutes.volume"], "day",
         "Close vs the VWAP anchored at the analysis day's start, %.")
def vwap_day(ctx):
    tp = (ctx.h + ctx.l + ctx.c) / 3
    v = ctx.v
    ok = np.isfinite(tp) & np.isfinite(v)
    pv = ctx.day_cum(np.where(ok, tp * v, 0), "sum")
    vv = ctx.day_cum(np.where(ok, v, 0), "sum")
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(vv > 0, (ctx.c / (pv / vv) - 1) * 100, np.nan)


# --- rolling 24-hour volume profile ------------------------------------------------------------------------------------
# Recomputed at each clock hour's last minute from the 24 clock hours ending there (1440 bars), with
# research/brain/profile.py's maths: each bar's volume spread over its high-low into absolute USD bins, POC, 70% value
# area. Each hour's histogram is built once and the last 24 are summed. A minute uses the latest profile at or before it
# (carried at most 59 minutes), so the value is known. There is no session, so there is no "developing" vs "final"
# profile: always the last 24 hours.
def _vp24(ctx):
    def make():
        bs = ctx.spec.bin if ctx.spec else 1.0
        poc, vah, val = (np.full(ctx.n, np.nan) for _ in range(3))
        hour = ctx.ts.floor("h")
        hid, hours = pd.factorize(hour, sort=True)
        blocks = {}
        for k in range(len(hours)):
            rows = np.flatnonzero(hid == k)
            b0, H = vp.spread(ctx.l[rows], ctx.h[rows], ctx.c[rows], ctx.v[rows], bs)
            blocks[hours[k]] = (b0, H.sum(axis=0), int(np.isfinite(ctx.c[rows]).sum()), rows[-1])
        for k in range(23, len(hours)):
            e_hour = hours[k]
            if ctx.ts[blocks[e_hour][3]].minute != 59:
                continue                                    # the hour is not complete in the data
            win = [blocks.get(e_hour - pd.Timedelta(hours=j)) for j in range(24)]
            if any(w is None for w in win) or sum(w[2] for w in win) < 0.8 * 1440:
                continue
            win = [w for w in win if len(w[1])]
            if not win:
                continue
            lo = min(w[0] for w in win)
            hi = max(w[0] + len(w[1]) for w in win)
            hist = np.zeros(hi - lo)
            for b0, hst, _, _ in win:
                hist[b0 - lo:b0 - lo + len(hst)] += hst
            p, a, b = (x[0] for x in vp.poc_va(hist[None, :]))
            if p < 0:
                continue
            e = blocks[e_hour][3]
            poc[e], val[e], vah[e] = (lo + p + 0.5) * bs, (lo + a) * bs, (lo + b + 1) * bs
        out = []
        for x in (poc, vah, val):           # carry the hourly value to the next 59 minutes, never further
            idx = np.where(np.isfinite(x), np.arange(ctx.n), -1)
            last = np.maximum.accumulate(idx)
            y = np.where((last >= 0) & (np.arange(ctx.n) - last < 60), x[np.maximum(last, 0)], np.nan)
            out.append(y)
        return tuple(out)
    return ctx.memo("vp24", make)


def _dist(ctx, lvl):
    with np.errstate(invalid="ignore", divide="ignore"):
        return (ctx.c / lvl - 1) * 100


@feature("vp24_poc_dist_pct", "profile", 1, ["minutes.high", "minutes.low", "minutes.close", "minutes.volume"],
         "1500m", "Close vs the rolling 24-hour volume profile's POC (last full hour's profile), %.")
def vp24_poc(ctx):
    return _dist(ctx, _vp24(ctx)[0])


@feature("vp24_vah_dist_pct", "profile", 1, ["minutes.high", "minutes.low", "minutes.close", "minutes.volume"],
         "1500m", "Close vs the 24-hour value-area high (70%), %.")
def vp24_vah(ctx):
    return _dist(ctx, _vp24(ctx)[1])


@feature("vp24_val_dist_pct", "profile", 1, ["minutes.high", "minutes.low", "minutes.close", "minutes.volume"],
         "1500m", "Close vs the 24-hour value-area low (70%), %.")
def vp24_val(ctx):
    return _dist(ctx, _vp24(ctx)[2])


@feature("vp24_state", "profile", 1, ["minutes.high", "minutes.low", "minutes.close", "minutes.volume"], "1500m",
         "Close above the 24-hour value area (+1), inside (0) or below (-1).")
def vp24_state(ctx):
    _, hi, lo = _vp24(ctx)
    s = np.where(ctx.c > hi, 1.0, np.where(ctx.c < lo, -1.0, 0.0))
    return np.where(np.isfinite(hi) & np.isfinite(ctx.c), s, np.nan)
