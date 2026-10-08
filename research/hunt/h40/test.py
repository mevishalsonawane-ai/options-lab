"""h40 tests (PREREG.md). PRE only unless --holdout (run once, at the end).

    python3 -I research/hunt/h40/test.py            # Part A + B + C on PRE -> choice.json, logs
    python3 -I research/hunt/h40/test.py --holdout  # chosen variants on 2025-10-01 .. latest, once
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats as st  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.overfit import bh, spa  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h40")
HOLD = date(2025, 10, 1)
HS = {"NIFTY": .0016, "BANKNIFTY": .0016, "FINNIFTY": .0042, "MIDCPNIFTY": .0021, "SENSEX": .0020}
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
FEATS = ["S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "S11", "S12", "S12L", "S13", "S14",
         "S15"]
DISCRETE = {"S08", "S10"}           # values in {-1, 0, 1}: one threshold only
FDOC = {"S01": "FII idx-fut long ratio z", "S02": "chg FII idx-fut net z", "S03": "chg FII idx-opt net bullish z",
        "S04": "chg Pro idx-fut net z", "S05": "chg Client idx-fut net z (faded)", "S06": "FII idx-fut net buy value z",
        "S07": "stock-fut build-up breadth z", "S08": "own index fut build-up", "S09": "own near-exp put-call OI chg z",
        "S10": "rollover + roll cost (expiry week)", "S11": "heavyweight delivery surge", "S12": "FII cash NSDL z",
        "S12L": "FII cash NSDL z, strict lag", "S13": "FII cash (NSE prov., Mendeley) z",
        "S14": "DII cash (Mendeley) z", "S15": "composite vote"}
TS = ["T1", "T2"]


def load():
    tr = pd.read_parquet(os.path.join(OUT, "opt_trades.parquet"))
    tr["und"] = tr.und.astype(str)
    tr["T"] = tr["T"].astype(str)
    tr["X"] = tr["X"].astype(str)
    hs = tr.und.map(HS).values
    sp = (tr.entry.values + tr.exit.values) * tr.qty.values * hs
    tr["netS"] = tr.net - sp
    tr["net15"] = tr.net - 1.5 * sp
    tr["prem"] = tr.entry * tr.qty
    key = ["und", "day", "T", "X"]
    w = tr.pivot_table(index=key, columns="side", values=["netS", "net15", "gross", "prem"], aggfunc="first",
                       observed=True)
    w.columns = [f"{a}_{'ce' if b == 1 else 'pe'}" for a, b in w.columns]
    w = w.dropna().reset_index()
    w["day"] = pd.to_datetime(w.day).dt.date
    f = pd.read_parquet(os.path.join(OUT, "feat.parquet"))
    f["day"] = pd.to_datetime(f.day).dt.date
    w = w.merge(f, on=["und", "day"], how="left")
    return w, f


def sides(x, thr, sense):
    v = sense * x
    return np.where(v >= thr, 1, np.where(v <= -thr, -1, 0))


def pick(w, feat, thr, sense, U, T, X):
    g = w[(w.und == U) & (w["T"] == T) & (w.X == X)]
    s = sides(g[feat].fillna(0).values, thr, sense)
    m = s != 0
    g, s = g[m], s[m]
    return pd.DataFrame(dict(day=g.day.values, side=s,
                             net=np.where(s > 0, g.netS_ce, g.netS_pe), net15=np.where(s > 0, g.net15_ce, g.net15_pe),
                             gross=np.where(s > 0, g.gross_ce, g.gross_pe), prem=np.where(s > 0, g.prem_ce, g.prem_pe),
                             cf_mu=(g.netS_ce.values + g.netS_pe.values) / 2,
                             cf_sd=np.abs(g.netS_ce.values - g.netS_pe.values) / 2))


def mdd(x):
    eq = np.cumsum(x)
    return float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min()) if len(x) else 0.0


def variants():
    for F in FEATS:
        for thr in ((0.5,) if F in DISCRETE else (0.5, 1.0)):
            for sense in (1, -1):
                for U in UNDS:
                    for T in TS:
                        for X in XS:
                            yield F, thr, sense, U, T, X


XS = ["X1015", "X1115", "X1510", "LIQ"] + [f"P{t}S{s}" for t in (15, 20, 25, 30) for s in (10, 15, 20)]


def vid(F, thr, sense, U, T, X):
    return f"{F}|{thr}|{'fol' if sense > 0 else 'fad'}|{U}|{T}|{X}"


def part_a(f):
    """index-level: raw feature vs index move of day D (open->10:15/11:15/15:09, gap, |open->15:09|), PRE."""
    idd = pd.read_parquet(os.path.join(OUT, "index_days.parquet"))
    idd["day"] = pd.to_datetime(idd.day).dt.date
    idd = idd.sort_values(["und", "day"])
    idd["prev"] = idd.groupby("und").c1509.shift(1)
    idd["gap"] = idd.o0915 / idd.prev - 1
    for h in ("1015", "1115", "1509"):
        idd[f"r{h}"] = idd[f"c{h}"] / idd.o0915 - 1
    idd["abs1509"] = idd.r1509.abs()
    m = f.merge(idd, on=["und", "day"])
    m = m[m.day < HOLD]
    rows = []
    for U in UNDS:
        g = m[m.und == U]
        for F in FEATS:
            for tgt in ("gap", "r1015", "r1115", "r1509", "abs1509"):
                xx = g[F] if tgt != "abs1509" else g[F].abs()
                ok = xx.notna() & g[tgt].notna()
                if ok.sum() < 60:
                    continue
                r, p = st.spearmanr(xx[ok], g[tgt][ok])
                rows.append(dict(und=U, feat=F, target=tgt, n=int(ok.sum()), rho=r, p=p))
    A = pd.DataFrame(rows)
    A["q"] = bh(A.p.values)
    return A, m


def run_pre():
    w, f = load()
    txt = []
    A, m = part_a(f)
    A.to_csv(os.path.join(OUT, "part_a_pre.csv"), index=False)
    pd.set_option("display.width", 250)
    txt.append(f"## A. index level, PRE: {len(A)} tests, BH q<.05: {(A.q < .05).sum()}, raw p<.05: {(A.p < .05).sum()}")
    txt.append(A.pivot_table(index="feat", columns=["target"], values="rho", aggfunc="mean").round(3).to_string())
    txt.append("by index, rho with open->15:09:\n" + A[A.target == "r1509"].pivot(index="feat", columns="und",
                                                                                     values="rho").round(3).to_string())
    txt.append("smallest q:\n" + A.sort_values("p").head(20).round(4).to_string())
    W = w[w.day < HOLD]
    sess = {u: W[W.und == u].day.nunique() for u in UNDS}
    # coin-flip hurdle
    c = W.assign(cf=(W.netS_ce + W.netS_pe) / 2, pr=(W.prem_ce + W.prem_pe) / 2)
    B = c.groupby(["und", "T", "X"]).agg(n=("cf", "size"), coinflip=("cf", "mean"), prem=("pr", "median")).round(0)
    B.to_csv(os.path.join(OUT, "hurdle_pre.csv"))
    txt.append("## coin-flip net Rs/trade (mean of CE and PE), PRE:\n" +
               B.reset_index().pivot_table(index="X", columns=["und", "T"], values="coinflip").round(0).to_string())
    days = np.array(sorted(W.day.unique()))
    dpos = {d: i for i, d in enumerate(days)}
    res, cols = [], []
    for F, thr, sense, U, T, X in variants():
        t = pick(W, F, thr, sense, U, T, X)
        n = len(t)
        r = dict(vid=vid(F, thr, sense, U, T, X), F=F, thr=thr, sense=sense, und=U, T=T, X=X, n=n)
        col = np.zeros(len(days))
        if n:
            np.add.at(col, [dpos[d] for d in t.day], t.net.values)
            yrs = pd.Series(t.net.values, index=[d.year for d in t.day])
            cnt = yrs.groupby(level=0).size()
            yall = yrs.groupby(level=0).sum()
            yn = yall[cnt >= 10]
            sd = t.net.std(ddof=1) if n > 1 else np.nan
            cf_sd = np.sqrt((t.cf_sd ** 2).sum())
            r.update(net=t.net.sum(), net15=t.net15.sum(), gross=t.gross.sum(), per_trade=t.net.mean(),
                     per_day=t.net.sum() / sess[U], gross_day=t.gross.sum() / sess[U], hit=(t.net > 0).mean(),
                     mdd=mdd(t.net.values), worst=t.net.min(), prem_med=t.prem.median(), cf=t.cf_mu.sum(),
                     p_t=st.t.sf(t.net.mean() / (sd / np.sqrt(n)), n - 1) if n > 2 and sd > 0 else 1.0,
                     p_cf=st.norm.sf((t.net.sum() - t.cf_mu.sum()) / cf_sd) if cf_sd > 0 else 1.0,
                     yrs_pos=f"{(yn > 0).sum()}/{len(yn)}", yrs_frac=(yn > 0).mean() if len(yn) else 0,
                     **{f"y{y}": v for y, v in yall.items()})
        else:
            r.update(net=0, net15=0, gross=0, p_t=1.0, p_cf=1.0, yrs_frac=0)
        res.append(r)
        cols.append(col)
    V = pd.DataFrame(res)
    V["q_t"] = bh(V.p_t.values)
    V["q_cf"] = bh(V.p_cf.values)
    X_ = np.array(cols).T
    sp = spa(X_, B=1000)
    V.to_csv(os.path.join(OUT, "variants_pre.csv"), index=False)
    v60 = V[V.n >= 60]
    txt.append(f"## B. variants: {len(V)} declared, {int((V.n > 0).sum())} with trades, {len(v60)} with >= 60")
    txt.append(f"share net>0 (n>=60): {(v60.net > 0).mean():.2f}; median Rs/trade {v60.per_trade.median():.0f}")
    txt.append(f"SPA/RC vs zero over {X_.shape[1]} variants x {X_.shape[0]} days: {sp}")
    txt.append(f"best q_t {V.q_t.min():.3f}; best q_cf {V.q_cf.min():.3f}; n(q_cf<.05) {(V.q_cf < .05).sum()}; "
               f"n(q_t<.05) {(V.q_t < .05).sum()}")
    show = ["vid", "n", "gross", "net", "net15", "per_trade", "per_day", "hit", "mdd", "worst", "prem_med", "yrs_pos",
            "cf", "p_t", "q_t", "p_cf", "q_cf"]
    txt.append("top 25 by PRE net (n>=60):\n" + v60.sort_values("net", ascending=False)[show].head(25).round(3).to_string())
    txt.append("top 15 by coin-flip p (n>=60):\n" + v60.sort_values("p_cf")[show].head(15).round(4).to_string())
    g = v60.groupby(["F", "sense"]).agg(variants=("vid", "size"), pos=("net", lambda x: (x > 0).mean()),
                                        med_pt=("per_trade", "median"), beat_cf=("p_cf", lambda x: (x < .5).mean()),
                                        best=("net", "max"), min_qcf=("q_cf", "min"), min_qt=("q_t", "min"))
    g["doc"] = [FDOC[a] for a, b in g.index]
    txt.append("by feature x sense:\n" + g.round(3).to_string())
    gx = v60.groupby("X").agg(pos=("net", lambda x: (x > 0).mean()), med_pt=("per_trade", "median"))
    txt.append("by exit:\n" + gx.round(3).to_string())
    # walk-forward (anchored): best total net on all earlier years with >= 40 trades there
    wf = []
    ycols = sorted(c for c in V.columns if c.startswith("y") and c[1:].isdigit())
    for Y in (2022, 2023, 2024, 2025):
        tr_cols = [c for c in ycols if int(c[1:]) < Y]
        tot = V[tr_cols].fillna(0).sum(axis=1)
        for i in tot.sort_values(ascending=False).index[:300]:
            v = V.loc[i]
            t = pick(W, v.F, v.thr, v.sense, v.und, v["T"], v.X)
            if sum(1 for d in t.day if d.year < Y) >= 40:
                ty = t[[d.year == Y for d in t.day]]
                wf.append(dict(year=Y, pick=v.vid, train_net=float(tot.loc[i]), trades=len(ty), net=ty.net.sum(),
                               net15=ty.net15.sum(), gross=ty.gross.sum(), cf=ty.cf_mu.sum()))
                break
    WF = pd.DataFrame(wf)
    WF.to_csv(os.path.join(OUT, "wf.csv"), index=False)
    txt.append("walk-forward:\n" + WF.round(0).to_string() + f"\nWF total net {WF.net.sum():.0f}, "
               f"net15 {WF.net15.sum():.0f}, gross {WF.gross.sum():.0f}, coin-flip {WF.cf.sum():.0f}")
    prom = V[(V.n >= 60) & (V.net > 0) & (V.net15 > 0) & (V.q_cf < .05) & (V.q_t < .05) & (V.yrs_frac >= .6)]
    best = v60.sort_values("net", ascending=False).iloc[0].vid
    choice = dict(promoted=list(prom.sort_values("net", ascending=False).vid.head(3)), best_pre=best,
                  wf_2025=WF.iloc[-1].pick if len(WF) else None, spa=sp, n_variants=len(V))
    txt.append(f"promoted: {choice['promoted']}  best PRE: {best}  WF 2025 pick: {choice['wf_2025']}")
    json.dump(choice, open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=str)
    open(os.path.join(OUT, "test_pre.log"), "w").write("\n\n".join(txt))
    print("\n\n".join(txt))


def monthly_stats(t, B=2000, seed=7):
    if not len(t):
        return {}
    s = pd.Series(t.net.values, index=pd.to_datetime(t.day))
    mo = s.groupby(s.index.to_period("M")).sum()
    rng = np.random.default_rng(seed)
    days = s.groupby(level=0).sum()
    per_m = max(1, int(round(len(days) / max(len(mo), 1))))
    boot = np.array([rng.choice(days.values, per_m).sum() for _ in range(B)])
    return dict(months=len(mo), losing_months=int((mo < 0).sum()), worst_month=float(mo.min()),
                worst_day=float(days.min()), p_losing_month=float((boot < 0).mean()))


def run_holdout():
    choice = json.load(open(os.path.join(OUT, "choice.json")))
    w, f = load()
    H = w[w.day >= HOLD]
    P = w[w.day < HOLD]
    sessH = {u: H[H.und == u].day.nunique() for u in UNDS}
    sessP = {u: P[P.und == u].day.nunique() for u in UNDS}
    vids = choice["promoted"] or [choice["best_pre"], choice["wf_2025"]]
    out = []
    for v in dict.fromkeys(vids):
        F, thr, sn, U, T, X = v.split("|")
        sense = 1 if sn == "fol" else -1
        for per, D, ss in (("PRE", P, sessP), ("HOLD", H, sessH)):
            t = pick(D, F, float(thr), sense, U, T, X)
            out.append(dict(vid=v, period=per, n=len(t), gross=t.gross.sum(), net=t.net.sum(), net15=t.net15.sum(),
                            gross_day=t.gross.sum() / ss[U], net_day=t.net.sum() / ss[U],
                            hit=(t.net > 0).mean() if len(t) else np.nan, mdd=mdd(t.net.values),
                            coinflip=t.cf_mu.sum(), prem_med=t.prem.median(),
                            p_cf=st.norm.sf((t.net.sum() - t.cf_mu.sum()) / np.sqrt((t.cf_sd ** 2).sum()))
                            if len(t) else np.nan, sessions=ss[U], **monthly_stats(t)))
    O = pd.DataFrame(out)
    O["lots_5k"] = np.where(O.net_day > 0, 5000 / O.net_day, np.nan)
    O["lots_in_1L"] = 100000 / O.prem_med
    O.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
    pd.set_option("display.width", 250)
    s = O.round(2).T.to_string()
    # info only (chosen after PRE, disclosed): the FII-flow 'follow' families that beat the coin-flip side in PRE
    fam = []
    for F in ("S02", "S05", "S06", "S12", "S12L", "S01"):
        for thr in (0.5, 1.0):
            for U in UNDS:
                for T in TS:
                    for X in XS:
                        for per, D in (("PRE", P), ("HOLD", H)):
                            t = pick(D, F, thr, 1, U, T, X)
                            if len(t) >= 10:
                                fam.append(dict(F=F, thr=thr, und=U, T=T, X=X, per=per, n=len(t), net=t.net.sum(),
                                                gross=t.gross.sum(), cf=t.cf_mu.sum()))
    Fm = pd.DataFrame(fam)
    Fm.to_csv(os.path.join(OUT, "holdout_families.csv"), index=False)
    g = Fm.groupby(["F", "per"]).apply(lambda x: pd.Series(dict(
        variants=len(x), net_pos=(x.net > 0).mean(), beat_cf=(x.net > x.cf).mean(), med_net=x.net.median(),
        sum_net=x.net.sum(), sum_cf=x.cf.sum())))
    s += "\n\nFII-flow follow families (info only):\n" + g.round(3).to_string()
    s += "\n\nby F x und, HOLD (sum net / sum coin-flip over T x X x thr):\n" + \
        Fm[Fm.per == "HOLD"].groupby(["F", "und"])[["n", "net", "cf"]].sum().round(0).to_string()
    open(os.path.join(OUT, "holdout.log"), "w").write(s)
    print(s)


if __name__ == "__main__":
    run_holdout() if "--holdout" in sys.argv else run_pre()
