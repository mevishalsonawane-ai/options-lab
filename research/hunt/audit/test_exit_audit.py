"""Exit-engine audit (14 Oct 2026, Boss: "check if your entry / exit / trailing profit lock / profit lock / stop are correct
for all the study you have done"). Every engine used by R25, R26 and R34-R37 is run on hand-made option paths whose fills, locks,
quantities and P&L are worked out here by hand. python3 -m pytest research/hunt/audit -q
"""
from __future__ import annotations

import math
import os
import sys
from pathlib import Path

import numpy as np
import pytest

ROOT = Path(__file__).resolve().parents[3]
for p in (ROOT / "research" / "hunt" / "r34", ROOT / "research" / "hunt" / "r35", ROOT / "research" / "hunt" / "r37"):
    sys.path.insert(0, str(p))

import r34cfg  # noqa: E402,F401
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
from r25data import C, H, L, N, O, OI, V, minute  # noqa: E402
from test_r25 import flat, make_day  # noqa: E402

K = 25100.0
SIG = 200                 # the signal row; the entry fills at row 201's open
LOT = 25


def day_with(path_close, path_high=None, lot=LOT, vol=5000.0):
    """A flat index day whose 25100 CE trades `path_close` each minute: open = the previous close, high = max(open,
    close) unless `path_high` overrides it, 5,000 units a minute (200 lots: 1-tick half-spread)."""
    day = make_day(flat())
    day.lot = lot
    c = np.asarray(path_close, dtype=float)
    a = np.full((N, 7), np.nan)
    a[:, C] = c
    a[:, O] = np.r_[c[0], c[:-1]]
    a[:, H] = np.maximum(a[:, O], a[:, C]) if path_high is None else np.fmax(np.maximum(a[:, O], a[:, C]), path_high)
    a[:, L] = np.minimum(a[:, O], a[:, C])
    a[:, V] = vol
    a[:, OI] = 1e5
    a[:, 6] = 20.0
    day.opts[(K, "C")] = a
    return day


def buy(p):
    return R.buy_px(p, 1000.0)


def sell(p):
    return R.sell_px(p, 1000.0)


def ch(b, px, qty):
    return R.charge(b, px, qty, "NIFTY")


def net_of(entry, qty, sales):
    """sales: [(qty, price)]"""
    cost = entry * qty + ch(True, entry, qty)
    got = sum(q * p - ch(False, p, q) for q, p in sales if p > 0)
    return round(got - cost, 2)


# ------------------------------------------------------------------------------------------------ fills and charges
def test_fill_and_charge_formulas_by_hand():
    assert buy(20.0) == 20.06 and sell(20.0) == 19.94                     # 5 bps + 1 tick
    assert R.buy_px(20.0, 5.0) == 20.11 and R.buy_px(20.0, 0.0) == 20.21    # 2 / 4 ticks when thin / untraded
    # Rs 20 brokerage + 0.03553% exchange + SEBI + 0.003% stamp (buy) / 0.15% STT (sell) + 18% GST
    assert ch(True, 20.0, 75) == pytest.approx(20 + 0.53295 + 0.0015 + 0.045 + 0.18 * (20 + 0.53295 + 0.0015), abs=0.01)
    assert ch(False, 40.0, 75) == pytest.approx(20 + 4.5 + 1.0659 + 0.003 + 0.18 * (20 + 1.0659 + 0.003), abs=0.01)


def test_entry_is_the_next_open_and_never_chases():
    px = np.full(N, 20.0)
    d = day_with(px)
    assert R.enter(d, K, "C", SIG, 20.0) == (SIG + 1, buy(20.0))
    px[SIG + 1:] = 30.0                                                    # gaps above the cap 20 x 1.2 + 0.10
    d = day_with(px)
    d.opts[(K, "C")][SIG + 1:, O] = 30.0
    d.opts[(K, "C")][SIG + 1:, L] = 30.0
    assert R.enter(d, K, "C", SIG, 20.0) is None


