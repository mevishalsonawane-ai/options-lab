"""R155 features and filters (PREREG section 2): every number is read at the decision minute s (entry row - 1) from index
bars and OI rows at or before s.

One `Feat` per index holds every session's index bars (oldest first, warm-up days included) as continuous 5- and
15-minute bars (R150's `bars15`), their pivots (R150's `pivots`, known only K bars later), the 5-minute EMA20 and
Supertrend (10, 3) (R73's `supertrend_dir`), and the index's OI store. `decide(i, s, side, strike)` returns the numbers
and each filter's pass / fail. A filter that cannot be read fails (the signal is skipped).
"""
from __future__ import annotations

import numpy as np

import r155cfg as K
import levels as V                  # hunt/r150_sr_levels/levels.py
from r73rules import supertrend_dir

NROWS = 375
NUM = ("w5_a", "w5_b", "w20_a", "w20_b", "w50_a", "w50_b", "w3m_a", "w3m_b", "piv_a", "piv_b", "b20", "ema_d", "st",
       "twap_d", "oib", "dpcr", "opt_dc", "opt_doi", "ag5", "for5", "cloc5", "body5", "ag1", "for1", "cloc1", "tod", "call")


# ------------------------------------------------------------------------------------------------ pure helpers
def ema(x: np.ndarray, n: int) -> np.ndarray:
    out = np.full(len(x), np.nan)
    a, prev = 2.0 / (n + 1.0), np.nan
    for i, v in enumerate(x):
        if np.isfinite(v):
            prev = v if not np.isfinite(prev) else prev + a * (v - prev)
        out[i] = prev
    return out


def ahead_behind(levels: list, px: float, side: str, min_touches: int = K.TOUCHES) -> tuple:
    """(fraction to the nearest level ahead, fraction to the nearest behind) among levels with >= min_touches turns;
    NaN when none. Ahead = above a call / below a put."""
    up = [lv / px - 1 for lv, n in levels if n >= min_touches and lv > px]
    dn = [1 - lv / px for lv, n in levels if n >= min_touches and lv < px]
    u = min(up) if up else np.nan
    d_ = min(dn) if dn else np.nan
    return (u, d_) if side == "C" else (d_, u)


def wall(ahead: float) -> bool:
    return bool(np.isfinite(ahead) and ahead <= K.WALL)


def floor_pivots(prev_idx: np.ndarray) -> list:
    """The previous session's P, R1, S1, R2, S2 as levels (each counted as 3 turns so the same rule reads them)."""
    if prev_idx is None or not np.isfinite(prev_idx[3]).any():
        return []
    h, lo = float(np.nanmax(prev_idx[1])), float(np.nanmin(prev_idx[2]))
    c = float(prev_idx[3][np.flatnonzero(np.isfinite(prev_idx[3]))[-1]])
    p = (h + lo + c) / 3.0
    return [(x, K.TOUCHES) for x in (p, 2 * p - lo, 2 * p - h, p + (h - lo), p - (h - lo))]


def candle(o, h, lo, c, side: str) -> tuple:
    """(wick against, wick for, close location from the trade's side, body share signed by the trade's colour), all as
    shares of the bar's range; NaN when the bar is missing. A flat bar: 0, 0, 0.5, 0."""
    if not all(np.isfinite(v) for v in (o, h, lo, c)):
        return np.nan, np.nan, np.nan, np.nan
    rng = h - lo
    if rng <= 0:
        return 0.0, 0.0, 0.5, 0.0
    up_w, dn_w = h - max(o, c), min(o, c) - lo
    body = abs(c - o) / rng
    if side == "C":
        return up_w / rng, dn_w / rng, (c - lo) / rng, body if c > o else -body
    return dn_w / rng, up_w / rng, (h - c) / rng, body if c < o else -body


def last_at(a: np.ndarray, s: int, stale: int = K.OI_STALE) -> float:
    """The last finite value of a in rows (s - stale, s]; NaN if none."""
    if s < 0:
        return np.nan
    seg = a[max(0, s - stale + 1): s + 1]
    f = np.flatnonzero(np.isfinite(seg))
    return float(seg[f[-1]]) if len(f) else np.nan


def last_close(idx: np.ndarray, s: int) -> float:
    if s < 0:
        return np.nan
    f = np.flatnonzero(np.isfinite(idx[3, :s + 1]))
    return float(idx[3, f[-1]]) if len(f) else np.nan


