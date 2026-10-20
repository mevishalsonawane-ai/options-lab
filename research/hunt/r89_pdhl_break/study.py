"""R89 study (PREREG.md): every variant on every instrument, design first, then the ONE holdout read.

    IRAALGO_LAKE=~/lake python -m research.hunt.r89_pdhl_break.study design     # 180 cells, twins, BH, gates, walk-forward
    IRAALGO_LAKE=~/lake python -m research.hunt.r89_pdhl_break.study old        # index futures-only on Aug 2021 - Nov 2024
    IRAALGO_LAKE=~/lake python -m research.hunt.r89_pdhl_break.study final      # the ONE holdout read (refuses a second)
    IRAALGO_LAKE=~/lake python -m research.hunt.r89_pdhl_break.study arms       # same-day comparison with the arms

Results (small JSON) go to results/; trade lists to $R89_OUT/trades (never git).
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from . import cfg as K
from . import data, price, rules, store

_HOLDOUT_OPEN = False


# ------------------------------------------------------------------------------------------------ guard
def check(lo: date, hi: date):
    if hi >= K.HOLDOUT[0] and not _HOLDOUT_OPEN:
        raise PermissionError(f"R89: {hi} is in the locked holdout (from {K.HOLDOUT[0]}); only `study.py final` reads it")


def day_range(P, lo: date, hi: date) -> tuple:
    check(lo, hi)
    ix = [i for i, d in enumerate(P.days) if lo <= d <= hi]
    return (ix[0], ix[-1]) if ix else (1, 0)


def sessions(P, a: int, b: int) -> int:
    return int(sum(1 for d in range(a, b + 1) if not P.skip[d] and not (P.kind == "mcx" and P.expiry[d])))


# ------------------------------------------------------------------------------------------------ statistics
def max_dd(daily: np.ndarray) -> float:
    if not len(daily):
        return 0.0
    eq = np.r_[0.0, np.cumsum(daily)]
    return float((np.maximum.accumulate(eq) - eq).max())


def stats(T: pd.DataFrame, n_sess: int, key: str = "net") -> dict:
    n = len(T)
    if not n:
        return dict(trades=0, net=0.0)
    x = T[key].values.astype(float)
    daily = T.groupby("date")[key].sum().sort_index()
    month = T.groupby(T.date.map(lambda d: d.strftime("%Y-%m")))[key].sum()
    w, lo = x[x > 0], x[x <= 0]
    top5 = np.sort(x)[::-1][:5].sum()
    return dict(trades=int(n), days=int(T.date.nunique()), sessions=int(n_sess),
                per_day=round(n / max(n_sess, 1), 3), win=round(float((x > 0).mean()), 4),
                avg_win=round(float(w.mean()), 0) if len(w) else None,
                avg_loss=round(float(lo.mean()), 0) if len(lo) else None,
                expectancy=round(float(x.mean()), 1), net=round(float(x.sum()), 0),
                ex_top5=round(float(x.sum() - top5), 0), max_dd=round(max_dd(daily.values), 0),
                worst_day=round(float(daily.min()), 0), worst_month=round(float(month.min()), 0),
                worst_month_name=str(month.idxmin()), green_months=f"{int((month > 0).sum())}/{len(month)}")


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


# ------------------------------------------------------------------------------------------------ one instrument
class Inst:
    def __init__(self, name: str):
        self.name = name
        self.P = data.panel(name)
        self.nse = name in K.INDICES
        self.store = store.load(name) if self.nse else None
        self.B = price.McxBook(data.market(name)) if not self.nse else None
        self._G: dict = {}
        self._SW: dict = {}

    def gen(self, v: K.Variant):
        if v.gen_key not in self._G:
            self._G[v.gen_key] = rules.gen_triggers(self.P, v.reading, v.tf, v.sr, v.wick)
        G = self._G[v.gen_key]
        sk = (v.tf, v.n)
        if sk not in self._SW:
            self._SW[sk] = rules.Swings(G["C"], v.n)
        return G, self._SW[sk]

    def run(self, v: K.Variant, lo: date, hi: date, twins: int = K.TWINS, seed: int = K.SEED) -> tuple:
        a, b = day_range(self.P, lo, hi)
        G, SW = self.gen(v)
        T = rules.plan(self.P, v, G, SW, a, b)
        if self.nse:
            O = price.idx_option(self.P, self.name, T, self.store, "lot")
            R = price.idx_option(self.P, self.name, T, self.store, "risk")
        else:
            O = price.mcx_option(self.P, self.B, T, "lot")
            R = price.mcx_option(self.P, self.B, T, "risk")
        rows = []
        for o, r in zip(O, R):
            dt = self.P.days[o["day"]]
            lot = float(price.K51.lot(self.name, dt)) if self.nse else K.MCX_MULT[self.name]
            f = price.fut_trade(self.P, o, lot, dt)
            held = int(self.P.mt[o["xm"]]) - int(self.P.mt[o["t"]])
            rows.append(dict(o, date=dt, tod=int(self.P.mt[o["t"]]), net_risk=r["net"], lots_risk=r["qty"],
                             fnet=f["fnet"], fgross=f["fgross"], false_break=bool(o["why"] == "stop" and held < 3 * v.tf),
                             lot=lot))
        T = pd.DataFrame(rows)
        first, last = rules.window(self.P, v)
        if len(T):
            filled = T[T.filled]
            rng = np.random.default_rng(seed)
            tw = price.twins(self.P, filled.to_dict("records"), first, last, twins, rng, "opt", self.name, self.store,
                             self.B)
            rng = np.random.default_rng(seed + 1)
            twf = price.twins(self.P, T.to_dict("records"), first, last, twins, rng, "fut", self.name, self.store,
                              self.B)
        else:
            filled, tw, twf = T, np.zeros(twins), np.zeros(twins)
        n_sess = sessions(self.P, a, b)
        res = dict(inst=self.name, variant=v.name, lo=str(lo), hi=str(hi))
        if len(filled):
            res["opt"] = stats(filled, n_sess, "net")
            res["opt_risk"] = stats(filled, n_sess, "net_risk")
            res["opt"]["twin_mean"] = round(float(tw.mean()), 0)
            res["opt"]["twin_p"] = round((1 + int((tw >= filled.net.sum()).sum())) / (twins + 1), 4)
            res["false_break"] = round(float(filled.false_break.mean()), 4)
            res["why"] = {k: int(x) for k, x in filled.why.value_counts().items()}
            res["avg_risk_pts"] = round(float(filled.risk.mean()), 2)
            res["avg_reward_pts"] = round(float(filled.reward.mean()), 2)
            res["lots_risk_mean"] = round(float((filled.lots_risk / (filled.lot if self.nse else 1.0)).mean()), 2)
            res["calls"] = int((filled.side > 0).sum())
        else:
            res["opt"] = dict(trades=0, net=0.0, twin_p=1.0, twin_mean=0.0)
        if len(T):
            res["fut"] = stats(T, n_sess, "fnet")
            res["fut"]["twin_mean"] = round(float(twf.mean()), 0)
            res["fut"]["twin_p"] = round((1 + int((twf >= T.fnet.sum()).sum())) / (twins + 1), 4)
        res["unfilled"] = int((~T.filled).sum()) if len(T) else 0
        res["no_target"] = rules.no_target_count(self.P, v, G, SW, a, b)
        return res, T


# ------------------------------------------------------------------------------------------------ gates
def folds_of(inst: str):
    return K.IDX_FOLDS if inst in K.INDICES else K.MCX_FOLDS


def fold_nets(T: pd.DataFrame, inst: str) -> list:
    out = []
    for a, b in folds_of(inst):
        if len(T):
            x = T[(T.filled) & (T.date >= a) & (T.date <= b)]
            out.append(dict(lo=str(a), hi=str(b), trades=int(len(x)), net=round(float(x.net.sum()), 0)))
        else:
            out.append(dict(lo=str(a), hi=str(b), trades=0, net=0.0))
    return out


def design_gates(r: dict) -> dict:
    o = r["opt"]
    f = r["folds"]
    need = 3 if r["inst"] in K.INDICES else 2
    g = dict(G1=o.get("trades", 0) >= K.MIN_TRADES, G2=o.get("net", 0) > 0, G3=o.get("twin_p", 1) < K.GATE_P,
             G4=r.get("q", 1) < K.GATE_Q, G5=sum(x["net"] > 0 for x in f) >= need, G6=o.get("ex_top5", -1) > 0,
             G7=o.get("worst_month", -1e9) >= K.MONTH_STOP)
    g["pass"] = all(g.values())
    return g


def selection_wf(cells: dict, trades: dict) -> dict:
    """Per instrument: at each fold pick the variant with the best design net before the fold (>= 20 trades; ties ->
    C-B), record its next-fold net, against C-B's and R-B's."""
    out = {}
    for inst in K.INSTRUMENTS:
        if inst not in trades:
            continue
        start = K.IDX_DESIGN[0] if inst in K.INDICES else K.MCX_DESIGN[0]
        picks, tot = [], 0.0
        for a, b in folds_of(inst):
            best, bv = "C-B", -1e18
            for v in K.VARIANTS:
                T = trades[inst].get(v.name)
                if T is None or not len(T):
                    continue
                x = T[(T.filled) & (T.date >= start) & (T.date < a)]
                if len(x) < 20:
                    continue
                s = float(x.net.sum())
                if s > bv or (s == bv and v.name == "C-B"):
                    best, bv = v.name, s
            T = trades[inst].get(best)
            nxt = T[(T.filled) & (T.date >= a) & (T.date <= b)] if T is not None and len(T) else T
            n = float(nxt.net.sum()) if nxt is not None and len(nxt) else 0.0
            picks.append(dict(fold=f"{a}..{b}", pick=best, prior_net=round(bv, 0) if bv > -1e18 else None,
                              next_net=round(n, 0)))
            tot += n
        base = {k: sum(x["net"] for x in cells[(inst, k)]["folds"]) for k in ("C-B", "R-B")}
        out[inst] = dict(picks=picks, selection_net=round(tot, 0), cb_net=base["C-B"], rb_net=base["R-B"])
    return out


# ------------------------------------------------------------------------------------------------ commands
def _save(name: str, obj):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def _trades_path(tag: str, inst: str, v: str):
    p = K.OUT / "trades" / tag
    p.mkdir(parents=True, exist_ok=True)
    return p / f"{inst}_{v}.parquet"


def run_design(insts=K.INSTRUMENTS):
    cells, trades = {}, {}
    for name in insts:
        I = Inst(name)
        lo, hi = (K.IDX_DESIGN if I.nse else K.MCX_DESIGN)
        trades[name] = {}
        for v in K.VARIANTS:
            r, T = I.run(v, lo, hi)
            r["folds"] = fold_nets(T, name)
            cells[(name, v.name)] = r
            trades[name][v.name] = T
            if len(T):
                T.drop(columns=["date"]).assign(date=T.date.astype(str)).to_parquet(_trades_path("design", name, v.name))
            o = r["opt"]
            print(f"{name:10} {v.name:6} n {o.get('trades', 0):4} net {o.get('net', 0):>10,.0f} p {o.get('twin_p')}"
                  f" fut {r.get('fut', {}).get('net', 0):>10,.0f}", flush=True)
    keys = sorted(cells)
    q = bh([cells[k]["opt"].get("twin_p", 1.0) for k in keys])
    for k, qq in zip(keys, q):
        cells[k]["q"] = round(float(qq), 4)
        cells[k]["gates"] = design_gates(cells[k])
    wf = selection_wf(cells, trades)
    pooled = pool(cells)
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), n_cells=len(cells),
               cells=[cells[k] for k in keys], selection_wf=wf, pooled=pooled,
               passers=[f"{k[0]}|{k[1]}" for k in keys if cells[k]["gates"]["pass"]])
    _save("design.json", out)
    return out


def pool(cells: dict) -> dict:
    out = {}
    for grp, members in (("indices", K.INDICES), ("mcx", K.MCX)):
        for v in K.VARIANTS:
            rs = [cells[(m, v.name)] for m in members if (m, v.name) in cells]
            if not rs:
                continue
            out[f"{grp}|{v.name}"] = dict(
                trades=sum(r["opt"].get("trades", 0) for r in rs), net=sum(r["opt"].get("net", 0) for r in rs),
                twin_mean=sum(r["opt"].get("twin_mean", 0) for r in rs),
                fut_net=sum(r.get("fut", {}).get("net", 0) for r in rs),
                risk_net=sum(r.get("opt_risk", {}).get("net", 0) for r in rs),
                false_break=round(float(np.average([r.get("false_break", 0) for r in rs],
                                                   weights=[max(r["opt"].get("trades", 0), 1) for r in rs])), 4))
    return out


def run_old():
    out = []
    for und in K.INDICES:
        P = data.panel(f"OLD_{und}")
        a, b = 0, P.ndays - 1
        while a < P.ndays and P.days[a] < K.OLD[0]:
            a += 1
        for v in K.VARIANTS:
            G = rules.gen_triggers(P, v.reading, v.tf, v.sr, v.wick)
            SW = rules.Swings(G["C"], v.n)
            T = rules.plan(P, v, G, SW, a, b)
            rows = []
            for o in T:
                dt = P.days[o["day"]]
                lot = float(price.K51.lot(und, dt))
                rows.append(dict(o, date=dt, **price.fut_trade(P, o, lot, dt)))
            T = pd.DataFrame(rows)
            r = dict(inst=und, variant=v.name, lo=str(P.days[a]) if a < P.ndays else None, hi=str(P.days[b]))
            if len(T):
                r["fut"] = stats(T, sessions(P, a, b), "fnet")
                r["fut_gross"] = round(float(T.fgross.sum()), 0)
                r["false_break"] = round(float(((T.why == "stop") & ((P.mt[T.xm.values] - P.mt[T.t.values])
                                                                     < 3 * v.tf)).mean()), 4)
                r["win_underlying"] = round(float((T.fgross > 0).mean()), 4)
            out.append(r)
            print(und, v.name, r.get("fut", {}).get("trades"), r.get("fut", {}).get("net"), flush=True)
    _save("old_futures.json", dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), cells=out))


def run_final():
    global _HOLDOUT_OPEN
    if K.FINAL_FLAG.exists():
        raise SystemExit("R89 holdout already read (results/FINAL_READ); a second look needs a new pre-registration")
    D = json.loads((K.RESULTS / "design.json").read_text())
    passers = set(D["passers"])
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    _HOLDOUT_OPEN = True
    cells = {}
    for name in K.INSTRUMENTS:
        I = Inst(name)
        for v in K.VARIANTS:
            r, T = I.run(v, *K.HOLDOUT)
            cells[(name, v.name)] = r
            if len(T):
                T.drop(columns=["date"]).assign(date=T.date.astype(str)).to_parquet(_trades_path("final", name, v.name))
            o = r["opt"]
            print(f"{name:10} {v.name:6} n {o.get('trades', 0):4} net {o.get('net', 0):>10,.0f} p {o.get('twin_p')}",
                  flush=True)
    keys = sorted(cells)
    q_all = bh([cells[k]["opt"].get("twin_p", 1.0) for k in keys])
    for k, qq in zip(keys, q_all):
        cells[k]["q_all"] = round(float(qq), 4)
    pk = [k for k in keys if f"{k[0]}|{k[1]}" in passers]
    qp = bh([cells[k]["opt"].get("twin_p", 1.0) for k in pk])
    verdict = {}
    for k, qq in zip(pk, qp):
        o = cells[k]["opt"]
        ok = (o.get("net", 0) > 0 and o.get("trades", 0) >= K.HOLD_MIN_TRADES and o.get("twin_p", 1) < K.HOLD_P
              and qq < K.GATE_Q and o.get("worst_month", -1e9) >= K.MONTH_STOP)
        verdict[f"{k[0]}|{k[1]}"] = dict(q=round(float(qq), 4), paper_candidate=bool(ok))
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), cells=[cells[k] for k in keys],
               design_passers=sorted(passers), verdict=verdict, pooled=pool(cells))
    _save("final.json", out)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "design":
        run_design(tuple(sys.argv[2:]) or K.INSTRUMENTS)
    elif cmd == "old":
        run_old()
    elif cmd == "final":
        run_final()
    elif cmd == "arms":
        from . import arms
        arms.main()
