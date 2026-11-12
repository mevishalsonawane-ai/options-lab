"""R163 features F1 - F6 (PREREG section 4): pure functions of arrays, each with an explicit look-ahead wall.

Every function receives the decision row `dr` (= er - 1, the close of the signal's 5-minute bar) and reads nothing from
rows after it. Directional features are side-signed (+ = in the trade's favour). Missing data gives NaN, never an error.
"""
from __future__ import annotations

import math

import numpy as np

NROWS = 375
F1 = ["ret15", "ret30", "ret60", "rv30_rel", "sma20d", "sma5d", "ema15_slope", "ema15_dist", "dist_extreme",
      "prev_ret", "prev_range"]
F2 = ["b_open_move", "b_ret15", "b_agree15", "b_disp"]
F3 = ["vix_chg30", "vix_pct250"]
F4 = ["pcr_log", "pcr_chg15", "own_oi_chg15", "oth_oi_chg15", "writers0920", "atm_iv", "skew", "iv_chg0920", "strad_pct",
      "k_oi_chg15", "k_oi_chg0920", "k_vol5", "k_spread", "k_prem_pct"]
F5 = ["sr_n_ahead05", "sr_n_behind05"]
F6 = ["to_expiry", "since_prev", "gap_filled"]
NEW = F1 + F2 + F3 + F4 + F5 + F6
ROW_0920 = 5


def _last(v: np.ndarray) -> float:
    ok = np.flatnonzero(np.isfinite(v))
    return float(v[ok[-1]]) if ok.size else np.nan


def _ret(a: float, b: float) -> float:
    return a / b - 1.0 if np.isfinite(a) and np.isfinite(b) and b > 0 else np.nan


def walled(arr: np.ndarray, dr: int, axis: int = -1) -> np.ndarray:
    """A copy with every row after dr set to NaN (the wall)."""
    a = np.array(arr, dtype=float, copy=True)
    sl = [slice(None)] * a.ndim
    sl[axis] = slice(dr + 1, None)
    a[tuple(sl)] = np.nan
    return a


# ------------------------------------------------------------------------------------------------ F1 index trend / vol
def f1(today: np.ndarray, hist: list, dr: int, s: float) -> dict:
    """today: (4, 375) O H L C of the session; hist: earlier sessions' (4, 375) arrays, oldest first (>= 20 wanted)."""
    t = walled(today, dr)
    c_today = t[3, : dr + 1]
    prev_c = np.concatenate([h[3] for h in hist[-1:]]) if hist else np.zeros(0)
    seq = np.concatenate([prev_c, c_today])
    seq = seq[np.isfinite(seq)]
    out = {k: np.nan for k in F1}
    if not seq.size:
        return out
    price = float(seq[-1])
    for n, k in ((15, "ret15"), (30, "ret30"), (60, "ret60")):
        if seq.size > n:
            out[k] = s * _ret(price, float(seq[-1 - n]))
    if seq.size > 30:
        lr = np.diff(np.log(seq[-31:]))
        day_sd = []
        for h in hist[-20:]:
            c = h[3][np.isfinite(h[3])]
            if c.size > 30:
                day_sd.append(float(np.std(np.diff(np.log(c)))))
        if day_sd and np.mean(day_sd) > 0:
            out["rv30_rel"] = float(np.std(lr)) / float(np.mean(day_sd))
    closes = [_last(h[3]) for h in hist]
    closes = [c for c in closes if np.isfinite(c)]
    if len(closes) >= 20:
        out["sma20d"] = s * _ret(price, float(np.mean(closes[-20:])))
    if len(closes) >= 5:
        out["sma5d"] = s * _ret(price, float(np.mean(closes[-5:])))
    # 15-minute closes of the last sessions + today (closed bars only), EMA20
    b15 = []
    for h in hist[-8:] + [t]:
        c = h[3]
        for j in range(0, NROWS, 15):
            seg = c[j: j + 15]
            if h is t and j + 15 - 1 > dr:
                break
            v = _last(seg)
            if np.isfinite(v):
                b15.append(v)
    if len(b15) >= 25:
        e = _ema(np.array(b15), 20)
        out["ema15_slope"] = s * (e[-1] - e[-4]) / price
        out["ema15_dist"] = s * (b15[-1] - e[-1]) / price
    hi, lo = np.nanmax(t[1]) if np.isfinite(t[1]).any() else np.nan, np.nanmin(t[2]) if np.isfinite(t[2]).any() else np.nan
    out["dist_extreme"] = (hi / price - 1) if s > 0 else (1 - lo / price)
    if len(hist) >= 2:
        pc, ppc = _last(hist[-1][3]), _last(hist[-2][3])
        out["prev_ret"] = s * _ret(pc, ppc)
        h1 = hist[-1]
        if np.isfinite(h1[1]).any() and np.isfinite(pc) and pc > 0:
            out["prev_range"] = (np.nanmax(h1[1]) - np.nanmin(h1[2])) / pc
    return out


