"""R51 family: R41 neural-net rules. Signal source: the INDEX (brain features of the underlying at the decision minute;
option-chain features enter only as inputs).

Deviation (PREREG): torch is not available, so the network is scikit-learn's MLP (64-32, relu, weight decay 1e-4,
early stopping), 3 seeds averaged, on the brain features (columns NaN on > 80% of training rows dropped, median-imputed,
z-scored, clipped to +-5) + one-hot index + time of day; no raw 60-minute sequence channels (the TCN / GRU of R41).
Walk-forward like R41: each block's model is fitted on the first 80% (in time) of its training rows (21 Nov 2024 on,
5-session gap), thresholds are quantiles of its predictions on the last 20% (validation).

R1-R3: direction over h = 15 / 30 / 60 minutes, decisions every 15 minutes from the 09:45 close (rows 29, 44, ...,
t + h <= 374); trade when |p - 0.5| is in the validation top 10%; buy the ATM call (p > 0.5) or put at t+1, sell at the
close of t+h, or after a minute close <= 70% of the fill (next open); square-off 15:20 (15:05 on expiry day); one open
position per index. R4-R6: the same, and the size forecast (an MLP regressor of the next-h range) >= its validation
median. R13: the next-morning gap from the 15:19 close (row 244), non-expiry days, top 20% confidence: 1-ITM bought at
the 15:20 open, the SAME contract sold at the next session's 09:16 open, no stop. ALWAYS-CALL: the 1-ITM call on every
non-expiry night (R41's baseline). Overnight fills: the liq rule (volume of the 5 minutes before) and dated charges.
"""
from __future__ import annotations

import warnings
from functools import lru_cache

import numpy as np
import pandas as pd
from sklearn.neural_network import MLPClassifier, MLPRegressor

import common as C
import engine as E
import ml
import r51cfg as K
import r51data

UNDS = K.UNDS
Q1_ROWS = tuple(range(29, 360, 15))
Q4_ROW = K.row(15, 19)
SEEDS = (0, 1, 2)
SQ = K.row(15, 20)


class Prep:
    def __init__(self, Xtr):
        nan = np.isnan(Xtr).mean(0)
        self.keep = nan <= 0.80
        Z = Xtr[:, self.keep]
        self.med = np.nan_to_num(np.nanmedian(Z, axis=0))
        Zf = np.where(np.isnan(Z), self.med, Z)
        self.mu, self.sd = Zf.mean(0), Zf.std(0)
        self.sd = np.where(self.sd > 1e-9, self.sd, 1.0)

    def __call__(self, X):
        Z = X[:, self.keep]
        Zf = np.where(np.isnan(Z), self.med, Z)
        return np.clip((Zf - self.mu) / self.sd, -5, 5)


def _nets(reg: bool, Xtr, ytr):
    out = []
    for s in SEEDS:
        kw = dict(hidden_layer_sizes=(64, 32), alpha=1e-4, batch_size=256, max_iter=200, early_stopping=True,
                  validation_fraction=0.1, n_iter_no_change=8, random_state=s)
        m = MLPRegressor(**kw) if reg else MLPClassifier(**kw)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            m.fit(Xtr, ytr)
        out.append(m)
    return out


def _pred(ms, X, reg):
    return np.mean([m.predict(X) if reg else m.predict_proba(X)[:, 1] for m in ms], axis=0)


@lru_cache(maxsize=2)
def data(final: bool, kind: str) -> pd.DataFrame:
    rows = Q1_ROWS if kind == "q1" else (Q4_ROW,)
    X = pd.concat([ml.dataset(u, rows, f"r41{kind}") for u in UNDS], ignore_index=True)
    if kind == "q4":
        X["y_gap"] = gap_label(X)
    return X.sort_values(["day", "row", "und"]).reset_index(drop=True)


def gap_label(X):
    """log(next session's 09:16 open / the 15:19 close) %, from the index minutes (NaN on the last day)."""
    out = np.full(len(X), np.nan)
    for i, (u, d) in enumerate(zip(X.und.values, X.day.dt.date.values)):
        days = r51data.load(u)
        ks = sorted(days)
        j = ks.index(d)
        if j + 1 >= len(ks):
            continue
        nd = days[ks[j + 1]]
        if C.final_open() or ks[j + 1] <= K.DESIGN_END:
            out[i] = np.log(nd.idx[0, 1] / days[d].idx[3, Q4_ROW]) * 100
    return out


