"""The backtest engine: signals -> option path packs -> vectorised exits -> trades.

1. SIGNALS (a DataFrame, one row per entry candidate; made by a strategy's signal function):
     und, day (datetime.date), sig_min (minute whose 1-min bar CLOSE triggers the entry; the fill is the option's open at
     the next minute, sig_min + 1, or the first bar within Execution.max_delay minutes after it), side (+1 buy a call,
     -1 buy a put, 0 buy both = straddle).
   optional: book (one position at a time per book), gate (minute from which the book must be flat; default sig_min+1),
     ref_spot (index price for the strike rule; default the index close at sig_min), strike (explicit strike),
     idx_stop (absolute index level: a long is stopped when the index LOW goes below it, a short when the HIGH goes above),
     idx_target (absolute index level), exit_at (minute at whose open the position is closed, e.g. a structural exit),
     lot (lot-size override), tag (free text).
2. prepare(): picks the contract (StrikeRule), finds the fill bar, applies entry filters (Execution), and cuts the option
   and index minute paths into dense (N, 375) matrices - a Pack. A Pack is exit-independent, so any number of exit
   variants run on it without touching the data again (pack.run(exits)). Random-entry baseline pools are built in the
   same pass (pool_k alternatives per candidate: same day / underlying / book, a random minute in the strategy's entry
   window, a coin-flip side, the same strike rule and the same structural exit DISTANCES / holding offsets).
3. Exits (vectorised over all trades): within a minute, resting orders on the option bar first - the stop (the higher
   of the premium stop and any profit lock / trailing level; a lock above the stop replaces it), then the premium
   target; then decisions on the minute's close, filled at the next minute's open (MARKET): index stop, time stop,
   index target, exit_at, close-mode premium exits, square-off. This is the order of the app's arms (OrbFill /
   ArmsBacktest / Liquidity replays) and of jarvis_exits.simulate.
4. positions(): one position at a time per book (a book is flat again the minute after its exit), optional max trades
   a day and daily loss stop.
"""
from __future__ import annotations

import hashlib
import os
import pickle
from dataclasses import asdict, dataclass, field, replace

import numpy as np
import pandas as pd

from . import config as C
from .costs import Costs, Fills, floor_tick
from .data import dnum, market

NEG = -np.inf


@dataclass(frozen=True)
class StrikeRule:
    money: int = 0                 # strike steps in the money (+1 = one ITM, -2 = two OTM, 0 = ATM)
    series: str = "near"           # 'near' (weekly else monthly) | 'week' | 'month'
    prem_band: tuple | None = None  # (lo, hi): nearest-to-money OTM strike whose last price at sig_min is in [lo, hi]

    def label(self):
        m = "ATM" if self.money == 0 else (f"ITM{self.money}" if self.money > 0 else f"OTM{-self.money}")
        if self.prem_band:
            m = f"OTM Rs{self.prem_band[0]:g}-{self.prem_band[1]:g}"
        return f"{m}/{self.series}"


@dataclass(frozen=True)
class Execution:
    fills: Fills = Fills()
    costs: Costs = Costs()
    lot_mode: str = "data"         # 'data' (the day's lot from OI) | 'official' (exchange schedule) | 'today'
    lots: int = 1
    budget: float | None = None    # size by premium: floor(budget / (entry x lot)) lots (0 -> no trade)
    max_delay: int = 2             # minutes the fill bar may be late
    min_premium: float = 0.0       # skip when the filled entry price is <= this
    min_vol_lots: float = 0.0      # skip when the contract traded fewer lots than this today before the entry bar
    expiry: str = "skip"           # 'skip' expiry days | 'allow' | 'only'
    real_index: bool = False       # require a real index OHLC that day (not Dhan's spot print)
    exact: bool = False            # charges rounded with Decimal per trade (parity checks; slower)


