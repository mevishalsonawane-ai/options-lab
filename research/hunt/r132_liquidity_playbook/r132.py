"""R132 (PREREG.md): the liquidity playbook from Boss's two PDFs. Signal on the index (wicks only); the option is bought.

    python research/hunt/r132_liquidity_playbook/r132.py design    # -> results/design.json (+ the PDFs' claims)
    python research/hunt/r132_liquidity_playbook/r132.py nifty     # NIFTY 2017-2020 index points -> results/nifty_2017_2020.json
    python research/hunt/r132_liquidity_playbook/r132.py holdout   # read once -> results/holdout.json

Buys and sells share one code path: sells run the buy logic on the mirrored index (high -> -low, low -> -high, ...).
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r130_sweep_mss_fvg"))
sys.path.insert(0, str(HERE.parent / "r131_trend15"))
import r130 as R  # noqa: E402
import r131 as R31  # noqa: E402

K, D3 = R.K, R.D3
N, TICK = R.N, R.TICK
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R132_OUT", K.K3.CACHE_ROOT / "r132")).expanduser()
ROUND = {"NIFTY": 100.0, "FINNIFTY": 100.0, "BANKNIFTY": 500.0, "SENSEX": 500.0, "MIDCPNIFTY": 50.0}
DEPTH_PCT, SUDDEN, SUDDEN_LOOK = 0.0002, 1.2, 20
RECLAIM_BARS, BOS_BARS, FVG_BARS, SWING_K = 2, 12, 12, 3
STOP_PCT, EQ_TOL, ROUND_FRESH_BARS, ROUND_BAND = 0.0002, 0.0003, 12, 0.01
VOL_X, VOL_LOOK, VOL_MIN = 1.5, 12, 3
FIRST_ENTRY, LAST_ENTRY = K.row(9, 30), K.row(14, 30)
OPEN_LO, OPEN_HI = K.row(9, 30), K.row(10, 30)
TWINS, SEED = 200, 132
EXTERNAL = {"pdl", "pwl"}
VERSIONS = {
    "P1": dict(entry="bos", vol=False, open_=False, h60=False, ext=False, label="the playbook (sweep, reclaim, BOS, opposite liquidity)"),
    "P2": dict(entry="fvg", vol=False, open_=False, h60=False, ext=False, label="enter on the FVG retrace"),
    "P3": dict(entry="bos", vol=True, open_=False, h60=False, ext=False, label="+ volume on the sweep (ATM options)"),
    "P4": dict(entry="bos", vol=False, open_=True, h60=False, ext=False, label="+ session open (sweeps 09:30-10:30)"),
    "P5": dict(entry="bos", vol=False, open_=False, h60=True, ext=False, label="+ 60-minute structure agrees"),
    "P6": dict(entry="bos", vol=False, open_=False, h60=False, ext=True, label="external liquidity only (PDH/PDL, previous week)"),
    "P7": dict(entry="reclaim", vol=False, open_=False, h60=False, ext=False, label="too early: enter on the reclaim, no BOS"),
    "P8": dict(entry="bos", vol=True, open_=True, h60=True, ext=False, label="all the confluence (P3 + P4 + P5)"),
}
GATES = dict(design_n=300, folds=3, q=0.10, holdout_n=50, win=0.65, dd=-40000.0, month=-13000.0)


# ------------------------------------------------------------------------------------------------ helpers
def _cummin_before(x: np.ndarray) -> np.ndarray:
    """out[r] = min of x[:r] (inf at r = 0), NaN-aware."""
    y = np.where(np.isfinite(x), x, np.inf)
    return np.r_[np.inf, np.minimum.accumulate(y)[:-1]]


def _cummax_before(x: np.ndarray) -> np.ndarray:
    y = np.where(np.isfinite(x), x, -np.inf)
    return np.r_[-np.inf, np.maximum.accumulate(y)[:-1]]


def round_below(x: float, step: float, offset: float = 0.0) -> float:
    """The largest level k x step + offset strictly below x."""
    k = np.floor((x - offset) / step)
    v = k * step + offset
    return float(v - step if v >= x else v)


def round_above(x: float, step: float, offset: float = 0.0) -> float:
    k = np.ceil((x - offset) / step)
    v = k * step + offset
    return float(v + step if v <= x else v)


def race(mh, ml, mo, r0: int, p0: float, d: float, last: int):
    """1:1 race on wicks from minute r0: 'up' (+d first), 'down' (-d first; both in a minute: down), 'none'."""
    if not (d > 0) or r0 > last:
        return "none"
    ex = R.exit_on(mh, ml, mo, r0, p0 - d, p0 + d, last)
    if ex is None:
        return "none"
    return {"target": "up", "stop": "down", "time": "none"}[ex[1]]


@dataclass
class Sweep:
    t: int              # sweep bar (index into the side's bars)
    d: int
    rc: int             # the reclaim minute
    level: float        # the swept level (the highest swept)
    low: float          # the sweep extreme
    kinds: set
    sudden: bool
    vol_ok: bool | None
    is_round: bool
    highs: list = field(default_factory=list)   # untaken swing highs known at the sweep (equal-highs targets)


# ------------------------------------------------------------------------------------------------ one side
@dataclass
class SideData:
    """Everything the buy logic needs for one side (the real index for buys, the mirrored one for sells)."""
    mh: np.ndarray
    ml: np.ndarray
    mo: np.ndarray
    mc: np.ndarray
    step: float
    days: list
    vol: dict | None = None             # day -> (N,) ATM call + put volume (real; the same for both sides)
    B: R.Bars = None
    first: dict = field(default_factory=dict)

    def __post_init__(self):
        self.B = R.make_bars(self.mo, self.mh, self.ml, self.mc, 5)
        for t, d in enumerate(self.B.day):
            self.first.setdefault(int(d), t)
        D = len(self.days)
        with np.errstate(all="ignore"):
            import warnings
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", RuntimeWarning)
                self.day_low = np.nanmin(self.ml, axis=1)
                self.day_high = np.nanmax(self.mh, axis=1)
                self.orl = np.nanmin(self.ml[:, :15], axis=1)
                self.orh = np.nanmax(self.mh[:, :15], axis=1)
        wk = [tuple(d.isocalendar())[:2] for d in self.days]
        self.pwl = np.full(D, np.nan)
        self.pwh = np.full(D, np.nan)
        self.week_low_before = np.full(D, np.inf)
        self.week_high_before = np.full(D, -np.inf)
        weeks = {}
        for i, w in enumerate(wk):
            weeks.setdefault(w, []).append(i)
        keys = sorted(weeks)
        for a, w in enumerate(keys):
            idx = weeks[w]
            if a > 0:
                prev = weeks[keys[a - 1]]
                lo, hi = self.day_low[prev], self.day_high[prev]
                if np.isfinite(lo).any():
                    self.pwl[idx] = np.nanmin(lo)
                    self.pwh[idx] = np.nanmax(hi)
            run_lo, run_hi = np.inf, -np.inf
            for i in idx:
                self.week_low_before[i], self.week_high_before[i] = run_lo, run_hi
                if np.isfinite(self.day_low[i]):
                    run_lo, run_hi = min(run_lo, self.day_low[i]), max(run_hi, self.day_high[i])
        self.cmin = np.vstack([_cummin_before(self.ml[d]) for d in range(D)]) if D else np.zeros((0, N))
        self.cmax = np.vstack([_cummax_before(self.mh[d]) for d in range(D)]) if D else np.zeros((0, N))
        rg = self.B.h - self.B.l
        self.med_rg = pd.Series(rg).rolling(SUDDEN_LOOK, min_periods=SUDDEN_LOOK).median().shift(1).values

    # -------------------------------------------------------------------------------------------- levels
    def low_levels(self, t: int, lows_untaken: list, round_offset: float = 0.0) -> list:
        """Fresh sell-side levels at bar t: (level, kind)."""
        B, d, rs = self.B, int(self.B.day[t]), int(self.B.start[t])
        out = []
        if d - 1 >= 0 and np.isfinite(self.day_low[d - 1]):
            pdl = self.day_low[d - 1]
            if self.cmin[d, rs] > pdl - TICK:
                out.append((float(pdl), "pdl"))
        if rs >= 15 and np.isfinite(self.orl[d]):
            seg = self.ml[d, 15:rs]
            if not (np.isfinite(seg) & (seg <= self.orl[d] - TICK)).any():
                out.append((float(self.orl[d]), "orl"))
        if np.isfinite(self.pwl[d]):
            if self.week_low_before[d] > self.pwl[d] - TICK and self.cmin[d, rs] > self.pwl[d] - TICK:
                out.append((float(self.pwl[d]), "pwl"))
        lv = sorted(x[0] for x in lows_untaken)
        for a, b in zip(lv, lv[1:]):
            if b - a <= EQ_TOL * abs(a):
                out.append((float(a), "eq"))
        lo12 = np.nanmin(self.B.l[max(t - ROUND_FRESH_BARS, 0):t]) if t > 0 else np.nan
        if np.isfinite(lo12):
            rl = round_below(lo12 - TICK + 1e-9, self.step, round_offset)
            if abs(lo12 - rl) <= ROUND_BAND * abs(lo12):
                out.append((rl, "round"))
        return out

    def target(self, d: int, e: int, ref: float, need: float, highs_untaken: list):
        """The nearest fresh buy-side level at or above `need` (PDH, ORH, PWH, outer of equal highs, round)."""
        cands = []
        if d - 1 >= 0 and np.isfinite(self.day_high[d - 1]) and self.cmax[d, e] < self.day_high[d - 1] + TICK:
            cands.append(self.day_high[d - 1])
        if e >= 15 and np.isfinite(self.orh[d]):
            seg = self.mh[d, 15:e]
            if not (np.isfinite(seg) & (seg >= self.orh[d] + TICK)).any():
                cands.append(self.orh[d])
        if np.isfinite(self.pwh[d]) and self.week_high_before[d] < self.pwh[d] + TICK and \
                self.cmax[d, e] < self.pwh[d] + TICK:
            cands.append(self.pwh[d])
        hv = sorted(float(x) for x in highs_untaken)                   # plain levels
        for a, b in zip(hv, hv[1:]):
            if b - a <= EQ_TOL * abs(a):
                cands.append(b)
        ru = round_above(need - 1e-9, self.step)
        recent = self.mh[d, max(e - 60, 0):e]
        if not (np.isfinite(recent) & (recent >= ru - TICK)).any():
            cands.append(ru)
        ok = [float(c) for c in cands if np.isfinite(c) and c >= need]
        return min(ok) if ok else None


def find_sweeps(S: SideData, round_offset: float = 0.0, only_round: bool = False):
    """All reclaimed sweeps (PREREG section 1) and the 'runs' (breaks with no reclaim), in time order, plus the
    side's swing-high availability for the BOS. Also returns, per sweep, the latest swing high known at it."""
    B = S.B
    hi_av, lo_av = {}, {}
    for i in R.swing_highs(B.h, SWING_K):
        hi_av.setdefault(int(i) + SWING_K + 1, []).append(int(i))
    for i in R.swing_highs(-B.l, SWING_K):
        lo_av.setdefault(int(i) + SWING_K + 1, []).append(int(i))
    highs, lows = [], []                      # (level, day): known swing highs (untaken for targets) / untaken lows
    sweeps, runs, H_at = [], [], []
    prev_d = -1
    for t in range(len(B.day)):
        d = int(B.day[t])
        if d != prev_d:
            highs = [x for x in highs if x[1] >= d - 4]
            lows = [x for x in lows if x[1] >= d - 4]
            prev_d = d
        for i in hi_av.get(t, ()):
            if B.day[i] >= d - 4 and not (B.h[i + 1:t] >= B.h[i] + TICK).any():
                highs.append((float(B.h[i]), int(B.day[i])))
        for i in lo_av.get(t, ()):
            if B.day[i] >= d - 4 and not (B.l[i + 1:t] <= B.l[i] - TICK).any():
                lows.append((float(B.l[i]), int(B.day[i])))
        levels = S.low_levels(t, lows, round_offset)
        if only_round:
            levels = [x for x in levels if x[1] == "round"]
        swept = [(lv, kd) for lv, kd in levels if B.l[t] <= lv - max(TICK, DEPTH_PCT * abs(lv))]
        if swept:
            L = max(x[0] for x in swept)
            kinds = {kd for lv, kd in swept}
            rs, re = int(B.start[t]), int(B.end[t])
            depth = max(TICK, DEPTH_PCT * abs(L))
            end2 = min(re + 5 * RECLAIM_BARS, N - 1)
            # minute by minute, point in time: the breach, the running low, then the first high back above the level
            r_br = next((r for r in range(rs, re + 1) if np.isfinite(S.ml[d, r]) and S.ml[d, r] <= L - depth), None)
            rc, sl = None, np.inf
            if r_br is not None:
                sl = float(S.ml[d, r_br])
                for r in range(r_br + 1, end2 + 1):
                    if not (np.isfinite(S.mh[d, r]) and np.isfinite(S.ml[d, r])):
                        continue
                    if S.mh[d, r] > L:
                        rc = r
                        break
                    sl = min(sl, float(S.ml[d, r]))
            if rc is not None:
                upto = min(rc, re)
                rng = np.nanmax(S.mh[d, rs:upto + 1]) - np.nanmin(S.ml[d, rs:upto + 1])
                sudden = bool(np.isfinite(S.med_rg[t]) and rng >= SUDDEN * S.med_rg[t])
                vol_ok = None
                if S.vol is not None and S.days[d] in S.vol:
                    v = S.vol[S.days[d]]
                    per = float(np.nansum(v[rs:upto + 1])) / (upto - rs + 1)
                    prev = [float(np.nansum(v[q:q + 5])) / 5 for q in range(max(rs - 5 * VOL_LOOK, 0), rs, 5)]
                    vol_ok = bool(len(prev) >= VOL_MIN and per >= VOL_X * np.mean(prev)) if prev else False
                sweeps.append(Sweep(t, d, rc, L, sl, kinds, sudden, vol_ok, "round" in kinds,
                                    highs=[x[0] for x in highs]))
                H_at.append(highs[-1][0] if highs else np.nan)
            elif r_br is not None:
                # a run: broken and no reclaim by the end of bar t + 2; raced from the next minute
                lowest = float(np.nanmin(S.ml[d, rs:end2 + 1]))
                runs.append((t, d, end2 + 1, L, lowest, "round" in kinds))
        x = B.l[t]
        lows = [lv for lv in lows if not x <= lv[0] - TICK]
        y = B.h[t]
        highs = [hv for hv in highs if not y >= hv[0] + TICK]
    return sweeps, runs, H_at, hi_av


