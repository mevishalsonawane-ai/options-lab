"""Liquidity 15+5: which exits? The arm's own exits against the 30/60 rule, 40/80 and the ORB's 40/40 (+ ladders).

    python3 -I research/liquidity_exits_3060.py <prep dir> <dhan options dir> <cache dir> [parity v_wf.csv]

<prep dir>   the per-underlying day files (BANKNIFTY.gz, FINNIFTY.gz + *_expiry.csv) exported from the Dhan expired-option
             minutes by the liq study's prep.py (index 1-min OHLC + every ATM+-10 option minute, real data, Aug 2021 -
             5 Oct 2026). Untrusted data: run with python -I.
<dhan options dir>  dhan-data/options (only for the premium table of NIFTY / SENSEX, which the arm does not trade).

The ENTRIES are the app's Liquidity 15+5 exactly as it runs since commit 1bd37ad: BANKNIFTY 15-min + 5-min books,
FINNIFTY 30-min + 5-min books, one position per book, pool-on-swing break, 09:20-14:00, room filter (skip a break with
less than one index stop to the next level), one strike in the money, expiry days skipped, app paper fills
(MARKET +-5 bps, SL-M -10 bps) and the app's F&O charges (SandboxCosts). It is a line-by-line port of the liq / liq2
Kotlin replay (scratchpad arms/liq2/eng .../liq/Liq.kt, itself trade-for-trade equal to the app's OrbArms), and with
the arm's own exits it reproduces that replay's 'wf_room1_itm1' trades (checked when the parity csv is given).

Only the EXITS differ between variants, minute by minute; within a minute the premium stop is checked first, then the
profit lock, then the premium target (as OrbFill / ArmsBacktest walk the ORB's), then 15:10, the index stop, the time
stop, the next-liquidity target, the failed break and the new liquidity. A book is flat again the minute after its
exit, so a variant that exits sooner can take a later break the arm would have been holding through.
"""
from __future__ import annotations

import gzip
import math
import os
import pickle
import sys
from collections import deque
from datetime import date, timedelta
from decimal import ROUND_HALF_EVEN, Decimal, getcontext

sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps when run with python -I
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

getcontext().prec = 120
PREP, OPTDIR, CACHE = sys.argv[1], sys.argv[2], sys.argv[3]
PARITY = sys.argv[4] if len(sys.argv) > 4 else None
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "LIQUIDITY_EXITS_3060.md")
os.makedirs(CACHE, exist_ok=True)

# ---------------------------------------------------------------- LiquidityRules (engine/orb/LiquidityRules.kt)
L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300
OPEN_M = 9 * 60 + 15
WIN_FROM, WIN_TO = 9 * 60 + 20, 14 * 60
SQUARE_OFF = 15 * 60 + 10
TICK = 0.05
BOOKS = {"BANKNIFTY": [("liquidity15", 15), ("liquidity5", 5)], "FINNIFTY": [("liquidity30_fin", 30), ("liquidity5_fin", 5)]}
STEP = {"BANKNIFTY": 100, "FINNIFTY": 50}
IDX_STOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0}
MIN_ROOM, ITM_STEPS = 1.0, 1
PREM_STOP, TIME_STOP_MIN, TIME_STOP_GAIN = 0.15, 20, 0.05
LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]   # ProfitLock.LADDER


