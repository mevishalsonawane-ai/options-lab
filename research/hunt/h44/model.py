"""h44 stage 3: meta-labelling models, strict nested walk-forward by year, SPA / White RC, random-skip baseline,
feature-importance stability, and the locked holdout ONCE (see PREREG.md).

    python3 -I research/hunt/h44/model.py pre      # pre-holdout only; writes choice.json
    python3 -I research/hunt/h44/model.py hold     # holdout, ONCE, for the configuration in choice.json
"""
from __future__ import annotations

import importlib.util
import json
import os
import sys
import warnings

HERE = os.path.dirname(os.path.abspath(__file__))
HUNT = os.path.dirname(HERE)
sys.path.insert(0, os.path.dirname(HUNT))
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor, RandomForestClassifier  # noqa: E402
from sklearn.linear_model import LogisticRegression, Ridge  # noqa: E402
from sklearn.metrics import roc_auc_score  # noqa: E402

warnings.filterwarnings("ignore")
OUT = os.path.join(C.SCRATCH, "hunt/h44")
HOLD = pd.Timestamp("2025-10-01")
TEST_YEARS = (2022, 2023, 2024, 2025)
SKIPS = (0.20, 0.33, 0.50)
TOPQ = 0.80
EMB = 5
MODELS = ("LR", "GB", "RF", "RIDGE", "GBR")
UNDS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "NIFTY", "SENSEX")
BOOKS = {"P1": ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"), "BN": ("BANKNIFTY",), "ALL5": UNDS}
KAPS = (0.02, 0.04)
CAL = pd.read_csv(os.path.join(C.SCRATCH, "hunt/h4/cache/h4/daily_real.csv"), index_col=0, parse_dates=True).index
import pickle  # noqa: E402
with open(os.path.join(C.JX, "ix_NIFTY.pkl"), "rb") as _f:
    ECAL = pd.DatetimeIndex(sorted(pd.Timestamp(d) for d in pickle.load(_f)))   # embargo calendar (2020+)
ECAL = ECAL.union(CAL)
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 60)

NUM = ["tod", "dow", "book15", "dte", "vix_prev", "vix_chg", "atr_pct", "range_atr", "fhr_atr", "gap_dir", "pdh_dir",
       "pdl_dir", "posr_dir", "r15_dir", "r60_dir", "ropen_dir", "bu15_dir", "buopen_dir", "doipc5_dir", "pcr_chg_dir",
       "iv", "iv_har", "iv_rv", "rr_dir", "drr15_dir", "strad_pct", "prem_pct", "tick_rel", "logv5", "roll_hs",
       "room_atr", "stop_atr", "brain_room_atr", "n_sig_before", "n_closed_before", "prior_out",
       "s01_dir", "s02_dir", "s03_dir"]
MISS = {"m_vix": "vix_chg", "m_iv": "iv", "m_oi": "bu15_dir", "m_fii": "s01_dir"}


def load():
    T = pd.read_parquet(os.path.join(OUT, "trades44.parquet"))
    X = pd.read_parquet(os.path.join(OUT, "feats44.parquet"))
    T["day"] = pd.to_datetime(T.day)
    for c in NUM + ["vwapsh_dir", "vwb_dir"]:
        if c not in X:
            X[c] = np.nan
    F = X[NUM].astype(float).copy()
    for k, c in MISS.items():
        F[k] = X[c].isna().astype(float)
    for u in UNDS:
        F["u_" + u[:4]] = (T.und == u).astype(float).values
    F = F.replace([np.inf, -np.inf], np.nan)
    T["year"] = T.day.dt.year
    T["filled"] = T["lots1_0.02"] > 0
    T["y"] = (T["net1_0.02"] > 0).astype(int)
    T["ret"] = np.where(T["prem1_0.02"] > 0, T["net1_0.02"] / T["prem1_0.02"].clip(lower=1), 0.0)
    return T, F, X


