"""Brain data assembly, folds and statistics shared by the five levels (MB1, PREREG sections 2-4).

  load_symbol(root, sym)        cube + features + design labels + decision-row mask (labels for design days only)
  decision_mask(cube)           every 15 minutes from 09:30 to close - 75 min, close present
  folds(days)                   6 equal blocks of design sessions (union over symbols); tests on blocks 3-6, each
                                trained on the blocks before it (labels never cross a session, so no purge is needed
                                beyond "earlier days"; the embargo is the day boundary itself)
  boot_p(diff, sessions)        one-sided session-block bootstrap p of mean(diff) > 0 (B = 2000, seed 0)
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from research.data import mcx as L

from . import config, cube, features as F, holdout, labels

B_BOOT, SEED = 2000, 0


def decision_mask(m: pd.DataFrame) -> np.ndarray:
    tod = (m.ts.dt.hour * 60 + m.ts.dt.minute).to_numpy()
    close = m.ts.dt.date.map(lambda d: L.close_min(pd.Timestamp(d))).to_numpy()
    good = m.flag.isin(["ok", "stale"]).to_numpy() & np.isfinite(m.close.to_numpy(float))
    return (tod >= 9 * 60 + 30) & ((tod - (9 * 60 + 30)) % 15 == 0) & (tod <= close - 75) & good


@dataclass
class Sym:
    sym: str
    cube: pd.DataFrame          # all days
    feats: pd.DataFrame         # aligned with cube
    y: pd.DataFrame | None      # design labels aligned with cube (NaN on later days)
    dec: np.ndarray             # decision-row mask


def load_symbol(root, sym: str, with_holdout_labels: bool = False) -> Sym:
    P = config.paths(root)
    m = cube.load(P.root, sym).sort_values("ts").reset_index(drop=True)
    f = pd.read_parquet(P.features / f"{sym}.parquet").sort_values("ts").reset_index(drop=True)
    assert (f.ts.values == m.ts.values).all(), "features and cube out of step: rebuild features"
    if with_holdout_labels:
        y = labels.compute(m)
    else:
        yd = labels.design(m)
        holdout.assert_design(yd)
        y = pd.DataFrame({"ts": m.ts}).merge(yd, on="ts", how="left")
    return Sym(sym, m, f, y, decision_mask(m))


def blocks(days) -> list[tuple]:
    days = np.array(sorted(set(days)))
    parts = np.array_split(days, config.N_BLOCKS)
    return [(p[0], p[-1]) for p in parts]


def folds(days) -> list[dict]:
    """[{k, train_end, test_lo, test_hi}] for test blocks 3..6 (k = 0..3)."""
    b = blocks([d for d in days if d <= config.DESIGN_END])
    out = []
    for k, (lo, hi) in enumerate(b[config.MIN_TRAIN_BLOCKS:]):
        out.append(dict(k=k, test_lo=lo, test_hi=hi))
    return out


def boot_p(diff: np.ndarray, sessions: np.ndarray, B: int = B_BOOT, seed: int = SEED) -> float:
    """One-sided p that the mean of diff is <= 0, resampling whole sessions (per-session means, weighted by rows)."""
    diff = np.asarray(diff, float)
    ok = np.isfinite(diff)
    diff, sessions = diff[ok], np.asarray(sessions)[ok]
    if not len(diff):
        return float("nan")
    s = pd.DataFrame({"d": diff, "s": sessions}).groupby("s").d.agg(["sum", "size"])
    sums, sizes = s["sum"].to_numpy(), s["size"].to_numpy()
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(s), size=(B, len(s)))
    means = sums[idx].sum(1) / sizes[idx].sum(1)
    return float((1 + np.sum(means <= 0)) / (B + 1))


def logloss_rows(y: np.ndarray, p: np.ndarray) -> np.ndarray:
    p = np.clip(p, 1e-6, 1 - 1e-6)
    return -(y * np.log(p) + (1 - y) * np.log(1 - p))


def binom_p(k: int, n: int, two_sided: bool = False) -> float:
    from scipy.stats import binomtest
    if n == 0:
        return float("nan")
    return float(binomtest(k, n, 0.5, alternative="two-sided" if two_sided else "greater").pvalue)


def bh(pvals: dict) -> dict:
    from research.hunt.r53.common import bh as _bh
    return _bh(pvals)
