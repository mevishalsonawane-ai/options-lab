"""IraGoldAlgo around the clock (the owner's ask, 2026-10-01): the XAUUSD 1-hour liquidity rule, buys only as the app
trades it (a short signal is ignored), on the session it trades today and on a 24x5 chart. Dukascopy 1-minute bid,
ask = bid + 0.30, $7 a lot; USD per standard lot (100 oz).

    python research/gold_24x5.py <xauusd_m1_bid.csv.gz> [out.md]

  London + New York (today)   chart 07:00-21:00 UTC, buys 08:00-19:00, out by 20:40 the same day
  all day, out daily          chart 00:00-21:00 UTC, buys 01:00-19:00, out by 20:40 the same day
  all day, held overnight     chart 00:00-21:00 UTC, buys 01:00-19:00, held until an exit; out by Friday 20:40
  24x5, held overnight        chart every hour Monday-Friday (the 21:00-22:00 UTC break is flat), buys any hour
                              (not after 19:00 on Friday), held until an exit; out by Friday 20:40
"""
from __future__ import annotations

import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import liquidity_gold as g  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

FRIDAY_CUT = 20 * 60 + 40
SWAP = 0.40          # USD an ounce a night held past the 21:00 UTC rollover ($40 a lot; an assumption - XM's varies), x3 on Wednesday


def simulate(days, b, zones, first, last, cut, start, carry):
    """Buys only. [first]..[last]: entry minutes from the day's start; [cut]: the daily cut-off (carry: Fridays only)."""
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
        day_cut = (FRIDAY_CUT - start) if carry else cut
        cut_today = (not carry) or friday
        if pos is not None:
            why, px = None, None
            for m in range(max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i], E[i]):
                if cut_today and m >= day_cut:
                    why, px = "cut-off", d["bclose"][m]
                    break
                if pos["target"] is not None and d["mhigh"][m] >= pos["target"]:
                    why, px = "next liquidity", pos["target"] - (d["aclose"][m] - d["bclose"][m]) / 2
                    break
            if why is None and C[i] - pos["level"] < 0:
                why, px = "failed break", d["bclose"][E[i] - 1]
            if why is None and any(z.side > 0 for z in known.get(i, [])):
                why, px = "new liquidity", d["bclose"][E[i] - 1]
            last_bar = i + 1 >= len(b) or DI[i + 1] != DI[i]
            if why is None and last_bar and (not carry or friday or i + 1 >= len(b)):
                why, px = "cut-off", d["bclose"][E[i] - 1]
            if why:
                xm = E[i] - 1
                nights = 0
                for k in range(pos["di"], DI[i] + 1):                   # each 21:00 UTC rollover between entry and exit
                    roll = 21 * 60 - start
                    if not (0 <= roll < days[k]["n"] + 60):
                        continue
                    after_entry = k > pos["di"] or pos["m"] < roll
                    before_exit = k < DI[i] or xm >= roll
                    if after_entry and before_exit:
                        nights += 3 if days[k]["day"].weekday() == 2 else 1
                trades.append(dict(day=pos["day"], why=why, usd=px - pos["px"] - g.COMM, nights=nights,
                                   held=(DI[i] - pos["di"]) * 1440 + (E[i] - pos["m"])))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (first <= m <= last) or (carry and friday and m + start > 19 * 60):
            continue
        pl = [z for z in breaks.get(i, []) if z.kind == "pool" and z.side > 0]
        cand = [p for p in pl if any(s.side > 0 and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                     and (s.broken < 0 or s.broken >= i) for s in swings)]
        if not cand:
            continue
        z = cand[0]
        mid = d["mopen"][m]
        ahead = [q.edge for q in zones if q.side > 0 and q.known <= i and (q.broken < 0 or q.broken > i) and q.edge - mid > 0]
        pos = dict(day=d["day"], di=DI[i], m=m, px=d["aopen"][m], level=z.edge, target=min(ahead) if ahead else None)
    return pd.DataFrame(trades)


def main():
    bid = g.read(sys.argv[1])
    ask = bid + g.SPREAD
    # (label, chart start, chart end, first entry, last entry, daily cut, held overnight) - minutes UTC
    variants = [
        ("London + New York (the app today)", 7 * 60, 21 * 60, 60, 12 * 60, 13 * 60 + 40, False),
        ("all day, out daily", 0, 21 * 60, 60, 19 * 60, 20 * 60 + 40, False),
        ("all day, held overnight", 0, 21 * 60, 60, 19 * 60, 20 * 60 + 40, True),
        ("24x5, held overnight", 0, 24 * 60, 5, 24 * 60 - 60, 20 * 60 + 40, True),
    ]
    L = ["## IraGoldAlgo around the clock (research/gold_24x5.py)", "",
         f"XAUUSD 1-hour liquidity, buys only as the app trades it. Dukascopy {bid.index.min():%Y-%m-%d} .. {bid.index.max():%Y-%m-%d}, "
         "ask = bid + 0.30, $7 a lot. USD per standard lot (100 oz) after costs. Years October to September.", ""]
    rows = []
    for label, start, end, first, last, cut, carry in variants:
        days = g.sessions(bid, ask, start, end)
        b = g.bars(days, 60)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        tr = simulate(days, b, zones, first, last, cut, start, carry)
        tr["year"] = [g.year_of(d) for d in tr.day]
        years = sorted(set(tr.year), key=lambda s: s[4:8])
        eq = tr.usd.cumsum()
        dd = (eq - eq.cummax()).min()
        x = tr.usd
        t = x.mean() / (x.std(ddof=1) / len(x) ** 0.5)
        mo = tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").usd.sum()
        tr["net"] = tr.usd - SWAP * tr.nights
        rows.append((label, years, {y: 100 * tr[tr.year == y].usd.sum() for y in years}, 100 * x.sum(), len(tr), 100 * (x > 0).mean(),
                     t, 100 * dd, 100 * x.min(), (mo > 0).sum(), len(mo), tr.held.median() / 60,
                     int(tr.nights.sum()), {y: 100 * tr[tr.year == y].net.sum() for y in years}, 100 * tr.net.sum()))
        print(rows[-1], flush=True)
    years = rows[0][1]
    L += ["| version | " + " | ".join(years) + " | 3 years | trades | win | t | max drawdown | worst trade | green months | median hold |",
          "|---|" + "---|" * (len(years) + 9)]
    for label, _, per, tot, n, win, t, dd, worst, gm, nm, hold, *_ in rows:
        L.append(f"| {label} | " + " | ".join(f"{per.get(y, 0):+,.0f}" for y in years) +
                 f" | {tot:+,.0f} | {n} | {win:.0f}% | {t:.2f} | {dd:,.0f} | {worst:+,.0f} | {gm}/{nm} | {hold:.1f} h |")
    L += ["", f"After an overnight swap of ${100 * SWAP:.0f} a lot a night (x3 Wednesday; an assumption, XM's rate varies):", "",
          "| version | nights held | " + " | ".join(years) + " | 3 years after swap |", "|---|---|" + "---|" * (len(years) + 1)]
    for label, *_, nights, pernet, totnet in rows:
        L.append(f"| {label} | {nights} | " + " | ".join(f"{pernet.get(y, 0):+,.0f}" for y in years) + f" | {totnet:+,.0f} |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
