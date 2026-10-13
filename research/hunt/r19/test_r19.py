"""R19 unit tests on synthetic data (no release files needed): python3 -m pytest research/hunt/r19/test_r19.py -q

They pin the arm's exits (premium stop, index stop, time stop, target, failed break / new level, 15:10), the locks
(rungs, never lower, never below breakeven, never at or above the best price), the edge cases (no target, target already
passed, a gap through the lock, missing option prints) and the look-ahead guard (nothing after a minute changes what was
decided at or before it).
"""
from __future__ import annotations

import os
import sys
from datetime import date, timedelta

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import run as R  # noqa: E402
import sim as S  # noqa: E402


def opt_path(prices, vol=10_000):
    a = np.full((S.N, 5), np.nan)
    for r, p in enumerate(prices):
        if p is not None:
            o, h, l_, c = p
            a[r] = (o, h, l_, c, vol)
    return a


def flat(n=S.N, px=100.0):
    return [(px, px + 0.5, px - 0.5, px)] * n


def index_path(level=50_000.0, n=S.N):
    ix = np.zeros((n, 4))
    ix[:] = (level, level + 2, level - 2, level)
    return ix


def sig(side=1, ref=50_000.0, target=50_200.0, idx_stop=None, exit_row=None):
    return dict(side=side, ref=ref, target=target, level=ref - 10 * side,
                idx_stop=(ref - 10 * side - 30 * side) if idx_stop is None else idx_stop, exit_row=exit_row)


QTY = 30
D = S.BY_ID["d"]


