"""R103 simulator: the standard option-selling structures with stops, targets, dated charges, measured-spread slippage
and a stated margin estimate (PREREG.md sections 3-6). Pure functions on Day arrays; nothing here reads files.

Conventions
- Rows are minute bars starting at 09:15 + t. A decision at row t uses that bar's close and fills at row t+1's open.
- A short leg is a liability: its P&L per unit is (sold price - current price). A long wing's is (current - paid).
- A leg's price path is its close forward-filled for at most FF_LIMIT minutes (a price is 'current' for 3 minutes
  after its last trade); any minute still without a print is valued by the calibrated Black-Scholes model
  (model_fill, PREREG amendment 1). A missing high is that modelled close.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date

import numpy as np
from scipy.special import ndtr

import r103data as R

N = R.N
TICK = 0.05
FF_LIMIT = 3
HALF_SPREAD = 0.0016          # h24: real half-spread 0.16% of the premium at ATM (NIFTY, BANKNIFTY), 6 Oct 2026
SPREAD_PER_STRIKE = 0.5       # the half-spread grows by 50% of the ATM rate per strike away from ATM
STOP_SLIP = 2.0               # a stop-market fill carries twice the half-spread
STRUCTS = ("STR", "STG", "IC", "IF")
STOPS = ("X0", "X1", "X2", "X3", "X4")
TARGETS = ("T0", "T1")
ENTRIES = {"0920": R.minute(9, 20), "0945": R.minute(9, 45), "1015": R.minute(10, 15)}
CLOSE_ROW = R.minute(15, 10)          # fills at this bar's open (decision at 15:09)
CLOSE_ROW_EXPIRY = R.minute(15, 5)    # CLAUDE.md: expiry square-off 15:05
TWIN_LO, TWIN_HI = R.minute(9, 20), R.minute(13, 30)
LEG_STOP = {"X1": 1.5, "X2": 2.0}
COMBINED_CREDIT = {"X3": 1.0}         # loss as a multiple of the credit
COMBINED_MARGIN = {"X4": 0.03}        # loss as a share of the margin estimate
TARGET_SHARE = {"T1": 0.5}            # keep half the credit
MARGIN_RATE = {"STR": 0.11, "STG": 0.10}   # naked: share of notional (PREREG 5)
HEDGED_MULT, HEDGED_FLOOR = 1.25, 0.02     # hedged: max(1.25 x max loss, 2% of notional)
EXPIRY_ELM = 0.02                          # SEBI expiry-day ELM on short options, share of notional
STRANGLE_MAX_W, CONDOR_WING, FLY_MAX_WING = 6, 4, 10
RATE = 0.065                  # risk-free rate for the Black-Scholes valuation of a leg without a print
SESSIONS_PER_YEAR = 252
DTE_UNKNOWN = 3               # sessions to expiry assumed when the lake has no later expiry (after its last one)


# ------------------------------------------------------------------------------------------------ price helpers
def ffill(a: np.ndarray, limit: int = FF_LIMIT) -> np.ndarray:
    a = np.asarray(a, dtype=np.float64)
    n = len(a)
    ok = np.isfinite(a)
    idx = np.where(ok, np.arange(n), -1)
    idx = np.maximum.accumulate(idx)
    out = np.where(idx >= 0, a[np.maximum(idx, 0)], np.nan)
    age = np.arange(n) - idx
    out[(idx < 0) | (age > limit)] = np.nan
    return out


def half_spread(price, offset_steps: float) -> np.ndarray:
    """PREREG 5: h = max(0.16% x price x (1 + 0.5 x strikes from ATM), 1 tick)."""
    p = np.asarray(price, dtype=np.float64)
    return np.maximum(HALF_SPREAD * p * (1.0 + SPREAD_PER_STRIKE * abs(offset_steps)), TICK)


def bs_price(S, K, T, sig, is_call: bool, r: float = RATE) -> np.ndarray:
    """Black-Scholes (European, cash-settled index options, q = 0), vectorised; NaN where an input is missing."""
    S, K, T, sig = (np.asarray(a, dtype=np.float64) for a in (S, K, T, sig))
    with np.errstate(divide="ignore", invalid="ignore"):
        v = sig * np.sqrt(T)
        d1 = (np.log(S / K) + (r + 0.5 * sig * sig) * T) / v
        d2 = d1 - v
        dr = np.exp(-r * T)
        call = S * ndtr(d1) - K * dr * ndtr(d2)
        put = K * dr * ndtr(-d2) - S * ndtr(-d1)
    return np.where(is_call, call, put)


def implied_vol(P, S, K, T, is_call: bool, lo: float = 0.01, hi: float = 5.0, iters: int = 40) -> np.ndarray:
    """Implied volatility by bisection under THIS module's conventions (years_left, RATE); NaN where there is no
    price, or the price is outside the no-arbitrage band at lo..hi."""
    P, S, K, T = (np.asarray(a, dtype=np.float64) for a in (P, S, K, T))
    P, S, K, T = np.broadcast_arrays(P, S, K, T)
    out = np.full(P.shape, np.nan)
    good = np.isfinite(P) & np.isfinite(S) & (P > 0) & (S > 0) & (T > 0)
    if not good.any():
        return out
    p, s, k, t = P[good], S[good], K[good], T[good]
    a, b = np.full_like(p, lo), np.full_like(p, hi)
    ok = (bs_price(s, k, t, a, is_call) <= p) & (bs_price(s, k, t, b, is_call) >= p)
    for _ in range(iters):
        m = 0.5 * (a + b)
        f = bs_price(s, k, t, m, is_call) - p
        a = np.where(f < 0, m, a)
        b = np.where(f >= 0, m, b)
    res = 0.5 * (a + b)
    res[~ok] = np.nan
    out[good] = res
    return out


def years_left(dte: int) -> np.ndarray:
    """Time to the 15:30 expiry close from each minute row, in years (sessions / 252; never below one minute)."""
    d = DTE_UNKNOWN if dte < 0 else dte
    frac = (N - np.arange(N)) / N
    return np.maximum((d + frac) / SESSIONS_PER_YEAR, 1.0 / (SESSIONS_PER_YEAR * N))


def model_fill(close: np.ndarray, raw_close: np.ndarray, spot: np.ndarray, k: float, side: str, dte: int,
               iv_fallback: float, refs: list[np.ndarray] | None = None) -> tuple[np.ndarray, np.ndarray]:
    """PREREG amendment 1 (22 Oct 2026): a leg without a print (its strike has left the lake's ATM+-10 window, or no
    trade for more than 3 minutes) is valued by Black-Scholes at that minute's index close with
        IV = the leg's own IV at its last print x (reference IV now / reference IV at that last print),
    where every IV is solved from a print under this module's time convention and the reference is the nearest
    same-side strike that has a print at both minutes (`refs`, nearest first). With no reference the own IV is carried
    as it is; with no own print at all the ATM IV at entry is used. Calibration on 2025 (every 5th day, OTM strikes 8-10
    steps out, priced from an IV solved 30 / 120 minutes earlier): median model / actual 0.99 / 0.98 (NIFTY) and
    1.00 / 1.00 (BANKNIFTY), quartiles within +-5%; the leg's own IV alone was 0.89 / 0.61 on NIFTY (the far smile
    steepens as spot moves away), and Dhan's `iv` column over-priced by 4-12% (a different time convention), so neither
    is used. Returns the filled closes and the mask of modelled rows. Only prints at or before each row are read."""
    out = close.copy()
    miss = ~np.isfinite(out) & np.isfinite(spot)
    if not miss.any():
        return out, miss
    T = years_left(dte)
    own = implied_vol(raw_close, spot, k, T, side == "C")
    idx = np.where(np.isfinite(own), np.arange(N), -1)
    last = np.maximum.accumulate(idx)                              # the row of the leg's last print at or before r
    sig = np.full(N, np.nan)
    for r in np.where(miss)[0]:
        r0 = last[r]
        if r0 < 0:
            sig[r] = iv_fallback if np.isfinite(iv_fallback) and iv_fallback > 0 else np.nan
            continue
        scale = 1.0
        for ref in refs or ():
            if np.isfinite(ref[r]) and np.isfinite(ref[r0]) and ref[r0] > 0 and ref[r] > 0:
                scale = ref[r] / ref[r0]
                break
        sig[r] = own[r0] * scale
    px = bs_price(spot, k, T, sig, side == "C")
    ok = miss & np.isfinite(px)
    out[ok] = np.maximum(np.round(px[ok] / TICK) * TICK, TICK)
    return out, ok


def iv_paths(day: R.Day, side: str) -> dict[float, np.ndarray]:
    """Solved IV per minute for every stored strike of one side (cached on the day; prints only, no look-ahead)."""
    cache = day.__dict__.setdefault("_iv_paths", {})
    if side not in cache:
        spot = ffill(day.ix[:, 3], 5)
        T = years_left(day.dte)
        cache[side] = {k: implied_vol(a[:, R.C].astype(np.float64), spot, k, T, side == "C")
                       for (k, sd), a in day.opts.items() if sd == side}
    return cache[side]


# ------------------------------------------------------------------------------------------------ charges (dated)
def _dn(d: date) -> int:
    return (d - date(1970, 1, 1)).days


_STT = [(_dn(date(2023, 4, 1)), 0.0005), (_dn(date(2024, 10, 1)), 0.000625), (_dn(date(2026, 4, 1)), 0.001),
        (10 ** 9, 0.0015)]
_NSE = [(_dn(date(2023, 1, 1)), 0.00053), (_dn(date(2024, 10, 1)), 0.000495), (10 ** 9, 0.0003503 + 0.000005)]
BROKERAGE, SEBI, STAMP, GST = 20.0, 10.0 / 1e7, 0.00003, 0.18


def _rate(table, dn: int) -> float:
    lo = -10 ** 9
    for until, r in table:
        if lo <= dn < until:
            return r
        lo = until
    return table[-1][1]


def order_charge(sell: bool, price: float, qty: float, d: date) -> float:
    """Rupees for one order of one leg (research/obuy/costs.py 'dated' rates): brokerage, STT on a sell, exchange +
    IPFT, SEBI, stamp on a buy, GST on brokerage + exchange + SEBI."""
    if not np.isfinite(price) or price <= 0 or qty <= 0:
        return 0.0
    val = price * qty
    dn = _dn(d)
    exch = val * _rate(_NSE, dn)
    sebi = val * SEBI
    stt = val * _rate(_STT, dn) if sell else 0.0
    stamp = 0.0 if sell else val * STAMP
    return BROKERAGE + exch + sebi + stt + stamp + GST * (BROKERAGE + exch + sebi)


# ------------------------------------------------------------------------------------------------ legs
@dataclass
class Leg:
    strike: float
    side: str            # 'C' / 'P'
    short: bool
    offset: float        # strikes from K0 (for the spread model)
    close: np.ndarray    # closes: ffilled for 3 minutes, then the model where the lake has no print (375,)
    high: np.ndarray     # raw highs where printed, else the (modelled) close
    open: np.ndarray     # raw opens
    model: np.ndarray | None = None   # mask of the minutes valued by the model


def leg_arrays(day: R.Day, k: float, side: str, offset: float, short: bool, iv_fallback: float = np.nan) -> Leg | None:
    a = day.opts.get((float(k), side))
    if a is None:
        return None
    paths = iv_paths(day, side)
    refs = [paths[kk] for kk in sorted(paths, key=lambda kk: abs(kk - k)) if kk != float(k)]
    close, model = model_fill(ffill(a[:, R.C]), a[:, R.C].astype(np.float64), ffill(day.ix[:, 3], 5), float(k), side,
                              day.dte, iv_fallback, refs)
    high = a[:, R.H].astype(np.float64)
    high = np.where(np.isfinite(high), high, close)
    return Leg(float(k), side, short, offset, close, high, a[:, R.O].astype(np.float64), model)


def spot_at(day: R.Day, t: int) -> float:
    c = ffill(day.ix[:, 3], 5)
    return float(c[t])


def traded(day: R.Day, k: float, side: str, t: int) -> bool:
    a = day.opts.get((float(k), side))
    if a is None:
        return False
    v = a[max(t - 2, 0):t + 1, R.V]
    return bool(np.nansum(v) > 0) and np.isfinite(ffill(a[:, R.C])[t])


def structure(day: R.Day, t: int, struct: str, sell: bool = True, width_mult: float = 1.0,
              max_w: int = STRANGLE_MAX_W) -> list[Leg] | None:
    """The legs of `struct` chosen at row t (PREREG 3), or None when any leg is not priced / traded at t.
    sell=False flips every sign (the BUY side mirror). width_mult / max_w (PREREG_FWD section 2 only) scale the strangle
    distance: w = round(width_mult x ATM straddle / step), 1..max_w; the defaults are the pre-registered 480 cells."""
    step = R.STEP[day.und]
    s = spot_at(day, t)
    if not np.isfinite(s):
        return None
    k0 = round(s / step) * step
    if not (traded(day, k0, "C", t) and traded(day, k0, "P", t)):
        return None
    em = float(ffill(day.opts[(k0, "C")][:, R.C])[t] + ffill(day.opts[(k0, "P")][:, R.C])[t])
    T0 = years_left(day.dte)[t]
    ivs = [float(implied_vol(ffill(day.opts[(k0, sd)][:, R.C])[t], s, k0, T0, sd == "C")) for sd in ("C", "P")]
    ivs = [x for x in ivs if np.isfinite(x)]
    iv_atm = float(np.mean(ivs)) if ivs else (day.vix_prev / 100.0 if np.isfinite(day.vix_prev) else np.nan)
    w = int(min(max(round(width_mult * em / step), 1), max_w))
    if struct == "STR":
        spec = [(k0, "C", True, 0), (k0, "P", True, 0)]
    elif struct == "STG":
        spec = [(k0 + w * step, "C", True, w), (k0 - w * step, "P", True, w)]
    elif struct == "IC":
        ww = w + CONDOR_WING
        spec = [(k0 + w * step, "C", True, w), (k0 - w * step, "P", True, w),
                (k0 + ww * step, "C", False, ww), (k0 - ww * step, "P", False, ww)]
    elif struct == "IF":
        ww = int(max(2, min(2 * w, FLY_MAX_WING)))
        spec = [(k0, "C", True, 0), (k0, "P", True, 0), (k0 + ww * step, "C", False, ww), (k0 - ww * step, "P", False, ww)]
    else:
        raise ValueError(struct)
    legs = []
    for k, side, short, off in spec:
        if not traded(day, k, side, t):
            return None
        lg = leg_arrays(day, k, side, off, short if sell else not short, iv_atm)
        if lg is None:
            return None
        legs.append(lg)
    return legs


def wing_of(legs: list[Leg]) -> dict[int, int]:
    """short leg index -> its wing's index (IC / IF): the long leg of the same side."""
    out = {}
    for i, lg in enumerate(legs):
        if lg.short:
            for j, w in enumerate(legs):
                if not w.short and w.side == lg.side:
                    out[i] = j
    return out


