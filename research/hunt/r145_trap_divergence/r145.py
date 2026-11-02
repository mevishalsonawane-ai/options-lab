"""R145: the "demand zone trap + put premium divergence + OI" video setup (PREREG.md), on the Drive lake, all five
indices, buying the ATM option of the nearest expiry that does not expire that day.

    python research/hunt/r145_trap_divergence/r145.py design     # design period -> results/design.json
    python research/hunt/r145_trap_divergence/r145.py holdout    # the ONE holdout read -> results/holdout.json

Signal source: the index spot (the underlying): the zone, the breakdown, the reclaim, the stop and the target are read
on the index's bars. The put's premium (factor 3) and the put's OI (factor 4) are option-chain context around that
signal; the ATM call is what is bought. V5's profit lock reads the held option's premium.

Bearish setups (V6) run the bullish code on the mirrored index (R130's `mirror`), so one code path serves both sides;
levels in the mirrored space are negative, so every percentage uses |level|.
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r130_sweep_mss_fvg"))
import r130 as R  # noqa: E402  (R130's bars, exits, option pricing, twins and stats; puts r123 / r124 on the path)

K, D3, R3 = R.K, R.D3, R.R3
N = K.N
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R145_OUT", K.K3.CACHE_ROOT / "r145")).expanduser()
TICK = 0.05
TF = 5
SWING_K = 3
SESSIONS_BACK = 4                  # zones from today and the previous 4 sessions
RALLY = 0.010                      # the zone's rally: 1.0% above its swing low before the low is taken
NEW_LOW_BARS = 5                   # the breakdown bar's low is below the 5 bars before it (30 minutes)
WIN = 30                           # factor windows: minutes e-29 .. e
MIN_BEFORE = 10                    # traded minutes of the put needed in e-29 .. e-5
OI_UP = 0.02                       # factor 4: OI up 2% over the window
STOP_PCT = 0.0002                  # stop 0.02% of L below the lowest low
RECLAIM_BARS = 6                   # V2: a close back above L within 6 bars
LOCK_AFTER, LOCK_KEEP = 1000.0, 0.60   # V5: once Rs 1,000 up (per lot), out at 60% of the best gain
FIRST_BD_END = K.row(9, 44)        # the breakdown bar ends 09:44 .. 14:59
LAST_BD_END = K.row(14, 59)
LAST_ENTRY = K.row(15, 0)
FIRST_TWIN = K.row(9, 20)
RACE = 0.0025                      # claims: a 0.25% 1:1 race from the breakdown bar's close
TWINS = 200
SEED = 145

VARIANTS = {
    "V1": dict(f3=True, f4=True, entry="next", exit="2R", sides=(1,), label="the setup as stated"),
    "V2": dict(f3=True, f4=True, entry="reclaim", exit="2R", sides=(1,), label="enter on a close back above the zone"),
    "V3": dict(f3=True, f4=False, entry="next", exit="2R", sides=(1,), label="no OI condition"),
    "V4": dict(f3=False, f4=False, entry="next", exit="2R", sides=(1,), label="baseline: zone + breakdown only"),
    "V5": dict(f3=True, f4=True, entry="next", exit="lock", sides=(1,),
               label="ride it: index stop + 60% premium lock after Rs 1,000"),
    "V6": dict(f3=True, f4=True, entry="next", exit="2R", sides=(1, -1), label="both sides (mirror)"),
}
GATES = R.GATES


# ------------------------------------------------------------------------------------------------ zones and breakdowns
@dataclass
class Zone:
    L: float
    i: int            # the swing bar
    day: int          # its session index
    peak: float
    rallied: bool = False


def breakdowns(B, census: dict | None = None) -> list[dict]:
    """Every first close below a qualified demand zone (PREREG section 1), on one side's 5-minute bars B (time order,
    across sessions). Uses bar t only after it closed; a swing at i is known only once bar i + 3 closed."""
    out, zones = [], []
    T = len(B.day)
    cen = census if census is not None else {}
    for k in ("zones", "rallied", "breakdowns", "in_window"):
        cen.setdefault(k, 0)
    for t in range(T):
        d = int(B.day[t])
        keep = []
        for z in zones:
            if d - z.day > SESSIONS_BACK:
                continue
            if not z.rallied:
                if B.l[t] < z.L:                                  # taken out before the rally: dropped
                    continue
                z.peak = max(z.peak, float(B.h[t]))
                if z.peak >= z.L + RALLY * abs(z.L):
                    z.rallied = True
                    cen["rallied"] += 1
                keep.append(z)
                continue
            if B.c[t] < z.L - TICK:                               # the breakdown: the zone is used up either way
                cen["breakdowns"] += 1
                s0 = t - NEW_LOW_BARS
                prev_same = s0 >= 0 and all(int(B.day[j]) == d for j in range(s0, t))
                new_low = prev_same and B.l[t] < float(np.min(B.l[s0:t]))
                e = int(B.end[t])
                if new_low and FIRST_BD_END <= e <= LAST_BD_END:
                    cen["in_window"] += 1
                    out.append(dict(d=d, t=t, e=e, L=float(z.L), bd_low=float(B.l[t]), bd_close=float(B.c[t])))
                continue
            keep.append(z)
        zones = keep
        i = t - SWING_K                                           # a swing low at i, confirmed by bar t
        if i >= SWING_K and B.l[i] < float(np.min(B.l[i - SWING_K:i])) and B.l[i] <= float(np.min(B.l[i + 1:t + 1])):
            z = Zone(L=float(B.l[i]), i=i, day=int(B.day[i]), peak=float(np.max(B.h[i + 1:t + 1])))
            cen["zones"] += 1
            if z.peak >= z.L + RALLY * abs(z.L):
                z.rallied = True
                cen["rallied"] += 1
            zones.append(z)
    return out


def entry_of(B, ev: dict, mode: str) -> tuple[int, float] | None:
    """(entry minute row, index stop in this side's space) for one breakdown, or None (no trade).
    V2's stop: the lowest low from the breakdown bar to the bar before entry, less 0.02% of L; a bar whose low reaches
    the stop as it stood before that bar ends the setup."""
    pad = STOP_PCT * abs(ev["L"])
    if mode == "next":
        return ev["e"] + 1, ev["bd_low"] - pad
    low = ev["bd_low"]
    d = ev["d"]
    for u in range(ev["t"] + 1, min(ev["t"] + 1 + RECLAIM_BARS, len(B.day))):
        if int(B.day[u]) != d:
            return None
        if B.l[u] <= low - pad:
            return None
        if B.c[u] > ev["L"]:
            return int(B.end[u]) + 1, low - pad
        low = min(low, float(B.l[u]))
    return None


# ------------------------------------------------------------------------------------------------ factors 3 and 4
def put_did_not_rise(px: np.ndarray | None, e: int) -> bool | None:
    """Factor 3 on one contract's traded closes (NaN = no trade): True / False, or None (no data). Reads rows <= e."""
    if px is None or e - WIN + 1 < 0:
        return None
    before = px[e - WIN + 1:e - 4]
    now = px[e - 4:e + 1]
    if np.isfinite(before).sum() < MIN_BEFORE or not np.isfinite(now).any():
        return None
    return bool(np.nanmax(now) <= np.nanmax(before))


def oi_building(oi: np.ndarray | None, e: int) -> bool | None:
    """Factor 4 on one contract's OI by minute: OI at e (last at or before e) >= 1.02 x OI at e-29 (first at or after
    it, up to e). None when either is missing. Reads rows <= e."""
    if oi is None or e - WIN + 1 < 0:
        return None
    w = oi[e - WIN + 1:e + 1]
    ok = np.flatnonzero(np.isfinite(w) & (w > 0))
    if len(ok) < 2:
        return None
    return bool(w[ok[-1]] >= (1 + OI_UP) * w[ok[0]])


class DayOptionsOI(D3.DayOptions):
    """R123's DayOptions plus each contract's OI by minute (the lake's OI, every minute it has a row)."""

    def __init__(self, g: pd.DataFrame, step: float):
        super().__init__(g, step)
        self.oi: dict = {}
        g = g[(g.strike - g.spot).abs() <= 12 * step].drop_duplicates(["m", "strike", "side"], keep="first")
        for (s, k), h in g.groupby(["side", "strike"], observed=True):
            a = np.full(N, np.nan)
            a[h.m.values] = h.oi.values.astype(float)
            self.oi[(str(s), float(k))] = a


D3.DayOptions = DayOptionsOI          # LakeOptions.day builds this class (this process only)


def factors(opts, g, d: int, ev: dict, sd: int, step: float) -> tuple[bool | None, bool | None]:
    """(factor 3, factor 4) for one breakdown: the opposite option to the one bought (puts for a call buy)."""
    if opts is None:
        return None, None
    e = ev["e"]
    cp = "P" if sd > 0 else "C"
    r = e - WIN                                                   # the close at e-30 (a 09:44 breakdown: the 09:15 open)
    ref = g.c[d][r] if r >= 0 else g.o[d][0]
    if not np.isfinite(ref):
        ref = D3._last(g.c[d][:max(r, 0) + 1])
    f3 = None
    if np.isfinite(ref):
        a = opts.contracts.get((cp, D3.atm(float(ref), step)))
        f3 = put_did_not_rise(None if a is None else a[:, 3], e)
    lvl = abs(ev["L"])                                            # the real support / resistance level
    f4 = oi_building(opts.oi.get((cp, D3.atm(lvl, step))), e)
    return f3, f4


# ------------------------------------------------------------------------------------------------ the option legs
def option_lock_trade(a, r0: int, xr: int, why: str, lot: int, d, und: str):
    """V5: buy at the open of r0; a 60% lock on the premium's traded closes once Rs 1,000 up (the lock set from earlier
    minutes' best); else sold at the last traded close at or before xr. (net, net at 2x slip, exit row, why)."""
    if a is None or not np.isfinite(a[r0, 0]) or a[r0, 0] <= 0:
        return None
    v0 = float(np.nansum(a[max(r0 - 5, 0):r0, 4])) / lot
    buy = float(D3.FILLS.buy(np.array([a[r0, 0]]), np.array([v0]))[0])
    best, lock, out = -np.inf, None, None
    for r in range(r0, xr + 1):
        c = a[r, 3]
        if not np.isfinite(c):
            continue
        if lock is not None and c <= lock:
            out = (r, "lock")
            break
        best = max(best, float(c))
        if (best - buy) * lot >= LOCK_AFTER:
            lock = buy + LOCK_KEEP * (best - buy)
    if out is None:
        fin = np.flatnonzero(np.isfinite(a[r0:xr + 1, 3]))
        if not len(fin):
            return None
        out = (r0 + int(fin[-1]), why)
    rr, w = out
    raw = float(a[rr, 3])
    v = float(np.nansum(a[max(rr - 5, 0):rr, 4])) / lot
    sell = float(D3.FILLS.sell(np.array([raw]), np.array([v]), stop=w in ("stop", "lock"))[0])
    net = (sell - buy) * lot - D3.charge(True, buy, lot, d, und) - D3.charge(False, sell, lot, d, und)
    slip = (buy - float(a[r0, 0]) + raw - sell) * lot
    return net, net - slip, rr, w


def priced(a, r0, xr, why, lot, day, und, lock: bool):
    if lock:
        return option_lock_trade(a, r0, xr, why, lot, day, und)
    tr = R.option_trade(a, r0, xr, why, lot, day, und)
    return None if tr is None else (tr[0], tr[1], xr, why)


# ------------------------------------------------------------------------------------------------ one index
def run_index(args) -> dict:
    und, part = args
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, dd in enumerate(g.days) if (dd in wexp and dd in mexp) or dd.year < first_opt}
    keep = lambda dd: lo <= dd <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part == "holdout" else 0))
    M = R.mirror(g)
    bars, evs, census = {}, {}, {}
    for sd in (1, -1):
        mh, ml, mo, mc = M[sd]
        bars[sd] = R.make_bars(mo, mh, ml, mc, TF)
        census[sd] = {}
        evs[sd] = [ev for ev in breakdowns(bars[sd], census[sd]) if keep(g.days[ev["d"]])]
    # option data and factors, once per breakdown
    days = sorted({ev["d"] for sd in evs for ev in evs[sd]})
    opt_day, src_of = {}, {}
    for d in days:
        day = g.days[d]
        src = Mo if day in wexp else W
        src_of[d] = src.series
        opt_day[d] = None if d in blocked else src.day(day, g.c[d])
    for sd in evs:
        for ev in evs[sd]:
            ev["f3"], ev["f4"] = factors(opt_day[ev["d"]], g, ev["d"], ev, sd, step)
    # claims: a 1:1 race from the breakdown close (calls side)
    races = []
    mh, ml, mo, mc = M[1]
    for ev in evs[1]:
        d, r0 = ev["d"], ev["e"] + 1
        dist = RACE * abs(ev["bd_close"])
        ex = R.exit_on(mh[d], ml[d], mo[d], r0, ev["bd_close"] - dist, ev["bd_close"] + dist, K.K3.EXIT_LAST) \
            if r0 <= K.K3.EXIT_LAST else None
        races.append(dict(f3=ev["f3"], f4=ev["f4"], up=None if ex is None else ex[1] == "target",
                          decided=ex is not None and ex[1] != "time"))
    rows, skips = [], {}
    for v, cfg in VARIANTS.items():
        sk = {k: 0 for k in ("f3_no", "f3_none", "f4_no", "f4_none", "no_entry", "late", "no_contract", "no_price",
                             "no_room", "busy", "both")}
        cands = []
        for sd in cfg["sides"]:
            mh, ml, mo, mc = M[sd]
            for ev in evs[sd]:
                if cfg["f3"] and ev["f3"] is not True:
                    sk["f3_none" if ev["f3"] is None else "f3_no"] += 1
                    continue
                if cfg["f4"] and ev["f4"] is not True:
                    sk["f4_none" if ev["f4"] is None else "f4_no"] += 1
                    continue
                en = entry_of(bars[sd], ev, cfg["entry"])
                if en is None:
                    sk["no_entry"] += 1
                    continue
                r0, stop = en
                if r0 > LAST_ENTRY:
                    sk["late"] += 1
                    continue
                d = ev["d"]
                if d in blocked or opt_day[d] is None:
                    sk["no_contract"] += 1
                    continue
                ref = mo[d][r0]
                if not np.isfinite(ref):
                    sk["no_price"] += 1
                    continue
                if ref <= stop:
                    sk["no_room"] += 1
                    continue
                tgt = ref + 2 * (ref - stop) if cfg["exit"] == "2R" else np.inf
                ex = R.exit_on(mh[d], ml[d], mo[d], r0, stop, tgt, K.K3.EXIT_LAST)
                if ex is None:
                    sk["no_price"] += 1
                    continue
                cands.append(dict(d=d, sd=sd, r0=r0, stop=stop, ref=ref, tgt=tgt, ex=ex, L=ev["L"]))
        cands.sort(key=lambda c: (c["d"], c["r0"]))
        busy = (-1, -1)
        i = 0
        while i < len(cands):
            c = cands[i]
            same = [x for x in cands[i:] if x["d"] == c["d"] and x["r0"] == c["r0"]]
            if len({x["sd"] for x in same}) > 1:
                sk["both"] += len(same)
                i += len(same)
                continue
            i += 1
            if c["d"] == busy[0] and c["r0"] <= busy[1]:
                sk["busy"] += 1
                continue
            d, sd, r0 = c["d"], c["sd"], c["r0"]
            day = g.days[d]
            xr, why, xpx = c["ex"]
            cp = "C" if sd > 0 else "P"
            ref_c = D3._last(g.c[d][:r0])
            if not np.isfinite(ref_c):
                sk["no_price"] += 1
                continue
            a = opt_day[d].contracts.get((cp, D3.atm(ref_c, step)))
            tr = priced(a, r0, xr, why, lot, day, und, cfg["exit"] == "lock")
            if tr is None:
                sk["no_price"] += 1
                continue
            busy = (d, xr)
            mh, ml, mo, mc = M[sd]
            stop_pts = c["ref"] - c["stop"]
            tgt_pts = c["tgt"] - c["ref"]
            tw = []
            for _ in range(TWINS):
                r1 = int(rng.integers(FIRST_TWIN, LAST_ENTRY + 1))
                ref1, c1 = mo[d][r1], D3._last(g.c[d][:r1])
                if not (np.isfinite(ref1) and np.isfinite(c1)):
                    tw.append(np.nan)
                    continue
                e1 = R.exit_on(mh[d], ml[d], mo[d], r1, ref1 - stop_pts, ref1 + tgt_pts, K.K3.EXIT_LAST)
                if e1 is None:
                    tw.append(np.nan)
                    continue
                t1 = priced(opt_day[d].contracts.get((cp, D3.atm(c1, step))), r1, e1[0], e1[1], lot, day, und,
                            cfg["exit"] == "lock")
                tw.append(np.nan if t1 is None else t1[0])
            pts = float(xpx - c["ref"])
            rows.append(dict(variant=v, und=und, d=d, day=day, side=sd, entry=r0, exit=int(tr[2]), why=tr[3],
                             ref=float(c["ref"] * sd), stop_pts=float(stop_pts), pts=pts, R=pts / stop_pts,
                             series=src_of[d], net=float(tr[0]), netx2=float(tr[1]), twins=tw))
        skips[v] = sk
    print(f"R145 {part} {und}: " + ", ".join(f"{v} {sum(r['variant'] == v for r in rows)}" for v in VARIANTS),
          flush=True)
    return dict(rows=rows, skips=skips, census={str(k): v for k, v in census.items()}, races=races,
                sessions=sorted({dd for dd in g.days if keep(dd)}),
                factors=dict(f3=_tally([ev["f3"] for ev in evs[1]]), f4=_tally([ev["f4"] for ev in evs[1]])))


