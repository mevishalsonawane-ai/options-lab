"""R81 rules (PREREG.md section 2): the exit and trade-management rules for Liquidity 15+5, as pure functions on a day's
index bars (idx: (4, 375) O H L C, 09:15..15:29) and one held option contract (C: (5, 375) O H L C V). 18 Oct 2026.

Every function reads only bars up to its decision minute; an index rule that fires on minute m sells at the open of
m + 1 (the caller turns the returned row into a forced exit row). test_r81.py checks each against altered later bars.
"""
from __future__ import annotations

import numpy as np

ATR_N = 14                      # 5-minute bars in the ATR
TF5 = 5
TRAIL_ARM, TRAIL_K = 1.0, 1.5   # X3: arm after 1.0 ATR, sell 1.5 ATR back from the best
NOPROG_MIN, NOPROG_ATR = 10, 0.25
STRUCT_ATR, STRUCT_OPT, STRUCT_KEEP = 0.5, 0.03, 0.01
PYR_FRAC = 0.50
SCALE_R = 0.15
OVN_GAIN = 0.20
REENTRY_WIN = 30


# ================================================================================================ ATR
def bars5(idx: np.ndarray) -> np.ndarray:
    """A session's 5-minute bars on the 09:15 grid: (k, 5) rows of (end_row, O, H, L, C); bars with no minute dropped.
    end_row is the row after the bar's last minute (the bar is complete once that row starts). Pure."""
    out = []
    n = idx.shape[1]
    for s in range(0, n, TF5):
        seg = slice(s, min(s + TF5, n))
        c = idx[3, seg]
        ok = np.isfinite(c)
        if not ok.any():
            continue
        o = idx[0, seg][ok][0]
        out.append((min(s + TF5, n), o, np.nanmax(idx[1, seg]), np.nanmin(idx[2, seg]), c[ok][-1]))
    return np.array(out, float).reshape(-1, 5)


def atr5(prev_idx: list, idx: np.ndarray, row: int, n: int = ATR_N) -> float:
    """Mean true range of the last n 5-minute index bars complete by `row` (bar end <= row), earlier sessions (oldest
    first in prev_idx) included. NaN if fewer than n bars. Pure."""
    bars = [bars5(a) for a in prev_idx]
    today = bars5(idx)
    bars.append(today[today[:, 0] <= row] if len(today) else today)
    B = np.vstack([b for b in bars if len(b)]) if any(len(b) for b in bars) else np.zeros((0, 5))
    if len(B) < n + 1:
        return float("nan")
    h, lo, c = B[:, 2], B[:, 3], B[:, 4]
    tr = np.maximum(h[1:], c[:-1]) - np.minimum(lo[1:], c[:-1])
    return float(tr[-n:].mean())


# ================================================================================================ index exits
def sweep_stop(idx: np.ndarray, e0: int, tf: int, sgn: int, b0_stop: float) -> float:
    """X2: the far side of the breaking bar (rows e0 - tf .. e0 - 1): its low for a call, high for a put, never tighter
    than B0's stop (the further of the two). Pure."""
    seg = slice(max(e0 - tf, 0), e0)
    if sgn > 0:
        lo = np.nanmin(idx[2, seg]) if np.isfinite(idx[2, seg]).any() else np.inf
        return float(min(lo, b0_stop))
    hi = np.nanmax(idx[1, seg]) if np.isfinite(idx[1, seg]).any() else -np.inf
    return float(max(hi, b0_stop))


def chandelier_row(idx: np.ndarray, ef: int, sgn: int, i0: float, atr: float, arm: float = TRAIL_ARM,
                   k: float = TRAIL_K) -> int:
    """X3: the first minute m >= ef where the trail is armed (best 1-min high / low since the fill >= arm x ATR past i0)
    and that minute's close is more than k x ATR back from the best. -1 if never. The trade sells at m + 1. Pure."""
    if ef < 0 or not (np.isfinite(atr) and atr > 0 and np.isfinite(i0)):
        return -1
    best = -np.inf
    for m in range(ef, idx.shape[1]):
        ext = idx[1, m] if sgn > 0 else idx[2, m]
        c = idx[3, m]
        if not (np.isfinite(ext) and np.isfinite(c)):
            continue
        best = max(best, sgn * ext)
        if best - sgn * i0 >= arm * atr and sgn * c < best - k * atr:
            return m
    return -1


def noprog_row(idx: np.ndarray, ef: int, sgn: int, i0: float, atr: float, mins: int = NOPROG_MIN,
               need: float = NOPROG_ATR) -> int:
    """X4: the 10th minute from the fill (ef + mins - 1) if the index close there has not moved need x ATR past i0 in
    the trade's direction; -1 otherwise (or no close / no ATR). The trade sells at that row + 1. Pure."""
    m = ef + mins - 1
    if ef < 0 or m >= idx.shape[1] or not (np.isfinite(atr) and np.isfinite(i0)):
        return -1
    c = idx[3, m]
    if not np.isfinite(c):
        return -1
    return m if sgn * (c - i0) < need * atr else -1


