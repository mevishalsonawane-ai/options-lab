"""Crypto feature definitions and the 24x7 point-in-time context they run on.

The same machinery as the Indian store (research/brain/features/registry.py: the Feature record, the @feature
decorator, run() and the catalogue renderer), with its own registry so the two catalogues and signatures never mix.
The rule is the same and tests/test_crypto_features.py checks it for every feature: the value at minute t may use only
cube rows with ts <= t. Row t holds the bar that closes at t + 1 minute, so a feature at t is usable from t + 1 on.

Unlike NSE there are no sessions: windows ("the last 60 minutes", "the last 7 days") run straight across midnight and
weekends. Day-based features (previous day's high, day of week) use the market's analysis day (markets.py: UTC
midnight, or IST midnight with --market crypto-ist).
"""
from __future__ import annotations

import hashlib
import re

import numpy as np
import pandas as pd

from ...features import registry as india
from ...markets import MARKETS
from .. import config

FAMILIES = ("price", "trend", "vol", "volume", "profile", "levels", "derivs", "options", "calendar")
REGISTRY: dict[str, india.Feature] = {}
PENDING: dict[str, dict] = {}


def feature(name, family, version, inputs, lookback, description):
    return india.feature(name, family, version, inputs, lookback, description, registry=REGISTRY, families=FAMILIES)


def pending(name, family, needs, description):
    return india.pending(name, family, needs, description, registry=REGISTRY, pending_=PENDING, families=FAMILIES)


def lookback_minutes(lb: str) -> int:
    m = re.fullmatch(r"(\d+)([md])", lb)
    if not m:
        return 1440 if lb == "day" else 0
    return int(m[1]) * (1 if m[2] == "m" else 1440)


def warmup_days(names=None) -> int:
    """Whole days of cube needed before the first day written (the longest declared lookback, plus one)."""
    from . import load_all
    load_all()
    w = max([lookback_minutes(REGISTRY[n].lookback) for n in (names or REGISTRY)] + [0])
    return int(np.ceil(w / 1440)) + 1


def signature(names=None) -> str:
    from . import load_all
    load_all()
    fs = sorted((REGISTRY[n].name, REGISTRY[n].version) for n in (names or REGISTRY))
    return hashlib.sha256(repr(("crypto", config.CUBE_VERSION, fs)).encode()).hexdigest()[:12]


GOOD = ("ok", "stale")


