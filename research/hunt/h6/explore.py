"""Phase 1 (index only, in-sample < 2025-10-01): does cross-index divergence in the first T minutes persist or revert?"""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as cm
import numpy as np, pandas as pd, itertools

A = cm.aligned()
days = np.array(A["days"])
ins = days < cm.HOLD0
Cl = A["C"]; Op = A["O"]
def at(u, col):
    return Cl[u][:, col]
rows = []
for T in (15, 30, 45, 60, 90):
    for a, b in itertools.permutations(cm.UNDS, 2):
        if a > b: continue
        o_a, o_b = Op[a][:, 0], Op[b][:, 0]
        ra = np.log(at(a, T - 1) / o_a); rb = np.log(at(b, T - 1) / o_b)
        sp = ra - rb
        # rolling sd of past 60 days' spread (strictly past)
        s = pd.Series(sp)
        sd = s.shift(1).rolling(60, min_periods=30).std()
        z = (sp / sd).values
        for h in (15, 30, 60, 120, "close"):
            e = 360 if h == "close" else min(T - 1 + h, 360)
            fa = np.log(at(a, e) / at(a, T - 1)); fb = np.log(at(b, e) / at(b, T - 1))
            fs = fa - fb
            ok = ins & np.isfinite(z) & np.isfinite(fs) & ~A["exp"][a] & ~A["exp"][b]
            if ok.sum() < 100: continue
            zz, ff = z[ok], fs[ok]
            big = np.abs(zz) > 1.5
            rows.append(dict(T=T, pair=f"{a}-{b}", h=h, n=ok.sum(), corr=np.corrcoef(zz, ff)[0, 1],
                             big_n=big.sum(), big_cont_bps=1e4 * np.mean(np.sign(zz[big]) * ff[big]),
                             fs_abs_bps=1e4 * np.mean(np.abs(ff)), sp_sd_bps=1e4 * np.nanstd(sp[ok])))
r = pd.DataFrame(rows)
pd.set_option("display.width", 200); pd.set_option("display.max_rows", 500)
print(r.round(3).to_string())
