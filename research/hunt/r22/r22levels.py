"""R22 levels from the morning's three 15-minute candles (PREREG.md part A2). Research only, 13 Oct 2026.

Grid: R17's Day.ix, 375 rows, row r = the minute labelled 09:15 + r. Candle j (0, 1, 2) = rows 15j .. 15j + 14, so the
three candles 09:15, 09:30, 09:45 are rows 0 .. 44 and all closed by 10:00. Nothing from row 45 on is read here (the
look-ahead guard in test_r22.py checks it).

    morning_levels(ix)      OH / OL / OM / R, each candle's high and low, the extensions, P10
    mirror(lv)              the random-level twin: every level mirrored around P10
    bars(ix, tf)            (open, high, low, close) of the tf-minute bars of the day
"""
from __future__ import annotations

import numpy as np

OR_ROWS = 45                 # rows 0 .. 44: the 15-minute candles labelled 09:15, 09:30, 09:45
EXT = (0.5, 1.0)             # extension multiples of the range


def morning_levels(ix: np.ndarray) -> dict:
    """The day's levels, built from rows 0 .. 44 only."""
    m = ix[:OR_ROWS]
    oh, ol = float(np.max(m[:, 1])), float(np.min(m[:, 2]))
    r = oh - ol
    candles = [(float(np.max(m[15 * j:15 * j + 15, 1])), float(np.min(m[15 * j:15 * j + 15, 2]))) for j in range(3)]
    lv = dict(OH=oh, OL=ol, OM=(oh + ol) / 2.0, R=r, P10=float(m[-1, 3]), candles=candles)
    for k in EXT:
        lv[f"X+{k:g}"] = oh + k * r
        lv[f"X-{k:g}"] = ol - k * r
    lv["inner"] = inner_levels(lv)
    return lv


def inner_levels(lv: dict) -> list[float]:
    """Candle highs / lows strictly inside the range (not equal to OH / OL), duplicates dropped, sorted."""
    out = set()
    for h, lo in lv["candles"]:
        for x in (h, lo):
            if lv["OL"] + 1e-9 < x < lv["OH"] - 1e-9:
                out.add(round(x, 6))
    return sorted(out)


def mirror(lv: dict) -> dict:
    """x -> 2 P10 - x for every level: twin OH = 2 P10 - OL, twin OL = 2 P10 - OH (each twin edge sits at the OTHER real
    edge's distance from the 10:00 price); candle highs and lows swap the same way; the range width is unchanged."""
    p = lv["P10"]
    f = lambda x: 2.0 * p - x  # noqa: E731
    out = dict(OH=f(lv["OL"]), OL=f(lv["OH"]), OM=f(lv["OM"]), R=lv["R"], P10=p,
               candles=[(f(lo), f(h)) for h, lo in lv["candles"]])
    for k in EXT:
        out[f"X+{k:g}"] = f(lv[f"X-{k:g}"])
        out[f"X-{k:g}"] = f(lv[f"X+{k:g}"])
    out["inner"] = inner_levels(out)
    return out


def bars(ix: np.ndarray, tf: int):
    """(open, high, low, close) arrays of the day's tf-minute bars (bar k = rows tf*k .. tf*k + tf - 1)."""
    n = ix.shape[0] // tf
    o = ix[0:n * tf:tf, 0]
    h = ix[:n * tf, 1].reshape(n, tf).max(axis=1)
    lo = ix[:n * tf, 2].reshape(n, tf).min(axis=1)
    c = ix[tf - 1:n * tf:tf, 3]
    return o, h, lo, c


def width_atr(lv: dict, atr: float) -> float:
    return lv["R"] / atr if atr and np.isfinite(atr) else float("nan")
