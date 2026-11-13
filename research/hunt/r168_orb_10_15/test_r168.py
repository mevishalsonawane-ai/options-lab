"""R168 tests (PREREG section 9): signals, the look-ahead guard, the taking walk, the B0 simulator, stats and gates.

    python -m pytest research/hunt/r168_orb_10_15 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r168cfg as K  # noqa: E402
import r168signals as SG  # noqa: E402
import r168engine as X  # noqa: E402
import r168study as S  # noqa: E402

NR = 375


def flat_day(level=100.0):
    """A (4, 375) index day flat at `level` with a 1-point range a minute."""
    a = np.full((4, NR), level, dtype=float)
    a[1] += 0.5
    a[2] -= 0.5
    return a


def set_bar(a, k, o, h, l, c):
    """Make 5-minute bar k open o, high h, low l, close c (its minutes in between)."""
    r = slice(5 * k, 5 * k + 5)
    a[0, r] = o
    a[3, r] = o
    a[1, r] = max(o, c)
    a[2, r] = min(o, c)
    a[1, 5 * k + 2] = h
    a[2, 5 * k + 3] = l
    a[3, 5 * k + 4] = c


# ------------------------------------------------------------------------------------------------ range and strike
def test_range_strike_and_skip():
    a = flat_day(1000.0)
    set_bar(a, 3, 1000, 1010, 995, 1000)
    s = SG.day_setup(a, "BANKNIFTY")
    assert s["orh"] == 1010 and s["orl"] == 995
    assert s["strike"] == 1000.0
    a2 = a.copy()
    a2[:, 0:15] = np.nan                       # 3 empty range bars -> skipped
    assert SG.day_setup(a2, "BANKNIFTY") is None
    a3 = a.copy()
    a3[3, 9] = np.nan                          # no 09:20 close -> skipped
    assert SG.day_setup(a3, "BANKNIFTY") is None
    a4 = a.copy()
    a4[1, 60:] = 2000.0                        # after 10:05: not in the range
    assert SG.day_setup(a4, "BANKNIFTY")["orh"] == 1010


def test_atm_half_up():
    assert SG.atm(45050.0, 100) == 45100.0
    assert SG.atm(45049.99, 100) == 45000.0
    assert SG.atm(22025.0, 50) == 22050.0


# ------------------------------------------------------------------------------------------------ signals
def test_orb_break_and_window():
    a = flat_day(1000.0)
    set_bar(a, 12, 1000, 1003, 999, 1002)     # close above the range high (1000.5)
    set_bar(a, 20, 1000, 1001, 990, 991)      # close below the low (999.5)
    s = SG.day_setup(a, "BANKNIFTY")
    assert SG.signal("ORB", s, 12) == 1
    assert SG.signal("ORB", s, 20) == -1
    assert SG.signal("ORB", s, 11) == 0
    set_bar(a, 57, 1000, 1003, 999, 1002)     # 14:00 bar: outside ORB's window, inside Sweep's
    s = SG.day_setup(a, "BANKNIFTY")
    assert SG.signal("ORB", s, 57) == 0
    set_bar(a, 9, 1000, 1000.5, 999.5, 1000)  # the 10:00 bar is in the range, never a decision
    assert SG.signal("ORB", SG.day_setup(a, "BANKNIFTY"), 9) == 0


def test_fresh_needs_a_fresh_break():
    a = flat_day(1000.0)
    set_bar(a, 12, 1000, 1003, 999.8, 1002)
    set_bar(a, 13, 1002, 1004, 1001, 1003)    # still above: ORB yes, Fresh no
    s = SG.day_setup(a, "BANKNIFTY")
    assert SG.signal("FRESH", s, 12) == 1
    assert SG.signal("ORB", s, 13) == 1
    assert SG.signal("FRESH", s, 13) == 0


def test_sweep_of_high_and_low():
    a = flat_day(1000.0)
    set_bar(a, 15, 1000, 1005, 999.8, 1000)   # wick above the high, close back inside -> PE
    set_bar(a, 16, 1000, 1000.2, 995, 1000)   # wick below the low, close back inside -> CE
    set_bar(a, 17, 1000, 1005, 999.8, 1003)   # closes above: a break, not a sweep
    s = SG.day_setup(a, "BANKNIFTY")
    assert SG.signal("SWEEP", s, 15) == -1
    assert SG.signal("SWEEP", s, 16) == 1
    assert SG.signal("SWEEP", s, 17) == 0
    assert SG.signal("SWEEP", s, 63) == 0     # 14:30: after its window


def test_look_ahead_guard():
    """Every arm's signal on bar k is unchanged when every minute after bar k is erased."""
    rng = np.random.default_rng(1)
    for _ in range(25):
        c = 20000 + np.cumsum(rng.normal(0, 8, NR))
        a = np.vstack([c, c + rng.uniform(0, 6, NR), c - rng.uniform(0, 6, NR), c + rng.normal(0, 2, NR)])
        a[1] = np.maximum(a[1], np.maximum(a[0], a[3]))
        a[2] = np.minimum(a[2], np.minimum(a[0], a[3]))
        full = SG.day_setup(a, "BANKNIFTY")
        for k in range(10, 63):
            cut = a.copy()
            cut[:, 5 * k + 5:] = np.nan
            part = SG.day_setup(cut, "BANKNIFTY")
            assert part["strike"] == full["strike"]
            for arm in K.ARMS:
                assert SG.signal(arm, part, k) == SG.signal(arm, full, k)


