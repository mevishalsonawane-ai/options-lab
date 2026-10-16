"""R64 experiment entries: one YAML per arm x variant (15, failures included) from results/summary.json.

    python research/hunt/r64/r64entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r64cfg as R
from r64report import rs

ENTRIES = R.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R64_LOCK_30_60_100.md"
PREREG = "research/hunt/r64/PREREG.md"
ARM_TXT = {
    "Donchian": ("R51 Donchian 20 (5-min index Donchian-20 break, 1-ITM, 5 indices)",
                 ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], "index 5-min bars (Donchian 20)"),
    "OOV": ("R52 Open outside value (previous day's value area + first 5-min bar, 1-ITM at 09:20, 5 indices)",
            ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], "index 5-min bar + previous day's value area"),
    "Solo2": ("Solo 2.0 (R57 A2 logistic meta-labeller picks of setups S1-S4, one a day, 4 indices)",
              ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"], "index (setups S1-S4); model inputs as R57"),
}


def line(s: dict) -> str:
    if not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades, win {100 * s['win']:.0f}%, net {rs(s['net'])} ({rs(s['per_trade'])}/trade), "
            f"worst day {rs(s['worst_day'])}, max DD {rs(s['max_dd'])}, stops {s['stops']}")


def main():
    s = json.loads((R.RESULTS / "summary.json").read_text())
    n = 0
    for arm in R.ARMS:
        a = s["arms"][arm]
        title, unds, src = ARM_TXT[arm]
        for v in R.VARIANTS:
            r = a[v]
            passed = r["verdict"] == "PASS"
            if v == "B0":
                verdict, note = "paper-only", "The current paper arm re-measured (the BEFORE column); unchanged."
            elif passed:
                verdict = "inconclusive"
                note = ("Passed every gate, but not at least BEFORE's net in design, walk-forward and final (PREREG "
                        "section 5), so not recommended; Boss may still choose it.")
            else:
                verdict, note = "rejected", "Failed the gates."
            pb = r.get("paired_vs_before")
            vs = (f" Versus BEFORE on the same signals: design {rs(pb['design']['diff'])} (bootstrap p "
                  f"{pb['design']['p']}), final {rs(pb['final']['diff'])} (p {pb['final']['p']}).") if pb else ""
            e = dict(
                id=f"r64-{arm.lower()}-{v.lower()}",
                title=f"R64 {arm} {R.VLABEL[v]}",
                family="trade-management",
                date="2026-10-16",
                doc=DOC,
                hypothesis=("Boss (16 Oct): a -10% premium stop and a 30-60-100 stepped profit lock (breakeven at +30%, "
                            "+30% at +60%, +60% at +100%) improve the new paper arms after real costs. This variant: "
                            f"{R.VLABEL[v]}; gates as R51 (final net > 0, twin p < 0.05, BH q <= 0.10 over 15 tests, "
                            "40+ trades, top-5 < net, design and walk-forward > 0)."),
                data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A "
                              "clean stores (Donchian, OOV) / R57 clean stores (Solo 2.0), 20 Nov 2024 - 13/14 Oct 2026"),
                period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026; Solo 2.0 "
                                   "Jul 2025 - Mar 2026)",
                            holdout="1 Apr - 13/14 Oct 2026, read once; already read by R51 / R52 / R57 (weaker)"),
                instruments=unds,
                features=[f"signal source: {src} (unchanged); only the option exits differ"],
                model_or_rule=f"{title}; exits: {R.VLABEL[v]}",
                settings="research/hunt/r64/r64cfg.py exit_spec / OWN; PREREG.md section 2",
                prereg=PREREG,
                variants_tried=15,
                results=dict(
                    summary=(f"Design: {line(r['design'])}. Walk-forward: {line(r['wf'])}. Final: {line(r['final'])}; "
                             f"twin p {r['p']}, BH q {r['q']}. Verdict: {r['verdict']}.{vs}"),
                    costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap fills at "
                          "the worse open, one held contract, 1 lot in force"),
                holdout_read=True,
                verdict=verdict,
                notes=note + " Signal on the underlying; nothing changes live.",
            )
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
            n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
