"""R9 data: per index, per day, per minute OPTV (ATM+-10 nearest-series option volume, CE+PE) and FLOW (ATM+-5 tick-rule
signed call volume minus signed put volume); FUTV from the Dhan 2026-10 futures minutes.
    python3 -I research/hunt/r9/build.py  -> scratch/hunt/r9/vol_<U>.npz, fut_<U>.npz"""
from __future__ import annotations

import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import lib9 as R  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

D = R.L.D
C = R.C


def tick_sign(c):
    """per row: sign of close change, zero change keeps last sign (NaN close = no print)."""
    s = np.zeros_like(c)
    last = np.zeros(c.shape[0])
    prev = np.full(c.shape[0], np.nan)
    for t in range(c.shape[1]):
        x = c[:, t]
        ok = np.isfinite(x)
        d = np.where(ok & np.isfinite(prev), np.sign(x - prev), 0.0)
        last = np.where(d != 0, d, last)
        s[:, t] = np.where(ok, last, 0.0)
        prev = np.where(ok, x, prev)
    return s


def build(u):
    mk = D.market()
    ix = mk.index(u)
    M = ix.mat()
    op = mk.options(u)
    step = C.STEP[u]
    days, OV, FL = [], [], []
    t0 = time.time()
    for i, d in enumerate(ix.days):
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        sp = pd.Series(M["c"][i]).ffill().bfill().values
        if not np.isfinite(sp).any():
            sp = pd.Series(ch.spot).ffill().bfill().values
        if not np.isfinite(sp).any():
            continue
        atm = np.round(sp / step) * step
        K = ch.K.astype(float)
        dist = np.abs(K[:, None] - atm[None, :]) / step
        vC, vP = np.nan_to_num(ch.v["C"]), np.nan_to_num(ch.v["P"])
        in10 = dist <= 10.5
        in5 = dist <= 5.5
        optv = ((vC + vP) * in10).sum(0)
        sC, sP = tick_sign(ch.c["C"]), tick_sign(ch.c["P"])
        flow = ((sC * vC - sP * vP) * in5).sum(0)
        days.append(D.dnum(d))
        OV.append(optv.astype(np.float32))
        FL.append(flow.astype(np.float32))
        if len(days) % 300 == 0:
            print(u, d, len(days), f"{time.time() - t0:.0f}s", flush=True)
    np.savez_compressed(os.path.join(R.OUT, f"vol_{u}.npz"), days=np.array(days), optv=np.stack(OV), flow=np.stack(FL))
    mk.release()
    print(u, "done", len(days), f"{time.time() - t0:.0f}s", flush=True)


def build_fut(u):
    f = os.path.join(C.DATA, "futures", "NSE_FNO", u, "2026-10-27_minute.parquet")
    if not os.path.exists(f):
        return
    x = pd.read_parquet(f)
    ts = x.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"] = ts.dt.date
    x["col"] = ts.dt.hour * 60 + ts.dt.minute - C.OPEN_M
    x = x[(x.col >= 0) & (x.col < C.W)]
    days, V, O, Cc = [], [], [], []
    for d, g in x.groupby("day"):
        v = np.full(C.W, np.nan, np.float32)
        o = np.full(C.W, np.nan, np.float32)
        c = np.full(C.W, np.nan, np.float32)
        v[g.col.values] = g.volume.values
        o[g.col.values] = g.open.values
        c[g.col.values] = g.close.values
        days.append(D.dnum(d))
        V.append(v)
        O.append(o)
        Cc.append(c)
    np.savez_compressed(os.path.join(R.OUT, f"fut_{u}.npz"), days=np.array(days), v=np.stack(V), o=np.stack(O), c=np.stack(Cc))
    print(u, "fut days", len(days))


if __name__ == "__main__":
    us = sys.argv[1:] or R.UNDS
    for u in us:
        build_fut(u)
        build(u)
