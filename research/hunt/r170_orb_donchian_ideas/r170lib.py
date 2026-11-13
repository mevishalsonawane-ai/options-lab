"""R170 pure functions (PREREG.md): the other Donchian-study rules (R152-R167) on the ORB family. Built on R169's
library (r169lib: the R3-parity walk, fills, dated charges, scoring, gates) and R3's engine. No file, network or lake
access: r170study.py feeds it. Grid as R3: row t = the 1-minute bar starting 09:15 + t; 5-minute bar b = rows 5b..5b+4.
Every decision at row m reads rows <= m only (test_r170.py: the look-ahead guard). 13 Nov 2026.
"""
from __future__ import annotations

import math
import sys
from dataclasses import dataclass, replace
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r169_orb_enhance"))
import r169cfg as K9  # noqa: E402  (puts R3 and research/ on the path)
import r169lib as X9  # noqa: E402
import r3engine as E  # noqa: E402
from r3data import C, H, L, N, O, V, OI  # noqa: E402

njit = X9.njit
EPS = 1e-9
FILTERS = ("E1", "E3", "E4", "E5", "E6", "E7", "E8", "E10", "E11", "E12", "E13", "P5", "P8", "P9", "P10", "P13", "P12",
           "P14", "P16", "P18", "V1", "V2", "I1", "I2", "T1", "T2", "D1", "D2", "O1", "K1", "K2",
           "S1", "S2", "S3", "A1", "Q1")                                   # S1-Q1: Amendment A
EXITS = ("B15", "B20", "LF1", "LF2", "T45", "NIP30", "IXA", "Q2K", "QT2K", "QS2K", "LCK", "DCAP", "VOLX", "VOLP", "MOM",
         "G1R", "G2R", "FK3", "FK5", "Z1", "Z2", "Z3")                          # Z1-Z3: Amendment B
STRUCT = ("ITM2", "D70", "IXR")
SINGLES = FILTERS + EXITS + STRUCT
WHY = ("stop", "lock", "target", "time", "square_off", "nip", "ixa", "qtgt", "volx", "mom", "fake", "stale")
LF = {"LF1": ((0.10, -0.05), (0.15, 0.03), (0.20, 0.08)),
      "LF2": ((0.10, 0.02), (0.15, 0.07), (0.20, 0.12), (0.25, 0.17))}
DCAP_RS = 4000.0
ZPTS = {"Z1": (20.0, 20.0), "Z2": (20.0, None), "Z3": (15.0, 20.0)}       # (stop, target; None = the arm's)
SR_NEAR = 0.0025


@dataclass(frozen=True)
class Cfg:
    arm: str
    lots: int = 1
    changes: tuple = ()
    costs: str = "dated"

    @property
    def base(self) -> E.Variant:
        return E.baseline(self.arm)

    def has(self, cid: str) -> bool:
        return cid in self.changes

    @property
    def filters(self) -> tuple:
        return tuple(c for c in self.changes if c in FILTERS)


# ================================================================================================ bar series
def ema(x: np.ndarray, n: int) -> np.ndarray:
    """EMA of a series with NaN gaps carried (NaN until the first value)."""
    out = np.full(len(x), np.nan)
    a = 2.0 / (n + 1)
    v = np.nan
    for i, xi in enumerate(x):
        if np.isfinite(xi):
            v = xi if not np.isfinite(v) else a * xi + (1 - a) * v
        out[i] = v
    return out


def supertrend_dir(bars: np.ndarray, n: int = 10, mult: float = 3.0) -> np.ndarray:
    """(len,) +1 up / -1 down / 0 unknown: the classic Supertrend on O H L C bars (Wilder ATR n). Causal."""
    m = len(bars)
    d = np.zeros(m, int)
    atr = np.nan
    pc = np.nan
    fu = fl = np.nan
    cur = 0
    k = 0
    for i in range(m):
        o, h, l, c = bars[i]
        if not np.isfinite(c):
            d[i] = cur
            continue
        tr = (h - l) if not np.isfinite(pc) else max(h - l, abs(h - pc), abs(l - pc))
        k += 1
        atr = tr if not np.isfinite(atr) else (atr * (n - 1) + tr) / n
        mid = (h + l) / 2
        bu, bl = mid + mult * atr, mid - mult * atr
        fu = bu if (not np.isfinite(fu) or bu < fu or pc > fu) else fu
        fl = bl if (not np.isfinite(fl) or bl > fl or pc < fl) else fl
        if k >= n:
            if cur == 0:
                cur = 1 if c >= mid else -1
            elif cur > 0 and c < fl:
                cur = -1
            elif cur < 0 and c > fu:
                cur = 1
        d[i] = cur
        pc = c
    return d


