"""B2 data (PREREG.md 1-3): F1's feature rows + outcomes + breadth, and B2's targets, per index.

Design = every row F1 built (its design AND its spent holdout). Holdout rows (27 Apr .. 12 Oct 2026) exist only after
`study.py holdout` built them (flag file); `load("holdout")` refuses otherwise.

Paths: F1 tables in $B2_F1 (default <cache>/f1, from gdrive:IraAlgo-data-lake/derived/f1_factory); B2's own in $B2_OUT
(default <cache>/b2); the lake's index minutes in $IRAALGO_LAKE (default <cache>/lake).
"""
from __future__ import annotations

import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (str(ROOT), str(ROOT / "research")):
    if p not in sys.path:
        sys.path.insert(0, p)

from research.brain import config as BC  # noqa: E402

CACHE = BC.paths()
F1 = Path(os.environ.get("B2_F1", CACHE.root / "f1")).expanduser()
OUT = Path(os.environ.get("B2_OUT", CACHE.root / "b2")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE.root / "lake")).expanduser()
RESULTS = HERE / "results"
HOLD_FLAG = RESULTS / "HOLDOUT_READ"

UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX")
HOLDOUT = (date(2026, 4, 27), date(2026, 10, 12))
BLOCKS = {"2023": (date(2023, 1, 1), date(2023, 12, 31)), "2024": (date(2024, 1, 1), date(2024, 12, 31)),
          "2025": (date(2025, 1, 1), date(2025, 12, 31)), "2026": (date(2026, 1, 1), date(2026, 4, 13))}
GAP_SESSIONS = 5
NOT_FEATURES = {"day", "row", "und", "is_expiry", "dte"}
OUTCOME_COLS = {"ret15", "ret30", "ret60", "abs30"}
ROW_TARGETS = ("G15", "G30", "G60", "GEOD", "CEW", "PEW", "SIZE")
DAY_TARGETS = ("DAY0930", "DAY1015")
TARGETS = ROW_TARGETS + DAY_TARGETS
BRCOLS = ["br_adv_share", "br_above_vwap", "br_adv_share_chg15", "br_bank_adv_share"]


def index_minutes(und: str) -> pd.DataFrame:
    """Lake index closes on the 375-row grid: one row per day, columns 0..374 (NaN where missing)."""
    parts = []
    for p in sorted(LAKE.rglob(f"index_1m_{und}_*.parquet")):
        df = pd.read_parquet(p, columns=["ts", "open", "close"])
        ts = pd.to_datetime(df.ts)
        if ts.dt.tz is not None:
            ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
        df = df.assign(day=ts.dt.normalize(), m=((ts - ts.dt.normalize()).dt.total_seconds() // 60 - 555).astype(int))
        parts.append(df[(df.m >= 0) & (df.m < 375)])
    df = pd.concat(parts, ignore_index=True)
    C = df.pivot_table(index="day", columns="m", values="close", aggfunc="last")
    O = df[df.m == 0].groupby("day").open.first()
    C = C.reindex(columns=range(375))
    C["open0915"] = O
    return C


def day_targets(und: str, rows: pd.DataFrame) -> pd.DataFrame:
    """GEOD (close 15:29 > close at t), DAY (close 15:29 > open 09:15), from the lake's index minutes."""
    C = index_minutes(und)
    last = C[list(range(375))].ffill(axis=1)[374]
    cl = C[list(range(375))]
    d = rows.day.values
    r = rows.row.values.astype(int)
    idx = C.index.get_indexer(pd.to_datetime(d))
    ok = idx >= 0
    now = np.full(len(rows), np.nan)
    now[ok] = cl.values[idx[ok], r[ok]]
    end = np.full(len(rows), np.nan)
    end[ok] = last.values[idx[ok]]
    op = np.full(len(rows), np.nan)
    op[ok] = C.open0915.values[idx[ok]]
    out = pd.DataFrame(index=rows.index)
    out["GEOD"] = np.where(np.isfinite(now) & np.isfinite(end) & (end != now), (end > now).astype(float), np.nan)
    out["DAYG"] = np.where(np.isfinite(op) & np.isfinite(end) & (end != op), (end > op).astype(float), np.nan)
    return out


def add_targets(X: pd.DataFrame) -> pd.DataFrame:
    for h in (15, 30, 60):
        r = X[f"ret{h}"]
        X[f"G{h}"] = np.where(r.notna() & (r != 0), (r > 0).astype(float), np.nan)
    X["CEW"] = np.where(X.net_ce_atm_x1.notna(), (X.net_ce_atm_x1 > 0).astype(float), np.nan)
    X["PEW"] = np.where(X.net_pe_atm_x1.notna(), (X.net_pe_atm_x1 > 0).astype(float), np.nan)
    return X


def _f1(part: str, kind: str, und: str) -> pd.DataFrame:
    return pd.read_parquet(F1 / kind / part / f"{und}.parquet")


def breadth_rows() -> pd.DataFrame:
    B = pd.read_parquet(F1 / "breadth.parquet")
    mn = (B.ts - B.ts.dt.normalize()).dt.total_seconds() // 60 - 555
    return pd.DataFrame(dict(day=B.ts.dt.normalize(), row=mn.astype(int), **{c: B[c].values for c in BRCOLS}))


def build_design() -> Path:
    """Design table (F1 design + F1 holdout rows) with every B2 target -> <OUT>/design.parquet."""
    br = breadth_rows()
    frames = []
    for u in UNDS:
        for part in ("design", "holdout"):
            F = _f1(part, "featrows", u)
            T = _f1(part, "outcomes", u)
            X = F.merge(T, on=["day", "row"], how="inner").merge(br, on=["day", "row"], how="left")
            X["und"] = u
            frames.append(X)
    X = pd.concat(frames, ignore_index=True)
    X["day"] = pd.to_datetime(X.day)
    X = X[X.day.dt.date < HOLDOUT[0]]
    X = add_targets(X)
    parts = []
    for u in UNDS:
        Xu = X[X.und == u]
        parts.append(day_targets(u, Xu))
    X = X.join(pd.concat(parts))
    X = X.sort_values(["day", "und", "row"]).reset_index(drop=True)
    OUT.mkdir(parents=True, exist_ok=True)
    q = OUT / "design.parquet"
    X.to_parquet(q, compression="zstd", index=False)
    return q


def load(part: str = "design") -> pd.DataFrame:
    if part == "holdout":
        if not HOLD_FLAG.exists():
            raise PermissionError("B2's holdout is read only by `study.py holdout`")
        return pd.read_parquet(OUT / "holdout.parquet")
    X = pd.read_parquet(OUT / "design.parquet")
    assert X.day.dt.date.max() < HOLDOUT[0]
    return X


def feature_cols(X: pd.DataFrame) -> list[str]:
    """Every point-in-time input: F1's features, breadth, the row (time of day), dte, expiry. Never an outcome or target."""
    banned = set(TARGETS) | OUTCOME_COLS | {"DAYG"} | {c for c in X.columns if c.startswith(("net_", "why_", "cut_"))}
    cols = [c for c in X.columns if c not in banned and c not in {"day", "und"} and pd.api.types.is_numeric_dtype(X[c])]
    return cols
