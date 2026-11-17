"""R193 pure parts (PREREG.md): FB-R72-A10's feature, windows, statistics, funded lots, twins, BH and the gate.
No data and no engine here (test_r193.py). Plain-English notes, 17 Nov 2026.
"""
from __future__ import annotations

import math
from collections import defaultdict
from datetime import date

import numpy as np

import r193cfg as K


# ================================================================================================ FB-R72-A10
def a10_series(day_high: np.ndarray, day_low: np.ndarray, min_known: int = 10, n: int = 20) -> np.ndarray:
    """A10 per session (R140a `Days` + `features`): yesterday's range / the mean range of the n sessions before today
    (at least `min_known` known). Session d only reads sessions < d, so today's and later minutes never matter; a holiday
    is simply not a session (the previous trading session is 'yesterday'). NaN = unknown (the rule then does not skip)."""
    hi = np.asarray(day_high, float)
    lo = np.asarray(day_low, float)
    rng = hi - lo
    out = np.full(len(rng), np.nan)
    for d in range(n, len(rng)):
        w = rng[d - n:d]
        w = w[np.isfinite(w)]
        if len(w) >= min_known:
            adr = float(w.mean())
            y = rng[d - 1]
            if adr > 0 and np.isfinite(y):
                out[d] = y / adr
    return out


def a10_skip(x: float, cut: float = K.A10_CUT) -> bool:
    """Skip after a very wide day: A10 >= the frozen cut. Unknown never skips."""
    return x is not None and np.isfinite(x) and x >= cut


# ================================================================================================ windows / stats
def in_window(trades: list, w: str) -> list:
    lo, hi = K.WINDOWS[w]
    return [t for t in trades if lo <= t["day"] <= hi]


def month_of(d: date) -> str:
    return f"{d.year:04d}-{d.month:02d}"


def months_between(lo: date, hi: date) -> tuple:
    out, y, m = [], lo.year, lo.month
    while (y, m) <= (hi.year, hi.month):
        out.append(f"{y:04d}-{m:02d}")
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return tuple(out)


def stats(trades: list, lo: date, hi: date) -> dict:
    """Trades, win %, net, average win / loss, max drawdown on the daily closed-trade equity (R106's `stats`), worst month
    (a month with no trade is Rs 0), worst day, net without the 5 best trades, and the account stop check."""
    months = months_between(lo, hi)
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, net=0, avg_win=None, avg_loss=None, max_dd=0, worst_month=dict(month=None, net=0),
                    worst_day=None, net_wo_top5=0, account_stop_ok=True, months={m: 0 for m in months})
    nets = np.array([t["net"] for t in trades], float)
    by_day = defaultdict(float)
    by_month = {m: 0.0 for m in months}
    for t in trades:
        by_day[t["day"]] += t["net"]
        k = month_of(t["day"])
        by_month[k] = by_month.get(k, 0.0) + t["net"]
    seq = np.array([by_day[d] for d in sorted(by_day)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    mdd = float((np.maximum.accumulate(eq) - eq).max())
    wins, losses = nets[nets > 0], nets[nets <= 0]
    wm = min(by_month, key=lambda m: by_month[m])
    net = float(nets.sum())
    top = float(np.sort(nets)[::-1][:K.TOP_N].sum())
    return dict(trades=n, win=round(float((nets > 0).mean()) * 100, 2), net=round(net),
                avg_win=round(float(wins.mean())) if len(wins) else None,
                avg_loss=round(float(losses.mean())) if len(losses) else None,
                max_dd=round(mdd), worst_month=dict(month=wm, net=round(by_month[wm])), worst_day=round(float(seq.min())),
                net_wo_top5=round(net - top),
                account_stop_ok=bool(mdd <= K.ACC_DD and by_month[wm] >= K.MONTH_CAP),
                months={m: round(v) for m, v in by_month.items()})


def by_windows(trades: list) -> dict:
    return {w: stats(in_window(trades, w), *K.WINDOWS[w]) for w in K.WINDOWS}


# ================================================================================================ funded lots
def funded_lots(premium: float, lot_size: float, account: float = K.ACCOUNT, cap: int = K.MAX_LOTS) -> int:
    """Lots Rs 56,000 can pay for at this premium (cap 3); 0 = not even one lot (the trade is skipped)."""
    if not (premium == premium) or premium <= 0 or lot_size <= 0:
        return 0
    return int(max(0, min(cap, math.floor(account / (premium * lot_size) + 1e-9))))


# ================================================================================================ twins, BH, gate
def twin_p(actual: float, twins: np.ndarray) -> float:
    t = np.asarray(twins, float)
    return float((1 + int((t >= actual - 1e-9).sum())) / (len(t) + 1))


def normal_p(actual: float, twins: np.ndarray) -> float:
    """One-sided normal-tail p of the actual total against the twins (R190's BH input)."""
    t = np.asarray(twins, float)
    sd = float(t.std(ddof=1)) if len(t) > 1 else 0.0
    if sd <= 0:
        return 0.0 if actual > float(t.mean()) else 1.0
    z = (actual - float(t.mean())) / sd
    return float(0.5 * math.erfc(z / math.sqrt(2.0)))


def random_skip_mask(n: int, k: int, rng) -> np.ndarray:
    """A keep-mask over n trades with k removed at random (the filter twin)."""
    keep = np.ones(n, bool)
    if k > 0:
        keep[rng.choice(n, size=min(k, n), replace=False)] = False
    return keep


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


def gate(var: dict, base: dict, p_emp: float, q: float) -> dict:
    """PREREG section 6. var / base: {W1, W2, total: stats}. Returns helps + every failed item."""
    fails = []
    for w in ("W1", "W2"):
        if not var[w]["net"] > base[w]["net"]:
            fails.append(f"{w}: net {var[w]['net']:,} not above A's {base[w]['net']:,}")
        if not var[w]["max_dd"] < base[w]["max_dd"]:
            fails.append(f"{w}: max drawdown {var[w]['max_dd']:,} not below A's {base[w]['max_dd']:,}")
        if var[w]["trades"] < K.MIN_TRADES:
            fails.append(f"{w}: {var[w]['trades']} trades < {K.MIN_TRADES}")
    if not var["total"]["net_wo_top5"] > base["total"]["net"]:
        fails.append(f"total: net without top 5 {var['total']['net_wo_top5']:,} not above A's {base['total']['net']:,}")
    if not p_emp < K.GATE_P:
        fails.append(f"twin p {p_emp:.4f} >= {K.GATE_P}")
    if not q <= K.GATE_Q:
        fails.append(f"BH q {q:.3g} > {K.GATE_Q}")
    return dict(helps=not fails, fails=fails, twin_p=round(p_emp, 4), q=q)
