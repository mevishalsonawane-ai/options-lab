"""R62 trade simulation (PREREG sections 5, 7, 8): the index option (R51's held-contract engine, an index-level stop /
target as a forced exit), the stock in cash (MIS intraday or delivery swing), and their random-entry twins.

Signal on the underlying, trade the option: every stop and target here is an index / stock level; the option premium is
only the price paid and received.
"""
from __future__ import annotations

import json
import sys

import numpy as np

import r62cfg as K

K51 = K.point_r51()
import engine as E  # noqa: E402  (R51's engine)

try:
    from numba import njit
except ImportError:                               # pragma: no cover
    def njit(*a, **k):
        return (lambda f: f) if not (a and callable(a[0])) else a[0]

TICK = 0.05
WHY = {0: "squareoff", 1: "stop", 2: "target"}


# ------------------------------------------------------------------------------------------------ underlying exits
@njit(cache=True)
def scan_exit(h, l, f0, fend, side, stop, tgt):
    """First minute in [f0, fend) whose low (long) reaches the stop or high reaches the target, on the underlying.
    Returns (the row the position is sold at: that minute + 1, capped at fend; why 1 stop / 2 target / 0 none).
    A minute reaching both counts as the stop. Missing minutes trigger nothing."""
    for m in range(f0, fend):
        hm, lm = h[m], l[m]
        if not (hm == hm and lm == lm):
            continue
        if side > 0:
            if lm <= stop:
                return min(m + 1, fend), 1
            if hm >= tgt:
                return min(m + 1, fend), 2
        else:
            if hm >= stop:
                return min(m + 1, fend), 1
            if lm <= tgt:
                return min(m + 1, fend), 2
    return fend, 0


@njit(cache=True)
def cash_walk(o, h, l, c, f0, fend, side, stop, tgt):
    """The stock held from the open of f0: a resting SL-M (filled at the worse of the stop and the minute's open) and a
    limit target (the better of the target and the open); else out at the open of fend (the last close before it if
    that minute is missing). Returns (raw price, why, row)."""
    for m in range(f0, fend):
        if not (l[m] == l[m]):
            continue
        op = o[m] if o[m] == o[m] else c[m]
        if side > 0:
            if l[m] <= stop:
                return min(stop, op), 1, m
            if h[m] >= tgt:
                return max(tgt, op), 2, m
        else:
            if h[m] >= stop:
                return max(stop, op), 1, m
            if l[m] <= tgt:
                return min(tgt, op), 2, m
    if o[fend] == o[fend]:
        return o[fend], 0, fend
    for m in range(fend - 1, f0 - 1, -1):
        if c[m] == c[m]:
            return c[m], 0, fend
    return np.nan, 0, fend


def target_level(kind: str, ref: float, stop: float, st_tgt: float, side: int) -> float:
    return ref + side * 2.0 * abs(ref - stop) if kind == "2r" else st_tgt


# ------------------------------------------------------------------------------------------------ index option
def _first_print(con, e, sq):
    for j in range(e, min(e + 3, sq)):
        if np.isfinite(con[0, j]) and con[0, j] > 0:
            return j
    return -1


def index_trade(und: str, D, side: int, s: int, stop: float, tgt: float | None = None, kind: str = "level",
                st_tgt: float = np.nan) -> dict | None:
    """One trade: buy the 1-ITM CE (side +1) / PE (-1) chosen at signal row s, filled from s + 1 (R51: the first print in
    that minute and the next 2), sold at the next minute's open after the index reaches stop / target, else at the
    square-off (15:15, 15:05 on expiry days). kind '2r' sets the target from the index open at the fill minute."""
    ex = E.Exits(sq_row=K.SQ_IDX)
    sq = E.sq_row(D, ex)
    sd = "C" if side > 0 else "P"
    k = E.strike_for(D, und, sd, s, K.ITM_MONEY)
    if k is None:
        return None
    con = D.contract(sd, k)
    if con is None:
        return None
    ef = _first_print(con, s + 1, sq)
    if ef < 0:
        return None
    ref = D.idx[0, ef] if np.isfinite(D.idx[0, ef]) else D.idx[3, ef - 1]
    if not np.isfinite(ref):
        return None
    if kind != "level":
        tgt = target_level(kind, float(ref), stop, st_tgt, side)
    fx, why = scan_exit(D.idx[1], D.idx[2], ef, sq, side, stop, tgt)
    qty = float(K51.lot(und, D.d))
    C64 = con.astype(np.float64)
    out = {}
    for tag, slip, mult in (("", E.SLIP1, 1.0), ("2", E.SLIP2, 2.0)):
        res = E.simulate(C64, ef, sq, fx if fx < sq else -1, -1.0, -1.0, -1.0, 0.0, 0.0, E.NOLOCK, -1, -1.0, -1.0,
                         K51.TICK, qty, *slip)
        if res[6] == E.R_NOFILL:
            return None
        rE, rX = res[2], res[3]
        ch = float(E.charges(und, rE, rX, qty, D.d)[0]) * mult
        out["net" + tag] = (rX - rE) * qty - ch
        if not tag:
            out.update(E=float(res[0]), X=float(res[1]), rE=float(rE), rX=float(rX), er=int(res[4]), xr=int(res[5]),
                       why_opt=E.REASONS[int(res[6])], charges=ch)
    xr = out["xr"]
    px_x = D.idx[0, xr] if xr < K.NROWS and np.isfinite(D.idx[0, xr]) else _last(D.idx[3], xr)
    risk = abs(ref - stop)
    out.update(und=und, day=D.d, side=side, strike=float(k), qty=qty, ref=float(ref), stop=float(stop), tgt=float(tgt),
               why=WHY[why] if fx < sq else "squareoff", r_mult=float(side * (px_x - ref) / risk) if risk > 0 else np.nan,
               risk_pts=float(risk))
    return out


