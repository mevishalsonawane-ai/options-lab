"""R187 tests (PREREG section 8): the look-ahead guard of every new feature, the walk-forward folds (no leakage), the
training-only scaling, the theta / delta choices, the exit composition, the gates, the twins and the BH count.

    python -m pytest research/hunt/r187_neural_meta/test_r187.py -q
"""
from __future__ import annotations

import sys
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pytest

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r187cfg as K  # noqa: E402
import r187feats as F  # noqa: E402
import r187net as N  # noqa: E402
import r187study as S  # noqa: E402


# ================================================================================================ synthetic lake
def _walk(rng, n_days, start=10000.0):
    days, out, px = [], {}, start
    d = date(2025, 1, 1)
    while len(days) < n_days:
        d += timedelta(days=1)
        if d.weekday() >= 5:
            continue
        steps = rng.normal(0, 3, F.N)
        c = px + np.cumsum(steps)
        o = np.concatenate([[px], c[:-1]])
        h = np.maximum(o, c) + rng.uniform(0, 2, F.N)
        l_ = np.minimum(o, c) - rng.uniform(0, 2, F.N)
        a = np.stack([o, h, l_, c], 1)
        a[rng.random(F.N) < 0.02] = np.nan                  # a few missing minutes
        out[d] = a
        days.append(d)
        px = c[-1]
    return out, days


def _lake(seed=1, n_days=30):
    rng = np.random.default_rng(seed)
    ix = {}
    for i, s in enumerate(K.CROSS + ("INDIAVIX",)):
        ix[s], days = _walk(rng, n_days, 100.0 if s == "INDIAVIX" else 10000.0 + 1000 * i)
    oa = {}
    for u in K.UNDS:
        oa[u] = {d: rng.uniform(1, 100, (F.N, F.NA)).astype(np.float32) for d in days}
    return F.Lake(ix, oa), days


def _junk_after(lake, day, r, rng):
    ix = {s: dict(v) for s, v in lake.ix.items()}
    for s in ix:
        if day in ix[s]:
            a = ix[s][day].copy()
            a[r + 1:] = rng.uniform(-1e6, 1e6, a[r + 1:].shape)
            ix[s][day] = a
    oa = {u: dict(v) for u, v in lake.oa.items()}
    for u in oa:
        if day in oa[u]:
            a = oa[u][day].copy()
            a[r + 1:] = rng.uniform(-1e6, 1e6, a[r + 1:].shape)
            oa[u][day] = a
    return F.Lake(ix, oa)


def _same(a: dict, b: dict) -> bool:
    return a.keys() == b.keys() and all((np.isnan(a[k]) and np.isnan(b[k])) or a[k] == b[k] for k in a)


@pytest.mark.parametrize("r", [0, 4, 14, 61, 200, 374])
@pytest.mark.parametrize("side", ["C", "P"])
def test_lookahead_guard_common(r, side):
    lake, days = _lake()
    d = days[-1]
    f1 = F.common(lake, "NIFTY", d, r, side, 3.0, False)
    f2 = F.common(_junk_after(lake, d, r, np.random.default_rng(9)), "NIFTY", d, r, side, 3.0, False)
    assert _same(f1, f2)
    assert set(f1) == set(F.COMMON)


def test_features_use_the_rows_they_should():
    lake, days = _lake()
    d = days[-1]
    f = F.common(lake, "BANKNIFTY", d, 120, "C", 2.0, True)
    assert f["side"] == 1.0 and f["is_exp"] == 1.0 and f["tod"] == 120.0
    assert np.isfinite(f["ret15"]) and np.isfinite(f["adx14"]) and np.isfinite(f["pcr"]) and np.isfinite(f["mv_SENSEX"])
    p = F.common(lake, "BANKNIFTY", d, 120, "P", 2.0, True)
    assert p["ret15"] == pytest.approx(-f["ret15"])          # signed by the side
    assert p["range_pos"] == pytest.approx(1 - f["range_pos"])


