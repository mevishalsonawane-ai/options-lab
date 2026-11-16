"""R189 engine (PREREG.md section 3): Boss's Hull line and the standard one on 1-minute index bars, the 2-candle entry
condition, the fresh-signal walk (one position per index), the exit candidates and the option fills.

Reused, unchanged: R51's `simulate` (research/hunt/r51/engine.py: the held contract, -15% stop, targets, step locks,
time stops, a forced exit minute, R17 "liq" fills) and its dated Zerodha charges; the obuy loader
(research/obuy/data.py) for Dhan's rolling option chains.

Nothing here reads a row after the decision: the line at candle t uses closes up to t; an exit is found minute by
minute from the fill on. test_r189.py's look-ahead guard checks both. Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import importlib.util
import math
import sys
from dataclasses import dataclass, field
from datetime import date

import numpy as np
from numba import njit
from numpy.lib.stride_tricks import sliding_window_view

import r189cfg as K


def _load(name: str, path):
    if name in sys.modules:
        return sys.modules[name]
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


E51 = _load("r51engine", K.R51 / "engine.py")      # simulate, _r2, _ticks, _vol5, COSTS, SLIP (R51 / R17)
FB, FS, FST, TK = E51.SLIP
SIM, R2, TICKS, VOL5 = E51.simulate, E51._r2, E51._ticks, E51._vol5
O, H, L, C, V = range(5)

# reasons: R51's codes, plus the forced exits split by kind
REASON = {1: "stop15", 2: "lock", 3: "target", 4: "time", 5: "squareoff", 7: "missing", 11: "line", 12: "index_stop",
          13: "index_target"}


# ================================================================================================ the Hull line
def wma(x, n: int) -> np.ndarray:
    """Pine's ta.wma: weights n (newest) .. 1 (oldest) over the last n values, divided by n(n+1)/2; NaN until n values
    exist and NaN whenever a value in the window is NaN."""
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if n <= 0 or len(x) < n:
        return out
    w = np.arange(1, n + 1, dtype=float)               # oldest .. newest
    out[n - 1:] = sliding_window_view(x, n) @ w / w.sum()
    return out


def hull(close, line: str, n: int = K.HMA_LEN) -> np.ndarray:
    """BOSS: WMA(2 x WMA(n) - WMA(n // 2), round(sqrt n)) (Boss's script, exactly); STD: WMA(2 x WMA(n // 2) - WMA(n),
    round(sqrt n)) (the standard Hull MA)."""
    c = np.asarray(close, float)
    slow, fast = wma(c, n), wma(c, n // 2)
    inner = 2.0 * slow - fast if line == "BOSS" else 2.0 * fast - slow
    return wma(inner, int(round(math.sqrt(n))))


def direction(h) -> np.ndarray:
    """+1 blue (h[t] > h[t-1]), -1 red (h[t] < h[t-1]), 0 equal or unknown (NaN on either side; the first bar)."""
    h = np.asarray(h, float)
    d = np.zeros(len(h), np.int8)
    if len(h) > 1:
        a, b = h[1:], h[:-1]
        ok = np.isfinite(a) & np.isfinite(b)
        d[1:][ok & (a > b)] = 1
        d[1:][ok & (a < b)] = -1
    return d


def lines_of(ix: np.ndarray) -> dict:
    """{line: (hull (nd, 375), dir (nd, 375))} on the continuous series of existing 1-minute closes across days (a
    missing minute is skipped, not filled)."""
    nd = ix.shape[0]
    valid = np.isfinite(ix[:, :, 3])
    flat = ix[:, :, 3][valid]
    out = {}
    for name in K.LINES:
        h = hull(flat, name)
        hh = np.full((nd, K.N), np.nan)
        dd = np.zeros((nd, K.N), np.int8)
        hh[valid] = h
        dd[valid] = direction(h)
        out[name] = (hh, dd)
    return out


def conditions(ix: np.ndarray, h: np.ndarray, dr: np.ndarray, reading: str) -> tuple:
    """(call, put) condition at each candle t (nd, 375): candles t and t-1 both present that day, both beyond the line
    (CLOSE reading: close; LOW reading: low for a call, high for a put), and the line blue (call) / red (put) at both."""
    c, lo, hi = ix[:, :, 3], ix[:, :, 2], ix[:, :, 1]
    pc, pp = (c, c) if reading == "close" else (lo, hi)
    ok = np.isfinite(c) & np.isfinite(h)
    with np.errstate(invalid="ignore"):
        aC = ok & (pc > h) & (dr == 1)
        aP = ok & (pp < h) & (dr == -1)
    cC = np.zeros_like(aC)
    cP = np.zeros_like(aP)
    cC[:, 1:] = aC[:, 1:] & aC[:, :-1]
    cP[:, 1:] = aP[:, 1:] & aP[:, :-1]
    return cC, cP


def fresh(cond: np.ndarray) -> np.ndarray:
    """True at t when the condition is true at t and false at t-1 (a new false -> true change; t = 0 needs only t)."""
    out = cond.copy()
    out[:, 1:] &= ~cond[:, :-1]
    return out


def atm_strike(spot: float, step: float) -> float:
    return math.floor(spot / step + 0.5) * step


def strike_for(side: str, spot: float, step: float, money: int) -> float:
    k = atm_strike(spot, step)
    return k - money * step if side == "C" else k + money * step


# ================================================================================================ exits and fills (numba)
@njit(cache=True)
def fill_row(o, e, sq):
    """The option's fill: its open at row e, or the first traded minute up to 2 minutes later (R51); -1 none."""
    for j in range(e, min(e + 3, sq)):
        if o[j] == o[j] and o[j] > 0:
            return j
    return -1


@njit(cache=True)
def forced_exit(ih, il, ic, io, h, dr, sgn, t, ef, sq, line, istop, rk):
    """(row, kind) of the first index-based exit from the fill row ef on: kind 1 the line (flip: dir against the trade;
    cross: the close back across the line), 2 the index stop at candle t's low (call) / high (put), 3 the index R
    target. A candle m that triggers sells at m + 1's open. In one minute the stop comes first. (-1, 0) none."""
    stop = np.nan
    tgt = np.nan
    if istop:
        stop = il[t] if sgn > 0 else ih[t]
        ent = io[ef] if io[ef] == io[ef] else ic[t]
        r = sgn * (ent - stop)
        if rk > 0 and r > 0:
            tgt = ent + sgn * rk * r
    for m in range(ef, sq):
        if istop:
            a = il[m]
            z = ih[m]
            if a == a and z == z:
                hit = (a <= stop) if sgn > 0 else (z >= stop)
                if hit:
                    return m + 1, 2
                if tgt == tgt:
                    ht = (z >= tgt) if sgn > 0 else (a <= tgt)
                    if ht:
                        return m + 1, 3
        if line > 0:
            hm = h[m]
            cm = ic[m]
            if hm == hm and cm == cm:
                if (line & 1) and dr[m] == -sgn:
                    return m + 1, 1
                if (line & 2) and sgn * (cm - hm) < 0:
                    return m + 1, 1
    return -1, 0


@njit(cache=True)
def run_batch(S, ci, tt, sg, ih, il, ic, io, h, dr, sq, line, istop, rk, stop_f, tgt_f, steps, tmax, tick, qty,
              fb, fs, fst, tk):
    """Price trades of one day. S (ncon, 5, 375) the day's contracts; ci contract per trade (-1 none); tt decision
    candle; sg +1 call / -1 put. Returns (n, 4) raw entry, raw exit, real entry, real exit; fill rows; exit rows;
    reasons (0 = no fill)."""
    n = len(ci)
    out = np.full((n, 4), np.nan)
    er = np.full(n, -1, np.int32)
    xr = np.full(n, -1, np.int32)
    rs = np.zeros(n, np.int32)
    for i in range(n):
        if ci[i] < 0:
            continue
        Cn = S[ci[i]]
        e = tt[i] + 1
        ef = fill_row(Cn[0], e, sq)
        if ef < 0:
            continue
        fx, fk = forced_exit(ih, il, ic, io, h, dr, sg[i], tt[i], ef, sq, line, istop, rk)
        a, b, c_, d_, r0, r1, r = SIM(Cn, e, sq, fx, stop_f, tgt_f, -1.0, 0.0, 0.0, steps, tmax, -1.0, -1.0, tick,
                                      qty, fb, fs, fst, tk)
        if r == 6:
            r = 10 + fk
            if fk == 2:                     # the index stop is a stop order: a stop sale's slippage
                d_ = max(R2(b * fst) - 2.0 * tk * TICKS(VOL5(Cn[4], r1 - 1, qty)) * tick, 0.0)
        out[i, 0], out[i, 1], out[i, 2], out[i, 3] = a, b, c_, d_
        er[i], xr[i], rs[i] = r0, r1, r
    return out, er, xr, rs


def charges(und: str, entry, exit_px, qty, days) -> np.ndarray:
    """Dated Zerodha charges (R51's COSTS) on arrays; days a list of dates (or one date)."""
    entry = np.atleast_1d(np.asarray(entry, float))
    exit_px = np.atleast_1d(np.asarray(exit_px, float))
    q = np.broadcast_to(np.asarray(qty, float), entry.shape)
    if isinstance(days, date):
        dn = np.full(entry.shape, days.toordinal() - E51.EPOCH, np.int64)
    else:
        dn = np.array([d.toordinal() - E51.EPOCH for d in days], np.int64)
    bse = und in K.BSE
    return E51.COSTS.charge(True, entry, q, dn, bse) + E51.COSTS.charge(False, exit_px, q, dn, bse)


# ================================================================================================ one index
@dataclass
class Und:
    und: str
    days: list                     # date per row (every index session from WARM_START)
    ix: np.ndarray                 # (nd, 375, 4) O H L C, NaN where missing
    exp: np.ndarray                # (nd,) bool: an expiry day
    lot: np.ndarray                # (nd,) int: the lot in force
    S: list                        # per day: (ncon, 5, 375) float64 contracts, or None (no chain)
    key: list                      # per day: {(side, strike): contract index}
    lines: dict = field(default_factory=dict)

    @property
    def nd(self) -> int:
        return len(self.days)

    def tradable(self, di: int) -> bool:
        return K.START <= self.days[di] <= K.END and self.S[di] is not None

    def sq(self, di: int) -> int:
        return K.SQ_ROW_EXPIRY if self.exp[di] else K.SQ_ROW


def make_und(und, days, ix, exp, lot, S, key) -> Und:
    U = Und(und, list(days), np.asarray(ix, float), np.asarray(exp, bool), np.asarray(lot, int), list(S), list(key))
    U.lines = lines_of(U.ix)
    return U


def exit_args(x: str) -> tuple:
    X = K.EXITS[x]
    steps = np.array(X["steps"], dtype=np.float64).reshape(-1, 2) if X["steps"] else np.zeros((0, 2))
    return int(X["line"]), bool(X["istop"]), float(X["rk"]), float(X["tgt"]), steps, int(X["tmax"])


def price_day(U: Und, di: int, tt, sides, money: int, line: str, x: str) -> dict:
    """Price entries (decision candles tt, sides 'C'/'P') of day row di at 1, 2 and 3 lots. Returns arrays: strike, ok
    (filled), ef, xr, reason, E, X (raw prices), net (n, 3)."""
    tt = np.asarray(tt, np.int64)
    n = len(tt)
    ixd = U.ix[di]
    step = K.STEP[U.und]
    strikes = np.full(n, np.nan)
    ci = np.full(n, -1, np.int64)
    for i in range(n):
        sp = ixd[tt[i], 3]
        if sp == sp:
            k = strike_for(sides[i], float(sp), step, money)
            strikes[i] = k
            ci[i] = U.key[di].get((sides[i], float(k)), -1)
    sg = np.array([1 if s == "C" else -1 for s in sides], np.int64)
    hh, dd = U.lines[line]
    ln, ist, rk, tgt, steps, tmax = exit_args(x)
    sq = U.sq(di)
    S = U.S[di] if U.S[di] is not None else np.zeros((0, 5, K.N))
    io, ih, il, ic = (np.ascontiguousarray(ixd[:, j]) for j in range(4))
    nets = np.zeros((n, len(K.LOTS)))
    base = None
    for li, lots in enumerate(K.LOTS):
        qty = float(U.lot[di] * lots)
        res, er, xr, rs = run_batch(S, ci, tt, sg, ih, il, ic, io, np.ascontiguousarray(hh[di]),
                                    np.ascontiguousarray(dd[di]), sq, ln, ist, rk, K.STOP_F, tgt, steps, tmax,
                                    K.TICK, qty, FB, FS, FST, TK)
        ok = rs != 0
        rE, rX = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
        ch = charges(U.und, rE, rX, qty, U.days[di]) if n else np.zeros(0)
        nets[:, li] = np.where(ok, (rX - rE) * qty - ch, 0.0)
        if base is None:
            base = dict(strike=strikes, ok=ok, ef=er, xr=xr, reason=rs, E=res[:, 0], X=res[:, 1])
        else:
            assert np.array_equal(xr[ok], base["xr"][ok]), "exit row must not depend on the lots"
    base["net"] = nets
    return base


@dataclass
class Trade:
    rule: str
    und: str
    day: date
    side: str
    t: int                         # decision candle
    strike: float
    fill: int
    exit: int
    reason: str
    lot: int
    entry_raw: float
    exit_raw: float
    net: tuple                     # Rs at 1, 2, 3 lots


def walk(U: Und, rule: str, rdef: tuple, lo: date, hi: date) -> tuple:
    """(trades, no-fills) of one rule set on one index: fresh signals only, one position at a time per index (calls and
    puts share it); a signal while busy is ignored; a signal with no fill leaves the index free and is counted."""
    line, reading, money, x = rdef
    hh, dd = U.lines[line]
    cC, cP = conditions(U.ix, hh, dd, reading)
    fC, fP = fresh(cC), fresh(cP)
    out, nofill = [], 0
    for di, d in enumerate(U.days):
        if not (lo <= d <= hi) or not U.tradable(di):
            continue
        ev = [(int(t), "C") for t in np.nonzero(fC[di, K.FIRST_T:K.LAST_T + 1])[0] + K.FIRST_T]
        ev += [(int(t), "P") for t in np.nonzero(fP[di, K.FIRST_T:K.LAST_T + 1])[0] + K.FIRST_T]
        ev.sort()
        busy = -1
        for t, side in ev:
            if t + 1 < busy:
                continue
            r = price_day(U, di, [t], [side], money, line, x)
            if not r["ok"][0]:
                nofill += 1
                continue
            busy = int(r["xr"][0])
            out.append(Trade(rule, U.und, d, side, t, float(r["strike"][0]), int(r["ef"][0]), busy,
                             REASON[int(r["reason"][0])], int(U.lot[di]), float(r["E"][0]), float(r["X"][0]),
                             tuple(float(v) for v in r["net"][0])))
    return out, nofill


def twin_table(U: Und, side: str, money: int, line: str, x: str, dis: list) -> np.ndarray:
    """(len(dis), LAST_T - FIRST_T + 1, 3): the net of an entry at every allowed decision candle of each day (0 = no
    fill or no candle), at 1 / 2 / 3 lots: the twins' draw table."""
    ts = np.arange(K.FIRST_T, K.LAST_T + 1)
    T = np.zeros((len(dis), len(ts), len(K.LOTS)))
    for r, di in enumerate(dis):
        ok = np.isfinite(U.ix[di, ts, 3])
        if not ok.any():
            continue
        sel = ts[ok]
        res = price_day(U, di, sel, [side] * len(sel), money, line, x)
        T[r, ok] = res["net"]
    return T
