"""R19c tests: the floor's arithmetic, its place in each arm's exits, gaps, a missing peak, the look-ahead guard, the day
rules and the statistics (PREREG.md)."""
from __future__ import annotations

from datetime import date

import numpy as np
import pytest

from research.hunt.r19b import sim as S19
from research.hunt.r19c import run as R
from research.hunt.r19c import sim as S

BOSS = S.Floor("F1000_500", 1000.0, 500.0)


# ---------------------------------------------------------------- floor_trigger
def test_waits_for_plus_1000_a_lot():
    # MIDCPNIFTY lot 120: Rs 1,000 = 8.33 points, Rs 500 = 4.17
    assert S.floor_trigger(BOSS, 195.45, 203.75, 120) is None              # +Rs 996: not yet
    assert S.floor_trigger(BOSS, 195.45, 203.80, 120) == pytest.approx(199.65)  # 203.80 - 4.1667 -> 199.633, up to 199.65


def test_bosss_trade_today_keeps_about_2000():
    trig = S.floor_trigger(BOSS, 195.45, 216.30, 120)
    assert trig == pytest.approx(212.15)
    assert (trig - 195.45) * 120 == pytest.approx(2004.0)
    assert (trig - 195.45) * 120 >= 500                                    # never below +Rs 500 once active


def test_rupees_to_points_by_lot():
    assert S.points(1000, 30) == pytest.approx(33.333, abs=1e-3)
    assert S.points(1000, 35) == pytest.approx(28.571, abs=1e-3)
    assert S.points(1000, 65) == pytest.approx(15.385, abs=1e-3)
    assert S.points(1000, 120) == pytest.approx(8.333, abs=1e-3)
    assert S.points(1000, 0) is None and S.points(None, 30) is None and S.points(1000, None) is None


def test_missing_inputs_give_no_floor():
    assert S.floor_trigger(None, 100.0, 200.0, 30) is None
    assert S.floor_trigger(BOSS, 100.0, None, 30) is None
    assert S.floor_trigger(BOSS, 100.0, float("nan"), 30) is None
    assert S.floor_trigger(BOSS, 0.0, 200.0, 30) is None
    assert S.floor_trigger(BOSS, 100.0, 200.0, 0) is None


def test_never_lowers_and_stays_on_the_tick_under_the_peak():
    last = -1.0
    for peak in np.linspace(133.4, 200, 141):
        t = S.floor_trigger(BOSS, 100.0, float(peak), 30)
        assert t is not None and t <= peak + 1e-9
        assert abs(round(t / 0.05) * 0.05 - t) < 1e-9
        assert t >= last - 1e-9
        last = t


def test_variants_activation_and_give_back():
    f = S.Floor("F750_500", 750.0, 500.0)
    assert S.floor_trigger(f, 100.0, 124.9, 30) is None
    assert S.floor_trigger(f, 100.0, 125.0, 30) == pytest.approx(108.35)    # 125 - 16.667 -> 108.333, up to 108.35
    g = S.Floor("F1000_300", 1000.0, 300.0)
    assert S.floor_trigger(g, 100.0, 140.0, 30) == pytest.approx(130.0)


def test_liquidity_trail_takes_a_separate_activation():
    t = S.as_trail(BOSS)
    assert S19.trail_trigger(t, 100.0, 133.0, 30) is None                  # +Rs 990
    assert S19.trail_trigger(t, 100.0, 140.0, 30) == pytest.approx(123.35)
    # R19b's own variants unchanged (activation = give-back when act is None)
    a1 = S19.Trail("A1", "rs", 500.0, False)
    assert S19.trail_trigger(a1, 100.0, 104.20, 120) == pytest.approx(100.05)


# ---------------------------------------------------------------- exits on a synthetic option
def grid(path):
    """A (375, 5) R17 grid: rows from `path` [(row, o, h, l, c)], volume 100 (no thin-spread widening at lot 30)."""
    a = np.full((375, 5), np.nan, dtype=np.float32)
    for r, o, h, lo, c in path:
        a[r] = (o, h, lo, c, 100000)
    return a


