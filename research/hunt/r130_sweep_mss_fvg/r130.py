"""R130: Boss's sweep -> structure shift (MSS) -> fair value gap (FVG) video setup (PREREG.md), on the Drive lake, all
five indices, buying the ATM option of the nearest expiry that does not expire that day.

    python research/hunt/r130_sweep_mss_fvg/r130.py design     # design period -> results/design.json
    python research/hunt/r130_sweep_mss_fvg/r130.py holdout    # the ONE holdout read -> results/holdout.json
    python research/hunt/r130_sweep_mss_fvg/r130.py design wick    # Amendment 1 (wick-only) -> results/design_wick.json
    python research/hunt/r130_sweep_mss_fvg/r130.py holdout wick   # its one holdout read -> results/holdout_wick.json

Signal source: the index spot (the underlying). Every swing, sweep, MSS, FVG, pullback, engulfing candle, stop and
target is read on the index's bars (wicks for levels; bodies only for the engulfing candle and the MSS close). The
option is only what is bought.

Bearish setups run the bullish code on the mirrored index (high -> -low, low -> -high, open -> -open, close -> -close),
so one code path serves both sides.
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass, field
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r124_big_moves"))
import r124cfg as K  # noqa: E402  (puts r123 on the path)
import r123data as D3  # noqa: E402
import r123rules as R3  # noqa: E402

N = K.N
RESULTS = HERE / "results"
OUT = Path(os.environ.get("R130_OUT", K.K3.CACHE_ROOT / "r130")).expanduser()
TICK = 0.05
SESSIONS_BACK = 4                  # swings from today and the previous 4 sessions
N_MSS, M_PULL = 12, 12             # bars from the sweep to the MSS close; bars from the MSS to the trigger
FVG_MIN_PCT = 0.0001               # 0.01% of the index (or 2 ticks, the larger)
STOP_PCT = 0.0002                  # stop 0.02% beyond the FVG's far edge
EQ_TOL = 0.0003                    # equal highs / lows within 0.03%
LAST_ENTRY = K.row(15, 0)          # entry minute at or before 15:00
FIRST_TWIN = K.row(9, 20)
TWINS = 200
SEED = 130

VARIANTS = {
    "V1": dict(tf=5, engulf=True, target="eq", k=3, close_back=False, label="the rule as stated (5-min)"),
    "V2": dict(tf=1, engulf=True, target="eq", k=3, close_back=False, label="1-minute chart"),
    "V3": dict(tf=5, engulf=False, target="eq", k=3, close_back=False, label="no engulfing (first touch of the FVG)"),
    "V4": dict(tf=5, engulf=True, target="2R", k=3, close_back=False, label="fixed 2R target"),
    "V5": dict(tf=5, engulf=True, target="eq", k=5, close_back=False, label="longer swing lookback (5 bars each side)"),
    "V6": dict(tf=5, engulf=True, target="eq", k=3, close_back=True, label="sweep needs a close back inside"),
}
# Amendment 1 (PREREG_WICK.md, 29 Oct, after the results): wick-only versions, no candle open or close in any signal
WICK_VARIANTS = {
    "W1": dict(tf=5, engulf=True, target="eq", k=3, close_back=False, wick=True,
               label="the rule as stated, wick-only (5-min)"),
    "W2": dict(tf=5, engulf=False, target="eq", k=3, close_back=False, wick=True,
               label="wick-only, no confirmation (first touch of the FVG)"),
    "W3": dict(tf=1, engulf=True, target="eq", k=3, close_back=False, wick=True, label="wick-only, 1-minute chart"),
    "W4": dict(tf=5, engulf=True, target="2R", k=3, close_back=False, wick=True, label="wick-only, fixed 2R target"),
}
FAMILIES = {"orig": VARIANTS, "wick": WICK_VARIANTS}
GATES = dict(design_n=300, folds=3, q=0.10, holdout_n=50, win=0.65, dd=-40000.0, month=-13000.0)


# ------------------------------------------------------------------------------------------------ bars and swings
@dataclass
class Bars:
    """tf-minute bars of one index, in time order across sessions; only bars with data."""
    day: np.ndarray        # session index into the grid's days
    start: np.ndarray      # first minute row of the bar
    end: np.ndarray        # last minute row of the bar
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray


def _first(x: np.ndarray, axis: int) -> np.ndarray:
    ok = np.isfinite(x)
    i = np.argmax(ok, axis=axis)
    v = np.take_along_axis(x, np.expand_dims(i, axis), axis).squeeze(axis)
    return np.where(ok.any(axis), v, np.nan)


def _last(x: np.ndarray, axis: int) -> np.ndarray:
    return _first(np.flip(x, axis), axis)


def make_bars(o: np.ndarray, h: np.ndarray, l: np.ndarray, c: np.ndarray, tf: int) -> Bars:
    """Days x N minute arrays -> tf-minute bars aligned to 09:15 (bars with no minute data are left out)."""
    D = o.shape[0]
    nb = N // tf
    cut = nb * tf
    r = lambda x: x[:, :cut].reshape(D, nb, tf)  # noqa: E731
    with np.errstate(all="ignore"):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            H = np.nanmax(r(h), axis=2)
            L = np.nanmin(r(l), axis=2)
    O, C = _first(r(o), 2), _last(r(c), 2)
    ok = np.isfinite(H) & np.isfinite(L) & np.isfinite(O) & np.isfinite(C)
    di, bi = np.nonzero(ok)                      # row-major: time order
    return Bars(day=di, start=bi * tf, end=bi * tf + tf - 1, o=O[di, bi], h=H[di, bi], l=L[di, bi], c=C[di, bi])


def swing_highs(h: np.ndarray, k: int) -> np.ndarray:
    """Bars i whose high is strictly above the k highs before and at or above the k highs after (wicks)."""
    T = len(h)
    out = []
    for i in range(k, T - k):
        if h[i] > h[i - k:i].max() and h[i] >= h[i + 1:i + k + 1].max():
            out.append(i)
    return np.array(out, dtype=int)


# ------------------------------------------------------------------------------------------------ one side's scanner
@dataclass
class Setup:
    phase: str                 # "mss" or "pull"
    day: int
    s: int                     # sweep bar
    H: float                   # the recent high to close above
    m: int = -1                # MSS bar
    top: float = np.nan        # FVG near edge (bullish: top)
    bottom: float = np.nan     # FVG far edge
    stop: float = np.nan
    touched: bool = False


@dataclass
class Side:
    """The bullish logic on (hh, ll, oo, cc): the real index for buys, the mirrored index for sells."""
    B: Bars
    hh: np.ndarray
    ll: np.ndarray
    oo: np.ndarray
    cc: np.ndarray
    k: int
    engulf: bool
    close_back: bool
    wick: bool = False                             # Amendment 1: wick MSS and wick engulfing (no opens / closes)
    stop_mode: str = "fvg"                         # Amendment 3: "sweep" = beyond the MSS leg's extreme (wider)
    hi_avail: dict = field(default_factory=dict)   # bar t -> swing-high pivots known from bar t on
    lo_avail: dict = field(default_factory=dict)
    highs: list = field(default_factory=list)      # (level, day) of known swing highs, last 5 sessions
    lows: list = field(default_factory=list)       # (level, day) of known swing lows not taken out yet
    pend: Setup | None = None
    census: dict = field(default_factory=lambda: dict(sweeps=0, mss=0, fvg=0, touched=0, triggered=0))

    def __post_init__(self):
        for i in swing_highs(self.hh, self.k):
            self.hi_avail.setdefault(int(i) + self.k + 1, []).append(int(i))
        for i in swing_highs(-self.ll, self.k):           # swing lows: the same rule on the negated lows
            self.lo_avail.setdefault(int(i) + self.k + 1, []).append(int(i))

    def new_bar(self, t: int, day_changed: bool):
        d = self.B.day[t]
        if day_changed:
            self.pend = None
            self.highs = [x for x in self.highs if x[1] >= d - SESSIONS_BACK]
            self.lows = [x for x in self.lows if x[1] >= d - SESSIONS_BACK]
        for i in self.hi_avail.get(t, ()):
            if self.B.day[i] >= d - SESSIONS_BACK:
                self.highs.append((float(self.hh[i]), int(self.B.day[i])))
        for i in self.lo_avail.get(t, ()):
            if self.B.day[i] >= d - SESSIONS_BACK:
                # taken out already between its pivot and now? (its own right-hand bars cannot, by the pivot rule)
                lv = float(self.ll[i])
                if not (self.ll[i + 1:t] <= lv - TICK).any():
                    self.lows.append((lv, int(self.B.day[i])))

    def after_bar(self, t: int):
        """Swing lows taken out by this bar's wick are no longer liquidity."""
        x = self.ll[t]
        self.lows = [lv for lv in self.lows if not x <= lv[0] - TICK]

    def is_engulfing(self, t: int) -> bool:
        if t < 1 or self.B.day[t - 1] != self.B.day[t]:
            return False
        if self.wick:      # its wicks engulf the previous candle's and it takes out the previous high
            return bool(self.hh[t] > self.hh[t - 1] and self.ll[t] <= self.ll[t - 1])
        o0, c0, o1, c1 = self.oo[t - 1], self.cc[t - 1], self.oo[t], self.cc[t]
        return bool(c1 > o1 and c0 <= o0 and o1 <= min(o0, c0) and c1 >= max(o0, c0))

    def step(self, t: int, mlow: np.ndarray | None = None):
        """Process bar t (completed). Returns the entry minute row (the trigger) or None. `mlow` = this side's
        1-minute lows of the session (for V3's first-touch trigger)."""
        B, p = self.B, self.pend
        d = B.day[t]
        if p is not None and p.phase == "pull":
            if t > p.m + M_PULL:
                self.pend = p = None
            else:
                if self.engulf:
                    if not p.touched and self.ll[t] <= p.top:
                        p.touched = True
                        self.census["touched"] += 1
                    if p.touched:
                        if self.ll[t] <= p.stop:
                            self.pend = None
                            return None
                        if self.is_engulfing(t):
                            self.census["triggered"] += 1
                            return int(B.end[t]) + 1
                    return None
                for r in range(int(B.start[t]), int(B.end[t]) + 1):   # V3: the first 1-minute touch
                    if np.isfinite(mlow[r]) and mlow[r] <= p.top:
                        self.census["touched"] += 1
                        self.census["triggered"] += 1
                        return r + 1
                return None
        if p is not None and p.phase == "mss":
            if t > p.s + N_MSS:
                self.pend = p = None
            elif (self.hh[t] >= p.H + TICK) if self.wick else (self.cc[t] > p.H):
                self.census["mss"] += 1
                fv = find_fvg(self.hh, self.ll, p.s, t)
                if fv is None:
                    self.pend = None
                else:
                    self.census["fvg"] += 1
                    top, bottom = fv
                    edge = bottom
                    if self.stop_mode == "sweep":
                        edge = float(self.ll[p.s:t + 1].min())        # the lowest low of the leg (wick)
                    self.pend = Setup("pull", int(d), p.s, p.H, m=t, top=top, bottom=bottom,
                                      stop=edge - STOP_PCT * abs(edge))
                return None
        # a new sweep (only while no setup of this side waits for its pullback)
        self._sweep(t, d)
        return None

    def _sweep(self, t: int, d: int):
        """A wick sweep of the recent (untaken) swing low starts a setup (Amendment 2 overrides the level)."""
        if self.lows and self.highs:
            L = self.lows[-1][0]
            H = self.highs[-1][0]
            if H > L and self.ll[t] <= L - TICK and (not self.close_back or self.cc[t] > L):
                self.census["sweeps"] += 1
                self.pend = Setup("mss", int(d), t, H)

    def target(self, ref: float, stop: float, how: str) -> float | None:
        if how == "2R":
            return ref + 2 * (ref - stop)
        return eq_target([x[0] for x in self.highs], ref)


