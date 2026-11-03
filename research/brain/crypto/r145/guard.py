"""R145 look-ahead guard: brain.guard's check for a cross-section of coins.

For each cut time t, every R145 feature is recomputed (a) with every bar known after t deleted (the grid truncated at t)
and funding settled after t removed, and (b) with those bars and funding values perturbed. Every feature value at a
decision time <= t must be identical to the full build (brain.guard._same). Labels are checked the other way in the
tests: they must change when the future is perturbed, and must not read any bar at or before the decision bar except
the entry close.

    python -m research.brain.crypto.r145.guard --days 30 --cuts 6     # on the real design panel in the cache
"""
from __future__ import annotations

import argparse
import sys

import numpy as np
import pandas as pd

from ...guard import _same
from . import panel as pn

SCALE = ["open", "high", "low", "close"]
SIZE = ["volume", "qv", "trades", "tbqv", "rv"]


def _cut(P: dict, fund: dict | None, t: pd.Timestamp, rng=None):
    known = P["close"].index + pd.Timedelta(minutes=pn.BAR)
    late = known > t
    if rng is None:
        P2 = {f: d[~late] for f, d in P.items()}
        F2 = None if fund is None else {s: f[f.ts <= t] for s, f in fund.items()}
        return P2, F2
    P2 = {}
    k = int(late.sum())
    scale = rng.uniform(0.5, 1.5, size=(k, P["close"].shape[1]))
    for f, d in P.items():
        d = d.copy()
        if f in SCALE:
            d.iloc[np.flatnonzero(late)] = d.values[late] * scale
        elif f in SIZE:
            d.iloc[np.flatnonzero(late)] = d.values[late] * rng.uniform(0.1, 3.0, size=(k, d.shape[1]))
        P2[f] = d
    F2 = None
    if fund is not None:
        F2 = {}
        for s, f in fund.items():
            f = f.copy()
            m = (f.ts > t).to_numpy()
            f.loc[m, "rate"] = rng.normal(0, 1e-3, m.sum())
            F2[s] = f
    return P2, F2


def check(P: dict, fund: dict | None, cuts, seed: int = 7) -> list[tuple]:
    """Returns [(feature, cut, mode)] for every feature that changed at a decision time <= the cut. Empty = pass."""
    rng = np.random.default_rng(seed)
    full = pn.features(P, fund)
    names = pn.feature_names(full)
    bad = []
    for t in cuts:
        t = pd.Timestamp(t)
        a = full[full.ts <= t].sort_values(["ts", "sym"]).reset_index(drop=True)
        for mode, (P2, F2) in (("deleted", _cut(P, fund, t)), ("perturbed", _cut(P, fund, t, rng))):
            b = pn.features(P2, F2)
            b = b[b.ts <= t].sort_values(["ts", "sym"]).reset_index(drop=True)
            if len(a) != len(b) or not (a.sym.values == b.sym.values).all():
                bad.append(("<rows>", str(t), mode))
                continue
            for c in names:
                if not _same(a[c], b[c], rtol=1e-7, atol=1e-10):
                    bad.append((c, str(t), mode))
    return bad


def main(argv=None):
    from . import data
    ap = argparse.ArgumentParser(prog="python -m research.brain.crypto.r145.guard")
    ap.add_argument("--days", type=int, default=30)
    ap.add_argument("--cuts", type=int, default=6)
    ap.add_argument("--symbols", nargs="*", default=["BTCUSDT", "ETHUSDT", "SOLUSDT", "SUIUSDT", "DOGEUSDT",
                                                      "1000PEPEUSDT", "TAOUSDT", "ENAUSDT"])
    ap.add_argument("--month", default="2025-06")
    a = ap.parse_args(argv)
    m0 = (pd.Period(a.month, "M") - 1).strftime("%Y-%m")
    bars = {s: data.load_bars(s, m0, a.month) for s in a.symbols}
    bars = {s: b for s, b in bars.items() if b is not None}
    fund = {}
    for s in bars:
        f = data.root() / "funding" / f"{s}.parquet"
        if f.exists():
            fund[s] = pd.read_parquet(f)
    P = pn.make_panel(bars)
    grid = P["close"].index
    rng = np.random.default_rng(3)
    dec = grid[pn.decision_bars(grid)] + pd.Timedelta(minutes=pn.BAR)
    late = dec[dec >= dec[-1] - pd.Timedelta(days=a.days)]
    cuts = sorted(set(rng.choice(late, size=a.cuts, replace=False)) | {late[-1] - pd.Timedelta(minutes=1)})
    bad = check(P, fund, cuts)
    print(f"R145 look-ahead: {len(bars)} coins, {len(cuts)} cuts x 2 modes, {len(pn.feature_names(pn.features(P, fund)))}"
          f" features: " + ("PASS" if not bad else f"FAIL {sorted(set(bad))[:20]}"))
    return int(bool(bad))


if __name__ == "__main__":
    sys.exit(main())
