"""R90B tests (PREREG_B): confirmation, lock / trail / giveback, no intrabar look-ahead, X0 = R90, look-ahead guard."""
from __future__ import annotations

import numpy as np
import pytest

from research.hunt.r90_almazov_bos import model, r90b
from research.hunt.r90_almazov_bos.structure import aggregate, structure
from research.hunt.r90_almazov_bos.tests.test_r90 import _synthetic

NAN = np.nan


def bars(rows):
    a = np.array(rows, float)
    return a[:, 0].copy(), a[:, 1].copy(), a[:, 2].copy(), a[:, 3].copy()


def ex(rows, lock=False, trail=False, give=False, tgt=NAN, ref=100.0, stop0=95.0, side=1):
    mo, mh, ml, mc = bars(rows)
    return r90b.exit_path(mo, mh, ml, mc, 0, False, side, ref, stop0, tgt, abs(ref - stop0), lock, trail, give, len(mo) - 1)


def test_lock_moves_stop_to_half_r():
    rows = [(100, 106, 99, 105), (105, 105, 102, 103), (103, 104, 100, 101), (101, 101, 101, 101)]
    m, why, px = ex(rows, lock=True)
    assert (m, why, px) == (1, r90b.X_MOVED, 102.5)     # +1R reached in minute 0 -> stop 102.5 hit in minute 1
    m, why, px = ex(rows)                                # no lock: held to the end
    assert why == r90b.X_TIME


def test_trail_follows_best_by_one_r():
    rows = [(100, 104, 99, 104), (104, 110, 103, 109), (109, 109, 104.5, 105), (105, 105, 105, 105)]
    m, why, px = ex(rows, trail=True)
    assert (m, why, px) == (2, r90b.X_MOVED, 105.0)


def test_giveback_half_after_one_r():
    rows = [(100, 104, 99, 104), (104, 110, 103, 109), (109, 109, 104.9, 105), (105, 105, 105, 105)]
    m, why, px = ex(rows, give=True)
    assert (m, why, px) == (2, r90b.X_MOVED, 105.0)
    rows = [(100, 104, 99, 104), (104, 104.5, 96, 97), (97, 97, 97, 97)]
    assert ex(rows, give=True)[1] == r90b.X_TIME          # never reached +1R: no giveback stop


def test_no_intrabar_look_ahead_and_gap():
    # minute 0 reaches +1.2R and dips to 101 in the same minute: the lock applies only from minute 1
    rows = [(100, 106, 101, 103), (103, 103, 102, 102), (102, 102, 102, 102)]
    m, why, px = ex(rows, lock=True)
    assert (m, why, px) == (1, r90b.X_MOVED, 102.5)
    rows = [(100, 101, 99, 100), (93, 94, 92, 93), (93, 93, 93, 93)]
    assert ex(rows)[0:3] == (1, r90b.X_STOP, 93.0)        # gap through the stop: out at the open


def test_short_mirror_and_target():
    rows = [(100, 101, 94, 95), (95, 96, 89, 90), (90, 90, 90, 90)]
    m, why, px = ex(rows, side=-1, ref=100.0, stop0=105.0, tgt=90.0)
    assert (m, why, px) == (1, r90b.X_TARGET, 90.0)


def M_of(rows):
    mo, mh, ml, mc = bars(rows)
    return dict(mo=mo, mh=mh, ml=ml, mc=mc)


def test_confirmation_rules():
    base = [(99.8, 100.2, 99.7, 100.1)]
    good = base + [(100.1, 100.5, 100, 100.4), (100.4, 100.6, 100.2, 100.3), (100.3, 100.8, 100.2, 100.7),
                   (100.7, 100.9, 100.4, 100.5), (100.5, 101, 100.4, 100.9), (100.9, 101, 100.8, 101)] + [(101,) * 4] * 3
    assert r90b.confirmed(M_of(good), 0, 1, 100.0, 95.0, 110.0, 9)
    weak = [list(r) for r in good]
    weak[5] = [100.5, 100.6, 99.5, 99.9]                  # the 5th candle closes back below the entry
    assert not r90b.confirmed(M_of(weak), 0, 1, 100.0, 95.0, 110.0, 9)
    reds = [list(r) for r in good]
    for i in (1, 3, 4):
        reds[i] = [reds[i][3] + 0.2, reds[i][1] + 0.2, reds[i][2], reds[i][3]]   # 3 red candles of 5
    assert not r90b.confirmed(M_of(reds), 0, 1, 100.0, 95.0, 110.0, 9)
    assert not r90b.confirmed(M_of(good), 0, 1, 100.0, 100.35, 110.0, 9)          # stop touched in the 5 minutes
    assert not r90b.confirmed(M_of(good), 0, 1, 100.0, 95.0, 110.0, 6)           # t + 6 not before the flat time


