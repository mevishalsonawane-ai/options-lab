"""R4 signals (PREREG.md): magic numbers, Gann, Fibonacci, harmonics -> trade requests (lib4.SReq).

Every rule uses only data known at its signal minute s (close of minute s); entry is the option open at s+1.
"""
from __future__ import annotations

import math
from datetime import date

import numpy as np

import lib4 as L4
from lib4 import col, W0, W1, SQ
import sigs as H37  # h37: bars (continuous, anchored 09:15), atr, zigzag (confirmed pivots)

NAN = float("nan")


def ok_s(s):
    return W0 <= s <= W1


# ------------------------------------------------------------------------------------------------ helpers
def bars5_day(P, di):
    o, h, l, c = P["o"][di], P["h"][di], P["l"][di], P["c"][di]
    n = 75
    O = np.array([o[5 * b] if np.isfinite(o[5 * b]) else c[5 * b] for b in range(n)])
    H = np.array([np.nanmax(np.r_[h[5 * b:5 * b + 5], c[5 * b:5 * b + 5]]) for b in range(n)])
    Lw = np.array([np.nanmin(np.r_[l[5 * b:5 * b + 5], c[5 * b:5 * b + 5]]) for b in range(n)])
    C = c[4::5][:n]
    return O, H, Lw, C


def sq9_grid(ref, step):
    base = math.floor(math.sqrt(ref)) - 2
    return (base + step * np.arange(0, int(4 / step) + 1)) ** 2


# ------------------------------------------------------------------------------------------------ magic numbers / SQ9
def sq9_rule(R, P, di, fam, ref, start, step, ks):
    g = sq9_grid(ref, step)
    idx = int(np.argmax(g > ref))
    if idx < 4 or idx + 4 >= len(g):
        return
    buy, sell = g[idx], g[idx - 1]
    c = P["c"][di]
    for s in range(max(start, W0), W1 + 1):
        if c[s] >= buy:
            side = 0
        elif c[s] <= sell:
            side = 1
        else:
            continue
        nat = {}
        for k in ks:
            if side == 0:
                nat[f"NATk{k}"] = (sell, g[idx + k] * 0.9995)
            else:
                nat[f"NATk{k}"] = (buy, g[idx - 1 - k] * 1.0005)
        L4.add_std(R, fam, di, s, side, extra_nat=nat)
        return


def magic(R, P, di, B5):
    c, o = P["c"][di], P["o"][di]
    u = P["u"]
    pc = P["pclose"][di]
    refs = {"r0925": (c[col(9, 25)], col(9, 26)), "rOpen": (o[0] if np.isfinite(o[0]) else c[0], 1), "rPC": (pc, 1)}
    for nm, (ref, st) in refs.items():
        if np.isfinite(ref):
            sq9_rule(R, P, di, f"M1_SQ9I|{nm}", ref, st, 0.125, (1, 3))
    for nm in ("r0925", "rPC"):
        ref, st = refs[nm]
        if np.isfinite(ref):
            sq9_rule(R, P, di, f"M2_SQ9H|{nm}", ref, st, 0.25, (1,))
    O, H, Lw, C = B5
    minor = 500 if u == "BANKNIFTY" else 100
    major = 1000 if u == "BANKNIFTY" else 500
    # M3 magnet
    for gnm, G in (("minor", minor), ("major", major)):
        used = set()
        for b in range(1, 75):
            s = 5 * b + 4
            if not ok_s(s) or not np.isfinite(C[b]):
                continue
            x = C[b]
            lu, ld = math.ceil(x / G) * G, math.floor(x / G) * G
            du, dd = (lu - x) / x, (x - ld) / x
            cand = []
            if 0.0005 <= du <= 0.0015 and lu not in used:
                cand.append((du, lu, 0))
            if 0.0005 <= dd <= 0.0015 and ld not in used:
                cand.append((dd, ld, 1))
            if not cand:
                continue
            dist, lev, side = min(cand)
            used.add(lev)
            stop = x - (lev - x)
            L4.add_std(R, f"M3_RMAG|{gnm}", di, s, side, nat=(stop, lev))
    # M4 fade at minor grid
    G = minor
    for b in range(1, 75):
        s = 5 * b + 4
        if not ok_s(s):
            continue
        pcl = C[b - 1]
        lev_up = math.ceil(pcl / G) * G
        lev_dn = math.floor(pcl / G) * G
        if H[b] >= lev_up and C[b] < lev_up and pcl < lev_up:
            L4.add_std(R, "M4_RFADE|minor", di, s, 1, nat=(lev_up * 1.001, lev_up * 0.998))
        elif Lw[b] <= lev_dn and C[b] > lev_dn and pcl > lev_dn:
            L4.add_std(R, "M4_RFADE|minor", di, s, 0, nat=(lev_dn * 0.999, lev_dn * 1.002))
    # M9 / F5 opening-range Fibonacci extensions
    h, l = P["h"][di], P["l"][di]
    orh, orl = np.nanmax(h[:15]), np.nanmin(l[:15])
    rg = orh - orl
    if np.isfinite(rg) and rg > 0:
        for b in range(3, 75):
            s = 5 * b + 4
            if not ok_s(s):
                continue
            if C[b] > orh:
                side = 0
                nat = {"NATk127": (orl + rg / 2, orl + 1.272 * rg), "NATk162": (orl + rg / 2, orl + 1.618 * rg)}
            elif C[b] < orl:
                side = 1
                nat = {"NATk127": (orl + rg / 2, orh - 1.272 * rg), "NATk162": (orl + rg / 2, orh - 1.618 * rg)}
            else:
                continue
            L4.add_std(R, "M9_ORFIB|or15", di, s, side, extra_nat=nat)
            break


