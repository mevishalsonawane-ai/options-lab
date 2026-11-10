"""R158 tests (PREREG section 5).

    python -m pytest research/hunt/r158_beat_breadth_donchian/test_r158.py -q
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r158cfg as K  # noqa: E402
import r158data as DT  # noqa: E402
import r158study as S  # noqa: E402

NROWS = 375


def test_config():
    assert K.TESTS == ("NET-EV", "NET-TOP5", "NET-TOP1", "PICK", "PICK50") and K.N_PRIOR + len(K.TESTS) == 2132
    assert K.SEQ_CH == 13 and K.MAX_PER_SIDE == 2


def _mods():
    try:
        import r155study as S5
        M = S5.mods()
        import r51data
        return M, r51data
    except Exception as e:
        pytest.skip(f"engine modules unavailable: {e}")


def _day(d, expiry=False):
    M, r51data = _mods()
    rng = np.random.default_rng(3)
    con = {}
    for side, k in (("C", 24950.0), ("P", 25050.0)):
        p = 150 + np.cumsum(rng.normal(0, 2.0, NROWS))
        o = np.concatenate([[p[0]], p[:-1]])
        con[(side, k)] = np.vstack([o, np.maximum(o, p) + 0.5, np.minimum(o, p) - 0.5, p, np.full(NROWS, 1e6)]).astype(np.float32)
    D = r51data.Day(d, np.full((4, NROWS), 25000.0))
    D.atm = np.full(NROWS, 25000.0)
    D.con = con
    D.expiry = expiry
    return M, D


def test_labels_equal_the_arms_engine_and_skip_expiry():
    M, D = _day(date(2034, 3, 1))
    s = np.array([4, 4, 59, 59, 329, 329])
    side = np.array([1, 0, 1, 0, 1, 0])
    net, xr = DT.day_labels("NIFTY", D, s, side, M)
    import r64engine as G
    ex = M["A"]._xl()
    for i in range(6):
        sd = "C" if side[i] else "P"
        k = M["E"].strike_for(D, "NIFTY", sd, int(s[i]), 1)
        want = G.run_day("NIFTY", D, [sd], [k], [int(s[i]) + 1], ex)[0]        # the arm's own pricing (R64's run_day)
        assert want is not None and abs(want["net"] - net[i]) < 0.01 and want["xr"] == xr[i]
    M, D2 = _day(date(2034, 3, 2), expiry=True)
    net, xr = DT.day_labels("NIFTY", D2, s, side, M)
    assert np.isnan(net).all() and (xr == -1).all()


def _toy(n=8):
    s = np.repeat(np.arange(n) * 5 + 4, 2).astype(np.int64)
    return s, np.full(2 * n, 100.0, np.float32), (s + 3).astype(np.int64), np.array([0, 2 * n], np.int64)


def test_walk_sides_two_a_side_and_one_open():
    s, net, xr, sess = _toy()
    score = np.ones(16)
    rows = S.walk_sides(sess, s, score, np.zeros(16), net, xr, 2)
    assert list(rows) == [0, 1, 2, 3]                      # two calls and two puts, then the day's caps
    xr[:] = s + 12                                         # a position stays open over the next two moments
    rows = S.walk_sides(sess, s, score, np.zeros(16), net, xr, 2)
    assert list(rows) == [0, 1, 6, 7]


def test_walk_sides_threshold_and_missing_label():
    s, net, xr, sess = _toy(3)
    score = np.array([0.0, 2.0, np.nan, 0.5, 1.0, 1.0])
    thr = np.array([0, 0, 0, 0.5, 0, 0.0])
    net[5] = np.nan
    rows = S.walk_sides(sess, s, score, thr, net, xr, 2)
    assert list(rows) == [1, 4]                            # 0 is not > 0; NaN score; 0.5 is not > 0.5; no label


def test_pick_fn_unscored_is_taken():
    ex = dict(und=np.array([0, 0], np.int8), day=np.array([738000, 738000]), s=np.array([9, 9], np.int16),
              side=np.array([1, 0], np.int8))
    fc = dict(ev=np.array([50.0, -20.0], np.float32), PICK50=np.array([60.0, -30.0], np.float32))
    d = date.fromordinal(738000)
    keep = S.pick_fn(ex, fc, "PICK")
    assert keep("NIFTY", d, 10, "C") and not keep("NIFTY", d, 10, "P")
    assert keep("NIFTY", d, 340, "C") and keep("BANKNIFTY", d, 10, "P")      # no forecast: taken
    keep = S.pick_fn(ex, fc, "PICK50")
    assert not keep("NIFTY", d, 10, "C") and keep("NIFTY", d, 10, "P")


def test_beats_and_gates():
    b0 = dict(P2=dict(trades=100, win=0.46, net=1000), P3=dict(trades=100, win=0.46, net=1000))
    good = dict(trades=80, win=0.50, net=1500)
    assert S.beats(good, b0["P2"]) and not S.beats(dict(trades=80, win=0.50, net=900), b0["P2"])
    assert not S.beats(dict(trades=80, win=0.45, net=2000), b0["P2"])
    assert S.gates(dict(P2=good, P3=good), b0, 0.05, False)["passed"]
    g = S.gates(dict(O1=dict(trades=9, win=0.5, net=-1), P2=good, P3=good), b0, 0.05, True)
    assert not g["passed"] and "O1" in g["fails"][0]
    g = S.gates(dict(P2=good, P3=dict(trades=30, win=0.6, net=2000)), b0, 0.5, False)
    assert len(g["fails"]) == 2


def test_network_shape_and_full_stats():
    torch = pytest.importorskip("torch")
    lg, ev = S.make_net()(torch.zeros(2, 60, K.SEQ_CH), torch.zeros(2, K.K6.N_TAB))
    assert lg.shape == (2, 1) and ev.shape == (2, 1)
    st = S.full_stats(np.array([500.0, 400, 300, 200, 100, 50, -900]), np.arange(7))
    assert st["ex_top5"] == 650 - 1500 and st["largest_loss"] == -900
