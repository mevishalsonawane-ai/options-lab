"""R189 experiment entries: one YAML per tested group (the picked exit; the other exit candidates; each entry variant).

    python3 research/hunt/r189_hull_bbi/r189entries.py && python3 research/experiments/validate.py
"""
from __future__ import annotations

import json
import pickle

import yaml

import r189cfg as K
from r189report import rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r189_hull_bbi/results/report.md"
PREREG = "research/hunt/r189_hull_bbi/PREREG.md"


def groups(pick: str) -> dict:
    others = tuple(f"BOSS-{x}" for x in sorted(K.EXITS) if x != pick)
    return {
        "boss-picked-exit": ((f"BOSS-{pick}",), f"Boss's Hull line, close beyond it, ATM, the researched exit {pick} "
                                                f"({K.EXIT_NAME[pick]})"),
        "boss-other-exits": (others, "Boss's Hull line, close beyond it, ATM, each of the other 15 exit candidates "
                                     "(secondary, not chosen by the design rule)"),
        "std-hull": ((f"STD-{pick}",), f"the standard Hull MA instead of Boss's script, exit {pick}"),
        "whole-candle": ((f"LOW-{pick}",), f"the whole candle beyond Boss's line (low / high), exit {pick}"),
        "itm": ((f"ITM-{pick}",), f"Boss's line, 1-ITM strike, exit {pick}"),
    }


def main():
    d = json.loads((K.RESULTS / "decision.json").read_text())
    with open(K.OUT / "cells.pkl", "rb") as fh:
        cells = pickle.load(fh)
    pick = d["picked_exit"]
    for g, (rules, what) in groups(pick).items():
        parts = []
        show = rules if len(rules) == 1 else ()
        for r in show:
            for n in K.LOTS:
                c = cells[(r, "all", "both", n)]["per"]
                f, s = c["full"], c["final"]
                parts.append(f"{r} {n} lot{'s' if n > 1 else ''}: full {f['trades']} trades, win {f['win']:.0f}%, "
                             f"{rs(f['net'])}, max DD {rs(f['max_dd'])}; last 6 months {s['trades']} trades, win "
                             f"{s['win']:.0f}%, {rs(s['net'])}")
            for side in ("C", "P"):
                s = cells[(r, "all", side, 1)]["per"]["final"]
                parts.append(f"{K.SIDE_NAME[side]} 1 lot last 6 months: {s['trades']} trades, win {s['win']:.0f}%, "
                             f"{rs(s['net'])}")
        if not show:
            for r in rules:
                s = cells[(r, "all", "both", 1)]["per"]
                parts.append(f"{r.split('-')[1]} 1 lot: design {rs(s['design']['net'])}, last 6 months "
                             f"{s['final']['trades']} trades {rs(s['final']['net'])}")
        cs = [v for k, v in d["cells"].items() if k.split("|")[0] in rules]
        npass = sum(v["passed"] for v in cs)
        lowp = min(v["p_emp"] for v in cs)
        lowq = min(v["q_all"] for v in cs)
        verdict = "rejected" if npass == 0 else ("inconclusive" if len(rules) > 1 else "passed")
        e = dict(
            id=f"r189-{g}",
            title=f"R189: {what} (1-min only, no 10-sec data; 5 index options, 1-3 lots)",
            family="patterns-indicators",
            date="2026-11-16",
            doc=DOC,
            hypothesis=("Boss's TradingView Hull line (HMA 55 as Boss's script writes it: WMA(2 x WMA55 - WMA27, 7)): "
                        "buy a call when the last 2 closed candles are above the rising (blue) line, a put when below "
                        "the falling (red) line; Boss asked for 1-min AND 10-sec agreement and 'Find the exit by "
                        f"research'. This group: {what}. Does it make money after costs and beat random entries?"),
            data_version=("options-lab dhan-data branch (Dhan v2, fetched 6 Oct 2026): 1-minute index candles and "
                          "rolling ATM +/- 10 1-minute options of the nearest expiry, read with research/obuy/data.py; "
                          "no 10-second data reachable (the phone's per-second recordings are sealed)"),
            period=dict(design="2024-11-20..2026-03-31 (4 folds; the exit chosen on them)",
                        holdout="2026-04-01..2026-10-06 final = last 6 months, read once (results/FINAL_READ)"),
            instruments=list(K.UNDS),
            features=["signal source: the index spot (1-minute candles; Hull line on the continuous 1-minute close "
                      "series)", "ATM option of the nearest expiry (1-ITM variant)",
                      "1-min only: the 10-second half of Boss's rule was not tested"],
            model_or_rule=(f"{what}; fresh signal only (condition false -> true), one position per index, orders "
                           "09:20-15:00; -15% premium stop and 15:10 square-off (15:05 on expiry) on every exit; 1, 2, "
                           "3 lots"),
            settings="research/hunt/r189_hull_bbi/r189cfg.py; PREREG.md section 3",
            prereg=PREREG,
            variants_tried=len(rules) * 54,
            results=dict(
                summary=(" | ".join(parts) + f". All five indices, calls + puts unless stated; {npass} of {len(cs)} "
                         f"cells (rule x index x side x lots) passed the gates; lowest twin p {lowp:.3f}, lowest BH q "
                         f"{lowq:.2f}."),
                costs=("dated Zerodha charges on the whole quantity; R17 fills (slippage from the 5-minute volume per "
                       "position; premium and index stops sold as stop orders: x0.999 minus 2x the half-spread; limit "
                       "targets at their price)")),
            holdout_read=True,
            verdict=verdict,
            notes=(f"BH m = {K.M_TOTAL:,}. The exit was chosen on the design folds only ({d['exit_choice']['rule']}); "
                   "Boss's script lags more than the standard Hull (it doubles the slow WMA, not the fast). Signal on "
                   "the underlying; nothing in the app changes."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print(e["id"], e["verdict"])


if __name__ == "__main__":
    main()
