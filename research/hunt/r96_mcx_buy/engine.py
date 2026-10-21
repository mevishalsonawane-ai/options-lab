"""R96 trade engine (PREREG_ROUND1.md s3-5). Pure given a Book (minute grid, printed legs, IV per stored strike).

One held contract (cp, strike) is followed minute by minute from its fill:
- a printed minute gives o/h/l/c; a minute where the strike sits outside the stored ATM+/-3 window gets a Black-76
  mark (o = h = l = c = the mark) from the future, the time to expiry and the IV of the nearest stored strike of the
  same side; any other minute (in the window but no print) gives no option price;
- single leg: stop / lock check on the minute's low first (level = max(hard stop, lock); out at the level, or at the
  open when the minute opened below it), then the underlying stop on the future's minute close (out at the option's
  exit price that minute), then the peak / lock update (ladder, then the trail above TRAIL_FROM);
- straddle: the same on the two legs' combined value, using each leg's latest known price (closes only);
- the exit price at a minute (time exit, underlying stop) = its printed close, else the next print's open within 10
  minutes the same session, else the model mark when out of the window, else the last print (stale).
Costs: Zerodha MCX option charges + a half-spread per fill (x2 on a model-priced exit), times `mult` (gate: 2x).
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C53
from research.hunt.r96_mcx_buy import cfg as K

ONE_MIN = np.timedelta64(1, "m")
OFFSETS = tuple(range(-3, 4))


# ------------------------------------------------------------------------------------------------ pricing / costs
def _ncdf(x):
    return 0.5 * (1.0 + math.erf(x / math.sqrt(2.0)))


def b76(F: float, Kx: float, T: float, vol: float, cp: int) -> float:
    """Black-76 undiscounted price; cp 0 call / 1 put; vol as a fraction a year; T in years."""
    if not (F > 0 and Kx > 0) or not np.isfinite(vol) or vol <= 0 or T <= 0:
        return max(F - Kx, 0.0) if cp == 0 else max(Kx - F, 0.0)
    s = vol * math.sqrt(T)
    d1 = (math.log(F / Kx) + 0.5 * s * s) / s
    d2 = d1 - s
    if cp == 0:
        return F * _ncdf(d1) - Kx * _ncdf(d2)
    return Kx * _ncdf(-d2) - F * _ncdf(-d1)


def opt_cost(ck: str, buy_rs: float, sell_rs: float, tod_in: int, tod_out: int, mult: float = 1.0,
             model_exit: bool = False) -> float:
    return (C53.mcx_opt_charges(buy_rs, sell_rs) + K.opt_half(ck, tod_in, mult) * buy_rs
            + K.opt_half(ck, tod_out, mult) * sell_rs * (K.MODEL_EXIT_HALF_MULT if model_exit else 1.0))


# ------------------------------------------------------------------------------------------------ calendar helpers
def last_ix_at_or_before(m, t64) -> int:
    return int(np.searchsorted(m.ts, t64, side="right")) - 1


def sq_ix(m, day) -> int:
    """Grid index of the square-off of a session: the last minute at or before close - 15 min."""
    d = np.datetime64(pd.Timestamp(day).normalize(), "ns")
    end = m.session_end(d) - K.SQ_BEFORE_CLOSE
    return m.at_or_before(d, end - 1)


def expiry_of(m, i: int):
    """The option expiry day of the contract live at grid index i (None when not detected in the data)."""
    s = int(m.seg[i])
    return m.exp_days[s] if s < len(m.exp_days) else None


def day_position(book, day) -> int:
    """Position of a day in the book's sessions (a day not in the data: where it would be inserted)."""
    k = book.day_pos.get(day)
    return int(np.searchsorted(book.m.days, day)) if k is None else k


