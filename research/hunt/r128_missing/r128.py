"""R128 (PREREG.md): what we were missing, tested for an option BUYER.

    python research/hunt/r128_missing/r128.py      # T1-T4 -> results/results.json

T1 straddle timing by forecast-vs-implied range; T2 convex exits on R123's S4/S5 entries; T3 late-day momentum with
2-ITM options; T4 where a buyer's money goes. Uses R123's cached rows and out-of-sample forecasts; option bars from the
cleaned lake; every exit intraday.
"""
from __future__ import annotations

import json
import math
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r124_big_moves"))
import r124cfg as K  # noqa: E402  (puts r123 on the path)
import r123cfg as K3  # noqa: E402
import r123data as D3  # noqa: E402
import r123rules as R3  # noqa: E402

R123_OUT = Path(os.environ.get("R123_OUT", K3.CACHE_ROOT / "r123")).expanduser()
OUT = Path(os.environ.get("R128_OUT", K3.CACHE_ROOT / "r128")).expanduser()
RESULTS = HERE / "results"
N = K.N
O_, H_, L_, C_, V_ = range(5)
FOLDS = dict(K3.FOLDS)
HOLD = K3.HOLDOUT
TWINS = 1000
SEED = 128
RANGE_K = 1.6
LOOK_Q, TOP_Q = 60, 0.90
T1_EXITS = ("a", "b", "c")
T3_LOOK, T3_TOP = 250, 0.70
ROW_1459, ROW_1500, ROW_1519, ROW_0944 = K.row(14, 59), K.row(15, 0), K.row(15, 19), K.row(9, 44)


def period_of(d) -> str | None:
    if HOLD[0] <= d <= HOLD[1]:
        return "holdout"
    for k, (a, b) in FOLDS.items():
        if a <= d <= b:
            return k
    return None


# ------------------------------------------------------------------------------------------------ option helpers
def vol5(a, r, lot):
    return float(np.nansum(a[max(r - 5, 0):r, V_])) / lot


def buy_fill(a, r, lot):
    if a is None or not np.isfinite(a[r, O_]) or a[r, O_] <= 0:
        return None
    return float(D3.FILLS.buy(np.array([a[r, O_]]), np.array([vol5(a, r, lot)]))[0]), float(a[r, O_])


def sell_at(a, r0, r, lot, stop=False, price=None):
    """Sell at `price` (raw) or else the last traded close in r0..r. Returns (fill, raw) or None."""
    if price is None:
        fin = np.flatnonzero(np.isfinite(a[r0:r + 1, C_]))
        if not len(fin):
            return None
        rr = r0 + fin[-1]
        price = float(a[rr, C_])
    else:
        rr = r
    return float(D3.FILLS.sell(np.array([price]), np.array([vol5(a, rr, lot)]), stop=stop)[0]), price


def leg_net(buy, raw_buy, sell, raw_sell, lot, d, und):
    net = (sell - buy) * lot - D3.charge(True, buy, lot, d, und) - D3.charge(False, sell, lot, d, und)
    slip = (buy - raw_buy + raw_sell - sell) * lot
    return net, net - slip


def last_close(a, r0, r):
    fin = np.flatnonzero(np.isfinite(a[r0:r + 1, C_]))
    return float(a[r0 + fin[-1], C_]) if len(fin) else np.nan


