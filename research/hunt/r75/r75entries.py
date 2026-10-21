"""R75: results/final.json -> research/experiments/entries/r75-*.yaml (one per test, failures included).

    python research/hunt/r75/r75entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r75cfg as R  # noqa: E402
from r75report import pct, rs  # noqa: E402

ENTRIES = HERE.parents[1] / "experiments" / "entries"
FAMILY = {"L1": "daily-filters", "L2": "daily-filters", "L3": "daily-filters", "L4": "daily-filters",
          "L5": "option-structure", "L6": "calendar-events", "L7": "sizing-execution"}


def line(st: dict) -> str:
    return (f"{st.get('trades', 0)} trades, net {rs(st.get('net'))}, win {pct(st.get('win'))}, avg win "
            f"{rs(st.get('avg_win'))} / loss {rs(st.get('avg_loss'))}, worst day {rs(st.get('worst_day'))}, max DD "
            f"{rs(st.get('max_dd'))}, green days {pct(st.get('green_days'))}, months < -13k {st.get('months_13k', 0)}, "
            f"DD > 40k episodes {st.get('dd_40k', 0)}")


def write():
    fin = json.loads((R.RESULTS / "final.json").read_text())
    des = json.loads((R.RESULTS / "design.json").read_text())
    for tname in R.TESTS:
        base, lv = tname.split(":")
        r = fin["tests"][tname]
        b = fin["base"][base]
        c = r["vs_base"]
        e = dict(
            id=f"r75-{base.lower()}-{lv.lower()}",
            title=f"R75 {lv} {R.NAMES[lv]} on {R.BASE_NAMES[base]} (paper exits)",
            family=FAMILY[lv[:2]],
            date="2026-10-16",
            doc="research/HUNT_R75_DONCHIAN_LESS_LOSS.md",
            hypothesis=(f"Boss (16 Oct: \"make Donchian more powerful, less loss\"): {R.NAMES[lv]} lowers the loss / "
                        f"drawdown of {R.BASE_NAMES[base]} without materially lowering its P&L, in design, in >= 3 of "
                        "4 walk-forward quarters and in the final, beats a random-skip twin (p < 0.05), survives BH "
                        "over R75's 40 tests (q <= 0.10), affects >= 40 trades and holds at 2x slippage."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m incl. INDIAVIX, options_rolling_1m WEEK), R51 "
                          "Amendment A clean stores, 20 Nov 2024 - 13 Oct 2026"),
            period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout=("1 Apr - 13 Oct 2026, read once for R75 (16 Oct); the window was already read by "
                                 "R51-R73 (Donchian chosen on it), so weak evidence")),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the index (Donchian 20 on 5-minute index bars; the lever reads the index, India "
                      "VIX, the arm's own closed trades or the calendar); no option chart"],
            model_or_rule=f"{R.BASE_NAMES[base]} + {lv}: {R.NAMES[lv]}; 1-ITM unless L5; exits P (-15%, A4 ladder + "
                          "L25, 30 min)",
            settings="research/hunt/r75/r75cfg.py, research/hunt/r75/r75levers.py; PREREG.md",
            prereg="research/hunt/r75/PREREG.md",
            variants_tried=1,
            results=dict(
                summary=(f"Design: {line(r['design'])}; baseline design: {line(des['base'][base]['design'])}; "
                         f"design better: {r['design_better']}; better in {r['wf_better']} of 4 walk-forward quarters. "
                         f"Final: {line(r['final'])}; baseline final: {line(b['final'])}; final better: "
                         f"{r['final_better']}. Affected {r['affected']} trades: {c['losers_avoided']} losers avoided "
                         f"({rs(c['losers_avoided_rs'])}), {c['winners_missed']} winners missed "
                         f"({rs(c['winners_missed_rs'])}), {c['halved']} halved, {c['extra']} new. Twin p {r['p']} "
                         f"(BH q {r['q']}). 2x slippage: final {rs(r['slip2_net'])} (baseline "
                         f"{rs(b['slip2']['net'])}). Verdict: {r['verdict']}"
                         + (" (" + "; ".join(r["fails"]) + ")" if r["fails"] else "") + "."),
                costs=("dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap fills at the "
                       "worse open, one held contract, 1 lot in force (half size: charges at half quantity)")),
            holdout_read=True,
            verdict="paper-only" if r["verdict"] == "PASS" else "rejected",
            notes=("Signal on the underlying (the index). Research only; nothing changes paper or live unless Boss "
                   "switches it on (paper only). Donchian frozen after R75."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    write()
