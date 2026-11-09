"""R154 experiment entries: one YAML per entry x index x reading (its 12 exit rules), failures included.

    python research/hunt/r154_scalp_10_15/r154entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r154cfg as K
from r154report import pct, rs

ENTRIES_DIR = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r154_scalp_10_15/results/report.md"
PREREG = "research/hunt/r154_scalp_10_15/PREREG.md"
FAMILY = {"E1": "orb", "E2": "patterns-indicators", "E3": "patterns-indicators", "E4": "patterns-indicators",
          "R72": "trade-management", "R51": "trade-management", "LIQ": "trade-management"}


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    n = 0
    for e in K.ENTRIES:
        for u in K.ENTRY_UNDS[e]:
            for rd in ("A", "B"):
                rules = [r for r in K.RULE_IDS if K.RULES[r]["reading"] == rd]
                parts, passed = [], []
                for r in rules:
                    p1, p2, p3 = d[e][u][f"{r}@1"]["P1"], d[e][u][f"{r}@1"]["P2"], f[e][u][f"{r}@1"]["P3"]
                    g = dec[f"{e}:{u}:{r}"]
                    if g["passed"]:
                        passed.append(r)
                    parts.append(f"{r}: P1 {p1['trades']} tr {rs(p1['net'])}; P2 {p2['trades']} tr {rs(p2['net'])}; P3 "
                                 f"{p3['trades']} tr, win {pct(p3['win'])} (break-even {pct(p3['be_win'])}), "
                                 f"{rs(p3['net'])}; twin p {g['twin_p']}, q {g['q']}")
                b0 = ""
                if e in K.ARMS:
                    b0 = (f" Today's exits (B0): P2 {rs(d[e][u]['B0@1']['P2']['net'])}, P3 "
                          f"{rs(f[e][u]['B0@1']['P3']['net'])}.")
                entry = dict(
                    id=f"r154-{e.lower()}-{u.lower()}-{rd.lower()}",
                    title=f"R154 {K.ENTRY_NAME[e]} on {u}, 10-15 point scalp exits, reading {rd} ({K.READING[rd]})",
                    family=FAMILY[e],
                    date="2026-11-09",
                    doc=DOC,
                    hypothesis=("Boss (9 Nov): \"study 10-15 pts scalping ... find out the winrate and profit/loss\". "
                                f"{K.ENTRY_NAME[e]} on {u} with a fixed scalp exit: target 10 / 12 / 15 points on the "
                                f"{'option premium' if rd == 'A' else 'index'}, stop 1:1 or 1:1.5, time stop 15 or 30 "
                                "minutes, 15:15 square-off (15:05 expiry). Gates: net > 0 in P1, P2 and P3; random-exit "
                                f"twin BH q <= 0.10 with m = {dec['_m']['total']}; >= 40 final trades"
                                + ("; above the arm's own exits in P2 and P3." if e in K.ARMS else ".")),
                    data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) "
                                  "copied 9 Nov 2026; R51 Amendment A clean stores from 20 Nov 2024 (post: R106's to 19 Oct), "
                                  "R154 early store (same code) Aug 2021 - 19 Nov 2024"),
                    period=dict(design=("P1 " + ("2024-11-20" if e in K.ARMS else "first option day (2021-08 / MIDCPNIFTY "
                                                                                  "2023-01 / SENSEX 2023-05)")
                                        + "..2025-03-31; P2 walk-forward 2025-04-01..2026-03-31"),
                                holdout="2026-04-01..2026-10-13 read once (already read by R51-R153 and SCALP17); "
                                        "post 14-19 Oct reported"),
                    instruments=[u],
                    features=[f"signal source: {K.SIGNAL_SRC[e]}; buys the 1-ITM nearest-expiry option",
                              f"exit: {K.READING[rd]}"],
                    model_or_rule=f"{K.ENTRY_NAME[e]} on {u}; exits {', '.join(rules)}",
                    settings="research/hunt/r154_scalp_10_15/r154cfg.py RULES, ENTRIES; PREREG.md sections 2-3",
                    prereg=PREREG,
                    variants_tried=len(rules),
                    results=dict(
                        summary=("1 lot. " + " | ".join(parts) + "." + b0
                                 + (f" PASS: {', '.join(passed)}." if passed else " Every rule fails the gates.")),
                        costs=("dated Zerodha charges, R17 fills (buy +5 bps + half-spread ticks, stop sales -10 bps and "
                               "2x the ticks, gaps at the open), limit targets at their price, 1 lot (2-3 lots in the report)")),
                    holdout_read=True,
                    verdict="paper-only" if passed else "rejected",
                    notes="Signal on the underlying; nothing changes live.",
                )
                (ENTRIES_DIR / f"{entry['id']}.yaml").write_text(
                    yaml.safe_dump(entry, sort_keys=False, allow_unicode=True, width=120))
                n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
