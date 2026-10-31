"""R138 Amendment A (PREREG.md): Boss, 31 Oct, "Remove that 5point and try again, still loss then try enhancing it".

    python research/hunt/r138_triple_top_fake/r138b.py design    # -> results/amendA_design.json
    python research/hunt/r138_triple_top_fake/r138b.py holdout   # one read of the ALREADY SEEN holdout months

Stage 1: the touches' band removed (U0) or scaled to ATR (U1 0.25, U2 0.5), trade T1. Stage 2: E1-E7 on the base
(the Stage 1 version with the best design net per trade). Signal source: the index spot (15-minute wicks).
"""
from __future__ import annotations

import json
import os
import sys
import warnings
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r132_ict_exits"))
import r138 as M8  # noqa: E402
import r132 as Q  # noqa: E402
import r132nn as NN  # noqa: E402

R, K, D3 = M8.R, M8.K, M8.D3
N = R.N
RESULTS, OUT = M8.RESULTS, M8.OUT
TWINS = M8.TWINS
SEED = 1381
BASE_CFG = dict(level="max", poke="tick", fail="f1", span=False)
STAGE1 = {"U0": dict(BASE_CFG, band="none"), "U1": dict(BASE_CFG, band="atr", k=0.25),
          "U2": dict(BASE_CFG, band="atr", k=0.5)}
STAGE2 = ("E1", "E2", "E3", "E4", "E5", "E6", "E7")
LABELS = {"U0": "no band (any two highs)", "U1": "band 0.25 x ATR", "U2": "band 0.5 x ATR",
          "E1": "base + events by 14:00", "E2": "base + neckline >= 1.5x the stop", "E3": "base + below the day's open",
          "E4": "base entry, 60% lock after +Rs 1,000, no target", "E5": "base + R123 N1 network filter",
          "E6": "flip: buy the call on a close back above the fake high, 2R",
          "E7": "base + E1 + E2 + E3"}
EARLY_R0 = K.row(14, 0)
ROOM = 1.5
GATES = M8.GATES


# ------------------------------------------------------------------------------------------------ candidates
def m_cands(sides, und, cfg) -> list:
    """The M (mirrored side) events as trade candidates: entry the next minute, stop above the fake high."""
    S = sides[-1]
    ch = S.chart(M8.TF)
    out = []
    for ev in M8.detect(ch, und, cfg):
        c = M8.TCand(int(ch.day[ev.e]), int(ch.B.end[ev.e]) + 1, R.stop_below(ev.fake), ev.neck)
        c.e_bar = ev.e
        c.fake = ev.fake
        out.append((-1, c))
    return out


def keep_filter(sides, c, which: set) -> bool:
    """E1 / E2 / E3 on one M candidate (side -1 coordinates; all known at the entry minute's open)."""
    S = sides[-1]
    if c.r0 >= N:
        return False
    ref = S.mo[c.d][c.r0]
    if not np.isfinite(ref):
        return False
    if "E1" in which and c.r0 > EARLY_R0:
        return False
    if "E2" in which and not (c.neck - ref >= ROOM * (ref - c.stop)):
        return False
    if "E3" in which:
        fo = S.mo[c.d][np.isfinite(S.mo[c.d])]
        if not len(fo) or not ref > fo[0]:           # mirrored: above the mirrored open = real price below the open
            return False
    return True


def flip_cands(sides, cands) -> list:
    """E6: after each M event, the first 15-minute close above the fake high (real side +1, same session) at least
    one candle later; buy the call at the next minute, stop 0.02% below that candle's low."""
    ch = sides[1].chart(M8.TF)
    out = []
    for _, c in cands:
        fake_hi = -c.fake
        t = c.e_bar + 1
        while t < ch.T and ch.day[t] == ch.day[c.e_bar]:
            if ch.c[t] > fake_hi:
                out.append((1, R.Cand(int(ch.day[t]), int(ch.B.end[t]) + 1, R.stop_below(float(ch.l[t])), M8.TF)))
                break
            t += 1
    return out


# ------------------------------------------------------------------------------------------------ trades
def price_rows(und, g, sides, T, src_for, lot, step, rng) -> list:
    """R137's pricing: the ATM option of each index trade, with 100 after-signal twins (same stop / target)."""
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
        tw = R.twin_nets(r1s, lastc, S.mh[d], S.ml[d], S.mo[d], opts.contracts, cp, step, t["stop_pts"],
                         t["tgt_pts"], lot, g.days[d], und)
        rows.append(dict(t, und=und, net=tr[0], netx2=tr[1], twins=tw))
    return rows