def setups(S: SideData, sweeps, H_at, cfg: dict, h60, side: int):
    """Candidate trades of one version on one side (before the one-position merge)."""
    B = S.B
    out = []
    for sw, H in zip(sweeps, H_at):
        if not sw.sudden:
            continue
        if cfg["vol"] and not sw.vol_ok:
            continue
        if cfg["open_"] and not (OPEN_LO <= int(B.start[sw.t]) <= OPEN_HI):
            continue
        if cfg["ext"] and not (sw.kinds & EXTERNAL):
            continue
        d = sw.d
        stop = sw.low - STOP_PCT * abs(sw.low)
        mh, ml, mo = S.mh[d], S.ml[d], S.mo[d]
        e = None
        if cfg["entry"] == "reclaim":
            e = sw.rc + 1
        else:
            if not np.isfinite(H) or H <= sw.level:
                continue
            end_bos = min(int(B.end[sw.t]) + 5 * BOS_BARS, N - 1)
            rb = None
            for r in range(sw.rc + 1, end_bos + 1):
                if not (np.isfinite(mh[r]) and np.isfinite(ml[r])):
                    continue
                if ml[r] <= stop:
                    break
                if mh[r] >= H + TICK:
                    rb = r
                    break
            if rb is None:
                continue
            if cfg["entry"] == "bos":
                e = rb + 1
            else:                                    # FVG retrace after the BOS bar completes
                m = next((j for j in range(sw.t, len(B.day)) if B.day[j] == d and B.start[j] <= rb <= B.end[j]), None)
                if m is None:
                    continue
                fv = R.find_fvg(B.h, B.l, sw.t, m)
                if fv is None:
                    continue
                top = fv[0]
                for r in range(int(B.end[m]) + 1, min(int(B.end[m]) + 5 * FVG_BARS, N - 1) + 1):
                    if not (np.isfinite(mh[r]) and np.isfinite(ml[r])):
                        continue
                    if ml[r] <= stop:
                        break
                    if ml[r] <= top:
                        e = r + 1
                        break
                if e is None:
                    continue
        if e is None or not (FIRST_ENTRY <= e <= LAST_ENTRY) or e >= N or not np.isfinite(mo[e]):
            continue
        if h60 is not None and cfg["h60"] and h60[d, e] != side:
            continue
        ref = float(mo[e])
        if ref <= stop:
            continue
        Rr = ref - stop
        tgt = S.target(d, e, ref, ref + Rr, sw.highs)
        if tgt is None:
            tgt = ref + 2 * Rr
        ex = R.exit_on(mh, ml, mo, e, stop, tgt, K.K3.EXIT_LAST)
        if ex is None:
            continue
        xr, why, xpx = ex
        out.append(dict(d=d, side=side, entry=e, exit=xr, why=why, ref=ref * side, stop_pts=Rr, tgt_pts=tgt - ref,
                        pts=xpx - ref, R=(xpx - ref) / Rr, kinds=",".join(sorted(sw.kinds))))
    return out


