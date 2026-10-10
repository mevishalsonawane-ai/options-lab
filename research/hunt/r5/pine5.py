"""R5: bar-by-bar replication of the uploaded guide's Pine strategy (see PREREG.md).

simulate(B, piv, ms, cd, cfg) -> (trades DataFrame, diagnostics dict). Bars B from h37 sigs.bars (continuous, 09:15).
Index points, 1 unit, as TradingView's broker emulator with process_orders_on_close = true.
"""
from __future__ import annotations

import math
import sys

sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np
import pandas as pd
from numpy.lib.stride_tricks import sliding_window_view as swv

SQ144 = (36, 48, 72, 96, 108, 144)
TOL = 0.045
SLBUF = 0.25
TP1RR = 1.8
TICK = 0.05
COMM = 0.0005
SQ9N = (0.25, 0.5, 1.0, 1.5, 2.0)
FIBR = (0.382, 0.5, 0.618, 0.786)
CONF_BAND = 0.001


def atr_rma(h, l, c, n=14):
    pc = np.r_[np.nan, c[:-1]]
    tr = np.where(np.isnan(pc), h - l, np.maximum.reduce([h - l, np.abs(h - pc), np.abs(l - pc)]))
    a = np.full(len(tr), np.nan)
    if len(tr) < n:
        return a
    a[n - 1] = tr[:n].mean()
    for i in range(n, len(tr)):
        a[i] = (a[i - 1] * (n - 1) + tr[i]) / n
    return a


def pivots(x, L, R, high):
    """ta.pivothigh/low: value reported at bar p+R for pivot bar p (strict vs left, >= / <= vs right)."""
    n = len(x)
    out = np.full(n, np.nan)
    if n < L + R + 1:
        return out
    win = swv(x, L + R + 1)                       # win[k] = x[k .. k+L+R], pivot at k+L, reported at k+L+R
    v = win[:, L]
    if high:
        ok = (v > win[:, :L].max(1)) & (v >= win[:, L + 1:].max(1))
    else:
        ok = (v < win[:, :L].min(1)) & (v <= win[:, L + 1:].min(1))
    idx = np.nonzero(ok)[0]
    out[idx + L + R] = v[idx]
    return out


def okr(a, t, tol):
    return abs(a - t) <= tol


def pattern(X, A, Bp, C, Dp):
    XA, AB, BC, XD = abs(A - X), abs(Bp - A), abs(C - Bp), abs(Dp - X)
    if not XA > 0:
        return None
    rB = AB / XA
    rC = BC / AB if AB > 0 else (math.inf if BC > 0 else math.nan)    # Pine: x/0 -> na/inf; comparisons false
    rD = XD / XA
    cab = (rC >= 0.382) and (rC <= 0.886)
    if okr(rB, 0.618, TOL) and cab and okr(rD, 0.786, TOL):
        return "Gartley"
    if 0.382 <= rB <= 0.50 and cab and okr(rD, 0.886, TOL):
        return "Bat"
    if okr(rB, 0.786, TOL) and cab and 1.27 <= rD <= 1.618:
        return "Butterfly"
    if 0.382 <= rB <= 0.618 and cab and okr(rD, 1.618, TOL + 0.015):
        return "Crab"
    return None


def confl(Bp, C, Dp):
    for n in SQ9N:
        for sgn in (1, -1):
            r = math.sqrt(C) + sgn * n
            lv = r * r
            if abs(Dp - lv) <= CONF_BAND * lv:
                return True
    for r in FIBR:
        lv = C - r * (C - Bp)
        if abs(Dp - lv) <= CONF_BAND * Dp:
            return True
    return False


