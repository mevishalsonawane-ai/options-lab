"""Level-1 specialists on daily rows (PREREG section 4): L1-D the US open, L1-E scheduled US releases.

L1-D: one row per symbol and session, at the bar that ends at the US cash open (09:30 New York). (i) the log close
range of the next 150 minutes against the mean of the previous 20 sessions; (ii) does the next 150 minutes continue
the day's move so far (sign of the 150-minute return = sign of ret_day)? against the training base rate. Walk-forward
on the same blocks as the minute specialists.

L1-E: one row per release, at the bar that ends at the release minute. (i) log of the next 60 minutes' close range
over the median of the same clock window on the previous 20 sessions without that release kind (is an event hour
bigger?); (ii) does the first 60 minutes follow the 60 minutes before (sign agreement), against 50%.
Groups: EIA crude on CRUDEOIL, EIA gas storage on NATURALGAS, US CPI + NFP pooled on each of the 6 symbols.
"""
from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import roc_auc_score

from research.hunt.r83.common import et_to_ist_min

from . import config, data as D, events, features as F, labels, levels as LV

GROUPS = [("CRUDEOIL", "eia", ("eia",)), ("NATURALGAS", "storage", ("storage",))] + \
    [(s, "us_macro", ("cpi", "nfp")) for s in config.OPTION_SYMBOLS]


def us_open_rows(S) -> pd.DataFrame:
    """The US-open decision row of each design session with its features and targets."""
    m = S.cube
    tod = (m.ts.dt.hour * 60 + m.ts.dt.minute).to_numpy()
    day = m.ts.dt.date.to_numpy()
    uo = pd.Series(sorted(set(day))).map(lambda d: et_to_ist_min(d, 9, 30))
    uo = dict(zip(sorted(set(day)), uo))
    want = np.array([uo[d] - 1 for d in day])
    rows = np.flatnonzero((tod == want) & np.isfinite(m.close.to_numpy(float)) & (day <= config.DESIGN_END))
    y = labels.compute(m[m.ts.dt.date <= config.DESIGN_END], horizons=(150,))
    y = pd.DataFrame({"ts": m.ts}).merge(y, on="ts", how="left")
    d = S.feats.iloc[rows].reset_index(drop=True)
    d["day"] = d.ts.dt.date
    d["y_lr"] = y.y_logrange_150m.to_numpy()[rows]
    r150 = y.y_ret_150m.to_numpy()[rows]
    rd = d.ret_day.to_numpy(float)
    okc = np.isfinite(r150) & np.isfinite(rd) & (r150 != 0) & (rd != 0)
    d["y_cont"] = np.where(okc, (np.sign(r150) == np.sign(rd)).astype(float), np.nan)
    d["b_prev20"] = d.y_lr.shift(1).rolling(20, min_periods=10).mean()
    d["b_prev20"] = d.b_prev20.fillna(d.y_lr.shift(1).expanding().mean())
    return d


def run_session(S, folds_) -> dict:
    d = us_open_rows(S)
    X = F.names()
    rows = []
    for fo in folds_:
        tr = d[d.day < fo["test_lo"]]
        te = d[(d.day >= fo["test_lo"]) & (d.day <= fo["test_hi"])]
        if len(tr) < 40 or not len(te):
            continue
        o = te[["day", "y_lr", "y_cont", "b_prev20"]].copy()
        ok = tr.y_lr.notna()
        o["lr_hgb"] = LV.hgb_reg().fit(tr.loc[ok, X], tr.y_lr[ok]).predict(te[X])
        o["lr_ridge"] = LV.ridge().fit(tr.loc[ok, X].to_numpy(float), tr.y_lr[ok]).predict(te[X].to_numpy(float))
        okc = tr.y_cont.notna()
        yc = tr.y_cont[okc].astype(int)
        o["c_rate"] = yc.mean()
        if 0 < yc.mean() < 1:
            o["c_hgb"] = LV.hgb_clf().fit(tr.loc[okc, X], yc).predict_proba(te[X])[:, 1]
            o["c_lin"] = LV.logit().fit(tr.loc[okc, X].to_numpy(float), yc).predict_proba(te[X].to_numpy(float))[:, 1]
        rows.append(o)
    o = pd.concat(rows, ignore_index=True)
    res = {}
    a = o[o.y_lr.notna() & o.b_prev20.notna()]
    e_m, e_b, e_r = (np.abs(a.y_lr - a[c]) for c in ("lr_hgb", "b_prev20", "lr_ridge"))
    res["size"] = dict(n_days=int(len(a)), mae_hgb=float(e_m.mean()), mae_ridge=float(e_r.mean()),
                       mae_base=float(e_b.mean()), skill=float(1 - e_m.mean() / e_b.mean()),
                       p=D.boot_p((e_b - e_m).to_numpy(), a.day.to_numpy()))
    c = o[o.y_cont.notna() & o.c_hgb.notna()]
    yc = c.y_cont.to_numpy(int)
    llm, llb = D.logloss_rows(yc, c.c_hgb.to_numpy()), D.logloss_rows(yc, c.c_rate.to_numpy())
    res["continue"] = dict(n_days=int(len(c)), rate=float(yc.mean()),
                           auc=float(roc_auc_score(yc, c.c_hgb)) if 0 < yc.mean() < 1 else np.nan,
                           auc_lin=float(roc_auc_score(yc, c.c_lin)) if 0 < yc.mean() < 1 else np.nan,
                           ll_model=float(llm.mean()), ll_base=float(llb.mean()),
                           p=D.boot_p(llb - llm, c.day.to_numpy()))
    return res


