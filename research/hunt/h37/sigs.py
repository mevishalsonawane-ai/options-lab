"""h37 signal library (see PREREG.md): Fibonacci, Gann (square of 9, 1x1 angles, time cycles), harmonics, Elliott,
Renko, Point & Figure, Market Profile (TPO; volume profile from constituents) and Wyckoff spring / upthrust.

Every signal uses only bars closed at or before its bar. Output of `all_events(ix, vp=None)`: list of configs
dict(sig, fam, tf, par, ord, mi, side[, H, L]) where ord = day ordinal of the ENTRY day, mi = signal minute column
(0 = 09:15; entry at the next minute's open), side +1 = CE, -1 = PE.
"""
from __future__ import annotations

import numpy as np

KS = (2, 4)
FIB_R = (0.382, 0.5, 0.618, 0.786)
NINES = tuple(range(9, 91, 9))
SQUARES = (4, 9, 16, 25, 36, 49, 64, 81)
GTC_D = (30, 45, 60, 90, 120, 144, 180, 270, 360)
BOX_PCT = (0.001, 0.002, 0.003)
BOX_ATR = (0.5, 1.0)
HARM = {  # AB/XA range, D as fraction of XA from A, CD/BC range
    "GARTLEY": ((0.588, 0.648), 0.786, (1.13, 1.618)),
    "BAT": ((0.382, 0.50), 0.886, (1.618, 2.618)),
    "BUTTERFLY": ((0.756, 0.816), 1.272, (1.618, 2.24)),
    "CRAB": ((0.382, 0.618), 1.618, (2.24, 3.618)),
}
BC_R = (0.382, 0.886)
TOL = 0.05
NIFTY50 = ("ADANIENT ADANIPORTS APOLLOHOSP ASIANPAINT AXISBANK BAJAJ-AUTO BAJFINANCE BAJAJFINSV BEL BHARTIARTL CIPLA "
           "COALINDIA DRREDDY EICHERMOT ETERNAL GRASIM HCLTECH HDFCBANK HDFCLIFE HEROMOTOCO HINDALCO HINDUNILVR "
           "ICICIBANK INDUSINDBK INFY ITC JIOFIN JSWSTEEL KOTAKBANK LT M_M MARUTI NESTLEIND NTPC ONGC POWERGRID "
           "RELIANCE SBILIFE SBIN SHRIRAMFIN SUNPHARMA TATACONSUM TMPV TATASTEEL TCS TECHM TITAN TRENT ULTRACEMCO "
           "WIPRO").split()
BANK12 = "HDFCBANK ICICIBANK SBIN KOTAKBANK AXISBANK INDUSINDBK BANKBARODA AUBANK FEDERALBNK IDFCFIRSTB PNB CANBK".split()


