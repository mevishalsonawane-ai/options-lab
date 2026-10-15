"""R53 gold book (PREREG.md section 4, GB1-GB13): each rule run with ITS OWN study script's signal and exit code, as a
per-trade list. Written 15 Oct 2026.

The old scripts are main()-style and print tables of totals. Where a script exposes a function that already returns
trades with times (gold_trend_lock.run) it is called as is. Elsewhere the study's loop is copied here verbatim, with a
comment naming the source lines, and the only additions are bookkeeping (entry / exit time and price) and, where marked,
the PREREG's gap fill (a stop that a bar opens through fills at that open, the worse price; `gap=False` is the study as
written and is what the reproduction uses). The old files are not edited. Indicators and helpers are imported from the
scripts themselves.

A trade is a dict: signal_ts (when the signal was known, UTC), entry_ts (the fill, UTC, naive), exit_ts, side (+1: the
gold studies buy only), entry (price paid, the ask), exit (price received, the bid; for TAS's partial targets the
weighted average), net (USD per standard lot of 100 oz after the study's costs), why, nights (21:00 UTC rollovers held,
Wednesday x3; charged only where the study charged swap: GB13).

Costs (all studies): ask = bid + 0.30 (the studies work on mid = bid + 0.15 and pay half the spread each side),
$7 a lot round trip, swap $40 a lot a night (x3 Wednesday) only in gold_trend_lock.py.
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

RESEARCH = Path(__file__).resolve().parents[3]
for _p in (str(RESEARCH), str(RESEARCH.parent)):
    if _p not in sys.path:
        sys.path.insert(0, _p)

import gold_1h_plus as p1  # noqa: E402
import gold_ai_opinion as ai  # noqa: E402
import gold_candles as gc  # noqa: E402
import gold_session_strats as gs  # noqa: E402
import gold_tas as tas  # noqa: E402
import gold_trend as gt  # noqa: E402
import gold_trend_lock as tl  # noqa: E402
import gold_trend_tune as tu  # noqa: E402
import liquidity_gold as lg  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

H, COMM, SWAP = 0.15, 0.07, 0.40        # USD an ounce: half spread, round-trip commission, swap a night
CUT = gs.CUT                            # 20:40 UTC, gold_session_strats / gold_candles
OHLC = ["open", "high", "low", "close"]
MIN = pd.Timedelta(minutes=1)


# ------------------------------------------------------------------------------------------------ data
def load(path, end=None) -> pd.DataFrame:
    """The shared loader (liquidity_gold.read: ms timestamps -> naive UTC, sorted, de-duplicated) plus Dukascopy's tick
    volume, which the AI-opinion study uses. [end]: keep bars before this time (the reproduction window)."""
    bid = lg.read(path)
    raw = pd.read_csv(path, usecols=["timestamp", "volume"])
    vol = pd.Series(raw.volume.values, index=pd.to_datetime(raw.timestamp, unit="ms"))
    vol = vol[~vol.index.duplicated()]
    bid["volume"] = vol.reindex(bid.index).fillna(0.0).values
    if end is not None:
        bid = bid[bid.index < pd.Timestamp(end)]
    return bid


def nights(t0, t1) -> int:
    """gold_trend.nights: 21:00 UTC rollovers in (t0, t1], weekdays only, Wednesday's counted three times."""
    return gt.nights(pd.Timestamp(t0), pd.Timestamp(t1))


def nights_vec(t0, t1) -> np.ndarray:
    """Vectorised gold_trend.nights for arrays of datetime64 (tested equal)."""
    t0 = np.asarray(t0, dtype="datetime64[m]")
    t1 = np.asarray(t1, dtype="datetime64[m]")
    k0 = ((t0 - np.timedelta64(21 * 60, "m")).astype("datetime64[D]")).astype(np.int64)
    k1 = ((t1 - np.timedelta64(21 * 60, "m")).astype("datetime64[D]")).astype(np.int64)
    lo = int(min(k0.min(), k1.min())) - 1 if k0.size else 0
    hi = int(max(k0.max(), k1.max())) + 1 if k0.size else 0
    days = np.arange(lo, hi + 1)
    dow = (days + 3) % 7                   # 1970-01-01 was a Thursday (dow 3, Monday = 0)
    w = np.where(dow >= 5, 0, np.where(dow == 2, 3, 1))
    cum = np.cumsum(w)
    return cum[k1 - lo] - cum[k0 - lo]


def _tr(signal_ts, entry_ts, exit_ts, entry, exit_, net, why="", swap=False):
    n = nights(entry_ts, exit_ts) if exit_ts > entry_ts else 0
    return dict(signal_ts=pd.Timestamp(signal_ts), entry_ts=pd.Timestamp(entry_ts), exit_ts=pd.Timestamp(exit_ts), side=1,
                entry=float(entry), exit=float(exit_), net=float(net) - (100 * SWAP * n if swap else 0.0), why=why,
                nights=int(n))


class Ctx:
    """The data and the derived series the rules share (built once, lazily)."""

    def __init__(self, bid: pd.DataFrame):
        self.bid = bid
        self._c = {}

    def get(self, key, fn):
        if key not in self._c:
            self._c[key] = fn()
        return self._c[key]

    @property
    def mid(self):               # gold_session_strats.load: mid = bid + 0.15, weekdays only
        return self.get("mid", lambda: (self.bid[OHLC] + H)[self.bid.index.dayofweek < 5])

    @property
    def M(self):
        return self.get("M", lambda: gs.Minutes(self.mid))

    def mbars(self, tf, drop21=False, volume=False):
        """gold_tas / gold_ai_opinion / gold_mtf_lock: mid candles of tf minutes (resample, label left), weekdays."""
        def mk():
            m = self.bid[OHLC + (["volume"] if volume else [])].copy()
            m[OHLC] += H
            m = m[m.index.dayofweek < 5]
            agg = {"open": "first", "high": "max", "low": "min", "close": "last"}
            if volume:
                agg["volume"] = "sum"
            b = m.resample(f"{tf}min", label="left", closed="left").agg(agg).dropna()
            return b[b.index.hour != 21] if drop21 else b
        return self.get(("mbars", tf, drop21, volume), mk)

    def sessions(self, start, end):  # liquidity_gold.sessions with ask = bid + 0.30
        return self.get(("sess", start, end), lambda: lg.sessions(self.bid[OHLC], self.bid[OHLC] + lg.SPREAD, start, end))


