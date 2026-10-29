"""R137: continuation after the first 15-minute close beyond a strong swing (PREREG.md), buying the ATM option.

    python research/hunt/r137_strong_break/r137.py design    # -> results/design.json
    python research/hunt/r137_strong_break/r137.py holdout   # the ONE holdout read -> results/holdout.json

Signal source: the index spot. Reuses R131 (bars, pivots, option rule, costs, twins), R132 (the E4 trail exit) and
R132 Amendment A (R123's N1 EV filter).
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r132_ict_exits"))
import r132 as Q  # noqa: E402
import r132nn as NN  # noqa: E402

R, K = Q.R, Q.K
D3 = R.D3
N = R.N
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R137_OUT", K.K3.CACHE_ROOT / "r137")).expanduser()
TICK = 0.05
TWINS = 100
SEED = 137
VARIANTS = {"B1": dict(exit="2R", nn=False), "B2": dict(exit="E4", nn=False),
            "B1N": dict(exit="2R", nn=True), "B2N": dict(exit="E4", nn=True)}
GATES = dict(n=300, folds=3, q=0.10, dd=-40000.0, month=-13000.0, holdout_n=50)


# ------------------------------------------------------------------------------------------------ the entry
def det_break(S, strong: bool = True) -> list:
    """Bullish candidates on this side: the first 15-minute close above a swing high whose strength (strong = it swept
    an earlier untaken swing high AND price then closed below the last swing low before it, before any high went above
    it, all before the break) equals `strong`. Stop 0.02% below the breakout bar's low."""
    ch = S.chart(15)
    k, sh, sl = ch.k, ch.sh, ch.sl
    if not ch.T:
        return []
    first_bar = np.searchsorted(ch.day, np.arange(int(ch.day[-1]) + 2))
    out = []
    for i in sh:
        i = int(i)
        last = min(ch.T - 1, int(first_bar[min(int(ch.day[i]) + R.SESSIONS_BACK + 1, len(first_bar) - 1)]) - 1)
        t = next((x for x in range(i + k + 1, last + 1) if ch.c[x] > ch.h[i]), None)
        if t is None:
            continue
        a = int(np.searchsorted(sh, first_bar[max(int(ch.day[i]) - R.SESSIONS_BACK, 0)]))
        b = int(np.searchsorted(sh, i - k))
        swept = any(ch.h[i] >= ch.h[j] + TICK and ch.h[j + 1:i].max(initial=-np.inf) < ch.h[j] + TICK
                    for j in sh[a:b])
        broke = False
        lows = sl[sl < i]
        if swept and len(lows):
            ref = ch.l[int(lows[-1])]
            for x in range(i + 1, t):
                if ch.h[x] > ch.h[i]:
                    break
                if ch.c[x] < ref:
                    broke = True
                    break
        if (swept and broke) == strong:
            out.append(R.Cand(int(ch.day[t]), int(ch.B.end[t]) + 1, R.stop_below(float(ch.l[t])), 15))
    return out


def candidates(sides: dict, strong: bool = True) -> list:
    out = []
    for sd in (1, -1):
        out += [(sd, c) for c in det_break(sides[sd], strong)]
    out.sort(key=lambda x: (x[1].d, x[1].r0, x[0]))
    return out


