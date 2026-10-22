"""R104 tests on hand-made days: the trigger, the look-ahead guard, XI / XP exits, stale marks, the fade twin, the
continuation counter, the holdout lock and the test count.

    python -m pytest research/hunt/r104_follow/tests -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
import r104engine as F  # noqa: E402
import r104study as S  # noqa: E402

N = 375
DAY, EXP = date(2026, 5, 4), date(2026, 5, 5)


def con(p, vol=100.0):
    p = np.asarray(p, float)
    a = np.full((5, N), np.nan)
    a[0] = a[1] = a[2] = a[3] = p
    a[4] = np.where(np.isfinite(p), vol, np.nan)
    return a


def day(path, k0=25000.0, step=50.0, prem=100.0):
    """Index follows `path` (375 closes). Every strike's call = max(prem - 0.5 (k - index) ... a simple linear
    'delta 0.5' pricing around each strike, so options move with the index and never go below 1."""
    idx = np.asarray(path, float)
    D = SimpleNamespace(idx=np.vstack([idx] * 4), atm=np.round(idx / step) * step, con={}, expiry=False)
    for j in range(-20, 21):
        k = k0 + j * step
        c = np.maximum(prem + 0.5 * (idx - k) + np.maximum(idx - k, 0) * 0.5, 1.0)
        p = np.maximum(prem + 0.5 * (k - idx) + np.maximum(k - idx, 0) * 0.5, 1.0)
        D.con[("C", k)] = con(c)
        D.con[("P", k)] = con(p)
    return D


def flat_then(move_at, size, k0=25000.0):
    path = np.full(N, k0)
    path[move_at:] = k0 + size
    return path


def test_anchor_and_trigger():
    D = day(flat_then(60, 120.0))                     # S = 200 at 09:20; 0.5 S = 100 -> triggers at row 60, up
    i0, s, k0 = F.anchor(D, "NIFTY")
    assert (i0, s, k0) == (25000.0, 200.0, 25000.0)
    assert F.trigger(D, i0, s, 0.5) == (60, 1)
    assert F.trigger(D, i0, s, 1.0) is None            # 120 < 200
    D2 = day(flat_then(60, -250.0))
    assert F.trigger(D2, 25000.0, 200.0, 1.0) == (60, -1)
    D3 = day(flat_then(F.LAST_TRIG_ROW + 1, 300.0))    # after 14:30: no trade
    assert F.trigger(D3, 25000.0, 200.0, 0.5) is None


def test_follow_buys_the_side_of_the_move_and_fade_the_other_at_the_same_minutes():
    path = flat_then(60, 120.0)
    path[61:] = np.linspace(25120, 25400, N - 61)      # keeps going: the XI target (0.5 + 0.5) S = 200 is hit
    D = day(path)
    t = F.run(D, "NIFTY", DAY, 0.5, "XI", False, EXP)
    assert t["side"] == "C" and t["trig"] == 60 and t["er"] == 61 and t["why"] == "target" and t["net"] > 0
    f = F.run(D, "NIFTY", DAY, 0.5, "XI", False, EXP, fade=True, exit_at=t["xr"])
    assert f["side"] == "P" and f["er"] == t["er"] and f["xr"] == t["xr"] and f["net"] < 0
    assert t["geo"] == 1 and f["geo"] is None


def test_xi_stop_when_the_move_fails_and_xp_stop_and_lock():
    path = flat_then(60, 120.0)
    path[90:] = 25040.0                                 # back to 40 < (0.5 - 0.25) x 200 = 50: XI stop at 91
    D = day(path)
    t = F.run(D, "NIFTY", DAY, 0.5, "XI", False, EXP)
    assert t["why"] == "stop" and t["xr"] == 91
    path = flat_then(60, 120.0)
    path[90:] = 24880.0                                 # call falls far: XP stop at 70% of entry
    t = F.run(day(path), "NIFTY", DAY, 0.5, "XP", False, EXP)
    assert t["why"] == "stop"
    path = flat_then(60, 120.0)
    path[70:100] = 25260.0                              # call up > 30% (armed) ...
    path[100:] = 25100.0                                # ... then back under +10% (100 vs 120 paid): lock
    t = F.run(day(path), "NIFTY", DAY, 0.5, "XP", False, EXP)
    assert t["why"] == "lock" and t["xr"] == 101


def test_lookahead_guard():
    path = flat_then(60, 120.0)
    path[90:] = 25040.0
    D = day(path)
    base = F.run(D, "NIFTY", DAY, 0.5, "XI", False, EXP)
    for c in D.con.values():
        c[0:4, base["xr"] + 1:] *= 3.0
    D.idx[:, base["xr"] + 1:] += 500.0
    again = F.run(D, "NIFTY", DAY, 0.5, "XI", False, EXP)
    assert (again["trig"], again["xr"], again["why"], again["side"]) == (base["trig"], base["xr"], base["why"], base["side"])
    D2 = day(flat_then(60, 120.0))
    D2.idx[:, 61:] += 1000.0                            # later prices never move the trigger minute
    assert F.trigger(D2, 25000.0, 200.0, 0.5)[0] == 60


def test_stale_marks_floor_at_intrinsic_and_keep_prints():
    path = flat_then(60, 120.0)
    path[100:] = 25800.0
    D = day(path)
    c = D.con[("C", 25100.0)]
    c[:, 100:] = np.nan                                 # left the window while 700 points in the money
    m = F.marks(D, c, "C", 25100.0, DAY, EXP, 95, 120)
    assert np.allclose(m[:5], c[3, 95:100]) and (m[5:] >= 700.0).all()
    assert (F.marks(D, D.con[("C", 25000.0)], "C", 25000.0, DAY, EXP, 95, 120) == D.con[("C", 25000.0)][3, 95:121]).all()


def test_entry_without_a_traded_open_is_skipped():
    D = day(flat_then(60, 120.0))
    D.con[("C", 25100.0)][0, 61:70] = np.nan
    assert F.run(D, "NIFTY", DAY, 0.5, "XI", False, EXP) is None


def test_geometry_counter():
    path = flat_then(60, 120.0)
    path[80:] = 25120 - 60.0                           # back 60 >= 0.25 x 200 = 50 first
    D = day(path)
    assert F.geometry(D, 25000.0, 200.0, 60, 1, 0.5, 355) == 0
    path = flat_then(60, 120.0)
    path[80:] = 25120 + 100.0                          # on 100 = 0.5 x 200 first
    assert F.geometry(day(path), 25000.0, 200.0, 60, 1, 0.5, 355) == 1
    assert F.geometry(day(flat_then(60, 120.0)), 25000.0, 200.0, 60, 1, 0.5, 355) is None


def test_continuation_and_fade_stats():
    tr = [dict(geo=1), dict(geo=0), dict(geo=0), dict(geo=None)]
    c = S.continuation(tr)
    assert c["n"] == 3 and c["rate"] == pytest.approx(1 / 3, abs=1e-4) and c["undecided"] == 1
    rng = np.random.default_rng(0)
    g = S.fade_gate([dict(net=100.0)] * 50, [dict(net=-100.0)] * 50, rng)
    assert g["mean_diff"] == 200.0 and g["p"] == 0.0 and g["fade_net"] == -5000


def test_holdout_lock_and_count(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    with pytest.raises(PermissionError):
        S.trades_for("holdout")
    (tmp_path / "HOLDOUT_READ").write_text("x")
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    with pytest.raises(SystemExit):
        S.holdout()
    assert len(S.TESTS) == 16 and len({S.test_id(*t) for t in S.TESTS}) == 16
