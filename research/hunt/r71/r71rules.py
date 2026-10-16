"""R71 chart-pattern rules (PREREG.md section 1). Signal source: the INDEX, every rule.

Input: the continuous series of 5-minute index bars, as R51's `fam_r44.bars5` builds them (day, bar-of-day b, O, H, L,
C; missing bars skipped). Output of every rule: a list of signals (i, side, inval): i is the flat 5-minute bar whose
CLOSE gives the signal (the trade fills at the next 5-minute row), side +1 call / -1 put, inval the index level whose
break is the rule's own stop (exit set XI).

Every value at bar i is computed from bars 0..i only (test_r71.py's look-ahead guard proves it for each rule). A
higher-timeframe bar (15 / 30 minutes) is known only once its LAST 5-minute bar has closed. Pure: numpy only.
"""
from __future__ import annotations

import numpy as np

WARM = 100                                  # first bars of the series give no signal (indicator warm-up)


# ------------------------------------------------------------------------------------------------ helpers
def atr(h, l, c, n=14):
    """Wilder ATR (the first value the mean of the first n true ranges; NaN before)."""
    T = len(c)
    out = np.full(T, np.nan)
    if T < n:
        return out
    tr = np.empty(T)
    tr[0] = h[0] - l[0]
    for t in range(1, T):
        tr[t] = max(h[t] - l[t], abs(h[t] - c[t - 1]), abs(l[t] - c[t - 1]))
    a = tr[:n].mean()
    out[n - 1] = a
    for t in range(n, T):
        a = (a * (n - 1) + tr[t]) / n
        out[t] = a
    return out


def ema(x, n):
    out = np.empty(len(x))
    if not len(x):
        return out
    a = 2.0 / (n + 1.0)
    out[0] = x[0]
    for t in range(1, len(x)):
        out[t] = a * x[t] + (1.0 - a) * out[t - 1]
    return out


def day_starts(d) -> np.ndarray:
    """Flat index of each bar's first bar of the same day."""
    s = np.zeros(len(d), np.int64)
    for i in range(1, len(d)):
        s[i] = s[i - 1] if d[i] == d[i - 1] else i
    return s


def agg(d, b, o, h, l, c, k: int):
    """k x 5-minute bars of each day (slot = b // k). Returns (last flat index, day, O, H, L, C) per slot that has bars;
    a slot is known at its last 5-minute bar's close."""
    last, dd, oo, hh, ll, cc = [], [], [], [], [], []
    i, n = 0, len(d)
    while i < n:
        j = i
        while j + 1 < n and d[j + 1] == d[i] and b[j + 1] // k == b[i] // k:
            j += 1
        last.append(j)
        dd.append(d[i])
        oo.append(o[i])
        hh.append(h[i:j + 1].max())
        ll.append(l[i:j + 1].min())
        cc.append(c[j])
        i = j + 1
    return (np.array(last, np.int64), np.array(dd, dtype=object), np.array(oo), np.array(hh), np.array(ll),
            np.array(cc))


def _break_after(i0, d, c, hi, lo, n_bars):
    """The first 5-minute close above hi (+1) or below lo (-1) in bars i0 .. i0+n_bars-1 of the same day."""
    for j in range(i0, min(i0 + n_bars, len(c))):
        if d[j] != d[i0 - 1]:
            return None
        if c[j] > hi:
            return j, 1
        if c[j] < lo:
            return j, -1
    return None


# ------------------------------------------------------------------------------------------------ P1 inside bar
def inside_bar(d, b, o, h, l, c, k=3, window=6):
    """15-min (k=3) or 30-min (k=6) bar I inside the previous bar M of the same day; the first 5-min close beyond M
    within the next 30 minutes (6 bars) -> that side; inval = the other side of M."""
    last, dd, _, hh, ll, _ = agg(d, b, o, h, l, c, k)
    out = []
    for g in range(1, len(last)):
        if dd[g] != dd[g - 1] or last[g] < WARM:
            continue
        if hh[g] <= hh[g - 1] and ll[g] >= ll[g - 1]:
            r = _break_after(last[g] + 1, d, c, hh[g - 1], ll[g - 1], window)
            if r is not None:
                j, s = r
                out.append((j, s, ll[g - 1] if s > 0 else hh[g - 1]))
    return out


