"""R61b reference: the trap / fake-breakout / order-flow-proxy features (PREREG.md section 3) and the model scorer, in
pure Python (no numpy, no file, clock or network). The research code calls these same functions, so this file IS the
spec a Kotlin port is checked against, feature by feature (test_r61b.py checks them on hand-made bars).

Inputs for one index and one session, each a list of 375 floats (row 0 = 09:15), NaN where the minute is missing:
    o, h, l, c        the index's 1-minute bars
    volc, volp        near-ATM option volume at that minute (calls / puts, offsets -2..+2 of the WEEK chain)
    oi                near-ATM option OI (calls + puts, offsets -5..+5), each strike slot carried forward within the day
History for the baselines: a list (oldest first) of the previous non-expiry sessions' dicts with the same keys.
Only rows <= frow are ever read, so nothing after the signal minute can change a value.
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r57"))
from r57eval import score, take  # noqa: E402,F401

NAN = float("nan")
TRAP_N = 15
OR_ROWS = 15
SWEEP_WIN = 30
BRK_BARS = 5
WIN15 = 15
DELTA_WIN = 30
BASE_SESSIONS = 20
BASE_MIN = 10

TR = ["trap_same_or", "trap_opp_or", "trap_same_pd", "trap_opp_pd", "sweep_same30", "sweep_opp30", "brk_wick",
      "brk_clv", "vol_brk_rel", "oi_chg15_pct", "bu15", "delta30", "cdelta_day", "optvol_imb15", "absorb15"]


def ok(x) -> bool:
    return x is not None and x == x and not math.isinf(x)


# ------------------------------------------------------------------------------------------------ levels
def opening_range(h: list, l: list, frow: int):
    """(high, low) of rows 0..14, or None before row 14 has closed or when none of them is valid."""
    if frow < OR_ROWS - 1:
        return None
    hs = [x for x in h[:OR_ROWS] if ok(x)]
    ls = [x for x in l[:OR_ROWS] if ok(x)]
    return (max(hs), min(ls)) if hs and ls else None


def day_high_low(h: list, l: list):
    hs = [x for x in h if ok(x)]
    ls = [x for x in l if ok(x)]
    return (max(hs) if hs else NAN, min(ls) if ls else NAN)


def last_close(c: list) -> float:
    for x in reversed(c):
        if ok(x):
            return x
    return NAN


# ------------------------------------------------------------------------------------------------ traps and sweeps
def failed_break(c: list, level: float, q: int, frow: int, first: int, prev_close: float) -> float:
    """1.0 if a break of `level` on side q (+1: a close above it; -1: a close below it) happened at a row in
    first..frow and a close at one of the next TRAP_N rows (and <= frow) was back inside; else 0.0. A break is a close
    beyond the level whose previous valid close (prev_close before the first valid row) was not beyond it."""
    if not ok(level):
        return NAN

    def beyond(x):
        return (x > level) if q > 0 else (x < level)

    pc = prev_close
    for t in range(0, frow + 1):
        x = c[t]
        if not ok(x):
            continue
        if t >= first and ok(pc) and beyond(x) and not beyond(pc):
            for u in range(t + 1, min(t + TRAP_N, frow) + 1):
                if ok(c[u]) and not beyond(c[u]):
                    return 1.0
        pc = x
    return 0.0


def sweep(h: list, l: list, c: list, q: int, frow: int) -> float:
    """1.0 if a bar in the last SWEEP_WIN rows (rows >= 15 only) went beyond the day's running extreme of all earlier
    bars on side q (+1: a new high; -1: a new low) and closed back inside that extreme; else 0.0 (NaN before row 15)."""
    if frow < OR_ROWS:
        return NAN
    ext = NAN
    for r in range(0, frow + 1):
        hi, lo, cl = h[r], l[r], c[r]
        if r >= max(OR_ROWS, frow - SWEEP_WIN + 1) and ok(ext) and ok(cl):
            if q > 0 and ok(hi) and hi > ext and cl <= ext:
                return 1.0
            if q < 0 and ok(lo) and lo < ext and cl >= ext:
                return 1.0
        v = hi if q > 0 else lo
        if ok(v):
            ext = v if not ok(ext) else (max(ext, v) if q > 0 else min(ext, v))
    return 0.0


# ------------------------------------------------------------------------------------------------ bars and windows
def bar(o: list, h: list, l: list, c: list, lo: int, hi: int):
    """(O, H, L, C) of rows lo..hi merged (first valid open, max high, min low, last valid close), or None."""
    lo = max(lo, 0)
    oo = [o[r] for r in range(lo, hi + 1) if ok(o[r])]
    hh = [h[r] for r in range(lo, hi + 1) if ok(h[r])]
    ll = [l[r] for r in range(lo, hi + 1) if ok(l[r])]
    cc = [c[r] for r in range(lo, hi + 1) if ok(c[r])]
    if not (oo and hh and ll and cc):
        return None
    return oo[0], max(hh), min(ll), cc[-1]


def clv(hi: float, lo: float, cl: float) -> float:
    """Close location in the bar, -1 (at the low) .. +1 (at the high); 0 when the bar has no range."""
    return 0.0 if hi <= lo else ((cl - lo) - (hi - cl)) / (hi - lo)


def wsum(x: list, lo: int, hi: int) -> float:
    """Sum of the valid values in rows lo..hi; NaN when none is valid."""
    v = [x[r] for r in range(max(lo, 0), hi + 1) if ok(x[r])]
    return sum(v) if v else NAN


def win_range(h: list, l: list, lo: int, hi: int) -> float:
    b = bar(h, h, l, l, lo, hi)
    return NAN if b is None else b[1] - b[2]


def base_mean(vals: list) -> float:
    """Mean of the last BASE_SESSIONS valid values (already one per earlier session, oldest first), NaN if fewer than
    BASE_MIN are valid."""
    v = [x for x in vals if ok(x)][-BASE_SESSIONS:]
    return sum(v) / len(v) if len(v) >= BASE_MIN else NAN


def _log_ratio(a: float, b: float) -> float:
    return math.log(a / b) if ok(a) and ok(b) and a > 0 and b > 0 else NAN


def _pct(now: float, then: float) -> float:
    return (now / then - 1.0) * 100.0 if ok(now) and ok(then) and then > 0 else NAN


def _ffill_at(x: list, r: int) -> float:
    for j in range(min(r, len(x) - 1), -1, -1):
        if ok(x[j]):
            return x[j]
    return NAN


def buildup_code(dp: float, doi: float) -> float:
    if not (ok(dp) and ok(doi)):
        return NAN
    if dp > 0 and doi > 0:
        return 2.0
    if dp > 0 and doi < 0:
        return 1.0
    if dp < 0 and doi < 0:
        return -1.0
    if dp < 0 and doi > 0:
        return -2.0
    return 0.0


# ------------------------------------------------------------------------------------------------ all 15
def features(day: dict, frow: int, side: str, prev_hl: tuple, prev_close: float, hist: list) -> dict:
    """The 15 TR features of a candidate on side 'C' / 'P' whose signal row is frow, signed as PREREG section 3."""
    s = 1 if side == "C" else -1
    o, h, l, c = day["o"], day["h"], day["l"], day["c"]
    out = {n: NAN for n in TR}
    # traps: OR15 (breaks from row 15 on) and the previous day's high / low
    orng = opening_range(h, l, frow)
    if orng is not None:
        up, dn = orng
        out["trap_same_or"] = failed_break(c, up if s > 0 else dn, s, frow, OR_ROWS, NAN)
        out["trap_opp_or"] = failed_break(c, dn if s > 0 else up, -s, frow, OR_ROWS, NAN)
    ph, pl = prev_hl
    out["trap_same_pd"] = failed_break(c, ph if s > 0 else pl, s, frow, 0, prev_close)
    out["trap_opp_pd"] = failed_break(c, pl if s > 0 else ph, -s, frow, 0, prev_close)
    out["sweep_same30"] = sweep(h, l, c, s, frow)
    out["sweep_opp30"] = sweep(h, l, c, -s, frow)
    # the signal bar
    b = bar(o, h, l, c, frow - BRK_BARS + 1, frow)
    if b is not None and b[1] > b[2]:
        O, H, L, C = b
        out["brk_wick"] = (H - max(O, C)) / (H - L) if s > 0 else (min(O, C) - L) / (H - L)
        out["brk_clv"] = clv(H, L, C) * s
    # volume at the break vs the same 5-minute window of earlier sessions
    vol = [a + b_ if ok(a) and ok(b_) else (a if ok(a) else b_) for a, b_ in zip(day["volc"], day["volp"])]
    hvol = [[a + b_ if ok(a) and ok(b_) else (a if ok(a) else b_) for a, b_ in zip(x["volc"], x["volp"])] for x in hist]
    v5 = wsum(vol, frow - BRK_BARS + 1, frow)
    out["vol_brk_rel"] = _log_ratio(v5, base_mean([wsum(x, frow - BRK_BARS + 1, frow) for x in hvol]))
    # OI with price over 15 minutes
    if frow - WIN15 >= 0:
        oi_now, oi_then = _ffill_at(day["oi"], frow), _ffill_at(day["oi"], frow - WIN15)
        out["oi_chg15_pct"] = _pct(oi_now, oi_then)
        dp = _ffill_at(c, frow) - _ffill_at(c, frow - WIN15)
        doi = oi_now - oi_then if ok(oi_now) and ok(oi_then) else NAN
        bu = buildup_code(dp, doi)
        out["bu15"] = bu * s if ok(bu) else NAN
    # delta proxies: volume-weighted close location of the index bars
    for name, lo in (("delta30", frow - DELTA_WIN + 1), ("cdelta_day", 0)):
        num = den = 0.0
        for r in range(max(lo, 0), frow + 1):
            if ok(h[r]) and ok(l[r]) and ok(c[r]) and ok(vol[r]):
                num += clv(h[r], l[r], c[r]) * vol[r]
                den += vol[r]
        out[name] = num / den * s if den > 0 else NAN
    # option-volume imbalance over 15 minutes
    vc, vp = wsum(day["volc"], frow - WIN15 + 1, frow), wsum(day["volp"], frow - WIN15 + 1, frow)
    if ok(vc) and ok(vp) and vc + vp > 0:
        out["optvol_imb15"] = (vc - vp) / (vc + vp) * s
    # absorption: 15-minute volume vs its baseline, minus 15-minute index range vs its baseline (both logs)
    v15 = wsum(vol, frow - WIN15 + 1, frow)
    r15 = win_range(h, l, frow - WIN15 + 1, frow)
    bv = base_mean([wsum(x, frow - WIN15 + 1, frow) for x in hvol])
    br = base_mean([win_range(x["h"], x["l"], frow - WIN15 + 1, frow) for x in hist])
    a, b2 = _log_ratio(v15, bv), _log_ratio(r15, br)
    out["absorb15"] = a - b2 if ok(a) and ok(b2) else NAN
    return out


# ------------------------------------------------------------------------------------------------ skip filters
def skip(rule: str, f: dict) -> bool:
    """PREREG section 4 (a). A missing value never causes a skip."""
    def on(n):
        return ok(f.get(n, NAN)) and f[n] == 1.0
    f1 = on("trap_same_or") or on("trap_same_pd")
    f2 = ok(f.get("brk_wick", NAN)) and f["brk_wick"] >= 0.5
    if rule == "F1":
        return f1
    if rule == "F2":
        return f2
    if rule == "F3":
        return ok(f.get("vol_brk_rel", NAN)) and f["vol_brk_rel"] < 0
    if rule == "F4":
        return f1 or f2 or on("sweep_same30")
    raise ValueError(rule)
