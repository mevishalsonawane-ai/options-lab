"""R166 pure helpers (PREREG.md): the decision-time features of each check, read only from minutes BEFORE the entry row
(the follow-through exit reads the minutes from the fill to its exit row), the strike caps, the gate routes.

Arrays in, numbers out; test_r166.py covers every function. 12 Nov 2026.
"""
from __future__ import annotations

import math

import numpy as np

SR_BAND = 0.0025          # R150's 0.25%
V_LOW, V_HIGH = 1.0, 3.0
V_MIN_BLOCKS = 4
LOOK_IV = 6               # minutes before entry for E1 / E2
WHIP = 30                 # F1: minutes


# ================================================================================================ B: previous-day levels
def prev_hl(idx_prev: np.ndarray):
    """(PDH, PDL) of the previous session's (4, 375) O H L C minutes; (nan, nan) if it has none."""
    h, l_ = idx_prev[1], idx_prev[2]
    if not np.isfinite(h).any() or not np.isfinite(l_).any():
        return float("nan"), float("nan")
    return float(np.nanmax(h)), float(np.nanmin(l_))


def sr1_skip(side: str, close: float, pdh: float, pdl: float, band: float = SR_BAND) -> bool:
    """SR1: the level ahead is within `band` of the close (above it for a call, below for a put). Unknown -> False."""
    if not (close == close) or close <= 0:
        return False
    if side == "C":
        return pdh == pdh and 0 < pdh - close <= band * close
    return pdl == pdl and 0 < close - pdl <= band * close


def sr2_skip(side: str, close: float, pdh: float, pdl: float) -> bool:
    """SR2: take a call only above PDH, a put only below PDL (skip otherwise). Unknown -> False."""
    if not (close == close):
        return False
    if side == "C":
        return pdh == pdh and not close > pdh
    return pdl == pdl and not close < pdl


# ================================================================================================ C: follow-through
def signal_extreme(idx_high: np.ndarray, idx_low: np.ndarray, er: int, side: str) -> float:
    """The signal 5-minute bar (er-5 .. er-1): its high for a call, its low for a put; nan with no print."""
    w = (idx_high if side == "C" else idx_low)[max(er - 5, 0):er]
    w = w[np.isfinite(w)]
    if not len(w):
        return float("nan")
    return float(w.max() if side == "C" else w.min())


def ft_exit_row(idx_high: np.ndarray, idx_low: np.ndarray, ef: int, n: int, level: float, side: str, sq: int) -> int:
    """FT n: the forced sell row ef + n if over minutes ef .. ef+n-1 the index never traded beyond `level` (above for a
    call, below for a put); -1 when it did, the level is unknown, or ef + n is at / past the square-off."""
    if not (level == level) or ef < 0 or ef + n >= sq:
        return -1
    if side == "C":
        w = idx_high[ef:ef + n]
        hit = bool(np.any(np.isfinite(w) & (w > level)))
    else:
        w = idx_low[ef:ef + n]
        hit = bool(np.any(np.isfinite(w) & (w < level)))
    return -1 if hit else ef + n


# ================================================================================================ D: option volume
def volume_ratio(v: np.ndarray, er: int, min_blocks: int = V_MIN_BLOCKS):
    """The contract's volume over er-5 .. er-1 / the median of its earlier 5-minute block volumes that day (blocks
    aligned to the signal bar); None with fewer than `min_blocks` earlier blocks or a zero median."""
    if er < 5:
        return None
    sig = float(np.nansum(v[er - 5:er]))
    blocks = []
    s = er - 10
    while s >= 0:
        blocks.append(float(np.nansum(v[s:s + 5])))
        s -= 5
    if len(blocks) < min_blocks:
        return None
    med = float(np.median(blocks))
    if med <= 0:
        return None
    return sig / med


def v1_skip(ratio) -> bool:
    return ratio is not None and ratio < V_LOW


