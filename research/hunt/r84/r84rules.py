"""R84 rules (PREREG.md): how many of one Breadth Donchian signal's correlated positions to take. Pure: numpy only.

A "signal" is one (day, entry row, side) of R72's C1 across the five indices. The day walker below replaces R64's
per-index `take` with one walk over all five indices in time order; at each signal it hands the ELIGIBLE candidates
(the ones the per-index rules would take now) to a chooser, which returns the subset to take. With the full set chosen
the walk equals the per-index `take` (test_r84 proves it), because the per-index rules only look at that index's own
open position and entry count.

Signal source: the indices' own 5-minute bars (break size in the index's own 5-minute ATR, NIFTY's own 10-minute move).
RB reads the chosen option's last price at the signal minute only as a size input, never as a signal.
"""
from __future__ import annotations

from collections import defaultdict

import numpy as np

ORDER = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")   # the app's emit order (BreadthDonchianArm)
MAX_PER_SIDE = 2
SHOCK_K = 5.0
SHOCK_LOOK = 20                 # sessions
SHOCK_MIN = 10                  # fewer earlier sessions: ratio unknown, no skip
RB_BUDGET = 5000.0
RB_STOP = 0.15
VARIANTS = ("K2-first", "K2-strong", "K1-strong", "K1-BN", "X-skip", "X-K2", "RB")
A1 = ("K2-open",)               # amendment A1 (post hoc): the app's open-position cap, on Breadth alone
ALL = VARIANTS + A1
OPEN_CAP = {"K2-open": 2}
NAMES = {
    "K0": "every index that broke (as now)",
    "K2-first": "at most 2 per signal, first in the app's index order",
    "K2-strong": "at most 2 per signal, the biggest breaks (in 5-min ATR)",
    "K1-strong": "only the single biggest break",
    "K1-BN": "only BANKNIFTY when it broke, else the biggest break",
    "X-skip": "skip the signal when NIFTY's 10-min move > 5x its usual",
    "X-K2": "X-skip, then at most 2 biggest breaks",
    "RB": "risk budget Rs 5,000 per signal (premium x lot x 15%), biggest breaks first",
    "K2-open": "at most 2 Breadth positions open the same way at any time (the app's cap)",
}


# ================================================================================================ inputs to a decision
def break_size(side: str, c: float, chi: float, clo: float, atr: float) -> float:
    """How far the signal bar's close is beyond its Donchian channel, in the index's own 5-minute ATR (0 if unknown)."""
    if not (np.isfinite(atr) and atr > 0):
        return 0.0
    x = (c - chi) if side == "C" else (clo - c)
    return float(x / atr) if np.isfinite(x) else 0.0


def ten_min_moves(o: np.ndarray, c: np.ndarray, ds: np.ndarray) -> np.ndarray:
    """|c_j / o_{j-1} - 1| per 5-minute bar (o_{j-1} = the previous bar's open on the same day; a day's first bar uses
    its own open). ds[j] = the flat index of bar j's day's first bar."""
    prev_o = np.where(np.arange(len(c)) > ds, np.roll(o, 1), o)
    with np.errstate(divide="ignore", invalid="ignore"):
        return np.abs(c / prev_o - 1.0)


def usual_by_day(d: np.ndarray, mv: np.ndarray, look: int = SHOCK_LOOK, need: int = SHOCK_MIN) -> dict:
    """{day: median 10-minute move over the previous `look` sessions' bars (not that day)}; days with fewer than `need`
    earlier sessions are left out (ratio unknown)."""
    days = []
    per = defaultdict(list)
    for di, m in zip(d, mv):
        if not days or days[-1] != di:
            days.append(di)
        if np.isfinite(m):
            per[di].append(m)
    out = {}
    for k, di in enumerate(days):
        prev = days[max(0, k - look):k]
        if len(prev) < need:
            continue
        x = np.concatenate([per[p] for p in prev if per[p]]) if any(per[p] for p in prev) else np.array([])
        if len(x):
            out[di] = float(np.median(x))
    return out


def shock_ratio(move: float, usual: float | None) -> float | None:
    if usual is None or not np.isfinite(move) or not (usual > 0):
        return None
    return float(move / usual)


# ================================================================================================ choosers
def by_strength(el: list) -> list:
    """Eligible candidates, biggest break first (ties: the fixed index order)."""
    return sorted(el, key=lambda c: (-c["size"], ORDER.index(c["und"])))


def by_order(el: list) -> list:
    return sorted(el, key=lambda c: ORDER.index(c["und"]))


def rb_pick(el: list, budget: float = RB_BUDGET) -> list:
    """Biggest break first; the first always; then each next one while the summed premium risk stays <= budget (a
    candidate whose risk is unknown is not added)."""
    out, tot = [], 0.0
    for c in by_strength(el):
        r = c.get("risk")
        if not out:
            out.append(c)
            tot = r if r is not None and np.isfinite(r) else 0.0
        elif r is not None and np.isfinite(r) and tot + r <= budget + 1e-9:
            out.append(c)
            tot += r
    return out


