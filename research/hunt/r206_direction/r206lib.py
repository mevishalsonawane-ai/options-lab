"""R206 pure parts (PREREG.md): what "right" means, the with-the-day and with-the-market labels, the day-block bootstrap,
BH and the gate. No data and no engine here (test_r206.py). Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import math

import numpy as np

import r206cfg as K

N = K.NROWS


# ================================================================================================ prices and labels
def last_close(c: np.ndarray, r: int, after: int = -1) -> float:
    """The last known close at or before minute r and after minute `after`; NaN if none."""
    r = min(int(r), len(c) - 1)
    for i in range(r, int(after), -1):
        if i >= 0 and c[i] == c[i]:
            return float(c[i])
    return math.nan


def move(idx: np.ndarray, er: int, side: str, horizon: int) -> float:
    """PREREG 3: the index's move the signal's way from p0 (last close at or before er - 1) to the last known close at or
    before er + horizon - 1, as a fraction. NaN when p0 is unknown, the horizon runs past the session, or the index has
    no print between the entry minute and the horizon (nothing to judge)."""
    er = int(er)
    c = np.asarray(idx[3], float)
    end = er + horizon - 1
    if er < 1 or end > N - 1:
        return math.nan
    p0 = last_close(c, er - 1)
    p1 = last_close(c, end, after=er - 1)
    if not (p0 == p0 and p1 == p1) or p0 <= 0:
        return math.nan
    return (p1 / p0 - 1.0) * (1.0 if side == "C" else -1.0)


def with_day(idx: np.ndarray, er: int, side: str):
    """True when p0 is above the day's first known open for a call (below for a put), False when on the other side, None
    when unknown or equal. Reads minutes before the entry minute only."""
    er = int(er)
    o = np.asarray(idx[0], float)[:max(er, 0)]
    ok = np.flatnonzero(np.isfinite(o))
    p0 = last_close(np.asarray(idx[3], float), er - 1)
    if not len(ok) or not (p0 == p0) or p0 == o[ok[0]]:
        return None
    up = p0 > o[ok[0]]
    return bool(up if side == "C" else not up)


def ema(x: np.ndarray, n: int) -> np.ndarray:
    """EMA(n) carried over NaN (a missing bar keeps the last value); seeded with the first known value."""
    out = np.full(len(x), np.nan)
    a = 2.0 / (n + 1.0)
    e = math.nan
    for i, v in enumerate(np.asarray(x, float)):
        if v == v:
            e = v if e != e else e + a * (v - e)
        out[i] = e
    return out


def bars15_close(idx: np.ndarray) -> np.ndarray:
    """(25,) the last known close of each 15-minute bar of a day (NaN for a bar with no minute)."""
    c = np.asarray(idx[3], float)
    out = np.full(N // 15, np.nan)
    for j in range(N // 15):
        seg = c[15 * j:15 * j + 15]
        ok = np.flatnonzero(np.isfinite(seg))
        if len(ok):
            out[j] = seg[ok[-1]]
    return out


def with_market(c15: np.ndarray, e15: np.ndarray, day_start: int, er: int, side: str):
    """True when the market's last completed 15-minute close before the entry minute is on the trade's side of its EMA20
    (above for a call, below for a put), False on the other side, None when unknown or equal. c15 / e15: every session's
    15-minute closes and their EMA in one line; day_start = the index of this day's first bar. A bar is complete when its
    last minute is before the entry minute; before 09:30 that is the session before's last bar."""
    j = day_start + int(er) // 15 - 1
    while j >= 0 and not (c15[j] == c15[j]):
        j -= 1
    if j < 0 or not (e15[j] == e15[j]) or c15[j] == e15[j]:
        return None
    up = c15[j] > e15[j]
    return bool(up if side == "C" else not up)


def key(s: dict) -> tuple:
    """The same call from two sources: index, day, entry minute, side."""
    return (s["und"], s["day"], int(s["er"]), s["side"])


# ================================================================================================ statistics
def day_table(right: np.ndarray, in_a: np.ndarray, day_id: np.ndarray, n_days: int) -> np.ndarray:
    """(n_days, 4): per day, A right, A count, B right, B count."""
    t = np.zeros((n_days, 4))
    r = np.asarray(right, bool)
    a = np.asarray(in_a, bool)
    d = np.asarray(day_id, int)
    np.add.at(t[:, 0], d[a], r[a])
    np.add.at(t[:, 1], d[a], 1.0)
    np.add.at(t[:, 2], d[~a], r[~a])
    np.add.at(t[:, 3], d[~a], 1.0)
    return t