def merge(cands: list, blocked: set) -> list:
    """One position per index: take candidates in entry order when no position is open."""
    out, busy = [], (-1, -1)
    for c in sorted(cands, key=lambda x: (x["d"], x["entry"])):
        if c["d"] in blocked:
            continue
        if (c["d"], c["entry"]) <= busy:
            continue
        out.append(c)
        busy = (c["d"], c["exit"])
    return out


# ------------------------------------------------------------------------------------------------ per index
def atm_volume(und: str, last_day: date) -> dict:
    """day -> (N,) ATM (offset 0) call + put volume of the nearest-expiry series."""
    out = {}
    for p in sorted((K.K3.LAKE / "options_rolling_1m").glob(f"opt_{und}_WEEK_*.parquet")):
        o = D3._ts_frame(p, ["ts", "offset", "volume"])
        o = o[(o.offset == 0) & (o.d <= last_day)]
        g = o.groupby(["d", "m"]).volume.sum()
        for (d, m), v in g.items():
            out.setdefault(d, np.zeros(N))[m] = v
    return out


def build_sides(g, step, vol):
    M = R.mirror(g)
    return {sd: SideData(*M[sd], step=step, days=list(g.days), vol=vol) for sd in (1, -1)}


def signals(g, und: str, vol, h60, blocked: set, keep, versions=None):
    """Per version: the merged trades (index side), plus the PDFs' claims inputs."""
    sides = build_sides(g, ROUND[und], vol)
    per_side = {}
    for sd, S in sides.items():
        sw, runs, H_at, _ = find_sweeps(S)
        per_side[sd] = (S, sw, runs, H_at)
    out = {}
    for v, cfg in (versions or VERSIONS).items():
        cands = []
        for sd, (S, sw, runs, H_at) in per_side.items():
            cands += setups(S, sw, H_at, cfg, h60, sd)
        out[v] = [dict(c, day=g.days[c["d"]]) for c in merge(cands, blocked) if keep(g.days[c["d"]])]
    return out, per_side