def group_bars(b5: np.ndarray, size: int) -> np.ndarray:
    """One day's 5-minute bars grouped `size` at a time from 09:15 (the last group may be short)."""
    out = []
    for j in range(0, len(b5), size):
        blk = b5[j:j + size]
        ok = np.isfinite(blk[:, 3])
        if not ok.any():
            out.append([np.nan] * 4)
            continue
        out.append([blk[ok, 0][0], np.nanmax(blk[:, 1]), np.nanmin(blk[:, 2]), blk[ok, 3][-1]])
    return np.array(out, float)


def htf_side(days_b5: list, size: int, n: int = 20) -> np.ndarray:
    """For today's (the last day's) 5-minute bar b: the side (+1 above / -1 below / 0 unknown) of the last fully closed
    `size`-group bar's close vs the midline of its n-bar Donchian channel, continuous across days."""
    groups, ends = [], []
    for di, b5 in enumerate(days_b5):
        g = group_bars(b5, size)
        for j in range(len(g)):
            groups.append(g[j])
            ends.append((di, min(len(b5), (j + 1) * size) - 1))     # its last 5-minute bar
    G = np.array(groups, float)
    today = len(days_b5) - 1
    out = np.zeros(len(days_b5[-1]), int)
    for b in range(len(days_b5[-1])):
        idx = [i for i, (di, e) in enumerate(ends) if di < today or (di == today and e <= b)]
        if len(idx) < n:
            continue
        k = idx[-1]
        w = G[k - n + 1:k + 1]
        if not np.isfinite(w[:, 1]).all() or not np.isfinite(G[k, 3]):
            continue
        mid = (np.nanmax(w[:, 1]) + np.nanmin(w[:, 2])) / 2
        out[b] = 1 if G[k, 3] > mid else (-1 if G[k, 3] < mid else 0)
    return out


def cont_series(days_b5: list) -> tuple:
    """Continuous 5-minute bars of the given days (oldest first) and the offset where the last day starts."""
    allb = np.vstack(days_b5)
    return allb, len(allb) - len(days_b5[-1])


def pivots(prev: tuple) -> list:
    _, h, l, c = prev
    if not (np.isfinite(h) and np.isfinite(l) and np.isfinite(c)):
        return []
    p = (h + l + c) / 3
    return [p, 2 * p - l, 2 * p - h, p + (h - l), p - (h - l)]


def swings(bars: np.ndarray, k: int = 3, upto: int | None = None) -> list:
    """Swing highs / lows of O H L C bars (k bars each side), only those confirmed by bar `upto` (j + k <= upto)."""
    m = len(bars) if upto is None else min(len(bars), upto + 1)
    out = []
    for j in range(k, m - k):
        w = bars[j - k:j + k + 1]
        if not np.isfinite(w[:, 1:3]).all():
            continue
        if bars[j, 1] >= w[:, 1].max():
            out.append(float(bars[j, 1]))
        if bars[j, 2] <= w[:, 2].min():
            out.append(float(bars[j, 2]))
    return out


def level_check(levels, c: float, side: int, broken: float) -> tuple:
    """(nearest distance ahead, nearest behind, any within SR_NEAR ahead, any within SR_NEAR behind) of a level list
    from close c; the broken range edge itself is not a level."""
    ahead, behind = np.inf, np.inf
    for x in levels:
        if not np.isfinite(x) or abs(x - broken) < 1e-6:
            continue
        d = side * (x - c)
        if d > 0:
            ahead = min(ahead, d)
        elif d < 0:
            behind = min(behind, -d)
    return ahead, behind, ahead <= SR_NEAR * c, behind <= SR_NEAR * c


