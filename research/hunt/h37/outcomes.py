"""h37 stage 1 (heavy): the outcome table (h25's design, h37's 17 exits). For every non-expiry day, every signal minute
09:15..15:00 and both sides, buy the 1-ITM nearest option at the next minute's open (obuy engine, app fills + charges)
and run the 17 pre-registered exits. Any signal set is then a lookup.

    OBUY_CACHE=<scratch>/hunt/h37/cache flock <scratch>/obuy.lock python3 -I research/hunt/h37/outcomes.py [UND ...]

Writes <scratch>/hunt/h37/out_<UND>.npz: days (ordinals), year, e, qty  (D, M, 2), side 0 = PE, 1 = CE;
per exit k: g_k gross, n_k app net, s_k (entry+exit) x qty, x_k exit minute, t_k tie flag (stop-first decided it).
"""
from __future__ import annotations

import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import floor_tick  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule, prepare_many  # noqa: E402
from obuy.strategies.liquidity import ARM_EXITS  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h37")
SM0, SM1 = 9 * 60 + 15, 15 * 60
M = SM1 - SM0 + 1
PTS = [(t, s) for t in (15, 20, 25, 30) for s in (10, 15, 20)]
EXITS = {f"P{t}_{s}": Exits(stop_pts=s, tgt_pts=t, sig_levels=False) for t, s in PTS}
EXITS["ARM"] = ARM_EXITS
EXITS["LAD"] = Exits(stop_pts=40, tgt_pts=40, ladder=LADDER, ladder_ref_pts=40, sig_levels=False)
for m in (15, 30, 60):
    EXITS[f"T{m}"] = Exits(time_stop=m, time_gain=None, sig_levels=False)
NAMES = list(EXITS)


def build(u, limit_days=None):
    t0 = time.time()
    mk = market()
    ix = mk.index(u)
    days = [d for d in ix.days if not ix.d[d]["exp"]]
    if limit_days:
        days = days[:limit_days]
    D = len(days)
    di = {d: i for i, d in enumerate(days)}
    mins = np.arange(SM0, SM1 + 1)
    sig = pd.DataFrame({"und": u, "day": np.repeat(np.array(days, dtype=object), M * 2),
                        "sig_min": np.tile(np.repeat(mins, 2), D), "side": np.tile(np.array([-1, 1]), D * M)})
    sig["book"] = "all"
    exe = Execution(expiry="skip")
    (pk, _), = prepare_many([(sig, StrikeRule(money=1), exe, 0, None, False)])
    print(u, "prepared", len(sig), "->", len(pk), f"{time.time() - t0:.0f}s", flush=True)
    meta = pk.meta
    flat = ((meta.day.map(di).values.astype(np.int64) * M + meta.sig_min.values.astype(np.int64) - SM0) * 2
            + (meta.side.values > 0).astype(np.int64))
    shp = (D, M, 2)
    res = dict(days=np.array([d.toordinal() for d in days], dtype=np.int32),
               year=np.array([d.year for d in days], dtype=np.int16))
    for nm, v, fill, dt in (("e", meta.e.values, np.nan, np.float32), ("qty", meta.qty.values, 0, np.float32)):
        a = np.full(D * M * 2, fill, dt)
        a[flat] = v
        res[nm] = a.reshape(shp)
    cpos = pd.Series(np.arange(len(meta)), index=meta.cand.values)
    for k, ex in EXITS.items():
        t1 = time.time()
        tr = pk.run(ex, exe, chunk=6000)
        pos = cpos.loc[tr.cand.values].values
        f = flat[pos]
        for nm, vals in (("g", tr.gross.values), ("n", tr.net.values),
                         ("s", (tr.entry.values + tr.exit.values) * tr.qty.values)):
            a = np.full(D * M * 2, np.nan, np.float32)
            a[f] = vals
            res[f"{nm}_{k}"] = a.reshape(shp)
        a = np.full(D * M * 2, -1, np.int16)
        a[f] = tr.exit_min.values
        res[f"x_{k}"] = a.reshape(shp)
        tie = np.zeros(D * M * 2, np.uint8)
        if ex.tgt_pts:
            st = tr.why.isin(["stop", "lock"]).values
            col = tr.exit_min.values - C.OPEN_M
            ok = st & (col >= 0) & (col < C.W)
            w = np.nonzero(ok)[0]
            if len(w):
                H = pk.get("H", pos[w])
                h = H[np.arange(len(w)), col[w]]
                tie[f[w]] = (h >= tr.entry.values[w] + ex.tgt_pts - 1e-9).astype(np.uint8)
                del H
        res[f"t_{k}"] = tie.reshape(shp)
        print(u, k, len(tr), f"ties {int(tie.sum())}", f"{time.time() - t1:.0f}s", flush=True)
    path = os.path.join(OUT, f"out_{u}{'_test' if limit_days else ''}.npz")
    np.savez_compressed(path, **res)
    mk.release(u)
    print(u, "saved", path, f"{os.path.getsize(path) / 1e6:.0f} MB", f"total {time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    args = sys.argv[1:]
    lim = None
    if args and args[0] == "--test":
        lim, args = 15, args[1:]
    for u in (args or ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]):
        build(u, lim)