@dataclass(frozen=True)
class Exits:
    stop_pct: float | None = None      # premium stop at floor_tick(e x (1 - pct))
    stop_pts: float | None = None      # premium stop at floor_tick(e - pts); with both, the higher trigger
    tgt_pct: float | None = None       # premium target e x (1 + pct)   (resting; first of the two if both)
    tgt_pts: float | None = None       # premium target e + pts
    ladder: tuple | None = None        # profit-lock rungs ((reach, lock), ...) in units of R, e.g. LADDER
    ladder_ref_pts: float | None = None  # R in premium points ...
    ladder_ref_pct: float | None = None  # ... or in % of the entry
    trail_pct: float | None = None     # trailing stop peak x (1 - trail_pct) ...
    trail_arm: float = 0.0             # ... once the peak is >= e x (1 + trail_arm)
    pine_trail: bool = False           # ProfitLock.Trail (+5% BE, from +8% keep 50% of the best gain, +20% 65%, +40% 75%)
    time_stop: int | None = None       # minutes after entry ...
    time_gain: float | None = 0.05     # ... out unless the premium is >= e x (1 + gain); None = out regardless
    sq_off: int = 15 * 60 + 10         # square-off: out at the first open at/after this minute
    idx_stop_pts: float | None = None  # index stop this many points against the entry reference (ref_spot)
    idx_tgt_pts: float | None = None   # index target this many points in favour
    idx_tgt_r: float | None = None     # index target at k x the signal's index risk (ref_spot to idx_stop)
    sig_levels: bool = True            # honour the signal's own idx_stop / idx_target / exit_at columns
    intrabar: bool = True              # premium exits on the bar's high/low (resting orders); False: on closes
    be_floor: bool = True              # ladder rungs never below breakeven after charges

    def label(self):
        d = {k: v for k, v in asdict(self).items() if v != getattr(Exits, k, None)}
        return ",".join(f"{k}={v}" for k, v in d.items()) or "default"


LADDER = ((0.25, 0.0), (0.50, 0.25), (0.75, 0.50))      # ProfitLock.LADDER
PINE_BE, PINE_STEPS = 5.0, ((8.0, 50.0), (20.0, 65.0), (40.0, 75.0))


# ------------------------------------------------------------------------------------------------ pack
class Store:
    """Shared, de-duplicated minute paths: one row per (und, day, series, strike, right) contract and per (und, day)
    index day, float32. Many candidates (and all their random alternatives) point at the same rows."""

    def __init__(self):
        self.ckey, self.ikey = {}, {}
        self.c = {k: [] for k in ("O", "H", "L", "Cl", "V")}
        self.i = {k: [] for k in ("IH", "IL")}
        self.arr = None

    def contract(self, key, rows):
        r = self.ckey.get(key)
        if r is None:
            r = self.ckey[key] = len(self.c["O"])
            for nm, a in zip(("O", "H", "L", "Cl", "V"), rows):
                self.c[nm].append(a.astype(np.float32))
        return r

    def index(self, key, I):
        r = self.ikey.get(key)
        if r is None:
            r = self.ikey[key] = len(self.i["IH"])
            self.i["IH"].append(I["h"].astype(np.float32))
            self.i["IL"].append(I["l"].astype(np.float32))
        return r

    def done(self):
        if self.arr is None:
            self.arr = {}
            for k, v in list(self.c.items()) + list(self.i.items()):
                self.arr[k] = np.stack(v) if v else np.zeros((0, C.W), np.float32)
            self.c = self.i = None
        return self

    def nbytes(self):
        return sum(v.nbytes for v in self.arr.values())


