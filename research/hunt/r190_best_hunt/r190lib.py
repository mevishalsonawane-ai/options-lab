"""R190 pure pieces (no data): the new rules' signals, the overlay, sizing, the month cap, statistics, twins, BH and the
gate. Every function here is tested in test_r190.py. Plain-English notes, 17 Nov 2026.

Index arrays are (375, 4) O H L C minutes of one session (row r = 09:15 + r minutes), NaN where there is no minute.
A decision at row t reads only rows < t (the minute before the order); `blank_after` enforces it.
"""
from __future__ import annotations

import math
from collections import defaultdict

import numpy as np

import r190cfg as K

O, H, L, C = 0, 1, 2, 3


# ================================================================================================ helpers
def blank_after(a: np.ndarray, t: int) -> np.ndarray:
    """A copy of the day with every row >= t blanked: the look-ahead wall for a decision at the order row t."""
    b = np.array(a, dtype=float, copy=True)
    b[t:] = np.nan
    return b


def last_close(a: np.ndarray, t: int, back: int = 10) -> float:
    """The last finite close at or before row t - 1 (within `back` rows): the price known when the row-t order goes."""
    seg = a[max(0, t - back):t, C]
    ok = np.flatnonzero(np.isfinite(seg))
    return float(seg[ok[-1]]) if ok.size else np.nan


def day_open(a: np.ndarray) -> float:
    seg = a[:5, O]
    ok = np.flatnonzero(np.isfinite(seg))
    return float(seg[ok[0]]) if ok.size else np.nan


def day_last(a: np.ndarray) -> float:
    ok = np.flatnonzero(np.isfinite(a[:, C]))
    return float(a[ok[-1], C]) if ok.size else np.nan


# ================================================================================================ 3. end-of-day momentum
def mom_returns(a: np.ndarray, prev_close: float) -> tuple:
    """(r1, r2): previous close -> 09:45, 09:45 -> 14:50, read from the closes before each mark (rows < 14:50 only)."""
    b = blank_after(a, K.R1450)
    p1, p2 = last_close(b, K.R0945), last_close(b, K.R1450)
    if not (np.isfinite(prev_close) and prev_close > 0 and np.isfinite(p1) and np.isfinite(p2)):
        return np.nan, np.nan
    return p1 / prev_close - 1.0, p2 / p1 - 1.0


def top40(x: float, hist) -> bool:
    """|x| at or above the 60th percentile of the previous sessions' values (needs the full look-back)."""
    h = np.asarray([v for v in hist if np.isfinite(v)], float)
    if len(h) < K.MOM_LOOKBACK or not np.isfinite(x):
        return False
    return bool(abs(x) >= np.percentile(np.abs(h[-K.MOM_LOOKBACK:]), K.MOM_PCTL))


def mom_side(r1: float, r2: float, hist_sum, hist_r2, variant: str) -> int:
    """M1 ('sum'): sign(r1 + r2) when it equals sign(r2) and |r1 + r2| is in the top 40%; M2 ('r2'): sign(r2) when |r2| is
    in the top 40%. 0 = no trade."""
    if not (np.isfinite(r1) and np.isfinite(r2)) or r2 == 0:
        return 0
    if variant == "sum":
        s = r1 + r2
        if s == 0 or np.sign(s) != np.sign(r2):
            return 0
        return int(np.sign(s)) if top40(s, hist_sum) else 0
    return int(np.sign(r2)) if top40(r2, hist_r2) else 0


# ================================================================================================ 4. noise-area breakout
def mark_closes(a: np.ndarray, marks=K.MARKS) -> np.ndarray:
    """The close known at each mark (the minute before it)."""
    return np.array([last_close(a, t) for t in marks], float)


def sigma_marks(prev_days: list, marks=K.MARKS) -> np.ndarray:
    """sigma(t): mean over the previous sessions (the last NOISE_DAYS given) of |close at t / that day's open - 1|."""
    rows = []
    for a in prev_days[-K.NOISE_DAYS:]:
        o = day_open(a)
        if np.isfinite(o) and o > 0:
            rows.append(np.abs(mark_closes(a, marks) / o - 1.0))
    if len(rows) < K.NOISE_DAYS:
        return np.full(len(marks), np.nan)
    with np.errstate(all="ignore"):
        return np.nanmean(np.array(rows), axis=0)


