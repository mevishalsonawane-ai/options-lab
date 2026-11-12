"""R165 tests (PREREG section 6): Black-Scholes and IV, decision-minute Greeks and their look-ahead guard, the strike
chooser, the index stop, the tick-rule proxy, the gates and BH. Synthetic data only (no lake)."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r165lib as L  # noqa: E402

DAY, EXP = date(2025, 6, 3), date(2025, 6, 5)


# ================================================================================================ Black-Scholes
@pytest.mark.parametrize("side", ["C", "P"])
@pytest.mark.parametrize("K", [24000.0, 24500.0, 25000.0])
@pytest.mark.parametrize("iv", [0.08, 0.15, 0.45])
def test_iv_round_trip(side, K, iv):
    T = 2.5 / 365
    P = L.bs_price(side, 24500.0, K, T, iv)
    assert L.implied_vol(side, P, 24500.0, K, T) == pytest.approx(iv, abs=1e-4)


def test_put_call_parity_and_delta_bounds():
    S, K, T, iv = 24500.0, 24400.0, 3 / 365, 0.14
    c, p = L.bs_price("C", S, K, T, iv), L.bs_price("P", S, K, T, iv)
    assert c - p == pytest.approx(S - K * np.exp(-L.R * T), abs=1e-6)
    gc, gp = L.greeks("C", S, K, T, iv), L.greeks("P", S, K, T, iv)
    assert gc["delta"] + gp["delta"] == pytest.approx(1.0, abs=1e-9)        # |put delta| = 1 - call delta
    assert 0.5 < gc["delta"] < 1 and gc["gamma"] > 0 and gc["theta"] < 0 and gc["vega"] > 0


def test_itm_call_has_higher_delta():
    T = 3 / 365
    d = [L.greeks("C", 24500.0, k, T, 0.13)["delta"] for k in (24300.0, 24500.0, 24700.0)]
    assert d[0] > d[1] > d[2]


@pytest.mark.parametrize("P", [np.nan, 0.0, -1.0, 50.0])
def test_iv_unknown_when_missing_or_below_intrinsic(P):
    # strike 24000 call with spot 24500: intrinsic ~500, so 50 is below it
    assert np.isnan(L.implied_vol("C", P, 24500.0, 24000.0, 2 / 365))


def test_iv_unknown_at_expiry_and_greeks_nan():
    assert np.isnan(L.implied_vol("C", 100.0, 24500.0, 24500.0, 0.0))
    g = L.greeks("C", 24500.0, 24500.0, 0.0, 0.15)
    assert all(np.isnan(v) for v in g.values())


def test_years_to_expiry():
    # expiry day, row 374 (15:29) ends at 15:30: zero left
    assert L.years_to_expiry(EXP, 374, EXP) == 0.0
    # two days before at 09:15 (row 0 ends 09:16): 2 days + 6h14m
    assert L.years_to_expiry(DAY, 0, EXP) * L.YEAR_MIN == pytest.approx(2 * 1440 + 374)
    assert L.years_to_expiry(date(2025, 6, 6), 10, EXP) == 0.0           # after expiry: never negative


def test_next_expiry():
    exps = [date(2025, 6, 5), date(2025, 6, 12)]
    assert L.next_expiry(date(2025, 6, 5), exps) == date(2025, 6, 5)
    assert L.next_expiry(date(2025, 6, 6), exps) == date(2025, 6, 12)
    assert L.next_expiry(date(2025, 6, 13), exps) is None


def test_last_close_lookback_and_gaps():
    s = np.array([1.0, np.nan, np.nan, np.nan, np.nan, np.nan, np.nan, 2.0])
    assert np.isnan(L.last_close(s, 6))                                    # 6 minutes old: NaN
    assert L.last_close(s, 5) == 1.0
    assert L.last_close(s, 7) == 2.0
    assert np.isnan(L.last_close(s, -1))
    assert np.isnan(L.last_close(np.full(5, np.nan), 4))                   # empty / stale data


# ================================================================================================ look-ahead guard
def _day(seed=0):
    rng = np.random.default_rng(seed)
    idx = 24500 + np.cumsum(rng.normal(0, 5, 375))
    opt = np.maximum(idx - 24400, 0) + 60 + rng.normal(0, 1, 375)
    return idx, opt


@pytest.mark.parametrize("er", [1, 30, 200, 374])
def test_greeks_ignore_minutes_from_entry_row_on(er):
    idx, opt = _day()
    g = L.contract_greeks("C", 24400.0, opt, idx, er, DAY, EXP)
    idx2, opt2 = idx.copy(), opt.copy()
    rng = np.random.default_rng(9)
    idx2[er:] = rng.permutation(idx2[er:]) * 1.3
    opt2[er:] = np.nan
    g2 = L.contract_greeks("C", 24400.0, opt2, idx2, er, DAY, EXP)
    for k in g:
        assert (np.isnan(g[k]) and np.isnan(g2[k])) or g[k] == g2[k], k


def test_flow_before_ignores_entry_row_on():
    c = np.array([10, 11, 10, 12, 12, 13, 9, 30], float)
    v = np.array([5, 6, 7, 8, 9, 10, 11, 12], float)
    sv = L.tick_signed_volume(c, v)
    c2, v2 = c.copy(), v.copy()
    c2[5:], v2[5:] = 0.0, 1e6
    assert L.flow_before(sv, 5, 3) == L.flow_before(L.tick_signed_volume(c2, v2), 5, 3)


def test_greeks_unknown_on_missing_price_and_expiry_minute():
    idx, opt = _day()
    o = opt.copy()
    o[:100] = np.nan
    assert np.isnan(L.contract_greeks("C", 24400.0, o, idx, 50, DAY, EXP)["delta"])
    assert np.isnan(L.contract_greeks("C", 24400.0, opt, idx, 375, EXP, EXP)["delta"])     # 15:29 on expiry: T = 0
    assert np.isnan(L.contract_greeks("C", 24400.0, opt, idx, 50, DAY, None)["delta"])     # no expiry known


# ================================================================================================ rules
def test_in_band_edges_and_unknown():
    assert L.in_band(0.45, "DB1") and not L.in_band(0.55, "DB1") and L.in_band(0.55, "DB2")
    assert L.in_band(0.80, "DB3") and not L.in_band(0.8001, "DB3") and not L.in_band(0.64, "DB3")
    assert L.in_band(float("nan"), "DB2")                                  # unknown: kept


def test_choose_strike():
    d = {24300.0: 0.78, 24400.0: 0.66, 24500.0: 0.52, 24600.0: 0.38, 24700.0: float("nan")}
    assert L.choose_strike(24400.0, d, 0.50) == 24500.0
    assert L.choose_strike(24400.0, d, 0.70) == 24400.0
    assert L.choose_strike(24400.0, d, 0.80) == 24300.0
    assert L.choose_strike(24400.0, {24400.0: 0.6, 24500.0: 0.6}, 0.55) == 24400.0      # tie: nearer today's
    assert L.choose_strike(24400.0, {}, 0.6) == 24400.0
    assert L.choose_strike(24400.0, {24500.0: float("nan")}, 0.6) == 24400.0


def test_twin_strikes():
    d = {24300.0: 0.9, 24400.0: 0.66, 24500.0: 0.52, 24600.0: 0.38}
    assert L.twin_strikes(24400.0, d) == [24400.0, 24500.0]
    assert L.twin_strikes(24400.0, {24300.0: 0.95}) == [24400.0]


def test_index_stop():
    assert L.index_stop_points(100.0, 0.5) == pytest.approx(30.0)
    assert np.isnan(L.index_stop_points(100.0, float("nan")))
    assert np.isnan(L.index_stop_points(float("nan"), 0.5))
    c = np.array([100, 101, 99, 97, 96, 99], float)
    assert L.index_stop_row(c, "C", 97.0, 1, 6) == 4                       # close 97 at row 3 -> sell at row 4
    assert L.index_stop_row(c, "C", 90.0, 1, 6) == -1
    assert L.index_stop_row(c, "P", 101.0, 0, 6) == 2                      # put: close >= level
    assert L.index_stop_row(c, "C", 99.5, 3, 6) == 4                       # rows before er are not read
    assert L.index_stop_row(c, "C", float("nan"), 0, 6) == -1
    c2 = c.copy()
    c2[3] = np.nan
    assert L.index_stop_row(c2, "C", 97.0, 1, 6) == 5                      # a gap minute is skipped


def test_tick_rule():
    c = np.array([10, 11, 11, np.nan, 10, 10, 12], float)
    v = np.array([5, 6, 7, 8, 9, 10, 11], float)
    assert L.tick_signed_volume(c, v).tolist() == [0, 6, 7, 0, -9, -10, 11]


def test_flow_exit_row():
    c = np.array([10, 10, 10, 10, 10, 10, 9, 8, 9, 7], float)
    v = np.array([1, 1, 1, 1, 1, 1, 3, 3, 1, 1], float)
    sv = L.tick_signed_volume(c, v)
    # V5 before er=5 is 5; from the fill (5): cum = 0, -3, -6 -> below -5 at row 7 -> sell at 8
    assert L.flow_exit_row(sv, v, 5, 5, 10) == 8
    assert L.flow_exit_row(sv, np.zeros(10), 5, 5, 10) == -1               # no volume before entry: never
    assert L.flow_exit_row(sv, v, -1, 5, 10) == -1


# ================================================================================================ statistics, gates
def _t(day, net):
    return dict(day=day, net=net)


def test_stats_and_empty():
    assert L.stats([])["trades"] == 0
    s = L.stats([_t(date(2025, 1, 2), 100), _t(date(2025, 1, 3), -300), _t(date(2025, 2, 3), 50)])
    assert s["trades"] == 3 and s["win"] == pytest.approx(0.6667, abs=1e-4) and s["net"] == -150
    assert s["worst_month"] == -200 and s["max_dd"] == 300


def test_win_p_and_bh():
    assert L.win_p(0.6, [0.5] * 99) == pytest.approx(1 / 100)
    assert L.win_p(0.5, [0.5] * 99) == 1.0
    q = L.bh([0.01, 0.04, 0.03], m=3)
    assert q.tolist() == pytest.approx([0.03, 0.04, 0.04])
    assert L.bh([0.001], m=2242)[0] == pytest.approx(1.0)                  # capped at 1
    assert L.bh([0.0001], m=2242)[0] == pytest.approx(0.2242)


def test_gates():
    base = {"wf": dict(trades=1000, win=0.46, net=100000), "final": dict(trades=500, win=0.46, net=50000)}
    good = {"wf": dict(trades=600, win=0.52, net=110000), "final": dict(trades=300, win=0.515, net=60000)}
    assert L.gates(good, base, 55000, 0.0001, 0.05)["passed"]
    assert not L.gates(good, base, 70000, 0.0001, 0.05)["passed"]           # below R151's picker
    assert not L.gates(good, base, None, 0.0001, 0.2)["passed"]             # BH
    low = {"wf": good["wf"], "final": dict(trades=300, win=0.50, net=60000)}
    assert not L.gates(low, base, None, 0.0001, 0.05)["passed"]             # +4 points only
    few = {"wf": good["wf"], "final": dict(trades=39, win=0.6, net=60000)}
    assert "< 40 final trades" in L.gates(few, base, None, 0.0001, 0.05)["fails"]
    none = {"wf": dict(trades=0, win=None, net=0), "final": good["final"]}
    assert not L.gates(none, base, None, 0.0001, 0.05)["passed"]
