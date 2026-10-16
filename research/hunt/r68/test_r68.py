"""R68 tests: the staircase tables (reading 1 on profit, reading 2 on progress to the target, never above its trigger),
the variant table, the moving-target engine (parity with R64's when off; the target rises 5 points a step from the next
minute; WM switched on only for WM), the look-ahead guard and the study hook. Synthetic data only.

    python -m pytest research/hunt/r68 -q
"""
from __future__ import annotations

import math
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
import r68cfg as R  # noqa: E402
import r68engine as M  # noqa: E402

N = K.NROWS


def flat(px=100.0):
    a = np.full((5, N), px)
    a[4] = 1000.0
    return a


def walk(rng):
    C = flat()
    w = 100 * np.exp(np.cumsum(rng.normal(0, 0.02, N)))
    C[0], C[3] = w * (1 + rng.normal(0, 0.003, N)), w
    C[1] = np.maximum(C[0], C[3]) * (1 + np.abs(rng.normal(0, 0.01, N)))
    C[2] = np.minimum(C[0], C[3]) * (1 - np.abs(rng.normal(0, 0.01, N)))
    C[4] = rng.integers(0, 5000, N)
    return C


def arr(steps):
    return np.array(steps, dtype=np.float64).reshape(-1, 2)


def msim(C, st, tgt=-1.0, mt=-1.0, stop=0.15, tmax=-1):
    return M.simulate(C, 10, K.SQ_ROW, -1, stop, tgt, -1.0, 0.0, 0.0, st, tmax, -1.0, -1.0, K.TICK, 75.0, 100.8,
                      mt, 0.05)


def test_staircase_reading1():
    s = R.staircase()
    assert s[0] == (0.25, -0.10) and s[1] == (0.30, -0.05) and math.isnan(s[2][1]) and s[3] == (0.40, 0.05)
    assert s[15] == (1.0, 0.65) and all(abs(g - lk - 0.35) < 1e-9 for g, lk in s if not math.isnan(lk))


def test_progress_staircase_reading2_never_above_its_trigger():
    s = R.progress_staircase(0.30)
    assert s[0] == (0.075, -0.10) and s[1] == (0.09, -0.05) and math.isnan(s[2][1]) and s[3] == (0.12, 0.05)
    assert s[4] == (0.135, 0.085)
    assert all(lk <= g - 0.05 + 1e-9 for g, lk in s if not math.isnan(lk))
    assert R.progress_staircase(0.50)[0] == (0.125, -0.10)


def test_variant_table():
    for arm in ("Donchian", "OOV", "S1", "S2", "S3", "S4"):
        own = R.OWN[arm]
        b0 = R65.exit_spec(own, "B0")
        for v in R.TRAILS:
            sp = R.exit_spec(own, v)
            assert (sp["stop"], sp["tmax"], sp["sq"]) == (b0["stop"], b0["tmax"], b0["sq"])
        assert R.exit_spec(own, "W")["target"] is None and R.exit_spec(own, "WT")["target"] == own.get("target")
        assert R.exit_spec(own, "WM")["target"] == (own.get("target") or 0.30)
        assert set(R.R64.LADDER) <= set(R.exit_spec(own, "WA")["steps"])
        for v in ("B0", "A4", "N0"):
            assert R.exit_spec(own, v) == R65.exit_spec(own, v)
    with pytest.raises(ValueError):
        R.exit_spec(R.OWN["OOV"], "WX")


def test_engine_parity_when_moving_target_off():
    rng = np.random.default_rng(68)
    st = arr(R.staircase())
    for _ in range(80):
        C = walk(rng)
        a = G.simulate(C, 10, K.SQ_ROW, -1, 0.15, 0.30, -1.0, 0.0, 0.0, st, 30, -1.0, -1.0, K.TICK, 75.0, 100.8)
        b = msim(C, st, tgt=0.30, tmax=30)
        assert np.allclose(np.array(a, float), np.array(b, float), equal_nan=True)


def test_moving_target_rises_from_the_next_minute():
    C = flat()
    C[:4, 12:] = 126.0
    C[1, 12] = 126.0                       # +26%: one step -> target 30% + 5% = 135
    C[1, 14] = 131.0                       # above the old 130 target, below 135: no exit; +31% = two steps -> 140
    C[1, 16] = 141.0
    out = msim(C, arr(R.staircase()), tgt=0.30, mt=0.25)
    assert G.E.REASONS[out[6]] == "target" and out[5] == 16 and out[1] == pytest.approx(140.0)
    fixed = msim(C, arr(R.staircase()), tgt=0.30)               # off: the 130 target at minute 14
    assert fixed[5] == 14 and fixed[1] == pytest.approx(130.0)
    C2 = flat()
    C2[1, 12] = 131.0                      # through 130 in the minute that would lift it: the old target wins
    assert msim(C2, arr(R.staircase()), tgt=0.30, mt=0.25)[1] == pytest.approx(130.0)


def test_staircase_stop_steps():
    C = flat()
    C[:4, 12:] = 131.0
    C[1, 12] = 131.0                       # +31%: stop -5%
    C[2, 15], C[0, 15] = 94.0, 120.0
    out = msim(C, arr(R.staircase()))
    assert out[5] == 15 and out[1] == pytest.approx(95.0)


def test_install_and_switch():
    R.before_variant("WM")
    assert G.simulate_many is M.simulate_many and M.MOVING == R.MT
    R.before_variant("W")
    assert M.MOVING is None


def test_lookahead_guard():
    rng = np.random.default_rng(6)
    st = arr(R.staircase())
    for _ in range(40):
        C = walk(rng)
        out = msim(C, st, tgt=0.30, mt=0.25)
        C2 = C.copy()
        if out[5] + 1 < N:
            C2[:4, out[5] + 1:] = rng.uniform(1, 400, size=(4, N - out[5] - 1))
        out2 = msim(C2, st, tgt=0.30, mt=0.25)
        assert out2[5] == out[5] and out2[1] == pytest.approx(out[1]) and out2[6] == out[6]


def test_study_runs_on_this_config():
    env = dict(os.environ, HUNT_CFG="r68cfg", HUNT_CFG_DIR=str(HERE))
    code = "import sys; sys.path.insert(0, %r); import r65study as S; print(S.R.NAME, S.RUN)" % str(HERE.parent / "r65")
    out = subprocess.run([sys.executable, "-c", code], env=env, capture_output=True, text=True, check=True).stdout
    assert out.startswith("R68") and "'WM'" in out