def twap_marks(a: np.ndarray, marks=K.MARKS) -> np.ndarray:
    """The running TWAP of the typical price (H + L + C) / 3 over rows 0 .. t - 1 (V1's VWAP stand-in)."""
    tp = (a[:, H] + a[:, L] + a[:, C]) / 3.0
    out = []
    for t in marks:
        seg = tp[:t]
        out.append(float(np.nanmean(seg)) if np.isfinite(seg).any() else np.nan)
    return np.array(out, float)


def noise_levels(a: np.ndarray, sig: np.ndarray, marks=K.MARKS) -> tuple:
    """(close, upper reference = max(upper band, TWAP), lower reference = min(lower band, TWAP)) at each mark."""
    o = day_open(a)
    c = mark_closes(a, marks)
    tw = twap_marks(a, marks)
    ub, lb = o * (1 + sig), o * (1 - sig)
    return c, np.fmax(ub, tw) if np.isfinite(o) else ub, np.fmin(lb, tw) if np.isfinite(o) else lb


def noise_exit(c, hi, lo, k: int, side: int, last_mark: int) -> int:
    """For a position entered at mark index k: the first later mark index (<= last_mark) where it must exit, or -1
    (held to the day's square-off)."""
    for j in range(k + 1, last_mark + 1):
        if not (np.isfinite(c[j]) and np.isfinite(hi[j]) and np.isfinite(lo[j])):
            continue
        if side > 0 and c[j] < hi[j]:
            return j
        if side < 0 and c[j] > lo[j]:
            return j
    return -1


def noise_walk(c, hi, lo, n_entry: int, last_mark: int, allow=None) -> list:
    """V1's day: [(entry mark index, side, exit mark index or -1)]. Entries only at mark indices < n_entry, flat only,
    one position at a time; after an exit the rule may enter again at the same mark. allow(k, side) -> bool (overlay)."""
    out, pos, ek = [], 0, -1
    for k in range(0, last_mark + 1):
        if not (np.isfinite(c[k]) and np.isfinite(hi[k]) and np.isfinite(lo[k])):
            continue
        if pos > 0 and c[k] < hi[k] or pos < 0 and c[k] > lo[k]:
            out.append((ek, pos, k))
            pos = 0
        if pos == 0 and k < n_entry:
            s = 1 if c[k] > hi[k] else (-1 if c[k] < lo[k] else 0)
            if s != 0 and (allow is None or allow(k, s)):
                pos, ek = s, k
    if pos != 0:
        out.append((ek, pos, -1))
    return out


# ================================================================================================ 6. overlay
def sma_prev(closes_before: list, n: int = K.SMA_DAYS) -> float:
    c = [x for x in closes_before if np.isfinite(x)]
    return float(np.mean(c[-n:])) if len(c) >= n else np.nan


def vix_high(vix_upto_prev: list) -> bool:
    """India VIX's previous close above the 90th percentile of the 252 closes ending with it. Unknown = not high."""
    v = [x for x in vix_upto_prev if np.isfinite(x)]
    if len(v) < K.VIX_DAYS:
        return False
    w = np.asarray(v[-K.VIX_DAYS:], float)
    return bool(w[-1] > np.percentile(w, K.VIX_PCTL))


def overlay_ok(side: int, price: float, sma: float, vix_is_high: bool) -> bool:
    if vix_is_high or not (np.isfinite(price) and np.isfinite(sma)):
        return False
    return (side > 0 and price > sma) or (side < 0 and price < sma)


