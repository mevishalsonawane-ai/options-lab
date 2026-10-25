"""R104 executor (PREREG.md section 5): one generic path walker for every entry rule, on any bar array (1-minute
series for intraday rules, daily bars for daily rules), then pricing per market.

An INTENT (one position or one sub-lot of it) is a tuple
    (start, side, kind, lvl, valid_end, stop, tgt, tmax, be, grp, w, ref)
  start      first bar the order can act on (for a decision at a bar's close: the next bar)
  side       +1 long / call, -1 short / put
  kind       0 market at start's open; 1 stop order at lvl; 2 limit order at lvl (valid start..valid_end inclusive)
  stop, tgt  underlying levels (NaN = none)
  tmax       exit at the open of this bar at the latest (-1 = none; -(m + 1) = m bars after the fill)
  be         move the stop to the entry once the price is be x R in favour (NaN = never)
  grp, w     sub-lots of one position share grp; w = its share of the position (results are per position)
  ref        the underlying price the strike rule reads (option markets)
Rule-level arrays (per bar, NaN / False = none), applied from the bar AFTER the fill:
  trail[side]   a candidate stop known at that bar's open (tightens only)
  xflag[side]   exit at that bar's open (a close-based rule exit decided on the bar before)
Within a bar: time / rule exits at the open; then the trail update; then the stop (touch, a gap fills at the open);
then the target (touch, a gap fills at the open); the stop wins a tie.
"""
from __future__ import annotations

from datetime import date

import numpy as np
from numba import njit

R_NOFILL, R_STOP, R_TARGET, R_TIME, R_RULE, R_END = 0, 1, 2, 3, 4, 5
REASONS = {0: "nofill", 1: "stop", 2: "target", 3: "time", 4: "rule", 5: "end"}
EMPTY_F = np.zeros(0)
EMPTY_B = np.zeros(0, np.bool_)


@njit(cache=True)
def walk(o, h, l, start, side, kind, lvl, valid_end, stop0, tgt, tmax, be, end, trail, xflag, tab=False, tmode=0):
    """Returns (fill bar, fill price, exit bar, exit price, reason, touch). touch = 1 when the exit was a stop / target
    touch inside the exit bar (an option is then sold at the next bar's open), 0 when it was at the bar's open."""
    n = len(o)
    f = -1
    fpx = np.nan
    if kind == 0:
        if start < end and start < n:
            f = start
            fpx = o[start]
    else:
        last = min(valid_end, end - 1, n - 1)
        for m in range(start, last + 1):
            if kind == 1:
                if side > 0 and h[m] >= lvl:
                    f = m
                    fpx = max(o[m], lvl)
                    break
                if side < 0 and l[m] <= lvl:
                    f = m
                    fpx = min(o[m], lvl)
                    break
            else:
                if side > 0 and l[m] <= lvl:
                    f = m
                    fpx = min(o[m], lvl)
                    break
                if side < 0 and h[m] >= lvl:
                    f = m
                    fpx = max(o[m], lvl)
                    break
    if f < 0:
        return -1, np.nan, -1, np.nan, 0, 0
    if tmax < -1:                        # relative time exit: -(minutes + 1) after the fill
        tmax = f + (-tmax - 1)
    stop = stop0
    risk = abs(fpx - stop0) if stop0 == stop0 else np.nan
    m0 = f if kind == 0 else f + 1
    ext = fpx
    has_t = trail.shape[0] > 0
    has_x = xflag.shape[0] > 0
    armed = not tab                      # tab: the trail starts only after the break-even move
    for m in range(m0, min(end, n)):
        if tmax >= 0 and m >= tmax:
            return f, fpx, m, o[m], 3, 0
        if has_x and m > f and xflag[m]:
            return f, fpx, m, o[m], 4, 0
        if has_t and m > f and armed:
            t = trail[m]
            if tmode == 1 and t == t:            # Smith: only after a bar that made a new extreme since the fill
                if side > 0 and not (h[m - 1] >= ext):
                    t = np.nan
                if side < 0 and not (l[m - 1] <= ext):
                    t = np.nan
            if tmode == 2 and t == t:            # chandelier: trail[m] is a distance from the extreme since the fill
                t = (ext - t) if side > 0 else (ext + t)
            if t == t:
                if side > 0:
                    if stop != stop or t > stop:
                        stop = t
                else:
                    if stop != stop or t < stop:
                        stop = t
        if stop == stop:
            if side > 0 and l[m] <= stop:
                return f, fpx, m, min(o[m], stop), 1, 1
            if side < 0 and h[m] >= stop:
                return f, fpx, m, max(o[m], stop), 1, 1
        if tgt == tgt:
            if side > 0 and h[m] >= tgt:
                return f, fpx, m, max(o[m], tgt), 2, 1
            if side < 0 and l[m] <= tgt:
                return f, fpx, m, min(o[m], tgt), 2, 1
        if be == be and risk == risk and risk > 0:
            if side > 0 and h[m] - fpx >= be * risk:
                armed = True
                if stop != stop or stop < fpx:
                    stop = fpx
            if side < 0 and fpx - l[m] >= be * risk:
                armed = True
                if stop != stop or stop > fpx:
                    stop = fpx
        # the extreme since the fill INCLUDING this bar: at the next bar, h[m] >= ext means bar m made a new extreme
        if side > 0 and h[m] > ext:
            ext = h[m]
        if side < 0 and l[m] < ext:
            ext = l[m]
    if end < n:
        return f, fpx, end, o[end], 5, 0
    return f, fpx, n - 1, np.nan, 5, 0


