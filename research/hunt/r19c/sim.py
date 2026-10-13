"""R19c simulator: every arm with no profit lock (plus the laddered ORB family) with Boss's rupee profit floor. Pure: no I/O.

The floor (PREREG.md): once the option's best 1-minute high since the entry is A rupees a lot above the entry fill, a
resting sell stop sits G rupees a lot below that best (G / lot points, on the tick rounded up), raised only. It only ADDS
an earlier exit; each arm's own exits are unchanged.

Reused, not re-written: Liquidity 15+5 is R19b's engine-exact `walk` (research/hunt/r19b/sim.py) with its Trail given a
separate activation; the ORB family, VIX divergence and Hero take R17's signals, contract choice, fills and charges
(research/hunt/r17/arms.py). The exit loops of those arms are copied here with the floor (and, for the ORB family, the
ProfitLock ladder on or off). Solo (midday) is ported here from engine SoloMidday.

Grids: R17's arrays are rows 0..374 = the minute starting 09:15 + row. Every trade record here carries minutes of the
day (bar start), so all arms share one clock: minute = 555 + row.
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np

_R17 = Path(__file__).resolve().parents[1] / "r17"
if str(_R17) not in sys.path:
    sys.path.insert(0, str(_R17))

import arms as A17  # noqa: E402  (research/hunt/r17/arms.py: signals, contracts, fills, charges)
from research.hunt.r19b import sim as S19  # noqa: E402

TICK = 0.05
OPEN_M = 555
EPS = 1e-9


# ---------------------------------------------------------------------------------------------------- the floor
@dataclass(frozen=True)
class Floor:
    """From +act rupees a lot (gross) a stop rests give rupees a lot under the best price. replace_ladder: the ORB family
    drops ProfitLock's ladder (R1000_500); otherwise the floor rides on top of it (the higher stop wins)."""
    name: str
    act: float = 1000.0
    give: float = 500.0
    replace_ladder: bool = False


VARIANTS = [
    Floor("F1000_500", 1000.0, 500.0),
    Floor("F750_500", 750.0, 500.0),
    Floor("F1500_500", 1500.0, 500.0),
    Floor("F1000_300", 1000.0, 300.0),
    Floor("F1000_700", 1000.0, 700.0),
]
REPLACE = Floor("R1000_500", 1000.0, 500.0, replace_ladder=True)   # ORB family only


def points(rupees, lot):
    """Rupees a lot as option points for a contract of `lot`; None when either is missing or not positive."""
    if rupees is None or lot is None or not (rupees > 0) or not (lot > 0) or not np.isfinite(rupees):
        return None
    return rupees / lot


def floor_trigger(f: Floor | None, entry, peak, lot):
    """The floor's sell-stop trigger for a buy at `entry` whose best price BEFORE this minute is `peak`, or None while
    not active (or no floor / a missing input). Rounded up to the tick, never above the peak, never below the tick."""
    if f is None or not (entry > 0) or peak is None or not np.isfinite(peak):
        return None
    act, give = points(f.act, lot), points(f.give, lot)
    if act is None or give is None or peak - entry < act - EPS:
        return None
    trig = S19.on_tick_up(peak - give)
    trig = min(trig, peak)
    return trig if trig >= TICK else None


def as_trail(f: Floor | None):
    """The floor as R19b's Trail (rupees, activation separate), for the Liquidity walk."""
    return None if f is None else S19.Trail(f.name, "rs", f.give, False, f.act)


# ---------------------------------------------------------------------------------------------------- shared records
def _path_rows(a, r0, r1):
    """Minute paths of an R17 grid array over rows r0..r1-1: minutes, opens, highs, closes (closes forward-filled)."""
    rows = np.arange(r0, max(r1, r0))
    c = a[r0:max(r1, r0), 3].astype(float)
    last = np.nan
    cc = np.empty_like(c)
    for i, x in enumerate(c):
        last = x if np.isfinite(x) else last
        cc[i] = last
    return dict(pm=OPEN_M + rows, ph=a[r0:max(r1, r0), 1].astype(float), pc=cc)


