"""R131: ten ICT / SMC video strategies (PREREG.md), 30 variants, on the Drive lake, all five indices, buying the ATM
option of the nearest expiry that does not expire that day.

    python research/hunt/r131_ict_ten/r131.py design     # design period -> results/design.json
    python research/hunt/r131_ict_ten/r131.py holdout    # the ONE holdout read -> results/holdout.json

Signal source: the index spot (the underlying). Every swing, sweep, CHoCH, FVG, CISD, block, fib level, stop and target
is read on the index's bars (wicks for levels; opens and closes only for green / red and the confirming closes). The
option is only what is bought.

Reused from R130 (branch claude/r130-sweep-mss-fvg, commit fca117a): the bars, the pivot rule, the sweep / MSS (CHoCH)
tracker, the three-bar FVG, the wick exits, the option pricing, the twins and the holdout lock. They are copied here
(R130 is not on main yet) and are unchanged except where a comment says so.

Bearish setups run the bullish code on the mirrored index (high -> -low, low -> -high, open -> -open, close -> -close),
so one code path serves both sides.
"""
from __future__ import annotations

import json
import os
import sys
import warnings
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
OUT = Path(os.environ.get("R131_OUT", K.K3.CACHE_ROOT / "r131")).expanduser()
TICK = 0.05
K_PIVOT = 3
SESSIONS_BACK = 4                  # swings from today and the previous 4 sessions
ATR_N = 14
N_CONF = 12                        # bars from a sweep / poke / run to its confirming close
FVG_MIN_PCT = 0.0001               # 0.01% of the index (or 2 ticks, the larger)
STOP_PCT = 0.0002                  # stop 0.02% beyond the extreme
IMPULSE_ATR = 2.0                  # displacement: >= 2 ATR in at most 3 green bars
OB_LOOKBACK = 3                    # the order block is among the 3 bars before the impulse
OB_LIFE = 36                       # an order block is active for 36 bars
BRK_LIFE = 24                      # a breaker's retest window
IFVG_LIFE, IFVG_RETEST = 24, 12
BOX_N, BOX_ATR, BOX_WATCH = 12, 2.5, 6
CISD_ATR = 2.0
GREEN_BACK = 5                     # the "last green candle" is at most 5 bars before the run
RED_SEARCH = 3                     # the drop run ends at a red bar at most 3 bars before the lowest-low bar (N1)
OTE_WATCH, OTE_LEG_ATR = 24, 2.0
LAST_ENTRY = K.row(15, 0)          # entry minute at or before 15:00
EXIT_LAST = K.K3.EXIT_LAST         # 15:20
FIRST_TWIN = K.row(9, 20)
SB_WINDOW = (K.row(9, 45), K.row(10, 44))
H4_END = K.row(13, 14)             # NSE's first 4-hour candle: 09:15-13:14
TWINS = 200
SEED = 131

VARIANTS = {
    "S1-V1": dict(s="sb", tf=15, target="S", label="Silver Bullet: 15-min sweep then CHoCH, all day"),
    "S1-V2": dict(s="sb", tf=5, target="S", window=SB_WINDOW, label="Silver Bullet: Indian window 09:45-10:45, 5-min"),
    "S1-V3": dict(s="sb", tf=15, target="2R", label="Silver Bullet: 15-min, fixed 2R"),
    "S2-V1": dict(s="cam", tf=5, target="S", trend=True, label="Cameron: PDH/PDL sweep with the trend, then FVG"),
    "S2-V2": dict(s="cam", tf=5, target="2R", trend=True, label="Cameron: fixed 2R"),
    "S2-V3": dict(s="cam", tf=5, target="S", trend=False, label="Cameron: no trend filter"),
    "S3-V1": dict(s="cisd", tf=5, target="S", level="green", label="CISD as stated: close above the last green high"),
    "S3-V2": dict(s="cisd", tf=5, target="S", level="open", label="CISD, ICT's: close above the drop run's open"),
    "S3-V3": dict(s="cisd", tf=5, target="2R", level="green", label="CISD as stated, fixed 2R"),
    "S4-V1": dict(s="crt", tf=15, target="S", rng="day", label="CRT: previous day's range, 15-min CHoCH"),
    "S4-V2": dict(s="crt", tf=15, target="S", rng="4h", label="CRT: today's first 4-hour candle, from 13:15"),
    "S4-V3": dict(s="crt", tf=15, target="2R", rng="day", label="CRT: previous day's range, fixed 2R"),
    "S5-V1": dict(s="amd", tf=5, target="S", level="open", label="AMD: box, false break, CISD (ICT's)"),
    "S5-V2": dict(s="amd", tf=5, target="2R", level="open", label="AMD: fixed 2R"),
    "S5-V3": dict(s="amd", tf=5, target="S", level="green", label="AMD: CISD as stated (last green high)"),
    "S6-V1": dict(s="ifvg", tf=5, target="S", trig="touch", label="IFVG: first touch of the inverted gap"),
    "S6-V2": dict(s="ifvg", tf=5, target="S", trig="close", label="IFVG: touch then a close back out"),
    "S6-V3": dict(s="ifvg", tf=5, target="2R", trig="touch", label="IFVG: first touch, fixed 2R"),
    "S7-V1": dict(s="ts", tf=5, target="S", lvl="swing", label="Turtle Soup: 5-min swing sweep, CISD"),
    "S7-V2": dict(s="ts", tf=5, target="S", lvl="pd", label="Turtle Soup: PDH/PDL sweep, CISD"),
    "S7-V3": dict(s="ts", tf=5, target="2R", lvl="swing", label="Turtle Soup: 5-min swing, fixed 2R"),
    "S8-V1": dict(s="ob", tf=5, target="S", bos=False, label="Order Block: first return to the block"),
    "S8-V2": dict(s="ob", tf=5, target="2R", bos=False, label="Order Block: fixed 2R"),
    "S8-V3": dict(s="ob", tf=5, target="S", bos=True, label="Order Block: impulse must break structure"),
    "S9-V1": dict(s="brk", tf=5, target="S", trig="touch", label="Breaker: first retest of the failed block"),
    "S9-V2": dict(s="brk", tf=5, target="2R", trig="touch", label="Breaker: fixed 2R"),
    "S9-V3": dict(s="brk", tf=5, target="S", trig="close", label="Breaker: retest then a close back out"),
    "S10-V1": dict(s="ote", tf=5, target="S", fib=0.618, label="OTE: trend pullback to 0.618"),
    "S10-V2": dict(s="ote", tf=5, target="S", fib=0.705, label="OTE: trend pullback to 0.705"),
    "S10-V3": dict(s="ote", tf=5, target="2R", fib=0.618, label="OTE: 0.618, fixed 2R"),
}
# PREREG N4 (Boss, 29 Oct: "wick to wick"): V1 with every confirming close replaced by a wick. S10 has no close.
VARIANTS_W = {
    "S1-W": dict(s="sb", tf=15, target="S", wick=True, label="Silver Bullet wick-only: CHoCH by a wick"),
    "S2-W": dict(s="cam", tf=5, target="S", trend=True, wick=True, label="Cameron wick-only: FVG low back above PDL"),
    "S3-W": dict(s="cisd", tf=5, target="S", level="green", wick=True, label="CISD wick-only: a wick above the last green high"),
    "S4-W": dict(s="crt", tf=15, target="S", rng="day", wick=True, label="CRT wick-only: a wick outside, CHoCH by a wick"),
    "S5-W": dict(s="amd", tf=5, target="S", level="open", wick=True, label="AMD wick-only: CISD by a wick"),
    "S6-W": dict(s="ifvg", tf=5, target="S", trig="touch", wick=True, label="IFVG wick-only: invalidated by a wick"),
    "S7-W": dict(s="ts", tf=5, target="S", lvl="swing", wick=True, label="Turtle Soup wick-only: CISD by a wick"),
    "S8-W": dict(s="ob", tf=5, target="S", bos=False, wick=True, label="Order Block wick-only: impulse wick to wick"),
    "S9-W": dict(s="brk", tf=5, target="S", trig="touch", wick=True, label="Breaker wick-only: fails on a wick"),
}
ALL_VARIANTS = {**VARIANTS, **VARIANTS_W}
STRATS = {"sb": "S1 Silver Bullet", "cam": "S2 Cameron's Model", "cisd": "S3 CISD", "crt": "S4 CRT", "amd": "S5 AMD",
          "ifvg": "S6 IFVG", "ts": "S7 Turtle Soup", "ob": "S8 Order Block", "brk": "S9 Breaker Block",
          "ote": "S10 OTE"}
