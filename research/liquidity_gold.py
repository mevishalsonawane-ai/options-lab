"""Liquidity 15+5 on XAUUSD (spot gold), research only (TODO R2).

    python research/liquidity_gold.py <xauusd_m1_bid.csv.gz> <xauusd_m1_ask.csv.gz> [out.md]

Data: Dukascopy 1-minute bid and ask candles (the gold.yml workflow puts them in the research-data release). Levels
are read on the mid price, continuous across sessions like a chart; the rule is the arm's: a POOL broken by a close
where an active SWING zone of the same side overlaps (swing lookback 20 full range, pools 2 contacts / 5 apart / 10
confirmation), enter at the next bar, exit at the first of: the next liquidity level touched, a close back through
the broken level (failed break), a new level on the trade's side, the session's cut-off. One position per book.
Gold has no option chain here, so a trade is the metal itself: a buy enters at the ask and sells at the bid (a
downside break is a short sale, reported separately because the owner buys only), plus $7 a lot (100 oz) round trip.
Results in USD an ounce; x100 for one standard lot.

Sessions (UTC): "London + New York" 07:00-21:00 (entries 07:05-19:00, out 20:40) and "India hours" 03:45-10:00 (the
NSE session's clock, 09:15-15:30 IST: entries 03:50-09:00, out 09:40). Years run October to September.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

COMM = 0.07        # USD an ounce round trip ($7 a 100 oz lot)
SPREAD = 0.30      # a typical XAUUSD spread, used only when the ask file is missing
SESSIONS = {
    "London + New York": (7 * 60, 21 * 60, 5, 12 * 60, 13 * 60 + 40),
    "India hours": (3 * 60 + 45, 10 * 60, 5, 315, 355),
}


def read(path):
    df = pd.read_csv(path)
    ts = df.columns[0]
    v = df[ts]
    if pd.api.types.is_numeric_dtype(v):             # epoch milliseconds (Dukascopy's default)
        df["ts"] = pd.to_datetime(v, unit="ms", utc=True).dt.tz_localize(None)
    else:
        df["ts"] = pd.to_datetime(v, utc=True).dt.tz_localize(None)
    df = df.set_index("ts")[["open", "high", "low", "close"]].sort_index()
    return df[~df.index.duplicated()]          # monthly downloads overlap by a day


def sessions(bid, ask, start, end):
    """Per session: minute grids of mid OHLC and bid / ask open & close, [end - start] minutes, forward-filled."""
    n = end - start
    mid = (bid + ask.reindex(bid.index)) / 2
    mid = mid.dropna()
    out = []
    for day, g in mid.groupby(mid.index.date):
        m = g.index.hour * 60 + g.index.minute - start
        ok = (m >= 0) & (m < n)
        if ok.sum() < 0.6 * n:
            continue
        idx = g.index[ok]
        grid = {}
        for src, name in ((g, "m"), (bid, "b"), (ask, "a")):
            s = src.loc[idx]
            for c in ("open", "high", "low", "close"):
                a = np.full(n, np.nan)
                a[m[ok]] = s[c].values
                grid[f"{name}{c}"] = a
        for name in ("m", "b", "a"):
            c = pd.Series(grid[f"{name}close"]).ffill().bfill().values
            grid[f"{name}close"] = c
            for k in ("open", "high", "low"):
                v = grid[f"{name}{k}"]
                grid[f"{name}{k}"] = np.where(np.isnan(v), c, v)
        out.append(dict(day=day, n=n, **grid))
    return out


def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        n = d["n"]
        for s in range(0, n, tf):
            e = min(s + tf, n)
            rows.append((di, s, e, d["mopen"][s], d["mhigh"][s:e].max(), d["mlow"][s:e].min(), d["mclose"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])


def simulate(days, b, zones, first, last, cut):
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    swings = [z for z in zones if z.kind == "swing"]
    trades, pos = [], None
    for i in range(len(b)):
        d = days[DI[i]]
        if pos is not None:
            sg = pos["sign"]
            why, px = None, None
            for m in range(max(S[i], pos["m"]), E[i]):
                if m >= cut:
                    why, px = "cut-off", d["bclose" if sg > 0 else "aclose"][m]
                    break
                if pos["target"] is not None and ((sg > 0 and d["mhigh"][m] >= pos["target"]) or (sg < 0 and d["mlow"][m] <= pos["target"])):
                    half = (d["aclose"][m] - d["bclose"][m]) / 2
                    why, px = "next liquidity", pos["target"] - sg * half
                    break
            if why is None and sg * (C[i] - pos["level"]) < 0:
                why, px = "failed break", d["bclose" if sg > 0 else "aclose"][E[i] - 1]
            if why is None and any(z.side == sg for z in known.get(i, [])):
                why, px = "new liquidity", d["bclose" if sg > 0 else "aclose"][E[i] - 1]
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, px = "cut-off", d["bclose" if sg > 0 else "aclose"][E[i] - 1]
            if why:
                trades.append(dict(day=pos["day"], sign=sg, why=why, usd=sg * (px - pos["px"]) - COMM))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (first <= m <= last):
            continue
        pl = [z for z in breaks.get(i, []) if z.kind == "pool"]
        cand = [p for p in pl if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                     and (s.broken < 0 or s.broken >= i) for s in swings)]
        if not cand:
            continue
        z = cand[0]
        sg = z.side
        px = d["aopen" if sg > 0 else "bopen"][m]
        mid = d["mopen"][m]
        ahead = [q.edge for q in zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i) and sg * (q.edge - mid) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        pos = dict(day=d["day"], sign=sg, m=m, px=px, level=z.edge, target=target)
    return pd.DataFrame(trades)


def year_of(day):
    y = day.year if day.month >= 10 else day.year - 1
    return f"Oct {y} - Sep {y + 1}"


def line(label, tr, ndays):
    if tr.empty:
        return f"| {label} | 0 | | | | | | |"
    x = tr.usd
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
    mo = tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").usd.sum()
    eq = x.cumsum()
    dd = (eq - eq.cummax()).min()
    return (f"| {label} | {len(tr)} ({len(tr) / ndays:.1f}/day) | {100 * (x > 0).mean():.0f}% | {x.mean():+.2f} | {t:.2f} | "
            f"{x.sum():+,.1f} | {100 * x.sum():+,.0f} (dd {100 * dd:,.0f}) | {(mo > 0).sum()}/{len(mo)} |")


def main():
    bid = read(sys.argv[1])
    # No ask file (the download is rate-limited): the bid plus a typical 0.30 spread.
    ask = read(sys.argv[2]) if os.path.exists(sys.argv[2]) else bid + SPREAD
    L = ["## Liquidity 15+5 on XAUUSD (research/liquidity_gold.py)", "",
         f"Dukascopy 1-minute bid/ask, {bid.index.min():%Y-%m-%d} .. {bid.index.max():%Y-%m-%d}. USD an ounce after the spread "
         "and $7 a lot; x100 = one standard lot (100 oz).", ""]
    for sname, (start, end, first, last, cut) in SESSIONS.items():
        days = sessions(bid, ask, start, end)
        L += [f"### {sname} ({len(days)} sessions)", "",
              "| year, books, direction | trades | win | USD/oz a trade | t | USD/oz total | USD per lot (max drawdown) | green months |",
              "|---|---|---|---|---|---|---|---|"]
        books = {}
        for tf in (5, 15, 30):
            b = bars(days, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            books[tf] = simulate(days, b, zones, first, last, cut)
        years = sorted({year_of(d["day"]) for d in days}, key=lambda s: s[4:8])
        for combo in ((15, 5), (30, 5)):
            tr = pd.concat([books[combo[0]], books[combo[1]]])
            tr["year"] = [year_of(d) for d in tr.day]
            for direction, sel in (("buys only", tr[tr.sign > 0]), ("buys + short sales", tr)):
                for y in years + ["all"]:
                    ys = sel if y == "all" else sel[sel.year == y]
                    nd = len(days) if y == "all" else sum(year_of(d["day"]) == y for d in days)
                    L.append(line(f"{y}, {combo[0]}+{combo[1]}-min, {direction}", ys, nd))
                    print(L[-1], flush=True)
        tr = pd.concat([books[15], books[5]])
        L += ["", f"Exits (15+5, both directions): " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in tr.why.value_counts(normalize=True).items()), ""]
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
