"""R202 pattern detectors (PREREG.md sections 3 - 4): 21 price-action patterns on the index's 5-minute bars.

Pure numpy / Python, no data and no engine here (test_r202.py). Every detector scans bars in time order and, at bar b,
reads only bars <= b (a pivot is known only 3 bars after it). Signals are (bar, side): 'C' bullish (buy the call), 'P'
bearish (buy the put). Signal source: the index. Plain-English notes, 21 Nov 2026.
"""
from __future__ import annotations

import math

import numpy as np

PIV = 3                    # pivot strength (bars each side)
ATR_N, ATR_MIN = 14, 10    # ATR window and the finite bars it needs
TOL = 0.25                 # tol = 0.25 x ATR
LEVEL_GROUP = 0.0010       # levels: pivots within 0.10% of the group's first price
LEVEL_TOUCH = 3
SHAPE_SESS = 2             # pivots, lines, zones: today and the 2 sessions before
LEVEL_SESS = 5             # levels: today and the 5 sessions before
EMA_N = 20
RETEST_BARS = 12
BOX_N, BOX_ATR = 12, 1.5
DT_GAP = (5, 40)
DT_NECK, DT_WAIT = 1.0, 20
HS_SPAN, HS_HEAD, HS_SHOULDER, HS_WAIT = (5, 60), 0.5, 0.5, 20
TRI_SPAN = 40
FLAG_POLE, FLAG_POLE_BARS, FLAG_MIN, FLAG_MAX, FLAG_FRAC = 3.0, 6, 3, 12, 0.5
OB_DISP, OB_LOOK, OB_WAIT = 2.0, 5, 30
FVG_MIN, FVG_WAIT = 0.3, 30
EQ_TOL, EQ_GAP, EQ_SPAN = 0.2, 5, 60
PIN_WICK, PIN_SHARE, PIN_RANGE, PIN_LOOK = 2.0, 0.6, 0.8, 12

PATTERNS = ("P01", "P02", "P03", "P04", "P06", "P07", "P09", "P10", "P11", "P12", "P13", "P14", "P15", "P16", "P17",
            "P18", "P19", "P21", "P22", "P24", "P25")
NAMES = {
    "P01": "1 Support bounce", "P02": "2 Resistance rejection", "P03": "3 Trend (pullback to EMA20 with HH/HL or LH/LL)",
    "P04": "4 Trendline bounce", "P06": "6 Retest of a broken level", "P07": "7 Consolidation (tight box) breakout",
    "P09": "9 Higher high / higher low (buy the new HL)", "P10": "10 Lower high / lower low (sell the new LH)",
    "P11": "11 Double top", "P12": "12 Double bottom", "P13": "13 Head and shoulders", "P14": "14 Inverse head and shoulders",
    "P15": "15 Ascending triangle", "P16": "16 Descending triangle", "P17": "17 Symmetrical triangle", "P18": "18 Flag",
    "P19": "19 Wedge (rising = put, falling = call)", "P21": "21 Order block", "P22": "22 Fair value gap",
    "P24": "24 Equal highs / lows sweep", "P25": "25 Candlestick rejection (long wick)",
}


def fin(*xs) -> bool:
    return all(x == x for x in xs)


# ================================================================================================ bars
def bars5(idx: np.ndarray) -> np.ndarray:
    """(4, 375) 1-minute O H L C on the 09:15 grid -> (4, 75) 5-minute bars (NaN where a bar has no finite minute)."""
    a = np.asarray(idx, float)
    n = a.shape[1] // 5
    out = np.full((4, n), np.nan)
    for b in range(n):
        o, h, lo, c = (a[k, 5 * b:5 * b + 5] for k in range(4))
        ok = np.isfinite(c) & np.isfinite(h) & np.isfinite(lo)
        if not ok.any():
            continue
        oo = o[np.isfinite(o)]
        cc = c[np.isfinite(c)]
        out[0, b] = oo[0] if len(oo) else cc[0]
        out[1, b] = np.nanmax(h)
        out[2, b] = np.nanmin(lo)
        out[3, b] = cc[-1]
    return out


