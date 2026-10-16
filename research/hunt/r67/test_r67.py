"""R67 tests: the variant table (A4 + one -10% step at +25% / +20% / +15%; B0 / A4 / N0 exactly as R65), the step's
engine semantics on R64's engine (the stop rises from -15% to -10% from the minute after the +25% high, the ladder's +30%
breakeven step then takes over), the look-ahead guard and the study's switch to this config. Synthetic data only.

    python -m pytest research/hunt/r67 -q
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
import r67cfg as R  # noqa: E402

N = K.NROWS


def steps(v, arm="Donchian"):
    return np.array(R.exit_spec(R.OWN[arm], v)["steps"], dtype=np.float64).reshape(-1, 2)


def sim(C, st, stop=0.15, be=100.8):
    return G.simulate(C, 10, K.SQ_ROW, -1, stop, -1.0, -1.0, 0.0, 0.0, st, -1, -1.0, -1.0, K.TICK, 75.0, be)


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
            assert sp["steps"] == a4["steps"] + ((trig, -0.10),)
            assert {k: sp[k] for k in ("stop", "target", "tmax", "sq")} == {k: a4[k] for k in ("stop", "target", "tmax", "sq")}
        for v in ("B0", "A4", "N0"):
            assert R.exit_spec(own, v) == R65.exit_spec(own, v)
    assert R.SEED == 67 and R.NAME == "R67" and R.OUT.name == "r67"


def test_plus25_then_fall_exits_at_minus10_not_minus15():
    C = flat()
    C[:4, 12:] = 120.0
    C[1, 12] = 125.0
    C[:4, 14:] = 80.0
    C[0, 14] = 95.0                        # gap from 120 to 95 (above -10%), low 80
    a4 = sim(C, steps("A4"))
    assert G.E.REASONS[a4[6]] == "stop" and a4[1] == pytest.approx(85.0)
    m = sim(C, steps("M25"))
    assert G.E.REASONS[m[6]] == "lock" and m[5] == 14 and m[1] == pytest.approx(90.0)
    C[0, 14] = 86.0                        # gap below -10%: fills at the worse open
    assert sim(C, steps("M25"))[1] == pytest.approx(86.0)


def test_needs_plus25_and_not_in_the_same_minute():
    C = flat()
    C[:4, 12:] = 120.0
    C[1, 12] = 124.0
    C[:4, 14:] = 80.0
    assert G.E.REASONS[sim(C, steps("M25"))[6]] == "stop"
    assert G.E.REASONS[sim(C, steps("M20"))[6]] == "lock"
    C = flat()
    C[1, 12], C[2, 12] = 125.0, 84.0
    assert G.E.REASONS[sim(C, steps("M25"))[6]] == "stop"


def test_ladder_breakeven_takes_over_at_plus30():
    C = flat()
    C[:4, 12:] = 130.0
    C[1, 12] = 131.0
    C[2, 15], C[0, 15] = 99.0, 120.0
    assert sim(C, steps("M25"))[1] == pytest.approx(100.8)


def test_lookahead_guard():
    rng = np.random.default_rng(67)
    for _ in range(40):
        C = flat()
        w = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, N)))
        C[0], C[3] = w, w
        C[1], C[2] = w * (1 + np.abs(rng.normal(0, 0.01, N))), w * (1 - np.abs(rng.normal(0, 0.01, N)))
        out = sim(C, steps("M25"))
        C2 = C.copy()
        if out[5] + 1 < N:
            C2[:4, out[5] + 1:] = rng.uniform(1, 400, size=(4, N - out[5] - 1))
        out2 = sim(C2, steps("M25"))
        assert out2[5] == out[5] and out2[1] == pytest.approx(out[1]) and out2[6] == out[6]


def test_study_runs_on_this_config():
    env = dict(os.environ, HUNT_CFG="r67cfg", HUNT_CFG_DIR=str(HERE))
    code = "import sys; sys.path.insert(0, %r); import r65study as S; print(S.R.NAME, S.RUN)" % str(HERE.parent / "r65")
    out = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True, check=True).stdout
    assert out.startswith("R67") and "'M25'" in out