# ------------------------------------------------------------------------------------------------ the 1-minute walk
def walk(M, i0, side, stop, target, exit_at=None, gap=True):
    """gold_session_strats.walk (lines 72-94) verbatim, returning also the exit price. [gap] (R53, PREREG section 2):
    a stop that the minute opened through fills at the minute's open (the worse price); gap=False is the study."""
    e = M.o[i0] + side * H
    day = M.day[i0]
    i = i0
    while i < len(M.t) and M.day[i] == day:
        if exit_at is not None and i >= exit_at:
            px = M.o[i] - side * H
            return side * (px - e) - COMM, "signal", i, px
        if M.mod[i] >= CUT:
            px = M.o[i] - side * H
            return side * (px - e) - COMM, "cut-off", i, px
        lo, hi = M.l[i] - H, M.h[i] - H
        if side < 0:
            lo, hi = M.l[i] + H, M.h[i] + H
        if stop is not None and ((side > 0 and lo <= stop) or (side < 0 and hi >= stop)):
            px = stop
            if gap:
                ox = M.o[i] - side * H
                px = min(stop, ox) if side > 0 else max(stop, ox)
            return side * (px - e) - COMM, "stop", i, px
        if target is not None and ((side > 0 and hi >= target) or (side < 0 and lo <= target)):
            return side * (target - e) - COMM, "target", i, target
        i += 1
    i -= 1
    px = M.c[i] - side * H
    return side * (px - e) - COMM, "cut-off", i, px


class FastWalk:
    """The same exits as walk() for a long, found with numpy over the day's remaining minutes (twin candidates only;
    tested equal to walk())."""

    def __init__(self, M):
        self.M = M
        day = M.day
        n = len(day)
        brk = np.r_[np.flatnonzero(day[1:] != day[:-1]) + 1, n]
        self.day_end = np.repeat(brk, np.diff(np.r_[0, brk]))          # exclusive end of each minute's day
        cutpos = np.where(M.mod >= CUT, np.arange(n), n)
        nxt = np.minimum.accumulate(cutpos[::-1])[::-1]                 # first minute >= 20:40 at or after i
        self.cut = np.minimum(nxt, self.day_end)

    def __call__(self, i0, stop, target, exit_at=None, gap=True):
        M = self.M
        e = M.o[i0] + H
        end = self.day_end[i0]
        lim = self.cut[i0]
        sig = exit_at is not None and exit_at < lim
        if sig:
            lim = max(exit_at, i0)
        lo = M.l[i0:lim] - H
        hi = M.h[i0:lim] - H
        s = np.flatnonzero(lo <= stop) if stop is not None else np.empty(0, int)
        t = np.flatnonzero(hi >= target) if target is not None else np.empty(0, int)
        s = s[0] if s.size else None
        t = t[0] if t.size else None
        if s is not None and (t is None or s <= t):
            i = i0 + s
            px = min(stop, M.o[i] - H) if gap else stop
            return px - e - COMM
        if t is not None:
            return target - e - COMM
        if lim < end:
            return M.o[lim] - H - e - COMM
        return M.c[end - 1] - H - e - COMM


def _fast(ctx):
    return ctx.get("fastwalk", lambda: FastWalk(ctx.M))


FIRST_TS = np.datetime64("2024-11-20")


def _walk_trade(M, i0, side, stop, target, exit_at, gap, signal_ts):
    usd, why, ie, px = walk(M, i0, side, stop, target, exit_at, gap)
    e = M.o[i0] + side * H
    return _tr(signal_ts, M.t[i0], M.t[ie], e, px, 100 * usd, why), ie


# ------------------------------------------------------------------------------------------------ GB1 boss option 1
def _gb1_frame(ctx, tf):
    def mk():
        mid = ctx.mid
        b = gs.bars(mid, tf)
        c = b.close
        e9, e21, r = gs.ema(c, 9), gs.ema(c, 21), gs.rsi(c)
        up = (e9 > e21) & (e9.shift() <= e21.shift())
        dn = (e9 < e21) & (e9.shift() >= e21.shift())
        swing_lo, swing_hi = b.low.rolling(10).min(), b.high.rolling(10).max()
        h1 = gs.bars(mid, 60).close
        h1_up = (h1 > gs.ema(h1, 50)).shift(1)         # the last COMPLETED hour
        h1_up = h1_up.reindex(b.index, method="ffill")
        return dict(b=b, up=up, dn=dn, r=r, swing_lo=swing_lo, swing_hi=swing_hi, h1_up=h1_up)
    return ctx.get(("gb1", tf), mk)


def _gb1_plan(M, F, j, side, exit_mode, i0=None):
    """option1's entry checks and exits for an entry after bar j (gold_session_strats.py lines 125-143). [i0]: the
    entry minute when the caller already has it (the same value as M.at(idx[j + 1]))."""
    b, idx = F["b"], F["b"].index
    t = idx[j]
    i0 = M.at(idx[j + 1]) if i0 is None else i0
    if i0 >= len(M.t) or M.day[i0] != np.datetime64(t.normalize()):
        return None
    e = M.o[i0] + side * H
    stop = (F["swing_lo"].iloc[j] - 0.10) if side > 0 else (F["swing_hi"].iloc[j] + 0.10)
    risk = side * (e - stop)
    if not risk > 0.2:
        return None
    target, exit_at = None, None
    if exit_mode == "1R":
        target = e + side * risk
    elif exit_mode == "2R":
        target = e + side * 2 * risk
    else:                                      # the opposite crossover
        opp = F["dn"] if side > 0 else F["up"]
        k = j + 1
        while k < len(b) - 1 and not opp.iloc[k] and idx[k].normalize() == t.normalize():
            k += 1
        exit_at = int(np.searchsorted(M.t, idx.values[min(k + 1, len(b) - 1)].astype(M.t.dtype)))
    return i0, stop, target, exit_at