# ------------------------------------------------------------------------------------------------ the taking walk
def _fake_price(exit_after=3, rE=100.0, log=None):
    def fn(er, side):
        if log is not None:
            log.append((er, side))
        return dict(er=er, xr=er + exit_after, rE=rE, side=side, net=1.0)
    return fn


def test_walk_one_a_side_and_cooldown():
    a = flat_day(1000.0)
    for k in range(10, 57):
        set_bar(a, k, 1001, 1003, 1000.8, 1002)   # above the range all day
    s = SG.day_setup(a, "BANKNIFTY")
    log = []
    tr = SG.walk("ORB", s, _fake_price(log=log))
    assert len(tr) == 1 and tr[0]["bar"] == 10 and tr[0]["er"] == SG.entry_row(10) == 55
    for k in range(30, 57):
        set_bar(a, k, 999, 999.2, 997, 998)       # then below: one PE
    s = SG.day_setup(a, "BANKNIFTY")
    tr = SG.walk("ORB", s, _fake_price(exit_after=200))   # the CE holds until row 255 (bar 51)
    assert [t["side"] for t in tr] == ["C", "P"]
    assert tr[1]["bar"] == 52                     # the first bar starting after the exit's bar (exit row 255 -> bar 51)


def test_walk_refuses_cheap_premium_without_counting():
    a = flat_day(1000.0)
    for k in range(10, 57):
        set_bar(a, k, 1001, 1003, 1000.8, 1002)
    s = SG.day_setup(a, "BANKNIFTY")
    calls = []

    def fn(er, side):
        calls.append(er)
        return dict(er=er, xr=er + 2, rE=40.0 if len(calls) < 3 else 60.0, side=side, net=0.0)
    tr = SG.walk("ORB", s, fn)
    assert len(tr) == 1 and len(calls) == 3


def test_walk_sweep_two_a_day():
    a = flat_day(1000.0)
    for k in range(10, 63):
        set_bar(a, k, 1000, 1005, 999.8, 1000)    # a sweep of the high on every bar
    s = SG.day_setup(a, "BANKNIFTY")
    tr = SG.walk("SWEEP", s, _fake_price())
    assert len(tr) == 2 and tr[1]["bar"] == 12    # exit row 58 is in bar 11: the next decision is bar 12


# ------------------------------------------------------------------------------------------------ B0 simulator
def contract(price=100.0, vol=1e6):
    C = np.full((5, NR), price, dtype=float)
    C[1] += 0.5
    C[2] -= 0.5
    C[4] = vol
    return C


BE = lambda e: e + 2.0  # noqa: E731  (breakeven: 2 points of charges a unit)


def test_own_stop_and_gap():
    C = contract()
    C[2, 80] = 50.0                                # low through the -40 stop
    r = X.own_exit(C, 60, 355, 30.0, 40.0, BE)
    e = X.buy_fill(100.0, C[4], 60, 30.0)
    assert r["why"] == "stop" and r["xr"] == 80
    assert r["X"] == X.floor_tick(e - 40)
    C[0, 80] = 45.0                                # gap below it: filled at the open
    r = X.own_exit(C, 60, 355, 30.0, 40.0, BE)
    assert r["X"] == 45.0


