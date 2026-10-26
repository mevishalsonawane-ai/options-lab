"""R113 adapter (26 Oct 2026): the "Hero Rs 1,000 ticket" arm's per-trade list, from R55's own code, unchanged.

Which research code: the app's arm is android/engine .../orb/HeroTicketRules.kt (paper only, Boss's choice, did not pass
R50). Its rules are R50's "cheap" 12:30 cell with Boss's 25% trail and the Rs 1,000 a lot cap, out at 15:05, on NIFTY's and
SENSEX's own expiry days. Since 25 Oct the app's default trail starts at the fill (TrailStart.AT_FILL, R55's recommended
cell). That is exactly R55's cell "1230-1505-T0" (= R54's "1230-1505-tr25", R54's stray-print guard on), so this adapter
runs r55lib.run_cell(days, "1230", "1505", "T0") on R54's day pickles. Nothing about the rule is re-implemented here.

Signal source: the clock only (12:30 on the index's own expiry day). The strike is picked by the OPTION'S OWN PRICE
(the nearest-the-money OTM option priced Rs 5-30 whose lot costs <= Rs 1,000), not by an index chart signal; the trail is
on the option premium.

    IRAALGO_LAKE=<dir with opt_{NIFTY,SENSEX}_WEEK_2024-26 + index_1m_{NIFTY,SENSEX,INDIAVIX}_2024-26> \\
    R54_OUT=<r54 cache> python3 research/hunt/r54/r54data.py build
    IRAALGO_LAKE=... R54_OUT=... python3 research/hunt/r113_time_of_day/adapters/hero.py <out_dir>

Writes <out_dir>/HERO.csv and HERO.json (outside git).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
R55 = os.path.abspath(os.path.join(HERE, "..", "..", "r55"))
sys.path.insert(0, R55)
import r55cfg as K  # noqa: E402  (puts R54, R50, R43, R25 on the path)
import r54data as DD  # noqa: E402
import r54lib as LB  # noqa: E402
import r55lib as L  # noqa: E402

CELL = ("1230", "1505", "T0")
COLS = ["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"]
# research/hunt/r55/results/report.md, cell 12:30 / 15:05 / T0 trail from fill
PUBLISHED = {"design": (248, -14507.0), "final": (107, 1559.0)}


def ts(d: date, row: int) -> str:
    """Grid row -> the IST minute of the bar that STARTS at 09:15 + row (row N = 15:30)."""
    t = datetime(d.year, d.month, d.day, 9, 15) + timedelta(minutes=int(row))
    return t.strftime("%Y-%m-%d %H:%M")


def main(out: str) -> None:
    os.makedirs(out, exist_ok=True)
    LB.load_late()
    rows, res = [], {}
    for part in ("design", "final"):
        days = []
        for u in K.UNDS:
            days += DD.load(u, part)
        days.sort(key=lambda d: (d.day, d.und))
        tr = L.run_cell(days, *CELL)
        net = round(sum(t.net for t in tr), 2)
        n_pub, net_pub = PUBLISHED[part]
        res[part] = dict(n=len(tr), net=net, days=len(days), published_n=n_pub, published_net=net_pub,
                         parity=(len(tr) == n_pub and abs(round(net) - net_pub) <= max(1.0, 0.01 * abs(net_pub))))
        for t in tr:
            x = t.exits[-1]
            rows.append(dict(arm="HERO", und=t.und, day=t.day.isoformat(), entry_ts=ts(t.day, t.fill_row),
                             exit_ts=ts(t.day, x[0]), side="C" if t.side > 0 else "P", net_rs=round(t.net, 2),
                             period=part, expiry=1, _why=x[3]))
    rows.sort(key=lambda r: (r["day"], r["und"], r["entry_ts"], r["side"]))
    with open(os.path.join(out, "HERO.csv"), "w") as f:
        f.write(",".join(COLS) + "\n")
        for r in rows:
            f.write(",".join(str(r[c]) for c in COLS) + "\n")
    why = {}
    for r in rows:
        why[r["_why"]] = why.get(r["_why"], 0) + 1
    meta = dict(
        arm="HERO", app="android/engine/src/main/kotlin/com/optionslab/engine/orb/HeroTicketRules.kt (HeroTicketArm.kt)",
        source=["research/hunt/r55/r55lib.py (run_cell, simulate)", "research/hunt/r55/r55cfg.py",
                "research/hunt/r54/r54lib.py (pick, enter, sell_at, guard)", "research/hunt/r54/r54data.py (days)",
                "research/hunt/r50/r50lib.py (Trade, dated charges)", "research/hunt/r25 (fills, held path)",
                "research/hunt/r113_time_of_day/adapters/hero.py"],
        command=("IRAALGO_LAKE=<lake 2024-26 NIFTY/SENSEX/INDIAVIX> R54_OUT=<cache>/r54 python3 research/hunt/r54/r54data.py "
                 "build && IRAALGO_LAKE=... R54_OUT=... python3 research/hunt/r113_time_of_day/adapters/hero.py <out>"),
        cell="R55 1230-1505-T0 (12:30 bar close, out at the 15:05 bar's open, 25% trail armed at the fill; = R54 1230-1505-tr25)",
        periods={"design": "20 Nov 2024 - 31 Mar 2026 (R50/R54/R55 design)",
                 "final": "1 Apr - 13 Oct 2026 (R55's final test, read once by R54/R55)"},
        results=res, n=len(rows), net=round(sum(r["net_rs"] for r in rows), 2), exit_reasons=why,
        published="R55 results/report.md: design 248 tickets -Rs 14,507; final 107 tickets +Rs 1,559 (app MEASURED string "
                  "-Rs 14,351 / 252 and +Rs 3,047 / 110 came from a 15 Oct re-run on R50's engine, before R54's stray-print guard and "
                  "foreign-rows filter; R54's guard-off figures are -Rs 13,672 / +Rs 3,047, so the guarded R54/R55 cell is "
                  "the reference used here)",
        size="1 lot per ticket (premium <= Rs 1,000 a lot; lot by date per r50cfg.LOTS: NIFTY 25 to 27 Nov 2024, 75, then 65 from 31 Dec 2025; SENSEX 20), each side CE and PE",
        costs="R50's dated Zerodha charges (brokerage, STT, exchange, SEBI, stamp, GST) on both legs; R25 fills: limit at the "
              "ask capped at ref x 1.2 + 0.10 over the 3 minutes after the 12:30 close, R54 stray-print guard and late-minute "
              "spread multiplier (r54/results/liquidity.json); sells at the next bar's open with spread",
        entry_ts="the FILL minute (bar start, IST); exit_ts the minute whose open sold (trail: the bar after the trail close; "
                 "end: the 15:05 bar)",
        expiry="1 for every ticket: the arm trades only on the index's own expiry day (R50's expiry rule from the data)",
        signal_source="clock only (12:30 on expiry day); strike chosen by the option's own PRICE (Rs 5-30, lot <= Rs 1,000), "
                      "not an underlying chart signal; trail and stop on the premium. Not the CLAUDE.md underlying-signal "
                      "default: label it as an option-price pick.",
        caveats=["Boss's choice; did not pass research (R50 0/28, R55 no cell passed).",
                 "The app's default trail start was 'after 2x' 15-25 Oct and is 'from the fill' since 25 Oct; this list is the "
                 "current default (from the fill).",
                 "All entries are at 12:30-12:33 by rule: the entry-time buckets are a single bucket; only exit time and "
                 "holding time vary.",
                 "Lake re-copied 26 Oct (data to 19 Oct); the study window stops at 13 Oct (LAKE_END)."],
    )
    with open(os.path.join(out, "HERO.json"), "w") as f:
        json.dump(meta, f, indent=1, default=str)
    print(json.dumps(res, indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/iraalgo/r113/trades"))
