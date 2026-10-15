"""Incremental crypto feature store: <cache>/crypto/features/<SYM>/<YYYY>/<day>.parquet (+ .json).

As research/brain/features/store.py: a day is (re)computed only when it is missing, the feature signature changed, or
one of the cube days it reads (itself and the warm-up days before it) was rebuilt. Work is done a month at a time with
the warm-up loaded in front, so memory stays small and a top-up touches only new days.
"""
from __future__ import annotations

import hashlib
import json
import time
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

from .. import config, cube
from . import load_all
from .registry import compute, signature, warmup_days


def _paths(root: Path, sym: str, d: date):
    base = config.paths(root).features(sym) / str(d.year)
    return base / f"{d}.parquet", base / f"{d}.json"


def _dep_hash(root: Path, sym: str, days: list[date]) -> str:
    parts = [f"{d}:{(cube.manifest(root, sym, d) or {}).get('sha256')}" for d in days]
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:16]


def build(root: Path, sym: str, frm: date, to: date, force: bool = False, market: str = "crypto",
          verbose: bool = True) -> dict:
    load_all()
    sig = signature() + ("" if market == "crypto" else f":{market}")
    W = warmup_days()
    days = cube.built_days(root, sym)
    have = set(days)
    todo = []
    for d in days:
        if not (frm <= d <= to):
            continue
        deps = [d - timedelta(days=k) for k in range(W, -1, -1) if d - timedelta(days=k) in have]
        dh = _dep_hash(root, sym, deps)
        pp, pj = _paths(root, sym, d)
        if not force and pj.exists() and pp.exists():
            j = json.loads(pj.read_text())
            if j.get("signature") == sig and j.get("deps") == dh:
                continue
        todo.append((d, dh))
    t0, n = time.time(), 0
    by_month: dict = {}
    for d, dh in todo:
        by_month.setdefault((d.year, d.month), []).append((d, dh))
    for _, items in sorted(by_month.items()):
        M = cube.load(root, sym, items[0][0] - timedelta(days=W), items[-1][0])
        F = compute(M, sym, market=market)
        prec = pd.Series(M.vol_precision.values, index=pd.DatetimeIndex(M.ts)).reindex(pd.DatetimeIndex(F.ts)).fillna("none")
        F["precision"] = prec.values
        F["d"] = F.ts.dt.date
        for d, dh in items:
            pp, pj = _paths(root, sym, d)
            pp.parent.mkdir(parents=True, exist_ok=True)
            f = F[F.d == d]
            cols = {c: f[c].astype(np.float32) for c in f.columns if c not in ("ts", "d", "precision")}
            out = pd.DataFrame({"ts": f.ts.values, "precision": f.precision.values, **cols})
            out.to_parquet(pp, compression="zstd", index=False)
            pj.write_text(json.dumps(dict(signature=sig, deps=dh, rows=len(out), features=len(cols))))
            n += 1
        if verbose:
            print(f"  {sym} features {items[-1][0]}: {n}/{len(todo)} days, {time.time() - t0:.0f}s", flush=True)
    if verbose:
        print(f"{sym}: features for {n} days computed, signature {sig}")
    return dict(built=n, signature=sig, seconds=round(time.time() - t0, 1))


def load(root: Path, sym: str, frm: date | None = None, to: date | None = None, columns=None) -> pd.DataFrame:
    base = config.paths(root).features(sym)
    files = sorted(base.glob("*/*.parquet")) if base.exists() else []
    keep = [p for p in files if (frm is None or date.fromisoformat(p.stem) >= frm)
            and (to is None or date.fromisoformat(p.stem) <= to)]
    return pd.concat([pd.read_parquet(p, columns=columns) for p in keep], ignore_index=True) if keep else pd.DataFrame()
