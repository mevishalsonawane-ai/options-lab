"""How long green and red runs last, and whether the price keeps going the candle's way afterwards.

    python research/streaks.py <year.parquet>

Runs are counted within a day (a run ends at the close). Every rate is compared with what a coin flip with the same
green share would give, and checked on both halves of the year.
"""
from __future__ import annotations

import sys

import numpy as np
import pandas as pd

sys.path.insert(0, __import__("os").path.dirname(__file__))
from green_candles import candles, load  # noqa: E402


def runs(b):
    """Every completed same-colour run within a day: (colour, length, points moved, start time)."""
    out = []
    for _, g in b.groupby(b.index.date):
        col = np.sign(g.close.values - g.open.values)
        i = 0
        while i < len(col):
            j = i
            while j + 1 < len(col) and col[j + 1] == col[i] and col[i] != 0:
                j += 1
            if col[i] != 0:
                out.append(("green" if col[i] > 0 else "red", j - i + 1, g.close.values[j] - g.open.values[i], g.index[i]))
            i = j + 1
    return pd.DataFrame(out, columns=["colour", "length", "pts", "start"])


def run_tables(b, rule):
    r = runs(b)
    half = b.index[len(b) // 2]
    lines = [f"### {rule} candles: how long runs last", ""]
    for colour in ("green", "red"):
        x = r[r.colour == colour]
        p = (b.close > b.open).mean() if colour == "green" else (b.close < b.open).mean()
        lines += [f"**{colour.title()} runs** ({len(x)} runs, average {x.length.mean():.2f} candles, "
                  f"average move {x.pts.mean():+.0f} pts; a coin flip would average {1 / (1 - p):.2f} candles)", "",
                  "| run length | runs | share | coin-flip share | average move (pts) |", "|---|---|---|---|---|"]
        for L in range(1, 9):
            m = x.length == L if L < 8 else x.length >= 8
            coin = (1 - p) * p ** (L - 1) if L < 8 else p ** 7
            lines.append(f"| {L if L < 8 else '8+'} | {m.sum()} | {100 * m.mean():.1f}% | {100 * coin:.1f}% | {x.pts[m].mean():+.0f} |")
        lines.append("")
    # continuation: given k same-colour candles so far, does the next one keep the colour?
    lines += ["**Does the run continue?** Chance the NEXT candle has the same colour after k in a row "
              "(coin flip = the colour's share)", "",
              "| k in a row | green continues | 1st / 2nd half | red continues | 1st / 2nd half |", "|---|---|---|---|---|"]
    rows = []
    for _, g in b.groupby(b.index.date):
        col = np.sign(g.close.values - g.open.values)
        k = 0
        for i in range(len(col) - 1):
            k = k + 1 if i and col[i] == col[i - 1] and col[i] != 0 else (1 if col[i] != 0 else 0)
            if col[i] != 0:
                rows.append((col[i], min(k, 6), col[i + 1] == col[i], g.index[i] < half))
    c = pd.DataFrame(rows, columns=["col", "k", "cont", "h1"])
    pg, pr = (b.close > b.open).mean(), (b.close < b.open).mean()
    for k in range(1, 7):
        cells = []
        for s in (1, -1):
            m = (c.col == s) & (c.k == k)
            cells.append(f"{100 * c.cont[m].mean():.1f}% ({m.sum()})")
            cells.append(f"{100 * c.cont[m & c.h1].mean():.1f}% / {100 * c.cont[m & ~c.h1].mean():.1f}%")
        lines.append(f"| {k if k < 6 else '6+'} | " + " | ".join(cells) + " |")
    lines += ["", f"Coin flip: green continues {100 * pg:.1f}%, red continues {100 * pr:.1f}%.", ""]
    return "\n".join(lines)


def follow_through(b, rule, horizons=(1, 2, 3, 5, 10, 20)):
    """After a green (red) candle, is the price still above (below) its close h candles later, same day?"""
    lines = [f"### {rule}: after a green / red candle, is the price still going that way h candles later?", "",
             "Share of cases where the close h candles later is beyond the candle's close in its own direction, and the "
             "average further move in that direction (pts). 'All candles' is the drift anyone would get.", "",
             "| after | " + " | ".join(f"h={h}" for h in horizons) + " |", "|---|" + "---|" * len(horizons)]
    day = pd.Series(b.index.date, index=b.index)
    body = (b.close - b.open)
    big = body.abs() > body.abs().quantile(0.8)
    fut = {h: b.groupby(day).close.shift(-h) - b.close for h in horizons}
    groups = {"green candle": body > 0, "red candle": body < 0, "big green (top 20%)": (body > 0) & big,
              "big red (top 20%)": (body < 0) & big}
    for name, m in groups.items():
        sign = 1 if "green" in name else -1
        cells = []
        for h in horizons:
            f = (fut[h] * sign)[m].dropna()
            cells.append(f"{100 * (f > 0).mean():.0f}% / {f.mean():+.1f}")
        lines.append(f"| {name} | " + " | ".join(cells) + " |")
    cells = []
    for h in horizons:
        f = fut[h].dropna()
        cells.append(f"{100 * (f > 0).mean():.0f}% up / {f.mean():+.1f}")
    lines.append("| all candles (drift) | " + " | ".join(cells) + " |")
    return "\n".join(lines) + "\n"


def main():
    ix, _, _, _ = load(sys.argv[1])
    out = [f"## Green and red runs, BANKNIFTY {ix.index.min().date()} .. {ix.index.max().date()}", ""]
    for rule in ("1min", "5min", "15min"):
        b = candles(ix, rule) if rule != "1min" else ix.between_time("09:15", "15:29").copy()
        out += [run_tables(b, rule), follow_through(b, rule)]
    d = ix.groupby(ix.index.date).agg(open=("open", "first"), high=("high", "max"), low=("low", "min"), close=("close", "last"))
    d.index = pd.to_datetime(d.index)
    col = np.sign(d.close - d.open)
    k, rows = 0, []
    for i in range(1, len(col)):
        rows.append((col.iloc[i - 1], col.iloc[i] == col.iloc[i - 1]))
    c = pd.DataFrame(rows, columns=["col", "cont"])
    r = []
    i = 0
    v = col.values
    while i < len(v):
        j = i
        while j + 1 < len(v) and v[j + 1] == v[i]:
            j += 1
        r.append((v[i], j - i + 1))
        i = j + 1
    r = pd.DataFrame(r, columns=["col", "len"])
    out += ["### Days", "",
            f"Green day followed by green: {100 * c.cont[c.col > 0].mean():.1f}%; red followed by red: "
            f"{100 * c.cont[c.col < 0].mean():.1f}% (coin flip {100 * (v > 0).mean():.0f}% / {100 * (v < 0).mean():.0f}%).",
            f"Green streaks average {r.len[r.col > 0].mean():.2f} days (longest {r.len[r.col > 0].max()}); "
            f"red streaks {r.len[r.col < 0].mean():.2f} days (longest {r.len[r.col < 0].max()}).", ""]
    print("\n".join(out))


if __name__ == "__main__":
    main()
