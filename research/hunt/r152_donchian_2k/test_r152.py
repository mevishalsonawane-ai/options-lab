"""R152 tests: the nearer-stop rule, rupee levels for N lots, the engine against R106's (no rupee level = B0, rupee stop
nearer = R106's 1:1), look-ahead guards, twin eligibility, the exit mix, the BH count and the family. Synthetic data only:
no stores, no lake."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r152cfg as K  # noqa: E402

Y = pytest.importorskip("r152engine")
import r106engine as X  # noqa: E402
import r152study as S  # noqa: E402
import engine as E  # noqa: E402

STEPS = np.array([[0.30, np.nan], [0.60, 0.30], [1.00, 0.60], [0.25, 0.05]])
XL = E.Exits(stop=0.15, target=-1.0, steps=tuple(map(tuple, STEPS)), tmax=30, sq_row=360)


def _charges(entry, exit_, qty):
    """A stand-in for dated charges: Rs 20 + Rs 20 brokerage (flat per order), 0.1% STT on the sale, 0.05% each side."""
    return 40.0 + 0.001 * exit_ * qty + 0.0005 * (entry + exit_) * qty + 0.18 * 40.0


def _ch(qty):
    return lambda a, b: _charges(a, b, qty)  # noqa: E731


def _contract(rng, n=375, start=100.0):
    c = start * np.exp(np.cumsum(rng.normal(0, 0.02, n)))
    o = np.r_[start, c[:-1]]
    h = np.maximum(o, c) * (1 + np.abs(rng.normal(0, 0.01, n)))
    l = np.minimum(o, c) * (1 - np.abs(rng.normal(0, 0.01, n)))
    v = rng.integers(0, 5000, n).astype(float)
    C = np.vstack([o, h, l, c, v]).astype(np.float64)
    C[:, rng.random(n) < 0.03] = np.nan
    return C


# ------------------------------------------------------------------------------------------------ levels
def test_no_rupee_level_keeps_the_arms_own_exits():
    assert Y.levels(100.0, 100.2, 65.0, XL, None, None, _ch(65), 2.0) == (-1.0, -1.0, 0.15, "own")


def test_rupee_stop_is_used_only_when_nearer_than_minus_15pct():
    # 1 lot of 65 at Rs 100: -15% is Rs 85 (about -Rs 1,000 + charges); the Rs 2,000 stop is lower -> the arm's stop acts
    t, s, f, k = Y.levels(100.0, 100.2, 65.0, XL, None, 2000.0, _ch(65), 2.0)
    assert (s, f, k) == (-1.0, 0.15, "own") and t == -1.0
    # 3 lots (195) at Rs 100: Rs 2,000 is about -Rs 10 a unit, nearer than -Rs 15 -> the rupee stop acts
    t, s, f, k = Y.levels(100.0, 100.2, 195.0, XL, None, 2000.0, _ch(195), 2.0)
    assert k == "rupee" and f == -1.0 and 85.0 < s < 100.0
    assert s == X.stop_price(100.2, 195.0, 2000.0, _ch(195), 2.0, E.SLIP[2], E.SLIP[3])


def test_target_is_for_the_whole_position_so_fewer_points_per_lot_with_more_lots():
    t1 = Y.levels(100.0, 100.2, 65.0, XL, 2000.0, None, _ch(65), 2.0)[0]
    t3 = Y.levels(100.0, 100.2, 195.0, XL, 2000.0, None, _ch(195), 2.0)[0]
    assert t1 > t3 > 100.2
    for q, t in ((65.0, t1), (195.0, t3)):
        assert (t - 100.2) * q - _charges(100.2, t, q) >= 2000.0 - 1e-6          # nets at least +Rs 2,000 for the position
        assert (t - 0.05 - 100.2) * q - _charges(100.2, t - 0.05, q) < 2000.0


def test_tiny_position_has_no_rupee_stop():
    # 1 lot of 65 at Rs 20 is worth Rs 1,300 < Rs 2,000: only the -15% stop
    assert Y.levels(20.0, 20.1, 65.0, XL, None, 2000.0, _ch(65), 2.0)[3] == "own"


# ------------------------------------------------------------------------------------------------ engine
def _sim(C, e, qty, stop_f, stop_abs, tgt_abs, be=0.0):
    return X.simulate(C, e, 355, -1, stop_f, -1.0, -1.0, 0.0, 0.0, STEPS, 30, -1.0, -1.0, 0.05, qty, be, stop_abs, tgt_abs)


def test_nearer_stop_never_exits_later_or_lower_than_b0_on_a_stop():
    """With the rupee stop the stop level is >= the arm's: every B0 stop exit happens no later with the rule."""
    rng = np.random.default_rng(5)
    for _ in range(300):
        C = _contract(rng)
        e = int(rng.integers(5, 300))
        qty = float(rng.choice([65.0, 130.0, 195.0]))
        raw = C[0, e] if np.isfinite(C[0, e]) else np.nan
        if not np.isfinite(raw):
            continue
        real = round(raw * 1.0005, 2) + 0.1
        _, s_abs, s_f, k = Y.levels(raw, real, qty, XL, None, 2000.0, _ch(qty), 2.0)
        b0 = _sim(C, e, qty, 0.15, -1.0, -1.0)
        r = _sim(C, e, qty, s_f, s_abs, -1.0)
        if k == "own":
            np.testing.assert_equal(np.array(b0, float), np.array(r, float))
        elif b0[6] == 1:
            assert r[5] <= b0[5]