def fold(ones, tf):
    """ones: sorted list of (day, minute, o, h, l, c) -> tf-minute bars labelled by start (day, startMinute, o, h, l, c)."""
    out = []
    cur = None
    for d, m, o, h, lo, c in ones:
        k = (d, OPEN_M + (max(m - OPEN_M, 0) // tf) * tf)
        if cur is None or cur[0] != k:
            if cur is not None:
                out.append((cur[0][0], cur[0][1], cur[1], cur[2], cur[3], cur[4]))
            cur = [k, o, h, lo, c]
        else:
            cur[2] = max(cur[2], h); cur[3] = min(cur[3], lo); cur[4] = c
    if cur is not None:
        out.append((cur[0][0], cur[0][1], cur[1], cur[2], cur[3], cur[4]))
    return out


class Zone:
    __slots__ = ("kind", "side", "top", "bottom", "origin", "known", "broken")

    def __init__(self, kind, side, top, bottom, origin, known):
        self.kind, self.side, self.top, self.bottom, self.origin, self.known, self.broken = kind, side, top, bottom, origin, known, -1

    @property
    def edge(self):
        return self.top if self.side > 0 else self.bottom


def mark_breaks(zones, C):
    for z in zones:
        seg = C[z.known:]
        hit = (seg > z.top) if z.side > 0 else (seg < z.bottom)
        z.broken = int(z.known + np.argmax(hit)) if hit.any() else -1


def swing_zones(H, Lo, C):
    n = len(H)
    out = []
    if n > 2 * L:
        from numpy.lib.stride_tricks import sliding_window_view as sw
        lmax = np.full(n, np.inf); rmax = np.full(n, np.inf); lmin = np.full(n, -np.inf); rmin = np.full(n, -np.inf)
        wh = sw(H, L).max(axis=1); wl = sw(Lo, L).min(axis=1)       # w[k] = window k..k+L-1
        js = np.arange(L, n - L)
        lmax[js] = wh[js - L]; rmax[js] = wh[js + 1]; lmin[js] = wl[js - L]; rmin[js] = wl[js + 1]
        for i in range(2 * L, n):
            j = i - L
            if lmax[j] < H[j] and rmax[j] < H[j]:
                out.append(Zone("swing", 1, H[j], Lo[j], j, i))
            if lmin[j] > Lo[j] and rmin[j] > Lo[j]:
                out.append(Zone("swing", -1, H[j], Lo[j], j, i))
    mark_breaks(out, C)
    return out


def pool_zones(O, H, Lo, C):
    out = []
    cand = []   # [side, top, bottom, origin, count, last]
    for i in range(len(C)):
        o, h, lo, c = O[i], H[i], Lo[i], C[i]
        keep = []
        for z in cand:
            side, top, bot, origin = z[0], z[1], z[2], z[3]
            if i - origin > MAX_AGE:
                continue
            if (side > 0 and c > top) or (side < 0 and c < bot):
                continue
            touched = (h >= bot and c < top) if side > 0 else (lo <= top and c > bot)
            if touched and i - z[5] >= GAP and z[4] < CONTACTS:
                z[4] += 1; z[5] = i
            if z[4] >= CONTACTS and i - z[5] >= CONFIRM:
                dup = any(q.side == side and q.bottom <= top and bot <= q.top for q in out[-50:])
                if not dup:
                    out.append(Zone("pool", side, top, bot, origin, i))
                continue
            keep.append(z)
        cand = keep
        bh, bl = max(o, c), min(o, c)
        if h > bh:
            cand.append([1, h, bh, i, 1, i])
        if lo < bl:
            cand.append([-1, bl, lo, i, 1, i])
    mark_breaks(out, C)
    return out


def atm(spot, step):
    return int(math.floor(spot / step + 0.5) * step)


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


def round_trip(e, x, qty): return charge(True, e, qty) + charge(False, x, qty)


def rt_per_unit(e, qty):
    """ProfitLock.roundTripPerUnit: the charges per unit of a buy at e and a sell at its breakeven."""
    buy = charge(True, e, qty)
    first = (buy + charge(False, e, qty)) / qty
    return (buy + charge(False, e + first, qty)) / qty


def lock_level(e, ref, peak, cpu):
    """ProfitLock.level with Boss's breakeven-after-charges floor."""
    be = e + max(cpu, 0.0)
    lv = [max(e + b * ref, be) for a, b in LADDER if peak >= e + a * ref - 1e-9]
    lv = [x for x in lv if x < peak]
    return max(lv) if lv else None


def pts_trigger(e, pts):
    """A resting stop pts below the price paid, floored to the tick (JarvisTrades.stopFor); None with no room."""
    t = math.floor((e - pts) / TICK + 1e-9) * TICK
    r = round(t * 100) / 100.0
    return r if TICK + 1e-9 < r < e else None


def pct_trigger(e, pct):
    t = math.floor(e * (1 - pct) / TICK + 1e-9) * TICK
    r = round(t * 100) / 100.0
    return r if TICK <= r < e else None


# ---------------------------------------------------------------- data (prep.py day files)
def lot_of(und, d, lot_oi):
    if lot_oi > 0:
        return lot_oi
    if und == "BANKNIFTY":
        if d >= date(2025, 1, 31): return 30
        if d >= date(2024, 1, 1): return 15
        return 25 if d < date(2023, 7, 1) else 15
    return 40


def days_of(und):
    flags = {}
    with open(os.path.join(PREP, f"{und}_expiry.csv")) as f:
        next(f)
        for ln in f:
            p = ln.strip().split(",")
            flags[p[0]] = p[1] == "1" or p[2] == "1"
    head, ix, opt = None, [], []
    with gzip.open(os.path.join(PREP, f"{und}.gz"), "rt") as f:
        for ln in f:
            t = ln[0]
            if t == "O":
                opt.append(ln)
            elif t == "X":
                p = ln.split(","); ix.append((int(p[1]), float(p[2]), float(p[3]), float(p[4]), float(p[5])))
            elif t == "D":
                if head is not None:
                    yield head, flags, ix, opt
                head = ln.strip().split(","); ix, opt = [], []
    if head is not None:
        yield head, flags, ix, opt


def build(und):
    """Signals, index minutes and the option minutes they need, per book-day (cached)."""
    path = os.path.join(CACHE, f"liqx_{und}.pkl")
    if os.path.exists(path):
        with open(path, "rb") as f:
            return pickle.load(f)
    rec = []
    hist = deque()   # (day, real, bars1)
    step = STEP[und]
    for head, flags, ix, optlines in days_of(und):
        d = date.fromisoformat(head[1])
        exp = flags.get(head[1], head[3] == "1")
        real_today = head[5] != "spot"
        lot = lot_of(und, d, int(head[4]))
        today1 = [(d, m, o, h, lo, c) for m, o, h, lo, c in ix if 555 <= m <= 929]   # file order, as toBars
        while hist and hist[0][0] < d - timedelta(days=10):
            hist.popleft()
        real = real_today and hist and all(x[1] for x in hist)
        if real and not exp:
            first = {}
            for b in [x for h_ in hist for x in h_[2]] + today1:
                first.setdefault((b[0], b[1]), b)                       # distinctBy keeps the first
            ones = sorted(first.values(), key=lambda b: (b[0], b[1]))
            imin = {b[1]: b for b in today1}
            need = set()
            day_sigs = {}
            for book, tf in BOOKS[und]:
                bars = fold(ones, tf)
                if len(bars) < 2 * L + 2:
                    continue
                O = np.array([b[2] for b in bars]); H = np.array([b[3] for b in bars])
                Lo = np.array([b[4] for b in bars]); C = np.array([b[5] for b in bars])
                sw_, po = swing_zones(H, Lo, C), pool_zones(O, H, Lo, C)
                zones = sw_ + po
                n = len(bars)
                is_today = [b[0] == d for b in bars]
                bar_end = [b[1] + tf if is_today[k] else -1 for k, b in enumerate(bars)]
                by_break = {}
                for p in po:
                    if p.broken >= 0:
                        by_break.setdefault(p.broken, []).append(p)
                sigs = []
                for i in range(n):
                    if not is_today[i] or bars[i][1] + tf > 930 or i not in by_break:
                        continue
                    pool = None
                    for p in by_break[i]:
                        if p.known > i:
                            continue
                        if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i and (s.broken < 0 or s.broken >= i)
                               for s in sw_):
                            pool = p; break
                    if pool is None:
                        continue
                    side, close = pool.side, C[i]
                    ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i) and side * (q.edge - close) > 0]
                    target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
                    level = pool.edge
                    # structural exits (independent of the exit variant): first failed-break bar end, first new-level bar end
                    fb = next((bar_end[k] for k in range(i + 1, n) if side * (C[k] - level) < 0), None)
                    nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= i + 1]
                    nl = min(nl) if nl else None
                    strike = atm(close, step) - side * ITM_STEPS * step
                    right = "C" if side > 0 else "P"
                    need.add((strike, right))
                    sigs.append(dict(i=i, sig_min=bars[i][1], done=bar_end[i], side=side, level=level, target=target, close=close,
                                     fb=fb, nl=nl, strike=strike, right=right))
                if sigs:
                    day_sigs[book] = sigs
            if day_sigs:
                opts = {}
                for ln in optlines:
                    p = ln.split(",")
                    k = (int(float(p[1])), p[2])
                    if k in need:
                        opts.setdefault(k, []).append((int(p[3]), float(p[4]), float(p[5]), float(p[6]), float(p[7])))
                optser = {}
                for k, rows in opts.items():
                    rows.sort()
                    a = np.array(rows)
                    optser[k] = (a[:, 0].astype(int), a[:, 1], a[:, 2], a[:, 3], a[:, 4])
                rec.append(dict(und=und, day=d, lot=lot, imin={m: (b[3], b[4]) for m, b in imin.items()}, books=day_sigs, opts=optser))
        hist.append((d, real_today, today1))
    with open(path, "wb") as f:
        pickle.dump(rec, f)
    return rec


