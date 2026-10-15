"""R26 unit tests on synthetic days (plus one real-day guard test when the lake's day files are built):
python3 -m pytest research/hunt/r26/test_r26.py -q"""
from __future__ import annotations

import math
import os
import sys
from dataclasses import replace
from datetime import date

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r26cfg as K26  # noqa: E402
import r25cfg as K  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L  # noqa: E402
from r25data import C, H, L as LO, N, O, OI, V, minute  # noqa: E402
from test_r25 import _bars, flat, make_day  # noqa: E402

A3 = replace(L.P, id="A3", filt=False)
WALL = 25100.0


def _unwind(a, frm=minute(13, 35), to=minute(14, 30), hi=3e5, lo=1.2e5):
    """The wall's OI: hi until `frm`, then falling in a straight line to lo at `to` (writers covering)."""
    r = np.arange(N)
    path = hi - np.clip((r - frm) / (to - frm), 0, 1) * (hi - lo)
    a[:, OI] = np.where(np.isnan(a[:, C]), np.nan, path)


def blast_day(oi_fall=True, vol_spike=True, rise_at=minute(13, 30), level=25000.0, pts=150.0, und="NIFTY", over=40.0):
    """Spot flat at 25,000, then up 150 points over 40 minutes from `rise_at` and stays: it crosses the 25,100 call
    wall (the highest call OI at 13:00) and holds. The wall's call OI falls from 3 to 1.2 lakh between 13:35 and 14:30
    (writers covering) and the chain's volume is 5x from 13:35."""
    r = np.arange(N)
    spot = level + np.clip((r - rise_at) / over, 0, 1) * pts
    day = make_day(spot, und=und)
    for (k, s), a in day.opts.items():
        if s == "C" and k == WALL:
            if oi_fall:
                _unwind(a)
            else:
                a[:, OI] = np.where(np.isnan(a[:, C]), np.nan, 3e5)
        if vol_spike:
            a[minute(13, 35):, V] *= 5
    return day


def price_path(day, k, side, px):
    a = day.opts[(k, side)]
    keep = ~np.isnan(a[:, C])
    b = _bars(px, 5000.0)
    b[:, OI] = a[:, OI]
    b[:, V] = a[:, V]
    b[~keep] = np.nan
    day.opts[(k, side)] = b


# ------------------------------------------------------------------------------------------------ walls and chain
def test_wall_is_the_highest_oi_strike_nearest_spot_on_ties_and_edge_flag():
    day = blast_day()
    ch = L.chain(day)
    assert ch.wall("C", L.ANCHOR) == (WALL, False)
    # every put has the same OI: the tie goes to the strike nearest spot
    k, edge = ch.wall("P", L.ANCHOR)
    assert k == 25000.0 and not edge
    # the outermost stored call strike as the wall -> edge
    day2 = make_day(flat())
    top = max(kk for kk, s in day2.opts if s == "C" and not math.isnan(day2.opts[(kk, s)][L.ANCHOR, C]))
    day2.opts[(top, "C")][:, OI] = 9e5
    assert L.chain(day2).wall("C", L.ANCHOR) == (top, True)


def test_oi_is_forward_filled_from_the_past_only():
    day = blast_day()
    a = day.opts[(WALL, "C")]
    a[200:210, OI] = 0                          # a gap with no OI print: the last known value carries
    ch = L.chain(day)
    assert ch.oi_at("C", WALL, 205) == 3e5
    a[:, OI] = np.nan
    a[150, OI] = 7e4
    ch = L.Chain(day)
    assert math.isnan(ch.oi_at("C", WALL, 149)) and ch.oi_at("C", WALL, 151) == 7e4


def test_volume_spike_is_last_15_minutes_vs_the_session_average():
    day = blast_day(vol_spike=False)
    ch = L.chain(day)
    assert not ch.vol_spike(L.FIRST)            # flat volume: last 15 = the average
    day = blast_day()
    ch = L.chain(day)
    assert ch.vol_spike(minute(13, 50))
    assert not ch.vol_spike(5)                  # fewer than 15 minutes of session