# ------------------------------------------------------------------------------------------------ margin
def margin_estimate(day: R.Day, t: int, struct: str, legs: list[Leg], credit: float) -> float:
    """PREREG 5: naked 11% / 10% of notional; hedged max(1.25 x max loss, 2% of notional); expiry day + 2%."""
    lot = R.lot(day.und, day.day)
    notional = spot_at(day, t) * lot
    if struct in MARGIN_RATE:
        m = MARGIN_RATE[struct] * notional
    else:
        width = max(abs(w.strike - s.strike) for s in legs if s.short for w in legs if not w.short and w.side == s.side)
        max_loss = max(width - credit, 0.0) * lot
        m = max(HEDGED_MULT * max_loss, HEDGED_FLOOR * notional)
    if day.expiry:
        m += EXPIRY_ELM * notional
    return float(m)


# ------------------------------------------------------------------------------------------------ one trade
@dataclass
class Trade:
    day: date
    und: str
    struct: str
    entry_row: int
    exit_row: int               # the last row at which any leg was still open
    credit: float               # net premium received per unit, after entry slippage (negative: a debit)
    gross: float                # rupees before charges (slippage included)
    charges: float
    net: float
    margin: float
    reason: str                 # 'close' | 'leg' | 'combined' | 'target' | 'mixed'
    lot: int
    modelled: bool = False      # some leg was valued by the model at some minute of the trade (no print in the lake)


