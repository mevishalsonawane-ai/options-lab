"""R89 pricing (PREREG.md section 4): the 1-ITM option on real held-contract prints, the futures-only mirror, and the
random-entry twins.

Index options: R51's engine (research/hunt/r51/engine.py, the app's slippage model R17 'liq' and dated charges). Entry at
the open of the minute after the trigger (first print within 2 minutes); the underlying stop / target touched at minute
m -> sold at the open of m + 1 (an R51 forced exit); else the 15:10 / 15:05 square-off.
MCX options: R53 sim_option's rules (entry: first print in (j, j + 2]; the future's minute close through the stop /
target from the fill minute, sold at that minute's close, else the next print within 10 minutes, else the last close),
re-implemented in numba for the twins and checked against R53 in the tests; costs R85 opt_cost (R53 McxCosts charges +
R85's strict half-spread).
"""
from __future__ import annotations

from datetime import date

import numpy as np
from numba import njit

from . import cfg as K
from . import store  # noqa: F401  (puts research/hunt/r51 on sys.path, extends R51's window)
import engine as E  # noqa: E402  (research/hunt/r51/engine.py)
import r51cfg as K51  # noqa: E402

from research.hunt.r53 import common as C53  # noqa: E402
from research.hunt.r85 import strategy as S85  # noqa: E402

NOLOCK = np.zeros((0, 2))


def lots_for(risk_pts: float, lot: float) -> int:
    """Fixed-risk view: floor(Rs 2,000 / (risk points x 0.6 x lot)), clamped to 1..3."""
    if not risk_pts > 0:
        return 1
    return int(min(max(np.floor(K.RISK_RS / (risk_pts * K.DELTA * lot) + 1e-9), 1), K.MAX_LOTS))


# ------------------------------------------------------------------------------------------------ index futures costs
def idx_fut_charges(buy_rs: float, sell_rs: float, d: date) -> float:
    brok = min(20.0, 0.0003 * buy_rs) + min(20.0, 0.0003 * sell_rs)
    if d <= date(2024, 9, 30):
        stt = 0.000125
    elif d <= date(2026, 3, 31):
        stt = 0.0002
    else:
        stt = 0.0005
    txn = 0.0000173 * (buy_rs + sell_rs)
    sebi = 10e-7 * (buy_rs + sell_rs)
    stamp = 0.00002 * buy_rs
    return brok + stt * sell_rs + txn + sebi + stamp + 0.18 * (brok + txn + sebi)


def fut_trade(P, tr: dict, lot: float, d: date) -> dict:
    """The same trade on the underlying itself, 1 lot (PREREG 4 futures-only)."""
    t, xm, s = tr["t"], tr["xm"], tr["side"]
    if P.kind == "nse":
        ein = max(tr["entry"], P.mo[t]) if s > 0 else min(tr["entry"], P.mo[t])
        ein *= 1 + 0.0001 * s
        if tr["why"] == "stop":
            px = P.mc[t] if xm == t else (min(tr["stop"], P.mo[xm]) if s > 0 else max(tr["stop"], P.mo[xm]))
            px *= 1 - 0.0002 * s
        elif tr["why"] == "target":
            px = tr["target"]
        else:
            px = P.mo[xm] * (1 - 0.0001 * s)
        gross = s * (px - ein) * lot
        buy, sell = (ein, px) if s > 0 else (px, ein)
        cost = idx_fut_charges(buy * lot, sell * lot, d)
    else:
        ein, px = P.mc[t], P.mc[xm]
        gross = s * (px - ein) * lot
        fam = K.MCX_FAM[P.name]
        cost = C53.mcx_fut_charges(*(((ein, px) if s > 0 else (px, ein))[i] * lot for i in (0, 1)))
        cost += (C53.fut_half_spread(ein, K.TICK[P.name], fam) + C53.fut_half_spread(px, K.TICK[P.name], fam)) * lot
    return dict(fgross=float(gross), fnet=float(gross - cost))


# ------------------------------------------------------------------------------------------------ index options
def _idx_strikes(D, und: str, side: str, rows: np.ndarray, money: int = 1) -> np.ndarray:
    step = K51.STEP[und]
    atm = D.atm[rows] if D.atm is not None else np.full(len(rows), np.nan)
    px = D.idx[3, rows]
    k = np.where(np.isfinite(atm), atm, np.round(px / step) * step)
    return k - money * step if side == "C" else k + money * step


