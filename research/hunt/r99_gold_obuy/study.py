"""R99 (PREREG.md): GOLDM intraday option buying - the 104-variant grid, its gates, the luck test and the one holdout read.

    IRAALGO_LAKE=<lake> python3 -m research.hunt.r99_gold_obuy.study design     # -> results/design.md, design.json
    IRAALGO_LAKE=<lake> python3 -m research.hunt.r99_gold_obuy.study holdout    # once (writes results/HOLDOUT_READ.lock)
    IRAALGO_LAKE=<lake> python3 -m research.hunt.r99_gold_obuy.study census     # expiry-day cheap options, descriptive
"""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from datetime import date
from multiprocessing import Pool
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r99_gold_obuy import sim as S

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
LOCK = RES / "HOLDOUT_READ.lock"
DESIGN = (date(2025, 8, 5), date(2026, 7, 14))
HOLDOUT = (date(2026, 7, 15), date(2026, 10, 19))
FOLDS = ((date(2025, 8, 5), date(2025, 10, 31)), (date(2025, 11, 1), date(2026, 1, 31)),
         (date(2026, 2, 1), date(2026, 4, 30)), (date(2026, 5, 1), date(2026, 7, 14)))
IMPULSES = ((100, 5), (150, 15), (200, 15), (300, 30))
TARGETS = (100, 200, 300)
SESSIONS = {"all": S.FIRST_ENTRY_TOD, "evening": 17 * 60}
STRIKES = {"ATM": 0, "1-ITM": 1}
RANGES = {"C1 09:00-10:00": (9 * 60, 10 * 60, 22 * 60), "C2 17:00-18:30": (17 * 60, 18 * 60 + 30, 22 * 60 + 30)}
HOLD = 60
TWINS = 200
SEED = 99
MONTH_STOP, DD_STOP = -13_000.0, -40_000.0


@dataclass(frozen=True)
class Variant:
    fam: str            # "A" fade, "B" follow, "C" range break
    X: int = 0
    W: int = 0
    T: int = 0
    session: str = "all"
    strike: str = "ATM"
    rng: str = ""
    k: int = 0

    @property
    def name(self) -> str:
        if self.fam == "C":
            return f"C {self.rng} | T {self.k}x range | {self.strike}"
        return f"{self.fam} {'fade' if self.fam == 'A' else 'follow'} | {self.X} in {self.W}m | T {self.T} | {self.session} | {self.strike}"


def grid() -> list[Variant]:
    out = []
    for fam in ("A", "B"):
        for X, W in IMPULSES:
            for T in TARGETS:
                for ses in SESSIONS:
                    for st in STRIKES:
                        out.append(Variant(fam, X, W, T, ses, st))
    for r in RANGES:
        for k in (1, 2):
            for st in STRIKES:
                out.append(Variant("C", strike=st, rng=r, k=k))
    return out


_BOOK = None


def book():
    global _BOOK
    if _BOOK is None:
        from research.hunt.r96_mcx_buy import data as D96
        _BOOK = D96.load_book("GOLDM")
    return _BOOK


def days_in(b, period):
    lo, hi = (np.datetime64(pd.Timestamp(x), "ns") for x in period)
    return [d for d in b.m.days if lo <= d <= hi]


def impulse_moves(m, W: int) -> np.ndarray:
    """mv[i] = F[i] - F[the grid minute exactly W minutes earlier, same day]; NaN when that minute is missing."""
    t = m.ts
    j = np.searchsorted(t, t - np.timedelta64(W, "m"))
    ok = (j < len(t)) & (t[np.minimum(j, len(t) - 1)] == t - np.timedelta64(W, "m"))
    ok &= m.day[np.minimum(j, len(t) - 1)] == m.day
    mv = np.full(len(t), np.nan)
    mv[ok] = m.F[ok] - m.F[j[ok]]
    return mv


def window(m, d, v: Variant):
    """(first tod, last tod) of entry signals for the variant on day d."""
    return (SESSIONS[v.session] if v.fam != "C" else RANGES[v.rng][1]), S.entry_end_tod(m, d)


