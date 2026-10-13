"""R19 (PREREG.md): Liquidity 15+5 with and without a profit lock, on real option minutes. Pure: no files, no network.

Grid: row r is the 1-minute bar labelled 09:15 + r (375 rows, 09:15 .. 15:29); everything in row r is known when that
minute closes. A decision on row r's close fills at the next printed open (row r + 1 on), as research/obuy/engine.py.

  signals(days, tf, ...)   the arm's entries for one chart (port of research/hunt/h4/comps.py liq_ext_signals, which is
                           signal-for-signal equal to the validated port on BANKNIFTY)
  run_trade(...)           one position's exits under one lock variant (control = no lock), as obuy run_exits
  Lock / VARIANTS          the pre-registered locks a1, a2, b1, b2, c1, c2 (and d, the control)
"""
from __future__ import annotations

import math
import os
import sys
from dataclasses import dataclass
from datetime import timedelta

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.dirname(os.path.dirname(HERE))
for p in (RES, os.path.dirname(RES)):
    if p not in sys.path:
        sys.path.insert(0, p)

from obuy.costs import Costs, Fills, floor_tick  # noqa: E402

N = 375
OPEN_M = 9 * 60 + 15
TICK = 0.05
SQ_ROW = 15 * 60 + 10 - OPEN_M          # 355: the 15:10 open is the square-off fill
WIN_FROM, WIN_TO = 9 * 60 + 20, 14 * 60  # a deciding bar must END in 09:20 .. 14:00
L, CONTACTS, GAP, CONFIRM, MAX_AGE = 20, 2, 5, 10, 300
PREMIUM_STOP = 0.15
TIME_STOP_MIN, TIME_STOP_GAIN = 20, 0.05
IDX_STOP = {"BANKNIFTY": 30.0, "MIDCPNIFTY": 8.0, "FINNIFTY": 15.0}
STEP = {"BANKNIFTY": 100, "MIDCPNIFTY": 25, "FINNIFTY": 50}
EPS = 1e-9

FILLS = Fills(mode="liq")
COSTS = Costs(mode="app")


# ------------------------------------------------------------------------------------------------ levels (h4 port)
class Zone:
    __slots__ = ("kind", "side", "top", "bottom", "origin", "known", "broken")

    def __init__(self, kind, side, top, bottom, origin, known):
        self.kind, self.side, self.top, self.bottom, self.origin, self.known, self.broken = kind, side, top, bottom, origin, known, -1

    @property
    def edge(self):
        return self.top if self.side > 0 else self.bottom


def mark_breaks(zones, Cl):
    for z in zones:
        seg = Cl[z.known:]
        hit = (seg > z.top) if z.side > 0 else (seg < z.bottom)
        z.broken = int(z.known + np.argmax(hit)) if hit.any() else -1


def swing_zones(H, Lo, Cl):
    n = len(H)
    out = []
    for i in range(2 * L, n):
        j = i - L
        lo_, hi_ = j - L, j + L + 1
        h, l_ = H[j], Lo[j]
        if H[lo_:j].max() < h and H[j + 1:hi_].max() < h:
            out.append(Zone("swing", 1, h, l_, j, i))
        if Lo[lo_:j].min() > l_ and Lo[j + 1:hi_].min() > l_:
            out.append(Zone("swing", -1, h, l_, j, i))
    mark_breaks(out, Cl)
    return out


def pool_zones(O, H, Lo, Cl):
    out = []
    cand = []
    for i in range(len(Cl)):
        o, h, lo, c = O[i], H[i], Lo[i], Cl[i]
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
    mark_breaks(out, Cl)
    return out


def fold(ones, tf):
    """(day, minute, o, h, l, c) 1-minute bars -> tf-minute bars from 09:15 each day, labelled by their start."""
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


def atm(spot, step):
    return int(math.floor(spot / step + 0.5) * step)


