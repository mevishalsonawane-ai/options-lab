"""R66 tests: the variant table (A4 + one +5% step at +25% / +20% / +30%; B0 / A4 / N0 exactly as R65), the step's
engine semantics on R64's engine (the stop rises to +5% only from the minute after the +25% high, stays there through
the ladder's +30% breakeven step, and the ladder takes over at +60%), the look-ahead guard and the study's switch to
this config. Synthetic data only (no lake).

    python -m pytest research/hunt/r66 -q
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r51"))
sys.path.insert(0, str(HERE.parent / "r64"))
sys.path.insert(0, str(HERE.parent / "r65"))
sys.path.insert(0, str(HERE))

import r51cfg as K  # noqa: E402
import r64engine as G  # noqa: E402
import r65cfg as R65  # noqa: E402
import r66cfg as R  # noqa: E402

N = K.NROWS


def steps(v, arm="Donchian"):
    return np.array(R.exit_spec(R.OWN[arm], v)["steps"], dtype=np.float64).reshape(-1, 2)


def sim(C, st, stop=0.15, e=10, be=100.8):
    return G.simulate(C, e, K.SQ_ROW, -1, stop, -1.0, -1.0, 0.0, 0.0, st, -1, -1.0, -1.0, K.TICK, 75.0, be)


def flat(px=100.0):
    a = np.full((5, N), px)
    a[4] = 1000.0
    return a


def test_variant_table():
    for arm in ("Donchian", "OOV", "S1", "S2", "S3", "S4"):
        own = R.OWN[arm]
        a4 = R65.exit_spec(own, "A4")
        for v, trig in R.TRIGGER.items():
            sp = R.exit_spec(own, v)
            assert sp["steps"] == a4["steps"] + ((trig, 0.05),)
            assert {k: sp[k] for k in ("stop", "target", "tmax", "sq")} == {k: a4[k] for k in ("stop", "target", "tmax", "sq")}
        for v in ("B0", "A4", "N0"):
            assert R.exit_spec(own, v) == R65.exit_spec(own, v)
    assert R.PLAIN == {"L25": "A4", "L20": "A4", "L30": "A4"} and R.SEED == 66
    assert len(R.VARIANTS) * len(R.ARMS) == 15


def test_plus25_then_fall_exits_at_plus5_not_at_the_stop():
    C = flat()
    C[:4, 12:] = 120.0
    C[1, 12] = 125.0                       # best +25%
    C[:4, 14:] = 84.0                      # then a crash through everything: gap fill at the open
    C[0, 14] = 103.0
    out_a4 = sim(C, steps("A4"))           # A4: no lock below +30%, the -15% stop
    assert G.E.REASONS[out_a4[6]] == "stop" and out_a4[1] == pytest.approx(85.0)
    out = sim(C, steps("L25"))
    assert G.E.REASONS[out[6]] == "lock" and out[5] == 14 and out[1] == pytest.approx(103.0)   # gapped below +5%
    C[0, 14] = 110.0
    C[2, 14] = 84.0
    assert sim(C, steps("L25"))[1] == pytest.approx(105.0)                                     # touched: +5%


def test_not_active_in_the_minute_of_the_high_and_needs_plus25():
    C = flat()
    C[1, 12], C[2, 12] = 125.0, 84.0       # the +25% high and the stop in the same minute: the stop wins
    assert G.E.REASONS[sim(C, steps("L25"))[6]] == "stop"
    C = flat()
    C[:4, 12:] = 120.0
    C[1, 12] = 124.0                       # +24% only: no lock, so a fall to -15% is a stop
    C[:4, 14:] = 84.0
    assert G.E.REASONS[sim(C, steps("L25"))[6]] == "stop"
    assert G.E.REASONS[sim(C, steps("L20"))[6]] == "lock"


def test_plus5_survives_the_breakeven_step_and_the_ladder_takes_over():
    C = flat()
    C[:4, 12:] = 130.0
    C[1, 12] = 131.0                       # past +30%: the ladder's breakeven (100.8) is below +5%
    C[2, 15], C[0, 15] = 104.0, 106.0
    assert sim(C, steps("L25"))[1] == pytest.approx(105.0)
    C = flat()
    C[:4, 12:] = 150.0
    C[1, 12] = 161.0                       # past +60%: the ladder's +30% lock is higher
    C[2, 15], C[0, 15] = 120.0, 140.0
    assert sim(C, steps("L25"))[1] == pytest.approx(130.0)


def test_lookahead_guard():
    rng = np.random.default_rng(66)
    for _ in range(40):
        C = flat()
        w = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, N)))
        C[0], C[3] = w, w
        C[1], C[2] = w * (1 + np.abs(rng.normal(0, 0.01, N))), w * (1 - np.abs(rng.normal(0, 0.01, N)))
        out = sim(C, steps("L25"))
        C2 = C.copy()
        if out[5] + 1 < N:
            C2[:4, out[5] + 1:] = rng.uniform(1, 400, size=(4, N - out[5] - 1))
        out2 = sim(C2, steps("L25"))
        assert out2[5] == out[5] and out2[1] == pytest.approx(out[1]) and out2[6] == out[6]


def test_study_runs_on_this_config_and_guards_the_final_read():
    env = dict(os.environ, HUNT_CFG="r66cfg")
    code = ("import sys; sys.path.insert(0, %r); import r65study as S; print(S.R.NAME, S.RUN)" % str(HERE.parent / "r65"))
    out = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True, check=True).stdout
    assert out.startswith("R66") and "'L25'" in out and "'N0'" in out
    assert R.FINAL_FLAG.parent == HERE / "results"
