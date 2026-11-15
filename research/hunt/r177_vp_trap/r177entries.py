"""R177 experiment entries: one YAML per rule (3), each covering the three arms.

    python research/hunt/r177_vp_trap/r177entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
RESULTS = HERE / "results"
NAME = {"LIQ": "Liquidity 15+5", "R72": "R72", "R51": "R51"}
TITLE = {"X1": "value-area trap exit (Cases 1 / 2)", "X2": "day-range trap exit (Case 5)",
         "K1": "skip after a failed value-area break"}


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    for rule in ("X1", "X2", "K1"):
        parts = []
        for a in ("LIQ", "R72", "R51"):
            A, S = r["year"]["A@1"][a], r["year"][f"{rule}@1"][a]
            g = d[f"{a}:{rule}"]
            parts.append(f"{NAME[a]}: {A['trades']} trades {rs(A['net'])} -> {S['trades']} trades {rs(S['net'])} "
                         f"(final {rs(r['final']['A@1'][a]['net'])} -> {rs(r['final'][f'{rule}@1'][a]['net'])}); twin p "
                         f"{g['twin_p']}, BH q {g['q_all']}; failed: {'; '.join(g['fails'])}")
        e = dict(
            id=f"r177-{rule.lower()}",
            title=f"R177 {rule}: {TITLE[rule]} from Boss's volume-profile PDF, on Liquidity 15+5, R72, R51 (last year, 1 lot)",
            family="daily-filters" if rule == "K1" else "trade-management",
            date="2026-11-15",
            doc="research/hunt/r177_vp_trap/results/report.md",
            hypothesis=("Boss's PDF (Volume Profile & Order Flow Setups, Cases 1, 2, 5): a break beyond yesterday's value area "
                        "(or day range) that closes back inside is a trap; " + TITLE[rule] + " should raise profit by "
                        "avoiding wrong trades. Footprint / delta / CVD not tested (no tick data for a year)."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                          "stores built to 19 Oct (R106 clean_post)"),
            period=dict(design="none fitted; early part 2025-10-20..2026-03-31",
                        holdout="2026-04-01..2026-10-13 final (read by many studies); whole year 2025-10-20..2026-10-19"),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the traded index (yesterday's TPO value area / high / low from index 1-minute bars; "
                      "today's index closes); the arms' entries unchanged"],
            model_or_rule=f"{TITLE[rule]}; PREREG.md section 2",
            settings="research/hunt/r177_vp_trap/r177lib.py, r177study.py; PREREG.md",
            prereg="research/hunt/r177_vp_trap/PREREG.md",
            variants_tried=3,
            results=dict(summary=" | ".join(parts) + ".",
                         costs="dated Zerodha charges on the whole quantity; R17 fills (stops sold as stops, gaps at the open)"),
            holdout_read=True,
            verdict="rejected",
            notes=(f"BH m = {d['_m']['total']}. " + ("Liquidity K1 beat today's rules in every period (twin p 0.014, q within "
                   "R177 0.126) but failed BH and the account-stop gate; a lead for a forward test, not adopted. "
                   if rule == "K1" else "") + "Nothing in the app changes."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("3 entries")


if __name__ == "__main__":
    main()
