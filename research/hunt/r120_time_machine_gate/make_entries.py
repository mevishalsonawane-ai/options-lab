"""Write R120's experiment-log entry (research/experiments/entries/r120-time-machine-gate.yaml) from results/final.json.

    python research/hunt/r120_time_machine_gate/make_entries.py

One entry for the family (2 rules x the arms planned), failure included. 28 Oct 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r120cfg as K  # noqa: E402


def rs(x):
    return ("-" if x < 0 else "") + f"Rs {abs(round(x)):,}"


def main():
    r = json.loads((K.RESULTS / "final.json").read_text())
    parts = []
    for k, t in r["tests"].items():
        c = r["arms"][t["arm"]]["cells"][t["rule"]]["final"]
        parts.append(f"{t['arm']} {t['rule']}: final {c['base']['trades']} trades, {c['rule']['skipped']} skipped, "
                     f"{rs(c['base']['net'])} -> {rs(c['rule']['net'])}, twin p {t['p']:.3f}, q {t['q_all']:.2f}")
    pc = r["pooled_calibration"]
    passed = r["passed"]
    e = dict(
        id="r120-time-machine-gate",
        title="R120 the time machine's verdict as a pre-entry gate on every arm (skip 'against' / take only 'edge')",
        family="daily-filters", date="2026-10-28", doc="research/HUNT_R120_TIME_MACHINE_GATE.md",
        data_version="Drive lake gdrive:IraAlgo-data-lake copied 28 Oct 2026 (index_1m, options_rolling_1m, ...); "
                     "R51 Amendment A clean stores; each arm's own study data via R113's adapters",
        period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                    holdout="2026-04-01..2026-10-13 read once (already read by R51-R119)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["signal source: each arm's own (the underlying; Hero's strike by the option's price, labelled); the "
                  "filter reads only the underlying's own 60 sessions before the entry day"],
        hypothesis="The app's time machine verdict (2,000 bootstrap replays of the entry on the underlying's last 60 "
                   "sessions, the arm's own stop and target in underlying points, stop before target inside a bar), "
                   "worked out BEFORE the entry, improves an arm as a filter: H1 skip 'against', H2 take only 'edge'. "
                   "Gates: net up in design, walk-forward and final; final above the random-skip twin's 95th "
                   f"percentile; BH q <= 0.10 over {r['m_all']:,} tests; >= 40 final trades; drawdown not worse.",
        model_or_rule="research/hunt/r120_time_machine_gate/tm.py (TimeMachine.kt ported, Kotlin's RNG included, "
                      "pinned to the engine's own numbers); r120arms.py (each arm's ask as its TimeMachineHost.Entry "
                      "call passes it); r120study.py",
        settings="PREREG.md + Amendment A1; the app's draws, slot tolerance, lookback, margin (none chosen by R120)",
        prereg="research/hunt/r120_time_machine_gate/PREREG.md",
        variants_tried=K.M_R120, holdout_read=True,
        verdict=("paper-only" if passed else "rejected"),
        results=dict(
            summary=(f"Passed: {', '.join(passed) if passed else 'none'} of {len(r['tests'])} tested cells "
                     f"({K.M_R120} registered; untested counted as p = 1). " + "; ".join(parts) +
                     f". Calibration (pooled, design + final): n {pc['n']}, Brier {pc['brier']} vs "
                     f"{pc['brier_base']} for the constant win rate, correlation {pc['corr']}."),
            costs="each arm's own study costs: dated Zerodha charges, R17 fills, next-minute fills, 1 lot (R3 ORB: "
                  "R3's charges; Pine: h22's)"),
        notes=("Not tested: " + "; ".join(f"{a} ({w})" for a, w in {**K.NOT_TESTABLE, **r.get('untested', {})}.items())
               + ". A skipped trade earns 0 (the arm's book is not re-walked; the twins are built the same way). "
                 "Every underlying given the full 60 sessions (the phone has them for NIFTY / BANKNIFTY, fewer for "
                 "others)."),
    )
    (K.ENTRIES / "r120-time-machine-gate.yaml").write_text(
        yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("written", e["verdict"])


if __name__ == "__main__":
    main()
