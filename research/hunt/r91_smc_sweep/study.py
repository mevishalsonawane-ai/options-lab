"""R91 study (PREREG.md): every variant on every instrument, design first, then the ONE holdout read.

    IRAALGO_LAKE=~/lake python -m research.hunt.r91_smc_sweep.study counts    # trade COUNTS only (no P&L), for NOTES
    IRAALGO_LAKE=~/lake python -m research.hunt.r91_smc_sweep.study design    # 63 cells, twins, BH, gates, walk-forward
    IRAALGO_LAKE=~/lake python -m research.hunt.r91_smc_sweep.study final     # the ONE holdout read (refuses a second)
    IRAALGO_LAKE=~/lake python -m research.hunt.r91_smc_sweep.study arms      # same-day comparison with the arms

Index stores / panels: R89's (`research/hunt/r89_pdhl_break/store.py build` with IRAALGO_LAKE=~/lakeflat first).
Instrument wrapper, option pricing and twins: R90's (15:15 / 15:05 flat). Results (small JSON) go to results/; trade
lists to $R91_OUT/trades (never git).
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from research.hunt.r89_pdhl_break.study import bh, stats
from research.hunt.r90_almazov_bos import price as P90
from research.hunt.r90_almazov_bos.study import Inst as Inst90

from . import cfg as K
from . import sweep as W

_HOLDOUT_OPEN = False


# ------------------------------------------------------------------------------------------------ guard
def check(lo: date, hi: date):
    if hi >= K.HOLDOUT[0] and not _HOLDOUT_OPEN:
        raise PermissionError(f"R91: {hi} is in the locked holdout (from {K.HOLDOUT[0]}); only `study.py final` reads it")


def day_range(P, lo: date, hi: date) -> tuple:
    check(lo, hi)
    ix = [i for i, d in enumerate(P.days) if lo <= d <= hi]
    return (ix[0], ix[-1]) if ix else (1, 0)


# ------------------------------------------------------------------------------------------------ one instrument
class Inst(Inst90):
    """R90's wrapper (panel, R51 store / MCX book, 15:15 flat minutes, day_ok, tick) plus R91's levels."""

    def __init__(self, name: str, **kw):
        super().__init__(name, **kw)
        self.S = W.from_panel(self.P)
        self._B: dict = {}
        self._L: dict = {}
        self.A5 = W.atr(self.bars5())

    def bars_tf(self, tf: int) -> dict:
        if tf not in self._B:
            self._B[tf] = W.bars(self.S, tf)
        return self._B[tf]

    def bars5(self) -> dict:
        return self.bars_tf(5)

    def levels(self, v: K.Variant):
        if v.level_key not in self._L:
            self._L[v.level_key] = W.levels(self.S, self.bars_tf(K.HOUR), self.bars_tf(v.sweep_tf), v.n, v.anyday,
                                            self.tick)
        return self._L[v.level_key]

    def win(self) -> tuple:
        return (K.NSE_FIRST, K.NSE_LAST) if self.nse else (K.MCX_FIRST, K.MCX_LAST)

    def trades(self, v: K.Variant, lo: date, hi: date) -> tuple:
        a, b = day_range(self.P, lo, hi)
        Lv, sw, amb = self.levels(v)
        ok = self.day_ok.copy()
        ok[:a] = False
        ok[b + 1:] = False
        first, last = self.win()
        T, F = W.run(self.S, v, Lv, sw, self.bars5(), self.A5, self.tick, self.sq, first, last, ok)
        F["ambiguous_all_days"] = int(amb)
        return T, F

    def run_cell(self, v: K.Variant, lo: date, hi: date, twins: int = K.TWINS, seed: int = K.SEED) -> tuple:
        a, b = day_range(self.P, lo, hi)
        T, F = self.trades(v, lo, hi)
        O = P90.option(self, T)
        P = self.P
        rows = []
        for o in O:
            d = int(o["day"])
            lot = self.lot(d)
            held = int(P.mt[o["xm"]]) - int(P.mt[o["t"]])
            rows.append(dict(o, date=P.days[d], tod=int(P.mt[o["t"]]), lot=lot, pts_rs=o["pts"] * lot, held=held,
                             fast_stop=bool(o["why"] == "stop" and held < K.FAST_STOP_MIN)))
        T = pd.DataFrame(rows)
        n_sess = int(self.day_ok[a:b + 1].sum())
        res = dict(inst=self.name, variant=v.name, lo=str(lo), hi=str(hi), sessions=n_sess, signals=int(len(T)),
                   funnel=F)
        first, last = self.win()
        filled = T[T.filled] if len(T) else T
        if len(filled):
            recs = filled.to_dict("records")
            tw = P90.twins(self, recs, first, last, twins, np.random.default_rng(seed), "after")
            tww = P90.twins(self, recs, first, last, twins, np.random.default_rng(seed + 1), "window")
            o = stats(filled, n_sess, "net")
            o["per_week"] = round(o["per_day"] * 5, 3)
            o["twin_mean"] = round(float(tw.mean()), 0)
            o["twin_p"] = round((1 + int((tw >= filled.net.sum()).sum())) / (twins + 1), 4)
            o["wtwin_mean"] = round(float(tww.mean()), 0)
            o["wtwin_p"] = round((1 + int((tww >= filled.net.sum()).sum())) / (twins + 1), 4)
            res["opt"] = o
            res["stopped"] = round(float((filled.why == "stop").mean()), 4)
            res["fast_stop"] = round(float(filled.fast_stop.mean()), 4)
            res["why"] = {k: int(x) for k, x in filled.why.value_counts().items()}
            res["puts"] = int((filled.side < 0).sum())
            res["avg_risk_pts"] = round(float(filled.risk.mean()), 2)
            res["avg_reward_pts"] = round(float(filled.reward.mean()), 2)
            res["opt_gross_pts"] = round(float((filled.gross / filled.lot).mean()), 2)
            res["opt_cost_pts"] = round(float(((filled.gross - filled.net) / filled.lot).mean()), 2)
            res["hold_min"] = round(float(filled.held.mean()), 1)
        else:
            res["opt"] = dict(trades=0, net=0.0, twin_p=1.0, twin_mean=0.0)
        if len(T):
            u = stats(T, n_sess, "pts_rs")
            u["pts_mean"] = round(float(T.pts.mean()), 2)
            u["pts_win"] = round(float((T.pts > 0).mean()), 4)
            u["stopped"] = round(float((T.why == "stop").mean()), 4)
            res["und"] = u
            res["unfilled"] = int((~T.filled).sum())
        return res, T


# ------------------------------------------------------------------------------------------------ gates
def folds_of(inst: str):
    return K.IDX_FOLDS if inst in K.INDICES else K.MCX_FOLDS


def fold_nets(T: pd.DataFrame, inst: str) -> list:
    out = []
    for a, b in folds_of(inst):
        x = T[(T.filled) & (T.date >= a) & (T.date <= b)] if len(T) else T
        out.append(dict(lo=str(a), hi=str(b), trades=int(len(x)), net=round(float(x.net.sum()), 0) if len(x) else 0.0))
    return out


def design_gates(r: dict) -> dict:
    o, f = r["opt"], r["folds"]
    need = 3 if r["inst"] in K.INDICES else 2
    g = dict(G1=o.get("trades", 0) >= K.MIN_TRADES, G2=o.get("net", 0) > 0, G3=o.get("twin_p", 1) < K.GATE_P,
             G4=r.get("q", 1) < K.GATE_Q, G5=sum(x["net"] > 0 for x in f) >= need, G6=o.get("ex_top5", -1) > 0,
             G7=o.get("worst_month", -1e9) >= K.MONTH_STOP)
    g["pass"] = all(g.values())
    return g


def selection_wf(cells: dict, trades: dict) -> dict:
    """Per instrument: before each fold pick the variant with the best design net so far (>= 20 trades; ties -> B),
    record its next-fold net, against B's."""
    out = {}
    for inst, tv in trades.items():
        start = K.IDX_DESIGN[0] if inst in K.INDICES else K.MCX_DESIGN[0]
        picks, tot = [], 0.0
        for a, b in folds_of(inst):
            best, bv = K.BASE, -1e18
            for v in K.VARIANTS:
                T = tv.get(v.name)
                if T is None or not len(T):
                    continue
                x = T[(T.filled) & (T.date >= start) & (T.date < a)]
                if len(x) < 20:
                    continue
                s = float(x.net.sum())
                if s > bv or (s == bv and v.name == K.BASE):
                    best, bv = v.name, s
            T = tv.get(best)
            nxt = T[(T.filled) & (T.date >= a) & (T.date <= b)] if T is not None and len(T) else None
            n = float(nxt.net.sum()) if nxt is not None and len(nxt) else 0.0
            picks.append(dict(fold=f"{a}..{b}", pick=best, prior_net=round(bv, 0) if bv > -1e18 else None,
                              next_net=round(n, 0)))
            tot += n
        out[inst] = dict(picks=picks, selection_net=round(tot, 0),
                         base_net=sum(x["net"] for x in cells[(inst, K.BASE)]["folds"]))
    return out


