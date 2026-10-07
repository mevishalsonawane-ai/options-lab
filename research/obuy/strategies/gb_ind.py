"""Indicator helpers for the gb_* strategies (catalog families trend_indicator TI-* and mean_reversion MR-*).

Bars(ix, tf): tf-minute candles anchored at 09:15 for every day of an index, as ONE continuous series (indicators run
across days, as on a TradingView chart). A bar's signal minute is its last printed 1-minute bar: the engine fills at the
next minute's open, so nothing after the candle's close is used.

VWAP: the index has no volume, so every 'VWAP' here is a TWAP = the running mean of the 1-minute typical price
(h + l + c) / 3 since 09:15, and the 'VWAP bands' use the running standard deviation of that typical price.
"""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd

from .. import config as C

CUT = 14 * 60 + 29          # last signal minute for new entries (entry at 14:30)


class Bars:
    def __init__(self, ix, tf):
        M = ix.mat()
        nd = M["c"].shape[0]
        nb = -(-C.W // tf)
        pad = nb * tf - C.W

        def P(a):
            if pad:
                a = np.concatenate([a, np.full((nd, pad), np.nan)], axis=1)
            return a.reshape(nd, nb, tf)

        o, h, l, c = (P(M[k]) for k in "ohlc")
        ok = ~np.isnan(c)
        anyok = ok.any(axis=2)
        first = ok.argmax(axis=2)
        last = tf - 1 - ok[:, :, ::-1].argmax(axis=2)
        D, B = np.nonzero(anyok)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            hh, ll = np.nanmax(h, axis=2), np.nanmin(l, axis=2)
        self.di, self.bi = D, B
        self.o = o[D, B, first[D, B]]
        self.c = c[D, B, last[D, B]]
        self.h, self.l = hh[D, B], ll[D, B]
        self.col = B * tf + last[D, B]
        self.s = B * tf
        self.e = np.minimum(B * tf + tf, C.W)
        self.sig = self.col + C.OPEN_M
        self.days = ix.days
        self.day = np.array(ix.days, dtype=object)[D]
        self.real = np.array([bool(ix.d[d]["real"]) for d in ix.days])[D]
        self.newday = np.r_[True, D[1:] != D[:-1]]
        self.M, self.tf, self.n = M, tf, len(D)

    def twap(self):
        """(twap, sd) at each bar's close: running mean / std of the 1-minute typical price since 09:15."""
        tw, sd = twap_minutes(self.M)
        return tw[self.di, self.col], sd[self.di, self.col]

    def ok(self, cut=CUT, lo=None):
        m = self.real & (self.sig <= cut)
        if lo is not None:
            m &= self.sig >= lo
        return m

    def same_day_lag(self, k):
        """True where bar i-k exists and is on the same day as bar i."""
        out = np.zeros(self.n, bool)
        if k < self.n:
            out[k:] = self.di[k:] == self.di[:-k]
        return out


def twap_minutes(M):
    tp = (M["h"] + M["l"] + M["c"]) / 3
    v = ~np.isnan(tp)
    cnt = np.cumsum(v, axis=1)
    s1 = np.cumsum(np.where(v, tp, 0.0), axis=1)
    s2 = np.cumsum(np.where(v, tp * tp, 0.0), axis=1)
    with np.errstate(all="ignore"):
        tw = s1 / cnt
        sd = np.sqrt(np.maximum(s2 / cnt - tw * tw, 0.0))
    return tw, sd


def shift(x, k=1, fill=np.nan):
    out = np.empty_like(x, dtype=float)
    out[:k] = fill
    out[k:] = x[:-k]
    return out


def ema(x, n):
    return pd.Series(x).ewm(span=n, adjust=False).mean().values


def rma(x, n):
    return pd.Series(x).ewm(alpha=1.0 / n, adjust=False).mean().values


def sma(x, n):
    return pd.Series(x).rolling(n).mean().values


def stdev(x, n):
    return pd.Series(x).rolling(n).std(ddof=0).values


def true_range(h, l, c):
    pc = shift(c)
    tr = np.fmax(h - l, np.fmax(np.abs(h - pc), np.abs(l - pc)))
    tr[0] = h[0] - l[0]
    return tr


def atr(h, l, c, n):
    return rma(true_range(h, l, c), n)


def rsi(c, n):
    d = np.diff(c, prepend=c[0])
    up, dn = rma(np.maximum(d, 0), n), rma(np.maximum(-d, 0), n)
    with np.errstate(all="ignore"):
        r = 100 - 100 / (1 + up / dn)
    return np.where(dn == 0, 100.0, r)


def supertrend(h, l, c, n=10, mult=3.0):
    """TradingView ta.supertrend: returns (direction +1 up / -1 down, line)."""
    a = atr(h, l, c, n)
    hl2 = (h + l) / 2
    ub0, lb0 = hl2 + mult * a, hl2 - mult * a
    N = len(c)
    ub, lb = ub0.copy(), lb0.copy()
    d = np.ones(N, np.int8)
    line = np.empty(N)
    for i in range(1, N):
        if not (lb0[i] > lb[i - 1] or c[i - 1] < lb[i - 1]):
            lb[i] = lb[i - 1]
        if not (ub0[i] < ub[i - 1] or c[i - 1] > ub[i - 1]):
            ub[i] = ub[i - 1]
        if d[i - 1] == -1:
            d[i] = 1 if c[i] > ub[i - 1] else -1
        else:
            d[i] = -1 if c[i] < lb[i - 1] else 1
    line = np.where(d == 1, lb, ub)
    return d, line


def adx(h, l, c, n=14):
    """(adx, +DI, -DI), Wilder smoothing."""
    up = h - shift(h)
    dn = shift(l) - l
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    pdm[0] = mdm[0] = 0.0
    a = atr(h, l, c, n)
    with np.errstate(all="ignore"):
        pdi = 100 * rma(pdm, n) / a
        mdi = 100 * rma(mdm, n) / a
        dx = 100 * np.abs(pdi - mdi) / (pdi + mdi)
    dx = np.nan_to_num(dx)
    return rma(dx, n), pdi, mdi


def heikin_ashi(o, h, l, c):
    hc = (o + h + l + c) / 4
    ho = np.empty_like(hc)
    ho[0] = (o[0] + c[0]) / 2
    for i in range(1, len(hc)):
        ho[i] = (ho[i - 1] + hc[i - 1]) / 2
    hh = np.fmax(h, np.fmax(ho, hc))
    hl = np.fmin(l, np.fmin(ho, hc))
    return ho, hh, hl, hc


def cross_up(a, b):
    """a crosses above b at bar i (a > b now, a <= b on the previous bar)."""
    pa, pb = shift(a), shift(b) if np.ndim(b) else b
    return (a > b) & (pa <= pb)


def cross_dn(a, b):
    pa, pb = shift(a), shift(b) if np.ndim(b) else b
    return (a < b) & (pa >= pb)


def next_event(ev, di):
    """For each bar i: the first bar j > i on the same day with ev[j], else -1."""
    n = len(ev)
    idx = np.where(ev, np.arange(n), n)
    nxt = np.minimum.accumulate(idx[::-1])[::-1]
    nxt = np.r_[nxt[1:], n]
    ok = nxt < n
    same = np.zeros(n, bool)
    same[ok] = di[nxt[ok]] == di[ok]
    return np.where(same, nxt, -1)


def exit_at_from(nxt, sig):
    """exit_at minute (engine: closed at that minute's open) = the minute after the exit bar's close; NaN if none."""
    return np.where(nxt >= 0, sig[np.maximum(nxt, 0)] + 1, np.nan).astype(float)


def rolling_min(x, k):
    return pd.Series(x).rolling(k, min_periods=1).min().values


def rolling_max(x, k):
    return pd.Series(x).rolling(k, min_periods=1).max().values


def frame(und, b, idx, side, book, idx_stop=None, idx_target=None, exit_at=None, sig_min=None, ref_spot=None, tag="",
          per_side=False):
    """Signals DataFrame for bars idx of Bars b."""
    idx = np.asarray(idx, dtype=np.int64)
    n = len(idx)
    if n == 0:
        return pd.DataFrame()
    pick = lambda a: np.full(n, np.nan) if a is None else (np.asarray(a, float)[idx] if np.ndim(a) else np.full(n, float(a)))  # noqa: E731
    side_a = np.asarray(side)[idx] if np.ndim(side) else np.full(n, side)
    df = pd.DataFrame(dict(und=und, day=b.day[idx], sig_min=(b.sig[idx] if sig_min is None else np.asarray(sig_min)[idx]).astype(int),
                           side=side_a.astype(int), idx_stop=pick(idx_stop), idx_target=pick(idx_target), exit_at=pick(exit_at)))
    if ref_spot is not None:
        df["ref_spot"] = pick(ref_spot)
    df["book"] = [f"{book}_{und}_{'C' if s > 0 else 'P'}" for s in df.side] if per_side else f"{book}_{und}"
    df["tag"] = tag
    return df


def finish(parts, years=None):
    """Concatenate signal frames; years: keep only these calendar years (smoke tests)."""
    parts = [p for p in parts if len(p)]
    if not parts:
        return pd.DataFrame(columns=["und", "day", "sig_min", "side"])
    out = pd.concat(parts, ignore_index=True)
    if years:
        out = out[[d.year in years for d in out.day]]
    return out.sort_values(["und", "day", "sig_min", "side"], kind="stable").reset_index(drop=True)