# ---------------------------------------------------------------- exit variants
class V:
    def __init__(self, name, label, prem_pct=PREM_STOP, stop_pts=None, tgt_pts=None, ladder=None, idx=True, timed=True,
                 next_liq=True, failed=True, newliq=True, family=""):
        self.name, self.label, self.prem_pct, self.stop_pts, self.tgt_pts, self.ladder = name, label, prem_pct, stop_pts, tgt_pts, ladder
        self.idx, self.timed, self.next_liq, self.failed, self.newliq, self.family = idx, timed, next_liq, failed, newliq, family


VARIANTS = [
    V("A", "A  current: -15% stop, index stop, 20-min time stop, next level, failed break, new level, 15:10", family="A"),
    V("B1", "B1 30/60 + ladder60, keep index + time stop", None, 30, 60, 60, family="B"),
    V("B2", "B2 30/60 + ladder60, drop index + time stop", None, 30, 60, 60, idx=False, timed=False, family="B"),
    V("B3", "B3 30/60 + ladder60 only (Jarvis's rule as is: + 15:10)", None, 30, 60, 60, idx=False, timed=False, failed=False, newliq=False,
      next_liq=False, family="B"),
    V("C1", "C1 40/80 + ladder80, keep index + time stop", None, 40, 80, 80, family="C"),
    V("C2", "C2 40/80 + ladder80, drop index + time stop", None, 40, 80, 80, idx=False, timed=False, family="C"),
    V("C3", "C3 40/80 + ladder80 only (+ 15:10)", None, 40, 80, 80, idx=False, timed=False, failed=False, newliq=False, next_liq=False, family="C"),
    V("D1", "D1 40/40 + ORB ladder40, keep index + time stop", None, 40, 40, 40, family="D"),
    V("D2", "D2 40/40 + ORB ladder40, drop index + time stop", None, 40, 40, 40, idx=False, timed=False, family="D"),
    V("D3", "D3 40/40 + ORB ladder40 only (the retired ORB arms' exits)", None, 40, 40, 40, idx=False, timed=False, failed=False,
      newliq=False, next_liq=False, family="D"),
    V("E60", "E  current + ladder on 60 (+15 BE, +30 lock 15, +45 lock 30)", ladder=60, family="E"),
    V("E40", "E' current + ladder on 40 (+10 BE, +20 lock 10, +30 lock 20)", ladder=40, family="E"),
    V("E80", "E'' current + ladder on 80 (+20 BE, +40 lock 20, +60 lock 40)", ladder=80, family="E"),
]


