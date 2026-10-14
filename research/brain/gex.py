"""Dealer gamma exposure (GEX) of one session's option chain, minute by minute (R28, 14 Oct 2026).

One core used by both the Data Brain's L4 features (features/gex_f.py, from the cube's chain) and the R28 study
(research/hunt/r28, from the lake's WEEK and MONTH files), so the two can never disagree. Definitions are fixed in
research/hunt/r28/PREREG.md:

- Price: each contract's last traded price at or before minute t, at most STALE_MIN minutes old (caller supplies the
  price and its age; both come from earlier rows of the same session only).
- IV: Black-Scholes on spot (bs.py), r and q from config.py, T from the END of minute t to 15:30 IST on the expiry day.
  A contract with no IV takes the IV of the nearest strike of the same side that has one at t.
- GEX of a contract, Rs per 1% move of the index = gamma (per unit per point) x OI (units) x spot^2 x 0.01. The lake's OI
  is in units (checked across the Nov 2024 lot change), so there is no lot multiplier.
- S1 (dealers long calls, short puts): calls +, puts -. S2 (customer net buying since the open, from the OI change signed
  by the price change; dealers take the other side): -sum gamma x N. S3 (writers short everything) = -gross.
- Zero-gamma flip: S1 with spot moved over FLIP_GRID (IV and T held), the crossing of zero nearest to spot.

Everything at minute t uses rows <= t only (tests/test_gex.py checks it by deleting and by corrupting the future).
"""
from __future__ import annotations

import warnings
from datetime import date, datetime

import numpy as np
import pandas as pd

from . import bs, config

STALE_MIN = 30                          # minutes: an older last trade is not a price
MIN_CONTRACTS = 10                      # fewer contracts with a GEX value at t -> the minute's outputs are NaN
EDGE = 9                                # offsets |o| >= EDGE are the stored range's edge (ATM +-9, +-10)
FLIP_GRID = np.round(np.arange(-0.05, 0.05 + 1e-12, 0.0005), 6)   # spot x (0.95 .. 1.05), step 0.05%
CRORE = 1e7
OFFSETS = np.arange(config.CHAIN_OFFSETS.start, config.CHAIN_OFFSETS.stop)
NOFF = len(OFFSETS)
OUT_COLS = ("net_cr", "gross_cr", "s2_cr", "ratio", "n_contracts", "flip_dist_pct", "spot_above_flip",
            "wall_pos_strike", "wall_neg_strike", "wall_abs_strike", "edge_share", "oi_edge_share")


def years_to_expiry(day: date, minutes: np.ndarray, expiry: date | None) -> np.ndarray:
    """T in years from the END of grid minute m (bar 09:15 + m) to 15:30 IST on the expiry day; NaN when <= 0."""
    if expiry is None or pd.isna(expiry):
        return np.full(len(minutes), np.nan)
    h, mm = config.EXPIRY_CLOSE
    end = datetime(expiry.year, expiry.month, expiry.day, h, mm)
    start = datetime(day.year, day.month, day.day, *config.SESSION_OPEN)
    left = (end - start).total_seconds() / 60.0 - (np.asarray(minutes, float) + 1.0)
    T = left / (365.0 * 1440.0)
    return np.where(T > 0, T, np.nan)


def fill_nearest(a: np.ndarray) -> np.ndarray:
    """NaNs along axis 1 (strikes) replaced by the nearest finite value of the same row (the lower strike on a tie)."""
    out = a.copy()
    miss = ~np.isfinite(out)
    if not miss.any():
        return out
    for k in range(1, a.shape[1]):
        if not miss.any():
            break
        for sh in (k, -k):                                # lower strike first, then the higher one
            src = np.full_like(a, np.nan)
            if sh > 0:
                src[:, sh:] = a[:, :-sh]
            else:
                src[:, :sh] = a[:, -sh:]
            take = miss & np.isfinite(src)
            out[take] = src[take]
            miss &= ~take
    return out


def contract_flow(df: pd.DataFrame) -> np.ndarray:
    """Customers' net long units per row since the contract's first row today: the running sum over its rows of
    (OI change) x sign(price change) (OI up with the price up = new longs bought; OI up with the price down = new shorts
    written; flat price = 0). df: minute, strike, side, oi, px (the last traded price), one session. Rows of a contract
    only look back at its own earlier rows."""
    d = df[["minute", "strike", "side", "oi", "px"]].copy()
    d["_i"] = np.arange(len(d))
    d = d.sort_values(["strike", "side", "minute"], kind="stable")
    g = d.groupby(["strike", "side"], sort=False)
    doi = g.oi.diff()
    dpx = g.px.diff()
    f = (doi * np.sign(dpx)).fillna(0.0)
    d["n"] = f.groupby([d.strike, d.side], sort=False).cumsum()
    return d.sort_values("_i").n.values


