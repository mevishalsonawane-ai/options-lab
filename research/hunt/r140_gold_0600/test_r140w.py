"""R140 Amendment K tests: the IST window filter (both windows, edges) and the rule table."""
from __future__ import annotations

import pandas as pd

from research.hunt.r140_gold_0600 import r140w as W


def test_ist_minute_and_windows():
    assert W.ist_minute(pd.Timestamp("2024-07-02 00:30")) == 6 * 60          # 06:00 IST
    s = [{"t": pd.Timestamp(x)} for x in ("2024-07-02 00:29", "2024-07-02 00:30", "2024-07-02 02:29",
                                           "2024-07-02 02:30", "2024-07-02 10:30", "2024-07-02 12:29", "2024-07-02 12:30")]
    assert [x["t"].strftime("%H:%M") for x in W.in_window(s, "am")] == ["00:30", "02:29"]
    assert [x["t"].strftime("%H:%M") for x in W.in_window(s, "pm")] == ["10:30", "12:29"]


def test_rules_table():
    assert len(W.RULES) == 10 and W.RULES["W1"] == ("F2", "am") and W.RULES["W10"] == ("J2", "pm")
