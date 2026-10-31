"""Tests for R139 Amendment B (PREREG_B.md): the flip (opposite side, same stop distance), stop-and-reverse, the lock
and the look-ahead guard."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r139b as F  # noqa: E402
from test_r131 import random_grid, truncate  # noqa: E402

R, B = F.R, F.B


def test_flip_mirrors_the_side_and_keeps_the_stop_distance():
    g = random_grid(np.random.default_rng(1), 2)
    S = R.sides_of(g)
    ref = S[1].mo[0][40]
    out = F.flip(S, [(1, B.C(0, 40, ref - 7.0))])
    assert len(out) == 1
    sd, c = out[0]
    assert sd == -1 and c.r0 == 40 and c.tgt is None
    ref2 = S[-1].mo[0][40]
    assert ref2 == pytest.approx(-ref)
    assert ref2 - c.stop == pytest.approx(7.0)            # the same distance, on the other side
    assert F.flip(S, [(1, B.C(0, 40, ref + 1.0))]) == []   # entry already beyond its stop: dropped
    assert F.flip(S, []) == []


def test_stop_and_reverse_after_a_stop_only():
    g = random_grid(np.random.default_rng(2), 2)
    S = R.sides_of(g)
    ref = S[1].mo[1][60]
    T = F.sequence_sar(S, [(1, B.C(1, 60, ref - 0.3))], set(), g.days, lambda d: True)   # a tight stop: stopped
    assert T[0]["why"] == "stop" and not T[0]["rev"]
    if len(T) > 1:
        r = T[1]
        assert r["rev"] and r["side"] == -1 and r["entry"] == T[0]["exit"] + 1
        assert r["stop_pts"] == pytest.approx(S[-1].mo[1][r["entry"]] - R.stop_below(-ref))
    T2 = F.sequence_sar(S, [(1, B.C(1, 60, ref - 1e6, ref + 0.5))], set(), g.days, lambda d: True)  # target hit
    assert len(T2) == 1 and T2[0]["why"] == "target"


def test_lock_once(tmp_path, monkeypatch):
    monkeypatch.setattr(F, "OUT", tmp_path / "o")
    monkeypatch.setattr(F, "RESULTS", tmp_path / "r")
    with pytest.raises(R.HoldoutError):
        F.lock("start")
    (tmp_path / "r").mkdir()
    (tmp_path / "r" / "design_b.json").write_text("{}")
    F.lock("start")
    F.lock("done")
    with pytest.raises(R.HoldoutError):
        F.lock("start")


@pytest.mark.parametrize("v", ["F1", "F3", "F7", "F8"])
def test_lookahead_guard_flip(v):
    rng = np.random.default_rng(1392)
    g = random_grid(rng, 30)
    var = B.VARIANTS[F.VARIANTS[v]["base"]]
    full = F.flip(R.sides_of(g), B.candidates(R.sides_of(g), var))
    assert full
    for k in rng.choice(len(full), size=min(5, len(full)), replace=False):
        sd, c = full[k]
        St = R.sides_of(truncate(g, c.d, c.r0 + 1))         # the entry minute's open is the fill: keep that minute
        again = F.flip(St, B.candidates(St, var))
        assert (sd, c.d, c.r0, round(c.stop, 6)) in [(s, x.d, x.r0, round(x.stop, 6)) for s, x in again]
