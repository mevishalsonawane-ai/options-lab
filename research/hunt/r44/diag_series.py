"""R44 diagnostic (not a test; changes no verdict): would counting BOTH the WEEK and the MONTH option series explain
why Boss's sweep has about twice our trades? Runs Boss's comparable NSE cells on each series, OOS-design only
(1 Jan 2024 - 13 Apr 2026; the holdout is not read again), and compares trades a session with Boss's.

    R44_OUT_MONTH=<cache>/r44_month python3 research/hunt/r44/diag_series.py
    (the MONTH cache is built with: IRAALGO_LAKE=<month lake> R44_OUT=<cache>/r44_month R44_SERIES=MONTH
     python3 research/hunt/r44/r44data.py build NIFTY BANKNIFTY FINNIFTY)
"""
from __future__ import annotations

import json
import os
from pathlib import Path


import r44cfg as K
import engine as G
import r44data
import run as R

CELLS = [("ema821_adx15_plock", "BANKNIFTY", 0, 1330, -52483), ("ema921_adx20", "BANKNIFTY", 0, 710, -34313),
         ("ema821_adx15_plock", "FINNIFTY", 0, 451, -33714), ("ema921_adx20", "NIFTY", 0, 768, -58515)]
BOSS_SESSIONS = 690          # NSE sessions 1 Jan 2024 - mid Oct 2026 (Boss's OOS), approximate


def series_stats(D, strat, side, keep):
    sig = G.signal_bars(D, strat)
    tr = G.add_nets(D, G.take_trades(D, G.plan_trades(D, sig, side, "full", keep), strat))
    tr = tr[G.in_period(tr.d.values, "OOS_design")]
    n_s = R.sessions_in(D, lambda d: G.in_period(d, "OOS_design"), True)
    return dict(trades=len(tr), net_boss=round(float(tr.boss.sum())), net_real=round(float(tr.real.sum())),
                per_session=round(len(tr) / n_s, 3), sessions=n_s)


def main():
    G.ALLOW_HOLDOUT = False
    month_out = Path(os.environ.get("R44_OUT_MONTH", K.CACHE.root / "r44_month"))
    out = []
    for strat, und, side, bt, bn in CELLS:
        W = r44data.load(und)
        K_out = K.OUT
        K.OUT = month_out
        try:
            M = r44data.load(und)
        finally:
            K.OUT = K_out
        rec = dict(cell=f"{strat}|{und}|{R.SIDES[side]}", boss_trades=bt, boss_net=bn,
                   boss_per_session=round(bt / BOSS_SESSIONS, 3))
        for lab, D in (("week", W), ("month", M)):
            for keep in (False, True):
                rec[f"{lab}{'_incl_expiry' if keep else ''}"] = series_stats(D, strat, side, keep)
        rec["week+month_incl_expiry_per_session"] = round(rec["week_incl_expiry"]["per_session"]
                                                          + rec["month_incl_expiry"]["per_session"], 3)
        out.append(rec)
        print(json.dumps(rec))
    (K.RESULTS / "diag_series.json").write_text(json.dumps(out, indent=1))


if __name__ == "__main__":
    main()
