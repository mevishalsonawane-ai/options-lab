"""R62 data (PREREG sections 1-2, 7): one minute grid per instrument plus its volume sources, and the R51 held-contract
store for the five indices.

    python research/hunt/r62/r62data.py idx [UND ...]     # R51 clean store (+ spikes.py rule) and grid_<UND>.npz
    python research/hunt/r62/r62data.py stk               # grid_<SYM>.npz for the 10 stocks and the (b) volumes

A grid file holds:
    days   (nd,) ISO dates of the sessions (>= 300 one-minute bars), 1 Sep 2024 .. the lake's end
    M      (nd, 375, 4) one-minute O H L C (NaN = missing minute)
    vol_a / vol_b / vol_c / vol   (nd, 375) the volume sources: (a) option-chain volume, (b) constituents' traded value,
           (c) TPO (1 per minute present), stock volume. NaN = unknown that minute.
"""
from __future__ import annotations

import glob
import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

import r62cfg as K

K51 = K.point_r51()
import r51data  # noqa: E402

V = 4


# ------------------------------------------------------------------------------------------------ pure helpers
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
        for k, a in D.con.items():
            m = spike_minutes(a[3].astype(np.float64))
            if m.any():
                a[:, m] = np.nan
                n += int(m.sum())
    return n


def option_volume(D) -> np.ndarray:
    """(375,) volume source (a): summed traded volume of every contract of the day's chain that minute (negative
    volumes count 0); NaN when no contract printed that minute."""
    out = np.full(K.NROWS, np.nan)
    if not D.con:
        return out
    S = np.stack([a[4].astype(np.float64) for a in D.con.values()])
    has = np.isfinite(S).any(axis=0)
    v = np.where(np.isfinite(S) & (S > 0), S, 0.0).sum(axis=0)
    out[has] = v[has]
    return out


def tpo(M: np.ndarray) -> np.ndarray:
    return np.where(np.isfinite(M[:, :, 3]), 1.0, np.nan)


def grid_from(df: pd.DataFrame, days: list, cols) -> np.ndarray:
    """(nd, 375, len(cols)) from a frame with ts (IST) and cols; minutes outside 09:15-15:29 or other days dropped."""
    dix = {d: i for i, d in enumerate(days)}
    ts = df.ts
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    di = ts.dt.date.map(dix)
    r = (ts.dt.hour * 60 + ts.dt.minute - (9 * 60 + 15)).to_numpy()
    ok = di.notna().to_numpy() & (r >= 0) & (r < K.NROWS)
    out = np.full((len(days), K.NROWS, len(cols)), np.nan)
    out[di.to_numpy()[ok].astype(int), r[ok]] = df[list(cols)].to_numpy(dtype=np.float64)[ok]
    return out


def sessions(M: np.ndarray, days: list) -> tuple[np.ndarray, list]:
    """Keep sessions with >= MIN_BARS minutes."""
    keep = np.isfinite(M[:, :, 3]).sum(axis=1) >= K.MIN_BARS
    return M[keep], [d for d, k in zip(days, keep) if k]


def save_grid(name: str, days: list, M: np.ndarray, **vols):
    K.OUT.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(K.OUT / f"grid_{name}.npz", days=np.array([d.isoformat() for d in days]), M=M.astype(np.float64),
                        **{k: v.astype(np.float64) for k, v in vols.items()})


def load_grid(name: str) -> dict:
    z = np.load(K.OUT / f"grid_{name}.npz")
    g = {k: z[k] for k in z.files}
    g["days"] = [date.fromisoformat(s) for s in g["days"]]
    return g


