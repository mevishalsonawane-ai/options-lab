"""Writes research/experiments/entries/r33-*.yaml (4 rules + the frequency scan).

    python3 research/hunt/r33/entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[1] / "experiments" / "entries"
NOTE = {"B1": "the pattern most common before zero-to-hero on the training years (first match each expiry), 25% trail",
        "B2": "B1 held to 15:05",
        "B3": "the best 2-3 pattern combination mined on the training years (patterns in >= 15% of heroes), 25% trail",
        "B4": "B3 held to 15:05"}
BASE = dict(date="2026-10-14", doc="research/HUNT_R33_BACKWARDS.md", family="patterns-indicators",
            data_version="Dhan lake on Drive: opt_{NIFTY,SENSEX}_WEEK, index_1m, INDIAVIX, equity_1m (R32 rows: 936,286 one-minute "
                         "rows; 310 option + index pattern variants + 13 chain flags + 16 shape clusters)",
            period={"design": "anchored walk-forward: test years 2023-2026, each chosen on earlier NIFTY / SENSEX expiries only"},
            instruments=["NIFTY", "SENSEX"], prereg="research/hunt/r33/PREREG.md", settings="research/hunt/r33/r33study.py",
            holdout_read=None)


def main():
    r = json.loads((HERE / "results" / "results.json").read_text())
    for rid, v in r["rules"].items():
        m = v["m"]
        s = (f"{m['trades']} trades (test years 2023-26), hit {m['hit_pct']}%, >= 10x {m['x10_pct']}%, net Rs {m['net']:+,.0f} "
             f"({m['per_1000_risked']:+.0f} per Rs 1,000); by year {m['years']}; random-time twin mean Rs {v['twins']['mean']:+,.0f}, "
             f"p {v['twins']['p']}, BH q {v['q']} (family {r['bh_size']}). " + ("PASSED the gates." if v["passes"] else "FAILED the gates."))
        e = dict(id=f"r33-{rid.lower()}", title=f"Backwards study {rid}: {NOTE[rid]}",
                 hypothesis="Starting from every zero-to-hero, the most common indicator / candle / shape pattern (option and index) "
                            "marks tradable entries.",
                 features=["310 option + index indicator variants", "chain / pricing flags", "30-candle shape clusters"],
                 model_or_rule=NOTE[rid] + f"; choices {r['choices']}", variants_tried=4,
                 results=dict(summary=s, costs="app charges at today's rates; next-open fills + 5 bps + 1/2/4-tick half-spread; x2 spread"),
                 verdict="paper-only" if v["passes"] else "rejected", notes="BH across R21-R33 (86).", **BASE)
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    top = r["most_occurring_top30"][:8]
    lift = r["highest_lift_top30"][:8]
    s = (f"{r['heroes10']} 10x heroes ({r['heroes5']} at 5x) vs {r['controls']} matched controls and {r['rows']:,} rows, {r['patterns']} patterns. "
         "Most occurring before heroes: " + "; ".join(f"{x['pattern']} {x['hero10_pct']}% (all rows {x['all_rows_pct']}%, 10x rate when present "
                                                     f"{x['row_10x_rate_pct']}%)" for x in top) +
         ". Highest lift: " + "; ".join(f"{x['pattern']} {x['hero10_pct']}% vs {x['all_rows_pct']}% (10x rate {x['row_10x_rate_pct']}%)" for x in lift) +
         ". Base 10x rate 1.71%: no pattern lifts the forward 10x rate above 2.5%.")
    e = dict(id="r33-frequency", title="Backwards study: pattern frequency before zero-to-hero (descriptive)",
             hypothesis="Descriptive: which patterns precede zero-to-hero most often and how much they lift the forward 10x rate.",
             features=["every R33 pattern"], model_or_rule="frequency scan (r33study.freq_table)", variants_tried=None,
             results=dict(summary=s, costs="none (descriptive)"), verdict="not-a-test",
             notes="Heroes are taken at their run's lowest row (hindsight): 'crashed / oversold' patterns are inflated by that choice.", **BASE)
    (ENT / "r33-frequency.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("wrote 5")


if __name__ == "__main__":
    main()
