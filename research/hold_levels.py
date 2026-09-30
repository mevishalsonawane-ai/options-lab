"""How long a candle's own levels hold afterwards, minute by minute, within the day.

    python research/hold_levels.py <year.parquet>

Green candle: until the price trades below its LOW (wick bottom), and below its OPEN (body bottom).
Red candle:   until the price trades above its HIGH (wick top), and above its OPEN (body top).
Baseline: the same distance from the close, but after a candle of the OTHER colour (mirrored) - if a green candle's
low were special support, it would hold longer than an equally distant level that is not a candle's low.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from green_candles import candles, load  # noqa: E402

MARKS = (5, 15, 30, 60, 120)


def minutes_to_break(ix, b, level, side):
    """Minutes after the candle ends until the level is crossed (side +1: price goes below; -1: above); NaN = held to close."""
    out = np.full(len(b), np.nan)
    left = np.zeros(len(b))
    lo = ix.low
    hi = ix.high
    day_min = {d: g for d, g in ix.groupby(ix.index.date)}
    step = b.index[1] - b.index[0] if len(b) > 1 else pd.Timedelta("5min")
    for i, (t, lv) in enumerate(zip(b.index, level)):
        g = day_min[t.date()]
        after = g[g.index >= t + (b.index[i + 1] - t if i + 1 < len(b) and b.index[i + 1].date() == t.date() else step)]
        if after.empty:
            out[i], left[i] = np.nan, 0
            continue
        left[i] = len(after)
        hit = np.flatnonzero(after.low.values < lv) if side > 0 else np.flatnonzero(after.high.values > lv)
        out[i] = hit[0] + 1 if len(hit) else np.nan
    return out, left


def table(rule, rows):
    lines = [f"### {rule} candles", "",
             "Share of candles whose level is STILL unbroken after N minutes (candles with less than N minutes of the "
             "day left are left out of that column), the median minutes to the break, and how many held to the close.", "",
             "| level | candles | avg distance from close (pts) | " + " | ".join(f"{m} min" for m in MARKS) +
             " | median minutes to break | held to 15:30 |", "|---|---|---|" + "---|" * len(MARKS) + "---|---|"]
    for name, mins, left, dist in rows:
        cells = []
        for m in MARKS:
            ok = left >= m
            held = np.isnan(mins[ok]) | (mins[ok] > m)
            cells.append(f"{100 * held.mean():.0f}%")
        broke = mins[~np.isnan(mins)]
        lines.append(f"| {name} | {len(mins)} | {np.nanmean(dist):.0f} | " + " | ".join(cells) +
                     f" | {np.median(broke):.0f} | {100 * np.isnan(mins).mean():.0f}% |")
    return "\n".join(lines) + "\n"


def run(ix, rule):
    b = candles(ix, rule) if rule != "1min" else ix.between_time("09:15", "15:29").copy()
    b = b.between_time("09:15", "15:10")
    body = b.close - b.open
    big = body.abs() > body.abs().quantile(0.8)
    g, r = body > 0, body < 0
    rows = []
    for label, m in (("all", slice(None)), ("big (top 20% body)", None)):
        for colour, sel, lvl_name, lvl, side in (
                ("GREEN", g, "low (wick bottom)", b.low, 1), ("GREEN", g, "open (body bottom)", b.open, 1),
                ("RED", r, "high (wick top)", b.high, -1), ("RED", r, "open (body top)", b.open, -1)):
            s = sel & big if m is None else sel
            bb = b[s]
            mins, left = minutes_to_break(ix, bb, lvl[s].values, side)
            rows.append((f"{colour} {label}: {lvl_name}", mins, left, (bb.close - lvl[s]).abs().values))
        # baselines: same distance, mirrored onto the other colour
        for colour, sel, other, side in (("GREEN low", g, r, 1), ("RED high", r, g, -1)):
            s_other = other & big if m is None else other
            d = (b.close - b.low)[sel & (big if m is None else True)] if side > 0 else (b.high - b.close)[sel & (big if m is None else True)]
            dist = np.random.default_rng(0).choice(d.values, size=int(s_other.sum()))
            bb = b[s_other]
            lvl = bb.close.values - dist if side > 0 else bb.close.values + dist
            mins, left = minutes_to_break(ix, bb, lvl, side)
            rows.append((f"baseline for {colour} ({label}): same distance after a {'red' if side > 0 else 'green'} candle",
                         mins, left, dist))
    return table(rule, rows)


def main():
    ix, _, _, _ = load(sys.argv[1])
    out = [f"## How long a candle's levels hold, BANKNIFTY {ix.index.min().date()} .. {ix.index.max().date()}", "",
           "Candles up to 15:10. Example: a green 5-min candle from 10 to 20 - how long until the price trades below 10?", ""]
    for rule in ("5min", "15min"):
        out.append(run(ix, rule))
        print(out[-1], flush=True)
    return out


if __name__ == "__main__":
    text = "\n".join(main())
    open(sys.argv[2], "w").write(text) if len(sys.argv) > 2 else None
