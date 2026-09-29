"""Selling a protected spread beyond a big 15-minute candle's level, on the year.

    python research/sell_levels.py <year.parquet> [out.md]

Signal: a 15-minute candle (09:15 .. 14:30) whose body is in the top 20% of the previous 20 days' 15-minute bodies.
  big GREEN -> sell a PUT spread: short the first listed strike at or below the candle's LOW, long the strike W lower
  big RED   -> sell a CALL spread: short the first strike at or above the candle's HIGH, long the strike W higher
Entry at the first minute after the candle (sell at the minute's open -0.5, buy the wing at open +0.5).
Exits: "15:10"  hold to 15:10 the same day
       "level"  buy back when a 5-minute close goes through the candle's low (green) / high (red), else 15:10
       "next day" hold to 15:10 the NEXT day (same expiry and strikes needed), level exit active on both days
Costs: Rs 80 a round trip (two legs), 1 lot of 30. Filters: the EMA/VWAP direction (the 5-minute close on the
candle's side of the 9 EMA and the day's VWAP), and skipping wide-CPR days (the year's widest third).
Comparisons: buying the ATM option in the candle's direction (level exit), and the same spread sold at an ordinary
15-minute close at the same distance from the price (no big candle).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from ml_long import grid, N  # noqa: E402

LOT, SLIP, CHG2, CHG1 = 30, 0.5, 80.0, 40.0
CUT = 15 * 60 + 10 - (9 * 60 + 15)


def load(path):
    df = pd.read_parquet(path)
    df["ts"] = pd.to_datetime(df.ts)
    df["expiry"] = df.expiry.dt.date
    days = []
    for day, g in df.groupby(df.day.dt.date):
        ix = g[g.right == "IX"].sort_values("ts").set_index("ts")
        if len(ix) < 300:
            continue
        I = grid(ix, ["open", "high", "low", "close"])
        o = g[g.right != "IX"]
        exps = sorted(e for e in o.expiry.unique() if e > day)
        if not exps:
            continue
        ch = o[o.expiry == exps[0]]
        chain = {}
        for (k, r), s in ch.groupby(["strike", "right"]):
            if len(s) > 150:
                chain[(k, r)] = grid(s.sort_values("ts").set_index("ts"), ["open", "high", "low", "close"])
        days.append(dict(day=day, exp=exps[0], I=I, chain=chain))
    # 5-minute closes -> 9 EMA (continuous), VWAP per day, CPR width
    closes = np.concatenate([d["I"]["close"][4::5] for d in days])
    ema = pd.Series(closes).ewm(span=9, adjust=False).mean().values
    k = 0
    for i, d in enumerate(days):
        n5 = len(d["I"]["close"][4::5])
        d["ema5"] = ema[k:k + n5]
        k += n5
        I = d["I"]
        d["vwap"] = np.cumsum((I["high"] + I["low"] + I["close"]) / 3) / np.arange(1, N + 1)
        if i:
            p = days[i - 1]["I"]
            H, L, C = p["high"].max(), p["low"].min(), p["close"][-1]
            P = (H + L + C) / 3
            d["cpr"] = abs(2 * P - (H + L)) / P * 100
        else:
            d["cpr"] = np.nan
    wide = np.nanquantile([d["cpr"] for d in days], 2 / 3)
    for d in days:
        d["wide"] = d["cpr"] > wide
    # 15-minute candles and the trailing big-body threshold
    for i, d in enumerate(days):
        I = d["I"]
        c = []
        for s in range(0, 330, 15):                       # 09:15 .. 14:30 starts
            e = s + 15
            c.append(dict(s=s, e=e, o=I["open"][s], h=I["high"][s:e].max(), l=I["low"][s:e].min(), c=I["close"][e - 1]))
        d["c15"] = c
    for i, d in enumerate(days):
        prev = [abs(x["c"] - x["o"]) for dd in days[max(0, i - 20):i] for x in dd["c15"]]
        d["big"] = np.quantile(prev, 0.8) if len(prev) >= 100 else np.inf
    return days


def spread_value(chain, right, k1, k2, m, side):
    """Cost to close the short k1 / long k2 spread at minute m (buy k1 at +slip, sell k2 at -slip)."""
    a, b = chain[(k1, right)], chain[(k2, right)]
    return (a["close"][m] + SLIP) - (b["close"][m] - SLIP)


def level_broken(d, m, lvl, sign):
    """A 5-minute close (minute index 4, 9, ...) through the level."""
    return (m % 5 == 4) and ((sign > 0 and d["I"]["close"][m] < lvl) or (sign < 0 and d["I"]["close"][m] > lvl))


def sell(days, i, cand, sign, W, exit_rule, dist=None):
    d = days[i]
    ch = d["chain"]
    right = "PE" if sign > 0 else "CE"
    lvl = cand["l"] if sign > 0 else cand["h"]
    if dist is not None:
        lvl = cand["c"] - sign * dist
    ks = sorted({k for k, r in ch if r == right})
    if sign > 0:
        k1s = [k for k in ks if k <= lvl]
        if not k1s:
            return None
        k1 = max(k1s)
        k2 = k1 - W
    else:
        k1s = [k for k in ks if k >= lvl]
        if not k1s:
            return None
        k1 = min(k1s)
        k2 = k1 + W
    if (k2, right) not in ch:
        return None
    m0 = cand["e"]
    if m0 >= CUT:
        return None
    credit = (ch[(k1, right)]["open"][m0] - SLIP) - (ch[(k2, right)]["open"][m0] + SLIP)
    if credit <= 1:
        return None
    for m in range(m0, CUT + 1):
        if exit_rule != "15:10" and level_broken(d, m, lvl, sign):
            return (credit - spread_value(ch, right, k1, k2, m, sign)) * LOT - CHG2, credit
    if exit_rule != "next day":
        return (credit - spread_value(ch, right, k1, k2, CUT, sign)) * LOT - CHG2, credit
    if i + 1 >= len(days):
        return None
    d2 = days[i + 1]
    if d2["exp"] != d["exp"] or (k1, right) not in d2["chain"] or (k2, right) not in d2["chain"]:
        return None
    for m in range(0, CUT + 1):
        if level_broken(d2, m, lvl, sign):
            return (credit - spread_value(d2["chain"], right, k1, k2, m, sign)) * LOT - CHG2, credit
    return (credit - spread_value(d2["chain"], right, k1, k2, CUT, sign)) * LOT - CHG2, credit


def buy(days, i, cand, sign):
    d = days[i]
    ch = d["chain"]
    right = "CE" if sign > 0 else "PE"
    m0 = cand["e"]
    ks = sorted({k for k, r in ch if r == right})
    if not ks or m0 >= CUT:
        return None
    k = min(ks, key=lambda x: abs(x - d["I"]["close"][m0 - 1]))
    leg = ch[(k, right)]
    e = leg["open"][m0] + SLIP
    lvl = cand["l"] if sign > 0 else cand["h"]
    for m in range(m0, CUT + 1):
        if level_broken(d, m, lvl, sign):
            return (leg["close"][m] - SLIP - e) * LOT - CHG1, e
    return (leg["close"][CUT] - SLIP - e) * LOT - CHG1, e


def run(days, kind, W=100, exit_rule="level", direction=False, skip_wide=False, control=False, dists=None):
    out = []
    for i, d in enumerate(days):
        if skip_wide and d["wide"]:
            continue
        used = 0
        for j, c in enumerate(d["c15"]):
            body = c["c"] - c["o"]
            is_big = abs(body) >= d["big"]
            if control:
                if is_big or not dists or j % 4 != 1:          # ordinary candles, a few a day
                    continue
                sign = 1 if body > 0 else -1
            else:
                if not is_big or body == 0:
                    continue
                sign = 1 if body > 0 else -1
            if direction:
                m = c["e"] - 1
                e5 = d["ema5"][min(m // 5, len(d["ema5"]) - 1)]
                cl = d["I"]["close"][m]
                if (sign > 0 and not (cl > e5 and cl > d["vwap"][m])) or (sign < 0 and not (cl < e5 and cl < d["vwap"][m])):
                    continue
            if used >= 2:
                break
            if kind == "sell":
                dist = None
                if control:
                    dist = dists[len(out) % len(dists)]
                r = sell(days, i, c, sign, W, exit_rule, dist)
            else:
                r = buy(days, i, c, sign)
            if r is None:
                continue
            dist_used = (c["c"] - c["l"]) if sign > 0 else (c["h"] - c["c"])
            out.append(dict(day=d["day"], net=r[0], credit=r[1], dist=dist_used))
            used += 1
    return pd.DataFrame(out, columns=["day", "net", "credit", "dist"])


def line(tr, alld, label, show_credit=True):
    if tr.empty:
        return f"| {label} | 0 | | | | | | | |"
    half = set(alld[: len(alld) // 2])
    t = tr.net.mean() / (tr.net.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else float("nan")
    m = tr.assign(m=pd.to_datetime(tr.day).dt.strftime("%Y-%m")).groupby("m").net.sum()
    return (f"| {label} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} | {tr.net.mean():,.0f} | {t:.2f} | "
            f"{tr[tr.day.isin(half)].net.sum():,.0f} / {tr[~tr.day.isin(half)].net.sum():,.0f} | {(m > 0).sum()}/{len(m)} | "
            f"{tr.net.min():,.0f} |")


def main():
    days = load(sys.argv[1])
    alld = [d["day"] for d in days]
    out = [f"## Selling beyond a big 15-minute candle, BANKNIFTY {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
           "| version | trades | win | net Rs | per trade | t | 1st / 2nd half | green months | worst trade |",
           "|---|---|---|---|---|---|---|---|---|"]
    rows = []
    for W in (100, 200):
        for ex in ("15:10", "level", "next day"):
            for dirf, sw in ((False, False), (True, False), (True, True)):
                label = f"SELL spread {W} wide, exit {ex}" + (", EMA/VWAP filter" if dirf else "") + (", skip wide CPR" if sw else "")
                tr = run(days, "sell", W, ex, dirf, sw)
                rows.append((label, tr))
                out.append(line(tr, alld, label))
                print(out[-1], flush=True)
    # comparisons
    base = run(days, "sell", 100, "level")
    dists = list(base.dist.values) if len(base) else [100.0]
    for label, tr in (
            ("BUY the ATM option instead (level exit)", run(days, "buy")),
            ("BUY, EMA/VWAP filter, skip wide CPR", run(days, "buy", direction=True, skip_wide=True)),
            ("CONTROL: same 100-wide spread, level exit, after ORDINARY candles at the same distance",
             run(days, "sell", 100, "level", control=True, dists=dists)),
            ("CONTROL: same, held to 15:10", run(days, "sell", 100, "15:10", control=True, dists=dists)),
            ("CONTROL: same, held to next day", run(days, "sell", 100, "next day", control=True, dists=dists))):
        out.append(line(tr, alld, label))
        print(out[-1], flush=True)
    sb = run(days, "sell", 100, "level")
    out += ["", f"Average credit collected on the 100-wide spread: {sb.credit.mean():.1f} pts (Rs {sb.credit.mean() * LOT:,.0f}); "
            f"most it can lose: {100 - sb.credit.mean():.1f} pts (Rs {(100 - sb.credit.mean()) * LOT:,.0f}) plus costs. "
            f"Distance from the candle's close to its low/high: median {sb.dist.median():.0f} pts."]
    text = "\n".join(out)
    print("\n" + text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
