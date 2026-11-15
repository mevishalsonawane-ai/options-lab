"""R180 engine (PREREG.md sections 1-2): the manual's exits X1 (Book 1) and E3 (Book 2) on ONE held option contract.

Fills and charges are R51's (hunt/r51/engine.py, reused): R17 "liq" fills (`_ticks`, `_vol5`, `_r2`; SLIP1 / SLIP2) and
Costs('dated') (`charges`). Two entry modes:
    close  the manual's: Book 1 buys at the close of minute em (the minute after the signal bar), Book 2 at the close of
           the trigger minute t; the walk starts at the next minute; Book 2's stop / lock sell at that minute's close
    open   the repo's standard (sensitivity): buy at the open of the minute after the signal (Book 1: em; Book 2: t + 1),
           the walk starts in the fill minute; Book 2's stop / lock sell at the next minute's open
Reasons: 1 stop (premium or index), 2 lock, 4 time / end of day, 7 missing print (last known close), 0 no fill.
Callers import r180cfg first (paths, stores).
"""
from __future__ import annotations

import numpy as np
from numba import njit

import r180cfg as K
import engine as E                       # hunt/r51/engine.py

_ticks, _r2, _vol5 = E._ticks, E._r2, E._vol5
SLIP1, SLIP2 = E.SLIP1, E.SLIP2
REASONS = {0: "nofill", 1: "stop", 2: "lock", 4: "time", 7: "missing"}


@njit(cache=True)
def _fill_entry(o, c, v, e, mode_open, qty, tick, fb, tk):
    """(raw price, real price, fill minute) or (nan, nan, -1): the first traded minute e .. e+2."""
    for j in range(e, min(e + 3, o.shape[0])):
        px = o[j] if mode_open else c[j]
        if px == px and px > 0:
            vr = j - 1 if mode_open else j            # the volume known at the fill
            return px, _r2(px * fb) + tk * _ticks(_vol5(v, vr, qty)) * tick, j
    return np.nan, np.nan, -1


