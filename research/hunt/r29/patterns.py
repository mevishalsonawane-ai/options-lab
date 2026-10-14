"""R29 pattern events (PREREG.md Step 1 table, P1-P12) from the Data Brain ribbon features.

An event is (row, sign) on the close of its bar: row = the index-minute row of the bar's last minute (known at the end
of that minute; the trade fills at the next minute's open). Every quantity comes from `ribbon.ribbon_bars` (the same
code the feature store runs), mapped to rows with `bars.to_rows` (the last COMPLETE bar at or before the row).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import r29cfg as K
from research.brain.features import ribbon as RB
from research.brain.features.bars import to_rows
from research.brain.features.registry import Ctx

PATTERNS = (
    [(f"P{i}_{tf}", tf) for i in range(1, 8) for tf in K.TFS]
    + [("P8_1", 1), ("P9_5", 5), ("P10_1", 1), ("P11_5", 5)]
    + [(f"P12_{tf}", tf) for tf in (1, 3, 5)]
)
assert len(PATTERNS) == 35


def onset(state: np.ndarray, want_full: bool = True) -> np.ndarray:
    """+1 / -1 on the bar where `state` becomes +1 / -1 from something else (a finite other value); 0 otherwise."""
    prev = np.r_[np.nan, state[:-1]]
    ok = np.isfinite(prev) & np.isfinite(state) & (np.abs(state) == 1) & (state != prev)
    return np.where(ok, state, 0.0)


def bar_events(b: pd.DataFrame, cb: pd.DataFrame) -> dict[int, np.ndarray]:
    """Single-timeframe events on one bar size: {pattern number: signed array over b's bars}."""
    st = b["stack"].values
    wc = b["width_chg"].values
    prev_wc = np.r_[np.nan, wc[:-1]]
    p7 = (np.abs(st) == 1) & np.isfinite(prev_wc) & (prev_wc <= 0) & (wc > 0)
    sq = b["sqz_break"].values
    return {
        1: onset(st),
        2: onset(cb["stack"].values),
        3: np.nan_to_num(b["twist"].values),
        4: np.nan_to_num(b["pullback"].values),
        5: onset(np.nan_to_num(sq) if np.isfinite(sq).any() else sq),
        6: onset(b["pos"].values),
        7: np.where(p7, st, 0.0),
    }


def events(M: pd.DataFrame, und: str) -> pd.DataFrame:
    """Every P1-P12 event of one underlying's index minutes. Columns: pattern, tf, row, day, m, sign, sess."""
    ctx = Ctx(M, None, und)
    rows_of = {}
    out = []
    rb = {tf: RB.ribbon_bars(ctx, tf, RB.RIB) for tf in K.TFS}
    cb = {tf: RB.ribbon_bars(ctx, tf, RB.CLS) for tf in K.TFS}
    for tf in K.TFS:
        b = rb[tf]
        if not len(b):
            continue
        for p, s in bar_events(b, cb[tf]).items():
            j = np.flatnonzero(s != 0)
            out.append(pd.DataFrame(dict(pattern=f"P{p}_{tf}", tf=tf, row=b.last_row.values[j], sign=s[j])))
        rows_of[tf] = b

    def row_state(tf, col):
        b = rb[tf]
        return to_rows(ctx, b, b[col].values) if len(b) else np.full(ctx.n, np.nan)

    s1, s5, s15 = row_state(1, "stack"), row_state(5, "stack"), row_state(15, "stack")
    o15 = row_state(15, "order")
    # P8: MTF alignment onset (1-minute rows)
    al = s1 + s5 + s15
    a3 = np.where(np.abs(al) == 3, np.sign(al), np.where(np.isfinite(al), 0.0, np.nan))
    s = onset(a3)
    j = np.flatnonzero(s != 0)
    out.append(pd.DataFrame(dict(pattern="P8_1", tf=1, row=j, sign=s[j])))

    def at(b, ev):
        j = np.flatnonzero(ev != 0)
        return b.last_row.values[j].astype(int), ev[j]
    if len(rb[5]):
        # P9: 5-min pullback with the 15-min ribbon fully stacked the same way
        r, sg = at(rb[5], np.nan_to_num(rb[5]["pullback"].values))
        keep = s15[r] == sg
        out.append(pd.DataFrame(dict(pattern="P9_5", tf=5, row=r[keep], sign=sg[keep])))
        # P11: 5-min compression-expansion onset with the 15-min order >= 5/7 the same way
        sq = rb[5]["sqz_break"].values
        r, sg = at(rb[5], onset(sq))
        keep = o15[r] * sg >= 5 / 7 - 1e-9
        out.append(pd.DataFrame(dict(pattern="P11_5", tf=5, row=r[keep], sign=sg[keep])))
    if len(rb[1]):
        # P10: 1-min pullback with the 5- and 15-min ribbons fully stacked the same way
        r, sg = at(rb[1], np.nan_to_num(rb[1]["pullback"].values))
        keep = (s5[r] == sg) & (s15[r] == sg)
        out.append(pd.DataFrame(dict(pattern="P10_1", tf=1, row=r[keep], sign=sg[keep])))
    for tf in (1, 3, 5):
        if not len(rb[tf]):
            continue
        # P12: lower-timeframe twist back in the direction of a fully stacked 15-minute ribbon
        r, sg = at(rb[tf], np.nan_to_num(rb[tf]["twist"].values))
        keep = s15[r] == sg
        out.append(pd.DataFrame(dict(pattern=f"P12_{tf}", tf=tf, row=r[keep], sign=sg[keep])))
    E = pd.concat(out, ignore_index=True)
    E["row"] = E.row.astype(int)
    E["day"] = pd.to_datetime(M.day.values[E.row.values]).date
    E["m"] = M.minute.values[E.row.values].astype(int)
    sess = pd.factorize(M.day, sort=True)[0]
    E["sess"] = sess[E.row.values]
    E = E[(E.m >= K.SIG_FIRST) & (E.m <= K.SIG_LAST) & (E.sess >= K.WARM_SESSIONS)]
    return E.sort_values(["row", "pattern"]).reset_index(drop=True)


def bar_atr_bps(M: pd.DataFrame, und: str) -> dict[int, np.ndarray]:
    """Per row, the ATR(14) of the last complete bar of each size, in bps of that row's close (for move-in-ATR labels)."""
    ctx = Ctx(M, None, und)
    out = {}
    for tf in K.TFS:
        b = RB.ribbon_bars(ctx, tf, RB.RIB)
        out[tf] = to_rows(ctx, b, b["atr"].values) / ctx.c * 1e4 if len(b) else np.full(ctx.n, np.nan)
    return out


def ema_cross_events(M: pd.DataFrame, und: str, tf: int = 5, fast: int = 9, slow: int = 21) -> pd.DataFrame:
    """Baseline B0: the plain single-EMA cross, EMA fast x EMA slow on tf-minute bars (+1 fast crosses above)."""
    from research.brain.features.bars import bars, ema
    ctx = Ctx(M, None, und)
    b = bars(ctx, tf)
    c = b.close.values
    f, s = ema(c, span=fast), ema(c, span=slow)
    d = np.sign(f - s)
    d = np.where(np.arange(len(c)) >= slow * 3, d, np.nan)
    ev = onset(d)
    j = np.flatnonzero(ev != 0)
    E = pd.DataFrame(dict(pattern="B0", tf=tf, row=b.last_row.values[j].astype(int), sign=ev[j]))
    E["day"] = pd.to_datetime(M.day.values[E.row.values]).date
    E["m"] = M.minute.values[E.row.values].astype(int)
    sess = pd.factorize(M.day, sort=True)[0]
    E["sess"] = sess[E.row.values]
    return E[(E.m >= K.SIG_FIRST) & (E.m <= K.SIG_LAST) & (E.sess >= K.WARM_SESSIONS)].reset_index(drop=True)
