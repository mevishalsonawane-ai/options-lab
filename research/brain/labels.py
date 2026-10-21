"""Labels (targets): what happens AFTER minute t. Kept apart from features on purpose.

  python -m research.brain.labels build [--from --to --und]   -> <cache>/labels/<UND>/<YYYY>/<day>.parquet

14 Oct 2026: also the rest of the session (t+1 .. 15:29): `y_range_rest` (high - low, % of the close at t) and
`y_er_rest` (efficiency |close(15:29) - close(t)| / that range; >= 0.5 is R16's "trend day"). Labels are written ONLY
for design days (holdout.DESIGN_END): the embargo and the locked holdout get none.

Every label column starts with "y_". Labels are computed from the cube only; they read rows t+1..t+h of the SAME
session (NaN when the window runs past 15:29) and are joined to features only in training code, through
split.join_forward(). Nothing in research/brain/features imports this module (tests/test_lookahead.py checks).
"""
from __future__ import annotations

import argparse
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from . import config, cube, holdout

PREFIX = "y_"
HORIZONS = (15, 30, 60)
MAX_HORIZON_MIN = max(HORIZONS)       # what split.py must purge (the rest-of-day labels purge a whole session)
COLUMNS = ["ts"] + [f"y_{k}_{h}m" for h in HORIZONS for k in ("ret", "range", "mfe", "mae")] + ["y_range_rest",
                                                                                               "y_er_rest"]


def _fwd(a: np.ndarray, day: np.ndarray, h: int, how: str) -> np.ndarray:
    """max/min of a[t+1 .. t+h] within the session; NaN if the window is incomplete."""
    s = pd.Series(a[::-1])
    g = pd.Series(day[::-1])
    r = getattr(s.groupby(g.values).rolling(h, min_periods=h), how)().reset_index(level=0, drop=True).sort_index()
    rv = r.values[::-1]                       # rv[t] = how(a[t .. t+h-1])
    out = np.full(len(a), np.nan)
    same = np.r_[day[1:] == day[:-1], False]
    out[:-1] = np.where(same[:-1], rv[1:], np.nan)
    return out


def compute(minutes: pd.DataFrame, horizons=HORIZONS) -> pd.DataFrame:
    """Labels for every minute. `day` bounds each window: the Indian cube passes its session, the crypto brain
    (24x7) passes one constant so windows run across midnight (13 Oct 2026)."""
    m = minutes.sort_values("ts").reset_index(drop=True)
    good = m.flag.isin(["ok", "stale"]).values
    c = np.where(good, m.close.values, np.nan)
    h = np.where(good, m.high.values, np.nan)
    l = np.where(good, m.low.values, np.nan)
    day = pd.factorize(m.day)[0]
    out = {"ts": m.ts.values}
    n = len(m)
    for H in horizons:
        fut = np.full(n, np.nan)
        same = np.zeros(n, bool)
        same[:-H] = day[H:] == day[:-H]
        fut[:-H] = np.where(same[:-H], c[H:], np.nan)
        hi, lo = _fwd(h, day, H, "max"), _fwd(l, day, H, "min")
        with np.errstate(invalid="ignore", divide="ignore"):
            out[f"y_ret_{H}m"] = np.log(fut / c) * 100
            out[f"y_range_{H}m"] = (hi - lo) / c * 100
            out[f"y_mfe_{H}m"] = (hi / c - 1) * 100
            out[f"y_mae_{H}m"] = (lo / c - 1) * 100
    # rest of the session: rows t+1 .. the session's last row (NaN on the last row, or if the last bar is missing)
    s_h, s_l = (pd.Series(a[::-1]).groupby(day[::-1]) for a in (h, l))
    rh = s_h.cummax().values[::-1]                 # rh[t] = max h[t .. end of session]
    rl = s_l.cummin().values[::-1]
    last_c = pd.Series(c).groupby(day).transform(lambda x: x.iloc[-1]).values      # the close of the 15:29 bar
    last_ok = np.isfinite(last_c)
    nxt = np.r_[day[1:] == day[:-1], False]
    hi_r, lo_r = np.full(n, np.nan), np.full(n, np.nan)
    hi_r[:-1], lo_r[:-1] = rh[1:], rl[1:]
    hi_r[~nxt], lo_r[~nxt] = np.nan, np.nan
    with np.errstate(invalid="ignore", divide="ignore"):
        rng = np.where(last_ok, hi_r - lo_r, np.nan)
        out["y_range_rest"] = rng / c * 100
        out["y_er_rest"] = np.where(rng > 0, np.abs(last_c - c) / rng, np.nan)
    return pd.DataFrame(out)


def build(root: Path, und: str, frm: date, to: date) -> int:
    """Labels for built cube days in [frm, to], design days only (never the embargo or the locked holdout)."""
    import pyarrow.parquet as pq
    n = 0
    to = min(to, holdout.DESIGN_END)
    for d in cube.built_days(root, und):
        if not (frm <= d <= to):
            continue
        p = root / "labels" / und / str(d.year) / f"{d}.parquet"
        pm = cube.day_paths(root, und, d)[0]
        if p.exists() and p.stat().st_mtime >= pm.stat().st_mtime and pq.read_schema(p).names == COLUMNS:
            continue
        M, _ = cube.load(root, und, d, d, chain=False)
        p.parent.mkdir(parents=True, exist_ok=True)
        compute(M).to_parquet(p, compression="zstd", index=False)
        n += 1
    print(f"{und}: labels for {n} days written")
    return n


def load(root: Path, und: str, frm: date | None = None, to: date | None = None) -> pd.DataFrame:
    base = root / "labels" / und
    files = sorted(base.glob("*/*.parquet")) if base.exists() else []
    keep = [p for p in files if (frm is None or date.fromisoformat(p.stem) >= frm)
            and (to is None or date.fromisoformat(p.stem) <= to)]
    return pd.concat([pd.read_parquet(p) for p in keep], ignore_index=True) if keep else pd.DataFrame()


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m research.brain.labels")
    ap.add_argument("cmd", choices=["build"])
    ap.add_argument("--from", dest="frm", default="2000-01-01")
    ap.add_argument("--to", default="2099-12-31")
    ap.add_argument("--und", default="BANKNIFTY")
    ap.add_argument("--cache", default=None)
    a = ap.parse_args(argv)
    build(config.paths(a.cache).root, a.und, date.fromisoformat(a.frm), date.fromisoformat(a.to))


if __name__ == "__main__":
    sys.exit(main())