# ------------------------------------------------------------------------------------------------ previous-day levels
def prevday(R, P, di, B5):
    O, H, Lw, C = B5
    ph, pl, pc = P["phigh"][di], P["plow"][di], P["pclose"][di]
    if not (np.isfinite(ph) and np.isfinite(pl) and np.isfinite(pc)) or ph <= pl:
        return
    rg = ph - pl
    piv = (ph + pl + pc) / 3
    R1, R2, S1, S2 = piv + 0.382 * rg, piv + 0.618 * rg, piv - 0.382 * rg, piv - 0.618 * rg
    grid = pl + rg * np.array([-0.618, -0.272, 0, .236, .382, .5, .618, .786, 1, 1.272, 1.618])
    qg = pl + rg * np.array([-0.5, -0.25, 0, .25, .5, .75, 1, 1.25, 1.5])
    mid = pl + 0.5 * rg
    done = set()
    for b in range(1, 75):
        s = 5 * b + 4
        if not ok_s(s):
            continue
        p, c, hi, lo = C[b - 1], C[b], H[b], Lw[b]
        # F1 Fibonacci pivots
        if "F1B+" not in done and p <= R1 < c:
            L4.add_std(R, "F1_FIBPIV|brk", di, s, 0, nat=(piv, R2)); done.add("F1B+")
        if "F1B-" not in done and p >= S1 > c:
            L4.add_std(R, "F1_FIBPIV|brk", di, s, 1, nat=(piv, S2)); done.add("F1B-")
        if p < R1 <= hi and c < R1:
            L4.add_std(R, "F1_FIBPIV|bnc", di, s, 1, nat=(R2, piv))
        if p > S1 >= lo and c > S1:
            L4.add_std(R, "F1_FIBPIV|bnc", di, s, 0, nat=(S2, piv))
        # F2 previous-day-range Fibonacci
        for k in (4, 6):  # 0.382, 0.618 in grid
            lv = grid[k]
            if p < lv <= hi and c < lv:
                L4.add_std(R, "F2_PDRFIB|bnc", di, s, 1, nat=(grid[k + 1], grid[k - 1]))
            if p > lv >= lo and c > lv:
                L4.add_std(R, "F2_PDRFIB|bnc", di, s, 0, nat=(grid[k - 1], grid[k + 1]))
        if p <= grid[6] < c:
            L4.add_std(R, "F2_PDRFIB|brk", di, s, 0, nat=(grid[5], grid[7]))
        if p >= grid[4] > c:
            L4.add_std(R, "F2_PDRFIB|brk", di, s, 1, nat=(grid[5], grid[3]))
        # G4 Gann 50% / quarters
        if p < mid <= hi and c < mid:
            L4.add_std(R, "G4_MID50|bnc", di, s, 1, nat=(qg[5], qg[3]))
        if p > mid >= lo and c > mid:
            L4.add_std(R, "G4_MID50|bnc", di, s, 0, nat=(qg[3], qg[5]))
        if p <= mid < c:
            L4.add_std(R, "G4_MID50|brk", di, s, 0, nat=(qg[3], qg[5]))
        if p >= mid > c:
            L4.add_std(R, "G4_MID50|brk", di, s, 1, nat=(qg[5], qg[3]))
        # F7 confluence with a round 100
        for lv, nb_dn, nb_up in ((R1, piv, R2), (S1, S2, piv), (grid[4], grid[3], grid[5]), (grid[6], grid[5], grid[7])):
            if abs(lv - round(lv / 100) * 100) / lv > 0.0005:
                continue
            if p < lv <= hi and c < lv:
                L4.add_std(R, "F7_CONF|rnd100", di, s, 1, nat=(nb_up, nb_dn))
            if p > lv >= lo and c > lv:
                L4.add_std(R, "F7_CONF|rnd100", di, s, 0, nat=(nb_dn, nb_up))


