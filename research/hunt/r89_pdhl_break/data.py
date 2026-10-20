"""R89 panels: one underlying's 1-minute bars as flat arrays, with the day-level facts the rules need.

A Panel holds, for every session in order:
  minutes   mt (minute of day), mo / mh / ml / mc (float), mday (session index); ms[d]:me[d] is session d's slice
  levels    pdh / pdl (previous session's high / low), popen (first open), pclose (previous session's last close)
  flags     expiry (the index's / MCX option's expiry day), skip (no previous session; MCX first session of a contract)
  sq        global minute of the session-end fallback (NSE: first minute at or after 15:10 / 15:05 on expiry, exit at its
            OPEN; MCX: R53 day_cut, exit at its CLOSE)
  htf       daily_up / daily_dn (previous close vs its 20-session average) and the 15m candles (c15_*) for the 15m test
  sr        R / S (PREREG 5 SR): nearest confirmed 15m fractal swing high above / low below the first open, from the
            previous 5 sessions (NaN when none)
Index panels come from R51 day stores (spot 1m OHLC, true wicks). MCX panels come from R53 Markets (close-only near
future: a minute's open is the previous minute's close, high / low = max / min of the two; the session's first minute has
open = close).
"""
from __future__ import annotations

import pickle
from dataclasses import dataclass, field
from datetime import date

import numpy as np
import pandas as pd

from . import cfg as K


@dataclass
class Panel:
    name: str
    kind: str                                   # 'nse' | 'mcx'
    days: list
    mt: np.ndarray
    mo: np.ndarray
    mh: np.ndarray
    ml: np.ndarray
    mc: np.ndarray
    mday: np.ndarray
    ms: np.ndarray
    me: np.ndarray
    expiry: np.ndarray
    skip: np.ndarray
    sq: np.ndarray
    extra: dict = field(default_factory=dict)   # MCX: gidx (global minute -> market grid index)

    def __post_init__(self):
        self.ndays = len(self.days)
        self.anchor = K.NSE_OPEN if self.kind == "nse" else K.MCX_OPEN
        self._levels()
        self._htf()
        self._sr()

    # ---------------------------------------------------------------------------------------------- day facts
    def _levels(self):
        n = self.ndays
        hi = np.array([self.mh[self.ms[d]:self.me[d]].max() for d in range(n)])
        lo = np.array([self.ml[self.ms[d]:self.me[d]].min() for d in range(n)])
        last = np.array([self.mc[self.me[d] - 1] for d in range(n)])
        self.day_hi, self.day_lo, self.day_close = hi, lo, last
        self.pdh = np.r_[np.nan, hi[:-1]]
        self.pdl = np.r_[np.nan, lo[:-1]]
        self.pclose = np.r_[np.nan, last[:-1]]
        self.popen = np.array([self.mo[self.ms[d]] for d in range(n)])
        self.skip = self.skip | ~np.isfinite(self.pdh)

    def _htf(self):
        c = pd.Series(self.day_close)
        sma = c.rolling(20, min_periods=20).mean()
        prev_c, prev_sma = c.shift(1).values, sma.shift(1).values
        ok = np.isfinite(prev_sma)
        self.daily_up = ok & (prev_c > prev_sma)
        self.daily_dn = ok & (prev_c < prev_sma)
        C15 = candles(self, 15)
        ema = pd.Series(C15["c"]).ewm(span=20, adjust=False, min_periods=20).mean().values
        self.c15_day = C15["day"]
        self.c15_end = self.mt[C15["e"]]
        self.c15_up = np.isfinite(ema) & (C15["c"] > ema)
        self.c15_dn = np.isfinite(ema) & (C15["c"] < ema)
        self.c15_key = self.c15_day.astype(np.int64) * 10000 + self.c15_end
        self.C15 = C15

    def htf_ok(self, d: int, tod: int, side: int) -> bool:
        """PREREG 5 HTF: daily structure and the last 15m candle that CLOSED before minute `tod` starts."""
        if not (self.daily_up[d] if side > 0 else self.daily_dn[d]):
            return False
        i = int(np.searchsorted(self.c15_key, d * 10000 + tod - 1, side="right")) - 1
        if i < 0:
            return False
        return bool(self.c15_up[i] if side > 0 else self.c15_dn[i])

    def _sr(self):
        C15 = self.C15
        hs, ls = fractals(C15["h"], C15["l"], 5)
        n = self.ndays
        self.R = np.full(n, np.nan)
        self.S = np.full(n, np.nan)
        tick = K.TICK.get(self.name, 0.05)
        cday = C15["day"]
        for d in range(n):
            lo_d = d - K.SWING_SESSIONS
            # a fractal at k is confirmed by candle k + 2; both must lie in sessions lo_d .. d - 1
            okh = hs & (cday >= lo_d) & (cday <= d - 1)
            okh[:-2] &= cday[2:] <= d - 1
            okh[-2:] = False
            okl = ls & (cday >= lo_d) & (cday <= d - 1)
            okl[:-2] &= cday[2:] <= d - 1
            okl[-2:] = False
            p = self.popen[d]
            up = C15["h"][okh]
            up = up[up > p]
            dn = C15["l"][okl]
            dn = dn[dn < p]
            r = up.min() if len(up) else np.nan
            s = dn.max() if len(dn) else np.nan
            for lv, name in ((r, "R"), (s, "S")):
                if np.isfinite(lv) and (abs(lv - self.pdh[d]) <= tick or abs(lv - self.pdl[d]) <= tick):
                    lv = np.nan
                getattr(self, name)[d] = lv

    def levels(self, d: int, sr: bool) -> np.ndarray:
        L = [self.pdh[d], self.pdl[d]]
        if sr:
            L += [self.R[d], self.S[d]]
        return np.array(L, dtype=np.float64)

    def day_index(self, dt: date) -> int:
        return self._di[dt]

    @property
    def _di(self):
        if not hasattr(self, "_di_cache"):
            self._di_cache = {d: i for i, d in enumerate(self.days)}
        return self._di_cache


