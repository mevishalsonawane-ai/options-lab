"""R95 study (PREREG.md): 8 variants x 4 timeframes x 9 instruments, design first, then the ONE holdout read.

    IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study counts    # signal COUNTS only (no outcome), for NOTES
    IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study design    # 288 cells, twins, BH, gates, walk-forward
    IRAALGO_LAKE=~/lake python -m research.hunt.r95_cci_signals.study final     # the ONE holdout read (refuses a second)

Index stores / panels: R89's (`research/hunt/r89_pdhl_break/store.py build` with IRAALGO_LAKE=~/lakeflat first).
Instrument wrapper: R90's (panel, R51 store / MCX book, 15:15 / 15:05 flat minutes, day_ok, tick). Results (small JSON)
go to results/; trade lists to $R95_OUT/trades (never git).
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd
from scipy.stats import norm

from research.hunt.r89_pdhl_break.study import bh, stats
from research.hunt.r90_almazov_bos.study import Inst as Inst90

from . import cfg as K
from . import cci as C
from . import describe as DS
from . import overlay as OV
from . import price as PR
from . import rules as R

_HOLDOUT_OPEN = False


# ------------------------------------------------------------------------------------------------ guard
def check(lo: date, hi: date):
    if hi >= K.HOLDOUT[0] and not _HOLDOUT_OPEN:
        raise PermissionError(f"R95: {hi} is in the locked holdout (from {K.HOLDOUT[0]}); only `study.py final` reads it")


def day_range(P, lo: date, hi: date) -> tuple:
    check(lo, hi)
    ix = [i for i, d in enumerate(P.days) if lo <= d <= hi]
    return (ix[0], ix[-1]) if ix else (1, 0)


def window_ok(day_ok: np.ndarray, a: int, b: int) -> np.ndarray:
    ok = day_ok.copy()
    ok[:a] = False
    ok[b + 1:] = False
    return ok


# ------------------------------------------------------------------------------------------------ one instrument
class Inst(Inst90):
    """R90's wrapper plus R95's bars and indicator arrays (cached per timeframe, CCI length and level)."""

    def __init__(self, name: str, **kw):
        super().__init__(name, **kw)
        P = self.P
        self.M = dict(mo=P.mo, mh=P.mh, ml=P.ml, mc=P.mc, mt=P.mt, mday=P.mday)
        self._B: dict = {}
        self._I: dict = {}
        self._dmap = {dt: i for i, dt in enumerate(P.days)}

    def day_index_of(self, dt):
        return self._dmap.get(dt)

    def tf_bars(self, tf: int) -> dict:
        if tf not in self._B:
            P = self.P
            self._B[tf] = C.bars(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, int(P.anchor), tf)
        return self._B[tf]

    def ind(self, tf: int, n: int = K.CCI_N, level: float = K.LEVEL) -> C.Ind:
        key = (tf, n, level)
        if key not in self._I:
            self._I[key] = C.indicator(self.tf_bars(tf), n, level)
        return self._I[key]

    def win(self) -> tuple:
        return (K.NSE_FIRST, K.NSE_LAST) if self.nse else (K.MCX_FIRST, K.MCX_LAST)

    def trades(self, v: K.Variant, tf: int, lo: date, hi: date) -> tuple:
        a, b = day_range(self.P, lo, hi)
        first, last = self.win()
        return R.run(self.ind(tf, v.n, v.level), v, self.M, self.sq, first, last, window_ok(self.day_ok, a, b),
                     self.tick)

    def run_cell(self, v: K.Variant, tf: int, lo: date, hi: date, twins: int = K.TWINS, seed: int = K.SEED) -> tuple:
        a, b = day_range(self.P, lo, hi)
        T, F = self.trades(v, tf, lo, hi)
        O = PR.option(self, T)
        P = self.P
        rows = []
        for o in O:
            d = int(o["day"])
            lot = self.lot(d)
            rows.append(dict(o, date=P.days[d], tod=int(P.mt[o["t"]]), lot=lot, pts_rs=o["pts"] * lot,
                             held=int(P.mt[o["xm"]]) - int(P.mt[o["t"]])))
        T = pd.DataFrame(rows)
        n_sess = int(self.day_ok[a:b + 1].sum())
        res = dict(inst=self.name, tf=tf, variant=v.name, lo=str(lo), hi=str(hi), sessions=n_sess, funnel=F)
        first, last = self.win()
        filled = T[T.filled] if len(T) else T
        if len(filled):
            recs = filled.to_dict("records")
            tw = PR.twins(self, recs, v, first, last, twins, np.random.default_rng(seed + 1000 * tf))
            res["opt"] = cell_stats(filled, n_sess, tw)
            res["why"] = {k: int(x) for k, x in filled.why.value_counts().items()}
            res["puts"] = int((filled.side < 0).sum())
            res["avg_risk_pts"] = round(float(filled.risk.mean()), 2)
            res["opt_gross_pts"] = round(float((filled.gross / filled.lot).mean()), 2)
            res["opt_cost_pts"] = round(float(((filled.gross - filled.net) / filled.lot).mean()), 2)
            res["hold_min"] = round(float(filled.held.mean()), 1)
        else:
            res["opt"] = dict(trades=0, net=0.0, p_emp=1.0, p_norm=1.0, twin_mean=0.0)
        if len(T):
            u = stats(T, n_sess, "pts_rs")
            u["pts_mean"] = round(float(T.pts.mean()), 2)
            u["pts_win"] = round(float((T.pts > 0).mean()), 4)
            u["payoff"] = payoff(T.pts.values)
            res["und"] = u
            res["unfilled"] = int((~T.filled).sum())
        return res, T


def payoff(x: np.ndarray):
    w, lo = x[x > 0], x[x <= 0]
    if not len(w) or not len(lo) or lo.mean() == 0:
        return None
    return round(float(abs(w.mean() / lo.mean())), 3)


def cell_stats(filled: pd.DataFrame, n_sess: int, tw: np.ndarray) -> dict:
    o = stats(filled, n_sess, "net")
    net = float(filled.net.sum())
    o["per_week"] = round(o["per_day"] * 5, 3)
    o["payoff"] = payoff(filled.net.values)
    o["twin_mean"] = round(float(tw.mean()), 0)
    o["twin_sd"] = round(float(tw.std()), 0)
    o["p_emp"] = round((1 + int((tw >= net).sum())) / (len(tw) + 1), 5)
    sd = float(tw.std())
    o["p_norm"] = float(norm.sf((net - float(tw.mean())) / sd)) if sd > 0 else (0.0 if net > tw.mean() else 1.0)
    return o


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
    g = dict(G1=o.get("trades", 0) >= K.MIN_TRADES, G2=o.get("net", 0) > 0, G3=o.get("p_emp", 1) < K.GATE_P,
             G4=r.get("q", 1) < K.GATE_Q, G5=sum(x["net"] > 0 for x in f) >= need, G6=o.get("ex_top5", -1) > 0,
             G7=o.get("worst_month", -1e9) >= K.MONTH_STOP)
    g["pass"] = bool(all(g.values()))
    return {k: bool(x) for k, x in g.items()}


def bh_q(cells: dict, keys: list) -> np.ndarray:
    return bh([max(float(cells[k]["opt"].get("p_norm", 1.0)), K.P_FLOOR) for k in keys])


def selection_wf(cells: dict, trades: dict) -> dict:
    """Per instrument and timeframe: before each fold pick the variant with the best design net so far (>= 20 trades;
    ties -> B), record its next-fold net, against B's."""
    out = {}
    for (inst, tf), tv in trades.items():
        start = K.IDX_DESIGN[0] if inst in K.INDICES else K.MCX_DESIGN[0]
        picks, tot = [], 0.0
        for a, b in folds_of(inst):
            best, bv = K.BASE, -1e18
            for v in K.FADE:
                T = tv.get(v.name)
                if T is None or not len(T):
                    continue
                x = T[(T.filled) & (T.date >= start) & (T.date < a)]
                if len(x) < K.WF_MIN:
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
        out[f"{inst}|{tf}"] = dict(picks=picks, selection_net=round(tot, 0),
                                   base_net=sum(x["net"] for x in cells[(inst, tf, K.BASE)]["folds"]))
    return out


