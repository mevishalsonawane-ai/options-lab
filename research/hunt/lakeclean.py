"""Lake option-row cleaning shared by R51 Amendment A and R52 Amendment 2 (15 Oct 2026; R47's two data fixes).

1. Foreign rows (R47 Amendment 2, research/hunt/r47/r47data.py foreign_rows): the lake's rolling option files carry rows
   of ANOTHER underlying (FINNIFTY files hold rows at MIDCPNIFTY's level, ...). A row is foreign when its own `spot` is
   more than FOREIGN_TOL from the index close at that minute (the session's last earlier close if that minute has
   none); with no index close at all that session it is foreign too.
2. Stray prints (R47 Amendment 1 thresholds, r47price.py): per contract (side, strike) and session, on the rows left
   after step 1, ref = the median of the closes of the row before, the row and the row after (its own close at the
   ends). A row whose open is more than 2 ticks AND more than BAD_PRINT from ref is dropped; a high more than BAD_PRINT
   above ref is capped at ref x (1 + BAD_PRINT). Lows are left alone (a stray low can only hurt a buyer).
3. Stale repeated prints (18 Oct 2026, research/hunt/lakestale/PREREG.md; only when `stale=True`, so every committed
   result before it reproduces unchanged). R77 found the FINNIFTY 24650 CE on 28 Sep 2026 at Rs 1,855.85 for 13 minutes
   with zero volume (open = high = low = close, the same price every minute) while it really traded near Rs 192; step 2
   cannot see it because its neighbours repeat the same wrong price. On the rows left after step 2, per contract and
   session (the first row of each minute, as the store keeps it), a STALE RUN is a stretch of at least STALE_MIN_RUN
   consecutive rows that are flat (O = H = L = C), have zero volume and repeat one price. A row of such a run is dropped
   when its price is outside the no-arbitrage band set by the nearest lower and higher strikes of the same side that
   day, read at that minute (calls: C(K_hi) <= C(K) <= min(C(K_lo), C(K_hi) + K_hi - K); puts mirrored), by more than
   STALE_TOL (50%) AND 2 ticks. A neighbour's price is the close of its latest TRADED row (volume > 0) at or before the
   minute, at most STALE_AGE minutes old. With no usable neighbour on a side, that side has no bound and the row is kept.
   Look-ahead: the band uses only rows at or before the minute; the run length is two-sided (offline repair of the data,
   as step 2's median already reads the row after). Signals never read these rows: every study's signal is on the
   index, and the ATM label and expiry test read the rows before step 2, so step 3 changes option prices only.

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
STALE_MIN_RUN = 5          # rows (minutes) a flat zero-volume price must repeat
STALE_TOL = 0.50           # how far outside the neighbours' band (and more than 2 ticks)
STALE_AGE = 15             # minutes: oldest neighbour trade used for the band


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


def _contract_order(df: pd.DataFrame):
    """(positions of the first row of each (day, side, strike, r) in the frame's order, sorted by contract then minute;
    contract codes of those rows). The first row is the one r51data's store keeps."""
    if not len(df):
        return np.zeros(0, dtype=np.int64), np.zeros(0, dtype=np.int64)
    side = df.side.astype(str).to_numpy()
    k = pd.MultiIndex.from_arrays([df.day.to_numpy(), side, df.strike.to_numpy(np.float64), df.r.to_numpy()])
    first = np.flatnonzero(~k.duplicated(keep="first"))
    ordn = np.array([d.toordinal() for d in df.day.to_numpy()[first]], dtype=np.int64)
    o = np.lexsort((df.r.to_numpy()[first], df.strike.to_numpy(np.float64)[first], side[first], ordn))
    pos = first[o]
    code = pd.factorize(pd.MultiIndex.from_arrays([df.day.to_numpy()[pos], side[pos],
                                                   df.strike.to_numpy(np.float64)[pos]]))[0]
    return pos, code


