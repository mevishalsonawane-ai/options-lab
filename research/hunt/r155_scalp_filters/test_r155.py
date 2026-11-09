"""R155 tests (PREREG section 5).

    python -m pytest research/hunt/r155_scalp_filters/test_r155.py -q
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r155cfg as K  # noqa: E402
import r155feat as FE  # noqa: E402
import r155study as S  # noqa: E402

N = 375


@dataclass
class _D:
    d: date
    idx: np.ndarray
    atm: np.ndarray | None = None
    con: dict = field(default_factory=dict)
    expiry: bool = False


def _day(i: int) -> date:
    return date.fromordinal(date(2025, 1, 1).toordinal() + i)


def walk_day(seed: int, start: float = 20000.0, vol: float = 8.0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, vol, N))
    o = np.concatenate([[start], c[:-1]])
    h = np.maximum(o, c) + rng.uniform(0, vol, N)
    lo = np.minimum(o, c) - rng.uniform(0, vol, N)
    return np.vstack([o, h, lo, c])


def oi_day(seed: int, atm: float = 20000.0, step: float = 50.0) -> dict:
    rng = np.random.default_rng(seed)
    con = {}
    for k in range(-4, 5):
        for s in ("C", "P"):
            a = np.vstack([100 + np.cumsum(rng.normal(0, 1, N)), 1e5 + np.cumsum(rng.normal(0, 500, N))]).astype(np.float32)
            con[(s, atm + k * step)] = a
    return dict(pcr=(1 + 0.1 * np.sin(np.arange(N) / 20)).astype(np.float32), con=con)


def make(n_days: int = 70):
    days = [(_day(i), _D(_day(i), walk_day(i), atm=np.full(N, 20000.0))) for i in range(n_days)]
    oi = {d: oi_day(i) for i, (d, _) in enumerate(days)}
    return days, oi


# ================================================================================================ config
def test_tests_and_m():
    assert len(K.FILTERS) == 20 and len(K.TESTS) == 280
    assert K.N_PRIOR + len(K.TESTS) == 1962
    assert set(K.STACK_OF) <= set(K.FILTERS)
    assert len(K.rules_of("A")) == len(K.rules_of("B")) == 12


# ================================================================================================ pure pieces
def test_ahead_behind_and_wall():
    lv = [(100.2, 3), (99.0, 4), (101.0, 2), (100.1, 2)]
    a, b = FE.ahead_behind(lv, 100.0, "C")
    assert a == pytest.approx(0.002) and b == pytest.approx(0.01)
    a, b = FE.ahead_behind(lv, 100.0, "P")
    assert a == pytest.approx(0.01) and b == pytest.approx(0.002)
    assert FE.wall(0.002) and not FE.wall(0.003) and not FE.wall(np.nan)


def test_floor_pivots():
    prev = np.array([[100.0] * 3, [110.0, 105, 104], [90.0, 95, 96], [100.0, 101, 102]])
    lv = FE.floor_pivots(prev)
    p = (110 + 90 + 102) / 3
    assert [round(x, 6) for x, _ in lv] == [round(v, 6) for v in (p, 2 * p - 90, 2 * p - 110, p + 20, p - 20)]
    assert FE.floor_pivots(None) == []


def test_candle_shares():
    ag, fo, cl, bo = FE.candle(100, 110, 90, 105, "C")              # range 20, upper wick 5, lower wick 10
    assert (ag, fo, cl, bo) == pytest.approx((0.25, 0.5, 0.75, 0.25))
    ag, fo, cl, bo = FE.candle(100, 110, 90, 105, "P")
    assert (ag, fo, cl, bo) == pytest.approx((0.5, 0.25, 0.25, -0.25))
    assert FE.candle(1, 1, 1, 1, "C") == (0.0, 0.0, 0.5, 0.0)
    assert np.isnan(FE.candle(np.nan, 1, 1, 1, "C")[0])


def test_oi_build_score():
    con = {}
    s = 30
    a = np.full((2, N), np.nan, np.float32)
    a[0, s - 15], a[0, s], a[1, s - 15], a[1, s] = 100, 110, 1000, 1200   # call: premium up, OI up -> +1
    con[("C", 20000.0)] = a
    b = np.full((2, N), np.nan, np.float32)
    b[0, s - 15], b[0, s], b[1, s - 15], b[1, s] = 100, 110, 1000, 1200   # put: premium up, OI up -> -1
    con[("P", 20050.0)] = b
    c = np.full((2, N), np.nan, np.float32)
    c[0, s - 15], c[0, s], c[1, s - 15], c[1, s] = 100, 90, 1000, 1200    # put: premium down, OI up -> +1
    con[("P", 20000.0)] = c
    assert FE.oi_build(con, 20000.0, 50.0, s) == pytest.approx(1 / 3)
    assert np.isnan(FE.oi_build({}, 20000.0, 50.0, s))


def test_last_at_staleness():
    a = np.full(N, np.nan)
    a[10] = 5.0
    assert FE.last_at(a, 12) == 5.0 and np.isnan(FE.last_at(a, 15)) and np.isnan(FE.last_at(a, -1))


def test_filters_rules():
    x = {k: np.nan for k in FE.NUM}
    x.update(px=100.0, w5_a=0.001, w20_a=0.01, w50_a=np.nan, w3m_a=0.004, piv_a=0.003, b20=1.0, ema_d=0.001, st=1.0,
             twap_d=-0.001, oib=0.2, dpcr=-0.01, opt_dc=-0.01, opt_doi=-0.02, ag5=0.4, ag1=0.6, for5=0.5, cloc5=0.7,
             body5=0.65)
    f = FE.filters(x)
    assert not f["F1"] and f["F2"] and f["F3"] and f["F4"] and f["F5"] and f["F6"]
    assert f["F7"] and f["F8"] and f["F9"] and not f["F10"] and f["F11"] and not f["F12"]
    assert not f["F13"]                                   # price and OI both fell
    assert f["F14"] and not f["F15"] and f["F16"] and f["F17"] and f["F18"]
    assert f["F19"] == (sum(f[k] for k in K.STACK_OF) >= 5)
    x["px"] = np.nan
    f = FE.filters(x)
    assert not any(f[k] for k in ("F1", "F2", "F3", "F4", "F5", "F6"))


def test_unreadable_fails():
    x = {k: np.nan for k in FE.NUM}
    x["px"] = 100.0
    f = FE.filters(x)
    for k in ("F7", "F8", "F9", "F10", "F11", "F12", "F13", "F14", "F15", "F16", "F17", "F18"):
        assert not f[k], k
    assert f["F1"] and f["F6"]                           # no level at all: no wall


# ================================================================================================ Feat
def test_levels_need_confirmation_and_window():
    days, oi = make(60)
    F = FE.Feat("NIFTY", days, oi, step=50.0)
    i = 55
    pbar, _, k = F.p5
    for s in (30, 200):
        last = F.last_bar(F.b5, F.first5, i, s)
        assert F.b5["end"][last] <= s and F.b5["day"][last] == i
        lv = F.levels("5", 20, i, s)
        lo_bar = F.first5[i - 19]
        used = pbar[(pbar >= lo_bar) & (pbar + k <= last)]
        assert sum(n for _, n in lv) == len(used)
    assert F.levels("5", 5, i, 100) != F.levels("5", 50, i, 100)


def test_lookahead_guard():
    """Every number at minute s is unchanged when the last session's bars and OI after s are erased."""
    days, oi = make(70)
    F = FE.Feat("NIFTY", days, oi, step=50.0)
    d = days[-1][0]
    for s in (3, 47, 150, 271, 330):
        cut = [(dd, _D(dd, D.idx.copy(), D.atm)) for dd, D in days]
        cut[-1][1].idx[:, s + 1:] = np.nan
        oc = {dd: dict(pcr=v["pcr"].copy(), con={kk: a.copy() for kk, a in v["con"].items()}) for dd, v in oi.items()}
        oc[d]["pcr"][s + 1:] = np.nan
        for a in oc[d]["con"].values():
            a[:, s + 1:] = np.nan
        G = FE.Feat("NIFTY", cut, oc, step=50.0)
        for side in ("C", "P"):
            x1, f1 = F.decide(d, s, side, 20000.0)
            x2, f2 = G.decide(d, s, side, 20000.0)
            assert f1 == f2, (s, side)
            for kk in x1:
                assert (np.isnan(x1[kk]) and np.isnan(x2[kk])) or x1[kk] == pytest.approx(x2[kk]), (s, side, kk)