# ------------------------------------------------------------------------------------------------ candles, swings
def candles(P, tf: int) -> dict:
    """TF candles (anchored at the session open), per session; global arrays day, o, h, l, c, s, e (first / last
    global minute index)."""
    b = (P.mt - P.anchor) // tf
    key = P.mday.astype(np.int64) * 100000 + b
    brk = np.r_[0, np.flatnonzero(key[1:] != key[:-1]) + 1]
    end = np.r_[brk[1:], len(key)] - 1
    return dict(day=P.mday[brk].astype(np.int32), o=P.mo[brk], h=np.maximum.reduceat(P.mh, brk),
                l=np.minimum.reduceat(P.ml, brk), c=P.mc[end], s=brk.astype(np.int64), e=end.astype(np.int64))


def fractals(h: np.ndarray, l: np.ndarray, n: int):
    """Fractal swing highs / lows of N candles (N = 3: 1 each side, N = 5: 2 each side), strict. Index k is a swing when
    its high is above every neighbour's within (N - 1) / 2; it is CONFIRMED once candle k + (N - 1) / 2 has closed."""
    w = (n - 1) // 2
    m = len(h)
    hs = np.zeros(m, bool)
    ls = np.zeros(m, bool)
    if m < 2 * w + 1:
        return hs, ls
    hs[w:m - w] = True
    ls[w:m - w] = True
    for j in range(1, w + 1):
        hs[w:m - w] &= (h[w:m - w] > h[w - j:m - w - j]) & (h[w:m - w] > h[w + j:m - w + j or None])
        ls[w:m - w] &= (l[w:m - w] < l[w - j:m - w - j]) & (l[w:m - w] < l[w + j:m - w + j or None])
    return hs, ls


# ------------------------------------------------------------------------------------------------ builders
def _flat(name, kind, days, per_day, expiry, skip, sq_fn, extra=None) -> Panel:
    mt, mo, mh, ml, mc, md, ms, me, sq = [], [], [], [], [], [], [], [], []
    pos = 0
    for i, (t, o, h, l, c) in enumerate(per_day):
        mt.append(t), mo.append(o), mh.append(h), ml.append(l), mc.append(c), md.append(np.full(len(t), i, np.int32))
        ms.append(pos)
        sq.append(pos + sq_fn(i, t))
        pos += len(t)
        me.append(pos)
    cat = lambda x, dt: np.concatenate(x).astype(dt)  # noqa: E731
    return Panel(name, kind, list(days), cat(mt, np.int32), cat(mo, float), cat(mh, float), cat(ml, float),
                 cat(mc, float), cat(md, np.int32), np.array(ms, np.int64), np.array(me, np.int64),
                 np.asarray(expiry, bool), np.asarray(skip, bool), np.array(sq, np.int64), extra or {})


