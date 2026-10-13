"""R19b simulator: Liquidity 15+5 (engine LiquidityRules) with Boss's rupee give-back trail. Pure: no I/O.

A port of research/liquidity_exits_3060.py (itself a line-by-line port of the Kotlin replay, trade-for-trade equal to
the app) with one change: a trail checked after the -15% premium stop and before the peak update (PREREG.md). Fills here
add a half-spread (1 tick on market orders, 2 on resting stops) on top of the app's paper fills, as pre-registered.
"""
from __future__ import annotations

import math
from dataclasses import dataclass
from decimal import ROUND_HALF_EVEN, Decimal, getcontext

import numpy as np

getcontext().prec = 120

# ---------------------------------------------------------------- LiquidityRules (engine/orb/LiquidityRules.kt)
L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300
OPEN_M = 9 * 60 + 15
WIN_FROM, WIN_TO = 9 * 60 + 20, 14 * 60
SQUARE_OFF = 15 * 60 + 10
TICK = 0.05
BOOKS = [("liquidity15", 15), ("liquidity5", 5)]
STEP = 100
IDX_STOP = 30.0
MIN_ROOM, ITM_STEPS = 1.0, 1
PREM_STOP, TIME_STOP_MIN, TIME_STOP_GAIN = 0.15, 20, 0.05
EPS = 1e-9


def fold(ones, tf):
    """ones: sorted (day, minute, o, h, l, c) -> tf-minute bars labelled by start (day, startMinute, o, h, l, c)."""
    out = []
    cur = None
    for d, m, o, h, lo, c in ones:
        k = (d, OPEN_M + (max(m - OPEN_M, 0) // tf) * tf)
        if cur is None or cur[0] != k:
            if cur is not None:
                out.append((cur[0][0], cur[0][1], cur[1], cur[2], cur[3], cur[4]))
            cur = [k, o, h, lo, c]
        else:
            cur[2] = max(cur[2], h)
            cur[3] = min(cur[3], lo)
            cur[4] = c
    if cur is not None:
        out.append((cur[0][0], cur[0][1], cur[1], cur[2], cur[3], cur[4]))
    return out


class Zone:
    __slots__ = ("kind", "side", "top", "bottom", "origin", "known", "broken")

    def __init__(self, kind, side, top, bottom, origin, known):
        self.kind, self.side, self.top, self.bottom = kind, side, top, bottom
        self.origin, self.known, self.broken = origin, known, -1

    @property
    def edge(self):
        return self.top if self.side > 0 else self.bottom


def mark_breaks(zones, C):
    for z in zones:
        seg = C[z.known:]
        hit = (seg > z.top) if z.side > 0 else (seg < z.bottom)
        z.broken = int(z.known + np.argmax(hit)) if hit.any() else -1


def swing_zones(H, Lo, C):
    out = []
    n = len(H)
    for i in range(2 * L, n):
        j = i - L
        h, lo = H[j], Lo[j]
        lw, rw = slice(j - L, j), slice(j + 1, j + L + 1)
        if H[lw].max() < h and H[rw].max() < h:
            out.append(Zone("swing", 1, h, lo, j, i))
        if Lo[lw].min() > lo and Lo[rw].min() > lo:
            out.append(Zone("swing", -1, h, lo, j, i))
    mark_breaks(out, C)
    return out


def pool_zones(O, H, Lo, C):
    out = []
    cand = []
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
                z[4] += 1
                z[5] = i
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


def signals(ones, day, tf):
    """The book's entries on `day` from the 1-minute index bars `ones` (the last 10 calendar days, sorted): one dict a
    pool-on-swing break with its level, target, close and the structural exits (first failed-break / new-level bar end)."""
    bars = fold(ones, tf)
    if len(bars) < 2 * L + 2:
        return []
    O = np.array([b[2] for b in bars]); H = np.array([b[3] for b in bars])
    Lo = np.array([b[4] for b in bars]); C = np.array([b[5] for b in bars])
    sw, po = swing_zones(H, Lo, C), pool_zones(O, H, Lo, C)
    zones = sw + po
    n = len(bars)
    today = [b[0] == day for b in bars]
    bar_end = [b[1] + tf if today[k] else -1 for k, b in enumerate(bars)]
    by_break = {}
    for p in po:
        if p.broken >= 0:
            by_break.setdefault(p.broken, []).append(p)
    out = []
    for i in range(n):
        if not today[i] or bars[i][1] + tf > 930 or i not in by_break:
            continue
        pool = None
        for p in by_break[i]:
            if p.known > i:
                continue
            if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i and (s.broken < 0 or s.broken >= i)
                   for s in sw):
                pool = p
                break
        if pool is None:
            continue
        side, close = pool.side, C[i]
        ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i) and side * (q.edge - close) > 0]
        target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
        level = pool.edge
        fb = next((bar_end[k] for k in range(i + 1, n) if side * (C[k] - level) < 0), None)
        nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= i + 1]
        nl = min(nl) if nl else None
        strike = atm(close, STEP) - side * ITM_STEPS * STEP
        out.append(dict(sig_min=bars[i][1], done=bar_end[i], side=side, level=level, target=target, close=close,
                        fb=fb, nl=nl, strike=strike, right="C" if side > 0 else "P"))
    return out


