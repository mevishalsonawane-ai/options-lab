"""R90B (PREREG_B.md): R90's setups with a 5 x 1-minute confirmation, profit lock, stop trail and profit trail.

    IRAALGO_LAKE=~/lake python -m research.hunt.r90_almazov_bos.r90b design
    IRAALGO_LAKE=~/lake python -m research.hunt.r90_almazov_bos.r90b final     # the ONE read of Apr - Oct 2026

Signal source: the underlying for every rule (setup, confirmation, lock, trails are read on the index / future in units
of R = the initial risk in points). The option (1-ITM) is only what is bought.
"""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from datetime import datetime, timezone

import numpy as np
import pandas as pd
from numba import njit

from research.hunt.r85 import strategy as S85
from research.hunt.r89_pdhl_break import cfg as K89
from research.hunt.r89_pdhl_break.study import bh, stats

from . import cfg as K
from . import model, price, study

RESULTS = K.HERE / "results_b"
FINAL_FLAG = RESULTS / "FINAL_READ"
SEED = 91
X_STOP, X_TARGET, X_TIME, X_MOVED = 1, 2, 3, 4
WHY = {X_STOP: "stop", X_TARGET: "target", X_TIME: "time", X_MOVED: "moved_stop"}
_OPEN = False


@dataclass(frozen=True)
class Rule:
    name: str
    confirm: bool = False
    target: bool = True
    lock: bool = False
    trail: bool = False
    give: bool = False


RULES = [Rule("X0"), Rule("C5", True), Rule("LTP", False, False, True, True, True), Rule("C5L", True, lock=True),
         Rule("C5T", True, trail=True), Rule("C5P", True, False, give=True), Rule("C5LTP", True, False, True, True, True)]
CHARTS = ("A1", "A5", "H60")
LOCK_AT, LOCK_TO, TRAIL_R, GIVE = 1.0, 0.5, 1.0, 0.5


# ------------------------------------------------------------------------------------------------ exits
@njit(cache=True)
def exit_path(mo, mh, ml, mc, start, close_only_first, side, ref, stop0, tgt, R, lock, trail, give, sq):
    """First exit from minute `start`. Returns (minute, reason, price). Stops move only after a minute closes.
    tgt NaN = no target. close_only_first: on `start` only its close counts (R90's fill-minute rule)."""
    stop = stop0
    best = ref
    for m in range(start, sq):
        if m == start and close_only_first:
            x = mc[m]
            if side * (x - stop) <= 0:
                return m, 1, x
            if tgt == tgt and side * (x - tgt) >= 0:
                return m, 2, x
            hi = x
        else:
            adverse = ml[m] if side > 0 else mh[m]
            if side * (adverse - stop) <= 0:
                px = min(stop, mo[m]) if side > 0 else max(stop, mo[m])
                return m, (1 if stop == stop0 else 4), px
            fav = mh[m] if side > 0 else ml[m]
            if tgt == tgt and side * (fav - tgt) >= 0:
                return m, 2, tgt
            hi = fav
        if side * (hi - best) > 0:
            best = hi
        gain = side * (best - ref)
        if lock and gain >= LOCK_AT * R:
            lv = ref + side * LOCK_TO * R
            if side * (lv - stop) > 0:
                stop = lv
        if trail:
            lv = best - side * TRAIL_R * R
            if side * (lv - stop) > 0:
                stop = lv
        if give and gain >= R:
            lv = ref + side * (1.0 - GIVE) * gain
            if side * (lv - stop) > 0:
                stop = lv
    return sq, 3, mo[sq]


@njit(cache=True)
def twin_exits(mo, mh, ml, mc, uu, side, risk, reward, use_tgt, lock, trail, give, sq):
    n = len(uu)
    xm = np.empty(n, np.int64)
    why = np.empty(n, np.int64)
    for i in range(n):
        u = uu[i]
        ref = mc[u]
        tgt = ref + side * reward if use_tgt else np.nan
        xm[i], why[i], _ = exit_path(mo, mh, ml, mc, u + 1, False, side, ref, ref - side * risk, tgt, risk, lock, trail,
                                     give, sq)
    return xm, why


