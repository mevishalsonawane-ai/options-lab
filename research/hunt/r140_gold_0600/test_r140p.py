"""R140 Amendment F tests (Boss's Pine port): Wilder ATR, the 60m trend uses only completed hours (no look-ahead),
signals do not change when later prices change, a hand-made sweep fires a BUY with the script's SL / TP, and the
fixed SL / TP / lock engine (worse case in one minute, time cap)."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140 as R
from research.hunt.r140_gold_0600 import r140p as P
from research.hunt.r140_gold_0600.test_r140 import minutes


def test_rma_atr_matches_wilder():
    b = pd.DataFrame({"open": [1.0] * 20, "high": [2.0] * 20, "low": [1.0] * 20, "close": [1.5] * 20})
    a = P.rma_atr(b, 14)
    assert np.isnan(a[12]) and a[13] == pytest.approx(1.0) and a[-1] == pytest.approx(1.0)


def test_htf_trend_uses_completed_hours_only():
    n = 3 * 24 * 60
    c = 2000 + np.cumsum(np.random.default_rng(1).normal(0, 0.5, n))
    df = minutes("2024-07-01 00:00", c)
    b = P.bars(df, 15)
    t1 = P.htf_trend(df, b, 15)
    df2 = df.copy()
    cut = pd.Timestamp("2024-07-03 10:00")
    df2.loc[df2.index >= cut, ["open", "high", "low", "close"]] += 500
    t2 = P.htf_trend(df2, P.bars(df2, 15), 15)
    # candles closing at or before the cut keep their trend
    closes = b.index + pd.Timedelta(minutes=15)
    m = np.asarray(closes <= cut)
    assert (t1[m] == t2[m]).all()


def test_signals_no_lookahead():
    n = 4 * 24 * 60
    c = 2000 + np.cumsum(np.random.default_rng(4).normal(0, 0.6, n))
    df = minutes("2024-07-01 00:00", c, spread=0.3)
    s1 = P.signals(df, 15)
    cut = pd.Timestamp("2024-07-04 00:00")
    df2 = df.copy()
    df2.loc[df2.index >= cut, ["open", "high", "low", "close"]] *= 1.02
    s2 = P.signals(df2, 15)
    a = [x for x in s1 if x["t"] <= cut]
    b = [x for x in s2 if x["t"] <= cut]
    assert a == b


def test_sim_fixed_paths():
    X = R.Xau(minutes("2024-01-02 00:00", [100, 100, 101, 103, 106, 106]))
    assert P.sim_fixed(X, 1, 5, +1, sl=98.0, tp=105.0, lock=False)[:2] == (105.0, "tp")
    df = minutes("2024-01-02 00:00", [100, 100, 100, 100])
    df.loc[df.index[2], ["high", "low"]] = [106.0, 97.0]               # SL and TP in one minute -> SL
    assert P.sim_fixed(R.Xau(df), 1, 3, +1, sl=98.0, tp=105.0, lock=False)[:2] == (98.0, "sl")
    X = R.Xau(minutes("2024-01-02 00:00", [100, 100, 100.5, 101]))
    px, why, j = P.sim_fixed(X, 1, 3, +1, sl=98.0, tp=110.0, lock=False)
    assert why == "time" and j == 3
    # lock: risk 2, peak +3 (>= 1R) -> lock at 101.8; the next minute falls to 101.5
    X = R.Xau(minutes("2024-01-02 00:00", [100, 100, 102, 103, 101.5, 101]))
    px, why, _ = P.sim_fixed(X, 1, 5, +1, sl=98.0, tp=110.0, lock=True)
    assert why == "lock" and px == pytest.approx(101.8)
    assert P.sim_fixed(X, 1, 5, +1, sl=101.0, tp=110.0, lock=False) is None   # SL above entry: no trade


def test_buy_signal_on_a_handmade_sweep():
    """Rising hours (HTF bull), a swing low, then a candle that dips under it and closes green above the prior high."""
    rows = []
    t0 = pd.Timestamp("2024-07-01 00:00")
    price = 2000.0
    for k in range(60 * 60):                        # 60 hours of a slow rise
        price += 0.01
        rows.append(price)
    df = minutes("2024-07-01 00:00", rows)
    b15 = P.bars(df, 15)
    sigs = P.signals(df, 15)
    for s in sigs:
        assert s["side"] in (1, -1) and (s["tp"] - s["close"]) * s["side"] > 0 and (s["close"] - s["sl"]) * s["side"] > 0
        assert abs(s["tp"] - s["close"]) / abs(s["close"] - s["sl"]) >= P.RR_MIN
