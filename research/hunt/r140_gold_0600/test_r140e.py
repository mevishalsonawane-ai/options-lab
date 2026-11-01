"""R140 Amendment B tests: indicators (RSI bounds, EMA spread sign, Supertrend flips), features taken from the candle
completed by 06:00 IST only (look-ahead guard), the vote, the target exits (worse-case ordering), and the walk-forward
model trained only on earlier days."""
from __future__ import annotations

from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140e as E
from research.hunt.r140_gold_0600.test_r140 import minutes


def _bars(closes):
    idx = pd.date_range("2024-07-01", periods=len(closes), freq="15min")
    c = pd.Series(closes, index=idx, dtype=float)
    return pd.DataFrame({"open": c.shift(1).fillna(c.iloc[0]), "high": c + 0.5, "low": c - 0.5, "close": c})


def test_rsi_bounds_and_direction():
    up = E.rsi(pd.Series(np.arange(100, 160, dtype=float)))
    dn = E.rsi(pd.Series(np.arange(160, 100, -1, dtype=float)))
    assert up.iloc[-1] > 90 and dn.iloc[-1] < 10
    r = E.rsi(pd.Series(np.random.default_rng(1).normal(0, 1, 500).cumsum() + 100)).dropna()
    assert ((r >= 0) & (r <= 100)).all()


def test_supertrend_flips_with_trend():
    c = list(np.linspace(100, 130, 60)) + list(np.linspace(130, 90, 60))
    st = E.supertrend_dir(_bars(c))
    assert st.iloc[55] == 1 and st.iloc[-1] == -1


def test_indicators_and_vote():
    b = _bars(list(np.linspace(100, 140, 120)))
    ind = E.indicators(b)
    assert ind.ema_spread.iloc[-1] > 0 and ind.macd_hist.iloc[-60:].abs().max() < 5
    f = pd.DataFrame({"ema_spread": [1.0, 1.0, -1.0], "rsi": [60, 60, 40], "macd_hist": [0.1, -0.1, -0.2],
                      "C3": [0.001, 0.001, -0.002], "bb_z": [0, 2.5, -2.5], "st": [1, 1, -1]},
                     index=[date(2024, 1, 2), date(2024, 1, 3), date(2024, 1, 4)])
    s = E.e_signals(f)
    assert list(s.E5) == [1, 0, -1]
    assert list(s.E3) == [0, -1, 1]


def test_day_features_use_candle_completed_by_0600():
    """Changing prices from 06:00 IST on must not change that day's features."""
    n = 3 * 24 * 60
    c = 2300 + np.cumsum(np.random.default_rng(5).normal(0, 0.3, n))
    df = minutes("2024-07-01 00:00", c)
    X = R.Xau(df)
    wt = R.window_table(X)
    pr = E.C.predictors(X, wt)
    f1 = E.day_features(E.indicators(X.bars15()), wt, pr)
    df2 = df.copy()
    df2.loc[df2.index >= pd.Timestamp("2024-07-03 00:30"), ["open", "high", "low", "close"]] += 30
    X2 = R.Xau(df2)
    wt2 = R.window_table(X2)
    f2 = E.day_features(E.indicators(X2.bars15()), wt2, E.C.predictors(X2, wt2))
    d = date(2024, 7, 3)
    for k in ("ema_spread", "rsi", "macd_hist", "bb_z", "st", "atr14"):
        assert f1.at[d, k] == pytest.approx(f2.at[d, k])


def test_target_exit_and_worse_case():
    X = R.Xau(minutes("2024-01-02 00:00", [100, 100, 101, 102.5, 103, 103]))
    px, why = E.sim_xau_t(X, 1, 5, +1, stop=3.0, trig=5.0, keep=0.6, target=2.0)
    assert why == "target" and px == pytest.approx(102.0)
    df = minutes("2024-01-02 00:00", [100, 100, 100, 100])
    df.loc[df.index[2], ["high", "low"]] = [103.0, 96.0]          # one minute touches both stop and target
    px, why = E.sim_xau_t(R.Xau(df), 1, 3, +1, stop=3.0, trig=5.0, keep=0.6, target=2.0)
    assert why == "stop" and px == pytest.approx(97.0)
    F = np.array([1000, 1000.5, 1001.6, 1003])
    assert E.sim_close_t(F, 0, 3, +1, 0.003, 0.002, 0.6, 0.0015) == (1001.6, "target")
    assert E.sim_close_t(np.array([1000, 997.0]), 0, 1, +1, 0.002, 0.001, 0.6, 0.0015) == (997.0, "stop")


def test_wf_logit_trains_on_earlier_days_only():
    days = [date(2024, 1, 1) + timedelta(days=k) for k in range(120)]
    rng = np.random.default_rng(0)
    x = rng.normal(0, 1, 120)
    y = np.where(np.arange(120) < 80, np.sign(x), -np.sign(x))     # the rule flips after day 80
    Xd = pd.DataFrame({"a": x}, index=days)
    ys = pd.Series(y, index=days)
    cut = (days[80],)
    p = E.wf_logit(Xd, ys, lambda d: cut, days[80:])
    # trained on the first 80 days only: it predicts the OLD relation (high p when x > 0)
    hi = [d for d in days[80:] if Xd.at[d, "a"] > 1]
    assert all(p[d] > 0.5 for d in hi)
    ys2 = ys.copy()
    ys2.iloc[80:] = 1.0
    p2 = E.wf_logit(Xd, ys2, lambda d: cut, days[80:])
    pd.testing.assert_series_equal(p, p2)


def test_prob_to_side():
    s = E.prob_to_side(pd.Series([0.6, 0.5, 0.4, 0.58, 0.42]))
    assert list(s) == [1, 0, -1, 1, -1]
