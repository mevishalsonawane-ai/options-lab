"""h15 stage 1: per-trade outcome tables as a function of lots, for the Liquidity 15+5 trade list (h10 Book) under
h14's adopted execution (E2 limit entry +0.5%, model M2) and h10's market baseline (E0/M1), kappa 0.01/0.02/0.04.

For every trade i and every lot count n = 1..NMAX[und]: net, gross, premium paid, filled lots, exit-end minute.
The capital simulator (cap1l.py) then only has to pick n per trade from the running capital.

    OBUY_CACHE=<scratch>/hunt/h15/cache flock <scratch>/obuy.lock python3 -I research/hunt/h15/tab.py
(packs: OBUY_CACHE=<scratch>/hunt/h15/cache python3 -I research/hunt/h7/build.py)
"""
from __future__ import annotations

import os
import pickle
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h14"))
import exe  # noqa: E402
import numpy as np  # noqa: E402
from exe import cap, sim  # noqa: E402

UNDS = ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]
NMAX = dict(BANKNIFTY=100, FINNIFTY=3, MIDCPNIFTY=11)     # h10 practical BN ceiling; FIN/MIDCP = h10 capacity
RUNS = {"E2_k02": ("E2", "M2", 0.02), "E2_k04": ("E2", "M2", 0.04), "E2_k01": ("E2", "M2", 0.01),
        "E0_k02": ("E0", "M1", 0.02)}
OUT = os.path.join(sim.C.CACHE, "h15")


def main(which=("real", "pool")):
    os.makedirs(OUT, exist_ok=True)
    P = sim.load()
    trs = {"real": sim.base_trades(P["real"][0], cap.EXE),
           "pool": sim.base_trades(P["real"][1], cap.EXE, pos=False)}
    for kind in which:
        pk = P["real"][0] if kind == "real" else P["real"][1]
        tr = trs[kind]
        res = {}
        for u in UNDS:
            t0 = time.time()
            b = cap.Book(pk, tr[tr.und == u])
            legs = dict(itm=exe.leg_itm(b), atm=None, dhat=None)
            cols = ["cand", "und", "book", "day", "entry_min", "exit_min", "lot", "why", "entry", "exit"]
            if kind == "pool":
                cols.append("parent")
            meta = b.tr[[c for c in cols if c in b.tr]].copy()
            runs = RUNS if kind == "real" else {"E2_k02": RUNS["E2_k02"], "E2_k04": RUNS["E2_k04"]}
            d = {}
            for rn, (pol, model, kap) in runs.items():
                N = NMAX[u]
                arr = {k: np.zeros((len(meta), N + 1)) for k in ("net", "gross", "prem", "lots", "end", "chg")}
                for n in range(1, N + 1):
                    o = exe.simulate(b, legs, n, dict(entry=pol, exit="X0", split=False), model, kap)
                    arr["net"][:, n] = o.net.values
                    arr["gross"][:, n] = o.gross.values
                    arr["prem"][:, n] = o.prem.values
                    arr["lots"][:, n] = o.lots.values
                    arr["end"][:, n] = o.exit_end_min.values
                    arr["chg"][:, n] = o.charges.values
                d[rn] = arr
            res[u] = (meta.reset_index(drop=True), d)
            print(kind, u, len(meta), "trades", round(time.time() - t0, 1), "s", flush=True)
        with open(os.path.join(OUT, f"tab_{kind}.pkl"), "wb") as f:
            pickle.dump(res, f, protocol=4)


if __name__ == "__main__":
    main(tuple(sys.argv[1:]) or ("real", "pool"))