class Prep:
    def fit(self, F):
        self.lo, self.hi = F.quantile(0.01), F.quantile(0.99)
        self.med = F.median()
        Z = F.clip(self.lo, self.hi, axis=1).fillna(self.med)
        self.mu, self.sd = Z.mean(), Z.std().replace(0, 1).fillna(1)
        return self

    def __call__(self, F):
        Z = F.clip(self.lo, self.hi, axis=1).fillna(self.med).fillna(0)
        return ((Z - self.mu) / self.sd).clip(-5, 5).values


def make(name):
    if name == "LR":
        return LogisticRegression(C=0.05, max_iter=2000)
    if name == "GB":
        return HistGradientBoostingClassifier(max_depth=2, max_iter=150, learning_rate=0.03, min_samples_leaf=50,
                                              l2_regularization=1.0, random_state=0)
    if name == "RF":
        return RandomForestClassifier(n_estimators=300, max_depth=4, min_samples_leaf=40, max_features="sqrt",
                                      n_jobs=1, random_state=0)
    if name == "RIDGE":
        return Ridge(alpha=30.0)
    return HistGradientBoostingRegressor(max_depth=2, max_iter=150, learning_rate=0.03, min_samples_leaf=50,
                                         l2_regularization=1.0, random_state=0)


def fit_score(name, Ftr, ytr, rtr, Fte):
    p = Prep().fit(Ftr)
    m = make(name)
    if name in ("RIDGE", "GBR"):
        lo, hi = np.quantile(rtr, [0.01, 0.99])
        m.fit(p(Ftr), np.clip(rtr, lo, hi))
        return m.predict(p(Fte)), m, p
    m.fit(p(Ftr), ytr)
    return m.predict_proba(p(Fte))[:, 1], m, p


def embargo_mask(days, lo, hi):
    """True where a training day is NOT within EMB trading days before lo or after hi (exclusive window [lo, hi))."""
    c = ECAL
    i_lo = c.searchsorted(lo)
    i_hi = c.searchsorted(hi)
    a = c[max(i_lo - EMB, 0)] if i_lo > 0 else lo
    b = c[min(i_hi + EMB, len(c) - 1)] if i_hi < len(c) else hi
    return ~((days >= a) & (days < b))


def inner_oof(name, T, F, idx):
    """leave-one-year-out OOF scores for training rows idx (filled trades), embargo either side."""
    tr = T.loc[idx]
    oof = pd.Series(np.nan, index=idx)
    for y in sorted(tr.year.unique()):
        lo, hi = pd.Timestamp(f"{y}-01-01"), pd.Timestamp(f"{y + 1}-01-01")
        te = tr.index[tr.year == y]
        ok = embargo_mask(tr.day, lo, hi).values & (tr.year != y).values
        fit = tr.index[ok]
        if len(fit) < 100 or len(te) == 0 or T.loc[fit, "y"].nunique() < 2:
            continue
        s, _, _ = fit_score(name, F.loc[fit], T.loc[fit, "y"].values, T.loc[fit, "ret"].values, F.loc[te])
        oof[te] = s
    return oof


def outer(name, T, F, lo, hi):
    """train on filled trades before lo (embargo), threshold from inner OOF; score all trades in [lo, hi)."""
    tr_mask = T.filled & (T.day < lo) & embargo_mask(T.day, lo, hi)
    idx = T.index[tr_mask]
    oof = inner_oof(name, T, F, idx)
    te = T.index[(T.day >= lo) & (T.day < hi)]
    s, m, p = fit_score(name, F.loc[idx], T.loc[idx, "y"].values, T.loc[idx, "ret"].values, F.loc[te])
    o = oof.dropna()
    thr = {sk: float(np.quantile(o.values, sk)) for sk in SKIPS}
    thr["top"] = float(np.quantile(o.values, TOPQ))
    return pd.Series(s, index=te), thr, m, p, idx, oof


def daily(t, col, lo, hi):
    c = CAL[(CAL >= lo) & (CAL < hi)]
    return t.groupby("day")[col].sum().reindex(c, fill_value=0.0)