# ================================================================================================ context
class Ctx:
    """One continuous series: O H L C (n,) and sess (n,) = sessions before today (0 = today). Precomputes the causal
    ATR, EMA20 and the pivots with the bar at which each becomes known."""

    def __init__(self, O, H, L, C, sess):
        self.O, self.H, self.L, self.C = (np.asarray(x, float) for x in (O, H, L, C))
        self.sess = np.asarray(sess, int)
        n = self.n = len(self.C)
        tr = np.full(n, np.nan)
        for i in range(n):
            if fin(self.H[i], self.L[i]):
                pc = self.C[i - 1] if i > 0 else np.nan
                tr[i] = (self.H[i] - self.L[i]) if not fin(pc) else max(self.H[i], pc) - min(self.L[i], pc)
        self.atr = np.full(n, np.nan)
        for i in range(n):
            w = tr[max(0, i - ATR_N + 1):i + 1]
            w = w[np.isfinite(w)]
            if len(w) >= ATR_MIN:
                self.atr[i] = float(w.mean())
        self.ema = np.full(n, np.nan)
        k = 2.0 / (EMA_N + 1)
        e, cnt = np.nan, 0
        for i in range(n):
            c = self.C[i]
            if fin(c):
                e = c if not fin(e) else e + k * (c - e)
                cnt += 1
            self.ema[i] = e if cnt >= EMA_N else np.nan
        # pivots: (bar, price), known at bar + PIV
        self.ph, self.pl = [], []
        for i in range(PIV, n - PIV):
            w = self.H[i - PIV:i + PIV + 1]
            if np.isfinite(w).all() and self.H[i] > w[:PIV].max() and self.H[i] >= w[PIV + 1:].max():
                self.ph.append((i, float(self.H[i])))
            w = self.L[i - PIV:i + PIV + 1]
            if np.isfinite(w).all() and self.L[i] < w[:PIV].min() and self.L[i] <= w[PIV + 1:].min():
                self.pl.append((i, float(self.L[i])))

    def known(self, piv: list, b: int, max_sess: int = SHAPE_SESS) -> list:
        """Pivots known at b's close (bar + 3 <= b) from sessions <= max_sess before today, oldest first."""
        return [p for p in piv if p[0] + PIV <= b and self.sess[p[0]] <= max_sess]

    def bar_ok(self, b: int) -> bool:
        return fin(self.O[b], self.H[b], self.L[b], self.C[b], self.atr[b])


def line_at(p1: tuple, p2: tuple, b: int) -> float:
    (i1, v1), (i2, v2) = p1, p2
    return v1 + (v2 - v1) * (b - i1) / (i2 - i1)


def levels(ctx: Ctx, b: int) -> list:
    """S/R levels known at b: [(level, key)], key = the bar of the group's first pivot (the level's identity)."""
    pts = sorted(ctx.known(ctx.ph, b, LEVEL_SESS) + ctx.known(ctx.pl, b, LEVEL_SESS), key=lambda p: (p[1], p[0]))
    out, grp = [], []
    for p in pts:
        if grp and abs(p[1] - grp[0][1]) <= LEVEL_GROUP * grp[0][1]:
            grp.append(p)
            continue
        if len(grp) >= LEVEL_TOUCH:
            out.append((float(np.mean([g[1] for g in grp])), min(g[0] for g in grp)))
        grp = [p]
    if len(grp) >= LEVEL_TOUCH:
        out.append((float(np.mean([g[1] for g in grp])), min(g[0] for g in grp)))
    return out


# ================================================================================================ detectors
# Each takes (ctx, start) and returns [(b, side)] for b >= start, reading only bars <= b at each b.

def p01(ctx, start):
    out, used = [], set()
    for b in range(max(start, 1), ctx.n):
        if not ctx.bar_ok(b) or not fin(ctx.C[b - 1]):
            continue
        tol = TOL * ctx.atr[b]
        best = None
        for L, key in levels(ctx, b):
            if key in used:
                continue
            if ctx.C[b - 1] > L and ctx.L[b] <= L + tol and ctx.C[b] > L and ctx.C[b] > ctx.O[b]:
                if best is None or L > best[0]:
                    best = (L, key)
        if best:
            used.add(best[1])
            out.append((b, "C"))
    return out


