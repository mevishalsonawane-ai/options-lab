"""R77 rules (PREREG.md sections 1-2): the "absorption bubble" proxy on 1-minute INDEX bars. Pure numpy, no data access.

Event on bar i of one session (rows of the 09:15-15:29 grid):
  high volume   vp(i) >= V: share of the previous 20 sessions' volumes at row i strictly below today's (>= 15 known)
  small range   rp(i) <= R: the same for the index bar's range H - L
  swing extreme downside: low(i) < min(low(i-20 .. i-1)); upside: high(i) > max(high(i-20 .. i-1)); within the day
  confirmation  bar i+1 only. Downside: low(i+1) >= low(i) AND (close(i+1) > the swept low OR close(i+1) > high(i));
                upside: the mirror image
  entry         the open of row i+2 (the minute after the confirmation bar closes), rows 22 .. 14:45
Volume is the nearest-expiry option volume of the index (CE + PE over the store's strikes), in lots: the index has no
volume and expired futures minutes are not in the lake (PREREG section 0). 18 Oct 2026.
"""
from __future__ import annotations

import numpy as np

N_SWING = 20
LOOKBACK = 20                       # sessions in the volume / range baseline
MIN_KNOWN = 15                      # at least 15 of those 20 must have the row
FIRST_ER = N_SWING + 2              # 09:37, the first possible entry row
LAST_ER = 14 * 60 + 45 - (9 * 60 + 15)   # 14:45
DETECTORS = {"A": (0.95, 0.40), "B": (0.90, 0.40), "C": (0.95, 0.25), "D": (0.90, 0.25)}
EXIT_KEYS = ("X1", "X2")
TESTS = tuple(f"{d}-{x}" for d in DETECTORS for x in EXIT_KEYS)


def day_volume(con: dict, lot: float, nrows: int = 375) -> np.ndarray:
    """(nrows,) option volume of one session in lots: the sum over every held contract's volume row; NaN for a minute
    no contract traded or printed (all NaN). Pure."""
    if not con or not lot:
        return np.full(nrows, np.nan)
    v = np.stack([np.asarray(c[4], float) for c in con.values()])
    known = np.isfinite(v).any(axis=0)
    out = np.nansum(v, axis=0) / float(lot)
    out[~known] = np.nan
    return out


def pct_prev(M: np.ndarray, lookback: int = LOOKBACK, min_known: int = MIN_KNOWN) -> np.ndarray:
    """M: (ndays, nrows) values, sessions in time order. Returns (ndays, nrows): for day t, row r, the share of the
    previous `lookback` sessions' values at row r that are strictly below M[t, r] (NaN when M[t, r] is NaN or fewer
    than `min_known` of those sessions have the row). Only earlier sessions are read. Pure."""
    nd, nr = M.shape
    out = np.full((nd, nr), np.nan)
    for t in range(nd):
        lo = max(0, t - lookback)
        if t - lo < min_known:
            continue
        past = M[lo:t]
        x = M[t]
        known = np.isfinite(past)
        cnt = known.sum(axis=0)
        below = (np.where(known, past, np.inf) < x[None, :]).sum(axis=0)
        ok = np.isfinite(x) & (cnt >= min_known)
        out[t, ok] = below[ok] / cnt[ok]
    return out


def swing_events(o, h, l, c, n: int = N_SWING) -> list:
    """Confirmed swing reversals of one session, before any volume / range condition (also the P-swing placebo).
    Returns [(i, sgn, swept, opposite)]: sgn +1 = downside extreme (buy CE), -1 = upside (buy PE); swept = the
    previous n bars' low (sgn +1) or high; opposite = the previous n bars' high (sgn +1) or low (the X1 target).
    Bar i+1 must be closed: i+1 <= last row. Any NaN in rows i-n .. i+1 means no event. Pure."""
    h, l, c = (np.asarray(x, float) for x in (h, l, c))
    nr = len(c)
    out = []
    for i in range(n, nr - 1):
        win = slice(i - n, i + 2)
        if not (np.isfinite(h[win]).all() and np.isfinite(l[win]).all() and np.isfinite(c[win]).all()):
            continue
        plo, phi = l[i - n:i].min(), h[i - n:i].max()
        dn, up = l[i] < plo, h[i] > phi
        if dn == up:
            continue
        j = i + 1
        if dn and l[j] >= l[i] and (c[j] > plo or c[j] > h[i]):
            out.append((i, 1, float(plo), float(phi)))
        elif up and h[j] <= h[i] and (c[j] < phi or c[j] < l[i]):
            out.append((i, -1, float(phi), float(plo)))
    return out


def absorption_events(o, h, l, c, vp, rp, vmin: float, rmax: float, n: int = N_SWING) -> list:
    """The confirmed swing reversals whose bar i had vp(i) >= vmin and rp(i) <= rmax, entry row in [FIRST_ER, LAST_ER].
    Returns dicts: i, er (= i + 2), sgn, side ('C' / 'P'), stop (the absorption bar's extreme), target (the opposite
    end of the swept range; NaN if close(i+1) is already at or past it), ref (close(i+1)), swept. Pure."""
    out = []
    for i, sgn, swept, opp in swing_events(o, h, l, c, n):
        er = i + 2
        if er < FIRST_ER or er > LAST_ER:
            continue
        v, r = vp[i], rp[i]
        if not (np.isfinite(v) and np.isfinite(r)) or v < vmin or r > rmax:
            continue
        ref = float(c[i + 1])
        tgt = opp if sgn * (opp - ref) > 0 else float("nan")
        out.append(dict(i=i, er=er, sgn=sgn, side="C" if sgn > 0 else "P", stop=float(l[i] if sgn > 0 else h[i]),
                        target=tgt, ref=ref, swept=swept))
    return out


def forced_row(h, l, er: int, sgn: int, stop: float, target: float, nrows: int = 375) -> int:
    """X1's index exit: the first minute m >= er whose index low is below `stop` (CE; PE: high above it) or whose high
    reaches `target` (CE; PE: low) -> sold at m + 1's open; -1 if none (or m + 1 is past the grid). NaN minutes
    trigger nothing; a NaN target is no target. Pure."""
    hh, ll = np.asarray(h, float)[er:], np.asarray(l, float)[er:]
    with np.errstate(invalid="ignore"):
        if sgn > 0:
            hit = (ll < stop) | ((hh >= target) if np.isfinite(target) else False)
        else:
            hit = (hh > stop) | ((ll <= target) if np.isfinite(target) else False)
    k = np.flatnonzero(hit)
    if not len(k):
        return -1
    m = er + int(k[0]) + 1
    return m if m < nrows else -1


def signed_move_bps(c, row: int, k: int, sgn: int) -> float:
    """Signed index move from the close of `row` to the close of `row + k`, in basis points (+ = the expected reversal:
    up after a downside absorption). NaN if either is missing or past the grid. Pure."""
    c = np.asarray(c, float)
    if row < 0 or row + k >= len(c):
        return float("nan")
    a, b = c[row], c[row + k]
    if not (np.isfinite(a) and np.isfinite(b)) or a <= 0:
        return float("nan")
    return float(sgn * (b - a) / a * 1e4)