def _rec(arm, und, day, lot, qty, e, x, entry_row, exit_row, why, gross, charges, path, ctx):
    peak = np.nanmax(path["ph"]) if len(path["ph"]) and np.isfinite(path["ph"]).any() else e
    peak = max(peak, e)
    return dict(arm=arm, und=und, day=day, lot=lot, qty=qty, entry=e, exit=x, entry_min=OPEN_M + entry_row,
                exit_min=OPEN_M + exit_row, why=why, gross=gross, charges=charges, net=gross - charges,
                peak_lot=(peak - e) * lot, ctx=ctx, **path)


# ---------------------------------------------------------------------------------------------------- ORB family
def orb_exit(a, c0, e, stop_pts, tgt, lot, qty, ladder=True, floor: Floor | None = None):
    """R17 `sim_fixed_pts` with the ladder switchable and the floor added: one resting SL-M at the highest of the -stop,
    the ladder's lock and the floor, each earned on minute highs BEFORE this minute; target on a minute's high, sold at
    the next open; out at 15:10. Returns (exit price, exit row, why)."""
    trig = A17.floor_tick(np.array([e - stop_pts]))[0] if np.isfinite(stop_pts) else -1.0
    be = e + float(A17.COSTS.rt_per_unit(np.array([e]), np.array([float(qty)]))[0])
    peak = e
    for r in range(c0, A17.SQ_ROW):
        if not np.isfinite(a[r, 3]):
            continue
        o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
        lock = -1.0
        if ladder:
            for reach, keep in A17.LADDER:
                if peak >= e + reach * tgt - 1e-9:
                    lv = max(e + keep * tgt, be)
                    if lv < peak:
                        lock = max(lock, lv)
        fl = floor_trigger(floor, e, peak, lot)
        fl = -1.0 if fl is None else fl
        rest = max(trig, lock, fl)
        if rest > TICK and a[r, 2] <= rest:
            px = min(rest, o)
            if trig >= max(lock, fl):
                return A17.sell_fill(px, a, r, lot, stop=True), r, "stop"
            if lock >= fl:
                return A17.sell_fill(px, a, r, lot, stop=False), r, "lock"
            return A17.sell_fill(px, a, r, lot, stop=True), r, "floor"
        if r <= A17.SQ_ROW - 2 and a[r, 1] >= e + tgt:
            rr, px = A17.next_open(a, r + 1)
            return A17.sell_fill(px, a, rr, lot), rr, "target"
        peak = max(peak, a[r, 1])
    rr, px = A17.next_open(a, A17.SQ_ROW)
    return A17.sell_fill(px, a, rr, lot), rr, "square_off"


def run_orb(days, arm, floor: Floor | None = None):
    """R17 `run_orb` (fixed 40-point stop), every session (no expected-range warm-up)."""
    kind, k0, k1, cap, side_cap, tgt = A17.ORB_ARMS[arm]
    ladder = not (floor is not None and floor.replace_ladder)
    out = []
    for d in days:
        sigs = A17.orb_signals(d, kind, k0, k1)
        n = 0
        per_side = {1: 0, -1: 0}
        block = -1
        for k, s, strike in sigs:
            if 5 * k <= block or n >= cap or (side_cap and per_side[s] >= side_cap):
                continue
            exp, a = A17.contract(d, strike, s, same_day_ok=False)
            if a is None:
                continue
            sig_row = 5 * k + 4
            c0, px = A17.next_open(a, sig_row + 1)
            if c0 > sig_row + 3 or px <= 40.0:
                continue
            qty = d.lot
            e = A17.buy_fill(px, a, c0, d.lot)
            ctx = dict(kind="orb", a=a, c0=c0, e=e, stop=A17.K.STOP_FIXED, tgt=tgt, lot=d.lot, qty=qty)
            out.append(_orb_trade(arm, d, ctx, floor, ladder))
            n += 1
            per_side[s] += 1
            xr = out[-1]["exit_min"] - OPEN_M
            block = xr - (xr % 5)
    return out


