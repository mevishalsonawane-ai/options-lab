"""R43 signals: Boss's 7 sweep families on the 5-minute INDEX bars (PREREG.md "Signals"). Signal source: the index
spot (never an option's chart).

Indicators follow TradingView Pine (the sweep's source):
  - ema(x, n): alpha 2/(n+1), seeded with the simple average of the first n values (ta.ema);
  - rma(x, n): Wilder's average, alpha 1/n, seeded the same way (ta.rma);
  - rsi(close, 14): rma of gains / rma of losses (ta.rsi; 100 when there are no losses, 0 when no gains);
  - adx(14): ta.dmi(14, 14): +DM / -DM from the change of high / low, true range, each smoothed with rma, DX smoothed with
    rma (the first bar has no true range: no previous close);
  - crossover(a, b) = a > b and a[1] <= b[1]; crossunder(a, b) = a < b and a[1] >= b[1].
Every value at bar i uses bars <= i only; the series runs over EVERY session, so day 1's first bars are warmed up by
earlier days (no reset at the open).

Signal: +1 = buy a CE, -1 = buy a PE, 0 = nothing, read at the bar's close.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

FAMILIES = ("ema821_adx15", "ema921_adx20", "ema926_adx20", "rsi_fade", "rsi_momo", "donchian20")


def _seeded(x: np.ndarray, n: int, alpha: float) -> np.ndarray:
    x = np.asarray(x, dtype=float)
    out = np.full(len(x), np.nan)
    ok = np.nonzero(~np.isnan(x))[0]
    if len(ok) < n:
        return out
    i0 = ok[0]
    # the seed needs n consecutive valid values from the first valid one (as Pine's sma over a window with no na)
    seg = x[i0:i0 + n]
    if np.isnan(seg).any() or len(seg) < n:
        return out
    s = float(seg.mean())
    out[i0 + n - 1] = s
    for i in range(i0 + n, len(x)):
        v = x[i]
        if not np.isnan(v):
            s = alpha * v + (1 - alpha) * s
        out[i] = s
    return out


def ema(x, n: int) -> np.ndarray:
    return _seeded(x, n, 2.0 / (n + 1))


def rma(x, n: int) -> np.ndarray:
    return _seeded(x, n, 1.0 / n)


def rsi(c, n: int = 14) -> np.ndarray:
    c = np.asarray(c, dtype=float)
    ch = np.full(len(c), np.nan)
    ch[1:] = np.diff(c)
    up = rma(np.where(np.isnan(ch), np.nan, np.maximum(ch, 0.0)), n)
    dn = rma(np.where(np.isnan(ch), np.nan, np.maximum(-ch, 0.0)), n)
    with np.errstate(divide="ignore", invalid="ignore"):
        out = 100.0 - 100.0 / (1.0 + up / dn)
    out = np.where(dn == 0, 100.0, out)
    out = np.where((up == 0) & (dn > 0), 0.0, out)
    return np.where(np.isnan(up) | np.isnan(dn), np.nan, out)


def adx(h, l, c, n: int = 14) -> np.ndarray:
    h, l, c = (np.asarray(a, dtype=float) for a in (h, l, c))
    m = len(c)
    up = np.full(m, np.nan)
    dn = np.full(m, np.nan)
    tr = np.full(m, np.nan)
    up[1:] = h[1:] - h[:-1]
    dn[1:] = -(l[1:] - l[:-1])
    tr[1:] = np.maximum.reduce([h[1:] - l[1:], np.abs(h[1:] - c[:-1]), np.abs(l[1:] - c[:-1])])
    pdm = np.where(np.isnan(up), np.nan, np.where((up > dn) & (up > 0), up, 0.0))
    mdm = np.where(np.isnan(dn), np.nan, np.where((dn > up) & (dn > 0), dn, 0.0))
    atr = rma(tr, n)
    with np.errstate(divide="ignore", invalid="ignore"):
        pdi = 100 * rma(pdm, n) / atr
        mdi = 100 * rma(mdm, n) / atr
        s = pdi + mdi
        dx = np.abs(pdi - mdi) / np.where(s == 0, 1.0, s)
    return 100 * rma(dx, n)


def crossover(a, b) -> np.ndarray:
    a, b = np.asarray(a, float), np.broadcast_to(np.asarray(b, float), np.shape(a))
    out = np.zeros(len(a), dtype=bool)
    out[1:] = (a[1:] > b[1:]) & (a[:-1] <= b[:-1])
    return out


def crossunder(a, b) -> np.ndarray:
    a, b = np.asarray(a, float), np.broadcast_to(np.asarray(b, float), np.shape(a))
    out = np.zeros(len(a), dtype=bool)
    out[1:] = (a[1:] < b[1:]) & (a[:-1] >= b[:-1])
    return out


def signals(bars: pd.DataFrame) -> pd.DataFrame:
    """bars: 5-minute bars (o h l c) in time order. Returns a frame of the same rows with one int column per family."""
    h, l, c = bars.h.values, bars.l.values, bars.c.values
    out = pd.DataFrame(index=bars.index)
    ax = adx(h, l, c, 14)
    e = {n: ema(c, n) for n in (8, 9, 21, 26)}
    for name, f, s, th in (("ema821_adx15", 8, 21, 15), ("ema921_adx20", 9, 21, 20), ("ema926_adx20", 9, 26, 20)):
        strong = ax > th
        out[name] = np.where(crossover(e[f], e[s]) & strong, 1, np.where(crossunder(e[f], e[s]) & strong, -1, 0))
    r = rsi(c, 14)
    out["rsi_fade"] = np.where(crossunder(r, 30), 1, np.where(crossover(r, 70), -1, 0))
    out["rsi_momo"] = np.where(crossover(r, 55), 1, np.where(crossunder(r, 45), -1, 0))
    hh = pd.Series(h).shift(1).rolling(20, min_periods=20).max().values
    ll = pd.Series(l).shift(1).rolling(20, min_periods=20).min().values
    out["donchian20"] = np.where(c > hh, 1, np.where(c < ll, -1, 0))
    out["ema821_adx15_lock"] = out["ema821_adx15"]          # variant 1b: the same entries, its own exit
    return out.astype(int)


def day_signals(bars: pd.DataFrame, sig: pd.DataFrame, d) -> dict:
    """{family: {bar j: signal}} for session d (non-zero signals only)."""
    m = (bars.d == d).values
    js = bars.j.values[m]
    out = {}
    for f in sig.columns:
        v = sig[f].values[m]
        out[f] = {int(j): int(s) for j, s in zip(js, v) if s != 0}
    return out
