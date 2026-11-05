"""Writes research/experiments/entries/r148-*.yaml from results/results.json (numbers copied).

    python -m research.hunt.r148_night_enhance.make_entries
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENT = HERE.parents[2] / "research" / "experiments" / "entries"
DOC, PRE = "research/HUNT_R148_NIGHT_ENHANCE.md", "research/hunt/r148_night_enhance/PREREG.md"
DATA = "Drive lake copied 5 Nov 2026 (R147 stores + 09:15 bar and same-day low per leg); to 16 Oct 2026"
COSTS = "app fills (+-5 bps, stop -10 bps), app charges (BSE for SENSEX), h28 half-spreads on both legs"


def main() -> None:
    r = json.loads((HERE / "results" / "results.json").read_text())
    chk = {x["id"]: x for x in r["check"]["A"] + r["check"]["C"] + r["check"]["B"]}
    for x in r["design"]["A"] + r["design"]["C"] + r["design"]["B"]:
        c = chk[x["id"]]
        if x["id"].startswith("B"):
            s = (f"design: dropped {x['dropped']} of {x['trades']}, net {x['base']['total']:,.0f} -> {x['capped']['total']:,.0f}, "
                 f"max DD {x['base']['max_dd']:,.0f} -> {x['capped']['max_dd']:,.0f}; check: dropped {c['dropped']}, net "
                 f"{c['base']['total']:,.0f} -> {c['capped']['total']:,.0f}, max DD {c['base']['max_dd']:,.0f} -> "
                 f"{c['capped']['max_dd']:,.0f}, DD better than {c['dd_better_than_twins_share']:.0%} of random drops")
        else:
            m = x["metric"]
            s = (f"design: {x['trades']} trades, {m} Rs {x[m]:,.0f} (by year {x['by_year']}), p {x['p']:.3f}, BH q "
                 f"{x['bh_q']:.3f}; check: {c['trades']} trades, {m} Rs {c[m]:,.0f}, p {c['p']:.3f}")
        e = {"id": f"r148-{x['id'].lower()}", "title": f"R148 Night enhancement {x['id']}: {x['rule']}", "family": "overnight",
             "date": "2026-11-05", "doc": DOC, "hypothesis": f"{x['rule']} improves the Night book (NIFTY, BANKNIFTY, FINNIFTY r3; SENSEX s17).",
             "data_version": DATA, "period": {"design": "2022-2024", "holdout": "Jan 2025 - Oct 2026 (a second look; R147's holdout)"},
             "instruments": ["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"],
             "features": ["signal source: the index (Night's rule); India VIX and the calendar for likely-big nights; option premium for stops / targets"],
             "model_or_rule": x["rule"], "settings": "research/hunt/r148_night_enhance/study.py", "prereg": PRE, "variants_tried": 1,
             "results": {"summary": s + f". Pass {x.get('pass')}.", "costs": COSTS}, "holdout_read": True, "verdict": "rejected"}
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
        print(e["id"])


if __name__ == "__main__":
    main()
