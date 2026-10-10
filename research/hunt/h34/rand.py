"""h34 random-entry baseline (pre-holdout only): 36 random entries per index-day (uniform minute 09:16-15:04, coin-flip
side), ATM and 1-ITM nearest expiry, all 128 point exits, dated charges + real half-spread.

    flock <scratch>/obuy.lock python3 -I research/hunt/h34/rand.py <UND> [--days N] [--hold]   (--hold: holdout days, run once at the end)
Writes scratchpad/hunt/h34/rand_<UND>_cells.npz (cell stats for the matched test) and rand_<UND>_agg.parquet.
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core as K  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.data import market, dnum  # noqa: E402
from obuy.engine import Execution, prepare_many  # noqa: E402

NPER = 36
LO, HI = 9 * 60 + 16, 15 * 60 + 4


def signals(und, ndays=None, seed=34, hold=False):
    mk = market()
    ix = mk.index(und)
    days = [d for d in ix.days if (d >= K.HOLD if hold else d < K.HOLD)]
    if ndays:
        days = days[:ndays]
    rng = np.random.default_rng(seed + K.UNDS5.index(und))
    rows = []
    for d in days:
        m = rng.integers(LO, HI + 1, NPER)
        s = rng.choice((1, -1), NPER)
        for k in range(NPER):
            rows.append(dict(und=und, day=d, sig_min=int(m[k]) - 1, side=int(s[k]), book=f"r{k}"))
    return pd.DataFrame(rows)


def main():
    und = sys.argv[1]
    nd = int(sys.argv[sys.argv.index("--days") + 1]) if "--days" in sys.argv else None
    t0 = time.time()
    hold = "--hold" in sys.argv
    sig = signals(und, nd, hold=hold)
    exe = K.exe_h34(Execution(expiry="allow"))
    packs = prepare_many([(sig, r, exe, 0, None, False) for r in K.RULES])
    print(und, "signals", len(sig), "packs", [len(p[0]) for p in packs], f"{time.time() - t0:.0f}s", flush=True)
    cells_out, agg = {}, []
    for ri, (pk, _) in enumerate(packs):
        res = K.run_menu(pk, K.MENU, exe)
        base = K.post(res[0])
        n = len(base)
        NET = np.zeros((n, K.NX), np.float32)
        GR = np.zeros((n, K.NX), np.float32)
        ST = np.zeros((n, K.NX), np.float32)
        for i in range(K.NX):
            t = K.post(res[i])
            assert len(t) == n and (t.cand.values == base.cand.values).all()
            NET[:, i], GR[:, i], ST[:, i] = t.NET.values, t.gross_mid.values, t.STRESS.values
        del res
        dn = np.array([dnum(d) for d in base.day])
        bk = K.bucket(base.entry_min.values)
        bd = K.band(base.e_raw.values)
        yr = np.array([d.year for d in base.day])
        cid = dn * 10 + bk
        u, inv = np.unique(cid, return_inverse=True)
        cnt = np.bincount(inv, minlength=len(u)).astype(np.int16)
        s1 = np.zeros((len(u), K.NX), np.float64)
        s2 = np.zeros((len(u), K.NX), np.float64)
        np.add.at(s1, inv, NET.astype(np.float64))
        np.add.at(s2, inv, NET.astype(np.float64) ** 2)
        cells_out[f"cid_r{ri}"] = u
        cells_out[f"cnt_r{ri}"] = cnt
        cells_out[f"s1_r{ri}"] = s1.astype(np.float32)
        cells_out[f"s2_r{ri}"] = s2.astype(np.float32)
        key = pd.DataFrame(dict(bk=bk, bd=bd, yr=yr))
        for (b, d_, y), g in key.groupby(["bk", "bd", "yr"]):
            ix = g.index.values
            agg.append(pd.DataFrame(dict(und=und, rule=K.RLAB[ri], bucket=b, band=d_, year=y, exit=np.arange(K.NX),
                                         n=len(ix), net=NET[ix].sum(0, dtype=np.float64), gross=GR[ix].sum(0, dtype=np.float64),
                                         stress=ST[ix].sum(0, dtype=np.float64),
                                         prem=float(base.e_raw.values[ix].sum()), lots=float((base.qty.values[ix] / base.lot.values[ix]).sum()))))
        print(und, K.RLAB[ri], "trades", n, "cells", len(u), f"{time.time() - t0:.0f}s", flush=True)
    tag = (f"_{nd}" if nd else "") + ("_hold" if hold else "")
    np.savez(os.path.join(K.OUT, f"rand_{und}{tag}_cells.npz"), **cells_out)
    pd.concat(agg, ignore_index=True).to_parquet(os.path.join(K.OUT, f"rand_{und}{tag}_agg.parquet"))
    print(und, "done", f"{time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    main()
