"""Rough first look (for M2-M4, NOT a validated test): time-series momentum and calendar-month seasonality on MCX
continuous near-month futures 2012-2026, roll days neutralised (see daily_stats.load). Costs 0.1% per position change."""
import sys, numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m1")
from daily_stats import load
SYMS = ["CRUDEOIL", "NATURALGAS", "GOLD", "SILVER", "COPPER", "ZINC", "ALUMINIUM", "LEAD", "NICKEL"]
R = pd.DataFrame({s: load(s).ret for s in SYMS}).fillna(0.0)
M = R.resample("ME").sum()                     # monthly log returns of a held, rolled future
print("months", len(M), M.index[0].date(), M.index[-1].date())
def stat(x):
    x = x.dropna(); return f"mean/mo {100*x.mean():+.2f}%  t={x.mean()/x.std()*np.sqrt(len(x)):+.2f}  n={len(x)}"
print("\n== TSMOM: sign of past L-month return, hold 1 month, equal-weight across 9, vol-scaled to 40%/yr each, cost 0.1%/flip")
vol = R.rolling(60).std().resample("ME").last() * np.sqrt(250)
for L in (1, 3, 6, 12):
    sig = np.sign(M.rolling(L).sum()).shift(1)
    w = (0.40 / vol.shift(1)).clip(upper=3)
    pnl = (sig * w * M) - 0.001 * sig.diff().abs() * w
    port = pnl.mean(axis=1)
    print(f"L={L:2d}  portfolio {stat(port)} | by year:", " ".join(f"{y}:{100*v:+.0f}" for y, v in port.groupby(port.index.year).sum().items()))
    print("       per-commodity t:", " ".join(f"{s}:{pnl[s].mean()/pnl[s].std()*np.sqrt(pnl[s].count()):+.1f}" for s in SYMS))
print("\n== long-only buy-and-hold (rolled) mean/mo:", " ".join(f"{s}:{100*M[s].mean():+.2f}%" for s in SYMS))
print("\n== calendar month mean return % (t) 2012-2026")
for s in ["NATURALGAS", "GOLD", "CRUDEOIL", "SILVER"]:
    g = M[s].groupby(M.index.month)
    print(f"{s:10s}", " ".join(f"{m}:{100*v.mean():+.1f}({v.mean()/v.std()*np.sqrt(len(v)):+.1f})" for m, v in g))
print("\n== day-of-week mean daily return bp (t), 2012-2026")
for s in ["CRUDEOIL", "NATURALGAS", "GOLD", "SILVER"]:
    x = R[s][R[s] != 0]; g = x.groupby(x.index.dayofweek)
    print(f"{s:10s}", " ".join(f"{'MTWTFSS'[d]}:{1e4*v.mean():+.0f}({v.mean()/v.std()*np.sqrt(len(v)):+.1f})" for d, v in g))
print("\n== daily return autocorrelation lag1 (2012-2026 / last 2y)")
for s in SYMS:
    x = R[s][R[s] != 0]; print(f"{s:10s} {x.autocorr(1):+.3f} / {x[x.index>='2024-10-01'].autocorr(1):+.3f}")
