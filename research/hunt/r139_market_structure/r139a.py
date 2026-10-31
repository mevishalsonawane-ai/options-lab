"""R139 Amendment A (PREREG_A.md): enhancing S3, the CHoCH reversal, with liquidity, volume, order flow, SMC, context and
exit add-ons. 19 versions, one BH family.

    python research/hunt/r139_market_structure/r139a.py design    # -> results/design_a.json
    python research/hunt/r139_market_structure/r139a.py holdout   # the ONE Amendment A read of the ALREADY SEEN holdout

Signal source: the index spot (S3's 15-minute swings, wicks). Volume and order flow come from the option chain (the
index has no volume): they are CONTEXT filters around the index signal (CLAUDE.md). The option bought is S3's ATM.
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
sys.path.insert(0, str(HERE))
import r139 as B  # noqa: E402

R, K, D3, NN = B.R, B.K, B.D3, B.NN
Q = NN.Q
N = R.N
TICK = B.TICK
RESULTS = B.RESULTS
OUT = B.OUT
SEED = 1391
TWINS = B.TWINS
TF = B.TF

DISPLACE_ATR = 1.0        # L2: the CHoCH bar's body >= 1 x ATR14
OB_WATCH = 12             # L5: the order-block retest within 12 bars, same session
VOL_SURGE = 1.5           # V1: chain volume of the last 15 minutes >= 1.5 x the session's average 15 minutes so far
VIX_DAYS = 20             # C1: VIX above the median of the previous 20 sessions' last VIX
MORNING_LAST = K.row(11, 15)
DAY_LOSSES = 2            # D1: after 2 losing trades on an index in a day, no more that day
STOP_ATR = 1.5            # D2: skip when the stop is more than 1.5 x ATR14 below the CHoCH close

SINGLE = ("L1", "L2", "L3", "L4", "V1", "OF1", "OF2", "C1", "C2", "C3", "D2")
VARIANTS = {
    "A0": dict(kind="filter", f=(), label="S3 as in R139 (the base, for reference; not a new test)"),
    "L1": dict(kind="filter", f=("L1",), label="liquidity sweep: the down-leg took an untaken swing low or the previous day's low"),
    "L2": dict(kind="filter", f=("L2",), label="displacement: the CHoCH bar's body >= 1 x ATR14"),
    "L3": dict(kind="filter", f=("L3",), label="fair value gap left between the low and the CHoCH bar"),
    "L4": dict(kind="filter", f=("L4",), label="discount: the CHoCH close at or below 50% of the down-leg's range"),
    "L5": dict(kind="ob", label="order-block retest entry: wait for price to come back to the last down candle before the turn"),
    "V1": dict(kind="filter", f=("V1",), label="volume surge: option-chain volume of the last 15 min >= 1.5x the session's average"),
    "OF1": dict(kind="filter", f=("OF1",), label="OI order flow: PCR rose over 15 min (put writing) for a call; fell for a put"),
    "OF2": dict(kind="filter", f=("OF2",), label="buying flow: call volume > put volume over 15 min for a call; the reverse for a put"),
    "C1": dict(kind="filter", f=("C1",), label="India VIX above its 20-session median"),
    "C2": dict(kind="filter", f=("C2",), label="the 60-minute trend is not against the trade"),
    "C3": dict(kind="filter", f=("C3",), label="morning only: entries to 11:15"),
    "C4": dict(kind="nn", label="R123's N1 network expected value > 0 (2023 on)"),
    "D1": dict(kind="daystop", label="day stop: after 2 losing trades on an index, no more that day"),
    "D2": dict(kind="filter", f=("D2",), label="stop cap: skip when the stop is more than 1.5 x ATR14 away"),
    "X1": dict(kind="exit", ex="E3", label="exit: index stop + 60% premium lock after +Rs 1,000, no fixed target"),
    "X2": dict(kind="exit", ex="E4", label="exit: breakeven at 1R, then a 1R trail, no fixed target"),
    "K1": dict(kind="filter", f=("L1", "L2"), label="SMC stack: sweep + displacement"),
    "K2": dict(kind="filter", f=("V1", "OF1", "OF2"), label="flow stack: volume surge + OI flow + buying flow"),
    "K3": dict(kind="filter", f=None, label="every single filter that beat S3 per trade in design (>= 300 trades), together"),
}
TESTED = [v for v in VARIANTS if v != "A0"]
GATES = B.GATES


# ------------------------------------------------------------------------------------------------ S3 with its parts
def choch_events(S: R.Side) -> list:
    """S3's events with what the add-ons need: (C, t = CHoCH bar, i = the lower-high pivot, b = the low bar,
    hprev = the swing high before i)."""
    ch = S.chart(TF)
    Hs, Ls = B.structure(ch)
    tr = B.trend(ch, Hs, Ls)
    out = []
    for t in range(1, ch.T):
        if tr[t - 1] != -1:
            continue
        i = int(Hs[t - 1, 0])
        if B._first_close_above(ch, i, t):
            b = i + int(np.argmin(ch.l[i:t + 1]))
            out.append(dict(c=B.C(int(ch.day[t]), B._entry(ch, t), R.stop_below(float(ch.l[b]))), t=t, i=i, b=b,
                            hprev=int(Hs[t - 1, 1]), Ls=Ls))
    return out


def smc_flags(S: R.Side, e: dict) -> dict:
    """L1-L4, D2 from the index chart only (bars <= t)."""
    ch = S.chart(TF)
    t, i, b, Ls = e["t"], e["i"], e["b"], e["Ls"]
    lb = ch.l[b]
    # L1: the down-leg (bars i..b) took a swing low that was known and untaken at the lower high i, or took the
    # previous session's low for the first time that session
    sw = False
    for j in Ls[i]:
        j = int(j)
        if j < 0:
            continue
        if ch.l[j + 1:i + 1].min(initial=np.inf) > ch.l[j] - TICK and lb <= ch.l[j] - TICK:
            sw = True
            break
    d = int(ch.day[b])
    if not sw and d >= 1 and np.isfinite(S.d_low[d - 1]):
        first = ch.first_bar.get(d, b)
        pdl = S.d_low[d - 1]
        sw = bool(lb <= pdl - TICK and ch.l[first:max(first, i + 1)].min(initial=np.inf) > pdl - TICK)
    disp = bool(np.isfinite(ch.atr[t]) and ch.c[t] - ch.o[t] >= DISPLACE_ATR * ch.atr[t])
    fvg = any(ch.same(x - 2, x) and ch.l[x] - ch.h[x - 2] >= R.fvg_min(ch.l[x]) for x in range(b + 2, t + 1))
    top = ch.h[e["hprev"]] if e["hprev"] >= 0 else np.nan
    disc = bool(np.isfinite(top) and ch.c[t] <= (top + lb) / 2)
    cap = bool(np.isfinite(ch.atr[t]) and ch.c[t] - e["c"].stop <= STOP_ATR * ch.atr[t])
    return dict(L1=sw, L2=disp, L3=fvg, L4=disc, D2=cap)


def ob_entry(S: R.Side, e: dict):
    """L5: the order block = the last red 15-min bar from the lower high to the low bar. After the CHoCH bar, the first
    1-minute low at or below the block's high within OB_WATCH bars (same session) buys at the next minute. Stop 0.02%
    below the lower of the block's low and the leg's low. 2R."""
    ch = S.chart(TF)
    t, i, b = e["t"], e["i"], e["b"]
    ob = next((x for x in range(b, i - 1, -1) if ch.red[x]), None)
    if ob is None:
        return None
    lv, stop = float(ch.h[ob]), R.stop_below(float(min(ch.l[ob], ch.l[b])))
    d = int(ch.day[t])
    for x in range(t + 1, min(t + OB_WATCH, ch.T - 1) + 1):
        if not ch.same(t, x):
            break
        r = R._first_touch(S.ml[d], int(ch.B.start[x]), int(ch.B.end[x]), lv)
        if r is not None:
            return B.C(d, r + 1, stop)
    return None


def slow_trend(S: R.Side):
    slow = R.Chart(R.make_bars(S.mo, S.mh, S.ml, S.mc, B.TF_SLOW), k=B.K_SLOW)
    if not slow.T:
        return slow, np.zeros(0, dtype=int)
    Hs, Ls = B.structure(slow)
    return slow, B.trend(slow, Hs, Ls)


def flow_flags(opts, r: int, side: int, lot: int, spot: float) -> dict:
    """V1, OF1, OF2 from the option chain at minute r (bars <= r of this session; R123's chain features)."""
    out = dict(V1=False, OF1=False, OF2=False)
    if opts is None:
        return out
    f = D3.chain_features(opts.chain, r, lot, spot)
    if np.isfinite(f["pcr_chg15"]):
        out["OF1"] = bool(f["pcr_chg15"] * side > 0)
    if np.isfinite(f["cp_vol15"]):
        out["OF2"] = bool(f["cp_vol15"] * side > 0)
    v = opts.chain["volume"][:, 5:16].sum(axis=(1, 2), where=np.isfinite(opts.chain["volume"][:, 5:16]))
    v = np.maximum(v, 0.0)
    if r - 15 >= 15:
        last15 = v[r - 14:r + 1].sum()
        base = v[:r - 14].mean() * 15
        out["V1"] = bool(base > 0 and last15 >= VOL_SURGE * base)
    return out


# ------------------------------------------------------------------------------------------------ trades
def sequence_daystop(sides: dict, cands: list, blocked: set, days, keep) -> list:
    """B.sequence plus D1: on an index, after DAY_LOSSES losing trades (index points < 0) in a day, none more."""
    out, losses = [], {}
    busy = -1
    for sd, c in cands:
        if losses.get(c.d, 0) >= DAY_LOSSES:
            continue
        T = B.sequence(sides, [(sd, c)], blocked, days, lambda d: True)
        if not T or c.d * N + c.r0 <= busy:
            continue
        t = T[0]
        busy = c.d * N + t["exit"]
        if t["pts"] < 0:
            losses[c.d] = losses.get(c.d, 0) + 1
        if keep(days[c.d]):
            out.append(t)
    return out


def _clean(cands: list) -> list:
    """B.sequence's both-sides rule needs the merged list sorted; a call and a put in one minute: neither."""
    cands = sorted(cands, key=lambda x: (x[1].d, x[1].r0, x[0]))
    out, i = [], 0
    while i < len(cands):
        j = i
        while j < len(cands) and cands[j][1].d == cands[i][1].d and cands[j][1].r0 == cands[i][1].r0:
            j += 1
        if len({s for s, _ in cands[i:j]}) == 1:
            out.append(cands[i])
        i = j
    return out


def run_index(args) -> dict:
    und, part, k3 = args
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    vix = D3.load_grid("INDIAVIX", hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0) + (50 if k3 else 0))
    sides = R.sides_of(g)
    cache = {}

    def src_for(day, closes):
        if day not in cache:
            cache.clear()
            cache[day] = (Mo if day in wexp else W).day(day, closes)
        return cache[day]

    # VIX: last known value before each minute, and the median of the previous 20 sessions' last values
    vpos = {d: i for i, d in enumerate(vix.days)}
    vlast = np.array([D3._last(vix.c[i]) for i in range(len(vix.days))])

    def vix_ok(day, r0):
        k = vpos.get(day)
        if k is None or k < VIX_DAYS:
            return False
        now = D3._last(vix.c[k][:r0])
        prev = vlast[k - VIX_DAYS:k]
        prev = prev[np.isfinite(prev)]
        return bool(np.isfinite(now) and len(prev) and now > np.median(prev))

    # every S3 event with its flags
    ev = []
    for sd in (1, -1):
        S = sides[sd]
        ch = S.chart(TF)
        slow, tr_slow = slow_trend(S)
        for e in choch_events(S):
            c = e["c"]
            if c.r0 > R.LAST_ENTRY or not keep(g.days[c.d]):
                continue
            fl = smc_flags(S, e)
            j = int(np.searchsorted(slow.gend, ch.gend[e["t"]], side="right")) - 1
            fl["C2"] = bool(j >= 0 and tr_slow[j] != -1)
            fl["C3"] = c.r0 <= MORNING_LAST
            fl["C1"] = vix_ok(g.days[c.d], c.r0)
            if c.d not in blocked:
                opts = src_for(g.days[c.d], g.c[c.d])
                fl.update(flow_flags(opts, c.r0 - 1, sd, lot, D3._last(g.c[c.d][:c.r0])))
            else:
                fl.update(V1=False, OF1=False, OF2=False)
            ev.append((sd, e, fl))

    def priced(T: list, v: str) -> list:
        rows = []
        for t in T:
            d = t["d"]
            opts = src_for(g.days[d], g.c[d])
            lastc = R.last_before(g.c[d])
            if opts is None or not np.isfinite(lastc[t["entry"]]):
                continue
            cp = "C" if t["side"] > 0 else "P"
            tr = R.option_trade(opts.contracts.get((cp, D3.atm(lastc[t["entry"]], step))), t["entry"], t["exit"],
                                t["why"], lot, g.days[d], und)
            if tr is None:
                continue
            S = sides[t["side"]]
            r1s = rng.integers(t["entry"], R.LAST_ENTRY + 1, size=TWINS)
            tw = R.twin_nets(r1s, lastc, S.mh[d], S.ml[d], S.mo[d], opts.contracts, cp, step, t["stop_pts"],
                             t["tgt_pts"], lot, g.days[d], und)
            rows.append(dict(t, und=und, variant=v, net=tr[0], netx2=tr[1], twins=tw))
        return rows

    def exit_trades(cands: list, exname: str, v: str) -> list:
        ex = Q.EXITS[exname]
        rows, busy = [], -1
        for sd, c in cands:
            g0 = c.d * N + c.r0
            if g0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
                continue
            S, d = sides[sd], c.d
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
            sp = ref - c.stop
            tw = np.full(TWINS, np.nan)
            for kk, r1 in enumerate(rng.integers(c.r0, R.LAST_ENTRY + 1, size=TWINS)):
                ref1 = S.mo[d][r1]
                if not (np.isfinite(ref1) and np.isfinite(lastc[r1])):
                    continue
                a1 = opts.contracts.get((cp, float(D3.atm(lastc[r1], step))))
                if a1 is None:
                    continue
                t1 = Q.sim_exit(a1, *R._contract_aux(a1), S.mh[d], S.ml[d], int(r1), ref1, ref1 - sp, ex, lot,
                                g.days[d], und)
                if t1 is not None:
                    tw[kk] = t1[2]
            rows.append(dict(und=und, d=d, day=g.days[d], side=sd, entry=c.r0, exit=xr, why=why, net=net, netx2=net2,
                             pts=0.0, R=0.0, variant=v, twins=tw.tolist()))
        return rows

    base = _clean([(sd, e["c"]) for sd, e, _ in ev])
    out = {}
    todo = {"K3": dict(kind="filter", f=tuple(k3))} if k3 is not None else {v: VARIANTS[v] for v in VARIANTS
                                                                             if v not in ("K3", "C4")}
    for v, var in todo.items():
        if var["kind"] == "filter":
            cs = _clean([(sd, e["c"]) for sd, e, fl in ev if all(fl[f] for f in var["f"])]) if var["f"] else base
            out[v] = priced(B.sequence(sides, cs, blocked, g.days, keep), v)
        elif var["kind"] == "ob":
            cs = _clean([(sd, x) for sd, e, _ in ev if (x := ob_entry(sides[sd], e)) is not None])
            out[v] = priced(B.sequence(sides, cs, blocked, g.days, keep), v)
        elif var["kind"] == "daystop":
            out[v] = priced(sequence_daystop(sides, base, blocked, g.days, keep), v)
        elif var["kind"] == "exit":
            out[v] = exit_trades(base, var["ex"], v)
    print(f"R139A {part} {und}: " + ", ".join(f"{k} {len(x)}" for k, x in out.items()), flush=True)
    return out


