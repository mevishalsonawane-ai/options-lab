"""h37: lookup vs direct engine run, trade for trade, on a few variants (pre-holdout days only)."""
from __future__ import annotations

import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.engine import Execution, StrikeRule, prepare_many  # noqa: E402
import evaluate as EV  # noqa: E402
import outcomes as OC  # noqa: E402

for (u, sig, tf, par, k) in [("NIFTY", "FIB_B618", 15, "k2", "P20_10"), ("NIFTY", "MP_IB", 5, "tpo", "ARM"),
                             ("NIFTY", "RK_REV", 1, "b0.2pct", "LAD"), ("NIFTY", "EW3", 60, "k2", "T30")]:
    t = EV.trades_for(u, sig, tf, par, k)
    t = t[t.pre].head(400)
    sig_df = pd.DataFrame(dict(und=u, day=[date.fromordinal(int(o)) for o in t.ord], sig_min=t.mi.values + EV.SM0,
                               side=t.side.values, book=np.arange(len(t)).astype(str)))
    exe = Execution(expiry="skip")
    (pk, _), = prepare_many([(sig_df, StrikeRule(money=1), exe, 0, None, False)])
    tr = pk.run(OC.EXITS[k], exe).sort_values("cand")
    hs = EV.HS[u]
    r = tr.net.values - hs * (tr.entry.values + tr.exit.values) * tr.qty.values
    same = len(tr) == len(t) and np.allclose(r, t.net.values, atol=0.05) and np.array_equal(tr.exit_min.values, t.exit_min.values)
    print(u, sig, tf, par, k, len(t), len(tr), "IDENTICAL" if same else "DIFF", float(np.abs(r - t.net.values).max()) if len(tr) == len(t) else None)