# ------------------------------------------------------------------------------------------------ the trigger
def test_trigger_fires_up_after_a_held_wall_break_with_oi_unwinding():
    day = blast_day()
    s = L.signal(day, A3)
    assert s is not None and s.side == 1 and s.wall == WALL
    # 16 closes after the first close above 25,100
    first_above = int(np.nonzero(day.ix[:, 3] > WALL)[0][0])
    assert s.row == max(first_above + L.HOLD - 1, L.FIRST)
    assert s.strike == R.atm(day, R.spot(day, s.row)) + 50.0
    c = L.conditions(day, A3, 1, s.row)
    assert c["break"] and c["oi_fall"] and c["vol"] and c["price"] and c["sum_oi"]


def test_held_only_15_closes_does_not_fire():
    day = blast_day()
    first_above = int(np.nonzero(day.ix[:, 3] > WALL)[0][0])
    t = first_above + L.HOLD - 2                  # 15 closes above
    assert not L.conditions(day, A3, 1, t)["break"]
    assert L.conditions(day, A3, 1, t + 1)["break"]


def test_no_trigger_without_the_oi_fall_but_the_no_oi_baseline_fires():
    day = blast_day(oi_fall=False)
    assert L.signal(day, A3) is None
    b1 = replace(A3, use_oi=False)
    assert L.signal(day, b1) is not None


def test_no_trigger_without_the_volume_spike():
    assert L.signal(blast_day(vol_spike=False), A3) is None


def test_window_is_1345_to_1430():
    late = blast_day(rise_at=minute(14, 20))        # held break completes after 14:30
    assert L.signal(late, A3) is None
    early = blast_day(rise_at=minute(13, 5))        # held well before 13:45: fires at the first window minute
    s = L.signal(early, A3)
    assert s is None or s.row >= L.FIRST
    assert all(L.signal(blast_day(rise_at=m), A3) is None or L.FIRST <= L.signal(blast_day(rise_at=m), A3).row <= L.LAST
               for m in (minute(13, 0), minute(13, 40), minute(14, 0)))


def test_break_already_above_at_1300_is_not_a_move_above():
    # spot is above the wall from the open: no close at or below it after 13:00, so no "move above"
    day = blast_day(rise_at=0)
    assert all(not L.conditions(day, A3, 1, t)["break"] for t in range(L.FIRST, L.LAST + 1))


def test_rolling_wall_needs_a_fresh_break():
    day = blast_day()
    a1 = replace(A3, wall="rolling")
    s = L.signal(day, a1)
    assert s is not None
    first_above = int(np.nonzero(day.ix[:, 3] > WALL)[0][0])
    assert s.row == first_above + L.HOLD - 1      # the close 16 bars back was at or below the wall
    early = blast_day(rise_at=minute(12, 40))      # the fresh break finished before 13:45: the rolling reading misses it
    assert L.signal(early, a1) is None


def test_price_filter_rs_15_to_30():
    day = blast_day()
    s = L.signal(day, A3)
    k = s.strike
    assert not (15 <= s.ref <= 30)
    assert L.signal(day, L.P) is None              # the same minute fails the Rs 15-30 filter
    price_path(day, k, "C", np.full(N, 20.0))
    s2 = L.signal(day, L.P)
    assert s2 is not None and s2.strike == k and s2.ref == 20.0


def test_down_trigger_mirrors_with_the_put_wall():
    r = np.arange(N)
    spot = 25000.0 - np.clip((r - minute(13, 30)) / 40.0, 0, 1) * 150.0
    day = make_day(spot)
    for (k, s), a in day.opts.items():
        if s == "P" and k == 24900.0:
            _unwind(a)
        a[minute(13, 35):, V] *= 5
    s = L.signal(day, A3)
    assert s is not None and s.side == -1 and s.wall == 24900.0
    assert s.strike == R.atm(day, R.spot(day, s.row)) - 50.0


def test_theta_by_index_and_banknifty_atm_plus_2():
    assert L.THETA == {"NIFTY": 0.15, "SENSEX": 0.15, "BANKNIFTY": 0.25, "FINNIFTY": 0.25}
    # a fall of at most 20% passes NIFTY's 15% but never BANKNIFTY's 25%
    day = blast_day()
    _unwind(day.opts[(WALL, "C")], lo=2.4e5)
    s = L.signal(day, A3)
    assert s is not None
    day.und = "BANKNIFTY"
    assert not L.conditions(day, A3, 1, s.row)["oi_fall"]
    a7 = [r for r in L.RULES if r.id == "A7"][0]
    assert a7.offset == 2 and a7.unds == ("BANKNIFTY",)