def day_signals(hist, d, today1, tf, und, room=1.0):
    """The chart's entries on day d. hist: [(day, [1-minute bars])] of the earlier days (last 10 calendar days are
    used); today1: today's 1-minute bars (day, minute, o, h, l, c) for minutes 09:15 .. 15:29."""
    step, istop = STEP[und], IDX_STOP[und]
    first = {}
    for b in [b for hd, hb in hist if hd >= d - timedelta(days=10) for b in hb] + today1:
        first.setdefault((b[0], b[1]), b)
    ones = sorted(first.values(), key=lambda b: (b[0], b[1]))
    bars = fold(ones, tf)
    rows = []
    if len(bars) < 2 * L + 2:
        return rows
    O = np.array([b[2] for b in bars])
    H = np.array([b[3] for b in bars])
    Lo = np.array([b[4] for b in bars])
    Cl = np.array([b[5] for b in bars])
    sw_, po = swing_zones(H, Lo, Cl), pool_zones(O, H, Lo, Cl)
    zones = sw_ + po
    n = len(bars)
    is_today = [b[0] == d for b in bars]
    bar_end = [b[1] + tf if is_today[k] else -1 for k, b in enumerate(bars)]
    by_break = {}
    for p in po:
        if p.broken >= 0:
            by_break.setdefault(p.broken, []).append(p)
    for i in range(n):
        if not is_today[i] or bars[i][1] + tf > 930 or i not in by_break:
            continue
        pool = None
        for p in by_break[i]:
            if p.known > i:
                continue
            if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                   and (s.broken < 0 or s.broken >= i) for s in sw_):
                pool = p
                break
        if pool is None:
            continue
        side, close = pool.side, Cl[i]
        ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i)
                 and side * (q.edge - close) > 0]
        target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
        level = pool.edge
        fb = next((bar_end[k] for k in range(i + 1, n) if side * (Cl[k] - level) < 0), None)
        nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= i + 1]
        nl = min(nl) if nl else None
        done = bar_end[i]
        if done > 930 or done < WIN_FROM or done > WIN_TO:
            continue
        if target is not None and side * (target - close) < room * istop:
            continue
        xa = [v for v in (fb, nl) if v is not None]
        rows.append(dict(und=und, day=d, book=f"{tf}m", sig_row=done - 1 - OPEN_M, gate_row=done - OPEN_M, side=side,
                         ref=float(close), strike=atm(close, step) - side * step, level=float(level),
                         idx_stop=float(level - side * istop), target=None if target is None else float(target),
                         exit_row=(min(xa) - 1 - OPEN_M) if xa else None))
    return rows


# ------------------------------------------------------------------------------------------------ the locks
@dataclass(frozen=True)
class Lock:
    """One pre-registered lock. kind: 'none' | 'index' (a) | 'be' (b) | 'giveback' (c)."""
    id: str
    kind: str
    keep: float = 0.0       # index ladder: share of the premium gained locked at 75% of the way; give-back: share kept
    start: float = 0.0      # be / give-back: the option's gain (share of the fill) that arms it

    def level(self, e, be, peak, progress):
        """The lock earned by the best option price [peak] and the best index progress to the target [progress]
        (None: no target) seen BEFORE the bar; None below the first rung. Never under breakeven [be], and only a level
        below the best price counts (else it would sell at once)."""
        lv = None
        if self.kind == "index":
            if progress is None:
                return None
            if progress >= 0.75 - EPS:
                lv = max(be, e + self.keep * (peak - e))
            elif progress >= 0.50 - EPS:
                lv = be
        elif self.kind == "be":
            if peak >= e * (1 + self.start) - EPS:
                lv = be
        elif self.kind == "giveback":
            if peak >= e * (1 + self.start) - EPS:
                lv = max(be, e + self.keep * (peak - e))
        if lv is None or not lv < peak - EPS:
            return None
        return lv


VARIANTS = [
    Lock("d", "none"),
    Lock("a1", "index", keep=0.25),
    Lock("a2", "index", keep=0.50),
    Lock("b1", "be", start=0.10),
    Lock("b2", "be", start=0.20),
    Lock("c1", "giveback", keep=0.50, start=0.10),
    Lock("c2", "giveback", keep=0.50, start=0.20),
]
BY_ID = {v.id: v for v in VARIANTS}


def progress_to(side, ref, target, best_idx):
    """Share of the way from the deciding close [ref] to the [target] level the index has gone at best (None: no
    target, or a target not ahead of ref)."""
    if target is None or best_idx is None:
        return None
    span = side * (target - ref)
    if not span > 0:
        return None
    return side * (best_idx - ref) / span


# ------------------------------------------------------------------------------------------------ one trade
def on_tick(x):
    return round(math.ceil(x / TICK - 1e-6) * TICK, 2)


def vol5_lots(opt, row, lot):
    v = opt[max(0, row - 5):row, 4]
    v = v[np.isfinite(v)]
    return float(v.sum() / lot) if lot else 0.0


def entry(opt, gate_row, lot, max_delay=2):
    """(c0, fill) at the first printed open in gate_row .. gate_row + max_delay, or None."""
    for r in range(gate_row, min(gate_row + max_delay + 1, N)):
        if np.isfinite(opt[r, 0]):
            e = float(FILLS.buy(np.array([round(float(opt[r, 0]), 2)]), np.array([vol5_lots(opt, r, lot)]))[0])
            return r, e
    return None


