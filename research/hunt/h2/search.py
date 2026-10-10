"""In-sample search (2024-10-07 .. 2025-09-30 only; the holdout is never touched here).

    python3 -I research/hunt/h2/search.py
Writes <cache>/../ins_daily_{net,gross}.npy, ins_variants.csv.
"""
import os
import sys
import time

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.insert(1, os.path.dirname(os.path.dirname(HERE)))   # research/ for obuy
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import lib  # noqa: E402
import strategies as ST  # noqa: E402

OUT = os.path.join(lib.SCR, "hunt", "h2")
NOTIONAL = 500_000.0

t0 = time.time()
m = lib.Mkt()
print("loaded", round(time.time() - t0), "s", flush=True)
dmask = m.ism.copy()
ins_days = np.nonzero(dmask)[0]
lab = ST.clusters(m)
np.save(os.path.join(OUT, "clusters.npy"), lab)
V = ST.grid()
NET = np.zeros((len(ins_days), len(V)))
GRO = np.zeros_like(NET)
rows = []
pos = {d: i for i, d in enumerate(ins_days)}
for vi, (fam, p) in enumerate(V):
    sig = ST.build(m, fam, p, dmask, lab)
    if len(sig["s"]) == 0:
        rows.append(dict(vid=vi, fam=fam, params=str(p), n=0))
        continue
    tr = lib.simulate(m, sig["s"], sig["d"], sig["te"], sig["stop"], sig["tgt"], sig["tx"])
    tr = tr[np.isfinite(tr.ent) & np.isfinite(tr.ex) & (tr.ent > 0)]
    tr = lib.pnl(m, tr, NOTIONAL)
    di = tr.d.map(pos).to_numpy()
    np.add.at(NET[:, vi], di, tr.net.to_numpy())
    np.add.at(GRO[:, vi], di, tr.gross.to_numpy())
    # concurrency: max simultaneous open positions in a day
    ev = pd.concat([pd.DataFrame(dict(d=tr.d, t=tr.te, x=1)), pd.DataFrame(dict(d=tr.d, t=tr.xb, x=-1))])
    ev = ev.sort_values(["d", "t", "x"])
    conc = ev.groupby("d").x.cumsum().groupby(ev.d).max().max()
    r = tr.net / tr.notional * 1e4
    rows.append(dict(vid=vi, fam=fam, params=str(p), n=len(tr), tpd=len(tr) / len(ins_days),
                     gross_bps=float((tr.gross / tr.notional * 1e4).mean()), net_bps=float(r.mean()),
                     win=float((tr.net > 0).mean()), max_conc=int(conc),
                     stop_rate=float((tr.why == 1).mean()), tgt_rate=float((tr.why == 2).mean())))
    if vi % 20 == 0:
        print(vi, fam, p, len(tr), round(rows[-1]["net_bps"], 1), round(time.time() - t0), flush=True)
df = pd.DataFrame(rows)
T = len(ins_days)
for k, X in (("net", NET), ("gross", GRO)):
    mu, sd = X.mean(0), X.std(0, ddof=1)
    df[f"{k}_day"] = mu
    df[f"{k}_sharpe"] = np.where(sd > 0, mu / sd * np.sqrt(250), 0)
np.save(os.path.join(OUT, "ins_daily_net.npy"), NET)
np.save(os.path.join(OUT, "ins_daily_gross.npy"), GRO)
np.save(os.path.join(OUT, "ins_days.npy"), m.days[ins_days])
df.to_csv(os.path.join(OUT, "ins_variants.csv"), index=False)
print(df.sort_values("net_sharpe", ascending=False).head(25).to_string())
print("done", round(time.time() - t0), "s")