def _ema(x: np.ndarray, n: int) -> np.ndarray:
    a = 2.0 / (n + 1)
    e = np.empty_like(x, dtype=float)
    e[0] = x[0]
    for i in range(1, len(x)):
        e[i] = a * x[i] + (1 - a) * e[i - 1]
    return e


# ------------------------------------------------------------------------------------------------ F2 breadth
def f2(sessions: list, dr: int, s: float) -> dict:
    """sessions: per index (today (4, 375), previous close) for the indices that traded today."""
    om, r15 = [], []
    for today, pc in sessions:
        c = walled(today, dr)[3]
        now = _last(c)
        if np.isfinite(pc) and pc > 0 and np.isfinite(now):
            om.append(s * _ret(now, pc))
        back = _last(c[: max(dr - 15, 0) + 1]) if dr >= 15 else np.nan
        if np.isfinite(back):
            r15.append(s * _ret(now, back))
    return {"b_open_move": float(np.mean(om)) if om else np.nan, "b_ret15": float(np.mean(r15)) if r15 else np.nan,
            "b_agree15": float(np.mean([v > 0 for v in r15])) if r15 else np.nan,
            "b_disp": float(np.std(om)) if len(om) >= 2 else np.nan}


# ------------------------------------------------------------------------------------------------ F3 VIX
def f3(vix_today: np.ndarray | None, prev_closes: list, dr: int) -> dict:
    """vix_today: (375, 4) O H L C; prev_closes: the previous sessions' VIX closes, oldest first."""
    out = {"vix_chg30": np.nan, "vix_pct250": np.nan}
    if vix_today is None:
        return out
    c = walled(vix_today[:, 3], dr)
    now = _last(c)
    if dr >= 30:
        out["vix_chg30"] = _ret(now, _last(c[: dr - 30 + 1]))
    pcs = [v for v in prev_closes[-250:] if np.isfinite(v)]
    if len(pcs) >= 60 and np.isfinite(now):
        out["vix_pct250"] = float(np.mean(np.array(pcs) < now))
    return out


# ------------------------------------------------------------------------------------------------ F4 option chain
def ffill_col(x: np.ndarray) -> np.ndarray:
    """Forward-fill along axis 0 (rows) within the session."""
    out = np.array(x, dtype=float, copy=True)
    for i in range(1, out.shape[0]):
        m = ~np.isfinite(out[i])
        out[i][m] = out[i - 1][m]
    return out


