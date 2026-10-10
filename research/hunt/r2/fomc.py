"""R2 F8: hold an MCX ATM straddle overnight across the FOMC statement (lands after the MCX close).
Buy both ATM legs at the first printed minute at/after 22:45 IST on the FOMC day; sell at the first printed minute at/after
09:30 IST next session (fallback: the -within-60-min print). Real Dhan option minutes (prints only). usage: fomc.py design|holdout"""
import sys
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M
from lib import *
MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout": assert (R2 / "holdout.lock").exists()
LO, HI = (pd.Timestamp("2025-08-01"), HOLD_START) if MODE == "design" else (HOLD_START, HOLD_END)
FOMC = pd.to_datetime("2025-09-17 2025-10-29 2025-12-10 2026-01-28 2026-03-18 2026-04-29 2026-06-17 2026-07-29 2026-09-16".split())
rows = []
for com in COMS:
    m = M.Market(MCX_SYM[com]); days = pd.DatetimeIndex(m.days)
    for f in FOMC:
        if not (LO <= f < HI) or f not in days: continue
        nxt = days[days > f]
        if not len(nxt): continue
        n = nxt[0]
        ia = np.where((m.day == f.to_datetime64()) & (m.tod >= 22 * 60 + 45))[0]
        ib = np.where((m.day == n.to_datetime64()) & (m.tod >= 9 * 60 + 30))[0]
        if not len(ia) or not len(ib): continue
        e, x = ia[0], ib[0]
        k = m.atm_k[e - 1] if np.isfinite(m.atm_k[e - 1]) else np.nan
        if not np.isfinite(k) or m.seg[e] != m.seg[x]: continue
        tot_b = tot_s = 0.0; ok = True
        for cp in (0, 1):
            o, h, lo, c, v = m.leg(cp, k, e, e + 30); p = np.where(np.isfinite(c) & (v > 0))[0]
            o2, h2, lo2, c2, v2 = m.leg(cp, k, x, x + 60); q = np.where(np.isfinite(c2) & (v2 > 0))[0]
            if not len(p) or not len(q): ok = False; break
            tot_b += o[p[0]] * OPT_MULT[com]; tot_s += o2[q[0]] * OPT_MULT[com]
        if not ok: continue
        sp_in, sp_out = OPT_SPREAD[com]
        gross = tot_s - tot_b
        net = gross - 2 * M.opt_charges(tot_b / 2, tot_s / 2) - 0.5 * sp_in * tot_b - 0.5 * sp_out * tot_s
        rows.append(dict(com=com, fomc=f.date(), prem=tot_b, gross=gross, net=net, fut_move_pct=100 * np.log(m.F[x] / m.F[e])))
R = pd.DataFrame(rows); R.to_csv(OUT / f"fomc_{MODE}.csv", index=False)
print(R.round(1).to_string()); print(R.groupby("com")[["gross", "net"]].agg(["mean", "sum", "count"]).round(0))