def oi_build(con: dict, atm: float, step: float, s: int, look: int = K.OI_LOOK) -> float:
    """R125's OI build-up score over `look` minutes on the strikes ATM +-3: +1 a call with premium up and OI up or a put
    with premium down and OI up; -1 a call with premium down and OI up or a put with premium up and OI up; 0 otherwise;
    averaged over the contracts readable at both ends. NaN when none is."""
    tot, n = 0.0, 0
    for k in range(-K.OIB_RANGE, K.OIB_RANGE + 1):
        for side in ("C", "P"):
            a = con.get((side, float(atm + k * step)))
            if a is None:
                continue
            c1, c0 = last_at(a[0], s), last_at(a[0], s - look)
            o1, o0 = last_at(a[1], s), last_at(a[1], s - look)
            if not all(np.isfinite(v) for v in (c1, c0, o1, o0)):
                continue
            n += 1
            if o1 > o0:
                if (side == "C" and c1 > c0) or (side == "P" and c1 < c0):
                    tot += 1
                elif (side == "C" and c1 < c0) or (side == "P" and c1 > c0):
                    tot -= 1
    return tot / n if n else np.nan


# ------------------------------------------------------------------------------------------------ per index
class Feat:
    def __init__(self, und: str, days: list, oi: dict | None = None, step: float | None = None):
        self.und = und
        self.days = days
        self.pos = {d: i for i, (d, _) in enumerate(days)}
        self.oi = oi or {}
        if step is None:
            import r51cfg
            step = r51cfg.STEP[und]
        self.step = step
        arrs = [D.idx for _, D in days]
        self.b5 = V.bars15(arrs, minutes=5)
        self.b15 = V.bars15(arrs, minutes=15)
        self.p5 = self._piv(self.b5, K.K5)
        self.p15 = self._piv(self.b15, K.K15)
        self.ema5 = ema(self.b5["c"], K.EMA_N)
        self.st5 = supertrend_dir(self.b5["h"], self.b5["l"], self.b5["c"], K.ST_N, K.ST_MULT)
        self.first5 = np.searchsorted(self.b5["day"], np.arange(len(days) + 1))
        self.first15 = np.searchsorted(self.b15["day"], np.arange(len(days) + 1))
        self._lv: dict = {}
        self._tw: dict = {}

    @staticmethod
    def _piv(b: dict, k: int) -> tuple:
        pv = V.pivots(b["h"], b["l"], k)
        bar = np.array([p[0] for p in pv], np.int64)
        price = np.array([p[1] for p in pv], float)
        o = np.argsort(bar, kind="stable")
        return bar[o], price[o], k

    def last_bar(self, b: dict, first: np.ndarray, i: int, s: int) -> int:
        """Global index of the last bar closed by minute s of session i (its end row <= s; earlier sessions' bars count)."""
        a, z = first[i], first[i + 1]
        return int(a + np.searchsorted(b["end"][a:z], s, side="right") - 1)

    def levels(self, kind: str, window: int, i: int, s: int) -> list:
        b, first, (pbar, pprice, k) = (self.b5, self.first5, self.p5) if kind == "5" else (self.b15, self.first15, self.p15)
        last = self.last_bar(b, first, i, s)
        lo_bar = int(first[max(0, i - window + 1)])
        key = (kind, window, last, lo_bar)
        if key not in self._lv:
            if last < 0:
                self._lv[key] = []
            else:
                a = np.searchsorted(pbar, lo_bar, "left")
                z = np.searchsorted(pbar, last - k, "right")
                self._lv[key] = V.group(list(pprice[a:z])) if z > a else []
        return self._lv[key]

    def twap(self, i: int) -> np.ndarray:
        if i not in self._tw:
            D = self.days[i][1]
            tp = (D.idx[1] + D.idx[2] + D.idx[3]) / 3.0
            ok = np.isfinite(tp)
            cn = np.cumsum(ok)
            with np.errstate(all="ignore"):
                self._tw[i] = np.where(cn > 0, np.cumsum(np.where(ok, tp, 0.0)) / np.maximum(cn, 1), np.nan)
        return self._tw[i]

    def decide(self, d, s: int, side: str, strike: float | None) -> tuple:
        """(numbers dict, {filter: bool}) for a signal decided at minute s of session d."""
        i = self.pos[d]
        D = self.days[i][1]
        idx = D.idx
        px = last_close(idx, s)
        x = {}
        lv = {}
        for name, w in K.WINDOWS5.items():
            lv[name] = self.levels("5", w, i, s)
        lv["W3M"] = self.levels("15", K.WINDOW15, i, s)
        lv["PIV"] = floor_pivots(self.days[i - 1][1].idx if i > 0 else None)
        for name, key in (("W5", "w5"), ("W20", "w20"), ("W50", "w50"), ("W3M", "w3m"), ("PIV", "piv")):
            a, b_ = ahead_behind(lv[name], px, side) if np.isfinite(px) else (np.nan, np.nan)
            x[key + "_a"], x[key + "_b"] = a, b_
        prev = last_close(idx, s - K.B_LOOK)
        x["b20"] = float(V.crossed(lv["W20"], prev, px, side)) if np.isfinite(prev) and np.isfinite(px) else np.nan
        k = self.last_bar(self.b5, self.first5, i, s)
        sgn = 1.0 if side == "C" else -1.0
        if k >= 0 and np.isfinite(self.ema5[k]):
            x["ema_d"] = sgn * (self.b5["c"][k] / self.ema5[k] - 1)
            x["st"] = float(sgn * self.st5[k]) if self.st5[k] != 0 else np.nan
            ag5, fo5, cl5, bo5 = candle(self.b5["o"][k], self.b5["h"][k], self.b5["l"][k], self.b5["c"][k], side)
        else:
            x["ema_d"], x["st"] = np.nan, np.nan
            ag5 = fo5 = cl5 = bo5 = np.nan
        tw = self.twap(i)[s] if s >= 0 else np.nan
        x["twap_d"] = sgn * (px / tw - 1) if np.isfinite(px) and np.isfinite(tw) else np.nan
        # OI (option chain context)
        od = self.oi.get(d)
        x["oib"] = x["dpcr"] = x["opt_dc"] = x["opt_doi"] = np.nan
        if od is not None:
            atm = D.atm[s] if D.atm is not None and np.isfinite(D.atm[s]) else (round(px / self.step) * self.step
                                                                              if np.isfinite(px) else np.nan)
            if np.isfinite(atm):
                v = oi_build(od["con"], atm, self.step, s)
                x["oib"] = sgn * v if np.isfinite(v) else np.nan
            p1, p0 = last_at(od["pcr"], s), last_at(od["pcr"], s - K.OI_LOOK)
            if np.isfinite(p1) and np.isfinite(p0):
                x["dpcr"] = sgn * (p1 - p0)
            a = od["con"].get((side, float(strike))) if strike is not None else None
            if a is not None:
                c1, c0 = last_at(a[0], s), last_at(a[0], s - K.OI_LOOK)
                o1, o0 = last_at(a[1], s), last_at(a[1], s - K.OI_LOOK)
                if all(np.isfinite(v) and v > 0 for v in (c0, o0)) and np.isfinite(c1) and np.isfinite(o1):
                    x["opt_dc"], x["opt_doi"] = c1 / c0 - 1, o1 / o0 - 1
        x["ag5"], x["for5"], x["cloc5"], x["body5"] = ag5, fo5, cl5, bo5
        o1_, h1_, l1_, c1_ = (idx[j, s] for j in range(4)) if s >= 0 else (np.nan,) * 4
        x["ag1"], x["for1"], x["cloc1"], _ = candle(o1_, h1_, l1_, c1_, side)
        x["tod"] = s / NROWS
        x["call"] = 1.0 if side == "C" else 0.0
        x["px"] = px
        return x, filters(x)


