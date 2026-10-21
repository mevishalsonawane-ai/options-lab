"""F1C report: results/design.json (+ holdout.json once read) -> results/report.md (every table of the write-up).

    python -m research.hunt.f1c.report
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from . import f1ccfg as C
from .rules import RULES


def rs(x) -> str:
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return "-"
    x = float(x)
    s = f"{abs(x):,.0f}"
    return ("-" if x < 0 else "+") + "Rs " + s if abs(x) >= 0.5 else "Rs 0"


def pct(x, d=0) -> str:
    return "-" if x is None or not np.isfinite(x) else f"{100 * x:.{d}f}%"


def num(x, d=2) -> str:
    return "-" if x is None or not np.isfinite(float(x)) else f"{float(x):.{d}f}"


def load():
    D = json.loads((C.RESULTS / "design.json").read_text())
    H = json.loads((C.RESULTS / "holdout.json").read_text()) if (C.RESULTS / "holdout.json").exists() else None
    V = pd.DataFrame(D["variants"])
    return D, H, V


def picks_table(D, H, V) -> list[str]:
    head = ("| rule | pick | trades (a day) | win | avg win / loss | move caught, bps a trade | net a day after tax A | "
            "green days | worst day | max DD | lock exits (cut short) | design net A | walk-forward | stress | twin p | BH q | holdout trades | "
            "holdout net A | passes |")
    out = [head, "|" + "---|" * head.count("|")[:-1] if False else "|" + "|".join(["---"] * (head.count("|") - 1)) + "|"]
    for rid in RULES:
        p = D["picks"][rid]
        v = V[(V.rule == rid) & (V.exit_v == p["pick"])].iloc[0]
        h = H["rules"][rid]["pick_result"] if H else {}
        ok = (H["rules"][rid]["passes"] if H else p["design_pass"])
        out.append(f"| {rid} | {p['pick']} | {int(v.trades):,} ({v.trades_per_day:.2f}) | {pct(v.win_rate)} | "
                   f"{rs(v.avg_win)} / {rs(v.avg_loss)} | {num(v.edge_bps, 1)} | {rs(v.per_day_a)} | {pct(v.green_days)} | {rs(v.worst_day)} | "
                   f"{rs(-abs(v.max_dd))} | {int(v.lock_exits):,} ({int(v.cut_short):,}) | {rs(v.net_a)} | {rs(p['wf_sum'])} "
                   f"({pct(p['wf_green'])} green q) | {rs(v.stress_net_a)} | {num(v.twin_p, 3)} | {num(v.twin_q, 3)} | "
                   f"{h.get('trades', 0):,} | {rs(h.get('net_a'))} | {'**yes**' if ok else 'no'} |")
    return out


def variants_table(D, H, V) -> list[str]:
    out = ["| rule | exit | trades | win | net pre-tax | tax A | net A | net B (business) | stress net A | twin p | "
           "BH q | holdout trades | holdout net A |", "|" + "|".join(["---"] * 13) + "|"]
    for r in V.itertuples():
        h = H["rules"][r.rule]["variants"].get(r.exit_v, {}) if H else {}
        out.append(f"| {r.rule} | {r.exit_v} | {int(r.trades):,} | {pct(r.win_rate)} | {rs(r.net_pre_tax)} | "
                   f"{rs(-r.tax_a)} | {rs(r.net_a)} | {rs(r.net_b)} | {rs(r.stress_net_a)} | {num(r.twin_p, 3)} | "
                   f"{num(r.twin_q, 3)} | {h.get('trades', 0):,} | {rs(h.get('net_a'))} |")
    return out


def money_table(V, D) -> list[str]:
    out = ["| rule (pick) | gross on fills | fees + GST | funding | net pre-tax | tax A (31.2% of winners) | net A | "
           "TDS peak locked | TDS passes half the account |", "|" + "|".join(["---"] * 9) + "|"]
    for rid in RULES:
        v = V[(V.rule == rid) & (V.exit_v == D["picks"][rid]["pick"])].iloc[0]
        t = v.tds if isinstance(v.tds, dict) else {}
        out.append(f"| {rid} ({v.exit_v}) | {rs(v.gross)} | {rs(-v.fees)} | {rs(-v.funding)} | {rs(v.net_pre_tax)} | "
                   f"{rs(-v.tax_a)} | {rs(v.net_a)} | {rs(t.get('peak')).lstrip('+')} | {t.get('first_half_day') or 'never'} |")
    return out


def wf_table(D) -> list[str]:
    qs = C.QUARTERS[1:]
    out = ["| rule | " + " | ".join(qs) + " | sum |", "|" + "|".join(["---"] * (len(qs) + 2)) + "|"]
    for rid in RULES:
        p = D["picks"][rid]
        out.append(f"| {rid} | " + " | ".join(f"{rs(p['wf'][q])} ({p['wf_chosen'][q]})" for q in qs) +
                   f" | {rs(p['wf_sum'])} |")
    return out


def coin_table(V, D, H) -> list[str]:
    out = ["| rule (pick) | " + " | ".join(c.replace("USDT", "") for c in C.COINS) + " |",
           "|" + "|".join(["---"] * (len(C.COINS) + 1)) + "|"]
    for rid in RULES:
        v = V[(V.rule == rid) & (V.exit_v == D["picks"][rid]["pick"])].iloc[0]
        bc = v.by_coin
        cells = [f"{bc[c]['trades']:,}: {rs(bc[c]['net_a'])}" if c in bc else "" for c in C.COINS]
        out.append(f"| {rid} ({v.exit_v}) | " + " | ".join(cells) + " |")
    return out


def baseline_table(D, H) -> list[str]:
    out = ["| coin | period | days | buy-and-hold after tax A | weekly DCA after tax A |", "|---|---|---|---|---|"]
    for part, src in (("design", D), ("holdout", H)):
        if not src:
            continue
        for sym, spans in src["baselines"].items():
            for s in spans:
                out.append(f"| {sym.replace('USDT', '')} | {part} {s['span']} | {s['days']} | {rs(s['hold_after_tax'])} "
                           f"| {rs(s['dca_after_tax'])} |")
    return out


def main():
    D, H, V = load()
    nb = int(V.twin_p.notna().sum())
    lines = ["# F1C results (generated by `python -m research.hunt.f1c.report`; do not edit)", "",
             f"Design days: {D['days']}. BH family: {nb} rule x exit tests. Rupees on a Rs 1,00,000 account, fixed "
             "size; net A = after Delta India fees + GST, slippage, funding and tax reading A.", "",
             "## The pick of each rule (best design net A of its four exits)", ""] + picks_table(D, H, V) + [
             "", "## Where the money goes (design, picks)", ""] + money_table(V, D) + [
             "", "## Walk-forward by quarter (each quarter uses the exit with the best earlier record)", ""] + wf_table(D) + [
             "", "## By coin (design, picks: trades: net A)", ""] + coin_table(V, D, H) + [
             "", "## Baselines (Rs 1,00,000 put in at the start of each span)", ""] + baseline_table(D, H) + [
             "", "## Every rule x exit", ""] + variants_table(D, H, V) + [""]
    (C.RESULTS / "report.md").write_text("\n".join(lines))
    print(f"wrote {C.RESULTS / 'report.md'}")


if __name__ == "__main__":
    main()