def book_pnl(T, keep, two, unds, k, lo, hi, gross=False):
    """daily P&L of a book: kept trades 1 lot (or 2 lots where two) ; unfiltered = keep all, 1 lot."""
    t = T[T.und.isin(unds) & (T.day >= lo) & (T.day < hi)].copy()
    kk = keep.reindex(t.index).fillna(True).values.astype(bool)
    tw = two.reindex(t.index).fillna(False).values.astype(bool)
    pre = "gross" if gross else "net"
    v = np.where(tw, t[f"{pre}2_{k}"].values, t[f"{pre}1_{k}"].values)
    t["pnl"] = np.where(kk, v, 0.0)
    return daily(t, "pnl", lo, hi)


def rand_skip_p(T, score, thr, unds, lo, hi, B=2000, seed=44):
    t = T[T.und.isin(unds) & (T.day >= lo) & (T.day < hi)]
    rng = np.random.default_rng(seed)
    net = t["net1_0.02"].values
    sk = (score.reindex(t.index).values < thr)
    real = -net[sk].sum()
    yrs = t.year.values
    draws = np.zeros(B)
    for y in np.unique(yrs):
        idx = np.flatnonzero(yrs == y)
        m = int(sk[idx].sum())
        if m == 0:
            continue
        for b in range(B):
            draws[b] += -net[rng.choice(idx, m, replace=False)].sum()
    return float((1 + (draws >= real).sum()) / (B + 1))


def walk_1L(T, keep, two, unds, k, lo, hi):
    R = _load("r23", os.path.join(HUNT, "h23", "run.py"))
    t = T[T.und.isin(unds) & (T.day >= lo) & (T.day < hi)].copy()
    kk = keep.reindex(t.index).fillna(True).values.astype(bool)
    tw = two.reindex(t.index).fillna(False).values.astype(bool)
    n = np.where(tw, 2, 1)
    for c in ("net", "gross", "lots", "prem", "end"):
        t[f"{c}_{k}"] = np.where(n == 2, t[f"{c}2_{k}"], t[f"{c}1_{k}"])
    t[f"lots_{k}"] = np.where(kk, t[f"lots_{k}"], 0)
    s, dn = R.walk(t, unds, k, lo, hi)
    return s


def _load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def stats_line(d):
    eq = d.cumsum()
    dd = (eq - np.maximum.accumulate(np.r_[0.0, eq.values])[1:]).min()
    return dict(rs_day=d.mean(), total=d.sum(), maxdd=dd, worst_day=d.min())


