"""R207 / R208 tests (PREREG.md section 6): synthetic data only, no lake, no engine. Plain-English notes, 20 Nov 2026."""
from __future__ import annotations

import math
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r207cfg as K  # noqa: E402
import r207lib as L  # noqa: E402

N = K.NROWS


def day(seed=0, vol=3.0, start=25000.0):
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, vol, N))
    o = np.concatenate([[start], c[:-1]])
    return np.vstack([o, np.maximum(o, c) + 0.5 * vol, np.minimum(o, c) - 0.5 * vol, c])


def box(low=100.0, high=101.0):
    """Every minute: open = close = low, high = `high`, low = `low` (each 5-minute bar's range = high - low)."""
    a = np.empty((4, N))
    a[0], a[1], a[2], a[3] = low, high, low, low
    return a


# ================================================================================================ features
def test_activity_counts_only_complete_bars_before_entry():
    a = box(100.0, 102.0)
    assert L.activity(a, 30) == pytest.approx(6 * 2.0)        # bars 09:15 .. 09:44
    assert L.activity(a, 34) == pytest.approx(6 * 2.0)        # the bar that holds minute 30 .. 34 is not complete
    assert L.activity(a, 35) == pytest.approx(7 * 2.0)
    assert math.isnan(L.activity(a, 4)) and math.isnan(L.activity(a, 0))
    hole = box(100.0, 102.0)
    hole[:, 5:10] = np.nan                                    # a missing bar adds nothing
    assert L.activity(hole, 30) == pytest.approx(5 * 2.0)
    assert math.isnan(L.activity(np.full((4, N), np.nan), 30))


def test_fwd_range_and_range_so_far():
    a = day(1)
    assert L.fwd_range(a, 100) == pytest.approx(a[1, 100:130].max() - a[2, 100:130].min())
    assert L.fwd_range(a, N - 10) == pytest.approx(a[1, N - 10:].max() - a[2, N - 10:].min())      # too late for 30 minutes
    assert math.isnan(L.fwd_range(a, N)) and math.isnan(L.fwd_range(np.full((4, N), np.nan), 100))
    assert L.range_so_far(a, 100) == pytest.approx(a[1, :100].max() - a[2, :100].min())
    assert math.isnan(L.range_so_far(a, 0))
    assert L.last_close(a, 100) == a[3, 99] and math.isnan(L.last_close(a, 0))


def test_features_values_and_too_few_sessions():
    today, usual = box(100.0, 102.0), box(100.0, 101.0)       # today's bars are twice as wide as usual
    f = L.features(today, [usual] * 20, 60)
    assert f["ratio"] == pytest.approx(2.0)
    assert f["x"] == pytest.approx(2.0 * 1.0 / 100.0)         # ratio x the usual next-30-minute range / p0
    assert f["rt"] == pytest.approx(2.0)
    f9 = L.features(today, [usual] * 9, 60)                   # fewer than 10 earlier sessions: unknown (never skips)
    assert all(math.isnan(v) for v in f9.values())
    f15 = L.features(today, [usual] * 15, 60)                 # enough for x, not for rt (needs all 20)
    assert f15["x"] == pytest.approx(0.02) and math.isnan(f15["rt"])
    f25 = L.features(today, [box(100.0, 105.0)] * 5 + [usual] * 20, 60)             # only the latest 20 are read
    assert f25["ratio"] == pytest.approx(2.0)
    f3 = L.features(today, [usual] * 20, 3)                   # before the first complete bar: no activity yet
    assert math.isnan(f3["ratio"]) and math.isnan(f3["x"]) and f3["rt"] == pytest.approx(2.0)


@pytest.mark.parametrize("seed", range(4))
def test_features_look_ahead_guard(seed):
    today = day(seed, vol=4.0)
    prev = [day(100 + i) for i in range(20)]
    er = 120
    f = L.features(today, prev, er)
    noisy = today.copy()
    noisy[:, er:] = np.random.default_rng(seed).normal(1e6, 1e3, (4, N - er))
    assert L.features(noisy, prev, er) == f
    assert all(v == v for v in f.values())


# ================================================================================================ the walk-forward gate
def _trades(n_days=80, per=5, seed=0):
    rng = np.random.default_rng(seed)
    d0 = date(2025, 1, 1)
    days, xs = [], []
    for i in range(n_days):
        for _ in range(per):
            days.append(d0 + timedelta(days=i))
            xs.append(float(rng.random()))
    return days, xs


def test_wf_keep_needs_enough_earlier_trades_and_skips_the_right_share():
    days, xs = _trades()
    keep = L.wf_keep(days, xs, 1.0 / 3.0)
    first = [k for d, k in zip(days, keep) if d < date(2025, 1, 1) + timedelta(days=20)]
    assert all(first)                                         # 19 earlier days x 5 = 95 trades < 100: no skip
    late = [k for d, k in zip(days, keep) if d >= date(2025, 1, 1) + timedelta(days=20)]
    assert 0.2 < 1 - np.mean(late) < 0.45                     # about a third skipped afterwards
    assert 0.35 < 1 - np.mean([k for d, k in zip(days, L.wf_keep(days, xs, 0.5)) if d >= date(2025, 1, 21)]) < 0.65


