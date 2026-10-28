"""R125 inputs (PREREG section 2): wick support/resistance (SR), liquidity (LIQ), order flow (FLOW), volume and the
volume profile (VOL), for R124's decision rows. Every input at row t reads bars <= t (pivots only once confirmed,
sweeps only once their failure bars closed, participant files from the next session).

    add_features(part) -> <R125_OUT>/feat_<part>.parquet (keyed und, day, row)
"""
from __future__ import annotations

import math
import os
import sys
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
for p in (str(HERE.parent / "r124_big_moves"), str(HERE.parent / "r123_neural_70"), str(HERE.parent / "of1"),
          str(HERE)):
    if p not in sys.path:
        sys.path.insert(0, p)

import r124cfg as K  # noqa: E402
import r123data as D3  # noqa: E402
import r124zig as Z  # noqa: E402

OUT = Path(os.environ.get("R125_OUT", K.K3.CACHE_ROOT / "r125")).expanduser()
OF1_RAW = Path(os.environ.get("OF1_RAW", K.K3.CACHE_ROOT / "of1" / "raw")).expanduser()
PIV_K = 5
PREV_DAYS = 5
SWEEP_LOOK, SWEEP_FAIL = 15, 3
ATM3 = slice(7, 14)                                   # offsets -3 .. +3 in the chain arrays (index 10 = ATM)
BIN = {"NIFTY": 5.0, "BANKNIFTY": 10.0, "FINNIFTY": 5.0, "MIDCPNIFTY": 2.5, "SENSEX": 15.0}   # ~ m/6 points

SR_COLS = ("sr_res_dist", "sr_sup_dist", "sr_res_str", "sr_sup_str", "sr_n_above", "sr_n_below", "sr_d5hi", "sr_d5lo")
LIQ_COLS = ("lq_pool_up", "lq_pool_dn", "lq_sweep_hi", "lq_sweep_lo", "lq_since_sweep", "lq_poke", "lq_hilo_net")
FLOW_COLS = ("fl_fii_ls", "fl_fii_ls_chg", "fl_pro_stance", "fl_pro_stance_chg", "fl_cli_stance", "fl_cp5", "fl_cp15",
             "fl_buildup")
VOL_COLS = ("vo_v5", "vo_v15", "vo_v60", "vo_poc", "vo_vah", "vo_val", "vo_in_va", "vo_node")
VWMA_COLS = ("vw_dist", "vw_slope", "vw_band", "ma9_dist", "ma20_dist", "ma50_dist", "ma_align", "ma20_slope",
             "ma_cross", "ma5m20_dist")
CANDLE_TF = (1, 5, 15)
CANDLE_KEYS = ("inside", "outside", "trend", "run", "rev2", "swing3", "nr7", "wide")
CANDLE_COLS = tuple(f"c{tf}_{k}" for tf in CANDLE_TF for k in CANDLE_KEYS)
NEW_COLS = SR_COLS + LIQ_COLS + FLOW_COLS + VOL_COLS + CANDLE_COLS + VWMA_COLS
GROUPS = {"sr": SR_COLS, "liq": LIQ_COLS, "flow": FLOW_COLS, "vol": VOL_COLS, "candle": CANDLE_COLS,
          "vwap_ma": VWMA_COLS}


# ------------------------------------------------------------------------------------------------ pivots and levels
def pivots(h: np.ndarray, l: np.ndarray, k: int = PIV_K) -> tuple[list, list]:
    """Wick pivots of one session: [(bar, price, confirm_bar)] for highs and lows. A high pivot is a bar whose high is
    the highest of the k bars on each side (ties: the first); it is known at bar + k."""
    hi, lo = [], []
    n = len(h)
    for i in range(k, n - k):
        wh, wl = h[i - k:i + k + 1], l[i - k:i + k + 1]
        if np.isfinite(h[i]) and np.isfinite(wh).all() and h[i] == wh.max() and int(np.argmax(wh)) == k:
            hi.append((i, float(h[i]), i + k))
        if np.isfinite(l[i]) and np.isfinite(wl).all() and l[i] == wl.min() and int(np.argmin(wl)) == k:
            lo.append((i, float(l[i]), i + k))
    return hi, lo