def p02(ctx, start):
    out, used = [], set()
    for b in range(max(start, 1), ctx.n):
        if not ctx.bar_ok(b) or not fin(ctx.C[b - 1]):
            continue
        tol = TOL * ctx.atr[b]
        best = None
        for L, key in levels(ctx, b):
            if key in used:
                continue
            if ctx.C[b - 1] < L and ctx.H[b] >= L - tol and ctx.C[b] < L and ctx.C[b] < ctx.O[b]:
                if best is None or L < best[0]:
                    best = (L, key)
        if best:
            used.add(best[1])
            out.append((b, "P"))
    return out


def _struct(ctx, b) -> int:
    """+1 when the last two known pivot highs and lows are both rising, -1 both falling, 0 otherwise."""
    ph, pl = ctx.known(ctx.ph, b), ctx.known(ctx.pl, b)
    if len(ph) < 2 or len(pl) < 2:
        return 0
    if ph[-1][1] > ph[-2][1] and pl[-1][1] > pl[-2][1]:
        return 1
    if ph[-1][1] < ph[-2][1] and pl[-1][1] < pl[-2][1]:
        return -1
    return 0


def _trend_cond(ctx, b):
    if not ctx.bar_ok(b) or not fin(ctx.ema[b]):
        return None
    s = _struct(ctx, b)
    tol, e = TOL * ctx.atr[b], ctx.ema[b]
    if s > 0 and ctx.L[b] <= e + tol and ctx.C[b] > e and ctx.C[b] > ctx.O[b]:
        return "C"
    if s < 0 and ctx.H[b] >= e - tol and ctx.C[b] < e and ctx.C[b] < ctx.O[b]:
        return "P"
    return None


def p03(ctx, start):
    out = []
    for b in range(max(start, 1), ctx.n):
        s = _trend_cond(ctx, b)
        if s and _trend_cond(ctx, b - 1) != s:            # edge: a new pullback
            out.append((b, s))
    return out


def p04(ctx, start):
    out, used = [], set()
    for b in range(start, ctx.n):
        if not ctx.bar_ok(b):
            continue
        tol = TOL * ctx.atr[b]
        for piv, side in ((ctx.pl, "C"), (ctx.ph, "P")):
            k = ctx.known(piv, b)
            if len(k) < 2:
                continue
            p1, p2 = k[-2], k[-1]
            if p2[0] - p1[0] < 5 or (p1, p2) in used:
                continue
            if side == "C" and not p2[1] > p1[1]:
                continue
            if side == "P" and not p2[1] < p1[1]:
                continue
            ln = line_at(p1, p2, b)
            seg = range(p2[0], b)
            if side == "C":
                if any(fin(ctx.C[j]) and ctx.C[j] < line_at(p1, p2, j) for j in seg):
                    continue
                if ctx.L[b] <= ln + tol and ctx.C[b] > ln and ctx.C[b] > ctx.O[b]:
                    used.add((p1, p2))
                    out.append((b, "C"))
            else:
                if any(fin(ctx.C[j]) and ctx.C[j] > line_at(p1, p2, j) for j in seg):
                    continue
                if ctx.H[b] >= ln - tol and ctx.C[b] < ln and ctx.C[b] < ctx.O[b]:
                    used.add((p1, p2))
                    out.append((b, "P"))
    return out


def p06(ctx, start):
    """Retest: a level broken at j (by more than tol), the first bar within 12 after j that comes back to it and holds."""
    out = []
    first = int(np.argmax(ctx.sess <= SHAPE_SESS)) if (ctx.sess <= SHAPE_SESS).any() else ctx.n
    pending = []                                              # (j, level, side, key)
    seen = set()
    for b in range(max(first, 1), ctx.n):
        if not ctx.bar_ok(b) or not fin(ctx.C[b - 1]):
            continue
        tol = TOL * ctx.atr[b]
        fired = []
        keep = []
        for (j, L, side, key) in pending:
            if b - j > RETEST_BARS:
                continue
            if side == "C" and ctx.L[b] <= L + tol and ctx.C[b] > L:
                fired.append(side)
                continue
            if side == "P" and ctx.H[b] >= L - tol and ctx.C[b] < L:
                fired.append(side)
                continue
            keep.append((j, L, side, key))
        pending = keep
        if b >= start:
            for s in dict.fromkeys(fired):
                out.append((b, s))
        for L, key in levels(ctx, b - 1):                     # breaks at b of the levels known before b
            if ctx.C[b - 1] <= L < ctx.C[b] - tol and (key, "C", b) not in seen:
                pending.append((b, L, "C", key))
                seen.add((key, "C", b))
            if ctx.C[b - 1] >= L > ctx.C[b] + tol and (key, "P", b) not in seen:
                pending.append((b, L, "P", key))
                seen.add((key, "P", b))
    return out


