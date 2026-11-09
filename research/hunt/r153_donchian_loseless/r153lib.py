"""R153 pure helpers (PREREG.md): loss statistics, the day loss cap, the morning cut, twins' p and the "lose less" gates.

Plain lists and dicts only (no stores); test_r153.py covers every function.
"""
from __future__ import annotations

import math
from collections import defaultdict

import numpy as np

DAY_CAP = -4000.0                  # DAYCAP: two of Boss's -Rs 2,000 stops
AM_LAST_ROW = 10 * 60 - (9 * 60 + 15)   # AM10: entry row <= 10:00 (R112)


# ================================================================================================ statistics
def daily(trades: list) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[t["day"]] += float(t["net"])
    return dict(out)


def loss_sum_of(day_nets) -> float:
    """Sum of the losing days of (day, net) pairs."""
    d = defaultdict(float)
    for day, x in day_nets:
        d[day] += float(x)
    return float(sum(v for v in d.values() if v < 0))


def loss_stats(trades: list) -> dict:
    """Trades, win, net, sum of losing days, losing days, worst day, max drawdown (daily), worst month, sum of losing
    trades, exit mix (target / Rs 2k stop / -15% stop / lock / other)."""
    n = len(trades)
    if not n:
        return dict(trades=0, win=None, net=0, loss_sum=0, losing_days=0, days=0, worst_day=0, max_dd=0, worst_month=0,
                    sum_losing_trades=0, mix={})
    nets = np.array([float(t["net"]) for t in trades])
    dd = daily(trades)
    seq = np.array([dd[d] for d in sorted(dd)])
    eq = np.concatenate([[0.0], np.cumsum(seq)])
    mon = defaultdict(float)
    for t in trades:
        mon[(t["day"].year, t["day"].month)] += float(t["net"])
    mix = defaultdict(int)
    for t in trades:
        w = t.get("why", "?")
        mix[w if w in ("target", "stop2k", "stop", "lock") else "other"] += 1
    return dict(trades=n, win=round(float((nets > 0).mean()), 4), net=round(float(nets.sum())),
                loss_sum=round(float(seq[seq < 0].sum())), losing_days=int((seq < 0).sum()), days=len(seq),
                worst_day=round(float(seq.min())), max_dd=round(float((np.maximum.accumulate(eq) - eq).max())),
                worst_month=round(float(min(mon.values()))), sum_losing_trades=round(float(nets[nets < 0].sum())),
                mix=dict(mix))


def tkey(t: dict) -> tuple:
    return (t["und"], t["day"], int(t["er_sig"]), t["side"])


def changed(a: list, b: list) -> int:
    """Trades in one list and not the other, or with a different exit row or net."""
    ka = {tkey(t): (int(t["xr"]), round(float(t["net"]), 2)) for t in a}
    kb = {tkey(t): (int(t["xr"]), round(float(t["net"]), 2)) for t in b}
    return sum(1 for k in set(ka) | set(kb) if ka.get(k) != kb.get(k))


# ================================================================================================ the skip rules
def day_cap(trades: list, cap: float = DAY_CAP) -> list:
    """DAYCAP: per day, in entry order, drop a trade when the kept trades already closed before its entry minute (exit
    row < entry row) net <= cap. Exact as a filter of the walked trades: once the cap is hit every later entry that day
    is blocked, and the trades before it are unchanged."""
    out = []
    by = defaultdict(list)
    for t in trades:
        by[t["day"]].append(t)
    for d in sorted(by):
        kept = []
        for t in sorted(by[d], key=lambda x: (int(x["er"]), x["und"], x["side"])):
            closed = sum(float(k["net"]) for k in kept if int(k["xr"]) < int(t["er"]))
            if closed <= cap:
                continue
            kept.append(t)
        out += kept
    return out


def am_keep(c: dict, last_row: int = AM_LAST_ROW) -> bool:
    """AM10: a candidate whose signal entry row is at or before 10:00."""
    return int(c["er"]) <= last_row


