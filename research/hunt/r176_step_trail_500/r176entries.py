"""R176 experiment entries (Amendment A, the last year): one YAML per arm.

    python research/hunt/r176_step_trail_500/r176entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r176cfg as K
from r176report import rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r176_step_trail_500/results/report.md"
PREREG = "research/hunt/r176_step_trail_500/PREREG.md"
SRC = {
    "LIQ": (["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"], "the index's liquidity levels (15+5 books; FINNIFTY 30+5)"),
    "R72": (["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], "index 5-min bars of all five indices (Donchian 20, 4 of 5)"),
    "R51": (["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            "index 5-min bars (Donchian 20, kept when Supertrend (10, 3) agrees)"),
}


def main():
    r = json.loads((K.RESULTS / "year.json").read_text())
    d = json.loads((K.RESULTS / "decision.json").read_text())
    for arm in K.ARMS:
        unds, src = SRC[arm]
        parts = []
        for n in K.LOTS:
            a, s = r["year"][f"A@{n}"][arm], r["year"][f"S@{n}"][arm]
            t = (f"{n} lot{'s' if n > 1 else ''}: today {a['trades']} trades, win {100 * a['win']:.0f}%, {rs(a['net'])}; "
                 f"step trail {s['trades']} trades, win {100 * s['win']:.0f}%, {rs(s['net'])}, max DD {rs(s['max_dd'])}")
            if arm in K.PICKER_ARMS:
                b, bs = r["year"][f"B@{n}"][arm], r["year"][f"B+S@{n}"][arm]
                t += f"; picker {rs(b['net'])} -> {rs(bs['net'])}"
            parts.append(t)
        e = dict(
            id=f"r176-{arm.lower()}-step-trail-500",
            title=f"R176 {K.ARM_NAME[arm]}: Boss's Rs 500 step trail per lot, last year of data (1-3 lots)",
            family="trade-management",
            date="2026-11-15",
            doc=DOC,
            hypothesis=("Boss's chosen rule (15 Nov, being built anyway): a -Rs 500-a-lot stop at entry (only where tighter "
                        "than -15%); from +Rs 1,000 a lot, the stop Rs 500 a lot under each Rs 500-a-lot step of the best "
                        "open P&L; every existing exit stays. Does it beat today's exits?"),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) copied 15 Nov "
                          "2026, ending 19 Oct 2026; R51 Amendment A clean stores built to 19 Oct (R106 clean_post)"),
            period=dict(design="none read (Amendment A: Boss asked for the last year only); early part 2025-10-20..2026-03-31",
                        holdout="2026-04-01..2026-10-13 final (read by R51-R173 too); whole year 2025-10-20..2026-10-19"),
            instruments=unds,
            features=[f"signal source: {src} (unchanged); only the option's stop on the premium differs"],
            model_or_rule=f"{K.ARM_NAME[arm]}: today's rules vs + Rs 500 step trail per lot; 1, 2, 3 lots; with / without R151 picker",
            settings="research/hunt/r176_step_trail_500/r176cfg.py; PREREG.md section 2 and Amendment A",
            prereg=PREREG,
            variants_tried=3,
            results=dict(summary=" | ".join(parts) + ". Failed every gate at every lot size: "
                         + "; ".join(f"{n} lot: " + ", ".join(d[f"{arm}@{n}"]["fails"]) for n in K.LOTS[:1]) + ".",
                         costs=("dated Zerodha charges on the whole quantity, R17 fills (slippage from the 5-minute volume "
                                "per position; every stop sold as a stop: x0.999 minus 2x the half-spread, gaps at the open)")),
            holdout_read=True,
            verdict="rejected",
            notes=(f"Twins p = 1.0, BH q = 1.0 (m = {K.M_TOTAL}). Boss's chosen rule, being built anyway; reported honestly as "
                   "failing. Signal on the underlying; nothing in the app changes from this study."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(len(K.ARMS), "entries")


if __name__ == "__main__":
    main()
