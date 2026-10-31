"""R140: US gold (XAUUSD) from 06:00 to 08:00 IST on 15-minute candles, and its link to MCX GOLDM after 09:00.

PREREG.md (committed 7231fcf before any outcome) fixes every rule, cost, gate and period below. Research only.

Signal source: XAUUSD spot (Dukascopy BID 1-minute) for part A and for part B's signal; part B trades the MCX GOLDM near
future (minute closes from the GOLDM option book, R53's loader). Every rule has a stop-loss and a 60% profit lock.

    python -m research.hunt.r140_gold_0600.r140 design      # descriptive tables + 12 variants on the design period
    python -m research.hunt.r140_gold_0600.r140 holdout     # ONE read of the locked holdout (refuses a second read)
    python -m research.hunt.r140_gold_0600.r140 full        # descriptive tables on the full period (after holdout)

Data stay outside git (IRAALGO_CACHE / IRAALGO_LAKE); results/ holds small JSON and markdown only.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser() / "r140"
NY = ZoneInfo("America/New_York")
SEED = 140

# ---------------------------------------------------------------------------------------------- periods (PREREG s2)
XAU_DESIGN = (date(2023, 10, 2), date(2026, 3, 31))
XAU_FOLDS = ((date(2023, 10, 2), date(2024, 3, 31)), (date(2024, 4, 1), date(2024, 9, 30)),
             (date(2024, 10, 1), date(2025, 3, 31)), (date(2025, 4, 1), date(2025, 9, 30)),
             (date(2025, 10, 1), date(2026, 3, 31)))
MCX_DESIGN = (date(2025, 8, 5), date(2026, 3, 31))
MCX_FOLDS = ((date(2025, 8, 5), date(2025, 9, 30)), (date(2025, 10, 1), date(2025, 11, 30)),
             (date(2025, 12, 1), date(2026, 1, 31)), (date(2026, 2, 1), date(2026, 3, 31)))
XAU_HOLD = (date(2026, 4, 1), date(2026, 10, 30))
MCX_HOLD = (date(2026, 4, 1), date(2026, 10, 19))

# ---------------------------------------------------------------------------------------------- rule constants
W_START_UTC, W_END_UTC = 30, 150          # 06:00-08:00 IST = 00:30-02:30 UTC (minutes of the UTC day)
MIN_WINDOW_MINUTES = 110
XAU_LOT_OZ = 100.0
XAU_COST = 0.25 * XAU_LOT_OZ + 7.0        # $ a lot a round trip at 1x (spread + commission)
XAU_STOP_ATR, XAU_LOCK_ATR, LOCK_KEEP = 1.0, 1.0, 0.60
MCX_UNITS = 10.0                          # GOLDM: rupees per point of the quote, 1 lot
MCX_STOP, MCX_LOCK = 0.0030, 0.0020       # fractions of the entry price
N_TWINS = 1000
MIN_TRADES = {"A1": 100, "A2": 100, "A3": 100, "A4": 100, "A5": 100, "A6": 50,
              "B1": 60, "B2": 60, "B3": 60, "B4": 60, "B5": 60, "B6": 60}
ACCOUNT_DD, ACCOUNT_MONTH = -40_000.0, -13_000.0

# US release dates (New York dates). From research/brain/mcx/events.py (Jun 2024 on) plus the BLS / Fed dates of
# Oct 2023 - May 2024 (bls.gov CPI and Employment Situation schedules; federalreserve.gov FOMC calendar).
EXTRA_CPI = "2023-10-12 2023-11-14 2023-12-12 2024-01-11 2024-02-13 2024-03-12 2024-04-10 2024-05-15"
EXTRA_NFP = "2023-10-06 2023-11-03 2023-12-08 2024-01-05 2024-02-02 2024-03-08 2024-04-05 2024-05-03"
EXTRA_FOMC = "2023-11-01 2023-12-13"


def us_release_dates() -> list[date]:
    from research.brain.mcx import events as EV
    s = " ".join([EV.CPI, EV.NFP, EV.FOMC, EXTRA_CPI, EXTRA_NFP, EXTRA_FOMC])
    return sorted({date.fromisoformat(x) for x in s.split()})


# ---------------------------------------------------------------------------------------------- costs
def mcx_charges(buy_rs: float, sell_rs: float) -> float:
    from research.hunt.r94_mcx_advanced.common import fut_leg_charges
    return fut_leg_charges(buy_rs, sell_rs)


def mcx_half_spread_pts(price: float) -> float:
    return max(1.0, 1e-4 * price) / 2.0


def mcx_net(side: int, entry: float, exit_: float, mult: float = 1.0) -> float:
    """Rupees for 1 GOLDM lot after charges and slippage (both scaled by mult)."""
    gross = side * (exit_ - entry) * MCX_UNITS
    buy, sell = (entry, exit_) if side > 0 else (exit_, entry)
    ch = mcx_charges(buy * MCX_UNITS, sell * MCX_UNITS)
    slip = (mcx_half_spread_pts(entry) + mcx_half_spread_pts(exit_)) * MCX_UNITS
    return gross - mult * (ch + slip)


# ---------------------------------------------------------------------------------------------- XAUUSD data
def load_xau() -> pd.DataFrame:
    """1-minute BID bars indexed by the minute's OPEN time in UTC (naive). Release file, then the fetched tail."""
    rel = CACHE / "rel" / "xauusd_m1_bid.csv.gz"
    x = pd.read_csv(rel)
    x.index = pd.to_datetime(x.timestamp, unit="ms")
    x = x[["open", "high", "low", "close"]].astype(float)
    tail = CACHE / "xau_tail_1m.parquet"
    if tail.exists():
        y = pd.read_parquet(tail)
        y.index = pd.DatetimeIndex(y.ts_utc)
        y = y[["open", "high", "low", "close"]].astype(float)
        x = pd.concat([x, y[y.index > x.index.max()]])
    return x[~x.index.duplicated()].sort_index()


