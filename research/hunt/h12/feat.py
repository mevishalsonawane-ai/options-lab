"""h12 features (all known at the signal minute's close) and the 28 pre-registered rules (see PREREG.md).

Trades come from the h7 packs (research/hunt/h7/build.py -> OBUY_CACHE/h7/packs.pkl), via h10's cap.py/h7's sim.py.
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h10"))
import cap  # noqa: E402
from cap import sim  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402

UNDS = ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]


def _und_tables(mk, u):
    ix = mk.index(u)
    M = ix.mat()
    days = ix.days
    dl = ix.daily().copy()
    dl.index = pd.to_datetime(dl.index)
    pc = dl.close.shift(1)
    tr = np.maximum(dl.high - dl.low, np.maximum((dl.high - pc).abs(), (dl.low - pc).abs()))
    atr = tr.rolling(14).mean().shift(1)                 # previous 14 days
    sma = dl.close.rolling(20).mean().shift(1)           # previous 20 closes
    dtrend = np.sign(pc - sma)
    exp = dl.exp.values.astype(bool)
    dte = np.full(len(dl), np.nan)
    nxt = None
    for i in range(len(dl) - 1, -1, -1):
        if exp[i]:
            nxt = i
        dte[i] = np.nan if nxt is None else nxt - i
    T = pd.DataFrame(dict(prev_close=pc, atr=atr, dtrend=dtrend, dte=dte, open=dl.open), index=dl.index)
    flat = pd.Series(M["c"].ravel()).ffill().values      # minute closes across days, ffilled
    return dict(M=M, pos={pd.Timestamp(d): i for i, d in enumerate(days)}, T=T, flat=flat)


def _vix(mk):
    v = mk.vix
    s = v.daily.close.copy()
    s.index = pd.to_datetime(s.index)
    med = s.rolling(250, min_periods=120).median().shift(1)
    prev = s.shift(1)
    mins = {pd.Timestamp(d): a for d, a in v.minutes().items()}
    op = v.daily.open.copy()
    op.index = pd.to_datetime(op.index)
    return dict(close=s, med=med, prev=prev, mins=mins, open=op)


def features(t: pd.DataFrame, mk=None) -> pd.DataFrame:
    """t: trades with und, day, sig_min, side, room. Returns feature frame aligned to t.index."""
    mk = mk or market()
    V = _vix(mk)
    out = pd.DataFrame(index=t.index)
    for c in ("dtrend", "m60", "intra", "vixhigh", "vixup", "dte", "gap", "rng"):
        out[c] = np.nan
    for u in t.und.unique():
        X = _und_tables(mk, u)
        m = (t.und == u).values
        tt = t[m]
        days = pd.to_datetime(tt.day.values)
        col = tt.sig_min.values.astype(int) - C.OPEN_M
        di = np.array([X["pos"].get(d, -1) for d in days])
        ok = di >= 0
        T = X["T"].reindex(days)
        side = tt.side.values
        c_now = np.where(ok, X["flat"][np.maximum(di, 0) * C.W + col], np.nan)
        c_60 = np.where(ok, X["flat"][np.maximum(np.maximum(di, 0) * C.W + col - 60, 0)], np.nan)
        H = X["M"]["h"][np.maximum(di, 0)]
        L = X["M"]["l"][np.maximum(di, 0)]
        cols = np.arange(C.W)[None, :]
        upto = cols <= col[:, None]
        hi = np.nanmax(np.where(upto, H, np.nan), axis=1)
        lo = np.nanmin(np.where(upto, L, np.nan), axis=1)
        atr = T.atr.values
        out.loc[m, "dtrend"] = T.dtrend.values
        out.loc[m, "m60"] = np.sign(c_now - c_60)
        out.loc[m, "intra"] = np.sign(c_now - T.open.values)
        out.loc[m, "dte"] = T.dte.values
        out.loc[m, "gap"] = np.abs(T.open.values - T.prev_close.values) / atr
        out.loc[m, "rng"] = (hi - lo) / atr
        vm = V["med"].reindex(days).values
        vp = V["prev"].reindex(days).values
        out.loc[m, "vixhigh"] = np.where(np.isfinite(vm) & np.isfinite(vp), (vp > vm).astype(float), np.nan)
        vnow = np.full(len(tt), np.nan)
        for i, (d, c) in enumerate(zip(days, col)):
            a = V["mins"].get(d)
            if a is not None:
                s = a[: c + 1]
                s = s[np.isfinite(s)]
                if len(s):
                    vnow[i] = s[-1]
        vo = V["open"].reindex(days).values
        vnow = np.where(np.isfinite(vnow), vnow, vo)
        out.loc[m, "vixup"] = np.where(np.isfinite(vnow) & np.isfinite(vp), (vnow > vp).astype(float), np.nan)
    out["side"] = t.side.values
    out["room"] = t.room.values
    out["tod"] = t.sig_min.values
    out["dow"] = pd.to_datetime(t.day.values).dayofweek
    return out


def rules(F: pd.DataFrame) -> dict:
    """-> {name: units array (0 = skip)}. NaN features count as 'condition false'."""
    s = F.side.values
    agree_d = (F.dtrend.values == s)
    dis_d = (F.dtrend.values == -s)
    agree_60 = (F.m60.values == s)
    agree_in = (F.intra.values == s)
    vh = F.vixhigh.values == 1
    vl = F.vixhigh.values == 0
    vu = F.vixup.values == 1
    vd = F.vixup.values == 0
    early = F.tod.values < 11 * 60
    midwk = ~np.isin(F.dow.values, [0, 4])
    dte3 = F.dte.values <= 3
    gapL = F.gap.values >= 0.3
    gapS = F.gap.values < 0.3
    rngW = F.rng.values >= 0.5
    rngN = F.rng.values < 0.5
    r3 = F.room.values >= 3
    r6 = F.room.values >= 6
    put = s < 0
    f = lambda c: c.astype(float)  # noqa: E731
    two = lambda c: 1.0 + c.astype(float)  # noqa: E731
    R = {
        "BASE": np.ones(len(F)),
        "F01_puts": f(put), "F02_calls": f(~put),
        "F03_dtrend_agree": f(agree_d), "F04_dtrend_disagree": f(dis_d),
        "F05_m60_agree": f(agree_60), "F06_intraday_agree": f(agree_in),
        "F07_vix_high": f(vh), "F08_vix_low": f(vl),
        "F09_vix_up": f(vu), "F10_vix_down": f(vd),
        "F11_before11": f(early), "F12_after11": f(~early),
        "F13_tue_thu": f(midwk), "F14_dte_le3": f(dte3), "F15_dte_gt3": f(~dte3 & np.isfinite(F.dte.values)),
        "F16_gap_small": f(gapS), "F17_gap_large": f(gapL),
        "F18_rng_narrow": f(rngN), "F19_rng_wide": f(rngW),
        "F20_room3": f(r3), "F21_room6": f(r6),
        "F22_puts_or_dtrend": f(put | agree_d),
        "S23_2x_puts": two(put), "S24_2x_dtrend": two(agree_d), "S25_2x_vixhigh": two(vh),
        "S26_2x_m60": two(agree_60), "S27_2x_room3": two(r3),
        "S28_score": 1.0 + f(agree_d) + f(agree_60) + f(vu),
    }
    assert len(R) == 29
    return R
