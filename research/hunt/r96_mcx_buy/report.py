"""R96 report: results/design_round<N>.json (+ any holdout_*.json) -> results/report_round<N>.md, and LEDGER.md.

    python -m research.hunt.r96_mcx_buy.report [round]
"""
from __future__ import annotations

import json
import sys

from research.hunt.r96_mcx_buy import cfg as K
from research.hunt.r96_mcx_buy import rules as R

NAMES = {"S1": "squeeze release, ATM straddle", "S2": "squeeze release, 1-ITM direction",
         "N1": "NR7 breakout, 1-ITM", "W1": "wide-range continuation, 1-ITM", "P1": "pullback in trend, 1-ITM",
         "V1": "IV below HAR forecast, ATM straddle", "E1": "event straddle (EIA / storage / CPI / NFP)",
         "E2": "event follow, 1-ITM", "U1": "US-session momentum (big first half-hour), 1-ITM"}


def rs(x) -> str:
    if x is None:
        return "-"
    s = "-" if x < 0 else ""
    v = abs(float(x))
    if v >= 1e5:
        return f"{s}Rs {v / 1e5:.2f} lakh"
    return f"{s}Rs {v:,.0f}"


def verdict(r) -> str:
    if r.get("pass"):
        return "PASS (holdout once)"
    return "lead" if r.get("lead") else "dies"


def main(round_no: int = 1):
    dj = json.loads((K.RESULTS / f"design_round{round_no}.json").read_text())
    L = []
    L.append(f"# R96 round {round_no}: design results ({dj['design'][0]} .. {dj['design'][1]})\n")
    L.append(f"Ideas in the cumulative ledger: {dj['ideas_in_ledger']}. 1 lot, net after real MCX charges and spreads.\n")
    L.append("| rule | trades | win | avg win / loss | gross | costs | net | net at 2x spread | folds + (F1..F4 net) | "
             "worst month (below -13k) | max DD | w/o top 3 | twin mean | twin p | BH q | model / stale exits | "
             "median stop risk -> capital at 2% | verdict |")
    L.append("|" + "---|" * 18)
    for rid in R.ORDER:
        r = dj["rules"].get(rid)
        if r is None:
            continue
        s = r["summary"]
        if not s.get("trades"):
            L.append(f"| {rid} {NAMES[rid]} | 0 | | | | | | | | | | | | | | | | dies (no trades) |")
            continue
        fl = r["folds"]
        fs = ", ".join(f"{rs(f['net'])} ({f['trades']})" for f in fl)
        nf = sum(f["net"] > 0 for f in fl)
        L.append(f"| {rid} {NAMES[rid]} | {s['trades']} | {s['win']:.0%} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
                 f"{rs(s['gross'])} | {rs(s['cost'])} | **{rs(s['net'])}** | {rs(s['net2'])} | {nf} of 4: {fs} | "
                 f"{rs(s['worst_month'])} ({s['months_below_stop']} of {s['months']}) | {rs(s['max_dd'])} | "
                 f"{rs(s['wo_top3'])} | {rs(r['twin_mean'])} | {r['twin_p']:.3f} | {r['bh_q']:.3f} | "
                 f"{s['model_share']:.0%} / {s['stale_share']:.0%} | {rs(s['risk_median'])} -> {rs(s['cap2_median'])} | "
                 f"{verdict(r)} |")
    L.append("\n## Gates per rule\n")
    for rid in R.ORDER:
        r = dj["rules"].get(rid)
        if r is None:
            continue
        failed = [k for k, v in r.get("gates", {}).items() if not v]
        L.append(f"- {rid}: failed {', '.join(failed) if failed else 'none'}")
    L.append("\n## By book (design)\n")
    L.append("| rule | " + " | ".join(K.BOOK_ORDER) + " |")
    L.append("|---|" + "---|" * len(K.BOOK_ORDER))
    for rid in R.ORDER:
        r = dj["rules"].get(rid)
        if r is None or not r["summary"].get("trades"):
            continue
        bb = r["summary"]["by_book"]
        cells = []
        for b in K.BOOK_ORDER:
            x = bb.get(b)
            cells.append("-" if x is None else f"{x['trades']}: gross {rs(x['gross'])}, net {rs(x['net'])}, "
                                               f"win {x['win']:.0%}")
        L.append(f"| {rid} | " + " | ".join(cells) + " |")
    L.append("\n## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)\n")
    L.append("| rule | " + " | ".join(K.BOOK_ORDER) + " | pooled |")
    L.append("|---|" + "---|" * (len(K.BOOK_ORDER) + 1))
    for rid in R.DIRECTIONAL_DAILY:
        r = dj["rules"].get(rid)
        if r is None or not r.get("history"):
            continue
        h = r["history"]
        cells = [f"{h[b]['n']}: {h[b]['mean'] * 100:+.2f}% (twin {h[b]['twin_mean'] * 100:+.2f}%, p {h[b]['p']:.2f})"
                 if h[b]["n"] else "-" for b in K.BOOK_ORDER]
        L.append(f"| {rid} | " + " | ".join(cells) + f" | {h['pooled']['n']}: {h['pooled']['mean'] * 100:+.2f}% |")
    (K.RESULTS / f"report_round{round_no}.md").write_text("\n".join(L) + "\n")
    ledger(round_no)


def ledger(round_no):
    rows = json.loads((K.RESULTS / "ledger.json").read_text())
    designs = {}
    for r in sorted({x["round"] for x in rows}):
        p = K.RESULTS / f"design_round{r}.json"
        if p.exists():
            designs[r] = json.loads(p.read_text())["rules"]
    L = ["# R96 cumulative ledger: every idea tried in every round\n",
         f"Benjamini-Hochberg runs over ALL rows (every round) on the design twin p. Ideas tried so far: {len(rows)}.\n",
         "| round | id | idea | design trades | design net | twin p | BH q (cumulative) | verdict |",
         "|---|---|---|---|---|---|---|---|"]
    for x in rows:
        d = designs.get(x["round"], {}).get(x["id"], {})
        name = NAMES.get(x["id"], d.get("name", ""))
        p = x.get("p")
        L.append(f"| {x['round']} | {x['id']} | {name} | {x['trades']} | {rs(x['net'])} | "
                 f"{'-' if p is None or p != p else f'{p:.3f}'} | {x['q']:.3f} | {verdict(d) if d else '-'} |")
    K.LEDGER.write_text("\n".join(L) + "\n")


if __name__ == "__main__":
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 1)
