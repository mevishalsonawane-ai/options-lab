"""R167 measurements (PREREG section 2): everything a rule may read at the close of minute m, per day and minute.

Pure numpy (no stores). Built from a list of (4, 375) index O H L C day arrays, oldest first, so ATR and Supertrend carry
across days. Every per-minute value at row m depends only on minutes <= m (test_r167.py cuts days after m and checks).

    ATR5(m)     Wilder ATR(14) of the 09:15-anchored 5-minute index bars closed by minute m (bar closed: last row <= m)
    levels(m)   R150 Amendment B L20 map: 5-minute pivots, 6 bars a side, 0.15% groups, last 20 sessions, 3+ touches
    swings      5-minute pivots with 2 bars a side (the "latest higher low" / "lower high")
    st(m)       Supertrend (10, 3) direction on 1-minute index bars (+1 up, -1 down, NaN no bar yet)
    er(m)       Kaufman efficiency ratio of the day's 1-minute closes from 09:15 to m (NaN before 15 printed minutes)
    vix(m)      India VIX's close, carried forward within the day (NaN before its first print)

Plain-English notes, 12 Nov 2026.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

_R150 = Path(__file__).resolve().parents[1] / "r150_sr_levels"
if str(_R150.parent) not in sys.path:
    sys.path.insert(0, str(_R150.parent))
from r150_sr_levels import levels as LV  # noqa: E402  (R150's bars, pivots, grouping)

N = 375
ATR_N = 14
LEVEL_K = 6            # R150 Amendment B: 6 bars (30 minutes) each side
LEVEL_WINDOW = 20      # L20
LEVEL_TOL = 0.0015
MIN_TOUCHES = 3
SWING_K = 2
ST_N, ST_MULT = 10, 3.0
ER_MIN_MINUTES = 15


# ================================================================================================ Wilder averages
def wilder(x: np.ndarray, n: int, min_count: int | None = None) -> np.ndarray:
    """Wilder average of x (NaN entries skipped; the value carries). Until n values exist: the mean of those seen, once
    at least min_count (default n) have been seen; then (prev x (n - 1) + x) / n."""
    min_count = n if min_count is None else min_count
    out = np.full(len(x), np.nan)
    cnt, s, prev = 0, 0.0, np.nan
    for i, v in enumerate(x):
        if v == v:
            cnt += 1
            if cnt <= n:
                s += v
                prev = s / cnt if cnt >= min_count else np.nan
                if cnt == n:
                    prev = s / n
            else:
                prev = (prev * (n - 1) + v) / n
        out[i] = prev
    return out


def true_range(h: np.ndarray, l_: np.ndarray, c: np.ndarray) -> np.ndarray:
    """TR with the previous bar's close (the first bar: high - low)."""
    pc = np.concatenate([[np.nan], c[:-1]])
    a = h - l_
    b = np.abs(h - pc)
    d = np.abs(l_ - pc)
    tr = np.fmax(a, np.fmax(b, d))
    return np.where(np.isfinite(h) & np.isfinite(l_), tr, np.nan)


# ================================================================================================ day context
@dataclass
class DayCtx:
    """One day's measurements; o h l c are the index's (375,) minute arrays."""
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray  # noqa: E741
    c: np.ndarray
    atr5: np.ndarray
    st: np.ndarray
    er: np.ndarray
    vix: np.ndarray
    lev_key: np.ndarray                       # (375,) int: which level list is known at minute m (-1: none yet)
    lev_lists: list = field(default_factory=list)       # [[level, ...]] 3+-touch levels, sorted
    sw_price: np.ndarray = field(default_factory=lambda: np.zeros(0))   # swings in bar order (last 20 sessions)
    sw_dir: np.ndarray = field(default_factory=lambda: np.zeros(0))     # +1 high, -1 low
    sw_known: np.ndarray = field(default_factory=lambda: np.zeros(0, int))  # minute of this day it is known (-1: before)

    def levels_at(self, m: int) -> list:
        k = int(self.lev_key[m]) if 0 <= m < N else -1
        return self.lev_lists[k] if k >= 0 else []

    def swing_below(self, m: int, s: int, fc_now: float):
        """In favourable coordinates (s = +1 call, -1 put): the latest known swing low (call) / swing high (put, as -price)
        whose favourable price is below fc_now; None if none."""
        want = -1 if s > 0 else 1
        for i in range(len(self.sw_price) - 1, -1, -1):
            if self.sw_dir[i] != want or self.sw_known[i] > m:
                continue                                     # most recent first: usually found within a few swings
            x = s * self.sw_price[i]
            if x < fc_now:
                return float(x)
        return None


def _minute_index(bars_day: np.ndarray, bars_end: np.ndarray, di: int) -> np.ndarray:
    """(375,) int: the index of the last 5-minute bar closed by minute m of day di (bars of earlier days count), -1."""
    out = np.full(N, -1, int)
    before = np.flatnonzero(bars_day < di)
    last_prev = int(before[-1]) if len(before) else -1
    mine = np.flatnonzero(bars_day == di)
    j = 0
    cur = last_prev
    for m in range(N):
        while j < len(mine) and bars_end[mine[j]] <= m:
            cur = int(mine[j])
            j += 1
        out[m] = cur
    return out


