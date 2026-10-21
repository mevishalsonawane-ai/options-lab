"""R25 Step 3: the pre-registered rules (PREREG.md), their twins, the walk-forward and the metrics.

Every rule is `Rule.signals(day) -> [Signal]` (one per side it buys; reads rows <= the signal row only) plus a budget
and an exit. H0 (the app's Hero) and V4 reuse R21's tested signal code (research/hunt/r21/rules.py, the port of
HeroRules.kt), with R25's engine for fills, exits and charges.
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass, field, replace

import numpy as np

import r25cfg as K
import r25lib as R
from r25data import Day


def _load_r21():
    """R21's HeroRules port, loaded by path under its own name. research/hunt/r21 goes to the END of sys.path (for its
    bare `config` / `data` imports), so it can never shadow an R25 module (both studies have an anatomy.py)."""
    import importlib.util
    d = K.ROOT / "research" / "hunt" / "r21"
    if str(d) not in sys.path:
        sys.path.append(str(d))
    if "r21_rules" in sys.modules:
        return sys.modules["r21_rules"]
    spec = importlib.util.spec_from_file_location("r21_rules", d / "rules.py")
    m = importlib.util.module_from_spec(spec)
    sys.modules["r21_rules"] = m
    spec.loader.exec_module(m)
    return m


R21 = _load_r21()


@dataclass
class Signal:
    row: int
    side: int
    strike: float
    ref: float


# ------------------------------------------------------------------------------------------------ rule kinds
@dataclass(frozen=True)
class Rule:
    id: str
    kind: str                      # hero | onesided | lottery | trigger
    budget: float
    exits: str
    unds: tuple = K.UNDS
    at: int = 0                    # lottery: the bar-close row
    band: tuple = (1.0, 5.0)
    target_px: float = 3.0         # lottery: the price it aims for
    first: int = 0
    last: int = 0
    params: tuple = ()             # trigger: ((feature, op, threshold), ...)
    note: str = ""

    def window(self) -> tuple[int, int]:
        if self.kind == "lottery":
            return self.at, self.at
        return self.first, self.last


H0 = Rule("H0", "hero", 5000.0, "app", ("NIFTY",), first=R.close_row(13, 30), last=R.close_row(14, 45), band=(1.0, 5.0),
          note="the app's Hero arm (HeroRules.kt = R21 V0)")
V4 = Rule("V4", "onesided", 5000.0, "app", first=R.close_row(13, 30), last=R.close_row(15, 0), band=(1.0, 15.0),
          note="R21 V4 on the lake: own close >= 2.5x its low since 12:00 + 0.25% momentum agreeing")
L1 = Rule("L1", "lottery", 500.0, "hold", at=R.close_row(13, 30), note="lottery basket 13:30, hold to 15:05")
L2 = Rule("L2", "lottery", 500.0, "ladder", at=R.close_row(13, 30), note="lottery basket 13:30, Boss's ladder")
L3 = Rule("L3", "lottery", 500.0, "hold", at=R.close_row(14, 30), note="lottery basket 14:30, hold to 15:05")
L4 = Rule("L4", "lottery", 500.0, "ladder", at=R.close_row(14, 30), note="lottery basket 14:30, Boss's ladder")
FIXED = [H0, L1, L2, L3, L4, V4]
EXITS = ("hold", "half3", "half5", "ladder", "trail", "app")


def _r21_params(rule: Rule) -> "R21.Params":
    if rule.kind == "hero":
        return R21.Params(name="V0", trigger="straddle", first=rule.first, last=rule.last, band=rule.band, exits="app")
    return R21.Params(name="V4", trigger="onesided", k=2.5, first=rule.first, last=rule.last, band=rule.band, exits="app")


def lottery_pick(day: Day, sgn: int, t: int, band=(1.0, 5.0), target=3.0):
    el = R.eligible(day, sgn, t, band)
    if not el:
        return None
    s = R.spot(day, t)
    el.sort(key=lambda kp: (abs(kp[1] - target), abs(kp[0] - s)))
    return el[0]


def signals(day: Day, rule: Rule) -> list[Signal]:
    if day.und not in rule.unds:
        return []
    if rule.kind in ("hero", "onesided"):
        s = R21.first_signal(day, _r21_params(rule))
        return [Signal(s.row, s.side, s.strike, s.ref)] if s else []
    if rule.kind == "lottery":
        out = []
        for sgn in (1, -1):
            c = lottery_pick(day, sgn, rule.at, rule.band, rule.target_px)
            if c:
                out.append(Signal(rule.at, sgn, c[0], c[1]))
        return out
    if rule.kind == "trigger":
        import triggers
        return triggers.signals(day, rule)
    raise ValueError(rule.kind)


def trade(day: Day, rule: Rule, sig: Signal, exits: str | None = None, per_lot: bool = False, mult: float = 1.0,
          oor_zero: bool = False):
    return R.simulate(day, sig.strike, sig.side, sig.row, sig.ref, None if per_lot else rule.budget,
                      exits or rule.exits, rule.id, mult, oor_zero)


def run(days: list[Day], rule: Rule, **kw) -> list:
    out = []
    for d in days:
        for s in signals(d, rule):
            t = trade(d, rule, s, **kw)
            if t is not None:
                out.append(t)
    return out


# ------------------------------------------------------------------------------------------------ twins
def twin_signal(day: Day, rule: Rule, sig: Signal, rng: np.random.Generator, mode: str) -> Signal | None:
    """time: a uniformly random minute of the rule's window, same side, the rule's strike rule (no trigger).
    strike: the same minute, a uniformly random OTM option on the same side in the band."""
    lo, hi = rule.band
    if mode == "time":
        a, b = rule.window()
        for _ in range(20):
            t = int(rng.integers(a, b + 1))
            el = R.eligible(day, sig.side, t, (lo, hi))
            if el:
                k, p = el[0] if rule.kind != "lottery" else min(el, key=lambda kp: abs(kp[1] - rule.target_px))
                return Signal(t, sig.side, k, p)
        return None
    el = R.eligible(day, sig.side, sig.row, (lo, hi))
    if not el:
        return None
    k, p = el[int(rng.integers(len(el)))]
    return Signal(sig.row, sig.side, k, p)


def twins(days: list[Day], rule: Rule, mode: str, n: int = K.TWINS, seed: int = K.SEED) -> np.ndarray:
    """Net of n twin sets (each signal replaced by its twin). Signals are computed once."""
    rng = np.random.default_rng(seed)
    sigs = [(d, s) for d in days for s in signals(d, rule)]
    out = np.zeros(n)
    cache: dict = {}
    for i in range(n):
        tot = 0.0
        for d, s in sigs:
            tw = twin_signal(d, rule, s, rng, mode)
            if tw is None:
                continue
            key = (d.und, d.day, tw.row, tw.side, tw.strike)
            if key not in cache:
                tr = trade(d, rule, tw)
                cache[key] = tr.net if tr else 0.0
            tot += cache[key]
        out[i] = tot
    return out


def boot_p(daily: np.ndarray, n: int = 10000, seed: int = K.SEED) -> float:
    """One-sided bootstrap p of mean per-expiry net > 0 (resampling expiry days)."""
    if len(daily) == 0:
        return 1.0
    rng = np.random.default_rng(seed)
    x = daily - daily.mean()                 # under H0: mean 0
    m = rng.choice(x, size=(n, len(x)), replace=True).mean(axis=1)
    return float((m >= daily.mean()).mean())


def bh(ps: list[float]) -> list[float]:
    p = np.asarray(ps, dtype=float)
    n = len(p)
    o = np.argsort(p)
    q = np.empty(n)
    run_min = 1.0
    for i in range(n - 1, -1, -1):
        j = o[i]
        run_min = min(run_min, p[j] * n / (i + 1))
        q[j] = run_min
    return q.tolist()


# ------------------------------------------------------------------------------------------------ metrics
def metrics(trades: list, days: list[Day], rule: Rule) -> dict:
    scope = [d for d in days if d.und in rule.unds]
    keys = [(d.und, d.day) for d in scope]
    daily = {k: 0.0 for k in keys}
    for t in trades:
        daily[(t.und, t.day)] = daily.get((t.und, t.day), 0.0) + t.net
    nets = np.array([t.net for t in trades])
    risked = sum(t.risked for t in trades)
    # equity in date order (all indices together)
    order = sorted(daily.items(), key=lambda kv: (kv[0][1], kv[0][0]))
    eq = np.cumsum([v for _, v in order]) if order else np.array([0.0])
    dd = float(np.min(eq - np.maximum.accumulate(np.r_[0.0, eq])[1:])) if len(eq) else 0.0
    traded_days = [v for k, v in order if any((t.und, t.day) == k for t in trades)] if trades else []

    def streak(xs):
        best = cur = 0
        for x in xs:
            cur = cur + 1 if x <= 0 else 0
            best = max(best, cur)
        return best

    tr_sorted = sorted(trades, key=lambda t: (t.day, t.und, t.fill_row))
    tot = float(nets.sum()) if len(nets) else 0.0
    best_day = max(daily.values()) if daily else 0.0
    wins, losses = nets[nets > 0], nets[nets <= 0]
    return {"expiries": len(keys), "trades": int(len(nets)), "hit_pct": round(float((nets > 0).mean()) * 100, 1) if len(nets) else 0.0,
            "avg_win": round(float(wins.mean()), 1) if len(wins) else 0.0,
            "avg_loss": round(float(losses.mean()), 1) if len(losses) else 0.0,
            "net": round(tot, 2), "per_expiry": round(tot / len(keys), 2) if keys else 0.0,
            "per_1000_risked": round(tot / risked * 1000, 1) if risked > 0 else 0.0,
            "green_expiries_pct": round(float(np.mean([v > 0 for v in daily.values()])) * 100, 1) if daily else 0.0,
            "worst_day": round(min(daily.values()), 2) if daily else 0.0, "best_day": round(best_day, 2),
            "max_dd": round(dd, 2), "losing_streak_trades": streak([t.net for t in tr_sorted]),
            "losing_streak_expiries": streak(traded_days),
            "best_day_share_pct": round(best_day / tot * 100, 1) if tot > 0 else None,
            "net_without_best_day": round(tot - best_day, 2), "proxy_trades": int(sum(t.proxy for t in trades))}


def per_year(trades: list, days: list[Day], rule: Rule) -> dict:
    out = {}
    for y in sorted({d.day.year for d in days}):
        ds = [d for d in days if d.day.year == y]
        ts = [t for t in trades if t.day.year == y]
        out[y] = metrics(ts, ds, rule)
    return out
