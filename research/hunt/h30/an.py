"""h30 step 3: statistics over the 3,078 pre-registered variants (pre-holdout), and the one-time holdout.

python3 -I research/hunt/h30/an.py pre        # choice data only (<= 2025-09-30)
python3 -I research/hunt/h30/an.py holdout    # run ONCE at the end, for the survivors / best variant listed in pre
"""
import json
import os
import sys
from datetime import date

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from scipy import stats as sst  # noqa: E402

from obuy.overfit import bh, holm, spa  # noqa: E402
import sig  # noqa: E402
from sim import HS, exit_menu  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h30")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
HOLD = date(2025, 10, 1)
DEFS = sig.defs()
EXN = [n for n, _ in exit_menu()]
MONEY = {-1: "OTM1", 0: "ATM", 1: "ITM1"}


def load():
    D = {}
    for u in UNDS:
        meta = pd.read_pickle(os.path.join(OUT, f"meta_{u}.pkl")).reset_index(drop=True)
        z = dict(np.load(os.path.join(OUT, f"sim_{u}.npz")))
        D[u] = (meta, z)
    return D


def trades(D, period):
    """Long table of every variant's trades in the period: one row per (variant trade), all 19 exits as columns."""
    rows = []
    for u, (meta, z) in D.items():
        hold = (meta.day >= HOLD).values
        keep = hold if period == "holdout" else ~hold
        hs = HS[u]
        for j in range(len(DEFS)):
            ok = (z["ENT"][j] >= 0) & keep
            r = np.nonzero(ok)[0]
            if not len(r):
                continue
            uu = z["real_u"][j, r]
            NET, G = z["NET"][uu], z["G"][uu]
            e, q = z["e_raw"][uu], z["qty"][uu]
            S = NET - 0.5 * hs * (2 * e * q)[:, None] - 0.5 * hs * G
            ru = z["rnd_u"][r]                                       # (n, 20)
            RN = np.where(ru[:, :, None] >= 0, z["NET"][np.maximum(ru, 0)], np.nan)   # (n, 20, 19)
            rows.append(dict(u=u, j=j, money=meta.money.values[r], right=meta.right.values[r], day=meta.day.values[r],
                             row=r, ent=z["ENT"][j, r], e=e, q=q, prem=e * q, NET=NET, G=G, S=S,
                             XM=z["XM"][uu], rmu=np.nanmean(RN, axis=1), rvar=np.nanvar(RN, axis=1),
                             rn=np.sum(~np.isnan(RN), axis=1)))
    return rows


def calendar(D, period):
    days = set()
    for u, (meta, z) in D.items():
        d = meta.day[(meta.day >= HOLD) if period == "holdout" else (meta.day < HOLD)]
        days |= set(d)
    return sorted(days)