# ------------------------------------------------------------------------------------------------ bars
def bars(M, tf):
    """tf-minute bars anchored 09:15 (h25's builder). M: dict o/h/l/c (n_days, 375)."""
    n, W = M["c"].shape
    nb = -(-W // tf)
    pad = nb * tf - W
    A = {k: np.concatenate([M[k], np.full((n, pad), np.nan)], axis=1).reshape(n, nb, tf) for k in "ohlc"}
    okc = ~np.isnan(A["c"])
    keep = okc.any(axis=2)
    first = np.argmax(~np.isnan(A["o"]), axis=2)
    last = tf - 1 - np.argmax(okc[:, :, ::-1], axis=2)
    ii, jj = np.indices((n, nb))
    o = A["o"][ii, jj, first]
    c = A["c"][ii, jj, last]
    with np.errstate(all="ignore"):
        h = np.nanmax(A["h"], axis=2)
        lo = np.nanmin(A["l"], axis=2)
    col_end = np.minimum((jj + 1) * tf, W) - 1
    sel = keep.ravel()
    out = dict(o=o.ravel()[sel], h=h.ravel()[sel], l=lo.ravel()[sel], c=c.ravel()[sel],
               dpos=ii.ravel()[sel], col_end=col_end.ravel()[sel])
    for k in "ohl":
        x = out[k]
        bad = np.isnan(x)
        x[bad] = out["c"][bad]
    out["h"] = np.maximum.reduce([out["h"], out["o"], out["c"]])
    out["l"] = np.minimum.reduce([out["l"], out["o"], out["c"]])
    return out


def daily(M):
    n = M["c"].shape[0]
    ok = ~np.isnan(M["c"])
    keep = ok.any(axis=1)
    first = np.argmax(~np.isnan(M["o"]), axis=1)
    last = M["c"].shape[1] - 1 - np.argmax(ok[:, ::-1], axis=1)
    r = np.arange(n)
    with np.errstate(all="ignore"):
        out = dict(o=M["o"][r, first], h=np.nanmax(M["h"], axis=1), l=np.nanmin(M["l"], axis=1), c=M["c"][r, last],
                   dpos=r, col_end=np.full(n, 374))
    return {k: v[keep] for k, v in out.items()}


def atr(B, n=14):
    h, lo, c = B["h"], B["l"], B["c"]
    pc = np.concatenate([[c[0]], c[:-1]])
    tr = np.maximum.reduce([h - lo, np.abs(h - pc), np.abs(lo - pc)])
    out = np.full(len(c), np.nan)
    if len(c) <= n:
        return out
    out[n - 1] = tr[:n].mean()
    for t in range(n, len(c)):
        out[t] = (out[t - 1] * (n - 1) + tr[t]) / n
    return out


def zigzag(B, A, k):
    """Confirmed pivots: arrays bar, price, type (+1 high / -1 low), conf (confirming bar)."""
    h, lo = B["h"], B["l"]
    n = len(h)
    pb, pp, pt, pc = [], [], [], []
    d = 0
    hi, hii, lw, lwi = h[0], 0, lo[0], 0
    for t in range(1, n):
        th = k * A[t]
        if not th > 0:
            if h[t] > hi:
                hi, hii = h[t], t
            if lo[t] < lw:
                lw, lwi = lo[t], t
            continue
        if d >= 0:
            if h[t] > hi:
                hi, hii = h[t], t
        if d <= 0:
            if lo[t] < lw:
                lw, lwi = lo[t], t
        if d == 0:
            if h[t] - lw >= th and lwi < t:
                pb.append(lwi); pp.append(lw); pt.append(-1); pc.append(t)
                d, hi, hii = 1, h[t], t
            elif hi - lo[t] >= th and hii < t:
                pb.append(hii); pp.append(hi); pt.append(1); pc.append(t)
                d, lw, lwi = -1, lo[t], t
        elif d == 1:
            if hi - lo[t] >= th:
                pb.append(hii); pp.append(hi); pt.append(1); pc.append(t)
                d, lw, lwi = -1, lo[t], t
        else:
            if h[t] - lw >= th:
                pb.append(lwi); pp.append(lw); pt.append(-1); pc.append(t)
                d, hi, hii = 1, h[t], t
    return (np.array(pb, np.int64), np.array(pp, float), np.array(pt, np.int64), np.array(pc, np.int64))


def _first(cond, a, b):
    """first index in [a, b] where cond (array over that slice) is True, else -1."""
    w = np.nonzero(cond)[0]
    return a + int(w[0]) if len(w) else -1


def _win(P, j, n):
    return P[3][j], (P[3][j + 1] - 1 if j + 1 < len(P[3]) else n - 1)


# ------------------------------------------------------------------------------------------------ swing-based signals
def swing_signals(B, A, k, daily_mode=False):
    """FIB_B/FIB_K, G1X1 (two scales), GTC_9/GTC_SQ, harmonics, Elliott. Returns {name: (events list of (t, side,
    extra))}."""
    h, lo, c = B["h"], B["l"], B["c"]
    n = len(c)
    P = zigzag(B, A, k)
    pb, pp, pt, pc = P
    out = {f"FIB_B{int(r * 1000)}": [] for r in FIB_R}
    out.update({f"FIB_K{int(r * 1000)}": [] for r in FIB_R})
    out.update({"G1X1_s25": [], "G1X1_s50": [], "GTC_9": [], "GTC_SQ": []})
    out.update({f"H_{p}": [] for p in list(HARM) + ["ABCD"]})
    out.update({"EW3": [], "EW5": [], "EWC": []})
    cprev = np.concatenate([[c[0]], c[:-1]])
    for j in range(len(pb)):
        a, b = _win(P, j, n)
        if b < a:
            continue
        s = pt[j]                      # +1: last pivot is a high (up-swing just ended)
        sl = slice(a, b + 1)
        tt = np.arange(a, b + 1)
        # --- Fibonacci on the completed swing (j-1 -> j)
        if j >= 1:
            Hs, Ls = pp[j], pp[j - 1]
            sw = Hs - Ls               # >0 for an up-swing, <0 for a down-swing
            # origin broken: close beyond Ls against the swing
            br = _first((c[sl] - Ls) * np.sign(sw) < 0, a, b)
            bb = b if br < 0 else br
            sl2 = slice(a, bb + 1)
            for r in FIB_R:
                lev = Hs - r * sw
                if sw > 0:
                    tb = _first((lo[sl2] <= lev) & (c[sl2] > lev) & (cprev[sl2] > lev), a, bb)
                    tk = _first((c[sl2] < lev) & (cprev[sl2] >= lev), a, bb)
                else:
                    tb = _first((h[sl2] >= lev) & (c[sl2] < lev) & (cprev[sl2] < lev), a, bb)
                    tk = _first((c[sl2] > lev) & (cprev[sl2] <= lev), a, bb)
                if tb >= 0:
                    out[f"FIB_B{int(r * 1000)}"].append((tb, int(np.sign(sw)), (Hs, Ls)))
                if tk >= 0:
                    out[f"FIB_K{int(r * 1000)}"].append((tk, -int(np.sign(sw)), None))
        # --- Gann 1x1 from pivot j
        if not daily_mode:
            for sc, nm in ((0.25, "G1X1_s25"), (0.5, "G1X1_s50")):
                u = sc * A[pb[j]]
                if not u > 0:
                    continue
                line = pp[j] - s * u * (tt - pb[j])      # rising from a low (s=-1), falling from a high (s=+1)
                linep = pp[j] - s * u * (tt - 1 - pb[j])
                if s < 0:
                    t1 = _first((c[sl] < line) & (cprev[sl] >= linep), a, b)
                    if t1 >= 0:
                        out[nm].append((t1, -1, None))
                else:
                    t1 = _first((c[sl] > line) & (cprev[sl] <= linep), a, b)
                    if t1 >= 0:
                        out[nm].append((t1, 1, None))
            # --- time cycles
            cnt = tt - pb[j]
            for st, nm in ((NINES, "GTC_9"), (SQUARES, "GTC_SQ")):
                for t in tt[np.isin(cnt, st)]:
                    out[nm].append((int(t), -1 if c[t] > pp[j] else 1, None))
        # --- harmonics (C = pivot j)
        if j >= 3:
            X, Ap, Bp, Cp = pp[j - 3], pp[j - 2], pp[j - 1], pp[j]
            bull = s > 0               # C is a high -> D is a low -> bullish
            XA, AB, BC = abs(Ap - X), abs(Ap - Bp), abs(Cp - Bp)
            if XA > 0 and AB > 0 and BC > 0:
                for nm, (abr, dr, cdr) in HARM.items():
                    if not (abr[0] <= AB / XA <= abr[1] and BC_R[0] <= BC / AB <= BC_R[1]):
                        continue
                    D = Ap - dr * XA if bull else Ap + dr * XA
                    CD = abs(Cp - D)
                    if not (cdr[0] * (1 - TOL) <= CD / BC <= cdr[1] * (1 + TOL)):
                        continue
                    _harm_hit(out[f"H_{nm}"], B, pb[j], a, b, D, bull)
        if j >= 2:
            Ap, Bp, Cp = pp[j - 2], pp[j - 1], pp[j]
            bull = s > 0
            AB, BC = abs(Ap - Bp), abs(Cp - Bp)
            if AB > 0 and BC > 0 and BC_R[0] <= BC / AB <= BC_R[1]:
                D = Cp - AB if bull else Cp + AB
                if 1.13 * (1 - TOL) <= AB / BC <= 2.618 * (1 + TOL):
                    _harm_hit(out["H_ABCD"], B, pb[j], a, b, D, bull)
        # --- Elliott
        if j >= 2:
            p0, p1, p2 = pp[j - 2], pp[j - 1], pp[j]
            if s < 0:   # p2 low: up impulse candidate
                if p2 > p0 and 0.382 <= (p1 - p2) / (p1 - p0) <= 0.786:
                    t1 = _first((c[sl] > p1) & (cprev[sl] <= p1), a, b)
                    if t1 >= 0:
                        out["EW3"].append((t1, 1, None))
            else:
                if p2 < p0 and 0.382 <= (p2 - p1) / (p0 - p1) <= 0.786:
                    t1 = _first((c[sl] < p1) & (cprev[sl] >= p1), a, b)
                    if t1 >= 0:
                        out["EW3"].append((t1, -1, None))
        if j >= 4:
            q = pp[j - 4:j + 1] * (1 if s < 0 else -1)      # orient so the impulse is "up"
            if _impulse(q):
                T = q[4] + (q[1] - q[0])
                if T > q[3]:
                    Tr = T if s < 0 else -T
                    hh = h if s < 0 else -lo
                    pre = hh[pb[j] + 1:a]
                    if not (len(pre) and pre.max() >= (Tr if s < 0 else -Tr)):
                        t1 = _first(hh[sl] >= (Tr if s < 0 else -Tr), a, b)
                        if t1 >= 0:
                            out["EW5"].append((t1, -1 if s < 0 else 1, None))
        if j >= 7:
            q = pp[j - 7:j + 1] * (1 if s > 0 else -1)      # p7 high for an up impulse (s=+1)
            if _impulse(q[:5]) and q[5] > q[3] and q[5] > q[6] and 0.382 <= (q[7] - q[6]) / (q[5] - q[6]) <= 0.886:
                p6 = pp[j - 1]
                if s > 0:
                    t1 = _first((c[sl] < p6) & (cprev[sl] >= p6), a, b)
                else:
                    t1 = _first((c[sl] > p6) & (cprev[sl] <= p6), a, b)
                if t1 >= 0:
                    out["EWC"].append((t1, -1 if s > 0 else 1, None))
    return out, P


def _impulse(q):
    """q = p0..p4 oriented up (p0 low)."""
    w1, w3 = q[1] - q[0], q[3] - q[2]
    return q[2] > q[0] and q[3] > q[1] and q[4] > q[1] and w1 > 0 and w3 > w1 and q[1] > q[2] and q[3] > q[4]


def _harm_hit(lst, B, cbar, a, b, D, bull):
    h, lo = B["h"], B["l"]
    pre = (lo if bull else h)[cbar + 1:a]
    if len(pre) and ((pre.min() <= D) if bull else (pre.max() >= D)):
        return
    sl = slice(a, b + 1)
    t1 = _first(lo[sl] <= D if bull else h[sl] >= D, a, b)
    if t1 >= 0:
        lst.append((t1, 1 if bull else -1, None))


# ------------------------------------------------------------------------------------------------ Gann square of 9
def sq9(B, ref_open, ref_prev):
    """ref_*: per-bar reference price (that day's open / previous close). Returns {SQ9_BRK_open: arr, ...}."""
    c, h, lo, dp = B["c"], B["h"], B["l"], B["dpos"]
    newday = np.concatenate([[True], dp[1:] != dp[:-1]])
    cp = np.concatenate([[np.nan], c[:-1]])
    out = {}
    for rn, ref in (("open", ref_open), ("prev", ref_prev)):
        cpr = np.where(newday, ref_open, cp)       # first bar of a day compares with the day's open
        up_b, dn_b, up_n, dn_n = (np.zeros(len(c), bool) for _ in range(4))
        rt = np.sqrt(ref)
        for jj in (-4, -3, -2, -1, 1, 2, 3, 4):
            L = (rt + jj / 4.0) ** 2
            with np.errstate(invalid="ignore"):
                conds = (("ub", (cpr < L) & (c >= L)), ("db", (cpr > L) & (c <= L)),
                         ("un", (lo <= L) & (c > L) & (cpr > L)), ("dn", (h >= L) & (c < L) & (cpr < L)))
            for nm, cd in conds:
                idx = np.nonzero(cd)[0]
                if not len(idx):
                    continue
                _, f = np.unique(dp[idx], return_index=True)
                tgt = {"ub": up_b, "db": dn_b, "un": up_n, "dn": dn_n}[nm]
                tgt[idx[f]] = True
        out[f"SQ9_BRK_{rn}"] = np.where(up_b & ~dn_b, 1, np.where(dn_b & ~up_b, -1, 0)).astype(np.int8)
        out[f"SQ9_BNC_{rn}"] = np.where(up_n & ~dn_n, 1, np.where(dn_n & ~up_n, -1, 0)).astype(np.int8)
    return out


# ------------------------------------------------------------------------------------------------ Wyckoff
def wyckoff(B, A, N=20, W=4.0):
    from numpy.lib.stride_tricks import sliding_window_view as swv
    h, lo, c = B["h"], B["l"], B["c"]
    n = len(c)
    RH = np.full(n, np.nan)
    RL = np.full(n, np.nan)
    if n > N:
        RH[N:] = swv(h, N).max(axis=1)[:-1]
        RL[N:] = swv(lo, N).min(axis=1)[:-1]
    Ap = np.concatenate([[np.nan], A[:-1]])
    with np.errstate(invalid="ignore"):
        ok = (RH - RL) <= W * Ap
        sp0 = ok & (lo < RL) & (c > RL)
        brk_dn = ok & (lo < RL) & (c <= RL)
        sp1 = np.zeros(n, bool)
        sp1[1:] = brk_dn[:-1] & (c[1:] > RL[:-1])
        ut0 = ok & (h > RH) & (c < RH)
        brk_up = ok & (h > RH) & (c >= RH)
        ut1 = np.zeros(n, bool)
        ut1[1:] = brk_up[:-1] & (c[1:] < RH[:-1])
    sp, ut = sp0 | sp1, ut0 | ut1
    return {"WY_SPRING": np.where(sp & ~ut, 1, 0).astype(np.int8), "WY_UPTHRUST": np.where(ut & ~sp, -1, 0).astype(np.int8)}


# ------------------------------------------------------------------------------------------------ Renko / P&F (1-min closes)
def renko_pf(Mc, Mo, box):
    """Mc, Mo: (n_days, 375) minute closes / opens; box: (n_days,) box size (NaN = skip day).
    Returns dict of (n_days, 375) int8 arrays RK_REV, RK_BRK, PF_DT, PF_TT."""
    nd, W = Mc.shape
    out = {k: np.zeros((nd, W), np.int8) for k in ("RK_REV", "RK_BRK", "PF_DT", "PF_TT")}
    for d in range(nd):
        b = box[d]
        if not b > 0:
            continue
        cc = Mc[d]
        ok = np.nonzero(~np.isnan(cc))[0]
        if len(ok) < 30:
            continue
        o0 = Mo[d, ok[0]] if not np.isnan(Mo[d, ok[0]]) else cc[ok[0]]
        # Renko
        top = bot = o0
        last = 0
        run = 0
        closes = []        # brick close prices
        # P&F (box levels relative to o0)
        col = 0            # +1 X, -1 O
        H = L = 0
        xtops, obots = [], []
        for m in ok:
            p = cc[m]
            # ---- Renko
            nb = []
            if last >= 0 and p >= top + b:
                k = int((p - top) // b)
                for _ in range(k):
                    top += b
                    nb.append(1)
                bot = top - b
            elif last <= 0 and p <= bot - b:
                k = int((bot - p) // b)
                for _ in range(k):
                    bot -= b
                    nb.append(-1)
                top = bot + b
            elif last > 0 and p <= bot - b:
                k = int((bot - p) // b)
                top = bot
                for _ in range(k):
                    bot -= b
                    nb.append(-1)
                top = bot + b
            elif last < 0 and p >= top + b:
                k = int((p - top) // b)
                bot = top
                for _ in range(k):
                    top += b
                    nb.append(1)
                bot = top - b
            if nb:
                dirn = nb[0]
                if last != 0 and dirn != last and run >= 3:
                    out["RK_REV"][d, m] = dirn
                if dirn != last:
                    run = 0
                prev10 = closes[-10:]
                run += len(nb)
                last = nb[-1]
                cl = top if last > 0 else bot
                if len(prev10) == 10:
                    if last > 0 and cl > max(prev10):
                        out["RK_BRK"][d, m] = 1
                    elif last < 0 and cl < min(prev10):
                        out["RK_BRK"][d, m] = -1
                closes.extend([top if x > 0 else bot for x in nb])
            # ---- P&F, 3-box reversal
            q = (p - o0) / b
            if col == 0:
                if q >= 1:
                    col, H = 1, int(np.floor(q))
                elif q <= -1:
                    col, L = -1, int(np.ceil(q))
            elif col == 1:
                if np.floor(q) > H:
                    Hp = H
                    H = int(np.floor(q))
                    if xtops and H > xtops[-1] and Hp <= xtops[-1]:
                        out["PF_DT"][d, m] = 1
                    if len(xtops) >= 2 and xtops[-1] == xtops[-2] and H > xtops[-1] and Hp <= xtops[-1]:
                        out["PF_TT"][d, m] = 1
                elif q <= H - 3:
                    xtops.append(H)
                    col, L = -1, int(np.ceil(q))
                    if obots and L < obots[-1]:
                        out["PF_DT"][d, m] = -1
                    if len(obots) >= 2 and obots[-1] == obots[-2] and L < obots[-1]:
                        out["PF_TT"][d, m] = -1
            else:
                if np.ceil(q) < L:
                    Lp = L
                    L = int(np.ceil(q))
                    if obots and L < obots[-1] and Lp >= obots[-1]:
                        out["PF_DT"][d, m] = -1
                    if len(obots) >= 2 and obots[-1] == obots[-2] and L < obots[-1] and Lp >= obots[-1]:
                        out["PF_TT"][d, m] = -1
                elif q >= L + 3:
                    obots.append(L)
                    col, H = 1, int(np.floor(q))
                    if xtops and H > xtops[-1]:
                        out["PF_DT"][d, m] = 1
                    if len(xtops) >= 2 and xtops[-1] == xtops[-2] and H > xtops[-1]:
                        out["PF_TT"][d, m] = 1
    return out


# ------------------------------------------------------------------------------------------------ Market Profile
def profile_levels(Mh, Ml, Mc, weights=None, tpo=30, binpct=0.0002):
    """Per day: POC, VAH, VAL from that day's minutes (TPO: 30-minute periods; or volume weights per minute at the
    typical price). Returns (n_days, 3) array; NaN where not computable."""
    nd, W = Mc.shape
    out = np.full((nd, 3), np.nan)
    for d in range(nd):
        ok = ~np.isnan(Mc[d])
        if ok.sum() < 200:
            continue
        cl = Mc[d][ok][-1]
        bs = binpct * cl
        if weights is None:
            cnt = {}
            for p0 in range(0, W, tpo):
                hh = np.nanmax(Mh[d, p0:p0 + tpo]) if np.any(~np.isnan(Mh[d, p0:p0 + tpo])) else np.nan
                ll = np.nanmin(Ml[d, p0:p0 + tpo]) if np.any(~np.isnan(Ml[d, p0:p0 + tpo])) else np.nan
                if np.isnan(hh):
                    continue
                for bi in range(int(np.floor(ll / bs)), int(np.floor(hh / bs)) + 1):
                    cnt[bi] = cnt.get(bi, 0) + 1
        else:
            w = weights[d]
            if not np.nansum(w) > 0:
                continue
            tp = (Mh[d] + Ml[d] + Mc[d]) / 3
            g = ~np.isnan(tp) & ~np.isnan(w)
            bins = np.floor(tp[g] / bs).astype(np.int64)
            cnt = {}
            for bi, ww in zip(bins, w[g]):
                cnt[bi] = cnt.get(bi, 0) + ww
        if not cnt:
            continue
        lo_b, hi_b = min(cnt), max(cnt)
        arr = np.array([cnt.get(i, 0) for i in range(lo_b, hi_b + 1)], float)
        mid = (len(arr) - 1) / 2
        mx = arr.max()
        cand = np.nonzero(arr == mx)[0]
        poc = int(cand[np.argmin(np.abs(cand - mid))])
        tot, acc = arr.sum(), arr[poc]
        a, b = poc, poc
        while acc < 0.7 * tot and (a > 0 or b < len(arr) - 1):
            up = arr[b + 1:b + 3].sum() if b < len(arr) - 1 else -1
            dn = arr[max(a - 2, 0):a].sum() if a > 0 else -1
            if up >= dn:
                nb = min(b + 2, len(arr) - 1)
                acc += arr[b + 1:nb + 1].sum()
                b = nb
            else:
                na = max(a - 2, 0)
                acc += arr[na:a].sum()
                a = na
        out[d] = ((lo_b + poc + 0.5) * bs, (lo_b + b + 1) * bs, (lo_b + a) * bs)
    return out


def mp_signals(B, Mo, Mh, Ml, lev, ib_end=59, with_ib=True):
    """lev: (n_days, 3) POC/VAH/VAL of the PREVIOUS day aligned to each day (row d = levels for trading day d)."""
    c, h, lo, dp, ce = B["c"], B["h"], B["l"], B["dpos"], B["col_end"]
    n = len(c)
    names = ["MP_80", "MP_OOV", "MP_VAB", "MP_VAF", "MP_POC"] + (["MP_IB"] if with_ib else [])
    out = {k: np.zeros(n, np.int8) for k in names}
    starts = np.nonzero(np.concatenate([[True], dp[1:] != dp[:-1]]))[0]
    ends = np.concatenate([starts[1:], [n]])
    for s0, e0 in zip(starts, ends):
        d = dp[s0]
        poc, vah, val = lev[d]
        okm = ~np.isnan(Mo[d])
        if not okm.any():
            continue
        op = Mo[d][okm][0]
        idx = np.arange(s0, e0)
        if with_ib:
            ihh = np.nanmax(Mh[d, :ib_end + 1]) if np.any(~np.isnan(Mh[d, :ib_end + 1])) else np.nan
            ill = np.nanmin(Ml[d, :ib_end + 1]) if np.any(~np.isnan(Ml[d, :ib_end + 1])) else np.nan
            post = idx[ce[idx] > ib_end]
            if len(post) and not np.isnan(ihh):
                u = post[c[post] > ihh]
                dn = post[c[post] < ill]
                if len(u):
                    out["MP_IB"][u[0]] = 1
                if len(dn):
                    out["MP_IB"][dn[0]] = -1 if out["MP_IB"][dn[0]] == 0 else 0
        if np.isnan(poc):
            continue
        cc = c[idx]
        cprev = np.concatenate([[op], cc[:-1]])
        inside = (cc >= val) & (cc <= vah)
        if op > vah or op < val:
            two = inside[1:] & inside[:-1]
            w = np.nonzero(two)[0]
            if len(w):
                out["MP_80"][idx[w[0] + 1]] = -1 if op > vah else 1
            if op > vah and cc[0] > vah:
                out["MP_OOV"][idx[0]] = 1
            if op < val and cc[0] < val:
                out["MP_OOV"][idx[0]] = -1
        else:
            u = np.nonzero(cc > vah)[0]
            dn = np.nonzero(cc < val)[0]
            if len(u):
                out["MP_VAB"][idx[u[0]]] = 1
            if len(dn):
                out["MP_VAB"][idx[dn[0]]] = -1 if out["MP_VAB"][idx[dn[0]]] == 0 else 0
            hh, ll = h[idx], lo[idx]
            u = np.nonzero((hh >= vah) & (cc < vah) & (cc > val))[0]
            dn = np.nonzero((ll <= val) & (cc > val) & (cc < vah))[0]
            if len(u):
                out["MP_VAF"][idx[u[0]]] = -1
            if len(dn):
                out["MP_VAF"][idx[dn[0]]] = 1 if out["MP_VAF"][idx[dn[0]]] == 0 else 0
        x = np.nonzero(((cprev < poc) & (cc >= poc)) | ((cprev > poc) & (cc <= poc)))[0]
        if len(x):
            out["MP_POC"][idx[x[0]]] = 1 if cc[x[0]] >= poc else -1
    return out


# ------------------------------------------------------------------------------------------------ everything for one index
def all_events(ix, vp_weights=None, only=None):
    """vp_weights: optional (n_days, 375) constituent turnover per index minute (NaN where none)."""
    M = ix.mat()
    allord = np.array([d.toordinal() for d in ix.days])
    nd = len(allord)
    cfg = []

    def emit(sig, fam, tf, par, Bb, arr_or_list, daily_mode=False):
        if isinstance(arr_or_list, list):
            if not arr_or_list:
                t = np.zeros(0, np.int64); sd = np.zeros(0, np.int64); ex = []
            else:
                t = np.array([e[0] for e in arr_or_list], np.int64)
                sd = np.array([e[1] for e in arr_or_list], np.int64)
                ex = [e[2] for e in arr_or_list]
        else:
            t = np.nonzero(arr_or_list)[0]
            sd = arr_or_list[t].astype(np.int64)
            ex = None
        dpos = Bb["dpos"][t]
        if daily_mode:
            nxt = dpos + 1
            keep = nxt < nd
            ordv = allord[np.minimum(nxt, nd - 1)]
            mi = np.zeros(len(t), np.int64)
        else:
            keep = np.ones(len(t), bool)
            ordv = allord[dpos]
            mi = Bb["col_end"][t].astype(np.int64)
        rec = dict(sig=sig, fam=fam, tf=tf, par=par, ord=ordv[keep], mi=mi[keep], side=sd[keep])
        if ex is not None and ex and ex[0] is not None:
            HL = np.array(ex, float)
            rec["H"], rec["L"] = HL[keep, 0], HL[keep, 1]
        cfg.append(rec)

    fams = {"FIB": "fibonacci", "G1X1": "gann", "GTC": "gann", "SQ9": "gann", "H": "harmonic", "EW3": "elliott",
            "EW5": "elliott", "EWC": "elliott", "RK": "renko", "PF": "pnf", "MP": "profile", "VP": "profile",
            "WY": "wyckoff"}

    def fam(name):
        return fams[name.split("_")[0]]

    for tf in (5, 15, 60, "D"):
        Bb = daily(M) if tf == "D" else bars(M, tf)
        A = atr(Bb)
        dm = tf == "D"
        for k in KS:
            ev, P = swing_signals(Bb, A, k, daily_mode=dm)
            for nm, lst in ev.items():
                if dm and (nm.startswith("G1X1") or nm.startswith("GTC")):
                    continue
                emit(nm, fam(nm), tf, f"k{k}", Bb, lst, dm)
            if dm:
                # daily Gann time cycles: calendar days since the last confirmed daily pivot
                pb, pp, pt, pc = P
                dord = allord[Bb["dpos"]]
                lst = []
                for j in range(len(pb)):
                    a, b = _win(P, j, len(dord))
                    for T in GTC_D:
                        # first day t (a < t <= b+1 bounded) with dord[t] - dord[pb] >= T; signal at t-1 close
                        tgt = dord[pb[j]] + T
                        t = int(np.searchsorted(dord, tgt))
                        if a + 1 <= t <= b and t >= 1:
                            lst.append((t - 1, -1 if Bb["c"][t - 1] > pp[j] else 1, None))
                lst.sort(key=lambda e: e[0])
                emit("GTC_D", "gann", "D", f"k{k}", Bb, lst, True)
        if not dm:
            # square of 9
            dp = Bb["dpos"]
            okm = ~np.isnan(M["o"])
            fo = np.argmax(okm, axis=1)
            dopen = M["o"][np.arange(nd), fo]
            okc = ~np.isnan(M["c"])
            lc = M["c"].shape[1] - 1 - np.argmax(okc[:, ::-1], axis=1)
            dclose = M["c"][np.arange(nd), lc]
            prevc = np.concatenate([[np.nan], dclose[:-1]])
            S9 = sq9(Bb, dopen[dp], prevc[dp])
            for nm, arr in S9.items():
                emit(nm, "gann", tf, "", Bb, arr)
        for nm, arr in wyckoff(Bb, A).items():
            emit(nm, "wyckoff", tf, "", Bb, arr, dm)
        if tf in (5, 15):
            lev = profile_levels(M["h"], M["l"], M["c"])
            levp = np.vstack([np.full((1, 3), np.nan), lev[:-1]])
            for nm, arr in mp_signals(Bb, M["o"], M["h"], M["l"], levp).items():
                emit(nm, "profile", tf, "tpo", Bb, arr)
            if vp_weights is not None:
                levv = profile_levels(M["h"], M["l"], M["c"], weights=vp_weights)
                levvp = np.vstack([np.full((1, 3), np.nan), levv[:-1]])
                for nm, arr in mp_signals(Bb, M["o"], M["h"], M["l"], levvp, with_ib=False).items():
                    emit("VP" + nm[2:], "profile", tf, "vol", Bb, arr)
    # Renko / P&F from 1-minute closes
    B15 = bars(M, 15)
    A15 = atr(B15)
    # ATR of 15-min bars at each day's close -> box for the NEXT day
    last_of_day = np.full(nd, np.nan)
    ends = np.nonzero(np.concatenate([B15["dpos"][1:] != B15["dpos"][:-1], [True]]))[0]
    last_of_day[B15["dpos"][ends]] = A15[ends]
    atr_prev = np.concatenate([[np.nan], last_of_day[:-1]])
    okc = ~np.isnan(M["c"])
    lc = M["c"].shape[1] - 1 - np.argmax(okc[:, ::-1], axis=1)
    dclose = M["c"][np.arange(nd), lc]
    prevc = np.concatenate([[np.nan], dclose[:-1]])
    B1 = dict(dpos=np.repeat(np.arange(nd), M["c"].shape[1]), col_end=np.tile(np.arange(M["c"].shape[1]), nd))
    boxes = [(f"b{p * 100:.1f}pct", p * prevc) for p in BOX_PCT] + [(f"b{a}atr", a * atr_prev) for a in BOX_ATR]
    for par, box in boxes:
        R = renko_pf(M["c"], M["o"], box)
        for nm, arr in R.items():
            emit(nm, "renko" if nm.startswith("RK") else "pnf", 1, par, B1, arr.ravel())
    return cfg
