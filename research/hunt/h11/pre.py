"""h11 stage 2: choose on data BEFORE 2025-10-01 only (PREREG.md). Reads build.py outputs and drops every row on or after
the holdout start at load time.

    OBUY_CACHE=<scratch>/hunt/h11/cache python3 -I research/hunt/h11/pre.py
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
import portfolio as PF  # noqa: E402

OUT = os.path.join(C.CACHE, "h11")
HOLD = pd.Timestamp("2025-10-01")
LONG = pd.Timestamp("2023-06-01")
REG = pd.Timestamp("2024-12-01")
UNDS = ("NIFTY", "SENSEX")
LOTS = [1, 2, 3, 5, 8, 10, 15, 20, 30, 50, 75, 100, 150, 200, 300, 500]
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)
pd.set_option("display.max_rows", 200)


def load(pre=True):
    T = pd.read_parquet(os.path.join(OUT, "trades1.parquet"))
    P = pd.read_parquet(os.path.join(OUT, "pool1.parquet"))
    D = pd.read_parquet(os.path.join(OUT, "daily.parquet"))
    BP = pd.read_parquet(os.path.join(OUT, "bnplan.parquet"))
    T["day"] = pd.to_datetime(T.day)
    P["day"] = pd.to_datetime(P.day)
    if pre:
        T, P, D, BP = T[T.day < HOLD], P[P.day < HOLD], D[D.index < HOLD], BP[BP.index < HOLD]
    return T, P, D, BP


def col(D, key, n=1, k=0.02, what="net"):
    return D[f"{key}|{n}|{k}|{what}"]


def year_tab(s):
    return s.groupby(s.index.year).sum()


def stats(x):
    st = PF.stats_row(x.values, x.index)
    return dict(net_day=x.mean(), worst_day=st["worst_day"], worst_month=st["worst_month"], losing_months=st["losing_months"],
                maxdd=st["maxdd"], sharpe=st["sharpe"], pos_days=st["pos_days"])


def main():
    T, P, D, BP = load(pre=True)
    keys = sorted(T.key.unique())
    assert len(keys) == 48, len(keys)
    rows, X = [], []
    for key in keys:
        vid, und = key.split("|")
        s = col(D, key)
        g = col(D, key, what="gross")
        t = T[T.key == key]
        rb = OF.random_baseline(t, P[P.key == key])
        yr = year_tab(s)
        r = dict(key=key, und=und, trades=len(t), net=s.sum(), gross=g.sum(), net_day=s.mean(), gross_day=g.mean(),
                 net_trade=t.net.mean(), rand_p=rb["p"], rand_null=rb["null_mean"],
                 sharpe=stats(s)["sharpe"], maxdd=PF.maxdd(s.values))
        for y in (2021, 2022, 2023, 2024, 2025):
            r[f"y{y}"] = yr.get(y, 0.0)
        rows.append(r)
        X.append(s.values)
    R = pd.DataFrame(rows)
    R["bh_q"] = OF.bh(R.rand_p.values)
    print(R.drop(columns=["rand_null"]).round(3).to_string())
    S = OF.spa(np.column_stack(X), B=2000)
    print("SPA/RC over 48 pre-holdout daily series (1 lot) vs 0:", S, "best:", keys[S["best"]])
    S_u = {}
    for und in UNDS:
        idx = [i for i, k in enumerate(keys) if k.endswith(und)]
        S_u[und] = OF.spa(np.column_stack([X[i] for i in idx]), B=2000)
        print("SPA/RC", und, "24 series:", S_u[und])
    # walk-forward (anchored, by year)
    wf, choice = {}, {}
    for und in UNDS:
        Ru = R[R.und == und]
        oos = []
        picks = {}
        for y in ((2023, 2024, 2025) if und == "NIFTY" else (2024, 2025)):
            tr_end = pd.Timestamp(f"{y}-01-01")
            best = max(Ru.key, key=lambda k: col(D, k)[col(D, k).index < tr_end].sum())
            picks[y] = best
            s = col(D, best)
            oos.append(s[(s.index >= tr_end) & (s.index < pd.Timestamp(f"{y + 1}-01-01"))])
        o = pd.concat(oos)
        wf[und] = dict(picks=picks, oos_net=o.sum(), oos_net_day=o.mean(), oos_by_year=year_tab(o).round(0).to_dict())
        best = Ru.sort_values("net_day", ascending=False).iloc[0]
        yrs = [best[f"y{y}"] for y in (2023, 2024, 2025)]
        tr_years = [y for y in (2023, 2024, 2025) if (T[(T.key == best.key)].day.dt.year == y).any()]
        pos_years = sum(best[f"y{y}"] > 0 for y in tr_years)
        a, b, c = o.sum() > 0, best.bh_q <= 0.05, pos_years >= 2
        choice[und] = dict(key=best.key, net_day=best.net_day, gross_day=best.gross_day, rand_p=best.rand_p, bh_q=best.bh_q,
                           years=dict(zip((2023, 2024, 2025), yrs)), wf_pos=bool(a), bh_ok=bool(b), years_ok=bool(c),
                           PASS=bool(a and b and c))
        print(und, "walk-forward:", wf[und])
        print(und, "CHOICE:", choice[und])
    # capacity + sizing (reported for both; binding only for a PASS)
    capy = {}
    for und in UNDS:
        key = choice[und]["key"]
        t = T[(T.key == key)]
        reg = t[t.day >= REG]
        lng = t[t.day >= LONG]
        N = int(np.floor(0.15 * np.quantile(reg.exit_v5, 0.25)))
        curve = {}
        for k in (0.01, 0.02, 0.04):
            curve[k] = {n: dict(long=col(D, key, n, k)[col(D, key, n, k).index >= LONG].mean(),
                                reg=col(D, key, n, k)[col(D, key, n, k).index >= REG].mean(),
                                gross_long=col(D, key, n, k, "gross")[col(D, key, n, k).index >= LONG].mean())
                        for n in LOTS}
        ok = [n for n in LOTS if n <= max(N, 1)]
        lots = max(ok, key=lambda n: curve[0.02][n]["long"])
        capy[und] = dict(key=key, p25_exit_v5_reg=float(np.quantile(reg.exit_v5, 0.25)),
                         p25_exit_v5_long=float(np.quantile(lng.exit_v5, 0.25)), N=N, lots=lots,
                         median_entry_v5_reg=float(reg.entry_v5.median()))
        print(und, "capacity:", capy[und])
        print(pd.DataFrame({(k, w): {n: v[w] for n, v in curve[k].items()} for k in curve for w in ("long", "reg", "gross_long")}).round(0).to_string())
    # combination with the h10 BANKNIFTY plan (pre-holdout)
    comb = {}
    for und in UNDS:
        key, n = capy[und]["key"], capy[und]["lots"]
        for lim in ("none", "both"):
            bn = BP[f"{lim}|0.02|net"]
            x = col(D, key, n, 0.02)
            for wl, lo in (("long", LONG), ("regime", REG)):
                b_, x_ = bn[bn.index >= lo], x[x.index >= lo]
                cc = b_ + x_
                comb[f"{und}|{lim}|{wl}"] = dict(corr=float(np.corrcoef(b_, x_)[0, 1]), plan=stats(b_), add=stats(x_),
                                                  combined=stats(cc))
    for k, v in comb.items():
        print(k, "corr %.3f" % v["corr"], {a: {kk: (round(vv, 2) if isinstance(vv, float) else vv) for kk, vv in b.items()}
                                            for a, b in v.items() if a != "corr"})
    json.dump(dict(choice=choice, wf=wf, spa=S, spa_und=S_u, capacity=capy, n_series=48,
                   table=R.round(4).to_dict(orient="records"), comb_pre=comb),
              open(os.path.join(OUT, "choice.json"), "w"), indent=1, default=float)


if __name__ == "__main__":
    main()
