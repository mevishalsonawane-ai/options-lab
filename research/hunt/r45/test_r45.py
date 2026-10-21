"""R45 tests: python3 -m pytest research/hunt/r45/test_r45.py -q   (no lake needed: synthetic sessions only)"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r45cfg as K  # noqa: E402
import r45data as D  # noqa: E402
import r45sim as S  # noqa: E402
import r45study as T  # noqa: E402

GRID = [555 + 5 * k for k in range(75)]          # 09:15 .. 15:25


def flat(n, px=100.0):
    return [px] * n, [px] * n, [px] * n, [px] * n


# ------------------------------------------------------------------------------------------------ Bollinger cross
def test_bollinger_matches_pandas_and_is_nan_before_20():
    c = np.random.default_rng(1).normal(100, 3, 60)
    lo = S.bollinger_lower(c)
    s = pd.Series(c)
    ref = s.rolling(20).mean() - 2 * s.rolling(20).std(ddof=0)
    assert np.isnan(lo[:19]).all()
    assert np.allclose(lo[19:], ref.values[19:])
    ref1 = s.rolling(20).mean() - 2 * s.rolling(20).std(ddof=1)
    assert np.allclose(S.bollinger_lower(c, ddof=1)[19:], ref1.values[19:])
    assert np.isnan(S.bollinger_lower(c[:10])).all()                       # too short: no band


def test_cross_below_needs_a_fresh_cross():
    close = [10, 9, 8, 8]
    lower = [9, 9, 9, 9]
    # bar1: 9 < 9? no. bar2: 8 < 9 and 9 >= 9 -> cross. bar3: still below -> no new signal
    assert list(S.cross_below(close, lower)) == [False, False, True, False]
    assert not S.cross_below([5, 4], [np.nan, 4.5]).any()                  # NaN band before: no signal
    assert len(S.cross_below([], [])) == 0


def _session_with_signal_at(i_sig, n=40, base=100.0):
    """Closes flat at `base` (with a tiny wiggle) then a drop at i_sig that crosses the lower band."""
    rng = np.random.default_rng(3)
    c = base + rng.normal(0, 0.2, n)
    c[i_sig] = base - 10
    c[i_sig + 1:] = base - 10
    return c


def test_entry_is_the_next_bars_open():
    c = _session_with_signal_at(25)
    lo = S.bollinger_lower(c)
    o = list(c - 0.5)
    o[26] = 77.0
    h = [x + 0.1 for x in c]
    l_ = [x - 0.1 for x in c]
    h[26], l_[26] = 78.0, 76.9
    tr = S.simulate_session(GRID[:40], c, lo, lambda e: (o, h, l_, list(c)), S.Rules())
    assert tr[0].sig == 25 and tr[0].e == 26 and tr[0].entry == 77.0


# ------------------------------------------------------------------------------------------------ stop / lock
def _bars(n=10, px=100.0):
    o, h, l_, c = (list(x) for x in flat(n, px))
    return o, h, l_, c


def test_stop_fills_at_level_without_gap_and_at_open_with_gap():
    o, h, l_, c = _bars()
    o[1], h[1], l_[1], c[1] = 85.0, 86.0, 84.0, 85.0                       # bar e+1 opens below the 90 stop
    x, px, why = S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules())
    assert (x, px, why) == (1, pytest.approx(90.0), "stop")
    x, px, why = S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules(gap=True))
    assert (x, px, why) == (1, 85.0, "stop")


def test_lock_fills_at_level_and_gap_and_prev_bar_rule():
    o, h, l_, c = _bars()
    h[0] = 130.0                                                           # bar e reaches +30% -> 25% lock armed
    o[1], h[1], l_[1], c[1] = 120.0, 121.0, 119.0, 120.0                  # bar e+1 opens below 125
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules())[1:] == (pytest.approx(125.0), "lock")
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules(gap=True, prev_lock=True))[1:] == (120.0, "lock")


def test_same_bar_high_cannot_arm_a_lock_under_p2():
    o, h, l_, c = _bars()
    h[0], l_[0], c[0] = 130.0, 99.0, 100.0                                 # +30% and back to entry in ONE bar
    # their code: high arms 25%, the same low (99 <= 125) fills the lock at 125
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules())[1:] == (pytest.approx(125.0), "lock")
    # P2: the lock is armed only from the next bar; bar e+1 at 100 then fills it at 125? no - it opens at 100 <= 125:
    x, px, why = S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules(gap=True, prev_lock=True))
    assert (x, px, why) == (1, 100.0, "lock")


def test_stepped_lock_uses_the_highest_level_reached():
    o, h, l_, c = _bars()
    h[0] = 180.0                                                           # +80% -> 75% lock
    l_[1], h[1] = 170.0, 172.0
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules())[1:] == (pytest.approx(175.0), "lock")


# ------------------------------------------------------------------------------------------------ hold length
def test_their_hold_exits_at_close_of_e_plus_3_without_checking_it():
    o, h, l_, c = _bars()
    l_[3], c[3] = 50.0, 95.0                                               # bar e+3 trades through the stop
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules()) == (3, 95.0, "time")
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules(checked=4, exit_at=3))[1:] == (pytest.approx(90.0), "stop")
    c[2] = 101.0
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules(checked=3, exit_at=2)) == (2, 101.0, "time")


def test_square_off_at_1515_open_and_no_entry_at_or_after_1515():
    t = GRID
    k1515 = t.index(15 * 60 + 15)
    o, h, l_, c = (list(x) for x in flat(75))
    o[k1515] = 99.0
    assert S.run_trade(t, o, h, l_, c, k1515 - 1, S.Rules()) == (k1515, 99.0, "sq")
    close = np.full(75, 100.0)
    close[k1515 - 1] = 50.0                                                # a cross whose entry bar is 15:15
    lower = np.full(75, 80.0)
    assert S.simulate_session(t, close, lower, lambda e: (o, h, l_, c), S.Rules()) == []


def test_max_two_trades_and_no_overlap():
    n = 75
    close = np.full(n, 100.0)
    lower = np.full(n, 90.0)
    for i in (21, 22, 30, 40, 50):                                         # alternate above / below the band
        close[i] = 80.0
    bars = (list(close), list(close + 1), list(close - 1), list(close))
    tr = S.simulate_session(GRID, close, lower, lambda e: bars, S.Rules())
    assert len(tr) == 2
    assert tr[1].sig > tr[0].x                                             # second signal after the first exit
    assert len(S.simulate_session(GRID, close, lower, lambda e: bars, S.Rules(after="signal", max_trades=9))) == 4


# ------------------------------------------------------------------------------------------------ gaps / empty
def test_missing_entry_bar_means_no_trade_and_missing_bars_are_skipped():
    o, h, l_, c = _bars()
    o[0] = None
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules()) is None
    o, h, l_, c = _bars()
    for k in (1, 2, 3):
        o[k] = h[k] = l_[k] = c[k] = None                                  # contract did not trade after entry
    assert S.run_trade(GRID[:10], o, h, l_, c, 0, S.Rules()) == (3, 100.0, "time")
    assert S.simulate_session([], [], [], lambda e: ([], [], [], []), S.Rules()) == []


def test_resample5_and_on_grid():
    m = np.array([555, 556, 559, 560, 570])
    t, o, h, l_, c = D.resample5(m, [1, 2, 3, 4, 5], [2, 9, 4, 5, 6], [0.5, 1, 1, 3, 4], [1.5, 2.5, 3.5, 4.5, 5.5])
    assert list(t) == [555, 560, 570]
    assert list(o) == [1, 4, 5] and list(h) == [9, 5, 6] and list(l_) == [0.5, 3, 4] and list(c) == [3.5, 4.5, 5.5]
    assert len(D.resample5([], [], [], [], [])[0]) == 0
    g = D.on_grid([555, 560, 565, 570], t, c)[0]
    assert g == [3.5, 4.5, None, 5.5]


# ------------------------------------------------------------------------------------------------ sessions
def _mk_session(day=date(2024, 1, 3), n_min=375, strikes=None):
    m = np.arange(555, 555 + n_min)
    px = 100 + np.sin(np.arange(n_min) / 9.0) * 5
    rstr = np.full(n_min, 21700.0) if strikes is None else np.asarray(strikes, dtype=float)
    t, o, h, l_, c = D.resample5(m, px, px + 0.5, px - 0.5, px)
    calls = {21700.0: (m, px, px + 0.5, px - 0.5, px), 21650.0: (m, px + 30, px + 30.5, px + 29.5, px + 30)}
    return D.Session(day, t, o, h, l_, c, m, rstr, calls)


def test_strike_change_detection_and_fixed_contract_bars():
    st = np.full(375, 21700.0)
    st[100:] = 21650.0                                                     # ATM rolls down at 10:55
    s = _mk_session(strikes=st)
    assert s.strikes_between(555, 600) == 1
    assert s.strikes_between(640, 660) == 2
    assert s.strike_at(555) == 21700.0 and s.strike_at(700) == 21650.0
    assert s.strike_at(10_000) is None
    t, o, *_ = s.contract5(21650.0)
    assert len(t) == 75 and o[0] == pytest.approx(130.0)
    assert len(s.contract5(99999.0)[0]) == 0                               # never in the lake rows


def test_prep_prices_each_trade_on_the_strike_held_at_entry():
    st = np.full(375, 21700.0)
    st[100:] = 21650.0
    s = _mk_session(strikes=st)
    v = K.variant(K.T0, dict(price="fixed"))
    p = T.Prep(s, v)
    e_early, e_late = 2, 30                                                # 09:25 (21700) and 11:45 (21650)
    assert p.bars(e_early)[0][e_early] == pytest.approx(p._own[0][e_early])
    assert p.bars(e_late)[0][e_late] == pytest.approx(p._own[0][e_late] + 30)
    v915 = K.variant(K.T0, dict(signal="k915"))
    assert T.Prep(s, v915).k == 21700.0


def test_expiry_filters():
    assert T.their_expiry([5, 4, 3, 0.9, 2])
    assert not T.their_expiry([5, 4, 3, 2, 1.0])
    assert not T.their_expiry([])


def test_run_variant_skips_short_days_and_real_expiry_days(monkeypatch):
    days = [_mk_session(date(2024, 1, 3)), _mk_session(date(2024, 1, 4)), _mk_session(date(2024, 1, 5), n_min=100)]
    monkeypatch.setattr(D, "load", lambda und, series: days)
    monkeypatch.setattr(D, "expiry_days", lambda und, series: {date(2024, 1, 4)})
    seen = []
    real = S.simulate_session
    monkeypatch.setattr(S, "simulate_session", lambda t, c, lo, b, r, entries=None: seen.append(len(t)) or real(t, c, lo, b, r, entries))
    T.run_variant(K.variant(K.T0, dict(expiry="real")), "design")
    assert seen == [75]                                                    # 4 Jan expiry and the 20-bar day skipped
    seen.clear()
    T.run_variant(K.variant(K.T0, dict(expiry="none")), "design")
    assert seen == [75, 75]


# ------------------------------------------------------------------------------------------------ costs, stats
def test_costs_slippage_and_lots():
    assert T.net_of(100, 110, 75, date(2024, 1, 3), "flat95", 0.0) == pytest.approx(10 * 75 - 95)
    d = T.net_of(100, 110, 75, date(2024, 1, 3), "dated", 0.0)
    assert 650 < d < 750 - 40                                              # brokerage 2 x 20 + GST + statutory
    assert T.net_of(100, 110, 75, date(2024, 1, 3), "dated", 0.01) < d - 150
    assert T.lot_on("NIFTY", date(2022, 5, 1)) == 50 and T.lot_on("NIFTY", date(2024, 6, 1)) == 25
    assert T.lot_on("NIFTY", date(2025, 3, 1)) == 75 and T.lot_on("NIFTY", date(2026, 2, 1)) == 65


def test_bh_and_p_value():
    q = T.bh({"a": 0.01, "b": 0.04, "c": 0.03, "d": 0.5})
    assert q["a"] == pytest.approx(0.04) and q["c"] == pytest.approx(0.0533, abs=1e-3) and q["d"] == 0.5
    assert T.p_value(10.0, [0, 1, 2]) == pytest.approx(0.25)
    assert T.p_value(1.0, [0, 1, 2]) == pytest.approx(0.75)


def test_metrics():
    tr = [dict(day="2024-01-03", e=600, net=100.0, why="time", roll_change=True, expiry=False),
          dict(day="2024-01-03", e=700, net=-50.0, why="stop", roll_change=False, expiry=False),
          dict(day="2024-02-01", e=600, net=-80.0, why="stop", roll_change=False, expiry=True)]
    m = T.metrics(tr)
    assert m["trades"] == 3 and m["green"] == "1/2" and m["max_dd"] == 130 and m["pf"] == pytest.approx(0.77, abs=0.01)
    assert m["roll_change_pct"] == pytest.approx(33.3) and m["expiry_trades"] == 1
    assert T.metrics([]) == dict(trades=0)


def test_twins_same_count_and_eligible_bars():
    c = np.full(75, 100.0)
    bars = (list(c), list(c + 1), list(c - 1), list(c))
    rng = np.random.default_rng(0)
    tw = S.twin_session(GRID, lambda e: bars, S.Rules(), 2, rng)
    assert len(tw) == 2 and all(x.sig >= 20 and GRID[x.e] < S.CUT for x in tw) and tw[1].sig > tw[0].x
    a = S.twin_session(GRID, lambda e: bars, S.Rules(), 2, np.random.default_rng(5))
    b = S.twin_session(GRID, lambda e: bars, S.Rules(), 2, np.random.default_rng(5))
    assert [x.sig for x in a] == [x.sig for x in b]
    assert S.twin_session(GRID[:15], lambda e: bars, S.Rules(), 2, rng) == []


def test_lookahead_prefix_invariance_synthetic():
    """P7 on synthetic paths: signals and finished trades on any prefix equal the full session's."""
    rng = np.random.default_rng(11)
    for _ in range(20):
        c = 100 + np.cumsum(rng.normal(0, 1.5, 75))
        o = np.r_[c[0], c[:-1]] + rng.normal(0, 0.3, 75)
        h, l_ = np.maximum(o, c) + 0.5, np.minimum(o, c) - 0.5
        own = [list(o), list(h), list(l_), list(c)]
        lo = S.bollinger_lower(c)
        full = S.simulate_session(GRID, c, lo, lambda e: own, S.Rules())
        for cut in range(2, 76):
            cc = c[:cut]
            lo2 = S.bollinger_lower(cc)
            assert np.array_equal(S.cross_below(cc, lo2), S.cross_below(c, lo)[:cut])
            part = [x[:cut] for x in own]
            tr = S.simulate_session(GRID[:cut], cc, lo2, lambda e: part, S.Rules())
            assert [(x.e, x.entry, x.exit) for x in tr if x.x < cut - 1] == [(x.e, x.entry, x.exit) for x in full if x.x < cut - 1]


def test_holdout_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(K, "RESULTS", tmp_path)
    with pytest.raises(SystemExit, match="design step first"):
        T.step_holdout()
    (tmp_path / "holdout.json").write_text(json.dumps({}))
    with pytest.raises(SystemExit, match="already read"):
        T.step_holdout()


def test_parts_never_include_the_embargo():
    assert T.in_part(date(2026, 4, 13), "design") and not T.in_part(date(2026, 4, 14), "design")
    assert not T.in_part(date(2026, 4, 20), "all") and not T.in_part(date(2026, 4, 26), "holdout")
    assert T.in_part(date(2026, 4, 27), "holdout")
    with pytest.raises(ValueError):
        T.in_part(date(2026, 1, 1), "x")
