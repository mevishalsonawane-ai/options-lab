"""R140 Amendment L tests: merging Boss's setups (duplicates once), the reverse transform (opposite side, SL at the
same risk on the other side, TP 2R), the high win-rate TP at 1R, the 11-13 IST window, and the busy median using
earlier candles only."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140l as L


def sig(t, side, close, sl, tp):
    return dict(t=pd.Timestamp(t), side=side, close=close, sl=sl, tp=tp)


def test_combined_dedupes_and_sorts():
    base = {k: [] for k in L.ORDER}
    base["F2"] = [sig("2024-07-02 06:00", 1, 100, 98, 104)]
    base["J2"] = [sig("2024-07-02 06:00", 1, 100, 99, 102), sig("2024-07-02 05:00", -1, 100, 101, 98)]
    c = L.combined(base)
    assert [(x["t"].hour, x["side"], x["src"]) for x in c] == [(5, -1, "J2"), (6, 1, "F2")]


def test_reverse_and_high_win_transform():
    s = [sig("2024-07-02 06:00", 1, 100.0, 98.0, 104.0)]                 # 06:00 UTC = 11:30 IST
    r = L.transform(s, None, None, (0, 0, 0, 1, 0))[0]
    assert r["side"] == -1 and r["sl"] == pytest.approx(102.0) and r["tp"] == pytest.approx(96.0)
    h = L.transform(s, None, None, (0, 0, 1, 0, 0))[0]
    assert h["side"] == 1 and h["tp"] == pytest.approx(102.0) and h["sl"] == 98.0
    assert len(L.transform(s, None, None, (0, 0, 0, 0, 1))) == 1          # 11:30 IST is inside 11-13
    s2 = [sig("2024-07-02 08:00", 1, 100.0, 98.0, 104.0)]                # 13:30 IST
    assert L.transform(s2, None, None, (0, 0, 0, 0, 1)) == []


def test_busy_uses_earlier_candles_only():
    idx = pd.date_range("2024-07-01 00:00", periods=200, freq="15min")
    ind = pd.DataFrame({"atr14": np.linspace(1, 3, 200), "ema_spread": 1.0, "rsi": 60.0, "macd_hist": 0.1}, index=idx)
    c1 = L.context(None, ind)
    ind2 = ind.copy()
    ind2.iloc[150:, 0] = 100.0
    c2 = L.context(None, ind2)
    assert (c1.busy.iloc[:150] == c2.busy.iloc[:150]).all()
    assert not c1.busy.iloc[0]
