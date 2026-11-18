"""R198 tests: cycles, intrinsic, settlement with exercise STT, the stale-exit fallback and the period guard, on
hand-made sessions (S5.setup is replaced; no lake needed)."""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
for p in (HERE, HERE.parent / "r195_option_selling"):
    sys.path.insert(0, str(p))
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r198study as S  # noqa: E402
from test_r195 import make_day  # noqa: E402


@pytest.fixture
def fake(monkeypatch):
    days = [date(2025, 3, 3) + timedelta(days=i) for i in range(10)]
    store = {d: make_day(d=d) for d in days}
    store[days[3]].expiry = store[days[8]].expiry = True
    Dl = RU.Daily(days, {d: i for i, d in enumerate(days)}, *[np.full(10, 22000.0)] * 4, np.full(10, 14.0))
    exp = {d: (days[3] if d <= days[3] else days[8]) for d in days}
    monkeypatch.setattr(S.S5, "setup", lambda und: (store, Dl, exp))
    return store, days


def test_cycles(fake):
    store, days = fake
    cys = S.cycles("NIFTY")
    assert cys == [days[4:9]]                                              # the first partial cycle is dropped


def test_settlement_pays_intrinsic_and_exercise_stt(fake):
    store, days = fake
    store[days[8]].idx[3, :] = 22400.0                                     # expiry close: calls 5 and 10 steps ITM?
    t = S.trade("F3-NIFTY", days[4:9])
    legs = {(s, k): x for (s, k, _), x in zip(t["legs"], t["exitpx"])}
    assert legs[("C", 22250.0)] == pytest.approx(150.0) and legs[("C", 22500.0)] == 0.0
    assert legs[("P", 21750.0)] == 0.0
    store[days[8]].idx[3, :] = 22600.0                                     # the long call 22500 is now ITM
    t2 = S.trade("F3-NIFTY", days[4:9])
    exits = [x for (s, k, g), x in zip(t2["legs"], t2["exitpx"]) if (s, k) == ("C", 22500.0)]
    assert exits == [100.0] and t2["charges"] > t["charges"]               # exercise STT on the long leg


def test_exit_fill_never_below_intrinsic(fake):
    store, days = fake
    cyc = days[4:9]
    D = store[cyc[-1]]
    leg = E.Leg("P", 22500.0, -1)
    D.con[("P", 22500.0)][:, :] = np.nan                                   # the leg never printed on expiry day
    D.idx[3, :] = 21900.0
    p = S.exit_fill(store, cyc, len(cyc) - 1, leg, S.R1505 + 1, cyc[-1], 65, 1.0)
    assert p >= 600.0                                                      # deep ITM: at least intrinsic


def test_trade_skips_period_boundary(fake):
    store, days = fake
    cyc = [date(2025, 12, 30), date(2025, 12, 31), date(2026, 1, 1)]
    assert S.trade("F1-NIFTY", cyc) is None
