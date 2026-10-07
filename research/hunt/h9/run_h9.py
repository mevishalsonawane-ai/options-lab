"""h9: Liquidity 15+5 (rules unchanged) on extra level timeframes. One data pass, three executions, random pools.

    OBUY_CACHE=<scratch>/hunt/h9/cache flock <scratch>/obuy.lock python3 -I research/hunt/h9/run_h9.py

Books (PREREG.md): every index x {3,5,10,15,30,60}-min from the h4 re-implementation (comps.liq_ext_signals), plus the
validated port for the existing BANKNIFTY/FINNIFTY books (used for the 'existing' set, as in h4).
Output <OBUY_CACHE>/h9/: trades_<exe>.parquet (src = port | ext), pool_real.parquet, vol_real.parquet.
"""
from __future__ import annotations

import dataclasses
import os
import sys
import time
from multiprocessing import Pool

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, RES)
sys.path.insert(0, os.path.join(RES, "hunt", "h4"))

import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, Fills  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, StrikeRule, positions, prepare_many  # noqa: E402
from obuy.strategies import liquidity as LQ  # noqa: E402
import comps  # noqa: E402

TFS = (3, 5, 10, 15, 30, 60)
UNDS = ("BANKNIFTY", "FINNIFTY", "MIDCPNIFTY")
EXES = {"gross": (Fills(mode="flat", pts=0.0), Costs(mode="flat", per_order=0.0)),
        "app": (Fills(), Costs()),
        "real": (Fills(mode="liq"), Costs())}
KEEP = ["cand", "und", "book", "day", "sig_min", "gate", "entry_min", "exit_min", "side", "strike", "lot", "qty",
        "entry", "exit", "why", "gross", "charges", "net"]
OUT = os.path.join(C.CACHE, "h9")


def sig_one(und):
    p = os.path.join(OUT, f"sig_ext_{und}.pkl")
    if os.path.exists(p):
        return pd.read_pickle(p)
    comps.BOOKS_EXT[und] = TFS
    s = comps.liq_ext_signals(market(), unds=(und,))
    s.to_pickle(p)
    return s


def main():
    os.makedirs(OUT, exist_ok=True)
    t0 = time.time()
    with Pool(3) as pl:
        ext = pd.concat(pl.map(sig_one, UNDS), ignore_index=True)
    print(time.strftime("%H:%M:%S"), "ext signals", len(ext), ext.groupby("book").size().to_dict(), flush=True)
    pp = os.path.join(OUT, "sig_port.pkl")
    if os.path.exists(pp):
        port = pd.read_pickle(pp)
    else:
        port = LQ.signals(market(), room=1.0)
        port.to_pickle(pp)
    print("port signals", len(port), port.groupby("book").size().to_dict(), flush=True)
    rule = StrikeRule(money=1)
    base = Execution(expiry="skip")
    win = (comps.WIN_FROM - 1, comps.WIN_TO - 1)
    jobs, meta = [], []
    for src, s in (("ext", ext), ("port", port)):
        for en, (fl, co) in EXES.items():
            exe = dataclasses.replace(base, fills=fl, costs=co)
            jobs.append((s, rule, exe, 5 if en == "real" else 0, win, False))
            meta.append((src, en, exe))
    packs = prepare_many(jobs)
    print(time.strftime("%H:%M:%S"), "packs ready", round(time.time() - t0), flush=True)
    res = {en: [] for en in EXES}
    pools, vols = [], []
    for (pk, pool), (src, en, exe) in zip(packs, meta):
        tr = pk.run(LQ.ARM_EXITS, exe)
        tr = positions(tr, one_at_a_time=True)
        tr = tr[[c for c in KEEP if c in tr.columns]].assign(src=src)
        res[en].append(tr)
        if en == "real":
            # fill realism: option volume (lots) in the 5 minutes before entry and around the exit minute
            m = pk.meta
            V = pk.get("V", slice(0, len(pk)))
            c0 = m.c0.values.astype(int)
            ve = np.array([np.nansum(V[i, max(c - 5, 0):c]) for i, c in enumerate(c0)]) / m.lot.values
            vols.append(pd.DataFrame(dict(src=src, cand=m.cand.values, vol5_entry_lots=ve, row=np.arange(len(m)))))
            Vfull = pd.DataFrame(dict(cand=m.cand.values, row=np.arange(len(m))))
            xx = tr.merge(Vfull, on="cand", how="left")
            xc = (xx.exit_min.values - C.OPEN_M).astype(int)
            vx = np.array([np.nansum(V[r, max(c - 5, 0):c + 1]) if 0 <= c < C.W else np.nan for r, c in zip(xx.row.values, xc)])
            vols.append(pd.DataFrame(dict(src=src, cand=xx.cand.values, vol5_exit_lots=vx / xx.lot.values, row=-1)))
            if pool is not None and len(pool):
                pt = pool.run(LQ.ARM_EXITS, exe)
                pools.append(pt[["parent", "und", "book", "day", "net", "gross"]].assign(src=src))
    for en, lst in res.items():
        df = pd.concat(lst, ignore_index=True)
        df["day"] = pd.to_datetime(df["day"])
        df.to_parquet(os.path.join(OUT, f"trades_{en}.parquet"))
        print(en, len(df), df.groupby(["src", "book"]).net.agg(["size", "sum"]).round(0).to_string(), flush=True)
    P = pd.concat(pools, ignore_index=True)
    P["day"] = pd.to_datetime(P["day"])
    P.to_parquet(os.path.join(OUT, "pool_real.parquet"))
    VV = pd.concat(vols, ignore_index=True)
    ve = VV[VV.vol5_entry_lots.notna()][["src", "cand", "vol5_entry_lots"]]
    vx = VV[VV.vol5_exit_lots.notna()][["src", "cand", "vol5_exit_lots"]]
    ve.merge(vx, on=["src", "cand"], how="left").to_parquet(os.path.join(OUT, "vol_real.parquet"))
    print("done", round(time.time() - t0), flush=True)


if __name__ == "__main__":
    main()
