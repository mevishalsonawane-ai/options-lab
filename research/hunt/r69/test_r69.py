"""R69 tests: the 1-point trail table (+5% at the start, +1 point per +1 point, 20 / 15 / 25 points below the best), the
variant table (A4 + the trail; B0 / A4 / N0 as R65), the engine semantics on R64's engine (the stop climbs a point per
point from the minute after each high, never falls, A4's ladder never binds above the start), the look-ahead guard and
the study's switch to this config. Synthetic data only.

    python -m pytest research/hunt/r69 -q
"""
from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
for d in ("r51", "r64", "r65"):
    sys.path.insert(0, str(HERE.parent / d))
sys.path.insert(0, str(HERE))

import r51cfg as K  # noqa: E402
import r64engine as G  # noqa: E402
import r65cfg as R65  # noqa: E402
import r69cfg as R  # noqa: E402

N = K.NROWS


def steps(v, arm="Donchian"):
    return np.array(R.exit_spec(R.OWN[arm], v)["steps"], dtype=np.float64).reshape(-1, 2)


def sim(C, st, stop=0.15):
    return G.simulate(C, 10, K.SQ_ROW, -1, stop, -1.0, -1.0, 0.0, 0.0, st, -1, -1.0, -1.0, K.TICK, 75.0, 100.8)


def flat(px=100.0):
    a = np.full((5, N), px)
    a[4] = 1000.0
    return a


def test_trail_table():
    t = R.trail(0.25)
    assert t[0] == (0.25, 0.05) and t[1] == (0.26, 0.06) and t[35] == (0.60, 0.40) and t[75] == (1.0, 0.80)
    assert len(t) == 976 and all(abs(g - k - 0.20) < 1e-9 for g, k in t)
    assert R.trail(0.20)[0] == (0.20, 0.05) and abs(R.trail(0.30)[5][0] - R.trail(0.30)[5][1] - 0.25) < 1e-9


def test_variant_table():
    for arm in ("Donchian", "OOV", "S1", "S2", "S3", "S4"):
        own = R.OWN[arm]
        a4 = R65.exit_spec(own, "A4")
        for v, s0 in R.START.items():
            sp = R.exit_spec(own, v)
            assert sp["steps"] == a4["steps"] + R.trail(s0)
            assert {k: sp[k] for k in ("stop", "target", "tmax", "sq")} == {k: a4[k] for k in ("stop", "target", "tmax", "sq")}
        for v in ("B0", "A4", "N0"):
            assert R.exit_spec(own, v) == R65.exit_spec(own, v)
    assert R.SEED == 69 and R.OUT.name == "r69"


@pytest.mark.parametrize("best, lock", [(125.0, 105.0), (126.0, 106.0), (127.5, 107.0), (160.0, 140.0)])
def test_stop_climbs_a_point_per_point(best, lock):
    C = flat()
    C[:4, 12:] = best - 1
    C[1, 12] = best
    C[2, 15], C[0, 15] = lock - 3, best - 1
    out = sim(C, steps("X25"))
    assert out[5] == 15 and out[1] == pytest.approx(lock)


def test_below_start_no_lock_and_not_in_the_same_minute():
    C = flat()
    C[:4, 12:] = 120.0
    C[1, 12] = 124.5
    C[:4, 14:] = 80.0
    assert G.E.REASONS[sim(C, steps("X25"))[6]] == "stop"
    C = flat()
    C[1, 12], C[2, 12] = 130.0, 84.0
    assert G.E.REASONS[sim(C, steps("X25"))[6]] == "stop"


def test_lookahead_guard():
    rng = np.random.default_rng(69)
    for _ in range(40):
        C = flat()
        w = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, N)))
        C[0], C[3] = w, w
        C[1], C[2] = w * (1 + np.abs(rng.normal(0, 0.01, N))), w * (1 - np.abs(rng.normal(0, 0.01, N)))
        out = sim(C, steps("X25"))
        C2 = C.copy()
        if out[5] + 1 < N:
            C2[:4, out[5] + 1:] = rng.uniform(1, 400, size=(4, N - out[5] - 1))
        out2 = sim(C2, steps("X25"))
        assert out2[5] == out[5] and out2[1] == pytest.approx(out[1]) and out2[6] == out[6]


def test_study_runs_on_this_config():
    env = dict(os.environ, HUNT_CFG="r69cfg", HUNT_CFG_DIR=str(HERE))
    code = "import sys; sys.path.insert(0, %r); import r65study as S; print(S.R.NAME, S.RUN)" % str(HERE.parent / "r65")
    out = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True, check=True).stdout
    assert out.startswith("R69") and "'X25'" in out