def last_exit_ix(book, i_entry: int, n_sessions: int | None, last_day=None) -> int:
    """Grid index of the latest allowed exit: the square-off of the n-th session counting the entry day as the first
    (None: the entry session's square-off), capped by the session before the option expiry day and by last_day."""
    m = book.m
    pos = book.day_pos[m.day[i_entry]]
    tgt = pos + (n_sessions - 1 if n_sessions else 0)
    ex = expiry_of(m, i_entry)
    if ex is not None:
        tgt = min(tgt, day_position(book, ex) - 1)
    tgt = min(tgt, len(m.days) - 1)
    if last_day is not None:
        ld = np.datetime64(pd.Timestamp(last_day).normalize(), "ns")
        while tgt > pos and m.days[tgt] > ld:
            tgt -= 1
    if tgt < pos:
        return -1
    return sq_ix(m, m.days[tgt])


def sessions_to_expiry(book, i: int) -> int:
    """Sessions strictly between the day of grid index i and the option expiry day (large when unknown)."""
    ex = expiry_of(book.m, i)
    if ex is None:
        return 10 ** 6
    return day_position(book, ex) - book.day_pos[book.m.day[i]] - 1


# ------------------------------------------------------------------------------------------------ marks
def contract_marks(book, cp: int, strike: float, i0: int, i1: int):
    """Over grid indices i0..i1: o, h, l, c (NaN = no print) and mdl (Black-76 mark where the strike is outside the
    stored window, else NaN)."""
    m = book.m
    n = i1 - i0 + 1
    o = np.full(n, np.nan)
    h, l, c = o.copy(), o.copy(), o.copy()
    days = np.unique(m.day[i0:i1 + 1])
    for d in days:
        lg = m.leg(d, cp, strike)
        if lg is None:
            continue
        ii, V = lg
        sel = (ii >= i0) & (ii <= i1)
        r = ii[sel] - i0
        o[r], h[r], l[r], c[r] = V[sel, 0], V[sel, 1], V[sel, 2], V[sel, 3]
    S = np.vstack([m.k_at.get((cp, k), np.full(len(m.ts), np.nan))[i0:i1 + 1] for k in OFFSETS])
    with np.errstate(all="ignore"):
        smax = np.nanmax(np.where(np.isfinite(S), S, -np.inf), axis=0)
        smin = np.nanmin(np.where(np.isfinite(S), S, np.inf), axis=0)
    have = np.isfinite(smax) & np.isfinite(smin)
    out = have & ((strike > smax + 1e-9) | (strike < smin - 1e-9))
    mdl = np.full(n, np.nan)
    if out.any():
        ex = expiry_of(m, i0)
        if ex is not None:
            t_exp = pd.Timestamp(ex) + pd.Timedelta(hours=23, minutes=30)
        else:
            t_exp = pd.Timestamp(m.days[-1]) + pd.Timedelta(days=10)
        IV = np.vstack([book.iv.get((cp, k), np.full(len(m.ts), np.nan))[i0:i1 + 1] for k in OFFSETS])
        for r in np.flatnonzero(out):
            col = S[:, r]
            kk = int(np.nanargmax(col)) if strike > smax[r] else int(np.nanargmin(col))
            vol = IV[kk, r]
            if not np.isfinite(vol):
                fin = np.flatnonzero(np.isfinite(IV[:, r]))
                if not len(fin):
                    continue
                vol = IV[fin[np.argmin(np.abs(fin - kk))], r]
            T = max((t_exp - pd.Timestamp(m.ts[i0 + r])).total_seconds() / (365.0 * 86400.0), 1e-6)
            mdl[r] = b76(float(m.F[i0 + r]), strike, T, vol / 100.0, cp)
    return o, h, l, c, mdl


