"""All the chart findings combined into one strategy, layer by layer, on the year (wide-strike file).

    python research/combined.py <banknifty_year_wide.parquet> [out.md]

Trigger  a 15-minute candle (09:15 .. 14:15 start) that is BIG (body in the top 20% of the previous 20 days) and WIDE
         (range >= 1.2x the average range of the previous 20 fifteen-minute candles). Its colour is the direction.
Pullback within 60 minutes after it, on a 5-minute close, the price has come back at least 40% of the candle's range
         toward its low (green; high for red) WITHOUT breaking that level.
Stretch  (optional) RSI(2) on 5-minute closes <= 10 (green; >= 90 for red), or the close beyond the day's VWAP -/+ 1
         standard deviation band.
IV       (optional) implied volatility (ATM straddle) at the candle below the median of the previous 20 days.
Trade    buy the ATM option (nearest strike, the day's nearest expiry after the day) at the next minute's open +0.5;
         stop = the INDEX trading through the candle's low / high; target = the index moving k x that risk the right
         way; 15:10 at the latest; exit at the option's close in that minute -0.5; Rs 40 a trip; 1 lot of 30; max 2 a day.
Also reported: the result in index risk units (R) before any option cost - is there a directional edge at all?
Control  the same pullback entry after ORDINARY 15-minute candles (not big).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load, CUT  # noqa: E402
from ml_long import N  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0


def prepare(days):
    for i, d in enumerate(days):
        I = d["I"]
        c5 = I["close"][4::5]
        dd = np.diff(c5, prepend=c5[0])
        up = pd.Series(np.clip(dd, 0, None)).ewm(alpha=1 / 2, adjust=False).mean()
        dn = pd.Series(np.clip(-dd, 0, None)).ewm(alpha=1 / 2, adjust=False).mean()
        d["rsi2"] = (100 - 100 / (1 + up / dn.replace(0, 1e-9))).values
        tp = (I["high"] + I["low"] + I["close"]) / 3
        n = np.arange(1, N + 1)
        vw = np.cumsum(tp) / n
        d["vw"] = vw
        d["vsd"] = np.sqrt(np.maximum(np.cumsum(tp ** 2) / n - vw ** 2, 0))
        # IV each minute from the ATM straddle
        ks = np.array(sorted({k for k, r in d["chain"]}))
        T = max((d["exp"] - d["day"]).days, 0.5) / 365
        iv = np.full(N, np.nan)
        for m in range(0, N, 5):
            s = I["close"][m]
            k = ks[np.argmin(np.abs(ks - s))]
            if (k, "CE") in d["chain"] and (k, "PE") in d["chain"]:
                iv[m] = (d["chain"][(k, "CE")]["close"][m] + d["chain"][(k, "PE")]["close"][m]) / (0.8 * s * np.sqrt(T)) * 100
        d["iv"] = pd.Series(iv).ffill().bfill().values
        d["ks"] = ks
    for i, d in enumerate(days):
        prev = [x for dd in days[max(0, i - 20):i] for x in dd["c15"]]
        d["iv_med"] = np.nanmedian([np.nanmedian(dd["iv"]) for dd in days[max(0, i - 20):i]]) if i >= 5 else np.nan
        seq = prev[-20:]
        d["atr0"] = np.mean([x["h"] - x["l"] for x in seq]) if len(seq) == 20 else np.nan


def candles(d):
    """15-minute candles with the trailing ATR (previous 20 candles, running into the day)."""
    hist = []
    out = []
    atr = d["atr0"]
    for c in d["c15"]:
        out.append(dict(c, atr=atr))
        hist.append(c["h"] - c["l"])
        atr = atr + ((c["h"] - c["l"]) - atr) / 20 if not np.isnan(atr) else atr
    return out


def run(days, big=True, wide=1.2, depth=0.4, stretch=None, iv=False, k=2.0, control=False, max_day=2):
    out = []
    for d in days:
        if np.isnan(d["atr0"]):
            continue
        I, busy, n = d["I"], -1, 0
        for c in candles(d):
            if c["s"] > 300 or n >= max_day:
                continue
            body = c["c"] - c["o"]
            rng = c["h"] - c["l"]
            is_big = abs(body) >= d["big"] and (wide is None or rng >= wide * c["atr"])
            if (is_big and control) or (not is_big and not control) or body == 0:
                continue
            if control and abs(body) < 0.5 * np.median([abs(x["c"] - x["o"]) for x in d["c15"]]):
                continue
            sign = 1 if body > 0 else -1
            lvl = c["l"] if sign > 0 else c["h"]
            if iv and not (d["iv"][c["e"] - 1] < d["iv_med"]):
                continue
            entry = None
            for m in range(c["e"], min(c["e"] + 60, 330)):
                if m <= busy:
                    continue
                if (sign > 0 and I["low"][m] <= lvl) or (sign < 0 and I["high"][m] >= lvl):
                    break                                        # level broken before any entry
                if m % 5 != 4:
                    continue
                cl = I["close"][m]
                pulled = (c["c"] - cl) >= depth * rng if sign > 0 else (cl - c["c"]) >= depth * rng
                if not pulled:
                    continue
                if stretch == "rsi2":
                    r = d["rsi2"][m // 5]
                    if not ((sign > 0 and r <= 10) or (sign < 0 and r >= 90)):
                        continue
                if stretch == "vwap":
                    if not ((sign > 0 and cl <= d["vw"][m] - d["vsd"][m]) or (sign < 0 and cl >= d["vw"][m] + d["vsd"][m])):
                        continue
                if stretch == "either":
                    r = d["rsi2"][m // 5]
                    band = (sign > 0 and cl <= d["vw"][m] - d["vsd"][m]) or (sign < 0 and cl >= d["vw"][m] + d["vsd"][m])
                    if not ((sign > 0 and r <= 10) or (sign < 0 and r >= 90) or band):
                        continue
                entry = m + 1
                break
            if entry is None or entry >= CUT:
                continue
            e_ix = I["close"][entry - 1]
            risk = abs(e_ix - lvl)
            tgt = e_ix + sign * k * risk
            right = "CE" if sign > 0 else "PE"
            kk = d["ks"][np.argmin(np.abs(d["ks"] - e_ix))]
            leg = d["chain"].get((kk, right))
            if leg is None:
                continue
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
            out.append(dict(day=d["day"], R=R, net=(leg["close"][x] - SLIP - ep) * LOT - CHG, risk=risk, sign=sign))
            n += 1
            busy = x
    return pd.DataFrame(out, columns=["day", "R", "net", "risk", "sign"])


def line(tr, alld, label):
    if tr.empty:
        return f"| {label} | 0 | | | | | | |"
    half = set(alld[: len(alld) // 2])
    t = tr.net.mean() / (tr.net.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else float("nan")
    return (f"| {label} | {len(tr)} | {100 * (tr.R > 0).mean():.0f}% | {tr.R.mean():+.2f} | {tr.risk.median():.0f} | "
            f"{100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} | {t:.2f} | "
            f"{tr[tr.day.isin(half)].net.sum():,.0f} / {tr[~tr.day.isin(half)].net.sum():,.0f} |")


def main():
    days = load(sys.argv[1])
    prepare(days)
    alld = [d["day"] for d in days]
    head = ("| layers | trades | index went our way | avg R (index, before costs) | median risk (pts) | option win | "
            "net Rs | t | 1st / 2nd half |")
    sep = "|---|---|---|---|---|---|---|---|---|"
    out = [f"## All chart findings combined, BANKNIFTY {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
           "R = the index result in units of the risk (candle level to entry): +2 = target, -1 = stop. An average R above 0 "
           "means the index itself moved our way more than against; the option columns add premium, decay and costs.", "",
           head, sep]
    steps = [
        ("big candle only (no width rule), pullback 40%", dict(wide=None)),
        ("+ wide (>= 1.2x ATR)", dict()),
        ("+ wide, pullback 25%", dict(depth=0.25)),
        ("+ wide, pullback 60%", dict(depth=0.6)),
        ("+ wide + RSI(2) stretch", dict(stretch="rsi2")),
        ("+ wide + VWAP band stretch", dict(stretch="vwap")),
        ("+ wide + RSI(2) or VWAP band", dict(stretch="either")),
        ("+ wide + low IV", dict(iv=True)),
        ("+ wide + low IV + RSI(2) or VWAP band", dict(iv=True, stretch="either")),
        ("+ wide + low IV, target 1.5R", dict(iv=True, k=1.5)),
        ("+ wide + low IV, target 3R", dict(iv=True, k=3.0)),
        ("CONTROL: ordinary candles, same pullback entry", dict(control=True, wide=None)),
        ("CONTROL: ordinary candles, low IV", dict(control=True, wide=None, iv=True)),
    ]
    for label, kw in steps:
        out.append(line(run(days, **kw), alld, label))
        print(out[-1], flush=True)
    text = "\n".join(out)
    print("\n" + text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
