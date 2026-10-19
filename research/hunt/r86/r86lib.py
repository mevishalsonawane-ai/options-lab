"""R86 library: pure functions (no file or network access). Pre-registration: research/hunt/r86/PREREG.md.

Signals, roll handling, trade simulators, costs and statistics for the big-players footprint tests. Every signal
function reads only rows up to its decision time (test_r86.py checks this with a look-ahead guard).
"""
from __future__ import annotations

import math
from datetime import date, timedelta

import numpy as np
import pandas as pd

# ------------------------------------------------------------------------------------------------ rolling helpers


def pct_rank(x: pd.Series, window: int, min_obs: int) -> pd.Series:
    """Rank of today's value among the last `window` values including today, in (0, 1].

    0.5-tie convention: share of values strictly below + half of the ties (today counts as one tie). NaN with fewer
    than `min_obs` non-missing values in the window or a missing value today.
    """
    v = x.to_numpy(float)
    out = np.full(len(v), np.nan)
    for i in range(len(v)):
        if np.isnan(v[i]):
            continue
        w = v[max(0, i - window + 1): i + 1]
        w = w[~np.isnan(w)]
        if len(w) < min_obs:
            continue
        out[i] = (np.sum(w < v[i]) + 0.5 * np.sum(w == v[i])) / len(w)
    return pd.Series(out, index=x.index)


def roll_z(x: pd.Series, window: int, min_obs: int) -> pd.Series:
    """z of today's value against the last `window` values including today (mean and sample std of that window)."""
    m = x.rolling(window, min_periods=min_obs).mean()
    s = x.rolling(window, min_periods=min_obs).std()
    z = (x - m) / s
    return z.where(s > 0)


def wilder_atr(h: np.ndarray, l: np.ndarray, c: np.ndarray, n: int = 14) -> np.ndarray:
    """Wilder ATR; value at i uses bars <= i. NaN until n true ranges exist."""
    h, l, c = (np.asarray(a, float) for a in (h, l, c))
    pc = np.concatenate([[np.nan], c[:-1]])
    tr = np.nanmax(np.vstack([h - l, np.abs(h - pc), np.abs(l - pc)]), axis=0)
    tr[0] = h[0] - l[0]
    out = np.full(len(c), np.nan)
    if len(c) < n:
        return out
    out[n - 1] = np.mean(tr[:n])
    for i in range(n, len(c)):
        out[i] = (out[i - 1] * (n - 1) + tr[i]) / n
    return out


# ------------------------------------------------------------------------------------------------ participants


def participant_features(p: pd.DataFrame) -> pd.DataFrame:
    """p: one row per date (index sorted) with FII and Client contract columns -> R86 P-family signals.

    Columns needed: fii_fut_idx_long, fii_fut_idx_short, fii_idx_call_long, fii_idx_call_short, fii_idx_put_long,
    fii_idx_put_short, cl_idx_call_long, cl_idx_call_short, cl_idx_put_long, cl_idx_put_short.
    """
    f = pd.DataFrame(index=p.index)
    tot = p.fii_fut_idx_long + p.fii_fut_idx_short
    f["L"] = (p.fii_fut_idx_long / tot).where(tot > 0)
    s4 = p.fii_idx_call_long + p.fii_idx_call_short + p.fii_idx_put_long + p.fii_idx_put_short
    f["O"] = ((p.fii_idx_call_long - p.fii_idx_call_short - p.fii_idx_put_long + p.fii_idx_put_short) / s4).where(s4 > 0)
    c4 = p.cl_idx_call_long + p.cl_idx_call_short + p.cl_idx_put_long + p.cl_idx_put_short
    f["W"] = ((p.cl_idx_call_short + p.cl_idx_put_short - p.cl_idx_call_long - p.cl_idx_put_long) / c4).where(c4 > 0)
    f["P1"] = pct_rank(f.L, 250, 200)
    f["P2"] = roll_z(f.L - f.L.shift(5), 250, 200)
    f["P3"] = pct_rank(f.O, 250, 200)
    f["P4"] = pct_rank(f.W, 250, 200)
    f["P5"] = (f.P1 - 0.5).abs()
    return f


