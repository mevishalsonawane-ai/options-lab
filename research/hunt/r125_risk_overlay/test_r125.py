"""R125 tests: one trade a day, the monthly breaker (point-in-time: it only skips LATER trades), and the twins.

    python3 -m pytest research/hunt/r125_risk_overlay -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r125cfg as K  # noqa: E402
import r125lib as L  # noqa: E402
import r119lib as L19  # noqa: E402


def tr(d, und, net):
    t = L19.Trade("R123", und, d, 15, 350, [])
    t.net = net
    return t


def test_one_a_day_priority():
    d = date(2025, 3, 27)
    b = L.one_a_day([tr(d, "BANKNIFTY", 5), tr(d, "NIFTY", -3), tr(date(2025, 3, 28), "SENSEX", 1)])
    assert [(t.und, t.net) for t in b] == [("NIFTY", -3), ("SENSEX", 1)]


def test_breaker_skips_only_later_trades_that_month():
    xs = [tr(date(2025, 1, 2), "NIFTY", -4000), tr(date(2025, 1, 9), "NIFTY", -3000),
          tr(date(2025, 1, 16), "NIFTY", 9000), tr(date(2025, 2, 6), "NIFTY", 100)]
    b = L.breaker(xs)
    assert [t.day for t in b] == [date(2025, 1, 2), date(2025, 1, 9), date(2025, 2, 6)]
    # the trade that tripped the breaker is kept (it was already placed); the next month starts fresh
    assert L.breaker(xs[:1]) == xs[:1]


def test_breaker_point_in_time():
    """Changing a LATER trade's result never changes which earlier trades are kept."""
    a = [tr(date(2025, 1, 2), "NIFTY", -7000), tr(date(2025, 1, 9), "NIFTY", 500)]
    b = [tr(date(2025, 1, 2), "NIFTY", -7000), tr(date(2025, 1, 9), "NIFTY", -99999)]
    assert [t.day for t in L.breaker(a)] == [t.day for t in L.breaker(b)] == [date(2025, 1, 2)]


def test_twins():
    rng = np.random.default_rng(0)
    d = date(2026, 1, 6)
    fin = [tr(d, "NIFTY", 10), tr(d, "SENSEX", 30), tr(date(2026, 1, 8), "SENSEX", 5)]
    tw = L.z1_twin(fin, 200, rng)
    assert set(np.round(tw, 6)) <= {15.0, 35.0}
    dt = L.drop_twin(fin, 1, 200, rng)
    assert set(np.round(dt, 6)) <= {35.0, 15.0, 40.0}
    assert np.allclose(L.drop_twin(fin, 0, 5, rng), 45.0)


def test_declared():
    assert K.BREAKER == -6_500.0 and list(K.TESTS) == ["Z1", "Z2"]
