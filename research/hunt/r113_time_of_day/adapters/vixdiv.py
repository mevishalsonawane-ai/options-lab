"""R113 (26 Oct 2026): VIX divergence (HUNT R1 idea N13) trade list with times, from R1's own code, unchanged
(research/hunt/r1: signals.n13_vixdiv, lib.run_engine, design.run_period / engine_dedup).

The app's arm (VixDivArm.kt / VixDivRules.kt) is R1's frozen pick per index: N13_VIXDIV|a0.002 with the Liquidity exit
on NIFTY (LIQ) and the 15:10 exit on BANKNIFTY (EOD). Checks at 10:30, 11:30, 12:30, 13:30 (decision on the bar before),
first signal of the day: index > +0.2% from its open while VIX > +2% from its open -> 1-ITM PE; both down -> 1-ITM CE.
Option bought at the first priced open in the next 3 minutes. R1 costs: app charges, 0.16% half-spread a side, +5 bps
on a stop; 1 lot = today's lot (NIFTY 65, BANKNIFTY 30) in every year, as R1.

Data: the Drive lake laid out for obuy by adapters/obuy_layout.py (raw, as R1's Dhan files). Run with
    OBUY_SCRATCH=<cache>/r113/obuy python3 -I research/hunt/r113_time_of_day/adapters/vixdiv.py <out_dir>
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
R1 = HERE.parents[1] / "r1"
sys.path.insert(0, str(R1))
import lib as L  # noqa: E402
import design as Dz  # noqa: E402
import signals as S  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

PICK = {"NIFTY": "N13_VIXDIV|a0.002|LIQ", "BANKNIFTY": "N13_VIXDIV|a0.002|EOD"}
PUBLISHED = {  # HUNT_R1_INTERNET.md results table: trades, Rs/day (all sessions of the period, 0 on no-trade days)
    ("NIFTY", "design"): (148, 67), ("NIFTY", "final"): (29, 70),
    ("BANKNIFTY", "design"): (178, 89), ("BANKNIFTY", "final"): (45, 112)}


def ts(d, col: int) -> str:
    return (datetime(d.year, d.month, d.day, 9, 15) + timedelta(minutes=int(col))).strftime("%Y-%m-%d %H:%M")


def n13_requests(u: str, period: str):
    """design.run_period, but only N13's part of signals.build_all (same calls, same order). The full build_all stops
    on BANKNIFTY in another idea's helper (N12 open_volume: a lake day whose first five minutes are missing gives
    round(NaN)); N13 does not read it."""
    P = L.load_index(u)
    mask = P["ok"] & (~P["hold"] if period == "design" else (P["hold"] & P["inrange"]))
    days_ok = np.nonzero(mask)[0]
    vmin, _ = L.vix_minutes(P["days"])
    R = S.Req()
    S.n13_vixdiv(P, R, days_ok, vmin)
    req = R.frame()
    req = req[req.di.isin(days_ok)].reset_index(drop=True)
    el = np.array([np.isfinite(vmin[i]).sum() >= 200 for i in range(P["nd"])])
    return P, mask, req, el


def main(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    rows, summ = [], {}
    for u in ("NIFTY", "BANKNIFTY"):
        for period in ("design", "holdout"):
            P, mask, req, el = n13_requests(u, period)
            req = req[req["var"] == PICK[u]].reset_index(drop=True)
            tr = Dz.engine_dedup(P, req)
            tr = tr[np.isfinite(tr.net)]
            el = el & mask
            nses = int(el.sum())
            per = "final" if period == "holdout" else "design"
            summ[f"{u}_{per}"] = dict(n=len(tr), net=round(float(tr.net.sum()), 2), sessions=nses,
                                      rs_day=round(float(tr.net.sum()) / max(nses, 1), 1),
                                      published_trades_rs_day=PUBLISHED[(u, per)],
                                      first=str(P["days"][int(tr.di.min())]) if len(tr) else None,
                                      last=str(P["days"][int(tr.di.max())]) if len(tr) else None)
            for r in tr.itertuples():
                d = P["days"][int(r.di)]
                rows.append(dict(arm="VIXDIV", und=u, day=d.isoformat(), entry_ts=ts(d, r.ent), exit_ts=ts(d, r.ex),
                                 side="C" if int(r.side) == 0 else "P", net_rs=round(float(r.net), 2), period=per,
                                 expiry=int(bool(P["exp"][int(r.di)]))))
            L.D.market().release()
            print(u, per, summ[f"{u}_{per}"], flush=True)
    df = pd.DataFrame(rows, columns=["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"])
    df = df.sort_values(["entry_ts", "und"]).reset_index(drop=True)
    df.to_csv(out / "VIXDIV.csv", index=False)
    print(json.dumps(summ, indent=1))
    return df, summ


if __name__ == "__main__":
    main(Path(sys.argv[1]).expanduser())
