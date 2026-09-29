"""What the options do during 5-minute BANKNIFTY candles over 50 points, and a straddle on the clusters.

    python research/big_bar_options.py <year_wide.parquet> [<another year> ...]

Options: the ATM call and put at the candle's opening minute (nearest strike, the day's nearest expiry after the day),
real minute prices. Straddle test: when a 5-minute candle closes with a body over 50 points (a cluster has started),
buy the ATM call AND put at the next minute's open (+0.5 each), exit after 1 / 3 / 6 candles or when the pair is up
+5% / +10%, 15:10 at the latest; Rs 80 a round trip (two options), 1 lot each of 30.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load, CUT  # noqa: E402

LOT, SLIP = 30, 0.5


def atm(d, m):
    ks = d["ks"]
    k = ks[np.argmin(np.abs(ks - d["I"]["open"][m]))]
    return d["chain"].get((k, "CE")), d["chain"].get((k, "PE"))


def during(days):
    rows = []
    for d in days:
        d["ks"] = np.array(sorted({k for k, r in d["chain"]}))
        I = d["I"]
        for s in range(0, 370, 5):
            ce, pe = atm(d, s)
            if ce is None or pe is None:
                continue
            body = I["close"][s + 4] - I["open"][s]
            c0, c1, p0, p1 = ce["open"][s], ce["close"][s + 4], pe["open"][s], pe["close"][s + 4]
            if min(c0, p0) < 5:
                continue
            rows.append(dict(day=d["day"], s=s, body=body, ce=(c1 / c0 - 1) * 100, pe=(p1 / p0 - 1) * 100,
                             ce_pts=c1 - c0, pe_pts=p1 - p0, st=((c1 + p1) / (c0 + p0) - 1) * 100, prem=c0))
    return pd.DataFrame(rows)


def straddle(days, hold, tgt=None):
    out = []
    for d in days:
        I = d["I"]
        busy = -1
        for s in range(0, 360, 5):
            e = s + 5
            if e <= busy or e >= CUT:
                continue
            if abs(I["close"][s + 4] - I["open"][s]) <= 50:
                continue
            ce, pe = atm(d, e)
            if ce is None or pe is None:
                continue
            cost = ce["open"][e] + pe["open"][e] + 2 * SLIP
            x = min(e + 5 * hold, CUT)
            if tgt:
                for m in range(e, x + 1):
                    if ce["high"][m] + pe["low"][m] >= cost * (1 + tgt) or ce["low"][m] + pe["high"][m] >= cost * (1 + tgt):
                        x = m
                        break
                val = cost * (1 + tgt) if x < min(e + 5 * hold, CUT) else ce["close"][x] + pe["close"][x] - 2 * SLIP
            else:
                val = ce["close"][x] + pe["close"][x] - 2 * SLIP
            out.append(dict(day=d["day"], net=(val - cost) * LOT - 80))
            busy = x
    return pd.DataFrame(out, columns=["day", "net"])


def main():
    out = ["## Options during 5-minute candles over 50 points", ""]
    for path in sys.argv[1:]:
        days = load(path)
        tag = os.path.basename(path).replace("_wide.parquet", "")
        r = during(days)
        out += [f"### {tag}: {r.day.nunique()} days", "",
                "**What the ATM call and put did inside the candle (medians)**", "",
                "| candle | count | index move | call % | call pts | put % | put pts | call + put (straddle) % | ATM call premium |",
                "|---|---|---|---|---|---|---|---|---|"]
        for name, m in (("big green (body > +50)", r.body > 50), ("big red (body < -50)", r.body < -50),
                        ("green 25-50", r.body.between(25, 50)), ("red 25-50", r.body.between(-50, -25)),
                        ("small (|body| < 25)", r.body.abs() < 25)):
            x = r[m]
            out.append(f"| {name} | {len(x)} | {x.body.median():+.0f} | {x.ce.median():+.1f}% | {x.ce_pts.median():+.1f} | "
                       f"{x.pe.median():+.1f}% | {x.pe_pts.median():+.1f} | {x.st.median():+.2f}% | {x.prem.median():.0f} |")
        big = r[r.body.abs() > 50]
        right = np.where(big.body > 0, big.ce, big.pe)
        wrong = np.where(big.body > 0, big.pe, big.ce)
        out += ["", f"If you had bought the RIGHT option at the big candle's open: median {np.median(right):+.1f}% "
                f"({100 * (right >= 10).mean():.0f}% of them reached +10% by the candle's close). The WRONG one: "
                f"median {np.median(wrong):+.1f}% ({100 * (wrong <= -10).mean():.0f}% lost 10% or more).", "",
                "**Straddle on the cluster (buy call + put after a >50-pt candle)**", "",
                "| exit | trades | win | net Rs (1 lot each) | per trade |", "|---|---|---|---|---|"]
        for hold, tgt, name in ((1, None, "after 1 candle"), (3, None, "after 3 candles"), (6, None, "after 6 candles"),
                                (6, 0.05, "+5% on the pair, else 6 candles"), (6, 0.10, "+10% on the pair, else 6 candles")):
            tr = straddle(days, hold, tgt)
            out.append(f"| {name} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} | {tr.net.mean():,.0f} |")
        out.append("")
        print("\n".join(out[-24:]), flush=True)
    open("research/BIG_BAR_OPTIONS.md", "w").write("\n".join(out) + "\n")


if __name__ == "__main__":
    main()
