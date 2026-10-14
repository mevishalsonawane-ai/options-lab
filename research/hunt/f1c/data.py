"""F1C data access: the crypto brain's features + labels at decision rows, the perp minute bars, and the holdout lock.

Every reader takes a `part` ("design" or "holdout"). Design readers drop every row whose [t, t + horizon] is not inside
a design span (f1ccfg.DESIGN), so no design number ever uses a holdout or locked minute. The holdout is opened only
through `read_holdout(idea)`, once per idea, logged in <cache>/f1c/HOLDOUT_READ.json.
"""
from __future__ import annotations

import json
from datetime import date, datetime, timezone

import numpy as np
import pandas as pd

from research.brain.crypto import cube, labels
from research.brain.crypto.features import store

from . import f1ccfg as C


class HoldoutError(RuntimeError):
    pass


def holdout_reads() -> dict:
    return json.loads(C.HOLD_LOG.read_text()) if C.HOLD_LOG.exists() else {}


def read_holdout(idea: str) -> None:
    """Record the one holdout read of `idea`; a second read raises."""
    reads = holdout_reads()
    if idea in reads:
        raise HoldoutError(f"holdout already read for {idea} on {reads[idea]}")
    reads[idea] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    C.HOLD_LOG.parent.mkdir(parents=True, exist_ok=True)
    C.HOLD_LOG.write_text(json.dumps(reads, indent=1))


def keep(ts, part: str, horizon_min: int) -> np.ndarray:
    if part == "design":
        return np.asarray(C.is_design(ts, horizon_min))
    if part == "holdout":
        return np.asarray(C.is_holdout(ts, horizon_min))
    raise ValueError(part)


def _span(part: str) -> tuple[date, date]:
    if part == "design":
        return C.DESIGN[0][0].date(), (C.DESIGN[1][1] - pd.Timedelta(days=1)).date()
    return C.HOLDOUT[0].date(), (C.HOLDOUT[1] - pd.Timedelta(days=1)).date()


def rows(sym: str, step: int, part: str = "design", horizon_min: int = 240, columns=None) -> pd.DataFrame:
    """Features and labels at decision rows t with (minute + 1) % step == 0 (the bar closing on a step boundary),
    restricted to `part` with the label window [t, t + horizon_min] inside it."""
    root = C.CACHE.root
    a, b = _span(part)
    out = []
    for d in pd.date_range(a, b, freq="MS"):
        lo, hi = d.date(), (d + pd.offsets.MonthEnd(0)).date()
        F = store.load(root, sym, lo, hi, columns=columns)
        if F.empty:
            continue
        mins = F.ts.dt.hour * 60 + F.ts.dt.minute
        F = F[((mins + 1) % step == 0).to_numpy()]
        L = labels.load(root, sym, lo, hi)
        if L.empty:
            continue
        F = F.merge(L, on="ts", how="inner")
        out.append(F[keep(F.ts, part, horizon_min)])
    return pd.concat(out, ignore_index=True) if out else pd.DataFrame()


BAR_COLS = ["ts", "perp_open", "perp_high", "perp_low", "perp_close", "perp_flag", "close", "flag", "funding_rate"]


def bars(sym: str, frm: date, to: date) -> pd.DataFrame:
    """Perp (and spot) minute bars from the cube, on the full minute grid (missing minutes as NaN rows)."""
    M = cube.load(C.CACHE.root, sym, frm, to)
    if M.empty:
        return M
    M = M[BAR_COLS].sort_values("ts").drop_duplicates("ts")
    g = pd.date_range(M.ts.iloc[0], M.ts.iloc[-1], freq="min")
    M = M.set_index("ts").reindex(g).rename_axis("ts").reset_index()
    bad = ~M.perp_flag.isin(["ok", "stale"])
    for c in ("perp_open", "perp_high", "perp_low", "perp_close"):
        M.loc[bad, c] = np.nan
    return M
