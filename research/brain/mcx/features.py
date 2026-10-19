"""L4 MCX feature store (MB1): point-in-time features on the cube, one row per cube minute.

Same machinery as the Indian and crypto stores (research/brain/features/registry.py: Feature, @feature, run(),
render_catalogue) with its own registry. The rule, checked for every feature by tests/test_mcx_brain.py and by
`python -m research.brain.mcx.guard` on the real cube: the value at minute t uses only cube rows with ts <= t (and,
for the daily family, daily bars of EARLIER days). Row t holds the bar that closes at t + 1 minute.

Prices are closes only (PREREG section 1). Windows count cube rows (session minutes) and never cross a contract
segment (cube `seg`); the overnight gap inside one segment is a real move and is included. Lookback "60m" = the last
60 rows, "20d" = the previous 20 sessions.

    python -m research.brain.mcx.features catalog [--check]       # FEATURES.md
    python -m research.brain.mcx.features build --symbols ...      # <cache>/mcx/features/<SYM>.parquet
"""
from __future__ import annotations

import argparse
import hashlib
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from ..features import registry as india
from . import config

FAMILIES = ("price", "vol", "trend", "levels", "session", "volume", "options", "calendar", "events", "daily")
REGISTRY: dict[str, india.Feature] = {}
PENDING: dict[str, dict] = {}
GOOD = ("ok", "stale")
MIN_PER_YEAR = 525_600
CATALOG = Path(__file__).resolve().parent / "FEATURES.md"


def feature(name, family, version, inputs, lookback, description):
    return india.feature(name, family, version, inputs, lookback, description, registry=REGISTRY, families=FAMILIES)


def pending(name, family, needs, description):
    return india.pending(name, family, needs, description, registry=REGISTRY, pending_=PENDING, families=FAMILIES)