def us_summer(day_utc: date) -> bool:
    """True when New York is on daylight time at 00:30 UTC of that UTC day (the window's start)."""
    t = datetime(day_utc.year, day_utc.month, day_utc.day, 0, 30, tzinfo=ZoneInfo("UTC")).astimezone(NY)
    return bool(t.dst())


def ny_to_utc(d: date, hh: int, mm: int) -> pd.Timestamp:
    t = datetime(d.year, d.month, d.day, hh, mm, tzinfo=NY).astimezone(ZoneInfo("UTC"))
    return pd.Timestamp(t.replace(tzinfo=None))


class Xau:
    """Minute arrays plus helpers. ts = minute OPEN (UTC, naive); a minute's close is known at ts + 1 min."""

    def __init__(self, df: pd.DataFrame):
        self.ts = df.index.values.astype("datetime64[ns]")
        self.o, self.h, self.l, self.c = (df[k].values for k in ("open", "high", "low", "close"))
        self.df = df

    def ix_at_or_after(self, t) -> int:
        return int(np.searchsorted(self.ts, np.datetime64(pd.Timestamp(t)), side="left"))

    def close_before(self, t) -> float:
        """Last close known at time t (the minute that opened at or before t - 1 min)."""
        j = int(np.searchsorted(self.ts, np.datetime64(pd.Timestamp(t) - pd.Timedelta(minutes=1)), side="right")) - 1
        return float(self.c[j]) if j >= 0 else np.nan

    def bars15(self) -> pd.DataFrame:
        """15-minute candles on IST quarter hours (IST = UTC + 5:30, so quarter hours line up in both)."""
        r = self.df.resample("15min", label="left", closed="left")
        b = pd.DataFrame({"open": r.open.first(), "high": r.high.max(), "low": r.low.min(), "close": r.close.last()})
        return b.dropna()


def atr15_before(b15: pd.DataFrame, t) -> float:
    """Mean true range of the 14 completed 15m candles before time t (UTC)."""
    k = int(np.searchsorted(b15.index.values, np.datetime64(pd.Timestamp(t)), side="left"))
    if k < 15:
        return np.nan
    seg = b15.iloc[k - 15:k]
    pc = seg.close.shift(1)
    tr = np.maximum(seg.high - seg.low, np.maximum((seg.high - pc).abs(), (seg.low - pc).abs())).iloc[1:]
    return float(tr.mean())


def window_table(X: Xau) -> pd.DataFrame:
    """One row per window day (IST date): window stats and the signals known at 06:00 IST."""
    df = X.df
    days = sorted({t.date() for t in df.index if t.weekday() < 5 and W_START_UTC <= t.hour * 60 + t.minute < W_END_UTC})
    rows = []
    for d in days:
        a = pd.Timestamp(d) + pd.Timedelta(minutes=W_START_UTC)
        z = pd.Timestamp(d) + pd.Timedelta(minutes=W_END_UTC)
        i0, i1 = X.ix_at_or_after(a), X.ix_at_or_after(z)
        n = i1 - i0
        if n < MIN_WINDOW_MINUTES:
            continue
        o, c = X.o[i0], X.c[i1 - 1]
        hi, lo = X.h[i0:i1].max(), X.l[i0:i1].min()
        f1 = X.ix_at_or_after(a + pd.Timedelta(minutes=15))
        fc_o, fc_c = X.o[i0], X.c[f1 - 1]
        # the New York session that ended before this window: the latest NY date whose 17:00 is before 00:30 UTC
        ny = (a.to_pydatetime().replace(tzinfo=ZoneInfo("UTC")).astimezone(NY)).date()
        k = 0
        while True:
            dd = ny - timedelta(days=k)
            if dd.weekday() < 5 and ny_to_utc(dd, 17, 0) <= a:
                break
            k += 1
        prev = dd - timedelta(days=1)
        while prev.weekday() >= 5:
            prev -= timedelta(days=1)
        u0, u1 = X.close_before(ny_to_utc(dd, 8, 20)), X.close_before(ny_to_utc(dd, 17, 0))
        p0 = X.close_before(ny_to_utc(prev, 17, 0))
        rows.append(dict(day=d, dow=d.weekday(), summer=us_summer(d), n=n, w_open=o, w_close=c, w_hi=hi, w_lo=lo,
                         W=c / o - 1, rng=hi - lo, first=fc_c - fc_o, rest=c - fc_c, ny_day=dd,
                         U=u1 / u0 - 1, P=u1 / p0 - 1))
    t = pd.DataFrame(rows).set_index("day")
    rel = us_release_dates()
    post = set()
    wd = list(t.index)
    for r in rel:
        j = int(np.searchsorted(np.array(wd, dtype="datetime64[D]"), np.datetime64(r + timedelta(days=1)), "left"))
        if j < len(wd) and (wd[j] - r).days <= 4:
            post.add(wd[j])
    t["post_release"] = [d in post for d in t.index]
    return t


