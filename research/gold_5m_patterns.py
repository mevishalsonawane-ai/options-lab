"""Gold's 5-minute candles: when and why they are green or red, and whether runs of candles say what comes next
(the owner's ask, 2026-10-02: "cross check the 5 min timeframe, how and when and why the candles are green and red,
check multiple candles at once, and find a better solution").

    python research/gold_5m_patterns.py <xauusd_m1_bid.csv.gz> [out.md] [minutes, default 5]

Part 1 describes: green share and size by UTC hour and weekday; how the next candle's colour depends on the last one's
colour, size (body in units of the 14-candle ATR) and wicks, and on the 1-hour trend (close vs its 50-hour EMA).
Part 2 counts every colour run of the last 1-5 candles (GGRG...) with what followed: next candle green share, and the
mean move over the next 1 / 3 / 6 / 12 candles, in the fitting years and the held-out one.
Part 3 trades it: BUY at the next candle's open (ask) after a pattern, sell after N candles (bid), $7 a lot, each pattern
x context (any / 1h trend up / 1h trend down) x session (any / Asia 00-07 / London 07-13 / New York 13-20 UTC) x N.
Chosen on the fitting years only (Oct 2023 - Sep 2025: positive, t >= 2, 200+ trades), then read on the held-out year
(Oct 2025 - Sep 2026). USD per standard lot (100 oz); one trade at a time per version.
"""
from __future__ import annotations

import itertools
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import gold_session_strats as s  # noqa: E402
import liquidity_gold as g  # noqa: E402

H, COMM = 0.15, 0.07


