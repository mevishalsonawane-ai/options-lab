"""R132 amendment A: R123's walk-forward neural network (N1) as a filter on R131's ten as-stated entries, with R131's
own exits (the same priced trades, nothing re-simulated).

    python research/hunt/r132_ict_exits/r132nn.py design         # folds F1-F4 -> results/nn_design.json
    python research/hunt/r132_ict_exits/r132nn.py holdout_seen   # the seen holdout period -> results/nn_holdout_seen.json

Inputs: R131's trade lists ($R131_OUT/trades_design.parquet, trades_holdout.parquet) and R123's predictions
($R123_PREDS, default <cache>/r123: wf_rows_preds_N1.parquet, holdout_rows_preds_N1.parquet, from
gdrive:IraAlgo-data-lake/derived/r123/cleaned/).
"""
from __future__ import annotations

import json
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r132 as Q  # noqa: E402

R, K = Q.R, Q.K
PREDS = Path(os.environ.get("R123_PREDS", K.K3.CACHE_ROOT / "r123")).expanduser()
FIRST_ROW, STEP, LAST_ROW = K.row(9, 19), 5, K.row(14, 54)
MAX_AGE = 5
PERMS = 2000
FILTERS = {"NEV": "network's mean EV of the side's 8 cells > 0", "NDIR": "network's P(up in 30 min) agrees with the side"}
NN_VARIANTS = {f"{e}-{f}": (e, f) for e in Q.ENTRIES for f in FILTERS}


def view_row(r0: int):
    """The latest decision row t <= r0 - 1 (its view acts from t + 1), if it is at most MAX_AGE minutes old."""
    if r0 - 1 < FIRST_ROW:
        return None
    t = min(FIRST_ROW + STEP * ((r0 - 1 - FIRST_ROW) // STEP), LAST_ROW)
    return t if r0 - (t + 1) <= MAX_AGE else None


def attach(T: pd.DataFrame, P: pd.DataFrame) -> pd.DataFrame:
    """Each trade with its network view: ev_side (mean ev of the side's 8 cells) and pdir30. No view: dropped."""
    T = T.copy()
    T["row"] = [view_row(int(r)) for r in T.entry]
    T = T[T.row.notna()].copy()
    T["row"] = T.row.astype(int)
    ev_c = P[[f"ev_{2 * c}" for c in range(8)]].mean(axis=1)
    ev_p = P[[f"ev_{2 * c + 1}" for c in range(8)]].mean(axis=1)
    V = pd.DataFrame(dict(und=P.und, day=P.day, row=P.row.astype(int), ev_c=ev_c, ev_p=ev_p, pdir30=P.pdir30))
    M = T.merge(V, on=["und", "day", "row"], how="inner")
    M["ev_side"] = np.where(M.side > 0, M.ev_c, M.ev_p)
    return M


def keep_mask(M: pd.DataFrame, f: str) -> np.ndarray:
    if f == "NEV":
        return (M.ev_side > 0).values
    return np.where(M.side > 0, M.pdir30 > 0.5, M.pdir30 < 0.5)


def subset_p(net: np.ndarray, keep: np.ndarray, strata: np.ndarray, rng, perms: int = PERMS) -> float:
    """Share of random subsets with the kept count in each stratum (fold) whose total >= the kept total."""
    obs = float(net[keep].sum())
    idx = {s: np.flatnonzero(strata == s) for s in np.unique(strata)}
    k = {s: int(keep[ix].sum()) for s, ix in idx.items()}
    ge = 0
    for _ in range(perms):
        tot = 0.0
        for s, ix in idx.items():
            if k[s]:
                tot += net[rng.choice(ix, k[s], replace=False)].sum()
        ge += tot >= obs
    return float((1 + ge) / (1 + perms))


def run(part: str):
    rng = np.random.default_rng(Q.SEED + 7)
    if part == "design":
        T = pd.read_parquet(R.OUT / "trades_design.parquet")
        P = pd.read_parquet(PREDS / "wf_rows_preds_N1.parquet")
    else:
        T = pd.read_parquet(R.OUT / "trades_holdout.parquet")
        P = pd.read_parquet(PREDS / "holdout_rows_preds_N1.parquet")
    T["day"] = pd.to_datetime(T.day).dt.date
    T = T[T.variant.isin(Q.ENTRIES)]
    if part == "design":
        T = T[[K.fold_of(d) is not None for d in T.day]]
    M = attach(T, P)
    out = dict(part=part, label=("folds F1-F4 (2023 - 15 Mar 2026), walk-forward network views" if part == "design"
                                 else "the R131 holdout period, ALREADY SEEN for these entries (context only)"),
               variants={}, base={})
    for e in Q.ENTRIES:
        X = M[M.variant == e].sort_values(["day", "und", "entry"]).reset_index(drop=True)
        strata = np.array([K.fold_of(d) or "H" for d in X.day])
        out["base"][e] = R.money(X) if len(X) else dict(n=0)
        for f in FILTERS:
            v = f"{e}-{f}"
            kp = keep_mask(X, f) if len(X) else np.zeros(0, bool)
            S = X[kp]
            s = R.money(S) if len(S) else dict(n=0)
            s["p_subsets"] = subset_p(X.net.values, kp, strata, rng) if kp.any() else 1.0
            s["kept_share"] = float(kp.mean()) if len(kp) else 0.0
            if part == "design" and len(S):
                fo = [K.fold_of(d) for d in S.day]
                s["by_fold"] = {k: R.money(S[[x == k for x in fo]]) for k in K.FOLDS}
                for x in s["by_fold"].values():
                    for drop in ("exits", "worst_month_name", "months_green", "months", "buys"):
                        x.pop(drop, None)
            s["label"] = f"{R.VARIANTS[e]['label']} | filter: {FILTERS[f]}"
            out["variants"][v] = s
    Q.RESULTS.mkdir(parents=True, exist_ok=True)
    (Q.RESULTS / f"nn_{part}.json").write_text(json.dumps(out, indent=1, default=str))
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s["p_subsets"], 3))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