def _grid(df: pd.DataFrame, col: str, side: str) -> np.ndarray:
    a = np.full((config.SESSION_MINUTES, NOFF), np.nan)
    s = df[df.side == side]
    m = s.minute.values.astype(int)
    o = s.offset.values.astype(int) - OFFSETS[0]
    ok = (m >= 0) & (m < config.SESSION_MINUTES) & (o >= 0) & (o < NOFF)
    a[m[ok], o[ok]] = s[col].values[ok].astype(float)
    return a


def _gamma_rs(S, K, T, iv):
    """gamma x S^2 x 0.01 per unit: Rs of delta-hedge per 1% move per unit of OI (shapes broadcast)."""
    _, g, _, _ = bs.greeks(S, K, T, iv, True, config.RISK_FREE, config.DIV_YIELD)
    return g * S * S * 0.01


def session(df: pd.DataFrame, spot: np.ndarray, day: date, expiry: date | None, flip: bool = True) -> pd.DataFrame:
    """Per-minute GEX outputs (375 rows, OUT_COLS) of one session of one expiry series.

    df: one row per (minute, offset, side) with columns minute (0..374), offset (-10..10), side 'C'/'P', strike, px (the
    contract's last traded price at or before the minute), age (minutes since that trade), oi (units, last reported),
    and optionally n (customers' net long units since the open, contract_flow); spot: (375,) index close per minute."""
    n = config.SESSION_MINUTES
    spot = np.asarray(spot, float)
    T = years_to_expiry(day, np.arange(n), expiry)[:, None]
    S = spot[:, None]
    has_n = "n" in df.columns
    side_vals = {}
    for side in ("C", "P"):
        K = _grid(df, "strike", side)
        px = _grid(df, "px", side)
        age = _grid(df, "age", side)
        oi = _grid(df, "oi", side)
        nn = _grid(df, "n", side) if has_n else np.zeros_like(oi)
        px = np.where(np.isfinite(age) & (age <= STALE_MIN) & (px >= config.IV_MIN_PRICE), px, np.nan)
        iv = bs.implied_vol(px, np.broadcast_to(S, px.shape), K, np.broadcast_to(T, px.shape), np.full(px.shape, side == "C"),
                            config.RISK_FREE, config.DIV_YIELD, config.IV_MIN, config.IV_MAX, min_vega=config.IV_MIN_VEGA)
        iv = np.where(np.isfinite(K), fill_nearest(iv), np.nan)
        with np.errstate(invalid="ignore", divide="ignore", over="ignore"):
            g = _gamma_rs(S, K, T, iv)
        g = np.where(np.isfinite(g) & np.isfinite(oi) & (oi >= 0), g, np.nan)
        side_vals[side] = dict(K=K, iv=iv, oi=oi, n=nn, g=g, c=g * oi)
    C, P = side_vals["C"], side_vals["P"]
    cnt = np.isfinite(C["c"]).sum(1) + np.isfinite(P["c"]).sum(1)
    valid = (cnt >= MIN_CONTRACTS) & np.isfinite(spot) & np.isfinite(T[:, 0])
    sc, sp = np.nansum(C["c"], 1), np.nansum(P["c"], 1)
    net = np.where(valid, sc - sp, np.nan)
    gross = np.where(valid, sc + sp, np.nan)
    s2 = np.where(valid, -(np.nansum(C["g"] * C["n"], 1) + np.nansum(P["g"] * P["n"], 1)), np.nan)
    with np.errstate(invalid="ignore", divide="ignore"):
        ratio = np.where(gross > 0, net / gross, np.nan)
    edge = np.abs(OFFSETS) >= EDGE
    eg = np.nansum(C["c"][:, edge], 1) + np.nansum(P["c"][:, edge], 1)
    oi_all = np.nansum(C["oi"], 1) + np.nansum(P["oi"], 1)
    oi_edge = np.nansum(C["oi"][:, edge], 1) + np.nansum(P["oi"][:, edge], 1)
    with np.errstate(invalid="ignore", divide="ignore"):
        edge_share = np.where(valid & (gross > 0), eg / gross, np.nan)
        oi_edge_share = np.where(valid & (oi_all > 0), oi_edge / oi_all, np.nan)
    # per-strike S1 (call and put of one offset share the strike at a minute) and the walls
    K = np.where(np.isfinite(C["K"]), C["K"], P["K"])
    per = np.where(np.isfinite(C["c"]) | np.isfinite(P["c"]), np.nan_to_num(C["c"]) - np.nan_to_num(P["c"]), np.nan)
    tot = np.where(np.isfinite(C["c"]) | np.isfinite(P["c"]), np.nan_to_num(C["c"]) + np.nan_to_num(P["c"]), np.nan)
    rows = np.arange(n)

    def pick(a, fn):
        ok = np.isfinite(a).any(1) & valid
        j = fn(np.where(np.isfinite(a), a, -np.inf if fn is np.argmax else np.inf), axis=1)
        return np.where(ok, K[rows, j], np.nan)
    wall_pos = pick(per, np.argmax)
    wall_neg = pick(per, np.argmin)
    wall_abs = pick(tot, np.argmax)
    flip_d = np.full(n, np.nan)
    if flip and valid.any():
        flip_d = _flip(spot, C, P, T[:, 0], valid)
    above = np.where(np.isfinite(flip_d), (flip_d < 0).astype(float), np.nan)
    return pd.DataFrame(dict(minute=rows, net_cr=net / CRORE, gross_cr=gross / CRORE, s2_cr=s2 / CRORE, ratio=ratio,
                             n_contracts=cnt.astype(float), flip_dist_pct=flip_d, spot_above_flip=above,
                             wall_pos_strike=wall_pos, wall_neg_strike=wall_neg, wall_abs_strike=wall_abs,
                             edge_share=edge_share, oi_edge_share=oi_edge_share))