def gb1(ctx, exit_mode="2R", tf=15, bias=True, gap=True, buys_only=True):
    """gold_session_strats.option1 (lines 97-146) verbatim: both sides are traded (one position at a time, as the
    study), then the buys are kept, as the study's "buys only" rows."""
    M, F = ctx.M, _gb1_frame(ctx, tf)
    b, up, dn, r, h1_up = F["b"], F["up"], F["dn"], F["r"], F["h1_up"]
    out, busy_until, idx = [], None, b.index
    for j in range(25, len(b) - 1):
        t = idx[j]
        mod = t.hour * 60 + t.minute + tf          # the signal is known when the bar closes
        if not (gs.START <= mod < gs.END) or t.dayofweek > 4:
            continue
        if busy_until is not None and t < busy_until:
            continue
        side = 1 if (up.iloc[j] and r.iloc[j] > 50) else -1 if (dn.iloc[j] and r.iloc[j] < 50) else 0
        if side == 0:
            continue
        if bias:
            hb = h1_up.iloc[j]
            if (side > 0 and hb is not True and hb != True) or (side < 0 and not (hb is False or hb == False)) or pd.isna(hb):  # noqa: E712
                continue
        plan = _gb1_plan(M, F, j, side, exit_mode)
        if plan is None:
            continue
        i0, stop, target, exit_at = plan
        trd, ie = _walk_trade(M, i0, side, stop, target, exit_at, gap, t + pd.Timedelta(minutes=tf))
        trd["side"] = side
        out.append(trd)
        busy_until = pd.Timestamp(M.t[ie])
    return [x for x in out if x["side"] > 0] if buys_only else out


def gb1_candidates(ctx, exit_mode="2R", tf=15, gap=True):
    """Random-entry twins: a buy after ANY bar whose close falls in the rule's 07:00-16:00 window, with the rule's own
    stop (10-bar low), risk check and exit."""
    M, F, W = ctx.M, _gb1_frame(ctx, tf), _fast(ctx)
    idx = F["b"].index
    mod = idx.hour * 60 + idx.minute + tf
    starts = np.searchsorted(M.t, idx.values.astype(M.t.dtype))
    out = []
    for j in range(max(25, int(np.searchsorted(idx.values, FIRST_TS))), len(idx) - 1):
        if not (gs.START <= mod[j] < gs.END) or idx[j].dayofweek > 4:
            continue
        plan = _gb1_plan(M, F, j, 1, exit_mode, int(starts[j + 1]))
        if plan is None:
            continue
        i0, stop, target, exit_at = plan
        out.append((M.t[i0], 100 * W(i0, stop, target, exit_at, gap)))
    return out


# ------------------------------------------------------------------------------------------------ GB2 boss option 2
def _gb2_days(ctx, tf):
    def mk():
        b = gs.bars(ctx.mid, tf)
        res = []
        for day, g_ in b.groupby(b.index.normalize()):
            if day.dayofweek > 4:
                continue
            mod = g_.index.hour * 60 + g_.index.minute
            asia = g_[mod < gs.START]
            if len(asia) < 0.6 * gs.START / tf:
                continue
            ah, al = asia.high.max(), asia.low.min()
            rng = ah - al
            if rng <= 0.5:
                continue
            sess = g_[(mod + tf >= gs.START + tf) & (mod + tf <= gs.END)]
            res.append((day, ah, al, rng, sess))
        return res
    return ctx.get(("gb2", tf), mk)


def gb2(ctx, mult=1.0, tf=5, gap=True, buys_only=True):
    """gold_session_strats.option2 (lines 149-180) verbatim (one trade a day, both sides, then the buys kept)."""
    M, out = ctx.M, []
    for day, ah, al, rng, sess in _gb2_days(ctx, tf):
        for k in range(len(sess) - 1):
            r = sess.iloc[k]
            side = 1 if (r.low < al and r.close > al) else -1 if (r.high > ah and r.close < ah) else 0
            if side == 0:
                continue
            i0 = M.at(sess.index[k + 1])
            if i0 >= len(M.t):
                break
            e = M.o[i0] + side * H
            stop = (r.low - 0.10) if side > 0 else (r.high + 0.10)
            if side * (e - stop) <= 0.2:
                break
            target = e + side * mult * rng
            trd, _ = _walk_trade(M, i0, side, stop, target, None, gap, sess.index[k] + pd.Timedelta(minutes=tf))
            trd["side"] = side
            out.append(trd)
            break                                  # one trade a day
    return [x for x in out if x["side"] > 0] if buys_only else out


def gb2_candidates(ctx, mult=1.0, tf=5, gap=True):
    """Random-entry twins: a buy after ANY session bar (07:00-16:00) of a day with a valid Asian range, stop under that
    bar's low, target the range x mult from the entry (the rule's own exits)."""
    M, W, out = ctx.M, _fast(ctx), []
    for day, ah, al, rng, sess in _gb2_days(ctx, tf):
        if day < pd.Timestamp(FIRST_TS):
            continue
        lows = sess.low.values
        starts = np.searchsorted(M.t, sess.index.values.astype(M.t.dtype))
        for k in range(len(sess) - 1):
            i0 = int(starts[k + 1])
            if i0 >= len(M.t):
                break
            e = M.o[i0] + H
            stop = lows[k] - 0.10
            if e - stop <= 0.2:
                continue
            out.append((M.t[i0], 100 * W(i0, stop, e + mult * rng, None, gap)))
    return out


# ------------------------------------------------------------------------------------------------ GB3 / GB4 TAS
def _tas_frame(ctx, tf, n_atr=19):
    def mk():
        b = ctx.mbars(tf, drop21=True)
        trk, d, pct = tas.indicators(b, n_atr)
        return b, trk, d, pct
    return ctx.get(("tas", tf, n_atr), mk)