def pool(cells: dict) -> dict:
    out = {}
    for grp, members in (("indices", K.INDICES), ("mcx", K.MCX)):
        for v in K.VARIANTS:
            rs = [cells[(m, v.name)] for m in members if (m, v.name) in cells]
            if not rs:
                continue
            w = [max(r["opt"].get("trades", 0), 1) for r in rs]
            out[f"{grp}|{v.name}"] = dict(
                trades=sum(r["opt"].get("trades", 0) for r in rs), net=sum(r["opt"].get("net", 0) for r in rs),
                twin_mean=sum(r["opt"].get("twin_mean", 0) for r in rs),
                wtwin_mean=sum(r["opt"].get("wtwin_mean", 0) for r in rs),
                und_rs=sum(r.get("und", {}).get("net", 0) for r in rs),
                und_trades=sum(r.get("und", {}).get("trades", 0) for r in rs),
                stopped=round(float(np.average([r.get("stopped", 0) for r in rs], weights=w)), 4),
                fast_stop=round(float(np.average([r.get("fast_stop", 0) for r in rs], weights=w)), 4),
                sweeps=sum(r["funnel"]["sweeps"] for r in rs),
                cancel_pre=sum(r["funnel"]["cancel_pre"] for r in rs))
    return out


def pooled_book(trades: dict, grp) -> dict:
    """The pooled filled trades of a group as one book (daily DD, worst day / month), per variant."""
    out = {}
    for v in K.VARIANTS:
        parts = [trades[m][v.name] for m in grp if m in trades and v.name in trades[m] and len(trades[m][v.name])]
        if not parts:
            continue
        T = pd.concat(parts, ignore_index=True)
        T = T[T.filled]
        if len(T):
            out[v.name] = stats(T, 0, "net")
    return out


