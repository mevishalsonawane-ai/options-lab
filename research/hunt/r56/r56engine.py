"""R56 engine: Boss's manual NIFTY ORB plan (PREREG A3 / A4) on one session.

Signal source: the NIFTY INDEX (1-minute closes against the 09:15 .. 09:29 range), the CLAUDE.md default. The option
(ATM at the signal, nearest expiry) is only the instrument; its target is set on the option premium (+Rs 1,000 a lot).

Grid rows: row t = the 1-minute bar that starts at 09:15 + t; a bar's close is known once its minute is over, so a
signal on row r is acted on at row r + 1's open. Nothing here reads a row after the decision to decide anything; the
stray-print guard on a fill at row r compares that open with the closes of rows r - 1, r and r + 1 (R47 amendment 1)
and can only make a fill worse (test_r56.py: the look-ahead guard).
"""
from __future__ import annotations

import math
import warnings
from dataclasses import dataclass
from datetime import date

import numpy as np

import r56cfg as K
import r3engine as E
from r3data import C, H, L, N, O
from obuy.costs import Costs

try:  # optional speed-up; the pure-Python path gives the same numbers
    from numba import njit
except Exception:  # pragma: no cover
    def njit(*a, **k):
        def deco(f):
            return f
        return deco if not (a and callable(a[0])) else a[0]

COSTS = Costs("dated")
EPOCH = date(1970, 1, 1)
WHY = ("stop", "target", "square_off", "hard_stop", "target_limit", "stale")


@dataclass(frozen=True)
class Variant:
    id: str
    target: bool = True        # +Rs 1,000 target
    range_stop: bool = True    # index close beyond the other side of the range
    hard_stop: bool = False    # C2: resting stop at entry - Rs 1,000 / lot
    limit: bool = False        # C3: target filled at the target price (resting limit)


PRIMARY = Variant("r56-boss-orb")
C1 = Variant("c1-no-target", target=False)
C2 = Variant("c2-hard-stop-1000", range_stop=False, hard_stop=True)
C3 = Variant("c3-limit-target", limit=True)
VARIANTS = (PRIMARY, C1, C2, C3)


# ------------------------------------------------------------------------------------------------ range and signal
def opening_range(ix: np.ndarray) -> tuple[float, float]:
    """High and low of the 1-minute index bars 09:15 .. 09:29; NaN unless the 09:29 bar exists."""
    if not np.isfinite(ix[K.RANGE_ROWS - 1, 3]):
        return np.nan, np.nan
    blk = ix[:K.RANGE_ROWS]
    if not (np.isfinite(blk[:, 1]).any() and np.isfinite(blk[:, 2]).any()):
        return np.nan, np.nan
    return float(np.nanmax(blk[:, 1])), float(np.nanmin(blk[:, 2]))


def find_signal(ix: np.ndarray, hi: float, lo: float) -> tuple[int, int]:
    """(row, side) of the FIRST 1-minute index close above hi (+1, call) or below lo (-1, put) on rows 09:30 .. 14:59;
    (-1, 0) when there is none. Reads rows up to the signal row only."""
    if not (np.isfinite(hi) and np.isfinite(lo)):
        return -1, 0
    for r in range(K.FIRST_SIG, K.LAST_SIG + 1):
        c = ix[r, 3]
        if not np.isfinite(c):
            continue
        if c > hi:
            return r, 1
        if c < lo:
            return r, -1
    return -1, 0


def atm(spot: float) -> float:
    """Nearest strike (step 50), a tie rounds up (OrbRules.atmStrike's half-up)."""
    return math.floor(spot / K.STEP + 0.5) * K.STEP


# ------------------------------------------------------------------------------------------------ prices and fills
def ref_close(c: np.ndarray, r: int) -> float:
    """Median of the contract's closes on rows r - 1, r, r + 1 (those that exist); NaN when none."""
    v = [c[i] for i in (r - 1, r, r + 1) if 0 <= i < len(c) and np.isfinite(c[i])]
    return float(np.median(v)) if v else np.nan