GATES = dict(design_n=300, folds=3, q=0.10, holdout_n=50, dd=-40000.0, month=-13000.0)   # no 65% rule: PREREG N3


# ------------------------------------------------------------------------------------------------ bars (R130)
@dataclass
class Bars:
    """tf-minute bars of one index, in time order across sessions; only bars with data."""
    day: np.ndarray
    start: np.ndarray
    end: np.ndarray
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
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        H = np.nanmax(r(h), axis=2)
        L = np.nanmin(r(l), axis=2)
    O, C = _first(r(o), 2), _last(r(c), 2)
    ok = np.isfinite(H) & np.isfinite(L) & np.isfinite(O) & np.isfinite(C)
    di, bi = np.nonzero(ok)
    return Bars(day=di, start=bi * tf, end=bi * tf + tf - 1, o=O[di, bi], h=H[di, bi], l=L[di, bi], c=C[di, bi])


def swing_highs(h: np.ndarray, k: int) -> np.ndarray:
    """R130's pivot: bars i whose high is strictly above the k highs before and at or above the k highs after."""
    T = len(h)
    if T < 2 * k + 1:
        return np.array([], dtype=int)
    from numpy.lib.stride_tricks import sliding_window_view as sw
    left = sw(h[:T - k - 1], k).max(1)             # left[i - k] = max(h[i-k:i]) for i = k .. T-k-1
    right = sw(h[k + 1:], k).max(1)                # right[i - k] = max(h[i+1:i+k+1])
    mid = h[k:T - k]
    ok = (mid > left[:len(mid)]) & (mid >= right[:len(mid)])
    return np.flatnonzero(ok) + k


@dataclass
class Chart:
    """One side's bars with ATR, pivots and per-session bar ranges."""
    B: Bars
    k: int = K_PIVOT

    def __post_init__(self):
        B = self.B
        self.h, self.l, self.o, self.c, self.day = B.h, B.l, B.o, B.c, B.day
        self.T = len(B.day)
        rng = B.h - B.l
        cs = np.r_[0.0, np.cumsum(rng)]
        t = np.arange(self.T)
        self.atr = np.where(t >= ATR_N, (cs[t] - cs[np.maximum(t - ATR_N, 0)]) / ATR_N, np.nan)
        self.sh = swing_highs(B.h, self.k)
        self.sl = swing_highs(-B.l, self.k)
        self.gend = B.day.astype(np.int64) * N + B.end
        self.green = B.c > B.o
        self.red = B.c < B.o
        # last bar index of each bar's session
        last = np.empty(self.T, dtype=int)
        if self.T:
            brk = np.flatnonzero(np.diff(B.day) != 0)
            ends = np.r_[brk, self.T - 1]
            starts = np.r_[0, brk + 1]
            for a, b in zip(starts, ends):
                last[a:b + 1] = b
            self.first_bar = {int(B.day[a]): int(a) for a in starts}
        else:
            self.first_bar = {}
        self.last_of = last

    def same(self, a: int, b: int) -> bool:
        return 0 <= a < self.T and 0 <= b < self.T and self.day[a] == self.day[b]


def stop_below(x: float) -> float:
    return float(x - STOP_PCT * abs(x))


def fvg_min(x: float) -> float:
    return max(2 * TICK, FVG_MIN_PCT * abs(x))


class Struct:
    """R130's swing tracker: highs = known swing highs (last 5 sessions); lows = known swing lows not yet taken out by
    a wick (one tick). A pivot i is known from bar i + k + 1 on."""

    def __init__(self, ch: Chart):
        self.ch = ch
        self.hi_at: dict = {}
        self.lo_at: dict = {}
        for i in ch.sh:
            self.hi_at.setdefault(int(i) + ch.k + 1, []).append(int(i))
        for i in ch.sl:
            self.lo_at.setdefault(int(i) + ch.k + 1, []).append(int(i))
        self.highs: list = []
        self.lows: list = []
        self.d = -1

    def new_bar(self, t: int) -> bool:
        ch = self.ch
        d = int(ch.day[t])
        changed = d != self.d
        if changed:
            self.highs = [i for i in self.highs if ch.day[i] >= d - SESSIONS_BACK]
            self.lows = [i for i in self.lows if ch.day[i] >= d - SESSIONS_BACK]
            self.d = d
        for i in self.hi_at.get(t, ()):
            if ch.day[i] >= d - SESSIONS_BACK:
                self.highs.append(i)
        for i in self.lo_at.get(t, ()):
            if ch.day[i] >= d - SESSIONS_BACK and not (ch.l[i + 1:t] <= ch.l[i] - TICK).any():
                self.lows.append(i)
        return changed

    def after_bar(self, t: int):
        x = self.ch.l[t]
        self.lows = [i for i in self.lows if not x <= self.ch.l[i] - TICK]

    def recent_high(self):
        return float(self.ch.h[self.highs[-1]]) if self.highs else None

    def recent_low(self):
        return float(self.ch.l[self.lows[-1]]) if self.lows else None


