"""R51 family: B2 "every indicator at once" trade rules (research/hunt/b2/study.py trade_rule). Signal source: the
underlying's brain features (index indicators, with option-chain features as inputs), one gradient-boosted model.

Model: B2's GBM settings (HistGradientBoosting, 300 iterations, rate 0.05, 31 leaves, min leaf 200, l2 0.1, early
stopping) on the brain's features at F1's decision rows (every 5 minutes 09:30-14:30) plus a one-hot index and the time
of day; NIFTY, BANKNIFTY, FINNIFTY, SENSEX pooled. Deviation (PREREG): B2's 261 extra sweep inputs are not rebuilt.
Targets: G15 / G30 / G60 = the index is up 15 / 30 / 60 minutes later; OPT = an ATM call (put) bought at t+1 with x1
made money after costs. Thresholds: the 90th / 10th percentile of the model's training predictions (a 60,000-row
sample, B2). Rules: G-targets buy the call at the first row a day with p >= hi and the put at the first with p <= lo;
OPT buys the call when p_call >= its hi, the put when p_put >= its hi (both -> the larger excess). ATM, x1 (x2 = no time
stop). Walk-forward models per design fold (trained from 21 Nov 2024, 5-session gap): design trades exist only from
Apr 2025; the final-test model is trained on all design rows.
"""
from __future__ import annotations

from functools import lru_cache

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier

import common as C
import engine as E
import ml
import r51cfg as K
import r51data

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")
ROWS = tuple(range(15, 316, 5))
SEED = 20261014
EXITS = {"x1": ml.X1, "x2": E.Exits(stop=0.15, lock_trig=1000, lock_keep=500, lock_trail=500)}
TARGETS = {"G15": "y_ret15", "G30": "y_ret30", "G60": "y_ret60"}


@lru_cache(maxsize=2)
def data(final: bool) -> pd.DataFrame:
    X = pd.concat([ml.dataset(u, ROWS, "b2", opt_labels=True) for u in UNDS], ignore_index=True)
    return X.sort_values(["day", "row", "und"]).reset_index(drop=True)


def _fit(Xm, y):
    m = HistGradientBoostingClassifier(max_iter=300, learning_rate=0.05, max_leaf_nodes=31, min_samples_leaf=200,
                                       l2_regularization=0.1, early_stopping=True, validation_fraction=0.1,
                                       random_state=SEED)
    m.fit(Xm, y)
    return m


@lru_cache(maxsize=64)
def predictions(target: str, block: str):
    """(block rows frame with p, train predictions sample) for one target and block."""
    ml.guard(block)
    X = data(C.final_open())
    cols = ml.feature_cols(X)
    name, tr_end, lo, hi = next(b for b in ml.blocks() if b[0] == block)
    tm = ml.train_mask(X, tr_end)
    bm = ml.block_mask(X, lo, hi)
    out = X.loc[bm, ["day", "row", "und"]].copy()
    if target == "OPT":
        labels = {"C": X.y_net_C, "P": X.y_net_P}
    else:
        labels = {"G": X[TARGETS[target]]}
    trp = {}
    for k, yv in labels.items():
        ok = tm & np.isfinite(yv.values)
        if ok.sum() < 2000:
            out[f"p{k}"] = np.nan
            trp[k] = np.array([])
            continue
        Xtr = ml.matrix(X[ok], cols, UNDS)
        fin = np.isfinite(Xtr)
        use = fin.any(0) & (np.nanmax(np.where(fin, Xtr, -np.inf), 0) > np.nanmin(np.where(fin, Xtr, np.inf), 0))
        Xtr = Xtr[:, use]                    # columns all-NaN or constant on the training rows carry nothing
        m = _fit(Xtr, (yv.values[ok] > 0).astype(int))
        rng = np.random.default_rng(SEED)
        samp = Xtr[rng.choice(len(Xtr), size=min(60000, len(Xtr)), replace=False)]
        trp[k] = m.predict_proba(samp)[:, 1]
        out[f"p{k}"] = m.predict_proba(ml.matrix(X[bm], cols, UNDS)[:, use])[:, 1] if bm.any() else []
    return out, trp


def block_of(lo, hi):
    return [b for b in ml.blocks() if b[2] <= hi and b[3] >= lo and (b[0] != "final" or lo >= K.FINAL_START)]


def make(target: str, pct: float = 0.90, ex_key: str = "x1") -> C.Config:
    ex = EXITS[ex_key]

    def run(lo, hi):
        C.check_range(lo, hi)
        rows = []
        for name, _, blo, bhi in block_of(lo, hi):
            P, trp = predictions(target, name)
            if target == "OPT":
                thr = {k: np.quantile(v, pct) if len(v) else np.inf for k, v in trp.items()}
            else:
                v = trp["G"]
                thr = dict(hi=np.quantile(v, pct) if len(v) else np.inf, lo=np.quantile(v, 1 - pct) if len(v) else -np.inf)
            P = P[(P.day.dt.date >= max(lo, blo)) & (P.day.dt.date <= min(hi, bhi))]
            for (und, d), g in P.groupby(["und", "day"], sort=True):
                d = d.date()
                D = r51data.load(und)[d]
                g = g.sort_values("row")
                if target == "OPT":
                    ec, ep = g.pC.values - thr["C"], g.pP.values - thr["P"]
                    side = np.where((ec >= 0) & ((ep < 0) | (ec >= ep)), "C", np.where(ep >= 0, "P", ""))
                else:
                    side = np.where(g.pG.values >= thr["hi"], "C", np.where(g.pG.values <= thr["lo"], "P", ""))
                for s in ("C", "P"):
                    for t in g.row.values[side == s]:          # the first filled row a side a day
                        k = E.strike_for(D, und, s, int(t), 0)
                        r = E.run_day(und, D, [s], [k], [int(t) + 1], ex)[0] if k is not None else None
                        if r is not None:
                            rows.append(r)
                            break
        return C.frame(rows)

    return C.Config(f"b2|{target}|p{pct}|{ex_key}", run, lambda und: C.TwinSpec(ex, 16, 316, 0))


def variants() -> list:
    out = []
    for t in ("G15", "G30", "G60", "OPT"):
        g = [make(t)] + [make(t, p, x) for p in (0.90, 0.95) for x in ("x1", "x2") if (p, x) != (0.90, "x1")]
        ex = dict(unds=UNDS, design_start=K.WF_FOLDS[0][0])     # walk-forward trades start with the first fold
        out.append(C.Variant(f"B2-{t}", "B2 all-at-once", "underlying features (one GBM)", "orig", g[:1],
                             f"B2 {t}: most confident 10% of a GBM, ATM, x1", extra=dict(ex)))
        out.append(C.Variant(f"B2-{t}-RT", "B2 all-at-once", "underlying features (one GBM)", "retune", g,
                             "re-tune: threshold {90th, 95th pct} x exit {x1, x2}", extra=dict(ex)))
    return out
