"""R75 report: results/design.json + results/final.json -> results/report.md (tables only). Pure apart from the files.

    python research/hunt/r75/r75study.py report
"""
from __future__ import annotations

import json

import r75cfg as R


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(float(x))
    s = f"{abs(x):,}"
    if abs(x) >= 100000:                         # Indian grouping: 1,23,456
        head, tail = str(abs(x))[:-3], str(abs(x))[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts) + "," + tail
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def row(name: str, st: dict, extra: list) -> str:
    aw = f"{rs(st.get('avg_win'))} / {rs(st.get('avg_loss'))}"
    acct = f"{st.get('months_13k', 0)} / {st.get('dd_40k', 0)}"
    return "| " + " | ".join([name, f"{st.get('trades', 0):,}", rs(st.get("net")), pct(st.get("win")), aw,
                              rs(st.get("worst_day")), rs(st.get("max_dd")), pct(st.get("green_days")), acct]
                             + extra) + " |"


HEAD = ("| rule | trades | net | win | avg win / loss | worst day | max DD | green days | months < -13k / DD > 40k "
        "episodes |")


def write():
    des = json.loads((R.RESULTS / "design.json").read_text())
    fin = json.loads((R.RESULTS / "final.json").read_text()) if (R.RESULTS / "final.json").exists() else None
    L = ["# R75 report: Donchian, less loss (tables)", ""]
    if fin:
        L += [f"Final period 1 Apr - {fin['end']} ({fin['sessions']} sessions), read once ({fin['read']}). Design 20 Nov "
              "2024 - 31 Mar 2026. 1 lot per trade (half size = 0.5 lot equivalent), net after dated charges and R17 "
              "slippage. Signal source: the index, every rule. The final window was read before by R51-R73 (disclosed).",
              ""]
    for base in R.BASES:
        L += [f"## {base}: {R.BASE_NAMES[base]}", ""]
        if fin:
            L += ["### Final period", "",
                  HEAD + " design net | quarters better | affected | losers avoided / winners missed | halved "
                  "(losers) | twin p (BH q) | 2x slip net | verdict |",
                  "|" + "---|" * 18]
            b = fin["base"][base]
            L.append(row(f"**{R.BASE_NAMES[base]} (baseline)**", b["final"],
                         [rs(des["base"][base]["design"]["net"]), "-", "-", "-", "-", "-", rs(b["slip2"]["net"]),
                          "baseline"]))
            for lv in R.LEVERS:
                r = fin["tests"][f"{base}:{lv}"]
                c = r["vs_base"]
                la = f"{c['losers_avoided']} ({rs(c['losers_avoided_rs'])}) / {c['winners_missed']} " \
                     f"({rs(c['winners_missed_rs'])})"
                hv = f"{c['halved']} ({c['halved_losers']})"
                v = "PASS" if r["verdict"] == "PASS" else "FAIL: " + ", ".join(r["fails"])
                L.append(row(f"{lv} {R.NAMES[lv]}", r["final"],
                             [rs(r["design"]["net"]), f"{r['wf_better']} of 4", f"{r['affected']:,}", la, hv,
                              f"{r['p']} ({r['q']})", rs(r["slip2_net"]), v]))
            L.append("")
        L += ["### Design period (20 Nov 2024 - 31 Mar 2026; L2 picks its indices in-sample here)", "",
              HEAD + " better | quarters better (Q1 / Q2 / Q3 / Q4 net) |", "|" + "---|" * 11]
        bd = des["base"][base]
        L.append(row("**baseline**", bd["design"], ["-", " / ".join(rs(q["net"]) for q in bd["quarters"])]))
        for lv in R.LEVERS:
            r = des["tests"][f"{base}:{lv}"]
            L.append(row(f"{lv} {R.NAMES[lv]}", r["design"],
                         ["yes" if r["design_better"] else "no",
                          f"{r['wf_better']} of 4 ({' / '.join(rs(q['lever']['net']) for q in r['quarters'])})"]))
        L.append("")
    if fin:
        L += ["## Combination (PREREG section 6)", ""]
        for base in R.BASES:
            c = fin["combos"][base]
            L.append(f"- {base}: passing levers {', '.join(c['passing']) or 'none'}; combined: "
                     f"{' + '.join(c['pick']) if len(c['pick']) == 2 else 'nothing to combine'}.")
            if "final" in c:
                L.append(f"  - design {rs(c['design']['net'])} (max DD {rs(c['design']['max_dd'])}), quarters better "
                         f"{c['wf_better']} of 4, final {rs(c['final']['net'])} (max DD {rs(c['final']['max_dd'])}, "
                         f"worst day {rs(c['final']['worst_day'])}); recommended: {c['recommended']}")
        L.append("")
        a = fin["base"]["A"]
        L += ["## Reference: plain Donchian 20 (A, not gated)", "", HEAD, "|" + "---|" * 9,
              row("A final", a["final"], []), row("A design", des["base"]["A"]["design"], []), ""]
    (R.RESULTS / "report.md").write_text("\n".join(L) + "\n")