def variant_stats(D, period):
    cal = calendar(D, period)
    dpos = {d: i for i, d in enumerate(cal)}
    T = len(cal)
    years = sorted({d.year for d in cal})
    R = trades(D, period)
    nv = len(DEFS) * 3 * len(EXN)
    daily = np.zeros((T, nv), np.float64)
    dailyS = np.zeros((T, nv))
    dailyG = np.zeros((T, nv))
    agg = {k: np.zeros(nv) for k in ("n", "net", "gross", "stress", "rmu", "rvar", "prem")}
    yearly = np.zeros((nv, len(years)))
    yi = {y: i for i, y in enumerate(years)}
    peru = {u: np.zeros(nv) for u in UNDS}
    perun = {u: np.zeros(nv) for u in UNDS}

    def vid(j, m, k):
        return (j * 3 + (m + 1)) * len(EXN) + k

    for x in R:
        di = np.array([dpos[d] for d in x["day"]])
        yy = np.array([yi[d.year] for d in x["day"]])
        for m in (-1, 0, 1):
            s = x["money"] == m
            if not s.any():
                continue
            for k in range(len(EXN)):
                v = vid(x["j"], m, k)
                net = x["NET"][s, k].astype(np.float64)
                okn = ~np.isnan(net)
                net = np.nan_to_num(net)
                g = np.nan_to_num(x["G"][s, k].astype(np.float64))
                st = np.nan_to_num(x["S"][s, k].astype(np.float64))
                daily[:, v] += np.bincount(di[s], net, T)
                dailyG[:, v] += np.bincount(di[s], g, T)
                dailyS[:, v] += np.bincount(di[s], st, T)
                yearly[v] += np.bincount(yy[s], net, len(years))
                agg["n"][v] += okn.sum()
                agg["net"][v] += net.sum()
                agg["gross"][v] += g.sum()
                agg["stress"][v] += st.sum()
                rmu = np.nan_to_num(x["rmu"][s, k])
                rvar = np.nan_to_num(x["rvar"][s, k])
                agg["rmu"][v] += rmu.sum()
                agg["rvar"][v] += rvar.sum()
                agg["prem"][v] += x["prem"][s].sum()
                peru[x["u"]][v] += net.sum()
                perun[x["u"]][v] += okn.sum()
    rows = []
    for j, (fam, tf) in enumerate(DEFS):
        for m in (-1, 0, 1):
            for k, en in enumerate(EXN):
                rows.append(dict(vid=vid(j, m, k), fam=fam, tf=tf, money=MONEY[m], exit=en))
    V = pd.DataFrame(rows).set_index("vid").sort_index()
    n = np.maximum(agg["n"], 1)
    V["n"] = agg["n"]
    V["net_tr"] = agg["net"] / n
    V["gross_tr"] = agg["gross"] / n
    V["rand_tr"] = agg["rmu"] / n
    V["excess_tr"] = V.net_tr - V.rand_tr
    se = np.sqrt(agg["rvar"]) / n
    V["z_rand"] = np.where(se > 0, V.excess_tr / np.where(se > 0, se, 1), 0)
    V["p_rand"] = 1 - sst.norm.cdf(V.z_rand)
    V["net_day"] = daily.mean(axis=0)
    V["gross_day"] = dailyG.mean(axis=0)
    V["stress_day"] = dailyS.mean(axis=0)
    sd = daily.std(axis=0, ddof=1)
    V["t0"] = np.where(sd > 0, V.net_day / np.where(sd > 0, sd, 1) * np.sqrt(T), 0)
    V["p0"] = 1 - sst.t.cdf(V.t0, T - 1)
    V["avg_prem"] = agg["prem"] / n
    for i, y in enumerate(years):
        V[f"y{y}"] = yearly[:, i]
    V["yrs_pos"] = (yearly > 0).sum(axis=1)
    for u in UNDS:
        V[f"{u}_day"] = peru[u] / T
        V[f"{u}_n"] = perun[u]
    eq = np.cumsum(daily, axis=0)
    V["maxdd"] = (eq - np.maximum.accumulate(np.maximum(eq, 0), axis=0)).min(axis=0)
    return V, daily, cal, years, R


def walk_forward(V, years, train=2):
    out = []
    ycols = [f"y{y}" for y in years]
    for i, y in enumerate(years[train:], start=train):
        past = V[ycols[:i]].sum(axis=1)
        p = past.idxmax()
        out.append(dict(test_year=y, picked=f"{V.fam[p]} tf{V.tf[p]} {V.money[p]} {V.exit[p]}", train_net=past[p],
                        test_net=V.loc[p, ycols[i]], test_n_note=""))
    return pd.DataFrame(out)


def fmt(df):
    return df.to_markdown(index=False, floatfmt=".1f") if hasattr(df, "to_markdown") else df.to_string(index=False)


