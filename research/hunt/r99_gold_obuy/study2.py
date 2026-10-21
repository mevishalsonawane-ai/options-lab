"""R99 round 2 (PREREG_ROUND2.md): GOLDM option buying, at most one trade a day, long holds - the 44-variant grid, the
luck test over rounds 1-2 together (148 variants) and the one holdout read for the whole of R99.

    IRAALGO_LAKE=<lake> python3 -m research.hunt.r99_gold_obuy.study2 design     # -> results/design2.md, design2.json
    IRAALGO_LAKE=<lake> python3 -m research.hunt.r99_gold_obuy.study2 holdout    # once (results/HOLDOUT_READ.lock)

Round 1's engine (sim.trade), fills, marks, costs, summaries and gates are reused unchanged (study.py).
"""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from multiprocessing import Pool

import numpy as np
import pandas as pd

from research.hunt.r99_gold_obuy import sim as S
from research.hunt.r99_gold_obuy import study as R1

RES, LOCK, DESIGN, HOLDOUT, SEED, TWINS = R1.RES, R1.LOCK, R1.DESIGN, R1.HOLDOUT, R1.SEED, R1.TWINS
D1_WINDOWS = {"09:00-09:30": (9 * 60, 9 * 60 + 30, 22 * 60), "09:00-10:00": (9 * 60, 10 * 60, 22 * 60),
              "09:00-11:00": (9 * 60, 11 * 60, 22 * 60)}
D2_WINDOWS = {"15:00-17:00": (15 * 60, 17 * 60, 22 * 60 + 30), "17:00-17:30": (17 * 60, 17 * 60 + 30, 22 * 60 + 30)}
OPEN_TOD = 9 * 60
E_CLOCKS = (17 * 60, 19 * 60)
E_K = (0.25, 0.5)
E_LOOKBACK, E_MIN_HIST = 20, 10
IMPULSES2 = ((400, 30), (600, 60))
F_HOLDS = (120, None)            # None = the square-off
G_HOLDS = (60, 120)
INF = float("inf")


@dataclass(frozen=True)
class V2:
    fam: str                     # D1, D2, E, F, G
    strike: str = "ATM"
    win: str = ""                # D1 / D2
    exit: str = "sq"             # D1 / D2: "sq" (no target) or "2x" (2 x the range)
    clock: int = 0               # E
    k: float = 0.0               # E
    X: int = 0                   # F / G
    W: int = 0
    H: int | None = None         # F / G hold (minutes; None = the square-off)

    @property
    def name(self) -> str:
        if self.fam in ("D1", "D2"):
            return f"{self.fam} ORB {self.win} | {'square-off' if self.exit == 'sq' else 'T 2x range'} | {self.strike}"
        if self.fam == "E":
            return f"E day trend at {self.clock // 60:02d}:{self.clock % 60:02d} | k {self.k} | {self.strike}"
        h = "square-off" if self.H is None else f"{self.H}m"
        return f"{self.fam} {'follow' if self.fam == 'F' else 'fade'} {self.X} in {self.W}m | {h} | {self.strike}"


def grid2() -> list[V2]:
    out = []
    for fam, wins in (("D1", D1_WINDOWS), ("D2", D2_WINDOWS)):
        for w in wins:
            for ex in ("sq", "2x"):
                for st in R1.STRIKES:
                    out.append(V2(fam, st, win=w, exit=ex))
    for c in E_CLOCKS:
        for k in E_K:
            for st in R1.STRIKES:
                out.append(V2("E", st, clock=c, k=k))
    for fam, holds in (("F", F_HOLDS), ("G", G_HOLDS)):
        for X, W in IMPULSES2:
            for H in holds:
                for st in R1.STRIKES:
                    out.append(V2(fam, st, X=X, W=W, H=H))
    return out


_RANGES = None


def daily_ranges(m) -> dict:
    """max - min of the future's minute closes, per session of the book."""
    global _RANGES
    if _RANGES is None:
        _RANGES = {}
        for d in m.days:
            f = m.F[m.day_ix(d)]
            f = f[np.isfinite(f)]
            _RANGES[d] = float(f.max() - f.min()) if len(f) else np.nan
    return _RANGES


def median_range_before(m, d) -> float:
    """The median daily range of the E_LOOKBACK sessions before d (NaN with fewer than E_MIN_HIST)."""
    rg = daily_ranges(m)
    prior = [rg[x] for x in m.days if x < d][-E_LOOKBACK:]
    prior = [x for x in prior if np.isfinite(x)]
    return float(np.median(prior)) if len(prior) >= E_MIN_HIST else np.nan


