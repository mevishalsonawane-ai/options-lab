"""R172 experiment entries: one YAML per arm (verdict not-a-test: a report of Boss's chosen rule, never gated).

    python research/hunt/r172_2k1k_6months/r172entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r172cfg as K
from r172report import rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r172_2k1k_6months/results/report.md"
PREREG = "research/hunt/r172_2k1k_6months/PREREG.md"
SRC = {
    "LIQ": (["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"], "the index's liquidity levels (15+5 books; FINNIFTY 30+5)"),
    "R72": (["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], "index 5-min bars of all five indices (Donchian 20, 4 of 5)"),
    "R51": (["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            "index 5-min bars (Donchian 20, kept when Supertrend (10, 3) agrees)"),
}


def main():
    r = json.loads((K.RESULTS / "r172.json").read_text())
    n = 0
    for arm in K.ARMS:
        unds, src = SRC[arm]
        parts = []
        for lots in K.LOTS:
            vs = ("A", "A+2k1k") if arm == "LIQ" else K.VARIANTS
            parts.append(f"{lots} lot{'s' if lots > 1 else ''}: " + "; ".join(
                f"{v} {r[f'{v}@{lots}'][arm]['trades']} trades, win {100 * r[f'{v}@{lots}'][arm]['win']:.0f}%, net "
                f"{rs(r[f'{v}@{lots}'][arm]['net'])}, max DD {rs(r[f'{v}@{lots}'][arm]['max_dd'])}" for v in vs))
        e = dict(
            id=f"r172-{arm.lower()}-2k1k-6months",
            title=f"R172 {K.ARM_NAME[arm]}: the last 6 months with Boss's +Rs 2,000 / -Rs 1,000 (whole position, 1-3 lots)",
            family="trade-management",
            date="2026-11-14",
            doc=DOC,
            hypothesis=("Not a hypothesis test. Boss (14 Nov) chose, as a 'failed gates, Boss's exception', a fixed +Rs 2,000 "
                        "target and -Rs 1,000 stop for the whole position, before charges (the Rs 1,000 stop only when "
                        "tighter than the arm's own; every existing exit stays), and asked for the last 6 months' results."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) copied 14 Nov "
                          "2026, ending 19 Oct 2026; R51 Amendment A clean stores built to 19 Oct (R106 clean_post)"),
            period=dict(design="none (a reporting window, nothing fitted)",
                        holdout="2026-04-20..2026-10-19 (overlaps the final 1 Apr - 13 Oct read by R51-R171)"),
            instruments=unds,
            features=[f"signal source: {src} (unchanged); only the option's exit (rupee target / stop on the premium) differs"],
            model_or_rule=(f"{K.ARM_NAME[arm]}: A today's rules; B + R151 picker (R72 / R51 only); each with +Rs 2,000 / "
                           "-Rs 1,000; 1, 2, 3 lots"),
            settings="research/hunt/r172_2k1k_6months/r172cfg.py; PREREG.md section 2",
            prereg=PREREG,
            variants_tried=None,
            results=dict(summary=" | ".join(parts) + ". The new rule lost money at every lot size.",
                         costs=("dated Zerodha charges on the whole quantity, R17 fills (slippage from the 5-minute volume "
                                "per position; stops -10 bps and 2x the half-spread, gaps at the open), limits at their price")),
            holdout_read=True,
            verdict="not-a-test",
            notes=("A reporting backtest of Boss's chosen rule: no gate, twin or BH; not counted as a test. Signal on the "
                   "underlying; nothing in the app changes from it."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
