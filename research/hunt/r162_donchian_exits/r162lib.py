"""R162 pure helpers (PREREG.md): Black-Scholes delta and implied vol, the expiry calendar, the index-stop level and
row, the scale-out arithmetic, twin rows and p, the release walk, BH and the gates.

Plain numbers, lists and dicts only (no stores); test_r162.py covers every function. 12 Nov 2026.
"""
from __future__ import annotations

import heapq
import math
from datetime import date

import numpy as np


# ================================================================================================ Black-Scholes
def _ncdf(x: float) -> float:
    return 0.5 * math.erfc(-x / math.sqrt(2.0))


def bs_price(side: str, s: float, k: float, t: float, vol: float, r: float) -> float:
    """European price of a call ('C') or put ('P'); t in years."""
    if t <= 0 or vol <= 0:
        return max(s - k, 0.0) if side == "C" else max(k - s, 0.0)
    sq = vol * math.sqrt(t)
    d1 = (math.log(s / k) + (r + 0.5 * vol * vol) * t) / sq
    d2 = d1 - sq
    if side == "C":
        return s * _ncdf(d1) - k * math.exp(-r * t) * _ncdf(d2)
    return k * math.exp(-r * t) * _ncdf(-d2) - s * _ncdf(-d1)


def implied_vol(side: str, px: float, s: float, k: float, t: float, r: float, lo: float = 0.01, hi: float = 5.0):
    """Implied vol by bisection, or None when the price is outside what any vol in [lo, hi] gives (e.g. below
    intrinsic value) or an input is not usable."""
    if not (px == px and s == s and k == k) or px <= 0 or s <= 0 or k <= 0 or t <= 0:
        return None
    if px < bs_price(side, s, k, t, lo, r) or px > bs_price(side, s, k, t, hi, r):
        return None
    a, b = lo, hi
    for _ in range(80):
        m = 0.5 * (a + b)
        if bs_price(side, s, k, t, m, r) < px:
            a = m
        else:
            b = m
    return 0.5 * (a + b)


def abs_delta(side: str, s: float, k: float, t: float, vol: float, r: float) -> float:
    """|delta| (calls N(d1), puts 1 - N(d1))."""
    d1 = (math.log(s / k) + (r + 0.5 * vol * vol) * t) / (vol * math.sqrt(t))
    return _ncdf(d1) if side == "C" else 1.0 - _ncdf(d1)


def pick_delta_strike(side: str, s: float, t: float, r: float, quotes: dict, target: float):
    """quotes {strike: option close}: the strike whose |delta| is nearest `target` (ties: the deeper in the money),
    or None when no strike gives a delta."""
    best, bk = None, None
    for k in sorted(quotes):
        v = implied_vol(side, quotes[k], s, k, t, r)
        if v is None:
            continue
        dist = abs(abs_delta(side, s, k, t, v, r) - target)
        deeper = bk is not None and ((side == "C" and k < bk) or (side == "P" and k > bk))
        if best is None or dist < best - 1e-12 or (abs(dist - best) <= 1e-12 and deeper):
            best, bk = dist, float(k)
    return bk


# ================================================================================================ expiry calendar
def next_expiry(d: date, flagged: list) -> date | None:
    """The first flagged expiry day after d; after the last flagged day, the last one plus the last gap (the public
    calendar repeats: weekly 7 days, monthly about 28-35). None with fewer than 2 flagged days and nothing after d."""
    later = [x for x in flagged if x > d]
    if later:
        return min(later)
    f = sorted(flagged)
    if len(f) < 2:
        return None
    gap = f[-1] - f[-2]
    x = f[-1]
    while x <= d:
        x = x + gap
    return x


def years_to_expiry(d: date, row: int, exp: date, open_min: int = 9 * 60 + 15, close_min: int = 15 * 60 + 30) -> float:
    """Calendar time in years from minute `row` of day d to 15:30 on the expiry day."""
    days = (exp - d).days + (close_min - (open_min + row)) / (24 * 60)
    return max(days, 1e-6) / 365.0


# ================================================================================================ index stop (family F)
def index_level(idx_low: np.ndarray, idx_high: np.ndarray, er: int, side: str) -> float:
    """The signal's 5-minute bar = the five minutes before the entry row (er-5 .. er-1): its low for a call, its high
    for a put. NaN if those minutes have no index print."""
    lo, hi = max(er - 5, 0), er
    if side == "C":
        w = idx_low[lo:hi]
        w = w[np.isfinite(w)]
        return float(w.min()) if len(w) else float("nan")
    w = idx_high[lo:hi]
    w = w[np.isfinite(w)]
    return float(w.max()) if len(w) else float("nan")


def index_exit_row(close: np.ndarray, start: int, level: float, side: str, sq: int) -> int:
    """The first minute m >= start whose index close is beyond `level` against the trade (calls: below; puts: above)
    -> sold at m + 1's open; -1 if none before the square-off (R71's XI rule). NaN minutes / level trigger nothing."""
    if not (level == level):
        return -1
    for m in range(max(start, 0), min(sq, len(close))):
        x = close[m]
        if x == x and ((side == "C" and x < level) or (side == "P" and x > level)):
            return m + 1 if m + 1 < sq else -1
    return -1