@lru_cache(maxsize=64)
def predictions(kind: str, target: str, block: str):
    """Block rows with p (direction) and size forecast, plus validation thresholds."""
    ml.guard(block)
    X = data(C.final_open(), kind)
    cols = ml.feature_cols(X)
    _, tr_end, lo, hi = next(b for b in ml.blocks() if b[0] == block)
    tm = ml.train_mask(X, tr_end)
    if kind == "q1":
        h = int(target)
        tm &= (X.row.values + h <= 374)
        y = X[f"y_ret{h}"].values
        ys = X[f"y_rng{h}"].values
    else:
        tm &= ~X.expiry.values
        y = X.y_gap.values
        ys = None
    bm = ml.block_mask(X, lo, hi)
    out = X.loc[bm, ["day", "row", "und", "expiry"]].copy()
    ok = tm & np.isfinite(y)
    idx = np.flatnonzero(ok)
    if len(idx) < (2000 if kind == "q1" else 300):     # R13 has one row a session per index
        out["p"], out["size"] = np.nan, np.nan
        return out, dict(conf=np.inf, size=np.inf, conf20=np.inf, conf5=np.inf)
    cut = idx[int(len(idx) * 0.8)]
    fit_i, val_i = idx[idx < cut], idx[idx >= cut]
    M = ml.matrix(X, cols, UNDS)
    P = Prep(M[fit_i])
    ms = _nets(False, P(M[fit_i]), (y[fit_i] > 0).astype(int))
    pv = _pred(ms, P(M[val_i]), False)
    thr = dict(conf=np.quantile(np.abs(pv - 0.5), 0.90), conf5=np.quantile(np.abs(pv - 0.5), 0.95),
               conf20=np.quantile(np.abs(pv - 0.5), 0.80))
    out["p"] = _pred(ms, P(M[bm]), False) if bm.any() else []
    if ys is not None:
        okr = fit_i[np.isfinite(ys[fit_i])]
        rs = _nets(True, P(M[okr]), np.log1p(ys[okr]))
        thr["size"] = np.median(_pred(rs, P(M[val_i]), True))
        out["size"] = _pred(rs, P(M[bm]), True) if bm.any() else []
    return out, thr


def blocks_for(lo, hi):
    return [b for b in ml.blocks() if b[2] <= hi and b[3] >= lo and (b[0] != "final" or lo >= K.FINAL_START)]


def make_dir(h: int, size: bool, top: str = "conf", stop: str = "close30") -> C.Config:
    ex = (E.Exits(tmax=h, close_stop=0.30, sq_row=SQ) if stop == "close30"
          else E.Exits(tmax=h, stop=0.15, sq_row=SQ))

    def run(lo, hi):
        C.check_range(lo, hi)
        rows = []
        for name, _, blo, bhi in blocks_for(lo, hi):
            P, thr = predictions("q1", str(h), name)
            P = P[(P.day.dt.date >= max(lo, blo)) & (P.day.dt.date <= min(hi, bhi)) & (P.row + h <= 374)]
            sel = np.abs(P.p.values - 0.5) >= thr[top]
            if size:
                sel &= P["size"].values >= thr["size"]
            P = P[sel]
            for (und, d), g in P.groupby(["und", "day"], sort=True):
                D = r51data.load(und)[d.date()]
                cands = []
                for t, p in zip(g.row.values, g.p.values):
                    s = "C" if p > 0.5 else "P"
                    k = E.strike_for(D, und, s, int(t), 0)
                    if k is not None:
                        cands.append(dict(er=int(t) + 1, side=s, strike=k))
                rows += C.take(und, D, cands, ex, scope="all")
        return C.frame(rows)

    return C.Config(f"r41|R{h}|size{int(size)}|{top}|{stop}", run,
                    lambda und: C.TwinSpec(ex, 30, 374 - h, 0))


# ------------------------------------------------------------------------------------------------ overnight (R13)
def _ticks(v_lots):
    return 1.0 if v_lots >= 20 else (2.0 if v_lots > 0 else 4.0)


def night_trade(und, d, side: str):
    """1-ITM bought at the 15:20 open on d (up to 15:22), the same contract sold at the next session's 09:16 open (up to
    09:21). None when either end has no trade, d is an expiry day, or the contract is gone the next session."""
    days = r51data.load(und)
    ks = sorted(days)
    j = ks.index(d)
    if j + 1 >= len(ks):
        return None
    D, N = days[d], days[ks[j + 1]]
    if D.expiry or N.atm is None:
        return None
    k = E.strike_for(D, und, side, Q4_ROW, 1)
    a, b = D.contract(side, k), N.contract(side, k)
    if a is None or b is None:
        return None
    er = next((r for r in range(Q4_ROW + 1, Q4_ROW + 4) if np.isfinite(a[0, r]) and a[0, r] > 0), None)
    xr = next((r for r in range(1, 7) if np.isfinite(b[0, r]) and b[0, r] > 0), None)
    if er is None or xr is None:
        return None
    q = float(K.lot(und, d))
    Ep, Xp = float(a[0, er]), float(b[0, xr])
    v1 = np.nansum(a[4, max(er - 5, 0):er]) / q
    v2 = np.nansum(b[4, max(xr - 5, 0):xr]) / q
    rE = round(Ep * 1.0005, 2) + _ticks(v1) * K.TICK
    rX = max(round(Xp * 0.9995, 2) - _ticks(v2) * K.TICK, 0.0)
    net = (rX - rE) * q - float(E.charges(und, rE, rX, q, d)[0])
    return dict(und=und, day=d, side=side, strike=k, er=er, xr=xr, E=Ep, X=Xp, rE=rE, rX=rX, qty=q, why="next_0916",
                net=net, gross=(Xp - Ep) * q, nd=ks[j + 1])


