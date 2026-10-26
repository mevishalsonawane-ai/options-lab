"""R113 adapter (26 Oct 2026): FT3 "near-ATM morning" as the app runs it, per ticket, from R98's ft3_app.py, unchanged.

The app's arm is android/engine .../orb/Ft3Rules.kt + app data/Ft3Arm.kt (paper, Boss's choice, did not pass R98e). Its
research replay is research/hunt/r98/ft3_app.py ("FT3 as the app runs it", results/ft3_app.md). This adapter calls that
file's own functions (pick_at, fill, net_legs, the cut-offs and marks) on R98's day pickles. The only code here is
ft3_app.run_und's loop written out again so that the fill BAR (kb) and the exit bar are kept: run_und keeps the fill price
but drops its bar. The loop is line for line the same (first mark with a pick, one ticket per index-day, the "app"
fill = start 1, exit = the last close on the bars kb .. the bar starting 15:04). ft3_app.main is NOT run (it writes into
the repo's results/).

Signal source: NOT an underlying chart signal. The clock (5-minute marks 11:00-12:55 on the index's own expiry day) plus
a pick by the OPTION'S OWN PRICE (Rs 6-10) and its Black-76 IV against the ATM IV and its distance in ATM straddles
(option-chain context). Label it as an option-price pick, reported separately (CLAUDE.md).

    R98_LAKE=~/.cache/iraalgo/lake R98_OUT=<cache>/r98 python3 research/hunt/r98/r98data.py build      # day pickles
    R98_LAKE=... R98_OUT=... python3 research/hunt/r113_time_of_day/adapters/ft3.py <out_dir>

Writes <out_dir>/FT3.csv and FT3.json (outside git).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timedelta
from multiprocessing import Pool

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
R98 = os.path.abspath(os.path.join(HERE, "..", "..", "r98"))
sys.path.insert(0, R98)
import ft3_app as F  # noqa: E402
import r98data as R  # noqa: E402
import r98lib as L  # noqa: E402
from hero2 import net_legs  # noqa: E402

COLS = ["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"]
# research/hunt/r98/results/ft3_app.md, row "app (LIMIT, 3-minute window, out 15:05)"
PUBLISHED = dict(tickets=478, total=862909, years={2021: -19603, 2022: 309889, 2023: 226439, 2024: 443221,
                                                    2025: -175093, 2026: 78056})


def ts(d: date, row: int) -> str:
    t = datetime(d.year, d.month, d.day, 9, 15) + timedelta(minutes=int(row))
    return t.strftime("%Y-%m-%d %H:%M")


def run_und(und: str) -> list[dict]:
    """ft3_app.run_und, keeping the fill and exit bars (the 'app' fill only)."""
    out = []
    for day in R.load(und):
        sp, cache = L.spot(day), {}
        for t in F.MARKS:
            p = F.pick_at(day, t, sp, cache)
            if p is None:
                continue
            k, side, px, dist, r = p
            a = day.opts[(k, side)]
            f = F.fill(a, t, px, 1)
            row = dict(und=und, day=day.day, t=t, strike=k, side=side, px=px)
            if f is not None:
                e, kb = f
                c = a[kb:F.T_EXIT + 1, R.C]
                ok = ~np.isnan(c)
                xb = kb + int(np.nonzero(ok)[0][-1]) if ok.any() else None
                row.update(net=net_legs(und, e, [(1.0, float(c[ok][-1]) if ok.any() else 0.0)]), entry=e, kb=kb, xb=xb)
            out.append(row)
            break                                      # one ticket per index-day: the first mark that met the rule
    print(und, len(out), flush=True)
    return out


def main(out: str) -> None:
    os.makedirs(out, exist_ok=True)
    with Pool(int(os.environ.get("R113_PROCS", "2"))) as p:
        parts = p.map(run_und, R.UNDS)
    sig = [r for part in parts for r in part]
    tr = [r for r in sig if "net" in r]
    rows = []
    for r in tr:
        d = r["day"]
        # exit: the close of bar xb (the bar starting 15:04 normally), i.e. the minute xb + 1 (15:05)
        x = ts(d, r["xb"] + 1) if r["xb"] is not None else ""
        rows.append(dict(arm="FT3", und=r["und"], day=d.isoformat(), entry_ts=ts(d, r["kb"]), exit_ts=x, side=r["side"],
                         net_rs=round(float(r["net"]), 2), period="design" if d.year <= 2023 else "other", expiry=1))
    rows.sort(key=lambda z: (z["day"], z["und"]))
    with open(os.path.join(out, "FT3.csv"), "w") as f:
        f.write(",".join(COLS) + "\n")
        for z in rows:
            f.write(",".join(str(z[c]) for c in COLS) + "\n")
    tot = sum(z["net_rs"] for z in rows)
    years = {}
    for z in rows:
        y = int(z["day"][:4])
        years[y] = round(years.get(y, 0.0) + z["net_rs"])
    per = {p_: dict(n=sum(z["period"] == p_ for z in rows), net=round(sum(z["net_rs"] for z in rows if z["period"] == p_), 2))
           for p_ in ("design", "other")}
    parity = len(rows) == PUBLISHED["tickets"] and abs(tot - PUBLISHED["total"]) <= 0.01 * abs(PUBLISHED["total"])
    meta = dict(
        arm="FT3", app="android/engine/src/main/kotlin/com/optionslab/engine/orb/Ft3Rules.kt (app data/Ft3Arm.kt)",
        source=["research/hunt/r98/ft3_app.py (pick_at, fill, cut-offs, marks; run_und's loop re-written to keep bars)",
                "research/hunt/r98/hero2.py (net_legs: R98 charges)", "research/hunt/r98/r98lib.py",
                "research/hunt/r98/r98data.py (day pickles)", "research/hunt/r113_time_of_day/adapters/ft3.py"],
        command=("R98_LAKE=~/.cache/iraalgo/lake R98_OUT=<cache>/r98 python3 research/hunt/r98/r98data.py build && "
                 "R98_LAKE=... R98_OUT=... python3 research/hunt/r113_time_of_day/adapters/ft3.py <out>"),
        periods={"design": "expiry days 2021-2023 (the years whose 10th percentiles set FT3's cut-offs, R98e)",
                 "other": "expiry days 2024 - 19 Oct 2026 (R98b's 'holdout' half, but seen by R98-R98d before FT3 was "
                          "chosen: not a clean final, R98e PREREG)"},
        signals=len(sig), n=len(rows), unfilled=len(sig) - len(rows), net=round(tot, 2), by_period=per, by_year=years,
        published=f"results/ft3_app.md app row: {PUBLISHED['tickets']} tickets, Rs {PUBLISHED['total']:,} total; years "
                  f"{PUBLISHED['years']}",
        parity=parity, parity_gap=dict(n=len(rows) - PUBLISHED["tickets"], net=round(tot - PUBLISHED["total"], 2)),
        size="Rs 5,000 of premium a ticket, fractional quantity as R98 (the app buys whole lots: a little less invested)",
        costs="R98 charges (hero2.net_legs): Rs 20 an order + GST, exchange fees + GST, stamp on the buy, STT 0.15% on the "
              "sell; the app's LIMIT fill = the first of the 3 bars after the mark whose open + 1 tick is within mark x 1.2 + "
              "0.10; sold at the last close by the bar starting 15:04 less 1 tick; under Rs 0.10 = worthless",
        entry_ts="the FILL minute (the bar whose open + 1 tick filled; IST bar start)",
        exit_ts="15:05 normally (the close of the bar starting 15:04, the app's expiry square-off); earlier only when the "
                "option has no later print (then the close minute of its last bar)",
        expiry="1 for every ticket: FT3 trades only on the index's own expiry day (R98's expiry rule from the data)",
        signal_source="NOT the underlying: clock marks 11:00-12:55 on expiry day + the option's own price (Rs 6-10), its "
                      "Black-76 IV vs the ATM IV and distance in ATM straddles (chain context). An option-price pick: "
                      "report separately (CLAUDE.md).",
        caveats=["Boss's choice; did not pass research (R98e SPA p 0.96).",
                 "All exits are at 15:05 by rule: only entry time (11:00-12:58) varies; holding time = 15:05 - entry.",
                 "5 of 478 tickets have exit_ts before 15:05 (12:31-15:04): the strike left the lake's stored ATM+-10 band, "
                 "so ft3_app.py books its last stored close; the app itself would sell at 15:05.",
                 "Rupees are per Rs 5,000 ticket (not per lot): not comparable 1:1 with 1-lot arms.",
                 "Lake re-copied 26 Oct; R98 used the lake to 19 Oct 2026, as here."],
    )
    with open(os.path.join(out, "FT3.json"), "w") as f:
        json.dump(meta, f, indent=1, default=str)
    print(json.dumps(dict(n=len(rows), net=round(tot), years=years, per=per, parity=parity), indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/iraalgo/r113/trades"))