def run_variant(v: Variant, period=DESIGN, last_day=None):
    """The variant's trades over the period (one position at a time). Returns (trades, unfilled)."""
    b = book()
    m = b.m
    last_day = last_day or period[1]
    trades, unfilled = [], 0
    mv = impulse_moves(m, v.W) if v.fam in "AB" else None
    for d in days_in(b, period):
        ix = m.day_ix(d)
        if not len(ix) or m.is_exp[ix[0]]:
            continue
        t0, t1 = window(m, d, v)
        tod = m.tod[ix]
        if v.fam in "AB":
            cand = ix[(tod >= t0) & (tod <= t1) & (np.abs(np.nan_to_num(mv[ix])) >= v.X)]
            free_at = np.datetime64("1970-01-01")
            for i in cand:
                if m.ts[i] < free_at:
                    continue
                side = int(np.sign(mv[i])) * (1 if v.fam == "B" else -1)
                t = S.trade(b, int(i), side, STRIKES[v.strike], v.T, v.T / 2, HOLD, last_day)
                if "net" not in t:
                    unfilled += "unfilled" in t
                    continue
                trades.append(t)
                free_at = m.ts[t["i_out"]] + np.timedelta64(v.W, "m")
        else:
            w0, w1, cut = RANGES[v.rng]
            win = ix[(tod >= w0) & (tod < w1)]
            if len(win) < 30:
                continue
            hi, lo = np.nanmax(m.F[win]), np.nanmin(m.F[win])
            height = hi - lo
            if not height > 0:
                continue
            after = ix[(tod >= w1) & (tod <= min(cut, t1))]
            brk = [i for i in after if m.F[i] > hi or m.F[i] < lo]
            if not brk:
                continue
            i = int(brk[0])
            side = 1 if m.F[i] > hi else -1
            T = v.k * height
            t = S.trade(b, i, side, STRIKES[v.strike], T, T / 2, None, last_day)
            if "net" not in t:
                unfilled += "unfilled" in t
                continue
            t["range"] = float(height)
            trades.append(t)
    for t in trades:
        t["variant"] = v.name
    return trades, unfilled


def twin_p(v: Variant, trades: list, period=DESIGN, n: int = TWINS, seed: int = SEED) -> float:
    """Each trade moved to a random signal minute of its own session window (same side, strike rule, exits)."""
    b = book()
    m = b.m
    rng = np.random.default_rng(seed)
    real = sum(t["net"] for t in trades)
    pools = {}
    for t in trades:
        d = m.day[t["i_sig"]]
        if d not in pools:
            ix = m.day_ix(d)
            t0, t1 = window(m, d, v)
            pools[d] = ix[(m.tod[ix] >= t0) & (m.tod[ix] <= t1)]
    hits = 0
    for _ in range(n):
        tot = 0.0
        for t in trades:
            pool = pools[m.day[t["i_sig"]]]
            T = t.get("range", 0) * v.k if v.fam == "C" else v.T
            for _try in range(20):
                i = int(pool[rng.integers(len(pool))])
                x = S.trade(b, i, t["side"], STRIKES[v.strike], T, T / 2, HOLD if v.fam in "AB" else None, period[1])
                if "net" in x:
                    tot += x["net"]
                    break
        hits += tot >= real
    return (hits + 1) / (n + 1)


def summarize(trades: list, unfilled: int) -> dict:
    if not trades:
        return dict(trades=0, unfilled=unfilled, net=0.0, net2=0.0, net05=0.0)
    df = pd.DataFrame(trades)
    df["month"] = df.day.dt.to_period("M")
    cum = df.sort_values("exit_ts").net.cumsum()
    dd = float((cum - cum.cummax()).min())
    top3 = df.net.nlargest(3).sum()
    byday = df.groupby("day").net.sum().sort_values(ascending=False)
    folds = []
    for a, z in FOLDS:
        f = df[(df.day.dt.date >= a) & (df.day.dt.date <= z)]
        folds.append((len(f), float(f.net.sum())))
    return dict(trades=len(df), unfilled=unfilled, win=float((df.net > 0).mean()),
                avg_win=float(df.net[df.net > 0].mean()) if (df.net > 0).any() else 0.0,
                avg_loss=float(df.net[df.net <= 0].mean()) if (df.net <= 0).any() else 0.0,
                gross=float(df.gross.sum()), cost=float(df.cost.sum()), net=float(df.net.sum()), net2=float(df.net2.sum()),
                net05=float(df.net05.sum()), folds=folds, folds_pos=sum(1 for _, x in folds if x > 0),
                worst_month=float(df.groupby("month").net.sum().min()), max_dd=dd, wo_top3=float(df.net.sum() - top3),
                wo_top5_days=float(df.net.sum() - byday.head(5).sum()), model=float(df.model.mean()), stale=float(df.stale.mean()),
                median_prem=float(df.prem_rs.median()), median_risk=float(df.risk_rs.median()),
                why={k: int(x) for k, x in df.why.value_counts().items()})


def cheap_gates(s: dict) -> bool:
    return s["trades"] >= 30 and s["net"] > 0 and s["net2"] > 0 and s.get("folds_pos", 0) >= 3 and s.get("wo_top3", -1) > 0


