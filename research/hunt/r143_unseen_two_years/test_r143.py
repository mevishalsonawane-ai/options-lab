"""Tests for R143 (PREREG.md): the frozen rules and the single-period lose-less gate."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r143 as T  # noqa: E402


def test_frozen_rules():
    assert T.drops("A10", dict(A10=1.40), "R72", 5) and T.drops("A10", dict(A10=1.3206), "R72", 5)
    assert not T.drops("A10", dict(A10=1.2), "R72", 5) and not T.drops("A10", dict(A10=np.nan), "R72", 5)
    assert not T.drops("A10", {}, "R72", 5)
    assert T.drops("BBX", dict(A11=1.0, A1=9.0, A7=9.0), "R51", 50)
    assert T.drops("BBX", dict(A11=0.0, A1=1.0, A7=9.0), "R51", 50)            # weak push (cut 1.627)
    assert T.drops("BBX", dict(A11=0.0, A1=9.0, A7=0.5), "R51", 50)            # quiet bar (cut 0.995)
    assert not T.drops("BBX", dict(A11=0.0, A1=9.0, A7=9.0), "R51", 50)
    assert set(T.TESTS) == {"FB-R72-A10", "NP5-52", "BBX-51"}


def test_gate():
    base = dict(loss_sum=-1000, max_dd=900, worst_day=-700, worst_month=-800, net=500, trades=100)
    good = dict(base, loss_sum=-800, max_dd=800)
    assert T.gate(good, base, 0.01, 0.03, 40)["passed"]
    assert not T.gate(dict(good, net=-1), base, 0.01, 0.03, 40)["passed"]
    assert not T.gate(good, base, 0.2, 0.03, 40)["passed"]
    assert not T.gate(good, base, 0.01, 0.03, 10)["passed"]
    assert not T.gate(dict(good, worst_month=-900), base, 0.01, 0.03, 40)["passed"]
