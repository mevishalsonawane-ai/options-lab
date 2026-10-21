"""Columnar and binary stores for models (MB1, Boss 19 Oct: "save the data in numerical tensors, vectors, or optimized
binary/columnar file formats").

Under <cache>/mcx/tensors/ (uploaded to Drive, never committed):
  <SYM>.f32.npy          float32 [T, F]: every feature (features.names() order) at every cube minute; np.load(...,
                         mmap_mode="r") maps it without reading it
  <SYM>.ts.npy           int64 [T]: the minute (ns since epoch, naive IST)
  <SYM>.arrow            the same table as uncompressed Arrow IPC (pyarrow.ipc / pyarrow.memory_map, zero-copy)
  joint.f32.npy          float32 [I, T, F] on the union minute grid of all symbols (NaN where a symbol has no row),
  joint.ts.npy           with joint.json naming the symbols and features
  <SYM>.win120.npz       fixed-length windows: `ends` (rows whose last 121 rows lie in one contract segment, with
                         >= 90% of the window's closes present) and `enc` float32 [N, 24, 6], the L2 encoder input at decision rows
  manifest.json          shapes, dtypes, feature signature, sha256 of every file

window_view(arr, ends, W) returns [N, W, F] windows as a strided view (no copy).
Every value is point-in-time: the tensors are the feature store reshaped, and the encoder channels are built from
rows <= t only (tests/test_mcx_brain.py checks both).
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path

import numpy as np
import pandas as pd

from . import config

W_ENC, STEP = 120, 5
CHANNELS = ("ret_norm", "pos", "iv_z", "iv_mask", "tod_sin", "tod_cos")


def window_view(arr: np.ndarray, ends: np.ndarray, W: int) -> np.ndarray:
    """[N, W, F] view: arr[e - W + 1 .. e] for each e in ends (every e >= W - 1)."""
    v = np.lib.stride_tricks.sliding_window_view(arr, W, axis=0)        # [T - W + 1, F, W]
    return np.moveaxis(v[np.asarray(ends) - W + 1], -1, 1)


def valid_ends(cube: pd.DataFrame, W: int = W_ENC, min_present: float = 0.9) -> np.ndarray:
    """Rows t whose last W + 1 rows (t - W .. t: the window and the row its first return starts from) are one contract
    segment, with >= min_present of the window's closes present."""
    seg = pd.factorize(cube.seg.astype(str))[0]
    ok = np.isfinite(cube.close.to_numpy(float)) & cube.flag.isin(["ok", "stale"]).to_numpy()
    n = len(cube)
    if n < W:
        return np.array([], dtype=np.int64)
    run = pd.Series(seg).groupby(seg).cumcount().to_numpy() + 1     # rows so far in this segment
    pres = pd.Series(ok.astype(float)).rolling(W).sum().to_numpy()
    e = np.flatnonzero((run >= W + 1) & (pres >= min_present * W))
    return e.astype(np.int64)