def tas_trades(b, trk, d, pct, side=1, late=True, tps=tas.TPS, tf=15):
    """gold_tas.trades (lines 90-147) verbatim without its unused options (day_end, lock), recording the exit bar."""
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    n, out, i, flip_at = len(c), [], 1, None
    idx = b.index
    span = pd.Timedelta(minutes=tf) - MIN
    while i < n - 1:
        if d[i] == side and d[i - 1] != side:
            flip_at = i
        if d[i] != side:
            flip_at = None
        go = flip_at is not None and (i - flip_at <= (tas.LATE if late else 0)) and np.isfinite(pct[i]) and side * pct[i] >= tas.MIN_SCORE
        if not go:
            i += 1; continue
        e = o[i + 1] + side * H
        stop = trk[i]
        r = side * (e - stop)
        if r <= 0:
            i += 1; continue
        left, pnl, be, j, done = 1.0, 0.0, False, i + 1, False
        hit = [False] * len(tps)
        x_ts, why = None, ""
        while j < n:
            lo, hi = (l[j] - H, h[j] - H) if side > 0 else (l[j] + H, h[j] + H)
            ox = o[j] - side * H
            if (side > 0 and lo <= stop) or (side < 0 and hi >= stop):
                px = min(stop, ox) if side > 0 else max(stop, ox)
                pnl += left * side * (px - e); left = 0; done = True
                x_ts, why = (idx[j] if px == ox else idx[j] + span), "stop"; break
            moved = False
            for k, (m, q) in enumerate(tps):
                tgt = e + side * m * r
                if not hit[k] and ((side > 0 and hi >= tgt) or (side < 0 and lo <= tgt)):
                    q = left if k == len(tps) - 1 else q
                    pnl += q * side * (tgt - e); left -= q; hit[k] = True; moved = True
            if left <= 1e-9:
                done = True; x_ts, why = idx[j] + span, "targets"; break
            if moved and not be:
                stop, be = e, True          # breakeven from the next bar
            if d[j] != side and j + 1 < n:  # the tracker turned: out at the next open
                pnl += left * side * (o[j + 1] - side * H - e); left = 0; j += 1; done = True
                x_ts, why = idx[j], "tracker"; break
            j += 1
        if not done:
            pnl += left * side * (c[n - 1] - side * H - e)
            x_ts, why = idx[n - 1] + span, "data end"
        out.append(_tr(idx[i] + pd.Timedelta(minutes=tf), idx[i + 1], x_ts, e, e + side * pnl, 100 * (pnl - COMM), why))
        flip_at = None
        i = j + 1 if d[min(j, n - 1)] == side else j  # one trade per up-trend
        while i < n and d[i] == side and d[i - 1] == side:
            i += 1
    return out


def gb_tas(ctx, tf=15, targets=True, n_atr=19):
    b, trk, d, pct = _tas_frame(ctx, tf, n_atr)
    return tas_trades(b, trk, d, pct, 1, True, tas.TPS if targets else (), tf)


# ------------------------------------------------------------------------------------------------ GB5 AI opinion
def _ai_frame(ctx, tf):
    def mk():
        b = ctx.mbars(tf, drop21=True, volume=True)
        sigs, atr = ai.signals(b)
        return b, sigs["Robust"], atr
    return ctx.get(("ai", tf), mk)


def ai_exit(o, h, l, c, a, i, side=1, gap=True):
    """gold_ai_opinion.trade's exit (lines 86-97) for an entry after bar i: stop 1 ATR under the signal close, target
    2 ATR over, 100 bars. Returns (exit bar, exit price, why). [gap]: R53's worse-price stop fill."""
    n = len(c)
    stop, tgt = c[i] - side * a[i], c[i] + 2 * side * a[i]
    j, px, why = i + 1, None, "time"
    while j < min(n, i + 1 + ai.MAXH):
        lo, hi = (l[j] - H, h[j] - H) if side > 0 else (l[j] + H, h[j] + H)
        if (side > 0 and lo <= stop) or (side < 0 and hi >= stop):
            px = stop
            if gap:
                ox = o[j] - side * H
                px = min(stop, ox) if side > 0 else max(stop, ox)
            why = "stop"; break
        if (side > 0 and hi >= tgt) or (side < 0 and lo <= tgt):
            px = tgt; why = "target"; break
        j += 1
    if px is None:
        j = min(n, i + 1 + ai.MAXH) - 1; px = c[j] - side * H
    return j, px, why


def gb5(ctx, tf=60, gap=True):
    """gold_ai_opinion.trade (lines 78-99) verbatim, Robust signal, buys."""
    b, sig, atr = _ai_frame(ctx, tf)
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    s, a = sig.values, atr.values
    n, out, i, side, idx = len(c), [], 1, 1, b.index
    span = pd.Timedelta(minutes=tf) - MIN
    while i < n - 1:
        if not (s[i] == side and s[i - 1] != side) or not np.isfinite(a[i]):
            i += 1; continue
        e = o[i + 1] + side * H
        j, px, why = ai_exit(o, h, l, c, a, i, side, gap)
        x_ts = idx[j] if (why == "stop" and px == o[j] - H) else idx[j] + span
        out.append(_tr(idx[i] + pd.Timedelta(minutes=tf), idx[i + 1], x_ts, e, px, 100 * (side * (px - e) - COMM), why))
        i = j + 1
    return out


def gb5_candidates(ctx, tf=60, gap=True):
    """Random-entry twins: a buy at the next open after ANY bar, the indicator's own stop / target / 100 bars."""
    b, sig, atr = _ai_frame(ctx, tf)
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    a, idx, out = atr.values, b.index, []
    for i in range(max(1, int(np.searchsorted(idx.values, FIRST_TS)) - 1), len(c) - 1):
        if not np.isfinite(a[i]):
            continue
        e = o[i + 1] + H
        j, px, _ = ai_exit(o, h, l, c, a, i, 1, gap)
        out.append((idx[i + 1], 100 * (px - e - COMM)))
    return out


