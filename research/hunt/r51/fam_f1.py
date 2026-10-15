"""R51 family: the F1 Strategy Factory's 20 rules (C1, C2, S1-S18; research/hunt/f1/results/rules.json, unchanged).

Signal source: the underlying's brain features (index trend / volatility / levels), with option-chain features (IV skew,
OI build-up, GEX share, option-volume VWAP, Greeks) as context or filters, exactly as F1 defined them. Feature rows
come from the brain feature store built from the lake (features.py). Decisions every 5 minutes 09:30-14:30 (rows
15..315); quintile rules compare a row's score with the same score on the reference rows: every earlier window row of
that index (expanding, from 21 Nov 2024) in the design, all design rows in the final test; at least 100 reference rows.
Trades (rules.trades' logic): the first FILLED signal a side a day; 1-ITM of the ATM at the decision row, filled at
the next minute's open (3-minute window); x1 = -15% stop, +Rs 1,000 lock keeping Rs 500 and trailing Rs 500, 30-minute
time stop unless +5%; x2 = without the time stop; square-off 15:10 (15:05 on expiry day).
"""
from __future__ import annotations

import json
from copy import deepcopy
from functools import lru_cache

import numpy as np
import pandas as pd

import common as C
import engine as E
import features as FT
import oldcode
import r51cfg as K
import r51data

R1 = oldcode.load("f1", "rules")
F1K = oldcode.load("f1", "f1cfg")
RULES = json.loads((K.ROOT / "research/hunt/f1/results/rules.json").read_text())
ROWS = tuple(range(15, 316, 5))
EXITS = {"x1": E.Exits(stop=0.15, lock_trig=1000, lock_keep=500, lock_trail=500, tmax=30, need=0.05),
         "x2": E.Exits(stop=0.15, lock_trig=1000, lock_keep=500, lock_trail=500)}
F1_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")


def featrows(und: str) -> pd.DataFrame:
    """Feature values at the decision rows: design days always; final-test days only inside `study.py final`."""
    X = _featrows(und, "design")
    if C.final_open():
        X = pd.concat([X, _featrows(und, "final")], ignore_index=True)
    return X


@lru_cache(maxsize=16)
def _featrows(und: str, part: str) -> pd.DataFrame:
    p = K.OUT / "f1" / f"featrows_{part}_{und}.parquet"
    if p.exists():
        return pd.read_parquet(p)
    lo, hi = (K.START, K.DESIGN_END) if part == "design" else (K.FINAL_START, K.END)
    keep = []
    for d in C.days_in(und, lo, hi):
        f = FT.day(und, d)
        if f is None:
            continue
        f = f[f.index.isin(ROWS)].copy()
        num = [c for c in f.columns if pd.api.types.is_numeric_dtype(f[c])]
        f = f[num].astype(np.float32)
        f.insert(0, "row", f.index.values.astype(int))
        f.insert(0, "day", pd.Timestamp(d))
        keep.append(f.reset_index(drop=True))
    X = pd.concat(keep, ignore_index=True)
    X["und"] = und
    p.parent.mkdir(parents=True, exist_ok=True)
    X.to_parquet(p, index=False)
    return X


def day_signals(rule: dict, und: str, lo, hi, quint: float) -> pd.DataFrame:
    """Signals (day, row, sig) for days in [lo, hi] with the rule's reference logic (expanding in the design; all
    design rows in the final test)."""
    C.check_range(lo, hi)
    X = featrows(und)
    X = X[X.day.dt.date <= hi]
    old = F1K.QUINT
    F1K.QUINT = quint
    try:
        out = []
        final = lo >= K.FINAL_START
        if rule["kind"] in ("classic", "level"):
            Xp = X[X.day.dt.date >= lo]
            return Xp.assign(sig=R1.signals(rule, Xp, Xp))[["day", "row", "sig"]]
        if final:
            ref = X[X.day.dt.date <= K.DESIGN_END]
            Xp = X[X.day.dt.date >= lo]
            return Xp.assign(sig=R1.signals(rule, Xp, ref))[["day", "row", "sig"]]
        for d, g in X[X.day.dt.date >= lo].groupby("day", sort=True):
            ref = X[X.day < d]
            out.append(g.assign(sig=R1.signals(rule, g, ref))[["day", "row", "sig"]])
        return pd.concat(out, ignore_index=True) if out else pd.DataFrame(columns=["day", "row", "sig"])
    finally:
        F1K.QUINT = old


def make(rule: dict, quint: float = 0.20, ex_key: str = "x1") -> C.Config:
    ex = EXITS[ex_key]

    def run(lo, hi):
        rows = []
        for und in rule["scope"]:
            S = day_signals(rule, und, lo, hi, quint)
            S = S[S.sig != 0]
            days = r51data.load(und)
            valid = set(C.days_in(und, lo, hi))
            for d, g in S.groupby("day", sort=True):
                d = d.date()
                if d not in valid:
                    continue
                D = days[d]
                for side, sv in (("ce", 1), ("pe", -1)):
                    if side not in rule["sides"]:
                        continue
                    s = "C" if sv > 0 else "P"
                    for t in g[g.sig == sv].row.values:          # the first FILLED signal a side a day
                        k = E.strike_for(D, und, s, int(t), 1)
                        if k is None:
                            continue
                        r = E.run_day(und, D, [s], [k], [int(t) + 1], ex)[0]
                        if r is not None:
                            rows.append(r)
                            break
        return C.frame(rows)

    return C.Config(f"f1|{rule['id']}|q{quint}|{ex_key}", run, lambda und: C.TwinSpec(ex, 16, 316, 1))


def variants() -> list:
    out = []
    for r in RULES:
        src = "underlying features" + (" + option-chain features (filters)" if r["id"] in
                                         ("C1", "C2", "S8", "S9", "S10", "S11", "S12", "S13", "S14", "S18") else "")
        g = [make(r)] + [make(r, q, x) for q in (0.20, 0.10) for x in ("x1", "x2") if (q, x) != (0.20, "x1")]
        ex = dict(unds=tuple(r["scope"]))
        name = r.get("name", r["id"])
        out.append(C.Variant(f"F1-{r['id']}", "F1 factory", src, "orig", g[:1], name + "; 1-ITM, x1", extra=dict(ex)))
        out.append(C.Variant(f"F1-{r['id']}-RT", "F1 factory", src, "retune", g,
                             "re-tune: threshold {quintile, decile} x exit {x1, x2}", extra=dict(ex)))
    return out