def cluster(prices: list[float], gap: float) -> list[tuple[float, int]]:
    """Sorted prices within `gap` of their neighbour form one level: (mean price, count)."""
    if not prices:
        return []
    ps = sorted(prices)
    out, cur = [], [ps[0]]
    for p in ps[1:]:
        if p - cur[0] <= gap:                     # a level spans at most `gap` from its first price (no chaining)
            cur.append(p)
        else:
            out.append((float(np.mean(cur)), len(cur)))
            cur = [p]
    out.append((float(np.mean(cur)), len(cur)))
    return out


def sr_features(levels: list[tuple[float, int]], hi_t: float, lo_t: float, m: float, h5: float, l5: float) -> dict:
    above = [(p, c) for p, c in levels if p > hi_t]
    below = [(p, c) for p, c in levels if p < lo_t]
    ra = min(above, key=lambda x: x[0]) if above else None
    sb = max(below, key=lambda x: x[0]) if below else None
    return dict(sr_res_dist=(ra[0] - hi_t) / m if ra else np.nan, sr_sup_dist=(lo_t - sb[0]) / m if sb else np.nan,
                sr_res_str=float(ra[1]) if ra else 0.0, sr_sup_str=float(sb[1]) if sb else 0.0,
                sr_n_above=float(sum(1 for p, _ in above if p - hi_t <= m)),
                sr_n_below=float(sum(1 for p, _ in below if lo_t - p <= m)),
                sr_d5hi=(h5 - hi_t) / m if np.isfinite(h5) else np.nan,
                sr_d5lo=(lo_t - l5) / m if np.isfinite(l5) else np.nan)


def pools(piv: list[tuple[int, float, int]], gap: float, t: int, h_or_l: np.ndarray, side: str,
          prev: list[float]) -> list[float]:
    """Untaken pools at bar t: >= 2 confirmed pivots (this side; earlier sessions' prices in `prev`) within `gap`.
    A pool is taken once a bar after its last pivot's confirmation trades beyond it (highs above / lows below)."""
    known = [(i, p, c) for i, p, c in piv if c <= t]
    pts = [(-1, p, -1) for p in prev] + known
    pts.sort(key=lambda x: x[1])
    out = []
    j = 0
    while j < len(pts):
        grp = [pts[j]]
        while j + 1 < len(pts) and pts[j + 1][1] - grp[-1][1] <= gap:
            j += 1
            grp.append(pts[j])
        j += 1
        if len(grp) < 2:
            continue
        lvl = max(g[1] for g in grp) if side == "high" else min(g[1] for g in grp)
        after = max(g[2] for g in grp) + 1
        seg = h_or_l[max(after, 0):t + 1]
        taken = (np.nanmax(seg) > lvl) if side == "high" and np.isfinite(seg).any() else \
            ((np.nanmin(seg) < lvl) if side == "low" and np.isfinite(seg).any() else False)
        if not taken:
            out.append(lvl)
    return out


def sweeps(h, l, t: int, hi_levels_at, lo_levels_at, m: float) -> dict:
    """A high sweep at bar j (t-14 <= j <= t-3): h[j] above a level known at j, and bars j+1..j+3 all stay below it
    (wicks). Low sweeps mirrored. Returns the latest of each, minutes since the latest of either, its poke size."""
    out = dict(lq_sweep_hi=0.0, lq_sweep_lo=0.0, lq_since_sweep=np.nan, lq_poke=0.0)
    last_j, poke = None, 0.0
    for j in range(max(t - SWEEP_LOOK + 1, 0), t - SWEEP_FAIL + 1):
        if not (np.isfinite(h[j]) and np.isfinite(l[j])):
            continue
        for L in hi_levels_at(j):
            if h[j] > L and np.all(np.nan_to_num(h[j + 1:j + SWEEP_FAIL + 1], nan=-np.inf) < L):
                out["lq_sweep_hi"] = 1.0
                if last_j is None or j >= last_j:
                    last_j, poke = j, (h[j] - L) / m
                break
        for L in lo_levels_at(j):
            if l[j] < L and np.all(np.nan_to_num(l[j + 1:j + SWEEP_FAIL + 1], nan=np.inf) > L):
                out["lq_sweep_lo"] = 1.0
                if last_j is None or j >= last_j:
                    last_j, poke = j, (L - l[j]) / m
                break
    if last_j is not None:
        out["lq_since_sweep"] = float(t - last_j)
        out["lq_poke"] = float(poke)
    return out


