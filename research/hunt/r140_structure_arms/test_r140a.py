"""Tests for R140 Amendment A (PREREG_A.md): the features read only the past, the slices, the bad-slice rule and the
drop filter."""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r140a as A  # noqa: E402
from test_r131 import random_grid, truncate  # noqa: E402

M = A.M
CTX = dict(f_gap=0.002, f_rv=0.5, f_vixchg=0.01, f_breadth=3, f_vixreg="HI", f_dow=2)


def ix_of(g):
    return M.from_arrays(g.o, g.h, g.l, g.c, g.days)


def test_features_signs_and_context():
    g = random_grid(np.random.default_rng(7), 25)
    ix = ix_of(g)
    dy = A.Days(ix)
    fc = A.features(ix, dy, g.days[22], 120, "C", CTX)
    fp = A.features(ix, dy, g.days[22], 120, "P", CTX)
    assert fc["A1"] == pytest.approx(-fp["A1"]) and fc["A3"] == pytest.approx(-fp["A3"])
    assert fc["A6"] == pytest.approx(1 - fp["A6"])
    assert fc["C1"] == pytest.approx(0.002) and fp["C1"] == pytest.approx(-0.002)
    assert fc["A5"] == 120 and fc["C5"] == "HI" and fc["C6"] == 2
    assert 0 <= fc["A11"] <= 3 and fc["A2"] > 0
    assert np.isnan(A.features(ix, dy, date(1999, 1, 1), 120, "C", CTX)["A1"])
    assert np.isnan(A.features(ix, dy, g.days[2], 120, "C", CTX)["A2"])   # no 20-session ADR yet


def test_features_read_only_the_past():
    rng = np.random.default_rng(1401)
    g = random_grid(rng, 26)
    ix, dy = ix_of(g), None
    dy = A.Days(ix)
    for _ in range(10):
        d = int(rng.integers(21, 26))
        er = int(rng.integers(20, 360))
        side = "C" if rng.random() < 0.5 else "P"
        f = A.features(ix, dy, g.days[d], er, side, CTX)
        it = ix_of(truncate(g, d, er))
        ft = A.features(it, A.Days(it), g.days[d], er, side, CTX)
        for k in A.FEATS:
            a, b = f[k], ft[k]
            if isinstance(a, float) and np.isnan(a):
                assert isinstance(b, float) and np.isnan(b), k
            else:
                assert a == pytest.approx(b), k


def test_slices_and_bad_slice_rule():
    ed = A.edges(list(range(100)))
    assert A.slice_of("A1", -5, ed) == 0 and A.slice_of("A1", 500, ed) == 4
    assert A.slice_of("A1", np.nan, ed) is None and A.slice_of("A9", -1.0, ed) == -1.0
    assert A.slice_of("C5", "HI", ed) == "HI" and A.slice_of("C5", None, ed) is None

    def row(n, avg):
        return dict(n=n, avg=avg)
    tab = {0: dict(all=row(80, -300), H1=row(40, -200), H2=row(40, -400)),
           2: dict(all=row(80, -900), H1=row(40, -900), H2=row(40, -900)),     # a middle quintile: never chosen
           4: dict(all=row(80, 100), H1=row(40, 50), H2=row(40, 150))}
    arm = dict(all=50.0, H1=40.0, H2=60.0)
    assert A.bad_slice("A1", tab, arm) == 0
    tab[0]["H1"] = row(20, -200)                                              # too few in one half
    assert A.bad_slice("A1", tab, arm) is None
    tab[0]["H1"] = row(40, 10)                                                # not losing in one half
    assert A.bad_slice("A1", tab, arm) is None
    cat = {"HI": dict(all=row(90, -50), H1=row(45, -40), H2=row(45, -60)),
           "LO": dict(all=row(90, 80), H1=row(45, 80), H2=row(45, 80))}
    assert A.bad_slice("C5", cat, arm) == "HI"


def test_drop_fn():
    feats = {("NIFTY", date(2025, 1, 2), 5, "C", None): dict(A1=-3.0, C5="HI"),
             ("NIFTY", date(2025, 1, 2), 40, "C", None): dict(A1=3.0, C5="LO")}
    ed = np.array([-1.0, 0.0, 1.0, 2.0])
    kf = A.drop_fn(feats, [("A1", 0, ed)])
    key = ("NIFTY", date(2025, 1, 2), None)
    assert kf(0, 0, key, dict(er=5, side="C")) is False
    assert kf(0, 1, key, dict(er=40, side="C")) is True
    assert kf(0, 2, key, dict(er=99, side="C")) is True                       # unknown: kept
    kf2 = A.drop_fn(feats, [("A1", 0, ed), ("C5", "LO", ed)])
    assert kf2(0, 1, key, dict(er=40, side="C")) is False
    rs = A.parse_rules([["A1", "0", [-1, 0, 1, 2]], ["C5", "HI", []], ["C6", "3", []]])
    assert rs[0][1] == 0 and rs[1][1] == "HI" and rs[2][1] == 3.0