def entry_window(m, d, v: V2) -> tuple[int, int]:
    """(first tod, last tod) of the variant's entry signals on day d - also the twins' window."""
    t1 = S.entry_end_tod(m, d)
    if v.fam in ("D1", "D2"):
        w0, w1, cut = (D1_WINDOWS if v.fam == "D1" else D2_WINDOWS)[v.win]
        return w1, min(cut, t1)
    if v.fam == "E":
        return 10 * 60, t1
    return S.FIRST_ENTRY_TOD, t1


def hold_of(v: V2):
    return v.H if v.fam in ("F", "G") else None


def signals(m, d, ix, v: V2, mv=None) -> list[tuple[int, int, float, float]]:
    """The day's candidate signals in order: (grid minute, side, target distance, stop distance). The caller takes the
    first that fills (at most one trade a day). Every value is read at or before the signal minute."""
    tod = m.tod[ix]
    t0, t1 = entry_window(m, d, v)
    if v.fam in ("D1", "D2"):
        w0, w1, _ = (D1_WINDOWS if v.fam == "D1" else D2_WINDOWS)[v.win]
        win = ix[(tod >= w0) & (tod < w1)]
        f = m.F[win]
        f = f[np.isfinite(f)]
        if len(f) < (w1 - w0) // 2:
            return []
        hi, lo = float(f.max()), float(f.min())
        height = hi - lo
        if not height > 0:
            return []
        out = []
        for i in ix[(tod >= t0) & (tod <= t1)]:
            x = m.F[i]
            if not np.isfinite(x) or lo <= x <= hi:
                continue
            side = 1 if x > hi else -1
            stop = (x - lo) if side > 0 else (hi - x)
            out.append((int(i), side, INF if v.exit == "sq" else 2 * height, float(stop)))
        return out
    if v.fam == "E":
        f0 = ix[(tod >= OPEN_TOD) & np.isfinite(m.F[ix])]
        at = ix[(tod >= v.clock) & (tod <= v.clock + S.FILL_WAIT) & (tod <= t1) & np.isfinite(m.F[ix])]
        if not len(f0) or not len(at):
            return []
        i, op = int(at[0]), float(m.F[f0[0]])
        if not m.tod[f0[0]] < m.tod[i]:
            return []
        med = median_range_before(m, d)
        move = float(m.F[i]) - op
        if not np.isfinite(med) or abs(move) < v.k * med or move == 0:
            return []
        return [(i, int(np.sign(move)), INF, 0.5 * abs(move))]
    out = []
    for i in ix[(tod >= t0) & (tod <= t1) & (np.abs(np.nan_to_num(mv[ix])) >= v.X)]:
        a = float(mv[i])
        if v.fam == "F":
            out.append((int(i), int(np.sign(a)), INF, abs(a)))
        else:
            out.append((int(i), -int(np.sign(a)), 0.5 * abs(a), 0.5 * abs(a)))
    return out


def run_variant2(v: V2, period=DESIGN, last_day=None):
    """The variant's trades over the period (at most one a day: the first signal that fills). Returns (trades, unfilled)."""
    b = R1.book()
    m = b.m
    last_day = last_day or period[1]
    trades, unfilled = [], 0
    mv = R1.impulse_moves(m, v.W) if v.fam in ("F", "G") else None
    for d in R1.days_in(b, period):
        ix = m.day_ix(d)
        if not len(ix) or m.is_exp[ix[0]]:
            continue
        for i, side, tgt, stp in signals(m, d, ix, v, mv):
            t = S.trade(b, i, side, R1.STRIKES[v.strike], tgt, stp, hold_of(v), last_day)
            if "net" not in t:
                unfilled += "unfilled" in t
                continue
            t.update(variant=v.name, target_d=tgt, stop_d=stp)
            trades.append(t)
            break
    return trades, unfilled


def twin_p2(v: V2, trades: list, period=DESIGN, n: int = TWINS, seed: int = SEED) -> float:
    """Each trade moved to a random minute of its session's entry window: same side, strike rule, distances and hold."""
    b = R1.book()
    m = b.m
    rng = np.random.default_rng(seed)
    real = sum(t["net"] for t in trades)
    pools = {}
    for t in trades:
        d = m.day[t["i_sig"]]
        if d not in pools:
            ix = m.day_ix(d)
            t0, t1 = entry_window(m, d, v)
            pools[d] = ix[(m.tod[ix] >= t0) & (m.tod[ix] <= t1)]
    hits = 0
    for _ in range(n):
        tot = 0.0
        for t in trades:
            pool = pools[m.day[t["i_sig"]]]
            for _try in range(20):
                i = int(pool[rng.integers(len(pool))])
                x = S.trade(b, i, t["side"], R1.STRIKES[v.strike], t["target_d"], t["stop_d"], hold_of(v), period[1])
                if "net" in x:
                    tot += x["net"]
                    break
        hits += tot >= real
    return (hits + 1) / (n + 1)


