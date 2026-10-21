"""R53 MCX trade simulation (PREREG section 2): one held option contract, or the near future (close-only proxy).

Options (after research/hunt/m3/m3lib.py sim_option, R2 cand.py opt_trade and R3 opt.py simulate):
- The strike is read at the SIGNAL minute j from the rolling series' offsets (ATM = offset 0; 1-ITM = offset -1 for a
  call, +1 for a put), then that one contract (day, side, strike) is priced to the exit.
- Entry: the open of the first printed minute in [e, e + wait] (e = j + 1, grid minutes); none: no trade.
- Option stop / target on the printed minutes' low / high from the entry minute on: at the level on the entry minute,
  later at the level or the minute's open when it gapped through (the worse price). Stop before target in one minute.
- Future stop / target on the near future's minute CLOSE (the only price it has); time exit at the given minute.
  Those exits sell the option at that minute's close if it printed, else at the open of the next print within 10 grid
  minutes the same day, else at the last printed close at or before it (marked stale).
- Ties at one minute: option stop, future stop, option target, future target, time.

Futures: fill = the near future's close of the entry minute e = j + 1 (close-only data: the "next bar" is priced at its
close, never its open); stops / targets on minute closes, filled at that close (so a gap through a level fills at the
worse price); time exit at the close of the exit minute.

Costs: common.mcx_opt_charges / mcx_fut_charges + common.opt_half_spread (by entry / exit time of day) /
fut_half_spread per side.
"""
from __future__ import annotations

import numpy as np

from research.hunt.r53 import common as C

NAN = float("nan")


def _opt_cost(fam, buy_rs, sell_rs, tod_in, tod_out):
    return (C.mcx_opt_charges(buy_rs, sell_rs) + C.opt_half_spread(fam, int(tod_in)) * buy_rs
            + C.opt_half_spread(fam, int(tod_out)) * sell_rs)


def _fut_cost(fam, tick, units, p_in, p_out, side):
    buy_rs = (p_in if side > 0 else p_out) * units
    sell_rs = (p_out if side > 0 else p_in) * units
    return C.mcx_fut_charges(buy_rs, sell_rs) + (C.fut_half_spread(p_in, tick, fam) + C.fut_half_spread(p_out, tick, fam)) * units


def strike_offset(side: int, strike: str) -> int:
    if strike == "atm":
        return 0
    if strike == "itm1":
        return -1 if side > 0 else 1
    raise ValueError(strike)


