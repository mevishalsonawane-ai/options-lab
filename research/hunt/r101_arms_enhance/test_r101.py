"""R101 tests: look-ahead guards for every feature, the walks against R64's taking rules, the keep rules, the sizing
rule, BH and the gates. Pure: no stores, no lake."""
from __future__ import annotations

import math
from datetime import date

import numpy as np
import pandas as pd
import pytest

import r101cfg as K
import r101feat as F
import r101study as S


# ------------------------------------------------------------------------------------------------ VIX intraday (L2)
def _vix_day(opens, closes):
    o = np.full(F.NROWS, np.nan); c = np.full(F.NROWS, np.nan)
    o[:len(opens)] = opens; c[:len(closes)] = closes
    return (o, c)


def test_vix_change_reads_only_minutes_before_the_entry_row():
    d = _vix_day([10.0] * 10, [10.0, 10.0, 10.0, 10.0, 10.5, 99.0, 99.0, 99.0, 99.0, 99.0])
    assert F.vix_change(d, 5) == pytest.approx(0.05)          # closes[4] = 10.5 against the 09:15 open
    d2 = _vix_day([10.0] * 10, [10.0] * 5 + [99.0] * 5)
    assert F.vix_change(d2, 5) == pytest.approx(0.0)          # the entry minute's own close (row 5) is never read


def test_vix_change_unknown_is_nan_and_never_confirms():
    assert math.isnan(F.vix_change(None, 5))
    assert math.isnan(F.vix_change(_vix_day([np.nan] * 5, [10.0] * 5), 5))
    assert math.isnan(F.vix_change(_vix_day([10.0] * 5, [10.0] * 5), 0))
    assert not F.vix_confirms(math.nan, "P") and not F.vix_confirms(math.nan, "C")


def test_vix_confirms_put_up_call_down_at_one_percent():
    assert F.vix_confirms(0.01, "P") and not F.vix_confirms(0.009, "P") and not F.vix_confirms(0.01, "C")
    assert F.vix_confirms(-0.01, "C") and not F.vix_confirms(-0.005, "C") and not F.vix_confirms(-0.01, "P")


# ------------------------------------------------------------------------------------------------ daily features
def _daily(closes, start=date(2024, 1, 1), opens=None):
    days = pd.bdate_range(start, periods=len(closes))
    return pd.DataFrame(dict(date=[d.date() for d in days], open=opens if opens is not None else closes, close=closes))


def test_vix_regime_uses_the_previous_session_against_the_sixty_before_it():
    closes = [10.0] * 60 + [12.0] + [50.0]           # the 61st is "previous"; today's own close (50) is never read
    df = _daily(closes)
    today = df.date.iloc[-1]
    assert F.vix_regime(df, today) == "HI"            # 12 > median 10
    df2 = _daily([10.0] * 60 + [10.0] + [50.0])
    assert F.vix_regime(df2, df2.date.iloc[-1]) == "LO"       # equal to the median counts as LO
    assert F.vix_regime(_daily([10.0] * 30), date(2030, 1, 1)) is None       # too short


def test_vix_regime_ignores_sessions_on_or_after_the_day():
    df = _daily([10.0] * 61 + [99.0] * 10)
    assert F.vix_regime(df, df.date.iloc[61]) == "LO"


def test_rv_rank_is_point_in_time_and_in_unit_range():
    rng = np.random.default_rng(1)
    c = 100 * np.exp(np.cumsum(rng.normal(0, 0.01, 400)))
    c[-1] = 1e6                                        # today's own close must not matter
    df = _daily(list(c))
    r = F.rv_rank(df, df.date.iloc[-1])
    assert 0.0 <= r <= 1.0
    c2 = c.copy(); c2[-30:-1] = c2[-31] * np.exp(np.cumsum(rng.normal(0, 0.08, 29)))   # a wild last month
    assert F.rv_rank(_daily(list(c2)), df.date.iloc[-1]) >= 0.9
    assert math.isnan(F.rv_rank(_daily(list(c[:100])), df.date.iloc[99]))     # too short


def test_gap_pct_uses_today_open_and_previous_close():
    df = _daily([100.0, 110.0, 120.0], opens=[100.0, 100.0, 121.0])
    assert F.gap_pct(df, df.date.iloc[2]) == pytest.approx(0.1)
    assert math.isnan(F.gap_pct(df, date(2030, 1, 1)))
    assert math.isnan(F.gap_pct(df, df.date.iloc[0]))