def claims(g, per_side, keep, und: str) -> dict:
    """K1, K2, K3 (PREREG section 3), index wicks, kept days only."""
    k1 = dict(sweeps=0, sweeps_bos=0, base=0, base_bos=0)
    k2 = dict(sweep=dict(up=0, down=0, none=0), run=dict(up=0, down=0, none=0))
    k3 = dict(round=dict(up=0, down=0, none=0), placebo=dict(up=0, down=0, none=0))
    last = K.K3.EXIT_LAST
    for sd, (S, sw, runs, H_at) in per_side.items():
        B = S.B
        for s, H in zip(sw, H_at):
            if not (s.sudden and keep(S.days[s.d])):
                continue
            d = s.d
            k1["sweeps"] += 1
            end_bos = min(int(B.end[s.t]) + 5 * BOS_BARS, N - 1)
            if np.isfinite(H) and (np.nan_to_num(S.mh[d, s.rc + 1:end_bos + 1], nan=-np.inf) >= H + TICK).any():
                k1["sweeps_bos"] += 1
            res = race(S.mh[d], S.ml[d], S.mo[d], s.rc + 1, s.level, s.level - s.low, last)
            k2["sweep"][res] += 1
            if s.is_round:
                k3["round"][res] += 1
        for (t, d, r0, L, lowest, is_round) in runs:
            if keep(S.days[d]) and r0 < N and np.isfinite(S.mo[d, min(r0, N - 1)]):
                k2["run"][race(S.mh[d], S.ml[d], S.mo[d], r0, float(S.mo[d, r0]), L - lowest, last)] += 1
        # K1 baseline: every bar from 09:30 as if it were a sweep (the latest swing high known then)
        hi_av = {}
        for i in R.swing_highs(B.h, SWING_K):
            hi_av.setdefault(int(i) + SWING_K + 1, []).append(int(i))
        Hcur = np.nan
        for t in range(len(B.day)):
            for i in hi_av.get(t, ()):
                Hcur = float(B.h[i])
            d = int(B.day[t])
            if B.start[t] < 15 or not keep(S.days[d]) or not np.isfinite(Hcur):
                continue
            k1["base"] += 1
            end_bos = min(int(B.end[t]) + 5 * BOS_BARS, N - 1)
            if (np.nan_to_num(S.mh[d, int(B.end[t]) + 1:end_bos + 1], nan=-np.inf) >= Hcur + TICK).any():
                k1["base_bos"] += 1
        # K3 placebo: the same sweeps at half-way levels
        sw_p, _, _, _ = find_sweeps(S, round_offset=ROUND[und] / 2, only_round=True)
        for s in sw_p:
            if s.sudden and keep(S.days[s.d]):
                k3["placebo"][race(S.mh[s.d], S.ml[s.d], S.mo[s.d], s.rc + 1, s.level, s.level - s.low, last)] += 1
    return dict(k1=k1, k2=k2, k3=k3)