@njit(cache=True)
def _first_fill(S, ci, e, sq):
    n = len(ci)
    out = np.full(n, -1, np.int64)
    for i in range(n):
        if ci[i] < 0:
            continue
        o = S[ci[i], 0]
        for j in range(e[i], min(e[i] + 3, sq)):
            if o[j] == o[j] and o[j] > 0:
                out[i] = j
                break
    return out


def idx_option_day(und: str, D, sides, rows_t, fx_rows, qtys):
    """Price several trades of one day. sides 'C'/'P'; rows_t trigger rows; fx_rows: forced exit rows (-1 none).
    Returns arrays (net, gross, rE, rX, filled)."""
    st = E.stack(und, D)
    n = len(rows_t)
    rows_t = np.asarray(rows_t, np.int64)
    ci = np.full(n, -1, np.int64)
    for s in ("C", "P"):
        m = np.array([x == s for x in sides])
        if m.any():
            ks = _idx_strikes(D, und, s, rows_t[m])
            ci[m] = [st.idx(s, k) if np.isfinite(k) else -1 for k in ks]
    sq = E.sq_row(D, E.Exits())
    e = rows_t + 1
    ef = _first_fill(st.S, ci, e, sq)
    fx = np.asarray(fx_rows, np.int64)
    fx = np.where(fx >= 0, np.maximum(fx, ef), -1)
    fx = np.where(fx >= sq, -1, fx)
    ci = np.where(ef >= 0, ci, -1)
    e2 = np.where(ef >= 0, ef, e)
    out_net = np.zeros(n)
    out_gross = np.zeros(n)
    rE = np.full(n, np.nan)
    rX = np.full(n, np.nan)
    ok = np.zeros(n, bool)
    for q in np.unique(qtys):
        sel = np.flatnonzero(np.asarray(qtys) == q)
        res, er, xr, rs = E.simulate_many(st.S, ci[sel], e2[sel], sq, fx[sel], -1.0, -1.0, -1.0, 0.0, 0.0, NOLOCK, -1,
                                          -1.0, -1.0, K51.TICK, float(q), *E.SLIP)
        good = rs != E.R_NOFILL
        a, b = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
        ch = E.charges(und, a, b, float(q), D.d)
        out_net[sel] = np.where(good, (b - a) * q - ch, 0.0)
        out_gross[sel] = np.where(good, (res[:, 1] - res[:, 0]) * q, 0.0)
        rE[sel], rX[sel] = res[:, 2], res[:, 3]
        ok[sel] = good
    return out_net, out_gross, rE, rX, ok


def idx_option(P, und: str, trades: list, days_store: dict, sizing: str = "lot") -> list:
    """Adds net / gross / filled (1 lot or fixed-risk lots) to each trade (a copy)."""
    out = [dict(t) for t in trades]
    by_day: dict = {}
    for i, tr in enumerate(out):
        by_day.setdefault(tr["day"], []).append(i)
    for d, ix in by_day.items():
        dt = P.days[d]
        D = days_store[dt]
        lot = float(K51.lot(und, dt))
        sides = ["C" if out[i]["side"] > 0 else "P" for i in ix]
        rows = [int(P.mt[out[i]["t"]]) - K.NSE_OPEN for i in ix]
        fx = [(int(P.mt[out[i]["xm"]]) - K.NSE_OPEN + 1) if out[i]["why"] != "time" else -1 for i in ix]
        if sizing == "lot":
            q = [lot] * len(ix)
        else:
            q = [lot * lots_for(out[i]["risk"], lot) for i in ix]
        net, gross, rE, rX, ok = idx_option_day(und, D, sides, rows, fx, q)
        for j, i in enumerate(ix):
            out[i].update(net=float(net[j]), gross=float(gross[j]), filled=bool(ok[j]), qty=float(q[j]),
                          rE=float(rE[j]), rX=float(rX[j]))
    return out


