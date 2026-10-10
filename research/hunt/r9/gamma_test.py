"""R9 C: does the prior evening's dealer gamma (bhavcopy) predict the next session's character, and does it help the arms?
    python3 -I gamma_test.py design      (hold: through holdout.py only)"""
from __future__ import annotations

import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402
import filt9 as F  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.stats import mannwhitneyu, norm  # noqa: E402

E1 = R.col(15, 25)
OUTC = ["TR", "X", "RV", "RANGE"]


def session_table(u):
    P = R.panel(u)
    g = pd.read_parquet(os.path.join(R.OUT, "gex.parquet"))
    g = g[g.und == u].copy()
    g["day"] = g.day.dt.date
    days = P["days"]
    vixm, vclose = R.L.vix_minutes(days)
    rows = []
    gi = g.set_index("day")
    prev_rv = np.nan
    for di in range(1, P["nd"]):
        d, t = days[di], days[di - 1]
        c, h, l = P["c"][di], P["hh"][di], P["ll"][di]
        o0 = P["o"][di][0] if np.isfinite(P["o"][di][0]) else c[0]
        rv_prev = R.rv(P["c"][di - 1], 0, E1) if P["ok"][di - 1] else np.nan
        if not P["ok"][di] or t not in gi.index:
            continue
        r = gi.loc[t]
        hi, lo = np.nanmax(h[:E1 + 1]), np.nanmin(l[:E1 + 1])
        vw = R.vwap_proxy(P, di)
        dte_d = r.dte - (d - t).days
        rows.append(dict(und=u, day=d, des=bool(P["isdes"][di]), hold=bool(P["ishold"][di]),
                         TR=abs(c[E1] - o0) / (hi - lo) if hi > lo else np.nan, X=R.crosses(c, vw, 0, E1),
                         RV=R.rv(c, 0, E1), RANGE=(hi - lo) / o0 * 100, rv_prev=rv_prev, vix_prev=vclose[di - 1],
                         inv_dte=1.0 / (1 + max(dte_d, 0)), gexA=r.gexA, ratioA=r.ratioA, flipdist=r.flipdist,
                         gexC=r.get("gexC", np.nan), gexD=r.get("gexD", np.nan)))
    return pd.DataFrame(rows)


def nw_ols(y, X, lags=5):
    X = np.column_stack([np.ones(len(y)), X])
    b = np.linalg.lstsq(X, y, rcond=None)[0]
    e = y - X @ b
    n = len(y)
    XtXi = np.linalg.inv(X.T @ X)
    S = (X * e[:, None]).T @ (X * e[:, None])
    for k in range(1, lags + 1):
        w = 1 - k / (lags + 1)
        G = (X[k:] * e[k:, None]).T @ (X[:-k] * e[:-k, None])
        S += w * (G + G.T)
    V = XtXi @ S @ XtXi
    return b, np.sqrt(np.diag(V))


def predictive(D, per):
    out = []
    for u in ("NIFTY", "BANKNIFTY"):
        g = D[(D.und == u) & D[per]]
        for m in OUTC:
            for nm, col in (("A sign (GEX_A>0 vs <0; B = reverse)", "gexA"), ("flip: spot above vs below zero-gamma", "flipdist"),
                            ("C customer-net sign", "gexC"), ("D Pro-dealer sign", "gexD")):
                a = g[g[col] > 0][m].dropna()
                b = g[g[col] < 0][m].dropna()
                p = mannwhitneyu(a, b, alternative="two-sided").pvalue if len(a) > 5 and len(b) > 5 else np.nan
                out.append(dict(und=u, outcome=m, test=nm, n_pos=len(a), n_neg=len(b), mean_pos=a.mean(), mean_neg=b.mean(), p=p))
            h = g[[m, "ratioA", "rv_prev", "vix_prev", "inv_dte"]].replace([np.inf, -np.inf], np.nan).dropna()
            y = h[m].values.astype(float)
            X = np.column_stack([h.ratioA.values, np.log(h.rv_prev.values + 1e-6), np.log(h.vix_prev.values), h.inv_dte.values])
            b, se = nw_ols(y, X)
            t = b[1] / se[1]
            out.append(dict(und=u, outcome=m, test="A ratio, partial (controls: prior RV, VIX, 1/(1+DTE)); coef per 0.1",
                            n_pos=len(h), n_neg=np.nan, mean_pos=b[1] * 0.1, mean_neg=t, p=2 * (1 - norm.cdf(abs(t)))))
    return pd.DataFrame(out)


def keep_rule(t, gx, rule, trend):
    keep = np.ones(len(t), bool)
    for i, d in enumerate(t.day.values):
        if d not in gx.index:
            continue
        r = gx.loc[d]
        if rule == "A":
            k = r.gexA < 0
        elif rule == "B":
            k = r.gexA > 0
        elif rule == "C":
            k = r.gexC < 0
        else:
            k = r.gexD < 0
        if pd.isna(k) or (rule in ("C", "D") and not np.isfinite(r["gex" + rule])):
            continue
        keep[i] = bool(k) if trend else not bool(k)
    return keep


def filters(per, D, T):
    rows, daily = [], {}
    for arm in F.TREND + F.RANGE:
        u = F.ARM_UND[arm]
        gx = D[D.und == u].set_index("day")
        ses = F.sessions(arm, T, per)
        t = T[(T.arm == arm) & T.day.isin(set(ses))].reset_index(drop=True)
        for rule in "ABCD":
            keep = keep_rule(t, gx, rule, arm in F.TREND)
            d, dl = F.stats(t, keep, ses)
            name = f"{arm}|gamma_{rule}"
            rows.append(dict(filter=name, arm=arm, rule=rule, **d))
            daily[name] = dl
    df = pd.DataFrame(rows)
    allses = sorted({d for s in daily.values() for d in s.index})
    Dm = pd.DataFrame({k: v.reindex(allses).fillna(0.0) for k, v in daily.items()})
    return df, Dm


def main(per):
    D = pd.concat([session_table(u) for u in R.UNDS], ignore_index=True)
    pk = "des" if per == "design" else "hold"
    D = D[D[pk]]
    D.to_parquet(os.path.join(R.OUT, f"C_days_{per}.parquet"))
    pr = predictive(D, pk)
    if per == "design":
        pr["q"] = R.bh(pr.p.values)
    pr.to_csv(os.path.join(R.OUT, f"C_predict_{per}.csv"), index=False)
    T = F.load_arms()
    df, Dm = filters(per, D, T)
    if per == "design":
        df["q"] = R.bh(df.p.values)
        df["rc_p"] = F.reality_check(Dm).reindex(df["filter"]).values
        df["gate"] = F.gate(df)
    df.to_csv(os.path.join(R.OUT, f"C_filter_{per}.csv"), index=False)
    with pd.option_context("display.width", 250, "display.max_columns", 40):
        print(D.groupby("und").agg(n=("day", "size"), posA=("gexA", lambda s: (s > 0).mean()),
                                   posC=("gexC", lambda s: (s > 0).mean()), posD=("gexD", lambda s: (s > 0).mean())).round(3))
        print(pr.round(4).to_string(index=False))
        print(df.round(3).to_string(index=False))


if __name__ == "__main__":
    per = sys.argv[1]
    if per == "hold" and not os.environ.get("R9_HOLDOUT_OK"):
        sys.exit("holdout runs only through holdout.py")
    main(per)