def filters(x: dict) -> dict:
    """Each filter's pass / fail from the numbers (PREREG section 2); unreadable = fail."""
    def ok(v):
        return v is not None and np.isfinite(v)

    def nowall(a, readable):
        return bool(readable and not wall(a))
    rd = ok(x.get("px"))                                  # no level at all is readable (no wall); no price is not
    f = {
        "F1": nowall(x["w5_a"], rd), "F2": nowall(x["w20_a"], rd), "F3": nowall(x["w50_a"], rd),
        "F4": nowall(x["w3m_a"], rd), "F5": nowall(x["piv_a"], rd),
        "F7": bool(ok(x["b20"]) and x["b20"] > 0),
        "F8": bool(ok(x["ema_d"]) and x["ema_d"] >= 0),
        "F9": bool(ok(x["st"]) and x["st"] > 0),
        "F10": bool(ok(x["twap_d"]) and x["twap_d"] >= 0),
        "F11": bool(ok(x["oib"]) and x["oib"] > 0),
        "F12": bool(ok(x["dpcr"]) and x["dpcr"] > 0),
        "F13": bool(ok(x["opt_dc"]) and ok(x["opt_doi"]) and not (x["opt_dc"] < 0 and x["opt_doi"] < 0)),
        "F14": bool(ok(x["ag5"]) and x["ag5"] < K.WICK),
        "F15": bool(ok(x["ag1"]) and x["ag1"] < K.WICK),
        "F16": bool(ok(x["for5"]) and x["for5"] >= K.WICK),
        "F17": bool(ok(x["cloc5"]) and x["cloc5"] >= 2.0 / 3.0),
        "F18": bool(ok(x["body5"]) and x["body5"] >= K.BODY),
    }
    f["F6"] = f["F2"] and f["F3"] and f["F4"] and f["F5"]
    f["F19"] = sum(f[k] for k in K.STACK_OF) >= K.STACK_MIN
    return f


def vector(x: dict) -> np.ndarray:
    return np.array([x[k] for k in NUM], float)
