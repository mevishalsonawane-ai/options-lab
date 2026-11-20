"""R206 tests (PREREG.md section 8): synthetic data only, no lake, no engine. Plain-English notes, 20 Nov 2026."""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r206cfg as K  # noqa: E402
import r206lib as L  # noqa: E402

N = K.NROWS


def day(seed=0, drift=0.0, start=25000.0):
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(drift, 3.0, N))
    o = np.concatenate([[start], c[:-1]])
    return np.vstack([o, np.maximum(o, c) + 1, np.minimum(o, c) - 1, c])


def ramp(a=100.0, step=1.0):
    c = a + step * np.arange(N, dtype=float)
    return np.vstack([c, c, c, c])


# ================================================================================================ right / wrong
def test_move_up_and_down():
    up = ramp(100.0, 1.0)                                    # close at minute r = 100 + r
    assert L.move(up, 30, "C", 15) == pytest.approx((100 + 44) / (100 + 29) - 1)
    assert L.move(up, 30, "P", 15) == pytest.approx(-((100 + 44) / (100 + 29) - 1))
    assert L.move(up, 30, "C", 30) == pytest.approx((100 + 59) / (100 + 29) - 1)
    flat = ramp(100.0, 0.0)
    assert L.move(flat, 30, "C", 15) == 0.0                  # no move is not "right" (right needs above 0)


def test_move_too_late_or_unknown():
    up = ramp()
    assert not math.isnan(L.move(up, N - 15, "C", 15))        # the horizon ends on the last minute
    assert math.isnan(L.move(up, N - 14, "C", 15))            # one minute past the session
    assert math.isnan(L.move(up, N - 20, "C", 30))
    assert math.isnan(L.move(up, 0, "C", 15))                 # nothing known before the entry minute
    gone = ramp()
    gone[:, 30:60] = np.nan                                   # the market closed mid-day: nothing to judge
    assert math.isnan(L.move(gone, 30, "C", 15))
    assert not math.isnan(L.move(gone, 30, "C", 30 + 15))     # a print inside a longer horizon is used
    hole = ramp()
    hole[:, 44] = np.nan                                      # the horizon minute is missing: the last close before it
    assert L.move(hole, 30, "C", 15) == pytest.approx((100 + 43) / (100 + 29) - 1)
    blank = ramp()
    blank[:, :30] = np.nan
    assert math.isnan(L.move(blank, 30, "C", 15))             # p0 unknown


@pytest.mark.parametrize("seed", range(5))
def test_look_ahead_guard(seed):
    """Prices are unchanged by anything after the horizon; the labels by anything from the entry minute on."""
    a = day(seed, drift=0.2)
    er = 60
    b = a.copy()
    b[:, er + 30:] = 1e6
    assert L.move(a, er, "C", 15) == L.move(b, er, "C", 15) and L.move(a, er, "P", 30) == L.move(b, er, "P", 30)
    c = a.copy()
    c[:, er:] = 1e6
    assert L.with_day(a, er, "C") == L.with_day(c, er, "C")
    c15a, c15c = L.bars15_close(a), L.bars15_close(c)
    ea, ec = L.ema(c15a, K.EMA_N), L.ema(c15c, K.EMA_N)
    assert L.with_market(c15a, ea, 0, er, "C") == L.with_market(c15c, ec, 0, er, "C")
    er2 = 67                                                  # mid-bar: the bar holding the entry minute is never read
    d = a.copy()
    d[:, 60:] = -5.0
    assert L.with_market(c15a, ea, 0, er2, "C") == L.with_market(L.bars15_close(d), L.ema(L.bars15_close(d), K.EMA_N), 0, er2, "C")


def test_with_day():
    up = ramp(100.0, 1.0)
    assert L.with_day(up, 30, "C") is True and L.with_day(up, 30, "P") is False
    dn = ramp(100.0, -0.1)
    assert L.with_day(dn, 30, "C") is False and L.with_day(dn, 30, "P") is True
    assert L.with_day(ramp(100.0, 0.0), 30, "C") is None      # equal: unknown
    assert L.with_day(up, 0, "C") is None
    late = ramp()
    late[:, :10] = np.nan                                     # the first minutes are missing: the first known open
    assert L.with_day(late, 30, "C") is True


def test_with_market_last_completed_bar_and_holiday_carry():
    c15 = np.array([10.0, 10.0, np.nan, 12.0, 9.0])
    e15 = np.array([11.0, 11.0, 11.0, 11.0, 11.0])
    # day starts at index 3 (bars 0..2 are the session before; its last bar is missing: a holiday-like hole)
    assert L.with_market(c15, e15, 3, 5, "C") is False        # 09:20: the session before's last KNOWN bar (10 < 11)
    assert L.with_market(c15, e15, 3, 5, "P") is True
    assert L.with_market(c15, e15, 3, 15, "C") is True        # 09:30: today's first bar (12 > 11)
    assert L.with_market(c15, e15, 3, 29, "C") is True        # 09:44: still the first bar (the second is not complete)
    assert L.with_market(c15, e15, 3, 30, "C") is False       # 09:45: the second bar (9 < 11)
    assert L.with_market(c15, e15, 0, 5, "C") is None         # nothing before the very first bar
    assert L.with_market(np.array([11.0]), np.array([11.0]), 0, 15, "C") is None
    e = L.ema(np.array([1.0, np.nan, 3.0]), 3)
    assert e[1] == 1.0 and e[2] == pytest.approx(2.0)