def band_cumvol(s) -> np.ndarray:
    """(375,) cumulative volume of the stored option band (ATM +-4) by minute (NaN minutes count 0)."""
    tot = np.zeros(N)
    for a in s.opts.values():
        v = a[:, V].astype(float)
        tot += np.where(np.isfinite(v), v, 0.0)
    return np.cumsum(tot)


def pcr(s, row: int) -> float:
    """Put / call OI ratio of the stored chain (ATM +-4) at `row` (last OI at or before it per contract)."""
    pe = ce = 0.0
    for (k, r_), a in s.opts.items():
        oi = a[:row + 1, OI]
        oi = oi[np.isfinite(oi)]
        if not len(oi):
            continue
        if r_ == "P":
            pe += float(oi[-1])
        else:
            ce += float(oi[-1])
    return pe / ce if ce > 0 else np.nan


def last_val(a: np.ndarray, row: int, col: int) -> float:
    x = a[:row + 1, col]
    x = x[np.isfinite(x)]
    return float(x[-1]) if len(x) else np.nan


def bar_volume(a: np.ndarray, b: int) -> float:
    v = a[5 * b:5 * b + 5, V]
    return float(np.nansum(v)) if np.isfinite(v).any() else np.nan


def signed_volume(a: np.ndarray, row: int, n: int = 3) -> float:
    """Tick-rule signed volume of the last n minutes (close up vs the minute before = buying)."""
    tot, any_ = 0.0, False
    for r in range(max(1, row - n + 1), row + 1):
        c0, c1, v = a[r - 1, C], a[r, C], a[r, V]
        if np.isfinite(c0) and np.isfinite(c1) and np.isfinite(v):
            any_ = True
            tot += v if c1 > c0 else (-v if c1 < c0 else 0.0)
    return tot if any_ else np.nan


def option_iv(s, k: float, side: int, row: int, expiry) -> float:
    a = s.opts.get((k, "C" if side > 0 else "P"))
    if a is None:
        return np.nan
    S = X9.last_close(s.ix, row)
    T = X9.years_to_expiry(s.day, row, expiry)
    p = X9.last_close(a, row)
    return X9.implied_vol(p, S, k, T, X9.RATE, side > 0) if np.isfinite(T) else np.nan


