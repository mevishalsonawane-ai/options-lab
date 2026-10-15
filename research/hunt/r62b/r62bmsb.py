"""R62b: a line-by-line Python port of EmreKb's "Market Structure Break & Order Block" (TradingView, Pine v5, open
source, https://www.tradingview.com/script/DkE7UniD/), plus the setup walk that turns its zones into entry candidates
(PREREG.md sections 1, 3, 4).

The indicator's logic below is a port of code under the Mozilla Public License 2.0 (c) EmreKb,
https://mozilla.org/MPL/2.0/ ; "Lnn" comments name the source line each step follows.

Pure numpy on one instrument's minute grid; nothing here reads files or prices a trade. Time is a flat minute index
t = session * 375 + row. A bar is known only at its last row; a pivot only at the bar where the zigzag trend flips.
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

import r62bcfg as K

O, H, L, C = range(4)
NAN = np.nan


# ------------------------------------------------------------------------------------------------ bars (as R62)
@dataclass
class Bars:
    di: np.ndarray        # session index
    r0: np.ndarray        # first row of the bar's slot
    rend: np.ndarray      # nominal last row of the slot (the bar is known at its close)
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    t: np.ndarray         # flat time of the bar's close: di * 375 + rend

    def __len__(self):
        return len(self.t)


def make_bars(M: np.ndarray, tf: int) -> Bars:
    """M: (nd, 375, 4) one-minute O H L C (NaN = missing). Bars of tf minutes aligned to 09:15 (the last slot of a
    session may be shorter); a slot with no minute makes no bar."""
    nd = M.shape[0]
    out = {k: [] for k in ("di", "r0", "rend", "o", "h", "l", "c")}
    for d in range(nd):
        for r0 in range(0, K.NROWS, tf):
            r1 = min(r0 + tf, K.NROWS)
            seg = M[d, r0:r1]
            ok = np.isfinite(seg[:, C])
            if not ok.any():
                continue
            s = seg[ok]
            out["di"].append(d)
            out["r0"].append(r0)
            out["rend"].append(r1 - 1)
            out["o"].append(s[0, O])
            out["h"].append(np.nanmax(s[:, H]))
            out["l"].append(np.nanmin(s[:, L]))
            out["c"].append(s[-1, C])
    a = {k: np.asarray(v, dtype=np.int64 if k in ("di", "r0", "rend") else np.float64) for k, v in out.items()}
    return Bars(a["di"], a["r0"], a["rend"], a["o"], a["h"], a["l"], a["c"], a["di"] * K.NROWS + a["rend"])


# ------------------------------------------------------------------------------------------------ the indicator
@dataclass
class MSB:
    trend: np.ndarray     # zigzag trend after bar t (L55-57)
    market: np.ndarray    # market state after bar t (L104-109): +1 / -1
    brk: np.ndarray       # +1 bullish MSB on bar t, -1 bearish, 0 none (L147)
    h0: np.ndarray        # pivots as they stood at bar t's close (L92-96)
    h0i: np.ndarray
    h1: np.ndarray
    h1i: np.ndarray
    l0: np.ndarray
    l0i: np.ndarray
    l1: np.ndarray
    l1i: np.ndarray
    bu_ob: np.ndarray     # bullish OB bar index after bar t (L111-116)
    be_ob: np.ndarray     # bearish OB bar index (L120-125)


def _eq(a: float, b: float) -> bool:
    """Pine's == with na: false when either side is na."""
    return a == a and b == b and a == b


def _loop(a: float, b: float):
    """Pine v5 `for i = a to b`: steps -1 when a > b; does not run when a bound is na."""
    if not (a == a and b == b):
        return range(0)
    a, b = int(a), int(b)
    return range(a, b + 1) if a <= b else range(a, b - 1, -1)


