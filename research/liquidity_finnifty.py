"""Liquidity 15+5 (the arm's rules) on FINNIFTY with real option prices from the owner's upload (research/finnifty_upload.py).

    python research/liquidity_finnifty.py <finnifty_index.parquet> <finnifty_data folder> [out.md]

The chart is continuous (index minutes from 2024-02, then a put-call-parity index for March 2026), so levels are warmed
up; trades count only on days with an option chain (>= 40 strikes quoted). 1 lot of 65, 0.5 slippage a side, Rs 40 a
round trip, ATM strike (50 apart) at the next minute's open.
"""
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import liquidity_break as lb  # noqa: E402
from finnifty_upload import load_days  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

lb.LOT = 65


def main():
    days, cr, rate = load_days(sys.argv[1], sys.argv[2])
    for d in days:
        if len(d["chain"]) < 40:
            d["chain"] = {}
    opt_days = [d["day"] for d in days if d["chain"]]
    L = [f"### FINNIFTY, real option prices: {len(opt_days)} days with a chain "
         f"({opt_days[0]} .. {opt_days[-1]}, 4 expiries)", "",
         f"Synthetic index for March 2026 from put-call parity, less a {100 * rate:.1f}%/yr carry measured on "
         f"{len(cr)} days that have both (median gap {cr['diff'].median():.0f} pts before the carry).", "",
         "| books, premium stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot of 65) | 1st / 2nd half | green months |",
         "|---|---|---|---|---|---|---|---|---|"]
    res = {}
    for tf in (15, 5):
        b = lb.bars(days, tf)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        for ps in (None, 0.15):
            tr = lb.simulate(days, b, zones, "both", True, prem_stop=ps)
            res[(tf, ps)] = tr[np.isfinite(tr.rs)] if not tr.empty else tr
    for ps in (None, 0.15):
        for tf in (15, 5):
            L.append(lb.row(f"{tf}-min, {'15% stop' if ps else 'no premium stop'}", res[(tf, ps)], opt_days))
        tr = pd.concat([res[(15, ps)], res[(5, ps)]])
        L.append(lb.row(f"**15+5 together, {'15% stop' if ps else 'no premium stop'}**", tr, opt_days))
    tr = pd.concat([res[(15, 0.15)], res[(5, 0.15)]])
    L += ["", "By expiry month (15+5, 15% stop): " + ", ".join(
        f"{m} Rs {v:+,.0f} ({n} trades)" for m, v, n in
        tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").rs.agg(["sum", "count"]).reset_index().values),
          "Exits: " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in tr.why.value_counts(normalize=True).items())]
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