def stale_runs(df: pd.DataFrame) -> tuple:
    """Per row of the frame (positional): (length of its stale run, its 1-based place in that run); 0, 0 when the row is
    not flat with zero volume, or is a later duplicate of a minute. A run is consecutive rows of one contract and
    session, all flat (O = H = L = C) with zero volume, all at one price."""
    n = len(df)
    length, place = np.zeros(n, dtype=np.int64), np.zeros(n, dtype=np.int64)
    if not n:
        return length, place
    pos, code = _contract_order(df)
    o, h, l, c = (df[x].to_numpy(np.float64)[pos] for x in ("open", "high", "low", "close"))
    v = df.volume.to_numpy(np.float64)[pos]
    flat = (o == h) & (h == l) & (l == c) & (v == 0) & np.isfinite(c)
    cont = np.r_[False, flat[1:] & flat[:-1] & (code[1:] == code[:-1]) & (c[1:] == c[:-1])]
    rid = np.cumsum(~cont)                                   # a new id where a run starts (or any non-run row)
    size = np.bincount(rid)[rid]
    start = np.flatnonzero(~cont)
    plc = np.arange(len(pos)) - start[rid - 1] + 1
    length[pos] = np.where(flat, size, 0)
    place[pos] = np.where(flat, plc, 0)
    return length, place


def neighbour_band(df: pd.DataFrame, at, age: int = STALE_AGE) -> tuple:
    """No-arbitrage band (lo, hi) for the rows at positions `at`, from the nearest lower and higher strike of the same
    side and day: each neighbour's close on its latest traded row (volume > 0) at or before that minute, at most `age`
    minutes old. Calls: lo = C(K_hi), hi = min(C(K_lo), C(K_hi) + K_hi - K); puts: lo = P(K_lo),
    hi = min(P(K_hi), P(K_lo) + K - K_lo). NaN where a side has no usable neighbour. Reads no row after the minute."""
    at = np.asarray(at, dtype=np.int64)
    lo, hi = np.full(len(at), np.nan), np.full(len(at), np.nan)
    if not len(at):
        return lo, hi
    pos, code = _contract_order(df)
    ncode = int(code.max()) + 1
    r = df.r.to_numpy(np.int64)[pos]
    c = df.close.to_numpy(np.float64)[pos]
    v = df.volume.to_numpy(np.float64)[pos]
    first = np.r_[0, np.flatnonzero(code[1:] != code[:-1]) + 1]           # one row per contract, in contract order
    cday, cside = df.day.to_numpy()[pos][first], df.side.astype(str).to_numpy()[pos][first]
    cstrike = df.strike.to_numpy(np.float64)[pos][first]
    same_prev = np.r_[False, (cday[1:] == cday[:-1]) & (cside[1:] == cside[:-1])]
    lower = np.where(same_prev, np.arange(ncode) - 1, -1)                 # next lower strike, same day and side
    higher = np.r_[np.where(same_prev[1:], np.arange(1, ncode), -1), -1]
    need = np.zeros(ncode, dtype=bool)
    code_of = np.empty(len(df), dtype=np.int64)
    code_of[:] = -1
    code_of[pos] = code
    cq = code_of[at]
    for nb in (lower, higher):
        x = nb[cq[cq >= 0]]
        need[x[x >= 0]] = True
    keep = need[code] & (v > 0) & np.isfinite(c) & (r >= 0) & (r < NROWS)
    rows_of = {k: i for i, k in enumerate(np.flatnonzero(need))}
    grid_c = np.full((len(rows_of), NROWS), np.nan)
    grid_r = np.full((len(rows_of), NROWS), np.nan)
    if rows_of:
        gi = np.array([rows_of[k] for k in code[keep]], dtype=np.int64)
        grid_c[gi, r[keep]] = c[keep]
        grid_r[gi, r[keep]] = r[keep]
        grid_c = pd.DataFrame(grid_c).ffill(axis=1).to_numpy()
        grid_r = pd.DataFrame(grid_r).ffill(axis=1).to_numpy()
    ra = df.r.to_numpy(np.int64)[at]
    ka = df.strike.to_numpy(np.float64)[at]
    sa = df.side.astype(str).to_numpy()[at]

    def price(nb):
        out = np.full(len(at), np.nan)
        for j in range(len(at)):
            if cq[j] < 0 or not (0 <= ra[j] < NROWS):
                continue
            k = nb[cq[j]]
            if k < 0 or k not in rows_of:
                continue
            g = rows_of[k]
            if np.isfinite(grid_r[g, ra[j]]) and ra[j] - grid_r[g, ra[j]] <= age:
                out[j] = grid_c[g, ra[j]]
        return out, np.array([cstrike[nb[q]] if q >= 0 and nb[q] >= 0 else np.nan for q in cq])

    p_lo, k_lo = price(lower)
    p_hi, k_hi = price(higher)
    call = sa == "C"
    with np.errstate(all="ignore"):
        lo = np.where(call, p_hi, p_lo)
        hi = np.where(call, np.fmin(p_lo, p_hi + (k_hi - ka)), np.fmin(p_hi, p_lo + (ka - k_lo)))
    return lo, hi


