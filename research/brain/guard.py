"""Look-ahead guard (ARCHITECTURE.md "Additions before building" item 2).

For each cut minute t it recomputes, from scratch,
  1. with every row after t DELETED, and
  2. with every row after t PERTURBED (prices scaled at random, OI/IV/flags scrambled),
and requires every value at rows <= t to be identical to the full computation. A feature (or a cube rule) that reads
anything after t fails. The same check runs on the cube builder itself (raw source rows after t removed/perturbed).

    python -m research.brain.guard [--und BANKNIFTY] [--days 5] [--cuts 6]    # on the real cube in the cache
(also checks that features do not depend on how much history before the declared warm-up is loaded)
tests/test_lookahead.py runs it on the committed fixture in CI.
"""
from __future__ import annotations

import argparse
import sys

import numpy as np
import pandas as pd

from . import config
from .adapters.base import DayData
from .cube import build_day
from .features.registry import compute

FLAGS = np.array(config.FLAGS, dtype=object)


def _same(a: pd.Series, b: pd.Series, rtol=1e-9, atol=1e-12) -> bool:
    if a.dtype.kind in "fiub" and b.dtype.kind in "fiub":
        x, y = a.to_numpy(float), b.to_numpy(float)
        return bool(np.all((np.isnan(x) & np.isnan(y)) | np.isclose(x, y, rtol=rtol, atol=atol, equal_nan=True)))
    return bool((a.astype(str).values == b.astype(str).values).all())


def _perturb_minutes(M: pd.DataFrame, t, rng) -> pd.DataFrame:
    M = M.copy()
    fut = (M.ts > t).values
    k = fut.sum()
    if not k:
        return M
    f = rng.uniform(0.9, 1.1, k)
    for c in ["open", "high", "low", "close", "fut_close"]:
        if c in M:
            M.loc[fut, c] = M.loc[fut, c].values * f
    for c in ["vix", "basis", "basis_pct", "chain_ok_share", "volume"]:
        if c in M:
            M.loc[fut, c] = rng.normal(0, 5, k)
    for c in ["flag", "fut_flag", "vix_flag"]:
        if c in M:
            M.loc[fut, c] = rng.choice(FLAGS, k)
    return M


def _perturb_chain(C: pd.DataFrame, t, rng) -> pd.DataFrame:
    if C is None or C.empty:
        return C
    C = C.copy()
    fut = (C.ts > t).values
    k = fut.sum()
    if not k:
        return C
    for c in ["open", "high", "low", "close", "ltp", "oi", "oi_chg_1m", "oi_chg_day", "iv", "delta", "volume"]:
        if c in C:
            C.loc[fut, c] = C.loc[fut, c].values * rng.uniform(0.5, 1.5, k) + rng.normal(0, 1, k)
    C.loc[fut, "flag"] = rng.choice(FLAGS, k)
    return C


def check_features(M: pd.DataFrame, C: pd.DataFrame, und: str, cuts, names=None, seed: int = 7) -> dict[str, list]:
    """Returns {feature: [(cut, mode), ...]} for every feature whose values at rows <= cut changed."""
    rng = np.random.default_rng(seed)
    full = compute(M, C, und, names)
    bad: dict[str, list] = {}
    for t in cuts:
        t = pd.Timestamp(t)
        ref = full[full.ts <= t].reset_index(drop=True)
        variants = {
            "deleted": (M[M.ts <= t], C[C.ts <= t] if C is not None and len(C) else C),
            "perturbed": (_perturb_minutes(M, t, rng), _perturb_chain(C, t, rng)),
        }
        for mode, (m2, c2) in variants.items():
            got = compute(m2, c2, und, names)
            got = got[got.ts <= t].reset_index(drop=True)
            if len(got) != len(ref):
                bad.setdefault("<rows>", []).append((str(t), mode))
                continue
            for col in ref.columns:
                if col != "ts" and not _same(ref[col], got[col]):
                    bad.setdefault(col, []).append((str(t), mode))
    return bad


def _cut_day(dd: DayData, t, rng=None) -> DayData:
    def cut(df):
        if df is None or df.empty:
            return df
        if rng is None:
            return df[pd.to_datetime(df.ts) <= t].reset_index(drop=True)
        df = df.copy()
        fut = (pd.to_datetime(df.ts) > t).values
        for c in ["open", "high", "low", "close", "oi", "volume"]:
            if c in df and fut.any():
                df.loc[fut, c] = df.loc[fut, c].values * rng.uniform(0.8, 1.2, fut.sum())
        return df
    return DayData(day=dd.day, und=dd.und, index=cut(dd.index), options=cut(dd.options), fut=cut(dd.fut),
                   vix=cut(dd.vix), sources=dd.sources)


