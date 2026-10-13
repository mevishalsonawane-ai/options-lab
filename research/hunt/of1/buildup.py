"""OF1 option-chain build-up, who won at expiry, and walls / magnets (PREREG §6). Pure functions; no I/O.

Build-up labels (per contract per day, price = settle, OI = NSE's change in OI):
    price up,   OI up    long build-up        price down, OI up    short build-up
    price up,   OI down  short covering       price down, OI down  long unwinding
    otherwise            neutral (no price or no OI change, or no previous price: a new contract is 'new')
"""
from __future__ import annotations

import numpy as np
import pandas as pd

LABELS = ("long_buildup", "short_buildup", "short_covering", "long_unwinding", "neutral", "new")


def classify(dprice, doi) -> np.ndarray:
    """Vectorised build-up label from the price change and the OI change (NaN price change = 'new')."""
    dp = np.asarray(dprice, dtype=float)
    do = np.asarray(doi, dtype=float)
    out = np.full(dp.shape, "neutral", dtype=object)
    out[(dp > 0) & (do > 0)] = "long_buildup"
    out[(dp < 0) & (do > 0)] = "short_buildup"
    out[(dp > 0) & (do < 0)] = "short_covering"
    out[(dp < 0) & (do < 0)] = "long_unwinding"
    out[np.isnan(dp)] = "new"
    return out


def implied_final(chain_last: pd.DataFrame) -> float:
    """The underlying's final settlement from the expiring chain's own expiry-day settles: at expiry CE - PE = S - K for
    every strike, so S = median(K + CE - PE) over strikes where both sides settled (robust to a stray strike)."""
    c = chain_last.pivot_table(index="strike", columns="kind", values="settle", aggfunc="first")
    if "CE" not in c or "PE" not in c:
        return np.nan
    c = c.dropna()
    if c.empty:
        return np.nan
    return float(np.median(c.index.to_numpy(float) + c.CE.to_numpy(float) - c.PE.to_numpy(float)))


def moneyness_bucket(kind, strike, spot) -> np.ndarray:
    """ITM / ATM (within 1%) / OTM 1-3% / OTM >3%, from the option's side, strike and the spot at the time."""
    k = np.asarray(strike, dtype=float)
    s = np.asarray(spot, dtype=float)
    call = np.asarray(kind) == "CE"
    otm = np.where(call, k / s - 1.0, 1.0 - k / s)          # > 0: out of the money
    out = np.full(k.shape, "OTM>3%", dtype=object)
    out[otm <= 0.03] = "OTM1-3%"
    out[np.abs(otm) <= 0.01] = "ATM"
    out[otm < -0.01] = "ITM"
    return out


def expiry_outcomes(adds: pd.DataFrame, finals: pd.DataFrame) -> pd.DataFrame:
    """For each OI ADDITION (rows: und, expiry, strike, kind, date, settle, add_qty, label, ...), the held-to-expiry P&L
    per unit for the new LONG (final settle - settle at the add) and the rupees for the long side (x add_qty). Writers'
    rupees are the negative. `finals`: und, expiry, strike, kind, final (the expiry-day settle)."""
    j = adds.merge(finals, on=["und", "expiry", "strike", "kind"], how="inner")
    j["long_pnl_unit"] = j.final - j.settle
    j["long_pnl_rs"] = j.long_pnl_unit * j.add_qty
    return j


# ---- walls and magnets -----------------------------------------------------------------------------------------------
def walls(chain_k: pd.DataFrame, spot: float) -> dict[str, float]:
    """Call wall: the call strike with the most OI at or above spot; put wall: the put strike with the most OI at or below
    spot; K*: the strike with the most CE + PE OI. Ties go to the strike nearest spot."""
    out = {"call_wall": np.nan, "put_wall": np.nan, "kstar": np.nan}
    ce = chain_k[(chain_k.kind == "CE") & (chain_k.strike >= spot) & (chain_k.oi_qty > 0)]
    pe = chain_k[(chain_k.kind == "PE") & (chain_k.strike <= spot) & (chain_k.oi_qty > 0)]

    def top(df):
        if df.empty:
            return np.nan
        df = df.assign(dist=(df.strike - spot).abs()).sort_values(["oi_qty", "dist"], ascending=[False, True])
        return float(df.strike.iloc[0])

    out["call_wall"] = top(ce)
    out["put_wall"] = top(pe)
    tot = chain_k[chain_k.oi_qty > 0].groupby("strike").oi_qty.sum()
    if len(tot):
        t = tot.reset_index().assign(dist=lambda x: (x.strike - spot).abs()).sort_values(["oi_qty", "dist"], ascending=[False, True])
        out["kstar"] = float(t.strike.iloc[0])
    return out


def breach_baseline(dist: np.ndarray, moves: np.ndarray, side: str) -> np.ndarray:
    """Twin baseline for the wall test: for each expiry i, p_i = share of the OTHER expiries whose k-day move (in %, signed)
    went beyond wall i's distance (in %, positive = away from spot on the wall's side). side 'call' = up moves."""
    m = np.asarray(moves, dtype=float)
    d = np.asarray(dist, dtype=float)
    p = np.empty(len(d))
    for i in range(len(d)):
        other = np.delete(m, i)
        other = other[~np.isnan(other)]
        if len(other) == 0 or np.isnan(d[i]):
            p[i] = np.nan
            continue
        p[i] = np.mean(other > d[i]) if side == "call" else np.mean(-other > d[i])
    return p


def poisson_binomial_p(observed: int, p: np.ndarray, sims: int = 10000, seed: int = 1) -> float:
    """One-sided P(X <= observed) for X = sum of independent Bernoulli(p_i), by simulation (with a +1 correction)."""
    p = np.asarray(p, dtype=float)
    p = p[~np.isnan(p)]
    rng = np.random.default_rng(seed)
    x = (rng.random((sims, len(p))) < p).sum(axis=1)
    return float(((x <= observed).sum() + 1) / (sims + 1))


def mirror(kstar: float, spot: float, step: float) -> float:
    """The twin strike at the same distance from spot on the other side, on the strike grid."""
    return float(np.round((2.0 * spot - kstar) / step) * step)


def signflip_p(diff: np.ndarray, sims: int = 10000, seed: int = 2) -> float:
    """One-sided sign-flip test that mean(diff) < 0 (the max-OI strike ends CLOSER than its twin). Zeros are kept."""
    d = np.asarray(diff, dtype=float)
    d = d[~np.isnan(d)]
    if len(d) == 0:
        return np.nan
    rng = np.random.default_rng(seed)
    obs = d.mean()
    flips = rng.choice([-1.0, 1.0], size=(sims, len(d)))
    null = (flips * np.abs(d)).mean(axis=1)
    return float(((null <= obs).sum() + 1) / (sims + 1))
