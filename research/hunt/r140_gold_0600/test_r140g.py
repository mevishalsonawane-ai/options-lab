"""R140 Amendment D tests: the 3-of-4 vote, risk sizing (1% / 2% with the 20x cap, 5x), the account maths, the
busy-day filter using earlier days only, and the later time exit."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140g as G
from research.hunt.r140_gold_0600.test_r140 import minutes


def test_votes_4_and_3():
    f = pd.DataFrame({"ema_spread": [1, 1, -1, 1], "rsi": [60, 60, 40, 40], "macd_hist": [1, 1, -1, -1],
                      "C3": [0.1, -0.1, -0.1, np.nan]}, index=list("abcd"))
    v = G.votes(f)
    assert list(v["4"]) == [1, 0, -1, 0]
    assert list(v["3"]) == [1, 1, -1, 0]           # d: 1 up, 2 down, 1 missing -> no 3-vote


def test_size_oz_modes_and_cap():
    assert G.size_oz("5x", 1000, 4000, 2.0) == pytest.approx(1.25)
    assert G.size_oz("risk1", 1000, 4000, 4.0) == pytest.approx(2.5)        # $10 risk / $4 stop
    assert G.size_oz("risk2", 1000, 4000, 0.5) == pytest.approx(5.0)        # 40 oz capped at 20x = 5 oz


def test_sized_account():
    tr = [dict(day=date(2026, 1, 2), entry=4000.0, stop_usd=4.0, net=400.0, net2=368.0),   # +$4/oz
          dict(day=date(2026, 2, 3), entry=4000.0, stop_usd=4.0, net=-400.0, net2=-432.0)]
    s = G.sized(tr, "risk1")
    # trade 1: 2.5 oz x +4 = +10 -> 1010; trade 2: 2.525 oz x -4 = -10.10 -> 999.90
    assert s["end"] == pytest.approx(999.90)
    assert s["max_dd_pct"] == pytest.approx(999.90 / 1010 - 1)
    assert s["months_green"] == "1/2"
    e = G.sized([], "risk2")
    assert e["end"] == 1000.0 and e["max_dd_pct"] == 0.0


def test_busy_flags_use_earlier_days_only():
    n = 4 * 24 * 60
    c = 2300 + np.cumsum(np.random.default_rng(2).normal(0, 0.3, n))
    df = minutes("2024-07-01 00:00", c)
    X = R.Xau(df)
    b = X.bars15()
    days = [date(2024, 7, 2), date(2024, 7, 3), date(2024, 7, 4)]
    f1 = G.busy_flags(X, b, days)
    df2 = df.copy()
    df2.loc[df2.index >= pd.Timestamp("2024-07-04 00:30"), ["high"]] += 100
    f2 = G.busy_flags(R.Xau(df2), R.Xau(df2).bars15(), days)
    assert not f1.iloc[0]                           # no earlier day -> not busy
    assert f1.iloc[1] == f2.iloc[1]                 # later prices cannot change an earlier flag


def test_trade_later_time_exit():
    n = 2 * 24 * 60
    c = 2300 + np.arange(n) * 0.001               # gentle up-drift, no stop / lock hit
    X = R.Xau(minutes("2024-07-01 00:00", c))
    t = G.trade(X, X.bars15(), date(2024, 7, 2), +1, stop=50.0, trig=50.0, keep=0.6, t_out_min=G.IST_1230)
    assert t["why"] == "time"
    assert t["gross"] == pytest.approx((X.df.loc["2024-07-02 07:00"].open - X.df.loc["2024-07-02 00:30"].open) * 100)
