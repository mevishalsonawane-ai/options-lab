"""h43 part B: the report's strategies on all history via the outcome table (table.py).

    python3 -I research/hunt/h43/partB.py check     # lookup == direct engine on the report window (vs partA.csv)
    python3 -I research/hunt/h43/partB.py pre       # pre-holdout 2021-08-04 .. 2025-09-30: per year, random, BH, SPA
    python3 -I research/hunt/h43/partB.py hold      # the locked holdout 2025-10-01 .. data end, run ONCE

Costs: gross = app fills, no charges. ours = app fills + app charges + 0.16% half-spread on entry and exit.
theirs = raw prints (app slippage added back: 5 bps a side, approx.) - Rs 95 per lot round trip.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.overfit import bh, spa  # noqa: E402
import sigs  # noqa: E402
from partA import HS, OUT, W0, W1  # noqa: E402

SM0 = 555
HOLD = date(2025, 10, 1)
B_RAND = 2000
M_BH = 41


def load(name="tab.npz"):
    z = np.load(os.path.join(OUT, name))
    T = dict(days=z["days"], year=z["year"], e=z["e"], qty=z["qty"])
    for k in [f[2:] for f in z.files if f.startswith("n_")]:
        n, s, g = z[f"n_{k}"], z[f"s_{k}"], z[f"g_{k}"]
        T[f"g_{k}"] = g
        T[f"r_{k}"] = (n - HS * s).astype(np.float32)
        T[f"t_{k}"] = (g + 0.0005 * s - 95.0).astype(np.float32)
        T[f"x_{k}"] = z[f"x_{k}"]
    return T


def walk(sig, T, ek, maxday, dsel):
    """Positions: one at a time (flat the minute after the exit), max trades a day; only days in dsel (ordinals)."""
    di = {d: i for i, d in enumerate(T["days"])}
    r, x = T[f"r_{ek}"], T[f"x_{ek}"]
    out = []
    sig = sig.sort_values(["day", "sig_min"], kind="stable")
    for d, g in sig.groupby("day", sort=True):
        o = d.toordinal()
        if o not in dsel or o not in di:
            continue
        i = di[o]
        flat, n = 0, 0
        for m, sd in zip(g.sig_min.values, g.side.values):
            if n >= maxday or m + 1 < flat:
                continue
            j, s = m - SM0, int(sd > 0)
            if np.isnan(r[i, j, s]):
                continue
            out.append((i, j, s))
            flat = int(x[i, j, s]) + 1
            n += 1
    if not out:
        return pd.DataFrame(columns=["i", "j", "s"])
    a = np.array(out)
    tr = pd.DataFrame(dict(i=a[:, 0], j=a[:, 1], s=a[:, 2]))
    for c in ("g", "r", "t"):
        tr[c] = T[f"{c}_{ek}"][tr.i, tr.j, tr.s].astype(np.float64)
    tr["prem"] = (T["e"][tr.i, tr.j, tr.s] * T["qty"][tr.i, tr.j, tr.s]).astype(np.float64)
    tr["day"] = [date.fromordinal(int(v)) for v in T["days"][tr.i]]
    tr["year"] = T["year"][tr.i]
    tr["why"] = ""
    return tr


def rand_p(tr, T, ek, dsel_idx, rng):
    """Time-of-day matched random entries: same minute, same side, random eligible day of the same year."""
    r = T[f"r_{ek}"]
    yrs = T["year"]
    draws = np.zeros((B_RAND, len(tr)))
    for (y, j, s), g in tr.groupby(["year", "j", "s"]):
        pool = dsel_idx[yrs[dsel_idx] == y]
        v = r[pool, j, s]
        v = v[~np.isnan(v)]
        if not len(v):
            continue
        pos = tr.index.get_indexer(g.index)
        draws[:, pos] = v[rng.integers(0, len(v), size=(B_RAND, len(g)))]
    mr = draws.mean(axis=1)
    real = tr.r.mean()
    return (1 + (mr >= real).sum()) / (B_RAND + 1), mr.mean()


def stats(tr, days_all):
    d = pd.Series(0.0, index=pd.Index(days_all))
    if len(tr):
        d = d.add(tr.groupby("day").r.sum(), fill_value=0.0)
    eq = d.cumsum().values
    dd = float((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min()) if len(eq) else 0.0
    mo = d.groupby([x.strftime("%Y-%m") for x in d.index]).sum()
    nd = len(days_all)
    return dict(trades=len(tr), days=nd, gross_day=tr.g.sum() / nd, theirs_day=tr.t.sum() / nd, ours_day=tr.r.sum() / nd,
                gross=tr.g.sum(), theirs=tr.t.sum(), ours=tr.r.sum(), per_trade=tr.r.mean() if len(tr) else np.nan,
                t=tr.r.mean() / (tr.r.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else np.nan,
                win=(tr.r > 0).mean() if len(tr) else np.nan, maxdd=dd, worst_day=d.min(), worst_month=mo.min(),
                green_months=(mo > 0).mean(), months=len(mo), prem_med=tr.prem.median() if len(tr) else np.nan), d


def run(mode):
    TN, TM = load(), load("tab_month.npz")
    T = TN
    ix = market().index("BANKNIFTY")
    days = T["days"]
    if mode == "check":
        lo, hi = W0, W1
    elif mode == "pre":
        lo, hi = date(2021, 1, 1), date(2025, 9, 30)
    else:
        lo, hi = HOLD, date(2026, 12, 31)
    sel = (days >= lo.toordinal()) & (days <= hi.toordinal())
    dsel = set(days[sel].tolist())
    dsel_idx = np.nonzero(sel)[0]
    days_all = [date.fromordinal(int(v)) for v in days[sel]]
    rng = np.random.default_rng(43)
    rows, daily, cache = [], {}, {}
    for s, tf, ek in sigs.variants():
        T = TM if s == "champM" else TN
        if (s, tf) not in cache:
            cache[(s, tf)] = sigs.make(ix, s, tf)
        tr = walk(cache[(s, tf)], T, ek, sigs.STRATS[s][0], dsel)
        st, dser = stats(tr, days_all)
        vid = f"{s}_{tf}m_{ek}"
        row = dict(vid=vid, strat=s, tf=tf, exit=ek, **st)
        if mode != "check" and len(tr):
            row["p_rand"], row["rand_per_trade"] = rand_p(tr, T, ek, dsel_idx, rng)
            for y, g in tr.groupby("year"):
                row[f"y{y}"] = g.r.sum()
                row[f"g{y}"] = g.g.sum()
        if mode == "hold":
            inw = np.array([W0 <= d <= W1 for d in days_all])
            ex = tr[[not (W0 <= d <= W1) for d in tr.day]] if len(tr) else tr
            row["ours_day_exwin"] = ex.r.sum() / max((~inw).sum(), 1)
            row["gross_day_exwin"] = ex.g.sum() / max((~inw).sum(), 1)
            row["trades_exwin"] = len(ex)
            row["ours_win_only"] = tr.r.sum() - ex.r.sum() if len(tr) else 0.0
        rows.append(row)
        daily[vid] = dser
        print(vid, {k: round(v, 1) if isinstance(v, float) else v for k, v in row.items() if k not in ("vid",)}, flush=True)
    res = pd.DataFrame(rows)
    if mode == "check":
        a = pd.read_csv(os.path.join(OUT, "partA.csv"))
        a = a[(a["mode"] == "hilo")]
        for c in ("ours", "theirs"):
            m = a[a.cost == c].set_index(["strat", "tf", "exit"])
            res[f"engine_{c}"] = [m.loc[(r.strat, r.tf, r.exit), "net"] if (r.strat, r.tf, r.exit) in m.index else np.nan
                                  for r in res.itertuples()]
            res[f"engine_n"] = [m.loc[(r.strat, r.tf, r.exit), "trades"] if (r.strat, r.tf, r.exit) in m.index else np.nan
                                for r in res.itertuples()]
        print(res[["vid", "trades", "engine_n", "ours", "engine_ours", "theirs", "engine_theirs"]].round(0).to_string())
        return
    X = pd.DataFrame(daily).values
    p = res.p_rand.fillna(1.0).values
    q = bh(np.r_[p, np.ones(max(M_BH - len(p), 0))])[:len(p)]
    res["q_bh40"] = q
    sp = spa(X, B=2000)
    res.to_csv(os.path.join(OUT, f"partB_{mode}.csv"), index=False)
    pd.DataFrame(daily).to_csv(os.path.join(OUT, f"partB_{mode}_daily.csv.gz"))
    with open(os.path.join(OUT, f"partB_{mode}_spa.json"), "w") as f:
        json.dump({k: (float(v) if np.isscalar(v) else str(v)) for k, v in sp.items()}, f)
    pd.set_option("display.width", 300)
    pd.set_option("display.max_columns", 50)
    cols = ["vid", "trades", "gross_day", "theirs_day", "ours_day", "per_trade", "t", "win", "p_rand", "rand_per_trade",
            "q_bh40", "maxdd"] + (["ours_day_exwin", "gross_day_exwin", "ours_win_only"] if mode == "hold" else []) + ["worst_month", "green_months"] + [c for c in res.columns if c.startswith("y")]
    print(res[cols].round(3).to_string())
    print("SPA/RC:", sp)


if __name__ == "__main__":
    run(sys.argv[1])
