"""R82 tables for the write-up, from results/design.json and results/final.json -> results/report.md.

    python3 -m research.hunt.r82.report
"""
from __future__ import annotations

import json

from research.hunt.r82 import r82lib as L
from research.hunt.r82 import study as S


def rs(x):
    if x is None:
        return "n/a"
    return ("-" if x < 0 else "+") + f"{abs(x):,.0f}"


def pct(x):
    return "n/a" if x is None else f"{100 * x:.0f}%"


def main():
    des = json.loads((L.RESULTS / "design.json").read_text())
    fin = json.loads((L.RESULTS / "final.json").read_text())
    D, F, G = des["tests"], fin["tests"], fin["gates"]
    out = ["# R82 tables (Rs per 1 lot of GOLDM + 1 lot of SILVERM, rupee proxies, after costs)", ""]
    out += ["## Main table", "",
            "| test | signal source | design trades | design win | design net | design max DD | WF net (blocks +) | twin p | "
            "BH q | final trades | final win | final net | final max DD | final twin p | final minus top 5 | baseline final | pass |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for t in ["T0"] + S.ALL_TESTS:
        d, f = D[t], F[t]
        b = S.BASE.get(t)
        bf = rs(F[b]["final"]["net"]) if b else ("0" if t != "T0" else "-")
        out.append(
            f"| {t} | {S.SIGNAL[t]} | {d['design']['trades']} | {pct(d['design']['win'])} | {rs(d['design']['net'])} | "
            f"{rs(d['design']['max_dd'])} | {rs(d['wf_net'])} ({d['wf_green']}/{len(d['wf_blocks'])}) | "
            f"{d.get('twin_p', float('nan')):.3f} | {d.get('bh_q', float('nan')):.3f} | {f['final']['trades']} | "
            f"{pct(f['final']['win'])} | {rs(f['final']['net'])} | {rs(f['final']['max_dd'])} | "
            f"{f.get('twin_p', float('nan')):.3f} | {rs(f['top5_removed'])} | {bf} | "
            f"{'-' if t == 'T0' else ('**yes**' if G[t]['pass'] else 'no')} |")
    out += ["", "## Gates", "", "| test | positive D/WF/F | beats baseline WF+F | design twin (q, p) | final twin | not top-5 | "
            ">= 30 trades |", "|---|---|---|---|---|---|---|"]
    for t in S.ALL_TESTS:
        g = G[t]
        out.append("| " + t + " | " + " | ".join("yes" if g[k] else "no" for k in
                   ("positive", "beats_baseline", "design_twin", "final_twin", "not_top5", "enough")) + " |")
    out += ["", "## Per metal, long / short, exits", "",
            "| test | design gold | design silver | design longs | design shorts | final gold | final silver | final longs | "
            "final shorts | final exits |", "|---|---|---|---|---|---|---|---|---|---|"]
    for t in ["T0"] + S.ALL_TESTS:
        d, f = D[t]["design"], F[t]["final"]

        def bm(s, m):
            x = s.get("by_metal", {}).get(m)
            return f"{rs(x['net'])} ({x['trades']})" if x else "n/a"

        def ls(s, k):
            x = s.get(k)
            return f"{rs(x['net'])} ({x['trades']})" if x else "n/a"
        ex = ", ".join(f"{k} {v}" for k, v in sorted(f.get("exits", {}).items(), key=lambda kv: -kv[1]))
        out.append(f"| {t} | {bm(d, 'gold')} | {bm(d, 'silver')} | {ls(d, 'longs')} | {ls(d, 'shorts')} | {bm(f, 'gold')} | "
                   f"{bm(f, 'silver')} | {ls(f, 'longs')} | {ls(f, 'shorts')} | {ex} |")
    out += ["", "## Walk-forward blocks (net, trades)", ""]
    for t in ["T0"] + S.ALL_TESTS:
        out.append(f"- {t}: " + ", ".join(f"{rs(b['net'])} ({b['trades']})" for b in D[t]["wf_blocks"]))
    out += ["", "## Largest final trades (by size)", ""]
    for t in ["T0"] + S.ALL_TESTS:
        for e in F[t]["examples"]:
            out.append(f"- {t} {e['metal']} {'long' if e['side'] > 0 else 'short'} {e['entry_t']} -> {e['exit_t']} "
                       f"{e['entry']:,} -> {e['exit']:,} ({e['why']}) {rs(e['net'])}")
    (L.RESULTS / "report.md").write_text("\n".join(out) + "\n")
    print("\n".join(out))


if __name__ == "__main__":
    main()
