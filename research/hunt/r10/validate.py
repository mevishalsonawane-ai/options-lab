"""r10: how close is the option-volume VWAP proxy (and TWAP) to the REAL near-month futures session VWAP?

Real futures minute OHLCV exists only for the contracts live at fetch time (2026-07-29 .. 2026-10-06, h38). On those
days compare, at every minute 09:30..15:29, the sign of (price - VWAP) and the z-score vs the +-SD bands.
Basis is constant-ish within a day, so each side uses its own price: future vs futures VWAP, index vs proxy VWAP.
"""
from __future__ import annotations

import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.append("/root/.local/lib/python3.11/site-packages")
sys.path.insert(0, HERE)
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import feats as F  # noqa: E402
from obuy import config as C  # noqa: E402


def fut_panel(u):
    fs = sorted(glob.glob(os.path.join(C.DATA, "futures", "NSE_FNO" if u not in C.BSE else "BSE_FNO", u, "*_minute.parquet")))
    if not fs:
        return None
    f = pd.read_parquet(fs[0])                      # nearest contract (2026-10 expiry)
    f["ts"] = f.ts.dt.tz_localize(None)
    f["day"] = f.ts.dt.normalize()
    f["m"] = f.ts.dt.hour * 60 + f.ts.dt.minute
    f = f[(f.m >= C.OPEN_M) & (f.m <= C.LAST_M)]
    return f


def main():
    rows = []
    for u in ["BANKNIFTY", "NIFTY", "MIDCPNIFTY", "SENSEX", "FINNIFTY"]:
        fp = fut_panel(u)
        if fp is None or not os.path.exists(os.path.join(F.R10, f"panel_{u}.npz")):
            continue
        days, o, h, l, c, v, exp = F.load(u)
        vf = F.vwap_family(h, l, c, v)
        pos = {d: i for i, d in enumerate(days)}
        for d, g in fp.groupby("day"):
            i = pos.get(d)
            if i is None or len(g) < 300:
                continue
            a = np.full(C.W, np.nan); vv = np.zeros(C.W)
            col = g.m.values - C.OPEN_M
            tp = (g.high.values + g.low.values + g.close.values) / 3
            a[col] = g.close.values
            tpa = np.full(C.W, np.nan); tpa[col] = tp; vv[col] = g.volume.values
            tpa = pd.Series(tpa).ffill().bfill().values; a = pd.Series(a).ffill().bfill().values
            cw = np.cumsum(vv) + 1e-9
            fv = np.cumsum(vv * tpa) / cw
            fsd = np.sqrt(np.maximum(np.cumsum(vv * tpa * tpa) / cw - fv * fv, 0))
            fz = (a - fv) / np.where(fsd > 0, fsd, np.nan)
            sl = slice(15, C.W)
            for k in ("vw", "tw"):
                z = (c[i] - vf[k][i]) / np.where(vf[k + "_sd"][i] > 0, vf[k + "_sd"][i], np.nan)
                ok = ~np.isnan(z[sl]) & ~np.isnan(fz[sl])
                rows.append(dict(und=u, day=d, w=k, n=int(ok.sum()),
                                 sign_agree=float((np.sign(z[sl][ok]) == np.sign(fz[sl][ok])).mean()),
                                 z_corr=float(np.corrcoef(z[sl][ok], fz[sl][ok])[0, 1]),
                                 band2_agree=float(((np.abs(z[sl][ok]) > 2) == (np.abs(fz[sl][ok]) > 2)).mean()),
                                 band1_agree=float(((np.abs(z[sl][ok]) > 1) == (np.abs(fz[sl][ok]) > 1)).mean())))
    R = pd.DataFrame(rows)
    R.to_csv(os.path.join(F.R10, "validate.csv"), index=False)
    print(R.groupby(["und", "w"])[["sign_agree", "z_corr", "band1_agree", "band2_agree"]].mean().round(3).assign(
        days=R.groupby(["und", "w"]).size()).to_string())


if __name__ == "__main__":
    main()