# ------------------------------------------------------------------------------------------------ R26 engine (E1 / E1b / E2 / E3)
def test_e1_and_e1b_sell_everything_at_the_square_off_open():
    px = np.full(N, 20.0)
    px[minute(15, 4):] = 30.0                                              # the 15:05 bar opens at 30
    t = L26.simulate(day_with(px), K, 1, SIG, 20.0, "E1", 2000.0)
    e = buy(20.0)
    lots = int(2000 // (e * LOT))
    assert t.exits == [(minute(15, 5), lots * LOT, sell(30.0), "E1")]
    assert t.net == pytest.approx(net_of(e, lots * LOT, [(lots * LOT, sell(30.0))]), abs=0.01)
    t = L26.simulate(day_with(px), K, 1, SIG, 20.0, "E1b", 2000.0)
    assert t.exits[-1][0] == minute(15, 20)


def test_e2_sells_all_at_the_4x_limit_only_when_confirmed():
    e = buy(20.0)
    t4 = R.floor_tick(4 * e)                                               # 80.20
    px = np.full(N, 20.0)
    px[220:] = 85.0
    t = L26.simulate(day_with(px), K, 1, SIG, 20.0, "E2", 2000.0)
    assert t.exits[0][1:] == (t.qty, t4, "4x") and t.exits[0][0] == 220
    # a single print to 90 that closes back at 20 (and the next open at 20) fills nothing
    hi = np.full(N, np.nan)
    hi[220] = 90.0
    t = L26.simulate(day_with(np.full(N, 20.0), hi), K, 1, SIG, 20.0, "E2", 2000.0)
    assert t.exits[-1][3] == "E2"


def test_e3_half_at_2x_then_breakeven_lock_sells_the_rest_at_the_next_open():
    e = buy(20.0)                                                          # 20.06
    lots = int(2000 // (e * LOT))                                          # 3
    px = np.full(N, 20.0)
    px[210:215] = 41.0                                                     # 2.04x: half at 2x, lock = 1x (breakeven)
    px[215:] = 19.0                                                        # the 215 close is below the lock
    t = L26.simulate(day_with(px), K, 1, SIG, 20.0, "E3", 2000.0)
    half = int(round(lots * 0.5))                                          # 2 of 3 lots
    assert t.exits[0] == (210, half * LOT, R.floor_tick(2 * e), "2x")
    assert t.exits[1] == (216, (lots - half) * LOT, sell(19.0), "lock")    # sold at the NEXT bar's open
    assert t.net == pytest.approx(net_of(e, lots * LOT, [(half * LOT, R.floor_tick(2 * e)), ((lots - half) * LOT, sell(19.0))]), abs=0.01)


def test_e3_step_lock_follows_the_best_multiple():
    e = buy(20.0)
    px = np.full(N, 20.0)
    px[210:213] = 41.0
    px[213:220] = 65.0                                                     # 3.24x: lock = 2x (40.12)
    px[220:] = 40.0                                                        # close <= 40.12: sells next open
    t = L26.simulate(day_with(px), K, 1, SIG, 20.0, "E3", 2000.0)
    assert [w for *_, w in t.exits] == ["2x", "lock"] and t.exits[1][0] == 221 and t.exits[1][2] == sell(40.0)
    # the lock never sells in the bar it is raised: a 65 high and a 39 close in the SAME bar keeps the old lock (1x)
    px = np.full(N, 20.0)
    px[210:213] = 41.0
    hi = np.full(N, np.nan)
    hi[213] = 65.0
    px[213:] = 39.0
    t = L26.simulate(day_with(px, hi), K, 1, SIG, 20.0, "E3", 2000.0)
    assert t.exits[1][0] == 215                                            # the 214 close (39 <= 2x lock) sells at 215


def test_e3_with_one_lot_sells_everything_at_2x():
    px = np.full(N, 20.0)
    px[210:] = 45.0
    t = L26.simulate(day_with(px, lot=150), K, 1, SIG, 20.0, "E3", 2000.0)
    assert t.lots == 1 and t.exits == [(210, 150, R.floor_tick(2 * buy(20.0)), "2x")]


def test_worthless_at_the_exit_sends_no_sell_and_loses_premium_plus_buy_charges():
    px = np.full(N, 20.0)
    px[205:] = 0.05
    t = L26.simulate(day_with(px), K, 1, SIG, 20.0, "E1", 2000.0)
    assert t.exits[-1][2] == 0.0 and t.net == pytest.approx(-t.risked, abs=0.01)


# ------------------------------------------------------------------------------------------------ R25 engine (hold / half / ladder / trail / app)
def test_r25_app_stop_at_minus_60_on_a_close_sells_next_open():
    px = np.full(N, 20.0)
    px[210:] = 7.9                                                         # <= 0.4 x 20.06 = 8.02
    t = R.simulate(day_with(px), K, 1, SIG, 20.0, 2000.0, "app")
    assert t.exits == [(211, t.qty, sell(7.9), "stop")]


def test_r25_app_half_at_5x_rest_at_20x():
    e = buy(20.0)
    px = np.full(N, 20.0)
    px[210:] = 120.0
    px[230:] = 420.0
    t = R.simulate(day_with(px), K, 1, SIG, 20.0, 2000.0, "app")
    assert [w for *_, w in t.exits] == ["5x", "20x"]
    assert t.exits[0][2] == R.floor_tick(5 * e) and t.exits[1][2] == R.floor_tick(20 * e)


def test_r25_ladder_half_at_3x_then_5x_locks_2_5x():
    e = buy(20.0)
    px = np.full(N, 20.0)
    px[210:215] = 105.0                                                    # 5.2x: half at 3x; lock 2.5x = 50.15
    px[215:] = 50.0
    t = R.simulate(day_with(px), K, 1, SIG, 20.0, 2000.0, "ladder")
    assert [w for *_, w in t.exits] == ["3x", "lock"] and t.exits[0][2] == R.floor_tick(3 * e) and t.exits[1][0] == 216


def test_r25_trail_arms_at_plus_50pct_and_gives_back_half():
    e = buy(20.0)
    px = np.full(N, 20.0)
    px[210:215] = 40.0                                                     # best close 40: trail at e + 0.5 x (40 - e) = 30.03
    px[215:] = 30.0
    t = R.simulate(day_with(px), K, 1, SIG, 20.0, 2000.0, "trail")
    assert t.exits[-1][3] == "trail" and t.exits[-1][0] == 216


# ------------------------------------------------------------------------------------------------ R37 T2 and R35 skip
def test_r37_t2_breakeven_at_2x_and_half_of_each_doubling():
    import r37flip as X
    e = buy(20.0)
    px = np.full(N, 20.0)
    px[290:295] = 170.0                                                    # 8.47x: lock = 0.5 x 8 x e = 4e = 80.24
    px[295:] = 80.0
    tr, sk = X.simulate(day_with(px), K, 1, 285, 20.0, "T2")
    assert not sk and tr.exits == [(296, tr.qty, sell(80.0), "lock")]


def test_r35_and_r37_skip_when_one_lot_costs_more_than_2000():
    import r35lib as S
    import r37flip as X
    d = day_with(np.full(N, 20.0), lot=150)
    assert S.trade(d, K, 1, 285, 20.0, "E3") == (None, True)
    assert X.simulate(d, K, 1, 285, 20.0, "T2") == (None, True)


# ------------------------------------------------------------------------------------------------ R34 K rules (KS stop)
def test_r34_ks_stop_is_an_index_close_beyond_the_range_then_next_open():
    import r34late as KL
    spot = np.full(N, 25000.0)
    spot[minute(14, 50):] = 25100.0                                        # breaks above the 14:30-14:45 range
    spot[minute(14, 58):] = 24990.0                                        # back below its low: the stop
    d = make_day(spot)
    tr = KL.trade(d, 1, "KS")
    assert tr is not None and tr.exits[-1][0] == minute(14, 58) + 1