def find_fvg(hh: np.ndarray, ll: np.ndarray, s: int, m: int):
    """The largest bullish three-bar gap inside the MSS leg (from the lowest low between s and m, to m):
    ll[j+1] - hh[j-1] >= max(2 ticks, 0.01% of price). Returns (top, bottom) or None; ties: the latest."""
    a = s + int(np.argmin(ll[s:m + 1]))
    best, out = -1.0, None
    for j in range(a + 1, m):
        gap = ll[j + 1] - hh[j - 1]
        if gap >= max(2 * TICK, FVG_MIN_PCT * abs(ll[j + 1])) and gap >= best:
            best, out = gap, (float(ll[j + 1]), float(hh[j - 1]))
    return out


def eq_target(highs: list, ref: float) -> float | None:
    """The nearest equal highs above ref (two swing highs within 0.03%: the higher of the pair), else the nearest
    swing high above ref, else None."""
    lv = sorted(x for x in highs if x > ref)
    for a, b in zip(lv, lv[1:]):
        if b - a <= EQ_TOL * abs(a):
            return b
    return lv[0] if lv else None


def exit_on(mh: np.ndarray, ml: np.ndarray, mo: np.ndarray, r0: int, stop: float, tgt: float, last: int):
    """(exit row, why, exit price) on one side's minute wicks from r0 to last; the stop first if both in a minute."""
    hseg, lseg = mh[r0:last + 1], ml[r0:last + 1]
    ok = np.isfinite(hseg) & np.isfinite(lseg)
    if not ok.any():
        return None
    s_hit = np.flatnonzero(ok & (lseg <= stop))
    t_hit = np.flatnonzero(ok & (hseg >= tgt))
    s0 = s_hit[0] if len(s_hit) else 10 ** 9
    t0 = t_hit[0] if len(t_hit) else 10 ** 9
    if s0 <= t0 and s0 < 10 ** 9:
        r = r0 + int(s0)
        px = min(stop, mo[r]) if np.isfinite(mo[r]) else stop      # a gap through the stop fills at the open
        return r, "stop", float(px)
    if t0 < 10 ** 9:
        return r0 + int(t0), "target", float(tgt)
    r = r0 + int(np.flatnonzero(ok)[-1])
    return r, "time", float((mh[r] + ml[r]) / 2)


