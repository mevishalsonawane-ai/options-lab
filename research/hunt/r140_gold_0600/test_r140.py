"""R140 tests: engines (stop, lock, time exit, gaps), costs, DST, the window table on synthetic minutes, the
walk-forward sign (uses only earlier days), the look-ahead guard, BH, drawdown and the twin p. No lake data needed."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140 as R


def minutes(start, closes, spread=0.0):
    idx = pd.date_range(start, periods=len(closes), freq="1min")
    c = np.asarray(closes, dtype=float)
    o = np.concatenate([[c[0]], c[:-1]])
    return pd.DataFrame({"open": o, "high": np.maximum(o, c) + spread, "low": np.minimum(o, c) - spread, "close": c},
                        index=idx)


# ------------------------------------------------------------------ engines
def test_sim_xau_stop_long():
    X = R.Xau(minutes("2024-01-02 00:00", [100, 100, 99, 97, 96, 95]))
    px, why = R.sim_xau(X, 1, 5, +1, stop=2.0, trig=5.0)
    assert why == "stop" and px == pytest.approx(98.0)


def test_sim_xau_stop_gap_fills_at_open():
    df = minutes("2024-01-02 00:00", [100, 100, 100, 90, 90])
    df.loc[df.index[3], ["open", "low"]] = [95.0, 90.0]     # opens through the 98 stop
    X = R.Xau(df)
    px, why = R.sim_xau(X, 1, 4, +1, stop=2.0, trig=5.0)
    assert why == "stop" and px == pytest.approx(95.0)


def test_sim_xau_lock_short():
    X = R.Xau(minutes("2024-01-02 00:00", [100, 100, 98, 96, 94, 95, 97, 99]))
    px, why = R.sim_xau(X, 1, 7, -1, stop=3.0, trig=4.0)
    # peak short profit 6 (at 94): lock = 100 - 0.6*6 = 96.4; the 97 minute crosses it
    assert why == "lock" and px == pytest.approx(96.4)


def test_sim_xau_time_exit():
    X = R.Xau(minutes("2024-01-02 00:00", [100, 100.5, 101, 100.8, 101.2]))
    px, why = R.sim_xau(X, 1, 4, +1, stop=5.0, trig=5.0)
    assert why == "time" and px == X.o[4]


def test_sim_close_stop_lock_time():
    F = np.array([1000, 1001, 997.5, 990])
    assert R.sim_close(F, 0, 3, +1, 0.002, 0.01) == (997.5, "stop")
    F = np.array([1000, 1003, 1006, 1003.5, 1001])
    px, why = R.sim_close(F, 0, 4, +1, 0.01, 0.005)        # peak +6 -> lock 1003.6, 1003.5 crosses
    assert why == "lock" and px == 1003.5
    F = np.array([1000, 1001, 1002])
    assert R.sim_close(F, 0, 2, -1, 0.01, 0.01) == (1002, "time")


def test_mcx_net_costs_positive_and_double():
    g = 10 * 100.0
    n1 = R.mcx_net(+1, 130000, 130100, 1.0)
    n2 = R.mcx_net(+1, 130000, 130100, 2.0)
    assert n1 < g and n2 < n1
    assert (g - n2) == pytest.approx(2 * (g - n1))
    # a short pays the same costs on a mirrored move
    assert R.mcx_net(-1, 130100, 130000) == pytest.approx(n1, rel=0.02)


# ------------------------------------------------------------------ time
def test_dst_regimes():
    assert R.us_summer(date(2024, 7, 1)) is True
    assert R.us_summer(date(2024, 1, 15)) is False
    # the NY close in UTC: 21:00 in summer, 22:00 in winter
    assert R.ny_to_utc(date(2024, 7, 1), 17, 0) == pd.Timestamp("2024-07-01 21:00")
    assert R.ny_to_utc(date(2024, 1, 15), 17, 0) == pd.Timestamp("2024-01-15 22:00")


def _two_days():
    # Mon 1 Jul 2024 20:00 UTC -> Wed 3 Jul 06:00 UTC, a slow up-drift with a wiggle
    n = 34 * 60
    c = 2300 + np.cumsum(np.where(np.arange(n) % 7 == 0, -0.3, 0.1))
    return minutes("2024-07-01 20:00", c)


def test_window_table_basic():
    X = R.Xau(_two_days())
    wt = R.window_table(X)
    assert date(2024, 7, 2) in wt.index
    r = wt.loc[date(2024, 7, 2)]
    assert r.n == 120 and r.summer
    a = X.df.loc["2024-07-02 00:30"].open
    z = X.df.loc["2024-07-02 02:29"].close
    assert r.W == pytest.approx(z / a - 1)
    assert r.ny_day == date(2024, 7, 1)


def test_window_needs_110_minutes():
    df = _two_days()
    df = df.drop(df.loc["2024-07-02 00:40":"2024-07-02 00:55"].index)    # 16 minutes missing -> 104 left
    wt = R.window_table(R.Xau(df))
    assert date(2024, 7, 2) not in wt.index


def test_lookahead_window_signals():
    """U, P, first candle and the window stats must not change when prices AFTER 08:00 IST change."""
    df = _two_days()
    wt1 = R.window_table(R.Xau(df))
    df2 = df.copy()
    after = df2.index >= pd.Timestamp("2024-07-02 02:30")
    df2.loc[after, ["open", "high", "low", "close"]] *= 1.05
    wt2 = R.window_table(R.Xau(df2))
    cols = ["W", "U", "P", "first", "w_hi", "w_lo"]
    pd.testing.assert_frame_equal(wt1.loc[[date(2024, 7, 2)], cols], wt2.loc[[date(2024, 7, 2)], cols])


def test_atr_uses_only_past_bars():
    df = _two_days()
    X = R.Xau(df)
    b = X.bars15()
    t = pd.Timestamp("2024-07-02 00:30")
    a1 = R.atr15_before(b, t)
    df2 = df.copy()
    df2.loc[df2.index >= t, "high"] += 50
    a2 = R.atr15_before(R.Xau(df2).bars15(), t)
    assert np.isfinite(a1) and a1 == pytest.approx(a2)


def test_range_trigger_first_close_outside():
    idx = pd.date_range("2024-07-02 02:30", periods=4, freq="15min")
    b = pd.DataFrame({"open": [10, 10, 10, 10], "high": [11, 12, 13, 9], "low": [9, 9, 9, 7],
                      "close": [10, 10.5, 12, 8]}, index=idx)
    t, s = R.range_trigger(b, date(2024, 7, 2), hi=11.0, lo=9.0)
    assert s == 1 and t == pd.Timestamp("2024-07-02 03:15")
    t, s = R.range_trigger(b.iloc[:2], date(2024, 7, 2), hi=11.0, lo=9.0)
    assert s == 0 and t is None


# ------------------------------------------------------------------ walk-forward, stats
def test_wf_sign_only_earlier_days():
    idx = [date(2024, 1, k) for k in range(1, 11)]
    sig = pd.Series([1.0] * 10, index=idx)
    out = pd.Series([1.0] * 5 + [-9.0] * 5, index=idx)        # follow before 6 Jan, fade after
    assert R.wf_sign(sig, out, date(2024, 1, 6)) == 1
    assert R.wf_sign(sig, out, date(2024, 1, 11)) == -1
    assert R.wf_sign(sig, out, date(2024, 1, 1)) == 1         # no history -> follow (fixed default)


def test_bh_and_drawdown():
    q = R.bh({"a": 0.01, "b": 0.04, "c": 0.5})
    assert q["a"] == pytest.approx(0.03) and q["b"] == pytest.approx(0.06) and q["c"] == pytest.approx(0.5)
    assert R.max_dd([100, -50, -80, 40]) == pytest.approx(-130)
    assert R.max_dd([-10, 5]) == pytest.approx(-10)
    assert R.max_dd([]) == 0.0


def test_twin_p_detects_edge_and_luck():
    rng = np.random.default_rng(0)
    L = rng.normal(0, 1, (300, 1))
    S = -L
    p_hi, _ = R.twin_p(L, S, 50, 0.5, total=40.0, rng=rng, n_sets=500)
    p_lo, _ = R.twin_p(L, S, 50, 0.5, total=0.0, rng=rng, n_sets=500)
    assert p_hi < 0.01 and 0.2 < p_lo < 0.8


def test_gates_logic():
    s = dict(total=100, total2=50, n=120, folds=[None, 1, 2, -1, 3], dd=-1000, worst_month=-500)
    g = R.gates(s, 0.01, 0.05, "A1")
    assert g["all"]
    s2 = dict(s, total2=-1)
    assert not R.gates(s2, 0.01, 0.05, "A1")["all"]
    s3 = dict(s, folds=[1, 1, -1, -1], dd=-45000)
    g3 = R.gates(s3, 0.01, 0.05, "B1")
    assert not g3["account"] and not g3["folds"]


def test_release_dates_cover_design_start():
    d = R.us_release_dates()
    assert min(d) <= date(2023, 10, 12) and date(2026, 10, 2) in d
