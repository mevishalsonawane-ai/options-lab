"""R55 engine (PREREG.md): R54's hero ticket with the 25% trail armed at the fill (T0 = R50 tr25 = R54) or only after
the option has run up to 2x / 3x / 5x the fill (T2 / T3 / T5), or no trail (H); out at 15:05, 15:15 or held to expiry
settlement.

Everything else is R54's code, imported: the strike picker, the next-minute fill with the stray-print guard and the late
multiplier (`r54lib.enter`), the sell at an open (`r54lib.sell_at`), the held contract's path (`r25lib.path_px`) and
R50's dated charges (`r50lib._settle`). R55 adds:
  - the armed trail (`simulate`);
  - expiry settlement: the index's last-30-minute mean (`settle_price`), intrinsic value for an ITM option, 0 for an OTM
    one, and the charges on an exercised option (`exercise_charges`: STT on exercise at the dated rate, brokerage,
    exchange, SEBI, GST; no stamp, no spread).

Grid: row t = the 1-minute bar that STARTS at 09:15 + t; a decision at row t reads rows <= t and acts from row t + 1.
"""
from __future__ import annotations

import math
from datetime import date

import numpy as np

import r55cfg as K
import r25lib as R
import r50lib as L50
import r54lib as LB
from obuy.costs import _BSE, _NSE, _dn, _rate
from r25data import N, minute

EPS = 1e-9
Trade = L50.Trade

END_ROW = {"1505": LB.bar_start("1505"), "1515": LB.bar_start("1515"), "SETTLE": N}   # N = 15:30, the close
ENTRY_ROW = {e: LB.bar_close(e) for e in K.ENTRIES}


# ------------------------------------------------------------------------------------------------ settlement
def settle_price(ix_close: np.ndarray) -> tuple[float, bool]:
    """(settlement price, flagged). The mean of the index's 1-minute closes on the bars starting 15:00 ... 15:29 (the
    exchanges' last-30-minute closing price, without weights: the lake has no index volume). Fewer than 20 of those
    bars: the day's last index close, flagged. No close at all: (nan, True)."""
    a, b = minute(*K.SETTLE_FROM), minute(*K.SETTLE_TO)
    w = np.asarray(ix_close[a:b + 1], dtype=float)
    w = w[np.isfinite(w)]
    if len(w) >= K.SETTLE_MIN_BARS:
        return float(w.mean()), False
    ok = np.nonzero(np.isfinite(np.asarray(ix_close, dtype=float)))[0]
    return (float(ix_close[ok[-1]]) if len(ok) else math.nan), True


def intrinsic(sgn: int, strike: float, s: float) -> float:
    """Per unit: CE max(0, S - K), PE max(0, K - S); 0 if S is unknown."""
    if math.isnan(s):
        return 0.0
    return max(0.0, (s - strike) if sgn > 0 else (strike - s))


def exercise_charges(value: float, und: str, d: date) -> float:
    """Charges on an exercised ITM option of settlement value `value` (= intrinsic x quantity), Rs (PREREG.md):
    STT on exercise at the dated rate + brokerage Rs 20 + exchange transaction charge + SEBI fee (dated rates; NSE adds
    IPFT) + GST 18% on brokerage + exchange + SEBI. No stamp (no buy). 0 when nothing is paid out."""
    if value <= 0:
        return 0.0
    dn = np.array([_dn(d)])
    exch = float(_rate(_BSE, dn)[0]) if und in K.BSE else float(_rate(_NSE, dn)[0]) + 0.000005
    txn = value * exch
    sebi = value * K.SEBI
    tot = K.BROKERAGE + value * K.stt_exercise(d) + txn + sebi + (K.BROKERAGE + txn + sebi) * K.GST
    return round(tot, 2)


def settle_trade(tr: Trade) -> None:
    """R50's `_settle` for a trade without an expiry exit (identical numbers). An exit marked 'settle' is the exchange's
    cash settlement: intrinsic x quantity in, exercise charges out (no sell charges, no spread); 0 if OTM."""
    if not any(x[3] == "settle" for x in tr.exits):
        L50._settle(tr)
        return
    buy = tr.entry * tr.qty
    ch = L50.charge(True, tr.entry, tr.qty, tr.und, tr.day)
    tr.risked = buy + ch
    got, best = 0.0, 0.0
    for _, q, px, why in tr.exits:
        best = max(best, px)
        if px <= 0:
            continue
        got += px * q
        ch += exercise_charges(px * q, tr.und, tr.day) if why == "settle" else L50.charge(False, px, q, tr.und, tr.day)
    tr.gross = round(got - buy, 2)
    tr.charges = round(ch, 2)
    tr.net = round(tr.gross - ch, 2)
    tr.best_exit_mult = best / tr.entry if tr.entry > 0 else 0.0


