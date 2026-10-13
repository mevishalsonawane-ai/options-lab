"""R17 shared code: the session grids, costs, the trade replay with the arm's own exits plus one variant, the index
features the variants read (VWAP bands, levels, R16's expected range), and the statistics (PREREG.md).

Minute k = the 1-minute bar starting 09:15 + k (0..374). A decision on minute k's CLOSE is filled at the OPEN of the next
printed minute. Resting stops fill at the stop or the gap open. Within a minute: stop first, then target, then the
close-based exits.
"""
from __future__ import annotations

import math
import os
import pickle
import sys
from dataclasses import dataclass, field
from datetime import date

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(ROOT, "research"))
sys.path.insert(0, HERE)

import config as K  # noqa: E402
from obuy.config import LOT_SCHEDULE  # noqa: E402
from obuy.costs import Costs, adverse_bps  # noqa: E402

N = 375
TICK = 0.05
COSTS = Costs("app")
STEP = {"BANKNIFTY": 100, "NIFTY": 50}
ROUND = {"BANKNIFTY": 500, "NIFTY": 100}
SQ_1510 = 15 * 60 + 10 - 555          # 355
SQ_1515 = 15 * 60 + 15 - 555          # 360
SQ_1505 = 15 * 60 + 5 - 555           # 350
VARIANTS = ["V0", "V1", "V2", "V3", "V4", "V5"]
VNAME = {"V0": "fixed (today's)", "V1": "VWAP band trail", "V2": "next support/resistance", "V3": "time stop 45 min",
         "V4": "expected range used up", "V5": "breakeven after +15%"}
# R16 frozen 4-input formula, next 30 minutes (HUNT_R16_PREDICTABILITY.md "Portable formula")
E30 = {"BANKNIFTY": (-2.491, 0.159, 0.261, 0.292, 0.234), "NIFTY": (-2.702, 0.101, 0.272, 0.320, 0.239)}


def floor_tick(x):
    return math.floor(x / TICK + 1e-9) * TICK


def ceil_tick(x):
    return math.ceil(x / TICK - 1e-9) * TICK


def lot_of(und, d):
    lot = None
    for since, n in LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(since):
            lot = n
    return lot


