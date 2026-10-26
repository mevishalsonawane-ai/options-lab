"""Write R113's experiment-log entries (research/experiments/entries/r113-*.yaml) from results/*.json.

    python research/hunt/r113_time_of_day/make_entries.py

One descriptive entry (not a test) and one entry per pre-registered Part D test: the 7 that qualified and were run, and
the 4 that did not qualify (proposed, never run). 26 Oct 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r113cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R113_TIME_OF_DAY.md"
DATA = "Drive lake gdrive:IraAlgo-data-lake copied 26 Oct 2026; R51 Amendment A clean stores; each arm's own study data"
COSTS = "dated Zerodha charges, R17 fills, next-minute fills (R101 / R106 engine), 1 lot"


def rs(x):
    return ("-" if x < 0 else "") + f"Rs {abs(round(x)):,}"


def base(**kw):
    e = dict(family="daily-filters", date="2026-10-26", doc=DOC, data_version=DATA,
             period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                         holdout="2026-04-01..2026-10-13 read once (already read by R51-R112)"),
             instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
             prereg="research/hunt/r113_time_of_day/PREREG.md")
    e.update(kw)
    return e


def main():
    sel = json.loads((K.RESULTS / "partD_select.json").read_text())["tests"]
    fin = json.loads((K.RESULTS / "partD_final.json").read_text())
    rows, m = fin["rows"], fin["m_total"]
    out = []
    ov = json.loads((K.RESULTS / "overview.json").read_text())
    out.append(base(
        id="r113-descriptive", title="R113 where each arm makes and loses its money (time of day, holding, weekday, "
        "expiry, gap, VIX, day shape)", family="market-facts",
        hypothesis="Descriptive only (Boss, 26 Oct: at what time each strategy made most profit, and a pattern between "
                   "them). No rule adopted.",
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "NATURALGAS", "SILVERM", "MCX"],
        features=["signal source: each arm's own (the underlying for all but FT3 / Hero, whose strike is picked by "
                  "the option's price); R113 only re-reads the trade lists"],
        model_or_rule=f"{len(ov)} arm trade lists from their own research code, bucketed (PREREG section 2)",
        settings="research/hunt/r113_time_of_day/r113cfg.py; r113study.py describe",
        variants_tried=None, holdout_read=True, verdict="not-a-test",
        results=dict(summary="; ".join(f"{a} {v['trades']} trades {rs(v['net'])}" for a, v in ov.items()),
                     costs="each arm's own study costs (see the write-up)")))
    for tid, s in sel.items():
        rule = K.RULES[s["rule"]]
        e = base(id=f"r113-{tid.lower()}", title=f"R113 {s['rule']} on {K.ARM_NAME[s['arm']]}: {rule['label']}",
                 hypothesis=f"{rule['label']} lifts {K.ARM_NAME[s['arm']]} (its design entries there: "
                            f"{s['removed_trades']} trades, {rs(s['removed_net'])}). Gates: net > 0 in design, walk-forward "
                            f"and final; above today's rules in walk-forward and final; random-drop twin BH q <= 0.10 "
                            f"over {m:,} tests; >= 40 final trades.",
                 features=["signal source: the index (the arm's own signals unchanged); only an entry-time filter"],
                 model_or_rule=f"{K.ARM_NAME[s['arm']]} with {rule['label']} (candidates removed before the arm's walk)",
                 settings="research/hunt/r113_time_of_day/r113cfg.py RULES; r113study.py select / design / final")
        if not s["qualifies"]:
            e.update(variants_tried=None, holdout_read=False, verdict="proposed",
                     results=dict(summary=f"Not run: did not qualify on design (the entries it removes: "
                                          f"{s['removed_trades']} trades, {rs(s['removed_net'])}, not a loss).",
                                  costs=COSTS))
        else:
            r = rows[tid]
            fl = "; ".join(r["gates"]["fails"]) or "none"
            e.update(variants_tried=1, holdout_read=True, verdict="passed" if r["gates"]["passed"] else "rejected",
                     results=dict(summary=(
                         f"Design {r['design']['trades']} trades {rs(r['design']['net'])} (today's rules "
                         f"{rs(r['b0_design']['net'])}); walk-forward {rs(r['wf']['net'])} (today's "
                         f"{rs(r['b0_wf']['net'])}); final {r['final']['trades']} trades {rs(r['final']['net'])} (today's "
                         f"{rs(r['b0_final']['net'])}, {r['b0_final']['trades']} trades); random-drop twin p "
                         f"{r['twin_p']} (twin median {rs(r['twin_median'])}); BH q {r['q_all']} over {m:,} tests "
                         f"(R113 alone {r['q_r113']}). Fails: {fl}."), costs=COSTS))
            if e["verdict"] == "passed":
                e["verdict"] = "paper-only"
        out.append(e)
    for e in out:
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(out), "entries")


if __name__ == "__main__":
    main()
