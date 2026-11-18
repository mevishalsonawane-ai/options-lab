"""R199 tests on hand-made sessions: the index stop fills at the next minute, a stop on a session's last minute fills at
the next session's first minute, the premium stop, the calm filter and the period guard."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling", HERE.parent / "r198_far_otm_expiry"):
    sys.path.insert(0, str(p))
import r195rules as RU  # noqa: E402
import r199study as S9  # noqa: E402
from test_r195 import make_day  # noqa: E402


@pytest.fixture
def fake(monkeypatch):
    days = [date(2025, 3, 3) + timedelta(days=i) for i in range(4)]
    store = {d: make_day(d=d) for d in days}
    store[days[-1]].expiry = True
    Dl = RU.Daily(days, {d: i for i, d in enumerate(days)}, *[np.full(4, 22000.0)] * 4, np.full(4, 14.0))
    exp = {d: days[-1] for d in days}
    monkeypatch.setattr(S9.S5, "setup", lambda und: (store, Dl, exp))
    return store, days


def test_no_stop_runs_to_expiry_exit(fake):
    store, days = fake
    t = S9.run_carry("H1-NIFTY", days)
    assert t["exit_day"] == days[-1] and t["why"] == "time"


def test_index_stop_next_minute(fake):
    store, days = fake
    store[days[-1]].idx[3, 100:] = 22300.0                                 # above K0 + 5 steps (22250) at minute 100
    t = S9.run_carry("H1-NIFTY", days)
    assert t["why"] == "stop" and t["exit_day"] == days[-1]


def test_stop_on_last_minute_fills_next_session(fake, monkeypatch):
    store, days = fake
    store[days[-2]].idx[3, S9.LAST] = 22300.0                              # the eve's last minute breaks the strike
    seen = {}
    real = S9.S8.exit_fill

    def spy(store_, cyc, k, leg, r, expiry, q, mult):
        seen["k"], seen["r"] = k, r
        return real(store_, cyc, k, leg, r, expiry, q, mult)
    monkeypatch.setattr(S9.S8, "exit_fill", spy)
    t = S9.run_carry("H1-NIFTY", days)
    assert t["why"] == "stop" and seen == {"k": len(days) - 1, "r": 0}


def test_premium_stop(fake):
    store, days = fake
    for k in (("C", 22250.0), ("P", 21750.0)):
        store[days[-1]].con[k][:, 50:] = 300.0                             # both shorts explode on expiry day
    t = S9.run_carry("H11-NIFTY", days)
    assert t["why"] == "stop"


def test_calm_filter_and_period_guard(fake):
    store, days = fake
    store[days[-2]].idx[3, S9.R1515] = 22300.0                             # the eve moved +1.4%: not calm
    assert S9.run_carry("H5-NIFTY", days) is None
    assert S9.run_carry("H1-NIFTY", [date(2025, 12, 30), date(2025, 12, 31), date(2026, 1, 1)]) is None
