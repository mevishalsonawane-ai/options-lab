"""R194 pure parts (PREREG.md): the picker ablation, R151's threshold rule, the S/R target, the Liquidity signal copy
(room check and new-level exit as separate pieces), the swap / reversed twins and the normal-tail p. No data and no engine
here (test_r194.py). Statistics, BH and the gate are R193's (r193lib), reused unchanged. Plain-English notes, 18 Nov 2026.
"""
from __future__ import annotations

import math

import numpy as np

SR_FEATS = ("sr_ahead", "sr_behind", "sr_touch_ahead", "sr_crossed", "piv_ahead")
WALL = {"WALL15": 0.0015, "WALL30": 0.0030}
NO_LEVEL = 0.05                       # R151's sr_ahead when no 3+-touch level lies ahead


# ================================================================================================ A: ablation
def ablate(nums: dict, row, feats=SR_FEATS) -> list:
    """The feature row with the S/R features set to the model's training medians (= what the model does when they are
    missing: R151's median imputer / R72Picker.kt)."""
    out = [float(v) for v in row]
    for f in feats:
        i = nums["feats"].index(f)
        out[i] = float(nums["median"][i])
    return out


def drop_feats(nums: dict, feats=SR_FEATS) -> list:
    """The model's feature list without the S/R features (family B's 23)."""
    return [f for f in nums["feats"] if f not in feats]


# ================================================================================================ B: R151's threshold rule
def best_point(curve: list, base: dict, keep: float = 0.25) -> dict | None:
    """The curve's best point (how R151's app thresholds were taken, PREREG 6 B): the highest walk-forward win rate among
    thresholds keeping >= `keep` of the arm's walk-forward trades and making >= the arm's per trade; ties -> the lower
    threshold. None when no threshold qualifies."""
    ok = [m for m in curve if m.get("trades", 0) >= keep * base["trades"]
          and m.get("per_trade", -1e18) >= base["per_trade"]]
    if not ok:
        return None
    return max(ok, key=lambda m: (m.get("win", 0.0), -m["thr"]))


def threshold_rule(curve: list, base: dict, first_pass) -> tuple:
    """(threshold, which rule): R151's PREREG rule (first_pass) if it finds one, else the best point."""
    fp = first_pass(curve, base)
    if fp is not None:
        return float(fp["thr"]), "first_pass"
    bp = best_point(curve, base)
    return (None, "none") if bp is None else (float(bp["thr"]), "best_point")


# ================================================================================================ C: filters
def wall_skip(sr_ahead: float, dist: float) -> bool:
    """Skip when a 3+-touch level lies ahead within `dist` (sr_ahead is R151's distance; NaN never skips)."""
    return sr_ahead is not None and np.isfinite(sr_ahead) and sr_ahead <= dist + 1e-15


def cross_skip(sr_crossed: float) -> bool:
    """Take only breaks that crossed a 3+-touch level: skip when sr_crossed is 0 (NaN never skips)."""
    return sr_crossed is not None and np.isfinite(sr_crossed) and sr_crossed < 0.5


# ================================================================================================ D: the S/R target
def target_level(levels: list, price: float, side: str, min_touches: int = 3):
    """The nearest `min_touches`+ level ahead of `price` (above for a call, below for a put); None if none. The level
    behind R151's sr_ahead."""
    if not np.isfinite(price):
        return None
    s = 1.0 if side == "C" else -1.0
    ahead = [lv for lv, n in levels if n >= min_touches and s * (lv - price) > 0]
    if not ahead:
        return None
    return min(ahead) if s > 0 else max(ahead)


def target_row(hi: np.ndarray, lo: np.ndarray, ef: int, side: str, level, n: int | None = None) -> int:
    """The forced exit row of the S/R target: the first minute m >= ef (the fill minute) whose index high reaches the
    level (call; low for a put) -> sold at the next minute's open, m + 1. -1: never (or no level / no fill)."""
    if level is None or ef < 0 or not np.isfinite(level):
        return -1
    n = len(hi) if n is None else min(n, len(hi))
    for m in range(ef, n):
        x = hi[m] if side == "C" else lo[m]
        if np.isfinite(x) and ((side == "C" and x >= level) or (side == "P" and x <= level)):
            return m + 1 if m + 1 < len(hi) else -1
    return -1


