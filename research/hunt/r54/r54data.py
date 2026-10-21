"""R54 data: R50's expiry days for NIFTY and SENSEX, built as R43 / R25 `Day`s with R47's foreign-rows filter.

    python3 research/hunt/r54/r54data.py build [UND ...]     # <OUT>/days_<UND>.pkl (+ <OUT>/foreign_<UND>.json)

The builder is R43's (`r43data.build_days`) with R50's expiry rule (`r50data.expiry_days_with_last`) and one more
measurement filter (PREREG.md): an option row whose own `spot` is more than 3% off the index close at that minute (or
the index's last close that session before it) is dropped - the lake's rolling files carry some rows of another
underlying (R47 amendment 2). Days before 20 Nov 2024 are never returned by `load()`.
"""
from __future__ import annotations

import json
import pickle
import sys

import numpy as np
import pandas as pd

import r54cfg as K
import r25data as D
import r50data as D50
from r25data import Day, N  # noqa: F401


def foreign_mask(spot: np.ndarray, m: np.ndarray, ix_close: np.ndarray, tol: float = K.FOREIGN_TOL) -> np.ndarray:
    """True for rows (minute m, own spot) more than tol off the index close at m, or off its last close before m that
    session when m has none (R47 `foreign_rows`). A row with no index close at or before it is foreign too."""
    ref = pd.Series(ix_close).ffill().to_numpy()[m]
    with np.errstate(all="ignore"):
        r = np.asarray(spot, dtype=float) / ref
    return ~(np.isfinite(r) & (np.abs(r - 1.0) <= tol))


def build_days(und: str) -> tuple[list, dict]:
    """R43's build_days with R50's expiry rule and the foreign-rows filter. Returns (days, dropped rows per date)."""
    ix = D._index_frames(und)
    vx = D._index_frames("INDIAVIX")
    ixd = dict(tuple(ix.groupby("d")))
    vxd = dict(tuple(vx.groupby("d"))) if len(vx) else {}
    sess = sorted(ixd)
    tvs, frames = [], {}
    for p in sorted(K.LAKE.glob(f"opt_{und}_WEEK_*.parquet")):
        o = D._read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= D.STRIKE_SLACK * K.K50.STEP[und]]
        tvs.append(D._tv_1515(o))
        frames[p] = o
    tv = pd.concat(tvs).sort_index()
    tv = tv[~tv.index.duplicated()]
    exp = set(D50.expiry_days_with_last(tv))
    out, dropped, seen = [], {}, set()
    for p, o in frames.items():
        o = o[o.d.isin(exp)]
        for d, g in o.groupby("d", sort=True):
            if d in seen or d not in ixd:
                continue
            seen.add(d)
            i = sess.index(d)
            ixa = D._grid(ixd[d], ["open", "high", "low", "close"])
            bad = foreign_mask(g.spot.values, g.m.values, ixa[:, 3])
            if bad.any():
                dropped[str(d)] = int(bad.sum())
            g = g[~bad]
            prev = ixd[sess[i - 1]] if i > 0 else None
            pc = float(prev.close.iloc[-1]) if prev is not None and len(prev) else np.nan
            pr = float(prev.high.max() - prev.low.min()) if prev is not None and len(prev) else np.nan
            vprev = float(vxd[sess[i - 1]].close.iloc[-1]) if i > 0 and sess[i - 1] in vxd else np.nan
            va = D._grid(vxd[d], ["close"])[:, 0] if d in vxd else np.full(N, np.nan)
            ixc = pd.Series(ixa[:, 3])
            g = g[(g.strike - ixc.reindex(g.m.values).values).abs() <= D.STRIKE_SLACK * K.K50.STEP[und]]
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            opts = {}
            for (k, s), h in g.groupby(["strike", "side"]):
                opts[(float(k), s)] = D._grid(h, ["open", "high", "low", "close", "volume", "oi", "iv"])
            edge = np.full((N, 2), np.nan)
            ec = g[(g.side == "C") & (g.offset == 10)]
            ep = g[(g.side == "P") & (g.offset == -10)]
            edge[ec.m.values, 0] = ec.close.values
            edge[ep.m.values, 1] = ep.close.values
            out.append(Day(d, und, ixa, opts, va, pc, vprev, K.lot(und, d), edge, pr))
    out.sort(key=lambda x: x.day)
    return out, dropped


def days_path(und: str):
    return K.OUT / f"days_{und}.pkl"


def _prep(days: list) -> list:
    for d in days:
        D.clean(d)
        d.lot = K.lot(d.und, d.day)
    return days


def load(und: str, part: str) -> list:
    """part: 'design', 'final' (only r54study.py final asks) or 'window' (both; never used for P&L)."""
    days = [d for d in pickle.loads(days_path(und).read_bytes()) if K.period(d.day) is not None]
    if part in ("design", "final"):
        days = [d for d in days if K.period(d.day) == part]
    elif part != "window":
        raise ValueError(part)
    return _prep(days)


def calendar(und: str) -> list:
    """Every in-window expiry date (no prices)."""
    return [d.day for d in pickle.loads(days_path(und).read_bytes()) if K.period(d.day) is not None]


def check_against_r50() -> dict:
    """R54's in-window expiry list vs R50's results/expiry_days.json (PREREG: any difference is reported)."""
    r50 = json.loads((K.R50 / "results" / "expiry_days.json").read_text())
    out = {}
    for u in K.UNDS:
        a, b = {str(x) for x in calendar(u)}, set(r50[u])
        out[u] = {"n": len(a), "only_r54": sorted(a - b), "only_r50": sorted(b - a)}
    return out


if __name__ == "__main__":
    import r54data as _self
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or K.UNDS):
            ds, dr = _self.build_days(u)
            days_path(u).write_bytes(pickle.dumps(ds))
            (K.OUT / f"foreign_{u}.json").write_text(json.dumps(dr, indent=1))
            print(u, "days", len(ds), "in window", sum(K.period(d.day) is not None for d in ds),
                  "days with foreign rows", len(dr), "rows dropped", sum(dr.values()), flush=True)
