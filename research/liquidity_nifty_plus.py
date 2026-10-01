"""Liquidity 15+5 on NIFTY, with a small reasoned set of enhancements, validated across two years (research only).

    python research/liquidity_nifty_plus.py <yearA.parquet> <yearB.parquet> <out.md> [cache dir]

A copy of research/liquidity_break.py's simulate() extended with options (the original is untouched):
  chart timeframes, swing lookback, pool confirmation, premium stop %, entry window, a trend filter (the index on the
  trade's side of the day's VWAP, or the 5-minute 50 EMA higher/lower than 30 minutes earlier, the trade's way; a 9 EMA slope
  was tautological - a breakout close almost always lifts it), a volatility filter (first-hour
  range at least the trailing 20-day median; entries from 10:15), strike (ATM / 1 strike ITM), a profit lock (once the
  premium is up X%, a stop at break-even or trailing Y% below the premium's high), skipping expiry days, and a cap on
  trades per day per book.
Baseline "Liquidity 15+5": source "both", swing 20, pool 2/5/10, failed-break stop, 15% premium stop, entries
09:20-14:30, 15-min and 5-min books side by side, ATM CE on an upside break / ATM PE on a downside.
BUYING options only. NIFTY lot 75, 0.5 slippage a side, Rs 40 a round trip, real option minute prices.

Validation: every variant is run on both years. Settings are chosen on Year A only (single factors, then a greedy
combination of the factors that helped on A) and reported on Year B untouched; then the reverse (chosen on B, tested
on A). A setting is recommended only if positive after costs in BOTH years and better than the baseline in the
held-out year.
"""
from __future__ import annotations

import os
import pickle
import sys
from dataclasses import dataclass, field, replace

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LOT, SLIP, CHG = 75, 0.5, 40.0
CUT = 355          # 15:10
STEP = 50          # NIFTY strike step


@dataclass(frozen=True)
class Cfg:
    tfs: tuple = (15, 5)
    sw: int = 20                 # swing lookback
    pc: int = 10                 # pool confirmation bars
    ps: float | None = 0.15      # premium stop
    stop: bool = True            # failed-break stop
    win: tuple = (5, 315)        # entry minutes (09:20 .. 14:30)
    trend: str | None = None     # None / "vwap" / "ema"
    vol: bool = False            # first-hour range >= trailing 20-day median, entries from 10:15
    itm: int = 0                 # strikes in the money (0 = ATM)
    lock: tuple | None = None    # (trigger, trail) - trail None = break-even stop
    skip: str | None = None      # None / "expday" (index expiry day) / "dte1" (option expires within 1 day)
    maxday: int | None = None    # entries per day per book


# ------------------------------------------------------------------ data
def load_days(path, cache):
    pk = os.path.join(cache, os.path.basename(path).replace(".parquet", "_days.pkl"))
    if os.path.exists(pk):
        days = pickle.load(open(pk, "rb"))
    else:
        from sell_levels import load
        days = load(path)
        for d in days:
            d.pop("c15", None)
            d.pop("ema5", None)
        pickle.dump(days, open(pk, "wb"))
    ex = pd.read_parquet(path, columns=["expiry"]).expiry.dropna().unique()
    expiries = set(pd.to_datetime(ex).date)
    # extras: 5-minute 50 EMA (continuous), first-hour range and its trailing 20-day median, expiry flags
    closes = np.concatenate([d["I"]["close"][4::5] for d in days])
    ema = pd.Series(closes).ewm(span=50, adjust=False).mean().values
    k = 0
    fh = []
    for i, d in enumerate(days):
        d["ema"] = ema[k:k + 75]
        k += 75
        I = d["I"]
        d["fh"] = np.nanmax(I["high"][:60]) - np.nanmin(I["low"][:60])
        prev = fh[-20:]
        d["fh_med"] = np.median(prev) if len(prev) >= 5 else np.inf
        fh.append(d["fh"])
        d["expday"] = d["day"] in expiries
        d["dte"] = (d["exp"] - d["day"]).days
    return days


# ------------------------------------------------------------------ strategy
def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, 375, tf):
            e = min(s + tf, 375)
            rows.append((di, s, e, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])


