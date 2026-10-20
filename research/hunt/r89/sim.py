"""R89 straddle pricing on the lake's option minutes (PREREG sections 2-3).

An OptionTape holds every option minute of one instrument keyed by (day, side, strike) -> contract id and the minute of
the session; lookups are vectorised with searchsorted on key = id * 2048 + minute.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r88 import cfg as R88
from research.hunt.r88 import data as D88

sys.path.insert(0, str(R88.ROOT / "research"))
from obuy.costs import Costs, Fills  # noqa: E402
from research.hunt.r51 import r51cfg  # noqa: E402
from research.hunt.r53 import common as C53  # noqa: E402
from research.hunt.r85 import strategy as S85  # noqa: E402

COSTS, FILLS = Costs("dated"), Fills("liq")
W = 2048
LOOK = 3                      # minutes to look for a print
HOLDS = {"H1": 15, "H4": 60}
INDEX_SQ, INDEX_SQ_EXPIRY = 15 * 60 + 10, 15 * 60 + 5
MCX_UNITS = {"GOLDM": 10.0, "SILVERM": 5.0, "CRUDEOIL": 10.0, "NATURALGAS": 250.0}  # R85 OPTION_BOOK units


def open_min(sym: str) -> int:
    return R88.MCX_OPEN if R88.is_mcx(sym) else R88.NSE_OPEN


@dataclass
class OptionTape:
    sym: str
    keys: np.ndarray          # sorted int64 id * W + minute
    opn: np.ndarray
    cls: np.ndarray
    cumvol: np.ndarray        # cumulative volume within each contract (inclusive)
    ids: dict                 # (day, side, strike) -> id
    atm: pd.DataFrame         # day, minute, strike (offset-0 call rows)

    @classmethod
    def build(cls, sym: str, book: pd.DataFrame) -> "OptionTape":
        """book: rows with ts (naive IST), offset, side, strike, open, close, volume."""
        om = open_min(sym)
        b = book.dropna(subset=["open", "close"]).copy()
        b = b[(b.open > 0) & (b.close > 0)]
        b["day"] = b.ts.dt.normalize()
        b["m"] = (b.ts.dt.hour * 60 + b.ts.dt.minute - om).astype(np.int64)
        b = b[(b.m >= 0) & (b.m < W)]
        trip = pd.MultiIndex.from_arrays([b.day, b.side.astype(str), b.strike.astype(float)])
        codes, uniq = pd.factorize(trip)
        b["id"] = codes.astype(np.int64)
        b["key"] = b.id * W + b.m
        b = b.sort_values("key").drop_duplicates("key", keep="last")
        v = b.volume.fillna(0).clip(lower=0).to_numpy(float)
        cum = pd.Series(v).groupby(b.id.to_numpy()).cumsum().to_numpy()
        ids = {k: i for i, k in enumerate(uniq)}
        atm = b[(b.offset == 0) & (b.side.astype(str) == "C")][["day", "m", "strike"]].sort_values(["day", "m"])
        return cls(sym, b.key.to_numpy(np.int64), b.open.to_numpy(float), b.close.to_numpy(float), cum, ids,
                   atm.reset_index(drop=True))

    def cid(self, days, side: str, strikes) -> np.ndarray:
        return np.array([self.ids.get((d, side, float(k)), -1) for d, k in zip(days, strikes)], dtype=np.int64)

    def first_print(self, cid, m, look=LOOK):
        """(position, ok) of the first print at minute m .. m + look of each contract."""
        q = cid * W + m
        pos = np.searchsorted(self.keys, q, side="left")
        pos_c = np.minimum(pos, len(self.keys) - 1)
        ok = (cid >= 0) & (pos < len(self.keys)) & (self.keys[pos_c] <= q + look) & (self.keys[pos_c] // W == cid)
        return pos_c, ok

    def last_close(self, cid, m):
        """(position, ok) of the last print at or before minute m of each contract (same contract)."""
        q = cid * W + m
        pos = np.searchsorted(self.keys, q, side="right") - 1
        pos_c = np.maximum(pos, 0)
        ok = (cid >= 0) & (pos >= 0) & (self.keys[pos_c] // W == cid)
        return pos_c, ok

    def vol_before(self, cid, m, n=5):
        """Volume of the contract in minutes m - n .. m - 1."""
        a, oka = self.last_close(cid, m - 1)
        b, okb = self.last_close(cid, m - n - 1)
        va = np.where(oka, self.cumvol[a], 0.0)
        vb = np.where(okb, self.cumvol[b], 0.0)
        return np.maximum(va - vb, 0.0)

    def atm_strike(self, days, m):
        """Offset-0 call strike at the last book minute <= m (within LOOK minutes) of each day; NaN when none."""
        a = self.atm
        dk = (a.day.values.astype("datetime64[D]").astype(np.int64) * W + a.m.to_numpy())
        q = np.asarray(days, dtype="datetime64[D]").astype(np.int64) * W + np.asarray(m)
        pos = np.searchsorted(dk, q, side="right") - 1
        pc = np.maximum(pos, 0)
        ok = (pos >= 0) & (dk[pc] >= q - LOOK) & (dk[pc] // W == q // W)
        return np.where(ok, a.strike.to_numpy()[pc], np.nan)


def exit_minute(sym: str, days, m0, hold: int, expiries: set) -> np.ndarray:
    """Exit minute (from the session open): entry + hold, capped at the square-off."""
    om = open_min(sym)
    if R88.is_mcx(sym):
        close = np.array([D88.MCXLIB.close_min(pd.Timestamp(d)) for d in days])
        cap = close - 15 - om
    else:
        cap = np.array([(INDEX_SQ_EXPIRY if pd.Timestamp(d).date() in expiries else INDEX_SQ) - om for d in days])
    return np.minimum(np.asarray(m0) + hold, cap)


def price(tape: OptionTape, sym: str, days, m0, m1) -> pd.DataFrame:
    """Price a straddle per row: buy the ATM call and put at minute m0, sell both at m1. Returns per-row columns:
    ok, strike, buy_c, buy_p, sell_c, sell_p, qty, gross, cost, net, stale (any leg exited on a last close)."""
    days = pd.to_datetime(pd.Series(days)).dt.normalize().to_numpy()
    m0, m1 = np.asarray(m0, np.int64), np.asarray(m1, np.int64)
    k = tape.atm_strike(days, m0 - 1)
    dlist = list(pd.to_datetime(days))
    out = dict(strike=k)
    ok = np.isfinite(k) & (m1 > m0)
    stale = np.zeros(len(k), bool)
    legs = {}
    for side in ("C", "P"):
        cid = tape.cid(dlist, side, np.where(np.isfinite(k), k, -1))
        pe, oke = tape.first_print(cid, m0)
        px, okx = tape.first_print(cid, m1)
        pl, okl = tape.last_close(cid, m1)
        exit_px = np.where(okx, tape.opn[px], np.where(okl, tape.cls[pl], np.nan))
        stale |= ~okx & okl
        legs[side] = (np.where(oke, tape.opn[pe], np.nan), exit_px, tape.vol_before(cid, m0))
        ok &= oke & (okx | okl)
    dd = [pd.Timestamp(d).date() for d in days]
    if R88.is_mcx(sym):
        units = MCX_UNITS[sym]
        tod_in = m0 + open_min(sym)
        tod_out = m1 + open_min(sym)
        gross = np.zeros(len(k))
        cost = np.zeros(len(k))
        for side in ("C", "P"):
            e, x, _ = legs[side]
            b_rs, s_rs = units * e, units * x
            gross += s_rs - b_rs
            cost += np.array([S85.opt_cost(sym, bi, si, ti, to) if np.isfinite(bi) and np.isfinite(si) else np.nan
                              for bi, si, ti, to in zip(b_rs, s_rs, tod_in, tod_out)])
        qty = np.full(len(k), units)
        out.update(buy_c=legs["C"][0], buy_p=legs["P"][0], sell_c=legs["C"][1], sell_p=legs["P"][1])
    else:
        qty = np.array([r51cfg.lot(sym, d) for d in dd], float)
        dn = np.array([(d - date(1970, 1, 1)).days for d in dd])
        bse = sym in ("SENSEX", "BANKEX")
        gross = np.zeros(len(k))
        cost = np.zeros(len(k))
        for side in ("C", "P"):
            e, x, v5 = legs[side]
            lots5 = v5 / np.where(qty > 0, qty, 1)
            fb = FILLS.buy(np.nan_to_num(e), lots5)
            fs = FILLS.sell(np.nan_to_num(x), None)
            out[f"buy_{side.lower()}"], out[f"sell_{side.lower()}"] = fb, fs
            gross += qty * (fs - fb)
            cost += COSTS.charge(True, fb, qty, dn, bse) + COSTS.charge(False, fs, qty, dn, bse)
    out.update(ok=ok, qty=qty, gross=np.where(ok, gross, np.nan), cost=np.where(ok, cost, np.nan), stale=stale & ok)
    df = pd.DataFrame(out)
    df["net"] = df.gross - df.cost
    df["entry_prem"] = df.buy_c + df.buy_p
    return df
