"""R115 pure parts (PREREG.md): the price-action features on index bars, the failed-break exit row, statistics, twins,
BH and the gates. numpy only (plus the entries count), so test_r115.py covers everything without the lake.

Every feature of a signal reads only bars at or before its signal bar (the look-ahead guard in test_r115.py changes
every later bar and checks nothing moves). The exit row reads bars after the fill, one closed bar at a time, and sells
at that bar's end (the next minute's open), never earlier.
"""
from __future__ import annotations

import math
import re
from collections import defaultdict
from pathlib import Path

import numpy as np

import r115cfg as K


# ------------------------------------------------------------------------------------------------ bar features
def bar_quality(o: float, h: float, l: float, c: float, side: str) -> bool:
    """Strong bar the trade's way: colour the trade's way, body >= 60% of the range, close in the top 25% (call) /
    bottom 25% (put). A missing value or zero range never confirms."""
    v = (o, h, l, c)
    if not all(np.isfinite(x) for x in v):
        return False
    rng = h - l
    if rng <= 0:
        return False
    if abs(c - o) / rng < K.BODY_MIN:
        return False
    if side == "C":
        return c > o and (c - l) / rng >= 1.0 - K.CLOSE_ZONE
    return c < o and (h - c) / rng >= 1.0 - K.CLOSE_ZONE


def is_outside(h: np.ndarray, l: np.ndarray, i: int) -> bool | None:
    """Bar i an outside bar (high above AND low below bar i-1's); None when there is no bar before it."""
    if i < 1:
        return None
    return bool(h[i] > h[i - 1] and l[i] < l[i - 1])


def day_starts(d: np.ndarray) -> np.ndarray:
    """Index of each bar's day's first bar (bars in time order)."""
    out = np.zeros(len(d), np.int64)
    for i in range(1, len(d)):
        out[i] = out[i - 1] if d[i] == d[i - 1] else i
    return out


def daily_hl(d: np.ndarray, h: np.ndarray, l: np.ndarray) -> tuple:
    """(days in order, high, low) per session from its bars."""
    days, hi, lo = [], [], []
    for i in range(len(d)):
        if not days or d[i] != days[-1]:
            days.append(d[i])
            hi.append(h[i])
            lo.append(l[i])
        else:
            hi[-1] = max(hi[-1], h[i])
            lo[-1] = min(lo[-1], l[i])
    return days, np.array(hi, float), np.array(lo, float)


def prev_day_index(days: list) -> dict:
    """{day: its position in `days`}."""
    return {x: k for k, x in enumerate(days)}


def fresh(side: str, h: np.ndarray, l: np.ndarray, ds: int, i: int, pdh: float, pdl: float) -> bool:
    """The break is fresh: no bar of the same session BEFORE the signal bar i (from its day start ds) already traded
    above the previous session's high (call) / below its low (put). The first bar of a day is always fresh; an unknown
    previous session never confirms."""
    if not (np.isfinite(pdh) and np.isfinite(pdl)):
        return False
    if i <= ds:
        return True
    if side == "C":
        return bool(np.nanmax(h[ds:i]) <= pdh)
    return bool(np.nanmin(l[ds:i]) >= pdl)


def compression(k: int, hi: np.ndarray, lo: np.ndarray) -> bool:
    """The session BEFORE session k was an NR4 (smallest high-low of its last 4 sessions) or an inside day (high <= and
    low >= the session before it). Needs 4 earlier sessions; otherwise False."""
    p = k - 1
    if p < 3:
        return False
    rng = hi - lo
    nr4 = bool(rng[p] <= rng[p - 3:p].min())
    inside = bool(hi[p] <= hi[p - 1] and lo[p] >= lo[p - 1])
    return nr4 or inside


def channel_level(h: np.ndarray, l: np.ndarray, i: int, side: str, n: int = K.N_CH) -> float:
    """The level the signal broke: the high (call) / low (put) of the n bars before bar i (R44 / R72's channel)."""
    if i < n:
        return float("nan")
    return float(np.max(h[i - n:i])) if side == "C" else float(np.min(l[i - n:i]))


def failed_break_row(side: str, i: int, level: float, b: np.ndarray, c: np.ndarray, ds_next: int,
                     nrows: int = 375) -> int:
    """Row of the failed-break exit for a signal on bar i: the first LATER bar k of the same session (k < ds_next, the
    next session's first bar) whose close is back inside the channel (below `level` for a call, above for a put); sold
    at that bar's end row 5 (b[k] + 1) (the next minute's open). -1 when none or past the session."""
    if not np.isfinite(level):
        return -1
    for k in range(i + 1, ds_next):
        if (side == "C" and c[k] < level) or (side == "P" and c[k] > level):
            r = 5 * (int(b[k]) + 1)
            return r if r < nrows else -1
    return -1


def minute_bar(idx: np.ndarray, r0: int, r1: int) -> tuple:
    """(o, h, l, c) of the index minutes r0..r1-1 (idx is (4, 375) O H L C); NaN when no minute is there."""
    a = idx[:, max(r0, 0):r1]
    ok = np.isfinite(a[3])
    if not ok.any():
        return (float("nan"),) * 4
    return (float(a[0][ok][0]), float(np.nanmax(a[1])), float(np.nanmin(a[2])), float(a[3][ok][-1]))


