"""R104 tests: two-day pricing (d1 prints, d1 stale, d1 never printed -> d0's IV), fills on d1, the stop at d1's open
after a gap, the two exits, the look-ahead guard, the entry-day rules and the holdout lock. Hand-made days only.

    python -m pytest research/hunt/r104_overnight/tests -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))
sys.path.insert(0, str(HERE.parents[1] / "r103_sell" / "tests"))
import r104engine as E4  # noqa: E402
import r104study as S4  # noqa: E402
from test_r103 import flat_day  # noqa: E402

E = E4.E
D0D, D1D, EXP = date(2026, 5, 4), date(2026, 5, 5), date(2026, 5, 7)


def two_days(gap=0.0):
    D0, D1 = flat_day(), flat_day()
    if gap:
        D1.idx[:, :] = 25000.0 + gap
    return D0, D1


def test_leg_value_paths():
    D0, D1 = two_days()
    assert E4.leg_value(D0, D1, "C", 25000.0, 10, D0D, D1D, EXP) == pytest.approx(D1.con[("C", 25000.0)][3, 10])
    c = D1.con[("P", 24800.0)]
    c[:, 20:] = np.nan                                         # stale on d1 after minute 19
    stale = E4.leg_value(D0, D1, "P", 24800.0, 60, D0D, D1D, EXP)
    assert stale == pytest.approx(E.repaired(D1, "P", 24800.0, 60, D1D, EXP))
    del D1.con[("C", 25200.0)]                                 # never printed on d1: d0's last print IV
    D1.idx[3, :] = 25400.0                                     # index up 400: the call wing is worth more
    v = E4.leg_value(D0, D1, "C", 25200.0, 30, D0D, D1D, EXP)
    assert v > D0.con[("C", 25200.0)][3, -1]
    del D0.con[("C", 25200.0)]
    assert np.isnan(E4.leg_value(D0, D1, "C", 25200.0, 30, D0D, D1D, EXP))


def test_flat_two_days_exits_at_their_times():
    D0, D1 = two_days()
    t1 = E4.run(D0, D1, "NIFTY", D0D, D1D, "IF", "O1", EXP, False)
    t2 = E4.run(D0, D1, "NIFTY", D0D, D1D, "IF", "O2", EXP, False)
    t3 = E4.run(D0, D1, "NIFTY", D0D, D1D, "IF", "O2", EXP, True)
    assert t1["why"] == "time" and t1["xr"] == E4.O1_ROW and t2["xr"] == E.SQ_ROW and t3["xr"] == E.SQ_ROW_EXPIRY
    assert t1["gross"] < 0 and t1["net"] < t1["gross"]        # flat prices: slippage and charges only
    assert t1["credit"] > 0 and t1["d1"] == D1D and t1["friday"] is False


def test_gap_hits_the_stop_at_the_open():
    D0, D1 = two_days(gap=500.0)
    for k in np.arange(24500.0, 25501.0, 50.0):               # d1 prices follow a +500 gap (calls up, puts down)
        D1.con[("C", k)][0:4] = np.maximum(25500.0 - k, 0) + 30.0
        D1.con[("P", k)][0:4] = np.maximum(k - 25500.0, 0) + 30.0
    t = E4.run(D0, D1, "NIFTY", D0D, D1D, "IF", "O2", EXP, False)
    assert t["why"] == "stop" and t["xr"] == 1 and t["net"] < -0.5 * t["credit"] * 0.5


def test_lookahead_guard():
    D0, D1 = two_days(gap=500.0)
    for k in np.arange(24500.0, 25501.0, 50.0):
        D1.con[("C", k)][0:4] = np.maximum(25500.0 - k, 0) + 30.0
        D1.con[("P", k)][0:4] = np.maximum(k - 25500.0, 0) + 30.0
    base = E4.run(D0, D1, "NIFTY", D0D, D1D, "IF", "O2", EXP, False)
    for c in D1.con.values():
        c[0:4, base["xr"] + 1:] *= 4.0                         # after the exit fill minute: nothing changes
    after = E4.run(D0, D1, "NIFTY", D0D, D1D, "IF", "O2", EXP, False)
    assert (after["why"], after["xr"], after["entry"]) == (base["why"], base["xr"], base["entry"])
    D0b, D1b = two_days()
    st = E.setup(D0b, "NIFTY", "IF", E4.DECIDE_ROW)
    for c in D0b.con.values():
        c[:, E4.DECIDE_ROW + 1:] *= 5.0
    assert E.setup(D0b, "NIFTY", "IF", E4.DECIDE_ROW).legs == st.legs


def test_holdout_lock_and_ids(tmp_path, monkeypatch):
    monkeypatch.setattr(S4, "HOLDOUT_FLAG", tmp_path / "HOLDOUT_READ")
    with pytest.raises(PermissionError):
        S4.trades("holdout")
    (tmp_path / "HOLDOUT_READ").write_text("x")
    monkeypatch.setattr(S4, "RESULTS", tmp_path)
    with pytest.raises(SystemExit):
        S4.holdout()
    assert S4.period_of(date(2026, 7, 14)) == "design" and S4.period_of(date(2026, 10, 16)) == "holdout"
    assert S4.period_of(date(2026, 10, 19)) == "after"
    assert len(S4.TESTS) == 12 and len({S4.test_id(*t) for t in S4.TESTS}) == 12


def test_pairs_skip_the_expiry_day_and_never_cross_the_expiry(monkeypatch):
    days = {date(2026, 5, d): flat_day() for d in (4, 5, 6, 7, 8, 11)}
    exp = {date(2026, 5, 7)}
    near = {d: (date(2026, 5, 7) if d <= date(2026, 5, 7) else date(2026, 5, 14)) for d in days}
    monkeypatch.setattr(S4.RD, "load", lambda u: days)
    monkeypatch.setattr(S4.S3, "expiry_info", lambda u, ds: (exp, near))
    p = S4.pairs("NIFTY", "design")
    d0s = [x[0] for x in p]
    assert date(2026, 5, 7) not in d0s                         # the contract's own expiry day: no entry
    assert all(d1 <= ex for _, d1, ex, _ in p)
    assert (date(2026, 5, 6), date(2026, 5, 7), date(2026, 5, 7), True) in p   # day before expiry -> exit on expiry
    assert (date(2026, 5, 8), date(2026, 5, 11), date(2026, 5, 14), False) in p  # Friday -> Monday, next contract