class Ctx:
    """Cube minutes of one symbol (sorted, one row per grid minute) with point-in-time helpers."""

    def __init__(self, minutes: pd.DataFrame, sym: str, daily: pd.DataFrame | None = None):
        m = minutes.sort_values("ts").drop_duplicates("ts").reset_index(drop=True)
        self.m, self.sym, self.n = m, sym, len(m)
        self.ts = pd.DatetimeIndex(m.ts)
        good = m.flag.isin(GOOD).to_numpy() if "flag" in m else np.zeros(self.n, bool)
        self.c = np.where(good, m.close.to_numpy(float), np.nan)
        self.day = self.ts.normalize().values
        self.dayid, self.days = pd.factorize(self.day, sort=True)
        self.segid = pd.factorize(m.seg.astype(str))[0] if "seg" in m else np.zeros(self.n, int)
        # one group per (segment, day) and per segment
        self.sd = pd.factorize(pd.Series(self.segid).astype(str) + "|" + pd.Series(self.dayid).astype(str))[0]
        self.tod = (self.ts.hour * 60 + self.ts.minute).to_numpy()
        self.daily = daily
        self._memo: dict = {}
        # log price with no jump at a segment change: last good close carried within the segment
        lc = pd.Series(np.log(self.c)).groupby(self.segid).ffill().to_numpy()
        r = np.r_[np.nan, np.diff(lc)]
        r[np.r_[True, self.segid[1:] != self.segid[:-1]]] = np.nan
        self.r1 = r * 100                       # 1-minute log return, % (NaN at a segment start)
        self.lc = lc                            # log close, carried within the segment

    def col(self, name: str) -> np.ndarray:
        return self.m[name].to_numpy(float) if name in self.m else np.full(self.n, np.nan)

    def memo(self, key, fn):
        if key not in self._memo:
            self._memo[key] = fn()
        return self._memo[key]

    # --- windows over the last k rows of the same segment ---
    def shift(self, a, k: int) -> np.ndarray:
        return pd.Series(np.asarray(a, float)).groupby(self.segid).shift(k).to_numpy()

    def roll(self, a, k: int, how: str, min_frac: float = 0.8) -> np.ndarray:
        s = pd.Series(np.asarray(a, float))
        r = s.groupby(self.segid).rolling(k, min_periods=max(1, int(k * min_frac)))
        return getattr(r, how)().reset_index(level=0, drop=True).sort_index().to_numpy()

    def ret(self, k: int) -> np.ndarray:
        """log return over the last k rows, % (NaN if the window starts before the segment)."""
        return (self.lc - self.shift(self.lc, k)) * 100

    def crange(self, k: int) -> np.ndarray:
        """close range of the last k rows (max - min of closes), % of the close."""
        return (np.exp(self.roll(self.lc, k, "max") - self.roll(self.lc, k, "min")) - 1) * 100

    # --- the session (one day of one segment) so far ---
    def day_cum(self, a, how: str) -> np.ndarray:
        s = pd.Series(np.asarray(a, float))
        g = s.groupby(self.sd)
        if how == "mean":
            return (g.cumsum() / pd.Series(np.isfinite(s)).groupby(self.sd).cumsum()).to_numpy()
        return getattr(g, "cum" + how)().to_numpy()

    def day_first(self, a) -> np.ndarray:
        s = pd.Series(np.asarray(a, float))
        first = s.groupby(self.sd).transform(lambda x: x.dropna().iloc[0] if x.notna().any() else np.nan).to_numpy()
        seen = pd.Series(np.isfinite(s)).groupby(self.sd).cummax().to_numpy()
        return np.where(seen, first, np.nan)

    def prev_day(self, how: str) -> np.ndarray:
        """how (max/min/last) of the log close over the previous session of the SAME segment; NaN otherwise."""
        def make():
            s = pd.DataFrame({"seg": self.segid, "day": self.dayid, "lc": np.log(self.c)})
            per = s.groupby(["seg", "day"]).lc.agg(how).reset_index()
            per["prev"] = per.groupby("seg").lc.shift(1)
            per["prevday"] = per.groupby("seg").day.shift(1)
            per.loc[per.prevday != per.day - 1, "prev"] = np.nan        # the previous session must be the day before
            key = pd.MultiIndex.from_arrays([self.segid, self.dayid])
            return per.set_index(["seg", "day"]).prev.reindex(key).to_numpy()
        return self.memo(("prev_day", how), make)

    def tod_mean_prev(self, per_row: np.ndarray, k: int = 20, min_n: int = 10) -> np.ndarray:
        """Mean of a per-row quantity at the same minute of day over the previous k sessions (any segment): only
        earlier days are read."""
        df = pd.DataFrame({"d": self.dayid, "tod": self.tod, "v": per_row})
        piv = df.pivot_table(index="d", columns="tod", values="v", aggfunc="first")
        piv = piv.reindex(range(len(self.days)))
        mean = piv.shift(1).rolling(k, min_periods=min_n).mean()
        return mean.stack(future_stack=True).reindex(pd.MultiIndex.from_arrays([self.dayid, self.tod])).to_numpy()


def _ema(c: Ctx, span: int) -> np.ndarray:
    """EMA of the log close within the segment, truncated to the last 5 x span rows so the value never depends on
    how much history is loaded beyond that."""
    w = 5 * span
    alpha = 2 / (span + 1)
    wts = (1 - alpha) ** np.arange(w)          # weight of the row k rows back
    out = np.full(c.n, np.nan)
    for ix in pd.Series(np.arange(c.n)).groupby(c.segid).indices.values():
        x = c.lc[ix]
        ok = np.isfinite(x)
        num = np.convolve(np.where(ok, x, 0.0), wts)[:len(x)]
        den = np.convolve(ok.astype(float), wts)[:len(x)]
        cnt = np.convolve(ok.astype(float), np.ones(w))[:len(x)]
        with np.errstate(invalid="ignore", divide="ignore"):
            out[ix] = np.where((cnt >= span) & (den > 0), num / den, np.nan)
    return out


def ema(c: Ctx, span: int) -> np.ndarray:
    return c.memo(("ema", span), lambda: _ema(c, span))


# =============================================================================================== price
for k in (5, 15, 30, 60, 240):
    feature(f"ret_{k}m", "price", 1, ["minutes.close"], f"{k}m", f"log return over the last {k} minutes, %")(
        lambda c, k=k: c.ret(k))


@feature("ret_day", "price", 1, ["minutes.close"], "day", "log return since the session's first close, %")
def _ret_day(c):
    return (c.lc - c.day_first(c.lc)) * 100


