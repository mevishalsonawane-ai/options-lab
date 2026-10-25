"""R104 pattern helpers (PREREG.md section 5 conventions): one `Pat` per (instrument, timeframe) with the bars, ATR(14),
EMA trends, swings and the order builders every bar-pattern rule uses.

Entry windows when a rule is silent: NSE decisions (bar close) 09:30-14:30; MCX decisions from 09:15 to 30 minutes
before the session's square-off; crypto any time up to 30 minutes before the 23:50 IST square-off. A rule's own window
wins. Orders never outlive their session (the executor closes at the square-off).
"""
from __future__ import annotations

import itertools

import numpy as np

import r104ind as I

_gid = itertools.count()
NAN = float("nan")
TICK = {"IDX": 0.05, "IDXF": 0.05, "MCXI": 1.0, "CRY": 0.1}


def gid(prefix: str = "") -> str:
    return f"{prefix}{next(_gid)}"


class Pat:
    def __init__(self, ctx, tf: int, swing_n: int = 2, bars=None):
        self.ctx = ctx
        self.s = ctx.s
        self.b = bars if bars is not None else ctx.bars(tf)
        b = self.b
        self.tf = tf
        self.n = len(b.c)
        self.atr = I.atr(b.h, b.l, b.c, 14)
        self.trend, self.ema20 = I.ema_trend(b.c, 20, 5)
        self.ema50 = I.ema(b.c, 50)
        self.sw = {swing_n: I.swings(b.h, b.l, swing_n)}
        self.swing_n = swing_n
        self.tick = TICK.get(ctx.market, 0.05)
        if ctx.market == "MCXI":
            self.tick = 0.1 if "NATGAS" in ctx.inst else 1.0
        self.body = np.abs(b.c - b.o)
        self.rng = b.h - b.l
        self.green = b.c > b.o
        self.red = b.c < b.o
        # session ids and the bar index of each session's last bar
        self.last_bar_of_ses = {}
        for k in range(self.n):
            self.last_bar_of_ses[int(b.ses[k])] = k

    # ------------------------------------------------------------------ swings
    def swings(self, n: int | None = None):
        n = n or self.swing_n
        if n not in self.sw:
            self.sw[n] = I.swings(self.b.h, self.b.l, n)
        return self.sw[n]

    def swing_lists(self, n: int | None = None):
        """(highs, lows): each a list of (bar, confirmed_at, value) in time order."""
        n = n or self.swing_n
        key = ("lists", n)
        if key not in self.sw:
            hs, ls = I.swing_lists(self.b.h, self.b.l, n)
            self.sw[key] = ([tuple(x) for x in zip(*hs)], [tuple(x) for x in zip(*ls)])
        return self.sw[key]

    def recent(self, k: int, kind: str, n: int | None = None, sessions: int | None = None, limit: int = 50):
        """Swings ('H' / 'L') confirmed strictly before bar k, most recent first, optionally only those whose bar lies in
        the last `sessions` sessions (inclusive of k's session); at most `limit`."""
        import bisect
        n = n or self.swing_n
        key = ("conf", n, kind)
        if key not in self.sw:
            hs, ls = self.swing_lists(n)
            src = hs if kind == "H" else ls
            self.sw[key] = ([x[1] for x in src], src)
        confs, src = self.sw[key]
        j = bisect.bisect_left(confs, k)
        out = []
        s = self.b.ses[k]
        while j > 0 and len(out) < limit:
            j -= 1
            x = src[j]
            if sessions is not None and self.b.ses[x[0]] < s - sessions:
                break
            out.append(x)
        return out

    def confirmed(self, k: int, n: int | None = None):
        """Swings confirmed at or before bar k: (highs, lows) lists."""
        hs, ls = self.swing_lists(n)
        return [x for x in hs if x[1] <= k], [x for x in ls if x[1] <= k]

    def target_swing(self, k: int, side: int, entry: float, n: int | None = None, back: int = 60):
        """The most recent swing confirmed by bar k beyond the entry in the trade's direction (PREREG 5), else NaN."""
        hs, ls = self.swing_lists(n)
        src = hs if side > 0 else ls
        for bar, conf, v in reversed(src):
            if conf > k:
                continue
            if bar < k - back:
                break
            if (side > 0 and v > entry) or (side < 0 and v < entry):
                return float(v)
        return NAN

    # ------------------------------------------------------------------ time
    def dec_tod(self, k: int) -> int:
        return int(self.b.tod1[k]) + 1

    def ok_time(self, k: int, lo: int | None = None, hi: int | None = None) -> bool:
        mk = self.ctx.market
        t = self.dec_tod(k)
        if mk in ("IDX", "IDXF"):
            lo = 9 * 60 + 30 if lo is None else lo
            hi = 14 * 60 + 30 if hi is None else hi
            return lo <= t <= hi and self.b.i1[k] + 1 < self.s.n and self.s.ses[self.b.i1[k] + 1] == self.b.ses[k]
        sq_i = self.s.sq[self.b.ses[k]]
        sq_t = int(self.s.tod[min(sq_i, self.s.n - 1)])
        lo = (9 * 60 + 15 if mk == "MCXI" else 0) if lo is None else lo
        return lo <= t <= sq_t - 30 and self.b.i1[k] + 1 < self.s.n and self.s.ses[self.b.i1[k] + 1] == self.b.ses[k]

    def same_ses(self, k1: int, k2: int) -> bool:
        return 0 <= k1 < self.n and 0 <= k2 < self.n and self.b.ses[k1] == self.b.ses[k2]

    # ------------------------------------------------------------------ orders
    def start(self, k: int) -> int:
        return int(self.b.i1[k]) + 1

    def end_row(self, k: int, nbars: int) -> int:
        """Last 1-minute row of bar k + nbars (clamped to the session)."""
        j = min(k + nbars, self.n - 1)
        if self.ctx.market in ("DLY", "MCXD", "STK"):          # daily bars: one bar per session, no clamp
            return int(self.b.i1[j])
        while j > k and self.b.ses[j] != self.b.ses[k]:
            j -= 1
        return int(self.b.i1[j])

    def mkt(self, k, side, stop, tgt=NAN, tmax=-1, be=NAN, grp=None, w=1.0, ref=NAN):
        ref = float(self.b.c[k]) if ref != ref else ref
        return (self.start(k), side, 0, NAN, -1, float(stop), float(tgt), int(tmax), float(be), grp or gid(), w, ref)

    def stop_order(self, k, side, lvl, nbars, stop, tgt=NAN, tmax=-1, be=NAN, grp=None, w=1.0):
        return (self.start(k), side, 1, float(lvl), self.end_row(k, nbars), float(stop), float(tgt), int(tmax), float(be),
                grp or gid(), w, float(lvl))

    def limit_order(self, k, side, lvl, valid_end_row, stop, tgt=NAN, tmax=-1, be=NAN, grp=None, w=1.0):
        return (self.start(k), side, 2, float(lvl), int(valid_end_row), float(stop), float(tgt), int(tmax), float(be),
                grp or gid(), w, float(lvl))

    def r_target(self, side, entry, stop, r):
        return entry + side * r * abs(entry - stop)

    @staticmethod
    def after_fill(minutes: int) -> int:
        """A time exit `minutes` bars after the fill (the executor resolves it)."""
        return -(int(minutes) + 2)

    def tmax_minutes(self, k, minutes):
        """The row at whose open a time exit `minutes` after the decision happens (same session or the square-off)."""
        i = self.start(k) + minutes
        return int(min(i, self.s.n - 1))

    # ------------------------------------------------------------------ session facts
    def prev_session_hl(self):
        """Per bar: the previous session's high and low (NaN on the first)."""
        b = self.b
        ses = b.ses
        hi = {}
        lo = {}
        for k in range(self.n):
            s = int(ses[k])
            hi[s] = max(hi.get(s, -np.inf), b.h[k])
            lo[s] = min(lo.get(s, np.inf), b.l[k])
        ph = np.array([hi.get(int(s) - 1, np.nan) for s in ses])
        pl = np.array([lo.get(int(s) - 1, np.nan) for s in ses])
        return ph, pl

    def day_hl_sofar(self):
        """Per bar: the session's high / low up to and including the bar."""
        b = self.b
        h = np.empty(self.n)
        l = np.empty(self.n)
        for k in range(self.n):
            if k == 0 or b.ses[k] != b.ses[k - 1]:
                h[k], l[k] = b.h[k], b.l[k]
            else:
                h[k], l[k] = max(h[k - 1], b.h[k]), min(l[k - 1], b.l[k])
        return h, l


def fit_line(x1, y1, x2, y2):
    s = (y2 - y1) / (x2 - x1) if x2 != x1 else 0.0
    return s, y1 - s * x1


def line_at(line, x):
    return line[0] * x + line[1]


def regress(x, y):
    x = np.asarray(x, float)
    y = np.asarray(y, float)
    xm = x.mean()
    sxx = ((x - xm) ** 2).sum()
    if sxx == 0:
        return 0.0, y.mean()
    s = ((x - xm) * (y - y.mean())).sum() / sxx
    return s, y.mean() - s * xm
