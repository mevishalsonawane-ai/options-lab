"""R70 tests: the L25 exits added to an arm's own (kept steps, nothing else changed), the positional trade-by-trade
account (shared keys never collide), the decision rule, the one-time final read, and the look-ahead guard of the step on
an index-exit arm (a forced index-exit row still wins when it comes first). Synthetic data only.

    python -m pytest research/hunt/r70 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
for d in ("r51", "r64", "r65"):
    sys.path.insert(0, str(HERE.parent / d))
sys.path.insert(0, str(HERE))

import r51cfg as K  # noqa: E402
import r64engine as G  # noqa: E402
import r70study as S  # noqa: E402

N = K.NROWS


def test_with_l25_keeps_everything_else():
    own = dict(stop=0.15, target=-1.0, steps=((0.4, 0.1),), tmax=20, need=0.05, sq_row=-1)
    out = S.with_l25(own)
    assert out["steps"] == ((0.4, 0.1), (0.25, 0.05))
    assert {k: out[k] for k in own if k != "steps"} == {k: own[k] for k in own if k != "steps"}
    assert S.with_l25(dict(sq_row=K.row(14, 30)))["steps"] == ((0.25, 0.05),)


def t(net, und="BANKNIFTY", side="C", e0=30, ret=0.0, why="time"):
    return dict(und=und, day=date(2026, 4, 1), side=side, e0=e0, net=net, ret=ret, why=why)


def test_account_is_positional_and_handles_shared_keys():
    b0 = [t(100.0), t(-500.0), t(200.0)]                  # all three share und / day / side / entry row
    l25 = [t(100.0), t(300.0, ret=0.05, why="lock"), t(50.0, ret=0.05, why="lock")]
    a = S.account(b0, l25)
    assert a["changed"] == 2 and a["n_better"] == 1 and a["saved"] == 800 and a["n_worse"] == 1 and a["given_up"] == 150
    assert a["net_effect"] == 650 and a["best_example"]["b0_net"] == -500 and a["worst_example"]["b0_net"] == 200
    e = S.account([], [])
    assert e["changed"] == 0 and e["worst_example"] is None
    with pytest.raises(AssertionError):
        S.account([t(1.0)], [])


def _arm(b, l, q):
    mk = lambda xs: {ph: dict(net=x) for ph, x in zip(("design", "wf", "final"), xs)}
    return {"B0": mk(b), "L25": dict(mk(l), exit_q=q)}


def test_decide():
    assert S.decide(_arm((1, 1, 1), (2, 2, 2), 0.05)) == dict(recommended="L25", harmless=True)
    assert S.decide(_arm((1, 1, 1), (2, 2, 2), 0.5)) == dict(recommended=None, harmless=True)
    assert S.decide(_arm((1, 1, 1), (0, 2, 2), 0.01)) == dict(recommended=None, harmless=False)


def test_index_exit_row_still_first_and_lookahead():
    C = np.full((5, N), 100.0)
    C[4] = 1000.0
    C[:4, 12:] = 126.0
    C[1, 12] = 126.0                                       # +26%: the step arms +5% from minute 13
    st = np.array([[0.25, 0.05]])
    out = G.simulate(C, 10, K.SQ_ROW, 14, 0.15, -1.0, -1.0, 0.0, 0.0, st, 20, 0.05, -1.0, K.TICK, 75.0, 0.0)
    assert G.E.REASONS[out[6]] == "rule_exit" and out[5] == 14       # the index exit at 14 comes first
    C2 = C.copy()
    C2[:4, 15:] = 1.0                                      # bars after the exit never matter
    out2 = G.simulate(C2, 10, K.SQ_ROW, 14, 0.15, -1.0, -1.0, 0.0, 0.0, st, 20, 0.05, -1.0, K.TICK, 75.0, 0.0)
    assert out2[:2] == pytest.approx(out[:2]) and out2[5] == out[5]


def test_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(SystemExit):
        S.run("liq", "final")
    (tmp_path / "FINAL_READ").write_text("x")
    (tmp_path / "final_liq.json").write_text("{}")
    with pytest.raises(SystemExit):
        S.run("liq", "final")
    with pytest.raises(SystemExit):
        S.final()
