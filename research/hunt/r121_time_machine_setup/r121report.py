"""R121 report and log entry: results/final.json -> results/report.md and
research/experiments/entries/r121-time-machine-setup.yaml. 28 Oct 2026.

    python research/hunt/r121_time_machine_setup/r121report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r121study as S  # noqa: E402

PERS = (("design", "design"), ("wf", "walk-forward"), ("final", "final"))


def rs(x):
    return ("-" if x < 0 else "") + f"Rs {abs(round(x)):,}"


def report(r: dict) -> str:
    L = ["# R121 results: the time machine replaying the arm's own setup with its real exits", "",
         f"BH m = {r['m_all']:,} ({r['m_prior']:,} logged to {S.ENTRIES_LAST_DAY} + R121's {r['m_r121']}). Rule: skip "
         "'against'. Skipped trades earn Rs 0; 2,000 random-skip twins. Signal source: each arm's own (the underlying; "
         "Hero's strike by the option's price).", "", f"**Passed every gate: {', '.join(r['passed']) or 'none'}.**", "",
         "| arm | variant | final trades / skipped | net before | net after | p | q (all) | q (R121) | fails |",
         "|---|---|---:|---:|---:|---:|---:|---:|---|"]
    for t in r["tests"].values():
        c = r["arms"][t["arm"]]["cells"][t["variant"]]["final"]
        L.append(f"| {r['arms'][t['arm']]['name']} | {t['variant']} | {c['base']['trades']} / {c['rule']['skipped']} | "
                 f"{rs(c['base']['net'])} | {rs(c['rule']['net'])} | {t['p']:.3f} | {t['q_all']:.2f} | {t['q_r121']:.2f} | "
                 f"{'; '.join(t['gates']['fails'])} |")
    L += ["", "## Every period", ""]
    for a in r["arms"].values():
        L += [f"### {a['name']}", "", "| variant | period | trades | skipped | net before | net after | max DD before | "
              "max DD after | p |", "|---|---|---:|---:|---:|---:|---:|---:|---:|"]
        for v, cc in a["cells"].items():
            for per, nm in PERS:
                c = cc[per]
                L.append(f"| {v} | {nm} | {c['base']['trades']} | {c['rule']['skipped']} | {rs(c['base']['net'])} | "
                         f"{rs(c['rule']['net'])} | {rs(c['base']['max_dd'])} | {rs(c['rule']['max_dd'])} | {c['p']} |")
        L.append("")
        for v in a["verdicts"]:
            for per, nm in PERS:
                vv = a["verdicts"][v][per]
                L.append(f"- {v} {nm}: " + "; ".join(f"{k} {x['n']} (win {x['win']:.0%}, mean {rs(x['mean'])})"
                                                     for k, x in vv.items()))
            cal = a["calibration"].get(v, {})
            L.append(f"- {v} calibration (the sample's mean R vs the real win): design n {cal.get('design', {}).get('n')} "
                     f"corr {cal.get('design', {}).get('corr')}; final n {cal.get('final', {}).get('n')} corr "
                     f"{cal.get('final', {}).get('corr')}")
        L.append("")
    L += ["## Pooled calibration (design + final)", ""]
    for v, c in r["pooled_calibration"].items():
        L += [f"**{v}**: n {c['n']}, correlation {c['corr']}.", "", "| mean R of the sample | trades | real win rate |",
              "|---|---:|---:|"] + [f"| {b['bin']} | {b['n']} | {b['actual']:.0%} |" for b in c["bins"]] + [""]
    return "\n".join(L) + "\n"


def entry(r: dict) -> dict:
    parts = []
    for t in r["tests"].values():
        c = r["arms"][t["arm"]]["cells"][t["variant"]]["final"]
        parts.append(f"{t['arm']} {t['variant']}: final {c['base']['trades']} trades, {c['rule']['skipped']} skipped, "
                     f"{rs(c['base']['net'])} -> {rs(c['rule']['net'])}, p {t['p']:.3f}")
    pc = r["pooled_calibration"]
    return dict(
        id="r121-time-machine-setup",
        title="R121 an enhanced time machine (the arm's own past entries, replayed with its real exits) as a pre-entry gate",
        family="daily-filters", date="2026-10-28", doc="research/HUNT_R121_TIME_MACHINE_SETUP.md",
        data_version="Drive lake copied 28 Oct 2026 (index_1m); R120's trade lists (R113 adapters)",
        period=dict(design="2024-11-20..2026-03-31 (walk-forward Apr 2025 - Mar 2026)",
                    holdout="2026-04-01..2026-10-13 read once (not fresh: read by R51-R120)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["signal source: each arm's own (the underlying; Hero's strike by the option's price); the filter reads "
                  "the arm's own entries and the underlying's 1-minute bars of the 60 sessions before the entry day"],
        hypothesis="After R120: replaying the arm's OWN past entries (not random clock slots) and (V_EXITS) its real paper "
                   "exits (time stops, locks, trail) on the underlying, scored in R, gives a verdict whose 'against' is "
                   "worth skipping. Gates as R120.",
        model_or_rule="research/hunt/r121_time_machine_setup/r121lib.py; r121study.py",
        settings="PREREG.md: 60 sessions, >= 10 replays, margin max(0.05 R, 2 SE), delta 0.5",
        prereg="research/hunt/r121_time_machine_setup/PREREG.md", variants_tried=S.M_R121, holdout_read=True,
        verdict="paper-only" if r["passed"] else "rejected",
        results=dict(summary=(f"Passed: {', '.join(r['passed']) or 'none'} of {len(r['tests'])}. " + "; ".join(parts) +
                              f". The replays track each trade's own result (V_EXITS: same sign 90% for R51/R72/Solo, "
                              f"design) but the sample's mean R does not predict the next trade: pooled correlation with "
                              f"the real win V_SETUP {pc['V_SETUP']['corr']} (n {pc['V_SETUP']['n']}), V_EXITS "
                              f"{pc['V_EXITS']['corr']} (n {pc['V_EXITS']['n']})."),
                     costs="each arm's own study costs (R120's lists)"),
        notes="The final is not fresh. A skipped trade earns 0 (no re-walk; twins the same). Underlying only, at delta 0.5.")


if __name__ == "__main__":
    res = json.loads((S.RESULTS / "final.json").read_text())
    (S.RESULTS / "report.md").write_text(report(res))
    e = entry(res)
    (S.K.ENTRIES / "r121-time-machine-setup.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True,
                                                                             width=120))
    print("written", e["verdict"])