# ------------------------------------------------------------------------------------------------ grids
class Grid:
    """All sessions of one index (data.py output)."""

    def __init__(self, und, days=None):
        self.und = und
        if days is None:
            with open(K.GRIDS / f"{und}.pkl", "rb") as f:
                days = pickle.load(f)
        self.days = days
        self.dates = [d["day"] for d in days]
        exps = set()
        for d in days:
            exps.update(d["chains"].keys())
        self.expiry_dates = exps
        self._feat = {}
        self.allowed = None          # set by the runners: the sessions an arm may trade (design or holdout)

    def ok(self, i):
        return self.allowed is None or self.dates[i] in self.allowed

    def is_expiry(self, i):
        return self.dates[i] in self.expiry_dates

    def chain(self, i, today_ok=True):
        """(expiry, strikes, arr) of the contract the arms trade: today's on an expiry day (today_ok), else the nearest
        after today. None when the data lacks it (an expiry day without the expiring chain)."""
        d = self.days[i]
        for e in d["expiries"]:
            if e == d["day"] and not today_ok:
                continue
            if e >= d["day"]:
                if self.is_expiry(i) and today_ok and e != d["day"]:
                    return None
                ks, arr = d["chains"][e]
                return e, ks, arr
        return None

    def opt(self, i, strike, side, today_ok=True):
        """[N, 6] O H L C V OI of one contract, or None."""
        c = self.chain(i, today_ok)
        if c is None:
            return None
        e, ks, arr = c
        j = np.searchsorted(ks, strike)
        if j >= len(ks) or abs(ks[j] - strike) > 1e-6:
            return None
        a = arr[:, j, 0 if side > 0 else 1, :].astype(np.float64)
        if not np.isfinite(a[:, 3]).any():
            return None
        return a

    # --- index features the variants read (point in time) ---------------------------------------------------------
    def vwap(self, i):
        """(vwap, sd) per minute: session VWAP of the typical price and its volume-weighted SD, NaN before 09:30."""
        key = ("vwap", i)
        if key in self._feat:
            return self._feat[key]
        d = self.days[i]
        ix = d["ix"]
        tp = (ix[:, 1] + ix[:, 2] + ix[:, 3]) / 3.0
        w = d["fut_vol"].copy() if np.nansum(d["fut_vol"]) > 0 else None
        if w is None:                                  # release: ATM +-2 option volume (a proxy, labelled)
            c = self.chain(i) or self.chain(i, today_ok=False)
            w = np.zeros(N)
            if c is not None:
                _, ks, arr = c
                step = STEP[self.und]
                cl = ix[:, 3]
                for k in range(N):
                    if not np.isfinite(cl[k]):
                        continue
                    atm = round(cl[k] / step) * step
                    sel = np.abs(ks - atm) <= 2 * step + 1e-6
                    w[k] = np.nansum(arr[k, sel, :, 4])
        w = np.where(np.isfinite(w) & np.isfinite(tp), w, 0.0)
        cw = np.cumsum(w)
        cwp = np.cumsum(w * np.nan_to_num(tp))
        cwp2 = np.cumsum(w * np.nan_to_num(tp) ** 2)
        with np.errstate(invalid="ignore", divide="ignore"):
            vw = cwp / cw
            sd = np.sqrt(np.maximum(cwp2 / cw - vw ** 2, 0.0))
        vw[:15] = np.nan
        sd[:15] = np.nan
        vw[cw <= 0] = np.nan
        self._feat[key] = (vw, sd)
        return vw, sd

    def prev(self, i):
        return self.days[i - 1] if i > 0 else None

    def vix_prev(self, i):
        p = self.prev(i)
        if p is None:
            return np.nan
        v = p["vix"][np.isfinite(p["vix"])]
        return float(v[-1]) if len(v) else np.nan

    def e30(self, i, k):
        """R16's expected next-30-minute index range in points, decided on minute k-1's close (entry at k)."""
        ix = self.days[i]["ix"]
        k1 = k - 1
        if k1 < 4:
            return np.nan
        px = ix[k1, 3]
        lo30 = max(0, k1 - 29)
        lo60 = max(0, k1 - 59)
        r30 = (np.nanmax(ix[lo30:k1 + 1, 1]) - np.nanmin(ix[lo30:k1 + 1, 2])) / px
        r60 = (np.nanmax(ix[lo60:k1 + 1, 1]) - np.nanmin(ix[lo60:k1 + 1, 2])) / px
        tods = []
        for j in range(max(0, i - 20), i):
            a = self.days[j]["ix"]
            hi = np.nanmax(a[k:k + 30, 1]) if k < N else np.nan
            lo = np.nanmin(a[k:k + 30, 2]) if k < N else np.nan
            ref = a[k1, 3]
            if np.isfinite(hi) and np.isfinite(lo) and hi > lo and np.isfinite(ref):
                tods.append(math.log((hi - lo) / ref))
        vix = self.vix_prev(i)
        if len(tods) < 5 or not (r30 > 0 and r60 > 0 and vix > 0):
            return np.nan
        c0, a, b, c, d = E30[self.und]
        return math.exp(c0 + a * float(np.mean(tods)) + b * math.log(r30) + c * math.log(r60) + d * math.log(vix)) * px

    def levels(self, i, k):
        """Support/resistance levels known at minute k-1's close (V2)."""
        ix = self.days[i]["ix"]
        out = []
        p = self.prev(i)
        if p is not None:
            H, L, C = np.nanmax(p["ix"][:, 1]), np.nanmin(p["ix"][:, 2]), p["ix"][np.isfinite(p["ix"][:, 3]), 3][-1]
            P = (H + L + C) / 3
            out += [H, L, C, P, 2 * P - L, 2 * P - H, P + (H - L), P - (H - L)]
        if k - 1 >= 49:
            out += [np.nanmax(ix[:50, 1]), np.nanmin(ix[:50, 2])]
        if k - 1 >= 0:
            out += [np.nanmax(ix[:k, 1]), np.nanmin(ix[:k, 2])]
        px = ix[k - 1, 3]
        r = ROUND[self.und]
        base = math.floor(px / r) * r
        out += [base + j * r for j in range(-4, 6)]
        return np.array([x for x in out if np.isfinite(x)])


# ------------------------------------------------------------------------------------------------ trades
@dataclass
class Trade:
    arm: str
    und: str
    i: int                       # session index in the Grid
    side: int                    # +1 CE, -1 PE
    strike: float
    c0: int                      # entry minute (filled at its open)
    e: float                     # entry fill price (costs included)
    qty: int
    path: np.ndarray             # [N, 6] option O H L C V OI
    sq: int                      # square-off minute (sold at its open)
    stop0: float = float("nan")  # resting stop trigger
    tgt: float = float("inf")    # premium target level
    ladder: tuple = ()           # ((reach points, lock points), ...) earned on highs, from the next minute
    pine_trail: bool = False
    time_rule: tuple | None = None   # (minute offset n, gain g): at the close of c0 + n exit if close < e(1+g)
    close_stop: float | None = None  # Hero: a close <= this -> out (from c0 + 2)
    hero: bool = False
    arm_exit: int | None = None      # the arm's own index/signal exit: decision minute (close), filled next open
    ix_entry: float = float("nan")   # index close of the decision minute
    hs: float = 0.0016               # half-spread (fraction); Hero uses ticks instead
    meta: dict = field(default_factory=dict)