def supertrend_dir(h: np.ndarray, l_: np.ndarray, c: np.ndarray, n: int = ST_N, mult: float = ST_MULT) -> np.ndarray:
    """Supertrend direction (+1 / -1) on a continuous bar series (NaN bars are skipped and get NaN); Wilder ATR(n)."""
    ok = np.flatnonzero(np.isfinite(h) & np.isfinite(l_) & np.isfinite(c))
    out = np.full(len(c), np.nan)
    if not len(ok):
        return out
    hh, ll, cc = h[ok], l_[ok], c[ok]
    atr = wilder(true_range(hh, ll, cc), n)
    mid = (hh + ll) / 2
    ub, lb = mid + mult * atr, mid - mult * atr
    fub, flb = np.nan, np.nan
    d = np.nan
    res = np.full(len(ok), np.nan)
    for i in range(len(ok)):
        if not np.isfinite(atr[i]):
            continue
        if not np.isfinite(fub):
            fub, flb, d = ub[i], lb[i], 1.0
        else:
            nub = ub[i] if (ub[i] < fub or cc[i - 1] > fub) else fub
            nlb = lb[i] if (lb[i] > flb or cc[i - 1] < flb) else flb
            if d > 0 and cc[i] < nlb:
                d = -1.0
            elif d < 0 and cc[i] > nub:
                d = 1.0
            fub, flb = nub, nlb
        res[i] = d
    out[ok] = res
    return out


def efficiency(c: np.ndarray, min_minutes: int = ER_MIN_MINUTES) -> np.ndarray:
    """(375,) Kaufman ER of the day's closes from its first print to m; NaN before min_minutes printed."""
    out = np.full(len(c), np.nan)
    first = None
    path, cnt, last = 0.0, 0, np.nan
    for m in range(len(c)):
        x = c[m]
        if x == x:
            cnt += 1
            if first is None:
                first = x
            elif last == last:
                path += abs(x - last)
            last = x
        if cnt >= min_minutes and first is not None:
            out[m] = abs(last - first) / path if path > 0 else 0.0
    return out


def carry(x: np.ndarray) -> np.ndarray:
    """Forward-fill NaN within one day."""
    out = np.array(x, float)
    for m in range(1, len(out)):
        if not out[m] == out[m]:
            out[m] = out[m - 1]
    return out


def build(day_arrays: list, vix_arrays: list | None = None, first_ctx: int = 0) -> list:
    """[DayCtx | None] for each day (None before `first_ctx`). day_arrays: (4, 375) O H L C, oldest first; vix_arrays:
    (375,) VIX closes per day (None = no VIX)."""
    nd = len(day_arrays)
    b = LV.bars15(day_arrays, minutes=5)
    atr_b = wilder(true_range(b["h"], b["l"], b["c"]), ATR_N)
    piv = LV.pivots(b["h"], b["l"], LEVEL_K)
    sw = LV.pivots(b["h"], b["l"], SWING_K)
    # 1-minute Supertrend over all days (continuous)
    cat = np.concatenate(day_arrays, axis=1) if nd else np.zeros((4, 0))
    st_all = supertrend_dir(cat[1], cat[2], cat[3])
    out = []
    for di in range(nd):
        if di < first_ctx:
            out.append(None)
            continue
        a = day_arrays[di]
        mi = _minute_index(b["day"], b["end"], di)
        atr5 = np.array([atr_b[j] if j >= 0 else np.nan for j in mi])
        # levels: one list per distinct "last closed bar"
        keys, lists, cache = np.full(N, -1, int), [], {}
        starts = np.flatnonzero(b["day"] >= di - LEVEL_WINDOW + 1)
        first_bar = int(starts[0]) if len(starts) else 0
        last_bar = int(mi[-1])
        piv_day = [p for p in piv if p[0] >= first_bar and p[3] <= last_bar]     # this day's 20-session window only
        for m in range(N):
            j = int(mi[m])
            if j < 0:
                continue
            if j not in cache:
                kp = LV.known_pivots(piv_day, j, first_bar)
                g = LV.group([p[1] for p in kp], LEVEL_TOL)
                cache[j] = len(lists)
                lists.append(sorted(lv for lv, n in g if n >= MIN_TOUCHES))
            keys[m] = cache[j]
        # swings of the last 20 sessions, with the minute of this day they become known
        sp, sd, sk = [], [], []
        for bar, price, dirn, conf in sw:
            if bar < first_bar or conf >= len(b["day"]) or b["day"][conf] > di:
                continue
            known = -1 if b["day"][conf] < di else int(b["end"][conf])
            sp.append(price)
            sd.append(dirn)
            sk.append(known)
        vx = carry(vix_arrays[di]) if vix_arrays is not None and vix_arrays[di] is not None else np.full(N, np.nan)
        out.append(DayCtx(o=a[0].astype(float), h=a[1].astype(float), l=a[2].astype(float), c=a[3].astype(float),
                          atr5=atr5, st=st_all[di * N:(di + 1) * N], er=efficiency(a[3]), vix=vx, lev_key=keys,
                          lev_lists=lists, sw_price=np.asarray(sp, float), sw_dir=np.asarray(sd, float),
                          sw_known=np.asarray(sk, int)))
    return out
