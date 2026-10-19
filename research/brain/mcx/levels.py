"""The five-level MCX brain, levels 1-3 (PREREG sections 4-6): specialists, the sequence encoder, the committee.

run_fold(syms, train_end_exclusive, test_days) fits every model on the training sessions of one fold and returns the
test rows' forecasts. The same function, with the design sessions as training and the holdout sessions as test, is
the one holdout read (study.py). Settings are the pre-registered ones; nothing is tuned.

Level 1 specialists (per symbol): A range (log next-60 close range), B persistence (does the last hour continue?),
C direction (next hour up?); D session (US open) and E events are daily/event rows: see session_event.py.
Level 2 encoder: a self-supervised MLP autoencoder 144-64-16-64-144 on the 120-minute window (tensors.encoder_inputs),
all symbols pooled, fitted per fold; the 16 bottleneck values are the market-state vector. PCA-16 is its baseline.
Level 3 committee: size = mean of A ridge, A HGB, A HGB + state, with an 80% band from inner out-of-time residuals and
abstention when the members disagree; direction = logistic stack of B and C (+ state) on the inner 30%, isotonic
calibration, acting only at p >= 0.55 or <= 0.45.
"""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd
from sklearn.cluster import KMeans
from sklearn.decomposition import PCA
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from sklearn.exceptions import ConvergenceWarning
from sklearn.impute import SimpleImputer
from sklearn.isotonic import IsotonicRegression
from sklearn.linear_model import LogisticRegression, Ridge
from sklearn.neural_network import MLPRegressor
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from . import features as F, tensors

HGB = dict(max_iter=200, learning_rate=0.05, max_leaf_nodes=15, min_samples_leaf=50, l2_regularization=1.0,
           random_state=0, early_stopping=False)
ENC_MAX_ROWS = 40_000
STATE = [f"s{i:02d}" for i in range(16)]
PCAC = [f"p{i:02d}" for i in range(16)]
ACT_HI, ACT_LO = 0.55, 0.45
INNER = 0.7


def hgb_reg():
    return HistGradientBoostingRegressor(loss="absolute_error", **HGB)


def hgb_clf():
    return HistGradientBoostingClassifier(loss="log_loss", **HGB)


def ridge():
    return make_pipeline(SimpleImputer(strategy="median"), StandardScaler(), Ridge(alpha=1.0))


def logit():
    return make_pipeline(SimpleImputer(strategy="median"), StandardScaler(), LogisticRegression(C=1.0, max_iter=2000))


# ============================================================================================== level 2: encoder
class Encoder:
    """MLP autoencoder 144-64-16-64-144 (tanh); state = the 16 bottleneck values."""

    def __init__(self):
        self.mlp = MLPRegressor(hidden_layer_sizes=(64, 16, 64), activation="tanh", solver="adam", alpha=1e-4,
                                max_iter=200, early_stopping=False, random_state=0)
        self.pca = PCA(n_components=16, random_state=0)

    def fit(self, X: np.ndarray) -> "Encoder":
        X = X.reshape(len(X), -1).astype(np.float64)
        if len(X) > ENC_MAX_ROWS:
            X = X[np.random.default_rng(0).choice(len(X), ENC_MAX_ROWS, replace=False)]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", ConvergenceWarning)
            self.mlp.fit(X, X)
        self.pca.fit(X)
        self.recon_mse = float(np.mean((self.mlp.predict(X) - X) ** 2))
        self.pca_mse = float(np.mean((self.pca.inverse_transform(self.pca.transform(X)) - X) ** 2))
        return self

    def state(self, X: np.ndarray) -> np.ndarray:
        h = X.reshape(len(X), -1).astype(np.float64)
        for W, b in zip(self.mlp.coefs_[:2], self.mlp.intercepts_[:2]):
            h = np.tanh(h @ W + b)
        return h.astype(np.float32)

    def pcs(self, X: np.ndarray) -> np.ndarray:
        return self.pca.transform(X.reshape(len(X), -1).astype(np.float64)).astype(np.float32)