def fill_open(leg: Leg, r: int) -> float:
    """The price a market order gets at row r: that bar's open, else its (filled / modelled) close, else the bar
    before."""
    for p in (leg.open[r] if r < N else np.nan, leg.close[r] if r < N else np.nan, leg.close[min(r, N - 1) - 1]):
        if np.isfinite(p):
            return float(p)
    return np.nan


def simulate(day: R.Day, t: int, struct: str, stop: str, target: str, legs: list[Leg] | None = None,
             sell: bool = True, buy_rules: tuple | None = None, lock: tuple | None = None,
             close_row: int | None = None) -> Trade | None:
    """One trade: enter at row t (fills at t + 1), run the stop / target rules, close at 15:10 (15:05 on expiry).
    buy_rules (BUY side only): (combined stop as a share of the debit, target as a share of the debit) or None.
    lock (SELL side, exploratory follow-up of 22 Oct 2026, not in the pre-registered 480 cells): (trigger, keep) as
    shares of the credit: once the profit on minute closes has reached trigger x credit, close everything when it
    falls back to keep x credit (next minute's open, normal slippage). close_row (PREREG_FWD only): the row whose
    open is the square-off fill, earlier than the default 15:10 / 15:05."""
    legs = structure(day, t, struct, sell) if legs is None else legs
    if legs is None or t + 1 >= N:
        return None
    default_close = CLOSE_ROW_EXPIRY if day.expiry else CLOSE_ROW
    close_row = default_close if close_row is None else min(close_row, default_close)
    if t + 1 >= close_row:
        return None
    lot = R.lot(day.und, day.day)
    k = len(legs)
    e = np.full(k, np.nan)
    for i, lg in enumerate(legs):
        p = fill_open(lg, t + 1)
        if not np.isfinite(p):
            return None
        h = float(half_spread(p, lg.offset))
        e[i] = max(p - h, TICK) if lg.short else p + h
    credit = float(sum(e[i] if lg.short else -e[i] for i, lg in enumerate(legs)))
    margin = margin_estimate(day, t, struct, legs, credit) if sell else 0.0
    rows = np.arange(t + 1, close_row + 1)              # rows at which prices are watched; close_row = exit fill
    watch = rows[:-1]                                    # decision rows (a decision at r fills at r + 1 <= close_row)
    wings = wing_of(legs)
    exit_row = np.full(k, close_row, dtype=int)          # the row at which each leg's exit FILLS
    exit_px = np.full(k, np.nan)
    stop_kind = np.zeros(k, dtype=int)                   # 0 close, 1 leg stop, 2 combined, 3 target
    if sell and stop in LEG_STOP:
        m = LEG_STOP[stop]
        for i, lg in enumerate(legs):
            if not lg.short:
                continue
            lvl = m * e[i]
            hi = lg.high[t + 1:close_row]                # a trigger at row r fills in row r (stop-market)
            hit = np.where(np.isfinite(hi) & (hi >= lvl - 1e-4))[0]      # float32 highs: a touch is a touch
            if hit.size:
                r = t + 1 + int(hit[0])
                c = lg.close[r] if np.isfinite(lg.close[r]) else lvl
                px = max(lvl, c)
                exit_row[i], exit_px[i], stop_kind[i] = r, px + STOP_SLIP * float(half_spread(px, lg.offset)), 1
                if i in wings:
                    j = wings[i]
                    c = legs[j].close[r] if np.isfinite(legs[j].close[r]) else fill_open(legs[j], r)
                    exit_row[j], stop_kind[j] = r, 1
                    exit_px[j] = max(c - float(half_spread(c, legs[j].offset)), TICK) if np.isfinite(c) else np.nan
    # the P&L path per unit on closes (realised legs frozen at their fill), for the combined stop and the target
    path = np.zeros(len(watch))
    for i, lg in enumerate(legs):
        sgn = 1.0 if lg.short else -1.0
        live = (e[i] - lg.close[watch]) * sgn
        done = watch >= exit_row[i]
        realised = (e[i] - exit_px[i]) * sgn if np.isfinite(exit_px[i]) else np.nan
        path = path + np.where(done, realised, live)
    thr_loss, thr_gain = None, None
    if sell:
        if stop in COMBINED_CREDIT:
            thr_loss = COMBINED_CREDIT[stop] * credit
        elif stop in COMBINED_MARGIN:
            thr_loss = COMBINED_MARGIN[stop] * margin / lot
        if target in TARGET_SHARE:
            thr_gain = TARGET_SHARE[target] * credit
    elif buy_rules is not None:
        debit = -credit
        thr_loss = buy_rules[0] * debit if buy_rules[0] else None
        thr_gain = buy_rules[1] * debit if buy_rules[1] else None
    comb_row, comb_kind = None, 0
    cand = []
    if thr_loss is not None and thr_loss > 0:
        hit = np.where(np.isfinite(path) & (path <= -thr_loss))[0]
        if hit.size:
            cand.append((int(watch[hit[0]]), 2))
    if thr_gain is not None and thr_gain > 0:
        hit = np.where(np.isfinite(path) & (path >= thr_gain))[0]
        if hit.size:
            cand.append((int(watch[hit[0]]), 3))
    if sell and lock is not None and credit > 0:
        trig, keep = lock
        fin = np.where(np.isfinite(path), path, -np.inf)
        armed = np.maximum.accumulate(fin) >= trig * credit
        hit = np.where(armed & np.isfinite(path) & (path <= keep * credit))[0]
        if hit.size:
            cand.append((int(watch[hit[0]]), 3))
    if cand:
        comb_row, comb_kind = min(cand)                  # the earliest; a tie goes to the stop (kind 2 < 3)
    gross, charges = 0.0, 0.0
    for i, lg in enumerate(legs):
        r = exit_row[i]
        px = exit_px[i]
        if comb_row is not None and comb_row + 1 < r:
            r = comb_row + 1
            p = fill_open(lg, r)
            if not np.isfinite(p):
                p = lg.close[comb_row]
            h = float(half_spread(p, lg.offset)) * (STOP_SLIP if comb_kind == 2 else 1.0)
            px = p + h if lg.short else max(p - h, TICK)
            exit_row[i], stop_kind[i] = r, comb_kind
        if not np.isfinite(px):                          # closes at 15:10 / 15:05
            p = fill_open(lg, r)
            if not np.isfinite(p):                       # no index print either: the last printed / modelled close
                last = lg.close[:r][np.isfinite(lg.close[:r])]
                p = float(last[-1]) if len(last) else e[i]
            h = float(half_spread(p, lg.offset))
            px = p + h if lg.short else max(p - h, TICK)
        exit_px[i] = px
        pnl = (e[i] - px) if lg.short else (px - e[i])
        gross += pnl * lot
        charges += order_charge(lg.short, e[i], lot, day.day) + order_charge(not lg.short, px, lot, day.day)
    kinds = set(int(x) for x in stop_kind)
    reason = {0: "close", 1: "leg", 2: "combined", 3: "target"}[stop_kind.max()] if len(kinds) == 1 else "mixed"
    modelled = any(lg.model is not None and bool(lg.model[t + 1:exit_row[i] + 1].any()) for i, lg in enumerate(legs))
    return Trade(day.day, day.und, struct, t, int(exit_row.max()), credit, float(gross), float(charges),
                 float(gross - charges), margin, reason, lot, modelled)