@feature("gap_open", "price", 1, ["minutes.close"], "1d", "today's first close vs the previous session's last close "
         "(same contract segment), %")
def _gap(c):
    return (c.day_first(c.lc) - c.prev_day("last")) * 100


# =============================================================================================== vol
for k in (30, 60, 240):
    feature(f"rv_{k}m", "vol", 1, ["minutes.close"], f"{k}m", f"realised volatility of 1-minute returns over {k} "
            "minutes, % per sqrt(minute)")(lambda c, k=k: c.roll(c.r1, k, "std"))
    feature(f"range_{k}m", "vol", 1, ["minutes.close"], f"{k}m", f"close range (max - min close) of the last {k} "
            "minutes, % of the close")(lambda c, k=k: c.crange(k))


@feature("log_range_60m", "vol", 1, ["minutes.close"], "60m", "log of range_60m (floor 0.001%): B-last of L1-A")
def _lr60(c):
    return np.log(np.fmax(c.crange(60), 1e-3))


@feature("rv_ratio_60_240", "vol", 1, ["minutes.close"], "240m", "rv_60m / rv_240m (above 1: the last hour is busier)")
def _rvr(c):
    a, b = c.roll(c.r1, 60, "std"), c.roll(c.r1, 240, "std")
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(b > 0, a / b, np.nan)


@feature("range_day", "vol", 1, ["minutes.close"], "day", "the session's close range so far, % of the close")
def _rday(c):
    return (np.exp(c.day_cum(c.lc, "max") - c.day_cum(c.lc, "min")) - 1) * 100


def _fwd_logrange(c: Ctx, h: int) -> np.ndarray:
    """log close range of rows t+1..t+h of the same session (a per-row quantity of PAST days for tod_mean_prev)."""
    s = pd.Series(c.lc[::-1])
    g = pd.Series(c.sd[::-1])
    mx = s.groupby(g.values).rolling(h, min_periods=h).max().reset_index(level=0, drop=True).sort_index().to_numpy()[::-1]
    mn = s.groupby(g.values).rolling(h, min_periods=h).min().reset_index(level=0, drop=True).sort_index().to_numpy()[::-1]
    out = np.full(c.n, np.nan)
    same = np.r_[c.sd[1:] == c.sd[:-1], False]
    out[:-1] = np.where(same[:-1], (mx[1:] - mn[1:]) * 100, np.nan)
    return np.log(np.fmax(np.exp(out / 100) * 100 - 100, 1e-3))


@feature("tod_logrange_60m", "vol", 1, ["minutes.close"], "20d", "mean log close range of the 60 minutes after this "
         "clock time over the previous 20 sessions (B-tod of L1-A; reads only earlier days)")
def _tod60(c):
    return c.tod_mean_prev(_fwd_logrange(c, 60))


# =============================================================================================== trend
for span in (30, 120):
    feature(f"dist_ema{span}", "trend", 1, ["minutes.close"], f"{5 * span}m", f"log close minus its {span}-minute EMA "
            "(truncated), %")(lambda c, s=span: (c.lc - ema(c, s)) * 100)


@feature("ema60_slope_15m", "trend", 1, ["minutes.close"], "315m", "change of the 60-minute EMA over 15 minutes, %")
def _slope(c):
    e = ema(c, 60)
    return (e - c.shift(e, 15)) * 100


@feature("er_60m", "trend", 1, ["minutes.close"], "60m", "efficiency: |60-minute return| / sum of |1-minute returns|")
def _er(c):
    s = c.roll(np.abs(c.r1), 60, "sum")
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(s > 0, np.abs(c.ret(60)) / s, np.nan)


for k in (60, 240):
    @feature(f"pos_{k}m", "trend", 1, ["minutes.close"], f"{k}m", f"position of the close in the last {k} minutes' "
             "close range (0 low .. 1 high)")
    def _pos(c, k=k):
        hi, lo = c.roll(c.lc, k, "max"), c.roll(c.lc, k, "min")
        with np.errstate(invalid="ignore", divide="ignore"):
            return np.where(hi > lo, (c.lc - lo) / (hi - lo), 0.5)


@feature("dist_session_mean", "trend", 1, ["minutes.close"], "day", "log close minus the mean log close of the "
         "session so far, %")
