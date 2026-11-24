"""R210 pure parts (PREREG.md): bars on the 09:15 minute grid, ATR / EMA, the hybrid trail, the Chandelier exit, the
higher-timeframe filter, the paired sign-flip test, BH and the gate. No data and no engine here (test_r210.py).

Every function that decides something at minute row r reads only minutes <= r: a bar is used only once its last minute
(its `end` row) has passed, and an exit decided on a bar's close is sold at the open of the next minute (end + 1), the way
the arms' own index exits are. Plain-English notes, 24 Nov 2026.
"""
from __future__ import annotations

import math
from collections import defaultdict
from datetime import date

import numpy as np

NROWS = 375                  # 09:15 .. 15:29


# ================================================================================================ bars
def day_bars(ohlc, tf: int) -> dict:
    """tf-minute bars of one session from its (>=4, 375) minute O H L C on the 09:15 grid, anchored at 09:15 (the last bar
    may be short: 60-minute bars end with 15:15 - 15:29). A minute counts when its high, low and close are known; a bar
    with no such minute is left out. Returns arrays o, h, l, c and end (the bar's last row on the grid)."""
    a = np.asarray(ohlc, float)
    o_, h_, l_, c_ = a[0], a[1], a[2], a[3]
    n = a.shape[1]
    out = {k: [] for k in ("o", "h", "l", "c", "end")}
    for s in range(0, n, tf):
        e = min(s + tf, n)
        ok = np.isfinite(h_[s:e]) & np.isfinite(l_[s:e]) & np.isfinite(c_[s:e])
        if not ok.any():
            continue
        idx = np.nonzero(ok)[0] + s
        op = o_[idx[0]] if np.isfinite(o_[idx[0]]) else c_[idx[0]]
        out["o"].append(float(op))
        out["h"].append(float(np.max(h_[idx])))
        out["l"].append(float(np.min(l_[idx])))
        out["c"].append(float(c_[idx[-1]]))
        out["end"].append(e - 1)
    return {k: np.asarray(v, float if k != "end" else np.int64) for k, v in out.items()}


def series(days: list, tf: int) -> dict:
    """One continuous bar series over sessions (list of (>=4, 375) arrays, oldest first): arrays day (the session's
    position in the list), end, o, h, l, c, and key = day * 1000 + end (sorted) for look-ups."""
    parts = {k: [] for k in ("day", "end", "o", "h", "l", "c")}
    for i, a in enumerate(days):
        b = day_bars(a, tf)
        m = len(b["c"])
        parts["day"].append(np.full(m, i, np.int64))
        for k in ("end", "o", "h", "l", "c"):
            parts[k].append(b[k])
    S = {k: (np.concatenate(v) if v else np.zeros(0)) for k, v in parts.items()}
    S["day"] = S["day"].astype(np.int64)
    S["end"] = S["end"].astype(np.int64)
    S["key"] = S["day"] * 1000 + S["end"]
    return S


