"""R178 tests (synthetic): the variant predicates, the selection rule, the mask helper, the BH count, look-ahead."""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
T = pytest.importorskip("r178study")

N = 375
LV = dict(vah=110.0, val=90.0, pdh=120.0, pdl=80.0, poc=100.0)


def _c(**rows):
    c = np.full(N, 100.0)
    for r, x in rows.items():
        c[int(r[1:])] = x
    return c


def test_windows():
    c = _c(r60=112.0, r62=108.0)                                   # failed VAH break 40 / 38 rows before row 100
    assert not T.skip(c, 100, "C", LV, T.SPEC["K1"])              # 30-row window misses it
    assert not T.skip(c, 100, "C", LV, T.SPEC["K2"])
    assert T.skip(c, 100, "C", LV, T.SPEC["K3"])                  # 60 rows sees it
    assert T.skip(c, 100, "C", LV, T.SPEC["K4"])                  # whole day sees it
    c = _c(r90=112.0, r91=108.0)
    assert all(T.skip(c, 100, "C", LV, T.SPEC[v]) for v in ("K1", "K2", "K3", "K4", "K6"))
    assert not T.skip(c, 100, "C", LV, T.SPEC["K5"])              # never above PDH 120


def test_day_level_and_or():
    c = _c(r90=122.0)                                              # failed PDH break; stays above VAH until the signal
    c[91:100] = 118.0
    assert T.skip(c, 100, "C", LV, T.SPEC["K5"]) and T.skip(c, 100, "C", LV, T.SPEC["K6"])
    assert not T.skip(c, 100, "C", LV, T.SPEC["K1"])              # 118 is still above VAH 110: no VA failure
    p = _c(r90=78.0)                                               # put mirror on PDL
    p[91:100] = 82.0
    assert T.skip(p, 100, "P", LV, T.SPEC["K5"]) and not T.skip(p, 100, "C", LV, T.SPEC["K5"])
    assert not T.skip(c, 100, "C", None, T.SPEC["K6"])            # no levels: no skip


def test_choose():
    assert T.choose({"K1": (1, 1, 5), "K2": (2, 3, 9), "K3": (-1, 9, 20)}) == "K2"   # K3 fails H1
    assert T.choose({"K1": (1, 1, 5), "K2": (1, 1, 5)}) == "K1"                     # tie: the earlier
    assert T.choose({"K1": (0, 1, 5), "K2": (1, 0, 5)}) is None                     # none in both halves
    assert T.choose({}) is None


def test_mask_units():
    us = [(("NIFTY", 1), [{"a": 1}, {"a": 2}, {"a": 3}], ["r1", "r2", "r3"], "h")]
    pr = [["p1", "p2", "p3"]]
    nu, np_ = T.mask_units(us, pr, [[True, False, True]])
    assert [c["a"] for c in nu[0][1]] == [1, 3] and nu[0][2] == ["r1", "r3"] and np_ == [["p1", "p3"]] and nu[0][3] == "h"


def test_counts():
    assert T.N_SEL == 18 and T.m_total(3) == 4012 + 18 + 3


@pytest.mark.parametrize("seed", range(8))
def test_look_ahead_guard(seed):
    rng = np.random.default_rng(seed)
    c = 100 + np.cumsum(rng.normal(0, 3, N))
    for v in T.VARIANTS:
        k = T.skip(c, 150, "C", LV, T.SPEC[v])
        d = c.copy()
        d[150:] = rng.normal(0, 100, N - 150)
        assert T.skip(d, 150, "C", LV, T.SPEC[v]) == k
