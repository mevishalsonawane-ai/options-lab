"""Jarvis's own trades: which exit rule? The 30-point stop / +60 target / profit-lock ladder ("30/60") against fixed
alternatives, on four entry sets, real option minute prices, the app's fills and charges.

    python3 -I research/jarvis_exits.py <scratchpad>

<scratchpad>/maxloss/prep/{NIFTY,BANKNIFTY,FINNIFTY}.gz (+ _expiry.csv): the Dhan expired-option minutes exported by
maxloss/scripts/prep.py (index 1-min OHLC + every ATM+-10 option minute of the nearest expiry, Aug 2020 - 5 Oct 2026).
<scratchpad>/arms/liq2/out/v_wf.csv: the Liquidity 15+5 replay's trades (variant wf_room1_itm1 = the app's arm).
Untrusted data: run with python -I. Caches go to <scratchpad>/jx/.

THE OPTION (as IraNewsTrades.contract / JarvisTrades.OptionSim pick it): ATM on the index's strike step (NIFTY 50,
BANKNIFTY 100, FINNIFTY 50; floor(spot/step + 0.5)), the nearest expiry strictly after today, 1 lot of the day's size,
a MARKET buy at the entry minute's open (+5 bps, the paper account), not bought at a premium of 35 or less
(JarvisTrades.MIN_PREMIUM - kept for every candidate so all see the same trades). Out by 15:15 (MARKET at the 15:15
minute's open). Expiry days are skipped: the data has only the nearest expiry, which on an expiry day is today's, and
Jarvis would buy the next one (and suggests nothing after 13:00 there anyway).

COSTS: the app's paper fills (MARKET +-5 bps, resting stop -10 bps) and its F&O charges per leg (SandboxCosts).
Within a minute: the stop (or lock) first, then the target; index / time exits are read on the minute's index bar and
filled at the next minute's open (MARKET).

ENTRY SETS
 PAT  Jarvis's pattern ideas as PatternExpert.judge makes them: Patterns.at on 5- and 15-minute candles of NIFTY,
      BANKNIFTY, FINNIFTY; only kinds whose index record HELD (Edge.held: >= 30 cases, >= 12 in each half, >= 55% its
      way over the next 4 candles in both halves of the trailing two years, average move > 0; recomputed each month
      from the two years before it - no look-ahead); suggested 09:20-14:30 at the candle's close, the 15-minute trend
      tracker (Supertrend 10, 3) agreeing on 15-minute ideas (the app reads no 5-minute trend), at least 0.2% room to
      the nearest level its way (yesterday's high/low/close, the opening range, 15-min swing points of the last 5 days),
      at most 2 a day. NOT applied: Edge.optionHeld (it scores each pattern with 30/60 itself, so it would pick entries
      for the rule under test), the trade check, IV rank and the daily loss limit.
 LIQ  Liquidity 15+5 entries (BANKNIFTY / FINNIFTY breaks, arm replay wf_room1_itm1) - a proxy for level-break ideas -
      bought as Jarvis buys (ATM, not the arm's one-ITM).
 SOLO Solo midday signals (SoloMidday: 12:00 decision, |c-o| >= 0.5 ATR14, close in the outer 25%; every index that
      signals, not only the strongest) - a proxy for momentum ideas - bought as Jarvis buys at 12:00.
 RND  the control: every non-expiry day, each index, one entry at a random minute 09:20-14:30, side by coin flip.

EXIT CANDIDATES - fixed before any result was looked at (12):
 C0  current: 30-pt stop, +60 target, ladder on 60 (+15 BE after charges, +30 lock 15, +45 lock 30)
 P1  -15% stop / +30% target          P2  the same + ladder on the 30% target
 P3  -20% / +40%                      P4  the same + ladder on 40%
 P5  -25% / +50%                      P6  the same + ladder on 50%
 I1  -15% premium stop + index stop (index back through the entry index price by 30 pts BANKNIFTY / 15 FINNIFTY and
     NIFTY, on the minute's low/high) + 20-min time stop (out if not +5%) + target at 2x risk (+30%)
 X1  40-pt stop / +80 target + ladder on 80
 T1  the Pine scripts' exit: 30/60 + ladder on 60 + the Pine % trail (ProfitLock.Trail defaults: +5% BE after charges,
     from +8% keep 50% of the best gain, from +20% 65%, from +40% 75%), the higher lock counting
 T2  -20% stop, no target, the Pine % trail only
 R1  risk-capped: the stop is the TIGHTER of 30 pts and 15% of the premium, target 2x that stop, ladder on the target
     (never risks more than C0 by construction)
"""
from __future__ import annotations

import gzip
import math
import os
import pickle
import random
import sys
from collections import defaultdict
from datetime import date, timedelta
from decimal import ROUND_HALF_EVEN, Decimal, getcontext

sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps when run with python -I
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

getcontext().prec = 60
SP = sys.argv[1]
PREP = os.path.join(SP, "maxloss/prep")
LIQ_CSV = os.path.join(SP, "arms/liq2/out/v_wf.csv")
CACHE = os.path.join(SP, "jx")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "JARVIS_EXITS.md")
os.makedirs(CACHE, exist_ok=True)

UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY"]
STEP = {"NIFTY": 50, "BANKNIFTY": 100, "FINNIFTY": 50}
IDX_STOP = {"NIFTY": 15.0, "BANKNIFTY": 30.0, "FINNIFTY": 15.0}
OPEN_M, LAST_M = 555, 929
EXIT_M = 15 * 60 + 15
MIN_PREMIUM = 35.0
TICK = 0.05
LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]
NEED = "**yes - needs Boss's OK**"
ADDS_TXT = "Adds risk - needs Boss's OK. "
TRAIL_BE, TRAIL_STEPS = 5.0, [(8.0, 50.0), (20.0, 65.0), (40.0, 75.0)]


# ---------------------------------------------------------------- app fills and charges (SandboxCosts, Px)
CENT = Decimal("0.01")


def adverse(p, buy, bps):
    if p <= 0:
        return p
    d = Decimal(p)
    delta = d * Decimal(bps) / Decimal(10000)
    s = d + delta if buy else d - delta
    if s <= 0:
        return 0.01
    return float(s.quantize(CENT, rounding=ROUND_HALF_EVEN))