def be_level(e, qty):
    return e + max(float(COSTS.rt_per_unit(np.array([e]), np.array([float(qty)]))[0]), 0.0)


def run_trade(opt, ix, sig, c0, e, qty, lot, lock: Lock, und="BANKNIFTY", force_exit_row=None):
    """One position's exits (obuy run_exits order). opt: (375, 5) O H L C V of the contract (NaN: no print); ix: (375, 4)
    index OHLC; sig: the signal dict. force_exit_row: a market exit decided on that row's close (the random twins).
    Returns dict(row, raw, fill, why, stop, peak, gross, charges, net)."""
    side, ref, target = sig["side"], sig["ref"], sig["target"]
    trig = float(floor_tick(e * (1 - PREMIUM_STOP)))
    trig = trig if (TICK <= trig < e) else -np.inf
    be = be_level(e, qty)
    kT = c0 + TIME_STOP_MIN - 1
    xr = sig["exit_row"]
    peak = e
    best_idx = None
    last_close = np.nan
    why = None
    km = None
    for r in range(c0, SQ_ROW):
        o, h, lo, c = opt[r, 0], opt[r, 1], opt[r, 2], opt[r, 3]
        valid = np.isfinite(c)
        # resting stop / lock on this bar, from what was known before it
        if valid:
            lk = lock.level(e, be, peak, progress_to(side, ref, target, best_idx)) if lock.kind != "none" else None
            lk = None if lk is None else on_tick(lk)          # the trigger on the tick, rounded UP (ProfitLock.onTick)
            rest = trig if lk is None else max(trig, lk)
            if lo <= rest + EPS:
                is_stop = lk is None or trig >= lk
                raw = min(rest, o) if np.isfinite(o) else rest
                return _close(opt, r, raw, "stop" if is_stop else "lock", True, e, qty, lot, peak, h)
            last_close = c
            peak = max(peak, h)                       # counts for the lock from the next bar on
        b = ix[r, 1] if side > 0 else ix[r, 2]
        if np.isfinite(b):
            best_idx = b if best_idx is None else (max(best_idx, b) if side > 0 else min(best_idx, b))
        # minute-close decisions, filled at the next open
        if r <= SQ_ROW - 2:
            ihi, ilo = ix[r, 1], ix[r, 2]
            if (side > 0 and ilo < sig["idx_stop"]) or (side < 0 and ihi > sig["idx_stop"]):
                why = "index_stop"
            elif r == kT and np.isfinite(last_close) and last_close < e * (1 + TIME_STOP_GAIN) - EPS:
                why = "time_stop"
            elif target is not None and ((side > 0 and ihi >= target) or (side < 0 and ilo <= target)):
                why = "target"
            elif xr is not None and r >= max(xr, c0):
                why = "failed_or_new"
            elif force_exit_row is not None and r >= force_exit_row:
                why = "twin"
        elif r == SQ_ROW - 1:
            why = "15:10"
        if why:
            km = r
            break
    if km is None:
        km, why = SQ_ROW - 1, "15:10"
    for r in range(km + 1, N):
        if np.isfinite(opt[r, 0]):
            return _close(opt, r, float(opt[r, 0]), why, False, e, qty, lot, peak, None)
    fin = np.nonzero(np.isfinite(opt[:, 3]))[0]
    r = int(fin[-1])
    return _close(opt, r, float(opt[r, 3]), why, False, e, qty, lot, peak, None)


def _close(opt, r, raw, why, stop, e, qty, lot, peak, bar_high):
    x = float(FILLS.sell(np.array([raw]), np.array([vol5_lots(opt, r, lot)]), stop=stop)[0])
    gross = (x - e) * qty
    ch = float(COSTS.charge(True, np.array([e]), np.array([qty]))[0] + COSTS.charge(False, np.array([x]), np.array([qty]))[0])
    pk = peak if bar_high is None or not np.isfinite(bar_high) else max(peak, bar_high)
    return dict(row=r, raw=raw, fill=x, why=why, stop=stop, peak=pk, gross=gross, charges=ch, net=gross - ch,
                tick_out=ticks_for(vol5_lots(opt, r, lot)))


def wide_spread_net(t, mult=1.5):
    """A trade's net with the modelled half-spread (the tick part of the liq fills) times [mult]: the extra ticks on
    both fills, x2 on a stop fill, as Fills('liq')."""
    extra = (mult - 1) * TICK * (t["tick_in"] + t["tick_out"] * (2 if t["stop"] else 1)) * t["qty"]
    return t["net"] - extra


def ticks_for(vol5):
    return 1.0 if vol5 >= FILLS.thin_lots else (2.0 if vol5 > 0 else 4.0)
