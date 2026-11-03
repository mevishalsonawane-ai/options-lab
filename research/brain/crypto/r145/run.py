"""R145 build and walk-forward (PREREG_R145_NN.md sections 1-4).

    python -m research.brain.crypto.r145.run build  --manifest <csv>          # design panel -> rows + sequence channels
    python -m research.brain.crypto.r145.run fit    [--folds 0 1 ...] [--models B0 B1 B2 N1 N2 TW]
    python -m research.brain.crypto.r145.run push                             # checkpoints -> Drive derived/r145/

Everything big lives in <cache>/crypto/r145/ (never git); `push` copies it to gdrive:IraAlgo-data-crypto/derived/r145/
so a restarted session can resume.
"""
from __future__ import annotations

import argparse
import json
import os
import pickle
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

from .. import config
from . import data, models as M, panel as pn

DESIGN_ROWS_FROM = pd.Timestamp("2024-01-08")
BARS_FROM, BARS_TO = "2023-12", "2026-03"
MAX_H = pd.Timedelta(minutes=240)
EMBARGO = pd.Timedelta(days=1)
FOLDS = [("2024-10-01", "2025-01-01"), ("2025-01-01", "2025-04-01"), ("2025-04-01", "2025-07-01"),
         ("2025-07-01", "2025-10-01"), ("2025-10-01", "2026-01-01"), ("2026-01-01", "2026-04-01")]
MODELS = ("B0", "B1", "B2", "N1", "N2", "TW")
N2_MAX_ROWS = 800_000
N1_SEEDS = (0, 1, 2)


def out_dir() -> Path:
    return data.root()


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# --- build --------------------------------------------------------------------------------------------------------------
def load_all(symbols, frm, to):
    bars, fund = {}, {}
    for s in symbols:
        b = data.load_bars(s, frm, to)
        if b is not None and len(b):
            bars[s] = b
            f = data.root() / "funding" / f"{s}.parquet"
            if f.exists():
                fund[s] = pd.read_parquet(f)
    return bars, fund


def build(symbols, tag="design", frm=BARS_FROM, to=BARS_TO, rows_from=DESIGN_ROWS_FROM, rows_to=None,
          first_seen=None):
    bars, fund = load_all(symbols, frm, to)
    log(f"{len(bars)} coins with bars")
    lo = pd.Timestamp(frm + "-01")
    hi = pd.Period(to, "M").end_time.floor("5min")
    P = pn.make_panel(bars, lo, hi)
    log("panel", P["close"].shape)
    F = pn.features(P, fund, first_seen=first_seen)
    log("features", F.shape)
    L = pn.labels(P)
    D = F.merge(L, on=["ts", "sym"], how="left")
    end = config.HOLDOUT_START if rows_to is None else pd.Timestamp(rows_to)
    D = D[(D.ts >= rows_from) & (D.ts + MAX_H <= end)].reset_index(drop=True)
    if tag == "design":
        assert D.ts.max() + MAX_H <= config.HOLDOUT_START, "design rows reach the holdout"
    cols = list(P["close"].columns)
    D["col"] = D.sym.map({s: i for i, s in enumerate(cols)}).astype(np.int32)
    o = out_dir() / tag
    o.mkdir(parents=True, exist_ok=True)
    D.to_parquet(o / "rows.parquet", index=False)
    C = pn.seq_channels(P)
    np.save(o / "seq.npy", C)
    pd.Series(P["close"].index).to_frame("grid").to_parquet(o / "grid.parquet")
    json.dump(dict(symbols=cols, rows=len(D), features=pn.feature_names(F),
                   first_seen={s: str(b.ts.min()) for s, b in bars.items()}), open(o / "meta.json", "w"))
    log("rows", D.shape, "seq", C.shape)
    return D


# --- walk-forward -------------------------------------------------------------------------------------------------------
HOLDOUT_FOLD = ("2026-04-01", "2026-10-13")     # Amendment 1: final models on all design rows, scored once here
HOLDOUT_ROWS_TO = "2026-10-12"                   # the lake's end (Oct 2026 was built on 13 Oct from daily files)


def split(D: pd.DataFrame, fold: int, folds=None):
    folds = FOLDS if folds is None else folds
    lo, hi = (pd.Timestamp(x) for x in folds[fold])
    te = np.flatnonzero((D.ts >= lo) & (D.ts < hi))
    tr = np.flatnonzero(D.ts + MAX_H + EMBARGO <= lo)
    ts = D.ts.values[tr]
    cut = np.quantile(ts.astype("int64"), 0.9).astype("datetime64[ns]")
    va = tr[ts >= cut]
    fit = tr[ts < cut]
    return fit, va, tr, te


