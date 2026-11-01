"""Tests for R146 (PREREG.md): BH, the block bootstrap's sign, partial correlation and the test family."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r146 as S  # noqa: E402


def test_family_and_bh():
    assert len(S.tests()) == 26
    q = S.bh([0.01, 0.04, 0.5])
    assert np.allclose(q, [0.03, 0.06, 0.5])


def test_block_bootstrap_detects_a_real_link_and_not_noise():
    rng = np.random.default_rng(0)
    x = rng.normal(size=600)
    y = 0.5 * x + rng.normal(size=600)
    r, p = S.block_p(x, y, np.random.default_rng(1))
    assert r > 0.3 and p < 0.01
    r2, p2 = S.block_p(x, rng.normal(size=600), np.random.default_rng(2))
    assert p2 > 0.01


def test_partial_removes_the_common_driver():
    rng = np.random.default_rng(3)
    us = rng.normal(size=800)
    x = us + 0.1 * rng.normal(size=800)
    y = us + 0.1 * rng.normal(size=800)
    assert abs(np.corrcoef(S.resid(x, us), S.resid(y, us))[0, 1]) < 0.15


def test_start_dates():
    assert S.start_of("G", "T0").year == 2015 and S.start_of("G", "T2").year == 2021
    assert S.start_of("MO", "T1") == S.STARTS["H"]