def test_look_ahead_guard_nothing_after_the_exit_minute_matters():
    rng = np.random.default_rng(11)
    for _ in range(200):
        C = _contract(rng)
        qty = 195.0
        raw = C[0, 10]
        if not np.isfinite(raw):
            continue
        real = round(raw * 1.0005, 2) + 0.1
        t_abs, s_abs, s_f, _ = Y.levels(raw, real, qty, XL, 2000.0, 2000.0, _ch(qty), 2.0)
        a = _sim(C, 10, qty, s_f, s_abs, t_abs)
        if a[5] < 0:
            continue
        C2 = C.copy()
        C2[:4, a[5] + 1:] *= rng.uniform(0.2, 5.0)
        b = _sim(C2, 10, qty, s_f, s_abs, t_abs)
        np.testing.assert_equal(np.array(a, float), np.array(b, float))


def test_levels_read_only_the_entry_fill():
    """Levels depend on (raw fill, real fill, qty, spread ticks) only: the same inputs give the same levels whatever the
    contract does later."""
    a = Y.levels(150.0, 150.3, 130.0, XL, 2000.0, 2000.0, _ch(130), 1.0)
    b = Y.levels(150.0, 150.3, 130.0, XL, 2000.0, 2000.0, _ch(130), 1.0)
    assert a == b


# ------------------------------------------------------------------------------------------------ study (pure)
D0 = date(2026, 4, 6)


def test_exit_mix_splits_the_stops():
    tr = [dict(why="target"), dict(why="stop2k"), dict(why="stop"), dict(why="time"), dict(why="lock"), dict(why="stop2k")]
    assert S.exit_mix(tr) == dict(target=1, stop2k=2, stop=1, other=2)
    assert S.exit_mix([]) == dict(target=0, stop2k=0, stop=0, other=0)


def test_rupee_stop_share():
    assert S.rupee_stop_share([dict(stop_kind="rupee"), dict(stop_kind="own"), dict(stop_kind="rupee"), {}]) == 0.6667
    assert S.rupee_stop_share([{}]) is None