# ------------------------------------------------------------------------------------------------ Gann intraday
def gann_intraday(R, P, di, B5):
    O, H, Lw, C = B5
    c, h, l, o = P["c"][di], P["h"][di], P["l"][di], P["o"][di]
    op = o[0] if np.isfinite(o[0]) else c[0]
    # G5 angles from the 09:15-09:44 range
    rg = P["phigh"][di] - P["plow"][di]
    if np.isfinite(rg) and rg > 0:
        bl, bh = int(np.nanargmin(Lw[:6])), int(np.nanargmax(H[:6]))
        orl, orh = Lw[bl], H[bh]
        for mult in (1, 2):
            u = rg / 75 * mult
            for b in range(6, 75):
                s = 5 * b + 4
                if not ok_s(s):
                    continue
                up_t, up_p = orl + u * (b - bl), orl + u * (b - 1 - bl)
                dn_t, dn_p = orh - u * (b - bh), orh - u * (b - 1 - bh)
                side = None
                if C[b] < up_t and C[b - 1] >= up_p:
                    side, line = 1, lambda bb: orl + u * (bb - bl)
                elif C[b] > dn_t and C[b - 1] <= dn_p:
                    side, line = 0, lambda bb: orh - u * (bb - bh)
                if side is None:
                    continue
                x = -1
                for bb in range(b + 1, 75):
                    if (side == 1 and C[bb] > line(bb)) or (side == 0 and C[bb] < line(bb)):
                        x = min(5 * bb + 5, SQ)
                        break
                R._P_nat(f"G5_ANG|{mult}x1|NAT", di, s, side, x)
                L4.add_std(R, f"G5_ANG|{mult}x1", di, s, side)
                break
    # G6 time: 45/90/180 minutes from the open
    for cp in (col(10, 0), col(10, 45), col(12, 15)):
        s = cp - 1
        mv = c[s] / op - 1
        if abs(mv) > 0.002:
            L4.add_std(R, "G6_TOPEN|45-90-180", di, s, 1 if mv > 0 else 0, times=(30, 60))
            break
    hh, ll = np.nanargmax(h[:60]), np.nanargmin(l[:60])
    anchor = int(max(hh, ll))
    ext = h[hh] if anchor == hh else l[ll]
    for k in (90, 144):
        s = anchor + k
        if not ok_s(s):
            continue
        mv = c[s] / ext - 1
        if abs(mv) > 0.002:
            L4.add_std(R, "G6_TEXT|90-144", di, s, 1 if mv > 0 else 0, times=(30, 60))
            break


SEAS = [(2, 4), (3, 21), (5, 6), (6, 21), (8, 8), (9, 23), (11, 7), (12, 21)]