def run_index(args) -> dict:
    und, part = args
    lo, hi = K.DESIGN if part == "design" else K.HOLDOUT
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    vol = atm_volume(und, hi)
    h60 = R31.state_grid(g, 60)
    sig, per_side = signals(g, und, vol, h60, blocked, keep)
    cl = claims(g, per_side, keep, und) if part == "design" else None
    by_day = {}
    for v, T in sig.items():
        for t in T:
            by_day.setdefault(t["d"], []).append((v, t))
    rows = []
    for d in sorted(by_day):
        day = g.days[d]
        src = Mo if day in wexp else W
        opts = src.day(day, g.c[d])
        if opts is None:
            continue
        H, L_, O = g.h[d], g.l[d], g.o[d]
        for v, t in by_day[d]:
            sd, r0, xr = t["side"], t["entry"], t["exit"]
            cp = "C" if sd > 0 else "P"
            ref_c = D3._last(g.c[d][:r0])
            if not np.isfinite(ref_c):
                continue
            tr = R.option_trade(opts.contracts.get((cp, D3.atm(ref_c, step))), r0, xr, t["why"], lot, day, und)
            if tr is None:
                continue
            mh, ml, mo = (H, L_, O) if sd > 0 else (-L_, -H, -O)
            tw = []
            for _ in range(TWINS):
                r1 = int(rng.integers(FIRST_ENTRY, LAST_ENTRY + 1))
                ref1, c1 = mo[r1], D3._last(g.c[d][:r1])
                x = None
                if np.isfinite(ref1) and np.isfinite(c1):
                    e1 = R.exit_on(mh, ml, mo, r1, ref1 - t["stop_pts"], ref1 + t["tgt_pts"], K.K3.EXIT_LAST)
                    if e1 is not None:
                        x = R.option_trade(opts.contracts.get((cp, D3.atm(c1, step))), r1, e1[0], e1[1], lot, day,
                                           und)
                tw.append(np.nan if x is None else x[0])
            rows.append(dict(t, variant=v, und=und, series=src.series, net=tr[0], netx2=tr[1], twins=tw))
    print(f"R132 {part} {und}: " + ", ".join(f"{v} {sum(r['variant'] == v for r in rows)}" for v in VERSIONS),
          flush=True)
    return dict(rows=rows, claims=cl, sessions=sorted({d for d in g.days if keep(d)}))