def atr(h, l, c, n: int = 14, min_n: int | None = None) -> np.ndarray:
    """ATR as of each bar (that bar included): the simple mean of the last n true ranges (at least min_n, default n).
    TR = max(high - low, |high - previous close|, |low - previous close|); the first bar's TR is its high - low."""
    h, l, c = (np.asarray(x, float) for x in (h, l, c))
    min_n = n if min_n is None else min_n
    m = len(c)
    tr = np.empty(m)
    for i in range(m):
        tr[i] = h[i] - l[i]
        if i > 0:
            tr[i] = max(tr[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
    out = np.full(m, np.nan)
    for i in range(m):
        w = tr[max(0, i - n + 1):i + 1]
        if len(w) >= min_n:
            out[i] = float(w.mean())
    return out


def ema(x, n: int = 20) -> np.ndarray:
    """EMA(n) as of each bar (that bar included), seeded with the mean of the first n values; NaN before."""
    x = np.asarray(x, float)
    out = np.full(len(x), np.nan)
    if len(x) < n:
        return out
    a = 2.0 / (n + 1.0)
    v = float(x[:n].mean())
    out[n - 1] = v
    for i in range(n, len(x)):
        v = a * x[i] + (1 - a) * v
        out[i] = v
    return out


def swings(h, l, k: int = 2) -> tuple:
    """(swing low, swing high) flags: bar s is a swing low when its low is strictly below the lows of the k bars before and
    the k bars after it (a swing high mirrors it). A swing at s is only KNOWN once bar s + k has closed."""
    h, l = np.asarray(h, float), np.asarray(l, float)
    m = len(l)
    lo = np.zeros(m, bool)
    hi = np.zeros(m, bool)
    for s in range(k, m - k):
        nb = np.r_[l[s - k:s], l[s + 1:s + k + 1]]
        lo[s] = bool(np.all(l[s] < nb))
        nb = np.r_[h[s - k:s], h[s + 1:s + k + 1]]
        hi[s] = bool(np.all(h[s] > nb))
    return lo, hi


def last_done(S: dict, day_i: int, row: int) -> int:
    """Position of the last bar completed by the end of minute `row` of session day_i (earlier sessions included); -1 if
    none."""
    return int(np.searchsorted(S["key"], day_i * 1000 + row, side="right")) - 1


def earlier(a: int, b: int) -> int:
    """The earlier of two exit rows, -1 = none."""
    a = -1 if a is None else int(a)
    b = -1 if b is None else int(b)
    if a < 0:
        return b
    if b < 0:
        return a
    return min(a, b)


# ================================================================================================ the hybrid trail
def stage_rows(opt_high, ef: int, sq: int, raw: float, r: float) -> tuple:
    """(t2, t3): the first minute rows (from the fill row ef, before the square-off row sq) whose premium high reaches
    +2R and +3R (raw x (1 + 2r), raw x (1 + 3r)); -1 = never. Stage 1 (+1R: stop to breakeven) is the engine's own
    breakeven lock (r186engine, be_x = r)."""
    h = np.asarray(opt_high, float)
    out = []
    for mult in (2.0, 3.0):
        lvl = raw * (1.0 + mult * r)
        t = -1
        for m in range(max(ef, 0), min(sq, len(h))):
            if np.isfinite(h[m]) and h[m] >= lvl:
                t = m
                break
        out.append(t)
    return tuple(out)


def _day_positions(S: dict, day_i: int) -> np.ndarray:
    lo = int(np.searchsorted(S["key"], day_i * 1000, side="left"))
    hi = int(np.searchsorted(S["key"], day_i * 1000 + 999, side="right"))
    return np.arange(lo, hi)


def hybrid_row(S: dict, atr_: np.ndarray, day_i: int, ef: int, sq: int, long: bool, t2: int, t3: int,
               k: float = 1.5, swing_k: int = 2, sw: tuple | None = None) -> int:
    """The exit row of the hybrid trail's stages 2 and 3 (stage 1 is the engine's breakeven), -1 = none before sq.

    Stage 2, from the first bar that ends after t2 (a level reached in a minute acts from the next minute): the highest
    5-minute close since entry (bars ending at or after the fill row ef) minus k x ATR(14) (long; short side mirrored:
    lowest close + k x ATR). Stage 3, from the first bar ending after t3: also the most recent confirmed swing low (swing
    high), whichever is tighter. The level never loosens. A bar whose close is beyond the level sells at the open of the
    next minute (end + 1)."""
    if t2 < 0:
        return -1
    lo_f, hi_f = sw if sw is not None else swings(S["h"], S["l"], swing_k)
    ext = -np.inf if long else np.inf
    lvl = -np.inf if long else np.inf
    for j in _day_positions(S, day_i):
        end = int(S["end"][j])
        if end < ef:
            continue
        if end + 1 >= sq:
            break
        c = float(S["c"][j])
        ext = max(ext, c) if long else min(ext, c)
        if end < t2 + 1:
            continue
        a = float(atr_[j])
        if np.isfinite(a):
            l2 = ext - k * a if long else ext + k * a
            lvl = max(lvl, l2) if long else min(lvl, l2)
        if t3 >= 0 and end >= t3 + 1:
            flags = lo_f if long else hi_f
            s = j - swing_k
            while s >= 0 and not flags[s]:
                s -= 1
            if s >= 0:
                l3 = float(S["l"][s]) if long else float(S["h"][s])
                lvl = max(lvl, l3) if long else min(lvl, l3)
        if (long and c < lvl) or (not long and c > lvl):
            return end + 1
    return -1


# ================================================================================================ the Chandelier exit
def chandelier_row(S: dict, atr_: np.ndarray, day_i: int, ef: int, sq: int, mh, ml, long: bool, k: float = 3.0) -> int:
    """The Chandelier exit row (-1 = none before sq): at each 5-minute index bar ending at or after the fill row ef, the
    level is the highest index minute high since the fill (rows ef .. the bar's end) minus k x ATR(14) as of that bar
    (puts: the lowest low + k x ATR); it never loosens. A bar close beyond it sells at the open of the next minute."""
    mh, ml = np.asarray(mh, float), np.asarray(ml, float)
    lvl = -np.inf if long else np.inf
    for j in _day_positions(S, day_i):
        end = int(S["end"][j])
        if end < ef:
            continue
        if end + 1 >= sq:
            break
        a = float(atr_[j])
        seg = (mh if long else ml)[ef:end + 1]
        seg = seg[np.isfinite(seg)]
        if not np.isfinite(a) or not len(seg):
            continue
        l1 = float(seg.max()) - k * a if long else float(seg.min()) + k * a
        lvl = max(lvl, l1) if long else min(lvl, l1)
        c = float(S["c"][j])
        if (long and c < lvl) or (not long and c > lvl):
            return end + 1
    return -1


# ================================================================================================ the higher-timeframe filter
def mtf_agree(S: dict, ema_: np.ndarray, day_i: int, er: int, side: str):
    """Does the higher timeframe agree with a break whose entry row is er (its signal bar closed at er - 1)? The last tf
    bar completed by the end of minute er - 1 (earlier sessions included): a call needs its close above EMA(20) of those
    closes, a put below it. None = unknown (fewer than 20 bars): the break is taken, as R151's picker does."""
    j = last_done(S, day_i, er - 1)
    if j < 0 or not np.isfinite(ema_[j]):
        return None
    c = float(S["c"][j])
    return bool(c > ema_[j]) if side == "C" else bool(c < ema_[j])


# ================================================================================================ statistics
def day_nets(trades: list, lo: date, hi: date) -> dict:
    out = defaultdict(float)
    for t in trades:
        if lo <= t["day"] <= hi:
            out[t["day"]] += float(t["net"])
    return out


def daily_diffs(var: list, base: list, lo: date, hi: date) -> np.ndarray:
    """Variant minus baseline net per session (sessions where either traded), oldest first."""
    a, b = day_nets(var, lo, hi), day_nets(base, lo, hi)
    return np.array([a.get(d, 0.0) - b.get(d, 0.0) for d in sorted(set(a) | set(b))], float)


def sign_flip(diffs, n: int, rng) -> dict:
    """Paired one-sided test of 'the variant makes more than the baseline': the daily differences' signs are flipped at
    random (H0: symmetric about 0). p = (1 + flips with a sum >= the actual) / (n + 1); p_normal uses the flip
    distribution's exact sd, sqrt(sum d^2) (the BH input, as R190 / R193 used a normal tail)."""
    d = np.asarray(diffs, float)
    obs = float(d.sum())
    if not len(d) or not np.any(d):
        return dict(stat=round(obs), p=1.0, p_normal=1.0, days=int(len(d)))
    hits = 0
    done = 0
    while done < n:
        b = min(2000, n - done)
        s = rng.choice((-1.0, 1.0), size=(b, len(d)))
        hits += int(((s * d).sum(axis=1) >= obs - 1e-9).sum())
        done += b
    sd = math.sqrt(float((d * d).sum()))
    z = obs / sd
    return dict(stat=round(obs), p=(1 + hits) / (n + 1), p_normal=float(0.5 * math.erfc(z / math.sqrt(2.0))),
                days=int(len(d)))


def bh(p, m_total: int) -> list:
    """Benjamini-Hochberg q with m_total tests; earlier entries count as p = 1 (R193's bh_total)."""
    p = np.asarray(p, float)
    n = len(p)
    m = max(m_total, n)
    order = np.argsort(p)
    q = np.empty(n)
    prev = 1.0
    for rank in range(n, 0, -1):
        i = order[rank - 1]
        prev = min(prev, p[i] * m / rank)
        q[i] = prev
    return [float(x) for x in np.minimum(q, 1.0)]


def gate(var: dict, base: dict, q: float, min_trades: int = 40, min_folds: int = 3, q_max: float = 0.10) -> dict:
    """PREREG 6 on the design years. var / base: {'design': stats, 'F1'..'F4': stats} (r193lib.stats rows). Passes only
    if: >= min_trades design trades; higher design net; higher net in >= min_folds folds; design worst month not worse;
    BH q <= q_max. Returns passes + every failed item."""
    fails = []
    v, b = var["design"], base["design"]
    if v["trades"] < min_trades:
        fails.append(f"design: {v['trades']} trades < {min_trades}")
    if not v["net"] > b["net"]:
        fails.append(f"design: net {v['net']:,} not above V5's {b['net']:,}")
    folds = [f for f in sorted(k for k in var if k.startswith("F")) if var[f]["net"] > base[f]["net"]]
    nf = len([k for k in var if k.startswith("F")])
    if len(folds) < min_folds:
        fails.append(f"walk-forward: beat V5 in {len(folds)} of {nf} folds (< {min_folds})")
    if v["worst_month"]["net"] < b["worst_month"]["net"]:
        fails.append(f"design: worst month {v['worst_month']['net']:,} worse than V5's {b['worst_month']['net']:,}")
    if not q <= q_max:
        fails.append(f"BH q {q:.3g} > {q_max}")
    return dict(passes=not fails, fails=fails, folds_won=folds, q=q)


def holdout_verdict(var: dict, base: dict) -> dict:
    """PREREG 7: a design survivor is a CANDIDATE only if, on the holdout read once, its net is above V5's and its worst
    month is not worse. Anything else: 'failed on the holdout'."""
    fails = []
    if not var["net"] > base["net"]:
        fails.append(f"holdout: net {var['net']:,} not above V5's {base['net']:,}")
    if var["worst_month"]["net"] < base["worst_month"]["net"]:
        fails.append(f"holdout: worst month {var['worst_month']['net']:,} worse than V5's {base['worst_month']['net']:,}")
    return dict(candidate=not fails, fails=fails)
