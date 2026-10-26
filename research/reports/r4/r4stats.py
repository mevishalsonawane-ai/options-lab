"""R4 report: pure statistics for the before / after page (no data access, no I/O).

26 Oct 2026 (TODO R4, Boss 13 Oct: "show me before and after results after everything is done").
Every function takes plain lists of trade dicts ({day, net, ...}) so the tests can feed hand-made trades.
Nothing here decides a trade; it only counts what the arms' own code already produced.
"""
from __future__ import annotations

from collections import defaultdict
from datetime import date

import numpy as np

# A "big winner" (R64's big-mover test): the held option's high, from the fill to 15:15, reached +60% of the fill.
# "Cut short" = it was sold for less than +30% (half of that). Rupees = what selling at +30% instead would have added
# ((0.30 - sold) x fill x quantity): a floor, since every one of them traded at +60% or more after the fill.
BIG_PEAK = 0.60
CUT_BELOW = 0.30
SMALL_N = 30            # fewer trades than this: marked "small sample" on the page


def daily(trades: list) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[t["day"]] += float(t["net"])
    return dict(out)


def max_drawdown(day_nets: list) -> float:
    """Largest fall of the running total from its best, over the days in order (0 if it never fell)."""
    best, cur, dd = 0.0, 0.0, 0.0
    for x in day_nets:
        cur += float(x)
        best = max(best, cur)
        dd = max(dd, best - cur)
    return dd


def big_cut(trades: list) -> dict:
    """Big winners (peak >= +60%) and how many of them were sold below +30%, with the rupees left (upper bound)."""
    big = [t for t in trades if np.isfinite(t.get("peak", np.nan)) and t["peak"] >= BIG_PEAK]
    cut = [t for t in big if t["ret"] < CUT_BELOW]
    left = sum((CUT_BELOW - t["ret"]) * t["E"] * t["qty"] for t in cut)
    return dict(big=len(big), cut=len(cut), left=round(float(left)))


def stats(trades: list, sessions: list) -> dict:
    """The page's columns for one arm (or the portfolio) over `sessions` (the period's trading days, in order)."""
    sess = set(sessions)
    tr = [t for t in trades if t["day"] in sess]
    n = len(tr)
    dn = daily(tr)
    seq = [dn.get(d, 0.0) for d in sessions]
    traded = [d for d in sessions if d in dn]
    nets = np.array([float(t["net"]) for t in tr]) if n else np.zeros(0)
    wins, losses = nets[nets > 0], nets[nets <= 0]
    total = float(nets.sum()) if n else 0.0
    out = dict(
        trades=n, sessions=len(sessions), days_traded=len(traded),
        net=round(total),
        per_day=round(total / len(sessions)) if sessions else 0,
        green_days=round(sum(1 for d in traded if dn[d] > 0) / len(traded), 4) if traded else None,
        win=round(float((nets > 0).mean()), 4) if n else None,
        avg_win=round(float(wins.mean())) if len(wins) else None,
        avg_loss=round(float(losses.mean())) if len(losses) else None,
        worst_day=round(min(seq)) if seq else 0,
        worst_trade=round(float(nets.min())) if n else None,
        max_dd=round(max_drawdown(seq)),
        small=n < SMALL_N,
    )
    if n and all("peak" in t for t in tr):
        out["big_cut"] = big_cut(tr)
    return out


def sig_key(t: dict) -> tuple:
    return (t["day"], t["und"], t["side"], int(t.get("er_sig", t["er"])), t.get("book"))


def paired(before: list, after: list, big: float = 2_000.0) -> dict:
    """The same signals under both rule sets (a signal taken by only one side is counted apart). 'cut' = signals that
    made >= Rs `big` under the old rules and less under the new; 'ran' = signals that made >= Rs `big` under the new
    rules and more than under the old."""
    b = {sig_key(t): float(t["net"]) for t in before}
    a = {sig_key(t): float(t["net"]) for t in after}
    both = b.keys() & a.keys()
    cut = [k for k in both if b[k] >= big and a[k] < b[k]]
    ran = [k for k in both if a[k] >= big and a[k] > b[k]]
    return dict(both=len(both), only_before=len(b.keys() - a.keys()), only_after=len(a.keys() - b.keys()),
                only_before_net=round(sum(b[k] for k in b.keys() - a.keys())),
                only_after_net=round(sum(a[k] for k in a.keys() - b.keys())),
                cut=len(cut), cut_rs=round(sum(a[k] - b[k] for k in cut)),
                ran=len(ran), ran_rs=round(sum(a[k] - b[k] for k in ran)),
                same_signals_change=round(sum(a[k] - b[k] for k in both)))


def month_breaches(trades: list, sessions: list, month_limit: float = -13_000.0) -> int:
    """Months whose net is at or below the account rule's month limit (CLAUDE.md: -Rs 13k a month)."""
    by = defaultdict(float)
    for t in trades:
        by[(t["day"].year, t["day"].month)] += float(t["net"])
    months = {(d.year, d.month) for d in sessions}
    return sum(1 for m in months if by.get(m, 0.0) <= month_limit)


def percentile_of(paper_total: float, pool: list, n: int, draws: int = 20_000, seed: int = 4) -> dict:
    """R105's method: where does the paper total of n trades sit among `draws` random samples of n trades drawn (with
    replacement) from the backtest's own trades? Returns the percentile (share of samples at or below the paper)."""
    pool = np.asarray(pool, dtype=float)
    if n <= 0 or len(pool) == 0:
        return dict(n=n, pct=None)
    rng = np.random.default_rng(seed)
    sums = pool[rng.integers(0, len(pool), size=(draws, n))].sum(axis=1)
    p = float((sums <= paper_total).mean())
    lo, mid, hi = np.percentile(sums, [5, 50, 95])
    return dict(n=n, pct=round(100 * p, 1), p5=round(float(lo), 2), p50=round(float(mid), 2), p95=round(float(hi), 2))


def reading(pct: float | None) -> str:
    """Plain words for a percentile (R105's wording)."""
    if pct is None:
        return "no sample"
    if pct < 2.5:
        return "outside the range: rare bad luck or the app differs"
    if pct < 10:
        return "low end of the range: unlucky, worth watching"
    if pct > 97.5:
        return "above the range: unusually good"
    return "inside the range: normal"


def as_date(x) -> date:
    return x if isinstance(x, date) else x.date()