# ------------------------------------------------------------------------------------------------ cells
def events(path) -> set[date]:
    import csv
    with open(path, newline="", encoding="utf-8") as f:
        return {date.fromisoformat(r["date"]) for r in csv.DictReader(f)}


def day_set(days: list[R.Day], dayset: str, blackout: set[date]) -> list[R.Day]:
    out = [d for d in days if d.day not in blackout]
    if dayset == "EXPIRY":
        out = [d for d in out if d.expiry]
    return out


def run_cell(days: list[R.Day], struct: str, entry_row: int, stop: str, target: str) -> list[Trade]:
    out = []
    for d in days:
        tr = simulate(d, entry_row, struct, stop, target)
        if tr is not None:
            out.append(tr)
    return out


def run_group(days: list[R.Day], struct: str, entry_row: int) -> dict[tuple[str, str], list[Trade]]:
    """All 10 stop x target cells of one (days, struct, entry) sharing the leg paths."""
    out = {(s, g): [] for s in STOPS for g in TARGETS}
    for d in days:
        legs = structure(d, entry_row, struct)
        if legs is None:
            continue
        for (s, g), lst in out.items():
            tr = simulate(d, entry_row, struct, s, g, legs=legs)
            if tr is not None:
                lst.append(tr)
    return out


def run_twins(days: list[R.Day], struct: str, n_twins: int, seed: int) -> list[dict[tuple[str, str], float]]:
    """Random-entry twins (PREREG 6): the structure entered at a uniform random minute in [09:20, 13:30] on every
    day, all 10 exit cells; returns the net per twin per cell."""
    rng = np.random.default_rng(seed)
    out = []
    for _ in range(n_twins):
        tot = {(s, g): 0.0 for s in STOPS for g in TARGETS}
        for d in days:
            t = int(rng.integers(TWIN_LO, TWIN_HI + 1))
            legs = structure(d, t, struct)
            if legs is None:
                continue
            for key in tot:
                tr = simulate(d, t, struct, key[0], key[1], legs=legs)
                if tr is not None:
                    tot[key] += tr.net
        out.append(tot)
    return out