def _dsm(c):
    return (c.lc - c.day_cum(c.lc, "mean")) * 100


# =============================================================================================== levels
@feature("dist_prev_high", "levels", 1, ["minutes.close"], "1d", "log close minus the previous session's highest close")
def _dph(c):
    return (c.lc - c.prev_day("max")) * 100


@feature("dist_prev_low", "levels", 1, ["minutes.close"], "1d", "log close minus the previous session's lowest close")
def _dpl(c):
    return (c.lc - c.prev_day("min")) * 100


@feature("dist_day_high", "levels", 1, ["minutes.close"], "day", "log close minus today's highest close so far, %")
def _ddh(c):
    return (c.lc - c.day_cum(c.lc, "max")) * 100


@feature("dist_day_low", "levels", 1, ["minutes.close"], "day", "log close minus today's lowest close so far, %")
def _ddl(c):
    return (c.lc - c.day_cum(c.lc, "min")) * 100


# =============================================================================================== session
@feature("min_since_open", "session", 1, ["minutes.ts"], "0m", "minutes since 09:00")
def _mso(c):
    return (c.tod - config.OPEN_MIN).astype(float)


@feature("min_to_close", "session", 1, ["minutes.ts"], "0m", "minutes to the session close (23:30 / 23:55)")
def _mtc(c):
    from research.data import mcx as L
    close = pd.Series(c.days).map(lambda d: L.close_min(pd.Timestamp(d))).to_numpy()
    return (close[c.dayid] - c.tod - 1).astype(float)


@feature("min_to_us_open", "session", 1, ["minutes.ts"], "0m", "minutes to the US cash open (09:30 New York; "
         "negative after it)")
def _mtu(c):
    from research.hunt.r83.common import et_to_ist_min
    uo = pd.Series(c.days).map(lambda d: et_to_ist_min(pd.Timestamp(d).date(), 9, 30)).to_numpy()
    return (uo[c.dayid] - c.tod - 1).astype(float)


@feature("tod_sin", "session", 1, ["minutes.ts"], "0m", "sin of the clock time (24 h cycle)")
def _ts(c):
    return np.sin(2 * np.pi * c.tod / 1440)


@feature("tod_cos", "session", 1, ["minutes.ts"], "0m", "cos of the clock time (24 h cycle)")
def _tc(c):
    return np.cos(2 * np.pi * c.tod / 1440)


# =============================================================================================== volume (future)
@feature("fvol_rel_30m", "volume", 1, ["minutes.fvol"], "5d", "futures volume of the last 30 minutes / its median "
         "30-minute volume over the last 5 x 870 rows (NaN before the lake has the near future)")
def _fvr(c):
    v = c.col("fvol")
    s = c.roll(v, 30, "sum")
    med = pd.Series(s).rolling(5 * 870, min_periods=870).median().to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(med > 0, s / med, np.nan)


@feature("foi_chg_60m", "volume", 1, ["minutes.foi"], "60m", "change of the future's open interest over 60 minutes, %")
def _foi(c):
    o = c.col("foi")
    p = c.shift(o, 60)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(p > 0, (o / p - 1) * 100, np.nan)


@feature("optvol_rel_30m", "volume", 1, ["minutes.opt_vol"], "5d", "option volume (ATM-3..+3) of the last 30 minutes / "
         "its median 30-minute volume over the last 5 x 870 rows")
def _ovr(c):
    s = c.roll(np.nan_to_num(c.col("opt_vol")), 30, "sum")
    s = np.where(np.isfinite(c.col("opt_vol")) | np.isfinite(c.shift(c.col("opt_vol"), 1)), s, np.nan)
    med = pd.Series(s).rolling(5 * 870, min_periods=870).median().to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(med > 0, s / med, np.nan)


# =============================================================================================== options (context)
@feature("atm_iv", "options", 1, ["minutes.atm_iv"], "15m", "ATM implied volatility (mean of call and put), % a year")
def _iv(c):
    return c.col("atm_iv")


@feature("iv_chg_60m", "options", 1, ["minutes.atm_iv"], "60m", "ATM IV change over 60 minutes, IV points")
def _ivc(c):
    v = c.col("atm_iv")
    return v - c.shift(v, 60)