# ================================================================================================ filters
def skip2(fid: str, s, arm: str, side: int, row: int, hi: float, lo: float, b5: np.ndarray, k: float, ctx: dict) -> bool:
    """True when filter `fid` skips this break; unknown never skips. ctx: per-session extras from r170study."""
    b = row // 5
    o, h, l, c = b5[b]
    rng = h - l
    atr = ctx.get("atr5", np.full(75, np.nan))[b]
    a = s.opts.get((k, "C" if side > 0 else "P"))
    if fid == "E1":
        if not (np.isfinite(rng) and rng > 0):
            return False
        pos = (c - l) / rng
        return not (pos >= 0.70 if side > 0 else pos <= 0.30)
    if fid == "E3":
        return bool(np.isfinite(atr) and np.isfinite(rng) and rng < atr)
    if fid == "E4":
        if not np.isfinite(atr):
            return False
        if arm == "orb_sweep":
            d = (h - hi) if side < 0 else (lo - l)
        else:
            d = (c - hi) if side > 0 else (lo - c)
        return bool(d < 0.20 * atr)
    if fid == "E5":
        med = ctx.get("width_med", np.nan)
        return bool(np.isfinite(med) and (hi - lo) > med)
    if fid in ("E6", "E7"):
        sd = ctx.get("htf15" if fid == "E6" else "htf60")
        return bool(sd is not None and sd[b] != 0 and sd[b] != side)
    if fid == "E8":
        lvl = s.prev[1] if side > 0 else s.prev[2]
        return bool(np.isfinite(lvl) and np.isfinite(c) and side * (c - lvl) <= 0)
    if fid == "E10":
        v = s.vix
        o0 = v[np.isfinite(v)]
        vn = X9.last_close(np.c_[v, v, v, v], row) if np.isfinite(v[:row + 1]).any() else np.nan
        if not len(o0) or not np.isfinite(vn):
            return False
        chg = vn - o0[0]
        return not (chg < 0 if side > 0 else chg > 0)
    if fid == "E11":
        others = ctx.get("breadth", {})
        n_ok, n_have = 0, 0
        for und, ob5 in list(others.items()) + [("self", b5)]:
            if ob5 is None or b >= len(ob5) or not np.isfinite(ob5[b, 3]):
                continue
            op = ob5[np.isfinite(ob5[:, 0]), 0]
            if not len(op):
                continue
            n_have += 1
            n_ok += int(side * (ob5[b, 3] - op[0]) > 0)
        return n_have == 5 and n_ok < 4
    if fid == "E12":
        t = row + 1
        return bool(135 <= t <= 254)          # entry minute 11:30 (row 135) .. 13:29 (row 254)
    if fid == "E13":
        if row + 1 < 60:
            return False
        fh = s.ix[:60]
        top, bot = np.nanmax(fh[:, H]), np.nanmin(fh[:, L])
        return not (c > top if side > 0 else c < bot)
    if fid == "P5":
        for p in pivots(s.prev):
            d = side * (p - c)
            if 0 < d <= 0.0025 * c:
                return True
        return False
    if fid == "P8":
        e20 = ctx.get("ema20", np.full(75, np.nan))[b]
        return bool(np.isfinite(e20) and side * (c - e20) <= 0)
    if fid == "P9":
        st = ctx.get("st5", np.zeros(75, int))[b]
        return bool(st != 0 and st != side)
    if fid == "P10":
        cl = s.ix[:row + 1, C]
        cl = cl[np.isfinite(cl)]
        return bool(len(cl) and side * (c - cl.mean()) <= 0)
    if fid == "P13":
        if a is None or row < 15:
            return False
        p0, p1 = last_val(a, row - 15, C), last_val(a, row, C)
        q0, q1 = last_val(a, row - 15, OI), last_val(a, row, OI)
        return bool(np.isfinite(p0 * p1 * q0 * q1) and p1 < p0 and q1 < q0)
    if fid == "P12":
        if row < 15:
            return False
        x0, x1 = pcr(s, row - 15), pcr(s, row)
        if not (np.isfinite(x0) and np.isfinite(x1)):
            return False
        return not (x1 > x0 if side > 0 else x1 < x0)
    if fid in ("P14", "P16", "P18"):
        if not (np.isfinite(rng) and rng > 0):
            return False
        upper, lower = h - max(o, c), min(o, c) - l
        against, for_ = (upper, lower) if side > 0 else (lower, upper)
        if fid == "P14":
            return bool(against >= 0.5 * rng)
        if fid == "P16":
            return bool(for_ < 0.5 * rng)
        body = abs(c - o)
        return not (body >= 0.6 * rng and side * (c - o) > 0)
    if fid in ("V1", "V2"):
        if a is None or b < 1:
            return False
        vb = bar_volume(a, b)
        prev = np.array([bar_volume(a, j) for j in range(b)])
        prev = prev[np.isfinite(prev)]
        if not np.isfinite(vb) or len(prev) < 3:
            return False
        med = float(np.median(prev))
        return bool(vb < med) if fid == "V1" else bool(med > 0 and vb >= 3 * med)
    if fid == "I1":
        if row < 15:
            return False
        e = ctx.get("expiry_date")
        iv0, iv1 = option_iv(s, k, side, row - 15, e), option_iv(s, k, side, row, e)
        return bool(np.isfinite(iv0) and np.isfinite(iv1) and iv1 < iv0)
    if fid == "I2":
        oth = s.opts.get((s.atm, "P" if side > 0 else "C"))
        if oth is None or row < 5:
            return False
        p0, p1 = last_val(oth, row - 5, C), last_val(oth, row, C)
        return bool(np.isfinite(p0) and np.isfinite(p1) and p1 > p0)
    if fid == "T1":
        for bb in range(max(0, b - 6), b):
            cc = b5[bb, 3]
            if bb >= 10 and np.isfinite(cc) and ((side > 0 and cc < lo) or (side < 0 and cc > hi)):
                return True
        return False
    if fid == "T2":
        if b < 1 or not np.isfinite(b5[b - 1]).all():
            return False
        return bool(h > b5[b - 1, 1] and l < b5[b - 1, 2])
    if fid in ("D1", "D2"):
        S = X9.last_close(s.ix, row)
        T = X9.years_to_expiry(s.day, row, ctx.get("expiry_date"))
        if a is None or not (np.isfinite(S) and np.isfinite(T)):
            return False
        iv = X9.implied_vol(X9.last_close(a, row), S, k, T, X9.RATE, side > 0)
        if not np.isfinite(iv):
            return False
        d = abs(X9.bs_delta(S, k, T, X9.RATE, iv, side > 0))
        lo_, hi_ = (0.45, 0.55) if fid == "D1" else (0.55, 0.65)
        return not (lo_ <= d <= hi_)
    if fid == "O1":
        if a is None:
            return False
        sv = signed_volume(a, row, 3)
        return bool(np.isfinite(sv) and sv <= 0)
    if fid in ("S1", "S2", "S3"):
        if not np.isfinite(c):
            return False
        lv = list(ctx.get("levels", [])) + pivots(s.prev) + [s.prev[1], s.prev[2]] + swings(b5, 3, b)
        broken = hi if (side > 0) == (arm != "orb_sweep") else lo
        ahead, behind, near_a, near_b = level_check(lv, c, side, broken)
        if fid == "S1":
            return bool(near_a)
        if fid == "S2":
            return bool(np.isfinite(ahead) and np.isfinite(behind) and ahead < behind)
        return bool(near_a and near_b)
    if fid == "A1":
        med = ctx.get("bandvol_med")
        if med is None or not np.isfinite(med[row]) or med[row] <= 0:
            return False
        return bool(band_cumvol(s)[row] < med[row])
    if fid == "Q1":
        if a is None or row < 15:
            return False
        p0, p1 = last_val(a, row - 15, C), last_val(a, row, C)
        q0, q1 = last_val(a, row - 15, OI), last_val(a, row, OI)
        return bool(np.isfinite(p0 * p1 * q0 * q1) and p1 < p0 and q1 > q0)
    if fid in ("K1", "K2"):
        v = ctx.get("pick_" + fid)
        return v is not None and not v
    raise KeyError(fid)