def _setup(seed, days=15, tf=1, htf=4):
    M, mday, ep = _synthetic(seed, days)
    Bk = aggregate(mday, ep, tf, M["mo"], M["mh"], M["ml"], M["mc"])
    Hk = aggregate(mday, ep, htf, M["mo"], M["mh"], M["ml"], M["mc"])
    Sl = structure(Bk["o"], Bk["h"], Bk["l"], Bk["c"], 2)
    V = model.htf_view(Bk["s"], Hk["s"], structure(Hk["o"], Hk["h"], Hk["l"], Hk["c"], 2))
    nd = int(mday.max()) + 1
    sq = np.array([d * 375 + 360 for d in range(nd)])
    return M, Bk, Sl, V, sq, nd


@pytest.mark.parametrize("seed", [3, 6, 8])
def test_x0_equals_r90(seed):
    M, Bk, Sl, V, sq, nd = _setup(seed)
    a = model.run_session(M, Bk, Sl, V, 1.0, 0.05, sq, 9 * 60 + 16, 14 * 60 + 45, np.ones(nd, bool))
    b = r90b.run(M, Bk, Sl, V, 1.0, 0.05, sq, 9 * 60 + 16, 14 * 60 + 45, np.ones(nd, bool), r90b.RULES[0])
    assert len(a) > 0
    assert [(x["t"], x["xm"], x["why"], round(x["pts"], 6)) for x in a] == \
           [(x["t"], x["xm"], x["why"], round(x["pts"], 6)) for x in b]


@pytest.mark.parametrize("seed,rule", [(3, 6), (6, 1), (8, 2)])
def test_look_ahead_guard_b(seed, rule):
    M, Bk, Sl, V, sq, nd = _setup(seed)
    R = r90b.RULES[rule]
    base = r90b.run(M, Bk, Sl, V, 1.0, 0.05, sq, 9 * 60 + 16, 14 * 60 + 45, np.ones(nd, bool), R)
    n = len(M["mc"])
    for cut in (n // 3, 2 * n // 3):
        rng = np.random.default_rng(cut)
        N = {k: v.copy() for k, v in M.items()}
        for k in ("mo", "mh", "ml", "mc"):
            N[k][cut + 1:] = 20000 + rng.normal(0, 300, n - cut - 1)
        N["mh"] = np.maximum.reduce([N["mh"], N["mo"], N["mc"]])
        N["ml"] = np.minimum.reduce([N["ml"], N["mo"], N["mc"]])
        Bn = aggregate(np.repeat(np.arange(nd), 375), (np.repeat(np.arange(nd), 375) + 20000) * 1440
                       + np.tile(9 * 60 + 15 + np.arange(375), nd) - 330, 1, N["mo"], N["mh"], N["ml"], N["mc"])
        Hn = aggregate(np.repeat(np.arange(nd), 375), (np.repeat(np.arange(nd), 375) + 20000) * 1440
                       + np.tile(9 * 60 + 15 + np.arange(375), nd) - 330, 4, N["mo"], N["mh"], N["ml"], N["mc"])
        Sn = structure(Bn["o"], Bn["h"], Bn["l"], Bn["c"], 2)
        Vn = model.htf_view(Bn["s"], Hn["s"], structure(Hn["o"], Hn["h"], Hn["l"], Hn["c"], 2))
        alt = r90b.run(N, Bn, Sn, Vn, 1.0, 0.05, sq, 9 * 60 + 16, 14 * 60 + 45, np.ones(nd, bool), R)
        key = lambda x: (x["t"], x["e"], x["side"], x["entry"], x["stop"], x["xm"], x["why"])  # noqa: E731
        assert [key(x) for x in base if x["xm"] <= cut] == [key(x) for x in alt if x["xm"] <= cut]
