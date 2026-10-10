"""h39 C3: heavyweight stock-option features as FILTERS on the app's Liquidity 15+5 trades (BANKNIFTY, NIFTY).

Trades, costs and the random-skip test are h26's (h4 port of Liquidity 15+5, 1 lot, 1-ITM nearest; net = gross - app
charges - h24 half-spread x (entry + exit)). A feature is read at the close of the signal minute, signed toward the
trade's side. Filter = skip the trade when the signed feature <= -th (the heavyweights' options disagree), th 1 or 2.
6 features x 2 thresholds = 12 filters. Adoption (PREREG): d_day > 0 overall, in both halves (pre-2024 / 2024-Sep 2025)
and with 1.5x spread, and BH q <= 0.10. Holdout is run only for adopted filters.

    flock <scratch>/obuy.lock python3 -I research/hunt/h39/liqfilter.py pre
    flock <scratch>/obuy.lock python3 -I research/hunt/h39/liqfilter.py hold FEAT:th [...]
"""
from __future__ import annotations

import os
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.data import market  # noqa: E402
import proxy_diag as P  # noqa: E402
import stage_c_leadlag as SC  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h26"))
import liqfilter as L26  # noqa: E402

HOLD = pd.Timestamp("2025-10-01")
SPLIT = pd.Timestamp("2024-01-01")
DATA = os.path.join(C.SCRATCH, "hunt", "h39", "data")
FEATS = ["PFLOW5", "VIMB5", "OIB15", "IVJ5", "SYNF1", "SYNF5"]
pd.set_option("display.width", 250)


def attach(t, upto):
    for u, W in (("BANKNIFTY", P.W_BN), ("NIFTY", P.W_NF)):
        days, F = SC.build(u, W, DATA, upto)
        pos = {pd.Timestamp(d): i for i, d in enumerate(days)}
        m = (t.und == u).values
        r = t.loc[m, "day"].map(pos)
        ok = r.notna().values
        idx = np.flatnonzero(m)[ok]
        for f in FEATS:
            if f not in t:
                t[f] = np.nan
            t.loc[t.index[idx], f] = F[f][r[ok].astype(int).values, t.col.values[idx]] * t.side.values[idx]
    return t


def evaluate(t, ndays, names=None, B=2000, seed=11):
    rng = np.random.default_rng(seed)
    rows = []
    for f in FEATS:
        for th in (1.0, 2.0):
            nm = f"{f}:{th:g}"
            if names and nm not in names:
                continue
            tt = t[t[f].notna()]
            sk = (tt[f].values <= -th)
            m = int(sk.sum())
            sk_net = tt.net.values[sk].sum()
            if 0 < m < len(tt):
                draws = np.array([tt.net.values[rng.choice(len(tt), m, replace=False)].sum() for _ in range(B)])
                p = (1 + (draws <= sk_net).sum()) / (B + 1)
            else:
                p = 1.0
            nd = ndays(tt)
            rows.append(dict(filter=nm, n=len(tt), skipped=m, base_day=tt.net.sum() / nd, d_day=-sk_net / nd,
                             d_day15=-tt.net15.values[sk].sum() / nd, d_gross_day=-tt.gross_p.values[sk].sum() / nd,
                             skipped_avg=tt.net.values[sk].mean() if m else np.nan, p=p))
    R = pd.DataFrame(rows)
    R["bh"] = OF.bh(R.p.values)
    return R


def main(mode, names=()):
    t = L26.load_trades()
    t = t[t.und.isin(["BANKNIFTY", "NIFTY"])].reset_index(drop=True)
    cal = pd.Series(pd.to_datetime(market().index("NIFTY").days))
    nd = L26.ndays_fn(cal)
    if mode == "pre":
        t = attach(t[t.day < HOLD].reset_index(drop=True), SC.HOLD)
        print("Liquidity trades pre-holdout (BN+NIFTY):", len(t), t.groupby("und").size().to_dict())
        print(f"unfiltered net Rs/day {t.net.sum() / nd(t):.0f}, gross {t.gross_p.sum() / nd(t):.0f}")
        R = evaluate(t, nd)
        for col, (lo, hi) in (("d_day_h1", (pd.Timestamp("2000-01-01"), SPLIT)), ("d_day_h2", (SPLIT, HOLD))):
            x = evaluate(t[(t.day >= lo) & (t.day < hi)], nd, B=200)
            R[col] = x.set_index("filter").d_day.reindex(R["filter"]).values
        R["adopt"] = (R.bh <= 0.10) & (R.d_day > 0) & (R.d_day15 > 0) & (R.d_day_h1 > 0) & (R.d_day_h2 > 0)
        print(R.round(3).to_string())
        R.to_csv(os.path.join(C.SCRATCH, "hunt", "h39", "liqfilter_pre.csv"), index=False)
    else:
        t = attach(t, pd.Timestamp("2100-01-01").date())
        th = t[t.day >= HOLD]
        print("Liquidity trades holdout (BN+NIFTY):", len(th), th.groupby("und").size().to_dict())
        print(evaluate(th, nd, names=set(names)).round(3).to_string())


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
