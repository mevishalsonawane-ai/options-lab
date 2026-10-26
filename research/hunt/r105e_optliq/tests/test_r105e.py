"""R105E tests: the liquidity checks on hand-made volume rows and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import r105e as Q  # noqa: E402


def vol(v=0.0):
    return np.full(375, float(v))


def test_vol20_and_vol100():
    v = vol(0.0)
    v[95:100] = 65 * 4                       # 4 lots a minute x 5 = 20 lots (NIFTY lot 65)
    assert Q.liquid("Q1", v, 100, 65.0) and not Q.liquid("Q2", v, 100, 65.0)
    v[95:100] = 65 * 20
    assert Q.liquid("Q2", v, 100, 65.0)
    v[95:100] = 65 * 3.9
    assert not Q.liquid("Q1", v, 100, 65.0)


def test_live5():
    v = vol(10.0)
    assert Q.liquid("Q3", v, 100, 65.0)
    v[97] = 0.0
    assert not Q.liquid("Q3", v, 100, 65.0)
    v[97] = np.nan
    assert not Q.liquid("Q3", v, 100, 65.0)


def test_edges():
    v = vol(1e6)
    assert not Q.liquid("Q1", v, 4, 65.0) and not Q.liquid("Q1", v, 100, 0.0)
    with pytest.raises(ValueError):
        Q.liquid("Q9", v, 100, 65.0)


def test_lookahead_guard():
    rng = np.random.default_rng(1)
    for _ in range(100):
        v = rng.choice([0.0, 50.0, 5000.0, np.nan], 375)
        er = int(rng.integers(5, 370))
        a = {r: Q.liquid(r, v, er, 65.0) for r in Q.RULES}
        w = v.copy()
        w[er:] = rng.uniform(0, 1e6, 375 - er)
        assert a == {r: Q.liquid(r, w, er, 65.0) for r in Q.RULES}