def diff(t: np.ndarray) -> float:
    s = t.sum(0)
    if s[1] <= 0 or s[3] <= 0:
        return math.nan
    return float(s[0] / s[1] - s[2] / s[3])


def boot_diff(t: np.ndarray, n: int, rng) -> dict:
    """Day-block bootstrap of d = share right in A - share right in B: whole days resampled with replacement. p = the
    share of draws with d <= 0 (one-sided); p_normal = the normal tail of d against the bootstrap's standard error."""
    d0 = diff(t)
    days = len(t)
    if not (d0 == d0) or days < 2:
        return dict(d=None, p=1.0, se=None, p_normal=1.0)
    out = np.empty(n)
    for i in range(n):
        s = t[rng.integers(0, days, days)].sum(0)
        out[i] = s[0] / s[1] - s[2] / s[3] if s[1] > 0 and s[3] > 0 else np.nan
    ok = out[np.isfinite(out)]
    se = float(ok.std(ddof=1)) if len(ok) > 1 else 0.0
    p = float((1 + int((ok <= 0).sum())) / (len(ok) + 1))
    pn = float(0.5 * math.erfc((d0 / se) / math.sqrt(2.0))) if se > 0 else (0.0 if d0 > 0 else 1.0)
    return dict(d=d0, p=p, se=se, p_normal=pn)


def boot_rate(right: np.ndarray, day_id: np.ndarray, n_days: int, n: int, rng) -> dict:
    """The share right with a 95% interval from a day-block bootstrap."""
    r = np.asarray(right, bool)
    if not len(r):
        return dict(n=0, rate=None, lo=None, hi=None)
    t = np.zeros((n_days, 2))
    np.add.at(t[:, 0], np.asarray(day_id, int), r)
    np.add.at(t[:, 1], np.asarray(day_id, int), 1.0)
    out = np.empty(n)
    for i in range(n):
        s = t[rng.integers(0, n_days, n_days)].sum(0)
        out[i] = s[0] / s[1] if s[1] > 0 else np.nan
    ok = out[np.isfinite(out)]
    return dict(n=int(len(r)), rate=float(r.mean()), lo=float(np.percentile(ok, 2.5)), hi=float(np.percentile(ok, 97.5)))


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


def gate(old: dict, years: dict, recent: dict, q) -> dict:
    """PREREG 6. old / recent: dict(d, p, n_a, n_b, rate_a, rate_src); years: {year: d}. Returns more_reliable + fails."""
    fails = []
    if old["d"] is None or not (old["d"] > 0 and old["p"] < K.GATE_P):
        fails.append(f"OLD: difference {fmt(old['d'])} with p {old['p']:.4f} (needs above 0 with p < {K.GATE_P})")
    if q is None or not q <= K.GATE_Q:
        fails.append("BH q " + ("n/a" if q is None else f"{q:.3g}") + f" > {K.GATE_Q}")
    up = sum(1 for d in years.values() if d is not None and d > 0)
    if up < K.YEARS_NEEDED:
        fails.append(f"above 0 in {up} of {len(years)} OLD years < {K.YEARS_NEEDED}")
    if recent["d"] is None or not recent["d"] > 0:
        fails.append(f"RECENT: difference {fmt(recent['d'])} not above 0")
    if min(old["n_a"], old["n_b"]) < K.MIN_N:
        fails.append(f"OLD: {old['n_a']} / {old['n_b']} signals in A / B < {K.MIN_N}")
    edge = None if old["rate_a"] is None or old["rate_src"] is None else old["rate_a"] - old["rate_src"]
    if edge is None or edge < K.MIN_EDGE - 1e-12:
        fails.append(f"OLD: A is {fmt(edge)} above the source's own share right (needs {K.MIN_EDGE * 100:.0f} points)")
    return dict(more_reliable=not fails, fails=fails, years_up=up, edge=edge, q=q)


def fmt(x) -> str:
    return "n/a" if x is None else f"{x * 100:+.1f} points"
