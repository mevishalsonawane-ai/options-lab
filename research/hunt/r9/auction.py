"""R9 B: auction regime vs the PRIOR day's value area. Predictive stats + regime filter on the arms.
    python3 -I auction.py design     (hold: through holdout.py only)"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402
import filt9 as F  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy.stats import mannwhitneyu, binomtest  # noqa: E402

SRCS = ["optv", "tpo"]
A0, A1 = R.col(10, 15) - 1, R.col(15, 15) - 1          # close of the 10:14 bar (=10:15) .. 15:15


def day_table(u, src):
    P = R.panel(u)
    rows, st30 = [], {}
    for di in range(1, P["nd"]):
        if not P["ok"][di]:
            continue
        lv = R.levels(P, di, src)
        if lv is None:
            continue
        c, h, l = P["c"][di], P["hh"][di], P["ll"][di]
        o0 = P["o"][di][0] if np.isfinite(P["o"][di][0]) else c[0]
        vah, val = lv["VAH"], lv["VAL"]
        loc = lambda x: 1 if x > vah else (-1 if x < val else 0)  # noqa: E731
        closes = [c[min(30 * k + 29, R.W - 1)] for k in range(13)]
        st = np.array([loc(x) if np.isfinite(x) else 0 for x in closes])
        st30[P["days"][di]] = (loc(o0), st)
        acc = "ACC_UP" if st[0] == 1 and st[1] == 1 else "ACC_DN" if st[0] == -1 and st[1] == -1 else \
            "IN2" if st[0] == 0 and st[1] == 0 else "MIX"
        s0, s1 = c[A0], c[A1]
        hi, lo = np.nanmax(h[A0 + 1:A1 + 1]), np.nanmin(l[A0 + 1:A1 + 1])
        vw = R.vwap_proxy(P, di)
        rows.append(dict(und=u, src=src, day=P["days"][di], des=bool(P["isdes"][di]), hold=bool(P["ishold"][di]),
                         open=loc(o0), acc=acc, TR=abs(s1 - s0) / (hi - lo) if hi > lo else np.nan,
                         X=R.crosses(c, vw, A0 + 1, A1), RV=R.rv(c, A0, A1), ZZ=R.zigzag_n(c, A0, A1),
                         mv=(s1 / s0 - 1) * 100))
    return pd.DataFrame(rows), st30


def predictive(D, per):
    out = []
    for (u, src), g in D.groupby(["und", "src"]):
        g = g[g[per]]
        acc = g[g.acc.isin(["ACC_UP", "ACC_DN"])]
        in2 = g[g.acc == "IN2"]
        oo = g[g.open != 0]
        oi = g[g.open == 0]
        for grp, a, b in (("ACC vs IN2", acc, in2), ("OPEN out vs in", oo, oi)):
            for m, alt in (("TR", "greater"), ("X", "less"), ("ZZ", "less")):
                x, y = a[m].dropna(), b[m].dropna()
                p = mannwhitneyu(x, y, alternative=alt).pvalue if len(x) > 5 and len(y) > 5 else np.nan
                out.append(dict(und=u, src=src, test=f"{grp}: {m} {'higher' if alt == 'greater' else 'lower'}",
                                n_a=len(x), n_b=len(y), mean_a=x.mean(), mean_b=y.mean(), med_a=x.median(), med_b=y.median(),
                                rv_a=a.RV.mean(), rv_b=b.RV.mean(), p=p))
        sgn = np.where(acc.acc == "ACC_UP", 1, -1)
        cont = (np.sign(acc.mv.values) == sgn)
        p = binomtest(int(cont.sum()), len(cont), 0.5, alternative="greater").pvalue if len(cont) > 5 else np.nan
        out.append(dict(und=u, src=src, test="ACC continuation > 50%", n_a=len(cont), n_b=np.nan, mean_a=cont.mean(),
                        mean_b=float(np.mean(acc.mv.values * sgn)), med_a=np.nan, med_b=np.nan, rv_a=np.nan, rv_b=np.nan, p=p))
    return pd.DataFrame(out)


def regime_keep(T, st30, filt):
    """bool keep per trade. st30[day] = (open loc, 13 states). Unknown regime -> kept (filter abstains)."""
    keep = np.ones(len(T), bool)
    for i, (d, em, side) in enumerate(zip(T.day.values, T.entry_min.values, T.side.values)):
        r = st30.get(d)
        if r is None:
            continue
        o, st = r
        ncomp = int((em - (R.C.OPEN_M + 30)) // 30 + 1) if em >= R.C.OPEN_M + 30 else 0   # 30-min bars done by em
        ncomp = min(ncomp, 13)
        if filt == "F1":
            keep[i] = o != 0
        elif filt in ("F2", "F3"):
            if ncomp >= 2 and st[ncomp - 1] != 0 and st[ncomp - 1] == st[ncomp - 2]:
                k, sd = True, st[ncomp - 1]
            elif ncomp >= 2:
                k, sd = False, 0
            else:
                k, sd = o != 0, o
            keep[i] = k and (filt == "F2" or side == sd)
        elif filt == "G1":
            keep[i] = o == 0
        elif filt == "G2":
            keep[i] = (st[ncomp - 1] == 0) if ncomp >= 1 else (o == 0)
    return keep


def filter_plan():
    plan = []
    for arm in F.TREND:
        for f in ("F1", "F2", "F3"):
            plan.append((arm, f, "primary"))
    for arm in F.RANGE:
        for f in ("G1", "G2"):
            plan.append((arm, f, "primary"))
    for arm in ("liq_bn", "liq_fin", "liq_midcp"):
        for f in ("G1", "G2"):
            plan.append((arm, f, "secondary"))
    for f in ("F1", "F2", "F3"):
        plan.append(("orb_sweep", f, "secondary"))
    return plan


def filters(per, ST, T):
    rows, daily = [], {}
    for src in SRCS:
        for arm, f, kind in filter_plan():
            u = F.ARM_UND[arm]
            ses = F.sessions(arm, T, per)
            sset = set(ses)
            t = T[(T.arm == arm) & T.day.isin(sset)].reset_index(drop=True)
            keep = regime_keep(t, ST[(u, src)], f)
            d, dl = F.stats(t, keep, ses)
            name = f"{arm}|{f}|{src}"
            rows.append(dict(filter=name, arm=arm, rule=f, src=src, kind=kind, **d))
            daily[name] = dl
    df = pd.DataFrame(rows)
    allses = sorted({d for s in daily.values() for d in s.index})
    Dm = pd.DataFrame({k: v.reindex(allses).fillna(0.0) for k, v in daily.items()})
    return df, Dm


def main(per):
    D, ST = [], {}
    for u in R.UNDS:
        for src in SRCS:
            d, st = day_table(u, src)
            D.append(d)
            ST[(u, src)] = st
        print(u, "regime days built", flush=True)
    D = pd.concat(D, ignore_index=True)
    D = D[D.des] if per == "design" else D[D.hold]
    D.to_parquet(os.path.join(R.OUT, f"B_days_{per}.parquet"))
    pr = predictive(D, "des" if per == "design" else "hold")
    if per == "design":
        pr["q"] = R.bh(pr.p.values)
    pr.to_csv(os.path.join(R.OUT, f"B_predict_{per}.csv"), index=False)
    T = F.load_arms()
    df, Dm = filters(per, ST, T)
    if per == "design":
        df["q"] = R.bh(df.p.values)
        df["rc_p"] = F.reality_check(Dm).reindex(df["filter"]).values
        df["gate"] = F.gate(df)
    df.to_csv(os.path.join(R.OUT, f"B_filter_{per}.csv"), index=False)
    with pd.option_context("display.width", 250, "display.max_columns", 40):
        print(D[D.des if per == "design" else D.hold].groupby(["und", "src"]).agg(
            n=("day", "size"), open_in=("open", lambda s: (s == 0).mean()), acc=("acc", lambda s: s.isin(["ACC_UP", "ACC_DN"]).mean()),
            in2=("acc", lambda s: (s == "IN2").mean())).round(3))
        print(pr.round(4).to_string(index=False))
        print(df.round(3).to_string(index=False))


if __name__ == "__main__":
    per = sys.argv[1]
    if per == "hold" and not os.environ.get("R9_HOLDOUT_OK"):
        sys.exit("holdout runs only through holdout.py")
    main(per)
