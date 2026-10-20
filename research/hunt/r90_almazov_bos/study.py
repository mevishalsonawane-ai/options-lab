"""R90 study (PREREG.md): every variant on every instrument, design first, then the ONE holdout read.

    IRAALGO_LAKE=~/lake python -m research.hunt.r90_almazov_bos.study design    # 99 cells, twins, BH, gates, walk-forward
    IRAALGO_LAKE=~/lake python -m research.hunt.r90_almazov_bos.study final     # the ONE holdout read (refuses a second)
    IRAALGO_LAKE=~/lake python -m research.hunt.r90_almazov_bos.study arms      # same-day comparison with the arms

Index stores / panels: R89's (`research/hunt/r89_pdhl_break/store.py build` with IRAALGO_LAKE=~/lakeflat first).
Results (small JSON) go to results/; trade lists to $R90_OUT/trades (never git).
"""
from __future__ import annotations

import json
import sys
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from research.hunt.r89_pdhl_break import data as D89
from research.hunt.r89_pdhl_break import store as S89
from research.hunt.r89_pdhl_break.study import bh, stats

from . import cfg as K
from . import model, price
from .structure import aggregate, structure

_HOLDOUT_OPEN = False


# ------------------------------------------------------------------------------------------------ guard
def check(lo: date, hi: date):
    if hi >= K.HOLDOUT[0] and not _HOLDOUT_OPEN:
        raise PermissionError(f"R90: {hi} is in the locked holdout (from {K.HOLDOUT[0]}); only `study.py final` reads it")


def day_range(P, lo: date, hi: date) -> tuple:
    check(lo, hi)
    ix = [i for i, d in enumerate(P.days) if lo <= d <= hi]
    return (ix[0], ix[-1]) if ix else (1, 0)


# ------------------------------------------------------------------------------------------------ one instrument
def epoch_minutes(P) -> np.ndarray:
    eday = np.array([(d - date(1970, 1, 1)).days for d in P.days], np.int64)
    return eday[P.mday] * 1440 + P.mt.astype(np.int64) - K.IST_UTC


def flat_minutes(P) -> np.ndarray:
    """Global minute index of each session's flat time: NSE the first minute at or after 15:15 (15:05 on the index's
    expiry day), exit at its open; MCX the R53 session cut (R89 panel)."""
    if P.kind != "nse":
        return P.sq.copy()
    out = np.empty(P.ndays, np.int64)
    for d in range(P.ndays):
        a, b = int(P.ms[d]), int(P.me[d])
        tgt = K.NSE_FLAT_EXPIRY if P.expiry[d] else K.NSE_FLAT
        out[d] = a + min(int(np.searchsorted(P.mt[a:b], tgt)), b - a - 1)
    return out