def pre():
    D = load()
    V, daily, cal, years, R = variant_stats(D, "pre")
    T = len(cal)
    V["q0_bh"] = bh(V.p0.values)
    V["q0_holm"] = holm(V.p0.values)
    V["qr_bh"] = bh(V.p_rand.values)
    V["qr_holm"] = holm(V.p_rand.values)
    sp = spa(daily, B=1000, mean_block=5.0)
    best = V.index[sp["best"]]
    V["surv"] = (V.q0_bh < 0.10) & (V.qr_bh < 0.10) & (sp["spa_p"] < 0.10) & (V.yrs_pos >= 3) & (V.stress_day > 0)
    V.to_csv(os.path.join(OUT, "variants_pre.csv"))
    wf = walk_forward(V, years)
    lines = [f"PRE-HOLDOUT  days={T}  variants={len(V)}  years={years}",
             f"SPA p={sp['spa_p']:.3f}  RC p={sp['rc_p']:.3f}  best t={sp['t_best']:.2f}: "
             f"{V.fam[best]} tf{V.tf[best]} {V.money[best]} {V.exit[best]}",
             f"variants with net>0: {(V.net_day>0).sum()}   t0>2: {(V.t0>2).sum()}   min q0_bh={V.q0_bh.min():.3f}  "
             f"min q0_holm={V.q0_holm.min():.3f}",
             f"beat random p<0.05: {(V.p_rand<0.05).sum()}  min qr_bh={V.qr_bh.min():.3f}  qr_bh<0.10: {(V.qr_bh<0.10).sum()}",
             f"survivors: {int(V.surv.sum())}",
             f"overall mean net/trade {np.average(V.net_tr, weights=V.n):.1f}  random {np.average(V.rand_tr, weights=V.n):.1f}"
             f"  gross/trade {np.average(V.gross_tr, weights=V.n):.1f}",
             "", "WALK-FORWARD (anchored, best total net so far):", wf.to_string(index=False),
             f"WF total {wf.test_net.sum():.0f}", ""]
    cols = ["fam", "tf", "money", "exit", "n", "gross_tr", "net_tr", "rand_tr", "excess_tr", "p_rand", "qr_bh",
            "gross_day", "net_day", "stress_day", "t0", "q0_bh", "yrs_pos", "maxdd"]
    lines += ["TOP 15 by t0:", V.sort_values("t0", ascending=False)[cols].head(15).round(3).to_string(), ""]
    lines += ["TOP 15 by excess z vs random:", V.sort_values("z_rand", ascending=False)[cols].head(15).round(3).to_string(), ""]
    fam = V.groupby("fam").agg(variants=("n", "size"), best_net_day=("net_day", "max"), med_net_day=("net_day", "median"),
                               med_gross_tr=("gross_tr", "median"), med_net_tr=("net_tr", "median"),
                               med_excess_tr=("excess_tr", "median"), share_beat_rand=("p_rand", lambda p: (p < 0.05).mean()),
                               best_t0=("t0", "max"))
    lines += ["BY FAMILY:", fam.round(2).to_string(), ""]
    ex = V.groupby("exit").agg(med_net_tr=("net_tr", "median"), med_rand_tr=("rand_tr", "median"),
                               med_excess=("excess_tr", "median"), best_net_day=("net_day", "max"))
    lines += ["BY EXIT:", ex.round(1).to_string(), ""]
    tfm = V.groupby(["tf"]).agg(med_net_tr=("net_tr", "median"), med_excess=("excess_tr", "median"))
    lines += ["BY TIMEFRAME:", tfm.round(1).to_string(), ""]
    mo = V.groupby(["money"]).agg(med_net_tr=("net_tr", "median"), med_excess=("excess_tr", "median"),
                                  med_gross_tr=("gross_tr", "median"))
    lines += ["BY MONEYNESS:", mo.round(1).to_string(), ""]
    # per underlying: random entries alone (what a coin flip costs on each index), all exits pooled
    lines += ["PER UNDERLYING (median over variants of Rs/day at 1 lot; trades):"]
    for u in UNDS:
        lines.append(f"  {u}: median net/day {V[f'{u}_day'].median():.0f}, best {V[f'{u}_day'].max():.0f}, "
                     f"share>0 {(V[f'{u}_day']>0).mean():.2f}")
    sel = V[V.surv] if V.surv.any() else V.loc[[best]]
    with open(os.path.join(OUT, "holdout_list.json"), "w") as f:
        json.dump(dict(vids=[int(x) for x in sel.index], survivors=bool(V.surv.any()), wf=wf.to_dict("records")), f,
                  default=str)
    lines.append(f"\nHOLDOUT LIST ({'survivors' if V.surv.any() else 'no survivors: best pre-holdout t only'}): "
                 + "; ".join(f"{V.fam[i]} tf{V.tf[i]} {V.money[i]} {V.exit[i]}" for i in sel.index))
    txt = "\n".join(lines)
    open(os.path.join(OUT, "an_pre.txt"), "w").write(txt)
    print(txt)


