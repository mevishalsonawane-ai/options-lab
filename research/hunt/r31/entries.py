"""Writes research/experiments/entries/r31-*.yaml (one per entry set, failures included).

    python3 research/hunt/r31/entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"


def main():
    r = json.loads((HERE / "results" / "results.json").read_text())
    for rid, m in r["rules"].items():
        s = (f"{rid}'s own entries with Boss's 25% trail (stop = 75% of the confirmed peak, from entry): {m['days']} expiry days "
             f"({m['positions']} positions), hit {m['hit_pct']}%, net Rs {m['net']:+,.0f} vs Rs {m['published_exit_net']:+,.0f} with the "
             f"study's own exit; per Rs 1,000 {m['per_1000']:+.0f}; average give-back from the peak at the exit {m['avg_giveback_pct']}%; "
             f"max DD Rs {m['max_dd']:+,.0f}; without the best day Rs {m['wo_best']:+,.0f}; 2x spread Rs {m['x2_spread']:+,.0f}; "
             f"raw-high peak Rs {m['raw_high_net']:+,.0f}; bootstrap p {round(m['p'], 3)}, BH q {m['q']} (family {r['bh_size']}). "
             + ("PASSED the gates." if m["passes"] else "FAILED the gates."))
        e = dict(id=f"r31-{rid.lower()}", title=f"25% trailing exit on {rid}'s entries", family="trade-management", date="2026-10-14",
                 doc="research/HUNT_R31_TRAIL25.md",
                 hypothesis="Selling when the premium falls 25% below its peak (from entry) turns the hero entries profitable.",
                 data_version="Dhan lake on Drive (R26 day files: NIFTY, SENSEX, BANKNIFTY, FINNIFTY weeklies); the entries are the "
                              "published trades of R26-R30",
                 period={"design": "the source study's expiries and entries (R26/R28/R30 all expiries; R27 walk-forward test years "
                                   "2023-26 and all expiries for K rules; R29 decision days)"},
                 instruments=sorted({"NIFTY"} | ({"SENSEX"} if rid[:3] in ("R28", "R29", "R30") or rid.startswith("R26") else set())
                                    | ({"BANKNIFTY", "FINNIFTY"} if rid.startswith("R26") else set())),
                 features=["the source study's entry"], model_or_rule="exit TR25 (research/hunt/r31/r31trail.py simulate)",
                 settings="research/hunt/r31/r31trail.py", prereg="research/hunt/r31/PREREG.md", variants_tried=26,
                 results=dict(summary=s, costs="app charges at today's rates; next-open fills + 5 bps + 1/2/4-tick half-spread; x2 spread"),
                 holdout_read=None, verdict="paper-only" if m["passes"] else "rejected",
                 notes="One of 26 R31 entry sets; BH across R21-R31 (78). Sells at the next open after a close below the stop.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(r["rules"]))


if __name__ == "__main__":
    main()
