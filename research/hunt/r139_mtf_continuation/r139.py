"""R139: the multi-timeframe stack, continuation version (PREREG.md): 4-hour trend -> 1-hour level -> 15-minute close
through it with the trend -> 5-minute FVG / order-block pullback -> 3-minute entry. Buys the ATM option.

    python research/hunt/r139_mtf_continuation/r139.py design    # -> results/design.json
    python research/hunt/r139_mtf_continuation/r139.py holdout   # the ONE holdout read -> results/holdout.json

Signal source: the index spot, every layer. Written for the bullish side; the bearish side runs the same code on R131's
mirrored side (high -> -low ...), so it buys the put. Reuses R131 (bars, pivots, option rule, costs, twins), R132 (the
E4 trail exit), R138 (pricing, sequence) and R138 Amendment A (R123's N1 network filter).
"""
from __future__ import annotations

import json
import os
import sys
import warnings
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r138_triple_top_fake"))
import r138 as M8  # noqa: E402
import r138b as A8  # noqa: E402

R, K, D3, Q = M8.R, M8.K, M8.D3, A8.Q
N = R.N
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R139_OUT", K.K3.CACHE_ROOT / "r139")).expanduser()
TICK = 0.05
H4_SPLIT = 240                      # the morning 4-hour candle is rows 0-239 (09:15-13:14)
EMA_N = 20
H1_K = 2                            # hourly swing: 2 hours each side
H4_K = 2
SESSIONS_BACK = 4
ZONE_BARS = 6                       # the break candle's three 5-minute candles and the three before
PULL_BARS = 12                      # 5-minute candles to wait for the pullback
ENTRY_BARS = 12                     # 3-minute candles to wait for the entry
TWINS = 100
SEED = 139
VARIANTS = {
    "C1": dict(trend="ema", stack=True, exit="2R", nn=False, label="full stack, 2R"),
    "C2": dict(trend="ema", stack=True, exit="3R", nn=False, label="full stack, 3R"),
    "C3": dict(trend="ema", stack=True, exit="trail", nn=False, label="full stack, breakeven at 1R then 1R trail"),
    "C4": dict(trend=None, stack=True, exit="2R", nn=False, label="no 4-hour trend, 2R"),
    "C5": dict(trend="ema", stack=False, exit="2R", nn=False, label="no 5m / 3m steps (enter on the 15m break), 2R"),
    "C6": dict(trend="ema", stack=True, exit="2R", nn=True, label="full stack + R123 network filter, 2R"),
    "C7": dict(trend="swing", stack=True, exit="2R", nn=False, label="4-hour trend as swing structure, 2R"),
}
GATES = dict(n=300, folds=3, q=0.10, dd=-40000.0, month=-13000.0, holdout_n=50)


# ------------------------------------------------------------------------------------------------ helpers
def _agg(x: np.ndarray, how: str) -> float:
    x = x[np.isfinite(x)]
    if not len(x):
        return np.nan
    return float({"o": x[0], "c": x[-1], "h": x.max(), "l": x.min()}[how])


def bars_of(S, d: int, tf: int):
    """One session's tf-minute candles from 09:15 (o, h, l, c arrays; NaN where a candle has no minute)."""
    nb = N // tf
    cut = nb * tf
    rs = lambda x: x[d, :cut].reshape(nb, tf)  # noqa: E731
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        h = np.nanmax(rs(S.mh), 1)
        l = np.nanmin(rs(S.ml), 1)
    o = np.array([_agg(r, "o") for r in rs(S.mo)])
    c = np.array([_agg(r, "c") for r in rs(S.mc)])
    return o, h, l, c