def pick_features(s, side: int, row: int, hi: float, lo: float, b5: np.ndarray, k: float, ctx: dict) -> dict:
    """K1 / K2's inputs: R169's 11 + PCR, ATM IV, signal-bar option volume / its median, Supertrend side, EMA20 distance."""
    f = X9.features(s, side, row, hi, lo, b5, k)
    b = row // 5
    f["pcr"] = pcr(s, row)
    f["atm_iv"] = option_iv(s, k, side, row, ctx.get("expiry_date"))
    a = s.opts.get((k, "C" if side > 0 else "P"))
    vr = np.nan
    if a is not None and b >= 1:
        vb = bar_volume(a, b)
        prev = np.array([bar_volume(a, j) for j in range(b)])
        prev = prev[np.isfinite(prev)]
        if np.isfinite(vb) and len(prev) >= 3 and np.median(prev) > 0:
            vr = vb / float(np.median(prev))
    f["vol_ratio"] = vr
    f["st_side"] = float(side * ctx.get("st5", np.zeros(75, int))[b])
    e20 = ctx.get("ema20", np.full(75, np.nan))[b]
    atr = ctx.get("atr5", np.full(75, np.nan))[b]
    f["ema_dist"] = side * (b5[b, 3] - e20) / atr if np.isfinite(e20) and np.isfinite(atr) and atr > 0 else np.nan
    return f


PICK_FEATS = X9.PICK_FEATS + ("pcr", "atm_iv", "vol_ratio", "st_side", "ema_dist")


