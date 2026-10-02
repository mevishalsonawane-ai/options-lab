"""The owner's ask (2026-10-02): "If the market is completely sideways Jarvis should not trade at all - stop all the
arms and start them again when the market is stable." Would a sideways guard have helped the app's intraday arms?

    python research/sideways_guard.py <year A wide parquet> <year B wide parquet> <out.md> [cache dir] [--lot N]
                                      [--vix extra_series.parquet] [--name BANKNIFTY]

The arms exactly as research/entry_cutoff.py replicates them as they trade now (1 lot, real option minute prices,
0.5 slippage a side, Rs 40 a trip): ORB, ORB Fresh, ORB Sweep (profit-lock ladder, last entry 14:30), Range Fade
(ladder, last entry 14:00) and Liquidity 15+5 (15% premium stop, 30-pt index stop, 20-min time stop, 09:20-14:30).

Sideways detectors, all computed from the index's 1-minute candles with no look-ahead:
  day-level, known before the open (block the whole day)
    PDR   prior day's high-low range / mean of the 20 sessions before it            sideways if < 0.7
    VIX   prior day's India VIX close, percentile in its trailing 60 sessions        sideways if < 25%
  day-level, known at 10:00 (block entries decided at or after 10:00)
    ADX   ADX(14, Wilder) on continuous 15-minute candles, the 09:45 candle's value  sideways if < 20
    CHOP  Choppiness(14) on 15-minute candles, same bar                              sideways if > 61.8
    OR    09:15-10:00 range / mean of the previous 20 sessions' 09:15-10:00 ranges   sideways if < 0.7
  intraday pause (re-evaluated at every 15-minute close; while sideways no new entries, open trades run as usual)
    ADX-pause   ADX(14) 15m of the last completed candle < 20
    CHOP-pause  CHOP(14) 15m of the last completed candle > 61.8
Each threshold is fixed up front (the textbook / brief values); neighbours are shown as a sensitivity check only.
"Skipping" a losing arm's days always looks good, so every skip is compared with skipping the same number of
random days (the expected change and a permutation p-value).
"""
from __future__ import annotations

import os
import pickle
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import arms_long as al  # noqa: E402
import entry_cutoff as ec  # noqa: E402
import liquidity_break as lb  # noqa: E402
from profit_lock import fill_ladder  # noqa: E402
from sell_levels import load as liq_load  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

ARMS = ["ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Liquidity 15+5"]
LAST = {"ORB": "14:30", "ORB Fresh": "14:30", "ORB Sweep": "14:30", "Range Fade": "14:00"}
LOT, CHG = 30, 40.0


# ---- the arms, with the decision minute recorded and an optional entry gate ------------------------------------

def mins(ts):
    return ts.hour * 60 + ts.minute - 555                       # minutes after 09:15


def run_arm(days, name, gate=None):
    _, stop, tgt, maxn = al.ARMS[name]
    out = []
    for day, b, legs in days:
        orb_ = b.between_time("09:15", "10:00")
        c = dict(orh=orb_.high.max(), orl=orb_.low.min())
        n, busy = 0, None
        for j in range(1, len(b) - 1):
            if n >= maxn:
                break
            if busy is not None and b.index[j] <= busy.floor("5min"):
                continue
            s = ec.signal(name, b, j, c, LAST[name])
            if not s:
                continue
            m = mins(b.index[j + 1])                            # decided at the close of bar j
            if gate is not None and gate(day, m):
                continue
            f = fill_ladder(legs[s], b.index[j + 1], stop, tgt, ec.LADDER)
            if f is None:
                continue
            out.append(dict(day=day, m=m, net=f[0] * LOT - CHG))
            n += 1
            busy = f[1]
    return pd.DataFrame(out, columns=["day", "m", "net"])


class _Last:
    """Stands in for liquidity_break.LAST_M inside `5 <= m <= LAST_M`: the entry window plus the gate (the day is
    read from simulate()'s frame), so a blocked entry leaves the book flat and free for a later break."""

    def __init__(self, gate):
        self.gate = gate

    def __ge__(self, m):
        if m > 315:
            return False
        if self.gate is None:
            return True
        return not self.gate(sys._getframe(1).f_locals["d"]["day"], m)


