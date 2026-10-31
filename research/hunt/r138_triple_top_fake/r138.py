"""R138: the triple-top fake breakout (an M, then a third poke above it that closes back below) and its W mirror
(PREREG.md). Descriptive first (what the index does next), then 8 tradable variants buying the ATM option.

    python research/hunt/r138_triple_top_fake/r138.py design    # -> results/design.json
    python research/hunt/r138_triple_top_fake/r138.py holdout   # the ONE holdout read -> results/holdout.json

Signal source: the index spot's 15-minute candles (wicks for every level, closes only for "closed back below").
The option is only what is bought. Reuses R131 (bars, mirrored sides, option rule, costs, wick exits, twins).

Coordinates: everything is written for a triple BOTTOM in one side's coordinates (R131's Side). On the real side (+1)
that is the W (buy the call); on the mirrored side (-1, high -> -low) it is the M, Boss's triple top (buy the put).
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
sys.path.insert(0, str(HERE.parent / "r131_ict_ten"))
import r131 as R  # noqa: E402

K, D3 = R.K, R.D3
N = R.N
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R138_OUT", K.K3.CACHE_ROOT / "r138")).expanduser()
TICK = 0.05
NIFTY_TOL = 5.0                  # Boss: "till 5 points is ok"
TOL_PCT = 5.0 / 22500.0          # the same on the other indices: 0.022% of price
MIN_GAP = 3                      # B - A >= 3: at least 2 candles between the touches
PULL_ATR = 1.0                   # the neckline is >= 1.0 x ATR14 beyond the level
TF = 15
HORIZONS = (15, 30, 60)
CTRL_N = 20
BOOT = 2000
TWINS = 100
SEED = 138
SIDE_NAME = {-1: "M", 1: "W"}

CONFIGS = {f"{lv}-{pk}-{fl}-{ss}": dict(level=lv, poke=pk, fail=fl, span=(ss == "span"))
           for lv in ("max", "avg") for pk in ("tick", "tol") for fl in ("f1", "f2") for ss in ("same", "span")}
PRIMARY = "max-tick-f1-same"
VARIANTS = {
    "T1": dict(side=-1, cfg=PRIMARY, target="neck", label="M primary, neckline target"),
    "T2": dict(side=-1, cfg=PRIMARY, target="2R", label="M primary, 2R target"),
    "T3": dict(side=-1, cfg="max-tick-f2-same", target="neck", label="M two-candle fail, neckline"),
    "T4": dict(side=-1, cfg="max-tick-f1-span", target="neck", label="M touches may span the previous session"),
    "T5": dict(side=-1, cfg="max-tol-f1-same", target="neck", label="M poke by at least the tolerance"),
    "T6": dict(side=-1, cfg="avg-tick-f1-same", target="neck", label="M average level"),
    "T7": dict(side=1, cfg=PRIMARY, target="neck", label="W mirror, neckline"),
    "T8": dict(side=1, cfg=PRIMARY, target="2R", label="W mirror, 2R"),
}
GATES = dict(n=300, folds=3, q=0.10, dd=-40000.0, month=-13000.0, holdout_n=50)


def tolerance(und: str, price: float) -> float:
    return NIFTY_TOL if und == "NIFTY" else TOL_PCT * abs(price)


def band(und: str, price: float, atr_b: float, cfg: dict) -> float:
    """The two touches' band: R138's fixed 5 NIFTY points / 0.022% (default), or Amendment A's none / k x ATR14."""
    mode = cfg.get("band", "fixed")
    if mode == "none":
        return np.inf
    if mode == "atr":
        return cfg["k"] * atr_b
    return tolerance(und, price)


def band_max(price: float, atr_b: float, cfg: dict) -> float:
    """An upper bound of band() for any first touch near price (for the early stop of the search)."""
    mode = cfg.get("band", "fixed")
    if mode == "none":
        return np.inf
    if mode == "atr":
        return cfg["k"] * atr_b
    return TOL_PCT * abs(price) + NIFTY_TOL


# ------------------------------------------------------------------------------------------------ the pattern
@dataclass
class Event:
    a: int            # first touch (bar index)
    b: int            # second touch
    c: int            # the poke bar
    e: int            # the event bar (c, or c + 1 for the two-candle fail)
    level: float      # this side's level (the support in side coordinates)
    neck: float       # the neckline (side coordinates; above the level)
    fake: float       # the fake extreme (side coordinates; below the level)
    tol: float


