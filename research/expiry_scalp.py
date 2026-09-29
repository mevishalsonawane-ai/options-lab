"""The owner's style on EXPIRY-DAY options: read the direction, buy, take +10% (3:1 stop or none), repeat all day.

    python research/expiry_scalp.py <expiry_days.parquet> [out.md]

Every expiry day (BANKNIFTY monthly, NIFTY weekly), from 09:30 to 14:30: when flat, read the direction at the minute
(rules below), buy the option nearest the money whose lot fits the capital, exit at the target / stop / 15:10,
then look again from the next minute. Coin-flip direction run 20 times shows how much is luck.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from reversal_setup import load, LOTS, SLIP, CHG, CUT  # noqa: E402

RULES = ["follow last 5 min", "follow last 15 min", "follow the day so far", "fade last 15 min", "coin flip"]


def direction(I, t, rule, rng):
    c = I["close"]
    if rule == "follow last 5 min":
        return np.sign(c[t - 1] - c[t - 6])
    if rule == "follow last 15 min":
        return np.sign(c[t - 1] - c[t - 16])
    if rule == "follow the day so far":
        return np.sign(c[t - 1] - I["open"][0])
    if rule == "fade last 15 min":
        return -np.sign(c[t - 1] - c[t - 16])
    return 1 if rng.random() < 0.5 else -1


def one(d, t, sign, capital, tgt, stop):
    right = "CE" if sign > 0 else "PE"
    lot = LOTS[d["u"]]
    spot = d["I"]["close"][t - 1]
    best = None
    for (k, r), leg in d["chain"].items():
        p = leg["open"][t]
        if r == right and p > 3 and p * lot <= capital and (best is None or abs(k - spot) < abs(best[0] - spot)):
            best = (k, leg)
    if best is None:
        return None
    leg = best[1]
    e = leg["open"][t] + SLIP
    for m in range(t, CUT + 1):
        if stop and leg["low"][m] <= e * (1 - stop):
            return (e * (1 - stop) - SLIP - e) * lot - CHG, m, e * lot
        if leg["high"][m] >= e * (1 + tgt):
            return (e * (1 + tgt) - SLIP - e) * lot - CHG, m, e * lot
    return (leg["close"][CUT] - SLIP - e) * lot - CHG, CUT, e * lot


def run(days, rule, capital, tgt, stop, per_day, seed=0):
    rng = np.random.default_rng(seed)
    out = []
    for d in days:
        t, n = 15, 0                                             # 09:30
        while t <= 315 and n < per_day:                          # until 14:30
            s = direction(d["I"], t, rule, rng)
            if s == 0:
                t += 1
                continue
            r = one(d, t, int(s), capital, tgt, stop)
            if r is None:
                break
            out.append(dict(u=d["u"], day=d["day"], net=r[0], cost=r[2]))
            n += 1
            t = r[1] + 1
    return pd.DataFrame(out, columns=["u", "day", "net", "cost"])


def line(tr, label):
    if tr.empty:
        return f"| {label} | 0 | | | | | |"
    w, l = tr.net[tr.net > 0], tr.net[tr.net <= 0]
    days = sorted(tr.day.unique())
    half = set(days[: len(days) // 2])
    return (f"| {label} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {w.mean() if len(w) else 0:,.0f} | "
            f"{l.mean() if len(l) else 0:,.0f} | {tr.net.sum():,.0f} | "
            f"{tr[tr.day.isin(half)].net.sum():,.0f} / {tr[~tr.day.isin(half)].net.sum():,.0f} |")


def main():
    days = load(sys.argv[1], "expiry")
    head = "| direction rule | trades | win | avg win Rs | avg loss Rs | net Rs | 1st / 2nd half |"
    sep = "|---|---|---|---|---|---|---|"
    out = ["## The owner's style on EXPIRY-DAY options (buy, +10%, repeat)", ""]
    for u, capital in (("BANKNIFTY", 5000), ("NIFTY", 5000), ("NIFTY", 15000)):
        du = [d for d in days if d["u"] == u]
        for tgt, stop, name in ((0.10, 0.10 / 3, "+10% / -3.3% (3:1)"), (0.10, None, "+10%, no stop (15:10 at the latest)")):
            for per_day, pd_name in ((1, "1 trade a day"), (5, "up to 5 trades a day")):
                out += [f"### {u} ({len(du)} expiry days), capital Rs {capital:,}, {name}, {pd_name}", "", head, sep]
                for rule in RULES[:-1]:
                    out.append(line(run(du, rule, capital, tgt, stop, per_day), rule))
                nets = [run(du, "coin flip", capital, tgt, stop, per_day, seed=s) for s in range(20)]
                sums = [x.net.sum() for x in nets]
                wins = np.mean([(x.net > 0).mean() for x in nets if len(x)])
                out += [f"| coin flip x20 | {int(np.mean([len(x) for x in nets]))} | {100 * wins:.0f}% | | | "
                        f"median {np.median(sums):,.0f} (range {min(sums):,.0f} .. {max(sums):,.0f}) | |", ""]
                print("\n".join(out[-9:]), flush=True)
    text = "\n".join(out)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