# ---------------------------------------------------------------------------------------------- trade engines
def sim_xau(X: Xau, i_in: int, i_out: int, side: int, stop: float, trig: float, keep: float = LOCK_KEEP):
    """Entry at the open of minute i_in, time exit at the open of minute i_out. Stop at stop $ from the entry; after the
    open profit reaches trig $, exit if it falls to keep x its peak. A minute touching both the stop / lock and a new
    high counts as the worse. Returns (exit price, reason)."""
    e = X.o[i_in]
    peak, lock = 0.0, None
    s_lvl = e - side * stop
    for j in range(i_in, i_out):
        hi, lo, op = X.h[j], X.l[j], X.o[j]
        adv = lo if side > 0 else hi
        fav = hi if side > 0 else lo
        lvl = lock if lock is not None else s_lvl
        if side * (adv - lvl) <= 0:
            px = min(op, lvl) if side > 0 else max(op, lvl)
            return px, ("lock" if lock is not None else "stop")
        peak = max(peak, side * (fav - e))
        if peak >= trig:
            new = e + side * keep * peak
            lock = new if lock is None else (max(lock, new) if side > 0 else min(lock, new))
            if side * (X.c[j] - lock) <= 0:
                return X.c[j], "lock"
    return X.o[i_out], "time"


def sim_close(F: np.ndarray, i_in: int, i_out: int, side: int, stop_frac: float = MCX_STOP,
              trig_frac: float = MCX_LOCK, keep: float = LOCK_KEEP):
    """Close-only engine (MCX minute closes): entry at F[i_in], checks at each later close up to i_out (time exit)."""
    e = F[i_in]
    stop, trig = stop_frac * e, trig_frac * e
    peak, lock = 0.0, None
    for j in range(i_in + 1, i_out + 1):
        p = F[j]
        g = side * (p - e)
        if g <= -stop:
            return p, "stop"
        if lock is not None and side * (p - lock) <= 0:
            return p, "lock"
        peak = max(peak, g)
        if peak >= trig:
            new = e + side * keep * peak
            lock = new if lock is None else (max(lock, new) if side > 0 else min(lock, new))
    return F[i_out], "time"


# ---------------------------------------------------------------------------------------------- stats
def bh(p: dict) -> dict:
    keys = sorted(p, key=lambda k: p[k])
    m = len(keys)
    q, prev = {}, 1.0
    for r, k in reversed(list(enumerate(keys, start=1))):
        prev = min(prev, p[k] * m / r)
        q[k] = prev
    return q


def max_dd(x) -> float:
    x = np.asarray(x, dtype=float)
    if not len(x):
        return 0.0
    eq = np.cumsum(x)
    return float(np.min(eq - np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]))


def in_p(d, p) -> bool:
    return p[0] <= d <= p[1]


def wf_sign(signal: pd.Series, outcome: pd.Series, before: date) -> int:
    """Follow (+1) or fade (-1): sign of the mean of signal x outcome over days strictly before `before`."""
    m = (signal.index < before) & (signal != 0) & outcome.notna()
    v = float((np.sign(signal[m]) * outcome[m]).mean()) if m.any() else 0.0
    return 1 if v >= 0 else -1


# ---------------------------------------------------------------------------------------------- part A outcomes
def xau_trade(X, b15, d, t_in, t_out, side, cost_mult=1.0):
    i_in, i_out = X.ix_at_or_after(t_in), X.ix_at_or_after(t_out)
    if i_in >= len(X.ts) or i_out >= len(X.ts) or i_out <= i_in:
        return None
    if X.ts[i_in] - np.datetime64(pd.Timestamp(t_in)) > np.timedelta64(5, "m"):
        return None
    atr = atr15_before(b15, t_in)
    if not np.isfinite(atr) or atr <= 0:
        return None
    px, why = sim_xau(X, i_in, i_out, side, XAU_STOP_ATR * atr, XAU_LOCK_ATR * atr)
    g = side * (px - X.o[i_in]) * XAU_LOT_OZ
    return dict(day=d, t_in=pd.Timestamp(t_in), side=side, gross=g, net=g - XAU_COST, net2=g - 2 * XAU_COST, why=why)


