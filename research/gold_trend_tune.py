"""Tuning the 4-hour Supertrend buy-only rule (the owner's ask, 2026-10-01: "edit some parameters, more profit and
less loss"), judged so that a better-looking setting has to earn it on data it never saw.

    python research/gold_trend_tune.py <xauusd_m1_bid.csv.gz> [out.md]

Grid (288 versions): chart 3h / 4h / 6h; ATR length 7 / 10 / 14 / 20; multiplier 2.5 / 3 / 3.5 / 4;
  exit "flip"   leave at the next hour's open after a chart bar closes below the Supertrend line (the baseline)
  exit "touch"  leave the moment an hour's low (bid) touches the line (the line of the last closed chart bar), at the
                line or the hour's open if it opened below; re-enter only on the next fresh flip up
  filter none / daily EMA 50 / daily EMA 200: enter only while the last daily close is above that average.
Chosen on the fitting years (Oct 2023 - Sep 2025) ONLY: each fitting year positive, ranked by fitting profit divided by
the fitting years' deepest drawdown (marked to market every hour, swap included). Then the held-out year
(Oct 2025 - Sep 2026) is read, unchanged. Costs as research/gold_trend.py: ask = bid + 0.30, $7 a lot, $40 a lot a
night swap (x3 Wednesdays). USD per standard lot.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import gold_session_strats as s  # noqa: E402
import gold_trend as gt  # noqa: E402
import liquidity_gold as g  # noqa: E402

H, COMM, SWAP = gt.H, gt.COMM, gt.SWAP


def supertrend_line(b, n, k):
    a = gt.atr(b, n).values
    h, l, c = b.high.values, b.low.values, b.close.values
    hl2 = (h + l) / 2
    ub, lb = hl2 + k * a, hl2 - k * a
    fu, fl, d = ub.copy(), lb.copy(), np.ones(len(c))
    for i in range(1, len(c)):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
        d[i] = 1 if c[i] > fu[i - 1] else (-1 if c[i] < fl[i - 1] else d[i - 1])
    return pd.Series(d > 0, index=b.index), pd.Series(fl, index=b.index)


def run(h1, up, line, allow, exit_mode):
    """up / line / allow: known at the start of each 1-hour bar. Entry at that bar's open on a fresh up state."""
    o, hi, lo, c, idx = h1.open.values, h1.high.values, h1.low.values, h1.close.values, h1.index
    u = up.values; ln = line.values; al = allow.values
    trades, entry, armed = [], None, True
    for i in range(1, len(h1)):
        if entry is None:
            if not u[i]:
                armed = True
            elif armed and al[i]:
                entry = (i, o[i] + H)
                armed = exit_mode == "flip"
            continue
        if not u[i]:                                   # the chart bar closed below the line
            trades.append((idx[entry[0]], entry[1], idx[i], o[i] - H)); entry = None; armed = True
            continue
        if exit_mode == "touch" and lo[i] - H <= ln[i]:
            trades.append((idx[entry[0]], entry[1], idx[i], min(ln[i], o[i] - H))); entry = None; armed = False
    if entry is not None:
        trades.append((idx[entry[0]], entry[1], idx[-1], c[-1] - H))
    return trades


def equity(trades, h1):
    """Hourly mark-to-market equity, USD a lot: costs on entry, swap on exit."""
    c = h1.close.values - H
    ix = h1.index
    ret = np.zeros(len(h1))
    for t0, e, t1, x in trades:
        i0, i1 = ix.get_indexer([t0])[0], ix.get_indexer([t1])[0]
        if i1 <= i0:
            ret[i0] += x - e - COMM
            continue
        ret[i0] += c[i0] - e - COMM                    # entry to the first hour's close
        ret[i0 + 1:i1] += np.diff(c[i0:i1])
        ret[i1] += x - c[i1 - 1] - SWAP * gt.nights(t0, t1)
    return pd.Series(100 * np.cumsum(ret), index=ix)


def dd(eq):
    return float((eq - eq.cummax()).min()) if len(eq) else 0.0


