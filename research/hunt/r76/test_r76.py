"""R76 tests (18 Oct 2026): R76's engine equals R64's when the index trigger is off; the index trigger flags (call /
put, only from the fill minute, no target -> none); the look-ahead guard (the lock acts only from the minute AFTER the
trigger minute, rows before the fill and after the exit never matter, a forced index-exit row still wins); the
positional trade-by-trade account; the decision rule; the one-time final read. Synthetic data only.

    python -m pytest research/hunt/r76 -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
for d in ("r51", "r64", "r65", "r70"):
    sys.path.insert(0, str(HERE.parent / d))
sys.path.insert(0, str(HERE))

import r51cfg as K  # noqa: E402
import r64engine as G  # noqa: E402
import r76engine as X  # noqa: E402
import r76study as S  # noqa: E402

N = K.NROWS
NOSTEP = np.zeros((0, 2))
OFF = np.zeros(N)


def _sim(C, fx=-1, trg=OFF, tkeep=-1.0, steps=NOSTEP, stop=0.15, tmax=20, need=0.05, e=10):
    return X.simulate(C, e, K.SQ_ROW, fx, stop, -1.0, -1.0, 0.0, 0.0, steps, tmax, need, -1.0, K.TICK, 30.0, 0.0,
                      trg, tkeep)


def _g(C, fx=-1, steps=NOSTEP, stop=0.15, tmax=20, need=0.05, e=10):
    return G.simulate(C, e, K.SQ_ROW, fx, stop, -1.0, -1.0, 0.0, 0.0, steps, tmax, need, -1.0, K.TICK, 30.0, 0.0)


def _walk(seed):
    r = np.random.default_rng(seed)
    c = 100.0 * np.exp(np.cumsum(r.normal(0, 0.02, N)))
    o = np.r_[100.0, c[:-1]]
    h, lo = np.maximum(o, c) * 1.01, np.minimum(o, c) * 0.99
    v = np.full(N, 50.0)
    C = np.vstack([o, h, lo, c, v])
    C[:, r.choice(N, 15, replace=False)] = np.nan                      # gaps
    return C


@pytest.mark.parametrize("seed", range(25))
def test_engine_equals_r64_when_trigger_off(seed):
    C = _walk(seed)
    for kw in (dict(), dict(fx=40), dict(steps=np.array([[0.25, 0.05]])), dict(tmax=-1, need=-1.0), dict(stop=-1.0)):
        a = _g(C, **kw)
        assert _sim(C, **kw) == pytest.approx(a, nan_ok=True)
        flags = np.ones(N)                                              # flags but tkeep < 0: still off
        assert _sim(C, trg=flags, **kw) == pytest.approx(a, nan_ok=True)


def _idx(hi, lo):
    a = np.full((4, N), 100.0)
    a[1], a[2] = hi, lo
    return a


def test_trigger_flags_call_put_and_edges():
    hi = np.full(N, 100.0)
    lo = np.full(N, 100.0)
    hi[5], hi[20] = 130.0, 125.0                                       # row 5 is before the fill
    a = _idx(hi, lo)
    f = X.trigger_flags(a, 10, +1, 100.0, 200.0, 0.25)                  # level 125
    assert np.flatnonzero(f).tolist() == [20]
    assert X.trigger_flags(a, 10, +1, 100.0, 200.0, 0.30).sum() == 0   # level 130: only row 5, before the fill
    lo[30] = 90.0
    p = X.trigger_flags(_idx(hi, lo), 10, -1, 100.0, 60.0, 0.25)       # put: level 90
    assert np.flatnonzero(p).tolist() == [30]
    assert X.trigger_flags(a, 10, +1, 100.0, np.nan, 0.25).sum() == 0  # no target ahead
    assert X.trigger_flags(a, 10, +1, 100.0, 90.0, 0.25).sum() == 0    # index already past the target
    assert X.trigger_flags(a, -1, +1, 100.0, 200.0, 0.25).sum() == 0   # no fill
    hi2 = hi.copy()
    hi2[40] = np.nan
    assert X.trigger_flags(_idx(hi2, lo), 10, +1, 100.0, 200.0, 0.25)[40] == 0
    assert X.entry_index(a, 10, 99.0) == 100.0
    b = a.copy()
    b[0, 10] = np.nan
    assert X.entry_index(b, 10, 99.0) == 99.0


def _flat(p=100.0):
    C = np.full((5, N), p)
    C[4] = 1000.0
    return C


def test_lock_acts_only_from_the_next_minute():
    C = _flat()
    C[:4, 12] = 110.0
    C[2, 12] = 104.0                                    # minute 12 dips below +5% while the index triggers in it
    C[:4, 13:] = 110.0
    C[2, 14] = 104.0                                    # minute 14 dips below +5% again
    trg = np.zeros(N)
    trg[12] = 1.0
    out = _sim(C, trg=trg, tkeep=0.05, tmax=-1, need=-1.0)
    assert G.E.REASONS[out[6]] == "lock" and out[5] == 14 and out[1] == pytest.approx(105.0)
    plain = _sim(C, tmax=-1, need=-1.0)
    assert G.E.REASONS[plain[6]] != "lock"


def test_lookahead_rows_before_fill_and_after_exit_never_matter():
    C = _flat()
    C[:4, 12:] = 112.0
    C[2, 30] = 100.0                                    # a dip through +5% at 30
    trg = np.zeros(N)
    trg[15] = 1.0
    base = _sim(C, trg=trg, tkeep=0.05, tmax=-1, need=-1.0)
    assert base[5] == 30 and G.E.REASONS[base[6]] == "lock"
    t2 = trg.copy()
    t2[:10] = 1.0                                       # flags before the fill (row 10) are never read
    t2[31:] = 0.0
    assert _sim(C, trg=t2, tkeep=0.05, tmax=-1, need=-1.0) == pytest.approx(base)
    C2 = C.copy()
    C2[:4, 31:] = 1.0                                   # bars after the exit never matter
    assert _sim(C2, trg=trg, tkeep=0.05, tmax=-1, need=-1.0) == pytest.approx(base)
    late = np.zeros(N)
    late[30] = 1.0                                      # a trigger in the exit minute itself cannot sell it there
    assert G.E.REASONS[_sim(C, trg=late, tkeep=0.05, tmax=-1, need=-1.0)[6]] != "lock"


def test_index_exit_row_still_first():
    C = _flat()
    C[:4, 12:] = 126.0
    C[2, 16] = 100.0
    trg = np.zeros(N)
    trg[12] = 1.0
    out = _sim(C, fx=14, trg=trg, tkeep=0.05)
    assert G.E.REASONS[out[6]] == "rule_exit" and out[5] == 14


def test_with_step_keeps_everything_else():
    own = dict(stop=0.15, target=-1.0, steps=((0.4, 0.1),), tmax=20, need=0.05, sq_row=-1)
    out = S.with_step(own, 0.35)
    assert out["steps"] == ((0.4, 0.1), (0.35, 0.05))
    assert {k: out[k] for k in own if k != "steps"} == {k: own[k] for k in own if k != "steps"}


def t(net, ret=0.0, why="time", side="C"):
    return dict(und="BANKNIFTY", day=date(2026, 4, 1), side=side, strike=50000.0, e0=30, net=net, ret=ret, why=why)


def test_account_is_positional():
    b0 = [t(100.0), t(-500.0), t(200.0)]
    v = [t(100.0), t(300.0, 0.05, "lock"), t(50.0, 0.05, "lock")]
    a = S.account(b0, v)
    assert S.changed(b0, v) == [1, 2]
    assert a["changed"] == 2 and a["saved"] == 800 and a["given_up"] == 150 and a["net_effect"] == 650
    assert a["best_example"]["b0_net"] == -500 and a["worst_example"]["b0_net"] == 200
    assert S.account([], [])["worst_example"] is None
    with pytest.raises(AssertionError):
        S.account([t(1.0)], [])


def test_lock_split():
    b0 = [t(100.0), t(-500.0), t(200.0), t(10.0)]
    v = [t(100.0), dict(t(-50.0, -0.01, "lock"), E=100.0, X=99.0), dict(t(150.0, 0.05, "lock"), E=100.0, X=105.0),
         dict(t(20.0, 0.0, "time"), E=100.0, X=100.0)]
    s = S.lock_split(b0, v)
    assert s == dict(below=dict(n=1, rs=450), at5=dict(n=1, rs=-50), other=dict(n=1, rs=10))


def _res(b, v, q):
    mk = lambda xs: {ph: dict(net=x) for ph, x in zip(S.PHASES, xs)}
    return {"B0": mk(b), "T35": dict(mk(v), exit_q=q)}


def test_decide():
    assert S.decide(_res((1, 1, 1), (2, 2, 2), 0.05), "T35") == dict(recommended=True, harmless=True)
    assert S.decide(_res((1, 1, 1), (1, 1, 1), 0.5), "T35") == dict(recommended=False, harmless=True)
    assert S.decide(_res((1, 1, 1), (0, 2, 2), 0.01), "T35") == dict(recommended=False, harmless=False)


def test_final_is_read_once(tmp_path, monkeypatch):
    monkeypatch.setattr(S, "RESULTS", tmp_path)
    monkeypatch.setattr(S, "FINAL_FLAG", tmp_path / "FINAL_READ")
    with pytest.raises(SystemExit):
        S.run("final")
    (tmp_path / "FINAL_READ").write_text("x")
    (tmp_path / "final.json").write_text("{}")
    with pytest.raises(SystemExit):
        S.run("final")
    with pytest.raises(SystemExit):
        S.final()