def _box(ctx, b):
    if b < BOX_N or not ctx.bar_ok(b) or not fin(ctx.atr[b - 1]):
        return None
    hh, ll = ctx.H[b - BOX_N:b], ctx.L[b - BOX_N:b]
    if not (np.isfinite(hh).all() and np.isfinite(ll).all()):
        return None
    top, bot = hh.max(), ll.min()
    if top - bot > BOX_ATR * ctx.atr[b - 1]:
        return None
    if ctx.C[b] > top:
        return "C"
    if ctx.C[b] < bot:
        return "P"
    return None


def p07(ctx, start):
    out = []
    for b in range(max(start, 1), ctx.n):
        s = _box(ctx, b)
        if s and _box(ctx, b - 1) != s:
            out.append((b, s))
    return out


def _new_swing(ctx, b, piv, other, side):
    """A pivot (of `piv`) becomes known exactly at b, beyond the previous one, and the other pivots' last two agree."""
    if not ctx.bar_ok(b):
        return False
    k = ctx.known(piv, b)
    if len(k) < 2 or k[-1][0] + PIV != b:
        return False
    o = ctx.known(other, b)
    if len(o) < 2:
        return False
    if side == "C":
        return k[-1][1] > k[-2][1] and o[-1][1] > o[-2][1] and ctx.C[b] > k[-1][1]
    return k[-1][1] < k[-2][1] and o[-1][1] < o[-2][1] and ctx.C[b] < k[-1][1]


def p09(ctx, start):
    return [(b, "C") for b in range(start, ctx.n) if _new_swing(ctx, b, ctx.pl, ctx.ph, "C")]


def p10(ctx, start):
    return [(b, "P") for b in range(start, ctx.n) if _new_swing(ctx, b, ctx.ph, ctx.pl, "P")]


def _double(ctx, start, top: bool):
    out, used = [], set()
    piv = ctx.ph if top else ctx.pl
    for b in range(start, ctx.n):
        if not ctx.bar_ok(b):
            continue
        k = ctx.known(piv, b)
        if len(k) < 2:
            continue
        p1, p2 = k[-2], k[-1]
        if (p1, p2) in used or not (DT_GAP[0] <= p2[0] - p1[0] <= DT_GAP[1]) or b - p2[0] > DT_WAIT:
            continue
        a = ctx.atr[b]
        if abs(p2[1] - p1[1]) > TOL * a:
            continue
        seg = slice(p1[0] + 1, p2[0])
        if top:
            neck = np.nanmin(ctx.L[seg]) if p2[0] - p1[0] > 1 else np.nan
            if not fin(neck) or neck > min(p1[1], p2[1]) - DT_NECK * a:
                continue
            cap = max(p1[1], p2[1]) + TOL * a
            if any(fin(ctx.C[j]) and ctx.C[j] > cap for j in range(p2[0] + 1, b)):
                used.add((p1, p2))
                continue
            if ctx.C[b] < neck and all(not (fin(ctx.C[j]) and ctx.C[j] < neck) for j in range(p2[0] + 1, b)):
                used.add((p1, p2))
                out.append((b, "P"))
        else:
            neck = np.nanmax(ctx.H[seg]) if p2[0] - p1[0] > 1 else np.nan
            if not fin(neck) or neck < max(p1[1], p2[1]) + DT_NECK * a:
                continue
            cap = min(p1[1], p2[1]) - TOL * a
            if any(fin(ctx.C[j]) and ctx.C[j] < cap for j in range(p2[0] + 1, b)):
                used.add((p1, p2))
                continue
            if ctx.C[b] > neck and all(not (fin(ctx.C[j]) and ctx.C[j] > neck) for j in range(p2[0] + 1, b)):
                used.add((p1, p2))
                out.append((b, "C"))
    return out