class Ctx:
    """Cube minutes of one symbol on a gap-free 1-minute grid (missing rows added as gaps), with point-in-time helpers.
    Spot prices on rows flagged gap/suspect are NaN here; stale rows keep their (repeated) value and zero volume."""

    def __init__(self, minutes: pd.DataFrame, sym: str, market: str = "crypto"):
        m = minutes.sort_values("ts").drop_duplicates("ts").reset_index(drop=True)
        if len(m):
            g = pd.date_range(m.ts.iloc[0], m.ts.iloc[-1], freq="min")
            if len(g) != len(m):
                m = m.set_index("ts").reindex(g).rename_axis("ts").reset_index()
                for c in [c for c in m.columns if c.endswith("flag")]:
                    m[c] = m[c].fillna("gap")
        self.m, self.sym, self.n = m, sym, len(m)
        self.spec = config.SPECS.get(sym)
        self.market = MARKETS[market]
        self.ts = pd.DatetimeIndex(m.ts)
        good = m.flag.isin(GOOD).values if "flag" in m else np.zeros(self.n, bool)
        self.o, self.h, self.l, self.c = (np.where(good, m[k].to_numpy(float), np.nan) for k in ("open", "high", "low", "close"))
        self.v = np.where(good, m.volume.to_numpy(float), np.nan)
        pg = m.perp_flag.isin(GOOD).values if "perp_flag" in m else np.zeros(self.n, bool)
        self.pc = np.where(pg, m.perp_close.to_numpy(float), np.nan) if "perp_close" in m else np.full(self.n, np.nan)
        self.aday = self.market.analysis_day(self.ts)
        self.dayid, self.days = pd.factorize(self.aday, sort=True)
        self._memo: dict = {}

    def col(self, name: str) -> np.ndarray:
        return self.m[name].to_numpy(float) if name in self.m else np.full(self.n, np.nan)

    def memo(self, key, fn):
        if key not in self._memo:
            self._memo[key] = fn()
        return self._memo[key]

    # --- windows over the last k minutes (rows <= t only) ---
    def shift(self, a: np.ndarray, k: int) -> np.ndarray:
        """a[t - k] (NaN for the first k rows)."""
        out = np.full(self.n, np.nan)
        if 0 < k < self.n:
            out[k:] = np.asarray(a, float)[:self.n - k]
        elif k == 0:
            out[:] = a
        return out

    def roll(self, a: np.ndarray, k: int, how: str, min_frac: float = 0.8) -> np.ndarray:
        """how(a[t-k+1 .. t]) with at least min_frac of the window finite; NaN otherwise."""
        r = pd.Series(np.asarray(a, float)).rolling(k, min_periods=max(1, int(k * min_frac)))
        return getattr(r, how)().to_numpy()

    # --- analysis-day helpers ---
    def day_cum(self, a: np.ndarray, how: str) -> np.ndarray:
        """Running how (sum/max/min) of a within the analysis day, up to and including t (NaN ignored)."""
        s = pd.Series(np.asarray(a, float))
        g = s.groupby(self.dayid)
        if how == "sum":
            return s.fillna(0).groupby(self.dayid).cumsum().to_numpy()
        return getattr(g, "cum" + how)().to_numpy()

    def day_first(self, a: np.ndarray) -> np.ndarray:
        s = pd.Series(np.asarray(a, float))
        seen = pd.Series(np.isfinite(s)).groupby(self.dayid).cummax().to_numpy()
        first = s.groupby(self.dayid).transform(lambda x: x.dropna().iloc[0] if x.notna().any() else np.nan).to_numpy()
        return np.where(seen, first, np.nan)

    def prev_days(self, a: np.ndarray, how: str, k: int = 1, complete: float = 0.9) -> np.ndarray:
        """how() (max, min, sum, mean, last) of a over the k calendar analysis days before t's day; NaN unless each
        of those days has >= `complete` of its 1440 spot closes. A day's value is used only on later days, so it is
        known."""
        s = pd.Series(np.asarray(a, float))
        per = getattr(s.groupby(self.dayid), how)()
        cnt = pd.Series(np.isfinite(self.c)).groupby(self.dayid).sum()
        per[cnt < complete * 1440] = np.nan
        dates = pd.DatetimeIndex(self.days)
        per.index = dates
        full = per.reindex(pd.date_range(dates.min(), dates.max(), freq="D")) if len(dates) else per
        if k == 1:
            agg = full.shift(1)
        else:
            agg = getattr(full.shift(1).rolling(k, min_periods=k), "sum" if how == "sum" else how)()
        return agg.reindex(dates).to_numpy()[self.dayid]


def compute(minutes: pd.DataFrame, sym: str, names=None, market: str = "crypto") -> pd.DataFrame:
    from . import load_all
    load_all()
    return india.run(Ctx(minutes, sym, market), REGISTRY, names)


def catalogue_md() -> str:
    from . import load_all
    load_all()
    head = ["# Crypto feature catalogue", "",
            "*Generated by `python -m research.brain.crypto.features catalog` from the definitions in "
            "`research/brain/crypto/features/`. Do not edit by hand.*", "",
            f"{len(REGISTRY)} features, signature `{signature()}` (crypto cube v{config.CUBE_VERSION}). One row per UTC "
            "minute, 24x7. Every feature at minute t uses only cube rows with ts <= t (checked by "
            "`research/brain/tests/test_crypto_features.py`). Labels are not features: see `crypto/labels.py`. "
            "Lookback `60m` = the last 60 minutes, `7d` = the last 7 days (across midnight and weekends).", ""]
    return india.render_catalogue(REGISTRY, PENDING, FAMILIES, head)