def a_times(d, rule):
    base = pd.Timestamp(d)
    if rule == "A1":
        return base + pd.Timedelta(minutes=45), base + pd.Timedelta(minutes=W_END_UTC)
    return base + pd.Timedelta(minutes=W_START_UTC), base + pd.Timedelta(minutes=W_END_UTC)


def range_trigger(b15: pd.DataFrame, d, hi, lo):
    """First 15m candle from 08:00 to 11:00 IST (02:30-05:30 UTC) closing outside [lo, hi]: (entry time, +1 up / -1)."""
    base = pd.Timestamp(d)
    seg = b15.loc[base + pd.Timedelta(minutes=W_END_UTC): base + pd.Timedelta(minutes=W_END_UTC + 165)]
    for t, r in seg.iterrows():
        if r.close > hi:
            return t + pd.Timedelta(minutes=15), 1
        if r.close < lo:
            return t + pd.Timedelta(minutes=15), -1
    return None, 0


def run_a(X, b15, wt, period, folds, learn_from=None, train_first=True):
    """Trades of A1-A6 in a period. learn_from: the frame whose days set the WF signs (the design table)."""
    out = {k: [] for k in ("A1", "A2", "A3", "A4", "A5", "A6")}
    lf = learn_from if learn_from is not None else wt
    outc = lf.w_close / lf.w_open - 1
    rest = lf.rest / lf.w_open
    sig = {"A1": (np.sign(lf["first"]), rest), "A2": (np.sign(lf.U), outc), "A3": (np.sign(lf.P), outc),
           "A6": (np.sign(lf.U[lf.post_release]), outc[lf.post_release])}
    for d, r in wt.iterrows():
        if not in_p(d, period):
            continue
        fk = next((i for i, f in enumerate(folds) if in_p(d, f)), None)
        before = folds[fk][0] if fk is not None else period[0]
        for rule, col in (("A1", "first"), ("A2", "U"), ("A3", "P"), ("A6", "U")):
            if train_first and fk == 0:
                continue                                   # design F1 is training only
            if rule == "A6" and not r.post_release:
                continue
            s = np.sign(r[col])
            if s == 0 or not np.isfinite(s):
                continue
            sg = wf_sign(*sig[rule], before)
            ti, to = a_times(d, rule)
            tr = xau_trade(X, b15, d, ti, to, int(sg * s))
            if tr:
                out[rule].append(tr)
        t_in, br = range_trigger(b15, d, r.w_hi, r.w_lo)
        if br:
            to = pd.Timestamp(d) + pd.Timedelta(hours=7)   # 12:30 IST = 07:00 UTC
            for rule, side in (("A4", br), ("A5", -br)):
                tr = xau_trade(X, b15, d, t_in, to, side)
                if tr:
                    out[rule].append(tr)
    return out


# ---------------------------------------------------------------------------------------------- MCX
class Mcx:
    """GOLDM near-future minute closes per session: {day: Series indexed by IST minute-of-day, forward-filled}."""

    def __init__(self, m):
        self.m = m
        ts = pd.DatetimeIndex(m.ts)
        s = pd.Series(m.F, index=ts)
        self.days, self.F, self.seg, self.last = [], {}, {}, {}
        for d, z in s.groupby(ts.normalize()):
            tod = (z.index.hour * 60 + z.index.minute).values
            if tod.min() > 9 * 60 + 2:
                continue
            dd = d.date()
            end = int(m.tod[m.day_cut[np.datetime64(d, "ns")]]) if np.datetime64(d, "ns") in m.day_cut else int(tod.max())
            full = pd.Series(z.values, index=tod).groupby(level=0).last().reindex(range(int(tod.min()), int(tod.max()) + 1)).ffill()
            self.days.append(dd)
            self.F[dd] = full
            self.last[dd] = (end, float(z.values[-1]), z.index[-1])
            self.seg[dd] = int(m.seg[np.searchsorted(m.ts, z.index.values[0])])


