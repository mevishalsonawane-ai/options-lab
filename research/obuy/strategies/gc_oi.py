"""Catalog family oi_sentiment (OI-01, OI-02, OI-04 tested; OI-03 and OI-05 skipped - see the report).

All OI is the minute OI of the nearest-expiry chain in the data, which holds ONLY the ATM+-10 strikes (rolling): 'all
strikes' in the catalog means these 21 strikes here.

OI-01 gc_oi01_pcr      PCR contrarian, positional: end-of-day (15:14) put/call ratio of OI (or of the day's volume);
                       PCR > hi -> buy a call, < lo -> buy a put (Varsity 1.3 / 0.5, or the rolling 90/10 or 80/20
                       percentile of the previous 250 sessions' PCR). Bought at 15:15 in the nearest MONTHLY ATM, held
                       2 / 5 / 12 sessions (exit 15:15), never later than 3 sessions before that monthly expiry; no new
                       signal while a position is open. Exits: time only, -30%, -30% / +50%. ('PCR normalises' exit
                       not modelled.)
OI-02 gc_oi02_doi      Intraday change in OI: every 5 / 15 minutes from 09:45 to 14:30, sum since the open of the OI
                       change of puts and of calls over ATM+-N strikes; put addition > thr x call addition (and put
                       addition > 0) at two consecutive snapshots -> buy ATM call (mirror -> put); optional filter
                       index above / below its time-weighted VWAP. A signal fires when the condition starts (not
                       at every snapshot while it lasts). Exit when the opposite condition appears at a later
                       snapshot ('OI ratio flips'), optional -30% / +60% (1:2), else 15:15. Up to 3 trades a day.
OI-04 gc_oi04_maxoi    Highest-OI strike as next-day magnet: at 15:14 the strike with the largest CE+PE OI; spot more
                       than dist away -> buy ATM toward it at 15:15, exit the next session at 09:30 / 12:00 / 15:15 or
                       when the index reaches that strike (or no index target); -30% stop optional.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from .. import config as C
from ..engine import Execution, Exits, StrikeRule
from ..multiday import MD_SQ, contract_expiry
from .base import Strategy
from .common import day_arrays
from .gc_common import feat_cache, ffill, nth_session, twap

U2 = ("NIFTY", "BANKNIFTY")
EOD = 15 * 60 + 14      # the signal bar (its close); the fill is 15:15


# ------------------------------------------------------------------------------------------------ features
def eod_oi_features(und):
    """Per day: OI PCR, volume PCR, max-OI strike, spot - at 15:14 over the strikes in the data (near chain)."""
    from ..data import market
    mk = market()
    ix = mk.index(und)
    opts = mk.options(und)
    col = EOD - C.OPEN_M
    rows = []
    for d in ix.days:
        ch = opts.chain(d, "near")
        if ch is None:
            continue
        o, h, l, c = day_arrays(ix, d)
        sp = c[col]
        oc, op = ffill(ch.oi["C"])[:, col], ffill(ch.oi["P"])[:, col]
        vc, vp = np.nansum(ch.v["C"][:, : col + 1]), np.nansum(ch.v["P"][:, : col + 1])
        toc, top = np.nansum(oc), np.nansum(op)
        tot = np.nan_to_num(oc) + np.nan_to_num(op)
        kmax = float(ch.K[int(np.argmax(tot))]) if tot.max() > 0 else np.nan
        rows.append(dict(day=d, pcr_oi=top / toc if toc > 0 else np.nan, pcr_vol=vp / vc if vc > 0 else np.nan,
                         kmax=kmax, spot=sp))
    mk.release(und)
    return pd.DataFrame(rows).set_index("day")


def intraday_oi_features(und, ns=(3, 5, 10), first=9 * 60 + 20, last=14 * 60 + 30):
    """Per day: snapshot minutes every 5 min, and for each N: summed OI change since the day's first print over ATM+-N
    (ATM at the snapshot) for calls and puts; the index close and time-weighted VWAP at the snapshot."""
    from ..data import market
    mk = market()
    ix = mk.index(und)
    opts = mk.options(und)
    step = C.STEP[und]
    snaps = np.arange(first, last + 1, 5) - 1 - C.OPEN_M        # bar columns whose close is the snapshot
    out = {}
    for d in ix.days:
        ch = opts.chain(d, "near")
        if ch is None:
            continue
        o, h, l, c = day_arrays(ix, d)
        tw = twap(h, l, c)
        f = {}
        for r in ("C", "P"):
            a = ffill(ch.oi[r])
            first_v = np.array([row[~np.isnan(row)][0] if (~np.isnan(row)).any() else np.nan for row in a])
            f[r] = a - first_v[:, None]
        res = {n: np.full((len(snaps), 2), np.nan) for n in ns}
        for si, col in enumerate(snaps):
            sp = c[col]
            if not np.isfinite(sp):
                continue
            k0 = np.floor(sp / step + 0.5) * step
            for n in ns:
                m = np.abs(ch.K - k0) <= n * step + 1e-9
                res[n][si, 0] = np.nansum(f["C"][m, col])
                res[n][si, 1] = np.nansum(f["P"][m, col])
        out[d] = dict(snaps=snaps + C.OPEN_M, spot=c[snaps], twap=tw[snaps], d=res)
    mk.release(und)
    return out


# ------------------------------------------------------------------------------------------------ OI-01
def pcr_signals(mk, thr="varsity", kind="oi", hold=5, unds=U2):
    out = []
    for und in unds:
        ix = mk.index(und)
        F = feat_cache(eod_oi_features, und)
        x = F[f"pcr_{kind}"]
        if thr == "varsity":
            hi = pd.Series(1.3, index=x.index)
            lo = pd.Series(0.5, index=x.index)
        else:
            q = 0.9 if thr == "pct90" else 0.8
            hi = x.shift(1).rolling(250, min_periods=120).quantile(q)
            lo = x.shift(1).rolling(250, min_periods=120).quantile(1 - q)
        busy_until = None
        for d in F.index:
            v = x.get(d)
            if busy_until is not None and d <= busy_until:
                continue
            if not np.isfinite(v) or not (np.isfinite(hi[d]) and np.isfinite(lo[d])):
                continue
            side = 1 if v > hi[d] else (-1 if v < lo[d] else 0)
            if side == 0:
                continue
            xd = nth_session(ix, d, hold)
            mexp = contract_expiry(ix, d, "month")
            if xd is None or mexp is None:
                continue
            cap = nth_session(ix, mexp, -3)
            if cap is not None and xd > cap:
                xd = cap
            if cap is None or xd <= d:
                continue
            out.append(dict(und=und, day=d, sig_min=EOD, side=side, book=f"pcr_{und}", exit_day=xd,
                            exit_min=15 * 60 + 15, tag=f"pcr={v:.2f}"))
            busy_until = xd
    return pd.DataFrame(out)


# ------------------------------------------------------------------------------------------------ OI-02
def doi_signals(mk, n=5, thr=1.5, every=5, vwap=False, first=9 * 60 + 45, unds=U2):
    out = []
    for und in unds:
        F = feat_cache(intraday_oi_features, und)
        for d, f in F.items():
            snaps = f["snaps"]
            keep = np.nonzero(((snaps + 1 - (9 * 60 + 20)) % every) == 0)[0]
            dc, dp = f["d"][n][keep, 0], f["d"][n][keep, 1]
            sm, sp, tw = snaps[keep], f["spot"][keep], f["twap"][keep]
            bull = (dp > 0) & (dp > thr * dc)
            bear = (dc > 0) & (dc > thr * dp)
            prev = 0
            for i in range(1, len(keep)):
                side = 1 if (bull[i] and bull[i - 1]) else (-1 if (bear[i] and bear[i - 1]) else 0)
                fresh = side != 0 and side != prev          # a NEW signal (the condition just started / flipped)
                prev = side
                if sm[i] + 1 < first or not fresh:
                    continue
                if vwap and not (np.isfinite(tw[i]) and (sp[i] - tw[i]) * side > 0):
                    continue
                flip = np.nonzero((bear if side > 0 else bull)[i + 1:])[0]
                xat = int(sm[i + 1 + flip[0]]) + 1 if len(flip) else np.nan
                out.append(dict(und=und, day=d, sig_min=int(sm[i]), side=side, book=f"doi_{und}", exit_at=xat))
    return pd.DataFrame(out)


SQ15 = 15 * 60 + 15
DOI_X = [Exits(sq_off=SQ15), Exits(stop_pct=0.3, tgt_pct=0.6, sq_off=SQ15)]


# ------------------------------------------------------------------------------------------------ OI-04
def maxoi_signals(mk, dist=0.006, xmin=930, target=True, unds=U2):
    xm = (xmin // 100) * 60 + xmin % 100
    out = []
    for und in unds:
        ix = mk.index(und)
        F = feat_cache(eod_oi_features, und)
        for d in F.index:
            r = F.loc[d]
            if not (np.isfinite(r.kmax) and np.isfinite(r.spot)) or abs(r.spot - r.kmax) / r.spot <= dist:
                continue
            xd = nth_session(ix, d, 1)
            if xd is None:
                continue
            side = 1 if r.kmax > r.spot else -1
            out.append(dict(und=und, day=d, sig_min=EOD, side=side, book=f"moi_{und}", exit_day=xd, exit_min=xm,
                            idx_target=r.kmax if target else np.nan, tag=f"kmax={r.kmax:.0f}"))
    return pd.DataFrame(out)


# multi-day exits keep sig_levels=True: the time exit is the signal's exit_at
MD_X3 = [Exits(sq_off=MD_SQ), Exits(stop_pct=0.3, sq_off=MD_SQ), Exits(stop_pct=0.3, tgt_pct=0.5, sq_off=MD_SQ)]

STRATEGIES = [
    Strategy(
        name="gc_oi01_pcr", family="oi_sentiment", signal_fn=pcr_signals,
        sig_grid=[dict(thr=t, kind=k, hold=h) for t in ("varsity", "pct90", "pct80") for k in ("oi", "vol") for h in (2, 5, 12)],
        rules=[StrikeRule(0, series="month")], exe=Execution(expiry="skip"),
        exits=MD_X3, pos=dict(one_at_a_time=True), window=(14 * 60 + 45, EOD),
        doc="OI-01 PCR contrarian (EOD OI / volume PCR), monthly ATM held 2/5/12 sessions."),
    Strategy(
        name="gc_oi02_doi", family="oi_sentiment", signal_fn=doi_signals,
        sig_grid=[dict(n=n, thr=t, every=e, vwap=v) for n in (3, 5, 10) for t in (1.2, 1.5, 2.0) for e in (5, 15)
                  for v in (False, True)],
        rules=[StrikeRule(0)], exe=Execution(expiry="skip"),
        exits=DOI_X, pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 44, 14 * 60 + 29),
        doc="OI-02 intraday put-vs-call OI addition since the open, exit when it flips."),
    Strategy(
        name="gc_oi04_maxoi", family="oi_sentiment", signal_fn=maxoi_signals,
        sig_grid=[dict(dist=x, xmin=m, target=t) for x in (0.003, 0.006, 0.010) for m in (930, 1200, 1515)
                  for t in (True, False)],
        rules=[StrikeRule(0)], exe=Execution(expiry="skip"),
        exits=[Exits(sq_off=MD_SQ), Exits(stop_pct=0.3, sq_off=MD_SQ)],
        pos=dict(one_at_a_time=True), window=(14 * 60 + 45, EOD),
        doc="OI-04 max-OI strike magnet, bought 15:15, out next session."),
]
