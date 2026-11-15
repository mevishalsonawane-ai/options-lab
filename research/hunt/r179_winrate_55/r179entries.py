"""R179 experiment entries: one per variant (V1-V4), each covering R72 and R51."""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
RESULTS = HERE / "results"
LAB = {"V1": "Rs 1,500 rule + R151 picker", "V2": "Rs 1,500 rule + breakeven lock (R162 BE10)",
       "V3": "Rs 1,500 rule + picker + breakeven lock", "V4": "today's rules + R151 picker + breakeven lock (R162 BE10)"}


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    for v in ("V1", "V2", "V3", "V4"):
        parts = []
        for a in ("R72", "R51"):
            s, f, g = r["year"][f"{v}@1"][a], r["final"][f"{v}@1"][a], d[f"{a}:{v}"]
            parts.append(f"{a}: year {s['trades']} trades win {100 * s['win']:.1f}% {rs(s['net'])} (base {rs(g['base_net_year'])}); "
                         f"final win {100 * f['win']:.1f}% {rs(f['net'])}; twin p {g['twin_p']}; failed: {'; '.join(g['fails'])}")
        e = dict(
            id=f"r179-{v.lower()}", title=f"R179 {v}: {LAB[v]} on R72 / R51 for a win rate above 55% (last year, 1 lot)",
            family="trade-management", date="2026-11-15", doc="research/hunt/r179_winrate_55/results/report.md",
            hypothesis="Boss: raise the win rate above 55% while keeping profit; " + LAB[v] + ".",
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                          "stores built to 19 Oct (R106 clean_post)"),
            period=dict(design="none fitted here; early part 2025-10-20..2026-03-31 (picker in-sample)",
                        holdout="2026-04-01..2026-10-13 final (clean for the picker; read by many studies); year to 2026-10-19"),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the index (entries; R151 picker features at the decision minute); stops on the premium"],
            model_or_rule=LAB[v] + "; PREREG.md section 1", settings="research/hunt/r179_winrate_55/r179study.py",
            prereg="research/hunt/r179_winrate_55/PREREG.md", variants_tried=1,
            results=dict(summary=" | ".join(parts) + ".",
                         costs="dated Zerodha charges on the whole quantity; R17 fills (stops sold as stops)"),
            holdout_read=True, verdict="rejected",
            notes=(f"BH m = {d['_m']['total']}. " + ("R51 V4 passed the win (55.5% / 55.1%) and profit gates but failed BH "
                   "and the account-stop gate; vs the live picker alone it earns about Rs 43k-47k less a year. A candidate "
                   "for a paper / shadow trial, not adopted. " if v == "V4" else "") + "Nothing in the app changes."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("4 entries")


if __name__ == "__main__":
    main()
