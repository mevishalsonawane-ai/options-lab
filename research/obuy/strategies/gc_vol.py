"""Catalog family volatility_long (VOL-01 .. VOL-04).

VOL-01 gc_vol01_strad   Long ATM straddle at 09:20 / 09:30, out at 14:20 / 15:10, Tue & Fri only (the source's in-sample
                        weekday pick) or every day, optional India VIX < 15 (yesterday's close), optional skip of the
                        session before expiry (DTE filter), no stop or a -30% combined stop. Expiry days skipped (the
                        weekly expiring today is not the source's trade). Extends straddle.py's fixed-time straddle.
VOL-02 gc_vol02_sbo     Straddle-premium breakout (the buy-side mirror of the seller's chart): combined ATM CE+PE
                        premium (strike frozen at 09:20, or rolling ATM) closes above its day high since 09:20 / its
                        09:20-09:34 range high / its time-weighted VWAP (crossing from below) after 12:00 / 13:00 ->
                        buy the straddle, or only the leg that rose more over the last 15 minutes. -15% stop, +20% /
                        +50% target, exit when the combined premium closes back below its VWAP, or a 10% trail after
                        +20%; out 15:15. (Supertrend on the combined premium not modelled.)
VOL-03 gc_vol03_ivp     Low IV percentile (India VIX percentile of yesterday's close among the previous 252 closes)
                        below 10 / 20 / 30, nearest monthly with >= 10 / 20 (and <= 30) calendar days left, no macro
                        event in the holding window -> buy the ATM straddle or 1-OTM strangle at 09:20, hold 3 / 5
                        sessions (15:15), never later than 3 sessions before expiry. Exits: none, -30%, -30%/+30%,
                        -30%/+50% (combined premium, on closes). ('IVP > 50' exit not modelled.) Positional.
VOL-04 gc_vol04_vixorb  India VIX up > 3 / 5 / 8 % from its 09:15 print AND the index's first 5-min close beyond the
                        15 / 30-minute opening range (cutoff 14:30) -> buy ATM / ITM1 that way; index stop at the
                        range's other side, target 1.5R / 2R, optional trail or -30% premium stop; out 15:15.
                        India VIX minute data starts Oct 2021.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from ..multiday import MD_SQ, contract_expiry
from .base import Strategy
from .common import day_arrays, vix_prev
from .gc_common import atm, events, feat_cache, ffill_recent, nth_session

U2 = ("NIFTY", "BANKNIFTY")


def _hm(x):
    return (x // 100) * 60 + x % 100


# ------------------------------------------------------------------------------------------------ VOL-01
def strad_signals(mk, at=920, days="tf", vix_max=None, dte="any", unds=("BANKNIFTY", "NIFTY")):
    T = _hm(at)
    vp, _ = vix_prev(mk)
    out = []
    for und in unds:
        ix = mk.index(und)
        for i, d in enumerate(ix.days):
            if days == "tf" and d.weekday() not in (1, 4):
                continue
            if vix_max and not (vp.get(d, 99) < vix_max):
                continue
            if dte == "skip_eve" and i + 1 < len(ix.days) and ix.d[ix.days[i + 1]]["exp"]:
                continue
            out.append(dict(und=und, day=d, sig_min=T - 1, side=0, book=f"s01_{und}"))
    return pd.DataFrame(out)


# ------------------------------------------------------------------------------------------------ VOL-02
def straddle_features(und):
    """Per day (float32 W-arrays): combined premium and legs, strike frozen at 09:20 and rolling ATM."""
    from ..data import market
    mk = market()
    ix = mk.index(und)
    opts = mk.options(und)
    step = C.STEP[und]
    t0 = 9 * 60 + 20 - C.OPEN_M
    out = {}
    for d in ix.days:
        ch = opts.chain(d, "near")
        if ch is None:
            continue
        o, h, l, c = day_arrays(ix, d)
        if not np.isfinite(c[t0 - 1]):
            continue
        cc, pc = ffill_recent(ch.c["C"]), ffill_recent(ch.c["P"])
        kf = atm(c[t0 - 1], step)
        res = {}
        for mode in ("fixed", "roll"):
            C_ = np.full(C.W, np.nan)
            P_ = np.full(C.W, np.nan)
            for t in range(t0, C.W):
                s = c[t]
                if np.isnan(s):
                    continue
                k = kf if mode == "fixed" else atm(s, step)
                i = ch.kpos(k)
                if i < 0:
                    continue
                C_[t], P_[t] = cc[i, t], pc[i, t]
            res[mode] = (C_.astype(np.float32), P_.astype(np.float32))
        out[d] = dict(kf=kf, **res)
    mk.release(und)
    return out


def sbo_signals(mk, ref="dayhigh", strike="fixed", legs="both", start=1200, last=1445,
                unds=("NIFTY", "BANKNIFTY", "SENSEX")):
    t0 = 9 * 60 + 20 - C.OPEN_M
    S, L = _hm(start) - C.OPEN_M, _hm(last) - C.OPEN_M
    out = []
    for und in unds:
        F = feat_cache(straddle_features, und)
        for d, f in F.items():
            Cc, Pc = (x.astype(float) for x in f[strike])
            Sv = Cc + Pc
            ok = ~np.isnan(Sv)
            cs = np.cumsum(np.where(ok, Sv, 0.0))
            n = np.cumsum(ok)
            with np.errstate(all="ignore"):
                vw = np.where(n > 0, cs / np.maximum(n, 1), np.nan)
            if ref == "dayhigh":
                lvl = np.fmax.accumulate(np.where(ok, Sv, -np.inf))
                lvl = np.r_[np.nan, lvl[:-1]]                           # high BEFORE this minute
            elif ref == "orhigh":
                orh = np.nanmax(Sv[t0:t0 + 15]) if ok[t0:t0 + 15].any() else np.nan
                lvl = np.full(C.W, orh)
            else:
                lvl = vw
            prev = None
            for t in range(S, L + 1):
                x = Sv[t]
                if np.isnan(x) or not np.isfinite(lvl[t]):
                    continue
                above = x > lvl[t]
                if prev is not None and above and not prev:
                    if legs == "both":
                        side = 0
                    else:
                        a, b = Cc[t - 15], Pc[t - 15]
                        if not (np.isfinite(a) and np.isfinite(b) and a > 0 and b > 0):
                            break
                        side = 1 if Cc[t] / a >= Pc[t] / b else -1
                    below = np.nonzero(Sv[t + 1:] < vw[t + 1:])[0]
                    xat = t + 1 + int(below[0]) + 1 + C.OPEN_M if len(below) else np.nan
                    out.append(dict(und=und, day=d, sig_min=t + C.OPEN_M, side=side, book=f"sbo_{und}",
                                    strike=float(f["kf"]) if strike == "fixed" else np.nan, exit_at=xat))
                    break
                prev = above
    return pd.DataFrame(out)


SQ15 = 15 * 60 + 15
SBO_X = [Exits(stop_pct=0.15, tgt_pct=0.2, sq_off=SQ15, sig_levels=False, intrabar=False),
         Exits(stop_pct=0.15, tgt_pct=0.5, sq_off=SQ15, sig_levels=False, intrabar=False),
         Exits(stop_pct=0.15, tgt_pct=0.5, sq_off=SQ15, sig_levels=True, intrabar=False),
         Exits(stop_pct=0.15, trail_pct=0.10, trail_arm=0.2, sq_off=SQ15, sig_levels=False, intrabar=False)]


# ------------------------------------------------------------------------------------------------ VOL-03
def ivp_signals(mk, ivp=20, dte_min=10, hold=5, unds=U2):
    s = mk.vix.daily.close
    pct = s.rolling(253).apply(lambda x: (x[:-1] < x[-1]).mean() * 100, raw=True)   # rank of each close vs prior 252
    pprev = pct.shift(1).to_dict()                                                    # known at today's open
    out = []
    for und in unds:
        ix = mk.index(und)
        ev = set(events(ix, "macro").day)
        busy = None
        for d in ix.days:
            if busy is not None and d <= busy:
                continue
            p = pprev.get(d)
            if p is None or not np.isfinite(p) or p >= ivp:
                continue
            mexp = contract_expiry(ix, d, "month")
            if mexp is None:
                continue
            dte = (mexp - d).days
            if not (dte_min <= dte <= 30):
                continue
            xd = nth_session(ix, d, hold)
            cap = nth_session(ix, mexp, -3)
            if xd is None or cap is None:
                continue
            xd = min(xd, cap)
            if xd <= d:
                continue
            span = ix.days[ix.pos[d]: ix.pos[xd] + 1]
            if any(x in ev for x in span):
                continue
            out.append(dict(und=und, day=d, sig_min=9 * 60 + 19, side=0, book=f"ivp_{und}", exit_day=xd,
                            exit_min=15 * 60 + 15, tag=f"ivp={p:.0f},dte={dte}"))
            busy = xd
    return pd.DataFrame(out)


IVP_X = [Exits(sq_off=MD_SQ, intrabar=False), Exits(stop_pct=0.3, sq_off=MD_SQ, intrabar=False),
         Exits(stop_pct=0.3, tgt_pct=0.3, sq_off=MD_SQ, intrabar=False),
         Exits(stop_pct=0.3, tgt_pct=0.5, sq_off=MD_SQ, intrabar=False)]


# ------------------------------------------------------------------------------------------------ VOL-04
def vixorb_signals(mk, x=0.05, or_min=15, tf=5, cutoff=14 * 60 + 30, unds=U2):
    vm = mk.vix.minutes()
    out = []
    for und in unds:
        ix = mk.index(und)
        for d in ix.days:
            v = vm.get(d)
            if v is None or not ix.d[d]["real"]:
                continue
            okv = np.nonzero(~np.isnan(v[:5]))[0]
            if not len(okv):
                continue
            v0 = v[okv[0]]
            o, h, l, c = day_arrays(ix, d)
            if np.isnan(c[:or_min]).sum() > 2:
                continue
            hi, lo = np.nanmax(h[:or_min]), np.nanmin(l[:or_min])
            for col in range(or_min + tf - 1, cutoff - C.OPEN_M, tf):
                xx = c[col]
                vv = v[max(col - 2, 0): col + 1]
                vv = vv[~np.isnan(vv)]
                if np.isnan(xx) or not len(vv):
                    continue
                side = 1 if xx > hi else (-1 if xx < lo else 0)
                if side == 0 or not (vv[-1] / v0 - 1 > x):
                    continue
                out.append(dict(und=und, day=d, sig_min=col + C.OPEN_M, side=side, book=f"vorb_{und}",
                                idx_stop=lo if side > 0 else hi))
                break
    return pd.DataFrame(out)


VORB_X = [Exits(idx_tgt_r=2.0, sq_off=SQ15), Exits(idx_tgt_r=1.5, sq_off=SQ15),
          Exits(idx_tgt_r=2.0, pine_trail=True, sq_off=SQ15), Exits(stop_pct=0.3, idx_tgt_r=2.0, sq_off=SQ15)]

STRATEGIES = [
    Strategy(
        name="gc_vol01_strad", family="volatility_long", signal_fn=strad_signals,
        sig_grid=[dict(at=a, days=w, vix_max=v, dte=t) for a in (920, 930) for w in ("tf", "all") for v in (None, 15.0)
                  for t in ("any", "skip_eve")],
        rules=[StrikeRule(0)], exe=Execution(expiry="skip"),
        exits=[Exits(sq_off=sq, stop_pct=s, intrabar=False) for sq in (14 * 60 + 20, 15 * 60 + 10) for s in (None, 0.3)],
        window=(9 * 60 + 19, 12 * 60), doc="VOL-01 09:20/09:30 ATM straddle to 14:20/15:10, Tue&Fri or all days."),
    Strategy(
        name="gc_vol02_sbo", family="volatility_long", signal_fn=sbo_signals,
        sig_grid=[dict(ref=r, strike=k, legs=g, start=s) for r in ("dayhigh", "orhigh", "vwap") for k in ("fixed", "roll")
                  for g in ("both", "one") for s in (1200, 1300)],
        rules=[StrikeRule(0)], exe=Execution(expiry="allow"), exits=SBO_X,
        pos=dict(one_at_a_time=True, max_per_day=1), window=(12 * 60, 14 * 60 + 45),
        doc="VOL-02 combined-premium breakout (day high / OR high / VWAP), straddle or the faster leg."),
    Strategy(
        name="gc_vol03_ivp", family="volatility_long", signal_fn=ivp_signals,
        sig_grid=[dict(ivp=p, dte_min=m, hold=h) for p in (10, 20, 30) for m in (10, 20) for h in (3, 5)],
        rules=[StrikeRule(0, series="month"), StrikeRule(-1, series="month")], exe=Execution(expiry="skip"),
        exits=IVP_X, pos=dict(one_at_a_time=True), window=(9 * 60 + 16, 10 * 60),
        doc="VOL-03 low VIX-percentile monthly straddle / strangle held 3-5 sessions."),
    Strategy(
        name="gc_vol04_vixorb", family="volatility_long", signal_fn=vixorb_signals,
        sig_grid=[dict(x=x, or_min=m) for x in (0.03, 0.05, 0.08) for m in (15, 30)],
        rules=[StrikeRule(0), StrikeRule(1)], exe=Execution(expiry="skip"), exits=VORB_X,
        pos=dict(one_at_a_time=True, max_per_day=1), window=(9 * 60 + 34, 14 * 60 + 30),
        doc="VOL-04 India VIX up X% + opening-range breakout, ATM / ITM1."),
]