# ------------------------------------------------------------------------------------------------ T1 straddles
def straddle(ca, pa, t, last, lot, d, und, rule):
    """Buy the ATM call and put at the open of t+1; exit per rule a (60 min) / b (square-off) / c (stop -35%, after +20%
    trail to 85% of the peak, marked at minute closes). Returns dict or None."""
    r0 = t + 1
    if r0 > last:
        return None
    bc, bp = buy_fill(ca, r0, lot), buy_fill(pa, r0, lot)
    if bc is None or bp is None:
        return None
    cost_raw = bc[1] + bp[1]
    xr, why = (min(t + 60, last), "time") if rule == "a" else (last, "close")
    if rule == "c":
        peak = cost_raw
        for r in range(r0, last + 1):
            v = last_close(ca, r0, r) + last_close(pa, r0, r)
            if not np.isfinite(v):
                continue
            peak = max(peak, v)
            if v <= 0.65 * cost_raw:
                xr, why = r, "stop"
                break
            if peak >= 1.20 * cost_raw and v <= 0.85 * peak:
                xr, why = r, "trail"
                break
    sc, sp = sell_at(ca, r0, xr, lot), sell_at(pa, r0, xr, lot)
    if sc is None or sp is None:
        return None
    n1, x1 = leg_net(bc[0], bc[1], sc[0], sc[1], lot, d, und)
    n2, x2 = leg_net(bp[0], bp[1], sp[0], sp[1], lot, d, und)
    charges = sum(D3.charge(b, p, lot, d, und) for b, p in ((True, bc[0]), (True, bp[0]), (False, sc[0]), (False, sp[0])))
    return dict(net=n1 + n2, netx2=x1 + x2, exit=xr, why=why, cost_rs=cost_raw * lot, cost_pts=cost_raw,
                charges=charges, slip=(n1 + n2) - (x1 + x2))


# ------------------------------------------------------------------------------------------------ T2 convex exits
def convex_exit(a, t, last, lot, d, und, x, y, mode):
    """The original cell's option bought at t+1; the original -y stop (trigger on a bar's low, fill at min(stop,
    open), stop fills); mode x1: once net (marked at minute closes) >= +Rs 1,000, exit when it falls to 60% of its peak;
    mode x2: no target. Else the square-off."""
    r0 = t + 1
    b = buy_fill(a, r0, lot)
    if b is None:
        return None
    buy, raw_buy = b
    v0 = vol5(a, r0, lot)
    pt, sp = D3.target_stop(buy, lot, x, y, v0, d, und)
    cb = D3.charge(True, buy, lot, d, und)

    def mark(px):
        s = float(D3.FILLS.sell(np.array([px]), np.array([v0]))[0])
        return (s - buy) * lot - cb - D3.charge(False, s, lot, d, und)

    peak = -np.inf
    for r in range(r0, last + 1):
        if not (np.isfinite(a[r, L_]) and np.isfinite(a[r, C_])):
            continue
        if sp is not None and a[r, L_] <= sp:
            raw = float(min(sp, a[r, O_])) if np.isfinite(a[r, O_]) else float(sp)
            s = sell_at(a, r0, r, lot, stop=True, price=raw)
            n, nx = leg_net(buy, raw_buy, s[0], s[1], lot, d, und)
            return dict(net=n, netx2=nx, exit=r, why="stop")
        m = mark(float(a[r, C_]))
        peak = max(peak, m)
        if mode == "x1" and peak >= 1000.0 and m <= 0.6 * peak:
            s = sell_at(a, r0, r, lot, price=float(a[r, C_]))
            n, nx = leg_net(buy, raw_buy, s[0], s[1], lot, d, und)
            return dict(net=n, netx2=nx, exit=r, why="trail")
    s = sell_at(a, r0, last, lot)
    if s is None:
        return None
    n, nx = leg_net(buy, raw_buy, s[0], s[1], lot, d, und)
    return dict(net=n, netx2=nx, exit=last, why="close")