# ------------------------------------------------------------------------------------------------ the scan
def mirror(g):
    """(h, l, o, c) minute arrays for buys (+1) and sells (-1, mirrored)."""
    return {1: (g.h, g.l, g.o, g.c), -1: (-g.l, -g.h, -g.o, -g.c)}


def scan(g, cfg: dict, blocked: set, days_keep, last_row: int = K.K3.EXIT_LAST, side_cls=None,
         entry_last: int = LAST_ENTRY, trend=None, min_stop_pct: float = 0.0,
         min_tgt_pct: float = 0.0) -> tuple[list, dict]:
    """All trades of one variant on one index (index only, no option yet): one trade at a time, both sides.
    `blocked` = session indices with no tradable contract; `days_keep(day)` -> keep the trade in the output.
    Amendment 2 hooks (defaults = the original rule): `side_cls` (the sweep's liquidity), `entry_last` (the last entry
    row), `trend(d, row) -> +1 / -1 / 0` (trades only in its direction), `min_stop_pct` (skip smaller stops)."""
    side_cls = side_cls or Side
    M = mirror(g)
    sides = {}
    for sd in (1, -1):
        mh, ml, mo, mc = M[sd]
        B = make_bars(mo, mh, ml, mc, cfg["tf"])
        sides[sd] = side_cls(B, B.h, B.l, B.o, B.c, cfg["k"], cfg["engulf"], cfg["close_back"],
                        cfg.get("wick", False))
        sides[sd].stop_mode = cfg.get("stop", "fvg")
    B = sides[1].B
    # both sides' bars are the same bars (mirroring keeps which bars have data)
    trades, skips = [], dict(no_target=0, no_room=0, late=0, both=0, no_contract=0, no_price=0)
    busy_day, busy_row = -1, -1
    prev_day = -1
    for t in range(len(B.day)):
        d = int(B.day[t])
        changed = d != prev_day
        prev_day = d
        for sd in (1, -1):
            sides[sd].new_bar(t, changed)
        if d == busy_day and B.start[t] <= busy_row:
            for sd in (1, -1):
                sides[sd].pend = None
                sides[sd].after_bar(t)
            continue
        trig = {}
        for sd in (1, -1):
            r0 = sides[sd].step(t, M[sd][1][d] if not cfg["engulf"] else None)
            if r0 is not None:
                trig[sd] = r0
        for sd in (1, -1):
            sides[sd].after_bar(t)
        if not trig:
            continue
        if len(trig) == 2:
            skips["both"] += 1
            for sd in (1, -1):
                sides[sd].pend = None
            continue
        sd, r0 = next(iter(trig.items()))
        S = sides[sd]
        p = S.pend
        S.pend = None
        if r0 > entry_last or r0 > last_row:
            skips["late"] += 1
            continue
        if trend is not None and trend(d, r0) != sd:              # the 15-minute structure known at entry must agree
            skips["trend"] = skips.get("trend", 0) + 1
            continue
        if d in blocked:
            skips["no_contract"] += 1
            continue
        mh, ml, mo, mc = (x[d] for x in M[sd])
        ref = mo[r0]
        if not np.isfinite(ref):
            skips["no_price"] += 1
            continue
        if ref <= p.stop:
            skips["no_room"] += 1
            continue
        if min_stop_pct and ref - p.stop < min_stop_pct * abs(ref):
            skips["small_stop"] = skips.get("small_stop", 0) + 1
            continue
        tgt = S.target(ref, p.stop, cfg["target"])
        if tgt is None or tgt <= ref:
            skips["no_target"] += 1
            continue
        if min_tgt_pct and tgt - ref < min_tgt_pct * abs(ref):        # Amendment 3: too close to pay for itself
            skips["small_target"] = skips.get("small_target", 0) + 1
            continue
        ex = exit_on(mh, ml, mo, r0, p.stop, tgt, last_row)
        if ex is None:
            skips["no_price"] += 1
            continue
        xr, why, xpx = ex
        sides[-sd].pend = None
        busy_day, busy_row = d, xr
        if days_keep(g.days[d]):
            trades.append(dict(d=d, day=g.days[d], side=sd, entry=r0, exit=xr, why=why, ref=float(ref * sd),
                               stop_pts=float(ref - p.stop), tgt_pts=float(tgt - ref), pts=float(xpx - ref),
                               R=float((xpx - ref) / (ref - p.stop))))
    census = {k: sides[1].census[k] + sides[-1].census[k] for k in sides[1].census}
    return trades, dict(skips=skips, census=census)


