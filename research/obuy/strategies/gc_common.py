"""Shared helpers for the gc_* catalog strategies (expiry_day, oi_sentiment, volatility_long, event, overnight_positional):
an EVENT CALENDAR, cached per-day features, time-weighted VWAP, daily trend indicators, max pain, multi-day exit days.

EVENT CALENDAR - built by hand from memory for this study (NOT downloaded, NOT verified against official releases).
  * Union Budget (speech ~11:00 IST), RBI MPC policy decisions (10:00 IST; the May 2022 off-cycle hike 14:00),
    general / major state election counting days (counting from 08:00 IST, before the open; weekend results hit the
    next session), US FOMC decisions (US date; ~23:30-00:30 IST, so they hit the NEXT Indian session).
    These dates are well documented and believed correct; the 2026 RBI dates after February are the scheduled
    meetings as recalled and are the least certain.
  * Quarterly results of index heavyweights (Reliance, HDFC Bank, Infosys, TCS): APPROXIMATE dates recalled from
    memory (may be off by a day or two in places); all announced after market hours or on weekends, so they hit the
    next session. Used only where a strategy's grid says so (event set 'all').
"""
from __future__ import annotations

import hashlib
import inspect
import os
import pickle
from datetime import date

import numpy as np
import pandas as pd

from .. import config as C

# (date, kind, announcement): 'HH:MM' = during the session that day, 'pre' = before that day's open,
# 'post' = after that date's close (or a holiday / weekend) -> hits the next session.
_BUDGET = ["2021-02-01", "2022-02-01", "2023-02-01", "2024-02-01", "2024-07-23", "2025-02-01", "2026-02-01"]
_RBI = ["2020-08-06", "2020-10-09", "2020-12-04",
        "2021-02-05", "2021-04-07", "2021-06-04", "2021-08-06", "2021-10-08", "2021-12-08",
        "2022-02-10", "2022-04-08", "2022-06-08", "2022-08-05", "2022-09-30", "2022-12-07",
        "2023-02-08", "2023-04-06", "2023-06-08", "2023-08-10", "2023-10-06", "2023-12-08",
        "2024-02-08", "2024-04-05", "2024-06-07", "2024-08-08", "2024-10-09", "2024-12-06",
        "2025-02-07", "2025-04-09", "2025-06-06", "2025-08-06", "2025-10-01", "2025-12-05",
        "2026-02-06", "2026-04-08", "2026-06-05", "2026-08-07", "2026-10-01"]
_ELECTION = [("2021-05-02", "pre"), ("2022-03-10", "pre"), ("2022-12-08", "pre"), ("2023-05-13", "pre"),
             ("2023-12-03", "pre"), ("2024-06-01", "post"),   # 2024 general-election exit polls (Sat evening)
             ("2024-06-04", "pre"), ("2024-10-08", "pre"), ("2024-11-23", "pre"), ("2025-02-08", "pre"),
             ("2025-11-14", "pre")]
_FED = ["2020-09-16", "2020-11-05", "2020-12-16",
        "2021-01-27", "2021-03-17", "2021-04-28", "2021-06-16", "2021-07-28", "2021-09-22", "2021-11-03", "2021-12-15",
        "2022-01-26", "2022-03-16", "2022-05-04", "2022-06-15", "2022-07-27", "2022-09-21", "2022-11-02", "2022-12-14",
        "2023-02-01", "2023-03-22", "2023-05-03", "2023-06-14", "2023-07-26", "2023-09-20", "2023-11-01", "2023-12-13",
        "2024-01-31", "2024-03-20", "2024-05-01", "2024-06-12", "2024-07-31", "2024-09-18", "2024-11-07", "2024-12-18",
        "2025-01-29", "2025-03-19", "2025-05-07", "2025-06-18", "2025-07-30", "2025-09-17", "2025-10-29", "2025-12-10",
        "2026-01-28", "2026-03-18", "2026-04-29", "2026-06-17", "2026-07-29", "2026-09-16"]