def per_bar_to_rows(b, arr, n_rows: int, dtype=float):
    """Place a per-bar value at the row after each bar's close (bars b with i1); NaN / False elsewhere."""
    out = np.full(n_rows, np.nan) if dtype is float else np.zeros(n_rows, np.bool_)
    j = b.i1 + 1
    ok = j < n_rows
    out[j[ok]] = np.asarray(arr)[ok]
    return out


def execute(o, h, l, intents, end_of, trail=None, xflag=None, max_side_day=2, day_of=None, one_pos=True, tab=False, tmode=0):
    """Walk every intent (orders rest independently), then accept positions in FILL order: one open position at a time
    when one_pos, at most max_side_day positions a side per day (day_of(bar)). Sub-lots share grp (the position fills
    at its earliest filled sub-lot); groups named 'key|x' are one-cancels-other with every group of the same key (the
    first to fill wins). trail / xflag: {+1: array, -1: array} or None. Returns executed sub-lot dicts."""
    groups = {}
    for t in intents:
        groups.setdefault(t[9], []).append(t)
    done = []
    for g, grp in groups.items():
        side = int(grp[0][1])
        tr = trail[side] if trail is not None and trail.get(side) is not None else EMPTY_F
        xf = xflag[side] if xflag is not None and xflag.get(side) is not None else EMPTY_B
        res = []
        for t in grp:
            start, sd, kind, lvl, vend, stop, tgt, tmax, be, _g, w, ref = t
            end = end_of(start)
            r = walk(o, h, l, int(start), int(sd), int(kind), float(lvl), int(vend), float(stop), float(tgt), int(tmax),
                     float(be), int(end), tr, xf, tab, tmode)
            if r[0] >= 0:
                res.append((t, r))
        if res:
            done.append((min(r[0] for _t, r in res), g, side, res))
    # one-cancels-other: keep the earliest fill per key
    best = {}
    for item in done:
        g = item[1]
        if isinstance(g, str) and "|" in g:
            key = g.split("|")[0]
            if key not in best or item[0] < best[key][0]:
                best[key] = item
    done = [it for it in done if not (isinstance(it[1], str) and "|" in it[1]) or best[it[1].split("|")[0]] is it]
    done.sort(key=lambda it: (it[0], str(it[1])))
    out = []
    busy = -1
    busy_open = False                    # the open position exited at a bar's OPEN (a new one may fill in that bar)
    count = {}
    for f, g, side, res in done:
        if one_pos and (f < busy or (f == busy and not busy_open)):
            continue
        dk = (day_of(f) if day_of else 0, side)
        if count.get(dk, 0) >= max_side_day:
            continue
        count[dk] = count.get(dk, 0) + 1
        last = max(res, key=lambda tr_: tr_[1][2])[1]
        if last[2] >= busy:
            busy = last[2]
            busy_open = last[5] == 0
        for t, r in res:
            out.append(dict(start=int(t[0]), side=side, kind=int(t[2]), stop0=float(t[5]), tgt=float(t[6]), w=float(t[10]),
                            ref=float(t[11]), grp=g, f=int(r[0]), fpx=float(r[1]), x=int(r[2]), xpx=float(r[3]),
                            reason=REASONS[int(r[4])], touch=int(r[5])))
    return out


