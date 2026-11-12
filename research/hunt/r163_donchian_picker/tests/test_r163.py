"""R163 tests (synthetic, no data needed): the features' look-ahead wall, the walk-forward cut, the threshold rule, the
exact random-skip twin, BH with earlier tests, lot scaling, summaries and the models' basic contracts."""
from __future__ import annotations

from datetime import date, timedelta
from itertools import combinations

import numpy as np
import pytest

from research.hunt.r163_donchian_picker import feats as F
from research.hunt.r163_donchian_picker import models as M

RNG = np.random.default_rng(0)


def _session(seed, base=100.0):
    r = np.random.default_rng(seed)
    c = base * np.exp(np.cumsum(r.normal(0, 0.0008, F.NROWS)))
    o = np.concatenate([[base], c[:-1]])
    h = np.maximum(o, c) * (1 + np.abs(r.normal(0, 0.0003, F.NROWS)))
    l_ = np.minimum(o, c) * (1 - np.abs(r.normal(0, 0.0003, F.NROWS)))
    return np.vstack([o, h, l_, c])


def _scramble(a, dr, axis=-1, seed=9):
    """Rows after dr replaced by garbage (the future)."""
    b = np.array(a, dtype=float, copy=True)
    sl = [slice(None)] * b.ndim
    sl[axis] = slice(dr + 1, None)
    b[tuple(sl)] = np.random.default_rng(seed).uniform(1, 1e6, b[tuple(sl)].shape)
    return b


def _same(a: dict, b: dict):
    assert a.keys() == b.keys()
    for k in a:
        x, y = a[k], b[k]
        assert (np.isnan(x) and np.isnan(y)) or x == pytest.approx(y, rel=1e-12, abs=1e-12), k


# ------------------------------------------------------------------------------------------------ look-ahead guard
@pytest.mark.parametrize("dr", [4, 20, 74, 200, 370])
@pytest.mark.parametrize("side", ["C", "P"])
def test_f1_f2_f3_f6_ignore_the_future(dr, side):
    s = 1.0 if side == "C" else -1.0
    hist = [_session(i) for i in range(25)]
    today = _session(99)
    _same(F.f1(today, hist, dr, s), F.f1(_scramble(today, dr), hist, dr, s))
    sess = [(_session(50 + i), 100.0) for i in range(5)]
    _same(F.f2(sess, dr, s), F.f2([(_scramble(t, dr), pc) for t, pc in sess], dr, s))
    vix = _session(7, 14.0).T                                     # (375, 4)
    pcs = list(np.linspace(10, 20, 250))
    _same(F.f3(vix, pcs, dr), F.f3(_scramble(vix, dr, axis=0), pcs, dr))
    _same(F.f6(3, 10, dr + 1, today, 99.5, dr), F.f6(3, 10, dr + 1, _scramble(today, dr), 99.5, dr))


def _chain(seed=3):
    r = np.random.default_rng(seed)
    g = {k: r.uniform(1, 1000, (F.NROWS, 21, 2)) for k in ("oi", "iv", "close", "volume")}
    g["oi"][r.uniform(size=g["oi"].shape) < 0.3] = np.nan          # OI printed every few minutes
    k = {k: r.uniform(50, 150, F.NROWS) for k in ("oi", "volume", "high", "low", "close")}
    return g, k


@pytest.mark.parametrize("dr", [0, 4, 15, 120, 374])
@pytest.mark.parametrize("side", ["C", "P"])
def test_f4_chain_ignores_the_future(dr, side):
    g, k = _chain()
    a = F.f4(g, k, dr, side, 24000.0, 65)
    g2 = {n: _scramble(v, dr, axis=0) for n, v in g.items()}
    k2 = {n: _scramble(v, dr) for n, v in k.items()}
    _same(a, F.f4(g2, k2, dr, side, 24000.0, 65))


def test_f4_missing_chain_is_all_nan_and_signs_flip_with_side():
    assert all(np.isnan(v) for v in F.f4(None, None, 50, "C", 24000.0, 65).values())
    g, k = _chain()
    c, p = F.f4(g, k, 100, "C", 24000.0, 65), F.f4(g, k, 100, "P", 24000.0, 65)
    assert c["pcr_log"] == pytest.approx(-p["pcr_log"])
    assert c["own_oi_chg15"] == pytest.approx(p["oth_oi_chg15"])