def gates(s: dict, p: float, q: float) -> dict:
    return {"1 trades >= 30": s["trades"] >= 30, "2 net > 0 at 1x and 2x": s["net"] > 0 and s["net2"] > 0,
            "3 twin p < 0.05, q <= 0.10": p < 0.05 and q <= 0.10, "4 folds >= 3/4": s.get("folds_pos", 0) >= 3,
            "5 month / drawdown": s.get("worst_month", -1e9) > MONTH_STOP and s.get("max_dd", -1e9) > DD_STOP,
            "6 w/o top 3 > 0": s.get("wo_top3", -1) > 0}


def daily_matrix(results: dict, period=DESIGN) -> tuple[np.ndarray, list]:
    b = book()
    days = [pd.Timestamp(d) for d in days_in(b, period)]
    pos = {d: i for i, d in enumerate(days)}
    names = list(results)
    X = np.zeros((len(days), len(names)))
    for j, nm in enumerate(names):
        for t in results[nm]["trades_list"]:
            X[pos[t["day"]], j] += t["net"]
    return X, names


def _run(v):
    tr, un = run_variant(v)
    return v, tr, un


def md(df: pd.DataFrame) -> str:
    """A markdown table (no tabulate dependency)."""
    cols = list(df.columns)
    out = ["| " + " | ".join(map(str, cols)) + " |", "|" + "---|" * len(cols)]
    for _, r in df.iterrows():
        out.append("| " + " | ".join(str(r[c]) for c in cols) + " |")
    return "\n".join(out)


def rs(x):
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,.0f}"


def design():
    from obuy.overfit import bh, pbo, spa
    V = grid()
    assert len(V) == 104, len(V)
    book()
    with Pool(4) as p:
        out = p.map(_run, V)
    results = {}
    for v, tr, un in out:
        s = summarize(tr, un)
        results[v.name] = dict(v=v, s=s, trades_list=tr)
    # twins only for the variants passing the cheap gates (others p = 1, conservative)
    for nm, r in results.items():
        r["p"] = twin_p(r["v"], r["trades_list"]) if cheap_gates(r["s"]) else 1.0
    names = list(results)
    qs = bh([results[n]["p"] for n in names])
    for n, q in zip(names, qs):
        results[n]["q"] = float(q)
        results[n]["gates"] = gates(results[n]["s"], results[n]["p"], results[n]["q"])
    X, nm2 = daily_matrix(results)
    sp = spa(X, B=2000, mean_block=5.0, seed=SEED)
    pb = pbo(X, S=16, seed=SEED)
    passed = [n for n in names if all(results[n]["gates"].values())] if sp["spa_p"] < 0.05 else []
    best = nm2[sp["best"]]
    RES.mkdir(exist_ok=True)
    rows = []
    for n in names:
        s, r = results[n]["s"], results[n]
        rows.append({"variant": n, "trades": s["trades"], "unfilled": s["unfilled"], "win": f"{s.get('win', 0) * 100:.0f}%",
                     "net": rs(s["net"]), "at 2x": rs(s["net2"]), "at 0.5x": rs(s["net05"]), "gross": rs(s.get("gross", 0)),
                     "costs": rs(s.get("cost", 0)), "folds +": f"{s.get('folds_pos', 0)}/4", "worst month": rs(s.get("worst_month", 0)),
                     "max DD": rs(s.get("max_dd", 0)), "w/o top 3": rs(s.get("wo_top3", 0)), "w/o 5 best days": rs(s.get("wo_top5_days", 0)),
                     "twin p": f"{r['p']:.3f}", "BH q": f"{r['q']:.3f}", "model / stale": f"{s.get('model', 0) * 100:.0f}% / {s.get('stale', 0) * 100:.0f}%",
                     "_net": s["net"]})
    t = pd.DataFrame(rows).sort_values("_net", ascending=False).drop(columns="_net")
    lines = ["# R99 design results (generated by study.py; design 5 Aug 2025 - 14 Jul 2026, GOLDM 1 lot, R85 strict costs)\n",
          f"- Variants: {len(names)} (one BH family). Hansen SPA p = **{sp['spa_p']:.3f}**, White RC p = {sp['rc_p']:.3f}; "
          f"SPA-best: {best}. PBO = {pb['pbo']:.2f}.",
          f"- Variants passing the cheap gates (>= 30 trades, net > 0 at 1x and 2x, >= 3/4 folds, w/o top 3 > 0): "
          f"{sum(cheap_gates(results[n]['s']) for n in names)}.",
          f"- **Passed every design gate: {', '.join(passed) if passed else 'none'}.**", "",
          md(t), ""]
    (RES / "design.md").write_text("\n".join(lines) + "\n")
    js = {n: dict(s=results[n]["s"], p=results[n]["p"], q=results[n]["q"], gates=results[n]["gates"]) for n in names}
    (RES / "design.json").write_text(json.dumps(dict(spa=sp, pbo=pb, best=best, passed=passed, variants=js), indent=1, default=str))
    pd.DataFrame([x for n in names for x in results[n]["trades_list"]]).to_parquet(RES.parent / "design_trades.parquet")
    print("\n".join(lines[:5]))