def choose(variant: str, el: list, shock: float | None = None) -> list:
    """The subset of a signal's eligible candidates that `variant` takes."""
    if variant == "K0":
        return list(el)
    if variant == "K2-first":
        return by_order(el)[:2]
    if variant == "K2-strong":
        return by_strength(el)[:2]
    if variant == "K1-strong":
        return by_strength(el)[:1]
    if variant == "K1-BN":
        bn = [c for c in el if c["und"] == "BANKNIFTY"]
        return bn if bn else by_strength(el)[:1]
    if variant == "X-skip":
        return [] if shock is not None and shock > SHOCK_K else list(el)
    if variant == "X-K2":
        return [] if shock is not None and shock > SHOCK_K else by_strength(el)[:2]
    if variant == "RB":
        return rb_pick(el)
    if variant == "K2-open":
        return by_order(el)              # walk_day keeps only the free slots (OPEN_CAP)
    raise ValueError(variant)


def keep_count(variant: str, el: list) -> int:
    """How many of an eligible set the variant's COUNT rule keeps (for the random twin, X rules aside)."""
    if variant in ("K2-first", "K2-strong", "X-K2"):
        return min(2, len(el))
    if variant in ("K1-strong", "K1-BN"):
        return min(1, len(el))
    if variant == "RB":
        return len(rb_pick(el))
    return len(el)


def twin_chooser(variant: str, rng, skip_sigs: set | None = None):
    """A chooser(sig_key, el, shock) for the random-subset twin: the same number kept, chosen at random; for X rules a
    random set of whole signals (skip_sigs) is skipped instead of the shocked ones."""
    def f(key, el, shock):
        if variant in ("X-skip", "X-K2") and key in (skip_sigs or set()):
            return []
        if variant == "X-skip":
            return list(el)
        if variant in OPEN_CAP:              # a random order; walk_day keeps the free slots
            return [el[i] for i in rng.permutation(len(el))]
        n = keep_count(variant, el)
        if n >= len(el):
            return list(el)
        ix = rng.choice(len(el), size=n, replace=False)
        return [el[i] for i in sorted(ix)]
    return f


# ================================================================================================ the day walker
def walk_day(cands: list, chooser, max_per_side: int = MAX_PER_SIDE, open_cap: int | None = None) -> tuple:
    """cands: dicts with und, er, side, res (the engine's result dict or None), size, risk, shock. Walks signals in
    entry-row order; at each (er, side) the eligible ones go to chooser((day, er, side), eligible, shock). Returns
    (taken trades, {(er, side): eligible count})."""
    groups = defaultdict(list)
    for c in cands:
        groups[(c["er"], c["side"])].append(c)
    busy, n_side, out, sigs = {}, {}, [], {}
    for key in sorted(groups, key=lambda k: (k[0], k[1])):
        el = []
        for c in by_order(groups[key]):
            r = c["res"]
            k2 = (c["und"], c["side"])
            if r is None or c["er"] <= busy.get(k2, -1) or n_side.get(k2, 0) >= max_per_side:
                continue
            el.append(c)
        if not el:
            continue
        sigs[key] = len(el)
        shock = el[0].get("shock")
        ch = chooser((el[0]["day"],) + key, el, shock)
        if open_cap is not None:
            n_open = sum(1 for t in out if t["side"] == key[1] and t["res"]["xr"] >= key[0])
            ch = ch[:max(0, open_cap - n_open)]
        for c in ch:
            k2 = (c["und"], c["side"])
            busy[k2] = c["res"]["xr"]
            n_side[k2] = n_side.get(k2, 0) + 1
            out.append(c)
    return out, sigs


# ================================================================================================ metrics and gate
def signal_nets(trades: list) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[(t["day"], t["sig"], t["side"])] += t["net"]
    return dict(out)


def gate(v: dict, k0: dict, p: float, q: float) -> list:
    """PREREG section 6: the failed parts (empty = PASS). v / k0: dicts with design (stats), wf_net (pooled quarters)."""
    fails = []
    d, b = v["design"], k0["design"]
    if not (abs(d["worst_day"]) <= 0.70 * abs(b["worst_day"]) and d["max_dd"] <= 0.70 * b["max_dd"]):
        fails.append("worst day and max DD not both cut >= 30% in design")
    if not (d["net"] >= 0.70 * b["net"] and v["wf_net"] >= 0.70 * k0["wf_net"]):
        fails.append("keeps < 70% of K0's net (design or walk-forward)")
    if not (p < 0.10 and q < 0.10):
        fails.append("no better than its random-subset twin (p or BH q >= 0.10)")
    if not (d["ex_top5"] > 0 and d["ex_top5"] >= 0.70 * b["ex_top5"]):
        fails.append("carried by its top 5 trades")
    return fails
