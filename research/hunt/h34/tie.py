"""h34 tie check (pre-holdout): how often the premium target and the stop (or lock) fall in the SAME 1-minute bar, so
the engine's conservative "stop first" rule decides the trade, and what it would cost vs "target first" (upper bound).
Exits are checked on the option's own 1-minute HIGH / LOW (obuy intrabar=True) in every single-leg h34 run.

    flock <scratch>/obuy.lock python3 -I research/hunt/h34/tie.py      (random entries: 9 per index-day, ATM + 1-ITM)
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core as K  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import rand as RD  # noqa: E402
from obuy.engine import Execution, prepare_many, run_exits  # noqa: E402

rows = []
exe = K.exe_h34(Execution(expiry="allow"))
for u in K.UNDS5:
    sig = RD.signals(u)
    sig = sig[sig.book.isin([f"r{k}" for k in range(9)])].reset_index(drop=True)
    for ri, (pk, _) in enumerate(prepare_many([(sig, r, exe, 0, None, False) for r in K.RULES])):
        for a in range(0, len(pk), 6000):
            sub = K._Sub(pk, slice(a, min(a + 6000, len(pk))))
            n = len(sub.meta)
            H = sub.get("H", None)
            pos = {c: i for i, c in enumerate(sub.meta.cand.values)}
            for xi, ex in enumerate(K.MENU):
                tr = run_exits(sub, slice(0, n), ex, exe)
                m = tr.why.isin(["stop", "lock"]).values
                rr = np.array([pos[c] for c in tr.cand.values])
                col = tr.exit_min.values - 555
                tie = m & (H[rr, col] >= tr.entry.values + ex.tgt_pts - 1e-9)
                tgt_fill = tr.entry.values + ex.tgt_pts
                cost = np.where(tie, (tgt_fill - tr.exit.values) * tr.qty.values, 0.0)
                rows.append(dict(und=u, rule=K.RLAB[ri], xi=xi, exit=K.MLAB[xi], n=len(tr), ties=int(tie.sum()),
                                 cost=float(cost.sum()), net=float(tr.net.sum())))
        print(u, K.RLAB[ri], "done", flush=True)
D = pd.DataFrame(rows).groupby(["und", "rule", "xi", "exit"], as_index=False)[["n", "ties", "cost", "net"]].sum()
D.to_csv(os.path.join(K.OUT, "ties.csv"), index=False)
t = D[["n", "ties", "cost"]].sum()
print(f"ALL: {int(t.n):,} trade-exits, ties {int(t.ties):,} ({t.ties / t.n:.2%}), cost if ties went to target "
      f"Rs {t.cost / t.n:.1f} per trade")
for k, g in D.assign(stop=D.exit.str.split("/").str[1], tgt=D.exit.str.split("/").str[0]).groupby(["tgt", "stop"]):
    s = g[["n", "ties", "cost"]].sum()
    print(k, f"ties {s.ties / s.n:.2%}, Rs {s.cost / s.n:.1f}/trade")
for k, g in D.groupby("und"):
    s = g[["n", "ties", "cost"]].sum()
    print(k, f"ties {s.ties / s.n:.2%}, Rs {s.cost / s.n:.1f}/trade")