# ------------------------------------------------------------------------------------------------ NIFTY 2017-2020
def run_nifty():
    if (RESULTS / "nifty_2017_2020.json").exists():
        raise R.HoldoutError("refused: already read (results/nifty_2017_2020.json)")
    g = R31.load_grid_from("NIFTY", date(2017, 1, 1), date(2020, 12, 31))
    h60 = R31.state_grid(g, 60)
    D = json.loads((RESULTS / "design.json").read_text())
    be = D["breakeven_pts_nifty"]
    vs = {v: VERSIONS[v] for v in ("P1", "P2", "P4", "P5", "P6", "P7")}
    sig, _ = signals(g, "NIFTY", None, h60, set(), lambda d: True, versions=vs)
    out = dict(period=["2017-01-01", "2020-12-31"], sessions=len(g.days), breakeven_pts=be, versions={})
    for v, T in sig.items():
        T = pd.DataFrame(T)
        if not len(T):
            out["versions"][v] = dict(n=0)
            continue
        yr = T.groupby([d.year for d in T.day]).pts.agg(["count", "mean"])
        out["versions"][v] = dict(label=VERSIONS[v]["label"], n=int(len(T)), avg_pts=float(T.pts.mean()),
                                  pts_win=float((T.pts > 0).mean()), avg_R=float(T.R.mean()),
                                  net_pts=float(T.pts.mean() - be), above_breakeven=bool(T.pts.mean() > be),
                                  by_year={int(y): dict(n=int(r["count"]), avg=float(r["mean"])) for y, r in yr.iterrows()},
                                  exits={k: int(x) for k, x in T.why.value_counts().items()})
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "nifty_2017_2020.json").write_text(json.dumps(out, indent=1, default=str))
    print(json.dumps({v: {k: s.get(k) for k in ("n", "avg_pts", "pts_win", "above_breakeven")}
                      for v, s in out["versions"].items()}, indent=1))