def pre():
    T, F, X = load()
    print("trades", len(T), "filled", int(T.filled.sum()), T.groupby("und").size().to_dict())
    print("pre-holdout win rate (filled)", T[T.filled & (T.day < HOLD)].y.mean().round(3))
    scores = {m: [] for m in MODELS}
    thrs = {m: {} for m in MODELS}
    imp_lr, imp_gb, aucs = {}, {}, []
    for Y in TEST_YEARS:
        lo, hi = pd.Timestamp(f"{Y}-01-01"), min(pd.Timestamp(f"{Y + 1}-01-01"), HOLD)
        for m in MODELS:
            s, thr, mdl, p, idx, oof = outer(m, T, F, lo, hi)
            scores[m].append(s)
            thrs[m][Y] = thr
            te = s.index[T.loc[s.index, "filled"]]
            yy = T.loc[te, "y"].values
            aucs.append(dict(model=m, year=Y, n_train=len(idx), n_test=len(te), auc_test=roc_auc_score(yy, s[te]) if len(set(yy)) > 1 else np.nan,
                             auc_inner_oof=roc_auc_score(T.loc[oof.dropna().index, "y"], oof.dropna()) if oof.notna().sum() > 50 else np.nan))
            if m == "LR":
                imp_lr[Y] = pd.Series(mdl.coef_[0], index=F.columns)
            if m == "GB":
                # permutation importance on the inner LOYO folds (training data only)
                tr = T.loc[idx]
                acc = pd.Series(0.0, index=F.columns)
                nf = 0
                rng = np.random.default_rng(Y)
                for y in sorted(tr.year.unique()):
                    te_i = tr.index[tr.year == y]
                    ok = embargo_mask(tr.day, pd.Timestamp(f"{y}-01-01"), pd.Timestamp(f"{y + 1}-01-01")).values & (tr.year != y).values
                    fi = tr.index[ok]
                    if len(fi) < 100 or len(te_i) < 50:
                        continue
                    _, mm, pp = fit_score("GB", F.loc[fi], T.loc[fi, "y"].values, T.loc[fi, "ret"].values, F.loc[te_i])
                    Z = pp(F.loc[te_i])
                    yt = T.loc[te_i, "y"].values
                    base = roc_auc_score(yt, mm.predict_proba(Z)[:, 1])
                    for j, c in enumerate(F.columns):
                        dl = []
                        for _ in range(3):
                            Zp = Z.copy()
                            Zp[:, j] = rng.permutation(Zp[:, j])
                            dl.append(base - roc_auc_score(yt, mm.predict_proba(Zp)[:, 1]))
                        acc[c] += np.mean(dl)
                    nf += 1
                imp_gb[Y] = acc / max(nf, 1)
            print(Y, m, "trained", len(idx), "thr", {k: round(v, 4) for k, v in thr.items()}, flush=True)
    A = pd.DataFrame(aucs)
    print("\nAUC (test year, filled trades) and inner OOF AUC\n", A.pivot(index="model", columns="year", values="auc_test").round(3).to_string())
    print(A.pivot(index="model", columns="year", values="auc_inner_oof").round(3).to_string())
    S = {m: pd.concat(v) for m, v in scores.items()}
    lo_all, hi_all = pd.Timestamp("2022-01-01"), HOLD
    # unfiltered per year (incl. 2021 for info) and per variant
    rows, X15 = [], {}
    for m in MODELS:
        for sk in SKIPS:
            vname = f"{m}_s{int(sk * 100)}"
            keep = pd.concat([S[m][(T.loc[S[m].index, "year"] == Y).values] >= thrs[m][Y][sk] for Y in TEST_YEARS])
            two = pd.concat([S[m][(T.loc[S[m].index, "year"] == Y).values] >= thrs[m][Y]["top"] for Y in TEST_YEARS]) & keep
            none = pd.Series(False, index=keep.index)
            for bk, unds in BOOKS.items():
                for k in KAPS:
                    for Y in TEST_YEARS + ("ALL",):
                        lo = lo_all if Y == "ALL" else pd.Timestamp(f"{Y}-01-01")
                        hi = hi_all if Y == "ALL" else min(pd.Timestamp(f"{Y + 1}-01-01"), HOLD)
                        base = book_pnl(T, pd.Series(True, index=keep.index), none, unds, k, lo, hi)
                        filt = book_pnl(T, keep, none, unds, k, lo, hi)
                        sz = book_pnl(T, keep, two, unds, k, lo, hi)
                        tb = T[T.und.isin(unds) & (T.day >= lo) & (T.day < hi)]
                        kp = keep.reindex(tb.index).fillna(True)
                        rows.append(dict(variant=vname, model=m, skip=sk, book=bk, kappa=k, year=Y, days=len(base),
                                         base=base.mean(), filt=filt.mean(), excess=filt.mean() - base.mean(),
                                         sizeup=sz.mean(), sizeup_excess=sz.mean() - base.mean(),
                                         trades=len(tb), skipped=int((~kp).sum()),
                                         skipped_avg=tb.loc[~kp.values, f"net1_{k}"].mean() if (~kp).any() else np.nan))
                        if bk == "P1" and k == 0.02 and Y == "ALL":
                            X15[vname] = (filt - base).values
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(OUT, "wf_variants.csv"), index=False)
    allp = R[(R.year == "ALL") & (R.kappa == 0.02)].pivot(index="variant", columns="book", values="excess")
    allp4 = R[(R.year == "ALL") & (R.kappa == 0.04)].pivot(index="variant", columns="book", values="excess")
    yrs = R[(R.book == "P1") & (R.kappa == 0.02) & (R.year != "ALL")].pivot(index="variant", columns="year", values="excess")
    base_rows = R[(R.variant == R.variant.iloc[0])][["book", "kappa", "year", "days", "base"]]
    print("\nUNFILTERED Liquidity, nested-WF test years, Rs/day (1 lot per index)\n",
          base_rows.pivot_table(index=["book", "kappa"], columns="year", values="base").round(1).to_string())
    for bk in BOOKS:
        g = []
        for Y in (2021,):
            lo, hi = pd.Timestamp("2021-01-01"), pd.Timestamp("2022-01-01")
            g.append(book_pnl(T, pd.Series(True, index=T.index), pd.Series(False, index=T.index), BOOKS[bk], 0.02, lo, hi).mean())
        print(f"  (info) {bk} 2021 unfiltered Rs/day k.02: {g[0]:.1f}")
    # random-skip p and SPA
    pr = {}
    for m in MODELS:
        for sk in SKIPS:
            vname = f"{m}_s{int(sk * 100)}"
            thr_s = pd.concat([pd.Series(thrs[m][Y][sk], index=S[m].index[(T.loc[S[m].index, "year"] == Y).values]) for Y in TEST_YEARS])
            pr[vname] = rand_skip_p(T, S[m] - thr_s, 0.0, BOOKS["P1"], lo_all, hi_all)
    sp = OF.spa(np.column_stack([X15[v] for v in X15]), B=2000, mean_block=5.0, seed=44)
    names = list(X15)
    summ = pd.DataFrame(dict(P1_k02=allp["P1"], P1_k04=allp4["P1"], BN_k02=allp["BN"], ALL5_k02=allp["ALL5"])).join(yrs)
    summ["years_pos"] = (yrs > 0).sum(axis=1)
    summ["p_rand"] = pd.Series(pr)
    summ["bh_q"] = OF.bh(summ.p_rand.values)
    sz = R[(R.year == "ALL") & (R.kappa == 0.02) & (R.book == "P1")].set_index("variant")
    summ["P1_sizeup_excess_k02"] = sz.sizeup_excess
    summ["P1_sizeup_excess_k04"] = R[(R.year == "ALL") & (R.kappa == 0.04) & (R.book == "P1")].set_index("variant").sizeup_excess
    summ["skipped_P1"] = sz.skipped
    summ["skipped_avg_P1"] = sz.skipped_avg
    print("\nNESTED-WF excess Rs/day vs unfiltered (2022-01 .. 2025-09), per variant\n", summ.round(3).to_string())
    print("\nSPA / White RC over the 15 skip variants (P1, k .02, daily excess):", sp, "best =", names[sp["best"]])
    summ.to_csv(os.path.join(OUT, "wf_summary.csv"))
    # choice
    best = summ.sort_values(["P1_k02", "skipped_P1"], ascending=[False, True]).index[0]
    m, sk = best.split("_s")
    g = summ.loc[best]
    gates = {"g1_k02_k04_pos": bool(g.P1_k02 > 0 and g.P1_k04 > 0), "g2_3of4_years": bool(g.years_pos >= 3),
             "g3_spa_p<=0.10": bool(sp["spa_p"] <= 0.10), "g4_BN>=0": bool(g.BN_k02 >= 0)}
    ch = dict(variant=best, model=m, skip=int(sk) / 100, gates_pre=gates, pass_pre=all(gates.values()), spa=sp,
              summary=g.to_dict())
    json.dump(ch, open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)
    print("\nCHOICE", json.dumps(ch, default=float, indent=1))
    # importance stability
    L = pd.DataFrame(imp_lr)
    G = pd.DataFrame(imp_gb)
    L.to_csv(os.path.join(OUT, "imp_lr.csv"))
    G.to_csv(os.path.join(OUT, "imp_gb.csv"))
    sign_agree = (np.sign(L).eq(np.sign(L[L.columns[-1]]), axis=0)).sum(axis=1)
    print("\nLR standardised coefficients by training cut (fit for test year)\n", L.assign(sign_same_as_last=sign_agree).round(3).to_string())
    print("\nGB permutation importance (inner OOF AUC drop) by training cut\n", G.round(4).to_string())
    for nm, Mx in (("LR |coef|", L.abs()), ("GB perm", G)):
        cs = Mx.columns
        rc = [Mx[cs[i]].rank().corr(Mx[cs[i + 1]].rank()) for i in range(len(cs) - 1)]
        print(f"Spearman rank corr of {nm} between consecutive cuts {list(cs)}: {np.round(rc, 2)}")
        print(f"  top-8 per cut: " + " | ".join(f"{c}: {', '.join(Mx[c].sort_values(ascending=False).index[:8])}" for c in cs))
    # calibration: test-year score quintiles vs mean net (filled), chosen model
    s = S[m]
    t = T.loc[s.index]
    t = t[t.filled]
    q = pd.qcut(s[t.index].rank(method="first"), 5, labels=False)
    print("\nchosen model", m, "pooled test-year score quintile -> mean net1 k.02 (filled trades), by book")
    print(t.assign(q=q).groupby(["q"]).agg(n=("y", "size"), win=("y", "mean"), net=("net1_0.02", "mean")).round(2).to_string())
    print(t[t.und.isin(BOOKS["P1"])].assign(q=q).groupby("q").agg(n=("y", "size"), win=("y", "mean"), net=("net1_0.02", "mean")).round(2).to_string())
    # Rs 1 lakh walk for the chosen variant (pre-holdout test years), P1 and BN, with and without size-up
    keep = pd.concat([S[m][(T.loc[S[m].index, "year"] == Y).values] >= thrs[m][Y][int(sk) / 100] for Y in TEST_YEARS])
    two = pd.concat([S[m][(T.loc[S[m].index, "year"] == Y).values] >= thrs[m][Y]["top"] for Y in TEST_YEARS]) & keep
    allk = pd.Series(True, index=keep.index)
    nonw = pd.Series(False, index=keep.index)
    W1 = []
    for bk in ("BN", "P1"):
        for nm, kp, tw in (("plain", allk, nonw), ("filtered", keep, nonw), ("filtered+2nd lot top", keep, two)):
            for k in KAPS:
                s1 = walk_1L(T, kp, tw, BOOKS[bk], k, lo_all, hi_all)
                W1.append(dict(book=bk, plan=nm, kappa=k, **{x: s1[x] for x in ("net_day", "gross_day", "maxdd", "worst_day", "taken", "skipped_cash", "green_months", "min_cap")}))
    W1 = pd.DataFrame(W1)
    print("\nRs 1 lakh free-cash walk, nested-WF test years 2022-01..2025-09, chosen config\n", W1.round(1).to_string())
    W1.to_csv(os.path.join(OUT, "wf_walk1L.csv"), index=False)
    # breadth (descriptive only, not in models): 2024-01..2025-09, filled BN/NIFTY trades
    tb = T[T.filled & (T.day >= "2024-01-01") & (T.day < HOLD) & T.und.isin(["BANKNIFTY", "NIFTY"])]
    for c in ("vwapsh_dir", "vwb_dir"):
        x = X.loc[tb.index, c]
        ok = x.notna()
        if ok.sum() > 30:
            print(f"breadth {c}: n {int(ok.sum())}, Spearman with net {x[ok].corr(tb.loc[ok, 'net1_0.02'], method='spearman'):.3f}, "
                  f"mean net when agreeing (>0) {tb.loc[ok & (x > 0), 'net1_0.02'].mean():.0f} vs opposing {tb.loc[ok & (x <= 0), 'net1_0.02'].mean():.0f}")