def _orb_trade(arm, d, ctx, floor, ladder):
    a, c0, e, lot, qty = ctx["a"], ctx["c0"], ctx["e"], ctx["lot"], ctx["qty"]
    x, xr, why = orb_exit(a, c0, e, ctx["stop"], ctx["tgt"], lot, qty, ladder, floor)
    ch = A17.charges(e, x, qty)
    return _rec(arm, d.und, d.day, lot, qty, e, x, c0, xr, why, (x - e) * qty, ch, _path_rows(a, c0, xr), ctx)


# ---------------------------------------------------------------------------------------------------- VIX divergence
def vixdiv_exit(a, c0, e, stop_pts, time_stop, lot, floor: Floor | None = None):
    """R17 `sim_vixdiv` with the floor added (checked after the arm's own stop, which wins at an equal level)."""
    trig = A17.floor_tick(np.array([e - stop_pts]))[0] if np.isfinite(stop_pts) else -1.0
    check = c0 + 19
    peak = e
    for r in range(c0, A17.SQ_ROW):
        if not np.isfinite(a[r, 3]):
            continue
        o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
        fl = floor_trigger(floor, e, peak, lot)
        if trig > TICK and a[r, 2] <= trig and (fl is None or trig >= fl):
            return A17.sell_fill(min(trig, o), a, r, lot, stop=True), r, "stop"
        if fl is not None and a[r, 2] <= fl:
            return A17.sell_fill(min(fl, o), a, r, lot, stop=True), r, "floor"
        if time_stop and r == check and a[r, 3] < e * 1.05:
            rr, px = A17.next_open(a, r + 1)
            return A17.sell_fill(px, a, rr, lot), rr, "time"
        peak = max(peak, a[r, 1])
    rr, px = A17.next_open(a, A17.SQ_ROW)
    return A17.sell_fill(px, a, rr, lot), rr, "square_off"


def run_vixdiv(days, floor: Floor | None = None):
    out = []
    for d in days:
        if d.vix_min is None or not np.isfinite(d.vix_open):
            continue
        step = 100 if d.und == "BANKNIFTY" else 50
        io = d.ix[0, 0]
        for T in A17.VD_CHECKS:
            row = T - A17.OPEN_MIN - 1
            im = d.ix[row, 3] / io - 1
            vm = d.vix_min[row] / d.vix_open - 1 if np.isfinite(d.vix_min[row]) else np.nan
            if not np.isfinite(vm):
                continue
            s = -1 if (im > 0.002 and vm > 0.02) else 1 if (im < -0.002 and vm < -0.02) else 0
            if not s:
                continue
            x0 = d.ix[row, 3]
            strike = float(round(x0 / step) * step - s * step)
            exp, a = A17.contract(d, strike, s)
            if a is None:
                break
            c0 = next((r for r in range(row + 1, row + 1 + 3) if np.isfinite(a[r, 0])), None)
            if c0 is None:
                break
            e = A17.buy_fill(float(a[c0, 0]), a, c0, d.lot)
            stop = e - A17.floor_tick(np.array([e * 0.85]))[0] if d.und == "NIFTY" else np.nan
            ctx = dict(kind="vixdiv", a=a, c0=c0, e=e, stop=stop, time_stop=d.und == "NIFTY", lot=d.lot, qty=d.lot)
            out.append(_vixdiv_trade(f"vix_div_{d.und.lower()}", d, ctx, floor))
            break
    return out


def _vixdiv_trade(arm, d, ctx, floor):
    a, c0, e, lot, qty = ctx["a"], ctx["c0"], ctx["e"], ctx["lot"], ctx["qty"]
    x, xr, why = vixdiv_exit(a, c0, e, ctx["stop"], ctx["time_stop"], lot, floor)
    ch = A17.charges(e, x, qty)
    return _rec(arm, d.und, d.day, lot, qty, e, x, c0, xr, why, (x - e) * qty, ch, _path_rows(a, c0, xr), ctx)