def edge_flags(book, cp: int, strike: float, i0: int, i1: int) -> np.ndarray:
    """Round 2 (PREREG_ROUND2 s2): per grid minute i0..i1, True when the held strike is the deepest in-the-money
    strike stored at that minute or beyond it (call: <= the lowest stored strike; put: >= the highest)."""
    m = book.m
    S = np.vstack([m.k_at.get((cp, k), np.full(len(m.ts), np.nan))[i0:i1 + 1] for k in OFFSETS])
    with np.errstate(all="ignore"):
        smax = np.nanmax(np.where(np.isfinite(S), S, -np.inf), axis=0)
        smin = np.nanmin(np.where(np.isfinite(S), S, np.inf), axis=0)
    have = np.isfinite(smax) & np.isfinite(smin)
    return have & ((strike <= smin + 1e-9) if cp == 0 else (strike >= smax - 1e-9))


def exit_price(o, c, mdl, day, k: int):
    """(price, model?, stale?) for an exit decided at relative minute k."""
    if np.isfinite(c[k]):
        return float(c[k]), False, False
    n = len(c)
    for q in range(k + 1, min(k + 11, n)):
        if day[q] != day[k]:
            break
        if np.isfinite(o[q]):
            return float(o[q]), False, False
    if np.isfinite(mdl[k]):
        return float(mdl[k]), True, False
    pr = np.flatnonzero(np.isfinite(c[:k + 1]))
    if len(pr):
        return float(c[pr[-1]]), False, True
    return float("nan"), False, True


def _lock_update(lock, peak, base, be, ladder, trail_from, trail_gap):
    g = peak / base - 1.0
    for trig, keep in ladder:
        if g >= trig - 1e-12:
            lv = be if not np.isfinite(keep) else base * (1 + keep)
            if lv > lock:
                lock = lv
    if g >= trail_from - 1e-12:
        lv = base * (1 + g - trail_gap)
        if lv > lock:
            lock = lv
    return lock


def walk_single(o, h, l, c, mdl, F, side: int, fill: float, be: float, ustop, stop_frac: float = K.PREM_STOP,
                ladder=K.LADDER, trail_from: float = K.TRAIL_FROM, trail_gap: float = K.TRAIL_GAP, edge=None):
    """Relative minute 0 = the fill minute. Returns (k, why, price or None, model?) where price None means 'use
    exit_price at k'. edge (round 2): per-minute 'at the edge' flags; at the first edge minute with a print the position
    is sold at that print's close; past the edge without a print, at the open of the contract's next print."""
    lvl_stop = fill * (1 - stop_frac)
    lock = -np.inf
    peak = fill
    n = len(c)
    pending = False
    for k in range(n):
        if pending and np.isfinite(o[k]) and k > 0:
            return k, "edge", float(o[k]), False
        if np.isfinite(l[k]):
            op, hi, lo, md = o[k], h[k], l[k], False
        elif np.isfinite(mdl[k]):
            op = hi = lo = mdl[k]
            md = True
        else:
            op = hi = lo = np.nan
            md = False
        if np.isfinite(lo):
            lvl = max(lvl_stop, lock)
            if lo <= lvl:
                px = op if (np.isfinite(op) and op < lvl) else lvl
                return k, ("lock" if lock > lvl_stop else "stop"), float(px), md
        u = ustop[k] if isinstance(ustop, np.ndarray) else ustop      # round 3: a per-minute (trailing) level
        if u is not None and np.isfinite(u) and np.isfinite(F[k]) and side * (F[k] - u) <= 0:
            return k, "ustop", None, False
        if edge is not None and edge[k] and k > 0:
            if np.isfinite(c[k]):
                return k, "edge", float(c[k]), False
            pending = True
        if np.isfinite(hi):
            if hi > peak:
                peak = hi
            lock = _lock_update(lock, peak, fill, be, ladder, trail_from, trail_gap)
    return n - 1, "time", None, False