def detect(ch: R.Chart, und: str, cfg: dict) -> list:
    """Triple-bottom fake breakdowns in this side's coordinates (PREREG section 1, mirrored). Known at bar e's close."""
    l, h, c, day, atr = ch.l.tolist(), ch.h.tolist(), ch.c.tolist(), ch.day.tolist(), ch.atr
    T = ch.T
    if T == 0:
        return []
    first = {}
    for t in range(T):
        first.setdefault(int(day[t]), t)
    best = {}                                  # c -> (b, a, level, neck, tol)
    for b in range(T):
        db = int(day[b])
        if not np.isfinite(atr[b]):
            continue
        lo_a = first[db - 1] if cfg["span"] and db - 1 in first else first[db]
        run_min, run_max = np.inf, -np.inf     # lows / highs strictly between a and b
        for a in range(b - 1, lo_a - 1, -1):
            if a <= b - MIN_GAP:
                tol = band(und, l[a], atr[b], cfg)
                lmin = min(l[a], l[b])
                if (abs(l[a] - l[b]) <= tol and run_min >= lmin and run_max - lmin >= PULL_ATR * atr[b]):
                    lvl = lmin if cfg["level"] == "max" else (l[a] + l[b]) / 2
                    poke = TICK if cfg["poke"] == "tick" else tol
                    last_day = db + 1 if cfg["span"] else db
                    t = b + 1
                    while t < T and int(day[t]) <= last_day and not l[t] <= lvl - poke:
                        t += 1
                    if t < T and int(day[t]) <= last_day and t >= b + 2:
                        dc = int(day[t])
                        if int(day[a]) >= dc - (1 if cfg["span"] else 0):
                            if t not in best or (b, a) > best[t][:2]:
                                best[t] = (b, a, lvl, float(run_max), tol)
            run_min = min(run_min, l[a])
            run_max = max(run_max, h[a])
            if run_min < l[b] - band_max(l[b], atr[b], cfg):          # no earlier a can hold the level any more
                break
    out = []
    for t in sorted(best):
        b, a, lvl, neck, tol = best[t]
        if cfg["fail"] == "f1":
            if c[t] > lvl:
                out.append(Event(a, b, t, t, lvl, neck, float(l[t]), tol))
        else:
            if c[t] <= lvl and t + 1 < T and day[t + 1] == day[t] and c[t + 1] > lvl:
                out.append(Event(a, b, t, t + 1, lvl, neck, float(min(l[t], l[t + 1])), tol))
    return out


def plain_doubles(ch: R.Chart, und: str) -> list:
    """C2: every double bottom (primary rules, same session), at the close of the candle after b if it did not poke
    below the level. One per b (its latest a). Returned as Events with c = e = b + 1 and fake = the level, so the race
    is the neckline vs a poke of one tick beyond the level."""
    l, h, day, atr = ch.l.tolist(), ch.h.tolist(), ch.day.tolist(), ch.atr
    out = []
    first = {}
    for t in range(ch.T):
        first.setdefault(int(day[t]), t)
    for b in range(ch.T - 1):
        if not np.isfinite(atr[b]) or day[b + 1] != day[b]:
            continue
        run_min, run_max = np.inf, -np.inf
        for a in range(b - 1, first[int(day[b])] - 1, -1):
            if a <= b - MIN_GAP:
                tol = tolerance(und, l[a])
                lmin = min(l[a], l[b])
                if abs(l[a] - l[b]) <= tol and run_min >= lmin and run_max - lmin >= PULL_ATR * atr[b]:
                    if l[b + 1] > lmin - TICK:
                        out.append(Event(a, b, b + 1, b + 1, lmin, float(run_max), lmin, tol))
                    break
            run_min = min(run_min, l[a])
            run_max = max(run_max, h[a])
    return out


# ------------------------------------------------------------------------------------------------ what happens next
def _close_at(mc_d: np.ndarray, r: int) -> float:
    """The last finite minute close at or before row r (NaN if none or r past the session)."""
    if r >= N:
        return np.nan
    seg = mc_d[:r + 1]
    fin = np.flatnonzero(np.isfinite(seg))
    return float(seg[fin[-1]]) if len(fin) else np.nan


