"""R130 Amendment 2 (PREREG_ENH.md): the improvement search on the wick-only setup W1.

    python research/hunt/r130_sweep_mss_fvg/r130enh.py design    # 96 versions on design -> results/design_enh.json
    python research/hunt/r130_sweep_mss_fvg/r130enh.py seen      # the 2 locked versions on Apr-Oct 2026 ("already
                                                                 # seen") -> results/seen_enh.json

Factors: L liquidity (3-bar swing / major only), M time (all day / morning), T 15-minute trend, S minimum stop, K strike
(ATM / 1-ITM), P profit lock on the option premium (none / +Rs 1,000 keep 60% / +Rs 500 keep 60%). Signals on the
index, wicks only; the option is what is bought.
"""
from __future__ import annotations

import itertools
import json
import sys
from math import erf, sqrt
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r130 as R  # noqa: E402

K, D3 = R.K, R.D3
N, TICK = R.N, R.TICK
BASE = R.WICK_VARIANTS["W1"]
MORNING_LAST = K.row(11, 0)
MIN_STOP = 0.001
LOCKS = (None, (1000.0, 0.60), (500.0, 0.60))
TREND_K = 2
FAMILY_SIZE = 106                       # these 96 + R130's V1-V6 and W1-W4
TWINS = R.TWINS
SEED = 1302
SCANS = list(itertools.product((0, 1), (0, 1), (0, 1), (0, 1)))     # L, M, T, S
VERSIONS = [(L, M, T, S, k, p) for (L, M, T, S) in SCANS for k in (0, 1) for p in range(3)]


def vname(v) -> str:
    return "E-L{}M{}T{}S{}K{}P{}".format(*v)


def describe(v) -> str:
    L, M, T, S, k, p = v
    parts = ["major liquidity" if L else "3-bar swings", "morning" if M else "all day",
             "15-min trend" if T else "no trend filter", "stop >= 0.10%" if S else "any stop",
             "1-ITM" if k else "ATM", ["no lock", "lock +Rs 1,000 keep 60%", "lock +Rs 500 keep 60%"][p]]
    return ", ".join(parts)


# ------------------------------------------------------------------------------------------------ L1: major liquidity
class MajorSide(R.Side):
    """The sweep starts only at major liquidity: yesterday's low, the opening-range low (09:15-09:29, usable from the
    09:30 bar) or equal lows (two untaken 3-bar swing lows within 0.03%: the lower of the pair). Wicks only."""

    def __post_init__(self):
        super().__post_init__()
        B = self.B
        self.first = {}
        for t, d in enumerate(B.day):
            self.first.setdefault(int(d), t)
        days = sorted(self.first)
        self.prev_low = {}
        for a, b in zip(days, days[1:]):
            self.prev_low[b] = float(self.ll[self.first[a]:self.first[b]].min())

    def majors(self, t: int, d: int) -> list:
        B, f = self.B, self.first[d]
        out = []
        if d in self.prev_low:
            pl = self.prev_low[d]
            if not (self.ll[f:t] <= pl - TICK).any():                  # not taken earlier today
                out.append(pl)
        orb = [i for i in range(f, t) if B.end[i] <= 14]
        if orb and B.start[t] >= 15:
            orl = float(self.ll[orb].min())
            after = [i for i in range(f, t) if B.start[i] >= 15]
            if not (len(after) and (self.ll[after] <= orl - TICK).any()):
                out.append(orl)
        lv = sorted(x[0] for x in self.lows)
        for a, b in zip(lv, lv[1:]):
            if b - a <= R.EQ_TOL * abs(a):
                out.append(a)
        return out

    def _sweep(self, t: int, d: int):
        if not self.highs:
            return
        H = self.highs[-1][0]
        for L in self.majors(t, int(d)):
            if H > L and self.ll[t] <= L - TICK:
                self.census["sweeps"] += 1
                self.pend = R.Setup("mss", int(d), t, H)
                return


