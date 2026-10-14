"""Writes research/experiments/entries/r32-*.yaml (4 rules + the event study + the model report).

    python3 research/hunt/r32/entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"
NOTE = {"P1": "walk-forward gradient-boosting model on every signal family (1-minute rows); first minute whose top score clears tau; Boss's 25% trail",
        "P2": "P1's entries held to 15:05",
        "P3": "the best 2-3 signal tercile pattern mined on earlier years; first matching row; 25% trail",
        "P4": "P3's entries held to 15:05"}
BASE = dict(date="2026-10-14", doc="research/HUNT_R32_ZERO_TO_HERO_ANATOMY.md",
            data_version="Dhan lake on Drive: opt_{NIFTY,SENSEX}_WEEK (ATM+-10 with OI, IV, volume), index_1m, INDIAVIX, equity_1m "
                         "HDFCBANK/RELIANCE/ICICIBANK; R26 day files; 936,286 one-minute rows",
            period={"design": "anchored walk-forward: test years 2023-2026, each fitted on earlier NIFTY / SENSEX expiries only"},
            instruments=["NIFTY", "SENSEX"], prereg="research/hunt/r32/PREREG.md", settings="research/hunt/r32/r32study.py",
            holdout_read=None)


def main():
    r = json.loads((HERE / "results" / "results.json").read_text())
    for rid, v in r["rules"].items():
        m = v["m"]
        s = (f"{m['trades']} trades on {sum(r['days'].values())} expiries (test years 2023-26), hit {m['hit_pct']}%, >= 10x {m['x10_pct']}%, "
             f"net Rs {m['net']:+,.0f} ({m['per_1000_risked']:+.0f} per Rs 1,000); by year {m['years']}; max DD Rs {m['max_dd']:+,.0f}; "
             f"random-time twin mean Rs {v['twins']['mean']:+,.0f}, p {v['twins']['p']}, BH q {v['q']} (family {r['bh_size']}). "
             + ("PASSED the gates." if v["passes"] else "FAILED the gates."))
        e = dict(id=f"r32-{rid.lower()}", title=f"Zero-to-hero anatomy {rid}: {NOTE[rid]}", family="ml",
                 hypothesis="Combining every signal family (OI, VWAP/TWAP, EMA, Supertrend, ADX, 2/3/4-candle patterns, volume, IV, "
                            "gamma exposure, iv_rv, heavyweights) at 1-minute level predicts zero-to-hero launches well enough to pay.",
                 features=[f for f in r["model"]["family_importance_auc_drop_2026"]], model_or_rule=NOTE[rid], variants_tried=4,
                 results=dict(summary=s, costs="app charges at today's rates; next-open fills + 5 bps + 1/2/4-tick half-spread; x2 spread"),
                 verdict="paper-only" if v["passes"] else "rejected",
                 notes="BH across R21-R32 (82). Amendment 1 (tau from out-of-sample scores) made before any P1/P2 outcome.", **BASE)
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    ev, md = r["event_study"], r["model"]
    s = (f"{ev['launches']} launches (an option at Rs 1-30 that later reached a confirmed 10x; the run's lowest row) on {ev['launch_days']} "
         f"expiries vs {ev['controls']} same-minute, same-band controls that never doubled. 30 bars before launch the option was "
         f"{ev['paths']['launch']['price_vs_now'][0]}x its launch price (controls {ev['paths']['control']['price_vs_now'][0]}x). "
         "Pattern rates launch vs control: " + "; ".join(f"{k} {x['launch_pct']}% vs {x['control_pct']}%" for k, x in ev["patterns"].items()) + ".")
    e = dict(id="r32-event-study", title="Zero-to-hero anatomy: the 30 one-minute candles before a launch (descriptive)", family="ml",
             hypothesis="Descriptive: what the 30 minutes before a 10x launch look like versus ordinary options.",
             features=["every R32 feature"], model_or_rule="event study (r32study.event_study)", variants_tried=None,
             results=dict(summary=s, costs="none (descriptive)"), verdict="not-a-test",
             notes="The launch is the run's lowest row (hindsight), which biases momentum features negative; descriptive only.", **BASE)
    (ENT / "r32-event-study.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    s = ("Walk-forward y10 classifier: " + "; ".join(f"{y}: AUC {x['auc']}, base {x['base_y10_pct']}%, top 1% {x['top1pct_y10_pct']}%"
                                                      for y, x in md.items() if y.isdigit()) +
         f". Family importance (AUC drop, 2026): {md['family_importance_auc_drop_2026']}. Row-level lift is clustered on a few days "
         "(2 Sep 2025 gave 195 of 2025's top-0.1% rows); the best row per expiry hit 10x on 3.9-7.5% of expiries vs 1.5-2.9% for an average option.")
    e = dict(id="r32-model", title="Zero-to-hero anatomy: walk-forward model of 10x launches (descriptive)", family="ml",
             hypothesis="Descriptive: how well every signal family together ranks 1-minute rows for a later 10x.",
             features=["every R32 feature"], model_or_rule="HistGradientBoostingClassifier (PREREG settings)", variants_tried=None,
             results=dict(summary=s, costs="none (descriptive)"), verdict="not-a-test", notes="See P1-P4 for the tradable versions.", **BASE)
    (ENT / "r32-model.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote 6")


if __name__ == "__main__":
    main()