def v2_skip(ratio) -> bool:
    return ratio is not None and ratio >= V_HIGH


# ================================================================================================ E: option market
def iv_falling(iv_now, iv_before) -> bool:
    """E1: implied vol at the minute before entry below the one LOOK_IV minutes before. Unknown -> False."""
    return iv_now is not None and iv_before is not None and iv_now < iv_before


def other_side_bid(px_now: float, px_before: float) -> bool:
    """E2: the opposite ATM option's premium higher now than LOOK_IV minutes before. Unknown -> False."""
    return px_now == px_now and px_before == px_before and px_now > px_before


# ================================================================================================ F: traps
def whipsaw(cands: list, j: int, window: int = WHIP) -> bool:
    """F1: an arm signal on the other side of the same index within the `window` minutes before candidate j's entry."""
    c = cands[j]
    return any(o["side"] != c["side"] and c["er"] - window <= o["er"] < c["er"] for o in cands)


def outside_bar(idx_high: np.ndarray, idx_low: np.ndarray, er: int) -> bool:
    """F2: the signal 5-minute bar (er-5 .. er-1) has a higher high AND a lower low than the bar before (er-10 .. er-6).
    Unknown -> False."""
    if er < 10:
        return False
    a_h, a_l = idx_high[er - 5:er], idx_low[er - 5:er]
    b_h, b_l = idx_high[er - 10:er - 5], idx_low[er - 10:er - 5]
    if not (np.isfinite(a_h).any() and np.isfinite(b_h).any() and np.isfinite(a_l).any() and np.isfinite(b_l).any()):
        return False
    return bool(np.nanmax(a_h) > np.nanmax(b_h) and np.nanmin(a_l) < np.nanmin(b_l))


# ================================================================================================ A: strike caps
def cap_depth(side: str, k: float, k_cap: float) -> float:
    """D60C2: never deeper in the money than k_cap (calls: not below it; puts: not above it)."""
    if k is None or k_cap is None:
        return k
    return max(k, k_cap) if side == "C" else min(k, k_cap)


# ================================================================================================ gates (PREREG 4)
def routes(rule: dict, base: dict, periods=("wf", "final"), win_up: float = 0.05, dd_slack: float = 0.10) -> dict:
    """rule / base: {period: stats (win, net, max_dd)}. Win route: win >= base + 5 points and net >= base in every
    period; profit route: net > base and max DD <= 1.1 x base's in every period."""
    win = all(rule[p]["win"] is not None and base[p]["win"] is not None and rule[p]["win"] >= base[p]["win"] + win_up - 1e-9
              and rule[p]["net"] >= base[p]["net"] for p in periods)
    prof = all(rule[p]["net"] > base[p]["net"] and rule[p]["max_dd"] <= (1 + dd_slack) * base[p]["max_dd"] + 1e-9
               for p in periods)
    return dict(win=bool(win), profit=bool(prof))


def gates(rule: dict, base: dict, q: float, periods=("wf", "final"), gate_q: float = 0.10, min_final: int = 40) -> dict:
    r = routes(rule, base, periods)
    fails = []
    if not (r["win"] or r["profit"]):
        fails.append("better by neither route (" + " and ".join(periods) + ")")
    for p in ("design", "wf", "final"):
        if not rule[p]["net"] > 0:
            fails.append(f"{p}: net not positive")
    if not q <= gate_q:
        fails.append(f"BH q {q:.3g} > {gate_q}")
    if rule["final"]["trades"] < min_final:
        fails.append(f"< {min_final} final trades")
    return dict(passed=not fails, fails=fails, routes=r)


def p_of(route: dict, p_net: float, p_win: float) -> float:
    """PREREG 3: p_net, or the larger of p_net and p_win when only the win route holds."""
    if route["win"] and not route["profit"]:
        return max(p_net, p_win)
    return p_net


def finite(x) -> bool:
    return x is not None and isinstance(x, (int, float, np.floating)) and math.isfinite(float(x))