# ------------------------------------------------------------------------------------------------ T1: 15-minute trend
def trend_grid(g, tf: int = 15) -> np.ndarray:
    """days x N: the 15-minute structure's direction known at each minute row (from 15-minute bars that ENDED at or
    before it): +1 after a wick 1 tick above the latest confirmed 2-bar swing high, -1 after one below the latest swing
    low, 0 before any break or when one bar breaks both. Carried across sessions."""
    B = R.make_bars(g.o, g.h, g.l, g.c, tf)
    hi_av, lo_av = {}, {}
    for i in R.swing_highs(B.h, TREND_K):
        hi_av.setdefault(int(i) + TREND_K + 1, []).append(int(i))
    for i in R.swing_highs(-B.l, TREND_K):
        lo_av.setdefault(int(i) + TREND_K + 1, []).append(int(i))
    out = np.zeros((len(g.days), N), dtype=np.int8)
    sh = sl = None
    cur = 0
    last_d, last_r = 0, 0
    for t in range(len(B.day)):
        for i in hi_av.get(t, ()):
            sh = float(B.h[i])
        for i in lo_av.get(t, ()):
            sl = float(B.l[i])
        up = sh is not None and B.h[t] >= sh + TICK
        dn = sl is not None and B.l[t] <= sl - TICK
        d, r = int(B.day[t]), int(B.end[t]) + 1          # known from the minute after the bar ends
        # fill the old direction up to here
        _fill(out, last_d, last_r, d, r, cur)
        cur = 0 if (up and dn) else (1 if up else (-1 if dn else cur))
        last_d, last_r = d, r
    _fill(out, last_d, last_r, len(g.days) - 1, N, cur)
    return out


def _fill(out, d0, r0, d1, r1, v):
    if d0 == d1:
        out[d0, r0:r1] = v
        return
    out[d0, r0:] = v
    out[d0 + 1:d1, :] = v
    out[d1, :r1] = v


# ------------------------------------------------------------------------------------------------ the option leg
def option_locks(a, r0: int, xr: int, why: str, lot: int, d, und: str):
    """[(net, net at 2x slippage) for each lock in LOCKS] or None. Bought at r0's open; the plain exit is the last
    traded close at or before xr; a lock exits earlier (see lock_exit)."""
    if a is None or not np.isfinite(a[r0, 0]) or a[r0, 0] <= 0:
        return None
    fin = np.flatnonzero(np.isfinite(a[r0:xr + 1, 3]))
    if not len(fin):
        return None
    v0 = float(np.nansum(a[max(r0 - 5, 0):r0, 4])) / lot
    buy = float(D3.FILLS.buy(np.array([a[r0, 0]]), np.array([v0]))[0])
    out = []
    for lk in LOCKS:
        ex = lock_exit(a, r0, xr, buy, lot, lk) if lk else None
        if ex is None:
            rr = r0 + fin[-1]
            raw, stop = float(a[rr, 3]), why == "stop"
        else:
            rr, raw = ex
            stop = True
        v = float(np.nansum(a[max(rr - 5, 0):rr, 4])) / lot
        sell = float(D3.FILLS.sell(np.array([raw]), np.array([v]), stop=stop)[0])
        net = (sell - buy) * lot - D3.charge(True, buy, lot, d, und) - D3.charge(False, sell, lot, d, und)
        slip = (buy - float(a[r0, 0]) + raw - sell) * lot
        out.append((net, net - slip))
    return out


def lock_exit(a, r0: int, xr: int, buy: float, lot: int, lk):
    """The profit lock on the premium: once the best open profit of EARLIER minutes (their highs) reaches the trigger,
    a minute whose low reaches buy + keep x best / lot exits there (or at the open if it gapped below).
    Returns (minute, raw price) or None."""
    trig, keep = lk
    hi = a[r0:xr + 1, 1]
    lo = a[r0:xr + 1, 2]
    op = a[r0:xr + 1, 0]
    prof = np.where(np.isfinite(hi), (hi - buy) * lot, -np.inf)
    best = np.maximum.accumulate(np.r_[-np.inf, prof[:-1]])
    px = buy + keep * best / lot
    hit = np.flatnonzero((best >= trig) & np.isfinite(lo) & (lo <= px))
    if not len(hit):
        return None
    j = int(hit[0])
    raw = float(px[j])
    if np.isfinite(op[j]) and op[j] < raw:
        raw = float(op[j])
    return r0 + j, raw


