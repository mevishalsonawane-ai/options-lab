"""RC1 signals, all point-in-time: a value at bar i uses bars <= i only, and a position for bar i comes from the signal at
bar i-1 (decided at its close, traded at bar i's open). test_rc1.py checks this by changing the future."""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

import config as K


def _roll_mean(x: np.ndarray, n: int) -> np.ndarray:
    s = pd.Series(x).rolling(n, min_periods=n).mean()
    return s.to_numpy()


def sma_on(c: np.ndarray, n: int) -> np.ndarray:
    """1 at bar i if close i > its n-bar simple average (bars i-n+1..i)."""
    ma = _roll_mean(c, n)
    return np.where(np.isnan(ma), 0, (c > ma).astype(int))


def donchian_state(c: np.ndarray, h: np.ndarray, lo: np.ndarray, n: int) -> np.ndarray:
    """1 after a close above the prior n-bar high, until a close below the prior n-bar low."""
    hh = pd.Series(h).shift(1).rolling(n, min_periods=n).max().to_numpy()
    ll = pd.Series(lo).shift(1).rolling(n, min_periods=n).min().to_numpy()
    out = np.zeros(len(c), dtype=int)
    st = 0
    for i in range(len(c)):
        if not np.isnan(hh[i]):
            if st == 0 and c[i] > hh[i]:
                st = 1
            elif st == 1 and c[i] < ll[i]:
                st = 0
        out[i] = st
    return out


def trend_on(rule: str, c, h, lo) -> np.ndarray:
    if rule == "sma100":
        return sma_on(c, 100)
    if rule == "sma200":
        return sma_on(c, 200)
    if rule == "dc50":
        return donchian_state(c, h, lo, 50)
    raise ValueError(rule)


def to_mask(on: np.ndarray) -> np.ndarray:
    """Position during bar i = signal at close i-1."""
    m = np.zeros(len(on), dtype=int)
    m[1:] = on[:-1]
    return m


def realised_vol(c: np.ndarray, n: int = K.VOL_WINDOW) -> np.ndarray:
    r = np.diff(np.log(c), prepend=np.nan)
    return pd.Series(r).rolling(n, min_periods=n).std().to_numpy() * math.sqrt(365)


def vol_weights(on: np.ndarray, c: np.ndarray) -> np.ndarray:
    """Target share of equity decided at close i (0.5 x min(1, 40% / 30-day vol) while the trend is on)."""
    rv = realised_vol(c)
    w = 0.5 * np.minimum(1.0, K.VOL_TARGET / np.where(np.isnan(rv) | (rv <= 0), np.inf, rv))
    return np.where(on.astype(bool), w, 0.0)


def shift_weights(w: np.ndarray) -> np.ndarray:
    out = np.zeros(len(w))
    out[1:] = w[:-1]
    return out


def atr(h, lo, c, n=K.ATR_N) -> np.ndarray:
    pc = np.concatenate([[np.nan], c[:-1]])
    tr = np.nanmax(np.vstack([h - lo, np.abs(h - pc), np.abs(lo - pc)]), axis=0)
    return pd.Series(tr).rolling(n, min_periods=n).mean().to_numpy()