def detail(V, daily, cal, vid, R, label):
    """Rs/day, drawdown, worst day/month, P(losing month), capital, lots for 5k/day for one variant."""
    x = daily[:, V.index.get_loc(vid)]
    s = pd.Series(x, index=pd.to_datetime(cal))
    mon = s.groupby(s.index.to_period("M")).sum()
    eq = s.cumsum()
    dd = (eq - eq.cummax().clip(lower=0)).min()
    rng = np.random.default_rng(5)
    b = np.array([rng.choice(x, 21).sum() for _ in range(5000)])
    nd = s.mean()
    return dict(label=label, days=len(x), rs_day=nd, lots_5k=(5000 / nd if nd > 0 else np.inf), maxdd=dd,
                worst_day=s.min(), worst_month=mon.min(), months_pos=f"{(mon>0).sum()}/{len(mon)}",
                p_losing_month=(b < 0).mean())


def holdout():
    D = load()
    hl = json.load(open(os.path.join(OUT, "holdout_list.json")))
    Vp = pd.read_csv(os.path.join(OUT, "variants_pre.csv")).set_index("vid")
    V, daily, cal, years, R = variant_stats(D, "holdout")
    Vpre, dpre, calpre, _, Rp = variant_stats(D, "pre")
    cols = ["fam", "tf", "money", "exit", "n", "gross_tr", "net_tr", "rand_tr", "excess_tr", "p_rand", "gross_day",
            "net_day", "stress_day", "t0"] + [f"{u}_day" for u in UNDS]
    lines = [f"HOLDOUT (run once) days={len(cal)} {cal[0]}..{cal[-1]}  list: {'survivors' if hl['survivors'] else 'best pre-holdout t (descriptive)'}"]
    for vid in hl["vids"]:
        lines += ["PRE:", Vp.loc[[vid], cols].round(2).T.to_string(), "HOLDOUT:", V.loc[[vid], cols].round(2).T.to_string()]
        lines.append(str(detail(Vpre, dpre, calpre, vid, R, "pre")))
        lines.append(str(detail(V, daily, cal, vid, R, "holdout")))
    # walk-forward pick for the holdout (best total pre-holdout net) - one more descriptive look, counted
    p = Vp.net_day.idxmax()
    lines += ["", "Best pre-holdout total net variant (the walk-forward's next pick):",
              f"{Vp.fam[p]} tf{Vp.tf[p]} {Vp.money[p]} {Vp.exit[p]}: pre {Vp.net_day[p]:.0f}/day, holdout {V.net_day[p]:.0f}/day "
              f"(gross {V.gross_day[p]:.0f}, stress {V.stress_day[p]:.0f}), n={V.n[p]:.0f}"]
    lines += ["", "Whole grid in the holdout (descriptive): variants net>0: "
              f"{(V.net_day>0).sum()}/{len(V)}; median net/trade {V.net_tr.median():.1f}, median random {V.rand_tr.median():.1f}"]
    txt = "\n".join(lines)
    open(os.path.join(OUT, "an_holdout.txt"), "w").write(txt)
    print(txt)


if __name__ == "__main__":
    {"pre": pre, "holdout": holdout}[sys.argv[1]]()
