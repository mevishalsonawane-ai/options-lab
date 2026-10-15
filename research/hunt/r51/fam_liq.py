"""R51 family: Liquidity 15+5 (LiquidityRules.kt), signal on the INDEX bars, 1-ITM option bought.

LIQ-FIN: the parked FINNIFTY books (30-minute + 5-minute). G7: R28's GEX filter on the live BANKNIFTY books (15 + 5),
skip when gex_norm_20d > +1 at the signal minute, against the unfiltered arm (a reference).

Levels and signals: research/hunt/h4/comps.py (the app's rules ported; swing_zones / pool_zones / fold reused unchanged),
from 10 calendar days of index minutes. Entry at the open of the minute after the breaking bar closes, 09:20-14:00, room
>= `room` index stops to the next level. Exits: -15% premium stop, 20-minute time stop unless +5%, 15:10; the index
exits (index stop through the broken level, next-level target: sold at the next minute's open; failed break and new
level: at the bar end) as one forced exit row. One position per book. Expiry days skipped.
"""
from __future__ import annotations

from datetime import timedelta
from functools import lru_cache

import numpy as np

import common as C
import engine as E
import features as FT
import oldcode
import r51cfg as K
import r51data

H4 = oldcode.load("h4", "comps")
OPEN_M = 9 * 60 + 15
EX = E.Exits(stop=0.15, tmax=20, need=0.05)
FIRST, LAST = K.row(9, 20), K.row(14, 0)
IDX_STOP = {"BANKNIFTY": 30.0, "FINNIFTY": 15.0}
ORIG_BOOKS = {"BANKNIFTY": (15, 5), "FINNIFTY": (30, 5)}


@lru_cache(maxsize=32)
def signals(und: str, tf: int, room: float):
    """{day: [signal dict]} for one book over every tradable day (levels need only past minutes)."""
    days = r51data.load(und)
    step, istop = K.STEP[und], IDX_STOP[und]
    hist, out = [], {}
    for d in sorted(days):
        D = days[d]
        a = D.idx
        ok = np.isfinite(a[3])
        today1 = [(d, OPEN_M + r, float(a[0, r]), float(a[1, r]), float(a[2, r]), float(a[3, r]))
                  for r in np.flatnonzero(ok)]
        hist = [hh for hh in hist if hh[0] >= d - timedelta(days=10)]
        if d >= K.START and D.atm is not None and not D.expiry and hist:
            ones = [b for hh in hist for b in hh[1]] + today1
            bars = H4.fold(ones, tf)
            if len(bars) >= 2 * H4.L + 2:
                out[d] = _day_signals(d, bars, tf, istop, step, room)
        hist.append((d, today1))
    return out


def _day_signals(d, bars, tf, istop, step, room):
    O = np.array([b[2] for b in bars]); H = np.array([b[3] for b in bars])
    Lo = np.array([b[4] for b in bars]); Cl = np.array([b[5] for b in bars])
    sw_, po = H4.swing_zones(H, Lo, Cl), H4.pool_zones(O, H, Lo, Cl)
    zones = sw_ + po
    n = len(bars)
    is_today = [b[0] == d for b in bars]
    bar_end = [b[1] + tf if is_today[k] else -1 for k, b in enumerate(bars)]
    by_break = {}
    for p in po:
        if p.broken >= 0:
            by_break.setdefault(p.broken, []).append(p)
    rows = []
    for i in range(n):
        if not is_today[i] or bars[i][1] + tf > 930 or i not in by_break:
            continue
        pool = None
        for p in by_break[i]:
            if p.known > i:
                continue
            if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                   and (s.broken < 0 or s.broken >= i) for s in sw_):
                pool = p
                break
        if pool is None:
            continue
        side, close = pool.side, Cl[i]
        ahead = [q.edge for q in zones if q.side == side and q.known <= i and (q.broken < 0 or q.broken > i)
                 and side * (q.edge - close) > 0]
        target = (min(ahead) if side > 0 else max(ahead)) if ahead else None
        level = pool.edge
        fb = next((bar_end[k] for k in range(i + 1, n) if side * (Cl[k] - level) < 0), None)
        nl = [bar_end[z.known] for z in zones if z.side == side and z.known >= i + 1]
        nl = min(nl) if nl else None
        done = bar_end[i]
        if done < OPEN_M + FIRST or done > OPEN_M + LAST:
            continue
        if target is not None and side * (target - close) < room * istop:
            continue
        xa = [v - OPEN_M for v in (fb, nl) if v is not None]
        rows.append(dict(er=done - OPEN_M, side="C" if side > 0 else "P", sgn=side, close=float(close),
                         strike=H4.atm(close, step) - side * step, idx_stop=level - side * istop,
                         target=np.nan if target is None else float(target), bar_exit=min(xa) if xa else -1))
    return rows


