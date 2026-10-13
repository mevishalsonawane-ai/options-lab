"""R20 rules (PREREG.md): opening ranges of 15 / 30 / 45 minutes, the adaptive range, the late entry and the wide-day fade.

Pure numpy on one session's 5-minute bars; no option data, no clock. 13 Oct 2026.

Bars: bar k is the 5-minute bar labelled 09:15 + 5k (k = 0 .. 74), built from the 1-minute grid rows 5k .. 5k + 4. It is
known once it has closed (row 5k + 4). "The L-minute range" follows the app's OrbParams.orEnd: the bars labelled 09:15 up to
and including 09:15 + L, i.e. bars 0 .. L / 5. Look-ahead guard: a range never reads a bar after its end bar, and a
decision on bar k never reads a bar after k.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

NB = 75                      # 5-minute bars in a session (09:15 .. 15:25)
EDGE = 0.10                  # Range Fade's outer tenth



def bar_index(label_hhmm: str) -> int:
    """'10:05' -> 10. The bar labelled at that time."""
    h, m = (int(x) for x in label_hhmm.split(":"))
    return (h * 60 + m - (9 * 60 + 15)) // 5


K_1000, K_0930, K_0945 = bar_index("10:00"), bar_index("09:30"), bar_index("09:45")   # the 45-, 15- and 30-minute ranges' end bars
K_1355, K_1425 = bar_index("13:55"), bar_index("14:25")   # the last decision bars for a 14:00 / 14:30 last entry
K_1030 = bar_index("10:30")                                # Range Fade's first bar
K_1400, K_1500 = bar_index("14:00"), bar_index("15:00")   # the late-break window of the facts


def bars5(ix: np.ndarray):
    """(open, high, low, close) of the 75 five-minute bars from the (375, 4) minute grid."""
    o = ix[0::5, 0]
    h = ix[:, 1].reshape(NB, 5).max(axis=1)
    lo = ix[:, 2].reshape(NB, 5).min(axis=1)
    c = ix[4::5, 3]
    return o, h, lo, c


def opening_range(bh: np.ndarray, bl: np.ndarray, end_k: int) -> tuple[float, float]:
    """(high, low) of bars 0 .. end_k. Reads nothing after end_k (the look-ahead guard)."""
    return float(np.max(bh[:end_k + 1])), float(np.min(bl[:end_k + 1]))


def daily_hlc(ix: np.ndarray) -> tuple[float, float, float]:
    return float(np.nanmax(ix[:, 1])), float(np.nanmin(ix[:, 2])), float(ix[-1, 3])


def atr14(hlc: list[tuple[float, float, float]], i: int) -> float:
    """The app's ShadowRules.atr14: the mean true range of the 14 sessions before session i (each against the close
    before it), so it needs 15 earlier sessions; NaN otherwise. Session i itself is never read."""
    if i < 15:
        return float("nan")
    tr = []
    for j in range(i - 14, i):
        h, lo, _ = hlc[j]
        pc = hlc[j - 1][2]
        tr.append(max(h - lo, abs(h - pc), abs(lo - pc)))
    return float(np.mean(tr))


@dataclass(frozen=True)
class Variant:
    """name; kind: 'orb' (one fixed range), 'adaptive' (B: 45 if narrow else 15), 'wide_fade' (D); end_k: the range's end
    bar for 'orb'; last_k: the last decision bar; k_atr: the width threshold in ATR14 for 'adaptive' / 'wide_fade'."""
    name: str
    kind: str
    end_k: int = K_1000
    last_k: int = K_1355
    k_atr: float = float("nan")


VARIANTS = [
    Variant("V0", "orb", K_1000, K_1355),
    Variant("A15", "orb", K_0930, K_1355),
    Variant("A30", "orb", K_0945, K_1355),
    Variant("B0.5", "adaptive", K_1000, K_1355, 0.5),
    Variant("B0.75", "adaptive", K_1000, K_1355, 0.75),
    Variant("B1.0", "adaptive", K_1000, K_1355, 1.0),
    Variant("C1430", "orb", K_1000, K_1425),
    Variant("D", "wide_fade", K_1000, K_1355, 0.75),
]
ENTRY_ID = {"A15": "r20a", "A30": "r20b", "B0.5": "r20c", "B0.75": "r20d", "B1.0": "r20e", "C1430": "r20f", "D": "r20g"}


@dataclass(frozen=True)
class Plan:
    """What a variant trades on one day: rule 'break' (ORB) or 'fade' (Range Fade); the range; the decision bars k0 .. k1;
    cap = entries a day; side_cap = entries a side a day (0: none)."""
    rule: str
    orh: float
    orl: float
    k0: int
    k1: int
    cap: int
    side_cap: int
    wide: bool = False


def plan_for(v: Variant, bh: np.ndarray, bl: np.ndarray, atr: float) -> Plan | None:
    """The day's plan. None when it cannot be made (no ATR14 for the width rules, an empty range)."""
    if v.kind == "orb":
        h, lo = opening_range(bh, bl, v.end_k)
        return Plan("break", h, lo, v.end_k + 1, v.last_k, 99, 1) if h > lo else None
    if not np.isfinite(atr) or atr <= 0:
        return None
    h45, l45 = opening_range(bh, bl, K_1000)
    h15, l15 = opening_range(bh, bl, K_0930)
    wide = (h45 - l45) > v.k_atr * atr
    if v.kind == "adaptive":
        h, lo = (h15, l15) if wide else (h45, l45)
        return Plan("break", h, lo, K_1000 + 1, v.last_k, 99, 1, wide) if h > lo else None
    if v.kind == "wide_fade":
        if wide:
            return Plan("fade", h15, l15, K_1000 + 1, v.last_k, 2, 0, True) if h15 > l15 else None
        return Plan("break", h45, l45, K_1000 + 1, v.last_k, 99, 1, False) if h45 > l45 else None
    raise ValueError(v.kind)


