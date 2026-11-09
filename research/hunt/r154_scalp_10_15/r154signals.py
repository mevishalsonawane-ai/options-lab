"""R154 plain scalping entries (PREREG section 3), pure: index bars in, (entry row, side) out.

Every function reads one session's index bars `idx` (4, 375) = O H L C on the 09:15 grid (NaN = no print) and, for the
EMA entries, a small state carried from earlier sessions. A signal seen at the close of minute s is entered at row
er = s + 1 (the next minute's open). Nothing reads a bar after s (test_r154.py's look-ahead guard erases every bar after
s and checks the signals up to s are unchanged).
"""
from __future__ import annotations

import numpy as np

import r154cfg as K


# ------------------------------------------------------------------------------------------------ 5-minute bars
def bars5(idx: np.ndarray) -> tuple:
    """(end_rows, close, high, low) of the session's 5-minute bars (bar k = rows 5k..5k+4, stamped at its last row).
    A bar's close is its last printed close; a bar with no print is NaN."""
    n = idx.shape[1] // 5
    h = idx[1, :5 * n].reshape(n, 5)
    lo = idx[2, :5 * n].reshape(n, 5)
    c = idx[3, :5 * n].reshape(n, 5)
    close = np.full(n, np.nan)
    for k in range(n):
        f = np.flatnonzero(np.isfinite(c[k]))
        if len(f):
            close[k] = c[k, f[-1]]
    with np.errstate(all="ignore"):
        hi5 = np.where(np.isfinite(h).any(1), np.nanmax(np.where(np.isfinite(h), h, -np.inf), 1), np.nan)
        lo5 = np.where(np.isfinite(lo).any(1), np.nanmin(np.where(np.isfinite(lo), lo, np.inf), 1), np.nan)
    return 5 * np.arange(n) + 4, close, hi5, lo5


def _ok(er: int, first: int = K.FIRST_ENTRY) -> bool:
    return first <= er <= K.LAST_ENTRY


# ------------------------------------------------------------------------------------------------ E1 OR15
def e1_or15(idx: np.ndarray) -> list:
    """First 1-minute close above the 09:15-09:29 high (call) / below its low (put); each side once a session.
    Needs at least 10 printed minutes in the range."""
    h, lo, c = idx[1, :K.OR_ROWS], idx[2, :K.OR_ROWS], idx[3]
    if np.isfinite(h).sum() < 10:
        return []
    orh, orl = float(np.nanmax(h)), float(np.nanmin(lo))
    out, done = [], set()
    for s in range(K.OR_ROWS, idx.shape[1] - 1):
        x = c[s]
        if not np.isfinite(x):
            continue
        side = "C" if x > orh else ("P" if x < orl else None)
        if side is None or side in done:
            continue
        done.add(side)
        if _ok(s + 1, K.FIRST_ENTRY_OR):
            out.append((s + 1, side))
        if len(done) == 2:
            break
    return out


# ------------------------------------------------------------------------------------------------ E2 TWAP cross
def twap(idx: np.ndarray) -> np.ndarray:
    """Running equal-weight average of (H + L + C) / 3 over the session's printed minutes up to each row (VWAP
    stand-in: the index has no volume)."""
    tp = (idx[1] + idx[2] + idx[3]) / 3.0
    ok = np.isfinite(tp)
    cs = np.cumsum(np.where(ok, tp, 0.0))
    cn = np.cumsum(ok)
    with np.errstate(all="ignore"):
        return np.where(cn > 0, cs / np.maximum(cn, 1), np.nan)


def e2_twapx(idx: np.ndarray) -> list:
    """A 5-minute close above (call) / below (put) the running average after the bar before closed on the other side
    (at or below / at or above it). From the bar ending 09:44."""
    ends, c5, _, _ = bars5(idx)
    tw = twap(idx)
    out = []
    for k in range(1, len(ends)):
        e = int(ends[k])
        if e < K.TWAP_FIRST_END:
            continue
        a, b = c5[k], c5[k - 1]
        ta, tb = tw[e], tw[int(ends[k - 1])]
        if not (np.isfinite(a) and np.isfinite(b) and np.isfinite(ta) and np.isfinite(tb)):
            continue
        side = "C" if (a > ta and b <= tb) else ("P" if (a < ta and b >= tb) else None)
        if side and _ok(e + 1):
            out.append((e + 1, side))
    return out


# ------------------------------------------------------------------------------------------------ EMAs (carried)
def ema_step(prev: float, x: float, n: int) -> float:
    if not np.isfinite(x):
        return prev
    if not np.isfinite(prev):
        return float(x)
    a = 2.0 / (n + 1.0)
    return float(prev + a * (x - prev))