def check_cube(dd: DayData, cuts, sessions=None, seed: int = 11) -> list[tuple]:
    """Cube rows <= t must not change when raw rows after t are removed or perturbed. Returns failures."""
    rng = np.random.default_rng(seed)
    m, ch, _ = build_day(dd, sessions)
    bad = []
    for t in cuts:
        t = pd.Timestamp(t)
        for mode, d2 in (("deleted", _cut_day(dd, t)), ("perturbed", _cut_day(dd, t, rng))):
            m2, ch2, _ = build_day(d2, sessions)
            a, b = m[m.ts <= t].reset_index(drop=True), m2[m2.ts <= t].reset_index(drop=True)
            for col in a.columns:
                if not _same(a[col], b[col]):
                    bad.append(("minutes." + col, str(t), mode))
            if len(ch):
                key = ["ts", "side", "offset"]
                a = ch[ch.ts <= t].sort_values(key).reset_index(drop=True)
                b = ch2[ch2.ts <= t].sort_values(key).reset_index(drop=True) if len(ch2) else ch2
                if len(a) != len(b):
                    bad.append(("chain.<rows>", str(t), mode))
                    continue
                for col in a.columns:
                    if not _same(a[col], b[col]):
                        bad.append(("chain." + col, str(t), mode))
    return bad


def default_cuts(ts: pd.Series, k: int, seed: int = 3) -> list:
    """Session open and close of the first/last day, plus k random minutes."""
    ts = pd.Series(pd.to_datetime(ts)).sort_values().reset_index(drop=True)
    rng = np.random.default_rng(seed)
    pick = list(rng.choice(len(ts), size=min(k, len(ts)), replace=False))
    days = ts.dt.normalize()
    firsts = ts.groupby(days).first()
    lasts = ts.groupby(days).last()
    return sorted({ts.iloc[0], lasts.iloc[0], firsts.iloc[-1], *(ts.iloc[i] for i in pick)})


def check_warmup(root, und: str, days: list, extra: int = 25) -> dict[str, int]:
    """Features of `days` computed with the declared warm-up and with `extra` more sessions must agree, so an
    incremental build (which loads only the warm-up) writes the same values as a full rebuild."""
    from . import cube
    from .features.registry import warmup_sessions
    allp = cube.built_days(root, und)
    i = allp.index(days[0])
    outs = []
    for w in (warmup_sessions(), warmup_sessions() + extra):
        M, C = cube.load(root, und, allp[max(0, i - w)], days[-1])
        F = compute(M, C, und)
        outs.append(F[F.ts.dt.date >= days[0]].reset_index(drop=True))
    a, b = outs
    return {c: int((~np.isclose(a[c], b[c], equal_nan=True, rtol=1e-9)).sum()) for c in a.columns
            if c != "ts" and not _same(a[c], b[c])}


def main(argv=None):
    from . import cube
    ap = argparse.ArgumentParser(prog="python -m research.brain.guard")
    ap.add_argument("--und", default="BANKNIFTY")
    ap.add_argument("--days", type=int, default=5, help="the last N built days (plus warm-up)")
    ap.add_argument("--cuts", type=int, default=6)
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    root = config.paths(a.cache).root
    days = cube.built_days(root, a.und)
    if not days:
        print("no cube built")
        return 1
    M, C = cube.load(root, a.und, days[max(0, len(days) - a.days)], days[-1])
    cuts = default_cuts(M.ts[M.day >= pd.Timestamp(days[max(0, len(days) - min(a.days, 3))])], a.cuts)
    bad = check_features(M, C, a.und, cuts)
    print(f"look-ahead: {len(cuts)} cuts x 2 modes over {M.day.nunique()} days: "
          + ("PASS" if not bad else f"FAIL {sorted(bad)}"))
    from .features.registry import warmup_sessions
    tail = days[-min(5, len(days)):]
    wb = check_warmup(root, a.und, tail) if len(days) > warmup_sessions() + 30 else {}
    print("warm-up independence: " + ("PASS" if not wb else f"FAIL {wb}") + f" ({tail[0]} .. {tail[-1]})")
    return 1 if bad or wb else 0


if __name__ == "__main__":
    sys.exit(main())