@feature("iv_z_20d", "options", 1, ["minutes.atm_iv"], "20d", "ATM IV z-score over the last 20 x 870 rows")
def _ivz(c):
    v = pd.Series(c.col("atm_iv"))
    r = v.rolling(20 * 870, min_periods=870)
    m, s = r.mean().to_numpy(), r.std().to_numpy()
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(s > 0, (v.to_numpy() - m) / s, np.nan)


@feature("iv_range_60m", "options", 1, ["minutes.atm_iv"], "15m", "the 60-minute move the ATM IV prices: "
         "IV x sqrt(60 / 525,600), % (B-iv of L1-A)")
def _ivr(c):
    return c.col("atm_iv") * np.sqrt(60 / MIN_PER_YEAR)


@feature("rv_minus_iv", "options", 1, ["minutes.atm_iv", "minutes.close"], "60m", "log of the realised 60-minute "
         "volatility (annualised) over the ATM IV")
def _rvmiv(c):
    rv = c.roll(c.r1, 60, "std") * np.sqrt(MIN_PER_YEAR)
    iv = c.col("atm_iv")
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where((rv > 0) & (iv > 0), np.log(rv / iv), np.nan)


@feature("pcr_oi", "options", 1, ["minutes.pcr_oi"], "15m", "put OI / call OI over ATM-5..+5")
def _pcr(c):
    return c.col("pcr_oi")


@feature("straddle_pct", "options", 1, ["minutes.straddle", "minutes.close"], "15m", "ATM straddle premium, % of the "
         "future")
def _strad(c):
    with np.errstate(invalid="ignore", divide="ignore"):
        return c.col("straddle") / c.c * 100


# =============================================================================================== calendar
@feature("dow", "calendar", 1, ["minutes.ts"], "0m", "day of week (0 Monday)")
def _dow(c):
    return c.ts.dayofweek.to_numpy().astype(float)


@feature("dte", "calendar", 1, ["minutes.dte"], "0m", "calendar days to the next option expiry (published calendar)")
def _dte(c):
    return c.col("dte")


@feature("fdte", "calendar", 1, ["minutes.fdte"], "0m", "calendar days to the held future's expiry")
def _fdte(c):
    return c.col("fdte")


@feature("us_summer", "calendar", 1, ["minutes.ts"], "0m", "1 while the US is on summer time (MCX closes 23:30)")
def _uss(c):
    from research.data import mcx as L
    v = pd.Series(c.days).map(lambda d: float(L.us_summer(pd.Timestamp(d)))).to_numpy()
    return v[c.dayid]


# =============================================================================================== events
for k in ("eia", "storage", "cpi", "nfp", "fomc"):
    feature(f"ev_{k}", "events", 1, [f"minutes.ev_{k}"], "0m", f"minutes to today's {k.upper()} release (negative "
            "after it; NaN on days without one; from the published calendar)")(lambda c, k=k: c.col(f"ev_{k}"))


@feature("event_window", "events", 1, ["minutes.ev_*"], "0m", "1 from 30 minutes before to 60 minutes after any "
         "scheduled release of the symbol's kinds (events.FOR_SYMBOL)")
def _evw(c):
    from . import events
    kinds = events.FOR_SYMBOL.get(c.sym, events.DEFAULT)
    out = np.zeros(c.n)
    for k in kinds:
        v = c.col(f"ev_{k}")
        out = np.maximum(out, ((v <= 30) & (v >= -60)).astype(float))
    return out


# =============================================================================================== daily (earlier days only)
def _daily_frame(c: Ctx) -> pd.DataFrame | None:
    d = c.daily
    if d is None or not len(d):
        return None
    d = d.copy()
    d["date"] = pd.to_datetime(d["date"]).dt.normalize()
    return d.sort_values("date").drop_duplicates("date", keep="last").set_index("date")


def _daily_map(c: Ctx, series: pd.Series) -> np.ndarray:
    """Value of a daily series at the last daily bar strictly BEFORE each row's session day."""
    s = series.dropna()
    if not len(s):
        return np.full(c.n, np.nan)
    days = pd.DatetimeIndex(c.days)
    pos = np.searchsorted(s.index.values, days.values, side="left") - 1
    v = np.where(pos >= 0, s.to_numpy(float)[np.clip(pos, 0, None)], np.nan)
    stale = (days - s.index[np.clip(pos, 0, None)]).days > 7
    v[stale] = np.nan
    return v[c.dayid]


