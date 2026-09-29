"""Non-directional option selling on BANKNIFTY: no direction call at all - earn the time decay, both years.

    python research/nondirectional.py <prev_year_wide.parquet> <year_wide.parquet> [out.md]

Structures (nearest expiry after the day, strikes from the index at entry, 1 lot of 30 on every leg):
  short straddle   sell the ATM call + put (unlimited risk; margin ~Rs 1.5-2 lakh)
  iron fly W       sell the ATM call + put, buy the call W above and the put W below (max loss = W - credit)
  iron condor      sell the call +D and put -D, buy +D+W / -D-W
Timing: INTRADAY (sell 09:20, buy back 15:10) or OVERNIGHT (sell 15:15, buy back at 09:30 / 15:10 next day, same
expiry only). Stop: buy everything back when the position's loss reaches S x the credit (checked every minute).
Costs: 0.5 slippage per leg per side, Rs 20 brokerage per order + taxes ~ Rs 50 per leg round trip.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402

LOT, SLIP, LEGCOST = 30, 0.5, 50.0
M920, M1510, M1515, M930 = 5, 355, 360, 15


def legs_for(d, spot, kind, W, D):
    ks = np.array(sorted({k for k, r in d["chain"]}))
    atm = ks[np.argmin(np.abs(ks - spot))]
    if kind == "straddle":
        return [(atm, "CE", -1), (atm, "PE", -1)]
    if kind == "fly":
        return [(atm, "CE", -1), (atm, "PE", -1), (atm + W, "CE", 1), (atm - W, "PE", 1)]
    return [(atm + D, "CE", -1), (atm - D, "PE", -1), (atm + D + W, "CE", 1), (atm - D - W, "PE", 1)]


def value(chain, legs, m, field="close"):
    return sum(q * chain[(k, r)][field][m] for k, r, q in legs)


def trade(days, i, kind, W, D, stop, when):
    d = days[i]
    m0 = M920 if when == "intraday" else M1515
    spot = d["I"]["close"][m0 - 1]
    legs = legs_for(d, spot, kind, W, D)
    if any((k, r) not in d["chain"] for k, r, _ in legs):
        return None
    # credit received (sell at open - slip, buy wings at open + slip)
    credit = -sum(q * d["chain"][(k, r)]["open"][m0] for k, r, q in legs) - SLIP * len(legs)
    if credit <= 0:
        return None
    cost = LEGCOST * len(legs)
    path = [(d, range(m0, M1510 + 1))] if when == "intraday" else []
    if when != "intraday":
        if i + 1 >= len(days):
            return None
        d2 = days[i + 1]
        if d2["exp"] != d["exp"] or any((k, r) not in d2["chain"] for k, r, _ in legs):
            return None
        path = [(d, range(m0 + 1, 375)), (d2, range(0, (M930 if when == "overnight to 09:30" else M1510) + 1))]
    last = None
    for dd, rng in path:
        for m in rng:
            # cost to close now (buy back shorts at high-ish, sell wings): use the minute's worst side for the stop
            worst = sum(q * dd["chain"][(k, r)]["high" if q < 0 else "low"][m] for k, r, q in legs)
            if stop and (-worst) - credit >= stop * credit:           # loss reached S x credit
                close_val = -worst + SLIP * len(legs)
                return (credit - close_val) * LOT - cost, credit, "stop"
            last = (dd, m)
    dd, m = last
    close_val = -value(dd["chain"], legs, m) + SLIP * len(legs)
    return (credit - close_val) * LOT - cost, credit, "time"


def run(days, years, kind, W=500, D=300, stop=None, when="intraday"):
    out = []
    for i, d in enumerate(days):
        r = trade(days, i, kind, W, D, stop, when)
        if r is None:
            continue
        out.append(dict(day=d["day"], year=years[i], net=r[0], credit=r[1], why=r[2]))
    return pd.DataFrame(out, columns=["day", "year", "net", "credit", "why"])


def line(tr, label):
    cells = [label]
    for yr in ("Feb24-Feb25", "Feb25-Feb26"):
        x = tr[tr.year == yr]
        if len(x) < 5:
            cells.append("-")
            continue
        eq = x.net.cumsum()
        cells.append(f"{len(x)} / {100 * (x.net > 0).mean():.0f}% / Rs {x.net.sum():,.0f} / worst day {x.net.min():,.0f} / "
                     f"drawdown {(eq - eq.cummax()).min():,.0f}")
    both = tr.net
    t = both.mean() / (both.std(ddof=1) / np.sqrt(len(both))) if len(both) > 2 else float("nan")
    cells.append(f"{t:.2f}")
    return "| " + " | ".join(cells) + " |"


def main():
    paths = [a for a in sys.argv[1:] if a.endswith(".parquet")]
    out_md = next((a for a in sys.argv[1:] if a.endswith(".md")), None)
    days, years = [], []
    for yi, p in enumerate(paths):
        ds = load(p)
        days += ds
        years += [("Feb24-Feb25" if yi == 0 else "Feb25-Feb26")] * len(ds)
    head = ("| strategy | Feb24-Feb25: trades / win / net / worst day / drawdown | Feb25-Feb26: same | t (both years) |")
    sep = "|---|---|---|---|"
    out = ["## Non-directional option selling - no direction call, both years", "",
           "1 lot of 30 on every leg, after slippage and ~Rs 50 per leg in charges. t above ~2 = unlikely to be luck.", ""]
    for when in ("intraday", "overnight to 09:30", "overnight to 15:10"):
        out += [f"### {when.upper()}", "", head, sep]
        for kind, W, D, name in (("straddle", 0, 0, "short straddle (ATM, naked)"),
                                 ("fly", 500, 0, "iron fly, wings 500"), ("fly", 800, 0, "iron fly, wings 800"),
                                 ("condor", 300, 300, "iron condor, short +-300, wings 300"),
                                 ("condor", 400, 400, "iron condor, short +-400, wings 400")):
            for stop in (None, 0.3, 0.5, 1.0):
                if kind == "straddle" and stop is None and when != "intraday":
                    pass
                label = f"{name}, " + (f"stop at {int(stop * 100)}% of credit" if stop else "no stop")
                out.append(line(run(days, years, kind, W, D, stop, when), label))
                print(out[-1], flush=True)
        out.append("")
    text = "\n".join(out)
    if out_md:
        open(out_md, "w").write(text)


if __name__ == "__main__":
    main()
