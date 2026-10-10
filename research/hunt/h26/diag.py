"""h26 diagnostics (no P&L, pre-holdout only): 15-min forward-return IC of each feature, and the constituent-basket vs
index lead-lag cross-correlation at 1-5 minutes. Not a selection input (PREREG.md).

    OBUY_CACHE=<scratch>/hunt/h26/cache python3 -I research/hunt/h26/diag.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import feats as FT  # noqa: E402

HOLD = pd.Timestamp("2025-10-01").date()
pd.set_option("display.width", 250)


def ic(x, spot, days, H=15):
    pre = np.array([d < HOLD for d in days])
    cols = FT.SAMPLE
    with np.errstate(invalid="ignore", divide="ignore"):
        fwd = spot[:, cols + H] / spot[:, cols] - 1
    a, b = x[pre][:, cols].ravel(), fwd[pre].ravel()
    ok = np.isfinite(a) & np.isfinite(b)
    if ok.sum() < 100:
        return np.nan, np.nan, 0
    r = np.corrcoef(a[ok], b[ok])[0, 1]
    neff = ok.sum() / 3.0                      # 15-min horizon sampled every 5 min: ~3x overlap
    return r, r * np.sqrt(neff), int(ok.sum())


def main():
    rows = []
    for u in ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]:
        days, D = FT.opt(u)
        sp = D["spot"]
        for f in ("BU15_z", "BUopen_z", "dOIpc2_z", "dOIpc5_z", "VIMB_z", "PFLOW_z"):
            r, t, n = ic(D[f], sp, days)
            rows.append(dict(und=u, feat=f, ic=r, t=t, n=n))
        for trig, p in (("WALL", 1), ("VSPIKE", 3.0)):
            dd, S = FT.state(trig, u, p)
            r, t, n = ic(np.where(S != 0, S, np.nan).astype(float), sp, dd)
            rows.append(dict(und=u, feat=f"{trig}{p} state", ic=r, t=t, n=n))
    for u in ("BANKNIFTY", "NIFTY"):
        days, D = FT.cons(u)
        for f in ("HVSURGE", "VWB", "VWAPSH", "B3_z"):
            r, t, n = ic(D[f], D["spot"], days)
            rows.append(dict(und=u, feat=f, ic=r, t=t, n=n))
    R = pd.DataFrame(rows)
    print("=== 15-min forward index return IC, pre-holdout (sign: + = feature's conventional bull reading works) ===")
    print(R.round(4).to_string())
    print("\n=== lead-lag: corr(basket 1-min return at t-k, index 1-min return at t), pre-holdout, 09:20-15:20 ===")
    L = []
    for u in ("BANKNIFTY", "NIFTY"):
        days, D = FT.cons(u)
        pre = np.array([d < HOLD for d in days])
        b1 = D["b1"][pre]
        I = D["spot"][pre]
        with np.errstate(invalid="ignore", divide="ignore"):
            i1 = I[:, 1:] / I[:, :-1] - 1
        b1 = b1[:, 1:]
        row = dict(und=u)
        for k in range(-3, 6):
            if k >= 0:
                a, b = b1[:, 5:360 - k], i1[:, 5 + k:360]
            else:
                a, b = b1[:, 5 - k:360], i1[:, 5:360 + k]
            a, b = a.ravel(), b.ravel()
            ok = np.isfinite(a) & np.isfinite(b)
            row[f"k={k}"] = np.corrcoef(a[ok], b[ok])[0, 1]
        L.append(row)
    print(pd.DataFrame(L).round(3).to_string())
    print("k > 0: basket leads the index by k minutes; k < 0: the index leads the basket.")
    R.to_csv(os.path.join(C.SCRATCH, "hunt", "h26", "diag_ic.csv"), index=False)


if __name__ == "__main__":
    main()