# ------------------------------------------------------------------------------------------------ MCX options
@njit(cache=True)
def _mcx_sim(ii, V, j, exit_i, side, stopF, tgtF, F, gday, wait):
    """R53 sim_option without option stops: returns (fill, exit px, fill minute, exit minute, reason, stale) or
    fill NaN for no trade. reason 1 future stop, 3 future target, 4 time."""
    e = j + 1
    a = np.searchsorted(ii, e)
    if a >= len(ii) or ii[a] > e + wait or ii[a] > exit_i:
        return np.nan, np.nan, -1, -1, 0, False
    i_in = ii[a]
    fill = V[a, 0]
    if not fill > 0:
        return np.nan, np.nan, -1, -1, 0, False
    t = exit_i
    why = 4
    for m in range(i_in, exit_i + 1):
        if side * (F[m] - stopF) <= 0:
            t, why = m, 1
            break
        if side * (F[m] - tgtF) >= 0:
            t, why = m, 3
            break
    q = np.searchsorted(ii, t)
    if q < len(ii) and ii[q] == t:
        return fill, V[q, 3], i_in, t, why, False
    if q < len(ii) and ii[q] <= t + 10 and gday[ii[q]] == gday[t]:
        return fill, V[q, 0], i_in, ii[q], why, False
    if q > 0:
        return fill, V[q - 1, 3], i_in, t, why, True
    return fill, np.nan, i_in, t, why, True


class McxBook:
    """The option side of one MCX market, for the numba simulator."""

    def __init__(self, m):
        self.m = m
        self.F = np.asarray(m.F, float)
        dd = m.day.astype("datetime64[D]").astype(np.int64)
        self.gday = dd

    def strike(self, side: int, j):
        return self.m.strike(0 if side > 0 else 1, -1 if side > 0 else 1, j)

    def leg(self, j: int, side: int, k: float):
        return self.m.leg(self.m.day[j], 0 if side > 0 else 1, k)


def mcx_one(B: McxBook, sym: str, j: int, side: int, exit_i: int, stopF: float, tgtF: float, lots: int = 1):
    k = B.strike(side, j)
    if not np.isfinite(k):
        return None
    lg = B.leg(j, side, k)
    if lg is None:
        return None
    ii, V = lg
    fill, px, i_in, t, why, stale = _mcx_sim(ii, V, j, exit_i, side, stopF, tgtF, B.F, B.gday, 2)
    if not np.isfinite(fill) or not np.isfinite(px):
        return None
    mult = K.MCX_MULT[sym] * lots
    buy, sell = fill * mult, px * mult
    cost = S85.opt_cost(K.MCX_HALF_BOOK[sym], buy, sell, int(B.m.tod[i_in]), int(B.m.tod[min(t, len(B.F) - 1)]))
    return dict(net=float(sell - buy - cost), gross=float(sell - buy), fill=float(fill), px=float(px), strike=float(k),
                i_in=int(i_in), i_out=int(t), why_opt=int(why), stale=bool(stale))


def mcx_option(P, B: McxBook, trades: list, sizing: str = "lot") -> list:
    out = []
    g = P.extra["gidx"]
    for tr in trades:
        x = dict(tr)
        j = int(g[tr["t"]])
        exit_i = int(g[P.sq[tr["day"]]])
        lots = 1 if sizing == "lot" else lots_for(tr["risk"], K.MCX_MULT[P.name])
        r = mcx_one(B, P.name, j, tr["side"], exit_i, tr["stop"], tr["target"], lots)
        if r is None:
            x.update(net=0.0, gross=0.0, filled=False, qty=float(lots))
        else:
            x.update(net=r["net"], gross=r["gross"], filled=True, qty=float(lots), stale=r["stale"])
        out.append(x)
    return out


# ------------------------------------------------------------------------------------------------ twins
@njit(cache=True)
def _twin_exits(mo, mh, ml, mc, tt, side, risk, reward, sq):
    n = len(tt)
    xm = np.empty(n, np.int64)
    why = np.empty(n, np.int64)
    for i in range(n):
        t = tt[i]
        ref = mc[t]
        stop = ref - side * risk[i]
        tgt = ref + side * reward[i]
        xm[i] = sq
        why[i] = 3
        for m in range(t + 1, sq):
            if (side > 0 and ml[m] <= stop) or (side < 0 and mh[m] >= stop):
                xm[i], why[i] = m, 1
                break
            if (side > 0 and mh[m] >= tgt) or (side < 0 and ml[m] <= tgt):
                xm[i], why[i] = m, 2
                break
    return xm, why


def _pool(P, d: int, first: int, last: int) -> np.ndarray:
    a, b = P.ms[d], min(P.me[d], P.sq[d])
    t = np.arange(a, b)
    tod = P.mt[a:b]
    return t[(tod >= first) & (tod <= last)]