def test_own_lock_from_next_minute_and_target_next_open():
    C = contract()
    C[1, 70] = 111.0                               # +10 rung earned at minute 70 ...
    C[2, 70] = 99.0                                # ... a low under breakeven the same minute does not sell
    C[2, 71] = 101.0                               # next minute at the lock (e + charges): sold
    r = X.own_exit(C, 60, 355, 30.0, 40.0, BE)
    assert r["why"] == "lock" and r["xr"] == 71
    C = contract()
    C[1, 90] = 141.0                               # target seen on the high ...
    C[0, 91] = 138.0                               # ... sold at the next minute's open
    r = X.own_exit(C, 60, 355, 30.0, 40.0, BE)
    assert r["why"] == "target" and r["xr"] == 91 and r["X"] == 138.0


def test_own_squareoff_and_refusal():
    C = contract()
    r = X.own_exit(C, 60, 355, 30.0, 40.0, BE)
    assert r["why"] == "squareoff" and r["xr"] == 355
    r = X.own_exit(contract(price=39.9), 60, 355, 30.0, 40.0, BE)   # real fill 39.97
    assert r["why"] == "refused"
    C = contract()
    C[0, 60:63] = np.nan                           # no print for 3 minutes: no fill
    assert X.own_exit(C, 60, 355, 30.0, 40.0, BE) is None


def test_sweep_ladder_on_80():
    C = contract(price=200.0)
    C[1, 70] = 221.0                               # +20 of the +80: breakeven rung
    r = X.own_exit(C, 60, 355, 30.0, 80.0, BE)
    assert r["why"] == "lock" and r["xr"] == 71   # the next minute's low (199.5) is under the locked breakeven
    C = contract(price=200.0)
    C[1, 70] = 215.0                               # +15 of the +80: no rung yet, so no lock exit
    assert X.own_exit(C, 60, 355, 30.0, 80.0, BE)["why"] == "squareoff"


# ------------------------------------------------------------------------------------------------ charges, stats, gates
def test_parity_charges_use_todays_stt():
    d = date(2022, 6, 1)                           # dated STT 0.05% then; the app model 0.15%
    assert X.round_trip("BANKNIFTY", 300.0, 320.0, 25.0, d, "parity") > X.round_trip("BANKNIFTY", 300.0, 320.0, 25.0, d)
    assert X.breakeven("BANKNIFTY", 300.0, 25.0, d) > 300.0


def test_stats_breakeven_and_gates():
    tr = [dict(day=date(2025, 5, 1), net=100.0, charges=50.0, why="target"),
          dict(day=date(2025, 5, 1), net=-300.0, charges=50.0, why="stop"),
          dict(day=date(2025, 5, 2), net=200.0, charges=50.0, why="lock")]
    st = S.stats(S.compact(tr), 2)
    assert st["trades"] == 3 and st["win"] == pytest.approx(2 / 3, abs=1e-4)
    assert st["be_win"] == pytest.approx(300 / 450, abs=1e-4)
    assert st["worst_day"] == -200 and st["max_dd"] == 200 and st["exits"]["lock"] == 1
    assert st["ex_top5"] == 0
    good = dict(net=10, trades=50)
    assert S.gates(good, good, good, 0.05, dict(net=0), dict(net=0))["passed"]
    g = S.gates(good, good, dict(net=10, trades=39), 0.2, dict(net=20), dict(net=0))
    assert len(g["fails"]) == 3


def test_parity_check_tolerance():
    p = S.parity_check("ORB", "pre", 980, -100000)
    assert p["trades_ok"] and p["net_ok"]
    p = S.parity_check("SWEEP", "ho", 400, -10000)
    assert not p["trades_ok"] and not p["net_ok"]


def test_tests_and_m():
    assert len(K.TESTS) == 360 and K.M_TOTAL == 3058
    assert K.period_of(date(2025, 3, 31)) == "P1" and K.period_of(date(2026, 4, 1)) == "P3"
