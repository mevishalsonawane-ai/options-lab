"""Write R164's experiment-log entries (research/experiments/entries/r164-*.yaml). 12 Nov 2026.

Every rule is "proposed": pre-registered and coded, never run, because the recordings could not be read in this session
(sealed .irlive; the private data repository was not attachable). Not counted as tests; the BH count stays 131 in flow-oi
until they run.

    python -m research.hunt.r164_donchian_orderflow.make_entries
"""
from __future__ import annotations

from pathlib import Path

import yaml

from research.hunt.r164_donchian_orderflow import flow as F

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r164_donchian_orderflow/results/report.md"
PREREG = "research/hunt/r164_donchian_orderflow/PREREG.md"

RULES = {
    "E1": ("skip when the bought option's top-5 depth imbalance (mean, last 60 s) <= -0.20",
           "the bought option's own 5-level book (OPTION-CHART rule: labelled, reported apart, Boss-only switch)"),
    "E2": ("skip when the index future's top-5 depth imbalance x direction (mean, last 60 s) <= -0.20",
           "the index future's 5-level book (underlying)"),
    "E3": ("skip when the index future's tick-rule delta / volume x direction over the last 60 s <= -0.20",
           "the index future's trades signed by the tick rule (underlying; no aggressor flag in Kite ticks)"),
    "E4": ("skip when the index future's tick-rule delta / volume x direction over the last 5 min <= -0.10",
           "the index future's trades signed by the tick rule (underlying)"),
    "E5": ("skip when the index future's net large prints (> day's p99 of earlier minutes) x direction over 5 min <= -2",
           "the index future's per-second traded size (underlying)"),
    "E6": ("skip when the index future's OI fell >= 0.10% over the last 15 min",
           "the index future's open interest (underlying context)"),
    "X1": ("exit at the next best bid when the future's 60-s tick-rule delta / volume x direction <= -0.30 for 30 s",
           "the index future's trades (underlying)"),
    "X2": ("exit at the next best bid when the bought option's top-5 depth imbalance <= -0.30 for 30 s",
           "the bought option's own book (OPTION-CHART rule)"),
    "X3": ("exit at the next best bid when X1's and X2's conditions both hold for 15 s",
           "future trades + the bought option's own book (OPTION-CHART rule)"),
}


def entry(rule: str) -> dict:
    text, src = RULES[rule]
    kind = "entry filter (skip only)" if rule.startswith("E") else "in-trade early exit (exit earlier only)"
    return {
        "id": f"r164-{rule.lower()}",
        "title": f"R164 {rule}: order flow on R72 / R51 Donchian trades - {kind}",
        "family": "flow-oi",
        "date": "2026-11-12",
        "doc": DOC,
        "hypothesis": "On R72 Breadth Donchian and R51 Donchian 20 (+Supertrend) trades on NIFTY / BANKNIFTY, the rule lifts "
                      "the win rate by >= 5 points without lowering net, beats random skips/exits of the same size, BH q <= 0.10.",
        "data_version": "none read: the phone's tick zone (ticks.jsonl.gz inside live-YYYY-MM-DD.irlive, IraAlgo releases "
                        "live-2026-10/11) is sealed with Boss's passphrase; the IraAlgo repository was not attachable in this session",
        "period": {"design": "none (no threshold fitted); planned sample: recorded sessions 12 Oct - 11 Nov 2026 (21 at most), "
                             "then the forward shadow log (report section 5)"},
        "instruments": ["NIFTY", "BANKNIFTY"],
        "features": [f"signal source: the index's 5-min Donchian break (the arm's own); the rule reads {src}"],
        "model_or_rule": text,
        "settings": "research/hunt/r164_donchian_orderflow/flow.py (constants frozen by PREREG sections 3-4)",
        "prereg": PREREG,
        "variants_tried": None,
        "results": {
            "summary": "Not run. The recordings are sealed (.irlive, AES-256-GCM with Boss's backup passphrase) and the private "
                       "data repository could not be attached, so no trade, depth or flow row was read. Even opened, about 76 "
                       "(R72) and 106 (R51) trades would fall inside the tick zone in 21 sessions (assumed 2 of 5 indices). "
                       "A 5-point win-rate lift needs about 245 trades per arm at alpha 0.05 (30% skipped), and about 650 at the "
                       "family's first BH rank. Too small: inconclusive by PREREG 6.5.",
            "costs": "planned: Costs('dated') charges, best-bid early exits, 1.5x spread stress",
        },
        "holdout_read": None,
        "verdict": "proposed",
        "notes": "Option-chart rule (signal read from the option's own book): labelled and reported apart."
                 if rule in F.OPTION_CHART_RULES else "Underlying rule.",
    }


def main() -> int:
    for r in RULES:
        (OUT / f"r164-{r.lower()}.yaml").write_text(yaml.safe_dump(entry(r), sort_keys=False, width=120, allow_unicode=True))
    print(f"wrote {len(RULES)} entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
