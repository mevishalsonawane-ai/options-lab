"""h29 stage 1: per (underlying, day, minute) option-PRICING panel from the nearest-expiry ATM±10 chain. No P&L.

    OBUY_CACHE=<scratch>/hunt/h29/cache flock <scratch>/obuy.lock python3 -I research/hunt/h29/build.py [UND ...]
Output: <OBUY_CACHE>/h29/panel_<UND>.parquet, one row per minute (float32):
  S        index close (ffill)
  F        synthetic forward = median over strikes within ±2 steps of S of K + C - P (both legs traded <= 5 min ago)
  basis    (F / S - 1) in bps   (carry + option traders' direction tell)
  K0       ATM strike (nearest F);  strad = C(K0) + P(K0)
  iv       ATM implied vol, Black-76 on F, from the straddle, TRADING-TIME T (remaining session minutes / (375*252))
  ivc, ivp IV of the OTM call at ~F + strad/2 and the OTM put at ~F - strad/2 (about 30-35 delta)
  rr       ivc - ivp (risk reversal: rises when calls get bid relative to puts)
  ivm      ATM IV of the nearest MONTHLY series when the near series is a weekly with a different expiry (term structure)
  dhan_iv  Dhan's own iv column at K0 (mean of C/P), sanity only
  tmin     remaining trading minutes to the near expiry;  dte calendar days;  exp expiry-day flag
Everything at minute m uses bars up to and including m only.
"""
from __future__ import annotations

import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.special import ndtr  # noqa: E402
from obuy import data as D  # noqa: E402

OUT = os.path.join(C.CACHE, "h29")
W = C.W
STALE = 5
YEAR_MIN = 375.0 * 252


def ffill_age(a):
    """forward-fill along axis -1; returns (filled, age in minutes since the last real print; inf if none)."""
    n = a.shape[-1]
    ok = ~np.isnan(a)
    idx = np.where(ok, np.arange(n), -1)
    np.maximum.accumulate(idx, axis=-1, out=idx)
    filled = np.take_along_axis(a, np.maximum(idx, 0), axis=-1)
    filled = np.where(idx >= 0, filled, np.nan)
    age = np.where(idx >= 0, np.arange(n) - idx, np.inf)
    return filled, age


def b76(F, K, T, sig, cp):
    sq = sig * np.sqrt(T)
    d1 = (np.log(F / K) + 0.5 * sq * sq) / sq
    d2 = d1 - sq
    c = F * ndtr(d1) - K * ndtr(d2)
    return np.where(cp > 0, c, c - F + K)


def implied(price, F, K, T, cp, n=48):
    """vectorised bisection for Black-76 vol (r = 0); NaN when price is not above intrinsic or inputs missing."""
    price, F, K, T, cp = np.broadcast_arrays(*(np.asarray(x, float) for x in (price, F, K, T, cp)))
    intr = np.maximum(cp * (F - K), 0)
    ok = np.isfinite(price) & np.isfinite(F) & np.isfinite(K) & (T > 0) & (price > intr + 0.025)
    lo = np.full(price.shape, 0.005)
    hi = np.full(price.shape, 5.0)
    Fz, Kz, Tz = np.where(ok, F, 1.0), np.where(ok, K, 1.0), np.where(ok, T, 1.0)
    for _ in range(n):
        mid = 0.5 * (lo + hi)
        p = b76(Fz, Kz, Tz, mid, cp)
        up = p > price
        hi = np.where(up, mid, hi)
        lo = np.where(up, lo, mid)
    v = 0.5 * (lo + hi)
    v = np.where(ok & (v < 4.9) & (v > 0.006), v, np.nan)
    return v


def straddle_iv(st, F, K, T, n=48):
    ok = np.isfinite(st) & np.isfinite(F) & np.isfinite(K) & (T > 0) & (st > np.abs(F - K) + 0.05)
    lo = np.full(st.shape, 0.005)
    hi = np.full(st.shape, 5.0)
    Fz, Kz, Tz = np.where(ok, F, 1.0), np.where(ok, K, 1.0), np.where(ok, T, 1.0)
    for _ in range(n):
        mid = 0.5 * (lo + hi)
        p = b76(Fz, Kz, Tz, mid, 1) + b76(Fz, Kz, Tz, mid, -1)
        up = p > st
        hi = np.where(up, mid, hi)
        lo = np.where(up, lo, mid)
    v = 0.5 * (lo + hi)
    return np.where(ok & (v < 4.9) & (v > 0.006), v, np.nan)


