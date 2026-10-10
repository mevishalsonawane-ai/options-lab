"""Data layer: index minutes, option chains (real Dhan minute bars with OI), India VIX - read-only, cached in memory.

Sources (all under config.DATA, untrusted: run with python -I):
  options/<U>/<WEEK|MONTH>/<CALL|PUT>/<YYYY>.parquet  rolling ATM-10..ATM+10 minute bars of the NEAREST weekly / monthly
      expiry (Dhan expiryCode 1). Re-keyed here by actual strike. A year file holds 30-day windows that START in that
      year, so year Y is read from files Y-1 and Y and filtered on the bar time; duplicate (minute, offset) rows keep
      the earlier file's (exactly as maxloss/scripts/prep.py, the export every earlier study used).
  candles/minute/IDX_I/<U>/<YYYY>.parquet, candles/daily/IDX_I/<U>.parquet  index / INDIA_VIX bars.
  jx/ix_<U>.pkl (NIFTY, BANKNIFTY, FINNIFTY): the per-day index 1-min OHLC that earlier studies used (real external /
      Dhan index feeds checked against Dhan's spot print; Dhan's spot print where none agreed), the day's lot read from
      the option OI moves, and the expiry-day flag. Reused as is so results line up with those studies.

Series:
  'near'  WEEK when Dhan has the weekly series that day, else MONTH (BANKNIFTY / FINNIFTY after Nov 2024) - the nearest
          listed expiry, as the app's arms trade it. On an expiry day this is TODAY's expiry.
  'week'  the weekly series only.   'month'  the nearest monthly series.
  Next-week (2nd weekly) contracts are NOT in the data (Dhan's rolling endpoint served expiryCode 1 only).
"""
from __future__ import annotations

import glob
import math
import os
import pickle
from collections import Counter, OrderedDict
from datetime import date, timedelta
from functools import reduce

import numpy as np
import pandas as pd
import pyarrow.parquet as pq

from . import config as C

EPOCH = date(1970, 1, 1)
IST_MS = 19800 * 1000


def dnum(d: date) -> int:
    return (d - EPOCH).days


def ddate(n: int) -> date:
    return EPOCH + timedelta(days=int(n))


# ------------------------------------------------------------------------------------------------ option chains
_TABLES = OrderedDict()   # raw parquet tables, LRU (a year file is read for its own year and for the next one's spill)
_COLS = ["ts", "offset", "strike", "spot", "open", "high", "low", "close", "volume", "oi", "iv"]