def measure(S: R.Side, d: int, e_row: int, neck: float, brk: float) -> dict | None:
    """From the close of minute e_row on session d (this side's coordinates): moves at 15/30/60 minutes and to 15:20,
    and the race neckline (high >= neck) vs break (low <= brk - tick), both in one minute = break."""
    c0 = _close_at(S.mc[d], e_row)
    if not np.isfinite(c0) or e_row >= R.EXIT_LAST:
        return None
    out = dict(c0=c0)
    for hz in HORIZONS:
        r = e_row + hz
        out[f"m{hz}"] = (_close_at(S.mc[d], r) - c0) if r <= N - 1 else np.nan
    out["m_eod"] = _close_at(S.mc[d], R.EXIT_LAST) - c0
    hs, ls = S.mh[d][e_row + 1:R.EXIT_LAST + 1], S.ml[d][e_row + 1:R.EXIT_LAST + 1]
    ok = np.isfinite(hs) & np.isfinite(ls)
    nh = np.flatnonzero(ok & (hs >= neck))
    bh = np.flatnonzero(ok & (ls <= brk - TICK))
    n0 = nh[0] if len(nh) else 10 ** 9
    b0 = bh[0] if len(bh) else 10 ** 9
    out["race"] = "neither" if n0 == b0 == 10 ** 9 else ("break" if b0 <= n0 else "neck")
    return out


def describe_index(und: str, g, sides: dict, keep_idx: list, rng) -> dict:
    """All events and controls of one index as rows (dicts) per (config, side) and per side for C2."""
    rows, ctrl, plain = [], [], []
    keep_set = set(keep_idx)
    for sd in (-1, 1):
        S = sides[sd]
        ch = S.chart(TF)

        def one(ev: Event, cfg_name: str, kind: str):
            d = int(ch.day[ev.e])
            if d not in keep_set:
                return
            e_row = int(ch.B.end[ev.e])
            m = measure(S, d, e_row, ev.neck, ev.fake)
            if m is None:
                return
            base = dict(und=und, side=sd, cfg=cfg_name, d=d, day=g.days[d], row=e_row, tol=ev.tol,
                        neck_pts=ev.neck - m["c0"], fake_pts=m["c0"] - ev.fake)
            if kind == "plain":
                plain.append({**base, **m})
                return
            rows.append({**base, **m})
            others = [x for x in keep_idx if x != d]
            for d2 in rng.choice(others, size=min(CTRL_N, len(others)), replace=False):
                c2 = _close_at(S.mc[d2], e_row)
                if not np.isfinite(c2):
                    continue
                k = abs(c2 / m["c0"])
                m2 = measure(S, int(d2), e_row, c2 + base["neck_pts"] * k, c2 - base["fake_pts"] * k)
                if m2 is not None:
                    ctrl.append({**base, "d2": int(d2), **{f"c_{x}": m2[x] for x in
                                                           ("m15", "m30", "m60", "m_eod", "race", "c0")}})

        for name, cfg in CONFIGS.items():
            for ev in detect(ch, und, cfg):
                one(ev, name, "event")
        for ev in plain_doubles(ch, und):
            one(ev, "plain", "plain")
    return dict(rows=rows, ctrl=ctrl, plain=plain)


# ------------------------------------------------------------------------------------------------ trades
@dataclass
class TCand:
    d: int
    r0: int
    stop: float
    neck: float


def trade_cands(sides: dict, und: str, var: dict) -> list:
    sd = var["side"]
    ch = sides[sd].chart(TF)
    out = []
    for ev in detect(ch, und, CONFIGS[var["cfg"]]):
        out.append((sd, TCand(int(ch.day[ev.e]), int(ch.B.end[ev.e]) + 1, R.stop_below(ev.fake), ev.neck)))
    return out


def sequence(sides: dict, cands: list, target: str, blocked: set, days, keep) -> list:
    """One trade at a time per index (R131's sequence with this study's neckline target)."""
    trades, busy = [], -1
    for sd, c in sorted(cands, key=lambda x: (x[1].d, x[1].r0)):
        if c.d * N + c.r0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
            continue
        S = sides[sd]
        mh, ml, mo = S.mh[c.d], S.ml[c.d], S.mo[c.d]
        ref = mo[c.r0]
        if not np.isfinite(ref) or ref <= c.stop:
            continue
        tgt = ref + 2 * (ref - c.stop) if target == "2R" else c.neck
        if tgt <= ref:
            continue
        ex = R.exit_on(mh, ml, mo, c.r0, c.stop, tgt, R.EXIT_LAST)
        if ex is None:
            continue
        xr, why, xpx = ex
        busy = c.d * N + xr
        if keep(days[c.d]):
            trades.append(dict(d=c.d, day=days[c.d], side=sd, entry=c.r0, exit=xr, why=why, stop_pts=float(ref - c.stop),
                               tgt_pts=float(tgt - ref), pts=float(xpx - ref), R=float((xpx - ref) / (ref - c.stop))))
    return trades