def trail_levels(F, side: int, ustop0: float, atr: float, be_atr: float = 1.0, gap_atr: float = 1.5) -> np.ndarray:
    """Round 3 (PREREG_ROUND3 R3C): the underlying stop per relative minute, from the future's minute closes F (minute 0
    = the fill minute). Starts at ustop0. Once the best close since the fill is >= be_atr x ATR in favour of F[0], the
    stop is at least F[0] (breakeven on the future), and from then on at least the best close - gap_atr x ATR (a
    chandelier). It never moves against the trade. The level at minute k uses closes up to k (known at k's close)."""
    F = np.asarray(F, float)
    out = np.full(len(F), float(ustop0))
    if not (np.isfinite(atr) and atr > 0) or not len(F) or not np.isfinite(F[0]):
        return out
    f0 = F[0]
    lvl = float(ustop0)
    best = f0
    on = False
    for k in range(len(F)):
        if np.isfinite(F[k]) and side * (F[k] - best) > 0:
            best = F[k]
        if not on and side * (best - f0) >= be_atr * atr - 1e-12:
            on = True
        if on:
            cand = best - side * gap_atr * atr
            for c_ in (f0, cand):
                if side * (c_ - lvl) > 0:
                    lvl = c_
        out[k] = lvl
    return out


def daily_chandelier(book, i_in: int, i_end: int, side: int, f_entry: float, init_atr: float = K.R93_INIT_ATR,
                     trail_atr: float = K.R93_TRAIL_ATR) -> np.ndarray:
    """R3K / R3L: R93's daily exits on the future, per relative minute i_in..i_end. NaN except at each session's
    "close" minute (its square-off minute, the last of the trade at i_end), where the value is the stop level to check
    that close against: first entry -/+ init_atr x ATR14 (ATR known before the entry day), then after each close the
    chandelier best close -/+ trail_atr x ATR14 (ATR known before that day; never loosens). Check first, then update
    (R93 sim_daily order)."""
    from research.hunt.r96_mcx_buy import rules as R
    m = book.m
    n = i_end - i_in + 1
    out = np.full(n, np.nan)
    a0 = R.atr_before(book, m.day[i_in])
    if not (np.isfinite(a0) and a0 > 0):
        return out
    lvl = f_entry - side * init_atr * a0
    best = f_entry
    for d in np.unique(m.day[i_in:i_end + 1]):
        k = min(sq_ix(m, d), i_end)
        if k < i_in:
            continue
        out[k - i_in] = lvl
        f = m.F[k]
        if np.isfinite(f):
            best = max(best, f) if side > 0 else min(best, f)
        at = R.atr_before(book, d)
        if np.isfinite(at):
            nl = best - side * trail_atr * at
            lvl = max(lvl, nl) if side > 0 else min(lvl, nl)
    return out


def sim_future(book, i_sig: int, side: int, n_sessions: int, last_day=None, i_from: int | None = None, **_):
    """R3L: the same trade on the near future itself (1 lot = book.units per point). Filled at the minute close after
    the signal (R53 convention); out at the first session close at or beyond R93's level, else at the time exit close.
    Costs: R93 futures costs (charges + 0.03% half-spread a side + 0.02% on stop / lock exits); net2 doubles both."""
    from research.hunt.r93_breadth_donchian_mcx import engine as E93
    m = book.m
    i_from = i_sig + 1 if i_from is None else i_from
    if i_from >= len(m.ts) or m.day[i_from] != m.day[i_sig] or m.is_exp[i_from]:
        return None
    if sessions_to_expiry(book, i_from) < K.MIN_SESSIONS_TO_EXPIRY:
        return None
    i_in = i_from
    entry = float(m.F[i_in])
    i_end = last_exit_ix(book, i_in, n_sessions, last_day)
    if i_end <= i_in or not np.isfinite(entry):
        return None
    lv = daily_chandelier(book, i_in, i_end, side, entry)
    k_out, why = i_end - i_in, "time"
    for k in np.flatnonzero(np.isfinite(lv)):
        if np.isfinite(m.F[i_in + k]) and side * (m.F[i_in + k] - lv[k]) <= 0:
            k_out, why = int(k), ("lock" if side * (lv[k] - entry) > 0 else "stop")
            break
    i_out = i_in + k_out
    px = float(m.F[i_out])
    u = book.units
    gross = side * (px - entry) * u
    buy, sell = (entry, px) if side > 0 else (px, entry)
    st = why in ("stop", "lock")
    c53 = C53.mcx_fut_charges(buy * u, sell * u)
    cost = c53 + K.R93_FUT_HALF * (buy + sell) * u + (K.R93_STOP_SLIP * sell * u if st else 0.0)
    cost2 = c53 + 2 * K.R93_FUT_HALF * (buy + sell) * u + (2 * K.R93_STOP_SLIP * sell * u if st else 0.0)
    from research.hunt.r96_mcx_buy import rules as R
    a0 = R.atr_before(book, m.day[i_in])
    return dict(book=book.name, entry_ts=pd.Timestamp(m.ts[i_in]), exit_ts=pd.Timestamp(m.ts[i_out]),
                day=pd.Timestamp(m.day[i_in]), side=int(side), kind="future", strike=[float("nan")], fill=[entry],
                exit=[px], why=why, model=False, stale=False, gross=gross, cost=cost, net=gross - cost,
                net2=gross - cost2, prem_rs=entry * u, risk_rs=K.R93_INIT_ATR * a0 * u + cost)


