"""R140 Amendment M tests (Boss's 6:30 AM IST breakout): the range is the candle opening at 06:30 IST; the first close
beyond it fires once a day (long first); SL = the previous candle's low / high, TP = 2R; the cross rule (open or the
previous close inside the range); the next-06:30 time exit; no look-ahead."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140s as S


def hourly(rows, start="2024-07-01 23:00"):
    """1H candles; start 23:00 UTC = 04:30 IST, so the 3rd candle (01:00 UTC) opens at 06:30 IST."""
    idx = pd.date_range(start, periods=len(rows), freq="60min")
    return pd.DataFrame(rows, columns=["open", "high", "low", "close"], index=idx)


ROWS = [(100, 101, 99, 100), (100, 101, 99.5, 100.5),       # 04:30, 05:30 IST
        (100.5, 102, 99, 101),                               # 06:30 IST range: H 102, L 99
        (101, 101.8, 100.2, 101.5),                          # inside
        (101.5, 103, 101, 102.6),                            # close 102.6 > 102, open inside -> BUY; SL = prev low 100.2
        (102.6, 98, 97, 97.5)]                               # a later close below the low: no 2nd trade that day


def test_buy_breakout_once_a_day():
    b = hourly(ROWS)
    s = S.breakout_signals(b, 60)
    assert len(s) == 1
    x = s[0]
    assert x["side"] == 1 and x["sl"] == pytest.approx(100.2) and x["tp"] == pytest.approx(102.6 + 2 * (102.6 - 100.2))
    assert x["t"] == b.index[4] + pd.Timedelta(minutes=60)


def test_short_and_cross_rule():
    rows = ROWS[:4] + [(101.5, 101.6, 98.5, 98.8)]          # close below 99, open inside -> SELL; SL = prev high 101.8
    s = S.breakout_signals(hourly(rows), 60)
    assert len(s) == 1 and s[0]["side"] == -1 and s[0]["sl"] == pytest.approx(101.8)
    # a candle that opens AND closes above the range, after a close above it: not a fresh cross
    rows2 = ROWS[:3] + [(101, 103, 100.8, 102.5)] + [(102.5, 104, 102.2, 103.5)]
    s2 = S.breakout_signals(hourly(rows2), 60)
    assert len(s2) == 1 and s2[0]["t"] == hourly(rows2).index[3] + pd.Timedelta(minutes=60)


def test_range_candle_itself_never_trades_and_new_day_resets():
    rows = ROWS + [(97.5, 98, 97, 97.6)] * 20 + [(97.6, 99, 97.2, 98.5), (98.5, 99.4, 98.4, 99.3)]
    b = hourly(rows)
    assert (b.index[26] + S.IST).strftime("%H:%M") == "06:30"          # the next day's range candle
    s = S.breakout_signals(b, 60)
    assert len(s) == 2                                                 # day 1 BUY, day 2 BUY (new range 97.2-99)
    assert s[1]["side"] == 1 and s[1]["t"] == b.index[27] + pd.Timedelta(minutes=60)
    assert all(((x["t"] - pd.Timedelta(minutes=60)) + S.IST).strftime("%H:%M") != "06:30" for x in s)


def test_next_range_open():
    assert S.next_range_open(pd.Timestamp("2024-07-02 03:00")) == pd.Timestamp("2024-07-03 01:00")
    assert S.next_range_open(pd.Timestamp("2024-07-02 00:30")) == pd.Timestamp("2024-07-02 01:00")
    assert S.next_range_open(pd.Timestamp("2024-07-02 01:00")) == pd.Timestamp("2024-07-03 01:00")


def test_no_lookahead():
    rng = np.random.default_rng(31)
    c = 2000 + np.cumsum(rng.normal(0, 2, 600))
    o = np.concatenate([[c[0]], c[:-1]])
    b = hourly(list(zip(o, np.maximum(o, c) + 1, np.minimum(o, c) - 1, c)))
    s1 = S.breakout_signals(b, 60)
    b2 = b.copy()
    b2.iloc[400:] += 50
    cut = b.index[400]
    assert [x for x in s1 if x["t"] <= cut] == [x for x in S.breakout_signals(b2, 60) if x["t"] <= cut]
