"""R122 engine (PREREG.md): an ATM iron fly sold on the 09:20 close and bought back at the 10:30 open, every session.

Sessions come from the lake's rolling nearest-expiry option minutes (R121's builder, rows to 15:15). Pricing is
R119's; a leg with no print at the exit is priced by Black-76 at its last own IV that day, the index then and calendar
time to its expiry's 15:30 (R121's clock). Grid: row t = the bar that STARTS at 09:15 + t.
"""
from __future__ import annotations

import math
from datetime import date

import numpy as np

import r122cfg as K
import r98data as R
import r119lib as L19
import r121lib as L121
import r25lib as R25
import r50lib as L50
import r50cfg as K50
import quant as Q


def sessions(und: str):
    """Yields (d, Day, expiry date, is_expiry) for every session from the index's first date, rows to 15:15."""
    exp = sorted(L121.expiries(und))
    exps = set(exp)
    ix = R._index_frames(und)
    ixd = dict(tuple(ix.groupby("d")))
    step = R.STEP[und]
    for p in sorted(R.LAKE.glob(f"opt_{und}_WEEK_*.parquet")):
        o = R._read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.m <= K.KEEP_TO) & ((o.strike - o.spot).abs() <= R.STRIKE_SLACK * step)]
        for d, g in o.groupby("d", sort=True):
            if d < K.FIRST[und] or d not in ixd:
                continue
            nxt = [e for e in exp if e >= d]
            if not nxt:
                continue
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            yield d, L121.day_from(und, d, g, ixd[d]), nxt[0], d in exps


def ctx(day) -> L19.DayCtx:
    c = L19.DayCtx(day)
    c.lot = K50.lot(day.und, day.day)
    return c


def stale_price(c: L19.DayCtx, leg: L19.Leg, r: int, exp: date) -> float | None:
    a = c.day.opts.get((leg.k, leg.side))
    s_now = c.sp[min(r, R.N - 1)]
    if a is None or not s_now == s_now:
        return None
    px, rc = L19.last_close(a, r)
    if not (px == px and rc >= 0 and c.sp[rc] == c.sp[rc]):
        return None
    call = leg.side == "C"
    iv = Q.b76_iv(np.array([px]), np.array([c.sp[rc]]), np.array([leg.k]),
                  np.array([L121.years_to_expiry(c.day.day, rc, exp)]), np.array([call]))[0]
    if not iv == iv:
        return max(s_now - leg.k, 0.0) if call else max(leg.k - s_now, 0.0)
    return float(Q.b76_price(np.array([s_now]), np.array([leg.k]), np.array([L121.years_to_expiry(c.day.day, r, exp)]),
                             iv, np.array([call]))[0])


def fly_legs(c: L19.DayCtx, t: int):
    k0, s = c.k0(t), c.em[t]
    if not (k0 == k0 and s == s and s > 0):
        return None
    w = min(max(round(K.WING * s / c.step), 1), 10) * c.step
    return [L19.Leg(k0, "C", -1), L19.Leg(k0, "P", -1), L19.Leg(k0 + w, "C", 1), L19.Leg(k0 - w, "P", 1)]


def trade(c: L19.DayCtx, t: int, exit_row: int, exp: date, mult: float = 1.0, strat: str = "R122") -> L19.Trade | None:
    legs = fly_legs(c, t)
    if legs is None or c.lot <= 0:
        return None
    q, d = c.lot, c.day.day
    tr = L19.Trade(strat, c.und, d, t, exit_row, legs)
    cash, ch = 0.0, 0.0
    for lg in legs:
        f = L19.fill_leg(c, lg, t + 1, lg.sign > 0, mult, allow_model=False)
        if f is None or f[0] < K.MIN_PX:
            return None
        cash -= lg.sign * f[0] * q
        ch += L50.charge(lg.sign > 0, f[0], q, c.und, d)
    for lg in legs:
        g = L19.fill_leg(c, lg, exit_row, lg.sign < 0, mult, allow_model=False)
        if g is None:
            p0 = stale_price(c, lg, exit_row, exp)
            tr.stale += 1
            p0 = 0.0 if p0 is None else p0
            p = R25.buy_px(p0, 0.0, mult) if lg.sign < 0 else R25.sell_px(p0, 0.0, mult)
        else:
            p = g[0]
        if p < K.MIN_PX:
            continue
        cash += lg.sign * p * q
        ch += L50.charge(lg.sign < 0, p, q, c.und, d)
    tr.gross, tr.charges = round(cash, 2), round(ch, 2)
    tr.net = round(cash - ch, 2)
    pct = K.MARGIN_PCT + (K.ELM_PCT if (d == exp and d >= K.ELM_FROM) else 0.0)
    tr.margin = round(pct * 2 * legs[0].k * q, 2)
    tr.extra.update(expiry=(d == exp), exp=exp)
    return tr


def twin_rows(rng, n: int = K.TWIN_ROWS_PER_DAY) -> np.ndarray:
    """A uniform random sample (without replacement) of decision rows 10:30-14:00 for one day's twins."""
    rows = np.arange(K.TWIN_A, K.TWIN_B + 1)
    return np.sort(rng.choice(rows, size=min(n, len(rows)), replace=False))