# ------------------------------------------------------------------------------------------------ bar breadth (L1)
def test_breadth_counts_the_bar_ending_before_the_entry_row_only():
    d = date(2026, 1, 5)
    unds = ("A", "B", "C", "D", "E")
    bars = {u: (np.array([d, d], dtype=object), np.array([0, 1]), np.array([100.0, 100.0]), np.array([101.0, 101.0]),
                np.array([99.0, 99.0]), np.array([101.0, 99.0])) for u in unds}        # bar 0 green, bar 1 red
    col = F.bar_colours(bars)
    assert F.breadth_agree(col, unds, d, 5, "C") == 5          # entry 09:20 reads bar 0 (09:15-09:19)
    assert F.breadth_agree(col, unds, d, 5, "P") == 0
    assert F.breadth_agree(col, unds, d, 10, "P") == 5         # entry 09:25 reads bar 1
    assert F.breadth_agree(col, unds, d, 15, "P") == 0         # no bar 2: a missing bar never agrees
    assert F.breadth_agree(col, unds, d, 0, "C") == 0


# ------------------------------------------------------------------------------------------------ OOV features
def test_oov_far_in_value_widths_each_side():
    lev = np.array([100.0, 110.0, 90.0])
    assert F.oov_far(lev, 115.0, "C") == pytest.approx(0.25)
    assert F.oov_far(lev, 85.0, "P") == pytest.approx(0.25)
    assert math.isnan(F.oov_far(None, 115.0, "C"))
    assert math.isnan(F.oov_far(np.array([100.0, 100.0, 100.0]), 115.0, "C"))


# ------------------------------------------------------------------------------------------------ weekday (L4)
def test_worst_weekday_reads_only_trades_before_the_date():
    tr = [dict(day=date(2025, 1, 6), net=-500.0), dict(day=date(2025, 1, 7), net=100.0), dict(day=date(2025, 1, 13), net=200.0),
          dict(day=date(2025, 4, 1), net=-9999.0)]
    assert F.worst_weekday(tr, date(2025, 4, 1)) == 0           # Monday -300 < Tuesday +100; the April trade is not read
    assert F.worst_weekday(tr, date(2025, 1, 1)) is None
    picks = {(date(2025, 4, 1), date(2025, 6, 30)): 0}
    assert F.dow_drop_for(date(2025, 5, 5), picks) == 0 and F.dow_drop_for(date(2025, 1, 5), picks) is None


# ------------------------------------------------------------------------------------------------ walks
def _c(er, side="C", **kw):
    return dict(er=er, side=side, strike=100.0, **kw)


def _r(er, xr, net):
    return dict(er=er, xr=xr, net=net, day=date(2026, 1, 5), und="X", qty=1.0, rE=1.0, rX=1.0 + net)


def test_walk_book_one_position_and_a_removed_signal_frees_the_book():
    cands = [_c(5), _c(8), _c(20)]
    res = [_r(5, 15, 10.0), _r(8, 30, 50.0), _r(20, 40, 5.0)]
    assert [t["er"] for t in S.walk_book(cands, res)] == [5, 20]
    assert [t["er"] for t in S.walk_book(cands, res, [False, True, True])] == [8]
    assert S.walk_book(cands, [None, None, None]) == []


def test_walk_side2_one_open_a_side_and_two_fills_a_side():
    cands = [_c(5, "C"), _c(6, "P"), _c(8, "C"), _c(20, "C"), _c(30, "C"), _c(31, "P")]
    res = [_r(5, 15, 1.0), _r(6, 10, 1.0), _r(8, 9, 1.0), _r(20, 25, 1.0), _r(30, 35, 1.0), _r(31, 40, 1.0)]
    assert [t["er"] for t in S.walk_side2(cands, res)] == [5, 6, 20, 31]     # 8 overlaps the 5-15 call; 30 is the 3rd call
    assert [t["er"] for t in S.walk_side2(cands, res, [False, True, True, True, True, True])] == [6, 8, 20, 31]


def test_walk_one_takes_the_first_kept_fill():
    cands = [_c(5), _c(5)]
    assert S.walk_one(cands, [None, _r(5, 9, 1.0)])[0]["er"] == 5
    assert S.walk_one(cands, [_r(5, 9, 1.0), _r(5, 9, 2.0)], [False, False]) == []


def test_run_variant_drops_random_ids_and_sorts():
    units = [(("X", date(2026, 1, 5), 5), [_c(5), _c(20)], [_r(5, 10, 1.0), _r(20, 30, 2.0)], "book")]
    assert len(S.run_variant(units, None)) == 2
    assert [t["er"] for t in S.run_variant(units, None, {(0, 0)})] == [20]


