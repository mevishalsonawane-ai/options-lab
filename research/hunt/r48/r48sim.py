"""R48 simulator: one position (1 to 4 held legs) from a decision minute to its exit, every exit rule at once.

Conventions (PREREG.md):
  * decision on minute t's close, every order fills at minute t+1's open (gaps paid in full; an overnight decision
    fills at the next session's first minute);
  * legs are held contracts (r48data), valued each minute by leg_day(): the close while inside the ATM+-10 window
    (last close carried over missing rows), bounded once outside it (see leg_day);
  * a position's "mark" M = sum over legs of pos x value (pos +1 long, -1 short); a credit position's cost to close
    is V = -M. P&L of a day is mark-to-market at the session's last minute; realised P&L with all costs lands on the
    exit day.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

import numpy as np

import r48cfg as K
import r48cost as X
import r48data as D

T1514 = D.mi(15, 14)
T1504 = D.mi(15, 4)
T1500 = D.mi(15, 0)
LAST = D.NMIN - 1


def ffill(a: np.ndarray, first: float = np.nan) -> np.ndarray:
    """Forward-fill NaNs; `first` seeds the value before index 0."""
    a = np.asarray(a, dtype=np.float64)
    ok = np.isfinite(a)
    if ok.all():
        return a.copy()
    idx = np.where(ok, np.arange(len(a)), -1)
    np.maximum.accumulate(idx, out=idx)
    out = np.where(idx >= 0, a[np.maximum(idx, 0)], first)
    return out


@dataclass
class LegDay:
    val: np.ndarray        # (NMIN,) value used for marks and checks
    fillpx: np.ndarray     # (NMIN,) price an order at that minute starts from (open if a row, else close, else val)
    row: np.ndarray        # (NMIN,) bool: a real row (close) that minute
    vol5: np.ndarray       # (NMIN,) units traded in the 5 minutes BEFORE that minute
    nosell: np.ndarray     # (NMIN,) bool: a long leg out of the window on the OTM side (valued 0, not sold)
    far: np.ndarray        # (NMIN,) bool: strike >= FAR_STEPS steps from the ATM strike


def leg_day(day: D.Day, side: int, strike: float, short: bool, prev: float = np.nan) -> LegDay:
    """Values of one held contract over one session. prev = its value at the end of the previous session (or NaN).

    Inside the window: the close (a missing row carries the last close). Outside the window (amendment 1):
      OTM side  short: min(last value, price of the window-edge strike of the same side)   (an upper bound)
                long : 0, and it is not sold                                               (conservative)
      ITM side  short: intrinsic + time value of the window-edge strike                    (an upper bound)
                long : intrinsic                                                           (a lower bound)
    A minute with no rows at all keeps the previous minute's value.
    """
    n = D.NMIN
    k = day.kidx(strike)
    if k >= 0:
        c = day.C[side, k].astype(np.float64)
        o = day.O[side, k].astype(np.float64)
        v = day.V[side, k].astype(np.float64)
    else:
        c = np.full(n, np.nan)
        o = np.full(n, np.nan)
        v = np.zeros(n)
    row = np.isfinite(c)
    have = np.isfinite(day.lo)
    inwin = have & (day.lo - 1e-6 <= strike) & (strike <= day.hi + 1e-6)
    cff = ffill(c, prev)
    s = day.spot
    above = have & (strike > day.hi + 1e-6)
    below = have & (strike < day.lo - 1e-6)
    otm = above if side == 0 else below
    itm = below if side == 0 else above
    val = cff.copy()
    if (otm | itm).any():
        edge = np.where(side == 0, np.where(above, day.hi, day.lo), np.where(below, day.lo, day.hi))
        edge = np.where(have, edge, day.K[0])
        ke = np.clip(np.searchsorted(day.K, edge), 0, len(day.K) - 1)
        epx = ffill(day.C[side, ke, np.arange(n)].astype(np.float64))
        intr = np.maximum(s - strike, 0.0) if side == 0 else np.maximum(strike - s, 0.0)
        eintr = np.maximum(s - edge, 0.0) if side == 0 else np.maximum(edge - s, 0.0)
        etv = np.maximum(np.nan_to_num(epx - eintr, nan=0.0), 0.0)
        if short:
            b_otm = np.where(np.isfinite(cff), np.fmin(cff, epx), epx)
            b_itm = intr + etv
        else:
            b_otm = np.zeros(n)
            b_itm = intr
        val = np.where(otm, b_otm, np.where(itm, b_itm, cff))
    val = np.where(have, val, np.nan)
    val = ffill(val, prev)
    nosell = otm & (not short)
    nosell = ffill(np.where(have, nosell.astype(float), np.nan), 0.0).astype(bool)
    fillpx = np.where(row & np.isfinite(o), o, np.where(row, c, val))
    cs = np.r_[0.0, np.cumsum(v)]
    idx = np.arange(n)
    vol5 = cs[idx] - cs[np.maximum(idx - 5, 0)]
    atm = np.round(s / day.step) * day.step if day.step > 0 else s
    far = np.abs(strike - atm) >= K.FAR_STEPS * day.step - 1e-6
    return LegDay(val, fillpx, row, vol5, nosell, far)


class LegCache:
    """leg_day() memo for one index: (day index, side, strike, short) -> LegDay; the previous-session value is
    threaded in so carried values cross nights."""

    def __init__(self, days: list[D.Day]):
        self.days = days
        self.memo: dict = {}

    def get(self, i: int, side: int, strike: float, short: bool, prev: float) -> LegDay:
        key = (i, side, strike, short, None if not np.isfinite(prev) else round(float(prev), 4))
        r = self.memo.get(key)
        if r is None:
            r = leg_day(self.days[i], side, strike, short, prev)
            if len(self.memo) > 400_000:
                self.memo.clear()
            self.memo[key] = r
        return r


@dataclass
class Leg:
    side: int              # 0 call, 1 put
    strike: float
    pos: int               # +1 long, -1 short


@dataclass
class Path:
    """One position over its hold: every leg's minute arrays from the fill minute f0 (g = 0) to the end of the last
    session of the hold."""
    und: str
    i0: int                # entry day index
    j: int                 # last day index of the path
    f0: int                # fill minute on day i0
    legs: list
    qty: int
    val: np.ndarray        # (nlegs, G)
    fillpx: np.ndarray
    vol5: np.ndarray
    nosell: np.ndarray
    far: np.ndarray
    spot: np.ndarray       # (G,)
    dayoff: np.ndarray     # (G,) 0 .. j - i0
    minute: np.ndarray     # (G,)
    entry_px: np.ndarray = field(default=None)    # (nlegs,) fill prices after slippage
    entry_charges: float = 0.0
    mark: np.ndarray = field(default=None)        # (G,) sum pos x val, per unit

    def g_at(self, off: int, minute: int) -> int:
        """Path index of (day offset, minute)."""
        if off == 0:
            return minute - self.f0
        return (D.NMIN - self.f0) + (off - 1) * D.NMIN + minute

    def close_g(self, off: int) -> int:
        return self.g_at(off, LAST)


def build_path(cache: LegCache, und: str, i0: int, j: int, f0: int, legs: list[Leg]) -> Path | None:
    """Arrays of every leg from (i0, f0) to the end of day j. None if a leg has no real row at the fill minute."""
    days = cache.days
    parts = {k: [] for k in ("val", "fillpx", "vol5", "nosell", "far")}
    for L in legs:
        prev = np.nan
        cols = {k: [] for k in parts}
        for i in range(i0, j + 1):
            ld = cache.get(i, L.side, L.strike, L.pos < 0, prev)
            if i == i0 and not ld.row[f0]:
                return None
            a = f0 if i == i0 else 0
            for k in parts:
                cols[k].append(getattr(ld, k)[a:])
            prev = float(ld.val[-1])
        for k in parts:
            parts[k].append(np.concatenate(cols[k]))
    spot = np.concatenate([days[i].spot[(f0 if i == i0 else 0):] for i in range(i0, j + 1)])
    dayoff = np.concatenate([np.full(D.NMIN - (f0 if i == i0 else 0), i - i0) for i in range(i0, j + 1)])
    minute = np.concatenate([np.arange(f0 if i == i0 else 0, D.NMIN) for i in range(i0, j + 1)])
    d0 = days[i0].day
    p = Path(und, i0, j, f0, legs, X.lot(und, d0), np.vstack(parts["val"]), np.vstack(parts["fillpx"]),
             np.vstack(parts["vol5"]), np.vstack(parts["nosell"]), np.vstack(parts["far"]), spot, dayoff, minute)
    if not np.isfinite(p.val[:, 0]).all():
        return None
    pos = np.array([L.pos for L in legs], dtype=np.float64)
    p.mark = (pos[:, None] * np.nan_to_num(p.val, nan=0.0)).sum(axis=0)
    ep, ch = [], 0.0
    for li, L in enumerate(legs):
        buy = L.pos > 0
        px = X.fill(float(p.fillpx[li, 0]), buy, p.vol5[li, 0] / p.qty, bool(p.far[li, 0]))
        ep.append(px)
        ch += X.charge(buy, px, p.qty, d0, und)
    p.entry_px = np.array(ep)
    p.entry_charges = ch
    return p


@dataclass
class Exit:
    g: int                 # decision index (fill at g + 1)
    kind: str              # 'pt', 'sl', 'isl', 'tgt', 'stop', 'time'
    pnl: float             # Rs, all costs
    off: int               # day offset of the fill
    daily: list            # [(day offset, Rs)] mark-to-market P&L per session of the hold


def exit_at(path: Path, days: list[D.Day], g: int, kind: str, settle: bool, tick_mult: float = 1.0) -> Exit:
    """Close every leg at path index g + 1 (the decision was on g's close). settle: the 15:05 expiry square-off, where
    a long leg priced <= Rs 1 (or out of the window) is left to expire at intrinsic value."""
    f = g + 1
    q = path.qty
    stop = kind in ("sl", "isl", "stop")
    off = int(path.dayoff[f])
    dday = days[path.i0 + off]
    cash = 0.0
    ch = 0.0
    settle_spot = None
    for li, L in enumerate(path.legs):
        px0 = float(path.fillpx[li, f])
        if L.pos < 0:
            px = X.fill(px0, True, path.vol5[li, f] / q, bool(path.far[li, f]), stop, tick_mult)
            cash -= px * q
            ch += X.charge(True, px, q, dday.day, path.und)
        else:
            if settle and (path.nosell[li, f] or px0 <= K.SETTLE_MAX):
                if settle_spot is None:
                    settle_spot = float(np.mean(dday.spot[T1500:]))
                iv = X.settle_value(L.side, L.strike, settle_spot)
                cash += iv * q
                ch += X.exercise_stt(iv, q, dday.day)
                continue
            if path.nosell[li, f]:
                continue
            px = X.fill(px0, False, path.vol5[li, f] / q, bool(path.far[li, f]), stop, tick_mult)
            cash += px * q
            ch += X.charge(False, px, q, dday.day, path.und)
    entry_cash = -float((np.array([L.pos for L in path.legs]) * path.entry_px).sum()) * q - path.entry_charges
    exit_cash = cash - ch
    pnl = entry_cash + exit_cash
    daily = []
    prev_mark = None
    for o in range(off):
        mc = float(path.mark[path.close_g(o)]) * q
        daily.append((o, (entry_cash if o == 0 else 0.0) + mc - (0.0 if prev_mark is None else prev_mark)))
        prev_mark = mc
    daily.append((off, (entry_cash if off == 0 else 0.0) + exit_cash - (0.0 if prev_mark is None else prev_mark)))
    return Exit(g, kind, pnl, off, daily)


def first_true(mask: np.ndarray, end: int) -> int:
    """First index <= end where mask is true, else -1."""
    m = mask[: end + 1]
    k = int(np.argmax(m)) if m.size else 0
    return k if m.size and m[k] else -1


def time_end(path: Path, hold: str, dte: int) -> tuple[int, bool]:
    """Decision index of the time exit and whether it is the expiry square-off. hold 'day': 15:14 on the entry day
    (15:04 if it is expiry day); 'exp': 15:04 on the last day of the path (expiry)."""
    if hold == "day" and dte > 0:
        return path.g_at(0, T1514), False
    return path.g_at(path.j - path.i0, T1504), True


def credit_exits(path: Path, days, dte: int, credit: float, istop: np.ndarray, holds=K.HOLD, pts=K.PTS, sls=K.SLS,
                 isls=K.ISL, tick_mult: float = 1.0) -> dict:
    """Every exit combination of a credit position: {(pt, sl, isl, hold): Exit}. credit = per-unit credit received
    (entry fills). istop: (G,) bool, the index-stop condition on each minute's close."""
    V = -path.mark
    out = {}
    memo = {}
    for hold in holds:
        if hold == "exp" and path.j < path.i0 + (0 if dte == 0 else dte):
            continue
        if hold == "day" and dte == 0 and "exp" in holds:
            continue                                        # identical to 'exp' on expiry day
        end, settle = time_end(path, hold, dte)
        if end >= len(V) - 1 or end < 0:
            continue
        gpt = {p: (first_true(V <= (1 - p) * credit, end) if p is not None else -1) for p in pts}
        gsl = {s: (first_true(V >= (1 + s) * credit, end) if s is not None else -1) for s in sls}
        gis = first_true(istop, end)
        for p in pts:
            for s in sls:
                for isl in isls:
                    cands = []
                    if gsl[s] >= 0:
                        cands.append((gsl[s], 0, "sl"))
                    if isl and gis >= 0:
                        cands.append((gis, 0, "isl"))
                    if gpt[p] >= 0:
                        cands.append((gpt[p], 1, "pt"))          # a stop wins a tie with the target
                    g, _, kind = min(cands) if cands else (end, 2, "time")
                    key = (g, kind, settle and kind == "time")
                    if key not in memo:
                        memo[key] = exit_at(path, days, g, kind, key[2], tick_mult)
                    out[(p, s, isl, hold)] = memo[key]
    return out


def buy_exits(path: Path, days, dte: int, tgts=K.TGTS, stops=K.BSTOPS, times=K.BTIME, tick_mult: float = 1.0) -> dict:
    """Every exit combination of a single bought leg: {(tgt, stop, time): Exit}. Intraday only."""
    M = path.mark
    e = float(path.entry_px[0])
    eod, settle = time_end(path, "day", dte)
    out, memo = {}, {}
    for tm in times:
        end = eod if tm == "eod" else min(eod, tm - 1)
        for t in tgts:
            gt = first_true(M >= (1 + t) * e, end)
            for s in stops:
                gs = first_true(M <= (1 - s) * e, end) if s is not None else -1
                cands = [(gs, 0, "stop")] if gs >= 0 else []
                if gt >= 0:
                    cands.append((gt, 1, "tgt"))
                g, _, kind = min(cands) if cands else (end, 2, "time")
                key = (g, kind, settle and kind == "time" and end == eod)
                if key not in memo:
                    memo[key] = exit_at(path, days, g, kind, key[2], tick_mult)
                out[(t, s, tm)] = memo[key]
    return out


# ------------------------------------------------------------------------------------------------ structures
def snap(x: float, step: float, up: bool) -> float:
    """Strike on the step grid at or beyond x (up: ceil, else floor)."""
    k = np.ceil(x / step - 1e-9) if up else np.floor(x / step + 1e-9)
    return float(k * step)


def atm_straddle(day: D.Day, t: int):
    """(ATM strike, call + put close) at minute t, the strike nearest the index with both sides traded that minute;
    None if none."""
    s = day.spot[t]
    ok = np.isfinite(day.C[0, :, t]) & np.isfinite(day.C[1, :, t])
    if not ok.any():
        return None
    ks = np.flatnonzero(ok)
    k = ks[np.argmin(np.abs(day.K[ks] - s))]
    return float(day.K[k]), float(day.C[0, k, t] + day.C[1, k, t])


def credit_legs(kind: str, day: D.Day, t: int, m: float, w: int):
    """Legs of a credit structure at decision minute t. kind: BPS, BCS, IC, IB. Returns (legs, short put strike or
    None, short call strike or None, width in points) or None."""
    a = atm_straddle(day, t)
    if a is None or not day.step > 0:
        return None
    katm, E = a
    s, st = day.spot[t], day.step
    W = w * st
    if kind == "IB":
        return [Leg(0, katm, -1), Leg(1, katm, -1), Leg(0, katm + W, +1), Leg(1, katm - W, +1)], katm, katm, W
    kp = snap(s - m * E, st, False)
    kc = snap(s + m * E, st, True)
    legs = []
    if kind in ("BPS", "IC"):
        legs += [Leg(1, kp, -1), Leg(1, kp - W, +1)]
    if kind in ("BCS", "IC"):
        legs += [Leg(0, kc, -1), Leg(0, kc + W, +1)]
    if not legs:
        return None
    return legs, (kp if kind in ("BPS", "IC") else None), (kc if kind in ("BCS", "IC") else None), W


def index_stop(path: Path, kind: str, kp, kc, credit: float) -> np.ndarray:
    """Index stop on each minute's close: the index beyond a short strike (IB: beyond ATM +- credit)."""
    s = path.spot
    if kind == "IB":
        return (s < kp - credit) | (s > kc + credit)
    m = np.zeros(len(s), dtype=bool)
    if kp is not None:
        m |= s < kp
    if kc is not None:
        m |= s > kc
    return m


def credit_of(path: Path) -> float:
    """Per-unit credit received at entry (fills after slippage)."""
    return float(-(np.array([L.pos for L in path.legs]) * path.entry_px).sum())
