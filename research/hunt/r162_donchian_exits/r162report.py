"""R162 report: results/report.md from design_*.json, final_*.json, decision.json, picker.json and the hand-written
plain-English summary results/boss_summary.md (written after the numbers, placed first for Boss's phone).

    python research/hunt/r162_donchian_exits/r162report.py
"""
from __future__ import annotations

import json

import r162cfg as K


def rs(x) -> str:
    if x is None:
        return "-"
    x = float(x)
    s = "-" if x < 0 else ""
    v = f"{abs(x):,.0f}"
    # Indian grouping (lakh / crore)
    n = f"{abs(round(x)):d}"
    if len(n) > 3:
        head, tail = n[:-3], n[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        v = ",".join(parts) + "," + tail
    return f"{s}Rs {v}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * float(x):.1f}%"


def row(name: str, s: dict, tw: dict | None = None, g: dict | None = None, rw=None) -> str:
    cells = [name, str(s["trades"]), pct(s["win"]) + (f" ({pct(rw)})" if rw is not None else ""), pct(s.get("be_win")),
             rs(s["net"]), rs(s.get("avg_win")), rs(-(s.get("avg_loss") or 0)), rs(s.get("worst_day")), rs(s.get("max_dd")),
             rs(s.get("ex_top5"))]
    if tw is not None:
        cells += [f"{tw['p_net']:.3f} / {tw['p_win']:.3f}", f"{g['q_all']:.3f}" if g else "-",
                  ("PASS" if g and g["passed"] else "fail") if g else "-"]
    return "| " + " | ".join(cells) + " |"


HEAD = ("| rule | trades | win % | break-even win % | net | avg win | avg loss | worst day | max DD | net without 5 best |")
HEAD_TW = HEAD + " twin p (net / win) | BH q | gates |"


def sep(n: int) -> str:
    return "|" + "---|" * n


def main():
    d = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    f = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    pk_path = K.RESULTS / "picker.json"
    pk = json.loads(pk_path.read_text()) if pk_path.exists() else None
    boss = K.RESULTS / "boss_summary.md"
    out = ["# R162: trade structure for a higher Donchian win rate - results", ""]
    if boss.exists():
        out += [boss.read_text().strip(), ""]
    out += ["## How it was tested", "",
            f"Pre-registered in `research/hunt/r162_donchian_exits/PREREG.md` (committed before any number). Arms: "
            f"{K.ARM_NAME['R72']} and {K.ARM_NAME['R51']}; entries unchanged (signal on the index). Engine, fills, dated "
            "Zerodha charges and R17 slippage exactly as R152. Design 20 Nov 2024 - 31 Mar 2026 (walk-forward window "
            "Apr 2025 - Mar 2026); final 1 Apr - 13 Oct 2026, read once by R162 but read by many earlier studies "
            "(weak evidence). Win = the whole position's net > 0 after charges; scale-out also shows the per-round win in "
            "brackets. Twins: 500 per test (seed 162); the test's p is the larger of the net and win p. BH: m = "
            f"{dec['_m']['total']} (R162 {dec['_m']['r162']} + {dec['_m']['prior']} earlier tests as p = 1). Raw p-values "
            "for a joint BH with R161 / R163: `results/raw_pvalues.csv`.", ""]
    n_pass = sum(1 for k, v in dec.items() if not k.startswith("_") and v["passed"])
    out += [f"**Tests: {len(K.TESTS)}; passed every gate: {n_pass}.**", ""]
    for a in K.ARMS:
        for lots in K.LOTS:
            out += [f"## {K.ARM_NAME[a]}, {lots} lot{'s' if lots > 1 else ''}", ""]
            for per, lab, src in (("final", "Final (1 Apr - 13 Oct 2026)", f[a]), ("wf", "Walk-forward window (Apr 2025 - Mar 2026)", d[a]),
                                  ("all", "Design (20 Nov 2024 - 31 Mar 2026)", d[a])):
                key = "all" if per in ("final", "all") else "wf"
                out += [f"**{lab}**", "", HEAD_TW if per == "final" else HEAD, sep(13 if per == "final" else 10)]
                b = src[f"B0@{lots}"][key]
                out.append(row("B0 today", b) + (" - | - | - |" if per == "final" else ""))
                for r in K.RULE_IDS:
                    if lots not in K.lots_of(r):
                        continue
                    s = src[f"{r}@{lots}"][key]
                    rw = s.get("round_win") if K.RULES[r]["kind"] == "so" else None
                    if per == "final":
                        out.append(row(r, s, src[f"{r}@{lots}"]["twin"], dec[f"{a}:{r}@{lots}"], rw))
                    else:
                        out.append(row(r, s, rw=rw))
                out.append("")
    out += ["## Walk-forward quarters (1 lot): net and win %", "",
            "| arm | rule | Q2 2025 | Q3 2025 | Q4 2025 | Q1 2026 |", sep(6)]
    for a in K.ARMS:
        for r in ("B0",) + K.RULE_IDS:
            lots = 1 if 1 in (K.lots_of(r) if r != "B0" else K.LOTS) else 2
            q = d[a][f"{r}@{lots}"]["wf_q"]
            out.append(f"| {a} | {r}{'' if lots == 1 else ' (2 lots)'} | " + " | ".join(f"{rs(x['net'])} / {pct(x['win'])}" for x in q) + " |")
    out.append("")
    out += ["## Gate failures (every test)", "", "| test | p | BH q | why it failed |", sep(4)]
    for a, r, n in K.TESTS:
        g = dec[f"{a}:{r}@{n}"]
        out.append(f"| {a} {r} @{n} | {g['p']:.3f} | {g['q_all']:.3f} | {'PASS' if g['passed'] else '; '.join(g['fails'])} |")
    out.append("")
    if pk:
        out += ["## Second look: on the trades R151's pickers take (Apr - Oct 2026, descriptive, never gated)", "",
                "A skipped break still holds its side and counts in the caps (as the app). Apr - Oct 2026 was read many "
                "times and the pickers failed their gates.", ""]
        for a in K.ARMS:
            for lots in K.LOTS:
                out += [f"**{a}, {lots} lot{'s' if lots > 1 else ''}**", "", HEAD, sep(10)]
                b = pk[a][f"B0@{lots}"]
                out.append(row("B0 picker", b))
                for r in K.RULE_IDS:
                    if lots in K.lots_of(r):
                        s = pk[a][f"{r}@{lots}"]
                        out.append(row(r, s, rw=s.get("round_win") if K.RULES[r]["kind"] == "so" else None))
                out.append("")
        out += ["## Separate item: when a picker-skipped break releases its side (Apr - Oct 2026, today's exits, 1 lot)", "",
                "| arm | release rule | trades | win % | net | worst day | max DD |", sep(7)]
        lab = {"P-APP": "holds until its would-be exit (the app today)", "P-N10": "holds 10 min", "P-N5": "holds 5 min",
               "P-IMM": "releases at once, counts in the cap", "P-R151": "removed before the walk (R151 research)"}
        for a in K.ARMS:
            for name in K.RELEASE:
                s = pk[a]["release"][name]
                out.append(f"| {a} | {name}: {lab[name]} | {s['trades']} | {pct(s['win'])} | {rs(s['net'])} | "
                           f"{rs(s['worst_day'])} | {rs(s['max_dd'])} |")
            par = pk[a]["release_parity_r151"]
            out.append(f"| {a} | parity: P-R151 vs R151's published picks | {par['trades']} vs {par['published'][0]} | | "
                       f"{rs(par['net'])} vs {rs(par['published'][1])} | | |")
        out.append("")
    out += ["## Not tested", "",
            "- Next-week expiry on expiry / near-expiry days: the lake holds only the nearest (WEEK) and monthly (MONTH) "
            "series, and the arms never trade the index's own expiry day; logged as `not-a-test`.",
            "- The post window (14 - 19 Oct) was not used.", "",
            "## Notes", "",
            "- D70 (delta 0.70) buys much deeper strikes on the monthly-expiry indices (MIDCPNIFTY, FINNIFTY, BANKNIFTY have "
            "weeks to expiry, so a 0.70 delta sits several strikes in the money, e.g. MIDCPNIFTY 9 steps): premiums, and so "
            "the -15% stop in rupees, are much bigger, hence its large design losses (checked trade by trade; not a bug).",
            "- Scale-out's change vs today is nearly the same at 2 and 3 lots: only the one scaled lot differs.", ""]
    (K.RESULTS / "report.md").write_text("\n".join(out))
    print("report.md", len(out), "lines")


if __name__ == "__main__":
    main()
