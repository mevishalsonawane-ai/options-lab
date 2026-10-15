"""Lake option-row cleaning shared by R51 Amendment A and R52 Amendment 2 (15 Oct 2026; R47's two data fixes).

1. Foreign rows (R47 Amendment 2, research/hunt/r47/r47data.py foreign_rows): the lake's rolling option files carry rows
   of ANOTHER underlying (FINNIFTY files hold rows at MIDCPNIFTY's level, ...). A row is foreign when its own `spot` is
   more than FOREIGN_TOL from the index close at that minute (the session's last earlier close if that minute has
   none); with no index close at all that session it is foreign too.
2. Stray prints (R47 Amendment 1 thresholds, r47price.py): per contract (side, strike) and session, on the rows left
   after step 1, ref = the median of the closes of the row before, the row and the row after (its own close at the
   ends). A row whose open is more than 2 ticks AND more than BAD_PRINT from ref is dropped; a high more than BAD_PRINT
   above ref is capped at ref x (1 + BAD_PRINT). Lows are left alone (a stray low can only hurt a buyer).

Frames carry `day` (datetime.date), `r` (minute row, 0 = 09:15), `side` ('C'/'P'), `strike`, `spot`, open/high/low/close.
Nothing here reads files: callers pass the index closes they already hold.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

FOREIGN_TOL = 0.03
BAD_PRINT = 0.20
TICK = 0.05
NROWS = 375


def ref_closes(closes: dict, days, rows) -> np.ndarray:
    """Index close for each (day, row): that minute's close, else the session's last earlier close, else NaN.
    closes: {day: (NROWS,) index closes, NaN where no minute}."""
    days = np.asarray(days, dtype=object)
    rows = np.asarray(rows, dtype=np.int64)
    out = np.full(len(rows), np.nan)
    if not len(rows) or not closes:
        return out
    keys = list(closes)
    pos_of = {d: i for i, d in enumerate(keys)}
    width = max(len(np.asarray(closes[d])) for d in keys)
    grid = np.full((len(keys), width), np.nan)
    for i, d in enumerate(keys):
        c = np.asarray(closes[d], dtype=np.float64)
        grid[i, :len(c)] = c
    grid = pd.DataFrame(grid).ffill(axis=1).to_numpy()
    pos = pd.Series(days).map(pos_of).to_numpy(dtype=np.float64)
    ok = np.isfinite(pos) & (rows >= 0) & (rows < width)
    out[ok] = grid[pos[ok].astype(np.int64), rows[ok]]
    return out


def foreign_mask(spot, ref, tol: float = FOREIGN_TOL) -> np.ndarray:
    """True where the row's own spot is more than `tol` from the index reference (or either is missing)."""
    with np.errstate(all="ignore"):
        x = np.asarray(spot, dtype=np.float64) / np.asarray(ref, dtype=np.float64)
    return ~(np.isfinite(x) & (np.abs(x - 1.0) <= tol))


def stray_ref(df: pd.DataFrame) -> np.ndarray:
    """Median of the closes of the row before, the row and the row after, within each (day, side, strike) contract,
    rows in minute order (positional: the frame's own order is kept in the output)."""
    if not len(df):
        return np.zeros(0)
    order = np.lexsort((df.r.to_numpy(), df.strike.to_numpy(), df.side.astype(str).to_numpy(),
                        np.array([d.toordinal() for d in df.day], dtype=np.int64)))
    s = df.iloc[order]
    c = s.close.to_numpy(np.float64)
    key = pd.MultiIndex.from_arrays([s.day.to_numpy(), s.side.astype(str).to_numpy(), s.strike.to_numpy()])
    codes = pd.factorize(key)[0]
    same_prev = np.r_[False, codes[1:] == codes[:-1]]
    same_next = np.r_[codes[:-1] == codes[1:], False]
    a = np.where(same_prev, np.r_[np.nan, c[:-1]], c)
    b = np.where(same_next, np.r_[c[1:], np.nan], c)
    med = np.maximum(np.minimum(a, c), np.minimum(np.maximum(a, c), b))
    out = np.empty(len(df))
    out[order] = med
    return out


