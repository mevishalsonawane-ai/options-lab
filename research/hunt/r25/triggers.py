"""R25 trigger rules T1-T3 (PREREG.md Part B), built from the design-only anatomy (results/anatomy.md).

Each trigger is checked at every bar close in its window. When it holds for a side (call = up, put = down) it buys the
nearest-the-money OTM option on that side priced Rs 1-10 that passes the per-option conditions, the traded rule and the
app's stale-slack rule. One entry per side per index per expiry day. Budget Rs 1,000.

`choose(spec, days)` is Part B's selection rule: over the spec's threshold grid x exits {hold, ladder, app}, the best
net per Rs 1,000 risked among combinations with at least max(15, 0.10 x expiries) trades; ties -> fewer trades.
Every value read at row t comes from rows <= t (the look-ahead guard in test_r25.py checks it).
"""
from __future__ import annotations

import math
from itertools import product

import numpy as np

import r25cfg as K
import r25lib as R
from r25data import C, H, L, N, OI, V, Day, minute

BAND = (1.0, 10.0)
OR_END = minute(9, 29)
EXIT_CHOICES = ("hold", "ladder", "app")

SPECS = [
    {"id": "T1", "note": "fresh breakout of the day's high (CE) / low (PE) within 15 min + the strike near in expected-move units",
     "first": R.close_row(11, 0), "last": R.close_row(14, 30),
     "grid": {"dist_em_max": [1.0, 1.25, 1.44]}},
    {"id": "T2", "note": "the strike near in day-range units + max pain behind the option (on the other side of spot)",
     "first": R.close_row(11, 0), "last": R.close_row(13, 30),
     "grid": {"dist_rng_max": [0.8, 1.0, 1.08], "maxpain_max": [-0.03, -0.058, -0.1]}},
    {"id": "T3", "note": "15-min momentum with the option + beyond the opening range + other-side OI building (writers adding)",
     "first": R.close_row(13, 0), "last": R.close_row(14, 45),
     "grid": {"mv15_min": [0.10, 0.15, 0.25], "opp_oi_min": [0.0, 2.86]}},
]


# ------------------------------------------------------------------------------------------------ per-day state
def _oi_matrix(day: Day):
    """Strikes x rows of last-known OI (carried forward from the past only; NaN once 30+ rows stale)."""
    out = {}
    for s in ("C", "P"):
        ks = sorted(k for k, sd in day.opts if sd == s)
        m = np.full((len(ks), N), np.nan)
        for i, k in enumerate(ks):
            x = day.opts[(k, s)][:, OI]
            last, lr = np.nan, -10 ** 6
            for r in range(N):
                if not np.isnan(x[r]):
                    last, lr = x[r], r
                m[i, r] = last if r - lr <= 30 else np.nan
        out[s] = (np.array(ks), m)
    return out


def maxpain_at(day: Day, t: int, mats) -> float:
    kc, mc = mats["C"]
    kp, mp = mats["P"]
    c = {k: v for k, v in zip(kc, mc[:, t]) if v == v}
    p = {k: v for k, v in zip(kp, mp[:, t]) if v == v}
    ks = sorted(set(c) | set(p))
    if not ks:
        return math.nan
    ka = np.array(ks)
    cv = np.array([c.get(k, 0.0) for k in ks])
    pv = np.array([p.get(k, 0.0) for k in ks])
    pain = [(cv * np.maximum(x - ka, 0)).sum() + (pv * np.maximum(ka - x, 0)).sum() for x in ka]
    return float(ka[int(np.argmin(pain))])


def state(day: Day, t: int, mats=None) -> dict:
    """Index-level trigger inputs at row t (rows <= t only)."""
    s = R.spot(day, t)
    o = {"spot": s}
    b = day.ix[t - 15, 3] if t >= 15 else math.nan
    o["mv15"] = (s / b - 1) * 100 if b == b and b > 0 and s == s else math.nan
    if t >= 15:
        o["brk_hi15"] = bool(np.nanmax(day.ix[t - 14: t + 1, 1]) > np.nanmax(day.ix[: t - 14, 1]))
        o["brk_lo15"] = bool(np.nanmin(day.ix[t - 14: t + 1, 2]) < np.nanmin(day.ix[: t - 14, 2]))
    else:
        o["brk_hi15"] = o["brk_lo15"] = False
    orh, orl = np.nanmax(day.ix[: OR_END + 1, 1]), np.nanmin(day.ix[: OR_END + 1, 2])
    o["or_up"], o["or_dn"] = bool(s > orh), bool(s < orl)
    o["rng"] = np.nanmax(day.ix[: t + 1, 1]) - np.nanmin(day.ix[: t + 1, 2])
    v = day.vix[: t + 1] if day.vix is not None else np.array([])
    vv = v[~np.isnan(v)] if len(v) else v
    vix = float(vv[-1]) if len(vv) else math.nan
    left = (15 * 60 + 30) - (9 * 60 + 15 + t + 1)
    o["em"] = s * vix / 100 * math.sqrt(max(left, 1) / (252 * 375)) if vix == vix and s == s else math.nan
    if mats is not None:
        mp = maxpain_at(day, t, mats)
        o["maxpain"] = (mp - s) / s * 100 if mp == mp and s == s else math.nan
    return o


