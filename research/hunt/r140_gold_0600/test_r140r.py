"""R140 recent-look tests: 0.01-lot scaling and the $200 account maths (empty list, all wins, drawdown)."""
from __future__ import annotations

from datetime import date

import pytest

from research.hunt.r140_gold_0600 import r140r as RR


def _t(net, day=1):
    return dict(day=date(2026, 9, day), net=net, net2=net - 32.0, gross=net + 32.0, why="lock", side=1)


def test_scale_to_001_lot():
    s = RR.scale([_t(500.0)])
    assert s[0]["net"] == pytest.approx(5.0) and s[0]["net2"] == pytest.approx(4.68)


def test_account_maths():
    a = RR.account([dict(_t(0), net=5.0, net2=4.0), dict(_t(0), net=-8.0, net2=-9.0), dict(_t(0), net=2.0, net2=1.0)])
    assert a["trades"] == 3 and a["wins"] == 2 and a["total"] == pytest.approx(-1.0)
    assert a["end"] == pytest.approx(199.0) and a["max_dd"] == pytest.approx(-8.0)
    assert a["avg_loss"] == pytest.approx(-8.0) and a["total_2x"] == pytest.approx(-4.0)


def test_account_empty_and_all_wins():
    e = RR.account([])
    assert e["trades"] == 0 and e["end"] == 200.0 and e["max_dd"] == 0.0
    w = RR.account([dict(_t(0), net=1.0, net2=0.5)] * 3)
    assert w["win"] == 1.0 and w["max_dd"] == 0.0 and w["avg_loss"] == 0.0