# ------------------------------------------------------------------------------------------------ COT


SHUTDOWNS = [(date(2013, 9, 24), date(2013, 10, 29)), (date(2018, 12, 18), date(2019, 3, 5)),
             (date(2025, 9, 23), date(2026, 1, 31))]


def cot_usable(report: date) -> bool:
    """False for report dates inside a US shutdown or its catch-up (PREREG section 1)."""
    return not any(a <= report <= b for a, b in SHUTDOWNS)


def cot_entry_index(report: date, sessions: list[date]) -> int | None:
    """Index of the first trading session on or after report + 7 days (None if none)."""
    first = report + timedelta(days=7)
    i = int(np.searchsorted(np.array(sessions, dtype="datetime64[D]"), np.datetime64(first, "D"), side="left"))
    return i if i < len(sessions) else None


def cot_features(c: pd.DataFrame) -> pd.DataFrame:
    """c: weekly rows (sorted by report date) with mm_long, mm_short, oi -> N, C1 (pct over 156), C2 (z over 156)."""
    f = pd.DataFrame(index=c.index)
    f["N"] = ((c.mm_long - c.mm_short) / c.oi).where(c.oi > 0)
    f["C1"] = pct_rank(f.N, 156, 104)
    f["C2"] = roll_z(f.N - f.N.shift(1), 156, 104)
    return f


# ------------------------------------------------------------------------------------------------ MCX roll


def detect_rolls(oi: np.ndarray, ratio: float = 1.5, gap: int = 15) -> np.ndarray:
    """Indices of roll days: OI >= ratio x previous day's, and >= gap sessions after the last roll."""
    oi = np.asarray(oi, float)
    out, last = [], -10 ** 9
    for i in range(1, len(oi)):
        if oi[i - 1] > 0 and oi[i] >= ratio * oi[i - 1] and i - last >= gap:
            out.append(i)
            last = i
    return np.array(out, dtype=int)


def back_adjust(o, h, l, c, rolls) -> tuple[np.ndarray, ...]:
    """Ratio back-adjustment that removes each roll day's overnight gap (previous close -> roll-day open).

    After it, the roll day's adjusted return close(k-1) -> close(k) equals its own open -> close. Prices after the
    last roll are unchanged.
    """
    o, h, l, c = (np.asarray(a, float).copy() for a in (o, h, l, c))
    f = np.ones(len(c))
    for k in sorted(rolls, reverse=True):
        if k <= 0:
            continue
        ratio = o[k] / c[k - 1]
        f[:k] *= ratio
    return o * f, h * f, l * f, c * f


# ------------------------------------------------------------------------------------------------ costs


def lot_on(schedule: list[tuple[str, int]], d) -> int:
    d = pd.Timestamp(d).strftime("%Y-%m-%d")
    lot = schedule[0][1]
    for start, n in schedule:
        if d >= start:
            lot = n
    return lot


def nse_fut_stt(d) -> float:
    d = pd.Timestamp(d)
    if d < pd.Timestamp("2023-04-01"):
        return 0.0001
    if d < pd.Timestamp("2024-10-01"):
        return 0.000125
    return 0.0002


FUT_SLIP, STOP_SLIP_NSE, CARRY = 0.0002, 0.0004, 0.055


def nse_fut_cost(side: int, entry: float, exit_: float, units: int, d_in, days_held: float, stop: bool) -> float:
    """Rupee cost of one NSE index futures round trip (PREREG section 3): charges + slippage + carry.

    Carry: 5.5% a year of the entry value; longs pay it, shorts receive it (a negative cost).
    """
    v_in, v_out = entry * units, exit_ * units
    sell_v = v_out if side > 0 else v_in
    buy_v = v_in if side > 0 else v_out
    brok = 40.0
    exch = 0.0000173 * (v_in + v_out)
    sebi = 10.0 / 1e7 * (v_in + v_out)
    stt = nse_fut_stt(d_in) * sell_v
    stamp = 0.00002 * buy_v
    gst = 0.18 * (brok + exch + sebi)
    slip = FUT_SLIP * v_in + (STOP_SLIP_NSE if stop else FUT_SLIP) * v_out
    carry = side * CARRY * v_in * days_held / 365.0
    return brok + exch + sebi + stt + stamp + gst + slip + carry