def p11(ctx, start):
    return _double(ctx, start, True)


def p12(ctx, start):
    return _double(ctx, start, False)


def _hs(ctx, start, top: bool):
    out, used = [], set()
    piv = ctx.ph if top else ctx.pl
    for b in range(start, ctx.n):
        if not ctx.bar_ok(b):
            continue
        k = ctx.known(piv, b)
        if len(k) < 3:
            continue
        ls, hd, rs = k[-3], k[-2], k[-1]
        key = (ls, hd, rs)
        if key in used or not (HS_SPAN[0] <= rs[0] - ls[0] <= HS_SPAN[1]) or b - rs[0] > HS_WAIT:
            continue
        a = ctx.atr[b]
        sg = 1.0 if top else -1.0
        if not sg * hd[1] >= sg * (ls[1] if sg * ls[1] > sg * rs[1] else rs[1]) + HS_HEAD * a:
            continue
        if abs(ls[1] - rs[1]) > HS_SHOULDER * a:
            continue
        s1, s2 = slice(ls[0] + 1, hd[0]), slice(hd[0] + 1, rs[0])
        if hd[0] - ls[0] < 2 or rs[0] - hd[0] < 2:
            continue
        arr = ctx.L if top else ctx.H
        seg1, seg2 = arr[s1], arr[s2]
        if not (np.isfinite(seg1).any() and np.isfinite(seg2).any()):
            continue
        if top:
            i1, i2 = s1.start + int(np.nanargmin(seg1)), s2.start + int(np.nanargmin(seg2))
        else:
            i1, i2 = s1.start + int(np.nanargmax(seg1)), s2.start + int(np.nanargmax(seg2))
        t1, t2 = (i1, float(arr[i1])), (i2, float(arr[i2]))
        nk = line_at(t1, t2, b)
        prior = [j for j in range(rs[0] + 1, b) if fin(ctx.C[j]) and sg * ctx.C[j] < sg * line_at(t1, t2, j)]
        if prior:
            used.add(key)
            continue
        if sg * ctx.C[b] < sg * nk:
            used.add(key)
            out.append((b, "P" if top else "C"))
    return out


def p13(ctx, start):
    return _hs(ctx, start, True)


def p14(ctx, start):
    return _hs(ctx, start, False)


def _last2(ctx, b):
    ph, pl = ctx.known(ctx.ph, b), ctx.known(ctx.pl, b)
    if len(ph) < 2 or len(pl) < 2:
        return None
    h1, h2, l1, l2 = ph[-2], ph[-1], pl[-2], pl[-1]
    if b - min(h1[0], l1[0]) > TRI_SPAN:
        return None
    return h1, h2, l1, l2


def _tri(ctx, start, kind):
    out, used = [], set()
    for b in range(max(start, 1), ctx.n):
        if not ctx.bar_ok(b) or not fin(ctx.C[b - 1]):
            continue
        x = _last2(ctx, b)
        if x is None:
            continue
        h1, h2, l1, l2 = x
        key = (h1, h2, l1, l2)
        if key in used:
            continue
        tol = TOL * ctx.atr[b]
        sig = None
        if kind == "ASC":
            if abs(h2[1] - h1[1]) <= tol and l2[1] >= l1[1] + tol:
                lvl = max(h1[1], h2[1])
                if ctx.C[b] > lvl and not ctx.C[b - 1] > lvl:
                    sig = "C"
        elif kind == "DESC":
            if abs(l2[1] - l1[1]) <= tol and h2[1] <= h1[1] - tol:
                lvl = min(l1[1], l2[1])
                if ctx.C[b] < lvl and not ctx.C[b - 1] < lvl:
                    sig = "P"
        elif kind == "SYM":
            if h2[1] <= h1[1] - tol and l2[1] >= l1[1] + tol:
                up, dn = line_at(h1, h2, b), line_at(l1, l2, b)
                if ctx.C[b] > up and not ctx.C[b - 1] > line_at(h1, h2, b - 1):
                    sig = "C"
                elif ctx.C[b] < dn and not ctx.C[b - 1] < line_at(l1, l2, b - 1):
                    sig = "P"
        elif kind == "WEDGE":
            sh = (h2[1] - h1[1]) / (h2[0] - h1[0])
            sl = (l2[1] - l1[1]) / (l2[0] - l1[0])
            if h2[1] < h1[1] and l2[1] < l1[1] and sh < sl:            # falling, converging: break up
                if ctx.C[b] > line_at(h1, h2, b) and not ctx.C[b - 1] > line_at(h1, h2, b - 1):
                    sig = "C"
            elif h2[1] > h1[1] and l2[1] > l1[1] and sl > sh:          # rising, converging: break down
                if ctx.C[b] < line_at(l1, l2, b) and not ctx.C[b - 1] < line_at(l1, l2, b - 1):
                    sig = "P"
        if sig:
            used.add(key)
            out.append((b, sig))
    return out