def hilo_net(h, l, t: int, look: int = 30) -> float:
    """New session highs minus new session lows among the last `look` bars (wicks)."""
    n = 0
    with np.errstate(all="ignore"):
        for j in range(max(t - look + 1, 1), t + 1):
            if np.isfinite(h[j]) and h[j] > np.nanmax(h[:j]):
                n += 1
            if np.isfinite(l[j]) and l[j] < np.nanmin(l[:j]):
                n -= 1
    return float(n)


# ------------------------------------------------------------------------------------------------ candle patterns (wicks)
def candles(h: np.ndarray, l: np.ndarray, t: int, tf: int, n: int = 12) -> tuple[np.ndarray, np.ndarray]:
    """The last n COMPLETED tf-minute candles at bar t (aligned to 09:15), oldest first: highs and lows only."""
    last = (t + 1) // tf - 1
    H, L = [], []
    for k in range(last - n + 1, last + 1):
        if k < 0:
            H.append(np.nan)
            L.append(np.nan)
            continue
        a, b = k * tf, k * tf + tf
        with np.errstate(all="ignore"):
            H.append(np.nanmax(h[a:b]) if np.isfinite(h[a:b]).any() else np.nan)
            L.append(np.nanmin(l[a:b]) if np.isfinite(l[a:b]).any() else np.nan)
    return np.array(H), np.array(L)


def candle_features(h: np.ndarray, l: np.ndarray, t: int, tf: int) -> dict:
    """Wick-only patterns of the last completed tf-minute candles (amendment 2): inside / outside bar; higher-high-and-
    higher-low (+1) or lower-high-and-lower-low (-1) and its run (capped +-3); the two-candle wick reversal (a new low,
    then the next candle takes the previous high: +1; mirrored -1); the three-candle swing (the middle candle's low is
    the lowest: +1, its high the highest: -1); NR7; the range against the previous 10 candles."""
    H, L = candles(h, l, t, tf)
    p = f"c{tf}_"
    out = {p + k: np.nan for k in CANDLE_KEYS}
    if not (np.isfinite(H[-3:]).all() and np.isfinite(L[-3:]).all()):
        return out
    h0, h1, h2, l0, l1, l2 = H[-1], H[-2], H[-3], L[-1], L[-2], L[-3]
    out[p + "inside"] = float(h0 < h1 and l0 > l1)
    out[p + "outside"] = float(h0 > h1 and l0 < l1)
    tr = 1.0 if (h0 > h1 and l0 > l1) else (-1.0 if (h0 < h1 and l0 < l1) else 0.0)
    out[p + "trend"] = tr
    run = 0
    for k in range(1, min(4, len(H))):
        a, b = len(H) - k, len(H) - k - 1
        if b < 0 or not (np.isfinite(H[a]) and np.isfinite(H[b])):
            break
        s = 1 if (H[a] > H[b] and L[a] > L[b]) else (-1 if (H[a] < H[b] and L[a] < L[b]) else 0)
        if s == 0 or (run and np.sign(run) != s):
            break
        run += s
    out[p + "run"] = float(run)
    up = l1 < l2 and h0 > h1
    dn = h1 > h2 and l0 < l1
    out[p + "rev2"] = 1.0 if up and not dn else (-1.0 if dn and not up else 0.0)
    lo_turn = l1 < l2 and l1 < l0
    hi_turn = h1 > h2 and h1 > h0
    out[p + "swing3"] = 1.0 if lo_turn and not hi_turn else (-1.0 if hi_turn and not lo_turn else 0.0)
    rng = H - L
    last7 = rng[-7:]
    out[p + "nr7"] = float(np.isfinite(last7).all() and rng[-1] <= np.min(last7))
    prev = rng[-11:-1]
    prev = prev[np.isfinite(prev)]
    out[p + "wide"] = float(rng[-1] / prev.mean()) if len(prev) and prev.mean() > 0 else np.nan
    return out


