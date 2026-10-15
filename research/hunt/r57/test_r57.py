"""R57 tests on synthetic data only (no lake needed):

    python -m pytest research/hunt/r57/test_r57.py -q

Covers: cleaning (foreign rows, bad ticks), the look-ahead guard for every feature R57 reads, held-contract labels
(never the rolling ATM series), the index exits, SoloMidday's signal, purge / embargo, the take rules, the period guard,
costs, the evaluator's parity with the trained models (and the committed golden vectors), and edge cases: holidays,
expiry day, missing minutes, no data.
"""
from __future__ import annotations

import copy
import json
import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import r57cfg as K  # noqa: E402
import engine as E  # noqa: E402
import r51data  # noqa: E402
import r57data  # noqa: E402
import r57eval  # noqa: E402
import r57model as M  # noqa: E402
import r57setups as S  # noqa: E402
import r57study as R  # noqa: E402

N = K.NROWS
UND = "NIFTY"


# ------------------------------------------------------------------------------------------------ synthetic data
def sessions(start: date, n: int, holidays=()) -> list:
    out, d = [], start
    while len(out) < n:
        if d.weekday() < 5 and d not in holidays:
            out.append(d)
        d += timedelta(days=1)
    return out


def walk_day(rng, base: float) -> np.ndarray:
    c = base + np.cumsum(rng.normal(0, 3, N))
    o = np.r_[base, c[:-1]]
    h = np.maximum(o, c) + rng.uniform(0, 2, N)
    lo = np.minimum(o, c) - rng.uniform(0, 2, N)
    return np.vstack([o, h, lo, c])


def make_day(d: date, idx: np.ndarray, expiry=False) -> r51data.Day:
    D = r51data.Day(d, idx.copy())
    D.atm = np.round(idx[3] / 50) * 50
    D.expiry = expiry
    D.atr, D.dte = 200.0, 2.0
    D.vix = np.full(N, 14.0, np.float32)
    D.vix_prev = 13.5
    D.ctx = {k: np.full(N, v, np.float32) for k, v in (("pcr5", 0.9), ("pcr10", 1.0), ("iv_atm", 12.0),
                                                      ("iv_skew", 1.5))}
    return D


@pytest.fixture
def store(monkeypatch):
    rng = np.random.default_rng(1)
    holiday = date(2025, 6, 11)
    ds = sessions(date(2025, 6, 2), 20, holidays={holiday})
    days, px = {}, 20000.0
    for d in ds:
        a = walk_day(rng, px)
        px = float(a[3, -1])
        days[d] = make_day(d, a, expiry=(d == ds[5]))
    monkeypatch.setitem(r57data._CACHE, UND, days)
    monkeypatch.setitem(r51data._CACHE, UND, days)
    feats = r57data.brain_features(UND, days)
    monkeypatch.setitem(r57data._FEAT, UND, dict(names=r57data.FEATURES_BRAIN, days=feats))
    return days, ds, holiday


# ------------------------------------------------------------------------------------------------ cleaning
def test_foreign_mask():
    m = r57data.foreign_mask(np.array([100.0, 104.0, 97.5, np.nan, 100.0]), np.array([100, 100, 100, 100, np.nan]))
    assert m.tolist() == [False, True, False, True, True]


def test_spike_mask_reverting_only():
    def flags(closes):
        n = len(closes)
        return r57data.spike_mask(np.zeros(n), np.array(["C"] * n), np.full(n, 100.0), np.arange(n),
                                  np.array(closes, float)).tolist()
    assert flags([100, 200, 100]) == [False, True, False]
    assert flags([100, 40, 100]) == [False, True, False]
    assert flags([100, 200, 200]) == [False, False, False]                 # sustained jump: not a spike
    assert flags([100, 200, 200, 100]) == [False, False, False, False]     # two-minute spike: not flagged
    assert flags([500, 100, 100]) == [False, False, False]                 # first bar never a spike


