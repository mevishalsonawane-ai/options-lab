"""R207 / R208 pure parts (PREREG.md): the expected-move features, the walk-forward quantile gate, the late-entry filter,
the day-block bootstraps and the gates. No data and no engine here (test_r207.py). Every feature reads minutes before the
entry minute only. Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import math

import numpy as np

import r207cfg as K

N = K.NROWS


# ================================================================================================ features (R207)
def activity(idx: np.ndarray, er: int) -> float:
    """A(d, e): the sum of (high - low) of the day's complete 5-minute bars that end before minute e. NaN if none has a
    minute."""
    nb = max(int(er), 0) // 5
    h, l = np.asarray(idx[1], float), np.asarray(idx[2], float)
    tot, seen = 0.0, False
    for k in range(nb):
        hs, ls = h[5 * k:5 * k + 5], l[5 * k:5 * k + 5]
        if np.isfinite(hs).any() and np.isfinite(ls).any():
            tot += float(np.nanmax(hs) - np.nanmin(ls))
            seen = True
    return tot if seen else math.nan


def fwd_range(idx: np.ndarray, r: int, minutes: int = K.FWD_MIN) -> float:
    """Highest high - lowest low of minutes r .. r + minutes - 1 (to the session's last minute if sooner). NaN if none."""
    r = int(r)
    if r < 0 or r >= N:
        return math.nan
    h, l = np.asarray(idx[1], float)[r:min(r + minutes, N)], np.asarray(idx[2], float)[r:min(r + minutes, N)]
    if not (np.isfinite(h).any() and np.isfinite(l).any()):
        return math.nan
    return float(np.nanmax(h) - np.nanmin(l))


def range_so_far(idx: np.ndarray, er: int) -> float:
    """Highest high - lowest low of the minutes before e. NaN if none."""
    er = int(er)
    if er <= 0:
        return math.nan
    h, l = np.asarray(idx[1], float)[:er], np.asarray(idx[2], float)[:er]
    if not (np.isfinite(h).any() and np.isfinite(l).any()):
        return math.nan
    return float(np.nanmax(h) - np.nanmin(l))


def last_close(idx: np.ndarray, er: int) -> float:
    c = np.asarray(idx[3], float)[:max(int(er), 0)]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if len(ok) else math.nan


def features(today: np.ndarray, prev: list, er: int) -> dict:
    """PREREG 2: ratio (today's activity so far against the usual), x (the expected move over the next 30 minutes, a
    share of the index) and rt (the range so far against the usual). `prev` = the previous sessions' index days, the
    latest PREV_N of them. Unknown = NaN (never skips)."""
    prev = list(prev)[-K.PREV_N:]
    out = dict(ratio=math.nan, x=math.nan, rt=math.nan)
    a = activity(today, er)
    acts = [v for v in (activity(p, er) for p in prev) if v == v]
    fwds = [v for v in (fwd_range(p, er) for p in prev) if v == v]
    p0 = last_close(today, er)
    if a == a and len(acts) >= K.PREV_MIN and np.mean(acts) > 0:
        out["ratio"] = a / float(np.mean(acts))
        if len(fwds) >= K.PREV_MIN and p0 == p0 and p0 > 0:
            out["x"] = out["ratio"] * float(np.mean(fwds)) / p0
    rs = range_so_far(today, er)
    prs = [v for v in (range_so_far(p, er) for p in prev) if v == v]
    if rs == rs and len(prev) == K.PREV_N and len(prs) == K.PREV_N and np.mean(prs) > 0:
        out["rt"] = rs / float(np.mean(prs))
    return out


def wf_keep(days: list, xs: list, q: float, sessions: int = K.WF_SESSIONS, min_n: int = K.WF_MIN_TRADES) -> np.ndarray:
    """Keep-mask of the walk-forward quantile gate. For a trade on day d: the reference = the known x of the trades on the
    previous `sessions` distinct trade days before d; with fewer than min_n of them, or an unknown x, the trade is kept;
    else it is skipped when x is below the reference's q-quantile. A trade never reads its own day or a later one."""
    xs = np.asarray(xs, float)
    order = sorted(set(days))
    by_day = {d: [] for d in order}
    for d, x in zip(days, xs):
        if x == x:
            by_day[d].append(float(x))
    thr = {}
    for i, d in enumerate(order):
        ref = [v for dd in order[max(0, i - sessions):i] for v in by_day[dd]]
        thr[d] = float(np.quantile(ref, q)) if len(ref) >= min_n else math.nan
    keep = np.ones(len(xs), bool)
    for i, (d, x) in enumerate(zip(days, xs)):
        t = thr[d]
        keep[i] = not (x == x and t == t and x < t)
    return keep


def rt_keep(rts: list, cut: float = K.RT_CUT) -> np.ndarray:
    r = np.asarray(rts, float)
    with np.errstate(invalid="ignore"):
        return ~(r < cut)


def _ranks(v: np.ndarray) -> np.ndarray:
    """Average ranks (ties share their mean rank)."""
    order = np.argsort(v, kind="mergesort")
    r = np.empty(len(v))
    i = 0
    while i < len(v):
        j = i
        while j + 1 < len(v) and v[order[j + 1]] == v[order[i]]:
            j += 1
        r[order[i:j + 1]] = (i + j) / 2.0
        i = j + 1
    return r


def spearman(x, y) -> float:
    """Rank correlation (ties share their mean rank); NaN with fewer than 3 known pairs or a constant side."""
    x, y = np.asarray(x, float), np.asarray(y, float)
    ok = np.isfinite(x) & np.isfinite(y)
    if ok.sum() < 3:
        return math.nan
    rx, ry = _ranks(x[ok]), _ranks(y[ok])
    if rx.std() == 0 or ry.std() == 0:
        return math.nan
    return float(np.corrcoef(rx, ry)[0, 1])


# ================================================================================================ late entries (R208)
def is_late(er: int) -> bool:
    return K.LATE_FIRST <= int(er) <= K.LATE_LAST


# ================================================================================================ bootstraps and gates
def _normal_tail(z: float) -> float:
    return float(0.5 * math.erfc(z / math.sqrt(2.0)))


def boot_mean_pos(day_values, n: int, rng) -> dict:
    """Is the mean daily value above 0? Days resampled with replacement; p = the share of draws with a mean <= 0."""
    v = np.asarray(day_values, float)
    if len(v) < 2:
        return dict(days=int(len(v)), mean=float(v.mean()) if len(v) else None, p=1.0, se=None, p_normal=1.0)
    m = v[rng.integers(0, len(v), (n, len(v)))].mean(1)
    se = float(m.std(ddof=1))
    mean = float(v.mean())
    return dict(days=int(len(v)), mean=mean, p=float((1 + int((m <= 0).sum())) / (n + 1)), se=se,
                p_normal=_normal_tail(mean / se) if se > 0 else (0.0 if mean > 0 else 1.0))


def boot_rate_above(right, day_id, n: int, rng, level: float = K.LEVEL) -> dict:
    """Is the share right above `level`? Whole days resampled; p = the share of draws with a share right <= level."""
    r = np.asarray(right, bool)
    d = np.asarray(day_id, int)
    if not len(r):
        return dict(n=0, days=0, rate=None, p=1.0, se=None, p_normal=1.0, lo=None, hi=None)
    days = int(d.max()) + 1
    t = np.zeros((days, 2))
    np.add.at(t[:, 0], d, r)
    np.add.at(t[:, 1], d, 1.0)
    t = t[t[:, 1] > 0]
    if len(t) < 2:
        return dict(n=int(len(r)), days=int(len(t)), rate=float(r.mean()), p=1.0, se=None, p_normal=1.0, lo=None, hi=None)
    idx = rng.integers(0, len(t), (n, len(t)))
    s = t[idx].sum(1)
    out = s[:, 0] / s[:, 1]
    se = float(out.std(ddof=1))
    rate = float(r.mean())
    return dict(n=int(len(r)), days=int(len(t)), rate=rate, p=float((1 + int((out <= level).sum())) / (n + 1)), se=se,
                p_normal=_normal_tail((rate - level) / se) if se > 0 else (0.0 if rate > level else 1.0),
                lo=float(np.percentile(out, 2.5)), hi=float(np.percentile(out, 97.5)))


def bh_total(p, m_total: int = K.M_TOTAL) -> list:
    """Benjamini-Hochberg q with m_total tests; the earlier entries count as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in np.minimum(q, 1.0)]


def gate_late(win: dict, folds: list, p, q) -> dict:
    """PREREG 3b: a standalone rule. win: {design, holdout: stats}; folds: the four design quarters' stats."""
    fails = []
    for w in ("design", "holdout"):
        if not win[w]["net"] > 0:
            fails.append(f"{w}: net {win[w]['net']:,} not above 0")
        if win[w]["trades"] < K.MIN_TRADES:
            fails.append(f"{w}: {win[w]['trades']} trades < {K.MIN_TRADES}")
    up = sum(1 for f in folds if f["net"] > 0)
    if up < K.FOLDS_NEEDED:
        fails.append(f"above 0 in {up} of {len(folds)} design quarters < {K.FOLDS_NEEDED}")
    if p is None or not p < K.GATE_P:
        fails.append("holdout bootstrap p " + ("n/a" if p is None else f"{p:.4f}") + f" >= {K.GATE_P}")
    if q is None or not q <= K.GATE_Q:
        fails.append("BH q " + ("n/a" if q is None else f"{q:.3g}") + f" > {K.GATE_Q}")
    return dict(passes=not fails, fails=fails, folds_up=up, p=p, q=q)


def gate_index(r: dict, q) -> dict:
    """PREREG 3a: the share right above 50% with p < 0.05, BH, and at least MIN_SIGNALS signals."""
    if r["n"] < K.MIN_SIGNALS:
        return dict(holds=False, verdict="too few to judge", fails=[f"{r['n']} signals < {K.MIN_SIGNALS}"], q=q)
    fails = []
    if not (r["rate"] is not None and r["rate"] > K.LEVEL and r["p"] < K.GATE_P):
        fails.append(f"share right {r['rate'] * 100:.1f}% with p {r['p']:.4f} (needs above 50% with p < {K.GATE_P})")
    if q is None or not q <= K.GATE_Q:
        fails.append("BH q " + ("n/a" if q is None else f"{q:.3g}") + f" > {K.GATE_Q}")
    return dict(holds=not fails, verdict="holds" if not fails else "does not hold", fails=fails, q=q)