def seasonal(R, P, days_ok):
    days = P["days"]
    okset = set(days_ok)
    for y in range(2020, 2027):
        for m, d in SEAS:
            t = date(y, m, d)
            cand = [i for i, dd in enumerate(days) if dd >= t]
            if not cand:
                continue
            di = cand[0]
            if (days[di] - t).days > 4 or di < 6 or di not in okset:
                continue
            r5 = P["dclose"][di - 1] / P["dclose"][di - 6] - 1
            side = 0 if r5 < 0 else 1
            R.add("G7_SEAS|fade5d|OPT", di, 0, side, sp=0.25, tp=0.50, lo=0, hi=0)
            R.add("G7_SEAS|fade5d|EOD", di, 0, side, lo=0, hi=0)


# ------------------------------------------------------------------------------------------------ bar-based (continuous)
def mk_bars(P, tf):
    M = {"o": P["o"], "h": P["h"], "l": P["l"], "c": P["c"]}
    B = H37.bars(M, tf)
    B["A"] = H37.atr(B)
    return B


def bar_ok(B, t, okd):
    return okd[B["dpos"][t]] and ok_s(int(B["col_end"][t]))


def hilo(R, P, B, tf, okd):
    h, l, c = B["h"], B["l"], B["c"]
    n = len(c)
    sh = np.convolve(h, np.ones(3) / 3, "full")[:n]
    sl = np.convolve(l, np.ones(3) / 3, "full")[:n]
    st = 0
    flips = []
    for t in range(3, n):
        ns = st
        if c[t] > sh[t - 1]:
            ns = 1
        elif c[t] < sl[t - 1]:
            ns = -1
        if ns != st and st != 0:
            flips.append((t, ns))
        st = ns
    for i, (t, ns) in enumerate(flips):
        if not bar_ok(B, t, okd):
            continue
        di, s = int(B["dpos"][t]), int(B["col_end"][t])
        side = 0 if ns == 1 else 1
        x = -1
        if i + 1 < len(flips):
            t2 = flips[i + 1][0]
            if B["dpos"][t2] == di:
                x = min(int(B["col_end"][t2]) + 1, SQ)
        R._P_nat(f"G2_HILO|tf{tf}|NAT", di, s, side, x)
        L4.add_std(R, f"G2_HILO|tf{tf}", di, s, side)


def gann_swing(R, P, B, tf, okd):
    h, l, c = B["h"], B["l"], B["c"]
    n = len(c)
    p = 0
    cu = cd = 0
    d = 0
    ext_hi, ext_lo = h[0], l[0]
    top = bot = None
    trend = 0
    for t in range(1, n):
        inside = h[t] <= h[p] and l[t] >= l[p]
        if not inside:
            hh, ll = h[t] > h[p], l[t] < l[p]
            if hh and not ll:
                cu, cd = cu + 1, 0
            elif ll and not hh:
                cd, cu = cd + 1, 0
            p = t
        ext_hi, ext_lo = max(ext_hi, h[t]), min(ext_lo, l[t])
        if d != 1 and cu >= 2:
            if d == -1:
                bot = ext_lo
            d, ext_hi, ext_lo = 1, h[t], l[t]
        elif d != -1 and cd >= 2:
            if d == 1:
                top = ext_hi
            d, ext_hi, ext_lo = -1, h[t], l[t]
        sig = None
        if top is not None and trend != 1 and c[t] > top:
            trend, sig = 1, 0
        elif bot is not None and trend != -1 and c[t] < bot:
            trend, sig = -1, 1
        if sig is None or not bar_ok(B, t, okd):
            continue
        di, s = int(B["dpos"][t]), int(B["col_end"][t])
        stop = bot if sig == 0 else top
        if stop is None:
            continue
        L4.add_std(R, f"G3_SWING|tf{tf}", di, s, sig, nat=(stop, NAN))


def piv(B, k=2):
    return H37.zigzag(B, B["A"], k)


