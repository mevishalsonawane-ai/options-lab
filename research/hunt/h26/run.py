"""h26 trigger strategies through the obuy Lab (fills with the measured spread, app charges, same-exit random baseline,
BH / Holm, White RC / SPA, walk-forward, DSR, PBO).

    OBUY_CACHE=<scratch>/hunt/h26/cache flock <scratch>/obuy.lock python3 -I research/hunt/h26/run.py pre <UND|CONS>
    ... run.py hold <json list of strategy|s|x picks>
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
from obuy.costs import Fills  # noqa: E402
from obuy.engine import LADDER, Execution, Exits, StrikeRule  # noqa: E402
from obuy.lab import Gates, Lab  # noqa: E402
from obuy.strategies.base import Strategy  # noqa: E402
from obuy.strategies import liquidity as LQ  # noqa: E402
import feats as FT  # noqa: E402

HOLD = pd.Timestamp("2025-10-01").date()
HS_BPS = {"BANKNIFTY": 16, "NIFTY": 16, "MIDCPNIFTY": 21, "FINNIFTY": 42, "SENSEX": 16}   # h24 real half-spreads
SQ = 15 * 60 + 10
EXITS = [LQ.ARM_EXITS,                                                        # X0 Liquidity arm
         Exits(stop_pts=20, tgt_pts=20, sq_off=SQ),                           # X1 20 / 20 points
         Exits(stop_pct=0.15, tgt_pct=0.30, sq_off=SQ),                       # X2 15% / 30%
         Exits(stop_pct=0.15, tgt_pct=0.30, ladder=LADDER, ladder_ref_pct=0.30, sq_off=SQ),   # X3 ladder
         Exits(stop_pct=0.15, time_stop=15, time_gain=None, sq_off=SQ),       # X4 15-min time stop
         Exits(stop_pct=0.15, time_stop=30, time_gain=None, sq_off=SQ),       # X5 30 min
         Exits(stop_pct=0.15, time_stop=60, time_gain=None, sq_off=SQ)]       # X6 60 min
# AMENDMENT 1: premium-point exits, targets +15/+20/+25/+30 x stops -10/-15/-20 (the +20/-20 pair is X1) -> X7..X17
EXITS += [Exits(stop_pts=s, tgt_pts=t, sq_off=SQ) for t in (15, 20, 25, 30) for s in (10, 15, 20) if (t, s) != (20, 20)]


def signals(mk, trig, und, p, sgn=1, period="pre", v=1):
    if isinstance(p, list):
        p = tuple(p)
    days, S = FT.state(trig, und, p)
    ev = FT.events(days, S)
    rows = []
    for d, t, s in ev:
        if (period == "pre") != (d < HOLD):
            continue
        rows.append(dict(und=und, day=d, sig_min=int(C.OPEN_M + t), side=int(s * sgn), book=f"{trig}_{und}"))
    return pd.DataFrame(rows, columns=["und", "day", "sig_min", "side", "book"])


def strategies(unds, trigs, period="pre"):
    out = []
    for u in unds:
        exe = Execution(fills=Fills(mode="app", bps_mkt=HS_BPS[u], bps_stop=HS_BPS[u] + 5), expiry="skip")
        for trig in trigs:
            for sgn in ((1, -1) if trig in FT.SIGNED else (1,)):
                nm = f"{trig}{'' if sgn > 0 else 'rev'}_{u}"
                grid = [dict(trig=trig, und=u, p=list(p) if isinstance(p, tuple) else p, sgn=sgn, period=period)
                        for p in FT.GRID[trig]]
                out.append(Strategy(name=nm, family=trig, signal_fn=signals, sig_grid=grid, rules=[StrikeRule(money=1)],
                                    exits=list(EXITS), exe=exe, pos=dict(one_at_a_time=True, max_per_day=3),
                                    window=(9 * 60 + 30, 14 * 60 + 30)))
    return out


if __name__ == "__main__":
    mode, what = sys.argv[1], sys.argv[2]
    if mode == "pre":
        if what == "CONS":
            sts = strategies(["BANKNIFTY", "NIFTY"], FT.CONS_TRIGS)
            g = Gates(train_years=1, min_train_trades=10)
        else:
            sts = strategies([what], FT.OPT_TRIGS)
            g = Gates()
        lab = Lab(sts, name=f"h26_pre_{what}", pool_k=10, B=2000, gates=g).run()
        print(lab.report())
    else:
        picks = json.loads(what)            # [{"strategy":..., "s":i, "x":k}, ...]
        sts = []
        for pk in picks:
            nm = pk["strategy"]
            trig_s, u = nm.rsplit("_", 1)
            trig = trig_s[:-3] if trig_s.endswith("rev") else trig_s
            st = [s for s in strategies([u], [trig], period="hold") if s.name == nm][0]
            st.sig_grid = [st.sig_grid[pk["s"]]]
            st.exits = [EXITS[pk["x"]]]
            st.name = nm + f"_s{pk['s']}x{pk['x']}"
            sts.append(st)
        lab = Lab(sts, name=sys.argv[3] if len(sys.argv) > 3 else "h26_hold", pool_k=10, B=2000, pool_all=True,
                  gates=Gates(train_years=0, min_train_trades=0)).run()
        print(lab.report())
