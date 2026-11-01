"""Tests for R145 (PREREG.md): the opposite side, the ATM strike from closes before the minute, the family."""
from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r145 as R  # noqa: E402


def test_opp_and_family():
    assert R.opp("C") == "P" and R.opp("P") == "C"
    assert R.TESTS == ("M15-L", "M5-L", "REV-S", "REV-F", "F-L")


def test_atm_reads_only_earlier_closes():
    idx = np.full((4, 375), np.nan)
    idx[3, :10] = 50024.0
    idx[3, 10] = 50080.0                       # minute 10 itself is not known at minute 10
    D = SimpleNamespace(idx=idx)
    assert R.atm(D, "BANKNIFTY", 10) == 50000.0
    assert R.atm(D, "BANKNIFTY", 11) == 50100.0
    assert R.atm(D, "BANKNIFTY", 0) is None
