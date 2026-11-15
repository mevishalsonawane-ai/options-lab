"""R180 rules (PREREG.md section 2): the manual's two books, as written, on the index (signal source: the index spot).

Pure numpy, no file access. Inputs per index:
    bars   (d, b, o, h, l, c): the continuous 5-minute index bars (R72's `fam_r44.bars5`), d = day, b = bar of day
    mins   {und: {day: (4, 375)}} index O H L C per minute (the store's `idx`)
    strad  {und: {day: (375,)}} straddle_vs_0920 per minute (`straddle_vs_0920` below)

Outputs: candidate dicts (book, und, day, side +1 call / -1 put, the signal minute, the straddle value used for the
tie-break, and Book 2's ref / stop points). Pricing and the one-position coordinator live in r180engine.py.
Every value at a minute or bar uses only that minute / bar and earlier ones (test_r180.py's look-ahead guard).
"""
from __future__ import annotations

import numpy as np

import r180cfg as K

NROWS = 375


# ------------------------------------------------------------------------------------------------ straddle measure
def straddle_series(atm, con) -> np.ndarray:
    """S(m) = close of the ATM call + close of the ATM put at minute m, ATM = the offset-0 strike of minute m (the rows
    R30's chain_features reads); NaN where the label or either leg's close is missing."""
    out = np.full(NROWS, np.nan)
    if atm is None:
        return out
    for m in range(NROWS):
        k = atm[m]
        if not np.isfinite(k):
            continue
        ce, pe = con.get(("C", float(k))), con.get(("P", float(k)))
        if ce is None or pe is None:
            continue
        a, b = float(ce[3, m]), float(pe[3, m])
        if np.isfinite(a) and np.isfinite(b):
            out[m] = a + b
    return out


def straddle_vs_0920(S: np.ndarray) -> np.ndarray:
    """(S(m) - S(09:20)) / S(09:20) for m >= 09:20; NaN before 09:20 or when S(09:20) is missing (no trade)."""
    out = np.full(len(S), np.nan)
    base = S[K.R0920]
    if not (np.isfinite(base) and base > 0):
        return out
    out[K.R0920:] = (S[K.R0920:] - base) / base
    return out


# ------------------------------------------------------------------------------------------------ 5-minute features
def donchian_prior(h, l, n=K.B1_NCH):
    """chi / clo = max high / min low of the PRIOR n bars (NaN for the first n)."""
    T = len(h)
    chi, clo = np.full(T, np.nan), np.full(T, np.nan)
    for i in range(n, T):
        chi[i] = h[i - n:i].max()
        clo[i] = l[i - n:i].min()
    return chi, clo


def atr_ewm(h, l, c, span=K.B1_ATR_SPAN):
    """ATR14 = EWM(span, adjust=False) of the true range (manual Step 1.2); TR[0] = H - L."""
    T = len(c)
    out = np.full(T, np.nan)
    if not T:
        return out
    a = 2.0 / (span + 1.0)
    tr0 = h[0] - l[0]
    out[0] = tr0
    for t in range(1, T):
        tr = max(h[t] - l[t], abs(h[t] - c[t - 1]), abs(l[t] - c[t - 1]))
        out[t] = a * tr + (1.0 - a) * out[t - 1]
    return out


def last_bar_at(b_of_day: np.ndarray, t: int) -> int:
    """Position (in a day's sorted bar numbers) of the last 5-minute bar COMPLETED by the close of minute t; -1 if none.
    Bar bb covers minutes 5bb .. 5bb+4 and completes at the close of minute 5bb+4."""
    done = (t + 1) // 5 - 1                 # the highest bar number whose last minute is <= t
    return int(np.searchsorted(b_of_day, done, side="right")) - 1


# ------------------------------------------------------------------------------------------------ Book 1
class B1Index:
    def __init__(self, bars):
        d, b, o, h, l, c = bars
        self.d, self.b, self.o, self.h, self.l, self.c = d, np.asarray(b), o, h, l, c
        self.chi, self.clo = donchian_prior(h, l)
        self.mid = (self.chi + self.clo) / 2
        self.atr = atr_ewm(h, l, c)
        idx = np.arange(len(c))
        with np.errstate(invalid="ignore"):
            self.up = (c > self.chi) & (idx >= K.B1_WARM)
            self.dn = (c < self.clo) & (idx >= K.B1_WARM)


