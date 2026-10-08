"""h38 trigger strategies through the obuy Lab (h24 real half-spread fills, app charges, same-exit random baseline,
BH / Holm, White RC / SPA, walk-forward, DSR, PBO). See PREREG.md.

    OBUY_CACHE=<scratch>/hunt/h38/cache flock <scratch>/obuy.lock python3 -I research/hunt/h38/run.py pre BASIS|DAILY
    ... run.py partB                       # minute futures OI, holdout window only, single pre-registered look
    ... run.py hold '<json list of {strategy, s, x}>' [name]
"""
from __future__ import annotations

import json
import os
import pickle
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
from obuy import overfit as OF  # noqa: E402
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
EXITS += [Exits(stop_pts=s, tgt_pts=t, sq_off=SQ) for t in (15, 20, 25, 30) for s in (10, 15, 20) if (t, s) != (20, 20)]
OUTD = os.path.join(C.SCRATCH, "hunt", "h38")


def signals(mk, trig, und, p, sgn=1, period="pre", v=1):
    if isinstance(p, list):
        p = tuple(p)
    if trig in FT.DAILY_A:
        ev = FT.daily_events(und, p)
    else:
        days, S = FT.state(trig, und, p)
        ev = FT.events(days, S)
    rows = []
    for d, t, s in ev:
        if period != "any" and (period == "pre") != (d < HOLD):
            continue
        rows.append(dict(und=und, day=d, sig_min=int(C.OPEN_M + t), side=int(s * sgn), book=f"{trig}_{und}"))
    return pd.DataFrame(rows, columns=["und", "day", "sig_min", "side", "book"])


def strategies(trigs, unds_of, period="pre"):
    out = []
    for trig in trigs:
        for u in unds_of(trig):
            exe = Execution(fills=Fills(mode="app", bps_mkt=HS_BPS[u], bps_stop=HS_BPS[u] + 5), expiry="skip")
            daily = trig in FT.DAILY_A
            for sgn in ((1, -1) if trig in FT.SIGNED else (1,)):
                nm = f"{trig}{'' if sgn > 0 else 'rev'}_{u}"
                grid = [dict(trig=trig, und=u, p=list(p) if isinstance(p, tuple) else p, sgn=sgn, period=period)
                        for p in FT.GRID[trig]]
                win = (C.OPEN_M + FT.DAILY_COL, C.OPEN_M + FT.DAILY_COL) if daily else (9 * 60 + 30, 14 * 60 + 30)
                out.append(Strategy(name=nm, family=trig, signal_fn=signals, sig_grid=grid, rules=[StrikeRule(money=1)],
                                    exits=list(EXITS), exe=exe,
                                    pos=dict(one_at_a_time=True, max_per_day=1 if daily else 3), window=win))
    return out


def unds_of(trig):
    if trig in FT.DAILY_A:
        return FT.UNDS_DAILY
    if trig in FT.PART_B:
        return FT.UNDS_B
    return FT.UNDS_A[trig]


def tod_baseline(lab, name, width=30):
    """time-of-day matched random baseline: pool alternatives whose minute is within +-width of the parent's signal."""
    V = lab.V if hasattr(lab, "V") else None
    rows = []
    for vid, tr in lab.trades.items():
        if tr is None or len(tr) < 10 or tr.net.sum() <= 0:
            continue
        pt = lab.pool_trades(vid)
        if pt is None or not len(pt):
            continue
        sm = tr.set_index("cand").sig_min
        par = pt.parent.astype(int) if pt.parent.dtype != object else pt.parent.astype(str).str.split("|").str[0].astype(int)
        keep = (pt.sig_min.values - sm.reindex(par.values).values)
        pt2 = pt[np.abs(keep) <= width]
        rb = OF.random_baseline(tr, pt2, B=2000)
        rows.append(dict(vid=vid, p_tod=rb["p"], n_tod=rb["n_used"], null_tod=rb.get("null_mean")))
    R = pd.DataFrame(rows, columns=["vid", "p_tod", "n_tod", "null_tod"])
    R.to_csv(os.path.join(OUTD, f"tod_{name}.csv"), index=False)
    return R


if __name__ == "__main__":
    mode = sys.argv[1]
    if mode == "pre":
        what = sys.argv[2]
        trigs = FT.INTRA_A if what == "BASIS" else FT.DAILY_A
        sts = strategies(trigs, unds_of)
        name = f"h38_pre_{what}"
        lab = Lab(sts, name=name, pool_k=10, B=2000, gates=Gates()).run()
        print(lab.report())
        R = tod_baseline(lab, name)
        print(f"time-of-day matched baseline: {len(R)} profitable variants checked; p_tod < .05: {(R.p_tod < .05).sum()}")
    elif mode == "partB":
        sts = strategies(FT.PART_B, unds_of, period="any")
        name = "h38_partB"
        lab = Lab(sts, name=name, pool_k=10, B=2000, pool_all=True, gates=Gates(train_years=0, min_train_trades=0)).run()
        print(lab.report())
        R = tod_baseline(lab, name)
        print(R.to_string())
    else:
        picks = json.loads(sys.argv[2])            # [{"strategy":..., "s":i, "x":k}, ...]
        sts = []
        for pk in picks:
            nm = pk["strategy"]
            trig_s, u = nm.rsplit("_", 1)
            trig = trig_s[:-3] if trig_s.endswith("rev") else trig_s
            st = [s for s in strategies([trig], lambda t: [u], period="hold") if s.name == nm][0]
            st.sig_grid = [st.sig_grid[pk["s"]]]
            st.exits = [EXITS[pk["x"]]]
            st.name = nm + f"_s{pk['s']}x{pk['x']}"
            sts.append(st)
        name = sys.argv[3] if len(sys.argv) > 3 else "h38_hold"
        lab = Lab(sts, name=name, pool_k=10, B=2000, pool_all=True, gates=Gates(train_years=0, min_train_trades=0)).run()
        print(lab.report())
        R = tod_baseline(lab, name)
        print(R.to_string())
