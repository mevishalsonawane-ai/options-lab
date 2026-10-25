"""R104 indicators: causal (a value at bar i uses bars <= i only). NaN until warm.

Swings are fractals: a swing high at bar j (high[j] above the n bars on each side) is CONFIRMED at bar j + n, never
earlier; `swings()` returns per bar the latest confirmed swing values and their bar index as known at that bar.
"""
from __future__ import annotations

import numpy as np
from numba import njit


def sma(x, n):
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if len(x) >= n:
        cs = np.cumsum(np.r_[0.0, x])
        out[n - 1:] = (cs[n:] - cs[:-n]) / n
    return out


@njit(cache=True)
def ema(x, n):
    out = np.full(len(x), np.nan)
    a = 2.0 / (n + 1.0)
    s = np.nan
    k = 0
    for i in range(len(x)):
        if x[i] != x[i]:
            out[i] = s
            continue
        if s != s:
            k += 1
            if k == n:                       # seed with the SMA of the first n values
                t = 0.0
                m = 0
                j = i
                while m < n:
                    if x[j] == x[j]:
                        t += x[j]
                        m += 1
                    j -= 1
                s = t / n
        else:
            s = s + a * (x[i] - s)
        out[i] = s
    return out


@njit(cache=True)
def wilder(x, n):
    out = np.full(len(x), np.nan)
    s = np.nan
    t = 0.0
    m = 0
    for i in range(len(x)):
        if x[i] != x[i]:
            out[i] = s
            continue
        if s != s:
            t += x[i]
            m += 1
            if m == n:
                s = t / n
        else:
            s = (s * (n - 1) + x[i]) / n
        out[i] = s
    return out


def true_range(h, l, c):
    pc = np.r_[np.nan, c[:-1]]
    tr = np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc)))
    tr[0] = h[0] - l[0]
    return tr


def atr(h, l, c, n=14):
    return wilder(true_range(h, l, c), n)


def rsi(c, n=14):
    d = np.diff(c, prepend=np.nan)
    up = wilder(np.where(np.isnan(d), np.nan, np.maximum(d, 0.0)), n)
    dn = wilder(np.where(np.isnan(d), np.nan, np.maximum(-d, 0.0)), n)
    with np.errstate(divide="ignore", invalid="ignore"):
        rs = up / dn
        out = 100.0 - 100.0 / (1.0 + rs)
    out = np.where(dn == 0, 100.0, out)
    return out


def rolling_max(x, n):
    from numpy.lib.stride_tricks import sliding_window_view
    out = np.full(len(x), np.nan)
    if len(x) >= n:
        out[n - 1:] = sliding_window_view(x, n).max(axis=1)
    return out


def rolling_min(x, n):
    from numpy.lib.stride_tricks import sliding_window_view
    out = np.full(len(x), np.nan)
    if len(x) >= n:
        out[n - 1:] = sliding_window_view(x, n).min(axis=1)
    return out


def rolling_std(x, n):
    from numpy.lib.stride_tricks import sliding_window_view
    out = np.full(len(x), np.nan)
    if len(x) >= n:
        out[n - 1:] = sliding_window_view(x, n).std(axis=1)
    return out


def cci(h, l, c, n=20):
    tp = (h + l + c) / 3.0
    from numpy.lib.stride_tricks import sliding_window_view
    out = np.full(len(c), np.nan)
    if len(c) >= n:
        w = sliding_window_view(tp, n)
        m = w.mean(axis=1)
        md = np.abs(w - m[:, None]).mean(axis=1)
        with np.errstate(divide="ignore", invalid="ignore"):
            out[n - 1:] = (tp[n - 1:] - m) / (0.015 * md)
    return out


def stoch(h, l, c, k=14, smooth=3, d=3):
    hh, ll = rolling_max(h, k), rolling_min(l, k)
    with np.errstate(divide="ignore", invalid="ignore"):
        raw = 100.0 * (c - ll) / (hh - ll)
    kk = sma(np.nan_to_num(raw, nan=50.0), smooth)
    kk[np.isnan(raw)] = np.nan
    return kk, sma(np.nan_to_num(kk, nan=50.0), d)


def dmi(h, l, c, n=14):
    """Wilder +DI, -DI, ADX."""
    up = np.r_[np.nan, h[1:] - h[:-1]]
    dn = np.r_[np.nan, l[:-1] - l[1:]]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    pdm[0] = mdm[0] = np.nan
    tr = true_range(h, l, c)
    tr[0] = np.nan
    a = wilder(tr, n)
    with np.errstate(divide="ignore", invalid="ignore"):
        pdi = 100.0 * wilder(pdm, n) / a
        mdi = 100.0 * wilder(mdm, n) / a
        dx = 100.0 * np.abs(pdi - mdi) / (pdi + mdi)
    return pdi, mdi, wilder(dx, n)


def bollinger(c, n=20, k=2.0):
    m = sma(c, n)
    sd = rolling_std(c, n)
    return m, m + k * sd, m - k * sd


def heikin_ashi(o, h, l, c):
    hc = (o + h + l + c) / 4.0
    ho = np.empty_like(o)
    ho[0] = (o[0] + c[0]) / 2.0
    for i in range(1, len(o)):
        ho[i] = (ho[i - 1] + hc[i - 1]) / 2.0
    return ho, np.maximum(h, np.maximum(ho, hc)), np.minimum(l, np.minimum(ho, hc)), hc


