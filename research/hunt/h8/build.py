"""Build h8 trades: Liquidity 15+5 long-only cash on all 214 F&O stocks (+ index spot runs), with 5 random entries
per kept trade (identical exits). Output: scratchpad/hunt/h8/{sig,rand}_stocks.parquet, {sig,rand}_index.parquet.

    flock <lock> python3 -I research/hunt/h8/build.py [stocks|index]
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

import liqcash as LC  # noqa: E402

what = sys.argv[1] if len(sys.argv) > 1 else "stocks"
os.makedirs(LC.OUT, exist_ok=True)

if what == "stocks":
    names = sorted(os.listdir(os.path.join(LC.DATA, "candles", "minute", "NSE_EQ")))
    S, R = [], []
    t0 = time.time()
    for k, nm in enumerate(names):
        try:
            df = LC.load_minutes("NSE_EQ", nm)
            s, r = LC.run_instrument(df, LC.stock_istop, seed=k)
        except Exception as ex:  # noqa: BLE001
            print("FAIL", nm, ex, flush=True)
            continue
        if len(s):
            s["sym"] = nm; r["sym"] = nm
            S.append(s); R.append(r)
        if k % 10 == 0:
            print(k, nm, len(s), f"{time.time() - t0:.0f}s", flush=True)
    keep = ["sym", "day", "book", "done", "ce", "xc", "e", "x", "why", "istop", "idx_stop", "target", "exit_at",
            "level", "adv", "atr"]
    pd.concat(S)[keep].to_parquet(os.path.join(LC.OUT, "sig_stocks.parquet"))
    pd.concat(R)[keep + ["rep"]].to_parquet(os.path.join(LC.OUT, "rand_stocks.parquet"))
else:
    # index spot: the app's own index stops (pts), books as the app (FINNIFTY 30+5), same cash-translated exits
    IST = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "MIDCPNIFTY": 8.0, "NIFTY": 15.0}
    BK = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5), "MIDCPNIFTY": (15, 5), "NIFTY": (15, 5)}
    S, R = [], []
    for k, u in enumerate(IST):
        df = LC.load_minutes("IDX_I", u)
        df = df[df.day >= "2021-09-15"]
        s, r = LC.run_instrument(df, lambda q, a, p, v=IST[u]: v, books=BK[u], seed=100 + k)
        s["sym"] = u; r["sym"] = u
        S.append(s); R.append(r)
        print(u, len(s), flush=True)
    pd.concat(S).to_parquet(os.path.join(LC.OUT, "sig_index.parquet"))
    pd.concat(R).to_parquet(os.path.join(LC.OUT, "rand_index.parquet"))
print("done")
