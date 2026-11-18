"""R200 tests on hand-made days: wings from the straddle, the stop inside max loss, the target, the expiry-day switch."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling"):
    sys.path.insert(0, str(p))
import r195rules as RU  # noqa: E402
import r200study as S0  # noqa: E402
from test_r195 import EXP, make_day  # noqa: E402


@pytest.fixture
def day(monkeypatch):
    D = make_day()
    store = {D.d: D}
    Dl = RU.Daily([D.d], {D.d: 0}, *[np.full(1, 22000.0)] * 4, np.full(1, 14.0))
    monkeypatch.setattr(S0.S5, "setup", lambda und: (store, Dl, {D.d: EXP}))
    return D


def test_wings_and_time_exit(day):
    t = S0.fly_day("IF1-NIFTY", day.d)
    ks = sorted(k for _, k, _ in t["legs"])
    assert ks == [21800.0, 22000.0, 22000.0, 22200.0]                       # straddle 200 -> wings 4 steps
    assert t["why"] == "time" and S0.fly_day("IF3-NIFTY", day.d) is None     # IF3 trades expiry days only


def test_stop_inside_max_loss(day):
    day.con[("C", 22000.0)][:, 80:] = 260.0                                  # short call +160: loss ~ 0.5 x max loss
    t = S0.fly_day("IF1-NIFTY", day.d)
    assert t["why"] == "stop" and t["net"] < 0


def test_target(day):
    for k in (("C", 22000.0), ("P", 22000.0)):
        day.con[k][:, 60:] = 60.0                                            # shorts decay 40%
    t = S0.fly_day("IF2-NIFTY", day.d)
    assert t["why"] == "target" and t["net"] > 0
