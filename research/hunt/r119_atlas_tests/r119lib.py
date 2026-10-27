"""R119 engine (PREREG.md): R118's stable patterns as six fixed strategies, priced with the R54 / R55 pieces.

    the path        R98's expiry-day records (all five indices), R118's ATM straddle series (`atlas.atm_series`)
    fills           R25's 'liq' model (5 bps + 1 / 2 / 4 ticks by the leg's 5-minute lots) x R54's late multiplier
    charges         R50 / obuy dated charges per order (BSE rates for SENSEX); R50's dated lots
    stale legs      R103 Amendment 2: no open in the 3 fill bars -> Black-76 at the leg's last own IV, 4 ticks

Grid: row t = the 1-minute bar that STARTS at 09:15 + t; a decision on row t's close fills at row t + 1's open.
Signal source per strategy is in r119cfg.STRATS (P4 reads the option chain: non-default, labelled).
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from datetime import date

import numpy as np
from scipy.special import ndtr

import r119cfg as K
import r98data as R
import r98lib as L98
import r55cfg as K55  # noqa: F401  (puts R54 / R50 / R25 on the path)
import r54lib as LB
import r25lib as R25
import r50lib as L50
import r50cfg as K50
import atlas as AT
import quant as Q

assert (R.O, R.C, R.V) == (R25.O, R25.C, R25.V)       # the same column layout in both engines
EPS = 1e-9
TICK = 0.05
SQ = math.sqrt(2 / math.pi)


@dataclass
class Leg:
    k: float
    side: str          # 'C' / 'P'
    sign: int          # +1 long, -1 short


@dataclass
class Trade:
    strat: str
    und: str
    day: date
    sig_row: int
    exit_row: int
    legs: list
    net: float = 0.0
    gross: float = 0.0
    charges: float = 0.0
    stale: int = 0
    margin: float = 0.0
    tag: str = ""
    extra: dict = field(default_factory=dict)


# ------------------------------------------------------------------------------------------------ the day's series
class DayCtx:
    """Per-day series computed once: spot (5-minute fill), ATM strike and straddle per minute, the lot."""

    def __init__(self, day):
        self.day = day
        self.und = day.und
        self.step = R.STEP[day.und]
        self.sp = L98.spot(day)
        self.katm, self.em, _, _ = AT.atm_series(day, self.sp)
        self.lot = K50.lot(day.und, day.day)
        self._ff = {}

    def close_ff(self, k, side):
        key = (k, side)
        if key not in self._ff:
            a = self.day.opts.get(key)
            self._ff[key] = L98.ffill(a[:, R.C], 3) if a is not None else None
        return self._ff[key]

    def k0(self, t):
        s = self.sp[t]
        return float(round(s / self.step) * self.step) if s == s else math.nan


def years_left(r: int) -> float:
    return max(R.N - r, 1) / Q.YEAR_MIN


def last_close(a: np.ndarray, r: int):
    """(close, row) of the leg's last close strictly before row r; (nan, -1) if none."""
    c = a[:r, R.C]
    ok = np.nonzero(~np.isnan(c))[0]
    return (float(c[ok[-1]]), int(ok[-1])) if len(ok) else (math.nan, -1)


def model_price(ctx: DayCtx, k: float, side: str, r: int) -> float | None:
    """Black-76 at the leg's last own IV (from its last close before r), the index at r and the time left (R103 Am. 2)."""
    a = ctx.day.opts.get((k, side))
    s = ctx.sp[min(r, R.N - 1)]
    if a is None or not s == s:
        return None
    c, rc = last_close(a, r)
    if not c == c or rc < 0 or not ctx.sp[rc] == ctx.sp[rc]:
        return None
    call = side == "C"
    iv = Q.b76_iv(np.array([c]), np.array([ctx.sp[rc]]), np.array([k]), np.array([years_left(rc)]), np.array([call]))[0]
    if not iv == iv:
        intr = max(s - k, 0.0) if call else max(k - s, 0.0)
        return intr                                         # no time value left to model: intrinsic
    return float(Q.b76_price(np.array([s]), np.array([k]), np.array([years_left(r)]), iv, np.array([call]))[0])


