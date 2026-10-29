"""R132: R131's ten as-stated ICT entries with premium stop-losses, a 60% profit lock and an index breakeven trail
(PREREG.md). 40 variants (10 entries x 4 exits).

    python research/hunt/r132_ict_exits/r132.py design         # design walk-forward -> results/design.json
    python research/hunt/r132_ict_exits/r132.py holdout_seen   # the R131 holdout period, ALREADY SEEN for these
                                                               # entries (context only) -> results/holdout_seen.json

Signal source: the index spot (R131's detectors, unchanged). Stops and locks E1-E3 are on the option premium, read
wick to wick (the minute's high, low and open); E3 / E4 index stops on the index's wicks.
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
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r131 as R  # noqa: E402

K, D3 = R.K, R.D3
N = R.N
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R132_OUT", K.K3.CACHE_ROOT / "r132")).expanduser()
EXIT_LAST = R.EXIT_LAST
LAST_ENTRY = R.LAST_ENTRY
TWINS = 100
SEED = 132
LOCK_AFTER, LOCK_KEEP = 1000.0, 0.60

ENTRIES = ("S1-V1", "S2-V1", "S3-V1", "S4-V1", "S5-V1", "S6-V1", "S7-V1", "S8-V1", "S9-V1", "S10-V1")
EXITS = {
    "E1": dict(prem_stop=1000.0, lock=True, idx="none", label="premium stop -Rs 1,000, 60% lock after +Rs 1,000"),
    "E2": dict(prem_stop=2000.0, lock=True, idx="none", label="premium stop -Rs 2,000, 60% lock after +Rs 1,000"),
    "E3": dict(prem_stop=None, lock=True, idx="fixed", label="R131 index stop, 60% lock after +Rs 1,000"),
    "E4": dict(prem_stop=None, lock=False, idx="trail", label="R131 index stop, breakeven at 1R, then a 1R trail"),
}
VARIANTS = {f"{e}-{x}": (e, x) for e in ENTRIES for x in EXITS}
GATES = dict(n=300, folds=3, q=0.10, dd=-40000.0, month=-13000.0, seen_n=50)


# ------------------------------------------------------------------------------------------------ the exit simulator
def _vol5(cv: np.ndarray, r) -> np.ndarray:
    r = np.asarray(r)
    return cv[r] - cv[np.maximum(r - 5, 0)]


def sim_exit(a: np.ndarray, cv: np.ndarray, li: np.ndarray, mh: np.ndarray, ml: np.ndarray, r0: int, ref: float,
             stop_idx: float, ex: dict, lot: int, d, und: str, last: int = EXIT_LAST):
    """One trade bought at the open of minute r0 (option array a: open, high, low, close, volume; NaN = no trade).
    mh / ml: this side's index minute highs / lows (bearish sides are mirrored). Returns
    (exit row, why, net, net at 2x slippage) or None when the option has no price at entry.
    Within a minute, levels use only earlier minutes' highs; the worse exit counts if two trigger together."""
    op = a[r0, 0]
    if not (np.isfinite(op) and op > 0):
        return None
    B = float(D3.FILLS.buy(np.array([op]), np.array([_vol5(cv, r0) / lot]))[0])
    seg = slice(r0, last + 1)
    o, h, lo = a[seg, 0], a[seg, 1], a[seg, 2]
    n = len(o)
    big = 10 ** 9
    # premium levels (E1-E3): the peak open profit from EARLIER minutes' highs
    tp = big
    plev = np.full(n, -np.inf)
    if ex["prem_stop"] is not None:
        plev[:] = B - ex["prem_stop"] / lot
    if ex["lock"]:
        hh = np.where(np.isfinite(h), h, -np.inf)
        peak_prev = np.r_[-np.inf, np.maximum.accumulate(hh)[:-1]]
        gain = (peak_prev - B) * lot
        lock = np.where(gain >= LOCK_AFTER, B + LOCK_KEEP * gain / lot, -np.inf)
        plev = np.maximum(plev, lock)
    okp = np.isfinite(lo) & (plev > -np.inf)
    hit = np.flatnonzero(okp & (lo <= plev))
    if len(hit):
        tp = int(hit[0])
    # index stops (E3 fixed, E4 breakeven + trail)
    ti = big
    if ex["idx"] != "none":
        il, ih = ml[seg], mh[seg]
        if ex["idx"] == "fixed":
            lev = np.full(n, stop_idx)
        else:
            Rpts = ref - stop_idx
            bh = np.where(np.isfinite(ih), ih, -np.inf)
            best_prev = np.r_[-np.inf, np.maximum.accumulate(bh)[:-1]]
            lev = np.where(best_prev - ref >= Rpts, np.maximum(stop_idx, best_prev - Rpts), stop_idx)
        hit = np.flatnonzero(np.isfinite(il) & (il <= lev))
        if len(hit):
            ti = int(hit[0])

    def sell_at(m: int, px: float, stop: bool):
        s = float(D3.FILLS.sell(np.array([px]), np.array([_vol5(cv, m) / lot]), stop=stop)[0])
        net = (s - B) * lot - D3.charge(True, B, lot, d, und) - D3.charge(False, s, lot, d, und)
        slip = (B - op + px - s) * lot
        return net, net - slip

    def index_exit(m: int):
        rr = li[m]
        if rr < r0:
            return None
        return sell_at(m, float(a[rr, 3]), True)

    t0 = min(tp, ti)
    if t0 < big:
        m = r0 + t0
        outs = []
        if tp == t0:
            px = min(plev[t0], o[t0]) if np.isfinite(o[t0]) else plev[t0]
            outs.append(("lock" if ex["lock"] and (ex["prem_stop"] is None or plev[t0] > B - ex["prem_stop"] / lot)
                         else "pstop", sell_at(m, float(px), True)))
        if ti == t0:
            ie = index_exit(m)
            if ie is not None:
                outs.append(("istop", ie))
        if outs:
            why, (net, net2) = min(outs, key=lambda x: x[1][0])
            return m, why, net, net2
    rr = li[last]
    if rr < r0:
        return None
    net, net2 = sell_at(int(rr), float(a[rr, 3]), False)
    return int(rr), "time", net, net2