def _stray(p: float, ref: float) -> bool:
    return np.isfinite(ref) and abs(p - ref) > 2 * K.TICK + 1e-9 and (ref <= 0 or abs(p / ref - 1.0) > K.BAD_PRINT)


def open_for_buy(a: np.ndarray, r: int) -> float:
    p, ref = float(a[r, O]), ref_close(a[:, C], r)
    return max(p, ref) if _stray(p, ref) else p


def open_for_sell(a: np.ndarray, r: int) -> float:
    p, ref = float(a[r, O]), ref_close(a[:, C], r)
    return min(p, ref) if _stray(p, ref) else p


def confirmed_highs(a: np.ndarray) -> np.ndarray:
    """Each row's high capped at 1 + BAD_PRINT times the median close of rows r-1..r+1 (a stray high cannot hit the
    target)."""
    c = a[:, C].astype(np.float64)
    h = a[:, H].astype(np.float64).copy()
    pad = np.concatenate([[np.nan], c, [np.nan]])
    trio = np.stack([pad[:-2], pad[1:-1], pad[2:]])
    with np.errstate(all="ignore"), warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        ref = np.nanmedian(trio, axis=0)          # = ref_close(c, r) for every row
    ok = np.isfinite(ref) & np.isfinite(h)
    h[ok] = np.minimum(h[ok], ref[ok] * (1.0 + K.BAD_PRINT))
    return h


def clear_cache(s) -> None:
    """Forget the cached prices of a session (tests that change a session's arrays after a run call this)."""
    s.__dict__.pop("_r56", None)


def _held(s, k: float, side: int):
    """The held contract's float64 prices and confirmed highs, cached on the session (twins reuse them)."""
    cache = s.__dict__.setdefault("_r56", {})
    key = (k, side)
    if key not in cache:
        a = s.opts.get((k, "C" if side > 0 else "P"))
        cache[key] = None if a is None else (a.astype(np.float64), confirmed_highs(a), s.ix[:, 3].astype(np.float64))
    return cache[key]


def dn(d: date) -> int:
    return (d - EPOCH).days


def charge(buy: bool, px: float, qty: int, d: date) -> float:
    return float(COSTS.charge(buy, np.array([px]), np.array([float(qty)]), np.array([dn(d)]), bse=False)[0])


# ------------------------------------------------------------------------------------------------ exits
@njit(cache=True)
def _walk(ixc, lo_opt, hc, op, cl, c0, side, hi, lo, tgt, hard, use_range, limit, sq):
    """Exit walk on rows c0 .. sq-1. Returns (row, price, why): why 0 range stop (sell at row+1's open), 1 target (sell
    at row+1's open), 2 square-off (sell at sq's open), 3 hard stop (filled now at min(level, open)), 4 limit target
    (filled now at the target). A stop and a target in the same minute count as the stop."""
    for r in range(c0, sq):
        if hard > 0 and np.isfinite(lo_opt[r]) and lo_opt[r] <= hard:
            o = op[r] if np.isfinite(op[r]) else cl[r]
            return r, min(hard, o), 3
        if use_range and np.isfinite(ixc[r]):
            if (side > 0 and ixc[r] < lo) or (side < 0 and ixc[r] > hi):
                return r + 1, np.nan, 0
        if tgt > 0 and np.isfinite(hc[r]):
            if limit:
                if hc[r] >= tgt + 0.05 - 1e-9:
                    return r, tgt, 4
            elif hc[r] >= tgt - 1e-9:
                return r + 1, np.nan, 1
    return sq, np.nan, 2