# ================================================================================================ the exit walk
@njit(cache=True)
def walk2(o, h, l, c, ixh, ixl, ixc, stdir, c0, e, trig, tgt, lad_t, be, be_pct, lf_p, lf_k, g_reach, g_keep, g_trail,
          q_tgt, ts_row, ts_need, nip_row, side, ixa_lvl, fk_row, fk_lvl, volx_atr, volx_ref, volp_atr, mom, sq):
    """1-minute rows c0 .. sq-1 -> (row, price, why). why: 0 stop, 1 lock (resting: sold at min(level, open)); 2 target,
    3 time, 5 nip, 6 ixa, 7 q target, 8 volx, 9 mom, 10 fake (sold at the next open); 4 square-off. Locks use the best high
    BEFORE the minute (R3); index rules decide at the close of the minute."""
    peak = e
    reached = False
    maxc = -1e18 if side > 0 else 1e18
    for r in range(c0, sq):
        if not np.isfinite(c[r]):
            continue
        op = o[r] if np.isfinite(o[r]) else c[r]
        lock = -1.0
        if lad_t > 0:
            for i in range(3):
                reach = (0.25, 0.50, 0.75)[i]
                keep = (0.0, 0.25, 0.50)[i]
                if peak >= e + reach * lad_t - 1e-9:
                    lv = max(e + keep * lad_t, be)
                    if lv < peak:
                        lock = max(lock, lv)
        if be_pct > 0 and peak >= e * (1.0 + be_pct) - 1e-9 and be < peak:
            lock = max(lock, be)
        for i in range(len(lf_p)):
            if peak >= e * (1.0 + lf_p[i]) - 1e-9:
                lv = e * (1.0 + lf_k[i])
                if lv < peak:
                    lock = max(lock, lv)
        if g_reach > 0 and peak >= g_reach - 1e-9:
            lv = g_keep
            if g_trail > 0:
                lv = max(lv, peak - g_trail)
            if lv < peak:
                lock = max(lock, lv)
        if volp_atr > 0:
            lv = peak - 3.0 * volp_atr
            if lv < peak and peak > e:
                lock = max(lock, lv)
        rest = max(trig, lock)
        if rest > 0.05 and l[r] <= rest:
            return r, min(rest, op), (0 if trig >= lock else 1)
        if r <= sq - 2:
            if tgt > 0 and h[r] >= e + tgt:
                return r + 1, np.nan, 2
            if q_tgt > 0 and h[r] >= q_tgt:
                return r + 1, np.nan, 7
            if ts_row > 0 and r >= ts_row and not reached:
                return r + 1, np.nan, 3
            if nip_row > 0 and r == nip_row and c[r] < e:
                return r + 1, np.nan, 5
        peak = max(peak, h[r])
        if ts_need > 0 and peak >= e + ts_need - 1e-9:
            reached = True
        if r <= sq - 2 and np.isfinite(ixc[r]):
            x = ixc[r]
            if ixa_lvl > 0 and ((side > 0 and x < ixa_lvl) or (side < 0 and x > ixa_lvl)):
                return r + 1, np.nan, 6
            if fk_row > 0 and r == fk_row:
                best = -1e18 if side > 0 else 1e18
                for rr in range(c0, r + 1):
                    if side > 0 and np.isfinite(ixh[rr]):
                        best = max(best, ixh[rr])
                    if side < 0 and np.isfinite(ixl[rr]):
                        best = min(best, ixl[rr])
                if (side > 0 and best <= fk_lvl) or (side < 0 and best >= fk_lvl):
                    return r + 1, np.nan, 10
            if volx_atr > 0:
                maxc = max(maxc, x) if side > 0 else min(maxc, x)
                if side > 0 and (x <= maxc - 3.0 * volx_atr or x >= volx_ref + 4.0 * volx_atr):
                    return r + 1, np.nan, 8
                if side < 0 and (x >= maxc + 3.0 * volx_atr or x <= volx_ref - 4.0 * volx_atr):
                    return r + 1, np.nan, 8
            if mom > 0 and r >= 1 and stdir[r] == -side and stdir[r - 1] == side:
                return r + 1, np.nan, 9
    return sq, np.nan, 4