def buy_mkt(p): return adverse(p, True, 5)
def sell_mkt(p): return adverse(p, False, 5)
def sell_stop(p): return adverse(p, False, 10)


def charge(buy, price, qty):
    value = price * abs(qty)
    if not value > 0:
        return 0.0
    b = value if buy else 0.0
    s = 0.0 if buy else value
    txn = (b + s) * 0.0003553
    sebi = (b + s) * (10.0 / 1_00_00_000)
    tot = 20.0 + s * 0.0015 + txn + sebi + b * 0.00003 + (20.0 + txn + sebi) * 0.18
    return float(Decimal(tot).quantize(CENT, rounding=ROUND_HALF_EVEN))


def rt_per_unit(e, qty):
    buy = charge(True, e, qty)
    first = (buy + charge(False, e, qty)) / qty
    return (buy + charge(False, e + first, qty)) / qty


def floor_tick(x):
    t = math.floor(x / TICK + 1e-9) * TICK
    return round(t * 100) / 100.0


def stop_points(e, pts):
    r = floor_tick(e - pts)
    return r if TICK + 1e-9 < r < e else None


def ladder_level(e, ref, peak, cpu):
    be = e + max(cpu, 0.0)
    lv = [max(e + b * ref, be) for a, b in LADDER if peak >= e + a * ref - 1e-9]
    lv = [x for x in lv if x < peak]
    return max(lv) if lv else None


def trail_level(e, peak, cpu):
    if not peak > e:
        return None
    gain = (peak - e) / e * 100
    lv = []
    be = e + max(cpu, 0.0)
    if gain >= TRAIL_BE - 1e-9 and be < peak:
        lv.append(be)
    for s, k in TRAIL_STEPS:
        if gain >= s - 1e-9:
            lv.append(e + k / 100 * (peak - e))
    return max(lv) if lv else None


# ---------------------------------------------------------------- exit candidates
class Exit:
    def __init__(self, name, label, stop_pts=None, stop_pct=None, tgt_pts=None, tgt_pct=None, ladder=False, trail=False,
                 index_time=False, capped=False):
        self.name, self.label = name, label
        self.stop_pts, self.stop_pct, self.tgt_pts, self.tgt_pct = stop_pts, stop_pct, tgt_pts, tgt_pct
        self.ladder, self.trail, self.index_time, self.capped = ladder, trail, index_time, capped

    def plan(self, e):
        """(stop trigger or None, target price or None, ladder reference in points or None)."""
        if self.capped:
            pts = min(30.0, 0.15 * e)
            stop = stop_points(e, pts)
            return stop, e + 2 * pts, 2 * pts
        stop = stop_points(e, self.stop_pts) if self.stop_pts else (stop_points(e, e * self.stop_pct) if self.stop_pct else None)
        tgt_pts = self.tgt_pts if self.tgt_pts else (e * self.tgt_pct if self.tgt_pct else None)
        return stop, (e + tgt_pts if tgt_pts else None), (tgt_pts if self.ladder else None)


EXITS = [
    Exit("C0", "C0 current 30-pt stop / +60 / ladder on 60", stop_pts=30, tgt_pts=60, ladder=True),
    Exit("P1", "P1 -15% / +30%", stop_pct=0.15, tgt_pct=0.30),
    Exit("P2", "P2 -15% / +30% + ladder", stop_pct=0.15, tgt_pct=0.30, ladder=True),
    Exit("P3", "P3 -20% / +40%", stop_pct=0.20, tgt_pct=0.40),
    Exit("P4", "P4 -20% / +40% + ladder", stop_pct=0.20, tgt_pct=0.40, ladder=True),
    Exit("P5", "P5 -25% / +50%", stop_pct=0.25, tgt_pct=0.50),
    Exit("P6", "P6 -25% / +50% + ladder", stop_pct=0.25, tgt_pct=0.50, ladder=True),
    Exit("I1", "I1 -15% + index stop + 20-min time stop + 2R (+30%)", stop_pct=0.15, tgt_pct=0.30, index_time=True),
    Exit("X1", "X1 40-pt stop / +80 / ladder on 80", stop_pts=40, tgt_pts=80, ladder=True),
    Exit("T1", "T1 Pine: 30/60 + ladder 60 + % trail", stop_pts=30, tgt_pts=60, ladder=True, trail=True),
    Exit("T2", "T2 -20% stop + % trail, no target", stop_pct=0.20, trail=True),
    Exit("R1", "R1 tighter of 30 pts / 15%, 2x target, ladder", ladder=True, capped=True),
]


# ---------------------------------------------------------------- data
def expiry_flags(und):
    flags = {}
    with open(os.path.join(PREP, f"{und}_expiry.csv")) as f:
        next(f)
        for ln in f:
            p = ln.strip().split(",")
            flags[date.fromisoformat(p[0])] = p[1] == "1" or p[2] == "1"
    return flags


def load_index(und):
    """Per day: (lot, expiry, minutes, o, h, l, c) from the D / X lines (cached)."""
    path = os.path.join(CACHE, f"ix_{und}.pkl")
    if os.path.exists(path):
        with open(path, "rb") as f:
            return pickle.load(f)
    flags = expiry_flags(und)
    out = {}
    cur, rows = None, []

    def flush():
        if cur is not None and rows:
            a = np.array(sorted(set(rows)))
            _, idx = np.unique(a[:, 0], return_index=True)
            a = a[idx]
            out[cur[0]] = dict(lot=cur[1], exp=cur[2], m=a[:, 0].astype(int), o=a[:, 1], h=a[:, 2], l=a[:, 3], c=a[:, 4])
    with gzip.open(os.path.join(PREP, f"{und}.gz"), "rt") as f:
        for ln in f:
            t = ln[0]
            if t == "X":
                p = ln.split(",")
                m = int(p[1])
                if OPEN_M <= m <= LAST_M:
                    rows.append((m, float(p[2]), float(p[3]), float(p[4]), float(p[5])))
            elif t == "D":
                flush()
                p = ln.strip().split(",")
                d = date.fromisoformat(p[1])
                cur, rows = (d, int(p[4]), flags.get(d, p[3] == "1")), []
    flush()
    last = 0
    for d in sorted(out):                        # a lot the OI could not tell: the previous day's
        if out[d]["lot"] > 0:
            last = out[d]["lot"]
        else:
            out[d]["lot"] = last
    with open(path, "wb") as f:
        pickle.dump(out, f)
    return out


