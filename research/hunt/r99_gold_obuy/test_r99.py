"""R99 tests (synthetic data only, R96's synthetic book): the walk's target / stop / hard stop / time order, the trade's
fill, strike, square-off and expiry-day refusal, the impulse moves (no gap, same day), the grid, the gates and the
look-ahead guard (a signal's trade does not change when every later bar is replaced).

    python -m pytest research/hunt/r99_gold_obuy/test_r99.py -q
"""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r96_mcx_buy.tests.test_r96 import make_book
from research.hunt.r99_gold_obuy import sim as S
from research.hunt.r99_gold_obuy import study as R

NAN = np.nan


def test_walk_order_target_stop_hardstop_time():
    F = np.array([5000, 5050, 5100, 5210, 5000.0])
    o = np.array([100, 101, 102, 103, 104.0]); lo = o - 1
    assert S.walk(o, lo, F, 1, 5000.0, 200, 100, 100.0)[:2] == (3, "target")
    assert S.walk(o, lo, F, -1, 5000.0, 300, 100, 100.0)[:2] == (2, "stop")        # a put: the future up 100 = its stop
    lo2 = lo.copy(); lo2[1] = 55.0                                                  # the premium's hard stop (-40%) first
    k, why, px = S.walk(o, lo2, F, 1, 5000.0, 200, 100, 100.0)
    assert (k, why, px) == (1, "stop_prem", 60.0)
    o3 = o.copy(); o3[1] = 50.0; lo3 = lo.copy(); lo3[1] = 49.0                     # opened below the stop: out at the open
    assert S.walk(o3, lo3, F, 1, 5000.0, 200, 100, 100.0)[2] == 50.0
    assert S.walk(o, lo, np.full(5, 5000.0), 1, 5000.0, 200, 100, 100.0)[:2] == (4, "time")
    # a minute with no future price never triggers
    F4 = F.copy(); F4[3] = NAN
    assert S.walk(o, lo, F4, 1, 5000.0, 200, 100, 100.0)[:2] == (4, "time")


def test_trade_fill_strike_target_and_costs():
    # The future climbs 1 point a minute from 10:00 on day 1: a call bought at 10:00 reaches +100 at 11:40.
    def path(i, ts):
        mins = (ts - ts.normalize()).total_seconds() / 60
        return np.where((ts.normalize() == ts.normalize()[0]) & (mins >= 600), 5000 + (mins - 600), 5000.0)
    bk = make_book(days=4, path=path)
    m = bk.m
    i = m.at_or_before(m.days[0], 10 * 60)
    t = S.trade(bk, i, 1, 0, 100, 50, 300)
    assert t["why"] == "target" and t["entry_ts"] == pd.Timestamp(m.ts[i + 1])
    assert t["exit_ts"] == pd.Timestamp(m.days[0]) + pd.Timedelta(hours=11, minutes=40)
    assert t["strike"] == 5000.0                                                    # ATM
    assert t["gross"] > 0 and t["cost"] > 0 and t["net2"] < t["net"] < t["net05"]
    t1 = S.trade(bk, i, -1, 1, 100, 50, 300)                                        # a 1-ITM put: the future up 50 = stop
    assert t1["why"] == "stop" and t1["strike"] == 5050.0 and t1["net"] < 0
    # held for 30 minutes at most
    t2 = S.trade(bk, i, 1, 0, 1000, 500, 30)
    assert t2["why"] == "time" and t2["exit_ts"] == pd.Timestamp(m.ts[i + 1]) + pd.Timedelta(minutes=30)


def test_trade_squareoff_expiry_day_and_no_print():
    bk = make_book(days=4, exp_day="2025-11-06")                                    # the 4th session is the expiry day
    m = bk.m
    i = m.at_or_before(m.days[0], 22 * 60 + 30)
    t = S.trade(bk, i, 1, 0, 1000, 500, None)
    assert t["why"] == "squareoff" and t["exit_ts"] == pd.Timestamp(m.days[0]) + pd.Timedelta(hours=23, minutes=14)
    assert "skip" in S.trade(bk, m.at_or_before(m.days[3], 12 * 60), 1, 0, 100, 50, 60)
    bk2 = make_book(days=2, drop_prints=lambda O: (O.ts.dt.hour == 10))
    j = bk2.m.at_or_before(bk2.m.days[0], 10 * 60 + 10)
    assert S.trade(bk2, j, 1, 0, 100, 50, 60).get("unfilled") == "no print"


def test_impulse_moves_need_the_exact_earlier_minute_same_day():
    bk = make_book(days=2, path=lambda i, ts: 5000.0 + i, minutes=(9 * 60, 9 * 60 + 30))
    m = bk.m
    mv = R.impulse_moves(m, 5)
    assert np.isnan(mv[:5]).all() and mv[5] == 5.0
    first2 = m.day_first[m.days[1]]
    assert np.isnan(mv[first2:first2 + 5]).all()                                    # never across the night
    bk2 = make_book(days=1, path=lambda i, ts: 5000.0 + i, minutes=(9 * 60, 9 * 60 + 30),
                    drop_prints=None)
    keep = np.ones(len(bk2.m.ts), bool); keep[3] = False                             # a missing minute
    from research.hunt.r53.mcx.data import Market
    m3 = Market("SYN", bk2.m.ts[keep], bk2.m.F[keep], None)
    mv3 = R.impulse_moves(m3, 5)
    assert np.isnan(mv3[3:7]).sum() >= 1


def test_the_grid_is_the_preregistered_104():
    V = R.grid()
    assert len(V) == 104 and len({v.name for v in V}) == 104
    assert sum(v.fam == "A" for v in V) == 48 and sum(v.fam == "B" for v in V) == 48 and sum(v.fam == "C" for v in V) == 8
    assert R.HOLD == 60 and R.TWINS == 200 and R.DESIGN[1] < R.HOLDOUT[0]


def test_gates():
    s = dict(trades=40, net=1000.0, net2=10.0, folds_pos=3, worst_month=-5000.0, max_dd=-9000.0, wo_top3=1.0)
    assert R.cheap_gates(s) and all(R.gates(s, 0.01, 0.05).values())
    assert not R.gates(s, 0.01, 0.2)["3 twin p < 0.05, q <= 0.10"]
    assert not R.cheap_gates(dict(s, net2=-1.0)) and not R.cheap_gates(dict(s, trades=29))
    assert not R.gates(dict(s, worst_month=-14000.0), 0.01, 0.05)["5 month / drawdown"]


def test_lookahead_guard_the_trade_does_not_see_later_bars():
    """A trade's entry, strike and exit are the same when the bars after its exit are replaced by an absurd path (after
    the exit minute + 10: an exit minute without a print fills at the next print within 10 minutes, as R96)."""
    rng = np.random.default_rng(7)
    steps = rng.normal(0, 20, 4 * 900)
    bk = make_book(days=3, path=lambda i, ts: 5000 + np.cumsum(steps[:len(i)]))
    m = bk.m
    i = m.at_or_before(m.days[0], 12 * 60)
    t = S.trade(bk, i, 1, 0, 150, 75, 60)
    cut = t["i_out"] + 10
    bad = bk.m.F.copy(); bad[cut + 1:] = 9e9

    def path2(ii, ts):
        return bad[ii]
    bk2 = make_book(days=3, path=path2)
    t2 = S.trade(bk2, i, 1, 0, 150, 75, 60)
    for k in ("i_in", "i_out", "strike", "fill", "exit", "why"):
        assert t[k] == t2[k], k
