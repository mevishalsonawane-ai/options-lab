"""R110 experiment-log entries (research/experiments/entries/r110-*.yaml).

    python research/hunt/r110_strangle/r110entries.py
"""
from __future__ import annotations

import json

import yaml

import r110study as S

ENTRIES = S.HERE.parents[1] / "experiments" / "entries"
IDEA = {"W1": "cap the tail with wings 500 beyond the shorts", "W2": "a cheaper, wider cap",
        "X1": "sell further out", "X1W": "sell further out and cap", "S1": "a premium stop at 1x the credit",
        "S2": "an index stop at the breakevens", "F1": "sell only when options are dear (IV/RV)",
        "F2": "skip fresh turbulence (IraThinker's aftershock rule)"}


def main():
    D = json.loads((S.RESULTS / "design.json").read_text())
    F = json.loads((S.RESULTS / "final.json").read_text())
    C = json.loads((S.RESULTS / "decision.json").read_text())
    for n in S.TESTS:
        parts = []
        for lab, src, p in (("History", D, "history"), ("Design", D, "design"), ("Walk-forward", D, "wf"),
                            ("Final", F, "final"), ("Post-13 Oct", F, "post")):
            s = src[n]["periods"].get(p) or {}
            if not s.get("n"):
                parts.append(f"{lab}: 0 weeks")
                continue
            parts.append(f"{lab}: {s['n']} weeks, win {s['win'] * 100:.0f}%, net {S.rs(s['net'])} (B0 same weeks "
                         f"{S.rs(s.get('b0_same_weeks_net'))}), worst month {S.rs(s['worst_month'])}, max DD "
                         f"{S.rs(s['max_dd'])}")
        r = C["tests"][n]
        e = dict(
            id=f"r110-{n.lower()}", title=f"R110 {S.LABEL[n]} on IraThinker's BANKNIFTY Monday strangle",
            family="option-structure", date="2026-10-26", doc="research/HUNT_R110_STRANGLE.md",
            hypothesis=(f"{IDEA[n]} keeps the R109 strangle profitable and inside Boss's account rule at 1 lot. Gates: "
                        "net > 0 and worst month >= -Rs 13,000 and drawdown < Rs 40,000 in design, walk-forward and "
                        "final; design bootstrap p < 0.05 (filters also a random-skip twin); BH q <= 0.10 over "
                        f"{C['m']:,} tests; >= 40 design / 15 final weeks."),
            data_version="Drive lake gdrive:IraAlgo-data-lake copied 26 Oct 2026; R109's R51 Amendment A clean MONTH "
                         "stores",
            period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-13 read once by R110 (B0 already read by R109); 14-19 Oct "
                                "descriptive"),
            instruments=["BANKNIFTY"],
            features=["signal source: none directional; strikes off the index spot at 09:30"
                      + ("; stop on the index (underlying)" if n == "S2" else "")
                      + ("; stop on the position's premium" if n == "S1" else "")
                      + ("; ATM implied vol and index realised vol as a filter" if n == "F1" else "")
                      + ("; index daily returns as a filter" if n == "F2" else "")],
            model_or_rule=f"R109 strangle-bn with {S.LABEL[n]}",
            settings="research/hunt/r110_strangle/r110study.py TESTS; PREREG.md section 2",
            prereg="research/hunt/r110_strangle/PREREG.md", variants_tried=1,
            results=dict(summary=". ".join(parts) + f". BH q {r['q']:.3g}. "
                         + ("Passes every gate." if r["verdict"] == "PASS"
                            else f"Fails: {'; '.join(r['fails'][:3])}."),
                         costs="dated Zerodha charges, liq slippage, next-minute fills, stale legs repriced by BS at "
                               "the last implied vol, exercise STT on long legs settled in the money"),
            holdout_read=True, verdict="paper-only" if r["verdict"] == "PASS" else "rejected",
            notes="Research only; the app does not sell options; nothing changes on paper or live.")
        (ENTRIES / f"r110-{n.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True,
                                                                         width=1000))
        print("wrote", n, r["verdict"])


if __name__ == "__main__":
    main()
