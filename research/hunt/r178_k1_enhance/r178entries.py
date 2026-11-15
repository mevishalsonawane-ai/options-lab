"""R178 experiment entry (one entry for the 18 selection comparisons)."""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
RESULTS = HERE / "results"


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    s = json.loads((RESULTS / "selection.json").read_text())
    parts = []
    for a in ("LIQ", "R72", "R51"):
        parts.append(f"{a}: today {rs(s[a]['A']['net'])}; " + ", ".join(
            f"{v} {rs(s[a][v]['net'])}" for v in ("K1", "K2", "K3", "K4", "K5", "K6")) + f"; chosen {s[a]['chosen'] or 'none'}")
    e = dict(
        id="r178-k1-variants",
        title="R178: six K1 skip variants (value-area / day-range failed breaks) selected on Nov 2024 - Oct 2025",
        family="daily-filters",
        date="2026-11-15",
        doc="research/hunt/r178_k1_enhance/results/report.md",
        hypothesis=("Boss: 'Can we enhance k1 skip more'. Variants of R177's K1 (windows 15 / 30 / 60 min / all day, "
                    "yesterday's high / low, K1 or K5), chosen on the year before K1 was found, then tested on the last year."),
        data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                      "stores built to 19 Oct (R106 clean_post)"),
        period=dict(design="2024-11-20..2025-10-19 (selection; halves to / from 1 May 2025)",
                    holdout="2025-10-20..2026-10-19 (not tested: nothing qualified)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["signal source: the traded index (yesterday's TPO value area / high / low, today's 1-minute closes)"],
        model_or_rule="skip calls (puts) after a failed break above VAH / PDH (below VAL / PDL); PREREG.md section 2",
        settings="research/hunt/r178_k1_enhance/r178study.py; PREREG.md",
        prereg="research/hunt/r178_k1_enhance/PREREG.md",
        variants_tried=18,
        results=dict(summary=" | ".join(parts) + ". No variant beat today's rules in both halves on any arm; K1 cut "
                     "Liquidity from Rs 1.43 L to Rs 88k on the selection year.",
                     costs="dated Zerodha charges on the whole quantity; R17 fills"),
        holdout_read=False,
        verdict="rejected",
        notes="BH m = 4,030 (18 selection comparisons counted). R177's K1 lead did not hold on the earlier year. Nothing in the app changes.",
    )
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("1 entry")


if __name__ == "__main__":
    main()