def sim_option(m, j: int, side: int, *, exit_i: int, fam: str, mult: float, strike: str = "itm1", wait: int = 2,
               stop_pct=None, tgt_pct=None, stopF=NAN, tgtF=NAN, hold_from_fill=None):
    """Buy the call (side +1) or put (-1) chosen at signal minute j. exit_i = grid index of the time exit (inclusive).
    hold_from_fill: if set, the time exit is min(exit_i, fill minute + hold_from_fill) (R3's 60 minutes from the fill).
    Returns a trade dict or None (no strike, no print in time, or no room)."""
    n = len(m.ts)
    e = j + 1
    if e >= n or m.day[e] != m.day[j] or exit_i <= e:
        return None
    cp = 0 if side > 0 else 1
    k = m.strike(cp, strike_offset(side, strike), j)
    if not np.isfinite(k):
        return None
    lg = m.leg(m.day[j], cp, k)
    if lg is None:
        return None
    ii, V = lg
    a = np.searchsorted(ii, e)
    if a >= len(ii) or ii[a] > e + wait or ii[a] > exit_i:
        return None
    i_in = int(ii[a])
    fill = V[a, 0]
    if not fill > 0:
        return None
    if hold_from_fill is not None:
        exit_i = min(exit_i, i_in + int(hold_from_fill))
    z = np.searchsorted(ii, exit_i, side="right")
    pi, PV = ii[a:z], V[a:z]                       # printed minutes from the entry to the time exit
    ev = []                                         # (minute index, priority, why, price or None)
    if stop_pct is not None:
        sp = fill * (1 - stop_pct)
        h = np.nonzero(PV[:, 2] <= sp)[0]
        if len(h):
            q = h[0]
            ev.append((int(pi[q]), 0, "opt_stop", sp if q == 0 else min(PV[q, 0], sp)))
    if tgt_pct is not None:
        tp = fill * (1 + tgt_pct)
        h = np.nonzero(PV[:, 1] >= tp)[0]
        if len(h):
            q = h[0]
            ev.append((int(pi[q]), 2, "opt_target", tp if q == 0 else max(PV[q, 0], tp)))
    F = m.F[i_in:exit_i + 1]
    if np.isfinite(stopF):
        h = np.nonzero(side * (F - stopF) <= 0)[0]
        if len(h):
            ev.append((i_in + int(h[0]), 1, "fut_stop", None))
    if np.isfinite(tgtF):
        h = np.nonzero(side * (F - tgtF) >= 0)[0]
        if len(h):
            ev.append((i_in + int(h[0]), 3, "fut_target", None))
    ev.append((int(exit_i), 4, "time", None))
    t, _, why, px = min(ev, key=lambda x: (x[0], x[1]))
    stale = False
    if px is None:
        px, t, stale = price_exit(m, ii, V, t)
    tod_in, tod_out = m.tod[i_in], m.tod[min(t, n - 1)]
    buy, sell = fill * mult, px * mult
    gross = sell - buy
    cost = _opt_cost(fam, buy, sell, tod_in, tod_out)
    return dict(entry_i=i_in, exit_i=int(t), entry_ts=m.ts[i_in], exit_ts=m.ts[min(t, n - 1)], side=int(side),
                strike=float(k), cp=cp, fill=float(fill), exit=float(px), why=why, stale=bool(stale),
                gross=float(gross), cost=float(cost), net=float(gross - cost))


def price_exit(m, ii, V, t: int):
    """Sell price of a held option at minute t: its close if printed, else the next print's open within 10 grid
    minutes the same day, else the last printed close at or before t (stale). Returns (price, minute, stale)."""
    q = np.searchsorted(ii, t)
    if q < len(ii) and ii[q] == t:
        return float(V[q, 3]), int(t), False
    if q < len(ii) and ii[q] <= t + 10 and m.day[ii[q]] == m.day[t]:
        return float(V[q, 0]), int(ii[q]), False
    if q > 0:
        return float(V[q - 1, 3]), int(t), True
    return float("nan"), int(t), True


def sim_fut(m, e: int, side: int, *, exit_i: int, fam: str, units: float, tick: float, stopF=NAN, tgtF=NAN,
            stop_pct=None):
    """Near future (or its mini / proxy, `units` per point of m.F), filled at the close of entry minute e."""
    n = len(m.ts)
    if e >= n or exit_i < e or m.day[exit_i] != m.day[e]:
        return None
    fill = m.F[e]
    if not fill > 0:
        return None
    F = m.F[e:exit_i + 1]
    ev = []
    if np.isfinite(stopF):
        h = np.nonzero(side * (F - stopF) <= 0)[0]
        if len(h):
            ev.append((int(h[0]), 1, "fut_stop"))
    if stop_pct is not None:
        h = np.nonzero(side * (F / fill - 1) <= -stop_pct)[0]
        if len(h):
            ev.append((int(h[0]), 0, "pct_stop"))
    if np.isfinite(tgtF):
        h = np.nonzero(side * (F - tgtF) >= 0)[0]
        if len(h):
            ev.append((int(h[0]), 3, "fut_target"))
    ev.append((len(F) - 1, 4, "time"))
    t, _, why = min(ev, key=lambda x: (x[0], x[1]))
    px = F[t]
    gross = side * (px - fill) * units
    cost = _fut_cost(fam, tick, units, fill, px, side)
    return dict(entry_i=int(e), exit_i=int(e + t), entry_ts=m.ts[e], exit_ts=m.ts[e + t], side=int(side),
                fill=float(fill), exit=float(px), why=why, stale=False, gross=float(gross), cost=float(cost),
                net=float(gross - cost))
