"""R18 design run (PREREG.md): every arm x exit variant on the DESIGN sessions only; BH over all 60 tests; candidates.

    R18_SCRATCH=<scratch> python3 research/hunt/r18/run.py      # -> results/design.json (small summary, committed)

The holdout sessions are excluded before any trade is built (Grid.allowed), so nothing here reads them.
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict

import numpy as np

import arms as A
import config as K
import lib
from lib import VARIANTS, Grid


def split_sessions(g: Grid, hold: bool):
    cut, _ = K.periods(g.und, g.dates)
    if hold:
        return [d for d in g.dates if d >= cut]
    return [d for d in g.dates if K.is_design(d, g.und)]


def evaluate(g: Grid, trades, B=10000, twins=1000, folds=None):
    """Per variant: totals, day deltas vs V0, fold deltas, stress, twins. folds: list of session sets."""
    base = lib.run_variant(g, trades, "V0")
    base2 = lib.run_variant(g, trades, "V0", hs_mult=K.STRESS_SPREAD)
    days = [g.dates[t.i] for t in trades]
    out = {}
    rng = np.random.default_rng(K.SEED)
    for v in VARIANTS:
        res = base if v == "V0" else lib.run_variant(g, trades, v)
        net = np.array([r["net"] for r in res])
        gross = np.array([r["gross"] for r in res])
        mfe = np.array([r["mfe"] for r in res])
        daily = defaultdict(float)
        dd = defaultdict(float)
        for d, r, b in zip(days, res, base):
            daily[d] += r["net"]
            dd[d] += r["net"] - b["net"]
        ds = sorted(daily)
        dvec = np.array([daily[d] for d in ds])
        delta = np.array([dd[d] for d in ds])
        changed = [j for j, (r, b) in enumerate(zip(res, base)) if r["exit_min"] != b["exit_min"] or abs(r["net"] - b["net"]) > 1e-6]
        row = dict(trades=len(trades), days=len(ds), net=round(float(net.sum())), win=round(float((net > 0).mean() * 100), 1) if len(net) else None,
                   charges=round(float(sum(r["charges"] for r in res))),
                   mfe_capture=round(float(gross.sum() / mfe.sum() * 100), 1) if mfe.sum() > 0 else None,
                   worst_day=round(float(dvec.min())) if len(dvec) else None, max_dd=round(lib.max_dd(dvec)),
                   changed=len(changed), whys=dict(sorted(_count(r["why"] for r in res).items())))
        if v != "V0":
            row["delta"] = round(float(delta.sum()))
            row["p"] = lib.boot_p(delta, B=B)
            if folds:
                row["folds"] = [round(float(sum(dd[d] for d in ds if d in f))) for f in folds]
            st = lib.run_variant(g, trades, v, hs_mult=K.STRESS_SPREAD)
            row["delta_2x_spread"] = round(float(sum(r["net"] for r in st) - sum(r["net"] for r in base2)))
            row["twin_p"] = twin_p(trades, res, base, changed, rng, twins)
        out[v] = row
    return out


def _count(it):
    c = defaultdict(int)
    for x in it:
        c[x] += 1
    return c


def twin_p(trades, res, base, changed, rng, n):
    """The changed trades exited instead at a random minute between the entry and the V0 exit (market, next open)."""
    if not changed:
        return 1.0
    total = sum(r["net"] for r in res)
    fixed = total - sum(res[j]["net"] for j in changed)
    opts = []
    for j in changed:
        t, b = trades[j], base[j]
        hi = max(t.c0 + 1, min(b["exit_min"], t.sq))
        vals = []
        for m in range(t.c0, hi):
            p, _ = lib.next_open(t.path, m, t.sq)
            x = lib.sell_px(p, t.hs, hero=t.hero)
            vals.append((x - t.e) * t.qty - lib.charges(True, t.e, t.qty) - lib.charges(False, x, t.qty))
        opts.append(np.array(vals))
    sims = np.array([fixed + sum(o[rng.integers(0, len(o))] for o in opts) for _ in range(n)])
    return float((1 + np.sum(sims >= total)) / (n + 1))


def main(which=None):
    grids = {u: Grid(u) for u in K.UNDS}
    folds = {}
    for u, g in grids.items():
        des = split_sessions(g, hold=False)
        g.allowed = set(des)
        folds[u] = [set(x) for x in np.array_split(np.array(des, dtype=object), K.N_FOLDS)]
    out = {}
    for arm in which or A.ARMS:
        g = grids[A.UND[arm]]
        trades = A.build(arm, grids)
        out[arm] = evaluate(g, trades, folds=folds[g.und])
        v0 = out[arm]["V0"]
        print(f"{A.NAMES[arm]:42} trades {v0['trades']:5} net {v0['net']:>10,} | " +
              " ".join(f"{v}:{out[arm][v]['delta']:+,}" for v in VARIANTS[1:]), flush=True)
    # BH over every arm x variant
    keys = [(a, v) for a in out for v in VARIANTS[1:]]
    q = lib.bh([out[a][v]["p"] for a, v in keys])
    for (a, v), qq in zip(keys, q):
        r = out[a][v]
        r["q"] = round(float(qq), 4)
        r["candidate"] = bool(qq < K.BH_Q and r["delta"] > 0 and sum(x > 0 for x in r["folds"]) >= 3
                              and r["delta_2x_spread"] > 0 and r["twin_p"] < 0.10)
    meta = dict(source=K.SOURCE, tests=len(keys), design_cut={u: str(K.periods(u, grids[u].dates)[0]) for u in grids},
                sessions={u: len(split_sessions(grids[u], False)) for u in grids},
                fold_bounds={u: [(str(min(f)), str(max(f))) for f in folds[u]] for u in grids})
    K.RESULTS.mkdir(exist_ok=True)
    with open(K.RESULTS / f"design_{K.SOURCE}.json", "w") as f:
        json.dump(dict(meta=meta, arms=out), f, indent=1, default=str)
    print("candidates:", [(a, v) for a, v in keys if out[a][v]["candidate"]])


if __name__ == "__main__":
    main(sys.argv[1:] or None)