# ------------------------------------------------------------------------------------------------ the arm's exits
def test_premium_stop_rests_at_85pct_and_fills_at_trigger_or_gap():
    p = flat()
    p[10] = (100, 100, 80, 82)
    a = opt_path(p)
    r = S.run_trade(a, index_path(), sig(), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "stop" and r["row"] == 10 and r["raw"] == 85.0
    p[10] = (70, 71, 69, 70)                              # opened under the stop: filled at the open
    r = S.run_trade(opt_path(p), index_path(), sig(), 5, 100.0, QTY, QTY, D)
    assert r["raw"] == 70 and r["stop"]


def test_index_stop_sells_at_next_open():
    ix = index_path()
    ix[12, 2] = 50_000 - 10 - 31                          # 31 points under the broken level
    p = flat()
    p[13] = (97, 98, 96, 97)
    r = S.run_trade(opt_path(p), ix, sig(), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "index_stop" and r["row"] == 13 and r["raw"] == 97


def test_time_stop_after_20_minutes_unless_up_5pct():
    r = S.run_trade(opt_path(flat()), index_path(), sig(target=None), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "time_stop" and r["row"] == 5 + 20
    p = [(106, 106.5, 105.5, 106)] * S.N
    r = S.run_trade(opt_path(p), index_path(), sig(target=None), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "15:10" and r["row"] == S.SQ_ROW


def test_target_failed_break_and_square_off():
    ix = index_path()
    ix[8, 1] = 50_200
    r = S.run_trade(opt_path([(106, 107, 105, 106)] * S.N), ix, sig(), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "target" and r["row"] == 9
    r = S.run_trade(opt_path([(106, 107, 105, 106)] * S.N), index_path(), sig(exit_row=14), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "failed_or_new" and r["row"] == 15


def test_missing_prints_use_next_printed_open_and_skip_the_time_stop_without_a_price():
    p = [None] * S.N
    p[5] = (100, 100.5, 99.5, 100)
    p[300] = (90, 90, 90, 90)
    r = S.run_trade(opt_path(p), index_path(), sig(target=None, exit_row=40), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "time_stop" and r["row"] == 300 and r["raw"] == 90      # decided on the last price (100)
    a = opt_path(p)
    a[5, 1:4] = np.nan                                                          # an open but no close: no price
    r = S.run_trade(a, index_path(), sig(target=None, exit_row=40), 5, 100.0, QTY, QTY, D)
    assert r["why"] == "failed_or_new" and r["row"] == 300 and r["raw"] == 90


# ------------------------------------------------------------------------------------------------ the locks
def test_index_ladder_rungs_and_no_target():
    a1 = S.BY_ID["a1"]
    be = S.be_level(100.0, QTY)
    assert a1.level(100.0, be, 130.0, 0.49) is None
    assert a1.level(100.0, be, 130.0, 0.50) == be
    assert a1.level(100.0, be, 140.0, 0.80) == pytest.approx(110.0)       # 25% of the +40 gained
    assert S.BY_ID["a2"].level(100.0, be, 140.0, 0.80) == pytest.approx(120.0)
    assert a1.level(100.0, be, 101.0, 0.80) is None                       # breakeven floor not below the best price
    assert a1.level(100.0, be, 140.0, None) is None                       # no level ahead: no ladder
    assert S.progress_to(1, 50_000, None, 50_100) is None
    assert S.progress_to(1, 50_000, 49_900, 50_100) is None               # target not ahead: no ladder
    assert S.progress_to(-1, 50_000, 49_800, 49_850) == pytest.approx(0.75)


def test_be_and_giveback_rungs():
    be = S.be_level(100.0, QTY)
    assert 100 < be < 103
    assert S.BY_ID["b1"].level(100.0, be, 109.9, None) is None
    assert S.BY_ID["b1"].level(100.0, be, 110.0, None) == be
    assert S.BY_ID["b2"].level(100.0, be, 119.0, None) is None
    assert S.BY_ID["c1"].level(100.0, be, 112.0, None) == pytest.approx(106.0)
    assert S.BY_ID["c1"].level(100.0, be, 103.0, None) is None
    assert S.BY_ID["c2"].level(100.0, be, 130.0, None) == pytest.approx(115.0)


def test_lock_only_raises_and_counts_from_the_next_bar():
    p = flat()
    p[6] = (100, 115, 100, 112)                     # best 115: b1 earns breakeven from row 7
    p[7] = (101, 101.5, 100.5, 101)                 # same bar as the earned lock: a rung counts from the next bar
    a = opt_path(p)
    r = S.run_trade(a, index_path(), sig(target=None), 5, 100.0, QTY, QTY, S.BY_ID["b1"])
    assert r["why"] == "lock" and r["row"] == 7 and r["stop"]
    # the bar that sets the best is not sold on its own low
    p2 = flat()
    p2[6] = (100, 115, 99, 101)
    r2 = S.run_trade(opt_path(p2), index_path(), sig(target=None), 5, 100.0, QTY, QTY, S.BY_ID["b1"])
    assert r2["row"] >= 7


def test_lock_never_widens_the_stop_and_gap_fills_at_open():
    p = flat()
    p[6] = (100, 130, 100, 128)                     # c1: lock 115 from row 7
    p[7] = (90, 91, 80, 85)                         # gaps under lock and stop: filled at the open
    r = S.run_trade(opt_path(p), index_path(), sig(target=None), 5, 100.0, QTY, QTY, S.BY_ID["c1"])
    assert r["why"] == "lock" and r["raw"] == 90
    rc = S.run_trade(opt_path(p), index_path(), sig(target=None), 5, 100.0, QTY, QTY, D)
    assert rc["why"] == "stop" and rc["raw"] == 85.0 and r["raw"] >= rc["raw"]


def test_every_lock_exits_no_later_than_control():
    rng = np.random.default_rng(3)
    for _ in range(40):
        steps = rng.normal(0, 1.5, S.N).cumsum() + 100
        p = [(c, c + abs(rng.normal(0, 1)), c - abs(rng.normal(0, 1)), c) for c in steps]
        ix = index_path() + rng.normal(0, 5, (S.N, 1)).cumsum(axis=0)
        s = sig(target=float(ix[5, 3] + 80))
        base = S.run_trade(opt_path(p), ix, s, 5, 100.0, QTY, QTY, D)
        for v in S.VARIANTS[1:]:
            r = S.run_trade(opt_path(p), ix, s, 5, 100.0, QTY, QTY, v)
            assert r["row"] <= base["row"]
            if r["row"] == base["row"] and r["why"] != "lock":
                assert r["net"] == base["net"]


def test_index_ladder_on_a_real_shaped_trade():
    ix = index_path()
    ix[6:9, 1] = 50_000 + 160                       # 80% of the way to 50,200 (ref 50,000)
    p = flat()
    p[6] = (100, 130, 100, 128)
    p[7] = p[8] = (127, 128, 126, 127)
    p[9] = (126, 126, 104, 105)                      # falls back: a2 locks 100 + 0.5 x 30 = 115
    r = S.run_trade(opt_path(p), ix, sig(target=50_200.0, exit_row=None), 5, 100.0, QTY, QTY, S.BY_ID["a2"])
    assert r["why"] == "lock" and r["raw"] == pytest.approx(115.0)
    assert S.on_tick(115.01) == 115.05 and S.on_tick(115.0) == 115.0 and S.on_tick(102.50000001) == 102.5


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_lookahead_guard_exit_unchanged_by_later_rows():
    rng = np.random.default_rng(7)
    for _ in range(25):
        steps = rng.normal(0, 1.2, S.N).cumsum() + 100
        p = [(c, c + abs(rng.normal(0, 1)), c - abs(rng.normal(0, 1)), c) for c in steps]
        ix = index_path() + rng.normal(0, 4, (S.N, 1)).cumsum(axis=0)
        s = sig(target=float(ix[5, 3] + 60))
        for v in S.VARIANTS:
            a = opt_path(p)
            r = S.run_trade(a, ix, s, 5, 100.0, QTY, QTY, v)
            cut = r["row"] if r["why"] in ("stop", "lock") else r["row"] - 1   # decided at this row
            b = a.copy()
            b[cut + 1:, :4] *= rng.uniform(0.5, 1.5, (S.N - cut - 1, 1))
            b[r["row"], 0] = a[r["row"], 0]                                     # the fill's own open stays
            ix2 = ix.copy()
            ix2[cut + 1:] += rng.normal(0, 200, (S.N - cut - 1, 1))
            r2 = S.run_trade(b, ix2, s, 5, 100.0, QTY, QTY, v)
            assert (r2["row"], r2["why"], r2["raw"]) == (r["row"], r["why"], r["raw"])


def test_lookahead_guard_signals_unchanged_by_later_minutes():
    rng = np.random.default_rng(11)
    d0 = date(2025, 3, 3)
    days, px = [], 50_000.0
    for k in range(4):
        d = d0 + timedelta(days=k)
        bars = []
        for m in range(S.N):
            o = px
            px += rng.normal(0, 25)
            bars.append((d, S.OPEN_M + m, o, max(o, px) + abs(rng.normal(0, 8)), min(o, px) - abs(rng.normal(0, 8)), px))
        days.append((d, bars))
    hist, today = days[:-1], days[-1]
    full = S.day_signals(hist, today[0], today[1], 5, "BANKNIFTY")
    for cut in (120, 200, 300):
        trunc = S.day_signals(hist, today[0], today[1][:cut], 5, "BANKNIFTY")
        early = [x for x in full if x["sig_row"] < cut - 5]
        got = [x for x in trunc if x["sig_row"] < cut - 5]
        key = lambda x: (x["sig_row"], x["side"], x["level"], x["target"])  # noqa: E731
        assert [key(x) for x in early] == [key(x) for x in got]


# ------------------------------------------------------------------------------------------------ the runner's helpers
def test_folds_holdout_and_lots():
    assert R.in_hold(date(2024, 8, 1)) and R.in_hold(date(2025, 2, 14)) and not R.in_hold(date(2025, 2, 17))
    assert R.fold_of(date(2024, 8, 1)) is None and R.fold_of(date(2025, 2, 17)) == "2025Q1"
    assert R.lot_on(date(2024, 8, 1)) == 15 and R.lot_on(date(2024, 12, 2)) == 30 and R.lot_on(date(2025, 7, 1)) == 35


def test_bh_and_maxdd():
    q = R.bh([0.01, 0.04, 0.03, 0.5])
    assert q[0] == pytest.approx(0.04) and q[3] == 0.5
    assert R.maxdd(np.array([100.0, -300.0, 50.0, -100.0])) == pytest.approx(-350.0)
    assert R.maxdd(np.array([-50.0])) == pytest.approx(-50.0)


def test_wide_spread_costs_more():
    t = dict(net=100.0, tick_in=1.0, tick_out=2.0, stop=True, qty=30)
    assert S.wide_spread_net(t, 1.5) == pytest.approx(100.0 - 0.5 * 0.05 * (1 + 4) * 30)


def test_today_illustration():
    out = R.today(write=False)
    assert out["d"]["gross"] == -864
    assert out["b2"]["gross"] == -864 and out["c2"]["gross"] == -864          # the best was +10.7%, under +20%
    assert out["b1"]["exit"] > 195.45 and out["c1"]["exit"] > out["b1"]["exit"]
    assert out["a1"]["exit"] is None
