"""R183 tests (synthetic): the sideways filters, the 5-minute bars, the selection rule, the BH count, the normal p and
the look-ahead guard (a filter's value at the entry does not change when the bars from the signal row on change)."""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r183lib as LB  # noqa: E402

N = 375


def _day(close, spread=1.0):
    c = np.asarray(close, float)
    a = np.full((4, N), np.nan)
    a[0, :len(c)] = np.concatenate([[c[0]], c[:-1]])
    a[1, :len(c)] = np.maximum(a[0, :len(c)], c) + spread
    a[2, :len(c)] = np.minimum(a[0, :len(c)], c) - spread
    a[3, :len(c)] = c
    return a


def _trend(n=N, start=100.0, step=1.0):
    return _day(start + step * np.arange(n), 0.1)


# ------------------------------------------------------------------------------------------------ bars
def test_bars5_uses_only_complete_bars_before_s():
    d = _trend()
    b = LB.bars5(d, 12)                       # rows 0..11 -> bars 0-4, 5-9 complete; 10-14 not (needs row 14 < 12)
    assert b.shape == (4, 2)
    assert b[3, 0] == d[3, 4] and b[3, 1] == d[3, 9]
    assert b[0, 0] == d[0, 0] and b[1, 1] == np.nanmax(d[1, 5:10]) and b[2, 1] == np.nanmin(d[2, 5:10])
    assert LB.bars5(d, 15).shape[1] == 3      # last row 14 <= s - 1 = 14
    assert LB.bars5(d, 4).shape[1] == 0
    assert LB.bars5(d).shape[1] == 75


def test_bars5_drops_all_nan_bars_and_handles_empty():
    d = _trend()
    d[:, 5:10] = np.nan
    assert LB.bars5(d, 20).shape[1] == 3
    assert LB.bars5(np.full((4, N), np.nan)).shape == (4, 0)


def test_series5_prepends_three_previous_sessions():
    prev = [_trend() for _ in range(5)]
    s = LB.series5(prev, _trend(), 30)
    assert s.shape[1] == 3 * 75 + 6            # rows 25-29 end at row 29 = s - 1: complete
    assert LB.series5([], _trend(), 30).shape[1] == 6


# ------------------------------------------------------------------------------------------------ indicators
def test_adx_high_on_trend_low_on_zigzag_and_nan_when_short():
    t = LB.bars5(_trend())
    z = LB.bars5(_day(100 + np.random.default_rng(1).normal(0, 2, N), 0.5))   # no trend: noise around 100
    assert LB.adx(t[1], t[2], t[3]) > 50
    assert LB.adx(z[1], z[2], z[3]) < 25
    assert math.isnan(LB.adx(t[1][:28], t[2][:28], t[3][:28]))
    assert not math.isnan(LB.adx(t[1][:29], t[2][:29], t[3][:29]))


def test_eff_ratio_values():
    assert LB.eff_ratio(np.arange(10.0), 6) == pytest.approx(1.0)
    assert LB.eff_ratio(np.array([0, 1, 0, 1, 0, 1, 0.0]), 6) == pytest.approx(0.0)
    assert LB.eff_ratio(np.array([0, 2, 1, 3.0]), 3) == pytest.approx(3 / 5)
    assert math.isnan(LB.eff_ratio(np.arange(6.0), 6))
    assert LB.eff_ratio(np.full(7, 5.0), 6) == 1.0       # flat path: no chop measured


def test_chop_trend_low_range_high():
    t = LB.bars5(_trend())
    z = LB.bars5(_day(100 + np.random.default_rng(1).normal(0, 2, N), 0.5))
    assert LB.chop(t[1], t[2], t[3]) < 40
    assert LB.chop(z[1], z[2], z[3]) > 61.8
    assert math.isnan(LB.chop(t[1][:14], t[2][:14], t[3][:14]))
    h = np.full(15, 1.0)
    assert LB.chop(h, h, h) == 100.0


def test_twap_crossings_counts_sign_changes_of_5min_closes():
    c = np.full(N, 100.0)
    # 5-minute closes alternate above / below 100 for 6 bars
    for k, sgn in enumerate([1, -1, 1, -1, 1, -1]):
        c[5 * k:5 * k + 5] = 100.0 + sgn * 2
    d = _day(c, 0.0)
    d[1], d[2] = d[3], d[3]                                # typical = close
    n = LB.twap_crossings(d, 30)
    assert n >= 4
    assert LB.twap_crossings(_trend(), 200) == 0
    assert LB.twap_crossings(d, 3) == 0