def forced_row(D, s) -> int:
    """First exit row from the index rules: index stop / target touched in a minute -> the next minute's open;
    failed break / new level -> that bar's end."""
    a = D.idx
    er = s["er"]
    fx = s["bar_exit"] if s["bar_exit"] >= 0 else 10 ** 6
    lo, hi = a[2, er:], a[1, er:]
    if s["sgn"] > 0:
        hit = np.flatnonzero((lo <= s["idx_stop"]) | (hi >= s["target"] if np.isfinite(s["target"]) else False))
    else:
        hit = np.flatnonzero((hi >= s["idx_stop"]) | (lo <= s["target"] if np.isfinite(s["target"]) else False))
    if len(hit):
        fx = min(fx, er + int(hit[0]) + 1)
    return int(fx) if fx < K.NROWS else -1


def make(und: str, books: tuple, room: float = 1.0, gex_skip: bool = False) -> C.Config:
    def run(lo, hi):
        days = r51data.load(und)
        rows = []
        for tf in books:
            sig = signals(und, tf, room)
            for d in C.days_in(und, lo, hi):
                if d not in sig:
                    continue
                D = days[d]
                cands = []
                for s in sig[d]:
                    if gex_skip:
                        g = FT.value(und, d, s["er"] - 1, "gex_norm_20d")
                        if g is not None and g > 1.0:          # a trade with no GEX value is kept (R28)
                            continue
                    c = dict(s, fx=forced_row(D, s), book=tf)
                    cands.append(c)
                rows += C.take(und, D, cands, EX, scope="all")
        return C.frame(rows)

    name = f"liq|{und}|{'+'.join(map(str, books))}|room{room}" + ("|gex" if gex_skip else "")
    return C.Config(name, run, lambda u: C.TwinSpec(EX, FIRST, LAST, 1))


def variants() -> list:
    fin = "FINNIFTY"
    grid = [make(fin, (30, 5), 1.0)] + [make(fin, b, r) for b in ((30, 5), (15, 5), (30,), (5,)) for r in (1.0, 2.0)
                                        if (b, r) != ((30, 5), 1.0)]
    one = dict(unds=("FINNIFTY",))
    v = [C.Variant("LIQ-FIN", "Liquidity 15+5 (paused arm)", "index (30-min + 5-min bars)", "orig", grid[:1],
                   "Liquidity FINNIFTY 30m + 5m books, 1-ITM, the app's exits", extra=dict(one)),
         C.Variant("LIQ-FIN-RT", "Liquidity 15+5 (paused arm)", "index (bars)", "retune", grid,
                   "re-tune: books {30+5, 15+5, 30, 5} x room {1, 2} index stops", extra=dict(one)),
         C.Variant("LIQ-BN-base", "Liquidity 15+5 (live arm)", "index (15-min + 5-min bars)", "ref",
                   [make("BANKNIFTY", (15, 5))], "Liquidity BANKNIFTY 15+5 unfiltered (reference)",
                   extra=dict(unds=("BANKNIFTY",))),
         C.Variant("G7-GEX-LIQ-BN", "R28 GEX filter", "index bars + option-chain GEX filter", "orig",
                   [make("BANKNIFTY", (15, 5), gex_skip=True)],
                   "Liquidity BANKNIFTY 15+5, skip when gex_norm_20d > +1 at the signal minute",
                   extra=dict(unds=("BANKNIFTY",)))]
    return v
