"""R112 pure parts (PREREG.md): the entry cut, the 10:00 close, the Supertrend filter, statistics, twins, BH and gates.

Everything here takes plain lists / arrays, so test_r112.py covers it without the lake.
"""
from __future__ import annotations

from collections import defaultdict
from datetime import date

import numpy as np

import r112cfg as K
import r106study as S106          # stats / bh / better_than_b0 (pure; its data side loads lazily)
import r108book as B              # twin_matrix / max_dd (pure)


# ------------------------------------------------------------------------------------------------ entries
def cut_keep(cands: list, cut: int | None) -> list:
    """Indices of the candidates whose entry row (the signal bar's close) is at or before `cut` (None = all day). Reads
    only each candidate's own entry row, so nothing after the entry is looked at."""
    return [j for j, c in enumerate(cands) if cut is None or int(c["er"]) <= cut]


def with_close(cands: list, close: int | None) -> list:
    """Copies of the candidates with a forced exit at row `close` (sold at that minute's open; None = no forced exit)."""
    out = []
    for c in cands:
        c2 = dict(c)
        if close is not None:
            c2["fx"] = int(close)
        out.append(c2)
    return out


def supertrend_keep(sig_by_day: dict, pos: dict, st: np.ndarray) -> dict:
    """R73 T7 on R51's signals: {day: [(er, side)]} -> only the signals whose 5-minute signal bar b (er = 5 (b + 1)) has
    Supertrend pointing the signal's way (+1 call, -1 put). pos[(day, b)] = the bar's index in the continuous 5-minute
    series; a bar not in the series, or Supertrend not yet known (0), is skipped (as the app: "cannot be judged")."""
    out = {}
    for d, sigs in sig_by_day.items():
        keep = []
        for er, side in sigs:
            b = int(er) // 5 - 1
            i = pos.get((d, b))
            if i is None:
                continue
            s = 1 if side == "C" else -1
            if int(st[i]) == s:
                keep.append((er, side))
        if keep:
            out[d] = keep
    return out


# ------------------------------------------------------------------------------------------------ statistics
def months_of(trades: list, sessions: list) -> dict:
    """{(year, month): net} over the sessions (a month with sessions but no trade is Rs 0)."""
    m = defaultdict(float)
    for d in sessions:
        m[(d.year, d.month)] += 0.0
    for t in trades:
        m[(t["day"].year, t["day"].month)] += t["net"]
    return dict(m)


def stats(trades: list, sessions: list) -> dict:
    """R106's statistics plus months at or below -Rs 13,000, the worst month and the sessions."""
    s = S106.stats(trades, len(sessions))
    mv = list(months_of(trades, sessions).values())
    s.update(months=len(mv), months_below=int(sum(1 for x in mv if x <= K.MONTH_LIMIT)),
             worst_month=(round(min(mv)) if mv else 0))
    return s


def be_theory(win: float = K.TARGET, loss: float = K.STOP) -> float:
    """The break-even win rate when every win nets +win and every loss -loss (both after charges)."""
    return loss / (win + loss)


def by_day(trades: list) -> dict:
    out = defaultdict(list)
    for t in trades:
        out[t["day"]].append(float(t["net"]))
    return dict(out)


# ------------------------------------------------------------------------------------------------ twins
def entry_twin_totals(rule: list, base: list, sessions: list, n: int, rng) -> np.ndarray:
    """PREREG gate 3, entry twin: per session the same number of trades as the rule took, drawn at random from the base
    rule's trades that day (all-day entries, the same exits). Returns the twins' total nets (n,)."""
    k = {d: len(v) for d, v in by_day(rule).items()}
    base_d = by_day(base)
    ks = {d: max(0, len(base_d.get(d, [])) - k.get(d, 0)) for d in sessions}      # trades REMOVED from the base
    M = B.twin_matrix(base_d, ks, sessions, n, rng)
    return M.sum(axis=1)


def exit_twin_totals(fixed: float, row_nets: list, n: int, rng) -> np.ndarray:
    """PREREG gate 3, exit twin. fixed: the summed net of the trades the rule did not close earlier; row_nets: for each
    trade it did close earlier, the nets of selling it at each allowed minute (fill+1 .. own exit-1). Each twin draws
    one minute per trade uniformly."""
    tot = np.full(n, float(fixed))
    for nets in row_nets:
        nets = np.asarray(nets, float)
        tot += nets[rng.integers(0, len(nets), size=n)]
    return tot


def twin_p(actual: float, twins: np.ndarray) -> float:
    return float((1 + int((np.asarray(twins) >= actual - 1e-9).sum())) / (len(twins) + 1))


def bh(p, m_total: int | None = None) -> np.ndarray:
    return S106.bh(p, m_total)


# ------------------------------------------------------------------------------------------------ gates
def better(r: dict, b: dict) -> bool:
    """R106 gate 2 for one period: net above B0's, or within 5% of it with worst day and drawdown each >= 20% smaller."""
    return S106.better_than_b0(r, b)


def gates(per: dict, q: float, n_final: int) -> dict:
    """per[period] = (rule stats, B0 stats) for 'design', 'wf', 'final'. PREREG section 5."""
    fails = []
    if not all(per[p][0]["net"] > 0 for p in ("design", "wf", "final")):
        fails.append("not positive in design, walk-forward and final")
    worse = [p for p in ("design", "wf", "final") if not better(*per[p])]
    if worse:
        fails.append("not better than today's rules in " + ", ".join(worse))
    if not q <= K.GATE_Q:
        fails.append(f"twin BH q {q:.3f} > {K.GATE_Q}")
    if n_final < K.MIN_FINAL:
        fails.append(f"only {n_final} final trades (< {K.MIN_FINAL})")
    return dict(passed=not fails, fails=fails, q=round(float(q), 4))


def in_wf(d: date) -> bool:
    return K.WF_START <= d <= K.DESIGN_END
