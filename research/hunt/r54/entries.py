"""Writes research/experiments/entries/r54-*.yaml: one per entry time (6 cells each: end 15:05 / 15:10 / 15:15 x trail /
hold), failures included.

    python3 research/hunt/r54/entries.py
"""
from __future__ import annotations

import json

import yaml

import r54cfg as K
from r54report import inr

ENT = K.ROOT / "research" / "experiments" / "entries"
ROLE = {"1230": "E1, the arm being built", "1330": "context", "1400": "context", "1430": "E2, Boss's question"}


def _line(k: str, d: dict, f: dict) -> str:
    m, fm = d["m"], f["m"]
    top = f"{fm['top5']:.0f}%" if fm["top5"] is not None else "n/a (net <= 0)"
    return (f"{k}: design {m['tickets']} tickets net Rs {inr(m['net'])} ({inr(m['per1000'])} per Rs 1,000); final "
            f"{fm['tickets']} tickets, hit {fm['hit']:.0f}%, reached 2x/5x/10x {fm['x2']}/{fm['x5']}/{fm['x10']}, net "
            f"Rs {inr(fm['net'])} ({inr(fm['per1000'])} per Rs 1,000), without top 5 Rs {inr(fm['ex5'])} (top-5 share "
            f"{top}), p {f['p']:.3f}, q {f['q']:.2f}: {'PASS' if f['gates']['pass'] else 'fail'}")


def main():
    des = json.loads((K.RESULTS / "design.json").read_text())
    fin = json.loads((K.RESULTS / "final.json").read_text())
    for e in K.ENTRIES:
        keys = [f"{e}-{z}-{x}" for x in K.EXITS for z in K.ENDS]
        npass = sum(fin["cells"][k]["gates"]["pass"] for k in keys)
        comp = [f"{c} (design {inr(des['compare'][c]['mean'])}, final {inr(fin['compare'][c]['mean'])} a day: "
                f"{fin['compare'][c]['reading']})" for c in fin["compare"] if c.endswith(f"| {e}-hold")
                or (e == "1430" and c.startswith("1430 vs 1230"))]
        summ = (f"Rs 1,000 hero ticket (1 lot CE + 1 lot PE, R50 cheap band capped at Rs 1,000 a lot) entered at "
                f"{e[:2]}:{e[2:]} ({ROLE[e]}) on {des['n_days']} design and {fin['n_days']} final-test NIFTY / SENSEX "
                f"expiry days (20 Nov 2024 - 13 Oct 2026). {npass} of 6 cells pass. "
                + "; ".join(_line(k, des["cells"][k], fin["cells"][k]) for k in keys)
                + ". Paired day-by-day: " + "; ".join(comp) + ".")
        ent = dict(
            id=f"r54-entry-{e}", title=f"Rs 1,000 hero ticket entered at {e[:2]}:{e[2:]}, out by trail or 15:05 / 15:10 / 15:15",
            family="calendar-events", date="2026-10-15", doc="research/HUNT_R54_HERO_1430.md",
            hypothesis=(f"On NIFTY / SENSEX own expiry days, the Rs 1,000 CE + PE hero ticket entered at {e[:2]}:{e[2:]} "
                        "makes money after real costs with Boss's 25% trail or held to 15:05 / 15:10 / 15:15, beats "
                        "random afternoon entry minutes, and (14:30) beats the 12:30 entry; later exits (15:10 / 15:15) "
                        "add to 15:05."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake: opt_NIFTY_WEEK / opt_SENSEX_WEEK 2024-2026 (rolling "
                          "ATM+-10 1-minute, rebuilt per held strike), index_1m 2024-2026; lake to 13 Oct 2026"),
            period={"design": "expiry days 20 Nov 2024 - 31 Mar 2026",
                    "holdout": "final test: expiry days 1 Apr - 13 Oct 2026, read once (not pristine: R43-R50 saw it)"},
            instruments=list(K.UNDS),
            features=["signal source: none (time only, both sides)", "option-chain prices for the strike rule"],
            model_or_rule=(f"at the bar closing {e[:2]}:{e[2:]}: nearest-the-money eligible OTM CE and PE priced Rs 5 - "
                           "min(30, 1000 / lot); 1 lot each, skipped if fill x lot > Rs 1,000; exit 25% trail on minute "
                           "closes (also a 25% stop) or hold; sell the rest at the open of the 15:05 / 15:10 / 15:15 bar"),
            settings="research/hunt/r54/r54cfg.py, research/hunt/r54/r54lib.py",
            prereg="research/hunt/r54/PREREG.md", variants_tried=6,
            results=dict(summary=summ, costs=(
                "charges in force on the trade date (obuy Costs('dated'), BSE rates for SENSEX); next-minute fills + "
                "5 bps + 1/2/4-tick half-spread by 5-minute volume (R17 liq), x2.25 from 15:00 and x2.75 from 15:10 "
                "(measured on design days); entry limit ref x 1.2 + 0.10 for 3 minutes; gap fills at the worse open; "
                "R47 stray-print guard and foreign-rows filter; 2x spread stress reported")),
            holdout_read=True, verdict="rejected",
            notes=("BH over all 24 R54 cells in each period. Random twins: 500 per cell, each ticket re-entered at a "
                   "random minute of 12:00-14:45 with the same end and exit. With the trail, no design ticket was "
                   "open at 15:05, so the end time changes nothing. Nothing in the app changes; the 15:05 square-off "
                   "is Boss's decision."))
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(K.ENTRIES))


if __name__ == "__main__":
    main()