def decision_frame(S) -> pd.DataFrame:
    """One symbol's decision rows: features, labels, the encoder input (NaN-free, or None where no valid window)."""
    rows = np.flatnonzero(S.dec)
    d = S.feats.iloc[rows].reset_index(drop=True)
    d.insert(0, "row", rows)
    d.insert(1, "sym", S.sym)
    d["day"] = d.ts.dt.date
    for c in ("y_logrange_60m", "y_ret_60m", "y_range_60m"):
        d[c] = S.y[c].to_numpy()[rows]
    ends = tensors.valid_ends(S.cube)
    d["has_win"] = np.isin(rows, ends)
    return d


def encoder_inputs(S, d: pd.DataFrame) -> np.ndarray:
    X = np.full((len(d), 24, 6), np.nan, np.float32)
    ok = d.has_win.to_numpy()
    if ok.any():
        X[ok] = tensors.encoder_inputs(S.cube, S.feats, d.row.to_numpy()[ok])
    return X


# ============================================================================================== levels 1 and 3
def _xy(d, cols):
    return d[cols].to_numpy(np.float64)


def _persist_target(d):
    a, b = d.ret_60m.to_numpy(float), d.y_ret_60m.to_numpy(float)
    ok = np.isfinite(a) & np.isfinite(b) & (a != 0) & (b != 0)
    return ok, (np.sign(a) == np.sign(b)).astype(int)


def _dir_target(d):
    b = d.y_ret_60m.to_numpy(float)
    ok = np.isfinite(b) & (b != 0)
    return ok, (b > 0).astype(int)


def _fit_set(tr: pd.DataFrame, X: list[str], XS: list[str], XP: list[str]) -> dict:
    """Every level-1 model of one symbol fitted on tr. Returns the fitted models (missing when too few rows)."""
    M = {}
    okA = np.isfinite(tr.y_logrange_60m.to_numpy(float))
    if okA.sum() >= 200:
        yA = tr.y_logrange_60m.to_numpy(float)[okA]
        M["A_ridge"] = ridge().fit(_xy(tr[okA], X), yA)
        M["A_hgb"] = hgb_reg().fit(_xy(tr[okA], X), yA)
        M["A_hgbS"] = hgb_reg().fit(_xy(tr[okA], XS), yA)
        M["A_hgbP"] = hgb_reg().fit(_xy(tr[okA], XP), yA)
    okB, yB = _persist_target(tr)
    if okB.sum() >= 200 and 0 < yB[okB].mean() < 1:
        M["B_hgb"] = hgb_clf().fit(_xy(tr[okB], X), yB[okB])
        M["B_lin"] = logit().fit(_xy(tr[okB], X), yB[okB])
        M["B_rate"] = float(yB[okB].mean())
    okC, yC = _dir_target(tr)
    if okC.sum() >= 200 and 0 < yC[okC].mean() < 1:
        M["C_hgb"] = hgb_clf().fit(_xy(tr[okC], X), yC[okC])
        M["C_hgbS"] = hgb_clf().fit(_xy(tr[okC], XS), yC[okC])
        M["C_hgbP"] = hgb_clf().fit(_xy(tr[okC], XP), yC[okC])
        M["C_lin"] = logit().fit(_xy(tr[okC], X), yC[okC])
        M["C_rate"] = float(yC[okC].mean())
    # B-iv: a + b log(iv_range_60m), fitted on training rows with IV
    iv = tr.iv_range_60m.to_numpy(float)
    ok = okA & np.isfinite(iv) & (iv > 0)
    if ok.sum() >= 50:
        M["Biv"] = np.polyfit(np.log(iv[ok]), tr.y_logrange_60m.to_numpy(float)[ok], 1)
    M["A_mean"] = float(np.nanmean(tr.y_logrange_60m)) if okA.any() else np.nan
    return M