def chain_core(ch, S, step):
    """F, basis, K0, straddle, ATM C/P prices from one Chain and the index close S (W,)."""
    K = ch.K.astype(float)
    Cc, Ca = ffill_age(ch.c["C"])
    Pc, Pa = ffill_age(ch.c["P"])
    Cc = np.where(Ca <= STALE, Cc, np.nan)
    Pc = np.where(Pa <= STALE, Pc, np.nan)
    near = np.abs(K[:, None] - S[None, :]) <= 2.0 * step + 1e-9
    fk = np.where(near, K[:, None] + Cc - Pc, np.nan)
    with np.errstate(all="ignore"):
        F = np.nanmedian(np.where(np.isfinite(fk), fk, np.nan), axis=0) if np.isfinite(fk).any() else np.full(W, np.nan)
    i0 = np.argmin(np.abs(K[:, None] - np.where(np.isfinite(F), F, S)[None, :]), axis=0)
    cols = np.arange(W)
    K0 = K[i0]
    cA, pA = Cc[i0, cols], Pc[i0, cols]
    return K, Cc, Pc, F, K0, cA + pA, i0


def nearest_idx(K, x):
    return np.clip(np.searchsorted(K, x), 0, len(K) - 1)


def build(und, mk):
    os.makedirs(OUT, exist_ok=True)
    ix = mk.index(und)
    op = mk.options(und)
    step = C.STEP[und]
    M = ix.mat()
    days = ix.days
    exp_days = np.array([d for d in days if ix.d[d]["exp"]])
    pos = {d: i for i, d in enumerate(days)}
    mins = C.OPEN_M + np.arange(W)
    rows = []
    for i, d in enumerate(days):
        x = ix.d[d]
        k = np.searchsorted(exp_days, d)
        if k >= len(exp_days):
            continue
        ed = exp_days[k]
        if (ed - d).days > 40 or ed not in pos:
            continue
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        S = pd.Series(M["c"][i]).ffill().values
        if np.isnan(S).all():
            continue
        ndays_after = pos[ed] - i                        # trading days after today up to and incl. expiry
        tmin = (C.LAST_M + 1 - mins) + 375.0 * ndays_after
        T = np.maximum(tmin, 1.0) / YEAR_MIN
        K, Cc, Pc, F, K0, strad, i0 = chain_core(ch, S, step)
        iv = straddle_iv(strad, F, K0, T)
        # wings at F -/+ strad/2, at least one step away
        dist = np.maximum(np.where(np.isfinite(strad), 0.5 * strad, np.nan), step)
        Fs = np.where(np.isfinite(F), F, S)
        kc = nearest_idx(K, Fs + dist)
        kp = nearest_idx(K, Fs - dist)
        kc = np.where(K[kc] <= Fs, np.minimum(kc + 1, len(K) - 1), kc)
        kp = np.where(K[kp] >= Fs, np.maximum(kp - 1, 0), kp)
        cols = np.arange(W)
        ivc = implied(Cc[kc, cols], F, K[kc], T, 1)
        ivp = implied(Pc[kp, cols], F, K[kp], T, -1)
        ivc = np.where(K[kc] > Fs, ivc, np.nan)
        ivp = np.where(K[kp] < Fs, ivp, np.nan)
        dI = 0.5 * (ffill_age(ch.iv["C"])[0][i0, cols] + ffill_age(ch.iv["P"])[0][i0, cols])
        ivm = np.full(W, np.nan)
        if ch.series == "WEEK":
            chm = op.chain(d, "month")
            if chm is not None and len(chm.K) >= 5:
                # monthly expiry = last expiry day in the calendar month of the next expiry (weekly regime)
                same = exp_days[(exp_days >= ed) & np.array([(e.year, e.month) == (ed.year, ed.month) for e in exp_days])]
                em = same.max() if len(same) else ed
                if em != ed and em in pos:
                    Tm = np.maximum((C.LAST_M + 1 - mins) + 375.0 * (pos[em] - i), 1.0) / YEAR_MIN
                    _, _, _, Fm, K0m, sm, _ = chain_core(chm, S, step)
                    ivm = straddle_iv(sm, Fm, K0m, Tm)
        df = pd.DataFrame(dict(m=mins.astype(np.int16), S=S, F=F, basis=(F / S - 1) * 1e4, K0=K0, strad=strad,
                               iv=iv, ivc=ivc, ivp=ivp, rr=ivc - ivp, ivm=ivm, dhan_iv=dI / 100.0, tmin=tmin))
        for c in df.columns:
            if c != "m":
                df[c] = df[c].astype(np.float32)
        df["day"] = pd.Timestamp(d)
        df["dte"] = np.int16((ed - d).days)
        df["exp"] = bool(x["exp"])
        df["real"] = bool(x["real"])
        df["series"] = ch.series
        rows.append(df)
        if i % 100 == 0:
            mk.release(und) if False else None
            print(und, d, len(rows), flush=True)
    P = pd.concat(rows, ignore_index=True)
    P["und"] = und
    P.to_parquet(os.path.join(OUT, f"panel_{und}.parquet"))
    mk.release(und)
    print("done", und, len(P), P.day.nunique(), flush=True)


if __name__ == "__main__":
    mk = D.market()
    for u in sys.argv[1:] or ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY"]:
        build(u, mk)