def _tally(xs) -> dict:
    return {"yes": sum(x is True for x in xs), "no": sum(x is False for x in xs), "none": sum(x is None for x in xs)}


# ------------------------------------------------------------------------------------------------ claims and gates
def two_prop(a_up: int, a_n: int, b_up: int, b_n: int) -> dict:
    from math import erf, sqrt
    if not (a_n and b_n):
        return dict(n1=a_n, n2=b_n)
    p1, p2 = a_up / a_n, b_up / b_n
    pp = (a_up + b_up) / (a_n + b_n)
    se = sqrt(pp * (1 - pp) * (1 / a_n + 1 / b_n)) or 1e-12
    z = (p1 - p2) / se
    return dict(n1=a_n, up1=p1, n2=b_n, up2=p2, z=z, p_two_sided=float(2 * (1 - 0.5 * (1 + erf(abs(z) / sqrt(2))))))


def claims(races: list[dict]) -> dict:
    """C1, C2, C4 on decided races (target or stop before 15:20)."""
    dec = [r for r in races if r["decided"]]

    def split(f):
        a = [r for r in dec if f(r) is True]
        b = [r for r in dec if f(r) is False]
        return two_prop(sum(r["up"] for r in a), len(a), sum(r["up"] for r in b), len(b))
    both = lambda r: True if (r["f3"] is True and r["f4"] is True) else (  # noqa: E731
        False if (r["f3"] is False and r["f4"] is False) else None)
    return dict(n_races=len(races), n_decided=len(dec), up_all=(sum(r["up"] for r in dec) / len(dec)) if dec else None,
                C1_f3=split(lambda r: r["f3"]), C2_f4=split(lambda r: r["f4"]), C4_both_vs_neither=split(both))