def test_wf_keep_unknown_never_skips_and_reads_only_earlier_days():
    days, xs = _trades(seed=1)
    xs2 = list(xs)
    xs2[300] = float("nan")
    assert L.wf_keep(days, xs2, 0.5)[300]
    # look-ahead guard: changing the trades of a day or of later days never changes an earlier day's decisions,
    # and a trade's own day is not part of its reference
    cut = date(2025, 1, 1) + timedelta(days=50)
    xs3 = [9.9 if d >= cut else x for d, x in zip(days, xs)]
    a, b = L.wf_keep(days, xs, 0.5), L.wf_keep(days, xs3, 0.5)
    assert all(x == y for d, x, y in zip(days, a, b) if d < cut)
    same_day = [i for i, d in enumerate(days) if d == cut]
    xs4 = list(xs)
    for i in same_day[1:]:
        xs4[i] = -5.0                                         # the other trades of the day change
    assert L.wf_keep(days, xs4, 0.5)[same_day[0]] == a[same_day[0]]


def test_rt_keep_and_late_and_spearman():
    assert L.rt_keep([0.5, 0.85, 1.2, float("nan")]).tolist() == [False, True, True, True]
    assert L.is_late(345) and L.is_late(355) and not L.is_late(344) and not L.is_late(356)
    x = np.arange(50.0)
    assert L.spearman(x, x ** 3) == pytest.approx(1.0) and L.spearman(x, -x) == pytest.approx(-1.0)
    assert math.isnan(L.spearman([1.0, 2.0], [1.0, 2.0])) and math.isnan(L.spearman(x, np.ones(50)))
    assert L.spearman([1.0, 2.0, 2.0, 3.0], [1.0, 2.0, 2.0, 3.0]) == pytest.approx(1.0)        # ties share a rank
    assert L.spearman([1.0, float("nan"), 3.0, 4.0], [1.0, 9.0, 3.0, 4.0]) == pytest.approx(1.0)  # unknown pairs dropped


# ================================================================================================ bootstraps and gates
def test_boot_mean_pos():
    rng = np.random.default_rng(1)
    good = rng.normal(300.0, 1000.0, 400)
    r = L.boot_mean_pos(good, 2000, np.random.default_rng(2))
    assert r["mean"] == pytest.approx(good.mean()) and r["p"] < 0.01 and r["p_normal"] < 0.01
    flat = rng.normal(0.0, 1000.0, 400)
    r = L.boot_mean_pos(flat, 2000, np.random.default_rng(3))
    assert 0.02 < r["p"] < 0.98
    assert L.boot_mean_pos([5.0], 10, np.random.default_rng(1))["p"] == 1.0 and L.boot_mean_pos([], 10, np.random.default_rng(1))["mean"] is None


def test_boot_rate_above():
    rng = np.random.default_rng(4)
    d = np.repeat(np.arange(200), 5)
    r = L.boot_rate_above(rng.random(1000) < 0.62, d, 2000, np.random.default_rng(5))
    assert r["p"] < 0.01 and r["lo"] < r["rate"] < r["hi"] and r["n"] == 1000 and r["days"] == 200
    r = L.boot_rate_above(rng.random(1000) < 0.50, d, 2000, np.random.default_rng(6))
    assert 0.02 < r["p"] < 0.98
    # whole days right or wrong together: the day-block interval is wide, so 55% of 40 days is not enough
    together = np.repeat(np.arange(40) < 22, 25)
    r = L.boot_rate_above(together, np.repeat(np.arange(40), 25), 2000, np.random.default_rng(7))
    assert r["rate"] == pytest.approx(0.55) and r["p"] > 0.1
    assert L.boot_rate_above([], [], 10, np.random.default_rng(1))["rate"] is None


def _st(net, trades=100):
    return dict(net=net, trades=trades)


def test_gate_late_passes_and_each_item_fails_alone():
    win = dict(design=_st(5000), holdout=_st(2000))
    folds = [_st(100), _st(200), _st(-50), _st(300)]
    assert L.gate_late(win, folds, 0.01, 0.05)["passes"]
    cases = {
        "design: net": (dict(win, design=_st(0)), folds, 0.01, 0.05),
        "holdout: net": (dict(win, holdout=_st(-1)), folds, 0.01, 0.05),
        "holdout: 39 trades": (dict(win, holdout=_st(2000, 39)), folds, 0.01, 0.05),
        "design quarters": (win, [_st(100), _st(-200), _st(-50), _st(300)], 0.01, 0.05),
        "bootstrap p": (win, folds, 0.05, 0.05),
        "BH q": (win, folds, 0.01, 0.2),
    }
    for item, args in cases.items():
        g = L.gate_late(*args)
        assert not g["passes"] and len(g["fails"]) == 1 and item in g["fails"][0], (item, g["fails"])


def test_gate_index():
    ok = dict(n=300, rate=0.6, p=0.001)
    assert L.gate_index(ok, 0.05)["holds"]
    assert L.gate_index(dict(ok, n=99), 0.05)["verdict"] == "too few to judge"
    assert L.gate_index(dict(ok, p=0.06), 0.05)["verdict"] == "does not hold"
    assert L.gate_index(dict(ok, rate=0.49, p=0.9), 0.05)["verdict"] == "does not hold"
    assert not L.gate_index(ok, 0.2)["holds"]


def test_settings_and_bh():
    assert len(K.GATE_TESTS) == 7 and len(K.LATE_TESTS) == 6 and K.N_TESTS == 15 and K.M_TOTAL == 4904
    assert K.SQ["15"] == 360 and K.FRESH[1] < date(2022, 4, 1)
    q = L.bh_total([1e-9, 0.5], 4904)
    assert q[0] == pytest.approx(1e-9 * 4904) and q[1] == 1.0