def run_fold(D, C, feats, fold, models=MODELS, tag="design", folds=None):
    o = out_dir() / tag / "preds"
    o.mkdir(parents=True, exist_ok=True)
    mdir = out_dir() / tag / "models"
    mdir.mkdir(parents=True, exist_ok=True)
    fit, va, tr, te = split(D, fold, folds)
    log(f"fold {fold} {(folds or FOLDS)[fold]}: fit {len(fit):,} val {len(va):,} test {len(te):,}")
    X = D[feats].to_numpy(np.float64)
    prep = M.Prep().fit(X[fit])
    Z = prep(X)
    Y = D[M.TARGETS].to_numpy(np.float64)
    ts_ = M.TargetScale().fit(Y[fit])
    pk = mdir / f"fold{fold}_prep.pkl"
    if not pk.exists():
        pickle.dump(dict(prep=prep, tscale=ts_, feats=feats), open(pk, "wb"))
    keys = D.loc[te, ["ts", "sym"]].reset_index(drop=True)
    seq = M.SeqGather(C, D.bar.to_numpy(), D.col.to_numpy())

    def put(name, pred: dict, info=None):
        P = keys.copy()
        for t in M.TARGETS:
            P[f"{name}|{t}"] = np.asarray(pred[t], np.float32)
        P.to_parquet(o / f"fold{fold}_{name}.parquet", index=False)
        if info is not None:
            json.dump(info, open(o / f"fold{fold}_{name}_info.json", "w"), default=str)

    if "B0" in models:
        put("B0", M.naive(D.iloc[tr], D.iloc[te]))
        log("  B0 done")
    if "B1" in models:
        put("B1", M.linear(Z[tr], D.iloc[tr], Z[te]))
        log("  B1 done")
    if "B2" in models:
        pred, inf = M.trees(X[fit], D.iloc[fit], X[va], D.iloc[va], X[te])
        put("B2", pred, inf)
        log("  B2 done")
    if "N1" in models or "TW" in models:
        import torch
        Ys = ts_.fwd(Y)
        if "N1" in models:
            outs, vals = [], []
            info = {}
            for sd in N1_SEEDS:
                net, ep, hist = M.fit_net(Z[fit], Ys[fit], Z[va], Ys[va], epochs=6, seed=sd, log=log)
                torch.save(net.state_dict(), mdir / f"fold{fold}_N1_s{sd}.pt")
                info[sd] = dict(best_epoch=ep, val=hist)
                outs.append(ts_.inv(M.predict_net(net, Z[te])))
                vals.append(ts_.inv(M.predict_net(net, Z[va])))
            vm = np.mean(vals, axis=0)
            info["val_q"] = {t: [float(np.quantile(vm[:, i], 0.05)), float(np.quantile(vm[:, i], 0.95))]
                                for i, t in enumerate(M.TARGETS)}
            put("N1", {t: np.mean([o_[:, i] for o_ in outs], axis=0) for i, t in enumerate(M.TARGETS)}, info)
            log("  N1 done")
        if "TW" in models:
            rng = np.random.default_rng(99 + fold)
            Ysh = Ys.copy()
            Ysh[fit] = Ys[rng.permutation(fit)]
            Ysh[va] = Ys[rng.permutation(va)]
            net, ep, hist = M.fit_net(Z[fit], Ysh[fit], Z[va], Ysh[va], epochs=6, seed=0, log=log)
            pr = ts_.inv(M.predict_net(net, Z[te]))
            put("TW", {t: pr[:, i] for i, t in enumerate(M.TARGETS)}, dict(best_epoch=ep, val=hist))
            log("  TW done")
    if "N2" in models:
        import torch
        Ys = ts_.fwd(Y)
        rng = np.random.default_rng(7 + fold)
        f2 = np.sort(rng.choice(fit, N2_MAX_ROWS, replace=False)) if len(fit) > N2_MAX_ROWS else fit
        net, ep, hist = M.fit_net(Z[f2], Ys[f2], Z[va], Ys[va], seq=seq, tr_idx=f2, va_idx=va, epochs=4, seed=0,
                                  log=log)
        torch.save(net.state_dict(), mdir / f"fold{fold}_N2.pt")
        pr = ts_.inv(M.predict_net(net, Z[te], seq=seq, idx=te))
        put("N2", {t: pr[:, i] for i, t in enumerate(M.TARGETS)}, dict(best_epoch=ep, val=hist, rows=len(f2)))
        log("  N2 done")


def load_design(tag="design"):
    o = out_dir() / tag
    D = pd.read_parquet(o / "rows.parquet")
    C = np.load(o / "seq.npy", mmap_mode="r")
    meta = json.load(open(o / "meta.json"))
    return D, C, meta


def push(sub=""):
    src = out_dir() / sub
    dst = "gdrive:IraAlgo-data-crypto/derived/r145/" + sub
    r = subprocess.run([data.RCLONE, "copy", str(src), dst, "--exclude", "bars5/**", "--exclude", "btc1m/**",
                        "--transfers", "4"], capture_output=True, text=True)
    log("push", sub or "all", "ok" if r.returncode == 0 else r.stderr[-500:])


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.r145.run")
    ap.add_argument("what", choices=["build", "fit", "push", "holdout-build", "holdout-fit"])
    ap.add_argument("--manifest")
    ap.add_argument("--folds", nargs="*", type=int, default=list(range(len(FOLDS))))
    ap.add_argument("--models", nargs="*", default=list(MODELS))
    a = ap.parse_args(argv)
    if a.what == "build":
        build(data.manifest_symbols(a.manifest))
    elif a.what == "fit":
        D, C, meta = load_design()
        for f in a.folds:
            run_fold(D, C, meta["features"], f, a.models)
            push("design/preds")
            push("design/models")
    elif a.what == "holdout-build":
        # the ONE holdout read (Amendment 1): bars to Oct 2026 must have been fetched with --allow-holdout first
        build(data.manifest_symbols(a.manifest), tag="full", frm=BARS_FROM, to="2026-10",
              rows_to=HOLDOUT_ROWS_TO)
    elif a.what == "holdout-fit":
        D, C, meta = load_design("full")
        run_fold(D, C, meta["features"], 0, a.models, tag="full", folds=[HOLDOUT_FOLD])
        push("full/preds")
        push("full/models")
    else:
        push()
    return 0


if __name__ == "__main__":
    sys.exit(main())