def main():
    mid = s.load(sys.argv[1])
    tf = int(sys.argv[3]) if len(sys.argv) > 3 else 5
    b = s.bars(mid, tf)
    b = b[(b.index.hour != 21)]                                     # gold's daily break
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    n = len(c)
    idx = b.index
    yr = np.array([g.year_of(d) for d in idx.date])
    years = sorted(set(yr), key=lambda x: x[4:8])
    fit, hold = years[:-1], years[-1:]
    body = c - o
    col = np.sign(body)                                              # +1 green, -1 red, 0 doji
    tr = np.maximum(h - l, np.maximum(np.abs(h - np.r_[c[0], c[:-1]]), np.abs(l - np.r_[c[0], c[:-1]])))
    atr = pd.Series(tr).ewm(alpha=1 / 14, adjust=False).mean().values
    rel = np.abs(body) / np.where(atr > 0, atr, np.nan)
    up_w = h - np.maximum(o, c); lo_w = np.minimum(o, c) - l
    h1 = s.bars(mid, 60).close
    trend_up = (h1 > h1.ewm(span=50, adjust=False).mean()).shift(1).reindex(idx, method="ffill").fillna(False).values.astype(bool)
    hour = idx.hour.values
    sess = np.where(hour < 7, "Asia", np.where(hour < 13, "London", np.where(hour < 21, "New York", "late")))
    # Next-candle facts: same trading stretch (no gap over 10 minutes).
    gap_ok = np.r_[(idx[1:] - idx[:-1]) <= pd.Timedelta(minutes=2 * tf), False]
    nxt_green = np.r_[col[1:] > 0, False]
    L = [f"## Gold's {tf}-minute candles: green, red, and what comes next (research/gold_5m_patterns.py)", "",
         f"XAUUSD {idx[0]:%Y-%m-%d} .. {idx[-1]:%Y-%m-%d}, {n:,} {tf}-minute candles (mid price). Green = close above open.", ""]
    # ---- Part 1 -------------------------------------------------------------
    L += ["### Part 1: when, and after what", "",
          f"All candles: {100 * (col > 0).mean():.1f}% green, {100 * (col < 0).mean():.1f}% red, {100 * (col == 0).mean():.1f}% doji.", "",
          "| UTC hour | IST | green | median body $ | median range $ |", "|---|---|---|---|---|"]
    for hr in sorted(set(hour)):
        m = hour == hr
        L.append(f"| {hr:02d}:00 | {(hr * 60 + 330) // 60 % 24:02d}:{(hr * 60 + 330) % 60:02d} | {100 * (col[m] > 0).mean():.1f}% | "
                 f"{np.median(np.abs(body[m])):.2f} | {np.median((h - l)[m]):.2f} |")
    L += ["", "| weekday | green |", "|---|---|"]
    for d, nm in enumerate(["Mon", "Tue", "Wed", "Thu", "Fri"]):
        m = idx.dayofweek.values == d
        L.append(f"| {nm} | {100 * (col[m] > 0).mean():.1f}% |")
    base = 100 * nxt_green[gap_ok].mean()
    L += ["", f"The next candle is green {base:.1f}% of the time overall. After...", "",
          "| the last candle | share of candles | next green | next candle mean move $ |", "|---|---|---|---|"]
    nxt_move = np.r_[c[1:] - o[1:], 0.0]

    def row(label, m):
        m = m & gap_ok
        L.append(f"| {label} | {100 * m.mean():.1f}% | {100 * nxt_green[m].mean():.1f}% | {nxt_move[m].mean():+.3f} |")
    row("green", col > 0); row("red", col < 0)
    for lo_, hi_, nm in ((0, 0.3, "small (< 0.3 ATR)"), (0.3, 1.0, "medium"), (1.0, 99, "big (> 1 ATR)")):
        row(f"green, {nm}", (col > 0) & (rel >= lo_) & (rel < hi_)); row(f"red, {nm}", (col < 0) & (rel >= lo_) & (rel < hi_))
    row("long lower wick (> 2x body), any colour", lo_w > 2 * np.abs(body) + 1e-9)
    row("long upper wick (> 2x body), any colour", up_w > 2 * np.abs(body) + 1e-9)
    row("green, 1h trend up", (col > 0) & trend_up); row("green, 1h trend down", (col > 0) & ~trend_up)
    row("red, 1h trend up", (col < 0) & trend_up); row("red, 1h trend down", (col < 0) & ~trend_up)
    # ---- Part 2: runs ----------------------------------------------------------
    hz = (1, 3, 6, 12)
    fwd = {k: np.full(n, np.nan) for k in hz}
    # move from the next candle's open to the close k candles later (mid), only within one stretch
    stretch = np.cumsum(~np.r_[True, gap_ok[:-1]])
    for k in hz:
        j = np.arange(n) + k
        good = (j < n)
        jj = np.where(good, j, n - 1)
        same = good & (stretch[jj] == stretch) & (np.arange(n) + 1 < n)
        f = np.full(n, np.nan)
        nx = np.minimum(np.arange(n) + 1, n - 1)
        f[same] = c[jj][same] - o[nx][same]
        fwd[k] = f
    colstr = np.where(col > 0, "G", np.where(col < 0, "R", "D"))
    L += ["", "### Part 2: runs of candles (the last 1-5 colours, oldest first) and what followed", "",
          "Mean move = from the next candle's open to the close N candles later, $ an ounce (the cost of a trade is about $0.37).", "",
          "| run | candles | next green (fit / held out) | next 1 | next 3 | next 6 | next 12 (fit / held out) |", "|---|---|---|---|---|---|---|"]
    fitm, holdm = np.isin(yr, fit), np.isin(yr, hold)
    pats = {}
    for k in range(1, 6):
        key = colstr.copy()
        for back in range(1, k):
            key = np.char.add(np.r_[np.full(back, "X"), colstr[:-back]].astype("<U8"), key)
        pats[k] = key
        for p in ["".join(t) for t in itertools.product("GR", repeat=k)]:
            m = (key == p) & gap_ok
            if m.sum() < 500:
                continue
            ng = lambda mm: 100 * nxt_green[mm].mean()
            L.append(f"| {p} | {m.sum():,} | {ng(m & fitm):.1f}% / {ng(m & holdm):.1f}% | " +
                     " | ".join(f"{np.nanmean(fwd[q][m]):+.3f}" for q in (1, 3, 6)) +
                     f" | {np.nanmean(fwd[12][m & fitm]):+.3f} / {np.nanmean(fwd[12][m & holdm]):+.3f} |")
    # ---- Part 3: trades ----------------------------------------------------------
    rows = []
    ctx = {"any": np.ones(n, bool), "1h up": trend_up, "1h down": ~trend_up}
    ses = {"any": np.ones(n, bool), "Asia": sess == "Asia", "London": sess == "London", "New York": sess == "New York"}
    for k in range(1, 6):
        for p in ["".join(t) for t in itertools.product("GR", repeat=k)]:
            base_m = (pats[k] == p) & gap_ok
            for cn, cm in ctx.items():
                for sn, sm in ses.items():
                    m0 = base_m & cm & sm
                    for N in hz:
                        f = fwd[N]
                        sel = np.where(m0 & ~np.isnan(f))[0]
                        if len(sel) < 300:
                            continue
                        # one trade at a time: skip signals inside an open trade
                        keep, busy = [], -1
                        for i in sel:
                            if i > busy:
                                keep.append(i); busy = i + N
                        keep = np.array(keep)
                        pnl = 100 * (f[keep] - 2 * H - COMM)
                        tf, th = pnl[fitm[keep]], pnl[holdm[keep]]
                        if len(tf) < 200 or len(th) < 30:
                            continue
                        t = lambda x: x.mean() / (x.std(ddof=1) / len(x) ** 0.5) if len(x) > 1 and x.std() > 0 else 0
                        rows.append(dict(name=f"{p}, {cn}, {sn}, hold {N}", nf=len(tf), nh=len(th), fit=tf.sum(), hold=th.sum(),
                                         tf=t(tf), th=t(th), per=tf.mean(), perh=th.mean(), win=100 * (pnl > 0).mean()))
    picked = [r for r in rows if r["fit"] > 0 and r["tf"] >= 2]
    picked.sort(key=lambda r: -r["tf"])
    good = [r for r in picked if r["hold"] > 0]
    L += ["", "### Part 3: trading the patterns (buys only, as IraGoldAlgo)", "",
          f"{len(rows):,} versions tried; {len(picked)} pass the fitting years (positive, t >= 2, 200+ trades); "
          f"{len(good)} of those are also positive in the held-out year; at random about half would be.", "",
          "| version | trades fit / held out | per trade fit / held out | fitting (t) | held out (t) | win |", "|---|---|---|---|---|---|"]
    for r in picked[:30]:
        L.append(f"| {r['name']} | {r['nf']} / {r['nh']} | {r['per']:+.0f} / {r['perh']:+.0f} | {r['fit']:+,.0f} ({r['tf']:.2f}) | "
                 f"{r['hold']:+,.0f} ({r['th']:.2f}) | {r['win']:.0f}% |")
    L += ["", "### The red-run bounce as a trade (buy after 3-5 red candles in a row; every holding time, any trend, any session)", "",
          "| version | trades fit / held out | per trade fit / held out | fitting (t) | held out (t) | win |", "|---|---|---|---|---|---|"]
    for r in rows:
        if r["name"].split(",")[0] in ("RRR", "RRRR", "RRRRR") and ", any, any," in r["name"]:
            L.append(f"| {r['name']} | {r['nf']} / {r['nh']} | {r['per']:+.0f} / {r['perh']:+.0f} | {r['fit']:+,.0f} ({r['tf']:.2f}) | "
                     f"{r['hold']:+,.0f} ({r['th']:.2f}) | {r['win']:.0f}% |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
