"""Writes research/experiments/entries/r147-*.yaml from results/design.json and holdout.json (numbers copied).

    python -m research.hunt.r147_night_plus.make_entries
"""
from __future__ import annotations

import json

import yaml

from . import cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"
DOC, PRE = "research/HUNT_R147_NIGHT_PLUS.md", "research/hunt/r147_night_plus/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake copied 5 Nov 2026: index_1m (5 indices + INDIAVIX), options_rolling_1m "
        "WEEK/MONTH, equity_1m (59 stocks); to 16 Oct 2026")
COSTS = "app fills (+-5 bps), app charges (BSE for SENSEX), h28 half-spreads on both legs; stress 1.5x spread"
PERIOD = {"design": "2022, 2023, 2024 (yearly folds)", "holdout": "1 Jan 2025 - 16 Oct 2026, read once 5 Nov 2026"}


def f0(x):
    return "n/a" if x is None else f"{x:,.0f}"


def main() -> None:
    d = json.loads((K.RESULTS / "design.json").read_text())
    h = json.loads((K.RESULTS / "holdout.json").read_text())
    out = []
    hn = {(r["und"], r["rule"]): r for r in h["night"]}
    for r in d["night"]:
        if r["und"] not in K.NEW_UNDS:
            continue
        x = hn[(r["und"], r["rule"])]
        both = bool(r.get("pass") and x.get("pass"))
        name = "Night (R3) exact rule" if r["rule"] == "r3" else "Night's strong close without the OI check"
        out.append({
            "id": f"r147-x-{r['und'].lower()}-{r['rule']}", "title": f"R147 {name} on {r['und']} (1-ITM, 15:20 -> 09:16)",
            "family": "overnight", "date": "2026-11-05", "doc": DOC,
            "hypothesis": f"Night's overnight rule makes money on {r['und']} after real costs, beating random sides.",
            "data_version": DATA, "period": PERIOD, "instruments": [r["und"]],
            "features": ["signal source: the index (close location, day move, 59-stock breadth)" +
                         (" + the index's own day-end option OI build-up z" if r["rule"] == "r3" else "")],
            "model_or_rule": "research/hunt/r147_night_plus/data.py (NightRules on this index)",
            "settings": "research/hunt/r147_night_plus/cfg.py", "prereg": PRE, "variants_tried": 1,
            "results": {"summary": (f"design {r.get('trades', 0)} trades, Rs {f0(r.get('net_per_trade'))} a trade (stress "
                                    f"{f0(r.get('stress_net_per_trade'))}), by year {r.get('by_year')}, worst month "
                                    f"{f0(r.get('worst_month'))}, max DD {f0(r.get('max_dd'))}, twin p {r.get('twin_p')}, "
                                    f"BH q {r.get('bh_q')}, design pass {r.get('pass')}; holdout {x.get('trades', 0)} "
                                    f"trades, Rs {f0(x.get('net_per_trade'))} a trade (stress {f0(x.get('stress_net_per_trade'))}), "
                                    f"by year {x.get('by_year')}, worst month {f0(x.get('worst_month'))}, max DD "
                                    f"{f0(x.get('max_dd'))}, twin p {x.get('twin_p')}, holdout pass {x.get('pass')}"),
                        "costs": COSTS},
            "holdout_read": True, "verdict": "paper-only" if both else "rejected",
            "notes": "Passed design and holdout: a PAPER candidate for the Night arm; live in shadow first." if both else
                     "Failed the pre-registered gates."})
    hf = {r["id"]: r for r in h["filters"] + h["contracts"]}
    for r in d["filters"] + d["contracts"]:
        x = hf[r["id"]]
        out.append({
            "id": f"r147-{r['id'].lower()}", "title": f"R147 Night (R3) NIFTY + BANKNIFTY: {r['rule']}",
            "family": "overnight", "date": "2026-11-05", "doc": DOC,
            "hypothesis": f"{r['rule']} raises Night's net on the same nights.",
            "data_version": DATA, "period": PERIOD, "instruments": ["NIFTY", "BANKNIFTY"],
            "features": ["signal source: the index (Night's rule) + the filter's input (calendar, VIX, Hang Seng, "
                         "last hour, option straddle as context)"],
            "model_or_rule": r["rule"], "settings": "research/hunt/r147_night_plus/cfg.py", "prereg": PRE,
            "variants_tried": 1,
            "results": {"summary": (f"design: {r['trades']} Night trades, " +
                                    (f"{r.get('skipped')} skipped, " if "skipped" in r else "") +
                                    f"difference Rs {f0(r['diff_total'])} (by year {r['diff_by_year']}), p {r['p']:.3f}, "
                                    f"BH q {r['bh_q']:.3f}, design pass {r['pass']}; holdout: {x['trades']} trades, " +
                                    (f"{x.get('skipped')} skipped, " if "skipped" in x else "") +
                                    f"difference Rs {f0(x['diff_total'])} (by year {x['diff_by_year']}), p {x['p']:.3f}"),
                        "costs": COSTS},
            "holdout_read": True, "verdict": "rejected", "notes": "Failed the pre-registered gates."})
    for e in out:
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
        print(e["id"], e["verdict"])


if __name__ == "__main__":
    main()