class Inst:
    def __init__(self, name: str, P=None, store=None, B=None):
        self.name = name
        self.P = P if P is not None else D89.panel(name)
        self.nse = self.P.kind == "nse"
        self.store = store if store is not None or not self.nse else S89.load(name)
        self.B = B if B is not None or self.nse else price.McxBook(D89.market(name))
        P = self.P
        self.M = dict(mo=P.mo, mh=P.mh, ml=P.ml, mc=P.mc, mt=P.mt)
        self.ep = epoch_minutes(P)
        self.sq = flat_minutes(P)
        self.day_ok = ~P.skip.copy()
        if not self.nse:
            self.day_ok &= ~P.expiry
        self.tick = float(K.TICK.get(name, 0.05))
        self._S: dict = {}
        self._daily = None

    def lot(self, d: int) -> float:
        return float(price.K51.lot(self.name, self.P.days[d])) if self.nse else float(K.MCX_MULT[self.name])

    def bars(self, minutes: int) -> dict:
        P = self.P
        return aggregate(P.mday, self.ep, minutes, P.mo, P.mh, P.ml, P.mc)

    def _reset(self, Bk: dict) -> np.ndarray | None:
        """MCX: restart the structure on the first bar of a contract's first session (R89 skip days)."""
        if self.nse:
            return None
        first = np.r_[True, Bk["day"][1:] != Bk["day"][:-1]]
        return first & self.P.skip[Bk["day"]]

    def daily_trend(self) -> np.ndarray:
        """Per session: the daily structure trend (swing 2) at the previous session's close (0 unknown)."""
        if self._daily is None:
            P = self.P
            S = structure(P.popen, P.day_hi, P.day_lo, P.day_close, 2)
            self._daily = np.r_[0, S.trend[:-1]].astype(np.int8)
        return self._daily

    def struct(self, v: K.Variant):
        key = v.struct_key
        if key not in self._S:
            Bk = self.bars(v.tf)
            Hk = self.bars(v.htf)
            Sl = structure(Bk["o"], Bk["h"], Bk["l"], Bk["c"], v.d, self._reset(Bk))
            Sh = structure(Hk["o"], Hk["h"], Hk["l"], Hk["c"], v.d, self._reset(Hk))
            V = model.htf_view(Bk["s"], Hk["s"], Sh)
            self._S[key] = (Bk, Sl, V)
        return self._S[key]

    def window(self, v: K.Variant) -> tuple:
        if self.nse:
            return (K.NSE_W30 if v.w30 else K.NSE_FIRST), K.NSE_LAST
        return (K.MCX_W30 if v.w30 else 0), K.MCX_LAST

    def trades(self, v: K.Variant, lo: date, hi: date) -> tuple:
        """(session trades, as-written trades) with fills on sessions lo .. hi."""
        a, b = day_range(self.P, lo, hi)
        Bk, Sl, V = self.struct(v)
        k1 = int(np.searchsorted(Bk["day"], b, side="right"))
        ok = self.day_ok.copy()
        ok[:a] = False
        ok[b + 1:] = False
        daily = self.daily_trend()[Bk["day"]] if v.daily else None
        first, last = self.window(v)
        T = model.run_session(self.M, Bk, Sl, V, v.minrr, self.tick, self.sq, first, last, ok, daily, 0, k1)
        W = model.run_as_written(Bk, Sl, V, v.minrr, self.tick, daily, 0, k1)
        W = [w for w in W if a <= int(Bk["day"][w["k_in"]]) <= b]
        for w in W:
            w["day"] = int(Bk["day"][w["k_in"]])
        return T, W

    def run(self, v: K.Variant, lo: date, hi: date, twins: int = K.TWINS, seed: int = K.SEED) -> tuple:
        a, b = day_range(self.P, lo, hi)
        T, W = self.trades(v, lo, hi)
        O = price.option(self, T)
        P = self.P
        rows = []
        for o in O:
            d = int(o["day"])
            lot = self.lot(d)
            held = int(P.mt[o["xm"]]) - int(P.mt[o["t"]])
            rows.append(dict(o, date=P.days[d], tod=int(P.mt[o["t"]]), lot=lot, pts_rs=o["pts"] * lot,
                             fast_stop=bool(o["why"] == "stop" and held < K.FAST_STOP_BARS * v.tf)))
        T = pd.DataFrame(rows)
        n_sess = int(self.day_ok[a:b + 1].sum())
        res = dict(inst=self.name, variant=v.name, lo=str(lo), hi=str(hi), sessions=n_sess, signals=int(len(T)))
        first, last = self.window(v)
        filled = T[T.filled] if len(T) else T
        if len(filled):
            recs = filled.to_dict("records")
            tw = price.twins(self, recs, first, last, twins, np.random.default_rng(seed), "after")
            tww = price.twins(self, recs, first, last, twins, np.random.default_rng(seed + 1), "window")
            o = stats(filled, n_sess, "net")
            o["twin_mean"] = round(float(tw.mean()), 0)
            o["twin_p"] = round((1 + int((tw >= filled.net.sum()).sum())) / (twins + 1), 4)
            o["wtwin_mean"] = round(float(tww.mean()), 0)
            o["wtwin_p"] = round((1 + int((tww >= filled.net.sum()).sum())) / (twins + 1), 4)
            res["opt"] = o
            res["fast_stop"] = round(float(filled.fast_stop.mean()), 4)
            res["why"] = {k: int(x) for k, x in filled.why.value_counts().items()}
            res["calls"] = int((filled.side > 0).sum())
            res["avg_risk_pts"] = round(float(filled.risk.mean()), 2)
            res["avg_reward_pts"] = round(float(filled.reward.mean()), 2)
            res["opt_gross_pts"] = round(float((filled.gross / filled.lot).mean()), 2)
            res["opt_cost_pts"] = round(float(((filled.gross - filled.net) / filled.lot).mean()), 2)
            res["hold_min"] = round(float((P.mt[filled.xm.values] - P.mt[filled.t.values]).mean()), 1)
        else:
            res["opt"] = dict(trades=0, net=0.0, twin_p=1.0, twin_mean=0.0)
        if len(T):
            u = stats(T, n_sess, "pts_rs")
            u["pts_mean"] = round(float(T.pts.mean()), 2)
            u["pts_win"] = round(float((T.pts > 0).mean()), 4)
            res["und"] = u
            res["unfilled"] = int((~T.filled).sum())
        if W:
            pts = np.array([w["pts"] for w in W])
            rs = np.array([w["pts"] * self.lot(w["day"]) for w in W])
            Bk = self.struct(v)[0]
            held = np.array([w["k_out"] - w["k_in"] for w in W])
            res["as_written"] = dict(trades=len(W), pts_mean=round(float(pts.mean()), 2),
                                     pts_sum=round(float(pts.sum()), 1), win=round(float((pts > 0).mean()), 4),
                                     rs=round(float(rs.sum()), 0), held_bars=round(float(held.mean()), 1),
                                     overnight=round(float(np.mean([Bk["day"][w["k_out"]] != Bk["day"][w["k_in"]]
                                                                    for w in W])), 4),
                                     target_share=round(float(np.mean([w["why"] == "target" for w in W])), 4))
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
    """Per instrument: before each fold pick the variant with the best design net so far (>= 20 trades; ties -> A5),
    record its next-fold net, against A5's."""
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
                aw_trades=sum(r.get("as_written", {}).get("trades", 0) for r in rs),
                aw_rs=sum(r.get("as_written", {}).get("rs", 0) for r in rs),
                fast_stop=round(float(np.average([r.get("fast_stop", 0) for r in rs], weights=w)), 4))
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
    aw = r.get("as_written", {})
    print(f"{name:10} {v:4} n {o.get('trades', 0):5} net {o.get('net', 0):>11,.0f} p {o.get('twin_p')} "
          f"und {r.get('und', {}).get('pts_mean')} aw {aw.get('trades')} {aw.get('pts_mean')}", flush=True)


