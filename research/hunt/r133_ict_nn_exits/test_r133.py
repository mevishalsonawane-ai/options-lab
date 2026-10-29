"""Tests for R133 (PREREG.md): the variant family, the gates, and the filter applied after one-trade-at-a-time."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r133 as T3  # noqa: E402


def test_185_new_variants_none_from_r132():
    assert len(T3.ENTRIES) == 39 and len(T3.EXITS) == 5
    assert len(T3.VARIANTS) == 185 and len(set(T3.VARIANTS)) == 185
    assert not set(T3.VARIANTS) & T3.DONE_IN_R132


def test_gates():
    s = dict(n=400, avg_net=10.0, avg_net_x2=1.0, max_dd=-30000.0, worst_month=-12000.0,
             by_fold={k: dict(total=1.0 if k != "F4" else -1.0) for k in T3.K.FOLDS})
    assert all(T3.gates(s, 0.05).values())
    assert not T3.gates(s, 0.2)["q"]
    assert not T3.gates(dict(s, worst_month=-13000.0), 0.05)["account"]
    assert not T3.gates(dict(s, avg_net_x2=-0.1), 0.05)["net"]


def test_evaluate_keeps_only_positive_ev_views():
    d0 = date(2024, 3, 4)
    v = T3.VARIANTS[0]
    e, x = v.rsplit("-", 1)
    T = pd.DataFrame(dict(v=[v] * 4, und="NIFTY", day=[d0] * 4, entry=[10, 20, 30, 40], side=[1, 1, -1, -1],
                          net=[100.0, -50.0, 80.0, -20.0], netx2=[90.0, -60.0, 70.0, -30.0], pts=0.0, R=0.0,
                          why="time", entry_rule=e, exit_rule=x))
    rows = [NN_row for NN_row in (T3.NN.view_row(r) for r in [10, 20, 30, 40])]
    P = pd.DataFrame(dict(und="NIFTY", day=[d0] * 4, row=rows, pdir30=0.5,
                          **{f"ev_{j}": [5.0, -5.0, -5.0, -5.0] if j % 2 == 0 else [-5.0, -5.0, 5.0, -5.0]
                             for j in range(16)}))
    out = T3.evaluate(T, P, "design", np.random.default_rng(0))
    assert out[v]["n"] == 2 and out[v]["total"] == 180.0 and out[v]["base"]["n"] == 4
