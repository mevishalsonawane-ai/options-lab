"""Writes research/experiments/entries/r124-*.yaml (the census, the model tests, each option rule), failures included.

    python research/hunt/r124_big_moves/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r124cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R124_BIG_MOVES.md"


def r(x, d=0):
    return None if x is None else round(float(x), d)


def main():
    wf = json.loads((K.RESULTS / "wf.json").read_text())
    ho = json.loads((K.RESULTS / "holdout.json").read_text())
    cd = json.loads((K.RESULTS / "census_design.json").read_text())
    ch = json.loads((K.RESULTS / "census_holdout.json").read_text())
    common = dict(
        date="2026-10-28", doc=DOC,
        data_version="Drive lake copied 28 Oct 2026 (index_1m 5 indices + INDIAVIX, options_rolling_1m WEEK), "
                     "lakeclean steps 1-3, zero-volume option minutes never priced; every move on candle highs/lows",
        period=dict(design="2021-08-01..2026-03-15 (walk-forward folds 2023, 2024, 2025, 2026-01-01..03-15)",
                    holdout="2026-04-01..2026-10-19 read once (not virgin: R123 read it the same day, R106/R113/"
                            "R120-R122 earlier)"),
        prereg="research/hunt/r124_big_moves/PREREG.md", holdout_read=True)
    # census
    keep = [k for k, v in cd["factors"].items() if v.get("q", 1) <= 0.05]
    held = [k for k in keep if k in ch["factors"] and ch["factors"][k].get("q", 1) <= 0.05
            and (k == "F1" or (ch["factors"][k]["std_diff"] > 0) == (cd["factors"][k]["std_diff"] > 0))]
    e = dict(id="r124-census", title="R124 every NIFTY 30-point move (wicks) traced back: what came before it",
             family="market-facts", **common, instruments=["NIFTY"],
             features=["20 pre-registered factors at the minute before each move start (PREREG section 4)"],
             hypothesis="Boss 28 Oct: find what caused each 30-point move. Descriptive: which states come before "
                        "moves more often than at ordinary minutes of the same time of day (association, not cause).",
             model_or_rule="research/hunt/r124_big_moves/r124zig.py", settings="PREREG.md section 4, amendment 1",
             # a measurement, not a trading test: "not-a-test" (the validator wants no variant count for those)
             variants_tried=None, verdict="not-a-test",
             results=dict(summary=(f"Design: {cd['moves']['big']:,} big moves over {cd['moves']['sessions']} sessions "
                                   f"({cd['moves']['big_per_day']:.1f} a day, median {cd['moves']['size_median']:.1f} "
                                   f"points in {cd['moves']['minutes_median']:.0f} minutes). Factors with q <= 0.05 in "
                                   f"design: {', '.join(keep)}; held the same side on the holdout: {', '.join(held)}. "
                                   "The busy-market factors (recent range, VIX, straddle, distance from the open) "
                                   "dominate; F3/F4/F13/F20 are partly mechanical (a swing starts at a turning point)."),
                          costs="none (descriptive)"))
    (ENTRIES / "r124-census.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    # model tests
    hm = {m["cell"]: m for m in ho["models"]}
    e = dict(id="r124-model-tests", title="R124 multi-level network: is a 30-point move coming in 15/30 min, and "
             "which way", family="ml", **common, instruments=list(K.UNDS),
             features=["R123 inputs (index minutes, VIX, breadth, calendar, option chain) + level distances and the "
                       "last confirmed move; labels wick-to-wick"],
             hypothesis="The network forecasts a 30-point move and its direction better than the base rate and trees",
             model_or_rule="research/hunt/r124_big_moves/r124run.py (R123's N2 + a levels specialist)",
             settings="PREREG.md section 5", variants_tried=4,
             verdict="rejected",
             results=dict(summary="; ".join(
                 f"{c}: holdout NN {m['nn']:.4f} vs {m['best']} {m['best_score']:.4f} (AUC {m['auc_nn']:.3f} vs "
                 f"{m['auc_best']:.3f}, n {m['n']:,}, q {m['q']:.3f})" for c, m in hm.items()),
                 costs="none (forecast scores)"))
    (ENTRIES / "r124-model-tests.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    for rule, h in ho["rules"].items():
        w = wf["rules"][rule]
        fails = [k for k, v in h["gates"].items() if not v]
        e = dict(id=f"r124-{rule.lower()}", title=f"R124 {rule}: {K.RULES[rule]['label']}", family="ml", **common,
                 instruments=list(K.UNDS),
                 features=["signal source: the network's reading of the underlying (index minutes, VIX, breadth, "
                           "calendar, levels) with the option chain as context; exits on the index's wicks; buys the "
                           "ATM option (M4: the ATM straddle); no rule reads an option's own chart"],
                 hypothesis="Buying the ATM option (or straddle) when the network says a 30-point move is coming "
                            "pays after costs and beats random entries with the same exits",
                 model_or_rule=f"research/hunt/r124_big_moves/ ({rule})",
                 settings="PREREG.md section 6; 1 lot at today's lots; max 5 trades a day, one open per index",
                 variants_tried=4, verdict="inconclusive" if h["passes"] else "rejected",
                 results=dict(summary=(f"Walk-forward {w.get('n', 0)} trades, avg net Rs {r(w.get('avg_net')) or 0:,.0f}, "
                                       f"q {w['q']:.3f}; holdout {h.get('n', 0)} trades, avg net Rs "
                                       f"{r(h.get('avg_net')) or 0:,.0f}, win {(h.get('win_rate') or 0):.0%}, q "
                                       f"{h['q']:.3f}. " + ("Passes every gate." if h["passes"] else
                                                            "Fails: " + ", ".join(fails) + ".")),
                              costs="Costs('dated') + Fills('liq'), 1 lot, today's lots"))
        (ENTRIES / f"r124-{rule.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    print("entries written")


if __name__ == "__main__":
    main()