@dataclass
class Side:
    """One side's minute arrays (days x N) and its charts."""
    mh: np.ndarray
    ml: np.ndarray
    mo: np.ndarray
    mc: np.ndarray
    charts: dict = field(default_factory=dict)

    def chart(self, tf: int) -> Chart:
        if tf not in self.charts:
            self.charts[tf] = Chart(make_bars(self.mo, self.mh, self.ml, self.mc, tf))
        return self.charts[tf]

    def __post_init__(self):
        D = self.mh.shape[0]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            self.d_low = np.nanmin(self.ml, axis=1)
            self.d_close = np.array([D3._last(self.mc[i]) for i in range(D)])
            self.h4_low = np.nanmin(self.ml[:, :H4_END + 1], axis=1)
        self.flat_h = self.mh.ravel()


def sides_of(g) -> dict:
    return {1: Side(g.h, g.l, g.o, g.c), -1: Side(-g.l, -g.h, -g.o, -g.c)}


# ------------------------------------------------------------------------------------------------ candidates
@dataclass
class Cand:
    d: int        # session index
    r0: int       # entry minute row (the option is bought at its open)
    stop: float   # in this side's coordinates
    tf: int       # the chart whose swings give the structural target


def _first_touch(ml_d: np.ndarray, a: int, b: int, level: float):
    """The first minute row in [a, b] whose low reaches level (finite lows only)."""
    if b < a:
        return None
    seg = ml_d[a:b + 1]
    hit = np.flatnonzero(np.isfinite(seg) & (seg <= level))
    return a + int(hit[0]) if len(hit) else None


def _touch_cand(S: Side, ch: Chart, d: int, a: int, b: int, level: float, stop: float, tf: int, kill_above=None):
    """A 1-minute touch trigger: first low <= level in rows [a, b]; dropped if that minute's low reaches the stop, or if
    a high above kill_above comes first (or in the same minute). Returns a Cand or None."""
    r = _first_touch(S.ml[d], a, b, level)
    if r is None:
        return None
    if kill_above is not None:
        seg = S.mh[d][a:r + 1]
        if (np.isfinite(seg) & (seg > kill_above)).any():
            return None
    if S.ml[d][r] <= stop:
        return None
    return Cand(d, r + 1, stop, tf)


def _close_after_touch(S: Side, ch: Chart, t_from: int, t_to: int, level: float, stop: float, tf: int):
    """V2 / V3 rejection trigger: after the first 1-minute touch of `level` inside bars t_from..t_to, the first bar (the
    touch bar or later, up to t_to) that closes above `level`, with no low at the stop in between."""
    d = int(ch.day[t_from])
    r = _first_touch(S.ml[d], int(ch.B.start[t_from]), int(ch.B.end[t_to]), level)
    if r is None:
        return None
    for w in range(t_from, t_to + 1):
        if ch.B.end[w] < r:
            continue
        seg = S.ml[d][r:int(ch.B.end[w]) + 1]
        if (np.isfinite(seg) & (seg <= stop)).any():
            return None
        if ch.c[w] > level:
            return Cand(d, int(ch.B.end[w]) + 1, stop, tf)
    return None


def drop_run_level(ch: Chart, lowbar: int, how: str):
    """The CISD level for a move whose lowest low is at `lowbar`: the run of consecutive red bars ending at the last red
    bar at most RED_SEARCH bars at or before lowbar (same session). how='open': the run's first open (ICT);
    'green': the high of the last green bar at most GREEN_BACK bars before the run (as stated). None if no run."""
    e = None
    for x in range(lowbar, lowbar - RED_SEARCH - 1, -1):
        if not ch.same(x, lowbar):
            break
        if ch.red[x]:
            e = x
            break
    if e is None:
        return None
    a = e
    while ch.same(a - 1, e) and ch.red[a - 1]:
        a -= 1
    return run_level(ch, a, how)


def run_level(ch: Chart, a: int, how: str):
    if how == "open":
        return float(ch.o[a])
    for x in range(a - 1, a - GREEN_BACK - 1, -1):
        if not ch.same(x, a):
            return None
        if ch.green[x]:
            return float(ch.h[x])
    return None


