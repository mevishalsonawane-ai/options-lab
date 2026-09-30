"""The owner's "Matrix Premium v2.2 - Bank Nifty Option Buying Strategy" Pine script, on BANKNIFTY, buying options.

    python research/matrix_pine.py <year.parquet> [out.md]

Exactly as the script, on 5-minute bars of the spot index (EMA and ATR continuous across days, like the chart):
  signal   close crosses over EMA(20) -> BUY CE, crosses under -> BUY PE, on bars 09:20-15:15, not 11:00-13:14
  entry    at the signal bar's close (the option bought at the next minute's open + 0.5), only when flat
  SL       entry -/+ 2.7 x ATR(14) (ta.atr: RMA of the true range)
  TP2      entry +/- 2.5 x risk: the script's only target exit (TP1 at 1.2 R is drawn, not traded)
  15:15    square-off; opposite signals are ignored while a trade is open.
Stops and targets are checked on the index minute by minute from the entry (stop first inside a minute), the option
sold at that minute's close - 0.5. 1 lot of 30, Rs 40 a round trip; the nearest expiry after the day in the data
(BANKNIFTY weeklies ended in Nov 2024, so mostly the monthly), ATM and 2 strikes (200 pts) in the money.
Also: the TP1 partial (half out at 1.2 R, stop to breakeven, 2 lots) the write-up describes, and no dead zone.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402
from matrix_v22 import bars  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0
SQ = 15 * 60 + 15 - (9 * 60 + 15)             # minute index of 15:15


def simulate(days, itm=0, partial=False, dead=True, atr_mult=2.7, tp1r=1.2, tp2r=2.5):
    b = bars(days, 5)
    b["ema"] = b.c.ewm(span=20, adjust=False).mean()
    up = (b.c > b.ema) & (b.c.shift() <= b.ema.shift())
    dn = (b.c < b.ema) & (b.c.shift() >= b.ema.shift())
    t = b.s + 9 * 60 + 15                                  # bar start, minutes since midnight
    sess = (t >= 9 * 60 + 20) & (t <= 15 * 60 + 15)
    dz = (t >= 11 * 60) & (t < 13 * 60 + 15)
    ok = sess & (~dz if dead else True)
    lots = 2 if partial else 1
    trades = []
    i, n = 0, len(b)
    while i < n:
        r = b.iloc[i]
        sig = 1 if (up.iat[i] and ok.iat[i]) else (-1 if (dn.iat[i] and ok.iat[i]) else 0)
        if not sig or r.e >= 375:
            i += 1
            continue
        d = days[int(r.di)]
        I = d["I"]
        m0 = int(r.e)                                      # the next minute: the option is bought here
        if m0 > SQ:
            i += 1
            continue
        e_ix = r.c
        risk = atr_mult * r.atr
        stop, tp1, tp2 = e_ix - sig * risk, e_ix + sig * tp1r * risk, e_ix + sig * tp2r * risk
        right = "CE" if sig > 0 else "PE"
        ks = np.array(sorted({k for k, rr in d["chain"] if rr == right}))
        if not len(ks):
            i += 1
            continue
        k = ks[np.argmin(np.abs(ks - (e_ix - sig * itm)))]
        leg = d["chain"][(k, right)]
        opx = leg["open"][m0] + SLIP
        parts, left, hit1, m = [], 1.0, False, m0
        while m < 375 and left > 1e-9:
            lo, hi = I["low"][m], I["high"][m]
            adverse, fav = (lo, hi) if sig > 0 else (hi, lo)
            if m >= SQ:
                parts.append((left, I["close"][m], leg["close"][m], "15:15")); left = 0; break
            if sig * (adverse - stop) <= 0:
                parts.append((left, stop, leg["close"][m], "breakeven" if hit1 else "stop")); left = 0; break
            if partial and not hit1 and sig * (fav - tp1) >= 0:
                parts.append((0.5, tp1, leg["close"][m], "tp1")); left -= 0.5; hit1 = True; stop = e_ix
            if sig * (fav - tp2) >= 0:
                parts.append((left, tp2, leg["close"][m], "tp2")); left = 0; break
            m += 1
        if left > 1e-9:
            parts.append((left, I["close"][374], leg["close"][374], "15:15"))
            m = 374
        rs = sum(f * lots * ((px - SLIP - opx) * LOT - CHG) for f, _, px, _ in parts)
        R = sum(f * sig * (ix - e_ix) / risk for f, ix, _, _ in parts)
        trades.append(dict(day=d["day"], sign=sig, rs=rs, R=R, why=parts[-1][3], hour=(m0 + 555) // 60,
                           risk=risk, held=m - m0, hit1=hit1 or any(p[3] == "tp1" for p in parts)))
        # Pine checks entries before exits, so the next signal can come on the bar after the exit's bar
        j = i + 1
        while j < n and int(b.di.iat[j]) == int(r.di) and b.s.iat[j] <= m:
            j += 1
        i = j
    return pd.DataFrame(trades)


def row(label, tr, alld):
    half = set(alld[: len(alld) // 2])
    x = tr.rs
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))
    m = tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").rs.sum()
    return (f"| {label} | {len(tr)} ({len(tr) / len(alld):.1f}/day) | {100 * (x > 0).mean():.0f}% | "
            f"{tr.R.mean():+.2f} | Rs {x.mean():+,.0f} | {t:.2f} | Rs {x.sum():+,.0f} | "
            f"{tr[tr.day.isin(half)].rs.sum():+,.0f} / {tr[~tr.day.isin(half)].rs.sum():+,.0f} | {(m > 0).sum()}/{len(m)} |")


def main():
    days = load(sys.argv[1])
    alld = [d["day"] for d in days]
    out = [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
           "| version | trades | win (Rs) | avg R (index) | per trade | t | net for the year | 1st / 2nd half | green months |",
           "|---|---|---|---|---|---|---|---|---|"]
    runs = [("as written, ATM, 1 lot", dict()),
            ("as written, 2 strikes ITM, 1 lot", dict(itm=200)),
            ("+ TP1 partial (half at 1.2R, breakeven), ATM, 2 lots", dict(partial=True)),
            ("+ TP1 partial, 2 strikes ITM, 2 lots", dict(partial=True, itm=200)),
            ("no dead zone, ATM, 1 lot", dict(dead=False))]
    base = None
    for label, kw in runs:
        tr = simulate(days, **kw)
        base = tr if base is None else base
        out.append(row(label, tr, alld))
        print(out[-1], flush=True)
    ex = base.why.value_counts(normalize=True)
    out += ["", "As written, ATM: exits " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in ex.items()) +
            f"; median risk {base.risk.median():.0f} index pts (so TP2 is ~{2.5 * base.risk.median():.0f} pts away), "
            f"median hold {base.held.median():.0f} min.",
            "By entry hour (as written, ATM): " + ", ".join(
                f"{h}h {len(g)} trades Rs {g.rs.sum():+,.0f}" for h, g in base.groupby("hour"))]
    print("\n".join(out[-2:]))
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write("\n".join(out))


if __name__ == "__main__":
    main()