@feature("d_atr14_pct", "daily", 1, ["daily.high", "daily.low", "daily.close"], "15d", "ATR14 of the near-month "
         "daily bars before today, % of the last daily close")
def _datr(c):
    d = _daily_frame(c)
    if d is None:
        return np.full(c.n, np.nan)
    pc = d.close.shift(1)
    tr = np.maximum(d.high - d.low, np.maximum((d.high - pc).abs(), (d.low - pc).abs()))
    return _daily_map(c, tr.rolling(14, min_periods=14).mean() / d.close * 100)


@feature("d_range_prev_pct", "daily", 1, ["daily.high", "daily.low", "daily.close"], "1d", "the previous daily bar's "
         "high - low, % of its close")
def _drp(c):
    d = _daily_frame(c)
    if d is None:
        return np.full(c.n, np.nan)
    return _daily_map(c, (d.high - d.low) / d.close * 100)


@feature("d_ret_5d", "daily", 1, ["daily.close"], "6d", "log return of the near-month daily close over the 5 bars "
         "before today, % (contract rolls included as they print)")
def _dr5(c):
    d = _daily_frame(c)
    if d is None:
        return np.full(c.n, np.nan)
    return _daily_map(c, np.log(d.close / d.close.shift(5)) * 100)


pending("bidask_spread", "volume", "order-book history (Dhan keeps none)", "quoted MCX spread per minute")


# =============================================================================================== runner, catalogue
def signature(names=None) -> str:
    fs = sorted((REGISTRY[n].name, REGISTRY[n].version) for n in (names or REGISTRY))
    return hashlib.sha256(repr(("mcx", config.CUBE_VERSION, fs)).encode()).hexdigest()[:12]


def names() -> list[str]:
    return sorted(REGISTRY)


def compute(minutes: pd.DataFrame, sym: str, names_=None, daily: pd.DataFrame | None = None) -> pd.DataFrame:
    return india.run(Ctx(minutes, sym, daily), REGISTRY, names_)


def catalogue_md() -> str:
    head = ["# MCX feature catalogue", "",
            "*Generated by `python -m research.brain.mcx.features catalog` from `research/brain/mcx/features.py`. "
            "Do not edit by hand.*", "",
            f"{len(REGISTRY)} features, signature `{signature()}` (MCX cube v{config.CUBE_VERSION}). One row per MCX "
            "session minute. Every feature at minute t uses only cube rows with ts <= t, and daily bars of earlier "
            "days (checked by `research/brain/tests/test_mcx_brain.py` and `python -m research.brain.mcx.guard`). "
            "Prices are minute closes; windows never cross a contract segment. Labels are not features (`labels.py`).",
            ""]
    return india.render_catalogue(REGISTRY, PENDING, FAMILIES, head)


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.mcx.features")
    ap.add_argument("cmd", choices=["catalog", "build"])
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--symbols", nargs="+", default=list(config.SPECS))
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    if a.cmd == "catalog":
        md = catalogue_md() + "\n"
        if a.check:
            ok = CATALOG.exists() and CATALOG.read_text() == md
            print("MCX FEATURES.md " + ("up to date" if ok else "OUT OF DATE: run the catalog command"))
            return 0 if ok else 1
        CATALOG.write_text(md)
        print(f"wrote {CATALOG} ({len(REGISTRY)} features)")
        return 0
    from . import cube
    P = config.paths(a.cache)
    P.features.mkdir(parents=True, exist_ok=True)
    for s in a.symbols:
        m = cube.load(P.root, s)
        if not len(m):
            print(s, "no cube")
            continue
        dp = P.base / "daily" / f"{s}.parquet"
        daily = pd.read_parquet(dp) if dp.exists() else None
        f = compute(m, s, None, daily)
        f.to_parquet(P.features / f"{s}.parquet", compression="zstd", index=False)
        print(s, f.shape, "signature", signature())
    return 0


if __name__ == "__main__":
    sys.exit(main())
