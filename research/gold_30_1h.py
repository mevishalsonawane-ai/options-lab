"""IraGoldAlgo with two books, 30-minute + 1-hour (the owner's ask, 2026-10-01), like IraAlgo's Liquidity 15+5.

    python research/gold_30_1h.py <xauusd_m1_bid.csv.gz> [out.md]

Each book runs the gold rule (24x5, buys only, held overnight, out by Friday 20:40 UTC) on its own chart with its own
one position, so both can hold gold at once (up to 2x the lot). Old settings (pool confirmation 10, nearest level) and
new (15, second level up). Drawdown is on the two books' combined daily P&L. USD per standard lot a book after costs.
"""
from __future__ import annotations

import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import gold_1h_plus as p  # noqa: E402
import liquidity_gold as g  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402


def book(days, tf, conf, second):
    b = g.bars(days, tf)
    tr = p.simulate(days, b, swing_zones(b, 20, "full") + pool_zones(b, 2, 5, conf), second_target=second)
    tr["book"] = f"{tf}m"
    return tr


def main():
    bid = g.read(sys.argv[1])
    days = g.sessions(bid, bid + g.SPREAD, p.START, p.END)
    years = sorted({g.year_of(d["day"]) for d in days}, key=lambda s: s[4:8])
    fit, hold = years[:-1], years[-1:]
    months = len(pd.period_range("2023-10", "2026-09", freq="M"))
    L = ["## IraGoldAlgo with 30-minute + 1-hour books (research/gold_30_1h.py)", "",
         "Each book holds at most one buy of 1 lot; both can be open together. USD after costs. Fitting years "
         + ", ".join(fit) + "; held out " + ", ".join(hold) + ".", "",
         "| books | settings | " + " | ".join(years) + " | fitting | held out | 3 years | trades | win | t | max drawdown (daily) | days both books traded | avg month at 0.01 lot a book |",
         "|---|---|" + "---|" * (len(years) + 9)]
    for label, conf, second in (("old: confirm 10, nearest level", 10, False), ("new: confirm 15, second level", 15, True)):
        h = book(days, 60, conf, second)
        m = book(days, 30, conf, second)
        for name, tr in (("1 hour alone", h), ("30 min alone", m), ("30 min + 1 hour", pd.concat([m, h], ignore_index=True))):
            tr = tr.copy()
            tr["year"] = [g.year_of(d) for d in tr.day]
            x = tr.usd
            t = x.mean() / (x.std(ddof=1) / len(x) ** 0.5)
            daily = tr.groupby("day").usd.sum().sort_index().cumsum()
            dd = (daily - daily.cummax()).min()
            both = len(set(m.day) & set(h.day)) if "+" in name else 0
            per = {y: tr[tr.year == y].usd.sum() for y in years}
            f_, h_ = sum(per[y] for y in fit), sum(per[y] for y in hold)
            L.append(f"| {name} | {label} | " + " | ".join(f"{per[y]:+,.0f}" for y in years) +
                     f" | {f_:+,.0f} | {h_:+,.0f} | {x.sum():+,.0f} | {len(x)} | {100 * (x > 0).mean():.0f}% | {t:.2f} | {dd:,.0f} | "
                     f"{both if both else '-'} | {x.sum() / 100 / months:+,.2f} |")
            print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