# ------------------------------------------------------------------------------------------------ trades
def trades_2r(und, g, sides, cands, blocked, keep, src_for, lot, step, rng) -> list:
    T, _ = R.sequence(sides, cands, dict(target="2R"), blocked, g.days, keep)
    rows = []
    for t in T:
        d = t["d"]
        opts = src_for(g.days[d], g.c[d])
        lastc = R.last_before(g.c[d])
        if opts is None or not np.isfinite(lastc[t["entry"]]):
            continue
        cp = "C" if t["side"] > 0 else "P"
        tr = R.option_trade(opts.contracts.get((cp, D3.atm(lastc[t["entry"]], step))), t["entry"], t["exit"], t["why"],
                            lot, g.days[d], und)
        if tr is None:
            continue
        S = sides[t["side"]]
        r1s = rng.integers(t["entry"], R.LAST_ENTRY + 1, size=TWINS)
        tw = R.twin_nets(r1s, lastc, S.mh[d], S.ml[d], S.mo[d], opts.contracts, cp, step, t["stop_pts"], t["tgt_pts"],
                         lot, g.days[d], und)
        rows.append(dict(und=und, d=d, day=g.days[d], side=t["side"], entry=t["entry"], exit=t["exit"], why=t["why"],
                         net=tr[0], netx2=tr[1], pts=t["pts"], R=t["R"], twins=tw))
    return rows


def trades_trail(und, g, sides, cands, blocked, keep, src_for, lot, step, rng) -> list:
    """R132's E4 exit, one trade at a time (a candidate entering while a trade is open is skipped)."""
    ex = Q.EXITS["E4"]
    rows, busy = [], -1
    i = 0
    while i < len(cands):
        sd, c = cands[i]
        j = i
        while j < len(cands) and cands[j][1].d == c.d and cands[j][1].r0 == c.r0:
            j += 1
        same, i = cands[i:j], j
        g0 = c.d * N + c.r0
        if len({s for s, _ in same}) == 2 or g0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
            continue
        S = sides[sd]
        d = c.d
        ref = S.mo[d][c.r0]
        if not np.isfinite(ref) or ref <= c.stop:
            continue
        opts = src_for(g.days[d], g.c[d])
        lastc = R.last_before(g.c[d])
        if opts is None or not np.isfinite(lastc[c.r0]):
            continue
        cp = "C" if sd > 0 else "P"
        a = opts.contracts.get((cp, float(D3.atm(lastc[c.r0], step))))
        if a is None:
            continue
        cv, li = R._contract_aux(a)
        res = Q.sim_exit(a, cv, li, S.mh[d], S.ml[d], c.r0, ref, c.stop, ex, lot, g.days[d], und)
        if res is None:
            continue
        xr, why, net, net2 = res
        busy = d * N + xr
        if not keep(g.days[d]):
            continue
        stop_pts = ref - c.stop
        tw = np.full(TWINS, np.nan)
        for kk, r1 in enumerate(rng.integers(c.r0, R.LAST_ENTRY + 1, size=TWINS)):
            ref1 = S.mo[d][r1]
            if not (np.isfinite(ref1) and np.isfinite(lastc[r1])):
                continue
            a1 = opts.contracts.get((cp, float(D3.atm(lastc[r1], step))))
            if a1 is None:
                continue
            t1 = Q.sim_exit(a1, *R._contract_aux(a1), S.mh[d], S.ml[d], int(r1), ref1, ref1 - stop_pts, ex, lot,
                            g.days[d], und)
            if t1 is not None:
                tw[kk] = t1[2]
        mid = (S.mh[d][xr] + S.ml[d][xr]) / 2
        rows.append(dict(und=und, d=d, day=g.days[d], side=sd, entry=c.r0, exit=xr, why=why, net=net, netx2=net2,
                         pts=float(mid - ref) if np.isfinite(mid) else 0.0,
                         R=float((mid - ref) / stop_pts) if np.isfinite(mid) else 0.0, twins=tw.tolist()))
    return rows


def run_index(args) -> dict:
    und, part = args
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    cache = {}

    def src_for(day, closes):
        if day not in cache:
            cache.clear()
            cache[day] = (Mo if day in wexp else W).day(day, closes)
        return cache[day]

    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    sides = R.sides_of(g)
    strong, weak = candidates(sides, True), candidates(sides, False)
    out = dict(B1=trades_2r(und, g, sides, strong, blocked, keep, src_for, lot, step, rng),
               B2=trades_trail(und, g, sides, strong, blocked, keep, src_for, lot, step, rng),
               W1=trades_2r(und, g, sides, weak, blocked, keep, src_for, lot, step, rng))
    print(f"R137 {part} {und}: " + ", ".join(f"{k} {len(v)}" for k, v in out.items()), flush=True)
    return out


