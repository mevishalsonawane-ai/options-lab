"""R56 data: one record per NIFTY session from 20 Nov 2024, built straight from the lake's Parquet.

    python3 research/hunt/r56/r56data.py build      # writes <R56_OUT>/sess_NIFTY.pkl
    python3 research/hunt/r56/r56data.py summary    # sessions, expiry days, lots, dropped rows (no trade prices)

The lake's options (Dhan "rollingoption", opt_NIFTY_WEEK_<YEAR>) hold, at each minute, the contracts that were
ATM-10 .. ATM+10 of the NEAREST expiry on or after the day. Within one session every row is that one expiry, so a
(strike, side) key is ONE contract: a trade holds that contract from entry to exit, never the rolling ATM series (R45).
All stored strikes are kept (R49 kept only 4 strikes around the 09:20 ATM; Boss's strike is the ATM at the signal,
which can be far from 09:20).

Clean-up, both from R47 (measurement fixes that can only lower results):
- foreign rows (R47 amendment 2): an option row whose own `spot` is more than 3% away from the NIFTY close of that
  minute (or the last close before it that session) belongs to another underlying and is dropped (counted);
- stray prints (R47 amendment 1): applied in the engine's fills and target check (r56engine).
research/data/spikes.py (the Data Brain's spike flags) is not on main on 15 Oct 2026, so no spike flag is applied.

Lot: the day's contract lot read from the traded volumes (r49data.lot_from_volume); the repo's lot table
(research/obuy/config.py LOT_SCHEDULE) is the fallback. Expiry day: R49's rule (R3's next-day time-value jump, or the
day's own 15:15 ATM time value under 0.15% of spot).
"""
from __future__ import annotations

import pickle
import sys
from collections import Counter

import numpy as np
import pandas as pd

import r56cfg as K
import r3data as D3
import r49data as D49
from r3data import N, Sess

def read_index() -> dict:
    parts = [D3._read(p) for p in sorted(K.LAKE.glob(f"index_1m_{K.UND}_*.parquet"))]
    if not parts:
        return {}
    df = pd.concat(parts, ignore_index=True).drop_duplicates(["d", "m"], keep="first")
    out = {}
    for d, g in df.groupby("d", sort=True):
        if d < K.FIRST_DAY:
            continue
        a = np.full((N, 4), np.nan)
        a[g.m.values] = g[["open", "high", "low", "close"]].values.astype(float)
        out[d] = a
    return out


def foreign_mask(spot: np.ndarray, ref: np.ndarray, tol: float = K.FOREIGN_TOL) -> np.ndarray:
    """True where an option row's own spot is more than tol away from the index reference close (NaN ref = foreign)."""
    with np.errstate(all="ignore"):
        r = np.asarray(spot, dtype=np.float64) / np.asarray(ref, dtype=np.float64)
    return ~(np.isfinite(r) & (np.abs(r - 1.0) <= tol))


def index_ref(ix: np.ndarray) -> np.ndarray:
    """The index close of each minute, or the last close before it that session (NaN before the first)."""
    return pd.Series(ix[:, 3]).ffill().to_numpy()


def read_options(ixd: dict) -> tuple[pd.DataFrame, dict]:
    frames, stats = [], Counter()
    for p in sorted(K.LAKE.glob(f"opt_{K.UND}_WEEK_*.parquet")):
        frames.append(D3._read(p).drop(columns=["iv"], errors="ignore"))
    df = pd.concat(frames, ignore_index=True)
    df = df[df.d >= K.FIRST_DAY]
    df["side"] = df.side.astype(str).str[0].str.upper()
    stats["rows"] = len(df)
    df = df[df.d.isin(set(ixd))]
    stats["rows_on_index_days"] = len(df)
    refs = np.full(len(df), np.nan)
    for d, idx in df.groupby("d").indices.items():
        refs[idx] = index_ref(ixd[d])[df.m.values[idx]]
    bad = foreign_mask(df.spot.values, refs)
    stats["foreign_dropped"] = int(bad.sum())
    df = df[~bad]
    n0 = len(df)
    df = df.drop_duplicates(["d", "m", "strike", "side"], keep="first")
    stats["duplicates_dropped"] = n0 - len(df)
    return df, dict(stats)


def build() -> tuple[list[Sess], dict]:
    ixd = read_index()
    o, stats = read_options(ixd)
    tv = D3._tv_1515(o)
    tv = tv[~tv.index.duplicated()].sort_index()
    exp = D3.expiry_days(tv)
    out = []
    for d, g in o.groupby("d", sort=True):
        opts = {}
        for (k, s), h in g.groupby(["strike", "side"]):
            a = np.full((N, 6), np.nan, dtype=np.float32)
            a[h.m.values] = h[["open", "high", "low", "close", "volume", "oi"]].values.astype(np.float32)
            opts[(float(k), s)] = a
        s = Sess(d, K.UND, ixd[d], np.full(N, np.nan), opts, None, d in exp, 0)
        lot = D49.lot_from_volume(opts, K.KNOWN_LOTS)
        s.lot = lot or K.lot_table(d)
        s.lot_src = "data" if lot else "table"
        t = D49.tv_1515(s)
        if np.isfinite(t) and t < D49.TV_LOW:
            s.expiry = True
        out.append(s)
    stats["index_days_without_options"] = len(set(ixd) - {s.day for s in out})
    return out, stats


def path():
    return K.OUT / f"sess_{K.UND}.pkl"


def load() -> list[Sess]:
    return pickle.loads(path().read_bytes())[0]


def summary():
    ss, stats = pickle.loads(path().read_bytes())
    print("sessions", len(ss), ss[0].day, ss[-1].day, "design", sum(K.period(s.day) == "design" for s in ss),
          "recent", sum(K.period(s.day) == "recent" for s in ss))
    print("expiry days", sum(s.expiry for s in ss), "by weekday",
          dict(Counter(s.day.strftime("%a") for s in ss if s.expiry)),
          "design", sum(s.expiry for s in ss if K.period(s.day) == "design"),
          "recent", sum(s.expiry for s in ss if K.period(s.day) == "recent"))
    lots = {}
    for s in ss:
        lots.setdefault(s.lot, [s.day, s.day])[1] = s.day
    print("lots", {k: (str(a), str(b)) for k, (a, b) in lots.items()},
          "from data", sum(getattr(s, "lot_src", "") == "data" for s in ss),
          "differs from table", sum(s.lot != K.lot_table(s.day) for s in ss))
    rng = sum(np.isfinite(s.ix[K.RANGE_ROWS - 1, 3]) for s in ss)
    print("sessions with the 09:29 bar", rng, "| stats", stats)


if __name__ == "__main__":
    import r56data as _self      # pickle Sess under its module name, not __main__
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        ss, st = _self.build()
        path().write_bytes(pickle.dumps((ss, st)))
        print(len(ss), ss[0].day, ss[-1].day, "expiry days", sum(s.expiry for s in ss), st, flush=True)
    elif sys.argv[1:2] == ["summary"]:
        summary()