def holdout():
    if (HERE / "PREREG_ROUND2.md").exists():
        sys.exit("R99 has a round 2: its one holdout read (rounds 1-2) is `study2.py holdout`")
    if LOCK.exists():
        sys.exit(f"the holdout was already read ({LOCK.read_text().strip()}); a second read is refused")
    from obuy.overfit import bh  # noqa: F401
    D = json.loads((RES / "design.json").read_text())
    names = D["passed"] or [D["best"]]
    kind = "passers" if D["passed"] else "the SPA-best variant, descriptive only (no design passer)"
    LOCK.write_text(f"read {pd.Timestamp.now()} for {kind}: {names}\n")
    V = {v.name: v for v in grid()}
    rows = []
    for n in names:
        v = V[n]
        tr, un = run_variant(v, period=HOLDOUT)
        s = summarize(tr, un)
        p = twin_p(v, tr, period=HOLDOUT) if tr else 1.0
        ok = (s["trades"] >= 10 and s["net"] > 0 and s["net2"] > 0 and p <= 0.10 and s.get("worst_month", -1e9) > MONTH_STOP
              and s.get("max_dd", -1e9) > DD_STOP)
        rows.append(dict(variant=n, trades=s["trades"], net=s["net"], net2=s["net2"], win=s.get("win"), twin_p=p,
                         worst_month=s.get("worst_month"), max_dd=s.get("max_dd"), passes=bool(ok and bool(D["passed"]))))
    (RES / "holdout.json").write_text(json.dumps(dict(kind=kind, rows=rows), indent=1, default=str))
    lines = [f"# R99 holdout (15 Jul - 19 Oct 2026), read once: {kind}\n", md(pd.DataFrame(rows)), ""]
    (RES / "holdout.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


def census():
    """Expiry-day cheap options (descriptive): every stored OTM option at 12:00 / 15:00 / 17:00 on the design expiry days,
    its price and the best close / 18:15 close as multiples (no trading rule)."""
    b = book()
    m = b.m
    rows = []
    for d in m.exp_days:
        if not (np.datetime64(pd.Timestamp(DESIGN[0]), "ns") <= d <= np.datetime64(pd.Timestamp(DESIGN[1]), "ns")):
            continue
        j_end = m.at_or_before(d, 18 * 60 + 15)
        for t in (12 * 60, 15 * 60, 17 * 60):
            j = m.at_or_before(d, t)
            if j < 0 or j_end <= j:
                continue
            ks = sorted({float(a[j]) for (c, _), a in m.k_at.items() if np.isfinite(a[j])})
            for cp in (0, 1):
                for k in ks:
                    if (cp == 0 and k <= m.F[j]) or (cp == 1 and k >= m.F[j]):
                        continue
                    lg = m.leg(d, cp, k)
                    if lg is None:
                        continue
                    ii, Vv = lg
                    pre = ii <= j
                    post = (ii > j) & (ii <= j_end)
                    if not pre.any() or not post.any():
                        continue
                    p0 = float(Vv[pre][-1, 3])
                    if p0 <= 0:
                        continue
                    rows.append(dict(day=str(d)[:10], at=f"{t // 60}:00", side="CE" if cp == 0 else "PE", strike=k, px=p0,
                                     best=float(Vv[post, 3].max()) / p0, end=float(Vv[post][-1, 3]) / p0))
    c = pd.DataFrame(rows)
    RES.mkdir(exist_ok=True)
    if c.empty:
        (RES / "census.md").write_text("# R99 expiry-day census: no rows\n")
        return
    c["bucket"] = pd.cut(c.px, [0, 50, 100, 200, 400, 1e9], labels=["<50", "50-100", "100-200", "200-400", "400+"])
    g = c.groupby(["at", "bucket"], observed=True).agg(n=("px", "size"), best_median=("best", "median"),
                                                       best_ge3=("best", lambda x: (x >= 3).mean()),
                                                       end_median=("end", "median"), end_ge2=("end", lambda x: (x >= 2).mean())).round(3)
    lines = ["# R99 expiry-day cheap-option census (descriptive; design expiries)\n",
          f"Expiry days: {c.day.nunique()}; option-marks: {len(c)}. 'best' = the best close to 18:15 / the price at the mark; "
          "'end' = the 18:15 close / the price.\n", md(g.reset_index()), ""]
    (RES / "census.md").write_text("\n".join(lines) + "\n")
    print("\n".join(lines))


if __name__ == "__main__":
    sys.path.insert(0, str(HERE.parents[1]))         # research/ on the path for obuy.overfit
    {"design": design, "holdout": holdout, "census": census}[sys.argv[1]]()