def msb(o: np.ndarray, h: np.ndarray, l: np.ndarray, c: np.ndarray, length: int = K.ZIGZAG_LEN,
        fib: float = K.FIB) -> MSB:
    """The indicator on bars (o, h, l, c), bar by bar. Every value at bar t uses bars 0..t only."""
    n = len(h)
    trend = np.ones(n, np.int8)
    market = np.ones(n, np.int8)
    brk = np.zeros(n, np.int8)
    piv = {k: np.full(n, NAN) for k in ("h0", "h0i", "h1", "h1i", "l0", "l0i", "l1", "l1i", "bu", "be")}
    hp, hix = [NAN] * 5, [NAN] * 5           # L42-45: arrays of 5 na
    lp, lix = [NAN] * 5, [NAN] * 5
    last_up = last_dn = -1                   # last bar where to_up[t-1] / to_down[t-1] was true (L59, L63)
    last_low_eq = last_high_eq = -1          # last bar where low == low_val / high == high_val (L61, L65)
    to_up_prev = to_down_prev = False
    pre_prev = 1                             # market before L109 on the previous bar (the history of L107's call)
    last_l0 = last_h0 = NAN                  # L107-108
    bu_prev = be_prev = NAN
    for t in range(n):
        # L52-53: ta.highest / ta.lowest over `length` bars, current included (na before `length` bars exist)
        if t >= length - 1:
            to_up = h[t] >= h[t - length + 1:t + 1].max()
            to_down = l[t] <= l[t - length + 1:t + 1].min()
        else:
            to_up = to_down = False
        # L55-57
        tr = trend[t - 1] if t > 0 else 1
        tr = -1 if (tr == 1 and to_down) else (1 if (tr == -1 and to_up) else tr)
        trend[t] = tr
        # L59-61: barssince(to_up[1]); lowest over that many bars (1 when 0 or na); barssince(low_val == low)
        if to_up_prev:
            last_up = t
        nl = (t - last_up) if last_up >= 0 else 0
        nl = nl if nl > 0 else 1
        low_val = l[t - nl + 1:t + 1].min() if t - nl + 1 >= 0 else NAN
        if _eq(low_val, l[t]):
            last_low_eq = t
        low_index = float(last_low_eq) if last_low_eq >= 0 else NAN
        # L63-65
        if to_down_prev:
            last_dn = t
        nh = (t - last_dn) if last_dn >= 0 else 0
        nh = nh if nh > 0 else 1
        high_val = h[t - nh + 1:t + 1].max() if t - nh + 1 >= 0 else NAN
        if _eq(high_val, h[t]):
            last_high_eq = t
        high_index = float(last_high_eq) if last_high_eq >= 0 else NAN
        to_up_prev, to_down_prev = to_up, to_down
        # L67-73: push the pivot when the trend flips
        if t > 0 and trend[t] != trend[t - 1]:
            if tr == 1:
                lp.append(low_val)
                lix.append(low_index)
            else:
                hp.append(high_val)
                hix.append(high_index)
        h0, h0i, h1, h1i = hp[-1], hix[-1], hp[-2], hix[-2]          # L92-96
        l0, l0i, l1, l1i = lp[-1], lix[-1], lp[-2], lix[-2]
        # L104-109
        pre = market[t - 1] if t > 0 else 1
        if t > 0 and pre != pre_prev:                               # L107: ta.change(market) before L109
            last_l0, last_h0 = l0, h0
        pre_prev = pre
        m = pre
        if not (_eq(last_l0, l0) or _eq(last_h0, h0)):
            if m == 1 and l0 < l1 and l0 < l1 - abs(h0 - l1) * fib:
                m = -1
            elif m == -1 and h0 > h1 and h0 > h1 + abs(h1 - l0) * fib:
                m = 1
        market[t] = m
        if t > 0 and m != pre:
            brk[t] = m                                              # L147: ta.change(market) after L109
        for k, v in (("h0", h0), ("h0i", h0i), ("h1", h1), ("h1i", h1i), ("l0", l0), ("l0i", l0i), ("l1", l1),
                     ("l1i", l1i)):
            piv[k][t] = v
        # L111-116: bullish OB = last bearish candle in loop h1i .. l0i[length]
        bu = bu_prev if bu_prev == bu_prev else float(t)
        lag = piv["l0i"][t - length] if t - length >= 0 else NAN
        for i in _loop(h1i, lag):
            if 0 <= i <= t and o[i] > c[i]:
                bu = float(i)
        # L120-125: bearish OB = last bullish candle in loop l1i .. h0i[length]
        be = be_prev if be_prev == be_prev else float(t)
        lag = piv["h0i"][t - length] if t - length >= 0 else NAN
        for i in _loop(l1i, lag):
            if 0 <= i <= t and o[i] < c[i]:
                be = float(i)
        piv["bu"][t], piv["be"][t] = bu, be
        bu_prev, be_prev = bu, be
    return MSB(trend, market, brk, piv["h0"], piv["h0i"], piv["h1"], piv["h1i"], piv["l0"], piv["l0i"], piv["l1"],
               piv["l1i"], piv["bu"], piv["be"])


@dataclass
class Zone:
    k: int                # the MSB bar (signal timeframe)
    dir: int              # +1 green (buy) / -1 red (sell)
    zlo: float
    zhi: float
    ob: int               # the OB candle's bar
    stop: float           # S (PREREG 4)
    tgt: float            # T
    act_t: int            # flat time it becomes known (close of bar k)


def zones(b: Bars, m: MSB) -> list:
    """One zone per MSB (L151, L158): the OB candle's full range, with the stop and target levels (PREREG 4)."""
    out = []
    for k in np.flatnonzero(m.brk != 0):
        d = int(m.brk[k])
        j = m.bu_ob[k] if d > 0 else m.be_ob[k]
        if not j == j:
            continue
        j = int(j)
        zlo, zhi = float(b.l[j]), float(b.h[j])
        if d > 0:
            stop = min(float(m.l0[k]), zlo) * (1 - K.STOP_BUFFER)
            tgt = float(m.h0[k])
        else:
            stop = max(float(m.h0[k]), zhi) * (1 + K.STOP_BUFFER)
            tgt = float(m.l0[k])
        if not (np.isfinite(stop) and np.isfinite(tgt)):
            continue
        out.append(Zone(int(k), d, zlo, zhi, j, stop, tgt, int(b.t[k])))
    return out