def twins(P, trades: list, first: int, last: int, n: int, rng, kind: str, und: str | None = None,
          days_store: dict | None = None, B: McxBook | None = None) -> np.ndarray:
    """(n,) twin totals: per trade n random entry minutes in its day's window, the same side, strike rule and point
    distances to the stop and target. kind: 'opt' (option, 1 lot) or 'fut' (futures-only)."""
    tot = np.zeros(n)
    by_day: dict = {}
    for tr in trades:
        by_day.setdefault(tr["day"], []).append(tr)
    for d, trs in by_day.items():
        pool = _pool(P, d, first, last)
        if not len(pool):
            continue
        sq = int(P.sq[d])
        dt = P.days[d]
        for tr in trs:
            s = tr["side"]
            tt = pool[rng.integers(0, len(pool), size=n)]
            rk = np.full(n, tr["risk"])
            rw = np.full(n, tr["reward"])
            xm, why = _twin_exits(P.mo, P.mh, P.ml, P.mc, tt, s, rk, rw, sq)
            if kind == "fut":
                tot += _twin_fut(P, tt, xm, why, s, rk, rw, dt)
            elif P.kind == "nse":
                D = days_store[dt]
                lot = float(K51.lot(und, dt))
                rows = (P.mt[tt] - K.NSE_OPEN).astype(np.int64)
                fx = np.where(why < 3, P.mt[xm] - K.NSE_OPEN + 1, -1)
                net, _, _, _, _ = idx_option_day(und, D, ["C" if s > 0 else "P"] * n, rows, fx, [lot] * n)
                tot += net
            else:
                tot += _twin_mcx(P, B, tt, s, rk, rw, sq)
    return tot


def _twin_fut(P, tt, xm, why, s, rk, rw, dt) -> np.ndarray:
    out = np.zeros(len(tt))
    lot = float(K51.lot(P.name, dt)) if P.kind == "nse" else K.MCX_MULT[P.name]
    for i in range(len(tt)):
        ref = P.mc[tt[i]]
        tr = dict(t=int(tt[i]), xm=int(xm[i]), side=s, entry=ref, stop=ref - s * rk[i], target=ref + s * rw[i],
                  why={1: "stop", 2: "target", 3: "time"}[int(why[i])])
        out[i] = _fut_twin_net(P, tr, lot, dt)
    return out


def _fut_twin_net(P, tr, lot, dt):
    if P.kind == "nse":
        # a twin enters at the close of its minute (no stop order to fill at a level)
        t, xm, s = tr["t"], tr["xm"], tr["side"]
        ein = P.mc[t] * (1 + 0.0001 * s)
        if tr["why"] == "stop":
            px = (min(tr["stop"], P.mo[xm]) if s > 0 else max(tr["stop"], P.mo[xm])) * (1 - 0.0002 * s)
        elif tr["why"] == "target":
            px = tr["target"]
        else:
            px = P.mo[xm] * (1 - 0.0001 * s)
        buy, sell = (ein, px) if s > 0 else (px, ein)
        return s * (px - ein) * lot - idx_fut_charges(buy * lot, sell * lot, dt)
    return fut_trade(P, tr, lot, dt)["fnet"]


def _twin_mcx(P, B, tt, s, rk, rw, sq) -> np.ndarray:
    g = P.extra["gidx"]
    out = np.zeros(len(tt))
    exit_i = int(g[sq])
    jj = g[tt]
    ks = np.array([B.strike(s, int(j)) for j in jj])
    for k in np.unique(ks[np.isfinite(ks)]):
        sel = np.flatnonzero(ks == k)
        for i in sel:
            j = int(jj[i])
            F0 = B.F[j]
            lg = B.leg(j, s, k)
            if lg is None:
                continue
            ii, V = lg
            fill, px, i_in, t, why, stale = _mcx_sim(ii, V, j, exit_i, s, F0 - s * rk[i], F0 + s * rw[i], B.F, B.gday, 2)
            if not (np.isfinite(fill) and np.isfinite(px)):
                continue
            mult = K.MCX_MULT[P.name]
            buy, sell = fill * mult, px * mult
            out[i] = sell - buy - S85.opt_cost(K.MCX_HALF_BOOK[P.name], buy, sell, int(B.m.tod[i_in]),
                                               int(B.m.tod[min(t, len(B.F) - 1)]))
    return out

