"""Phase 1b (index only, in-sample): catch-up. When the leader has moved |z|>k more than the laggard by T, does the
LAGGARD move in the leader's direction afterwards (directional, bps of the laggard)? Compare with the unconditional
drift in that direction (sign of leader's move) for the laggard."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import common as cm
import numpy as np, pandas as pd, itertools
A = cm.aligned(); days = np.array(A["days"]); ins = days < cm.HOLD0
Cl, Op = A["C"], A["O"]
rows = []
for T in (15, 30, 60):
    for lead, lag in itertools.permutations(cm.UNDS, 2):
        rl = np.log(Cl[lead][:, T-1] / Op[lead][:, 0]); rg = np.log(Cl[lag][:, T-1] / Op[lag][:, 0])
        sp = rl - rg
        sd = pd.Series(sp).shift(1).rolling(60, min_periods=30).std().values
        z = sp / sd
        sdr = pd.Series(rl).shift(1).rolling(60, min_periods=30).std().values
        zl = rl / sdr
        for h in (30, 60, 120, "close"):
            e = 360 if h == "close" else min(T-1+h, 360)
            fg = np.log(Cl[lag][:, e] / Cl[lag][:, T-1]); fl = np.log(Cl[lead][:, e] / Cl[lead][:, T-1])
            ok = ins & np.isfinite(z) & np.isfinite(fg) & np.isfinite(zl) & ~A["exp"][lag]
            # catch-up condition: leader moved strongly (|zl|>1) AND spread in leader's direction (sign(z)==sign(rl), |z|>1.5)
            cond = ok & (np.abs(zl) > 1) & (np.abs(z) > 1.5) & (np.sign(z) == np.sign(rl))
            dirn = np.sign(rl)
            rows.append(dict(T=T, lead=lead, lag=lag, h=h, n=cond.sum(),
                             lag_dir_bps=1e4*np.mean(dirn[cond]*fg[cond]) if cond.sum() else np.nan,
                             lead_dir_bps=1e4*np.mean(dirn[cond]*fl[cond]) if cond.sum() else np.nan,
                             all_strong_lag_bps=1e4*np.mean((dirn*fg)[ok & (np.abs(zl) > 1)]),
                             lag_absmove_bps=1e4*np.mean(np.abs(fg[ok]))))
r = pd.DataFrame(rows)
pd.set_option("display.width", 200); pd.set_option("display.max_rows", 500)
print(r.round(2).to_string())