def test_f5_counts_only_three_turn_levels_within_half_a_percent():
    lv = [(100.3, 3), (100.4, 2), (99.8, 4), (101.0, 5), (99.0, 3)]
    assert F.f5(lv, 100.0, 1.0) == {"sr_n_ahead05": 1.0, "sr_n_behind05": 1.0}
    assert F.f5(lv, 100.0, -1.0) == {"sr_n_ahead05": 1.0, "sr_n_behind05": 1.0}


def test_f6_gap_filled_and_no_previous_break():
    t = np.full((4, F.NROWS), np.nan)
    t[:, :10] = 101.0
    t[2, 8] = 99.9                                                   # dips through the previous close 100 at row 8
    assert F.f6(None, None, 6, t, 100.0, 5)["gap_filled"] == 0.0     # not yet at the decision row 5
    assert F.f6(None, None, 10, t, 100.0, 9)["gap_filled"] == 1.0
    assert F.f6(None, None, 10, t, 100.0, 9)["since_prev"] == 999.0


# ------------------------------------------------------------------------------------------------ folds / threshold
def test_train_mask_takes_only_days_before_the_fold():
    days = [date(2025, 3, 30), date(2025, 3, 31), date(2025, 4, 1), date(2025, 4, 2)]
    assert M.train_mask(days, date(2025, 4, 1)).tolist() == [True, True, False, False]


def test_keep_thresholds_keep_the_share_asked():
    s = np.arange(1000) / 1000
    for keep, thr in M.keep_thresholds(s):
        assert abs((s >= thr).mean() - keep) <= 0.002


def c(thr, win, net, trades=100):
    return {"thr": thr, "win": win, "net": net, "trades": trades}


def test_choose_highest_win_with_net_at_least_the_bar_ties_to_lower_threshold():
    cur = [c(0.3, 0.50, 900), c(0.4, 0.55, 1000), c(0.5, 0.60, 800), c(0.45, 0.55, 1200)]
    assert M.choose(cur, 1000)["thr"] == 0.4                         # 0.60 misses the bar; 0.4 vs 0.45 tie -> lower
    assert M.choose(cur, 5000) is None
    assert M.choose([c(0.2, 0.9, 1e9, trades=0)], 0) is None


# ------------------------------------------------------------------------------------------------ statistics
def test_hyper_p_matches_brute_force():
    y = np.array([1, 0, 1, 1, 0, 0, 1, 0, 0])
    for k in (1, 3, 5):
        subs = list(combinations(range(len(y)), k))
        for w in range(k + 1):
            brute = np.mean([y[list(s)].sum() >= w for s in subs])
            assert M.hyper_p(len(y), int(y.sum()), k, w) == pytest.approx(brute)
    assert M.hyper_p(10, 5, 0, 0) == 1.0


def test_net_twin_p_extremes():
    nets = np.arange(-10.0, 10.0)
    rng = np.random.default_rng(1)
    assert M.net_twin_p(nets, 5, nets[-5:].sum(), rng, n=2000) < 0.01
    assert M.net_twin_p(nets, 5, nets[:5].sum(), rng, n=2000) > 0.99


def test_bh_counts_earlier_tests_as_p_one():
    q = M.bh([0.001, 0.02], 2)
    assert q.tolist() == pytest.approx([0.002, 0.02])
    q = M.bh([0.001, 0.02], 20)                         # 18 earlier tests at p = 1
    assert q.tolist() == pytest.approx([0.02, 0.2])
    assert M.bh([0.001, 0.02], 1000).tolist() == pytest.approx([1.0, 1.0])     # capped at 1


def test_lots_net_scales_gross_and_charges():
    charge = lambda q: 40.0 + 0.001 * q * 100          # flat brokerage + a per-quantity part
    assert M.lots_net(2.0, 65, 1, charge) == pytest.approx(130 - charge(65))
    assert M.lots_net(2.0, 65, 3, charge) == pytest.approx(390 - charge(195))