def simulate(days, b, zones, c: Cfg, tf):
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    swings = [z for z in zones if z.kind == "swing"]
    trades = []
    pos = None
    nday = {}
    for i in range(len(b)):
        d = days[DI[i]]
        I = d["I"]
        if pos is not None:
            sg = pos["sign"]
            why, xm, fill = None, None, None
            op = d["chain"][pos["key"]]
            for m in range(max(S[i], pos["m"]), E[i]):
                if m >= CUT:
                    why, xm = "15:10", m
                    break
                stops = []
                if c.ps:
                    stops.append((pos["px"] * (1 - c.ps), "premium stop"))
                if c.lock and pos["hi"] >= pos["px"] * (1 + c.lock[0]):
                    lv = pos["px"] if c.lock[1] is None else max(pos["px"], pos["hi"] * (1 - c.lock[1]))
                    stops.append((lv, "profit lock"))
                if stops:
                    lv, nm = max(stops)
                    if op["low"][m] <= lv:
                        why, xm, fill = nm, m, min(op["close"][m], lv)
                        break
                pos["hi"] = max(pos["hi"], op["high"][m])
                if pos["target"] is not None and ((sg > 0 and I["high"][m] >= pos["target"]) or
                                                  (sg < 0 and I["low"][m] <= pos["target"])):
                    why, xm = "next liquidity", m
                    break
            if why is None and c.stop and sg * (C[i] - pos["level"]) < 0:
                why, xm = "failed break", E[i] - 1
            if why is None and any(z.side == sg for z in known.get(i, [])):
                why, xm = "new liquidity", E[i] - 1
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, xm = "15:10", min(E[i] - 1, 374)
            if why:
                ix = I["close"][xm] if why != "next liquidity" else pos["target"]
                opt = op["close"][xm] if fill is None else fill
                trades.append(dict(day=pos["day"], m=pos["m"], tf=tf, sign=sg, why=why, pts=sg * (ix - pos["ix"]),
                                   rs=(opt - SLIP - pos["px"]) * LOT - CHG, held=xm - pos["m"]))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (c.win[0] <= m <= c.win[1]):
            continue
        brk = breaks.get(i)
        if not brk:
            continue
        cand = [p for p in brk if p.kind == "pool" and any(
            s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i and (s.broken < 0 or s.broken >= i)
            for s in swings)]
        if not cand:
            continue
        # ---- filters
        if c.skip == "expday" and d["expday"]:
            continue
        if c.skip == "dte1" and d["dte"] <= 1:
            continue
        if c.maxday and nday.get((DI[i]), 0) >= c.maxday:
            continue
        if c.vol and (m < 60 or d["fh"] < d["fh_med"]):
            continue
        z = cand[0]
        sg = z.side
        if c.trend == "vwap" and sg * (I["close"][m - 1] - d["vwap"][m - 1]) <= 0:
            continue
        if c.trend == "ema":
            k = m // 5 - 1                       # last complete 5-minute bar
            e_now = d["ema"][k]
            e_prev = d["ema"][k - 6] if k >= 6 else days[DI[i] - 1]["ema"][k - 6] if DI[i] else e_now
            if sg * (e_now - e_prev) <= 0:
                continue
        ix = I["open"][m]
        ahead = [q.edge for q in zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                 and sg * (q.edge - ix) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        right = "CE" if sg > 0 else "PE"
        ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
        if not len(ks):
            continue
        k = ks[np.argmin(np.abs(ks - ix))] - sg * c.itm * STEP     # ITM: lower strike for a CE, higher for a PE
        if (k, right) not in d["chain"] or not np.isfinite(d["chain"][(k, right)]["open"][m]):
            continue
        px = d["chain"][(k, right)]["open"][m] + SLIP
        nday[DI[i]] = nday.get(DI[i], 0) + 1
        pos = dict(day=d["day"], di=DI[i], sign=sg, m=m, ix=ix, level=z.edge, target=target, key=(k, right),
                   px=px, hi=px - SLIP)
    return pd.DataFrame(trades)


# ------------------------------------------------------------------ evaluation
class Book:
    def __init__(self, days):
        self.days = days
        self.cache = {}

    def zones(self, tf, sw, pc):
        key = (tf, sw, pc)
        if key not in self.cache:
            b = bars(self.days, tf)
            self.cache[key] = (b, swing_zones(b, sw, "full") + pool_zones(b, 2, 5, pc))
        return self.cache[key]

    def run(self, c: Cfg):
        out = []
        for tf in c.tfs:
            b, z = self.zones(tf, c.sw, c.pc)
            out.append(simulate(self.days, b, z, c, tf))
        tr = pd.concat(out, ignore_index=True)
        return tr.sort_values(["day", "m"]).reset_index(drop=True) if len(tr) else tr


def stats(tr, ndays):
    if tr.empty:
        return dict(n=0, per_day=0, win=np.nan, pts=np.nan, per=np.nan, t=np.nan, net=0, green="0/0", dd=0)
    x = tr.rs
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
    mo = tr.groupby(tr.day.map(lambda d: str(d)[:7])).rs.sum()
    daily = tr.groupby("day").rs.sum().cumsum()
    dd = (daily - np.maximum.accumulate(np.maximum(daily, 0))).min()
    return dict(n=len(tr), per_day=len(tr) / ndays, win=100 * (x > 0).mean(), pts=tr.pts.mean(), per=x.mean(), t=t,
                net=x.sum(), green=f"{(mo > 0).sum()}/{len(mo)}", dd=min(dd, 0))


def fmt(label, s):
    if not s["n"]:
        return f"| {label} | 0 | | | | | | | | |"
    return (f"| {label} | {s['n']} ({s['per_day']:.1f}/day) | {s['win']:.0f}% | {s['pts']:+.1f} | {s['per']:+,.0f} | "
            f"{s['t']:.2f} | **{s['net']:+,.0f}** | {s['green']} | {s['dd']:,.0f} |")


HDR = ["| variant | trades | win | index pts/trade | Rs/trade | t | net Rs / lot / yr | green months | max DD |",
       "|---|---|---|---|---|---|---|---|---|"]


def describe(c: Cfg):
    base = Cfg()
    parts = []
    for f in c.__dataclass_fields__:
        v, v0 = getattr(c, f), getattr(base, f)
        if v != v0:
            parts.append(f"{f}={v}")
    return "baseline" if not parts else ", ".join(parts)


# single-factor variants (each one change from the baseline)
SINGLE = {
    "tfs": [(15,), (5,), (10,), (30,), (15, 30), (10, 30), (3, 15)],
    "sw": [10, 15, 30],
    "pc": [5, 15],
    "ps": [None, 0.10, 0.20, 0.25],
    "stop": [False],
    "win": [(5, 225), (60, 315)],
    "trend": ["vwap", "ema"],
    "vol": [True],
    "itm": [1],
    "lock": [(0.20, None), (0.30, 0.15)],
    "skip": ["expday", "dte1"],
    "maxday": [1, 2],
}


def main():
    pa, pb, out = sys.argv[1:4]
    cache = sys.argv[4] if len(sys.argv) > 4 else os.path.dirname(os.path.abspath(pa))
    Y = {"A": Book(load_days(pa, cache)), "B": Book(load_days(pb, cache))}
    nd = {k: len(v.days) for k, v in Y.items()}
    span = {k: f"{v.days[0]['day']} .. {v.days[-1]['day']} ({len(v.days)} days)" for k, v in Y.items()}
    res = {}                                   # Cfg -> {"A": stats, "B": stats}
    trades = {}

    def ev(c):
        if c not in res:
            res[c] = {}
            for y, bk in Y.items():
                tr = bk.run(c)
                trades[(c, y)] = tr
                res[c][y] = stats(tr, nd[y])
            print(f"{describe(c):55s} A {res[c]['A']['net']:+9,.0f} t {res[c]['A']['t']:5.2f} n {res[c]['A']['n']:4d} | "
                  f"B {res[c]['B']['net']:+9,.0f} t {res[c]['B']['t']:5.2f} n {res[c]['B']['n']:4d}", flush=True)
        return res[c]

    base = Cfg()
    legacy = replace(base, ps=None)
    ev(base)
    singles = []
    for f, vals in SINGLE.items():
        for v in vals:
            c = replace(base, **{f: v})
            ev(c)
            singles.append((f, c))

    # greedy combination chosen on one year only
    def choose(sel):
        tried = []
        best_by_factor = {}
        for f, c in singles:
            if res[c][sel]["net"] > res[base][sel]["net"] and (f not in best_by_factor or
                                                                   res[c][sel]["net"] > res[best_by_factor[f]][sel]["net"]):
                best_by_factor[f] = c
        order = sorted(best_by_factor, key=lambda f: -res[best_by_factor[f]][sel]["net"])
        cur = base
        for f in order:
            cand = replace(cur, **{f: getattr(best_by_factor[f], f)})
            ev(cand)
            tried.append(cand)
            if res[cand][sel]["net"] > res[cur][sel]["net"]:
                cur = cand
        pool = [base] + [c for _, c in singles] + tried
        best = max(pool, key=lambda c: res[c][sel]["net"])
        return best, order, tried

    bestA, orderA, triedA = choose("A")
    bestB, orderB, triedB = choose("B")
    nvar = len(res)

    # ----------------------------------------------------------------- report
    L = ["# Liquidity 15+5 on NIFTY: enhancements, validated out-of-sample (research/liquidity_nifty_plus.py)", "",
         f"Year A: {span['A']}. Year B: {span['B']}. NIFTY lot {LOT}, real option minute prices, "
         f"0.5 slippage a side, Rs {CHG:.0f} a round trip. BUYING options only (ATM CE on an up-break, ATM PE on a "
         "down-break unless noted). Net Rs is per lot per year, 15-min and 5-min books together unless `tfs` says "
         "otherwise. Max DD on the daily equity curve.", "",
         f"**Variants evaluated: {nvar}** (1 baseline, {len(singles)} single-factor changes, "
         f"{len(triedA)} combinations built on Year A, {len(triedB)} built on Year B; "
         f"plus the no-premium-stop legacy row for reference).", ""]

    ev(legacy)

    def block(title, y, cfgs):
        L.extend([f"### {title}", ""] + HDR)
        for c in cfgs:
            L.append(fmt(describe(c), res[c][y]))
        L.append("")

    L += ["## 1. Baseline reproduced", ""]
    for y in "AB":
        block(f"Year {y}", y, [base, legacy])
    for y in "AB":
        tr = trades[(base, y)]
        L.append(f"- Year {y} baseline by book: " + "; ".join(
            f"{tf}-min {len(g)} trades Rs {g.rs.sum():+,.0f} ({g.pts.mean():+.1f} pts)" for tf, g in tr.groupby("tf")) +
            "; exits " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in tr.why.value_counts(normalize=True).items()))
    L.append("")

    L += ["## 2. Single-factor changes, both years", "",
          "Each row changes one thing from the baseline. Shown for transparency - picking the best row of the Year B "
          "column here would be in-sample; the out-of-sample test is section 3.", "",
          "| variant | A trades | A Rs/trade | A t | A net | B trades | B Rs/trade | B t | B net |", "|---|---|---|---|---|---|---|---|---|"]
    for c in [base] + [c for _, c in singles]:
        a, bb = res[c]["A"], res[c]["B"]
        L.append(f"| {describe(c)} | {a['n']} | {a['per']:+,.0f} | {a['t']:.2f} | {a['net']:+,.0f} | "
                 f"{bb['n']} | {bb['per']:+,.0f} | {bb['t']:.2f} | {bb['net']:+,.0f} |")
    L.append("")

    verdicts = []
    for sel, held, best, order, tried in (("A", "B", bestA, orderA, triedA), ("B", "A", bestB, orderB, triedB)):
        L += [f"## 3{'a' if sel == 'A' else 'b'}. Chosen on Year {sel}, tested on Year {held}", "",
              f"Factors that beat the baseline on Year {sel} (best level each), combined greedily in this order: "
              f"{', '.join(order) or 'none'}.", ""]
        L += ["Combinations tried (selection year / held-out year):", "",
              "| combination | sel net | sel t | held-out net | held-out t |", "|---|---|---|---|---|"]
        for c in tried:
            L.append(f"| {describe(c)} | {res[c][sel]['net']:+,.0f} | {res[c][sel]['t']:.2f} | "
                     f"{res[c][held]['net']:+,.0f} | {res[c][held]['t']:.2f} |")
        L.append("")
        L += [f"**Best on Year {sel}: `{describe(best)}`**", ""] + HDR
        L.append(fmt(f"Year {sel} (selection) - chosen", res[best][sel]))
        L.append(fmt(f"Year {sel} (selection) - baseline", res[base][sel]))
        L.append(fmt(f"Year {held} (held out) - chosen", res[best][held]))
        L.append(fmt(f"Year {held} (held out) - baseline", res[base][held]))
        ok = res[best]["A"]["net"] > 0 and res[best]["B"]["net"] > 0 and res[best][held]["net"] > res[base][held]["net"]
        verdicts.append((sel, best, ok))
        L += ["", f"Positive in both years: {res[best]['A']['net'] > 0 and res[best]['B']['net'] > 0}; "
                  f"beats baseline in held-out year: {res[best][held]['net'] > res[base][held]['net']} -> "
                  f"**{'RECOMMENDABLE' if ok else 'not recommended'}**", ""]
    open(out, "w").write("\n".join(L))
    pickle.dump(dict(res=res, verdicts=verdicts, bestA=bestA, bestB=bestB),
                open(os.path.join(cache, "nifty_plus_res.pkl"), "wb"))
    for c in {bestA, bestB, base}:
        for y in "AB":
            trades[(c, y)].to_csv(os.path.join(cache, f"nifty_plus_trades_{y}_{abs(hash(c)) % 10**6}.csv"), index=False)
    print("\n".join(L))


if __name__ == "__main__":
    main()