def _last(c, r):
    seg = c[:max(r, 1)]
    seg = seg[np.isfinite(seg)]
    return float(seg[-1]) if len(seg) else np.nan


@njit(cache=True)
def _twin_index(S, ci_row, io, ih, il, ic, rows, side, sp, tp, sq, tick, qty, fb, fs, fst, tk):
    """Twins of one trade: rows (n, tries) random fill rows. Returns (n, 2) real entry / exit prices (NaN: no fill)."""
    n, tries = rows.shape
    out = np.full((n, 2), np.nan)
    for i in range(n):
        for k in range(tries):
            e = rows[i, k]
            c = ci_row[e - 1]
            if c < 0:
                continue
            con = S[c]
            ef = -1
            for j in range(e, min(e + 3, sq)):
                if con[0, j] == con[0, j] and con[0, j] > 0:
                    ef = j
                    break
            if ef < 0:
                continue
            ref = io[ef] if io[ef] == io[ef] else ic[ef - 1]
            if not (ref == ref):
                continue
            stop = ref * (1.0 - side * sp)
            tgt = ref * (1.0 + side * tp)
            fx, why = scan_exit(ih, il, ef, sq, side, stop, tgt)
            r = E.simulate(con, ef, sq, fx if fx < sq else -1, -1.0, -1.0, -1.0, 0.0, 0.0, np.zeros((0, 2)), -1, -1.0,
                           -1.0, tick, qty, fb, fs, fst, tk)
            if r[6] == 0:
                continue
            out[i, 0], out[i, 1] = r[2], r[3]
            break
    return out


def twin_index_nets(t: dict, D, n: int, rng, tries: int = 5) -> np.ndarray:
    """Random-entry twins of one index trade (PREREG 8): same index, session and side, a random fill row in 09:25-14:30,
    the same stop / target distances in % of the index, 1-ITM at the row before, same square-off. No fill in 5 draws
    adds 0."""
    und, side = t["und"], int(t["side"])
    sd = "C" if side > 0 else "P"
    st = E.stack(und, D)
    ex = E.Exits(sq_row=K.SQ_IDX)
    sq = E.sq_row(D, ex)
    ci_row = np.full(K.NROWS, -1, np.int64)
    for r in range(K.row(9, 24), K.row(14, 30)):
        ci_row[r] = st.idx(sd, E.strike_for(D, und, sd, r, K.ITM_MONEY))
    sp = side * (t["ref"] - t["stop"]) / t["ref"]
    tp = side * (t["tgt"] - t["ref"]) / t["ref"]
    rows = rng.integers(K.row(9, 25), K.row(14, 30) + 1, size=(n, tries))
    qty = float(K51.lot(und, D.d))
    res = _twin_index(st.S, ci_row, D.idx[0], D.idx[1], D.idx[2], D.idx[3], rows.astype(np.int64), side, sp, tp, sq,
                      K51.TICK, qty, *E.SLIP1)
    ok = np.isfinite(res[:, 0])
    rE, rX = np.nan_to_num(res[:, 0]), np.nan_to_num(res[:, 1])
    ch = E.charges(und, rE, rX, qty, D.d)
    return np.where(ok, (rX - rE) * qty - ch, 0.0)


# ------------------------------------------------------------------------------------------------ stock cash
def _r58():
    if str(K.R58) not in sys.path:
        sys.path.insert(0, str(K.R58))
    import r58engine
    return r58engine


def latest_lot(sym: str) -> int:
    """The stock's lot in the latest bhavcopy R58 read (results/lots.json), on today's (split-adjusted) share scale."""
    x = json.loads((K.R58 / "results" / "lots.json").read_text())["lots"][sym]
    best = (None, 0)
    for _exp, rows in x.items():
        for d, lot in rows:
            if best[0] is None or d > best[0]:
                best = (d, lot)
    return int(best[1])


def slip(px: float, stop: bool, mult: float) -> float:
    return max(TICK, px * 1e-4) * (2 if stop else 1) * mult