BUY_RULES = {"B0": (None, None), "B1": (0.5, 1.0)}     # no stop; combined 50%-of-debit stop + 100%-of-debit target


def run_buy(days: list[R.Day], struct: str, entry_row: int) -> dict[str, list[Trade]]:
    out = {k: [] for k in BUY_RULES}
    for d in days:
        legs = structure(d, entry_row, struct, sell=False)
        if legs is None:
            continue
        for k, rules in BUY_RULES.items():
            tr = simulate(d, entry_row, struct, "X0", "T0", legs=legs, sell=False, buy_rules=rules)
            if tr is not None:
                out[k].append(tr)
    return out


# ------------------------------------------------------------------------------------------------ statistics
def sign_flip_p(x: np.ndarray, draws: int = 10000, seed: int = 103) -> float:
    """Two-sided sign-flip test of mean(x) = 0 (PREREG 6)."""
    x = np.asarray(x, dtype=np.float64)
    if len(x) == 0:
        return 1.0
    rng = np.random.default_rng(seed)
    obs = abs(x.mean())
    hits = 0
    chunk = 1000
    for a in range(0, draws, chunk):
        n = min(chunk, draws - a)
        s = rng.choice([-1.0, 1.0], size=(n, len(x)))
        m = np.abs((s * x).mean(axis=1))
        hits += int((m >= obs - 1e-12).sum())
    return (hits + 1) / (draws + 1)