def opt_atr(a: np.ndarray, row: int, n: int = 14) -> float:
    """The option's mean 1-minute true range over the n minutes up to `row`."""
    tr, pc = [], np.nan
    for r in range(max(0, row - n - 1), row + 1):
        hh, ll, cc = a[r, H], a[r, L], a[r, C]
        if not np.isfinite(cc):
            continue
        tr.append((hh - ll) if not np.isfinite(pc) else max(hh - ll, abs(hh - pc), abs(ll - pc)))
        pc = cc
    tr = tr[-n:]
    return float(np.mean(tr)) if len(tr) >= n // 2 else np.nan


def simulate2(s, cfg: Cfg, side: int, sig_row: int, k: float, P: float, b5: np.ndarray, ctx: dict) -> dict | None:
    a = s.opts.get((k, "C" if side > 0 else "P"))
    if a is None:
        return None
    a = a.astype(np.float64)
    c0, px = E.next_open(a, sig_row + 1, sig_row + 1 + E.FILL_TRIES)
    v = cfg.base
    if c0 < 0 or px <= P or not px > 0:
        return None
    lots = max(1, cfg.lots)
    qty = s.lot * lots
    e = float(E.FILLS.buy(np.array([px]), np.array([X9.thin_lots(a, c0, s.lot, lots)]))[0])
    stop_pts, tgt = P, v.tgt_mult * P
    for z, (zs, zt) in ZPTS.items():
        if cfg.has(z):
            stop_pts, tgt = zs, (tgt if zt is None else zt)
    trig = E.floor_tick(e - stop_pts)
    lad_t = tgt if v.ladder else 0.0
    be = e + X9.be_unit(cfg, e, qty, s.und, s.day)
    b = sig_row // 5
    sb = b5[b]
    be_pct = 0.15 if cfg.has("B15") else (0.20 if cfg.has("B20") else 0.0)
    lf = LF["LF2"] if cfg.has("LF2") else (LF["LF1"] if cfg.has("LF1") else ())
    lf_p = np.array([x for x, _ in lf], float)
    lf_k = np.array([y for _, y in lf], float)
    g_reach = g_keep = g_trail = 0.0
    if cfg.has("G1R"):
        g_reach, g_keep = be + 1500.0 / qty, be
    if cfg.has("G2R"):
        g_reach, g_keep, g_trail = be + 1500.0 / qty, be + 500.0 / qty, 1000.0 / qty
    if cfg.has("LCK"):
        g_reach, g_keep = be + 2000.0 / qty, be + 1000.0 / qty
    q_tgt = be + 2000.0 / qty if (cfg.has("Q2K") or cfg.has("QT2K")) else 0.0
    if cfg.has("Q2K") or cfg.has("QS2K") or cfg.has("LCK"):
        trig = max(trig, E.floor_tick(be - 2000.0 / qty))
    ts_row, ts_need = -1, 0.0
    if cfg.has("T45"):
        ts_row, ts_need = c0 + 45 - 1, 0.05 * e
    nip_row = c0 + 30 - 1 if cfg.has("NIP30") else -1
    ixa = 0.0
    if cfg.has("IXA") or cfg.has("IXR"):
        ixa = float(sb[2] if side > 0 else sb[1])
        if cfg.has("IXR"):
            trig = 0.0
    fk_row, fk_lvl = -1, 0.0
    if cfg.has("FK3") or cfg.has("FK5"):
        fk_row = c0 + (3 if cfg.has("FK3") else 5) - 1
        fk_lvl = float(sb[1] if side > 0 else sb[2])
    volx_atr, volx_ref = 0.0, 0.0
    if cfg.has("VOLX"):
        at = ctx.get("atr5", np.full(75, np.nan))[b]
        ref = X9.last_close(s.ix, c0 - 1)
        if np.isfinite(at) and np.isfinite(ref):
            volx_atr, volx_ref = float(at), float(ref)
    volp = 0.0
    if cfg.has("VOLP"):
        x = opt_atr(a, c0 - 1)
        volp = float(x) if np.isfinite(x) else 0.0
    mom = 1 if cfg.has("MOM") else 0
    std = ctx.get("st1", np.zeros(N, np.int64)).astype(np.int64)
    sq = E.SQ_EXPIRY if s.expiry else E.SQ_ROW
    ix = s.ix.astype(np.float64)
    r, p, why = walk2(a[:, O], a[:, H], a[:, L], a[:, C], ix[:, H], ix[:, L], ix[:, C], std, c0, e, trig, tgt, lad_t, be,
                      be_pct, lf_p, lf_k, g_reach, g_keep, g_trail, q_tgt, ts_row, ts_need, nip_row, side, ixa, fk_row,
                      fk_lvl, volx_atr, volx_ref, volp, mom, sq)
    stale = False
    if why in (0, 1):
        x = float(E.FILLS.sell(np.array([p]), np.array([X9.thin_lots(a, r, s.lot, lots)]), stop=True)[0])
    else:
        rr, p2 = E.next_open(a, r)
        if rr < 0:
            fin = np.where(np.isfinite(a[:r, C]))[0]
            rr = int(fin[-1]) if len(fin) else c0
            p2 = float(a[rr, C])
            stale = True
        r = rr
        x = float(E.FILLS.sell(np.array([p2]), np.array([X9.thin_lots(a, rr, s.lot, lots)]))[0])
    ch = X9.charge(cfg, True, e, qty, s.und, s.day) + X9.charge(cfg, False, x, qty, s.und, s.day)
    gross = (x - e) * qty
    return dict(und=s.und, day=s.day, side=side, sig_row=sig_row, k=k, entry_row=c0, exit_row=r, entry=e, exit=x,
                why=WHY[11] if stale else WHY[why], qty=qty, lots=lots, gross=gross, charges=ch, net=gross - ch,
                expiry=bool(s.expiry))


