"""R189 study (PREREG.md): the 16 exit candidates on Boss's Hull-line entry, the design-only exit choice, the three
entry variants with the picked exit, the one final read, the twins, the gates and the report.

    python3 -I research/hunt/r189_hull_bbi/r189study.py design   # design period only (20 Nov 2024 - 31 Mar 2026)
    python3 -I research/hunt/r189_hull_bbi/r189study.py final    # the ONE final read (writes results/FINAL_READ first)
    python3 -I research/hunt/r189_hull_bbi/r189study.py report   # re-write the report from the cached final results

Pure helpers (stats, months, drawdown, BH, gates, the exit choice and its walk-forward, the twin sampler) are tested in
test_r189.py. Plain-English notes, 16 Nov 2026.
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
import r189cfg as K  # noqa: E402

HEAD = ("all", "both", 1)          # the headline cell: all five indices, calls + puts, 1 lot


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


def norm_tail(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2.0))


def twin_p(rule_net: float, twins) -> tuple:
    """(empirical p, normal-tail p) of the rule's net against its twin totals (one-sided)."""
    twins = np.asarray(twins, float)
    k = int((twins >= rule_net - 1e-9).sum())
    pe = (1 + k) / (len(twins) + 1)
    sd = float(twins.std(ddof=1)) if len(twins) > 1 else 0.0
    if sd <= 0:
        pn = 1.0 if rule_net <= float(twins.mean() if len(twins) else 0.0) else 0.0
    else:
        pn = norm_tail((rule_net - float(twins.mean())) / sd)
    return pe, pn


def twin_ranks(nd: int, nb: int, n: int, rng) -> list:
    """Per day, a random order of the nb allowed candles for each of n twins (drawn once per unit: every rule set's
    twins use the same draws, common random numbers)."""
    return [np.argsort(rng.random((n, nb)), axis=1).astype(np.int16) for _ in range(nd)]


def twin_draws(table: np.ndarray, counts, ranks: list) -> np.ndarray:
    """Twin totals (n, nlots): table (ndays, nb, nlots) of each (day, candle)'s net (0 = no fill); counts (ndays,) the
    rule's entries a day; each twin takes the first counts[d] candles of its random order (distinct candles)."""
    nd, nb, nl = table.shape
    n = ranks[0].shape[0] if ranks else 0
    out = np.zeros((n, nl))
    for d in range(nd):
        k = min(int(counts[d]), nb)
        if k <= 0:
            continue
        pick = ranks[d][:, :k]
        out += table[d][pick].sum(axis=1)
    return out


def select(trades: list, und: str, side: str, period: str) -> list:
    return [t for t in trades if (und == "all" or t.und == und) and (side == "both" or t.side == side)
            and K.in_period(t.day, period)]


def fold_nets(trades: list, li: int = 0) -> list:
    return [float(sum(t.net[li] for t in trades if a <= t.day <= b)) for a, b in K.FOLDS]


def rank_exits(nets: dict) -> list:
    """The exit choice (PREREG section 4): candidates with every given fold > 0 first, by highest median fold net;
    then the rest by highest total; ties by the lower X number. nets: {exit: [fold nets]}."""
    xs = sorted(nets)
    good = [x for x in xs if nets[x] and all(v > 0 for v in nets[x])]
    rest = [x for x in xs if x not in good]
    good.sort(key=lambda x: (-float(np.median(nets[x])), x))
    rest.sort(key=lambda x: (-float(sum(nets[x])), x))
    return good + rest


def selection_walk_forward(nets: dict) -> tuple:
    """(walk-forward net, picks): for fold k = 2..4, pick by rank_exits on folds 1..k-1 and take the pick's fold-k net."""
    total, picks = 0.0, []
    nf = len(next(iter(nets.values())))
    for k in range(1, nf):
        p = rank_exits({x: v[:k] for x, v in nets.items()})[0]
        picks.append(p)
        total += nets[p][k]
    return total, picks


def gate(final: dict, design_wf: float, design: dict, pe: float, q: float) -> list:
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
    if not design_wf > 0:
        fails.append("design walk-forward net <= 0")
    bad = [p for p, s in (("design", design), ("final", final))
           if s["max_dd"] > K.MAX_DD or s["worst_month"] < K.WORST_MONTH]
    if bad:
        fails.append("outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in " + ", ".join(bad))
    return fails


# ================================================================================================ running
def _dump(obj, path: Path):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=1, default=str))