def struct_flags(idx: np.ndarray, C: np.ndarray, ef: int, sgn: int, level: float, atr: float, fill: float,
                 k: float = STRUCT_ATR, opt: float = STRUCT_OPT) -> np.ndarray:
    """X11: per-minute flags, 1 on the last minute of a completed 5-min index bar (09:15 grid) whose close is >= k x ATR
    beyond the broken level in the trade's direction while the option's close that minute is >= (1 + opt) x the fill.
    Only minutes >= ef. Pure."""
    n = idx.shape[1]
    out = np.zeros(n)
    if ef < 0 or not (np.isfinite(atr) and np.isfinite(level) and np.isfinite(fill)):
        return out
    for s in range(0, n, TF5):
        last = min(s + TF5, n) - 1
        if last < ef:
            continue
        c = idx[3, s:last + 1]
        ok = np.isfinite(c)
        if not ok.any() or not np.isfinite(idx[3, last]):
            continue
        oc = C[3, last]
        if sgn * (idx[3, last] - level) >= k * atr and np.isfinite(oc) and oc >= (1 + opt) * fill:
            out[last] = 1.0
    return out


def pyramid_row(idx: np.ndarray, C: np.ndarray, ef: int, xr: int, sgn: int, i0: float, tg: float, fill: float,
                frac: float = PYR_FRAC) -> int:
    """X7: the first minute m in [ef, xr - 2] where the index has covered `frac` of the way from i0 to tg (1-min high
    for a call, low for a put) and the option's close is above the fill. The add is bought at m + 1. -1 if never. Pure."""
    if ef < 0 or not (np.isfinite(tg) and np.isfinite(i0)) or sgn * (tg - i0) <= 0:
        return -1
    lvl = i0 + frac * (tg - i0)
    for m in range(ef, min(xr - 1, idx.shape[1])):
        ext = idx[1, m] if sgn > 0 else idx[2, m]
        if np.isfinite(ext) and sgn * (ext - lvl) >= 0 and np.isfinite(C[3, m]) and C[3, m] > fill:
            return m
    return -1


def index_stop_hit(idx: np.ndarray, e0: int, sgn: int, stop: float) -> int:
    """The first row >= e0 whose 1-min low (call) / high (put) touches the index stop; -1 if none. Pure."""
    a = idx[2, e0:] if sgn > 0 else idx[1, e0:]
    with np.errstate(invalid="ignore"):
        hit = np.flatnonzero(a <= stop) if sgn > 0 else np.flatnonzero(a >= stop)
    return int(e0 + hit[0]) if len(hit) else -1


def forced(idx: np.ndarray, er: int, sgn: int, stop: float, tg: float, bar_exit: int, nrows: int) -> int:
    """fam_liq.forced_row with a given stop: index stop / target touched -> the next minute; failed break / new level
    (bar_exit) -> that bar's end. -1 if none before the close. Pure."""
    fx = bar_exit if bar_exit >= 0 else 10 ** 6
    lo, hi = idx[2, er:], idx[1, er:]
    with np.errstate(invalid="ignore"):
        if sgn > 0:
            hit = np.flatnonzero((lo <= stop) | ((hi >= tg) if np.isfinite(tg) else False))
        else:
            hit = np.flatnonzero((hi >= stop) | ((lo <= tg) if np.isfinite(tg) else False))
    if len(hit):
        fx = min(fx, er + int(hit[0]) + 1)
    return int(fx) if fx < nrows else -1


def reentry_row(idx: np.ndarray, xr: int, sgn: int, level: float, tg: float, istop: float, last: int,
                win: int = REENTRY_WIN) -> int:
    """X6: the end row b of the first 5-min index bar (09:15 grid) that completes after the exit row xr (its last minute
    >= xr), with b <= xr + win and b <= last, whose close is back beyond the level with room >= istop to tg (no target:
    no room check). The re-entry is bought at b. -1 if none. Pure."""
    for b in range(TF5, idx.shape[1] + 1, TF5):
        if b - 1 < xr:
            continue
        if b > xr + win or b > last:
            break
        c = idx[3, b - 1]
        if not np.isfinite(c) or sgn * (c - level) <= 0:
            continue
        if np.isfinite(tg) and sgn * (tg - c) < istop:
            continue
        return b
    return -1


def failed_break_row(idx: np.ndarray, b: int, sgn: int, level: float) -> int:
    """X6's failed break: the end of the first 5-min bar after b whose close is back through the level; -1 if none."""
    for e in range(b + TF5, idx.shape[1] + 1, TF5):
        c = idx[3, e - 1]
        if np.isfinite(c) and sgn * (c - level) < 0:
            return e
    return -1


def overnight_px(C2: np.ndarray, gtt: float):
    """X10's next-session exit on the same contract: (raw exit price, row, how). The GTT stop (the fill) triggers if the
    09:15 minute trades at or below it (fill at the stop, or the open if it gapped below); else the 09:16 open (the next
    traded open if 09:16 has none). (nan, -1, '') if the contract has no bar. Pure."""
    o, lo = C2[0], C2[2]
    if np.isfinite(lo[0]) and lo[0] <= gtt:
        px = o[0] if (np.isfinite(o[0]) and o[0] < gtt) else gtt
        return float(px), 0, "gtt"
    for j in range(1, min(6, C2.shape[1])):
        if np.isfinite(o[j]) and o[j] > 0:
            return float(o[j]), j, "open"
    return float("nan"), -1, ""


def flip_p(d: np.ndarray, n: int, rng) -> float:
    """Sign-flip twin: share of n random sign assignments of the day differences d whose total is >= the actual total
    (with the +1 correction). Pure apart from rng."""
    d = np.asarray(d, float)
    if not len(d):
        return 1.0
    s = rng.choice((-1.0, 1.0), size=(n, len(d)))
    tw = (s * d).sum(axis=1)
    return round((1 + int((tw >= d.sum() - 1e-9).sum())) / (n + 1), 4)


def top5_ok(deltas) -> float:
    """The gain over B0 left after removing the 5 largest per-trade gains. Pure."""
    d = np.sort(np.asarray(deltas, float))[::-1]
    return float(d[5:].sum())
