"""Tests for R136's ICT inputs (PREREG.md): 35 columns, and the look-ahead guard (each input unchanged when every
minute after the decision minute is removed)."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import ict_features as X  # noqa: E402
from test_r131 import random_grid, truncate  # noqa: E402


def test_35_columns():
    assert len(X.ICT_COLS) == 35 and len(set(X.ICT_COLS)) == 35


def test_lookahead_guard():
    rng = np.random.default_rng(136)
    g = random_grid(rng, 30)
    full = X.features("NIFTY", None, g=g).set_index(["day", "row"])
    days = sorted(set(full.index.get_level_values(0)))
    picks = [(days[i], t) for i in rng.choice(np.arange(22, len(days)), 6, replace=False)
             for t in rng.choice(X.DEC_ROWS, 3, replace=False)]
    for day, t in picks:
        d = g.days.index(day)
        cut = truncate(g, d, int(t) + 1)                    # minutes after t removed, later sessions removed
        part = X.features("NIFTY", None, g=cut).set_index(["day", "row"])
        a, b = full.loc[(day, int(t))], part.loc[(day, int(t))]
        for c in X.ICT_COLS:
            assert (np.isnan(a[c]) and np.isnan(b[c])) or np.isclose(a[c], b[c]), (day, t, c, a[c], b[c])


def test_rsi_bounds():
    c = np.cumsum(np.random.default_rng(1).normal(size=500)) + 100
    r = X.rsi_wilder(c, 50)
    assert np.all((r[50:] >= 0) & (r[50:] <= 100)) and np.isnan(r[:50]).all()
