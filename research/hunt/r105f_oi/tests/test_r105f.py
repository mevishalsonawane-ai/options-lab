"""R105F tests: OI at m, each check on hand-made chains, unknowns, and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import r105f as O  # noqa: E402

N = 375
STEP = 50.0
ATM = 22500.0


def flat(v):
    return np.full(N, float(v))


def chain(cv=100.0, pv=100.0):
    ks = [ATM + k * STEP for k in range(-10, 11)]
    return {k: flat(cv) for k in ks}, {k: flat(pv) for k in ks}


def test_oi_at_last_print():
    s = np.full(N, np.nan)
    s[3], s[10] = 5.0, 7.0
    assert np.isnan(O.oi_at(s, 2)) and O.oi_at(s, 5) == 5.0 and O.oi_at(s, 10) == 7.0 and O.oi_at(s, 200) == 7.0
    assert np.isnan(O.oi_at(s, -1))


def test_o1_pcr():
    c, p = chain(100, 120)
    assert O.o1_pcr(c, p, ATM, STEP, 50) == 1
    c, p = chain(100, 70)
    assert O.o1_pcr(c, p, ATM, STEP, 50) == -1
    c, p = chain(100, 90)
    assert O.o1_pcr(c, p, ATM, STEP, 50) == 0
    assert O.o1_pcr({}, {}, ATM, STEP, 50) == 0
    assert O.keep("O1", "P", (*chain(100, 120), ATM, STEP), 50, None, ATM) is False
    assert O.keep("O1", "C", (*chain(100, 120), ATM, STEP), 50, None, ATM) is True


def test_o2_writers():
    c, p = chain(100, 100)
    for k in p:
        p[k][20:] = 150.0                                     # put writers add after 09:35
    assert O.o2_writers(c, p, ATM, STEP, 60, "C") is True
    assert O.o2_writers(c, p, ATM, STEP, 60, "P") is False
    assert O.o2_writers(c, p, ATM, STEP, 3, "P") is True     # before 09:20: unknown keeps


def test_o3_ownoi():
    own = flat(100.0)
    own[50:] = 106.0
    assert O.o3_ownoi(own, 60) is False                      # +6% in 15 minutes
    own[50:] = 104.0
    assert O.o3_ownoi(own, 60) is True
    assert O.o3_ownoi(None, 60) is True and O.o3_ownoi(own, 10) is True


def test_o4_wall():
    c, p = chain(100, 100)
    c[ATM + STEP][:] = 1000.0                                # a call wall one strike above
    assert O.o4_wall(c, p, ATM, STEP, 60, "C", ATM + STEP - 30) is False    # 30 points below the wall: within 0.3%
    assert O.o4_wall(c, p, ATM, STEP, 60, "C", ATM - 100) is True           # 150 points away (0.67%)
    p[ATM - STEP][:] = 1000.0
    assert O.o4_wall(c, p, ATM, STEP, 60, "P", ATM - STEP + 30) is False
    assert O.o4_wall(c, p, ATM, STEP, 60, "C", float("nan")) is True


def test_lookahead_guard():
    rng = np.random.default_rng(2)
    for _ in range(60):
        c, p = chain()
        for b in (c, p):
            for k in b:
                b[k] = rng.uniform(50, 150, N)
        m = int(rng.integers(20, 370))
        own = c[ATM + STEP]
        before = [O.keep(r, s, (c, p, ATM, STEP), m, own, ATM + 10) for r in O.RULES for s in ("C", "P")]
        for b in (c, p):
            for k in b:
                b[k][m + 1:] = rng.uniform(1e5, 1e6, N - m - 1)
        after = [O.keep(r, s, (c, p, ATM, STEP), m, own, ATM + 10) for r in O.RULES for s in ("C", "P")]
        assert before == after
