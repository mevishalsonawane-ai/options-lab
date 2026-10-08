"""h25 parity check: a few signal sets run directly through the obuy engine (prepare_many + run + positions) must
equal the outcome-table lookup + greedy of evaluate.py (net before the extra spread, trade for trade).

    OBUY_CACHE=<scratch>/hunt/h25/cache H25_TEST=SENSEX python3 -I research/hunt/h25/check.py
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
from datetime import date  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, StrikeRule, positions, prepare_many  # noqa: E402
import evaluate as EV  # noqa: E402
import outcomes as OC  # noqa: E402
import sigs  # noqa: E402

u = os.environ.get("H25_TEST", "SENSEX")
z = np.load(os.path.join(EV.OUT, f"out_{u}{'_test' if EV.TEST else ''}.npz"))
mk = market()
ix = mk.index(u)
days = set(z["days"].tolist())
allord = np.array([d.toordinal() for d in ix.days])
dmap = {o: i for i, o in enumerate(z["days"])}
bad = 0
for tf, name, k in ((5, "EMA_PX20", "P20"), (1, "CDLENGULFING", "ARM"), (15, "CH_INSIDE", "T30"), (3, "MACD_X", "LAD")):
    B = sigs.bars(ix.mat(), tf)
    s = sigs.compute(B)[name]
    bdi = np.array([dmap.get(o, -1) for o in allord[B["dpos"]]])
    ev = np.nonzero((bdi >= 0) & (B["col_end"] <= EV.MW - 1) & (s != 0))[0]
    sig = pd.DataFrame(dict(und=u, day=[date.fromordinal(int(allord[B["dpos"][i]])) for i in ev],
                            sig_min=B["col_end"][ev] + EV.SM0, side=s[ev].astype(int), book="b"))
    exe = Execution(expiry="skip")
    (pk, _), = prepare_many([(sig, StrikeRule(money=1), exe, 0, None, False)])
    tr = pk.run(OC.exits(u)[k], exe)
    tr = positions(tr, one_at_a_time=True, max_per_day=EV.MAXDAY)
    # lookup path
    di0, mi0, sx = bdi[ev], B["col_end"][ev], (s[ev] > 0).astype(np.int64)
    n = z[f"n_{k}"][di0, mi0, sx]
    v = ~np.isnan(n)
    di, mi, sxx = di0[v], mi0[v], sx[v]
    sel = EV.greedy(di, mi, z[f"x_{k}"][di, mi, sxx])
    net = z[f"n_{k}"][di[sel], mi[sel], sxx[sel]]
    ok = len(tr) == len(sel) and abs(tr.net.sum() - net.sum()) < 0.05 * len(sel) + 1
    bad += not ok
    print(u, tf, name, k, "engine", len(tr), round(tr.net.sum(), 2), "lookup", len(sel), round(float(net.sum()), 2),
          "OK" if ok else "MISMATCH")
print("parity", "PASS" if not bad else "FAIL")
