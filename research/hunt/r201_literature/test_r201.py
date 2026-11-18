"""R201 tests on hand-made sessions: the overnight exit at the next 09:15 open, the expiry guards, the VIX-spike rules
(causal), the event list."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling"):
    sys.path.insert(0, str(p))
import r195rules as RU  # noqa: E402
import r201study as S1  # noqa: E402
from test_r195 import make_day  # noqa: E402


@pytest.fixture
def fake(monkeypatch):
    days = [date(2025, 3, 3) + timedelta(days=i) for i in range(10)]
    store = {d: make_day(d=d) for d in days}
    Dl = RU.Daily(days, {d: i for i, d in enumerate(days)}, *[np.full(10, 22000.0)] * 4, np.full(10, 14.0))
    exp = {d: d + timedelta(days=20) for d in days}
    vix = {d: np.full((4, 375), 14.0) for d in days}
    monkeypatch.setattr(S1.S5, "setup", lambda und: (store, Dl, exp))
    monkeypatch.setitem(S1.S5._G, "vix", vix)
    return store, days, vix


def test_overnight_exits_at_next_open(fake):
    store, days, _ = fake
    t = S1.trade("ON1-NIFTY", days[2])
    assert t["exits"] == [0] * 4 and t["why"] == "next" and len(t["legs"]) == 4
    assert sorted(k for _, k, _, _ in t["legs"]) == [21500.0, 22000.0, 22000.0, 22500.0]


def test_expiry_guards(fake):
    store, days, _ = fake
    store[days[3]].expiry = True
    assert S1.trade("ON1-NIFTY", days[2]) is None                          # next session is an expiry
    assert S1.trade("ON1-NIFTY", days[3]) is None                          # today is an expiry
    assert S1.trade("EV1-NIFTY", days[2]) is not None                      # EV1 may exit on an expiry day


def test_vix_spike_rules_are_causal(fake):
    store, days, vix = fake
    _, Dl, _ = S1.S5.setup("NIFTY")
    vix[days[7]][:] = 17.0                                                 # +21% vs five sessions before
    assert S1.vix_spike_eve(Dl, 8) and not S1.vix_spike_eve(Dl, 7)         # VX1 reads yesterday only
    assert S1.vix_spike_1515(Dl, 7)
    vix[days[8]][:] = 40.0                                                 # a later session must not matter
    assert S1.vix_spike_eve(Dl, 8)


def test_event_list():
    assert date(2024, 6, 4) in S1.EVENTS and date(2026, 2, 1) in S1.EVENTS and len(S1.RBI) == 33
