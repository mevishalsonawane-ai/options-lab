"""Non-directional BUYING on BANKNIFTY: long straddles / strangles, and when they pay.

    python research/long_vol.py dump <year.parquet> <extra_series.parquet> <out_days.parquet>
    python research/long_vol.py report <days_y0.parquet> <days_y1.parquet> [out.md]

A long straddle (buy the ATM call AND put) needs no direction: it makes money when the index moves more than the
premium paid, either way. So the question is not "which way" but "when does BANKNIFTY move more than its options
charge". For every day, what the buyer could have known BEFORE entering, and what each trade made:

  known before entry   previous day's range (and NR4 / NR7 / inside day), CPR width, the opening gap, the first
                       5 minutes' range, the straddle's price vs the last 10 days' real moves (cheap or dear), India
                       VIX level and its 60-day rank, days to expiry, weekday, a 5-minute squeeze (Bollinger inside
                       Keltner)
  trades (1 lot each leg, real minute prices, nearest expiry after the day, 0.5 slippage a side, Rs 40 a leg)
      straddle at 09:20 / 09:30 / 10:00 / 11:00 / 13:00 / 14:00, out at 15:10
      straddle at 09:20 with a take-profit on the pair (+10/20/30/50%) and a stop (-20/-30/-40%)
      strangle 200 / 400 points out at 09:20, out at 15:10
      overnight straddle: bought 15:15, sold next morning 09:20 (the gap), same contracts
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))

LOT, SLIP, LEG = 30, 0.5, 40.0
CUT = 355                                   # 15:10
ENTRIES = {"0920": 5, "0930": 15, "1000": 45, "1100": 105, "1300": 225, "1400": 285}


def pair(d, k):
    ce, pe = d["chain"].get((k, "CE")), d["chain"].get((k, "PE"))
    return (ce, pe) if ce is not None and pe is not None else None


def atm(d, spot, width=0):
    ks = sorted({k for k, r in d["chain"] if r == "CE"} & {k for k, r in d["chain"] if r == "PE"})
    if not ks:
        return None
    k0 = min(ks, key=lambda k: abs(k - spot))
    if width == 0:
        return k0, k0
    kc = min(ks, key=lambda k: abs(k - (k0 + width)))
    kp = min(ks, key=lambda k: abs(k - (k0 - width)))
    return kc, kp


def legs_pnl(ce, pe, m0, m1):
    """Rupees for one lot of each leg bought at minute m0's open and sold at m1's close."""
    e = ce["open"][m0] + pe["open"][m0] + 2 * SLIP
    x = ce["close"][m1] + pe["close"][m1] - 2 * SLIP
    return (x - e) * LOT - 2 * LEG, e


def managed(ce, pe, m0, tp, sl):
    """Straddle from m0 with a take-profit / stop on the pair's value (minute closes), else 15:10."""
    e = ce["open"][m0] + pe["open"][m0] + 2 * SLIP
    for m in range(m0, CUT + 1):
        v = ce["close"][m] + pe["close"][m] - 2 * SLIP
        if v >= e * (1 + tp) or v <= e * (1 - sl) or m == CUT:
            return (v - e) * LOT - 2 * LEG