def pooled_book(trades: dict, grp, tf: int, v: str):
    parts = [trades[(m, tf)][v] for m in grp if (m, tf) in trades and v in trades[(m, tf)] and len(trades[(m, tf)][v])]
    if not parts:
        return None
    T = pd.concat(parts, ignore_index=True)
    T = T[T.filled]
    if not len(T):
        return None
    o = stats(T, 0, "net")
    o["payoff"] = payoff(T.net.values)
    return o


def pool(cells: dict, trades: dict) -> dict:
    out = {}
    for grp, members in (("indices", K.INDICES), ("mcx", K.MCX)):
        for tf in K.TIMEFRAMES:
            for v in K.VARIANTS:
                rs = [cells[(m, tf, v.name)] for m in members if (m, tf, v.name) in cells]
                if not rs:
                    continue
                out[f"{grp}|{tf}|{v.name}"] = dict(
                    trades=sum(r["opt"].get("trades", 0) for r in rs), net=sum(r["opt"].get("net", 0) for r in rs),
                    twin_mean=sum(r["opt"].get("twin_mean", 0) for r in rs),
                    und_rs=sum(r.get("und", {}).get("net", 0) for r in rs),
                    und_trades=sum(r.get("und", {}).get("trades", 0) for r in rs),
                    book=pooled_book(trades, members, tf, v.name))
    return out


