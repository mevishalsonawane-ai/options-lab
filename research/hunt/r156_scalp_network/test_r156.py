"""R156 tests (PREREG section 8).

    python -m pytest research/hunt/r156_scalp_network/test_r156.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r156cfg as K  # noqa: E402
import r156data as DT  # noqa: E402
import r156net as N  # noqa: E402
import r156study as S  # noqa: E402

NROWS = 375


def walk_day(seed: int, start: float = 20000.0, vol: float = 8.0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, vol, NROWS))
    o = np.concatenate([[start], c[:-1]])
    h = np.maximum(o, c) + rng.uniform(0, vol, NROWS)
    lo = np.minimum(o, c) - rng.uniform(0, vol, NROWS)
    return np.vstack([o, h, lo, c])


# ================================================================================================ config
def test_config():
    assert len(K.GRID) == 66 and K.GRID[0] == 4 and K.GRID[-1] + 1 <= K.K4.LAST_ENTRY
    assert len(K.TESTS) == 51 and K.N_PRIOR + len(K.TESTS) == 2013
    assert len(K.A_IDX) == len(K.B_IDX) == 12 and K.NR == 24
    cols = sorted(c for g in K.TAB_GROUPS.values() for c in g)
    assert cols == list(range(K.N_TAB))                       # every table column in exactly one group
    assert [f[0] for f in K.FOLDS] == ["2023", "2024", "2025", "2026q1", "final"]


# ================================================================================================ inputs
def test_scales_use_earlier_sessions_only():
    days = [walk_day(i, vol=4.0 + i) for i in range(30)]
    sc = DT.scales(days)
    assert np.isnan(sc[:K.SCALE_MIN]).all() and np.isfinite(sc[K.SCALE_MIN:]).all()
    days2 = [d.copy() for d in days]
    days2[25] = walk_day(999, vol=80.0)                       # changing session 25 must not move its own scale
    sc2 = DT.scales(days2)
    assert sc2[25] == sc[25] and sc2[26] != sc[26]
    assert sc[25] == pytest.approx(np.mean([DT.day_abs_ret(d) for d in days[5:25]]))


def test_sequence_shape_padding_and_mirror():
    idx = walk_day(1)
    a = DT.sequence(idx, 20, 3e-4, "C")
    assert a.shape == (K.SEQ_LEN, K.SEQ_CH)
    assert (a[:K.SEQ_LEN - 21] == 0).all()                    # minutes before 09:15 are padding
    assert (a[K.SEQ_LEN - 21:, 4] == 1).all()
    p = DT.sequence(idx, 20, 3e-4, "P")
    assert np.allclose(p[:, 0], -a[:, 0]) and np.allclose(p[:, 2], -a[:, 2]) and np.allclose(p[:, 3], -a[:, 3])
    assert np.allclose(p[:, 1], a[:, 1]) and np.allclose(p[:, 4], a[:, 4])
    assert np.abs(a).max() <= K.CLIP


def test_sequence_missing_minute_and_first_bar():
    idx = walk_day(2)
    idx[:, 100] = np.nan
    a = DT.sequence(idx, 120, 3e-4, "C")
    j = K.SEQ_LEN - 1 - (120 - 100)
    assert (a[j] == 0).all()
    want = np.log(idx[3, 101] / idx[3, 99]) / 3e-4            # the return skips the missing minute
    assert a[j + 1, 0] == pytest.approx(np.clip(want, -K.CLIP, K.CLIP), rel=1e-4)
    b = DT.sequence(idx, 4, 3e-4, "C")
    assert b[K.SEQ_LEN - 5, 0] == pytest.approx(np.clip(np.log(idx[3, 0] / idx[0, 0]) / 3e-4, -K.CLIP, K.CLIP), rel=1e-4)


def test_inputs_lookahead_guard():
    """The sequence and the extra numbers at s are unchanged when every bar and VIX minute after s is erased."""
    idx = walk_day(3)
    vix = 14 + np.cumsum(np.random.default_rng(3).normal(0, 0.01, NROWS))
    for s in (4, 59, 150, 329):
        cut, vc = idx.copy(), vix.copy()
        cut[:, s + 1:] = np.nan
        vc[s + 1:] = np.nan
        for side in "CP":
            assert np.array_equal(DT.sequence(idx, s, 3e-4, side), DT.sequence(cut, s, 3e-4, side))
            a = DT.extras(idx, s, side, 3e-4, vix, 120.0, False, 2, 1)
            b = DT.extras(cut, s, side, 3e-4, vc, 120.0, False, 2, 1)
            assert np.allclose(a, b, equal_nan=True)


def test_extras_layout():
    idx = walk_day(4)
    x = DT.extras(idx, 100, "P", 3e-4, None, 150.0, True, 4, 3)
    assert len(x) == 16 and len(x) + 27 == K.N_TAB
    assert np.isnan(x[0]) and np.isnan(x[1])                  # no VIX
    assert x[6] == 1.0 and x[7] == 1.0
    assert list(x[11:16]) == [0, 0, 0, 1, 0]
    px = idx[3, 100]
    assert x[8] == pytest.approx(10 / px / 3e-4, rel=1e-5) and x[10] == pytest.approx(15 / px / 3e-4, rel=1e-5)
    c = DT.extras(idx, 100, "C", 3e-4, None, 150.0, True, 4, 3)
    assert c[4] == pytest.approx(-x[4])                       # the move since the open is side-signed


def test_labels_match_r154_engine():
    try:
        import r155study as S5
        M = S5.mods()
        import r51data
    except Exception as e:
        pytest.skip(f"engine modules unavailable: {e}")
    Y = M["Y"]
    p = np.full(NROWS, 100.0)
    p[40:] = 118.0
    p[200:] = 80.0
    o = np.concatenate([[p[0]], p[:-1]])
    C = np.vstack([o, np.maximum(o, p), np.minimum(o, p), p, np.full(NROWS, 1e6)]).astype(np.float32)
    idx = np.full((4, NROWS), 25000.0)
    idx[1, 45] = 25013.0
    idx[2, 205] = 24980.0
    D = r51data.Day(date(2032, 5, 4), idx)
    D.atm = np.full(NROWS, 25000.0)
    D.con = {("C", 24950.0): C}
    for er in (20, 38, 195, 340):
        net, xr = DT.labels("NIFTY", D, er, "C", 24950.0, M)
        for j, r in enumerate(K.RULE_IDS):
            t = Y.price("NIFTY", D, er, "C", K.RULES[r], 1, strike=24950.0)
            assert t is not None and abs(t["net"] - net[j]) < 0.01 and t["xr"] == xr[j], (er, r)
    net, xr = DT.labels("NIFTY", D, 20, "P", 25050.0, M)        # no such contract
    assert np.isnan(net).all() and (xr == -1).all()


# ================================================================================================ folds
def test_folds_never_train_on_or_after_the_fold():
    days = np.array([date(2022, 6, 1).toordinal() + i for i in range(1500)])
    for name, first, last in K.FOLDS:
        tr, sc = N.fold_masks(days, first, last)
        assert days[tr].max() < first.toordinal()
        assert days[sc].min() >= first.toordinal() and days[sc].max() <= last.toordinal()
        assert not (tr & sc).any()


def test_validation_is_the_last_tenth_of_sessions():
    days = np.repeat(np.arange(1000, 1100), 7)
    fit, val = N.val_split(days)
    assert len(np.unique(days[val])) == 10 and days[val].min() == 1090 and days[fit].max() == 1089


def test_standardiser_and_targets():
    tab = np.array([[1.0, np.nan], [3.0, 4.0], [5.0, 8.0]], np.float32)
    st = N.Standardiser().fit(tab)
    z = st.apply(np.array([[np.nan, 6.0]], np.float32))
    assert z[0, 0] == pytest.approx(0.0, abs=1e-6) and z[0, 1] == pytest.approx(0.0, abs=1e-6)
    win, val, mask = N.targets(np.array([[500.0, np.nan, -200.0, 50_000.0]], np.float32))
    assert list(win[0]) == [1, 0, 0, 1] and list(mask[0]) == [1, 0, 1, 1]
    assert val[0, 0] == pytest.approx(0.5) and val[0, 3] == K.CLIP


def test_network_shapes_and_learning():
    torch = pytest.importorskip("torch")
    rng = np.random.default_rng(0)
    n = 20000
    seq = rng.normal(size=(n, K.SEQ_LEN, K.SEQ_CH)).astype(np.float32)
    tab = rng.normal(size=(n, K.N_TAB)).astype(np.float32)
    net = np.where(tab[:, :1] > 0, 500.0, -500.0).repeat(K.NR, 1).astype(np.float32)   # column 0 decides every scalp
    days = np.repeat(np.arange(100), n // 100)
    model, hist = N.train(seq, tab, net, days, seed=1, epochs=8)
    p, ev = N.predict(model, seq, tab)
    assert p.shape == (n, K.NR) and ev.shape == (n, K.NR)
    assert ((p[:, 0] > 0.5) == (tab[:, 0] > 0)).mean() > 0.9
    assert len(hist) == 8 and hist[-1][2] < hist[0][2]          # the validation loss fell


# ================================================================================================ trading rules
def _toy(n_mom: int = 6):
    """One session: n_mom moments (s = 4, 9, ...), call row then put row."""
    s = np.repeat(np.arange(n_mom) * 5 + 4, 2).astype(np.int64)
    net = np.full((2 * n_mom, K.NR), 100.0, np.float32)
    xr = np.tile((s + 3)[:, None], (1, K.NR)).astype(np.int64)
    return s, net, xr, np.array([0, 2 * n_mom], np.int64)


def test_walk_one_open_day_cap_and_side():
    s, net, xr, sess = _toy(8)
    score = np.tile([1.0, 2.0], 8)                              # the put always scores higher
    rule = np.zeros(16, np.int64)
    rows, cols = S.walk(sess, s, score, rule, net, xr, np.zeros(16), True, 5)
    assert list(rows) == [1, 3, 5, 7, 9] and (cols == 0).all()  # exits at s + 3 free the next moment; 5 a day
    xr[:] = (s + 7)[:, None]                                    # exits after the next moment's entry
    rows, _ = S.walk(sess, s, score, rule, net, xr, np.zeros(16), True, 5)
    assert list(rows) == [1, 5, 9, 13]


def test_walk_threshold_missing_label_and_strictness():
    s, net, xr, sess = _toy(3)
    score = np.array([0.0, -1.0, 0.5, 0.2, 0.9, 0.1])
    rule = np.zeros(6, np.int64)
    rows, _ = S.walk(sess, s, score, rule, net, xr, np.zeros(6), True, 5)
    assert list(rows) == [2, 4]                                 # 0 is not > 0
    rows, _ = S.walk(sess, s, score, rule, net, xr, np.zeros(6), False, 5)
    assert list(rows) == [0, 2, 4]                              # >= for TOP
    net[2, 0] = np.nan                                          # no fill: no trade
    rows, _ = S.walk(sess, s, score, rule, net, xr, np.zeros(6), True, 5)
    assert list(rows) == [4]


def test_scores_for():
    p = np.random.default_rng(0).random((4, K.NR)).astype(np.float32)
    ev = np.random.default_rng(1).normal(size=(4, K.NR)).astype(np.float32)
    thr = np.linspace(0.5, 0.9, K.NR)
    sc, rule, th, strict = S.scores_for("EV-A12s18t30", p, ev, thr)
    j = K.RULE_IDS.index("A12s18t30")
    assert np.allclose(sc, ev[:, j]) and (rule == j).all() and strict and (th == 0).all()
    sc, rule, th, strict = S.scores_for("TOP-B10s10t15", p, ev, thr)
    j = K.RULE_IDS.index("B10s10t15")
    assert np.allclose(sc, p[:, j]) and not strict and th[0] == pytest.approx(thr[j])
    sc, rule, _, _ = S.scores_for("BEST-B", p, ev, thr)
    assert all(r in K.B_IDX for r in rule) and np.allclose(sc, ev[:, list(K.B_IDX)].max(1))
    sc, rule, _, _ = S.scores_for("BEST", p, ev, thr)
    assert np.allclose(sc, ev.max(1)) and (rule == ev.argmax(1)).all()


def test_sessions_of():
    und = np.array([0, 0, 0, 0, 1, 1], np.int8)
    day = np.array([5, 5, 6, 6, 5, 5])
    assert list(S.sessions_of(und, day)) == [0, 2, 4, 6]


def test_twin_totals_draw_inside_the_session_and_rule():
    net = np.full((8, K.NR), np.nan, np.float32)
    net[:4, 0] = [10, 10, 10, 10]                               # session 0, rule 0: every draw is 10
    net[4:, 0] = [1, 2, 3, 4]                                   # session 1, rule 0
    net[4:, 1] = [100, 100, 100, np.nan]
    sess = np.array([0, 4, 8])
    rng = np.random.default_rng(0)
    tt = S.twin_totals(sess, net, np.array([0, 1, 5]), np.array([0, 0, 1]), 200, rng)
    assert np.all(tt == 120.0)
    tt = S.twin_totals(sess, net, np.array([4, 5]), np.array([0, 0]), 400, rng)
    assert tt.min() >= 3 and tt.max() <= 7 and len(np.unique(tt)) > 2
    assert S.twin_p(8.0, tt) == pytest.approx(1 / 401)
    assert np.all(S.twin_totals(sess, net, np.array([], int), np.array([], int), 5, rng) == 0)


def test_calibration_stats_bh_gates():
    p = np.linspace(0.01, 0.99, 1000)
    net = np.where(np.arange(1000) % 2 == 0, 100.0, -50.0)
    cal = S.calibration(p, net)
    assert len(cal) == 10 and cal[0]["forecast"] < cal[-1]["forecast"] and sum(c["n"] for c in cal) == 1000
    assert cal[0]["win"] == 0.5 and cal[0]["be_win"] == pytest.approx(50 / 150, abs=1e-3)
    st = S.stats(np.array([100.0, -300.0, 50.0]), np.array([1, 1, 2]))
    assert st["trades"] == 3 and st["net"] == -150 and st["worst_day"] == -200 and st["max_dd"] == 200
    assert st["be_win"] == pytest.approx(300 / 375, abs=1e-4)
    assert S.stats(np.array([]), np.array([]))["trades"] == 0
    assert S.bh([0.0005], 2013)[0] == pytest.approx(1.0)
    ok = dict(net=5, trades=50)
    assert S.gates(ok, ok, ok, 0.05)["passed"]
    assert not S.gates(ok, dict(net=-1, trades=50), ok, 0.05)["passed"]
    assert "final trades" in S.gates(ok, ok, dict(net=5, trades=10), 0.05)["fails"][0]
