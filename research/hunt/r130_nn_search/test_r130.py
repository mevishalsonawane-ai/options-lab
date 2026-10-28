"""R130 tests: the rule grid, the pick (window, side, threshold, fillable), the summary's fragility numbers, the twins.

    python -m pytest research/hunt/r130_nn_search/test_r130.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r130 as S  # noqa: E402


def _rows(evs: dict, row=4, und="NIFTY", d=date(2025, 1, 2), cls=0):
    rec = dict(und=und, day=d, row=row)
    for j in range(S.NJ):
        rec[f"ev_{j}"] = evs.get(j, 0.0)
        rec[f"cls_{j}"] = cls
    return rec


def test_the_grid_has_84_rules_and_s5_trail_is_one():
    assert len(S.RULES) == 84 and len(set(S.RULES)) == 84
    assert "W1-any-300-x1" in S.RULES
    (a, b), sides, thr, x = S.parse("W1-any-300-x1")
    assert (a, b) == (4, 14) and sides == (0, 1) and thr == 300.0 and x == "x1"


def test_pick_respects_window_side_threshold_and_fillable():
    F = pd.DataFrame([_rows({3: 500.0, 6: 700.0}), _rows({3: 900.0}, row=19), _rows({5: 650.0}, row=9)])
    C = S.candidates(F, "W1-any-300-x0")
    assert list(C.row) == [4, 9] and list(C.j) == [6, 5]          # row 19 is outside W1
    C = S.candidates(F, "W1-put-300-x0")
    assert list(C.j) == [3, 5]                                      # puts are odd j
    C = S.candidates(F, "W1-any-600-x0")
    assert list(C.j) == [6, 5]
    C = S.candidates(F, "W1-call-600-x0")
    assert list(C.j) == [6]
    G = F.copy()
    G.loc[0, "cls_6"] = -1                                          # not fillable: the next best is taken
    assert list(S.candidates(G, "W1-any-300-x0").j) == [3, 5]
    assert S.candidates(F.iloc[0:0], "W1-any-300-x0").empty


def test_summary_fragility_numbers():
    T = pd.DataFrame(dict(day=[date(2024, 1, 2), date(2024, 1, 3), date(2025, 1, 2), date(2025, 1, 3)],
                          row=[4, 4, 4, 4], j=[1, 1, 0, 1], net=[1000.0, -200.0, 300.0, -100.0], netx2=[990.0, -210, 290, -110]))
    s = S.summary(T)
    assert s["n"] == 4 and s["win"] == 0.5 and s["total"] == 1000.0 and s["puts"] == 0.75
    assert s["by_year"] == {2024: [2, 800.0], 2025: [2, 200.0]}
    assert abs(s["top5_share"] - 1.0) < 1e-9 and s["busiest_year_share"] == 0.5 and s["without_best_year"] == 200.0
    assert S.summary(T.iloc[0:0]) == dict(n=0)


def test_twins_beat_a_rule_that_picks_the_worst_row():
    d = date(2025, 1, 2)
    F = pd.DataFrame([_rows({}, row=r, d=d) for r in (4, 9, 14)])
    oc = {("NIFTY", d, 4, 1): (-500.0, -510.0, 50), ("NIFTY", d, 9, 1): (800.0, 790.0, 60), ("NIFTY", d, 14, 1): (700.0, 690.0, 70)}
    T = pd.DataFrame(dict(und=["NIFTY"], day=[d], row=[4], j=[1], net=[-500.0]))
    p, mean = S.twin_p(F, T, "W1-put-300-x0", oc, np.random.default_rng(0))
    assert p > 0.5 and mean > 0
    assert S.twin_p(F, T.iloc[0:0], "W1-put-300-x0", oc, np.random.default_rng(0)) == (1.0, 0.0)


def test_trades_go_through_one_open_trade_per_index():
    d = date(2025, 1, 2)
    F = pd.DataFrame([_rows({1: 500.0}, row=4, d=d), _rows({1: 500.0}, row=9, d=d), _rows({1: 500.0}, row=14, d=d)])
    oc = {("NIFTY", d, 4, 1): (100.0, 90.0, 11), ("NIFTY", d, 9, 1): (50.0, 40.0, 20), ("NIFTY", d, 14, 1): (70.0, 60.0, 30)}
    T = S.trades_of(F, "W1-any-300-x0", oc)
    assert list(T.row) == [4, 14]                                   # row 9 is taken while row 4's trade is open (to row 11)
