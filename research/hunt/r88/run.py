"""R88 design run (PREREG sections 3-6 on the design period only; the holdout is read by final.py, once).

    python -m research.hunt.r88.run build      # candle tables per instrument x timeframe into cfg.CACHE
    python -m research.hunt.r88.run design     # Parts A-D on the design period -> results/
"""
from __future__ import annotations

import json
import sys
import warnings

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier, HistGradientBoostingRegressor
from sklearn.metrics import roc_auc_score

from . import candles as C
from . import cfg, data, stats

warnings.filterwarnings("ignore")
CODES = {"F09": {"below": -1, "inside": 0, "above": 1}, "F20": {"normal": 0, "event": 1},
         "F19": {"Mon": 0, "Tue": 1, "Wed": 2, "Thu": 3, "Fri": 4}}


def events() -> set:
    e = pd.read_csv(cfg.ROOT / "research" / "brain" / "calendar_data" / "events.csv")
    return set(pd.to_datetime(e.date).dt.date)


def path(sym: str, tf: int):
    return cfg.CACHE / f"candles_{sym}_{tf}.parquet"


def build_all(syms=cfg.ALL) -> None:
    ev = events()
    raw, lead = {}, {}
    for s in syms:
        raw[s] = data.instrument(s)
        print(s, len(raw[s]["min"]), "minutes", len(raw[s]["expiries"]), "expiries", flush=True)
    for s in cfg.INDICES:
        if s in raw:
            for tf in cfg.TFS:
                lead[(s, tf)] = C.targets(C.build(raw[s]["min"], tf, cfg.NSE_OPEN))
    for s in syms:
        x = raw[s]
        for tf in cfg.TFS:
            ld = lead.get((cfg.LEADER.get(s), tf))
            k = C.factors(s, tf, x["min"], x["chain"], x["vix"], x["expiries"], leader=ld, events=ev)
            k = pd.concat([k, C.concurrent(k, x["chain"], x["vix"], ld, tf)], axis=1)
            k = k[k.day.dt.date >= cfg.start(s)].reset_index(drop=True)
            k.to_parquet(path(s, tf))
            print(s, tf, len(k), "candles", k.day.nunique(), "sessions", flush=True)
        if s in cfg.INDICES:  # 1-minute candles for the census
            k1 = C.targets(C.build(x["min"], 1, cfg.NSE_OPEN))
            k1 = k1[k1.day.dt.date >= cfg.start(s)].reset_index(drop=True)
            k1.to_parquet(path(s, 1))


def load(sym: str, tf: int, part: str) -> pd.DataFrame:
    k = pd.read_parquet(path(sym, tf))
    d = k.day.dt.date
    if part == "design":
        return k[d <= cfg.DESIGN_END].reset_index(drop=True)
    if part == "holdout":
        if not cfg.FINAL_FLAG.exists():
            raise RuntimeError("holdout not opened: run final.py")
        return k[(d >= cfg.HOLD_START) & (d <= cfg.END)].reset_index(drop=True)
    raise ValueError(part)


# ------------------------------------------------------------------------------------------------ Part A (census)
def census(k: pd.DataFrame, sym: str, tf: int, expiries: set | None = None, ev: set | None = None) -> dict:
    rng = (k.h - k.l)
    body = (k.c - k.o)
    ok = rng > 0
    upw = ((k.h - np.maximum(k.o, k.c)) / rng).where(ok)
    low = ((np.minimum(k.o, k.c) - k.l) / rng).where(ok)
    bps = rng / k.c * 1e4
    r = k.r
    lr = np.log(rng.where(ok))
    out = dict(sym=sym, tf=tf, candles=int(len(k)), sessions=int(k.day.nunique()),
               range_bps_median=float(bps.median()), range_pts_median=float(rng.median()),
               range_bps_p99=float(bps.quantile(0.99)),
               body_to_range_median=float((body.abs() / rng).where(ok).median()),
               upper_wick_median=float(upw.median()), lower_wick_median=float(low.median()),
               up_share=float(k.up.mean()), ret_bps_p99_abs=float((r.abs() * 1e4).quantile(0.99)),
               ret_autocorr_lag1=float(r.autocorr(1)), logrange_autocorr_lag1=float(lr.autocorr(1)))
    if expiries is not None:
        e = k.day.dt.date.isin(expiries)
        out["range_bps_expiry_day"] = float(bps[e].median()) if e.any() else None
        out["range_bps_other_days"] = float(bps[~e].median())
        out["expiry_days"] = int(k.day[e].nunique())
    if ev:
        e = k.day.dt.date.isin(ev)
        out["range_bps_event_day"] = float(bps[e].median()) if e.any() else None
        out["event_days"] = int(k.day[e].nunique())
    return out


