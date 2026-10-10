"""h37: per-minute rupee turnover of an index's constituents (NSE_EQ minute candles, from 2024-10-07), aligned to the
obuy Index day x minute grid. Used only for the volume-profile check.

    OBUY_CACHE=<scratch>/hunt/h37/cache python3 -I research/hunt/h37/vp.py      -> <scratch>/hunt/h37/vp_<UND>.npy
"""
from __future__ import annotations

import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
import sigs  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h37")
EQ = os.path.join(C.DATA, "candles", "minute", "NSE_EQ")
LISTS = {"NIFTY": sigs.NIFTY50, "BANKNIFTY": sigs.BANK12}


def path(u):
    return os.path.join(OUT, f"vp_{u}.npy")


def build(u):
    ix = market().index(u)
    pos = {d.toordinal(): i for i, d in enumerate(ix.days)}
    W = np.zeros((len(ix.days), C.W), np.float64)
    have = []
    for s in LISTS[u]:
        fs = sorted(glob.glob(os.path.join(EQ, s, "*.parquet")))
        if not fs:
            continue
        have.append(s)
        for f in fs:
            x = pd.read_parquet(f, columns=["ts", "close", "volume"])
            ts = x.ts.dt.tz_localize(None)
            o = np.array([d.toordinal() for d in ts.dt.date.values])
            m = (ts.dt.hour * 60 + ts.dt.minute).values - C.OPEN_M
            di = np.array([pos.get(v, -1) for v in o])
            ok = (di >= 0) & (m >= 0) & (m < C.W)
            np.add.at(W, (di[ok], m[ok]), x.close.values[ok].astype(float) * x.volume.values[ok].astype(float))
    W[W == 0] = np.nan
    np.save(path(u), W.astype(np.float32))
    print(u, "constituents found", len(have), "of", len(LISTS[u]), "days with volume", int((~np.isnan(W)).any(1).sum()))


if __name__ == "__main__":
    for u in ("NIFTY", "BANKNIFTY"):
        build(u)