def test_spike_mask_never_pairs_contracts_or_days():
    m = r57data.spike_mask(np.array([0, 0, 1]), np.array(["C", "C", "C"]), np.full(3, 100.0), np.array([0, 1, 0]),
                           np.array([100.0, 300.0, 100.0]))
    assert not m.any()                                                     # 300 is a contract-day's last bar


def test_ffill_is_point_in_time():
    a = np.array([np.nan, 1.0, np.nan, 3.0, np.nan])
    assert np.allclose(r57data.ffill_row(a)[1:], [1, 1, 3, 3]) and np.isnan(r57data.ffill_row(a)[0])


# ------------------------------------------------------------------------------------------------ look-ahead guard
def _truncate(D, t: int):
    T = copy.deepcopy(D)
    T.idx[:, t + 1:] = np.nan
    T.vix[t + 1:] = np.nan
    for k in T.ctx:
        T.ctx[k][t + 1:] = np.nan
    return T


@pytest.mark.parametrize("t", [4, 29, 164, 250])
def test_every_feature_ignores_the_future(store, monkeypatch, t):
    """Each feature at row t, recomputed with every minute after t removed, is unchanged (brain + R57's own)."""
    days, ds, _ = store
    d = ds[-1]
    full_a = M.row_a(UND, days[d], t, "C", "S2")
    full_b = M.row_b(UND, days[d], t)
    cut = dict(days)
    cut[d] = _truncate(days[d], t)
    monkeypatch.setitem(r57data._FEAT, UND, dict(names=r57data.FEATURES_BRAIN,
                                                 days=r57data.brain_features(UND, cut)))
    part_a = M.row_a(UND, cut[d], t, "C", "S2")
    part_b = M.row_b(UND, cut[d], t)
    for full, part in ((full_a, part_a), (full_b, part_b)):
        assert set(full) == set(part)
        for k in full:
            assert (np.isnan(full[k]) and np.isnan(part[k])) or full[k] == pytest.approx(part[k], abs=1e-9), k


def test_feature_lists_cover_every_input():
    assert len(M.names_a()) == len(M.BASE) + 1 + 4 and "setup_C4" in M.names_a(True)
    assert set(M.names_b()) - set(M.BASE) == set(M.B_TIME_NAMES)


def test_atr_uses_only_completed_days(store):
    days, ds, _ = store
    atr = r57data.daily_atr14(days)
    assert np.isnan(atr[ds[13]]) and np.isfinite(atr[ds[14]])
    changed = dict(days)
    changed[ds[14]] = make_day(ds[14], days[ds[14]].idx * 1.5)
    assert r57data.daily_atr14(changed)[ds[14]] == atr[ds[14]]            # today's own bars never enter


# ------------------------------------------------------------------------------------------------ held contract
def test_labels_are_on_the_held_contract_not_the_rolling_series():
    d = date(2025, 6, 2)
    idx = np.vstack([np.full(N, 20000.0)] * 4)
    D = make_day(d, idx)
    D.atm = np.where(np.arange(N) < 8, 20000.0, 20100.0)                  # ATM rolls during the trade
    held = np.full((5, N), np.nan, np.float32)
    held[:4, :] = 100.0 + np.arange(N) * 0.5
    held[4, :] = 5000
    other = held.copy()
    other[:4, :] = 40.0
    D.con = {("C", 20000.0): held, ("C", 20100.0): other}
    k = E.strike_for(D, UND, "C", 4, 0)
    assert k == 20000.0
    t = E.run_day(UND, D, ["C"], [k], [5], E.Exits(tmax=10), [-1])[0]
    assert t["E"] == pytest.approx(held[0, 5]) and t["X"] == pytest.approx(held[3, 14])
    assert t["X"] != pytest.approx(other[3, 14])
    q = K.lot(UND, d)
    ch = float(E.charges(UND, t["rE"], t["rX"], q, d)[0])
    assert t["net"] == pytest.approx((t["rX"] - t["rE"]) * q - ch)
    assert S.cost_2x(t) == pytest.approx(t["net"] - ((t["rE"] - t["E"]) + (t["X"] - t["rX"])) * q - ch)