def choose_strike2(s, cfg: Cfg, side: int, row: int, expiry) -> float:
    if cfg.has("ITM2"):
        return E.strike_for(s, side, 2)
    if cfg.has("D70"):
        return X9.strike_by_delta(s, side, row, 0.70, expiry)
    return s.atm


def run_session2(s, cfg: Cfg, ctx: dict | None = None, keep_fn=None) -> list:
    """As r169lib.run_session with R170's rules. Filtered breaks stay in the list (skipped=True), holding their side.
    DCAP: no new entry once the day's trades closed before the signal bar net <= -Rs 4,000."""
    ctx = ctx or {}
    b5 = X9.bars5(s.ix)
    hi, lo = E.opening_range(b5)
    if not (np.isfinite(hi) and np.isfinite(lo)) or not np.isfinite(s.atm):
        return []
    v = cfg.base
    P = K9.K3.PTS[s.und]
    first, last = v.win
    out, per_side, n, free_bar = [], {1: 0, -1: 0}, 0, -1
    for b in range(first, last + 1):
        if b <= free_bar:
            continue
        if n >= v.max_entries:
            break
        side = E.signal(v.arm, b5, b, hi, lo)
        if not side or (v.side_cap and per_side[side] >= v.side_cap):
            continue
        row = 5 * b + 4
        if cfg.has("DCAP"):
            closed = sum(t["net"] for t in out if not t["skipped"] and t["exit_row"] <= row)
            if closed <= -DCAP_RS:
                break
        k = choose_strike2(s, cfg, side, row, ctx.get("expiry_date"))
        t = simulate2(s, cfg, side, row, k, P, b5, ctx)
        if t is None:
            continue
        c2 = dict(ctx)
        if keep_fn is not None:
            c2.update(keep_fn(s, side, row, pick_features(s, side, row, hi, lo, b5, k, ctx)))
        skipped = [fid for fid in cfg.filters if skip2(fid, s, v.arm, side, row, hi, lo, b5, k, c2)]
        t["skipped"] = bool(skipped)
        t["skip_by"] = skipped
        t["bar"] = b
        out.append(t)
        per_side[side] += 1
        n += 1
        free_bar = t["exit_row"] // 5
    return out


taken = X9.taken
score = X9.score
gate1 = X9.gate1
within_limits = X9.within_limits
twin_p = X9.twin_p
bh_total = X9.bh_total
