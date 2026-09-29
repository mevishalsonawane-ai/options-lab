"""The owner's manual method, on the year (wide-strike file).

    python research/manual_style.py <banknifty_year_wide.parquet> [out.md]

1. read the direction from the recent candles (several mechanical versions of "which way are the candles going")
2. buy the CE (up) or PE (down) of an expiry that is NOT this week's (entries skipped within 7 days of expiry;
   BANKNIFTY has monthly expiries only), at the strike closest to the money whose one lot fits the capital
3. sell when the option is up 10% (a resting limit at +10%); no stop in the owner's version (held up to 5 trading
   days, then out at 15:25), plus versions with a -10 / -20 / -30% stop
One position at a time; entry at the next minute's open +0.5; exits -0.5; Rs 40 a trip; lot 30.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0
LAST = 15 * 60 + 25 - (9 * 60 + 15)
TARGET = 0.10                     # take profit at +10% of the premium


def direction(d, t, rule, rng):
    I = d["I"]
    c = I["close"]
    if rule == "follow last 15 min":
        return np.sign(c[t - 1] - c[t - 16])
    if rule == "follow last 5-min candle":
        s = (t // 5 - 1) * 5
        return np.sign(c[s + 4] - I["open"][s])
    if rule == "follow the day so far":
        return np.sign(c[t - 1] - I["open"][0])
    if rule == "fade last 15 min":
        return -np.sign(c[t - 1] - c[t - 16])
    return 1 if rng.random() < 0.5 else -1


def pick(d, t, sign, capital):
    right = "CE" if sign > 0 else "PE"
    spot = d["I"]["close"][t - 1]
    best = None
    for (k, r), leg in d["chain"].items():
        if r != right:
            continue
        p = leg["open"][t]
        if not (p > 5) or p * LOT > capital:
            continue
        if best is None or abs(k - spot) < abs(best[0] - spot):
            best = (k, p)
    return best


def trade(days, i, t, sign, capital, stop):
    d = days[i]
    b = pick(d, t, sign, capital)
    if b is None:
        return None
    k, _ = b
    right = "CE" if sign > 0 else "PE"
    e = d["chain"][(k, right)]["open"][t] + SLIP
    tgt, stp = e * (1 + TARGET), (e * (1 - stop) if stop else -1)
    for h in range(0, 5):
        if i + h >= len(days):
            break
        dn = days[i + h]
        if dn["exp"] != d["exp"] or (k, right) not in dn["chain"]:
            break
        leg = dn["chain"][(k, right)]
        start = t if h == 0 else 0
        for m in range(start, LAST + 1):
            if m == start and h > 0:                       # the next morning's gap
                if leg["open"][m] >= tgt:
                    return (leg["open"][m] - SLIP - e) * LOT - CHG, i + h, "target (gap)", e
                if stop and leg["open"][m] <= stp:
                    return (leg["open"][m] - SLIP - e) * LOT - CHG, i + h, "stop (gap)", e
            if stop and leg["low"][m] <= stp:
                return (stp - SLIP - e) * LOT - CHG, i + h, "stop", e
            if leg["high"][m] >= tgt:
                return (tgt - SLIP - e) * LOT - CHG, i + h, "target", e
        last = (leg, i + h)
    leg, j = last
    return (leg["close"][LAST] - SLIP - e) * LOT - CHG, j, "time (5 days)", e


def run(days, rule, entry_min, capital, stop, seed=0):
    rng = np.random.default_rng(seed)
    out, free = [], 0
    for i, d in enumerate(days):
        if i < free or (d["exp"] - d["day"]).days <= 7:
            continue
        sign = direction(d, entry_min, rule, rng)
        if sign == 0:
            continue
        r = trade(days, i, entry_min, int(sign), capital, stop)
        if r is None:
            continue
        pnl, j, why, e = r
        out.append(dict(day=d["day"], net=pnl, why=why, held=j - i, prem=e))
        free = j + 1 if j > i else i + 1
    return pd.DataFrame(out, columns=["day", "net", "why", "held", "prem"])


def line(tr, alld, label, capital):
    if tr.empty:
        return f"| {label} | 0 |"
    half = set(alld[: len(alld) // 2])
    eq = tr.net.cumsum()
    w, l = tr.net[tr.net > 0], tr.net[tr.net <= 0]
    return (f"| {label} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {w.mean():,.0f} | {l.mean():,.0f} | "
            f"{tr.net.min():,.0f} | {tr.net.sum():,.0f} | {100 * tr.net.sum() / capital:+.0f}% | "
            f"{(eq - eq.cummax()).min():,.0f} | {tr[tr.day.isin(half)].net.sum():,.0f} / {tr[~tr.day.isin(half)].net.sum():,.0f} | "
            f"{tr.prem.mean():.0f} | {100 * (tr.why.str.startswith('time')).mean():.0f}% |")


def main():
    days = load(sys.argv[1])
    alld = [d["day"] for d in days]
    head = ("| version | trades | win | avg win Rs | avg loss Rs | worst Rs | net Rs | net vs capital | worst drawdown | "
            "1st / 2nd half | avg premium | out on time |")
    sep = "|---|---|---|---|---|---|---|---|---|---|---|---|"
    out = [f"## The manual method, BANKNIFTY {alld[0]} .. {alld[-1]} ({len(alld)} days, entries on days more than 7 days "
           f"from expiry)", ""]
    rules = ["follow last 15 min", "follow last 5-min candle", "follow the day so far", "fade last 15 min", "coin flip"]
    for capital in (20000, 10000):
        out += [f"### Capital Rs {capital:,} (one lot), entry 10:00, +10% target, NO stop (held up to 5 days)", "", head, sep]
        for rule in rules:
            out.append(line(run(days, rule, 45, capital, None), alld, rule, capital))
            print(out[-1], flush=True)
        out.append("")
    out += ["### Capital Rs 20,000, 'follow last 15 min', +10% target: stops and entry times", "", head, sep]
    for stop in (None, 0.10, 0.20, 0.30):
        for em, tl in ((45, "10:00"), (105, "11:00"), (225, "13:00")):
            label = f"entry {tl}, " + (f"stop -{int(stop * 100)}%" if stop else "no stop")
            out.append(line(run(days, "follow last 15 min", em, 20000, stop), alld, label, 20000))
            print(out[-1], flush=True)
    out += ["", "### Coin flip, 20 different seeds (Rs 20,000, 10:00, no stop) - how much is luck", ""]
    nets = [run(days, "coin flip", 45, 20000, None, seed=s).net.sum() for s in range(20)]
    out.append(f"Net over the year: median Rs {np.median(nets):,.0f}, range Rs {min(nets):,.0f} .. {max(nets):,.0f}.")
    text = "\n".join(out)
    print("\n" + text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
