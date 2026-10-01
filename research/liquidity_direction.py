"""Do liquidity pools, liquidity swings and liquidity sweeps tell the DIRECTION for the app's normal arms?

    python research/liquidity_direction.py <year A file> <year B file> [out.md]

Two BANKNIFTY years (dump_year.py layout, real ATM option prices; arms exactly as research/arms_long.py runs them).
Levels as the Liquidity 15+5 arm (indicator/liquidity.py: swing lookback 20 full range; pools 2 contacts / 5 apart /
10 confirmation), on a continuous 5-minute and a continuous 15-minute chart. At each 5-minute decision bar, using only
what was known then, four readings, each +1 (up), -1 (down) or 0:
  taken    the most recent level taken by a close in the last 2 hours: a high taken -> +1, a low taken -> -1
  sweep    the most recent sweep in the last 2 hours: a wick through an active level's outer edge that closes back
           inside (a high swept -> -1, the stop-run reversal; a low swept -> +1)
  draw     which side has the nearer active level (price "draws" to the nearer liquidity): above -> +1, below -> -1
  taken15  "taken" read on the 15-minute chart (last 4 hours)
Part 1: does each reading predict the index over the next 30 and 60 minutes (every bar 10:05-14:25)?
Part 2: for each arm, the trades that agree with a reading vs against it vs no reading, per year, and the arm with
only the agreeing trades kept (a filter). A reading counts only if it helps in BOTH years.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import arms_long as al  # noqa: E402
from range_fade_long import CHG, LOT  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

READINGS = ["taken", "sweep", "draw", "taken15"]
WINDOW = {"taken": 24, "sweep": 24, "taken15": 16}


def readings(bars: pd.DataFrame, zones, window_taken, window_sweep):
    """Per bar index: (taken, sweep, draw) from what is known once that bar has closed."""
    n = len(bars)
    h, l, c = bars.high.values, bars.low.values, bars.close.values
    last_taken = np.full(n, -10**9); taken_side = np.zeros(n)
    last_sweep = np.full(n, -10**9); sweep_side = np.zeros(n)
    ev_t = np.zeros(n); ev_s = np.zeros(n)
    for z in zones:
        if z.broken >= 0:
            ev_t[z.broken] = z.side           # a level taken at that bar (later zones overwrite: fine, same bar)
        end = z.broken if z.broken >= 0 else min(n, z.known + 300)
        k = np.arange(z.known + 1, end)
        if len(k):
            if z.side > 0:
                hit = k[(h[k] > z.top) & (c[k] <= z.top)]
                ev_s[hit] = -1
            else:
                hit = k[(l[k] < z.bottom) & (c[k] >= z.bottom)]
                ev_s[hit] = 1
    lt = ls = -10**9; st = ss = 0
    for i in range(n):
        if ev_t[i]:
            lt, st = i, ev_t[i]
        if ev_s[i]:
            ls, ss = i, ev_s[i]
        taken_side[i] = st if i - lt < window_taken else 0
        sweep_side[i] = ss if i - ls < window_sweep else 0
    # draw: nearer active level above vs below at each bar's close
    draw = np.zeros(n)
    up = np.full(n, np.inf); dn = np.full(n, np.inf)
    for z in zones:
        end = z.broken if z.broken >= 0 else n
        k = np.arange(z.known, end)
        if not len(k):
            continue
        d = (z.edge - c[k]) * z.side
        ok = d > 0
        if z.side > 0:
            up[k[ok]] = np.minimum(up[k[ok]], d[ok])
        else:
            dn[k[ok]] = np.minimum(dn[k[ok]], d[ok])
    draw[(up < dn)] = 1
    draw[(dn < up)] = -1
    return taken_side, sweep_side, draw


def chart(days):
    allb = pd.concat([b for _, b, _ in days])[["open", "high", "low", "close"]]
    allb = allb[~allb.index.duplicated()]
    b15 = pd.concat([b.resample("15min", label="left", closed="left", origin=b.index[0].normalize() + pd.Timedelta("9h15min"))
                     .agg({"open": "first", "high": "max", "low": "min", "close": "last"}).dropna() for _, b, _ in days])
    z5 = swing_zones(allb.reset_index(drop=True), 20, "full") + pool_zones(allb.reset_index(drop=True), 2, 5, 10)
    z15 = swing_zones(b15.reset_index(drop=True), 20, "full") + pool_zones(b15.reset_index(drop=True), 2, 5, 10)
    t5, s5, d5 = readings(allb, z5, WINDOW["taken"], WINDOW["sweep"])
    t15, _, _ = readings(b15, z15, WINDOW["taken15"], 1)
    r = pd.DataFrame({"taken": t5, "sweep": s5, "draw": d5}, index=allb.index)
    # 15-minute reading known at a 5-minute bar = the last 15-minute bar that has closed by then
    end15 = b15.index + pd.Timedelta(minutes=15)
    end5 = allb.index + pd.Timedelta(minutes=5)
    pos = np.searchsorted(end15.values, end5.values, side="right") - 1
    r["taken15"] = np.where(pos >= 0, t15[np.clip(pos, 0, None)], 0)
    # never read a 15-minute bar from an earlier day as today's (it is still the chart's last state, which is fine)
    return allb, r


def predict(allb, r, days_set):
    """Mean index move (points) over the next 30 / 60 minutes in the reading's direction, bars 10:05-14:25."""
    c = allb.close
    out = []
    t = allb.index
    ok = (t.strftime("%H:%M") >= "10:05") & (t.strftime("%H:%M") <= "14:25") & np.isin(t.date, list(days_set))
    for horizon in (6, 12):
        fwd = c.shift(-horizon) - c
        same_day = pd.Series(t.date, index=t).shift(-horizon) == pd.Series(t.date, index=t)
        for name in READINGS:
            s = r[name]
            m = ok & same_day.values & (s.values != 0) & fwd.notna().values
            x = (fwd[m] * s[m]).values
            tt = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
            out.append((horizon * 5, name, len(x), x.mean(), tt, 100 * (x > 0).mean()))
    return out


