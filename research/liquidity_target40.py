"""Liquidity 15+5 (BANKNIFTY 15-min + 5-min books, 15% premium stop) with a fixed +40 premium target like the ORB
arms, with and without the profit-lock ladder (25% of 40 -> price paid, 50% -> +10, 75% -> +20).

    python research/liquidity_target40.py <year A file> <year B file> [out.md]
"""
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from liquidity_break import bars, row, simulate  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

VARIANTS = [("the arm today (no fixed target)", dict()),
            ("+40 target", dict(prem_target=40, lock_ref=False)),
            ("+40 target + profit lock", dict(prem_target=40)),
            ("+40 target + profit lock, no 15% stop", dict(prem_target=40, prem_stop=None)),
            ("+40 target, -40 stop (exactly the ORB's), + profit lock", dict(prem_target=40, prem_stop="40pts"))]


def main():
    L = ["## Liquidity 15+5 with a +40 target and the profit lock (research/liquidity_target40.py)", "",
         "BANKNIFTY, 15-min + 5-min books, real option prices, 1 lot of 30, after costs. The arm's other exits stay "
         "(next liquidity, failed break, new liquidity, 15:10).", ""]
    for path in sys.argv[1:3]:
        days = load(path)
        alld = [d["day"] for d in days]
        L += [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
              "| version | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |",
              "|---|---|---|---|---|---|---|---|---|"]
        res = {}
        for tf in (15, 5):
            b = bars(days, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            for name, kw in VARIANTS:
                kw = dict(kw)
                ps = kw.pop("prem_stop", 0.15)
                if ps == "40pts":
                    ps, kw["abs_stop"] = None, 40
                res[(tf, name)] = simulate(days, b, zones, "both", True, prem_stop=ps, **kw)
        for name, _ in VARIANTS:
            tr = pd.concat([res[(15, name)], res[(5, name)]])
            L.append(row(name, tr, alld) + (" " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in tr.why.value_counts(normalize=True).head(4).items())))
            print(L[-1], flush=True)
        L.append("")
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