def encoder_inputs(cube: pd.DataFrame, feats: pd.DataFrame, rows: np.ndarray) -> np.ndarray:
    """[N, 24, 6] float32 for the given rows (each in valid_ends): per 5-minute step j (oldest first) the 5-minute log
    return / (rv_60m at t x sqrt(5)) clipped to +-5, the close's position in the window's close range, the ATM IV
    z-score and its missing mask, and the clock time (sin, cos). Reads rows t - 119 .. t only."""
    from .features import Ctx
    c = Ctx(cube, str(cube.sym.iloc[0]) if "sym" in cube and len(cube) else "")
    lc = c.lc
    rv = feats["rv_60m"].to_numpy(float)
    ivz = feats["iv_z_20d"].to_numpy(float)
    rows = np.asarray(rows, dtype=np.int64)
    k = W_ENC // STEP
    idx = rows[:, None] - STEP * np.arange(k - 1, -1, -1)[None, :]           # step ends, oldest first
    prev = idx - STEP
    out = np.zeros((len(rows), k, len(CHANNELS)), dtype=np.float32)
    with np.errstate(invalid="ignore", divide="ignore"):
        scale = (rv[rows] * np.sqrt(STEP))[:, None]
        r5 = (lc[idx] - lc[np.maximum(prev, 0)]) * 100 / scale
        r5[prev < 0] = np.nan
        win = np.stack([lc[rows - W_ENC + 1 + i] for i in range(W_ENC)], axis=1)
        lo, hi = np.nanmin(win, axis=1)[:, None], np.nanmax(win, axis=1)[:, None]
        pos = np.where(hi > lo, (lc[idx] - lo) / (hi - lo), 0.5)
    out[..., 0] = np.clip(np.nan_to_num(r5, nan=0.0, posinf=0.0, neginf=0.0), -5, 5)
    out[..., 1] = np.nan_to_num(pos, nan=0.5)
    z = ivz[idx]
    out[..., 2] = np.clip(np.nan_to_num(z, nan=0.0), -5, 5)
    out[..., 3] = np.isfinite(z).astype(np.float32)
    tod = c.tod[idx]
    out[..., 4] = np.sin(2 * np.pi * tod / 1440)
    out[..., 5] = np.cos(2 * np.pi * tod / 1440)
    return out


def _sha(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def write_symbol(out: Path, sym: str, feats: pd.DataFrame, names: list[str]) -> dict:
    import pyarrow as pa
    import pyarrow.feather as pf
    out.mkdir(parents=True, exist_ok=True)
    X = feats[names].to_numpy(np.float32)
    np.save(out / f"{sym}.f32.npy", X)
    np.save(out / f"{sym}.ts.npy", pd.DatetimeIndex(feats.ts).asi8)
    tab = pa.table({"ts": pa.array(pd.DatetimeIndex(feats.ts).asi8, pa.int64()),
                    **{n: pa.array(X[:, i], pa.float32()) for i, n in enumerate(names)}})
    pf.write_feather(tab, out / f"{sym}.arrow", compression="uncompressed")
    return {"rows": int(X.shape[0]), "features": int(X.shape[1])}


def write_windows(out: Path, sym: str, cube: pd.DataFrame, feats: pd.DataFrame, rows: np.ndarray) -> dict:
    ends = valid_ends(cube)
    rows = np.intersect1d(np.asarray(rows, np.int64), ends)
    enc = encoder_inputs(cube, feats, rows) if len(rows) else np.zeros((0, W_ENC // STEP, len(CHANNELS)), np.float32)
    np.savez(out / f"{sym}.win120.npz", ends=ends, rows=rows, enc=enc, channels=np.array(CHANNELS))
    return {"valid_ends": int(len(ends)), "encoder_rows": int(len(rows))}


def write_joint(out: Path, frames: dict[str, pd.DataFrame], names: list[str]) -> dict:
    syms = sorted(frames)
    ts = np.unique(np.concatenate([pd.DatetimeIndex(frames[s].ts).asi8 for s in syms]))
    J = np.lib.format.open_memmap(out / "joint.f32.npy", mode="w+", dtype=np.float32,
                                  shape=(len(syms), len(ts), len(names)))
    for i, s in enumerate(syms):
        J[i] = np.nan
        pos = np.searchsorted(ts, pd.DatetimeIndex(frames[s].ts).asi8)
        J[i, pos] = frames[s][names].to_numpy(np.float32)
    J.flush()
    np.save(out / "joint.ts.npy", ts)
    (out / "joint.json").write_text(json.dumps({"symbols": syms, "features": names, "shape": list(J.shape)}))
    return {"shape": list(J.shape)}


def manifest(out: Path, extra: dict) -> dict:
    files = {p.name: {"bytes": p.stat().st_size, "sha256": _sha(p)} for p in sorted(out.iterdir())
             if p.is_file() and p.name != "manifest.json"}
    m = {**extra, "cube_version": config.CUBE_VERSION, "files": files}
    (out / "manifest.json").write_text(json.dumps(m, indent=1))
    return m