def strike(ref_c: float, step: float, side: int, itm: int) -> float:
    k = D3.atm(ref_c, step)
    return k - side * step * itm                 # 1-ITM: a call one step below, a put one step above


# ------------------------------------------------------------------------------------------------ per index
def run_index(args) -> dict:
    und, part, only = args
    lo, hi = K.DESIGN if part == "design" else K.HOLDOUT
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    tg = trend_grid(g)
    scans = sorted({v[:4] for v in only}) if only else SCANS
    sig, meta = {}, {}
    for sc in scans:
        L, M, T, S = sc
        sig[sc], meta[sc] = R.scan(g, BASE, blocked, keep, side_cls=MajorSide if L else None,
                                   entry_last=MORNING_LAST if M else R.LAST_ENTRY,
                                   trend=(lambda d, r: int(tg[d, r])) if T else None,
                                   min_stop_pct=MIN_STOP if S else 0.0)
    rows = []
    by_day = {}
    for sc, T_ in sig.items():
        for t in T_:
            by_day.setdefault(t["d"], []).append((sc, t))
    for d in sorted(by_day):
        day = g.days[d]
        src = Mo if day in wexp else W
        opts = src.day(day, g.c[d])
        if opts is None:
            continue
        H, L_, O = g.h[d], g.l[d], g.o[d]
        for sc, t in by_day[d]:
            sd, r0, xr = t["side"], t["entry"], t["exit"]
            cp = "C" if sd > 0 else "P"
            ref_c = D3._last(g.c[d][:r0])
            if not np.isfinite(ref_c):
                continue
            res = {kk: option_locks(opts.contracts.get((cp, strike(ref_c, step, sd, kk))), r0, xr, t["why"], lot,
                                    day, und) for kk in (0, 1)}
            mh, ml, mo = (H, L_, O) if sd > 0 else (-L_, -H, -O)
            hi_r = min(MORNING_LAST if sc[1] else R.LAST_ENTRY, K.K3.EXIT_LAST)
            tw = {kk: [] for kk in (0, 1)}
            for _ in range(TWINS):
                r1 = int(rng.integers(R.FIRST_TWIN, hi_r + 1))
                ref1 = mo[r1]
                c1 = D3._last(g.c[d][:r1])
                e1 = None
                if np.isfinite(ref1) and np.isfinite(c1):
                    e1 = R.exit_on(mh, ml, mo, r1, ref1 - t["stop_pts"], ref1 + t["tgt_pts"], K.K3.EXIT_LAST)
                for kk in (0, 1):
                    x = None if e1 is None else option_locks(
                        opts.contracts.get((cp, strike(c1, step, sd, kk))), r1, e1[0], e1[1], lot, day, und)
                    tw[kk].append([np.nan] * 3 if x is None else [y[0] for y in x])
            for kk in (0, 1):
                if res[kk] is None:
                    continue
                twk = np.array(tw[kk])                                  # twins x locks
                for p in range(3):
                    v = sc + (kk, p)
                    if only and v not in only:
                        continue
                    rows.append(dict(t, version=vname(v), und=und, net=res[kk][p][0], netx2=res[kk][p][1],
                                     twins=twk[:, p].tolist()))
    print(f"R130enh {part} {und}: {len(rows)} version-trades", flush=True)
    skips = {str(sc): m["skips"] for sc, m in meta.items()}
    return dict(rows=rows, skips=skips)


# ------------------------------------------------------------------------------------------------ statistics
def normal_p(T: pd.DataFrame, seed: int = SEED) -> tuple[float, float]:
    """(p from the normal approximation of the twin totals, the empirical p): see PREREG_ENH section 3."""
    p_emp, _, _ = R.twin_p(T, seed)
    rng = np.random.default_rng(seed)
    tw = np.array(T.twins.tolist(), dtype=float)
    for k in range(tw.shape[0]):
        bad = ~np.isfinite(tw[k])
        if bad.all():
            tw[k] = np.nan
        elif bad.any():
            tw[k, bad] = rng.choice(tw[k, ~bad], bad.sum())
    keep = np.isfinite(tw).all(1)
    if not keep.any():
        return 1.0, p_emp
    tot = tw[keep].sum(0)
    sd = float(tot.std(ddof=1)) or 1e-9
    z = (float(T.net.values[keep].sum()) - float(tot.mean())) / sd
    return float(0.5 * (1 - erf(z / sqrt(2)))), p_emp