# ------------------------------------------------------------------------------------------------ statistics
def stats(T: pd.DataFrame, by_fold: bool) -> dict:
    if not len(T):
        return dict(n=0)
    T = T.sort_values(["day", "und", "entry"]).reset_index(drop=True)
    s = R.money(T)
    s["by_year"] = {int(y): R.money(X) for y, X in T.groupby([d.year for d in T.day])}
    s["by_und"] = {u: R.money(X) for u, X in T.groupby("und")}
    if by_fold:
        f = [K.fold_of(d) for d in T.day]
        s["by_fold"] = {k: R.money(T[[x == k for x in f]]) for k in K.FOLDS}
    for blk in ("by_year", "by_und", "by_fold"):
        for x in s.get(blk, {}).values():
            for drop in ("exits", "worst_month_name", "months_green", "months", "buys"):
                x.pop(drop, None)
    return s


def nn_filter(T: pd.DataFrame, part: str):
    P = pd.read_parquet(NN.PREDS / ("wf_rows_preds_N1.parquet" if part == "design" else "holdout_rows_preds_N1.parquet"))
    T = T.copy()
    if part == "design":
        T = T[[K.fold_of(d) is not None for d in T.day]]
    M = NN.attach(T, P)
    return M, NN.keep_mask(M, "NEV")


def lock(status: str):
    p = OUT / "HOLDOUT_READ.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout.json").exists() or p.exists():
            raise R.HoldoutError("refused: the R137 holdout was already read")
        rec = dict(status="started", started_at=now)
    else:
        rec = dict(json.loads(p.read_text()), status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def run(part: str):
    from multiprocessing import Pool
    if part == "holdout":
        lock("start")
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    frames = {k: pd.DataFrame([r for x in res for r in x[k]]) for k in ("B1", "B2", "W1")}
    rng = np.random.default_rng(SEED + 7)
    out = dict(part=part, variants={})
    for v, cfg in VARIANTS.items():
        base = frames["B1" if cfg["exit"] == "2R" else "B2"]
        if not len(base):
            out["variants"][v] = dict(n=0, p=1.0)
            continue
        if cfg["nn"]:
            M, kp = nn_filter(base, part)
            s = stats(M[kp], part == "design")
            strata = np.array([K.fold_of(d) or "H" for d in M.day])
            s["p"] = NN.subset_p(M.net.values, kp, strata, rng) if kp.any() else 1.0
            s["unfiltered_n"] = int(len(M))
        else:
            s = stats(base, part == "design")
            s["p"], s["twin_mean_total"], _ = R.twin_p(base, SEED)
        out["variants"][v] = s
    out["control_W1"] = stats(frames["W1"], part == "design") if len(frames["W1"]) else dict(n=0)
    if len(frames["W1"]):
        out["control_W1"]["p"], _, _ = R.twin_p(frames["W1"], SEED)
    if part == "design":
        qs = R.R3.bh([out["variants"][v].get("p", 1.0) for v in VARIANTS])
        for v, q in zip(VARIANTS, qs):
            s = out["variants"][v]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["gates"] = dict(n=s.get("n", 0) >= GATES["n"],
                              net=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                              folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"],
                              account=s.get("max_dd", -1e9) > GATES["dd"] and s.get("worst_month", -1e9) > GATES["month"])
            s["passes_design"] = all(s["gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    for k, f in frames.items():
        if len(f):
            f.drop(columns=["twins"]).to_parquet(OUT / f"trades_{k}_{part}.parquet")
    if part == "holdout":
        lock("done")
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s.get("p", 1), 3))
           for v, s in out["variants"].items()}, "W1", out["control_W1"].get("n"), round(out["control_W1"].get("avg_net", 0) or 0))


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