# ------------------------------------------------------------------------------------------------ commands
def _save(name: str, obj):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def _trades_path(tag: str, inst: str, tf: int, v: str):
    p = K.OUT / "trades" / tag
    p.mkdir(parents=True, exist_ok=True)
    return p / f"{inst}_{tf}_{v}.parquet"


def _log(name, tf, v, r):
    o = r["opt"]
    print(f"{name:10} {tf:3} {v:4} n {o.get('trades', 0):5} win {o.get('win')} net {o.get('net', 0):>11,.0f} "
          f"p {o.get('p_emp')} und {r.get('und', {}).get('pts_mean')} funnel {r['funnel']}", flush=True)


def run_cells(tag: str, insts, window_of) -> tuple:
    """The 324 entry cells (and the descriptive study). Index wrappers are kept for the EX overlay."""
    cells, trades, desc, keep = {}, {}, {}, {}
    for name in insts:
        I = Inst(name)
        if I.nse:
            keep[name] = I
        lo, hi = window_of(I)
        for tf in K.TIMEFRAMES:
            trades[(name, tf)] = {}
            for v in K.VARIANTS:
                r, T = I.run_cell(v, tf, lo, hi)
                r["folds"] = fold_nets(T, name)
                cells[(name, tf, v.name)] = r
                trades[(name, tf)][v.name] = T
                if len(T):
                    T.drop(columns=["date"]).assign(date=T.date.astype(str)).to_parquet(_trades_path(tag, name, tf, v.name))
                _log(name, tf, v.name, r)
        desc[name] = DS.describe(I, lo, hi, day_range)
    return cells, trades, desc, keep


def run_overlay(tag: str, insts: dict, lo, hi) -> dict:
    """The 4 EX cells (amendment A1, PREREG 10.2). insts: the index wrappers."""
    check(lo, hi)
    for u in K.INDICES:
        if u not in insts:
            insts[u] = Inst(u)
    cells = {}
    for arm in K.OVL_ARMS:
        for tf in K.OVL_TFS:
            r, F = OV.run_cell(arm, tf, insts, lo, hi)
            cells[(r["inst"], tf, "EX")] = r
            if len(F):
                F.drop(columns=["date", "day"]).assign(date=F.date.astype(str)).to_parquet(
                    _trades_path(tag, r["inst"].replace(":", "_"), tf, "EX"))
            print(r["inst"], tf, "EX", r["opt"], flush=True)
    return cells