def stale_mask(df: pd.DataFrame, min_run: int = STALE_MIN_RUN, tol: float = STALE_TOL, age: int = STALE_AGE,
               tick: float = TICK, causal: bool = False) -> np.ndarray:
    """True for rows of a stale run (length >= min_run; with causal=True, rows at place >= min_run of their run, so only
    the rows before them count) whose price is outside the neighbours' band by more than tol and 2 ticks."""
    out = np.zeros(len(df), dtype=bool)
    if not len(df):
        return out
    length, place = stale_runs(df)
    cand = np.flatnonzero((place if causal else length) >= min_run)
    if not len(cand):
        return out
    lo, hi = neighbour_band(df, cand, age)
    p = df.close.to_numpy(np.float64)[cand]
    with np.errstate(all="ignore"):
        above = np.isfinite(hi) & (p > hi * (1.0 + tol) + 2 * tick + 1e-9)
        below = np.isfinite(lo) & (p < lo / (1.0 + tol) - 2 * tick - 1e-9)
    out[cand[above | below]] = True
    return out


def drop_stale(df: pd.DataFrame, **kw):
    """(rows kept, stale rows dropped). Later duplicates of a dropped minute go too (the store would keep none)."""
    if not len(df):
        return df, df.iloc[:0]
    m = stale_mask(df, **kw)
    if m.any():
        k = pd.MultiIndex.from_arrays([df.day.to_numpy(), df.side.astype(str).to_numpy(),
                                       df.strike.to_numpy(np.float64), df.r.to_numpy()])
        m = k.isin(k[m])
    return df[~m], df[m]


def clean(df: pd.DataFrame, closes: dict, tol: float = FOREIGN_TOL, tick: float = TICK, bad: float = BAD_PRINT,
          stale: bool = False):
    """Steps 1-2 (and 3 when `stale`). Returns (cleaned frame, after-step-1 frame, log frame of every touched row with
    `why` in {'foreign', 'stray_open', 'high_capped', 'stale_run'})."""
    a, foreign = drop_foreign(df, closes, tol)
    b, stray, capped = fix_stray(a, tick, bad)
    cols = ["day", "r", "side", "strike"]
    parts = [foreign[cols].assign(why="foreign"), stray[cols].assign(why="stray_open"),
             capped[cols].assign(why="high_capped")]
    if stale:
        b, st = drop_stale(b, tick=tick)
        parts.append(st[cols + ["close"]].assign(why="stale_run"))
    log = pd.concat(parts, ignore_index=True)
    return b, a, log


def counts(log: pd.DataFrame, total: int) -> dict:
    v = log.why.value_counts() if len(log) else pd.Series(dtype=int)
    out = dict(rows=int(total), foreign=int(v.get("foreign", 0)), stray_open=int(v.get("stray_open", 0)),
               high_capped=int(v.get("high_capped", 0)))
    if v.get("stale_run", 0):
        out["stale_run"] = int(v["stale_run"])
    return out


WHYS = ("foreign", "stray_open", "high_capped", "stale_run")


def touched(trades: pd.DataFrame, log: pd.DataFrame, clean_keys=None) -> pd.DataFrame:
    """Per trade (columns day, side, strike, er, xr: entry and exit rows), whether a logged row of its contract falls
    in [er, xr], by reason; `foreign_contract` when `clean_keys` (a set of (day, side, strike) that still have rows
    after cleaning) does not hold the trade's contract. Returns the trades with boolean columns added."""
    T = trades.copy()
    for w in WHYS:
        T[w] = False
    if len(T) and len(log):
        g = {k: x for k, x in log.groupby(["day", "side", "strike"])}
        for i, t in enumerate(T.itertuples(index=False)):
            x = g.get((t.day, t.side, float(t.strike)))
            if x is None:
                continue
            w = x.why[(x.r >= t.er) & (x.r <= t.xr)].unique()
            for k in w:
                if k in WHYS:
                    T.iloc[i, T.columns.get_loc(k)] = True
    T["foreign_contract"] = False
    if clean_keys is not None and len(T):
        T["foreign_contract"] = [(d, s, float(k)) not in clean_keys for d, s, k in zip(T.day, T.side, T.strike)]
    T["any"] = T[list(WHYS) + ["foreign_contract"]].any(axis=1)
    return T
