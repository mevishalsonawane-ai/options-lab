"""h11 SECONDARY pre-holdout analysis - POST-HOC, added after reading pre.py's 1-lot results (labelled so in the report).

Why: at 1 lot the fixed Rs 20/order brokerage (+GST) is ~Rs 47 of a ~Rs 60 round-trip cost per NIFTY/SENSEX trade, so the
pre-registered 1-lot metric penalises exactly what capacity removes. Here the SAME 48 series are re-ranked at a fixed
10 lots per trade (kappa 0.02), with the same PASS rules; random-entry p is also computed on GROSS per trade (costs equal
for real and random entries). Holdout still untouched; hold.py tests both choices once.

    OBUY_CACHE=<scratch>/hunt/h11/cache python3 -I research/hunt/h11/pre2.py
"""
import json, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import pre  # noqa
from pre import OF, PF, UNDS, col, year_tab, stats  # noqa
import numpy as np
import pandas as pd

N = 10


def main():
    T, P, D, BP = pre.load(pre=True)
    keys = sorted(T.key.unique())
    rows, X = [], []
    for key in keys:
        und = key.split("|")[1]
        s, g = col(D, key, N), col(D, key, N, what="gross")
        t, p = T[T.key == key], P[P.key == key]
        rg = OF.random_baseline(t.assign(net=t.gross), p.assign(net=p.gross))
        yr = year_tab(s)
        r = dict(key=key, und=und, net_day=s.mean(), gross_day=g.mean(), cost_share=1 - s.sum() / g.sum() if g.sum() > 0 else np.nan,
                 rand_p_gross=rg["p"], sharpe=stats(s)["sharpe"])
        for y in (2022, 2023, 2024, 2025):
            r[f"y{y}"] = yr.get(y, 0.0)
        rows.append(r); X.append(s.values)
    R = pd.DataFrame(rows)
    R["bh_q"] = OF.bh(R.rand_p_gross.values)
    print(R.round(3).to_string())
    S = OF.spa(np.column_stack(X), B=2000)
    print("SPA/RC 48 series at %d lots:" % N, S, keys[S["best"]])
    out = {}
    for und in UNDS:
        Ru = R[R.und == und]
        oos = []
        picks = {}
        for y in ((2023, 2024, 2025) if und == "NIFTY" else (2024, 2025)):
            e = pd.Timestamp(f"{y}-01-01")
            b = max(Ru.key, key=lambda k: col(D, k, N)[col(D, k, N).index < e].sum())
            picks[y] = b
            s = col(D, b, N)
            oos.append(s[(s.index >= e) & (s.index < pd.Timestamp(f"{y + 1}-01-01"))])
        o = pd.concat(oos)
        best = Ru.sort_values("net_day", ascending=False).iloc[0]
        tr_years = [y for y in (2023, 2024, 2025) if (T[T.key == best.key].day.dt.year == y).any()]
        a, b_, c = o.sum() > 0, best.bh_q <= 0.05, sum(best[f"y{y}"] > 0 for y in tr_years) >= 2
        out[und] = dict(key=best.key, net_day=best.net_day, gross_day=best.gross_day, bh_q=best.bh_q, picks=picks,
                        wf_oos_net=o.sum(), wf_by_year=year_tab(o).round(0).to_dict(), PASS=bool(a and b_ and c),
                        wf_pos=bool(a), bh_ok=bool(b_), years_ok=bool(c))
        print(und, out[und])
    json.dump(dict(lots=N, choice=out, spa=S, table=R.round(4).to_dict(orient="records")),
              open(os.path.join(pre.OUT, "choice2.json"), "w"), indent=1, default=float)


if __name__ == "__main__":
    main()
