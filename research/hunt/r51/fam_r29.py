"""R51 family: R29 EMA ribbons. Signal source: the INDEX (ribbon EMA 5/8/13/21/34/55/89/200 and 9/21/50/200 on 1/3/5/15-
minute index bars; research/brain/features/ribbon.py through research/hunt/r29/patterns.py, unchanged).

Events from the index minutes of 2023 on (the 60-session warm-up and EMA 200 need history; nothing before 20 Nov 2024 is
traded). Signal bars closing 09:30-14:45; the option (ATM, nearest expiry) fills at the next minute's open. X1: stop
-30%, lock +Rs 1,000 keep Rs 500 (trailing Rs 500), 60-minute time exit, square-off 15:20 (15:05 on expiry day); one
trade at a time per rule and index, at most 3 a day. Follow buys the event's direction, fade the other side.
"""
from __future__ import annotations

import pickle
from functools import lru_cache

import numpy as np
import pandas as pd

import common as C
import engine as E
import oldcode
import r51cfg as K
import r51data

P29 = oldcode.load("r29", "patterns")
D29 = oldcode.load("r29", "r29data")
R29 = oldcode.load("r29", "rules")
SQ = K.row(15, 20)
FIRST, LAST = 15, 330                  # entry rows: signal rows 14..329 (+1)
EXITS = {"X1": E.Exits(stop=0.30, lock_trig=1000, lock_keep=500, lock_trail=500, tmax=60, sq_row=SQ),
         "X1t30": E.Exits(stop=0.30, lock_trig=1000, lock_keep=500, lock_trail=500, tmax=30, sq_row=SQ),
         "S20T40": E.Exits(stop=0.20, target=0.40, tmax=60, sq_row=SQ)}
BOSS = [  # Boss's three ideas (R29 Part A), traded follow like R29 mined them
    ("P9_5", "Boss idea 1: 15-min stack + 5-min pullback"),
    ("P11_5", "Boss idea 2: squeeze then fan-out (5-min, 15-min order >= 5/7)"),
    ("P5_15", "Boss idea 2: squeeze-break onset on 15-min bars"),
    ("P8_1", "Boss idea 3: 1/5/15-min alignment onset"),
]


@lru_cache(maxsize=8)
def events(und: str) -> pd.DataFrame:
    p = K.OUT / "r29" / f"events_{und}.pkl"
    if p.exists():
        return pd.read_pickle(p)
    M = D29.index_minutes(und)
    M = M[pd.to_datetime(M.day).dt.date <= K.END].reset_index(drop=True)
    Ev = P29.events(M, und)
    B0 = P29.ema_cross_events(M, und)
    X = pd.concat([Ev, B0], ignore_index=True)[["pattern", "day", "m", "sign"]]
    X = X[X.day >= K.START].reset_index(drop=True)
    p.parent.mkdir(parents=True, exist_ok=True)
    X.to_pickle(p)
    return X


def make(pattern: str, sign, mode: str, ex_key: str = "X1", money: int = 0) -> C.Config:
    ex = EXITS[ex_key]

    def run(lo, hi):
        rows = []
        for und in K.UNDS:
            Ev = events(und)
            Ev = Ev[Ev.pattern == pattern]
            if sign is not None:
                Ev = Ev[Ev.sign == sign]
            Ev = Ev.drop_duplicates(["day", "m", "sign"])
            days = r51data.load(und)
            by = dict(tuple(Ev.groupby("day")))
            for d in C.days_in(und, lo, hi):
                if d not in by:
                    continue
                D = days[d]
                cands = []
                for m, s in zip(by[d].m.values, by[d].sign.values):
                    side = ("C" if s > 0 else "P") if mode == "follow" else ("P" if s > 0 else "C")
                    er = int(m) + 1
                    k = E.strike_for(D, und, side, int(m), money)
                    if k is not None and FIRST <= er <= LAST:
                        cands.append(dict(er=er, side=side, strike=k))
                rows += C.take(und, D, sorted(cands, key=lambda c: c["er"]), ex, max_per_day=3, scope="all")
        return C.frame(rows)

    return C.Config(f"r29|{pattern}|{sign}|{mode}|{ex_key}|m{money}", run,
                    lambda und: C.TwinSpec(ex, FIRST, LAST, money))


def _grid(p, s, m):
    base = make(p, s, m)
    return [base] + [make(p, s, m, e, k) for e in EXITS for k in (0, 1) if (e, k) != ("X1", 0)]


def variants() -> list:
    src = "index (EMA ribbons on 1/3/5/15-min bars)"
    rules = [("B0", None, "follow", "B0: EMA 9 x 21 cross on 5-min bars, follow")]
    rules += [(p, None, "follow", d) for p, d in BOSS]
    rules += [(r["patterns"][0], r["sign"], r["mode"], f"{r['id']}: {r['name']}") for r in R29.RULES]
    out = []
    for i, (p, s, m, d) in enumerate(rules):
        rid = "B0" if p == "B0" else (f"BOSS-{p}" if i <= len(BOSS) else d.split(":")[0])
        g = _grid(p, s, m)
        out.append(C.Variant(f"R29-{rid}", "R29 EMA ribbons", src, "orig", g[:1], d + "; ATM, X1"))
        out.append(C.Variant(f"R29-{rid}-RT", "R29 EMA ribbons", src, "retune", g,
                             "re-tune: exit {X1, X1 30 min, -20%/+40%/60 min} x strike {ATM, 1-ITM}"))
    return out