def f4(grid: dict | None, k_series: dict | None, dr: int, side: str, spot: float, lot: int) -> dict:
    """grid: {'oi','iv','close','volume'}: (375, 21, 2) arrays by row, offset -10..+10, side (C, P) of the session;
    k_series: the bought contract's {'oi','volume','high','low','close'} (375,) arrays. Rows after dr are walled."""
    out = {k: np.nan for k in F4}
    if grid is None or not np.isfinite(spot) or spot <= 0:
        return out
    own, oth = (0, 1) if side == "C" else (1, 0)
    s = 1.0 if side == "C" else -1.0
    g = {k: ffill_col(walled(v, dr, axis=0)[: dr + 1]) for k, v in grid.items() if k != "volume"}
    oi = g["oi"]
    with np.errstate(all="ignore"):
        now = oi[-1, 5:16]
        sc, sp = np.nansum(now[:, 0]), np.nansum(now[:, 1])
        if sc > 0 and sp > 0:
            out["pcr_log"] = s * math.log(sp / sc)
            if dr >= 15:
                b = oi[dr - 15, 5:16]
                bc, bp = np.nansum(b[:, 0]), np.nansum(b[:, 1])
                if bc > 0 and bp > 0:
                    out["pcr_chg15"] = s * (sp / sc - bp / bc)
                    chg = (sc / bc - 1) * 100, (sp / bp - 1) * 100
                    out["own_oi_chg15"], out["oth_oi_chg15"] = chg[own], chg[oth]
        if dr >= ROW_0920:
            a, b = oi[-1, 8:13], oi[ROW_0920, 8:13]
            add = np.nansum(a, axis=0) - np.nansum(b, axis=0)
            base = np.nansum(b)
            if base > 0:
                out["writers0920"] = (add[oth] - add[own]) / base * 100
        iv = g["iv"]
        ivn = iv[-1, 10]
        ivn = ivn[np.isfinite(ivn) & (ivn > 0)]
        if ivn.size:
            out["atm_iv"] = float(np.mean(ivn))
            if dr >= ROW_0920:
                iv0 = iv[ROW_0920, 10]
                iv0 = iv0[np.isfinite(iv0) & (iv0 > 0)]
                if iv0.size:
                    out["iv_chg0920"] = out["atm_iv"] - float(np.mean(iv0))
        pi, ci = iv[-1, 7, 1], iv[-1, 13, 0]
        if np.isfinite(pi) and np.isfinite(ci) and pi > 0 and ci > 0:
            out["skew"] = float(pi - ci)
        cc, pc = g["close"][-1, 10, 0], g["close"][-1, 10, 1]
        if np.isfinite(cc) and np.isfinite(pc):
            out["strad_pct"] = (cc + pc) / spot * 100
    if k_series is not None:
        ko = ffill_col(walled(k_series["oi"], dr)[: dr + 1][:, None])[:, 0]
        now = ko[-1]
        if np.isfinite(now):
            if dr >= 15 and np.isfinite(ko[dr - 15]) and ko[dr - 15] > 0:
                out["k_oi_chg15"] = (now / ko[dr - 15] - 1) * 100
            if dr >= ROW_0920 and np.isfinite(ko[ROW_0920]) and ko[ROW_0920] > 0:
                out["k_oi_chg0920"] = (now / ko[ROW_0920] - 1) * 100
        v = walled(k_series["volume"], dr)[max(dr - 4, 0): dr + 1]
        if np.isfinite(v).any():
            out["k_vol5"] = math.log1p(max(float(np.nansum(v)), 0.0) / max(lot, 1))
        h = walled(k_series["high"], dr)[max(dr - 4, 0): dr + 1]
        l_ = walled(k_series["low"], dr)[max(dr - 4, 0): dr + 1]
        c = walled(k_series["close"], dr)[max(dr - 4, 0): dr + 1]
        ok = np.isfinite(h) & np.isfinite(l_) & np.isfinite(c) & (c > 0)
        if ok.any():
            out["k_spread"] = float(np.mean((h[ok] - l_[ok]) / c[ok]))
        last = _last(walled(k_series["close"], dr)[: dr + 1])
        if np.isfinite(last):
            out["k_prem_pct"] = last / spot * 100
    return out


# ------------------------------------------------------------------------------------------------ F5 / F6
def f5(levels: list, price: float, s: float) -> dict:
    """levels: R150's [(level, turns)] known at the decision."""
    if not np.isfinite(price) or price <= 0:
        return {"sr_n_ahead05": np.nan, "sr_n_behind05": np.nan}
    ah = sum(1 for l_, n in levels if n >= 3 and s * (l_ - price) > 0 and abs(l_ / price - 1) <= 0.005)
    be = sum(1 for l_, n in levels if n >= 3 and s * (l_ - price) <= 0 and abs(l_ / price - 1) <= 0.005)
    return {"sr_n_ahead05": float(ah), "sr_n_behind05": float(be)}


def f6(to_expiry: int | None, prev_er: int | None, er: int, today: np.ndarray, prev_close: float, dr: int) -> dict:
    t = walled(today, dr)
    o0 = _last(t[0][:5])
    filled = np.nan
    if np.isfinite(o0) and np.isfinite(prev_close):
        if o0 >= prev_close:
            filled = float(np.isfinite(t[2]).any() and np.nanmin(t[2]) <= prev_close)
        else:
            filled = float(np.isfinite(t[1]).any() and np.nanmax(t[1]) >= prev_close)
    return {"to_expiry": float(to_expiry) if to_expiry is not None else np.nan,
            "since_prev": float(er - prev_er) if prev_er is not None else 999.0, "gap_filled": filled}