@njit(cache=True)
def walk_x1(C, e, end, mode_open, qty, tick, stop_f, lk_trig, lk_keep, fb, fs, fst, tk):
    """Book 1 X1. C (5, 375); e = em; end = min(em + 60, 365 or 350 on expiry days). The manual's levels are on the
    filled price: stop = (1 - stop_f) x entry; once the PRIOR peak >= (1 + lk_trig) x entry, exit when the low <=
    entry + lk_keep x (peak - entry); peak from the highs; else out at the close of `end`.
    Returns (raw entry, raw exit, real entry, real exit, fill minute, exit minute, reason)."""
    o, h, l, c, v = C[0], C[1], C[2], C[3], C[4]
    E_, real_e, ef = _fill_entry(o, c, v, e, mode_open, qty, tick, fb, tk)
    if ef < 0 or ef > end:
        return np.nan, np.nan, np.nan, np.nan, -1, -1, 0
    stop = real_e * (1.0 - stop_f)
    peak = real_e
    last = real_e
    m = ef if mode_open else ef + 1
    while m <= end:
        if not (l[m] == l[m]):
            m += 1
            continue
        if l[m] <= stop:
            px = o[m] if (o[m] == o[m] and o[m] < stop) else stop
            rx = max(_r2(px * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
            return E_, px, real_e, rx, ef, m, 1
        if peak >= (1.0 + lk_trig) * real_e:
            lv = real_e + lk_keep * (peak - real_e)
            if l[m] <= lv:
                px = o[m] if (o[m] == o[m] and o[m] < lv) else lv
                rx = max(_r2(px * fst) - 2.0 * tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
                return E_, px, real_e, rx, ef, m, 2
        if h[m] > peak:
            peak = h[m]
        last = c[m]
        if m == end:
            rx = max(_r2(c[m] * fs) - tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
            return E_, c[m], real_e, rx, ef, m, 4
        m += 1
    rx = max(_r2(last * fs) - tk * _ticks(_vol5(v, end - 1, qty)) * tick, 0.0)
    return E_, last, real_e, rx, ef, end, 7


@njit(cache=True)
def walk_e3(C, I, e, end, side, ref, stop_pts, mode_open, qty, tick, lk_trig, lk_keep, fb, fs, fst, tk):
    """Book 2 E3. C (5, 375) the option; I (4, 375) the index O H L C; e = the entry minute (close mode: t; open mode:
    t + 1); end = 364 (350 on expiry days). Each minute: (a) the index low <= ref - stop_pts (side +1) / high >= ref +
    stop_pts (side -1) -> stop; (b) cur = (close - raw entry) x qty, peak = max(peak, cur); peak >= lk_trig and cur <=
    lk_keep x peak -> lock; (c) else out at the close of `end`. Close mode sells at the minute's close, open mode at the
    next minute's open. Same returns as walk_x1."""
    o, h, l, c, v = C[0], C[1], C[2], C[3], C[4]
    E_, real_e, ef = _fill_entry(o, c, v, e, mode_open, qty, tick, fb, tk)
    if ef < 0 or ef > end:
        return np.nan, np.nan, np.nan, np.nan, -1, -1, 0
    lvl = ref - side * stop_pts
    peak = -1e18
    last = E_
    m = ef if mode_open else ef + 1
    while m <= end:
        why = 0
        il, ih = I[2, m], I[1, m]
        if side > 0 and il == il and il <= lvl:
            why = 1
        elif side < 0 and ih == ih and ih >= lvl:
            why = 1
        if why == 0 and c[m] == c[m]:
            cur = (c[m] - E_) * qty
            if cur > peak:
                peak = cur
            if peak >= lk_trig and cur <= lk_keep * peak:
                why = 2
        if c[m] == c[m]:
            last = c[m]
        if why > 0:
            if mode_open:
                if m + 1 < o.shape[0] and o[m + 1] == o[m + 1]:
                    px = o[m + 1]
                    xm = m + 1
                else:
                    px = last
                    xm = m
            else:
                px = last
                xm = m
            mult = fst if why == 1 else fs
            ntk = 2.0 if why == 1 else 1.0
            rx = max(_r2(px * mult) - ntk * tk * _ticks(_vol5(v, xm - 1, qty)) * tick, 0.0)
            return E_, px, real_e, rx, ef, xm, why
        if m == end:
            rs = 4 if c[m] == c[m] else 7
            rx = max(_r2(last * fs) - tk * _ticks(_vol5(v, m - 1, qty)) * tick, 0.0)
            return E_, last, real_e, rx, ef, m, rs
        m += 1
    rx = max(_r2(last * fs) - tk * _ticks(_vol5(v, end - 1, qty)) * tick, 0.0)
    return E_, last, real_e, rx, ef, end, 7


# ------------------------------------------------------------------------------------------------ pricing one candidate
def end_row(book: str, em: int, expiry: bool) -> int:
    eod = K.B1_EOD if book == "B1" else K.B2_EOD
    if expiry:
        eod = min(eod, K.EXPIRY_EOD)
    return min(em + K.B1_HOLD, eod) if book == "B1" else eod


def strike_at(D, und: str, row: int):
    """The ATM strike at `row`: the offset-0 strike of that minute, else the index close rounded to the step."""
    k = D.atm[row] if D.atm is not None else np.nan
    if not np.isfinite(k):
        px = D.idx[3, row]
        if not np.isfinite(px):
            return None
        step = K.K51.STEP[und]
        k = round(px / step) * step
    return float(k)


def price(cnd: dict, D, mode: str = "close", slip=SLIP1, em=None, ref=None) -> dict:
    """Price one candidate on the day's store D. Returns the trade dict (ok False when no fill or over the cap).
    em / ref override the entry minute and Book 2's ref (random twins)."""
    und, book, s = cnd["und"], cnd["book"], cnd["side"]
    sig = cnd["sig"] if em is None else em - (1 if book == "B1" else 0)
    mode_open = mode == "open"
    if book == "B1":
        e = sig + 1
        k_row = e if not mode_open else sig
    else:
        e = sig + 1 if mode_open else sig
        k_row = sig
    side = "C" if s > 0 else "P"
    k = strike_at(D, und, k_row)
    qty = float(K.lot(und, D.d))
    out = dict(book=book, und=und, day=D.d, side=s, sig=sig, open=e, strike=k, qty=qty, ok=False, net=0.0, xr=e,
               why="nofill")
    C = D.con.get((side, k)) if k is not None else None
    if C is None:
        return out
    C = C.astype(np.float64)
    endr = end_row(book, sig + 1 if book == "B1" else sig, D.expiry)
    fb, fs, fst, tk = slip
    if book == "B1":
        r = walk_x1(C, e, endr, mode_open, qty, K.TICK, K.B1_STOP, K.B1_LOCK_TRIG, K.B1_LOCK_KEEP, fb, fs, fst, tk)
    else:
        rf = cnd["ref"] if ref is None else ref
        r = walk_e3(C, D.idx.astype(np.float64), e, endr, s, rf, cnd["stop_pts"], mode_open, qty, K.TICK,
                    K.B2_LOCK_TRIG, K.B2_LOCK_KEEP, fb, fs, fst, tk)
    Er, Xr, rE, rX, ef, xr, why = r
    if why == 0:
        return out
    out.update(E=float(Er), X=float(Xr), rE=float(rE), rX=float(rX), er=int(ef), xr=int(xr), why=REASONS[int(why)])
    if rE * qty > K.CAP:
        out["why"] = "cap"
        return out
    ch = float(E.charges(und, rE, rX, qty, D.d)[0])
    out.update(ok=True, net=float((rX - rE) * qty - ch), gross=float((Xr - Er) * qty), charges=ch)
    return out
