"""R22 events at the morning-range levels (PREREG.md part A3) and the measurements after each event. 13 Oct 2026.

    edge_events(bars, tf, level, side, rng_w, k0, k1, retest)   touch / REJECT / BREAK / HOLD / FAIL / RETEST-* at OH or OL
    level_reject(bars, k0, k1, level)                            MID / INNER reject: the first touch from the previous
                                                                 close's side and whether 3 closes stay on that side
    ext_reach(ix, tf, b, level, side, targets)                   after a break: does the 1-minute extreme reach each target
                                                                 by 15:29, and before a close back inside
    measure(ix, row, pe, d, atr)                                 forward moves, MFE / MAE (R20c's measure, no fake-out)

Every event carries k (its decision bar) and row = (k + 1) * tf - 1, the minute whose close is the decision. An event
decided at bar k reads only bars <= k (the look-ahead guard in test_r22.py). "Beyond" an edge = a close strictly beyond
it; "inside" = a close at or inside it.
"""
from __future__ import annotations

import numpy as np

from r22levels import bars as _bars

HORIZONS = (15, 30, 60)
CONFIRM = 3                    # the touch / break bar plus the next 2 bars
RETEST_TOL = 0.10              # a retest: back within 0.10 x the range of the broken edge
# Event-study windows (part A3): 5-min bars labelled 10:00 .. 14:55, 15-min bars 10:00 .. 14:45 (all close by 15:00).
WINDOW = {5: (9, 59), 15: (3, 19)}


def row_of(k: int, tf: int) -> int:
    return (k + 1) * tf - 1


def edge_events(bars, tf: int, level: float, side: int, rng_w: float, k0: int, k1: int, retest: bool = True,
                touch: bool = True) -> list[dict]:
    """Events at one OR edge (side +1 = OH, a resistance above; -1 = OL) on bars k0 .. k1 (O, H, L, C arrays).

    touch:  the first bar reaching the edge; REJECT when it and the next 2 bars all close inside (decided at the 3rd)
    BREAK:  the first close beyond; HOLD when the next 2 closes are beyond too (decided at the 3rd); else FAIL at the first
            close back inside. RETEST (after a HOLD, if retest): the first later bar back within RETEST_TOL x range of the
            edge; RETEST-HOLD when it closes beyond, RETEST-FAIL when it closes inside (decided at its close).
    Directions: +side for BREAK / HOLD / RETEST-HOLD, -side for REJECT / FAIL / RETEST-FAIL."""
    _, H, Lo, C = bars
    beyond = lambda c: side * (c - level) > 0  # noqa: E731
    out = []
    if touch:
        for k in range(k0, k1 + 1):
            if (H[k] >= level) if side > 0 else (Lo[k] <= level):
                out.append(dict(kind="touch", k=k, d=side))
                if k + CONFIRM - 1 <= k1 and not any(beyond(C[j]) for j in range(k, k + CONFIRM)):
                    out.append(dict(kind="REJECT", k=k + CONFIRM - 1, d=-side, k_touch=k))
                break
    for b in range(k0, k1 + 1):
        if not beyond(C[b]):
            continue
        out.append(dict(kind="BREAK", k=b, d=side))
        fail = next((j for j in range(b + 1, b + CONFIRM) if j <= k1 and not beyond(C[j])), None)
        if fail is not None:
            out.append(dict(kind="FAIL", k=fail, d=-side, k_break=b))
        elif b + CONFIRM - 1 <= k1:
            out.append(dict(kind="HOLD", k=b + CONFIRM - 1, d=side, k_break=b))
            if retest:
                tol = RETEST_TOL * rng_w
                for r in range(b + CONFIRM, k1 + 1):
                    near = (Lo[r] <= level + tol) if side > 0 else (H[r] >= level - tol)
                    if near:
                        kind = "RETEST-HOLD" if beyond(C[r]) else "RETEST-FAIL"
                        out.append(dict(kind=kind, k=r, d=side if kind == "RETEST-HOLD" else -side, k_break=b))
                        break
        break
    return out