def night_days(und, lo, hi):
    """Non-expiry sessions whose next session is also inside [lo, hi] (no final-test minute in a design night)."""
    ds = C.days_in(und, lo, hi)
    return ds[:-1]


def make_r13(top: str = "conf20", always_call: bool = False) -> C.Config:
    def run(lo, hi):
        C.check_range(lo, hi)
        rows = []
        if always_call:
            for und in UNDS:
                for d in night_days(und, lo, hi):
                    t = night_trade(und, d, "C")
                    if t is not None:
                        rows.append(t)
            return C.frame(rows)
        for name, _, blo, bhi in blocks_for(lo, hi):
            P, thr = predictions("q4", "gap", name)
            P = P[(P.day.dt.date >= max(lo, blo)) & (P.day.dt.date <= min(hi, bhi)) & ~P.expiry.values]
            P = P[np.abs(P.p.values - 0.5) >= thr[top]]
            for und, d, p in zip(P.und.values, P.day.dt.date.values, P.p.values):
                if d not in set(night_days(und, lo, hi)):
                    continue
                t = night_trade(und, d, "C" if p > 0.5 else "P")
                if t is not None:
                    rows.append(t)
        return C.frame(rows)

    def twin_fn(T, nt, rng):
        """Random nights (same count per index, random side; the always-call baseline keeps the call) from every
        non-expiry night of the same window."""
        tot = np.zeros(nt)
        lo, hi = min(T.day), max(T.day)
        for und, g in T.groupby("und"):
            pool = {}
            for d in night_days(und, lo, hi):
                for s in (("C",) if always_call else ("C", "P")):
                    t = night_trade(und, d, s)
                    if t is not None:
                        pool.setdefault(d, []).append(t["net"])
            ds = list(pool)
            k = min(len(g), len(ds))
            for i in range(nt):
                for j in rng.choice(len(ds), size=k, replace=False):
                    v = pool[ds[j]]
                    tot[i] += v[rng.integers(len(v))]
        return tot

    return C.Config("r41|ALWAYS_CALL" if always_call else f"r41|R13|{top}", run, None, twin_fn)


def variants() -> list:
    ex = dict(unds=UNDS, design_start=K.WF_FOLDS[0][0])
    src = "index (brain features of the underlying; MLP)"
    out = []
    for i, (h, size) in enumerate([(15, False), (30, False), (60, False), (15, True), (30, True), (60, True)], 1):
        g = [make_dir(h, size)] + [make_dir(h, size, t, s) for t in ("conf", "conf5") for s in ("close30", "stop15")
                                   if (t, s) != ("conf", "close30")]
        desc = f"R{i}: direction {h} min, top 10% confidence" + (", size forecast >= median" if size else "") + \
            ", ATM, exit at t+h or -30% close stop"
        out.append(C.Variant(f"R41-R{i}", "R41 neural nets", src, "orig", g[:1], desc, extra=dict(ex)))
        out.append(C.Variant(f"R41-R{i}-RT", "R41 neural nets", src, "retune", g,
                             "re-tune: confidence {top 10%, top 5%} x stop {-30% close, -15% resting}", extra=dict(ex)))
    g13 = [make_r13("conf20"), make_r13("conf"), make_r13("conf5")]
    out.append(C.Variant("R41-R13", "R41 neural nets", src, "orig", g13[:1],
                         "R13: next-morning gap from 15:19, top 20% confidence, 1-ITM 15:20 -> next 09:16",
                         extra=dict(ex)))
    out.append(C.Variant("R41-R13-RT", "R41 neural nets", src, "retune", g13,
                         "re-tune: confidence {top 20%, 10%, 5%} (overnight rule has no stop to tune)", extra=dict(ex)))
    out.append(C.Variant("R41-ALWAYS-CALL", "R41 neural nets", "the clock (every non-expiry night)", "orig",
                         [make_r13(always_call=True)], "the 1-ITM call bought 15:20, sold next 09:16, every night",
                         extra=dict(unds=UNDS)))
    return out
