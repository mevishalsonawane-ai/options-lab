"""h18 option-BUYING rule from the only index footprint that passed T1-T4: level BREAKS continue (T4), optionally
gated by the dealer-gamma regime (T1). Run with the obuy Lab (fills, dated costs, same-exit random baseline, BH, SPA).

    OBUY_CACHE=<scratch>/hunt/h18/cache flock <scratch>/obuy.lock python3 -I research/hunt/h18/rule.py pre
    OBUY_CACHE=<scratch>/hunt/h18/cache flock <scratch>/obuy.lock python3 -I research/hunt/h18/rule.py hold <vid>

Grid (declared before any P&L, 12 variants):
  levels  R60 (rolling 60-min high/low) | ALL (R60 + prior-day H/L + 09:15-09:45 opening range)
  gamma   none | Aneg (GEX_A < 0 at the last 5-min point before the signal) | Blow (B_lvl < 0: less gamma than usual)
  exits   the Liquidity arm's (-15% premium, 20 min not +5% -> out, 15:10) | same + premium target +30%
Index stop: the broken level -/+ the arm's index-stop size (BN 30, FIN 15, NIFTY 15, SENSEX 50, MIDCP 8).
1-ITM nearest expiry, expiry days skipped, one position at a time per underlying, max 3 per day per underlying.
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
from obuy import config as C  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy.engine import Execution, Exits, StrikeRule  # noqa: E402
from obuy.lab import Lab  # noqa: E402
from obuy.strategies.base import Strategy  # noqa: E402
from obuy.strategies import liquidity as LQ  # noqa: E402

HOLD = pd.Timestamp("2025-10-01")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX", "MIDCPNIFTY")
ISTOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0, "NIFTY": 15.0, "SENSEX": 50.0, "MIDCPNIFTY": 8.0}
OUT = os.path.join(C.CACHE, "h18")


def _events():
    f = os.path.join(OUT, "break_events_all.parquet")
    if os.path.exists(f):
        return pd.read_parquet(f)
    import analyze as A
    E = pd.concat([A.sweep_events(u, "all") for u in UNDS], ignore_index=True)
    E = E[E.kind == "break"].reset_index(drop=True)
    P = pd.read_parquet(os.path.join(OUT, "panel_feat.parquet"))[["und", "day", "col", "gexA", "B_lvl"]]
    E["day"] = pd.to_datetime(E.day)
    E["col"] = ((E.t - 1) // 5) * 5
    E = E.merge(P, on=["und", "day", "col"], how="left")
    E.to_parquet(f)
    return E


def signals(mk, levels="R60", gamma="none", period="pre"):
    E = _events()
    E = E[E.day < HOLD] if period == "pre" else E[E.day >= HOLD]
    if levels == "R60":
        E = E[E.lvl.isin(["R60H", "R60L"])]
    if gamma == "Aneg":
        E = E[E.gexA < 0]
    elif gamma == "Blow":
        E = E[E.B_lvl < 0]
    out = []
    for r in E.itertuples():
        step = C.STEP[r.und]
        out.append(dict(und=r.und, day=r.day.date(), sig_min=int(555 + r.t), side=int(r.side), book=f"brk_{r.und}",
                        idx_stop=float(r.lev - r.side * ISTOP[r.und]), tag=r.lvl))
    return pd.DataFrame(out)


EXITS = [LQ.ARM_EXITS, Exits(stop_pct=0.15, tgt_pct=0.30, time_stop=20, time_gain=0.05, sq_off=15 * 60 + 10)]


def strategy(period, grid=None):
    grid = grid or [dict(levels=lv, gamma=g, period=period) for lv in ("R60", "ALL") for g in ("none", "Aneg", "Blow")]
    return Strategy(name=f"h18brk_{period}", family="break", signal_fn=signals, sig_grid=grid,
                    rules=[StrikeRule(money=1)], exits=EXITS, exe=Execution(expiry="skip"),
                    pos=dict(one_at_a_time=True, max_per_day=3), window=(9 * 60 + 45, 14 * 60 + 30))


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "pre":
        lab = Lab([strategy("pre")], name="h18_pre", pool_k=10, B=2000, pool_all=True).run()
        print(lab.report())
    else:
        ch = json.load(open(os.path.join(OUT, "choice.json")))
        st = strategy("hold", [dict(levels=ch["levels"], gamma=ch["gamma"], period="hold")])
        st.exits = [EXITS[ch["xi"]]]
        lab = Lab([st], name="h18_hold", pool_k=10, B=2000, pool_all=True).run()
        print(lab.report())