def account(T: pd.DataFrame) -> dict:
    """Max drawdown and the worst calendar month, 1 lot, trades in time order."""
    if not len(T):
        return dict(max_dd=0.0, worst_month=0.0)
    T = T.sort_values(["day", "entry", "und"])
    eq = np.cumsum(T.net.values)
    dd = float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0))
    mo = T.assign(mo=[f"{d.year}-{d.month:02d}" for d in T.day]).groupby("mo").net.sum()
    return dict(max_dd=dd, worst_month=float(mo.min()))


def run(part: str):
    from multiprocessing import Pool
    if part == "holdout":
        if not (RESULTS / "design.json").exists():
            raise R.HoldoutError("refused: commit the design results first")
        R.holdout_lock("start", out=OUT, results=RESULTS)
    with Pool(int(os.environ.get("R145_PROCS", "3"))) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    T = pd.DataFrame([r for x in res for r in x["rows"]])
    out = dict(part=part, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)],
               sessions=len({dd for x in res for dd in x["sessions"]}), variants={},
               census={u: x["census"] for u, x in zip(K.UNDS, res)},
               factors={u: x["factors"] for u, x in zip(K.UNDS, res)},
               claims=claims([r for x in res for r in x["races"]]))
    for v in VARIANTS:
        Tv = T[T.variant == v] if len(T) else T
        s = R.stats(Tv, part == "design") if len(Tv) else dict(n=0)
        s["label"] = VARIANTS[v]["label"]
        s["skips"] = {k: sum(x["skips"][v][k] for x in res) for k in res[0]["skips"][v]}
        s["c3_share_10k"] = float((Tv.net >= 10_000).mean()) if len(Tv) else None
        out["variants"][v] = s
    if part == "design":
        qs = R3.bh([out["variants"][v].get("p_twins", 1.0) for v in VARIANTS])
        for v, q in zip(VARIANTS, qs):
            s = out["variants"][v]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["design_gates"] = dict(n=s.get("n", 0) >= GATES["design_n"],
                                     net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                                     folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"])
            s["passes_design"] = all(s["design_gates"].values())
    else:
        D = json.loads((RESULTS / "design.json").read_text())
        Td = pd.read_parquet(OUT / "trades_design.parquet") if (OUT / "trades_design.parquet").exists() else None
        for v in VARIANTS:
            s = out["variants"][v]
            both = pd.concat([x for x in (Td[Td.variant == v] if Td is not None else None, T[T.variant == v]
                                          if len(T) else None) if x is not None and len(x)]) \
                if (Td is not None and len(Td)) or len(T) else pd.DataFrame()
            acc = account(both) if len(both) else dict(max_dd=0.0, worst_month=0.0)
            s["account_design_plus_holdout"] = acc
            s["holdout_gates"] = dict(n=s.get("n", 0) >= GATES["holdout_n"], net_pos=s.get("avg_net", -1) > 0,
                                      win=s.get("win", 0) >= GATES["win"], dd=acc["max_dd"] > GATES["dd"],
                                      month=acc["worst_month"] > GATES["month"])
            s["passes_all"] = bool(D["variants"][v].get("passes_design")) and all(s["holdout_gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins"]).to_parquet(OUT / f"trades_{part}.parquet")
    if part == "holdout":
        R.holdout_lock("done", out=OUT, results=RESULTS)
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 3), s.get("p_twins"))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
