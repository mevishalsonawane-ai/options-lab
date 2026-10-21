"""Write research/experiments/entries/r86b-*.yaml from results/b_design.json.

    python -m research.hunt.r86.r86bentries
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research/experiments/entries"
DOC = "research/HUNT_R86B_HOW_BIG_PLAYERS_TRADE.md"
PREREG = "research/hunt/r86/PREREG_B.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake index_1m (NIFTY 2019-, BANKNIFTY Aug 2021-) and equity_1m (59 current "
        "NIFTY 50 + NIFTY BANK stocks, 2019 - 12 Oct 2026), files of 13-15 Oct 2026; NSE participant OI as labels only")
COSTS = ("index future: Rs 40 brokerage, dated STT, exchange, SEBI, stamp, GST, 0.02% slippage a side (0.04% on stops); "
         "overnight carry 5.5%/yr; 1 lot in force")
IDEAS = {
    "t1": ("Steady accumulation (VWAP/TWAP footprint): follow a steady, busy 09:30-11:29 drift from 11:30 to 15:15",
           "11:30: morning ER top 20% and abs move above median (previous 250 sessions) and morning basket turnover >= its "
           "20-session median; stop 0.5 x morning range"),
    "t2": ("Program / basket trade: follow a minute where >= 80% of the basket moved one way on >= 3x usual turnover",
           "next minute's open, stop 2 x that minute's range (>= 0.05%), 30 minutes or 15:15; max 3 a day"),
    "t3": ("Absorption at the day's extreme: fade a heavy, quiet 5-minute bar that makes a new day high/low",
           "turnover >= 2x and range <= 0.5x the slot medians, after 10:15; stop beyond the bar, 2R target, 60 minutes"),
    "t4": ("Close / benchmark flow: follow an extreme 14:30-15:24 move on a heavy last hour overnight to 09:30",
           "move in the top/bottom 10% of 250 sessions and last-hour turnover share above its median; stop 1 ATR14"),
}


def main():
    r = json.loads((HERE / "results/b_design.json").read_text())["part2"]
    for key, (title, rule) in IDEAS.items():
        ids = [f"{key.upper()}-NIFTY", f"{key.upper()}-BANKNIFTY"]
        parts = []
        for t in ids:
            x = r[t]
            parts.append(f"{t}: {x['n']} trades, net Rs {x['net']:,.0f}, without top 5 Rs {x['net_wo_top5']:,.0f}, random "
                         f"twins mean Rs {x['twin_mean']:,.0f}, twin p {x['p']:.3f}, BH q {x['bh_q']:.3f}")
        passed = any(r[t]["passes"] for t in ids)
        e = {"id": f"r86b-{key}", "title": f"R86b {key.upper()}: {title}",
             "family": "patterns-indicators" if key != "t4" else "overnight", "date": "2026-10-19", "doc": DOC,
             "hypothesis": f"Pre-registered ({PREREG}): {title} beats random twins in design 2022-2024 (net > 0 and > twins, "
                           "twin p < 0.10, BH q < 0.10 over R86b's 8 tests, not carried by its top 5).",
             "data_version": DATA,
             "period": {"design": "2019 - 2024 (folds 2022, 2023, 2024)",
                        "holdout": "1 Jan 2025 - 12 Oct 2026 NOT read: no R86b test passed design"},
             "instruments": ["NIFTY", "BANKNIFTY"],
             "features": ["signal source: the index's own minutes and its constituents' minute prices and turnover; no option chart"],
             "model_or_rule": rule, "settings": "research/hunt/r86/r86b.py, r86blib.py; PREREG_B.md", "prereg": PREREG,
             "variants_tried": 2,
             "results": {"summary": "Design walk-forward. " + "; ".join(parts) + (". PASS." if passed else ". Verdict: FAIL."),
                         "costs": COSTS},
             "holdout_read": False, "verdict": "adopted" if passed else "rejected",
             "notes": "Reverse-engineering how big players trade (Boss, 19 Oct). Research only."}
        (ENTRIES / f"r86b-{key}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    f = {"id": "r86b-footprints", "title": "R86b Part 1: how the big players' trading shows in index and constituent minutes",
         "family": "market-facts", "date": "2026-10-19", "doc": DOC,
         "hypothesis": "Describe (not a test): where in the day the move is made on FII/Client big-buy and big-sell days, expiry and "
                       "month-end days; turnover profile; basket bursts and what follows them.",
         "data_version": DATA, "period": {"design": "2019 - 2024 (descriptive, design years only)"},
         "instruments": ["NIFTY", "BANKNIFTY"],
         "features": ["participant OI labels (after the close)", "index minutes", "constituent minute turnover and direction"],
         "model_or_rule": "bootstrap means by day label", "settings": PREREG + " section 2", "prereg": PREREG,
         "variants_tried": None,
         "results": {"summary": "See the doc: FII-sell days make ~61% of NIFTY's move by 10:15 (gap 37% + first hour 24%, 169 days); "
                                "FII-buy days 40% (gap 36% + first hour 4%, 179 days) and 16% in the last hour; month-end last-30-minute "
                                "turnover 26.7% vs 16.1% on all days (72 / 1,479 days); basket bursts follow-through no better than random minutes.",
                     "costs": "none (description)"},
         "holdout_read": None, "verdict": "not-a-test"}
    (ENTRIES / "r86b-footprints.yaml").write_text(yaml.safe_dump(f, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    main()
