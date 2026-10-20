"""R89B study (PREREG_B.md): 9 enhanced variants x 5 indices, design 20 Nov 2024 - 19 Oct 2026, then the ONE holdout read
on 2 Aug 2021 - 19 Nov 2024.

    IRAALGO_LAKE=~/lake python -m research.hunt.r89_pdhl_break.study_b design
    IRAALGO_LAKE=~/lake python -m research.hunt.r89_pdhl_break.study_b final      # refuses a second read

Results (small JSON) go to results_b/; trade lists to $R89_OUT/trades_b (never git).
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from . import cfg as K
from . import data, price, rules, rules_b, store
from .study import bh, stats

RESULTS = K.HERE / "results_b"
FINAL_FLAG = RESULTS / "FINAL_READ"
DESIGN = (date(2024, 11, 20), date(2026, 10, 19))
HOLDOUT = (date(2021, 8, 2), date(2024, 11, 19))
FOLDS = ((date(2024, 12, 1), date(2025, 5, 31)), (date(2025, 6, 1), date(2025, 11, 30)),
         (date(2025, 12, 1), date(2026, 5, 31)), (date(2026, 6, 1), date(2026, 10, 19)))
BLOCKS = ((date(2021, 8, 2), date(2022, 12, 31)), (date(2023, 1, 1), date(2023, 12, 31)),
          (date(2024, 1, 1), date(2024, 11, 19)))
_OPEN = False


def check(lo: date, hi: date):
    if lo < DESIGN[0] and not _OPEN:
        raise PermissionError(f"R89B: {lo} is in the locked holdout (to {HOLDOUT[1]}); only `study_b.py final` reads it")


class World:
    """The five index panels of one period, their stores, ATRs and first-break tables (for BREADTH)."""

    def __init__(self, which: str):
        self.which = which
        self.P = {u: data.panel(u if which == "new" else f"B_OLD_{u}") for u in K.INDICES}
        self.S = {u: store.load(u, which) for u in K.INDICES}
        self.ATR = {u: rules_b.atr15(P) for u, P in self.P.items()}
        self.fb = {u: rules_b.first_breaks(P) for u, P in self.P.items()}
        self._G: dict = {}

    def gen(self, u: str, tf: int):
        k = (u, tf)
        if k not in self._G:
            G = rules.gen_triggers(self.P[u], "C", tf, False, False)
            self._G[k] = (G, rules.Swings(G["C"], 5))
        return self._G[k]


def _range(P, lo, hi):
    check(lo, hi)
    ix = [i for i, d in enumerate(P.days) if lo <= d <= hi]
    return (ix[0], ix[-1]) if ix else (1, 0)


def twins_b(W: World, u: str, v, trades: list, n: int, rng, kind: str) -> np.ndarray:
    P, S = W.P[u], W.S[u]
    first, last = rules_b.window_first(v), K.NSE_LAST
    tot = np.zeros(n)
    for tr in trades:
        d = tr["day"]
        pool = price._pool(P, d, first, last)
        if not len(pool):
            continue
        s, sq, dt = tr["side"], int(P.sq[d]), P.days[d]
        tt = pool[rng.integers(0, len(pool), size=n)]
        rk = np.full(n, tr["risk"])
        if v.exit == "swing":
            xm, why = price._twin_exits(P.mo, P.mh, P.ml, P.mc, tt, s, rk, np.full(n, tr["reward"]), sq)
            ref = P.mc[tt]
            lv = np.where(why == 1, ref - s * rk, ref + s * tr["reward"])
        else:
            xm, why, lv = rules_b.run_exits_many(P.mo, P.mh, P.ml, P.mc, tt, s, rk, sq)
        if kind == "opt":
            if dt not in S or S[dt].atm is None:
                continue
            lot = float(price.K51.lot(u, dt))
            rows = (P.mt[tt] - K.NSE_OPEN).astype(np.int64)
            fx = np.where(why != 3, P.mt[xm] - K.NSE_OPEN + 1, -1)
            net, *_ = price.idx_option_day(u, S[dt], ["C" if s > 0 else "P"] * n, rows, fx, [lot] * n)
            tot += net
        else:
            lot = float(price.K51.lot(u, dt))
            for i in range(n):
                w = {1: "stop", 2: "target", 3: "time", 4: "stop"}[int(why[i])]
                tot[i] += price._fut_twin_net(P, dict(t=int(tt[i]), xm=int(xm[i]), side=s, stop=float(lv[i]),
                                                      target=float(lv[i]), why=w), lot, dt)
    return tot


def run_cell(W: World, u: str, v, lo: date, hi: date, twins: int = K.TWINS):
    P = W.P[u]
    a, b = _range(P, lo, hi)
    G, SW = W.gen(u, v.tf)
    T = rules_b.plan_b(P, v, G, SW, W.ATR[u], W.fb, a, b)
    # only sessions with option data can trade (holdout: MIDCPNIFTY / SENSEX options start in 2023)
    T = [x for x in T if P.days[x["day"]] in W.S[u] and W.S[u][P.days[x["day"]]].atm is not None]
    O = price.idx_option(P, u, T, W.S[u], "lot")
    R = price.idx_option(P, u, T, W.S[u], "risk")
    rows = []
    for o, r in zip(O, R):
        dt = P.days[o["day"]]
        lot = float(price.K51.lot(u, dt))
        f = price.fut_trade(P, dict(o, stop=o["xlv"], why="stop" if o["why"] == "trail" else o["why"]), lot, dt)
        held = int(P.mt[o["xm"]]) - int(P.mt[o["t"]])
        rows.append(dict(o, date=dt, tod=int(P.mt[o["t"]]), net_risk=r["net"], fnet=f["fnet"], fgross=f["fgross"],
                         lot=lot, false_break=bool(o["why"] == "stop" and held < 3 * v.tf),
                         pts=float(o["side"] * (o["xlv"] - o["entry"]) if o["why"] != "time"
                                   else o["side"] * (P.mo[o["xm"]] - o["entry"]))))
    T = pd.DataFrame(rows)
    opt_days = sum(1 for d in range(a, b + 1) if P.days[d] in W.S[u] and W.S[u][P.days[d]].atm is not None)
    res = dict(inst=u, variant=v.name, lo=str(lo), hi=str(hi), option_sessions=int(opt_days))
    if len(T) and T.filled.any():
        F = T[T.filled]
        tw = twins_b(W, u, v, F.to_dict("records"), twins, np.random.default_rng(K.SEED), "opt")
        twf = twins_b(W, u, v, T.to_dict("records"), twins, np.random.default_rng(K.SEED + 1), "fut")
        res["opt"] = stats(F, opt_days, "net")
        res["opt"]["twin_mean"] = round(float(tw.mean()), 0)
        res["opt"]["twin_p"] = round((1 + int((tw >= F.net.sum()).sum())) / (twins + 1), 4)
        res["opt_risk"] = stats(F, opt_days, "net_risk")
        res["fut"] = stats(T, opt_days, "fnet")
        res["fut"]["twin_mean"] = round(float(twf.mean()), 0)
        res["fut"]["twin_p"] = round((1 + int((twf >= T.fnet.sum()).sum())) / (twins + 1), 4)
        res["false_break"] = round(float(F.false_break.mean()), 4)
        res["why"] = {k: int(x) for k, x in F.why.value_counts().items()}
        res["pts_mean"] = round(float(T.pts.mean()), 2)
        res["avg_risk_pts"] = round(float(F.risk.mean()), 2)
        res["opt_gross_pts"] = round(float((F.gross / F.lot).mean()), 2)
        res["opt_cost_pts"] = round(float(((F.gross - F.net) / F.lot).mean()), 2)
        res["held_min"] = round(float((P.mt[F.xm.values] - P.mt[F.t.values]).mean()), 1)
    else:
        res["opt"] = dict(trades=0, net=0.0, twin_p=1.0, twin_mean=0.0)
    res["unfilled"] = int((~T.filled).sum()) if len(T) else 0
    return res, T


def nets_by(T: pd.DataFrame, spans) -> list:
    out = []
    for a, b in spans:
        x = T[(T.filled) & (T.date >= a) & (T.date <= b)] if len(T) else T
        out.append(dict(lo=str(a), hi=str(b), trades=int(len(x)), net=round(float(x.net.sum()), 0) if len(x) else 0.0))
    return out


def gates(r: dict) -> dict:
    o = r["opt"]
    g = dict(G1=o.get("trades", 0) >= K.MIN_TRADES, G2=o.get("net", 0) > 0, G3=o.get("twin_p", 1) < K.GATE_P,
             G4=r.get("q", 1) < K.GATE_Q, G5=sum(f["net"] > 0 for f in r["folds"]) >= 3, G6=o.get("ex_top5", -1) > 0,
             G7=o.get("worst_month", -1e9) >= K.MONTH_STOP)
    g["pass"] = all(g.values())
    return g


def pool(cells: dict) -> dict:
    out = {}
    for v in rules_b.VARIANTS_B:
        rs = [cells[(u, v.name)] for u in K.INDICES if (u, v.name) in cells]
        out[v.name] = dict(trades=sum(r["opt"].get("trades", 0) for r in rs), net=sum(r["opt"].get("net", 0) for r in rs),
                           twin_mean=sum(r["opt"].get("twin_mean", 0) for r in rs),
                           risk_net=sum(r.get("opt_risk", {}).get("net", 0) for r in rs),
                           fut_net=sum(r.get("fut", {}).get("net", 0) for r in rs),
                           false_break=round(float(np.average([r.get("false_break", 0) for r in rs],
                                             weights=[max(r["opt"].get("trades", 0), 1) for r in rs])), 4))
    return out


def _save(name, obj):
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def _tpath(tag, u, v):
    p = K.OUT / "trades_b" / tag
    p.mkdir(parents=True, exist_ok=True)
    return p / f"{u}_{v}.parquet"


def _run(which: str, lo: date, hi: date, spans, tag: str) -> dict:
    W = World(which)
    cells = {}
    for u in K.INDICES:
        for v in rules_b.VARIANTS_B:
            r, T = run_cell(W, u, v, lo, hi)
            r["spans"] = nets_by(T, spans)
            cells[(u, v.name)] = r
            if len(T):
                T.drop(columns=["date"]).assign(date=T.date.astype(str)).to_parquet(_tpath(tag, u, v.name))
            o = r["opt"]
            print(f"{u:10} {v.name} n {o.get('trades', 0):4} net {o.get('net', 0):>10,.0f} p {o.get('twin_p')}", flush=True)
    keys = sorted(cells)
    for k, q in zip(keys, bh([cells[k]["opt"].get("twin_p", 1.0) for k in keys])):
        cells[k]["q"] = round(float(q), 4)
    return cells


def design():
    cells = _run("new", *DESIGN, FOLDS, "design")
    for c in cells.values():
        c["folds"] = c.pop("spans")
        c["gates"] = gates(c)
    keys = sorted(cells)
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), cells=[cells[k] for k in keys],
               pooled=pool(cells), passers=[f"{k[0]}|{k[1]}" for k in keys if cells[k]["gates"]["pass"]])
    _save("design.json", out)


def final():
    global _OPEN
    if FINAL_FLAG.exists():
        raise SystemExit("R89B holdout already read (results_b/FINAL_READ); a second look needs a new pre-registration")
    D = json.loads((RESULTS / "design.json").read_text())
    passers = set(D["passers"])
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    _OPEN = True
    cells = _run("old", *HOLDOUT, BLOCKS, "final")
    keys = sorted(cells)
    pk = [k for k in keys if f"{k[0]}|{k[1]}" in passers]
    verdict = {}
    for k, q in zip(pk, bh([cells[k]["opt"].get("twin_p", 1.0) for k in pk])):
        c, o = cells[k], cells[k]["opt"]
        ok = (o.get("trades", 0) >= K.MIN_TRADES and o.get("net", 0) > 0 and o.get("twin_p", 1) < K.HOLD_P
              and q < K.GATE_Q and o.get("worst_month", -1e9) >= K.MONTH_STOP
              and sum(s["net"] > 0 for s in c["spans"]) >= 2)
        verdict[f"{k[0]}|{k[1]}"] = dict(q=round(float(q), 4), paper_candidate=bool(ok))
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), cells=[cells[k] for k in keys],
               pooled=pool(cells), design_passers=sorted(passers), verdict=verdict)
    _save("final.json", out)


if __name__ == "__main__":
    {"design": design, "final": final}[sys.argv[1] if len(sys.argv) > 1 else "design"]()
