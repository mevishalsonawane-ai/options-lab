"""R196 pure parts (PREREG.md): the SENSEX slice, windows and walk-forward quarters, twin plans and totals, BH and the
gate. No data and no engine here (test_r196.py). Statistics are R193's (`r193lib.stats`): R196's windows are R193's own
dates. Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

from collections import Counter
from datetime import date

import numpy as np

import r196cfg as K
import r193lib as G3                      # stats, twin_p, normal_p, bh_total (pure)


# ================================================================================================ slices and windows
def only(trades: list, und: str) -> list:
    return [t for t in trades if t["und"] == und]


def without(trades: list, und: str) -> list:
    return [t for t in trades if t["und"] != und]


def in_dates(trades: list, lo: date, hi: date) -> list:
    return [t for t in trades if lo <= t["day"] <= hi]


def window_stats(trades: list, w: str) -> dict:
    lo, hi = K.WINDOWS[w]
    s = G3.stats(in_dates(trades, lo, hi), lo, hi)
    return s


def quarters(trades: list, w: str) -> list:
    """[(name, trades, net)] for each walk-forward quarter of window w."""
    out = []
    for name, lo, hi in K.QUARTERS[w]:
        tr = in_dates(trades, lo, hi)
        out.append(dict(quarter=name, trades=len(tr), net=round(float(sum(t["net"] for t in tr)))))
    return out


def wf_positive(qs: list) -> int:
    return int(sum(1 for q in qs if q["net"] > 0))


def exits_by_reason(trades: list) -> dict:
    return dict(Counter(str(t.get("why", t.get("reason", "?"))) for t in trades).most_common())


# ================================================================================================ twins
def twin_plan(trades: list, expiry_days: set | None = None) -> list:
    """[(day, side, count)] of the slice's taken trades, sorted. A day in expiry_days is never planned (the rules never
    trade the index's own expiry day; a slice trade there would be a bug, so it raises)."""
    c = Counter((t["day"], t["side"]) for t in trades)
    plan = sorted((d, s, n) for (d, s), n in c.items())
    if expiry_days:
        bad = [p for p in plan if p[0] in expiry_days]
        if bad:
            raise ValueError(f"slice trades on the index's own expiry day: {bad[:3]}")
    return plan


def twin_rows(first: int, last: int, n_twins: int, count: int, rng) -> np.ndarray:
    """(n_twins, count) uniform random entry rows in [first, last] (both included)."""
    if last < first:
        raise ValueError("empty entry window")
    return rng.integers(first, last + 1, size=(n_twins, count))


def twin_totals(plan: list, net_at, first: int, last: int, n_twins: int, rng) -> np.ndarray:
    """Twin totals (n_twins,): for each (day, side, count), `count` random rows a twin, each worth net_at(day, side, row)
    (Rs 0 when it cannot be filled: net_at returns None or NaN)."""
    tot = np.zeros(n_twins)
    for d, side, cnt in plan:
        rows = twin_rows(first, last, n_twins, cnt, rng)
        vals = {}
        for r in np.unique(rows):
            v = net_at(d, side, int(r))
            vals[int(r)] = 0.0 if v is None or not np.isfinite(v) else float(v)
        tot += np.vectorize(vals.__getitem__, otypes=[float])(rows).sum(axis=1)
    return tot


# ================================================================================================ BH and the gate
def bh(ps: list) -> list:
    return G3.bh_total(ps, K.M_TOTAL)


def gate(win: dict, wf_pos: int, p_emp: float, q: float) -> dict:
    """PREREG 7. win: {W1, W2, total: stats of the SENSEX slice}. Returns passed + every failed item."""
    fails = []
    for w in ("W1", "W2"):
        if not win[w]["net"] > 0:
            fails.append(f"{w}: net Rs {win[w]['net']:,} not above 0")
        if win[w]["trades"] < K.MIN_TRADES:
            fails.append(f"{w}: {win[w]['trades']} trades < {K.MIN_TRADES}")
    if wf_pos < K.WF_MIN_POSITIVE:
        fails.append(f"walk-forward: {wf_pos} of 6 W1 quarters positive < {K.WF_MIN_POSITIVE}")
    if not win["total"]["net_wo_top5"] > 0:
        fails.append(f"total: net without the 5 best trades Rs {win['total']['net_wo_top5']:,} not above 0")
    if not p_emp < K.GATE_P:
        fails.append(f"twin p {p_emp:.4f} >= {K.GATE_P}")
    if not q <= K.GATE_Q:
        fails.append(f"BH q {q:.3g} > {K.GATE_Q}")
    return dict(passed=not fails, fails=fails, twin_p=round(p_emp, 4), q=q)