def test_sensex_round_level_wall():
    day = make_day(np.full(N, 81640.0), und="SENSEX")
    ch = L.chain(day)
    a8 = [r for r in L.RULES if r.id == "A8"][0]
    assert L._wall(ch, day, a8, 1, L.ANCHOR)[0] == 82000.0
    assert L._wall(ch, day, a8, -1, L.ANCHOR)[0] == 81000.0
    day = make_day(np.full(N, 82000.0), und="SENSEX")    # exactly on a level: the next one up / down
    ch = L.chain(day)
    assert L._wall(ch, day, a8, 1, L.ANCHOR)[0] == 83000.0 and L._wall(ch, day, a8, -1, L.ANCHOR)[0] == 81000.0


def test_one_trade_per_index_per_expiry_and_index_scope():
    day = blast_day()
    tr, sigs = L.run([day], A3)
    assert len(tr) == 1 and len(sigs) == 1
    assert L.run([day], replace(A3, unds=("SENSEX",)))[0] == []


def test_market_closed_empty_and_stale_days_give_no_signal():
    day = blast_day()
    day.ix[:] = np.nan
    assert L.signal(day, A3) is None
    day = blast_day()
    day.opts = {}
    assert L.signal(day, A3) is None
    # the bought strike has not traded for 5 bars: no pick
    day = blast_day()
    s = L.signal(day, A3)
    day.opts[(s.strike, "C")][s.row - 6: s.row + 1, V] = 0
    assert L.pick(day, A3, 1, s.row) is None


# ------------------------------------------------------------------------------------------------ exits and sizing
def _trade_on(px, exits, budget=K26.BUDGET, lot=25):
    day = make_day(flat())
    day.lot = lot
    k = 25100.0
    price_path(day, k, "C", px)
    return L.simulate(day, k, 1, L.FIRST, float(px[L.FIRST]), exits, budget)


