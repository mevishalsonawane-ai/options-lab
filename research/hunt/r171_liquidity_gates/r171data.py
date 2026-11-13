"""R171 data: the public `research-data` release turned into one record per index per session (PREREG.md section 0).

    R171_DATA=/path/to/release/files python research/hunt/r171_liquidity_gates/r171data.py build

Files (download with `gh api .../releases/assets/<id>` or research/data/fetch_release.py-style; never committed):
NIFTY nifty_prev.parquet + nifty_year.parquet; BANKNIFTY banknifty_prev_year_wide.parquet + banknifty_year_wide.parquet;
FINNIFTY / SENSEX finnifty_index.parquet / sensex_index.parquet (index only, R72's breadth count).

Grid: row r = the 1-minute bar that STARTS at 09:15 + r (375 rows). Nothing is filled: a minute without a bar is NaN
(its volume counts 0 in the gates). Every session on or after 2026-02-24 is DROPPED (docs/design.md:83, the data hole).
"""
from __future__ import annotations

import os
import pickle
import sys
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT, ROOT / "research", ROOT / "research" / "hunt" / "r51"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))

DATA = Path(os.environ.get("R171_DATA", Path.home() / ".cache" / "iraalgo" / "r171_release")).expanduser()
CACHE = Path(os.environ.get("R171_CACHE", Path.home() / ".cache" / "iraalgo" / "r171")).expanduser()

N = 375
O, H, L, C, V = range(5)
HOLE_START = date(2026, 2, 24)          # docs/design.md:83: the 2026-02-24 .. 2026-09-05 hole; nothing on or after is used
HOLE_END = date(2026, 9, 5)
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0}
FILES = {
    "NIFTY": ("nifty_prev.parquet", "nifty_year.parquet"),
    "BANKNIFTY": ("banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"),
    "FINNIFTY": ("finnifty_index.parquet",),
    "SENSEX": ("sensex_index.parquet",),
}
OPTION_UNDS = ("NIFTY", "BANKNIFTY")
INDEX_UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")


@dataclass
class Day:
    day: date
    und: str
    ix: np.ndarray                                  # (375, 4) index O H L C, NaN where missing
    exp: date | None = None                         # the stored options' expiry (one a day)
    opts: dict = field(default_factory=dict)        # (strike, 'C'|'P') -> (375, 5) O H L C V (V in units)
    lot: int = 0


def usable(d: date) -> bool:
    """False on and after the hole's start: no window may span 2026-02-24 .. 2026-09-05, and the release has nothing after
    it, so every later session is dropped."""
    return d < HOLE_START


def rows_of(ts: pd.Series) -> np.ndarray:
    return ((ts.dt.hour * 60 + ts.dt.minute) - (9 * 60 + 15)).to_numpy()


def _grid(g: pd.DataFrame, cols) -> np.ndarray:
    out = np.full((N, len(cols)), np.nan)
    r = rows_of(g.ts)
    ok = (r >= 0) & (r < N)
    out[r[ok]] = g.loc[ok, cols].to_numpy(dtype=float)
    return out


def read_index_file(path: Path) -> dict:
    df = pd.read_parquet(path)
    df = df[df.right.astype(str) == "IX"].copy()
    df["ts"] = pd.to_datetime(df.ts)
    out = {}
    for d, g in df.groupby(df.ts.dt.date):
        if usable(d):
            out[d] = _grid(g.sort_values("ts"), ["open", "high", "low", "close"])
    return out


def read_option_file(und: str, path: Path) -> list[Day]:
    import r51cfg as K
    df = pd.read_parquet(path)
    df["ts"] = pd.to_datetime(df.ts)
    df["d"] = df.ts.dt.date
    out = []
    for d, g in df.groupby("d"):
        if not usable(d):
            continue
        ix = g[g.right.astype(str) == "IX"].sort_values("ts")
        if len(ix) < 300:
            continue
        o = g[g.right.astype(str) != "IX"]
        exps = sorted({e.date() for e in pd.to_datetime(o.expiry.dropna().unique())})
        exps = [e for e in exps if e >= d]
        day = Day(day=d, und=und, ix=_grid(ix, ["open", "high", "low", "close"]), lot=K.lot(und, d))
        if exps:
            day.exp = exps[0]
            oo = o[pd.to_datetime(o.expiry).dt.date == day.exp]
            for (k, r), s in oo.groupby(["strike", "right"]):
                day.opts[(float(k), "C" if str(r) == "CE" else "P")] = _grid(
                    s.sort_values("ts"), ["open", "high", "low", "close", "volume"]).astype(np.float64)
        out.append(day)
    return out


def build() -> dict:
    CACHE.mkdir(parents=True, exist_ok=True)
    store = {}
    for und in OPTION_UNDS:
        days = []
        for f in FILES[und]:
            days += read_option_file(und, DATA / f)
        seen, uniq = set(), []
        for d in sorted(days, key=lambda x: x.day):
            if d.day not in seen:
                seen.add(d.day)
                uniq.append(d)
        store[und] = uniq
        print(und, len(uniq), uniq[0].day, uniq[-1].day, flush=True)
    for und in ("FINNIFTY", "SENSEX"):
        ix = {}
        for f in FILES[und]:
            ix.update(read_index_file(DATA / f))
        store[und] = [Day(day=d, und=und, ix=a) for d, a in sorted(ix.items())]
        print(und, len(store[und]), flush=True)
    with open(CACHE / "store.pkl", "wb") as fh:
        pickle.dump(store, fh, protocol=pickle.HIGHEST_PROTOCOL)
    return store


_STORE: dict = {}


def load() -> dict:
    if not _STORE:
        p = CACHE / "store.pkl"
        if not p.exists():
            build()
        with open(p, "rb") as fh:
            _STORE.update(pickle.load(fh))
    return _STORE


def expiry_days(days: list[Day]) -> set:
    """An index's expiry days, from the data: every stored expiry date (on an expiry day the release stores the NEXT
    expiry, so the day itself is found from the earlier sessions' expiry column)."""
    return {d.exp for d in days if d.exp is not None}


if __name__ == "__main__":
    if (sys.argv[1:] or [""])[0] == "build":
        import r171data                 # pickle the module's own Day class, not __main__.Day
        r171data.build()
