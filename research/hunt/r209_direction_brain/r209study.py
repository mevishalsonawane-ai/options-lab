"""R209 study (PREREG.md): walk-forward of three direction models, the holdout read once, and the tests on the arms.

    python3 -I research/hunt/r209_direction_brain/r209study.py wf        # -> results/wf.json (+ predictions in ~/.cache)
    python3 -I research/hunt/r209_direction_brain/r209study.py holdout   # read once -> results/holdout.json
    python3 -I research/hunt/r209_direction_brain/r209study.py signals   # R206's arm signals in the holdout (index-only)
    python3 -I research/hunt/r209_direction_brain/r209study.py arms      # T2, T3 -> results/arms.json
    python3 -I research/hunt/r209_direction_brain/r209study.py decide    # the gates -> results/decision.json

Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import json
import pickle
import sys
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r209cfg as K  # noqa: E402

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import r209lib as L  # noqa: E402

FEATS = L.GROUPS["ALL"]


# ================================================================================================ models
class Prep:
    """M-LOG / M-NET inputs (Amendment A): clip to the training rows' 0.5% / 99.5% quantiles, standardise, missing -> 0
    plus a flag for every input that is ever missing in training."""

    def fit(self, X: np.ndarray):
        self.lo = np.nanquantile(X, 0.005, axis=0)
        self.hi = np.nanquantile(X, 0.995, axis=0)
        Z = np.clip(X, self.lo, self.hi)
        self.mu = np.nanmean(Z, axis=0)
        sd = np.nanstd(Z, axis=0)
        self.sd = np.where(sd > 0, sd, 1.0)
        self.flags = np.flatnonzero(np.isnan(X).any(axis=0))
        return self

    def __call__(self, X: np.ndarray) -> np.ndarray:
        Z = (np.clip(X, self.lo, self.hi) - self.mu) / self.sd
        F = np.isnan(Z[:, self.flags]).astype(float)
        return np.hstack([np.nan_to_num(Z, nan=0.0), F])


def fit_predict(model: str, Xtr, ytr, Xte) -> np.ndarray:
    if model == "GBM":
        from sklearn.ensemble import HistGradientBoostingClassifier
        m = HistGradientBoostingClassifier(**K.GBM).fit(Xtr, ytr)
        return m.predict_proba(Xte)[:, 1]
    prep = Prep().fit(Xtr)
    if model == "LOG":
        from sklearn.linear_model import LogisticRegression
        m = LogisticRegression(C=K.LOG_C, max_iter=2000).fit(prep(Xtr), ytr)
    else:
        from sklearn.neural_network import MLPClassifier
        m = MLPClassifier(**K.NET).fit(prep(Xtr), ytr)
    return m.predict_proba(prep(Xte))[:, 1]


def table() -> pd.DataFrame:
    df = pd.read_parquet(K.OUT / "table.parquet")
    df["day"] = pd.to_datetime(df.day).dt.date
    return df[df.up15.notna() & (df.day >= K.TRAIN_FROM)].reset_index(drop=True)


def metrics(y, p, rng=None, boot: int = 0, day_id=None) -> dict:
    top, k = L.top_accuracy(y, p)
    out = dict(n=int(len(y)), auc=L.auc(y, p), acc=L.accuracy(y, p), top20=top, top20_n=k, up_share=float(np.mean(y)))
    if boot:
        out["boot"] = L.boot_auc(y, p, day_id, boot, rng)
    return out


# ================================================================================================ walk-forward
def wf() -> dict:
    df = table()
    df = df[df.day < K.HOLDOUT[0]].reset_index(drop=True)                 # nothing from the holdout is read here
    y = df.up15.to_numpy()
    runs = [(m, "ALL") for m in K.MODELS] + [("GBM", g) for g in ("P", "PX", "PXV")]
    preds = {f"{m}:{g}": np.full(len(df), np.nan) for m, g in runs}
    per_q = {f"{m}:{g}": [] for m, g in runs}
    for q in K.QUARTERS:
        tr, te = L.wf_split(df.day, q)
        for m, g in runs:
            cols = L.GROUPS[g]
            p = fit_predict(m, df.loc[tr, cols].to_numpy(float), y[tr], df.loc[te, cols].to_numpy(float))
            preds[f"{m}:{g}"][te] = p
            per_q[f"{m}:{g}"].append(dict(quarter=str(q), **metrics(y[te], p)))
            print(q, m, g, {k: round(v, 4) for k, v in per_q[f"{m}:{g}"][-1].items() if isinstance(v, float)}, flush=True)
    ok = np.isfinite(preds["GBM:ALL"])
    days = sorted(df.day[ok].unique())
    did = df.day[ok].map({d: i for i, d in enumerate(days)}).to_numpy()
    rng = np.random.default_rng(K.SEED)
    pooled = {k: metrics(y[ok], v[ok], rng, 500, did) for k, v in preds.items()}
    chosen = max(K.MODELS, key=lambda m: pooled[f"{m}:ALL"]["auc"])
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), rows=int(ok.sum()), quarters=[str(q) for q in K.QUARTERS]),
           "per_quarter": per_q, "pooled": pooled, "chosen": chosen,
           "by_index": {u: {m: metrics(y[ok & (df.und == u).to_numpy()], preds[f"{m}:ALL"][ok & (df.und == u).to_numpy()])
                            for m in K.MODELS} for u in K.UNDS},
           "calibration": calib(y[ok], preds[f"{chosen}:ALL"][ok])}
    p = df.loc[ok, ["und", "day", "e", "up15", "m15"]].copy()
    for k, v in preds.items():
        p[k] = v[ok]
    K.OUT.mkdir(parents=True, exist_ok=True)
    p.to_parquet(K.OUT / "wf_preds.parquet", index=False)
    out["t3"] = choose_t3(p[["und", "day", "e", f"{chosen}:ALL"]].rename(columns={f"{chosen}:ALL": "p"}))
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "wf.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def calib(y, p) -> list:
    """The share up in ten bins of the predicted P(up)."""
    edges = np.quantile(p, np.linspace(0, 1, 11))
    out = []
    for a, b in zip(edges[:-1], edges[1:]):
        m = (p >= a) & (p <= b)
        out.append(dict(p_from=round(float(a), 4), p_to=round(float(b), 4), n=int(m.sum()), mean_p=round(float(p[m].mean()), 4),
                        up=round(float(y[m].mean()), 4)))
    return out


# ================================================================================================ T3 helpers (R202's trades)
def r202():
    sys.path.insert(0, str(K.HUNT / "r202_trend_exit"))
    import r202cfg as K2
    import r202lib as G
    return K2, G


def arm_trades() -> dict:
    K2, _ = r202()
    tr = pickle.loads((K2.OUT / "r202_trades_final.pkl").read_bytes())
    return {a: tr[a + ":B"] for a in ("R72", "LIQ")}


def p_side(trades: list, pred: pd.DataFrame) -> np.ndarray:
    """P(the trade's side) from the predictions at (index, day, signal minute); NaN where none."""
    m = {(u, d, int(e)): float(v) for u, d, e, v in pred[["und", "day", "e", "p"]].itertuples(index=False)}
    out = np.full(len(trades), np.nan)
    for i, t in enumerate(trades):
        v = m.get((t["und"], t["day"], int(t["er_sig"])))
        if v is not None and v == v:
            out[i] = v if t["side"] == "C" else 1.0 - v
    return out


def choose_t3(pred: pd.DataFrame) -> dict:
    K2, _ = r202()
    out = {}
    for arm, trades in arm_trades().items():
        des = [t for t in trades if K2.DESIGN[0] <= t["day"] <= K2.DESIGN[1]]
        ps = p_side(des, pred)
        nets = {t: float(sum(x["net"] for x, v in zip(des, ps) if not (v == v and v < t))) for t in K.T3_GRID}
        out[arm] = dict(nets={str(k): round(v) for k, v in nets.items()}, base=round(sum(x["net"] for x in des)),
                        scored=int(np.isfinite(ps).sum()), trades=len(des), t=L.choose_t(nets))
    return out


# ================================================================================================ holdout (read once)
def holdout() -> dict:
    if not (K.RESULTS / "wf.json").exists():
        raise SystemExit("walk-forward first (PREREG 6)")
    if K.HOLDOUT_FLAG.exists() and (K.RESULTS / "holdout.json").exists():
        raise SystemExit("R209 holdout already read once: " + K.HOLDOUT_FLAG.read_text())
    if not K.HOLDOUT_FLAG.exists():
        K.HOLDOUT_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
    w = json.loads((K.RESULTS / "wf.json").read_text())
    df = table()
    tr = (df.day < K.HOLDOUT[0]).to_numpy()
    te = ((df.day >= K.HOLDOUT[0]) & (df.day <= K.HOLDOUT[1])).to_numpy()
    y = df.up15.to_numpy()
    days = sorted(df.day[te].unique())
    did = df.day[te].map({d: i for i, d in enumerate(days)}).to_numpy()
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), rows=int(te.sum()), days=len(days), chosen=w["chosen"]),
           "models": {}}
    pr = df.loc[te, ["und", "day", "e", "up15", "m15"]].copy()
    for i, m in enumerate(K.MODELS):
        p = fit_predict(m, df.loc[tr, FEATS].to_numpy(float), y[tr], df.loc[te, FEATS].to_numpy(float))
        pr[m] = p
        out["models"][m] = metrics(y[te], p, np.random.default_rng([K.SEED, i]), K.BOOT, did)
        out["models"][m]["by_index"] = {u: metrics(y[te][(df.und[te] == u).to_numpy()], p[(df.und[te] == u).to_numpy()]) for u in K.UNDS}
        out["models"][m]["by_month"] = {str(mm): metrics(y[te][(pd.to_datetime(df.day[te]).dt.month == mm).to_numpy()],
                                                         p[(pd.to_datetime(df.day[te]).dt.month == mm).to_numpy()])
                                        for mm in range(4, 11)}
        print(m, {k: round(v, 4) for k, v in out["models"][m].items() if isinstance(v, float)}, out["models"][m]["boot"], flush=True)
    out["calibration"] = calib(y[te], pr[w["chosen"]].to_numpy())
    pr.to_parquet(K.OUT / "holdout_preds.parquet", index=False)
    (K.RESULTS / "holdout.json").write_text(json.dumps(out, indent=1, default=str))
    return out