def test_changed_needs_an_earlier_exit_and_room_for_a_twin():
    assert S.changed(dict(er=10, xr=20), dict(xr=40))
    assert not S.changed(dict(er=10, xr=40), dict(xr=40))            # same exit: not changed
    assert not S.changed(dict(er=10, xr=10), dict(xr=11))            # no minute strictly between
    assert not S.changed(dict(er=10, xr=20), None)


def test_family_is_small_and_matches_prereg():
    assert K.RULE_IDS == ("T2000", "T2000S", "S2000") and K.LOTS == (1, 2, 3)
    assert len(K.TESTS) == 18
    assert K.N_PRIOR == 848 and S.m_total() == 866
    assert K.RULES["T2000"] == dict(target=2000.0, stop=None)
    assert K.RULES["T2000S"] == dict(target=2000.0, stop=2000.0)
    assert K.RULES["S2000"] == dict(target=None, stop=2000.0)


def test_periods_do_not_overlap():
    assert K.period_of(K.DESIGN_END) == "design" and K.period_of(K.FINAL_START) == "final"
    assert K.period_of(K.FINAL_END) == "final" and K.period_of(K.POST_START) == "post"
    assert K.period_of(date(2024, 11, 19)) is None


# ------------------------------------------------------------------------------------------------ price() on a synthetic day
def _day(path_low=None, path_high=None):
    """One NIFTY day (lot 65 on 6 Apr 2026) with a single 1-ITM call flat at Rs 100 and enough volume for 1-tick fills."""
    import r51data
    C = np.full((5, 375), np.nan)
    C[:, :] = np.array([100.0, 100.5, 99.5, 100.0, 1e6])[:, None]
    if path_low is not None:
        C[2, path_low[0]] = path_low[1]
    if path_high is not None:
        C[1, path_high[0]] = path_high[1]
    D = r51data.Day(D0, np.full((4, 375), 22000.0), atm=np.full(375, 22000.0), con={("C", 21950.0): C})
    E._STACKS.clear()
    return D


def test_price_without_rules_is_r106_price_at_one_lot_and_scales_with_lots():
    D = _day()
    c = [dict(side="C", strike=21950.0, er=20)]
    a = Y.price("NIFTY", D, c, XL, None, None, 1)[0]
    b = X.price("NIFTY", D, c, XL, None, "own")[0]
    assert a["net"] == pytest.approx(b["net"]) and a["xr"] == b["xr"] and a["qty"] == 65.0
    a3 = Y.price("NIFTY", D, c, XL, None, None, 3)[0]
    assert a3["qty"] == 195.0 and a3["lots"] == 3 and a3["xr"] == a["xr"]


def test_price_rupee_stop_exit_is_labelled_and_loses_about_2000():
    D = _day(path_low=(40, 88.0))                 # -12% at 10:55: inside -15%, through the 3-lot Rs 2,000 stop
    c = [dict(side="C", strike=21950.0, er=20)]
    b0 = Y.price("NIFTY", D, c, XL, None, None, 3)[0]
    r = Y.price("NIFTY", D, c, XL, None, 2000.0, 3)[0]
    assert b0["why"] != "stop" and r["why"] == "stop2k" and r["stop_kind"] == "rupee" and r["xr"] == 40
    assert -2000.0 - 200 <= r["net"] <= -2000.0 + 0.05 * 195 + 1
    r1 = Y.price("NIFTY", D, c, XL, None, 2000.0, 1)[0]   # 1 lot: -15% is nearer, nothing changes
    assert r1["stop_kind"] == "own" and r1["net"] == pytest.approx(Y.price("NIFTY", D, c, XL, None, None, 1)[0]["net"])


def test_price_rupee_target_nets_at_least_2000_for_the_position():
    D = _day(path_high=(35, 140.0))
    c = [dict(side="C", strike=21950.0, er=20)]
    for lots in (1, 2, 3):
        r = Y.price("NIFTY", D, c, XL, 2000.0, None, lots)[0]
        assert r["why"] == "target" and r["xr"] == 35 and r["net"] >= 2000.0 - 1e-6