def emas_day(c5: np.ndarray, state: dict) -> tuple:
    """EMA 9 and 21 after each 5-minute bar of the session, carried from `state` ({'e9', 'e21'}); returns (e9, e21,
    e9_before, e21_before, new_state): *_before[k] is the value after bar k-1 (the previous session's last for k = 0)."""
    n = len(c5)
    e9, e21 = np.full(n, np.nan), np.full(n, np.nan)
    b9, b21 = np.full(n, np.nan), np.full(n, np.nan)
    p9, p21 = state.get("e9", np.nan), state.get("e21", np.nan)
    for k in range(n):
        b9[k], b21[k] = p9, p21
        p9, p21 = ema_step(p9, c5[k], 9), ema_step(p21, c5[k], 21)
        e9[k], e21[k] = p9, p21
    return e9, e21, b9, b21, {"e9": p9, "e21": p21}


def e3_ema921(idx: np.ndarray, state: dict) -> tuple:
    """9 / 21 EMA cross on 5-minute closes (a printed bar only): call when EMA9 goes from <= EMA21 to > EMA21, put the
    other way. Returns (signals, new_state)."""
    ends, c5, _, _ = bars5(idx)
    e9, e21, b9, b21, st = emas_day(c5, state)
    out = []
    for k in range(len(ends)):
        if not np.isfinite(c5[k]) or not all(np.isfinite(v) for v in (e9[k], e21[k], b9[k], b21[k])):
            continue
        side = "C" if (e9[k] > e21[k] and b9[k] <= b21[k]) else ("P" if (e9[k] < e21[k] and b9[k] >= b21[k]) else None)
        er = int(ends[k]) + 1
        if side and _ok(er):
            out.append((er, side))
    return out, st


def e4_pbb(idx: np.ndarray, state: dict) -> tuple:
    """1-minute close above the previous minute's high (call) / below its low (put), only when the last COMPLETED
    5-minute bar (ending at or before that minute) closed above (call) / below (put) its EMA21 and that EMA21 rose
    (call) / fell (put) from the bar before. Returns (signals, new_state)."""
    ends, c5, _, _ = bars5(idx)
    _, e21, _, b21, st = emas_day(c5, state)
    h, lo, c = idx[1], idx[2], idx[3]
    out = []
    for s in range(4, idx.shape[1] - 1):
        er = s + 1
        if not _ok(er):
            continue
        k = (s + 1) // 5 - 1                       # the last bar with end row 5k + 4 <= s
        if k < 0 or not (np.isfinite(c[s]) and np.isfinite(h[s - 1]) and np.isfinite(lo[s - 1])):
            continue
        if not (np.isfinite(c5[k]) and np.isfinite(e21[k]) and np.isfinite(b21[k])):
            continue
        up = c5[k] > e21[k] and e21[k] > b21[k]
        dn = c5[k] < e21[k] and e21[k] < b21[k]
        if up and c[s] > h[s - 1]:
            out.append((er, "C"))
        elif dn and c[s] < lo[s - 1]:
            out.append((er, "P"))
    return out, st


# ------------------------------------------------------------------------------------------------ random baseline
def random_entries(und: str, d, n: int = K.RANDOM_PER_DAY) -> list:
    """n distinct entry rows uniform on 09:20-14:45 and a coin-flip side, seeded by (154, date, index)."""
    rng = np.random.default_rng([K.SEED, d.toordinal(), K.UNDS.index(und)])
    rows = np.sort(rng.choice(np.arange(K.FIRST_ENTRY, K.LAST_ENTRY + 1), size=n, replace=False))
    sides = rng.integers(0, 2, size=n)
    return [(int(r), "C" if s else "P") for r, s in zip(rows, sides)]


# ------------------------------------------------------------------------------------------------ all sessions
def plain_signals(entry: str, days: list, und: str | None = None) -> dict:
    """{date: [(er, side)]} for `entry` over [(date, Day)] in date order (index-only warm-up days included). EMA
    entries carry their state through every day and give no signal in the first K.WARMUP_SESSIONS sessions."""
    out, state = {}, {}
    for i, (d, D) in enumerate(days):
        idx = D.idx
        if entry == "E1":
            sig = e1_or15(idx)
        elif entry == "E2":
            sig = e2_twapx(idx)
        elif entry == "E3":
            sig, state = e3_ema921(idx, state)
        elif entry == "E4":
            sig, state = e4_pbb(idx, state)
        elif entry == "RND":
            sig = random_entries(und, d)
        else:
            raise ValueError(entry)
        if entry in ("E3", "E4") and i < K.WARMUP_SESSIONS:
            continue
        if sig:
            out[d] = sig
    return out