# APPROXIMATE (from memory): announcement dates, all after hours / weekends.
_RESULTS = {
    "RELIANCE": ["2021-01-22", "2021-04-30", "2021-07-23", "2021-10-22", "2022-01-21", "2022-05-06", "2022-07-22",
                 "2022-10-21", "2023-01-20", "2023-04-21", "2023-07-21", "2023-10-27", "2024-01-19", "2024-04-22",
                 "2024-07-19", "2024-10-14", "2025-01-16", "2025-04-25", "2025-07-18", "2025-10-17"],
    "HDFCBANK": ["2021-01-16", "2021-04-17", "2021-07-17", "2021-10-16", "2022-01-15", "2022-04-16", "2022-07-16",
                 "2022-10-15", "2023-01-14", "2023-04-15", "2023-07-17", "2023-10-16", "2024-01-16", "2024-04-20",
                 "2024-07-20", "2024-10-19", "2025-01-22", "2025-04-19", "2025-07-19", "2025-10-18"],
    "INFY": ["2021-01-13", "2021-04-14", "2021-07-14", "2021-10-13", "2022-01-12", "2022-04-13", "2022-07-24",
             "2022-10-13", "2023-01-12", "2023-04-13", "2023-07-20", "2023-10-12", "2024-01-11", "2024-04-18",
             "2024-07-18", "2024-10-17", "2025-01-16", "2025-04-17", "2025-07-23", "2025-10-16"],
    "TCS": ["2021-01-08", "2021-04-12", "2021-07-08", "2021-10-08", "2022-01-12", "2022-04-11", "2022-07-08",
            "2022-10-10", "2023-01-09", "2023-04-12", "2023-07-12", "2023-10-11", "2024-01-11", "2024-04-12",
            "2024-07-11", "2024-10-10", "2025-01-09", "2025-04-10", "2025-07-10", "2025-10-09"],
}


def raw_events():
    ev = [(d, "budget", "11:00") for d in _BUDGET]
    ev += [(d, "rbi", "14:00" if d == "2022-05-04" else "10:00") for d in _RBI + ["2022-05-04"]]
    ev += [(d, "election", a) for d, a in _ELECTION]
    ev += [(d, "fed", "post") for d in _FED]
    ev += [(d, f"results_{k}", "post") for k, v in _RESULTS.items() for d in v]
    return sorted(ev)


MACRO = ("budget", "rbi", "election", "fed")


def events(ix, kinds="macro"):
    """Events mapped onto the index's trading days: DataFrame(day = the session the news hits, kind, ann_min = clock
    minute of the announcement within that session (OPEN_M when it came before the open / overnight), intraday flag,
    src = the announcement date). kinds: 'macro' | 'all' | tuple of kinds."""
    days = ix.days
    pos = set(days)
    rows = []
    for ds, kind, a in raw_events():
        if kinds == "macro" and not kind in MACRO:
            continue
        if isinstance(kinds, tuple) and kind not in kinds:
            continue
        d = date.fromisoformat(ds)
        if a == "post":
            i = np.searchsorted(np.array(days, dtype=object), d, side="right")
            if i >= len(days):
                continue
            rows.append(dict(day=days[i], kind=kind, ann_min=C.OPEN_M, intraday=False, src=d))
        elif a == "pre":
            i = np.searchsorted(np.array(days, dtype=object), d, side="left")
            if i >= len(days):
                continue
            rows.append(dict(day=days[i], kind=kind, ann_min=C.OPEN_M, intraday=False, src=d))
        else:
            if d not in pos:
                continue
            h, m = a.split(":")
            rows.append(dict(day=d, kind=kind, ann_min=int(h) * 60 + int(m), intraday=True, src=d))
    df = pd.DataFrame(rows)
    if df.empty:
        return df
    # one row per session: keep the most important event (budget > election > rbi > fed > results)
    pri = {"budget": 0, "election": 1, "rbi": 2, "fed": 3}
    df["pri"] = [pri.get(k, 4) for k in df.kind]
    return df.sort_values(["day", "pri"]).drop_duplicates("day").drop(columns="pri").reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ cached features
def feat_cache(fn, *args, **kw):
    """Disk cache of a per-underlying feature table keyed by the function's source and arguments."""
    src = inspect.getsource(fn)
    key = hashlib.sha1(repr((fn.__module__, fn.__name__, src, args, sorted(kw.items()))).encode()).hexdigest()[:12]
    d = os.path.join(C.CACHE, "gc_feat")
    os.makedirs(d, exist_ok=True)
    p = os.path.join(d, f"{fn.__name__}_{key}.pkl")
    if os.path.exists(p):
        with open(p, "rb") as f:
            return pickle.load(f)
    out = fn(*args, **kw)
    with open(p, "wb") as f:
        pickle.dump(out, f, protocol=4)
    return out


