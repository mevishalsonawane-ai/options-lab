"""R96 report: results/design_round<N>.json (+ any holdout_*.json) -> results/report_round<N>.md, and LEDGER.md.

    python -m research.hunt.r96_mcx_buy.report [round]
"""
from __future__ import annotations

import json
import sys

from research.hunt.r96_mcx_buy import cfg as K

NAMES = {"S1": "squeeze release, ATM straddle", "S2": "squeeze release, 1-ITM direction",
         "N1": "NR7 breakout, 1-ITM", "W1": "wide-range continuation, 1-ITM", "P1": "pullback in trend, 1-ITM",
         "V1": "IV below HAR forecast, ATM straddle", "E1": "event straddle (EIA / storage / CPI / NFP)",
         "E2": "event follow, 1-ITM", "U1": "US-session momentum (big first half-hour), 1-ITM",
         "R2A": "NR7 breakout, 1-ITM, edge take-profit (real fills)", "R2B": "NR7 breakout, 2-ITM, edge take-profit",
         "R2C": "Crabel inside-day NR7 breakout, 1-ITM", "R2D": "NR7 breakout on CRUDEOIL / NATURALGAS options",
         "R2E": "NR7 breakout with a Rs 6,500 stop-risk cap", "R2F": "relaxed squeeze release, ATM straddle, 6 books",
         "R2G": "NR7 day -> ATM straddle", "R2H": "quiet-base (NR7 within 5 days) 20-day breakout, 1-ITM",
         "R3A": "NR7 breakout on crude only (CRUDEOILM + CRUDEOIL)", "R3B": "NR4 breakout on crude",
         "R3C": "NR7 breakout, future trail instead of the premium ladder (energy)",
         "R3D": "NR7 breakout in a low-volatility regime (energy)", "R3E": "weekly NR7 breakout, 5 sessions (energy)",
         "R3F": "NR7 breakout in the first two hours only (energy)",
         "R3G": "NR7 morning break still holding at the US open (energy)",
         "R3H": "EIA-day 11:00-11:30 ET momentum, 1-ITM (crude)",
         "R3I": "last-half-hour intraday momentum, 1-ITM (4 minis)",
         "R3J": "US-open range breakout in a high-volatility regime (crude)",
         "R3K": "NR7 crude, 1-ITM option with R93's 2 ATR stop + 3 ATR chandelier, 10 sessions",
         "R3L": "NR7 crude, the future itself with R93's 2 ATR stop + 3 ATR chandelier, 10 sessions",
         "R4A": "NR7 crude, morning break still holding -> entry at the US open (R3A x R3G)",
         "R4B": "NR7 crude, only breaks agreeing with the 12-month trend (TSMOM filter)",
         "R4C": "EIA spike fade, 1-ITM against the first 5 minutes (crude)",
         "R4D": "MCX 17:00-17:30 IST momentum, 1-ITM, same session (GOLDM, SILVERM)",
         "R4E": "NR7 crude, 2-ITM strike",
         "R4F": "NR7 crude, only breaks agreeing with the 1-month trend",
         "R4G": "NR7 crude, only when the NR7 day's ATM IV is below its 20-session median",
         "R5A": "natural-gas EIA storage day, 1-ITM call from 17:00 IST, same session",
         "R5B": "NR7 breakout on gas and metals, only on cheap-IV days (R4G's filter)",
         "R5C": "NR7 crude -> ATM straddle, only on cheap-IV days",
         "R5D": "EIA spike fade at 0.10 ATR (crude)",
         "R5E": "pre-event ATM straddle 17:00 IST -> release - 5 min (EIA crude, EIA storage)",
         "R5F": "gold / silver ratio breakout -> 1-ITM SILVERM, 5 sessions",
         "R6A": "NR7 breakout on the untouched COPPER and ZINC option books (R3A's rule)",
         "R6B": "NR7 breakout on COPPER and ZINC, only on cheap-IV days (R4G's filter)",
         "R6C": "first-half-hour momentum -> last-hour 1-ITM (COPPER, ZINC)",
         "R6D": "20-day Donchian breakout (first day) -> 1-ITM, 5 sessions (COPPER, ZINC)"}


def books_of(dj) -> list:
    """The books this round's rules traded or checked, in the usual order (rounds 1-5: the six; round 6 adds the base
    metals)."""
    seen = set()
    for r in dj["rules"].values():
        seen |= set(r["summary"].get("by_book", {}))
        seen |= {k for k in (r.get("history") or {}) if k != "pooled"}
    return [b for b in tuple(K.ALL_BOOKS) + tuple(K.BASE) if b in seen or (b in K.ALL_BOOKS and not seen & set(K.BASE))]


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
    order = list(dj["rules"])
    for rid in order:
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
                 f"{s['model_share']:.0%} / {s['stale_share']:.0%} (real-exit net {rs(s.get('real_exit_net'))}) | "
                 f"{rs(s['risk_median'])} -> {rs(s['cap2_median'])} | "
                 f"{verdict(r)} |")
    L.append("\n## Gates per rule\n")
    for rid in order:
        r = dj["rules"].get(rid)
        if r is None:
            continue
        failed = [k for k, v in r.get("gates", {}).items() if not v]
        L.append(f"- {rid}: failed {', '.join(failed) if failed else 'none'}")
    BK = books_of(dj)
    L.append("\n## By book (design)\n")
    L.append("| rule | " + " | ".join(BK) + " |")
    L.append("|---|" + "---|" * len(BK))
    for rid in order:
        r = dj["rules"].get(rid)
        if r is None or not r["summary"].get("trades"):
            continue
        bb = r["summary"]["by_book"]
        cells = []
        for b in BK:
            x = bb.get(b)
            cells.append("-" if x is None else f"{x['trades']}: gross {rs(x['gross'])}, net {rs(x['net'])}, "
                                               f"win {x['win']:.0%}")
        L.append(f"| {rid} | " + " | ".join(cells) + " |")
    L.append("\n## Long-history check of the directional daily signals (futures, 2012 - Jul 2025, 0.10% cost)\n")
    L.append("| rule | " + " | ".join(BK) + " | pooled |")
    L.append("|---|" + "---|" * (len(BK) + 1))
    for rid in order:
        r = dj["rules"].get(rid)
        if r is None or not r.get("history"):
            continue
        h = r["history"]
        cells = [f"{h[b]['n']}: {h[b]['mean'] * 100:+.2f}% (twin {h[b]['twin_mean'] * 100:+.2f}%, p {h[b]['p']:.2f})"
                 if b in h and h[b]["n"] else "-" for b in BK]
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
