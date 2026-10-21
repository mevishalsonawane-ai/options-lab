"""R58 engine: one trade on ONE held stock-option contract, priced on its own lake prints (PREREG sections 3-4, 6).

Flat index inside a trade: f = (session - entry session) * 375 + row. The walk checks, each minute from the fill:
premium stop (resting, filled at min(level, open)), underlying stop, underlying target, premium target, VWAP cross,
time exit, band exit, square-off (15:15 of the last holding session). Signal-type exits sell at the next minute's open.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, replace
from datetime import date

import numpy as np

import r58cfg as K
from r58data import C, H, L, O, V, IV, Book

try:
    from numba import njit
except ImportError:                                 # pragma: no cover - numba is optional (slower)
    def njit(*a, **k):
        def deco(f):
            return f
        return deco(a[0]) if a and callable(a[0]) else deco

TICK = 0.05
WHY = ("pstop", "ustop", "utgt", "ptgt", "vwap", "time", "band", "sq")
PSTOP, USTOP, UTGT, PTGT, VWAPX, TIME, BAND, SQX = range(8)


@dataclass(frozen=True)
class Spec:
    """One entry decision. Levels are in ADJUSTED stock prices (the signal's own scale)."""
    sym: str
    di: int                  # session index of the signal (= entry session)
    s: int                   # signal row (the fill is from row s + 1)
    side: int                # +1 CE (long), -1 PE (short)
    itm: int = 0
    pstop: float = 0.0       # premium stop as a fraction (0.30 = -30%); 0 none
    ptgt: float = 0.0        # premium target as a fraction; 0 none
    ustop: float = np.nan    # stock level: exit when a minute closes beyond it
    utgt: float = np.nan     # stock level: exit when a minute closes at or beyond it
    vwap: bool = False       # exit on the first 5-minute close back across VWAP
    tmin: int = 0            # time exit, minutes after the fill (0: none)
    hold: int = 1            # sessions held at most (the entry session counts as 1)


@dataclass(frozen=True)
class Mode:
    cutoff: bool = True      # PREREG 3: no entries on T-2..T, out by 15:15 on T-3
    band: bool = True        # PREREG 4: band exit (False = context run, model prices beyond the band)
    cost: float = 1.0        # 1x / 2x spread, bps and charges
    end: int = -1            # last session index of the book in use (design: 31 Mar 2026)


# ------------------------------------------------------------------------------------------------ charges
def opt_charge(buy: bool, px: float, qty: float, d: date, mult: float = 1.0) -> float:
    """obuy.costs.Costs('dated') for one NSE option order (test_r58 checks equality), times mult."""
    v = px * qty
    if not v > 0:
        return 0.0
    stt = 0.0 if buy else (0.001 if d < date(2026, 4, 1) else 0.0015)
    if d < date(2024, 10, 1):
        raise ValueError("R58 trades start 20 Nov 2024")
    exch = 0.0003503 + 0.000005
    txn = v * exch
    sebi = v * 1e-6
    stamp = v * 0.00003 if buy else 0.0
    tot = 20.0 + v * stt + txn + sebi + stamp + (20.0 + txn + sebi) * 0.18
    return round(tot, 2) * mult


def cash_charge(buy: bool, px: float, qty: float, delivery: bool, mult: float = 1.0) -> float:
    """NSE equity: MIS (intraday) or delivery, PREREG 6."""
    v = px * qty
    if not v > 0:
        return 0.0
    exch = v * 0.0000297
    sebi = v * 1e-6
    if delivery:
        brk = 0.0
        stt = v * 0.001
        stamp = v * 0.00015 if buy else 0.0
        dp = 0.0 if buy else 15.34
    else:
        brk = min(20.0, v * 0.0003)
        stt = 0.0 if buy else v * 0.00025
        stamp = v * 0.00003 if buy else 0.0
        dp = 0.0
    return round(brk + stt + exch + sebi + stamp + (brk + exch + sebi) * 0.18 + dp, 2) * mult


# ------------------------------------------------------------------------------------------------ fills
def half_spread(bk: Book, px: float, lots5: float, stop: bool, mult: float) -> float:
    """Rupees off the price for one order: R17 bps + max(R17 ticks, fitted Roll half-spread of the bucket)."""
    b = "none" if not lots5 > 0 else ("thin" if lots5 < 20 else "liquid")
    ticks = {"none": 4, "thin": 2, "liquid": 1}[b] * (2 if stop else 1)
    fit = bk.spread.get(b, {}).get("half_used", 0.0) * px * (2 if stop else 1)
    bps = (10 if stop else 5) / 1e4 * px
    return (bps + max(ticks * TICK, fit)) * mult


def lots5(arr: np.ndarray, f: int, lot: int) -> float:
    """Contract volume of the 5 minutes before flat row f (same session only), in lots."""
    m = f % K.NMIN
    v = arr[f - min(m, 5):f, V] if m else arr[f:f, V]
    v = v[np.isfinite(v)]
    return float(v.sum() / lot) if lot else 0.0


def bs_price(S: float, K_: float, T: float, vol: float, call: bool, r: float = K.RATE) -> float:
    if not (S > 0 and K_ > 0):
        return np.nan
    intr = max(S - K_, 0.0) if call else max(K_ - S, 0.0)
    if not (T > 0 and vol > 0):
        return intr
    sq = vol * math.sqrt(T)
    d1 = (math.log(S / K_) + (r + 0.5 * vol * vol) * T) / sq
    d2 = d1 - sq
    N = lambda x: 0.5 * (1 + math.erf(x / math.sqrt(2)))  # noqa: E731
    if call:
        return S * N(d1) - K_ * math.exp(-r * T) * N(d2)
    return K_ * math.exp(-r * T) * N(-d2) - S * N(-d1)


# ------------------------------------------------------------------------------------------------ the walk
@njit(cache=True)
def walk(po, pl, ph, sc, so, vw, f0, fend, side, pstop, ptgt, ustop, utgt, use_vwap, ftime, band_lo, band_hi,
         use_band):
    """Returns (flat index, why, filled_at_level). filled_at_level: the premium stop's min(level, open) at that row;
    every other exit sells at that row's open."""
    for f in range(f0, fend + 1):
        if ftime > 0 and f >= ftime:
            return f, 5, 0.0
        if f >= fend:
            return fend, 7, 0.0
        m = f % 375
        if use_band and m == 0 and f > f0 and so[f] == so[f] and (so[f] >= band_hi or so[f] <= band_lo):
            return f, 6, 0.0                              # an overnight gap beyond the band: out at the open
        if pstop > 0 and pl[f] == pl[f] and pl[f] <= pstop:
            op = po[f] if po[f] == po[f] else pstop
            return f, 0, min(pstop, op)
        s = sc[f]
        if s == s:
            if ustop == ustop and ((side > 0 and s < ustop) or (side < 0 and s > ustop)):
                return f + 1, 1, 0.0
            if utgt == utgt and ((side > 0 and s >= utgt) or (side < 0 and s <= utgt)):
                return f + 1, 2, 0.0
        if ptgt > 0 and ph[f] == ph[f] and ph[f] >= ptgt and f + 1 < fend:
            return f + 1, 3, 0.0
        if use_vwap and m % 5 == 4 and s == s and vw[f] == vw[f]:
            if (side > 0 and s < vw[f]) or (side < 0 and s > vw[f]):
                return f + 1, 4, 0.0
        if use_band and s == s and (s >= band_hi or s <= band_lo):
            return f + 1, 6, 0.0
    return fend, 7, 0.0


# ------------------------------------------------------------------------------------------------ book helpers
def last_hold(bk: Book, di: int, hold: int, mode: Mode) -> int:
    """The last session a position opened on session di may be held (-1: no entry allowed that session)."""
    ei = bk.exp_of[di]
    if ei < 0 or bk.bad[di]:
        return -1
    T = bk.exp_di[ei]
    if T < 0:                                        # the expiry is after the data's last session: no cut-off inside
        T = bk.nd + 10 ** 6
    if mode.cutoff:
        if di > T - 3:
            return -1
        dl = min(di + hold - 1, T - 3)
    else:
        dl = min(di + hold - 1, T)
    end = mode.end if mode.end >= 0 else bk.nd - 1
    dl = min(dl, end)
    for d in range(di + 1, dl + 1):                  # no holding across an ex-date or through a bad day
        if bk.exdate[d] or bk.bad[d]:
            dl = d - 1
            break
    return dl


def vwap_of(bk: Book) -> np.ndarray:
    v = getattr(bk, "_vwap", None)
    if v is None:
        tp = (bk.S[:, :, H] + bk.S[:, :, L] + bk.S[:, :, C]) / 3
        vol = np.where(np.isfinite(tp), np.nan_to_num(bk.S[:, :, 4]), 0.0)
        num = np.cumsum(np.nan_to_num(tp) * vol, axis=1)
        den = np.cumsum(vol, axis=1)
        with np.errstate(invalid="ignore", divide="ignore"):
            v = np.where(den > 0, num / den, np.nan)
        bk._vwap = v
    return v


def con_flat(bk: Book, key: tuple, di: int, dl: int) -> np.ndarray | None:
    x = bk.con.get(key)
    if x is None:
        return None
    d0, arr = x
    n = dl - di + 1
    out = np.full((n, K.NMIN, 6), np.nan)
    a, b = max(di, d0), min(dl, d0 + arr.shape[0] - 1)
    if a <= b:
        out[a - di:b - di + 1] = arr[a - d0:b - d0 + 1]
    return out.reshape(n * K.NMIN, 6)


def strike_for(bk: Book, di: int, s: int, side: int, itm: int) -> float:
    k = bk.atm[di, s]
    if not np.isfinite(k):
        return np.nan
    if itm:
        st = bk.step[di, s]
        if not np.isfinite(st):
            return np.nan
        k = k - side * itm * st
    return float(k)


# ------------------------------------------------------------------------------------------------ one trade
def simulate(bk: Book, sp: Spec, mode: Mode = Mode(), want_cash: bool = True) -> dict | None:
    di, s = sp.di, sp.s
    dl = last_hold(bk, di, sp.hold, mode)
    if dl < di:
        return None
    ei = int(bk.exp_of[di])
    k = strike_for(bk, di, s, sp.side, sp.itm)
    if not np.isfinite(k):
        return None
    sd = "C" if sp.side > 0 else "P"
    a = con_flat(bk, (ei, k, sd), di, dl)
    if a is None:
        return None
    sq_row = K.SQ_EXPIRY if (not mode.cutoff and dl == bk.exp_di[ei]) else K.SQ
    fend = (dl - di) * K.NMIN + sq_row
    c0 = -1
    for f in range(s + 1, min(s + 1 + K.FILL_TRIES, fend)):
        if np.isfinite(a[f, O]):
            c0 = f
            break
    if c0 < 0:
        return None
    lot = bk.lot(ei, k, sd, di)
    if not lot:
        return None
    m = mode.cost
    px0 = float(a[c0, O])
    e = px0 + half_spread(bk, px0, lots5(a, c0, lot), False, m)
    sc = bk.S[di:dl + 1, :, C].reshape(-1)
    so = bk.S[di:dl + 1, :, O].reshape(-1)
    vw = vwap_of(bk)[di:dl + 1].reshape(-1) if sp.vwap else sc
    ratio = bk.ratio[di] if np.isfinite(bk.ratio[di]) else 1.0
    st = bk.step[di, s]
    band_lo = (k - K.BAND * st) / ratio
    band_hi = (k + K.BAND * st) / ratio
    pst = math.floor((e * (1 - sp.pstop)) / TICK + 1e-9) * TICK if sp.pstop > 0 else 0.0
    ptg = e * (1 + sp.ptgt) if sp.ptgt > 0 else 0.0
    ftime = min(c0 + sp.tmin, fend) if sp.tmin > 0 else -1
    x, why, lvl = walk(a[:, O], a[:, L], a[:, H], sc, so, vw, c0, fend, sp.side, pst, ptg, sp.ustop, sp.utgt,
                       sp.vwap, ftime, band_lo, band_hi, mode.band and np.isfinite(st))
    x = int(x)
    price_src = "print"
    if why == PSTOP:
        raw, xr = float(lvl), x
    else:
        raw, xr = np.nan, x
        day_end = (x // K.NMIN) * K.NMIN + K.NMIN
        for f in range(x, min(x + K.FILL_TRIES, day_end)):
            if np.isfinite(a[f, O]):
                raw, xr = float(a[f, O]), f
                break
        if not np.isfinite(raw):
            lo = (x // K.NMIN) * K.NMIN
            fin = np.flatnonzero(np.isfinite(a[lo:x, C]))
            if len(fin) and (mode.band or x - (lo + int(fin[-1])) <= 15):
                xr = lo + int(fin[-1])
                raw, price_src = float(a[xr, C]), "stale"
            else:
                raw, price_src, xr = model_price(bk, a, k, sd, ei, di, x), "model", x
    if not np.isfinite(raw):
        return None
    stop_fill = why in (PSTOP,)
    xp = max(raw - half_spread(bk, raw, lots5(a, xr, lot) if price_src == "print" else 0.0, stop_fill, m), 0.0)
    dx = di + xr // K.NMIN
    d_in, d_out = bk.days[di], bk.days[dx]
    ch = opt_charge(True, e, lot, d_in, m) + opt_charge(False, xp, lot, d_out, m)
    gross = (xp - e) * lot
    t = dict(sym=bk.sym, di=di, day=d_in, exit_day=d_out, s=s, side=sp.side, itm=sp.itm, strike=k, expiry=bk.expiries[ei],
             lot=lot, entry_row=c0, exit_f=xr, held_sessions=int(dx - di + 1), entry=e, exit=xp, raw_entry=px0, raw_exit=raw,
             why=WHY[why], src=price_src, gross=gross, charges=ch, net=gross - ch, capital=e * lot,
             intrinsic_exit=intrinsic(bk, k, sd, di, xr))
    if want_cash:
        t.update(cash(bk, sp, di, dl, c0, xr, why, lot, ratio, m))
    return t


def intrinsic(bk: Book, k: float, sd: str, di: int, f: int) -> float:
    d, r = di + f // K.NMIN, f % K.NMIN
    S = bk.S[d, r, O] if np.isfinite(bk.S[d, r, O]) else bk.S[d, r, C]
    if not np.isfinite(S):
        prev = bk.S[d, :r + 1, C]
        prev = prev[np.isfinite(prev)]
        S = prev[-1] if len(prev) else np.nan
    S = S * (bk.ratio[di] if np.isfinite(bk.ratio[di]) else 1.0)
    return float(max(S - k, 0.0) if sd == "C" else max(k - S, 0.0)) if np.isfinite(S) else np.nan


def model_price(bk: Book, a: np.ndarray, k: float, sd: str, ei: int, di: int, f: int) -> float:
    """Black-Scholes with the contract's last observed IV before f (PREREG 4 'model'); intrinsic when no IV."""
    iv = a[:f, IV]
    iv = iv[np.isfinite(iv) & (iv > 0)]
    vol = float(iv[-1]) / 100 if len(iv) else 0.0
    d, r = di + f // K.NMIN, f % K.NMIN
    S = bk.S[d, :r + 1, C]
    S = S[np.isfinite(S)]
    if not len(S):
        return np.nan
    S = float(S[-1]) * (bk.ratio[di] if np.isfinite(bk.ratio[di]) else 1.0)
    exp = bk.expiries[ei]
    T = ((exp - bk.days[d]).days + (375 - r) / 375 * 0.26) / 365
    return bs_price(S, k, T, vol, sd == "C")


def cash(bk: Book, sp: Spec, di: int, dl: int, c0: int, xr: int, why: int, lot: int, ratio: float, m: float) -> dict:
    """The same trade in the stock (PREREG 6): same minutes, lot-many shares; MIS for one session, delivery longer."""
    delivery = sp.hold > 1
    if delivery and sp.side < 0:
        return dict(cash_net=np.nan, cash_capital=np.nan, cash_kind="n/a (short delivery)")

    def px(f, use_open=True):
        d, r = di + f // K.NMIN, f % K.NMIN
        p = bk.S[d, r, O] if use_open else np.nan
        if not np.isfinite(p):
            prev = bk.S[d, :r + 1, C]
            prev = prev[np.isfinite(prev)]
            p = prev[-1] if len(prev) else np.nan
        return float(p) * ratio
    e0, x0 = px(c0), px(xr)
    if not (np.isfinite(e0) and np.isfinite(x0)):
        return dict(cash_net=np.nan, cash_capital=np.nan, cash_kind="no price")
    slip = lambda p, stop: max(TICK, p * 1e-4) * (2 if stop else 1) * m  # noqa: E731
    stop = why in (PSTOP, USTOP)
    e = e0 + sp.side * slip(e0, False)
    x = x0 - sp.side * slip(x0, stop)
    gross = (x - e) * lot * sp.side
    if sp.side > 0:
        ch = cash_charge(True, e, lot, delivery, m) + cash_charge(False, x, lot, delivery, m)
    else:
        ch = cash_charge(False, e, lot, delivery, m) + cash_charge(True, x, lot, delivery, m)
    return dict(cash_net=gross - ch, cash_gross=gross, cash_charges=ch,
                cash_capital=e * lot * (1.0 if delivery else 0.2), cash_kind="delivery" if delivery else "MIS")


# ------------------------------------------------------------------------------------------------ twins
def twin_spec(bk: Book, real: dict, sp: Spec, di: int, s: int) -> Spec:
    """The real trade's exits moved to (di, s): stock levels keep their distance in % of the signal close."""
    ref = bk.S[sp.di, sp.s, C]
    now = bk.S[di, s, C]
    us, ut = np.nan, np.nan
    if np.isfinite(sp.ustop) and np.isfinite(ref) and np.isfinite(now):
        us = now * sp.ustop / ref
    if np.isfinite(sp.utgt) and np.isfinite(ref) and np.isfinite(now):
        ut = now * sp.utgt / ref
    return replace(sp, di=di, s=s, ustop=us, utgt=ut)