# ---- S1 Silver Bullet: sweep of the recent low -> CHoCH close above the recent high (R130's sweep + MSS)
def det_sb(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    st = Struct(ch)
    win = cfg.get("window")
    out, pend = [], None

    def in_win(t):
        return win is None or (ch.B.start[t] >= win[0] and ch.B.end[t] <= win[1])

    for t in range(ch.T):
        if st.new_bar(t):
            pend = None
        if pend is not None:
            s, H = pend
            if t > s + N_CONF or not ch.same(s, t):
                pend = None
            elif ch.c[t] > H:
                pend = None
                if in_win(t):
                    out.append(Cand(int(ch.day[t]), int(ch.B.end[t]) + 1, stop_below(ch.l[s:t + 1].min()), cfg["tf"]))
                st.after_bar(t)
                continue
        L, H = st.recent_low(), st.recent_high()
        if L is not None and H is not None and H > L and ch.l[t] <= L - TICK and in_win(t):
            pend = (t, H)
        st.after_bar(t)
    return out


# ---- S2 Cameron's Model and S7-V2's daily poke
def _fresh_poke(ch: Chart, t: int, L: float) -> bool:
    return bool(ch.l[t] <= L - TICK and (not ch.same(t - 1, t) or ch.l[t - 1] > L - TICK))


def det_cam(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    out = []
    for d, a in ch.first_bar.items():
        if d < 6:
            continue
        L = S.d_low[d - 1]
        if not np.isfinite(L):
            continue
        if cfg["trend"] and not (S.d_close[d - 1] < S.d_close[d - 6]):
            continue
        pend = None
        for t in range(a, int(ch.last_of[a]) + 1):
            if pend is not None:
                if t > pend + N_CONF:
                    pend = None
                elif t - 2 >= pend and ch.same(t - 2, t) and ch.l[t] - ch.h[t - 2] >= fvg_min(ch.l[t]) \
                        and (ch.l[t] > L if cfg.get("wick") else ch.c[t] > L):
                    out.append(Cand(d, int(ch.B.end[t]) + 1, stop_below(ch.l[pend:t + 1].min()), cfg["tf"]))
                    pend = None
                    continue
            if _fresh_poke(ch, t, L):
                pend = t
    return out


# ---- S3 CISD: a rapid drop run, then a close above the level
def det_cisd(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    out, pend = [], None
    for t in range(1, ch.T):
        if not ch.same(t - 1, t):
            pend = None
        # a red run ended at t-1 (bar t is not red)
        if ch.same(t - 1, t) and ch.red[t - 1] and not ch.red[t]:
            e = t - 1
            a = e
            while ch.same(a - 1, e) and ch.red[a - 1]:
                a -= 1
            if e - a + 1 >= 2 and np.isfinite(ch.atr[a]) and ch.o[a] - ch.l[a:e + 1].min() >= CISD_ATR * ch.atr[a]:
                lv = run_level(ch, a, cfg["level"])
                if lv is not None:
                    pend = (a, e, lv)
        if pend is not None:
            a, e, lv = pend
            if t > e + N_CONF or not ch.same(e, t):
                pend = None
            elif ch.c[t] > lv:
                out.append(Cand(int(ch.day[t]), int(ch.B.end[t]) + 1, stop_below(ch.l[a:t + 1].min()), cfg["tf"]))
                pend = None
    return out


# ---- S4 CRT: an outside close below the range low, then a CHoCH back inside
def det_crt(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    st = Struct(ch)
    out, pend = [], None
    for t in range(ch.T):
        if st.new_bar(t):
            pend = None
        d = int(ch.day[t])
        if cfg["rng"] == "day":
            L = S.d_low[d - 1] if d >= 1 else np.nan
            first_ok = int(ch.first_bar[d])
        else:
            L = S.h4_low[d]
            first_ok = None
            if ch.B.start[t] <= H4_END:          # the 4-hour candle is not complete yet
                st.after_bar(t)
                continue
        if not np.isfinite(L):
            st.after_bar(t)
            continue
        if pend is not None:
            s, H = pend
            if t > s + N_CONF or not ch.same(s, t):
                pend = None
            elif ch.c[t] > H and ch.c[t] > L:
                out.append(Cand(d, int(ch.B.end[t]) + 1, stop_below(ch.l[s:t + 1].min()), cfg["tf"]))
                pend = None
                st.after_bar(t)
                continue
        first = (t == first_ok) if first_ok is not None else (not ch.same(t - 1, t) or ch.B.start[t - 1] <= H4_END)
        if ch.c[t] < L and (first or ch.c[t - 1] >= L):
            H = st.recent_high()
            if H is not None:
                pend = (t, H)
        st.after_bar(t)
    return out


# ---- S5 AMD: box, false break below it, CISD
def det_amd(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    out = []
    box = None        # (b, lo, hi)
    man = None        # f (false-break bar)
    for t in range(ch.T):
        if box is not None and not ch.same(box[0], t):
            box = None
        if man is not None and not ch.same(man, t):
            man = None
        if man is None and box is not None:
            b, lo, hi = box
            if t > b + BOX_WATCH:
                box = None
            elif ch.l[t] <= lo - TICK:
                man, box = t, None
            elif ch.h[t] > hi:
                box = None
        if man is not None:
            f = man
            if t > f + N_CONF:
                man = None
            else:
                lowbar = f + int(np.argmin(ch.l[f:t + 1]))
                lv = drop_run_level(ch, lowbar, cfg["level"])
                if lv is not None and ch.c[t] > lv:
                    out.append(Cand(int(ch.day[t]), int(ch.B.end[t]) + 1, stop_below(ch.l[f:t + 1].min()), cfg["tf"]))
                    man = None
        if man is None and t >= BOX_N - 1 and ch.same(t - BOX_N + 1, t):
            a = t - BOX_N + 1
            if np.isfinite(ch.atr[a]) and ch.h[a:t + 1].max() - ch.l[a:t + 1].min() <= BOX_ATR * ch.atr[a]:
                box = (t, float(ch.l[a:t + 1].min()), float(ch.h[a:t + 1].max()))
    return out


# ---- S6 IFVG: a bearish FVG closed above becomes support; enter on the retest
def det_ifvg(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    out = []
    zones = []        # [formed bar, bottom, top]
    for t in range(ch.T):
        zones = [z for z in zones if ch.same(z[0], t) and t <= z[0] + IFVG_LIFE]
        keep = []
        for z in zones:
            j1, bot, top = z
            if (ch.h[t] >= top + TICK) if cfg.get("wick") else (ch.c[t] > top):   # invalidated: now support
                last = min(t + IFVG_RETEST, int(ch.last_of[t]))
                stop = stop_below(bot)
                if last > t:
                    if cfg["trig"] == "touch":
                        cd = _touch_cand(S, ch, int(ch.day[t]), int(ch.B.end[t]) + 1, int(ch.B.end[last]), top, stop,
                                         cfg["tf"])
                    else:
                        cd = _close_after_touch(S, ch, t + 1, last, top, stop, cfg["tf"])
                    if cd is not None:
                        out.append(cd)
            else:
                keep.append(z)
        zones = keep
        if t >= 2 and ch.same(t - 2, t) and ch.l[t - 2] - ch.h[t] >= fvg_min(ch.h[t]):
            zones.append([t, float(ch.h[t]), float(ch.l[t - 2])])
    return out


# ---- S7 Turtle Soup: sweep, then CISD
def det_ts(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    st = Struct(ch)
    out, pend = [], None

    def check(s, t):
        lowbar = s + int(np.argmin(ch.l[s:t + 1]))
        lv = drop_run_level(ch, lowbar, "open")
        if lv is not None and ch.c[t] > lv:
            out.append(Cand(int(ch.day[t]), int(ch.B.end[t]) + 1, stop_below(ch.l[s:t + 1].min()), cfg["tf"]))
            return True
        return False

    for t in range(ch.T):
        if st.new_bar(t):
            pend = None
        d = int(ch.day[t])
        if pend is not None:
            if t > pend + N_CONF or not ch.same(pend, t):
                pend = None
            elif check(pend, t):
                pend = None
                st.after_bar(t)
                continue
        if cfg["lvl"] == "swing":
            L = st.recent_low()
            swept = L is not None and ch.l[t] <= L - TICK
        else:
            L = S.d_low[d - 1] if d >= 1 else np.nan
            swept = bool(np.isfinite(L)) and _fresh_poke(ch, t, L)
        if swept:
            pend = t
            if check(t, t):
                pend = None
        st.after_bar(t)
    return out


# ---- S8 Order Block / S9 Breaker
def find_obs(ch: Chart, bull: bool, bos: bool = False, wick: bool = False) -> list:
    """Order blocks on this chart: (ob bar, impulse last bar). bull: a green impulse and the last red bar before it;
    else a red impulse and the last green bar before it. bos: the impulse's close must also be above the recent
    swing high known at its last bar (bull only)."""
    col, opp = (ch.green, ch.red) if bull else (ch.red, ch.green)
    st = Struct(ch) if bos else None
    out, seen = [], set()
    for t in range(ch.T):
        if st is not None:
            st.new_bar(t)
        if col[t]:
            a = t
            while a > t - 2 and ch.same(a - 1, t) and col[a - 1]:
                a -= 1
            if wick:                                  # N4: wick to wick
                mv = (ch.h[t] - ch.l[a]) if bull else (ch.h[a] - ch.l[t])
            else:
                mv = (ch.c[t] - ch.o[a]) if bull else (ch.o[a] - ch.c[t])
            if np.isfinite(ch.atr[a]) and mv >= IMPULSE_ATR * ch.atr[a]:
                ob = None
                for x in range(a - 1, a - OB_LOOKBACK - 1, -1):
                    if not ch.same(x, a):
                        break
                    if opp[x]:
                        ob = x
                        break
                ok = ob is not None and ob not in seen
                if ok and bos:
                    H = st.recent_high()
                    ok = H is not None and ch.c[t] > H
                if ok:
                    seen.add(ob)
                    out.append((ob, t))
        if st is not None:
            st.after_bar(t)
    return out


def det_ob(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    out = []
    for ob, t in find_obs(ch, True, cfg["bos"], cfg.get("wick", False)):
        last = min(t + OB_LIFE, int(ch.last_of[t]))
        if last <= t:
            continue
        d = int(ch.day[t])
        cd = _touch_cand(S, ch, d, int(ch.B.end[t]) + 1, int(ch.B.end[last]), float(ch.h[ob]),
                         stop_below(ch.l[ob]), cfg["tf"])
        if cd is not None:
            out.append(cd)
    return out


def det_brk(S: Side, cfg: dict) -> list:
    """A bearish order block that a bar closes above becomes a bullish breaker; buy its retest."""
    ch = S.chart(cfg["tf"])
    out = []
    wick = cfg.get("wick", False)
    for ob, t in find_obs(ch, False, wick=wick):
        last = min(t + OB_LIFE, int(ch.last_of[t]))
        hi, lo = float(ch.h[ob]), float(ch.l[ob])
        v = next((x for x in range(t + 1, last + 1) if (ch.h[x] >= hi + TICK if wick else ch.c[x] > hi)), None)
        if v is None:
            continue
        last2 = min(v + BRK_LIFE, int(ch.last_of[v]))
        if last2 <= v:
            continue
        d = int(ch.day[v])
        stop = stop_below(lo)
        if cfg["trig"] == "touch":
            cd = _touch_cand(S, ch, d, int(ch.B.end[v]) + 1, int(ch.B.end[last2]), hi, stop, cfg["tf"])
        else:
            cd = _close_after_touch(S, ch, v + 1, last2, hi, stop, cfg["tf"])
        if cd is not None:
            out.append(cd)
    return out


# ---- S10 OTE
def det_ote(S: Side, cfg: dict) -> list:
    ch = S.chart(cfg["tf"])
    out = []
    sh, sl, k = ch.sh, ch.sl, ch.k
    for n, i in enumerate(sh):
        t0 = int(i) + k + 1                    # SH known from bar t0
        if n == 0 or t0 >= ch.T or not ch.same(int(i), t0):
            continue
        d = int(ch.day[i])
        p = int(sh[n - 1])
        if ch.day[p] < d - SESSIONS_BACK or not ch.h[i] > ch.h[p]:
            continue
        m = int(np.searchsorted(sl, i)) - 1    # the latest swing low before SH's pivot
        if m < 1:
            continue
        j, j0 = int(sl[m]), int(sl[m - 1])
        if ch.day[j0] < d - SESSIONS_BACK or not ch.l[j] > ch.l[j0]:
            continue
        SH, SL = float(ch.h[i]), float(ch.l[j])
        leg = SH - SL
        if not (np.isfinite(ch.atr[i]) and leg >= OTE_LEG_ATR * ch.atr[i]):
            continue
        # N1: skipped when the retracement had already gone past the OTE zone (0.786) before SH was known
        if ch.l[i + 1:t0].min() < SH - 0.786 * leg:
            continue
        last = min(t0 + OTE_WATCH - 1, int(ch.last_of[t0]))
        cd = _touch_cand(S, ch, d, int(ch.B.end[t0 - 1]) + 1, int(ch.B.end[last]), SH - cfg["fib"] * leg,
                         stop_below(SL), cfg["tf"], kill_above=SH)
        if cd is not None:
            out.append(cd)
    return out


# ---- PREREG N4: minute-wick confirmations
def _cross(S: Side, ch: Chart, t: int, X: float):
    """The first minute of bar t whose high is at least one tick above X, or None."""
    d, a, b = int(ch.day[t]), int(ch.B.start[t]), int(ch.B.end[t])
    seg = S.mh[d][a:b + 1]
    hit = np.flatnonzero(np.isfinite(seg) & (seg >= X + TICK))
    return a + int(hit[0]) if len(hit) else None


def _low_upto(S: Side, ch: Chart, s: int, t: int, r: int) -> float:
    """The lowest low from bar s to minute r of bar t (bars s .. t-1 whole, bar t up to and including minute r)."""
    lo = float(ch.l[s:t].min()) if t > s else np.inf
    seg = S.ml[int(ch.day[t])][int(ch.B.start[t]):r + 1]
    seg = seg[np.isfinite(seg)]
    return float(min(lo, seg.min() if len(seg) else np.inf))


def det_sb_w(S: Side, cfg: dict) -> list:
    """S1-W (and S4-W through det_crt_w): sweep of the recent low, then a minute wick above the recent high."""
    ch = S.chart(cfg["tf"])
    st = Struct(ch)
    out, pend = [], None
    for t in range(ch.T):
        if st.new_bar(t):
            pend = None
        if pend is not None:
            s0, H = pend
            if t > s0 + N_CONF or not ch.same(s0, t):
                pend = None
            else:
                r = _cross(S, ch, t, H)
                if r is not None:
                    out.append(Cand(int(ch.day[t]), r + 1, stop_below(_low_upto(S, ch, s0, t, r)), cfg["tf"]))
                    pend = None
                    st.after_bar(t)
                    continue
        L, H = st.recent_low(), st.recent_high()
        if L is not None and H is not None and H > L and ch.l[t] <= L - TICK:
            pend = (t, H)
        st.after_bar(t)
    return out


def det_crt_w(S: Side, cfg: dict) -> list:
    """S4-W: a fresh wick below the previous day's low (15-min), then a minute wick above the recent 15-min high."""
    ch = S.chart(cfg["tf"])
    st = Struct(ch)
    out, pend = [], None
    for t in range(ch.T):
        if st.new_bar(t):
            pend = None
        d = int(ch.day[t])
        L = S.d_low[d - 1] if d >= 1 else np.nan
        if pend is not None:
            s0, H = pend
            if t > s0 + N_CONF or not ch.same(s0, t):
                pend = None
            else:
                r = _cross(S, ch, t, H)
                if r is not None:
                    out.append(Cand(d, r + 1, stop_below(_low_upto(S, ch, s0, t, r)), cfg["tf"]))
                    pend = None
                    st.after_bar(t)
                    continue
        if np.isfinite(L) and _fresh_poke(ch, t, L):
            H = st.recent_high()
            if H is not None and H > L:
                pend = (t, H)
        st.after_bar(t)
    return out


def det_cisd_w(S: Side, cfg: dict) -> list:
    """S3-W: a red run (armed after each completed red bar) whose first high to lowest low is >= 2 ATR; then a minute
    wick above the last green candle's high within 12 bars of the run's last bar."""
    ch = S.chart(cfg["tf"])
    out, pend = [], None
    for t in range(ch.T):
        if t and not ch.same(t - 1, t):
            pend = None
        if pend is not None:
            a, e, lv = pend
            if t > e + N_CONF or not ch.same(e, t):
                pend = None
            else:
                r = _cross(S, ch, t, lv)
                if r is not None:
                    out.append(Cand(int(ch.day[t]), r + 1, stop_below(_low_upto(S, ch, a, t, r)), cfg["tf"]))
                    pend = None
                    continue
        if ch.red[t]:
            a = t
            while ch.same(a - 1, t) and ch.red[a - 1]:
                a -= 1
            if t - a + 1 >= 2 and np.isfinite(ch.atr[a]) and ch.h[a] - ch.l[a:t + 1].min() >= CISD_ATR * ch.atr[a]:
                lv = run_level(ch, a, cfg["level"])
                if lv is not None:
                    pend = (a, t, lv)
    return out


def det_amd_w(S: Side, cfg: dict) -> list:
    """S5-W: the AMD box and false break as S5 (wicks already); the CISD is a minute wick above ICT's level from the
    completed bars since the false break."""
    ch = S.chart(cfg["tf"])
    out, box, man = [], None, None
    for t in range(ch.T):
        if box is not None and not ch.same(box[0], t):
            box = None
        if man is not None and not ch.same(man, t):
            man = None
        if man is not None:
            f = man
            if t > f + N_CONF:
                man = None
            elif t > f:
                lowbar = f + int(np.argmin(ch.l[f:t]))
                lv = drop_run_level(ch, lowbar, cfg["level"])
                r = _cross(S, ch, t, lv) if lv is not None else None
                if r is not None:
                    out.append(Cand(int(ch.day[t]), r + 1, stop_below(_low_upto(S, ch, f, t, r)), cfg["tf"]))
                    man = None
                    continue
        if man is None and box is not None:
            b, lo, hi = box
            if t > b + BOX_WATCH:
                box = None
            elif ch.l[t] <= lo - TICK:
                man, box = t, None
            elif ch.h[t] > hi:
                box = None
        if man is None and t >= BOX_N - 1 and ch.same(t - BOX_N + 1, t):
            a = t - BOX_N + 1
            if np.isfinite(ch.atr[a]) and ch.h[a:t + 1].max() - ch.l[a:t + 1].min() <= BOX_ATR * ch.atr[a]:
                box = (t, float(ch.l[a:t + 1].min()), float(ch.h[a:t + 1].max()))
    return out


def det_ts_w(S: Side, cfg: dict) -> list:
    """S7-W: a sweep of the recent low (5-min swings), then a minute wick above ICT's level from the completed bars."""
    ch = S.chart(cfg["tf"])
    st = Struct(ch)
    out, pend = [], None
    for t in range(ch.T):
        if st.new_bar(t):
            pend = None
        if pend is not None:
            if t > pend + N_CONF or not ch.same(pend, t):
                pend = None
            else:
                lowbar = pend + int(np.argmin(ch.l[pend:t]))
                lv = drop_run_level(ch, lowbar, "open")
                r = _cross(S, ch, t, lv) if lv is not None else None
                if r is not None:
                    out.append(Cand(int(ch.day[t]), r + 1, stop_below(_low_upto(S, ch, pend, t, r)), cfg["tf"]))
                    pend = None
                    st.after_bar(t)
                    continue
        L = st.recent_low()
        if L is not None and ch.l[t] <= L - TICK:
            pend = t
        st.after_bar(t)
    return out


W_DETECTORS = dict(sb=det_sb_w, crt=det_crt_w, cisd=det_cisd_w, amd=det_amd_w, ts=det_ts_w)

DETECTORS = dict(sb=det_sb, cam=det_cam, cisd=det_cisd, crt=det_crt, amd=det_amd, ifvg=det_ifvg, ts=det_ts,
                 ob=det_ob, brk=det_brk, ote=det_ote)


def detector(cfg: dict):
    """The detector of a variant: the wick-only one (N4) where it has its own, else the shared one."""
    if cfg.get("wick") and cfg["s"] in W_DETECTORS:
        return W_DETECTORS[cfg["s"]]
    return DETECTORS[cfg["s"]]


def candidates(sides: dict, cfg: dict) -> list:
    """All candidate entries of one variant on one index, both sides: (side, Cand), sorted by time."""
    out = []
    for sd in (1, -1):
        out += [(sd, c) for c in detector(cfg)(sides[sd], cfg)]
    out.sort(key=lambda x: (x[1].d, x[1].r0, x[0]))
    return out


# ------------------------------------------------------------------------------------------------ targets and exits
def struct_target(S: Side, ch: Chart, d: int, r0: int, ref: float):
    """The nearest swing high above ref, confirmed before the entry minute, not traded above (one tick) since it
    formed; today and the previous 4 sessions. None if there is none."""
    g0 = d * N + r0
    piv = ch.sh
    if not len(piv):
        return None
    conf = ch.gend[np.minimum(piv + ch.k, ch.T - 1)]
    lv = ch.h[piv]
    sel = np.flatnonzero((ch.day[piv] >= d - SESSIONS_BACK) & (conf < g0) & (piv + ch.k < ch.T) & (lv > ref))
    for x in sel[np.argsort(lv[sel], kind="stable")]:
        i = int(piv[x])
        seg = S.flat_h[int(ch.gend[i]) + 1:g0]
        seg = seg[np.isfinite(seg)]
        if not len(seg) or seg.max() < lv[x] + TICK:
            return float(lv[x])
    return None


def exit_on(mh: np.ndarray, ml: np.ndarray, mo: np.ndarray, r0: int, stop: float, tgt: float, last: int):
    """R130: (exit row, why, exit price) on one side's minute wicks; the stop first if both in one minute."""
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
        px = min(stop, mo[r]) if np.isfinite(mo[r]) else stop
        return r, "stop", float(px)
    if t0 < 10 ** 9:
        return r0 + int(t0), "target", float(tgt)
    r = r0 + int(np.flatnonzero(ok)[-1])
    return r, "time", float((mh[r] + ml[r]) / 2)


def sequence(sides: dict, cands: list, cfg: dict, blocked: set, days, days_keep, last_row: int = EXIT_LAST):
    """One trade at a time per index: candidates in time order; one entering while a trade is open is skipped."""
    trades = []
    sk = dict(busy=0, both=0, late=0, no_contract=0, no_price=0, no_room=0, no_target=0)
    busy = -1
    i = 0
    while i < len(cands):
        sd, c = cands[i]
        g0 = c.d * N + c.r0
        j = i
        while j < len(cands) and cands[j][1].d == c.d and cands[j][1].r0 == c.r0:
            j += 1
        same, i = cands[i:j], j
        if len({x[0] for x in same}) == 2:
            sk["both"] += 1
            continue
        if g0 <= busy:
            sk["busy"] += 1
            continue
        if c.r0 > LAST_ENTRY or c.r0 > last_row:
            sk["late"] += 1
            continue
        if c.d in blocked:
            sk["no_contract"] += 1
            continue
        S = sides[sd]
        mh, ml, mo = S.mh[c.d], S.ml[c.d], S.mo[c.d]
        ref = mo[c.r0]
        if not np.isfinite(ref):
            sk["no_price"] += 1
            continue
        if ref <= c.stop:
            sk["no_room"] += 1
            continue
        if cfg["target"] == "2R":
            tgt = ref + 2 * (ref - c.stop)
        else:
            tgt = struct_target(S, S.chart(c.tf), c.d, c.r0, ref)
        if tgt is None or tgt <= ref:
            sk["no_target"] += 1
            continue
        ex = exit_on(mh, ml, mo, c.r0, c.stop, tgt, last_row)
        if ex is None:
            sk["no_price"] += 1
            continue
        xr, why, xpx = ex
        busy = c.d * N + xr
        if days_keep(days[c.d]):
            trades.append(dict(d=c.d, day=days[c.d], side=sd, entry=c.r0, exit=xr, why=why, ref=float(ref * sd),
                               stop_pts=float(ref - c.stop), tgt_pts=float(tgt - ref), pts=float(xpx - ref),
                               R=float((xpx - ref) / (ref - c.stop))))
    return trades, sk


# ------------------------------------------------------------------------------------------------ the option leg (R130)
def option_trade(a, r0: int, xr: int, why: str, lot: int, d, und: str):
    """Buy at the open of r0, sell at the last traded close at or before xr. (net, net at 2x slip)."""
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


def last_before(x: np.ndarray) -> np.ndarray:
    """out[r] = the last finite value of x in rows [0, r - 1] (NaN if none): D3._last(x[:r]) for every r."""
    idx = np.where(np.isfinite(x), np.arange(len(x)), -1)
    idx = np.maximum.accumulate(idx)
    prev = np.r_[-1, idx[:-1]]
    return np.where(prev >= 0, x[np.maximum(prev, 0)], np.nan)


def _contract_aux(a: np.ndarray):
    """Cumulative volume (NaN as 0) and, per row, the index of the last finite close at or before it (-1: none)."""
    cv = np.r_[0.0, np.cumsum(np.nan_to_num(a[:, 4]))]
    li = np.maximum.accumulate(np.where(np.isfinite(a[:, 3]), np.arange(len(a)), -1))
    return cv, li


def twin_nets(r1s, lastc, mh, ml, mo, contracts: dict, cp: str, step: float, stop_pts: float, tgt_pts: float,
              lot: int, d, und: str, cache: dict | None = None) -> list:
    """The random-entry twins of one trade (PREREG section 3): entries at rows r1s, the same stop / target distances,
    the same exits, the ATM option of each twin's own entry. Vectorised option_trade (same arithmetic); NaN = no
    trade."""
    cache = {} if cache is None else cache
    n = len(r1s)
    out = np.full(n, np.nan)
    xr = np.full(n, -1)
    stp = np.zeros(n, dtype=bool)
    for i, r1 in enumerate(r1s):
        ref1, c1 = mo[r1], lastc[r1]
        if not (np.isfinite(ref1) and np.isfinite(c1)):
            continue
        e1 = exit_on(mh, ml, mo, int(r1), ref1 - stop_pts, ref1 + tgt_pts, EXIT_LAST)
        if e1 is not None:
            xr[i], stp[i] = e1[0], e1[1] == "stop"
    ok = xr >= 0
    if not ok.any():
        return out.tolist()
    ks = np.array([D3.atm(x, step) if np.isfinite(x) else np.nan for x in lastc[r1s]])
    for kk in np.unique(ks[ok]):
        a = contracts.get((cp, float(kk)))
        if a is None:
            continue
        if (cp, kk) not in cache:
            cache[(cp, kk)] = _contract_aux(a)
        cv, li = cache[(cp, kk)]
        sel = np.flatnonzero(ok & (ks == kk))
        r0 = r1s[sel]
        op = a[r0, 0]
        good = np.isfinite(op) & (op > 0)
        rr = li[xr[sel]]
        good &= rr >= r0
        sel, r0, op, rr = sel[good], r0[good], op[good], rr[good]
        if not len(sel):
            continue
        v0 = (cv[r0] - cv[np.maximum(r0 - 5, 0)]) / lot
        buy = D3.FILLS.buy(op, v0)
        raw = a[rr, 3]
        v = (cv[rr] - cv[np.maximum(rr - 5, 0)]) / lot
        st = stp[sel]
        sell = np.where(st, D3.FILLS.sell(raw, v, stop=True), D3.FILLS.sell(raw, v, stop=False))
        dn = np.full(len(sel), D3._dn(d))
        q = np.full(len(sel), lot)
        bse = und in K.BSE
        net = (sell - buy) * lot - D3.COSTS.charge(True, buy, q, dn, bse=bse) - D3.COSTS.charge(False, sell, q, dn,
                                                                                                bse=bse)
        out[sel] = net
    return out.tolist()


class Series(D3.LakeOptions):
    """R123's lake reader on one series: WEEK (the nearest expiry) or MONTH."""

    def __init__(self, und: str, last_day: date, series: str):
        super().__init__(und, last_day)
        self.series = series
        self.files = sorted((K.K3.LAKE / "options_rolling_1m").glob(f"opt_{und}_{series}_*.parquet"))


def family(fam: str) -> tuple[dict, str, int]:
    """The variant set, the file suffix and the seed offset: 'main' (the 30) or 'W' (PREREG N4's 9 wick-only)."""
    return (VARIANTS, "", 0) if fam == "main" else (VARIANTS_W, "_w", 500)


def run_index(args) -> dict:
    und, part, fam = args if len(args) == 3 else (*args, "main")
    VS, _, seed_off = family(fam)
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = Series(und, hi, "WEEK"), Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + seed_off + K.UNDS.index(und) + (100 if part == "holdout" else 0))
    rng2 = np.random.default_rng(SEED + seed_off + 1000 + K.UNDS.index(und) + (100 if part == "holdout" else 0))
    sides = sides_of(g)
    sig, meta = {}, {}
    for v, cfg in VS.items():
        cands = candidates(sides, cfg)
        sig[v], sk = sequence(sides, cands, cfg, blocked, g.days, keep)
        meta[v] = dict(candidates=sum(keep(g.days[c.d]) for _, c in cands), skips=sk)
    by_day = {}
    for v, T in sig.items():
        for t in T:
            by_day.setdefault(t["d"], []).append((v, t))
    rows = []
    hi_r = min(LAST_ENTRY, EXIT_LAST)
    for d in sorted(by_day):
        day = g.days[d]
        src = Mo if day in wexp else W
        opts = src.day(day, g.c[d])
        if opts is None:
            continue
        lastc = last_before(g.c[d])
        cache = {}
        for v, t in by_day[d]:
            sd, r0, xr = t["side"], t["entry"], t["exit"]
            cp = "C" if sd > 0 else "P"
            ref_c = D3._last(g.c[d][:r0])
            if not np.isfinite(ref_c):
                continue
            tr = option_trade(opts.contracts.get((cp, D3.atm(ref_c, step))), r0, xr, t["why"], lot, day, und)
            if tr is None:
                continue
            S = sides[sd]
            mh, ml, mo = S.mh[d], S.ml[d], S.mo[d]
            r1s = rng.integers(FIRST_TWIN, hi_r + 1, size=TWINS)
            tw = twin_nets(r1s, lastc, mh, ml, mo, opts.contracts, cp, step, t["stop_pts"], t["tgt_pts"], lot, day,
                           und, cache)
            r2s = rng2.integers(r0, hi_r + 1, size=TWINS)                     # N2: after-signal twins
            tw2 = twin_nets(r2s, lastc, mh, ml, mo, opts.contracts, cp, step, t["stop_pts"], t["tgt_pts"], lot, day,
                            und, cache)
            rows.append(dict(t, variant=v, und=und, series=src.series, net=tr[0], netx2=tr[1], twins=tw,
                             twins_after=tw2))
    print(f"R131 {part} {und}: " + ", ".join(f"{v} {sum(r['variant'] == v for r in rows)}" for v in VS),
          flush=True)
    return dict(rows=rows, meta=meta, sessions=sorted({d for d in g.days if keep(d)}))


# ------------------------------------------------------------------------------------------------ statistics (R130)
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
    T = T.sort_values(["day", "entry"])
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
                exits={k: int(v) for k, v in T.why.value_counts().items()}, buys=float((T.side > 0).mean()))


def stats(T: pd.DataFrame, by_fold: bool) -> dict:
    T = T.sort_values(["day", "und", "entry"]).reset_index(drop=True)
    s = money(T)
    if not s["n"]:
        return s
    s["p_twins"], s["twin_mean_total"], s["twin_trades"] = twin_p(T)
    if "twins_after" in T:
        s["p_twins_after"], s["twin_after_mean_total"], _ = twin_p(T.assign(twins=T.twins_after))
    s["by_year"] = {int(y): money(X) for y, X in T.groupby([d.year for d in T.day])}
    s["by_und"] = {u: money(X) for u, X in T.groupby("und")}
    if by_fold:
        f = [K.fold_of(d) for d in T.day]
        s["by_fold"] = {k: money(T[[x == k for x in f]]) for k in K.FOLDS}
    for blk in ("by_year", "by_und", "by_fold"):
        for x in s.get(blk, {}).values():
            for drop in ("exits", "worst_month_name", "months_green", "months", "buys"):
                x.pop(drop, None)
    return s


class HoldoutError(RuntimeError):
    pass


def holdout_lock(status: str, out: Path = OUT, results: Path = RESULTS, suffix: str = "") -> None:
    """The ONE holdout read (PREREG section 3; N4 for the W family): 'start' refuses once a read has begun or its
    result is committed."""
    p = out / f"HOLDOUT_READ{suffix}.json"
    old = json.loads(p.read_text()) if p.exists() else None
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (results / f"holdout{suffix}.json").exists():
            raise HoldoutError("refused: the holdout was already read (results/holdout.json exists)")
        if old:
            raise HoldoutError(f"refused: a holdout read already started at {old.get('started_at')}")
        rec = dict(status="started", started_at=now)
    else:
        rec = dict(old or {}, status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def run(part: str, fam: str = "main"):
    VS, suf, _ = family(fam)
    from multiprocessing import Pool
    if part == "holdout":
        holdout_lock("start", suffix=suf)
    with Pool(4) as pool:
        res = pool.map(run_index, [(u, part, fam) for u in K.UNDS])
    rows = [r for x in res for r in x["rows"]]
    T = pd.DataFrame(rows)
    out = dict(part=part, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)],
               sessions=len({d for x in res for d in x["sessions"]}), variants={},
               scan={u: x["meta"] for u, x in zip(K.UNDS, res)})
    for v in VS:
        Tv = T[T.variant == v] if len(T) else T
        s = stats(Tv, part == "design")
        s["candidates"] = sum(out["scan"][u][v]["candidates"] for u in K.UNDS)
        s["skips"] = {kk: sum(out["scan"][u][v]["skips"][kk] for u in K.UNDS) for kk in out["scan"][K.UNDS[0]][v]["skips"]}
        s["label"], s["strategy"] = VS[v]["label"], STRATS[VS[v]["s"]]
        out["variants"][v] = s
    qa = R3.bh([out["variants"][v].get("p_twins_after", 1.0) for v in VS])     # N2, descriptive
    for v, q in zip(VS, qa):
        out["variants"][v]["q_after"] = q
    if part == "design":
        ps = [out["variants"][v].get("p_twins", 1.0) for v in VS]
        if fam == "W":                     # N4: BH over all 39 (the 30 from design.json plus these 9)
            main = json.loads((RESULTS / "design.json").read_text())["variants"]
            ps39 = [main[v].get("p_twins", 1.0) for v in VARIANTS] + ps
            qs = R3.bh(ps39)[len(VARIANTS):]
        else:
            qs = R3.bh(ps)
        for v, q in zip(VS, qs):
            s = out["variants"][v]
            s["q"] = q
            folds = [s.get("by_fold", {}).get(f, {}).get("total", 0.0) > 0 for f in K.FOLDS]
            s["design_gates"] = dict(n=s.get("n", 0) >= GATES["design_n"],
                                     net_pos=s.get("avg_net", -1) > 0 and s.get("avg_net_x2", -1) > 0,
                                     folds=sum(folds) >= GATES["folds"], q=q <= GATES["q"])
            s["passes_design"] = all(s["design_gates"].values())
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}{suf}.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    if len(T):
        T.drop(columns=["twins", "twins_after"]).to_parquet(OUT / f"trades_{part}{suf}.parquet")
    if part == "holdout":
        holdout_lock("done", suffix=suf)
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 3), s.get("p_twins"))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design", sys.argv[2] if len(sys.argv) > 2 else "main")
