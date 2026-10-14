"""R27 tests: python3 -m pytest research/hunt/r27/test_r27.py -q"""
from __future__ import annotations

import math
import os
import sys

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import r27cfg as K27  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r26lib as L26  # noqa: E402
import r27feat as F  # noqa: E402
import r27late as KL  # noqa: E402
import r27rules as RR  # noqa: E402
from r25data import N, OI, minute  # noqa: E402
from test_r25 import flat, make_day, rally  # noqa: E402


def _same(a, b):
    return a == b or (isinstance(a, float) and isinstance(b, float) and math.isnan(a) and math.isnan(b))


# ------------------------------------------------------------------------------------------------ features
def test_feature_look_ahead_guard_synthetic():
    day = make_day(rally(start=minute(13, 0)))
    ratio = np.ones(len(F.DECISIONS))
    for j, t in enumerate(F.DECISIONS[::4]):
        want = F.rows_at(day, t, 1.0)
        for fut in (L26.masked(day, t), L26.poisoned(day, t)):
            got = F.rows_at(fut, t, 1.0)
            assert len(want) == len(got)
            for x, y in zip(want, got):
                for k, v in x.items():
                    if k.startswith("_oi"):
                        continue
                    assert _same(v, y[k]), (t, k, v, y[k])
    assert len(ratio) == len(F.DECISIONS)


def test_seasonal_ratio_uses_earlier_days_only():
    ds = [make_day(rally(start=minute(13, 0), pts=50 + i)) for i in range(14)]
    full = F.all_ratios(ds)
    # changing the LAST day changes no ratio of any day (each day uses earlier days only)
    ds2 = ds[:-1] + [make_day(rally(start=minute(13, 0), pts=900))]
    full2 = F.all_ratios(ds2)
    for a, b in zip(full, full2):
        assert np.allclose(a, b, equal_nan=True)
    assert np.all(full[5] == 1.0)                      # fewer than 10 earlier days -> 1.0
    assert np.isfinite(full[12]).all()


def test_vol_and_fair_value_features():
    day = make_day(rally(start=minute(13, 0)))
    rows = F.rows_at(day, R.close_row(13, 30), 1.0)
    assert rows
    for f in rows:
        assert f["rv60"] > 0 and f["rv_fc"] == pytest.approx(f["rv60"])
        assert f["band"] in ("1-10", "10-15", "15-30") and 1 <= f["price"] <= 30
        assert math.isnan(f["iv_rv"]) or f["iv_rv"] > 0
        assert f["side"] in ("C", "P")
        # signed toward the option: a rally is positive for calls, negative for puts
        if f["side"] == "C":
            assert f["trend_open"] > 0
        else:
            assert f["trend_open"] < 0


def test_gamma_pin_is_the_largest_oi_gamma_strike():
    day = make_day(flat())
    for (k, s), a in day.opts.items():
        if k == 25000.0 and s == "C":
            a[:, OI] = np.where(np.isnan(a[:, 3]), np.nan, 5e6)
    g = F.gamma_state(day, R.close_row(13, 0), 25010.0, 0.12)
    assert g["pin"] == 25000.0 and g["gam_tot"] > 0
    assert math.isnan(F.gamma_state(day, R.close_row(13, 0), math.nan, 0.12)["pin"])


def test_empty_or_closed_day_gives_no_rows():
    day = make_day(flat())
    day.ix[:] = np.nan
    assert F.rows_at(day, R.close_row(13, 0), 1.0) == []
    day = make_day(flat())
    day.opts = {}
    assert F.rows_at(day, R.close_row(13, 0), 1.0) == []


