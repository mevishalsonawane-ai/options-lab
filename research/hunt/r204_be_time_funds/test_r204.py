"""R204 / R205 tests (PREREG.md section 6): synthetic data only, no lake, no engine. Plain-English notes, 20 Nov 2026."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r204cfg as K  # noqa: E402
import r204lib as L  # noqa: E402

N = L.NROWS


# ================================================================================================ the early time stop
def test_no_go_row_basic():
    h = np.full(N, 100.0)
    assert L.no_go_row(h, 30, 10, 105.0) == 40                 # never reached in minutes 30..39 -> sold at 40
    h2 = h.copy()
    h2[39] = 105.0                                             # reached exactly, on the last minute of the window
    assert L.no_go_row(h2, 30, 10, 105.0) == -1
    h3 = h.copy()
    h3[40] = 500.0                                             # the sale minute itself is not read
    assert L.no_go_row(h3, 30, 10, 105.0) == 40
    assert L.no_go_row(h, 30, 15, 105.0) == 45


def test_no_go_row_missing_minutes_late_fill_no_fill():
    h = np.full(N, np.nan)
    assert L.no_go_row(h, 30, 10, 105.0) == 40                 # no print at all: not reached
    h[33] = 104.0
    assert L.no_go_row(h, 30, 10, 105.0) == 40
    h[35] = 106.0
    assert L.no_go_row(h, 30, 10, 105.0) == -1
    flat = np.full(N, 100.0)
    assert L.no_go_row(flat, N - 10, 10, 105.0) == -1          # the session has no minute fill + 10
    assert L.no_go_row(flat, N - 11, 10, 105.0) == N - 1
    assert L.no_go_row(flat, -1, 10, 105.0) == -1              # no fill
    assert L.no_go_row(flat, 30, 10, float("nan")) == -1       # no threshold


def test_no_go_row_look_ahead_guard():
    rng = np.random.default_rng(4)
    for seed in range(20):
        h = 100.0 + np.cumsum(np.random.default_rng(seed).normal(0, 0.6, N))
        ef = int(rng.integers(0, N - 30))
        for m in (10, 15):
            r = L.no_go_row(h, ef, m, h[ef] + 2.0)
            h2 = h.copy()
            h2[ef + m:] = rng.normal(1e6, 10.0, N - ef - m)     # everything from the sale minute on replaced by noise
            assert L.no_go_row(h2, ef, m, h[ef] + 2.0) == r
            assert r == -1 or r == ef + m


# ================================================================================================ the breakeven level
def test_add_level_keeps_trigger_order():
    t, l = L.add_level(np.array([110.0, 115.0]), np.array([105.0, 110.0]), 105.0, 101.0)
    assert t.tolist() == [105.0, 110.0, 115.0] and l.tolist() == [101.0, 105.0, 110.0]
    t, l = L.add_level(np.zeros(0), np.zeros(0), 105.0, 101.0)             # no lock levels at all
    assert t.tolist() == [105.0] and l.tolist() == [101.0]
    t, l = L.add_level(np.array([110.0]), np.array([100.5]), 105.0, 100.5)  # Liquidity: +500 before +1,000, same stop
    assert t.tolist() == [105.0, 110.0] and l.tolist() == [100.5, 100.5]
    t, l = L.add_level(np.array([110.0]), np.array([105.0]), 112.0, 101.0)  # a later trigger goes after
    assert t.tolist() == [110.0, 112.0] and l.tolist() == [105.0, 101.0]


def test_add_level_never_lowers_a_lock():
    """The simulator's walk: at each trigger reached the stop is the highest lock so far."""
    def stop_at(peak, t, l):
        s = -1.0
        for a, b in zip(t, l):
            if peak >= a:
                s = max(s, b)
        return s
    t0, l0 = np.array([110.0, 115.0]), np.array([105.0, 110.0])
    t1, l1 = L.add_level(t0, l0, 105.0, 101.0)
    for peak in (100.0, 104.9, 105.0, 109.0, 110.0, 114.0, 115.0, 200.0):
        assert stop_at(peak, t1, l1) >= stop_at(peak, t0, l0)
    assert stop_at(105.0, t1, l1) == 101.0 and stop_at(104.9, t1, l1) == -1.0


def test_add_level_without_a_stop_is_not_added():
    t0, l0 = np.array([110.0]), np.array([105.0])
    for lock in (-1.0, 0.0):
        t, l = L.add_level(t0, l0, 105.0, lock)
        assert t.tolist() == [110.0] and l.tolist() == [105.0]
    t, l = L.add_level(t0, l0, float("nan"), 101.0)
    assert t.tolist() == [110.0]