class Pack:
    """Candidate trades: meta (one row per candidate; crow / crow2 = contract rows, irow = index row) + a shared Store."""

    def __init__(self, meta, store, nleg):
        self.meta = meta.reset_index(drop=True)
        self.store = store
        self.nleg = nleg

    def __len__(self):
        return len(self.meta)

    def subset(self, idx):
        return Pack(self.meta.iloc[np.asarray(idx)], self.store, self.nleg)

    def get(self, name, sl):
        """(n, W) float64 rows of matrix `name` (O, H, L, Cl, V, IH, IL, and O2 ... V2 for the second leg)."""
        if name in ("IH", "IL"):
            rows = self.meta.irow.values[sl]
            a = self.store.arr[name][rows]
        else:
            leg2 = name.endswith("2")
            rows = self.meta["crow2" if leg2 else "crow"].values[sl]
            a = self.store.arr[name[:-1] if leg2 else name][rows]
        a = a.astype(np.float64)
        return a if name.startswith("V") else np.round(a, 2)

    def save(self, path):
        with open(path, "wb") as f:
            pickle.dump((self.meta, self.store.arr, self.nleg), f, protocol=4)

    @staticmethod
    def load(path):
        with open(path, "rb") as f:
            meta, arr, nleg = pickle.load(f)
        st = Store()
        st.arr = arr
        return Pack(meta, st, nleg)

    def run(self, ex: Exits, exe: Execution, chunk=8000):
        out = [run_exits(self, slice(i, min(i + chunk, len(self))), ex, exe) for i in range(0, len(self), chunk)]
        if not out:
            return empty_trades()
        return pd.concat(out, ignore_index=True)


def empty_trades():
    return pd.DataFrame(columns=["cand", "und", "book", "day", "year", "sig_min", "gate", "entry_min", "exit_min", "side",
                                 "strike", "qty", "entry", "exit", "why", "gross", "charges", "net", "risk_rs", "R"])


def _atm(spot, step):
    return np.floor(np.asarray(spot) / step + 0.5) * step


def _strike_for(rule, side, spot, step):
    a = _atm(spot, step)
    return (a - side * rule.money * step).astype(np.int64)


def _vol5(Vrow, c):
    """Volume of the 5 bars before column c."""
    lo = max(c - 5, 0)
    x = Vrow[lo:c]
    return float(np.nansum(x)) if len(x) else 0.0


def _prem_strike(ch, right, side, ref, c, band):
    """Nearest-to-money OTM strike whose last price (traded in the last 5 minutes) at column c is in [lo, hi]."""
    K = ch.K
    otm = np.nonzero(K > ref if side > 0 else K < ref)[0]
    otm = otm[np.argsort(np.abs(K[otm] - ref))]
    cl = ch.c[right]
    lo, hi = band
    for i in otm:
        row = cl[i, max(c - 4, 0):c + 1]
        ok = ~np.isnan(row)
        if not ok.any():
            continue
        p = row[ok][-1]
        if lo - 1e-9 <= p <= hi + 1e-9:
            return int(K[i])
    return None


def _last_valid(a, c):
    x = a[:c + 1]
    ok = np.nonzero(~np.isnan(x))[0]
    return x[ok[-1]] if len(ok) else np.nan


def _norm_signals(signals):
    sig = signals.copy().reset_index(drop=True)
    if "book" not in sig:
        sig["book"] = "main"
    if "gate" not in sig:
        sig["gate"] = sig.sig_min + 1
    for c in ("ref_spot", "strike", "idx_stop", "idx_target", "exit_at", "lot"):
        if c not in sig:
            sig[c] = np.nan
    if "tag" not in sig:
        sig["tag"] = ""
    sig["cand"] = np.arange(len(sig))
    return sig


def prepare(signals, rule, exe, pool_k=0, window=None, seed=7, pool_same_side=False):
    """One signal set -> (Pack, pool Pack or None)."""
    return prepare_many([(signals, rule, exe, pool_k, window, pool_same_side)], seed=seed)[0]