def walk_straddle(legs, fill_v: float, be_v: float, stop_frac: float = K.STRAD_STOP, ladder=K.LADDER,
                  trail_from: float = K.TRAIL_FROM, trail_gap: float = K.TRAIL_GAP, edges=None):
    """legs: [(o, h, l, c, mdl), (o, h, l, c, mdl)] from the fill minute; checks the combined value on closes / marks.
    edges (round 2): per-leg edge flags; the first minute where a leg is at the edge and printed (or, past the edge,
    prints again) ends the trade (both legs sold at their exit prices). Returns (k, why)."""
    n = len(legs[0][3])
    last = [np.nan, np.nan]
    lvl_stop = fill_v * (1 - stop_frac)
    lock = -np.inf
    peak = fill_v
    pending = [False, False]
    for k in range(n):
        if edges is not None and k > 0:
            for j in (0, 1):
                pr = np.isfinite(legs[j][3][k])
                if (edges[j][k] or pending[j]) and pr:
                    return k, "edge"
                if edges[j][k]:
                    pending[j] = True
        for j in (0, 1):
            c, mdl = legs[j][3], legs[j][4]
            if np.isfinite(c[k]):
                last[j] = c[k]
            elif np.isfinite(mdl[k]):
                last[j] = mdl[k]
        if not (np.isfinite(last[0]) and np.isfinite(last[1])):
            continue
        v = last[0] + last[1]
        if v <= max(lvl_stop, lock):
            return k, ("lock" if lock > lvl_stop else "stop")
        if v > peak:
            peak = v
            lock = _lock_update(lock, peak, fill_v, be_v, ladder, trail_from, trail_gap)
    return n - 1, "time"


# ------------------------------------------------------------------------------------------------ fills
def first_print(book, cp: int, strike: float, i_from: int, wait: int = K.FILL_WAIT):
    """(grid index, open) of the contract's first printed minute in [ts[i_from], ts[i_from] + wait min) the same day."""
    m = book.m
    lg = m.leg(m.day[i_from], cp, strike)
    if lg is None:
        return None
    ii, V = lg
    a = int(np.searchsorted(ii, i_from))
    if a >= len(ii) or m.ts[ii[a]] >= m.ts[i_from] + wait * ONE_MIN or not V[a, 0] > 0:
        return None
    return int(ii[a]), float(V[a, 0])