def run_cells(tag: str, insts, window_of) -> tuple:
    cells, trades = {}, {}
    for name in insts:
        I = Inst(name)
        lo, hi = window_of(I)
        trades[name] = {}
        for v in K.VARIANTS:
            r, T = I.run(v, lo, hi)
            r["folds"] = fold_nets(T, name)
            cells[(name, v.name)] = r
            trades[name][v.name] = T
            if len(T):
                T.drop(columns=["date"]).assign(date=T.date.astype(str)).to_parquet(_trades_path(tag, name, v.name))
            _log(name, v.name, r)
    return cells, trades


def run_design(insts=K.INSTRUMENTS):
    cells, trades = run_cells("design", insts, lambda I: K.IDX_DESIGN if I.nse else K.MCX_DESIGN)
    keys = sorted(cells)
    q = bh([cells[k]["opt"].get("twin_p", 1.0) for k in keys])
    for k, qq in zip(keys, q):
        cells[k]["q"] = round(float(qq), 4)
        cells[k]["gates"] = design_gates(cells[k])
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), n_cells=len(cells),
               cells=[cells[k] for k in keys], selection_wf=selection_wf(cells, trades), pooled=pool(cells),
               passers=[f"{k[0]}|{k[1]}" for k in keys if cells[k]["gates"]["pass"]])
    _save("design.json", out)
    return out


def run_final():
    global _HOLDOUT_OPEN
    if K.FINAL_FLAG.exists():
        raise SystemExit("R90 holdout already read (results/FINAL_READ); a second look needs a new pre-registration")
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
               design_passers=sorted(passers), verdict=verdict, pooled=pool(cells))
    _save("final.json", out)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "design"
    if cmd == "design":
        run_design(tuple(sys.argv[2:]) or K.INSTRUMENTS)
    elif cmd == "final":
        run_final()
    elif cmd == "arms":
        from . import arms
        arms.main()
