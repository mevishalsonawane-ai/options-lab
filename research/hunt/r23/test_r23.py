"""R23 tests: ranges, value area, windows, breaks, features, outcome classes, configs, selection, BH, and the look-ahead
guards (PREREG.md A2, A3). Synthetic days only; no data download. 13 Oct 2026."""
from __future__ import annotations

import os
import sys
from datetime import date
from types import SimpleNamespace

import numpy as np
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import r23cfg as CFG  # noqa: E402
import r23features as F  # noqa: E402
import r23ranges as R  # noqa: E402


def flat_day(p=100.0):
    ix = np.full((375, 4), p, float)
    return ix


def set_min(ix, r, o, h, lo, c):
    ix[r] = (o, h, lo, c)


def walk(seed, p=20000.0, vol=8.0):
    rng = np.random.default_rng(seed)
    c = p + np.cumsum(rng.normal(0, vol, 375))
    o = np.concatenate([[p], c[:-1]])
    h = np.maximum(o, c) + rng.random(375) * vol
    lo = np.minimum(o, c) - rng.random(375) * vol
    return np.stack([o, h, lo, c], axis=1)


def noise_after(ix, r, seed):
    """Replace every minute after row r with noise."""
    out = ix.copy()
    tail = walk(seed, p=float(ix[r, 3]) * 1.03, vol=40.0)[r + 1:]
    out[r + 1:] = tail
    return out


def sess(ix, atr=200.0, prev=(19900.0, 20100.0, 19800.0, 19950.0), expiry=False, flow=None, hist=None, vix_min=None):
    ctx = SimpleNamespace(ix=ix, atr=atr, vix=14.0, expiry=expiry)
    return SimpleNamespace(ix=ix, atr=atr, prev=prev, ctx=ctx, flow=flow, ovol_hist=hist or [], vix_min=vix_min)


# ------------------------------------------------------------------------------------------------ ranges
def test_time_range_reads_only_its_rows():
    ix = flat_day()
    set_min(ix, 3, 100, 105, 99, 101)
    set_min(ix, 29, 100, 100, 90, 95)
    set_min(ix, 30, 100, 200, 10, 100)          # outside a 30-minute range
    rg = R.time_range(ix, 30)
    assert (rg["hi"], rg["lo"], rg["end"], rg["p_end"]) == (105, 90, 29, 95)


def test_vol_range_closes_at_threshold_or_cap():
    ix = flat_day()
    set_min(ix, 16, 100, 110, 100, 105)          # high - low = 10 by row 16 -> the 5-min bar ending row 19
    assert R.vol_range(ix, atr=20.0, k=0.5)["end"] == 19
    assert R.vol_range(ix, atr=20.0, k=0.3)["end"] == 19      # never before row 14 ... and here reached at 19
    set_min(ix, 2, 100, 107, 100, 100)
    assert R.vol_range(ix, atr=20.0, k=0.3)["end"] == 14      # 7 >= 6 already by 09:29
    assert R.vol_range(ix, atr=1000.0, k=0.5)["end"] == 59    # never reached: 10:14 cap
    rg = R.vol_range(ix, atr=1000.0, k=0.5)
    assert rg["hi"] == 110 and rg["lo"] == 100


def test_value_area_symmetric_and_inside():
    closes = np.array([100.0] * 40 + [101.0] * 10 + [99.0] * 10)
    vah, val = R.value_area(closes, 110.0, 90.0, nbins=20)
    assert (val, vah) == (100.0, 102.0)          # bins of 1 from 90; POC [100, 101); 42 of 60 needs one neighbour; tie -> upper
    flat = np.full(60, 100.0)
    vah, val = R.value_area(flat, 100.0, 100.0)
    assert val <= 100.0 <= vah


def test_pd_and_gap_ranges():
    ix = flat_day()
    rg = R.pd_range(ix, 45, pdh=120.0, pdl=95.0)
    assert (rg["hi"], rg["lo"]) == (120.0, 95.0)
    rg = R.gap_range(ix, 30, pclose=90.0)
    assert (rg["hi"], rg["lo"]) == (100.0, 90.0)


