"""R105: results/final.json -> research/experiments/entries/r105-*.yaml (one per test, failures included).

    python research/hunt/r105_donchian_giveback/r105entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r105lib as L  # noqa: E402


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(float(x))
    s = f"{abs(x):,}"
    return ("-Rs " if x < 0 else "Rs ") + s


def line(st: dict) -> str:
    return (f"{st.get('trades', 0)} trades, net {rs(st.get('net'))}, win {round(100 * (st.get('win') or 0))}%, worst "
            f"day {rs(st.get('worst_day'))}, max DD {rs(st.get('max_dd'))}")


def write():
    fin = json.loads((L.RESULTS / "final.json").read_text())
    for t in L.TESTS:
        arm, rule = t.split(":")
        r = fin["tests"][t]
        g = r["gate"]
        e = dict(
            id=f"r105-{arm.lower()}-{rule.lower()}",
            title=f"R105 {rule} {L.NAMES[rule]} on {L.ARM_NAMES[arm]} (on top of the paper exits)",
            family="trade-management",
            date="2026-10-26",
            doc="research/HUNT_R105_DONCHIAN_GIVEBACK.md",
            hypothesis=("Boss (26 Oct: \"it goes to 1,000 to 2,000 profit and then we end up losing\"): "
                        f"{L.NAMES[rule]} recovers the giveback of {L.ARM_NAMES[arm]} without cutting its profit: "
                        "better net in design, walk-forward (>= 3 of 4 quarters) and final, worst day / max DD not "
                        "worse (2%), beats 500 random-minute exit twins (p < 0.05 in design and final), BH q <= 0.10 "
                        "over the 20 new tests plus every earlier Donchian test, >= 30 changed design trades, design "
                        "gain >= 0 without the 5 best, still better at 2x slippage."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A "
                          "clean stores, 20 Nov 2024 - 13 Oct 2026 (OOS 14-19 Oct reported apart)"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout=("1 Apr - 13 Oct 2026, read once for R105 (26 Oct); already read by R51-R75, R101, "
                                 "R104, so weak evidence")),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=[f"signal source: the index (Donchian 20 on 5-minute index bars); exit source: {L.SOURCE[rule]}; "
                      "no option chart for a signal"],
            model_or_rule=(f"{L.ARM_NAMES[arm]}, 1-ITM, exits P (-15%, L25, 30/60/100 ladder, 30 min, 15:15) + "
                           f"{rule}: {L.NAMES[rule]}"),
            settings="research/hunt/r105_donchian_giveback/r105lib.py; PREREG.md",
            prereg="research/hunt/r105_donchian_giveback/PREREG.md",
            variants_tried=1,
            results=dict(
                summary=(f"Design: {line(r['design'])} (baseline {line(r['base_design'])}). Walk-forward net "
                         f"{rs(r['wf_net'])} vs {rs(r['base_wf_net'])}; quarters {r['q']} vs {r['base_q']}. Final: "
                         f"{line(r['final'])} (baseline {line(r['base_final'])}). Changed {r['changed_design']} design "
                         f"/ {r['changed_final']} final trades. Exit-twin p {r['p_design']} design / {r['p_final']} "
                         f"final; BH q {r['q_bh']} over {fin['family']['new']} new + {fin['family']['prior']} earlier "
                         f"Donchian tests. 2x slippage final {rs(r['slip2_final_net'])} vs "
                         f"{rs(r['base_slip2_final_net'])}. Verdict: {'PASS' if g['passed'] else 'FAIL'}"
                         + (" (" + ", ".join(g["failed"]) + ")" if g["failed"] else "") + "."),
                costs=("dated charges, R17 liquidity fills, next-minute fills, stops on the minute low with gap fills "
                       "at the worse open, one held contract, 1 lot in force (X9: two 1-lot legs, per lot)")),
            holdout_read=True,
            verdict="paper-only" if g["passed"] else "rejected",
            notes=("Signal on the underlying (the index). Research only; a passer goes on paper only via the app "
                   "session (logged, undoable), never live."),
        )
        (L.ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    write()