# ------------------------------------------------------------------------------------------------ per index
def run_index(args):
    und, P_u, S_trades = args
    g = D3.load_grid(und, HOLD[1])
    LO = D3.LakeOptions(und, HOLD[1])
    exp = LO.expiry_days()
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und))
    pos = g.pos()
    out = dict(t1=[], t2=[], t3=[], t4=[])
    # ---- T1 signal (rolling 90th percentile of Q over the previous 60 sessions, earlier rows only)
    P_u = P_u.sort_values(["day", "row"]).reset_index(drop=True)
    days = sorted(P_u.day.unique())
    qs_by_day = {d: P_u.Q.values[P_u.day.values == d] for d in days}
    thr = {}
    for k, d in enumerate(days):
        prev = days[max(k - LOOK_Q, 0):k]
        if len(prev) >= LOOK_Q:
            v = np.concatenate([qs_by_day[x] for x in prev])
            v = v[np.isfinite(v)]
            thr[d] = float(np.quantile(v, TOP_Q)) if len(v) else np.nan
    # ---- T3 signal series over every session (|r| percentile over the previous 250 sessions)
    mids_last = {}
    for i, d in enumerate(g.days):
        fin = np.flatnonzero(np.isfinite(g.h[i]) & np.isfinite(g.l[i]))
        mids_last[d] = (g.h[i, fin[-1]] + g.l[i, fin[-1]]) / 2 if len(fin) else np.nan
    t3_hist = {"a": [], "b": []}
    t3_days = set()
    for i in range(1, len(g.days)):
        d = g.days[i]
        pm = mids_last[g.days[i - 1]]
        for var, rr in (("a", ROW_1459), ("b", ROW_0944)):
            m = (g.h[i, rr] + g.l[i, rr]) / 2
            r = m / pm - 1 if np.isfinite(m) and np.isfinite(pm) and pm > 0 else np.nan
            hist = [x for x in t3_hist[var][-T3_LOOK:] if np.isfinite(x)]
            if np.isfinite(r) and len(hist) >= T3_LOOK // 2 and d not in exp and period_of(d) is not None:
                if abs(r) >= np.quantile(np.abs(hist), T3_TOP):
                    t3_days.add((d, var, float(np.sign(r))))
            t3_hist[var].append(r)
    need_days = set(P_u.day[P_u.Q >= P_u.day.map(thr).fillna(np.inf)]) | set(S_trades.day) | {x[0] for x in t3_days}
    need_days |= set(P_u.day[P_u.row == K.row(9, 59)])                     # T4 10:00 straddles
    for d in sorted(need_days):
        i = pos.get(d)
        if i is None:
            continue
        opts = LO.day(d, g.c[i])
        if opts is None:
            continue
        is_exp = d in exp
        last = K3.EXIT_LAST_EXPIRY if is_exp else K3.EXIT_LAST
        per = period_of(d)
        Pd = P_u[P_u.day == d]
        rows_today = Pd.row.values

        def atm_pair(t):
            c = D3._last(g.c[i][:t + 1])
            if not np.isfinite(c):
                return None, None, None
            k = D3.atm(c, step)
            return opts.contracts.get(("C", k)), opts.contracts.get(("P", k)), c

        # ---- T1: signal rows, one open straddle per index (per exit rule), at most 5 a day
        th = thr.get(d, np.nan)
        sig = Pd[(Pd.Q >= th)] if np.isfinite(th) else Pd.iloc[0:0]
        for rule in T1_EXITS:
            busy, n = -1, 0
            for t, ivt in zip(sig.row.values, sig.atm_iv.values):
                if n >= 5 or t <= busy:
                    continue
                ca, pa, c0 = atm_pair(t)
                s = straddle(ca, pa, t, last, lot, d, und, rule) if ca is not None and pa is not None else None
                if s is None:
                    continue
                busy, n = s["exit"], n + 1
                tw = []
                for _ in range(TWINS // 10):                               # 100 twins a trade (pooled to 1,000)
                    t1 = int(rng.choice(rows_today))
                    c1, p1, _ = atm_pair(t1)
                    s1 = straddle(c1, p1, t1, last, lot, d, und, rule) if c1 is not None and p1 is not None else None
                    if s1 is not None:
                        tw.append(s1["net"])
                ex = s["exit"]
                hi, lo = np.nanmax(g.h[i, t + 1:ex + 1]), np.nanmin(g.l[i, t + 1:ex + 1])
                iv_exit = Pd.atm_iv.values[np.searchsorted(rows_today, min(ex, rows_today[-1]))] \
                    if len(rows_today) else np.nan
                out["t1"].append(dict(rule=f"T1{rule}", und=und, day=d, row=t, period=per, net=s["net"],
                                      netx2=s["netx2"], why=s["why"], exit=ex, cost_rs=s["cost_rs"],
                                      cost_pts=s["cost_pts"], charges=s["charges"], slip=s["slip"],
                                      move_pts=abs((g.h[i, ex] + g.l[i, ex]) / 2 - c0) if np.isfinite(g.h[i, ex]) else np.nan,
                                      range_pts=hi - lo, iv0=ivt, iv1=iv_exit,
                                      Q=float(Pd.Q.values[rows_today == t][0]), twins=tw))
        # ---- T4: every 10:00 straddle (held 60 minutes), for the breakdown
        t10 = K.row(9, 59)
        if t10 in set(rows_today):
            ca, pa, c0 = atm_pair(t10)
            s = straddle(ca, pa, t10, last, lot, d, und, "a") if ca is not None and pa is not None else None
            if s is not None:
                ex = s["exit"]
                out["t4"].append(dict(und=und, day=d, period=per, net=s["net"], cost_rs=s["cost_rs"],
                                      cost_pts=s["cost_pts"], charges=s["charges"], slip=s["slip"],
                                      move_pts=abs((g.h[i, ex] + g.l[i, ex]) / 2 - c0) if np.isfinite(g.h[i, ex]) else np.nan,
                                      Q=float(Pd.Q.values[rows_today == t10][0])))
        # ---- T2: R123's S4/S5 entries with convex exits
        for tr in S_trades[S_trades.day == d].itertuples(index=False):
            x, y, _h = K3.CELLS[int(tr.j) // 2]
            side = "C" if int(tr.j) % 2 == 0 else "P"
            other = "P" if side == "C" else "C"
            c = D3._last(g.c[i][:tr.row + 1])
            if not np.isfinite(c):
                continue
            k = D3.atm(c, step)
            a, ao = opts.contracts.get((side, k)), opts.contracts.get((other, k))
            for mode in ("x1", "x2"):
                r_ = convex_exit(a, tr.row, last, lot, d, und, x, y, mode) if a is not None else None
                if r_ is None:
                    continue
                ro = convex_exit(ao, tr.row, last, lot, d, und, x, y, mode) if ao is not None else None
                out["t2"].append(dict(rule=f"T2-{tr.rule}{mode}", und=und, day=d, row=tr.row, period=per,
                                      net=r_["net"], netx2=r_["netx2"], why=r_["why"], orig_net=tr.net,
                                      other_net=np.nan if ro is None else ro["net"]))
        # ---- T3: late-day momentum, 2-ITM option, 15:00 -> 15:19
        for (dd, var, sgn) in [x for x in t3_days if x[0] == d]:
            c = D3._last(g.c[i][:ROW_1500])
            if not np.isfinite(c):
                continue
            k = D3.atm(c, step)
            res = {}
            for s_, kk in (("C", k - 2 * step), ("P", k + 2 * step)):
                a = opts.contracts.get((s_, kk))
                b = buy_fill(a, ROW_1500, lot) if a is not None else None
                if b is None:
                    continue
                sl = sell_at(a, ROW_1500, ROW_1519, lot)
                if sl is None:
                    continue
                res[s_] = leg_net(b[0], b[1], sl[0], sl[1], lot, d, und)
            side = "C" if sgn > 0 else "P"
            if side in res:
                o = "P" if side == "C" else "C"
                out["t3"].append(dict(rule=f"T3{var}", und=und, day=d, row=ROW_1500, period=per, net=res[side][0],
                                      netx2=res[side][1], other_net=res[o][0] if o in res else np.nan, why="time"))
    print(f"R128 {und}: T1 {len(out['t1'])}, T2 {len(out['t2'])}, T3 {len(out['t3'])}, T4 {len(out['t4'])}", flush=True)
    return out


# ------------------------------------------------------------------------------------------------ statistics
def summarise(T: pd.DataFrame, sessions: dict, twin_kind: str, rng) -> dict:
    """Walk-forward (F1-F4 pooled) and holdout stats, twins and fold signs."""
    res = {}
    for part, sel in (("wf", T.period.isin(list(FOLDS))), ("holdout", T.period == "holdout")):
        X = T[sel].sort_values(["day", "row"]).reset_index(drop=True)
        X = X.assign(cls=0, j=0)
        s = R3.stats(X, sessions[part])
        s.pop("by_cell", None)
        if len(X):
            if twin_kind == "rows":
                tot = np.zeros(TWINS)
                for tw in X.twins:
                    tw = np.asarray(tw, dtype=float)
                    if len(tw):
                        tot += rng.choice(tw, TWINS)
                    else:
                        tot += 0.0
            else:                                                              # random side: own vs other option
                own, oth = X.net.values, np.where(np.isfinite(X.other_net.values), X.other_net.values, X.net.values)
                flips = rng.integers(0, 2, (TWINS, len(X))).astype(bool)
                tot = np.where(flips, oth, own).sum(1)
            s["p_twins"] = float((1 + (tot >= X.net.sum()).sum()) / (1 + TWINS))
            s["twin_mean"] = float(tot.mean())
            s["exits"] = {k: int(v) for k, v in X.why.value_counts().items()}
            if "orig_net" in X:
                s["orig_avg_net"] = float(X.orig_net.mean())
                s["orig_total"] = float(X.orig_net.sum())
        else:
            s["p_twins"] = 1.0
        if part == "wf":
            s["folds"] = {f: float(X.net[X.period == f].sum()) for f in FOLDS}
            s["folds_positive"] = int(sum(v > 0 for v in s["folds"].values()))
        res[part] = s
    return res


def deciles(T1a: pd.DataFrame) -> list:
    out = []
    if not len(T1a):
        return out
    q = pd.qcut(T1a.Q.rank(method="first"), 10, labels=False)
    for k in range(10):
        X = T1a[q == k]
        out.append(dict(decile=k + 1, n=int(len(X)), Q=float(X.Q.mean()), avg_net=float(X.net.mean()),
                        realised_vs_cost=float((X.range_pts / X.cost_pts).mean())))
    return out


def breakdown(X: pd.DataFrame) -> dict:
    if not len(X):
        return {}
    return dict(n=int(len(X)), avg_cost_rs=float(X.cost_rs.mean()), avg_charges=float(X.charges.mean()),
                avg_slippage=float(X.slip.mean()), avg_net=float(X.net.mean()),
                avg_move_pts=float(X.move_pts.mean()), avg_cost_pts=float(X.cost_pts.mean()),
                share_move_beats_cost=float((X.move_pts > X.cost_pts).mean()),
                net_before_costs=float((X.net + X.charges + X.slip).mean()))


def run():
    from multiprocessing import Pool
    P = pd.concat([pd.read_parquet(R123_OUT / "wf_rows_preds_N1.parquet"),
                   pd.read_parquet(R123_OUT / "holdout_rows_preds_N1.parquet").assign(fold="holdout")],
                  ignore_index=True)
    F = np.exp(P.nn_rg_60) / 100 * P.close
    I = RANGE_K * P.atm_iv / 100 * P.close * math.sqrt(60 / (252 * 375))
    P["Q"] = F / I
    S = []
    for rule in ("S4", "S5"):
        for part in ("wf", "holdout"):
            p = R123_OUT / f"trades_N1-{rule}_{part}.parquet"
            if p.exists():
                S.append(pd.read_parquet(p).assign(rule=rule))
    S = pd.concat(S, ignore_index=True)
    args = [(u, P[P.und == u].copy(), S[S.und == u].copy()) for u in K.UNDS]
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(run_index, args)
    T1 = pd.DataFrame([x for r in res for x in r["t1"]])
    T2 = pd.DataFrame([x for r in res for x in r["t2"]])
    T3 = pd.DataFrame([x for r in res for x in r["t3"]])
    T4 = pd.DataFrame([x for r in res for x in r["t4"]])
    sessions = dict(wf=sorted(P.day[P.fold.isin(list(FOLDS))].unique()), holdout=sorted(P.day[P.fold == "holdout"].unique()))
    rng = np.random.default_rng(SEED)
    out = dict(rules={})
    for rule in [f"T1{r}" for r in T1_EXITS]:
        out["rules"][rule] = summarise(T1[T1.rule == rule], sessions, "rows", rng)
    for rule in ("T2-S4x1", "T2-S4x2", "T2-S5x1", "T2-S5x2"):
        out["rules"][rule] = summarise(T2[T2.rule == rule], sessions, "side", rng)
    for rule in ("T3a", "T3b"):
        out["rules"][rule] = summarise(T3[T3.rule == rule], sessions, "side", rng)
    ids = list(out["rules"])
    for part in ("wf", "holdout"):
        for rid, q in zip(ids, R3.bh([out["rules"][r][part].get("p_twins", 1.0) for r in ids])):
            out["rules"][rid][part]["q"] = q
    for rid, s in out["rules"].items():
        w, h = s["wf"], s["holdout"]
        s["gates"] = dict(wf_n=w.get("n", 0) >= 300, wf_net=w.get("avg_net", -1) > 0 and w.get("avg_net_x2", -1) > 0,
                          wf_folds=w.get("folds_positive", 0) >= 3, wf_q=w.get("q", 1) <= 0.10,
                          ho_n=h.get("n", 0) >= 100, ho_net=h.get("avg_net", -1) > 0 and h.get("avg_net_x2", -1) > 0,
                          ho_q=h.get("q", 1) <= 0.10,
                          account=h.get("max_dd", -1e9) > -40000 and h.get("worst_month", -1e9) > -13000)
        s["passes"] = all(s["gates"].values())
    T1a = T1[T1.rule == "T1a"]
    out["t1_deciles"] = dict(wf=deciles(T1a[T1a.period.isin(list(FOLDS))]),
                             holdout=deciles(T1a[T1a.period == "holdout"]))
    weekly = T1a.und.isin(["NIFTY", "SENSEX"])
    out["t1a_weekly_vs_monthly"] = {
        "NIFTY+SENSEX": dict(n=int(weekly.sum()), avg_net=float(T1a.net[weekly].mean()) if weekly.any() else None),
        "monthly-expiry indices after Nov 2024": dict(
            n=int((~weekly & (T1a.day >= date(2024, 11, 20))).sum()),
            avg_net=float(T1a.net[~weekly & (T1a.day >= date(2024, 11, 20))].mean())
            if (~weekly & (T1a.day >= date(2024, 11, 20))).any() else None)}
    out["t4"] = dict(t1a_trades=breakdown(T1a), every_10am_straddle=breakdown(T4),
                     every_10am_by_period={p: breakdown(T4[T4.period == p]) for p in ["F1", "F2", "F3", "F4", "holdout"]})
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    for name, X in (("t1", T1), ("t2", T2), ("t3", T3), ("t4", T4)):
        X.drop(columns=[c for c in ("twins",) if c in X], errors="ignore").to_parquet(OUT / f"{name}.parquet")
    print({k: (v["wf"].get("n"), round(v["wf"].get("avg_net") or 0), v["holdout"].get("n"),
               round(v["holdout"].get("avg_net") or 0), v["passes"]) for k, v in out["rules"].items()})


if __name__ == "__main__":
    run()