def stray_open_mask(opn, ref, tick: float = TICK, bad: float = BAD_PRINT) -> np.ndarray:
    o = np.asarray(opn, dtype=np.float64)
    r = np.asarray(ref, dtype=np.float64)
    with np.errstate(all="ignore"):
        far = np.abs(o - r) > 2 * tick + 1e-9
        rel = (r <= 0) | (np.abs(o / r - 1.0) > bad)
    return far & rel


def drop_foreign(df: pd.DataFrame, closes: dict, tol: float = FOREIGN_TOL):
    """(rows kept, foreign rows) of an option frame."""
    if not len(df):
        return df, df.iloc[:0]
    bad = foreign_mask(df.spot.to_numpy(), ref_closes(closes, df.day.to_numpy(), df.r.to_numpy()), tol)
    return df[~bad], df[bad]


def fix_stray(df: pd.DataFrame, tick: float = TICK, bad: float = BAD_PRINT):
    """(rows kept with highs capped, dropped stray-open rows, capped rows as they were before the cap)."""
    if not len(df):
        return df, df.iloc[:0], df.iloc[:0]
    ref = stray_ref(df)
    so = stray_open_mask(df.open.to_numpy(), ref, tick, bad)
    cap = ref * (1.0 + bad)
    hi = df.high.to_numpy(np.float64)
    capped = ~so & np.isfinite(cap) & (cap > 0) & (hi > cap + 1e-9)
    keep = df[~so].copy()
    keep_cap = capped[~so]
    if keep_cap.any():
        h = keep.high.to_numpy(np.float64).copy()
        h[keep_cap] = cap[~so][keep_cap]
        keep["high"] = h.astype(keep.high.dtype)
    return keep, df[so], df[capped]


def clean(df: pd.DataFrame, closes: dict, tol: float = FOREIGN_TOL, tick: float = TICK, bad: float = BAD_PRINT):
    """Both steps. Returns (cleaned frame, after-step-1 frame, log frame of every touched row with `why` in
    {'foreign', 'stray_open', 'high_capped'})."""
    a, foreign = drop_foreign(df, closes, tol)
    b, stray, capped = fix_stray(a, tick, bad)
    cols = ["day", "r", "side", "strike"]
    log = pd.concat([foreign[cols].assign(why="foreign"), stray[cols].assign(why="stray_open"),
                     capped[cols].assign(why="high_capped")], ignore_index=True)
    return b, a, log


def counts(log: pd.DataFrame, total: int) -> dict:
    v = log.why.value_counts() if len(log) else pd.Series(dtype=int)
    return dict(rows=int(total), foreign=int(v.get("foreign", 0)), stray_open=int(v.get("stray_open", 0)),
                high_capped=int(v.get("high_capped", 0)))


def touched(trades: pd.DataFrame, log: pd.DataFrame, clean_keys=None) -> pd.DataFrame:
    """Per trade (columns day, side, strike, er, xr: entry and exit rows), whether a logged row of its contract falls
    in [er, xr], by reason; `foreign_contract` when `clean_keys` (a set of (day, side, strike) that still have rows
    after cleaning) does not hold the trade's contract. Returns the trades with boolean columns added."""
    T = trades.copy()
    for w in ("foreign", "stray_open", "high_capped"):
        T[w] = False
    if len(T) and len(log):
        g = {k: x for k, x in log.groupby(["day", "side", "strike"])}
        for i, t in enumerate(T.itertuples(index=False)):
            x = g.get((t.day, t.side, float(t.strike)))
            if x is None:
                continue
            w = x.why[(x.r >= t.er) & (x.r <= t.xr)].unique()
            for k in w:
                T.iloc[i, T.columns.get_loc(k)] = True
    T["foreign_contract"] = False
    if clean_keys is not None and len(T):
        T["foreign_contract"] = [(d, s, float(k)) not in clean_keys for d, s, k in zip(T.day, T.side, T.strike)]
    T["any"] = T[["foreign", "stray_open", "high_capped", "foreign_contract"]].any(axis=1)
    return T