# ------------------------------------------------------------------------------------------------ rules
def _frame():
    rows = []
    for d, day in enumerate(pd.date_range("2024-01-04", periods=3, freq="7D").date):
        for t in (200, 205, 314):
            for j, iv_rv in enumerate((0.7, 1.4)):
                rows.append(dict(day=day, t=t, iv_rv=iv_rv + 0.01 * t, price=20.0, pin_dist=1.0, mv15=0.3, dist_pct=0.1 * (j + 1),
                                 rod=1.0, rv_day=10.0, y_net_E1=100.0, y_net_E3=50.0, y_risked=1000.0, y_peak=2.0, band="15-30"))
    return pd.DataFrame(rows)


def test_first_qualifying_time_then_best_score():
    df = _frame()
    idx = RR.pick_days(df, RR.f1_mask(df, 3.0), RR.f1_score(df))
    assert len(idx) == 3
    assert all(df.loc[i, "t"] == 200 and df.loc[i, "iv_rv"] == pytest.approx(2.7) for i in idx)   # first time, lowest iv_rv
    # the threshold only lets the later minutes through: the first qualifying time is used, never an earlier one
    idx2 = RR.pick_days(df, RR.f1_mask(df, 0.7 + 0.01 * 314 + 1e-9) & (df.t >= 205).values, RR.f1_score(df))
    assert all(df.loc[i, "t"] == 205 for i in idx2)


def test_q_rules_at_1430_momentum_and_reversal():
    df = _frame()
    m1 = RR.q_mask(df, 1.0, 1)
    assert set(df[m1].t) == {RR.AT_1430}
    df2 = df.assign(rod=-1.0)
    assert not RR.q_mask(df2, 1.0, 1).any() and RR.q_mask(df2, 1.0, -1).any()
    assert not RR.q_mask(df.assign(price=10.0), 0.5, 1).any()        # Rs 15-30 only


def test_choose_rule_falls_back_to_strict():
    df = _frame().iloc[:2]
    assert RR.choose_rule("F1", df) == (RR.STRICT["F1"], "E1")


# ------------------------------------------------------------------------------------------------ Boss's late range breakout
def test_k_range_and_first_break():
    spot = np.full(N, 25000.0)
    spot[minute(14, 50):] = 25100.0                       # breaks up at 14:50
    day = make_day(spot)
    hi, lo = KL.rng(day, 1)
    assert hi == 25000.0 and lo == 25000.0
    s = KL.signal(day, 1)
    assert s is not None and s[1] == 1 and s[0] == minute(14, 50) and s[2] == 25150.0
    assert KL.signal(make_day(flat()), 1) is None           # never breaks: no trade


def test_k_reads_no_future_and_window3_exits_after_fill():
    spot = np.full(N, 25000.0)
    spot[minute(15, 18):] = 24900.0
    day = make_day(spot)
    s = KL.signal(day, 3)
    assert s is not None and s[1] == -1
    for fut in (L26.masked(day, s[0]), L26.poisoned(day, s[0])):
        assert KL.signal(fut, 3)[:4] == s[:4]
    tr = KL.trade(day, 3, "X25")
    assert tr is not None and tr.exits[-1][0] > tr.fill_row


def test_k1e_uses_the_1505_square_off():
    spot = np.full(N, 25000.0)
    spot[minute(14, 50):] = 25100.0
    tr = KL.trade(make_day(spot), 1, "E1")
    assert tr is not None and tr.exits[-1][0] == minute(15, 5)


@pytest.mark.skipif(not D.path("NIFTY").exists(), reason="the lake day files are not built here")
def test_feature_guard_on_real_nifty_days():
    ds = sorted(D.load("NIFTY", "all"), key=lambda d: d.day)
    n = 0
    for day in (ds[60], ds[200]):
        for t in F.DECISIONS[::7]:
            want = F.rows_at(day, t, 1.2)
            got = F.rows_at(L26.poisoned(day, t), t, 1.2)
            assert len(want) == len(got)
            for x, y in zip(want, got):
                for k, v in x.items():
                    if not k.startswith("_oi"):
                        assert _same(v, y[k]), (day.day, t, k)
                n += 1
    assert n > 50
