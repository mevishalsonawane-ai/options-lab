"""h31 tests (PREREG.md). PRE only unless --holdout (run once, at the end).

    python3 -I research/hunt/h31/test.py            # PRE: descriptive, variants, BH, SPA, walk-forward -> choice.json
    python3 -I research/hunt/h31/test.py --holdout  # the chosen variants on 2025-10-01 .. latest, once
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

OUT = os.path.join(C.SCRATCH, "hunt", "h31")
HOLD = date(2025, 10, 1)
HS = {"NIFTY": .0016, "BANKNIFTY": .0016, "FINNIFTY": .0042, "MIDCPNIFTY": .0021, "SENSEX": .0020}
RULES = [f"R{i:02d}" for i in range(1, 18)]
FILTS = ["F0", "F1", "F2"]
KS = ["M", "W"]
XS = ["X0916", "X0930", "X1015", "GX", "P15", "P20", "P25", "P30"]
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
RDOC = {"R01": "CE always", "R02": "PE always", "R03": "close-loc follow", "R04": "close-loc CE only (strong close)",
        "R05": "close-loc fade", "R06": "last-hour follow", "R07": "last-hour fade", "R08": "day follow",
        "R09": "day fade", "R10": "breadth follow", "R11": "VIX change", "R12": "VIX low CE", "R13": "ES follow",
        "R14": "FII OI follow", "R15": "event night CE", "R16": "FOMC night CE", "R17": "all agree"}


def rule_sides(f):
    sg = lambda c, th: np.where(f[c] >= th, 1, np.where(f[c] <= -th, -1, 0))  # noqa: E731
    one = np.ones(len(f), int)
    loc = f["loc"].values
    s = {"R01": one, "R02": -one,
         "R03": np.where(loc >= .75, 1, np.where(loc <= .25, -1, 0)),
         "R04": np.where(loc >= .75, 1, 0)}
    s["R05"] = -s["R03"]
    s["R06"] = sg("LH", .001)
    s["R07"] = -s["R06"]
    s["R08"] = sg("DAY", .003)
    s["R09"] = -s["R08"]
    br = f["BR"].fillna(.5).values
    s["R10"] = np.where(br >= .6, 1, np.where(br <= .4, -1, 0))
    vc = f["VIXCH"].fillna(0).values
    s["R11"] = np.where(vc <= -.03, 1, np.where(vc >= .03, -1, 0))
    s["R12"] = np.where(f["VIXLOW"].fillna(False).astype(bool), 1, 0)
    es = f["ES"].fillna(0).values
    s["R13"] = np.where(es >= .002, 1, np.where(es <= -.002, -1, 0))
    fz = f["FIIZ"].fillna(0).values
    s["R14"] = np.where(fz >= .5, 1, np.where(fz <= -.5, -1, 0))
    s["R15"] = np.where(f.fomc | f.uscpi, 1, 0)
    s["R16"] = np.where(f.fomc, 1, 0)
    day = f["DAY"].values
    brr = f["BR"].values
    s["R17"] = np.where((loc >= .6) & (day >= 0) & (brr >= .5), 1,
                        np.where((loc <= .4) & (day <= 0) & (brr <= .5), -1, 0))
    return pd.DataFrame(s, index=f.index)


def load():
    f = pd.read_parquet(os.path.join(OUT, "feat.parquet"))
    tr = pd.read_parquet(os.path.join(OUT, "trades.parquet"))
    hs = tr.und.map(HS).values
    sp = (tr.entry.values + tr.exit.values) * tr.qty.values * hs
    tr["net"] = tr.net_app - sp
    tr["net15"] = tr.net_app - 1.5 * sp
    tr["prem"] = tr.efill * tr.qty
    key = ["und", "day", "K", "X"]
    w = tr.pivot_table(index=key, columns="side", values=["net", "net15", "gross", "prem"], aggfunc="first")
    w.columns = [f"{a}_{'ce' if b == 1 else 'pe'}" for a, b in w.columns]
    w = w.dropna().reset_index()
    dte = tr[tr.side == 1].set_index(key).dte
    w = w.join(dte, on=key)
    S = rule_sides(f)
    S["und"], S["day"] = f.und.values, f.day.values
    S["nights"] = f.nights.values
    w = w.merge(S, on=["und", "day"], how="left")
    return f, w


def pick(w, R, F, K, X, U):
    m = (w.und == U) & (w.K == K) & (w.X == X) & (w[R] != 0)
    if F != "F0":
        m &= w.nights == 1
    if F == "F2":
        m &= w.dte >= 7
    g = w[m]
    s = g[R].values
    out = pd.DataFrame(dict(day=g.day.values, side=s,
                            net=np.where(s > 0, g.net_ce, g.net_pe), net15=np.where(s > 0, g.net15_ce, g.net15_pe),
                            gross=np.where(s > 0, g.gross_ce, g.gross_pe), prem=np.where(s > 0, g.prem_ce, g.prem_pe),
                            cf_mu=(g.net_ce.values + g.net_pe.values) / 2,
                            cf_sd=np.abs(g.net_ce.values - g.net_pe.values) / 2))
    return out


def mdd(x):
    eq = np.cumsum(x)
    return float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min()) if len(x) else 0.0


def describe(f, w, sess):
    lines = []
    P = f[(f.day < HOLD) & f.real]
    lines.append("## A. Index overnight move (15:19 close -> next 09:15 open), PRE, % mean (t)")
    rows = []
    conds = {"all": P.und == P.und, "loc>=.75": P["loc"] >= .75, "loc<=.25": P["loc"] <= .25,
             "DAY>=+.3%": P.DAY >= .003, "DAY<=-.3%": P.DAY <= -.003, "LH>=+.1%": P.LH >= .001,
             "LH<=-.1%": P.LH <= -.001, "BR>=.6": P.BR >= .6, "BR<=.4": P.BR <= .4, "VIXCH<=-3%": P.VIXCH <= -.03,
             "VIXCH>=+3%": P.VIXCH >= .03, "FOMC night": P.fomc, "CPI night": P.uscpi, "weekend/holiday": P.nights > 1,
             "Friday": P.wd == 4, "ES>=+.2%": P.ES >= .002, "ES<=-.2%": P.ES <= -.002, "FIIZ>=.5": P.FIIZ >= .5,
             "FIIZ<=-.5": P.FIIZ <= -.5}
    for u in UNDS:
        Q = P[P.und == u]
        for cn, cm in conds.items():
            x = Q.ov[cm.loc[Q.index]].dropna() * 100
            x10 = Q.ov1015[cm.loc[Q.index]].dropna() * 100
            if len(x) < 5:
                continue
            rows.append(dict(und=u, cond=cn, n=len(x), ov=round(x.mean(), 3), t=round(x.mean() / (x.std() / np.sqrt(len(x))), 2),
                             to1015=round(x10.mean(), 3), absov=round(x.abs().mean(), 3)))
    A = pd.DataFrame(rows)
    A.to_csv(os.path.join(OUT, "index_overnight_pre.csv"), index=False)
    lines.append(A.pivot(index="cond", columns="und", values="ov").round(3).to_string())
    lines.append("t-stats:\n" + A.pivot(index="cond", columns="und", values="t").to_string())
    lines.append("mean |overnight| %:\n" + A.pivot(index="cond", columns="und", values="absov").to_string())
    lines.append("\n## B. Cost of carrying a 1-ITM option overnight (coin-flip mean = (CE+PE)/2 net, real spread), PRE, Rs/trade")
    W = w[w.day < HOLD]
    c = W.assign(cf=(W.net_ce + W.net_pe) / 2, gce=W.gross_ce, gpe=W.gross_pe, nce=W.net_ce, npe=W.net_pe,
                 pr=(W.prem_ce + W.prem_pe) / 2)
    B = c.groupby(["und", "K", "X"]).agg(n=("cf", "size"), coinflip=("cf", "mean"), CE_gross=("gce", "mean"),
                                         PE_gross=("gpe", "mean"), CE_net=("nce", "mean"), PE_net=("npe", "mean"),
                                         prem=("pr", "median")).round(0)
    B.to_csv(os.path.join(OUT, "carry_pre.csv"))
    lines.append(B.to_string())
    return "\n".join(lines)


def run_pre():
    f, w = load()
    sess = {u: w[(w.und == u) & (w.day < HOLD)].day.nunique() for u in UNDS}
    W = w[w.day < HOLD]
    txt = [describe(f, w, sess)]
    allpre_days = np.array(sorted(W.day.unique()))
    dpos = {d: i for i, d in enumerate(allpre_days)}
    res, cols = [], []
    for U in UNDS:
        for K in KS:
            for X in XS:
                for R in RULES:
                    for F in FILTS:
                        t = pick(W, R, F, K, X, U)
                        vid = f"{U}|{K}|{X}|{R}|{F}"
                        n = len(t)
                        r = dict(vid=vid, und=U, K=K, X=X, R=R, F=F, n=n)
                        col = np.zeros(len(allpre_days))
                        if n:
                            np.add.at(col, [dpos[d] for d in t.day], t.net.values)
                            yrs = pd.Series(t.net.values, index=[d.year for d in t.day])
                            cnt = yrs.groupby(level=0).size()
                            yall = yrs.groupby(level=0).sum()
                            yn = yall[cnt >= 10]
                            sd = t.net.std(ddof=1) if n > 1 else np.nan
                            cf_sd = np.sqrt((t.cf_sd ** 2).sum())
                            r.update(net=t.net.sum(), net15=t.net15.sum(), gross=t.gross.sum(), per_trade=t.net.mean(),
                                     per_day=t.net.sum() / sess[U], hit=(t.net > 0).mean(), mdd=mdd(t.net.values),
                                     worst=t.net.min(), prem_med=t.prem.median(),
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
    X = np.array(cols).T
    sp = spa(X, B=1000)
    V.to_csv(os.path.join(OUT, "variants_pre.csv"), index=False)
    txt.append(f"\n## C. Variants: {len(V)} declared, {int((V.n > 0).sum())} with trades, {int((V.n >= 20).sum())} with >= 20")
    txt.append(f"share with net > 0: {(V[V.n >= 20].net > 0).mean():.2f}; median Rs/trade {V[V.n >= 20].per_trade.median():.0f}")
    txt.append(f"SPA/RC over {X.shape[1]} variants x {X.shape[0]} days: {sp}")
    txt.append(f"best q_t {V.q_t.min():.3f}; best q_cf {V.q_cf.min():.3f}; n(q_cf<.05) {(V.q_cf < .05).sum()}; n(q_t<.05) {(V.q_t < .05).sum()}")
    show = ["vid", "n", "gross", "net", "net15", "per_trade", "per_day", "hit", "mdd", "worst", "prem_med", "yrs_pos",
            "p_t", "q_t", "p_cf", "q_cf"]
    pd.set_option("display.width", 250)
    txt.append("top 25 by PRE net:\n" + V[V.n >= 20].sort_values("net", ascending=False)[show].head(25).round(3).to_string())
    txt.append("top 15 by coin-flip p:\n" + V[V.n >= 20].sort_values("p_cf")[show].head(15).round(4).to_string())
    # rule-level summary
    g = V[V.n >= 20].groupby("R").agg(variants=("vid", "size"), pos=("net", lambda x: (x > 0).mean()),
                                      med_pt=("per_trade", "median"), best=("net", "max"), min_qcf=("q_cf", "min"))
    g["doc"] = g.index.map(RDOC)
    txt.append("by rule:\n" + g.round(3).to_string())
    gx = V[V.n >= 20].groupby("X").agg(pos=("net", lambda x: (x > 0).mean()), med_pt=("per_trade", "median"))
    txt.append("by exit:\n" + gx.round(3).to_string())
    # unconditional call
    txt.append("unconditional CE (R01, F0):\n" + V[(V.R == "R01") & (V.F == "F0")][show].round(3).to_string())
    # walk-forward
    wf = []
    for Y in (2022, 2023, 2024, 2025):
        ycols = [c for c in V.columns if c.startswith("y") and c[1:].isdigit() and int(c[1:]) < Y]
        tot = V[ycols].fillna(0).sum(axis=1)
        # min 20 training trades: approximate by n * share of earlier years -> recompute exactly
        best = None
        order = tot.sort_values(ascending=False).index
        for i in order[:200]:
            v = V.loc[i]
            U, K, Xx, R, F = v.vid.split("|")
            t = pick(W, R, F, K, Xx, U)
            ntr = sum(1 for d in t.day if d.year < Y)
            if ntr >= 20:
                best = (v.vid, t, float(tot.loc[i]))
                break
        if best is None:
            continue
        vid, t, trn = best
        ty = t[[d.year == Y for d in t.day]]
        wf.append(dict(year=Y, pick=vid, train_net=trn, trades=len(ty), net=ty.net.sum(),
                       net15=ty.net15.sum(), gross=ty.gross.sum()))
    WF = pd.DataFrame(wf)
    txt.append("walk-forward:\n" + WF.round(0).to_string() + f"\nWF total net {WF.net.sum():.0f}, net15 {WF.net15.sum():.0f}")
    WF.to_csv(os.path.join(OUT, "wf.csv"), index=False)
    # promotion
    prom = V[(V.n >= 20) & (V.net15 > 0) & (V.q_cf < .05) & (V.q_t < .05) & (V.yrs_frac >= .6)]
    prom = prom if sp["spa_p"] < .10 else prom.iloc[0:0]
    best = V[V.n >= 20].sort_values("net", ascending=False).iloc[0].vid
    choice = dict(promoted=list(prom.sort_values("net", ascending=False).vid.head(3)), best_pre=best,
                  wf_2025=WF.iloc[-1].pick if len(WF) else None, spa=sp)
    txt.append(f"promoted: {choice['promoted']}  best PRE: {best}  WF 2025 pick: {choice['wf_2025']}")
    json.dump(choice, open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=str)
    open(os.path.join(OUT, "test_pre.log"), "w").write("\n\n".join(txt))
    print("\n\n".join(txt))


def run_holdout():
    choice = json.load(open(os.path.join(OUT, "choice.json")))
    f, w = load()
    H = w[w.day >= HOLD]
    sess = {u: H[H.und == u].day.nunique() for u in UNDS}
    vids = choice["promoted"] or [choice["best_pre"], choice["wf_2025"]]
    vids += ["{}|M|X0916|R01|F0".format(u) for u in UNDS]       # info: unconditional call, monthly, out 09:16
    out = []
    for vid in dict.fromkeys(vids):
        U, K, X, R, F = vid.split("|")
        t = pick(H, R, F, K, X, U)
        out.append(dict(vid=vid, n=len(t), gross=t.gross.sum(), net=t.net.sum(), net15=t.net15.sum(),
                        per_day=t.net.sum() / max(sess[U], 1), hit=(t.net > 0).mean() if len(t) else np.nan,
                        mdd=mdd(t.net.values), worst=t.net.min() if len(t) else np.nan,
                        coinflip=t.cf_mu.sum(), d0=H.day.min(), d1=H.day.max(), sessions=sess[U]))
    O = pd.DataFrame(out)
    O.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
    s = O.round(1).to_string()
    # info only: the families whose side beat a coin flip in PRE (R03 R04 R08 R17), every variant, F0
    fam = []
    for R in ("R01", "R03", "R04", "R08", "R17"):
        for U in UNDS:
            for K in KS:
                for X in XS:
                    t = pick(H, R, "F0", K, X, U)
                    if len(t) >= 10:
                        fam.append(dict(R=R, und=U, K=K, X=X, n=len(t), net=t.net.sum(), cf=t.cf_mu.sum(),
                                        per_day=t.net.sum() / max(sess[U], 1)))
    Fm = pd.DataFrame(fam)
    Fm.to_csv(os.path.join(OUT, "holdout_families.csv"), index=False)
    g = Fm.groupby("R").agg(variants=("net", "size"), pos=("net", lambda x: (x > 0).mean()),
                            beat_cf=("net", lambda x: (x > Fm.loc[x.index, "cf"]).mean()),
                            med_net=("net", "median"), med_per_day=("per_day", "median"))
    s += "\n\nfamilies (info, F0, all index x contract x exit with >= 10 trades):\n" + g.round(2).to_string()
    s += "\n\nby R x und (sum net / sum coin-flip):\n" + Fm.groupby(["R", "und"])[["n", "net", "cf"]].sum().round(0).to_string()
    open(os.path.join(OUT, "holdout.log"), "w").write(s)
    print(s)


if __name__ == "__main__":
    run_holdout() if "--holdout" in sys.argv else run_pre()