def last_at_or_before(mins, m):
    return int(np.searchsorted(mins, m, side="right")) - 1


def open_at_or_after(ser, m):
    mins, op, _, _, cl = ser
    i = last_at_or_before(mins, m - 1) + 1
    return (op[i], int(mins[i])) if i < len(mins) else (cl[-1], int(mins[-1]))


def run_variant(v, recs):
    trades = []
    for r in recs:
        und, lot, imin = r["und"], r["lot"], r["imin"]
        istop = IDX_STOP[und]
        for book, sigs in r["books"].items():
            flat_from = 0
            for s in sigs:
                if s["done"] < flat_from:
                    continue
                done = s["done"]
                if done > 930 or done < WIN_FROM or done > WIN_TO:
                    continue
                side, level, target = s["side"], s["level"], s["target"]
                if target is not None and side * (target - s["close"]) < MIN_ROOM * istop:
                    continue
                ser = r["opts"].get((s["strike"], s["right"]))
                if ser is None or len(ser[0]) == 0:
                    continue
                mins, op, hi, lo, cl = ser
                ei = last_at_or_before(mins, done - 1) + 1
                if ei >= len(mins) or mins[ei] > done + 2:
                    continue
                t0 = int(mins[ei])
                e = buy_mkt(op[ei])
                qty = lot
                ptrig = pct_trigger(e, v.prem_pct) if v.prem_pct else None
                strig = pts_trigger(e, v.stop_pts) if v.stop_pts else None
                trig = max([x for x in (ptrig, strig) if x is not None], default=None)
                tgt = e + v.tgt_pts if v.tgt_pts else None
                cpu = rt_per_unit(e, qty) if v.ladder else 0.0
                peak = e
                timed = not v.timed
                fb = s["fb"] if v.failed else None
                nl = s["nl"] if v.newliq else None
                x_px = x_why = x_min = None
                t = t0 + 1
                while t <= 930:
                    j = last_at_or_before(mins, t - 1)
                    if j >= ei and mins[j] == t - 1:
                        if trig is not None and lo[j] <= trig:
                            x_px, x_why, x_min = sell_stop(min(trig, op[j])), "premium_stop", t - 1; break
                        if v.ladder:
                            lk = lock_level(e, v.ladder, peak, cpu)
                            if lk is not None and lo[j] <= lk:
                                x_px, x_why, x_min = sell_mkt(min(lk, op[j])), "profit_lock", t - 1; break
                        if tgt is not None and hi[j] >= tgt:
                            x_px, x_why, x_min = sell_mkt(max(tgt, op[j])), "premium_target", t - 1; break
                        peak = max(peak, hi[j])
                    if t >= SQUARE_OFF:
                        p, m = open_at_or_after(ser, t); x_px, x_why, x_min = sell_mkt(p), "session_end", m; break
                    nm = imin.get(t - 1)
                    ltp = cl[j] if j >= 0 else e
                    why = None
                    if v.idx and nm is not None and (nm[1] < level - istop if side > 0 else nm[0] > level + istop):
                        why = "index_stop"
                    elif not timed and t >= t0 + TIME_STOP_MIN:
                        if ltp < e * (1 + TIME_STOP_GAIN) - 1e-9:
                            why = "time_stop"
                        else:
                            timed = True
                    if why is None:
                        if v.next_liq and target is not None and nm is not None and (nm[0] >= target if side > 0 else nm[1] <= target):
                            why = "next_liquidity"
                        elif fb is not None and t >= fb:
                            why = "failed_break"
                        elif nl is not None and t >= nl:
                            why = "new_liquidity"
                    if why is not None:
                        p, m = open_at_or_after(ser, t); x_px, x_why, x_min = sell_mkt(p), why, m; break
                    t += 1
                if x_px is None:
                    x_px, x_why, x_min = sell_mkt(cl[-1]), "last_bar", int(mins[-1])
                ch = round_trip(e, x_px, qty)
                trades.append((v.name, und, book, r["day"], s["sig_min"], t0, x_min, s["right"], s["strike"], qty, e, x_px, x_why,
                               (x_px - e) * qty, ch, (x_px - e) * qty - ch))
                flat_from = x_min + 1
    return pd.DataFrame(trades, columns=["v", "und", "book", "day", "signal", "entry_min", "exit_min", "right", "strike", "qty",
                                         "entry", "exit", "why", "gross", "charges", "net"])