def _predict(M: dict, d: pd.DataFrame, X, XS, XP) -> pd.DataFrame:
    out = pd.DataFrame(index=d.index)
    for k, cols in (("A_ridge", X), ("A_hgb", X), ("A_hgbS", XS), ("A_hgbP", XP)):
        out[k] = M[k].predict(_xy(d, cols)) if k in M else np.nan
    for k, cols in (("B_hgb", X), ("B_lin", X), ("C_hgb", X), ("C_hgbS", XS), ("C_hgbP", XP), ("C_lin", X)):
        out[k] = M[k].predict_proba(_xy(d, cols))[:, 1] if k in M else np.nan
    out["B_rate"] = M.get("B_rate", np.nan)
    out["C_rate"] = M.get("C_rate", np.nan)
    # baselines of L1-A
    last = d.log_range_60m.to_numpy(float)
    last = np.where(np.isfinite(last), last, M.get("A_mean", np.nan))
    tod = d.tod_logrange_60m.to_numpy(float)
    out["b_last"] = last
    out["b_tod"] = np.where(np.isfinite(tod), tod, last)
    iv = d.iv_range_60m.to_numpy(float)
    if "Biv" in M:
        with np.errstate(divide="ignore", invalid="ignore"):
            biv = np.polyval(M["Biv"], np.log(iv))
        out["b_iv"] = np.where(np.isfinite(biv), biv, out["b_tod"])
    else:
        out["b_iv"] = out["b_tod"]
    # committee inputs
    out["size_comm"] = out[["A_ridge", "A_hgb", "A_hgbS"]].mean(axis=1)
    out["size_spread"] = out[["A_ridge", "A_hgb", "A_hgbS"]].std(axis=1, ddof=0)
    r = d.ret_60m.to_numpy(float)
    out["pB_up"] = np.where(r > 0, out.B_hgb, np.where(r < 0, 1 - out.B_hgb, 0.5))
    return out


def _logit(p):
    p = np.clip(np.asarray(p, float), 1e-4, 1 - 1e-4)
    return np.log(p / (1 - p))


def _stack_X(P: pd.DataFrame, d: pd.DataFrame) -> np.ndarray:
    st = d[STATE].to_numpy(np.float64)
    return np.column_stack([_logit(P.C_hgb.fillna(0.5)), _logit(P.pB_up.fillna(0.5)), np.nan_to_num(st)])