def event_rows(S, kinds) -> pd.DataFrame:
    m, f, y = S.cube, S.feats, S.y
    day = m.ts.dt.date.to_numpy()
    tod = (m.ts.dt.hour * 60 + m.ts.dt.minute).to_numpy()
    dsn = sorted(d for d in set(day) if d <= config.DESIGN_END)
    cal = events.calendar(dsn[0], dsn[-1])
    ev = {}
    for k in kinds:
        for d, t in cal[k].items():
            ev.setdefault(d, t)                                 # one release per day (the first listed kind)
    piv = pd.DataFrame({"d": day, "tod": tod, "r": y.y_range_60m.to_numpy()}).pivot_table(
        index="d", columns="tod", values="r", aggfunc="first")
    out = []
    pos = {d: i for i, d in enumerate(piv.index)}
    for d, t in sorted(ev.items()):
        if d not in pos or d > config.DESIGN_END:
            continue
        i = np.flatnonzero((day == d) & (tod == t - 1))
        if not len(i) or (t - 1) not in piv.columns:
            continue
        i = i[0]
        prev = [x for x in piv.index[:pos[d]] if x not in ev][-20:]
        ref = piv.loc[prev, t - 1].dropna() if prev else pd.Series(dtype=float)
        r = y.y_range_60m.iloc[i]
        out.append(dict(day=d, minute=t, range60=r, ref_median=float(ref.median()) if len(ref) >= 10 else np.nan,
                        n_ref=int(len(ref)), ret_next=y.y_ret_60m.iloc[i], ret_before=f.ret_60m.iloc[i]))
    e = pd.DataFrame(out)
    if len(e):
        with np.errstate(divide="ignore", invalid="ignore"):
            e["log_ratio"] = np.log(e.range60 / e.ref_median)
    return e


def run_events(syms: dict) -> dict:
    res = {}
    for s, name, kinds in GROUPS:
        if s not in syms:
            continue
        e = event_rows(syms[s], kinds)
        lr = e.log_ratio.replace([np.inf, -np.inf], np.nan).dropna() if len(e) else pd.Series(dtype=float)
        days = e.loc[lr.index, "day"].to_numpy() if len(lr) else np.array([])
        fol = e[np.isfinite(e.ret_next) & np.isfinite(e.ret_before) & (e.ret_next != 0) & (e.ret_before != 0)] \
            if len(e) else e
        k = int((np.sign(fol.ret_next) == np.sign(fol.ret_before)).sum()) if len(fol) else 0
        res[f"{s}:{name}"] = dict(
            n_events=int(len(lr)), mean_log_ratio=float(lr.mean()) if len(lr) else np.nan,
            multiplier=float(np.exp(lr.median())) if len(lr) else np.nan,
            p_bigger=D.boot_p(lr.to_numpy(), days) if len(lr) else np.nan,
            n_follow=int(len(fol)), follow_rate=float(k / len(fol)) if len(fol) else np.nan,
            p_follow=D.binom_p(k, len(fol), two_sided=True) if len(fol) else np.nan)
    return res
