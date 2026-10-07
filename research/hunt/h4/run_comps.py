"""Run every h4 component (comps.py) under three executions in ONE data pass and save the trades.

    OBUY_CACHE=<scratch>/hunt/h4/cache flock <scratch>/obuy.lock python3 -I research/hunt/h4/run_comps.py

Executions (same signals, strikes and exits):
  gross  fills at the bar print (no slippage), no charges              -> Boss's headline
  app    the app's paper fills (+-5 bps, stops -10 bps) + SandboxCosts  -> what the app's paper account shows
  real   'liq' fills (app bps PLUS a 1-4 tick half-spread from the contract's last-5-min volume) + SandboxCosts
Random-entry pools (5 per signal, same day / book / exits / strike rule) are built for the 'real' execution.
Output: <OBUY_CACHE>/h4/trades_<exe>.parquet, pool_real.parquet.
"""
from __future__ import annotations

import dataclasses
import os
import pickle
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)

import obuy  # noqa: E402,F401
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import positions, prepare_many  # noqa: E402
import comps  # noqa: E402

EXES = {"gross": (Fills(mode="flat", pts=0.0), Costs(mode="flat", per_order=0.0)),
        "app": (Fills(), Costs()),
        "real": (Fills(mode="liq"), Costs())}
KEEP = ["cand", "und", "book", "day", "year", "sig_min", "gate", "entry_min", "exit_min", "side", "strike", "lot", "qty",
        "entry", "exit", "why", "gross", "charges", "net", "risk_rs", "R"]
OUT = os.path.join(C.CACHE, "h4")


def sigs(comp, st, si, sp):
    p = os.path.join(OUT, f"sig_{comp}_{si}.pkl")
    if os.path.exists(p):
        return pd.read_pickle(p)
    s = st.signal_fn(market(), **sp)
    market().release()
    s.to_pickle(p)
    return s


def main():
    os.makedirs(OUT, exist_ok=True)
    t0 = time.time()
    jobs, meta = [], []
    for comp, st in comps.components():
        for si, sp in enumerate(st.sig_grid):
            s = sigs(comp, st, si, sp)
            print(time.strftime("%H:%M:%S"), comp, si, sp, len(s), flush=True)
            for ri, rule in enumerate(st.rules):
                for en, (fl, co) in EXES.items():
                    exe = dataclasses.replace(st.exe, fills=fl, costs=co)
                    jobs.append((s, rule, exe, 5 if en == "real" else 0, st.window, st.pool_same_side))
                    meta.append((comp, st, si, ri, en, exe))
    print("jobs", len(jobs), flush=True)
    packs = prepare_many(jobs)
    print(time.strftime("%H:%M:%S"), "packs ready", time.time() - t0, flush=True)
    res = {en: [] for en in EXES}
    pools = []
    for (pk, pool), (comp, st, si, ri, en, exe) in zip(packs, meta):
        for xi, ex in enumerate(st.exits):
            vid = f"{comp}|s{si}|r{ri}|x{xi}"
            if not len(pk):
                continue
            tr = pk.run(ex, exe)
            if not len(tr):
                continue
            tr = positions(tr, **st.pos)
            tr = tr[[c for c in KEEP if c in tr.columns]].assign(comp=comp, vid=vid)
            res[en].append(tr)
            if en == "real" and pool is not None and len(pool):
                pt = pool.run(ex, exe)
                pools.append(pt[["parent", "und", "book", "day", "net", "gross"]].assign(vid=vid))
    for en, lst in res.items():
        df = pd.concat(lst, ignore_index=True)
        df["day"] = pd.to_datetime(df["day"])
        df.to_parquet(os.path.join(OUT, f"trades_{en}.parquet"))
        print(en, len(df), df.groupby("comp").net.sum().round(0).to_dict(), flush=True)
    P = pd.concat(pools, ignore_index=True)
    P["day"] = pd.to_datetime(P["day"])
    P.to_parquet(os.path.join(OUT, "pool_real.parquet"))
    print("done", time.time() - t0)


if __name__ == "__main__":
    main()
