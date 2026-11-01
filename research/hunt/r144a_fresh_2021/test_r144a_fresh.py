"""Tests for R144a's fresh-year test (PREREG.md): the frozen rules, the gate and the periods. No lake needed."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r144a_fresh as T  # noqa: E402


def test_periods_do_not_touch_used_months():
    assert T.FRESH == (date(2021, 11, 1), date(2022, 11, 30))
    assert T.FRESH[1] < date(2022, 12, 1)                       # R143's unseen years start 1 Dec 2022
    assert T.CHECK[0] > T.FRESH[1]                              # the engine check is outside the test
    a, b = T.HALVES["Nov21-May22"], T.HALVES["Jun-Nov22"]
    assert a[0] == T.FRESH[0] and b[1] == T.FRESH[1] and (b[0] - a[1]).days == 1
    assert T.STORE["fresh"][0] < T.FRESH[0] <= T.STORE["fresh"][1]   # warm-up before the first trade


def test_frozen_rules():
    assert T.drops("A10", dict(A10=1.3206), "R72", ) and not T.drops("A10", dict(A10=1.32), "R72")
    assert T.drops("V4", dict(V4=1.4), "R72") and not T.drops("V4", dict(V4=1.3), "R72")
    assert T.drops("F4", dict(F4="covering against"), "R51") and not T.drops("F4", dict(F4="covering with"), "R51")
    assert T.drops("BBX", dict(A11=1.0, A1=9.0, A7=9.0), "R51")
    assert T.drops("BBX", dict(A11=0.0, A1=1.7, A7=9.0), "R72") and not T.drops("BBX", dict(A11=0.0, A1=1.7, A7=9.0),
                                                                                "R51")
    assert not T.drops("BBX", dict(A11=0.0, A1=9.0, A7=9.0), "R51")


def test_unknown_never_drops():
    for rule in ("A10", "V4", "F4", "BBX"):
        assert not T.drops(rule, {}, "R72")
        assert not T.drops(rule, dict(A10=np.nan, V4=np.nan, F4=None, A1=np.nan, A7=np.nan, A11=np.nan), "R51")


def test_gate_profit_rule_and_counts():
    base = dict(loss_sum=-1000, max_dd=500, worst_day=-300, worst_month=-600, net=-100, trades=200)
    good = dict(base, loss_sum=-800, net=-50, trades=150)
    assert T.gate(good, base, 0.01, 0.05, 40) == []
    assert "no longer makes money" in T.gate(dict(good, net=-1), dict(base, net=10), 0.01, 0.05, 40)
    assert T.gate(good, base, 0.06, 0.05, 40) and T.gate(good, base, 0.01, 0.2, 40)
    assert T.gate(good, base, 0.01, 0.05, 29) and T.gate(dict(good, trades=39), base, 0.01, 0.05, 40)
    assert T.gate(dict(good, worst_month=-700), base, 0.01, 0.05, 40)


def test_trade_set_is_order_free():
    a = dict(und="NIFTY", day=date(2022, 1, 3), er_sig=5, side="C", net=10.004)
    b = dict(und="BANKNIFTY", day=date(2022, 1, 3), er_sig=10, side="P", net=-5.0)
    assert T.trade_set([a, b]) == T.trade_set([b, a])
