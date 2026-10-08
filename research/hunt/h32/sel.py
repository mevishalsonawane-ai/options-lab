"""h32 rule selection on DISCOVERY data only (first day .. 2023-12-31), by a criterion fixed in PREREG Part B.

Conditions: each feature's bottom quintile (Q1) or top quintile (Q5), cut-points per index from discovery.
Stratified stats vs the index x contract-type base rates (so BANKNIFTY's easy +20 does not dominate):
  liftJ = events / expected events, liftK = drops / expected drops, wsh = J / (J + K) vs stratum base share.
Pick: the 6 singles with n_days >= 150 and liftJ >= 1.15 that have the highest (liftJ - liftK) (= more jumps than
drops, relative to normal); then the 3 best pairwise ANDs among those 6 (n_days >= 80) by the same score.
Writes rules.json + sel_*.csv.
"""
from __future__ import annotations

import itertools
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from common import DISC_END, FEATS, OUT, UNDS, crash, event, load  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

df = load()
d = df[df.day < DISC_END].reset_index(drop=True)
J, K = event(d), crash(d)
strat = d.und.astype(int) * 4 + d.typ.astype(int)
bJ = pd.Series(J).groupby(strat).mean()
bK = pd.Series(K).groupby(strat).mean()
eJ, eK = strat.map(bJ).values, strat.map(bK).values
cuts = {}
for f in FEATS:
    cuts[f] = {}
    for u in d.und.unique():
        x = d.loc[d.und == u, f].values
        x = x[np.isfinite(x)]
        if len(x) < 1000 or np.unique(x).size < 3:
            continue
        cuts[f][UNDS[u]] = [float(np.quantile(x, 0.2)), float(np.quantile(x, 0.8))]


def mask(frame, conds):
    m = np.ones(len(frame), bool)
    for f, tail in conds:
        x = frame[f].values
        lo = frame.und.map({UNDS.index(u): c[0] for u, c in cuts[f].items()}).values
        hi = frame.und.map({UNDS.index(u): c[1] for u, c in cuts[f].items()}).values
        m &= (x <= lo) if tail == "Q1" else (x >= hi)
    return m


def stats(m, conds):
    nd = len(np.unique(d.day.values[m] * 10 + d.und.values[m]))
    sJ, sK = J[m].sum(), K[m].sum()
    lJ, lK = sJ / max(eJ[m].sum(), 1e-9), sK / max(eK[m].sum(), 1e-9)
    return dict(rule=" & ".join(f"{f} {t}" for f, t in conds), n=int(m.sum()), n_days=nd, pJ=J[m].mean(), pK=K[m].mean(),
                liftJ=lJ, liftK=lK, score=lJ - lK, recall=sJ / J.sum())


rows = []
for f in FEATS:
    if not cuts[f]:
        continue
    for t in ("Q1", "Q5"):
        if f == "exp":
            continue
        rows.append(dict(**stats(mask(d, [(f, t)]), [(f, t)]), conds=[(f, t)]))
# expiry day as a binary condition
m = d.exp.values == 1
rows.append(dict(**stats(m, [("exp", "1")]), conds=[("exp", "1")]))
sg = pd.DataFrame(rows).sort_values("score", ascending=False)
sg.drop(columns="conds").to_csv(os.path.join(OUT, "sel_singles.csv"), index=False)
print(sg.drop(columns="conds").round(3).to_string())
ok = sg[(sg.n_days >= 150) & (sg.liftJ >= 1.15)]
top = []
for _, r in ok.iterrows():
    if r.conds[0][0] in [c[0][0] for c in top]:
        continue
    top.append(r.conds)
    if len(top) == 6:
        break
pr = []
for a, b in itertools.combinations(top, 2):
    conds = a + b
    mm = (d.exp.values == 1) if False else None
    m = np.ones(len(d), bool)
    for c in conds:
        m &= (d.exp.values == 1) if c[0] == "exp" else mask(d, [c])
    pr.append(dict(**stats(m, conds), conds=conds))
pr = pd.DataFrame(pr).sort_values("score", ascending=False)
pr.drop(columns="conds").to_csv(os.path.join(OUT, "sel_pairs.csv"), index=False)
print(pr.drop(columns="conds").round(3).to_string())
pairs = pr[pr.n_days >= 80].head(3).conds.tolist()
rules = [dict(name=f"R{i + 1}", conds=c) for i, c in enumerate(top + pairs)]
json.dump(dict(rules=rules, cuts=cuts), open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "rules.json"), "w"), indent=1)
for r in rules:
    print(r["name"], r["conds"])