def day_rows(days, vix):
    rows = []
    closes5 = []
    for i, d in enumerate(days):
        I = d["I"]
        r = dict(day=d["day"], dte=(d["exp"] - d["day"]).days, wd=pd.Timestamp(d["day"]).weekday())
        if i >= 10:
            prev = [days[j]["I"] for j in range(i - 10, i)]
            rng = [(p["high"].max() - p["low"].min()) for p in prev]
            p1 = prev[-1]
            r["prev_range_pct"] = rng[-1] / p1["close"][-1] * 100
            r["nr7"] = rng[-1] <= min(rng[-7:])
            r["nr4"] = rng[-1] <= min(rng[-4:])
            p2 = prev[-2]
            r["inside"] = p1["high"].max() <= p2["high"].max() and p1["low"].min() >= p2["low"].min()
            r["avg_range10"] = float(np.mean(rng))
            r["avg_oc10"] = float(np.mean([abs(p["close"][CUT] - p["open"][5]) for p in prev]))
            r["gap_pct"] = (I["open"][0] - p1["close"][-1]) / p1["close"][-1] * 100
        r["cpr"] = d.get("cpr", np.nan)
        r["first5_range"] = I["high"][:5].max() - I["low"][:5].min()
        # day outcome (for description only, never a feature)
        r["day_range"] = I["high"].max() - I["low"].min()
        r["move_0920_1510"] = I["close"][CUT] - I["open"][5]
        # squeeze on the 5-minute closes up to 09:20 (continuous across days)
        closes5.extend(list(I["close"][4::5]))
        cs = pd.Series(closes5)
        if len(cs) > 40:
            last = len(I["close"][4::5])
            s = cs.iloc[: len(cs) - last + 1]          # up to this day's 09:15-09:20 bar
            m20 = s.rolling(20).mean().iloc[-1]
            sd = s.rolling(20).std(ddof=0).iloc[-1]
            tr = s.diff().abs().rolling(20).mean().iloc[-1]
            r["squeeze"] = bool(2 * sd < 1.5 * tr * 1.0) if np.isfinite(sd) else np.nan
        v = vix.get(d["day"])
        if v is not None:
            r["vix"], r["vix_rank60"] = v
        # trades
        spot = I["open"][5]
        k = atm(d, spot)
        if k and pair(d, k[0]):
            ce, pe = pair(d, k[0])
            pnl, cost = legs_pnl(ce, pe, 5, CUT)
            r["straddle_cost"] = cost
            r["implied_move_pct"] = cost / spot * 100
            if "avg_range10" in r:
                r["cost_vs_range10"] = cost / r["avg_range10"]
                r["cost_vs_oc10"] = cost / r["avg_oc10"]
            for tp in (0.1, 0.2, 0.3, 0.5):
                for sl in (0.2, 0.3, 0.4):
                    r[f"s0920_tp{int(tp*100)}_sl{int(sl*100)}"] = managed(ce, pe, 5, tp, sl)
        for name, m0 in ENTRIES.items():
            kk = atm(d, I["open"][m0])
            if kk and pair(d, kk[0]):
                ce, pe = pair(d, kk[0])
                r[f"s{name}"] = legs_pnl(ce, pe, m0, CUT)[0]
        for w in (200, 400):
            kk = atm(d, spot, w)
            if kk and d["chain"].get((kk[0], "CE")) is not None and d["chain"].get((kk[1], "PE")) is not None:
                ce, pe = d["chain"][(kk[0], "CE")], d["chain"][(kk[1], "PE")]
                r[f"strangle{w}"] = legs_pnl(ce, pe, 5, CUT)[0]
        # overnight: bought 15:15 today, sold 09:20 tomorrow, same contracts (same expiry only)
        if i + 1 < len(days) and days[i + 1]["exp"] == d["exp"]:
            kk = atm(d, I["close"][360])
            n = days[i + 1]
            if kk and pair(d, kk[0]) and pair(n, kk[0]):
                ce, pe = pair(d, kk[0])
                ce2, pe2 = pair(n, kk[0])
                e = ce["open"][360] + pe["open"][360] + 2 * SLIP
                x = ce2["close"][5] + pe2["close"][5] - 2 * SLIP
                r["overnight"] = (x - e) * LOT - 2 * LEG
                r["overnight_gap_pts"] = n["I"]["open"][0] - I["close"][360]
        rows.append(r)
    return pd.DataFrame(rows)


def vix_by_day(path):
    e = pd.read_parquet(path)
    v = e[e.series.astype(str) == "INDIAVIX"].copy()
    v["ts"] = pd.to_datetime(v.ts)
    v["day"] = v.ts.dt.date
    first = v[v.ts.dt.time <= pd.Timestamp("09:20").time()].groupby("day").close.last()
    last = v.groupby("day").close.last()
    rank = last.rolling(60, min_periods=20).apply(lambda x: (x[:-1] <= x[-1]).mean(), raw=True).shift()
    return {d: (float(first.get(d, np.nan)), float(rank.get(d, np.nan))) for d in last.index}


def dump(year, extra, out):
    from sell_levels import load
    days = load(year)
    day_rows(days, vix_by_day(extra)).to_parquet(out)
    print(out, len(days), "days")


# ---- report --------------------------------------------------------------------------------------------------

def stat(x):
    x = pd.Series(x).dropna()
    if len(x) < 5:
        return f"{len(x)} | | | |"
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))
    return f"{len(x)} | {100 * (x > 0).mean():.0f}% | Rs {x.mean():+,.0f} | Rs {x.sum():+,.0f} | {t:.2f}"


