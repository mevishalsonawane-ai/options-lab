"""R95 pricing (PREREG section 5) and twins (section 7).

Index options: R90's call into R51's engine (the app's slippage model, dated charges, 1-ITM, flat 15:15 / 15:05).
The option is bought at the open of the minute after the signal minute and sold at the open of the minute after the
underlying exit minute (a flat exit at the flat minute). MCX options: R89's port of R53's simulator and R85's strict
half-spread; the exit is forced at the underlying exit minute (the simulator's own future stop / target are switched
off with infinite levels, because R95's exits are decided on the underlying here).

Twins: per trade, n random ENTRY minutes from the trade's own entry minute to the window's last minute that day (and
before the flat minute). The twin keeps the side, the strike rule and the stop distance; an XR twin keeps the target
distance; every other twin holds for the trade's own number of minutes unless stopped (capped at the flat time).
"""
from __future__ import annotations

import numpy as np
from numba import njit

from research.hunt.r89_pdhl_break import price as P89
from research.hunt.r90_almazov_bos import price as P90

from . import cfg as K

INF = np.inf


# ------------------------------------------------------------------------------------------------ real trades
def _rows(P, ts, xm, why_flat):
    rows = (P.mt[np.asarray(ts, np.int64)] - K.NSE_OPEN).astype(np.int64)
    fx = np.where(np.asarray(why_flat, bool), -1, P.mt[np.asarray(xm, np.int64)] - K.NSE_OPEN + 1).astype(np.int64)
    return rows, fx


def idx_price(I, trades: list) -> list:
    """Adds net / gross / filled / qty to a copy of each index trade (1 lot in force on the date)."""
    P = I.P
    out = [dict(t) for t in trades]
    by_day: dict = {}
    for n, tr in enumerate(out):
        by_day.setdefault(tr["day"], []).append(n)
    for d, ix in by_day.items():
        dt = P.days[d]
        lot = float(P90.K51.lot(I.name, dt))
        rows, fx = _rows(P, [out[n]["ts"] for n in ix], [out[n]["xm"] for n in ix], [out[n]["why"] == "flat" for n in ix])
        sides = ["C" if out[n]["side"] > 0 else "P" for n in ix]
        net, gross, _, _, ok = P90.idx_option_day(I.name, I.store[dt], sides, rows, fx, [lot] * len(ix))
        for m, n in enumerate(ix):
            out[n].update(net=float(net[m]), gross=float(gross[m]), filled=bool(ok[m]), qty=lot)
    return out


def _mcx_one(I, ts: int, xm: int, side: int):
    """(net, gross) of one MCX option trade bought after minute ts and sold at minute xm, or None (no print)."""
    B, P = I.B, I.P
    g = P.extra["gidx"]
    j, exit_i = int(g[ts]), int(g[xm])
    k = B.strike(side, j)
    if not np.isfinite(k):
        return None
    lg = B.leg(j, side, k)
    if lg is None:
        return None
    ii, V = lg
    fill, px, i_in, t, _, _ = P89._mcx_sim(ii, V, j, exit_i, side, -side * INF, side * INF, B.F, B.gday, 2)
    if not (np.isfinite(fill) and np.isfinite(px)):
        return None
    mult = K.MCX_MULT[P.name]
    buy, sell = fill * mult, px * mult
    cost = P89.S85.opt_cost(K.K89.MCX_HALF_BOOK[P.name], buy, sell, int(B.m.tod[i_in]), int(B.m.tod[min(t, len(B.F) - 1)]))
    return float(sell - buy - cost), float(sell - buy)


def mcx_price(I, trades: list) -> list:
    out = []
    for tr in trades:
        x = dict(tr)
        r = _mcx_one(I, int(tr["ts"]), int(tr["xm"]), int(tr["side"]))
        if r is None:
            x.update(net=0.0, gross=0.0, filled=False, qty=1.0)
        else:
            x.update(net=r[0], gross=r[1], filled=True, qty=1.0)
        out.append(x)
    return out


def option(I, trades: list) -> list:
    return idx_price(I, trades) if I.nse else mcx_price(I, trades)


# ------------------------------------------------------------------------------------------------ twins
@njit(cache=True)
def twin_exits(mo, mh, ml, t0s, side, risk, reward, hold, sq):
    """Per twin entry minute t0: stop at entry -/+ risk, target at entry +/- reward (reward <= 0: none), held `hold`
    minutes (hold < 0: none). Returns (exit minute, reason 1 stop / 2 target / 3 held / 4 flat)."""
    n = len(t0s)
    xm = np.empty(n, np.int64)
    why = np.empty(n, np.int64)
    for i in range(n):
        t0 = t0s[i]
        E = mo[t0]
        stop = E - side * risk
        tgt = E + side * reward
        end = sq
        if hold >= 0 and t0 + hold < sq:
            end = t0 + hold
        xm[i] = sq
        why[i] = 4
        for m in range(t0, end + 1):
            if m >= sq:
                break
            if (side > 0 and ml[m] <= stop) or (side < 0 and mh[m] >= stop):
                xm[i], why[i] = m, 1
                break
            if reward > 0 and ((side > 0 and mh[m] >= tgt) or (side < 0 and ml[m] <= tgt)):
                xm[i], why[i] = m, 2
                break
            if m == end:
                xm[i], why[i] = m, 3
                break
    return xm, why


def pool(P, d: int, t0: int, first: int, last: int, sq: int) -> np.ndarray:
    """Twin entry minutes: from the trade's own entry minute t0 to the window's last minute that day, before sq."""
    a, b = max(int(P.ms[d]), int(t0)), min(int(P.me[d]), int(sq))
    t = np.arange(a, b)
    tod = P.mt[a:b] if b > a else np.zeros(0)
    return t[(tod >= first) & (tod <= last)]


def twins(I, trades: list, v: K.Variant, first: int, last: int, n: int, rng) -> np.ndarray:
    """(n,) twin totals (option, 1 lot, after costs) for the filled trades of one cell."""
    P = I.P
    tot = np.zeros(n)
    for tr in trades:
        d = int(tr["day"])
        sq = int(I.sq[d])
        pl = pool(P, d, int(tr["t"]), first, last, sq)
        if not len(pl):
            continue
        s = int(tr["side"])
        t0 = pl[rng.integers(0, len(pl), size=n)]
        reward = K.RR * float(tr["risk"]) if v.exit == "R" else -1.0
        hold = -1 if v.exit == "R" else int(tr["xm"]) - int(tr["t"])
        xm, why = twin_exits(P.mo, P.mh, P.ml, t0, s, float(tr["risk"]), reward, hold, sq)
        ts = t0 - 1
        if I.nse:
            dt = P.days[d]
            lot = float(P90.K51.lot(I.name, dt))
            rows, fx = _rows(P, ts, xm, why == 4)
            net, _, _, _, _ = P90.idx_option_day(I.name, I.store[dt], ["C" if s > 0 else "P"] * n, rows, fx, [lot] * n)
            tot += net
        else:
            for m in range(n):
                r = _mcx_one(I, int(ts[m]), int(xm[m]), s)
                if r is not None:
                    tot[m] += r[0]
    return tot
