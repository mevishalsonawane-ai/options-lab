"""Chandelier Exit Trend Navigator [MarkitTick] (the owner's settings: ATR 7, ATR mult 2, close extremes on, every
filter off) on BANKNIFTY, BUYING options only: a BUY signal buys the ATM CE, a SELL signal buys the ATM PE.

    python research/chandelier_exit.py <year A file> <year B file> [out.md]

Indicator, as the Pine script: atr = ta.atr(7) (Wilder); hh / ll = highest / lowest CLOSE of 7 bars;
ceLong = hh - 2 atr, ceShort = ll + 2 atr; dir = close > ceShort[1] ? 1 : close < ceLong[1] ? -1 : dir[1];
BUY when dir turns 1, SELL when it turns -1 (on a closed bar). Chart continuous across days, like TradingView.
Trading: enter at the next minute's option open + 0.5 slippage, entries 09:20-14:30, one position at a time, out by
15:10, Rs 40 a round trip, 1 lot of 30. Exits tested:
  flip      the opposite signal closes it and buys the other side (the script's close-long / close-short alerts)
  TP1/2/3   the script's trade levels on the index: SL 1 x ATR, target 1R / 2R / 3R (or the opposite signal first)
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from liquidity_break import bars  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0
ATR_LEN, ATR_MULT = 7, 2.0
CUT = 355


def chandelier(b, length=ATR_LEN, mult=ATR_MULT):
    h, l, c, o = b.high.values, b.low.values, b.close.values, b.open.values
    pc = np.r_[c[0], c[:-1]]
    tr = np.maximum(h - l, np.maximum(np.abs(h - pc), np.abs(l - pc)))
    atr = pd.Series(tr).ewm(alpha=1 / length, adjust=False).mean().values
    hh = pd.Series(c).rolling(length).max().values
    ll = pd.Series(c).rolling(length).min().values
    ce_long, ce_short = hh - atr * mult, ll + atr * mult
    d = np.zeros(len(c), dtype=int)
    d[0] = 1 if c[0] >= o[0] else -1
    for i in range(1, len(c)):
        if not np.isnan(ce_short[i - 1]) and c[i] > ce_short[i - 1]:
            d[i] = 1
        elif not np.isnan(ce_long[i - 1]) and c[i] < ce_long[i - 1]:
            d[i] = -1
        else:
            d[i] = d[i - 1]
    sig = np.zeros(len(c), dtype=int)
    sig[1:] = np.where((d[1:] == 1) & (d[:-1] == -1), 1, np.where((d[1:] == -1) & (d[:-1] == 1), -1, 0))
    return sig, atr


def simulate(days, b, sig, atr, mode):
    """mode: 'flip' or an R multiple (1, 2, 3) for the index target with a 1-ATR index stop."""
    DI, S, E = b.di.values, b.s.values, b.e.values
    trades, pos = [], None

    def close_out(d, m, why, ixp=None):
        k = pos["key"]
        px = d["chain"][k]["close"][m] if why not in ("stop", "target") else d["chain"][k]["close"][m]
        trades.append(dict(day=pos["day"], side=pos["side"], why=why, rs=(px - SLIP - pos["px"]) * LOT - CHG,
                           pts=pos["side"] * ((ixp if ixp is not None else d["I"]["close"][m]) - pos["ix"])))

    def open_pos(d, i, side, m):
        I = d["I"]
        right = "CE" if side > 0 else "PE"
        ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
        if not len(ks):
            return None
        ix = I["open"][m]
        k = ks[np.argmin(np.abs(ks - ix))]
        risk = atr[i]
        return dict(day=d["day"], di=DI[i], side=side, m=m, key=(k, right), px=d["chain"][(k, right)]["open"][m] + SLIP,
                    ix=ix, sl=ix - side * risk, tp=None if mode == "flip" else ix + side * risk * mode)

    for i in range(len(b)):
        d = days[DI[i]]
        I = d["I"]
        if pos is not None and pos["di"] == DI[i]:
            sd = pos["side"]
            done = False
            for m in range(max(S[i], pos["m"]), E[i]):
                if m >= CUT:
                    close_out(d, m, "15:10"); done = True; break
                if mode != "flip":
                    if (sd > 0 and I["low"][m] <= pos["sl"]) or (sd < 0 and I["high"][m] >= pos["sl"]):
                        close_out(d, m, "stop", pos["sl"]); done = True; break
                    if (sd > 0 and I["high"][m] >= pos["tp"]) or (sd < 0 and I["low"][m] <= pos["tp"]):
                        close_out(d, m, "target", pos["tp"]); done = True; break
            if done:
                pos = None
            elif i + 1 >= len(b) or DI[i + 1] != DI[i]:
                close_out(d, min(E[i] - 1, 374), "15:10"); pos = None
        last = i + 1 >= len(b) or DI[i + 1] != DI[i]
        if sig[i] == 0 or last or not d["chain"]:
            continue
        m = S[i + 1]
        if pos is not None and pos["side"] != sig[i]:        # the opposite signal closes the trade
            close_out(d, E[i] - 1, "opposite signal"); pos = None
        if pos is None and 5 <= m <= 315:
            pos = open_pos(d, i, sig[i], m)
    return pd.DataFrame(trades)


def row(label, tr, ndays):
    if tr.empty:
        return f"| {label} | 0 | | | | | | |"
    x = tr.rs
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
    mo = tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").rs.sum()
    eq = x.cumsum()
    return (f"| {label} | {len(tr)} ({len(tr) / ndays:.1f}/day) | {100 * (x > 0).mean():.0f}% | {tr.pts.mean():+.1f} | "
            f"Rs {x.mean():+,.0f} | {t:.2f} | Rs {x.sum():+,.0f} (dd {(eq - eq.cummax()).min():,.0f}) | {(mo > 0).sum()}/{len(mo)} |")


def main():
    yA, yB = load(sys.argv[1]), load(sys.argv[2])
    days = sorted(yA + yB, key=lambda d: d["day"])
    years = {"A": {d["day"] for d in yA}, "B": {d["day"] for d in yB}}
    L = ["## Chandelier Exit Trend Navigator, ATR 7 x 2, buying BANKNIFTY options (research/chandelier_exit.py)", "",
         f"Year A {min(years['A'])} .. {max(years['A'])}, year B {min(years['B'])} .. {max(years['B'])}. BUY -> ATM CE, "
         "SELL -> ATM PE; real option prices, 0.5 slippage a side, Rs 40 a trip, 1 lot of 30.", "",
         "| chart, exit, year | trades | win | index pts / trade | per trade | t | net (max drawdown) | green months |",
         "|---|---|---|---|---|---|---|---|"]
    for tf in (5, 15, 30, 60):
        b = bars(days, tf)
        sig, atr = chandelier(b)
        for mode in ("flip", 1, 2, 3):
            tr = simulate(days, b, sig, atr, mode)
            name = "opposite signal" if mode == "flip" else f"SL 1 ATR / TP{mode}"
            for y in ("A", "B"):
                ys = tr[tr.day.isin(years[y])] if not tr.empty else tr
                L.append(row(f"{tf}-min, {name}, year {y}", ys, len(years[y])))
            print("\n".join(L[-2:]), flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