# ------------------------------------------------------------------------------------------------ the run
def lock(status: str):
    p = OUT / "HOLDOUT_READ_A.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout_a.json").exists() or p.exists():
            raise R.HoldoutError("refused: the R139 Amendment A holdout was already read")
        if not (RESULTS / "design_a.json").exists():
            raise R.HoldoutError("refused: commit the Amendment A design results first")
        rec = dict(status="started", started_at=now, note="holdout months already seen by R139")
    else:
        rec = dict(json.loads(p.read_text()), status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def pick_k3(variants: dict) -> list:
    """K3's filters (PREREG_A): every single filter whose design average net beats A0's on >= 300 trades."""
    a0 = variants["A0"].get("avg_net", np.inf)
    return [f for f in SINGLE if variants[f].get("n", 0) >= GATES["n"] and variants[f].get("avg_net", -np.inf) > a0]


def run(part: str):
    from multiprocessing import Pool
    if part == "holdout":
        lock("start")
    procs = int(os.environ.get("R139_PROCS", "4"))
    with Pool(procs) as pool:
        res = pool.map(run_index, [(u, part, None) for u in K.UNDS])
    frames = {v: pd.DataFrame([r for x in res for r in x[v]]) for v in res[0]}
    out = dict(part=part, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)], variants={},
               note=("design" if part == "design" else "holdout 1 Apr - 19 Oct 2026, ALREADY SEEN by R139 S3"))
    for v, T in frames.items():
        s = B.stats(T, part == "design") if len(T) else dict(n=0)
        s["p"] = R.twin_p(T, SEED)[0] if len(T) else 1.0
        s["label"] = VARIANTS[v]["label"]
        out["variants"][v] = s
    # C4: the network on A0's trades (R132's subset test)
    rng = np.random.default_rng(SEED + 7 + (100 if part != "design" else 0))
    M, kp = B.nn_filter(frames["A0"], part)
    s = B.stats(M[kp], part == "design") if kp.any() else dict(n=0)
    strata = np.array([K.fold_of(d) or "H" for d in M.day])
    s["p"] = NN.subset_p(M.net.values, kp, strata, rng) if kp.any() else 1.0
    s["label"] = VARIANTS["C4"]["label"]
    out["variants"]["C4"] = s
    # K3
    if part == "design":
        k3 = pick_k3(out["variants"])
    else:
        k3 = json.loads((RESULTS / "design_a.json").read_text())["k3_filters"]
    out["k3_filters"] = k3
    if k3:
        with Pool(procs) as pool:
            r3 = pool.map(run_index, [(u, part, k3) for u in K.UNDS])
        T = pd.DataFrame([r for x in r3 for r in x["K3"]])
        frames["K3"] = T
        s = B.stats(T, part == "design") if len(T) else dict(n=0)
        s["p"] = R.twin_p(T, SEED)[0] if len(T) else 1.0
    else:
        s = dict(n=0, p=1.0)
    s["label"] = VARIANTS["K3"]["label"] + f" ({' + '.join(k3) or 'none qualified'})"
    out["variants"]["K3"] = s
    out["variants"] = {v: out["variants"][v] for v in VARIANTS}
    if part == "design":
        qs = R.R3.bh([out["variants"][v].get("p", 1.0) for v in TESTED])
        for v, q in zip(TESTED, qs):
            sv = out["variants"][v]
            sv["q"] = float(q)
            sv["gates"] = B.gates(sv, q)
            sv["passes_design"] = all(sv["gates"].values())
    else:
        for sv in out["variants"].values():
            sv["passes_holdout"] = (sv.get("n", 0) >= GATES["holdout_n"] and sv.get("avg_net", -1) > 0
                                    and sv.get("avg_net_x2", -1) > 0)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}_a.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    for v, f in frames.items():
        if len(f):
            f.drop(columns=["twins"]).to_parquet(OUT / f"a_trades_{v}_{part}.parquet")
    if part == "holdout":
        lock("done")
    print("K3:", k3)
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s.get("p", 1), 3))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