# ---------------------------------------------------------------------------------------------------- Hero
def hero_exit(a, c0, e, level, lots, lot, floor: Floor | None = None):
    """R17 `_hero_exits` (half at 5x, the rest at 20x, close-based stop at 0.4 x entry, 15:05) with the floor per lot on
    what is still held: a resting stop on the minute's low, checked first in the minute (conservative)."""
    t1 = float(A17.floor_tick(np.array([5 * e]))[0])
    t2 = float(A17.floor_tick(np.array([20 * e]))[0])
    held = lots
    half = max(1, int(round(lots / 2)))
    gross = 0.0
    ch = float(A17.COSTS.charge(True, np.array([e]), np.array([lots * lot]))[0])
    why = "time"
    peak = e

    def sell(px, q):
        return (px - e) * q, float(A17.COSTS.charge(False, np.array([px]), np.array([q]))[0])

    for r in range(c0, 375):
        if not np.isfinite(a[r, 3]):
            if r >= A17.HERO_EXIT_ROW:
                break
            continue
        o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
        fl = floor_trigger(floor, e, peak, lot)
        if fl is not None and held > 0 and r > c0 and r < A17.HERO_EXIT_ROW and a[r, 2] <= fl:
            g, c = sell(max(min(fl, o) - TICK, TICK), held * lot)
            return gross + g, ch + c, r, "floor"
        if held == lots and a[r, 1] >= t1 + TICK:
            g, c = sell(t1, half * lot)
            gross += g
            ch += c
            held -= half
            why = "5x"
        if 0 < held < lots and a[r, 1] >= t2 + TICK:
            g, c = sell(t2, held * lot)
            return gross + g, ch + c, r, "20x"
        if held <= 0:
            return gross, ch, r, why
        if r >= A17.HERO_EXIT_ROW or (r > c0 and a[r, 3] <= level + 1e-9):
            stop = r < A17.HERO_EXIT_ROW
            rr, px = A17.next_open(a, min(r + 1, 374) if stop else r)
            g, c = sell(max(px - TICK, TICK), held * lot)
            return gross + g, ch + c, rr, ("stop" if stop else why if why != "time" else "15:05")
        peak = max(peak, a[r, 1])
    fin = np.nonzero(np.isfinite(a[:, 3]))[0]
    g, c = sell(max(float(a[fin[-1], 3]) - TICK, TICK), held * lot)
    return gross + g, ch + c, int(fin[-1]), "15:05"


def run_hero(days, floor: Floor | None = None):
    """R17 `run_hero` / `_hero_day` (NIFTY expiry days with that day's chain), without the expected-range inputs."""
    out = []
    for d in days:
        if d.und != "NIFTY":
            continue
        chain = {(k, s): a for (e, k, s), a in d.opts.items() if e == d.day}
        if len(chain) < 10:
            continue
        ctx = _hero_entry(d, chain)
        if ctx is not None:
            out.append(_hero_trade(d, ctx, floor))
    return out


