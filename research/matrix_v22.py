"""The "Matrix Premium v2.2" TP / SL framework on BANKNIFTY, buying options only.

    python research/matrix_v22.py <year.parquet> [out.md]

The description gives the risk engine, not the entry, so the entry is the structure break it is built around
(smart-money style, on the chart timeframe, continuous across days like a TradingView chart):
  swing pivots   fractal highs / lows, 3 bars each side (confirmed 3 bars later)
  signal         a bar CLOSES above the last unbroken swing high -> long (BUY the ATM CE),
                 below the last unbroken swing low -> short (BUY the ATM PE); a break against the current structure
                 is a CHoCH, with it a BOS. Entries 09:30-14:30, one position at a time, filled at the next bar's open.
Risk engine, exactly as described, levels on the INDEX:
  SL      the last swing low (long) / high (short) minus / plus [buf] x ATR(14, RMA) of the timeframe
  exit    a CHoCH against the position (a close through the last swing low / high) -> out at the next bar's open
  TP1     [tp1] R: half the position out, stop to entry (breakeven)
  rest    "fixed"   out at TP2 (2R)
          "lock"    at TP2 (2R) the stop locks to TP1; out at TP3 (4R, max expansion)
          "ribbon"  after TP1 the stop trails [2 x ATR] behind each bar's close, no cap
  15:10   anything left is squared off.
Trades 2 lots (60 qty) so half can go at TP1. Option fills: entry at the minute's open + 0.5, exits at the close of the
minute the index touched the level - 0.5 (stop checked before target inside a minute), Rs 40 per lot round trip.
R = the index result in units of the initial risk, weighted by the part of the position (was the chart read right?).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402

LOT, LOTS, SLIP, CHG = 30, 2, 0.5, 40.0
CUT = 15 * 60 + 10 - (9 * 60 + 15)            # 15:10
FIRST, LAST = 15, 5 * 60 + 15                  # entries 09:30 .. 14:30
PIV = 3


def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, 375, tf):
            e = min(s + tf, 375)
            rows.append((di, s, e, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
    b = pd.DataFrame(rows, columns=["di", "s", "e", "o", "h", "l", "c"])
    pc = b.c.shift().fillna(b.c)
    tr = np.maximum(b.h - b.l, np.maximum((b.h - pc).abs(), (b.l - pc).abs()))
    b["atr"] = tr.ewm(alpha=1 / 14, adjust=False).mean()
    return b


def simulate(days, tf, buf=2.0, tp1=1.0, mode="lock", choch_only=False):
    b = bars(days, tf)
    H, L, C, A = b.h.values, b.l.values, b.c.values, b.atr.values
    DI, S, E = b.di.values, b.s.values, b.e.values
    sh = sl = None               # last unbroken swing high / low (price)
    lsh = lsl = None             # last swing high / low (for stops), broken or not
    trend = 0
    trades, pos = [], None

    def opt(d, k, right, m, col):
        leg = d["chain"].get((k, right))
        return None if leg is None else leg[col][min(m, 374)]

    def exit_part(frac, m, ix, why):
        d = days[pos["di"]]
        px = opt(d, pos["k"], pos["right"], m, "close")
        pos["parts"].append((frac, ix, px, why, m))
        pos["left"] -= frac

    def finish():
        d = days[pos["di"]]
        rs, r = 0.0, 0.0
        for frac, ix, px, why, m in pos["parts"]:
            r += frac * pos["sign"] * (ix - pos["ix"]) / pos["risk"]
            rs += frac * LOTS * ((px - SLIP - pos["px"]) * LOT - CHG)
        trades.append(dict(day=d["day"], sign=pos["sign"], kind=pos["kind"], R=r, rs=rs, risk=pos["risk"],
                           why=pos["parts"][-1][3], tp1=any(p[3] == "tp1" for p in pos["parts"]),
                           held=pos["parts"][-1][4] - pos["m"]))

    for i in range(len(b)):
        di = DI[i]
        d = days[di]
        I = d["I"]
        # ---- manage the open position minute by minute through this bar
        if pos is not None:
            sg = pos["sign"]
            for m in range(max(S[i], pos["m"]), E[i]):
                if pos["left"] <= 1e-9:
                    break
                adverse = I["low"][m] if sg > 0 else I["high"][m]
                fav = I["high"][m] if sg > 0 else I["low"][m]
                if m >= CUT:
                    exit_part(pos["left"], m, I["close"][m], "15:10")
                    break
                if sg * (adverse - pos["stop"]) <= 0:
                    exit_part(pos["left"], m, pos["stop"], "breakeven" if pos["stop"] == pos["ix"] else
                              ("trail" if pos["tp1hit"] else "stop"))
                    break
                lvl = lambda R: pos["ix"] + sg * R * pos["risk"]  # noqa: E731
                if not pos["tp1hit"] and sg * (fav - lvl(tp1)) >= 0:
                    exit_part(0.5, m, lvl(tp1), "tp1")
                    pos["tp1hit"] = True
                    pos["stop"] = pos["ix"]
                if pos["tp1hit"] and mode == "fixed" and sg * (fav - lvl(2.0)) >= 0:
                    exit_part(pos["left"], m, lvl(2.0), "tp2")
                    break
                if pos["tp1hit"] and mode == "lock":
                    if sg * (fav - lvl(2.0)) >= 0:
                        pos["stop"] = lvl(tp1) if sg * (lvl(tp1) - pos["stop"]) > 0 else pos["stop"]
                    if sg * (fav - lvl(4.0)) >= 0:
                        exit_part(pos["left"], m, lvl(4.0), "tp3")
                        break
            if pos["left"] <= 1e-9:
                finish()
                pos = None
            elif mode == "ribbon" and pos["tp1hit"]:
                trail = C[i] - pos["sign"] * 2.0 * A[i]
                if pos["sign"] * (trail - pos["stop"]) > 0:
                    pos["stop"] = trail
        # ---- structure: confirm the pivot PIV bars back (same day or not: a continuous chart)
        j = i - PIV
        if j >= PIV:
            if H[j] == H[j - PIV:i + 1].max():
                sh = lsh = H[j]
            if L[j] == L[j - PIV:i + 1].min():
                sl = lsl = L[j]
        sig = 0
        if sh is not None and C[i] > sh:
            sig, kind = 1, "CHoCH" if trend < 0 else "BOS"
            sh = None
        elif sl is not None and C[i] < sl:
            sig, kind = -1, "CHoCH" if trend > 0 else "BOS"
            sl = None
        if sig:
            trend = sig
        # a CHoCH against the open position ends it at the next bar's open
        nxt = i + 1 < len(b) and DI[i + 1] == di
        if pos is not None and sig == -pos["sign"]:
            m = S[i + 1] if nxt else 374
            exit_part(pos["left"], m, I["open"][m] if nxt else I["close"][374], "choch")
            finish()
            pos = None
        if pos is not None and not nxt:              # the day ended with it open (should not happen: 15:10)
            exit_part(pos["left"], 374, I["close"][374], "15:10")
            finish()
            pos = None
        if pos is None and sig and nxt and FIRST <= S[i + 1] <= LAST and not (choch_only and kind != "CHoCH"):
            m = S[i + 1]
            ix = I["open"][m]
            stop = (lsl - buf * A[i]) if sig > 0 else (lsh + buf * A[i])
            if lsl is None or lsh is None or sig * (ix - stop) <= 0:
                continue
            right = "CE" if sig > 0 else "PE"
            ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
            if not len(ks):
                continue
            k = ks[np.argmin(np.abs(ks - ix))]
            px = opt(d, k, right, m, "open")
            if px is None or not np.isfinite(px):
                continue
            pos = dict(di=di, sign=sig, kind=kind, m=m, ix=ix, risk=abs(ix - stop), stop=stop, k=k, right=right,
                       px=px + SLIP, left=1.0, parts=[], tp1hit=False)
    return pd.DataFrame(trades)


def row(label, tr, alld):
    if tr.empty:
        return f"| {label} | 0 | | | | | | | |"
    half = set(alld[: len(alld) // 2])
    x = tr.rs
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))
    h1, h2 = tr[tr.day.isin(half)].rs.sum(), tr[~tr.day.isin(half)].rs.sum()
    m = tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").rs.sum()
    return (f"| {label} | {len(tr)} ({len(tr) / len(alld):.1f}/day) | {100 * (x > 0).mean():.0f}% | "
            f"{100 * tr.tp1.mean():.0f}% | {tr.R.mean():+.2f} | Rs {x.mean():+,.0f} | {t:.2f} | Rs {x.sum():+,.0f} | "
            f"{h1:+,.0f} / {h2:+,.0f} | {(m > 0).sum()}/{len(m)} |")


def main():
    days = load(sys.argv[1])
    alld = [d["day"] for d in days]
    out = [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
           "| version | trades | win (Rs) | TP1 hit | avg R (index) | per trade (2 lots) | t | net (2 lots) | 1st / 2nd half | green months |",
           "|---|---|---|---|---|---|---|---|---|---|"]
    runs = []
    for tf in (5, 15):
        for buf in (1.5, 2.0, 2.5):
            for tp1 in (1.0, 1.5):
                for mode in ("fixed", "lock", "ribbon"):
                    runs.append((f"{tf}-min, SL pivot {buf}xATR, TP1 {tp1}R, {mode}", dict(tf=tf, buf=buf, tp1=tp1, mode=mode)))
        runs.append((f"{tf}-min, CHoCH only, SL 2xATR, TP1 1R, lock", dict(tf=tf, buf=2.0, tp1=1.0, mode="lock", choch_only=True)))
        runs.append((f"{tf}-min, tight SL pivot 0.5xATR (outside the spec), TP1 1R, lock", dict(tf=tf, buf=0.5, tp1=1.0, mode="lock")))
    for label, kw in runs:
        tr = simulate(days, **kw)
        out.append(row(label, tr, alld))
        print(out[-1], flush=True)
        if label.startswith("5-min, SL pivot 2.0xATR, TP1 1.0R, lock"):
            ex = tr.why.value_counts(normalize=True)
            note = ("5-min default exits: " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in ex.items()) +
                    f"; median risk {tr.risk.median():.0f} index pts, median hold {tr.held.median():.0f} min")
    out += ["", note]
    text = "\n".join(out)
    print(note)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
