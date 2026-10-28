"""Writes research/experiments/entries/r126-*.yaml: one entry per indicator/data family (variants_tried = its real
variant x horizon count), the all-variants network N4, and the 54-setting rule sweep. Failures included.

    python research/hunt/r126_param_sweep/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r126feat as F  # noqa: E402

ROOT = HERE.parents[2]
ENTRIES = ROOT / "research" / "experiments" / "entries"
RESULTS = HERE / "results"
DOC = "research/HUNT_R126_PARAM_SWEEP.md"
NAMES = {"bb": "Bollinger Bands", "macd": "MACD", "rsi": "RSI", "st": "Supertrend", "ema": "EMA", "cross": "EMA crosses",
         "vwap": "VWAP", "vol": "option volume windows", "prof": "volume profile", "sr": "wick support/resistance",
         "sweep": "liquidity sweeps", "pool": "liquidity pools", "candle": "wick candle patterns",
         "flow": "option flow", "base125": "participant OI + highs-minus-lows"}


def main():
    wf = json.loads((RESULTS / "wf.json").read_text())
    ho = json.loads((RESULTS / "holdout.json").read_text())
    common = dict(
        date="2026-10-28", doc=DOC, family="ml",
        data_version="Drive lake copied 28 Oct 2026 (index_1m, options_rolling_1m WEEK, NSE participant OI); lakeclean "
                     "steps 1-3; every indicator on wick midpoints / highs-lows",
        period=dict(design="2024-10-20..2026-03-15 (quarterly folds Apr 2025 - Mar 2026)",
                    holdout="2026-04-01..2026-10-19 (fourth read today; only N4 was read, nothing else passed)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        prereg="research/hunt/r126_param_sweep/PREREG.md")
    for fam, vs in F.VARIANTS.items():
        rs = [r for r in wf["screen"] if r["family"] == fam]
        best = min(rs, key=lambda r: r["ll_change"])
        e = dict(id=f"r126-{fam}", title=f"R126 {NAMES[fam]} at {len(vs)} settings: does any add to the direction of "
                 "the next 30-point move?", **common, holdout_read=False,
                 features=[f"{NAMES[fam]} variants: {', '.join(vs)}"],
                 hypothesis="Some setting of this indicator/data adds to a price-only model's forecast of which way "
                            "the next 30-point (wick) move goes (log-loss gain >= 0.001, q <= 0.05, 3 of 4 folds)",
                 model_or_rule="research/hunt/r126_param_sweep/r126run.py (screen: logistic regression, price core "
                               "with vs without the variant)",
                 settings="PREREG.md section 2-3A", variants_tried=len(rs), verdict="rejected",
                 results=dict(summary=(f"{len(rs)} tests ({len(vs)} settings x 15/30 min), walk-forward: none adds; "
                                       f"{sum(r['ll_change'] < 0 for r in rs)} improve at all; best {best['variant']} "
                                       f"h{best['h']}: log-loss change {best['ll_change']:+.5f} (q {best['q']:.2f}, "
                                       f"{best['folds_better']} of 4 folds), below the 0.001 bar."),
                              costs="none (forecast scores)"))
        (ENTRIES / f"r126-{fam}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    hc = {c["cell"]: c for c in ho["compare"]}
    e = dict(id="r126-n4", title="R126 the network with all 118 variants (359 inputs) vs R124's network", **common,
             holdout_read=True, features=["R124 inputs + all 359 R126 inputs, one specialist per family"],
             hypothesis="Feeding every setting of every indicator to the network improves its direction forecast",
             model_or_rule="research/hunt/r126_param_sweep/r126run.py (n4)", settings="PREREG.md section 3B",
             variants_tried=4, verdict="rejected",
             results=dict(summary="; ".join(f"{c}: holdout AUC {m['auc_n3']:.3f} vs {m['auc_r124']:.3f} (n {m['n']:,}, "
                                            f"q {m['q']:.2f})" for c, m in hc.items()),
                          costs="none (forecast scores)"))
    (ENTRIES / "r126-n4.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    rules = wf["rules"]
    pos = [k for k, s in rules.items() if s["n"] and s["avg_net"] > 0]
    big = [k for k, s in rules.items() if s["n"] >= 300]
    best = max((k for k in rules if rules[k]["n"] >= 50), key=lambda k: rules[k]["avg_net"], default=None)
    e = dict(id="r126-rule-sweep", title="R126 54 rule settings (direction share, move threshold, horizon, exit size) "
             "on the all-variants network", **common, holdout_read=False,
             features=["signal on the underlying (the network's direction share and P(move)); ATM call/put; exits on "
                       "the index wicks at 20/30/45 NIFTY points or the time limit"],
             hypothesis="Some rule setting makes money after costs and beats random entries with the same exits",
             model_or_rule="research/hunt/r126_param_sweep/r126run.py (rule_sweep)", settings="PREREG.md section 3C",
             variants_tried=len(rules), verdict="rejected",
             results=dict(summary=(f"Walk-forward: {len(pos)} of {len(rules)} settings had a positive average net; "
                                   f"{len(big)} reached 300 trades; none passes. Best with >= 50 trades: {best} "
                                   f"({rules[best]['n']} trades, avg Rs {rules[best]['avg_net']:,.0f})."
                                   if best else f"{len(pos)} positive; none passes."),
                          costs="Costs('dated') + Fills('liq'), 1 lot, today's lots"))
    (ENTRIES / "r126-rule-sweep.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    print("entries written")


if __name__ == "__main__":
    main()