def confirmed(M, t: int, side: int, E: float, stop: float, tgt: float, sq: int) -> bool:
    """PREREG_B C5: the 5 one-minute candles after the trigger minute t confirm the direction."""
    mo, mh, ml, mc = M["mo"], M["mh"], M["ml"], M["mc"]
    if t + 6 >= sq:
        return False
    x = mc[t]
    if side * (x - stop) <= 0 or side * (x - tgt) >= 0:
        return False
    for m in range(t + 1, t + 6):
        adverse = ml[m] if side > 0 else mh[m]
        fav = mh[m] if side > 0 else ml[m]
        if side * (adverse - stop) <= 0 or side * (fav - tgt) >= 0:
            return False
    if side * (mc[t + 5] - E) <= 0:
        return False
    good = sum(1 for m in range(t + 1, t + 6) if side * (mc[m] - mo[m]) > 0)
    return good >= 3


# ------------------------------------------------------------------------------------------------ session run
def run(M, B, S, V, minrr, tick, sq, first, last, day_ok, rule: Rule, k0=0, k1=None) -> list:
    """R90 model.run_session with R90B's confirmation and exits. Each trade: day, k, t (trigger), e (reference minute: the
    option is bought at e + 1's open), side, entry, ref, stop, target, risk, reward, xm, why, pts."""
    mo, mh, ml, mc, mt = M["mo"], M["mh"], M["ml"], M["mc"], M["mt"]
    o, c, s, e_, bday = B["o"], B["c"], B["s"], B["e"], B["day"]
    k1 = len(o) if k1 is None else k1
    used_l, used_h = set(), set()
    out, pend, busy = [], None, -1
    for k in range(k0, k1):
        d = int(bday[k])
        if pend is not None and busy < s[k] and k > 0 and int(bday[k - 1]) == d and day_ok[d]:
            side, E, stp, T, lid, hid = pend
            t = -1
            for m in range(s[k], e_[k] + 1):
                if (side > 0 and mh[m] >= E) or (side < 0 and ml[m] <= E):
                    t = m
                    break
            if t >= 0 and first <= mt[t] <= last and t < sq[d]:
                used_l.add(lid)
                risk = side * (E - stp)
                tgt = T if rule.target else np.nan
                if rule.confirm:
                    if confirmed(M, t, side, E, stp, T, int(sq[d])):
                        e, ref = t + 5, float(mc[t + 5])
                        xm, why, px = exit_path(mo, mh, ml, mc, t + 6, False, side, ref, stp, tgt, risk, rule.lock,
                                                rule.trail, rule.give, int(sq[d]))
                    else:
                        e = -1
                else:
                    e = t
                    ref = float(max(E, mo[t]) if side > 0 else min(E, mo[t]))
                    xm, why, px = exit_path(mo, mh, ml, mc, t, True, side, ref, stp, tgt, risk, rule.lock, rule.trail,
                                            rule.give, int(sq[d]))
                if e >= 0:
                    if why == X_TARGET:
                        used_h.add(hid)
                    busy = int(xm)
                    out.append(dict(day=d, k=k, t=int(t), e=int(e), side=side, entry=E, ref=ref, stop=stp, target=T,
                                    risk=float(risk), reward=float(side * (T - E)), xm=int(xm), why=WHY[int(why)],
                                    pts=float(side * (px - ref))))
        pend = None
        if busy <= e_[k]:
            pend = model.setup(k, c[k], S, V, minrr, tick, used_l, used_h, None)
    return out


# ------------------------------------------------------------------------------------------------ pricing
def mcx_fixed(I, j: int, side: int, xg: int):
    """MCX 1-ITM option bought at the first print in (j, j + 2], sold at the close of grid minute xg (else the next print
    within 10 minutes, else the last close): R53 conventions, R85 costs. None if no fill."""
    Bk = I.B
    k = Bk.strike(side, j)
    if not np.isfinite(k):
        return None
    lg = Bk.leg(j, side, k)
    if lg is None:
        return None
    ii, Vv = lg
    a = int(np.searchsorted(ii, j + 1))
    if a >= len(ii) or ii[a] > j + 2 or ii[a] > xg or not Vv[a, 0] > 0:
        return None
    fill = Vv[a, 0]
    q = int(np.searchsorted(ii, xg))
    if q < len(ii) and ii[q] == xg:
        px, t_out = Vv[q, 3], xg
    elif q < len(ii) and ii[q] <= xg + 10 and Bk.gday[ii[q]] == Bk.gday[xg]:
        px, t_out = Vv[q, 0], int(ii[q])
    else:
        px, t_out = Vv[q - 1, 3], xg
    if not np.isfinite(px):
        return None
    mult = K.MCX_MULT[I.name]
    buy, sell = fill * mult, px * mult
    cost = S85.opt_cost(K89.MCX_HALF_BOOK[I.name], buy, sell, int(Bk.m.tod[ii[a]]), int(Bk.m.tod[min(t_out, len(Bk.F) - 1)]))
    return float(sell - buy - cost), float(sell - buy)