def test_index_exit_stop_lock_and_missing_minutes():
    n = 40
    o = np.full(n, 100.0)
    h, lo, c = o + 1, o - 1, o.copy()
    assert S.index_exit(o, h, lo, c, 5, 1, 3.0, -1.0, n) == -1             # nothing fires
    c2 = c.copy()
    c2[10] = 96.9
    assert S.index_exit(o, h, lo, c2, 5, 1, 3.0, -1.0, n) == 11            # close through the stop -> next open
    c3 = c2.copy()
    c3[10] = np.nan                                                        # a missing minute triggers nothing
    assert S.index_exit(o, h, lo, c3, 5, 1, 3.0, -1.0, n) == -1
    h4, lo4 = h.copy(), lo.copy()
    h4[7] = 105.0                                                          # earns the lock (+4.5)
    lo4[8] = 99.5                                                          # next bar touches the entry level
    assert S.index_exit(o, h4, lo4, c, 5, 1, 3.0, 4.5, n) == 9
    assert S.index_exit(o, h4, lo4, c, 5, 1, 3.0, -1.0, n) == -1           # no lock without earn
    assert S.index_exit(o, h, lo, c, 5, -1, 3.0, -1.0, n) == -1            # put: same flat path, no exit


def test_s1_signal_rules():
    d = date(2025, 6, 2)
    base = 20000 + np.arange(N) * (200.0 / 165)
    idx = np.vstack([base, base + 0.5, base - 0.5, base + 0.2])
    D = make_day(d, idx)
    s = S.s1_signal(UND, D)
    assert s["side"] == "C"
    atm = S.solo_atm(UND, idx[0, S.S1_ROW])
    assert s["strikes"] == [atm - 200, atm - 150, atm - 250]
    miss = make_day(d, idx)
    miss.idx[:, S.S1_ROW - 1] = np.nan                                     # no 11:59 minute: no decision
    assert S.s1_signal(UND, miss) is None
    late = make_day(d, idx)
    late.idx[:, :2] = np.nan                                               # first minute after 09:16
    assert S.s1_signal(UND, late) is None
    small = make_day(d, idx)
    small.atr = 1000.0                                                     # move < 0.5 ATR
    assert S.s1_signal(UND, small) is None


# ------------------------------------------------------------------------------------------------ calendar edges
def test_days_in_skips_expiry_holiday_and_guards_final(store):
    days, ds, holiday = store
    got = S.days_in(UND, ds[0], ds[-1])
    assert ds[5] not in got and holiday not in got and len(got) == len(ds) - 1
    with pytest.raises(PermissionError):
        S.days_in(UND, date(2026, 3, 1), date(2026, 4, 2))
    with pytest.raises(PermissionError):
        S.days_in(UND, date(2024, 11, 1), date(2024, 12, 1))


def test_dte_counts_sessions_across_a_holiday(store):
    days, ds, holiday = store
    dte = r57data.dte_map(days)
    assert dte[ds[5]] == 0 and dte[ds[4]] == 1 and dte[ds[0]] == 5
    assert np.isnan(dte[ds[-1]])                                           # past the last known expiry
    assert holiday not in dte


def test_train_mask_purges_and_embargoes():
    cal = sessions(date(2025, 6, 2), 30)
    days = np.array(cal, dtype=object)
    start = date(2025, 7, 1)
    m = M.train_mask(days, days, start, cal)
    last_before = max(d for d in cal if d < start)
    assert not m[list(cal).index(last_before)]                             # embargo: last session dropped
    assert all(not x for d, x in zip(cal, m) if d >= start)                # nothing from the test month
    assert all(x for d, x in zip(cal, m) if d < last_before)
    exit_late = days.copy()
    exit_late[0] = start                                                   # a label that ends in the test month
    assert not M.train_mask(days, exit_late, start, cal)[0]                # is purged
    assert not M.train_mask(days, days, cal[0], cal).any()                 # no history: nothing to train on


def test_month_end():
    assert M.month_end(2026, 2) == date(2026, 2, 28) and M.month_end(2025, 12) == date(2025, 12, 31)


