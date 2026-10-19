"""R86 tests: synthetic data only (no lake, no network). Signals with a LOOK-AHEAD GUARD (rows after the decision are
deleted or perturbed and the decision must not change), COT availability, roll handling, the trade simulators, costs,
the stop-run detector, VWAP, BH and the HAC statistic.

    python -m pytest research/hunt/r86/test_r86.py -q
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r86 import r86lib as L

RNG = np.random.default_rng(0)


# ------------------------------------------------------------------ rolling helpers


def test_pct_rank_needs_history_and_counts_ties():
    x = pd.Series([1.0, 2.0, 3.0, np.nan, 2.0])
    p = L.pct_rank(x, window=10, min_obs=3)
    assert np.isnan(p[0]) and np.isnan(p[1]) and np.isnan(p[3])
    assert p[2] == pytest.approx((2 + 0.5) / 3)
    assert p[4] == pytest.approx((1 + 0.5 * 2) / 4)       # values 1,2,3,2: one below, two ties


def test_pct_rank_window_drops_old_values():
    x = pd.Series([100.0] * 5 + [1.0, 2.0, 3.0])
    assert L.pct_rank(x, window=3, min_obs=3).iloc[-1] == pytest.approx(2.5 / 3)


def test_roll_z_flat_series_has_no_signal():
    z = L.roll_z(pd.Series([5.0] * 30), 20, 10)
    assert z.isna().all()


def test_wilder_atr_constant_bars():
    h, l, c = np.full(30, 11.0), np.full(30, 9.0), np.full(30, 10.0)
    a = L.wilder_atr(h, l, c, 14)
    assert np.isnan(a[12]) and a[13] == pytest.approx(2.0) and a[-1] == pytest.approx(2.0)
    assert np.isnan(L.wilder_atr(h[:5], l[:5], c[:5], 14)).all()      # too short: no ATR at all


# ------------------------------------------------------------------ look-ahead guard


def _participants(n=320):
    idx = pd.bdate_range("2015-01-01", periods=n)
    cols = ["fii_fut_idx_long", "fii_fut_idx_short", "fii_idx_call_long", "fii_idx_call_short", "fii_idx_put_long",
            "fii_idx_put_short", "cl_idx_call_long", "cl_idx_call_short", "cl_idx_put_long", "cl_idx_put_short"]
    return pd.DataFrame(RNG.integers(1000, 100000, size=(n, len(cols))).astype(float), index=idx, columns=cols)


@pytest.mark.parametrize("cut", [260, 290, 318])
def test_participant_features_lookahead_guard(cut):
    p = _participants()
    full = L.participant_features(p)
    trunc = L.participant_features(p.iloc[:cut + 1])
    pert = p.copy()
    pert.iloc[cut + 1:] = RNG.integers(1, 10 ** 6, size=pert.iloc[cut + 1:].shape).astype(float)
    pf = L.participant_features(pert)
    for col in ("P1", "P2", "P3", "P4", "P5"):
        a = full[col].iloc[:cut + 1].to_numpy()
        np.testing.assert_allclose(a, trunc[col].to_numpy(), equal_nan=True)
        np.testing.assert_allclose(a, pf[col].iloc[:cut + 1].to_numpy(), equal_nan=True)


def test_participant_features_zero_positions_give_nan():
    p = _participants(5)
    p.iloc[2, :2] = 0.0
    f = L.participant_features(p)
    assert np.isnan(f.L.iloc[2])


@pytest.mark.parametrize("cut", [120, 180])
def test_cot_features_lookahead_guard(cut):
    n = 200
    c = pd.DataFrame({"mm_long": RNG.uniform(1e4, 1e5, n), "mm_short": RNG.uniform(1e4, 1e5, n),
                      "oi": RNG.uniform(3e5, 5e5, n)})
    full = L.cot_features(c)
    pert = c.copy()
    pert.iloc[cut + 1:] = 1.0
    for col in ("C1", "C2"):
        np.testing.assert_allclose(full[col].iloc[:cut + 1], L.cot_features(c.iloc[:cut + 1])[col], equal_nan=True)
        np.testing.assert_allclose(full[col].iloc[:cut + 1], L.cot_features(pert)[col].iloc[:cut + 1], equal_nan=True)
    assert full.C1.iloc[:103].isna().all()         # fewer than 104 reports: no COT index


# ------------------------------------------------------------------ COT timing


def test_cot_entry_is_at_least_a_week_after_the_report():
    sess = [date(2024, 1, d) for d in (8, 9, 10, 15, 16, 17)]
    # report Tuesday 2 Jan -> released Fri 5 Jan (Sat ~01:00 IST); +7 = Tue 9 Jan
    assert sess[L.cot_entry_index(date(2024, 1, 2), sess)] == date(2024, 1, 9)
    # a holiday on 9 Jan (missing session) -> the next one
    sess2 = [d for d in sess if d != date(2024, 1, 9)]
    assert sess2[L.cot_entry_index(date(2024, 1, 2), sess2)] == date(2024, 1, 10)
    assert L.cot_entry_index(date(2024, 1, 16), sess) is None      # after the data ends


def test_cot_shutdown_windows_dropped():
    assert not L.cot_usable(date(2013, 10, 8))
    assert not L.cot_usable(date(2019, 1, 15))
    assert not L.cot_usable(date(2025, 10, 14))
    assert L.cot_usable(date(2026, 3, 3)) and L.cot_usable(date(2019, 3, 12))


# ------------------------------------------------------------------ MCX roll


def test_detect_rolls_and_back_adjust_remove_the_gap():
    oi = np.array([100, 90, 80, 300, 310, 300] + [300] * 20 + [80, 400], float)
    r = L.detect_rolls(oi)
    assert list(r) == [3, 27]
    c = np.array([100.0, 101, 102, 110, 111, 112] + [112.0] * 20 + [112, 120])
    o = c.copy()
    o[3] = 109.0           # roll-day open (new contract)
    o[27] = 118.0
    h, l = c + 1, c - 1
    ao, ah, al, ac = L.back_adjust(o, h, l, c, r)
    # the roll day's adjusted return equals its own open -> close
    assert ac[3] / ac[2] - 1 == pytest.approx(c[3] / o[3] - 1)
    assert ac[27] / ac[26] - 1 == pytest.approx(c[27] / o[27] - 1)
    np.testing.assert_allclose(ac[27:], c[27:])          # after the last roll: unchanged
    assert ac[1] / ac[0] == pytest.approx(c[1] / c[0])  # ordinary returns unchanged


def test_detect_rolls_needs_a_gap_between_rolls():
    oi = np.array([100, 200, 400, 800], float)
    assert list(L.detect_rolls(oi)) == [1]


# ------------------------------------------------------------------ costs


def test_lot_and_stt_dated():
    sch = [("2000-01-01", 75), ("2021-07-23", 50), ("2024-11-28", 75)]
    assert L.lot_on(sch, "2021-07-22") == 75 and L.lot_on(sch, "2021-07-23") == 50 and L.lot_on(sch, "2025-01-01") == 75
    assert L.nse_fut_stt("2023-03-31") == 0.0001 and L.nse_fut_stt("2024-09-30") == 0.000125
    assert L.nse_fut_stt("2024-10-01") == 0.0002


def test_nse_cost_carry_sign_and_stop_slip():
    c_long = L.nse_fut_cost(1, 20000, 20000, 75, "2025-01-02", 7, False)
    c_short = L.nse_fut_cost(-1, 20000, 20000, 75, "2025-01-02", 7, False)
    carry = 0.055 * 20000 * 75 * 7 / 365
    assert c_long - c_short == pytest.approx(2 * carry)
    assert L.nse_fut_cost(1, 20000, 20000, 75, "2025-01-02", 0, True) > L.nse_fut_cost(1, 20000, 20000, 75,
                                                                                         "2025-01-02", 0, False)


def test_mcx_cost_matches_mcxcosts_doc():
    # McxCosts.kt doc (as R82/R83): a 1-lot CRUDEOIL futures round trip at Rs 8,552 (Rs 8,55,200 a side) is Rs 194
    ch = L.mcx_leg(True, 855200) + L.mcx_leg(False, 855200)
    assert round(ch) == 194


# ------------------------------------------------------------------ simulators


def test_sim_daily_stop_gap_and_time_exit():
    o = np.array([100, 100, 95, 100.0])
    h = np.array([101, 101, 96, 101.0])
    l = np.array([99, 99, 94, 99.0])
    c = np.array([100, 100, 95, 100.0])
    assert L.sim_daily(o, h, l, c, 0, 2, 1, 5.0) == (1, 100.0, False)           # time exit at close
    j, px, st = L.sim_daily(o, h, l, c, 1, 3, 1, 3.0)                             # gap through the stop on day 2
    assert (j, px, st) == (2, 95.0, True)
    j, px, st = L.sim_daily(o, h, l, c, 0, 2, -1, 0.5)                            # short: high 101 >= stop 100.5
    assert (j, px, st) == (0, 100.5, True)
    assert L.sim_daily(o, h, l, c, 3, 2, 1, 1.0) is None                          # runs past the data
    assert L.sim_daily(o, h, l, c, 0, 2, 1, np.nan) is None                       # no ATR -> no trade


def test_sim_minutes_stop_first_and_target():
    o = np.array([100, 100, 100, 100.0])
    h = np.array([100.5, 103, 101, 100.0])
    l = np.array([99.5, 98, 99, 100.0])
    c = np.array([100, 100, 100, 101.0])
    assert L.sim_minutes(o, h, l, c, 0, 3, 1, 99.0, 102.0)[2] == "stop"         # both in minute 1: stop first
    assert L.sim_minutes(o, h, l, c, 0, 3, 1, 97.0, 102.0)[:3] == (1, 102.0, "target")
    assert L.sim_minutes(o, h, l, c, 0, 3, 1, 90.0, 110.0) == (3, 101.0, "time")


def test_vec_sim_matches_scalar_sim():
    from research.hunt.r86.r86study import MinuteBook, vec_sim
    n = 400
    px = 100 + np.cumsum(RNG.normal(0, 0.1, n))
    m = pd.DataFrame({"day": pd.Timestamp("2024-01-02"), "mod": 555 + np.arange(n), "open": px,
                      "high": px + RNG.uniform(0, 0.2, n), "low": px - RNG.uniform(0, 0.2, n),
                      "close": px + RNG.normal(0, 0.05, n)})
    B = MinuteBook(m)
    e = RNG.integers(0, 300, 50)
    last = e + 59
    side = RNG.choice([-1, 1], 50)
    stop = B.o[e] - side * 0.3
    tgt = B.o[e] + side * 0.6
    xi, px_, kind = vec_sim(B, e, last, side, stop, tgt)
    for k in range(50):
        j, p, kd = L.sim_minutes(B.o, B.h, B.l, B.c, int(e[k]), int(last[k]), int(side[k]), stop[k], tgt[k])
        assert (xi[k], kd) == (j, kd) and kind[k] == kd and px_[k] == pytest.approx(p)


def test_one_at_a_time():
    from research.hunt.r86.r86study import one_at_a_time
    assert list(one_at_a_time(np.array([1, 5, 12, 13]), np.array([10, 11, 20, 14]))) == [0, 2]


# ------------------------------------------------------------------ stop-run detector


def _day(highs, lows, closes):
    n = len(closes)
    mods = 570 + np.arange(n)
    return mods, np.array(closes, float), np.array(highs, float), np.array(lows, float), np.array(closes, float)


def test_stop_run_failed_poke_is_an_event():
    # level 100: approach from below, poke to 100.05 (>= 100.03), close back below 3 minutes later
    mods, o, h, l, c = _day([99.9, 100.05, 100.02, 99.98, 99.9], [99.8, 99.9, 99.95, 99.9, 99.8],
                            [99.9, 100.01, 100.01, 99.95, 99.85])
    ev = L.stop_run_events(mods, o, h, l, c, [100.0])
    assert len(ev) == 1 and ev[0]["side"] == -1 and ev[0]["k"] == 3 and ev[0]["extreme"] == pytest.approx(100.05)


def test_stop_run_held_poke_is_not_an_event():
    mods, o, h, l, c = _day([99.9] + [100.2] * 12, [99.8] + [100.0] * 12, [99.9] + [100.1] * 12)
    assert L.stop_run_events(mods, o, h, l, c, [100.0]) == []


def test_stop_run_lower_level_and_time_window():
    mods, o, h, l, c = _day([100.2, 100.1, 100.1], [100.1, 99.9, 100.0], [100.15, 99.99, 100.05])
    ev = L.stop_run_events(mods, o, h, l, c, [100.0])
    assert len(ev) == 1 and ev[0]["side"] == 1
    late = mods + (871 - 570)                         # poke at 14:31 (871): ignored
    assert L.stop_run_events(late, o, h, l, c, [100.0]) == []


def test_stop_run_lookahead_guard():
    mods, o, h, l, c = _day([99.9, 100.05, 100.02, 99.98, 99.9, 99.9], [99.8, 99.9, 99.95, 99.9, 99.8, 99.8],
                            [99.9, 100.01, 100.01, 99.95, 99.85, 99.9])
    ev = L.stop_run_events(mods, o, h, l, c, [100.0])
    k = ev[0]["k"]
    h2, l2, c2 = h.copy(), l.copy(), c.copy()
    h2[k + 1:] += 5
    l2[k + 1:] -= 5
    c2[k + 1:] = 150
    assert L.stop_run_events(mods, o, h2, l2, c2, [100.0]) == ev
    assert L.stop_run_events(mods[:k + 1], o[:k + 1], h[:k + 1], l[:k + 1], c[:k + 1], [100.0]) == ev


def test_stop_run_empty_and_nan_level():
    assert L.stop_run_events(np.array([570]), np.array([1.0]), np.array([1.0]), np.array([1.0]), np.array([1.0]),
                             [np.nan, 1.0]) == []


# ------------------------------------------------------------------ VWAP


def test_vwap_skips_zero_volume_and_is_causal():
    typ = np.array([10.0, 20.0, 30.0, 40.0])
    w = np.array([0.0, 1.0, 0.0, 3.0])
    v = L.vwap(typ, w)
    assert np.isnan(v[0]) and v[1] == 20.0 and v[2] == 20.0 and v[3] == pytest.approx((20 + 120) / 4)
    np.testing.assert_allclose(L.vwap(typ[:3], w[:3]), v[:3], equal_nan=True)


# ------------------------------------------------------------------ statistics


def test_bh_known_values():
    q = L.bh([0.01, 0.04, 0.03, 0.20, np.nan])
    np.testing.assert_allclose(q[:4], [0.04, 0.0533333, 0.0533333, 0.2], rtol=1e-5)
    assert np.isnan(q[4])


def test_hac_and_p_and_twins():
    mu, t = L.hac_mean_t(np.ones(50) + RNG.normal(0, 0.01, 50), 2)
    assert mu == pytest.approx(1.0, abs=0.01) and t > 50
    assert L.one_sided_p(0.0) == pytest.approx(0.5)
    assert L.twin_p(10.0, np.array([1.0, 11.0, 12.0])) == pytest.approx(3 / 4)
    assert np.isnan(L.hac_mean_t(np.array([1.0]), 1)[0])


def test_without_top_and_walk_sign():
    assert L.without_top([1, 2, 3, 4, 5, 6, -10], 5) == -9
    assert L.without_top([1, 2], 5) == 0.0
    x = np.arange(50.0)
    assert L.walk_sign(x, x, 25.0) == 1 and L.walk_sign(x, -x, 25.0) == -1
    assert L.walk_sign(x[:5], x[:5], 0.0) == 1       # too few rows: default +1
