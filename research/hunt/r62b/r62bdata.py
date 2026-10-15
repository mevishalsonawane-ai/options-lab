"""R62b data (PREREG section 2): the R51 held-contract store for the five indices, cleaned (R51 Amendment A, the R47
foreign / stray-row filter) and with research/data/spikes.py's spike rule applied to contract-minutes, plus one index
minute grid per index.

    python research/hunt/r62b/r62bdata.py [UND ...]     # day_<UND>.pkl (R51 store) and grid_<UND>.npz

A grid file holds days (nd,) ISO dates of the sessions (>= 300 one-minute bars) and M (nd, 375, 4) index O H L C.
The spike helpers are R62's (research/hunt/r62/r62data.py on branch claude/r62-volume-profile-smc), copied unchanged.
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np

import r62bcfg as K

K51 = K.point_r51()
import r51data  # noqa: E402


def spike_minutes(close: np.ndarray, jump: float = 1.5) -> np.ndarray:
    """research/data/spikes.py's rule on one contract-day's (375,) closes: True where the close is more than 50% away
    from BOTH neighbouring traded closes (missing minutes skipped) and the neighbours are within 50% of each other.
    The first and last traded bar are never spikes."""
    out = np.zeros(close.shape[0], bool)
    idx = np.flatnonzero(np.isfinite(close) & (close > 0))
    if len(idx) < 3:
        return out
    c = close[idx]
    p, cur, n = c[:-2], c[1:-1], c[2:]
    hi, lo = np.maximum(p, n), np.minimum(p, n)
    sp = ((cur > jump * hi) | (cur * jump < lo)) & (hi <= jump * lo)
    out[idx[1:-1][sp]] = True
    return out


def drop_spikes(days: dict) -> int:
    """Blank (NaN) every spike contract-minute of the store, in place. Returns how many."""
    n = 0
    for D in days.values():
        for a in D.con.values():
            m = spike_minutes(a[3].astype(np.float64))
            if m.any():
                a[:, m] = np.nan
                n += int(m.sum())
    return n


def save_grid(name: str, days: list, M: np.ndarray):
    K.OUT.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(K.OUT / f"grid_{name}.npz", days=np.array([d.isoformat() for d in days]),
                        M=M.astype(np.float64))


def load_grid(name: str) -> dict:
    z = np.load(K.OUT / f"grid_{name}.npz")
    return dict(days=[date.fromisoformat(s) for s in z["days"]], M=z["M"])


def build_idx(und: str) -> dict:
    days = r51data.index_days(und)
    log = r51data.add_options(und, days, clean=True)
    n_sp = drop_spikes(days)
    K51.OUT.mkdir(parents=True, exist_ok=True)
    with open(K51.OUT / f"day_{und}.pkl", "wb") as f:
        pickle.dump(days, f, protocol=pickle.HIGHEST_PROTOCOL)
    ds = sorted(days)
    M = np.stack([days[d].idx.T for d in ds])                       # (nd, 375, 4)
    keep = np.isfinite(M[:, :, 3]).sum(axis=1) >= K.MIN_BARS
    ds = [d for d, k in zip(ds, keep) if k]
    save_grid(und, ds, M[keep])
    return dict(und=und, sessions=len(ds), first=str(ds[0]), last=str(ds[-1]),
                option_sessions=int(sum(1 for d in ds if d >= K.START and days[d].atm is not None)),
                cleaned_rows=int(len(log)) if log is not None else 0, spike_minutes=n_sp,
                expiry_days=int(sum(days[d].expiry for d in ds)))


if __name__ == "__main__":
    import json
    import r62bdata as _self       # pickle the store under r51data's name, not __main__
    for u in sys.argv[1:] or K.IDX:
        print(json.dumps(_self.build_idx(u)), flush=True)