def simulate(s, side: int, sig_row: int, v: Variant = PRIMARY, hi: float = np.nan, lo: float = np.nan) -> dict | None:
    """One trade on session s: signal on index row sig_row, buy 1 lot of the ATM (at that close) call / put at the next
    print within FILL_TRIES minutes. None when the contract is missing or does not print in time."""
    if not (np.isfinite(hi) and np.isfinite(lo)):
        hi, lo = opening_range(s.ix)
    spot = s.ix[sig_row, 3]
    if not np.isfinite(spot):
        return None
    k = atm(float(spot))
    held = _held(s, k, side)
    if held is None:
        return None
    a, hc, ixc = held
    sq = K.SQ_EXPIRY if s.expiry else K.SQ_ROW
    c0, _ = E.next_open(a, sig_row + 1, min(sig_row + 1 + K.FILL_TRIES, sq))
    if c0 < 0:
        return None
    lot = int(s.lot)
    e = E.buy_px(open_for_buy(a, c0), a, c0, lot)
    tgt = E.ceil_tick(e + K.TARGET_RS / lot) if v.target else 0.0
    hard = E.floor_tick(e - K.HARD_STOP_RS / lot) if v.hard_stop else 0.0
    r, p, why = _walk(ixc, a[:, L], hc, a[:, O], a[:, C], c0, side, hi, lo, tgt, hard,
                      v.range_stop, v.limit, sq)
    stale = False
    if why == 3:
        x = E.sell_px(p, a, r, lot, stop=True)
    elif why == 4:
        x = float(p)
    else:
        rr, _ = E.next_open(a, r)
        if rr < 0:                       # the strike left the stored band: last stored close before (A3)
            fin = np.where(np.isfinite(a[:r, C]))[0]
            rr = int(fin[-1]) if len(fin) else c0
            x = E.sell_px(float(a[rr, C]), a, rr, lot)
            stale = True
        else:
            x = E.sell_px(open_for_sell(a, rr), a, rr, lot, stop=(why == 0))
        r = rr
    gross = (x - e) * lot
    ch = charge(True, e, lot, s.day) + charge(False, x, lot, s.day)
    return dict(day=s.day, side=side, sig_row=sig_row, strike=k, entry_row=c0, exit_row=r, entry=e, exit=x,
                target_px=tgt, why=WHY[5] if stale else WHY[why], lot=lot, gross=gross, charges=ch, net=gross - ch,
                expiry=bool(s.expiry), hi=hi, lo=lo)


def run_session(s, v: Variant = PRIMARY) -> dict:
    """The rule on one session: {'status': no_range | no_signal | no_fill | traded, 'trade': dict | None}."""
    hi, lo = opening_range(s.ix)
    if not np.isfinite(hi):
        return dict(day=s.day, status="no_range", trade=None)
    r, side = find_signal(s.ix, hi, lo)
    if side == 0:
        return dict(day=s.day, status="no_signal", trade=None)
    sq = K.SQ_EXPIRY if s.expiry else K.SQ_ROW
    if r + 1 >= sq:
        return dict(day=s.day, status="no_signal", trade=None)
    t = simulate(s, side, r, v, hi, lo)
    if t is None:
        return dict(day=s.day, status="no_fill", trade=None)
    return dict(day=s.day, status="traded", trade=t)


# ------------------------------------------------------------------------------------------------ twins
def twin_rows() -> np.ndarray:
    return np.arange(K.FIRST_SIG, K.LAST_SIG + 1)


def twin_trade(s, side: int, rng: np.random.Generator, v: Variant = PRIMARY) -> dict | None:
    """One random-entry twin of a real trade (PREREG A5): same session and side, entry minute uniform on 09:30 .. 14:59,
    ATM at that minute, the same exits; a draw that cannot fill is re-drawn up to 5 times."""
    rows = twin_rows()
    hi, lo = opening_range(s.ix)
    sq = K.SQ_EXPIRY if s.expiry else K.SQ_ROW
    for _ in range(5):
        r = int(rows[int(rng.integers(len(rows)))])
        if r + 1 >= sq:
            continue
        t = simulate(s, side, r, v, hi, lo)
        if t is not None:
            return t
    return None