# ---------------------------------------------------------------- app fills and charges (SandboxCosts, Px) + half-spread
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


def buy_fill(p):
    """Entry: the app's MARKET buy (+5 bps) plus a 1-tick half-spread."""
    return round(adverse(p, True, 5) + TICK, 2)


def sell_fill(p):
    """Structural exits: the app's MARKET sell (-5 bps) less a 1-tick half-spread (never under a tick)."""
    return max(round(adverse(p, False, 5) - TICK, 2), TICK)


def stop_fill(p):
    """Resting stops (the -15% stop and the trail): the app's SL-M fill (-10 bps) less a 2-tick half-spread."""
    return max(round(adverse(p, False, 10) - 2 * TICK, 2), TICK)


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


def round_trip(e, x, qty):
    return charge(True, e, qty) + charge(False, x, qty)


def pct_trigger(e, pct):
    """LiquidityRules.stopTrigger: pct below the fill, floored to the tick; None with no room."""
    t = math.floor(e * (1 - pct) / TICK + 1e-9) * TICK
    r = round(t * 100) / 100.0
    return r if TICK <= r < e else None


def on_tick_up(level):
    """ProfitLock.onTick: a sell stop's trigger rounded UP to the tick."""
    return round(math.ceil(level / TICK - 1e-6) * TICK, 2)


# ---------------------------------------------------------------- the trail (PREREG.md)
@dataclass(frozen=True)
class Trail:
    """kind 'rs': give back `g` rupees per lot (g / lot points); kind 'pct': give back g x the entry fill.
    from_entry: active from the entry (A2); else only once the peak is g above the entry (A1, B*, P3)."""
    name: str
    kind: str = "rs"
    g: float = 500.0
    from_entry: bool = False

    def points(self, entry, lot):
        return self.g / lot if self.kind == "rs" else self.g * entry


def trail_trigger(t: Trail | None, entry, peak, lot):
    """The trail's sell-stop trigger for a buy at `entry` whose best price so far is `peak` (1 lot of `lot`), or None
    while it is not active. On the tick, rounded up (ProfitLock.onTick). Never above the peak."""
    if t is None or not (entry > 0) or not (lot > 0) or peak is None or not np.isfinite(peak):
        return None
    pts = t.points(entry, lot)
    if not (pts > 0):
        return None
    if not t.from_entry and peak - entry < pts - EPS:
        return None
    trig = on_tick_up(peak - pts)
    if trig > peak + EPS:
        trig = peak
    return trig if trig >= TICK else None


VARIANTS = [
    None,                                            # CTRL
    Trail("A1", "rs", 500.0, False),
    Trail("A2", "rs", 500.0, True),
    Trail("B300", "rs", 300.0, False),
    Trail("B750", "rs", 750.0, False),
    Trail("B1000", "rs", 1000.0, False),
    Trail("P3", "pct", 0.03, False),
]


def vname(t):
    return "CTRL" if t is None else t.name


# ---------------------------------------------------------------- the minute walk
def last_at_or_before(mins, m):
    return int(np.searchsorted(mins, m, side="right")) - 1


def open_at_or_after(ser, m):
    mins, op, _, _, cl = ser
    i = last_at_or_before(mins, m - 1) + 1
    return (op[i], int(mins[i])) if i < len(mins) else (cl[-1], int(mins[-1]))


