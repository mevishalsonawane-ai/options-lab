"""On BANKNIFTY expiry days, buy the option expiring THAT day (0 DTE) instead of the next expiry (what the arms do
today)? Same signals, same exits; only the contract changes. 11 monthly expiry days with same-day option prices
(expiry_days.parquet, Feb 2025 - Jan 2026); the charts and levels come from the full year file.

    python research/expiry_same_day.py <banknifty_year_wide.parquet> <expiry_days.parquet> [out.md]
"""
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import arms_long as al  # noqa: E402
import profit_lock as pl  # noqa: E402
from ml_long import grid  # noqa: E402
from sell_levels import load  # noqa: E402
from liquidity_break import bars, simulate  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LADDER = pl.LADDERS["25% -> BE, 50% -> lock 25%, 75% -> lock 50%"]


def same_day(exp_path):
    e = pd.read_parquet(exp_path)
    e = e[(e.underlying == "BANKNIFTY") & (e.right != "IX")]
    e["ts"] = pd.to_datetime(e.ts)
    e["d"] = e.ts.dt.date
    e = e[pd.to_datetime(e.expiry).dt.date == e.d]
    return {d: g for d, g in e.groupby("d")}


def main():
    year, exp_path = sys.argv[1], sys.argv[2]
    sd = same_day(exp_path)
    L = ["## Expiry day: same-day option vs the next expiry (research/expiry_same_day.py)", "",
         f"BANKNIFTY, {len(sd)} monthly expiry days with same-day option prices ({min(sd)} .. {max(sd)}). Same signals and "
         "exits; only the option bought changes. 1 lot of 30, after costs.", "",
         "| arm | trades | next expiry (today's rule): net, per trade | same-day expiry: net, per trade |", "|---|---|---|---|"]
    # Liquidity 15+5 (with the index + time stop): swap the chain on expiry days.
    days = load(year)
    alt = []
    for d in days:
        d2 = dict(d)
        if d["day"] in sd:
            ch = {}
            for (k, r), s in sd[d["day"]].groupby(["strike", "right"]):
                if len(s) > 150:
                    ch[(float(k), r)] = grid(s.sort_values("ts").set_index("ts"), ["open", "high", "low", "close"])
            d2["chain"] = ch
        alt.append(d2)
    for name, ds in (("next", days), ("same", alt)):
        res = []
        for tf in (15, 5):
            b = bars(ds, tf)
            z = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            res.append(simulate(ds, b, z, "both", True, prem_stop=0.15, ix_buffer=30, time_stop=(20, 0.05)))
        t = pd.concat(res)
        t = t[t.day.isin(set(sd))]
        if name == "next":
            liq_next = t
        else:
            liq_same = t
    L.append(f"| Liquidity 15+5 (index + time stop) | {len(liq_next)} / {len(liq_same)} | Rs {liq_next.rs.sum():+,.0f}, "
             f"{liq_next.rs.mean():+,.0f} | Rs {liq_same.rs.sum():+,.0f}, {liq_same.rs.mean():+,.0f} |")
    # The ORB family (with the profit lock): legs from the same-day chain on expiry days.
    adays = al.load("file:" + year)
    alt_days = []
    for day, b, legs in adays:
        if day in sd:
            ref = b.between_time("09:20", "09:20").close
            spot = ref.iloc[0] if len(ref) else b.close.iloc[0]
            ch, new = sd[day], {}
            for right in ("CE", "PE"):
                s = ch[ch.right == right]
                ks = s.strike.unique()
                k = ks[np.argmin(np.abs(ks - spot))]
                new[right] = s[s.strike == k].sort_values("ts").set_index("ts")[["open", "high", "low", "close"]]
            alt_days.append((day, b, new))
        else:
            alt_days.append((day, b, legs))
    for arm in ("ORB", "ORB Fresh", "ORB Sweep", "Range Fade"):
        a = pl.run(adays, arm, LADDER); a = a[a.day.isin(set(sd))]
        s = pl.run(alt_days, arm, LADDER); s = s[s.day.isin(set(sd))]
        L.append(f"| {arm} (profit lock) | {len(a)} / {len(s)} | Rs {a.net.sum():+,.0f}, {a.net.mean():+,.0f} | "
                 f"Rs {s.net.sum():+,.0f}, {s.net.mean():+,.0f} |")
        print(L[-1], flush=True)
    print("\n".join(L))
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write("\n".join(L))


if __name__ == "__main__":
    main()
