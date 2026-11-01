"""R140 Amendment J tests (Boss's Internal SMC BOS & HL/LH sweep): the first break is a silent CHoCH (no setup), the
second same-way break arms the HL sweep; a wick below the HL with a close back above fires a BUY (SL at the wick, TP
2R); a close below the HL disarms; the SELL mirror; no look-ahead."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140j as J


def candles(rows):
    idx = pd.date_range("2024-07-01", periods=len(rows), freq="15min")
    return pd.DataFrame(rows, columns=["open", "high", "low", "close"], index=idx)


def zig(points, steps=4):
    """Candles walking linearly through the given turning points (small bodies, 0.3 wicks)."""
    rows, prev = [], points[0]
    for p in points[1:]:
        for k in range(1, steps + 1):
            x = prev + (p - prev) * k / steps
            o = rows[-1][3] if rows else prev
            up = x > o
            rows.append((o, max(o, x) + (0.3 if up else 0.1), min(o, x) - (0.1 if up else 0.3), x))
        prev = p
    return rows


# up-trend: highs 105, 110 (CHoCH break), 115 (BOS break); higher lows 100, 104, 108
UP = zig([100, 105, 100, 110.5, 104, 112, 108, 116.5, 111])


def test_choch_then_bos_then_hl_sweep_buy():
    rows = UP + [(111, 111.5, 107.0, 109.0)]            # wick below the HL (~107.7) and close back above
    b = candles(rows)
    s = J.smc_signals(b, 15)
    buys = [x for x in s if x["side"] == 1]
    assert len(buys) == 1
    x = buys[0]
    assert x["sl"] == pytest.approx(107.0) and x["tp"] == pytest.approx(109.0 + 2 * 2.0)
    assert x["t"] == b.index[-1] + pd.Timedelta(minutes=15)


def test_close_below_hl_disarms():
    rows = UP + [(111, 111.5, 106.5, 107.0), (107.0, 109.5, 106.8, 109.0)]
    assert [x for x in J.smc_signals(candles(rows), 15) if x["side"] == 1] == []


def test_sell_mirror():
    m = 230.0
    rows = [(m - o, m - l, m - h, m - c) for o, h, l, c in UP + [(111, 111.5, 107.0, 109.0)]]
    s = [x for x in J.smc_signals(candles(rows), 15) if x["side"] == -1]
    assert len(s) == 1 and s[0]["sl"] == pytest.approx(m - 107.0)


def test_no_lookahead():
    rng = np.random.default_rng(21)
    c = 2000 + np.cumsum(rng.normal(0, 1, 900))
    o = np.concatenate([[c[0]], c[:-1]])
    b = candles(list(zip(o, np.maximum(o, c) + 0.6, np.minimum(o, c) - 0.6, c)))
    s1 = J.smc_signals(b, 15)
    b2 = b.copy()
    b2.iloc[650:] += 30
    cut = b.index[650]
    assert [x for x in s1 if x["t"] <= cut] == [x for x in J.smc_signals(b2, 15) if x["t"] <= cut]