def test_mirror_twin():
    rg = dict(hi=110.0, lo=100.0, end=29, p_end=108.0)
    m = R.mirror(rg)
    assert (m["hi"], m["lo"]) == (116.0, 106.0) and m["hi"] - m["lo"] == 10.0


def test_windows_match_orb_and_r22():
    assert R.window(49, 5) == (10, 56)           # ORB: 10:05 .. 13:55
    assert R.window(44, 15) == (3, 18)           # R22: 10:00 .. 13:45
    assert R.window(29, 30) == (1, 8)            # 09:45 .. 13:15 (closes 13:44)
    assert R.window(14, 15) == (1, 18)
    assert R.row_of(56, 5) == 284


def test_first_breaks_window_and_side():
    ix = flat_day()
    rg = dict(hi=101.0, lo=99.0, end=49, p_end=100.0)
    ix[44, 3] = 105.0                            # before the window: ignored
    ix[59, 3] = 102.0                            # 5-min candle 11 (rows 55..59) closes above
    ix[289, 3] = 90.0                            # after 14:00: ignored
    assert R.first_breaks(ix, rg, 5) == [(1, 11, 59)]
    ix[64, 3] = 98.0
    assert R.first_breaks(ix, rg, 5) == [(1, 11, 59), (-1, 12, 64)]
    assert R.first_break_any(ix, rg, 5) == (1, 11, 59)
    ix[59, 3] = 101.0                            # AT the edge is not beyond
    assert R.first_breaks(ix, rg, 5) == [(-1, 12, 64)]


# ------------------------------------------------------------------------------------------------ features / outcome
def test_features_basic_values():
    ix = flat_day(20000.0)
    set_min(ix, 0, 20100.0, 20110.0, 20090.0, 20100.0)      # gap up 150 vs previous close 19950
    rg = R.time_range(ix, 50)
    ix[54, 3] = 20200.0                                      # breaks up at the 10:05 candle
    f = F.features(sess(ix), rg, 1, 54)
    assert f["gap_al"] == pytest.approx(150 / 200)
    assert f["w_atr"] == pytest.approx((20110 - 20000) / 200) and f["or_inside_pd"] == 0.0
    assert f["beyond_pd"] == 1.0 and f["t_break"] == 55
    assert f["brk_str"] == pytest.approx((20200 - rg["hi"]) / 200)
    assert np.isnan(f["ovol45_rel"]) and np.isnan(f["vix_chg"])
    f2 = F.features(sess(ix), rg, -1, 54)
    assert f2["gap_al"] == pytest.approx(-150 / 200)


def test_edge_tests_count():
    ix = flat_day(100.0)
    set_min(ix, 10, 100, 110, 90, 100)                       # range 90 .. 110 (rows 0 .. 49)
    rg = R.time_range(ix, 50)
    ix[52, 1] = 109.5                                        # candle 10 tests (within 0.1 x 20 = 2) and closes inside
    ix[57, 1] = 108.5                                        # candle 11 tests
    ix[62, 1] = 105.0                                        # candle 12 does not
    ix[69, 3] = 111.0                                        # candle 13 breaks
    f = F.features(sess(ix, atr=50.0), rg, 1, 69)
    assert f["tests"] == 2


def test_option_flow_features():
    ix = flat_day(100.0)
    vol = np.ones(375, np.float32)
    oc = np.full(375, 100.0, np.float32)
    op = np.full(375, 100.0, np.float32)
    op[30:] = 120.0
    hist = [(45.0 / 2, 15.0 / 2)] * 20
    rg = R.time_range(ix, 30)
    f = F.features(sess(ix, flow=(vol, oc, op), hist=hist), rg, 1, 34)
    assert f["ovol15_rel"] == pytest.approx(2.0) and np.isnan(f["ovol45_rel"])   # row 34 < 44: not known yet
    assert f["oi_flow_al"] == pytest.approx(20 / 200) and f["oi_chg"] == pytest.approx(0.1)
    g = F.features(sess(ix, flow=(vol, oc, op), hist=hist), rg, -1, 54)
    assert g["ovol45_rel"] == pytest.approx(2.0) and g["oi_flow_al"] == pytest.approx(-0.1)


