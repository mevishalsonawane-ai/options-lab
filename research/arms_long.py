"""Every ORB-family arm (and two candidates) on the long BANKNIFTY history, same data and fills as range_fade_long.py.

Arms, as the app runs them (5-minute bars, opening range 09:15-10:00, ATM strike from the 09:20 bar, nearest expiry
after the day, entry at the next bar on the option's first minute + 0.5, 15:10 exit, Rs 40 a trip, 1 lot of 30):
  ORB          close outside the range (bars 10:05-14:25) -> buy that way; -40/+40; no daily cap
  ORB Fresh    the same, only when the previous bar closed inside
  ORB Sweep    wick through the range, close back inside -> fade; -40/+80; max 2 a day
  Range Fade   bar in the outer 10% of the range, close back inside -> fade (10:30-13:55); -40/+40; max 2
Candidates (not in the app): RSI(14) 30/70 reversal (-40/+80, max 2) and Supertrend(10,3)+EMA20/EMA50 on 15-minute
bars (-40/+40, max 2), whose indicators run over the whole history.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from range_fade_long import CHG, LOT, SLIP, archive_days, five_min, local_days  # noqa: E402


def legs_for(day, b, opts):
    ref = b.between_time("09:20", "09:20").close
    spot = ref.iloc[0] if len(ref) else b.close.iloc[0]
    exps = sorted(e for e in opts.expiry.unique() if e > day)
    if not exps:
        return None
    ch = opts[opts.expiry == exps[0]]
    legs = {}
    for right in ("CE", "PE"):
        s = ch[ch.right == right]
        if s.empty:
            return None
        ks = s.strike.unique()
        k = ks[np.argmin(np.abs(ks - spot))]
        legs[right] = s[s.strike == k].sort_values("ts").set_index("ts")[["open", "high", "low", "close"]]
    return legs


def load(src):
    """[(day, 5-minute index bars, legs)] with only the two ATM legs kept, so a year fits in memory."""
    gen = local_days() if src == "local" else archive_days(int(src))
    out = []
    for day, ix, opts in gen:
        if ix is None or len(ix) == 0 or opts.empty:
            continue
        b = five_min(ix)
        if len(b.between_time("09:15", "10:00")) < 8 or b.index.max().strftime("%H:%M") < "14:00":
            continue
        legs = legs_for(day, b, opts)
        if legs:
            out.append((day, b, legs))
    return out


def fill(leg, t_entry, stop, tgt):
    a = leg[leg.index >= t_entry]
    if a.empty or a.index[0].strftime("%H:%M") >= "15:10":
        return None
    e = a.open.iloc[0] + SLIP
    for ts, r in a.iterrows():
        if r.low <= e - stop:
            return -stop - SLIP, ts
        if r.high >= e + tgt:
            return tgt - SLIP, ts
        if ts.strftime("%H:%M") >= "15:10":
            return r.close - SLIP - e, ts
    return a.close.iloc[-1] - SLIP - e, a.index[-1]


# ---- signals: (bars, j, ctx) -> "CE" / "PE" / None ------------------------------------------------------------

def hm(t):
    return t.strftime("%H:%M")


def orb(b, j, c):
    r = b.iloc[j]
    if "10:00" < hm(b.index[j]) < "14:30":
        return "CE" if r.close > c["orh"] else "PE" if r.close < c["orl"] else None


def orb_fresh(b, j, c):
    s = orb(b, j, c)
    q = b.iloc[j - 1]
    if s and ((s == "CE" and q.close > c["orh"]) or (s == "PE" and q.close < c["orl"])):
        return None
    return s


def sweep(b, j, c):
    r = b.iloc[j]
    if "10:00" < hm(b.index[j]) < "14:30":
        if r.high > c["orh"] and r.close < c["orh"]:
            return "PE"
        if r.low < c["orl"] and r.close > c["orl"]:
            return "CE"


def fade(b, j, c):
    r = b.iloc[j]
    w = c["orh"] - c["orl"]
    if "10:30" <= hm(b.index[j]) < "14:00" and w > 0:
        if r.high >= c["orh"] - 0.1 * w and r.close < c["orh"]:
            return "PE"
        if r.low <= c["orl"] + 0.1 * w and r.close > c["orl"]:
            return "CE"


def rsi_rev(b, j, c):
    if not ("10:00" <= hm(b.index[j]) < "14:30"):
        return None
    ind = c["ind"]
    t, tq = b.index[j], b.index[j - 1]
    if t not in ind.index or tq not in ind.index:
        return None
    r, q = ind.rsi[t], ind.rsi[tq]
    if q < 30 <= r:
        return "CE"
    if q > 70 >= r:
        return "PE"


def st_ema(b, j, c):
    """Decides only when a 15-minute bar has closed (the 5-minute bar ending on the quarter hour)."""
    t = b.index[j]
    if not ("09:30" <= hm(t) <= "14:30") or (t.minute % 15) != 10:
        return None
    k = t - pd.Timedelta(minutes=10)
    s = c["st15"]
    if k not in s.index:
        return None
    i = s.index.get_loc(k)
    if i < 1:
        return None
    r, q = s.iloc[i], s.iloc[i - 1]
    up = (q.close <= q.e20 and r.close > r.e20) or (q.dir < 0 < r.dir)
    dn = (q.close >= q.e20 and r.close < r.e20) or (q.dir > 0 > r.dir)
    if r.close > r.e50 and r.close > r.e20 and r.dir > 0 and up:
        return "CE"
    if r.close < r.e50 and r.close < r.e20 and r.dir < 0 and dn:
        return "PE"


ARMS = {  # name: (signal, stop, target, max a day)
    "ORB": (orb, 40, 40, 99),
    "ORB Fresh": (orb_fresh, 40, 40, 99),
    "ORB Sweep": (sweep, 40, 80, 2),
    "Range Fade": (fade, 40, 40, 2),
    "RSI reversal (candidate)": (rsi_rev, 40, 80, 2),
    "Supertrend+EMA 15m (candidate)": (st_ema, 40, 40, 2),
}


def indicators(days):
    allb = pd.concat([b for _, b, _ in days])
    c = allb.close
    d = c.diff()
    up = d.clip(lower=0).ewm(alpha=1 / 14, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / 14, adjust=False).mean()
    ind = pd.DataFrame({"rsi": 100 - 100 / (1 + up / dn)})
    s = allb.resample("15min", label="left", closed="left").agg(
        {"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
    h, l, cl = s.high.values, s.low.values, s.close.values
    pc = np.r_[cl[0], cl[:-1]]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    atr = pd.Series(tr).ewm(alpha=1 / 10, adjust=False).mean().values
    hl2 = (h + l) / 2
    ub, lb = hl2 + 3 * atr, hl2 - 3 * atr
    fu, fl, dr = ub.copy(), lb.copy(), np.ones(len(cl))
    for i in range(1, len(cl)):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or cl[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or cl[i - 1] < fl[i - 1]) else fl[i - 1]
        dr[i] = 1 if cl[i] > fu[i - 1] else (-1 if cl[i] < fl[i - 1] else dr[i - 1])
    s["dir"] = dr
    s["e20"] = s.close.ewm(span=20, adjust=False).mean()
    s["e50"] = s.close.ewm(span=50, adjust=False).mean()
    return ind, s


def run(days, sig, stop, tgt, maxn, ind, st15):
    out = []
    for day, b, legs in days:
        orb_ = b.between_time("09:15", "10:00")
        ctx = dict(orh=orb_.high.max(), orl=orb_.low.min(), ind=ind, st15=st15)
        n, busy = 0, None
        for j in range(1, len(b) - 1):
            if n >= maxn:
                break
            if busy is not None and b.index[j] <= busy.floor("5min"):
                continue
            s = sig(b, j, ctx)
            if not s:
                continue
            f = fill(legs[s], b.index[j + 1], stop, tgt)
            if f is None:
                continue
            out.append(dict(day=day, net=f[0] * LOT - CHG))
            n += 1
            busy = f[1]
    return pd.DataFrame(out, columns=["day", "net"])


def line(name, tr, alld):
    if tr.empty:
        return f"| {name} | 0 | - | - | - | - | - | - | - |"
    daily = tr.groupby("day").net.sum().reindex(alld, fill_value=0.0)
    eq = daily.cumsum()
    t = tr.net.mean() / (tr.net.std(ddof=1) / np.sqrt(len(tr))) if len(tr) > 2 else float("nan")
    last6 = set(alld[-126:])
    q = [tr[tr.day.isin(set(p))].net.sum() for p in np.array_split(np.array(alld, dtype=object), 4)]
    pos_months = (tr.assign(m=pd.to_datetime(tr.day).dt.strftime("%Y-%m")).groupby("m").net.sum() > 0)
    return (f"| {name} | {len(tr)} | {100 * (tr.net > 0).mean():.0f}% | {tr.net.sum():,.0f} | {t:.2f} | "
            f"{(eq - eq.cummax()).min():,.0f} | {tr[tr.day.isin(last6)].net.sum():,.0f} | "
            f"{' / '.join(f'{x:,.0f}' for x in q)} | {pos_months.sum()}/{len(pos_months)} |")


def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "local"
    days = load(src)
    ind, st15 = indicators(days)
    alld = [d for d, _, _ in days]
    out = [f"## Arms on {len(alld)} BANKNIFTY sessions {alld[0]} .. {alld[-1]} (1 lot, after costs)", "",
           "| arm | trades | win | net Rs | t | max drawdown | last 6 months | quarters 1/2/3/4 | green months |",
           "|---|---|---|---|---|---|---|---|---|"]
    for name, (sig, stop, tgt, maxn) in ARMS.items():
        out.append(line(name, run(days, sig, stop, tgt, maxn, ind, st15), alld))
        print(out[-1], flush=True)
    text = "\n".join(out)
    print("\n" + text)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as fh:
            fh.write(text + "\n")


if __name__ == "__main__":
    main()