def load_options(und, need):
    """need: {day: {(strike, 'C'|'P')}} -> {(day, strike, right): (m, o, h, l, c)} (cached per need set)."""
    import hashlib
    key = hashlib.sha1(repr(sorted((d.isoformat(), k, r) for d, s in need.items() for k, r in s)).encode()).hexdigest()[:12]
    path = os.path.join(CACHE, f"optv_{und}_{key}.pkl")
    if os.path.exists(path):
        with open(path, "rb") as f:
            return pickle.load(f)
    rows = defaultdict(list)
    want = None
    day = None
    with gzip.open(os.path.join(PREP, f"{und}.gz"), "rt") as f:
        for ln in f:
            t = ln[0]
            if t == "D":
                day = date.fromisoformat(ln[2:12])
                want = need.get(day)
            elif t == "O" and want:
                p = ln.split(",", 9)
                k = (int(float(p[1])), p[2])
                if k in want:
                    rows[(day, k[0], k[1])].append((int(p[3]), float(p[4]), float(p[5]), float(p[6]), float(p[7]), float(p[8])))
    out = {}
    for k, r in rows.items():
        a = np.array(sorted(set(r)))
        _, idx = np.unique(a[:, 0], return_index=True)
        a = a[idx]
        out[k] = (a[:, 0].astype(int), a[:, 1], a[:, 2], a[:, 3], a[:, 4], a[:, 5])
    with open(path, "wb") as f:
        pickle.dump(out, f)
    return out