# ------------------------------------------------------------------------------------------------ 4 hour
class Trend4H:
    """The 4-hour candles of one side (two per session) and the trend known at any minute."""

    def __init__(self, S):
        D = S.mh.shape[0]
        H, L, C = [], [], []
        for d in range(D):
            for a, b in ((0, H4_SPLIT), (H4_SPLIT, N)):
                H.append(_agg(S.mh[d, a:b], "h"))
                L.append(_agg(S.ml[d, a:b], "l"))
                C.append(_agg(S.mc[d, a:b], "c"))
        self.h, self.l, self.c = map(np.array, (H, L, C))
        n = len(self.c)
        ema = np.full(n, np.nan)
        e, alpha = np.nan, 2.0 / (EMA_N + 1)
        for i, x in enumerate(self.c):
            if np.isfinite(x):
                e = x if not np.isfinite(e) else e + alpha * (x - e)
            ema[i] = e
        prev = np.r_[np.nan, ema[:-1]]
        with np.errstate(invalid="ignore"):
            self.up_ema = np.isfinite(self.c) & (self.c > ema) & (ema > prev)
        self.up_swing = self._swing()

    def _swing(self) -> np.ndarray:
        """At each 4-hour candle's close: the last two confirmed swing highs AND swing lows both rising."""
        n = len(self.c)
        hh = np.where(np.isfinite(self.h), self.h, -np.inf)
        ll = np.where(np.isfinite(self.l), self.l, np.inf)
        sh = set(R.swing_highs(hh, H4_K).tolist())
        sl = set(R.swing_highs(-ll, H4_K).tolist())
        out = np.zeros(n, dtype=bool)
        his, los = [], []
        for i in range(n):
            j = i - H4_K                       # a pivot at j is confirmed when candle j + 2 closes
            if j in sh:
                his.append(hh[j])
            if j in sl:
                los.append(ll[j])
            out[i] = len(his) >= 2 and len(los) >= 2 and his[-1] > his[-2] and los[-1] > los[-2]
        return out

    @staticmethod
    def last_done(d: int, r: int) -> int:
        """Index of the last 4-hour candle completed before minute r of session d (-1: none)."""
        return 2 * d if r >= H4_SPLIT else 2 * d - 1

    def up(self, d: int, r: int, how: str) -> bool:
        i = self.last_done(d, r)
        if i < 0:
            return False
        return bool((self.up_ema if how == "ema" else self.up_swing)[i])


# ------------------------------------------------------------------------------------------------ 1 hour and 15 min
def breaks(S, trend: Trend4H | None, how: str | None) -> list:
    """15-minute closes through an untaken 1-hour swing high, with the 4-hour trend (how=None: no trend).
    (d, 15m bar start row, 15m bar end row, the broken level, the break candle's low)."""
    h1 = R.Chart(R.make_bars(S.mo, S.mh, S.ml, S.mc, 60), k=H1_K)
    ch = S.chart(15)
    piv = h1.sh
    if not len(piv) or not ch.T:
        return []
    conf = np.array([h1.gend[min(i + H1_K, h1.T - 1)] if i + H1_K < h1.T else 10 ** 12 for i in piv])
    lv = h1.h[piv]
    pday = h1.day[piv]
    flat = S.flat_h
    out = []
    for t in range(1, ch.T):
        d = int(ch.day[t])
        g0 = d * N + int(ch.B.start[t])
        c_now, c_prev = ch.c[t], ch.c[t - 1]
        sel = np.flatnonzero((pday >= d - SESSIONS_BACK) & (conf < g0) & (lv < c_now) & (lv >= c_prev))
        best = None
        for x in sel:
            i = int(piv[x])
            seg = flat[int(h1.gend[i]) + 1:g0]
            seg = seg[np.isfinite(seg)]
            if len(seg) and seg.max() >= lv[x] + TICK:
                continue                        # taken before this candle
            if best is None or lv[x] > best:
                best = float(lv[x])
        if best is None:
            continue
        e = int(ch.B.end[t])
        if how is not None and not trend.up(d, e + 1, how):
            continue
        out.append((d, int(ch.B.start[t]), e, best, float(ch.l[t])))
    return out


# ------------------------------------------------------------------------------------------------ 5 min and 3 min
@dataclass
class Setup:
    d: int
    r0: int            # entry minute (bought at its open)
    stop: float
    stage: str         # how far it got: zone / pullback / entry


