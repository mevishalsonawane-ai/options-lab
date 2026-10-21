"""R53 crypto options: Delta India BTC/ETH daily options priced on 1-minute MARK candles (fetch.OptionStore), with
RC2's strike rules (rc2/sim.py pick_strike: ATM nearest listed strike within 1%; the hero's cheap OTM band), RC2's
listing-glitch filter (rc2/data.py Panel.tradable: no entry in a strike's first 15 minutes, nor on a bar that opens
more than 2x / under 0.5x the previous close), RC2's fills (rc2/costs.py: buy at the ask, sell at the bid less a tick
and 1%) and RC2's stop + lock exits (rc2/exits.py run), and R53's cost books (common.option_fee) and money.

Timing (PREREG section 2): the signal is known at the decision instant T (a bar's close). The strike is chosen at T
from the marks known at T (the close of the minute that ends at T). The option is bought ONE MINUTE LATER than the
next bar: at the open of the 1-minute candle that starts at T + 1 min (the book is thin). Exits are walked on the
1-minute marks from the entry candle on (rc2.exits.run: a candle that opens through the level exits at its open).
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from research.hunt.rc2 import config as RCC
from research.hunt.rc2 import costs as rc2costs
from research.hunt.rc2.exits import Exit, run as run_exit

from .. import common as RC
from . import fetch

ONE = pd.Timedelta(minutes=1)
LISTING_WAIT = 15                     # minutes (RC2 amendment 4: 3 five-minute bars)
JUMP = 2.0
ATM_MAX_DIST = 0.01                   # rc2/sim.py
CHEAP_BAND = (0.0002, 0.0010)         # rc2/sim.py: the hero's mark between 0.02% and 0.10% of spot
GRIDS = {"BTC": (200, 500, 1000), "ETH": (20, 25, 50)}   # Delta's strike spacings (rc2/fetch/fetch_delta.py FINE)
OTM_MAX = 0.08                        # the hero looks at most 8% out of the money (RC2's coarse grid reach)
ACCOUNT = 100_000.0
PREMIUM_FRAC = 0.02                   # RC2 / F1C: premium (with the entry fee) <= 2% of the account
LOT = RCC.LOT
TICK = RCC.TICK
SMODEL = rc2costs.spread_model()      # RC2's spread model (the wider of its two live-chain fits)
BOOKS = tuple(RC.CRYPTO_BOOKS)


@dataclass
class Contract:
    """Dense 1-minute marks over [expiry - 28 h, expiry). A minute with no candle repeats the last close as a flat
    candle (no backfill: before the first candle the arrays are NaN)."""
    t0: pd.Timestamp
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    first: int

    @classmethod
    def from_frame(cls, d: pd.DataFrame, expiry: pd.Timestamp) -> "Contract":
        t0 = expiry - fetch.OPT_SPAN
        n = int(fetch.OPT_SPAN / ONE)
        idx = ((pd.DatetimeIndex(d.t) - t0) // ONE).to_numpy()
        ok = (idx >= 0) & (idx < n)
        a = np.full((n, 4), np.nan)
        a[idx[ok]] = d[["o", "h", "l", "c"]].to_numpy(float)[ok]
        have = np.isfinite(a[:, 3])
        first = int(np.argmax(have)) if have.any() else n
        last_c = pd.Series(np.where(have, a[:, 3], np.nan)).ffill().to_numpy()
        for k in range(4):
            a[:, k] = np.where(have, a[:, k], last_c)
        return cls(t0, a[:, 0], a[:, 1], a[:, 2], a[:, 3], first)

    def pos(self, ts: pd.Timestamp) -> int:
        """Index of the candle that STARTS at ts (-1 outside)."""
        i = int((ts - self.t0) // ONE)
        return i if 0 <= i < len(self.c) else -1

    def known_close(self, T: pd.Timestamp) -> float:
        """The mark known at T: the close of the candle that ends at T."""
        i = self.pos(T - ONE)
        return float(self.c[i]) if i >= 0 else np.nan

    def listed_by(self, T: pd.Timestamp) -> bool:
        i = self.pos(T - ONE)
        return i >= 0 and i >= self.first + LISTING_WAIT - 1

    def entry_ok(self, j: int) -> bool:
        """The entry candle j has a finite open within 0.5x-2x of the previous close (RC2's jump filter)."""
        if j < 1 or j >= len(self.o) or j <= self.first:
            return False
        prev, op = self.c[j - 1], self.o[j]
        return bool(np.isfinite(prev) and np.isfinite(op) and prev > 0 and op > 0 and 1 / JUMP <= op / prev <= JUMP)


class Chain:
    """Contracts of one coin, fetched lazily (fetch.OptionStore) and memoised."""

    def __init__(self, coin: str, store: fetch.OptionStore):
        self.coin, self.store = coin, store
        self.mem: dict = {}

    def contract(self, expiry, typ, K) -> Contract | None:
        key = (expiry, typ, int(K))
        if key not in self.mem:
            d = self.store.get(self.coin, expiry, typ, int(K))
            self.mem[key] = None if d is None else Contract.from_frame(d, expiry)
        return self.mem[key]

    def candidates(self, spot: float, lo: float, hi: float) -> list[int]:
        """Strikes on any of Delta's grids in [lo, hi], nearest to spot first (ties: the lower strike, as RC2)."""
        ks = set()
        for g in GRIDS[self.coin]:
            ks.update(range(int(np.ceil(lo / g)) * g, int(np.floor(hi / g)) * g + 1, g))
        return sorted(ks, key=lambda k: (abs(k - spot), k))

    def keys_atm(self, spot):
        return self.candidates(spot, spot * (1 - ATM_MAX_DIST), spot * (1 + ATM_MAX_DIST))

    def atm(self, expiry, typ, spot, T):
        """rc2/sim.py pick_strike 'atm': the listed, tradable strike nearest the index (skip beyond 1%)."""
        for K in self.keys_atm(spot):
            ct = self.contract(expiry, typ, K)
            if ct is None or not ct.listed_by(T):
                continue
            px = ct.known_close(T)
            if np.isfinite(px) and px > 0:
                return K, ct
        return None

    def cheap(self, expiry, typ, spot, T):
        """rc2/sim.py pick_strike 'cheap': the dearest out-of-the-money option on the side whose mark is 0.02-0.10%
        of the index. Marks fall away from the money, so the walk outward stops at the first one in (or under) the
        band."""
        if typ == "C":
            ks = [k for k in self.candidates(spot, spot, spot * (1 + OTM_MAX)) if k > spot]
        else:
            ks = [k for k in self.candidates(spot, spot * (1 - OTM_MAX), spot) if k < spot]
        for K in ks:
            ct = self.contract(expiry, typ, K)
            if ct is None or not ct.listed_by(T):
                continue
            px = ct.known_close(T)
            if not (np.isfinite(px) and px > 0):
                continue
            if px > CHEAP_BAND[1] * spot:
                continue
            return (K, ct) if px >= CHEAP_BAND[0] * spot else None
        return None


def book_nets(coin: str, buy: float, sell: float, spot_in: float, spot_out: float, q: float, fx: float) -> dict:
    """Rupee net of one option trade (q coins of underlying) in every R53 cost book (common.option_fee), before tax."""
    out = {}
    for b in BOOKS:
        f_in = RC.option_fee(b, buy * q, spot_in * q)
        f_out = RC.option_fee(b, sell * q, spot_out * q) if sell > 0 else 0.0
        out[b] = ((sell - buy) * q - f_in - f_out) * fx
    return out


def trade(chain: Chain, T: pd.Timestamp, side: str, expiry: pd.Timestamp, deadline: pd.Timestamp, ex: Exit,
          mode: str, spot_at, fx_at) -> dict | str:
    """One option trade for a signal at decision instant T (side 'C' / 'P'). Returns a row, or a skip reason.
    spot_at(ts) -> the Delta index known at ts; fx_at(ts) -> USD/INR."""
    if not isinstance(ex, Exit):
        raise TypeError("an option trade needs an rc2.exits.Exit (stop-loss AND profit lock)")
    coin = chain.coin
    spot = spot_at(T)
    if not np.isfinite(spot):
        return "no_spot"
    pick = chain.atm(expiry, side, spot, T) if mode == "atm" else chain.cheap(expiry, side, spot, T)
    if pick is None:
        return "no_strike"
    K, ct = pick
    t_in = T + ONE                                    # one minute later than the next bar (PREREG section 2)
    j = ct.pos(t_in)
    if j < 0 or not ct.entry_ok(j):
        return "no_print"
    mid = float(ct.o[j])
    if mid < 2 * TICK[coin]:
        return "too_cheap"
    dl = int(np.ceil((deadline - ct.t0) / ONE))
    if dl <= j:
        return "no_time"
    seg = slice(j, len(ct.o))
    lvl, why, k, peak = run_exit(ex, mid, ct.o[seg], ct.h[seg], ct.l[seg], ct.c[seg], dl - j)
    t_out = ct.t0 + (j + k) * ONE
    fx = float(fx_at([t_in])[0])
    s_in = spot_at(t_in)
    s_in = spot if not np.isfinite(s_in) else s_in
    s_out = spot_at(t_out)
    s_out = s_in if not np.isfinite(s_out) else s_out
    buy = rc2costs.buy_fill(coin, mid, s_in, SMODEL)
    sell = rc2costs.sell_fill(coin, lvl, s_out, SMODEL, market=True)
    fee_lot = RC.option_fee("delta", buy * LOT[coin], s_in * LOT[coin])
    cost_lot_inr = (buy * LOT[coin] + fee_lot) * fx
    lots = int(np.floor(PREMIUM_FRAC * ACCOUNT / cost_lot_inr + 1e-9))
    if lots < 1:
        return "too_dear"
    q = lots * LOT[coin]
    nets = book_nets(coin, buy, sell, s_in, s_out, q, fx)
    row = dict(coin=coin, T=T, entry_ts=t_in, exit_ts=t_out, side=side, expiry=expiry, K=K, spot=spot, mid=mid,
               buy=buy, level=lvl, sell=sell, reason=why, peak=peak, lots=lots, q=q, fx=fx,
               gross=(sell - buy) * q * fx, sale_inr=sell * q * fx)
    row.update({f"net_{b}": v for b, v in nets.items()})
    return row


def keys_needed(chain: Chain, T, side, expiry, mode, spot) -> list:
    """Contracts the strike walk may read for one entry (to prefetch in parallel): the ATM candidates, or the hero's
    out-of-the-money walk, nearest first. Over-fetching is harmless (cached)."""
    if mode == "atm":
        ks = chain.keys_atm(spot)[:2]
    elif side == "C":
        ks = [k for k in chain.candidates(spot, spot, spot * 1.03) if k > spot][:8]
    else:
        ks = [k for k in chain.candidates(spot, spot * 0.97, spot) if k < spot][:8]
    return [(chain.coin, expiry, side, k) for k in ks]
