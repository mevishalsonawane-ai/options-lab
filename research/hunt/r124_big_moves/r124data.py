"""R124 parts B and C data (PREREG sections 5-6, amendment 1): R123's decision rows and point-in-time inputs, plus the
level distances and the last confirmed big move, the wick-to-wick move labels and the index-exit option trades.

Everything that measures a move reads candle highs and lows. The one non-wick price is a trade's own reference, the
index at the entry (the open of bar t+1, when the option is bought); its target and stop touches are read on wicks.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

import r124cfg as K
import r123cfg as K3
import r123data as D3
import r124zig as Z

LEVEL_COLS = ("lv_open", "lv_pdh", "lv_pdl", "lv_pc", "lv_or", "lv_round", "lv_since_move", "lv_last_dir", "thr_pct")


def span_dist(lo: float, hi: float, level: float, thr: float) -> float:
    """Distance from a bar's wick span [lo, hi] to a level, in move sizes (0 when the bar touched it)."""
    if not (np.isfinite(lo) and np.isfinite(hi) and np.isfinite(level)) or not thr > 0:
        return np.nan
    if lo <= level <= hi:
        return 0.0
    return min(abs(level - lo), abs(level - hi)) / thr


def level_features(I, i: int, t: int, thr: float, conf: dict) -> dict:
    """Point-in-time: bar t's wicks against the day's open, the previous day's high / low / close, the opening range
    (after 09:29) and the nearest round 100; minutes since the last CONFIRMED big move (confirm <= t) and its side."""
    g = I.g
    lo, hi = g.l[i, t], g.h[i, t]
    with np.errstate(all="ignore"):
        ph = np.nanmax(g.h[i - 1]) if i > 0 else np.nan
        pl = np.nanmin(g.l[i - 1]) if i > 0 else np.nan
        pc = I.d_close[i - 1] if i > 0 else np.nan
        f = dict(lv_open=span_dist(lo, hi, I.d_open[i], thr), lv_pdh=span_dist(lo, hi, ph, thr),
                 lv_pdl=span_dist(lo, hi, pl, thr), lv_pc=span_dist(lo, hi, pc, thr))
        if t >= 14:
            orh, orl = np.nanmax(g.h[i, :15]), np.nanmin(g.l[i, :15])
            f["lv_or"] = min(span_dist(lo, hi, orh, thr), span_dist(lo, hi, orl, thr))
        else:
            f["lv_or"] = np.nan
        mid = (lo + hi) / 2 if np.isfinite(lo) and np.isfinite(hi) else np.nan
        f["lv_round"] = span_dist(lo, hi, round(mid / 100.0) * 100.0, thr) if np.isfinite(mid) else np.nan
    ks = [c for c in conf if c <= t]
    if ks:
        end, dirn = conf[max(ks)]
        f["lv_since_move"], f["lv_last_dir"] = float(t - end), float(dirn)
    else:
        f["lv_since_move"], f["lv_last_dir"] = np.nan, 0.0
    f["thr_pct"] = thr / I.d_open[i] * 100 if np.isfinite(I.d_open[i]) else np.nan
    return f


def move_label(h: np.ndarray, l: np.ndarray, t: int, end: int, thr: float):
    """Wick-to-wick inside bars t+1..end: UP when a bar's high is >= thr above the lowest low so far (same bar
    included), DOWN when a bar's low is >= thr below the highest high so far; the first bar that completes one wins;
    both in one bar: UNCLEAR. Returns (class, bar) or (NEITHER, None)."""
    mn, mx = np.inf, -np.inf
    for j in range(t + 1, end + 1):
        if not (np.isfinite(h[j]) and np.isfinite(l[j])):
            continue
        mn, mx = min(mn, l[j]), max(mx, h[j])
        up, dn = h[j] - mn >= thr, mx - l[j] >= thr
        if up and dn:
            return K.UNCLEAR, j
        if up:
            return K.UP, j
        if dn:
            return K.DOWN, j
    return K.NEITHER, None


def _last_traded_close(a, r0, r):
    fin = np.flatnonzero(np.isfinite(a[r0:r + 1, D3.C_]))
    return (r0 + fin[-1]) if len(fin) else None


def index_exit_trade(opts: dict, sides: tuple, ih, il, io, t: int, end: int, thr: float, lot: int, d, und: str,
                     straddle: bool = False) -> dict | None:
    """Buy 1 lot of each option in `sides` ((side, array) pairs) at the open of bar t+1. The index reference is the
    index open of bar t+1. Exit at the option's close of the first bar where the index WICK touches ref +- thr
    (calls: high -> target, low -> stop; puts mirrored; a bar touching both: the stop; straddle: either touch), else
    at the close of bar `end`. An exit bar without an option print uses the last traded close before it."""
    r0 = t + 1
    if r0 > end or not np.isfinite(io[r0]):
        return None
    ref = io[r0]
    legs = []
    for s, a in sides:
        if a is None or not np.isfinite(a[r0, D3.O_]) or a[r0, D3.O_] <= 0:
            return None
        v0 = float(np.nansum(a[max(r0 - 5, 0):r0, D3.V_])) / lot
        legs.append((s, a, float(D3.FILLS.buy(np.array([a[r0, D3.O_]]), np.array([v0]))[0]), float(a[r0, D3.O_])))
    xr, why = end, "time"
    for j in range(r0, end + 1):
        up = np.isfinite(ih[j]) and ih[j] >= ref + thr
        dn = np.isfinite(il[j]) and il[j] <= ref - thr
        if up or dn:
            xr = j
            if straddle:
                why = "touch"
            else:
                s0 = sides[0][0]
                good, bad = (up, dn) if s0 == "C" else (dn, up)
                why = "stop" if bad else "target"
            break
    net = slip = 0.0
    for s, a, buy, p0 in legs:
        rr = _last_traded_close(a, r0, xr)
        if rr is None:
            return None
        raw = float(a[rr, D3.C_])
        v = float(np.nansum(a[max(rr - 5, 0):rr, D3.V_])) / lot
        sell = float(D3.FILLS.sell(np.array([raw]), np.array([v]), stop=(why == "stop"))[0])
        net += (sell - buy) * lot - D3.charge(True, buy, lot, d, und) - D3.charge(False, sell, lot, d, und)
        slip += (buy - p0 + raw - sell) * lot
    return dict(net=net, net_x2=net - slip, exit_row=xr, why=why)