def level_reject(bars, k0: int, k1: int, level: float) -> list[dict]:
    """MID / INNER levels: the first bar touching the level from the side of the previous close (low <= level after a close
    above it: a support touch, d = +1; mirrored d = -1). REJECT when it and the next 2 bars all close on that side."""
    _, H, Lo, C = bars
    for k in range(k0, k1 + 1):
        prev = C[k - 1]
        if prev > level and Lo[k] <= level:
            d = 1
        elif prev < level and H[k] >= level:
            d = -1
        else:
            continue
        out = [dict(kind="touch", k=k, d=d)]
        if k + CONFIRM - 1 <= k1 and all(d * (C[j] - level) > 0 for j in range(k, k + CONFIRM)):
            out.append(dict(kind="REJECT", k=k + CONFIRM - 1, d=d, k_touch=k))
        return out
    return []


def ext_reach(ix: np.ndarray, tf: int, b: int, level: float, side: int, targets: dict, C) -> dict:
    """After a break on bar b: for each target name -> price, did a 1-minute high (low for side -1) reach it after the
    break bar's close, by 15:29 ('reach_<name>') and before the first later close of tf bars back inside ('clean_<name>')."""
    start = row_of(b, tf) + 1
    back = next((j for j in range(b + 1, len(C)) if side * (C[j] - level) <= 0), None)
    end_clean = row_of(back, tf) if back is not None else ix.shape[0] - 1
    ext = ix[:, 1] if side > 0 else ix[:, 2]
    out = {}
    for name, px in targets.items():
        hit = np.nonzero(side * (ext[start:] - px) >= 0)[0]
        first = start + int(hit[0]) if len(hit) else None
        out[f"reach_{name}"] = float(first is not None)
        out[f"clean_{name}"] = float(first is not None and first <= end_clean)
    return out


def measure(ix: np.ndarray, row: int, pe: float, d: int, atr: float) -> dict:
    """Moves after an event decided at minute row (its close = pe), direction d; index points and '_a' = / ATR14."""
    C, H, Lo = ix[:, 3], ix[:, 1], ix[:, 2]
    last = len(C) - 1
    out = {}
    for h in HORIZONS:
        out[f"mv{h}"] = d * (C[row + h] - pe) if row + h <= last else np.nan
    out["mvc"] = d * (C[last] - pe)
    fav, adv = (H, Lo) if d > 0 else (Lo, H)
    end = min(row + 60, last)
    if end > row:
        out["mfe60"] = float(max(0.0, np.max(d * (fav[row + 1:end + 1] - pe))))
        out["mae60"] = float(min(0.0, np.min(d * (adv[row + 1:end + 1] - pe))))
    else:
        out["mfe60"] = out["mae60"] = np.nan
    for k in list(out):
        out[k + "_a"] = out[k] / atr if atr and np.isfinite(atr) else np.nan
    return out


def day_events(ix: np.ndarray, lv: dict) -> list[dict]:
    """Every part A3 event of one session for one level set (real or mirrored), without measurements."""
    rows = []
    for tf in (5, 15):
        bs = _bars(ix, tf)
        k0, k1 = WINDOW[tf]
        for edge, side in (("OH", 1), ("OL", -1)):
            evs = edge_events(bs, tf, lv[edge], side, lv["R"], k0, k1, retest=(tf == 5), touch=(tf == 5))
            for e in evs:
                e.update(edge=edge, tf=tf, level=lv[edge])
                if e["kind"] == "BREAK" and tf == 5:
                    tg = {f"{k:g}": lv[f"X{'+' if side > 0 else '-'}{k:g}"] for k in (0.5, 1.0)}
                    e.update(ext_reach(ix, tf, e["k"], lv[edge], side, tg, bs[3]))
            rows += evs
        if tf == 5:
            for e in level_reject(bs, k0, k1, lv["OM"]):
                e.update(edge="OM", tf=tf, level=lv["OM"], kind=e["kind"].replace("REJECT", "MID-REJECT"))
                rows.append(e)
            for x in lv["inner"]:
                for e in level_reject(bs, k0, k1, x):
                    e.update(edge="INNER", tf=tf, level=x, kind=e["kind"].replace("REJECT", "INNER-REJECT"))
                    rows.append(e)
    for e in rows:
        e["row"] = row_of(e["k"], e["tf"])
    return rows

