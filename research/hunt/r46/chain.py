"""R46 amendments: one expiry's whole chain on a dense minute grid, the ROLLING ATM series (what a "btc_atm" file
holds: the strike nearest spot, rolling to the next expiry each day, so its price jumps between contracts), and lookups
on the HELD contract (one strike and expiry, followed through time).

Delta candles are trades: prices are carried forward inside a contract and volume is 0 where nothing traded.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import amend_cfg as A
from . import data as D
from . import r46cfg as K

ONE = pd.Timedelta(minutes=1)


def own_window(E: pd.Timestamp) -> tuple[pd.Timestamp, pd.Timestamp]:
    """Minutes t whose expiry (R46 rule: nearest with >= 90 min left) is E: (E - 1 day - 90 min, E - 90 min]."""
    return E - pd.Timedelta(days=1) - K.MIN_LEFT + ONE, E - K.MIN_LEFT


class ChainDay:
    """Contracts of expiry E on the grid [own window start - LOOKBACK, E - 10 min]. arr[typ] = (strikes ascending,
    o, h, l, c, v) with price arrays (n_strikes, G), carried forward inside each contract (NaN before its first
    trade), v = traded volume (0 where none)."""

    def __init__(self, E: pd.Timestamp, frame: pd.DataFrame | None = None):
        self.E = E
        a, b = own_window(E)
        self.grid = pd.date_range(a - pd.Timedelta(minutes=A.LOOKBACK_MIN), E - K.LAST_EXIT_BEFORE_EXPIRY, freq="1min")
        self.t0 = self.grid[0]
        self.own = (self.idx(a), self.idx(b))                   # inclusive index range of the own window
        G = len(self.grid)
        if frame is None:
            p = K.LAKE / "delta_india" / "candles" / "BTC" / str(E.year) / f"{E.date()}.parquet"
            frame = pd.read_parquet(p, columns=["timestamp", "option_type", "strike", "expiry_ts", "open", "high",
                                                "low", "close", "volume"]) if p.exists() else pd.DataFrame()
            if len(frame):
                frame = frame[frame.expiry_ts == int(E.value // 10**6)]
        self.arr = {}
        F = frame.copy()
        if len(F):
            F["ts"] = pd.to_datetime(F.timestamp, unit="ms")
            F = F[(F.ts >= self.grid[0]) & (F.ts <= self.grid[-1])]
        self.ok = len(F) > 0
        for typ in ("C", "P"):
            g = F[F.option_type == typ] if len(F) else F
            ks = np.sort(g.strike.unique()).astype(float) if len(g) else np.zeros(0)
            n = len(ks)
            o, h, l, c = (np.full((n, G), np.nan) for _ in range(4))
            v = np.zeros((n, G))
            for j, k in enumerate(ks):
                x = g[g.strike == k].sort_values("ts").drop_duplicates("ts", keep="last").set_index("ts")
                x = x.reindex(self.grid)
                v[j] = x.volume.fillna(0.0).to_numpy(float)
                px = x[["open", "high", "low", "close"]].copy()
                px[~(px > 0)] = np.nan
                px = px.ffill().to_numpy(float)
                o[j], h[j], l[j], c[j] = px[:, 0], px[:, 1], px[:, 2], px[:, 3]
            self.arr[typ] = (ks, o, h, l, c, v)

    def idx(self, t) -> int:
        return int((pd.Timestamp(t) - self.t0) / ONE)

    def atm(self, typ: str, spot: np.ndarray) -> np.ndarray:
        """Per grid minute, the row of the strike nearest spot (within ATM_BAND) among strikes with a price; -1 if
        none. Ties go to the lower strike. Uses only prices up to that minute (carried forward)."""
        ks, _, _, _, c, _ = self.arr[typ]
        spot = np.asarray(spot, float)
        if not len(ks):
            return np.full(len(spot), -1)
        d = np.abs(ks[:, None] - spot[None, :])
        d[~np.isfinite(c)] = np.inf
        d[:, ~np.isfinite(spot)] = np.inf
        k = np.argmin(d, axis=0)
        best = d[k, np.arange(len(spot))]
        ok = np.isfinite(best) & (best <= K.ATM_BAND * np.where(np.isfinite(spot), spot, 0))
        return np.where(ok, k, -1)


def expiries(a: pd.Timestamp, b: pd.Timestamp) -> list[pd.Timestamp]:
    """Expiries whose own window meets [a, b)."""
    E = D.expiry_of(a)
    out = []
    while own_window(E)[0] < b:
        out.append(E)
        E += pd.Timedelta(days=1)
    return out


def rolling_day(day: ChainDay, typ: str, spot_close: np.ndarray, atm_rows: np.ndarray) -> pd.DataFrame:
    """The rolling ATM series over E's own window: ts, expiry, strike, price, traded (volume > 0 that minute)."""
    i0, i1 = day.own
    ks, _, _, _, c, v = day.arr[typ]
    r = atm_rows[i0:i1 + 1]
    cols = np.arange(i0, i1 + 1)
    ok = r >= 0
    price = np.full(len(r), np.nan)
    strike = np.full(len(r), np.nan)
    traded = np.zeros(len(r), bool)
    price[ok] = c[r[ok], cols[ok]]
    strike[ok] = ks[r[ok]]
    traded[ok] = v[r[ok], cols[ok]] > 0
    return pd.DataFrame(dict(ts=day.grid[i0:i1 + 1], side=typ, expiry=day.E, strike=strike, premium=price,
                             traded=traded, spot=spot_close[i0:i1 + 1]))


def contract_id(df: pd.DataFrame) -> np.ndarray:
    """One integer per (side, expiry, strike); -1 where there is no contract."""
    key = df.side.astype(str) + "|" + df.expiry.astype(str) + "|" + df.strike.astype(str)
    codes, _ = pd.factorize(key)
    return np.where(df.strike.notna().to_numpy(), codes, -1)
