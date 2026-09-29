"""The volatility at the moment of each finding, and every finding split by volatility.

    python research/volatility.py <year.parquet> [out.md]

IV: implied volatility from the ATM straddle each minute, sigma ~= straddle / (0.8 * spot * sqrt(T)) with T the
calendar time to the day's nearest expiry (annualised %). RV: realised volatility of the index's 1-minute returns
over the previous 30 minutes, annualised %. Low / mid / high = the year's thirds of each.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from green_candles import candles, load  # noqa: E402
from hold_levels import minutes_to_break  # noqa: E402


def vol_series(path, ix, strad):
    df = pd.read_parquet(path, columns=["day", "right", "expiry"])
    exp = df[df.right != "IX"].groupby(df.day.dt.date).expiry.min()
    t = ix.index
    d = pd.Series(t.date, index=t)
    dte = (pd.to_datetime(d.map(exp)) - pd.to_datetime(d)).dt.days.values
    left = ((15 * 60 + 30) - (t.hour * 60 + t.minute)) / 375.0
    T = np.maximum(dte + left, 0.05) / 365.0
    iv = strad.reindex(t, method="ffill").values / (0.8 * ix.close.values * np.sqrt(T)) * 100
    r = np.log(ix.close).groupby(t.date).diff()
    rv = r.rolling(30, min_periods=20).std() * np.sqrt(375 * 252) * 100
    return pd.DataFrame({"iv": iv, "rv": rv.values}, index=t)


def at(v, times):
    return v.reindex(times, method="ffill")


def tert(x):
    q = x.quantile([1 / 3, 2 / 3]).values
    return pd.Series(np.where(x <= q[0], "low", np.where(x <= q[1], "mid", "high")), index=x.index), q


def held(mins, left, m):
    ok = left >= m
    return (np.isnan(mins[ok]) | (mins[ok] > m)).mean() if ok.any() else np.nan


def section_moment(ix, v, rule):
    b = candles(ix, rule).between_time("09:15", "15:10")
    end = b.index + (b.index[1] - b.index[0]) - pd.Timedelta("1min")
    body = b.close - b.open
    big = body.abs() > body.abs().quantile(0.8)
    iv0 = at(v.iv, b.index - pd.Timedelta("1min")).values
    iv1 = at(v.iv, end).values
    iv30 = at(v.iv, end + pd.Timedelta("30min")).values
    rv0 = at(v.rv, b.index - pd.Timedelta("1min")).values
    rows = ["| candles | count | IV before (%) | IV change during the candle | IV change next 30 min | RV before (%) |",
            "|---|---|---|---|---|---|"]
    for name, m in (("normal green", (body > 0) & ~big), ("normal red", (body < 0) & ~big),
                    ("big green", (body > 0) & big), ("big red", (body < 0) & big)):
        m = m.values
        rows.append(f"| {name} | {m.sum()} | {np.nanmedian(iv0[m]):.1f} | {np.nanmedian(iv1[m] - iv0[m]):+.2f} | "
                    f"{np.nanmedian(iv30[m] - iv1[m]):+.2f} | {np.nanmedian(rv0[m]):.1f} |")
    return f"### Volatility at the moment of {rule} candles (medians)\n\n" + "\n".join(rows) + "\n"


def section_levels(ix, v, rule, big_only):
    b = candles(ix, rule).between_time("09:15", "15:10")
    body = b.close - b.open
    big = body.abs() > body.abs().quantile(0.8)
    ivb = at(v.iv, b.index - pd.Timedelta("1min"))
    ivb.index = b.index
    lab, q = tert(ivb)
    rvb = at(v.rv, b.index - pd.Timedelta("1min"))
    rvb.index = b.index
    rlab, rq = tert(rvb)
    marks = (60, 120) if rule == "15min" else (15, 60)
    title = f"big {rule}" if big_only else f"all {rule}"
    lines = [f"### How long the candle's level holds, {title} candles, by volatility", "",
             f"IV thirds: low <= {q[0]:.1f}%, high > {q[1]:.1f}%. RV thirds: low <= {rq[0]:.1f}%, high > {rq[1]:.1f}%. "
             "Baseline = same distance after a candle of the other colour, same volatility third.", "",
             "| candle / level | vol measure | third | candles | " + " | ".join(f"held {m} min" for m in marks) +
             " | " + " | ".join(f"baseline {m} min" for m in marks) + " |", "|---|---|---|---|" + "---|" * (2 * len(marks))]
    rng = np.random.default_rng(0)
    for cname, sel, lvl, side, other in (("green / low", body > 0, b.low, 1, body < 0), ("red / high", body < 0, b.high, -1, body > 0)):
        for vname, L in (("IV", lab), ("RV", rlab)):
            for t3 in ("low", "mid", "high"):
                s = sel & (L == t3) & (big if big_only else True)
                o = other & (L == t3) & (big if big_only else True)
                if s.sum() < 30 or o.sum() < 30:
                    continue
                bb = b[s]
                mins, left = minutes_to_break(ix, bb, lvl[s].values, side)
                d = ((b.close - b.low) if side > 0 else (b.high - b.close))[s].values
                ob = b[o]
                dist = rng.choice(d, size=len(ob))
                ol = ob.close.values - dist if side > 0 else ob.close.values + dist
                m2, l2 = minutes_to_break(ix, ob, ol, side)
                lines.append(f"| {cname} | {vname} | {t3} | {s.sum()} | " +
                             " | ".join(f"{100 * held(mins, left, m):.0f}%" for m in marks) + " | " +
                             " | ".join(f"{100 * held(m2, l2, m):.0f}%" for m in marks) + " |")
    return "\n".join(lines) + "\n"


def section_next(ix, v, rule):
    b = candles(ix, rule)
    day = pd.Series(b.index.date, index=b.index)
    body = b.close - b.open
    nxt = body.groupby(day).shift(-1)
    big = body.abs() > body.abs().quantile(0.8)
    col = np.sign(body)
    run = col.groupby(day).transform(lambda g: g.groupby((g != g.shift()).cumsum()).cumcount() + 1)
    ivb = at(v.iv, b.index + (b.index[1] - b.index[0]) - pd.Timedelta("1min"))
    ivb.index = b.index
    lab, q = tert(ivb)
    fut5 = b.close.groupby(day).shift(-5) - b.close
    lines = [f"### Next-candle effects on {rule} candles, by IV third (low <= {q[0]:.1f}%, high > {q[1]:.1f}%)", "",
             "| after | IV third | candles | next candle green | next candle avg pts | price 5 candles later, avg pts |",
             "|---|---|---|---|---|---|"]
    conds = {"red candle": body < 0, "green candle": body > 0, "3+ reds in a row": (col < 0) & (run >= 3),
             "3+ greens in a row": (col > 0) & (run >= 3), "big red": (body < 0) & big, "big green": (body > 0) & big}
    for name, m in conds.items():
        for t3 in ("low", "mid", "high"):
            s = m & (lab == t3) & nxt.notna()
            lines.append(f"| {name} | {t3} | {s.sum()} | {100 * (nxt[s] > 0).mean():.1f}% | {nxt[s].mean():+.1f} | "
                         f"{fut5[s].mean():+.1f} |")
    return "\n".join(lines) + "\n"


def section_days(ix, v):
    d = ix.groupby(ix.index.date).agg(open=("open", "first"), close=("close", "last"), high=("high", "max"), low=("low", "min"))
    iv_open = v.iv.between_time("09:20", "09:25").groupby(v.iv.between_time("09:20", "09:25").index.date).median()
    d["iv"] = iv_open
    d["rng"] = d.high - d.low
    lab, q = tert(d.iv.dropna())
    d["t3"] = lab
    lines = [f"### Days by opening IV (09:20; low <= {q[0]:.1f}%, high > {q[1]:.1f}%)", "",
             "| IV third | days | green days | average day range (pts) | average |close - open| (pts) |", "|---|---|---|---|---|"]
    for t3 in ("low", "mid", "high"):
        x = d[d.t3 == t3]
        lines.append(f"| {t3} | {len(x)} | {100 * (x.close > x.open).mean():.0f}% | {x.rng.mean():.0f} | {(x.close - x.open).abs().mean():.0f} |")
    return "\n".join(lines) + "\n"


def candle_vol(ix, b):
    """The candle's own volatility: range, range vs the average of the previous 20 candles, and the 1-minute
    realised volatility inside it (annualised %)."""
    day = pd.Series(b.index.date, index=b.index)
    rng = b.high - b.low
    atr = rng.groupby(day).transform(lambda g: g.shift(1).rolling(20, min_periods=5).mean())
    r = np.log(ix.close).groupby(ix.index.date).diff()
    step = b.index[1] - b.index[0]
    inside = r.groupby(r.index.floor(step)).std().reindex(b.index) * np.sqrt(375 * 252) * 100
    return pd.DataFrame({"range": rng, "rel": rng / atr, "inside": inside}, index=b.index)


def section_candle(ix, v, rule):
    b = candles(ix, rule).between_time("09:15", "15:10")
    cv = candle_vol(ix, b)
    body = b.close - b.open
    big = body.abs() > body.abs().quantile(0.8)
    ivb = at(v.iv, b.index - pd.Timedelta("1min"))
    ivb.index = b.index
    lines = [f"### The candle's own volatility, {rule} (medians)", "",
             "| candles | count | range (pts) | range vs last 20 candles (x) | 1-min volatility inside (%) | IV before (%) |",
             "|---|---|---|---|---|---|"]
    for name, m in (("normal green", (body > 0) & ~big), ("normal red", (body < 0) & ~big),
                    ("big green", (body > 0) & big), ("big red", (body < 0) & big)):
        lines.append(f"| {name} | {m.sum()} | {cv.range[m].median():.0f} | {cv.rel[m].median():.2f} | "
                     f"{cv.inside[m].median():.1f} | {ivb[m].median():.1f} |")
    lab, q = tert(cv.rel.dropna())
    lab = lab.reindex(b.index)
    marks = (60, 120) if rule == "15min" else (15, 60)
    lines += ["", f"**Level holding by the candle's own volatility** (range vs last 20 candles: calm <= {q[0]:.2f}x, "
              f"wild > {q[1]:.2f}x; baseline = same distance after the other colour, same third)", "",
              "| candle / level | candle volatility | candles | " + " | ".join(f"held {m} min" for m in marks) + " | " +
              " | ".join(f"baseline {m} min" for m in marks) + " |", "|---|---|---|" + "---|" * (2 * len(marks))]
    rng = np.random.default_rng(0)
    names = {"low": "calm", "mid": "normal", "high": "wild"}
    for cname, sel, lvl, side, other in (("green / low", body > 0, b.low, 1, body < 0), ("red / high", body < 0, b.high, -1, body > 0)):
        for t3 in ("low", "mid", "high"):
            s = sel & (lab == t3)
            o = other & (lab == t3)
            bb = b[s]
            mins, left = minutes_to_break(ix, bb, lvl[s].values, side)
            d = ((b.close - b.low) if side > 0 else (b.high - b.close))[s].values
            ob = b[o]
            dist = rng.choice(d, size=len(ob))
            ol = ob.close.values - dist if side > 0 else ob.close.values + dist
            m2, l2 = minutes_to_break(ix, ob, ol, side)
            lines.append(f"| {cname} | {names[t3]} | {s.sum()} | " + " | ".join(f"{100 * held(mins, left, m):.0f}%" for m in marks)
                         + " | " + " | ".join(f"{100 * held(m2, l2, m):.0f}%" for m in marks) + " |")
    day = pd.Series(b.index.date, index=b.index)
    nxt = body.groupby(day).shift(-1)
    fut5 = b.close.groupby(day).shift(-5) - b.close
    half = b.index[len(b) // 2]
    lines += ["", "**What comes next, by the candle's own volatility**", "",
              "| candle | candle volatility | candles | next green | 1st / 2nd half | next avg pts | 5 candles later, avg pts |",
              "|---|---|---|---|---|---|---|"]
    for cname, sel in (("green", body > 0), ("red", body < 0)):
        for t3 in ("low", "mid", "high"):
            s = sel & (lab == t3) & nxt.notna()
            h = b.index < half
            lines.append(f"| {cname} | {names[t3]} | {s.sum()} | {100 * (nxt[s] > 0).mean():.1f}% | "
                         f"{100 * (nxt[s & h] > 0).mean():.1f}% / {100 * (nxt[s & ~h] > 0).mean():.1f}% | "
                         f"{nxt[s].mean():+.1f} | {fut5[s].mean():+.1f} |")
    return "\n".join(lines) + "\n"


def main():
    path = sys.argv[1]
    ix, oi, vol, strad = load(path)
    v = vol_series(path, ix, strad)
    out = [f"## Volatility behind the findings, BANKNIFTY {ix.index.min().date()} .. {ix.index.max().date()}", "",
           f"IV over the year: median {v.iv.median():.1f}% (10th-90th percentile {v.iv.quantile(.1):.1f}-{v.iv.quantile(.9):.1f}%). "
           f"RV: median {v.rv.median():.1f}% ({v.rv.quantile(.1):.1f}-{v.rv.quantile(.9):.1f}%).", ""]
    out += [section_candle(ix, v, "5min"), section_candle(ix, v, "15min"), section_days(ix, v), section_moment(ix, v, "5min"), section_moment(ix, v, "15min"),
            section_levels(ix, v, "15min", True), section_levels(ix, v, "5min", False),
            section_next(ix, v, "5min"), section_next(ix, v, "15min")]
    text = "\n".join(out)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
