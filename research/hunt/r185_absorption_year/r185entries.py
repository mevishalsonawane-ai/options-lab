"""Writes research/experiments/entries/r185-*.yaml: one entry per setup and index read (from results/final_<IDX>.json and
results/selection_<IDX>.json). python research/hunt/r185_absorption_year/r185entries.py. Plain-English notes, 16 Nov 2026."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r185 as RN  # noqa: E402

ENTRIES = HERE.parents[1] / "experiments" / "entries"
DOC = "research/hunt/r185_absorption_year/results/report.md"
PREREG = "research/hunt/r185_absorption_year/PREREG.md"
DATA = ("data lake (Dhan v2, gdrive:IraAlgo-data-lake): index 1-minute and rolling WEEK options ATM-10..+10 to 19 Oct 2026, "
        "plus the 20 Oct - 13 Nov 2026 gap fetched from Dhan on 16 Nov 2026 (gdrive:IraAlgo-data-lake/dhan/); outside git")
RULE = {
    "ABS": ("absorption (R184's rule on BVC flow): heavy estimated selling over w minutes (sum of D <= -k x the day's mean "
            "|w-minute sum| so far) while the window's low stays within beta bp of the close before it -> buy the ATM CALL "
            "(mirror -> PUT); 'turn' = the next minute closes in the trade's direction"),
    "EXH": ("exhaustion (R184's rule on BVC flow): a climax minute at a new X-minute low with an estimated selling spike "
            "(|D| >= k x the day's mean |D| so far), then within 3 minutes selling under half the climax's, no low beyond "
            "beta bp, and D turns positive -> buy the ATM CALL (mirror at a high -> PUT)"),
}
NAME = {"ABS": "absorption", "EXH": "exhaustion"}


def rs(x):
    return "-" if x is None else (f"Rs -{abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}")


def main():
    for u in RN.INDICES:
        fp = HERE / "results" / f"final_{u}.json"
        if not fp.exists():
            continue
        F = json.loads(fp.read_text())
        sel = json.loads((HERE / "results" / f"selection_{u}.json").read_text())
        for key, c in F["cells"].items():
            setup = key.split()[0]
            y, tw = c["year"], c["twins"]
            sc = sel["cells"][key]
            ch = sc["choice"]
            tid = f"r185-{setup.lower()}-{u.lower()}"
            verdict = "rejected" if (y["mean"] is None or y["mean"] <= 0 or c["p_twin"] > 0.2) else "inconclusive"
            if c["verdict"] == "passes the gates":
                verdict = "passed"
            e = dict(
                id=tid, title=f"R185 {NAME[setup]} on {u} over a year (BVC flow from the index price and ATM option volume)",
                family="flow-oi", date="2026-11-16", doc=DOC,
                hypothesis=(f"R184's {NAME[setup]} rule, on flow estimated by bulk volume classification from the index's "
                            "1-minute price and the near-ATM options' volume, picks entries whose ATM option trades beat "
                            "random entries at the same minutes and beat doing nothing, after real charges."),
                data_version=DATA,
                period=dict(design=(f"2024-11-20..2025-10-19 (halves H1 to 30 Apr 2025, H2 from 1 May; 32 variants, "
                                    f"{sc['n_eligible']} eligible; chosen {ch['vid']}: {ch['summary']['n']} trades, "
                                    f"{rs(ch['summary']['net'])}; "
                                    f"{'passed the selection' if ch['passed_selection'] else 'fallback, nothing passed'})"),
                            holdout="2025-10-20..2026-10-19 (read once after results/FINAL_READ_" + u + ")"),
                instruments=[u],
                features=[("signal source: the " + u + " index's own 1-minute price (underlying); each minute's buy / sell "
                           "split by BVC (Phi of the price change over the day's earlier sigma) sized by the summed volume "
                           "of the ATM-2..+2 calls and puts - option-volume context (OVC); no futures volume history and "
                           "no second-level history exist at Dhan; option prices only price the trade"), RULE[setup]],
                model_or_rule=(f"{c['vid']}: {RULE[setup]}. Exits: -15% premium stop, breakeven (fill + charges) from the "
                               "minute after +10%, index stop beyond the extreme by beta bp, time stop T minutes, 15:14 "
                               "(15:04 expiry). 1 lot (lot schedule by date)."),
                settings=PREREG + " sections 2-6; r185lib.py", prereg=PREREG, variants_tried=32,
                results=dict(summary=(
                    f"Final year {y['n']} trades, win {y['win']:.0%}, {rs(y['net'])} (2x slippage {rs(y['net2'])}), mean "
                    f"{rs(y['mean'])} a trade; worst month {rs(y['worst_month']['net'])}, max drawdown {rs(y['max_dd'])}. "
                    f"Random twins mean {rs(tw.get('mean'))}: p {c['p_twin']:.3f}, BH q {c['q_twin']:.2f}; mean > 0 p "
                    f"{c['p_zero']:.3f}, q {c['q_zero']:.2f} (m {RN.M_TOTAL:,}). Pre-registered verdict: {c['verdict']}."
                    if y["n"] else f"No trades in the final year. Verdict: {c['verdict']}."),
                    costs="Costs('dated') both sides (BSE charges for SENSEX); Fills('liq') slippage by the contract's "
                          "minute volume; 2x slippage sensitivity; no historical bid / ask"),
                holdout_read=True, verdict=verdict,
                notes=(f"BVC infers the side from price, so it cannot see true absorption (volume that fails to move "
                       f"price); this tests R184's definitions on estimated flow. BH m = {RN.M_TOTAL:,} (4,512 after R184 "
                       "+ 320 variants + 20 tests). Gate fails: " + ("; ".join(c["gates"]["fails"]) or "none")
                       + ". Nothing in the app changes."))
            (ENTRIES / f"{tid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
            print(tid, verdict)


if __name__ == "__main__":
    main()