def bh(p: np.ndarray) -> np.ndarray:
    """Benjamini-Hochberg adjusted q-values."""
    p = np.asarray(p, dtype=np.float64)
    n = len(p)
    if n == 0:
        return p
    order = np.argsort(p)
    ranked = p[order] * n / (np.arange(n) + 1)
    q = np.minimum.accumulate(ranked[::-1])[::-1]
    out = np.empty(n)
    out[order] = np.minimum(q, 1.0)
    return out


def stats(trades: list[Trade], blocks: list[tuple[date, date]] | None = None) -> dict:
    """Per-cell numbers (PREREG 6) from one trade per day."""
    if not trades:
        return dict(trades=0, net=0.0)
    net = np.array([t.net for t in trades])
    days = [t.day for t in trades]
    wins = net[net > 0]
    losses = net[net <= 0]
    cum = np.cumsum(net)
    dd = cum - np.maximum.accumulate(np.maximum(cum, 0))
    months = {}
    for t in trades:
        months[(t.day.year, t.day.month)] = months.get((t.day.year, t.day.month), 0.0) + t.net
    out = dict(
        trades=len(trades), win_rate=float((net > 0).mean()), net=float(net.sum()), avg=float(net.mean()),
        avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
        worst_day=float(net.min()), best_day=float(net.max()), max_dd=float(dd.min()),
        mean_margin=float(np.mean([t.margin for t in trades])), modelled_share=float(np.mean([t.modelled for t in trades])),
        days_below_13k=int((net < -13000).sum()), months_below_13k=int(sum(v < -13000 for v in months.values())),
        worst_month=float(min(months.values())), gross=float(sum(t.gross for t in trades)),
        charges=float(sum(t.charges for t in trades)), mean_credit=float(np.mean([t.credit for t in trades])),
        reasons={k: int(sum(t.reason == k for t in trades)) for k in ("close", "leg", "combined", "target", "mixed")},
        first=str(days[0]), last=str(days[-1]),
    )
    out["rom"] = out["net"] / out["mean_margin"] if out["mean_margin"] > 0 else float("nan")
    if blocks:
        out["blocks"] = [float(sum(t.net for t in trades if a <= t.day <= b)) for a, b in blocks]
        out["blocks_positive"] = int(sum(v > 0 for v in out["blocks"]))
    return out