def signal(rule: str, orh: float, orl: float, h: float, lo: float, c: float) -> int:
    """+1 (buy the call) / -1 (the put) / 0 on one completed bar. 'break': OrbRules.breakDirection (buffer 0); 'fade':
    RangeFadeRules.entrySignal (the high reaches the top tenth and the close is back below the high: -1, and mirrored)."""
    if rule == "break":
        return 1 if c > orh else -1 if c < orl else 0
    w = orh - orl
    if w <= 1e-9:
        return 0
    if h >= orh - EDGE * w - 1e-9 and c < orh - 1e-9:
        return -1
    if lo <= orl + EDGE * w + 1e-9 and c > orl + 1e-9:
        return 1
    return 0


def signals(p: Plan, bh: np.ndarray, bl: np.ndarray, bc: np.ndarray) -> list[tuple[int, int]]:
    """Every (bar, side) in the plan's window that signals; bar k reads only bar k."""
    out = []
    for k in range(p.k0, p.k1 + 1):
        s = signal(p.rule, p.orh, p.orl, bh[k], bl[k], bc[k])
        if s:
            out.append((k, s))
    return out


def take(sigs: list[tuple[int, int]], p: Plan, trade) -> list:
    """Walk the signals as the app does: at most p.cap entries a day and p.side_cap a side; after an exit, decide again
    only from the bar after the exit's bar. trade(k, s) -> (record, exit_row) or None (refused: no contract, no print,
    premium too low); a refused signal uses no cap."""
    out = []
    n = 0
    per_side = {1: 0, -1: 0}
    block = -1
    for k, s in sigs:
        if 5 * k <= block or n >= p.cap or (p.side_cap and per_side[s] >= p.side_cap):
            continue
        r = trade(k, s)
        if r is None:
            continue
        rec, xr = r
        out.append(rec)
        n += 1
        per_side[s] += 1
        block = xr - (xr % 5)
    return out


# ------------------------------------------------------------------------------------------------ Step 1 facts
def sweep_signal(orh, orl, h, lo, c) -> int:
    return -1 if (h > orh and c < orh) else 1 if (lo < orl and c > orl) else 0


def day_facts(bh, bl, bc) -> dict:
    """On the plain 45-minute range: did the ORB / Sweep / Range Fade signal at all, and the first late (14:00-15:00)
    break on a day with no ORB break."""
    orh, orl = opening_range(bh, bl, K_1000)
    orb = any(signal("break", orh, orl, bh[k], bl[k], bc[k]) for k in range(K_1000 + 1, K_1355 + 1))
    sweep = any(sweep_signal(orh, orl, bh[k], bl[k], bc[k]) for k in range(K_1000 + 1, K_1425 + 1))
    fade = any(signal("fade", orh, orl, bh[k], bl[k], bc[k]) for k in range(K_1030, K_1355 + 1))
    late_k, late_s = None, 0
    if not orb:
        for k in range(K_1400, K_1500 + 1):
            s = signal("break", orh, orl, bh[k], bl[k], bc[k])
            if s:
                late_k, late_s = k, s
                break
    return dict(w45=orh - orl, orb=orb, sweep=sweep, fade=fade, late_k=late_k, late_s=late_s)


# ------------------------------------------------------------------------------------------------ scores
def metrics(daily: np.ndarray, trades: int) -> dict:
    """daily: net per eligible session (0 on no-trade days)."""
    daily = np.asarray(daily, dtype=float)
    traded = daily[daily != 0]
    cum = np.cumsum(daily)
    dd = float(np.max(np.maximum.accumulate(np.concatenate([[0.0], cum]))[1:] - cum)) if len(cum) else 0.0
    return dict(sessions=int(len(daily)), trades=int(trades), trade_days=int(len(traded)), net=float(daily.sum()),
                per_session=float(daily.mean()) if len(daily) else 0.0,
                green_pct=float(100 * (traded > 0).mean()) if len(traded) else float("nan"),
                worst_day=float(daily.min()) if len(daily) else 0.0, max_dd=dd)


def bh_adjust(p: list[float]) -> list[float]:
    """Benjamini-Hochberg q-values in the input order."""
    m = len(p)
    order = np.argsort(p)
    q = np.empty(m)
    prev = 1.0
    for rank in range(m, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in q]


def blocks(n: int, parts: int = 4) -> list[tuple[int, int]]:
    """parts consecutive near-equal [start, end) blocks of n items."""
    edges = [round(i * n / parts) for i in range(parts + 1)]
    return [(edges[i], edges[i + 1]) for i in range(parts)]