def mss_census(g, days_keep, k: int = 3, tf: int = 5, wick: bool = False) -> dict:
    """C3: every first close beyond the latest swing high (low) with an FVG in its leg, split by whether a sweep came
    in the 12 bars before; a 1:1 race on index wicks from the MSS bar's close to 15:20 (V1's bars and swings).
    wick=True (Amendment 1): the first WICK 1 tick beyond the swing; the race starts the minute after the breaking
    minute, from the broken level + 1 tick; the leg and its FVG use only bars before the breaking bar and that bar's
    minutes up to the break (nothing later)."""
    M = mirror(g)
    out = {True: [0, 0, 0], False: [0, 0, 0]}          # swept -> [up first, down first, neither]
    for sd in (1, -1):
        mh, ml, mo, mc = M[sd]
        B = make_bars(mo, mh, ml, mc, tf)
        S = Side(B, B.h, B.l, B.o, B.c, k, True, False)
        broken = set()
        last_sweep = -10 ** 9
        prev = -1
        for t in range(len(B.day)):
            d = int(B.day[t])
            S.new_bar(t, d != prev)
            if d != prev:
                last_sweep = -10 ** 9
            prev = d
            if S.lows and S.highs and S.highs[-1][0] > S.lows[-1][0] and S.ll[t] <= S.lows[-1][0] - TICK:
                last_sweep = t
            if S.highs:
                H = S.highs[-1]                                            # (level, session): one break per swing
                if wick and S.hh[t] >= H[0] + TICK and H not in broken:
                    broken.add(H)
                    _wick_race(out, S, B, mh[d], ml[d], mo[d], t, d, H[0] + TICK, last_sweep, days_keep(g.days[d]))
                elif not wick and S.cc[t] > H[0] and H not in broken:
                    broken.add(H)
                    swept = t - last_sweep <= N_MSS
                    a0 = last_sweep if swept else t - N_MSS
                    a0 = max(a0, int(np.searchsorted(B.day, d)))           # same session only
                    if find_fvg(S.hh, S.ll, a0, t) is not None and days_keep(g.days[d]):
                        a = a0 + int(np.argmin(S.ll[a0:t + 1]))
                        dist = S.cc[t] - S.ll[a]
                        r0 = int(B.end[t]) + 1
                        if dist > 0 and r0 <= K.K3.EXIT_LAST:
                            ex = exit_on(mh[d], ml[d], mo[d], r0, S.cc[t] - dist, S.cc[t] + dist, K.K3.EXIT_LAST)
                            if ex is not None:
                                out[swept][{"target": 0, "stop": 1, "time": 2}[ex[1]]] += 1
            S.after_bar(t)
    return {"swept": out[True], "not_swept": out[False]}


