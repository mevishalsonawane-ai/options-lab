"""Tests for r190export (17 Nov 2026): side names, dropping bad rows, order, and the verdict table."""
from __future__ import annotations

import math
import sys
from datetime import date
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r190export as X  # noqa: E402


def test_side_name_all_forms():
    assert X.side_name(1) == "CE" and X.side_name(-1) == "PE"
    assert X.side_name("C") == "CE" and X.side_name("p") == "PE" and X.side_name("CE") == "CE"
    with pytest.raises(ValueError):
        X.side_name(0)
    with pytest.raises(ValueError):
        X.side_name("X")


def test_compact_drops_bad_rows_and_sorts():
    tr = [dict(und="BANKNIFTY", day=date(2025, 1, 3), era="C", side="P", net=-100.456),
          dict(day=date(2025, 1, 2), era="C", side=1, net=50.0),                     # no und -> NIFTY
          dict(und="NIFTY", day=date(2025, 1, 1), era=None, side=1, net=10.0),       # outside the eras
          dict(und="NIFTY", day=date(2025, 1, 4), era="C", side=1, net=math.nan),    # never emit NaN
          dict(und="NIFTY", day=date(2025, 1, 5), era="C", side=-1, net=None)]
    rows = X.compact("D1", tr)
    assert rows == [["D1", "NIFTY", "2025-01-02", "C", "CE", 50.0],
                    ["D1", "BANKNIFTY", "2025-01-03", "C", "PE", -100.46]]


def test_compact_empty_and_none():
    assert X.compact("L1", []) == [] and X.compact("L1", None) == []


def test_build_keeps_every_candidate_and_verdict():
    sets = {"N1N": [dict(und="NIFTY", day=date(2021, 1, 4), era="A", side=1, net=1.0)]}
    dec = {"m_total": 4762, "candidates": {"N1N": {"reliable": False, "fails": ["era A twin p 0.07 >= 0.05"],
                                                   "account_stop_breaches": {"A": [], "C": ["worst month"]}}}}
    d = X.build(sets, dec)
    assert d["cols"] == X.COLS and len(d["rows"]) == 1 and set(d["names"]) == set(X.K.CANDIDATES)
    assert d["verdicts"]["N1N"] == dict(reliable=False, fails=["era A twin p 0.07 >= 0.05"], stop={"A": [], "C": ["worst month"]})
    assert d["m_total"] == 4762