def arm_trades(days, name):
    sig, stop, tgt, maxn = al.ARMS[name]
    ind, st15 = al.indicators(days)
    out = []
    for day, b, legs in days:
        orb_ = b.between_time("09:15", "10:00")
        ctx = dict(orh=orb_.high.max(), orl=orb_.low.min(), ind=ind, st15=st15)
        n, busy = 0, None
        for j in range(1, len(b) - 1):
            if n >= maxn:
                break
            if busy is not None and b.index[j] <= busy.floor("5min"):
                continue
            s = sig(b, j, ctx)
            if not s:
                continue
            f = al.fill(legs[s], b.index[j + 1], stop, tgt)
            if f is None:
                continue
            out.append(dict(day=day, t=b.index[j], side=1 if s == "CE" else -1, net=f[0] * LOT - CHG))
            n += 1
            busy = f[1]
    return pd.DataFrame(out)


def stat(x):
    if len(x) == 0:
        return "0 | | |"
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else float("nan")
    return f"{len(x)} | {x.mean():+,.0f} | {x.sum():+,.0f} | {t:.2f}"


def main():
    yA, yB = al.load("file:" + sys.argv[1]), al.load("file:" + sys.argv[2])
    days = sorted(yA + yB, key=lambda d: d[0])
    years = {"A": {d for d, _, _ in yA}, "B": {d for d, _, _ in yB}}
    span = {k: f"{min(v)} .. {max(v)}" for k, v in years.items()}
    allb, r = chart(days)
    L = ["## Liquidity readings as a direction filter for the normal arms (research/liquidity_direction.py)", "",
         f"BANKNIFTY, year A {span['A']} ({len(years['A'])} days), year B {span['B']} ({len(years['B'])} days). "
         "Real ATM option prices, arms as the app runs them, 1 lot of 30, Rs 40 a trip.", "",
         "### 1. Does a reading predict the index? (every 5-minute bar 10:05-14:25)", "",
         "| next | reading | year | bars with a reading | index pts in its direction | t | right way |", "|---|---|---|---|---|---|---|"]
    for y in ("A", "B"):
        for mins, name, n, mean, t, hit in predict(allb, r, years[y]):
            L.append(f"| {mins} min | {name} | {y} | {n} | {mean:+.1f} | {t:.2f} | {hit:.1f}% |")
    L += ["", "### 2. The arms' trades split by each reading (Rs per lot after costs: trades | per trade | total | t)", ""]
    for name in ("ORB", "ORB Fresh", "ORB Sweep", "Range Fade"):
        tr = arm_trades(days, name)
        rr = r.reindex(tr.t)
        L += [f"#### {name}", "", "| reading | year | all trades | agree | against | no reading | agree-only filter vs arm |",
              "|---|---|---|---|---|---|---|"]
        for rd in READINGS:
            v = rr[rd].values * tr.side.values
            for y in ("A", "B"):
                m = tr.day.isin(years[y]).values
                a, g, o = tr.net[m], tr.net[m & (v > 0)], tr.net[m & (v < 0)]
                z = tr.net[m & (v == 0)]
                L.append(f"| {rd} | {y} | {stat(a)} | {stat(g)} | {stat(o)} | {stat(z)} | {g.sum() - a.sum():+,.0f} |")
        L.append("")
        print("\n".join(L[-10:]), flush=True)
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