# ------------------------------------------------------------------------------------------------ VWAP and moving averages
def span_signed(lo: float, hi: float, level: float, m: float) -> float:
    """Signed distance of a bar's wick span from a level, in m: + when the whole span is above it, - below, 0 touching."""
    if not (np.isfinite(lo) and np.isfinite(hi) and np.isfinite(level)) or not m > 0:
        return np.nan
    if lo > level:
        return (lo - level) / m
    if hi < level:
        return (hi - level) / m
    return 0.0


def ema(x: np.ndarray, n: int) -> np.ndarray:
    """EMA over a series (NaNs carry the last value); each value uses only earlier and current points."""
    a = 2.0 / (n + 1)
    out = np.full(len(x), np.nan)
    e = np.nan
    for i, v in enumerate(x):
        if np.isfinite(v):
            e = v if not np.isfinite(e) else e + a * (v - e)
        out[i] = e
    return out


def session_vwap(h: np.ndarray, l: np.ndarray, v: np.ndarray, t: int) -> tuple[float, float, float]:
    """(VWAP, its value 15 minutes earlier, the volume-weighted SD) of the wick midpoints, bars 0..t, weights = the
    minute's option volume (the index has no volume before 2026)."""
    mid = (h[:t + 1] + l[:t + 1]) / 2
    w = np.where(np.isfinite(mid), v[:t + 1], 0.0)
    mid = np.nan_to_num(mid)
    cw = np.cumsum(w)
    cp = np.cumsum(w * mid)
    if not cw[-1] > 0:
        return np.nan, np.nan, np.nan
    vw = cp[-1] / cw[-1]
    prev = cp[t - 15] / cw[t - 15] if t >= 15 and cw[t - 15] > 0 else np.nan
    sd = math.sqrt(max(np.sum(w * (mid - vw) ** 2) / cw[-1], 0.0))
    return vw, prev, sd


def vwap_ma_features(h, l, v, t, m, e9, e20, e50, e20_15, cross_age, e5m20) -> dict:
    lo, hi = l[t], h[t]
    vw, vw15, sd = session_vwap(h, l, v, t)
    mid = (lo + hi) / 2
    f = dict(vw_dist=span_signed(lo, hi, vw, m), vw_slope=(vw - vw15) / m if np.isfinite(vw15) else np.nan,
             vw_band=(mid - vw) / sd if sd and sd > 0 else np.nan,
             ma9_dist=span_signed(lo, hi, e9, m), ma20_dist=span_signed(lo, hi, e20, m),
             ma50_dist=span_signed(lo, hi, e50, m),
             ma_align=1.0 if e9 > e20 > e50 else (-1.0 if e9 < e20 < e50 else 0.0),
             ma20_slope=(e20 - e20_15) / m if np.isfinite(e20_15) else np.nan, ma_cross=cross_age,
             ma5m20_dist=span_signed(lo, hi, e5m20, m))
    return f


def ma_series(I) -> dict:
    """1-minute EMAs 9/20/50 of the wick midpoints over the continuous minute series; the signed minutes since the
    last EMA9/EMA20 cross (+ after an up cross, capped at 120); and the 5-minute EMA20 of 5-minute candle midpoints,
    read at the last completed 5-minute candle."""
    mid = ((I.g.h + I.g.l) / 2).reshape(-1)
    e9, e20, e50 = ema(mid, 9), ema(mid, 20), ema(mid, 50)
    sgn = np.sign(e9 - e20)
    cross = np.full(len(mid), np.nan)
    last, last_s = None, 0
    for i in range(len(mid)):
        if i > 0 and np.isfinite(sgn[i]) and np.isfinite(sgn[i - 1]) and sgn[i] != sgn[i - 1] and sgn[i] != 0:
            last, last_s = i, sgn[i]
        cross[i] = last_s * min(i - last, 120) if last is not None else 0.0
    D = I.g.h.shape[0]
    hh = I.g.h.reshape(D, -1, 5)
    ll = I.g.l.reshape(D, -1, 5)
    with np.errstate(all="ignore"):
        c5 = ((np.nanmax(hh, 2) + np.nanmin(ll, 2)) / 2).reshape(-1)
    e5 = ema(c5, 20)
    return dict(e9=e9, e20=e20, e50=e50, cross=cross, e5=e5)


