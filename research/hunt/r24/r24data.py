"""R24 data assembly: the what-if table (labels) and the brain's features (inputs), kept apart until a study joins them
on the decision minute. Research only, 13 Oct 2026.

    minute_frame(und)   one row per (session, entry minute 09:20..14:45, side): the features at the decision minute
                        (entry - 1) and their side-aligned values. FEATURES ONLY.
    whatif(unds, cols)  the what-if rows (outcomes), from <cache>/whatif.
    key(...)            one int64 per (index, session, entry minute, side, offset) to look outcomes up.
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

import items as I
import r24cfg as K
from research.brain.features import store

UND_I = {u: i for i, u in enumerate(K.UNDS)}


def key(und_i, day_ord, entry_row, side, offset):
    """int64 key of one option row. day_ord: date.toordinal()."""
    return ((((np.asarray(und_i, np.int64) * 1_000_000 + np.asarray(day_ord, np.int64)) * 400
              + np.asarray(entry_row, np.int64)) * 2 + (np.asarray(side) > 0)) * 8 + (np.asarray(offset, np.int64) + 3))


def whatif(unds=K.UNDS, cols=None, frm: date | None = None, to: date | None = None, series: str = "WEEK") -> pd.DataFrame:
    """series: WEEK (the nearest expiry, <cache>/whatif/<UND>/) or MONTH (<cache>/whatif/<UND>_MONTH/)."""
    parts = []
    for u in unds:
        for p in sorted((K.WHATIF / (u if series == "WEEK" else f"{u}_MONTH")).glob("*.parquet")):
            y = int(p.stem)
            if (frm and y < frm.year) or (to and y > to.year):
                continue
            parts.append(pd.read_parquet(p, columns=cols))
    if not parts:
        return pd.DataFrame()
    t = pd.concat(parts, ignore_index=True)
    t["d"] = pd.to_datetime(t.day).dt.date
    if frm:
        t = t[t.d >= frm]
    if to:
        t = t[t.d <= to]
    t["period"] = t.d.map(K.period_of)
    t["key"] = key(t.und.map(UND_I).values, [d.toordinal() for d in t.d], t.entry_row.values, t.side.values,
                   t.offset.values)
    return t.reset_index(drop=True)


def minute_frame(und: str) -> pd.DataFrame:
    """Features at the decision minute for every entry minute 09:20..14:45, both sides (FEATURES ONLY, no outcome)."""
    return frame_from(store.load(K.CACHE.root, und), und)


def frame_from(F: pd.DataFrame, und: str) -> pd.DataFrame:
    """Feature-store rows -> the minute frame. The entry minute is the decision minute + 1: a feature known at the close
    of minute t is used for an order that fills from minute t + 1 on."""
    F = F[["ts"] + I.FEATURES + ["minute_of_day"]].copy()
    F = F[(F.minute_of_day >= K.ENTRY_FIRST - 1) & (F.minute_of_day <= K.ENTRY_LAST - 1)]
    F = F.reset_index(drop=True)
    n = len(F)
    two = pd.concat([F, F], ignore_index=True)
    two["side"] = np.r_[np.ones(n, np.int8), -np.ones(n, np.int8)]
    two["und"] = und
    two["d"] = pd.to_datetime(two.ts).dt.date
    two["entry_row"] = (two.minute_of_day + 1).astype(np.int16)
    two["period"] = two.d.map(K.period_of)
    return two


def frames(unds=K.UNDS) -> pd.DataFrame:
    return pd.concat([minute_frame(u) for u in unds], ignore_index=True)


def design_blocks(days: list[date], n: int = K.N_BLOCKS) -> list[tuple[date, date]]:
    """n consecutive blocks of the design sessions (by date, both indices together)."""
    ds = sorted(d for d in set(days) if K.period_of(d) == "design")
    parts = np.array_split(np.arange(len(ds)), n)
    return [(ds[p[0]], ds[p[-1]]) for p in parts if len(p)]


def block_of(d: date, blocks) -> int:
    for i, (a, b) in enumerate(blocks):
        if a <= d <= b:
            return i
    return -1