def fib_swings(R, P, B, tf, okd, Z):
    """F3 golden pocket (tf 3/5/15), F4 Upstox 3-min 61.8 + reversal candle (tf 3 only)."""
    pb, pp, pt, pc = Z
    h, l, c, o = B["h"], B["l"], B["c"], B["o"]
    n = len(c)
    for j in range(1, len(pb)):
        a, e = pp[j - 1], pp[j]           # swing from a to e (end pivot j confirmed at pc[j])
        up = pt[j] == 1
        rg = abs(e - a)
        if rg <= 0:
            continue
        sgn = 1 if up else -1
        lev = lambda r: e - sgn * r * rg  # noqa: E731
        t_end = pc[j + 1] if j + 1 < len(pb) else n
        gp_done = up_done = False
        for t in range(pc[j] + 1, min(t_end + 1, n)):
            far = l[t] if up else h[t]
            if sgn * (far - lev(0.786)) < 0:      # beyond 0.786 -> setup invalid
                break
            if not bar_ok(B, t, okd):
                continue
            di, s = int(B["dpos"][t]), int(B["col_end"][t])
            side = 0 if up else 1
            if not gp_done and sgn * (far - lev(0.618)) <= 0 and sgn * (c[t] - lev(0.618)) > 0:
                gp_done = True
                L4.add_std(R, f"F3_GP|tf{tf}", di, s, side,
                           extra_nat={"NAT": (lev(0.786), e), "NAT127": (lev(0.786), e + sgn * 0.272 * rg)})
            if tf == 3 and not up_done and sgn * (far - lev(0.618)) <= 0 and sgn * (c[t] - o[t - 1]) > 0 \
                    and sgn * (c[t] - o[t]) > 0 and sgn * (c[t] - lev(0.618)) > 0:
                up_done = True
                risk = abs(c[t] - far)
                L4.add_std(R, "F4_UPX61|tf3", di, s, side, nat=(far, c[t] + sgn * risk))
            if gp_done and (tf != 3 or up_done):
                break


def fib_time(R, P, B, okd, Z):
    pb, pp, pt, pc = Z
    c = B["c"]
    n = len(c)
    for j in range(len(pb)):
        t0 = pb[j]
        nxt = pc[j + 1] if j + 1 < len(pb) else n
        for k in (5, 8, 13, 21, 34, 55):
            t = t0 + k
            if t <= pc[j] or t >= n or t > nxt or B["dpos"][t] != B["dpos"][t0] or not bar_ok(B, t, okd):
                continue
            di, s = int(B["dpos"][t]), int(B["col_end"][t])
            L4.add_std(R, "F6_FTZ|tf5", di, s, 1 if c[t] > pp[j] else 0, times=(30, 60))


# ------------------------------------------------------------------------------------------------ harmonics
CLASSIC = {  # B band (of XA), D (of XA from A), stop (of XA from A)
    "GARTLEY": ((0.588, 0.648), 0.786, 1.0),
    "BAT": ((0.382, 0.50), 0.886, 1.13),
    "ALTBAT": ((0.30, 0.382), 1.13, 1.27),
    "BUTTERFLY": ((0.756, 0.816), 1.272, 1.414),
    "CRAB": ((0.382, 0.618), 1.618, 2.0),
    "DEEPCRAB": ((0.856, 0.916), 1.618, 2.0),
}


def inb(x, lo, hi, tol=0.05):
    return lo * (1 - tol) <= x <= hi * (1 + tol)


