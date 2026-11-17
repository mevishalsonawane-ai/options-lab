"""R183 pure parts (PREREG.md section 3): the sideways filters on the index, the selection rule, the BH count, the
normal-tail twin p. No data is read here. Plain-English notes, 15 Nov 2026.

Rows are 1-minute rows from 09:15 (0 .. 374). For a trade with signal-entry row `er`, the signal bar is s = er - 1 and
only today's rows 0 .. s - 1 are used (strictly before the signal bar). Every function takes `s` and slices the bars
itself, so nothing at or after the signal bar can reach a value (the look-ahead guard in test_r183.py checks this).
"""
from __future__ import annotations

import math

import numpy as np

# id -> (feature, comparison, threshold): skip when feature <cmp> threshold
SPEC = {
    "ADX15": ("adx", "<", 15.0), "ADX20": ("adx", "<", 20.0), "ADX25": ("adx", "<", 25.0),
    "ER6_20": ("er6", "<", 0.20), "ER6_30": ("er6", "<", 0.30), "ER12_20": ("er12", "<", 0.20), "ER12_30": ("er12", "<", 0.30),
    "TX3": ("tx", ">=", 3), "TX5": ("tx", ">=", 5), "TX7": ("tx", ">=", 7),
    "RF40": ("rf", "<", 0.40), "RF60": ("rf", "<", 0.60),
    "RT70": ("rt", "<", 0.70), "RT85": ("rt", "<", 0.85),
    "CH55": ("chop", ">", 55.0), "CH62": ("chop", ">", 61.8),
}
VARIANTS = tuple(SPEC)                                               # the table's order (ties go to the earlier)
LABEL = {
    "ADX15": "ADX(14) 5-min < 15", "ADX20": "ADX(14) 5-min < 20", "ADX25": "ADX(14) 5-min < 25",
    "ER6_20": "efficiency ratio, 6 x 5-min < 0.20", "ER6_30": "efficiency ratio, 6 x 5-min < 0.30",
    "ER12_20": "efficiency ratio, 12 x 5-min < 0.20", "ER12_30": "efficiency ratio, 12 x 5-min < 0.30",
    "TX3": ">= 3 TWAP crossings today", "TX5": ">= 5 TWAP crossings today", "TX7": ">= 7 TWAP crossings today",
    "RF40": "range so far < 40% of the 20-day average range", "RF60": "range so far < 60% of the 20-day average range",
    "RT70": "range so far < 70% of the usual range by this time", "RT85": "range so far < 85% of the usual range by this time",
    "CH55": "Choppiness(14) 5-min > 55", "CH62": "Choppiness(14) 5-min > 61.8",
}
N_PREV5 = 3                  # earlier sessions in the 5-minute series
N_ADR = 20                   # sessions in the average daily range
ARMS = ("R72", "R51")
N_SEL = len(VARIANTS) * len(ARMS)                                    # 32 selection comparisons
M_PRIOR = 4073 + 270         # after R182 (4,073) + R177-EMA's 270 (run after R182)


def m_total(n_tests: int) -> int:
    return M_PRIOR + N_SEL + n_tests


# ================================================================================================ bars
def bars5(idx: np.ndarray, s: int | None = None) -> np.ndarray:
    """(4, k) 5-minute O H L C from a day's (4, 375) 1-minute bars, using only rows < s (None: the whole day). A 5-minute
    bar is kept only if its last row is < s; an all-NaN bar is dropped."""
    n = idx.shape[1] if s is None else max(0, min(int(s), idx.shape[1]))
    out = []
    for a in range(0, n - 4, 5):
        if a + 4 >= n:
            break
        o, h, l_, c = (idx[i, a:a + 5] for i in range(4))
        ok = np.isfinite(c)
        if not ok.any():
            continue
        fo = o[np.isfinite(o)]
        out.append((fo[0] if len(fo) else c[ok][0], np.nanmax(h) if np.isfinite(h).any() else np.nanmax(c),
                    np.nanmin(l_) if np.isfinite(l_).any() else np.nanmin(c), c[ok][-1]))
    return np.array(out, float).T.reshape(4, -1)


def series5(prev: list, today: np.ndarray, s: int) -> np.ndarray:
    """The previous sessions' full 5-minute bars (oldest first) followed by today's complete bars before s."""
    parts = [bars5(p) for p in prev[-N_PREV5:]] + [bars5(today, s)]
    return np.concatenate(parts, axis=1) if parts else np.zeros((4, 0))


# ================================================================================================ indicators
def adx(h: np.ndarray, l_: np.ndarray, c: np.ndarray, n: int = 14) -> float:
    """Wilder's ADX(n) at the last bar; NaN with fewer than 2n + 1 bars."""
    k = len(c)
    if k < 2 * n + 1:
        return float("nan")
    up, dn = h[1:] - h[:-1], l_[:-1] - l_[1:]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    ndm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = np.maximum.reduce([h[1:] - l_[1:], np.abs(h[1:] - c[:-1]), np.abs(l_[1:] - c[:-1])])
    s_tr, s_p, s_n = tr[:n].sum(), pdm[:n].sum(), ndm[:n].sum()
    dx = []

    def _dx(a, b, t):
        if t <= 0:
            return 0.0
        pi, ni = 100 * a / t, 100 * b / t
        return 0.0 if pi + ni == 0 else 100 * abs(pi - ni) / (pi + ni)

    dx.append(_dx(s_p, s_n, s_tr))
    for i in range(n, len(tr)):
        s_tr = s_tr - s_tr / n + tr[i]
        s_p = s_p - s_p / n + pdm[i]
        s_n = s_n - s_n / n + ndm[i]
        dx.append(_dx(s_p, s_n, s_tr))
    a = float(np.mean(dx[:n]))
    for x in dx[n:]:
        a = (a * (n - 1) + x) / n
    return a