def _run(v):
    tr, un = run_variant2(v)
    return v, tr, un


def round1_daily(days: list) -> tuple[np.ndarray, list, list]:
    """Round 1's per-variant daily net on the design days (from its trade file; re-run if missing) and its twin p's."""
    D = json.loads((RES / "design.json").read_text())
    names = list(D["variants"])
    f = RES.parent / "design_trades.parquet"
    if f.exists():
        df = pd.read_parquet(f)
    else:
        with Pool(4) as p:
            out = p.map(R1._run, R1.grid())
        df = pd.DataFrame([t for _, tr, _ in out for t in tr])
    pos = {d: i for i, d in enumerate(days)}
    X = np.zeros((len(days), len(names)))
    col = {nm: j for j, nm in enumerate(names)}
    for nm, day, net in zip(df.variant, pd.to_datetime(df.day), df.net):
        X[pos[day], col[nm]] += net
    for nm in names:     # the file holds every round-1 trade: its totals must match design.json's
        assert abs(X[:, col[nm]].sum() - D["variants"][nm]["s"]["net"]) < 1.0, nm
    return X, names, [D["variants"][n]["p"] for n in names]


def design():
    from obuy.overfit import bh, pbo, spa
    V = grid2()
    assert len(V) == 44, len(V)
    b = R1.book()
    daily_ranges(b.m)
    with Pool(4) as p:
        out = p.map(_run, V)
    res = {}
    for v, tr, un in out:
        res[v.name] = dict(v=v, s=R1.summarize(tr, un), trades_list=tr)
    for r in res.values():
        r["p"] = twin_p2(r["v"], r["trades_list"]) if R1.cheap_gates(r["s"]) else 1.0
    days = [pd.Timestamp(d) for d in R1.days_in(b, DESIGN)]
    X1, n1, p1 = round1_daily(days)
    n2 = list(res)
    pos = {d: i for i, d in enumerate(days)}
    X2 = np.zeros((len(days), len(n2)))
    for j, nm in enumerate(n2):
        for t in res[nm]["trades_list"]:
            X2[pos[t["day"]], j] += t["net"]
    X = np.hstack([X1, X2])
    allnames = n1 + n2
    qs = bh(p1 + [res[n]["p"] for n in n2])
    q2 = dict(zip(allnames, qs))
    for n in n2:
        res[n]["q"] = float(q2[n])
        res[n]["gates"] = R1.gates(res[n]["s"], res[n]["p"], res[n]["q"])
    sp = spa(X, B=2000, mean_block=5.0, seed=SEED)
    sp2 = spa(X2, B=2000, mean_block=5.0, seed=SEED)
    pb = pbo(X, S=16, seed=SEED)
    best = allnames[sp["best"]]
    passed = [n for n in n2 if all(res[n]["gates"].values())] if sp["spa_p"] < 0.05 else []
    # a round-1 variant passes only with the cumulative SPA and its cumulative BH q (its round-1 gates otherwise stand)
    D1j = json.loads((RES / "design.json").read_text())
    if sp["spa_p"] < 0.05:
        for n in n1:
            s = D1j["variants"][n]["s"]
            if all(R1.gates(s, D1j["variants"][n]["p"], float(q2[n])).values()):
                passed.append(n)
    near = [n for n in n2 if res[n]["s"]["trades"] >= 30 and res[n]["s"]["net"] > 0 and res[n]["s"]["net2"] > 0
            and res[n]["s"].get("folds_pos", 0) >= 3]
    rows = []
    for n in n2:
        s, r = res[n]["s"], res[n]
        rows.append({"variant": n, "trades": s["trades"], "unfilled": s["unfilled"], "win": f"{s.get('win', 0) * 100:.0f}%",
                     "net": R1.rs(s["net"]), "at 2x": R1.rs(s["net2"]), "at 0.5x": R1.rs(s["net05"]), "gross": R1.rs(s.get("gross", 0)),
                     "costs": R1.rs(s.get("cost", 0)), "folds +": f"{s.get('folds_pos', 0)}/4",
                     "worst month": R1.rs(s.get("worst_month", 0)), "max DD": R1.rs(s.get("max_dd", 0)),
                     "w/o top 3": R1.rs(s.get("wo_top3", 0)), "w/o 5 best days": R1.rs(s.get("wo_top5_days", 0)),
                     "twin p": f"{r['p']:.3f}", "BH q (148)": f"{r['q']:.3f}",
                     "model / stale": f"{s.get('model', 0) * 100:.0f}% / {s.get('stale', 0) * 100:.0f}%", "_net": s["net"]})
    t = pd.DataFrame(rows).sort_values("_net", ascending=False).drop(columns="_net")
    lines = ["# R99 round 2 design results (generated by study2.py; design 5 Aug 2025 - 14 Jul 2026, GOLDM 1 lot, R85 strict costs)\n",
             f"- Round 2: {len(n2)} variants (at most one trade a day, long holds). Its own SPA p = {sp2['spa_p']:.3f}.",
             f"- **Rounds 1-2 together ({len(allnames)} variants): Hansen SPA p = {sp['spa_p']:.3f}**, White RC p = {sp['rc_p']:.3f}; "
             f"SPA-best: {best}. PBO = {pb['pbo']:.2f}.",
             f"- Round-2 variants passing the cheap gates (>= 30 trades, net > 0 at 1x and 2x, >= 3/4 folds, w/o top 3 > 0): "
             f"{sum(R1.cheap_gates(res[n]['s']) for n in n2)}.",
             f"- Near-misses (>= 30 trades, net > 0 at 1x and 2x, >= 3/4 folds): {', '.join(near) if near else 'none'}.",
             f"- **Passed every design gate: {', '.join(passed) if passed else 'none'}.**", "",
             R1.md(t), ""]
    (RES / "design2.md").write_text("\n".join(lines) + "\n")
    js = {n: dict(s=res[n]["s"], p=res[n]["p"], q=res[n]["q"], gates=res[n]["gates"]) for n in n2}
    (RES / "design2.json").write_text(json.dumps(dict(spa=sp, spa_round2=sp2, pbo=pb, best=best, passed=passed, near=near,
                                                      variants=js), indent=1, default=str))
    pd.DataFrame([x for n in n2 for x in res[n]["trades_list"]]).to_parquet(RES.parent / "design2_trades.parquet")
    print("\n".join(lines[:7]))