def run_rules(rules: dict, lo: date, hi: date, with_twins: bool = False, pick: str | None = None) -> tuple:
    """Walk every rule set on every index (one index in memory at a time). Returns trades {rule: [Trade]}, nofill
    {rule: {und: n}} and, with twins, {(rule, und, side): (TWINS, 3)} over the final period."""
    import r189data as D
    import r189lib as Z
    trades = {r: [] for r in rules}
    nofill = {r: {} for r in rules}
    twins = {}
    for ui, u in enumerate(K.UNDS):
        U = D.load(u)
        for r, rdef in rules.items():
            t, nf = Z.walk(U, r, rdef, lo, hi)
            trades[r] += t
            nofill[r][u] = nf
        print(f"{u}: walked {len(rules)} rule sets", flush=True)
        if with_twins:
            dis = [di for di, d in enumerate(U.days) if K.in_period(d, "final") and U.tradable(di)]
            nb = K.LAST_T - K.FIRST_T + 1
            tables = {}
            for si, side in enumerate(("C", "P")):
                ranks = twin_ranks(len(dis), nb, K.TWINS, np.random.default_rng([K.SEED, ui, si]))
                for r, (line, reading, money, x) in rules.items():
                    tk = (line if K.EXITS[x]["line"] else "-", money, x)
                    if tk not in tables:
                        tables[tk] = {s: None for s in "CP"}
                    if tables[tk][side] is None:
                        tables[tk][side] = Z.twin_table(U, side, money, line, x, dis)
                    cnt = defaultdict(int)
                    for t in trades[r]:
                        if t.und == u and t.side == side and K.in_period(t.day, "final"):
                            cnt[t.day] += 1
                    counts = np.array([cnt.get(U.days[di], 0) for di in dis])
                    twins[(r, u, side)] = twin_draws(tables[tk][side], counts, ranks)
                del ranks
            print(f"{u}: twins done ({len(tables)} tables)", flush=True)
        del U
    return trades, nofill, twins


def design_choice(trades: dict) -> dict:
    """The exit choice on the headline cell's design folds (PREREG section 4)."""
    u, s, n = HEAD
    nets = {r.split("-", 1)[1]: fold_nets(select(trades[r], u, s, "design"), K.LOTS.index(n))
            for r in trades if r.startswith("BOSS-")}
    order = rank_exits(nets)
    wf, picks = selection_walk_forward(nets)
    qualified = [x for x in order if all(v > 0 for v in nets[x])]
    return dict(order=order, pick=order[0], next_two=order[1:3], fold_nets={x: [round(v) for v in nets[x]] for x in order},
                qualified=qualified, rule=("best median fold net among exits with all 4 folds > 0" if qualified
                                           else "no exit had all 4 folds > 0: best design net"),
                walk_forward=dict(net=round(wf), picks=picks))


def run_design():
    if K.FINAL_FLAG.exists():
        print("note: the final read was already done; design re-run only", flush=True)
    rules = K.rule_sets(None)
    trades, nofill, _ = run_rules(rules, K.START, K.DESIGN_END)
    ch = design_choice(trades)
    var = {k: v for k, v in K.rule_sets(ch["pick"]).items() if k not in rules}
    t2, nf2, _ = run_rules(var, K.START, K.DESIGN_END)
    trades.update(t2)
    nofill.update(nf2)
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / "design.pkl", "wb") as fh:
        pickle.dump(dict(trades=trades, nofill=nofill, choice=ch), fh)
    out = dict(choice=ch, rules={})
    for r in trades:
        for und in K.IDX_SCOPES:
            for side in K.SIDE_SCOPES:
                for li, n in enumerate(K.LOTS):
                    sel = select(trades[r], und, side, "design")
                    s = {k: v for k, v in stats(sel, li).items() if k not in ("months",)}
                    s["folds"] = [round(v) for v in fold_nets(sel, li)]
                    out["rules"][f"{r}|{und}|{side}|{n}"] = s
    _dump(out, K.RESULTS / "design.json")
    print("exit order:", ch["order"], "pick:", ch["pick"], ch["rule"], flush=True)
    for r in trades:
        s = out["rules"][f"{r}|all|both|1"]
        print(f"{r:10s} design 1 lot: {s['trades']:6d} trades  win {s['win']:4.1f}%  net {s['net']:>12,.0f}  "
              f"folds {s['folds']}", flush=True)


