"""R195 log entries: one research/experiments/entries/r195-*.yaml per test (18 candidates + 5 baselines).

    python3 -I research/hunt/r195_option_selling/r195entries.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r195cfg as K  # noqa: E402
import r195rules as RU  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
RULE = {
    "S1": "09:20 short ATM straddle, 25% stop on each leg's premium, square-off 15:10 / 15:05",
    "S2": "S1 when India VIX at 09:20 >= its median of the previous 250 sessions",
    "S3": "S1 when the 09:20 straddle's implied vol >= 1.2 x 20-session realised vol",
    "S4": "09:20 short straddle with wings 6 steps out, 30% stop per side on the short leg's premium",
    "E1": "expiry-day 09:20 iron fly (wings 4 steps), stop when the shorts reach 1.5x their entry, square-off 15:05",
    "E2": "expiry-day 13:30 iron fly (wings 4 steps), 1.5x premium stop, square-off 15:05",
    "E3": "SENSEX expiry-day 09:20 iron fly (wings 4 steps), 1.5x premium stop",
    "T1": "10:15 first-hour index move >= 0.25%: credit spread (short 1 step OTM, 4-step width) with the trend; index stop at "
          "the 09:15 open and a stop at 50% of max loss",
    "T2": "09:20 credit spread with the daily trend (yesterday's close vs 20-session average); stop at 50% of max loss",
    "V1": "09:20 iron condor on calm days (yesterday's range <= 0.8x its 20-session average, open within 0.3%); stop at 50% "
          "of max loss",
    "V2": "09:20 iron condor (V1's strikes) when implied vol >= 1.2 x 20-session realised vol; stop at 50% of max loss",
    "P1": "overnight iron fly (wings 4 steps): sell at 15:16, buy back at the next session's 09:21; no expiry carry",
    "N1": "S4 taken only when a 6-hidden-layer MLP (32 units each), refitted each year walk-forward, predicts a positive net",
    "B0": "BASELINE: naked 09:20 short straddle held to the square-off, no stop, no hedge",
}
SOURCE = {"S1": "none directional (ATM from the index at 09:20)", "S2": "none directional; India VIX filter",
          "S3": "none directional; implied vs realised vol filter", "S4": "none directional", "E1": "none directional",
          "E2": "none directional", "E3": "none directional", "T1": "the index (first-hour return; index stop)",
          "T2": "the index (daily trend)", "V1": "the index (calm-day filter)", "V2": "implied vs realised vol filter",
          "P1": "none directional", "N1": "index, India VIX and the 09:20 straddle (features at 09:20)",
          "B0": "none directional"}


def rs(x):
    return "n/a" if x is None else (f"-Rs {abs(round(x)):,}" if x < 0 else f"Rs {round(x):,}")


def summ(s):
    if not s or not s.get("n"):
        return "0 trades"
    return (f"{s['n']} tr, {s['win'] * 100:.1f}% win, {rs(s['net'])}, DD {rs(s['max_dd'])}, worst month "
            f"{rs(s['worst_month'])}, worst day {rs(s['worst_day'])}, margin p95 {rs(s['margin_p95'])}")


def main():
    fj = json.loads((K.RESULTS / "final.json").read_text())
    for cid in RU.TESTS:
        t = fj["tests"][cid]
        f = RU.fam(cid)
        e = dict(
            id=f"r195-{cid.lower()}",
            title=f"R195 option selling (side study): {cid} - {RULE[f]}",
            family="ml" if f == "N1" else "option-structure",
            date="2026-11-18",
            doc="research/HUNT_R195_OPTION_SELLING.md",
            hypothesis=f"{RULE[f]} makes money after real charges and slippage, in walk-forward and in the 2026 holdout, "
                       f"inside Boss's account stop at 1 lot",
            data_version="Drive lake copy of 18 Nov 2026 (index_1m five indices + INDIAVIX, options_rolling_1m WEEK ATM+-10 "
                         "with the 20 Oct - 13 Nov 2026 parts); R51 Amendment A clean held-contract stores 2 Aug 2021 - 13 Nov 2026",
            period=dict(design="walk-forward 2021-08-02..2025-12-31 (filters trailing-only; N1 refitted yearly, design 2023-2025)",
                        holdout="2026-01-01..2026-11-13, read once (results/HOLDOUT_READ)"),
            instruments=[RU.und_of(cid)],
            features=[f"signal source: {SOURCE[f]}", "stops on the option premium (T1 also on the index)",
                      "stale legs marked by Black-Scholes at the last print's implied vol"],
            model_or_rule=RULE[f],
            settings="research/hunt/r195_option_selling/r195rules.py; PREREG.md section 4",
            prereg="research/hunt/r195_option_selling/PREREG.md",
            variants_tried=1,
            results=dict(
                summary=(f"walk-forward {summ(t['design'])}; post-Nov-2024 part {summ(t['post_regime_design'])}; holdout "
                         f"{summ(t['holdout'])}; twin p {t['twin'].get('p_emp')}, boot p {t['boot_p']:.3f}, BH q "
                         f"{t['bh_q']:.3g} (m = {fj['m']:,}); 2x slippage {rs(t['net_2x_slip'])}; "
                         f"possible at Rs 56k: {'yes' if t['possible_at_56k'] else 'no'}. "
                         + ("Baseline (never a candidate)." if t["verdict"] == "baseline"
                            else f"Failed: {', '.join(t['failed']) or 'none'}")),
                costs="dated Zerodha charges (STT on sales, 0.15% from Apr 2026; BSE rates for SENSEX), R103 'liq' "
                      "slippage (doubled on stops), today's lot every year; no exercise (all legs closed by order)"),
            holdout_read=True,
            verdict="rejected" if t["verdict"] != "PASS" else "inconclusive",
            notes="Research only (Boss, 18 Nov 2026: a side study). The app stays buy-only; Boss chose no option selling "
                  "on 21 Oct 2026. A pass would only be a note for Boss." + (" Baseline only." if f == "B0" else ""),
        )
        (ENTRIES / f"r195-{cid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    print("entries written", len(RU.TESTS))


if __name__ == "__main__":
    main()