# McxCosts.kt (as research/hunt/r82/r82lib.py)
_BROK_CAP, _BROK_RATE, _CTT, _TXN, _SEBI, _STAMP, _GST = 20.0, 0.0003, 0.0001, 0.000021, 10.0 / 1e7, 0.00002, 0.18
MCX_SLIP, MCX_STOP_SLIP = 0.0003, 0.0010


def mcx_leg(buy: bool, turnover: float) -> float:
    if not turnover > 0:
        return 0.0
    b = min(_BROK_CAP, turnover * _BROK_RATE)
    ctt = 0.0 if buy else turnover * _CTT
    txn, sebi = turnover * _TXN, turnover * _SEBI
    stamp = turnover * _STAMP if buy else 0.0
    return b + ctt + txn + sebi + stamp + (b + txn + sebi) * _GST


def mcx_cost(side: int, entry: float, exit_: float, mult: float, stop: bool) -> float:
    v_in, v_out = entry * mult, exit_ * mult
    ch = mcx_leg(side > 0, v_in) + mcx_leg(side < 0, v_out)
    return ch + MCX_SLIP * v_in + (MCX_STOP_SLIP if stop else MCX_SLIP) * v_out


# ------------------------------------------------------------------------------------------------ daily trades


def sim_daily(o, h, l, c, i: int, hold: int, side: int, stop_dist: float):
    """Enter at open[i], exit at close[i + hold - 1] unless the stop (entry -+ stop_dist) is hit first.

    Day i: the stop can be hit intraday (no gap: the entry is that open). Later days: an open beyond the stop fills at
    that open; else a low/high touching the stop fills at the stop. Returns (exit_index, exit_price, stopped) or None
    when the window runs past the data.
    """
    if i + hold - 1 >= len(c) or not (stop_dist > 0):
        return None
    e = o[i]
    st = e - side * stop_dist
    for j in range(i, i + hold):
        if j > i and (o[j] - st) * side <= 0:
            return j, o[j], True
        if (l[j] <= st) if side > 0 else (h[j] >= st):
            return j, st, True
    return i + hold - 1, c[i + hold - 1], False


def walk_sign(signal: np.ndarray, fwd: np.ndarray, centre: float) -> int:
    """Sign of the Spearman correlation of (signal - centre) with the forward return (+1 when 0 or undefined)."""
    m = ~(np.isnan(signal) | np.isnan(fwd))
    if m.sum() < 10:
        return 1
    a = pd.Series(signal[m] - centre).rank().to_numpy()
    b = pd.Series(fwd[m]).rank().to_numpy()
    r = np.corrcoef(a, b)[0, 1]
    return -1 if r < 0 else 1


# ------------------------------------------------------------------------------------------------ minute trades


def sim_minutes(o, h, l, c, e: int, last: int, side: int, stop: float, target: float | None):
    """Enter at open[e]; stop first when both stop and target fall in one minute; a minute opening beyond the stop
    fills at that open (or beyond the target: at the target). Exit at close[last] otherwise. Returns (exit_i, px,
    kind) kind in {'stop', 'target', 'time'}."""
    for j in range(e, last + 1):
        if j > e and (o[j] - stop) * side <= 0:
            return j, o[j], "stop"
        if (l[j] <= stop) if side > 0 else (h[j] >= stop):
            return j, stop, "stop"
        if target is not None:
            if j > e and (o[j] - target) * side >= 0:
                return j, target, "target"
            if (h[j] >= target) if side > 0 else (l[j] <= target):
                return j, target, "target"
    return last, c[last], "time"


