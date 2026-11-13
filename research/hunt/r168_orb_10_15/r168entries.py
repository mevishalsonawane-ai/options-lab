"""R168 experiment entries: one YAML per arm x index x reading (its 12 exit rules), failures included.

    python research/hunt/r168_orb_10_15/r168entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r168cfg as K
from r154report import pct, rs

ENTRIES_DIR = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r168_orb_10_15/results/report.md"
PREREG = "research/hunt/r168_orb_10_15/PREREG.md"


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    n = 0
    for a in K.ARMS:
        for u in K.UNDS:
            b0 = (f"Own exits (B0): P1 {d[a][u]['B0@1']['P1']['trades']} tr {rs(d[a][u]['B0@1']['P1']['net'])}; P2 "
                  f"{d[a][u]['B0@1']['P2']['trades']} tr {rs(d[a][u]['B0@1']['P2']['net'])}; P3 "
                  f"{f[a][u]['B0@1']['P3']['trades']} tr {rs(f[a][u]['B0@1']['P3']['net'])}.")
            for rd in ("A", "B"):
                rules = [r for r in K.RULE_IDS if K.RULES[r]["reading"] == rd]
                parts, passed = [], []
                for r in rules:
                    p1, p2, p3 = d[a][u][f"{r}@1"]["P1"], d[a][u][f"{r}@1"]["P2"], f[a][u][f"{r}@1"]["P3"]
                    g = dec[f"{a}:{u}:{r}"]
                    if g["passed"]:
                        passed.append(r)
                    parts.append(f"{r}: P1 {p1['trades']} tr {rs(p1['net'])}; P2 {p2['trades']} tr {rs(p2['net'])}; P3 "
                                 f"{p3['trades']} tr, win {pct(p3['win'])} (break-even {pct(p3['be_win'])}), "
                                 f"{rs(p3['net'])}; twin p {g['twin_p']}, q {g['q']}")
                app = "the app's arm" if u == K.APP_UND else "same rules, not an app arm"
                entry = dict(
                    id=f"r168-{a.lower()}-{u.lower()}-{rd.lower()}",
                    title=(f"R168 {K.ARM_NAME[a]} (the app's entry rules) on {u} ({app}), 10-15 point exits, reading {rd} "
                           f"({K.READING[rd]})"),
                    family="orb",
                    date="2026-11-13",
                    doc=DOC,
                    hypothesis=("Boss (13 Nov): \"Try ORB with 10-15 points only.\" The app's own "
                                f"{K.ARM_NAME[a]} entries (09:15-10:00 opening range, its window, caps and cool-down) on {u} "
                                f"with only the exits replaced: target 10 / 12 / 15 points on the "
                                f"{'option premium' if rd == 'A' else 'index'}, stop 1:1 or 1:1.5, time stop 15 or 30 "
                                "minutes, 15:15 square-off (15:05 expiry). Gates: net > 0 in P1, P2 and P3; random-exit twin "
                                f"BH q <= 0.10 with m = {dec['_m']['total']}; >= 40 final trades; above the arm's own exits "
                                "in P2 and P3."),
                    data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) "
                                  "copied 13 Nov 2026; R51 Amendment A clean stores from 20 Nov 2024, R154 early store "
                                  "(same code) Aug 2021 - 19 Nov 2024"),
                    period=dict(design=("P1 first option day (2021-08 / MIDCPNIFTY 2023-01 / SENSEX 2023-05)..2025-03-31; "
                                        "P2 walk-forward 2025-04-01..2026-03-31"),
                                holdout=("2026-04-01..2026-10-13 read once (already read by R14 / H20 for the ORB arms, "
                                         "R51-R167 and SCALP17: weak evidence)")),
                    instruments=[u],
                    features=[f"signal source: {K.ARM_SIGNAL[a]}; buys the ATM option (strike from the 09:20 bar), "
                              "same-day expiry on an expiry day",
                              f"exit: {K.READING[rd]}"],
                    model_or_rule=f"{K.ARM_NAME[a]} on {u}; exits {', '.join(rules)}",
                    settings="research/hunt/r168_orb_10_15/r168cfg.py ARM_RULES, RULES; PREREG.md sections 1-3",
                    prereg=PREREG,
                    variants_tried=len(rules),
                    results=dict(
                        summary=("1 lot. " + " | ".join(parts) + ". " + b0
                                 + (f" PASS: {', '.join(passed)}." if passed else " Every rule fails the gates.")),
                        costs=("dated Zerodha charges, R17 fills (buy +5 bps + half-spread ticks, stop sales -10 bps and "
                               "2x the ticks, gaps at the open), limit targets at their price, 1 lot (2-3 lots in the report)")),
                    holdout_read=True,
                    verdict="paper-only" if passed else "rejected",
                    notes="Signal on the underlying; nothing changes in the app (the ORB family is parked since 13 Oct).",
                )
                (ENTRIES_DIR / f"{entry['id']}.yaml").write_text(
                    yaml.safe_dump(entry, sort_keys=False, allow_unicode=True, width=120))
                n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