# ------------------------------------------------------------------------------------------------ P2 NR-N bar
def nr_bar(d, b, o, h, l, c, n=7, window=6, k=3):
    """Today's 15-min bar whose range is the smallest of today's last n 15-min bars; the first 5-min close beyond it
    within the next 30 minutes -> that side; inval = its other side."""
    last, dd, _, hh, ll, _ = agg(d, b, o, h, l, c, k)
    rg = hh - ll
    out = []
    for g in range(n - 1, len(last)):
        if dd[g - n + 1] != dd[g] or last[g] < WARM:
            continue
        if rg[g] <= rg[g - n + 1:g].min():
            r = _break_after(last[g] + 1, d, c, hh[g], ll[g], window)
            if r is not None:
                j, s = r
                out.append((j, s, ll[g] if s > 0 else hh[g]))
    return out


# ------------------------------------------------------------------------------------------------ P3 squeeze release
def squeeze(d, b, o, h, l, c, s_min=6, n=20, bb_k=2.0, kc_k=1.5, window=6):
    """Bollinger(20, 2 sd) inside Keltner(EMA20, 1.5 x ATR20) for >= s_min bars in a row; then, within 6 bars of the
    squeeze ending, the first bar still out of the squeeze closing beyond a Bollinger band -> that side;
    inval = the Bollinger middle at the signal."""
    T = len(c)
    mid = np.full(T, np.nan)
    sd = np.full(T, np.nan)
    for t in range(n - 1, T):
        w = c[t - n + 1:t + 1]
        mid[t] = w.mean()
        sd[t] = w.std()
    em = ema(c, n)
    at = atr(h, l, c, n)
    ub, lb = mid + bb_k * sd, mid - bb_k * sd
    on = (ub < em + kc_k * at) & (lb > em - kc_k * at)
    out = []
    run = 0
    t = WARM
    while t < T:
        if on[t]:
            run += 1
            t += 1
            continue
        if run >= s_min:
            for j in range(t, min(t + window, T)):
                if on[j] or d[j] != d[t]:
                    break
                if c[j] > ub[j]:
                    out.append((j, 1, mid[j]))
                    break
                if c[j] < lb[j]:
                    out.append((j, -1, mid[j]))
                    break
        run = 0
        t += 1
    return out


# ------------------------------------------------------------------------------------------------ P4 flag
def flag(d, b, o, h, l, c, K=2.0, min_flag=3, max_flag=10):
    """Pole: 3 bars of one day ending at p with rising closes and close_p - open_(p-2) >= K x ATR14 (puts: the
    mirror). Flag: the next bars never trade beyond the pole's extreme nor retrace more than half the pole; the first
    close beyond the pole extreme after >= 3 flag bars -> that side (a flag bar trading beyond without closing beyond,
    a > 50% retrace, more than 10 flag bars or the day's end kills the setup); inval = the flag's far extreme."""
    at = atr(h, l, c, 14)
    T = len(c)
    seen = set()
    out = []
    for p in range(max(WARM, 2), T):
        if d[p - 2] != d[p] or not np.isfinite(at[p]):
            continue
        for s in (1, -1):
            mono = (c[p - 2] < c[p - 1] < c[p]) if s > 0 else (c[p - 2] > c[p - 1] > c[p])
            if not mono or s * (c[p] - o[p - 2]) < K * at[p]:
                continue
            top, bot = h[p - 2:p + 1].max(), l[p - 2:p + 1].min()
            ext = top if s > 0 else bot
            half = top - 0.5 * (top - bot) if s > 0 else bot + 0.5 * (top - bot)
            far = None
            for j in range(p + 1, min(p + 2 + max_flag, T)):
                if d[j] != d[p]:
                    break
                nflag = j - p - 1
                beyond = h[j] > ext if s > 0 else l[j] < ext
                if beyond:
                    closed = c[j] > ext if s > 0 else c[j] < ext
                    if closed and nflag >= min_flag and (j, s) not in seen:
                        seen.add((j, s))
                        out.append((j, s, far))
                    break
                if (l[j] < half) if s > 0 else (h[j] > half):
                    break
                if nflag >= max_flag:
                    break
                far = (l[j] if far is None else min(far, l[j])) if s > 0 else (h[j] if far is None else max(far, h[j]))
    return out