# ================================================================================================ sizing
def fit_lots(entry_fill: float, qty: int, cash: float = K.ACCOUNT) -> int:
    """The largest whole number of lots whose premium fits the cash (0: not even one)."""
    if not (np.isfinite(entry_fill) and entry_fill > 0 and qty > 0):
        return 0
    return int(cash // (entry_fill * qty))


def month_capped(trades: list, cap: float = K.MONTH_CAP) -> list:
    """Night N2's month stop: walk the trades in time order; once a month's closed net is <= cap, skip the rest of
    that month. trades: dicts with 'day' and 'net' (1 lot)."""
    out, mon, stopped = [], defaultdict(float), set()
    for t in sorted(trades, key=lambda x: (x["day"], x.get("und", ""))):
        m = (t["day"].year, t["day"].month)
        if m in stopped:
            continue
        out.append(t)
        mon[m] += t["net"]
        if mon[m] <= cap:
            stopped.add(m)
    return out


# ================================================================================================ statistics
def max_drawdown(daily) -> float:
    eq, peak, dd = 0.0, 0.0, 0.0
    for x in daily:
        eq += x
        peak = max(peak, eq)
        dd = max(dd, peak - eq)
    return dd


def stats(trades: list, key: str = "net") -> dict:
    """The report's numbers for one set of trades (dicts with 'day' and `key`)."""
    tr = [t for t in trades if t.get(key) is not None and np.isfinite(t[key])]
    n = len(tr)
    if not n:
        return dict(trades=0, win=0.0, net=0.0, avg_win=0.0, avg_loss=0.0, net_wo_top5=0.0, worst_day=0.0,
                    worst_month=0.0, max_dd=0.0, per_trade=0.0, months=0, months_up=0)
    nets = np.array([t[key] for t in tr], float)
    days, months = defaultdict(float), defaultdict(float)
    for t in tr:
        days[t["day"]] += t[key]
        months[f"{t['day'].year}-{t['day'].month:02d}"] += t[key]
    dser = [days[d] for d in sorted(days)]
    wins, losses = nets[nets > 0], nets[nets <= 0]
    return dict(trades=n, win=float(len(wins) / n * 100), net=float(nets.sum()), per_trade=float(nets.mean()),
                avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
                net_wo_top5=float(nets.sum() - np.sort(nets)[::-1][:5].sum()), worst_day=float(min(dser)),
                worst_month=float(min(months.values())), max_dd=float(max_drawdown(dser)), months=len(months),
                months_up=int(sum(1 for v in months.values() if v > 0)))


# ================================================================================================ twins, BH
def norm_tail(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def twin_p(rule_net: float, twins) -> tuple:
    """(empirical p, normal-tail p), one-sided: twins at or above the rule's net."""
    tw = np.asarray(twins, float)
    if not len(tw):
        return 1.0, 1.0
    k = int((tw >= rule_net - 1e-9).sum())
    pe = (1 + k) / (len(tw) + 1)
    sd = float(tw.std(ddof=1)) if len(tw) > 1 else 0.0
    pn = (1.0 if rule_net <= float(tw.mean()) else 0.0) if sd <= 0 else norm_tail((rule_net - float(tw.mean())) / sd)
    return pe, pn


def twin_draw(pool_c: np.ndarray, pool_p: np.ndarray, n: int, rng, n_twins: int = K.TWINS) -> np.ndarray:
    """Twin totals: n entries drawn without replacement from the pool (each entry's call and put net), random side."""
    m = len(pool_c)
    if n <= 0 or m == 0:
        return np.zeros(n_twins)
    n = min(n, m)
    out = np.empty(n_twins)
    for i in range(n_twins):
        idx = rng.choice(m, n, replace=False)
        side = rng.random(n) < 0.5
        out[i] = float(np.where(side, pool_c[idx], pool_p[idx]).sum())
    return out


def bh_total(p, m_total: int = K.M_TOTAL) -> np.ndarray:
    """Benjamini-Hochberg q with m_total tests in the family; the earlier tests count as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


# ================================================================================================ the gate
def gate(eras: dict, p_a: float | None, q: float) -> list:
    """PREREG section 6. eras: {'A','B','C'} -> stats at 1 lot (missing / None = untestable). Returns the failed items."""
    fails = []
    for e in ("A", "B", "C"):
        s = eras.get(e)
        if s is None:
            fails.append(f"era {e}: untestable (no data)")
            continue
        if s["trades"] < K.MIN_TRADES:
            fails.append(f"era {e}: {s['trades']} trades < {K.MIN_TRADES}")
        if not s["net"] > 0:
            fails.append(f"era {e}: net {s['net']:,.0f} <= 0")
        if not s["net_wo_top5"] > 0:
            fails.append(f"era {e}: net without the top 5 trades {s['net_wo_top5']:,.0f} <= 0")
    if p_a is None:
        fails.append("era A twin p: untestable")
    elif not p_a < K.GATE_P:
        fails.append(f"era A twin p {p_a:.3f} >= {K.GATE_P}")
    if not q <= K.GATE_Q:
        fails.append(f"BH q {q:.3g} > {K.GATE_Q}")
    return fails


def account_breach(s: dict) -> list:
    out = []
    if s and s.get("max_dd", 0) > K.ACC_DD:
        out.append(f"max drawdown Rs {s['max_dd']:,.0f} > Rs 40k")
    if s and s.get("worst_month", 0) < K.MONTH_CAP:
        out.append(f"worst month Rs {s['worst_month']:,.0f} < -Rs 13k")
    return out
