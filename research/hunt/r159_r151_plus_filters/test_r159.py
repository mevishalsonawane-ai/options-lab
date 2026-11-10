"""R159 tests (PREREG section 4). The filters and their look-ahead guard are tested in r155_scalp_filters/test_r155.py."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r159study as S  # noqa: E402


def test_tests_and_names():
    assert len(S.TESTS) == 22 and S.TESTS[:19] == tuple(f"F{i}" for i in range(1, 20)) and S.N_PRIOR + 22 == 2154
    assert set(S.NAME) == set(S.TESTS)
    assert S.COMBOS["C1"] == ("F3", "F9") and "F6" in S.COMBOS["C2"]


def test_passes():
    f = {f"F{i}": i % 2 == 1 for i in range(1, 20)}            # odd filters pass
    assert S.passes("F3", f) and not S.passes("F6", f)
    assert S.passes("C1", f)                                    # F3 and F9
    assert not S.passes("C2", f) and not S.passes("C3", f)
    assert not S.passes("F3", None)                             # unreadable = skipped


def test_combined_scores_picker_and_filter():
    sc = {"a": 0.9, "b": 0.9, "c": 0.1}                         # d is unscored
    out = S.combined_scores(sc, ["a", "b", "c", "d"], lambda k: k in ("a", "c", "d"), 0.4656)
    assert out["a"] == 0.9 and out["b"] < 0.4656 and out["c"] == 0.1 and "d" not in out
    kept = [k for k in "abcd" if out.get(k, -1) >= 0.4656]      # R151's walk rule
    assert kept == ["a"]


def test_better_and_gates():
    base = dict(trades=652, win=0.489, net=219057)
    good = dict(trades=400, win=0.52, net=230000)
    assert S.better(good, base)
    assert not S.better(dict(trades=400, win=0.55, net=200000), base)      # wins more often but makes less
    assert not S.better(dict(trades=400, win=0.48, net=250000), base)
    assert S.gates(good, good, base, base, 0.05)["passed"]
    g = S.gates(good, dict(trades=30, win=0.6, net=250000), base, base, 0.5)
    assert not g["passed"] and len(g["fails"]) == 2