def simulate(B, piv, ms, cd, cfg):
    """cfg: AS_WRITTEN (useSq144) | PRAG | CONFL. Returns trades, diag."""
    o, h, l, c = B["o"], B["h"], B["l"], B["c"]
    n = len(c)
    atr = B.get("_atr")
    if atr is None:
        atr = B["_atr"] = atr_rma(h, l, c)
    key = ("_pv", piv)
    if key not in B:
        B[key] = (pivots(h, piv, piv, True), pivots(l, piv, piv, False))
    ph, pl = B[key]
    use144 = cfg == "AS_WRITTEN"
    useconf = cfg == "CONFL"

    pP, pB, pH = [], [], []
    ver = 0
    pat_cache = (-1, None, None)
    last_sig = 0
    pos, ebar, epx, sl, tp = 0, -1, 0.0, 0.0, 0.0
    cur = None
    trades = []
    diag = dict(signals=0, resignal_in_pos=0, sig_per_D={}, alt_ok=0, alt_bad=0, sl_wrong_side=0,
                tp_wrong_side=0, imm_exit=0, win144_bars=0, bars=n, adds=0)

    def close_pos(i, px, why):
        nonlocal pos, cur
        cur.update(xbar=i, xpx=px, why=why)
        trades.append(cur)
        cur = None
        pos = 0

    def f_add(price, bar, isH, a):
        nonlocal ver
        if len(pP) == 0 or abs(price - pP[-1]) > a * ms:      # na atr -> comparison false
            pP.append(price)
            pB.append(bar)
            pH.append(isH)
            if len(pP) > 15:
                pP.pop(0), pB.pop(0), pH.pop(0)
            ver += 1
            diag["adds"] += 1

    for i in range(n):
        # ---- 1. pending exit orders on this bar (TradingView broker emulator)
        if pos != 0 and ebar < i:
            oo, hh, ll = o[i], h[i], l[i]
            if pos > 0:
                if oo <= sl:
                    close_pos(i, oo, "SL")
                elif oo >= tp:
                    close_pos(i, oo, "TP")
                else:
                    up_first = (hh - oo) < (oo - ll) or ((hh - oo) == (oo - ll))
                    if up_first:
                        if hh >= tp:
                            close_pos(i, tp, "TP")
                        elif ll <= sl:
                            close_pos(i, sl, "SL")
                    else:
                        if ll <= sl:
                            close_pos(i, sl, "SL")
                        elif hh >= tp:
                            close_pos(i, tp, "TP")
            else:
                if oo >= sl:
                    close_pos(i, oo, "SL")
                elif oo <= tp:
                    close_pos(i, oo, "TP")
                else:
                    up_first = (hh - oo) < (oo - ll) or ((hh - oo) == (oo - ll))
                    if up_first:
                        if hh >= sl:
                            close_pos(i, sl, "SL")
                        elif ll <= tp:
                            close_pos(i, tp, "TP")
                    else:
                        if ll <= tp:
                            close_pos(i, tp, "TP")
                        elif hh >= sl:
                            close_pos(i, sl, "SL")
        # ---- 2. script at the bar close
        a = atr[i]
        if not np.isnan(ph[i]):
            f_add(ph[i], i - piv, True, a)
        if not np.isnan(pl[i]):
            f_add(pl[i], i - piv, False, a)
        inwin = False
        if len(pB):
            bs = i - pB[-1]
            inwin = any(abs(bs - d) <= 3 for d in SQ144)
            if inwin:
                diag["win144_bars"] += 1
        if len(pP) < 5:
            continue
        if pat_cache[0] != ver:
            X, A, Bp, C, Dp = pP[-5:]
            nm = pattern(X, A, Bp, C, Dp)
            hs = pH[-5:]
            alt = all(hs[k] != hs[k + 1] for k in range(4))
            cf = confl(Bp, C, Dp) if nm else False
            pat_cache = (ver, nm, dict(X=X, D=Dp, Dbar=pB[-1], bull=not pH[-1], alt=alt, cf=cf))
        nm, info = pat_cache[1], pat_cache[2]
        if nm is None:
            continue
        timeok = (not use144) or inwin
        if useconf and not info["cf"]:
            continue
        if not (timeok and (i - last_sig > cd)):
            continue
        if np.isnan(a):
            continue
        bull = info["bull"]
        side = 1 if bull else -1
        last_sig = i
        X, Dp = info["X"], info["D"]
        nsl = X - a * SLBUF if bull else X + a * SLBUF
        risk = abs(Dp - nsl)
        ntp = Dp + risk * TP1RR if bull else Dp - risk * TP1RR
        diag["signals"] += 1
        kD = (info["Dbar"], Dp)
        diag["sig_per_D"][kD] = diag["sig_per_D"].get(kD, 0) + 1
        if pos == side:
            diag["resignal_in_pos"] += 1
            sl, tp = nsl, ntp
            cur["n_mod"] += 1
        else:
            if pos == -side:
                close_pos(i, c[i], "REV")
            pos, ebar, epx, sl, tp = side, i, c[i], nsl, ntp
            cur = dict(ebar=i, side=side, epx=c[i], sl=nsl, tp=ntp, pat=nm, Dbar=info["Dbar"], D=Dp, X=X,
                       alt=info["alt"], lag=i - info["Dbar"], inwin=inwin, n_mod=0, cf=info["cf"])
            diag["alt_ok" if info["alt"] else "alt_bad"] += 1
            if (bull and nsl >= c[i]) or ((not bull) and nsl <= c[i]):
                diag["sl_wrong_side"] += 1
            if (bull and ntp <= c[i]) or ((not bull) and ntp >= c[i]):
                diag["tp_wrong_side"] += 1
        # process_orders_on_close: exit orders also tried at this close
        if pos > 0:
            if c[i] <= sl:
                close_pos(i, c[i], "IMM_SL")
                diag["imm_exit"] += 1
            elif c[i] >= tp:
                close_pos(i, c[i], "IMM_TP")
                diag["imm_exit"] += 1
        elif pos < 0:
            if c[i] >= sl:
                close_pos(i, c[i], "IMM_SL")
                diag["imm_exit"] += 1
            elif c[i] <= tp:
                close_pos(i, c[i], "IMM_TP")
                diag["imm_exit"] += 1
    if cur is not None:
        close_pos(n - 1, c[n - 1], "END")
    T = pd.DataFrame(trades)
    if len(T):
        T["gross"] = T.side * (T.xpx - T.epx)
        nslip = 1 + T.why.isin(["SL", "IMM_SL", "REV", "END"]).astype(int)
        T["net"] = T.gross - COMM * (T.epx + T.xpx) - TICK * nslip
        T["edpos"] = B["dpos"][T.ebar.values]
        T["xdpos"] = B["dpos"][T.xbar.values]
        T["ecol"] = B["col_end"][T.ebar.values]
        T["xcol"] = B["col_end"][T.xbar.values]
    spd = diag.pop("sig_per_D")
    diag["patterns_signalled"] = len(spd)
    diag["mean_sig_per_pattern"] = float(np.mean(list(spd.values()))) if spd else 0.0
    return T, diag


def twins_points(B, T, k=10, seed=5):
    """(A) random twins: same entry bar and holding bars, random side; P&L to the close of the exit bar, same costs."""
    rng = np.random.default_rng(seed)
    c = B["c"]
    if len(T) == 0:
        return np.array([])
    e = np.repeat(T.ebar.values, k)
    x = np.repeat(T.xbar.values, k)
    s = rng.choice([-1, 1], len(e))
    g = s * (c[x] - c[e])
    return g - COMM * (c[e] + c[x]) - TICK * 1.5
