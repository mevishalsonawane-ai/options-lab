"""R91 rule engine (PREREG section 3): 1h swings, the sweep, the strong 5m candle, the stop order, the underlying exit.

All prices are the UNDERLYING's (index spot 1m OHLC; MCX near future, close-only). Every function works on flat
1-minute arrays (a Series) so the tests can feed hand-made minutes.

    bars()        bars of `tf` minutes anchored at the session open, never across sessions
    swings()      fractal swing highs / lows (strictly above / below N bars each side)
    atr()         Wilder ATR(14) of a bar series
    levels()      the 1h swing levels (born at the confirming bar's close, dead at a break / sweep / expiry / reset)
                  and the sweeps of the latest eligible intact level
    run()         sweeps -> strong 5m candle -> stop order -> fill -> underlying exit (one position, 2 a day)
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from . import cfg as K

INF = np.iinfo(np.int64).max
EPS = 1e-9
R_STOP, R_TARGET, R_TIME = 1, 2, 3
WHY = {R_STOP: "stop", R_TARGET: "target", R_TIME: "time"}


@dataclass
class Series:
    """Global 1-minute arrays of one instrument (sessions in order) and its day facts."""
    mday: np.ndarray
    mt: np.ndarray
    mo: np.ndarray
    mh: np.ndarray
    ml: np.ndarray
    mc: np.ndarray
    anchor: int
    reset: np.ndarray           # per session: clear every level at its first bar (MCX first session of a contract)
    pdh: np.ndarray             # per session: the previous session's high / low (NaN for the first)
    pdl: np.ndarray

    @property
    def ndays(self) -> int:
        return len(self.reset)


def from_panel(P) -> Series:
    reset = P.skip.copy() if P.kind == "mcx" else np.zeros(P.ndays, bool)
    return Series(P.mday, P.mt, P.mo, P.mh, P.ml, P.mc, int(P.anchor), np.asarray(reset, bool), P.pdh, P.pdl)


# ------------------------------------------------------------------------------------------------ bars, swings, ATR
def bars(S: Series, tf: int) -> dict:
    """Bars of tf minutes anchored at the session open: day, o, h, l, c, s, e (first / last global minute)."""
    b = (S.mt.astype(np.int64) - S.anchor) // tf
    key = S.mday.astype(np.int64) * 100000 + b
    brk = np.r_[0, np.flatnonzero(key[1:] != key[:-1]) + 1].astype(np.int64)
    end = (np.r_[brk[1:], len(key)] - 1).astype(np.int64)
    return dict(day=S.mday[brk].astype(np.int64), o=S.mo[brk].astype(float), h=np.maximum.reduceat(S.mh, brk),
                l=np.minimum.reduceat(S.ml, brk), c=S.mc[end].astype(float), s=brk, e=end)


def swings(h: np.ndarray, l: np.ndarray, n: int):
    """Bar p is a swing high if h[p] > h of each of the n bars before AND after it (strict); lows mirrored."""
    m = len(h)
    hs = np.zeros(m, bool)
    ls = np.zeros(m, bool)
    if m < 2 * n + 1:
        return hs, ls
    a, b = n, m - n
    hs[a:b] = True
    ls[a:b] = True
    for j in range(1, n + 1):
        hs[a:b] &= (h[a:b] > h[a - j:b - j]) & (h[a:b] > h[a + j:b + j])
        ls[a:b] &= (l[a:b] < l[a - j:b - j]) & (l[a:b] < l[a + j:b + j])
    return hs, ls


def atr(B: dict, n: int = K.ATR_N) -> np.ndarray:
    """Wilder ATR(n): the first value is the mean of the first n true ranges, then (prev x (n-1) + TR) / n."""
    h, l, c = B["h"], B["l"], B["c"]
    m = len(h)
    out = np.full(m, np.nan)
    if m < n:
        return out
    pc = np.r_[np.nan, c[:-1]]
    tr = np.nanmax(np.vstack([h - l, np.abs(h - pc), np.abs(l - pc)]), axis=0)
    out[n - 1] = tr[:n].mean()
    for i in range(n, m):
        out[i] = (out[i - 1] * (n - 1) + tr[i]) / n
    return out


# ------------------------------------------------------------------------------------------------ levels and sweeps
@dataclass
class Levels:
    side: np.ndarray            # +1 swing high, -1 swing low
    px: np.ndarray
    p: np.ndarray               # the 1h swing bar
    sess: np.ndarray            # its session
    born: np.ndarray            # global minute of the confirming bar's close (usable from then on)
    died: np.ndarray            # global minute it stopped being intact (INF while intact)
    why: list                   # 'break' | 'sweep' | 'expired' | 'reset' | ''

    def alive_at(self, t: int) -> np.ndarray:
        return (self.born <= t) & (self.died > t)


def _segments(day: np.ndarray, reset: np.ndarray) -> np.ndarray:
    first = np.r_[True, day[1:] != day[:-1]]
    return np.cumsum(first & reset[day])


def levels(S: Series, H: dict, Q: dict, n: int, anyday: bool, tick: float, age: int = K.AGE) -> tuple:
    """H: 1h bars (swings); Q: the sweep bars (1h, or 15m for S15; every H close is also a Q close). Returns (Levels,
    sweeps, ambiguous). Each sweep: side (the TRADE's side: -1 after a swept high), L, X (the sweep's extreme), k (Q bar),
    day, t (global minute of the sweep bar's close), lvl (index into Levels)."""
    hs, ls = swings(H["h"], H["l"], n)
    seg = _segments(H["day"], S.reset)
    conf: dict = {}
    m = len(H["h"])
    for p in np.flatnonzero(hs | ls):
        if p + n < m and seg[p - n] == seg[p + n]:
            conf.setdefault(int(p + n), []).append(int(p))
    hend = {int(e): i for i, e in enumerate(H["e"])}
    side, px, pp, sess, born, died, why = [], [], [], [], [], [], []
    alive: list = []
    sweeps, ambiguous = [], 0

    def kill(i, t, w):
        died[i] = t
        why[i] = w

    for k in range(len(Q["o"])):
        d = int(Q["day"][k])
        te = int(Q["e"][k])
        if k == 0 or int(Q["day"][k - 1]) != d:
            ts = int(Q["s"][k])
            if S.reset[d]:
                for i in alive:
                    kill(i, ts, "reset")
                alive = []
            keep = []
            for i in alive:
                if sess[i] < d - age:
                    kill(i, ts, "expired")
                else:
                    keep.append(i)
            alive = keep
        # (1) the sweep test on the latest eligible intact level of each side
        cand = {}
        for i in alive:
            if not anyday and sess[i] >= d:
                continue
            s = side[i]
            if s not in cand or pp[i] > pp[cand[s]]:
                cand[s] = i
        hit = {}
        if 1 in cand:
            L = px[cand[1]]
            if Q["h"][k] >= L + tick - EPS and Q["c"][k] < L:
                hit[1] = cand[1]
        if -1 in cand:
            L = px[cand[-1]]
            if Q["l"][k] <= L - tick + EPS and Q["c"][k] > L:
                hit[-1] = cand[-1]
        for s, i in hit.items():
            kill(i, te, "sweep")
            alive.remove(i)
        if len(hit) == 2:
            ambiguous += 1
        elif 1 in hit:
            sweeps.append(dict(side=-1, L=px[hit[1]], X=float(Q["h"][k]), k=k, day=d, t=te, lvl=hit[1]))
        elif -1 in hit:
            sweeps.append(dict(side=1, L=px[hit[-1]], X=float(Q["l"][k]), k=k, day=d, t=te, lvl=hit[-1]))
        # (2) breaks: a close beyond an intact level
        c = Q["c"][k]
        keep = []
        for i in alive:
            if (side[i] > 0 and c > px[i]) or (side[i] < 0 and c < px[i]):
                kill(i, te, "break")
            else:
                keep.append(i)
        alive = keep
        # (3) swings confirmed by the 1h bar closing now
        hi = hend.get(te)
        if hi is not None and hi in conf:
            for p in conf[hi]:
                ps = int(H["day"][p])
                if ps < d - age:
                    continue
                for flag, s, lv in ((hs[p], 1, H["h"][p]), (ls[p], -1, H["l"][p])):
                    if flag:
                        side.append(s), px.append(float(lv)), pp.append(p), sess.append(ps), born.append(te)
                        died.append(INF), why.append("")
                        alive.append(len(side) - 1)
    L = Levels(np.array(side, np.int64), np.array(px, float), np.array(pp, np.int64), np.array(sess, np.int64),
               np.array(born, np.int64), np.array(died, np.int64), why)
    return L, sweeps, ambiguous


# ------------------------------------------------------------------------------------------------ the trade
def underlying_exit(mo, mh, ml, mc, t: int, side: int, stop: float, target: float, sq: int):
    """R89 / R90 rule: on the fill minute only its close counts, later each minute's low / high, the stop first; else
    the session end (exit at that minute's open)."""
    x = mc[t]
    if (side > 0 and x <= stop) or (side < 0 and x >= stop):
        return t, R_STOP
    if (side > 0 and x >= target) or (side < 0 and x <= target):
        return t, R_TARGET
    for m in range(t + 1, sq):
        if (side > 0 and ml[m] <= stop) or (side < 0 and mh[m] >= stop):
            return m, R_STOP
        if (side > 0 and mh[m] >= target) or (side < 0 and ml[m] <= target):
            return m, R_TARGET
    return sq, R_TIME


def strong(B5: dict, A: np.ndarray, j: int, side: int) -> bool:
    """Bearish (side -1) / bullish (+1) 5m bar, body >= 60% of the range, range >= 1.0 x ATR(14) at the previous bar."""
    o, h, l, c = B5["o"][j], B5["h"][j], B5["l"][j], B5["c"][j]
    rng = h - l
    if j < 1 or not np.isfinite(A[j - 1]) or not rng > 0:
        return False
    if (side < 0 and not c < o) or (side > 0 and not c > o):
        return False
    return abs(c - o) >= K.STRONG_BODY * rng - EPS and rng >= K.STRONG_ATR * A[j - 1] - EPS


def target_of(v: K.Variant, Lv: Levels, S: Series, d: int, t: int, side: int, E: float, risk: float) -> float:
    if v.target == "2R":
        return E + side * 2.0 * risk
    if v.target == "PD":
        x = S.pdl[d] if side < 0 else S.pdh[d]
        return float(x) if np.isfinite(x) and side * (x - E) > 0 else np.nan
    ok = Lv.alive_at(t) & (Lv.side == (-1 if side < 0 else 1))
    pxs = Lv.px[ok]
    pxs = pxs[side * (pxs - E) > 0]
    if not len(pxs):
        return np.nan
    return float(pxs.max() if side < 0 else pxs.min())


def run(S: Series, v: K.Variant, Lv: Levels, sweeps: list, B5: dict, A5: np.ndarray, tick: float, sq: np.ndarray,
        first: int, last: int, day_ok: np.ndarray) -> tuple:
    """(trades, funnel). sq[d]: global minute of session d's flat time (exit at its open). day_ok[d]: trades allowed.
    Each trade: day, t (fill minute), side, entry, fill, stop, target, risk, reward, xm, why, pts, plus the sweep's
    level L, extreme X, sweep minute ts and strong-candle minute tc."""
    mo, mh, ml, mc, mt = S.mo, S.mh, S.ml, S.mc, S.mt
    F = dict(sweeps=0, cancel_pre=0, no_candle=0, busy=0, cap=0, no_target=0, rr=0, order_stop=0, order_expired=0,
             filled=0)
    out, busy, per_day = [], -1, {}
    nb = len(B5["o"])
    for sw in sweeps:
        d, side, X = int(sw["day"]), int(sw["side"]), float(sw["X"])
        if not day_ok[d]:
            continue
        F["sweeps"] += 1
        stop = X + tick if side < 0 else X - tick
        j0 = int(np.searchsorted(B5["s"], sw["t"], side="right"))
        jc, cancelled = -1, False
        for j in range(j0, min(j0 + K.CANDLE_BARS, nb)):
            if int(B5["day"][j]) != d:
                break
            if (side < 0 and B5["h"][j] >= stop - EPS) or (side > 0 and B5["l"][j] <= stop + EPS):
                cancelled = True
                break
            if strong(B5, A5, j, side):
                jc = j
                break
        if cancelled:
            F["cancel_pre"] += 1
            continue
        if jc < 0:
            F["no_candle"] += 1
            continue
        tc = int(B5["e"][jc])
        if tc <= busy:
            F["busy"] += 1
            continue
        if per_day.get(d, 0) >= K.MAX_PER_DAY:
            F["cap"] += 1
            continue
        E = float(B5["l"][jc] if side < 0 else B5["h"][jc])
        risk = side * (E - stop)
        T = target_of(v, Lv, S, d, tc, side, E, risk)
        if not np.isfinite(T):
            F["no_target"] += 1
            continue
        reward = side * (T - E)
        if not risk > 0 or reward / risk < v.minrr - 1e-9:
            F["rr"] += 1
            continue
        jl = jc
        while jl + 1 < nb and jl + 1 <= jc + K.ORDER_BARS and int(B5["day"][jl + 1]) == d:
            jl += 1
        t, how = -1, "order_expired"
        for m in range(tc + 1, int(B5["e"][jl]) + 1):
            if mt[m] > last or m >= sq[d]:
                break
            if mt[m] >= first and ((side < 0 and ml[m] <= E) or (side > 0 and mh[m] >= E)):
                t = m
                break
            if (side < 0 and mh[m] >= stop) or (side > 0 and ml[m] <= stop):
                how = "order_stop"
                break
        if t < 0:
            F[how] += 1
            continue
        fill = min(E, mo[t]) if side < 0 else max(E, mo[t])
        xm, why = underlying_exit(mo, mh, ml, mc, t, side, stop, T, int(sq[d]))
        if why == R_STOP:
            px = mc[t] if xm == t else (max(stop, mo[xm]) if side < 0 else min(stop, mo[xm]))
        elif why == R_TARGET:
            px = mc[t] if xm == t else T
        else:
            px = mo[xm]
        busy = xm
        per_day[d] = per_day.get(d, 0) + 1
        F["filled"] += 1
        out.append(dict(day=d, t=int(t), side=side, entry=E, fill=float(fill), stop=float(stop), target=float(T),
                        risk=float(risk), reward=float(reward), xm=int(xm), why=WHY[why], pts=float(side * (px - fill)),
                        L=float(sw["L"]), X=X, ts=int(sw["t"]), tc=tc))
    return out, F
