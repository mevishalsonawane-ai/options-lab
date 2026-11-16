"""R189 data (PREREG.md section 2): one Und per index from the `dhan-data` branch, read with the obuy loader.

    git archive --format=tar -o <scratch>/r189data/dhan.tar origin/dhan-data \\
        ':(glob)dhan-data/options/NIFTY/*/*/202[456]*.parquet' ... ':(glob)dhan-data/candles/minute/IDX_I/*/202[456]*.parquet'
    tar -xf dhan.tar -C <scratch>/r189data/repo
    R189_DATA=<scratch>/r189data/repo/dhan-data python3 -I research/hunt/r189_hull_bbi/r189data.py build

The data are untrusted: run with `python -I` (this script puts the repo's research/ on the path itself) and never
from inside the data folder. Nothing is committed. Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import os
import pickle
import sys
from datetime import date
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r189cfg as K  # noqa: E402

os.environ.setdefault("OBUY_DATA", str(K.DATA))
os.environ.setdefault("OBUY_CACHE", str(K.OUT / "obuy_cache"))

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402


def _r51cfg():
    import r51cfg
    return r51cfg


def index_minutes(und: str) -> dict:
    """{date: (375, 4) O H L C} from Dhan's index 1-minute candles, session minutes only, first print of a minute kept."""
    fs = sorted((K.DATA / "candles" / "minute" / "IDX_I" / und).glob("*.parquet"))
    if not fs:
        return {}
    ix = pd.concat([pd.read_parquet(f, columns=["ts", "open", "high", "low", "close"]) for f in fs])
    ts = pd.to_datetime(ix.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    ix = ix.assign(day=ts.dt.date, r=(ts.dt.hour * 60 + ts.dt.minute) - K.OPEN_M)
    ix = ix[(ix.r >= 0) & (ix.r < K.N)].drop_duplicates(["day", "r"]).sort_values(["day", "r"])
    out = {}
    for d, g in ix.groupby("day"):
        if d < K.WARM_START or d > K.END:
            continue
        a = np.full((K.N, 4), np.nan)
        a[g.r.to_numpy()] = np.round(g[["open", "high", "low", "close"]].to_numpy(float), 2)
        out[d] = a
    return out


def build_und(und: str):
    """The Und of one index: every index session from WARM_START to END; the near chain where Dhan has it."""
    from obuy import data as OD
    import r189lib as Z
    mins = index_minutes(und)
    opts = OD.Options(und)
    days = sorted(mins)
    S, key, exp, lot = [], [], [], []
    for d in days:
        ch = opts.chain(d, "near") if d >= K.START else None
        if ch is None or len(ch.K) == 0:
            S.append(None)
            key.append({})
            exp.append(False)
        else:
            mats, kk = [], {}
            for side in ("C", "P"):
                for i, k in enumerate(ch.K):
                    A = np.stack([ch.o[side][i], ch.h[side][i], ch.l[side][i], ch.c[side][i], ch.v[side][i]])
                    if not np.isfinite(A[0]).any():
                        continue
                    kk[(side, float(k))] = len(mats)
                    mats.append(A.astype(np.float32))
            S.append(np.stack(mats) if mats else None)
            key.append(kk)
            exp.append(bool(OD.expiry_from_chain(ch, K.STEP[und])))
        lot.append(_r51cfg().lot(und, d))
    ix = np.stack([mins[d] for d in days])
    return dict(und=und, days=days, ix=ix, exp=exp, lot=lot, S=S, key=key)


def load(und: str):
    """The Und of one index, built once and cached in R189_OUT."""
    import r189lib as Z
    p = K.OUT / f"und_{und}.pkl"
    if not p.exists():
        K.OUT.mkdir(parents=True, exist_ok=True)
        raw = build_und(und)
        with open(p, "wb") as fh:
            pickle.dump(raw, fh, protocol=4)
    with open(p, "rb") as fh:
        raw = pickle.load(fh)
    S = [None if s is None else s.astype(np.float64) for s in raw["S"]]
    return Z.make_und(raw["und"], raw["days"], raw["ix"], raw["exp"], raw["lot"], S, raw["key"])


def coverage() -> dict:
    """Days per index (no P&L): sessions, sessions with a chain, expiry days, per period."""
    out = {}
    for u in K.UNDS:
        U = load(u)
        row = {}
        for p in K.PERIODS:
            dis = [i for i, d in enumerate(U.days) if K.in_period(d, p)]
            row[p] = dict(sessions=len(dis), with_chain=sum(U.S[i] is not None for i in dis),
                          expiry=int(sum(U.exp[i] for i in dis)))
        row["first"], row["last"] = str(U.days[0]), str(U.days[-1])
        out[u] = row
    return out


if __name__ == "__main__":
    if (sys.argv[1:] or [""])[0] == "build":
        for u in K.UNDS:
            load(u)
            print("built", u, flush=True)
        import json
        print(json.dumps(coverage(), indent=1))
    else:
        print(__doc__)