def lock_rows(und, g, sides, cands, blocked, keep, src_for, lot, step, rng) -> list:
    """E4: R132's E3 exit (index stop + 60% lock after +Rs 1,000, out 15:20), one trade at a time; twins with the
    same exit from random later entries, same stop distance."""
    ex = Q.EXITS["E3"]
    rows, busy = [], -1
    for sd, c in sorted(cands, key=lambda x: (x[1].d, x[1].r0)):
        if c.d * N + c.r0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
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
        res = Q.sim_exit(a, *R._contract_aux(a), S.mh[d], S.ml[d], c.r0, ref, c.stop, ex, lot, g.days[d], und)
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
        rows.append(dict(d=d, day=g.days[d], side=sd, entry=c.r0, exit=xr, why=why, und=und, net=net, netx2=net2,
                         pts=float(mid - ref) if np.isfinite(mid) else 0.0,
                         R=float((mid - ref) / stop_pts) if np.isfinite(mid) else 0.0, twins=tw.tolist()))
    return rows


def run_index(args) -> dict:
    und, part, base = args
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    keep_idx = [i for i, d in enumerate(g.days) if keep(d)]
    lot, step = K.LOT[und], K.STEP[und]
    off = K.UNDS.index(und) + (100 if part != "design" else 0)
    sides = R.sides_of(g)
    cache = {}

    def src_for(day, closes):
        if day not in cache:
            cache.clear()
            cache[day] = (Mo if day in wexp else W).day(day, closes)
        return cache[day]

    # descriptive (section 2 measures and C1 controls) for U0-U2, both sides
    drng = np.random.default_rng(SEED + off)
    desc_rows, desc_ctrl = [], []
    saved = M8.CONFIGS
    try:
        M8.CONFIGS = STAGE1
        dd = M8.describe_index(und, g, sides, keep_idx, drng)
    finally:
        M8.CONFIGS = saved
    for r in dd["rows"]:
        desc_rows.append(r)
    for r in dd["ctrl"]:
        desc_ctrl.append(r)

    rng = np.random.default_rng(SEED + 1000 + off)
    trades = {}
    cands = {u: m_cands(sides, und, cfg) for u, cfg in STAGE1.items()}

    def plain(cs):
        T = M8.sequence(sides, cs, "neck", blocked, g.days, keep)
        return price_rows(und, g, sides, T, src_for, lot, step, rng)

    for u in STAGE1:
        trades[u] = plain(cands[u])
    if base is not None:
        bc = cands[base]
        trades["E1"] = plain([x for x in bc if keep_filter(sides, x[1], {"E1"})])
        trades["E2"] = plain([x for x in bc if keep_filter(sides, x[1], {"E2"})])
        trades["E3"] = plain([x for x in bc if keep_filter(sides, x[1], {"E3"})])
        trades["E7"] = plain([x for x in bc if keep_filter(sides, x[1], {"E1", "E2", "E3"})])
        trades["E4"] = lock_rows(und, g, sides, bc, blocked, keep, src_for, lot, step, rng)
        T6, _ = R.sequence(sides, flip_cands(sides, bc), dict(target="2R"), blocked, g.days, keep)
        trades["E6"] = price_rows(und, g, sides, T6, src_for, lot, step, rng)
    print(f"R138A {part} {und}: " + ", ".join(f"{k} {len(x)}" for k, x in trades.items()), flush=True)
    return dict(rows=desc_rows, ctrl=desc_ctrl, trades=trades)


# ------------------------------------------------------------------------------------------------ statistics
def describe(D: pd.DataFrame, C: pd.DataFrame, rng) -> dict:
    out = dict(configs={}, tests=[])
    for name in STAGE1:
        for sd in (-1, 1):
            E = D[(D.cfg == name) & (D.side == sd)]
            Cc = C[(C.cfg == name) & (C.side == sd)]
            s = M8.summ(E)
            s["control_C1"] = M8.ctrl_summ(Cc)
            if len(E) >= 2:
                for col, tag in (("m60", "a_m60_vs_C1"), ("m_eod", "b_eod_vs_C1"), ("race", "c_neck_vs_C1")):
                    p = M8.paired(E, Cc, col, rng)
                    s[f"p_{tag}"] = p
                    out["tests"].append(dict(cfg=name, side=M8.SIDE_NAME[sd], test=tag, n=int(len(E)), p=p))
            if sd == -1 and len(E):
                s["by_und"] = {u: M8.summ(X) for u, X in E.groupby("und")}
                s["by_year"] = {int(y): M8.summ(X) for y, X in E.groupby([d.year for d in E.day])}
            out["configs"][f"{M8.SIDE_NAME[sd]}:{name}"] = s
    if out["tests"]:
        for t, q in zip(out["tests"], R.R3.bh([t["p"] for t in out["tests"]])):
            t["q"] = float(q)
    return out