def _nse_sq(expiry):
    def f(i, t):
        tgt = K.NSE_SQ_EXPIRY if expiry[i] else K.NSE_SQ
        j = int(np.searchsorted(t, tgt))
        return min(j, len(t) - 1)
    return f


def nse_from_store(und: str, days_store: dict) -> Panel:
    days, per, exp = [], [], []
    for d in sorted(days_store):
        a = days_store[d].idx
        ok = np.isfinite(a[3])
        if ok.sum() < 120:
            continue
        r = np.flatnonzero(ok)
        days.append(d)
        per.append((K.NSE_OPEN + r, a[0, r], a[1, r], a[2, r], a[3, r]))
        exp.append(bool(days_store[d].expiry))
    exp = np.array(exp, bool)
    return _flat(und, "nse", days, per, exp, np.zeros(len(days), bool), _nse_sq(exp))


def nse_old(und: str, lo: date, hi: date) -> Panel:
    """Older index history (descriptive futures-only view): lake index_1m, regular sessions only (R87 A2 rule:
    weekdays with >= 120 minutes and under half of them flat). Expiry days are not identified here (15:10 every day)."""
    parts = []
    for y in range(lo.year, hi.year + 1):
        p = K.LAKE / "index_1m" / f"index_1m_{und}_{y}.parquet"
        if p.exists():
            parts.append(pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"]))
    x = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(x.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"] = ts.dt.date
    x["t"] = (ts.dt.hour * 60 + ts.dt.minute).astype(np.int32)
    x = x[(x.t >= K.NSE_OPEN) & (x.t < K.NSE_OPEN + 375) & (x.day >= lo) & (x.day <= hi)]
    x = x[(x[["open", "high", "low", "close"]] > 0).all(axis=1)].drop_duplicates(["day", "t"], keep="last")
    x = x.sort_values(["day", "t"])
    days, per = [], []
    for d, g in x.groupby("day", sort=True):
        if pd.Timestamp(d).dayofweek >= 5 or len(g) < 120 or (g.high == g.low).mean() >= 0.5:
            continue
        days.append(d)
        per.append((g.t.values, g.open.values, g.high.values, g.low.values, g.close.values))
    exp = np.zeros(len(days), bool)
    return _flat(und, "nse", days, per, exp, np.zeros(len(days), bool), _nse_sq(exp))


def mcx_from_market(sym: str, m) -> Panel:
    days, per, exp, skip, gidx, cut = [], [], [], [], [], []
    prev_seg = None
    for d64 in m.days:
        ix = m.day_ix(d64)
        F = m.F[ix]
        ok = np.isfinite(F) & (F > 0)
        ix, F = ix[ok], F[ok]
        if len(ix) < 60:
            continue
        seg = int(m.seg[ix[0]])
        o = np.r_[F[0], F[:-1]]
        days.append(pd.Timestamp(d64).date())
        per.append((m.tod[ix].astype(np.int32), o, np.maximum(o, F), np.minimum(o, F), F))
        exp.append(bool(m.is_exp[ix[0]]))
        skip.append(prev_seg is not None and seg != prev_seg)
        prev_seg = seg
        gidx.append(ix)
        c = m.day_cut[d64]
        cut.append(int(np.searchsorted(ix, c, side="right")) - 1)
    sq = lambda i, t: max(cut[i], 0)  # noqa: E731
    return _flat(sym, "mcx", days, per, exp, np.array(skip, bool), sq, dict(gidx=np.concatenate(gidx)))


# ------------------------------------------------------------------------------------------------ cached loaders
def _cache(name: str):
    return K.OUT / "panels" / f"{name}.pkl"


def panel(name: str, rebuild: bool = False) -> Panel:
    p = _cache(name)
    if p.exists() and not rebuild:
        with open(p, "rb") as f:
            return pickle.load(f)
    if name.startswith("OLD_"):
        P = nse_old(name[4:], K.OLD[0], K.OLD[1])
    elif name in K.INDICES:
        from . import store
        P = nse_from_store(name, store.load(name))
    else:
        P = mcx_from_market(name, market(name))
    p.parent.mkdir(parents=True, exist_ok=True)
    with open(p, "wb") as f:
        pickle.dump(P, f, protocol=pickle.HIGHEST_PROTOCOL)
    return P


def market(sym: str):
    from research.hunt.r53.mcx import data as D53
    return D53.load_market(sym)
