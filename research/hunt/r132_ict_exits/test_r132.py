"""Tests for R132's exit simulator (PREREG.md): premium stop, the 60% lock after +Rs 1,000, index stop, breakeven
trail, same-minute order, gaps, the time exit, and a look-ahead guard. No lake data needed."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r132 as Q  # noqa: E402

N = Q.N
LOT = 65
D0 = date(2025, 6, 2)


def opt(path, r0=10, vol=5000.0):
    """An option array: minute r0 + i has open = high = low = close = path[i] unless path[i] is a tuple (o, h, l, c)."""
    a = np.full((N, 5), np.nan)
    for i, p in enumerate(path):
        o, h, l, c = p if isinstance(p, tuple) else (p, p, p, p)
        a[r0 + i] = (o, h, l, c, vol)
    cv, li = Q.R._contract_aux(a)
    return a, cv, li


def flat_index(level=100.0):
    return np.full(N, level + 0.5), np.full(N, level - 0.5)


def run(a, cv, li, ex, r0=10, ref=100.0, stop=95.0, mh=None, ml=None, last=Q.EXIT_LAST):
    if mh is None:
        mh, ml = flat_index()
    return Q.sim_exit(a, cv, li, mh, ml, r0, ref, stop, Q.EXITS[ex], LOT, D0, "NIFTY", last)


def buy_price(p, cv=None, r=10):
    v = 0.0 if cv is None else Q._vol5(cv, r) / LOT
    return float(Q.D3.FILLS.buy(np.array([p]), np.array([v]))[0])


def sell_price(p, cv, r, stop=True):
    return float(Q.D3.FILLS.sell(np.array([p]), np.array([Q._vol5(cv, r) / LOT]), stop=stop)[0])


def net_of(B, s):
    return (s - B) * LOT - Q.D3.charge(True, B, LOT, D0, "NIFTY") - Q.D3.charge(False, s, LOT, D0, "NIFTY")


def test_premium_stop_fills_at_its_level():
    B = buy_price(100, None)
    lvl = B - 1000 / LOT
    a, cv, li = opt([100, 99, (98, 98, lvl - 1, 97)] + [97] * 20)
    m, why, net, net2 = run(a, cv, li, "E1")
    assert (m, why) == (12, "pstop")
    assert net == pytest.approx(net_of(B, sell_price(lvl, cv, 12)))
    assert net2 < net


def test_premium_stop_gap_fills_at_the_open():
    B = buy_price(100, None)
    lvl = B - 1000 / LOT
    a, cv, li = opt([100, (lvl - 5, lvl - 5, lvl - 6, lvl - 5)] + [80] * 10)
    m, why, net, _ = run(a, cv, li, "E1")
    assert (m, why) == (11, "pstop") and net == pytest.approx(net_of(B, sell_price(lvl - 5, cv, 11)))


def test_lock_keeps_60_percent_of_the_peak_after_1000():
    B = buy_price(100, None)
    up = B + 1500 / LOT                                  # +Rs 1,500 open profit at the high
    a, cv, li = opt([100, (101, up, 101, up - 1), (up - 1, up - 1, B, B + 1)] + [B] * 10)
    m, why, net, _ = run(a, cv, li, "E2")
    lock = B + 0.6 * 1500 / LOT
    assert (m, why) == (12, "lock")
    assert net == pytest.approx(net_of(B, sell_price(lock, cv, 12)))


def test_lock_ignores_the_same_minutes_high():
    B = buy_price(100, None)
    up = B + 1500 / LOT
    # the peak and the drop are in the SAME minute 11: the lock is not armed yet there; it fires at minute 12
    a, cv, li = opt([100, (101, up, B + 1, B + 2)] + [B + 2] * 10)
    m, why, _, _ = run(a, cv, li, "E3", last=25)
    assert (m, why) == (12, "lock")


def test_lock_not_armed_below_1000():
    B = buy_price(100, None)
    up = B + 900 / LOT
    a, cv, li = opt([100, up, B + 0.5] + [B + 0.5] * 10)
    assert run(a, cv, li, "E3", last=25)[1] == "time"


def test_index_stop_and_the_worse_of_two_in_one_minute():
    a, cv, li = opt([100] * 30)
    mh, ml = flat_index()
    ml[15] = 94.0                                        # the index stop (95) is hit at minute 15
    m, why, _, _ = run(a, cv, li, "E3", mh=mh, ml=ml)
    assert (m, why) == (15, "istop")
    assert run(a, cv, li, "E1", mh=mh, ml=ml)[1] == "time"     # E1 has no index stop


def test_breakeven_then_trail():
    a, cv, li = opt([100] * 40)
    mh, ml = flat_index()
    mh[:], ml[:] = 101.5, 100.6                          # quiet above the entry
    mh[12] = 105.5                                       # best 105.5 = 1.1R (R = 5): the stop moves to 100.5
    ml[14] = 100.4                                       # at or below 100.5: out
    assert run(a, cv, li, "E4", mh=mh, ml=ml)[:2] == (14, "istop")
    mh2, ml2 = flat_index()
    mh2[12] = 104.0                                      # 0.8R only: the stop stays at 95
    ml2[14] = 100.4
    assert run(a, cv, li, "E4", mh=mh2, ml=ml2)[1] == "time"


def test_time_exit_at_the_last_traded_close():
    a, cv, li = opt([100, 101, 102] + [np.nan] * 0, r0=10)
    m, why, _, _ = run(a, cv, li, "E1", last=50)
    assert (m, why) == (12, "time")                      # no trade after minute 12


def test_no_price_at_entry():
    a, cv, li = opt([100] * 5, r0=12)
    assert run(a, cv, li, "E1", r0=10) is None


def test_lookahead_guard_exit_does_not_change_when_later_minutes_are_removed():
    rng = np.random.default_rng(132)
    for _ in range(200):
        path = list(np.maximum(100 + np.cumsum(rng.normal(0, 3, 200)), 1.0))
        a, cv, li = opt(path)
        mh = 100.5 + np.cumsum(rng.normal(0, 0.8, N))
        ml = mh - 1.0
        for ex in Q.EXITS:
            full = run(a, cv, li, ex, mh=mh, ml=ml)
            m = full[0]
            if full[1] == "time":
                continue
            a2 = a.copy()
            a2[m + 1:] = np.nan
            cv2, li2 = Q.R._contract_aux(a2)
            mh2, ml2 = mh.copy(), ml.copy()
            mh2[m + 1:] = np.nan
            ml2[m + 1:] = np.nan
            cut = run(a2, cv2, li2, ex, mh=mh2, ml=ml2)
            assert cut[:2] == full[:2] and cut[2] == pytest.approx(full[2]), ex


def test_forty_exit_variants():
    assert len(Q.VARIANTS) == 40 and len(Q.ENTRIES) == 10 and set(Q.EXITS) == {"E1", "E2", "E3", "E4"}