def pick_strike(m, cp: int, j: int, itm: int) -> float:
    """The strike `itm` steps in the money from the ATM at grid minute j (0 = ATM), from the set of strikes stored at
    that minute (both sides, all offsets): the ATM is the stored strike nearest the future; 1-ITM is the next stored
    strike below it for a call, above it for a put. NaN when the set does not reach that far. (The offset -> strike map
    of the thin metal books is forward-filled within the day and can repeat a strike, so offsets are not used.)"""
    ks = {float(a[j]) for (c_, _k), a in m.k_at.items() if np.isfinite(a[j])}
    if not ks:
        return float("nan")
    ks = np.array(sorted(ks))
    a = int(np.argmin(np.abs(ks - m.F[j])))
    b = a - itm if cp == 0 else a + itm
    return float(ks[b]) if 0 <= b < len(ks) else float("nan")


def _trade_dict(book, i_in, i_out, side, strikes, fills, exits, why, model, stale, cost, cost2, kind):
    units = book.units
    gross = sum((x - f) * units for f, x in zip(fills, exits))
    return dict(book=book.name, entry_ts=pd.Timestamp(book.m.ts[i_in]), exit_ts=pd.Timestamp(book.m.ts[i_out]),
                day=pd.Timestamp(book.m.day[i_in]), side=int(side), kind=kind, strike=strikes, fill=fills,
                exit=exits, why=why, model=bool(model), stale=bool(stale), gross=gross, cost=cost, net=gross - cost,
                net2=gross - cost2, prem_rs=sum(f * units for f in fills))


def sim_single(book, i_sig: int, side: int, ustop, n_sessions: int | None, last_day=None, i_from: int | None = None,
               offset: int | None = None, stop_frac: float = K.PREM_STOP, ladder=K.LADDER,
               trail_from: float = K.TRAIL_FROM, trail_gap: float = K.TRAIL_GAP, edge_exit: bool = False,
               max_risk: float | None = None, utrail=None, r93_exit: bool = False):
    """Buy one contract: the strike from the future's close at grid index i_sig (the minute before entry), filled at
    the first print from i_from (default i_sig + 1). `offset` = steps in the money (default 1: 1-ITM). Returns a trade
    or None."""
    m = book.m
    i_from = i_sig + 1 if i_from is None else i_from
    if i_from >= len(m.ts) or m.day[i_from] != m.day[i_sig] or m.is_exp[i_from]:
        return None
    if sessions_to_expiry(book, i_from) < K.MIN_SESSIONS_TO_EXPIRY and n_sessions is not None:
        return None
    if sessions_to_expiry(book, i_from) < 0:
        return None
    cp = 0 if side > 0 else 1
    strike = pick_strike(m, cp, i_sig, 1 if offset is None else offset)
    if not np.isfinite(strike):
        return None
    fp = first_print(book, cp, strike, i_from)
    if fp is None:
        return None
    i_in, fill = fp
    if max_risk is not None and stop_frac * fill * book.units + K.R2_RISK_COST > max_risk:
        return None                   # round 2 R2E: the 1-lot stop risk is above the cap -> skip
    i_end = last_exit_ix(book, i_in, n_sessions, last_day)
    if i_end <= i_in:
        return None
    o, h, l, c, mdl = contract_marks(book, cp, strike, i_in, i_end)
    o[0] = fill                       # the fill minute is entered at its open
    tod_in = int(m.tod[i_in])
    be = fill + opt_cost(book.ck, fill * book.units, fill * book.units, tod_in, tod_in) / book.units
    edge = edge_flags(book, cp, strike, i_in, i_end) if edge_exit else None
    if utrail is not None:            # round 3 R3C: (ATR14, breakeven after, chandelier gap) in ATR units
        ustop = trail_levels(m.F[i_in:i_end + 1], side, ustop, *utrail)
    if r93_exit:                      # R3K: R93's daily stop / chandelier on the future replaces the NR7 stop
        ustop = daily_chandelier(book, i_in, i_end, side, float(m.F[i_in]))
    k, why, px, md = walk_single(o, h, l, c, mdl, m.F[i_in:i_end + 1], side, fill, be, ustop, stop_frac, ladder,
                                 trail_from, trail_gap, edge)
    stale = False
    if px is None:
        px, md, stale = exit_price(o, c, mdl, m.day[i_in:i_end + 1], k)
    if not np.isfinite(px):
        return None
    i_out = i_in + k
    tod_out = int(m.tod[i_out])
    b, s = fill * book.units, px * book.units
    cost = opt_cost(book.ck, b, s, tod_in, tod_out, 1.0, md)
    cost2 = opt_cost(book.ck, b, s, tod_in, tod_out, 2.0, md)
    t = _trade_dict(book, i_in, i_out, side, [strike], [fill], [px], why, md, stale, cost, cost2, "single")
    t["risk_rs"] = fill * book.units * stop_frac + cost
    return t