# ------------------------------------------------------------------------------------------------ GB6 reopen drift
def gb6_reopen(ctx, a=5, b_=120):
    """gold_reopen.by_reopen (lines 67-94) verbatim: buy at the reopen (first minute after a 30+ minute gap) + a min,
    sell at + b min, at the minutes' opens (both must exist)."""
    mid = ctx.bid[OHLC] + H
    t = mid.index
    gap = np.r_[np.inf, np.diff(t.values).astype("timedelta64[m]").astype(float)]
    reopen = t[gap >= 30]
    o = mid.open
    out = []
    for r in reopen:
        e, x = r + pd.Timedelta(minutes=a), r + pd.Timedelta(minutes=b_)
        pe, px = o.get(e), o.get(x)
        if pe is None or px is None:
            continue
        out.append(_tr(e, e, x, pe + H, px - H, 100 * ((px - H) - (pe + H) - COMM), "time"))
    return out


def gb6_fixed(ctx, a="23:00", b_="23:55"):
    """gold_reopen.main (lines 37-53) verbatim for one fixed UTC window, every weekday night but Friday's."""
    mid = ctx.bid[OHLC] + H
    o = mid.open
    ah, am = map(int, a.split(":")); bh, bm = map(int, b_.split(":"))
    ts = o.index
    ent = o[(ts.hour == ah) & (ts.minute == am)]
    out = []
    for t, px in ent.items():
        end = t.normalize() + pd.Timedelta(hours=bh, minutes=bm)
        if end <= t:
            end += pd.Timedelta(days=1)
        if t.dayofweek == 4 and ah >= 21:          # no reopen on Friday night
            continue
        ex = o.get(end)
        if ex is None or np.isnan(ex):
            continue
        out.append(_tr(t, t, end, px + H, ex - H, 100 * ((ex - H) - (px + H) - COMM), "time"))
    return out


# ------------------------------------------------------------------------------------------------ liquidity (GB7, GB10)
def lg_simulate(days, b, zones, first, last, cut, start):
    """liquidity_gold.simulate (lines 87-137) verbatim with carry=False, recording entry / exit minutes and prices.
    Returns trades of both signs (the caller keeps the buys)."""
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    swings = [z for z in zones if z.kind == "swing"]
    trades, pos = [], None
    at = lambda di, mm: pd.Timestamp(days[di]["day"]) + pd.Timedelta(minutes=int(start + mm))  # noqa: E731
    for i in range(len(b)):
        d = days[DI[i]]
        if pos is not None:
            sg = pos["sign"]
            why, px, m = None, None, None
            for m in range(max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i], E[i]):
                if m >= cut:
                    why, px = "cut-off", d["bclose" if sg > 0 else "aclose"][m]
                    break
                if pos["target"] is not None and ((sg > 0 and d["mhigh"][m] >= pos["target"]) or (sg < 0 and d["mlow"][m] <= pos["target"])):
                    half = (d["aclose"][m] - d["bclose"][m]) / 2
                    why, px = "next liquidity", pos["target"] - sg * half
                    break
            if why is None and sg * (C[i] - pos["level"]) < 0:
                why, px = "failed break", d["bclose" if sg > 0 else "aclose"][E[i] - 1]
            if why is None and any(z.side == sg for z in known.get(i, [])):
                why, px = "new liquidity", d["bclose" if sg > 0 else "aclose"][E[i] - 1]
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, px = "cut-off", d["bclose" if sg > 0 else "aclose"][E[i] - 1]
            if why:
                xm = m if why in ("cut-off", "next liquidity") and m is not None and m < E[i] else E[i] - 1
                usd = sg * (px - pos["px"]) - lg.COMM
                tr_ = _tr(pos["sig"], at(pos["di"], pos["m"]), at(DI[i], xm), pos["px"], px, 100 * usd, why)
                tr_["side"] = sg
                trades.append(tr_)
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (first <= m <= last):
            continue
        pl = [z for z in breaks.get(i, []) if z.kind == "pool"]
        cand = [p for p in pl if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                     and (s.broken < 0 or s.broken >= i) for s in swings)]
        if not cand:
            continue
        z = cand[0]
        sg = z.side
        px = d["aopen" if sg > 0 else "bopen"][m]
        mid = d["mopen"][m]
        ahead = [q.edge for q in zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i) and sg * (q.edge - mid) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        pos = dict(day=d["day"], di=DI[i], sign=sg, m=m, px=px, level=z.edge, target=target, sig=at(DI[i], E[i]))
    return trades


def _lg_book(ctx, session, tf, conf=10):
    start, end, first, last, cut = lg.SESSIONS[session]

    def mk():
        days = ctx.sessions(start, end)
        b = lg.bars(days, tf)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, conf)
        return lg_simulate(days, b, zones, first, last, cut, start)
    return ctx.get(("lg", session, tf, conf), mk)


def gb_liq_india(ctx, tfs=(60, 15)):
    """GB7 (liquidity_gold_1h.py, India hours, 1h + 15m books) and GB10 (liquidity_gold.py, India hours, 15 + 5):
    the books run side by side, buys kept."""
    out = []
    for tf in tfs:
        out += [x for x in _lg_book(ctx, "India hours", tf) if x["side"] > 0]
    return sorted(out, key=lambda x: x["entry_ts"])