def _oi_pct(a: np.ndarray, t: int, k: int = 30) -> float:
    x = a[: t + 1, OI]
    ok = np.nonzero(~np.isnan(x))[0]
    if not len(ok) or ok[-1] < t - 5:
        return math.nan
    old = ok[ok <= t - k]
    if not len(old) or old[-1] < t - k - 10:
        return math.nan
    return (x[ok[-1]] / x[old[-1]] - 1) * 100 if x[old[-1]] > 0 else math.nan


def holds(spec_id: str, p: dict, st: dict, sgn: int, k: float, day: Day, t: int) -> bool:
    """The trigger for side sgn and candidate strike k at row t, with thresholds p."""
    s = st["spot"]
    if s != s:
        return False
    dist = abs(k - s)
    if spec_id == "T1":
        brk = st["brk_hi15"] if sgn > 0 else st["brk_lo15"]
        return bool(brk) and st["em"] == st["em"] and st["em"] > 0 and dist / st["em"] <= p["dist_em_max"] + 1e-12
    if spec_id == "T2":
        mp = st.get("maxpain", math.nan)
        if mp != mp or not st["rng"] > 0:
            return False
        return dist / st["rng"] <= p["dist_rng_max"] + 1e-12 and sgn * mp <= p["maxpain_max"] + 1e-12
    if spec_id == "T3":
        mv = st["mv15"]
        orb = st["or_up"] if sgn > 0 else st["or_dn"]
        if mv != mv or not orb or sgn * mv < p["mv15_min"] - 1e-12:
            return False
        am = R.atm(day, s)
        b = day.opts.get((am - (k - am), "P" if sgn > 0 else "C"))
        if b is None:
            return False
        oi = _oi_pct(b, t)
        return oi == oi and oi >= p["opp_oi_min"] - 1e-12
    raise ValueError(spec_id)


# ------------------------------------------------------------------------------------------------ signals
def _params(rule) -> dict:
    return {k: v for k, _, v in rule.params}


def candidates(day: Day, spec_id: str, first: int, last: int):
    """Per day (cached): for every row in the window and side, the index state and the eligible options. Reading these
    at row t uses rows <= t only (state() and eligible() do)."""
    key = (spec_id, first, last)
    cache = day.__dict__.setdefault("_tg", {})
    if key in cache:
        return cache[key]
    mats = _oi_matrix(day) if spec_id == "T2" else None
    out = []
    for t in range(first, last + 1):
        st = state(day, t, mats)
        el = {sgn: R.eligible(day, sgn, t, BAND) for sgn in (1, -1)}
        out.append((t, st, el))
    cache[key] = out
    return out


def signals(day: Day, rule) -> list:
    from r25rules import Signal
    if day.und not in rule.unds:
        return []
    p = _params(rule)
    out, done = [], set()
    for t, st, el in candidates(day, rule.id, rule.first, rule.last):
        for sgn in (1, -1):
            if sgn in done:
                continue
            for k, px in el[sgn]:
                if holds(rule.id, p, st, sgn, k, day, t):
                    out.append(Signal(t, sgn, k, px))
                    done.add(sgn)
                    break
        if len(done) == 2:
            break
    return out


def make(spec: dict, params: dict, exits: str):
    from r25rules import Rule
    return Rule(spec["id"], "trigger", 1000.0, exits, first=spec["first"], last=spec["last"], band=BAND,
                params=tuple((k, "=", float(v)) for k, v in sorted(params.items())), note=spec["note"])


def choose(spec: dict, days: list):
    """Part B's selection rule on `days` (design days or a walk-forward training set)."""
    import r25rules as RU
    names = sorted(spec["grid"])
    combos = [dict(zip(names, vals)) for vals in product(*[spec["grid"][n] for n in names])]
    min_tr = max(15, int(0.10 * len(days)))
    best = None
    for c in combos:
        base = make(spec, c, "hold")
        sigs = [(d, s) for d in days for s in signals(d, base)]
        for ex in EXIT_CHOICES:
            trs = [RU.trade(d, base, s, exits=ex) for d, s in sigs]
            trs = [x for x in trs if x is not None]
            if len(trs) < min_tr:
                continue
            risked = sum(x.risked for x in trs)
            score = sum(x.net for x in trs) / risked * 1000 if risked > 0 else -math.inf
            key = (score, -len(trs))
            if best is None or key > best[0]:
                best = (key, c, ex)
    if best is None:                       # nothing reached the minimum: the strictest combination, held
        strict = {n: (max(spec["grid"][n]) if n.endswith("_min") else min(spec["grid"][n])) for n in names}
        return make(spec, strict, "hold")
    return make(spec, best[1], best[2])