def test_sizing_whole_lots_and_one_lot_over_budget():
    px = np.full(N, 20.0)
    t = _trade_on(px, "E1")
    assert t.lots == int(2000 // (t.entry * 25)) and not t.over_budget
    t = _trade_on(px, "E1", lot=150)                    # one lot costs ~ Rs 3,000 > Rs 2,000: 1 lot, flagged
    assert t.lots == 1 and t.over_budget and t.risked > 2000


def test_e2_target_4x_confirmed_else_1505():
    px = np.full(N, 20.0)
    px[L.FIRST + 10:] = 90.0
    t = _trade_on(px, "E2")
    assert t.exits[-1][3] == "4x" and t.exits[-1][2] == R.floor_tick(4 * t.entry)
    px = np.full(N, 20.0)
    t = _trade_on(px, "E2")
    assert t.exits[-1][0] == minute(15, 5) and t.exits[-1][3] == "E2"


def test_a_freak_high_fills_no_4x_target():
    day = make_day(flat())
    k = 25100.0
    price_path(day, k, "C", np.full(N, 20.0))
    day.opts[(k, "C")][L.FIRST + 10, H] = 200.0          # one print, close and next open back at 20
    t = L.simulate(day, k, 1, L.FIRST, 20.0, "E2")
    assert t.exits[-1][3] == "E2" and t.peak_mult < 1.1


def test_e1b_holds_to_1520_and_e1_to_1505():
    px = np.full(N, 20.0)
    assert _trade_on(px, "E1b").exits[-1][0] == minute(15, 20)
    assert _trade_on(px, "E1").exits[-1][0] == minute(15, 5)


def test_e3_half_at_2x_then_step_lock():
    px = np.full(N, 20.0)
    px[L.FIRST + 10: L.FIRST + 20] = 65.0      # > 3x: half sold at 2x, the lock steps to 2x
    px[L.FIRST + 20:] = 30.0                   # falls below 2x: the rest sells at the next open
    t = _trade_on(px, "E3")
    assert t.lots >= 2
    whys = [x[3] for x in t.exits]
    assert whys == ["2x", "lock"]
    assert t.exits[0][2] == R.floor_tick(2 * t.entry)
    # one lot: the 2x sale is the whole position
    t1 = _trade_on(px, "E3", lot=150)
    assert [x[3] for x in t1.exits] == ["2x"]


def test_worthless_at_the_exit_sends_no_sell():
    px = np.full(N, 20.0)
    px[L.FIRST + 5:] = 0.05
    t = _trade_on(px, "E1")
    assert t.exits[-1][2] == 0.0 and math.isclose(t.net, -t.risked, abs_tol=0.01)


# ------------------------------------------------------------------------------------------------ look-ahead guard
def test_look_ahead_guard_synthetic():
    for day in (blast_day(), blast_day(oi_fall=False)):
        for rule in L.RULES + [A3]:
            full = L.signal(day, rule)
            for t in range(L.FIRST, L.LAST + 1):
                want = L.conditions(day, rule, 1, t)
                for fut in (L.masked(day, t), L.poisoned(day, t)):
                    got = L.conditions(fut, rule, 1, t)
                    for k, v in want.items():
                        assert v == got[k] or (isinstance(v, float) and math.isnan(v) and math.isnan(got[k])), (rule.id, t, k)
                    s = L.signal(fut, rule)
                    exp = full if full is not None and full.row <= t else None
                    assert (s is None and exp is None) or (s is not None and exp is not None and s.row == exp.row
                                                           and s.strike == exp.strike), (rule.id, t)


def test_masked_day_shares_no_chain_cache():
    day = blast_day()
    L.chain(day)
    m = L.masked(day, 250)
    assert "_r26chain" not in m.__dict__ and "_r26chain" in day.__dict__


@pytest.mark.skipif(not D.path("NIFTY").exists(), reason="the lake day files are not built here")
def test_look_ahead_guard_on_real_days():
    """Every rule's conditions and signal on real triggered expiries (masked / poisoned future), every window minute."""
    picks = {("NIFTY", date(2024, 9, 12)), ("BANKNIFTY", date(2021, 10, 14)), ("SENSEX", date(2025, 10, 23)),
             ("FINNIFTY", date(2023, 4, 18))}
    n = 0
    for u in K26.UNDS:
        for day in D.load(u, "all"):
            if (u, day.day) not in picks:
                continue
            for rule in L.RULES:
                full = L.signal(day, rule)
                for t in range(L.FIRST, L.LAST + 1, 3):
                    for sgn in (1, -1):
                        want = L.conditions(day, rule, sgn, t)
                        got = L.conditions(L.poisoned(day, t), rule, sgn, t)
                        for k, v in want.items():
                            assert v == got[k] or (isinstance(v, float) and math.isnan(v) and math.isnan(got[k])), (u, rule.id, t, k)
                    s = L.signal(L.masked(day, t), rule)
                    exp = full if full is not None and full.row <= t else None
                    assert (s is None) == (exp is None) and (s is None or (s.row, s.strike) == (exp.row, exp.strike))
                    n += 1
    assert n > 300


def test_twins_are_deterministic_and_p_counts_ties():
    day = blast_day()
    tr, sigs = L.run([day], A3)
    a = L.twins(sigs, A3, n=20, seed=1)
    b = L.twins(sigs, A3, n=20, seed=1)
    assert np.array_equal(a, b)
    assert L.twin_p(0.0, np.array([0.0, -1.0, 1.0, 2.0])) == 0.75


def test_metrics_on_no_trades_and_hit_shares():
    day = blast_day()
    m = L.metrics([], [day], A3)
    assert m["trades"] == 0 and m["net"] == 0 and m["x4_pct"] == 0
    px = np.full(N, 20.0)
    px[L.FIRST + 10:] = 90.0
    t = _trade_on(px, "E2")
    m = L.metrics([t], [make_day(flat())], A3)
    assert m["x2_pct"] == 100.0 and m["x4_pct"] == 100.0


def test_finnifty_settings_and_split():
    assert K.STEP["FINNIFTY"] == 50.0 and K.LAST_WEEKLY["FINNIFTY"] == date(2024, 11, 19)
    assert K.lot("FINNIFTY", date(2023, 1, 3)) == 40 and K.lot("FINNIFTY", date(2024, 8, 1)) == 25
    assert K26.HOLDOUT_END == date(2022, 12, 31)
    assert K.OUT == K26.OUT
