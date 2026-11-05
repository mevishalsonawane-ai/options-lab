"""R148 tests (synthetic only)."""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r148_night_enhance import study as S

NAN = np.nan


def test_stop_same_day_first():
    assert S.exit_price(100, 130, (60, 61, 59, 60, 79), 0.2, 0.4) == (80.0, True, "stop_same_day")


def test_stop_gap_through_fills_at_open():
    assert S.exit_price(100, 70, (70, 72, 68, 71, 95), 0.2, None) == (70, True, "stop_gap_through")


def test_target_at_open_and_at_close_not_high():
    assert S.exit_price(100, 150, (150, 155, 149, 152, 95), None, 0.4) == (150, False, "target_at_open")
    assert S.exit_price(100, 141, (120, 160, 119, 141, 95), None, 0.4) == (140.0, False, "target_0915")
    # the high touched the target but the close did not: the high is not trusted -> 09:16 sale
    assert S.exit_price(100, 125, (120, 160, 119, 130, 95), None, 0.4) == (125, False, "x16")


def test_stop_in_0915_bar_and_plain_exit():
    assert S.exit_price(100, 90, (95, 96, 79, 90, 95), 0.2, 0.4) == (80.0, True, "stop_0915")
    assert S.exit_price(100, 105, (101, 103, 99, 102, NAN), 0.2, 0.4) == (105, False, "x16")


def test_cap_keeps_strongest():
    b = pd.DataFrame({"d": [date(2024, 1, 2)] * 3 + [date(2024, 1, 3)] * 2, "strength": [0.2, 0.4, 0.3, 0.1, 0.2]})
    assert list(S.cap(b, 2)) == [False, True, True, True, True]
    assert list(S.cap(b, 1)) == [False, True, False, True, True]     # 2-trade nights are never capped


def test_likely_big_nights():
    df = pd.DataFrame({"wkend": [True, False, False], "vix": [12.0, 16.0, NAN]})
    assert list(S.is_L(df)) == [True, True, False]
