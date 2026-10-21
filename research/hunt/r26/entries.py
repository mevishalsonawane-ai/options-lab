"""Writes research/experiments/entries/r26-*.yaml: one per rule (failures included) plus the descriptive check.

    python3 research/hunt/r26/entries.py      # from results/results.json and results/describe.json
"""
from __future__ import annotations

import json

import yaml

import r26cfg as K26
import r26lib as L

ENT = K26.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R26_OI_WALL_BLAST.md"
PREREG = "research/hunt/r26/PREREG.md"
DATA = ("Dhan lake on Drive (IraAlgo-data-lake): options_rolling_1m opt_{NIFTY,BANKNIFTY,SENSEX,FINNIFTY}_WEEK (ATM+-10, "
        "nearest expiry, OI per strike), index_1m NIFTY/BANKNIFTY/SENSEX/FINNIFTY/INDIAVIX; expiry days found from the "
        "data (R25 loader); BANKNIFTY weeklies to 2024-11-13, FINNIFTY to 2024-11-19; 13 Oct 2026 not in the lake")
COSTS = ("app charges at today's rates (Rs 20/order, STT 0.15% sell, exchange NSE/BSE, SEBI, stamp, GST); fills at the "
         "next open + 5 bps + a 1/2/4-tick half-spread by 5-min volume; stress x2 spread; out-of-range prices at 0")
PERIOD = dict(design="ALL expiry days 2021-08-05..2026-10-08 as an out-of-sample test of a third-party rule (no fitted "
                     "parameter): NIFTY 271, BANKNIFTY 172, SENSEX 175, FINNIFTY 152 (770); R25's blocks reported "
                     "separately (2021-22: 201, 2023-26: 569)")
BASE = ("Boss's OI wall blast framework: 13:45-14:30, spot beyond the 13:00 call (put) wall for 16 one-minute closes, "
        "the wall's OI down >= 15% (NIFTY/SENSEX) or 25% (BANKNIFTY/FINNIFTY) from its peak since 12:00, chain volume "
        "15 min >= 2x the session's 15-min average, price with the move and summed call (put) OI falling; ATM+1 CE / "
        "ATM-1 PE in Rs 15-30; Rs 2,000 a trade; target 4x else 15:05")


def main():
    r = json.loads((K26.RESULTS / "results.json").read_text())
    ds = json.loads((K26.RESULTS / "describe.json").read_text())
    ENT.mkdir(parents=True, exist_ok=True)
    for rule in L.RULES:
        v = r["rules"][rule.id]
        m, b = v["m"], v["blocks"]
        s = (f"{m['trades']} trades on {m['expiries']} expiries ({m['up']} UP / {m['down']} DOWN), hit {m['hit_pct']}%, "
             f">= 4x {m['x4_pct']}%, net Rs {m['net']:+,.0f} ({m['per_1000_risked']:+.0f} per Rs 1,000 risked); "
             f"2021-22 {b['2021-22']['trades']} trades Rs {b['2021-22']['net']:+,.0f}, 2023-26 {b['2023-26']['trades']} "
             f"trades Rs {b['2023-26']['net']:+,.0f}; worst day Rs {m['worst_day']:+,.0f}, max DD Rs {m['max_dd']:+,.0f}, "
             f"net without the best day Rs {m['net_without_best_day']:+,.0f}; random-time twin p {v['twins']['p']} "
             f"(twin mean Rs {v['twins']['mean']:+,.0f}), BH q {v['q']} (family {r['bh_size']}). "
             + ("PASSED the gates." if v["passes"] else "FAILED the gates."))
        e = dict(id=f"r26-{rule.id.lower()}", title=f"OI wall blast {rule.id}: {rule.note}", family="outside-strategies",
                 date="2026-10-14", doc=DOC,
                 hypothesis=("A third-party hero-zero framework (call/put wall break held 15+ min with wall OI unwinding "
                             "and a volume spike, ATM+1 at Rs 15-30) makes money after costs on every expiry."
                             if rule.id != "B1" else "The OI-unwinding conditions add value over the same trigger without them."),
                 data_version=DATA, period=PERIOD, instruments=list(rule.unds),
                 features=["option-chain OI per strike (walls)", "index minutes", "option-chain volume", "ATM+-1 option prices"],
                 model_or_rule=f"{BASE}. This variant: {rule.note}", settings="research/hunt/r26/r26lib.py", prereg=PREREG,
                 variants_tried=len(L.RULES), results=dict(summary=s, costs=COSTS), holdout_read=None,
                 verdict="paper-only" if v["passes"] else "rejected",
                 notes=("One of 10 R26 rules (P + 8 alternative readings + the no-OI baseline); BH across R21 9 + R25 9 + "
                        "R26 10 = 28. Every parameter comes from the external text, so all expiries are out-of-sample; "
                        "no holdout was locked."))
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    w, fu = ds["walls"], ds["funnel"]
    bw = ds["blast_wall_oi"]
    pre = ds["wall_oi_at_1345_vs_blast"]
    s = (f"{w['n']} expiry-sides: the 13:00 wall is the outermost stored strike on {w.get('outermost', 0)}, no OI on "
         f"{w.get('no wall', 0)} (SENSEX 2023-24). Spot closed beyond the wall in 13:45-14:30 on "
         f"{fu['1 spot beyond the wall (a close)']}, held 16 closes on {fu['2 broke and held 16 closes']}, + wall OI down "
         f">= theta {fu['3 + wall OI down >= theta']}, + volume {fu['4 + volume spike']}, full trigger "
         f"{fu['5 + price and summed OI (full trigger)']}. ATM+-1 at 13:45 reached 4x on {bw['blast']['n']} of "
         f"{bw['blast']['n'] + bw['no blast']['n']}; the wall's OI fell >= theta during {bw['blast'].get('falling >= theta', 0)} "
         f"blasts and {bw['no blast'].get('falling >= theta', 0)} non-blasts. Point-in-time at 13:45 the wall's OI state "
         f"does not lift the blast rate: " + ", ".join(f"{k} {v['rate_pct']}% (n {v['n']})" for k, v in pre.items()) + ".")
    e = dict(id="r26-describe", title="OI wall blast: descriptive check of the wall / OI-unwinding claim", family="outside-strategies",
             date="2026-10-14", doc=DOC, hypothesis="Descriptive: how often walls break in the window and how wall OI moves around 4x option moves.",
             data_version=DATA, period=PERIOD, instruments=list(K26.UNDS),
             features=["option-chain OI per strike (walls)", "index minutes", "ATM+-1 option prices"],
             model_or_rule="descriptive (research/hunt/r26/describe.py)", settings="research/hunt/r26/describe.py", prereg=PREREG,
             variants_tried=None, results=dict(summary=s, costs="none (descriptive)"), holdout_read=None, verdict="not-a-test",
             notes="Linked to R25 (jackpots come with strike OI rising): wall OI falling during a move is a consequence of the move, not a predictor.")
    (ENT / "r26-describe.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(L.RULES) + 1, "entries")


if __name__ == "__main__":
    main()