def harmonics(R, P, B, tf, okd, Z):
    pb, pp, pt, pc = Z
    h, l, c = B["h"], B["l"], B["c"]
    n = len(c)
    seen = set()
    for j in range(2, len(pb)):
        bull = pt[j] == 1          # last pivot a high -> next leg down -> bullish completion
        g = 1.0 if bull else -1.0
        q = pp[: j + 1] * g        # transform: bull formulas on g*price
        setups = []
        # ABCD (A=j-2 high, B=j-1 low, C=j high)
        Ah, Bl, Ch = q[j - 2], q[j - 1], q[j]
        if Ah - Bl > 0 and inb((Ch - Bl) / (Ah - Bl), 0.382, 0.886):
            D = Ch - (Ah - Bl)
            setups.append(("ABCD", D, D - 0.272 * (Ch - D), Ah))
        if j >= 3:
            X, A, Bp, C = q[j - 3], q[j - 2], q[j - 1], q[j]
            XA = A - X
            if XA > 0 and A - Bp > 0:
                br = (A - Bp) / XA
                bc = (C - Bp) / (A - Bp)
                for nm, (bb, dr, sr) in CLASSIC.items():
                    if inb(br, *bb) and inb(bc, 0.382, 0.886) and C < A:
                        setups.append((nm, A - dr * XA, A - sr * XA, A))
                # Cypher: C beyond A at 1.272-1.414 XA
                if inb(br, 0.382, 0.618) and inb((C - X) / XA, 1.272, 1.414):
                    D = C - 0.786 * (C - X)
                    setups.append(("CYPHER", D, X, C))
            # Shark: 0=j-3 low, X=j-2 high, A=j-1 low, B=j high beyond X
            O_, Xs, As, Bs = q[j - 3], q[j - 2], q[j - 1], q[j]
            if Xs - As > 0 and Xs - O_ > 0 and inb((Bs - As) / (Xs - As), 1.13, 1.618) and Bs > Xs:
                Cc = Xs - 0.886 * (Xs - O_)
                setups.append(("SHARK", Cc, Xs - 1.13 * (Xs - O_), Bs))
        if j >= 4:
            # 5-0: 0=j-4 high, X=j-3 low, A=j-2 high, B=j-1 low below X, C=j high
            O_, X, A, Bp, C = q[j - 4], q[j - 3], q[j - 2], q[j - 1], q[j]
            if A - X > 0 and A - Bp > 0 and Bp < X and O_ > A * 0 + X and inb((A - Bp) / (A - X), 1.13, 1.618) \
                    and inb((C - Bp) / (A - Bp), 1.618, 2.24):
                D = C - 0.5 * (C - Bp)
                setups.append(("FIVE0", D, C - 0.786 * (C - Bp), C))
        if not setups:
            continue
        t_end = pc[j + 1] if j + 1 < len(pb) else n
        # price between the C pivot bar and its confirmation must not have reached D already
        seg_lo = (l if bull else -h)  # in transformed space the "low" is g*low for bull, -high for bear
        for nm, D, stop, top in setups:
            if not (stop < D < q[j]):
                continue
            pre = (l[pb[j]:pc[j] + 1] * g) if bull else (-h[pb[j]:pc[j] + 1])
            if len(pre) and pre.min() <= D:
                continue
            for t in range(pc[j] + 1, min(t_end + 1, n)):
                lo_t = l[t] if bull else -h[t]
                cl_t = c[t] * g
                if lo_t <= stop:
                    break
                if lo_t <= D:
                    if cl_t > D and bar_ok(B, t, okd):
                        di, s = int(B["dpos"][t]), int(B["col_end"][t])
                        side = 0 if bull else 1
                        t1 = (D + 0.382 * (top - D)) * g
                        t2 = (D + 0.618 * (top - D)) * g
                        st = stop * g
                        nat = {"NAT1": (st, t1), "NAT2": (st, t2)}
                        L4.add_std(R, f"H_{nm}|tf{tf}", di, s, side, extra_nat=nat, times=())
                        if (di, s, side, tf) not in seen:
                            seen.add((di, s, side, tf))
                            L4.add_std(R, f"H_ALL|tf{tf}", di, s, side, extra_nat=nat, times=())
                    break
        _ = seg_lo


# ------------------------------------------------------------------------------------------------ all
def build_all(P, days_ok):
    R = L4.SReq(P)
    okd = np.zeros(P["nd"], bool)
    okd[days_ok] = True
    for di in days_ok:
        B5 = bars5_day(P, di)
        magic(R, P, di, B5)
        prevday(R, P, di, B5)
        gann_intraday(R, P, di, B5)
    seasonal(R, P, days_ok)
    for tf in (3, 5, 15, 60):
        B = mk_bars(P, tf)
        Z = piv(B, 2)
        if tf in (5, 15, 60):
            hilo(R, P, B, tf, okd)
        if tf in (15, 60):
            gann_swing(R, P, B, tf, okd)
        if tf in (3, 5, 15):
            fib_swings(R, P, B, tf, okd, Z)
        if tf == 5:
            fib_time(R, P, B, okd, Z)
        harmonics(R, P, B, tf, okd, Z)
    req = R.frame()
    return req
