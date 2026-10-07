"""h18 index-level tests T1-T4 (see PREREG.md).

    OBUY_CACHE=<scratch>/hunt/h18/cache python3 -I research/hunt/h18/analyze.py pre    # choice window (< 2025-10-01)
    OBUY_CACHE=<scratch>/hunt/h18/cache python3 -I research/hunt/h18/analyze.py hold   # holdout confirmation, once
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
from obuy import data as D  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.CACHE, "h18")
HOLD = pd.Timestamp("2025-10-01")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY"]
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def window(df, mode, col="day"):
    return df[df[col] < HOLD] if mode == "pre" else df[df[col] >= HOLD]


# ---------------------------------------------------------------- OLS with day-clustered SE
def ols_cl(y, X, g):
    ok = np.isfinite(y) & np.isfinite(X).all(1)
    y, X, g = y[ok], X[ok], g[ok]
    XtX_inv = np.linalg.pinv(X.T @ X)
    b = XtX_inv @ X.T @ y
    e = y - X @ b
    _, gi = np.unique(g, return_inverse=True)
    S = np.zeros((X.shape[1], X.shape[1]))
    Xe = X * e[:, None]
    G = np.zeros((gi.max() + 1, X.shape[1]))
    np.add.at(G, gi, Xe)
    S = G.T @ G
    V = XtX_inv @ S @ XtX_inv
    ng = gi.max() + 1
    V *= ng / max(ng - 1, 1)
    return b, np.sqrt(np.diag(V)), len(y), ng


def dummies(s, prefix):
    d = pd.get_dummies(s.astype(str), prefix=prefix, drop_first=True, dtype=float)
    return d


# ---------------------------------------------------------------- index matrices
IXC = {}


def index(und):
    if und not in IXC:
        ix = D.Index(und)
        M = ix.mat()
        days = pd.DatetimeIndex([pd.Timestamp(d) for d in ix.days])
        real = np.array([bool(ix.d[d]["real"]) for d in ix.days])
        exp = np.array([bool(ix.d[d]["exp"]) for d in ix.days])
        IXC[und] = dict(ix=ix, M=M, days=days, real=real, exp=exp, pos={d: i for i, d in enumerate(days)})
    return IXC[und]


def ffill_rows(a):
    return pd.DataFrame(a).ffill(axis=1).values


# ================================================================ T1 GEX
def t1_panel(und):
    P = pd.read_parquet(os.path.join(OUT, f"panel_{und}.parquet"))
    I = index(und)
    H, L, Cl = I["M"]["h"], I["M"]["l"], ffill_rows(I["M"]["c"])
    pi = P.day.map(I["pos"]).values.astype(int)
    col = P.col.values.astype(int)
    S = Cl[pi, col]
    out = {}
    for h in (15, 30, 60):
        hi = np.full(len(P), np.nan); lo = np.full(len(P), np.nan); fr = np.full(len(P), np.nan)
        for j in np.unique(col):
            m = col == j
            if j + h >= C.W:
                continue
            hi[m] = np.nanmax(H[pi[m], j + 1:j + h + 1], axis=1)
            lo[m] = np.nanmin(L[pi[m], j + 1:j + h + 1], axis=1)
            fr[m] = Cl[pi[m], j + h] / S[m] - 1
        out[f"rng{h}"] = (hi - lo) / S
        out[f"fret{h}"] = fr
    ph = np.full(len(P), np.nan); pl = np.full(len(P), np.nan); pr = np.full(len(P), np.nan)
    for j in np.unique(col):
        m = col == j
        if j < 30:
            continue
        ph[m] = np.nanmax(H[pi[m], j - 29:j + 1], axis=1)
        pl[m] = np.nanmin(L[pi[m], j - 29:j + 1], axis=1)
        pr[m] = S[m] / Cl[pi[m], j - 30] - 1
    out["prng30"] = (ph - pl) / S
    out["pret30"] = pr
    for k, v in out.items():
        P[k] = v
    # features
    P["A_share"] = P.gexA / P.gtot.replace(0, np.nan)
    P["A_pos"] = (P.gexA > 0).astype(float)
    P = P.sort_values(["col", "day"]).reset_index(drop=True)
    lg = np.log(P.gtot.replace(0, np.nan))
    med = lg.groupby(P.col).transform(lambda s: s.shift(1).rolling(20, min_periods=10).median())
    P["B_lvl"] = lg - med
    P["flip_abs"] = P.flip.abs()
    P["und"] = und
    return P[(P.col <= 315)].reset_index(drop=True)


def t1(mode, panels):
    rows = []
    trend = []
    for und, P in list(panels.items()) + [("ALL", pd.concat(panels.values(), ignore_index=True))]:
        P = window(P, mode)
        P = P[np.isfinite(P.B_lvl) & (P.gtot > 0) & np.isfinite(P.prng30) & (P.prng30 > 0)]
        if P.day.nunique() < 100:
            continue
        g = (P.und + P.day.astype(str)).values
        dte_b = pd.cut(P.dte, [-1, 0, 1, 3, 7, 100], labels=False)
        base = pd.concat([dummies(dte_b, "d"), dummies(P.col // 60, "t")] +
                         ([dummies(P.und, "u")] if und == "ALL" else []), axis=1)
        for h in (15, 30, 60):
            y = np.log(P[f"rng{h}"].clip(lower=1e-6)).values
            for feat in ("A_share", "A_pos", "B_lvl", "flip_abs"):
                z = P[feat].values
                zz = (z - np.nanmean(z)) / np.nanstd(z)
                X = np.column_stack([np.ones(len(P)), zz, np.log(P.prng30.values), np.log(P.vix.values), base.values])
                b, se, n, ng = ols_cl(y, X, g)
                rows.append(dict(und=und, h=h, feat=feat, coef=b[1], t=b[1] / se[1], n=n, days=ng))
        # trend vs reversion: fret30 on pret30 x feature
        for h in (30, 60):
            y = P[f"fret{h}"].values * 1e4
            r = P.pret30.values * 1e4
            for feat in ("A_share", "B_lvl"):
                z = P[feat].values
                zz = (z - np.nanmean(z)) / np.nanstd(z)
                X = np.column_stack([np.ones(len(P)), r, r * zz, zz])
                b, se, n, ng = ols_cl(y, X, g)
                trend.append(dict(und=und, h=h, feat=feat, slope=b[1], t_slope=b[1] / se[1], inter=b[2],
                                  t_inter=b[2] / se[2], n=n))
            # regime split: slope in A>0 vs A<0
            for nm, m in (("A>0", P.gexA.values > 0), ("A<0", P.gexA.values <= 0)):
                X = np.column_stack([np.ones(m.sum()), r[m]])
                b, se, n, ng = ols_cl(y[m], X, g[m])
                trend.append(dict(und=und, h=h, feat=nm, slope=b[1], t_slope=b[1] / se[1], n=n))
    R = pd.DataFrame(rows)
    T = pd.DataFrame(trend)
    print("\n=== T1a: incremental effect of a 1-sd GEX feature on log next-h range (controls: past range, VIX, DTE, "
          "time-of-day[, und]); A predicts coef<0 for A_share/A_pos, B predicts coef>0 for B_lvl ===")
    print(R.pivot_table(index=["feat", "h"], columns="und", values="t").round(1).to_string())
    print(R.pivot_table(index=["feat", "h"], columns="und", values="coef").round(3).to_string())
    print("\n=== T1b: next-h return (bps) on past-30 return (bps): slope, and its change per 1-sd feature ===")
    print(T.round(3).to_string())
    return R, T


def t1_quintiles(mode, panels):
    """Plain-language table: next 30-min range and |return| by A_share quintile, ALL underlyings, raw."""
    P = window(pd.concat(panels.values(), ignore_index=True), mode)
    P = P[np.isfinite(P.A_share) & np.isfinite(P.rng30)]
    P["q"] = P.groupby("und").A_share.transform(lambda s: pd.qcut(s, 5, labels=False, duplicates="drop"))
    P["rr"] = P.rng30 / P.prng30
    tab = P.groupby("q").agg(n=("rng30", "size"), rng30_bps=("rng30", lambda s: 1e4 * s.mean()),
                             ratio_to_past=("rr", "median"), absret30_bps=("fret30", lambda s: 1e4 * s.abs().mean()),
                             share=("A_share", "mean"))
    print("\n=== T1c: by GEX_A share quintile (0 = most negative dealer gamma) ===")
    print(tab.round(3).to_string())


# ================================================================ T2 pinning
def t2(mode, panels):
    rows = []
    for und, P in panels.items():
        P = window(P, mode)
        I = index(und)
        Cl = ffill_rows(I["M"]["c"])
        for col0 in (195, 255):          # 12:30, 13:30
            Q = P[(P.col == col0)].copy()
            pi = Q.day.map(I["pos"]).values.astype(int)
            S0 = Q.spot.values
            close = Cl[pi, C.W - 1]
            for tgt in ("maxoi", "mpain", "maxc", "maxp"):
                K = Q[tgt].values
                dist = K - S0
                okm = np.abs(dist) >= 0.25 * Q.step.values
                drift = np.sign(dist) * (close - S0) / S0 * 1e4
                closer = (np.abs(close - K) < np.abs(S0 - K))
                for ex in (True, False):
                    m = okm & (Q.exp.values == ex)
                    if m.sum() < 20:
                        continue
                    d = drift[m]
                    rows.append(dict(und=und, at=C.hm(C.OPEN_M + col0), target=tgt, expiry=ex, n=int(m.sum()),
                                     drift_bps=d.mean(), t=d.mean() / (d.std(ddof=1) / np.sqrt(len(d))),
                                     p_closer=closer[m].mean(), dist_bps=(np.abs(dist[m]) / S0[m] * 1e4).mean()))
    R = pd.DataFrame(rows)
    print("\n=== T2a: drift TOWARD the OI target from 12:30 / 13:30 to the close (bps of spot; + = toward) ===")
    print(R.round(2).to_string())
    # strike clustering of the close (Ni-Pearson-Poteshman style)
    rows = []
    for und in panels:
        I = index(und)
        Cl = ffill_rows(I["M"]["c"])
        df = pd.DataFrame(dict(day=I["days"], close=Cl[:, -1], exp=I["exp"], real=I["real"]))
        df = window(df[df.real], mode)
        st = C.STEP[und]
        frac = (df.close / st) % 1.0
        dfr = np.minimum(frac, 1 - frac)
        for ex in (True, False):
            x = dfr[df.exp == ex]
            rows.append(dict(und=und, expiry=ex, n=len(x), within10pct_step=(x <= 0.10).mean(),
                             within20pct_step=(x <= 0.20).mean(), mean_dist_step=x.mean()))
    R2 = pd.DataFrame(rows)
    print("\n=== T2b: close within 10% / 20% of a strike step of a strike (uniform = 0.20 / 0.40; mean dist 0.25) ===")
    print(R2.round(3).to_string())
    return R, R2


# ================================================================ T3 expiry-morning push / reversal
def t3(mode):
    rows = []
    per = [("<2023", "2016-01-01", "2023-01-01"), ("2023-01..2025-03 (SEBI window)", "2023-01-01", "2025-04-01"),
           ("2025-04..2025-07-03", "2025-04-01", "2025-07-04"), ("after order", "2025-07-04", "2030-01-01")]
    for und in ("BANKNIFTY", "NIFTY", "FINNIFTY", "SENSEX"):
        I = index(und)
        O, Cl = I["M"]["o"], ffill_rows(I["M"]["c"])
        op = O[:, 0]
        mid = Cl[:, 150]               # 11:45 close
        cl = Cl[:, -1]
        df = pd.DataFrame(dict(day=I["days"], am=(mid / op - 1) * 1e4, pm=(cl / mid - 1) * 1e4, exp=I["exp"],
                               real=I["real"]))
        df = window(df[df.real & np.isfinite(df.am) & np.isfinite(df.pm)], mode)
        for nm, a, b in per:
            for ex in (True, False):
                x = df[(df.day >= a) & (df.day < b) & (df.exp == ex)]
                if len(x) < 15:
                    continue
                big = x[x.am.abs() > 50]
                rows.append(dict(und=und, period=nm, expiry=ex, n=len(x), corr=x.am.corr(x.pm),
                                 n_big=len(big), pm_vs_am_big_bps=(np.sign(big.am) * big.pm).mean() if len(big) else np.nan,
                                 t_big=((np.sign(big.am) * big.pm).mean() / ((np.sign(big.am) * big.pm).std() / np.sqrt(len(big))))
                                 if len(big) > 3 else np.nan))
    R = pd.DataFrame(rows)
    print("\n=== T3: morning (open->11:45) vs afternoon (11:45->close); pm_vs_am_big = afternoon return in the morning's "
          "direction when |morning| > 0.5% (negative = reversal) ===")
    print(R.round(2).to_string())
    return R


# ================================================================ T4 liquidity sweeps
def sweep_events(und, mode, tol=0.0010, back=5, brk=0.0005, cool=30):
    I = index(und)
    H, L, Cl = I["M"]["h"], I["M"]["l"], ffill_rows(I["M"]["c"])
    days = I["days"]
    ev = []
    for i in range(1, len(days)):
        if not (I["real"][i] and I["real"][i - 1]):
            continue
        if mode == "pre" and days[i] >= HOLD or mode == "hold" and days[i] < HOLD:
            continue
        h, l, c = H[i], L[i], Cl[i]
        if np.isnan(h).sum() > 30:
            continue
        hh = pd.Series(h).ffill().values; ll = pd.Series(l).ffill().values
        lv = {"PDH": (1, np.nanmax(H[i - 1])), "PDL": (-1, np.nanmin(L[i - 1])),
              "ORH": (1, np.nanmax(h[:30])), "ORL": (-1, np.nanmin(l[:30]))}
        roll_h = pd.Series(hh).shift(1).rolling(60).max().values
        roll_l = pd.Series(ll).shift(1).rolling(60).min().values
        for name in ("PDH", "PDL", "ORH", "ORL", "R60H", "R60L"):
            side = 1 if name.endswith("H") else -1
            last_s = last_b = -999
            done_s = done_b = False
            for t in range(30, 316):           # 09:45 .. 14:30
                lev = (roll_h[t] if side > 0 else roll_l[t]) if name.startswith("R60") else lv[name][1]
                if not np.isfinite(lev):
                    continue
                ext = side * ((hh[t] if side > 0 else ll[t]) - lev) / lev
                if name.startswith("R60"):
                    can_s, can_b = t - last_s >= cool, t - last_b >= cool
                else:
                    can_s, can_b = not done_s, not done_b
                # break: close beyond by > brk
                if can_b and side * (c[t] - lev) / lev > brk:
                    ev.append(dict(und=und, day=days[i], kind="break", lvl=name, side=side, t=t, lev=lev))
                    last_b = t; done_b = True
                if can_s and 0 < ext <= tol:
                    # close back inside within `back` bars (t..t+back), and never closed beyond by > brk before that
                    for e in range(t, min(t + back + 1, C.W - 61)):
                        if side * (c[e] - lev) / lev > brk:
                            break
                        if side * (c[e] - lev) < 0:
                            ev.append(dict(und=und, day=days[i], kind="sweep", lvl=name, side=-side, t=e, lev=lev))
                            last_s = t; done_s = True
                            break
    E = pd.DataFrame(ev)
    if len(E):
        pi = E.day.map(I["pos"]).values.astype(int)
        t = E.t.values
        for hz in (15, 30, 60):
            tt = np.minimum(t + hz, C.W - 1)
            E[f"f{hz}"] = E.side.values * (Cl[pi, tt] / Cl[pi, t] - 1) * 1e4
    return E


def t4(mode):
    allE = []
    for und in UNDS:
        E = sweep_events(und, mode)
        allE.append(E)
    E = pd.concat(allE, ignore_index=True)
    E.to_parquet(os.path.join(OUT, f"sweeps_{mode}.parquet"))

    def agg(x):
        r = {}
        for hz in (15, 30, 60):
            v = x[f"f{hz}"]
            r[f"f{hz}_bps"] = v.mean()
            r[f"t{hz}"] = v.mean() / (v.std() / np.sqrt(len(v))) if len(v) > 2 else np.nan
        r["n"] = len(x)
        r["win30"] = (x.f30 > 0).mean()
        return pd.Series(r)
    print("\n=== T4: directional index return after a SWEEP (side = reversal) or a BREAK (side = continuation), bps ===")
    print(E.groupby(["kind", "lvl"]).apply(agg, include_groups=False).round(2).to_string())
    print(E.groupby(["kind", "und"]).apply(agg, include_groups=False).round(2).to_string())
    return E


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "pre"
    which = sys.argv[2:] or ["t1", "t2", "t3", "t4"]
    panels = {}
    if "t1" in which or "t2" in which:
        for u in UNDS:
            f = os.path.join(OUT, f"panel_{u}.parquet")
            if os.path.exists(f):
                panels[u] = t1_panel(u)
        pd.concat(panels.values(), ignore_index=True).to_parquet(os.path.join(OUT, "panel_feat.parquet"))
    if "t1" in which:
        t1(mode, panels)
        t1_quintiles(mode, panels)
    if "t2" in which:
        t2(mode, panels)
    if "t3" in which:
        t3(mode)
    if "t4" in which:
        t4(mode)
