"""Writes research/experiments/entries/r63-<rule>-<strategy>.yaml: one per gated cell (4 rules x 7), failures included.

    python research/hunt/r63/entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r63cfg as K  # noqa: E402
import r63rules as RR  # noqa: E402
from r63report import inr  # noqa: E402

ENT = K.ROOT / "research" / "experiments" / "entries"


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    n = 0
    for r in RR.RULES:
        for s in K.STRATS + (K.POOLED,):
            c, dc = f["cells"][r][s], d["cells"][r][s]
            g = c["gate"]
            summ = (f"{RR.NAMES[r]} (signal: the underlying) added to {K.NAMES[s]}'s own exits. Design: {dc['trades']} trades, "
                    f"{dc['changed']} changed, net change {inr(dc['improvement'])} (saved on losers {inr(dc['saved_on_losers'])}, "
                    f"given up on winners {inr(dc['given_up_on_winners'])}), walk-forward {inr(dc['wf_net'])}, twin p {dc['p']:.3f}. "
                    f"Final (read once): {c['trades']} trades on {c['sessions']} sessions, {c['changed']} changed, net change "
                    f"{inr(c['improvement'])} (saved {inr(c['saved_on_losers'])}, given up {inr(c['given_up_on_winners'])}; without "
                    f"the top 5 {inr(c['impr_ex_top5'])}), plain {inr(c['plain_net'])} -> {inr(c['rule_net'])}, max DD "
                    f"{inr(-c['plain_max_dd'])} -> {inr(-c['rule_max_dd'])}, twin p {c['p']:.3f} (twin mean {inr(c['twin_mean'])}), "
                    f"BH q {g['q']:.3f}. Verdict: " + ("PASS" if g["passed"] else "FAIL: " + "; ".join(g["fails"])) + ".")
            ent = dict(
                id=f"r63-{r.lower()}-{s.lower()}",
                title=f"R63 {r} on {K.NAMES[s]}: sell when the underlying's trend turns against the trade",
                family="trade-management",
                date="2026-10-16",
                doc="research/HUNT_R63_TREND_TURN_EXIT.md",
                hypothesis=("Boss, 16 Oct: if the trend turns, sell. Added to the strategy's own exits, a trend-turn exit on the "
                            "underlying makes more after costs than the plain rules and than random early exits of the same trades "
                            "(gates: final net change > 0, twin p < 0.05, BH q <= 0.10 over 28 cells, >= 40 changed trades, "
                            "not from the top 5, design and walk-forward > 0)."),
                data_version=("Drive lake gdrive:IraAlgo-data-lake fetched 16 Oct 2026: index_1m and options_rolling_1m WEEK "
                              "2024-2026; strategies priced on their own studies' stores (R51 Amendment A clean, R57, R54)"),
                period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                            holdout=("final test 1 Apr - 14 Oct 2026, read once 16 Oct 2026 (not pristine: R51, R52, R54, R55, "
                                     "R57 and R61 read it, and these strategies were chosen on it)")),
                instruments=list(K.UNDS_OF.get(s, K.ALL_UNDS)),
                features=[f"signal source: the INDEX 1-minute spot bars ({RR.TF[r]}-minute bars); no option-chain or option-price "
                          "input; the strategy's own entries held fixed"],
                model_or_rule=(f"{RR.NAMES[r]}; acts only on a completed bar >= 5 minutes after the fill; sells the held contract "
                               "at the next minute's open (R51 engine forced exit; Hero: R54's sell)"),
                settings="research/hunt/r63/PREREG.md sections 2-7; r63rules.py",
                prereg="research/hunt/r63/PREREG.md",
                variants_tried=28,
                results=dict(summary=summ, costs=("R51 engine: R17 'liq' fills, Costs('dated') charges, lot in force; Hero: "
                                                  "R54's limit fill, late-minute spread and R50's dated charges")),
                holdout_read=True,
                verdict="paper-only" if g["passed"] else "rejected",
                notes=("One of 28 R63 cells in one BH family (research/hunt/r63/results/report.md). "
                       + ("Paper only (Hero ticket is paper only, forever); live never acts." if g["passed"]
                          else "Shadow notes and alerts only in the app; never acts.")),
            )
            (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
            n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