# ------------------------------------------------------------------------------------------------ price helpers
def ffill(a):
    """Forward-fill NaNs along the last axis."""
    a = np.asarray(a, float)
    if a.ndim == 1:
        return ffill(a[None, :])[0]
    idx = np.where(~np.isnan(a), np.arange(a.shape[-1])[None, :], 0)
    np.maximum.accumulate(idx, axis=1, out=idx)
    return np.take_along_axis(a, idx, axis=1)    # leading NaNs stay NaN (index 0 is NaN there)


def twap(h, l, c):
    """Time-weighted 'VWAP' (no index volume in the data): running mean of the typical price."""
    tp = (h + l + c) / 3.0
    ok = ~np.isnan(tp)
    cs = np.cumsum(np.where(ok, tp, 0.0))
    n = np.cumsum(ok)
    with np.errstate(all="ignore"):
        return np.where(n > 0, cs / np.maximum(n, 1), np.nan)


def last_valid(a, col):
    x = a[: col + 1]
    ok = np.nonzero(~np.isnan(x))[0]
    return x[ok[-1]] if len(ok) else np.nan


def atm(spot, step):
    return int(np.floor(spot / step + 0.5) * step)


def straddle_series(ch, sp, step, fixed=None):
    """Per-minute ATM straddle (CE+PE closes, last print in the last 3 minutes) on dense spot sp; fixed strike if given."""
    out = np.full(C.W, np.nan)
    cc, pc = ffill_recent(ch.c["C"]), ffill_recent(ch.c["P"])
    for t in range(C.W):
        s = sp[t]
        if np.isnan(s):
            continue
        k = fixed if fixed is not None else atm(s, step)
        i = ch.kpos(k)
        if i < 0:
            continue
        a, b = cc[i, t], pc[i, t]
        if np.isfinite(a) and np.isfinite(b):
            out[t] = a + b
    return out


def ffill_recent(a, lim=3):
    """Forward-fill along minutes, at most `lim` minutes old."""
    a = np.asarray(a, float)
    cols = np.arange(a.shape[1])[None, :]
    idx = np.where(~np.isnan(a), cols, -10**6)
    np.maximum.accumulate(idx, axis=1, out=idx)
    ok = (cols - idx) < lim
    out = np.take_along_axis(a, np.maximum(idx, 0), axis=1)
    return np.where(ok & (idx >= 0), out, np.nan)


def ema(x, n):
    return pd.Series(x).ewm(span=n, adjust=False).mean().values


def supertrend(dl, n=10, mult=3.0):
    """Daily Supertrend direction (+1 / -1) on a daily OHLC frame (index = day)."""
    h, l, c = dl.high.values, dl.low.values, dl.close.values
    pc = np.r_[np.nan, c[:-1]]
    tr = np.nanmax(np.vstack([h - l, np.abs(h - pc), np.abs(l - pc)]), axis=0)
    atr = pd.Series(tr).ewm(alpha=1.0 / n, adjust=False).mean().values
    hl2 = (h + l) / 2
    ub, lb = hl2 + mult * atr, hl2 - mult * atr
    fu, fl = ub.copy(), lb.copy()
    d = np.ones(len(c), int)
    for i in range(1, len(c)):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
        if d[i - 1] == -1 and c[i] > fu[i - 1]:
            d[i] = 1
        elif d[i - 1] == 1 and c[i] < fl[i - 1]:
            d[i] = -1
        else:
            d[i] = d[i - 1]
    return pd.Series(d, index=dl.index)


def max_pain(ch, col):
    """Max-pain strike from OI at column col over the strikes in the data (ATM+-10): the strike minimising the total
    intrinsic value paid to option holders."""
    K = ch.K.astype(float)
    oc = ffill(ch.oi["C"])[:, col]
    op = ffill(ch.oi["P"])[:, col]
    ok = np.isfinite(oc) & np.isfinite(op)
    if ok.sum() < 5:
        return None
    K, oc, op = K[ok], oc[ok], op[ok]
    pain = [(np.maximum(s - K, 0) * oc).sum() + (np.maximum(K - s, 0) * op).sum() for s in K]
    return float(K[int(np.argmin(pain))])


def nth_session(ix, d, n):
    """The n-th trading day after d (n may be negative); None outside the data."""
    p = ix.pos.get(d)
    if p is None:
        return None
    q = p + n
    return ix.days[q] if 0 <= q < len(ix.days) else None


def sessions_between(ix, a, b):
    return ix.pos[b] - ix.pos[a]
