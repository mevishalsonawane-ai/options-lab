"""R130 Amendment 2 tests: the profit lock (point in time), the 15-minute trend (look-ahead guard), the 1-ITM strike and
the major-liquidity levels.

    python -m pytest research/hunt/r130_sweep_mss_fvg -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r130 as R  # noqa: E402
import r130enh as E  # noqa: E402

N = R.N


def _opt(prices_hi, prices_lo, opens=None):
    a = np.full((N, 5), np.nan)
    n = len(prices_hi)
    a[:n, 1], a[:n, 2] = prices_hi, prices_lo
    a[:n, 0] = opens if opens is not None else (np.array(prices_hi) + np.array(prices_lo)) / 2
    a[:n, 3] = a[:n, 0]
    a[:n, 4] = 10000
    return a


def test_lock_arms_on_earlier_highs_and_fills_at_the_lock():
    # bought at 100, lot 65: +Rs 1,000 needs a high of 115.39; minute 3's high 120 = +Rs 1,300 -> lock at 112
    hi = [100, 104, 108, 120, 118, 114, 113]
    lo = [99, 101, 105, 112.5, 113, 111, 110]
    op = [100, 103, 107, 115, 117, 113, 111]
    a = _opt(hi, lo, op)
    assert E.lock_exit(a, 0, 6, 100.0, 65, (1000.0, 0.6)) == (5, 112.0)
    op2 = list(op)
    op2[5] = 111.0                                           # gapped below the lock: filled at the open
    assert E.lock_exit(_opt(hi, lo, op2), 0, 6, 100.0, 65, (1000.0, 0.6)) == (5, 111.0)


def test_lock_never_uses_the_same_minutes_high():
    hi = [100, 120, 101]
    lo = [99, 105, 100]                                      # minute 1 makes +1,300 AND dips to 105 (< 112)
    a = _opt(hi, lo)
    assert E.lock_exit(a, 0, 1, 100.0, 65, (1000.0, 0.6)) is None    # not armed inside minute 1 itself
    assert E.lock_exit(a, 0, 2, 100.0, 65, (1000.0, 0.6)) == (2, 100.5)  # armed from minute 2: low 100 hits 112


def test_lock_not_armed_below_trigger():
    a = _opt([100, 110, 112, 101], [99, 105, 104, 95])
    assert E.lock_exit(a, 0, 3, 100.0, 65, (1000.0, 0.6)) is None       # best +780 < 1,000
    assert E.lock_exit(a, 0, 3, 100.0, 65, (500.0, 0.6)) is not None    # the +500 lock is armed


def test_option_locks_returns_one_result_per_lock():
    hi = [100, 104, 108, 120, 118, 114, 113] + [113] * 10
    lo = [99, 101, 105, 112.5, 113, 111, 110] + [90] * 10
    r = E.option_locks(_opt(hi, lo), 0, 15, "time", 65, date(2025, 6, 10), "NIFTY")
    assert len(r) == 3
    assert r[1][0] > r[0][0]                                  # the lock beats riding it down to the close


def test_strike_itm():
    assert E.strike(25012.0, 50.0, 1, 0) == 25000.0
    assert E.strike(25012.0, 50.0, 1, 1) == 24950.0           # 1-ITM call: one step below
    assert E.strike(25012.0, 50.0, -1, 1) == 25050.0          # 1-ITM put: one step above


def _wave_days(nd=3, seed=1):
    rng = np.random.default_rng(seed)
    c = 1000 + np.cumsum(rng.normal(0, 1, nd * N))
    h, l = c + rng.random(nd * N), c - rng.random(nd * N)
    o = np.r_[c[0], c[:-1]]
    sh = lambda x: x.reshape(nd, N).copy()  # noqa: E731
    return SimpleNamespace(days=[date(2025, 6, 10 + i) for i in range(nd)], o=sh(o), h=sh(h), l=sh(l), c=sh(c))


def test_trend_grid_look_ahead_guard():
    g = _wave_days()
    full = E.trend_grid(g)
    assert set(np.unique(full)) <= {-1, 0, 1} and (full != 0).any()
    for cut in (100, 200, 300):
        g2 = _wave_days()
        for a in (g2.o, g2.h, g2.l, g2.c):
            a[2, cut:] = np.nan                                # remove day 3 after `cut`
        part = E.trend_grid(g2)
        assert (part[:2] == full[:2]).all() and (part[2, :cut] == full[2, :cut]).all()


def test_trend_known_only_after_the_15_minute_bar_ends():
    g = _wave_days()
    full = E.trend_grid(g)
    # a direction can only change at the first minute after a 15-minute bar ends (rows 15, 30, ...)
    ch = np.flatnonzero(np.diff(full.reshape(-1)) != 0) + 1
    assert all((x % N) % 15 == 0 for x in ch)


def test_major_levels_prev_low_and_opening_range():
    day0 = [(1000, 1001, 990.5, 1000)] * 75
    import test_r130 as T1                                   # the hand-built session
    g = SimpleNamespace(days=[date(2025, 6, 9), date(2025, 6, 10)])
    m0, m1 = T1.minutes(day0), T1.minutes()
    for k in ("o", "h", "l", "c"):
        setattr(g, k, np.vstack([getattr(m0, k), getattr(m1, k)]))
    B = R.make_bars(g.o, g.h, g.l, g.c, 5)
    S = E.MajorSide(B, B.h, B.l, B.o, B.c, 3, True, False, True)
    t4 = 75 + 4                                              # day 2, bar 4 (09:35): before any opening-range break
    assert S.majors(t4, 1) == [990.5, 999.0]                 # yesterday's low, the opening-range low
    t6 = 75 + 6                                              # bar 5 (low 998) took the opening-range low
    assert S.majors(t6, 1) == [990.5]
    t2 = 75 + 2                                              # inside the opening range: not usable yet
    assert S.majors(t2, 1) == [990.5]
