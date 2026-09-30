"""The 9 EMA + VWAP + higher-highs/higher-lows direction filter, on the year.

    python research/trend_filter.py <src> [out.md]      (src: local | file:<year.parquet>)

Rules (5-minute BANKNIFTY bars, decided on a completed bar from the 09:30 bar to the 14:25 bar):
  bullish  the bar's LOW is above the 9 EMA and above the day's VWAP (the whole candle above both), and the last
           n bars each made a higher high AND a higher low          -> CE trades only
  bearish  the bar's HIGH below both, and n lower highs AND lower lows -> PE trades only
  sideways the closes crossed the VWAP 2+ times in the last 6 bars  -> no trade
The index has no volume, so VWAP here is the day's running average of the typical price (high+low+close)/3 - the
volume-weighted line on a futures chart can differ a little.

Part 1: does the filter call the direction? (index points afterwards)
Part 2: buying the ATM option on it, next-bar entry, real option prices, Rs 40 + 0.5 a side, 1 lot of 30, max 2 a day,
        exits either -40/+40 or "trend exit" (a 5-minute close back below the 9 EMA for CE / above it for PE, with a
        -60 safety stop), 15:10 square-off.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from arms_long import load  # noqa: E402
from range_fade_long import CHG, LOT, SLIP  # noqa: E402


def prepare(days):
    allb = pd.concat([b for _, b, _ in days])
    ema = allb.close.ewm(span=9, adjust=False).mean()
    out = []
    for day, b, legs in days:
        b = b.copy()
        b["ema9"] = ema.reindex(b.index).values
        tp = (b.high + b.low + b.close) / 3
        b["vwap"] = tp.expanding().mean()
        side = np.sign(b.close - b.vwap)
        b["cross6"] = (side != side.shift()).astype(int).rolling(6, min_periods=2).sum() - 0
        out.append((day, b, legs))
    return out


def signal(b, j, n, chop):
    r = b.iloc[j]
    t = b.index[j].strftime("%H:%M")
    if not ("09:30" <= t <= "14:25") or j < n:
        return 0
    if chop and b.cross6.iloc[j] >= 2:
        return 0
    w = b.iloc[j - n:j + 1]
    hh = (w.high.diff().dropna() > 0).all() and (w.low.diff().dropna() > 0).all()
    ll = (w.high.diff().dropna() < 0).all() and (w.low.diff().dropna() < 0).all()
    if r.low > r.ema9 and r.low > r.vwap and hh:
        return 1
    if r.high < r.ema9 and r.high < r.vwap and ll:
        return -1
    return 0


def direction(days, n, chop):
    rows = []
    for day, b, _ in days:
        for j in range(len(b) - 1):
            s = signal(b, j, n, chop)
            c = b.close.iloc[j]
            f = {h: (b.close.iloc[j + h] - c) if j + h < len(b) else np.nan for h in (3, 6, 12)}
            rows.append(dict(day=day, s=s, f15=f[3], f30=f[6], f60=f[12], eod=b.close.iloc[-1] - c))
    return pd.DataFrame(rows)


def dir_table(d, label):
    lines = [f"**{label}**", "", "| state | bars | up after 15 min | avg pts 15 min | 30 min | 60 min | to close |",
             "|---|---|---|---|---|---|---|"]
    for name, s in (("bullish (CE only)", 1), ("bearish (PE only)", -1), ("no signal", 0)):
        x = d[d.s == s]
        lines.append(f"| {name} | {len(x)} | {100 * (x.f15 > 0).mean():.0f}% | {x.f15.mean():+.1f} | {x.f30.mean():+.1f} | "
                     f"{x.f60.mean():+.1f} | {x.eod.mean():+.1f} |")
    return "\n".join(lines) + "\n"


def fill_trend(b, j, leg, sign):
    """Buy at the next bar; exit on a 5-min close back through the 9 EMA, the -60 safety stop, or 15:10."""
    t0 = b.index[j + 1]
    a = leg[leg.index >= t0]
    if a.empty or a.index[0].strftime("%H:%M") >= "15:10":
        return None
    e = a.open.iloc[0] + SLIP
    for k in range(j + 1, len(b)):
        seg = a[(a.index >= b.index[k]) & (a.index < b.index[k] + pd.Timedelta("5min"))]
        if len(seg) and seg.low.min() <= e - 60:
            return -60 - SLIP, seg.index[(seg.low <= e - 60).values.argmax()]
        tk = b.index[k]
        broken = (b.close.iloc[k] < b.ema9.iloc[k]) if sign > 0 else (b.close.iloc[k] > b.ema9.iloc[k])
        if tk.strftime("%H:%M") >= "15:05" or broken:
            nx = a[a.index >= tk + pd.Timedelta("5min")]
            px = nx.open.iloc[0] if len(nx) else a.close.iloc[-1]
            return px - SLIP - e, (nx.index[0] if len(nx) else a.index[-1])
    return a.close.iloc[-1] - SLIP - e, a.index[-1]


def fill_fixed(b, j, leg, stop=40, tgt=40):
    a = leg[leg.index >= b.index[j + 1]]
    if a.empty or a.index[0].strftime("%H:%M") >= "15:10":
        return None
    e = a.open.iloc[0] + SLIP
    for ts, r in a.iterrows():
        if r.low <= e - stop:
            return -stop - SLIP, ts
        if r.high >= e + tgt:
            return tgt - SLIP, ts
        if ts.strftime("%H:%M") >= "15:10":
            return r.close - SLIP - e, ts
    return a.close.iloc[-1] - SLIP - e, a.index[-1]


def trades(days, n, chop, exit_rule, maxn=2):
    out = []
    for day, b, legs in days:
        k, busy = 0, None
        for j in range(len(b) - 1):
            if k >= maxn:
                break
            if busy is not None and b.index[j] <= busy.floor("5min"):
                continue
            s = signal(b, j, n, chop)
            if s == 0:
                continue
            leg = legs["CE" if s > 0 else "PE"]
            f = fill_trend(b, j, leg, s) if exit_rule == "trend" else fill_fixed(b, j, leg)
            if f is None:
                continue
            out.append(dict(day=day, net=f[0] * LOT - CHG))
            k += 1
            busy = f[1]
    return pd.DataFrame(out, columns=["day", "net"])


def trade_line(tr, alld, label):
    if tr.empty:
        return f"| {label} | 0 | | | | | |"
    half = set(alld[: len(alld) // 2])
    t = tr.net.mean() / (tr.net.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else float("nan")
    m = tr.assign(m=pd.to_datetime(tr.day).dt.strftime("%Y-%m")).groupby("m").net.sum()
    return (f"| {label} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} | {t:.2f} | "
            f"{tr[tr.day.isin(half)].net.sum():,.0f} / {tr[~tr.day.isin(half)].net.sum():,.0f} | {(m > 0).sum()}/{len(m)} |")


def main():
    days = prepare(load(sys.argv[1] if len(sys.argv) > 1 else "local"))
    alld = [d for d, _, _ in days]
    out = [f"## 9 EMA + VWAP + HH/HL filter, BANKNIFTY {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
           "### Part 1: does it call the direction? (index points after the signal bar)", ""]
    for n in (2, 3):
        out.append(dir_table(direction(days, n, True), f"{n} higher highs + higher lows (or lower), sideways filter on"))
    out.append(dir_table(direction(days, 2, False), "2 HH/HL, sideways filter OFF"))
    out += ["### Part 2: buying the ATM option on it (1 lot, after costs)", "",
            "| version | trades | win | net Rs | t | 1st / 2nd half | green months |", "|---|---|---|---|---|---|---|"]
    for n in (2, 3):
        for ex in ("fixed", "trend"):
            for chop in (True, False):
                label = f"{n} HH/HL, {'-40/+40' if ex == 'fixed' else 'trend exit (close through 9 EMA)'}, sideways filter {'on' if chop else 'off'}"
                out.append(trade_line(trades(days, n, chop, ex), alld, label))
                print(out[-1], flush=True)
    text = "\n".join(out)
    print("\n" + text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
