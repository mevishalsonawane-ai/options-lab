"""h5 step 4: run ONCE on the locked holdout (2025-10-01 ..). Variants fixed by pick.py (sel.pkl):
 A = best train net/day (n>=60), its plain 1-lot twin (N=1), the 'all days' control with the same exits,
 the walk-forward 2025 pick; random-day baselines (same count, same exits, drive dir / coin-flip dir)."""
import os, sys, pickle
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np, pandas as pd
from datetime import date
from obuy import config as C
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import pick as PK

OUT = PK.OUT


def daily(F, S, days, v, dir_override=None, day_subset=None):
    f = F[(F["T"] == v["T"]) & F.day.isin(set(days))].reset_index(drop=True)
    if dir_override is not None:
        f = f.assign(dir=dir_override(len(f)))
    s = S[(S["T"] == v["T"]) & (S.X == v["X"]) & (S.Y == v["Y"]) & (S.TS == v["TS"])]
    m = f[["und", "day", "dir"]].merge(s, on=["und", "day", "dir"], how="left")
    net, took = PK.lots_pnl(m, v["N"], "n"); gro, _ = PK.lots_pnl(m, v["N"], "g")
    mask = took & ((~f.exp.values) if v["exp"] == "skip" else True)
    if day_subset is None:
        for n in v["cond"].split("&"):
            if n != "all":
                mask &= PK.FILTERS[n][1](f).fillna(False).values.astype(bool)
    else:
        mask &= day_subset(f)
    dpos = {d: i for i, d in enumerate(days)}
    di = f.day.map(dpos).values
    lots = np.sum(~np.isnan(m[[f"n{i}" for i in range(v["N"])]].values), 1)
    prem = m.prem.values
    T = pd.DataFrame(dict(und=f.und, day=f.day, dir=f.dir, lots=lots, prem=prem, net=net, gross=gro))[mask]
    return (np.bincount(di[mask], weights=net[mask], minlength=len(days)),
            np.bincount(di[mask], weights=gro[mask], minlength=len(days)), T)


def summarize(name, dn, dg, T, days):
    s = pd.Series(dn, index=pd.to_datetime(days)); g = pd.Series(dg, index=s.index)
    eq = s.cumsum(); dd = float((eq - eq.cummax()).min())
    mo = s.groupby(s.index.to_period("M")).sum()
    rng = np.random.default_rng(1)
    # P(losing month): bootstrap 21-day months from the daily P&L
    boot = rng.choice(dn, size=(20000, 21)).sum(1)
    return dict(name=name, trades=len(T), days=len(days), trade_days_per_month=len(T.day.unique()) / (len(days) / 21),
                gross_day=g.mean(), net_day=s.mean(), gross_trade=g.sum() / max(len(T), 1), net_trade=s.sum() / max(len(T), 1),
                win=(T.net > 0).mean() if len(T) else np.nan, worst_day=s.min(), best_day=s.max(), worst_month=mo.min(),
                months_neg=f"{(mo < 0).sum()}/{len(mo)}", p_lose_month=(boot < 0).mean(), maxdd=dd,
                top5_share=(np.sort(dn)[::-1][:5].sum() / dn.sum()) if dn.sum() > 0 else np.nan,
                avg_lots=T.lots.mean() if len(T) else np.nan, avg_prem=T.prem.mean() if len(T) else np.nan)


def random_days(F, S, days, v, n_trades, B=300, coin=False, seed=7):
    rng = np.random.default_rng(seed)
    res = []
    for b in range(B):
        def sub(f):
            m = np.zeros(len(f), bool)
            m[rng.choice(len(f), size=min(n_trades, len(f)), replace=False)] = True
            return m
        do = (lambda n: rng.choice([-1, 1], n)) if coin else None
        dn, dg, T = daily(F, S, days, v, dir_override=do, day_subset=sub)
        res.append((dn.mean(), dg.mean()))
    return np.array(res)


def main(which="holdout"):
    with open(os.path.join(OUT, "sel.pkl"), "rb") as f:
        sel = pickle.load(f)
    V = sel["V"]
    F, S = PK.load()
    if which == "holdout":
        days = sorted(F[F.day >= PK.HOLD].day.unique())
    else:
        days = sel["days"]
    elig = V[V.n >= 60]
    A = elig.sort_values("net_day", ascending=False).iloc[0].to_dict()
    G = elig.sort_values("gross_day", ascending=False).iloc[0].to_dict()
    vs = {"A best train net/day": A, "A plain 1 lot": {**A, "N": 1}, "A pyramid 4 lots": {**A, "N": 4},
          "A exits, ALL days": {**A, "cond": "all"}, "G best train gross/day": G}
    rows, tr = [], {}
    for k, v in vs.items():
        dn, dg, T = daily(F, S, days, v)
        rows.append({**summarize(k, dn, dg, T, days), "rule": f"{v['cond']} T{C.hm(v['T'])} X{v['X']} Y{v['Y']} TS{v['TS']} N{v['N']} {v['exp']}"})
        tr[k] = T
    R = pd.DataFrame(rows)
    nA = rows[0]["trades"]
    rd = random_days(F, S, days, A, nA); rc = random_days(F, S, days, A, nA, coin=True)
    pdrv = (1 + (rd[:, 0] >= rows[0]["net_day"]).sum()) / (len(rd) + 1)
    pcoin = (1 + (rc[:, 0] >= rows[0]["net_day"]).sum()) / (len(rc) + 1)
    return R, dict(rand_drive=rd.mean(0), rand_drive_hi=np.quantile(rd[:, 0], 0.95), p_drive=pdrv,
                   rand_coin=rc.mean(0), rand_coin_hi=np.quantile(rc[:, 0], 0.95), p_coin=pcoin), tr, A


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "train"
    R, rb, tr, A = main(which)
    pd.set_option("display.width", 250)
    print(which, R.round(3).T.to_string()); print(rb)
    for k, T in tr.items():
        T.to_csv(os.path.join(OUT, f"trades_{which}_{k.split()[0]}_{k.replace(' ','_').replace('/','')}.csv"), index=False)