# ------------------------------------------------------------------------------------------------ volume profile
def minute_volume(chain) -> np.ndarray:
    """ATM+-3 option volume (calls + puts) per minute of a session (0 where nothing traded); negative prints -> 0."""
    if chain is None:
        return np.zeros(K.N)
    v = chain["volume"][:, ATM3, :]
    return np.clip(np.nansum(v, axis=(1, 2)), 0, None)


def spread_hist(lo: np.ndarray, hi: np.ndarray, v: np.ndarray, bw: float, base: int, nb: int) -> np.ndarray:
    """Volume-at-price: each minute's volume spread evenly over the bins its wick range [lo, hi] covers."""
    out = np.zeros(nb)
    ok = np.isfinite(lo) & np.isfinite(hi) & (v > 0)
    for a, b, w in zip(lo[ok], hi[ok], v[ok]):
        i0, i1 = int(math.floor(a / bw)) - base, int(math.floor(b / bw)) - base
        i0, i1 = max(i0, 0), min(i1, nb - 1)
        if i1 < i0:
            continue
        out[i0:i1 + 1] += w / (i1 - i0 + 1)
    return out


def profile_features(hist: np.ndarray, bw: float, base: int, lo_t: float, hi_t: float, m: float) -> dict:
    tot = hist.sum()
    if not tot > 0 or not (np.isfinite(lo_t) and np.isfinite(hi_t)):
        return {c: np.nan for c in ("vo_poc", "vo_vah", "vo_val", "vo_in_va", "vo_node")}
    poc = (int(np.argmax(hist)) + base + 0.5) * bw
    order = np.argsort(hist)[::-1]
    cum = np.cumsum(hist[order])
    take = order[:int(np.searchsorted(cum, 0.7 * tot)) + 1]
    vah, val = (take.max() + base + 1) * bw, (take.min() + base) * bw
    i0, i1 = int(math.floor(lo_t / bw)) - base, int(math.floor(hi_t / bw)) - base
    i0, i1 = max(i0, 0), min(i1, len(hist) - 1)
    nz = hist[hist > 0]
    here = hist[i0:i1 + 1].mean() if i1 >= i0 else 0.0
    return dict(vo_poc=Z_dist(lo_t, hi_t, poc) / m, vo_vah=(vah - hi_t) / m, vo_val=(lo_t - val) / m,
                vo_in_va=float(lo_t <= vah and hi_t >= val), vo_node=float(here / nz.mean()) if len(nz) else np.nan)


def Z_dist(lo, hi, lvl):
    if lo <= lvl <= hi:
        return 0.0
    return min(abs(lvl - lo), abs(lvl - hi))


# ------------------------------------------------------------------------------------------------ order flow
def participants_daily() -> pd.DataFrame:
    """Daily participant features by date (as published that evening; the caller lags them one session)."""
    import load as OF1
    part, _ = OF1.participants("oi", raw=OF1_RAW)
    W = OF1.wide(part)
    tot_opt = (W["TOTAL.idx_call_long"] + W["TOTAL.idx_put_long"]).replace(0, np.nan)
    F = pd.DataFrame(index=W.index)
    F["fl_fii_ls"] = W["FII.long_share"]
    F["fl_fii_ls_chg"] = W["FII.long_share"].diff()
    F["fl_pro_stance"] = W["Pro.opt_stance"] / tot_opt
    F["fl_pro_stance_chg"] = F["fl_pro_stance"].diff()
    F["fl_cli_stance"] = W["Client.opt_stance"] / tot_opt
    F.index = F.index.date
    return F


def lagged(P: pd.DataFrame, d) -> dict:
    """The participant row of the last date strictly before d (published after that session's close)."""
    idx = P.index[P.index < d]
    if not len(idx):
        return {c: np.nan for c in P.columns}
    return P.loc[idx.max()].to_dict()