def test_summ_drawdown_worst_month_and_empty():
    d0 = date(2026, 4, 1)
    m = M.summ([100, -300, 50, 400], [d0, d0 + timedelta(1), date(2026, 5, 2), date(2026, 5, 3)])
    assert m["trades"] == 4 and m["win"] == 0.75 and m["net"] == 250 and m["max_dd"] == 300 and m["worst_month"] == -200
    assert M.summ([], [])["trades"] == 0


def test_calibration_bins_cover_every_row():
    p = RNG.uniform(0.3, 0.7, 500)
    y = (RNG.uniform(size=500) < p).astype(int)
    assert sum(b["n"] for b in M.calibration(p, y)) == 500


# ------------------------------------------------------------------------------------------------ models
def _data(n=600, k=8, seed=5):
    r = np.random.default_rng(seed)
    X = r.normal(size=(n, k))
    X[r.uniform(size=X.shape) < 0.05] = np.nan
    y = (np.nan_to_num(X[:, 0]) + r.normal(0, 1, n) > 0).astype(int)
    net = np.where(y == 1, 500.0, -400.0) + r.normal(0, 50, n)
    return X, y, net


@pytest.mark.parametrize("method", ["P151", "G1", "G2", "N1", "E1"])
def test_models_fit_score_and_learn_the_signal(method):
    if method in ("G1", "G2", "E1"):
        pytest.importorskip("lightgbm")
    X, y, net = _data()
    sc, _ = M.fit_score(method, X[:400], y[:400], net[:400], X[400:], [0, 1, 2])
    assert sc.shape == (200,) and np.isfinite(sc).all()
    assert M.auc(sc, y[400:]) > 0.6


def test_mlp_is_deterministic():
    X, y, _ = _data(300)
    a = M.MLP().fit(X[:200], y[:200]).predict_proba(X[200:])[:, 1]
    b = M.MLP().fit(X[:200], y[:200]).predict_proba(X[200:])[:, 1]
    assert np.allclose(a, b)


# ------------------------------------------------------------------------------------------------ real data (skipped in CI)
_STORE = __import__("pathlib").Path.home() / ".cache" / "iraalgo" / "r51" / "clean" / "day_NIFTY.pkl"
_SCRIPT = r'''
import sys, numpy as np
sys.argv = ["x", "design"]
import importlib.util
spec = importlib.util.spec_from_file_location("r163study", "research/hunt/r163_donchian_picker/study.py")
R = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R)
from datetime import date
ctx = R.Ctx()
und, day, er, side = "NIFTY", date(2025, 9, 10), 120, "C"
x = R.B.lv(und)
k = float(round(x.close_at(day, er - 1) / 50) * 50 - 50)
a = R.new_feats(ctx, und, day, er, side, k, 60)
x.idx[day][:, er:] = 1e6                                             # the future, scrambled
for u in R.UNDS:
    y = R.B.lv(u)
    if day in y.idx:
        y.idx[day][:, er:] = 1e6
ctx.vix[day][er:, :] = 1e6
D = ctx.chain(und, day)
for g in D.grid.values():
    g[er:] = 1e6
D._k = {kk: ({n: np.where(np.arange(375) >= er, 1e6, v) for n, v in s.items()} if s else s) for kk, s in D._k.items()}
b = R.new_feats(ctx, und, day, er, side, k, 60)
bad = [n for n in a if not ((np.isnan(a[n]) and np.isnan(b[n])) or a[n] == b[n])]
print("BAD", bad)
assert not bad
'''


@pytest.mark.skipif(not _STORE.exists(), reason="R51 clean store / lake not on this machine")
def test_new_feats_on_real_data_ignore_the_future():
    import os
    import subprocess
    import sys
    root = __import__("pathlib").Path(__file__).resolve().parents[4]
    env = dict(os.environ, R51_CLEAN="1", IRAALGO_LAKE=os.environ.get("IRAALGO_LAKE", str(_STORE.parents[2] / "lake")))
    r = subprocess.run([sys.executable, "-c", _SCRIPT], cwd=root, env=env, capture_output=True, text=True, timeout=900)
    assert r.returncode == 0, r.stdout[-2000:] + r.stderr[-2000:]