def network(T: pd.DataFrame, part: str, rng) -> dict:
    """E5: the base's trades kept by R123's N1 EV filter; p from random subsets of the base by fold."""
    P = pd.read_parquet(NN.PREDS / ("wf_rows_preds_N1.parquet" if part == "design" else "holdout_rows_preds_N1.parquet"))
    T = T.copy()
    if part == "design":
        T = T[[K.fold_of(d) is not None for d in T.day]]
    Mx = NN.attach(T, P)
    kp = NN.keep_mask(Mx, "NEV")
    s = M8.stats(Mx[kp], part == "design") if kp.any() else dict(n=0)
    strata = np.array([K.fold_of(d) or "H" for d in Mx.day])
    s["p"] = NN.subset_p(Mx.net.values, kp, strata, rng) if kp.any() else 1.0
    s["unfiltered_n"] = int(len(Mx))
    s["unfiltered_avg_net"] = float(Mx.net.mean()) if len(Mx) else None
    return s


def lock(status: str):
    p = OUT / "HOLDOUT_READ_A.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "amendA_holdout_seen.json").exists() or p.exists():
            raise R.HoldoutError("refused: the Amendment A holdout read was already made")
        if not (RESULTS / "amendA_design.json").exists():
            raise R.HoldoutError("refused: commit the Amendment A design results first")
        rec = dict(status="started", started_at=now)
    else:
        rec = dict(json.loads(p.read_text()), status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def run_stage(part: str, base):
    from multiprocessing import Pool
    with Pool(int(os.environ.get("R138_PROCS", "4"))) as pool:
        return pool.map(run_index, [(u, part, base) for u in K.UNDS])


def frame(res, key) -> pd.DataFrame:
    return pd.DataFrame([r for x in res for r in x["trades"].get(key, [])])


def run(part: str):
    if part == "holdout":
        lock("start")
        base = json.loads((RESULTS / "amendA_design.json").read_text())["base"]
        res = run_stage(part, base)
    else:
        res = run_stage(part, None)                  # Stage 1 first: the base is chosen from it
        s1 = {}
        for u in STAGE1:
            T = frame(res, u)
            s1[u] = float(T.net.mean()) if len(T) else -np.inf
        base = max(s1, key=s1.get)
        res = run_stage(part, base)
    D = pd.DataFrame([r for x in res for r in x["rows"]])
    C = pd.DataFrame([r for x in res for r in x["ctrl"]])
    rng = np.random.default_rng(SEED + 7 + (100 if part != "design" else 0))
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        out = dict(part=part, label=("design" if part == "design" else
                                     "holdout months ALREADY SEEN for this pattern family (R138's read); one read"),
                   base=base, describe=describe(D, C, rng), variants={})
    for v in (*STAGE1, *STAGE2):
        if v == "E5":
            T = frame(res, base)
            s = network(T, part, rng) if len(T) else dict(n=0, p=1.0)
        else:
            T = frame(res, v)
            if not len(T):
                out["variants"][v] = dict(n=0, p=1.0, label=LABELS[v])
                continue
            s = M8.stats(T, part == "design")
            s["p"], s["twin_mean_total"], _ = R.twin_p(T, SEED)
            OUT.mkdir(parents=True, exist_ok=True)
            T.drop(columns=["twins"]).to_parquet(OUT / f"trades_A_{v}_{part}.parquet")
        s["label"] = LABELS[v]
        out["variants"][v] = s
    if part == "design":
        r138 = json.loads((RESULTS / "design.json").read_text())["variants"]
        names = [*r138, *out["variants"]]
        ps = [r138[v].get("p", 1.0) for v in r138] + [out["variants"][v].get("p", 1.0) for v in out["variants"]]
        qs = dict(zip(names, R.R3.bh(ps)))
        out["bh_over"] = names
        for v, s in out["variants"].items():
            s["q"] = float(qs[v])
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["gates"] = dict(n=s.get("n", 0) >= GATES["n"],
                              net=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                              folds=sum(folds) >= GATES["folds"], q=s["q"] <= GATES["q"],
                              account=s.get("max_dd", -1e9) > GATES["dd"] and s.get("worst_month", -1e9) > GATES["month"])
            s["passes_design"] = all(s["gates"].values())
        name = "amendA_design.json"
    else:
        for s in out["variants"].values():
            s["passes_seen_holdout"] = s.get("n", 0) >= GATES["holdout_n"] and s.get("avg_net", -1) > 0
        name = "amendA_holdout_seen.json"
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / name).write_text(json.dumps(out, indent=1, default=str))
    if part == "holdout":
        lock("done")
    print("base", base)
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s.get("p", 1), 3))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
