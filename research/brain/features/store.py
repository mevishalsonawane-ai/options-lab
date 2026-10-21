"""Incremental feature store: <cache>/features/<UND>/<YYYY>/<day>.parquet (+ .json).

A day is (re)computed only when it is missing, the feature signature changed, or one of the cube days it reads (the
day itself and the warm-up sessions before it) was rebuilt. The minutes are loaded for the full warm-up (252 sessions:
IV rank); the chain only for the warm-up of the features that read it. Work is done in monthly chunks with the warm-up loaded in
front, so memory stays small and a nightly top-up touches only the new days.
"""
from __future__ import annotations

import hashlib
import json
import time
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from .. import cube
from . import load_all
from .registry import compute, signature, warmup_sessions


def _paths(root: Path, und: str, d: date):
    base = root / "features" / und / str(d.year)
    return base / f"{d}.parquet", base / f"{d}.json"


def _dep_hash(root: Path, und: str, days: list[date]) -> str:
    parts = []
    for d in days:
        man = cube.manifest(root, und, d) or {}
        parts.append(f"{d}:{man.get('sha256')}:{man.get('cube_version')}")
    return hashlib.sha256("|".join(parts).encode()).hexdigest()[:16]


def build(root: Path, und: str, frm: date, to: date, force: bool = False, verbose: bool = True) -> dict:
    load_all()
    sig, W, Wc = signature(), warmup_sessions(), warmup_sessions(chain_only=True)
    days = cube.built_days(root, und)
    pos = {d: i for i, d in enumerate(days)}
    todo = []
    for d in days:
        if not (frm <= d <= to):
            continue
        deps = days[max(0, pos[d] - W):pos[d] + 1]
        dh = _dep_hash(root, und, deps)
        pp, pj = _paths(root, und, d)
        if not force and pj.exists():
            j = json.loads(pj.read_text())
            if j.get("signature") == sig and j.get("deps") == dh and pp.exists():
                continue
        todo.append((d, dh))
    t0, n = time.time(), 0
    by_month: dict[tuple[int, int], list] = {}
    for d, dh in todo:
        by_month.setdefault((d.year, d.month), []).append((d, dh))
    for _, items in sorted(by_month.items()):
        first = items[0][0]
        lo = days[max(0, pos[first] - W)]
        M, C = cube.load(root, und, lo, items[-1][0], chain_from=days[max(0, pos[first] - Wc)])
        F = compute(M, C, und)
        F["day"] = F.ts.dt.date
        prec = M.sort_values("ts").vol_precision.values if "vol_precision" in M else np.full(len(F), "none")
        for d, dh in items:
            pp, pj = _paths(root, und, d)
            pp.parent.mkdir(parents=True, exist_ok=True)
            f = F[F.day == d].drop(columns="day")
            cols = {c: f[c].astype(np.float32) for c in f.columns if c != "ts"}
            # precision of the volume-based features in this row: "estimate" (1-minute bars), "exact" (ticks), "none"
            out = pd.DataFrame({"ts": f.ts.values, "precision": prec[f.index.values], **cols})
            out.to_parquet(pp, compression="zstd", index=False)
            pj.write_text(json.dumps(dict(signature=sig, deps=dh, rows=len(out), features=len(cols),
                                          precision=sorted(set(out.precision)))))
            n += 1
        if verbose:
            print(f"  features {items[-1][0]}: {n}/{len(todo)} days, {time.time() - t0:.0f}s", flush=True)
    if verbose:
        print(f"{und}: features for {n} days computed, {len(days) - n} up to date or out of range, signature {sig}")
    return dict(built=n, signature=sig, seconds=round(time.time() - t0, 1))


def load(root: Path, und: str, frm: date | None = None, to: date | None = None) -> pd.DataFrame:
    base = root / "features" / und
    files = sorted(base.glob("*/*.parquet")) if base.exists() else []
    keep = [p for p in files if (frm is None or date.fromisoformat(p.stem) >= frm)
            and (to is None or date.fromisoformat(p.stem) <= to)]
    return pd.concat([pd.read_parquet(p) for p in keep], ignore_index=True) if keep else pd.DataFrame()
