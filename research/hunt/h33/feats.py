"""h33 per-minute index shock features (no P&L).

For every option-era session and index: 1-minute close returns, a 'normal' 1-minute volatility sigma taken from the
PREVIOUS 5 sessions only (09:20-15:25 returns), shock scores z_k(t) = (c_t / c_{t-k} - 1) / (sigma * sqrt(k)) for
k = 1, 3, and an option-volume burst ratio vb(t) = V5(t) / median V5 over the previous 5 sessions (V5 = ATM+-2 call +
put volume of the 5 minutes ending at t, from h26's feature panel, nearest series).

    python3 -I research/hunt/h33/feats.py      -> <scratch>/hunt/h33/feats_<U>.npz
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market, dnum  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h33")
H26 = os.path.join(C.SCRATCH, "hunt/h26/cache/h26")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
LB = 5


def build(u, mk):
    ix = mk.index(u)
    M = ix.mat()
    c = M["c"].astype(np.float64)
    n = len(ix.days)
    r = np.full_like(c, np.nan)
    r[:, 1:] = c[:, 1:] / c[:, :-1] - 1
    win = r[:, 5:371]                      # 09:20 .. 15:25
    dstd = np.nanstd(win, axis=1)
    sig = np.full(n, np.nan)
    for i in range(LB, n):
        s = dstd[i - LB:i]
        if np.isfinite(s).sum() >= 3:
            sig[i] = np.sqrt(np.nanmean(s ** 2))
    z = {}
    for k in (1, 3, 5):
        d = np.full_like(c, np.nan)
        d[:, k:] = c[:, k:] / c[:, :-k] - 1
        z[k] = d / (sig[:, None] * np.sqrt(k))
    # option volume burst
    vb = np.full_like(c, np.nan)
    try:
        f = np.load(os.path.join(H26, f"opt_{u}.npz"))
        vd = {int(d): i for i, d in enumerate(f["days"])}
        V = f["X"][:, 7, :].astype(np.float64) + f["X"][:, 8, :].astype(np.float64)   # vc5 + vp5
        rows = [vd.get(dnum(d), -1) for d in ix.days]
        V5 = np.full_like(c, np.nan)
        for i, j in enumerate(rows):
            if j >= 0:
                V5[i] = V[j]
        med = np.nanmedian(V5[:, 15:365], axis=1)
        base = np.full(n, np.nan)
        for i in range(LB, n):
            m = med[i - LB:i]
            if np.isfinite(m).sum() >= 3:
                base[i] = np.nanmedian(m)
        vb = V5 / base[:, None]
    except FileNotFoundError:
        print(u, "no h26 volume panel")
    days = np.array([dnum(d) for d in ix.days])
    np.savez_compressed(os.path.join(OUT, f"feats_{u}.npz"), days=days, c=c.astype(np.float32), sig=sig,
                        z1=z[1].astype(np.float32), z3=z[3].astype(np.float32), z5=z[5].astype(np.float32),
                        vb=vb.astype(np.float32))
    print(u, n, "days", f"median sigma {np.nanmedian(sig) * 1e4:.2f} bp/min", flush=True)


if __name__ == "__main__":
    mk = market()
    for u in (sys.argv[1:] or UNDS):
        build(u, mk)
        mk.release(u)
