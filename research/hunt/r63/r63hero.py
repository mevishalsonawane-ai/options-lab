"""R63 Hero Rs 1,000 ticket: R55's cell 12:30 / 15:05 / T2 (the app's "trail after 2x", mode "trail only") with a
trend-turn exit on the INDEX added (PREREG.md section 3).

Everything is R55 / R54's code, imported: the picker (`r54lib.pick`), the next-minute limit fill (`r54lib.enter`), the
held contract's path (`r25lib.path_px`), the sell at an open (`r54lib.sell_at`, R17 liq ticks x the late multiplier) and
R55's settle (`r55lib.settle_trade`, R50's dated charges). `simulate` is R55's loop with one addition: at the open of
the forced row x (a trend act row) whatever is still held is sold there (the trail and the 15:05 end still come first
when they are earlier). With x = -1 it is R55's ticket exactly (checked on every ticket by `run`).
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r55"))
import r55cfg as K55  # noqa: E402  (puts R54, R50, R43, R25 on the path)
import r54data as DD  # noqa: E402
import r54lib as LB  # noqa: E402
import r55lib as L  # noqa: E402
import r25lib as R  # noqa: E402

import r63cfg as K  # noqa: E402
import r63data  # noqa: E402
import r63rules as RR  # noqa: E402

ENTRY, END, EXIT = "1230", "1505", "T2"
EPS = 1e-9


def simulate(day, k: float, sgn: int, sig_row: int, ref: float, x: int = -1, mult: float = 1.0):
    """R55 simulate(end 15:05, exit T2) with a forced sale at the open of row x (x < 0: none)."""
    side = "C" if sgn > 0 else "P"
    if (k, side) not in day.opts or day.lot <= 0:
        return None
    end_row = L.END_ROW[END]
    f = LB.enter(day, k, side, sig_row, ref, end_row, mult)
    if f is None:
        return None
    fr, e = f
    if e * day.lot > K55.K54.BUDGET + EPS:
        return None
    tr = L.Trade(day.und, day.day, f"{ENTRY}-{END}-{EXIT}", sgn, k, sig_row, fr, e, 1, day.lot)
    tr.settled = None
    tr.armed_row = None
    a = day.opts[(k, side)]
    arm = K55.ARM[EXIT]
    armed = False
    last_stop = end_row - 2
    best_c = e
    peak = e
    r = fr
    sold = False
    while r < end_row:
        if 0 <= x == r:                                   # the trend rule's act row: sell at this minute's open
            rr, p, pxx = LB.sell_at(day, k, side, r, mult)
            tr.proxy |= pxx
            tr.exits.append((rr, tr.qty, p, "trend"))
            sold = True
            break
        o, hi, c, px = R.path_px(day, k, side, r)
        tr.proxy |= px
        if not math.isnan(hi) and not px:
            conf = R.confirmed(a, r)
            if not math.isnan(conf):
                peak = max(peak, min(LB.guard_high(a, r, hi), conf))
        why = None
        if armed and fr < r <= last_stop and not math.isnan(c) and c <= (1 - K55.TRAIL) * max(best_c, c) + EPS:
            why = "trail"
        if not math.isnan(c) and not px:
            if armed:
                best_c = max(best_c, c)
            elif arm is not None and c >= arm * e - EPS:
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
        rr, p, pxx = LB.sell_at(day, k, side, end_row, mult)
        tr.proxy |= pxx
        tr.exits.append((rr, tr.qty, p, "end" + END))
    tr.peak_mult = peak / e
    L.settle_trade(tr)
    return tr


def tickets(days: list) -> list:
    """The plain tickets: (day, sgn, strike, sig_row, ref, Trade) for every expiry day and side."""
    t = L.ENTRY_ROW[ENTRY]
    out = []
    for d in days:
        for sgn in (1, -1):
            pk = LB.pick(d, sgn, t)
            if pk is None:
                continue
            tr = simulate(d, pk[0], sgn, t, pk[1])
            if tr is not None:
                out.append((d, sgn, pk[0], t, pk[1], tr))
    return out


def run(period: str):
    LB.load_late()
    days = []
    for u in K.UNDS_OF["HERO"]:
        days += DD.load(u, period)
    days.sort(key=lambda d: (d.day, d.und))
    tk = tickets(days)
    ref = {(t.und, t.day, t.side): t.net for t in L.run_cell(days, ENTRY, END, EXIT)}
    for d, sgn, k, sr, rf, tr in tk:                      # x = -1 must be R55's ticket exactly
        if abs(ref[(tr.und, tr.day, tr.side)] - tr.net) > 0.011:
            raise AssertionError("R63 HERO: the plain ticket does not reproduce R55's")
    tr_rows = {u: r63data.turns(u) for u in K.UNDS_OF["HERO"]}
    rows = []
    for d, sgn, k, sr, rf, tr in tk:
        rows.append(dict(und=tr.und, day=tr.day, side="C" if sgn > 0 else "P", strike=k, er=tr.fill_row,
                         xr=tr.exits[-1][0], net=tr.net, why=tr.exits[-1][3], sig_row=sr, ref=rf, exk="HERO",
                         e_sig=sr, fx_own=-1))
    T = pd.DataFrame(rows)
    twins = {}
    byday = {(d.und, d.day): d for d in days}
    for j, rule in enumerate(RR.RULES):
        x = np.array([r63data.trend_row(tr_rows[u], rule, dd, s, int(e)) for u, dd, s, e in
                      zip(T.und, T.day, T.side, T.er)], np.int64) if len(T) else np.zeros(0, np.int64)
        nets, xrs, whys = [], [], []
        for i, (d, sgn, k, sr, rf, tr0) in enumerate(tk):
            t2 = simulate(d, k, sgn, sr, rf, int(x[i])) if x[i] >= 0 else tr0
            nets.append(t2.net)
            xrs.append(t2.exits[-1][0])
            whys.append(t2.exits[-1][3])
        T[f"x_{rule}"] = x
        T[f"net_{rule}"] = nets
        T[f"xr_{rule}"] = xrs
        T[f"why_{rule}"] = whys
        T[f"chg_{rule}"] = (np.array(xrs) != T.xr.to_numpy()) & (x >= 0) if len(T) else []
        rng = np.random.default_rng([K.SEED, K.STRATS.index("HERO"), j, 0 if period == "design" else 1])
        tot = np.zeros(K.TWINS)
        for i in np.flatnonzero(T[f"chg_{rule}"].to_numpy()) if len(T) else []:
            d, sgn, k, sr, rf, tr0 = tk[i]
            lo, hi = int(T.er[i]) + RR.MIN_OPEN, max(int(T.xr[i]) - 1, int(T.er[i]) + RR.MIN_OPEN)
            rows_ = rng.integers(lo, hi + 1, size=K.TWINS)
            cache = {}
            for n_, rr in enumerate(rows_):
                rr = int(rr)
                if rr not in cache:
                    cache[rr] = simulate(byday[(tr0.und, tr0.day)], k, sgn, sr, rf, rr).net
                tot[n_] += cache[rr] - tr0.net
        twins[rule] = tot
    return T, twins
