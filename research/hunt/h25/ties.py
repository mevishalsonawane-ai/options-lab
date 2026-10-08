"""h25: how often do a premium target and a premium stop both trade inside the same option minute? (The engine checks
resting stops/targets on the option's own 1-minute HIGH/LOW and assumes the STOP first in such a tie.)
A fixed 3% random sample of all outcome-table entries (every minute, both sides, 5 indices), every exit that has both a
premium stop and a premium target.

    OBUY_CACHE=<scratch>/hunt/h25/cache flock <scratch>/obuy.lock python3 -I research/hunt/h25/ties.py
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
from obuy import config as C  # noqa: E402
from obuy.costs import floor_tick  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, StrikeRule, prepare_many  # noqa: E402
import outcomes as OC  # noqa: E402

rng = np.random.default_rng(25)
mk = market()
rows = []
for u in ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]:
    ix = mk.index(u)
    days = [d for d in ix.days if not ix.d[d]["exp"]]
    n = len(days) * OC.M * 2
    pick = np.sort(rng.choice(n, int(n * 0.03), replace=False))
    di, rem = np.divmod(pick, OC.M * 2)
    mi, sx = np.divmod(rem, 2)
    sig = pd.DataFrame(dict(und=u, day=np.array(days, dtype=object)[di], sig_min=mi + OC.SM0, side=np.where(sx, 1, -1),
                            book=np.arange(len(pick)).astype(str)))
    exe = Execution(expiry="skip")
    (pk, _), = prepare_many([(sig, StrikeRule(money=1), exe, 0, None, False)])
    exs = {"PCT": OC.exits(u)["PCT"], "P20": OC.exits(u)["P20"], **OC.exits2(u)}
    H = pk.get("H", slice(None))
    L = pk.get("L", slice(None))
    e = pk.meta.e.values
    for k, ex in exs.items():
        tr = pk.run(ex, exe)
        pos = pd.Series(np.arange(len(pk.meta)), index=pk.meta.cand.values).loc[tr.cand.values].values
        ee = e[pos]
        if ex.stop_pct:
            stop = floor_tick(ee * (1 - ex.stop_pct))
            tgt = ee * (1 + ex.tgt_pct)
        else:
            stop = floor_tick(ee - ex.stop_pts)
            tgt = ee + ex.tgt_pts
        col = tr.exit_min.values - C.OPEN_M
        ok = (col >= 0) & (col < C.W)
        h = np.where(ok, H[pos, np.clip(col, 0, C.W - 1)], np.nan)
        lo = np.where(ok, L[pos, np.clip(col, 0, C.W - 1)], np.nan)
        is_stop = tr.why.values == "stop"
        tie = is_stop & (lo <= stop + 1e-9) & (h >= tgt - 1e-9)
        rows.append(dict(und=u, exit=k, trades=len(tr), stop_exits=int(is_stop.sum()),
                         target_exits=int((tr.why.values == "target").sum()), ties=int(tie.sum()),
                         tie_pct_of_trades=100 * tie.mean(), tie_pct_of_stops=100 * tie.sum() / max(is_stop.sum(), 1),
                         tie_net_if_target=float(((tgt - stop) * tr.qty.values)[tie].sum())))
    mk.release(u)
    print(u, flush=True)
df = pd.DataFrame(rows)
df.to_csv(os.path.join(OC.OUT, "ties.csv"), index=False)
pd.set_option("display.width", 200)
print(df.round(3).to_string())
g = df.groupby("exit")[["trades", "stop_exits", "ties"]].sum()
g["tie_pct_of_trades"] = 100 * g.ties / g.trades
print(g.round(3).to_string())
print("ALL", df.trades.sum(), df.ties.sum(), round(100 * df.ties.sum() / df.trades.sum(), 3), "%")