def breakout_spells(o, h, lo, c, start: int = 0) -> list:
    """Idea g on 4h bars: enter at open i+1 after close i > highest high of bars i-20..i-1; stop = entry - 2 ATR(14) at
    bar i (fills at the stop, or the open if it gaps through); else exit at open j+1 after close j < lowest low of
    bars j-10..j-1. Returns [(entry bar, exit bar, stop fill or None)]; exit bar = len for 'still held'."""
    n = len(c)
    hh = pd.Series(h).shift(1).rolling(K.BRK_N, min_periods=K.BRK_N).max().to_numpy()
    ll = pd.Series(lo).shift(1).rolling(K.BRK_EXIT_N, min_periods=K.BRK_EXIT_N).min().to_numpy()
    a = atr(h, lo, c)
    out = []
    i = max(start, 0)
    while i < n - 1:
        if np.isnan(hh[i]) or np.isnan(a[i]) or not c[i] > hh[i]:
            i += 1
            continue
        e = i + 1
        stop = o[e] - K.ATR_K * a[i]
        spell = (e, n, None)
        for j in range(e, n):
            if lo[j] <= stop:                       # stopped inside bar j; a new signal can come from close j
                spell = (e, j, min(o[j], stop))
                i = j
                break
            if not np.isnan(ll[j]) and c[j] < ll[j]:  # exit at open j+1; a new signal can come from close j+1
                spell = (e, j + 1, None)
                i = j + 1
                break
        out.append(spell)
        if spell[1] >= n:
            break
    return out


def carry_state(fund_daily: np.ndarray) -> np.ndarray:
    """1 while the mean of the last 9 prints (3 days x 3), annualised, has gone above 10% and not yet below 0."""
    s = pd.Series(fund_daily).rolling(3, min_periods=3).sum().to_numpy() / 9 * 3 * 365
    out = np.zeros(len(s), dtype=int)
    st = 0
    for i, x in enumerate(s):
        if not np.isnan(x):
            if st == 0 and x > K.CARRY_IN:
                st = 1
            elif st == 1 and x < K.CARRY_OUT:
                st = 0
        out[i] = st
    return out


# ------------------------------------------------------------------------------------------------ twins


def shuffle_mask(mask: np.ndarray, rng: np.random.Generator) -> np.ndarray:
    """Same in-market spells (number and lengths) and gaps, in random order."""
    sp, gaps, i, n = [], [], 0, len(mask)
    g = 0
    while i < n:
        if mask[i]:
            j = i
            while j < n and mask[j]:
                j += 1
            gaps.append(g)
            sp.append(j - i)
            g = 0
            i = j
        else:
            g += 1
            i += 1
    gaps.append(g)
    rng.shuffle(sp)
    rng.shuffle(gaps)
    out = np.zeros(n, dtype=int)
    pos = 0
    for k in range(len(sp)):
        pos += gaps[k]
        out[pos:pos + sp[k]] = 1
        pos += sp[k]
    return out


def spells_to_mask(spells: list, n: int) -> np.ndarray:
    m = np.zeros(n, dtype=int)
    for a, e, *_ in spells:
        m[a:min(e, n)] = 1
    return m


# ------------------------------------------------------------------------------------------------ day of week (idea e)


def bh(p: list, q: float = 0.05) -> np.ndarray:
    """Benjamini-Hochberg adjusted q-values."""
    p = np.asarray(p, dtype=float)
    n = len(p)
    order = np.argsort(p)
    adj = np.empty(n)
    run = 1.0
    for rank in range(n, 0, -1):
        k = order[rank - 1]
        run = min(run, p[k] * n / rank)
        adj[k] = run
    return adj


def welch_p(x: np.ndarray, y: np.ndarray) -> tuple[float, float]:
    from scipy import stats
    t, p = stats.ttest_ind(x, y, equal_var=False)
    return float(t), float(p)


def dow_tests(dates: list, c: np.ndarray, coin: str) -> list:
    r = np.diff(np.log(c))
    wd = np.array([d.weekday() for d in dates[1:]])
    rows = []
    for k, name in enumerate(["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]):
        x, y = r[wd == k], r[wd != k]
        t, p = welch_p(x, y)
        rows.append(dict(coin=coin, test=name, n=len(x), mean_bp=x.mean() * 1e4, rest_bp=y.mean() * 1e4, t=t, p=p))
    x, y = r[wd >= 5], r[wd < 5]
    t, p = welch_p(x, y)
    rows.append(dict(coin=coin, test="Sat+Sun", n=len(x), mean_bp=x.mean() * 1e4, rest_bp=y.mean() * 1e4, t=t, p=p))
    return rows
