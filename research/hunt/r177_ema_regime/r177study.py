"""R177-EMA study (PREREG.md): run the 10 rule sets on NIFTY and BANKNIFTY, the twins, the gates and the report.

    python research/hunt/r177_ema_regime/r177study.py design     # design period only (6 May 2024 - 22 Aug 2025)
    python research/hunt/r177_ema_regime/r177study.py final      # the ONE final read (writes results/FINAL_READ first)
    python research/hunt/r177_ema_regime/r177study.py report     # re-write the report from the cached results

Pure helpers (stats, months, drawdown, BH, gates, the twin sampler) are tested in test_r177.py.
"""
from __future__ import annotations

import json
import math
import pickle
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r177cfg as K  # noqa: E402


# ================================================================================================ pure helpers
def max_drawdown(daily: list) -> float:
    """Largest fall of the cumulative daily net from its running peak (the start counts as a peak of 0)."""
    eq, peak, dd = 0.0, 0.0, 0.0
    for x in daily:
        eq += x
        peak = max(peak, eq)
        dd = max(dd, peak - eq)
    return dd


def by_day(trades: list, li: int) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[t.day] += t.net[li]
    return dict(sorted(out.items()))


def by_month(trades: list, li: int) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[K.month_of(t.day)] += t.net[li]
    return dict(sorted(out.items()))


def stats(trades: list, li: int) -> dict:
    """The report's numbers for one set of trades at lots K.LOTS[li]."""
    n = len(trades)
    nets = np.array([t.net[li] for t in trades], float)
    wins, losses = nets[nets > 0], nets[nets <= 0]
    days = by_day(trades, li)
    months = by_month(trades, li)
    top5 = float(np.sort(nets)[::-1][:5].sum()) if n else 0.0
    mix = defaultdict(int)
    for t in trades:
        mix[t.reason] += 1
    return dict(
        trades=n, win=float(len(wins) / n * 100) if n else 0.0, net=float(nets.sum()),
        avg_win=float(wins.mean()) if len(wins) else 0.0, avg_loss=float(losses.mean()) if len(losses) else 0.0,
        worst_day=float(min(days.values())) if days else 0.0, max_dd=float(max_drawdown(list(days.values()))),
        worst_month=float(min(months.values())) if months else 0.0, net_wo_top5=float(nets.sum() - top5),
        exits=dict(mix), months={k: round(v) for k, v in months.items()})


def bh_total(p, m_total: int) -> np.ndarray:
    """Benjamini-Hochberg q with m_total tests in the family; the earlier tests count as p = 1 (R169 / R171's rule)."""
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