# ================================================================================================ the book filter
def test_keep_book():
    assert not L.keep_book(dict(und="MIDCPNIFTY", book=5))
    assert L.keep_book(dict(und="MIDCPNIFTY", book=15)) and L.keep_book(dict(und="BANKNIFTY", book=5))
    assert L.keep_book(dict(und="MIDCPNIFTY"))                  # an arm with no books


# ================================================================================================ the funded replay
D1, D2 = date(2026, 1, 5), date(2026, 1, 6)


def tr(er, xr, net, cost=10000.0, und="NIFTY", side="C", strike=100.0, day=D1):
    return dict(day=day, er=er, xr=xr, net=net, rE=cost / 50.0, qty=50.0, und=und, side=side, strike=strike)


def test_funded_no_limit_takes_everything():
    by = dict(R72=[tr(5, 20, 100.0), tr(6, 30, -50.0, und="SENSEX")], LIQ=[], R51=[tr(5, 20, 100.0)])
    taken, refused, peak = L.funded_replay(by, None)
    assert len(taken) == 3 and not refused and peak == 30000.0
    assert [t["arm"] for t in taken] == ["R72", "R51", "R72"]


def test_funded_refuses_and_orders_the_same_minute():
    by = dict(R72=[tr(5, 20, 100.0)], LIQ=[tr(5, 20, 7.0, und="BANKNIFTY")], R51=[tr(5, 20, 100.0)])
    taken, refused, peak = L.funded_replay(by, 20000.0)
    assert [t["arm"] for t in taken] == ["R72", "LIQ"] and [t["arm"] for t in refused] == ["R51"] and peak == 20000.0
    taken, refused, _ = L.funded_replay(by, 9999.0)             # not even one lot
    assert not taken and len(refused) == 3
    taken, refused, _ = L.funded_replay(by, 10000.0)            # exactly one lot: the first in the order
    assert [t["arm"] for t in taken] == ["R72"]


def test_funded_sale_money_comes_back_before_the_next_entry():
    by = dict(R72=[tr(5, 20, -2000.0), tr(20, 40, 0.0, und="SENSEX")], LIQ=[], R51=[])
    taken, refused, _ = L.funded_replay(by, 12000.0)            # 10,000 - 2,000 back at minute 20 + 2,000 idle = 10,000
    assert len(taken) == 2 and not refused
    by = dict(R72=[tr(5, 20, -2500.0), tr(20, 40, 0.0, und="SENSEX")], LIQ=[], R51=[])
    taken, refused, _ = L.funded_replay(by, 12000.0)            # only 9,500 free at minute 20: the loss took the rest
    assert len(taken) == 1 and len(refused) == 1
    by = dict(R72=[tr(5, 21, 0.0), tr(20, 40, 0.0, und="SENSEX")], LIQ=[], R51=[])
    taken, refused, _ = L.funded_replay(by, 12000.0)            # the first is still open at minute 20
    assert len(taken) == 1 and len(refused) == 1


def test_funded_profit_can_be_used_the_same_day_and_the_day_resets():
    by = dict(R72=[tr(5, 10, +5000.0), tr(12, 30, 0.0, cost=14000.0, und="SENSEX")], LIQ=[], R51=[])
    taken, refused, _ = L.funded_replay(by, 10000.0)
    assert len(taken) == 2                                       # 15,000 free after the win
    by = dict(R72=[tr(5, 10, +5000.0), tr(12, 30, 0.0, cost=14000.0, und="SENSEX", day=D2)], LIQ=[], R51=[])
    taken, refused, _ = L.funded_replay(by, 10000.0)
    assert len(taken) == 1 and len(refused) == 1                 # the next day starts at the account size again
    by = dict(R72=[tr(5, 10, -9000.0), tr(5, 10, 0.0, day=D2)], LIQ=[], R51=[])
    assert len(L.funded_replay(by, 10000.0)[0]) == 2             # a loss is not carried to the next day either


def test_funded_empty_and_instant_exit():
    assert L.funded_replay(dict(R72=[], LIQ=[], R51=[]), 56000.0) == ([], [], 0.0)
    by = dict(R72=[tr(5, 5, 0.0), tr(5, 9, 0.0, und="SENSEX")], LIQ=[], R51=[])
    taken, refused, _ = L.funded_replay(by, 10000.0)            # an exit at its own fill minute still held the money
    assert len(taken) == 1 and len(refused) == 1


def test_settings():
    assert len(K.TESTS) == 10 and K.M_TOTAL == 4879 and K.ACCOUNTS[0] == 56000.0 and K.ACCOUNTS[-1] is None
    assert K.ARM_ORDER == ("R72", "LIQ", "R51") and set(K.ARM_ORDER) == set(K.ARMS)
    assert ("LIQ", "NOM5") in K.TESTS and ("R72", "NOM5") not in K.TESTS
