"""R195 engine (PREREG.md section 3): price a multi-leg short-option position on one day's held contracts.

Pure functions over R51's Day (idx (4, 375) index OHLC, atm (375,), con {(side, strike): (5, 375) O H L C V}).
Decisions read minute closes up to the decision minute only; fills take the NEXT minute's open (look-ahead guard in
test_r195.py). A leg that has not traded for more than 5 minutes is marked with Black-Scholes at the implied volatility
of its last print (R103's stale-leg lesson, part of every rule here).

A position is a list of Legs grouped into stop groups. Each group closes on its own trigger (per-leg, per-side,
combined-premium, max-loss or index stop) at the next minute's open; whatever is left closes at the square-off.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, timedelta
from math import exp, log, sqrt

import numpy as np
from scipy.optimize import brentq
from scipy.special import ndtr

import r195cfg as K
import r103engine as R103          # slippage ('liq'), fill_px, charges (dated), vol5_lots

TICK = 0.05
FRESH = 5                           # minutes a last close stays a valid mark
RATE = 0.065
CLOSE_ROW = K.NROWS                 # 15:30, the settlement minute of the expiring contract
THIN = 0.0                          # traded lots for a model-priced fill: 4 ticks + 5 bps (R103 thin-contract slippage)


# ------------------------------------------------------------------------------------------------ Black-Scholes
def bs(spot, k, t, vol, call: bool):
    """Vectorised Black-Scholes price; intrinsic value when t <= 0 or vol <= 0."""
    spot, t, vol = np.asarray(spot, float), np.asarray(t, float), np.asarray(vol, float)
    intr = np.maximum(spot - k, 0.0) if call else np.maximum(k - spot, 0.0)
    ok = (t > 0) & (vol > 0) & (spot > 0)
    ts, vs, ss = np.where(ok, t, 1.0), np.where(ok, vol, 1.0), np.where(ok, spot, k)
    d1 = (np.log(ss / k) + (RATE + vs * vs / 2) * ts) / (vs * np.sqrt(ts))
    d2 = d1 - vs * np.sqrt(ts)
    if call:
        p = ss * ndtr(d1) - k * np.exp(-RATE * ts) * ndtr(d2)
    else:
        p = k * np.exp(-RATE * ts) * ndtr(-d2) - ss * ndtr(-d1)
    return np.where(ok, np.maximum(p, intr), intr)


def implied_vol(p: float, spot: float, k: float, t: float, call: bool) -> float | None:
    intr = max(spot - k, 0.0) if call else max(k - spot, 0.0)
    if not (np.isfinite(p) and np.isfinite(spot)) or t <= 0 or p <= intr + 0.05:
        return None
    f = lambda v: float(bs(spot, k, t, v, call)) - p  # noqa: E731
    try:
        return brentq(f, 0.005, 5.0)
    except ValueError:
        return None


def years_left(d: date, expiry: date, row) -> np.ndarray | float:
    """Calendar time from minute `row` of day d to 15:30 on the expiry day, in years."""
    return ((expiry - d).days * 1440 + (CLOSE_ROW - np.asarray(row, float))) / (365.0 * 1440)


def expiry_of(days: dict) -> dict:
    """{day: expiry date} = the next day flagged as an expiry on or after it (R51's flag). Days after the last flag get
    day + 7 (only the last sessions of the data; it only matters for a stale leg's model mark)."""
    ds = sorted(days)
    out, nxt = {}, None
    for d in reversed(ds):
        if days[d].expiry:
            nxt = d
        out[d] = nxt if nxt is not None else d + timedelta(days=7)
    return out


# ------------------------------------------------------------------------------------------------ legs and marks
@dataclass
class Leg:
    side: str            # 'C' / 'P'
    k: float
    sign: int            # -1 sold, +1 bought
    group: int = 0


def _ffill_idx(c: np.ndarray) -> np.ndarray:
    """Index of the last finite value at or before each position (-1 if none)."""
    idx = np.where(np.isfinite(c), np.arange(len(c)), -1)
    return np.maximum.accumulate(idx)


def marks(D, leg: Leg, r0: int, r1: int, d: date, expiry: date) -> tuple[np.ndarray, np.ndarray]:
    """(marks, stale flags) for minutes r0..r1: the close; else the last close if <= FRESH minutes old; else Black-Scholes
    at the last print's implied vol, the index at the minute and the time left (floored at intrinsic and one tick)."""
    con = D.con.get((leg.side, float(leg.k)))
    n = r1 - r0 + 1
    if con is None:
        return np.full(n, np.nan), np.ones(n, bool)
    c = con[3, : r1 + 1].astype(float)
    li = _ffill_idx(c)[r0: r1 + 1]
    rows = np.arange(r0, r1 + 1)
    out = np.where(li >= 0, c[np.clip(li, 0, None)], np.nan)
    stale = (li < 0) | (rows - li > FRESH)
    if stale.any():
        spot = D.idx[3, r0: r1 + 1].astype(float)
        spot = np.where(np.isfinite(spot), spot, np.nan)
        # forward-fill the index too (a missing index minute keeps the last value)
        si = _ffill_idx(D.idx[3, : r1 + 1].astype(float))[r0: r1 + 1]
        spot = np.where(si >= 0, D.idx[3, np.clip(si, 0, None)], np.nan)
        call = leg.side == "C"
        for last in np.unique(li[stale]):
            sel = stale & (li == last)
            if last < 0:
                continue
            p0, s0 = float(c[last]), float(D.idx[3, last]) if np.isfinite(D.idx[3, last]) else float("nan")
            v = implied_vol(p0, s0, leg.k, float(years_left(d, expiry, last)), call)
            sp = spot[sel]
            intr = np.maximum(sp - leg.k, 0.0) if call else np.maximum(leg.k - sp, 0.0)
            if v is None:
                out[sel] = np.maximum(intr, p0)
            else:
                out[sel] = np.maximum(bs(sp, leg.k, years_left(d, expiry, rows[sel]), v, call), TICK)
    return out, stale


def fresh_at(D, leg: Leg, r: int) -> bool:
    """A real close in the FRESH minutes up to and including r."""
    con = D.con.get((leg.side, float(leg.k)))
    if con is None:
        return False
    return bool(np.isfinite(con[3, max(0, r - FRESH): r + 1]).any())


def real_open(D, leg: Leg, r: int) -> float | None:
    con = D.con.get((leg.side, float(leg.k)))
    if con is None:
        return None
    for j in range(r, min(K.NROWS, r + FRESH + 1)):
        o = con[0, j]
        if np.isfinite(o) and o > 0:
            return float(o)
    return None


def prev_model(Dp, dp: date, leg: Leg, D, d: date, r: int, expiry: date) -> float:
    """Overnight fallback: Black-Scholes at the implied vol of the leg's last print on the PREVIOUS session (Dp, dp),
    with today's index at minute r and the time left. NaN when there is nothing to invert."""
    con = Dp.con.get((leg.side, float(leg.k)))
    if con is None:
        return float("nan")
    ok = np.flatnonzero(np.isfinite(con[3]))
    if not len(ok) or not np.isfinite(D.idx[3, r]):
        return float("nan")
    j = int(ok[-1])
    call = leg.side == "C"
    v = implied_vol(float(con[3, j]), float(Dp.idx[3, j]), leg.k, float(years_left(dp, expiry, j)), call)
    if v is None:
        s = float(D.idx[3, r])
        return max(s - leg.k, 0.0) if call else max(leg.k - s, 0.0)
    return float(bs(D.idx[3, r], leg.k, years_left(d, expiry, r), v, call))


def fill(D, leg: Leg, r: int, buy: bool, d: date, expiry: date, q: int, mult: float = 1.0,
         prev: tuple | None = None) -> tuple[float, bool]:
    """(price, modelled) of a market order sent for minute r: a real open within FRESH minutes with 'liq' slippage,
    else the model mark at r with thin-contract slippage (prev = (Dp, dp): the previous session, for an overnight leg
    that has not printed yet today)."""
    o = real_open(D, leg, r)
    if o is not None:
        con = D.con[(leg.side, float(leg.k))]
        return R103.slip(o, buy, R103.vol5_lots(con, r, q), mult), False
    m, _ = marks(D, leg, r, r, d, expiry)
    p = float(m[0])
    if not np.isfinite(p) and prev is not None:
        p = prev_model(prev[0], prev[1], leg, D, d, r, expiry)
    p = p if np.isfinite(p) else TICK
    return R103.slip(max(p, TICK), buy, THIN, mult), True


def charge(price: float, q: int, buy: bool, d: date, bse: bool) -> float:
    dn = np.array([(d - date(1970, 1, 1)).days])
    return float(R103.COSTS.charge(buy, np.array([price]), np.array([q]), dn, bse)[0])


# ------------------------------------------------------------------------------------------------ margin
def margin(legs: list, q: int, d: date, expiry_day: bool) -> float:
    """PREREG 3: exposure 2% of each short leg's notional (+2% on its expiry day from 20 Nov 2024) plus, if hedged, the
    widest wing x lot, else SPAN 8% of one short leg's notional (once for a pair)."""
    shorts = [l for l in legs if l.sign < 0]
    longs = [l for l in legs if l.sign > 0]
    exp_pct = K.EXPOSURE + (K.ELM_EXPIRY if expiry_day and d >= K.REGIME else 0.0)
    m = sum(exp_pct * l.k * q for l in shorts)
    if not shorts:
        return 0.0
    if longs:
        w = 0.0
        for s in shorts:
            same = [l for l in longs if l.side == s.side]
            if same:
                w = max(w, min(abs(l.k - s.k) for l in same))
            else:
                w = max(w, K.SPAN_NAKED * s.k)   # an unhedged short side inside a hedged book: naked SPAN
        m += w * q
    else:
        m += K.SPAN_NAKED * max(l.k for l in shorts) * q
    return round(m, 2)


# ------------------------------------------------------------------------------------------------ the simulator
@dataclass
class Spec:
    """One position: legs, the decision row, and each group's stop. stops: {group: (kind, value)} with kind
    'leg' (short leg mark >= value x its fill), 'prem' (sum of the group's short marks >= value x their fills),
    'maxloss' (group open loss >= value x the position's max loss), or None. index_stop: (direction, level): close
    everything when the index closes below (direction +1) / above (-1) the level."""
    legs: list
    decide: int
    stops: dict = field(default_factory=dict)
    index_stop: tuple | None = None
    tag: str = ""


def run(D, und: str, d: date, spec: Spec, expiry: date, expiry_day: bool, mult: float = 1.0,
        D_next=None, d_next: date | None = None, exit_next: int | None = None) -> dict | None:
    """One trade. Entry at decide + 1; triggers on each minute's close, filled at the next open; square-off 15:10
    (15:05 on expiry day). With D_next, the position is carried overnight and closed at exit_next on the next session
    (no intraday stop)."""
    q, bse = K.LOT[und], und in K.BSE
    er = spec.decide + 1
    sq = K.SQ_ROW_EXPIRY if expiry_day else K.SQ_ROW
    if D_next is None and er >= sq:
        return None
    for l in spec.legs:                                    # entry needs a fresh print and a real open on every leg
        if not fresh_at(D, l, spec.decide) or real_open(D, l, er) is None:
            return None
    entry, ch = [], 0.0
    for l in spec.legs:
        p, _ = fill(D, l, er, l.sign > 0, d, expiry, q, mult)
        entry.append(p)
        ch += charge(p, q, l.sign > 0, d, bse)
    credit = sum(-l.sign * f for l, f in zip(spec.legs, entry)) * q
    longs = [l for l in spec.legs if l.sign > 0]
    width = 0.0
    for s in (l for l in spec.legs if l.sign < 0):
        same = [l.k for l in longs if l.side == s.side]
        if same:
            width = max(width, min(abs(k - s.k) for k in same))
    max_loss = width * q - credit if longs else float("nan")
    exits = [None] * len(spec.legs)                        # (row, why, day)
    if D_next is None:
        n = sq - er + 1
        M = np.empty((len(spec.legs), n))
        stale_any = 0
        for i, l in enumerate(spec.legs):
            M[i], st = marks(D, l, er, sq, d, expiry)
            stale_any += int(st.any())
        trig_idx = None
        if spec.index_stop is not None:
            dirn, lvl = spec.index_stop
            ic = D.idx[3, er: sq + 1]
            hit = (ic < lvl) if dirn > 0 else (ic > lvl)
            hit &= np.isfinite(ic)
            j = np.flatnonzero(hit[:-1])
            trig_idx = int(j[0]) if len(j) else None
        groups = sorted({l.group for l in spec.legs})
        gfirst = {}
        for g in groups:
            ii = [i for i, l in enumerate(spec.legs) if l.group == g]
            kind = spec.stops.get(g)
            hit = np.zeros(n, bool)
            if kind is not None:
                kd, v = kind
                sh = [i for i in ii if spec.legs[i].sign < 0]
                if kd == "leg":
                    for i in sh:
                        hit |= M[i] >= v * entry[i]
                elif kd == "prem":
                    hit |= M[sh].sum(axis=0) >= v * sum(entry[i] for i in sh)
                elif kd == "maxloss":
                    pnl = ((M[ii] - np.array([entry[i] for i in ii])[:, None])
                           * np.array([spec.legs[i].sign for i in ii])[:, None]).sum(axis=0) * q
                    hit |= pnl <= -v * max_loss
            j = np.flatnonzero(hit[:-1])
            gj = int(j[0]) if len(j) else None
            why = "stop"
            if trig_idx is not None and (gj is None or trig_idx < gj):
                gj, why = trig_idx, "index"
            gfirst[g] = (gj, why)
        for i, l in enumerate(spec.legs):
            gj, why = gfirst[l.group]
            exits[i] = (er + gj + 1, why) if gj is not None else (sq, "time")
        xs, modelled = [], 0
        for i, l in enumerate(spec.legs):
            xr, why = exits[i]
            p, mdl = fill(D, l, xr, l.sign < 0, d, expiry, q, mult * (2.0 if why in ("stop", "index") else 1.0))
            modelled += int(mdl)
            xs.append(p)
            ch += charge(p, q, l.sign < 0, d, bse)
    else:                                                  # overnight: close at exit_next on the next session
        xs, modelled, stale_any = [], 0, 0
        for i, l in enumerate(spec.legs):
            p, mdl = fill(D_next, l, exit_next, l.sign < 0, d_next, expiry, q, mult, prev=(D, d))
            modelled += int(mdl)
            xs.append(p)
            ch += charge(p, q, l.sign < 0, d_next, bse)
            exits[i] = (exit_next, "next")
    gross = sum(l.sign * (x - f) for l, f, x in zip(spec.legs, entry, xs)) * q
    whys = sorted({e[1] for e in exits})
    return dict(und=und, day=d, tag=spec.tag, decide=spec.decide, legs=[(l.side, l.k, l.sign, l.group) for l in spec.legs],
                entry=entry, exitpx=xs, exits=[e[0] for e in exits], why="+".join(whys), credit=round(credit, 2),
                max_loss=None if not np.isfinite(max_loss) else round(max_loss, 2),
                margin=margin(spec.legs, q, d, expiry_day), gross=round(gross, 2), charges=round(ch, 2),
                net=round(gross - ch, 2), stale_legs=stale_any, model_fills=modelled)
