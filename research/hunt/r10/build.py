"""r10 panels: per underlying, per day, per minute (375 cols 09:15..15:29):
   index o/h/l/c (obuy Index: jx / h26 caches) and the NEAREST-expiry option volume (sum of CE+PE units over the
   ATM+-10 rolling strikes) used as the volume weight of an index VWAP proxy (the index itself has no volume, and
   minute futures volume for expired contracts cannot be fetched - h38).

    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/r10/build.py [UND ...]
    -> <scratch>/hunt/r10/panel_<U>.npz  (days int days-since-1970, o,h,l,c,v float32 [ndays, 375], exp bool)
"""
from __future__ import annotations

import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import pyarrow.parquet as pq  # noqa: E402
from obuy.data import market, dnum  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "r10")
UNDS = ["BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]


def optvol(u, series):
    """dict day(int) -> volume[375] summed over CE+PE, all offsets."""
    acc = []
    for side in ("CALL", "PUT"):
        for f in sorted(glob.glob(os.path.join(C.DATA, "options", u, series, side, "*.parquet"))):
            t = pq.read_table(f, columns=["ts", "volume", "offset"]).to_pandas()
            t["ts"] = t.ts.dt.tz_localize(None)
            t = t.drop_duplicates(["ts", "offset"])          # windows overlapping across files
            acc.append(t.groupby("ts").volume.sum())
    if not acc:
        return {}
    s = pd.concat(acc).groupby(level=0).sum()
    df = s.reset_index()
    df["day"] = (df.ts.dt.normalize() - pd.Timestamp("1970-01-01")).dt.days
    df["m"] = df.ts.dt.hour * 60 + df.ts.dt.minute
    df = df[(df.m >= C.OPEN_M) & (df.m <= C.LAST_M)]
    out = {}
    for d, g in df.groupby("day"):
        a = np.zeros(C.W, np.float32)
        a[g.m.values - C.OPEN_M] = g.volume.values
        out[int(d)] = a
    return out


def build(u):
    mk = market()
    ix = mk.index(u)
    M = ix.mat()
    wk, mo = optvol(u, "WEEK"), optvol(u, "MONTH")
    days, V, keep = [], [], []
    for i, d in enumerate(ix.days):
        dn = dnum(d)
        v = wk.get(dn)
        if v is None or v.sum() == 0:
            v = mo.get(dn)
        if v is None or not ix.d[d].get("real", True):
            continue
        days.append(dn); V.append(v); keep.append(i)
    keep = np.array(keep)
    exp = np.array([ix.d[ix.days[i]]["exp"] for i in keep])
    np.savez_compressed(os.path.join(OUT, f"panel_{u}.npz"), days=np.array(days), exp=exp,
                        o=M["o"][keep].astype(np.float32), h=M["h"][keep].astype(np.float32),
                        l=M["l"][keep].astype(np.float32), c=M["c"][keep].astype(np.float32),
                        v=np.stack(V).astype(np.float32))
    print(u, len(days), pd.to_datetime(days[0], unit="D").date(), pd.to_datetime(days[-1], unit="D").date(), flush=True)


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for u in (sys.argv[1:] or UNDS):
        build(u)