def stack_entry(S, d: int, s: int, cache: dict) -> Setup:
    """From a 15-minute break candle starting at row s of session d: the 5-minute zone, pullback and 3-minute entry."""
    if (d, 5) not in cache:
        cache[(d, 5)] = bars_of(S, d, 5)
        cache[(d, 3)] = bars_of(S, d, 3)
    o5, h5, l5, c5 = cache[(d, 5)]
    o3, h3, l3, c3 = cache[(d, 3)]
    s5 = s // 5
    win = [j for j in range(s5 - 3, s5 + 3) if 0 <= j < len(h5)]
    zone = None
    for j in reversed(win):                     # the most recent bullish FVG
        if j - 2 >= 0 and np.isfinite(l5[j]) and np.isfinite(h5[j - 2]) and l5[j] > h5[j - 2]:
            zone = (float(h5[j - 2]), float(l5[j]))
            break
    if zone is None:
        for j in reversed(win):                 # else the last red candle (the order block)
            if np.isfinite(c5[j]) and np.isfinite(o5[j]) and c5[j] < o5[j]:
                zone = (float(l5[j]), float(h5[j]))
                break
    if zone is None:
        return Setup(d, -1, np.nan, "none")
    zb, zt = zone
    pull = None
    for j in range(s5 + 3, min(s5 + 3 + PULL_BARS, len(h5))):
        if np.isfinite(l5[j]) and l5[j] <= zt:
            if np.isfinite(c5[j]) and c5[j] < zb:
                return Setup(d, -1, np.nan, "zone")       # closed through the zone: cancelled
            pull = j
            break
    if pull is None:
        return Setup(d, -1, np.nan, "zone")
    pe = pull * 5 + 4
    m0 = (pe + 1 + 2) // 3                       # the first 3-minute candle starting after the pullback closes
    for m in range(max(m0, 1), min(m0 + ENTRY_BARS, len(h3))):
        if np.isfinite(l3[m]) and l3[m] < zb:
            return Setup(d, -1, np.nan, "pullback")
        if np.isfinite(c3[m]) and np.isfinite(h3[m - 1]) and c3[m] > h3[m - 1]:
            r0 = 3 * m + 3
            lows = S.ml[d, pull * 5:r0]
            lows = lows[np.isfinite(lows)]
            if not len(lows) or r0 >= N:
                return Setup(d, -1, np.nan, "pullback")
            return Setup(d, r0, R.stop_below(float(lows.min())), "entry")
    return Setup(d, -1, np.nan, "pullback")


def candidates(sides: dict, var: dict, trends: dict, funnel: dict | None = None, keep_day=None) -> list:
    out = []
    for sd in (1, -1):
        S = sides[sd]
        cache = {}
        for d, s, e, lvl, blow in breaks(S, trends[sd], var["trend"]):
            counted = funnel is not None and (keep_day is None or keep_day(d))
            if counted:
                funnel["breaks"] += 1
            if not var["stack"]:
                out.append((sd, R.Cand(d, e + 1, R.stop_below(blow), 15)))
                continue
            st = stack_entry(S, d, s, cache)
            if counted:
                for k, stages in (("zone", ("zone", "pullback", "entry")), ("pullback", ("pullback", "entry")),
                                  ("entry", ("entry",))):
                    funnel[k] += st.stage in stages
            if st.stage == "entry":
                out.append((sd, R.Cand(d, st.r0, st.stop, 15)))
    out.sort(key=lambda x: (x[1].d, x[1].r0, x[0]))
    return out


# ------------------------------------------------------------------------------------------------ trades
def sequence(sides, cands, mult: float, blocked, days, keep) -> list:
    """One trade at a time per index; both sides in one minute: neither. Target mult x R from the entry open."""
    trades, busy, i = [], -1, 0
    while i < len(cands):
        sd, c = cands[i]
        j = i
        while j < len(cands) and cands[j][1].d == c.d and cands[j][1].r0 == c.r0:
            j += 1
        same, i = cands[i:j], j
        if len({x[0] for x in same}) == 2 or c.d * N + c.r0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
            continue
        S = sides[sd]
        mh, ml, mo = S.mh[c.d], S.ml[c.d], S.mo[c.d]
        ref = mo[c.r0]
        if not np.isfinite(ref) or ref <= c.stop:
            continue
        tgt = ref + mult * (ref - c.stop)
        ex = R.exit_on(mh, ml, mo, c.r0, c.stop, tgt, R.EXIT_LAST)
        if ex is None:
            continue
        xr, why, xpx = ex
        busy = c.d * N + xr
        if keep(days[c.d]):
            trades.append(dict(d=c.d, day=days[c.d], side=sd, entry=c.r0, exit=xr, why=why,
                               stop_pts=float(ref - c.stop), tgt_pts=float(tgt - ref), pts=float(xpx - ref),
                               R=float((xpx - ref) / (ref - c.stop))))
    return trades