def state_at(b: Bars, state: np.ndarray, t) -> np.ndarray:
    """The state known at flat times t: after the last bar that closed at or before t (0 before any bar)."""
    j = np.searchsorted(b.t, np.asarray(t), side="right") - 1
    return np.where(j >= 0, state[np.clip(j, 0, None)], 0)


# ------------------------------------------------------------------------------------------------ the setup walk
@dataclass
class Cand:
    di: int               # session of the signal
    s: int                # signal row; the fill is from s + 1
    side: int             # +1 CE / -1 PE
    zone: int             # zone id (one try per zone)
    conf: str             # 'tch' / 'rej'
    htf: bool             # the 1-hour market state agrees
    stop: float
    tgt: float
    zlo: float
    zhi: float
    act_di: int           # session the zone became active in


def zone_life_end(b: Bars, z: Zone, nxt_t: int, act_di: int, nd: int) -> int:
    """The last flat minute the zone is alive (inclusive): the opposite MSB's close, the end of the session after the
    activation session, or the close of the first signal bar after activation that closes beyond its far side."""
    end = min(nxt_t, (act_di + 2) * K.NROWS - 1) if act_di + 1 < nd else min(nxt_t, (act_di + 1) * K.NROWS - 1)
    j0 = z.k + 1
    for j in range(j0, len(b)):
        if b.t[j] > end:
            break
        if (z.dir > 0 and b.c[j] < z.zlo) or (z.dir < 0 and b.c[j] > z.zhi):
            return int(b.t[j])
    return int(end)


def touch_minute(Mf: np.ndarray, z: Zone, end: int) -> int:
    """`tch`: the first minute after activation whose low <= zhi (green) / high >= zlo (red); -1 if none by `end`."""
    lo, hi = Mf[z.act_t + 1:end + 1, L], Mf[z.act_t + 1:end + 1, H]
    hit = (lo <= z.zhi) if z.dir > 0 else (hi >= z.zlo)
    hit &= np.isfinite(lo)
    k = np.flatnonzero(hit)
    return int(z.act_t + 1 + k[0]) if len(k) else -1


def reject_bar(b: Bars, z: Zone, end: int) -> int:
    """`rej`: the signal bar whose close confirms the first touch (PREREG 4), or -1."""
    d = z.dir
    for j in range(z.k + 1, len(b)):
        if b.t[j] > end:
            return -1
        touched = b.l[j] <= z.zhi if d > 0 else b.h[j] >= z.zlo
        if not touched:
            continue
        if (d > 0 and b.c[j] > z.zhi) or (d < 0 and b.c[j] < z.zlo):
            return j
        inside = b.c[j] >= z.zlo if d > 0 else b.c[j] <= z.zhi
        if inside and j + 1 < len(b) and b.t[j + 1] <= end:
            g = b.c[j + 1] > b.o[j + 1] and b.c[j + 1] > z.zhi if d > 0 else (b.c[j + 1] < b.o[j + 1]
                                                                               and b.c[j + 1] < z.zlo)
            if g:
                return j + 1
        return -1
    return -1


def candidates(M: np.ndarray, tf: int, fib: float = K.FIB) -> tuple[list, dict]:
    """Entry candidates of one index on one signal timeframe, both confirmations, in time order (PREREG 4)."""
    nd = M.shape[0]
    Mf = M.reshape(-1, 4)
    b = make_bars(M, tf)
    m = msb(b.o, b.h, b.l, b.c, K.ZIGZAG_LEN, fib)
    b60 = make_bars(M, K.TF_HTF)
    m60 = msb(b60.o, b60.h, b60.l, b60.c, K.ZIGZAG_LEN, fib)
    zs = zones(b, m)
    cnt = dict(bars=len(b), msb=len(zs), fresh=0, tch=0, rej=0)
    out = []
    for zi, z in enumerate(zs):
        if not ((z.dir > 0 and b.c[z.k] > z.zhi) or (z.dir < 0 and b.c[z.k] < z.zlo)):
            continue
        cnt["fresh"] += 1
        nxt = zs[zi + 1].act_t if zi + 1 < len(zs) else 1 << 62
        act_di = int(b.di[z.k])
        end = zone_life_end(b, z, nxt, act_di, nd)
        sigs = []
        f = touch_minute(Mf, z, end)
        if f >= 0:
            sigs.append(("tch", f))
        j = reject_bar(b, z, end)
        if j >= 0:
            sigs.append(("rej", int(b.t[j])))
        for conf, ft in sigs:
            di, s = divmod(ft, K.NROWS)
            if not (K.SIG_FIRST <= s <= K.SIG_LAST):
                continue
            cnt[conf] += 1
            htf = bool(state_at(b60, m60.market, ft) == z.dir)
            out.append(Cand(int(di), int(s), z.dir, zi, conf, htf, z.stop, z.tgt, z.zlo, z.zhi, act_di))
    out.sort(key=lambda x: (x.di, x.s, x.conf))
    return out, cnt
