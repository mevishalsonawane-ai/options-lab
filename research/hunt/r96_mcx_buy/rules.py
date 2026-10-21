"""R96 round 1 rules (PREREG_ROUND1.md s6). Signal source of every rule: the MCX future.

daily_ind adds the indicators to a book's daily signal series (R93 daily_series: RAW o/h/l/c, causal roll flag, ATR14).
Each rule's `candidates(book, period)` returns trade specs; `twin(book, spec, day)` returns the same trade moved to
another session (the rule's twin, s6). A spec is a dict the study turns into a trade with engine.sim_single /
engine.sim_straddle:
    day, i_sig, i_from, side (+1 / -1 / 0 straddle), ustop (None or a price), n_sessions (None = same session),
    kind ('single' / 'straddle'), i_end_fixed (None or a grid index), katr (stop distance in ATR14 units, or None)
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r93_breadth_donchian_mcx import rules as R93
from research.hunt.r96_mcx_buy import cfg as K

DAY = np.timedelta64(1, "D")


# ================================================================================================ daily indicators
def har_forecast(rv: np.ndarray, h: int = 5, min_rows: int = 250) -> np.ndarray:
    """HAR forecast made at each row t (with rows <= t only) of the mean daily variance over rows t+1..t+h.
    Features at s: rv[s], mean rv[s-4..s], mean rv[s-21..s]; target at s: mean rv[s+1..s+h]. The OLS at t uses only
    rows s with s + h <= t (targets fully known at t). NaN until min_rows such rows exist."""
    rv = np.asarray(rv, float)
    n = len(rv)
    s = pd.Series(rv)
    X = np.column_stack([np.ones(n), rv, s.rolling(5).mean().values, s.rolling(22).mean().values])
    y = s[::-1].rolling(h).mean()[::-1].shift(-1).values         # mean of rv[s+1..s+h]
    ok = np.isfinite(X).all(axis=1) & np.isfinite(y)
    out = np.full(n, np.nan)
    XtX = np.zeros((4, 4))
    Xty = np.zeros(4)
    cnt = 0
    for t in range(n):
        s_new = t - h                                          # row whose target became known at t
        if s_new >= 0 and ok[s_new]:
            XtX += np.outer(X[s_new], X[s_new])
            Xty += X[s_new] * y[s_new]
            cnt += 1
        if cnt >= min_rows and np.isfinite(X[t]).all():
            beta = np.linalg.lstsq(XtX, Xty, rcond=None)[0]
            out[t] = float(X[t] @ beta)
    return out


def daily_ind(d: pd.DataFrame) -> pd.DataFrame:
    """d: R93 daily_series output. Adds back-adjusted closes and every daily signal input (all causal)."""
    d = d.copy().reset_index(drop=True)
    roll = d.roll.values.astype(bool)
    ao, ah, al, ac = R93.back_adjust(d.open.values, d.high.values, d.low.values, d.close.values, roll)
    d["ao"], d["ah"], d["al"], d["ac"] = ao, ah, al, ac
    c = pd.Series(ac)
    d["sma20"] = c.rolling(20).mean().values
    sd = c.rolling(20).std(ddof=0).values
    ema = c.ewm(span=20, adjust=False).mean().values
    atr20 = R93.atr_roll(d.high.values, d.low.values, d.close.values, roll, 20)
    bb_u, bb_l = d.sma20.values + 2 * sd, d.sma20.values - 2 * sd
    kc_u, kc_l = ema + 1.5 * atr20, ema - 1.5 * atr20
    sq = (bb_u < kc_u) & (bb_l > kc_l) & np.isfinite(sd) & np.isfinite(atr20)
    d["sq"] = sq
    prev6 = pd.Series(sq.astype(float)).shift(1).rolling(6).sum().values
    d["sq_release"] = (~sq) & np.r_[False, sq[:-1]] & (prev6 >= 5)
    prev5 = pd.Series(sq.astype(float)).shift(1).rolling(5).sum().values
    d["sq_release3"] = (~sq) & np.r_[False, sq[:-1]] & (prev5 >= 3)          # round 2 R2F (relaxed)
    d["sq_side"] = np.nan_to_num(np.sign(ac - d.sma20.values)).astype(int)
    rng = (d.high - d.low).values
    d["rng"] = rng
    prev_min6 = pd.Series(rng).shift(1).rolling(6).min().values
    d["nr7"] = np.isfinite(prev_min6) & (rng < prev_min6)
    hp, lp = np.r_[np.nan, d.high.values[:-1]], np.r_[np.nan, d.low.values[:-1]]
    roll_today = d.roll.values.astype(bool)
    d["inside"] = (d.high.values < hp) & (d.low.values > lp) & ~roll_today             # raw prices; not on a roll day
    d["nr7_id"] = d.nr7.values & d.inside.values                                         # round 2 R2C (Crabel ID/NR7)
    hi20 = c.shift(1).rolling(20).max().values
    lo20 = c.shift(1).rolling(20).min().values
    nr7_recent = pd.Series(d.nr7.values.astype(float)).shift(1).rolling(5).max().values >= 1
    dc = np.where(ac > hi20, 1, np.where(ac < lo20, -1, 0))
    d["qb_side"] = np.where(nr7_recent & np.isfinite(hi20), dc, 0).astype(int)          # round 2 R2H
    atr_prev = np.r_[np.nan, d.atr.values[:-1]]
    loc = np.where(rng > 0, (d.close.values - d.low.values) / np.where(rng > 0, rng, 1), 0.5)
    wide = np.isfinite(atr_prev) & (rng >= 1.8 * atr_prev)
    d["wrb_side"] = np.where(wide & (loc >= 0.75), 1, np.where(wide & (loc <= 0.25), -1, 0))
    r20 = c.diff(20).values
    r60 = c.diff(60).values
    tr = np.where((np.sign(r20) == np.sign(r60)) & np.isfinite(r20) & np.isfinite(r60), np.sign(r20), 0)
    d1 = np.r_[np.nan, np.diff(ac)]
    d2 = np.r_[np.nan, d1[:-1]]
    pull = (tr != 0) & (d1 * tr < 0) & (d2 * tr < 0)
    d["pb_side"] = np.where(pull, tr, 0).astype(int)
    o, h, l, cl = (d[x].values.astype(float) for x in ("open", "high", "low", "close"))
    with np.errstate(all="ignore"):
        gk = 0.5 * np.log(h / l) ** 2 - (2 * np.log(2) - 1) * np.log(cl / o) ** 2
    gk = np.where(np.isfinite(gk), np.maximum(gk, 0.0), np.nan)
    d["gk"] = gk
    fc = har_forecast(pd.Series(gk).ffill().fillna(0.0).values)       # a missing day repeats the last one (causal)
    d["fc_vol"] = np.sqrt(252.0 * np.maximum(fc, 0.0))
    return r3_daily(d)


# ================================================================================================ helpers
def _day64(x):
    return np.datetime64(pd.Timestamp(x).normalize(), "ns")


def next_market_day(book, t):
    """The first option-book session after daily date t (None when none)."""
    m = book.m
    k = int(np.searchsorted(m.days, _day64(t), side="right"))
    return m.days[k] if k < len(m.days) else None


def entry_ix(book, day, tod: int):
    """(i_sig, i_from): i_from = first grid minute of `day` at or after tod; i_sig = the minute before it when it is
    the same day, else i_from."""
    m = book.m
    i_from = m.at_or_after(day, tod)
    if i_from < 0:
        return None
    i_sig = i_from - 1 if i_from > 0 and m.day[i_from - 1] == m.day[i_from] else i_from
    return i_sig, i_from


def atr_before(book, day):
    """ATR14 of the last daily row strictly before `day` (known at that day's open)."""
    d = book.daily
    k = int(np.searchsorted(d.date.values, _day64(day), side="left")) - 1
    return float(d.atr.values[k]) if k >= 0 else float("nan")


def in_p(day, period) -> bool:
    return K.in_period(pd.Timestamp(day).date(), period)


# ================================================================================================ rules
class DailyDir:
    """S2, W1, P1: a daily signal at date t -> 1-ITM next session 09:15, stop katr x ATR14(t), n sessions."""

    def __init__(self, rid, col, katr, n, mask_col=None, edge=False, books=K.BOOK_ORDER):
        self.rid, self.col, self.katr, self.n, self.mask_col = rid, col, katr, n, mask_col
        self.kind, self.edge, self.books = "single", edge, books

    def sides(self, d):
        s = d[self.col].values.astype(int)
        if self.mask_col:
            s = np.where(d[self.mask_col].values, s, 0)
        return s

    def spec(self, book, day, side, atr):
        e = entry_ix(book, day, K.ENTRY_TOD)
        if e is None or not np.isfinite(atr) or side == 0:
            return None
        i_sig, i_from = e
        ust = book.m.F[i_sig] - side * self.katr * atr
        return dict(day=day, i_sig=i_sig, i_from=i_from, side=int(side), ustop=float(ust), n_sessions=self.n,
                    kind="single", i_end_fixed=None, katr=self.katr, edge=self.edge)

    def candidates(self, book, period):
        d = book.daily
        out = []
        for t, side, atr in zip(d.date.values, self.sides(d), d.atr.values):
            if side == 0:
                continue
            day = next_market_day(book, t)
            if day is None or not in_p(day, period) or (day - _day64(t)) > 7 * DAY:
                continue
            sp = self.spec(book, day, side, atr)
            if sp:
                out.append(sp)
        return out

    def twin(self, book, sp, day):
        return self.spec(book, day, sp["side"], atr_before(book, day))


class SqueezeStraddle:
    """S1 (and round 2: R2F relaxed squeeze, R2G NR7 day): a daily flag at date t -> ATM straddle next session."""
    kind = "straddle"

    def __init__(self, rid="S1", n=5, col="sq_release", edge=False, books=K.BOOK_ORDER, iv_cheap=False):
        self.rid, self.n, self.col, self.edge, self.books = rid, n, col, edge, books
        self.iv_cheap = iv_cheap          # round 5 R5C: only when R4G's cheap-IV filter holds (the twin is not filtered)

    def spec(self, book, day):
        e = entry_ix(book, day, K.ENTRY_TOD)
        if e is None:
            return None
        return dict(day=day, i_sig=e[0], i_from=e[1], side=0, ustop=None, n_sessions=self.n, kind="straddle",
                    i_end_fixed=None, katr=None, edge=self.edge)

    def candidates(self, book, period):
        d = book.daily
        out = []
        for t in d.date.values[d[self.col].values.astype(bool)]:
            day = next_market_day(book, t)
            if day is None or not in_p(day, period) or (day - _day64(t)) > 7 * DAY:
                continue
            if self.iv_cheap and not iv_cheap_before(book, day):
                continue
            sp = self.spec(book, day)
            if sp:
                out.append(sp)
        return out

    def twin(self, book, sp, day):
        return self.spec(book, day)


class NR7:
    """N1 (and round 2: R2A-R2E with edge exits, 2-ITM, inside day, big books, a risk cap)."""
    kind = "single"
    n = 3
    LAST_TOD = 22 * 60

    def __init__(self, rid="N1", col="nr7", itm=1, edge=False, max_risk=None, books=K.BOOK_ORDER):
        self.rid, self.col, self.itm, self.edge, self.max_risk, self.books = rid, col, itm, edge, max_risk, books

    def extra(self):
        return dict(itm=self.itm, edge=self.edge, max_risk=self.max_risk)

    def candidates(self, book, period):
        m, d = book.m, book.daily
        out = []
        for t in d.date.values[d[self.col].values.astype(bool)]:
            t = _day64(t)
            if t not in book.day_pos:
                continue
            day = next_market_day(book, t)
            if day is None or not in_p(day, period) or (day - t) > 7 * DAY:
                continue
            ix = m.day_ix(t)
            hi, lo = float(np.nanmax(m.F[ix])), float(np.nanmin(m.F[ix]))
            jx = m.day_ix(day)
            jx = jx[(m.tod[jx] >= K.ENTRY_TOD) & (m.tod[jx] <= self.LAST_TOD)]
            br = np.flatnonzero((m.F[jx] > hi) | (m.F[jx] < lo))
            if not len(br):
                continue
            i_sig = int(jx[br[0]])
            side = 1 if m.F[i_sig] > hi else -1
            ust = lo if side > 0 else hi
            atr = atr_before(book, day)
            katr = abs(m.F[i_sig] - ust) / atr if np.isfinite(atr) and atr > 0 else None
            out.append(dict(day=day, i_sig=i_sig, i_from=i_sig + 1, side=side, ustop=float(ust), n_sessions=self.n,
                            kind="single", i_end_fixed=None, katr=katr, tod=int(m.tod[i_sig]), **self.extra()))
        return out

    def twin(self, book, sp, day):
        m = book.m
        i_sig = m.at_or_before(day, sp["tod"])
        if i_sig < 0 or sp.get("katr") is None:
            return None
        atr = atr_before(book, day)
        if not np.isfinite(atr):
            return None
        katr = round(sp["katr"] * 4) / 4                      # cache-friendly: quarter-ATR steps
        ust = m.F[i_sig] - sp["side"] * katr * atr
        return dict(day=day, i_sig=i_sig, i_from=i_sig + 1, side=sp["side"], ustop=float(ust), n_sessions=self.n,
                    kind="single", i_end_fixed=None, katr=katr, tod=sp["tod"], **self.extra())


class VRP:
    rid, kind = "V1", "straddle"
    n = 5
    TOD = 17 * 60 + 1

    def atm_iv(self, book, day):
        m = book.m
        ix = m.day_ix(day)
        ix = ix[(m.tod[ix] >= 16 * 60 + 45) & (m.tod[ix] < 17 * 60)]
        if not len(ix):
            return float("nan")
        a = book.iv.get((0, 0))
        b = book.iv.get((1, 0))
        if a is None or b is None:
            return float("nan")
        v = np.nanmean(np.vstack([a[ix], b[ix]]), axis=0)
        v = v[np.isfinite(v)]
        return float(np.median(v)) if len(v) else float("nan")

    def forecast(self, book, day):
        d = book.daily
        k = int(np.searchsorted(d.date.values, _day64(day), side="left")) - 1
        return float(d.fc_vol.values[k]) if k >= 0 else float("nan")

    def spec(self, book, day):
        e = entry_ix(book, day, self.TOD)
        if e is None:
            return None
        return dict(day=day, i_sig=e[0], i_from=e[1], side=0, ustop=None, n_sessions=self.n, kind="straddle",
                    i_end_fixed=None, katr=None)

    def candidates(self, book, period):
        out = []
        for day in book.m.days:
            if not in_p(day, period):
                continue
            iv, fc = self.atm_iv(book, day), self.forecast(book, day)
            if np.isfinite(iv) and np.isfinite(fc) and iv / 100.0 < fc:
                sp = self.spec(book, day)
                if sp:
                    sp["iv"], sp["fc"] = iv, fc
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        return self.spec(book, day)


def release_days(book, period_or_all=None) -> dict:
    """{day (datetime64): release minute (IST)} for the book's events, over the whole option data."""
    from research.brain.mcx import events as EV
    cal = EV.calendar(K.DATA_START, K.HOLDOUT[1])
    out = {}
    for kind in book.events:
        for d, mnt in cal.get(kind, {}).items():
            out[_day64(d)] = int(mnt)
    return out


class EventStraddle:
    rid, kind = "E1", "straddle"
    PRE, POST = 20, 45

    def spec(self, book, day, rel):
        m = book.m
        i_from = m.at_or_after(day, rel - self.PRE, until=rel - self.PRE + 2)
        i_end = m.at_or_before(day, rel + self.POST)
        if i_from < 0 or i_end < 0 or m.tod[i_end] < rel:
            return None
        i_sig = i_from - 1 if i_from > 0 and m.day[i_from - 1] == m.day[i_from] else i_from
        return dict(day=day, i_sig=i_sig, i_from=i_from, side=0, ustop=None, n_sessions=None, kind="straddle",
                    i_end_fixed=i_end, katr=None, rel=rel, fill_wait=self.PRE - 5)

    def candidates(self, book, period):
        out = []
        for day, rel in sorted(release_days(book).items()):
            if day in book.day_pos and in_p(day, period):
                sp = self.spec(book, day, rel)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        if day in release_days(book):
            return None
        return self.spec(book, day, sp["rel"])


class EventFollow:
    rid, kind = "E2", "single"

    def spec(self, book, day, rel):
        m = book.m
        i_pre = m.at_or_before(day, rel - 1)
        i_sig = m.at_or_before(day, rel + 15)
        if i_pre < 0 or i_sig < 0 or m.tod[i_pre] < rel - 5 or m.tod[i_sig] < rel + 10:
            return None
        mv = m.F[i_sig] - m.F[i_pre]
        if not np.isfinite(mv) or mv == 0:
            return None
        side = 1 if mv > 0 else -1
        return dict(day=day, i_sig=i_sig, i_from=i_sig + 1, side=side, ustop=float(m.F[i_pre]), n_sessions=None,
                    kind="single", i_end_fixed=None, katr=None, rel=rel)

    def candidates(self, book, period):
        out = []
        for day, rel in sorted(release_days(book).items()):
            if day in book.day_pos and in_p(day, period):
                sp = self.spec(book, day, rel)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        if day in release_days(book):
            return None
        return self.spec(book, day, sp["rel"])


class USMomentum:
    rid, kind = "U1", "single"
    MULT, LOOK, MIN_PAST = 1.5, 20, 10

    @staticmethod
    def us_open(day) -> int:
        from research.hunt.r83.common import et_to_ist_min
        return et_to_ist_min(pd.Timestamp(day).date(), 9, 30)

    def window(self, book, day):
        m = book.m
        t0 = self.us_open(day)
        i_a = m.at_or_before(day, t0 - 1)
        i_b = m.at_or_before(day, t0 + 29)
        if i_a < 0 or i_b < 0 or m.tod[i_a] < t0 - 5 or m.tod[i_b] < t0 + 25:
            return None
        return i_a, i_b, float(m.F[i_b] - m.F[i_a])

    def spec_from(self, i_a, i_b, mv, book, day):
        side = 1 if mv > 0 else -1
        return dict(day=day, i_sig=i_b, i_from=i_b + 1, side=side, ustop=float(book.m.F[i_a]), n_sessions=None,
                    kind="single", i_end_fixed=None, katr=None)

    def candidates(self, book, period):
        hist = []
        out = []
        for day in book.m.days:
            w = self.window(book, day)
            past = [abs(x) for x in hist[-self.LOOK:] if np.isfinite(x)]
            if w is not None:
                i_a, i_b, mv = w
                if in_p(day, period) and len(past) >= self.MIN_PAST and mv != 0 \
                        and abs(mv) >= self.MULT * float(np.median(past)):
                    out.append(self.spec_from(i_a, i_b, mv, book, day))
                hist.append(mv)
        return out

    def twin(self, book, sp, day):
        w = self.window(book, day)
        if w is None or w[2] == 0:
            return None
        return self.spec_from(*w, book, day)


RULES = {
    "S1": SqueezeStraddle(),
    "S2": DailyDir("S2", "sq_side", 1.0, 7, mask_col="sq_release"),
    "N1": NR7(),
    "W1": DailyDir("W1", "wrb_side", 1.0, 5),
    "P1": DailyDir("P1", "pb_side", 1.5, 5),
    "V1": VRP(),
    "E1": EventStraddle(),
    "E2": EventFollow(),
    "U1": USMomentum(),
}
ORDER = ("S1", "S2", "N1", "W1", "P1", "V1", "E1", "E2", "U1")
DIRECTIONAL_DAILY = ("S2", "N1", "W1", "P1")

# ---------------------------------------------------------------------------------------------- round 2 (PREREG_ROUND2)
RULES.update({
    "R2A": NR7("R2A", edge=True),
    "R2B": NR7("R2B", itm=2, edge=True),
    "R2C": NR7("R2C", col="nr7_id", edge=True),
    "R2D": NR7("R2D", edge=True, books=K.BIG_ENERGY),
    "R2E": NR7("R2E", edge=True, max_risk=K.R2_MAX_RISK),
    "R2F": SqueezeStraddle("R2F", n=5, col="sq_release3", edge=True, books=K.ALL_BOOKS),
    "R2G": SqueezeStraddle("R2G", n=3, col="nr7", edge=True),
    "R2H": DailyDir("R2H", "qb_side", 1.0, 5, edge=True),
})
ORDER2 = ("R2A", "R2B", "R2C", "R2D", "R2E", "R2F", "R2G", "R2H")
HISTORY_RULES = {"S2": "dir", "N1": "nr7", "W1": "dir", "P1": "dir", "R2A": "nr7", "R2B": "nr7", "R2C": "nr7_id",
                 "R2D": "nr7", "R2E": "nr7", "R2H": "dir"}


# ---------------------------------------------------------------------------------------------- round 3 (PREREG_ROUND3)
def nr_n(rng: np.ndarray, n: int) -> np.ndarray:
    """NRn: today's range is narrower than each of the previous n - 1 ranges (Crabel). NR7 = nr_n(rng, 7)."""
    prev = pd.Series(rng).shift(1).rolling(n - 1).min().values
    return np.isfinite(prev) & (rng < prev)


def low_vol(d: pd.DataFrame, win: int = K.R3_LOWVOL_WIN, frac: float = K.R3_LOWVOL_FRAC) -> np.ndarray:
    """R3D: ATR14 / raw close at row t sits in the bottom `frac` of its min..max over rows t-win+1..t (causal)."""
    x = pd.Series(d.atr.values / d.close.values)
    lo, hi = x.rolling(win).min().values, x.rolling(win).max().values
    with np.errstate(all="ignore"):
        pos = (x.values - lo) / (hi - lo)
    return np.isfinite(pos) & (pos <= frac + 1e-12)


def weekly_nr7(d: pd.DataFrame) -> pd.DataFrame:
    """R3E: one row per ISO week of the daily series: first / last row, range (back-adjusted high - low), roll_in (a
    roll row inside the week), and wnr7 = the range is narrower than each of the previous 6 weeks' ranges. A week with
    a roll is never itself a weekly NR7 (its range still counts in the later weeks' comparison window)."""
    iso = pd.DatetimeIndex(d.date.values).isocalendar()
    key = (iso.year.values.astype(int) * 100 + iso.week.values.astype(int))
    g = pd.DataFrame(dict(key=key, i=np.arange(len(d)), ah=d.ah.values, al=d.al.values,
                          roll=d.roll.values.astype(bool))).groupby("key", sort=True)
    w = pd.DataFrame(dict(first=g.i.min(), last=g.i.max(), hi=g.ah.max(), lo=g.al.min(), roll_in=g.roll.any()))
    w["rng"] = (w.hi - w.lo).values
    w["wnr7"] = nr_n(w.rng.values.astype(float), 7) & ~w.roll_in.values
    return w.reset_index()


class NR7Var(NR7):
    """Round 3 NR7 variants: a different flag column, a break window (first_tod..last_tod), an underlying trail
    instead of the premium ladder (R3C), or an entry at the US cash open after a morning break that still holds (R3G)."""

    def __init__(self, rid, col="nr7", books=K.CRUDE, last_tod=22 * 60, trail=None, us_open=False, n=3,
                 r93=False, fut=False, itm=1, side_col=None, iv_cheap=False):
        super().__init__(rid, col=col, itm=itm, edge=not (r93 or fut), books=books)
        self.LAST_TOD, self.trail, self.us_open, self.n = last_tod, trail, us_open, n
        self.r93, self.fut = r93 or fut, fut
        self.side_col, self.iv_cheap = side_col, iv_cheap        # round 4 filters (R4B / R4F, R4G)
        if self.r93:
            self.n = K.R93_MAX_HOLD

    def extra(self):
        e = dict(itm=self.itm, edge=self.edge, max_risk=self.max_risk)
        if self.trail is not None:
            e.update(trail=self.trail, no_ladder=True)
        if self.r93:                    # R3K / R3L: R93's daily exits; no premium ladder, no edge take-profit
            e.update(r93=True, no_ladder=True)
        return e

    @staticmethod
    def us_tod(day) -> int:
        return USMomentum.us_open(day)

    def _finish(self, book, sp):
        if sp is not None and self.trail is not None:
            sp["atr"] = atr_before(book, sp["day"])
        if sp is not None and self.fut:
            sp["kind"] = "future"
        return sp

    def _keep(self, book, sp) -> bool:
        """Round 4 filters, read from the last daily row / option session strictly before the entry day (the NR7 day,
        known at the entry day's open). The twin (another day of the month) is not filtered."""
        if self.side_col is not None and value_before(book, sp["day"], self.side_col) != sp["side"]:
            return False
        if self.iv_cheap and not iv_cheap_before(book, sp["day"]):
            return False
        return True

    def candidates(self, book, period):
        return [sp for sp in self._candidates(book, period) if self._keep(book, sp)]

    def _candidates(self, book, period):
        if not self.us_open:
            return [self._finish(book, sp) for sp in super().candidates(book, period)]
        m, d = book.m, book.daily
        out = []
        for t in d.date.values[d[self.col].values.astype(bool)]:
            t = _day64(t)
            if t not in book.day_pos:
                continue
            day = next_market_day(book, t)
            if day is None or not in_p(day, period) or (day - t) > 7 * DAY:
                continue
            ix = m.day_ix(t)
            hi, lo = float(np.nanmax(m.F[ix])), float(np.nanmin(m.F[ix]))
            u = self.us_tod(day)
            i_sig = m.at_or_before(day, u - 1)
            if i_sig < 0 or m.tod[i_sig] < u - 5:
                continue
            jx = m.day_ix(day)
            jx = jx[(m.tod[jx] >= K.ENTRY_TOD) & (m.tod[jx] <= u - 1)]
            br = np.flatnonzero((m.F[jx] > hi) | (m.F[jx] < lo))
            if not len(br):
                continue
            side = 1 if m.F[jx[br[0]]] > hi else -1
            lvl = hi if side > 0 else lo
            if side * (m.F[i_sig] - lvl) <= 0:            # the break no longer holds at the US open
                continue
            ust = lo if side > 0 else hi
            atr = atr_before(book, day)
            katr = abs(m.F[i_sig] - ust) / atr if np.isfinite(atr) and atr > 0 else None
            out.append(dict(day=day, i_sig=int(i_sig), i_from=int(i_sig) + 1, side=side, ustop=float(ust),
                            n_sessions=self.n, kind="single", i_end_fixed=None, katr=katr, tod=int(m.tod[i_sig]),
                            **self.extra()))
        return out

    def twin(self, book, sp, day):
        if self.us_open:
            sp = dict(sp, tod=self.us_tod(day) - 1)
        return self._finish(book, super().twin(book, sp, day))


class WeeklyNR7(NR7Var):
    """R3E: the narrowest week of 7 -> the first future minute close beyond that week's high / low (from the minute
    future of the week's sessions) during the next ISO week's sessions, 09:15-22:00 -> 1-ITM, held 5 sessions, stop at
    the week's opposite extreme. The NR week and the break must lie in the same option contract segment."""

    def __init__(self, rid="R3E", books=K.ENERGY):
        super().__init__(rid, books=books, n=5)

    def candidates(self, book, period):
        m, d = book.m, book.daily
        w = weekly_nr7(d)
        dates = d.date.values
        out = []
        for r in range(len(w) - 1):
            if not w.wnr7.values[r]:
                continue
            wk_days = [_day64(x) for x in dates[int(w["first"].values[r]):int(w["last"].values[r]) + 1]]
            wk_days = [x for x in wk_days if x in book.day_pos]
            nx = w.iloc[r + 1]
            nx_days = [_day64(x) for x in dates[int(nx["first"]):int(nx["last"]) + 1]]
            nx_days = [x for x in nx_days if x in book.day_pos]
            if not wk_days or not nx_days:
                continue
            ix = np.concatenate([m.day_ix(x) for x in wk_days])
            seg = int(m.seg[ix[-1]])
            if (m.seg[ix] != seg).any():
                continue
            hi, lo = float(np.nanmax(m.F[ix])), float(np.nanmin(m.F[ix]))
            for day in nx_days:
                if not in_p(day, period):
                    continue
                jx = m.day_ix(day)
                jx = jx[(m.tod[jx] >= K.ENTRY_TOD) & (m.tod[jx] <= self.LAST_TOD)]
                if not len(jx) or (m.seg[jx] != seg).any():
                    break
                br = np.flatnonzero((m.F[jx] > hi) | (m.F[jx] < lo))
                if not len(br):
                    continue
                i_sig = int(jx[br[0]])
                side = 1 if m.F[i_sig] > hi else -1
                ust = lo if side > 0 else hi
                atr = atr_before(book, day)
                katr = abs(m.F[i_sig] - ust) / atr if np.isfinite(atr) and atr > 0 else None
                out.append(dict(day=day, i_sig=i_sig, i_from=i_sig + 1, side=side, ustop=float(ust),
                                n_sessions=self.n, kind="single", i_end_fixed=None, katr=katr,
                                tod=int(m.tod[i_sig]), **self.extra()))
                break
        return out


def r4_daily(d: pd.DataFrame) -> pd.DataFrame:
    """Round 4 daily columns (causal): tsm12 / tsm1 = the sign of the back-adjusted close change over the last
    R4_TSM_LONG / R4_TSM_SHORT rows (0 when unknown)."""
    d = d.copy()
    ac = d.ac.values.astype(float)
    for col, L in (("tsm12", K.R4_TSM_LONG), ("tsm1", K.R4_TSM_SHORT)):
        prev = np.r_[np.full(L, np.nan), ac[:-L]] if len(ac) > L else np.full(len(ac), np.nan)
        x = ac - prev
        d[col] = np.where(np.isfinite(x), np.sign(x), 0).astype(int)
    return r6_daily(d)


def value_before(book, day, col):
    """A daily column's value on the last row strictly before `day` (known at that day's open); None when none."""
    d = book.daily
    k = int(np.searchsorted(d.date.values, _day64(day), side="left")) - 1
    return d[col].values[k] if k >= 0 else None


def atm_iv_by_day(book) -> dict:
    """{option session: median over its minutes of the mean ATM call / put IV (offset 0, Dhan's IV %)}; cached."""
    c = getattr(book, "_atm_iv_day", None)
    if c is not None:
        return c
    m = book.m
    a, b = book.iv.get((0, 0)), book.iv.get((1, 0))
    out = {}
    if a is not None and b is not None:
        with np.errstate(all="ignore"):
            v = np.nanmean(np.vstack([a, b]), axis=0)
        for day in m.days:
            x = v[m.day_ix(day)]
            x = x[np.isfinite(x)]
            out[day] = float(np.median(x)) if len(x) else float("nan")
    book._atm_iv_day = out
    return out


def iv_cheap_before(book, day) -> bool:
    """R4G: the ATM IV of the option session before `day` is below the median of the R4_IV_LOOK sessions before that
    one (>= R4_IV_MIN finite values). Uses sessions strictly before `day` only."""
    m = book.m
    pos = int(np.searchsorted(m.days, _day64(day), side="left"))
    if pos < 1:
        return False
    iv = atm_iv_by_day(book)
    t = iv.get(m.days[pos - 1], float("nan"))
    past = np.array([iv.get(x, float("nan")) for x in m.days[max(0, pos - 1 - K.R4_IV_LOOK):pos - 1]], float)
    past = past[np.isfinite(past)]
    return bool(np.isfinite(t) and len(past) >= K.R4_IV_MIN and t < float(np.median(past)))


def r3_daily(d: pd.DataFrame) -> pd.DataFrame:
    """Round 3 daily flags (added by daily_ind's caller when present; all causal)."""
    d = d.copy()
    d["nr4"] = nr_n(d.rng.values.astype(float), 4)
    d["lowvol"] = low_vol(d)
    d["nr7_lowvol"] = d.nr7.values & d.lowvol.values
    v5 = pd.Series(d.gk.values).rolling(5).mean()
    lo, hi = v5.rolling(K.R3_LOWVOL_WIN).min().values, v5.rolling(K.R3_LOWVOL_WIN).max().values
    with np.errstate(all="ignore"):
        pos = (v5.values - lo) / (hi - lo)
    d["hivol"] = np.isfinite(pos) & (pos >= K.R3_HIVOL_FRAC - 1e-12)                       # R3J
    return r4_daily(d)


def flag_before(book, day, col) -> bool:
    """A daily flag of the last row strictly before `day` (known at that day's open)."""
    d = book.daily
    k = int(np.searchsorted(d.date.values, _day64(day), side="left")) - 1
    return bool(d[col].values[k]) if k >= 0 else False


def et(day, hh, mm) -> int:
    from research.hunt.r83.common import et_to_ist_min
    return et_to_ist_min(pd.Timestamp(day).date(), hh, mm)


class EIAMomentum:
    """R3H (Wen, Indriawan, Lien & Xu, Energy Journal 2023): on EIA crude days the future's 11:00-11:30 ET return ->
    1-ITM that way at the next minute; stop -40% premium / the 11:00 ET price; out at the session square-off.
    Twin: the same clock on a non-EIA session of the same month (its own sign)."""
    kind = "single"

    def __init__(self, rid="R3H", books=K.CRUDE):
        self.rid, self.books = rid, books

    def spec(self, book, day):
        m = book.m
        a, b = et(day, 11, 0), et(day, 11, 30)
        i_a, i_b = m.at_or_before(day, a), m.at_or_before(day, b)
        if i_a < 0 or i_b < 0 or m.tod[i_a] < a - 5 or m.tod[i_b] < b - 5:
            return None
        mv = m.F[i_b] - m.F[i_a]
        if not np.isfinite(mv) or mv == 0:
            return None
        side = 1 if mv > 0 else -1
        return dict(day=day, i_sig=i_b, i_from=i_b + 1, side=side, ustop=float(m.F[i_a]), n_sessions=None,
                    kind="single", i_end_fixed=None, katr=None, edge=True, tod=int(m.tod[i_b]))

    def candidates(self, book, period):
        out = []
        for day in sorted(release_days(book)):
            if day in book.day_pos and in_p(day, period):
                sp = self.spec(book, day)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        if day in release_days(book):
            return None
        return self.spec(book, day)


class LastHalfHour:
    """R3I (Baltussen, Da, Lammers & Martens, JFE 2021, market intraday momentum): at the session close - 45 min, the
    future's move since the previous session's last minute; when |move| is at least the 67th percentile of the last 60
    sessions' moves (>= 20 known), 1-ITM that way; stop -40% / 0.25 x ATR14 behind; out at the square-off (close - 15).
    The previous session must be in the same option contract segment. Twin: a random session of the same month, its
    own sign, no size filter."""
    kind = "single"

    def __init__(self, rid="R3I", books=K.BOOK_ORDER):
        self.rid, self.books = rid, books

    def window(self, book, day):
        m = book.m
        pos = book.day_pos.get(day)
        if pos is None or pos == 0:
            return None
        prev = m.days[pos - 1]
        i_p = m.day_last[prev]
        t = m.session_end(day) - K.R3_LH_MIN
        i_s = m.at_or_before(day, t - 1)
        if i_s < 0 or m.tod[i_s] < t - 5 or m.seg[i_p] != m.seg[i_s]:
            return None
        mv = float(m.F[i_s] / m.F[i_p] - 1.0)
        return (i_s, mv) if np.isfinite(mv) and mv != 0 else None

    def spec(self, book, day, i_s, mv):
        atr = atr_before(book, day)
        if not np.isfinite(atr):
            return None
        side = 1 if mv > 0 else -1
        return dict(day=day, i_sig=i_s, i_from=i_s + 1, side=side, ustop=float(book.m.F[i_s] - side * K.R3_LH_KATR * atr),
                    n_sessions=None, kind="single", i_end_fixed=None, katr=K.R3_LH_KATR, edge=True,
                    tod=int(book.m.tod[i_s]))

    def candidates(self, book, period):
        hist, out = [], []
        for day in book.m.days:
            w = self.window(book, day)
            if w is None:
                continue
            past = [abs(x) for x in hist[-K.R3_LH_LOOK:]]
            if in_p(day, period) and len(past) >= K.R3_LH_PAST and abs(w[1]) >= np.quantile(past, K.R3_LH_Q):
                sp = self.spec(book, day, *w)
                if sp:
                    out.append(sp)
            hist.append(w[1])
        return out

    def twin(self, book, sp, day):
        w = self.window(book, day)
        return None if w is None else self.spec(book, day, *w)


class HighVolORB:
    """R3J (Holmberg, Loennbark & Lundstroem, FRL 2013; Lundstroem WP 861: crude ORB returns depend on the volatility
    state): only when the previous session's 5-day mean Garman-Klass variance is in the top third of its last-250
    range; the opening range = the future's minute closes 09:00-09:29 ET; the first minute close beyond it from 09:30
    to 11:00 ET -> 1-ITM that way; stop -40% / the range midpoint; out at the square-off. Twin: the same clock on a
    random session of the same month without the volatility filter."""
    kind = "single"

    def __init__(self, rid="R3J", books=K.CRUDE):
        self.rid, self.books = rid, books

    def spec(self, book, day):
        m = book.m
        a, b, z = et(day, 9, 0), et(day, 9, 29), et(day, 11, 0)
        ix = m.day_ix(day)
        rx = ix[(m.tod[ix] >= a) & (m.tod[ix] <= b)]
        if len(rx) < 20:
            return None
        hi, lo = float(np.nanmax(m.F[rx])), float(np.nanmin(m.F[rx]))
        jx = ix[(m.tod[ix] > b) & (m.tod[ix] <= z)]
        br = np.flatnonzero((m.F[jx] > hi) | (m.F[jx] < lo))
        if not len(br):
            return None
        i_sig = int(jx[br[0]])
        side = 1 if m.F[i_sig] > hi else -1
        return dict(day=day, i_sig=i_sig, i_from=i_sig + 1, side=side, ustop=float((hi + lo) / 2), n_sessions=None,
                    kind="single", i_end_fixed=None, katr=None, edge=True, tod=int(m.tod[i_sig]))

    def candidates(self, book, period):
        out = []
        for day in book.m.days:
            if in_p(day, period) and flag_before(book, day, "hivol"):
                sp = self.spec(book, day)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        return self.spec(book, day)


RULES.update({
    "R3A": NR7Var("R3A", books=K.CRUDE),
    "R3B": NR7Var("R3B", col="nr4", books=K.CRUDE),
    "R3C": NR7Var("R3C", books=K.ENERGY, trail=K.R3_TRAIL),
    "R3D": NR7Var("R3D", col="nr7_lowvol", books=K.ENERGY),
    "R3E": WeeklyNR7("R3E", books=K.ENERGY),
    "R3F": NR7Var("R3F", books=K.ENERGY, last_tod=K.R3_EARLY_TOD),
    "R3G": NR7Var("R3G", books=K.ENERGY, us_open=True),
    "R3H": EIAMomentum("R3H", books=K.CRUDE),
    "R3I": LastHalfHour("R3I", books=K.BOOK_ORDER),
    "R3J": HighVolORB("R3J", books=K.CRUDE),
})
ORDER3 = ("R3A", "R3B", "R3C", "R3D", "R3E", "R3F", "R3G", "R3H", "R3I", "R3J")
# Boss, 21 Oct (after round 3): "add trailing profit lock and trailing stoploss same as breadth donchian"
RULES.update({
    "R3K": NR7Var("R3K", books=K.CRUDE, r93=True),
    "R3L": NR7Var("R3L", books=K.CRUDE, fut=True),
})
ORDER3B = ("R3K", "R3L")
HISTORY_RULES.update({"R3K": "nr7_r93", "R3L": "nr7_r93", "R3A": "nr7", "R3B": "nr4", "R3C": "nr7", "R3D": "nr7_lowvol", "R3E": "wnr7", "R3F": "nr7",
                      "R3G": "nr7"})


# ---------------------------------------------------------------------------------------------- round 4 (PREREG_ROUND4)
class EIASpikeFade:
    """R4C (Li & Geman 2018; Rousse & Sevi 2019: the EIA spike partly reverts): on EIA crude days, the future's move
    from the last minute before the release to release + 5 min; if |move| >= 0.20 x ATR14 (known before the day),
    1-ITM AGAINST it from the next minute; underlying stop = the future's extreme between those two minutes (in the
    spike's direction); -40% premium stop, lock ladder, edge; out 60 minutes after the entry minute (or the square-off).
    Twin: the same clock and side on a non-EIA session of the same month, no size filter."""
    kind = "single"

    def __init__(self, rid="R4C", books=K.CRUDE, katr=K.R4_SPIKE_KATR):
        self.rid, self.books, self.katr = rid, books, katr          # round 5 R5D: katr 0.10

    def spec(self, book, day, rel, side=None):
        m = book.m
        i_pre = m.at_or_before(day, rel - 1)
        i_post = m.at_or_before(day, rel + K.R4_SPIKE_MIN)
        if i_pre < 0 or i_post <= i_pre or m.tod[i_pre] < rel - 5 or m.tod[i_post] < rel + K.R4_SPIKE_MIN - 2:
            return None
        mv = float(m.F[i_post] - m.F[i_pre])
        seg = m.F[i_pre:i_post + 1]
        if side is None:
            atr = atr_before(book, day)
            if not (np.isfinite(mv) and np.isfinite(atr) and atr > 0 and abs(mv) >= self.katr * atr):
                return None
            side = -1 if mv > 0 else 1
        ext = float(np.nanmax(seg)) if side < 0 else float(np.nanmin(seg))   # the spike's extreme, against the trade
        ext -= side * 1e-6               # the stop is a close BEYOND the extreme (the engine stops on touching its level)
        i_from = i_post + 1
        if i_from >= len(m.ts) or m.day[i_from] != m.day[i_post]:
            return None
        i_end = m.at_or_before(day, int(m.tod[i_from]) + K.R4_FADE_HOLD)
        return dict(day=day, i_sig=i_post, i_from=i_from, side=int(side), ustop=ext, n_sessions=None,
                    kind="single", i_end_fixed=int(i_end), katr=None, edge=True, tod=int(m.tod[i_post]), rel=rel)

    def candidates(self, book, period):
        out = []
        for day, rel in sorted(release_days(book).items()):
            if day in book.day_pos and in_p(day, period):
                sp = self.spec(book, day, rel)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        if day in release_days(book):
            return None
        return self.spec(book, day, sp["rel"], side=sp["side"])


class EveningMomentum:
    """R4D (night-session first half-hour momentum in metal futures: Bouri, Ma, Xu & Zhou; Ma et al., GFJ 64, 2025):
    the future's move from 17:00 to 17:30 IST (last minutes at or before them, within 5 minutes) -> 1-ITM that way from
    the next minute; underlying stop = the 17:00 price; -40% premium stop, lock ladder, edge; out at the session
    square-off. Every session with a non-zero move trades. Twin: the same clock and side on a random session of the
    month (so the twin tests the direction, not the clock)."""
    kind = "single"

    def __init__(self, rid="R4D", books=K.METALS):
        self.rid, self.books = rid, books

    def spec(self, book, day, side=None):
        m = book.m
        i_a, i_b = m.at_or_before(day, K.R4_EVE_A), m.at_or_before(day, K.R4_EVE_B)
        if i_a < 0 or i_b <= i_a or m.tod[i_a] < K.R4_EVE_A - 5 or m.tod[i_b] < K.R4_EVE_B - 5:
            return None
        mv = float(m.F[i_b] - m.F[i_a])
        if side is None:
            if not np.isfinite(mv) or mv == 0:
                return None
            side = 1 if mv > 0 else -1
        return dict(day=day, i_sig=i_b, i_from=i_b + 1, side=int(side), ustop=float(m.F[i_a]), n_sessions=None,
                    kind="single", i_end_fixed=None, katr=None, edge=True, tod=int(m.tod[i_b]))

    def candidates(self, book, period):
        out = []
        for day in book.m.days:
            if in_p(day, period):
                sp = self.spec(book, day)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        return self.spec(book, day, side=sp["side"])


RULES.update({
    "R4A": NR7Var("R4A", books=K.CRUDE, us_open=True),
    "R4B": NR7Var("R4B", books=K.CRUDE, side_col="tsm12"),
    "R4C": EIASpikeFade("R4C", books=K.CRUDE),
    "R4D": EveningMomentum("R4D", books=K.METALS),
    "R4E": NR7Var("R4E", books=K.CRUDE, itm=2),
    "R4F": NR7Var("R4F", books=K.CRUDE, side_col="tsm1"),
    "R4G": NR7Var("R4G", books=K.CRUDE, iv_cheap=True),
})
ORDER4 = ("R4A", "R4B", "R4C", "R4D", "R4E", "R4F", "R4G")
# the NR7-crude family: R3A and its round-4 variants share most trades; PREREG_ROUND4 s5 adds a collapsed-BH gate
NR7_CRUDE_FAMILY = (("3", "R3A"), ("4", "R4A"), ("4", "R4B"), ("4", "R4E"), ("4", "R4F"), ("4", "R4G"))
HISTORY_RULES.update({"R4A": "nr7", "R4B": "nr7_tsm12", "R4E": "nr7", "R4F": "nr7_tsm1"})


# ---------------------------------------------------------------------------------------------- round 5 (PREREG_ROUND5)
class StorageDayCall:
    """R5A (Prokopczuk, Wese Simen & Wichmann, Energy Journal 2021: natural-gas futures earn most of their return on
    EIA storage days): on storage days, the 1-ITM call at the first minute at or after 17:00 IST (within 15 minutes);
    underlying stop = the signal minute's close - 1 x ATR14 (known before the day); -40% premium stop, lock ladder,
    edge; out at the session square-off. Twin: the same clock and side on a non-storage session of the month."""
    kind = "single"

    def __init__(self, rid="R5A", books=K.GAS):
        self.rid, self.books = rid, books

    def spec(self, book, day):
        m = book.m
        i_from = m.at_or_after(day, K.R5_STORAGE_TOD, until=K.R5_STORAGE_TOD + K.FILL_WAIT)
        if i_from <= 0 or m.day[i_from - 1] != m.day[i_from]:
            return None
        i_sig = i_from - 1
        atr = atr_before(book, day)
        if not (np.isfinite(atr) and atr > 0 and np.isfinite(m.F[i_sig])):
            return None
        return dict(day=day, i_sig=int(i_sig), i_from=int(i_from), side=1,
                    ustop=float(m.F[i_sig] - K.R5_STORAGE_KATR * atr), n_sessions=None, kind="single",
                    i_end_fixed=None, katr=K.R5_STORAGE_KATR, edge=True, tod=K.R5_STORAGE_TOD)

    def candidates(self, book, period):
        out = []
        for day in sorted(release_days(book)):
            if day in book.day_pos and in_p(day, period):
                sp = self.spec(book, day)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        if day in release_days(book):
            return None
        return self.spec(book, day)


class PreEventStraddle:
    """R5E (pre-event IV run-up: Gao, Xing & Zhang, JFQA 2018; Horan, Peterson & Mahar 2004): on the book's release days
    (EIA crude / EIA storage), the ATM straddle at the first prints at or after 17:00 IST (both within 15 minutes), out
    at the last minute at or before the release - 5 (that minute within 5 minutes of it); stop -25%, short event
    ladder. Twin: the same clock on a non-release session of the month."""
    kind = "straddle"

    def __init__(self, rid="R5E", books=K.CRUDE + K.GAS):
        self.rid, self.books = rid, books

    def spec(self, book, day, rel):
        m = book.m
        i_from = m.at_or_after(day, K.R5_PRE_TOD, until=K.R5_PRE_TOD + 2)
        t_out = rel - K.R5_PRE_EXIT
        i_end = m.at_or_before(day, t_out)
        if i_from < 0 or i_end < 0 or i_end <= i_from or m.tod[i_end] < t_out - 5:
            return None
        i_sig = i_from - 1 if i_from > 0 and m.day[i_from - 1] == m.day[i_from] else i_from
        return dict(day=day, i_sig=int(i_sig), i_from=int(i_from), side=0, ustop=None, n_sessions=None,
                    kind="straddle", i_end_fixed=int(i_end), katr=None, rel=int(rel), fill_wait=K.FILL_WAIT)

    def candidates(self, book, period):
        out = []
        for day, rel in sorted(release_days(book).items()):
            if day in book.day_pos and in_p(day, period):
                sp = self.spec(book, day, rel)
                if sp:
                    out.append(sp)
        return out

    def twin(self, book, sp, day):
        if day in release_days(book):
            return None
        return self.spec(book, day, sp["rel"])


def gs_ratio_sides(silver: pd.DataFrame, gold: pd.DataFrame, look: int = K.R5_GSR_LOOK) -> np.ndarray:
    """R5F: per silver daily row, -1 (buy a silver put) when the gold-minus-silver return index is above the max of its
    previous `look` values, +1 (call) when below their min, else 0. Daily return = diff of the back-adjusted close / the
    previous raw close (unchanged by later rolls); the index is the running sum over the dates both series have. Causal:
    the value at a date uses rows up to that date only."""
    def ret(d):
        r = pd.Series(np.r_[np.nan, np.diff(d.ac.values.astype(float))] / np.r_[np.nan, d.close.values[:-1]],
                      index=pd.DatetimeIndex(d.date.values))
        return r[~r.index.duplicated()]
    j = pd.concat([ret(gold).rename("g"), ret(silver).rename("s")], axis=1, join="inner").dropna()
    idx = (j.g - j.s).cumsum()
    hi = idx.shift(1).rolling(look).max()
    lo = idx.shift(1).rolling(look).min()
    side = pd.Series(np.where(idx > hi, -1, np.where(idx < lo, 1, 0)), index=idx.index)
    return side.reindex(pd.DatetimeIndex(silver.date.values)).fillna(0).astype(int).values


class GSRatioDir(DailyDir):
    """R5F: the gold / silver ratio breakout traded on SILVERM (gs_ratio_sides); next session 09:15, 1-ITM, stop -40% /
    1.5 x ATR14, ladder, edge, 5 sessions. `gold` = GOLDM's daily frame (daily_ind; loaded from the lake on first use;
    tests set it)."""

    def __init__(self, rid="R5F", books=("SILVERM",), gold_sig="GOLDM"):
        super().__init__(rid, None, K.R5_GSR_KATR, K.R5_GSR_N, edge=True, books=books)
        self.gold, self.gold_sig = None, gold_sig

    def gold_daily(self):
        if self.gold is None:
            from research.hunt.r96_mcx_buy import data as D
            self.gold = daily_ind(D.load_daily(self.gold_sig))
        return self.gold

    def sides(self, d):
        return gs_ratio_sides(d, self.gold_daily())


RULES.update({
    "R5A": StorageDayCall("R5A", books=K.GAS),
    "R5B": NR7Var("R5B", books=K.R5_OTHER, iv_cheap=True),
    "R5C": SqueezeStraddle("R5C", n=3, col="nr7", edge=True, books=K.CRUDE, iv_cheap=True),
    "R5D": EIASpikeFade("R5D", books=K.CRUDE, katr=K.R5_SPIKE_KATR),
    "R5E": PreEventStraddle("R5E", books=K.CRUDE + K.GAS),
    "R5F": GSRatioDir("R5F"),
})
ORDER5 = ("R5A", "R5B", "R5C", "R5D", "R5E", "R5F")
# PREREG_ROUND5 s5: gate 9 collapses every family of near-copies into one test
EIA_FADE_FAMILY = (("4", "R4C"), ("5", "R5D"))
FAMILIES = (NR7_CRUDE_FAMILY + (("5", "R5C"),), EIA_FADE_FAMILY)
HISTORY_RULES.update({"R5A": "storage_long", "R5F": "dir"})


# ---------------------------------------------------------------------------------------------- round 6 (PREREG_ROUND6)
def r6_daily(d: pd.DataFrame) -> pd.DataFrame:
    """Round 6 daily column (causal): dc_side = +1 on the FIRST row whose back-adjusted close is above the max of the
    previous R6_DC_LOOK closes (-1 below the min), 0 otherwise (also 0 while fewer than R6_DC_LOOK earlier rows exist,
    and on the row after a break on the same side)."""
    d = d.copy()
    c = pd.Series(d.ac.values.astype(float))
    hi = c.shift(1).rolling(K.R6_DC_LOOK).max().values
    lo = c.shift(1).rolling(K.R6_DC_LOOK).min().values
    with np.errstate(invalid="ignore"):
        raw = np.where(c.values > hi, 1, np.where(c.values < lo, -1, 0)).astype(int)
    prev = np.r_[0, raw[:-1]]
    d["dc_side"] = np.where(raw != prev, raw, 0).astype(int)
    return d


class OpenHalfHour(LastHalfHour):
    """R6C (Jin, Kearney, Li & Yang, J. Futures Markets 40(4) 2020: on Chinese copper futures the first half-hour's
    return predicts the last half-hour's, more so on high-volume / high-volatility days). The future's move from the
    previous session's last minute to 09:30 IST (the last minute at or before 09:29, within 5 minutes of it); when
    |move| is at least the 67th percentile of the last 60 sessions' moves (>= 20 known), 1-ITM that way at the
    session close - 45 min (R3I's clock: our square-off is close - 15); stop -40% / 0.25 x ATR14 behind the entry-signal
    price; out at the square-off. The previous session must be in the same option contract segment. Twin: a random
    session of the same month, its own sign, no size filter."""

    def window(self, book, day):
        m = book.m
        pos = book.day_pos.get(day)
        if pos is None or pos == 0:
            return None
        prev = m.days[pos - 1]
        i_p = m.day_last[prev]
        i_o = m.at_or_before(day, K.R6_OPEN_B - 1)
        if i_o < 0 or m.tod[i_o] < K.R6_OPEN_B - 5 or m.day[i_o] != _day64(day):
            return None
        t = m.session_end(day) - K.R3_LH_MIN
        i_s = m.at_or_before(day, t - 1)
        if i_s < 0 or m.tod[i_s] < t - 5 or m.seg[i_p] != m.seg[i_s]:
            return None
        mv = float(m.F[i_o] / m.F[i_p] - 1.0)
        return (i_s, mv) if np.isfinite(mv) and mv != 0 else None


RULES.update({
    "R6A": NR7Var("R6A", books=K.BASE),
    "R6B": NR7Var("R6B", books=K.BASE, iv_cheap=True),
    "R6C": OpenHalfHour("R6C", books=K.BASE),
    "R6D": DailyDir("R6D", "dc_side", K.R6_DC_KATR, K.R6_DC_N, edge=True, books=K.BASE),
})
ORDER6 = ("R6A", "R6B", "R6C", "R6D")
# PREREG_ROUND6 s4: R6B is R6A with a filter -> one more family for gate 9
NR7_BASE_FAMILY = (("6", "R6A"), ("6", "R6B"))
FAMILIES = FAMILIES + (NR7_BASE_FAMILY,)
HISTORY_RULES.update({"R6A": "nr7", "R6D": "dir"})
