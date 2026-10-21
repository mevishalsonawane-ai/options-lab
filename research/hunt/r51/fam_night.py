"""R51 family: the Night (R3) arm's rejected add-ons - R27's 8 hold variants and R28's GEX filter (G10).

Signal source: the index (15:19 close location and move), 59-stock breadth, and the option chain's 15:19 OI build-up
as a filter (research/hunt/r27/r27data.py, unchanged; nights built into <R51_OUT>/r27 from the lake). NIFTY nearest
weekly and BANKNIFTY nearest monthly, 1-ITM, bought at the 15:20 open (first minute 15:20-15:23), the SAME strike held
to the next morning. B0 sells at the next session's 09:16 open (the arm itself: a reference). R27's exits (r27lib.py,
unchanged): option levels on the minute high / low with gap fills at the open, index levels sold at the next open,
10:30 cap.

Costs (R51): Costs('dated') charges, +-5 bps (stops -10 bps), and a half-spread of max(2 ticks, 0.16% of the price)
on each side (R27's measured half-spread for these contracts; the nights' option bars carry no volume for the liq rule).
A night is in the design when it is entered on or before 31 Mar 2026 AND its morning is too (no final-test minute).
"""
from __future__ import annotations

import pickle
from dataclasses import replace
from datetime import date
from functools import lru_cache

import numpy as np

import common as C
import engine as E
import features as FT
import oldcode
import r51cfg as K

L27 = oldcode.load("r27", "r27lib")
oldcode.load("r27", "r27data")             # puts r27/ on sys.path so the pickled r27data.Night records load
NIGHTS = K.OUT / "r27"
HALF = 0.0016
STOP_WHY = ("stop", "stop_gap", "lock", "lock_gap", "idx_stop")


@lru_cache(maxsize=2)
def nights(und: str):
    return pickle.loads((NIGHTS / f"nights_{und}.pkl").read_bytes())


def in_range(n, lo, hi) -> bool:
    C.check_range(lo, hi)
    return lo <= n.d and n.nd <= hi if hi <= K.DESIGN_END else lo <= n.d <= hi


def _r2(x):
    return round(x * 100) / 100


def net_of(n, t) -> dict:
    q = n.qty
    e, x = t.entry, t.exit
    hb, hs = max(2 * K.TICK, HALF * e), max(2 * K.TICK, HALF * x)
    re_ = _r2(e * 1.0005) + hb
    if t.why == "target":
        rx = x
    elif t.why in STOP_WHY:
        rx = max(_r2(x * 0.999) - hs, 0.0)
    else:
        rx = max(_r2(x * 0.9995) - hs, 0.0)
    ch = float(E.charges(n.und, re_, rx, q, n.d)[0])
    return dict(und=n.und, day=n.d, side="C" if t.side > 0 else "P", strike=float(n.strike.get(t.side, np.nan)),
                er=K.row(15, 20), xr=int(t.row), E=e, X=x, rE=re_, rX=rx, qty=float(q), why=t.why,
                net=(rx - re_) * q - ch, gross=(x - e) * q, held=bool(t.held))


def gex_ok(n) -> bool:
    v = FT.value(n.und, n.d, K.row(15, 15), "gex_net_cr")
    return v is None or v < 0                       # no value -> kept (R28)


def make(spec, gex: bool = False, name: str | None = None) -> C.Config:
    def run(lo, hi):
        rows = []
        for und in ("NIFTY", "BANKNIFTY"):
            for n in nights(und):
                if n.r3 == 0 or not in_range(n, lo, hi):
                    continue
                if gex and not gex_ok(n):
                    continue
                t = L27.trade(n, n.r3, spec)
                if t is not None:
                    rows.append(net_of(n, t))
        return C.frame(rows)

    def twin_fn(T, nt, rng):
        """Random-entry twins: the same number of nights per index drawn from every tradable night in the same
        window (random side), the same exits. For the GEX filter: random subsets of the unfiltered R3 nights."""
        tot = np.zeros(nt)
        lo, hi = min(T.day), max(T.day)
        for und, g in T.groupby("und"):
            if gex:
                pool = [n for n in nights(und) if n.r3 != 0 and lo <= n.d <= hi]
                vals = []
                for n in pool:
                    t = L27.trade(n, n.r3, spec)
                    if t is not None:
                        vals.append(net_of(n, t)["net"])
                vals = np.array(vals)
                k = len(g)
                for i in range(nt):
                    tot[i] += vals[rng.choice(len(vals), size=min(k, len(vals)), replace=False)].sum()
                continue
            pool = []
            for n in nights(und):
                if not n.tradable or not (lo <= n.d <= hi) or n.ix is None:
                    continue
                for side in (1, -1):
                    t = L27.trade(n, side, spec)
                    if t is not None:
                        pool.append((n.d, net_of(n, t)["net"]))
            ds = np.array([p[0] for p in pool], dtype=object)
            vals = np.array([p[1] for p in pool])
            uniq = sorted(set(ds))
            idx = {d: np.flatnonzero(ds == d) for d in uniq}
            k = len(g)
            for i in range(nt):
                pick = rng.choice(len(uniq), size=min(k, len(uniq)), replace=False)
                tot[i] += sum(vals[rng.choice(idx[uniq[j]])] for j in pick)
        return tot

    return C.Config(name or f"night|{spec.id}" + ("|gex" if gex else ""), run, None, twin_fn)


def retune_specs():
    return [L27.Spec(f"RT_P{int(p)}", "always", "prem", p, "", "prem", p) for p in (10.0, 15.0, 20.0, 30.0, 40.0)]


def sessions(lo, hi):
    return len({n.d for u in ("NIFTY", "BANKNIFTY") for n in nights(u) if in_range(n, lo, hi)})


def variants() -> list:
    ex = dict(unds=("NIFTY", "BANKNIFTY"), sessions=sessions)
    v = [C.Variant("NIGHT-B0", "Night (R3) arm", "index close + breadth + OI build-up", "ref", [make(L27.B0)],
                   "Night R3, sold at the 09:16 open (the arm as it runs; reference)", extra=dict(ex))]
    for s in L27.VARIANTS:
        v.append(C.Variant(f"NIGHT-{s.id}", "R27 Night holds", "index close + breadth + OI build-up", "orig",
                           [make(s)], f"Night R3 held past 09:16: {s.id} (10:30 cap)", extra=dict(ex)))
    rt = retune_specs()
    grid = [make(L27.BY_ID["V5_H1_P20"])] + [make(s) for s in rt]
    v.append(C.Variant("NIGHT-HOLD-RT", "R27 Night holds", "index close + breadth + OI build-up", "retune", grid,
                       "re-tune: option target = stop Rs {10, 15, 20, 30, 40} a unit, 10:30 cap", extra=dict(ex)))
    v.append(C.Variant("G10-GEX-NIGHT", "R28 GEX filter", "index + breadth + OI; GEX filter", "orig",
                       [make(L27.B0, gex=True)], "Night R3 (09:16 sale) only when S1 net GEX < 0 at 15:15",
                       extra=dict(ex)))
    return v