def b_rows(mc: Mcx, X: Xau, wt: pd.DataFrame, period, med_abs: pd.Series):
    """Trades of B1-B6 for the MCX sessions in a period (1x and 2x nets)."""
    out = {k: [] for k in ("B1", "B2", "B3", "B4", "B5", "B6")}
    for i, d in enumerate(mc.days):
        if not in_p(d, period) or d not in wt.index:
            continue
        r = wt.loc[d]
        s = int(np.sign(r.W))
        if s == 0:
            continue
        F = mc.F[d]
        if 545 not in F.index:
            continue
        end = mc.last[d][0]
        arr = F.values
        ix = {t: k for k, t in enumerate(F.index)}

        def add(rule, side, t_in, t_out):
            if t_in not in ix or t_out not in ix or ix[t_out] <= ix[t_in]:
                return
            px, why = sim_close(arr, ix[t_in], ix[t_out], side)
            e = arr[ix[t_in]]
            out[rule].append(dict(day=d, t_in=t_in, side=side, gross=side * (px - e) * MCX_UNITS,
                                  net=mcx_net(side, e, px), net2=mcx_net(side, e, px, 2.0), why=why))

        cut = min(end, int(F.index.max()))
        add("B1", s, 545, 600)
        add("B5", -s, 545, 600)
        add("B2", s, 545, cut)
        if abs(r.W) >= med_abs.get(d, np.inf):
            add("B3", s, 545, cut)
        if i > 0:
            pd_ = mc.days[i - 1]
            if mc.seg[pd_] == mc.seg[d] and 540 in ix:
                t_prev = mc.last[pd_][2] + pd.Timedelta(minutes=1)            # IST, minute end
                xu_prev = X.close_before(t_prev - pd.Timedelta(hours=5, minutes=30))
                xu_now = X.close_before(pd.Timestamp(d) + pd.Timedelta(hours=3, minutes=31))
                gap = arr[ix[540]] / mc.last[pd_][1] - 1
                resid = (xu_now / xu_prev - 1) - gap
                if np.isfinite(resid) and resid * s > 0:
                    add("B4", s, 545, 840)
        # B6: the window's range scaled to GOLDM at 09:05
        xu_905 = X.close_before(pd.Timestamp(d) + pd.Timedelta(hours=3, minutes=36))
        k = arr[ix[545]] / xu_905
        hi, lo = r.w_hi * k, r.w_lo * k
        for t in range(546, 721):
            if t not in ix or t - 1 not in ix:
                continue
            p0, p1 = arr[ix[t - 1]], arr[ix[t]]
            if lo <= p0 <= hi and (p1 > hi or p1 < lo):
                add("B6", 1 if p1 > hi else -1, t, 840)
                break
    return out


# ---------------------------------------------------------------------------------------------- twins
def twin_p(nets_by_day_long: np.ndarray, nets_by_day_short: np.ndarray, n: int, share_long: float, total: float,
           rng: np.random.Generator, n_sets: int = N_TWINS, col: np.ndarray | None = None):
    """p of the rule's total against twin sets. Arrays are (n_days, n_times); col[i] = the time column of trade i."""
    ok_days = np.where(np.isfinite(nets_by_day_long).all(axis=1) & np.isfinite(nets_by_day_short).all(axis=1))[0]
    if col is None:
        col = np.zeros(n, dtype=int)
    tot = np.empty(n_sets)
    for k in range(n_sets):
        dd = rng.choice(ok_days, size=n, replace=True)
        lg = rng.random(n) < share_long
        tot[k] = np.where(lg, nets_by_day_long[dd, col], nets_by_day_short[dd, col]).sum()
    return (1 + int((tot >= total).sum())) / (n_sets + 1), float(np.median(tot))


def twins_a(X, b15, wt, period, trades, rng):
    if not trades:
        return 1.0, 0.0
    days = [d for d in wt.index if in_p(d, period)]
    offs = sorted({(t["t_in"] - pd.Timestamp(t["day"]), t.get("t_out_off")) for t in trades}, key=str)
    oi = {o: k for k, o in enumerate(offs)}
    L = np.full((len(days), len(offs)), np.nan)
    S = np.full((len(days), len(offs)), np.nan)
    for a, d in enumerate(days):
        for (off, oo), k in oi.items():
            ti, to = pd.Timestamp(d) + off, pd.Timestamp(d) + oo
            for side, M in ((1, L), (-1, S)):
                tr = xau_trade(X, b15, d, ti, to, side)
                M[a, k] = tr["net"] if tr else np.nan
    good = np.isfinite(L).all(axis=1) & np.isfinite(S).all(axis=1)
    L, S = L[good], S[good]
    col = np.array([oi[(t["t_in"] - pd.Timestamp(t["day"]), t.get("t_out_off"))] for t in trades])
    share = float(np.mean([t["side"] > 0 for t in trades]))
    return twin_p(L, S, len(trades), share, float(sum(t["net"] for t in trades)), rng, col=col)