# ------------------------------------------------------------------------------------------------ the run
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
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    sides = R.sides_of(g)
    by_day: dict = {}
    for e in ENTRIES:
        for sd, c in R.candidates(sides, R.VARIANTS[e]):
            if keep(g.days[c.d]):
                by_day.setdefault(c.d, {}).setdefault(e, []).append((sd, c))
    rows = []
    skips = {v: dict(both=0, busy=0, late=0, no_contract=0, no_price=0, no_room=0, no_target=0, no_option=0)
             for v in VARIANTS}
    for d in sorted(by_day):
        day = g.days[d]
        src = Mo if day in wexp else W
        opts = src.day(day, g.c[d]) if d not in blocked else None
        lastc = R.last_before(g.c[d])
        aux = {}

        def contract(cp, r):
            if opts is None or not np.isfinite(lastc[r]):
                return None
            key = (cp, float(D3.atm(lastc[r], step)))
            if key not in aux:
                a = opts.contracts.get(key)
                aux[key] = None if a is None else (a, *R._contract_aux(a))
            return aux[key]

        for e, cands in by_day[d].items():
            cands.sort(key=lambda x: (x[1].r0, x[0]))
            for x, ex in EXITS.items():
                v = f"{e}-{x}"
                sk = skips[v]
                busy = -1
                i = 0
                while i < len(cands):
                    sd, c = cands[i]
                    j = i
                    while j < len(cands) and cands[j][1].r0 == c.r0:
                        j += 1
                    same, i = cands[i:j], j
                    if len({s for s, _ in same}) == 2:
                        sk["both"] += 1
                        continue
                    if c.r0 <= busy:
                        sk["busy"] += 1
                        continue
                    if c.r0 > LAST_ENTRY:
                        sk["late"] += 1
                        continue
                    if d in blocked:
                        sk["no_contract"] += 1
                        continue
                    S = sides[sd]
                    ref = S.mo[d][c.r0]
                    if not np.isfinite(ref):
                        sk["no_price"] += 1
                        continue
                    if ref <= c.stop:
                        sk["no_room"] += 1
                        continue
                    tgt = R.struct_target(S, S.chart(c.tf), d, c.r0, ref)    # N1: R131's entry filter, unchanged
                    if tgt is None or tgt <= ref:
                        sk["no_target"] += 1
                        continue
                    cp = "C" if sd > 0 else "P"
                    ct = contract(cp, c.r0)
                    res = None if ct is None else sim_exit(ct[0], ct[1], ct[2], S.mh[d], S.ml[d], c.r0, ref, c.stop,
                                                           ex, lot, day, und)
                    if res is None:
                        sk["no_option"] += 1
                        continue
                    xr, why, net, net2 = res
                    busy = xr
                    stop_pts = ref - c.stop
                    tw = np.full(TWINS, np.nan)
                    for k, r1 in enumerate(rng.integers(c.r0, LAST_ENTRY + 1, size=TWINS)):
                        ref1 = S.mo[d][r1]
                        ct1 = contract(cp, int(r1))
                        if ct1 is None or not np.isfinite(ref1):
                            continue
                        t1 = sim_exit(ct1[0], ct1[1], ct1[2], S.mh[d], S.ml[d], int(r1), ref1, ref1 - stop_pts, ex,
                                      lot, day, und)
                        if t1 is not None:
                            tw[k] = t1[2]
                    mid = (S.mh[d][xr] + S.ml[d][xr]) / 2
                    rows.append(dict(variant=v, entry_rule=e, exit_rule=x, und=und, d=d, day=day, side=sd,
                                     entry=c.r0, exit=xr, why=why, net=net, netx2=net2,
                                     pts=float(mid - ref) if np.isfinite(mid) else 0.0,
                                     R=float((mid - ref) / stop_pts) if np.isfinite(mid) else 0.0,
                                     twins=tw.tolist()))
    print(f"R132 {part} {und}: {len(rows)} trades", flush=True)
    return dict(rows=rows, skips=skips)