def main():
    mid = s.load(sys.argv[1])
    h1 = s.bars(mid, 60)
    d1 = s.bars(mid, 1440)
    years = sorted({g.year_of(d) for d in pd.Series(mid.index.date).unique()}, key=lambda x: x[4:8])
    fit, hold = years[:-1], years[-1:]
    yr = np.array([g.year_of(t) for t in h1.index.date])
    filters = {"none": pd.Series(True, index=h1.index)}
    for n_ in (50, 200):
        filters[f"daily EMA {n_}"] = gt.to_hours(d1.close > gt.ema(d1.close, n_), h1)
    rows = []
    for tf in (180, 240, 360):
        b = s.bars(mid, tf)
        for n in (7, 10, 14, 20):
            for k in (2.5, 3.0, 3.5, 4.0):
                d, fl = supertrend_line(b, n, k)
                up = gt.to_hours(d, h1)
                line = gt.to_hours(fl, h1).fillna(-np.inf)
                for ex in ("flip", "touch"):
                    for fname, allow in filters.items():
                        tr = run(h1, up, line, allow, ex)
                        eq = equity(tr, h1)
                        step = eq.diff().fillna(eq.iloc[0])
                        per = {y: float(step[yr == y].sum()) for y in years}
                        fit_eq = eq[np.isin(yr, fit)]
                        hold_eq = eq[np.isin(yr, hold)]
                        pnl = np.array([100 * (x - e - COMM - SWAP * gt.nights(t0, t1)) for t0, e, t1, x in tr])
                        t = pnl.mean() / (pnl.std(ddof=1) / len(pnl) ** 0.5) if len(pnl) > 1 else 0
                        rows.append(dict(name=f"{tf // 60}h ({n}, {k:g}), exit {ex}, filter {fname}", per=per,
                                         fit=sum(per[y] for y in fit), hold=sum(per[y] for y in hold),
                                         total=sum(per.values()), fit_dd=dd(fit_eq), hold_dd=dd(hold_eq), dd=dd(eq),
                                         n=len(tr), win=100 * float((pnl > 0).mean()) if len(pnl) else 0, t=t,
                                         fit_ok=all(per[y] > 0 for y in fit)))
                        print(rows[-1]["name"], round(rows[-1]["fit"]), round(rows[-1]["hold"]), round(rows[-1]["dd"]), flush=True)
    for r in rows:
        r["score"] = r["fit"] / -r["fit_dd"] if r["fit_dd"] < 0 else 0
    base = next(r for r in rows if r["name"] == "4h (10, 3), exit flip, filter none")
    ranked = sorted([r for r in rows if r["fit_ok"]], key=lambda r: -r["score"])
    hdr = ("| setting | " + " | ".join(years) + " | 3 years | trades | win | t | fitting drawdown | held-out drawdown | "
           "3-year drawdown | fitting profit / drawdown |")
    sep = "|---|" + "---|" * (len(years) + 8)

    def line_(r):
        return (f"| {r['name']} | " + " | ".join(f"{r['per'][y]:+,.0f}" for y in years) +
                f" | {r['total']:+,.0f} | {r['n']} | {r['win']:.0f}% | {r['t']:.2f} | {r['fit_dd']:,.0f} | "
                f"{r['hold_dd']:,.0f} | {r['dd']:,.0f} | {r['score']:.2f} |")
    beat = [r for r in ranked if r["score"] > base["score"]]
    beat_hold = [r for r in beat if r["hold"] > base["hold"] and r["hold_dd"] >= base["hold_dd"]]
    L = ["## Tuning the 4-hour Supertrend (research/gold_trend_tune.py)", "",
         f"{len(rows)} versions; {len(ranked)} positive in each fitting year; {len(beat)} beat the baseline's fitting "
         f"profit / drawdown; of those, {len(beat_hold)} also beat it in the held-out year on both profit and drawdown.", "",
         "Baseline:", "", hdr, sep, line_(base), "",
         "Top 25 by the fitting years (held-out columns read afterwards):", "", hdr, sep] + [line_(r) for r in ranked[:25]]
    for title, key in (("By exit", "exit "), ("By filter", "filter "), ("By chart", None)):
        L += ["", f"### {title} (averages over the grid)", "", "| group | versions | avg 3 years | avg fitting | avg held-out | avg 3-year drawdown |", "|---|---|---|---|---|---|"]
        groups = {}
        for r in rows:
            if key == "exit ":
                gname = r["name"].split("exit ")[1].split(",")[0]
            elif key == "filter ":
                gname = r["name"].split("filter ")[1]
            else:
                gname = r["name"].split(" ")[0]
            groups.setdefault(gname, []).append(r)
        for gname, rs in groups.items():
            L.append(f"| {gname} | {len(rs)} | {np.mean([r['total'] for r in rs]):+,.0f} | {np.mean([r['fit'] for r in rs]):+,.0f} | "
                     f"{np.mean([r['hold'] for r in rs]):+,.0f} | {np.mean([r['dd'] for r in rs]):,.0f} |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
