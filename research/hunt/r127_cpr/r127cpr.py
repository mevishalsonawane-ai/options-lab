"""R127: Boss's CPR video strategy (PREREG.md), on the Drive lake, all five indices, buying the ATM option.

    python research/hunt/r127_cpr/r127cpr.py run       # trades, twins, claims -> results/results.json

Levels use yesterday's H, L, C (the formula) and today's open (the bias), as the video defines them; every touch,
bounce, stop and target is read on the index's minute wicks (highs and lows).
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r124_big_moves"))
import r124cfg as K  # noqa: E402  (puts r123 on the path)
import r123data as D3  # noqa: E402
import r123rules as R3  # noqa: E402

N = K.N
RESULTS = HERE / "results"
TOUCH, BUF = 0.0003, 0.0003
FIRST_DEC, LAST_DEC = 4, K.row(14, 30)              # decisions from the 09:19 bar's close to the 14:30 bar
NARROW_LOOK = 60
VARIANTS = {"V1": dict(tf=5, narrow=False), "V2": dict(tf=5, narrow=True), "V3": dict(tf=1, narrow=False)}
TWINS = 200
SEED = 127


def levels(h: float, l: float, c: float) -> dict:
    p = (h + l + c) / 3
    bc = (h + l) / 2
    tc = 2 * p - bc
    return dict(P=p, top=max(tc, bc), bottom=min(tc, bc), R1=2 * p - l, R2=p + (h - l), S1=2 * p - h, S2=p - (h - l),
                width=(max(tc, bc) - min(tc, bc)) / p)


def find_setup(h: np.ndarray, l: np.ndarray, top: float, bottom: float, side: int, tf: int):
    """The first valid pullback-hold-bounce (wicks). side +1: a completed tf-candle whose low touches top and stays
    above bottom; within the next 3 candles a high above that candle's high, with no low below bottom first.
    Returns the bounce minute j (entry at j + 1) or None. side -1 mirrors."""
    for k in range(0, N // tf):
        a, e = k * tf, k * tf + tf - 1
        if e < FIRST_DEC or e > LAST_DEC:
            continue
        hh, ll = h[a:e + 1], l[a:e + 1]
        if not (np.isfinite(hh).any() and np.isfinite(ll).any()):
            continue
        H, L = np.nanmax(hh), np.nanmin(ll)
        if side > 0:
            if not (L <= top * (1 + TOUCH) and L > bottom):
                continue
        else:
            if not (H >= bottom * (1 - TOUCH) and H < top):
                continue
        for j in range(e + 1, min(e + 3 * tf, N - 2) + 1):
            if not (np.isfinite(h[j]) and np.isfinite(l[j])):
                continue
            broke = l[j] < bottom if side > 0 else h[j] > top
            if broke:
                break
            if (side > 0 and h[j] > H) or (side < 0 and l[j] < L):
                return j
    return None


def exit_path(h, l, o, r0: int, side: int, stop: float, tgt: float, last: int):
    """(exit minute, why, index exit price) on wicks from minute r0 (the entry minute) to `last`."""
    for r in range(r0, last + 1):
        if not (np.isfinite(h[r]) and np.isfinite(l[r])):
            continue
        hit_stop = l[r] <= stop if side > 0 else h[r] >= stop
        hit_tgt = h[r] >= tgt if side > 0 else l[r] <= tgt
        if hit_stop:
            px = min(stop, o[r]) if side > 0 and np.isfinite(o[r]) else (max(stop, o[r]) if np.isfinite(o[r]) else stop)
            return r, "stop", px
        if hit_tgt:
            return r, "target", tgt
    fin = [r for r in range(r0, last + 1) if np.isfinite(h[r])]
    if not fin:
        return None
    r = fin[-1]
    return r, "time", (h[r] + l[r]) / 2


def option_trade(a: np.ndarray, r0: int, xr: int, why: str, lot: int, d, und: str):
    """Buy at the open of r0, sell at the last traded close at or before xr (stop fills on a stop)."""
    if a is None or not np.isfinite(a[r0, 0]) or a[r0, 0] <= 0:
        return None
    v0 = float(np.nansum(a[max(r0 - 5, 0):r0, 4])) / lot
    buy = float(D3.FILLS.buy(np.array([a[r0, 0]]), np.array([v0]))[0])
    fin = np.flatnonzero(np.isfinite(a[r0:xr + 1, 3]))
    if not len(fin):
        return None
    rr = r0 + fin[-1]
    raw = float(a[rr, 3])
    v = float(np.nansum(a[max(rr - 5, 0):rr, 4])) / lot
    sell = float(D3.FILLS.sell(np.array([raw]), np.array([v]), stop=(why == "stop"))[0])
    net = (sell - buy) * lot - D3.charge(True, buy, lot, d, und) - D3.charge(False, sell, lot, d, und)
    slip = (buy - float(a[r0, 0]) + raw - sell) * lot
    return net, net - slip


def run_index(und: str, log=print) -> tuple[list, list]:
    last_day = K.HOLDOUT[1]
    g = D3.load_grid(und, last_day)
    LO = D3.LakeOptions(und, last_day)
    exp = LO.expiry_days()
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und))
    D = len(g.days)
    prevH = [np.nanmax(g.h[i]) for i in range(D)]
    prevL = [np.nanmin(g.l[i]) for i in range(D)]
    prevC = [D3._last(g.c[i]) for i in range(D)]
    widths = []
    trades, days = [], []
    first_opt = min(int(p.stem[-4:]) for p in LO.files) if LO.files else 9999
    for i in range(1, D):
        d = g.days[i]
        if d.year < first_opt:
            widths.append(np.nan)
            continue
        lv = levels(prevH[i - 1], prevL[i - 1], prevC[i - 1])
        hist = [w for w in widths[-NARROW_LOOK:] if np.isfinite(w)]
        narrow = bool(len(hist) >= 20 and lv["width"] <= np.quantile(hist, 1 / 3))
        wide = bool(len(hist) >= 20 and lv["width"] >= np.quantile(hist, 2 / 3))
        widths.append(lv["width"])
        h, l, o = g.h[i], g.l[i], g.o[i]
        op = D3._first(o)
        if not np.isfinite(op):
            continue
        side = 1 if op > lv["top"] else (-1 if op < lv["bottom"] else 0)
        with np.errstate(all="ignore"):
            H, L = np.nanmax(h), np.nanmin(l)
            avg20 = np.nanmean([prevH[k] - prevL[k] for k in range(max(i - 20, 0), i)])
        rngd = H - L
        days.append(dict(und=und, day=d, side=side, narrow=narrow, wide=wide, width=lv["width"],
                         range_rel=rngd / avg20 if avg20 else np.nan,
                         trend=bool(rngd > 0 and (op - L <= 0.2 * rngd or H - op <= 0.2 * rngd)),
                         up_more=bool(H - op > op - L)))
        if side == 0:
            continue
        is_exp = d in exp
        last = K.K3.EXIT_LAST_EXPIRY if is_exp else K.K3.EXIT_LAST
        opts = None
        for vname, vc in VARIANTS.items():
            if vc["narrow"] and not narrow:
                continue
            j = find_setup(h, l, lv["top"], lv["bottom"], side, vc["tf"])
            if j is None or j + 1 > last:
                continue
            r0 = j + 1
            ref = o[r0]
            if not np.isfinite(ref):
                continue
            if side > 0:
                stop = lv["bottom"] * (1 - BUF)
                tgt = next((x for x in (lv["R1"], lv["R2"]) if x > ref), None)
            else:
                stop = lv["top"] * (1 + BUF)
                tgt = next((x for x in (lv["S1"], lv["S2"]) if x < ref), None)
            if tgt is None:
                continue
            ex = exit_path(h, l, o, r0, side, stop, tgt, last)
            if ex is None:
                continue
            xr, why, xpx = ex
            if opts is None:
                opts = LO.day(d, g.c[i])
            if opts is None:
                break
            ref_c = D3._last(g.c[i][:r0])
            if not np.isfinite(ref_c):
                continue
            k_atm = D3.atm(ref_c, step)
            a = opts.contracts.get(("C" if side > 0 else "P", k_atm))
            tr = option_trade(a, r0, xr, why, lot, d, und)
            if tr is None:
                continue
            # twins: same day and side, random entry minute, the same stop / target distances
            ds, dt = abs(ref - stop), abs(tgt - ref)
            tw = []
            for _ in range(TWINS):
                r1 = int(rng.integers(FIRST_DEC + 1, min(LAST_DEC, last) + 1))
                ref1 = o[r1]
                if not np.isfinite(ref1):
                    tw.append(np.nan)
                    continue
                s1, t1 = (ref1 - ds, ref1 + dt) if side > 0 else (ref1 + ds, ref1 - dt)
                e1 = exit_path(h, l, o, r1, side, s1, t1, last)
                c1 = D3._last(g.c[i][:r1])
                if not (e1 and np.isfinite(c1)):                    # no earlier print to pick a strike from
                    tw.append(np.nan)
                    continue
                a1 = opts.contracts.get(("C" if side > 0 else "P", D3.atm(c1, step)))
                t1r = option_trade(a1, r1, e1[0], e1[1], lot, d, und)
                tw.append(np.nan if t1r is None else t1r[0])
            trades.append(dict(variant=vname, und=und, day=d, side=side, narrow=narrow, entry=r0, exit=xr, why=why,
                               net=tr[0], netx2=tr[1], pts=(xpx - ref) * side, stop_pts=ds, tgt_pts=dt,
                               twins=tw))
    log(f"R127 {und}: {len(days)} days, {len(trades)} trades", flush=True)
    return trades, days


def _one(und):
    return run_index(und)


def stats(T: pd.DataFrame, sessions: int) -> dict:
    if not len(T):
        return dict(n=0)
    net = T.net.values
    w = net > 0
    eq = np.cumsum(net)
    dd = float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0))
    mo = T.assign(mo=[f"{d.year}-{d.month:02d}" for d in T.day]).groupby("mo").net.sum()
    yr = T.assign(y=[d.year for d in T.day]).groupby("y").net.agg(["count", "sum", "mean"])
    # R128 T5 fix: a twin without a trade is resampled from that trade's valid twins (it used to count as Rs 0, which
    # pulled twin totals toward zero); a trade with no valid twin is left out of the twin totals
    rng = np.random.default_rng(SEED)
    tw = np.array(T.twins.tolist(), dtype=float)                         # trades x twins
    for k in range(tw.shape[0]):
        bad = ~np.isfinite(tw[k])
        if bad.all():
            tw[k] = np.nan
        elif bad.any():
            tw[k, bad] = rng.choice(tw[k, ~bad], bad.sum())
    keep = np.isfinite(tw).all(1)
    tot = tw[keep].sum(0)
    rule_total = float(net[keep].sum())
    return dict(n=int(len(T)), win=float(w.mean()), avg_net=float(net.mean()), avg_net_x2=float(T.netx2.mean()),
                avg_win=float(net[w].mean()) if w.any() else 0.0, avg_loss=float(net[~w].mean()) if (~w).any() else 0.0,
                total=float(net.sum()), per_session=float(net.sum() / sessions), avg_pts=float(T.pts.mean()),
                exits={k: int(v) for k, v in T.why.value_counts().items()},
                target_rate=float((T.why == "target").mean()), max_dd=dd, worst_month=float(mo.min()),
                worst_month_name=str(mo.idxmin()), months_green=int((mo > 0).sum()), months=int(mo.size),
                by_year={int(y): dict(n=int(r["count"]), total=float(r["sum"]), avg=float(r["mean"]))
                         for y, r in yr.iterrows()},
                by_und={u: dict(n=int((T.und == u).sum()), total=float(T.net[T.und == u].sum()),
                                win=float((T.net[T.und == u] > 0).mean())) for u in sorted(T.und.unique())},
                twin_mean=float(tot.mean()), p_twins=float((1 + (tot >= rule_total).sum()) / (1 + len(tot))),
                twin_trades=int(keep.sum()),
                buys=float((T.side > 0).mean()))


def claims(Dd: pd.DataFrame) -> dict:
    out = {}
    for u in ["ALL"] + sorted(Dd.und.unique()):
        X = Dd if u == "ALL" else Dd[Dd.und == u]
        n, w = X[X.narrow], X[X.wide]
        out[u] = dict(days=int(len(X)),
                      narrow=dict(n=int(len(n)), range_rel=float(n.range_rel.mean()), trend=float(n.trend.mean())),
                      wide=dict(n=int(len(w)), range_rel=float(w.range_rel.mean()), trend=float(w.trend.mean())),
                      all_trend=float(X.trend.mean()),
                      open_above=dict(n=int((X.side > 0).sum()), up_more=float(X.up_more[X.side > 0].mean())),
                      open_below=dict(n=int((X.side < 0).sum()), up_more=float(X.up_more[X.side < 0].mean())),
                      open_inside=dict(n=int((X.side == 0).sum()), up_more=float(X.up_more[X.side == 0].mean())),
                      all_up_more=float(X.up_more.mean()))
    return out


def run():
    from multiprocessing import Pool
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(_one, list(K.UNDS))
    T = pd.DataFrame([t for r in res for t in r[0]])
    Dd = pd.DataFrame([d for r in res for d in r[1]])
    sessions = int(Dd.groupby("day").ngroups)
    out = dict(sessions=sessions, index_days=int(len(Dd)), claims=claims(Dd), variants={})
    latest = pd.Timestamp("2026-04-01").date()
    for v in VARIANTS:
        Tv = T[T.variant == v].sort_values(["day", "entry"]).reset_index(drop=True)
        s = stats(Tv, sessions)
        s["latest_half_year"] = stats(Tv[Tv.day >= latest], int(Dd[Dd.day >= latest].groupby("day").ngroups))
        if s["n"]:
            s["latest_half_year"].pop("by_und", None)
        out["variants"][v] = s
    for v, q in zip(VARIANTS, R3.bh([out["variants"][v].get("p_twins", 1.0) for v in VARIANTS])):
        s = out["variants"][v]
        s["q"] = q
        yrs = [s["by_year"].get(y, {}).get("total", 0.0) > 0 for y in range(2022, 2027)] if s.get("n") else []
        s["gates"] = dict(n=s.get("n", 0) >= 300, net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                          years=sum(yrs) >= 3, q=q <= 0.10,
                          account=s.get("max_dd", -1e9) > -40000 and s.get("worst_month", -1e9) > -13000)
        s["passes"] = all(s["gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    out_dir = Path(__import__("os").environ.get("R127_OUT", K.K3.CACHE_ROOT / "r127")).expanduser()
    out_dir.mkdir(parents=True, exist_ok=True)
    T.drop(columns=["twins"]).to_parquet(out_dir / "trades.parquet")
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), s.get("passes")) for v, s in out["variants"].items()})


if __name__ == "__main__":
    run()
