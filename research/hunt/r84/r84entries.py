"""R84: results/design.json (+ day19.json) -> research/experiments/entries/r84-*.yaml (one per variant, failures too).

    python research/hunt/r84/r84entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r75"))
import r84rules as P  # noqa: E402
from r75report import pct, rs  # noqa: E402

ENTRIES = HERE.parents[1] / "experiments" / "entries"


def line(st: dict) -> str:
    return (f"{st.get('trades', 0)} trades, net {rs(st.get('net'))}, win {pct(st.get('win'))}, worst day "
            f"{rs(st.get('worst_day'))}, worst signal {rs(st.get('worst_signal'))}, max DD {rs(st.get('max_dd'))}, "
            f"green days {pct(st.get('green_days'))}, months < -13k {st.get('months_13k', 0)}, DD > 40k episodes "
            f"{st.get('dd_40k', 0)}, net without top 5 {rs(st.get('ex_top5'))}")


def write():
    des = json.loads((HERE / "results" / "design.json").read_text())
    d19 = json.loads((HERE / "results" / "day19.json").read_text())
    k0 = des["k0"]
    for v in P.ALL:
        r = des["variants"][v]
        c = r["vs_k0"]
        post = v in P.A1
        e = dict(
            id=f"r84-{v.lower()}",
            title=f"R84 {v}: Breadth Donchian, {P.NAMES[v]}" + (" (post hoc, amendment A1)" if post else ""),
            family="sizing-execution",
            date="2026-10-19",
            doc="research/HUNT_R84_BREADTH_RISK.md",
            hypothesis=("Boss (19 Oct, after -Rs 9,721 on one Breadth Donchian signal): " + P.NAMES[v] + " cuts R72 "
                        "Breadth Donchian's design worst day and max drawdown by >= 30% while keeping >= 70% of its net "
                        "in design and walk-forward, beats a random-subset twin (p < 0.10, BH q < 0.10) and is not "
                        "carried by its top 5 trades."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK, files of 15 Oct), "
                          "R51 Amendment A clean stores; 19 Oct index side from Yahoo 1-minute bars"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout=("1 Apr - 13 Oct 2026 NOT read: no variant passed design + walk-forward (PREREG "
                                 "section 7); the window was already read by R72, R73, R75")),
            instruments=list(P.ORDER),
            features=["signal source: the indices' own 5-minute bars (R72 C1, 4 of 5); the variant reads break size in "
                      "the index's 5-minute ATR, NIFTY's 10-minute move, or (RB) the option's last price as a size "
                      "input only; no option chart"],
            model_or_rule=f"R75's C (R72 C1 Breadth Donchian, 1-ITM, exits P) + {v}: {P.NAMES[v]}",
            settings="research/hunt/r84/r84rules.py, research/hunt/r84/r84study.py; PREREG.md",
            prereg="research/hunt/r84/PREREG.md",
            variants_tried=1,
            results=dict(
                summary=(f"Design: {line(r['design'])}; K0 design: {line(k0['design'])}. Walk-forward pooled net "
                         f"{rs(r['wf_net'])} vs K0 {rs(k0['wf_net'])}; beat K0 on net and worst day in "
                         f"{r['quarters_beat']} of 4 quarters. Dropped {c['skipped']} K0 trades ({c['losers_avoided']} "
                         f"losers, {rs(c['losers_avoided_rs'])}; {c['winners_missed']} winners, "
                         f"{rs(c['winners_missed_rs'])}), took {c['extra']} later entries K0 did not "
                         f"({rs(c['extra_net'])}). Random-subset twin mean {rs(r['twin_mean'])}, p {r['p']}, BH q "
                         f"{r['q']} over 8" + (f" (q {r['q7']} over the 7 pre-registered)" if r.get('q7') is not None
                                               else "") + f". 19 Oct (app's paper losses of the positions kept): "
                         f"{rs(d19['costs'][v]['cost'])} vs K0 {rs(d19['costs']['K0']['cost'])}. Verdict: "
                         f"{r['verdict']}" + (" (" + "; ".join(r["fails"]) + ")" if r["fails"] else "") + "."),
                costs=("R75's: dated charges, R17 liquidity fills, next-minute fills, stops on the minute low with "
                       "gap fills at the worse open, one held contract, 1 lot in force")),
            holdout_read=False,
            verdict="paper-only" if r["verdict"] == "PASS" else "rejected",
            notes=("Signal on the underlying (the indices). Risk shaping, not signal tuning. Research only; nothing "
                   "changes paper or live." + (" Post hoc: PREREG amendment A1, added after the design results." if post else "")),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    write()
