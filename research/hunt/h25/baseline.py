"""h25: the "do nothing clever" baseline - every minute 09:15-15:00 on both sides (pre-holdout), per index and exit:
mean gross and net per random entry; plus Rs per premium point per lot and the 1-ITM premium per lot today.

    python3 -I research/hunt/h25/baseline.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import evaluate as EV  # noqa: E402

rows = []
for u in EV.UNDS:
    tb = EV.load_table(u)
    pre = tb["days"] < EV.HOLD
    last = tb["days"] >= tb["days"].max() - 30
    z = np.load(os.path.join(EV.OUT, f"out_{u}.npz"))
    q = z["qty"][last]
    e = z["e"][last]
    lot_now = int(np.nanmax(np.where(q > 0, q, np.nan)))
    prem_now = float(np.nanmedian(e * np.where(q > 0, q, np.nan)))
    for k in EV.EXITS:
        g = tb[f"g_{k}"][pre]
        r = tb[f"r_{k}"][pre]
        t = tb[f"t_{k}"][pre]
        rows.append(dict(und=u, exit=k, n=int((~np.isnan(r)).sum()), gross_tr=np.nanmean(g), net_tr=np.nanmean(r),
                         stress_tr=np.nanmean(t), win_gross=np.nanmean(g > 0), lot_now=lot_now, rs_per_point=lot_now,
                         prem_lot_now=prem_now, lots_in_1L=int(100000 // prem_now)))
df = pd.DataFrame(rows)
df.to_csv(os.path.join(EV.OUT, "baseline.csv"), index=False)
pd.set_option("display.width", 250)
print(df.round(2).to_string())
