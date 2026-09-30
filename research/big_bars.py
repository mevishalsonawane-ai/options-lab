"""5-minute BANKNIFTY candles that move more than 50 points: how often, when, clustered, predictable, tradable.

    python research/big_bars.py <year_wide.parquet> [<another year> ...]
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0


def bars(days):
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, 375, 5):
            rows.append(dict(di=di, day=d["day"], s=s, o=I["open"][s], h=I["high"][s:s + 5].max(),
                             l=I["low"][s:s + 5].min(), c=I["close"][s + 4], m1=I["close"][s] - I["open"][s],
                             m2=I["close"][s + 1] - I["open"][s]))
    b = pd.DataFrame(rows)
    b["rng"] = b.h - b.l
    b["body"] = b.c - b.o
    b["t"] = [f"{(555 + s) // 60:02d}:{(555 + s) % 60:02d}" for s in b.s]
    return b


def study(days, tag):
    b = bars(days)
    g = b.groupby("di")
    b["prev_rng"] = g.rng.shift(1)
    b["prev_body"] = g.body.shift(1)
    b["next_body"] = g.body.shift(-1)
    b["next_rng"] = g.rng.shift(-1)
    b["rng6"] = g.rng.transform(lambda x: x.shift(1).rolling(6, min_periods=3).mean())
    big = b.rng > 50
    bigb = b.body.abs() > 50
    out = [f"### {tag}: {b.day.nunique()} days {b.day.min()} .. {b.day.max()}", ""]
    per_day = b[bigb].groupby("day").size().reindex(b.day.unique(), fill_value=0)
    per_day_r = b[big].groupby("day").size().reindex(b.day.unique(), fill_value=0)
    out += [f"- Candles with a **range** over 50 pts: {100 * big.mean():.0f}% of all 5-min candles, {per_day_r.mean():.1f} a day "
            f"(median {per_day_r.median():.0f}). With a **body** (open to close) over 50 pts: {100 * bigb.mean():.1f}%, "
            f"{per_day.mean():.1f} a day (median {per_day.median():.0f}; days with none: {(per_day == 0).sum()}).",
            f"- Direction of the >50-pt bodies: {100 * (b.body[bigb] > 0).mean():.0f}% green."]
    # time of day
    tod = b.assign(hr=b.t.str[:4] + "0").groupby(b.t.str[:2]).apply(lambda x: 100 * (x.body.abs() > 50).mean())
    out += ["- Chance a 5-min candle has a >50-pt body, by hour: " + ", ".join(f"{h}h {v:.0f}%" for h, v in tod.items())]
    # clustering / predictability of SIZE
    base = bigb.mean()
    cond = {
        "previous candle body > 50": b.prev_body.abs() > 50,
        "previous candle range > 50": b.prev_rng > 50,
        "last 6 candles' average range > 50": b.rng6 > 50,
        "last 6 candles' average range < 30": b.rng6 < 30,
        "first half hour (09:15-09:45)": b.s < 30,
        "13:00-14:30": (b.s >= 225) & (b.s < 315),
    }
    out += ["", "**Can we tell a big candle is coming? (chance the candle's body is > 50 pts)**", "",
            f"| before the candle | candles | chance of a >50-pt body (base {100 * base:.1f}%) |", "|---|---|---|"]
    for k, m in cond.items():
        out.append(f"| {k} | {m.sum()} | {100 * bigb[m].mean():.1f}% |")
    # direction
    up3 = g.body.transform(lambda x: x.shift(1).rolling(3).sum())
    out += ["", "**Can we tell its direction?**", "", "| before a >50-pt candle | candles | share green |", "|---|---|---|"]
    for k, m in {"previous candle green": b.prev_body > 0, "previous candle red": b.prev_body < 0,
                 "last 3 candles up in total": up3 > 0, "last 3 candles down in total": up3 < 0,
                 "previous candle big green (>50)": b.prev_body > 50, "previous candle big red (<-50)": b.prev_body < -50}.items():
        mm = m & bigb
        out.append(f"| {k} | {mm.sum()} | {100 * (b.body[mm] > 0).mean():.0f}% |")
    # inside the candle: once the first 1-2 minutes have moved, does it finish big in that direction?
    out += ["", "**Inside the candle: after a fast first 2 minutes, does it finish big that way?**", "",
            "| first 2 minutes moved | candles | finishes > 50 pts that way | finishes the other way | avg further move (pts) |",
            "|---|---|---|---|---|"]
    for lo, hi in ((20, 30), (30, 40), (40, 1e9)):
        m = b.m2.abs().between(lo, hi)
        sgn = np.sign(b.m2[m])
        fin = b.body[m] * sgn
        out.append(f"| {lo}-{int(hi) if hi < 1e9 else '+'} pts | {m.sum()} | {100 * (fin > 50).mean():.0f}% | "
                   f"{100 * (fin < 0).mean():.0f}% | {(fin - b.m2[m].abs()).mean():+.1f} |")
    # after a big candle
    out += ["", "**After a >50-pt candle, what does the NEXT candle do?**", "",
            "| candle | count | next candle same colour | next candle avg move its way (pts) | next candle range > 50 |",
            "|---|---|---|---|---|"]
    for k, m in {"big green (>50)": b.body > 50, "big red (<-50)": b.body < -50}.items():
        s = np.sign(b.body[m])
        out.append(f"| {k} | {m.sum()} | {100 * ((b.next_body[m] * s) > 0).mean():.0f}% | {(b.next_body[m] * s).mean():+.1f} | "
                   f"{100 * (b.next_rng[m] > 50).mean():.0f}% |")
    return out, b


def main():
    out = ["## 5-minute BANKNIFTY candles over 50 points", ""]
    for path in sys.argv[1:]:
        days = load(path)
        o, _ = study(days, os.path.basename(path).replace("_wide.parquet", ""))
        out += o + [""]
    text = "\n".join(out)
    print(text)
    open("research/BIG_BARS.md", "w").write(text + "\n")


if __name__ == "__main__":
    main()