# ------------------------------------------------------------------------------------------------ 24x5 (GB8, GB9)
def p1_simulate(days, b, zones, second_target=False):
    """gold_1h_plus.simulate (lines 33-81) verbatim with allow=None, new_liq_exit=True, recording the exit."""
    START, FRIDAY_CUT, FIRST, LAST = p1.START, p1.FRIDAY_CUT, p1.FIRST, p1.LAST
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    swings = [z for z in zones if z.kind == "swing"]
    trades, pos = [], None
    at = lambda di, mm: pd.Timestamp(days[di]["day"]) + pd.Timedelta(minutes=int(START + mm))  # noqa: E731
    for i in range(len(b)):
        d = days[DI[i]]
        friday = d["day"].weekday() == 4
        if pos is not None:
            why, px, xm = None, None, None
            for m in range(max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i], E[i]):
                if friday and m >= FRIDAY_CUT - START:
                    why, px, xm = "cut-off", d["bclose"][m], m
                    break
                if pos["target"] is not None and d["mhigh"][m] >= pos["target"]:
                    why, px, xm = "next liquidity", pos["target"] - (d["aclose"][m] - d["bclose"][m]) / 2, m
                    break
            if why is None and C[i] - pos["level"] < 0:
                why, px, xm = "failed break", d["bclose"][E[i] - 1], E[i] - 1
            if why is None and any(z.side > 0 for z in known.get(i, [])):
                why, px, xm = "new liquidity", d["bclose"][E[i] - 1], E[i] - 1
            if why is None and (friday and (i + 1 >= len(b) or DI[i + 1] != DI[i]) or i + 1 >= len(b)):
                why, px, xm = "cut-off", d["bclose"][E[i] - 1], E[i] - 1
            if why:
                trades.append(_tr(pos["sig"], at(pos["di"], pos["m"]), at(DI[i], xm), pos["px"], px,
                                  100 * (px - pos["px"] - lg.COMM), why))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (FIRST <= m <= LAST) or (friday and m + START > 19 * 60):
            continue
        pl = [z for z in breaks.get(i, []) if z.kind == "pool" and z.side > 0]
        cand = [p for p in pl if any(s.side > 0 and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                     and (s.broken < 0 or s.broken >= i) for s in swings)]
        if not cand:
            continue
        z = cand[0]
        mid = d["mopen"][m]
        ahead = sorted(q.edge for q in zones if q.side > 0 and q.known <= i and (q.broken < 0 or q.broken > i) and q.edge - mid > 0)
        tgt = (ahead[1] if second_target and len(ahead) > 1 else ahead[0]) if ahead else None
        pos = dict(day=d["day"], di=DI[i], m=m, px=d["aopen"][m], level=z.edge, target=tgt, sig=at(DI[i], E[i]))
    return trades


def gb_24x5(ctx, tf=60, conf=20, second=True):
    """GB8 (gold_timeframes.py: 2h, confirmation 15, second level) and GB9 (gold_1h_plus.py: 1h, confirmation 20,
    second level): 24x5 sessions (00:00-24:00 UTC), buys, held overnight, out by Friday 20:40."""
    def mk():
        days = ctx.sessions(p1.START, p1.END)
        b = lg.bars(days, tf)
        return p1_simulate(days, b, swing_zones(b, 20, "full") + pool_zones(b, 2, 5, conf), second_target=second)
    return ctx.get(("24x5", tf, conf, second), mk)


# ------------------------------------------------------------------------------------------------ GB11 candles
def _gc_frame(ctx, tf):
    def mk():
        b = gs.bars(ctx.mid, tf)
        P, lo3, hi3 = gc.patterns(b)
        ema50 = b.close.ewm(span=50, adjust=False).mean().values
        return b, P, lo3, hi3, ema50
    return ctx.get(("gc", tf), mk)


def _gc_plan(M, b, starts, mod, tf, j, side, lo3, hi3, exit_mode):
    i0 = starts[j + 1]
    if i0 >= len(M.t) or M.day[i0] != M.day[starts[j]]:
        return None
    e = M.o[i0] + side * gs.H
    stop = (lo3[j] - 0.10) if side > 0 else (hi3[j] + 0.10)
    risk = side * (e - stop)
    if not risk > 0.3:
        return None
    target, exit_at = None, None
    if exit_mode == "1R":
        target = e + side * risk
    elif exit_mode == "2R":
        target = e + side * 2 * risk
    else:
        exit_at = i0 + 4 * tf
    return i0, stop, target, exit_at


def gb11(ctx, exit_mode="2R", pattern="hammer / pin bar", tf=60, trend=False, gap=True, buys_only=True):
    """gold_candles.run (lines 64-98) verbatim (both sides, one at a time, then the buys kept)."""
    M = ctx.M
    b, P, lo3, hi3, ema50 = _gc_frame(ctx, tf)
    sig_long, sig_short = P[pattern]
    idx = b.index
    starts = np.searchsorted(M.t, idx.values)
    mod = (idx.hour * 60 + idx.minute).values
    c = b.close.values
    out, busy = [], -1
    for j in range(55, len(b) - 1):
        side = 1 if sig_long[j] else -1 if sig_short[j] else 0
        if side == 0:
            continue
        m_next = mod[j] + tf
        if (21 * 60 <= m_next < 22 * 60) or m_next >= 20 * 60 + 40 or idx[j].dayofweek > 4:
            continue
        if trend and ((side > 0 and not c[j] > ema50[j]) or (side < 0 and not c[j] < ema50[j])):
            continue
        i0 = starts[j + 1]
        if i0 <= busy:
            continue
        plan = _gc_plan(M, b, starts, mod, tf, j, side, lo3, hi3, exit_mode)
        if plan is None:
            continue
        i0, stop, target, exit_at = plan
        trd, ie = _walk_trade(M, i0, side, stop, target, exit_at, gap, idx[j] + pd.Timedelta(minutes=tf))
        trd["side"] = side
        out.append(trd)
        busy = ie
    return [x for x in out if x["side"] > 0] if buys_only else out


def gb11_candidates(ctx, exit_mode="2R", tf=60, gap=True):
    """Random-entry twins: a buy after ANY 1h candle allowed by the rule's clock (not into the 21:00 break, signal
    before 20:40, weekdays), stop under the last three candles' low, the rule's own exit."""
    M, W = ctx.M, _fast(ctx)
    b, P, lo3, hi3, ema50 = _gc_frame(ctx, tf)
    idx = b.index
    starts = np.searchsorted(M.t, idx.values)
    mod = (idx.hour * 60 + idx.minute).values
    out = []
    for j in range(max(55, int(np.searchsorted(idx.values, FIRST_TS))), len(b) - 1):
        m_next = mod[j] + tf
        if (21 * 60 <= m_next < 22 * 60) or m_next >= 20 * 60 + 40 or idx[j].dayofweek > 4:
            continue
        plan = _gc_plan(M, b, starts, mod, tf, j, 1, lo3, hi3, exit_mode)
        if plan is None:
            continue
        i0, stop, target, exit_at = plan
        out.append((M.t[i0], 100 * W(i0, stop, target, exit_at, gap)))
    return out