# ------------------------------------------------------------------------------------------------ statistics
def stats(trades: list) -> dict:
    """Trades, win rate, net, average win / loss, worst day, max drawdown (daily), months at or below -Rs 13,000."""
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, net=0, avg_win=None, avg_loss=None, worst_day=0, max_dd=0, bad_months=0)
    nets = np.array([float(t["net"]) for t in trades])
    daily, monthly = defaultdict(float), defaultdict(float)
    for t in trades:
        daily[t["day"]] += float(t["net"])
        monthly[(t["day"].year, t["day"].month)] += float(t["net"])
    seq = np.array([daily[d] for d in sorted(daily)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    w, lo = nets[nets > 0], nets[nets <= 0]
    return dict(trades=n, win=round(float((nets > 0).mean()), 4), net=round(float(nets.sum())),
                avg_win=round(float(w.mean())) if len(w) else None, avg_loss=round(float(lo.mean())) if len(lo) else None,
                worst_day=round(float(seq.min())), max_dd=round(float((np.maximum.accumulate(eq) - eq).max())),
                bad_months=int(sum(1 for v in monthly.values() if v <= K.MONTH_LIMIT)))


def open_slice(trades: list) -> list:
    """The trades filled 09:15-09:30 (fill row < 15)."""
    return [t for t in trades if int(t["er"]) < K.OPEN_SLICE_ROW]


def trade_key(t: dict) -> tuple:
    return (t["und"], t["day"], int(t["er_sig"]), t["side"], t.get("book"))


def changed(a: list, b: list) -> int:
    """Trades in one book and not the other, or with a different exit (by index, day, signal row, side, book)."""
    ka = {trade_key(t): (int(t["xr"]), round(float(t["net"]), 2)) for t in a}
    kb = {trade_key(t): (int(t["xr"]), round(float(t["net"]), 2)) for t in b}
    return sum(1 for k in set(ka) | set(kb) if ka.get(k) != kb.get(k))


# ------------------------------------------------------------------------------------------------ twins, p, BH
def twin_p(actual: float, twins) -> dict:
    """Monte-Carlo p = (1 + twins >= actual) / (n + 1); when no twin reaches it, the normal-tail p from the twins' mean
    and sd (PREREG gate 3). Returns both and the p used."""
    t = np.asarray(twins, float)
    n = len(t)
    hits = int((t >= actual - 1e-9).sum())
    p_mc = (1 + hits) / (n + 1)
    sd = float(t.std(ddof=1)) if n > 1 else 0.0
    if sd > 0:
        z = (actual - float(t.mean())) / sd
        p_norm = 0.5 * math.erfc(z / math.sqrt(2.0))
    else:
        p_norm = 0.0 if actual > float(t.mean()) else 1.0
    p = p_mc if hits > 0 else min(p_mc, p_norm)
    return dict(p=float(p), p_mc=float(p_mc), p_norm=float(p_norm), twin_mean=round(float(t.mean())) if n else None,
                twin_sd=round(sd), hits=hits)


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


def exit_twin_totals(fixed: float, row_nets: list, n: int, rng) -> np.ndarray:
    """Exit twin: `fixed` = the net of every trade the rule did not sell earlier; for each trade it did, one random
    minute's net drawn uniformly from row_nets[k] (the nets of selling it at each minute after its fill and before
    today's exit)."""
    tot = np.full(n, float(fixed))
    for nets in row_nets:
        nets = np.asarray(nets, float)
        if len(nets):
            tot += nets[rng.integers(0, len(nets), size=n)]
    return tot


def random_drop(n_total: int, k: int, rng) -> np.ndarray:
    return rng.choice(n_total, size=k, replace=False) if k else np.zeros(0, np.int64)


# ------------------------------------------------------------------------------------------------ gates
def gates(rule: dict, base: dict, q_all: float, final_changed: int) -> dict:
    """rule / base: {period: stats} for design, wf, final. PREREG section 5, all must hold."""
    fails = []
    for per in ("design", "wf", "final"):
        r, b = rule[per], base[per]
        if r["net"] <= b["net"]:
            fails.append(f"{per}: net not above today's")
        if r["worst_day"] < b["worst_day"]:
            fails.append(f"{per}: worst day worse")
        if r["max_dd"] > b["max_dd"]:
            fails.append(f"{per}: drawdown worse")
    if q_all > K.GATE_Q:
        fails.append(f"BH q {q_all:.3g} > {K.GATE_Q}")
    if rule["final"]["trades"] < K.MIN_FINAL:
        fails.append(f"< {K.MIN_FINAL} final trades")
    if final_changed < K.MIN_CHANGED:
        fails.append(f"< {K.MIN_CHANGED} final trades differ")
    return dict(passed=not fails, fails=fails)


# ------------------------------------------------------------------------------------------------ the count of earlier tests
def count_prior(entries_dir: Path, last_day: str = "2026-10-26") -> int:
    """PREREG gate 4: every family in the entries up to last_day, each the larger of its entry count and its summed
    variants_tried (a family = the id's leading 'r<N>' or its first word)."""
    import yaml
    fam, var = defaultdict(int), defaultdict(int)
    for f in sorted(Path(entries_dir).glob("*.yaml")):
        y = yaml.safe_load(f.read_text())
        if str(y.get("date", "")) > last_day:
            continue
        i = str(y.get("id", ""))
        m = re.match(r"r(\d+)", i)
        key = ("R" + m.group(1)) if m else re.split(r"[-_]", i)[0]
        fam[key] += 1
        try:
            v = int(y.get("variants_tried") or 1)
        except (TypeError, ValueError):
            v = 1
        var[key] += max(v, 1)
    return sum(max(fam[k], var[k]) for k in fam)