def sim_straddle(book, i_sig: int, n_sessions: int | None, last_day=None, i_from: int | None = None,
                 i_end_fixed: int | None = None, stop_frac: float = K.STRAD_STOP, ladder=K.LADDER,
                 trail_from: float = K.TRAIL_FROM, trail_gap: float = K.TRAIL_GAP, fill_wait: int = K.FILL_WAIT,
                 edge_exit: bool = False):
    """Buy the ATM call and put (strikes at grid index i_sig); both must print within fill_wait minutes of i_from."""
    m = book.m
    i_from = i_sig + 1 if i_from is None else i_from
    if i_from >= len(m.ts) or m.day[i_from] != m.day[i_sig] or m.is_exp[i_from]:
        return None
    if n_sessions is not None and sessions_to_expiry(book, i_from) < K.MIN_SESSIONS_TO_EXPIRY:
        return None
    ks = [pick_strike(m, 0, i_sig, 0), pick_strike(m, 1, i_sig, 0)]
    if not all(np.isfinite(ks)):
        return None
    fps = [first_print(book, cp, ks[cp], i_from, fill_wait) for cp in (0, 1)]
    if any(f is None for f in fps):
        return None
    i_in = max(fps[0][0], fps[1][0])
    fills = [fps[0][1], fps[1][1]]
    if i_end_fixed is not None:
        i_end = i_end_fixed
        cap = last_exit_ix(book, i_in, None, last_day)
        if cap < i_end:
            i_end = cap
    else:
        i_end = last_exit_ix(book, i_in, n_sessions, last_day)
    if i_end <= i_in:
        return None
    legs = []
    for cp in (0, 1):
        o, h, l, c, mdl = contract_marks(book, cp, ks[cp], i_in, i_end)
        if fps[cp][0] == i_in:
            o[0] = fills[cp]
        legs.append((o, h, l, c, mdl))
    tod_in = int(m.tod[i_in])
    fv = sum(fills)
    be = fv + opt_cost(book.ck, fv * book.units, fv * book.units, tod_in, tod_in) / book.units + 40.0 / book.units
    edges = [edge_flags(book, cp, ks[cp], i_in, i_end) for cp in (0, 1)] if edge_exit else None
    k, why = walk_straddle(legs, fv, be, stop_frac, ladder, trail_from, trail_gap, edges)
    dd = m.day[i_in:i_end + 1]
    exits, mds, sts = [], [], []
    for cp in (0, 1):
        o, h, l, c, mdl = legs[cp]
        px, md, st = exit_price(o, c, mdl, dd, k)
        exits.append(px)
        mds.append(md)
        sts.append(st)
    if not all(np.isfinite(exits)):
        return None
    i_out = i_in + k
    tod_out = int(m.tod[i_out])
    cost = cost2 = 0.0
    for cp in (0, 1):
        b, s = fills[cp] * book.units, exits[cp] * book.units
        cost += opt_cost(book.ck, b, s, tod_in, tod_out, 1.0, mds[cp])
        cost2 += opt_cost(book.ck, b, s, tod_in, tod_out, 2.0, mds[cp])
    t = _trade_dict(book, i_in, i_out, 0, ks, fills, exits, why, any(mds), any(sts), cost, cost2, "straddle")
    t["risk_rs"] = fv * book.units * stop_frac + cost
    return t