def run_final():
    if K.FINAL_FLAG.exists():
        raise SystemExit(f"{K.FINAL_FLAG} exists: the final period was already read once (PREREG section 4)")
    dj = json.loads((K.RESULTS / "design.json").read_text())
    pick = dj["choice"]["pick"]
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text(f"R189 final period (1 Apr - 6 Oct 2026) read once, 16 Nov 2026, picked exit {pick}.\n")
    rules = K.rule_sets(pick)
    trades, nofill, twins = run_rules(rules, K.START, K.END, with_twins=True)
    ch = design_choice({r: v for r, v in trades.items() if r.startswith("BOSS-")})
    assert ch["pick"] == pick, "the design choice must reproduce on the full run"
    K.OUT.mkdir(parents=True, exist_ok=True)
    with open(K.OUT / "final.pkl", "wb") as fh:
        pickle.dump(dict(trades=trades, nofill=nofill, twins=twins, choice=ch), fh)
    evaluate(trades, nofill, twins, ch)


def evaluate(trades: dict, nofill: dict, twins: dict, ch: dict):
    pick = ch["pick"]
    picks_wf = ch["walk_forward"]["picks"]
    cells = {}
    for r in trades:
        for und in K.IDX_SCOPES:
            for side in K.SIDE_SCOPES:
                for n in K.LOTS:
                    li = K.LOTS.index(n)
                    per = {p: stats(select(trades[r], und, side, p), li) for p in K.PERIODS}
                    units = [(u, s) for u in (K.UNDS if und == "all" else (und,))
                             for s in (("C", "P") if side == "both" else (side,))]
                    tw = sum(twins[(r, u, s)][:, li] for u, s in units)
                    pe, pn = twin_p(per["final"]["net"], tw)
                    folds = fold_nets(select(trades[r], und, side, "design"), li)
                    if r == f"BOSS-{pick}":
                        # the picked exit: the selection walk-forward, applied to this cell's own fold nets
                        fn = {x: fold_nets(select(trades[f"BOSS-{x}"], und, side, "design"), li) for x in K.EXITS}
                        wf = float(sum(fn[p][k + 1] for k, p in enumerate(picks_wf)))
                    else:
                        wf = per["design"]["net"]
                    cells[(r, und, side, n)] = dict(per=per, p_emp=pe, p_norm=pn, twin_mean=float(tw.mean()),
                                                    twin_sd=float(tw.std(ddof=1)), folds=folds, wf=wf)
    keys = list(cells)
    q_all = bh_total([cells[k]["p_norm"] for k in keys], K.M_TOTAL)
    q_own = bh_total([cells[k]["p_norm"] for k in keys], len(keys))
    dec = {}
    for k, qa, qo in zip(keys, q_all, q_own):
        c = cells[k]
        c["q_all"], c["q_own"] = float(qa), float(qo)
        c["fails"] = gate(c["per"]["final"], c["wf"], c["per"]["design"], c["p_emp"], c["q_all"])
        r, und, side, n = k
        dec[f"{r}|{und}|{side}|{n}"] = dict(passed=not c["fails"], fails=c["fails"], p_emp=round(c["p_emp"], 4),
                                            p_norm=float(f"{c['p_norm']:.3g}"), q_all=round(c["q_all"], 4),
                                            q_r189=round(c["q_own"], 4), final_net=round(c["per"]["final"]["net"]),
                                            final_trades=c["per"]["final"]["trades"],
                                            design_net=round(c["per"]["design"]["net"]), design_wf=round(c["wf"]),
                                            full_net=round(c["per"]["full"]["net"]))
    verdict_rules = [f"BOSS-{pick}"] + [f"{v}-{pick}" for v in K.VARIANTS]
    passed = [k for k, v in dec.items() if v["passed"]]
    passed_verdict = [k for k in passed if k.split("|")[0] in verdict_rules]
    head = {n: dec[f"BOSS-{pick}|all|both|{n}"] for n in K.LOTS}
    out = dict(study="R189", prereg="research/hunt/r189_hull_bbi/PREREG.md", data_note="1-min only, no 10-sec data",
               m_total=K.M_TOTAL, tests=len(keys), exit_choice=ch, picked_exit=pick, verdict_rules=verdict_rules,
               passed_verdict_cells=passed_verdict, passed_any_cells=passed,
               verdict="rejected" if not passed_verdict else "passed cells listed (see report)",
               headline={str(n): head[n] for n in K.LOTS}, nofill=nofill, cells=dec)
    _dump(out, K.RESULTS / "decision.json")
    with open(K.OUT / "cells.pkl", "wb") as fh:
        pickle.dump(cells, fh)
    import r189report as REP
    REP.write(cells, nofill, trades, ch)


def run_report():
    with open(K.OUT / "final.pkl", "rb") as fh:
        z = pickle.load(fh)
    evaluate(z["trades"], z["nofill"], z["twins"], z["choice"])


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    {"design": run_design, "final": run_final, "report": run_report}.get(cmd, lambda: print(__doc__))()