def report(p0, p1, out=None):
    y0, y1 = pd.read_parquet(p0), pd.read_parquet(p1)
    L = ["## Non-directional buying on BANKNIFTY: long straddles / strangles (research/long_vol.py)", "",
         f"Year A = {y0.day.min()} .. {y0.day.max()} ({len(y0)} days), Year B = {y1.day.min()} .. {y1.day.max()} "
         f"({len(y1)} days). 1 lot each leg, real minute prices, 0.5 slippage a side, Rs 40 a leg. A rule counts only "
         "if it makes money in BOTH years.", ""]

    def two(label, a, b):
        L.append(f"| {label} | {stat(a)} | {stat(b)} |")

    head = "| trade / filter | A days | A win | A avg | A total | A t | B days | B win | B avg | B total | B t |"
    sep = "|---|---|---|---|---|---|---|---|---|---|---|"
    L += ["### Every day, no filter", "", head, sep]
    cols = [c for c in y1.columns if c.startswith(("s0", "s1", "strangle", "overnight")) and c != "straddle_cost"
            and not c.endswith("_pts")]
    for c in cols:
        two(c, y0.get(c), y1.get(c))

    L += ["", "### Filters on the 09:20 straddle held to 15:10 (terciles; cut points from year B, applied to both)", "",
          head, sep]
    feats = ["prev_range_pct", "cost_vs_range10", "cost_vs_oc10", "implied_move_pct", "gap_pct", "first5_range",
             "cpr", "vix", "vix_rank60", "dte"]
    best = []
    for f in feats:
        if f not in y1:
            continue
        g1 = y1[f].abs() if f == "gap_pct" else y1[f]
        g0 = y0[f].abs() if f == "gap_pct" else y0[f]
        q = g1.quantile([1 / 3, 2 / 3]).values
        for lab, lo, hi in (("low", -np.inf, q[0]), ("mid", q[0], q[1]), ("high", q[1], np.inf)):
            a = y0.s0920[(g0 > lo) & (g0 <= hi)]
            b = y1.s0920[(g1 > lo) & (g1 <= hi)]
            two(f"{'|gap|' if f == 'gap_pct' else f} {lab} ({lo:.2f}..{hi:.2f})", a, b)
            if len(a.dropna()) > 20 and len(b.dropna()) > 20:
                best.append((min(a.mean(), b.mean()), f, lab, lo, hi))
    for f in ("nr7", "nr4", "inside", "squeeze"):
        if f in y1:
            for v in (True, False):
                two(f"{f} = {v}", y0.s0920[y0[f] == v], y1.s0920[y1[f] == v])
    for wd in range(5):
        two(f"weekday {['Mon', 'Tue', 'Wed', 'Thu', 'Fri'][wd]}", y0.s0920[y0.wd == wd], y1.s0920[y1.wd == wd])

    L += ["", "### The best single filters (ranked by the WORSE of the two years' average)", "", head, sep]
    for worst, f, lab, lo, hi in sorted(best, reverse=True)[:8]:
        g0 = y0[f].abs() if f == "gap_pct" else y0[f]
        g1 = y1[f].abs() if f == "gap_pct" else y1[f]
        two(f"{f} {lab}", y0.s0920[(g0 > lo) & (g0 <= hi)], y1.s0920[(g1 > lo) & (g1 <= hi)])

    L += ["", "### Why straddles lose on an ordinary day", ""]
    for nm, y in (("A", y0), ("B", y1)):
        ok = y.dropna(subset=["straddle_cost"])
        L.append(f"- Year {nm}: the 09:20 straddle cost {ok.straddle_cost.median():.0f} pts (median; "
                 f"{ok.implied_move_pct.median():.2f}% of spot); the index then moved a median "
                 f"{ok.move_0920_1510.abs().median():.0f} pts by 15:10 (a straddle held to 15:10 needs more than its "
                 f"cost minus the time value left); it moved more than the cost on "
                 f"{100 * (ok.move_0920_1510.abs() > ok.straddle_cost).mean():.0f}% of days.")
    text = "\n".join(L)
    print(text)
    if out:
        open(out, "w").write(text)


if __name__ == "__main__":
    if sys.argv[1] == "dump":
        dump(*sys.argv[2:5])
    else:
        report(*sys.argv[2:])