# ------------------------------------------------------------------------------------------------ builds
def build_idx(und: str) -> dict:
    days = r51data.index_days(und)
    log = r51data.add_options(und, days, clean=True)
    n_sp = drop_spikes(days)
    K51.OUT.mkdir(parents=True, exist_ok=True)
    with open(K51.OUT / f"day_{und}.pkl", "wb") as f:
        pickle.dump(days, f, protocol=pickle.HIGHEST_PROTOCOL)
    ds = sorted(days)
    M = np.stack([days[d].idx.T for d in ds])                       # (nd, 375, 4)
    va = np.stack([option_volume(days[d]) for d in ds])
    keep = np.isfinite(M[:, :, 3]).sum(axis=1) >= K.MIN_BARS
    ds = [d for d, k in zip(ds, keep) if k]
    M, va = M[keep], va[keep]
    vols = dict(vol_a=va, vol_c=tpo(M))
    save_grid(und, ds, M, **vols)
    return dict(und=und, sessions=len(ds), first=str(ds[0]), last=str(ds[-1]),
                option_sessions=int(sum(1 for d in ds if d >= K.START and days[d].atm is not None)),
                cleaned_rows=int(len(log)) if log is not None else 0, spike_minutes=n_sp,
                expiry_days=int(sum(days[d].expiry for d in ds)))


def equity_files():
    return sorted(glob.glob(str(K.LAKE / "equity_1m" / "equity_1m_202[456].parquet")))


def build_stk() -> dict:
    """The 10 stocks' grids (stock volume + TPO) and the constituents' traded value (b) for NIFTY and BANKNIFTY."""
    want = sorted(set(K.STOCKS) | set(K.NIFTY50) | set(K.BANKS))
    per = {s: [] for s in K.STOCKS}
    val = {u: [] for u in K.CONSTITUENTS}
    for f in equity_files():
        x = pd.read_parquet(f, columns=["symbol", "ts", "open", "high", "low", "close", "volume"],
                            filters=[("symbol", "in", want)])
        x["symbol"] = x.symbol.astype(str)
        d = x.ts.dt.tz_convert("Asia/Kolkata").dt.date if x.ts.dt.tz is not None else x.ts.dt.date
        x = x[(d >= K.WARM_START) & (d <= K.END)]
        x = x.drop_duplicates(["symbol", "ts"])
        for s in K.STOCKS:
            per[s].append(x[x.symbol == s].drop(columns="symbol"))
        x["value"] = x.close * x.volume.clip(lower=0)
        for u, names in K.CONSTITUENTS.items():
            g = x[x.symbol.isin(names)].groupby("ts", sort=True)["value"].agg(["sum", "count"]).reset_index()
            val[u].append(g)
        del x
    out = {}
    for s in K.STOCKS:
        e = pd.concat(per[s], ignore_index=True).sort_values("ts")
        ts = e.ts.dt.tz_convert("Asia/Kolkata") if e.ts.dt.tz is not None else e.ts
        days = sorted(set(ts.dt.date))
        M = grid_from(e, days, ["open", "high", "low", "close", "volume"])
        vol = M[:, :, 4].copy()
        vol[~np.isfinite(vol) | (vol < 0)] = np.nan
        M4, ds = sessions(M[:, :, :4], days)
        keep = [days.index(d) for d in ds]
        save_grid(s, ds, M4, vol=vol[keep], vol_c=tpo(M4))
        out[s] = dict(sessions=len(ds), first=str(ds[0]), last=str(ds[-1]))
    for u, parts in val.items():
        g = pd.concat(parts, ignore_index=True)
        gi = load_grid(u)
        b = grid_from(g.rename(columns={"sum": "v"}), gi["days"], ["v", "count"])
        vb = b[:, :, 0]
        vb[~(b[:, :, 1] >= 0.8 * len(K.CONSTITUENTS[u]))] = np.nan    # a minute needs >= 80% of the names printing
        gi["vol_b"] = vb
        save_grid(u, gi["days"], gi["M"], **{k: v for k, v in gi.items() if k.startswith("vol_")})
        out[u + "_b"] = dict(minutes_with_b=float(np.isfinite(vb).mean()))
    return out


if __name__ == "__main__":
    import json
    import r62data as _self       # pickle the store under r51data's name, not __main__
    if sys.argv[1] == "idx":
        for u in sys.argv[2:] or K.IDX:
            print(json.dumps(_self.build_idx(u)), flush=True)
    elif sys.argv[1] == "stk":
        print(json.dumps(_self.build_stk(), indent=1))