def test_bars15_and_key():
    a = ramp()
    b = L.bars15_close(a)
    assert len(b) == 25 and b[0] == a[3, 14] and b[24] == a[3, 374]
    a[:, 15:30] = np.nan
    assert math.isnan(L.bars15_close(a)[1])
    s1 = dict(und="NIFTY", day=1, er=np.int64(30), side="C", src="R72")
    s2 = dict(und="NIFTY", day=1, er=30, side="C", src="R51")
    assert L.key(s1) == L.key(s2) and L.key(dict(s2, side="P")) != L.key(s1) and L.key(dict(s2, er=35)) != L.key(s1)


# ================================================================================================ bootstrap, BH, gate
def _sample(p_a, p_b, days=300, per=6, seed=1):
    rng = np.random.default_rng(seed)
    d = np.repeat(np.arange(days), per)
    a = np.tile(np.array([True, False] * (per // 2)), days)
    right = np.where(a, rng.random(len(d)) < p_a, rng.random(len(d)) < p_b)
    return right, a, d, days


def test_boot_diff_finds_a_real_difference_and_not_a_fake_one():
    right, a, d, days = _sample(0.62, 0.50)
    r = L.boot_diff(L.day_table(right, a, d, days), 800, np.random.default_rng(2))
    assert r["d"] == pytest.approx(right[a].mean() - right[~a].mean()) and r["p"] < 0.01 and r["p_normal"] < 0.01
    right, a, d, days = _sample(0.55, 0.55, seed=3)
    r = L.boot_diff(L.day_table(right, a, d, days), 800, np.random.default_rng(4))
    assert 0.05 < r["p"] < 0.95


def test_boot_diff_day_blocks_are_wider_than_signal_noise():
    """Signals of one day rise and fall together: the day-block interval must be wide."""
    rng = np.random.default_rng(5)
    days, per = 200, 10
    good = rng.random(days) < 0.5
    d = np.repeat(np.arange(days), per)
    a = np.tile(np.array([True, False] * (per // 2)), days)
    right = np.repeat(good, per)                              # every signal of a day is right or wrong together
    r = L.boot_diff(L.day_table(right, a, d, days), 500, np.random.default_rng(6))
    assert r["d"] == pytest.approx(0.0) and r["p"] > 0.2


def test_boot_edge_cases_and_rate():
    t = L.day_table(np.array([True, False]), np.array([True, True]), np.array([0, 1]), 2)      # no B at all
    assert L.boot_diff(t, 50, np.random.default_rng(1))["p"] == 1.0
    assert L.boot_diff(np.zeros((1, 4)), 50, np.random.default_rng(1))["d"] is None
    right, a, d, days = _sample(0.6, 0.6)
    r = L.boot_rate(right, d, days, 400, np.random.default_rng(7))
    assert r["lo"] < r["rate"] < r["hi"] and r["n"] == len(right) and 0.55 < r["rate"] < 0.65
    assert L.boot_rate(np.zeros(0, bool), np.zeros(0, int), 3, 10, np.random.default_rng(1))["rate"] is None


def test_bh():
    q = L.bh_total([1e-9, 0.5], 4888)
    assert q[0] == pytest.approx(1e-9 * 4888) and q[1] == 1.0
    assert L.bh_total([0.01, 0.02], 2) == pytest.approx([0.02, 0.02])


def _ok():
    old = dict(d=0.05, p=0.001, n_a=500, n_b=900, rate_a=0.58, rate_src=0.55)
    return old, {"2022": 0.04, "2023": 0.06, "2024": -0.01}, dict(d=0.03), 0.05


def test_gate_passes_and_each_item_fails_alone():
    old, years, recent, q = _ok()
    assert L.gate(old, years, recent, q)["more_reliable"]
    cases = {
        "OLD: difference": (dict(old, p=0.06), years, recent, q),
        "BH q": (old, years, recent, 0.2),
        "OLD years": (old, {"2022": 0.04, "2023": -0.06, "2024": -0.01}, recent, q),
        "RECENT": (old, years, dict(d=-0.01), q),
        "signals in A / B": (dict(old, n_a=199), years, recent, q),
        "above the source's own": (dict(old, rate_a=0.569), years, recent, q),
    }
    for item, args in cases.items():
        g = L.gate(*args)
        assert not g["more_reliable"] and len(g["fails"]) == 1 and item in g["fails"][0], (item, g["fails"])
    g = L.gate(dict(old, d=None, p=1.0, rate_a=None), {"2022": None}, dict(d=None), None)
    assert not g["more_reliable"] and len(g["fails"]) == 5


def test_settings():
    assert len(K.TESTS) == 8 and K.M_TOTAL == 4888
    assert K.ERAS["old"][1] < K.ERAS["recent"][0] and K.ERAS["recent"][1] == K.LAST_DAY
    assert all(K.ERAS["old"][0] <= a <= b <= K.ERAS["old"][1] for a, b in K.YEARS["old"].values())
    assert {t[0] for t in K.TESTS.values()} <= set(K.SOURCES)