def _wick_race(out, S, B, mh, ml, mo, t, d, level, last_sweep, keep):
    """One wick-break event of the wick census (see mss_census)."""
    if not keep:
        return
    s0 = int(np.searchsorted(B.day, d))
    swept = t - last_sweep <= N_MSS
    a0 = max(last_sweep if swept else t - N_MSS, s0)
    rows = np.arange(int(B.start[t]), int(B.end[t]) + 1)
    hit = rows[np.isfinite(mh[rows]) & (mh[rows] >= level)]
    if not len(hit):
        return
    brk = int(hit[0])
    if a0 > t - 1 or find_fvg(S.hh, S.ll, a0, t - 1) is None:
        return
    low = min(float(S.ll[a0:t].min()), float(np.nanmin(ml[int(B.start[t]):brk + 1])))
    dist = level - low
    r0 = brk + 1
    if dist > 0 and r0 <= K.K3.EXIT_LAST:
        ex = exit_on(mh, ml, mo, r0, level - dist, level + dist, K.K3.EXIT_LAST)
        if ex is not None:
            out[swept][{"target": 0, "stop": 1, "time": 2}[ex[1]]] += 1


# ------------------------------------------------------------------------------------------------ the option leg
def option_trade(a, r0: int, xr: int, why: str, lot: int, d, und: str):
    """R127's pricing: buy at the open of r0, sell at the last traded close at or before xr. (net, net at 2x slip)."""
    if a is None or not np.isfinite(a[r0, 0]) or a[r0, 0] <= 0:
        return None
    v0 = float(np.nansum(a[max(r0 - 5, 0):r0, 4])) / lot
    buy = float(D3.FILLS.buy(np.array([a[r0, 0]]), np.array([v0]))[0])
    fin = np.flatnonzero(np.isfinite(a[r0:xr + 1, 3]))
    if not len(fin):
        return None
    rr = r0 + fin[-1]
    raw = float(a[rr, 3])
    v = float(np.nansum(a[max(rr - 5, 0):rr, 4])) / lot
    sell = float(D3.FILLS.sell(np.array([raw]), np.array([v]), stop=(why == "stop"))[0])
    net = (sell - buy) * lot - D3.charge(True, buy, lot, d, und) - D3.charge(False, sell, lot, d, und)
    slip = (buy - float(a[r0, 0]) + raw - sell) * lot
    return net, net - slip