def option(I, trades: list) -> list:
    out = [dict(t) for t in trades]
    P = I.P
    if I.nse:
        by_day: dict = {}
        for i, tr in enumerate(out):
            by_day.setdefault(tr["day"], []).append(i)
        for d, ix in by_day.items():
            dt = P.days[d]
            lot = float(price.K51.lot(I.name, dt))
            rows = [int(P.mt[out[i]["e"]]) - K.NSE_OPEN for i in ix]
            fx = [(int(P.mt[out[i]["xm"]]) - K.NSE_OPEN + 1) if out[i]["why"] != "time" else -1 for i in ix]
            net, gross, _, _, ok = price.idx_option_day(I.name, I.store[dt], ["C" if out[i]["side"] > 0 else "P"
                                                                              for i in ix], rows, fx, [lot] * len(ix))
            for j, i in enumerate(ix):
                out[i].update(net=float(net[j]), gross=float(gross[j]), filled=bool(ok[j]), lot=lot)
    else:
        g = P.extra["gidx"]
        for tr in out:
            xg = int(g[tr["xm"]]) if tr["why"] != "time" else int(g[I.sq[tr["day"]]])
            r = mcx_fixed(I, int(g[tr["e"]]), tr["side"], xg)
            tr.update(net=r[0] if r else 0.0, gross=r[1] if r else 0.0, filled=r is not None,
                      lot=float(K.MCX_MULT[I.name]))
    return out


def twins(I, trades: list, rule: Rule, last: int, n: int, rng) -> np.ndarray:
    """After-trigger twins: entry minute uniform from the trade's reference minute to the window's last minute."""
    P = I.P
    tot = np.zeros(n)
    g = P.extra.get("gidx")
    for tr in trades:
        d = int(tr["day"])
        sq = int(I.sq[d])
        pool = price._pool(P, d, int(tr["e"]), 0, last, sq - 1)
        if not len(pool):
            continue
        s = int(tr["side"])
        uu = pool[rng.integers(0, len(pool), size=n)]
        xm, why = twin_exits(P.mo, P.mh, P.ml, P.mc, uu, s, float(tr["risk"]), float(tr["reward"]), rule.target,
                             rule.lock, rule.trail, rule.give, sq)
        if I.nse:
            dt = P.days[d]
            lot = float(price.K51.lot(I.name, dt))
            rows = (P.mt[uu] - K.NSE_OPEN).astype(np.int64)
            fx = np.where(why != X_TIME, P.mt[xm] - K.NSE_OPEN + 1, -1)
            net, _, _, _, _ = price.idx_option_day(I.name, I.store[dt], ["C" if s > 0 else "P"] * n, rows, fx, [lot] * n)
            tot += net
        else:
            for i in range(n):
                xg = int(g[xm[i]]) if why[i] != X_TIME else int(g[sq])
                r = mcx_fixed(I, int(g[uu[i]]), s, xg)
                tot[i] += r[0] if r else 0.0
    return tot


# ------------------------------------------------------------------------------------------------ study
def cell(I, chart: str, rule: Rule, lo, hi, n_twins=K.TWINS) -> tuple:
    a, b = study.day_range(I.P, lo, hi) if not _OPEN else _range(I.P, lo, hi)
    v = K.BY_NAME[chart]
    Bk, Sl, V = I.struct(v)
    k1 = int(np.searchsorted(Bk["day"], b, side="right"))
    ok = I.day_ok.copy()
    ok[:a] = False
    ok[b + 1:] = False
    first, last = I.window(v)
    T = run(I.M, Bk, Sl, V, v.minrr, I.tick, I.sq, first, last, ok, rule, 0, k1)
    O = option(I, T)
    P = I.P
    rows = [dict(o, date=P.days[o["day"]], pts_rs=o["pts"] * o["lot"]) for o in O]
    T = pd.DataFrame(rows)
    n_sess = int(I.day_ok[a:b + 1].sum())
    res = dict(inst=I.name, variant=f"{chart}-{rule.name}", chart=chart, rule=rule.name, lo=str(lo), hi=str(hi),
               sessions=n_sess, signals=int(len(T)))
    f = T[T.filled] if len(T) else T
    if len(f):
        tw = twins(I, f.to_dict("records"), rule, last, n_twins, np.random.default_rng(SEED))
        o = stats(f, n_sess, "net")
        o["twin_mean"] = round(float(tw.mean()), 0)
        o["twin_p"] = round((1 + int((tw >= f.net.sum()).sum())) / (n_twins + 1), 4)
        res["opt"] = o
        res["why"] = {k: int(x) for k, x in f.why.value_counts().items()}
        res["und_pts"] = round(float(f.pts.mean()), 2)
        res["opt_gross_pts"] = round(float((f.gross / f.lot).mean()), 2)
        res["hold_min"] = round(float((P.mt[f.xm.values] - P.mt[f.e.values]).mean()), 1)
    else:
        res["opt"] = dict(trades=0, net=0.0, twin_p=1.0, twin_mean=0.0)
    res["folds"] = study.fold_nets(T, I.name) if len(T) else [dict(lo=str(x), hi=str(y), trades=0, net=0.0)
                                                              for x, y in study.folds_of(I.name)]
    return res, T


