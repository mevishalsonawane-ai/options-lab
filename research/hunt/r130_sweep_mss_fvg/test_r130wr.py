"""R130 Amendment 3 tests: the wider stop, the minimum target, the 60-minute trend, the pick rule and the walk-forward.

    python -m pytest research/hunt/r130_sweep_mss_fvg -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r130 as R  # noqa: E402
import r130wr as WR  # noqa: E402
import test_r130 as T1  # noqa: E402
import test_r130enh as TE  # noqa: E402


def _w1(**kw):
    return R.scan(T1.minutes(T1._wick_bars()), dict(R.WICK_VARIANTS["W1"], **kw.pop("cfg", {})), set(),
                  lambda d: True, **kw)


def test_sweep_stop_is_beyond_the_legs_low():
    fvg = _w1()[0][0]
    wide = _w1(cfg=dict(stop="sweep"))[0][0]
    assert fvg["stop_pts"] == pytest.approx(1005.5 - (998 - 0.0002 * 998))
    assert wide["stop_pts"] == pytest.approx(1005.5 - (989 - 0.0002 * 989))   # bar 17's sweep low 989


def test_min_target_skips_small_targets():
    t = _w1()[0][0]                                           # target 1010.2 - 1005.5 = 4.7 points = 0.47%
    assert _w1(min_tgt_pct=0.004)[0] != []
    trades, meta = _w1(min_tgt_pct=0.005)
    assert trades == [] and meta["skips"]["small_target"] == 1


def test_trend_grid_60_minutes_look_ahead_guard():
    g = TE._wave_days()
    full = R.__dict__  # noqa: F841
    import r130enh as E
    a = E.trend_grid(g, 60)
    g2 = TE._wave_days()
    for x in (g2.o, g2.h, g2.l, g2.c):
        x[2, 200:] = np.nan
    b = E.trend_grid(g2, 60)
    assert (a[:2] == b[:2]).all() and (a[2, :200] == b[2, :200]).all()


def _T(rows):
    return pd.DataFrame([dict(version=v, net=n, day=date(y, 6, 1)) for v, n, y in rows])


def test_pick_rule_order():
    rows = [("A", 100, 2022)] * 40 + [("A", -50, 2022)] * 20          # 60 trades, 67% win, positive
    rows += [("B", 300, 2022)] * 30 + [("B", -100, 2022)] * 30        # 50% win, bigger total
    assert WR.pick(_T(rows), ["A", "B"]) == "A"                       # the 65% rule first
    rows2 = [("A", 100, 2022)] * 30 + [("A", -50, 2022)] * 30         # 50%, positive
    rows2 += [("B", 300, 2022)] * 33 + [("B", -100, 2022)] * 27       # 55%, positive
    assert WR.pick(_T(rows2), ["A", "B"]) == "B"                      # none at 65%: the highest win rate
    assert WR.pick(_T([("A", -1, 2022)] * 60 + [("B", -2, 2022)] * 60), ["A", "B"]) == "A"


def test_walk_forward_never_uses_the_test_year():
    A, B = "WR-S0G0X0T0", "WR-S1G0X0T0"
    base = [(A, 100, y) for y in (2021, 2022)] * 40 + [(B, -10, y) for y in (2021, 2022)] * 40
    test = [(A, -500, 2023)] * 10 + [(B, 900, 2023)] * 10
    T = _T(base + test).assign(und="NIFTY", entry=0, netx2=0.0, pts=0.0, R=0.0, why="time", side=1)
    wf = WR.walk_forward(T, [A, B])
    s23 = wf["steps"][0]
    assert s23["test"] == "2023" and s23["picked"] == A and s23["total"] == -5000      # B's 2023 gain is not seen
    assert wf["steps"][1]["picked"] == A      # by 2024: A 89% won and positive; B has a bigger total but 11% won