# ------------------------------------------------------------------------------------------------ GB12 dip + lock
def _mtf_frame(ctx):
    def mk():
        m30 = gs.bars(ctx.mid, 30); m30 = m30[m30.index.hour != 21]
        h1 = gs.bars(ctx.mid, 60); h1 = h1[h1.index.hour != 21]
        # gold_mtf_lock.py lines 34-47 verbatim
        o, hi, lo, c = (m30[k].values for k in ("open", "high", "low", "close"))
        idx = m30.index
        tr = np.maximum(hi - lo, np.maximum(np.abs(hi - np.r_[c[0], c[:-1]]), np.abs(lo - np.r_[c[0], c[:-1]])))
        atr = pd.Series(tr).ewm(alpha=1 / 14, adjust=False).mean().values
        col = np.sign(c - o)
        hc = np.sign(h1.close.values - h1.open.values)
        hend = (h1.index + pd.Timedelta(hours=1)).values
        lh = np.searchsorted(hend, (idx + pd.Timedelta(minutes=30)).values, side="right") - 1
        gg = (lh >= 1) & (hc[np.maximum(lh, 0)] > 0) & (hc[np.maximum(lh - 1, 0)] > 0)
        sig = gg & (col > 0) & (np.r_[0, col[:-1]] < 0)
        gap_after = np.r_[(idx[1:] - idx[:-1]) > pd.Timedelta(minutes=60), True]   # the last candle before a break
        return dict(o=o, hi=hi, lo=lo, c=c, idx=idx, atr=atr, sig=sig, gap_after=gap_after)
    return ctx.get("mtf", mk)


def mtf_exit(F, i, stop_kind, stop_x, lock, lock_x, maxn):
    """gold_mtf_lock.main's run() body (lines 55-74) verbatim for an entry after candle i."""
    o, hi, lo, c, atr, gap_after = F["o"], F["hi"], F["lo"], F["c"], F["atr"], F["gap_after"]
    n = len(c)
    k = i + 1
    e = o[k] + H; a = atr[i]
    stop = -np.inf
    if stop_kind == "atr":
        stop = e - stop_x * a
    elif stop_kind == "pullback":
        stop = lo[i - 1] - H - 0.10
    peak = -np.inf; end = None
    for j in range(k, min(k + maxn, n)):
        bl, bh, bo = lo[j] - H, hi[j] - H, o[j] - H
        if bl <= stop:
            end = (j, min(stop, bo), "stop/lock"); break
        peak = max(peak, bh); gain = peak - e
        if lock == "breakeven" and gain >= a:
            stop = max(stop, e + COMM)
        elif lock == "giveback" and gain >= a:
            stop = max(stop, peak - lock_x * a)
        elif lock == "half" and gain >= 2 * a:
            stop = max(stop, e + 0.5 * gain)
        if gap_after[j]:
            end = (j, c[j] - H, "break"); break
    if end is None:
        j = min(k + maxn, n) - 1; end = (j, c[j] - H, "time")
    return e, end


def gb12(ctx, lock="half", lock_x=0, maxn=16, stop_kind="none", stop_x=0):
    """gold_mtf_lock.main's run() (lines 50-75): two green hours, a red then a green 30m candle -> buy."""
    F = _mtf_frame(ctx)
    sig, gap_after, idx, n = F["sig"], F["gap_after"], F["idx"], len(F["c"])
    out, i = [], 0
    span = pd.Timedelta(minutes=29)
    while i < n - 1:
        if not sig[i] or gap_after[i]:
            i += 1; continue
        e, end = mtf_exit(F, i, stop_kind, stop_x, lock, lock_x, maxn)
        j, px, why = end
        x_ts = idx[j] if (why == "stop/lock" and px == F["o"][j] - H) else idx[j] + span
        out.append(_tr(idx[i] + pd.Timedelta(minutes=30), idx[i + 1], x_ts, e, px, 100 * (px - e - COMM), why))
        i = j + 1
    return out


def gb12_candidates(ctx, lock="half", lock_x=0, maxn=16):
    """Random-entry twins: a buy after ANY 30m candle that is not the last before a break, the rule's own lock / 8 h."""
    F = _mtf_frame(ctx)
    idx, out = F["idx"], []
    for i in range(max(1, int(np.searchsorted(idx.values, FIRST_TS)) - 1), len(F["c"]) - 1):
        if F["gap_after"][i]:
            continue
        e, (j, px, _) = mtf_exit(F, i, "none", 0, lock, lock_x, maxn)
        out.append((idx[i + 1], 100 * (px - e - COMM)))
    return out


# ------------------------------------------------------------------------------------------------ GB13 trend lock
def gb13(ctx, x=3, rejoin=False):
    """gold_trend_lock.run called as is (Supertrend(10, 3) on 4h, giveback x ATR, wait), set up as its main() does
    (lines 71-81); net with the study's swap."""
    def mk():
        mid = ctx.mid
        h1, h4 = gs.bars(mid, 60), gs.bars(mid, 240)
        d, _ = tu.supertrend_line(h4, 10, 3.0)
        up = gt.to_hours(d, h1)
        A = gt.to_hours(gt.atr(h4, 14), h1).bfill()
        stamp = pd.Series(np.arange(len(h4)), index=h4.index)
        k4 = gt.to_hours(stamp, h1)
        newbar = (k4 != k4.shift()).fillna(False)
        return h1, up, newbar, A
    h1, up, newbar, A = ctx.get("trend", mk)
    out = []
    for t0, e, t1, xx in tl.run(h1, up, newbar, A, "giveback", x, rejoin):
        out.append(_tr(t0, t0, t1, e, xx, 100 * (xx - e - COMM), "", swap=True))
    return out