def p15(ctx, start):
    return _tri(ctx, start, "ASC")


def p16(ctx, start):
    return _tri(ctx, start, "DESC")


def p17(ctx, start):
    return _tri(ctx, start, "SYM")


def p19(ctx, start):
    return _tri(ctx, start, "WEDGE")


def p18(ctx, start):
    out, used = [], set()
    for b in range(start, ctx.n):
        if not ctx.bar_ok(b):
            continue
        a = ctx.atr[b]
        hit = None
        for p in range(b - FLAG_MAX - 1, b - FLAG_MIN):
            if p - FLAG_POLE_BARS + 1 < 0 or ctx.sess[p - FLAG_POLE_BARS + 1] > SHAPE_SESS:
                continue
            pole = slice(p - FLAG_POLE_BARS + 1, p + 1)
            fl = slice(p + 1, b)
            ph_, pl_, fh, flo = ctx.H[pole], ctx.L[pole], ctx.H[fl], ctx.L[fl]
            if not (np.isfinite(ph_).all() and np.isfinite(pl_).all() and np.isfinite(fh).all()
                    and np.isfinite(flo).all() and fin(ctx.C[p])):
                continue
            for side in ("C", "P"):
                if (p, side) in used:
                    continue
                if side == "C":
                    size = ctx.C[p] - pl_.min()
                    top = ctx.H[p]
                    if size < FLAG_POLE * a:
                        continue
                    if fh.max() - flo.min() > FLAG_FRAC * size or flo.min() < top - FLAG_FRAC * size:
                        continue
                    if ctx.C[b] > fh.max():
                        hit = hit or (p, side)
                else:
                    size = ph_.max() - ctx.C[p]
                    bot = ctx.L[p]
                    if size < FLAG_POLE * a:
                        continue
                    if fh.max() - flo.min() > FLAG_FRAC * size or fh.max() > bot + FLAG_FRAC * size:
                        continue
                    if ctx.C[b] < flo.min():
                        hit = hit or (p, side)
        if hit:
            used.add(hit)
            out.append((b, hit[1]))
    return out