# ================================================================================================ twins
def twin_p(actual: float, twins) -> dict:
    """R115's p: (1 + twins >= actual) / (n + 1); when no twin reaches it, the normal-tail p from the twins' mean / sd."""
    t = np.asarray(twins, float)
    n = len(t)
    hits = int((t >= actual - 1e-9).sum())
    p_mc = (1 + hits) / (n + 1)
    sd = float(t.std(ddof=1)) if n > 1 else 0.0
    if sd > 0:
        p_norm = 0.5 * math.erfc(((actual - float(t.mean())) / sd) / math.sqrt(2.0))
    else:
        p_norm = 0.0 if actual > float(t.mean()) else 1.0
    p = p_mc if hits > 0 else min(p_mc, p_norm)
    return dict(p=float(p), p_mc=float(p_mc), p_norm=float(p_norm), twin_mean=round(float(t.mean())) if n else None,
                hits=hits)


def exit_twin_loss_sums(fixed: list, rows: list, n: int, rng) -> np.ndarray:
    """fixed: (day, net) of trades the rule did not sell earlier; rows: (day, nets of selling at each minute strictly
    between the fill and today's exit). Each twin draws one minute per row; returns each twin's sum of losing days."""
    base = defaultdict(float)
    for d, x in fixed:
        base[d] += float(x)
    days = sorted(set(base) | {d for d, _ in rows})
    pos = {d: i for i, d in enumerate(days)}
    out = np.zeros(n)
    b = np.array([base.get(d, 0.0) for d in days])
    draws = [(pos[d], np.asarray(nets, float)) for d, nets in rows if len(nets)]
    for x in range(n):
        v = b.copy()
        for i, nets in draws:
            v[i] += nets[rng.integers(0, len(nets))]
        out[x] = v[v < 0].sum()
    return out


def day_skip_twin_loss_sums(base: list, kept: list, n: int, rng) -> np.ndarray:
    """DAYCAP's twin: from `base` (the trades without the cap) remove at random, within each day, as many trades as the
    cap removed that day; each twin's sum of losing days."""
    by = defaultdict(list)
    for t in base:
        by[t["day"]].append(float(t["net"]))
    k = defaultdict(int)
    nk = defaultdict(int)
    for t in kept:
        nk[t["day"]] += 1
    for d in by:
        k[d] = len(by[d]) - nk.get(d, 0)
    days = sorted(by)
    out = np.zeros(n)
    for x in range(n):
        s = 0.0
        for d in days:
            v = np.asarray(by[d])
            if k[d] > 0:
                drop = rng.choice(len(v), size=k[d], replace=False)
                v = np.delete(v, drop)
            tot = float(v.sum())
            s += tot if tot < 0 else 0.0
        out[x] = s
    return out


# ================================================================================================ gates
def gates(rule: dict, base: dict, p: float, q: float, final_changed: int, gate_q: float = 0.10, gate_p: float = 0.05,
          min_final: int = 40, min_changed: int = 30) -> dict:
    """PREREG section 4 (R140's): rule / base = {period: loss_stats} for design, wf, final."""
    fails = []
    for per in ("design", "wf", "final"):
        r, b = rule[per], base[per]
        if not r["loss_sum"] > b["loss_sum"]:
            fails.append(f"{per}: losing days not smaller")
        if r["max_dd"] > b["max_dd"]:
            fails.append(f"{per}: drawdown worse")
        if r["worst_day"] < b["worst_day"]:
            fails.append(f"{per}: worst day worse")
        if r["worst_month"] < b["worst_month"]:
            fails.append(f"{per}: worst month worse")
        if not r["net"] > 0:
            fails.append(f"{per}: the arm no longer makes money")
    if not p < gate_p:
        fails.append(f"twin p {p:.3g} >= {gate_p}")
    if q > gate_q:
        fails.append(f"BH q {q:.3g} > {gate_q}")
    if rule["final"]["trades"] < min_final:
        fails.append(f"< {min_final} final trades")
    if final_changed < min_changed:
        fails.append(f"< {min_changed} final trades differ")
    return dict(passed=not fails, fails=fails)
