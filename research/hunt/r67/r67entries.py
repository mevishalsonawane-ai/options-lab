"""R67 experiment entries: one YAML per arm x variant (15, failures included) from results/summary.json.

    python research/hunt/r67/r67entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys

import yaml

import r67cfg as R

sys.path.insert(0, str(R.HERE.parent / "r64"))
from r64entries import ARM_TXT, line  # noqa: E402
from r65report import rs  # noqa: E402

ENTRIES = R.R64.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R67_STOP_TO_10.md"
PREREG = "research/hunt/r67/PREREG.md"


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
                verdict, note = "paper-only", "Baseline 2: R64 A4, what the paper arm runs today; unchanged by R67."
            elif r["verdict"] == "PASS":
                verdict = "inconclusive"
                note = ("Passed R51's gates but not recommended (PREREG section 3): it must also beat both B0 and A4 in "
                        f"design, walk-forward and final and beat random early exits; exit-twin q {r.get('exit_q')}. "
                        "Boss may still choose it.")
            else:
                verdict, note = "rejected", "Failed the gates."
            pb = r.get("paired", {})
            vs = "".join(f" Versus {b[3:]}: design {rs(x['design']['diff'])} (bootstrap p {x['design']['p']}), final "
                         f"{rs(x['final']['diff'])} (p {x['final']['p']})." for b, x in pb.items())
            tr = ""
            if v in R.TRAILS:
                t = r["trail"]["final"]
                tr = (f" Final: versus A4 the -10% step changed {t['n_worse'] + t['n_better']} trades; it gave up "
                      f"{rs(t['given_up'])} and saved {rs(t['saved'])} (net {rs(t['net_effect'])}).")
            ex = f", exit-twin p {r['exit_p']} (q {r.get('exit_q')})" if r.get("exit_p") is not None else ""
            e = dict(
                id=f"r67-{arm.lower()}-{v.lower()}",
                title=f"R67 {arm} {R.VLABEL[v]}",
                family="trade-management",
                date="2026-10-16",
                doc=DOC,
                hypothesis=("Boss (16 Oct): once the option is +25%, moving the stop from -15% to -10% (on top of the A4 "
                            "30/60/100 ladder) improves the paper arms after real costs, because trades that reach +25% "
                            f"and turn back lose less. This variant: {R.VLABEL[v]}; gates as R51 (BH over 15 tests); "
                            "recommended only if it also beats B0 and A4 everywhere and random early exits (q <= 0.10)."),
                data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A "
                              "clean stores (Donchian, OOV) / R57 stores (Solo 2.0), 20 Nov 2024 - 13/14 Oct 2026"),
                period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026; Solo 2.0 "
                                   "Jul 2025 - Mar 2026)",
                            holdout="1 Apr - 13/14 Oct 2026, read once; already read by R51 / R52 / R57 / R64 - R66 (weak)"),
                instruments=unds,
                features=[f"signal source: {src} (unchanged); only the option exits differ (stops and locks on the "
                          "premium)"],
                model_or_rule=f"{title}; exits: {R.VLABEL[v]}",
                settings="research/hunt/r67/r67cfg.py exit_spec / TRIGGER; PREREG.md",
                prereg=PREREG,
                variants_tried=15,
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