class Series(D3.LakeOptions):
    """R123's lake reader on one series: WEEK (the nearest expiry) or MONTH."""

    def __init__(self, und: str, last_day: date, series: str):
        super().__init__(und, last_day)
        self.series = series
        self.files = sorted((K.K3.LAKE / "options_rolling_1m").glob(f"opt_{und}_{series}_*.parquet"))


def run_index(args) -> dict:
    und, part, family = (tuple(args) + ("orig",))[:3]
    VARS = FAMILIES[family]
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = Series(und, hi, "WEEK"), Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part == "holdout" else 0)
                                + (1000 if family == "wick" else 0))
    sig, meta = {}, {}
    for v, cfg in VARS.items():
        sig[v], meta[v] = scan(g, cfg, blocked, keep)
    census = mss_census(g, keep, wick=family == "wick")
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
        H, L, O = g.h[d], g.l[d], g.o[d]
        for v, t in by_day[d]:
            sd, r0, xr = t["side"], t["entry"], t["exit"]
            cp = "C" if sd > 0 else "P"
            ref_c = D3._last(g.c[d][:r0])
            if not np.isfinite(ref_c):
                continue
            tr = option_trade(opts.contracts.get((cp, D3.atm(ref_c, step))), r0, xr, t["why"], lot, day, und)
            if tr is None:
                continue
            mh, ml, mo = (H, L, O) if sd > 0 else (-L, -H, -O)
            tw = []
            hi_r = min(LAST_ENTRY, K.K3.EXIT_LAST)
            for _ in range(TWINS):
                r1 = int(rng.integers(FIRST_TWIN, hi_r + 1))
                ref1 = mo[r1]
                c1 = D3._last(g.c[d][:r1])
                if not (np.isfinite(ref1) and np.isfinite(c1)):
                    tw.append(np.nan)
                    continue
                e1 = exit_on(mh, ml, mo, r1, ref1 - t["stop_pts"], ref1 + t["tgt_pts"], K.K3.EXIT_LAST)
                if e1 is None:
                    tw.append(np.nan)
                    continue
                t1 = option_trade(opts.contracts.get((cp, D3.atm(c1, step))), r1, e1[0], e1[1], lot, day, und)
                tw.append(np.nan if t1 is None else t1[0])
            rows.append(dict(t, variant=v, und=und, series=src.series, net=tr[0], netx2=tr[1], twins=tw))
    print(f"R130 {part} {und}: " + ", ".join(f"{v} {sum(r['variant'] == v for r in rows)}" for v in VARS),
          flush=True)
    return dict(rows=rows, meta=meta, census=census, sessions=sorted({d for d in g.days if keep(d)}))


