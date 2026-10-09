"""R2 post-hoc sensitivity (holdout, chooses nothing): entry/exit timing grid for the futures survivors."""
import sys
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3"); import m3lib as M
from lib import *
assert (R2 / "holdout.lock").exists()
HR = pd.read_parquet(OUT / "hourly_holdout.parquet")
THR = {"CRUDE": 12e-4, "NATGAS": 22e-4, "GOLD": 11e-4, "SILVER": 14e-4}
rows = []
for com in COMS:
    m = M.Market(MCX_SYM[com]); hr = HR[HR.com == com]
    for D in [pd.Timestamp(d) for d in m.days if HOLD_START <= pd.Timestamp(d) <= HOLD_END]:
        d64 = D.to_datetime64(); ix = np.where(m.day == d64)[0]; pv = np.where(m.day < d64)[0]
        if len(ix) < 200 or not len(pv) or D not in hr.index: continue
        fair = hr.loc[D, "us_on"] + np.nan_to_num(hr.loc[D, "inr_on"])
        if not np.isfinite(fair): continue
        pc = pv[-1]
        for et in (9 * 60 + 5, 9 * 60 + 15, 9 * 60 + 30, 10 * 60):
            k = np.searchsorted(m.tod[ix], et)
            if k >= len(ix): continue
            e = ix[k]
            if m.seg[e] != m.seg[pc] or m.tod[e] > et + 10: continue
            gap = np.log(m.F[e] / m.F[pc]); res = gap - fair
            sig = {"C1": np.sign(gap), "C2": np.sign(fair), "C3": -np.sign(res) if abs(res) > THR[com] else 0}
            for xt in (12 * 60, 14 * 60, 17 * 60, 20 * 60, 99 * 60):
                xi = m.day_cut[d64] if xt == 99 * 60 else ix[np.searchsorted(m.tod[ix], xt, side="right") - 1]
                if xi <= e: continue
                for r, s in sig.items():
                    if s == 0: continue
                    g, n = fut_pnl(com, s, m.F[e], m.F[xi])
                    rows.append(dict(com=com, rule=r, entry=et, exit=xt, net=n))
R = pd.DataFrame(rows)
P = R.pivot_table(index=["com", "rule", "entry"], columns="exit", values="net", aggfunc="mean").round(0)
P.columns = [f"x{c//60:02d}" if c < 99 * 60 else "xEOD" for c in P.columns]
P.index = P.index.set_levels([f"{e//60:02d}:{e%60:02d}" for e in P.index.levels[2]], level=2)
pd.set_option("display.width", 200); print(P.to_string()); P.to_csv(OUT / "sens_holdout.csv")