def p21(ctx, start):
    """Order block: a 3-bar displacement (>= 2 ATR) that closes beyond the last known pivot, the last opposite bar in
    the 5 before it is the zone; the first bar that returns into the zone and closes with the move."""
    out = []
    first = int(np.argmax(ctx.sess <= SHAPE_SESS)) if (ctx.sess <= SHAPE_SESS).any() else ctx.n
    zones = []                                                # (made_at, lo, hi, side)
    for b in range(first, ctx.n):
        if not ctx.bar_ok(b):
            continue
        fired = None
        keep = []
        for (m, lo, hi, side) in zones:
            if b - m > OB_WAIT:
                continue
            if side == "C":
                if ctx.C[b] < lo:
                    continue
                if fired is None and ctx.L[b] <= hi and ctx.C[b] >= lo and ctx.C[b] > ctx.O[b]:
                    fired = "C"
                    continue
            else:
                if ctx.C[b] > hi:
                    continue
                if fired is None and ctx.H[b] >= lo and ctx.C[b] <= hi and ctx.C[b] < ctx.O[b]:
                    fired = "P"
                    continue
            keep.append((m, lo, hi, side))
        zones = keep
        if fired and b >= start:
            out.append((b, fired))
        # a displacement ending at b (bars d = b-2 .. b)
        d = b - 2
        if d - OB_LOOK < 0 or ctx.sess[d - OB_LOOK] > SHAPE_SESS or not fin(ctx.O[d]):
            continue
        a = ctx.atr[b]
        mv = ctx.C[b] - ctx.O[d]
        ph, pl = ctx.known(ctx.ph, b), ctx.known(ctx.pl, b)
        if mv >= OB_DISP * a and ph and ctx.C[b] > ph[-1][1]:
            for j in range(d - 1, d - OB_LOOK - 1, -1):
                if fin(ctx.O[j], ctx.C[j]) and ctx.C[j] < ctx.O[j]:
                    zones.append((b, float(ctx.L[j]), float(ctx.H[j]), "C"))
                    break
        if -mv >= OB_DISP * a and pl and ctx.C[b] < pl[-1][1]:
            for j in range(d - 1, d - OB_LOOK - 1, -1):
                if fin(ctx.O[j], ctx.C[j]) and ctx.C[j] > ctx.O[j]:
                    zones.append((b, float(ctx.L[j]), float(ctx.H[j]), "P"))
                    break
    return out


def p22(ctx, start):
    """Fair value gap: a 3-bar gap of >= 0.3 ATR; the first bar that returns into it and closes with the gap."""
    out = []
    first = int(np.argmax(ctx.sess <= SHAPE_SESS)) if (ctx.sess <= SHAPE_SESS).any() else ctx.n
    zones = []
    for b in range(first, ctx.n):
        if not ctx.bar_ok(b):
            continue
        fired = None
        keep = []
        for (m, lo, hi, side) in zones:
            if b - m > FVG_WAIT:
                continue
            if side == "C":
                if ctx.C[b] < lo:
                    continue
                if fired is None and ctx.L[b] <= hi and ctx.C[b] >= lo and ctx.C[b] > ctx.O[b]:
                    fired = "C"
                    continue
            else:
                if ctx.C[b] > hi:
                    continue
                if fired is None and ctx.H[b] >= lo and ctx.C[b] <= hi and ctx.C[b] < ctx.O[b]:
                    fired = "P"
                    continue
            keep.append((m, lo, hi, side))
        zones = keep
        if fired and b >= start:
            out.append((b, fired))
        i = b
        if i - 2 < 0 or ctx.sess[i - 2] > SHAPE_SESS or not fin(ctx.H[i - 2], ctx.L[i - 2]):
            continue
        a = ctx.atr[b]
        if ctx.L[i] - ctx.H[i - 2] >= FVG_MIN * a:
            zones.append((i, float(ctx.H[i - 2]), float(ctx.L[i]), "C"))
        if ctx.L[i - 2] - ctx.H[i] >= FVG_MIN * a:
            zones.append((i, float(ctx.H[i]), float(ctx.L[i - 2]), "P"))
    return out


def p24(ctx, start):
    out, used = [], set()
    for b in range(start, ctx.n):
        if not ctx.bar_ok(b):
            continue
        a = ctx.atr[b]
        sig = None
        for piv, side in ((ctx.pl, "C"), (ctx.ph, "P")):
            k = [p for p in ctx.known(piv, b) if b - p[0] <= EQ_SPAN]
            # the most recent equal pair not yet swept
            for x in range(len(k) - 1, 0, -1):
                p2 = k[x]
                for y in range(x - 1, -1, -1):
                    p1 = k[y]
                    if p2[0] - p1[0] < EQ_GAP or abs(p2[1] - p1[1]) > EQ_TOL * a or (p1, p2) in used:
                        continue
                    pool = min(p1[1], p2[1]) if side == "C" else max(p1[1], p2[1])
                    # swept before b (between p2 known and b-1)? then spent
                    spent = any(fin(ctx.L[j] if side == "C" else ctx.H[j]) and
                                ((ctx.L[j] < pool) if side == "C" else (ctx.H[j] > pool))
                                for j in range(p2[0] + 1, b))
                    if spent:
                        used.add((p1, p2))
                        continue
                    if side == "C" and ctx.L[b] < pool and ctx.C[b] > pool:
                        used.add((p1, p2))
                        sig = sig or "C"
                    elif side == "P" and ctx.H[b] > pool and ctx.C[b] < pool:
                        used.add((p1, p2))
                        sig = sig or "P"
                    elif (ctx.L[b] < pool) if side == "C" else (ctx.H[b] > pool):
                        used.add((p1, p2))                          # swept without closing back: spent
        if sig:
            out.append((b, sig))
    return out


