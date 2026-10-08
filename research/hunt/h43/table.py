"""h43 stage B1 (heavy): BANKNIFTY ATM nearest-expiry outcome table. For every non-expiry day, every signal minute
09:15..15:13 and both sides: buy the ATM option at the next minute's open (obuy engine, app fills + app charges, today's
lot = 30) and run the report's 5 exit sets (30% stop / 60% target on the option's 1-min high/low, stop first on ties;
time stops 40/15/30 min or none; 120% target variant; square-off 15:15). Any signal set, and the time-of-day matched
random baseline, is then a lookup.

    flock <scratch>/obuy.lock python3 -I research/hunt/h43/table.py

Writes <scratch>/hunt/h43/tab.npz: days (ordinals), year, e, qty  (D, M, 2), and per exit k: g_k gross, n_k net
(app charges, before spread), s_k (entry+exit)*qty, x_k exit minute.  M = 359 minutes (555..913), side 0=PE 1=CE.
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import StrikeRule, prepare_many  # noqa: E402
from partA import EXE_APP, EXITS  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h43")
SM0, SM1 = 555, 15 * 60 + 13
M = SM1 - SM0 + 1


SERIES = os.environ.get("H43_SERIES", "near")
ONLY = os.environ.get("H43_EXITS")


def build(limit_days=None):
    t0 = time.time()
    mk = market()
    ix = mk.index("BANKNIFTY")
    days = [d for d in ix.days if not ix.d[d]["exp"]]
    if limit_days:
        days = days[-limit_days:]
    D = len(days)
    di = {d: i for i, d in enumerate(days)}
    mins = np.arange(SM0, SM1 + 1)
    sig = pd.DataFrame({"und": "BANKNIFTY", "day": np.repeat(np.array(days, dtype=object), M * 2),
                        "sig_min": np.tile(np.repeat(mins, 2), D), "side": np.tile(np.array([-1, 1]), D * M)})
    sig["book"] = "all"
    (pk, _), = prepare_many([(sig, StrikeRule(money=0, series=SERIES), EXE_APP, 0, None, False)])
    print("prepared", len(sig), "->", len(pk), f"{time.time() - t0:.0f}s", flush=True)
    meta = pk.meta
    dpos = meta.day.map(di).values.astype(np.int64)
    mpos = meta.sig_min.values.astype(np.int64) - SM0
    spos = (meta.side.values > 0).astype(np.int64)
    flat = (dpos * M + mpos) * 2 + spos
    shp = (D, M, 2)
    res = dict(days=np.array([d.toordinal() for d in days], dtype=np.int32),
               year=np.array([d.year for d in days], dtype=np.int16))
    for nm, v in (("e", meta.e.values), ("qty", meta.qty.values)):
        a = np.full(D * M * 2, np.nan, np.float32)
        a[flat] = v
        res[nm] = a.reshape(shp)
    cpos = pd.Series(np.arange(len(meta)), index=meta.cand.values)
    for k, ex in EXITS.items():
        if ONLY and k not in ONLY.split(','):
            continue
        t1 = time.time()
        tr = pk.run(ex, EXE_APP, chunk=6000)
        f = flat[cpos.loc[tr.cand.values].values]
        for nm, vals in (("g", tr.gross.values), ("n", tr.net.values),
                         ("s", (tr.entry.values + tr.exit.values) * tr.qty.values)):
            a = np.full(D * M * 2, np.nan, np.float32)
            a[f] = vals
            res[f"{nm}_{k}"] = a.reshape(shp)
        a = np.full(D * M * 2, -1, np.int16)
        a[f] = tr.exit_min.values
        res[f"x_{k}"] = a.reshape(shp)
        print(k, len(tr), f"gross {tr.gross.mean():.1f} net {tr.net.mean():.1f}", tr.why.value_counts().to_dict(),
              f"{time.time() - t1:.0f}s", flush=True)
    path = os.path.join(OUT, f"tab{'' if SERIES == 'near' else '_' + SERIES}{'_test' if limit_days else ''}.npz")
    np.savez_compressed(path, **res)
    print("saved", path, f"{os.path.getsize(path) / 1e6:.0f} MB", f"total {time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    build(int(sys.argv[1]) if len(sys.argv) > 1 else None)