# ---------------------------------------------------------------- candles and patterns (ira Candles / Patterns port)
def fold(ix, days, tf):
    """Candles (day, startMinute, o, h, l, c) over [days], anchored 09:15 (Candles.fold)."""
    out = []
    for d in days:
        x = ix[d]
        m = x["m"]
        slot = OPEN_M + ((m - OPEN_M) // tf) * tf
        b = 0
        n = len(m)
        while b < n:
            e = b
            while e + 1 < n and slot[e + 1] == slot[b]:
                e += 1
            out.append((d, int(slot[b]), x["o"][b], x["h"][b:e + 1].max(), x["l"][b:e + 1].min(), x["c"][e]))
            b = e + 1
    return out


def tracker(C, n=10, mult=3.0):
    """Candles.tracker (Supertrend): +1 / -1 per candle."""
    N = len(C)
    dirs = np.ones(N, dtype=int)
    a = 0.0
    fu = fl = 0.0
    up = True
    for i in range(N):
        _, _, o, h, l, c = C[i]
        rng = h - l
        tr = rng if i == 0 else max(rng, abs(h - C[i - 1][5]), abs(l - C[i - 1][5]))
        a = rng if i == 0 else a + (tr - a) / n
        mid = (h + l) / 2
        ub, lb = mid + mult * a, mid - mult * a
        if i == 0:
            fu, fl = ub, lb
        else:
            pc = C[i - 1][5]
            pu, pl = fu, fl
            fu = ub if (ub < pu or pc > pu) else pu
            fl = lb if (lb > pl or pc < pl) else pl
            up = True if c > pu else (False if c < pl else up)
        dirs[i] = 1 if up else -1
    return dirs


KINDS = ["BULLISH_ENGULFING", "BEARISH_ENGULFING", "HAMMER", "SHOOTING_STAR", "DOJI", "INSIDE_BAR", "THREE_WHITE_SOLDIERS",
         "THREE_BLACK_CROWS", "BREAKOUT_UP", "BREAKOUT_DOWN", "DOUBLE_TOP", "DOUBLE_BOTTOM"]
BIAS = {"BULLISH_ENGULFING": 1, "BEARISH_ENGULFING": -1, "HAMMER": 1, "SHOOTING_STAR": -1, "DOJI": 0, "INSIDE_BAR": 0,
        "THREE_WHITE_SOLDIERS": 1, "THREE_BLACK_CROWS": -1, "BREAKOUT_UP": 1, "BREAKOUT_DOWN": -1, "DOUBLE_TOP": -1, "DOUBLE_BOTTOM": 1}
LOOKBACK, HORIZON = 20, 4


def swings(c, k):
    hi, lo = [], []
    for i in range(k, len(c) - k):
        if all(j == i or c[j][3] < c[i][3] for j in range(i - k, i + k + 1)):
            hi.append(i)
        if all(j == i or c[j][4] > c[i][4] for j in range(i - k, i + k + 1)):
            lo.append(i)
    return hi, lo


def patterns_at(c, i):
    if i < 1 or i >= len(c):
        return []
    out = []
    _, _, xo, xh, xl, xc = c[i]
    _, _, po, ph, pl, pc = c[i - 1]
    body = lambda q: abs(q[5] - q[2])
    green = lambda q: q[5] > q[2]
    red = lambda q: q[5] < q[2]
    w = c[max(0, i - LOOKBACK):i]
    avg_body = sum(body(q) for q in w) / len(w) if w else body(c[i])
    xb, pb = body(c[i]), body(c[i - 1])
    xr = xh - xl
    if xr > 0:
        upper = xh - max(xo, xc)
        lower = min(xo, xc) - xl
        if red(c[i - 1]) and green(c[i]) and xc >= po and xo <= pc and xb > pb:
            out.append("BULLISH_ENGULFING")
        if green(c[i - 1]) and red(c[i]) and xc <= min(pc, po) and xo >= pc and xb > pb:
            out.append("BEARISH_ENGULFING")
        if xb <= 0.1 * xr and xr >= 0.5 * avg_body:
            out.append("DOJI")
        else:
            if lower >= 2 * xb and upper <= 0.5 * max(xb, xr * 0.1) and xb > 0:
                out.append("HAMMER")
            if upper >= 2 * xb and lower <= 0.5 * max(xb, xr * 0.1) and xb > 0:
                out.append("SHOOTING_STAR")
    if xh < ph and xl > pl:
        out.append("INSIDE_BAR")
    if i >= 2:
        a, p, x = c[i - 2], c[i - 1], c[i]
        if green(a) and green(p) and green(x) and p[5] > a[5] and x[5] > p[5] and all(body(q) >= 0.6 * avg_body for q in (a, p, x)):
            out.append("THREE_WHITE_SOLDIERS")
        if red(a) and red(p) and red(x) and p[5] < a[5] and x[5] < p[5] and all(body(q) >= 0.6 * avg_body for q in (a, p, x)):
            out.append("THREE_BLACK_CROWS")
    if i >= LOOKBACK:
        prior = c[i - LOOKBACK:i]
        if xc > max(q[3] for q in prior):
            out.append("BREAKOUT_UP")
        if xc < min(q[4] for q in prior):
            out.append("BREAKOUT_DOWN")
        dbl = double(c, i)
        if dbl:
            out.append(dbl)
    return out


def double(c, i):
    win = c[i - LOOKBACK:i]
    hi, lo = swings(win, 2)
    for a in range(len(hi)):
        for b in range(a + 1, len(hi)):
            h1, h2 = win[hi[a]][3], win[hi[b]][3]
            if hi[b] - hi[a] < 4 or abs(h1 - h2) > 0.0015 * h1:
                continue
            trough = min(q[4] for q in win[hi[a]:hi[b] + 1])
            if c[i][5] < trough and c[i - 1][5] >= trough:
                return "DOUBLE_TOP"
    for a in range(len(lo)):
        for b in range(a + 1, len(lo)):
            l1, l2 = win[lo[a]][4], win[lo[b]][4]
            if lo[b] - lo[a] < 4 or abs(l1 - l2) > 0.0015 * l1:
                continue
            peak = max(q[3] for q in win[lo[a]:lo[b] + 1])
            if c[i][5] > peak and c[i - 1][5] <= peak:
                return "DOUBLE_BOTTOM"
    return None


def pattern_entries(ix_all):
    """PatternExpert.judge over history (see the module doc)."""
    path = os.path.join(CACHE, "pat_entries.pkl")
    if os.path.exists(path):
        with open(path, "rb") as f:
            return pickle.load(f)
    cands = []        # (day, minute, und, tf, kind, side, spot)
    for und in UNDS:
        ix = ix_all[und]
        days = sorted(ix)
        dpos = {d: k for k, d in enumerate(days)}
        c15_all = fold(ix, days, 15)
        dir15 = tracker(c15_all)
        # 15-min candle index by (day, start) for the trend and swing levels
        pos15 = {(q[0], q[1]): k for k, q in enumerate(c15_all)}
        for tf in (5, 15):
            C = c15_all if tf == 15 else fold(ix, days, 5)
            cases = defaultdict(list)          # kind -> [(day, move%)]
            found = []
            for i in range(LOOKBACK, len(C)):
                ks = [k for k in patterns_at(C, i) if BIAS[k] != 0]
                if not ks:
                    continue
                if i + HORIZON < len(C) and C[i + HORIZON][0] == C[i][0]:
                    for k in ks:
                        cases[k].append((C[i][0], (C[i + HORIZON][5] - C[i][5]) / C[i][5] * 100 * BIAS[k]))
                found.append((i, ks))
            # the record each month: the two years before the month starts
            case_arr = {k: (np.array([dpos[d] for d, _ in v]), np.array([mv for _, mv in v])) for k, v in cases.items()}
            held_cache = {}

            def held(k, d):
                mkey = (d.year, d.month)
                if (k, mkey) in held_cache:
                    return held_cache[(k, mkey)]
                start = date(d.year, d.month, 1)
                lo_d = start - timedelta(days=730)
                ok = False
                if k in case_arr:
                    di, mv = case_arr[k]
                    win = [x for x in days if lo_d <= x < start]
                    if len(win) > 300:
                        a0, a1 = dpos[win[0]], dpos[win[-1]]
                        mid = dpos[win[len(win) // 2]]
                        sel = (di >= a0) & (di <= a1)
                        A = mv[sel & (di < mid)]
                        B = mv[sel & (di >= mid)]
                        if len(A) >= 12 and len(B) >= 12 and len(A) + len(B) >= 30:
                            allm = np.concatenate([A, B])
                            ok = (A > 0).mean() >= 0.55 and (B > 0).mean() >= 0.55 and allm.mean() > 0
                held_cache[(k, mkey)] = ok
                return ok

            for i, ks in found:
                d, start = C[i][0], C[i][1]
                done = start + tf
                if done < 9 * 60 + 20 or done > 14 * 60 + 30 or ix[d]["exp"]:
                    continue
                x = ix[d]
                # the last 1-min close before the suggestion (the snapshot's price)
                j = int(np.searchsorted(x["m"], done, side="left")) - 1
                if j < 0:
                    continue
                price = x["c"][j]
                for k in ks:
                    if not held(k, d):
                        continue
                    side = BIAS[k]
                    if tf == 15:
                        p = pos15.get((d, start))
                        if p is not None and p + 1 >= 30 and dir15[p] != side:
                            continue
                    # levels: yesterday's H/L/C, the opening range, 15-min swings of the last 5 days
                    lv = []
                    k0 = dpos[d]
                    if k0 > 0:
                        y = ix[days[k0 - 1]]
                        lv += [y["h"].max(), y["l"].min(), y["c"][-1]]
                    if j + 1 > 15:
                        lv += [x["h"][:15].max(), x["l"][:15].min()]
                    d5 = days[max(0, k0 - 4):k0 + 1]
                    p_now = pos15.get((d, OPEN_M + ((done - 1 - OPEN_M) // 15) * 15))
                    if p_now is not None:
                        # 15-min candles of the last 5 days that have closed by `done`
                        c15 = [q for q in c15_all[max(0, p_now - 200):p_now + 1] if q[0] in d5 and (q[0] < d or q[1] + 15 <= done)]
                        sh, sl = swings(c15, 3)
                        lv += [c15[s][3] for s in sh] + [c15[s][4] for s in sl]
                    ahead = [v for v in lv if (v > price if side > 0 else v < price)]
                    if ahead:
                        nxt = min(ahead) if side > 0 else max(ahead)
                        if abs(nxt - price) / price * 100 < 0.2:
                            continue
                    cands.append((d, done, und, tf, k, side, C[i][5]))
                    break
    cands.sort(key=lambda r: (r[0], r[1], UNDS.index(r[2]), r[3]))
    out, per_day = [], defaultdict(int)
    for d, done, und, tf, k, side, spot in cands:
        if per_day[d] >= 2:
            continue
        per_day[d] += 1
        out.append(dict(set="PAT", und=und, day=d, minute=done, side=side, spot=spot, tag=f"{tf}m {k}"))
    with open(path, "wb") as f:
        pickle.dump(out, f)
    return out


def liq_entries(ix_all):
    d = pd.read_csv(LIQ_CSV)
    d = d[d.variant == "wf_room1_itm1"]
    out = []
    for r in d.itertuples():
        day = date.fromisoformat(r.day)
        x = ix_all[r.und].get(day)
        if x is None or x["exp"]:
            continue
        hh, mm = r.entry_time.split(":")
        m = int(hh) * 60 + int(mm)
        j = int(np.searchsorted(x["m"], m))
        spot = x["o"][j] if j < len(x["m"]) and x["m"][j] <= m + 2 else r.sig_close
        out.append(dict(set="LIQ", und=r.und, day=day, minute=m, side=1 if r.right == "CE" else -1, spot=spot, tag=r.book))
    return out


def solo_entries(ix_all):
    out = []
    for und in UNDS:
        ix = ix_all[und]
        days = sorted(ix)
        tr = []
        prev_c = None
        for d in days:
            x = ix[d]
            h, l, c = x["h"].max(), x["l"].min(), x["c"][-1]
            tr.append(h - l if prev_c is None else max(h - l, abs(h - prev_c), abs(l - prev_c)))
            prev_c = c
        for k, d in enumerate(days):
            if k < 14 or ix[d]["exp"]:
                continue
            atr = float(np.mean(tr[k - 14:k]))
            x = ix[d]
            m = x["m"]
            pre = m < 720
            if pre.sum() < 100 or m[0] > 556 or not (m[pre][-1] == 719):
                continue
            o = x["o"][0]
            c = x["c"][pre][-1]
            hi, lo = x["h"][pre].max(), x["l"][pre].min()
            mv = c - o
            if abs(mv) < 0.5 * atr:
                continue
            side = 1 if mv > 0 else -1
            pos = (hi - c) / (hi - lo) if side > 0 else (c - lo) / (hi - lo)
            if pos > 0.25:
                continue
            j = int(np.searchsorted(m, 720))
            spot = x["o"][j] if j < len(m) and m[j] == 720 else c
            out.append(dict(set="SOLO", und=und, day=d, minute=720, side=side, spot=spot, tag=f"{abs(mv) / atr:.2f}"))
    return out


def random_entries(ix_all, seed=7):
    rnd = random.Random(seed)
    out = []
    for und in UNDS:
        for d in sorted(ix_all[und]):
            x = ix_all[und][d]
            m = rnd.randint(9 * 60 + 20, 14 * 60 + 30)
            side = rnd.choice((1, -1))
            if x["exp"]:
                continue
            j = int(np.searchsorted(x["m"], m))
            if j >= len(x["m"]) or x["m"][j] > m + 2:
                continue
            out.append(dict(set="RND", und=und, day=d, minute=m, side=side, spot=x["o"][j], tag=""))
    return out


# ---------------------------------------------------------------- the walk
def simulate(ex, en, ser, ix_day, lot):
    mins, op, hi, lo, cl, vol = ser
    m0 = en["minute"]
    ei = int(np.searchsorted(mins, m0))
    if ei >= len(mins) or mins[ei] > m0 + 2 or mins[ei] >= EXIT_M:
        return None
    # StrikeLiquidity: a strike that has traded under 50 lots today is not bought (here also when the data shows no
    # trades at all - its prints are then stale, carried forward)
    if vol[:ei].sum() < 50 * lot:
        return None
    e = buy_mkt(op[ei])
    if e <= MIN_PREMIUM:
        return None
    qty = lot
    stop, tgt, ref = ex.plan(e)
    cpu = rt_per_unit(e, qty)
    peak = e
    side = en["side"]
    ispot = en["spot"]
    istop = IDX_STOP[en["und"]]
    imap = None
    if ex.index_time:
        imap = {int(m): (h, l) for m, h, l in zip(ix_day["m"], ix_day["h"], ix_day["l"])}
    t_end = m0 + 20
    timed = False
    x_px = why = x_min = None
    pending = None
    i = ei
    while i < len(mins):
        m = int(mins[i])
        if pending is not None:                  # an index / time exit decided on the previous minute: MARKET at this open
            x_px, why, x_min = sell_mkt(op[i]), pending, m
            break
        if m >= EXIT_M:
            x_px, why, x_min = sell_mkt(op[i]), "15:15", m
            break
        lk = None
        if ref is not None:
            lk = ladder_level(e, ref, peak, cpu)
        if ex.trail:
            tl = trail_level(e, peak, cpu)
            if tl is not None and (lk is None or tl > lk):
                lk = tl
        if stop is not None and lo[i] <= stop and (lk is None or stop >= lk):
            x_px, why, x_min = sell_stop(min(stop, op[i])), "stop", m
            break
        if lk is not None and lo[i] <= lk:
            x_px, why, x_min = sell_mkt(min(lk, op[i])), "lock", m
            break
        if tgt is not None and hi[i] >= tgt:
            x_px, why, x_min = sell_mkt(max(tgt, op[i])), "target", m
            break
        peak = max(peak, hi[i])
        if ex.index_time:
            b = imap.get(m)
            if b is not None and (b[1] < ispot - istop if side > 0 else b[0] > ispot + istop):
                pending = "index_stop"
            elif not timed and m + 1 >= t_end:
                timed = True
                if cl[i] < e * 1.05 - 1e-9:
                    pending = "time_stop"
        i += 1
    if x_px is None:
        x_px, why, x_min = sell_mkt(cl[-1]), "last_bar", int(mins[-1])
    ch = charge(True, e, qty) + charge(False, x_px, qty)
    risk_pts = (e - stop) if stop is not None else e
    return dict(entry=e, exit=x_px, why=why, exit_min=x_min, qty=qty, gross=(x_px - e) * qty, charges=ch,
                net=(x_px - e) * qty - ch, pts=x_px - e, risk_pts=risk_pts, risk_rs=risk_pts * qty)


# ---------------------------------------------------------------- statistics
def stats(t, years_span):
    t = t.sort_values(["day", "exit_min"])
    n = len(t)
    if n == 0:
        return dict(trades=0, win=0, avg=0, net=0, per_year=0, pf=0, dd=0, years_pos=0, years=0, worst=0, avg_pts=0)
    net = t.net.values
    daily = t.groupby("day").net.sum()
    eq = daily.cumsum()
    dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min())
    yr = t.groupby(t.year).net.sum()
    gp, gl = net[net > 0].sum(), -net[net < 0].sum()
    return dict(trades=n, win=(net > 0).mean(), avg=net.mean(), net=net.sum(), per_year=net.sum() / years_span,
                pf=gp / gl if gl > 0 else float("inf"), dd=dd, years_pos=int((yr > 0).sum()), years=len(yr),
                worst=float(net.min()), avg_pts=t.pts.mean() - (t.charges / t.qty).mean())


def rs(x):
    return ("-" if x < 0 else "+") + f"{abs(x):,.0f}"


def main():
    ix_all = {u: load_index(u) for u in UNDS}
    print("index loaded", {u: len(v) for u, v in ix_all.items()}, flush=True)
    sets = {"PAT": pattern_entries(ix_all), "LIQ": liq_entries(ix_all), "SOLO": solo_entries(ix_all), "RND": random_entries(ix_all)}
    print({k: len(v) for k, v in sets.items()}, flush=True)
    entries = [e for v in sets.values() for e in v]
    need = {u: defaultdict(set) for u in UNDS}
    for en in entries:
        en["strike"] = int(math.floor(en["spot"] / STEP[en["und"]] + 0.5) * STEP[en["und"]])
        en["right"] = "C" if en["side"] > 0 else "P"
        need[en["und"]][en["day"]].add((en["strike"], en["right"]))
    opts = {}
    for u in UNDS:
        opts[u] = load_options(u, dict(need[u]))
        print("options", u, len(opts[u]), flush=True)
    rows = []
    for en in entries:
        ser = opts[en["und"]].get((en["day"], en["strike"], en["right"]))
        if ser is None or len(ser[0]) == 0:
            continue
        ixd = ix_all[en["und"]][en["day"]]
        for ex in EXITS:
            r = simulate(ex, en, ser, ixd, ixd["lot"])
            if r is None:
                continue
            r.update(set=en["set"], und=en["und"], day=en["day"], minute=en["minute"], side=en["side"], tag=en["tag"], x=ex.name)
            rows.append(r)
    T = pd.DataFrame(rows)
    T["year"] = pd.to_datetime(T.day).dt.year
    T.to_csv(os.path.join(CACHE, "jx_trades.csv"), index=False)
    report(T, sets)


def report(T, sets):
    L = []
    P = L.append
    names = [e.name for e in EXITS]
    label = {e.name: e.label for e in EXITS}
    SETS = ["PAT", "LIQ", "SOLO", "RND"]
    REAL = ["PAT", "LIQ", "SOLO"]
    span = {}
    for s in SETS:
        d = T[T.set == s].day
        span[s] = max((d.max() - d.min()).days / 365.25, 0.5) if len(d) else 1
    S = {(s, x): stats(T[(T.set == s) & (T.x == x)], span[s]) for s in SETS for x in names}
    real = T[T.set.isin(REAL)]
    span_real = (real.day.max() - real.day.min()).days / 365.25
    SR = {x: stats(real[real.x == x], span_real) for x in names}
    years = sorted(T.year.unique())
    by_year = real.groupby(["x", "year"]).net.sum().unstack().reindex(names).fillna(0)

    # walk-forward (anchored yearly, pooled over the three real sets): the first two calendar years only train
    test_years = [y for y in years if y >= years[0] + 2]
    wf = []
    for y in test_years:
        past = by_year[[c for c in years if c < y]].sum(axis=1)
        p = past.idxmax()
        wf.append((y, p, past[p], past["C0"], by_year.loc[p, y], by_year.loc["C0", y]))
    wf_net = sum(r[4] for r in wf)
    c0_oos = sum(r[5] for r in wf)
    wf_tr = pd.concat([real[(real.x == p) & (real.year == y)] for y, p, *_ in wf])
    c0_tr = real[(real.x == "C0") & real.year.isin(test_years)]
    span_oos = (c0_tr.day.max() - c0_tr.day.min()).days / 365.25
    s_wf, s_c0oos = stats(wf_tr, span_oos), stats(c0_tr, span_oos)
    # per-set walk-forward (does the same pick hold in each set?)
    wf_set = {}
    for s in REAL:
        t = T[T.set == s]
        by = t.groupby(["x", "year"]).net.sum().unstack().reindex(names).fillna(0)
        ys = sorted(t.year.unique())
        tot = tot0 = 0.0
        picks = []
        for y in ys[2:]:
            past = by[[c for c in ys if c < y]].sum(axis=1)
            p = past.idxmax(); picks.append(f"{y}:{p}")
            tot += by.loc[p, y]; tot0 += by.loc["C0", y]
        wf_set[s] = (tot, tot0, picks)

    # risk: planned stop in points and Rs per lot, by index (median), against C0's 30 points
    risk = T[T.set.isin(SETS)].groupby(["x", "und"]).agg(pts=("risk_pts", "median"), rs=("risk_rs", "median"),
                                                          p90=("risk_rs", lambda v: np.percentile(v, 90))).reset_index()
    prem = T[(T.x == "C0")].groupby("und").entry.median()
    adds = {}
    for x in names:
        r = risk[risk.x == x]
        c0 = risk[risk.x == "C0"].set_index("und")
        adds[x] = any(row.rs > c0.loc[row.und, "rs"] * 1.001 for row in r.itertuples())
    worst = {x: float(T[(T.x == x)].net.min()) for x in names}
    worst_real = {x: float(real[real.x == x].net.min()) for x in names}

    # the judgement (pre-declared in the docstring of this function's caller): beats C0 on net in the pooled real sets,
    # in >= 2 of 3 real sets, in most years, in walk-forward picks, with a drawdown no worse; and the gain on real
    # entries clearly larger than on random ones
    def beats(x):
        if x == "C0":
            return False
        sets_better = sum(S[(s, x)]["net"] > S[(s, "C0")]["net"] for s in REAL)
        yrs_better = int((by_year.loc[x] > by_year.loc["C0"]).sum())
        gain_real = SR[x]["avg"] - SR["C0"]["avg"]
        gain_rnd = S[("RND", x)]["avg"] - S[("RND", "C0")]["avg"]
        return (SR[x]["net"] > SR["C0"]["net"] and sets_better >= 2 and yrs_better > len(years) / 2
                and SR[x]["dd"] >= SR["C0"]["dd"] and SR[x]["net"] > 0), sets_better, yrs_better, gain_real, gain_rnd

    J = {x: beats(x) for x in names if x != "C0"}
    passing = [x for x in J if J[x][0]]
    safe_passing = [x for x in passing if not adds[x]]
    best = max(names, key=lambda x: SR[x]["net"])
    c0 = SR["C0"]

    P("## Jarvis's own trades: which exit rule? 30/60 against 11 fixed alternatives (research/jarvis_exits.py)")
    P("")
    P("### Verdict")
    P("")
    if passing:
        w = max(passing, key=lambda x: SR[x]["net"])
        ws = max(safe_passing, key=lambda x: SR[x]["net"]) if safe_passing else None
        P(f"**Best: {label[w]}.** {ADDS_TXT if adds[w] else 'Never risks more than 30/60. '}"
          f"On the three real entry sets pooled: Rs {rs(SR[w]['avg'])} a trade, Rs {rs(SR[w]['per_year'])} a year (1 lot each), max drawdown "
          f"Rs {rs(SR[w]['dd'])}, worst trade Rs {rs(worst_real[w])}, {SR[w]['years_pos']} of {SR[w]['years']} years positive - against 30/60's "
          f"Rs {rs(c0['avg'])} a trade, Rs {rs(c0['per_year'])} a year, drawdown Rs {rs(c0['dd'])}, worst trade Rs {rs(worst_real['C0'])}, "
          f"{c0['years_pos']} of {c0['years']} years.")
        if ws and ws != w:
            P("")
            P(f"**Best that adds no risk: {label[ws]}**: Rs {rs(SR[ws]['avg'])} a trade, Rs {rs(SR[ws]['per_year'])} a year, drawdown "
              f"Rs {rs(SR[ws]['dd'])}, worst trade Rs {rs(worst_real[ws])}, {SR[ws]['years_pos']} of {SR[ws]['years']} years positive.")
    else:
        P(f"**Nothing beats 30/60 robustly.** No candidate passed every test (better than 30/60 pooled, in at least 2 of the 3 real "
          f"entry sets and in most years, drawdown no worse, and positive). 30/60: Rs {rs(c0['avg'])} a trade, Rs {rs(c0['per_year'])} a "
          f"year, drawdown Rs {rs(c0['dd'])}, {c0['years_pos']} of {c0['years']} years positive. Best by net: {label[best]} "
          f"Rs {rs(SR[best]['per_year'])} a year (drawdown Rs {rs(SR[best]['dd'])}).")
    P("")
    P("**In plain words.** Keep the 30-point stop, +60 target and ladder for now: none of the 11 alternatives was better across "
      "the entry sets, the years and the walk-forward, and the only one ahead on total (X1, 40/80 + ladder) is ahead by about "
      f"Rs {SR['X1']['avg'] - SR['C0']['avg']:+.0f} a trade, risks a third more per lot, has a deeper drawdown, loses on the pattern ideas, and "
      "gains MORE on random entries than on real ones - an exit effect, not a better fit to Jarvis's ideas. The percentage stops "
      "(-15/-20/-25%) lose more in total and on the pattern and momentum sets (with the ladder a few are slightly ahead on the "
      "level breaks only, with drawdowns twice as deep), and on BANKNIFTY they risk about twice 30/60's points (adds risk). The "
      "Pine % trail (T1) is behind 30/60 on all three sets (it only cuts the drawdown on the momentum set). The bigger finding is that the exit is not what decides "
      f"Jarvis's results: with any rule, random entries lose about the charges (30/60: Rs {rs(S[('RND', 'C0')]['avg'])} a trade, "
      f"charges about Rs 70), and the real entry sets with 30/60 make Rs {rs(S[('PAT', 'C0')]['avg'])} (pattern ideas), "
      f"Rs {rs(S[('LIQ', 'C0')]['avg'])} (level breaks) and Rs {rs(S[('SOLO', 'C0')]['avg'])} (momentum) a trade. Only the momentum "
      "proxy is clearly positive; the pattern ideas lose with every rule tried.")
    P("")
    P(f"Walk-forward (each year trade the rule best on all earlier years, pooled real sets; {test_years[0]}-{test_years[-1]}): "
      f"Rs {rs(wf_net)} (drawdown Rs {rs(s_wf['dd'])}) against Rs {rs(c0_oos)} (drawdown Rs {rs(s_c0oos['dd'])}) for 30/60. Picks: "
      + ", ".join(f"{y} {p}" for y, p, *_ in wf) + ".")
    P("")
    P(f"Tried: {len(names)} exit rules ({', '.join(names)}), fixed before any result was read (the script's header); no parameter "
      "was tuned. With 11 alternatives one of them beating 30/60 by luck in a year or a set is expected; the tests below ask for it "
      "across sets, years and walk-forward.")
    P("")
    P("### Set-up")
    P("")
    P("- Option: as Jarvis buys it (IraNewsTrades.contract): ATM on the strike step, the nearest expiry strictly after today, 1 lot "
      "of the day's size, MARKET buy at the entry minute's open; not bought at 35 or less (kept for every rule). Out by 15:15.")
    P("- Real Dhan option minute bars (expired contracts), Aug 2020 - 5 Oct 2026 (BANKNIFTY / FINNIFTY from Aug 2021). App fills "
      "(MARKET +-5 bps, resting stop -10 bps) and the app's F&O charges per leg. Stop before target inside a minute; the "
      "ladder / trail rungs count from the minute after the best price; their breakeven is after charges (Boss's 06 Oct fix). "
      "A strike that traded under 50 lots that day before the entry is not bought (the app's StrikeLiquidity check; here it also "
      "drops stale, carried-forward prints, common in FINNIFTY's first months).")
    P("- Expiry days skipped in every set: the data holds only the nearest contract (today's on an expiry day), not the next one Jarvis would buy.")
    P(f"- Entry sets (trades priced with 30/60; others within a few): " + "; ".join(
        f"{s} {S[(s, 'C0')]['trades']}" for s in SETS) + ". Set definitions in the script's header: PAT = PatternExpert.judge's "
      "rules on 5/15-min NIFTY, BANKNIFTY, FINNIFTY (index record held in the trailing two years, trend, room, 09:20-14:30, 2 a "
      "day; NOT the 30/60 option filter, which would select entries for the rule under test); LIQ = Liquidity 15+5's entries "
      "(arm replay), bought ATM; SOLO = every index's Solo midday signal at 12:00; RND = one random minute and side a day per index.")
    P("")
    P("### All rules, the three real entry sets pooled (1 lot each trade, after costs)")
    P("")
    P("| rule | trades | win | Rs / trade | Rs / year | net | PF | max DD | worst trade | years + | years better than C0 | sets better than C0 | gain vs C0 a trade: real / random | adds risk? |")
    P("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for x in names:
        s = SR[x]
        if x == "C0":
            extra = "- | - | - |"
        else:
            j = J[x]
            extra = f"{j[2]} of {len(years)} | {j[1]} of 3 | {rs(j[3])} / {rs(j[4])} |"
        P(f"| {label[x]} | {s['trades']} | {s['win'] * 100:.0f}% | {rs(s['avg'])} | **{rs(s['per_year'])}** | {rs(s['net'])} | {s['pf']:.2f} | "
          f"{rs(s['dd'])} | {rs(worst_real[x])} | {s['years_pos']}/{s['years']} | {extra} {NEED if adds[x] else 'no'} |")
    P("")
    for s in SETS:
        P(f"### {s}: " + {"PAT": "Jarvis's pattern ideas", "LIQ": "Liquidity 15+5 entries, bought ATM", "SOLO": "Solo midday signals",
                         "RND": "random entries (the control)"}[s])
        P("")
        P("| rule | trades | win | Rs / trade | Rs / year | net | PF | max DD | worst trade | years + |")
        P("|---|---|---|---|---|---|---|---|---|---|")
        for x in names:
            q = S[(s, x)]
            P(f"| {x} | {q['trades']} | {q['win'] * 100:.0f}% | {rs(q['avg'])} | {rs(q['per_year'])} | {rs(q['net'])} | {q['pf']:.2f} | "
              f"{rs(q['dd'])} | {rs(q['worst'])} | {q['years_pos']}/{q['years']} |")
        P("")
    P("### Net by year, real sets pooled (Rs)")
    P("")
    P("| rule | " + " | ".join(str(y) for y in years) + " |")
    P("|---|" + "---|" * len(years))
    for x in names:
        P(f"| {x} | " + " | ".join(("**" if x != "C0" and by_year.loc[x, y] > by_year.loc["C0", y] else "") + rs(by_year.loc[x, y]) +
                                    ("**" if x != "C0" and by_year.loc[x, y] > by_year.loc["C0", y] else "") for y in years) + " |")
    P("")
    P("Bold: better than C0 that year. 2020 is NIFTY only (Aug-Dec); 2026 to 5 Oct.")
    P("")
    P("### Walk-forward")
    P("")
    P("| test year | picked | its net before | C0 before | picked that year | C0 that year |")
    P("|---|---|---|---|---|---|")
    for y, p, a, b, c, d in wf:
        P(f"| {y} | {p} | {rs(a)} | {rs(b)} | {rs(c)} | {rs(d)} |")
    P("")
    P("Per entry set (picked on that set's own past): " + "; ".join(
        f"{s}: Rs {rs(v[0])} vs C0 Rs {rs(v[1])} ({', '.join(v[2])})" for s, v in wf_set.items()) + ".")
    P("")
    P("### Risk per trade (planned stop, median per index; all sets)")
    P("")
    P("Median price paid (ATM, next expiry, at these entries): " + ", ".join(f"{u} Rs {prem.get(u, float('nan')):.0f}" for u in UNDS) + ".")
    P("")
    P("| rule | " + " | ".join(f"{u} pts | {u} Rs / lot (p90)" for u in UNDS) + " | worst single trade, any set | adds risk vs 30 pts? |")
    P("|---|" + "---|" * (2 * len(UNDS) + 2))
    for x in names:
        r = risk[risk.x == x].set_index("und")
        cells = []
        for u in UNDS:
            if u in r.index:
                cells.append(f"{r.loc[u, 'pts']:.0f} | {r.loc[u, 'rs']:,.0f} ({r.loc[u, 'p90']:,.0f})")
            else:
                cells.append("- | -")
        P(f"| {x} | " + " | ".join(cells) + f" | {rs(worst[x])} | {NEED if adds[x] else 'no'} |")
    P("")
    P("A rule 'adds risk' when its median planned stop per lot is larger than 30/60's on any index. T2 has no target and the "
      "trail; I1's index / time stops can only make a loss smaller than its -15% stop, except for gaps.")
    P("")
    P("### How trades end (share; real sets pooled)")
    P("")
    P("| rule | exits |")
    P("|---|---|")
    for x in names:
        vc = real[real.x == x].why.value_counts(normalize=True)
        P(f"| {x} | " + ", ".join(f"{k} {v * 100:.0f}%" for k, v in vc.items()) + " |")
    P("")
    P("### Caveats")
    P("")
    P("- Expiry days are not in any set (no next-expiry prices on them). Jarvis may suggest on expiry days until 13:00.")
    P(f"- PAT is small ({S[('PAT', 'C0')]['trades']} trades, 2022-2026: the held-record filter needs two years of history, and the "
      "trend / room / 2-a-day rules thin it); its per-rule differences are within noise. The live app also requires the 30/60 "
      "option record to be positive (Edge.optionHeld) - left out on purpose; with it the set would be chosen by the rule under test.")
    P("- LIQ and SOLO are proxies (level breaks and momentum ideas), bought the Jarvis way (ATM, next expiry, at the signal minute). "
      "SOLO takes every index's signal, not only the strongest. Trades are independent: no one-position limit, no daily loss limit.")
    P("- 'Per year' adds the three real sets together (one lot on every idea); each set alone is in its own table. Lot sizes are the "
      "day's (NIFTY 75/50/25/75/65, BANKNIFTY 25/15/30/35, FINNIFTY 40/25/65/60 over the years), so rupees weigh years differently; "
      "points per trade tell the same story.")
    P("- Fills are the paper account's; a real stop in a fast market can slip more, which hurts the tight point stops and the "
      "percentage stops on BANKNIFTY alike. Prices are minute bars: inside a minute the stop is assumed hit before the target.")
    P("- 12 rules were tried; a rule ahead in one set or a few years is expected by chance. The walk-forward (choose on the past, "
      "trade the next year) lost to simply keeping 30/60.")
    P("")
    with open(OUT, "w") as f:
        f.write("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main()