# ------------------------------------------------------------------------------------------------ take rules
def test_select_a_one_a_day_time_order_then_score():
    d1, d2 = date(2025, 7, 1), date(2025, 7, 2)
    rows = [dict(day=d1, er=40), dict(day=d1, er=10), dict(day=d1, er=10), dict(day=d2, er=10), dict(day=d2, er=20)]
    sc = np.array([0.9, 0.6, 0.8, 0.1, np.nan])
    thr = np.full(5, 0.5)
    got = R.select_a(rows, sc, thr, "gbc")
    assert got == [rows[2]]                                                # first minute, highest score; d2 none
    assert R.select_a(rows, np.array([0.0, -1, 0.0, 0.2, 0.3]), thr, "gbr") == [rows[3]]   # gbr: strictly > 0
    assert R.select_a([], np.zeros(0), np.zeros(0), "gbc") == []


def test_b_decision_edge_beats_cost():
    assert R.b_decision(0.6, 50, 0.6, 75, 100) == ("C", pytest.approx(0.2 * 50 * 0.6 * 75 - 100))
    assert R.b_decision(0.4, 50, 0.6, 75, 100)[0] == "P"
    assert R.b_decision(0.5, 50, 0.6, 75, 0) is None
    assert R.b_decision(0.51, 10, 0.5, 75, 500) is None                    # edge below the round trip


def test_no_data_edges():
    assert R.stats([]) == dict(trades=0, net=0.0, net_2x=0.0)
    assert R.twin_p([], "A") == (None, None)
    sc, thr, info = M.walk(np.array([], dtype=object), np.zeros((0, 3)), np.zeros(0), "gbc", K.WF_MONTHS, [])
    assert len(sc) == 0 and info == {}
    assert R.bh([0.01, 0.04, 0.5]).tolist() == pytest.approx([0.03, 0.06, 0.5])


# ------------------------------------------------------------------------------------------------ evaluator parity
@pytest.mark.parametrize("kind", ["gbc", "logit", "gbr"])
def test_evaluator_matches_trained_model(kind):
    rng = np.random.default_rng(57)
    X = rng.normal(size=(600, 6))
    X[rng.random(X.shape) < 0.1] = np.nan
    z = np.nan_to_num(X[:, 0]) - 0.5 * np.nan_to_num(X[:, 2]) + rng.normal(0, 1, 600)
    y = z if kind == "gbr" else (z > 0).astype(int)
    m = M.fit(kind, X, y)
    js = json.loads(json.dumps(M.export(kind, m, [f"f{i}" for i in range(6)], 0.5)))
    want = M.predict(kind, m, X)
    got = np.array([r57eval.score(js, [None if np.isnan(v) else v for v in row]) for row in X])
    assert np.max(np.abs(got - want)) < 1e-12
    named = r57eval.score(js, {f"f{i}": X[0, i] for i in range(6) if not np.isnan(X[0, i])})
    assert named == pytest.approx(want[0], abs=1e-12)                      # absent names = missing values
    with pytest.raises(ValueError):
        r57eval.score(js, [0.0] * 5)


def test_take_rule_strictness():
    m = dict(kind="logit", features=["a"], medians=[0.0], mean=[0.0], scale=[1.0], coef=[0.0], intercept=0.0,
             threshold=0.5)
    assert r57eval.take(m, [1.0])                                          # 0.5 >= 0.5
    assert not r57eval.take(dict(m, rule=">"), [1.0])                      # 0.5 > 0.5 is False


GOLDEN = sorted((Path(__file__).resolve().parent / "results" / "models").glob("*_golden.json"))


@pytest.mark.parametrize("path", GOLDEN, ids=[p.stem for p in GOLDEN])
def test_committed_golden_vectors(path):
    g = json.loads(path.read_text())
    model = json.loads((path.parent / g["model"]).read_text())
    assert len(g["rows"]) >= 20
    for r in g["rows"]:
        assert r57eval.score(model, r["inputs"]) == pytest.approx(r["score"], abs=1e-12)
