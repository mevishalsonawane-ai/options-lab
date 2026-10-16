"""R65 experiment entries: one YAML per arm x variant (30, failures included) from results/summary.json.

    python research/hunt/r65/r65entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys

import yaml

import r65cfg as R
from r65report import rs

sys.path.insert(0, str(R.HERE.parent / "r64"))
from r64entries import ARM_TXT, line  # noqa: E402

ENTRIES = R.R64.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R65_STEP_TRAIL.md"
PREREG = "research/hunt/r65/PREREG.md"


def main():
    s = json.loads((R.RESULTS / "summary.json").read_text())
    n = 0
    for arm in R.ARMS:
        a = s["arms"][arm]
        title, unds, src = ARM_TXT[arm]
        for v in R.VARIANTS:
            r = a[v]
            if v == "B0":
                verdict, note = "superseded", "Baseline 1 (research rules) re-measured; the paper arm runs A4 since 16 Oct."
            elif v == "A4":
                verdict, note = "paper-only", "Baseline 2: R64 A4, what the paper arm runs today; unchanged by R65."
            elif r["verdict"] == "PASS":
                verdict = "inconclusive"
                note = ("Passed R51's gates, but not recommended (PREREG section 6): it does not beat both B0 and A4 in "
                        f"design, walk-forward and final, and its exit-twin q is {r.get('exit_q')} (> 0.10: no better "
                        "than the same number of random early exits). Boss may still choose it.")
            else:
                verdict, note = "rejected", "Failed the gates."
            pb = r.get("paired", {})
            vs = "".join(f" Versus {b[3:]}: design {rs(x['design']['diff'])} (bootstrap p {x['design']['p']}), final "
                         f"{rs(x['final']['diff'])} (p {x['final']['p']})." for b, x in pb.items())
            tr = ""
            if "trail" in r:
                t = r["trail"]["final"]
                tr = (f" Final: the lock closed {t['locked']} trades; versus the plain rules it gave up "
                      f"{rs(t['given_up'])} and saved {rs(t['saved'])} (net {rs(t['net_effect'])}); {t['went_on30']} "
                      "of them went on to +30% after the exit.")
            ex = f", exit-twin p {r['exit_p']} (q {r.get('exit_q')})" if r.get("exit_p") is not None else ""
            e = dict(
                id=f"r65-{arm.lower()}-{v.lower()}",
                title=f"R65 {arm} {R.VLABEL[v]}",
                family="trade-management",
                date="2026-10-16",
                doc=DOC,
                hypothesis=("Boss (16 Oct): once the premium is +10%, put the stop at +5%, then +10% at +15%, and so on "
                            "(a 5-point step trail), and a -5% stop moved up the same way, improve the new paper arms "
                            f"after real costs. This variant: {R.VLABEL[v]}; gates as R51 (final net > 0, twin p < 0.05, "
                            "BH q <= 0.10 over 30 tests, 40+ trades, top-5 < net, design and walk-forward > 0); "
                            "recommended only if it also beats B0 and A4 everywhere and random early exits (q <= 0.10)."),
                data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A "
                              "clean stores (Donchian, OOV) / R57 stores (Solo 2.0), 20 Nov 2024 - 13/14 Oct 2026"),
                period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026; Solo 2.0 "
                                   "Jul 2025 - Mar 2026)",
                            holdout="1 Apr - 13/14 Oct 2026, read once; already read by R51 / R52 / R57 / R64 (weak)"),
                instruments=unds,
                features=[f"signal source: {src} (unchanged); only the option exits differ (stops and trail on the "
                          "premium)"],
                model_or_rule=f"{title}; exits: {R.VLABEL[v]}",
                settings="research/hunt/r65/r65cfg.py exit_spec / trail_steps; PREREG.md sections 2-3",
                prereg=PREREG,
                variants_tried=30,
                results=dict(
                    summary=(f"Design: {line(r['design'])}. Walk-forward: {line(r['wf'])}. Final: {line(r['final'])}; "
                             f"entry-twin p {r['p']}, BH q {r['q']}{ex}. Verdict: {r['verdict']}.{vs}{tr}"),
                    costs="dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap fills at "
                          "the worse open, locks from the next minute, one held contract, 1 lot in force"),
                holdout_read=True,
                verdict=verdict,
                notes=note + " Signal on the underlying; research only, nothing changes paper or live.",
            )
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
            n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