# ------------------------------------------------------------------------------------------------ commands
def _save(name: str, obj):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def _trades_path(tag: str, inst: str, v: str):
    p = K.OUT / "trades" / tag
    p.mkdir(parents=True, exist_ok=True)
    return p / f"{inst}_{v}.parquet"


def _log(name, v, r):
    o = r["opt"]
    print(f"{name:10} {v:4} n {o.get('trades', 0):5} net {o.get('net', 0):>11,.0f} p {o.get('twin_p')} "
          f"und {r.get('und', {}).get('pts_mean')} funnel {r['funnel']}", flush=True)


def run_cells(tag: str, insts, window_of) -> tuple:
    cells, trades = {}, {}
    for name in insts:
        I = Inst(name)
        lo, hi = window_of(I)
        trades[name] = {}
        for v in K.VARIANTS:
            r, T = I.run_cell(v, lo, hi)
            r["folds"] = fold_nets(T, name)
            cells[(name, v.name)] = r
            trades[name][v.name] = T
            if len(T):
                T.drop(columns=["date"]).assign(date=T.date.astype(str)).to_parquet(_trades_path(tag, name, v.name))
            _log(name, v.name, r)
    return cells, trades


def run_counts(insts=K.INSTRUMENTS):
    """Design signal COUNTS and the funnel only (no price, P&L or outcome), for NOTES N1."""
    out = {}
    for name in insts:
        I = Inst(name)
        lo, hi = K.IDX_DESIGN if I.nse else K.MCX_DESIGN
        for v in K.VARIANTS:
            T, F = I.trades(v, lo, hi)
            out[f"{name}|{v.name}"] = dict(fills=len(T), **F)
            print(name, v.name, len(T), F, flush=True)
    _save("counts_design.json", out)


def run_design(insts=K.INSTRUMENTS):
    cells, trades = run_cells("design", insts, lambda I: K.IDX_DESIGN if I.nse else K.MCX_DESIGN)
    keys = sorted(cells)
    q = bh([cells[k]["opt"].get("twin_p", 1.0) for k in keys])
    for k, qq in zip(keys, q):
        cells[k]["q"] = round(float(qq), 4)
        cells[k]["gates"] = design_gates(cells[k])
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), n_cells=len(cells),
               cells=[cells[k] for k in keys], selection_wf=selection_wf(cells, trades), pooled=pool(cells),
               book_indices=pooled_book(trades, K.INDICES), book_mcx=pooled_book(trades, K.MCX),
               passers=[f"{k[0]}|{k[1]}" for k in keys if cells[k]["gates"]["pass"]])
    _save("design.json", out)
    return out


def run_final():
    global _HOLDOUT_OPEN
    if K.FINAL_FLAG.exists():
        raise SystemExit("R91 holdout already read (results/FINAL_READ); a second look needs a new pre-registration")
    D = json.loads((K.RESULTS / "design.json").read_text())
    passers = set(D["passers"])
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    _HOLDOUT_OPEN = True
    cells, trades = run_cells("final", K.INSTRUMENTS, lambda I: K.HOLDOUT)
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
               design_passers=sorted(passers), verdict=verdict, pooled=pool(cells),
               book_indices=pooled_book(trades, K.INDICES), book_mcx=pooled_book(trades, K.MCX))
    _save("final.json", out)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "counts":
        run_counts(tuple(sys.argv[2:]) or K.INSTRUMENTS)
    elif cmd == "design":
        run_design(tuple(sys.argv[2:]) or K.INSTRUMENTS)
    elif cmd == "final":
        run_final()
    elif cmd == "arms":
        from . import arms
        arms.main()