def test_day_range_and_ratios():
    prev = [_day(np.linspace(100, 120, N), 0.0) for _ in range(20)]   # each session's range = 20
    today = _day(np.linspace(100, 104, N), 0.0)
    f = LB.features(prev, today, 200)
    r = LB.day_range(today, 200)
    assert f["rf"] == pytest.approx(r / LB.day_range(prev[0]))
    assert f["rt"] == pytest.approx(r / LB.day_range(prev[0], 200))
    f19 = LB.features(prev[:19], today, 200)                            # fewer than 20 sessions: not computable
    assert math.isnan(f19["rf"]) and math.isnan(f19["rt"])
    assert math.isnan(LB.day_range(np.full((4, N), np.nan)))


# ------------------------------------------------------------------------------------------------ skip
def test_skip_directions_and_nan_never_skips():
    assert LB.skip("ADX20", dict(adx=19.9)) and not LB.skip("ADX20", dict(adx=20.0))
    assert LB.skip("CH62", dict(chop=61.9)) and not LB.skip("CH62", dict(chop=61.8))
    assert LB.skip("TX5", dict(tx=5)) and not LB.skip("TX5", dict(tx=4))
    assert LB.skip("RF40", dict(rf=0.39)) and not LB.skip("RF40", dict(rf=0.40))
    assert LB.skip("ER12_30", dict(er12=0.29)) and not LB.skip("ER12_30", dict(er12=0.3))
    for v in LB.VARIANTS:
        assert not LB.skip(v, dict(adx=float("nan"), er6=float("nan"), er12=float("nan"), rf=float("nan"),
                                   rt=float("nan"), chop=float("nan")) | {"tx": None})


def test_sixteen_variants_and_bh_count():
    assert len(LB.VARIANTS) == 16 and LB.N_SEL == 32
    assert LB.m_total(2) == 4073 + 270 + 32 + 2 == 4377
    assert set(LB.LABEL) == set(LB.VARIANTS)


# ------------------------------------------------------------------------------------------------ selection
def test_choose_needs_both_halves_and_takes_largest_gain():
    assert LB.choose({"ADX15": (1, 1, 5), "ADX20": (1, -1, 50), "TX3": (2, 3, 9)}) == "TX3"
    assert LB.choose({"ADX15": (1, 1, 9), "TX3": (2, 3, 9)}) == "ADX15"          # tie: the earlier
    assert LB.choose({"ADX15": (0, 1, 9), "TX3": (-1, 3, 9)}) is None
    assert LB.choose({}) is None


def test_normal_p():
    tw = np.array([0.0, 1.0, 2.0, 3.0, 4.0])
    assert LB.normal_p(2.0, tw) == pytest.approx(0.5)
    assert LB.normal_p(100.0, tw) < 1e-6
    assert LB.normal_p(5.0, np.full(5, 1.0)) == 0.0 and LB.normal_p(1.0, np.full(5, 1.0)) == 1.0


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("s", [5, 31, 120, 300, 370])
def test_lookahead_guard_noise_from_signal_row_on(s):
    rng = np.random.default_rng(s)
    prev = [_day(100 + np.cumsum(rng.normal(0, 1, N)), 0.5) for _ in range(20)]
    today = _day(100 + np.cumsum(rng.normal(0, 1, N)), 0.5)
    f0 = LB.features(prev, today, s)
    noisy = today.copy()
    noisy[:, s:] = rng.normal(500, 200, (4, N - s))         # every bar from the signal row on
    f1 = LB.features(prev, noisy, s)
    for k in f0:
        a, b = f0[k], f1[k]
        assert (isinstance(a, float) and math.isnan(a) and math.isnan(b)) or a == b, k
    for v in LB.VARIANTS:
        assert LB.skip(v, f0) == LB.skip(v, f1)


def test_post_walk_mask_is_a_subset(monkeypatch):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r179_winrate_55"))
    S = pytest.importorskip("r183study")
    trades = [dict(und="NIFTY", day=i, er_sig=10, net=1.0) for i in range(5)]
    S._FEAT.clear()
    S._FEAT.update({("NIFTY", i, 10): dict(adx=10.0 if i % 2 else 30.0) for i in range(5)})
    monkeypatch.setattr(S.S6, "_d", lambda x: x)          # synthetic integer days
    kept = S.filtered(trades, "ADX20")
    S._FEAT.clear()
    assert [t["day"] for t in kept] == [0, 2, 4]
    assert all(t in trades for t in kept)