def _hero_entry(d, chain):
    C = d.ix[:, 3]
    low = None
    skipped = 0
    for r in range(164, 330):
        atm = float(np.floor(C[r] / 50 + 0.5) * 50)
        ce, pe = chain.get((atm, "C")), chain.get((atm, "P"))
        st = None
        if ce is not None and pe is not None and A17._traded(ce, r, 2) and A17._traded(pe, r, 2):
            cc = ce[:r + 1, 3][np.isfinite(ce[:r + 1, 3])]
            pc = pe[:r + 1, 3][np.isfinite(pe[:r + 1, 3])]
            if len(cc) and len(pc):
                st = float(cc[-1] + pc[-1])
        if st is not None:
            low = st if low is None else min(low, st)
        if r < 254:
            continue
        if st is None or r < 15:
            skipped += 1
            if skipped > 5:
                return None
            continue
        skipped = 0
        mom = C[r] / C[r - 15] - 1
        if low is None or st / low - 1 < 0.15 - 1e-9 or abs(mom) < 0.0025 - 1e-9:
            continue
        s = 1 if mom > 0 else -1
        cands = sorted([(abs(k - C[r]), k, a) for (k, ss), a in chain.items()
                        if ss == ("C" if s > 0 else "P") and ((k > C[r]) if s > 0 else (k < C[r]))], key=lambda z: z[0])
        pick = None
        for i, (_, k, a) in enumerate(cands):
            lc = a[:r + 1, 3][np.isfinite(a[:r + 1, 3])]
            if not len(lc):
                continue
            ltp = float(lc[-1])
            if not (1.0 - 1e-9 <= ltp <= 5.0 + 1e-9) or not A17._traded(a, r, 4):
                continue
            slack = max(0.10, 0.25 * ltp)
            if any(np.isfinite(b[r, 3]) and b[r, 3] > ltp + slack for _, _, b in cands[i + 1:]):
                continue
            pick = (k, a, ltp)
            break
        if pick is None:
            continue
        k, a, ltp = pick
        ask = ltp + TICK
        limit = float(A17.floor_tick(np.array([min(ask + (2 if ask < 2 else 1) * TICK, ltp * 1.2 + 0.10)]))[0])
        c0 = r + 1
        if not (np.isfinite(a[c0, 2]) and a[c0, 2] <= limit):
            return None
        e = min(limit, float(a[c0, 0])) if np.isfinite(a[c0, 0]) else limit
        lots = int(math.floor(5000.0 / (limit * d.lot) + 1e-9))
        if lots <= 0:
            return None
        return dict(kind="hero", a=a, c0=c0, e=e, level=0.4 * e, lots=lots, lot=d.lot, qty=lots * d.lot)
    return None


def _hero_trade(d, ctx, floor):
    a, c0, e = ctx["a"], ctx["c0"], ctx["e"]
    gross, ch, xr, why = hero_exit(a, c0, e, ctx["level"], ctx["lots"], ctx["lot"], floor)
    return _rec("hero", d.und, d.day, ctx["lot"], ctx["qty"], e, np.nan, c0, xr, why, gross, ch, _path_rows(a, c0, xr), ctx)


# ---------------------------------------------------------------------------------------------------- Solo (midday)
SOLO_MOVE_ATR, SOLO_OUTER, SOLO_ITM, SOLO_STOP_ATR, SOLO_TARGET_R, SOLO_LOCK_AT = 0.5, 0.25, 4, 0.3, 2.0, 0.75
SOLO_DECIDE_ROW = 164        # the 11:59 bar
SOLO_EXIT_ROW = 315          # 14:30
SOLO_SLACK = 3


def atr14(days, i):
    """Engine ShadowRules.atr14: the mean true range of the 14 completed daily candles before day i (None if fewer)."""
    if i < 15:
        return None
    trs = []
    for j in range(i - 14, i):
        h, lo, pc = np.nanmax(days[j].ix[:, 1]), np.nanmin(days[j].ix[:, 2]), days[j - 1].ix[-1, 3]
        trs.append(max(h - lo, abs(h - pc), abs(lo - pc)))
    return float(np.mean(trs))


def solo_signal(d, atr):
    """SoloMidday.signal on the release grid: (side, strength, 12:00 index) or None."""
    if atr is None or not (atr > 0):
        return None
    ix = d.ix
    o, c = ix[0, 0], ix[SOLO_DECIDE_ROW, 3]
    hi, lo = np.nanmax(ix[:SOLO_DECIDE_ROW + 1, 1]), np.nanmin(ix[:SOLO_DECIDE_ROW + 1, 2])
    move = c - o
    if abs(move) < SOLO_MOVE_ATR * atr or not (hi > lo):
        return None
    side = 1 if move > 0 else -1
    pos = (hi - c) / (hi - lo) if side > 0 else (c - lo) / (hi - lo)
    if pos > SOLO_OUTER:
        return None
    index = ix[SOLO_DECIDE_ROW + 1, 0] if np.isfinite(ix[SOLO_DECIDE_ROW + 1, 0]) else c
    return side, abs(move) / atr, float(index)


