"""Crypto labels (targets): what happens AFTER minute t. research/brain/labels.compute, unchanged, with crypto horizons
and no session: the windows run across midnight and weekends (one constant "day").

    python -m research.brain.crypto.labels build --symbols BTCUSDT ETHUSDT

-> <cache>/crypto/labels/<SYM>/<YYYY>/<day>.parquet: y_ret_{60,240,1440}m (log %, spot close t -> t+h), y_range_*,
y_mfe_*, y_mae_* over t+1 .. t+h (NaN when any of the window is missing at the end of the data). Joined to features
only through research/brain/split.join_forward. A design row whose window reaches the holdout is dropped by
holdout.design().
"""
from __future__ import annotations

import argparse
import sys
from datetime import date, timedelta
from pathlib import Path

import pandas as pd

from .. import labels as india
from . import config, cube

HORIZONS = (60, 240, 1440)
MAX_HORIZON_MIN = max(HORIZONS)


def compute(minutes: pd.DataFrame, horizons=HORIZONS) -> pd.DataFrame:
    m = minutes[["ts", "open", "high", "low", "close", "flag"]].sort_values("ts").drop_duplicates("ts")
    out_ts = m.ts.values
    if len(m):
        # labels.compute works by row position, so a missing day (cube.build skips days with no source) would shift
        # every window next to it: put the rows back on the full minute grid first, the hole as gaps
        g = pd.date_range(m.ts.iloc[0], m.ts.iloc[-1], freq="min")
        m = m.set_index("ts").reindex(g).rename_axis("ts").reset_index()
        m["flag"] = m.flag.fillna("gap")
    m["day"] = 0                                       # 24x7: one continuous "session"
    L = india.compute(m, horizons)
    return L[L.ts.isin(out_ts)].reset_index(drop=True)


def _path(root: Path, sym: str, d: date) -> Path:
    return config.paths(root).labels(sym) / str(d.year) / f"{d}.parquet"


def build(root: Path, sym: str, frm: date, to: date) -> int:
    days = [d for d in cube.built_days(root, sym) if frm <= d <= to]
    n = 0
    by_month: dict = {}
    for d in days:
        by_month.setdefault((d.year, d.month), []).append(d)
    for _, ds in sorted(by_month.items()):
        pm = [cube.day_paths(root, sym, d)[0] for d in ds]
        if all(_path(root, sym, d).exists() and _path(root, sym, d).stat().st_mtime >= p.stat().st_mtime
               for d, p in zip(ds, pm)):
            nxt = cube.day_paths(root, sym, ds[-1] + timedelta(days=1))[0]
            if not nxt.exists() or nxt.stat().st_mtime <= _path(root, sym, ds[-1]).stat().st_mtime:
                continue
        M = cube.load(root, sym, ds[0], ds[-1] + timedelta(days=2))     # + the windows' future
        L = compute(M)
        L["d"] = L.ts.dt.date
        for d in ds:
            p = _path(root, sym, d)
            p.parent.mkdir(parents=True, exist_ok=True)
            L[L.d == d].drop(columns="d").to_parquet(p, compression="zstd", index=False)
            n += 1
    print(f"{sym}: labels for {n} days written")
    return n


def load(root: Path, sym: str, frm: date | None = None, to: date | None = None) -> pd.DataFrame:
    base = config.paths(root).labels(sym)
    files = sorted(base.glob("*/*.parquet")) if base.exists() else []
    keep = [p for p in files if (frm is None or date.fromisoformat(p.stem) >= frm)
            and (to is None or date.fromisoformat(p.stem) <= to)]
    return pd.concat([pd.read_parquet(p) for p in keep], ignore_index=True) if keep else pd.DataFrame()


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.labels")
    ap.add_argument("cmd", choices=["build"])
    ap.add_argument("--symbols", nargs="+", default=["BTCUSDT", "ETHUSDT"])
    ap.add_argument("--from", dest="frm", default="2017-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    for s in a.symbols:
        build(config.paths(a.cache).root, s, date.fromisoformat(a.frm), date.fromisoformat(a.to))
    return 0


if __name__ == "__main__":
    sys.exit(main())
