#!/usr/bin/env python3
"""B2 holdout rows (PREREG.md 2), 27 Apr .. 12 Oct 2026: F1's own code builds the feature rows and the option outcomes
from the brain's cube / features (built from the lake for 2025-03 .. 2026-10 with the F1 branch, unchanged).

Called only by `study.py holdout`, after it writes the read flag:

    IRAALGO_CACHE=<brain cache with the 2026 cube> F1_SRC=<checkout of claude/f1-india-factory> \
        python3 research/hunt/b2/holdout_rows.py
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import b2data as D  # noqa: E402

RULE_ROWS = tuple(range(15, 316, 5))


def build():
    if not D.HOLD_FLAG.exists():
        raise PermissionError("holdout rows are built only inside `study.py holdout`")
    src = Path(os.environ["F1_SRC"])
    sys.path.insert(0, str(src / "research" / "hunt" / "f1"))
    sys.path.insert(0, str(src))
    import data as F1D  # noqa: E402  (F1's day_table: the arm outcomes and returns)
    from research.brain import config as BC  # noqa: E402
    root = BC.paths().root
    a, b = D.HOLDOUT
    frames = []
    br = D.breadth_rows()
    for u in D.UNDS:
        feats, outs = [], []
        for p in sorted((root / "features" / u).glob("*/*.parquet")):
            d = date.fromisoformat(p.stem)
            if not (a <= d <= b):
                continue
            f = pd.read_parquet(p)
            mn = (f.ts - f.ts.dt.normalize()).dt.total_seconds().values // 60 - 555
            f = f[np.isin(mn, RULE_ROWS)].copy()
            f.insert(0, "row", ((f.ts - f.ts.dt.normalize()).dt.total_seconds().values // 60 - 555).astype(int))
            f.insert(0, "day", pd.Timestamp(d))
            for c in f.columns[3:]:
                if pd.api.types.is_numeric_dtype(f[c]):
                    f[c] = f[c].astype(np.float32)
                else:
                    f = f.drop(columns=c)
            feats.append(f.drop(columns="ts"))
            t = F1D._one((u, d))
            if t is not None:
                outs.append(t)
        F, T = pd.concat(feats, ignore_index=True), pd.concat(outs, ignore_index=True)
        X = F.merge(T, on=["day", "row"], how="inner").merge(br, on=["day", "row"], how="left")
        X["und"] = u
        frames.append(X)
        print(u, X.day.nunique(), "days", len(X), "rows", flush=True)
    X = pd.concat(frames, ignore_index=True)
    X["day"] = pd.to_datetime(X.day)
    X = D.add_targets(X)
    X = X.join(pd.concat([D.day_targets(u, X[X.und == u]) for u in D.UNDS]))
    X.sort_values(["day", "und", "row"]).reset_index(drop=True).to_parquet(D.OUT / "holdout.parquet", compression="zstd",
                                                                          index=False)


if __name__ == "__main__":
    build()