def twins_b(mc, period, trades, rng):
    if not trades:
        return 1.0, 0.0
    days = [d for d in mc.days if in_p(d, period)]
    offs = sorted({(t["t_in"], t["t_out"]) for t in trades})
    oi = {o: k for k, o in enumerate(offs)}
    L = np.full((len(days), len(offs)), np.nan)
    S = np.full((len(days), len(offs)), np.nan)
    for a, d in enumerate(days):
        F = mc.F[d]
        ix = {t: k for k, t in enumerate(F.index)}
        arr = F.values
        for (ti, to), k in oi.items():
            to2 = min(to, mc.last[d][0], int(F.index.max())) if to >= 1300 else to
            if ti not in ix or to2 not in ix or ix[to2] <= ix[ti]:
                continue
            e = arr[ix[ti]]
            for side, M in ((1, L), (-1, S)):
                px, _ = sim_close(arr, ix[ti], ix[to2], side)
                M[a, k] = mcx_net(side, e, px)
    col = np.array([oi[(t["t_in"], t["t_out"])] for t in trades])
    share = float(np.mean([t["side"] > 0 for t in trades]))
    # a day missing one column is dropped only for that column's draws: keep days finite in every used column
    good = np.isfinite(L).all(axis=1) & np.isfinite(S).all(axis=1)
    return twin_p(L[good], S[good], len(trades), share, float(sum(t["net"] for t in trades)), rng, col=col)


# ---------------------------------------------------------------------------------------------- summaries
def summarise(rule, trades, folds, is_mcx):
    n = len(trades)
    net = np.array([t["net"] for t in trades], dtype=float)
    net2 = np.array([t["net2"] for t in trades], dtype=float)
    s = dict(rule=rule, n=n, win=float((net > 0).mean()) if n else 0.0, avg=float(net.mean()) if n else 0.0,
             total=float(net.sum()), total2=float(net2.sum()), avg2=float(net2.mean()) if n else 0.0,
             longs=int(sum(t["side"] > 0 for t in trades)))
    fl = []
    for f in folds:
        v = [t["net"] for t in trades if in_p(t["day"], f)]
        fl.append(float(sum(v)) if v else None)
    s["folds"] = fl
    order = sorted(range(n), key=lambda i: (trades[i]["day"], str(trades[i]["t_in"])))
    s["dd"] = max_dd(net[order]) if n else 0.0
    mon = {}
    for t in trades:
        k = f"{t['day'].year}-{t['day'].month:02d}"
        mon[k] = mon.get(k, 0.0) + t["net"]
    s["worst_month"] = float(min(mon.values())) if mon else 0.0
    s["months_green"] = f"{sum(v > 0 for v in mon.values())}/{len(mon)}"
    s["why"] = {k: sum(t["why"] == k for t in trades) for k in ("stop", "lock", "time")}
    return s


def gates(s, p, q, rule):
    fl = [x for x in s["folds"] if x is not None]
    pos = sum(x > 0 for x in fl)
    if rule in ("A4", "A5"):
        fold_ok = pos >= 4
    else:
        fold_ok = pos >= 3
    g = dict(net=s["total"] > 0 and s["total2"] > 0, twins=p <= 0.05, bh=q <= 0.10, trades=s["n"] >= MIN_TRADES[rule],
             folds=fold_ok)
    if rule.startswith("B"):
        g["account"] = s["dd"] > ACCOUNT_DD and s["worst_month"] > ACCOUNT_MONTH
    g["all"] = all(g.values())
    return g