def test_orb_floor_rides_on_the_ladder_and_the_higher_stop_wins():
    # lot 30, entry 100: +40 target is Rs 1,200 a lot; the floor activates at +33.33
    rows = [(10, 100, 100, 100, 100), (11, 100, 135, 100, 134), (12, 134, 134, 117, 118), (13, 118, 118, 118, 118)]
    a = grid(rows)
    x, xr, why = S.orb_exit(a, 10, 100.0, 40.0, 40.0, 30, 30, ladder=True, floor=BOSS)
    assert why == "lock" and xr == 12                                      # ladder +20 (120) above the floor (118.35)
    x0, xr0, why0 = S.orb_exit(a, 10, 100.0, 40.0, 40.0, 30, 30, ladder=True, floor=None)
    assert (why0, xr0) == ("lock", 12) and x0 == pytest.approx(x)
    x3, xr3, why3 = S.orb_exit(a, 10, 100.0, 40.0, 40.0, 30, 30, ladder=False, floor=BOSS)
    assert (why3, xr3) == ("floor", 12) and x3 < 118.35                    # R1000_500: the floor alone
    x4, _, why4 = S.orb_exit(a, 10, 100.0, 40.0, 40.0, 30, 30, ladder=False, floor=None)
    assert why4 == "square_off"                                            # no lock at all: rides to 15:10


def test_floor_counts_the_peak_from_the_next_minute_only():
    # the minute that makes the peak cannot also fill the floor (its low came before or after the high: unknown)
    a = grid([(10, 100, 100, 100, 100), (11, 100, 140, 120, 125), (12, 125, 125, 125, 125)])
    _, xr, why = S.vixdiv_exit(a, 10, 100.0, np.nan, False, 30, BOSS)
    assert why != "floor" or xr > 11


def test_gap_through_the_floor_fills_at_the_open():
    a = grid([(10, 100, 100, 100, 100), (11, 100, 140, 120, 139), (12, 110, 112, 108, 110)])
    x, xr, why = S.vixdiv_exit(a, 10, 100.0, np.nan, False, 30, BOSS)
    assert why == "floor" and xr == 12
    assert x <= 110.0                                                      # the open, less the stop slippage


def test_minutes_with_no_trade_add_nothing_to_the_peak():
    a = grid([(10, 100, 100, 100, 100), (11, 100, 120, 100, 120), (20, 120, 121, 119, 120)])
    x, xr, why = S.vixdiv_exit(a, 10, 100.0, np.nan, False, 30, BOSS)
    assert why == "square_off"                                             # never reached +33.33


def test_hero_floor_is_per_lot_on_what_is_held():
    # NIFTY lot 75: Rs 1,000 = 13.33 points; a Rs 3 option bought, 22 lots
    rows = [(300, 3, 3, 3, 3), (301, 3, 18, 3, 17.5), (302, 17.5, 17.6, 9, 9.5), (303, 9.5, 9.5, 9.5, 9.5)]
    a = grid(rows)
    gross, ch, xr, why = S.hero_exit(a, 300, 3.0, 1.2, 22, 75, BOSS)
    assert why == "floor" and xr == 302
    g0, _, _, why0 = S.hero_exit(a, 300, 3.0, 1.2, 22, 75, None)
    assert why0 != "floor"


def test_look_ahead_truncating_after_the_exit_changes_nothing():
    rng = np.random.default_rng(3)
    for _ in range(50):
        p = 100 + np.cumsum(rng.normal(0, 3, 200))
        rows = [(r, float(p[i]), float(p[i] + abs(rng.normal(0, 2))), float(p[i] - abs(rng.normal(0, 2))), float(p[i]))
                for i, r in enumerate(range(100, 300))]
        a = grid(rows)
        e = float(a[100, 0])
        x, xr, why = S.vixdiv_exit(a, 100, e, np.nan, False, 30, BOSS)
        if xr >= 299:
            continue
        b = a.copy()
        b[xr + 1:] = np.nan
        b[xr + 1:, 0:4] = 1.0                                              # anything after the exit: garbage
        b[xr + 1:, 4] = 1
        x2, xr2, why2 = S.vixdiv_exit(b, 100, e, np.nan, False, 30, BOSS)
        if why in ("floor", "stop"):
            assert (x2, xr2, why2) == (x, xr, why)


