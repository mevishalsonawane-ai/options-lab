"""R196 tests: the pair engine reproduces R195's S1, stop to cost, the combined stop, the lock, the shock filter, the
put-spread legs, look-ahead guards and the forward lock. Hand-made days; no lake needed."""
from __future__ import annotations

import copy
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r195_option_selling"))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402
import r195rules as RU  # noqa: E402
import r196engine as E6  # noqa: E402
import r196study as S6  # noqa: E402
from test_r195 import EXP, _daily, make_day  # noqa: E402


def pair(D, **kw):
    return E6.run_short_pair(D, "NIFTY", D.d, [("C", 22000.0), ("P", 22000.0)], RU.R0920, EXP, False, **kw)


def test_reproduces_r195_s1():
    for shape in (None, "call_up", "both_up"):
        D = make_day()
        if shape:
            D.con[("C", 22000.0)][:, 50:] = 130.0
        if shape == "both_up":
            D.con[("P", 22000.0)][:, 120:] = 140.0
        a = E.run(D, "NIFTY", D.d, RU.spec_straddle(D, "NIFTY", RU.R0920, stop_leg=1.25), EXP, False)
        b = pair(D, leg_stop=1.25)
        assert b["net"] == pytest.approx(a["net"]) and b["exits"] == a["exits"] and b["why"] == a["why"]


def test_stop_to_cost_moves_the_other_leg():
    D = make_day()
    D.con[("C", 22000.0)][:, 50:] = 130.0                                  # call stopped at minute 50 -> exit 51
    D.con[("P", 22000.0)][:, 45:] = 90.0                                   # the put falls below its fill ...
    D.con[("P", 22000.0)][:, 80:] = 101.0                                  # ... then comes back above it (~99.9)
    t = pair(D, leg_stop=1.25, sl_to_cost=True)
    assert t["exits"] == [51, 81]
    t0 = pair(D, leg_stop=1.25)
    assert t0["exits"] == [51, K.SQ_ROW]                                   # without it the put runs to 15:10


def test_combined_stop_and_lock():
    D = make_day()
    D.con[("C", 22000.0)][:, 60:] = 141.0                                  # pair 241 vs ~200 -> >= 1.20x
    t = pair(D, comb_stop=1.20)
    assert t["exits"] == [61, 61] and t["why"] == "stop"
    D = make_day()
    for k in (("C", 22000.0), ("P", 22000.0)):
        D.con[k][:, 40:] = 65.0                                            # +35% of credit: armed
        D.con[k][:, 90:] = 90.0                                            # back to +10%: lock fires
    t = pair(D, leg_stop=1.25, lock=(0.30, 0.15))
    assert t["exits"] == [91, 91] and t["why"] == "lock"


def test_shock_filter_is_causal():
    Dl = _daily()
    i = 260
    a = S6.shock(Dl, i)
    Dl2 = copy.deepcopy(Dl)
    Dl2.close[i:] *= 1.3
    Dl2.high[i:] *= 1.3
    assert S6.shock(Dl2, i) == a
    Dl2.open_[i] = Dl2.close[i - 1] * 1.01                                 # today's open 1% away: a shock day
    assert S6.shock(Dl2, i)


def test_forward_is_locked(tmp_path, monkeypatch):
    monkeypatch.setattr(S6, "FORWARD_FLAG", tmp_path / "FORWARD_READ")
    (tmp_path / "FORWARD_READ").write_text("read")
    with pytest.raises(PermissionError):
        S6.forward()


def test_paired_p_counts_skipped_days_as_zero():
    base = [dict(day=date(2025, 1, i), net=v) for i, v in ((1, -500.0), (2, 300.0), (3, -200.0))]
    var = [dict(day=date(2025, 1, 2), net=300.0)]                         # skipped the two losing days
    p, gain = S6.paired_p(var, base)
    assert gain == 700 and p < 0.5
