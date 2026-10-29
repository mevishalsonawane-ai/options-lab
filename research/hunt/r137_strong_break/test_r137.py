"""Tests for R137 (PREREG.md): the strong-swing break on hand-built 15-minute bars, its weak counterpart, and the
look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r137 as B  # noqa: E402
from test_r131 import grid, random_grid, truncate  # noqa: E402

STRONG = [(100, 100.5, 99.5, 100), (100, 101, 99.8, 100.8), (100.8, 101.5, 100.5, 101.2),
          (101.2, 103, 101, 102.8),            # 3: swing high H0 = 103
          (102.8, 102.9, 101.5, 101.8), (101.8, 102, 100.2, 100.7),   # 5: swing low 100.2
          (100.7, 101.5, 100.4, 101.3), (101.3, 102.5, 101.2, 102.4),
          (102.4, 103.6, 102.3, 103.4),        # 8: swing high H1 = 103.6 sweeps H0
          (103.4, 103.5, 101.8, 102.0), (102, 102.2, 99.9, 100.0),    # 10: closes below 100.2 (broke structure)
          (100, 101, 99.8, 100.9), (100.9, 102.5, 100.8, 102.4),
          (102.4, 103.8, 102.3, 103.7)]        # 13: closes above 103.6: the strong break
FLAT = [(103.7, 104, 103.4, 103.8)] * 10


def bull(bars, strong=True):
    g = grid([bars + FLAT], tf=15)
    return B.det_break(B.R.sides_of(g)[1], strong)


def test_strong_break_found_with_its_stop():
    c = bull(STRONG)
    assert [(x.d, x.r0) for x in c] == [(0, 13 * 15 + 15)]
    assert c[0].stop == pytest.approx(B.R.stop_below(102.3))


def test_without_the_sweep_it_is_not_strong():
    bars = list(STRONG)
    bars[8] = (102.4, 102.95, 102.3, 102.9)            # no longer above H0 = 103
    bars[9] = (102.9, 102.9, 101.8, 102.0)
    assert bull(bars, strong=True) == []
    assert len(bull(bars, strong=False)) >= 1          # it is a weak break instead


def test_lookahead_guard():
    rng = np.random.default_rng(137)
    g = random_grid(rng, 20)
    S = B.R.sides_of(g)
    for strong in (True, False):
        full = B.candidates(S, strong)
        pick = [full[i] for i in rng.choice(len(full), size=min(8, len(full)), replace=False)]
        for sd, c in pick:
            again = [(s2, x.d, x.r0, round(x.stop, 6)) for s2, x in B.candidates(B.R.sides_of(truncate(g, c.d, c.r0)),
                                                                                    strong)]
            assert (sd, c.d, c.r0, round(c.stop, 6)) in again


def test_four_variants():
    assert set(B.VARIANTS) == {"B1", "B2", "B1N", "B2N"}