def hold():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    flag = os.path.join(OUT, "HOLDOUT_RUN.flag")
    if os.path.exists(flag):
        raise SystemExit("holdout already run once")
    open(flag, "w").write(pd.Timestamp.now().isoformat())
    T, F, X = load()
    m, sk = ch["model"], ch["skip"]
    lo, hi = HOLD, pd.Timestamp("2030-01-01")
    s, thr, mdl, p, idx, oof = outer(m, T, F, lo, hi)
    keep = s >= thr[sk]
    two = (s >= thr["top"]) & keep
    none = pd.Series(False, index=keep.index)
    allk = pd.Series(True, index=keep.index)
    print("HOLDOUT (run once) config", ch["variant"], "trained on", len(idx), "thr", thr)
    te = s.index[T.loc[s.index, "filled"]]
    print("holdout AUC", round(roc_auc_score(T.loc[te, "y"], s[te]), 3))
    rows = []
    for bk, unds in BOOKS.items():
        for k in KAPS:
            base = book_pnl(T, allk, none, unds, k, lo, hi)
            filt = book_pnl(T, keep, none, unds, k, lo, hi)
            sz = book_pnl(T, keep, two, unds, k, lo, hi)
            gb = book_pnl(T, allk, none, unds, k, lo, hi, gross=True)
            gf = book_pnl(T, keep, none, unds, k, lo, hi, gross=True)
            tb = T[T.und.isin(unds) & (T.day >= lo)]
            kp = keep.reindex(tb.index).fillna(True)
            rows.append(dict(book=bk, kappa=k, days=len(base), base=base.mean(), filt=filt.mean(), excess=filt.mean() - base.mean(),
                             sizeup=sz.mean(), sizeup_excess=sz.mean() - base.mean(), gross_base=gb.mean(), gross_filt=gf.mean(),
                             trades=len(tb), skipped=int((~kp).sum()), skipped_avg=tb.loc[~kp.values, f"net1_{k}"].mean(),
                             base_dd=stats_line(base)["maxdd"], filt_dd=stats_line(filt)["maxdd"], sizeup_dd=stats_line(sz)["maxdd"],
                             lots_5k_base=5000 / base.mean() if base.mean() > 0 else np.inf,
                             lots_5k_filt=5000 / filt.mean() if filt.mean() > 0 else np.inf))
    H = pd.DataFrame(rows)
    print(H.round(1).to_string())
    H.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
    p1 = rand_skip_p(T, s - thr[sk], 0.0, BOOKS["P1"], lo, hi)
    pbn = rand_skip_p(T, s - thr[sk], 0.0, BOOKS["BN"], lo, hi)
    print("random-skip p (P1, BN):", round(p1, 3), round(pbn, 3))
    W1 = []
    for bk in ("BN", "P1"):
        for nm, kp, tw in (("plain", allk, none), ("filtered", keep, none), ("filtered+2nd lot top", keep, two)):
            for k in KAPS:
                s1 = walk_1L(T, kp, tw, BOOKS[bk], k, lo, hi)
                W1.append(dict(book=bk, plan=nm, kappa=k, **{x: s1[x] for x in ("net_day", "gross_day", "maxdd", "worst_day", "taken", "skipped_cash", "green_months", "min_cap")}))
    W1 = pd.DataFrame(W1)
    print("\nRs 1 lakh free-cash walk, HOLDOUT\n", W1.round(1).to_string())
    W1.to_csv(os.path.join(OUT, "hold_walk1L.csv"), index=False)
    g5 = bool(H[(H.book == "P1") & (H.kappa == 0.02)].excess.iloc[0] > 0)
    print("gate5 holdout excess>0 (P1 k.02):", g5, "| pre gates:", ch["gates_pre"], "| ADOPTED:", bool(ch["pass_pre"] and g5))
    # per-month holdout excess P1 k.02
    base = book_pnl(T, allk, none, BOOKS["P1"], 0.02, lo, hi)
    filt = book_pnl(T, keep, none, BOOKS["P1"], 0.02, lo, hi)
    mm = pd.DataFrame(dict(base=base, filt=filt)).groupby(base.index.to_period("M")).sum()
    mm["excess"] = mm.filt - mm.base
    print("\nholdout months P1 k.02\n", mm.round(0).to_string())


if __name__ == "__main__":
    {"pre": pre, "hold": hold}[sys.argv[1]]()
