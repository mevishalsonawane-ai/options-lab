"""R98 tests: bracket labels, the futures walk and fill, futures costs, candle features (look-ahead guard), clock
anchors across daylight-saving changes, direction signing, rules on the grid (synthetic data only)."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r98_catch100 import candles as CA
from research.hunt.r98_catch100 import engine as EN
from research.hunt.r98_catch100 import labels as L
from research.hunt.r98_catch100 import rules as R


def test_bracket_target_stop_time_and_edges():
    F = np.array([0, 10, 50, 120, 0.0])
    assert L.bracket(F, 0, 1, 100, 100, 60) == (1, 110.0, 2)          # entry 10, close 120 first
    assert L.bracket(F, 0, -1, 100, 30, 60) == (-1, -40.0, 1)         # short from 10: 50 is -40
    assert L.bracket(np.array([0, 10, 20, 30.0]), 0, 1, 100, 100, 60) == (0, 20.0, 2)
    assert L.bracket(np.array([5.0]), 0, 1, 100, 100, 60) is None
    assert L.bracket(np.array([5.0, 6.0]), 0, 1, 100, 100, 60) == (0, 0.0, 0)


def test_fut_walk_limit_target_stop_at_close_and_lock():
    assert EN.fut_walk(np.array([20, 60, 130.0])) == (2, "target", 100.0)     # limit: no overshoot
    assert EN.fut_walk(np.array([-20, -130.0])) == (1, "stop", -130.0)         # market after the touch
    assert EN.fut_walk(np.array([75, 40, 15.0])) == (2, "lock", 15.0)           # +70 seen: stop at +20
    assert EN.fut_walk(np.array([10, 30.0])) == (1, "time", 30.0)
    assert EN.fut_walk(np.array([]))[0] == -1


def test_fut_cost_points_reasonable():
    c = L.fut_cost_pts(140000.0)
    assert 30 < c < 60                     # charges (CTT 0.01% on the sale dominates) + one full spread


def test_sim_future_fill_next_close_and_squareoff():
    from research.hunt.r96_mcx_buy.tests.test_r96 import make_book
    up = lambda i, ts: 140000.0 + 5.0 * (i % 871)   # noqa: E731
    bk = make_book(days=1, path=up)
    m = bk.m
    i = m.at_or_after(m.days[0], 600)
    t = EN.sim_future(bk, i, 1)
    assert t["fill"] == [m.F[i + 1]] and t["why"] == "target" and t["gross"] == pytest.approx(1000.0)
    assert t["net"] == pytest.approx(t["gross"] - t["cost"]) and t["net2"] < t["net"]
    j = m.at_or_after(m.days[0], 23 * 60 + 20)       # after the square-off (23:15 here): no trade
    assert EN.sim_future(bk, j, 1) is None


def _sess(n=300, seed=4):
    rng = np.random.default_rng(seed)
    ts = pd.date_range("2026-03-03 09:00", periods=n, freq="1min").delete([40, 41, 133])
    F = 140000 + np.cumsum(rng.normal(0, 30, len(ts)))
    return ts, F, (ts.hour * 60 + ts.minute).values


def test_candles_lookahead_guard():
    ts, F, tod = _sess()
    full = CA.session_candles(ts, F, tod)
    for i in (0, 3, 4, 5, 14, 15, 39, 40, 100, 160, len(ts) - 1):
        part = CA.session_candles(ts[:i + 1], F[:i + 1], tod[:i + 1])
        a, b = full.iloc[i], part.iloc[i]
        for c in full.columns:
            if np.isnan(a[c]):
                assert np.isnan(b[c]), (i, c)
            else:
                assert a[c] == pytest.approx(b[c]), (i, c)


def test_candles_use_completed_bars_only():
    ts = pd.date_range("2026-03-03 09:00", periods=12, freq="1min")
    F = np.array([100, 110, 90, 105, 120, 120, 120, 120, 120, 50, 60, 70.0])
    cf = CA.session_candles(ts, F, (ts.hour * 60 + ts.minute).values, sizes=(5,))
    assert cf.c5_body.iloc[:4].isna().all()                 # the first 5-min bar completes with row 4
    assert cf.c5_body.iloc[4] == pytest.approx(20.0)         # 100 -> 120
    assert cf.c5_body.iloc[8] == pytest.approx(20.0)         # the 09:05 bar is not complete before row 9
    assert cf.c5_body.iloc[9] == pytest.approx(-70.0)        # 120 -> 50


def test_clock_anchors_follow_daylight_saving():
    w, s = CA.anchors("2026-01-15"), CA.anchors("2026-07-15")
    assert w["sge_pm"] == s["sge_pm"] == 11 * 60 + 45
    assert w["lbma_pm"] == 20 * 60 + 30 and s["lbma_pm"] == 19 * 60 + 30
    assert w["us_data"] == 19 * 60 and s["us_data"] == 18 * 60
    assert w["comex_open"] == 18 * 60 + 50


def test_signed_features_flip_for_shorts():
    df = pd.DataFrame(dict(c5_body=[30.0], c5_cl=[0.8], c5_rsi=[70.0], c5_uw=[0.1], c5_lw=[0.5], c5_hammer=[1.0],
                           c5_star=[0.0], c5_rng=[50.0]))
    lo, sh = CA.signed(df, 1).iloc[0], CA.signed(df, -1).iloc[0]
    assert lo.c5_body == 30 and sh.c5_body == -30 and sh.c5_cl == pytest.approx(-0.3) and sh.c5_rsi == -20
    assert sh.c5_uw == 0.5 and sh.c5_lw == 0.1 and sh.c5_star == 1.0 and sh.c5_rng == 50


def test_rule_masks_and_windows():
    df = pd.DataFrame(dict(tod=[600, 800, 800, 1300], i_sig=[1, 2, 3, 4], side=[1, 1, -1, 1], day=[0, 0, 0, 0],
                           xau_lead15_bp=[12, 12, 3, 15], c5_mom3=[-1, 5, -1, 0], twap_dev=[-700, 0, -700, -700],
                           c15_streak=[-2, 0, -3, 0], c5_body=[-60, 0, -70, 0], pred=[60, 10, 56, np.nan]))
    assert list(R.RULES["R98A"].signals(df).i_sig) == [1, 4]
    assert list(R.RULES["R98B"].signals(df).i_sig) == [4]
    assert list(R.RULES["R98D"].signals(df).i_sig) == [1, 4]
    assert list(R.RULES["R98F"].signals(df).i_sig) == [1, 3]
    assert list(R.RULES["R98C"].signals(df).i_sig) == [1, 3]
    assert R.RULES["R98E"].instrument == "option"