@njit(cache=True)
def _swings(h, l, n):
    m = len(h)
    sh = np.full(m, np.nan)       # latest confirmed swing high value known at bar i
    shi = np.full(m, -1)          # its bar
    sl = np.full(m, np.nan)
    sli = np.full(m, -1)
    ph = np.nan
    phi = -1
    pl = np.nan
    pli = -1
    for i in range(m):
        j = i - n
        if j >= n:
            ok = True
            for k in range(j - n, j + n + 1):
                if k != j and not (h[k] < h[j]):
                    if k < j and h[k] == h[j]:
                        continue
                    ok = False
                    break
            if ok:
                ph = h[j]
                phi = j
            ok = True
            for k in range(j - n, j + n + 1):
                if k != j and not (l[k] > l[j]):
                    if k < j and l[k] == l[j]:
                        continue
                    ok = False
                    break
            if ok:
                pl = l[j]
                pli = j
        sh[i] = ph
        shi[i] = phi
        sl[i] = pl
        sli[i] = pli
    return sh, shi, sl, sli


def swings(h, l, n=2):
    return _swings(np.asarray(h, float), np.asarray(l, float), n)


def swing_lists(h, l, n=2):
    """All swing highs / lows as (bar, confirmed_at, value) arrays, in time order."""
    sh, shi, sl, sli = swings(h, l, n)
    out = []
    for vi, v in ((shi, sh), (sli, sl)):
        ch = np.flatnonzero(np.r_[vi[0] >= 0, (vi[1:] != vi[:-1]) & (vi[1:] >= 0)])
        out.append((vi[ch].astype(int), ch.astype(int), v[ch]))
    return out


@njit(cache=True)
def zigzag(h, l, c, thr):
    """ZigZag pivots with a per-bar reversal threshold thr (e.g. k x ATR). Returns arrays (pivot bar, confirmed bar,
    value, type +1 high / -1 low); a pivot is confirmed at the bar where price has reversed thr from it."""
    m = len(c)
    pb = np.full(m, -1)
    cb = np.full(m, -1)
    pv = np.full(m, np.nan)
    pt = np.zeros(m, np.int64)
    k = 0
    d = 0
    hi = h[0]
    hii = 0
    lo = l[0]
    loi = 0
    for i in range(1, m):
        t = thr[i]
        if t != t:
            continue
        if d == 0:
            if h[i] > hi:
                hi = h[i]
                hii = i
            if l[i] < lo:
                lo = l[i]
                loi = i
            if h[i] - lo >= t and loi < i:
                pb[k] = loi; cb[k] = i; pv[k] = lo; pt[k] = -1; k += 1
                d = 1; hi = h[i]; hii = i
            elif hi - l[i] >= t and hii < i:
                pb[k] = hii; cb[k] = i; pv[k] = hi; pt[k] = 1; k += 1
                d = -1; lo = l[i]; loi = i
        elif d == 1:
            if h[i] > hi:
                hi = h[i]
                hii = i
            elif hi - l[i] >= t:
                pb[k] = hii; cb[k] = i; pv[k] = hi; pt[k] = 1; k += 1
                d = -1; lo = l[i]; loi = i
        else:
            if l[i] < lo:
                lo = l[i]
                loi = i
            elif h[i] - lo >= t:
                pb[k] = loi; cb[k] = i; pv[k] = lo; pt[k] = -1; k += 1
                d = 1; hi = h[i]; hii = i
    return pb[:k], cb[:k], pv[:k], pt[:k]


def linreg_channel(c, n=50, k=2.0):
    """Per bar: slope, the line's value at the bar, and the residual standard error (bars <= i)."""
    m = len(c)
    slope = np.full(m, np.nan)
    mid = np.full(m, np.nan)
    se = np.full(m, np.nan)
    x = np.arange(n, dtype=float)
    xm = x.mean()
    sxx = ((x - xm) ** 2).sum()
    for i in range(n - 1, m):
        y = c[i - n + 1:i + 1]
        ym = y.mean()
        b = ((x - xm) * (y - ym)).sum() / sxx
        a = ym - b * xm
        r = y - (a + b * x)
        slope[i], mid[i], se[i] = b, a + b * (n - 1), np.sqrt((r ** 2).sum() / (n - 2))
    return slope, mid, se


def force_index(c, v):
    return np.r_[np.nan, (c[1:] - c[:-1]) * v[1:]]


def crossed_above(a, b):
    a, b = np.asarray(a, float), np.asarray(b, float)
    out = np.zeros(len(a), bool)
    out[1:] = (a[1:] > b[1:]) & (a[:-1] <= b[:-1])
    return out


def crossed_below(a, b):
    return crossed_above(-np.asarray(a, float), -np.asarray(b, float))


def ema_trend(c, n=20, lag=5):
    """+1 when close > EMA n and EMA n above its value lag bars before; -1 mirror; else 0."""
    e = ema(np.asarray(c, float), n)
    prev = np.r_[np.full(lag, np.nan), e[:-lag]]
    up = (c > e) & (e > prev)
    dn = (c < e) & (e < prev)
    return np.where(up, 1, np.where(dn, -1, 0)), e