def run_liq(ldays, books, gate=None):
    lb.LAST_M = _Last(gate)
    lb.LOT = LOT
    rows = []
    for tf in (15, 5):
        b, zones = books[tf]
        tr = lb.simulate(ldays, b, zones, "both", True, prem_stop=0.15, ix_buffer=30, time_stop=(20, 0.05))
        if not tr.empty:
            rows.append(pd.DataFrame(dict(day=tr.day, m=tr.m0, net=tr.rs)))
    lb.LAST_M = 315
    return pd.concat(rows) if rows else pd.DataFrame(columns=["day", "m", "net"])


# ---- detectors --------------------------------------------------------------------------------------------------

def index_minutes(paths):
    parts = []
    for p in paths:
        d = pd.read_parquet(p, columns=["ts", "open", "high", "low", "close", "right"])
        parts.append(d[d.right == "IX"].drop(columns="right"))
    ix = pd.concat(parts).drop_duplicates("ts").sort_values("ts")
    ix["ts"] = pd.to_datetime(ix.ts)
    return ix.set_index("ts").between_time("09:15", "15:29")


def wilder(x, n):
    return pd.Series(x).ewm(alpha=1 / n, adjust=False).mean().values


def bars15(ix):
    s = ix.resample("15min", label="left", closed="left").agg(
        {"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
    h, l, c = s.high.values, s.low.values, s.close.values
    pc = np.r_[c[0], c[:-1]]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    up, dn = np.r_[0, np.diff(h)], np.r_[0, -np.diff(l)]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    ndm = np.where((dn > up) & (dn > 0), dn, 0.0)
    atr = wilder(tr, 14)
    pdi, ndi = 100 * wilder(pdm, 14) / atr, 100 * wilder(ndm, 14) / atr
    dx = 100 * abs(pdi - ndi) / np.maximum(pdi + ndi, 1e-9)
    s["adx"] = wilder(dx, 14)
    s["adx"] = s.adx.where(np.arange(len(s)) >= 28)           # warm-up
    rng = pd.Series(h).rolling(14).max().values - pd.Series(l).rolling(14).min().values
    s["chop"] = 100 * np.log10(pd.Series(tr).rolling(14).sum().values / rng) / np.log10(14)
    s["day"] = s.index.date
    s["known"] = (s.index.hour * 60 + s.index.minute - 555) + 15  # minute after 09:15 when the candle has closed
    return s


def daily_features(ix, vix_path):
    g = ix.groupby(ix.index.date)
    d = pd.DataFrame({"hi": g.high.max(), "lo": g.low.min()})
    d["rng"] = d.hi - d.lo
    o = ix.between_time("09:15", "09:59")
    go = o.groupby(o.index.date)
    d["or"] = go.high.max() - go.low.min()
    d["PDR"] = d.rng.shift(1) / d.rng.shift(1).rolling(20).mean()
    d["OR"] = d["or"] / d["or"].shift(1).rolling(20).mean()
    s = bars15(ix)
    at10 = s[s.known == 45].set_index("day")
    d["ADX"] = at10.adx
    d["CHOP"] = at10.chop
    d["VIX"] = np.nan
    if vix_path and os.path.exists(vix_path):
        v = pd.read_parquet(vix_path)
        v = v[v.series == "INDIAVIX"].copy()
        v["ts"] = pd.to_datetime(v.ts)
        vc = v.groupby(v.ts.dt.date).close.last()
        pr = vc.rolling(60, min_periods=20).apply(lambda x: (x[:-1] < x[-1]).mean() if len(x) > 1 else np.nan, raw=True)
        d["VIX"] = pr.shift(1).reindex(d.index)               # prior close's percentile: known before the open
    return d, s


DAY_RULES = {  # name: (feature, side, threshold, when (minute after 09:15 from which entries are blocked), sens)
    "PDR < 0.7 (prior day range / 20d avg)": ("PDR", "<", 0.7, 0, [0.6, 0.8]),
    "VIX pct < 25% (prior close, 60d)": ("VIX", "<", 0.25, 0, [0.15, 0.33]),
    "ADX15m(14) < 20 at 10:00": ("ADX", "<", 20, 45, [15, 25]),
    "CHOP15m(14) > 61.8 at 10:00": ("CHOP", ">", 61.8, 45, [55, 65]),
    "OR(09:15-10:00) < 0.7 x 20d avg": ("OR", "<", 0.7, 45, [0.6, 0.8]),
}


def flag(d, feat, side, thr):
    x = d[feat]
    return (x < thr) if side == "<" else (x > thr)


def day_gate(sideways_days, when):
    sd = set(sideways_days)
    return lambda day, m: (day in sd) and m >= when


def pause_gate(s, col, side, thr):
    """Blocked at minute m when the last 15m candle closed by m reads sideways (from 09:30 on; before it the
    previous day's last candle - continuous, as a chart)."""
    flags = (s[col] < thr) if side == "<" else (s[col] > thr)
    known = s.known.values
    by = {}
    for day, k, f in zip(s.day.values, known, flags.values):
        by.setdefault(day, []).append((k, bool(f)))
    days = sorted(by)
    prev_last = {}
    for i, dd in enumerate(days):
        prev_last[dd] = by[days[i - 1]][-1][1] if i else False
    cache = {}

    def gate(day, m):
        key = (day, m)
        if key not in cache:
            st = prev_last.get(day, False)
            for k, f in by.get(day, []):
                if k <= m:
                    st = f
                else:
                    break
            cache[key] = st
        return cache[key]
    return gate


# ---- statistics -------------------------------------------------------------------------------------------------

def maxdd(tr, alldays):
    daily = tr.groupby("day").net.sum().reindex(alldays, fill_value=0.0).cumsum()
    return float((daily - daily.cummax()).min()) if len(daily) else 0.0


def perm(tr, alldays, k, actual, n=2000, seed=7):
    """Change in total P&L from skipping k random days: (mean, p of >= actual)."""
    if k == 0:
        return 0.0, float("nan")
    daily = tr.groupby("day").net.sum().reindex(alldays, fill_value=0.0).values
    rng = np.random.default_rng(seed)
    sims = np.array([-daily[rng.choice(len(daily), k, replace=False)].sum() for _ in range(n)])
    return float(sims.mean()), float((sims >= actual).mean())


def summ(x):
    return len(x), x.net.sum(), (x.net > 0).mean() * 100 if len(x) else float("nan"), x.net.mean() if len(x) else float("nan")


def main():
    pa, pb, out = sys.argv[1], sys.argv[2], sys.argv[3]
    cache = sys.argv[4] if len(sys.argv) > 4 and not sys.argv[4].startswith("--") else os.path.dirname(out)
    global LOT
    args = sys.argv
    LOT = int(args[args.index("--lot") + 1]) if "--lot" in args else 30
    vix = args[args.index("--vix") + 1] if "--vix" in args else None
    name = args[args.index("--name") + 1] if "--name" in args else "BANKNIFTY"

    ix = index_minutes([pa, pb])
    feats, s15 = daily_features(ix, vix)
    feats.to_csv(os.path.join(cache, f"sideways_feats_{name}.csv"))

    pause = {"ADX-pause (15m ADX14 < 20)": ("adx", "<", 20), "CHOP-pause (15m CHOP14 > 61.8)": ("chop", ">", 61.8)}
    cf = os.path.join(cache, f"sideways_trades_{name}.pkl")
    if os.path.exists(cf):
        res = pickle.load(open(cf, "rb"))
    else:
        res = {}
        for yr, p in (("A", pa), ("B", pb)):
            A = al.load("file:" + p)
            for arm in ARMS[:4]:
                res[(yr, arm, "base")] = run_arm(A, arm)
                for pn, (col, side, thr) in pause.items():
                    res[(yr, arm, pn)] = run_arm(A, arm, pause_gate(s15, col, side, thr))
                print(yr, arm, len(res[(yr, arm, "base")]), res[(yr, arm, "base")].net.sum(), flush=True)
            res[(yr, "days")] = [d for d, _, _ in A]
            del A
            L = liq_load(p)
            books = {}
            for tf in (15, 5):
                b = lb.bars(L, tf)
                books[tf] = (b, swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10))
            res[(yr, "Liquidity 15+5", "base")] = run_liq(L, books)
            for pn, (col, side, thr) in pause.items():
                res[(yr, "Liquidity 15+5", pn)] = run_liq(L, books, pause_gate(s15, col, side, thr))
            print(yr, "liq", len(res[(yr, "Liquidity 15+5", "base")]), res[(yr, "Liquidity 15+5", "base")].net.sum(), flush=True)
            res[(yr, "ldays")] = [d["day"] for d in L]
            del L, books
        pickle.dump(res, open(cf, "wb"))

    yrs = {yr: sorted(set(res[(yr, "days")]) | set(res[(yr, "ldays")])) for yr in "AB"}
    W = []
    W += [f"# Sideways guard: would \"stop the arms when the market is sideways\" have helped? (research/sideways_guard.py)", "",
          f"{name}, two years of 1-minute data: A {yrs['A'][0]} .. {yrs['A'][-1]} ({len(yrs['A'])} sessions), "
          f"B {yrs['B'][0]} .. {yrs['B'][-1]} ({len(yrs['B'])} sessions). Arms exactly as research/entry_cutoff.py "
          f"replicates them today (1 lot of {LOT}, real option minute prices, 0.5 slippage a side, Rs 40 a trip). "
          "Rs per lot after costs.", ""]

    base = {}
    W += ["## The arms without any guard", "", "| arm | A trades | A net | A max DD | B trades | B net | B max DD |", "|---|---|---|---|---|---|---|"]
    for arm in ARMS:
        r = [arm]
        for yr in "AB":
            t = res[(yr, arm, "base")]
            base[(yr, arm)] = t
            r += [str(len(t)), f"{t.net.sum():+,.0f}", f"{maxdd(t, yrs[yr]):,.0f}"]
        W.append("| " + " | ".join(r) + " |")
    W.append("")

    # feature coverage
    W += ["## How often each detector calls a day sideways", "", "| detector | A days flagged | B days flagged |", "|---|---|---|"]
    for dn, (feat, side, thr, when, _) in DAY_RULES.items():
        r = [dn]
        for yr in "AB":
            f = flag(feats.reindex(yrs[yr]), feat, side, thr)
            r.append(f"{int(f.sum())} / {len(yrs[yr])} ({f.mean() * 100:.0f}%)")
        W.append("| " + " | ".join(r) + " |")
    W.append("")

    verdict = {arm: [] for arm in ARMS}
    W += ["## Day-level detectors: sideways days vs the rest, and the effect of skipping them", "",
          "Per arm and year: trades / net / win% / avg on sideways days (S) and other days (O); the net and max DD with "
          "the guard on; the change vs no guard; the change expected from skipping the same number of random days "
          "(for a losing arm any skip 'helps'); p = share of 2,000 random skips that did at least as well. "
          "The 10:00 detectors only block entries decided at or after 10:00 (Liquidity's 09:20-09:55 trades stay).", ""]
    for dn, (feat, side, thr, when, sens) in DAY_RULES.items():
        W += [f"### {dn}", "",
              "| arm | yr | S trades | S net | S win% | S avg | O trades | O net | O win% | O avg | guarded net | max DD (no guard -> guard) | change | random-skip change | p |",
              "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for arm in ARMS:
            good = []
            for yr in "AB":
                t = base[(yr, arm)]
                fl = flag(feats.reindex(yrs[yr]), feat, side, thr)
                sdays = set(fl[fl].index)
                isS = t.day.isin(sdays) & (t.m >= when)
                S, O = t[isS], t[~isS]
                g = O
                ch = g.net.sum() - t.net.sum()
                k = len(sdays)
                exp, p = perm(t[t.m >= when], yrs[yr], k, ch)
                sn, ss, sw, sa = summ(S)
                on, os_, ow, oa = summ(O)
                W.append(f"| {arm} | {yr} | {sn} | {ss:+,.0f} | {sw:.0f} | {sa:+,.0f} | {on} | {os_:+,.0f} | {ow:.0f} | {oa:+,.0f} | "
                         f"{g.net.sum():+,.0f} | {maxdd(t, yrs[yr]):,.0f} -> {maxdd(g, yrs[yr]):,.0f} | {ch:+,.0f} | {exp:+,.0f} | {p:.2f} |")
                good.append(dict(ch=ch, exp=exp, p=p, avgS=sa, avgO=oa, dd=maxdd(g, yrs[yr]) - maxdd(t, yrs[yr])))
            verdict[arm].append((dn, good))
        # sensitivity
        W += ["", f"Sensitivity (change in net vs no guard, A / B; thresholds {sens + [thr]}):", ""]
        for arm in ARMS:
            cells = []
            for th in sorted(sens + [thr]):
                c2 = []
                for yr in "AB":
                    t = base[(yr, arm)]
                    fl = flag(feats.reindex(yrs[yr]), feat, side, th)
                    sd = set(fl[fl].index)
                    c2.append(-t[t.day.isin(sd) & (t.m >= when)].net.sum())
                cells.append(f"{th}: {c2[0]:+,.0f} / {c2[1]:+,.0f}")
            W.append(f"- {arm}: " + "; ".join(cells))
        W.append("")

    W += ["## Intraday pause: arms skip new entries while the last 15-minute candle reads sideways", "",
          "Re-run with the gate inside each arm (a blocked signal leaves the arm free for a later one). "
          "Random-skip baseline: removing the same number of trades at random from the unguarded run.", "",
          "| detector | arm | yr | trades no guard -> paused | net no guard | net paused | change | random change | p | max DD no guard -> paused |",
          "|---|---|---|---|---|---|---|---|---|---|"]
    rng = np.random.default_rng(11)
    for pn in pause:
        for arm in ARMS:
            good = []
            for yr in "AB":
                t, g = base[(yr, arm)], res[(yr, arm, pn)]
                ch = g.net.sum() - t.net.sum()
                k = max(len(t) - len(g), 0)
                sims = np.array([-t.net.values[rng.choice(len(t), k, replace=False)].sum() for _ in range(2000)]) if k else np.zeros(1)
                W.append(f"| {pn} | {arm} | {yr} | {len(t)} -> {len(g)} | {t.net.sum():+,.0f} | {g.net.sum():+,.0f} | {ch:+,.0f} | "
                         f"{sims.mean():+,.0f} | {(sims >= ch).mean():.2f} | {maxdd(t, yrs[yr]):,.0f} -> {maxdd(g, yrs[yr]):,.0f} |")
                good.append(dict(ch=ch, exp=float(sims.mean()), p=float((sims >= ch).mean()), dd=maxdd(g, yrs[yr]) - maxdd(t, yrs[yr])))
            verdict[arm].append((pn, good))
    W.append("")

    W += ["## Scorecard", "",
          "A guard 'passes' for an arm only if, in BOTH years, it raises net P&L by more than skipping the same number "
          "of random days/trades would (p < 0.10) and does not deepen the max drawdown.", "",
          "| arm | detector | A change (random) p | B change (random) p | passes |", "|---|---|---|---|---|"]
    passes = {}
    for arm in ARMS:
        for dn, gd in verdict[arm]:
            ok = all(x["ch"] > max(x["exp"], 0) and x["p"] < 0.10 and x["dd"] >= 0 for x in gd)
            passes.setdefault(arm, []).append((dn, ok))
            W.append(f"| {arm} | {dn} | {gd[0]['ch']:+,.0f} ({gd[0]['exp']:+,.0f}) {gd[0]['p']:.2f} | "
                     f"{gd[1]['ch']:+,.0f} ({gd[1]['exp']:+,.0f}) {gd[1]['p']:.2f} | {'YES' if ok else 'no'} |")
    W.append("")
    open(out, "w").write("\n".join(W))
    print("\n".join(W))
    pickle.dump(passes, open(os.path.join(cache, f"sideways_passes_{name}.pkl"), "wb"))


if __name__ == "__main__":
    main()