# ------------------------------------------------------------------------------------------------ statistics
def fold_of(d: date) -> str | None:
    return K.fold_of(d)


def twin_p(T: pd.DataFrame, seed: int = SEED) -> tuple[float, float, int]:
    """p of the rule's total vs the twins' totals (a twin with no trade resampled from that trade's valid twins)."""
    if not len(T):
        return 1.0, 0.0, 0
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
        return 1.0, 0.0, 0
    tot = tw[keep].sum(0)
    rule = float(T.net.values[keep].sum())
    return float((1 + (tot >= rule).sum()) / (1 + len(tot))), float(tot.mean()), int(keep.sum())


def money(T: pd.DataFrame) -> dict:
    if not len(T):
        return dict(n=0)
    net = T.net.values
    w = net > 0
    eq = np.cumsum(net)
    dd = float(min((eq - np.maximum.accumulate(np.r_[0.0, eq])[1:]).min(), 0.0))
    mo = T.assign(mo=[f"{d.year}-{d.month:02d}" for d in T.day]).groupby("mo").net.sum()
    return dict(n=int(len(T)), win=float(w.mean()), avg_win=float(net[w].mean()) if w.any() else 0.0,
                avg_loss=float(net[~w].mean()) if (~w).any() else 0.0, avg_net=float(net.mean()),
                avg_net_x2=float(T.netx2.mean()), total=float(net.sum()), max_dd=dd,
                worst_month=float(mo.min()), worst_month_name=str(mo.idxmin()), months_green=int((mo > 0).sum()),
                months=int(mo.size), avg_pts=float(T.pts.mean()), avg_R=float(T.R.mean()),
                pts_win=float((T.pts > 0).mean()),
                exits={k: int(v) for k, v in T.why.value_counts().items()},
                target_vs_stop=dict(target=int((T.why == "target").sum()), stop=int((T.why == "stop").sum())),
                buys=float((T.side > 0).mean()))


def stats(T: pd.DataFrame, by_fold: bool) -> dict:
    T = T.sort_values(["day", "und", "entry"]).reset_index(drop=True)
    s = money(T)
    if not s["n"]:
        return s
    s["p_twins"], s["twin_mean_total"], s["twin_trades"] = twin_p(T)
    s["by_year"] = {int(y): money(X) | {} for y, X in T.groupby([d.year for d in T.day])}
    s["by_und"] = {u: money(X) for u, X in T.groupby("und")}
    if by_fold:
        f = [fold_of(d) for d in T.day]
        s["by_fold"] = {k: money(T[[x == k for x in f]]) for k in K.FOLDS}
    for blk in ("by_year", "by_und", "by_fold"):
        for x in s.get(blk, {}).values():
            for drop in ("exits", "worst_month_name", "months_green", "months", "buys"):
                x.pop(drop, None)
    return s


