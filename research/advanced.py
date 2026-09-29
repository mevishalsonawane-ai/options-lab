"""Advanced indicators on the big-candle pullback (the +0.20 R edge in COMBINED.md), on the year.

    python research/advanced.py <banknifty_year_wide.parquet> [out.md]

Base: a big 15-minute candle (top-20% body vs the previous 20 days), then within 60 minutes a 5-minute close 40% of
its range back toward its low (green) / high (red) without breaking it -> trade its direction, stop at the level,
target 2R. Each indicator replaces the entry or filters it:
  FVG          15-minute fair value gap: candle before the big one and candle after it leave a gap (green: low of
               the next candle above the high of the previous one); enter when the price trades back into the gap
  Fib zone     retracement of the swing (the big candle's low -> the highest high since it) between 38.2% and 50%
               (also 50-61.8%, 38.2-61.8%) on a 5-minute close
  AVWAP        anchored VWAP from the big candle's start: at entry the close must still be above it (green)
  BOS          after the 40% pullback, wait for a 5-minute close above the previous 5-minute high (green) - buyers back
  GEX          gamma exposure from the nearest expiry's open interest at 09:30 (calls +, puts -, gamma from the
               ATM implied volatility); days with GEX below the trailing 20-day median ("short gamma", trending) vs above
  Squeeze      TTM squeeze on 15-minute candles (Bollinger 20,2 inside Keltner 20,1.5 ATR) in any of the 3 candles
               before the big one
R = index result in units of the risk (entry to level); option P&L = ATM option, next-minute entry, Rs 40 + 0.5 a side.
"""
from __future__ import annotations

import os
import sys
from math import erf, exp, log, pi, sqrt

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load, CUT  # noqa: E402
from combined import prepare  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0


def gex_by_day(path, days):
    df = pd.read_parquet(path, columns=["day", "right", "expiry", "strike", "ts", "open_interest"])
    df = df[df.right != "IX"]
    df["ts"] = pd.to_datetime(df.ts)
    df["expiry"] = df.expiry.dt.date
    byday = {d["day"]: d for d in days}
    out = {}
    for day, g in df.groupby(df.day.dt.date):
        d = byday.get(day)
        if d is None:
            continue
        g = g[(g.expiry == d["exp"]) & (g.ts.dt.hour * 60 + g.ts.dt.minute <= 9 * 60 + 30)]
        if g.empty:
            continue
        oi = g.sort_values("ts").groupby(["strike", "right"]).open_interest.last()
        S = d["I"]["close"][15]
        sig = max(d["iv"][15], 5) / 100
        T = max((d["exp"] - day).days, 0.5) / 365
        tot = 0.0
        for (k, r), q in oi.items():
            d1 = (log(S / k) + 0.5 * sig * sig * T) / (sig * sqrt(T))
            gam = exp(-0.5 * d1 * d1) / sqrt(2 * pi) / (S * sig * sqrt(T))
            tot += (1 if r == "CE" else -1) * gam * q * S * S * 0.01
        out[day] = tot
    return out


def squeeze_flags(days):
    """TTM squeeze on the continuous 15-minute series: {(day, start): squeeze on}."""
    rows = [(d["day"], c["s"], c["h"], c["l"], c["c"]) for d in days for c in d["c15"]]
    df = pd.DataFrame(rows, columns=["day", "s", "h", "l", "c"])
    m = df.c.rolling(20).mean()
    sd = df.c.rolling(20).std()
    tr = np.maximum(df.h - df.l, np.maximum((df.h - df.c.shift()).abs(), (df.l - df.c.shift()).abs()))
    atr = tr.rolling(20).mean()
    on = (m + 2 * sd < m + 1.5 * atr) & (m - 2 * sd > m - 1.5 * atr)
    prior = on.shift(1).rolling(3).max().fillna(0).astype(bool)
    return {(r.day, r.s): bool(p) for r, p in zip(df.itertuples(), prior)}


def trade_one(d, sign, lvl, entry, k=2.0):
    I = d["I"]
    e_ix = I["close"][entry - 1]
    risk = abs(e_ix - lvl)
    if risk < 5:
        return None
    tgt = e_ix + sign * k * risk
    kk = d["ks"][np.argmin(np.abs(d["ks"] - e_ix))]
    leg = d["chain"].get((kk, "CE" if sign > 0 else "PE"))
    if leg is None:
        return None
    ep = leg["open"][entry] + SLIP
    x, R = CUT, None
    for m in range(entry, CUT + 1):
        if (sign > 0 and I["low"][m] <= lvl) or (sign < 0 and I["high"][m] >= lvl):
            x, R = m, -1.0
            break
        if (sign > 0 and I["high"][m] >= tgt) or (sign < 0 and I["low"][m] <= tgt):
            x, R = m, k
            break
    if R is None:
        R = sign * (I["close"][CUT] - e_ix) / risk
    return R, (leg["close"][x] - SLIP - ep) * LOT - CHG, x, risk


