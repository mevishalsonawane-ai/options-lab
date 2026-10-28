"""Writes research/experiments/entries/r125-*.yaml (the N3-vs-R124 comparison and the 4 rules), failures included.

    python research/hunt/r125_levels_flow/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r125run as RN  # noqa: E402

ROOT = HERE.parents[2]
ENTRIES = ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R125_LEVELS_FLOW.md"


def main():
    wf = json.loads((RN.RESULTS / "wf.json").read_text())
    ho = json.loads((RN.RESULTS / "holdout.json").read_text())
    common = dict(
        date="2026-10-28", doc=DOC, family="ml",
        data_version="Drive lake copied 28 Oct 2026 (index_1m, options_rolling_1m WEEK, NSE participant OI); "
                     "lakeclean steps 1-3; every level, move and indicator on candle highs/lows (wicks)",
        period=dict(design="2024-10-20..2026-03-15 (quarterly folds Apr 2025 - Mar 2026)",
                    holdout="2026-04-01..2026-10-19 read once (third read today: R123 and R124 read it first)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        prereg="research/hunt/r125_levels_flow/PREREG.md", holdout_read=True,
        features=["74 new inputs in 7 specialists: wick support/resistance, liquidity (pools, sweeps), order flow "
                  "(participant OI, option volume and OI build-up), volume and volume profile, wick candle patterns, "
                  "VWAP and EMAs, RSI and Supertrend; plus R124's inputs. Signal on the underlying; option chain as "
                  "context; exits on the index's wicks"])
    hc = {c["cell"]: c for c in ho["compare"]}
    wc = {c["cell"]: c for c in wf["compare"]}
    e = dict(id="r125-n3-vs-r124", title="R125 do support/resistance, liquidity, order flow, volume profile, candle "
             "patterns, VWAP/EMAs, RSI and Supertrend help the network see the next 30-point move's direction?",
             **common,
             hypothesis="Adding the 74 inputs to R124's network improves direction-given-a-move (and move-coming) "
                        "forecasts on the same rows",
             model_or_rule="research/hunt/r125_levels_flow/ (N3 vs R124's network retrained with the same seeds)",
             settings="PREREG.md sections 3-4, amendments 1-4", variants_tried=4, verdict="rejected",
             results=dict(summary="; ".join(
                 f"{c}: walk-forward AUC {wc[c]['auc_n3']:.3f} vs {wc[c]['auc_r124']:.3f} (q {wc[c]['q']:.2f}), "
                 f"holdout AUC {m['auc_n3']:.3f} vs {m['auc_r124']:.3f} (n {m['n']:,}, q {m['q']:.2f})"
                 for c, m in hc.items()), costs="none (forecast scores)"))
    (ENTRIES / "r125-n3-vs-r124.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    for rule, h in ho["rules"].items():
        w = wf["rules"][rule]
        fails = [k for k, v in h["gates"].items() if not v]
        e = dict(id=f"r125-{rule.lower()}", title=f"R125 {rule} with the 74-input network", **common,
                 hypothesis="Buying the ATM option on the network's direction (with the new inputs) pays after costs",
                 model_or_rule=f"research/hunt/r125_levels_flow/r125run.py ({rule})",
                 settings="PREREG.md section 4; 1 lot at today's lots; max 5 trades a day", variants_tried=4,
                 verdict="inconclusive" if h["passes"] else "rejected",
                 results=dict(summary=(f"Walk-forward {w.get('n', 0)} trades (avg net Rs {w.get('avg_net') or 0:,.0f}); "
                                       f"holdout {h.get('n', 0)} trades (avg net Rs {h.get('avg_net') or 0:,.0f}). "
                                       + ("Passes every gate." if h["passes"] else "Fails: " + ", ".join(fails) + ".")),
                              costs="Costs('dated') + Fills('liq'), 1 lot, today's lots"))
        (ENTRIES / f"r125-{rule.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    print("entries written")


if __name__ == "__main__":
    main()