def run_counts(insts=K.INSTRUMENTS):
    """Design signal COUNTS and the funnel only (no price, P&L or outcome), for NOTES N1."""
    out = {}
    for name in insts:
        I = Inst(name)
        lo, hi = K.IDX_DESIGN if I.nse else K.MCX_DESIGN
        for tf in K.TIMEFRAMES:
            for v in K.VARIANTS:
                T, F = I.trades(v, tf, lo, hi)
                out[f"{name}|{tf}|{v.name}"] = dict(trades=len(T), **F)
                print(name, tf, v.name, len(T), F, flush=True)
    _save("counts_design.json", out)


def _cells_out(cells: dict, keys: list) -> list:
    return [cells[k] for k in keys]


def is_overlay(k) -> bool:
    return str(k[0]).startswith("ARM:")


def run_design(insts=K.INSTRUMENTS):
    cells, trades, desc, keep = run_cells("design", insts, lambda I: K.IDX_DESIGN if I.nse else K.MCX_DESIGN)
    cells.update(run_overlay("design", keep, *K.IDX_DESIGN))
    keys = sorted(cells, key=lambda k: tuple(map(str, k)))
    for k, qq in zip(keys, bh_q(cells, keys)):
        cells[k]["q"] = round(float(qq), 5)
        cells[k]["gates"] = OV.gates(cells[k]) if is_overlay(k) else design_gates(cells[k])
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), n_cells=len(cells),
               cells=_cells_out(cells, keys), selection_wf=selection_wf(cells, trades), pooled=pool(cells, trades),
               describe=desc, passers=["|".join(map(str, k)) for k in keys if cells[k]["gates"]["pass"]])
    _save("design.json", out)
    return out


def run_final():
    global _HOLDOUT_OPEN
    if K.FINAL_FLAG.exists():
        raise SystemExit("R95 holdout already read (results/FINAL_READ); a second look needs a new pre-registration")
    D = json.loads((K.RESULTS / "design.json").read_text())
    if D.get("n_cells") != K.N_CELLS:
        raise SystemExit(f"R95: design.json does not cover all {K.N_CELLS} cells; run the full design first")
    passers = set(D["passers"])
    from research.hunt.r89_pdhl_break import arms as A89     # imported before the flag, so a failure costs no read
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    _HOLDOUT_OPEN = True
    A89.C51.open_final()                    # R51's own guard, for the arms' holdout trades (EX overlay)
    cells, trades, desc, keep = run_cells("final", K.INSTRUMENTS, lambda I: K.HOLDOUT)
    cells.update(run_overlay("final", keep, *K.HOLDOUT))
    keys = sorted(cells, key=lambda k: tuple(map(str, k)))
    for k, qq in zip(keys, bh_q(cells, keys)):
        cells[k]["q_all"] = round(float(qq), 5)
    pk = [k for k in keys if "|".join(map(str, k)) in passers]
    verdict = {}
    for k, qq in zip(pk, bh_q(cells, pk)):
        o = cells[k]["opt"]
        ok = (o.get("net", 0) > 0 and o.get("trades", 0) >= K.HOLD_MIN_TRADES and o.get("p_emp", 1) < K.HOLD_P
              and qq < K.GATE_Q and o.get("worst_month", -1e9) >= K.MONTH_STOP)
        verdict["|".join(map(str, k))] = dict(q=round(float(qq), 5), paper_candidate=bool(ok))
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), cells=_cells_out(cells, keys),
               design_passers=sorted(passers), verdict=verdict, pooled=pool(cells, trades), describe=desc)
    _save("final.json", out)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "counts":
        run_counts(tuple(sys.argv[2:]) or K.INSTRUMENTS)
    elif cmd == "design":
        run_design(tuple(sys.argv[2:]) or K.INSTRUMENTS)
    elif cmd == "final":
        run_final()
