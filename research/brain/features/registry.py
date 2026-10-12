"""Feature definitions as code, and the point-in-time context they are computed on.

A feature is a function f(ctx) -> array aligned to ctx.m (one value per cube minute), registered with
@feature(name, family, version, inputs, lookback, description). The rule every feature obeys, and that
tests/test_lookahead.py checks for every registered feature: the value at minute t may use only cube rows with
ts <= t. Row t holds the bar that closes at t + 1 minute, so a feature at t is usable from t + 1 minute on.

Labels (future outcomes) live in research/brain/labels.py and never enter this registry.
"""
from __future__ import annotations

import hashlib
from dataclasses import dataclass, field
from typing import Callable

import numpy as np
import pandas as pd

from .. import config

FAMILIES = ("price", "structure", "volume", "profile", "levels", "indicators", "candles", "options", "market",
            "calendar", "breadth")


@dataclass(frozen=True)
class Feature:
    name: str
    family: str
    version: int
    inputs: tuple[str, ...]          # cube columns ("minutes.close", "chain.oi", ...) the feature reads
    lookback: str                    # how far back it reads: "15m", "1d" (previous session), "10d", "day" (since open)
    description: str
    fn: Callable = field(repr=False, compare=False)


REGISTRY: dict[str, Feature] = {}
PENDING: dict[str, dict] = {}       # features defined in the dictionary but not computable from any source yet


def pending(name: str, family: str, needs: str, description: str):
    """Register a dictionary field that has no data source yet. It is listed in FEATURES.md as "pending data" and is
    never computed: no column, no placeholder values."""
    assert family in FAMILIES, family
    if name in REGISTRY or name in PENDING:
        raise ValueError(f"duplicate feature {name}")
    PENDING[name] = dict(name=name, family=family, needs=needs, description=description)


def feature(name: str, family: str, version: int, inputs: list[str], lookback: str, description: str):
    assert family in FAMILIES, family
    assert not name.startswith("y_"), "y_ is reserved for labels"

    def deco(fn):
        if name in REGISTRY:
            raise ValueError(f"duplicate feature {name}")
        REGISTRY[name] = Feature(name, family, version, tuple(inputs), lookback, description, fn)
        return fn
    return deco


def signature(names=None) -> str:
    """Hash of the feature set (names + versions) and the cube version: a change triggers a rebuild."""
    from . import load_all
    load_all()
    fs = sorted((REGISTRY[n].name, REGISTRY[n].version) for n in (names or REGISTRY))
    h = hashlib.sha256(repr((config.CUBE_VERSION, fs)).encode()).hexdigest()[:12]
    return h


def warmup_sessions(names=None, chain_only: bool = False) -> int:
    """Sessions of history a feature set needs before the first day it is written for. chain_only: the same, counting
    only features that read the chain (the chain is ~30x the size of the minutes, so it is loaded for fewer days)."""
    from . import load_all
    load_all()
    w = 1
    for n in names or REGISTRY:
        f = REGISTRY[n]
        if chain_only and not any(i.startswith("chain.") for i in f.inputs):
            continue
        lb = f.lookback
        if lb.endswith("d") and lb[:-1].isdigit():
            w = max(w, int(lb[:-1]))
    return w


