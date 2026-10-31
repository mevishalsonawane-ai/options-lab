"""R140 Amendment H tests (Boss's refined Pine): the lagged HTF trend is one completed hour behind Amendment F's, no
look-ahead in the signals, every signal respects its own SL < close < TP and RR >= 1.5, and the HTF switch."""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140p as P
from research.hunt.r140_gold_0600 import r140q as Q
from research.hunt.r140_gold_0600.test_r140 import minutes


def _df(seed=3, days=4, vol=0.6):
    c = 2000 + np.cumsum(np.random.default_rng(seed).normal(0, vol, days * 24 * 60))
    return minutes("2024-07-01 00:00", c, spread=0.3)


def test_lagged_htf_is_one_hour_behind():
    df = _df()
    b = P.bars(df, 15)
    a, z = P.htf_trend(df, b, 15), Q.htf_trend_lagged(df, b, 15)
    # every 4th 15m candle closes an hour; from then on the lagged value equals the plain value 4 candles earlier
    idx = [i for i in range(300, 380)]
    assert all(z[i] == a[i - 4] for i in idx)


def test_signals_consistent_and_no_lookahead():
    df = _df(seed=11, days=6, vol=0.8)
    s1 = Q.signals(df, 15, True)
    for s in s1:
        risk, reward = s["side"] * (s["close"] - s["sl"]), s["side"] * (s["tp"] - s["close"])
        assert risk > 0 and reward / risk >= P.RR_MIN - 1e-9
    cut = pd.Timestamp("2024-07-05 00:00")
    df2 = df.copy()
    df2.loc[df2.index >= cut, ["open", "high", "low", "close"]] *= 1.03
    s2 = Q.signals(df2, 15, True)
    assert [x for x in s1 if x["t"] <= cut] == [x for x in s2 if x["t"] <= cut]


def test_htf_off_allows_at_least_as_many_signals():
    df = _df(seed=5, days=6, vol=0.8)
    assert len(Q.signals(df, 15, False)) >= len(Q.signals(df, 15, True))
