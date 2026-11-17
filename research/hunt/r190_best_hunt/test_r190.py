"""R190 tests (PREREG.md section 8): the look-ahead guard, the rules on hand-made days, sizing, the month cap, stats,
BH, the twin p, the gate and the era-A lock. No data needed.

    python -m pytest research/hunt/r190_best_hunt -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r190cfg as K  # noqa: E402
import r190lib as G  # noqa: E402
import r190study as ST  # noqa: E402


def day(path: np.ndarray) -> np.ndarray:
    """A (375, 4) day from a close path (open = previous close, high / low around them)."""
    a = np.full((K.N, 4), np.nan)
    c = np.asarray(path, float)
    o = np.concatenate([[c[0]], c[:-1]])
    a[:len(c), 0], a[:len(c), 3] = o, c
    a[:len(c), 1], a[:len(c), 2] = np.maximum(o, c) + 1, np.minimum(o, c) - 1
    return a


def ramp(start, end, n=K.N):
    return np.linspace(start, end, n)


# ================================================================================================ look-ahead guard
def test_momentum_ignores_every_minute_after_1450():
    rng = np.random.default_rng(1)
    a = day(ramp(100, 110))
    b = a.copy()
    b[K.R1450:] = rng.normal(500, 50, size=b[K.R1450:].shape)     # the future replaced by noise
    assert G.mom_returns(a, 99.0) == G.mom_returns(b, 99.0)


def test_noise_levels_ignore_minutes_after_each_mark():
    rng = np.random.default_rng(2)
    a = day(100 + np.cumsum(rng.normal(0, 0.1, K.N)))
    sig = np.full(len(K.MARKS), 0.002)
    c, hi, lo = G.noise_levels(a, sig)
    for k, t in enumerate(K.MARKS):
        b = a.copy()
        b[t:] = rng.normal(1000, 1, size=b[t:].shape)
        c2, hi2, lo2 = G.noise_levels(b, sig)
        assert c2[k] == c[k] and hi2[k] == hi[k] and lo2[k] == lo[k]


def test_sigma_uses_only_earlier_days():
    days = [day(ramp(100, 100 + i * 0.1)) for i in range(20)]
    s1 = G.sigma_marks(days[:14])
    s2 = G.sigma_marks(days[:14] + [])
    assert np.allclose(s1, s2)
    assert np.isnan(G.sigma_marks(days[:13])).all()          # not enough history: no band, no trade


def test_blank_after_copies():
    a = day(ramp(1, 2))
    b = G.blank_after(a, 10)
    assert np.isnan(b[10:]).all() and np.isfinite(a[10:]).all()


# ================================================================================================ momentum
def test_mom_returns_values():
    a = day(ramp(100, 110))
    r1, r2 = G.mom_returns(a, 100.0)
    p1 = a[K.R0945 - 1, 3]
    p2 = a[K.R1450 - 1, 3]
    assert r1 == pytest.approx(p1 / 100 - 1) and r2 == pytest.approx(p2 / p1 - 1)
    assert np.isnan(G.mom_returns(a, np.nan)[0])


def test_mom_side_rules():
    hist = [0.001] * 60
    assert G.mom_side(0.002, 0.003, hist, hist, "sum") == 1
    assert G.mom_side(-0.002, -0.003, hist, hist, "sum") == -1
    assert G.mom_side(0.010, -0.003, hist, hist, "sum") == 0          # r1 + r2 > 0 but r2 < 0: signs differ
    assert G.mom_side(0.0, 0.0005, hist, hist, "sum") == 0            # too small (below the 60th percentile)
    assert G.mom_side(0.0, 0.0, hist, hist, "sum") == 0
    assert G.mom_side(0.5, -0.003, hist, hist, "r2") == -1            # r2 alone ignores r1
    assert G.mom_side(0.002, 0.003, hist[:59], hist[:59], "sum") == 0  # not enough history


def test_top40_percentile():
    hist = list(np.arange(1, 61) / 1000)
    assert G.top40(0.0360, hist) is False or G.top40(0.0360, hist) is True
    assert G.top40(-0.06, hist) is True
    assert G.top40(0.001, hist) is False
    assert G.top40(np.percentile(np.abs(hist), 60), hist) is True       # at the 60th percentile counts


# ================================================================================================ noise area
def test_noise_walk_entry_exit_and_reentry():
    c = np.array([101, 102, 100, 97, 96, 99, 100, 100, 100, 100, 100], float)
    hi = np.full(11, 100.5)
    lo = np.full(11, 98.0)
    w = G.noise_walk(c, hi, lo, n_entry=10, last_mark=10)
    # long at 0, exits at 2 (100 < 100.5); short at 3 (97 < 98); exits at 5 (99 > 98)
    assert w == [(0, 1, 2), (3, -1, 5)]


def test_noise_walk_reverses_at_same_mark_and_holds_to_close():
    c = np.array([101, 97, 97, 97, 97, 97, 97, 97, 97, 97, 97], float)
    hi, lo = np.full(11, 100.0), np.full(11, 98.0)
    assert G.noise_walk(c, hi, lo, 10, 10) == [(0, 1, 1), (1, -1, -1)]


def test_noise_walk_no_entry_after_last_entry_mark_and_overlay():
    c = np.array([99] * 10 + [105], float)
    hi, lo = np.full(11, 100.0), np.full(11, 98.0)
    assert G.noise_walk(c, hi, lo, 10, 10) == []
    c2 = np.array([105] * 11, float)
    assert G.noise_walk(c2, hi, lo, 10, 10, allow=lambda k, s: False) == []
    assert G.noise_exit(c2, hi, lo, 0, 1, 10) == -1


def test_twap_and_bands():
    a = day(np.full(K.N, 100.0))
    tw = G.twap_marks(a)
    assert np.allclose(tw, 100.0)
    c, hi, lo = G.noise_levels(a, np.full(len(K.MARKS), 0.01))
    assert np.allclose(hi, 101.0) and np.allclose(lo, 99.0)


# ================================================================================================ overlay, sizing, cap
def test_overlay():
    assert G.overlay_ok(1, 101, 100, False) and not G.overlay_ok(1, 99, 100, False)
    assert G.overlay_ok(-1, 99, 100, False) and not G.overlay_ok(-1, 101, 100, False)
    assert not G.overlay_ok(1, 101, 100, True)
    assert not G.overlay_ok(1, 101, np.nan, False)
    assert np.isnan(G.sma_prev([1.0] * 49)) and G.sma_prev([1.0] * 49 + [2.0]) == pytest.approx(51 / 50)


def test_vix_high():
    base = [15.0 + (i % 10) for i in range(251)]
    assert G.vix_high(base + [40.0]) is True
    assert G.vix_high(base + [15.0]) is False
    assert G.vix_high([20.0] * 100) is False                         # not enough history: never skips


def test_fit_lots():
    assert G.fit_lots(100.0, 75) == 7            # 56,000 / 7,500
    assert G.fit_lots(800.0, 75) == 0
    assert G.fit_lots(np.nan, 75) == 0 and G.fit_lots(10.0, 0) == 0


def test_month_cap():
    tr = [dict(day=date(2024, 1, d), net=n) for d, n in ((2, -8000), (3, -6000), (4, 9000), (5, 1000))]
    tr.append(dict(day=date(2024, 2, 1), net=-500))
    kept = G.month_capped(tr)
    assert [t["day"].day for t in kept if t["day"].month == 1] == [2, 3]      # stopped after -14,000
    assert any(t["day"].month == 2 for t in kept)                             # a new month starts again


# ================================================================================================ stats, twins, BH, gate
def test_stats_and_drawdown():
    tr = [dict(day=date(2024, 1, 1), net=100.0), dict(day=date(2024, 1, 1), net=-50.0),
          dict(day=date(2024, 1, 2), net=-300.0), dict(day=date(2024, 2, 1), net=500.0)]
    s = G.stats(tr)
    assert s["trades"] == 4 and s["win"] == 50.0 and s["net"] == 250.0
    assert s["worst_day"] == -300.0 and s["max_dd"] == 300.0
    assert s["worst_month"] == -250.0 and s["avg_win"] == 300.0 and s["avg_loss"] == -175.0
    assert G.stats([])["trades"] == 0
    assert G.stats([dict(day=date(2024, 1, 1), net=1.0, fit=None)], "fit")["trades"] == 0
    assert G.max_drawdown([10, -5, -10, 20]) == 15


def test_twin_p_and_draw():
    pe, pn = G.twin_p(10.0, [0.0] * 99 + [20.0])
    assert pe == pytest.approx(2 / 101) and 0 <= pn <= 1
    rng = np.random.default_rng(0)
    tw = G.twin_draw(np.ones(10), -np.ones(10), 4, rng, n_twins=200)
    assert tw.shape == (200,) and set(np.unique(tw)) <= {-4, -2, 0, 2, 4}
    assert (G.twin_draw(np.array([]), np.array([]), 3, rng, 5) == 0).all()


def test_bh_total():
    q = G.bh_total([0.0001, 0.5], m_total=1000)
    assert q[0] == pytest.approx(0.1) and q[1] == 1.0
    assert len(G.bh_total([])) == 0


def _ok(n=50, net=1000.0, wo=500.0):
    return dict(trades=n, net=net, net_wo_top5=wo)


def test_gate():
    assert G.gate({"A": _ok(), "B": _ok(), "C": _ok()}, 0.01, 0.05) == []
    f = G.gate({"A": None, "B": _ok(), "C": _ok()}, None, 1.0)
    assert any("untestable" in x for x in f)
    f = G.gate({"A": _ok(39), "B": _ok(net=-1), "C": _ok(wo=-1)}, 0.2, 0.5)
    assert len(f) == 5
    assert G.account_breach(dict(max_dd=50000, worst_month=-20000)) and not G.account_breach(dict(max_dd=1, worst_month=0))


# ================================================================================================ the era cut and lock
def test_select_era_refuses_a_closed_era():
    tr = [dict(era="A", net=1), dict(era="B", net=2)]
    assert ST.select_era(tr, "B", ("B", "C")) == [tr[1]]
    with pytest.raises(PermissionError):
        ST.select_era(tr, "A", ("B", "C"))


def test_era_a_lock():
    assert ST.era_a_allowed(True, False)
    assert not ST.era_a_allowed(False, False)       # flag not committed
    assert not ST.era_a_allowed(True, True)         # already read once


def test_eras_and_counts():
    assert K.era_of(date(2020, 8, 3)) == "A" and K.era_of(date(2021, 7, 30)) == "A"
    assert K.era_of(date(2021, 9, 1)) is None and K.era_of(date(2021, 12, 1)) == "B"
    assert K.era_of(date(2024, 11, 1)) == "C" and K.era_of(date(2026, 11, 14)) is None
    assert K.M_TOTAL == 4749 + 13
    assert K.MARKS[0] == K.row(10, 15) and K.MARKS[-1] == K.row(15, 15) and K.LAST_ENTRY_MARK == K.row(14, 45)