def run_fold(frames: dict[str, pd.DataFrame], enc_in: dict[str, np.ndarray], train_days, test_days,
             log=print) -> tuple[pd.DataFrame, dict]:
    """frames: sym -> decision_frame; enc_in: sym -> encoder inputs. train_days / test_days: sets of dates.
    Returns (test rows with every forecast, fold info)."""
    X = F.names()
    XS, XP = X + STATE, X + PCAC
    # ---- level 2: encoder on the training windows of every symbol
    tr_win = np.concatenate([enc_in[s][frames[s].day.isin(train_days).to_numpy() & frames[s].has_win.to_numpy()]
                             for s in frames])
    enc = Encoder().fit(tr_win)
    info = {"encoder_rows": int(min(len(tr_win), ENC_MAX_ROWS)), "encoder_recon_mse": enc.recon_mse,
            "pca_recon_mse": enc.pca_mse, "thresholds": {}}
    # market states: KMeans (k 6, seed 0) on the training states (PREREG section 5, descriptive)
    tr_states = enc.state(tr_win)
    if len(tr_states) > ENC_MAX_ROWS:
        tr_states = tr_states[np.random.default_rng(0).choice(len(tr_states), ENC_MAX_ROWS, replace=False)]
    km = KMeans(n_clusters=6, random_state=0, n_init=10).fit(tr_states)
    info["state_centres"] = km.cluster_centers_.tolist()
    info["encoder"] = enc
    outs = []
    for s, d in frames.items():
        d = d.copy()
        st = np.full((len(d), 16), np.nan, np.float32)
        pc = np.full((len(d), 16), np.nan, np.float32)
        ok = d.has_win.to_numpy()
        if ok.any():
            st[ok] = enc.state(enc_in[s][ok])
            pc[ok] = enc.pcs(enc_in[s][ok])
        d[STATE] = st
        d[PCAC] = pc
        d["regime"] = np.where(ok, km.predict(np.nan_to_num(st)), -1)
        tr = d[d.day.isin(train_days)]
        te = d[d.day.isin(test_days)]
        if len(tr) < 500 or not len(te):
            continue
        tdays = sorted(set(tr.day))
        cut = tdays[int(len(tdays) * INNER)]
        fit, cal = tr[tr.day < cut], tr[tr.day >= cut]
        # ---- inner fit -> calibration rows (out of time)
        Mi = _fit_set(fit, X, XS, XP)
        Pc = _predict(Mi, cal, X, XS, XP)
        resid = cal.y_logrange_60m.to_numpy(float) - Pc.size_comm.to_numpy()
        okr = np.isfinite(resid)
        band = np.quantile(resid[okr], [0.1, 0.9]) if okr.sum() > 50 else np.array([np.nan, np.nan])
        spread_thr = float(np.nanquantile(Pc.size_spread, 0.8)) if okr.any() else np.nan
        okC, yC = _dir_target(cal)
        stack, iso = None, None
        if "C_hgb" in Mi and "B_hgb" in Mi and okC.sum() >= 200 and 0 < yC[okC].mean() < 1:
            Zc = _stack_X(Pc, cal)[okC]
            stack = LogisticRegression(C=1.0, max_iter=2000).fit(Zc, yC[okC])
            iso = IsotonicRegression(out_of_bounds="clip", y_min=0, y_max=1).fit(stack.predict_proba(Zc)[:, 1], yC[okC])
        cal_size = Pc.size_comm.to_numpy()
        with np.errstate(invalid="ignore", divide="ignore"):
            cal_ratio = np.exp(cal_size) / cal.iv_range_60m.to_numpy(float)
        thr = {"size_q67": float(np.nanquantile(cal_size, 2 / 3)), "size_q80": float(np.nanquantile(cal_size, 0.8)),
               "size_q33": float(np.nanquantile(cal_size, 1 / 3)),
               "ratio_q90": float(np.nanquantile(cal_ratio, 0.9)) if np.isfinite(cal_ratio).any() else np.nan,
               "ratio_q80": float(np.nanquantile(cal_ratio, 0.8)) if np.isfinite(cal_ratio).any() else np.nan,
               "spread_q80": spread_thr, "band": [float(x) for x in band], "inner_cut": str(cut),
               "train_rows": int(len(tr)), "train_sessions": len(tdays)}
        info["thresholds"][s] = thr
        # ---- full training fit -> test rows
        M = _fit_set(tr, X, XS, XP)
        P = _predict(M, te, X, XS, XP)
        P["band_lo"], P["band_hi"] = P.size_comm + band[0], P.size_comm + band[1]
        P["size_abstain"] = P.size_spread > spread_thr
        if stack is not None:
            P["p_dir"] = iso.predict(stack.predict_proba(_stack_X(P, te))[:, 1])
        else:
            P["p_dir"] = np.nan
        P["act"] = np.where(P.p_dir >= ACT_HI, 1, np.where(P.p_dir <= ACT_LO, -1, 0))
        keep = ["row", "sym", "ts", "day", "y_logrange_60m", "y_ret_60m", "y_range_60m", "ret_60m", "ret_30m",
                "iv_range_60m", "dist_session_mean", "log_range_60m", "has_win", "regime"] + STATE
        o = pd.concat([te[keep].reset_index(drop=True), P.reset_index(drop=True)], axis=1)
        for k in ("size_q67", "size_q80", "size_q33", "ratio_q90", "ratio_q80"):
            o["thr_" + k] = thr[k]
        outs.append(o)
        log(f"  {s}: train {len(tr)} rows / {len(tdays)} sessions, test {len(te)} rows")
    return (pd.concat(outs, ignore_index=True) if outs else pd.DataFrame()), info
