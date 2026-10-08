"""h25 stage 1 (heavy): the outcome table. For every non-expiry day, every signal minute 09:15..15:00 and both sides,
buy the 1-ITM nearest option at the next minute's open (obuy engine, app fills + charges) and run the 7 pre-registered
exits. Any signal set is then a lookup into this table.

    OBUY_CACHE=<scratch>/hunt/h25/cache flock <scratch>/obuy.lock python3 -I research/hunt/h25/outcomes.py [UND ...]

Writes <scratch>/hunt/h25/out_<UND>.npz:
  days (date ordinals), year, exp(0), e (entry fill), qty, valid  -- shape (D, M, 2)  M = 346 minutes, side 0=PE 1=CE
  per exit k: g_k gross (app fills, before charges), n_k net before the extra spread, s_k (entry+exit) x qty
  (spread base: cost = hs x s_k), x_k exit minute.
"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule, prepare_many  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h25")
SM0, SM1 = 9 * 60 + 15, 15 * 60          # signal minutes 555..900
M = SM1 - SM0 + 1
ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}
SQ = 15 * 60 + 10
SET2 = os.environ.get("H25_SET") == "points"


def exits(u):
    return {
        "ARM": Exits(stop_pct=0.15, time_stop=20, time_gain=0.05, idx_stop_pts=ISTOP[u], sq_off=SQ),
        "P20": Exits(stop_pts=20, tgt_pts=20, sq_off=SQ),
        "PCT": Exits(stop_pct=0.15, tgt_pct=0.30, sq_off=SQ),
        "LAD": Exits(stop_pct=0.15, ladder=LADDER, ladder_ref_pct=0.15, tgt_pct=0.30, sq_off=SQ),
        "T15": Exits(time_stop=15, time_gain=None, sq_off=SQ),
        "T30": Exits(time_stop=30, time_gain=None, sq_off=SQ),
        "T60": Exits(time_stop=60, time_gain=None, sq_off=SQ),
    }


EXITS = ["ARM", "P20", "PCT", "LAD", "T15", "T30", "T60"]
# AMENDMENT 1: premium-point exits (targets 15/20/25/30 x stops 10/15/20, minus the existing P20 = 20/20)
PTS = [(t, s) for t in (15, 20, 25, 30) for s in (10, 15, 20) if (t, s) != (20, 20)]
EXITS2 = [f"P{t}_{s}" for t, s in PTS]


def exits2(u):
    return {f"P{t}_{s}": Exits(stop_pts=s, tgt_pts=t, sq_off=SQ) for t, s in PTS}


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
    sig = pd.DataFrame({
        "und": u,
        "day": np.repeat(np.array(days, dtype=object), M * 2),
        "sig_min": np.tile(np.repeat(mins, 2), D),
        "side": np.tile(np.array([-1, 1]), D * M),
    })
    sig["book"] = "all"
    exe = Execution(expiry="skip")
    (pk, _), = prepare_many([(sig, StrikeRule(money=1), exe, 0, None, False)])
    print(u, "prepared", len(sig), "->", len(pk), f"{time.time() - t0:.0f}s", flush=True)
    meta = pk.meta
    dpos = meta.day.map(di).values.astype(np.int64)
    mpos = meta.sig_min.values.astype(np.int64) - SM0
    spos = (meta.side.values > 0).astype(np.int64)
    flat = (dpos * M + mpos) * 2 + spos
    shp = (D, M, 2)
    res = dict(days=np.array([d.toordinal() for d in days], dtype=np.int32),
               year=np.array([d.year for d in days], dtype=np.int16))
    e = np.full(D * M * 2, np.nan, np.float32)
    e[flat] = meta.e.values
    q = np.zeros(D * M * 2, np.float32)
    q[flat] = meta.qty.values
    res["e"], res["qty"] = e.reshape(shp), q.reshape(shp)
    for k, ex in (exits2(u) if SET2 else exits(u)).items():
        t1 = time.time()
        tr = pk.run(ex, exe, chunk=6000)
        cand = tr.cand.values.astype(np.int64)        # cand = row of meta? map via meta.cand
        pos = pd.Series(np.arange(len(meta)), index=meta.cand.values).loc[cand].values
        f = flat[pos]
        for nm, vals in (("g", tr.gross.values), ("n", tr.net.values),
                         ("s", (tr.entry.values + tr.exit.values) * tr.qty.values)):
            a = np.full(D * M * 2, np.nan, np.float32)
            a[f] = vals
            res[f"{nm}_{k}"] = a.reshape(shp)
        a = np.full(D * M * 2, -1, np.int16)
        a[f] = tr.exit_min.values
        res[f"x_{k}"] = a.reshape(shp)
        print(u, k, len(tr), f"mean gross {tr.gross.mean():.1f} net {tr.net.mean():.1f}", f"{time.time() - t1:.0f}s",
              flush=True)
    if SET2:
        res = {k: v for k, v in res.items() if k[:2] in ("g_", "n_", "s_", "x_")}
    path = os.path.join(OUT, f"{'out2' if SET2 else 'out'}_{u}{'_test' if limit_days else ''}.npz")
    np.savez_compressed(path, **res)
    mk.release(u)
    print(u, "saved", path, f"{os.path.getsize(path) / 1e6:.0f} MB", f"total {time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    args = sys.argv[1:]
    lim = None
    if args and args[0] == "--test":
        lim, args = int(os.environ.get("H25_LIM", 20)), args[1:]
    for u in (args or ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]):
        build(u, lim)
