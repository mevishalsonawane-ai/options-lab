"""R99 tests: the +100 / -40 label and exits through R51's engine on a synthetic session, the runner lock, the
day-clustered scan statistic, the bootstrap, summaries, sessions-to-expiry (synthetic data only)."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import r99cfg as K  # noqa: E402
import r51cfg as K51  # noqa: E402
import r51data as RD  # noqa: E402
import r99data as DA  # noqa: E402
import r99dataset as DS  # noqa: E402
import r99scan as SC  # noqa: E402
import r99study as ST  # noqa: E402


def synth_day(prem_path, d=date(2025, 6, 2), strike=25000.0, expiry=False):
    """A session whose index is flat at the strike and whose ATM call trades every minute along prem_path."""
    idx = np.full((4, K51.NROWS), strike)
    p = np.asarray(prem_path, float)
    C = np.vstack([p, p + 0.5, p - 0.5, p, np.full(K51.NROWS, 100.0)]).astype(np.float32)
    D = RD.Day(d, idx, atm=np.full(K51.NROWS, strike), con={("C", strike): C, ("P", strike): C.copy()}, expiry=expiry)
    return D


def test_label_target_stop_squareoff():
    up = np.r_[np.full(20, 120.0), np.linspace(120, 260, 200), np.full(155, 260.0)]
    lb = DS.label(synth_day(up), "C", 10, 100.0, 40.0)
    assert lb[2] == "target" and lb[1] == pytest.approx(100.0) and lb[0] > 0
    dn = np.r_[np.full(20, 120.0), np.linspace(120, 40, 100), np.full(255, 40.0)]
    lb = DS.label(synth_day(dn), "C", 10, 100.0, 40.0)
    assert lb[2] == "stop" and lb[0] < -40 * 75 * 0.9
    flat = np.full(K51.NROWS, 120.0)
    assert DS.label(synth_day(flat), "C", 10, 100.0, 40.0)[2] == "squareoff"
    gone = flat.copy()
    gone[11:20] = np.nan
    assert DS.label(synth_day(gone), "C", 10, 100.0, 40.0) is None         # no print within 3 minutes


def test_catch100_and_runner_exits():
    ex = ST.catch100_exits(120.0)
    assert ex.stop == pytest.approx(40 / 120) and ex.target == pytest.approx(100 / 120)
    r = ST.runner_exits("S2", 75.0)
    assert r.stop == 0.15 and r.target < 0 and r.lock_trig == 50 * 75 and r.lock_trail == 40 * 75
    up_down = np.r_[np.full(20, 120.0), np.linspace(120, 200, 80), np.linspace(200, 120, 80), np.full(195, 120.0)]
    t = ST.price_und("NIFTY", synth_day(up_down), "C", 25000.0, 11, r)
    assert t["why"] == "lock" and 150 <= t["X"] <= 162                      # trailed 40 under the 200 peak


def test_clustered_and_bootstrap():
    day = np.repeat(np.arange(30), 4)
    g = pd.DataFrame(dict(day=day, y=10.0 + 0.5 * ((day % 3) - 1)))
    m, p, nd = SC.clustered(g, "y")
    assert m == pytest.approx(10.0) and nd == 30 and p < 1e-6
    g0 = pd.DataFrame(dict(day=day, y=np.where(day % 2, 5.0, -5.0)))
    assert SC.clustered(g0, "y")[1] > 0.9                                  # no edge: p near 1
    assert ST.boot_diff(pd.Series(np.full(50, 5.0))) < 0.01
    assert ST.boot_diff(pd.Series(np.full(50, -5.0))) > 0.99


def test_summary_quarters_and_month():
    tr = [dict(day=pd.Timestamp("2025-05-02"), net=1000.0, why="target"),
          dict(day=pd.Timestamp("2025-08-04"), net=-14000.0, why="stop")]
    s = ST.summary(tr)
    assert s["trades"] == 2 and s["quarters"][0] == 1000 and s["quarters"][1] == -14000
    assert s["worst_month"] == -14000 and s["max_dd"] == -14000
    assert ST.summary([]) == dict(trades=0, net=0.0)


def test_sessions_to_expiry():
    ds = {date(2025, 6, k): RD.Day(date(2025, 6, k), np.zeros((4, 2)), expiry=(k == 5)) for k in (2, 3, 4, 5, 6)}
    m = DA.sessions_to_expiry(ds)
    assert m[date(2025, 6, 2)] == 3 and m[date(2025, 6, 5)] == 0 and m[date(2025, 6, 6)] == 99