class Ctx:
    """Cube minutes (+ chain) of a run of sessions, sorted by time, with point-in-time helpers.

    Index prices on rows flagged gap/suspect are NaN here (stale rows keep their repeated value).
    """

    def __init__(self, minutes: pd.DataFrame, chain: pd.DataFrame | None, und: str):
        m = minutes.sort_values("ts").reset_index(drop=True)
        self.und = und
        self.m = m
        self.n = len(m)
        self.ts = pd.DatetimeIndex(m.ts)
        self.day = m.day.values
        self.minute = m.minute.values.astype(int)
        codes, self.days = pd.factorize(m.day, sort=True)
        self.dayid = codes
        good = m.flag.isin(["ok", "stale"]).values
        self.o = np.where(good, m.open.values, np.nan)
        self.h = np.where(good, m.high.values, np.nan)
        self.l = np.where(good, m.low.values, np.nan)
        self.c = np.where(good, m.close.values, np.nan)
        self.chain = chain if chain is not None else pd.DataFrame()
        self._memo: dict = {}

    # --- near-month future (the index's volume; see cube) ---
    def fut(self) -> dict:
        """Futures OHLCV on rows flagged ok/stale (NaN otherwise); volume NaN where the source has none."""
        def make():
            m = self.m
            good = m.fut_flag.isin(["ok", "stale"]).values if "fut_flag" in m else np.zeros(self.n, bool)
            out = {}
            for k, c in (("o", "fut_open"), ("h", "fut_high"), ("l", "fut_low"), ("c", "fut_close"), ("v", "fut_volume")):
                out[k] = np.where(good, m[c].values.astype(float), np.nan) if c in m else np.full(self.n, np.nan)
            return out
        return self.memo("fut", make)

    def memo(self, key, fn):
        if key not in self._memo:
            self._memo[key] = fn()
        return self._memo[key]

    # --- within-day helpers (never reach into the next session, never into the future) ---
    def shift_in_day(self, a: np.ndarray, k: int) -> np.ndarray:
        """a[t - k] if that row is in the same session, else NaN."""
        out = np.full(self.n, np.nan)
        if k < self.n:
            same = self.dayid[k:] == self.dayid[:-k] if k else np.ones(self.n, bool)
            out[k:] = np.where(same, a[:self.n - k], np.nan)
        return out

    def groupby_day(self, a: np.ndarray):
        return pd.Series(a).groupby(self.dayid)

    def day_first(self, a: np.ndarray) -> np.ndarray:
        """The first finite value of the session so far, known only from the row it appears on."""
        s = pd.Series(a)
        first = s.groupby(self.dayid).transform(lambda x: x.dropna().iloc[0] if x.notna().any() else np.nan).values
        seen = self.groupby_day(np.isfinite(a).astype(float)).cummax().values > 0
        return np.where(seen, first, np.nan)

    def prev_session(self, per_day: pd.Series) -> np.ndarray:
        """Map a per-session value (indexed by dayid) to each row of the NEXT session (previous session's value)."""
        v = per_day.reindex(range(len(self.days))).values
        prev = np.r_[np.nan, v[:-1]]
        return prev[self.dayid]

    def day_complete_agg(self, a: np.ndarray, how: str) -> pd.Series:
        """Per-session aggregate; use only through prev_session() (a session's own value is known at its close)."""
        return getattr(pd.Series(a).groupby(self.dayid), how)()

    # --- chain as arrays ---
    def chain_arr(self, col: str, side: str) -> np.ndarray:
        """n x 21 array of a chain column for one side, offsets -10..+10 (NaN where the chain has no row)."""
        def make():
            ch = self.chain
            out = np.full((self.n, len(config.CHAIN_OFFSETS)), np.nan)
            if ch.empty:
                return out
            s = ch[ch.side == side]
            pos = self.ts.get_indexer(pd.DatetimeIndex(s.ts))
            ok = pos >= 0
            off = s.offset.values.astype(int) - config.CHAIN_OFFSETS.start
            out[pos[ok], off[ok]] = s[col].values[ok].astype(float)
            return out
        return self.memo(("chain", col, side), make)

    def chain_ok(self, side: str) -> np.ndarray:
        def make():
            ch = self.chain
            out = np.zeros((self.n, len(config.CHAIN_OFFSETS)), bool)
            if ch.empty:
                return out
            s = ch[ch.side == side]
            pos = self.ts.get_indexer(pd.DatetimeIndex(s.ts))
            ok = pos >= 0
            off = s.offset.values.astype(int) - config.CHAIN_OFFSETS.start
            out[pos[ok], off[ok]] = (s.flag.values == "ok")[ok]
            return out
        return self.memo(("chain_ok", side), make)


def compute(minutes: pd.DataFrame, chain: pd.DataFrame | None, und: str, names=None) -> pd.DataFrame:
    """All (or the named) features for every cube minute given. Returns ts + one column per feature."""
    from . import load_all
    load_all()
    ctx = Ctx(minutes, chain, und)
    out = {"ts": ctx.ts}
    for n in names or sorted(REGISTRY):
        v = np.asarray(REGISTRY[n].fn(ctx), dtype=float)
        if v.shape != (ctx.n,):
            raise ValueError(f"{n}: shape {v.shape}, expected ({ctx.n},)")
        out[n] = v
    return pd.DataFrame(out)


def catalogue_md() -> str:
    from . import load_all
    load_all()
    lines = ["# Feature catalogue", "",
             "*Generated by `python -m research.brain.features catalog` from the definitions in "
             "`research/brain/features/`. Do not edit by hand.*", "",
             f"{len(REGISTRY)} features, signature `{signature()}` (cube v{config.CUBE_VERSION}). Every feature at minute "
             "t uses only cube rows with ts <= t (checked by `tests/test_lookahead.py`). Labels are not features: see "
             "`labels.py`.", ""]
    for fam in FAMILIES:
        fs = sorted((f for f in REGISTRY.values() if f.family == fam), key=lambda f: f.name)
        if not fs:
            continue
        lines += [f"## {fam.capitalize()} ({len(fs)})", "", "| name | v | lookback | inputs | description |",
                  "|---|---|---|---|---|"]
        for f in fs:
            lines.append(f"| `{f.name}` | {f.version} | {f.lookback} | {', '.join(f.inputs)} | {f.description} |")
        lines.append("")
    if PENDING:
        lines += [f"## Pending data ({len(PENDING)})", "",
                  "Defined in the data dictionary but **not computed**: no source in the adapters has the data yet. "
                  "They have no column and no values until an adapter provides the input.", "",
                  "| name | family | needs | description |", "|---|---|---|---|"]
        for p in sorted(PENDING.values(), key=lambda p: (p["family"], p["name"])):
            lines.append(f"| `{p['name']}` | {p['family']} | {p['needs']} | {p['description']} |")
        lines.append("")
    return "\n".join(lines)