def _range(P, lo, hi):
    ix = [i for i, d in enumerate(P.days) if lo <= d <= hi]
    return (ix[0], ix[-1]) if ix else (1, 0)


def run_all(tag: str, window_of) -> dict:
    cells = {}
    for name in K.INSTRUMENTS:
        I = study.Inst(name)
        lo, hi = window_of(I)
        for ch in CHARTS:
            for r in RULES:
                res, T = cell(I, ch, r, lo, hi)
                cells[(name, res["variant"])] = res
                if len(T):
                    p = K.OUT / "trades_b" / tag
                    p.mkdir(parents=True, exist_ok=True)
                    T.drop(columns=["date"]).assign(date=T.date.astype(str)).to_parquet(p / f"{name}_{res['variant']}.parquet")
                o = res["opt"]
                print(f"{name:10} {res['variant']:9} n {o.get('trades', 0):5} win {o.get('win', 0):.2f} net "
                      f"{o.get('net', 0):>11,.0f} p {o.get('twin_p')}", flush=True)
    return cells


def pooled(cells: dict) -> dict:
    out = {}
    for grp, mem in (("indices", K.INDICES), ("mcx", K.MCX)):
        for ch in CHARTS:
            for r in RULES:
                rs = [cells[(m, f"{ch}-{r.name}")] for m in mem]
                n = sum(x["opt"].get("trades", 0) for x in rs)
                w = sum(x["opt"].get("win", 0) * x["opt"].get("trades", 0) for x in rs)
                out[f"{grp}|{ch}-{r.name}"] = dict(trades=n, win=round(w / n, 4) if n else None,
                                                   net=sum(x["opt"].get("net", 0) for x in rs),
                                                   twin_mean=sum(x["opt"].get("twin_mean", 0) for x in rs))
    return out


def _save(name, obj):
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / name).write_text(json.dumps(obj, indent=1, default=str))


def design():
    cells = run_all("design", lambda I: K.IDX_DESIGN if I.nse else K.MCX_DESIGN)
    keys = sorted(cells)
    q = bh([cells[k]["opt"].get("twin_p", 1.0) for k in keys])
    for k, qq in zip(keys, q):
        cells[k]["q"] = round(float(qq), 4)
        cells[k]["gates"] = study.design_gates(cells[k])
    _save("design.json", dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), n_cells=len(cells),
                              cells=[cells[k] for k in keys], pooled=pooled(cells),
                              passers=[f"{k[0]}|{k[1]}" for k in keys if cells[k]["gates"]["pass"]]))


def final():
    global _OPEN
    if FINAL_FLAG.exists():
        raise SystemExit("R90B check window already read (results_b/FINAL_READ)")
    D = json.loads((RESULTS / "design.json").read_text())
    FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    _OPEN = True
    cells = run_all("final", lambda I: K.HOLDOUT)
    keys = sorted(cells)
    q = bh([cells[k]["opt"].get("twin_p", 1.0) for k in keys])
    for k, qq in zip(keys, q):
        cells[k]["q_all"] = round(float(qq), 4)
    _save("final.json", dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"), cells=[cells[k] for k in keys],
                             design_passers=D["passers"], pooled=pooled(cells)))


if __name__ == "__main__":
    {"design": design, "final": final}[sys.argv[1] if len(sys.argv) > 1 else "design"]()
