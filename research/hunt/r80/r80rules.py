"""R80 rules (PREREG.md): ten entry filters for Liquidity 15+5. Signal source: the index. Research only, 18 Oct 2026.

`day_signals` is fam_liq._day_signals (R51) with the same rows plus the features each filter reads, all known at the
end of the breaking bar. The pure helpers below (features, filters, the book walk) are what the tests check.
"""
from __future__ import annotations

import math

import numpy as np

OPEN_M = 9 * 60 + 15
ROUND = {"BANKNIFTY": 500.0, "SENSEX": 500.0, "NIFTY": 100.0}
STACK_MIN = 3            # V2: at least 3 same-side zones on the broken band
DISP_K, DISP_N = 1.5, 20  # V8: body >= 1.5 x the mean body of the 20 bars before
FH_ROWS = 60             # first hour = rows 0..59 (09:15-10:14), complete at row 60 (10:15)
FH_DAYS = 20
T1015 = 60               # entry row of 10:15
CAP = 2                  # V9
VARIANTS = ("V1", "V2", "V3", "V4", "V5", "V6", "V7", "V8", "V9", "V10")
LABEL = {"V1": "PRIOR: pool formed on an earlier session", "V2": "STACK: >= 3 same-side zones on the level",
         "V3": "ROUND: the break crosses a round number", "V4": "CLEAN: the pool was not swept before the break",
         "V5": "PDX: close beyond the previous day's high / low", "V6": "T1015: no entries before 10:15",
         "V7": "FHR: from 10:15, first-hour range >= its 20-day median",
         "V8": "DISP: breaking bar's body >= 1.5x the last 20 bars' mean", "V9": "CAP2: at most 2 trades a day",
         "V10": "L1: stop for the day after the first loss"}
POST_WALK = ("V9", "V10")


# ------------------------------------------------------------------------------------------------ pure features
def crosses_round(side: int, level: float, close: float, s: float, unit: float) -> bool:
    """A multiple of `unit` lies in [level - s, close + s] (side +1) or [close - s, level + s] (side -1). Pure."""
    lo, hi = (level - s, close + s) if side > 0 else (close - s, level + s)
    return math.floor(hi / unit) * unit >= lo - 1e-9


def swept_before(side: int, edge: float, H, Lo, Cl, known: int, i: int) -> bool:
    """Any bar k with known < k < i that wicked beyond the edge and closed back inside. Reads no bar >= i. Pure."""
    for k in range(known + 1, i):
        if side > 0 and H[k] > edge and Cl[k] <= edge:
            return True
        if side < 0 and Lo[k] < edge and Cl[k] >= edge:
            return True
    return False


def body_ratio(O, Cl, i: int, n: int = DISP_N) -> float:
    """|C - O| of bar i over the mean |C - O| of the n bars before it (NaN if fewer than n, or a flat history). Pure."""
    if i < n:
        return float("nan")
    b = np.abs(np.asarray(Cl[i - n:i], float) - np.asarray(O[i - n:i], float)).mean()
    return float(abs(Cl[i] - O[i]) / b) if b > 0 else float("nan")


def stack_count(zones, side: int, top: float, bottom: float, i: int) -> int:
    """Same-side zones known by bar i, not broken before i, whose band overlaps [bottom, top]. Pure."""
    return sum(1 for z in zones if z.side == side and z.known <= i and (z.broken < 0 or z.broken >= i)
               and z.bottom <= top and bottom <= z.top)


def first_hour_range(idx: np.ndarray) -> float:
    """High - low of rows 0..59 (NaN if any of those minutes is missing in full)."""
    h, lo = idx[1, :FH_ROWS], idx[2, :FH_ROWS]
    if not np.isfinite(h).any():
        return float("nan")
    return float(np.nanmax(h) - np.nanmin(lo))


def fh_gate(today: float, history: list) -> bool | None:
    """V7: today's first-hour range >= the median of the previous 20 sessions' (None = fewer than 20 known). Pure."""
    h = [x for x in history[-FH_DAYS:] if np.isfinite(x)]
    if len(history) < FH_DAYS or len(h) < FH_DAYS or not np.isfinite(today):
        return None
    return bool(today >= float(np.median(h)))


# ------------------------------------------------------------------------------------------------ signals + features
def day_signals(H4, d, bars, tf, istop, step, room, unit, prev_hl):
    """fam_liq._day_signals (R51, unchanged rows) plus the R80 features of each row. prev_hl = (high, low) of the
    previous session or None."""
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
        if done < OPEN_M + 5 or done > OPEN_M + 285:          # fam_liq FIRST (09:20) .. LAST (14:00)
            continue
        if target is not None and side * (target - close) < room * istop:
            continue
        xa = [v - OPEN_M for v in (fb, nl) if v is not None]
        feat = dict(
            f_prior=bool(bars[pool.origin][0] != d),
            f_stack=int(stack_count(zones, side, pool.top, pool.bottom, i)),
            f_round=bool(crosses_round(side, float(level), float(close), istop, unit)),
            f_swept=bool(swept_before(side, float(level), H, Lo, Cl, pool.known, i)),
            f_pdx=(None if prev_hl is None else bool(close > prev_hl[0] if side > 0 else close < prev_hl[1])),
            f_body=body_ratio(O, Cl, i))
        rows.append(dict(er=done - OPEN_M, side="C" if side > 0 else "P", sgn=side, close=float(close),
                         strike=H4.atm(close, step) - side * step, idx_stop=level - side * istop,
                         target=np.nan if target is None else float(target), bar_exit=min(xa) if xa else -1,
                         level=float(level), **feat))
    return rows


# ------------------------------------------------------------------------------------------------ filters
def keep_signal(v: str, c: dict) -> bool:
    """V1-V8 on one candidate (with its f_* features and f_fh day gate). Unknown inputs keep the signal only where
    the rule needs nothing unknown; an unknown V5 / V7 / V8 input drops it (the rule cannot confirm). Pure."""
    if v == "V1":
        return c["f_prior"]
    if v == "V2":
        return c["f_stack"] >= STACK_MIN
    if v == "V3":
        return c["f_round"]
    if v == "V4":
        return not c["f_swept"]
    if v == "V5":
        return bool(c["f_pdx"])
    if v == "V6":
        return c["er"] >= T1015
    if v == "V7":
        return c["er"] >= T1015 and bool(c.get("f_fh"))
    if v == "V8":
        return bool(np.isfinite(c["f_body"]) and c["f_body"] >= DISP_K)
    raise ValueError(v)


def walk(cands: list, res: list, keep=None) -> list:
    """One book's day, as R51 common.take(scope='all'): candidates in time order with their simulated result (None =
    no fill); a kept candidate is taken when the book is free (its er > the open trade's exit row). Pure."""
    out, busy = [], -1
    for j, (c, r) in enumerate(zip(cands, res)):
        if r is None or (keep is not None and not keep[j]):
            continue
        if c["er"] <= busy:
            continue
        busy = r["xr"]
        t = dict(r)
        t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        out.append(t)
    return out


def post_walk(v: str, trades: list) -> list:
    """V9 / V10 on one day's trades of both books (sorted by entry row). Pure.
    V9: the first CAP trades. V10: drop every trade entered after an earlier kept trade closed at a loss
    (exit row strictly before the entry row)."""
    ts = sorted(trades, key=lambda t: (t["er"], t.get("book", 0)))
    if v == "V9":
        return ts[:CAP]
    if v == "V10":
        kept = []
        for t in ts:
            if any(k["net"] < 0 and k["xr"] < t["er"] for k in kept):
                continue
            kept.append(t)
        return kept
    raise ValueError(v)