def cash_trade(sym: str, g: dict, di: int, s: int, side: int, stop: float, kind: str, st_tgt: float, qty: int,
               swing: bool = False, last_di: int | None = None) -> dict | None:
    """The stock bought (sold short) at the open after signal row s of session di; MIS intraday (out 15:10) or, swing,
    delivery held to 15:10 of session min(di + 4, last_di)."""
    M = g["M"]
    nd = M.shape[0]
    f0 = di * K.NROWS + s + 1
    dl = di if not swing else min(di + K.SWING_HOLD - 1, nd - 1 if last_di is None else last_di)
    fend = dl * K.NROWS + K.SQ_STK
    flat = M.reshape(-1, 4)
    o, h, l, c = (np.ascontiguousarray(flat[:, i]) for i in range(4))
    if not np.isfinite(o[f0]):
        return None
    ref = float(o[f0])
    tgt = target_level(kind, ref, stop, st_tgt, side)
    if (side > 0 and not stop < ref) or (side < 0 and not stop > ref):
        return None
    raw, why, xr = cash_walk(o, h, l, c, f0, fend, side, stop, tgt)
    if not np.isfinite(raw):
        return None
    eng = _r58()
    d_in = g["days"][di]
    d_out = g["days"][xr // K.NROWS]
    out = dict(sym=sym, day=d_in, exit_day=d_out, side=side, s=s, ref=ref, stop=stop, tgt=tgt, raw_exit=float(raw),
               why=WHY[int(why)], qty=qty, xf=int(xr), held=int(xr // K.NROWS - di + 1),
               r_mult=float(side * (raw - ref) / abs(ref - stop)), risk_pct=abs(ref - stop) / ref)
    for tag, mult in (("", 1.0), ("2", 2.0)):
        e = ref + side * slip(ref, False, mult)
        x = raw - side * slip(raw, why == 1, mult)
        gross = (x - e) * qty * side
        if side > 0:
            ch = eng.cash_charge(True, e, qty, swing, mult) + eng.cash_charge(False, x, qty, swing, mult)
        else:
            ch = eng.cash_charge(False, e, qty, swing, mult) + eng.cash_charge(True, x, qty, swing, mult)
        out["net" + tag] = gross - ch
        if not tag:
            out["charges"] = ch
            # 1% of Rs 1 lakh at risk (information only): shares = 1,000 / risk, capped at 5x leverage
            q1 = int(min(K.RISK_CAPITAL * K.RISK_FRAC / abs(ref - stop), K.MIS_LEVERAGE * K.RISK_CAPITAL / ref))
            if q1 > 0:
                ch1 = (eng.cash_charge(side > 0, e, q1, swing) + eng.cash_charge(side < 0, x, q1, swing))
                out["net_risk1"] = (x - e) * q1 * side - ch1
            else:
                out["net_risk1"] = 0.0
            out["qty_risk1"] = q1
    return out


@njit(cache=True)
def _twin_cash(o, h, l, c, rows, base, fend_off, side, sp, tp):
    n, tries = rows.shape
    out = np.full((n, 3), np.nan)
    for i in range(n):
        for k in range(tries):
            f0 = base + rows[i, k]
            if not (o[f0] == o[f0]):
                continue
            ref = o[f0]
            stop = ref * (1.0 - side * sp)
            tgt = ref * (1.0 + side * tp)
            raw, why, xr = cash_walk(o, h, l, c, f0, base + fend_off, side, stop, tgt)
            if not (raw == raw):
                continue
            out[i, 0], out[i, 1], out[i, 2] = ref, raw, why
            break
    return out


def twin_cash_nets(t: dict, g: dict, di: int, n: int, rng, swing: bool, last_di: int, tries: int = 5) -> np.ndarray:
    eng = _r58()
    M = g["M"].reshape(-1, 4)
    o, h, l, c = (np.ascontiguousarray(M[:, i]) for i in range(4))
    side = int(t["side"])
    dl = di if not swing else min(di + K.SWING_HOLD - 1, last_di)
    fend_off = (dl - di) * K.NROWS + K.SQ_STK
    sp = side * (t["ref"] - t["stop"]) / t["ref"]
    tp = side * (t["tgt"] - t["ref"]) / t["ref"]
    rows = rng.integers(K.row(9, 25), K.row(14, 30) + 1, size=(n, tries)).astype(np.int64)
    res = _twin_cash(o, h, l, c, rows, di * K.NROWS, fend_off, side, sp, tp)
    qty = t["qty"]
    nets = np.zeros(n)
    for i in range(n):
        ref, raw, why = res[i]
        if not np.isfinite(ref):
            continue
        e = ref + side * slip(ref, False, 1.0)
        x = raw - side * slip(raw, why == 1, 1.0)
        ch = (eng.cash_charge(side > 0, e, qty, swing) + eng.cash_charge(side < 0, x, qty, swing))
        nets[i] = (x - e) * qty * side - ch
    return nets
