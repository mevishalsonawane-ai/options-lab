"""1-minute BANKNIFTY option scalping with the "institutional" layers, on the year.

    python research/scalp_1min.py <year.parquet> [bank_stocks_year.parquet] [out.md]

Layers (each can be switched on):
  HMA     21-period Hull moving average on 1-minute closes "turns green" (slope from <= 0 to > 0; red = mirror);
          "sharp" = the new slope is at least 3 index points a minute
  VWAP    the 1-minute close above (CE) / below (PE) the day's VWAP (typical-price running average; no index volume)
  candle  the signal minute's candle closes in the trade's colour
  CPR     from the previous day's high/low/close: pivot P=(H+L+C)/3, BC=(H+L)/2, TC=2P-BC, width=|TC-BC|/P.
          "narrow" = the year's lowest third (trade only then); "not wide" = skip the widest third
  A/D     advances of the 12 bank stocks vs their previous close >= 8 for CE (<= 4 for PE); "+HDFC/ICICI" also needs
          both within 0.1% of their day's high (CE) / low (PE) at that minute
Trade: the ATM option at the signal minute's strike (nearest expiry after the day), bought at the next minute's open
+0.5; stop = the swing low (CE) / swing high (PE) of the last 10 minutes on the INDEX; target = 2x that risk on the
index (1:2); exit on the option's close in the minute the index touches either (-0.5), 15:10 at the latest; risk
must be 10-80 index points; at most 5 trades a day, one at a time, 09:30-14:30; Rs 40 a trip; 1 lot of 30.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from ml_long import grid, N  # noqa: E402
from range_fade_long import CHG, LOT, SLIP  # noqa: E402

M0 = 9 * 60 + 15


def wma(x, n):
    w = np.arange(1, n + 1)
    return x.rolling(n).apply(lambda a: (a * w).sum() / w.sum(), raw=True)


def hma(x, n=21):
    return wma(2 * wma(x, n // 2) - wma(x, n), int(np.sqrt(n)))


def load(path, stocks_path=None):
    df = pd.read_parquet(path)
    df["ts"] = pd.to_datetime(df.ts)
    df["expiry"] = df.expiry.dt.date
    ixall = df[df.right == "IX"].sort_values("ts").set_index("ts")
    h = hma(ixall.close)
    days = []
    for day, g in df.groupby(df.day.dt.date):
        ix = g[g.right == "IX"].sort_values("ts").set_index("ts")
        if len(ix) < 300:
            continue
        I = grid(ix, ["open", "high", "low", "close"])
        I["hma"] = pd.Series(grid(pd.DataFrame({"close": h.reindex(ix.index)}), ["close"])["close"]).values
        o = g[g.right != "IX"]
        exps = sorted(e for e in o.expiry.unique() if e > day)
        if not exps:
            continue
        ch = o[o.expiry == exps[0]]
        chain = {}
        for (k, r), s in ch.groupby(["strike", "right"]):
            if len(s) > 150:
                chain[(k, r)] = grid(s.sort_values("ts").set_index("ts"), ["open", "high", "low", "close"])
        days.append(dict(day=day, I=I, chain=chain, strikes=np.array(sorted({k for k, _ in chain}))))
    # CPR from the previous day
    for i, d in enumerate(days):
        if i == 0:
            d["cpr"] = np.nan
            continue
        p = days[i - 1]["I"]
        H, L, C = p["high"].max(), p["low"].min(), p["close"][-1]
        P = (H + L + C) / 3
        BC = (H + L) / 2
        d["cpr"] = abs(2 * P - 2 * BC) / P * 100
    q = np.nanquantile([d["cpr"] for d in days], [1 / 3, 2 / 3])
    for d in days:
        d["cpr_t"] = "narrow" if d["cpr"] <= q[0] else ("wide" if d["cpr"] > q[1] else "mid")
    ad_q = None
    if stocks_path and os.path.exists(stocks_path):
        st = pd.read_parquet(stocks_path)
        st["ts"] = pd.to_datetime(st.ts)
        st["date"] = st.ts.dt.date
        prev = st.groupby(["symbol", "date"]).close.last().groupby(level=0).shift(1)
        for d in days:
            s = st[st.date == d["day"]]
            if s.empty:
                d["adv"] = None
                continue
            adv = np.zeros(N)
            hd = {}
            for sym, g in s.groupby("symbol"):
                gg = grid(g.set_index("ts"), ["open", "high", "low", "close"])
                pc = prev.get((sym, d["day"]), np.nan)
                adv += (gg["close"] > pc).astype(float)
                if sym in ("HDFCBANK", "ICICIBANK"):
                    hd[sym] = (gg["close"] >= np.maximum.accumulate(gg["high"]) * 0.999,
                               gg["close"] <= np.minimum.accumulate(gg["low"]) * 1.001)
            d["adv"] = adv
            d["hd"] = hd
        ad_q = True
    return days, q, ad_q


def signals(d, t, cfg):
    I = d["I"]
    if not (15 <= t <= 315):                                   # 09:30 .. 14:30
        return 0
    h = I["hma"]
    s1, s0 = h[t] - h[t - 1], h[t - 1] - h[t - 2]
    tp = (I["high"][: t + 1] + I["low"][: t + 1] + I["close"][: t + 1]) / 3
    vwap = tp.mean()
    c, o = I["close"][t], I["open"][t]
    side = 0
    if s0 <= 0 < s1 and (not cfg["sharp"] or s1 >= 3):
        side = 1
    elif s0 >= 0 > s1 and (not cfg["sharp"] or s1 <= -3):
        side = -1
    if side == 0:
        return 0
    if cfg["vwap"] and ((side > 0 and c <= vwap) or (side < 0 and c >= vwap)):
        return 0
    if cfg["candle"] and ((side > 0 and c <= o) or (side < 0 and c >= o)):
        return 0
    if cfg["cpr"] == "narrow" and d["cpr_t"] != "narrow":
        return 0
    if cfg["cpr"] == "not wide" and d["cpr_t"] == "wide":
        return 0
    if cfg["ad"]:
        adv = d.get("adv")
        if adv is None:
            return 0
        if side > 0 and adv[t] < 8 or side < 0 and adv[t] > 4:
            return 0
        if cfg["ad"] == "heavy":
            hd = d.get("hd", {})
            if len(hd) < 2:
                return 0
            k = 0 if side > 0 else 1
            if not (hd["HDFCBANK"][k][t] and hd["ICICIBANK"][k][t]):
                return 0
    return side


def run(days, cfg):
    out = []
    for d in days:
        I, n, busy = d["I"], 0, -1
        for t in range(2, 316):
            if n >= 5 or t <= busy:
                continue
            side = signals(d, t, cfg)
            if side == 0:
                continue
            swing = I["low"][t - 9: t + 1].min() if side > 0 else I["high"][t - 9: t + 1].max()
            entry_ix = I["close"][t]
            risk = abs(entry_ix - swing)
            if not (10 <= risk <= 80):
                continue
            k = d["strikes"][np.argmin(np.abs(d["strikes"] - entry_ix))]
            leg = d["chain"].get((k, "CE" if side > 0 else "PE"))
            if leg is None or t + 1 >= N:
                continue
            e = leg["open"][t + 1] + SLIP
            stop, tgt = swing, entry_ix + side * cfg["rr"] * risk
            x = None
            for u in range(t + 1, min(N, 356)):                 # 15:10
                lo, hi = I["low"][u], I["high"][u]
                if (side > 0 and lo <= stop) or (side < 0 and hi >= stop):
                    x = u
                    break
                if (side > 0 and hi >= tgt) or (side < 0 and lo <= tgt):
                    x = u
                    break
            x = x if x is not None else min(N - 1, 355)
            out.append(dict(day=d["day"], net=(leg["close"][x] - SLIP - e) * LOT - CHG, mins=x - t))
            n += 1
            busy = x
    return pd.DataFrame(out, columns=["day", "net", "mins"])


def line(tr, alld, label):
    if tr.empty:
        return f"| {label} | 0 | | | | | | |"
    half = set(alld[: len(alld) // 2])
    t = tr.net.mean() / (tr.net.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else float("nan")
    m = tr.assign(m=pd.to_datetime(tr.day).dt.strftime("%Y-%m")).groupby("m").net.sum()
    return (f"| {label} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} | {tr.net.mean():,.0f} | {t:.2f} | "
            f"{tr[tr.day.isin(half)].net.sum():,.0f} / {tr[~tr.day.isin(half)].net.sum():,.0f} | {(m > 0).sum()}/{len(m)} |")


def main():
    stocks = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2].endswith(".parquet") else None
    days, q, has_ad = load(sys.argv[1], stocks)
    alld = [d["day"] for d in days]
    base = dict(sharp=False, vwap=False, candle=False, cpr="all", ad=None, rr=2)
    steps = [
        ("HMA 21 turns (alone)", {}),
        ("+ sharp turn (>= 3 pts/min)", dict(sharp=True)),
        ("+ VWAP side", dict(sharp=True, vwap=True)),
        ("+ candle colour", dict(sharp=True, vwap=True, candle=True)),
        ("+ skip wide-CPR days", dict(sharp=True, vwap=True, candle=True, cpr="not wide")),
        ("+ narrow-CPR days only", dict(sharp=True, vwap=True, candle=True, cpr="narrow")),
        ("HMA+VWAP+candle, all days, 1:1 target", dict(sharp=True, vwap=True, candle=True, rr=1)),
    ]
    if has_ad:
        steps += [
            ("HMA+VWAP+candle + A/D 8:4", dict(sharp=True, vwap=True, candle=True, ad="ad")),
            ("  + HDFC & ICICI at day high/low", dict(sharp=True, vwap=True, candle=True, ad="heavy")),
            ("  + HDFC & ICICI, skip wide CPR", dict(sharp=True, vwap=True, candle=True, ad="heavy", cpr="not wide")),
            ("  + HDFC & ICICI, narrow CPR only", dict(sharp=True, vwap=True, candle=True, ad="heavy", cpr="narrow")),
            ("A/D 8:4 + HDFC & ICICI + VWAP (no HMA sharpness)", dict(vwap=True, candle=True, ad="heavy")),
        ]
    out = [f"## 1-minute scalping with HMA / VWAP / CPR / A-D, BANKNIFTY {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
           f"CPR width thirds: narrow <= {q[0]:.3f}% of price, wide > {q[1]:.3f}%. A/D data: {'yes' if has_ad else 'not yet'}.", "",
           "| layers | trades | win | net Rs | per trade | t | 1st / 2nd half | green months |", "|---|---|---|---|---|---|---|---|"]
    for label, ch in steps:
        cfg = dict(base, **ch)
        tr = run(days, cfg)
        out.append(line(tr, alld, label))
        print(out[-1], flush=True)
    # CPR: do narrow days really trend? (day range and |close-open| by CPR third)
    rows = pd.DataFrame([dict(t=d["cpr_t"], rng=d["I"]["high"].max() - d["I"]["low"].min(),
                              move=abs(d["I"]["close"][-1] - d["I"]["open"][0])) for d in days[1:]])
    out += ["", "**Does a narrow CPR bring a trending day?**", "", "| CPR | days | average day range (pts) | average |close - open| (pts) |",
            "|---|---|---|---|"]
    for t3 in ("narrow", "mid", "wide"):
        x = rows[rows.t == t3]
        out.append(f"| {t3} | {len(x)} | {x.rng.mean():.0f} | {x.move.mean():.0f} |")
    text = "\n".join(out)
    print("\n" + text)
    if sys.argv[-1].endswith(".md"):
        open(sys.argv[-1], "w").write(text)


if __name__ == "__main__":
    main()


def breadth_direction(days):
    """Index move after the minute, by the advance count of the 12 stocks (every minute 09:30-14:30)."""
    rows = []
    for d in days:
        adv = d.get("adv")
        if adv is None:
            continue
        c = d["I"]["close"]
        hd = d.get("hd", {})
        for t in range(15, 316):
            both_hi = len(hd) == 2 and hd["HDFCBANK"][0][t] and hd["ICICIBANK"][0][t]
            both_lo = len(hd) == 2 and hd["HDFCBANK"][1][t] and hd["ICICIBANK"][1][t]
            rows.append(dict(adv=adv[t], hi=both_hi, lo=both_lo, f5=c[t + 5] - c[t], f15=c[t + 15] - c[t],
                             f30=c[min(t + 30, N - 1)] - c[t]))
    return pd.DataFrame(rows)
