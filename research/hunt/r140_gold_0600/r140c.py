"""R140 Amendment A (PREREG.md, commit f85c7dc): does the market that closed before 06:00 IST guess the direction of
the 06:00-08:00 IST gold window?

Signal source: XAUUSD spot (the underlying). Predictors C1-C6 are moves known by 06:00 IST; each trades the window
(06:00 -> 08:00 IST) in its sign's direction, follow or fade learned walk-forward, with A2's stop and lock.

    python -m research.hunt.r140_gold_0600.r140c design
    python -m research.hunt.r140_gold_0600.r140c holdout     # one read, only for a rule passing every design gate
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd

from research.hunt.r140_gold_0600 import r140 as R

RULES = ("C1", "C2", "C3", "C4", "C5", "C6")
MIN_TRADES = {"C1": 100, "C2": 100, "C3": 100, "C4": 100, "C5": 100, "C6": 50}
BIG_Q = 0.80


def mcx_close_utc(d_ist: date) -> pd.Timestamp:
    """MCX's close on the MCX weekday before IST day d: 23:30 IST (US summer) / 23:55 IST (US winter), as UTC."""
    p = d_ist - timedelta(days=1)
    while p.weekday() >= 5:
        p -= timedelta(days=1)
    summer = R.us_summer(p)
    ist = pd.Timestamp(p) + pd.Timedelta(hours=23, minutes=30 if summer else 55)
    return ist - pd.Timedelta(hours=5, minutes=30)


def predictors(X: R.Xau, wt: pd.DataFrame) -> pd.DataFrame:
    """C1-C5 returns (and C6's big-day U) per window day; every price is known by 06:00 IST (00:30 UTC)."""
    rows = {}
    for d, r in wt.iterrows():
        a = pd.Timestamp(d) + pd.Timedelta(minutes=R.W_START_UTC)
        dd = r.ny_day
        cl = X.close_before(R.ny_to_utc(dd, 17, 0))
        now = X.close_before(a)
        rows[d] = dict(
            C1=cl / X.close_before(R.ny_to_utc(dd, 16, 0)) - 1,
            C2=cl / X.close_before(R.ny_to_utc(dd, 13, 30)) - 1,
            C3=now / cl - 1,
            C4=now / X.close_before(a - pd.Timedelta(minutes=30)) - 1,
            C5=now / X.close_before(mcx_close_utc(d)) - 1,
        )
    p = pd.DataFrame.from_dict(rows, orient="index")
    absu = wt.U.abs()
    thr = absu.expanding().quantile(BIG_Q).shift(1)            # earlier days only
    p["C6"] = np.where(absu >= thr, wt.U, 0.0)
    p.loc[thr.isna(), "C6"] = 0.0
    return p


def run_c(X, b15, wt, pr, period, folds, learn_from=None, train_first=True):
    out = {k: [] for k in RULES}
    lf = learn_from if learn_from is not None else wt
    outc = lf.w_close / lf.w_open - 1
    sigs = {rule: np.sign(pr.loc[lf.index, rule]) for rule in RULES}
    for d, r in wt.iterrows():
        if not R.in_p(d, period):
            continue
        fk = next((i for i, f in enumerate(folds) if R.in_p(d, f)), None)
        if train_first and fk == 0:
            continue
        before = folds[fk][0] if fk is not None else period[0]
        for rule in RULES:
            s = np.sign(pr.at[d, rule])
            if not np.isfinite(s) or s == 0:
                continue
            sg = R.wf_sign(sigs[rule], outc, before)
            ti, to = R.a_times(d, "A2")
            tr = R.xau_trade(X, b15, d, ti, to, int(sg * s))
            if tr:
                tr["t_out_off"] = pd.Timedelta(minutes=R.W_END_UTC)
                out[rule].append(tr)
    return out