def test_missing_day_and_first_day():
    lake, days = _lake()
    f = F.common(lake, "NIFTY", date(2030, 1, 1), 100, "C", np.nan, False)    # no data that day
    assert np.isnan(f["ret5"]) and f["tod"] == 100.0
    g = F.common(lake, "NIFTY", days[0], 100, "C", np.nan, False)            # no earlier session
    assert np.isnan(g["day_move"]) and np.isnan(g["range_adr"]) and np.isnan(g["dvix"])


def test_lastf_bars5_indicators():
    a = np.array([1.0, np.nan, 3.0, np.nan])
    assert F.lastf(a, 3) == 3.0 and F.lastf(a, 1) == 1.0 and np.isnan(F.lastf(a, -1))
    grid = np.full((15, 4), np.nan)
    grid[:, 0] = grid[:, 3] = np.arange(15.0)
    grid[:, 1] = grid[:, 3] + 1
    grid[:, 2] = grid[:, 3] - 1
    assert len(F.bars5(grid, 8)) == 1 and len(F.bars5(grid, 9)) == 2 and len(F.bars5(grid)) == 3
    up = np.arange(40.0)
    b = np.stack([up, up + 0.5, up - 0.5, up + 0.2], 1)
    assert F.eff_ratio(b[:, 3]) == pytest.approx(1.0)
    assert np.isnan(F.adx(b[:20])) and F.adx(b) > 50
    assert np.isnan(F.chop(b[:10])) and 0 < F.chop(b) < 100


# ================================================================================================ folds and leakage
def test_folds_are_ordered_and_disjoint():
    prev_hi = None
    for name, lo, hi, vlo in K.FOLDS:
        assert vlo < lo <= hi
        if prev_hi is not None:
            assert lo == prev_hi + timedelta(days=1)
        prev_hi = hi
    assert K.fold_of(date(2025, 3, 31)) is None and K.fold_of(date(2026, 11, 13)) == "F5"
    assert K.FOLDS[-1][1] == K.FINAL_START                   # the frozen model starts with the final


def _row(arm, d, walked=True):
    return dict(arm=arm, und="NIFTY", day=d, walked=walked, v5=walked, feats={}, study={},
                nets={e: 1.0 for e in K.menu(arm)})


def test_training_rows_never_reach_the_fold():
    rows = {"R72": {i: _row("R72", date(2025, 3, 1) + timedelta(days=i)) for i in range(60)},
            "orb_fresh": {0: _row("orb_fresh", date(2025, 3, 1))},
            "orb": {0: _row("orb", date(2025, 3, 2)), 1: _row("orb", date(2025, 3, 3), walked=False)}}
    tr = S.training_rows(rows, date(2025, 4, 1))
    assert tr and all(r["day"] < date(2025, 4, 1) for r in tr)
    assert not any(r["arm"] == "orb_fresh" for r in tr) and all(r["walked"] for r in tr)


def test_prep_uses_training_rows_only():
    rng = np.random.default_rng(3)
    Xtr = rng.normal(0, 1, (200, len(N.COLS)))
    Xtr[::7, 0] = np.nan
    p = N.Prep(Xtr)
    Xte = rng.normal(50, 1, (20, len(N.COLS)))
    Xte[0, 0] = np.nan
    Z = p(Xte)
    med = np.nanmedian(Xtr[:, 0])
    assert Z[0, 0] == pytest.approx((med - p.mu[0]) / p.sd[0])
    assert np.allclose(p.mu, N.Prep(np.vstack([Xtr])).mu)
    assert np.array_equal(Z[:, N.N_NUM:], Xte[:, N.N_NUM:])   # one-hots untouched


def test_stacked_rows_and_exit_onehot():
    r = dict(arm="LIQ", und="BANKNIFTY", feats={}, study={}, nets={e: (np.nan if e == "R:P60" else 100.0)
                                                                  for e in K.menu("LIQ")})
    X, yw, yn, ix = N.stacked([r])
    assert X.shape == (7, len(N.COLS)) and yw.sum() == 7 and set(ix) == {0}
    oh = X[:, -len(K.EXITS):]
    assert (oh.sum(1) == 1).all() and oh[:, K.EXITS.index("R:P60")].sum() == 0
    o = dict(arm="orb_fresh", und="BANKNIFTY", feats={}, study={}, nets={e: -5.0 for e in K.menu("orb")})
    Xo, ywo, _, _ = N.stacked([o])
    assert Xo.shape[0] == 7 and ywo.sum() == 0
    assert Xo[0, N.COLS.index("arm_ORB")] == 1.0              # ORB Fresh shares ORB's identity


