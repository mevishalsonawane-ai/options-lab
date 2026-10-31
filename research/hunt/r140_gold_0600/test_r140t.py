"""R140 Amendment I tests (break and retest): a hand-built break of a swing low then a retest into the zone fires a
SELL with SL above the zone and TP at 2R; a close back above the zone cancels; the BUY mirror; no look-ahead."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140p as P
from research.hunt.r140_gold_0600 import r140t as T


def candles(rows):
    idx = pd.date_range("2024-07-01", periods=len(rows), freq="15min")
    return pd.DataFrame(rows, columns=["open", "high", "low", "close"], index=idx)


BASE = [(100 + (k % 3) * 0.1, 101.0 + (k % 3) * 0.1, 99.0 + (k % 3) * 0.1, 100 + (k % 3) * 0.1) for k in range(16)]
# swing low 95 at bar 18, confirmed at bar 21; break close 92 at bar 23; retest high 95.2, close 94.4 at bar 26
SEQ = BASE + [(100, 100.5, 97, 97.5), (97.5, 98, 96, 96.5), (96.5, 97, 95, 96), (96, 97.5, 95.5, 97),
              (97, 98, 96.5, 97.5), (97.5, 98, 96.8, 97.2), (97.2, 97.5, 94, 94.5), (94.5, 95, 91.5, 92),
              (92, 93, 91, 92.5), (92.5, 94, 92, 93.5), (93.5, 95.2, 93, 94.4), (94.4, 94.6, 92, 92.2)]


def test_sell_retest():
    b = candles(SEQ)
    s = T.retests(b, 15)
    sells = [x for x in s if x["side"] == -1]
    assert len(sells) == 1
    x = sells[0]
    atr = P.rma_atr(b)[26]
    assert x["t"] == b.index[26] + pd.Timedelta(minutes=15)
    assert x["sl"] > 95 and x["tp"] == pytest.approx(94.4 - 2 * (x["sl"] - 94.4))
    assert x["sl"] == pytest.approx(95 + T.ZONE_HALF * P.rma_atr(b)[23] + T.SL_PAD * atr)


def test_reclaim_cancels():
    rows = SEQ[:25] + [(93.5, 97.5, 93, 97.4), (97.4, 97.6, 94.8, 94.9)]
    assert [x for x in T.retests(candles(rows), 15) if x["side"] == -1] == []


def test_buy_mirror():
    m = 200.0
    rows = [(m - o, m - l, m - h, m - c) for o, h, l, c in SEQ]
    s = [x for x in T.retests(candles(rows), 15) if x["side"] == 1]
    assert len(s) == 1 and s[0]["sl"] < m - 95 and s[0]["tp"] > s[0]["close"]


def test_no_lookahead():
    rng = np.random.default_rng(12)
    c = 2000 + np.cumsum(rng.normal(0, 1, 900))
    o = np.concatenate([[c[0]], c[:-1]])
    b = candles(list(zip(o, np.maximum(o, c) + 0.6, np.minimum(o, c) - 0.6, c)))
    s1 = T.retests(b, 15)
    b2 = b.copy()
    b2.iloc[700:] -= 40
    cut = b.index[700]
    assert [x for x in s1 if x["t"] <= cut] == [x for x in T.retests(b2, 15) if x["t"] <= cut]
