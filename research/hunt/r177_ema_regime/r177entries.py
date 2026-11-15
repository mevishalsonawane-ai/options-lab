"""R177-EMA experiment entries: one YAML per tested group (PLAIN, each filter alone, all three, and the pre-registered
variants of all three).

    python research/hunt/r177_ema_regime/r177entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json
import pickle

import yaml

import r177cfg as K
from r177report import rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r177_ema_regime/results/report.md"
PREREG = "research/hunt/r177_ema_regime/PREREG.md"
GROUPS = {
    "plain": (("PLAIN",), "the plain 9/21 EMA crossover, no filter"),
    "adx": (("ADX",), "9/21 crossover + ADX(14) > 25 only"),
    "trend-vwap": (("TREND",), "9/21 crossover + 1-hour trend (H1 close > H1 EMA 21) + session VWAP only"),
    "atr-stop": (("ATR",), "9/21 crossover + a 2 x ATR(14) index stop only"),
    "all-three": (("ALL",), "9/21 crossover + ADX > 25 + 1-hour trend + VWAP + 2 x ATR index stop (the PDF's full rule)"),
    "all-variants": (("ALL-H1B", "ALL-TWAP", "ALL-OR", "ALL-2R", "ALL-ITM"),
                     "all three with: H1 EMA 9 > 21; TWAP; clearing the opening range; a 2R target; a 1-ITM strike"),
}


def main():
    d = json.loads((K.RESULTS / "decision.json").read_text())
    with open(K.OUT / "cells.pkl", "rb") as fh:
        cells = pickle.load(fh)
    for g, (rules, what) in GROUPS.items():
        parts = []
        for r in rules:
            for n in K.LOTS:
                c = cells[(r, "both", "both", n)]["per"]
                f, s = c["full"], c["final"]
                parts.append(f"{r} {n} lot{'s' if n > 1 else ''}: full {f['trades']} trades, win {f['win']:.0f}%, "
                             f"{rs(f['net'])}, max DD {rs(f['max_dd'])}; last 6 months {s['trades']} trades, win "
                             f"{s['win']:.0f}%, {rs(s['net'])}")
        cs = [v for k, v in d["cells"].items() if k.split("|")[0] in rules]
        npass = sum(v["passed"] for v in cs)
        lowp = min(v["p_emp"] for v in cs)
        e = dict(
            id=f"r177-ema-{g}",
            title=f"R177-EMA: {what} (15-minute index bars, NIFTY + BANKNIFTY options, 1-3 lots)",
            family="patterns-indicators",
            date="2026-11-15",
            doc=DOC,
            hypothesis=("Boss's PDF: a 9/21 EMA crossover bot fails in chop and three regime filters (ADX(14) > 25; 1-hour "
                        "trend up + price above VWAP; a stop at 2 x ATR(14)) fix it. Boss: 'What if all 3 are together'. "
                        f"This group: {what}. Does it make money after costs and beat random entries?"),
            data_version=("repo research-data release (nifty_prev, nifty_year, banknifty_prev_year_wide, "
                          "banknifty_year_wide) via R171's loader; sessions before 24 Feb 2026 only; the Drive lake was "
                          "not reachable"),
            period=dict(design="2024-05-06..2025-08-22 (4 folds)",
                        holdout="2025-08-25..2026-02-23 final = last 6 months, read once (results/FINAL_READ)"),
            instruments=["NIFTY", "BANKNIFTY"],
            features=["signal source: the index spot (15-minute EMA 9/21 crossover; ADX(14), ATR(14) on 15-minute "
                      "bars; 1-hour bars completed by the signal bar; session VWAP weighted by option-chain volume, "
                      "the index has no volume)", "ATM option of the nearest expiry (1-ITM variant)"],
            model_or_rule=(f"{what}; exits: opposite crossover, the index stop where used, 2R where used, 15:10 "
                           "square-off; one position a side; expiry days skipped; 1, 2, 3 lots"),
            settings="research/hunt/r177_ema_regime/r177cfg.py; PREREG.md section 2",
            prereg=PREREG,
            variants_tried=len(rules) * 27,
            results=dict(
                summary=(" | ".join(parts) + f". Both indices, calls + puts shown; {npass} of {len(cs)} cells (index x "
                         f"side x lots) passed the gates; lowest twin p {lowp:.2f}."),
                costs=("dated Zerodha charges on the whole quantity; R17 fills (slippage from the 5-minute volume per "
                       "position; index stops sold as stop orders: x0.999 minus 2x the half-spread)")),
            holdout_read=True,
            verdict="rejected" if npass == 0 else "inconclusive",
            notes=(f"BH q = 1.00 on every cell (m = {K.M_TOTAL:,}). Signal on the underlying; nothing in the app "
                   "changes. Folder r177_ema_regime (origin/main already had an R177, r177_vp_trap)."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print(e["id"], e["verdict"])


if __name__ == "__main__":
    main()