# ------------------------------------------------------------------------------------------------ keep rules
def test_keep_rules_follow_the_prereg_and_unknown_never_confirms():
    c = dict(er=10, side="P", f_vixreg="LO", f_gap=0.004, f_breadth=3, f_vixchg=0.012, f_rv=0.5, f_dow=0, f_far=0.25, f_open3=3)
    assert S.keep_signal("B1", c) and not S.keep_signal("B1b", c) and S.keep_signal("B2", c) and S.keep_signal("B3", c)
    assert S.keep_signal("B4", c) and S.keep_signal("L1", c) and S.keep_signal("L2", c) and S.keep_signal("L3", c)
    assert not S.keep_signal("L3b", c) and S.keep_signal("D2", c) and S.keep_signal("D3", c) and S.keep_signal("B5", c)
    assert S.keep_signal("L4", c, None) and not S.keep_signal("L4", c, 0) and S.keep_signal("L4", c, 1)
    u = dict(er=5, side="C", f_vixreg=None, f_gap=math.nan, f_breadth=2, f_vixchg=math.nan, f_rv=math.nan, f_dow=4, f_far=math.nan, f_open3=2)
    for v in ("B1", "B1b", "B2", "B3", "L1", "L2", "L3", "L3b", "D2", "D3"):
        assert not S.keep_signal(v, u), v
    assert not S.keep_signal("B4", dict(er=K.NOON_ROW + 1, side="C")) and S.keep_signal("B4", dict(er=K.NOON_ROW, side="C"))
    with pytest.raises(ValueError):
        S.keep_signal("Z9", c)


# ------------------------------------------------------------------------------------------------ sizing (B5)
def _charges(und, rE, rX, qty, day):
    return np.array([20.0 + 0.001 * rX[0] * qty])       # a fixed part (per order) and a part that scales


def test_sized_doubles_the_gross_but_not_the_fixed_charge_and_per_lot_counts_lots():
    t = dict(und="X", day=date(2026, 1, 5), rE=100.0, rX=110.0, qty=10.0, net=(10.0 * 10.0) - (20.0 + 0.001 * 110.0 * 10.0), f_count=5)
    one = S.sized([t], lambda t: 1, _charges)[0]
    two = S.sized([t], lambda t: 2 if t["f_count"] == 5 else 1, _charges)[0]
    assert one["net"] == pytest.approx(t["net"]) and one["lots"] == 1
    assert two["lots"] == 2 and two["net"] == pytest.approx(200.0 - (20.0 + 0.001 * 110.0 * 20.0))
    assert two["net"] > 2 * one["net"] - 1e-9          # the per-order brokerage is paid once
    assert S.per_lot([two]) == pytest.approx(two["net"] / 2)
    tw = S.sizing_twins([t, dict(t, f_count=4)], 1, _charges, 7, np.random.default_rng(0))
    assert tw.shape == (7,) and np.isfinite(tw).all()


# ------------------------------------------------------------------------------------------------ stats, BH, gates
def test_stats_win_rate_net_drawdown_and_top5():
    tr = [dict(day=date(2026, 1, 5), net=100.0), dict(day=date(2026, 1, 5), net=-50.0), dict(day=date(2026, 1, 6), net=-80.0),
          dict(day=date(2026, 1, 7), net=10.0)]
    s = S.stats(tr)
    assert s["trades"] == 4 and s["win"] == 0.5 and s["net"] == -20 and s["worst_day"] == -80 and s["max_dd"] == 80
    assert s["ex_top5"] == 0 and s["top5_share"] is None and s["days"] == 3   # fewer than 5 trades: nothing left
    assert S.stats([])["trades"] == 0


def test_bh_is_monotone_and_bounded():
    q = S.bh([0.01, 0.04, 0.03, 0.5])
    assert np.all(q <= 1) and q[0] <= q[2] <= q[1] <= q[3]
    assert S.bh([]).size == 0


def test_gates_need_every_condition():
    d = dict(wf_diff=100)
    f = dict(diff=100, twin_p=0.01, top5_diff=50, all=dict(trades=60))
    assert S.gates("L1", d, f, 0.05)["passed"]
    assert "walk-forward not above B0" in S.gates("L1", dict(wf_diff=0), f, 0.05)["fails"]
    assert "final not above B0" in S.gates("L1", d, dict(f, diff=-1), 0.05)["fails"]
    assert "twin p >= 0.05" in S.gates("L1", d, dict(f, twin_p=0.05), 0.05)["fails"]
    assert "BH q >= 0.10" in S.gates("L1", d, f, 0.10)["fails"]
    assert "< 40 final trades" in S.gates("L1", d, dict(f, all=dict(trades=39)), 0.05)["fails"]
    assert "carried by its 5 best trades" in S.gates("L1", d, dict(f, top5_diff=0), 0.05)["fails"]


def test_prereg_has_twenty_tests_and_every_variant_a_label():
    assert len(K.ALL_TESTS) == 20 and len(set(K.ALL_TESTS)) == 20
    assert all(v in K.LABEL for v in K.ALL_TESTS)
