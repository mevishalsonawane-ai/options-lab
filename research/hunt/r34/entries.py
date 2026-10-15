"""Writes research/experiments/entries/r34-*.yaml: one per rule (failures included) plus the iv_rv descriptive table.

    python3 research/hunt/r34/entries.py      # from results/final.json
"""
from __future__ import annotations

import json

import yaml

import r34cfg as K27

ENT = K27.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R34_NIFTY_ZERO_TO_HERO.md"
PREREG = "research/hunt/r34/PREREG.md"
DATA = ("Dhan lake on Drive (IraAlgo-data-lake): options_rolling_1m opt_NIFTY_WEEK (ATM+-10, nearest expiry, OHLC, volume, "
        "OI, IV), index_1m NIFTY/INDIAVIX; 271 NIFTY expiries 2021-08-05..2026-10-06 found from the data (R25 loader, R26 "
        "day files); 13 Oct 2026 not in the lake")
COSTS = ("app charges at today's rates (Rs 20/order, STT 0.15% sell, exchange, SEBI, stamp, GST); fills at the next open + "
         "5 bps + a 1/2/4-tick half-spread by 5-min volume; entry limit 1.2x signal + 0.10 for 3 minutes; stress x2 spread")
TITLES = {
    "M1": "NIFTY hero ML ranker (gradient boosting on 45 point-in-time features, Rs 1-30 OTM, hold to 15:05)",
    "M2": "NIFTY hero ML ranker, exit half at 2x + step lock",
    "F1": "NIFTY hero fair value: buy when implied vol <= theta x forecast realized vol (Rs 1-30 OTM)",
    "G1": "NIFTY hero gamma + momentum: Rs 15-30 option, spot moving away from the OI-gamma pin with 15-min momentum",
    "Q1": "NIFTY hero rest-of-day momentum at 14:30 (Rs 15-30 option with the move since the previous close)",
    "Q2": "NIFTY hero rest-of-day reversal at 14:30 (Rs 15-30 option against the move since the previous close)",
    "K1": "Boss's late range breakout: 14:30-14:45 range, break by 15:00, ATM+1, hold to 15:25",
    "K2": "Boss's late range breakout: 14:45-15:00 range, break by 15:15, ATM+1, hold to 15:25",
    "K3": "Boss's late range breakout: 15:00-15:15 range, break by 15:20, ATM+1, hold to 15:25",
    "K1e": "Boss's late range breakout window 1 with the app's 15:05 square-off",
    "KS": "Boss's late range breakout, all 3 windows, stop at the other side of the range",
}


def main():
    r = json.loads((K27.RESULTS / "final.json").read_text())
    wf = "walk-forward test years 2023-2026 (anchored; every choice on NIFTY expiries before the test year; training from Aug 2021): 197 expiries"
    allp = "all 271 NIFTY expiries (nothing fitted); R25 blocks 2021-22 / 2023-26 reported"
    for rid, v in r["rules"].items():
        m = v["m"]
        s = (f"{m['trades']} trades, hit {m['hit_pct']}%, >= 10x {m.get('x10_pct', 0)}%, net Rs {m['net']:+,.0f} "
             f"({m['per_1000_risked']:+.0f} per Rs 1,000); worst day Rs {m['worst_day']:+,.0f}, max DD Rs {m['max_dd']:+,.0f}, "
             f"net without the best day Rs {m['net_without_best_day']:+,.0f}; 2x spread Rs {v['x2_spread']:+,.0f}; "
             f"random-twin p {v['twins']['p']}, BH q {v['q']} (family {r['bh_size']}). "
             + ("PASSED the gates." if v["passes"] else "FAILED the gates."))
        e = dict(id=f"r34-{rid.lower()}", title=TITLES[rid], family="calendar-events" if rid[0] != "K" else "outside-strategies",
                 date="2026-10-14", doc=DOC,
                 hypothesis="A NIFTY expiry-day cheap-option rule catches zero-to-hero moves often enough to pay after costs.",
                 data_version=DATA, period={"design": wf if rid[0] != "K" else allp}, instruments=["NIFTY"],
                 features=["NIFTY expiry-day OTM options Rs 1-30", "index minutes", "India VIX", "option OI (gamma)", "realized vol"],
                 model_or_rule=TITLES[rid] + (f"; walk-forward choices {v.get('choices')}" if v.get("choices") else ""),
                 settings="research/hunt/r34/" + ("r34late.py" if rid[0] == "K" else "r34rules.py"), prereg=PREREG,
                 variants_tried=11, results=dict(summary=s, costs=COSTS), holdout_read=None,
                 verdict="paper-only" if v["passes"] else "rejected",
                 notes="One of 11 R34 rules; BH across R21 9 + R25 9 + R26 10 + R34 11 = 39. No virgin holdout exists for NIFTY "
                       "expiries; the forward shadow log is the clean test.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    d = r["desc_ivrv"]["E1"]
    s = ("Mean net per Rs 1,000 held to 15:05 by price band x iv_rv (our implied vol / forecast realized vol), "
         f"{r['rows']:,} decision rows on 271 expiries (all rows {r['base_per1000']['E1']}): "
         + "; ".join(f"{k} {x['per1000']:+.0f} (n {x['n']})" for k, x in d.items()) + ".")
    e = dict(id="r34-ivrv", title="NIFTY hero: how option richness (implied vs realized vol) relates to the loss", family="calendar-events",
             date="2026-10-14", doc=DOC, hypothesis="Descriptive: options priced rich relative to the realized-vol forecast lose more.",
             data_version=DATA, period={"design": "all 271 NIFTY expiries (descriptive)"}, instruments=["NIFTY"],
             features=["iv_rv", "price band"], model_or_rule="descriptive (research/hunt/r34/r34rules.py desc_ivrv)",
             settings="research/hunt/r34/r34feat.py", prereg=PREREG, variants_tried=None, results=dict(summary=s, costs=COSTS),
             holdout_read=None, verdict="not-a-test", notes="Every bucket still loses on average; the cheapest-vs-realized buckets lose least.")
    (ENT / "r34-ivrv.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(r["rules"]) + 1)


if __name__ == "__main__":
    main()