class HoldoutError(RuntimeError):
    pass


def holdout_lock(status: str, out: Path = OUT, results: Path = RESULTS, suffix: str = "") -> None:
    """The ONE holdout read (PREREG section 3): 'start' refuses once a read has begun or its result is committed.
    suffix "_wick": Amendment 1's own lock (HOLDOUT_READ_WICK.json / holdout_wick.json)."""
    p = out / f"HOLDOUT_READ{suffix.upper()}.json"
    old = json.loads(p.read_text()) if p.exists() else None
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (results / f"holdout{suffix}.json").exists():
            raise HoldoutError(f"refused: the holdout was already read (results/holdout{suffix}.json exists)")
        if old:
            raise HoldoutError(f"refused: a holdout read already started at {old.get('started_at')}")
        rec = dict(status="started", started_at=now)
    else:
        rec = dict(old or {}, status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def run(part: str, family: str = "orig"):
    from multiprocessing import Pool
    VARS = FAMILIES[family]
    sfx = "" if family == "orig" else f"_{family}"
    if part == "holdout":
        holdout_lock("start", suffix=sfx)
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part, family) for u in K.UNDS])
    rows = [r for x in res for r in x["rows"]]
    T = pd.DataFrame(rows)
    out = dict(part=part, family=family, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)],
               sessions=len({d for x in res for d in x["sessions"]}), variants={},
               scan={u: x["meta"] for u, x in zip(K.UNDS, res)})
    cs = {"swept": [0, 0, 0], "not_swept": [0, 0, 0]}
    for x in res:
        for kk in cs:
            cs[kk] = [a + b for a, b in zip(cs[kk], x["census"][kk])]
    out["claim_c3"] = c3_test(cs)
    for v in VARS:
        Tv = T[T.variant == v] if len(T) else T
        s = stats(Tv, part == "design")
        cen = {kk: sum(out["scan"][u][v]["census"][kk] for u in K.UNDS) for kk in ("sweeps", "mss", "fvg", "touched",
                                                                                     "triggered")}
        sk = {kk: sum(out["scan"][u][v]["skips"][kk] for u in K.UNDS) for kk in out["scan"][K.UNDS[0]][v]["skips"]}
        s["census"], s["skips"], s["label"] = cen, sk, VARS[v]["label"]
        out["variants"][v] = s
    if part == "design":
        qs = R3.bh([out["variants"][v].get("p_twins", 1.0) for v in VARS])
        for v, q in zip(VARS, qs):
            s = out["variants"][v]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["design_gates"] = dict(n=s.get("n", 0) >= GATES["design_n"],
                                     net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                                     folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"])
            s["passes_design"] = all(s["design_gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}{sfx}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins"]).to_parquet(OUT / f"trades_{part}{sfx}.parquet")
    if part == "holdout":
        holdout_lock("done", suffix=sfx)
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 3), s.get("p_twins"))
           for v, s in out["variants"].items()})


def c3_test(cs: dict) -> dict:
    """Two-proportion z test: up-first share among decided races, swept vs not swept."""
    from math import erf, sqrt
    a, b = cs["swept"], cs["not_swept"]
    n1, n2 = a[0] + a[1], b[0] + b[1]
    if not (n1 and n2):
        return dict(swept=a, not_swept=b)
    p1, p2 = a[0] / n1, b[0] / n2
    pp = (a[0] + b[0]) / (n1 + n2)
    se = sqrt(pp * (1 - pp) * (1 / n1 + 1 / n2)) or 1e-12
    z = (p1 - p2) / se
    return dict(swept=a, not_swept=b, up_first_swept=p1, up_first_not_swept=p2, n_swept=n1, n_not_swept=n2, z=z,
                p_two_sided=float(2 * (1 - 0.5 * (1 + erf(abs(z) / sqrt(2))))))


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design", sys.argv[2] if len(sys.argv) > 2 else "orig")