def option_flow(chain, t: int) -> dict:
    out = dict(fl_cp5=np.nan, fl_cp15=np.nan, fl_buildup=np.nan)
    if chain is None:
        return out
    v = np.clip(chain["volume"][:, ATM3, :], 0, None)
    for k, name in ((5, "fl_cp5"), (15, "fl_cp15")):
        a = np.nansum(v[max(t - k + 1, 0):t + 1], axis=(0, 1))
        out[name] = math.log((a[0] + 1) / (a[1] + 1))
    if t >= 15:
        cl = D3.ffill_rows(chain["close"][:t + 1, ATM3, :].transpose(1, 2, 0))
        oi = D3.ffill_rows(chain["oi"][:t + 1, ATM3, :].transpose(1, 2, 0))
        dp, doi = cl[..., t] - cl[..., t - 15], oi[..., t] - oi[..., t - 15]
        ok = np.isfinite(dp) & np.isfinite(doi) & (doi > 0)
        s = np.zeros_like(dp)
        s[:, 0] = np.where(dp[:, 0] > 0, 1, -1)         # calls: bought (up, OI up) bullish / written (down, OI up)
        s[:, 1] = np.where(dp[:, 1] < 0, 1, -1)         # puts: written (down, OI up) bullish / bought (up) bearish
        out["fl_buildup"] = float(s[ok].mean()) if ok.any() else 0.0
    return out