# ---------------------------------------------------------------------------------------------- descriptive
def describe(wt: pd.DataFrame, X: Xau, b15: pd.DataFrame, mc: Mcx | None, label: str) -> dict:
    out = {"label": label}
    for name, sub in (("all", wt), ("US summer (EDT)", wt[wt.summer]), ("US winter (EST)", wt[~wt.summer])):
        W = sub.W * 1e4
        out[name] = dict(n=len(sub), mean_bp=float(W.mean()), se_bp=float(W.std() / np.sqrt(max(len(W), 1))),
                         median_bp=float(W.median()), up=float((sub.W > 0).mean()), abs_bp=float(W.abs().mean()),
                         move_usd_med=float((sub.w_close - sub.w_open).abs().median()),
                         range_usd_med=float(sub.rng.median()), range_bp_med=float((sub.rng / sub.w_open * 1e4).median()),
                         corr_W_U=float(sub.W.corr(sub.U)), corr_W_P=float(sub.W.corr(sub.P)),
                         corr_first_rest=float(sub["first"].corr(sub.rest)),
                         first_rest_same=float((np.sign(sub["first"]) == np.sign(sub.rest))[sub["first"] != 0].mean()),
                         U_W_same=float((np.sign(sub.U) == np.sign(sub.W)).mean()),
                         P_W_same=float((np.sign(sub.P) == np.sign(sub.W)).mean()))
    # each 15m candle in the window
    days = set(wt.index)
    rows = []
    for k in range(8):
        sel = b15[[(t.date() in days) and (t.hour * 60 + t.minute == W_START_UTC + 15 * k) for t in b15.index]]
        ist = f"{6 + (15 * k) // 60:02d}:{(15 * k) % 60:02d}"
        rows.append(dict(ist=ist, n=len(sel), green=float((sel.close > sel.open).mean()),
                         body_med=float((sel.close - sel.open).abs().median()),
                         range_med=float((sel.high - sel.low).median()),
                         mean_bp=float(((sel.close / sel.open - 1) * 1e4).mean())))
    out["candles"] = rows
    out["weekday"] = [dict(dow=["Mon", "Tue", "Wed", "Thu", "Fri"][k], n=int((wt.dow == k).sum()),
                           mean_bp=float(wt.W[wt.dow == k].mean() * 1e4), up=float((wt.W[wt.dow == k] > 0).mean()),
                           abs_bp=float(wt.W[wt.dow == k].abs().mean() * 1e4)) for k in range(5)]
    pr = wt[wt.post_release]
    nr = wt[~wt.post_release]
    out["post_release"] = dict(n=len(pr), abs_bp=float(pr.W.abs().mean() * 1e4), range_bp=float((pr.rng / pr.w_open).median() * 1e4),
                               up=float((pr.W > 0).mean()), corr_W_U=float(pr.W.corr(pr.U)))
    out["other_days"] = dict(n=len(nr), abs_bp=float(nr.W.abs().mean() * 1e4), range_bp=float((nr.rng / nr.w_open).median() * 1e4),
                             up=float((nr.W > 0).mean()), corr_W_U=float(nr.W.corr(nr.U)))
    # XAUUSD 08:00 -> 12:30 IST after the window: does the window continue?
    nxt = []
    for d, r in wt.iterrows():
        a = X.close_before(pd.Timestamp(d) + pd.Timedelta(minutes=W_END_UTC))
        z = X.close_before(pd.Timestamp(d) + pd.Timedelta(hours=7))
        nxt.append(z / a - 1)
    wt2 = wt.assign(after=nxt)
    out["after_0800_1230"] = dict(n=int(wt2.after.notna().sum()), corr_W_after=float(wt2.W.corr(wt2.after)),
                                  same_sign=float((np.sign(wt2.W) == np.sign(wt2.after)).mean()))
    if mc is not None:
        rows = []
        for i, d in enumerate(mc.days):
            if d not in wt.index or i == 0:
                continue
            F = mc.F[d]
            if 545 not in F.index or 600 not in F.index or 540 not in F.index:
                continue
            pd_ = mc.days[i - 1]
            roll = mc.seg[pd_] != mc.seg[d]
            end = min(mc.last[d][0], int(F.index.max()))
            rows.append(dict(day=d, W=wt.loc[d].W, summer=wt.loc[d].summer, roll=roll,
                             gap=F[540] / mc.last[pd_][1] - 1, m1=F[600] / F[545] - 1, mclose=F[end] / F[545] - 1,
                             first5=F[545] / F[540] - 1))
        m = pd.DataFrame(rows)
        nr = m[~m.roll]
        res = {}
        for name, sub in (("all", nr), ("US summer (EDT)", nr[nr.summer]), ("US winter (EST)", nr[~nr.summer])):
            res[name] = dict(n=len(sub), corr_W_gap=float(sub.W.corr(sub.gap)), corr_W_0905_1000=float(sub.W.corr(sub.m1)),
                             corr_W_0905_close=float(sub.W.corr(sub.mclose)),
                             same_0905_1000=float((np.sign(sub.W) == np.sign(sub.m1)).mean()),
                             same_0905_close=float((np.sign(sub.W) == np.sign(sub.mclose)).mean()),
                             gap_same=float((np.sign(sub.W) == np.sign(sub.gap)).mean()))
        out["mcx"] = res
    return out


# ---------------------------------------------------------------------------------------------- runners
def _load():
    X = Xau(load_xau())
    b15 = X.bars15()
    wt = window_table(X)
    return X, b15, wt


def _load_mcx():
    from research.hunt.r53.mcx import data as D53
    return Mcx(D53.load_market("GOLDM"))


def expanding_median_abs(wt):
    a = wt.W.abs()
    return a.expanding().median().shift(1)


def _clean(o):
    if isinstance(o, dict):
        return {str(k): _clean(v) for k, v in o.items()}
    if isinstance(o, (list, tuple)):
        return [_clean(v) for v in o]
    if isinstance(o, (np.floating, float)):
        return None if not np.isfinite(o) else round(float(o), 6)
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.bool_,)):
        return bool(o)
    if isinstance(o, (date, pd.Timestamp)):
        return str(o)
    return o


