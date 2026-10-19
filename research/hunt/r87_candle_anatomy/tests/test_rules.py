import json
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r87_candle_anatomy import rules as R


def _row(und, tf, fam, cond, eff, z, q, folds, rw_eff, rw_n, rw_pts, n=500):
    return dict(und=und, tf=tf, family=fam, cond=cond, eff=eff, z=z, q=q, tested=True, n=n,
                fold_eff=json.dumps(folds), rw_eff=rw_eff, rw_n=rw_n, rw_move_pts=rw_pts)


COST = {u: dict(points=10.0) for u in ("NIFTY", "BANKNIFTY", "SENSEX")}


def test_select_screens_and_ranks():
    p1 = pd.DataFrame([
        _row("NIFTY", 5, "hammer", "", 0.2, 5.0, 0.01, [0.1, 0.2, 0.1, -0.1], 0.1, 100, 15.0),      # passes
        _row("NIFTY", 5, "doji", "", 0.2, 6.0, 0.01, [0.1, -0.2, -0.1, 0.1], 0.1, 100, 15.0),       # 2 of 4 folds
        _row("NIFTY", 1, "marubozu_red", "", -0.2, 9.0, 0.01, [-1, -1, -1, -1], -0.1, 100, -50.0),  # 1m: not eligible
        _row("BANKNIFTY", 15, "big_lower_wick", "", -0.3, 4.0, 0.05, [-.1] * 4, -0.2, 50, -5.0),   # fails cost only
        _row("SENSEX", 5, "spinning_top", "", 0.3, 7.0, 0.2, [.1] * 4, 0.1, 50, 50.0),             # q too high
    ])
    p2 = pd.DataFrame([
        _row("NIFTY", 15, "hammer", "sweep_pdl", 0.4, 8.0, 0.02, [.1] * 4, 0.3, 60, 30.0),        # same family as #1
        _row("SENSEX", 5, "shooting_star", "uptrend", -0.3, 4.5, 0.03, [-.1] * 4, 0.1, 60, -30.0),  # rw sign flips
    ])
    out = R.select(p1, p2, COST)
    assert [r["family"] for r in out] == ["hammer", "big_lower_wick"]
    assert out[0]["cond"] == "sweep_pdl" and out[0]["long"] and out[0]["screen"] == "passes every screen"
    assert out[1]["screen"].startswith("fails the cost") and not out[1]["long"]


def test_select_nothing_eligible():
    p1 = pd.DataFrame([_row("NIFTY", 5, "doji", "", 0.2, 6.0, 0.5, [.1] * 4, 0.1, 100, 15.0)])
    assert R.select(p1, p1.iloc[:0], COST) == []


def test_summary_top5_and_drawdown():
    tr = pd.DataFrame(dict(day=[date(2025, 1, d) for d in range(1, 11)], net=[100, -50, 200, -300, 50, 10, 20, 30,
                                                                          40, -10.0],
                           stale=False, why="time"))
    s = R.summary(tr)
    assert s["trades"] == 10 and np.isclose(s["net"], 90)
    assert np.isclose(s["top5_removed"], 90 - (200 + 100 + 50 + 40 + 30))
    assert np.isclose(s["max_dd"], 300) and s["worst_day"] == -300
