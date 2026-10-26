"""R105: results/{design,final,oos}.json -> results/report.md (every table).

    python research/hunt/r105_donchian_giveback/r105report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r105lib as L  # noqa: E402
from r105entries import rs  # noqa: E402


def write():
    des = json.loads((L.RESULTS / "design.json").read_text())
    fin = json.loads((L.RESULTS / "final.json").read_text())
    oos = json.loads((L.RESULTS / "oos.json").read_text()) if (L.RESULTS / "oos.json").exists() else None
    o = ["# R105 report: Donchian giveback (all tables)", "",
         f"Final read {fin['read']}. BH family: {fin['family']['new']} new + {fin['family']['prior']} earlier "
         f"Donchian tests ({fin['family']['prior_p_below_005']} of them with p < 0.05). 1 lot, net after charges and "
         "slippage. 'fixed' = the baseline's own trades re-priced (no re-walk).", ""]
    for arm in L.ARMS:
        D, F = des["arms"][arm], fin["arms"][arm]
        o += [f"## {L.ARM_NAMES[arm]}", "",
              "| rule | design trades / net | WF net | quarters | final trades / net | final worst day / max DD | "
              "changed d / f | twin p d / f | BH q | 2x slip final | verdict |", "|---|---|---|---|---|---|---|---|---|---|---|",
              f"| baseline | {D['base']['trades']} / {rs(D['base']['net'])} | {rs(D['base_wf_net'])} | {D['base_q']} | "
              f"{F['base']['trades']} / {rs(F['base']['net'])} | {rs(F['base']['worst_day'])} / {rs(F['base']['max_dd'])}"
              f" | - | - | - | {rs(F['base_slip2_net'])} | - |"]
        for rule in L.RULES:
            t = fin["tests"][f"{arm}:{rule}"]
            g = t["gate"]
            o.append(f"| {rule} {L.NAMES[rule]} | {t['design']['trades']} / {rs(t['design']['net'])} | {rs(t['wf_net'])} | "
                     f"{t['q']} | {t['final']['trades']} / {rs(t['final']['net'])} | {rs(t['final']['worst_day'])} / "
                     f"{rs(t['final']['max_dd'])} | {t['changed_design']} / {t['changed_final']} | {t['p_design']} / "
                     f"{t['p_final']} | {t['q_bh']} | {rs(t['slip2_final_net'])} | "
                     f"{'PASS' if g['passed'] else 'fail: ' + ', '.join(g['failed'])} |")
        o += ["", "### The giveback cohort (baseline trades)", "",
              "| group | design n / net | WF n / net | final n / net |" + (" OOS n / net |" if oos else ""),
              "|---|---|---|---|" + ("---|" if oos else "")]
        for k in ("all", "reached_rs1000", "rs1000_lost", "rs1000_2000_lost", "rs2000plus_lost", "pct8_25_lost",
                  "pct8_15_lost", "pct15_25_lost", "pct25plus_lost", "never8"):
            cells = [D["cohort"][k], F["cohort_wf"][k], F["cohort"][k]] + ([oos["arms"][arm]["cohort"][k]] if oos else [])
            o.append(f"| {k} | " + " | ".join(f"{c['n']} / {rs(c['net'])}" for c in cells) + " |")
        o += ["", "### What each rule does to the cohort and to the winners (same trades, no re-walk)", "",
              "| rule | period | cohort (peak >= Rs 1,000, lost) | cohort (+8-25%, lost) | baseline winners | net change |",
              "|---|---|---|---|---|---|"]
        for ph, B in (("design", D), ("final", F)):
            c1, c2 = B["cohort"]["rs1000_lost"]["net"], B["cohort"]["pct8_25_lost"]["net"]
            for rule in L.RULES:
                r = B["rules"][rule]
                o.append(f"| {rule} | {ph} | {rs(c1)} -> {rs(r['cohort_rs1000_lost'])} | {rs(c2)} -> "
                         f"{rs(r['cohort_pct8_25_lost'])} | {rs(r['base_winners'])} -> {rs(r['base_winners_after'])} | "
                         f"{rs(r['fixed_net'] - B['base']['net'])} |")
        o += ["", "### Paper bootstrap (percentile of the paper net; P(net <= paper))", "",
              "| paper | from | iid trades | consecutive trades | 6 consecutive sessions |", "|---|---|---|---|---|"]
        for k, v in F["paper_bootstrap"].items():
            nm, per = k.rsplit("_", 1)
            o.append(f"| {nm}: {v['paper']['n']} trades, {rs(v['paper']['net'])} | {per} | {v['iid']['pctile']} "
                     f"(n {v['iid']['n']}) | {v['runs']['pctile']} (n {v['runs']['n']}) | {v['sessions6']['pctile']} "
                     f"(n {v['sessions6']['n']}) |")
        if oos:
            B = oos["arms"][arm]
            o += ["", f"### OOS 14-19 Oct (4 sessions; {B['base']['trades']} trades; never gated)", "",
                  "| rule | trades | net |", "|---|---|---|", f"| baseline | {B['base']['trades']} | {rs(B['base']['net'])} |"]
            o += [f"| {r} | {B['rules'][r]['stats']['trades']} | {rs(B['rules'][r]['stats']['net'])} |" for r in L.RULES]
        o.append("")
    (L.RESULTS / "report.md").write_text("\n".join(o) + "\n")


if __name__ == "__main__":
    write()
