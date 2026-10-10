"""h5 step 3 (TRAIN ONLY, days < 2025-10-01): trend-day recognition tables, the declared rule grid, selection,
walk-forward, BH, SPA/RC, random-day baselines.  Writes sel.pkl + train tables (markdown) to the scratch dir."""
import os, sys, pickle, itertools
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
from datetime import date
from obuy import config as C, overfit as OF

OUT = os.path.join(C.SCRATCH, "hunt", "h5")
HOLD = date(2025, 10, 1)
FILTERS = {  # name: (group, fn(feats df) -> bool mask)
    "drive>=0.3": ("drive", lambda f: f.drive >= 0.3), "drive>=0.5": ("drive", lambda f: f.drive >= 0.5),
    "rngr>=1.2": ("rngr", lambda f: f.rngr >= 1.2), "rngr>=1.5": ("rngr", lambda f: f.rngr >= 1.5),
    "gap_unfilled": ("gap", lambda f: f.gap_unf),
    "vix>=+2%": ("vix", lambda f: f.vchg >= 0.02), "vix>=+5%": ("vix", lambda f: f.vchg >= 0.05),
    "straddle>=1.00": ("st", lambda f: f.st_ratio >= 1.0), "straddle>=1.10": ("st", lambda f: f.st_ratio >= 1.1),
    "oi_side>=0.3": ("oi", lambda f: f.oi_al >= 0.3), "oi_side>=0.6": ("oi", lambda f: f.oi_al >= 0.6),
    "breadth>=0.65": ("br", lambda f: f.breadth >= 0.65), "breadth>=0.80": ("br", lambda f: f.breadth >= 0.8),
    "NR4": ("nr", lambda f: f.nr4), "NR7": ("nr", lambda f: f.nr7),
    "loc>=0.75": ("loc", lambda f: f["loc"] >= 0.75), "loc>=0.90": ("loc", lambda f: f["loc"] >= 0.9),
}
XS, YS, TSS, NS = (0.15, 0.25, 0.35), (0.25, 0.4, 0.6), (0, 60), (1, 3, 4)
EXPS = ("skip", "allow")


def conditions():
    out = [("all",)]
    names = list(FILTERS)
    out += [(n,) for n in names]
    for a, b in itertools.combinations(names, 2):
        if FILTERS[a][0] != FILTERS[b][0]:
            out.append((a, b))
    return out


def load():
    F = pd.read_pickle(os.path.join(OUT, "feats.pkl"))
    S = pd.read_pickle(os.path.join(OUT, "sims.pkl"))
    return F, S


def lots_pnl(S, N, kind):
    a = S[[f"{kind}{i}" for i in range(N)]].values
    return np.nansum(a, axis=1), (~np.isnan(a[:, 0]))


def build(F, S, days, span):
    """Returns variant table V and daily-P&L matrices (net, gross) over `days` for every declared variant."""
    F = F[F.day.isin(set(days))].copy()
    dpos = {d: i for i, d in enumerate(days)}
    conds = conditions()
    V, NET, GRO, CNT = [], [], [], []
    for T in sorted(F["T"].unique()):
        f = F[F["T"] == T].reset_index(drop=True)
        masks = {}
        for c in conds:
            m = np.ones(len(f), bool)
            for n in c:
                if n != "all":
                    m &= FILTERS[n][1](f).fillna(False).values.astype(bool)
            masks[c] = m
        di = f.day.map(dpos).values
        for (X, Y, TS) in itertools.product(XS, YS, TSS):
            s = S[(S["T"] == T) & (S.X == X) & (S.Y == Y) & (S.TS == TS)]
            m = f[["und", "day", "dir"]].merge(s, on=["und", "day", "dir"], how="left")
            for N in NS:
                net, took = lots_pnl(m, N, "n")
                gro, _ = lots_pnl(m, N, "g")
                for ex in EXPS:
                    base = took & (~f.exp.values if ex == "skip" else True)
                    for c in conds:
                        mm = masks[c] & base
                        if mm.sum() < 1:
                            continue
                        dn = np.bincount(di[mm], weights=net[mm], minlength=len(days))
                        dg = np.bincount(di[mm], weights=gro[mm], minlength=len(days))
                        V.append(dict(T=T, cond="&".join(c), X=X, Y=Y, TS=TS, N=N, exp=ex, n=int(mm.sum())))
                        NET.append(dn.astype(np.float32)); GRO.append(dg.astype(np.float32))
    return pd.DataFrame(V), np.array(NET).T, np.array(GRO).T