# ================================================================================================ the arms (T2, T3)
def signals():
    """R206's arm signals (index-only, its own code) in the holdout, with their 15-minute move -> ~/.cache/r209."""
    sys.path.insert(0, str(K.HUNT / "r206_direction"))
    import r206study as S
    rows = S.label(S.in_span(S.all_signals(), *K.HOLDOUT))
    keep = [dict(src=s["src"], und=s["und"], day=s["day"], er=int(s["er"]), side=s["side"], m15=s["m15"]) for s in rows]
    (K.OUT / "holdout_signals.pkl").write_bytes(pickle.dumps(keep))
    print(len(keep), "signals", flush=True)


def arms() -> dict:
    sys.path.insert(0, str(K.HUNT / "r206_direction"))
    import r206lib as L6
    K2, G = r202()
    w = json.loads((K.RESULTS / "wf.json").read_text())
    ch = w["chosen"]
    hp = pd.read_parquet(K.OUT / "holdout_preds.parquet")
    hp["day"] = pd.to_datetime(hp.day).dt.date
    pm = {(u, d, int(e)): float(v) for u, d, e, v in hp[["und", "day", "e", ch]].itertuples(index=False)}
    sig = pickle.loads((K.OUT / "holdout_signals.pkl").read_bytes())
    out = {"chosen": ch, "t2": {}, "t3": {}}
    for i, src in enumerate(("R72", "R51", "LIQ")):
        rs = [s for s in sig if s["src"] == src and s["m15"] == s["m15"] and (s["und"], s["day"], s["er"]) in pm]
        ps = np.array([pm[(s["und"], s["day"], s["er"])] for s in rs])
        ps = np.where(np.array([s["side"] for s in rs]) == "C", ps, 1.0 - ps)
        right = np.array([s["m15"] > 0 for s in rs], bool)
        fav = ps >= 0.5
        days = sorted({s["day"] for s in rs})
        did = np.array([{d: k for k, d in enumerate(days)}[s["day"]] for s in rs])
        r = L6.boot_diff(L6.day_table(right, fav, did, len(days)), 5000, np.random.default_rng([K.SEED, 10 + i]))
        r.update(n_a=int(fav.sum()), n_b=int((~fav).sum()), rate_a=float(right[fav].mean()) if fav.any() else None,
                 rate_b=float(right[~fav].mean()) if (~fav).any() else None, rate_src=float(right.mean()) if len(right) else None,
                 signals=len(rs), unscored=sum(1 for s in sig if s["src"] == src) - len(rs))
        out["t2"][src] = r
        print("T2", src, {k: (round(v, 4) if isinstance(v, float) else v) for k, v in r.items()}, flush=True)
    wp = pd.read_parquet(K.OUT / "wf_preds.parquet")
    wp["day"] = pd.to_datetime(wp.day).dt.date
    allp = pd.concat([wp[["und", "day", "e", f"{ch}:ALL"]].rename(columns={f"{ch}:ALL": "p"}),
                      hp[["und", "day", "e", ch]].rename(columns={ch: "p"})], ignore_index=True)
    for i, (arm, trades) in enumerate(arm_trades().items()):
        t = w["t3"][arm]["t"]
        ps = p_side(trades, allp)
        keep = ~(np.isfinite(ps) & (ps < t))
        kept = [x for x, k in zip(trades, keep) if k]
        wins = {"design": K2.DESIGN, "holdout": K2.HOLDOUT, "last_year": K2.LAST_YEAR, "total": K2.SPAN}
        r = dict(t=t, changed={}, effect={}, twin={}, windows={w_: G.stats(G.in_window(kept, *v), *v) for w_, v in wins.items()},
                 base={w_: G.stats(G.in_window(trades, *v), *v) for w_, v in wins.items()},
                 folds=[G.stats(G.in_window(kept, *f), *f) for f in K2.FOLDS], base_folds=[G.stats(G.in_window(trades, *f), *f) for f in K2.FOLDS])
        for j, (w_, (a, b)) in enumerate(wins.items()):
            bw = [(x, k) for x, k in zip(trades, keep) if a <= x["day"] <= b]
            rem = [x for x, k in bw if not k]
            r["changed"][w_] = len(rem)
            r["effect"][w_] = dict(removed_net=round(sum(x["net"] for x in rem)), removed_losers=sum(1 for x in rem if x["net"] <= 0))
            if w_ in ("design", "holdout"):
                tot = G.skip_twins(np.array([x["net"] for x, _ in bw], float), len(rem), 2000, np.random.default_rng([K.SEED, 20 + i, j]))
                act = float(sum(x["net"] for x, k in bw if k))
                r["twin"][w_] = dict(p=round(G.twin_p(act, tot), 5), p_normal=G.normal_p(act, tot), actual=round(act))
        out["t3"][arm] = r
        print("T3", arm, t, {w_: (r["windows"][w_]["trades"], r["windows"][w_]["net"], r["base"][w_]["net"], r["changed"][w_]) for w_ in wins},
              {w_: r["twin"][w_]["p"] for w_ in ("design", "holdout")}, flush=True)
    (K.RESULTS / "arms.json").write_text(json.dumps(out, indent=1, default=str))
    return out