def p25(ctx, start):
    out = []
    for b in range(max(start, PIN_LOOK), ctx.n):
        if not ctx.bar_ok(b):
            continue
        o, h, lo, c, a = ctx.O[b], ctx.H[b], ctx.L[b], ctx.C[b], ctx.atr[b]
        rng = h - lo
        if rng < PIN_RANGE * a or rng <= 0:
            continue
        body = abs(c - o)
        lw, uw = min(o, c) - lo, h - max(o, c)
        prev_l, prev_h = ctx.L[b - PIN_LOOK:b], ctx.H[b - PIN_LOOK:b]
        if not (np.isfinite(prev_l).all() and np.isfinite(prev_h).all()):
            continue
        if lw >= PIN_WICK * body and lw >= PIN_SHARE * rng and lo <= prev_l.min():
            out.append((b, "C"))
        elif uw >= PIN_WICK * body and uw >= PIN_SHARE * rng and h >= prev_h.max():
            out.append((b, "P"))
    return out


DETECT = {"P01": p01, "P02": p02, "P03": p03, "P04": p04, "P06": p06, "P07": p07, "P09": p09, "P10": p10, "P11": p11,
          "P12": p12, "P13": p13, "P14": p14, "P15": p15, "P16": p16, "P17": p17, "P18": p18, "P19": p19, "P21": p21,
          "P22": p22, "P24": p24, "P25": p25}


# ================================================================================================ a session's signals
FIRST_BAR, LAST_BAR = 5, 62          # entry rows 5b+5 within 09:45 (30) .. 14:30 (315)


def series(bars_by_day: list) -> tuple:
    """bars_by_day: oldest first, today last, each (4, 75). Returns (O, H, L, C, sess, today's first index)."""
    k = len(bars_by_day)
    O, H, L, C, S = [], [], [], [], []
    for i, b5 in enumerate(bars_by_day):
        O.append(b5[0]); H.append(b5[1]); L.append(b5[2]); C.append(b5[3])
        S.append(np.full(b5.shape[1], k - 1 - i))
    t0 = sum(x.shape[1] for x in bars_by_day[:-1])
    return np.concatenate(O), np.concatenate(H), np.concatenate(L), np.concatenate(C), np.concatenate(S), t0


def day_signals(bars_by_day: list, patterns=PATTERNS) -> dict:
    """{pattern: [(entry row, side)]} for today (the last session), signals in the entry window only, in time order;
    at most one signal a bar (a call and a put on the same bar: both dropped as ambiguous)."""
    O, H, L, C, S, t0 = series(bars_by_day)
    ctx = Ctx(O, H, L, C, S)
    out = {}
    for p in patterns:
        sig = DETECT[p](ctx, t0 + FIRST_BAR)
        by = {}
        for b, s in sig:
            lb = b - t0
            if FIRST_BAR <= lb <= LAST_BAR:
                by.setdefault(lb, set()).add(s)
        out[p] = [(5 * lb + 5, next(iter(v))) for lb, v in sorted(by.items()) if len(v) == 1]
    return out


def book(sigs: list, exits: list, max_day: int = 3) -> list:
    """The one-position book: sigs [(er, side)] in time order, exits[i] = the exit row of signal i if taken (None = no
    fill). Returns the indices taken: a signal is taken when its entry row is after the last taken trade's exit row;
    at most max_day a day."""
    taken, last_x = [], -1
    for i, (er, _) in enumerate(sigs):
        if len(taken) >= max_day:
            break
        if er <= last_x or exits[i] is None:
            continue
        taken.append(i)
        last_x = exits[i]
    return taken


def math_ok() -> bool:  # pragma: no cover - keeps `math` used for future helpers
    return math.isfinite(1.0)
