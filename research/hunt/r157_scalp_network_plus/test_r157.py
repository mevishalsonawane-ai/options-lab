"""R157 tests (PREREG section 5).

    python -m pytest research/hunt/r157_scalp_network_plus/test_r157.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r157cfg as K  # noqa: E402
import r157data as DT  # noqa: E402
import r157study as S  # noqa: E402
import r156data as DT6  # noqa: E402

NROWS = 375


def walk_day(seed: int, start: float = 20000.0, vol: float = 8.0) -> np.ndarray:
    rng = np.random.default_rng(seed)
    c = start + np.cumsum(rng.normal(0, vol, NROWS))
    o = np.concatenate([[start], c[:-1]])
    return np.vstack([o, np.maximum(o, c) + rng.uniform(0, vol, NROWS), np.minimum(o, c) - rng.uniform(0, vol, NROWS), c])


def test_config():
    assert K.NR == 28 and K.NCOL == 56 and K.SEQ_CH == 13 and len(K.TESTS) == 114
    assert K.RULE_IDS[:24] == K.K6.RULE_IDS and K.RULE_IDS[24:] == ("R10t30", "R10t60", "R15t30", "R15t60")
    assert K.col("A10s10t15", 1) == 0 and K.col("A10s10t15", 3) == 28 and K.col("R15t60", 3) == 55
    assert K.N_PRIOR + len(K.TESTS) == 2127
    assert sorted(j for g in K.GROUPS.values() for j in g) == list(range(28))


def test_cross_sequence_mirror_missing_and_lookahead():
    a, b = walk_day(1), walk_day(2, start=50000.0)
    others = [(a, 3e-4), (None, np.nan), (b, 2e-4), (a, np.nan)]
    x = DT.cross_sequence(others, 100, "C")
    assert x.shape == (60, 8)
    assert np.allclose(x[:, 0], DT6.sequence(a, 100, 3e-4, "C")[:, 0]) and (x[:, 1] == 1).all()
    assert (x[:, 2:4] == 0).all() and (x[:, 6:8] == 0).all()           # no session / no scale: zeros
    assert np.allclose(x[:, 4], DT6.sequence(b, 100, 2e-4, "C")[:, 0])  # each index in its own scale
    p = DT.cross_sequence(others, 100, "P")
    assert np.allclose(p[:, 0], -x[:, 0]) and np.allclose(p[:, 1], x[:, 1])
    cut = [(a.copy(), 3e-4), (None, np.nan), (b.copy(), 2e-4), (a.copy(), np.nan)]
    for idx, _ in cut:
        if idx is not None:
            idx[:, 101:] = np.nan
    assert np.array_equal(DT.cross_sequence(cut, 100, "C"), x)          # nothing after s is read


def _mods():
    try:
        import r155study as S5
        M = S5.mods()
        import r51data
        return M, r51data
    except Exception as e:
        pytest.skip(f"engine modules unavailable: {e}")


def _day(path, d):
    M, r51data = _mods()
    p = np.array(path, float)
    o = np.concatenate([[p[0]], p[:-1]])
    C = np.vstack([o, np.maximum(o, p), np.minimum(o, p), p, np.full(NROWS, 1e6)]).astype(np.float32)
    D = r51data.Day(d, np.full((4, NROWS), 25000.0))
    D.atm = np.full(NROWS, 25000.0)
    D.con = {("C", 24950.0): C}
    return M, D


def test_run_exit_stop_trail_and_time():
    p = np.full(NROWS, 100.0)
    p[30:] = 88.0                                              # falls 12: the 10-point stop
    M, D = _day(p, date(2033, 1, 3))
    net, xr = DT.labels("NIFTY", D, 20, "C", 24950.0, 1, M)
    j = K.RULE_IDS.index("R10t60")
    assert xr[j] == 30 and net[j] < -10 * 75 * 0.9
    p = np.full(NROWS, 100.0)
    p[25:35] = 130.0                                           # +30, then back to 115: the trail (130 - 10 = 120) sells
    p[35:] = 115.0
    M, D = _day(p, date(2033, 1, 4))
    net, xr = DT.labels("NIFTY", D, 20, "C", 24950.0, 1, M)
    assert xr[j] == 35 and net[j] > 10 * 75                    # sold near 115-120, well above a +10 target's profit
    jf = K.RULE_IDS.index("A10s10t30")
    assert net[j] > net[jf] > 0                                # the fixed +10 target made less on the same path
    p = np.full(NROWS, 100.0)
    p[25:30] = 112.0                                           # +12 arms the trail at 112 - 10 = 102; back to 99 -> out near 102
    p[30:] = 99.0
    M, D = _day(p, date(2033, 1, 5))
    net, xr = DT.labels("NIFTY", D, 20, "C", 24950.0, 1, M)
    assert xr[j] == 30 and -75 < net[j] < 3 * 75               # about break-even, not a 10-point loss
    p = np.full(NROWS, 100.0)
    M, D = _day(p, date(2033, 1, 6))
    net, xr = DT.labels("NIFTY", D, 20, "C", 24950.0, 1, M)
    assert xr[K.RULE_IDS.index("R10t30")] == 20 + 30 - 1 and xr[j] == 20 + 60 - 1


def test_three_lots_and_r156_equality():
    p = np.full(NROWS, 100.0)
    p[40:] = 118.0
    M, D = _day(p, date(2033, 2, 1))
    n1, x1 = DT.labels("NIFTY", D, 20, "C", 24950.0, 1, M)
    n3, x3 = DT.labels("NIFTY", D, 20, "C", 24950.0, 3, M)
    old, oxr = DT6.labels("NIFTY", D, 21 - 1, "C", 24950.0, M)
    assert np.allclose(n1[:24], old, atol=0.01) and (x1[:24] == oxr).all()
    j = K.RULE_IDS.index("A15s15t30")
    assert 3 * n1[j] < n3[j] < 3 * n1[j] + 200                 # brokerage is per order: 3 lots net more than 3 x 1 lot
    assert np.isnan(DT.labels("NIFTY", D, 20, "P", 25050.0, 1, M)[0]).all()


def test_scores_for():
    rng = np.random.default_rng(0)
    p, ev = rng.random((5, K.NCOL)).astype(np.float32), rng.normal(size=(5, K.NCOL)).astype(np.float32)
    thr = np.tile(np.linspace(0.6, 0.9, K.NCOL), (5, 1)).astype(np.float32)
    sc, col, th, strict = S.scores_for("EV-R10t30@3", p, ev, thr)
    assert (col == 52).all() and np.allclose(sc, ev[:, 52]) and strict
    sc, col, th, strict = S.scores_for("TOP99-B15s22.5t30@1", p, ev, thr)
    c = K.col("B15s22.5t30", 1)
    assert (col == c).all() and np.allclose(sc, p[:, c]) and not strict and np.allclose(th, thr[:, c])
    sc, col, _, _ = S.scores_for("BEST@3", p, ev, thr)
    assert (col >= 28).all() and np.allclose(sc, ev[:, 28:].max(1)) and (col == 28 + ev[:, 28:].argmax(1)).all()
    sc, col, _, _ = S.scores_for("BEST@1", p, ev, thr)
    assert (col < 28).all()


def test_top_share():
    p = np.linspace(0, 1, 1000)
    net = np.where(p > 0.985, 100.0, -50.0)
    t = S.top_share(p, net, 0.01)
    assert t["n"] == 10 and t["win"] == 1.0 and t["per_trade"] == 100.0
    assert S.top_share(p[:50], net[:50]) is None


def test_network_shape():
    torch = pytest.importorskip("torch")
    m = S.make_net()
    lg, ev = m(torch.zeros(3, 60, K.SEQ_CH), torch.zeros(3, K.K6.N_TAB))
    assert lg.shape == (3, K.NCOL) and ev.shape == (3, K.NCOL)