def main():
    F, S = load()
    F["year"] = pd.to_datetime(F.day).dt.year
    tr = F[F.day < HOLD]
    days = sorted(tr.day.unique())
    rep = []
    # ---------------- (a) recognition: base rates and feature tables (train only)
    rep.append(f"train days {len(days)} ({days[0]} .. {days[-1]}), und-days {tr.drop_duplicates(['und','day']).shape[0]}")
    t1 = tr[tr["T"] == 600].drop_duplicates(["und", "day"])
    rep.append(f"trend-day base rate (|close-open| >= 60% of range & range >= 1 ATR): {t1.trend.mean():.1%}; per month ~{t1.groupby('und').trend.sum().mean() / (len(days)/21):.1f} per index")
    rows = []
    for T in (600, 630, 660, 690):
        f = tr[tr["T"] == T]
        rows.append(dict(cond="ALL", T=T, n=len(f), share=1.0, p_trend=f.trend.mean(), cont=f.cont.mean(), mfe=f.mfe.mean()))
        for name, (g, fn) in FILTERS.items():
            m = fn(f).fillna(False).astype(bool)
            if m.sum() < 20:
                continue
            ff = f[m]
            # trend in the SAME direction as the drive at T
            same = ff.trend & (ff.cont > -0.0)
            rows.append(dict(cond=name, T=T, n=int(m.sum()), share=m.mean(), p_trend=ff.trend.mean(), cont=ff.cont.mean(), mfe=ff.mfe.mean()))
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(OUT, "recog_train.csv"), index=False)
    # trend day: also share of trend days whose direction is the drive direction at T
    for T in (600, 690):
        f = tr[(tr["T"] == T) & tr.trend]
        rep.append(f"T={C.hm(T)}: on trend days, drive dir at T = final dir in {(f.cont > -f.drive).mean():.0%} (cont>0: {(f.cont>0).mean():.0%})")
    # ---------------- (b) the grid
    V, NET, GRO = build(F, S, days, None)
    nd = len(days)
    V["net_day"] = NET.sum(0) / nd; V["gross_day"] = GRO.sum(0) / nd
    V["net_trade"] = NET.sum(0) / V.n; V["gross_trade"] = GRO.sum(0) / V.n
    dyears = np.array([d.year for d in days])
    for y in sorted(set(dyears)):
        V[f"y{y}"] = NET[dyears == y].sum(0)
    sd = NET.std(0, ddof=1); sd[sd == 0] = np.inf
    tstat = NET.mean(0) / (sd / np.sqrt(nd))
    from scipy.stats import norm
    V["p"] = 1 - norm.cdf(tstat)
    V["bh_q"] = OF.bh(V.p.values)
    V["years_pos"] = (V[[c for c in V if c.startswith("y2")]] > 0).sum(1)
    print("variants", len(V), flush=True)
    elig = V.n >= 60
    best = V[elig].sort_values("net_day", ascending=False)
    bestg = V[elig].sort_values("gross_day", ascending=False)
    # SPA / RC on net daily P&L (all variants) vs not trading
    sp = OF.spa(NET.astype(np.float64), B=500)
    spg = OF.spa(GRO.astype(np.float64), B=500)
    # walk-forward anchored by year: pick best net (n>=40 in train years) on years < y
    wf = []
    ycols = sorted(c for c in V if c.startswith("y2"))
    for y in [2022, 2023, 2024, 2025]:
        past = [c for c in ycols if int(c[1:]) < y]
        sc = V[past].sum(1).where(elig, -np.inf)
        k = int(sc.idxmax())
        wf.append(dict(year=y, pick=f"{V.at[k,'cond']} T{C.hm(V.at[k,'T'])} X{V.at[k,'X']} Y{V.at[k,'Y']} TS{V.at[k,'TS']} N{V.at[k,'N']} {V.at[k,'exp']}",
                       train_net=float(sc[k]), test_net=float(V.at[k, f"y{y}"]),
                       test_gross=float(GRO[dyears == y, k].sum()), test_days=int((dyears == y).sum())))
    # same rule family at N=1 vs N=3/4 (pyramid vs plain), and the "all days" control
    with open(os.path.join(OUT, "sel.pkl"), "wb") as f:
        pickle.dump(dict(V=V, days=days, spa=sp, spag=spg, wf=wf, rep=rep), f)
    np.save(os.path.join(OUT, "NET_train.npy"), NET); np.save(os.path.join(OUT, "GRO_train.npy"), GRO)
    pd.set_option("display.width", 250)
    print("\n".join(rep))
    print(R[R["T"].isin([600, 690])].round(3).to_string())
    cols = ["T", "cond", "X", "Y", "TS", "N", "exp", "n", "gross_day", "net_day", "gross_trade", "net_trade", "years_pos", "p", "bh_q"] + ycols
    print("TOP NET\n", best[cols].head(25).round(3).to_string())
    print("TOP GROSS\n", bestg[cols].head(10).round(3).to_string())
    print("SPA net", sp, "SPA gross", spg)
    print("BH q<0.05 net:", int((V.bh_q < 0.05).sum()), "of", len(V))
    print(pd.DataFrame(wf).to_string())
    a = V[(V.cond == "all")]
    print("ALL-days control:\n", a.groupby(["N"])[["gross_day", "net_day", "net_trade"]].describe().round(1).to_string())
    print("by N (all variants median):\n", V[elig].groupby("N")[["gross_day", "net_day"]].median().round(1))


if __name__ == "__main__":
    main()
