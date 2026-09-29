"""Swing trading BANKNIFTY with bought options: direction from the daily chart, hold 1-5 days.

    python research/swing.py <prev_year_wide.parquet> <year_wide.parquet> [out.md]

Signals are decided at 15:15 on day i from daily candles up to day i-1 plus day i so far (09:15-15:14). The option is
bought at 15:15 (the minute's open +0.5): the day's nearest expiry after the day, ATM or 1 strike in the money, 1 lot
of 30. It is held for H trading days (out at 15:15 on day i+H) or earlier on the option's target / stop; if the
contract expires or drops out of the data first, it is sold at 15:15 on its last available day. Rs 40 a round trip.
Direction accuracy is also reported on the index itself (close 15:15 day i -> 15:15 day i+H).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0
T1515 = 15 * 60 + 15 - (9 * 60 + 15)          # minute index 360


def daily(days):
    rows = []
    for d in days:
        I = d["I"]
        rows.append(dict(day=d["day"], o=I["open"][0], h=I["high"][:T1515].max(), l=I["low"][:T1515].min(),
                         c=I["close"][T1515 - 1], H=I["high"].max(), L=I["low"].min(), C=I["close"][-1]))
    return pd.DataFrame(rows)


def signals(D):
    """One column per rule: +1 up, -1 down, 0 no signal, decided with the 15:15 price of the day."""
    # history uses full days up to yesterday; today's bar uses 09:15-15:14
    C = D.C.shift(1)                      # yesterday's close
    c = D.c                               # today at 15:15
    ser = pd.concat([D.C.shift(1)]).copy()
    # series that ends with today's 15:15 price
    px = D.C.copy()
    px.iloc[:] = np.nan
    s = {}
    closes_hist = D.C.shift(1)
    def ema_at(n):
        # EMA over past full closes, then one step with today's 15:15 price
        e = D.C.ewm(span=n, adjust=False).mean().shift(1)
        a = 2 / (n + 1)
        return e + a * (c - e)
    e20, e50 = ema_at(20), ema_at(50)
    s["EMA 20/50 trend (price above both & 20>50 -> up)"] = np.where((c > e20) & (e20 > e50), 1, np.where((c < e20) & (e20 < e50), -1, 0))
    hi20 = D.H.shift(1).rolling(20).max()
    lo20 = D.L.shift(1).rolling(20).min()
    s["20-day breakout (Donchian / turtle)"] = np.where(c > hi20, 1, np.where(c < lo20, -1, 0))
    # daily supertrend(10,3) on full days, direction as of yesterday, flip today if price crosses the band
    h, l, cl = D.H.values, D.L.values, D.C.values
    pc = np.r_[cl[0], cl[:-1]]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    atr = pd.Series(tr).ewm(alpha=1 / 10, adjust=False).mean().values
    up, dn = (h + l) / 2 - 3 * atr, (h + l) / 2 + 3 * atr
    lo_b, hi_b, dirn = up.copy(), dn.copy(), np.ones(len(cl))
    for i in range(1, len(cl)):
        lo_b[i] = up[i] if (up[i] > lo_b[i - 1] or cl[i - 1] < lo_b[i - 1]) else lo_b[i - 1]
        hi_b[i] = dn[i] if (dn[i] < hi_b[i - 1] or cl[i - 1] > hi_b[i - 1]) else hi_b[i - 1]
        dirn[i] = 1 if cl[i] > hi_b[i - 1] else (-1 if cl[i] < lo_b[i - 1] else dirn[i - 1])
    st_prev = pd.Series(dirn).shift(1).values
    flip_up = (st_prev < 0) & (c.values > pd.Series(hi_b).shift(1).values)
    flip_dn = (st_prev > 0) & (c.values < pd.Series(lo_b).shift(1).values)
    s["Daily Supertrend(10,3) flip"] = np.where(flip_up, 1, np.where(flip_dn, -1, 0))
    s["Daily Supertrend(10,3) direction (every day)"] = np.where(flip_up, 1, np.where(flip_dn, -1, st_prev))
    # RSI(2) pullback in the trend (Connors): above 50-EMA and RSI2 < 10 -> up; below and > 90 -> down
    closes = pd.concat([D.C.shift(1)], axis=1)
    diff = D.C.diff().shift(1)
    today = c - D.C.shift(1)
    g = diff.clip(lower=0).ewm(alpha=1 / 2, adjust=False).mean()
    ls = (-diff.clip(upper=0)).ewm(alpha=1 / 2, adjust=False).mean()
    g2 = g + (today.clip(lower=0) - g) / 2
    l2 = ls + ((-today).clip(lower=0) - ls) / 2
    rsi2 = 100 - 100 / (1 + g2 / l2.replace(0, 1e-9))
    s["RSI(2) pullback in trend (Connors)"] = np.where((c > e50) & (rsi2 < 10), 1, np.where((c < e50) & (rsi2 > 90), -1, 0))
    s["5-day momentum (up over 5 days -> up)"] = np.sign(c - D.C.shift(5)).fillna(0).values
    s["5-day reversal (down over 5 days -> up)"] = -np.sign(c - D.C.shift(5)).fillna(0).values
    inside = (D.H.shift(1) < D.H.shift(2)) & (D.L.shift(1) > D.L.shift(2))
    s["Inside-day breakout"] = np.where(inside & (c > D.H.shift(1)), 1, np.where(inside & (c < D.L.shift(1)), -1, 0))
    # big daily candle then pullback (our intraday edge, scaled up)
    body = (D.C - D.o).shift(1)
    big = body.abs() > body.abs().rolling(60, min_periods=30).quantile(0.8).shift(1)
    rng = (D.H - D.L).shift(1)
    pull_up = big & (body > 0) & (c <= D.C.shift(1) - 0.4 * rng) & (c > D.L.shift(1))
    pull_dn = big & (body < 0) & (c >= D.C.shift(1) + 0.4 * rng) & (c < D.H.shift(1))
    s["Big daily candle, 40% pullback next day"] = np.where(pull_up, 1, np.where(pull_dn, -1, 0))
    s["Gap-down day recovering at 15:15 -> up (mirror)"] = np.where((D.o < D.C.shift(1) * 0.997) & (c > D.o), 1,
                                                               np.where((D.o > D.C.shift(1) * 1.003) & (c < D.o), -1, 0))
    return {k: pd.Series(np.nan_to_num(np.asarray(v, dtype=float)), index=D.index) for k, v in s.items()}


def option_trade(days, i, sign, hold, itm, tgt, stop):
    d = days[i]
    right = "CE" if sign > 0 else "PE"
    ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
    s0 = d["I"]["close"][T1515 - 1]
    k = ks[np.argmin(np.abs(ks - (s0 - sign * itm)))]
    leg = d["chain"][(k, right)]
    e = leg["open"][T1515] + SLIP
    last = (leg, T1515)
    for h in range(0, hold + 1):
        if i + h >= len(days):
            break
        dh = days[i + h]
        if dh["exp"] != d["exp"] or (k, right) not in dh["chain"]:
            break
        lg = dh["chain"][(k, right)]
        start = T1515 + 1 if h == 0 else 0
        end = T1515 if h == hold else 374
        for m in range(start, end + 1):
            if h > 0 and m == 0:
                if tgt and lg["open"][0] >= e * (1 + tgt):
                    return (lg["open"][0] - SLIP - e) * LOT - CHG, e
                if stop and lg["open"][0] <= e * (1 - stop):
                    return (lg["open"][0] - SLIP - e) * LOT - CHG, e
            if stop and lg["low"][m] <= e * (1 - stop):
                return (e * (1 - stop) - SLIP - e) * LOT - CHG, e
            if tgt and lg["high"][m] >= e * (1 + tgt):
                return (e * (1 + tgt) - SLIP - e) * LOT - CHG, e
        last = (lg, T1515 if h == hold else 374)
    lg, m = last
    return (lg["close"][m] - SLIP - e) * LOT - CHG, e


def evaluate(days, D, sig, hold, itm=0, tgt=None, stop=None, one_at_a_time=True):
    out, free = [], 0
    for i in range(len(D)):
        s = sig.iloc[i]
        if s == 0 or i < free or i + 1 >= len(D):
            continue
        j = min(i + hold, len(D) - 1)
        idx_move = s * (days[j]["I"]["close"][T1515 - 1] - days[i]["I"]["close"][T1515 - 1])
        pnl, prem = option_trade(days, i, int(s), hold, itm, tgt, stop)
        out.append(dict(day=D.day.iloc[i], year=D.year.iloc[i], idx=idx_move, net=pnl, prem=prem))
        if one_at_a_time:
            free = i + hold
    return pd.DataFrame(out, columns=["day", "year", "idx", "net", "prem"])


def main():
    paths = [a for a in sys.argv[1:] if a.endswith(".parquet")]
    out_md = next((a for a in sys.argv[1:] if a.endswith(".md")), None)
    days, years = [], []
    for yi, p in enumerate(paths):
        ds = load(p)
        days += ds
        years += [("Feb24-Feb25" if yi == 0 else "Feb25-Feb26")] * len(ds)
    D = daily(days)
    D["year"] = years
    S = signals(D)
    rng = np.random.default_rng(0)
    out = [f"## Swing trading BANKNIFTY with bought options, {D.day.iloc[0]} .. {D.day.iloc[-1]} ({len(D)} days)", "",
           "Index = the index move in the signal's direction from 15:15 on the signal day to 15:15 H days later "
           "(what a futures position would make, before costs). Option = buying the ATM call/put (or 1 strike ITM) at "
           "15:15, real prices, 1 lot, after costs. Each year shown separately: a real edge should show in both.", ""]
    for hold in (1, 3, 5):
        out += [f"### Hold {hold} day{'s' if hold > 1 else ''}", "",
                "| rule | year | trades | index direction right | avg index pts our way | option win | option net Rs (ATM) | option net Rs (1 ITM, +30% / -30%) |",
                "|---|---|---|---|---|---|---|---|"]
        rules = dict(S)
        rules["coin flip"] = pd.Series(rng.choice([-1, 1], size=len(D)), index=D.index)
        for name, sig in rules.items():
            a = evaluate(days, D, sig, hold)
            b = evaluate(days, D, sig, hold, itm=100, tgt=0.30, stop=0.30)
            for yr in ("Feb24-Feb25", "Feb25-Feb26"):
                x, y = a[a.year == yr], b[b.year == yr]
                if len(x) < 5:
                    out.append(f"| {name} | {yr} | {len(x)} | | | | | |")
                    continue
                out.append(f"| {name} | {yr} | {len(x)} | {100 * (x.idx > 0).mean():.0f}% | {x.idx.mean():+.0f} | "
                           f"{100 * (x.net > 0).mean():.0f}% | {x.net.sum():,.0f} | {y.net.sum():,.0f} |")
        out.append("")
        print("\n".join(out[-(4 + 2 * (len(S) + 1)):]), flush=True)
    text = "\n".join(out)
    if out_md:
        open(out_md, "w").write(text)


if __name__ == "__main__":
    main()