# ------------------------------------------------------------------------------------------------ P5 double bottom / top
def double_bottom(d, b, o, h, l, c, T_=0.25, gap=4, neck_atr=1.0, w=2):
    """5-min swing lows (below the w bars each side, all the same day; known w bars later). The two most recent of
    today, >= gap bars apart, within T x ATR14 of each other, with a neckline (the highest high between) >= 1 ATR14
    above the higher low: the first close above the neckline from the confirmation on, before any low below the lower
    low -> call; inval = the lower low. Double top: the mirror -> put. One signal per pair."""
    at = atr(h, l, c, 14)
    T = len(c)
    out = []
    for s in (1, -1):
        x = l if s > 0 else -h                       # work on lows; tops are the mirror (negated highs)
        y = h if s > 0 else -l
        cc = c if s > 0 else -c
        piv = []                                     # confirmed swing points of the current day: (i, confirm bar)
        fired = set()
        for j in range(T):
            if j > 0 and d[j] != d[j - 1]:
                piv = []
            i = j - w                                # the bar that j confirms
            if i - w >= 0 and all(d[k] == d[j] for k in range(i - w, j + 1)):
                if all(x[i] < x[k] for k in range(i - w, i + w + 1) if k != i):
                    piv.append(i)
            if j < WARM or len(piv) < 2:
                continue
            i1, i2 = piv[-2], piv[-1]
            if (i1, i2) in fired or i2 - i1 < gap or not np.isfinite(at[i2 + w]):
                continue
            a = at[i2 + w]
            if abs(x[i1] - x[i2]) > T_ * a:
                continue
            neck = y[i1 + 1:i2].max()
            if neck - max(x[i1], x[i2]) < neck_atr * a:
                continue
            low = min(x[i1], x[i2])
            if x[i2 + 1:j + 1].min(initial=np.inf) < low:
                fired.add((i1, i2))                  # the pattern failed: a lower low came first
                continue
            if cc[j] > neck:
                fired.add((i1, i2))
                out.append((j, s, low if s > 0 else -low))
    out.sort()
    return out


# ------------------------------------------------------------------------------------------------ P6 Heikin-Ashi flip
def ha_flip(d, b, o, h, l, c, L=20, k=3):
    """15-min Heikin-Ashi (continuous): a green HA bar with no lower wick after >= 2 red HA bars while the 15-min close
    is above EMA(L) of 15-min closes -> call (mirror -> put); inval = the lowest real low (highest high) of the last 3
    15-min bars."""
    last, dd, oo, hh, ll, cc = agg(d, b, o, h, l, c, k)
    G = len(last)
    hc = (oo + hh + ll + cc) / 4.0
    ho = np.empty(G)
    if G:
        ho[0] = (oo[0] + cc[0]) / 2.0
    for g in range(1, G):
        ho[g] = (ho[g - 1] + hc[g - 1]) / 2.0
    hhi = np.maximum(hh, np.maximum(ho, hc))
    hlo = np.minimum(ll, np.minimum(ho, hc))
    em = ema(cc, L)
    green = hc > ho
    eps = 1e-9
    out = []
    for g in range(max(2, L), G):
        if last[g] < WARM:
            continue
        if green[g] and hlo[g] >= ho[g] - eps and not green[g - 1] and not green[g - 2] and cc[g] > em[g]:
            out.append((int(last[g]), 1, ll[g - 2:g + 1].min()))
        elif (not green[g]) and hhi[g] <= ho[g] + eps and green[g - 1] and green[g - 2] and cc[g] < em[g]:
            out.append((int(last[g]), -1, hh[g - 2:g + 1].max()))
    return out


