"""Tests for R144 (PREREG.md): the wide-day rule and Liquidity's combined forced row."""
from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r144 as X  # noqa: E402


def test_wide():
    assert X.wide(dict(A10=1.3), 1.2907) and X.wide(dict(A10=1.2907), 1.2907)
    assert not X.wide(dict(A10=1.0), 1.2907) and not X.wide(dict(A10=np.nan), 1.2) and not X.wide({}, 1.2)
    assert len(X.TESTS) == 4


def test_liq_forced_row_is_the_earlier():
    con = np.full((5, 375), np.nan)
    con[0, 100] = 100.0
    con[1, 100:105] = 101.0                          # no progress: the no-progress row is 105
    D = SimpleNamespace(contract=lambda side, strike: con)
    c = lambda fx: dict(side="C", strike=1.0, er=100, fx=fx)  # noqa: E731
    assert X.liq_np_fxs(D, [c(-1)], 5, 0.05) == [105]
    assert X.liq_np_fxs(D, [c(103)], 5, 0.05) == [103]       # the arm's own exit comes first
    assert X.liq_np_fxs(D, [c(200)], 5, 0.05) == [105]
    con[1, 102] = 106.0                              # progress made: only the arm's own exit
    assert X.liq_np_fxs(D, [c(200)], 5, 0.05) == [200]
    assert X.liq_np_fxs(D, [c(-1)], 5, 0.05) == [-1]