def run(days, mode="base", fib=(0.382, 0.5), avwap=False, gex=None, gex_side=None, sq=None, sq_need=None):
    out = []
    for i, d in enumerate(days):
        if gex_side is not None:
            g = gex.get(d["day"])
            prev = [gex[x["day"]] for x in days[max(0, i - 20):i] if x["day"] in gex]
            if g is None or len(prev) < 10:
                continue
            short = g < np.median(prev)
            if (gex_side == "short") != short:
                continue
        I, busy, n = d["I"], -1, 0
        c15 = d["c15"]
        for j, c in enumerate(c15):
            if c["s"] > 300 or n >= 2:
                continue
            body = c["c"] - c["o"]
            if abs(body) < d["big"] or body == 0:
                continue
            if sq_need is not None and sq.get((d["day"], c["s"]), False) != sq_need:
                continue
            sign = 1 if body > 0 else -1
            lvl = c["l"] if sign > 0 else c["h"]
            rng = c["h"] - c["l"]
            tp = (I["high"] + I["low"] + I["close"]) / 3
            entry = None
            start = c["e"]
            fvg = None
            if mode == "fvg":
                if j == 0 or j + 1 >= len(c15):
                    continue
                pv, nx = c15[j - 1], c15[j + 1]
                if sign > 0 and nx["l"] > pv["h"]:
                    fvg = (pv["h"], nx["l"])
                elif sign < 0 and nx["h"] < pv["l"]:
                    fvg = (nx["h"], pv["l"])
                else:
                    continue
                start = nx["e"]
            pulled_at = None
            for m in range(start, min(c["e"] + 75, 330)):
                if m <= busy:
                    continue
                if (sign > 0 and I["low"][m] <= lvl) or (sign < 0 and I["high"][m] >= lvl):
                    break
                cl = I["close"][m]
                if mode == "fvg":
                    if (sign > 0 and I["low"][m] <= fvg[1]) or (sign < 0 and I["high"][m] >= fvg[0]):
                        entry = m + 1
                        break
                    continue
                if m % 5 != 4:
                    continue
                if mode == "fib":
                    sw = I["high"][c["s"]:m + 1].max() if sign > 0 else I["low"][c["s"]:m + 1].min()
                    span = abs(sw - lvl)
                    r = (sw - cl) / span if sign > 0 else (cl - sw) / span
                    ok = fib[0] <= r <= fib[1]
                else:
                    ok = ((c["c"] - cl) if sign > 0 else (cl - c["c"])) >= 0.4 * rng
                if not ok and pulled_at is None:
                    continue
                if mode == "bos":
                    if pulled_at is None:
                        pulled_at = m
                        continue
                    prev_hi = I["high"][m - 9:m - 4].max() if sign > 0 else I["low"][m - 9:m - 4].min()
                    if not ((sign > 0 and cl > prev_hi) or (sign < 0 and cl < prev_hi)):
                        continue
                if avwap:
                    av = tp[c["s"]:m + 1].mean()
                    if (sign > 0 and cl <= av) or (sign < 0 and cl >= av):
                        continue
                entry = m + 1
                break
            if entry is None or entry >= CUT:
                continue
            r = trade_one(d, sign, lvl, entry)
            if r is None:
                continue
            out.append(dict(day=d["day"], R=r[0], net=r[1], risk=r[3]))
            n += 1
            busy = r[2]
    return pd.DataFrame(out, columns=["day", "R", "net", "risk"])


def line(tr, alld, label):
    if len(tr) < 3:
        return f"| {label} | {len(tr)} | | | | | | | |"
    half = set(alld[: len(alld) // 2])
    R = tr.R
    t = R.mean() / (R.std(ddof=1) / np.sqrt(len(R)))
    m = tr.assign(m=[str(x)[:7] for x in tr.day]).groupby("m").R.mean()
    return (f"| {label} | {len(tr)} | {R.mean():+.2f} | {t:.2f} | {R[tr.day.isin(half)].mean():+.2f} / "
            f"{R[~tr.day.isin(half)].mean():+.2f} | {(m > 0).sum()}/{len(m)} | {tr.risk.median():.0f} | "
            f"{100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} |")


def main():
    path = sys.argv[1]
    days = load(path)
    prepare(days)
    alld = [d["day"] for d in days]
    gex = gex_by_day(path, days)
    sq = squeeze_flags(days)
    head = ("| version | trades | avg R (index) | t of R | R 1st / 2nd half | months R > 0 | median risk pts | "
            "option win | option net Rs |")
    sep = "|---|---|---|---|---|---|---|---|---|"
    out = [f"## Advanced indicators on the big-candle pullback, BANKNIFTY {alld[0]} .. {alld[-1]}", "", head, sep]
    steps = [
        ("BASE: 40% pullback", dict()),
        ("FVG: enter on the return into the 15-min fair value gap", dict(mode="fvg")),
        ("Fib 38.2-50% of the swing", dict(mode="fib", fib=(0.382, 0.5))),
        ("Fib 50-61.8%", dict(mode="fib", fib=(0.5, 0.618))),
        ("Fib 38.2-61.8%", dict(mode="fib", fib=(0.382, 0.618))),
        ("BASE + anchored VWAP holds", dict(avwap=True)),
        ("BASE + break of structure after the pullback", dict(mode="bos")),
        ("BASE on short-gamma days (GEX below its 20-day median)", dict(gex=gex, gex_side="short")),
        ("BASE on long-gamma days (GEX above)", dict(gex=gex, gex_side="long")),
        ("BASE after a TTM squeeze (in the 3 candles before)", dict(sq=sq, sq_need=True)),
        ("BASE without a squeeze before", dict(sq=sq, sq_need=False)),
    ]
    for label, kw in steps:
        out.append(line(run(days, **kw), alld, label))
        print(out[-1], flush=True)
    # does a squeeze predict big candles?
    rows = [(sq.get((d["day"], c["s"]), False), abs(c["c"] - c["o"]) >= d["big"]) for d in days for c in d["c15"]]
    s = pd.DataFrame(rows, columns=["sq", "big"])
    out += ["", f"Does a TTM squeeze predict a big candle? Big-candle rate after a squeeze {100 * s.big[s.sq].mean():.1f}% "
            f"({s.sq.sum()} candles) vs {100 * s.big[~s.sq].mean():.1f}% otherwise."]
    g = pd.Series(gex)
    out += [f"GEX: {len(g)} days, {100 * (g < 0).mean():.0f}% of days net negative (puts dominate)."]
    text = "\n".join(out)
    print("\n" + text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