def trail_rows(und, g, sides, cands, blocked, keep, src_for, lot, step, rng) -> list:
    """C3: R132's E4 exit (index stop, breakeven at 1R, then a 1R trail), twins with the same exit."""
    ex = Q.EXITS["E4"]
    rows, busy, i = [], -1, 0
    while i < len(cands):
        sd, c = cands[i]
        j = i
        while j < len(cands) and cands[j][1].d == c.d and cands[j][1].r0 == c.r0:
            j += 1
        same, i = cands[i:j], j
        if len({x[0] for x in same}) == 2 or c.d * N + c.r0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
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
    und, part = args
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    off = K.UNDS.index(und) + (100 if part != "design" else 0)
    rng = np.random.default_rng(SEED + off)
    sides = R.sides_of(g)
    trends = {sd: Trend4H(sides[sd]) for sd in (1, -1)}
    cache = {}

    def src_for(day, closes):
        if day not in cache:
            cache.clear()
            cache[day] = (Mo if day in wexp else W).day(day, closes)
        return cache[day]

    funnel = dict(breaks=0, zone=0, pullback=0, entry=0)
    cand = {}
    for v, var in VARIANTS.items():
        key = (var["trend"], var["stack"])
        if key not in cand:
            cand[key] = candidates(sides, var, trends, funnel if v == "C1" else None, lambda d: keep(g.days[d]))
    trades = {}
    for v, var in VARIANTS.items():
        if var["nn"]:
            continue                                # C6 is C1's trades filtered (in run())
        cs = cand[(var["trend"], var["stack"])]
        if var["exit"] == "trail":
            trades[v] = trail_rows(und, g, sides, cs, blocked, keep, src_for, lot, step, rng)
        else:
            T = sequence(sides, cs, 3.0 if var["exit"] == "3R" else 2.0, blocked, g.days, keep)
            trades[v] = A8.price_rows(und, g, sides, T, src_for, lot, step, rng)
    print(f"R139 {part} {und}: funnel {funnel}; " + ", ".join(f"{k} {len(x)}" for k, x in trades.items()), flush=True)
    return dict(trades=trades, funnel=funnel)


# ------------------------------------------------------------------------------------------------ the run
def lock(status: str):
    p = OUT / "HOLDOUT_READ.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout.json").exists() or p.exists():
            raise R.HoldoutError("refused: the R139 holdout was already read")
        if not (RESULTS / "design.json").exists():
            raise R.HoldoutError("refused: commit the design results first")
        rec = dict(status="started", started_at=now)
    else:
        rec = dict(json.loads(p.read_text()), status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def run(part: str):
    from multiprocessing import Pool
    if part == "holdout":
        lock("start")
    with Pool(int(os.environ.get("R139_PROCS", "4"))) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    rng = np.random.default_rng(SEED + 7 + (100 if part != "design" else 0))
    out = dict(part=part, funnel={u: x["funnel"] for u, x in zip(K.UNDS, res)}, variants={})
    for v, var in VARIANTS.items():
        T = pd.DataFrame([r for x in res for r in x["trades"].get("C1" if var["nn"] else v, [])])
        if not len(T):
            out["variants"][v] = dict(n=0, p=1.0, label=var["label"])
            continue
        if var["nn"]:
            s = A8.network(T, part, rng)
        else:
            s = M8.stats(T, part == "design")
            s["p"], s["twin_mean_total"], _ = R.twin_p(T, SEED)
            s["avg_index_pts"] = float(T.pts.mean())
            OUT.mkdir(parents=True, exist_ok=True)
            T.drop(columns=["twins"]).to_parquet(OUT / f"trades_{v}_{part}.parquet")
        s["label"] = var["label"]
        out["variants"][v] = s
    if part == "design":
        qs = R.R3.bh([out["variants"][v].get("p", 1.0) for v in VARIANTS])
        for v, q in zip(VARIANTS, qs):
            s = out["variants"][v]
            s["q"] = float(q)
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["gates"] = dict(n=s.get("n", 0) >= GATES["n"],
                              net=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                              folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"],
                              account=s.get("max_dd", -1e9) > GATES["dd"] and s.get("worst_month", -1e9) > GATES["month"])
            s["passes_design"] = all(s["gates"].values())
    else:
        for s in out["variants"].values():
            s["passes_holdout"] = s.get("n", 0) >= GATES["holdout_n"] and s.get("avg_net", -1) > 0
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(out, indent=1, default=str))
    if part == "holdout":
        lock("done")
    print(out["funnel"])
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s.get("p", 1), 3))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
