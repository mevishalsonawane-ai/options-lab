"""Writes research/experiments/entries/r55-*.yaml: one per exit (T0 / T2 / T3 / T5 / H), each covering its 6 cells (entry
12:30 / 14:30 x end 15:05 / 15:15 / settlement), failures included.

    python3 research/hunt/r55/entries.py
"""
from __future__ import annotations

import json

import yaml

import r55cfg as K
from r54report import inr

ENT = K.ROOT / "research" / "experiments" / "entries"
NAME = {"T0": "trail from the fill (R50 tr25, = R54)", "T2": "trail armed after 2x", "T3": "trail armed after 3x",
        "T5": "trail armed after 5x", "H": "hold, no trail"}
RULE = {"T0": "25% trail on minute closes from the fill (also a 25% stop)",
        "T2": "no stop; once a minute close >= 2x the fill, a close <= 0.75 x the best close since sells at the next open",
        "T3": "no stop; once a minute close >= 3x the fill, a close <= 0.75 x the best close since sells at the next open",
        "T5": "no stop; once a minute close >= 5x the fill, a close <= 0.75 x the best close since sells at the next open",
        "H": "no trail"}


def _line(k: str, d: dict, f: dict) -> str:
    m, fm = d["m"], f["m"]
    top = f"{fm['top5']:.0f}%" if fm["top5"] is not None else "n/a (net <= 0)"
    st = f", settled ITM/OTM {fm['itm']}/{fm['otm']}" if k.split("-")[1] == "SETTLE" else ""
    return (f"{k}: design {m['tickets']} tickets net Rs {inr(m['net'])} ({inr(m['per1000'])} per Rs 1,000), p {d['p']:.3f}; "
            f"final {fm['tickets']} tickets, hit {fm['hit']:.0f}%, reached 2x/5x/10x {fm['x2']}/{fm['x5']}/{fm['x10']}, "
            f"net Rs {inr(fm['net'])} ({inr(fm['per1000'])} per Rs 1,000), without top 5 Rs {inr(fm['ex5'])} (top-5 share "
            f"{top}){st}, p {f['p']:.3f}, q {f['q']:.2f}: {'PASS' if f['gates']['pass'] else 'fail'}")


def main():
    des = json.loads((K.RESULTS / "design.json").read_text())
    fin = json.loads((K.RESULTS / "final.json").read_text())
    for x in K.EXITS:
        keys = [f"{e}-{z}-{x}" for e in K.ENTRIES for z in K.ENDS]
        npass = sum(fin["cells"][k]["gates"]["pass"] for k in keys)
        comp = [c for c in fin["compare"] if (c.startswith(f"{x} vs T0") or c.endswith(f"-{x}"))]
        comp_s = [f"{c} (design {inr(des['compare'][c]['mean'])}, final {inr(fin['compare'][c]['mean'])} a day: "
                  f"{fin['compare'][c]['reading']})" for c in comp]
        summ = (f"Rs 1,000 hero ticket (1 lot CE + 1 lot PE, R54's cheap band) with the {NAME[x]}, entered at 12:30 "
                f"(primary) or 14:30, out at 15:05 / 15:15 or held to expiry settlement, on {des['n_days']} design and "
                f"{fin['n_days']} final-test NIFTY / SENSEX expiry days (20 Nov 2024 - 13 Oct 2026). {npass} of 6 cells "
                f"pass. Best trail start at 12:30 on design (per end): {des['best_trail_design']}. "
                + "; ".join(_line(k, des["cells"][k], fin["cells"][k]) for k in keys)
                + ". Paired day-by-day: " + "; ".join(comp_s) + ".")
        ent = dict(
            id=f"r55-{x.lower()}", title=f"Rs 1,000 hero ticket, {NAME[x]}, out at 15:05 / 15:15 / expiry settlement",
            family="calendar-events", date="2026-10-15", doc="research/HUNT_R55_HERO_TRAIL.md",
            hypothesis=(f"On NIFTY / SENSEX own expiry days, the Rs 1,000 CE + PE hero ticket with the {NAME[x]} makes "
                        "money after real costs, beats random afternoon entry minutes, beats the trail from the fill "
                        "(T0), and holding to expiry settlement beats the 15:05 square-off."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake: opt_NIFTY_WEEK / opt_SENSEX_WEEK 2024-2026 (rolling "
                          "ATM+-10 1-minute, rebuilt per held strike), index_1m 2024-2026 (settlement = mean of the "
                          "15:00-15:29 closes); lake to 13 Oct 2026"),
            period={"design": "expiry days 20 Nov 2024 - 31 Mar 2026",
                    "holdout": ("final test: expiry days 1 Apr - 13 Oct 2026, read once (not pristine: R43-R54 saw it; "
                                "R54 read these tickets with T0 and H)")},
            instruments=list(K.UNDS),
            features=["signal source: none (time only, both sides)", "option-chain prices for the strike rule",
                      "stops and trail on the option premium"],
            model_or_rule=("at the bar closing 12:30 (or 14:30): nearest-the-money eligible OTM CE and PE priced Rs 5 - "
                           f"min(30, 1000 / lot); 1 lot each, skipped if fill x lot > Rs 1,000; exit: {RULE[x]}; the "
                           "rest sells at the open of the 15:05 / 15:15 bar, or is cash-settled at the index's "
                           "last-30-minute mean (ITM pays intrinsic less STT on exercise and charges; OTM worthless)"),
            settings="research/hunt/r55/r55cfg.py, research/hunt/r55/r55lib.py",
            prereg="research/hunt/r55/PREREG.md", variants_tried=6,
            results=dict(summary=summ, costs=(
                "R54's pricing: charges in force on the trade date (obuy Costs('dated'), BSE rates for SENSEX); "
                "next-minute fills + 5 bps + 1/2/4-tick half-spread by 5-minute volume (R17 liq), x2.25 from 15:00 and "
                "x2.75 from 15:10; entry limit ref x 1.2 + 0.10 for 3 minutes; gap fills at the worse open; R47 "
                "stray-print guard and foreign-rows filter; 2x spread stress reported. Settlement: no spread; STT on "
                "exercise 0.125% of the settlement value to 31 Mar 2026 and 0.15% from 1 Apr 2026, plus brokerage Rs 20, "
                "exchange, SEBI and GST; OTM pays no sell charges")),
            holdout_read=True, verdict="rejected" if npass == 0 else "inconclusive",
            notes=("BH over all 30 R55 cells in each period. Random twins: 500 per cell, each ticket re-entered at a "
                   "random minute of 12:00-14:45 with the same end and exit. Nothing in the app changes; the 15:05 "
                   "square-off stays for every arm and anything live; holding the hero paper ticket longer is Boss's "
                   "call."))
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(K.EXITS))


if __name__ == "__main__":
    main()