def slot_table(k: pd.DataFrame, sym: str, tf: int) -> pd.DataFrame:
    bps = (k.h - k.l) / k.c * 1e4
    g = k.assign(bps=bps, hhmm=k.ts.dt.strftime("%H:%M"), wd=k.day.dt.day_name().str[:3])
    t = g.groupby("hhmm").agg(candles=("bps", "size"), range_bps=("bps", "median"), up_share=("up", "mean"),
                              abs_body_bps=("r", lambda x: float((x.abs() * 1e4).median())))
    return t.reset_index().assign(sym=sym, tf=tf)


# ------------------------------------------------------------------------------------------------ Part B (concurrent)
def concurrent_ols(k: pd.DataFrame, sym: str, tf: int) -> dict:
    cols = [c for c in C.CONC if k[c].notna().mean() > 0.5]
    d = k[["r", *cols, "day"]].replace([np.inf, -np.inf], np.nan).dropna()
    if len(d) < 200:
        return dict(sym=sym, tf=tf, n=int(len(d)))
    y = d.r.to_numpy() * 1e4
    X = d[cols].to_numpy()
    X = (X - X.mean(0)) / np.where(X.std(0) > 0, X.std(0), 1)
    X1 = np.column_stack([np.ones(len(y)), X])
    beta, *_ = np.linalg.lstsq(X1, y, rcond=None)
    res = y - X1 @ beta
    r2 = 1 - res.var() / y.var()
    corr = {c: float(np.corrcoef(d[c], y)[0, 1]) for c in cols}
    single = {}
    for c in cols:
        x = (d[c] - d[c].mean()) / (d[c].std() or 1)
        b = np.polyfit(x, y, 1)
        single[c] = float(1 - (y - np.polyval(b, x)).var() / y.var())
    return dict(sym=sym, tf=tf, n=int(len(d)), sessions=int(d.day.nunique()), r2=float(r2), ret_sd_bps=float(y.std()),
                beta_bps_per_sd={c: float(b) for c, b in zip(cols, beta[1:])}, corr=corr, r2_alone=single)


# ------------------------------------------------------------------------------------------------ Part D (model)
def features(k: pd.DataFrame) -> pd.DataFrame:
    X = k[C.CONT].astype(float).copy()
    for f, m in CODES.items():
        X[f] = k[f].map(m).astype(float)
    X["dte"] = k.dte.astype(float)
    X["slot"] = k.slot.astype(float)
    return X.replace([np.inf, -np.inf], np.nan)


def _boot_days(days, n=1000, seed=cfg.SEED):
    rng = np.random.default_rng(seed)
    u = np.unique(days)
    idx = {d: np.flatnonzero(days == d) for d in u}
    for _ in range(n):
        pick = rng.choice(u, len(u), replace=True)
        yield np.concatenate([idx[d] for d in pick])


def size_scores(y, p_model, p_lin, days, n_boot=1000) -> dict:
    y, pm, pl = (np.asarray(a, float) for a in (y, p_model, p_lin))
    mse0, msel, msem = float(np.mean(y ** 2)), float(np.mean((y - pl) ** 2)), float(np.mean((y - pm) ** 2))
    gains = []
    for ix in _boot_days(np.asarray(days), n_boot):
        gains.append(1 - np.mean((y[ix] - pm[ix]) ** 2) / np.mean((y[ix] - pl[ix]) ** 2))
    lo, hi = np.quantile(gains, [0.025, 0.975])
    return dict(n=int(len(y)), sessions=int(len(np.unique(days))), mse_tod=mse0, mse_lin=msel, mse_model=msem,
                gain_vs_tod=1 - msem / mse0, gain_vs_lin=1 - msem / msel, gain_vs_lin_ci=[float(lo), float(hi)],
                lin_gain_vs_tod=1 - msel / mse0)


def dir_scores(up, p, move_pts, days, cost=None, n_boot=1000) -> dict:
    up, p, mv, days = (np.asarray(a) for a in (up, p, move_pts, days))
    ok = np.isfinite(up.astype(float))
    up, p, mv, days = up[ok].astype(int), p[ok], mv[ok].astype(float), days[ok]
    auc = float(roc_auc_score(up, p))
    aucs = []
    for ix in _boot_days(days, n_boot):
        if len(np.unique(up[ix])) == 2:
            aucs.append(roc_auc_score(up[ix], p[ix]))
    hi_t, lo_t = np.quantile(p, [0.9, 0.1])
    long_, short = p >= hi_t, p <= lo_t
    gain = np.r_[mv[long_], -mv[short]]
    return dict(n=int(len(up)), sessions=int(len(np.unique(days))), auc=auc,
                auc_ci=[float(np.quantile(aucs, 0.025)), float(np.quantile(aucs, 0.975))],
                top_decile_calls=int(len(gain)), top_decile_move_pts=float(gain.mean()),
                top_decile_hit=float(np.mean(gain > 0)), cost_pts=cost,
                beats_cost=(None if cost is None else bool(gain.mean() > cost)))