def fill_leg(ctx: DayCtx, leg: Leg, r: int, buy: bool, mult: float, allow_model: bool):
    """Price of one order at row r: (price, row, stale) or None. Open of the first of rows r .. r+2 with one, through the
    liq model x late multiplier; else (allow_model) Black-76, 4 ticks."""
    a = ctx.day.opts.get((leg.k, leg.side))
    if a is not None:
        for rr in range(r, min(r + K.FILL_BARS, R.N)):
            o = a[rr, R.O]
            if o == o and o > 0:
                v5 = R25.vol5_lots(a, rr, ctx.lot)
                m = mult * LB.late_mult(rr)
                return (R25.buy_px(o, v5, m) if buy else R25.sell_px(o, v5, m)), rr, False
    if not allow_model:
        return None
    p = model_price(ctx, leg.k, leg.side, r)
    if p is None:
        return None
    m = mult * LB.late_mult(min(r, R.N - 1))
    return (R25.buy_px(p, 0.0, m) if buy else R25.sell_px(p, 0.0, m)), r, True


def run_trade(ctx: DayCtx, strat: str, legs: list, sig_row: int, exit_row: int, mult: float = 1.0) -> Trade | None:
    """Enter every leg at sig_row + 1 (real prints only: a leg that cannot fill skips the trade), exit at exit_row."""
    q = ctx.lot
    d = ctx.day.day
    if q <= 0:
        return None
    tr = Trade(strat, ctx.und, d, sig_row, exit_row, legs)
    cash, ch = 0.0, 0.0
    for lg in legs:
        f = fill_leg(ctx, lg, sig_row + 1, lg.sign > 0, mult, allow_model=False)
        if f is None:
            return None
        p = f[0]
        if p < K.MIN_PX:
            return None                                     # nothing to trade at entry
        cash -= lg.sign * p * q
        ch += L50.charge(lg.sign > 0, p, q, ctx.und, d)
    for lg in legs:
        f = fill_leg(ctx, lg, exit_row, lg.sign < 0, mult, allow_model=True)
        p, _, stale = f if f is not None else (0.0, exit_row, True)
        tr.stale += int(stale)
        if p < K.MIN_PX:
            continue                                        # worthless: no order, no charges
        cash += lg.sign * p * q
        ch += L50.charge(lg.sign < 0, p, q, ctx.und, d)
    tr.gross, tr.charges = round(cash, 2), round(ch, 2)
    tr.net = round(cash - ch, 2)
    shorts = [lg for lg in legs if lg.sign < 0]
    if shorts:
        k0 = float(np.mean([lg.k for lg in shorts]))
        pct = K.MARGIN_PCT + (K.ELM_PCT if d >= K.ELM_FROM else 0.0)
        tr.margin = round(pct * len(shorts) * k0 * q, 2)
    return tr


# ------------------------------------------------------------------------------------------------ the strategies
def iron_fly(ctx: DayCtx, t: int, mult: float = 1.0, strat: str = "P1"):
    k0, s = ctx.k0(t), ctx.em[t]
    if not (k0 == k0 and s == s and s > 0):
        return None
    w = min(max(round(K.WING * s / ctx.step), 1), 10) * ctx.step
    legs = [Leg(k0, "C", -1), Leg(k0, "P", -1), Leg(k0 + w, "C", 1), Leg(k0 - w, "P", 1)]
    return run_trade(ctx, strat, legs, t, K.ROW_1505, mult)


def long_straddle(ctx: DayCtx, t: int, exit_row: int, mult: float = 1.0, strat: str = "P3"):
    k0 = ctx.k0(t)
    if not k0 == k0:
        return None
    return run_trade(ctx, strat, [Leg(k0, "C", 1), Leg(k0, "P", 1)], t, exit_row, mult)


def p_itm(ctx: DayCtx, k: float, side: str, t: int) -> float:
    c = ctx.close_ff(k, side)
    s = ctx.sp[t]
    if c is None or not (c[t] == c[t] and s == s):
        return math.nan
    call = side == "C"
    yt = years_left(t)
    iv = Q.b76_iv(np.array([c[t]]), np.array([s]), np.array([k]), np.array([yt]), np.array([call]))[0]
    if not iv == iv:
        return math.nan
    _, d2 = Q.b76_d(s, k, yt, iv)
    return float(ndtr(d2) if call else ndtr(-d2))


def longshot_legs(ctx: DayCtx, t: int) -> list:
    s = ctx.sp[t]
    if not s == s:
        return []
    legs = []
    for side in ("C", "P"):
        best = None
        for (k, sd), a in ctx.day.opts.items():
            if sd != side or not L98.otm(k, side, s) or not L98.traded(a, t):
                continue
            c = ctx.close_ff(k, side)
            if not (c[t] == c[t] and c[t] >= K.LONGSHOT_MIN_PX):
                continue
            p = p_itm(ctx, k, side, t)
            if not p == p:
                continue
            key = (abs(p - K.LONGSHOT_P), abs(k - s))
            if best is None or key < best[0]:
                best = (key, k)
        if best is None:
            continue
        k = best[1]
        kl = k + K.LONGSHOT_WING * ctx.step if side == "C" else k - K.LONGSHOT_WING * ctx.step
        if (kl, side) not in ctx.day.opts:
            continue
        legs += [Leg(k, side, -1), Leg(kl, side, 1)]
    return legs


