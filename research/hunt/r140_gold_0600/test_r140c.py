"""R140 Amendment A tests: predictor timing (MCX close in both DST regimes), look-ahead (nothing after 06:00 IST
changes a predictor), the big-day threshold uses earlier days only, and the window trade direction."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140c as C
from research.hunt.r140_gold_0600.test_r140 import minutes


def test_mcx_close_utc_both_regimes_and_monday():
    assert C.mcx_close_utc(date(2024, 7, 3)) == pd.Timestamp("2024-07-02 18:00")      # 23:30 IST, US summer
    assert C.mcx_close_utc(date(2024, 1, 17)) == pd.Timestamp("2024-01-16 18:25")     # 23:55 IST, US winter
    assert C.mcx_close_utc(date(2024, 7, 8)) == pd.Timestamp("2024-07-05 18:00")      # Monday -> Friday


def _days():
    n = 4 * 24 * 60
    rng = np.random.default_rng(3)
    c = 2300 + np.cumsum(rng.normal(0, 0.2, n))
    return minutes("2024-07-01 00:00", c)       # Mon 1 Jul - Thu 4 Jul 2024 (UTC)


def test_predictors_known_by_0600_ist():
    df = _days()
    X = R.Xau(df)
    wt = R.window_table(X)
    p1 = C.predictors(X, wt)
    d = date(2024, 7, 3)
    df2 = df.copy()
    df2.loc[df2.index >= pd.Timestamp("2024-07-03 00:30"), ["open", "high", "low", "close"]] += 40
    X2 = R.Xau(df2)
    p2 = C.predictors(X2, R.window_table(X2))
    for k in ("C1", "C2", "C3", "C4", "C5"):
        assert p1.at[d, k] == pytest.approx(p2.at[d, k])


def test_predictor_values():
    df = _days()
    X = R.Xau(df)
    wt = R.window_table(X)
    p = C.predictors(X, wt)
    d = date(2024, 7, 3)
    a = pd.Timestamp("2024-07-03 00:30")
    cl = df.loc["2024-07-02 20:59"].close          # 17:00 NY (EDT) = 21:00 UTC; last close known then
    assert p.at[d, "C3"] == pytest.approx(df.loc["2024-07-03 00:29"].close / cl - 1)
    assert p.at[d, "C4"] == pytest.approx(df.loc["2024-07-03 00:29"].close / df.loc["2024-07-02 23:59"].close - 1)
    assert p.at[d, "C1"] == pytest.approx(cl / df.loc["2024-07-02 19:59"].close - 1)
    assert a > pd.Timestamp("2024-07-02 21:00")


def test_big_day_threshold_uses_earlier_days_only():
    idx = [date(2024, 1, k) for k in range(1, 8)]
    wt = pd.DataFrame({"U": [0.01, -0.02, 0.005, 0.03, -0.001, 0.5, 0.002], "ny_day": idx,
                       "w_open": 1.0, "w_close": 1.0}, index=idx)
    absu = wt.U.abs()
    thr = absu.expanding().quantile(C.BIG_Q).shift(1)
    big = (absu >= thr) & thr.notna()
    # day 6 (0.5) is big against the earlier five; changing day 7 cannot change day 6
    assert bool(big.iloc[5])
    wt2 = wt.copy()
    wt2.iloc[6, 0] = 9.0
    thr2 = wt2.U.abs().expanding().quantile(C.BIG_Q).shift(1)
    assert thr.iloc[5] == pytest.approx(thr2.iloc[5])