# ------------------------------------------------------------------------------------------------ one index
def index_features(part: str, und: str, rows: pd.DataFrame, log=print) -> pd.DataFrame:
    """The 31 new inputs for R124's rows of one index."""
    last_day = K.DESIGN[1] if part == "design" else K.HOLDOUT[1]
    grids = {u: D3.load_grid(u, last_day) for u in K.UNDS}
    vix = D3.load_grid("INDIAVIX", last_day)
    I = D3.IndexData(und, grids, vix)
    nifty = D3.IndexData("NIFTY", grids, vix)
    LO = D3.LakeOptions(und, last_day)
    P = participants_daily()
    bw = BIN[und]
    want = rows[rows.und == und].groupby("day").row.apply(list).to_dict()
    days_needed = sorted(want)
    first = I.dpos[days_needed[0]] - 25 if days_needed else 0
    vol_by_day, piv_by_day, hist_by_day = {}, {}, {}
    hi_all = np.nanmax(I.g.h) if np.isfinite(I.g.h).any() else 1.0
    nb = int(hi_all * 1.3 / bw) + 10
    MA = ma_series(I)
    out = []
    for i in range(max(first, 0), len(I.days)):
        d = I.days[i]
        if d > (days_needed[-1] if days_needed else d):
            break
        opts = LO.day(d, I.g.c[i])
        ch = opts.chain if opts is not None else None
        v = minute_volume(ch)
        vol_by_day[i] = v
        piv_by_day[i] = pivots(I.g.h[i], I.g.l[i])
        hist_by_day[i] = spread_hist(I.g.l[i], I.g.h[i], v, bw, 0, nb)
        if d not in want:
            continue
        ni = nifty.dpos.get(d)
        m, _ = Z.thresholds(I.d_open[i], nifty.d_open[ni] if ni is not None else np.nan)
        if not np.isfinite(m):
            continue
        h, l = I.g.h[i], I.g.l[i]
        prev = [k for k in range(i - PREV_DAYS, i) if k in piv_by_day]
        prev_hi = [p for k in prev for _, p, _ in piv_by_day[k][0]]
        prev_lo = [p for k in prev for _, p, _ in piv_by_day[k][1]]
        with np.errstate(all="ignore"):
            h5p = max([np.nanmax(I.g.h[k]) for k in prev], default=np.nan)
            l5p = min([np.nanmin(I.g.l[k]) for k in prev], default=np.nan)
        pdh = np.nanmax(I.g.h[i - 1]) if i > 0 else np.nan
        pdl = np.nanmin(I.g.l[i - 1]) if i > 0 else np.nan
        hp, lp = piv_by_day[i]
        prev20 = [vol_by_day[k] for k in range(i - 20, i) if k in vol_by_day]
        base_hist = sum((hist_by_day[k] for k in prev if k in hist_by_day), np.zeros(nb))
        flow_day = lagged(P, d)

        # notable levels for sweeps (amendment 1): yesterday's high/low, the previous 5 sessions' high/low, untaken
        # equal-high/low pools (as at the decision bar) and today's confirmed swings
        notable_hi = [x for x in (pdh, h5p) if np.isfinite(x)]
        notable_lo = [x for x in (pdl, l5p) if np.isfinite(x)]

        today_hist = np.zeros(nb)
        done = -1
        for t in sorted(want[d]):
            lo_t, hi_t = l[t], h[t]
            if not (np.isfinite(lo_t) and np.isfinite(hi_t)):
                continue
            known_hi = [p for _, p, c in hp if c <= t]
            known_lo = [p for _, p, c in lp if c <= t]
            levels = cluster(known_hi + known_lo + prev_hi + prev_lo, m / 3)
            with np.errstate(all="ignore"):
                h5 = np.nanmax([h5p, np.nanmax(h[:t + 1])])
                l5 = np.nanmin([l5p, np.nanmin(l[:t + 1])])
            f = dict(und=und, day=d, row=t)
            f.update(sr_features(levels, hi_t, lo_t, m, h5, l5))
            up = pools(hp, m / 6, t, h, "high", prev_hi)
            dn = pools(lp, m / 6, t, l, "low", prev_lo)
            upa = [p for p in up if p > hi_t]
            dnb = [p for p in dn if p < lo_t]
            f["lq_pool_up"] = (min(upa) - hi_t) / m if upa else np.nan
            f["lq_pool_dn"] = (lo_t - max(dnb)) / m if dnb else np.nan
            f.update(sweeps(h, l, t, lambda j, _u=up: [p for _, p, c in hp if c <= j] + notable_hi + _u,
                            lambda j, _d=dn: [p for _, p, c in lp if c <= j] + notable_lo + _d, m))
            f["lq_hilo_net"] = hilo_net(h, l, t)
            f.update({k: flow_day.get(k, np.nan) for k in ("fl_fii_ls", "fl_fii_ls_chg", "fl_pro_stance",
                                                         "fl_pro_stance_chg", "fl_cli_stance")})
            f.update(option_flow(ch, t))
            for k, name in ((5, "vo_v5"), (15, "vo_v15"), (60, "vo_v60")):
                a = v[max(t - k + 1, 0):t + 1].sum()
                b = np.mean([pv[max(t - k + 1, 0):t + 1].sum() for pv in prev20]) if prev20 else np.nan
                f[name] = math.log((a + 1) / (b + 1)) if np.isfinite(b) else np.nan
            if t > done:
                today_hist += spread_hist(l[done + 1:t + 1], h[done + 1:t + 1], v[done + 1:t + 1], bw, 0, nb)
                done = t
            f.update(profile_features(base_hist + today_hist, bw, 0, lo_t, hi_t, m))
            for tf in CANDLE_TF:
                f.update(candle_features(h, l, t, tf))
            g = I.gpos(i, t)
            k5 = i * (K.N // 5) + (t + 1) // 5 - 1                 # the last completed 5-minute candle
            f.update(vwap_ma_features(h, l, v, t, m, MA["e9"][g], MA["e20"][g], MA["e50"][g],
                                      MA["e20"][g - 15] if g >= 15 else np.nan, MA["cross"][g],
                                      MA["e5"][k5] if k5 >= 0 else np.nan))
            out.append(f)
    log(f"R125 {und} {part}: {len(out)} rows", flush=True)
    return pd.DataFrame(out)


def _one(args):
    part, und, rows = args
    return index_features(part, und, rows)


def add_features(part: str, rows: pd.DataFrame):
    from multiprocessing import Pool
    keys = rows[["und", "day", "row"]]
    with Pool(len(K.UNDS)) as pool:
        res = pool.map(_one, [(part, u, keys[keys.und == u]) for u in K.UNDS])
    F = pd.concat(res, ignore_index=True)
    OUT.mkdir(parents=True, exist_ok=True)
    F.to_parquet(OUT / f"feat_{part}.parquet")
    return F
