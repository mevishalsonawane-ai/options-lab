"""MCX look-ahead guard (MB1): research/brain/guard.py's checks with the MCX cube's columns.

  check_features(M, sym, cuts, daily)   every feature recomputed with the cube rows after t deleted (and the daily bars
                                        from t's day on dropped) and with those rows perturbed: values at rows <= t
                                        must not change
  check_cube(spot, fut, sym, exp, cuts) the cube builder: rows <= t must not change when source rows after t are
                                        removed or perturbed

    python -m research.brain.mcx.guard --symbols GOLDM CRUDEOIL --days 30 --cuts 6     # on the real cube
"""
from __future__ import annotations

import argparse
import sys

import numpy as np
import pandas as pd

from .. import guard as india
from . import config, cube
from .features import compute

PERTURB = dict(
    scale=["close", "fo", "fh", "fl", "fc", "straddle"],
    noise=["atm_iv", "pcr_oi", "opt_age"],
    size=["fvol", "foi", "opt_vol"],
    flags=["flag"],
)


def _daily_before(daily, t):
    if daily is None or not len(daily):
        return daily
    return daily[pd.to_datetime(daily["date"]) < pd.Timestamp(t).normalize()]


def check_features(M: pd.DataFrame, sym: str, cuts, daily=None, names=None, seed: int = 7) -> dict:
    def fn(m, c, u, n):
        last = m.ts.max()
        d = daily if last >= M.ts.max() else _daily_before(daily, last)
        return compute(m, sym, n, d)
    return india.check_features(M, None, sym, cuts, names, seed, compute_fn=fn, perturb=PERTURB)


def _cut(df: pd.DataFrame, t, rng=None):
    if df is None or not len(df):
        return df
    late = (pd.to_datetime(df.ts) > t).to_numpy()
    if rng is None:
        return df[~late].reset_index(drop=True)
    df = df.copy()
    for c in df.columns:
        if c != "ts" and df[c].dtype.kind == "f" and late.any():
            v = df[c].to_numpy(np.float64).copy()
            v[late] = v[late] * rng.uniform(0.5, 1.5, late.sum())
            df[c] = v
    return df


def check_cube(spot, fut, sym: str, expiries, cuts, seed: int = 11) -> list[tuple]:
    rng = np.random.default_rng(seed)
    full = cube.build_frame(spot, fut, sym, expiries)
    days = sorted(set(full.ts.dt.date))
    from . import events
    cal = events.calendar(days[0], days[-1])
    full = cube.build_frame(spot, fut, sym, expiries, cal, days)
    bad = []
    for t in cuts:
        t = pd.Timestamp(t)
        for mode, r in (("deleted", None), ("perturbed", rng)):
            m2 = cube.build_frame(_cut(spot, t, r), _cut(fut, t, r), sym, expiries, cal, days)
            a, b = full[full.ts <= t].reset_index(drop=True), m2[m2.ts <= t].reset_index(drop=True)
            for col in a.columns:
                if not india._same(a[col], b[col]):
                    bad.append((col, str(t), mode))
    return bad


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.mcx.guard")
    ap.add_argument("--symbols", nargs="+", default=["GOLDM", "CRUDEOIL"])
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--cuts", type=int, default=6)
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    P = config.paths(a.cache)
    rc = 0
    for s in a.symbols:
        M = cube.load(P.root, s)
        if not len(M):
            print(s, "no cube")
            rc = 1
            continue
        days = sorted(set(M.day))
        M = M[M.day >= days[max(0, len(days) - a.days)]].reset_index(drop=True)
        dp = P.base / "daily" / f"{s}.parquet"
        daily = pd.read_parquet(dp) if dp.exists() else None
        cuts = india.default_cuts(M.ts[M.ts >= M.ts.iloc[-1] - pd.Timedelta(days=4)], a.cuts)
        bad = check_features(M, s, cuts, daily)
        print(f"{s} look-ahead: {len(cuts)} cuts x 2 modes over {M.day.nunique()} sessions: "
              + ("PASS" if not bad else f"FAIL {sorted(bad)}"))
        rc |= bool(bad)
    return rc


if __name__ == "__main__":
    sys.exit(main())
