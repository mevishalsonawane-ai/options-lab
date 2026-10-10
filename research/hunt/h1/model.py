"""h1 ML stage: walk-forward models on the build.py rows, trade the top-confidence slice, stats, holdout.

python3 -I research/hunt/h1/model.py wf        # anchored yearly walk-forward 2022 .. 2025-09 (pre-holdout ONLY)
python3 -I research/hunt/h1/model.py select    # pick ONE variant on the walk-forward, baseline / BH / SPA / PBO
python3 -I research/hunt/h1/model.py holdout   # the single locked-holdout run of the chosen variant (once)

Variants = model (lr, hgb, rf) x label (5 presets) x slice (top 5 / 2 / 1 % of predicted edge) x max trades a day (2, 5).
Slice thresholds come from purged, embargoed 3-fold time-series CV (out-of-fold scores) on each training window only.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
import time
import warnings
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import costs as K  # noqa: E402
from obuy import data as D  # noqa: E402
from obuy import overfit as OV  # noqa: E402

warnings.filterwarnings("ignore")
OUT = os.path.join(C.SCRATCH, "hunt", "h1")
HOLD = D.dnum(date(2025, 10, 1))
EMBARGO = 2                                   # trading days dropped between train and test / CV folds
LOT = {0: 65, 1: 35, 2: 60}                   # today's lots: NIFTY, BANKNIFTY, FINNIFTY (1 lot per trade)
UNAME = {0: "NIFTY", 1: "BANKNIFTY", 2: "FINNIFTY"}
LABELS = {  # name: (T, S, L) premium fractions / minutes; REG = signed 30-min forward return (time exit only)
    "T20S10L30": (0.20, 0.10, 30), "T30S15L60": (0.30, 0.15, 60), "T15S15L30": (0.15, 0.15, 30),
    "T40S20L60": (0.40, 0.20, 60), "REG30": (None, None, 30)}
MODELS = ("lr", "hgb", "rf")
SLICES = (0.05, 0.02, 0.01)
MAXN = (2, 5)
TEST = [("2022", date(2022, 1, 1), date(2022, 12, 31)), ("2023", date(2023, 1, 1), date(2023, 12, 31)),
        ("2024", date(2024, 1, 1), date(2024, 12, 31)), ("2025pre", date(2025, 1, 1), date(2025, 9, 30))]
TRAIN_CAP = 120_000
RF_CAP = 120_000
SPREAD = 1.0                                  # Rs/pt per unit round trip (0.5 a side) in the 'net+spread' column

DIRF = ["r5", "r15", "r30", "r60", "dayret", "gap", "prevret", "prev5", "pos", "orpos", "dvwap", "pcr15", "pcrd",
        "vixd", "vix30", "opm5x", "oidiff"]
NDF = ["s", "dte", "wd", "rv30", "rvr", "rngr", "prevrng", "vix", "strad", "strad15", "stradd", "iv", "iv15", "pcr",
       "money", "prem", "opm5", "opm15", "oich", "lvol5", "room", "back", "side", "und0", "und1", "und2"]


def load():
    R = pd.concat([pd.read_parquet(os.path.join(OUT, f"rows_{u}.parquet")) for u in ("NIFTY", "BANKNIFTY", "FINNIFTY")],
                  ignore_index=True)
    R = R[(R.E >= 5) & (R.vol5 > 0)].reset_index(drop=True)
    sd = R.side.astype(np.float32)
    R["opm5x"] = R.opm5                      # premium momentum of the contract itself (already side-specific)
    R["oidiff"] = R.coi15 - R.poi15          # calls added faster than puts (bearish writers' view), signed below
    R["room"] = np.where(sd > 0, R.dhi, R.dlo)
    R["back"] = np.where(sd > 0, R.dlo, R.dhi)
    R["lvol5"] = np.log1p(R.vol5)
    for u in range(3):
        R[f"und{u}"] = (R.und == u).astype(np.float32)
    X = pd.DataFrame({f: (R[f] * sd if f not in ("opm5x",) else R[f]).astype(np.float32) for f in DIRF})
    for f in NDF:
        X[f] = R[f].astype(np.float32)
    X = X.replace([np.inf, -np.inf], np.nan)
    pnl = exits(R)
    return R, X, pnl


def exits(R):
    """Per row and label: gross pts, net Rs (app fills + charges), net Rs + spread, bars held, target-first flag."""
    out = {}
    E = R.E.values.astype(float)
    qty = R.und.map(LOT).values.astype(float)
    dn = R.day.values
    cost = K.Costs("app")
    for name, (T, S, L) in LABELS.items():
        cL = R[f"c{L}"].values.astype(float)
        nL = R[f"n{L}"].values.astype(float)
        if T is None:
            X = E + cL
            is_stop = np.zeros(len(R), bool)
            held = nL
            y = cL / E
        else:
            tu = R[f"u{int(T * 100)}"].values.astype(float)
            td = R[f"d{int(S * 100)}"].values.astype(float)
            gap = R[f"g{int(S * 100)}"].values.astype(float)
            stop = (td <= tu) & (td <= nL)
            tgt = (~stop) & (tu <= nL)
            X = np.where(stop, E * (1 - S) - gap, np.where(tgt, E * (1 + T), E + cL))
            X = np.maximum(X, 0.05)
            is_stop = stop
            held = np.where(stop, td, np.where(tgt, tu, nL))
            y = tgt.astype(float)
        g = (X - E) * qty
        b = K.adverse_bps(E, 5, True)
        sl = np.where(is_stop, K.adverse_bps(X, 10, False), K.adverse_bps(X, 5, False))
        ch = cost.charge(True, b, qty, dn) + cost.charge(False, sl, qty, dn)
        net = (sl - b) * qty - ch
        out[name] = dict(gross=g.astype(np.float32), net=net.astype(np.float32),
                         nets=(net - SPREAD * qty).astype(np.float32), held=held.astype(np.int16),
                         y=y.astype(np.float32), charges=ch.astype(np.float32), prem=(E * qty).astype(np.float32))
    return out


# ------------------------------------------------------------------------------------------------ models
def make(model, reg):
    from sklearn.ensemble import (HistGradientBoostingClassifier, HistGradientBoostingRegressor,
                                  RandomForestClassifier, RandomForestRegressor)
    from sklearn.impute import SimpleImputer
    from sklearn.linear_model import LogisticRegression, Ridge
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler
    if model == "lr":
        est = Ridge(alpha=10.0) if reg else LogisticRegression(C=0.05, max_iter=400)
        return make_pipeline(SimpleImputer(strategy="median"), StandardScaler(), est)
    if model == "hgb":
        kw = dict(max_iter=120, learning_rate=0.08, max_leaf_nodes=31, min_samples_leaf=300, l2_regularization=1.0,
                  early_stopping=False, random_state=0)
        return HistGradientBoostingRegressor(**kw) if reg else HistGradientBoostingClassifier(**kw)
    kw = dict(n_estimators=100, max_depth=8, min_samples_leaf=200, max_features=0.3, n_jobs=2, random_state=0)
    return RandomForestRegressor(**kw) if reg else RandomForestClassifier(**kw)


class Clipper:
    """Winsorise every feature at the training 0.5 / 99.5 percentiles (keeps NaN)."""

    def fit(self, X):
        self.lo = np.nanpercentile(X, 0.5, axis=0)
        self.hi = np.nanpercentile(X, 99.5, axis=0)
        return self

    def __call__(self, X):
        X = np.clip(X, self.lo, self.hi)
        bad = ~np.isfinite(self.lo)              # a column with no data in training (e.g. minute VIX before Oct 2021)
        if bad.any():
            X = X.copy()
            X[:, bad] = 0.0
        return X


def fit_predict(model, reg, Xtr, ytr, Xte, rng):
    cap = RF_CAP if model == "rf" else TRAIN_CAP
    if len(Xtr) > cap:
        i = rng.choice(len(Xtr), cap, replace=False)
        Xtr, ytr = Xtr[i], ytr[i]
    cl = Clipper().fit(Xtr)
    m = make(model, reg)
    yt = np.clip(ytr, -1.0, 2.0) if reg else ytr.astype(int)
    m.fit(cl(Xtr), yt)
    Xc = cl(Xte)
    p = m.predict(Xc) if reg else m.predict_proba(Xc)[:, 1]
    return m, cl, p


def purged_folds(days, k=3, embargo=EMBARGO, last_only=True):
    """Contiguous day blocks; train on the rest minus `embargo` trading days either side of the test block.
    last_only (used for speed on this shared 4-core box): only the LAST block is scored, trained strictly on the
    earlier days minus the embargo (a purged forward validation split, no look-ahead even inside the CV)."""
    ud = np.unique(days)
    if last_only:
        a = int(len(ud) * 0.75)
        lo = ud[a]
        yield days < ud[max(a - embargo, 0)], days >= lo
        return
    edges = np.linspace(0, len(ud), k + 1).astype(int)
    for a, b in zip(edges[:-1], edges[1:]):
        lo, hi = ud[a], ud[b - 1]
        lo_e = ud[max(a - embargo, 0)]
        hi_e = ud[min(b - 1 + embargo, len(ud) - 1)]
        te = (days >= lo) & (days <= hi)
        tr = (days < lo_e) | (days > hi_e)
        yield tr, te


def auc(y, p):
    from sklearn.metrics import roc_auc_score
    try:
        return float(roc_auc_score(y, p))
    except ValueError:
        return float("nan")


def run_period(R, X, pnl, tr_mask, te_mask, tag, rng, imp_rows=None):
    """All model x label fits for one walk-forward period. Returns {(model,label): dict(score, thr, cv)}."""
    res = {}
    Xa = X.values
    days_tr = R.day.values[tr_mask]
    for label, (T, S, L) in LABELS.items():
        reg = T is None
        y = pnl[label]["y"]
        for model in MODELS:
            t0 = time.time()
            oof = np.full(tr_mask.sum(), np.nan)
            Xtr, ytr = Xa[tr_mask], y[tr_mask]
            for ftr, fte in purged_folds(days_tr):
                _, _, p = fit_predict(model, reg, Xtr[ftr], ytr[ftr], Xtr[fte], rng)
                oof[fte] = p
            m, cl, p = fit_predict(model, reg, Xtr, ytr, Xa[te_mask], rng)
            ok = np.isfinite(oof)
            thr = {q: float(np.nanquantile(oof, 1 - q)) for q in SLICES}
            cv = dict(oof_auc=auc(ytr[ok] > (0 if reg else 0.5), oof[ok]),
                      oof_ic=float(pd.Series(oof[ok]).corr(pd.Series(ytr[ok]), method="spearman")),
                      test_auc=auc(y[te_mask] > (0 if reg else 0.5), p),
                      test_ic=float(pd.Series(p).corr(pd.Series(y[te_mask]), method="spearman")))
            imp = importance(model, m, cl, X, te_mask, y, reg, rng) if (model != "hgb" or label in ("T20S10L30", "REG30")) else None
            res[(model, label)] = dict(score=p.astype(np.float32), thr=thr, cv=cv, imp=imp)
            print(tag, model, label, {k: round(v, 4) for k, v in cv.items()}, f"{time.time() - t0:.0f}s", flush=True)
    return res


def importance(model, m, cl, X, te_mask, y, reg, rng):
    cols = list(X.columns)
    if model == "lr":
        coef = m[-1].coef_.ravel()
        return dict(zip(cols, np.abs(coef).tolist()))
    if model == "rf":
        return dict(zip(cols, m.feature_importances_.tolist()))
    from sklearn.inspection import permutation_importance
    idx = np.nonzero(te_mask)[0]
    idx = rng.choice(idx, min(15_000, len(idx)), replace=False)
    Xs = cl(X.values[idx])
    ys = np.clip(y[idx], -1, 2) if reg else y[idx].astype(int)
    pi = permutation_importance(m, Xs, ys, n_repeats=2, random_state=0, n_jobs=1,
                                scoring="r2" if reg else "roc_auc")
    return dict(zip(cols, pi.importances_mean.tolist()))


# ------------------------------------------------------------------------------------------------ trading
def simulate(R, pnl, label, score, thr, maxn, te_idx):
    """One position at a time across all three indices; at each decision minute take the best-scored candidate
    above the threshold if flat; at most maxn entries a day. Returns trade rows (indices into R)."""
    P = pnl[label]
    sel = te_idx[score >= thr]
    if not len(sel):
        return np.zeros(0, np.int64)
    sc = score[score >= thr]
    df = pd.DataFrame(dict(i=sel, day=R.day.values[sel], s=R.s.values[sel], sc=sc))
    df = df.sort_values(["day", "s", "sc"], ascending=[True, True, False]).drop_duplicates(["day", "s"])
    took = []
    for d, g in df.groupby("day", sort=False):
        free, n = -1, 0
        for i, s in zip(g.i.values, g.s.values):
            if n >= maxn:
                break
            if s >= free:
                took.append(i)
                n += 1
                free = s + int(P["held"][i])          # exit bar = s + held; next decision at or after it
    return np.array(took, np.int64)


def trades_df(R, pnl, label, idx):
    P = pnl[label]
    t = pd.DataFrame(dict(day=[D.ddate(x) for x in R.day.values[idx]], und=R.und.values[idx], s=R.s.values[idx],
                          side=R.side.values[idx], money=R.money.values[idx], E=R.E.values[idx],
                          gross=P["gross"][idx], net=P["net"][idx], nets=P["nets"][idx], charges=P["charges"][idx],
                          prem=P["prem"][idx], held=P["held"][idx]))
    t["year"] = [d.year for d in t.day]
    return t


def random_baseline(R, pnl, label, idx, pool_mask, B=1000, seed=7, col="net"):
    """Same days, same count per day, random candidates (any minute, index, contract) from that day's pool, same
    exits. p = P(random mean per trade >= real)."""
    rng = np.random.default_rng(seed)
    v = pnl[label][col]
    real = float(v[idx].mean()) if len(idx) else np.nan
    pool = np.nonzero(pool_mask)[0]
    pday = R.day.values[pool]
    order = np.argsort(pday, kind="stable")
    pool, pday = pool[order], pday[order]
    ud, st = np.unique(pday, return_index=True)
    en = np.append(st[1:], len(pool))
    cnt = pd.Series(R.day.values[idx]).value_counts()
    pos = np.searchsorted(ud, cnt.index.values)
    a, b, k = st[pos], en[pos], cnt.values
    tot = k.sum()
    means = np.empty(B)
    rep_a, rep_n = np.repeat(a, k), np.repeat(b - a, k)
    for j in range(B):
        r = rep_a + (rng.random(tot) * rep_n).astype(np.int64)
        means[j] = v[pool[r]].mean()
    return dict(real=real, rand_mean=float(means.mean()), p=float((1 + (means >= real).sum()) / (B + 1)))


# ------------------------------------------------------------------------------------------------ stages
def stage_wf():
    R, X, pnl = load()
    print("rows", len(R), "features", X.shape[1], flush=True)
    rng = np.random.default_rng(0)
    pre = R.day.values < HOLD
    allres = {}
    for tag, a, b in TEST:
        te = (R.day.values >= D.dnum(a)) & (R.day.values <= D.dnum(b)) & pre
        ud = np.unique(R.day.values[R.day.values < D.dnum(a)])
        cut = ud[-EMBARGO] if len(ud) > EMBARGO else ud[0]
        tr = R.day.values < cut
        allres[tag] = dict(te=np.nonzero(te)[0], res=run_period(R, X, pnl, tr, te, tag, rng))
        with open(os.path.join(OUT, "wf.pkl"), "wb") as f:
            pickle.dump(allres, f)
    variants(R, pnl, allres)


def variants(R, pnl, allres):
    rows, daily, trades = [], {}, {}
    for model in MODELS:
        for label in LABELS:
            for q in SLICES:
                for n in MAXN:
                    key = f"{model}|{label}|top{q * 100:g}%|max{n}"
                    ts = []
                    for tag, x in allres.items():
                        r = x["res"][(model, label)]
                        ts.append(simulate(R, pnl, label, r["score"], r["thr"][q], n, x["te"]))
                    idx = np.concatenate(ts)
                    t = trades_df(R, pnl, label, idx)
                    trades[key] = idx
                    rows.append(dict(variant=key, model=model, label=label, slice=q, maxn=n, trades=len(t),
                                     gross=t.gross.sum(), net=t.net.sum(), nets=t.nets.sum(),
                                     **{f"net_{y}": t[t.year == y].net.sum() for y in (2022, 2023, 2024, 2025)},
                                     **{f"gross_{y}": t[t.year == y].gross.sum() for y in (2022, 2023, 2024, 2025)}))
    V = pd.DataFrame(rows)
    V.to_csv(os.path.join(OUT, "variants.csv"), index=False)
    with open(os.path.join(OUT, "wf_trades.pkl"), "wb") as f:
        pickle.dump(trades, f)
    print(V.sort_values("net", ascending=False).head(15).to_string(), flush=True)


def stage_select():
    R, X, pnl = load()
    with open(os.path.join(OUT, "wf.pkl"), "rb") as f:
        allres = pickle.load(f)
    with open(os.path.join(OUT, "wf_trades.pkl"), "rb") as f:
        trades = pickle.load(f)
    V = pd.read_csv(os.path.join(OUT, "variants.csv"))
    te_all = np.concatenate([x["te"] for x in allres.values()])
    wf_days = np.unique(R.day.values[te_all])
    pool = np.zeros(len(R), bool)
    pool[te_all] = True
    # daily matrices (all wf days, zeros when flat)
    mats = {}
    for col in ("gross", "net", "nets"):
        M = np.zeros((len(wf_days), len(V)))
        for j, key in enumerate(V.variant):
            idx = trades[key]
            if len(idx):
                s = pd.Series(pnl[V.label[j]][col][idx]).groupby(R.day.values[idx]).sum()
                M[np.searchsorted(wf_days, s.index.values), j] = s.values
        mats[col] = M
    # random baseline per variant (net and gross), BH across all variants
    ps, pg, rm = [], [], []
    for j, key in enumerate(V.variant):
        idx = trades[key]
        if len(idx) < 10:
            ps.append(1.0); pg.append(1.0); rm.append(np.nan)
            continue
        bn = random_baseline(R, pnl, V.label[j], idx, pool, B=500, col="net")
        bg = random_baseline(R, pnl, V.label[j], idx, pool, B=500, col="gross")
        ps.append(bn["p"]); pg.append(bg["p"]); rm.append(bn["rand_mean"])
    V["p_rand_net"], V["p_rand_gross"], V["rand_mean_net"] = ps, pg, rm
    V["bh_q_net"] = OV.bh(np.array(ps))
    V["bh_q_gross"] = OV.bh(np.array(pg))
    sp = {c: OV.spa(mats[c], B=1000) for c in ("gross", "net", "nets")}
    pb = {c: OV.pbo(mats[c], S=16, max_combos=4000) for c in ("gross", "net")}
    # walk-forward OF THE SELECTION: each year pick the best variant on earlier wf years (2023+)
    yrs = [2022, 2023, 2024, 2025]
    sel_wf = []
    for i, y in enumerate(yrs[1:], 1):
        sc = V[[f"net_{yy}" for yy in yrs[:i]]].sum(axis=1)
        j = int(sc.idxmax())
        sel_wf.append(dict(year=y, picked=V.variant[j], net=float(V[f"net_{y}"][j]), gross=float(V[f"gross_{y}"][j])))
    # the choice: best total walk-forward net (pre-holdout), tie-break gross
    j = int(V.sort_values(["net", "gross"], ascending=False).index[0])
    choice = V.iloc[j].to_dict()
    # importance stability
    stab = {}
    for model, label in (("lr", "T20S10L30"), ("rf", "T20S10L30"), ("hgb", "T20S10L30"), ("lr", "REG30"),
                         ("rf", "REG30"), ("hgb", "REG30")):
        imps = pd.DataFrame({tag: x["res"][(model, label)]["imp"] for tag, x in allres.items()})
        rk = imps.rank(ascending=False)
        cors = [float(imps.iloc[:, i].corr(imps.iloc[:, i + 1], method="spearman")) for i in range(imps.shape[1] - 1)]
        top = {tag: list(imps[tag].sort_values(ascending=False).index[:6]) for tag in imps}
        stab[f"{model}|{label}"] = dict(rank_corr_consecutive=cors, top6=top,
                                        mean_rank=rk.mean(axis=1).sort_values().head(8).round(1).to_dict())
    cvs = {f"{tag}|{m}|{l}": x["res"][(m, l)]["cv"] for tag, x in allres.items() for (m, l) in x["res"]}
    V.to_csv(os.path.join(OUT, "variants_scored.csv"), index=False)
    res = dict(choice=choice, spa=sp, pbo=pb, sel_wf=sel_wf, stab=stab, cv=cvs, n_variants=len(V),
               n_wf_days=len(wf_days), n_pos_net=int((V.net > 0).sum()), n_pos_gross=int((V.gross > 0).sum()),
               n_bh_net=int((V.bh_q_net < 0.05).sum()), n_bh_gross=int((V.bh_q_gross < 0.05).sum()))
    with open(os.path.join(OUT, "select.json"), "w") as f:
        json.dump(res, f, indent=1, default=str)
    print(json.dumps({k: v for k, v in res.items() if k not in ("cv", "stab")}, indent=1, default=str))
    print(V.sort_values("net", ascending=False).head(10)[["variant", "trades", "gross", "net", "nets", "p_rand_net", "bh_q_net", "p_rand_gross"]].to_string())


def stage_holdout():
    """The ONE locked-holdout run: the chosen variant, trained on everything before 2025-10-01 (minus embargo)."""
    R, X, pnl = load()
    with open(os.path.join(OUT, "select.json")) as f:
        sel = json.load(f)
    ch = sel["choice"]
    model, label, q, n = ch["model"], ch["label"], float(ch["slice"]), int(ch["maxn"])
    ud = np.unique(R.day.values[R.day.values < HOLD])
    tr = R.day.values < ud[-EMBARGO]
    te = R.day.values >= HOLD
    rng = np.random.default_rng(0)
    T, S, L = LABELS[label]
    reg = T is None
    y = pnl[label]["y"]
    days_tr = R.day.values[tr]
    oof = np.full(tr.sum(), np.nan)
    Xa = X.values
    for ftr, fte in purged_folds(days_tr):
        _, _, p = fit_predict(model, reg, Xa[tr][ftr], y[tr][ftr], Xa[tr][fte], rng)
        oof[fte] = p
    m, cl, p = fit_predict(model, reg, Xa[tr], y[tr], Xa[te], rng)
    thr = float(np.nanquantile(oof, 1 - q))
    te_idx = np.nonzero(te)[0]
    idx = simulate(R, pnl, label, p, thr, n, te_idx)
    t = trades_df(R, pnl, label, idx)
    bn = random_baseline(R, pnl, label, idx, te, B=2000, col="net")
    bg = random_baseline(R, pnl, label, idx, te, B=2000, col="gross")
    hdays = np.unique(R.day.values[te])
    t.to_csv(os.path.join(OUT, "holdout_trades.csv"), index=False)
    out = dict(choice=ch, thr=thr, test_auc=auc(y[te] > (0 if reg else 0.5), p), n_days=len(hdays),
               base_net=bn, base_gross=bg)
    with open(os.path.join(OUT, "holdout.json"), "w") as f:
        json.dump(out, f, indent=1, default=str)
    print(json.dumps(out, indent=1, default=str))
    print(t.groupby("year")[["gross", "net", "nets"]].sum(), len(t))


if __name__ == "__main__":
    {"wf": stage_wf, "select": stage_select, "holdout": stage_holdout}[sys.argv[1]]()
