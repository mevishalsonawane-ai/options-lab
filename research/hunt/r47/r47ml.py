"""R47 family M1: gradient-boosted trees predict the index's direction from a decision minute to 15:14, from underlying
and option-chain features known at that minute. Trained only on sessions BEFORE the predicted ones.

Decision minutes: 10:15, 11:45, 13:15 (the close of the minute before). Label: index close at 15:14 > close at the
decision minute (used only for training rows). Walk-forward: block B2 is predicted by a model trained on B1, B3 on
B1-B2, B4 on B1-B3; the holdout by a model trained on all design sessions (r47holdout).
"""
from __future__ import annotations

import numpy as np

import r47cfg as K
import r47sig as S

TIMES = ((10, 15), (11, 45), (13, 15))
FEATURES = ("r_open", "r_prev", "gap", "r30", "twap_dist", "or_pos", "prev_ret", "vix_prev", "vix_chg", "dte", "dow",
            "pcr_rank", "iv_rank", "minute")


def feature_rows(u, f=None):
    """(di, m, X, y) for every session from FIRST_DAY and decision time; y is NaN when 15:14 has no close."""
    f = S.day_features(u) if f is None else f
    out_di, out_m, X, y = [], [], [], []
    ranks = {}
    for at in TIMES:
        m = K.mi(*at) - 1
        ranks[m] = (S.prev_rank(S.chain_series(u, m)), S.prev_rank(u.stores["WEEK"].iv_atm[:, m].astype(np.float64)))
    for di in S.sessions_from(u):
        if di < 2:
            continue
        o0, pc, ppc = u.O[di, 0], u.dclose[di - 1], u.dclose[di - 2]
        tw = S.twap(u.H[di], u.L[di], u.C[di])
        hi30, lo30 = np.nanmax(u.H[di, :30]) if np.isfinite(u.H[di, :30]).any() else np.nan, \
            np.nanmin(u.L[di, :30]) if np.isfinite(u.L[di, :30]).any() else np.nan
        for at in TIMES:
            m = K.mi(*at) - 1
            c = u.C[di, m]
            if not np.isfinite([c, o0, pc, ppc]).all():
                continue
            c30 = S.last_close_before(u.C[di], m - 30)
            v = S.last_close_before(u.vixC[di], m)
            row = [c / o0 - 1, c / pc - 1, o0 / pc - 1, c / c30 - 1 if np.isfinite(c30) else np.nan,
                   c / tw[m] - 1 if np.isfinite(tw[m]) else np.nan,
                   (c - lo30) / (hi30 - lo30) if np.isfinite(hi30) and hi30 > lo30 else np.nan,
                   pc / ppc - 1, f["vix_prev"][di], v / f["vix_prev"][di] - 1 if np.isfinite(v) else np.nan,
                   min(f["dte"][di], 10), f["dow"][di], ranks[m][0][di], ranks[m][1][di], m]
            end = S.last_close_before(u.C[di], K.SQ - 1)
            out_di.append(di)
            out_m.append(m)
            X.append(row)
            y.append(float(end > c) if np.isfinite(end) else np.nan)
    return np.array(out_di, dtype=np.int64), np.array(out_m, dtype=np.int64), np.array(X, dtype=np.float64), np.array(y)


def _model():
    from sklearn.ensemble import HistGradientBoostingClassifier
    return HistGradientBoostingClassifier(max_depth=3, max_iter=200, learning_rate=0.05, min_samples_leaf=40,
                                          random_state=K.SEED)


def fit_predict(di, X, y, train_mask, test_mask):
    ok = train_mask & np.isfinite(y)
    p = np.full(len(di), np.nan)
    if ok.sum() < 200 or not test_mask.any():
        return p
    X = X.copy()
    X[:, ~np.isfinite(X[ok]).any(axis=0)] = 0.0         # a feature with no value in training (SENSEX chain before 2023)
    mdl = _model().fit(X[ok], y[ok].astype(int))
    p[test_mask] = mdl.predict_proba(X[test_mask])[:, 1]
    return p


def wf_probs(u, rows=None):
    """Walk-forward P(up) per row: each design block from B2 on is predicted by a model fitted on the earlier blocks."""
    di, m, X, y = feature_rows(u) if rows is None else rows
    from r47run import block_of
    blk = block_of(u.days[di])
    p = np.full(len(di), np.nan)
    for b in range(1, len(K.BLOCKS)):
        q = fit_predict(di, X, y, (blk >= 0) & (blk < b), blk == b)
        p = np.where(np.isfinite(q), q, p)
    return di, m, p


def events_from_probs(di, m, p, th):
    rows = []
    for a, b, q in zip(di, m, p):
        if not np.isfinite(q):
            continue
        s = 1 if q > th else -1 if q < 1 - th else 0
        if s:
            rows.append((int(a), int(b), s, -1))
    return S._ev(rows, (K.mi(*TIMES[0]) - 1, K.mi(*TIMES[-1]) - 1))
