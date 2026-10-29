"""R136: R123's N1 retrained walk-forward with and without the 35 ICT inputs (PREREG.md), then compared.

    python research/hunt/r136_ict_network/r136.py features   # ICT inputs for R123's design rows -> $R136_OUT
    python research/hunt/r136_ict_network/r136.py train      # both networks, folds F1-F4 -> $R136_OUT/preds_*.parquet
    python research/hunt/r136_ict_network/r136.py compare    # T1-T3 -> results/compare.json
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import timedelta
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r134_forward"))
import ict_features as X  # noqa: E402
import nnlive  # noqa: E402  (puts R123 on the path)

K3, D3, M, RR = nnlive.K, nnlive.D, nnlive.M, nnlive.RR
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R136_OUT", K3.CACHE_ROOT / "r136")).expanduser()
PREDS = nnlive.PREDS
R133_TRADES = K3.CACHE_ROOT / "r133" / "trades_design.parquet"
ARCHS = {"base": list(D3.TAB_COLS), "ict": list(D3.TAB_COLS) + list(X.ICT_COLS)}
BOOT = 2000
SEED = 136


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def cmd_features():
    OUT.mkdir(parents=True, exist_ok=True)
    keep = lambda d: K3.DESIGN[0] <= d <= K3.DESIGN[1]  # noqa: E731
    parts = []
    for u in K3.UNDS:
        parts.append(X.features(u, K3.DESIGN[1], days_keep=keep))
        log("features", u, len(parts[-1]))
    pd.concat(parts, ignore_index=True).to_parquet(OUT / "ict_features.parquet")


def design_frame() -> tuple[pd.DataFrame, dict]:
    F = pd.read_parquet(PREDS / "rows_design.parquet")
    S = dict(np.load(PREDS / "seq_holdout.npz"))        # the design minutes are its prefix (R123 run.rekey)
    I = pd.read_parquet(OUT / "ict_features.parquet")
    F = F.merge(I, on=["und", "day", "row"], how="left")
    F["fold"] = [K3.fold_of(d) for d in F.day]
    return F, S


def cmd_train(seeds=K3.SEEDS, epochs=K3.EPOCHS, folds=None):
    F, S = design_frame()
    out = {a: [] for a in ARCHS}
    for f, (a, _) in K3.FOLDS.items():
        if folds and f not in folds:
            continue
        train = F[F.day < a - timedelta(days=K3.FOLD_EMBARGO_DAYS)]
        test = F[F.fold == f]
        mn = RR.neither_means(train)
        for arch, cols in ARCHS.items():
            log(f"{f} {arch}: train {len(train)}, test {len(test)}, inputs {len(cols)}")
            net = M.NetModel(seeds=seeds, epochs=epochs, log=lambda *x, **k: None, arch="N1").fit(
                train, M.SeqStore(S), cols)
            pn = net.predict(test, M.SeqStore(S))
            P = RR.attach(test, pn, mn)
            keep = ["und", "day", "row", "fold", "y_dir_30"] + [f"cls_{j}" for j in range(16)]
            out[arch].append(P[keep + [f"pw_{j}" for j in range(16)] + [f"pl_{j}" for j in range(16)] +
                               [f"pn_{j}" for j in range(16)] + [f"ev_{j}" for j in range(16)] + ["pdir30"]])
            log(f"{f} {arch}: done")
    for arch in ARCHS:
        if out[arch]:
            pd.concat(out[arch], ignore_index=True).to_parquet(OUT / f"preds_{arch}.parquet")


# ------------------------------------------------------------------------------------------------ comparison
def _ll_dir(P: pd.DataFrame) -> np.ndarray:
    y = P.y_dir_30.values.astype(float)
    p = np.clip(P.pdir30.values.astype(float), 1e-6, 1 - 1e-6)
    return np.where(np.isfinite(y), -(y * np.log(p) + (1 - y) * np.log(1 - p)), np.nan)


def _ll_cells(P: pd.DataFrame) -> np.ndarray:
    out = np.zeros(len(P))
    cnt = np.zeros(len(P))
    for j in range(16):
        c = P[f"cls_{j}"].values
        pr = np.stack([P[f"pw_{j}"].values, P[f"pl_{j}"].values, P[f"pn_{j}"].values], 1)
        ok = c >= 0
        ll = -np.log(np.clip(pr[np.arange(len(P)), np.where(ok, c, 0).astype(int)], 1e-6, 1))
        out += np.where(ok, ll, 0)
        cnt += ok
    return np.where(cnt > 0, out / np.maximum(cnt, 1), np.nan)


def paired_boot(diff: np.ndarray, sess: np.ndarray, rng, n: int = BOOT) -> dict:
    """Mean of per-row differences (ict - base; negative = ict better) and a session-block bootstrap p (two-sided)."""
    ok = np.isfinite(diff)
    d, s = diff[ok], sess[ok]
    keys, inv = np.unique(s, return_inverse=True)
    sums = np.bincount(inv, weights=d)
    cnts = np.bincount(inv)
    obs = d.mean()
    bs = []
    for _ in range(n):
        ix = rng.integers(0, len(keys), len(keys))
        bs.append(sums[ix].sum() / cnts[ix].sum())
    bs = np.array(bs)
    p = float(2 * min((bs >= 0).mean(), (bs <= 0).mean()))
    return dict(n=int(ok.sum()), sessions=int(len(keys)), mean_diff=float(obs), p=min(p, 1.0))


def filter_compare(T: pd.DataFrame, Pb: pd.DataFrame, Pi: pd.DataFrame, rng, n: int = BOOT) -> dict:
    """The N-EV filter (R132) with each network on the same trades; sign-flip test of the paired difference."""
    import r132nn as NN
    T = T.copy()
    T["day"] = pd.to_datetime(T.day).dt.date
    views = {}
    for name, P in (("base", Pb), ("ict", Pi)):
        V = P[["und", "day", "row", "pdir30"] + [f"ev_{j}" for j in range(16)]]
        M_ = NN.attach(T, V)
        views[name] = M_.set_index(["und", "day", "entry", "side"]).ev_side
    J = T.set_index(["und", "day", "entry", "side"]).join(views["base"].rename("ev_b")).join(views["ict"].rename("ev_i"))
    J = J.dropna(subset=["ev_b", "ev_i"])
    kb, ki = (J.ev_b > 0).values, (J.ev_i > 0).values
    net = J.net.values
    diff = net * (ki.astype(float) - kb.astype(float))
    obs = diff.sum()
    flips = np.array([(diff * rng.choice([-1, 1], len(diff))).sum() for _ in range(n)])
    p = float((1 + (np.abs(flips) >= abs(obs)).sum()) / (1 + n))

    def m(k):
        x = net[k]
        return dict(n=int(k.sum()), total=float(x.sum()), avg=float(x.mean()) if k.any() else 0.0,
                    win=float((x > 0).mean()) if k.any() else 0.0)
    return dict(trades=int(len(J)), base=m(kb), ict=m(ki), diff_total=float(obs), p=p)


def cmd_compare():
    sys.path.insert(0, str(HERE.parent / "r132_ict_exits"))
    rng = np.random.default_rng(SEED)
    Pb = pd.read_parquet(OUT / "preds_base.parquet").sort_values(["und", "day", "row"]).reset_index(drop=True)
    Pi = pd.read_parquet(OUT / "preds_ict.parquet").sort_values(["und", "day", "row"]).reset_index(drop=True)
    assert (Pb[["und", "day", "row"]].values == Pi[["und", "day", "row"]].values).all()
    sess = Pb.day.astype(str).values
    res = dict(rows=len(Pb), tests={}, reported={})
    db, di = _ll_dir(Pb), _ll_dir(Pi)
    res["tests"]["T1-direction"] = dict(paired_boot(di - db, sess, rng), base=float(np.nanmean(db)),
                                        ict=float(np.nanmean(di)))
    cb, ci = _ll_cells(Pb), _ll_cells(Pi)
    res["tests"]["T2-cells"] = dict(paired_boot(ci - cb, sess, rng), base=float(np.nanmean(cb)),
                                    ict=float(np.nanmean(ci)))
    T = pd.read_parquet(R133_TRADES)
    T = T[[K3.fold_of(pd.Timestamp(d).date()) is not None for d in T.day]]
    res["tests"]["T3-filter-S3-V2-E0"] = filter_compare(T[T.v == "S3-V2-E0"], Pb, Pi, rng)
    for v in ("S3-V2-E3", "S7-V2-E4", "S3-V1-E3", "S9-W-E4", "S3-W-E1"):
        res["reported"][f"filter-{v}"] = filter_compare(T[T.v == v], Pb, Pi, rng, n=500)
    E0 = T[T.exit_rule == "E0"]
    res["reported"]["filter-all-39-E0"] = filter_compare(E0, Pb, Pi, rng, n=500)
    for f in K3.FOLDS:
        mk = Pb.fold.values == f
        res["reported"][f"dir-logloss-{f}"] = dict(base=float(np.nanmean(db[mk])), ict=float(np.nanmean(di[mk])))
    qs = RR.bh([t["p"] for t in res["tests"].values()])
    for t, q in zip(res["tests"].values(), qs):
        t["q"] = q
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "compare.json").write_text(json.dumps(res, indent=1, default=str))
    print(json.dumps(res, indent=1, default=str))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "compare"
    {"features": cmd_features, "train": cmd_train, "compare": cmd_compare}[cmd]()