def _table(f):
    if f in _TABLES:
        _TABLES.move_to_end(f)
        return _TABLES[f]
    t = pq.read_table(f, columns=_COLS)
    ts = t.column("ts").cast("int64").to_numpy() + IST_MS          # IST wall-clock ms
    a = dict(ts=ts, day=ts // 86_400_000, m=((ts % 86_400_000) // 60_000).astype(np.int16))
    for c in _COLS[1:]:
        a[c] = t.column(c).to_numpy()
    _TABLES[f] = a
    while len(_TABLES) > 4:
        _TABLES.popitem(last=False)
    return a


class Block:
    """One calendar year of one (underlying, series): rows sorted by (day, right C>P, strike, minute).

    Prices stay float32 here (as stored); Chain turns one day into float64 2-decimal prints."""

    def __init__(self, und, series, year):
        lo, hi = dnum(date(year, 1, 1)), dnum(date(year, 12, 31))
        parts = []
        for right, side in ((1, "CALL"), (-1, "PUT")):
            arrs = []
            for y in (year - 1, year):
                for f in sorted(glob.glob(os.path.join(C.DATA, "options", und, series, side, f"{y}*.parquet"))):
                    a = _table(f)
                    keep = (a["day"] >= lo) & (a["day"] <= hi) & (a["m"] >= C.OPEN_M) & (a["m"] <= C.LAST_M)
                    if keep.any():
                        arrs.append((f, {c: v[keep] for c, v in a.items()}))
            if not arrs:
                continue
            a = {c: np.concatenate([x[c] for _, x in arrs]) for c in arrs[0][1]}
            if len(arrs) > 1:
                # duplicate (minute, offset) rows across files: keep the first (earlier file), as prep.py
                key = a["ts"] * 64 + (a["offset"].astype(np.int64) + 32)
                _, first = np.unique(key, return_index=True)
                if len(first) < len(key):
                    first.sort()
                    a = {c: v[first] for c, v in a.items()}
            a["right"] = np.full(len(a["ts"]), right, np.int8)
            parts.append(a)
        if not parts:
            self.n = 0
            self.days = np.zeros(0, np.int64)
            return
        a = {c: np.concatenate([x[c] for x in parts]) for c in parts[0]}
        strike = np.rint(a["strike"]).astype(np.int64)
        key = (((a["day"] - lo) * 2 + (a["right"] < 0)) * 2_000_000 + strike) * 1024 + a["m"]
        order = np.argsort(key, kind="stable")
        key = key[order]
        dup = np.zeros(len(key), bool)
        dup[1:] = key[1:] == key[:-1]        # a strike seen at two offsets in one minute: keep the first
        sel = order[~dup]
        self.day = a["day"][sel]
        self.m, self.strike, self.right, self.off = a["m"][sel], strike[sel], a["right"][sel], a["offset"][sel]
        self.o, self.h, self.l, self.c = a["open"][sel], a["high"][sel], a["low"][sel], a["close"][sel]
        self.v, self.oi, self.iv, self.spot = a["volume"][sel], a["oi"][sel], a["iv"][sel], a["spot"][sel]
        self.n = len(self.day)
        self.days, self.start = np.unique(self.day, return_index=True)
        self.end = np.append(self.start[1:], self.n)

    def has(self, dn):
        i = np.searchsorted(self.days, dn)
        return i < len(self.days) and self.days[i] == dn

    def rows(self, dn):
        i = np.searchsorted(self.days, dn)
        if i >= len(self.days) or self.days[i] != dn:
            return None
        return slice(self.start[i], self.end[i])


class Chain:
    """One day's option chain of one series: dense [strike, minute] arrays (NaN = no bar that minute).

    K: strikes (int, ascending). For right in ('C', 'P'): self.o[right], .h, .l, .c, .v (volume, units), .oi (units),
    .iv (%). spot: Dhan's per-minute underlying print (from the ATM rows), NaN where none.
    """

    FIELDS = ("o", "h", "l", "c", "v", "oi", "iv")

    def __init__(self, blk: Block, sl: slice, und: str, series: str, d: date):
        self.und, self.series, self.day = und, series, d
        k = blk.strike[sl]
        self.K = np.unique(k)
        nk = len(self.K)
        ki = np.searchsorted(self.K, k)
        col = blk.m[sl].astype(np.int64) - C.OPEN_M
        r = blk.right[sl]
        for f in self.FIELDS:
            setattr(self, f, {})
        r2 = lambda x: np.round(x[sl].astype(np.float64), 2)  # noqa: E731  (prices as their 2-decimal prints)
        src = dict(o=r2(blk.o), h=r2(blk.h), l=r2(blk.l), c=r2(blk.c), v=blk.v[sl].astype(np.float64),
                   oi=blk.oi[sl].astype(np.float64), iv=blk.iv[sl].astype(np.float64))
        for rv, rn in ((1, "C"), (-1, "P")):
            mk = r == rv
            for f in self.FIELDS:
                a = np.full((nk, C.W), np.nan)
                a[ki[mk], col[mk]] = src[f][mk]
                getattr(self, f)[rn] = a
        sp = np.full(C.W, np.nan)
        atm = blk.off[sl] == 0
        sp[col[atm]] = np.round(blk.spot[sl][atm].astype(np.float64), 2)
        self.spot = sp

    def kpos(self, strike):
        i = int(np.searchsorted(self.K, strike))
        return i if i < len(self.K) and self.K[i] == strike else -1

    def series_of(self, strike, right):
        """(o, h, l, c, v, oi) rows of one contract, or None."""
        i = self.kpos(strike)
        if i < 0:
            return None
        return tuple(getattr(self, f)[right][i] for f in ("o", "h", "l", "c", "v", "oi"))


class Options:
    """Lazy per-year loader of one underlying's option series. chain(day, series) -> Chain or None."""

    def __init__(self, und, max_blocks=3, max_chains=32):
        self.und = und
        self.blocks = OrderedDict()
        self.chains = OrderedDict()
        self.max_blocks, self.max_chains = max_blocks, max_chains

    def block(self, series, year):
        k = (series, year)
        if k in self.blocks:
            self.blocks.move_to_end(k)
            return self.blocks[k]
        b = Block(self.und, series, year)
        self.blocks[k] = b
        while len(self.blocks) > self.max_blocks:
            self.blocks.popitem(last=False)
        return b

    def resolve(self, d: date, series: str):
        if series in ("near", "week"):
            b = self.block("WEEK", d.year)
            if b.n and b.has(dnum(d)):
                return "WEEK"
            if series == "week":
                return None
        b = self.block("MONTH", d.year)
        return "MONTH" if b.n and b.has(dnum(d)) else None

    def chain(self, d: date, series: str = "near"):
        s = self.resolve(d, series)
        if s is None:
            return None
        k = (d, s)
        if k in self.chains:
            self.chains.move_to_end(k)
            return self.chains[k]
        b = self.block(s, d.year)
        ch = Chain(b, b.rows(dnum(d)), self.und, s, d)
        self.chains[k] = ch
        while len(self.chains) > self.max_chains:
            self.chains.popitem(last=False)
        return ch

    def days(self, series="near", years=range(2020, 2027)):
        out = set()
        for y in years:
            for s in (("WEEK", "MONTH") if series == "near" else ("WEEK",) if series == "week" else ("MONTH",)):
                b = self.block(s, y)
                out |= {ddate(x) for x in b.days}
        return sorted(out)


# ------------------------------------------------------------------------------------------------ index minutes
def lot_from_oi(chain: Chain):
    """Lots.lotFromChain: gcd of the 5 most frequent OI move sizes, >= 95% of moves agreeing; 0 if unclear."""
    moves = []
    for r in ("C", "P"):
        for row in chain.oi[r]:
            x = row[~np.isnan(row)].astype(np.int64)
            x = np.abs(np.diff(x))
            moves.extend(x[x != 0].tolist())
    if not moves:
        return 0
    common = [k for k, _ in Counter(moves).most_common(5)]
    g = reduce(math.gcd, common)
    if g <= 1:
        return 0
    return g if sum(1 for m in moves if m % g == 0) / len(moves) >= 0.95 else 0


def expiry_from_chain(chain: Chain, step):
    """expiry_days.py: min over strikes of CE+PE at the last common minute < 0.75 x step -> expiry day."""
    best = None
    lastc = {}
    for r in ("C", "P"):
        c = chain.c[r]
        valid = ~np.isnan(c)
        last = np.where(valid.any(axis=1), C.W - 1 - np.argmax(valid[:, ::-1], axis=1), -1)
        lastc[r] = (last, c)
    m = max(lastc["C"][0].max(), lastc["P"][0].max())
    for i in range(len(chain.K)):
        lc, lp = lastc["C"][0][i], lastc["P"][0][i]
        if lc >= m - 2 and lp >= m - 2 and lc >= 0 and lp >= 0:
            v = lastc["C"][1][i, lc] + lastc["P"][1][i, lp]
            best = v if best is None else min(best, v)
    return best is not None and best < 0.75 * step


class Index:
    """Per-day index minutes. day(d) -> dict(m, o, h, l, c, lot, exp, real); dense matrices via .mat()."""

    def __init__(self, und, opts: Options | None = None):
        self.und = und
        path = os.path.join(C.JX, f"ix_{und}.pkl")
        if os.path.exists(path):
            with open(path, "rb") as f:
                self.d = pickle.load(f)
            self.source = "jx"
        else:
            self.d = self._build(opts or Options(und))
            self.source = "dhan_idx"
        for x in self.d.values():
            x["real"] = not (np.all(x["o"] == x["h"]) and np.all(x["l"] == x["c"]) and np.all(x["o"] == x["c"]))
        self.days = sorted(self.d)
        self.pos = {d: i for i, d in enumerate(self.days)}
        self._mat = None
        self._daily = None

    def _build(self, opts):
        os.makedirs(C.CACHE, exist_ok=True)
        path = os.path.join(C.CACHE, f"ix_{self.und}.pkl")
        if os.path.exists(path):
            with open(path, "rb") as f:
                return pickle.load(f)
        fs = sorted(glob.glob(os.path.join(C.DATA, "candles", "minute", "IDX_I", self.und, "*.parquet")))
        ix = pd.concat([pd.read_parquet(f, columns=["ts", "open", "high", "low", "close"]) for f in fs]) if fs else None
        bars = {}
        if ix is not None:
            ix["ts"] = ix.ts.dt.tz_localize(None)
            ix["day"] = ix.ts.dt.date
            ix["m"] = ix.ts.dt.hour * 60 + ix.ts.dt.minute
            ix = ix[(ix.m >= C.OPEN_M) & (ix.m <= C.LAST_M)].drop_duplicates(["day", "m"]).sort_values(["day", "m"])
            for d, g in ix.groupby("day"):
                bars[d] = g
        out = {}
        last_lot = 0
        for d in opts.days("near"):
            ch = opts.chain(d, "near")
            if ch is None:
                continue
            lot = lot_from_oi(ch) or last_lot or lot_schedule(self.und, d)
            last_lot = lot
            exp = expiry_from_chain(ch, C.STEP[self.und])
            g = bars.get(d)
            sp = ch.spot
            if g is not None and len(g) > 300:
                m = g.m.values.astype(int)
                o, h, l, c = (np.round(g[k].values.astype(float), 2) for k in ("open", "high", "low", "close"))
            else:
                ok = ~np.isnan(sp)
                m = np.nonzero(ok)[0] + C.OPEN_M
                o = h = l = c = sp[ok]
            out[d] = dict(lot=int(lot), exp=bool(exp), m=m, o=o, h=h, l=l, c=c)
        with open(path, "wb") as f:
            pickle.dump(out, f)
        return out

    def day(self, d):
        return self.d.get(d)

    def mat(self):
        """Dense (n_days, W) float arrays o, h, l, c (NaN = no bar) aligned with self.days."""
        if self._mat is None:
            n = len(self.days)
            M = {k: np.full((n, C.W), np.nan) for k in ("o", "h", "l", "c")}
            for i, d in enumerate(self.days):
                x = self.d[d]
                col = x["m"] - C.OPEN_M
                ok = (col >= 0) & (col < C.W)
                for k in M:
                    M[k][i, col[ok]] = x[k][ok]
            self._mat = M
        return self._mat

    def daily(self):
        """Daily OHLC from the minutes (session 09:15-15:29), with lot / exp / real flags."""
        if self._daily is None:
            rows = []
            for d in self.days:
                x = self.d[d]
                rows.append((d, x["o"][0], x["h"].max(), x["l"].min(), x["c"][-1], x["lot"], x["exp"], x["real"]))
            self._daily = pd.DataFrame(rows, columns=["day", "open", "high", "low", "close", "lot", "exp", "real"]).set_index("day")
        return self._daily

    def lot(self, d, mode="data"):
        """'data': the day's lot from the OI moves (forward-filled); 'official': the exchange schedule;
        'today': the latest lot in the data (today's contract size)."""
        if mode == "today":
            return int(self.d[self.days[-1]]["lot"])
        if mode == "official":
            return lot_schedule(self.und, d)
        x = self.d.get(d)
        return int(x["lot"]) if x and x["lot"] > 0 else lot_schedule(self.und, d)


def lot_schedule(und, d):
    lot = None
    for since, n in C.LOT_SCHEDULE[und]:
        if d >= date.fromisoformat(since):
            lot = n
    return lot


# ------------------------------------------------------------------------------------------------ VIX
class Vix:
    def __init__(self):
        p = os.path.join(C.DATA, "candles", "daily", "IDX_I", "INDIA_VIX.parquet")
        d = pd.read_parquet(p)
        d["day"] = d.ts.dt.tz_localize(None).dt.date
        self.daily = d.drop_duplicates("day").set_index("day")[["open", "high", "low", "close"]].astype(float).sort_index()
        self._min = None

    def prev_close(self, d):
        s = self.daily.close
        i = s.index.searchsorted(d) - 1
        return float(s.iloc[i]) if i >= 0 else float("nan")

    def minutes(self):
        """{day: dense W-array of VIX closes} (Dhan minute data from Oct 2021)."""
        if self._min is None:
            fs = sorted(glob.glob(os.path.join(C.DATA, "candles", "minute", "IDX_I", "INDIA_VIX", "*.parquet")))
            v = pd.concat([pd.read_parquet(f, columns=["ts", "close"]) for f in fs])
            v["ts"] = v.ts.dt.tz_localize(None)
            v["day"] = v.ts.dt.date
            v["m"] = v.ts.dt.hour * 60 + v.ts.dt.minute
            v = v[(v.m >= C.OPEN_M) & (v.m <= C.LAST_M)].drop_duplicates(["day", "m"])
            out = {}
            for d, g in v.groupby("day"):
                a = np.full(C.W, np.nan)
                a[g.m.values - C.OPEN_M] = g.close.values
                out[d] = a
            self._min = out
        return self._min


# ------------------------------------------------------------------------------------------------ one handle for all
class Market:
    """Lazy access to everything: mk.index(u), mk.options(u), mk.vix."""

    def __init__(self):
        self._ix, self._op, self._vix = {}, {}, None

    def options(self, u) -> Options:
        if u not in self._op:
            self._op[u] = Options(u)
        return self._op[u]

    def release(self, u=None):
        """Drop cached option blocks / chains / raw tables (all underlyings, or one) to free memory."""
        for k in ([u] if u else list(self._op)):
            if k in self._op:
                self._op[k].blocks.clear()
                self._op[k].chains.clear()
        _TABLES.clear()

    def index(self, u) -> Index:
        if u not in self._ix:
            self._ix[u] = Index(u, self.options(u))
        return self._ix[u]

    @property
    def vix(self) -> Vix:
        if self._vix is None:
            self._vix = Vix()
        return self._vix


_MK = None


def market() -> Market:
    global _MK
    if _MK is None:
        _MK = Market()
    return _MK