# ------------------------------------------------------------------------------------------------ run
def run(part: str):
    from multiprocessing import Pool
    if part == "nifty":
        return run_nifty()
    if part == "holdout":
        R.holdout_lock("start", OUT, RESULTS)
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    T = pd.DataFrame([r for x in res for r in x["rows"]])
    out = dict(part=part, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)],
               sessions=len({d for x in res for d in x["sessions"]}), variants={})
    for v in VERSIONS:
        s = R.stats(T[T.variant == v] if len(T) else T, part == "design")
        if s.get("n"):
            s["index_win"] = float((T[T.variant == v].pts > 0).mean())
            s["by_kind"] = {k: dict(n=int(len(X)), total=float(X.net.sum()))
                            for k, X in T[T.variant == v].groupby("kinds")}
        s["label"] = VERSIONS[v]["label"]
        out["variants"][v] = s
    if part == "design":
        qs = R.R3.bh([out["variants"][v].get("p_twins", 1.0) for v in VERSIONS])
        for v, q in zip(VERSIONS, qs):
            s = out["variants"][v]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["design_gates"] = dict(n=s.get("n", 0) >= GATES["design_n"],
                                     net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                                     folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"])
            s["passes_design"] = all(s["design_gates"].values())
        X = T[(T.variant == "P1") & (T.und == "NIFTY")]
        out["breakeven_pts_nifty"] = float(np.median(X.pts - 2 * X.net / K.LOT["NIFTY"])) if len(X) else None
        cl = {}
        for x in res:
            for k, blk in x["claims"].items():
                for kk, vv in blk.items():
                    if isinstance(vv, dict):
                        for k3, n3 in vv.items():
                            cl.setdefault(k, {}).setdefault(kk, {}).setdefault(k3, 0)
                            cl[k][kk][k3] += n3
                    else:
                        cl.setdefault(k, {}).setdefault(kk, 0)
                        cl[k][kk] += vv
        out["claims"] = claims_test(cl)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins"]).to_parquet(OUT / f"trades_{part}.parquet")
    if part == "holdout":
        R.holdout_lock("done", OUT, RESULTS)
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 3), s.get("p_twins"))
           for v, s in out["variants"].items()})


def _two_prop(a_up, a_n, b_up, b_n) -> dict:
    from math import erf, sqrt
    if not (a_n and b_n):
        return {}
    p1, p2 = a_up / a_n, b_up / b_n
    pp = (a_up + b_up) / (a_n + b_n)
    se = sqrt(pp * (1 - pp) * (1 / a_n + 1 / b_n)) or 1e-12
    z = (p1 - p2) / se
    return dict(a=p1, n_a=a_n, b=p2, n_b=b_n, z=z, p=float(2 * (1 - 0.5 * (1 + erf(abs(z) / sqrt(2))))))


def claims_test(cl: dict) -> dict:
    k1, k2, k3 = cl["k1"], cl["k2"], cl["k3"]
    dec = lambda x: (x["up"], x["up"] + x["down"])  # noqa: E731
    return dict(
        k1=dict(raw=k1, test=_two_prop(k1["sweeps_bos"], k1["sweeps"], k1["base_bos"], k1["base"])),
        k2=dict(raw=k2, test=_two_prop(*dec(k2["sweep"]), *dec(k2["run"]))),
        k3=dict(raw=k3, test=_two_prop(*dec(k3["round"]), *dec(k3["placebo"]))))


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
