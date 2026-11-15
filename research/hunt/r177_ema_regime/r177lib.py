"""R177-EMA engine (PREREG.md section 2): 15-minute and 1-hour index bars, the signals and filters, the index-based
exits, the option fills and the walk (one position a side).

Reused, unchanged: R44's EMA / ADX / crossover (research/hunt/r44/ind.py), R51's fill helpers, slippage and dated
charges (research/hunt/r51/engine.py: R17 "liq" fills, Costs("dated")), R171's release loader and strike rule.

Nothing here reads a row after the decision: a signal on 15-minute bar b uses bars 0..b and 1-hour bars completed by
b's close; the exit is found minute by minute from the fill on. test_r177.py's look-ahead guard checks both.
"""
from __future__ import annotations

import importlib.util
import math
import sys
from dataclasses import dataclass, field
from datetime import date

import numpy as np

import r177cfg as K


def _load(name: str, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


IND = _load("r44ind", K.R44 / "ind.py")            # ema, adx, cross_up, cross_dn (R44)
E51 = _load("r51engine", K.R51 / "engine.py")      # _r2, _ticks, _vol5, charges, SLIP (R51 / R17)
FB, FS, FST, TK = E51.SLIP                          # buy x1.0005, market sell x0.9995, stop sell x0.999, 1 tick unit
O, H, L, C, V = range(5)

R_CROSS, R_STOP, R_TARGET, R_SQ = "cross", "stop", "target", "squareoff"


# ================================================================================================ indicators
def atr(h, l, c, n: int = K.ATR_N) -> np.ndarray:
    """Wilder ATR: TR[0] = h - l, TR[t] = max(h-l, |h-c[t-1]|, |l-c[t-1]|); the first value (t = n-1) is the mean of
    TR[0..n-1], then (prev x (n-1) + TR) / n. NaN before n-1."""
    h, l, c = (np.asarray(x, float) for x in (h, l, c))
    T = len(c)
    out = np.full(T, np.nan)
    if T < n:
        return out
    tr = np.empty(T)
    tr[0] = h[0] - l[0]
    if T > 1:
        pc = c[:-1]
        tr[1:] = np.maximum.reduce([h[1:] - l[1:], np.abs(h[1:] - pc), np.abs(l[1:] - pc)])
    a = tr[:n].mean()
    out[n - 1] = a
    for t in range(n, T):
        a = (a * (n - 1) + tr[t]) / n
        out[t] = a
    return out


def bars(ix: np.ndarray, width: int) -> np.ndarray:
    """(nd, 375, 4) minute O H L C -> (nd, ceil(375/width), 4) bars anchored at 09:15; NaN where a bar has no minute."""
    nd = ix.shape[0]
    nb = -(-K.N // width)
    out = np.full((nd, nb, 4), np.nan)
    for b in range(nb):
        seg = ix[:, width * b: width * (b + 1)]
        ok = np.isfinite(seg[:, :, 3])
        has = ok.any(axis=1)
        if not has.any():
            continue
        first = np.argmax(ok, axis=1)
        last = seg.shape[1] - 1 - np.argmax(ok[:, ::-1], axis=1)
        r = np.arange(nd)
        hi = np.where(ok, seg[:, :, 1], -np.inf).max(axis=1)
        lo = np.where(ok, seg[:, :, 2], np.inf).min(axis=1)
        out[has, b, 0] = seg[r, first, 0][has]
        out[has, b, 1] = hi[has]
        out[has, b, 2] = lo[has]
        out[has, b, 3] = seg[r, last, 3][has]
    return out


def _scatter(valid: np.ndarray, vals: np.ndarray, fill=np.nan) -> np.ndarray:
    out = np.full(valid.shape, fill, dtype=np.asarray(vals).dtype if fill is not np.nan else float)
    out[valid] = vals
    return out


def h1_slot_index(valid_h1: np.ndarray) -> np.ndarray:
    """For every 15-minute slot (d, b): the position, in the compressed series of existing 1-hour bars, of the last
    1-hour bar COMPLETED by the close of b (1-hour bar k completes with 15-minute bar min(4k + 3, 24)). -1 if none."""
    nd = valid_h1.shape[0]
    done = np.array([min(4 * k + 3, K.NB - 1) for k in range(K.NH)])
    keys = (np.arange(nd)[:, None] * K.NB + done[None, :])[valid_h1]        # completion slot of each existing bar
    slots = np.arange(nd * K.NB)
    pos = np.searchsorted(keys, slots, side="right") - 1
    return pos.reshape(nd, K.NB)


def session_vwap(ix: np.ndarray, w: np.ndarray) -> np.ndarray:
    """(nd, 25): the session VWAP at each 15-minute bar's last minute, of the typical price (H + L + C) / 3 weighted by
    w (nd, 375); a minute with no index bar has weight 0. NaN where the weight sum is 0."""
    tp = ix[:, :, 1:4].mean(axis=2)
    ok = np.isfinite(tp) & np.isfinite(w) & (w > 0)
    ww = np.where(ok, w, 0.0)
    num = np.cumsum(np.where(ok, tp * ww, 0.0), axis=1)
    den = np.cumsum(ww, axis=1)
    rows = np.minimum(np.arange(K.NB) * K.BAR + K.BAR - 1, K.N - 1)
    with np.errstate(invalid="ignore", divide="ignore"):
        v = num[:, rows] / den[:, rows]
    return np.where(den[:, rows] > 0, v, np.nan)


def atm_strike(spot: float, step: float) -> float:
    return math.floor(spot / step + 0.5) * step


def strike_for(side: str, spot: float, step: float, money: int) -> float:
    k = atm_strike(spot, step)
    return k - money * step if side == "C" else k + money * step


# ================================================================================================ one index
@dataclass
class Index:
    und: str
    days: list                       # date per row
    ix: np.ndarray                   # (nd, 375, 4)
    chainvol: np.ndarray             # (nd, 375) total option-chain volume a minute
    expiry: set
    lot: list
    opts: list                       # per day: {(strike, 'C'|'P'): (375, 5)}
    f: dict = field(default_factory=dict)   # feature arrays (nd, 25)

    @property
    def nd(self) -> int:
        return len(self.days)


def features(I: Index) -> dict:
    """Every per-slot input of the signals and filters, (nd, 25) each, from data up to that slot's close only."""
    b15 = bars(I.ix, K.BAR)[:, :K.NB]
    v15 = np.isfinite(b15[:, :, 3])
    h, l, c = b15[v15][:, 1], b15[v15][:, 2], b15[v15][:, 3]
    e9, e21 = IND.ema(c, K.EMA_FAST), IND.ema(c, K.EMA_SLOW)
    f = dict(close=b15[:, :, 3], valid=v15)
    f["up"] = _scatter(v15, IND.cross_up(e9, e21), False).astype(bool)
    f["dn"] = _scatter(v15, IND.cross_dn(e9, e21), False).astype(bool)
    f["adx"] = _scatter(v15, IND.adx(h, l, c, K.ADX_N))
    f["atr"] = _scatter(v15, atr(h, l, c, K.ATR_N))
    # sequence number of each existing bar (warm-up: the series' first bars give no signal)
    f["seq"] = _scatter(v15, np.arange(int(v15.sum()), dtype=float))
    b60 = bars(I.ix, 60)
    v60 = np.isfinite(b60[:, :, 3])
    hc = b60[v60][:, 3]
    pos = h1_slot_index(v60)
    he21, he9 = IND.ema(hc, K.H1_EMA_SLOW), IND.ema(hc, K.H1_EMA_FAST)
    okp = pos >= 0
    for name, arr in (("h1c", hc), ("h1e21", he21), ("h1e9", he9)):
        out = np.full(pos.shape, np.nan)
        out[okp] = arr[pos[okp]]
        f[name] = out
    f["h1n"] = np.where(okp, pos + 1, 0)          # how many 1-hour bars exist by then (warm-up)
    f["vwap"] = session_vwap(I.ix, I.chainvol)
    f["twap"] = session_vwap(I.ix, np.where(np.isfinite(I.ix[:, :, 3]), 1.0, 0.0))
    orh = np.nanmax(b15[:, :2, 1], axis=1) if b15.shape[0] else np.zeros(0)
    orl = np.nanmin(b15[:, :2, 2], axis=1) if b15.shape[0] else np.zeros(0)
    f["orh"], f["orl"] = orh, orl
    return f


def warm_ok(f: dict, di: int, b: int, day_no: int) -> bool:
    return day_no >= K.WARM_SESSIONS and f["seq"][di, b] >= 100 and f["h1n"][di, b] >= 2 * K.H1_EMA_SLOW


def decide(f: dict, di: int, b: int, side: str, rule: str) -> bool:
    """The rule's entry decision at the close of 15-minute bar b of day row di (side 'C' long, 'P' mirror short)."""
    R = K.RULES[rule]
    if not (0 <= b <= K.LAST_SIGNAL_BAR) or not f["valid"][di, b]:
        return False
    if not (f["up"] if side == "C" else f["dn"])[di, b]:
        return False
    sgn = 1.0 if side == "C" else -1.0
    c = f["close"][di, b]
    if R["adx"]:
        a = f["adx"][di, b]
        if not (a == a and a > K.ADX_MIN):
            return False
    if R["trend"] is not None:
        if R["trend"] == "A":                          # H1 close vs H1 EMA 21 (primary)
            hc, ref = f["h1c"][di, b], f["h1e21"][di, b]
        else:                                          # H1 EMA 9 vs H1 EMA 21 (ALL-H1B)
            hc, ref = f["h1e9"][di, b], f["h1e21"][di, b]
        if not (hc == hc and ref == ref and sgn * (hc - ref) > 0):
            return False
    if R["vwap"] is not None:
        vw = f[R["vwap"]][di, b]
        if not (vw == vw and sgn * (c - vw) > 0):
            return False
    if R["orange"]:
        if b < 1:
            return False
        lvl = f["orh"][di] if side == "C" else f["orl"][di]
        if not (lvl == lvl and sgn * (c - lvl) > 0):
            return False
    if (R["stop"] or R["target"]) and not (f["atr"][di, b] == f["atr"][di, b]):
        return False
    return True


def index_exit(ix: np.ndarray, up: np.ndarray, dn: np.ndarray, b: int, side: str, fill: int, stop: float,
               target: float, sq: int = K.SQ_ROW) -> tuple:
    """(exit_row, reason) from the index alone. ix (375, 4) of the day; up / dn (25,) the day's crossovers; b the
    signal bar; fill the option's fill row. Stop (index low <= stop for a call, high >= stop for a put) and target
    (high >= target / low <= target) sell at the NEXT minute's open; the opposite crossover on bar b' > b sells at
    row 15(b' + 1); square-off at sq. Ties: stop, then target, then the cross, then the square-off. stop / target NaN =
    not used."""
    xs = xt = 10 ** 6
    lo, hi = ix[:, 2], ix[:, 1]
    if stop == stop or target == target:
        for m in range(fill, sq):
            a, z = lo[m], hi[m]
            if not (a == a and z == z):
                continue
            hit_s = stop == stop and ((a <= stop) if side == "C" else (z >= stop))
            hit_t = target == target and ((z >= target) if side == "C" else (a <= target))
            if hit_s:
                xs = m + 1
                break
            if hit_t:
                xt = m + 1
                break
    opp = dn if side == "C" else up
    xc = 10 ** 6
    for bb in range(b + 1, K.NB):
        if opp[bb]:
            xc = K.BAR * (bb + 1)
            break
    x = min(xs, xt, xc, sq)
    if xs == x:
        return x, R_STOP
    if xt == x:
        return x, R_TARGET
    if xc == x:
        return x, R_CROSS
    return sq, R_SQ


def fill_row(A: np.ndarray, e: int, sq: int = K.SQ_ROW) -> int:
    """The option's fill: its open at row e, or the first traded minute up to 2 minutes later (R51); -1 none."""
    o = A[:, O]
    for j in range(e, min(e + K.FILL_SLACK, sq)):
        if o[j] == o[j] and o[j] > 0:
            return j
    return -1


def exit_px(A: np.ndarray, x: int) -> float:
    """The option's price at exit row x: its open; else the first traded open within 3 minutes; else the last close
    before x (R51)."""
    o, c = A[:, O], A[:, C]
    if x < K.N and o[x] == o[x]:
        return float(o[x])
    for j in range(x + 1, min(x + 4, K.N)):
        if o[j] == o[j]:
            return float(o[j])
    for j in range(min(x, K.N) - 1, -1, -1):
        if c[j] == c[j]:
            return float(c[j])
    return float("nan")


def price(A: np.ndarray, ef: int, x: int, reason: str, qty: float, d: date, und: str) -> tuple:
    """(raw entry, raw exit, real entry, real exit, net) of one round trip at qty: R17 fills and dated charges."""
    E_ = float(A[ef, O])
    v = A[:, V]
    re = E51._r2(E_ * FB) + TK * E51._ticks(E51._vol5(v, ef - 1, qty)) * K.TICK
    X = exit_px(A, x)
    if reason == R_STOP:
        rx = max(E51._r2(X * FST) - 2.0 * TK * E51._ticks(E51._vol5(v, x - 1, qty)) * K.TICK, 0.0)
    else:
        rx = max(E51._r2(X * FS) - TK * E51._ticks(E51._vol5(v, x - 1, qty)) * K.TICK, 0.0)
    ch = float(E51.charges(und, re, rx, qty, d)[0])
    return E_, X, re, rx, (rx - re) * qty - ch


@dataclass
class Trade:
    rule: str
    und: str
    day: date
    side: str
    bar: int
    strike: float
    fill: int
    exit: int
    reason: str
    lot: int
    entry_raw: float
    exit_raw: float
    net: tuple                       # Rs at 1, 2, 3 lots


def trade_at(I: Index, di: int, b: int, side: str, rule: str):
    """Price one entry at signal bar b (no filters checked here). None if no fill. Used by the walk and the twins."""
    f = I.f
    stop_on, tgt_on, money = K.exit_mode(rule)
    spot = f["close"][di, b]
    if not spot == spot:
        return None
    k = strike_for(side, float(spot), K.STEP[I.und], money)
    A = I.opts[di].get((k, side))
    if A is None:
        return None
    e = K.BAR * (b + 1)
    ef = fill_row(A, e)
    if ef < 0:
        return None
    ixd = I.ix[di]
    ent = ixd[ef, 0] if ixd[ef, 0] == ixd[ef, 0] else spot
    sgn = 1.0 if side == "C" else -1.0
    a = f["atr"][di, b]
    stop = ent - sgn * K.ATR_MULT * a if stop_on else float("nan")
    target = ent + sgn * K.R_TARGET * K.ATR_MULT * a if tgt_on else float("nan")
    x, why = index_exit(ixd, f["up"][di], f["dn"][di], b, side, ef, stop, target)
    nets = []
    raw = None
    for n in K.LOTS:
        qty = float(I.lot[di] * n)
        raw = price(A, ef, x, why, qty, I.days[di], I.und)
        nets.append(raw[4])
    return Trade(rule, I.und, I.days[di], side, b, k, ef, x, why, int(I.lot[di]), raw[0], raw[1], tuple(nets))


def tradable(I: Index, di: int) -> bool:
    d = I.days[di]
    return K.START <= d <= K.END and d not in I.expiry and di >= K.WARM_SESSIONS


def walk(I: Index, rule: str, lo: date = K.START, hi: date = K.END) -> list:
    """(trades, no-fills) of the rule on one index: one open position a side, a signal while that side is open is
    ignored; a signal with no fill (strike not stored, no trade in the slack) leaves the side free and is counted."""
    out = []
    nofill = 0
    for di, d in enumerate(I.days):
        if not (lo <= d <= hi) or not tradable(I, di):
            continue
        busy = {"C": -1, "P": -1}                     # exit row of the open position (sold at its open)
        for b in range(K.LAST_SIGNAL_BAR + 1):
            for side in ("C", "P"):
                e = K.BAR * (b + 1)
                if busy[side] > e:
                    continue
                if not warm_ok(I.f, di, b, di) or not decide(I.f, di, b, side, rule):
                    continue
                t = trade_at(I, di, b, side, rule)
                if t is None:
                    nofill += 1
                    continue
                busy[side] = t.exit
                out.append(t)
    return out, nofill


# ================================================================================================ build from R171's store
def from_days(und: str, days: list) -> Index:
    """An Index from R171's Day objects (ix (375, 4), opts {(strike, side): (375, 5)}, exp, lot)."""
    days = sorted(days, key=lambda x: x.day)
    nd = len(days)
    ix = np.stack([d.ix for d in days]) if nd else np.zeros((0, K.N, 4))
    cv = np.zeros((nd, K.N))
    for i, d in enumerate(days):
        for A in d.opts.values():
            cv[i] += np.nan_to_num(A[:, V])
    exps = {d.exp for d in days if d.exp is not None}
    I = Index(und, [d.day for d in days], ix, cv, exps, [d.lot for d in days], [d.opts for d in days])
    I.f = features(I)
    return I