def build(part: str, unds=K.UNDS, log=print):
    if part == "holdout" and not Z.holdout_open():
        raise Z.HoldoutError("the R124 holdout is built only inside r124run.py's one holdout read")
    last_day = K.DESIGN[1] if part == "design" else K.HOLDOUT[1]
    grids = {u: D3.load_grid(u, last_day) for u in K.UNDS}
    vix = D3.load_grid("INDIAVIX", last_day)
    budget = D3.budget_days()
    nifty = D3.IndexData("NIFTY", grids, vix)
    rows, seqs = [], {}
    for und in unds:
        I = nifty if und == "NIFTY" else D3.IndexData(und, grids, vix)
        LO = D3.LakeOptions(und, last_day)
        exp = LO.expiry_days()
        exp_sorted = sorted(exp)
        seqs[und] = I.seq
        lot, step = K.LOT[und], K.STEP[und]
        n0 = len(rows)
        for i, d in enumerate(I.days):
            if K.part(d) != part:
                continue
            ni = nifty.dpos.get(d)
            thr, rev = Z.thresholds(I.d_open[i], nifty.d_open[ni] if ni is not None else np.nan)
            if not np.isfinite(thr):
                continue
            opts = LO.day(d, I.g.c[i])
            if opts is None:
                continue
            legs = Z.zigzag(I.g.h[i], I.g.l[i], rev)
            conf = {g["confirm"]: (g["end"], g["dir"]) for g in legs if g["confirm"] is not None and g["size"] >= thr}
            nxt = [e for e in exp_sorted if e >= d]
            to_exp = sum(1 for x in I.days[i:] if x <= nxt[0]) - 1 if nxt else None
            is_exp = d in exp
            ctx = dict(to_expiry=to_exp, is_expiry=is_exp, is_budget=d in budget)
            last = K3.EXIT_LAST_EXPIRY if is_exp else K3.EXIT_LAST
            ih, il, io = I.g.h[i], I.g.l[i], I.g.o[i]
            for t in K3.DECISION_ROWS:
                if not np.isfinite(I.g.c[i, t]):
                    continue
                p = I.gpos(i, t)
                rec = dict(und=und, day=d, row=t, gpos=p, close=float(I.cf[p]), thr=thr)
                rec.update(D3.tab_features(I, i, t, ctx, opts.chain))
                rec.update(level_features(I, i, t, thr, conf))
                rl = D3.index_labels(I, i, t)
                rec["y_range_30"], rec["y_range_60"] = rl["y_range_30"], rl["y_range_60"]
                rec["y_dir_30"] = rec["y_dir_60"] = np.nan          # direction lives in the wick move labels
                k = D3.atm(I.cf[p], step)
                ca, pa = opts.contracts.get(("C", k)), opts.contracts.get(("P", k))
                for hz in K.HS:
                    end = min(t + hz, last)
                    ok = t + hz <= last
                    c, _ = move_label(ih, il, t, end, thr) if ok else (K.UNCLEAR, None)
                    rec[f"mv_{hz}"] = c if ok else K.UNCLEAR
                    for name, sides, strad in (("C", (("C", ca),), False), ("P", (("P", pa),), False),
                                               ("S", (("C", ca), ("P", pa)), True)):
                        tr = index_exit_trade(opts, sides, ih, il, io, t, end, thr, lot, d, und, strad) if ok else None
                        rec[f"tr{hz}_{name}_net"] = np.nan if tr is None else tr["net"]
                        rec[f"tr{hz}_{name}_netx2"] = np.nan if tr is None else tr["net_x2"]
                        rec[f"tr{hz}_{name}_exit"] = -1 if tr is None else tr["exit_row"]
                        rec[f"tr{hz}_{name}_why"] = "" if tr is None else tr["why"]
                rows.append(rec)
        log(f"{und} {part}: {len(rows) - n0} rows", flush=True)
    return pd.DataFrame(rows), seqs


def _build_one(args):
    import pickle
    part, und = args
    p = K.OUT / f"part_{part}_{und}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    res = build(part, unds=(und,))
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(pickle.dumps(res))
    return res


def save(part: str, log=print):
    from multiprocessing import Pool
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(_build_one, [(part, u) for u in K.UNDS])
    F = pd.concat([r[0] for r in res], ignore_index=True)
    seqs = {u: r[1][u] for u, r in zip(K.UNDS, res)}
    K.OUT.mkdir(parents=True, exist_ok=True)
    F.to_parquet(K.OUT / f"rows_{part}.parquet")
    np.savez(K.OUT / f"seq_{part}.npz", **seqs)
    log(f"{part}: {len(F)} rows", flush=True)
    return F


def load(part: str):
    if part == "holdout" and not Z.holdout_open() and not (K.RESULTS / "holdout.json").exists():
        raise Z.HoldoutError("the R124 holdout is read only inside r124run.py's one holdout read")
    return pd.read_parquet(K.OUT / f"rows_{part}.parquet"), dict(np.load(K.OUT / f"seq_{part}.npz"))