def earlier(a: int, b: int) -> int:
    xs = [x for x in (a, b) if x is not None and x >= 0]
    return min(xs) if xs else -1


# ================================================================================================ D: Liquidity signals
OPEN_M = 9 * 60 + 15


def liq_day_signals(H4, d, bars, tf, istop, step, first_m, last_m):
    """fam_liq._day_signals (R51's family code for LiquidityRules.kt, research/hunt/r51/fam_liq.py) copied with room = 0 and
    the two index exits kept apart: each row also carries 'room' (the index distance to the next level ahead; inf when there
    is none) and 'istop', 'fb_exit' (failed break, minute of day or None) and 'nl_exit' (new level on the trade's side). The
    rows of fam_liq at room r are `liq_select(rows, r, True)`. first_m / last_m: the entry window (minutes of day)."""
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
        if done < first_m or done > last_m:
            continue
        room = float("inf") if target is None else float(side * (target - close))
        rows.append(dict(er=done - OPEN_M, side="C" if side > 0 else "P", sgn=side, close=float(close),
                         strike=H4.atm(close, step) - side * step, idx_stop=level - side * istop,
                         target=np.nan if target is None else float(target), room=room, istop=float(istop),
                         fb_exit=fb, nl_exit=nl))
    return rows


def liq_select(rows: list, room: float, nl_on: bool) -> list:
    """The signals a room check of `room` index stops keeps (no level ahead: kept, as fam_liq), each with fam_liq's
    'bar_exit' (failed break and, when nl_on, new level; -1 none) as a row of the day. fam_liq skips when
    side x (target - close) < room x stop (the same comparison here, so room 1 is fam_liq to the bit)."""
    out = []
    for r in rows:
        if r["room"] < room * r["istop"]:
            continue
        xa = [v - OPEN_M for v in (r["fb_exit"], r["nl_exit"] if nl_on else None) if v is not None]
        s = {k: v for k, v in r.items() if k not in ("room", "istop", "fb_exit", "nl_exit")}
        s["bar_exit"] = min(xa) if xa else -1
        out.append(s)
    return out


# ================================================================================================ twins / p
def swap_mask(kb: np.ndarray, n_drop: int, n_add: int, rng) -> np.ndarray:
    """A random twin of a picker that differs from the baseline's kept set `kb`: n_drop of the kept removed and n_add of
    the skipped added, at random."""
    kb = np.asarray(kb, bool)
    m = kb.copy()
    on, off = np.flatnonzero(kb), np.flatnonzero(~kb)
    if n_drop > 0:
        m[rng.choice(on, size=min(n_drop, len(on)), replace=False)] = False
    if n_add > 0:
        m[rng.choice(off, size=min(n_add, len(off)), replace=False)] = True
    return m


def p_upper(actual: float, twins) -> float:
    """Evidence the actual is HIGH among the twins: (1 + twins >= actual) / (n + 1)."""
    t = np.asarray(twins, float)
    return float((1 + int((t >= actual - 1e-9).sum())) / (len(t) + 1))


def p_lower(stat: float, twins) -> float:
    """Evidence the statistic is LOW among the twins (the reversed Liquidity tests): (1 + twins <= stat) / (n + 1)."""
    t = np.asarray(twins, float)
    return float((1 + int((t <= stat + 1e-9).sum())) / (len(t) + 1))


def normal_p(stat: float, twins, upper: bool = True) -> float:
    """One-sided normal-tail p (R190 / R193's BH input), upper or lower tail."""
    t = np.asarray(twins, float)
    sd = float(t.std(ddof=1)) if len(t) > 1 else 0.0
    mu = float(t.mean())
    if sd <= 0:
        return 0.0 if (stat > mu if upper else stat < mu) else 1.0
    z = (stat - mu) / sd if upper else (mu - stat) / sd
    return float(0.5 * math.erfc(z / math.sqrt(2.0)))
