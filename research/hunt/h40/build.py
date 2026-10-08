"""h40 option pass (heavy): every option-era session x index x entry (T1 = 09:16 open, T2 = 09:30 open) x side
(CE, PE), 1-ITM nearest expiry, expiry days allowed, app fills + app charges, 1 lot, under the 16 PREREG exits.
Spread is NOT applied here (test.py adds hs x (entry + exit) x qty). Holdout rows are written but only read once.

    OBUY_CACHE=<scratch>/hunt/h40/cache flock <scratch>/obuy.lock python3 -I research/hunt/h40/build.py
"""
from __future__ import annotations

import os
import sys
import time

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, Exits, StrikeRule, prepare_many  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h40")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
TIMES = {"T1": 9 * 60 + 15, "T2": 9 * 60 + 29}     # signal minute; fill at the next minute's open
SQ = 15 * 60 + 10
EXITS = {"X1015": Exits(sq_off=10 * 60 + 15), "X1115": Exits(sq_off=11 * 60 + 15), "X1510": Exits(sq_off=SQ),
         "LIQ": Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, sq_off=SQ)}
for t in (15, 20, 25, 30):
    for s in (10, 15, 20):
        EXITS[f"P{t}S{s}"] = Exits(tgt_pts=t, stop_pts=s, sq_off=SQ)
RULE = StrikeRule(1, series="near")
EXE = Execution(expiry="allow")


def main():
    t0 = time.time()
    mk = market()
    allt, days = [], []
    for und in UNDS:
        ix = mk.index(und)
        M = ix.mat()
        sig = []
        for p, d in enumerate(ix.days):
            c = M["c"][p]
            o = M["o"][p]
            ok = np.nonzero(np.isfinite(o))[0]
            if not len(ok):
                continue
            days.append(dict(und=und, day=d, o0915=o[ok[0]], c1015=c[60 - 1] if np.isfinite(c[59]) else np.nan,
                             c1115=c[119], c1509=c[354], c0929=c[14]))
            for tn, sm in TIMES.items():
                for side in (1, -1):
                    sig.append(dict(und=und, day=d, sig_min=sm, side=side, book=f"{und}_{d}_{tn}_{side}", tag=tn))
        sig = pd.DataFrame(sig)
        print(und, "signals", len(sig), f"{time.time() - t0:.0f}s", flush=True)
        (pk, _), = prepare_many([(sig, RULE, EXE, 0, None, False)])
        print(und, "packed", len(pk), f"{time.time() - t0:.0f}s", flush=True)
        for xn, ex in EXITS.items():
            tr = pk.run(ex, EXE)
            tr = tr[["und", "day", "side", "qty", "tag", "entry_min", "exit_min", "entry", "exit", "why", "gross",
                     "charges", "net"]].copy()
            tr["X"] = xn
            allt.append(tr.rename(columns={"tag": "T"}))
        mk.release(und)
        del pk
    tr = pd.concat(allt, ignore_index=True)
    for c in ("entry", "exit", "gross", "charges", "net"):
        tr[c] = tr[c].astype(np.float32)
    for c in ("und", "T", "X", "why"):
        tr[c] = tr[c].astype("category")
    tr.to_parquet(os.path.join(OUT, "opt_trades.parquet"))
    pd.DataFrame(days).to_parquet(os.path.join(OUT, "index_days.parquet"))
    print("rows", len(tr), f"{time.time() - t0:.0f}s")
    print(tr.groupby(["und", "X"], observed=True).size().unstack().to_string())


if __name__ == "__main__":
    main()
