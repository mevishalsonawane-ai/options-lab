"""Tests for R142 (PREREG.md): the bad-break rules and the no-progress exit row (reads only minutes before it)."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r142 as B  # noqa: E402

CUT = dict(A1_40=0.2, A7_20=0.5)


def test_bad_rules():
    assert B.bad("BB1", dict(A11=1.0), CUT, 50) and not B.bad("BB1", dict(A11=0.0), CUT, 50)
    assert B.bad("BB2", dict(A1=0.1), CUT, 50) and not B.bad("BB2", dict(A1=0.3), CUT, 50)
    assert B.bad("BB3", dict(A7=0.4), CUT, 50) and not B.bad("BB3", dict(A7=0.9), CUT, 50)
    assert not B.bad("BB2", dict(A1=np.nan), CUT, 50) and not B.bad("BB1", {}, CUT, 50)     # unknown: not bad
    assert B.bad("BBX", dict(A11=0.0, A1=0.5, A7=0.1), CUT, 50)
    assert not B.bad("BBX", dict(A11=0.0, A1=0.5, A7=0.9), CUT, 50)
    assert B.bad("T11", {}, CUT, 75) and B.bad("T11", {}, CUT, 164) and not B.bad("T11", {}, CUT, 165)
    assert not B.bad("T11", {}, CUT, 74)


def con_with(highs, er=100, o0=100.0):
    c = np.full((5, 375), np.nan)
    c[0, er] = o0
    c[1, er:er + len(highs)] = highs
    return c


def test_no_progress_row():
    assert B.no_progress_row(con_with([101, 102, 103, 104, 104.9]), 100, 5, 0.05) == 105      # never +5%
    assert B.no_progress_row(con_with([101, 106, 99, 98, 97]), 100, 5, 0.05) == -1            # reached +5%
    assert B.no_progress_row(con_with([101] * 5), 372, 5, 0.05) == -1                        # past the session
    assert B.no_progress_row(None, 100, 5, 0.05) == -1
    c = con_with([101] * 5)
    c[0, 100] = np.nan
    assert B.no_progress_row(c, 100, 5, 0.05) == -1                                            # no entry price
    c = con_with([101, 102, 103, 104, 104.9] + [200.0])                                        # later minutes ignored
    assert B.no_progress_row(c, 100, 5, 0.05) == 105


def test_family():
    assert len(B.TESTS) == 22
    assert sum(1 for v in B.TESTS.values() if v["kind"] == "exit") == 9
    assert all(v["arm"] != "LIQ" for v in B.TESTS.values() if v["kind"] == "exit")
