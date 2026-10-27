"""R121 engine (PREREG.md): sell the ATM iron fly at 15:15, buy it back at 09:20 the next session (same expiry).

Builder: for each index, the lake's rolling nearest-expiry option minutes are read a year at a time; only the evening
(14:50 on) and the next morning (to 09:40) are kept, as R98-style `Day`s (R25's same-minute bad-print clean applied).
A night (d, n) = a session d that is not an expiry day and the next session n, so both days trade the same contract.

Pricing is R119's (R25 liq slippage x R54 late multiplier, R50 dated charges); a leg with no print at the exit is
priced by Black-76 at its last own IV on d, the index at n's 09:20 open and calendar time to its expiry's 15:30.
Grid: row t = the bar that STARTS at 09:15 + t.
"""
from __future__ import annotations

import math
import pickle
from dataclasses import dataclass
from datetime import date, datetime, timedelta

import numpy as np

import r121cfg as K
import r98data as R
import r98lib as L98
import r119lib as L19
import r25lib as R25
import r50lib as L50
import r50cfg as K50
import quant as Q

ROW_EVE, ROW_MORN = K.ROW_EVE, K.ROW_MORN


# ------------------------------------------------------------------------------------------------ builder
def expiries(und: str) -> list[date]:
    return [d.day for d in pickle.loads(R.path(und).read_bytes())]


def keep_rows(m) -> np.ndarray:
    m = np.asarray(m)
    return (m >= K.EVE_FROM) | (m <= K.MORN_TO)


def day_from(und, d, g, ixd):
    """An R98-style Day from one session's option rows g (evening and morning rows only) and its index grid."""
    opts = {}
    for (k, s), h in g.groupby(["strike", "side"]):
        opts[(float(k), s)] = R._grid(h, ["open", "high", "low", "close", "volume", "oi", "iv"])
    ix = R._grid(ixd, ["open", "high", "low", "close"]) if ixd is not None else np.full((R.N, 4), np.nan)
    day = R.Day(d, und, ix, opts)
    day.n_clean = R.clean(day)
    return day


def sessions(und: str):
    """Yields (d, Day) for every session with option rows, in date order, one year file at a time."""
    ix = R._index_frames(und)
    ixd = dict(tuple(ix.groupby("d")))
    step = R.STEP[und]
    first = K.FIRST[und]
    for p in sorted(R.LAKE.glob(f"opt_{und}_WEEK_*.parquet")):
        o = R._read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[keep_rows(o.m.values) & ((o.strike - o.spot).abs() <= R.STRIKE_SLACK * step)]
        for d, g in o.groupby("d", sort=True):
            if d < first or d not in ixd:
                continue
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            yield d, day_from(und, d, g, ixd[d])


def nights(und: str):
    """Yields (d, n, eve Day, morning Day, expiry date) for every night of the same expiry."""
    exp = sorted(expiries(und))
    exps = set(exp)
    prev = None
    for d, day in sessions(und):
        if prev is not None:
            pd_, pday = prev
            if pd_ not in exps:
                nxt = [e for e in exp if e >= d]
                if nxt:
                    yield pd_, d, pday, day, nxt[0]
        prev = (d, day)


# ------------------------------------------------------------------------------------------------ pricing
def years_to_expiry(d: date, row: int, exp: date) -> float:
    now = datetime(d.year, d.month, d.day, 9, 15) + timedelta(minutes=row)
    end = datetime(exp.year, exp.month, exp.day, 15, 30)
    return max((end - now).total_seconds() / 60.0, 1.0) / Q.YEAR_MIN


def stale_price(eve: L19.DayCtx, morn: L19.DayCtx, leg: L19.Leg, d: date, n: date, exp: date) -> float | None:
    """Black-76 at the leg's last own IV on d (its last close), the index at n's 09:20 open, calendar time to expiry."""
    a = eve.day.opts.get((leg.k, leg.side))
    if a is None:
        return None
    c, rc = L19.last_close(a, R.N)
    s_then = eve.sp[rc] if rc >= 0 else math.nan
    s_now = morn_open(morn)
    if not (c == c and s_then == s_then and s_now == s_now):
        return None
    call = leg.side == "C"
    iv = Q.b76_iv(np.array([c]), np.array([s_then]), np.array([leg.k]), np.array([years_to_expiry(d, rc, exp)]),
                  np.array([call]))[0]
    if not iv == iv:
        return max(s_now - leg.k, 0.0) if call else max(leg.k - s_now, 0.0)
    return float(Q.b76_price(np.array([s_now]), np.array([leg.k]), np.array([years_to_expiry(n, ROW_MORN, exp)]), iv,
                             np.array([call]))[0])


def morn_open(morn: L19.DayCtx) -> float:
    for r in range(ROW_MORN, ROW_MORN + 3):
        o = morn.day.ix[r, R.O]
        if o == o:
            return float(o)
    s = morn.sp[ROW_MORN]
    return float(s) if s == s else math.nan


@dataclass
class Night:
    und: str
    d: date
    n: date
    exp: date
    trade: L19.Trade


def fly_legs(ctx: L19.DayCtx, t: int):
    k0, s = ctx.k0(t), ctx.em[t]
    if not (k0 == k0 and s == s and s > 0):
        return None
    w = min(max(round(K.WING * s / ctx.step), 1), 10) * ctx.step
    return [L19.Leg(k0, "C", -1), L19.Leg(k0, "P", -1), L19.Leg(k0 + w, "C", 1), L19.Leg(k0 - w, "P", 1)]


def overnight(eve: L19.DayCtx, morn: L19.DayCtx, d: date, n: date, exp: date, mult: float = 1.0) -> L19.Trade | None:
    legs = fly_legs(eve, ROW_EVE)
    if legs is None or eve.lot <= 0:
        return None
    q = eve.lot
    tr = L19.Trade("R121", eve.und, d, ROW_EVE, ROW_MORN, legs)
    cash, ch = 0.0, 0.0
    for lg in legs:
        f = L19.fill_leg(eve, lg, ROW_EVE + 1, lg.sign > 0, mult, allow_model=False)
        if f is None or f[0] < K.MIN_PX:
            return None
        cash -= lg.sign * f[0] * q
        ch += L50.charge(lg.sign > 0, f[0], q, eve.und, d)
    for lg in legs:
        g = L19.fill_leg(morn, lg, ROW_MORN, lg.sign < 0, mult, allow_model=False)
        if g is None:
            p0 = stale_price(eve, morn, lg, d, n, exp)
            tr.stale += 1
            if p0 is None:
                p0 = 0.0
            p = R25.buy_px(p0, 0.0, mult) if lg.sign < 0 else R25.sell_px(p0, 0.0, mult)
        else:
            p = g[0]
        if p < K.MIN_PX:
            continue
        cash += lg.sign * p * q
        ch += L50.charge(lg.sign < 0, p, q, eve.und, n)
    tr.gross, tr.charges = round(cash, 2), round(ch, 2)
    tr.net = round(cash - ch, 2)
    pct = K.MARGIN_PCT + (K.ELM_PCT if (n == exp and n >= K.ELM_FROM) else 0.0)
    tr.margin = round(pct * 2 * legs[0].k * q, 2)
    tr.extra.update(n=n, exp=exp, gap_days=(n - d).days, eve_expiry=(n == exp))
    return tr


def ctx(day) -> L19.DayCtx:
    c = L19.DayCtx(day)
    c.lot = K50.lot(day.und, day.day)
    return c