def solo_exit(d_ix, a, c0, e, side, index, atr, lot, floor: Floor | None = None):
    """SoloMidday.walk on the index (close-based 0.3 ATR stop, breakeven lock from 0.45 ATR, 14:30), each decided on a
    bar's close and sold at the next minute's open; the floor is a resting stop on the option's minute low, checked
    first in each minute."""
    stop = index - side * SOLO_STOP_ATR * atr
    earn = SOLO_LOCK_AT * SOLO_TARGET_R * SOLO_STOP_ATR * atr
    best, locked = 0.0, False
    peak = e
    for r in range(SOLO_DECIDE_ROW + 1, SOLO_EXIT_ROW):
        if r >= c0 and np.isfinite(a[r, 3]):
            o = a[r, 0] if np.isfinite(a[r, 0]) else a[r, 3]
            fl = floor_trigger(floor, e, peak, lot)
            if fl is not None and r > c0 and a[r, 2] <= fl:
                return A17.sell_fill(min(fl, o), a, r, lot, stop=True), r, "floor"
        b = d_ix[r]
        fav = (b[1] - index) if side > 0 else (index - b[2])
        why = None
        if (b[3] - stop) * side <= 0:
            why = "index_stop"
        elif locked and (b[2] <= index if side > 0 else b[1] >= index):
            why = "lock"
        if why is not None:
            rr, px = A17.next_open(a, r + 1)
            return A17.sell_fill(px, a, rr, lot), rr, why
        best = max(best, fav)
        if best >= earn - EPS:
            locked = True
        if r >= c0 and np.isfinite(a[r, 1]):
            peak = max(peak, a[r, 1])
    rr, px = A17.next_open(a, SOLO_EXIT_ROW)
    return A17.sell_fill(px, a, rr, lot), rr, "time"


def run_solo(days_by, floor: Floor | None = None, unds=("BANKNIFTY", "NIFTY")):
    """Solo (midday) over the indices with option minutes: per day the strongest signal not on its expiry day (ties A-Z);
    strike 4 steps ITM, then one step either way (less in the money first); entry at the first option open 12:00-12:03."""
    by_day: dict = {}
    for u in unds:
        ds = days_by.get(u) or []
        for i, d in enumerate(ds):
            atr = atr14(ds, i)
            sg = solo_signal(d, atr)
            if sg:
                by_day.setdefault(d.day, []).append((u, d, sg, atr))
    out = []
    for day in sorted(by_day):
        cands = sorted(by_day[day], key=lambda z: (-z[2][1], z[0]))
        for u, d, (side, _k, index), atr in cands:
            if d.expiry == d.day:
                continue
            step = 50 if u == "NIFTY" else 100
            w = int(math.floor(index / step + 0.5) * step) - side * SOLO_ITM * step
            got = None
            for k in (w, w + side * step, w - side * step):
                exp, a = A17.contract(d, float(k), side, same_day_ok=False)
                if a is None:
                    continue
                c0 = next((r for r in range(SOLO_DECIDE_ROW + 1, SOLO_DECIDE_ROW + 2 + SOLO_SLACK) if np.isfinite(a[r, 0])), None)
                if c0 is not None:
                    got = (a, c0)
                    break
            if got is None:
                continue
            a, c0 = got
            e = A17.buy_fill(float(a[c0, 0]), a, c0, d.lot)
            ctx = dict(kind="solo", a=a, c0=c0, e=e, side=side, index=index, atr=atr, ix=d.ix, lot=d.lot, qty=d.lot)
            out.append(_solo_trade(d, ctx, floor))
            break
    return out


def _solo_trade(d, ctx, floor):
    a, c0, e, lot = ctx["a"], ctx["c0"], ctx["e"], ctx["lot"]
    x, xr, why = solo_exit(ctx["ix"], a, c0, e, ctx["side"], ctx["index"], ctx["atr"], lot, floor)
    ch = A17.charges(e, x, lot)
    return _rec("solo", d.und, d.day, lot, lot, e, x, c0, xr, why, (x - e) * lot, ch, _path_rows(a, c0, xr), ctx)