def _flip(spot, C, P, T, valid) -> np.ndarray:
    """Distance of the zero-gamma level from spot, % (flip / spot - 1) x 100; NaN without a crossing on the grid."""
    out = np.full(len(spot), np.nan)
    idx = np.flatnonzero(valid)
    x = FLIP_GRID
    for lo in range(0, len(idx), 64):                    # chunks keep the (minutes x grid x strikes) cube small
        r = idx[lo:lo + 64]
        Sg = spot[r][:, None] * (1 + x)[None, :]          # (m, G)
        tot = np.zeros(Sg.shape)
        for sgn, d in ((1.0, C), (-1.0, P)):
            K, iv, oi = d["K"][r], d["iv"][r], d["oi"][r]
            use = np.isfinite(d["c"][r])
            with np.errstate(invalid="ignore", divide="ignore", over="ignore"):
                g = _gamma_rs(Sg[:, :, None], K[:, None, :], T[r][:, None, None], iv[:, None, :])
            tot += sgn * np.nansum(np.where(use[:, None, :], g * oi[:, None, :], 0.0), axis=2)
        s = np.sign(tot)
        cross = (s[:, :-1] * s[:, 1:] < 0) | (s[:, :-1] == 0)
        for i, rr in enumerate(r):
            js = np.flatnonzero(cross[i])
            if not len(js):
                continue
            # the crossing nearest to spot: interpolate in each candidate interval, keep the smallest |x|
            a, b = tot[i, js], tot[i, js + 1]
            with np.errstate(invalid="ignore", divide="ignore"):
                xs = np.where(a == 0, x[js], x[js] + (x[js + 1] - x[js]) * a / (a - b))
            out[rr] = xs[np.argmin(np.abs(xs))] * 100
    return out


def rolling_norm(net_by_day: np.ndarray, window: int = 20, min_days: int = 10) -> np.ndarray:
    """net_by_day: (days, 375) S1 of consecutive sessions. Each value / the median of |S1| at the same minute over the
    previous `window` sessions (at least `min_days` finite); NaN otherwise. Uses earlier sessions only."""
    a = np.asarray(net_by_day, float)
    out = np.full(a.shape, np.nan)
    for d in range(1, a.shape[0]):
        w = np.abs(a[max(0, d - window):d])
        ok = np.isfinite(w).sum(0) >= min_days
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            med = np.nanmedian(w, axis=0)
        out[d] = np.where(ok & (med > 0), a[d] / med, np.nan)
    return out


def last_trade(df: pd.DataFrame, stale: int = STALE_MIN) -> pd.DataFrame:
    """For lake-style rows (minute, strike, side, close, volume) of ONE session: px = the contract's last close on a
    minute with volume > 0 at or before this row, age = minutes since it (NaN before its first trade). Uses each
    contract's own earlier rows only."""
    d = df.copy()
    d["_i"] = np.arange(len(d))
    d = d.sort_values(["strike", "side", "minute"], kind="stable")
    traded = (d.volume.fillna(0) > 0) & (d.close > 0)
    d["px"] = np.where(traded, d.close, np.nan)
    d["_tm"] = np.where(traded, d.minute, np.nan)
    g = d.groupby(["strike", "side"], sort=False)
    d["px"] = g.px.ffill()
    d["_tm"] = g._tm.ffill()
    d["age"] = d.minute - d._tm
    d.loc[d.age > stale, "px"] = np.nan
    return d.sort_values("_i").drop(columns=["_i", "_tm"])
