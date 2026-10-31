"""R140 Amendment E tests: IST hour -> UTC, the latest New York close (weekday, Monday, both DST regimes), the busy
flag using earlier days only, and the rule picking the vote's side."""
from __future__ import annotations

from datetime import date

import pandas as pd
import pytest

from research.hunt.r140_gold_0600 import r140h as H


def test_entry_utc():
    assert H.entry_utc(date(2024, 7, 2), 6) == pd.Timestamp("2024-07-02 00:30")
    assert H.entry_utc(date(2024, 7, 2), 0) == pd.Timestamp("2024-07-01 18:30")
    assert H.entry_utc(date(2024, 7, 2), 23) == pd.Timestamp("2024-07-02 17:30")


def test_last_ny_close():
    # Tue 2 Jul 2024 00:30 UTC (summer): Monday's 17:00 NY = 21:00 UTC
    assert H.last_ny_close(pd.Timestamp("2024-07-02 00:30")) == pd.Timestamp("2024-07-01 21:00")
    # Tue 2 Jul 2024 15:00 UTC (NY 11:00): still Monday's close
    assert H.last_ny_close(pd.Timestamp("2024-07-02 15:00")) == pd.Timestamp("2024-07-01 21:00")
    # exactly at the close counts
    assert H.last_ny_close(pd.Timestamp("2024-07-02 21:00")) == pd.Timestamp("2024-07-02 21:00")
    # Monday morning (winter): Friday's 17:00 NY = 22:00 UTC
    assert H.last_ny_close(pd.Timestamp("2024-01-15 03:00")) == pd.Timestamp("2024-01-12 22:00")


def test_busy_uses_earlier_days_only():
    rows = {date(2024, 1, k): {"atr": a} for k, a in zip(range(1, 6), [1.0, 3.0, 2.0, 5.0, 0.5])}
    f = H.busy(rows)
    assert f[date(2024, 1, 1)] is False                        # no earlier day
    assert f[date(2024, 1, 2)] is True and f[date(2024, 1, 5)] is False
    rows2 = dict(rows)
    rows2[date(2024, 1, 5)] = {"atr": 99.0}
    assert H.busy(rows2)[date(2024, 1, 4)] == f[date(2024, 1, 4)]


def test_rule_trades_follow_the_vote():
    mk = lambda d, v: {"vote": v, "long": {"side": 1, "day": d}, "short": {"side": -1, "day": d}}
    rows = {date(2024, 1, 2): mk(date(2024, 1, 2), 1), date(2024, 1, 3): mk(date(2024, 1, 3), -1),
            date(2024, 1, 4): mk(date(2024, 1, 4), 0)}
    t = H.rule_trades(rows, {date(2024, 1, 2): False, date(2024, 1, 3): True}, (date(2024, 1, 1), date(2024, 2, 1)), False)
    assert [x["side"] for x in t] == [1, -1]
    tb = H.rule_trades(rows, {date(2024, 1, 2): False, date(2024, 1, 3): True}, (date(2024, 1, 1), date(2024, 2, 1)), True)
    assert [x["side"] for x in tb] == [-1]