# ---------------------------------------------------------------------------------------------------- Liquidity (R19b)
LIQ_ARMS = {"liq15": "liquidity15", "liq5": "liquidity5"}


def _liq_series_path(ser, m0, m1):
    """Minute paths of an R19b option series over minutes m0..m1-1 (closes forward-filled from the last trade)."""
    mins, op, hi, lo, cl = ser
    pm = np.arange(m0, max(m1, m0))
    ph = np.full(len(pm), np.nan)
    pc = np.full(len(pm), np.nan)
    for i, m in enumerate(pm):
        j = S19.last_at_or_before(mins, m)
        if j >= 0:
            pc[i] = cl[j]
            if mins[j] == m:
                ph[i] = hi[j]
    return dict(pm=pm, ph=ph, pc=pc)


def run_liq(recs, arm, floor: Floor | None = None, only=None):
    book = LIQ_ARMS[arm]
    by_day = {r["day"]: r for r in recs}
    out = []
    for t in S19.run_book(recs, book, as_trail(floor), only=only):
        r = by_day[t["day"]]
        ser = r["opts"][(t["strike"], "C" if t["side"] > 0 else "P")]
        path = _liq_series_path(ser, t["entry_min"], t["exit_min"])
        peak = np.nanmax(path["ph"]) if np.isfinite(path["ph"]).any() else t["entry"]
        ctx = dict(kind="liq", ser=ser, lot=t["qty"], qty=t["qty"], e=t["entry"], key=(t["day"], t["entry_min"]), book=book)
        out.append(dict(arm=arm, und="BANKNIFTY", day=t["day"], lot=t["qty"], qty=t["qty"], entry=t["entry"], exit=t["exit"],
                        entry_min=t["entry_min"], exit_min=t["exit_min"], why=t["why"], gross=t["gross"],
                        charges=t["charges"], net=t["net"], peak_lot=(max(peak, t["entry"]) - t["entry"]) * t["qty"],
                        ctx=ctx, **path))
    return out


# ---------------------------------------------------------------------------------------------------- one entry, any exit
def market_sell_after(t, m):
    """The trade `t` sold at market at the first open after minute m (a random twin's cut, or a day rule): price."""
    c = t["ctx"]
    if c["kind"] == "liq":
        p, _ = S19.open_at_or_after(c["ser"], m + 1)
        return S19.sell_fill(p)
    a = c["a"]
    rr, px = A17.next_open(a, m + 1 - OPEN_M)
    return A17.sell_fill(px, a, rr, c["lot"])


def net_if_sold(t, px):
    """Net of `t` (the whole position) sold at `px`. Hero: its charges for a single sale of everything."""
    c = t["ctx"]
    e, q = t["entry"], t["qty"]
    if c["kind"] == "liq":
        return (px - e) * q - S19.round_trip(e, px, q)
    return (px - e) * q - A17.charges(e, px, q)


def mark(t, m):
    """The open trade's value at minute m's close, less what selling there would cost (charges at that price)."""
    i = int(m - t["pm"][0]) if len(t["pm"]) else -1
    if i < 0 or i >= len(t["pc"]) or not np.isfinite(t["pc"][i]):
        px = t["entry"]
    else:
        px = t["pc"][i]
    return net_if_sold(t, px)


def refloor(t, floor: Floor | None):
    """CTRL trade `t`'s entry, held on its own, under `floor` (its exits re-simulated): a new record."""
    c = t["ctx"]
    if c["kind"] == "liq":
        raise ValueError("Liquidity re-floors through run_liq(only=...)")

    class _D:
        pass
    d = _D()
    d.und, d.day = t["und"], t["day"]
    if c["kind"] == "orb":
        ladder = not (floor is not None and floor.replace_ladder)
        return _orb_trade(t["arm"], d, c, floor, ladder)
    if c["kind"] == "vixdiv":
        return _vixdiv_trade(t["arm"], d, c, floor)
    if c["kind"] == "hero":
        return _hero_trade(d, c, floor)
    if c["kind"] == "solo":
        return _solo_trade(d, c, floor)
    raise ValueError(c["kind"])
