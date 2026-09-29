"""The owner's 29 Sep 2026 trade as rules: buy the call once a big morning fall stalls (mirror: the put after a rally).

    python research/reversal_setup.py next <banknifty_year_wide.parquet> [out.md]    next-expiry options
    python research/reversal_setup.py expiry <expiry_days.parquet> [out.md]          expiry-day options

Setup, checked on each 5-minute close from 09:45 to 13:00 (first one of the day only):
  the index is at least DROP below the day's open and at least 0.3% below the day's VWAP (typical-price average);
  the day's low was made at least 15 minutes ago (the selling has stalled); the last 5-minute candle is green
  -> buy the CE nearest the money whose one lot fits the capital. Mirror for a rally -> PE.
Exits: target / stop on the option's minute highs/lows (+10% / -3.3% is the owner's 3:1), 15:10 at the latest.
Control: the same moments, the OPPOSITE option (does the reversal read matter?).
Entry at the next minute's open +0.5, exits -0.5, Rs 40 a trip.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from ml_long import grid, N  # noqa: E402

SLIP, CHG = 0.5, 40.0
CUT = 15 * 60 + 10 - (9 * 60 + 15)
LOTS = {"BANKNIFTY": 30, "NIFTY": 75}


def load(path, mode):
    df = pd.read_parquet(path)
    df["ts"] = pd.to_datetime(df.ts)
    if "underlying" not in df:
        df["underlying"] = "BANKNIFTY"
    df["expiry"] = df.expiry.dt.date
    days = []
    for (u, day), g in df.groupby(["underlying", df.day.dt.date]):
        ix = g[g.right == "IX"].sort_values("ts").set_index("ts")
        if len(ix) < 300:
            continue
        o = g[g.right != "IX"]
        if mode == "expiry":
            ch = o[o.expiry == day]
        else:
            exps = sorted(e for e in o.expiry.unique() if e > day)
            if not exps:
                continue
            ch = o[o.expiry == exps[0]]
        chain = {}
        for (k, r), s in ch.groupby(["strike", "right"]):
            if len(s) > 60:
                chain[(k, r)] = grid(s.sort_values("ts").set_index("ts"), ["open", "high", "low", "close"])
        if chain:
            days.append(dict(u=u, day=day, I=grid(ix, ["open", "high", "low", "close"]), chain=chain))
    return days


def setup(d, drop):
    I = d["I"]
    o0 = I["open"][0]
    vw = np.cumsum((I["high"] + I["low"] + I["close"]) / 3) / np.arange(1, N + 1)
    for t in range(30, 226, 5):                                   # 09:45 .. 13:00
        c = I["close"][t - 1]
        lo_at, hi_at = int(np.argmin(I["low"][:t])), int(np.argmax(I["high"][:t]))
        green = I["close"][t - 1] > I["open"][t - 5]
        if c <= o0 * (1 - drop) and c <= vw[t - 1] * 0.997 and lo_at <= t - 15 and green:
            return t, 1
        if c >= o0 * (1 + drop) and c >= vw[t - 1] * 1.003 and hi_at <= t - 15 and not green:
            return t, -1
    return None


def trade(d, t, sign, capital, tgt, stop):
    right = "CE" if sign > 0 else "PE"
    lot = LOTS[d["u"]]
    spot = d["I"]["close"][t - 1]
    best = None
    for (k, r), leg in d["chain"].items():
        p = leg["open"][t]
        if r == right and p > 3 and p * lot <= capital and (best is None or abs(k - spot) < abs(best[0] - spot)):
            best = (k, leg)
    if best is None:
        return None
    leg = best[1]
    e = leg["open"][t] + SLIP
    for m in range(t, CUT + 1):
        if stop and leg["low"][m] <= e * (1 - stop):
            return (e * (1 - stop) - SLIP - e) * lot - CHG, e * lot, "stop"
        if tgt and leg["high"][m] >= e * (1 + tgt):
            return (e * (1 + tgt) - SLIP - e) * lot - CHG, e * lot, "target"
    return (leg["close"][CUT] - SLIP - e) * lot - CHG, e * lot, "15:10"


def run(days, drop, capital, tgt, stop, flip=False):
    out = []
    for d in days:
        s = setup(d, drop)
        if s is None:
            continue
        t, sign = s
        r = trade(d, t, -sign if flip else sign, capital, tgt, stop)
        if r:
            out.append(dict(u=d["u"], day=d["day"], net=r[0], cost=r[1], why=r[2], sign=sign, t=t))
    return pd.DataFrame(out, columns=["u", "day", "net", "cost", "why", "sign", "t"])


def line(tr, label):
    if tr.empty:
        return f"| {label} | 0 | | | | | | |"
    w, l = tr.net[tr.net > 0], tr.net[tr.net <= 0]
    days = sorted(tr.day.unique())
    half = set(days[: len(days) // 2])
    return (f"| {label} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {w.mean() if len(w) else 0:,.0f} | "
            f"{l.mean() if len(l) else 0:,.0f} | {tr.net.sum():,.0f} | {100 * tr.net.sum() / tr.cost.mean():+.0f}% | "
            f"{tr[tr.day.isin(half)].net.sum():,.0f} / {tr[~tr.day.isin(half)].net.sum():,.0f} |")


def main():
    mode, path = sys.argv[1], sys.argv[2]
    days = load(path, mode)
    head = "| version | trades | win | avg win Rs | avg loss Rs | net Rs | net vs avg money in | 1st / 2nd half |"
    sep = "|---|---|---|---|---|---|---|---|"
    out = [f"## Buy the reversal after a big move stalls ({'EXPIRY-DAY' if mode == 'expiry' else 'next-expiry'} options)", ""]
    for u in sorted({d["u"] for d in days}):
        du = [d for d in days if d["u"] == u]
        out += [f"### {u}: {len(du)} days {du[0]['day']} .. {du[-1]['day']}", ""]
        for capital in ((5000, 20000) if u == "BANKNIFTY" else (5000, 15000)):
            out += [f"**Capital Rs {capital:,}**", "", head, sep]
            for drop in (0.005, 0.003):
                for tgt, stop, name in ((0.10, 0.10 / 3, "+10% / -3.3% (3:1)"), (0.10, None, "+10%, no stop"),
                                        (0.10, 0.33, "+10% / -33%"), (0.20, 0.20 / 3, "+20% / -6.7% (3:1)"),
                                        (None, None, "hold to 15:10")):
                    tr = run(du, drop, capital, tgt, stop)
                    out.append(line(tr, f"move {drop * 100:.1f}%, {name}"))
                    if name.startswith("+10% / -3.3%"):
                        out.append(line(run(du, drop, capital, tgt, stop, flip=True), f"  CONTROL: opposite option, {name}"))
                    print(out[-1], flush=True)
            out.append("")
    text = "\n".join(out)
    print("\n" + text)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
