"""h22: Liquidity 15+5 with the rules the phone ran on 1 Oct 2026 morning (LiquidityRules.kt at aeb9226, before
b1e1c12 added the index stop and 20-minute time stop at 13:11 IST, before the 14:00 entry cut-off, the room filter and
1-ITM of later days).

  books: BANKNIFTY 15m + 5m, FINNIFTY 30m + 5m (FIN 30m from 30 Sep 20:30 UTC); one position per book;
  entry: pool taken on an active same-side swing (h4's comps.liq_ext_signals with room=0 and entries to 14:30);
         ATM strike of the signal close, nearest expiry strictly after today, paper MARKET buy at the next minute open;
  exits (first of): resting 15% premium stop (SL-M, fills at trigger or the gapped open, -10 bps); the index touches the
         next liquidity level (target) -> sell next minute open; a completed chart bar closes back through the level
         (failed break) or a new same-side level forms (new liquidity) -> sell at the open after that bar (exit_at);
         15:10 square-off.
Liquidity was first armed for the 1 Oct session (it was added 30 Sep 19:16 UTC), so 28-30 Sep had none.

    flock <scratch>/obuy.lock python3 -I research/hunt/h22/liq_old.py
"""
from __future__ import annotations

import math
import os
import pickle
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h19"))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))

import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, adverse_bps  # noqa: E402
from obuy.data import market  # noqa: E402
import sim as H19  # noqa: E402
import comps  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h22")
COST = Costs("app")
SQ = 15 * 60 + 10 - C.OPEN_M


def px(a, b, buy):
    return float(adverse_bps(np.array([a]), b, buy)[0])


def run(mk, sig, ix_cache):
    rows = []
    busy = {}
    for s in sig.sort_values(["day", "gate"]).itertuples():
        d = pd.Timestamp(s.day).date()
        if busy.get((s.book, d), -1) >= s.gate:
            continue
        ix = mk.index(s.und).d[d]
        if ix.get("exp", False):
            continue  # expiry day: the next contract is not in the data
        ch = mk.options(s.und).chain(d, "near")
        if ch is None:
            continue
        step = C.STEP[s.und]
        k = int(math.floor(s.ref_spot / step + 0.5) * step)
        r = "C" if s.side > 0 else "P"
        ki = ch.kpos(k)
        if ki < 0:
            continue
        o, h, l, c = ch.o[r][ki], ch.h[r][ki], ch.l[r][ki], ch.c[r][ki]
        g = int(s.gate) - C.OPEN_M
        j0 = next((j for j in range(g, min(g + 4, C.W)) if o[j] == o[j]), None)
        if j0 is None or j0 >= SQ:
            continue
        e = px(o[j0], 5, True)
        trig = math.floor(e * 0.85 / 0.05 + 1e-9) * 0.05
        dm = ix_cache[(s.und, d)]
        tgt = s.idx_target
        xa = None if s.exit_at != s.exit_at else int(s.exit_at) - C.OPEN_M
        xm = xp = why = None
        for j in range(j0, C.W):
            if j >= SQ:
                q = next((q for q in range(j, min(j + 5, C.W)) if o[q] == o[q]), None)
                xm, xp, why = (q, px(o[q], 5, False), "square_off") if q is not None else (j, px(c[j - 1], 5, False), "square_off")
                break
            if o[j] == o[j] and l[j] <= trig + 1e-9:
                xm, xp, why = j, px(min(trig, o[j]), 10, False), "prem_stop"; break
            dec = None
            if tgt == tgt and dm[j] is not None:
                hi, lo = dm[j]
                if (s.side > 0 and hi >= tgt) or (s.side < 0 and lo <= tgt):
                    dec = "next_liquidity"
            if dec is None and xa is not None and j >= xa - 1:
                dec = "failed_or_new"
            if dec:
                q = next((q for q in range(j + 1, min(j + 6, C.W)) if o[q] == o[q]), None)
                xm, xp, why = (q, px(o[q], 5, False), dec) if q is not None else (j, px(c[j], 5, False), dec)
                break
        if xm is None:
            continue
        lot = mk.index(s.und).lot(d)
        gross = (xp - e) * lot
        chg = COST.charge_exact(True, e, lot) + COST.charge_exact(False, xp, lot)
        rows.append(dict(und=s.und, day=d, book=s.book, sig_min=int(s.sig_min), side=int(s.side), strike=k, em=j0 + C.OPEN_M,
                         xm=xm + C.OPEN_M, entry=e, exit=xp, lot=lot, why=why, gross=round(gross, 2),
                         charges=round(chg, 2), net=round(gross - chg, 2)))
        busy[(s.book, d)] = xm + C.OPEN_M
    return pd.DataFrame(rows)


def main():
    mk = market()
    for u in ("BANKNIFTY", "FINNIFTY"):
        H19.supplement(mk, u)
    comps.WIN_TO = 14 * 60 + 30
    sig = comps.liq_ext_signals(mk, unds=("BANKNIFTY", "FINNIFTY"), room=0.0)
    sig["book"] = sig.book.str.replace("_BANKNIFTY", "_BN").str.replace("_FINNIFTY", "_FIN")
    ixc = {}
    for u in ("BANKNIFTY", "FINNIFTY"):
        ix = mk.index(u)
        for d in ix.days:
            x = ix.d[d]
            a = [None] * C.W
            for m, hh, ll in zip(x["m"], x["h"], x["l"]):
                j = int(m) - C.OPEN_M
                if 0 <= j < C.W:
                    a[j] = (float(hh), float(ll))
            ixc[(u, d)] = a
    tr = run(mk, sig, ixc)
    tr.to_pickle(os.path.join(OUT, "liq_old.pkl"))
    w = tr[tr.day >= date(2026, 9, 28)]
    print(w.to_string())
    print(w.groupby("day").net.agg(["count", "sum"]))
    tr["y"] = [d.year for d in tr.day]
    print(tr.groupby(["y", "und"]).net.agg(["count", "sum"]))


if __name__ == "__main__":
    main()