def test_outcome_classes():
    ix = flat_day(100.0)
    rg = dict(hi=101.0, lo=99.0, end=49, p_end=100.0)
    ix[54, 3] = 102.0
    run = ix.copy()
    run[200, 1] = 102.0 + 0.6 * 10
    o = F.outcome(run, rg, 1, 54, 10.0)
    assert o["cls"] == "RUN" and not o["r10"]
    fake = ix.copy()                             # every later close at 100: back inside at once, MFE 0
    assert F.outcome(fake, rg, 1, 54, 10.0)["cls"] == "FAKE"
    chop = ix.copy()
    chop[55:, :] = 102.0                         # stays out, never runs
    assert F.outcome(chop, rg, 1, 54, 10.0)["cls"] == "CHOP"
    late = ix.copy()
    late[350, 1] = 200.0                         # after 14:59: not counted
    assert F.outcome(late, rg, 1, 54, 10.0)["cls"] == "FAKE"


# ------------------------------------------------------------------------------------------------ look-ahead guards
@pytest.mark.parametrize("seed", range(40))
def test_lookahead_ranges(seed):
    ix = walk(seed)
    rng = np.random.default_rng(seed + 100)
    for mk in (lambda x: R.time_range(x, 30), lambda x: R.time_range(x, 75), lambda x: R.va_range(x, 60),
               lambda x: R.vol_range(x, 60.0, 0.4), lambda x: R.pd_range(x, 45, 20100, 19900),
               lambda x: R.gap_range(x, 30, 19950)):
        rg = mk(ix)
        rg2 = mk(noise_after(ix, rg["end"], int(rng.integers(1e9))))
        assert rg == rg2


@pytest.mark.parametrize("seed", range(40))
def test_lookahead_breaks_and_features(seed):
    ix = walk(seed, vol=15.0)
    rng = np.random.default_rng(seed + 7)
    flow = (rng.random(375).astype(np.float32), (100 + rng.random(375)).astype(np.float32),
            (100 + rng.random(375)).astype(np.float32))
    vm = 14 + rng.random(375)
    for T, tf in ((50, 5), (30, 5), (45, 15), (60, 30)):
        rg = R.time_range(ix, T)
        for side, k, row in R.first_breaks(ix, rg, tf):
            other = noise_after(ix, row, int(rng.integers(1e9)))
            vm2 = vm.copy()
            vm2[row + 1:] = 99.0
            fl2 = tuple(np.where(np.arange(375) > row, 1e6, a).astype(np.float32) for a in flow)
            assert (side, k, row) in R.first_breaks(other, rg, tf)
            a = F.features(sess(ix, flow=flow, hist=[(10.0, 3.0)] * 20, vix_min=vm), rg, side, row)
            b = F.features(sess(other, flow=fl2, hist=[(10.0, 3.0)] * 20, vix_min=vm2), rg, side, row)
            assert a.keys() == b.keys()
            for key in a:
                assert (np.isnan(a[key]) and np.isnan(b[key])) or a[key] == b[key], key


def test_period_of_release_and_lake(monkeypatch):
    assert CFG.period_of(date(2026, 4, 13)) == ("design" if CFG.SOURCE == "release" else "design")
    monkeypatch.setattr(CFG, "SOURCE", "lake")
    assert CFG.period_of(date(2026, 4, 13)) == "design"
    assert CFG.period_of(date(2026, 4, 20)) == "embargo"
    assert CFG.period_of(date(2026, 4, 27)) == "holdout"
    monkeypatch.setattr(CFG, "SOURCE", "release")
    assert CFG.period_of(date(2026, 4, 27)) == "design"