# ------------------------------------------------------------------------------------------------ pricing
def price_option(D, und: str, d: date, side: int, e_row: int, x_row: int, ref: float, money: int, lot_: int,
                 expiry: bool):
    """Buy the option the strike rule picks (money 0 ATM, 1 = 1-ITM) at the open of e_row and sell it at the open of
    x_row (or the square-off), on R51's engine with liq fills and dated charges. Returns a dict or None (no fill)."""
    import r104cfg as K
    import engine as E                      # research/hunt/r51/engine.py
    step = K.STEP[und]
    atm = round(ref / step) * step
    k = atm - money * step if side > 0 else atm + money * step
    C = D.con.get(("C" if side > 0 else "P", float(k)))
    if C is None:
        return None
    sq = K.SQ_ROW_EXPIRY if expiry else K.SQ_ROW
    if e_row >= sq:
        return None
    fx = x_row if 0 <= x_row < sq else -1
    if fx >= 0 and fx <= e_row:
        fx = e_row + 1
    r = E.simulate(np.asarray(C, np.float64), int(e_row), int(sq), int(fx), -1.0, -1.0, -1.0, 0.0, 0.0, E.NOLOCK, -1, -1.0,
                   -1.0, 0.05, float(lot_), *E.SLIP)
    raw_e, raw_x, real_e, real_x, er, xr, rs = r
    if er < 0 or not np.isfinite(real_x):
        return None
    ch = float(E.charges(und, real_e, real_x, lot_, d)[0])
    gross = (real_x - real_e) * lot_
    return dict(strike=k, prem=real_e, gross=gross, cost=ch, net=gross - ch, opt_reason=E.REASONS[int(rs)],
                e_row=int(er), x_row=int(xr))


def price_index_future(und: str, d_in: date, d_out: date, side: int, epx: float, xpx: float, stop_exit: bool,
                       stop_entry: bool, lot_: int):
    """Spot points as the near future: half-spread fills (stops 2x), carry for the days held, dated charges."""
    import r104cfg as K
    hs_e = K.index_half_spread(epx) * (2 if stop_entry else 1)
    hs_x = K.index_half_spread(xpx) * (2 if stop_exit else 1)
    e = epx + side * hs_e
    x = xpx - side * hs_x
    gross = side * (x - e) * lot_
    days = max((d_out - d_in).days, 0)
    carry = K.CARRY * epx * lot_ * days / 365.0
    gross -= side * carry
    buy, sell = (e, x) if side > 0 else (x, e)
    cost = K.index_fut_charges(buy * lot_, sell * lot_, d_in)
    return dict(gross=gross, cost=cost, net=gross - cost)


def price_mcx_future(sym: str, side: int, epx: float, xpx: float, stop_exit: bool, stop_entry: bool, units: float):
    import common as C94                       # research/hunt/r94_mcx_advanced/common.py
    hs_e = C94.fut_half_spread(sym, epx) * (2 if stop_entry else 1)
    hs_x = C94.fut_half_spread(sym, xpx) * (2 if stop_exit else 1)
    e = epx + side * hs_e
    x = xpx - side * hs_x
    gross = side * (x - e) * units
    buy, sell = (e, x) if side > 0 else (x, e)
    cost = C94.fut_leg_charges(buy * units, sell * units)
    return dict(gross=gross, cost=cost, net=gross - cost)


def price_crypto(side: int, epx: float, xpx: float, stop_exit: bool, stop_entry: bool):
    import r104cfg as K
    e = epx * (1 + side * K.CRY_HALF * (2 if stop_entry else 1))
    x = xpx * (1 - side * K.CRY_HALF * (2 if stop_exit else 1))
    q = K.CRY_NOTIONAL / epx
    gross = side * (x - e) * q
    cost = K.crypto_charges(K.CRY_NOTIONAL)
    return dict(gross=gross, cost=cost, net=gross - cost)


def price_stock(side: int, epx: float, xpx: float, stop_exit: bool, stop_entry: bool, d: date):
    import r104cfg as K
    e = epx * (1 + side * K.STK_HALF * (2 if stop_entry else 1))
    x = xpx * (1 - side * K.STK_HALF * (2 if stop_exit else 1))
    q = K.STK_NOTIONAL / epx
    gross = side * (x - e) * q
    buy, sell = (e * q, x * q) if side > 0 else (x * q, e * q)
    cost = K.index_fut_charges(buy, sell, d)
    return dict(gross=gross, cost=cost, net=gross - cost)