def test_model_fit_predict_small():
    rng = np.random.default_rng(5)
    rows = []
    for i in range(300):
        x = rng.normal()
        rows.append(dict(arm="R72", und="NIFTY", feats={"ret5": x}, study={},
                         nets={e: (500 * x + rng.normal(0, 50)) for e in K.menu("R72")}))
    X, yw, yn, _ = N.stacked(rows)
    m = N.Model("clf", (8, 4), 1e-3, seeds=(187,)).fit(X, yw, yn)
    p = m.predict(X)
    assert p.shape == (len(X),) and ((p >= 0) & (p <= 1)).all()
    assert np.corrcoef(p, yn)[0, 1] > 0.5
    g = N.Model("reg", (8, 4), 1e-3, seeds=(187,)).fit(X, yw, yn)
    assert np.corrcoef(g.predict(X), yn)[0, 1] > 0.5


# ================================================================================================ the uses' choices
def test_choose_theta():
    p = np.array([0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.35, 0.45])
    net = np.array([-100, -100, -50, 50, 100, 100, 200, 200, -80, 20.0])
    th = N.choose_theta(p, net, 0.40)
    k = p >= th
    assert k.sum() >= 4 and net[k].sum() > net.sum() and (net[k] > 0).mean() > (net > 0).mean()
    assert N.choose_theta(p, np.abs(net), 0.4) is None        # nothing beats keeping all winners
    assert N.choose_theta([], [], 0.4) is None
    assert N.choose_theta(p, net, 0.95) is None               # keeping >= 95% leaves no grid theta


def test_exit_choice_and_delta():
    ev = {"R:V5": 100.0, "R:A": 180.0, "R:T1000": 150.0}
    assert N.exit_choice(ev, "R:V5", 0.0) == "R:A"
    assert N.exit_choice(ev, "R:V5", 100.0) == "R:V5"
    assert N.exit_choice(ev, "R:V5", float("inf")) == "R:V5"
    assert N.exit_choice({"R:V5": 5.0, "R:A": 5.0}, "R:V5", 0.0) == "R:V5"
    evs = [ev, ev]
    good = [{"R:V5": 0.0, "R:A": 100.0, "R:T1000": 0.0}] * 2
    bad = [{"R:V5": 0.0, "R:A": -100.0, "R:T1000": 0.0}] * 2
    assert N.choose_delta(evs, good, "R:V5") in (0.0,)
    assert N.choose_delta(evs, bad, "R:V5") == float("inf")


def test_priced_composition():
    import types
    A = types.SimpleNamespace()
    A.us = [(("NIFTY", date(2025, 5, 2), None), [dict(er=30, side="C"), dict(er=60, side="P")], None, None)]
    A.pr = {e: [[f"{e}-0", f"{e}-1"]] for e in ("R:V5", "R:A")}
    import r187rarms as RA
    pr = RA.Arm.priced(A, {("NIFTY", date(2025, 5, 2), 60, "P"): "R:A"})
    assert pr == [["R:V5-0", "R:A-1"]]
    assert RA.Arm.priced(A, None) is A.pr["R:V5"]


# ================================================================================================ gates, twins, BH
def _per(net, win, dd, wm, trades=100):
    return {p: dict(net=net, win=win, max_dd=dd, worst_month=dict(net=wm), trades=trades) for p in K.PERIODS}


def test_gates():
    base = _per(1000, 0.50, 5000, -2000)
    assert S.gates(_per(1100, 0.55, 4000, -2500), base, 0.05)["passed"]
    assert S.gates(_per(1100, 0.55, 6000, -1500), base, 0.05)["passed"]       # worst month better is enough
    f = S.gates(_per(900, 0.50, 6000, -3000, trades=30), base, 0.5)
    assert not f["passed"] and len(f["fails"]) == 2 * 3 + 2
    neg = _per(1100, 0.55, 4000, -1000)
    neg["early"]["net"] = -1
    assert "not positive in every period" in S.gates(neg, base, 0.01)["fails"]


