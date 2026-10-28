"""R120 report: results/final.json -> results/report.md (every arm, rule and period, with n). 28 Oct 2026.

    python research/hunt/r120_time_machine_gate/r120report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r120cfg as K  # noqa: E402

PERS = (("design", "design"), ("wf", "walk-forward"), ("final", "final"))


def rs(x) -> str:
    if x is None:
        return "-"
    return ("-" if x < 0 else "") + f"Rs {abs(round(x)):,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def main() -> str:
    r = json.loads((K.RESULTS / "final.json").read_text())
    L = ["# R120 results: the time machine's verdict as a pre-entry gate", "",
         f"BH family: m = {r['m_all']:,} (every logged test to {K.ENTRIES_LAST_DAY}: {r['m_prior']:,}, plus R120's "
         f"{r['m_r120']}). Twins: {K.TWINS:,} random skips of the same number of trades. Skipped trades earn Rs 0. "
         "1 lot, each arm's own charges and fills. Signal source: each arm's own (the underlying; Hero picks its strike "
         "by the option's price). The time machine reads only the underlying's 60 sessions before the entry day.", "",
         f"**Passed every gate: {', '.join(r['passed']) or 'none'}.**", "", "## Gates per arm and rule (final p and q)", "",
         "| arm | rule | final trades kept / all | net before | net after | twin 95th pct | p | q (all) | q (R120) | "
         "passed | fails |", "|---|---|---:|---:|---:|---:|---:|---:|---:|---|---|"]
    for k, t in r["tests"].items():
        c = r["arms"][t["arm"]]["cells"][t["rule"]]["final"]
        L.append(f"| {K.ARM_NAME[t['arm']]} | {t['rule']} | {c['rule']['kept']} / {c['base']['trades']} | "
                 f"{rs(c['base']['net'])} | {rs(c['rule']['net'])} | {rs(c['twin_p95'])} | {t['p']:.3f} | "
                 f"{t['q_all']:.2f} | {t['q_r120']:.2f} | {'yes' if t['gates']['passed'] else 'no'} | "
                 f"{'; '.join(t['gates']['fails'])} |")
    L += ["", "## Every period", ""]
    for arm, a in r["arms"].items():
        L += [f"### {a['name']}", "", "| period | rule | trades | skipped | net before | net after | win before | "
              "win after | max DD before | max DD after | twin p |", "|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
        for rule, cc in a["cells"].items():
            for per, nm in PERS:
                c = cc[per]
                L.append(f"| {nm} | {rule} | {c['base']['trades']} | {c['rule']['skipped']} | {rs(c['base']['net'])} | "
                         f"{rs(c['rule']['net'])} | {pct(c['base']['win'])} | {pct(c['rule']['win'])} | "
                         f"{rs(c['base']['max_dd'])} | {rs(c['rule']['max_dd'])} | {c.get('p', '-')} |")
        L += ["", "Verdicts (n, win rate, mean net per trade):", ""]
        for per, nm in PERS:
            v = a["verdicts"].get(per, {})
            L.append(f"- {nm}: " + "; ".join(f"{k} {x['n']} ({pct(x['win'])}, {rs(x['mean'])})" for k, x in v.items()))
        L += ["", "Calibration (predicted = target first / decided draws; actual = net > 0):", ""]
        for per in ("design", "final"):
            c = a["calibration"].get(per, {})
            if not c.get("n"):
                L.append(f"- {per}: no trade with decided draws")
                continue
            L.append(f"- {per}: n {c['n']}, Brier {c['brier']} vs {c['brier_base']} for the constant design win rate "
                     f"{pct(c['base_rate'])}; correlation {c['corr']}; bins " +
                     ", ".join(f"{b['bin']} n {b['n']} pred {pct(b['predicted'])} actual {pct(b['actual'])}"
                               for b in c["bins"]))
        L.append("")
    pc = r["pooled_calibration"]
    L += ["## Pooled calibration (all tested arms, design + final)", "",
          f"n {pc['n']}, Brier {pc['brier']} vs {pc['brier_base']} for the pooled win rate {pct(pc['base_rate'])}; "
          f"correlation {pc['corr']}.", "", "| bin | n | predicted | actual |", "|---|---:|---:|---:|"]
    L += [f"| {b['bin']} | {b['n']} | {pct(b['predicted'])} | {pct(b['actual'])} |" for b in pc["bins"]]
    L += ["", "## Not tested", ""]
    for a, why in {**K.NOT_TESTABLE, **r.get("untested", {})}.items():
        L.append(f"- {K.ARM_NAME.get(a, a)}: {why}")
    txt = "\n".join(L) + "\n"
    (K.RESULTS / "report.md").write_text(txt)
    return txt


if __name__ == "__main__":
    print(main()[:3000])
