"""R77 tests: synthetic bars only (no lake, no secrets). The absorption-proxy detector on hand-made sessions, the
look-ahead guard (the event and its confirmation use only bars closed before the entry), the index exits, the
percentiles, and the study's pure pieces (gates, twin p, draws, bootstrap, moves).

    python -m pytest research/hunt/r77/test_r77.py -q
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r77rules as P  # noqa: E402
import r77study as S  # noqa: E402

NR = 375


def flat_session(px=100.0, n=NR, wiggle=0.2, seed=0):
    """A quiet session: bars around `px` with small random wiggles (no new 20-bar extremes most of the time)."""
    rng = np.random.default_rng(seed)
    c = px + rng.uniform(-wiggle, wiggle, n)
    o = np.r_[px, c[:-1]]
    h = np.maximum(o, c) + 0.5
    l = np.minimum(o, c) - 0.5
    return o, h, l, c


def with_down_absorption(i=100, conf="inside"):
    """A session with a downside sweep at bar i (low well under the previous 20 lows, tiny range) and a confirmation."""
    o, h, l, c = (x.copy() for x in flat_session())
    o[i], h[i], l[i], c[i] = 99.0, 99.1, 98.9, 99.0          # range 0.2, new low (others >= ~99.3)
    if conf == "inside":                                    # back above the swept low, low holds
        o[i + 1], h[i + 1], l[i + 1], c[i + 1] = 99.0, 99.9, 98.95, 99.8
    elif conf == "break":                                   # closes over the absorption bar's high
        o[i + 1], h[i + 1], l[i + 1], c[i + 1] = 99.0, 99.2, 98.95, 99.15
    elif conf == "lower":                                   # a lower low: no confirmation
        o[i + 1], h[i + 1], l[i + 1], c[i + 1] = 99.0, 99.9, 98.5, 99.8
    elif conf == "weak":                                    # low holds but closes under both levels
        o[i + 1], h[i + 1], l[i + 1], c[i + 1] = 99.0, 99.05, 98.95, 99.0
    return o, h, l, c


def hot(i, v=0.99, r=0.1):
    vp, rp = np.full(NR, 0.5), np.full(NR, 0.5)
    vp[i], rp[i] = v, r
    return vp, rp


# ------------------------------------------------------------------------------------------------ percentiles
def test_pct_prev_uses_only_earlier_sessions_and_counts_strictly_below():
    M = np.arange(25 * 3, dtype=float).reshape(25, 3)
    out = P.pct_prev(M)
    assert np.isnan(out[:15]).all()                          # fewer than 15 earlier sessions
    assert out[20, 0] == 1.0                                 # rising series: all 20 earlier values below
    M2 = M.copy()
    M2[21:] = -1e9                                           # change the future: the past rows do not move
    np.testing.assert_array_equal(P.pct_prev(M2)[:21], out[:21])
    M3 = np.ones((21, 2))
    assert P.pct_prev(M3)[20, 0] == 0.0                      # ties are not "below"


def test_pct_prev_needs_15_known_and_nan_today_is_nan():
    M = np.ones((21, 2))
    M[:6, 0] = np.nan                                        # only 14 of the previous 20 known in row 0
    M[20, 1] = np.nan
    out = P.pct_prev(M)
    assert np.isnan(out[20, 0]) and np.isnan(out[20, 1])


def test_day_volume_in_lots_nan_when_nothing_printed():
    a = np.full((5, NR), np.nan)
    b = np.full((5, NR), np.nan)
    a[4, 0], b[4, 0], a[4, 1] = 60.0, 30.0, 30.0
    v = P.day_volume({("C", 1.0): a, ("P", 1.0): b}, lot=30)
    assert v[0] == 3.0 and v[1] == 1.0 and np.isnan(v[2])
    assert np.isnan(P.day_volume({}, 30)).all()


# ------------------------------------------------------------------------------------------------ detector
@pytest.mark.parametrize("conf", ["inside", "break"])
def test_downside_absorption_is_found_and_buys_ce(conf):
    o, h, l, c = with_down_absorption(100, conf)
    vp, rp = hot(100)
    ev = P.absorption_events(o, h, l, c, vp, rp, 0.95, 0.40)
    assert len(ev) == 1
    e = ev[0]
    assert e["i"] == 100 and e["er"] == 102 and e["side"] == "C" and e["sgn"] == 1
    assert e["stop"] == 98.9 and e["ref"] == c[101]
    assert e["target"] == pytest.approx(h[80:100].max())


@pytest.mark.parametrize("conf", ["lower", "weak"])
def test_no_confirmation_no_event(conf):
    o, h, l, c = with_down_absorption(100, conf)
    vp, rp = hot(100)
    assert P.absorption_events(o, h, l, c, vp, rp, 0.95, 0.40) == []


def test_volume_and_range_thresholds():
    o, h, l, c = with_down_absorption(100)
    assert P.absorption_events(o, h, l, c, *hot(100, v=0.94), 0.95, 0.40) == []
    assert len(P.absorption_events(o, h, l, c, *hot(100, v=0.94), 0.90, 0.40)) == 1
    assert P.absorption_events(o, h, l, c, *hot(100, r=0.30), 0.95, 0.25) == []
    assert P.absorption_events(o, h, l, c, *hot(100, v=np.nan), 0.90, 0.40) == []


def test_upside_mirror_buys_pe():
    o, h, l, c = with_down_absorption(150)
    # mirror around 100: highs become lows
    o2, h2, l2, c2 = 200 - o, 200 - l, 200 - h, 200 - c
    ev = P.absorption_events(o2, h2, l2, c2, *hot(150), 0.95, 0.40)
    assert len(ev) == 1 and ev[0]["side"] == "P" and ev[0]["sgn"] == -1 and ev[0]["stop"] == pytest.approx(101.1)


def test_entry_window_first_and_last_rows():
    for i, ok in ((19, False), (20, True), (P.LAST_ER - 2, True), (P.LAST_ER - 1, False)):
        o, h, l, c = with_down_absorption(i)
        ev = P.absorption_events(o, h, l, c, *hot(i), 0.95, 0.40)
        assert (len(ev) == 1) is ok, i


def test_nan_in_window_means_no_event():
    o, h, l, c = with_down_absorption(100)
    l[85] = np.nan
    assert P.absorption_events(o, h, l, c, *hot(100), 0.95, 0.40) == []
    o, h, l, c = with_down_absorption(100)
    c[101] = np.nan                                           # confirmation bar missing
    assert P.absorption_events(o, h, l, c, *hot(100), 0.95, 0.40) == []


def test_target_nan_when_already_past():
    o, h, l, c = with_down_absorption(100)
    c[101] = h[80:100].max() + 1.0
    h[101] = c[101] + 0.1
    ev = P.absorption_events(o, h, l, c, *hot(100), 0.95, 0.40)
    assert len(ev) == 1 and np.isnan(ev[0]["target"])


def test_look_ahead_guard_bars_from_entry_on_change_nothing():
    """The event and its confirmation read only bars <= i + 1 (closed before the entry at i + 2) and the volume / range
    percentiles of bar i: scrambling every bar from the entry row on, and the percentiles after bar i, changes nothing."""
    rng = np.random.default_rng(7)
    for i in (40, 100, 250):
        o, h, l, c = with_down_absorption(i)
        vp, rp = hot(i)
        base = P.absorption_events(o, h, l, c, vp, rp, 0.95, 0.40)
        base = [e for e in base if e["i"] == i]
        assert len(base) == 1
        for _ in range(20):
            o2, h2, l2, c2, vp2, rp2 = (x.copy() for x in (o, h, l, c, vp, rp))
            k = i + 2
            c2[k:] = 100 + rng.normal(0, 5, NR - k)
            o2[k:] = 100 + rng.normal(0, 5, NR - k)
            h2[k:] = np.maximum(o2[k:], c2[k:]) + rng.uniform(0, 3, NR - k)
            l2[k:] = np.minimum(o2[k:], c2[k:]) - rng.uniform(0, 3, NR - k)
            vp2[i + 1:] = rng.uniform(0, 1, NR - i - 1)
            rp2[i + 1:] = rng.uniform(0, 1, NR - i - 1)
            got = [e for e in P.absorption_events(o2, h2, l2, c2, vp2, rp2, 0.95, 0.40) if e["i"] == i]
            assert got == base


def test_look_ahead_guard_every_event_on_random_sessions():
    """On random sessions, each event found with the full day is found again when only bars <= i + 1 are kept."""
    rng = np.random.default_rng(3)
    seen = 0
    for s in range(30):
        c = 100 + np.cumsum(rng.normal(0, 0.3, NR))
        o = np.r_[100, c[:-1]]
        h = np.maximum(o, c) + rng.uniform(0, 0.4, NR)
        l = np.minimum(o, c) - rng.uniform(0, 0.4, NR)
        vp, rp = rng.uniform(0, 1, NR), rng.uniform(0, 1, NR)
        for e in P.absorption_events(o, h, l, c, vp, rp, 0.5, 0.9):
            i = e["i"]
            cut = [np.r_[x[:i + 2], np.full(NR - i - 2, np.nan)] for x in (o, h, l, c)]
            vcut = np.r_[vp[:i + 1], np.full(NR - i - 1, np.nan)]
            rcut = np.r_[rp[:i + 1], np.full(NR - i - 1, np.nan)]
            again = [x for x in P.absorption_events(*cut, vcut, rcut, 0.5, 0.9) if x["i"] == i]
            assert again == [e]
            seen += 1
    assert seen > 10


# ------------------------------------------------------------------------------------------------ exits and moves
def test_forced_row_stop_target_and_none():
    h = np.full(NR, 101.0)
    l = np.full(NR, 99.0)
    assert P.forced_row(h, l, 50, 1, 98.0, 102.0) == -1
    l[60] = 97.9
    assert P.forced_row(h, l, 50, 1, 98.0, 102.0) == 61                # stop below the absorption low
    h[55] = 102.0
    assert P.forced_row(h, l, 50, 1, 98.0, 102.0) == 56                # target first
    assert P.forced_row(h, l, 50, 1, 98.0, float("nan")) == 61         # no target
    l2 = np.full(NR, 99.0)
    l2[NR - 1] = 90.0
    assert P.forced_row(h, l2, 50, 1, 98.0, float("nan")) == -1         # past the grid
    hp = np.full(NR, 101.0)
    hp[70] = 103.0                                                      # PE: stop above the absorption high
    assert P.forced_row(hp, l, 50, -1, 102.5, 90.0) == 71
    l3 = np.full(NR, 99.0)
    l3[40] = 50.0                                                       # before the entry: ignored
    assert P.forced_row(h, l3, 50, 1, 98.0, float("nan")) == -1
    l3[45:] = np.nan
    assert P.forced_row(h, l3, 50, 1, 98.0, float("nan")) == -1         # NaN minutes trigger nothing


def test_signed_move_and_vector_version_agree():
    c = np.linspace(100, 110, NR)
    assert P.signed_move_bps(c, 10, 5, 1) == pytest.approx((c[15] - c[10]) / c[10] * 1e4)
    assert P.signed_move_bps(c, 10, 5, -1) == pytest.approx(-(c[15] - c[10]) / c[10] * 1e4)
    assert np.isnan(P.signed_move_bps(c, NR - 3, 5, 1))
    cc = np.stack([c, c * 2, c])
    cc[2, 20] = np.nan
    got = S._moves(cc, np.array([10, 10, 15]), 5, np.array([1, -1, 1]))
    assert got[0] == pytest.approx(P.signed_move_bps(c, 10, 5, 1))
    assert got[1] == pytest.approx(P.signed_move_bps(c * 2, 10, 5, -1))
    assert np.isnan(got[2])
    assert np.isnan(S._moves(cc, np.array([NR - 2]), 5, 1)).all()


# ------------------------------------------------------------------------------------------------ study pieces
def test_gates():
    good = dict(net=10, trades=150)
    assert S.gates(good, dict(net=1), dict(net=1), 0.05) == []
    assert "design net <= 0" in S.gates(dict(net=0, trades=150), dict(net=1), dict(net=1), 0.05)
    assert "walk-forward net <= 0" in S.gates(good, dict(net=-1), dict(net=1), 0.05)
    assert "final net <= 0" in S.gates(good, dict(net=1), dict(net=0), 0.05)
    assert "fewer than 100 design trades" in S.gates(dict(net=10, trades=99), dict(net=1), dict(net=1), 0.05)
    assert "twin q > 0.10" in S.gates(good, dict(net=1), dict(net=1), 0.11)
    assert "twin q > 0.10" in S.gates(good, dict(net=1), dict(net=1), None)


def test_twin_p_and_draws_exclude_own_day():
    assert S.twin_p(10.0, np.array([0.0] * 9 + [10.0])) == round(2 / 11, 4)
    rng = np.random.default_rng(0)
    x = S.draw_other(rng, 5, 2, 10000)
    assert set(x.tolist()) == {0, 1, 3, 4}
    assert set(S.draw_other(rng, 3, -1, 1000).tolist()) == {0, 1, 2}


def test_block_boot_interval_contains_mean():
    rng = np.random.default_rng(1)
    d = {k: list(rng.normal(5, 1, 3)) for k in range(40)}
    lo, hi = S.block_boot(d, 500, rng)
    m = np.mean([v for vs in d.values() for v in vs])
    assert lo < m < hi
    assert S.block_boot({1: [1.0]}, 10, rng) == (None, None)


def test_tests_are_the_eight_preregistered():
    assert S.P.TESTS == ("A-X1", "A-X2", "B-X1", "B-X2", "C-X1", "C-X2", "D-X1", "D-X2")
    assert P.DETECTORS == {"A": (0.95, 0.40), "B": (0.90, 0.40), "C": (0.95, 0.25), "D": (0.90, 0.25)}
    assert S.EXIT_SPEC["X2"]["stop"] == 0.15 and S.EXIT_SPEC["X2"]["target"] == 0.30
    assert P.FIRST_ER == 22 and P.LAST_ER == 330
