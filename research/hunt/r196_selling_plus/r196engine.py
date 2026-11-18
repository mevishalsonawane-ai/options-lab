"""R196 engine (PREREG.md section 2): two short legs walked minute by minute, so one leg's stop can depend on the other
leg's exit (stop to cost) and a lock can read the whole pair. Marks, fills, charges and margin are R195's
(`r195engine`), unchanged. With only per-leg stops it reproduces R195's S1 trade for trade (test_r196.py).

Decisions read minute closes up to the decision minute; every exit fills at the NEXT minute's open.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r195_option_selling"))
import r195cfg as K  # noqa: E402
import r195engine as E  # noqa: E402


def run_short_pair(D, und, d, legs, decide, expiry, expiry_day, leg_stop=None, comb_stop=None, sl_to_cost=False,
                   lock=None, mult=1.0, tag=""):
    """legs: [(side, strike), ...] all SOLD. leg_stop: a leg exits when its mark >= leg_stop x its fill. comb_stop: all
    exit when the open legs' marks summed >= comb_stop x their fills summed. sl_to_cost: after a leg is stopped, every
    other open leg's stop moves down to its own fill. lock = (arm, keep): once the pair's P&L (open legs at marks,
    closed legs at their exit fills) reaches arm x credit, all exit if it falls to keep x credit."""
    q, bse = K.LOT[und], und in K.BSE
    L = [E.Leg(s, float(k), -1, i) for i, (s, k) in enumerate(legs)]
    er = decide + 1
    sq = K.SQ_ROW_EXPIRY if expiry_day else K.SQ_ROW
    if er >= sq:
        return None
    for l in L:
        if not E.fresh_at(D, l, decide) or E.real_open(D, l, er) is None:
            return None
    entry, ch = [], 0.0
    for l in L:
        p, _ = E.fill(D, l, er, False, d, expiry, q, mult)
        entry.append(p)
        ch += E.charge(p, q, False, d, bse)
    entry = np.array(entry)
    credit = float(entry.sum() * q)
    n = sq - er + 1
    M = np.empty((len(L), n))
    stale = 0
    for i, l in enumerate(L):
        M[i], st = E.marks(D, l, er, sq, d, expiry)
        stale += int(st.any())
    level = np.full(len(L), np.inf) if leg_stop is None else leg_stop * entry
    xrow = [None] * len(L)
    why = [None] * len(L)
    xpx = [None] * len(L)
    armed = False

    def close(i, r, w):
        p, mdl = E.fill(D, L[i], r, True, d, expiry, q, mult * (2.0 if w == "stop" else 1.0))
        xrow[i], why[i], xpx[i] = r, w, p
        return mdl

    modelled = 0
    for j in range(n - 1):
        r_next = er + j + 1
        op = [i for i in range(len(L)) if xrow[i] is None]
        if not op:
            break
        if comb_stop is not None and M[op, j].sum() >= comb_stop * entry[op].sum():
            for i in op:
                modelled += close(i, r_next, "stop")
            break
        hit = [i for i in op if M[i, j] >= level[i]]
        for i in hit:
            modelled += close(i, r_next, "stop")
        if hit and sl_to_cost:
            for i in range(len(L)):
                if xrow[i] is None:
                    level[i] = min(level[i], entry[i])
        if lock is not None:
            pnl = sum((entry[i] - (M[i, j] if xrow[i] is None else xpx[i])) * q for i in range(len(L)))
            if pnl >= lock[0] * credit:
                armed = True
            elif armed and pnl <= lock[1] * credit:
                for i in range(len(L)):
                    if xrow[i] is None:
                        modelled += close(i, r_next, "lock")
                break
    for i in range(len(L)):
        if xrow[i] is None:
            modelled += close(i, sq, "time")
    for p in xpx:
        ch += E.charge(p, q, True, d, bse)
    gross = float(((entry - np.array(xpx)) * q).sum())
    return dict(und=und, day=d, tag=tag, decide=decide, legs=[(l.side, l.k, -1, l.group) for l in L],
                entry=list(entry), exitpx=xpx, exits=xrow, why="+".join(sorted(set(why))), credit=round(credit, 2),
                max_loss=None, margin=E.margin(L, q, d, expiry_day), gross=round(gross, 2), charges=round(ch, 2),
                net=round(gross - ch, 2), stale_legs=stale, model_fills=modelled)