def describe(wt, pr, mc):
    cols = {"US day session (A2)": wt.U, "prior day (A3)": wt.P, **{k: pr[k] for k in RULES}}
    names = {"C1": "last US hour 16-17 NY", "C2": "US afternoon 13:30-17 NY", "C3": "US close -> 06:00 IST",
             "C4": "05:30 -> 06:00 IST", "C5": "MCX close -> 06:00 IST", "C6": "big US day (top 20%)"}
    out = []
    for k, v in cols.items():
        row = {"predictor": names.get(k, k)}
        for lab, m in (("all", np.ones(len(wt), bool)), ("summer", wt.summer.values), ("winter", ~wt.summer.values)):
            x, w = v[m], wt.W[m]
            ok = (x != 0) & x.notna() & (w != 0)
            row[lab] = dict(n=int(ok.sum()), same=float((np.sign(x[ok]) == np.sign(w[ok])).mean()),
                            corr=float(x[ok].corr(w[ok])))
        if mc is not None:
            g = []
            for d in mc.days:
                if d in wt.index and 545 in mc.F[d].index and 600 in mc.F[d].index:
                    g.append((v.get(d, np.nan), mc.F[d][600] / mc.F[d][545] - 1))
            g = pd.DataFrame(g, columns=["x", "y"]).dropna()
            g = g[g.x != 0]
            row["goldm_0905_1000"] = dict(n=len(g), corr=float(g.x.corr(g.y)),
                                          same=float((np.sign(g.x) == np.sign(g.y)).mean()))
        out.append(row)
    return out


def main(mode):
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    X, b15, wt = R._load()
    pr = predictors(X, wt)
    rng = np.random.default_rng(R.SEED + 1)
    if mode == "design":
        tr = run_c(X, b15, wt, pr, R.XAU_DESIGN, R.XAU_FOLDS)
        res, p = {}, {}
        for rule, ts in tr.items():
            s = R.summarise(rule, ts, R.XAU_FOLDS, False)
            s["twin_p"], s["twin_median_total"] = R.twins_a(X, b15, wt, R.XAU_DESIGN, ts, rng)
            p[rule] = s["twin_p"]
            res[rule] = s
        prior = json.loads((R.RESULTS / "design.json").read_text())["variants"]
        q = R.bh({**{k: v["twin_p"] for k, v in prior.items()}, **p})       # BH over 18
        for rule, s in res.items():
            s["q"] = q[rule]
            g = R.gates(s, s["twin_p"], q[rule], "A2")
            g["trades"] = s["n"] >= MIN_TRADES[rule]
            g["all"] = all(v for k, v in g.items() if k != "all")
            s["gates"] = g
        mc = R.mc_subset(R._load_mcx(), R.MCX_DESIGN)
        dwt = wt[[R.in_p(d, R.XAU_DESIGN) for d in wt.index]]
        desc = describe(dwt, pr.loc[dwt.index], mc)
        (R.RESULTS / "amendA_design.json").write_text(json.dumps(R._clean(dict(variants=res, describe=desc)), indent=1))
        (R.RESULTS / "amendA_design.md").write_text("# R140 Amendment A design results\n\n" + R.table_md(res) + "\n")
        print(R.table_md(res))
        for row in desc:
            print(row)
    elif mode == "holdout":
        lock = R.RESULTS / "amendA_holdout.lock"
        if lock.exists():
            raise SystemExit("Amendment A holdout already read: " + lock.read_text())
        dz = json.loads((R.RESULTS / "amendA_design.json").read_text())["variants"]
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no Amendment A rule passed the design gates; the holdout is not read")
            return
        dwt = wt[[R.in_p(d, R.XAU_DESIGN) for d in wt.index]]
        tr = run_c(X, b15, wt, pr, R.XAU_HOLD, (R.XAU_HOLD,), learn_from=dwt, train_first=False)
        res = {k: R.summarise(k, tr[k], (R.XAU_HOLD,), False) for k in passing}
        (R.RESULTS / "amendA_holdout.json").write_text(json.dumps(R._clean(res), indent=1))
        print(res)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