def walk_forward(k: pd.DataFrame, sym: str, tf: int) -> dict:
    X = features(k)
    month = k.day.dt.to_period("M")
    months = sorted(month.unique())
    pred_s, pred_l, pred_p = (np.full(len(k), np.nan) for _ in range(3))
    for m in months[6:]:
        tr, te = (month < m).to_numpy(), (month == m).to_numpy()
        s_ok = tr & k.S.notna().to_numpy()
        reg = HistGradientBoostingRegressor(max_iter=200, learning_rate=0.05, random_state=cfg.SEED)
        reg.fit(X[s_ok], k.S[s_ok])
        pred_s[te] = reg.predict(X[te])
        lin_ok = s_ok & X.F02.notna().to_numpy() & X.F03.notna().to_numpy()
        A = np.column_stack([np.ones(lin_ok.sum()), X.F02[lin_ok], np.log(X.F03[lin_ok].clip(lower=1e-6))])
        b, *_ = np.linalg.lstsq(A, k.S[lin_ok], rcond=None)
        At = np.column_stack([np.ones(te.sum()), X.F02[te], np.log(X.F03[te].clip(lower=1e-6))])
        pred_l[te] = At @ b
        u_ok = tr & k.up.notna().to_numpy()
        clf = HistGradientBoostingClassifier(max_iter=200, learning_rate=0.05, random_state=cfg.SEED)
        clf.fit(X[u_ok], k.up[u_ok].astype(int))
        pred_p[te] = clf.predict_proba(X[te])[:, 1]
    ev = np.isfinite(pred_s) & k.S.notna().to_numpy() & np.isfinite(pred_l)
    evd = np.isfinite(pred_p) & k.up.notna().to_numpy()
    cost = cfg.COST_POINTS.get(sym)
    return dict(sym=sym, tf=tf, months_tested=[str(m) for m in months[6:]],
                size=size_scores(k.S[ev], pred_s[ev], pred_l[ev], k.day[ev].to_numpy()),
                direction=dir_scores(k.up[evd], pred_p[evd], (k.c - k.o)[evd], k.day[evd].to_numpy(), cost))


def design() -> None:
    cfg.RESULTS.mkdir(parents=True, exist_ok=True)
    ev = events()
    cen, slots, conc, tests, cuts, wf = [], [], [], [], {}, []
    for s in cfg.ALL:
        exps = data.detect_expiries(pd.read_parquet(cfg.CACHE / f"{s}_chain.parquet"))
        for tf in ((1, *cfg.TFS) if s in cfg.INDICES else cfg.TFS):
            k = load(s, tf, "design")
            cen.append(census(k, s, tf, exps, ev if s in cfg.INDICES else None))
            slots.append(slot_table(k, s, tf))
            if tf == 1:
                continue
            conc.append(concurrent_ols(k, s, tf))
            rows, used = stats.factor_tests(k, C.CONT, C.CAT)
            for r in rows:
                tests.append(dict(sym=s, tf=tf, **r))
            cuts[f"{s}|{tf}"] = {f: [float(x) for x in q] for f, q in used.items()}
            wf.append(walk_forward(k, s, tf))
            print(s, tf, "done", flush=True)
    t = pd.DataFrame(tests)
    t["q"] = np.nan
    for y in ("S", "D"):
        m = t.target == y
        t.loc[m, "q"] = stats.bh(t.loc[m, "p"])
    t["design_pass"] = t.q < cfg.Q_DESIGN
    pd.DataFrame(cen).to_csv(cfg.RESULTS / "partA_census_design.csv", index=False)
    pd.concat(slots).to_csv(cfg.RESULTS / "partA_slots_design.csv", index=False)
    json.dump(conc, open(cfg.RESULTS / "partB_design.json", "w"), indent=1)
    t.to_csv(cfg.RESULTS / "partC_design.csv", index=False)
    json.dump(cuts, open(cfg.RESULTS / "cuts.json", "w"), indent=1)
    json.dump(wf, open(cfg.RESULTS / "partD_design.json", "w"), indent=1)
    print("tests", len(t), "pass S", int(t[t.target == "S"].design_pass.sum()), "pass D",
          int(t[t.target == "D"].design_pass.sum()))


if __name__ == "__main__":
    {"build": build_all, "design": design}[sys.argv[1]]()
