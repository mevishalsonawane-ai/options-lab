"""R178 report: results/report.md (summary on top, then the selection and last-year tables)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r176_step_trail_500"))
import r176report as RP  # noqa: E402

RESULTS = HERE / "results"
VARS = ("K1", "K2", "K3", "K4", "K5", "K6")
NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "ALL": "All three"}


def build() -> str:
    s = json.loads((RESULTS / "selection.json").read_text())
    t = json.loads((RESULTS / "test.json").read_text())
    out = [(RESULTS / "summary.md").read_text().rstrip() + "\n", "## Selection year, 20 Nov 2024 - 19 Oct 2025, 1 lot\n",
           "| arm | variant | trades | win | net | H1 (to 30 Apr 2025) | H2 (from 1 May 2025) | gain vs today | candidates skipped |",
           "|---|---|---|---|---|---|---|---|---|"]
    for a in ("LIQ", "R72", "R51"):
        A = s[a]["A"]
        out.append(f"| {NAME[a]} | today | {A['trades']} | - | **{RP.rs(A['net'])}** | {RP.rs(A['H1'])} | {RP.rs(A['H2'])} | - | - |")
        for v in VARS:
            x = s[a][v]
            out.append(f"| {NAME[a]} | {v} | {x['trades']} | {RP.pct(x['win'])} | **{RP.rs(x['net'])}** | {RP.rs(x['H1'])} | "
                       f"{RP.rs(x['H2'])} | {RP.rs(x['gain'])} | {x['skipped']} |")
        out.append(f"| {NAME[a]} | chosen | {s[a]['chosen'] or 'none'} | | | | | | |")
    out += ["", "## Last year, 20 Oct 2025 - 19 Oct 2026, 1 lot (descriptive; nothing was chosen, so nothing is tested)\n",
            "| arm | today | " + " | ".join(VARS) + " |", "|---|---|" + "---|" * len(VARS)]
    for a in ("LIQ", "R72", "R51", "ALL"):
        out.append(f"| {NAME[a]} | {RP.rs(t['year']['A@1'][a]['net'])} | "
                   + " | ".join(f"{RP.rs(t['year'][f'{v}@1'][a]['net'])} ({t['year'][f'{v}@1'][a]['trades']})" for v in VARS) + " |")
    out.append("\nParity (today's rules, 1 lot, final period): " + "; ".join(
        f"{NAME[a]} {p['trades']} / {RP.rs(p['net'])} (R172 {p['published'][0]} / {RP.rs(p['published'][1])})"
        for a, p in t["parity"].items()) + ".")
    out.append("\nSignal source: the traded index for every variant. Costs: dated Zerodha charges, R17 slippage.")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    (RESULTS / "report.md").write_text(build())