def summarize(T: pd.DataFrame, by_fold: bool) -> dict:
    s = R.stats(T, by_fold)
    if s.get("n"):
        s["p_normal"], s["p_emp"] = normal_p(T.sort_values(["day", "und", "entry"]).reset_index(drop=True))
    return s


def run(part: str):
    from multiprocessing import Pool
    only = None
    if part == "seen":
        sel = json.loads((R.RESULTS / "design_enh.json").read_text())["locked"]
        only = {tuple(int(c) for c in x[3::2]) for x in sel}       # "E-L1M0..." -> (1, 0, ...)
        if (R.RESULTS / "seen_enh.json").exists():
            raise R.HoldoutError("refused: the locked versions were already checked (results/seen_enh.json)")
        R.holdout_lock("start", suffix="_enh")                          # its own one-read lock file
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part, only) for u in K.UNDS])
    T = pd.DataFrame([r for x in res for r in x["rows"]])
    names = [vname(v) for v in VERSIONS if not only or v in only]
    out = dict(part=part, versions={}, skips={u: x["skips"] for u, x in zip(K.UNDS, res)})
    for nm in names:
        s = summarize(T[T.version == nm] if len(T) else T, part == "design")
        s["label"] = describe(tuple(int(c) for c in nm[3::2]))
        out["versions"][nm] = s
    if part == "design":
        prior = [json.loads((R.RESULTS / f).read_text())["variants"] for f in ("design.json", "design_wick.json")]
        prior_p = [v.get("p_twins", 1.0) for x in prior for v in x.values()]
        ps = [out["versions"][nm].get("p_normal", 1.0) for nm in names]
        qs = R.R3.bh(ps + prior_p)[:len(ps)]
        for nm, q in zip(names, qs):
            s = out["versions"][nm]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["gates"] = dict(n=s.get("n", 0) >= 300, net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                              folds=sum(folds) >= 3, q=q <= 0.10)
            s["folds_positive"] = int(sum(folds))
            s["passes_design"] = all(s["gates"].values())
        passers = [nm for nm in names if out["versions"][nm]["passes_design"]]
        pool_ = passers or [nm for nm in names if out["versions"][nm].get("n")]
        out["locked"] = sorted(pool_, key=lambda nm: -out["versions"][nm].get("total", -1e18))[:2]
        out["any_passed_design"] = bool(passers)
        out["factor_effects"] = factor_effects(out["versions"])
        out["bh_family"] = FAMILY_SIZE
    R.RESULTS.mkdir(parents=True, exist_ok=True)
    fn = "design_enh.json" if part == "design" else "seen_enh.json"
    (R.RESULTS / fn).write_text(json.dumps(out, indent=1, default=str))
    R.OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins"]).to_parquet(R.OUT / f"trades_enh_{part}.parquet")
    if part == "seen":
        R.holdout_lock("done", suffix="_enh")
    for nm in (out.get("locked") or names)[:4]:
        s = out["versions"][nm]
        print(nm, s.get("label"), s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 3))


def factor_effects(V: dict) -> dict:
    """Each factor level averaged over the other factors (design): mean of the versions' avg net and totals."""
    out = {}
    names = {"L": 0, "M": 1, "T": 2, "S": 3, "K": 4, "P": 5}
    for f, i in names.items():
        lv = {}
        for nm, s in V.items():
            if not s.get("n"):
                continue
            x = int(nm[3 + 2 * i])
            lv.setdefault(x, []).append(s)
        out[f] = {str(x): dict(versions=len(ss), mean_avg_net=float(np.mean([s["avg_net"] for s in ss])),
                               mean_total=float(np.mean([s["total"] for s in ss])),
                               mean_trades=float(np.mean([s["n"] for s in ss])),
                               mean_win=float(np.mean([s["win"] for s in ss])))
                  for x, ss in sorted(lv.items())}
    return out


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