def release_minute(mins_day: np.ndarray, m_lbl: int, mid: float, s: int, last: int = K.row(15, 10)) -> int:
    """xm: the first minute after m_lbl whose low <= mid (s = +1) / high >= mid (s = -1), else `last` (355)."""
    for m in range(m_lbl + 1, last + 1):
        x = mins_day[2, m] if s > 0 else mins_day[1, m]
        if np.isfinite(x) and ((x <= mid) if s > 0 else (x >= mid)):
            return m
    return last


def book1_candidates(bars: dict, mins: dict, strad: dict, unds=K.UNDS, initial_lock: bool = True) -> tuple[list, dict]:
    """Every accepted Book 1 signal (before the cap and the one-position rule) and counts of what was dropped.
    initial_lock False (post-hoc diagnostic only, not a test): no per-index "initial breakout" state machine."""
    F = {u: B1Index(bars[u]) for u in unds}
    rows = {}
    for u in unds:
        for i in range(len(F[u].c)):
            rows.setdefault((F[u].d[i], int(F[u].b[i])), {})[u] = i
    lock = {u: (None, -1) for u in unds}          # (day, first free minute)
    out = []
    cnt = dict(breadth=0, not_initial=0, window=0, strength=0, straddle=0, accepted=0)
    for (day, bb) in sorted(rows):
        at = rows[(day, bb)]
        for s, flag in ((1, "up"), (-1, "dn")):
            hit = [u for u in unds if u in at and getattr(F[u], flag)[at[u]]]
            if len(hit) < K.B1_M:
                continue
            m_lbl = 5 * bb + 4
            em = m_lbl + 1
            for u in hit:
                cnt["breadth"] += 1
                i = at[u]
                ld, lm = lock[u]
                if initial_lock and ld == day and em < lm:
                    cnt["not_initial"] += 1
                    continue
                f = F[u]
                md = mins.get(u, {}).get(day)
                xm = release_minute(md, m_lbl, f.mid[i], s) if md is not None else K.row(15, 10)
                lock[u] = (day, xm + 1)
                if not (K.B1_FIRST <= em <= K.B1_LAST):
                    cnt["window"] += 1
                    continue
                edge = f.chi[i] if s > 0 else f.clo[i]
                a = f.atr[i]
                if not (np.isfinite(a) and a > 0 and abs(f.c[i] - edge) / a >= K.B1_STR):
                    cnt["strength"] += 1
                    continue
                sv = strad.get(u, {}).get(day)
                v = float(sv[m_lbl]) if sv is not None else np.nan
                if not (np.isfinite(v) and v > 0):
                    cnt["straddle"] += 1
                    continue
                cnt["accepted"] += 1
                out.append(dict(book="B1", und=u, day=day, side=s, sig=m_lbl, em=em, strad=v,
                                strength=float(abs(f.c[i] - edge) / a)))
    return out, cnt


# ------------------------------------------------------------------------------------------------ Book 2
def order_blocks(bars, k=K.B2_K):
    """Displacement bars and their order blocks on one index's continuous 5-minute series.
    Returns [(i_disp, side, L, H)]: side +1 = a bullish displacement (its OB = the last bearish candle before it)."""
    d, b, o, h, l, c = bars
    T = len(c)
    body = np.abs(c - o)
    sh = sl = None                      # last confirmed swing high / low value
    sh_broken = sl_broken = True
    out = []
    for i in range(T):
        # 1. the close of bar i against the last swing confirmed by bar i-1 (each swing broken once)
        brk = 0
        if sh is not None and not sh_broken and c[i] > sh:
            sh_broken = True
            brk = 1
        if sl is not None and not sl_broken and c[i] < sl:
            sl_broken = True
            brk = -1 if brk == 0 else brk
        if brk != 0 and i >= K.B2_MED_N:
            med = float(np.median(body[i - K.B2_MED_N:i]))
            s = 1 if c[i] > o[i] else (-1 if c[i] < o[i] else 0)
            if s == brk and body[i] > K.B2_DISP * med:
                for j in range(i - 1, max(i - 1 - K.B2_OB_BACK, -1), -1):
                    if (c[j] < o[j]) if s > 0 else (c[j] > o[j]):
                        out.append((i, s, float(l[j]), float(h[j])))
                        break
        # 2. swings confirmed at the close of bar i: bar i-2 a strict extremum of i-4 .. i
        if i >= 2 * k:
            p = i - k
            win = list(range(i - 2 * k, i + 1))
            if all(h[p] > h[q] for q in win if q != p):
                sh, sh_broken = float(h[p]), False
            if all(l[p] < l[q] for q in win if q != p):
                sl, sl_broken = float(l[p]), False
    return out