def holdout():
    """The one holdout read of R99 (rounds 1-2): every design passer, else the cumulative SPA-best as a description."""
    if LOCK.exists():
        sys.exit(f"the holdout was already read ({LOCK.read_text().strip()}); a second read is refused")
    D = json.loads((RES / "design2.json").read_text())
    names = D["passed"] or [D["best"]]
    kind = "passers" if D["passed"] else "the cumulative SPA-best variant, descriptive only (no design passer)"
    LOCK.write_text(f"read {pd.Timestamp.now()} for {kind}: {names}\n")
    V1 = {v.name: v for v in R1.grid()}
    V2s = {v.name: v for v in grid2()}
    rows = []
    for n in names:
        if n in V2s:
            v = V2s[n]
            tr, un = run_variant2(v, period=HOLDOUT)
            p = twin_p2(v, tr, period=HOLDOUT) if tr else 1.0
        else:
            v = V1[n]
            tr, un = R1.run_variant(v, period=HOLDOUT)
            p = R1.twin_p(v, tr, period=HOLDOUT) if tr else 1.0
        s = R1.summarize(tr, un)
        ok = (s["trades"] >= 10 and s["net"] > 0 and s["net2"] > 0 and p <= 0.10 and s.get("worst_month", -1e9) > R1.MONTH_STOP
              and s.get("max_dd", -1e9) > R1.DD_STOP)
        rows.append(dict(variant=n, trades=s["trades"], win=s.get("win"), net=s["net"], net2=s["net2"], gross=s.get("gross"),
                         twin_p=p, worst_month=s.get("worst_month"), max_dd=s.get("max_dd"), wo_top3=s.get("wo_top3"),
                         passes=bool(ok and bool(D["passed"]))))
    (RES / "holdout.json").write_text(json.dumps(dict(kind=kind, rows=rows), indent=1, default=str))
    lines = [f"# R99 holdout (15 Jul - 19 Oct 2026), read once for rounds 1-2: {kind}\n", R1.md(pd.DataFrame(rows)), ""]
    (RES / "holdout.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    {"design": design, "holdout": holdout}[sys.argv[1]]()
