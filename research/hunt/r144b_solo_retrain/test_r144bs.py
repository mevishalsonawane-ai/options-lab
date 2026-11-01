"""Tests for R144b-S (PREREG.md): periods, the random same-day twin, the gates and the description. No lake needed."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r144bs as S  # noqa: E402


def T(day, net, und="NIFTY", setup="S1"):
    return dict(day=day, net=float(net), und=und, setup=setup, er=10, side="C")


def test_periods_and_months():
    assert S.in_period(date(2023, 7, 1), "P1") and not S.in_period(date(2023, 6, 30), "P1")
    assert S.in_period(date(2024, 11, 20), "P2") and S.in_period(date(2026, 10, 13), "P3")
    assert S.TEST_MONTHS[0] == (2023, 7) and S.TEST_MONTHS[-1] == (2026, 10) and len(S.TEST_MONTHS) == 40
    assert set(S.VARIANTS["V2"]["setups"]) == {"S1", "S2", "S3"} and "S4" in S.VARIANTS["V1"]["setups"]


def test_twin_p_good_and_bad_picker():
    rng = np.random.default_rng(0)
    days = [date(2025, 1, d) for d in range(1, 29)]
    by_day = {d: [T(d, 500), T(d, -500), T(d, -500)] for d in days}
    good = [by_day[d][0] for d in days]
    bad = [by_day[d][1] for d in days]
    pg, mean = S.twin_p(good, by_day, 500, rng)
    pb, _ = S.twin_p(bad, by_day, 500, rng)
    assert pg < 0.01 and pb > 0.9 and -400 * 28 < mean < 0
    assert S.twin_p([], by_day, 10, rng) == (1.0, 0.0)


def test_gates_and_describe():
    ref = dict(max_dd=1000)
    ok = dict(net=100, trades=50, max_dd=900)
    assert S.gates(ok, ref, 0.01, 0.05) == []
    f = S.gates(dict(net=-1, trades=10, max_dd=2000), ref, 0.5, 0.5)
    assert len(f) == 5
    d = S.describe([T(date(2025, 1, 2), 100, "NIFTY", "S1"), T(date(2025, 1, 3), -50, "BANKNIFTY", "S2")])
    assert d["trades"] == 2 and d["per_trade"] == 25 and d["by_setup"]["S1"]["win"] == 1.0
    assert d["by_und"]["BANKNIFTY"]["net"] == -50
