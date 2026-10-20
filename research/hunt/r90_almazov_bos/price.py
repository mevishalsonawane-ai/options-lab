"""R90 pricing (PREREG section 4): the 1-ITM option on real held-contract prints, and the random-entry twins.

Index options: R89's call into R51's engine (research/hunt/r51/engine.py: the app's slippage model R17 'liq', dated
charges), with Boss's flat time 15:15 (15:05 on expiry) instead of R89's 15:10. MCX options: R89's numba port of R53's
sim_option and R85's costs, unchanged (the MCX flat time is the R53 session cut in both studies).

Twins (PREREG 6): per trade, n random entry minutes; the twin enters at that minute's close (the option at the next
minute's open), keeps the trade's side, strike rule and point distances to the stop and target, and exits by the same
underlying rule. 'after': entry minutes from the trade's own fill minute to the window's last minute that day (gated);
'window': anywhere in the day's window (R89's twin, descriptive).
"""
from __future__ import annotations

import numpy as np

from research.hunt.r89_pdhl_break import price as P89
from research.hunt.r89_pdhl_break.price import E, K51, McxBook, mcx_option  # noqa: F401  (re-exported)

from . import cfg as K

FLAT_ROW = K51.row(15, 15)
EXITS = E.Exits(sq_row=FLAT_ROW)          # sq_row() caps it at 15:05 on expiry days


def idx_option_day(und: str, D, sides, rows_t, fx_rows, qtys):
    """R89 price.idx_option_day with the 15:15 / 15:05 square-off. Returns (net, gross, rE, rX, filled)."""
    st = E.stack(und, D)
    n = len(rows_t)
    rows_t = np.asarray(rows_t, np.int64)
    ci = np.full(n, -1, np.int64)
    for s in ("C", "P"):
        m = np.array([x == s for x in sides])
        if m.any():
            ks = P89._idx_strikes(D, und, s, rows_t[m])
            ci[m] = [st.idx(s, k) if np.isfinite(k) else -1 for k in ks]
    sq = E.sq_row(D, EXITS)
    e = rows_t + 1
    ef = P89._first_fill(st.S, ci, e, sq)
    fx = np.asarray(fx_rows, np.int64)
    fx = np.where(fx >= 0, np.maximum(fx, ef), -1)
    fx = np.where(fx >= sq, -1, fx)
    ci = np.where(ef >= 0, ci, -1)
    e2 = np.where(ef >= 0, ef, e)
    out_net, out_gross = np.zeros(n), np.zeros(n)
    rE, rX = np.full(n, np.nan), np.full(n, np.nan)
    ok = np.zeros(n, bool)
    for q in np.unique(qtys):
        sel = np.flatnonzero(np.asarray(qtys) == q)
        res, er, xr, rs = E.simulate_many(st.S, ci[sel], e2[sel], sq, fx[sel], -1.0, -1.0, -1.0, 0.0, 0.0, P89.NOLOCK,
                                          -1, -1.0, -1.0, K51.TICK, float(q), *E.SLIP)
        good = rs != E.R_NOFILL
        a, b = np.nan_to_num(res[:, 2]), np.nan_to_num(res[:, 3])
        ch = E.charges(und, a, b, float(q), D.d)
        out_net[sel] = np.where(good, (b - a) * q - ch, 0.0)
        out_gross[sel] = np.where(good, (res[:, 1] - res[:, 0]) * q, 0.0)
        rE[sel], rX[sel] = res[:, 2], res[:, 3]
        ok[sel] = good
    return out_net, out_gross, rE, rX, ok


def idx_option(P, und: str, trades: list, days_store: dict) -> list:
    """Adds net / gross / filled / qty (1 lot in force) to a copy of each trade."""
    out = [dict(t) for t in trades]
    by_day: dict = {}
    for i, tr in enumerate(out):
        by_day.setdefault(tr["day"], []).append(i)
    for d, ix in by_day.items():
        dt = P.days[d]
        lot = float(K51.lot(und, dt))
        sides = ["C" if out[i]["side"] > 0 else "P" for i in ix]
        rows = [int(P.mt[out[i]["t"]]) - K.NSE_OPEN for i in ix]
        fx = [(int(P.mt[out[i]["xm"]]) - K.NSE_OPEN + 1) if out[i]["why"] != "time" else -1 for i in ix]
        net, gross, rE, rX, ok = idx_option_day(und, days_store[dt], sides, rows, fx, [lot] * len(ix))
        for j, i in enumerate(ix):
            out[i].update(net=float(net[j]), gross=float(gross[j]), filled=bool(ok[j]), qty=lot, rE=float(rE[j]),
                          rX=float(rX[j]))
    return out


def option(I, trades: list) -> list:
    """Option trades for an instrument wrapper I (study.Inst)."""
    if I.nse:
        return idx_option(I.P, I.name, trades, I.store)
    return mcx_option(I.P, I.B, trades, "lot")


# ------------------------------------------------------------------------------------------------ twins
def _pool(P, d: int, lo_t: int, first: int, last: int, sq: int) -> np.ndarray:
    a, b = max(int(P.ms[d]), lo_t), min(int(P.me[d]), sq)
    t = np.arange(a, b)
    tod = P.mt[a:b] if b > a else np.zeros(0)
    return t[(tod >= first) & (tod <= last)]


def twins(I, trades: list, first: int, last: int, n: int, rng, kind: str) -> np.ndarray:
    """(n,) twin totals (option, 1 lot, after costs). kind 'after' or 'window' (module doc)."""
    P = I.P
    tot = np.zeros(n)
    for tr in trades:
        d = int(tr["day"])
        sq = int(I.sq[d])
        pool = _pool(P, d, int(tr["t"]) if kind == "after" else 0, first, last, sq)
        if not len(pool):
            continue
        s = int(tr["side"])
        tt = pool[rng.integers(0, len(pool), size=n)]
        rk = np.full(n, float(tr["risk"]))
        rw = np.full(n, float(tr["reward"]))
        if I.nse:
            xm, why = P89._twin_exits(P.mo, P.mh, P.ml, P.mc, tt, s, rk, rw, sq)
            dt = P.days[d]
            lot = float(K51.lot(I.name, dt))
            rows = (P.mt[tt] - K.NSE_OPEN).astype(np.int64)
            fx = np.where(why < 3, P.mt[xm] - K.NSE_OPEN + 1, -1)
            net, _, _, _, _ = idx_option_day(I.name, I.store[dt], ["C" if s > 0 else "P"] * n, rows, fx, [lot] * n)
            tot += net
        else:
            tot += P89._twin_mcx(P, I.B, tt, s, rk, rw, sq)
    return tot