def run_index(args) -> dict:
    und, part = args
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
    desc = describe_index(und, g, sides, keep_idx, np.random.default_rng(SEED + off))
    rng = np.random.default_rng(SEED + 1000 + off)
    cache = {}

    def src_for(day, closes):
        if day not in cache:
            cache.clear()
            cache[day] = (Mo if day in wexp else W).day(day, closes)
        return cache[day]

    trades = {}
    for v, var in VARIANTS.items():
        rows = []
        for t in sequence(sides, trade_cands(sides, und, var), var["target"], blocked, g.days, keep):
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
            rows.append(dict(t, und=und, net=tr[0], netx2=tr[1], twins=tw))
        trades[v] = rows
    print(f"R138 {part} {und}: events {len(desc['rows'])}, " + ", ".join(f"{k} {len(x)}" for k, x in trades.items()),
          flush=True)
    return dict(desc=desc, trades=trades)


# ------------------------------------------------------------------------------------------------ statistics
def boot_p(x: np.ndarray, cl: np.ndarray, rng, y: np.ndarray | None = None, cly: np.ndarray | None = None) -> float:
    """Two-sided session-clustered bootstrap p that mean(x) (paired differences), or mean(x) - mean(y), is 0."""
    def groups(v, c):
        df = pd.DataFrame(dict(v=v, c=c)).dropna()
        gb = df.groupby("c").v
        return gb.sum().values, gb.count().values
    sx, nx = groups(x, cl)
    if len(sx) < 2:
        return 1.0
    ix = rng.integers(0, len(sx), size=(BOOT, len(sx)))
    bx = sx[ix].sum(1) / np.maximum(nx[ix].sum(1), 1)
    if y is None:
        b = bx
    else:
        sy, ny = groups(y, cly)
        if len(sy) < 2:
            return 1.0
        iy = rng.integers(0, len(sy), size=(BOOT, len(sy)))
        b = bx - sy[iy].sum(1) / np.maximum(ny[iy].sum(1), 1)
    p = 2 * min((b <= 0).mean(), (b >= 0).mean())
    return float(min(1.0, max(p, 1.0 / BOOT)))


def summ(E: pd.DataFrame) -> dict:
    """Moves (points and %) and race shares of a set of event rows."""
    if not len(E):
        return dict(n=0)
    s = dict(n=int(len(E)), sessions=int(E.day.nunique()))
    for k in ("m15", "m30", "m60", "m_eod"):
        x = E[k].dropna()
        pc = (x / E.loc[x.index, "c0"].abs() * 100)
        s[k] = dict(n=int(len(x)), mean_pts=float(x.mean()) if len(x) else None,
                    median_pts=float(x.median()) if len(x) else None, mean_pct=float(pc.mean()) if len(x) else None,
                    median_pct=float(pc.median()) if len(x) else None,
                    with_pattern=float((x > 0).mean()) if len(x) else None,
                    against=float((x < 0).mean()) if len(x) else None)
    for r in ("neck", "break", "neither"):
        s[f"race_{r}"] = float((E.race == r).mean())
    s["neck_pts_median"] = float(E.neck_pts.median())
    s["fake_pts_median"] = float(E.fake_pts.median())
    return s


def ctrl_summ(C: pd.DataFrame) -> dict:
    if not len(C):
        return dict(n=0)
    s = dict(n=int(len(C)))
    for k in ("m15", "m30", "m60", "m_eod"):
        x = C[f"c_{k}"].dropna()
        s[k] = dict(mean_pts=float(x.mean()), with_pattern=float((x > 0).mean()),
                    mean_pct=float((x / C.loc[x.index, "c_c0"].abs() * 100).mean()))
    for r in ("neck", "break", "neither"):
        s[f"race_{r}"] = float((C.c_race == r).mean())
    return s


def paired(E: pd.DataFrame, C: pd.DataFrame, col: str, rng) -> float:
    """Paired test vs C1: per event, its value minus its controls' mean (moves in % of price; race as 0/1)."""
    key = ["und", "side", "cfg", "d", "row"]
    if col == "race":
        ev = (E.race == "neck").astype(float)
        cv = (C.c_race == "neck").astype(float)
    else:
        ev = E[col] / E.c0.abs() * 100
        cv = C[f"c_{col}"] / C.c_c0.abs() * 100
    cm = C.assign(v=cv).groupby(key).v.mean()
    x = E.assign(v=ev).set_index(key).v - cm.reindex(E.set_index(key).index).values
    return boot_p(x.values, E.day.astype(str).values, rng)