# ---------------------------------------------------------------- day rules and statistics
def fake(arm, day, m0, m1, net, closes, lot=30):
    pm = np.arange(m0, m1)
    pc = np.asarray(closes, dtype=float)
    assert len(pc) == len(pm)
    return dict(arm=arm, day=day, entry_min=m0, exit_min=m1, entry=100.0, exit=100.0 + net / lot, qty=lot, lot=lot,
                net=net, why="x", pm=pm, pc=pc, ph=pc, peak_lot=0.0,
                ctx=dict(kind="orb", a=_flat_grid(m0, m1, closes), lot=lot))


def _flat_grid(m0, m1, closes):
    a = np.full((375, 5), np.nan, dtype=np.float32)
    for m, c in zip(range(m0, m1), closes):
        a[m - 555] = (c, c, c, c, 100000)
    a[m1 - 555] = (closes[-1], closes[-1], closes[-1], closes[-1], 100000)
    return a


def test_loss_cap_blocks_later_entries_only():
    d = date(2025, 5, 2)
    t1 = fake("orb", d, 600, 605, -2500.0, [100, 95, 90, 20, 15])           # marks fall far below -2,000
    t2 = fake("solo", d, 700, 710, 800.0, [100] * 10)
    kept, act = R.day_rule([t1, t2], cap=-2000.0)
    assert act == "block" and [t["arm"] for t in kept] == ["orb"]
    kept, act = R.day_rule([t1, t2], cap=-3000.0)
    assert len(kept) == 2                                                  # realised -2,500 never reaches -3,000


def test_profit_lock_sells_open_trades_and_blocks():
    d = date(2025, 5, 2)
    t1 = fake("orb", d, 600, 640, 3000.0, [100] * 5 + [180] * 5 + [125] * 30)  # +2,400 marked, then about +700
    t2 = fake("solo", d, 700, 710, 800.0, [100] * 10)
    kept, act = R.day_rule([t1, t2], lock=(2000.0, 1000.0))
    assert act == "sell"
    assert [t["arm"] for t in kept] == ["orb"] and kept[0]["why"] == "day_lock" and kept[0]["net"] < 3000


def test_twin_force_before_the_first_entry_blocks_the_day():
    d = date(2025, 5, 2)
    t1 = fake("orb", d, 600, 605, 100.0, [100] * 5)
    kept, act = R.day_rule([t1], force=(570, "block"))
    assert kept == [] and act == "block"


def test_sign_test_and_bh():
    up, down, p = R.sign_test({1: -1, 2: -1, 3: 5}, {1: 5, 2: 5, 3: 5})
    assert (up, down) == (2, 0) and p == pytest.approx(0.25)
    q = R.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == pytest.approx(0.5)
    assert all(qq >= pp for qq, pp in zip(q, [0.01, 0.04, 0.03, 0.5]))


def test_gates():
    c = dict(green=0.4, worst_day=-3000.0, max_dd=-10000.0, net=10000.0)
    ok = dict(green=0.5, worst_day=-2000.0, max_dd=-8000.0, net=7500.0)
    assert R.gates12(c, ok) == (True, True)
    assert R.gates12(c, dict(ok, net=6500.0)) == (True, False)             # gave up more than 30%
    assert R.gates12(c, dict(ok, worst_day=-3500.0)) == (False, True)
    neg = dict(c, net=-5000.0)
    assert R.gates12(neg, dict(ok, net=-100.0))[1] is False                 # must turn positive
    assert R.gates12(neg, dict(ok, net=100.0))[1] is True


def test_max_dd_and_stats():
    assert R.max_dd({1: 100, 2: -300, 3: 50, 4: -100}) == -350
    tr = [dict(day=1, net=-50.0, qty=30, lot=30, peak_lot=1200.0, why="x"),
          dict(day=1, net=100.0, qty=30, lot=30, peak_lot=0.0, why="floor"),
          dict(day=2, net=-10.0, qty=30, lot=30, peak_lot=0.0, why="x")]
    s = R.stats(tr)
    assert s["days"] == 2 and s["green"] == 0.5 and s["profit_to_loss"] == 1 and s["floor_exits"] == 1