def test_bh_and_count():
    assert len(K.TESTS) == 20 and K.M_TOTAL == 4902
    q = S.bh_q([0.0001, 0.5], 4902)
    assert q[0] == pytest.approx(0.0001 * 4902) and q[1] == 1.0
    assert S.bh_q([0.01, 0.02], 2) == pytest.approx([0.02, 0.02])


def test_twins():
    rng = np.random.default_rng(187)
    tw = S.skip_twins([1, 2, 3, 4], 4, 10, rng)
    assert (tw == 10).all()
    tw = S.skip_twins([1, 2, 3, 4], 2, 200, rng)
    assert tw.min() >= 3 and tw.max() <= 7
    trades = [dict(net=5.0, exit_id="R:V5"), dict(net=9.0, exit_id="R:A")]
    alts = [{"R:V5": 5.0, "R:A": 1.0}, {"R:V5": 4.0, "R:A": 9.0, "R:T1000": 7.0}]
    m = S.exit_twin_nets(trades, alts, "R:V5", 100, rng)
    assert (m[:, 0] == 5.0).all() and set(np.unique(m[:, 1])) <= {9.0, 7.0}
    assert S.twin_p(10.0, np.array([1.0, 2.0, 11.0])) == pytest.approx(2 / 4)


def test_describe():
    d0 = date(2026, 4, 1)
    tr = [dict(day=d0, net=100.0), dict(day=d0 + timedelta(days=1), net=-300.0),
          dict(day=d0 + timedelta(days=40), net=50.0), dict(day=date(2025, 1, 1), net=1e6)]
    s = S.describe(tr, d0, date(2026, 5, 31))
    assert s["trades"] == 3 and s["net"] == -150 and s["max_dd"] == 300 and s["win"] == pytest.approx(2 / 3, abs=1e-4)
    assert s["worst_month"] == dict(month="2026-04", net=-200) and s["months"]["2026-05"] == 50
    e = S.describe([], d0, d0)
    assert e["trades"] == 0 and e["net"] == 0 and e["win"] is None


# ================================================================================================ ORB walk parity (store needed)
def test_orb_walk_matches_r169_b0():
    import os
    store = Path(os.environ.get("R169_OUT", Path.home() / ".cache" / "iraalgo" / "r187" / "r3")) / "sess_BANKNIFTY.pkl"
    if not store.exists():
        pytest.skip("R187's ORB store is not built here")
    import r187orb as OB
    import r169lib as X
    ss = [s for s in OB.sessions() if date(2025, 1, 1) <= s.day <= date(2025, 2, 28)]
    for arm in K.ORB_ARMS:
        mine = OB.walk(arm, ss)
        ref = X.taken([t for s in ss for t in X.run_session(s, X.Cfg(arm), OB.contexts()[s.day])])
        assert [(t["day"], t["sig_row"], round(t["net"], 6)) for t in mine] == \
               [(t["day"], t["sig_row"], round(t["net"], 6)) for t in ref]
        # a chooser that always answers B0 is B0
        same = OB.walk(arm, ss, lambda *a: "B0")
        assert [round(t["net"], 6) for t in same] == [round(t["net"], 6) for t in mine]


def test_early_stopping_split_takes_whole_latest_trades():
    rows = [dict(day=date(2025, 1, 1) + timedelta(days=i)) for i in range(20)]
    ix = np.repeat(np.arange(20), 8)                         # each trade stacked 8 times
    order = N.order_of(rows, ix)
    es = N.es_split(order, 0.10)
    assert es.sum() == 2 * 8                                 # the 2 latest trades, all their rows
    assert set(ix[es]) == {18, 19} and not (set(ix[es]) & set(ix[~es]))
    assert not N.es_split(np.array([5, 5, 5]), 0.1).any()    # one trade: nothing held out
