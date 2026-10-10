"""x2 step D0 (descriptive, index level, PRE ONLY, no option P&L): does day-end option OI build-up (h26 BUopen at
15:19) or the h40 evening positioning (lag so that it is known by 15:20) line up with the NEXT overnight move
(15:19 -> next 09:15 open), beyond the close-strength variables h31 already used?  Used only to decide whether the
OI proxy enters the overnight rules (declared in PREREG.md)."""
import os, sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
from datetime import date, timedelta
import numpy as np, pandas as pd
from scipy import stats
S = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt"
HOLD = date(2025, 10, 1)
f = pd.read_parquet(f"{S}/h31/feat.parquet")
f = f[f.day < HOLD]
FE = ["spot", "BU15", "BUopen", "dOIpc2", "dOIpc5"]
f40 = pd.read_parquet(f"{S}/h40/feat.parquet"); f40["day"] = pd.to_datetime(f40.day).dt.date
for u in ["NIFTY", "BANKNIFTY"]:
    z = np.load(f"{S}/h26/cache/h26/opt_{u}.npz")
    days = [date(1970, 1, 1) + timedelta(days=int(x)) for x in z["days"]]
    X = z["X"].astype(float)
    bu = pd.Series(X[:, 2, 364], index=days)
    mu = bu.rolling(60, min_periods=20).mean().shift(1); sd = bu.rolling(60, min_periods=20).std().shift(1)
    buz = ((bu - mu) / sd).replace([np.inf, -np.inf], np.nan).rename("buz")
    g = f[f.und == u].set_index("day").join(buz)
    # h40 features for day D are computed from D-1 evening data -> known at 15:20 of D
    g = g.join(f40[f40.und == u].set_index("day")[["S01", "S02", "S06", "S07", "S08", "S09", "S12", "S15"]])
    g = g.dropna(subset=["ov", "DAY", "loc"])
    print(f"== {u} n={len(g)}")
    for c in ["DAY", "loc", "LH", "BR", "buz", "S01", "S02", "S06", "S07", "S08", "S09", "S12", "S15"]:
        h = g[list(dict.fromkeys([c, "ov", "DAY", "loc"]))].dropna()
        r, p = stats.spearmanr(h[c], h["ov"])
        # partial: residual of ov and c on DAY, loc
        A = np.c_[np.ones(len(h)), h.DAY, h["loc"]]
        ro = h.ov - A @ np.linalg.lstsq(A, h.ov, rcond=None)[0]
        rc = h[c] - A @ np.linalg.lstsq(A, h[c], rcond=None)[0]
        pr, pp = stats.spearmanr(rc, ro) if c not in ("DAY", "loc") else (np.nan, np.nan)
        print(f"  {c:5s} n={len(h):5d} rho={r:+.3f} p={p:.3g}   partial|DAY,loc rho={pr:+.3f} p={pp:.3g}")
