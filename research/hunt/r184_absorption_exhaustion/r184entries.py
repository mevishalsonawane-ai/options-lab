"""Writes research/experiments/entries/r184-*.yaml: one entry per setup and index (4), from results/holdout.json and
results/selection.json. python research/hunt/r184_absorption_exhaustion/r184entries.py. Plain-English notes, 16 Nov 2026."""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[1] / "experiments" / "entries"
DOC = "research/hunt/r184_absorption_exhaustion/results/report.md"
PREREG = "research/hunt/r184_absorption_exhaustion/PREREG.md"
DATA = ("phone recordings exported 15 Nov 2026: 1-second order flow 15 Oct - 13 Nov 2026 (20 days; NIFTY / BANKNIFTY near "
        "futures, index and near-ATM options); daily diagnostics 19 Oct - 13 Nov (paper orders, overlap only); outside git")
RULE = {
    "ABS": ("absorption: heavy aggressive selling (w-minute futures delta <= -k x the day's mean |w-minute delta| so far) "
            "while the window's low stays within beta bp of the close before it -> buy the ATM CALL (mirror: buying "
            "absorbed at a high -> PUT); confirm 'turn' = the next minute closes in the trade's direction"),
    "EXH": ("exhaustion: a climax minute at a new X-minute low with a selling spike (|D| >= k x the day's mean |D| so far), "
            "then within 3 minutes selling under half the climax's, no low beyond beta bp, and delta flips positive -> "
            "buy the ATM CALL (mirror at a high -> PUT); 'flip+close' also needs the close beyond the climax close"),
}
NAME = {"ABS": "absorption", "EXH": "exhaustion"}


def rs(x):
    return "-" if x is None else (f"Rs -{abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}")


def main():
    h = json.loads((HERE / "results" / "holdout.json").read_text())
    sel = json.loads((HERE / "results" / "selection.json").read_text())
    for key, c in h["cells"].items():
        setup, und = key.split()
        S, D, tw = c["holdout"], c["design"], c["twins"]
        npos = sum(v["net"] > 0 for v in sel["cells"][key]["variants"])
        tid = f"r184-{setup.lower()}-{und.lower()}"
        verdict = "rejected" if (S["mean"] is None or S["mean"] <= 0) else "inconclusive"
        e = dict(
            id=tid, title=f"R184 {NAME[setup]} on the {und} future, buying the ATM option ({c['vid']})",
            family="flow-oi", date="2026-11-16", doc=DOC,
            hypothesis=(f"Order-flow {NAME[setup]} on the index future picks reversal entries whose ATM option trades beat "
                        "random entries at the same minutes (twins) and beat doing nothing, after real charges."),
            data_version=DATA,
            period=dict(design=f"2026-10-15..2026-11-03 (13 days; 32 variants, {npos} with net > 0; chosen: {D['n']} trades, "
                               f"win {D['win']:.0%}, {rs(D['net'])})",
                        holdout="2026-11-04..2026-11-13 (7 days, read once after results/FINAL_READ)"),
            instruments=[und],
            features=[f"signal source: the {und} near index future's own 1-minute price and 1-second tick-rule delta "
                      "(buy_vol - sell_vol, the app's recorder), references from the same day's earlier minutes only; "
                      "option prices only price the trade; premium stop and breakeven lock on the option premium",
                      RULE[setup]],
            model_or_rule=(f"{c['vid']}: {RULE[setup]}. Exits: -15% premium stop, breakeven (fill + charges) once +10%, "
                           f"index stop beyond the extreme by beta bp, time stop T minutes, 15:15 (15:05 expiry). 1 lot."),
            settings=PREREG + " sections 2-6; r184lib.py", prereg=PREREG, variants_tried=32,
            results=dict(summary=(
                f"Holdout {S['n']} trades on {S.get('days_traded', 0)} of 7 days, win {S['win']:.0%}, {rs(S['net'])} "
                f"(2x slippage {rs(S['net2'])}; bid/ask {rs(S['net_ba'])}), mean {rs(S['mean'])} a trade "
                f"(avg win {rs(S['avg_win'])}, avg loss {rs(S['avg_loss'])}), worst day {rs(S['worst_day'])}. Random-entry "
                f"twins (1,000 sets) mean {rs(tw['mean'])}: p {c['p_twin']:.3f}, BH q {c['q_twin']:.2f}; mean > 0 "
                f"day-bootstrap p {c['p_zero']:.3f}, q {c['q_zero']:.2f} (m {h['m_total']:,}). Smallest edge detectable "
                f"(80% power, 5%) {rs(c['power']['mde_5pct'])} a trade. Pre-registered verdict: {c['verdict']}."),
                costs="Costs('dated') charges both sides; Fills('liq') slippage by the option's recorded volume; 2x "
                      "slippage and recorded bid/ask sensitivities"),
            holdout_read=True, verdict=verdict,
            notes=("Exploratory: 20 days, at most 39 design / 22 holdout trades a cell; cannot pass the gates. The 1-second "
                   "recorder drops big unchanged-price prints (R182), the very prints absorption is about. BH m = 4,512 "
                   "(4,376 after R183 + 128 variants + 8 tests). Nothing in the app changes."))
        (ENTRIES / f"{tid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
        print(tid, verdict)


if __name__ == "__main__":
    main()
