"""Enhancing IraGoldAlgo's 1-hour liquidity arm (the owner's ask, 2026-10-01: "enhance our liquidity strategies").

    python research/gold_1h_plus.py <xauusd_m1_bid.csv.gz> [out.md]

The arm as the app trades it (research/gold_24x5.py, "24x5, held overnight": buys only, held until the next liquidity
level / a failed break / new liquidity / Friday 20:40 UTC), plus one change at a time:
  trend filters  buy only when the signal candle closes above its 50 / 100 / 200-hour EMA, or when yesterday's close
                 was above its 20-day EMA (gold has fallen for weeks: buying breaks against the trend may be the losers)
  hours          no buys from the Asian session (entries 00:00-06:59 UTC)
  levels         swing lookback 10 / 30 (20 now), pool confirmation 5 / 15 (10 now)
  exits          no "new liquidity" exit (hold to the target or the failed break); target the second level up
Honest test: every variant is judged on the first two years (Oct 2023 - Sep 2025) and then, unchanged, on the held-out
third (Oct 2025 - Sep 2026). A variant is worth taking only if it beats the arm in both, not just in total.
Dukascopy 1-minute bid, ask = bid + 0.30, $7 a lot; USD per standard lot (100 oz) after costs.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import liquidity_gold as g  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

FRIDAY_CUT = 20 * 60 + 40
START, END, FIRST, LAST = 0, 24 * 60, 5, 24 * 60 - 60


def simulate(days, b, zones, allow=None, new_liq_exit=True, second_target=False):
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
        friday = d["day"].weekday() == 4
        if pos is not None:
            why, px = None, None
            for m in range(max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i], E[i]):
                if friday and m >= FRIDAY_CUT - START:
                    why, px = "cut-off", d["bclose"][m]
                    break
                if pos["target"] is not None and d["mhigh"][m] >= pos["target"]:
                    why, px = "next liquidity", pos["target"] - (d["aclose"][m] - d["bclose"][m]) / 2
                    break
            if why is None and C[i] - pos["level"] < 0:
                why, px = "failed break", d["bclose"][E[i] - 1]
            if why is None and new_liq_exit and any(z.side > 0 for z in known.get(i, [])):
                why, px = "new liquidity", d["bclose"][E[i] - 1]
            if why is None and (friday and (i + 1 >= len(b) or DI[i + 1] != DI[i]) or i + 1 >= len(b)):
                why, px = "cut-off", d["bclose"][E[i] - 1]
            if why:
                trades.append(dict(day=pos["day"], at=pd.Timestamp(pos["day"]) + pd.Timedelta(minutes=int(pos["m"]) + START),
                                   why=why, usd=100 * (px - pos["px"] - g.COMM)))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (FIRST <= m <= LAST) or (friday and m + START > 19 * 60):
            continue
        if allow is not None and not allow(i, m):
            continue
        pl = [z for z in breaks.get(i, []) if z.kind == "pool" and z.side > 0]
        cand = [p for p in pl if any(s.side > 0 and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                     and (s.broken < 0 or s.broken >= i) for s in swings)]
        if not cand:
            continue
        z = cand[0]
        mid = d["mopen"][m]
        ahead = sorted(q.edge for q in zones if q.side > 0 and q.known <= i and (q.broken < 0 or q.broken > i) and q.edge - mid > 0)
        tgt = (ahead[1] if second_target and len(ahead) > 1 else ahead[0]) if ahead else None
        pos = dict(day=d["day"], di=DI[i], m=m, px=d["aopen"][m], level=z.edge, target=tgt)
    return pd.DataFrame(trades)


def summary(tr, fit, hold):
    x = tr.usd
    f = tr[tr.year.isin(fit)].usd
    h = tr[tr.year.isin(hold)].usd
    t = x.mean() / (x.std(ddof=1) / len(x) ** 0.5) if len(x) > 1 else 0
    eq = x.cumsum()
    return dict(fit=f.sum(), hold=h.sum(), total=x.sum(), n=len(x), win=100 * (x > 0).mean(), t=t, dd=(eq - eq.cummax()).min())


def main():
    bid = g.read(sys.argv[1])
    ask = bid + g.SPREAD
    days = g.sessions(bid, ask, START, END)
    b = g.bars(days, 60)
    years = sorted({g.year_of(d["day"]) for d in days}, key=lambda s: s[4:8])
    fit, hold = years[:-1], years[-1:]
    C = b.close
    ema = {n: C.ewm(span=n, adjust=False).mean().values for n in (50, 100, 200)}
    # Yesterday's daily close above its 20-day EMA (only days already finished are used).
    dclose = b.groupby("di").close.last()
    dema = dclose.ewm(span=20, adjust=False).mean()
    up_day = {di: bool(dclose.get(di - 1, np.nan) > dema.get(di - 1, np.nan)) for di in dclose.index}
    Cv, DIv = C.values, b.di.values

    def zones_for(sw=20, conf=10):
        return swing_zones(b, sw, "full") + pool_zones(b, 2, 5, conf)

    base_z = zones_for()
    variants = [
        ("the arm today", base_z, {}),
        ("trend: above the 50-hour EMA", base_z, dict(allow=lambda i, m: Cv[i] > ema[50][i])),
        ("trend: above the 100-hour EMA", base_z, dict(allow=lambda i, m: Cv[i] > ema[100][i])),
        ("trend: above the 200-hour EMA", base_z, dict(allow=lambda i, m: Cv[i] > ema[200][i])),
        ("trend: yesterday above its 20-day EMA", base_z, dict(allow=lambda i, m: up_day.get(DIv[i], False))),
        ("hours: no Asian-session buys (00-07 UTC)", base_z, dict(allow=lambda i, m: m + START >= 7 * 60)),
        ("levels: swing lookback 10", zones_for(sw=10), {}),
        ("levels: swing lookback 30", zones_for(sw=30), {}),
        ("levels: pool confirmation 5", zones_for(conf=5), {}),
        ("levels: pool confirmation 15", zones_for(conf=15), {}),
        ("exit: no 'new liquidity' exit", base_z, dict(new_liq_exit=False)),
        ("exit: target the second level up", base_z, dict(second_target=True)),
        ("combined: confirmation 15 + second level", zones_for(conf=15), dict(second_target=True)),
        ("robustness: confirmation 12 + second level", zones_for(conf=12), dict(second_target=True)),
        ("robustness: confirmation 20 + second level", zones_for(conf=20), dict(second_target=True)),
        ("robustness: confirmation 15 + second level, swing 30", zones_for(sw=30, conf=15), dict(second_target=True)),
        ("combined: confirmation 15 + above the 100-hour EMA", zones_for(conf=15), dict(allow=lambda i, m: Cv[i] > ema[100][i])),
    ]
    rows = []
    for label, z, kw in variants:
        tr = simulate(days, b, z, **kw)
        tr["year"] = [g.year_of(d) for d in tr.day]
        s = summary(tr, fit, hold)
        s["years"] = {y: tr[tr.year == y].usd.sum() for y in years}
        rows.append((label, s))
        print(label, {k: round(v, 2) for k, v in s.items() if k != "years"}, flush=True)
    base = rows[0][1]
    L = ["## Enhancing IraGoldAlgo's 1-hour liquidity arm (research/gold_1h_plus.py)", "",
         f"XAUUSD, Dukascopy 1-minute bid {bid.index.min():%Y-%m-%d} .. {bid.index.max():%Y-%m-%d}; ask = bid + 0.30, $7 a lot. "
         f"USD per standard lot after costs. Fitting years: {', '.join(fit)}; held out: {', '.join(hold)}. "
         "A change is worth taking only if it beats the arm in BOTH the fitting years and the held-out year.", "",
         "| change | " + " | ".join(years) + " | fitting | held out | 3 years | trades | win | t | max drawdown | verdict |",
         "|---|" + "---|" * (len(years) + 9)]
    for label, s in rows:
        better = s["fit"] > base["fit"] and s["hold"] > base["hold"]
        verdict = "-" if s is base else ("**better in both**" if better else "no")
        L.append(f"| {label} | " + " | ".join(f"{s['years'].get(y, 0):+,.0f}" for y in years) +
                 f" | {s['fit']:+,.0f} | {s['hold']:+,.0f} | {s['total']:+,.0f} | {s['n']} | {s['win']:.0f}% | {s['t']:.2f} | {s['dd']:,.0f} | {verdict} |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