def decide() -> dict:
    _, G = r202()
    w = json.loads((K.RESULTS / "wf.json").read_text())
    h = json.loads((K.RESULTS / "holdout.json").read_text())
    a = json.loads((K.RESULTS / "arms.json").read_text())
    ps = [h["models"][m]["boot"]["p_normal"] for m in K.MODELS]
    ps += [a["t2"][s]["p_normal"] for s in ("R72", "R51", "LIQ")]
    ps += [G.p_for_bh(a["t3"][s]["changed"]["holdout"], a["t3"][s]["twin"]["holdout"]["p_normal"]) for s in ("R72", "LIQ")]
    qs = L.bh_total(ps, K.M_TOTAL)
    qf = L.bh_total(ps, len(ps))
    out = {"m_total": K.M_TOTAL, "chosen": w["chosen"], "tests": {}}
    for i, m in enumerate(K.MODELS):
        tops = [x["top20"] for x in w["per_quarter"][f"{m}:ALL"]]
        g = L.gate_t1(h["models"][m]["boot"], h["models"][m]["top20"], tops, qs[i])
        g.update(q_family=qf[i])
        out["tests"][f"T1-{m}"] = g
    for j, s in enumerate(("R72", "R51", "LIQ")):
        g = L.gate_t2(a["t2"][s], qs[3 + j])
        g.update(q_family=qf[3 + j])
        out["tests"][f"T2-{s}"] = g
    for j, s in enumerate(("R72", "LIQ")):
        r = a["t3"][s]
        g = G.gate(r["windows"], r["base"], r["folds"], r["base_folds"], r["changed"], r["twin"]["holdout"]["p"], qs[6 + j])
        g.update(q_family=qf[6 + j])
        out["tests"][f"T3-{s}"] = g
    out["passes"] = [k for k, g in out["tests"].items() if g["passes"]]
    (K.RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    {"wf": wf, "holdout": holdout, "signals": signals, "arms": arms, "decide": decide}.get(
        (sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
