"""R105C tests: rule table, target / half-way breakeven fills on R105's engine, selection, verdict, read-once."""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r105_donchian_giveback"))

import r105c as Cc  # noqa: E402
import r105engine as X  # noqa: E402
import r105lib as L  # noqa: E402

N = 375


def contract(path):
    C = np.full((5, N), np.nan)
    p = np.asarray(path, float)
    n = len(p)
    o, c = p.copy(), np.concatenate([p[1:], p[-1:]])
    C[0, :n], C[3, :n], C[1, :n], C[2, :n], C[4, :n] = o, c, np.maximum(o, c), np.minimum(o, c), 1000.0
    return C


def sim(C, spec, be=None):
    I = np.full((4, N), 100.0)
    st = np.array(spec["steps"], float).reshape(-1, 2)
    be = C[0, 10] * 1.01 if be is None else be
    return X.simulate(C, 10, 360, -1, 0.15, spec["target"], st, 30, -1.0, -1.0, 0.05, 75.0, be, spec["be_rs"],
                      spec["tt_off"], spec["tt_pk"], spec["gb_trig"], spec["gb_keep"], spec["idx_mode"],
                      spec["idx_arm"], math.nan, 1, I)


def test_rule_table():
    assert len(Cc.RULES) == 12 and Cc.RULES[0] == "T5" and Cc.RULES[-1] == "T20B"
    s = Cc.spec_of("T8", L.OFF, L.P_STEPS)
    assert s["target"] == pytest.approx(0.08) and s["steps"] == L.P_STEPS
    s = Cc.spec_of("T10B", L.OFF, L.P_STEPS)
    assert s["target"] == pytest.approx(0.10) and s["steps"][-1][0] == pytest.approx(0.05) and math.isnan(s["steps"][-1][1])


def test_target_fill_is_the_limit():
    C = contract([100] * 10 + [100, 104, 111, 100] + [100] * 50)
    r = sim(C, Cc.spec_of("T10", L.OFF, L.P_STEPS))
    assert r[6] == 3 and r[1] == pytest.approx(110.0) and r[3] == pytest.approx(110.0)


def test_halfway_breakeven():
    # +6% (half of T12 reached), then falls through the -15% stop: TkB sells at breakeven, Tk at the stop
    C = contract([100] * 10 + [100, 106, 100, 90, 84] + [84] * 50)
    a = sim(C, Cc.spec_of("T12", L.OFF, L.P_STEPS))
    b = sim(C, Cc.spec_of("T12B", L.OFF, L.P_STEPS))
    assert a[6] == 1 and b[6] == 2 and b[1] == pytest.approx(101.0)


def test_pick_and_verdict():
    d = {"T5": dict(win=0.70, net=-100), "T8": dict(win=0.62, net=500), "T10": dict(win=0.55, net=900)}
    assert Cc.pick(d) == dict(rule="T8", reached60=True)
    d2 = {"T5": dict(win=0.58, net=-100), "T8": dict(win=0.52, net=500)}
    assert Cc.pick(d2) == dict(rule="T5", reached60=False)
    assert Cc.verdict(dict(win=0.61, net=10), dict(win=0.6, net=5)) == "meets 60%"
    assert Cc.verdict(dict(win=0.61, net=10), dict(win=0.6, net=-5)) == "60% but losing"
    assert Cc.verdict(dict(win=0.61, net=10), dict(win=0.59, net=5)) == "not reached"


def test_final_read_once(tmp_path, monkeypatch):
    flag = tmp_path / "FINAL_READ"
    flag.write_text("x")
    monkeypatch.setattr(Cc, "FINAL_FLAG", flag)
    with pytest.raises(SystemExit):
        Cc.final()


def test_s10_rules_and_stop():
    assert Cc.S10_RULES[0] == "S10" and len(Cc.S10_RULES) == 7
    assert Cc.s10_spec("S10", L.OFF, L.P_STEPS)["target"] == -1.0
    assert Cc.s10_spec("S10T8", L.OFF, L.P_STEPS)["target"] == pytest.approx(0.08)
    # a -10% stop: a fall to 89 stops at 90 (the -15% stop would not)
    C = contract([100] * 10 + [100, 95, 89] + [89] * 50)
    I = np.full((4, N), 100.0)
    st = np.array(L.P_STEPS, float).reshape(-1, 2)
    r = X.simulate(C, 10, 360, -1, 0.10, -1.0, st, 30, -1.0, -1.0, 0.05, 75.0, 101.0, -1.0, -1, 0.0, -1.0, 0.0, 0, 0.0,
                   math.nan, 1, I)
    assert r[6] == 1 and r[1] == pytest.approx(90.0)
