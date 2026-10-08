"""h27: detail for one variant (premium tied up, months, bootstrap losing-month probability, 1-lakh sizing).
    python3 -I research/hunt/h27/detail.py pre|hold RULE ZCOL UND EXIT"""
import os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE); sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
from datetime import date
import numpy as np, pandas as pd
import analyse as A
mode, rule, zcol, und, ex = sys.argv[1:6]
T, P = A.load(mode)
P = P[(P.day < date(2025, 10, 1)) if mode == "pre" else (P.day >= date(2025, 10, 1))]
P = P[P.und == und]
sel = A.signals(P, rule, zcol)
t = T[(T.und == und) & (T.exit_id == ex)].merge(sel[["day", "sig_min", "side"]], on=["day", "sig_min", "side"])
ndays = T[T.und == und].day.nunique()
t["month"] = [f"{d.year}-{d.month:02d}" for d in t.day]
t["prem"] = t.entry * t.qty
mo = t.groupby("month").net.sum()
allm = sorted({f"{d.year}-{d.month:02d}" for d in T[T.und == und].day})
mo = mo.reindex(allm).fillna(0)
rng = np.random.default_rng(1)
# bootstrap months of ~21 trading days from the per-day P&L (0 on no-trade days)
d = t.groupby("day").net.sum().reindex(sorted(T[T.und == und].day.unique())).fillna(0).values
bm = np.array([rng.choice(d, 21).sum() for _ in range(5000)])
print(f"{mode} {rule}:{zcol}|{und}|{ex}: trades {len(t)} of {ndays} days; hit {np.mean(t.net>0):.3f}; "
      f"gross {t.gross.sum():.0f} net {t.net.sum():.0f} stress {t.stress.sum():.0f}; net/day {t.net.sum()/ndays:.1f}; "
      f"gross/day {t.gross.sum()/ndays:.1f}; avg win {t.net[t.net>0].mean():.0f} avg loss {t.net[t.net<=0].mean():.0f}")
print(f"premium per lot: median Rs {t.prem.median():.0f}, max {t.prem.max():.0f}; lots affordable on Rs 1 lakh ~ {int(1e5 // t.prem.quantile(0.9))}")
print(f"months: {len(mo)}, losing {int((mo<0).sum())}, worst {mo.min():.0f}, best {mo.max():.0f}; bootstrap P(losing month) {np.mean(bm<0):.2f}")
print("exit reasons:", t.why.value_counts().to_dict())
print("by side:", t.groupby("side").net.agg(["size", "sum"]).round(0).to_dict())