def unpaired(E: pd.DataFrame, P: pd.DataFrame, col: str, rng) -> float:
    f = (lambda X: (X.race == "neck").astype(float).values) if col == "race" else (
        lambda X: (X[col] / X.c0.abs() * 100).values)
    return boot_p(f(E), E.day.astype(str).values, rng, f(P), P.day.astype(str).values)


def describe(D: pd.DataFrame, C: pd.DataFrame, P: pd.DataFrame, rng) -> dict:
    out = dict(configs={}, plain={}, tests=[])
    for name in CONFIGS:
        for sd in (-1, 1):
            E = D[(D.cfg == name) & (D.side == sd)]
            Cc = C[(C.cfg == name) & (C.side == sd)]
            s = summ(E)
            s["control_C1"] = ctrl_summ(Cc)
            if len(E) >= 2:
                for col, tag in (("m60", "a_m60_vs_C1"), ("m_eod", "b_eod_vs_C1"), ("race", "c_neck_vs_C1")):
                    p = paired(E, Cc, col, rng)
                    s[f"p_{tag}"] = p
                    out["tests"].append(dict(cfg=name, side=SIDE_NAME[sd], test=tag, n=int(len(E)), p=p))
            if name == PRIMARY:
                Ps = P[P.side == sd]
                if len(E) >= 2 and len(Ps) >= 2:
                    for col, tag in (("m60", "d_m60_vs_C2"), ("race", "e_neck_vs_C2")):
                        p = unpaired(E, Ps, col, rng)
                        s[f"p_{tag}"] = p
                        out["tests"].append(dict(cfg=name, side=SIDE_NAME[sd], test=tag, n=int(len(E)), p=p))
                s["by_und"] = {u: summ(X) for u, X in E.groupby("und")}
                s["by_year"] = {int(y): summ(X) for y, X in E.groupby([d.year for d in E.day])}
                hour = (E.row + 1 + 15 + 9 * 60) // 60         # the clock hour of the event's close
                s["by_hour"] = {int(hh): summ(X) for hh, X in E.groupby(hour.values)}
                s["tolerance_pts_median_by_und"] = {u: float(X.tol.median()) for u, X in E.groupby("und")}
                out["plain"][SIDE_NAME[sd]] = summ(Ps)
            out["configs"][f"{SIDE_NAME[sd]}:{name}"] = s
    if out["tests"]:
        qs = R.R3.bh([t["p"] for t in out["tests"]])
        for t, q in zip(out["tests"], qs):
            t["q"] = float(q)
    return out


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
    s["win_vs_65"] = bool(s["win"] >= 0.65)
    return s


def lock(status: str):
    p = OUT / "HOLDOUT_READ.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout.json").exists() or p.exists():
            raise R.HoldoutError("refused: the R138 holdout was already read")
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
    with Pool(int(os.environ.get("R138_PROCS", "5"))) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    D = pd.DataFrame([r for x in res for r in x["desc"]["rows"]])
    C = pd.DataFrame([r for x in res for r in x["desc"]["ctrl"]])
    P = pd.DataFrame([r for x in res for r in x["desc"]["plain"]])
    rng = np.random.default_rng(SEED + 7 + (100 if part != "design" else 0))
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        out = dict(part=part, describe=describe(D, C, P, rng), variants={})
    for v, var in VARIANTS.items():
        T = pd.DataFrame([r for x in res for r in x["trades"][v]])
        if not len(T):
            out["variants"][v] = dict(n=0, p=1.0, label=var["label"])
            continue
        s = stats(T, part == "design")
        s["p"], s["twin_mean_total"], _ = R.twin_p(T, SEED)
        s["label"] = var["label"]
        out["variants"][v] = s
        OUT.mkdir(parents=True, exist_ok=True)
        T.drop(columns=["twins"]).to_parquet(OUT / f"trades_{v}_{part}.parquet")
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
    OUT.mkdir(parents=True, exist_ok=True)
    D.to_parquet(OUT / f"events_{part}.parquet")
    if part == "holdout":
        lock("done")
    pm = out["describe"]["configs"].get(f"M:{PRIMARY}", {})
    print("M primary:", pm.get("n"), pm.get("m60"), {k: pm.get(f"race_{k}") for k in ("neck", "break", "neither")})
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s.get("p", 1), 3))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