def norm_tail(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def twin_p(rule_net: float, twins: np.ndarray) -> tuple:
    """(empirical p, normal-tail p) of the rule's net against its twin totals (one-sided, PREREG section 6)."""
    twins = np.asarray(twins, float)
    k = int((twins >= rule_net - 1e-9).sum())
    pe = (1 + k) / (len(twins) + 1)
    sd = float(twins.std(ddof=1)) if len(twins) > 1 else 0.0
    if sd <= 0:
        pn = 1.0 if rule_net <= float(twins.mean() if len(twins) else 0.0) else 0.0
    else:
        pn = norm_tail((rule_net - float(twins.mean())) / sd)
    return pe, pn


def twin_draws(table: np.ndarray, counts: np.ndarray, n: int, rng) -> np.ndarray:
    """Twin totals: table (ndays, nbars, nlots) of each (day, entry bar)'s net (0 = no fill); counts (ndays,) the
    rule's entries a day. Each twin draws counts[d] distinct bars a day uniformly. Returns (n, nlots)."""
    nd, nb, nl = table.shape
    out = np.zeros((n, nl))
    for d in range(nd):
        k = int(counts[d])
        if k <= 0:
            continue
        k = min(k, nb)
        pick = np.argsort(rng.random((n, nb)), axis=1)[:, :k]
        out += table[d][pick].sum(axis=1)
    return out


def select(trades: list, und: str, side: str, period: str) -> list:
    return [t for t in trades if (und == "both" or t.und == und) and (side == "both" or t.side == side)
            and K.in_period(t.day, period)]


def gate(final: dict, design: dict, pe: float, q: float) -> list:
    """The failed gates of one cell (PREREG section 7); empty = passed."""
    fails = []
    if not final["net"] > 0:
        fails.append("final net <= 0")
    if not pe < K.GATE_P:
        fails.append(f"twin p {pe:.3f} >= 0.05")
    if not q <= K.GATE_Q:
        fails.append(f"BH q {q:.2f} > 0.10")
    if final["trades"] < K.MIN_FINAL:
        fails.append(f"{final['trades']} final trades < 40")
    if not final["net_wo_top5"] > 0:
        fails.append("final net without the top 5 <= 0")
    if not design["net"] > 0:
        fails.append("design walk-forward net <= 0")
    bad = [p for p, s in (("design", design), ("final", final))
           if s["max_dd"] > K.MAX_DD or s["worst_month"] < K.WORST_MONTH]
    if bad:
        fails.append("outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in " + ", ".join(bad))
    return fails


# ================================================================================================ running
def indices() -> dict:
    import r171data as D
    import r177lib as Z
    store = D.load()
    return {u: Z.from_days(u, store[u]) for u in K.UNDS}


def run_walks(I: dict, lo: date, hi: date) -> tuple:
    import r177lib as Z
    trades, nofill = {}, {}
    for rule in K.RULES:
        trades[rule], nofill[rule] = [], {}
        for u in K.UNDS:
            t, nf = Z.walk(I[u], rule, lo, hi)
            trades[rule] += t
            nofill[rule][u] = nf
    return trades, nofill


def _dump(obj, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str))


def run_design():
    I = indices()
    trades, nofill = run_walks(I, K.START, K.DESIGN_END)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / "design.pkl", "wb") as fh:
        pickle.dump(dict(trades=trades, nofill=nofill), fh)
    out = {}
    for rule in K.RULES:
        for li, n in enumerate(K.LOTS):
            s = stats(select(trades[rule], "both", "both", "design"), li)
            out[f"{rule}@{n}"] = {k: v for k, v in s.items() if k != "months"}
            folds = []
            for a, b in K.FOLDS:
                folds.append(round(sum(t.net[li] for t in trades[rule] if a <= t.day <= b)))
            out[f"{rule}@{n}"]["folds"] = folds
    _dump(out, K.RESULTS / "design.json")
    for rule in K.RULES:
        s = out[f"{rule}@1"]
        print(f"{rule:9s} design 1 lot: {s['trades']:5d} trades  win {s['win']:4.1f}%  net {s['net']:>11,.0f}  "
              f"folds {s['folds']}", flush=True)


def twin_tables(I: dict, trades: dict) -> dict:
    """(rule, und, side) -> (n_twins, nlots) twin totals over the final period."""
    import r177lib as Z
    rep = {}
    for rule in K.RULES:
        rep.setdefault(K.exit_mode(rule), rule)
    tables = {}
    for key, rule in rep.items():
        for u in K.UNDS:
            Ix = I[u]
            dis = [di for di, d in enumerate(Ix.days) if K.in_period(d, "final") and Z.tradable(Ix, di)]
            for side in ("C", "P"):
                T = np.zeros((len(dis), K.LAST_SIGNAL_BAR + 1, len(K.LOTS)))
                for r, di in enumerate(dis):
                    for b in range(K.LAST_SIGNAL_BAR + 1):
                        if not Ix.f["valid"][di, b]:
                            continue
                        t = Z.trade_at(Ix, di, b, side, rule)
                        if t is not None:
                            T[r, b] = t.net
                tables[(key, u, side)] = (dis, T)
    out = {}
    for ri, rule in enumerate(K.RULES):
        for ui, u in enumerate(K.UNDS):
            for si, side in enumerate(("C", "P")):
                dis, T = tables[(K.exit_mode(rule), u, side)]
                days = [I[u].days[di] for di in dis]
                cnt = defaultdict(int)
                for t in trades[rule]:
                    if t.und == u and t.side == side and K.in_period(t.day, "final"):
                        cnt[t.day] += 1
                counts = np.array([cnt.get(d, 0) for d in days])
                rng = np.random.default_rng([K.SEED, ri, ui, si])
                out[(rule, u, side)] = twin_draws(T, counts, K.TWINS, rng)
    return out