# ================================================================================================ scale-out (family A)
def so_net(rE: float, parts: list, charge_buy, charge_sell) -> float:
    """Net of a position bought at rE (one order, the whole quantity) and sold in parts [(real exit, qty)], each part
    its own sell order: sum (rX - rE) x q - buy charges(whole qty) - sum sell charges(part)."""
    qty = sum(q for _, q in parts)
    gross = sum((rx - rE) * q for rx, q in parts)
    return float(gross - charge_buy(rE, qty) - sum(charge_sell(rx, q) for rx, q in parts))


def round_wins(trades: list) -> tuple:
    """Per-round win (PREREG A): each exit order counted on its own. Returns (wins, rounds). A trade without 'rounds'
    is one round with its own net."""
    w = n = 0
    for t in trades:
        rs = t.get("rounds") or [t["net"]]
        n += len(rs)
        w += sum(1 for x in rs if x > 0)
    return w, n


# ================================================================================================ twins
def twin_window(fill_row: int, rule_xr: int, b0_xr: int):
    """PREREG section 5: (lo, hi, which) for a trade whose exit differs from B0's: earlier -> rows in [fill+1, b0_xr-1]
    with B0's exits ('b0'); later -> rows in [b0_xr+1, rule_xr-1] with the rule's exits ('rule'). None when the
    exits are the same or the window is empty (the twin is then the rule's own trade)."""
    if rule_xr == b0_xr:
        return None
    if rule_xr < b0_xr:
        lo, hi, which = fill_row + 1, b0_xr - 1, "b0"
    else:
        lo, hi, which = b0_xr + 1, rule_xr - 1, "rule"
    return (lo, hi, which) if hi >= lo else None


def twin_p(actual: float, twins) -> float:
    """p = (1 + twins >= actual) / (n + 1)."""
    t = np.asarray(twins, float)
    return float((1 + int((t >= actual - 1e-9).sum())) / (len(t) + 1))


def bh(p, m_total: int | None = None) -> np.ndarray:
    """Benjamini-Hochberg q; m_total > len(p) counts extra earlier tests taken as p = 1."""
    p = np.asarray(p, float)
    n = len(p)
    if n == 0:
        return p
    m = max(m_total or n, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return np.minimum(q, 1.0)


# ================================================================================================ gates (PREREG 6)
def gates(rule: dict, base: dict, q: float, win_up: float = 0.05, gate_q: float = 0.10, min_final: int = 40) -> dict:
    """rule / base = {'design', 'wf', 'final'}: stats dicts (trades, win, net)."""
    fails = []
    for per in ("wf", "final"):
        r, b = rule[per], base[per]
        if r["win"] is None or b["win"] is None or r["win"] < b["win"] + win_up - 1e-9:
            fails.append(f"{per}: win rate not up 5 points")
        if r["net"] < b["net"]:
            fails.append(f"{per}: net lower than today's")
    for per in ("design", "wf", "final"):
        if not rule[per]["net"] > 0:
            fails.append(f"{per}: net not positive")
    if not q <= gate_q:
        fails.append(f"BH q {q:.3g} > {gate_q}")
    if rule["final"]["trades"] < min_final:
        fails.append(f"< {min_final} final trades")
    return dict(passed=not fails, fails=fails)


# ================================================================================================ release walk (PREREG 7)
def walk_release(units: list, priced: list, skipped, mode: str = "hold", hold: int | None = None,
                 max_per_side: int = 2) -> list:
    """R106's 'side2' walk (one open a side, at most `max_per_side` fresh fills a side a day) for one day, where
    skipped(g, j) marks a picker-skipped break. mode 'remove': skipped breaks are dropped before the walk (R151);
    mode 'hold': a skipped break that would have been taken counts in the side's cap and holds the side until its
    would-be exit (hold None, the app), for `hold` minutes after its fill (or its exit if sooner), or not at all
    (hold 0). Returns the taken (not skipped) trades, in R106's order."""
    heap = []
    for g, (key, cands, how) in enumerate(units):
        for j, c in enumerate(cands):
            heap.append((c["er"], g, j, c, priced[g][j]))
    heapq.heapify(heap)
    busy, n_side, taken = {}, {}, []
    while heap:
        er, g, j, c, r = heapq.heappop(heap)
        if r is None:
            continue
        sk = bool(skipped(g, j))
        if sk and mode == "remove":
            continue
        bk = (g, c["side"])
        if er <= busy.get(bk, -1):
            continue
        if n_side.get(bk, 0) >= max_per_side:
            continue
        n_side[bk] = n_side.get(bk, 0) + 1
        if sk:
            if hold is None:
                busy[bk] = r["xr"]
            elif hold > 0:
                busy[bk] = min(r["xr"], r["er"] + hold)
            continue
        busy[bk] = r["xr"]
        t = dict(r)
        t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        t["er_sig"] = c["er"]
        t["re"] = 0
        t["_g"] = g
        taken.append(t)
    taken.sort(key=lambda t: (t["_g"], t["er_sig"]))
    for t in taken:
        t.pop("_g", None)
    return taken


def day_of(units: list) -> dict:
    out = {}
    for g, u in enumerate(units):
        out.setdefault(u[0][1], []).append(g)
    return out


