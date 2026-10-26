"""R113 adapter (26 Oct 2026): the ntfy / IraThinker "mom60" arm's per-trade list, from R110's own code, unchanged.

Signal source: the underlying index (|60-minute return| of the index's closed 1-minute bars >= the index's threshold,
IraThinker's Mom60.Watcher). The ATM monthly option in the move's direction is bought; the 30% stop is on the premium.
Nothing about the rule is re-implemented here: the signals are R110Runner.java's mom60 CSVs (IraThinker's own Java
engine) and the trades are r110study.run_directional(..., "S3", Fill(1.0), real=True), exactly as R110 priced them.

    python -I research/hunt/r113_time_of_day/adapters/mom60.py <release_dir> <bars_dir> <signals_dir> <out_dir>

Inputs as R110 (HUNT_R110_IRATHINKER.md "To reproduce"): research-data release files, r110prep.py's bar CSVs and
R110Runner's mom60_<IDX>.csv. Writes <out_dir>/MOM60.csv and MOM60.json (outside git). Only NIFTY and BANKNIFTY have
real option prices; FINNIFTY / SENSEX are index points before option costs in R110 and are left out (no net after
charges). Release files are untrusted data: read only, run with python -I.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
R110 = os.path.abspath(os.path.join(HERE, "..", "..", "r110_irathinker"))
sys.path.insert(0, R110)
import r110study as S  # noqa: E402

PUBLISHED = {"NIFTY": (118, 5809), "BANKNIFTY": (213, 1407)}   # HUNT_R110 table, design (<= 30 Sep 2025), S3
COLS = ["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"]


def hhmm(d: date, m: int) -> str:
    t = S.OPEN_M + int(m)
    return f"{d.isoformat()} {t // 60:02d}:{t % 60:02d}"


def main(rel: str, bars: str, sig: str, out: str) -> None:
    os.makedirs(out, exist_ok=True)
    rows, parity, extra = [], {}, {}
    for und in ("NIFTY", "BANKNIFTY"):
        mk = S.Market(und, bars, rel)
        mom = pd.read_csv(os.path.join(sig, f"mom60_{und}.csv"))
        t = S.run_directional(mk, mom, "S3", S.Fill(1.0), True)
        des = t[t["day"] <= S.DESIGN_END]
        got = (int(len(des)), int(round(float(des["net"].sum()))))
        parity[und] = dict(published_design=list(PUBLISHED[und]), reproduced_design=list(got),
                           match=got == PUBLISHED[und], signals=int(len(mom)), priced_all=int(len(t)))
        extra[und] = dict(why=t["why"].value_counts().to_dict(), first=str(t["day"].min()), last=str(t["day"].max()))
        for r in t.to_dict("records"):
            d = r["day"]
            rows.append(dict(arm="MOM60", und=und, day=d.isoformat(), entry_ts=hhmm(d, r["entry_min"]),
                             exit_ts=hhmm(d, r["exit_min"]), side="C" if r["right"] == "CE" else "P",
                             net_rs=round(float(r["net"]), 2), period="design" if d <= S.DESIGN_END else "final",
                             expiry=int(mk.expiry.get(d) == d)))
        del mk
    df = pd.DataFrame(rows, columns=COLS).sort_values(["day", "entry_ts", "und"])
    df.to_csv(os.path.join(out, "MOM60.csv"), index=False)
    per = {p: dict(n=int((df["period"] == p).sum()), net=round(float(df.loc[df["period"] == p, "net_rs"].sum()), 0))
           for p in ("design", "final")}
    meta = dict(arm="MOM60", n=int(len(df)), net=round(float(df["net_rs"].sum()), 0), by_period=per, parity=parity,
                exits=extra)
    with open(os.path.join(out, "MOM60.parity.json"), "w") as f:
        json.dump(meta, f, indent=1, default=str)
    print(json.dumps(meta, default=str))


if __name__ == "__main__":
    main(*sys.argv[1:5])
