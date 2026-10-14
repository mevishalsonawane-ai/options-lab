"""Crypto look-ahead guard: research/brain/guard.py's checks with the crypto cube's columns.

  check_features(M, sym, cuts)   every feature recomputed with the rows after t deleted, and with them perturbed:
                                 values at rows <= t must not change (brain.guard.check_features)
  check_cube(raw, dvol, g, cuts) the cube builder: rows <= t must not change when raw items that land after t (bars
                                 starting after t; funding, metrics and DVOL available after row t) are removed or
                                 perturbed

    python -m research.brain.crypto.guard --symbols BTCUSDT --days 40 --cuts 6     # on the real cube in the cache
"""
from __future__ import annotations

import argparse
import sys

import numpy as np
import pandas as pd

from .. import guard as india
from . import config, cube
from .features.registry import compute

PERTURB = dict(
    scale=["open", "high", "low", "close", "perp_open", "perp_high", "perp_low", "perp_close", "mark_close",
           "index_close", "basis"],
    noise=["basis_bps", "premium_close", "mark_premium_bps", "funding_rate", "funding_interval_h", "funding_age_min",
           "min_to_next_funding", "oi", "oi_value", "top_ls_accounts", "top_ls_positions", "ls_accounts",
           "taker_ls_vol", "metrics_age_min", "dvol", "dvol_age_min", "opt_atm_iv", "opt_skew", "opt_pc_24h",
           "opt_flow_24h", "opt_n_1h", "opt_age_min"],
    size=["volume", "quote_volume", "trades", "taker_buy_volume", "perp_volume", "perp_quote_volume", "perp_trades",
          "perp_taker_buy_volume"],
    flags=["flag", "perp_flag", "mark_flag", "index_flag", "premium_flag"],
)


def check_features(M: pd.DataFrame, sym: str, cuts, names=None, market: str = "crypto", seed: int = 7) -> dict:
    fn = lambda m, c, u, n: compute(m, u, n, market)          # noqa: E731
    return india.check_features(M, None, sym, cuts, names, seed, compute_fn=fn, perturb=PERTURB)


def _row_of(kind: str, d: pd.DataFrame) -> pd.DatetimeIndex:
    """The grid row each raw item lands on (cube.build_frame's placement)."""
    if kind == "dvol":
        return pd.DatetimeIndex(d.avail).floor("min")
    if kind == "metrics":
        return cube.metrics_avail(d.ts).floor("min")
    return pd.DatetimeIndex(d.ts).floor("min")


def _cut(raw: dict, dvol, t, rng=None):
    out = {}
    for k, d in list(raw.items()) + [("dvol", dvol)]:
        if d is None or not len(d):
            out[k] = d
            continue
        late = np.asarray(_row_of(k, d) > t)
        if rng is None:
            out[k] = d[~late].reset_index(drop=True)
        else:
            d = d.copy()
            for c in d.columns:
                if c not in ("ts", "avail") and d[c].dtype.kind == "f" and late.any():
                    d.loc[late, c] = d.loc[late, c].values * rng.uniform(0.5, 1.5, late.sum())
            out[k] = d
    dv = out.pop("dvol")
    return out, dv


def check_cube(raw: dict, dvol, g: pd.DatetimeIndex, cuts, seed: int = 11) -> list[tuple]:
    rng = np.random.default_rng(seed)
    full, _ = cube.build_frame(raw, dvol, g)
    bad = []
    for t in cuts:
        t = pd.Timestamp(t)
        for mode, (r2, d2) in (("deleted", _cut(raw, dvol, t)), ("perturbed", _cut(raw, dvol, t, rng))):
            m2, _ = cube.build_frame(r2, d2, g)
            a, b = full[full.ts <= t].reset_index(drop=True), m2[m2.ts <= t].reset_index(drop=True)
            for col in a.columns:
                if not india._same(a[col], b[col]):
                    bad.append((col, str(t), mode))
    return bad


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.guard")
    ap.add_argument("--symbols", nargs="+", default=["BTCUSDT"])
    ap.add_argument("--days", type=int, default=40, help="the last N built days (covers the 32-day warm-up)")
    ap.add_argument("--cuts", type=int, default=6)
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    root = config.paths(a.cache).root
    rc = 0
    for s in a.symbols:
        days = cube.built_days(root, s)
        if not days:
            print(f"{s}: no cube built")
            rc = 1
            continue
        M = cube.load(root, s, days[max(0, len(days) - a.days)], days[-1])
        cuts = india.default_cuts(M.ts[M.ts >= M.ts.iloc[-1] - pd.Timedelta(days=3)], a.cuts)
        bad = check_features(M, s, cuts)
        print(f"{s} look-ahead: {len(cuts)} cuts x 2 modes over {len(M) // 1440} days: "
              + ("PASS" if not bad else f"FAIL {sorted(bad)}"))
        rc |= bool(bad)
    return rc


if __name__ == "__main__":
    sys.exit(main())