def walk(s, ser, imin, lot, trail: Trail | None, trail_fill="stop", cut_at=None):
    """One trade from signal `s` on option series `ser` (mins, o, h, l, c of traded minutes) with index minutes `imin`
    {minute: (high, low)}. Returns a dict, or None when the entry cannot fill. cut_at: a random-twin exit minute (sold at
    that minute's next open with a market fill, if no other exit came first)."""
    mins, op, hi, lo, cl = ser
    done = s["done"]
    side, level, target = s["side"], s["level"], s["target"]
    ei = last_at_or_before(mins, done - 1) + 1
    if ei >= len(mins) or mins[ei] > done + 2:
        return None
    t0 = int(mins[ei])
    e = buy_fill(op[ei])
    trig = pct_trigger(e, PREM_STOP)
    peak = e
    mfe_hi = e
    timed = False
    fb, nl = s["fb"], s["nl"]
    x_px = x_why = x_min = None
    t = t0 + 1
    while t <= 930:
        j = last_at_or_before(mins, t - 1)
        if j >= ei and mins[j] == t - 1:
            if trig is not None and lo[j] <= trig:
                x_px, x_why, x_min = stop_fill(min(trig, op[j])), "premium_stop", t - 1
                break
            tt = trail_trigger(trail, e, peak, lot)
            if tt is not None and (trig is None or tt > trig) and lo[j] <= tt:
                if trail_fill == "next":
                    p, m = open_at_or_after(ser, t)
                    x_px, x_why, x_min = sell_fill(p), "trail", m
                else:
                    x_px, x_why, x_min = stop_fill(min(tt, op[j])), "trail", t - 1
                break
            peak = max(peak, hi[j])
            mfe_hi = max(mfe_hi, hi[j])
        if t >= SQUARE_OFF:
            p, m = open_at_or_after(ser, t)
            x_px, x_why, x_min = sell_fill(p), "session_end", m
            break
        nm = imin.get(t - 1)
        ltp = cl[j] if j >= 0 else e
        why = None
        if nm is not None and (nm[1] < level - IDX_STOP if side > 0 else nm[0] > level + IDX_STOP):
            why = "index_stop"
        elif not timed and t >= t0 + TIME_STOP_MIN:
            if ltp < e * (1 + TIME_STOP_GAIN) - 1e-9:
                why = "time_stop"
            else:
                timed = True
        if why is None:
            if target is not None and nm is not None and (nm[0] >= target if side > 0 else nm[1] <= target):
                why = "next_liquidity"
            elif fb is not None and t >= fb:
                why = "failed_break"
            elif nl is not None and t >= nl:
                why = "new_liquidity"
            elif cut_at is not None and t >= cut_at:
                why = "twin_cut"
        if why is not None:
            p, m = open_at_or_after(ser, t)
            x_px, x_why, x_min = sell_fill(p), why, m
            break
        t += 1
    if x_px is None:
        x_px, x_why, x_min = sell_fill(cl[-1]), "last_bar", int(mins[-1])
    ch = round_trip(e, x_px, lot)
    return dict(entry_min=t0, exit_min=x_min, entry=e, exit=x_px, why=x_why, qty=lot, gross=(x_px - e) * lot,
                charges=ch, net=(x_px - e) * lot - ch, mfe=(mfe_hi - e) * lot)


def run_book(recs, book, trail, trail_fill="stop", only=None, cuts=None):
    """Every entry of `book` over `recs` (one dict a session: day, lot, imin, books {book: [signal]}, opts). One position
    at a time; a book is flat the minute after its exit. only: a set of (day, entry minute) to take exactly (each held on
    its own, for fixed-entry comparisons). cuts: {(day, entry minute): twin cut minute}."""
    out = []
    for r in recs:
        sigs = r["books"].get(book, [])
        flat_from = 0
        for s in sigs:
            if only is None and s["done"] < flat_from:
                continue
            done = s["done"]
            if done > 930 or done < WIN_FROM or done > WIN_TO:
                continue
            if s["target"] is not None and s["side"] * (s["target"] - s["close"]) < MIN_ROOM * IDX_STOP:
                continue
            ser = r["opts"].get((s["strike"], s["right"]))
            if ser is None or len(ser[0]) == 0:
                continue
            mins = ser[0]
            ei = last_at_or_before(mins, done - 1) + 1
            if ei >= len(mins) or mins[ei] > done + 2:
                continue
            key = (r["day"], int(mins[ei]))
            if only is not None and key not in only:
                continue
            tr = walk(s, ser, r["imin"], r["lot"], trail, trail_fill, None if cuts is None else cuts.get(key))
            if tr is None:
                continue
            tr.update(book=book, day=r["day"], signal=s["sig_min"], side=s["side"], strike=s["strike"])
            out.append(tr)
            flat_from = tr["exit_min"] + 1
    return out
