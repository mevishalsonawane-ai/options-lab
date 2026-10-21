"""R96 long-history check of the directional daily signals on the future (PREREG_ROUND1.md s7 gate 7).

Daily continuous futures (back-adjusted at causal rolls; differences between rows are not changed by later rolls),
1 Jan 2012 - 31 Jul 2025, before any option data. Same signal as the option rule; entry at the next session's open;
the rule's ATR stop checked on closes (out at that close); out at the close of the n-th session (entry day = 1) or
at the close before a roll row. N1: entry when the next session trades through the NR7 day's high / low (at the level,
or the open when it gapped through; a session that crosses both is skipped), stop at the opposite extreme.
Return per trade = side x (exit - entry) / the raw entry price - 0.10% (round trip). Twins: the same trade on a random
session of the same calendar year, same side (500 sets).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import cfg as K

COST = 0.001


def _exit(d, a, side, lvl, n):
    c, roll = d.ac.values, d.roll.values
    last = min(a + n - 1, len(c) - 1)
    for k in range(a, last + 1):
        if lvl is not None and side * (c[k] - lvl) <= 0:
            return k
        if k + 1 < len(c) and roll[k + 1]:
            return k
    return last


def trade_dir(d, i, side, katr, n):
    a = i + 1
    if a >= len(d) or d.roll.values[a] or not np.isfinite(d.atr.values[i]):
        return None
    e = d.ao.values[a]
    lvl = e - side * katr * d.atr.values[i]
    b = _exit(d, a, side, lvl, n)
    return side * (d.ac.values[b] - e) / d.open.values[a] - COST


def trade_nr7(d, i, n=3):
    a = i + 1
    if a >= len(d) or d.roll.values[a]:
        return None
    hi, lo = d.ah.values[i], d.al.values[i]
    o, h, l = d.ao.values[a], d.ah.values[a], d.al.values[a]
    up, dn = h > hi, l < lo
    if up and dn and not (o > hi or o < lo):
        return None
    if not (up or dn):
        return None
    side = 1 if (o > hi or (up and not dn)) else -1
    e = (max(o, hi) if side > 0 else min(o, lo))
    lvl = lo if side > 0 else hi
    b = _exit(d, a, side, lvl, n)
    return side * (d.ac.values[b] - e) / d.open.values[a] - COST


def run(rule_id: str, d: pd.DataFrame, period=K.HISTORY, twins: int = K.TWINS, seed: int = K.SEED):
    """d: a book's daily frame (rules.daily_ind). Returns dict(n, mean, total, twin_mean, p)."""
    from research.hunt.r96_mcx_buy import rules as R
    dates = pd.DatetimeIndex(d.date.values)
    inp = (dates.date >= period[0]) & (dates.date <= period[1])
    rng = np.random.default_rng(seed)
    rets, tw_specs = [], []
    kind = R.HISTORY_RULES.get(rule_id, "dir")
    if kind in ("nr7", "nr7_id"):
        for i in np.flatnonzero(d[kind].values.astype(bool) & inp):
            r = trade_nr7(d, i)
            if r is not None:
                rets.append(r)
                tw_specs.append((i, None))
    else:
        rule = R.RULES[rule_id]
        sides = rule.sides(d)
        for i in np.flatnonzero((sides != 0) & inp):
            r = trade_dir(d, i, int(sides[i]), rule.katr, rule.n)
            if r is not None:
                rets.append(r)
                tw_specs.append((i, int(sides[i])))
    rets = np.asarray(rets)
    if not len(rets):
        return dict(n=0, mean=float("nan"), total=0.0, twin_mean=float("nan"), p=float("nan"))
    years = dates.year.values
    pools = {y: np.flatnonzero((years == y) & inp) for y in np.unique(years[inp])}
    tot = np.zeros(twins)
    for i, side in tw_specs:
        pool = pools[years[i]]
        for t in range(twins):
            r = None
            for _ in range(20):
                j = int(pool[rng.integers(len(pool))])
                if j == i:
                    continue
                if kind in ("nr7", "nr7_id"):
                    r = trade_nr7(d, j)
                else:
                    rule = R.RULES[rule_id]
                    r = trade_dir(d, j, side, rule.katr, rule.n)
                if r is not None:
                    break
            tot[t] += r if r is not None else 0.0
    total = float(rets.sum())
    return dict(n=int(len(rets)), mean=float(rets.mean()), total=total, win=float((rets > 0).mean()),
                twin_mean=float(tot.mean() / len(rets)), p=float((np.sum(tot >= total) + 1) / (twins + 1)))