def run_final():
    if K.FINAL_FLAG.exists():
        raise SystemExit(f"{K.FINAL_FLAG} exists: the final period was already read once (PREREG section 5)")
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text("R177-EMA final period (25 Aug 2025 - 23 Feb 2026) read once, 15 Nov 2026.\n")
    I = indices()
    trades, nofill = run_walks(I, K.START, K.END)
    twins = twin_tables(I, trades)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / "final.pkl", "wb") as fh:
        pickle.dump(dict(trades=trades, nofill=nofill, twins=twins), fh)
    evaluate(trades, nofill, twins)


def evaluate(trades: dict, nofill: dict, twins: dict):
    cells = {}
    for (rule, und, side, n) in K.TESTS:
        li = K.LOTS.index(n)
        per = {p: stats(select(trades[rule], und, side, p), li) for p in K.PERIODS}
        units = [(u, s) for u in (K.UNDS if und == "both" else (und,)) for s in (("C", "P") if side == "both" else (side,))]
        tw = sum(twins[(rule, u, s)][:, li] for u, s in units)
        pe, pn = twin_p(per["final"]["net"], tw)
        folds = [round(sum(t.net[li] for t in select(trades[rule], und, side, "full") if a <= t.day <= b))
                 for a, b in K.FOLDS]
        cells[(rule, und, side, n)] = dict(per=per, p_emp=pe, p_norm=pn, twin_mean=float(tw.mean()), folds=folds)
    keys = list(cells)
    q_all = bh_total([cells[k]["p_norm"] for k in keys], K.M_TOTAL)
    q_own = bh_total([cells[k]["p_norm"] for k in keys], len(keys))
    dec = {}
    for k, qa, qo in zip(keys, q_all, q_own):
        c = cells[k]
        c["q_all"], c["q_own"] = float(qa), float(qo)
        c["fails"] = gate(c["per"]["final"], c["per"]["design"], c["p_emp"], c["q_all"])
        rule, und, side, n = k
        dec[f"{rule}|{und}|{side}|{n}"] = dict(passed=not c["fails"], fails=c["fails"], p_emp=round(c["p_emp"], 4),
                                               p_norm=float(f"{c['p_norm']:.3g}"), q_all=round(c["q_all"], 4),
                                               q_r177=round(c["q_own"], 4),
                                               final_net=round(c["per"]["final"]["net"]),
                                               final_trades=c["per"]["final"]["trades"],
                                               design_net=round(c["per"]["design"]["net"]),
                                               full_net=round(c["per"]["full"]["net"]))
    passed = [k for k, v in dec.items() if v["passed"]]
    head = {n: dec[f"ALL|both|both|{n}"] for n in K.LOTS}
    out = dict(study="R177-EMA", prereg="research/hunt/r177_ema_regime/PREREG.md", m_total=K.M_TOTAL,
               tests=len(K.TESTS), passed=passed,
               verdict="rejected" if not passed else "passed cells listed (see report)",
               headline_all_three={str(n): head[n] for n in K.LOTS}, nofill=nofill, cells=dec)
    _dump(out, K.RESULTS / "decision.json")
    with open(K.OUT / "cells.pkl", "wb") as fh:
        pickle.dump(cells, fh)
    import r177report as REP
    REP.write(cells, nofill, trades)


def run_report():
    with open(K.OUT / "final.pkl", "rb") as fh:
        z = pickle.load(fh)
    evaluate(z["trades"], z["nofill"], z["twins"])


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    {"design": run_design, "final": run_final, "report": run_report}.get(cmd, lambda: print(__doc__))()