def stop_run_events(mins: np.ndarray, o, h, l, c, levels: list[float], first: int = 570, last_poke: int = 870,
                    delta: float = 0.0003, within: int = 10) -> list[dict]:
    """Failed pokes ("stop runs") at the given levels on one day of 1-minute bars (PREREG section 3, family S).

    mins: minute of day (09:15 = 555). For each level, pokes are scanned in time order; a poke is a minute in
    [first, last_poke] whose high >= L(1+delta) with the previous close < L (upper) or low <= L(1-delta) with the
    previous close > L (lower). It fails when a close comes back across L within `within` minutes (poke minute
    included). The first failed poke of each level is an event at that close (index k); side = the fade direction.
    """
    ev = []
    n = len(c)
    for L in levels:
        if not np.isfinite(L):
            continue
        for m in range(1, n):
            if not (first <= mins[m] <= last_poke):
                continue
            up = h[m] >= L * (1 + delta) and c[m - 1] < L
            dn = l[m] <= L * (1 - delta) and c[m - 1] > L
            if not (up or dn):
                continue
            side = -1 if up else 1
            k_end = min(n - 1, m + within - 1)
            for k in range(m, k_end + 1):
                if (c[k] < L) if up else (c[k] > L):
                    ext = h[m:k + 1].max() if up else l[m:k + 1].min()
                    ev.append(dict(level=L, poke=m, k=k, side=side, extreme=float(ext)))
                    break
            else:
                continue
            break
    return ev


# ------------------------------------------------------------------------------------------------ VWAP


def vwap(typ: np.ndarray, w: np.ndarray) -> np.ndarray:
    """Cumulative VWAP from the first row; minutes with no weight carry the last value (NaN before any weight)."""
    w = np.nan_to_num(np.asarray(w, float))
    num = np.cumsum(np.nan_to_num(typ) * w)
    den = np.cumsum(w)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(den > 0, num / den, np.nan)


# ------------------------------------------------------------------------------------------------ statistics


def bh(p: list[float]) -> list[float]:
    """Benjamini-Hochberg q values (NaN p stays NaN and is not counted)."""
    p = np.asarray(p, float)
    ok = ~np.isnan(p)
    q = np.full(len(p), np.nan)
    pv = p[ok]
    m = len(pv)
    if m == 0:
        return list(q)
    order = np.argsort(pv)
    ranked = pv[order] * m / (np.arange(m) + 1)
    ranked = np.minimum.accumulate(ranked[::-1])[::-1]
    qq = np.empty(m)
    qq[order] = np.minimum(ranked, 1.0)
    q[ok] = qq
    return list(q)


def hac_mean_t(d: np.ndarray, lag: int) -> tuple[float, float]:
    """Mean of d and its Newey-West t statistic (Bartlett weights)."""
    d = np.asarray(d, float)
    d = d[~np.isnan(d)]
    n = len(d)
    if n < 3:
        return float("nan"), float("nan")
    mu = d.mean()
    x = d - mu
    v = np.dot(x, x) / n
    for k in range(1, lag + 1):
        v += 2 * (1 - k / (lag + 1)) * np.dot(x[k:], x[:-k]) / n
    se = math.sqrt(max(v, 1e-300) / n)
    return float(mu), float(mu / se)


def one_sided_p(t: float) -> float:
    if not np.isfinite(t):
        return float("nan")
    return 0.5 * math.erfc(t / math.sqrt(2))


def twin_p(stat: float, twins: np.ndarray) -> float:
    twins = np.asarray(twins, float)
    return float((1 + np.sum(twins >= stat)) / (len(twins) + 1))


def ols_fit(X: np.ndarray, y: np.ndarray) -> np.ndarray:
    X1 = np.column_stack([np.ones(len(X)), X])
    beta, *_ = np.linalg.lstsq(X1, y, rcond=None)
    return beta


def ols_pred(beta: np.ndarray, X: np.ndarray) -> np.ndarray:
    return np.column_stack([np.ones(len(X)), X]) @ beta


def without_top(x, k: int = 5) -> float:
    x = np.sort(np.asarray(x, float))
    return float(x[:-k].sum()) if len(x) > k else 0.0
