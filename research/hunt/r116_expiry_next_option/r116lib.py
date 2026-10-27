"""R116 pure helpers (no lake, no stores): the expiry calendar logic, the minute grid of one contract, statistics,
twins' p, Benjamini-Hochberg, the gates and the 2% risk-cap arithmetic. 27 Oct 2026."""
from __future__ import annotations

import math
from bisect import bisect_left, bisect_right
from collections import defaultdict
from datetime import date

import numpy as np

import r116cfg as K

NROWS = 375                     # 09:15 .. 15:29


# ------------------------------------------------------------------------------------------------ the expiry calendar
def next_after(days: list, d: date) -> date | None:
    """The first day in the sorted list strictly after d (None if none)."""
    i = bisect_right(days, d)
    return days[i] if i < len(days) else None


def first_on_or_after(days: list, d: date) -> date | None:
    i = bisect_left(days, d)
    return days[i] if i < len(days) else None


def classify_month(d: date, week_exp: list, month_exp: list) -> tuple:
    """For a WEEK-series expiry day d of a weekly index, what the lake's MONTH series holds that day:
    ('same', d) when the monthly expires that day too (no next-expiry prints), ('exact', m) when the monthly is the very
    next weekly expiry, ('standin', m) when it is a later one, ('unknown', None) when the calendar does not reach it.
    week_exp / month_exp: sorted expiry days of each series."""
    m = first_on_or_after(month_exp, d)
    if m is None:
        return ("unknown", None)
    if m == d:
        return ("same", d)
    w = next_after(week_exp, d)
    if w is None:
        return ("unknown", m)
    if m == w:
        return ("exact", m)
    if m > w:
        return ("standin", m)
    return ("unknown", m)            # a monthly before the next weekly: the calendars disagree, never guessed


def archive_ok(d: date, stored: date, next_exp: date | None, weekly: bool) -> bool:
    """Is the archive's stored expiry on expiry day d the NEXT expiry? It must be after d and equal the next expiry of
    the store's calendar; past the calendar's end, within 8 (weekly) / 40 (monthly) days."""
    if stored is None or stored <= d:
        return False
    if next_exp is not None:
        return stored == next_exp
    return (stored - d).days <= (8 if weekly else 40)


# ------------------------------------------------------------------------------------------------ one contract's grid
def grid(r: np.ndarray, cols: list) -> np.ndarray:
    """(len(cols), 375) float32 minute grid of one contract from its rows (minute row r, one array per column);
    NaN where no print; the first row of a duplicated minute is kept (as r51data)."""
    out = np.full((len(cols), NROWS), np.nan, dtype=np.float32)
    r = np.asarray(r, np.int64)
    ok = (r >= 0) & (r < NROWS)
    _, first = np.unique(r[ok], return_index=True)
    rr = r[ok][first]
    for i, c in enumerate(cols):
        out[i, rr] = np.asarray(c, np.float64)[ok][first]
    return out


def minute_row(hh: np.ndarray, mm: np.ndarray) -> np.ndarray:
    return hh * 60 + mm - (9 * 60 + 15)


# ------------------------------------------------------------------------------------------------ statistics
def stats(trades: list) -> dict:
    """Trades, days, win rate, net, average, worst day, max drawdown (daily, from zero), months at or below -Rs 13,000."""
    n = len(trades)
    if not n:
        return dict(trades=0, days=0, win=None, net=0, avg=None, worst_day=0, max_dd=0, bad_months=0)
    nets = np.array([float(t["net"]) for t in trades])
    daily, monthly = defaultdict(float), defaultdict(float)
    for t in trades:
        daily[t["day"]] += float(t["net"])
        monthly[(t["day"].year, t["day"].month)] += float(t["net"])
    seq = np.array([daily[d] for d in sorted(daily)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    return dict(trades=n, days=len(daily), win=round(float((nets > 0).mean()), 4), net=round(float(nets.sum())),
                avg=round(float(nets.mean())), worst_day=round(float(seq.min())),
                max_dd=round(float((np.maximum.accumulate(eq) - eq).max())),
                bad_months=int(sum(1 for v in monthly.values() if v <= K.MONTH_LIMIT)))


def open_slice(trades: list) -> list:
    """Trades filled 09:15-09:30 (fill row < 15)."""
    return [t for t in trades if int(t["er"]) < K.OPEN_SLICE_ROW]


def twin_p(actual: float, twins) -> dict:
    """Monte-Carlo p = (1 + twins >= actual) / (n + 1); when no twin reaches it, the normal-tail p (as R115)."""
    t = np.asarray(twins, float)
    n = len(t)
    if not n:
        return dict(p=1.0, p_mc=1.0, p_norm=1.0, twin_mean=None, twin_sd=None, hits=0)
    hits = int((t >= actual - 1e-9).sum())
    p_mc = (1 + hits) / (n + 1)
    sd = float(t.std(ddof=1)) if n > 1 else 0.0
    if sd > 0:
        p_norm = 0.5 * math.erfc((actual - float(t.mean())) / sd / math.sqrt(2.0))
    else:
        p_norm = 0.0 if actual > float(t.mean()) else 1.0
    p = p_mc if hits > 0 else min(p_mc, p_norm)
    return dict(p=float(p), p_mc=float(p_mc), p_norm=float(p_norm), twin_mean=round(float(t.mean())), twin_sd=round(sd),
                hits=hits)


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q of the given p-values within m_total tests (the rest taken as p = 1)."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(n, int(m_total or n))
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return q


def gates(per: dict, q_all: float) -> dict:
    """per: {period: stats} for design, wf, final of the world's expiry-day trades. PREREG section 5: positive net in
    every period that has data (against skipping = Rs 0), at least MIN_FINAL final trades, BH q <= GATE_Q over all tests.
    'decidable' is False when the final has too few trades to decide either way."""
    fails = []
    for p in ("design", "wf", "final"):
        s = per.get(p) or {}
        if s.get("trades", 0) and s["net"] <= 0:
            fails.append(f"{p}: net not above skipping (Rs {s['net']:,})")
        if not s.get("trades", 0):
            fails.append(f"{p}: no trades (no data)")
    n_final = (per.get("final") or {}).get("trades", 0)
    if n_final < K.MIN_FINAL:
        fails.append(f"only {n_final} final trades (< {K.MIN_FINAL})")
    if q_all > K.GATE_Q:
        fails.append(f"BH q {q_all:.3g} > {K.GATE_Q}")
    return dict(passed=not fails, fails=fails, decidable=n_final >= K.MIN_FINAL)


# ------------------------------------------------------------------------------------------------ the 2% risk cap
def risk_per_lot(entry: float, lot: int, stop_share: float = K.STOP_SHARE) -> float:
    """BossRisk.riskCap's rupees at risk for one lot: lot x entry x stop share + the round trip's charges."""
    return float(lot) * float(entry) * float(stop_share) + K.CHARGES_PER_LOT


def account_needed(entry: float, lot: int, stop_share: float = K.STOP_SHARE) -> float:
    """The smallest account on which one lot passes the 2% cap."""
    return risk_per_lot(entry, lot, stop_share) / K.RISK_PCT
