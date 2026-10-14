"""R44 indicators and signals on the underlying's 5-minute bars (PREREG.md "Signals").

All run on the continuous series of existing 5-minute bars (across days, missing bars skipped), so each value at bar t
uses bars 0..t only (no look-ahead). EMA adjust=False; RSI and ADX are Wilder's.
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r44cfg as K


@njit(cache=True)
def ema(x, n):
    """EMA with adjust=False: e[0] = x[0], e[t] = a*x[t] + (1-a)*e[t-1], a = 2/(n+1)."""
    out = np.empty(len(x))
    if len(x) == 0:
        return out
    a = 2.0 / (n + 1.0)
    out[0] = x[0]
    for t in range(1, len(x)):
        out[t] = a * x[t] + (1.0 - a) * out[t - 1]
    return out


@njit(cache=True)
def rsi(c, n=14):
    """Wilder RSI: the first average gain/loss is the simple mean of the first n changes (defined at t=n), then
    avg = (prev*(n-1) + x)/n. NaN before t=n. Average loss 0 -> 100 (0 if the gain is 0 too -> 50)."""
    T = len(c)
    out = np.full(T, np.nan)
    if T <= n:
        return out
    ag = 0.0
    al = 0.0
    for t in range(1, n + 1):
        d = c[t] - c[t - 1]
        if d > 0:
            ag += d
        else:
            al -= d
    ag /= n
    al /= n
    for t in range(n, T):
        if t > n:
            d = c[t] - c[t - 1]
            g = d if d > 0 else 0.0
            lo = -d if d < 0 else 0.0
            ag = (ag * (n - 1) + g) / n
            al = (al * (n - 1) + lo) / n
        if al == 0.0:
            out[t] = 100.0 if ag > 0 else 50.0
        else:
            out[t] = 100.0 - 100.0 / (1.0 + ag / al)
    return out


@njit(cache=True)
def adx(h, l, c, n=14):
    """Wilder ADX. +DM/-DM and TR from t=1; smoothed sums seeded with the sum of t=1..n, then s = s - s/n + x;
    DX from t=n; ADX = mean of DX[n..2n-1] at t=2n-1, then (prev*(n-1) + dx)/n. NaN before 2n-1."""
    T = len(c)
    out = np.full(T, np.nan)
    if T < 2 * n:
        return out
    sp = 0.0
    sm = 0.0
    st = 0.0
    dxs = 0.0
    a = 0.0
    for t in range(1, T):
        up = h[t] - h[t - 1]
        dn = l[t - 1] - l[t]
        pdm = up if (up > dn and up > 0) else 0.0
        mdm = dn if (dn > up and dn > 0) else 0.0
        tr = max(h[t] - l[t], abs(h[t] - c[t - 1]), abs(l[t] - c[t - 1]))
        if t <= n:
            sp += pdm
            sm += mdm
            st += tr
            if t < n:
                continue
        else:
            sp = sp - sp / n + pdm
            sm = sm - sm / n + mdm
            st = st - st / n + tr
        pdi = 100.0 * sp / st if st > 0 else 0.0
        mdi = 100.0 * sm / st if st > 0 else 0.0
        dx = 100.0 * abs(pdi - mdi) / (pdi + mdi) if (pdi + mdi) > 0 else 0.0
        if t < 2 * n - 1:
            dxs += dx
        elif t == 2 * n - 1:
            dxs += dx
            a = dxs / n
            out[t] = a
        else:
            a = (a * (n - 1) + dx) / n
            out[t] = a
    return out


def cross_up(a, b):
    """a > b and a[1] <= b[1] (b may be a scalar). False at t=0 and wherever a value is NaN."""
    a = np.asarray(a, float)
    b = np.broadcast_to(np.asarray(b, float), a.shape)
    out = np.zeros(len(a), bool)
    if len(a) > 1:
        out[1:] = (a[1:] > b[1:]) & (a[:-1] <= b[:-1])
    return out


def cross_dn(a, b):
    a = np.asarray(a, float)
    b = np.broadcast_to(np.asarray(b, float), a.shape)
    out = np.zeros(len(a), bool)
    if len(a) > 1:
        out[1:] = (a[1:] < b[1:]) & (a[:-1] >= b[:-1])
    return out


def donchian(h, l, c, n):
    """(long, short): close > max(high of the prior n bars) / close < min(low of the prior n bars)."""
    T = len(c)
    lo_ = np.zeros(T, bool)
    sh = np.zeros(T, bool)
    if T <= n:
        return lo_, sh
    from numpy.lib.stride_tricks import sliding_window_view as sw
    hh = sw(h, n).max(axis=1)[:-1]        # hh[i] = max(h[i .. i+n-1]) -> used at t = i+n
    ll = sw(l, n).min(axis=1)[:-1]
    lo_[n:] = c[n:] > hh
    sh[n:] = c[n:] < ll
    return lo_, sh


def signals(kind: str, params: tuple, o, h, l, c, warm: int = K.WARM_BARS):
    """(long -> CE, short -> PE) boolean arrays on the bar series. No signal on the first `warm` bars."""
    h, l, c = (np.asarray(x, float) for x in (h, l, c))
    if kind == "ema":
        f, s, thr = params
        ef, es = ema(c, f), ema(c, s)
        ax = adx(h, l, c, 14)
        strong = np.nan_to_num(ax, nan=-1.0) > thr
        lo_, sh = cross_up(ef, es) & strong, cross_dn(ef, es) & strong
    elif kind == "rsi_fade":
        lo_lvl, hi_lvl = params
        r = rsi(c, 14)
        lo_, sh = cross_dn(r, lo_lvl), cross_up(r, hi_lvl)
    elif kind == "rsi_momo":
        up_lvl, dn_lvl = params
        r = rsi(c, 14)
        lo_, sh = cross_up(r, up_lvl), cross_dn(r, dn_lvl)
    elif kind == "donchian":
        lo_, sh = donchian(h, l, c, params[0])
    else:
        raise ValueError(kind)
    lo_ = lo_.copy()
    sh = sh.copy()
    lo_[:warm] = False
    sh[:warm] = False
    return lo_, sh


def bars5(minute: np.ndarray, day: np.ndarray, o, h, l, c, start: int, nbars: int):
    """5-minute bars from 1-minute bars. minute = minutes since midnight (bar start), day = ordinal.
    Returns dict(day, bar, open, high, low, close) of the bars that have at least one minute, in time order."""
    import pandas as pd
    b = (np.asarray(minute) - start) // 5
    ok = (b >= 0) & (b < nbars)
    df = pd.DataFrame(dict(day=np.asarray(day)[ok], bar=b[ok], m=np.asarray(minute)[ok], o=np.asarray(o)[ok],
                           h=np.asarray(h)[ok], l=np.asarray(l)[ok], c=np.asarray(c)[ok]))
    df = df.sort_values(["day", "m"])
    g = df.groupby(["day", "bar"], sort=True)
    a = g.agg(open=("o", "first"), high=("h", "max"), low=("l", "min"), close=("c", "last")).reset_index()
    return {k: a[k].values for k in ("day", "bar", "open", "high", "low", "close")}
