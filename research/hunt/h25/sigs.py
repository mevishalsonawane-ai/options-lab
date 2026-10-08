"""h25 signal library: every TA-Lib candlestick pattern, objective chart patterns, every moving-average family and the
classic indicators, on tf-minute bars of an index (anchored 09:15). Each signal is an int8 array over bars:
+1 bull (buy a CE), -1 bear (buy a PE), 0 nothing. Everything at bar t uses bars <= t only (swing pivots are known two
bars after the pivot).
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.join("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad",
                                "hunt/h25/pylib"))
import numpy as np  # noqa: E402
import talib  # noqa: E402
from scipy.signal import lfilter  # noqa: E402

CDL = sorted(talib.get_function_groups()["Pattern Recognition"])
NEUTRAL = {"CDLDOJI", "CDLLONGLEGGEDDOJI", "CDLRICKSHAWMAN", "CDLHIGHWAVE", "CDLSPINNINGTOP"}
MA_TYPES = ["SMA", "EMA", "WMA", "HMA", "DEMA", "TEMA", "KAMA"]
PX_N = (9, 20, 50, 200)
SLOPE_N = (9, 20, 50)
PAIRS = ((5, 13), (9, 21), (13, 34), (20, 50), (50, 200))
PB_N = (20, 50)
RIB = (5, 8, 13, 21, 34, 55)
CHART = ["CH_DOUBLE", "CH_BOS", "CH_TRIANGLE", "CH_FLAG", "CH_INSIDE", "CH_NR7", "CH_NR4", "CH_3BAR", "CH_OUTSIDE"]
OTHER = ["MACD_X", "MACD_0", "STOCH_X", "STOCH_ZONE", "CCI_BRK", "CCI_REV", "WILLR", "RSI_REV", "RSI_50", "BB_OUT",
         "BB_REV", "BB_SQZ", "KC_OUT", "TTM_SQZ", "DON20", "DON55", "ICHI_TK", "ICHI_CLOUD", "PSAR", "AROON", "ADX_DI",
         "SUPERTREND", "HEIKIN", "TWAP_X", "ROC0"]


def family_of(name):
    if name.startswith("CDL"):
        return "candlestick"
    if name.startswith("CH_"):
        return "chart"
    if name.split("_")[0] in MA_TYPES or name.startswith("RIB"):
        return "moving-average"
    return "indicator"


def names():
    out = list(CDL) + list(CHART)
    for T in MA_TYPES:
        out += [f"{T}_PX{n}" for n in PX_N] + [f"{T}_SLOPE{n}" for n in SLOPE_N]
        out += [f"{T}_X{a}_{b}" for a, b in PAIRS] + [f"{T}_PB{n}" for n in PB_N]
    out += ["RIB_EMA", "RIB_SMA"] + OTHER
    return out


# ------------------------------------------------------------------------------------------------ bars
def bars(M, tf):
    """M: dict o/h/l/c of (n_days, 375) minute arrays. Returns dict o,h,l,c (n_bars,), dpos (day row), col_end
    (last column of the bar, so sig_min = 555 + col_end)."""
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


# ------------------------------------------------------------------------------------------------ helpers
def _prev(a, k=1):
    p = np.empty_like(a)
    p[:k] = np.nan
    p[k:] = a[:-k]
    return p


def xup(a, b):
    with np.errstate(invalid="ignore"):
        return (a > b) & (_prev(a) <= _prev(b))


def xdn(a, b):
    with np.errstate(invalid="ignore"):
        return (a < b) & (_prev(a) >= _prev(b))


def sig(up, dn):
    up = np.asarray(up, bool)
    dn = np.asarray(dn, bool)
    return np.where(up & ~dn, 1, np.where(dn & ~up, -1, 0)).astype(np.int8)


def hma(c, n):
    h = max(n // 2, 1)
    s = max(int(np.sqrt(n)), 1)
    x = 2 * talib.WMA(c, h) - talib.WMA(c, n)
    out = np.full_like(c, np.nan)
    ok = ~np.isnan(x)
    if ok.sum() > s:
        out[ok] = talib.WMA(x[ok], s)
    return out


def ma(T, c, n):
    if T == "HMA":
        return hma(c, n)
    return getattr(talib, T)(c, timeperiod=n)


def roll_max(a, n):
    from numpy.lib.stride_tricks import sliding_window_view as swv
    out = np.full_like(a, np.nan)
    if len(a) >= n:
        out[n - 1:] = swv(a, n).max(axis=1)
    return out


def roll_min(a, n):
    from numpy.lib.stride_tricks import sliding_window_view as swv
    out = np.full_like(a, np.nan)
    if len(a) >= n:
        out[n - 1:] = swv(a, n).min(axis=1)
    return out


def ffill(a):
    idx = np.where(~np.isnan(a), np.arange(len(a)), 0)
    np.maximum.accumulate(idx, out=idx)
    out = a[idx]
    if np.isnan(a[0]):
        first = np.argmax(~np.isnan(a)) if (~np.isnan(a)).any() else len(a)
        out[:first] = np.nan
    return out


def pivots(h, lo, k=2):
    """Swing pivots (k-bar fractals). Returns, as known at each bar (confirmed k bars later):
    last / previous pivot-high value and index, last / previous pivot-low value and index."""
    n = len(h)
    ph = np.zeros(n, bool)
    pl = np.zeros(n, bool)
    for j in range(1, k + 1):
        pass
    core = slice(k, n - k)
    phc = np.ones(n - 2 * k, bool)
    plc = np.ones(n - 2 * k, bool)
    for j in range(1, k + 1):
        phc &= (h[k:n - k] > h[k - j:n - k - j]) & (h[k:n - k] >= h[k + j:n - k + j])
        plc &= (lo[k:n - k] < lo[k - j:n - k - j]) & (lo[k:n - k] <= lo[k + j:n - k + j])
    ph[core] = phc
    pl[core] = plc
    res = {}
    for nm, mask, val in (("H", ph, h), ("L", pl, lo)):
        idx = np.nonzero(mask)[0]
        conf = idx + k                              # known at this bar
        last_v = np.full(n, np.nan)
        last_i = np.full(n, np.nan)
        prev_v = np.full(n, np.nan)
        prev_i = np.full(n, np.nan)
        if len(idx):
            last_v[conf] = val[idx]
            last_i[conf] = idx
            if len(idx) > 1:
                prev_v[conf[1:]] = val[idx[:-1]]
                prev_i[conf[1:]] = idx[:-1]
        res[nm] = (ffill(last_v), ffill(last_i), ffill(prev_v), ffill(prev_i))
    return res


def supertrend(h, lo, c, n=10, m=3.0):
    atr = talib.ATR(h, lo, c, n)
    hl2 = (h + lo) / 2
    ub = hl2 + m * atr
    lb = hl2 - m * atr
    N = len(c)
    fu = ub.copy()
    fl = lb.copy()
    d = np.zeros(N, np.int8)
    cur = 1
    for i in range(1, N):
        if np.isnan(atr[i]):
            continue
        if not np.isnan(fu[i - 1]) and not (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]):
            fu[i] = fu[i - 1]
        if not np.isnan(fl[i - 1]) and not (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]):
            fl[i] = fl[i - 1]
        if cur == 1 and c[i] < fl[i]:
            cur = -1
        elif cur == -1 and c[i] > fu[i]:
            cur = 1
        d[i] = cur
    pd_ = _prev(d.astype(float))
    return sig((d == 1) & (pd_ == -1), (d == -1) & (pd_ == 1))


# ------------------------------------------------------------------------------------------------ all signals
def compute(B):
    o, h, lo, c = (B[k].astype(np.float64) for k in ("o", "h", "l", "c"))
    dpos = B["dpos"]
    S = {}
    mv5 = c - _prev(c, 5)
    # candlesticks
    for f in CDL:
        r = getattr(talib, f)(o, h, lo, c)
        if f in NEUTRAL and not (r < 0).any():
            s = np.where(r != 0, np.where(mv5 < 0, 1, np.where(mv5 > 0, -1, 0)), 0)
        elif f == "CDLGRAVESTONEDOJI":
            s = np.where(r != 0, -1, 0)
        elif f == "CDLDRAGONFLYDOJI":
            s = np.where(r != 0, 1, 0)
        else:
            s = np.sign(r)
        S[f] = s.astype(np.int8)
    # chart patterns
    atr = talib.ATR(h, lo, c, 14)
    P = pivots(h, lo)
    H1, H1i, H0, H0i = P["H"]          # last pivot high (H1) and the one before (H0)
    L1, L1i, L0, L0i = P["L"]
    with np.errstate(invalid="ignore"):
        dbl_b = (np.abs(L1 - L0) <= 0.25 * atr) & (H1i > L0i) & (H1i < L1i)
        dbl_t = (np.abs(H1 - H0) <= 0.25 * atr) & (L1i > H0i) & (L1i < H1i)
        S["CH_DOUBLE"] = sig(dbl_b & xup(c, H1), dbl_t & xdn(c, L1))
        S["CH_BOS"] = sig((L1 > L0) & xup(c, H1), (H1 < H0) & xdn(c, L1))
        tri = (H1 < H0) & (L1 > L0)
        S["CH_TRIANGLE"] = sig(tri & xup(c, H1), tri & xdn(c, L1))
        pole = _prev(c, 5) - _prev(c, 10)
        chi = _prev(roll_max(h, 5))
        clo = _prev(roll_min(lo, 5))
        rng = chi - clo
        bullf = (pole >= 2.5 * atr) & (rng <= 0.5 * pole) & (c > chi) & (_prev(c) <= chi)
        bearf = (-pole >= 2.5 * atr) & (rng <= -0.5 * pole) & (c < clo) & (_prev(c) >= clo)
        S["CH_FLAG"] = sig(bullf, bearf)
        h1, l1, h2, l2 = _prev(h), _prev(lo), _prev(h, 2), _prev(lo, 2)
        inside = (h1 < h2) & (l1 > l2)
        S["CH_INSIDE"] = sig(inside & (c > h1), inside & (c < l1))
        r_ = h - lo
        for k, nm in ((7, "CH_NR7"), (4, "CH_NR4")):
            nr = _prev((r_ <= roll_min(r_, k)).astype(float)) == 1
            S[nm] = sig(nr & (c > h1), nr & (c < l1))
        o2, c2 = _prev(o, 2), _prev(c, 2)
        S["CH_3BAR"] = sig((c2 < o2) & (l1 < l2) & (c > h1), (c2 > o2) & (h1 > h2) & (c < l1))
        outside = (h > h1) & (lo < l1)
        S["CH_OUTSIDE"] = sig(outside & (c > h1), outside & (c < l1))
    # moving averages
    with np.errstate(invalid="ignore"):
        for T in MA_TYPES:
            cache = {}

            def m_(n):
                if n not in cache:
                    cache[n] = ma(T, c, n)
                return cache[n]
            for n in PX_N:
                S[f"{T}_PX{n}"] = sig(xup(c, m_(n)), xdn(c, m_(n)))
            for n in SLOPE_N:
                a = m_(n)
                sl = np.sign(a - _prev(a))
                ps = _prev(sl)
                S[f"{T}_SLOPE{n}"] = sig((sl > 0) & (ps <= 0), (sl < 0) & (ps >= 0))
            for a, b in PAIRS:
                S[f"{T}_X{a}_{b}"] = sig(xup(m_(a), m_(b)), xdn(m_(a), m_(b)))
            for n in PB_N:
                a = m_(n)
                pa, pc = _prev(a), _prev(c)
                S[f"{T}_PB{n}"] = sig((a > pa) & (pc > pa) & (lo <= a) & (c > a), (a < pa) & (pc < pa) & (h >= a) & (c < a))
        for T in ("EMA", "SMA"):
            R = [getattr(talib, T)(c, n) for n in RIB]
            up = np.ones(len(c), bool)
            dn = np.ones(len(c), bool)
            for x, y in zip(R[:-1], R[1:]):
                up &= x > y
                dn &= x < y
            pu, pdn = _prev(up.astype(float)) == 1, _prev(dn.astype(float)) == 1
            S[f"RIB_{T}"] = sig(up & ~pu, dn & ~pdn)
        # other indicators
        mac, mas, _ = talib.MACD(c, 12, 26, 9)
        S["MACD_X"] = sig(xup(mac, mas), xdn(mac, mas))
        z = np.zeros_like(c)
        S["MACD_0"] = sig(xup(mac, z), xdn(mac, z))
        k, d = talib.STOCH(h, lo, c, 14, 3, 0, 3, 0)
        S["STOCH_X"] = sig(xup(k, d) & (k < 20), xdn(k, d) & (k > 80))
        S["STOCH_ZONE"] = sig(xup(k, z + 20), xdn(k, z + 80))
        cci = talib.CCI(h, lo, c, 20)
        S["CCI_BRK"] = sig(xup(cci, z + 100), xdn(cci, z - 100))
        S["CCI_REV"] = sig(xup(cci, z - 100), xdn(cci, z + 100))
        wr = talib.WILLR(h, lo, c, 14)
        S["WILLR"] = sig(xup(wr, z - 80), xdn(wr, z - 20))
        rsi = talib.RSI(c, 14)
        S["RSI_REV"] = sig(xup(rsi, z + 30), xdn(rsi, z + 70))
        S["RSI_50"] = sig(xup(rsi, z + 50), xdn(rsi, z + 50))
        ub, mb, lb = talib.BBANDS(c, 20, 2, 2, 0)
        S["BB_OUT"] = sig(xup(c, ub), xdn(c, lb))
        S["BB_REV"] = sig(xup(c, lb), xdn(c, ub))
        bw = (ub - lb) / mb
        sq = roll_min(bw, 5) <= roll_min(bw, 120) + 1e-12
        S["BB_SQZ"] = sig(sq & xup(c, ub), sq & xdn(c, lb))
        e20 = talib.EMA(c, 20)
        a20 = talib.ATR(h, lo, c, 20)
        S["KC_OUT"] = sig(xup(c, e20 + 2 * a20), xdn(c, e20 - 2 * a20))
        on = (ub < e20 + 1.5 * a20) & (lb > e20 - 1.5 * a20)
        rel = (_prev(on.astype(float)) == 1) & ~on
        S["TTM_SQZ"] = sig(rel & (c > mb), rel & (c < mb))
        for n in (20, 55):
            dh, dl = _prev(roll_max(h, n)), _prev(roll_min(lo, n))
            S[f"DON{n}"] = sig(xup(c, dh), xdn(c, dl))
        tk = (roll_max(h, 9) + roll_min(lo, 9)) / 2
        kj = (roll_max(h, 26) + roll_min(lo, 26)) / 2
        S["ICHI_TK"] = sig(xup(tk, kj), xdn(tk, kj))
        sa = _prev((tk + kj) / 2, 26)
        sb = _prev((roll_max(h, 52) + roll_min(lo, 52)) / 2, 26)
        top, bot = np.fmax(sa, sb), np.fmin(sa, sb)
        S["ICHI_CLOUD"] = sig(xup(c, top), xdn(c, bot))
        sar = talib.SAR(h, lo, 0.02, 0.2)
        S["PSAR"] = sig(xup(c, sar), xdn(c, sar))
        adn, aup = talib.AROON(h, lo, 25)
        S["AROON"] = sig(xup(aup, adn), xdn(aup, adn))
        adx = talib.ADX(h, lo, c, 14)
        pdi, mdi = talib.PLUS_DI(h, lo, c, 14), talib.MINUS_DI(h, lo, c, 14)
        S["ADX_DI"] = sig(xup(pdi, mdi) & (adx > 25), xdn(pdi, mdi) & (adx > 25))
        S["SUPERTREND"] = supertrend(h, lo, c)
        hac = (o + h + lo + c) / 4
        hao = np.empty_like(c)
        hao[0] = (o[0] + c[0]) / 2
        hao[1:] = lfilter([0.5], [1, -0.5], hac[:-1], zi=[0.5 * hao[0]])[0]
        col = np.sign(hac - hao)
        pcol = _prev(col)
        S["HEIKIN"] = sig((col > 0) & (pcol < 0), (col < 0) & (pcol > 0))
        tp = (h + lo + c) / 3
        newday = np.r_[True, dpos[1:] != dpos[:-1]]
        grp = np.cumsum(newday) - 1
        cs = np.cumsum(tp)
        start = np.nonzero(newday)[0]
        base = np.r_[0, cs[start[1:] - 1]][grp]
        cnt = np.arange(len(c)) - start[grp] + 1
        twap = (cs - base) / cnt
        x_up = (c > twap) & (_prev(c) <= _prev(twap)) & ~newday
        x_dn = (c < twap) & (_prev(c) >= _prev(twap)) & ~newday
        S["TWAP_X"] = sig(x_up, x_dn)
        roc = talib.ROC(c, 10)
        S["ROC0"] = sig(xup(roc, z), xdn(roc, z))
    assert set(S) == set(names()), set(names()) ^ set(S)
    return S