def longshot(ctx: DayCtx, t: int, mult: float = 1.0, strat: str = "P4"):
    legs = longshot_legs(ctx, t)
    return run_trade(ctx, strat, legs, t, K.ROW_1505, mult) if legs else None


def otm_call(ctx: DayCtx, t: int, mult: float = 1.0, strat: str = "P6"):
    k0, s = ctx.k0(t), ctx.em[t]
    if not (k0 == k0 and s == s and s > 0):
        return None
    k = k0 + max(round(K.CALL_DIST * s / ctx.step), 1) * ctx.step
    return run_trade(ctx, strat, [Leg(k, "C", 1)], t, K.ROW_1505, mult)


def fade_signals(ctx: DayCtx) -> list[tuple[int, int]]:
    """P5's decisions: (row, direction of the move: +1 up / -1 down), one position at a time."""
    out, free_from = [], 0
    for t in K.FADE_MARKS:
        if t < free_from:
            continue
        s, s0, em = ctx.sp[t], ctx.sp[t - 15], ctx.em[t]
        if not (s == s and s0 == s0 and em == em and em > 0):
            continue
        sd = em / SQ * math.sqrt(15 / (R.N - t))
        r = s - s0
        if abs(r) >= K.FADE_K * sd:
            out.append((t, 1 if r > 0 else -1))
            free_from = min(t + 1 + K.FADE_HOLD, K.ROW_1505)
    return out


def fade_trade(ctx: DayCtx, t: int, move: int, mult: float = 1.0, against: bool = True, strat: str = "P5"):
    k0 = ctx.k0(t)
    if not k0 == k0:
        return None
    up = move > 0
    side = ("P" if up else "C") if against else ("C" if up else "P")
    tr = run_trade(ctx, strat, [Leg(k0, side, 1)], t, min(t + 1 + K.FADE_HOLD, K.ROW_1505), mult)
    if tr is not None:
        tr.tag = "against" if against else "with"
        tr.extra["move"] = move
    return tr


def trades_for(ctx: DayCtx, strat: str, mult: float = 1.0) -> list[Trade]:
    if strat == "P1":
        tr = [iron_fly(ctx, K.ROW_0930, mult, "P1")]
    elif strat == "P2":
        tr = [iron_fly(ctx, K.ROW_1200, mult, "P2")]
    elif strat == "P3":
        tr = [long_straddle(ctx, K.ROW_1500, K.ROW_1520, mult, "P3")]
    elif strat == "P4":
        tr = [longshot(ctx, K.ROW_1200, mult, "P4")]
    elif strat == "P5":
        tr = [fade_trade(ctx, t, mv, mult) for t, mv in fade_signals(ctx)]
    elif strat == "P6":
        tr = [otm_call(ctx, K.ROW_1200, mult, "P6")]
    else:
        raise ValueError(strat)
    return [t for t in tr if t is not None]


# ------------------------------------------------------------------------------------------------ twins
def twin_options(ctx: DayCtx, strat: str, actual: list[Trade]) -> list[list[float]]:
    """For each actual trade, the nets a random twin could draw (PREREG gate 4)."""
    if strat in ("P1", "P2", "P4", "P6"):
        f = {"P1": iron_fly, "P2": iron_fly, "P4": longshot, "P6": otm_call}[strat]
        nets = [x.net for x in (f(ctx, t, 1.0, strat) for t in range(K.TWIN_A, K.TWIN_B + 1)) if x is not None]
        return [nets for _ in actual]
    if strat == "P3":
        nets = [x.net for x in (long_straddle(ctx, t, t + 20, 1.0, "P3") for t in range(K.TWIN3_A, K.TWIN3_B + 1))
                if x is not None]
        return [nets for _ in actual]
    if strat == "P5":
        out = []
        for tr in actual:
            mv = tr.extra["move"]
            w = fade_trade(ctx, tr.sig_row, mv, 1.0, against=False)
            out.append([tr.net, w.net] if w is not None else [tr.net])
        return out
    raise ValueError(strat)