def prepare_many(jobs, seed=7, store=None, pool_cap=30000, pool_total=400000):
    """Several jobs (signals, StrikeRule, Execution, pool_k, window, pool_same_side) in ONE pass over the data, sharing
    one Store. Returns [(Pack, pool Pack or None)] in job order. See the module doc for the candidate / pool rules.
    pool_cap / pool_total: at most this many random alternatives per job / in all (pool_k is lowered, never below 3)."""
    from .data import market
    mk = market()
    store = store or Store()
    rng = np.random.default_rng(seed)
    sigs = [_norm_signals(job[0]) for job in jobs]
    nlegs = []
    for s in sigs:
        nl = 2 if (s.side == 0).any() else 1
        if nl == 2 and not (s.side == 0).all():
            raise ValueError("a signal set is either all single-leg or all straddles")
        nlegs.append(nl)
    metas = [([], []) for _ in jobs]
    multi = [j for j, s in enumerate(sigs) if "exit_day" in s.columns]    # multi-day holds: see multiday.py
    single = [s.assign(jobid=j) for j, s in enumerate(sigs) if j not in multi]
    allsig = pd.concat(single, ignore_index=True) if single else pd.DataFrame()
    if allsig.empty and not multi:
        return [(Pack(pd.DataFrame(columns=["cand"]), store.done(), nl), None) for nl in nlegs]

    def cut(j, kind, und, d, ch, I, irow, lot_day, bse, step, sm, side, ref, strike, istop, itgt, xat, lot_o, book, gate,
            tag, cand, parent):
        rule, exe = jobs[j][1], jobs[j][2]
        csig = sm - C.OPEN_M
        if csig < 0 or csig >= C.W - 1:
            return
        if not np.isfinite(ref):
            ref = _last_valid(I["c"], csig)
            if not np.isfinite(ref):
                return
        if side == 0:
            kc = int(strike) if np.isfinite(strike) else int(_strike_for(rule, 1, ref, step))
            kp = int(strike) if np.isfinite(strike) else int(_strike_for(rule, -1, ref, step))
            legs = [("C", kc), ("P", kp)]
        else:
            right = "C" if side > 0 else "P"
            if np.isfinite(strike):
                k = int(strike)
            elif rule.prem_band:
                k = _prem_strike(ch, right, side, ref, csig, rule.prem_band)
                if k is None:
                    return
            else:
                k = int(_strike_for(rule, side, ref, step))
            legs = [(right, k)]
        rows = []
        for right, k in legs:
            i = ch.kpos(k)
            if i < 0:
                return
            rows.append((ch.o[right][i], ch.h[right][i], ch.l[right][i], ch.c[right][i], ch.v[right][i]))
        cf = sm + 1 - C.OPEN_M
        ok = np.ones(C.W, bool)
        for r in rows:
            ok &= ~np.isnan(r[0])
        nz = np.nonzero(ok[cf:cf + exe.max_delay + 1])[0]
        if not len(nz):
            return
        c0 = cf + int(nz[0])
        lot = int(lot_o) if np.isfinite(lot_o) else lot_day
        es = []
        for r in rows:
            if exe.min_vol_lots and np.nansum(r[4][:c0]) < exe.min_vol_lots * lot:
                return
            v5 = _vol5(r[4], c0) / lot
            es.append(float(exe.fills.buy(np.array([round(float(r[0][c0]), 2)]), np.array([v5]))[0]))
        e = sum(es)
        if e <= exe.min_premium:
            return
        qty = exe.lots * lot
        if exe.budget:
            n = int(np.floor(exe.budget / (e * lot) + 1e-9))
            if n <= 0:
                return
            qty = n * lot
        crows = [store.contract((und, d, ch.series, k, right), r) for (right, k), r in zip(legs, rows)]
        metas[j][0 if kind == "real" else 1].append(dict(
            cand=cand, parent=parent, und=und, day=d, dn=dnum(d), book=book, gate=gate, sig_min=sm, side=side,
            right="".join(r for r, _ in legs), strike=legs[0][1], strike2=legs[-1][1], lot=lot, qty=qty, c0=c0, e=e,
            e1=es[0], e2=es[-1] if len(es) > 1 else np.nan, ref=ref, idx_stop=istop, idx_target=itgt, exit_at=xat, tag=tag,
            bse=bse, crow=crows[0], crow2=crows[-1], irow=irow))

    tot = max(sum(len(s) for s, job in zip(sigs, jobs) if job[3]), 1)
    kcap = [min(job[3], max(3, pool_cap // max(len(s), 1), 0), max(3, pool_total // tot)) if job[3] else 0
            for job, s in zip(jobs, sigs)]
    if multi:
        from .multiday import build_multi
        build_multi(jobs, sigs, kcap, multi, store, rng, metas)
    for und, g in (allsig.groupby("und", sort=False) if not allsig.empty else []):
        ix = mk.index(und)
        opts = mk.options(und)
        M = ix.mat()
        step = C.STEP[und]
        bse = und in C.BSE
        wins = {}
        for j in g.jobid.unique():
            gj = g[g.jobid == j]
            wins[j] = jobs[j][4] if jobs[j][4] else (int(gj.sig_min.min()), int(gj.sig_min.max()))
        for d, gd in g.groupby("day", sort=True):
            p = ix.pos.get(d)
            if p is None:
                continue
            xd = ix.d[d]
            I = {k: M[k][p] for k in ("c", "h", "l")}
            irow = None
            chains = {}
            for r in gd.itertuples(index=False):
                j = int(r.jobid)
                exe, pool_k, pool_same_side = jobs[j][2], kcap[j], jobs[j][5]
                if (exe.expiry == "skip" and xd["exp"]) or (exe.expiry == "only" and not xd["exp"]) or (exe.real_index and not xd["real"]):
                    continue
                if irow is None:
                    irow = store.index((und, d), I)
                lot_day = ix.lot(d, exe.lot_mode)
                ser = jobs[j][1].series
                if ser not in chains:
                    chains[ser] = opts.chain(d, ser)
                ch = chains[ser]
                if ch is None:
                    continue
                args = (und, d, ch, I, irow, lot_day, bse, step)
                cut(j, "real", *args, int(r.sig_min), int(r.side), float(r.ref_spot), float(r.strike), float(r.idx_stop),
                    float(r.idx_target), float(r.exit_at), float(r.lot), r.book, int(r.gate), r.tag, int(r.cand), -1)
                lo_w, hi_w = wins[j]
                ref0 = r.ref_spot if np.isfinite(r.ref_spot) else _last_valid(I["c"], int(r.sig_min) - C.OPEN_M)
                for _ in range(pool_k):
                    sm = int(rng.integers(lo_w, hi_w + 1))
                    sd = int(r.side) if (pool_same_side or r.side == 0) else int(rng.choice((1, -1)))
                    ref1 = _last_valid(I["c"], sm - C.OPEN_M)
                    ist = itg = xat = np.nan
                    if np.isfinite(r.idx_stop) and r.side != 0:
                        ist = ref1 - sd * (r.side * (ref0 - r.idx_stop))
                    if np.isfinite(r.idx_target) and r.side != 0:
                        itg = ref1 + sd * (r.side * (r.idx_target - ref0))
                    if np.isfinite(r.exit_at):
                        xat = sm + (r.exit_at - r.sig_min)
                    cut(j, "pool", *args, sm, sd, ref1, np.nan, ist, itg, xat, float(r.lot), r.book, sm + 1, "",
                        int(r.cand), int(r.cand))
        mk.release(und)
    store.done()
    out = []
    for j, (real, pool) in enumerate(metas):
        rp = Pack(pd.DataFrame(real) if real else pd.DataFrame(columns=["cand"]), store, nlegs[j])
        pp = Pack(pd.DataFrame(pool), store, nlegs[j]) if pool else None
        out.append((rp, pp))
    return out


# ------------------------------------------------------------------------------------------------ exits
def _first(mask):
    """First True column per row, W if none."""
    any_ = mask.any(axis=1)
    return np.where(any_, mask.argmax(axis=1), C.W)


def run_exits(pk: Pack, sl: slice, ex: Exits, exe: Execution) -> pd.DataFrame:
    meta = pk.meta.iloc[sl].reset_index(drop=True)
    N = len(meta)
    if N == 0:
        return empty_trades()
    g = lambda k: pk.get(k, sl)  # noqa: E731
    two = pk.nleg == 2
    O1, Cl1 = g("O"), g("Cl")
    if two:
        O2, Cl2 = g("O2"), g("Cl2")
        O, Cl = O1 + O2, Cl1 + Cl2
        H = L = None
        intrabar = False
    else:
        O, Cl, H, L = O1, Cl1, g("H"), g("L")
        intrabar = ex.intrabar
    IH, IL = g("IH"), g("IL")
    c0 = meta.c0.values.astype(np.int64)
    e = meta.e.values.astype(np.float64)
    qty = meta.qty.values.astype(np.float64)
    side = meta.side.values.astype(np.int64)
    ref = meta.ref.values.astype(np.float64)
    rows = np.arange(N)
    cols = np.arange(C.W)[None, :]
    valid = ~np.isnan(Cl)
    after = cols >= c0[:, None]
    sqc = ex.sq_off - C.OPEN_M
    barok = valid & after & (cols < sqc)

    # premium stop and target
    trig = np.full(N, np.nan)
    if ex.stop_pct:
        t = floor_tick(e * (1 - ex.stop_pct))
        trig = np.where((t >= C.TICK) & (t < e), t, np.nan)
    if ex.stop_pts:
        t = floor_tick(e - ex.stop_pts)
        t = np.where((t > C.TICK + 1e-9) & (t < e), t, np.nan)
        trig = np.fmax(trig, t)
    tgt = np.full(N, np.nan)
    if ex.tgt_pts:
        tgt = e + ex.tgt_pts
    if ex.tgt_pct:
        tgt = np.fmin(tgt, e * (1 + ex.tgt_pct))

    # profit lock / trailing levels from the peak BEFORE each bar
    lock = None
    if ex.ladder or ex.pine_trail or ex.trail_pct:
        src = H if intrabar else Cl
        hm = np.where(barok, src, NEG)
        inc = np.maximum.accumulate(hm, axis=1)
        peak = np.empty_like(inc)
        peak[:, 0] = NEG
        peak[:, 1:] = inc[:, :-1]
        peak = np.maximum(peak, e[:, None])
        lock = np.full((N, C.W), NEG)
        be = e + np.maximum(exe.costs.rt_per_unit(e, qty, meta.dn.values, False), 0.0) if (ex.be_floor or ex.pine_trail) else e
        if ex.ladder:
            R = np.full(N, ex.ladder_ref_pts) if ex.ladder_ref_pts else e * ex.ladder_ref_pct
            for a, b in ex.ladder:
                lv = np.maximum(e + b * R, be) if ex.be_floor else e + b * R
                armed = peak >= (e + a * R - 1e-9)[:, None]
                lock = np.maximum(lock, np.where(armed & (lv[:, None] < peak), lv[:, None], NEG))
        if ex.pine_trail:
            gain = (peak - e[:, None]) / e[:, None] * 100
            up = peak > e[:, None]
            lock = np.maximum(lock, np.where(up & (gain >= PINE_BE - 1e-9) & (be[:, None] < peak), be[:, None], NEG))
            for s, k in PINE_STEPS:
                lock = np.maximum(lock, np.where(up & (gain >= s - 1e-9), e[:, None] + k / 100 * (peak - e[:, None]), NEG))
        if ex.trail_pct:
            lv = floor_tick(peak * (1 - ex.trail_pct))
            armed = peak >= (e * (1 + ex.trail_arm) - 1e-9)[:, None]
            lock = np.maximum(lock, np.where(armed & (lv < peak), lv, NEG))
    trig_m = np.where(np.isnan(trig), NEG, trig)[:, None]
    rest = trig_m if lock is None else np.maximum(trig_m, lock)

    BIG = C.W
    if intrabar:
        hit_rest = barok & (L <= rest)
        hit_tgt = barok & (H >= tgt[:, None])
        kb = _first(hit_rest | hit_tgt)
        kc_rest = kc_tgt = np.full(N, BIG)
    else:
        kb = np.full(N, BIG)
        okc = barok & (cols <= sqc - 2)
        kc_rest = _first(okc & (Cl <= rest))
        kc_tgt = _first(okc & (Cl >= tgt[:, None]))

    # minute-close decisions, filled at the next open
    okm = after & (cols <= sqc - 2)
    lvl = np.full(N, np.nan)
    tl = np.full(N, np.nan)
    if ex.sig_levels:
        lvl = meta.idx_stop.values.astype(np.float64)
        tl = meta.idx_target.values.astype(np.float64)
    if ex.idx_stop_pts:
        l2 = ref - side * ex.idx_stop_pts
        lvl = np.where(side > 0, np.fmax(lvl, l2), np.fmin(lvl, l2))
    if ex.idx_tgt_pts:
        t2 = ref + side * ex.idx_tgt_pts
        tl = np.where(side > 0, np.fmin(tl, t2), np.fmax(tl, t2))
    if ex.idx_tgt_r:
        risk = np.abs(ref - lvl)
        t2 = ref + side * ex.idx_tgt_r * risk
        tl = np.where(side > 0, np.fmin(tl, t2), np.fmax(tl, t2))
    lvl = np.where(side == 0, np.nan, lvl)
    tl = np.where(side == 0, np.nan, tl)
    k_is = _first(okm & np.where((side > 0)[:, None], IL < lvl[:, None], IH > lvl[:, None]))
    k_it = _first(okm & np.where((side > 0)[:, None], IH >= tl[:, None], IL <= tl[:, None]))
    k_ts = np.full(N, BIG)
    if ex.time_stop:
        kT = c0 + ex.time_stop - 1
        okT = kT <= sqc - 2
        lvc = np.maximum.accumulate(np.where(valid, cols, -1), axis=1)
        kTc = np.clip(kT, 0, C.W - 1)
        ltp = Cl[rows, np.maximum(lvc[rows, kTc], 0)]
        cond = np.ones(N, bool) if ex.time_gain is None else ltp < e * (1 + ex.time_gain) - 1e-9
        k_ts = np.where(okT & cond, kT, BIG)
    k_xa = np.full(N, BIG)
    if ex.sig_levels:
        xa = meta.exit_at.values.astype(np.float64)
        kx = np.where(np.isfinite(xa), np.maximum(np.nan_to_num(xa, nan=0).astype(np.int64) - 1 - C.OPEN_M, c0), BIG)
        k_xa = np.where(kx <= sqc - 2, kx, BIG)
    k_sq = np.maximum(sqc - 1, c0)
    stack = np.stack([k_is, k_ts, k_it, k_xa, kc_rest, kc_tgt, k_sq])
    which = stack.argmin(axis=0)
    km = stack[which, rows]
    names = np.array(["index_stop", "time_stop", "index_target", "exit_at", "stop", "target", "square_off"], dtype=object)

    bar = kb <= km
    why = np.where(bar, "", names[which]).astype(object)
    xcol = np.where(bar, kb, 0)
    raw1 = np.full(N, np.nan)
    raw2 = np.full(N, np.nan)
    stopfill = np.zeros(N, bool)
    # bar-level fills
    if bar.any():
        b = np.nonzero(bar)[0]
        k = kb[b]
        r_at = rest[b, k] if rest.ndim == 2 and rest.shape[1] > 1 else rest[b, 0]
        hr = L[b, k] <= r_at
        lk_at = lock[b, k] if lock is not None else np.full(len(b), NEG)
        is_stop = hr & (trig_m[b, 0] >= lk_at)
        is_lock = hr & ~is_stop
        o = O[b, k]
        raw1[b] = np.where(is_stop, np.minimum(trig_m[b, 0], o), np.where(is_lock, np.minimum(lk_at, o), np.maximum(tgt[b], o)))
        why[b] = np.where(is_stop, "stop", np.where(is_lock, "lock", "target"))
        stopfill[b] = is_stop
    # minute-level fills: the first bar at/after km + 1 (else the day's last bar's close)
    mm = np.nonzero(~bar)[0]
    if len(mm):
        nv = np.where(valid, cols, BIG)
        nv = np.minimum.accumulate(nv[:, ::-1], axis=1)[:, ::-1]
        lv = np.maximum.accumulate(np.where(valid, cols, -1), axis=1)[:, -1]
        k1 = np.minimum(km[mm] + 1, C.W - 1)
        nxt = np.where(km[mm] + 1 <= C.W - 1, nv[mm, k1], BIG)
        has = nxt < BIG
        col = np.where(has, nxt, lv[mm])
        xcol[mm] = col
        if two:
            raw1[mm] = np.where(has, O1[mm, col], Cl1[mm, col])
            raw2[mm] = np.where(has, O2[mm, col], Cl2[mm, col])
        else:
            raw1[mm] = np.where(has, O[mm, col], Cl[mm, col])
    # fills, charges
    lot = meta.lot.values.astype(np.float64)

    def v5(V, c):
        cs = np.concatenate([np.zeros((N, 1)), np.cumsum(np.nan_to_num(V), axis=1)], axis=1)
        return (cs[rows, c] - cs[rows, np.maximum(c - 5, 0)]) / lot

    fl, co = exe.fills, exe.costs
    dn = meta.dn.values
    bse = bool(meta.bse.iloc[0])
    vv = v5(g("V"), xcol) if fl.mode == "liq" else None
    x1 = fl.sell(raw1, vv, stop=False)
    if stopfill.any():
        x1 = np.where(stopfill, fl.sell(raw1, vv, stop=True), x1)
    e1 = meta.e1.values.astype(np.float64)
    if two:
        vv2 = v5(g("V2"), xcol) if fl.mode == "liq" else None
        x2 = fl.sell(raw2, vv2)
        e2 = meta.e2.values.astype(np.float64)
        gross = (x1 - e1 + x2 - e2) * qty
        chg = (co.charge(True, e1, qty, dn, bse) + co.charge(False, x1, qty, dn, bse) + co.charge(True, e2, qty, dn, bse)
               + co.charge(False, x2, qty, dn, bse))
        xtot = x1 + x2
    else:
        gross = (x1 - e1) * qty
        if getattr(exe, "exact", False):
            chg = np.array([co.charge_exact(True, a, q, d, bse) + co.charge_exact(False, b, q, d, bse)
                            for a, b, q, d in zip(e1, x1, qty, dn)])
        else:
            chg = co.charge(True, e1, qty, dn, bse) + co.charge(False, x1, qty, dn, bse)
        xtot = x1
    net = gross - chg
    risk = np.where(np.isfinite(trig), (e - trig) * qty, e * qty) + co.charge(True, e1, qty, dn, bse) * (2 if two else 1)
    out = meta[["cand", "parent", "und", "book", "day", "sig_min", "gate", "side", "right", "strike", "lot", "qty", "tag"]].copy()
    out["year"] = [d.year for d in meta.day]
    out["entry_min"] = c0 + C.OPEN_M
    out["exit_min"] = xcol + C.OPEN_M
    out["entry"] = e
    out["exit"] = xtot
    out["why"] = why
    out["gross"] = gross
    out["charges"] = chg
    out["net"] = net
    out["risk_rs"] = risk
    out["R"] = net / risk
    return out[c0 < sqc - 1].reset_index(drop=True)      # no entries at / after the square-off


# ------------------------------------------------------------------------------------------------ position rules
def positions(tr: pd.DataFrame, one_at_a_time=True, max_per_day=None, day_loss=None):
    """Keep the trades a book could actually take: flat again the minute after an exit, at most max_per_day entries a
    day per book, no new entry once the book's day is down day_loss rupees."""
    if tr.empty or (not one_at_a_time and not max_per_day and not day_loss):
        return tr
    t = tr.sort_values(["book", "day", "gate", "cand"], kind="stable")
    keep = np.zeros(len(t), bool)
    book, day, gate = t.book.values, t.day.values, t.gate.values
    xm, net = t.exit_min.values, t.net.values
    cur = None
    flat = n = 0
    pnl = 0.0
    for i in range(len(t)):
        k = (book[i], day[i])
        if k != cur:
            cur, flat, n, pnl = k, 0, 0, 0.0
        if one_at_a_time and gate[i] < flat:
            continue
        if max_per_day and n >= max_per_day:
            continue
        if day_loss and pnl <= -day_loss:
            continue
        keep[i] = True
        flat = xm[i] + 1
        n += 1
        pnl += net[i]
    return t[keep].sort_values(["day", "entry_min", "book"], kind="stable").reset_index(drop=True)


def pack_key(*parts):
    return hashlib.sha1(repr(parts).encode()).hexdigest()[:12]