# ================================================================================================ study pure parts
def test_be_rate_and_folds():
    assert S.be_rate(np.array([100, 100, -300])) == pytest.approx(300 / 400)
    assert np.isnan(S.be_rate(np.array([1.0, 2.0])))
    assert S.fold_of(date(2021, 9, 1)) == -1
    assert S.fold_of(date(2022, 6, 1)) == 0
    assert S.fold_of(date(2025, 5, 1)) == 4
    assert S.fold_of(date(2026, 5, 1)) == len(K.PICK_FOLDS) - 1


def test_pick_keep_trains_only_on_earlier_days():
    rng = np.random.default_rng(0)
    n = 2000
    days = np.array([date(2022, 1, 1).toordinal() + int(v) for v in rng.integers(0, 365, n)])
    X = rng.normal(size=(n, len(FE.NUM)))
    net = np.where(X[:, 0] > 0, 100.0, -100.0)                   # feature 0 decides the trade
    train = dict(day=days, X=X, net=net)
    rows = [(("u", i), date(2023, 2, 1), np.r_[v, np.zeros(len(FE.NUM) - 1)]) for i, v in enumerate((-2.0, 2.0))]
    keep = S.pick_keep(train, rows)
    assert keep == {("u", 1)}
    early = [(("u", 9), date(2021, 9, 1), np.zeros(len(FE.NUM)))]      # before the first fold: kept
    assert S.pick_keep(train, early) == {("u", 9)}
    few = dict(day=days[:50], X=X[:50], net=net[:50])                   # too few training trades: kept
    assert S.pick_keep(few, rows) == {("u", 0), ("u", 1)}
    late = dict(day=days + 10_000, X=X, net=net)                       # only LATER trades: nothing to train on
    assert S.pick_keep(late, rows) == {("u", 0), ("u", 1)}


def test_subset_twin():
    rng = np.random.default_rng(1)
    base = np.array([-10.0] * 90 + [100.0] * 10)
    p, m = S.subset_twin(base, 10, 1000.0, 500, rng)
    assert p < 0.01 and m < 1000
    p, _ = S.subset_twin(base, 10, -100.0, 500, rng)
    assert p == 1.0
    assert S.subset_twin(base, 0, 0.0, 10, rng) == (1.0, 0.0)


def test_gates():
    good = dict(trades=1200, net=10)
    f0 = dict(trades=1200, net=-100)
    assert S.gates(good, good, good, f0, f0, 0.05, 12)["passed"]
    g = S.gates(good, good, dict(trades=100, net=10), f0, f0, 0.05, 12)
    assert not g["passed"] and "final trades" in g["fails"][0]
    g = S.gates(good, good, good, dict(trades=10, net=100), f0, 0.05, 12)
    assert "better per trade" in g["fails"][0]
    g = S.gates(good, good, good, f0, f0, 0.05, 12, b0_p2=100, b0_p3=0)
    assert "own exits" in g["fails"][0]