def book2_candidates(bars: dict, mins: dict, strad: dict, unds=K.B2_UNDS) -> tuple[list, dict]:
    out = []
    cnt = dict(order_blocks=0, in_window=0, triggered=0)
    for u in unds:
        d, b, o, h, l, c = bars[u]
        b = np.asarray(b)
        atr = atr_ewm(h, l, c)
        day_bars = {}
        for i in range(len(c)):
            day_bars.setdefault(d[i], []).append(i)
        for i, s, L, H in order_blocks(bars[u]):
            cnt["order_blocks"] += 1
            day = d[i]
            first = 5 * int(b[i]) + 5
            last = 5 * (int(b[i]) + K.B2_OB_LIFE) + 4
            lo, hi = max(first, K.B2_FIRST), min(last, K.B2_LAST)
            if lo > hi:
                continue
            cnt["in_window"] += 1
            md = mins.get(u, {}).get(day)
            sv = strad.get(u, {}).get(day)
            if md is None or sv is None:
                continue
            ii = np.array(day_bars[day])
            bod = b[ii]
            for t in range(lo, hi + 1):
                O, Hh, Ll, C = md[0, t], md[1, t], md[2, t], md[3, t]
                if not (np.isfinite(O) and np.isfinite(C) and np.isfinite(Hh) and np.isfinite(Ll)):
                    continue
                if not (Ll <= H and Hh >= L):
                    continue
                if s > 0 and not (C > O and C >= L):
                    continue
                if s < 0 and not (C < O and C <= H):
                    continue
                pos = last_bar_at(bod, t)
                if pos < 0:
                    continue
                cb = int(ii[pos])
                v = float(sv[5 * int(b[cb]) + 4])
                if not (np.isfinite(v) and v < K.B2_STRAD):
                    continue
                a = float(atr[cb])
                far = L if s > 0 else H
                stop_pts = abs(C - far) + K.B2_ATR_K * a
                cnt["triggered"] += 1
                out.append(dict(book="B2", und=u, day=day, side=s, sig=t, em=t, strad=v, ref=float(C),
                                stop_pts=float(stop_pts), disp=int(i), ob=(L, H)))
                break
    out.sort(key=lambda x: (x["day"], x["sig"]))
    return out, cnt


# ------------------------------------------------------------------------------------------------ one-position account
def one_position(cands: list, pause: float | None = None) -> list:
    """Pick trades for ONE position at a time. cands: priced candidates with 'day', 'open' (the minute the position
    opens), 'xr' (exit minute), 'net', 'ok' (filled and within the cap), 'order' (the tie-break key, lower first).
    A candidate is taken only if no position is open (open >= last exit + 1 on the same day), the month is not paused
    (the month's closed net <= pause stops new entries until the next month), and it is the first acceptable one at its
    minute in tie order. Returns the taken candidates in order."""
    taken = []
    free = (None, -1)
    month, mnet = None, 0.0
    srt = sorted(cands, key=lambda x: (x["day"], x["open"], x["order"]))
    for cnd in srt:
        day = cnd["day"]
        mo = (day.year, day.month)
        if mo != month:
            month, mnet = mo, 0.0
        if pause is not None and mnet <= pause:
            continue
        if free[0] == day and cnd["open"] < free[1]:
            continue
        if not cnd["ok"]:
            continue
        taken.append(cnd)
        free = (day, cnd["xr"] + 1)
        mnet += cnd["net"]
    return taken