def eff_ratio(c: np.ndarray, n: int) -> float:
    """Kaufman's efficiency ratio over the last n steps (n + 1 closes); NaN if too few; 1.0 if the path is flat."""
    if len(c) < n + 1:
        return float("nan")
    w = c[-(n + 1):]
    path = float(np.abs(np.diff(w)).sum())
    return 1.0 if path == 0 else float(abs(w[-1] - w[0]) / path)


def chop(h: np.ndarray, l_: np.ndarray, c: np.ndarray, n: int = 14) -> float:
    """Choppiness index(n) at the last bar (true range uses the previous close); NaN with fewer than n + 1 bars."""
    if len(c) < n + 1:
        return float("nan")
    hh, ll, cc = h[-n:], l_[-n:], c[-(n + 1):-1]
    tr = np.maximum.reduce([hh - ll, np.abs(hh - cc), np.abs(ll - cc)])
    rng = float(hh.max() - ll.min())
    if rng <= 0:
        return 100.0
    return float(100 * math.log10(tr.sum() / rng) / math.log10(n))


def twap_crossings(today: np.ndarray, s: int) -> int:
    """Sign changes of (5-minute close - running TWAP at that bar's last row), today's complete bars before s."""
    s = max(0, min(int(s), today.shape[1]))
    typ = (today[1, :s] + today[2, :s] + today[3, :s]) / 3.0
    ok = np.isfinite(typ)
    cs, cn = np.cumsum(np.where(ok, typ, 0.0)), np.cumsum(ok)
    sgn = []
    for a in range(0, s - 4, 5):
        e = a + 4
        if e >= s:
            break
        c = today[3, a:a + 5]
        if not np.isfinite(c).any() or cn[e] == 0:
            continue
        d = c[np.isfinite(c)][-1] - cs[e] / cn[e]
        if d != 0:
            sgn.append(d > 0)
    return int(sum(1 for x, y in zip(sgn, sgn[1:]) if x != y))


def day_range(day: np.ndarray, s: int | None = None) -> float:
    """max H - min L over rows < s (None: the whole day); NaN if no bar."""
    e = day.shape[1] if s is None else max(0, min(int(s), day.shape[1]))
    h, l_ = day[1, :e], day[2, :e]
    if not np.isfinite(h).any() or not np.isfinite(l_).any():
        return float("nan")
    return float(np.nanmax(h) - np.nanmin(l_))


def features(prev: list, today: np.ndarray, s: int) -> dict:
    """Every filter's value at signal bar s. prev: earlier sessions' (4, 375) bars, oldest first (up to 20 used)."""
    o, h, l_, c = series5(prev, today, s)
    r = day_range(today, s)
    p20 = prev[-N_ADR:]
    adr = float(np.mean([day_range(p) for p in p20])) if len(p20) == N_ADR else float("nan")
    tod = float(np.mean([day_range(p, s) for p in p20])) if len(p20) == N_ADR else float("nan")
    return dict(adx=adx(h, l_, c), er6=eff_ratio(c, 6), er12=eff_ratio(c, 12), tx=twap_crossings(today, s),
                rf=(r / adr if adr > 0 and np.isfinite(r) else float("nan")),
                rt=(r / tod if tod > 0 and np.isfinite(r) else float("nan")), chop=chop(h, l_, c))


def skip(variant: str, f: dict) -> bool:
    """True when the variant skips; a value that cannot be computed (NaN) never skips."""
    k, op, th = SPEC[variant]
    x = f.get(k)
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return False
    return {"<": x < th, ">": x > th, ">=": x >= th}[op]


# ================================================================================================ selection / stats
def choose(rows: dict) -> str | None:
    """rows: {variant: (gain H1, gain H2, gain whole window)} vs B. The eligible variant (gain > 0 in both halves) with
    the largest whole-window gain; ties: the earlier in VARIANTS; None if none is eligible."""
    best, best_gain = None, None
    for v in VARIANTS:
        if v not in rows:
            continue
        g1, g2, gt = rows[v]
        if g1 > 0 and g2 > 0 and (best_gain is None or gt > best_gain):
            best, best_gain = v, gt
    return best


def normal_p(actual: float, twins) -> float:
    """One-sided normal-tail p of z = (actual - twin mean) / twin sd (R177-EMA's BH p)."""
    x = np.asarray(twins, float)
    sd = float(x.std(ddof=1)) if len(x) > 1 else 0.0
    if sd <= 0:
        return 0.0 if actual > float(x.mean()) else 1.0
    z = (actual - float(x.mean())) / sd
    return float(0.5 * math.erfc(z / math.sqrt(2)))
