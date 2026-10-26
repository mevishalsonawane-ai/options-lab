"""R113 (26 Oct 2026): Night (R3) trade list with times, from R27's own code, unchanged (research/hunt/r27).

R27's B0 is R3 exactly as the app's NightRules runs it: at 15:19 a strong close (R17 "all agree") that the day-end OI
build-up z agrees with (side x z >= 0.5), the 1-ITM option of the 15:19 close bought at its 15:20 open (first bar
15:20-15:23), sold at the next session's 09:16 open (first bar 09:16-09:21). No entry on an expiry day; never held into
the contract's expiry. 1 lot in force on the entry day; R27 costs (app fills, app charges, 0.16% half-spread).

    R27_OUT=<cache>/r113/r27 python3 research/hunt/r27/r27data.py build        # R27's own builder (once)
    R27_OUT=<cache>/r113/r27 python3 research/hunt/r113_time_of_day/adapters/night_r3.py <out_dir>

R27's Night record keeps the 15:20 entry PRICE but not its minute, so this reads the same lake option rows again
(R27's own reader, 15:20-15:23 only) and takes the first minute with an open, checking it equals the stored price.
Nights entered after 12 Oct 2026 (past R27's lake end) are left out, so the list matches R27's published blocks.
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timedelta
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
R27 = HERE.parents[1] / "r27"
sys.path.insert(0, str(R27))
import r27cfg as K  # noqa: E402
import r27data as D  # noqa: E402
import r27lib as R  # noqa: E402

LAST_ENTRY = date(2026, 10, 12)
PERIOD = {"desc": "design", "wf": "other", "hold": "final"}


def hm(d, row: int) -> str:
    t = datetime(d.year, d.month, d.day, 9, 15) + timedelta(minutes=int(row))
    return t.strftime("%Y-%m-%d %H:%M")


def entry_rows(und: str) -> dict:
    """{(day, strike, side 'C'/'P'): (row, open)} first bar with an open in 15:20-15:23, R27's own reader."""
    o = D._opt_frames(und, K.TRADE_SERIES[und], lambda f: (f.m >= D.R1520) & (f.m < D.R1520 + 4))
    o = o[np.isfinite(o.open)].sort_values("m")
    f = o.groupby(["d", "strike", "side"]).first()
    return {k: (int(r.m), float(r.open)) for k, r in f.iterrows()}


def main(out: Path):
    out.mkdir(parents=True, exist_ok=True)
    rows, bad = [], 0
    for u in K.UNDS:
        nights = [n for n in R.r3_nights(D.load(u, ("desc", "wf", "hold"))) if n.d <= LAST_ENTRY]
        er = entry_rows(u)
        for n in nights:
            t = R.trade(n, n.r3, R.B0)
            if t is None:
                continue
            k = n.strike[n.r3]
            sd = "C" if n.r3 > 0 else "P"
            row, px = er.get((n.d, k, sd), (-1, np.nan))
            if row < 0 or abs(px - n.entry[n.r3]) > 1e-9:
                bad += 1
                continue
            rows.append(dict(arm="NIGHT_R3", und=u, day=n.d.isoformat(), entry_ts=hm(n.d, row), exit_ts=hm(n.nd, t.row),
                             side=sd, net_rs=round(t.net, 2), period=PERIOD[K.part(n.d)], expiry=0))
    rows.sort(key=lambda r: (r["entry_ts"], r["und"]))
    import pandas as pd
    df = pd.DataFrame(rows, columns=["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"])
    df.to_csv(out / "NIGHT_R3.csv", index=False)
    summ = {p: dict(n=int((df.period == p).sum()), net=round(float(df[df.period == p].net_rs.sum()), 2))
            for p in ("design", "other", "final")}
    print(json.dumps(dict(summary=summ, entry_minute_mismatch=bad, n=len(df)), indent=1))
    return df, summ, bad


if __name__ == "__main__":
    main(Path(sys.argv[1]).expanduser())