def buy_px(p, hs, hero=False):
    x = float(adverse_bps(np.array([p]), 5, True)[0])
    return round(x + TICK, 2) if hero else round(x * (1 + hs), 2)


def sell_px(p, hs, stop=False, hero=False):
    x = float(adverse_bps(np.array([p]), 10 if stop else 5, False)[0])
    return max(round(x - TICK, 2), 0.0) if hero else max(round(x * (1 - hs), 2), 0.0)


def charges(buy, px, qty):
    return float(COSTS.charge(buy, np.array([px]), np.array([qty]))[0])


def be_level(t: Trade):
    """V5: the sell price that nets zero after charges and the spread."""
    cpu = (charges(True, t.e, t.qty) + charges(False, t.e, t.qty)) / t.qty
    f = (1 - 0.001) * (1 - (0 if t.hero else t.hs))
    return ceil_tick((t.e + cpu + (TICK if t.hero else 0.0)) / f)


def next_open(path, t, sq):
    """(price, minute) of the first printed open after minute t (at most up to the last bar)."""
    o = path[:, 0]
    for m in range(t + 1, N):
        if np.isfinite(o[m]):
            return o[m], m
    cl = path[:, 3]
    j = np.where(np.isfinite(cl))[0]
    return (cl[j[-1]], int(j[-1])) if len(j) else (np.nan, t)


def replay(t: Trade, extra: int | None = None, be: bool = False, hs_mult: float = 1.0):
    """Replays one trade. extra = a variant's decision minute (close), be = V5. Returns a dict."""
    hs = t.hs * hs_mult
    O, H, L, C = (t.path[:, j] for j in range(4))
    e = t.e
    qty_left = t.qty
    sold = []                     # (price, qty, minute, why)
    stop = t.stop0
    peak = e
    be_px = be_level(t) if be else None
    decide = [m for m in (t.arm_exit, extra) if m is not None]
    first_decide = min(decide) if decide else None
    half_done = False
    last_c = e
    k = t.c0
    timed = t.time_rule is None
    while k < N:
        if k >= t.sq:
            p, m = (O[k], k) if np.isfinite(O[k]) else next_open(t.path, k, t.sq)
            sold.append((sell_px(p, hs, hero=t.hero), qty_left, m, "square_off"))
            qty_left = 0
            break
        printed = np.isfinite(O[k])
        if printed:
            if np.isfinite(stop) and L[k] <= stop:
                sold.append((sell_px(min(stop, O[k]), hs, stop=True, hero=t.hero), qty_left, k, "stop"))
                qty_left = 0
                break
            if t.hero and not half_done and H[k] >= floor_tick(5 * e) and k > t.c0:
                q = t.qty // t.meta["lot"]
                half = int(round(q / 2)) * t.meta["lot"]          # round-half-even on lots
                if half > 0:
                    sold.append((sell_px(max(floor_tick(5 * e), O[k]), hs, hero=True), half, k, "5x"))
                    qty_left -= half
                half_done = True
                if qty_left <= 0:
                    break
            if H[k] >= t.tgt:
                sold.append((sell_px(max(t.tgt, O[k]), hs, hero=t.hero), qty_left, k, "target"))
                qty_left = 0
                break
            peak = max(peak, H[k])
            # locks earned on this minute's high count from the next minute
            for reach, lock in t.ladder:
                if peak >= e + reach:
                    lv = e + lock
                    if lock == 0:
                        lv = e + max(0.0, (charges(True, e, t.qty) + charges(False, e, t.qty)) / t.qty)
                    stop = max(stop, ceil_tick(lv)) if np.isfinite(stop) else ceil_tick(lv)
            if t.pine_trail:
                g = peak / e - 1
                lv = None
                if g >= 0.40:
                    lv = e + 0.75 * (peak - e)
                elif g >= 0.20:
                    lv = e + 0.65 * (peak - e)
                elif g >= 0.08:
                    lv = e + 0.50 * (peak - e)
                elif g >= 0.05:
                    lv = e + (charges(True, e, t.qty) + charges(False, e, t.qty)) / t.qty
                if lv is not None:
                    stop = max(stop, ceil_tick(lv)) if np.isfinite(stop) else ceil_tick(lv)
            if be_px is not None and peak >= 1.15 * e:
                stop = max(stop, be_px) if np.isfinite(stop) else be_px
            last_c = C[k]
        why = None
        if not timed and k >= t.c0 + t.time_rule[0]:
            timed = True
            if last_c < e * (1 + t.time_rule[1]) - 1e-9:
                why = "time_stop"
        if why is None and t.close_stop is not None and printed and k >= t.c0 + 2 and C[k] <= t.close_stop:
            why = "close_stop"
        if why is None and first_decide is not None and k >= first_decide:
            why = "arm_exit" if (t.arm_exit is not None and k >= t.arm_exit) else "variant"
        if why is not None:
            p, m = next_open(t.path, k, t.sq)
            sold.append((sell_px(p, hs, hero=t.hero), qty_left, m, why))
            qty_left = 0
            break
        k += 1
    if qty_left > 0:
        p, m = next_open(t.path, N - 2, t.sq)
        sold.append((sell_px(p, hs, hero=t.hero), qty_left, m, "last_bar"))
    gross = sum((p - e) * q for p, q, _, _ in sold)
    ch = charges(True, e, t.qty) + sum(charges(False, p, q) for p, q, _, _ in sold)
    end = max(m for _, _, m, _ in sold)
    return dict(net=gross - ch, gross=gross, charges=ch, exit_min=end, why=sold[-1][3],
                xavg=sum(p * q for p, q, _, _ in sold) / t.qty)