def run_all(period_x, period_m, folds_x, folds_m, label, learn_from=None, mark_out=True):
    X, b15, wt = _load()
    mc = _load_mcx()
    rng = np.random.default_rng(SEED)
    a = run_a(X, b15, wt, period_x, folds_x, learn_from=learn_from(wt) if learn_from else None,
              train_first=learn_from is None)
    for rule, ts in a.items():
        for t in ts:
            t["t_out_off"] = pd.Timedelta(hours=7) if rule in ("A4", "A5") else pd.Timedelta(minutes=W_END_UTC)
    b = b_rows(mc, X, wt, period_m, expanding_median_abs(wt))
    for rule, ts in b.items():
        for t in ts:
            t["t_out"] = 600 if rule in ("B1", "B5") else (840 if rule in ("B4", "B6") else 1400)
    res, p = {}, {}
    for rule, ts in {**a, **b}.items():
        is_m = rule.startswith("B")
        s = summarise(rule, ts, folds_m if is_m else folds_x, is_m)
        if is_m:
            pv, med = twins_b(mc, period_m, ts, rng)
        else:
            pv, med = twins_a(X, b15, wt, period_x, ts, rng)
        s["twin_p"], s["twin_median_total"] = pv, med
        p[rule] = pv
        res[rule] = s
    q = bh(p)
    for rule, s in res.items():
        s["q"] = q[rule]
        s["gates"] = gates(s, s["twin_p"], q[rule], rule)
    return res, (X, b15, wt, mc), {**a, **b}


def table_md(res, cur_a="$", cur_b="Rs", is_rs=lambda r: r.startswith("B")):
    lines = ["| id | n | win (65% rule) | avg net | total 1x | total 2x | folds | twin p | BH q | drawdown | worst month | gates passed |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for rule, s in res.items():
        cur = cur_b if is_rs(rule) else cur_a
        fl = " / ".join("-" if x is None else f"{x:+,.0f}" for x in s["folds"])
        g = s["gates"]
        ok = ", ".join(k for k, v in g.items() if v and k != "all") or "none"
        lines.append(f"| {rule} | {s['n']} | {s['win']:.0%} ({'meets' if s['win'] >= 0.65 else 'below'}) | {cur} {s['avg']:+,.0f} | "
                     f"{cur} {s['total']:+,.0f} | {cur} {s['total2']:+,.0f} | {fl} | {s['twin_p']:.3f} | {s['q']:.3f} | "
                     f"{cur} {s['dd']:,.0f} | {cur} {s['worst_month']:,.0f} | {'ALL' if g['all'] else ok} |")
    return "\n".join(lines)


def main(mode):
    RESULTS.mkdir(parents=True, exist_ok=True)
    if mode == "design":
        res, (X, b15, wt, mc), trades = run_all(XAU_DESIGN, MCX_DESIGN, XAU_FOLDS, MCX_FOLDS, "design")
        dwt = wt[[in_p(d, XAU_DESIGN) for d in wt.index]]
        desc = describe(dwt, X, b15, mc_subset(mc, MCX_DESIGN), "design")
        (RESULTS / "design.json").write_text(json.dumps(_clean(dict(variants=res, describe=desc)), indent=1))
        (RESULTS / "design.md").write_text("# R140 design results\n\n" + table_md(res) + "\n")
        print(table_md(res))
        print(json.dumps(_clean(desc), indent=1)[:6000])
    elif mode == "holdout":
        lock = RESULTS / "holdout.lock"
        if lock.exists():
            raise SystemExit("holdout already read once: " + lock.read_text())
        dz = json.loads((RESULTS / "design.json").read_text())["variants"]
        passing = [k for k, v in dz.items() if v["gates"]["all"]]
        lock.write_text(f"read {datetime.now().isoformat(timespec='seconds')} for {passing or 'no variant (none passed)'}\n")
        if not passing:
            print("no variant passed the design gates; the holdout is not read for any rule")
            return
        res, *_ = run_all(XAU_HOLD, MCX_HOLD, (XAU_HOLD,), (MCX_HOLD,), "holdout",
                          learn_from=lambda wt: wt[[in_p(d, XAU_DESIGN) for d in wt.index]])
        res = {k: v for k, v in res.items() if k in passing}
        (RESULTS / "holdout.json").write_text(json.dumps(_clean(res), indent=1))
        print(table_md(res))
    elif mode == "full":
        if not (RESULTS / "holdout.lock").exists():
            raise SystemExit("run the holdout step first")
        X, b15, wt = _load()
        mc = _load_mcx()
        desc = describe(wt, X, b15, mc, "full period (design + holdout), after the holdout step")
        hwt = wt[[in_p(d, XAU_HOLD) for d in wt.index]]
        desc_h = describe(hwt, X, b15, mc_subset(mc, MCX_HOLD), "holdout period only, after the holdout step")
        (RESULTS / "full_describe.json").write_text(json.dumps(_clean(dict(full=desc, holdout=desc_h)), indent=1))
        print(json.dumps(_clean(desc), indent=1)[:4000])


def mc_subset(mc, period):
    import copy
    m2 = copy.copy(mc)
    m2.days = [d for d in mc.days if in_p(d, period)]
    return m2


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "design")
