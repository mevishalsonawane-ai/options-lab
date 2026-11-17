"""R183 report: results/report.md (the plain-English summary.md first, then every table). Plain-English notes, 15 Nov 2026."""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r183lib as LB  # noqa: E402

RESULTS = HERE / "results"
NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend"}
PER = {"early": "early (20 Oct 2025 - 31 Mar 2026; picker in-sample)", "final": "final (1 Apr - 13 Oct 2026)",
       "six": "last 6 months (20 Apr - 19 Oct 2026)", "year": "whole year (20 Oct 2025 - 19 Oct 2026)"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.1f}%"


def build() -> str:
    sel = json.loads((RESULTS / "selection.json").read_text())
    t = json.loads((RESULTS / "test.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    rc = json.loads((RESULTS / "recent.json").read_text()) if (RESULTS / "recent.json").exists() else None
    o = [(RESULTS / "summary.md").read_text().rstrip() + "\n", "# Full tables\n"]
    o.append("Signal source of every filter: the index the arm signals on (1-minute and 5-minute bars strictly before "
             "the signal bar). Baseline B = R179's V4 (today's rules + R151 picker + breakeven at +10%), 1 lot, after "
             "dated Zerodha charges and R17 fills (slippage). A skipped entry still holds its side (as the app's picker).\n")
    o.append("## Parity (asserted before anything else was read)\n\n| arm : period | trades | net | R179 published | ok |\n"
             "|---|---|---|---|---|")
    for k, p in t["parity"].items():
        o.append(f"| {k} | {p['trades']:,} | {rs(p['net'])} | {p['published'][0]:,} / {rs(p['published'][1])} | "
                 f"{'yes' if p['reproduced'] else 'NO'} |")
    o.append("\n## Selection: 20 Nov 2024 - 19 Oct 2025 (1 lot; H1 to 30 Apr 2025, H2 from 1 May 2025)\n")
    for a in LB.ARMS:
        s = sel[a]
        b = s["B"]
        o.append(f"### {NAME[a]}: chosen **{s['chosen'] or 'none (no variant beat B in both halves)'}**\n")
        o.append(f"B: {b['trades']:,} trades, {pct(b['win'])} won, {rs(b['net'])} (H1 {rs(b['H1'])}, H2 {rs(b['H2'])}), "
                 f"worst month {rs(b['worst_month']['net'])} ({b['worst_month']['month']}), max drawdown {rs(b['max_dd'])}.\n")
        o.append("| variant | rule (skip when) | trades | skipped | win | net | gain H1 | gain H2 | gain whole | worst month | "
                 "max DD | eligible |\n|---|---|---|---|---|---|---|---|---|---|---|---|")
        for v in LB.VARIANTS:
            x = s[v]
            o.append(f"| {v} | {LB.LABEL[v]} | {x['trades']:,} | {x['skipped']:,} | {pct(x['win'])} | {rs(x['net'])} | "
                     f"{rs(x['gain_H1'])} | {rs(x['gain_H2'])} | {rs(x['gain'])} | {rs(x['worst_month']['net'])} | "
                     f"{rs(x['max_dd'])} | {'yes' if x['eligible'] else 'no'} |")
        o.append("")
    m = d["_m"]
    o.append(f"## The one test: the last year (1 lot), BH m = {m['total']:,} ({m['prior']:,} earlier + {m['selection']} "
             f"selection comparisons + {m['tests']} test)\n")
    for a in LB.ARMS:
        if a not in d:
            o.append(f"- {NAME[a]}: nothing chosen, so no test.")
            continue
        g, tw = d[a], t["twins"][a]
        o.append(f"- {NAME[a]}, **{g['variant']}** ({LB.LABEL[g['variant']]}): passed **{'yes' if g['passed'] else 'no'}**. "
                 f"Skipped {tw['skipped']} of B's year trades; net {rs(tw['net'])} vs the 2,000 random-skip twins' mean "
                 f"{rs(tw['mean'])} (sd {rs(tw['sd'])}); empirical p {g['p_emp']}, normal p {g['p_norm']:.3f}, BH q "
                 f"{g['q_all']} (q within R183 {g['q_own']}). Failed: {'; '.join(g['fails'])}.")
    o.append("")
    for lots in (1, 3):
        o.append(f"### B vs the chosen filter, {lots} lot{'s' if lots > 1 else ''}\n")
        o.append("| arm | period | B trades / win / net | filter trades / win / net | B worst month / max DD | "
                 "filter worst month / max DD |\n|---|---|---|---|---|---|")
        for a in LB.ARMS:
            if a not in d:
                continue
            for p in ("early", "final", "six", "year"):
                b, s = t[p][f"B@{lots}"][a], t[p][f"S@{lots}"][a]
                o.append(f"| {a} | {PER[p]} | {b['trades']:,} / {pct(b['win'])} / {rs(b['net'])} | {s['trades']:,} / "
                         f"{pct(s['win'])} / {rs(s['net'])} | {rs(b['worst_month']['net'])} / {rs(b['max_dd'])} | "
                         f"{rs(s['worst_month']['net'])} / {rs(s['max_dd'])} |")
        o.append("")
    o.append("## Every variant on the last year (descriptive only; read after the one test, never used to choose)\n")
    o.append("| variant | R72 year: trades / win / net (skipped) | R72 final net | R51 year: trades / win / net (skipped) | "
             "R51 final net |\n|---|---|---|---|---|")
    for v in ("B",) + LB.VARIANTS:
        cells = []
        for a in LB.ARMS:
            y, f = t["year"][f"{v}@1"][a], t["final"][f"{v}@1"][a]
            sk = "" if v == "B" else f" ({t['skipped_year'][f'{v}@1'][a]:,})"
            cells += [f"{y['trades']:,} / {pct(y['win'])} / {rs(y['net'])}{sk}", rs(f["net"])]
        o.append(f"| {v} | " + " | ".join(cells) + " |")
    o.append("")
    if rc:
        o.append("## 9 - 13 Nov 2026 paper trades (descriptive; the engine's stores end 19 Oct 2026)\n")
        o.append(f"{rc['trades']} R51 / R72 paper trades rebuilt from the phone's diagnostics ({rc['wins']} won, "
                 f"{rs(rc['net'])} paper net; no paper trades on 10 Nov, which was not recorded). Filter values computable "
                 f"for {rc['by_source']['index']} (NIFTY / BANKNIFTY index) + {rc['by_source']['near future (proxy)']} "
                 f"(FINNIFTY / MIDCPNIFTY near future, a proxy); {rc['by_source']['not recorded']} SENSEX trades not "
                 f"computable. Weekdays without a recording since 15 Oct: {', '.join(rc['weekdays_not_recorded'])}.\n")
        o.append("| arm : variant | computable | skipped (won) | skipped paper net | kept (won) | kept paper net |\n"
                 "|---|---|---|---|---|---|")
        for k, x in rc["by_variant"].items():
            o.append(f"| {k} | {x['computable']} | {x['skipped']} ({x['skipped_wins']}) | {rs(x['skipped_net'])} | "
                     f"{x['kept']} ({x['kept_wins']}) | {rs(x['kept_net'])} |")
        o.append("")
    (RESULTS / "report.md").write_text("\n".join(o) + "\n")
    return "\n".join(o)


if __name__ == "__main__":
    build()
    print("report.md written")