def mfe(t: Trade):
    """Best option high reachable from the entry minute to the arm's square-off, in rupees for the position."""
    h = t.path[t.c0:t.sq, 1]
    h = h[np.isfinite(h)]
    return max(float(h.max()) - t.e, 0.0) * t.qty if len(h) else 0.0


# ------------------------------------------------------------------------------------------------ variants
def variant_minute(g: Grid, t: Trade, v: str):
    """The decision minute (close) at which variant v exits, or None (V0 and V5 have none)."""
    ix = g.days[t.i]["ix"]
    s = t.side
    c0 = t.c0
    if v == "V3":
        return c0 + 44
    if v == "V1":
        vw, sd = g.vwap(t.i)
        kbest = None
        for k in range(c0 - 1, t.sq):
            c = ix[k, 3]
            if not (np.isfinite(c) and np.isfinite(vw[k]) and np.isfinite(sd[k]) and sd[k] > 0):
                continue
            z = (c - vw[k]) / sd[k] * s            # in the trade's direction
            reached = max([b for b in (-2, -1, 0, 1, 2) if z >= b], default=-3)
            kbest = reached if kbest is None else max(kbest, reached)
            line = kbest - 1
            if k >= c0 and z < line:
                return k
        return None
    if v in ("V2", "V4"):
        e30 = g.e30(t.i, c0)
        if not np.isfinite(e30):
            return None
        ref = t.ix_entry
        if v == "V2":
            lv = g.levels(t.i, c0)
            ahead = lv[(lv - ref) * s >= 0.25 * e30]
            if not len(ahead):
                return None
            target = ahead.min() if s > 0 else ahead.max()
            for k in range(c0, t.sq):
                if (s > 0 and ix[k, 1] >= target) or (s < 0 and ix[k, 2] <= target):
                    return k
            return None
        best = -np.inf
        for k in range(c0, t.sq):
            fav = (ix[k, 1] - ref) if s > 0 else (ref - ix[k, 2])
            if np.isfinite(fav):
                best = max(best, fav)
            if best >= e30:
                return k
        return None
    return None


def run_variant(g: Grid, trades, v: str, hs_mult=1.0):
    out = []
    for t in trades:
        x = variant_minute(g, t, v) if v not in ("V0", "V5") else None
        r = replay(t, extra=x, be=(v == "V5"), hs_mult=hs_mult)
        r["mfe"] = mfe(t)
        out.append(r)
    return out


# ------------------------------------------------------------------------------------------------ statistics
def bh(ps):
    ps = np.asarray(ps, dtype=float)
    n = len(ps)
    o = np.argsort(ps)
    q = np.empty(n)
    run = 1.0
    for r in range(n - 1, -1, -1):
        run = min(run, ps[o[r]] * n / (r + 1))
        q[o[r]] = run
    return q


def boot_p(day_delta, B=10000, seed=K.SEED):
    """One-sided (H1: mean > 0) day bootstrap of the mean per-day delta."""
    x = np.asarray(day_delta, dtype=float)
    if len(x) < 2 or np.allclose(x, 0):
        return 1.0
    rng = np.random.default_rng(seed)
    m = x.mean()
    bs = x[rng.integers(0, len(x), (B, len(x)))].mean(axis=1) - m
    return float((1 + np.sum(bs >= m)) / (B + 1))


def max_dd(daily):
    eq = np.cumsum(daily)
    peak = np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]
    return float((eq - peak).min()) if len(eq) else 0.0
