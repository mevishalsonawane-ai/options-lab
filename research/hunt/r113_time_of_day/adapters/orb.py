"""R113 adapter (26 Oct 2026): the ORB family's per-trade lists (ORB, ORB Fresh, ORB Sweep, Range Fade) as the app has them,
from R3's own engine, unchanged.

Which research code: the app's four ORB-family arms (android/engine .../orb/OrbRules.kt, SweepRules.kt, RangeFadeRules.kt;
app data/OrbArms.kt) trade BANKNIFTY only, 1 lot of the 09:20 ATM, -40 / +40 premium points (Sweep +80) with the ProfitLock
ladder, entries to 13:55 (Sweep 14:25), 15:10 square-off (15:05 on expiry day), ORB / ORB Fresh at most 1 entry a side a day
(ReentryCap.PAPER_CAP), Sweep / Fade at most 2 entries a day with no side cap. That is R3's `r3engine.baseline(arm)` exactly
(R3 "rebuild": "as the app has them today"). R49 / R92 use the 1-a-side cap on every arm (not the app's Sweep / Fade rule),
so R3 is used. The four arms are PARKED in the app since 13 Oct (FamilyPark: off, a tap brings each back).

Signal source: the underlying (BANKNIFTY index 5-minute bars against the 09:15-10:00 opening range); exits on the option
premium. CLAUDE.md default.

    IRAALGO_LAKE=~/.cache/iraalgo/lake R3_OUT=<cache>/r3orb python3 research/hunt/r3orb/r3data.py build BANKNIFTY
    IRAALGO_LAKE=... R3_OUT=... python3 research/hunt/r113_time_of_day/adapters/orb.py <out_dir>

Writes <out_dir>/ORB_{ORB,FRESH,SWEEP,FADE}.csv and .json (outside git).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
R3 = os.path.abspath(os.path.join(HERE, "..", "..", "r3orb"))
sys.path.insert(0, R3)
import r3cfg as K  # noqa: E402
import r3data as D  # noqa: E402
import r3engine as E  # noqa: E402

UND = "BANKNIFTY"
COLS = ["arm", "und", "day", "entry_ts", "exit_ts", "side", "net_rs", "period", "expiry"]
ARM_ID = {"orb": "ORB_ORB", "orb_fresh": "ORB_FRESH", "orb_sweep": "ORB_SWEEP", "range_fade": "ORB_FADE"}
# research/hunt/r3orb/results/rebuild.md, BANKNIFTY row of each arm (2021-09-03 .. 2026-10-12)
PUBLISHED = {"orb": (1207, -123935), "orb_fresh": (1207, -123935), "orb_sweep": (1677, -299428), "range_fade": (1843, -286033)}
APP_FILE = {"orb": "OrbRules.kt", "orb_fresh": "OrbRules.kt", "orb_sweep": "SweepRules.kt", "range_fade": "RangeFadeRules.kt"}


def ts(d: date, row: int) -> str:
    t = datetime(d.year, d.month, d.day, 9, 15) + timedelta(minutes=int(row))
    return t.strftime("%Y-%m-%d %H:%M")


def main(out: str) -> None:
    os.makedirs(out, exist_ok=True)
    ss = D.load(UND, "design")
    summary = {}
    for arm, aid in ARM_ID.items():
        v = E.baseline(arm)
        tr = E.run(ss, v)
        rows = [dict(arm=aid, und=t["und"], day=t["day"].isoformat(), entry_ts=ts(t["day"], t["entry_row"]),
                     exit_ts=ts(t["day"], t["exit_row"]), side="C" if t["side"] > 0 else "P", net_rs=round(float(t["net"]), 2),
                     period="design", expiry=int(bool(t["expiry"])), why=t["why"]) for t in tr]
        with open(os.path.join(out, f"{aid}.csv"), "w") as f:
            f.write(",".join(COLS) + "\n")
            for r in rows:
                f.write(",".join(str(r[c]) for c in COLS) + "\n")
        net = round(sum(r["net_rs"] for r in rows), 2)
        n_pub, net_pub = PUBLISHED[arm]
        why = {}
        for r in rows:
            why[r["why"]] = why.get(r["why"], 0) + 1
        parity = len(rows) == n_pub and abs(net - net_pub) <= 0.01 * abs(net_pub)
        years = {}
        for r in rows:
            y = r["day"][:4]
            years.setdefault(y, [0.0, 0])
            years[y][0] += r["net_rs"]
            years[y][1] += 1
        meta = dict(
            arm=aid, name=E.NAMES[arm], und=UND,
            app=f"android/engine/src/main/kotlin/com/optionslab/engine/orb/{APP_FILE[arm]} (app data/OrbArms.kt); PARKED "
                "since 13 Oct 2026 (FamilyPark), un-parked by one tap",
            source=["research/hunt/r3orb/r3engine.py (baseline, run_session, simulate, _walk, fills, charges)",
                    "research/hunt/r3orb/r3data.py (sessions)", "research/hunt/r3orb/r3cfg.py",
                    "research/hunt/r113_time_of_day/adapters/orb.py"],
            command=("IRAALGO_LAKE=~/.cache/iraalgo/lake R3_OUT=<cache>/r3orb python3 research/hunt/r3orb/r3data.py build "
                     "BANKNIFTY && IRAALGO_LAKE=... R3_OUT=... python3 research/hunt/r113_time_of_day/adapters/orb.py <out>"),
            variant=str(v),
            periods={"design": "R3: BANKNIFTY is a DESIGN index for all its sessions (2021-09-03 .. 2026-10-12); R3's holdout "
                               "was other indices (FINNIFTY, MIDCPNIFTY, SENSEX), not a later time window. R3, R14, R49 and "
                               "R92 have all read these BANKNIFTY sessions: no clean final. R113 may re-label by date."},
            n=len(rows), net=net, by_year={y: [round(a), b] for y, (a, b) in sorted(years.items())}, exit_reasons=why,
            published=f"research/hunt/r3orb/results/rebuild.md, {E.NAMES[arm]} BANKNIFTY row: {n_pub} trades, Rs {net_pub:,}",
            parity=parity, parity_gap=dict(n=len(rows) - n_pub, net=round(net - net_pub, 2)),
            size="1 lot of the day's contract (R3 calendar lots: BANKNIFTY 25, 15 from 21 Jul 2023, 30 from 28 Nov 2024, 35 "
                 "from 27 Jun 2025)",
            costs="R3: research/obuy/costs.py Costs('app') (Zerodha brokerage, STT, exchange, SEBI, stamp, GST; not dated: "
                  "R49/R92 later used dated rates) and R17 Fills('liq') slippage by 5-minute volume; fill at the next minute's "
                  "open after the 5-minute signal bar (within 3 minutes); stops filled at min(level, open)",
            entry_ts="the FILL minute (IST bar start; the minute after the 5-minute signal bar closes)",
            exit_ts="the stop / lock minute whose low hit the resting stop; for target and square-off the minute whose open "
                    "sold (15:10, 15:05 on expiry day)",
            expiry="1 when the session is BANKNIFTY's expiry day by R3's rule from the data (ATM time value at 15:15; weekly "
                   "to Nov 2024, then monthly)",
            signal_source="underlying: BANKNIFTY index 5-minute closes vs the 09:15-10:00 opening range (Sweep: a wick "
                          "beyond and close back inside; Fade: the outer 10% of the range and close back inside); option "
                          "premium for stop / target / ladder",
            caveats=["App arm is parked (off) since 13 Oct 2026; un-retired 7 Oct, retired 6 Oct: it traded on paper only "
                     "briefly.",
                     "On BANKNIFTY, ORB Fresh's list is identical to ORB's: with 1 entry a side a day the first break is "
                     "always fresh (R3, R49).",
                     "Some stops fill in the fill minute itself (exit_ts == entry_ts, holding 0 minutes).",
                     "R3's charges are not dated and its lots are a calendar table (R49/R92 refined both).",
                     "Lake re-copied 26 Oct (data to 19 Oct); R3's window stops at 12 Oct 2026 (r3cfg.LAST_DAY)."],
        )
        with open(os.path.join(out, f"{aid}.json"), "w") as f:
            json.dump(meta, f, indent=1, default=str)
        summary[aid] = dict(n=len(rows), net=net, published=(n_pub, net_pub), parity=parity)
    print(json.dumps(summary, indent=1))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser("~/.cache/iraalgo/r113/trades"))