# ------------------------------------------------------------------------------------------------ one ticket
def simulate(day, k: float, sgn: int, sig_row: int, ref: float, end: str, exit_: str, mult: float = 1.0,
             rule: str = "") -> Trade | None:
    """One 1-lot ticket on the HELD contract.

    exit_: T0 (R54 tr25: the best close starts at the fill, so a 25% stop too), T2 / T3 / T5 (no stop; armed once a
    minute close >= 2 / 3 / 5 x the fill, then a close <= 0.75 x the best close since arming sells at the next open),
    H (no trail). end: '1505' / '1515' (sell the rest at that bar's open, R54) or 'SETTLE' (trail until the close, the
    rest is cash-settled at the index's last-30-minute mean)."""
    side = "C" if sgn > 0 else "P"
    if (k, side) not in day.opts or day.lot <= 0:
        return None
    end_row = END_ROW[end]
    f = LB.enter(day, k, side, sig_row, ref, end_row, mult)
    if f is None:
        return None
    fr, e = f
    if e * day.lot > K.K54.BUDGET + EPS:
        return None                                         # the lot costs more than Rs 1,000: no ticket
    tr = Trade(day.und, day.day, rule, sgn, k, sig_row, fr, e, 1, day.lot)
    tr.settled = None                                       # 'itm' / 'otm' when held to settlement
    tr.armed_row = None
    a = day.opts[(k, side)]
    arm = K.ARM[exit_]
    armed = arm == 0.0
    if armed:
        tr.armed_row = fr
    last_stop = end_row - 2                                 # closes of bars closing <= end - 1 minute
    best_c = e
    peak = e
    r = fr
    sold = False
    while r < end_row:
        o, hi, c, px = R.path_px(day, k, side, r)
        tr.proxy |= px
        if not math.isnan(hi) and not px:
            conf = R.confirmed(a, r)
            if not math.isnan(conf):
                peak = max(peak, min(LB.guard_high(a, r, hi), conf))
        why = None
        if armed and fr < r <= last_stop and not math.isnan(c) and c <= (1 - K.TRAIL) * max(best_c, c) + EPS:
            why = "trail"
        if not math.isnan(c) and not px:
            if armed:
                best_c = max(best_c, c)
            elif arm is not None and c >= arm * e - EPS:   # arms on this close; it can sell from the next one
                armed = True
                best_c = c
                tr.armed_row = r
        if why:
            rr, p, pxx = LB.sell_at(day, k, side, r + 1, mult)
            tr.proxy |= pxx
            tr.exits.append((rr, tr.qty, p, why))
            sold = True
            break
        r += 1
    if not sold:
        if end == "SETTLE":
            s, flag = settle_price(day.ix[:, 3])
            tr.settle_px, tr.settle_flag = s, flag
            v = intrinsic(sgn, k, s)
            tr.settled = "itm" if v > 0 else "otm"
            tr.exits.append((N, tr.qty, v, "settle"))
        else:
            rr, p, pxx = LB.sell_at(day, k, side, end_row, mult)
            tr.proxy |= pxx
            tr.exits.append((rr, tr.qty, p, "end" + end))
    tr.peak_mult = peak / e
    settle_trade(tr)
    return tr


def run_cell(days: list, entry: str, end: str, exit_: str, mult: float = 1.0) -> list[Trade]:
    t = ENTRY_ROW[entry]
    out = []
    for d in days:
        for sgn in (1, -1):
            pk = LB.pick(d, sgn, t)
            if pk is None:
                continue
            tr = simulate(d, pk[0], sgn, t, pk[1], end, exit_, mult, f"{entry}-{end}-{exit_}")
            if tr is not None:
                out.append(tr)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twin_tables_day(day) -> dict:
    """{(end, exit): {(sgn, t): net or None}} for every minute t of R54's twin window (12:00-14:45 bar closes)."""
    a, b = LB.TWIN_WINDOW
    out = {(z, x): {} for z in K.ENDS for x in K.EXITS}
    for sgn in (1, -1):
        for t in range(a, b + 1):
            pk = LB.pick(day, sgn, t)
            for z in K.ENDS:
                for x in K.EXITS:
                    tr = simulate(day, pk[0], sgn, t, pk[1], z, x) if pk else None
                    out[(z, x)][(sgn, t)] = None if tr is None else tr.net
    return out


twins = LB.twins
p_value = LB.p_value
bh = LB.bh
daily = LB.daily
reading = LB.reading


def paired(a, b) -> dict:
    return LB.paired(a, b, n=K.BOOT, seed=K.SEED)


def metrics(trades: list, days: list) -> dict:
    m = LB.metrics(trades, days)
    st = [getattr(t, "settled", None) for t in trades]
    m["itm"] = sum(s == "itm" for s in st)
    m["otm"] = sum(s == "otm" for s in st)
    m["settle_flagged"] = sum(bool(getattr(t, "settle_flag", False)) for t in trades if getattr(t, "settled", None))
    m["trail_exits"] = sum(t.exits[-1][3] == "trail" for t in trades)
    m["armed"] = sum(getattr(t, "armed_row", None) is not None for t in trades)
    return m


def gates(fm: dict, p: float, q: float, design_net: float) -> dict:
    g = {"net>0": fm["net"] > 0, "p<0.05": p < K.GATE_P, "q<=0.10": q <= K.GATE_Q,
         "40+ tickets": fm["tickets"] >= K.GATE_TRADES, "net without top 5 > 0": fm["ex5"] > 0,
         "design net > 0": design_net > 0}
    g["pass"] = all(g.values())
    return g
