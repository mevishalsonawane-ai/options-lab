"""Dense stock minute closes/opens (NSE_EQ, 2024-10..2026-10) aligned to the index day list. Cached float32."""
import sys, os, glob
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as cm
import numpy as np, pandas as pd
from obuy import config as C


def load():
    p = os.path.join(cm.SCR, "stocks.npz")
    if os.path.exists(p):
        z = np.load(p, allow_pickle=False)
        return dict(names=list(z["names"]), days=[pd.Timestamp(str(x)).date() for x in z["days"]], C=z["C"], O=z["O"], V=z["V"])
    root = os.path.join(C.DATA, "candles", "minute", "NSE_EQ")
    names = sorted(os.listdir(root))
    A = cm.aligned()
    days = [d for d in A["days"] if d >= pd.Timestamp("2024-10-01").date()]
    dpos = {d: i for i, d in enumerate(days)}
    Cm = np.full((len(names), len(days), C.W), np.nan, np.float32)
    Om = Cm.copy(); Vm = Cm.copy()
    for si, s in enumerate(names):
        fs = sorted(glob.glob(os.path.join(root, s, "*.parquet")))
        d = pd.concat([pd.read_parquet(f) for f in fs])
        ts = d.ts.dt.tz_localize(None)
        day = ts.dt.date.values
        m = (ts.dt.hour * 60 + ts.dt.minute).values - C.OPEN_M
        di = np.array([dpos.get(x, -1) for x in day])
        ok = (di >= 0) & (m >= 0) & (m < C.W)
        Cm[si, di[ok], m[ok]] = d.close.values[ok]
        Om[si, di[ok], m[ok]] = d.open.values[ok]
        Vm[si, di[ok], m[ok]] = d.volume.values[ok]
    np.savez(p, names=np.array(names), days=np.array([str(x) for x in days]), C=Cm, O=Om, V=Vm)
    return load()

if __name__ == "__main__":
    S = load(); print(len(S["names"]), len(S["days"]), S["days"][0], S["days"][-1], np.isfinite(S["C"][:, :, 0]).mean())