# ------------------------------------------------------------------------------------------------ P7 three-bar reversal
def three_bar(d, b, o, h, l, c, start_b=6):
    """5-min: bar 1 (from bar-of-day start_b) makes a new low of the day, bar 2 no lower low, bar 3 closes above bar 1's
    high with no lower low -> call; inval = bar 1's low (new day high: the mirror -> put)."""
    ds = day_starts(d)
    out = []
    for i in range(max(WARM, 1), len(c) - 2):
        if b[i] < start_b or d[i + 2] != d[i] or ds[i] == i:
            continue
        s0 = ds[i]
        if l[i] < l[s0:i].min() and l[i + 1] >= l[i] and l[i + 2] >= l[i] and c[i + 2] > h[i]:
            out.append((i + 2, 1, l[i]))
        elif h[i] > h[s0:i].max() and h[i + 1] <= h[i] and h[i + 2] <= h[i] and c[i + 2] < l[i]:
            out.append((i + 2, -1, h[i]))
    return out


# ------------------------------------------------------------------------------------------------ P8 swing structure
def swing_structure(d, b, o, h, l, c, W=2, k=3):
    """15-min swing highs / lows (beyond the W bars each side; known when the W-th later 15-min bar closes; continuous
    series). Up: the last two swing highs rise, the last two swing lows rise, the last low after the last high; the
    first 5-min close above the last swing high -> call; inval = the last swing low. Down: the mirror -> put. One
    signal per (high, low) pair."""
    last, dd, _, hh, ll, _ = agg(d, b, o, h, l, c, k)
    G = len(last)
    known_h, known_l = [], []                       # (known at flat 5-min index, 15-min index, price)
    for g in range(W, G - W):
        nb = [x for x in range(g - W, g + W + 1) if x != g]
        if all(hh[g] > hh[x] for x in nb):
            known_h.append((int(last[g + W]), g, hh[g]))
        if all(ll[g] < ll[x] for x in nb):
            known_l.append((int(last[g + W]), g, ll[g]))
    out = []
    fired = set()
    ih = il = 0
    H, Lw = [], []
    for j in range(len(c)):
        while ih < len(known_h) and known_h[ih][0] <= j:
            H.append(known_h[ih])
            ih += 1
        while il < len(known_l) and known_l[il][0] <= j:
            Lw.append(known_l[il])
            il += 1
        if j < WARM or len(H) < 2 or len(Lw) < 2:
            continue
        (_, gh1, h1), (_, gh2, h2) = H[-2], H[-1]
        (_, gl1, l1), (_, gl2, l2) = Lw[-2], Lw[-1]
        if h2 > h1 and l2 > l1 and gl2 > gh2 and c[j] > h2 and ("u", gh2, gl2) not in fired:
            fired.add(("u", gh2, gl2))
            out.append((j, 1, l2))
        elif h2 < h1 and l2 < l1 and gh2 > gl2 and c[j] < l2 and ("d", gh2, gl2) not in fired:
            fired.add(("d", gh2, gl2))
            out.append((j, -1, h2))
    return out


# ------------------------------------------------------------------------------------------------ the family
RULES = {   # id: (function, param name, original, grid values) - PREREG section 1
    "P1": (inside_bar, "k", 3, (3, 6)),
    "P2": (nr_bar, "n", 7, (7, 4)),
    "P3": (squeeze, "s_min", 6, (6, 12)),
    "P4": (flag, "K", 2.0, (2.0, 1.5)),
    "P5": (double_bottom, "T_", 0.25, (0.25, 0.5)),
    "P6": (ha_flip, "L", 20, (20, 50)),
    "P7": (three_bar, "start_b", 6, (6, 15)),
    "P8": (swing_structure, "W", 2, (2, 3)),
}
NAMES = {"P1": "Inside bar", "P2": "NR-N bar", "P3": "Squeeze release", "P4": "Flag", "P5": "Double bottom / top",
         "P6": "Heikin-Ashi flip in trend", "P7": "Three-bar reversal at the day's extreme",
         "P8": "Swing structure (HH + HL)"}


def signals(rule: str, param, d, b, o, h, l, c) -> list:
    fn, name, _, _ = RULES[rule]
    return fn(d, b, o, h, l, c, **{name: param})