# ---------------------------------------------------------------- statistics
def stats(t, days_total):
    t = t.sort_values(["day", "exit_min"])
    n = len(t)
    if n == 0:
        return {}
    net = t.net.values
    daily = t.groupby("day").net.sum()
    eq = daily.cumsum()
    dd = float((eq - np.maximum.accumulate(np.maximum(eq, 0))).min())
    month = t.groupby(pd.to_datetime(t.day).dt.to_period("M")).net.sum()
    streak = best = 0
    for x in net:
        streak = streak + 1 if x < 0 else 0
        best = max(best, streak)
    yr = t.groupby(pd.to_datetime(t.day).dt.year).net.sum()
    gp, gl = net[net > 0].sum(), -net[net < 0].sum()
    return dict(trades=n, win=(net > 0).mean(), avg=net.mean(), net=net.sum(), per_year=net.sum() / (days_total / 248.0),
                pf=gp / gl if gl > 0 else float("inf"), dd=dd, worst_month=float(month.min()), worst_month_name=str(month.idxmin()),
                streak=best, years_pos=int((yr > 0).sum()), years=len(yr), worst_trade=float(net.min()), charges=t.charges.sum())


def rs(x):
    return ("-" if x < 0 else "+") + f"{abs(x):,.0f}"


def main():
    recs = build("BANKNIFTY") + build("FINNIFTY")
    days_total = len({(r["day"]) for r in recs})
    all_days = sorted({r["day"] for r in recs})
    # trading days in the sample (real-data days, expiry skipped) for 'per year': use the calendar span of real days
    span_days = (all_days[-1] - all_days[0]).days / 365.25 * 248
    res = {v.name: run_variant(v, recs) for v in VARIANTS}
    T = pd.concat(res.values())
    T.to_csv(os.path.join(CACHE, "liqx_trades.csv"), index=False)
    T["year"] = pd.to_datetime(T.day).dt.year
    lines = []
    P = lines.append

    # ---- parity with the Kotlin replay
    parity = ""
    if PARITY and os.path.exists(PARITY):
        k = pd.read_csv(PARITY)
        k = k[k.variant == "wf_room1_itm1"]
        a = res["A"]
        parity = (f"Kotlin replay (wf_room1_itm1): {len(k)} trades, net Rs {k.net.sum():,.2f}. This port: {len(a)} trades, "
                  f"net Rs {a.net.sum():,.2f}.")
        ka = k.assign(key=k.book + "|" + k.day + "|" + k.entry_time)
        aa = a.assign(key=a.book + "|" + a.day.astype(str) + "|" + a.entry_min.map(lambda m: f"{m // 60:02d}:{m % 60:02d}"))
        mm = ka.merge(aa, on="key", how="outer", suffixes=("_k", "_p"), indicator=True)
        both = mm[mm._merge == "both"]
        same = (np.abs(both.net_k - both.net_p) < 0.02).sum()
        parity += (f" Matched by book/day/entry minute: {len(both)}; identical net (to the paisa): {same}; only in Kotlin: "
                   f"{(mm._merge == 'left_only').sum()}; only here: {(mm._merge == 'right_only').sum()}.")
        print(parity)

    S = {v.name: stats(res[v.name], span_days) for v in VARIANTS}
    years = sorted(T.year.unique())
    by_year = T.groupby(["v", "year"]).net.sum().unstack().reindex([v.name for v in VARIANTS])

    # ---- walk-forward: each year, pick the variant with the best net over all earlier years, trade it that year
    wf_rows = []
    test_years = [y for y in years if y >= years[0] + 2]
    for y in test_years:
        past = by_year[[c for c in years if c < y]].sum(axis=1)
        pick = past.idxmax()
        wf_rows.append((y, pick, past[pick], past["A"], by_year.loc[pick, y], by_year.loc["A", y]))
    wf_trades = pd.concat([res[p][pd.to_datetime(res[p].day).dt.year == y] for y, p, *_ in wf_rows])
    a_oos = res["A"][pd.to_datetime(res["A"].day).dt.year.isin(test_years)]
    oos_days = span_days * len(a_oos.day.unique()) / max(len(res["A"].day.unique()), 1)
    s_wf, s_a_oos = stats(wf_trades, oos_days), stats(a_oos, oos_days)
    # leave-one-family-out (is the WF just picking A?): the same with A excluded
    wf2 = []
    for y in test_years:
        past = by_year.drop(index="A")[[c for c in years if c < y]].sum(axis=1)
        p = past.idxmax(); wf2.append((y, p, by_year.loc[p, y], by_year.loc["A", y]))

    # ---- premiums: what 30 / 40 / 60 / 80 points are in % of what is bought
    a = res["A"].copy()
    a["year"] = pd.to_datetime(a.day).dt.year
    prem = a.groupby(["und", "year"]).entry.median().unstack()

    def survey(u, step_years):
        out = {}
        for y in step_years:
            vals = []
            for side, off in (("CALL", -1), ("PUT", 1)):
                f = os.path.join(OPTDIR, u, "WEEK", side, f"{y}.parquet")
                if not os.path.exists(f):
                    f = os.path.join(OPTDIR, u, "MONTH", side, f"{y}.parquet")
                if not os.path.exists(f):
                    continue
                d = pd.read_parquet(f, columns=["ts", "offset", "close"])
                d = d[(d.offset == off)]
                tm = d.ts.dt.hour * 60 + d.ts.dt.minute
                vals.append(d[tm == 11 * 60].close.values)
            if vals:
                out[y] = float(np.median(np.concatenate(vals)))
        return out

    surv = {u: survey(u, range(2021, 2027)) for u in ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")}

    # ---- write
    order = [v.name for v in VARIANTS]
    A = S["A"]
    beats = {}
    for n_ in order[1:]:
        yb = int((by_year.loc[n_] > by_year.loc["A"]).sum())
        beats[n_] = (yb, S[n_]["dd"] >= A["dd"])
    winners = [n_ for n_ in order[1:] if beats[n_][0] > len(years) / 2 and beats[n_][1] and S[n_]["net"] > A["net"]]
    wf_ok = s_wf["net"] > s_a_oos["net"] and s_wf["dd"] >= s_a_oos["dd"]
    verdict_keep = not (winners and wf_ok and any(p != "A" for _, p, *_ in wf_rows))

    P("## Liquidity 15+5: which exits? Current exits vs 30/60, 40/80, 40/40 and the profit-lock ladder (research/liquidity_exits_3060.py)")
    P("")
    P("### Verdict")
    P("")
    best_alt = max(order[1:], key=lambda n_: S[n_]["net"])
    if verdict_keep:
        P(f"**Keep the current exits.** On real Dhan option prices, Aug 2021 - 5 Oct 2026, after the app's charges and fills, "
          f"the arm's own exits make **Rs {rs(A['per_year'])} a year per lot** (Rs {rs(A['net'])} in all, {A['trades']} trades), "
          f"max drawdown **Rs {rs(A['dd'])}**, positive in **{A['years_pos']} of {A['years']} years**. "
          f"None of the {len(order) - 1} other exit sets beats it after costs in most years with a drawdown no worse, "
          f"and the year-by-year walk-forward {'kept picking the current exits' if all(p == 'A' for _, p, *_ in wf_rows) else 'did not beat it'}. "
          f"The best alternative, {best_alt}, made Rs {rs(S[best_alt]['per_year'])} a year (drawdown Rs {rs(S[best_alt]['dd'])}). "
          f"Walk-forward {test_years[0]}-{test_years[-1]} (pick the best on earlier years, trade it the next): Rs {rs(s_wf['net'])} "
          f"(drawdown Rs {rs(s_wf['dd'])}) against Rs {rs(s_a_oos['net'])} (drawdown Rs {rs(s_a_oos['dd'])}) for the current exits. "
          "Why: the arm earns on a minority of breaks that run on to the next liquidity level; a fixed premium target or a ladder "
          "sells those early, and a fixed point stop is much tighter than the -15% stop on BANKNIFTY and looser on FINNIFTY.")
    else:
        w = max(winners, key=lambda n_: S[n_]["net"])
        P(f"**Switch to {w}.** Rs {rs(S[w]['per_year'])} a year per lot vs Rs {rs(A['per_year'])}; drawdown Rs {rs(S[w]['dd'])} vs "
          f"Rs {rs(A['dd'])}; years positive {S[w]['years_pos']} of {S[w]['years']}.")
    P("")
    b3 = S["B3"]
    mb, mf = a[a.und == "BANKNIFTY"].entry.median(), a[a.und == "FINNIFTY"].entry.median()
    P(f"**Is 30/60 workable for Liquidity?** {'No' if S['B1']['net'] < A['net'] and S['B2']['net'] < A['net'] and b3['net'] < A['net'] else 'Partly'}: "
      f"with the 30-point stop and +60 target (and its ladder) in place of the -15% stop and the next-level target, the arm makes "
      f"Rs {rs(S['B1']['per_year'])} a year keeping the index and time stops (B1), Rs {rs(S['B2']['per_year'])} dropping them (B2) and "
      f"Rs {rs(b3['per_year'])} as Jarvis's bare rule (B3), against Rs {rs(A['per_year'])} now. "
      f"Points are the wrong unit for this arm: 30 points is about {30 / mb * 100:.0f}% of the BANKNIFTY option it buys "
      f"but about {30 / mf * 100:.0f}% of the FINNIFTY one (median entry premiums Rs {mb:.0f} / Rs {mf:.0f}; the -15% stop is about "
      f"{0.15 * mb:.0f} / {0.15 * mf:.0f} points), so the same rule is half the arm's stop on BANKNIFTY and wider than it on FINNIFTY; "
      "on NIFTY (one-ITM weekly about Rs 125-145) 30 / 60 points would be about 20% / 40% of the premium.")
    P("")
    P(f"Tried: {len(order)} exit sets in all (A + {len(order) - 1} alternatives: B1-B3, C1-C3, D1-D3, E on 60/40/80), fixed before any "
      "result was read; no parameter was tuned. With that many tries, one alternative beating A in a single year means little.")
    P("")
    if parity:
        P(f"Reproduces the arm's known backtest: {parity}")
        P("")
    P("### Set-up")
    P("")
    P("- Entries: the app's Liquidity 15+5 as it runs now (commit 1bd37ad): BANKNIFTY 15-min + 5-min, FINNIFTY 30-min + 5-min, one "
      "position per book, entries 09:20-14:00, room filter (>= 1 index stop to the next level), one strike in the money, expiry days "
      "skipped, days with a real index OHLC only (as the liq / liq2 replays). 1 lot of the day's size (BANKNIFTY 25/15/30, FINNIFTY 40/25/65 over the years).")
    P("- Prices: real option minute bars; MARKET fills +-5 bps on the price, resting stops -10 bps (the app's paper account); "
      "the app's F&O charges per leg (SandboxCosts).")
    P("- Within a minute: premium stop first, then the profit lock, then the premium target (fills: stop at the trigger or the "
      "open if it gapped; lock at the rung or the open, MARKET; target at entry + target or the open, MARKET); then 15:10, the "
      "index stop, the 20-min time stop, the next-level target, the failed break and the new level (MARKET at the next minute's open).")
    P("- Ladder (ProfitLock.LADDER on a reference R): from +R/4 the stop moves to breakeven after charges, from +R/2 to +R/4, "
      "from +3R/4 to +R/2. For R = 60: +15 -> breakeven, +30 -> lock +15, +45 -> lock +30 (JarvisTrades). R = 40 is the "
      "retired ORB arms' (+10 / +20 / +30). A point stop that would sit at or below 0.05 is not placed (cheap options).")
    P("- 'Keep index + time stop' (x1) keeps the arm's 30/15-point index stop and the 20-min +5% time stop; 'drop' (x2) removes "
      "them; both keep the failed break, the new level and 15:10. x3 is the stop / target / ladder alone with 15:10 (how Jarvis's "
      "own trades and the retired ORB arms exit).")
    P("")
    P(f"### All variants, Aug 2021 - Oct 2026 ({len(all_days)} trading days with a signal-ready real index; per year = net / {span_days / 248:.2f} years)")
    P("")
    P("| variant | trades | win | avg Rs / trade | net Rs / year | net total | PF | max DD | worst month | longest losing run | years + | years better than A | BANKNIFTY | FINNIFTY |")
    P("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for v in VARIANTS:
        s = S[v.name]
        t = res[v.name]
        bn, fn = t[t.und == "BANKNIFTY"].net.sum(), t[t.und == "FINNIFTY"].net.sum()
        better = "-" if v.name == "A" else f"{beats[v.name][0]} of {len(years)}"
        P(f"| {v.label} | {s['trades']} | {s['win'] * 100:.0f}% | {rs(s['avg'])} | **{rs(s['per_year'])}** | {rs(s['net'])} | {s['pf']:.2f} | "
          f"{rs(s['dd'])} | {rs(s['worst_month'])} ({s['worst_month_name']}) | {s['streak']} | {s['years_pos']}/{s['years']} | {better} | {rs(bn)} | {rs(fn)} |")
    P("")
    P("### Net by year (Rs, 1 lot, after costs)")
    P("")
    P("| variant | " + " | ".join(str(y) + (" (Aug-Dec)" if y == years[0] else " (to 5 Oct)" if y == years[-1] else "") for y in years) + " |")
    P("|---|" + "---|" * len(years))
    for v in VARIANTS:
        P(f"| {v.name} | " + " | ".join(("**" if v.name != "A" and by_year.loc[v.name, y] > by_year.loc["A", y] else "") + rs(by_year.loc[v.name, y]) +
                                       ("**" if v.name != "A" and by_year.loc[v.name, y] > by_year.loc["A", y] else "") for y in years) + " |")
    P("")
    P("Bold: better than A that year.")
    P("")
    P("### Per book (net Rs, all years)")
    P("")
    books = ["liquidity15", "liquidity5", "liquidity30_fin", "liquidity5_fin"]
    P("| variant | BN 15-min | BN 5-min | FIN 30-min | FIN 5-min |")
    P("|---|---|---|---|---|")
    for v in VARIANTS:
        t = res[v.name]
        P(f"| {v.name} | " + " | ".join(rs(t[t.book == b].net.sum()) for b in books) + " |")
    P("")
    P("### How the trades end (share of trades)")
    P("")
    P("| variant | exits |")
    P("|---|---|")
    for v in VARIANTS:
        vc = res[v.name].why.value_counts(normalize=True)
        P(f"| {v.name} | " + ", ".join(f"{k} {x * 100:.0f}%" for k, x in vc.items()) + " |")
    P("")
    P("### Walk-forward (anchored, yearly)")
    P("")
    P("Each year, the variant with the highest net over ALL earlier years (from Aug 2021) is traded in that year; the first two "
      "calendar years only train.")
    P("")
    P("| test year | picked | its net on earlier years | A on earlier years | picked, test year | A, test year |")
    P("|---|---|---|---|---|---|")
    for y, p, tp, ta, np_, na in wf_rows:
        P(f"| {y} | {p} | {rs(tp)} | {rs(ta)} | {rs(np_)} | {rs(na)} |")
    P("")
    P(f"Walk-forward total over {test_years[0]}-{test_years[-1]}: Rs {rs(s_wf['net'])} (max DD Rs {rs(s_wf['dd'])}, PF {s_wf['pf']:.2f}) "
      f"vs A Rs {rs(s_a_oos['net'])} (max DD Rs {rs(s_a_oos['dd'])}, PF {s_a_oos['pf']:.2f}).")
    P("")
    P("With A taken off the menu (does the best alternative, chosen on the past, hold up?): " +
      "; ".join(f"{y}: {p} {rs(npk)} vs A {rs(na)}" for y, p, npk, na in wf2) +
      f" -> total {rs(sum(x[2] for x in wf2))} vs A {rs(sum(x[3] for x in wf2))}.")
    P("")
    P("### Windows the arm's rules were not tuned on")
    P("")
    P("The arm's current exits were chosen on 13 Feb 2024 - 23 Feb 2026 (research/LIQUIDITY_*.md), which flatters A inside that "
      "window. Outside it:")
    P("")
    P("| variant | before 13 Feb 2024: trades | net | after 23 Feb 2026: trades | net |")
    P("|---|---|---|---|---|")
    for v in VARIANTS:
        t = res[v.name]
        d = t.day.astype(str)
        pre, post = t[d < "2024-02-13"], t[d > "2026-02-23"]
        P(f"| {v.name} | {len(pre)} | {rs(pre.net.sum())} | {len(post)} | {rs(post.net.sum())} |")
    P("")
    P("### What the point rules mean in % of the premium")
    P("")
    P("Median price paid by the arm (one strike ITM, at its entries):")
    P("")
    P("| index | " + " | ".join(str(y) for y in prem.columns) + " | all | 30 pts | 40 pts | 60 pts | 80 pts | -15% stop in points |")
    P("|---|" + "---|" * (len(prem.columns) + 6))
    for u in prem.index:
        md = a[a.und == u].entry.median()
        P(f"| {u} | " + " | ".join(f"Rs {prem.loc[u, y]:.0f}" if not np.isnan(prem.loc[u, y]) else "-" for y in prem.columns) +
          f" | Rs {md:.0f} | {30 / md * 100:.0f}% | {40 / md * 100:.0f}% | {60 / md * 100:.0f}% | {80 / md * 100:.0f}% | {0.15 * md:.0f} pts |")
    P("")
    P("For comparison, the median one-strike-ITM weekly (else monthly) option at 11:00 on Dhan's data (NIFTY and SENSEX are not "
      "traded by this arm):")
    P("")
    ys = sorted({y for u in surv for y in surv[u]})
    P("| index | " + " | ".join(str(y) for y in ys) + " | 30 pts on the latest | 60 pts on the latest |")
    P("|---|" + "---|" * (len(ys) + 2))
    for u, sv in surv.items():
        last = sv[max(sv)] if sv else float("nan")
        P(f"| {u} | " + " | ".join(f"Rs {sv[y]:.0f}" if y in sv else "-" for y in ys) + f" | {30 / last * 100:.0f}% | {60 / last * 100:.0f}% |")
    P("")
    P("### Reading it")
    P("")
    P(f"- A (the arm now): Rs {rs(A['net'])} over the sample, Rs {rs(A['per_year'])} a year, PF {A['pf']:.2f}, max DD Rs {rs(A['dd'])}, "
      f"worst month Rs {rs(A['worst_month'])}, {A['years_pos']}/{A['years']} years positive.")
    for fam, names in (("B (30/60)", ["B1", "B2", "B3"]), ("C (40/80)", ["C1", "C2", "C3"]), ("D (40/40 ORB)", ["D1", "D2", "D3"]),
                       ("E (ladder on top)", ["E60", "E40", "E80"])):
        P(f"- {fam}: " + "; ".join(f"{n_} Rs {rs(S[n_]['net'])} (DD {rs(S[n_]['dd'])}, better than A in {beats[n_][0]}/{len(years)} years)" for n_ in names) + ".")
    with open(OUT, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