def stats(T: pd.DataFrame, by_fold: bool) -> dict:
    T = T.sort_values(["day", "und", "entry"]).reset_index(drop=True)
    s = R.money(T)
    if not s["n"]:
        return s
    s["p_twins"], s["twin_mean_total"], s["twin_trades"] = R.twin_p(T, SEED)
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


def seen_lock(status: str, out: Path = OUT, results: Path = RESULTS) -> None:
    """One read of the (already seen) holdout period for R132."""
    p = out / "HOLDOUT_SEEN_READ.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (results / "holdout_seen.json").exists() or p.exists():
            raise R.HoldoutError("refused: R132 already read the holdout period")
        rec = dict(status="started", started_at=now)
    else:
        rec = dict(json.loads(p.read_text()), status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def run(part: str):
    from multiprocessing import Pool
    if part != "design":
        seen_lock("start")
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    T = pd.DataFrame([r for x in res for r in x["rows"]])
    out = dict(part=part, label=("design walk-forward" if part == "design" else
                                 "the R131 holdout period, ALREADY SEEN for these entries (context only)"),
               variants={})
    for v, (e, x) in VARIANTS.items():
        s = stats(T[T.variant == v], part == "design") if len(T) else dict(n=0)
        s["entry"], s["exit"] = e, x
        s["label"] = f"{R.VARIANTS[e]['label']} | {EXITS[x]['label']}"
        s["skips"] = {k: sum(r["skips"][v][k] for r in res) for k in res[0]["skips"][v]}
        out["variants"][v] = s
    if part == "design":
        qs = R.R3.bh([out["variants"][v].get("p_twins", 1.0) for v in VARIANTS])
        for v, q in zip(VARIANTS, qs):
            s = out["variants"][v]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["gates"] = dict(n=s.get("n", 0) >= GATES["n"],
                              net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                              folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"],
                              account=s.get("max_dd", -1e9) > GATES["dd"] and s.get("worst_month", -1e9) > GATES["month"])
            s["passes_design"] = all(s["gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins"]).to_parquet(OUT / f"trades_{part}.parquet")
    if part != "design":
        seen_lock("done")
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2),
               round(s.get("p_twins", 1), 3)) for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