# ------------------------------------------------------------------------------------------------ the registry
# twin: ("random", candidates(ctx) -> [(entry_ts, net)]) or ("hold", window(minute_of_day, weekday) -> bool, swap)
def _any_time(mod, dow):
    return dow < 5


def _india(mod, dow):                      # India-hours entries 03:50-09:00 UTC (liquidity_gold.SESSIONS)
    return (dow < 5) & (mod >= 3 * 60 + 50) & (mod <= 9 * 60)


def _24x5(mod, dow):                       # gold_1h_plus: entries 00:05-23:00, Friday not after 19:00
    return (dow < 5) & (mod >= 5) & (mod <= 23 * 60) & ~((dow == 4) & (mod > 19 * 60))


RULES = {
    "GB1": {"name": "boss-opt1 (M15 EMA9/21+RSI, H1 bias)", "src": "gold_session_strats.py option1", "variants": {
        "orig": dict(fn=lambda c: gb1(c, "2R"), twin=("random", lambda c: gb1_candidates(c, "2R"))),
        "rt_1R": dict(fn=lambda c: gb1(c, "1R"), twin=("random", lambda c: gb1_candidates(c, "1R"))),
        "rt_oppcross": dict(fn=lambda c: gb1(c, "opposite cross"), twin=("random", lambda c: gb1_candidates(c, "opposite cross"))),
    }},
    "GB2": {"name": "boss-opt2 (M5 Asian-range sweep)", "src": "gold_session_strats.py option2", "variants": {
        "orig": dict(fn=lambda c: gb2(c, 1.0), twin=("random", lambda c: gb2_candidates(c, 1.0))),
        "rt_200": dict(fn=lambda c: gb2(c, 2.0), twin=("random", lambda c: gb2_candidates(c, 2.0))),
    }},
    "GB3": {"name": "tas-15m (ATR 19, flip + delayed)", "src": "gold_tas.py", "variants": {
        "orig": dict(fn=lambda c: gb_tas(c, 15, True), twin=("hold", _any_time, False)),
        "rt_notargets": dict(fn=lambda c: gb_tas(c, 15, False), twin=("hold", _any_time, False)),
    }},
    "GB4-4h": {"name": "tas-4h with targets", "src": "gold_tas.py", "variants": {
        "orig": dict(fn=lambda c: gb_tas(c, 240, True), twin=("hold", _any_time, False)),
    }},
    "GB4-30m": {"name": "tas-30m no targets", "src": "gold_tas.py (gold_tas_lock.py row)", "variants": {
        "orig": dict(fn=lambda c: gb_tas(c, 30, False), twin=("hold", _any_time, False)),
    }},
    "GB5-1h": {"name": "ai-opinion Robust 1h", "src": "gold_ai_opinion.py", "variants": {
        "orig": dict(fn=lambda c: gb5(c, 60), twin=("random", lambda c: gb5_candidates(c, 60))),
    }},
    "GB5-4h": {"name": "ai-opinion Robust 4h", "src": "gold_ai_opinion.py", "variants": {
        "orig": dict(fn=lambda c: gb5(c, 240), twin=("random", lambda c: gb5_candidates(c, 240))),
    }},
    "GB6": {"name": "reopen-drift (reopen +5 to +120 min)", "src": "gold_reopen.py by_reopen", "variants": {
        "orig": dict(fn=lambda c: gb6_reopen(c, 5, 120), twin=("hold", _any_time, False)),
        "rt_fixed2300": dict(fn=lambda c: gb6_fixed(c, "23:00", "23:55"), twin=("hold", _any_time, False)),
    }},
    "GB7": {"name": "liq-india-1h15", "src": "liquidity_gold_1h.py", "variants": {
        "orig": dict(fn=lambda c: gb_liq_india(c, (60, 15)), twin=("hold", _india, False)),
    }},
    "GB8": {"name": "liq-2h (confirm 15, second level)", "src": "gold_timeframes.py", "variants": {
        "orig": dict(fn=lambda c: gb_24x5(c, 120, 15, True), twin=("hold", _24x5, False)),
    }},
    "GB9": {"name": "liq-1h-conf20 (second level)", "src": "gold_1h_plus.py", "variants": {
        "orig": dict(fn=lambda c: gb_24x5(c, 60, 20, True), twin=("hold", _24x5, False)),
    }},
    "GB10": {"name": "liq-india-15+5", "src": "liquidity_gold.py", "variants": {
        "orig": dict(fn=lambda c: gb_liq_india(c, (15, 5)), twin=("hold", _india, False)),
    }},
    "GB11": {"name": "hammer-1h", "src": "gold_candles.py", "variants": {
        "orig": dict(fn=lambda c: gb11(c, "2R"), twin=("random", lambda c: gb11_candidates(c, "2R"))),
        "rt_4candles": dict(fn=lambda c: gb11(c, "4 candles"), twin=("random", lambda c: gb11_candidates(c, "4 candles"))),
    }},
    "GB12": {"name": "dip-keephalf (2 ATR, 8 h)", "src": "gold_mtf_lock.py", "variants": {
        "orig": dict(fn=lambda c: gb12(c, "half", 0, 16), twin=("random", lambda c: gb12_candidates(c, "half", 0, 16))),
        "rt_giveback1.5": dict(fn=lambda c: gb12(c, "giveback", 1.5, 16), twin=("random", lambda c: gb12_candidates(c, "giveback", 1.5, 16))),
    }},
    "GB13": {"name": "trend-gb3 (Supertrend 4h, 3-ATR giveback, wait)", "src": "gold_trend_lock.py", "variants": {
        "orig": dict(fn=lambda c: gb13(c, 3), twin=("hold", _any_time, True)),
        "rt_giveback5": dict(fn=lambda c: gb13(c, 5), twin=("hold", _any_time, True)),
    }},
}
