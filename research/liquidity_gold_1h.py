"""Liquidity 15+5 rule on XAUUSD 1-hour charts (the owner's ask, 2026-10-01). Same levels, entries and exits as
research/liquidity_gold.py; the chart is 60-minute candles, alone or with a 15-minute / 5-minute book beside it.

    python research/liquidity_gold_1h.py <xauusd_m1_bid.csv.gz> [out.md]

Sessions (UTC): London + New York 07:00-21:00, India hours 03:45-10:00, and "almost all day" 00:00-21:00 (entries to
19:00, out 20:40). "Held overnight": a trade is not closed at the session's end but runs into the next session until
an exit rule fires (gold trades round the clock; overnight swap is not charged here).
"""
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import liquidity_gold as g  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

SESSIONS = dict(g.SESSIONS, **{"Almost all day": (0, 21 * 60, 5, 19 * 60, 20 * 60 + 40)})


def main():
    bid = g.read(sys.argv[1])
    ask = bid + g.SPREAD
    L = ["## Liquidity rule on XAUUSD, 1-hour charts (research/liquidity_gold_1h.py)", "",
         f"Dukascopy 1-minute bid, {bid.index.min():%Y-%m-%d} .. {bid.index.max():%Y-%m-%d}; ask = bid + {g.SPREAD}; "
         "$7 a lot. USD an ounce; x100 = one standard lot. Years October to September.", ""]
    for sname, (start, end, first, last, cut) in SESSIONS.items():
        days = g.sessions(bid, ask, start, end)
        years = sorted({g.year_of(d["day"]) for d in days}, key=lambda s: s[4:8])
        L += [f"### {sname} ({len(days)} sessions)", "",
              "| year, books, direction | trades | win | USD/oz a trade | t | USD/oz total | USD per lot (max drawdown) | green months |",
              "|---|---|---|---|---|---|---|---|"]
        books = {}
        for tf in (5, 15, 60):
            b = g.bars(days, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            books[tf] = g.simulate(days, b, zones, first, last, cut)
            if tf == 60:
                books["60c"] = g.simulate(days, b, zones, first, last, cut, carry=True)
        for label, parts in (("1h alone", [60]), ("1h held overnight", ["60c"]), ("1h + 15m", [60, 15]), ("1h + 5m", [60, 5])):
            tr = pd.concat([books[p] for p in parts])
            if tr.empty:
                continue
            tr["year"] = [g.year_of(d) for d in tr.day]
            for direction, sel in (("buys only", tr[tr.sign > 0]), ("buys + short sales", tr)):
                for y in years + ["all"]:
                    ys = sel if y == "all" else sel[sel.year == y]
                    nd = len(days) if y == "all" else sum(g.year_of(d["day"]) == y for d in days)
                    L.append(g.line(f"{y}, {label}, {direction}", ys, nd))
            print("\n".join(L[-8:]), flush=True)
        L.append("")
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
