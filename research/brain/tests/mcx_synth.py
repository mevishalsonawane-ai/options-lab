"""Seeded synthetic MCX sources for the MCX brain tests (no network, no lake): option-book rows in the lake's layout,
continuous-future minutes and near-month daily bars, over weekdays that cross the US daylight-saving change (MCX closes
23:30 before 2 Nov 2025 and 23:55 after)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.data import mcx as L

START = "2025-10-06"


def days(n: int = 30, start: str = START) -> list:
    return [d.date() for d in pd.bdate_range(start, periods=n)]


def spot_minutes(n_days: int = 30, seed: int = 0, start: str = START, expiry_idx=(12,)) -> pd.DataFrame:
    """The option book's near future per minute (naive IST) with option context. The contract rolls the session after
    each expiry day (index into the day list): the price jumps 2% and the straddle triples (R53's detection rule)."""
    rng = np.random.default_rng(seed)
    rows = []
    px, iv = 100_000.0, 20.0
    for i, d in enumerate(days(n_days, start)):
        if i - 1 in expiry_idx:
            px *= 1.02
        k = L.close_min(d) - 9 * 60
        ts = pd.Timestamp(d) + pd.Timedelta(hours=9) + pd.to_timedelta(np.arange(k), unit="min")
        r = rng.normal(0, 0.0006, k)
        r[0] += rng.normal(0, 0.003)
        c = px * np.exp(np.cumsum(r))
        px = c[-1]
        ivs = iv + np.cumsum(rng.normal(0, 0.05, k))
        days_left = min((e - i for e in expiry_idx if e >= i), default=20)
        strad = c * ivs / 100 * np.sqrt(max(days_left, 0.3) / 365) * 0.8
        f = pd.DataFrame({"ts": ts, "close": c, "atm_iv": ivs, "straddle": strad,
                          "pcr_oi": rng.uniform(0.6, 1.4, k), "opt_vol": rng.integers(0, 500, k).astype(float)})
        drop = rng.random(k) < 0.02           # a few missing minutes (gaps, never filled)
        rows.append(f[~drop])
    return pd.concat(rows, ignore_index=True)


def option_rows(spot: pd.DataFrame, seed: int = 1) -> pd.DataFrame:
    """Lake-layout option rows (tz-aware IST ts, offset, side, close, iv, volume, oi, spot) for ATM-1..+1."""
    rng = np.random.default_rng(seed)
    out = []
    for off in (-1, 0, 1):
        for side in ("C", "P"):
            n = len(spot)
            out.append(pd.DataFrame({
                "ts": spot.ts.dt.tz_localize("Asia/Kolkata"), "offset": np.int8(off), "side": side,
                "close": spot.straddle.values / 2 * (1 + 0.1 * off * (1 if side == "P" else -1)),
                "iv": np.where(off == 0, spot.atm_iv.values + (0.5 if side == "C" else -0.5), 25.0).astype("float32"),
                "volume": rng.integers(0, 50, n), "oi": rng.integers(100, 1000, n),
                "spot": spot.close.values + rng.choice([-1.0, 0.0, 1.0], n)}))
    o = pd.concat(out, ignore_index=True)
    o["side"] = o.side.astype("category")
    return o


def fut_minutes(spot: pd.DataFrame, from_day=None, expiry="2025-12-05", seed: int = 2) -> pd.DataFrame:
    """clean.fut_frame layout: the future's true bar from from_day on."""
    rng = np.random.default_rng(seed)
    s = spot if from_day is None else spot[spot.ts.dt.date >= from_day]
    c = s.close.values * 1.001
    return pd.DataFrame({"ts": s.ts.values, "fo": c * (1 - 1e-4), "fh": c * (1 + 3e-4), "fl": c * (1 - 3e-4), "fc": c,
                         "fvol": rng.integers(1, 200, len(s)).astype(float),
                         "foi": 10_000 + np.cumsum(rng.integers(-5, 6, len(s))).astype(float),
                         "fexp": pd.Timestamp(expiry)})


def daily_bars(spot: pd.DataFrame, sym: str = "GOLDM") -> pd.DataFrame:
    g = spot.groupby(spot.ts.dt.normalize()).close
    return pd.DataFrame({"symbol": sym, "expiry_code": 0, "open": g.first().values, "high": g.max().values,
                         "low": g.min().values, "close": g.last().values, "volume": 1.0, "oi": 1.0,
                         "date": g.first().index.values})
