"""R140 Amendment G tests: a hand-built BOS -> pullback -> sweep of the origin low fires a BUY with SL under the sweep
and TP at the impulse high; a close below the origin low cancels the strict setup; the simple mode allows a dip and a
recovery within 3 candles; the mirror SELL; and no look-ahead."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140k as K


def candles(rows):
    idx = pd.date_range("2024-07-01", periods=len(rows), freq="15min")
    return pd.DataFrame(rows, columns=["open", "high", "low", "close"], index=idx)


# swing high at bar 4 (H 110), pullback to an origin low (bar 7, L 103), BOS close 112 at bar 10, impulse to 120 (bar
# 11), pullback, then the sweep candle: low 102.5 < 103, close 104 > 103.
UP = [(100, 101, 99, 100), (100, 102, 99.5, 101), (101, 104, 100, 103), (103, 107, 102, 106), (106, 110, 105, 108),
      (108, 109, 106, 107), (107, 108, 104, 105), (105, 106, 103, 104), (104, 107, 103.5, 106), (106, 109, 105, 108),
      (108, 112.5, 107, 112), (112, 120, 111, 119), (119, 119.5, 112, 113), (113, 114, 106, 107), (107, 108, 104, 105),
      (105, 106, 102.5, 104), (104, 106, 103.8, 105.5)]


def test_strict_buy_on_origin_low_sweep():
    s = K.setups(candles(UP), 15, "strict")
    assert len(s) == 1
    x = s[0]
    assert x["side"] == 1 and x["sl"] == pytest.approx(102.5) and x["tp"] == pytest.approx(120.0)
    assert x["t"] == candles(UP).index[15] + pd.Timedelta(minutes=15)


def test_strict_cancelled_by_close_below():
    rows = UP[:15] + [(105, 106, 102.0, 102.8), (102.8, 104, 102.5, 103.6)]
    assert K.setups(candles(rows), 15, "strict") == []


def test_simple_allows_dip_then_recovery():
    rows = UP[:15] + [(105, 106, 102.0, 102.8), (102.8, 104, 102.5, 103.6), (103.6, 105, 103.4, 104.5)]
    s = K.setups(candles(rows), 15, "simple")
    assert len(s) == 1 and s[0]["sl"] == pytest.approx(102.0) and s[0]["side"] == 1


def test_sell_mirror():
    m = 240.0
    rows = [(m - o, m - l, m - h, m - c) for o, h, l, c in UP]
    s = K.setups(candles(rows), 15, "strict")
    assert len(s) == 1 and s[0]["side"] == -1
    assert s[0]["sl"] == pytest.approx(m - 102.5) and s[0]["tp"] == pytest.approx(m - 120.0)


def test_no_lookahead():
    rng = np.random.default_rng(9)
    c = 2000 + np.cumsum(rng.normal(0, 1, 800))
    o = np.concatenate([[c[0]], c[:-1]])
    h, l = np.maximum(o, c) + 0.5, np.minimum(o, c) - 0.5
    b = candles(list(zip(o, h, l, c)))
    s1 = K.setups(b, 15, "strict")
    b2 = b.copy()
    b2.iloc[600:] += 50
    s2 = K.setups(b2, 15, "strict")
    cut = b.index[600]
    assert [x for x in s1 if x["t"] <= cut] == [x for x in s2 if x["t"] <= cut]
