"""h30: how often a premium stop AND target were both touched inside the same 1-minute bar (the engine then takes the
STOP, the conservative choice). Counts over all unique trades (real + random) of every exit with both a stop and a
target. Also prints Rs per premium point per lot (= latest lot) per index.

python3 -I research/hunt/h30/ties.py
"""
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".."))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy.costs import floor_tick  # noqa: E402
from sim import HS, exit_menu  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h30")
EX = exit_menu()
tot = {}
for u in ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]:
    P = np.load(os.path.join(OUT, f"paths_{u}.npy"), mmap_mode="r")
    z = np.load(os.path.join(OUT, f"sim_{u}.npz"))
    meta = pd.read_pickle(os.path.join(OUT, f"meta_{u}.pkl"))
    urow, e_raw, XM = z["urow"], z["e_raw"].astype(float), z["XM"]
    e = np.round(e_raw * 1.0005 * (1 + HS[u]), 2)
    H = P[:, 1, :][urow, :]
    L = P[:, 2, :][urow, :]
    for k, (name, ex) in enumerate(EX):
        if not ((ex.stop_pct or ex.stop_pts) and (ex.tgt_pct or ex.tgt_pts)):
            continue
        st = floor_tick(e * (1 - ex.stop_pct)) if ex.stop_pct else floor_tick(e - ex.stop_pts)
        tg = e * (1 + ex.tgt_pct) if ex.tgt_pct else e + ex.tgt_pts
        xm = XM[:, k].astype(int)
        ok = xm >= 0
        r = np.arange(len(xm))[ok]
        c = xm[ok]
        tie = (L[r, c] <= st[ok]) & (H[r, c] >= tg[ok]) & (st[ok] > 0.05)
        a = tot.setdefault(name, [0, 0])
        a[0] += int(tie.sum())
        a[1] += int(ok.sum())
    print(u, "Rs per premium point per lot (latest lot):", int(meta.lot.iloc[-1]), flush=True)
for k, (t, n) in tot.items():
    print(f"{k}: same-minute stop+target ties {t} of {n} trades ({100 * t / n:.2f}%)")
