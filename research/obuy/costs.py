"""Fills (slippage) and charges for bought index options.

FILLS (Fills.mode)
  'app'   the app's paper account (Px): MARKET orders +-5 bps on the price, resting stops (SL-M) -10 bps, rounded to
          the paisa half-even (exact integer arithmetic, so it matches the Kotlin / Decimal replays to the paisa).
  'liq'   liquidity-aware: the 'app' bps PLUS a half-spread in ticks that grows when the contract is thin. Dhan's
          minute data has no bid/ask, so the spread is modelled from the contract's traded volume in the 5 minutes
          before the order: >= 20 lots -> 1 tick, 1-20 lots -> 2 ticks, nothing traded -> 4 ticks (stops: x2).
          A tick is Rs 0.05, which is 1-5% of a Rs 1-5 'hero' option - the dominant cost there.
  'flat'  +-`pts` rupees a side (older studies: 0.5).

CHARGES (Costs.mode) per order (one leg, one side), Rs:
  'app'    SandboxCosts (the app's model, today's rates): brokerage 20 + STT 0.15% of sell premium + exchange 0.03553%
           (NSE 0.03503% + IPFT 0.0005%; BSE 0.0325%) + SEBI Rs 10/crore + stamp 0.003% of buy premium + GST 18% on
           (brokerage + exchange + SEBI), rounded to the paisa.
  'dated'  the rates in force on the trade date: STT on option sells 0.05% (to 31 Mar 2023), 0.0625% (1 Apr 2023 -
           30 Sep 2024), 0.1% (1 Oct 2024 - 31 Mar 2026, Budget 2024), 0.15% (from 1 Apr 2026, Budget 2026); NSE
           exchange charge 0.053% (to 2022), 0.0495% (2023 - Sep 2024), 0.03503% from 1 Oct 2024 (SEBI 'true to label');
           BSE 0.05% before Oct 2024, 0.0325% after; IPFT 0.0005% (NSE); SEBI, stamp, GST and brokerage as above.
           (Exchange rates before Oct 2024 are approximate; they move the cost by < Rs 1 a lot on a Rs 100 option.)
  'flat'   Rs `per_order` per order (older studies: Rs 20 a side + no statutory).
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import date
from decimal import ROUND_HALF_EVEN, Decimal

import numpy as np

from . import config as C

CENT = Decimal("0.01")


# ------------------------------------------------------------------------------------------------ price helpers
def to_paise(p):
    return np.rint(np.asarray(p, dtype=np.float64) * 100).astype(np.int64)


def adverse_bps(p, bps, buy):
    """Px.adverse: p*(1 +- bps/1e4) rounded half-even to the paisa, exactly (integer arithmetic). p <= 0 unchanged;
    a result <= 0 becomes 0.01."""
    p = np.asarray(p, dtype=np.float64)
    P = to_paise(p)
    num = P * (10000 + bps if buy else 10000 - bps)                  # in 1e-4 paise
    q, r = np.divmod(num, 10000)
    up = (r > 5000) | ((r == 5000) & (q % 2 == 1))
    out = (q + up) / 100.0
    # a price that is not a whole number of paise (a lock at breakeven-after-charges): exact-binary product, rounded
    frac = np.abs(p * 100 - np.rint(p * 100)) > 1e-7
    if frac.any():
        out = np.where(frac, np.round(p * ((10000 + bps if buy else 10000 - bps) / 10000.0), 2), out)
    out = np.where(out <= 0, 0.01, out)
    return np.where(p <= 0, p, out)


def floor_tick(x):
    t = np.floor(np.asarray(x, dtype=np.float64) / C.TICK + 1e-9) * C.TICK
    return np.rint(t * 100) / 100.0


@dataclass(frozen=True)
class Fills:
    mode: str = "app"            # 'app' | 'liq' | 'flat'
    bps_mkt: int = 5
    bps_stop: int = 10
    pts: float = 0.5             # 'flat' mode: rupees a side
    thin_lots: float = 20.0      # 'liq': 5-minute volume (lots) below which the spread widens

    def buy(self, p, vol5_lots=None):
        if self.mode == "flat":
            return np.asarray(p, dtype=np.float64) + self.pts
        x = adverse_bps(p, self.bps_mkt, True)
        if self.mode == "liq":
            x = x + self._ticks(vol5_lots, p) * C.TICK
        return np.round(x, 2)

    def sell(self, p, vol5_lots=None, stop=False):
        if self.mode == "flat":
            return np.maximum(np.asarray(p, dtype=np.float64) - self.pts, 0.0)
        x = adverse_bps(p, self.bps_stop if stop else self.bps_mkt, False)
        if self.mode == "liq":
            x = np.maximum(x - self._ticks(vol5_lots, p) * (2 if stop else 1) * C.TICK, 0.0)
        return np.round(x, 2)

    def _ticks(self, vol5_lots, p):
        if vol5_lots is None:
            return np.ones_like(np.asarray(p, dtype=np.float64))
        v = np.asarray(vol5_lots, dtype=np.float64)
        return np.where(v >= self.thin_lots, 1.0, np.where(v > 0, 2.0, 4.0))


# ------------------------------------------------------------------------------------------------ charges
def _dn(d):
    return (d - date(1970, 1, 1)).days


_STT = [(_dn(date(2023, 4, 1)), 0.0005), (_dn(date(2024, 10, 1)), 0.000625), (_dn(date(2026, 4, 1)), 0.001), (10 ** 9, 0.0015)]
_NSE = [(_dn(date(2023, 1, 1)), 0.00053), (_dn(date(2024, 10, 1)), 0.000495), (10 ** 9, 0.0003503)]
_BSE = [(_dn(date(2024, 10, 1)), 0.0005), (10 ** 9, 0.000325)]


def _rate(table, dn):
    dn = np.asarray(dn)
    out = np.empty(dn.shape, dtype=np.float64)
    lo = -10 ** 9
    for until, r in table:
        out[(dn >= lo) & (dn < until)] = r
        lo = until
    return out


@dataclass(frozen=True)
class Costs:
    mode: str = "app"            # 'app' | 'dated' | 'flat'
    brokerage: float = 20.0
    per_order: float = 20.0      # 'flat'

    def charge(self, buy, price, qty, dn=None, bse=False):
        """Charges of one order, vectorised. buy: bool; price, qty arrays; dn: day numbers (days since 1970) for 'dated'."""
        price = np.asarray(price, dtype=np.float64)
        v = price * np.abs(np.asarray(qty, dtype=np.float64))
        if self.mode == "flat":
            return np.where(v > 0, self.per_order, 0.0)
        b = v if buy else np.zeros_like(v)
        s = np.zeros_like(v) if buy else v
        if self.mode == "app":
            stt = 0.0015
            exch = (0.000325 if bse else 0.0003553)
        else:
            stt = _rate(_STT, dn)
            exch = _rate(_BSE, dn) if bse else _rate(_NSE, dn) + 0.000005
        txn = (b + s) * exch
        sebi = (b + s) * (10.0 / 1_00_00_000)
        tot = self.brokerage + s * stt + txn + sebi + b * 0.00003 + (self.brokerage + txn + sebi) * 0.18
        return np.where(v > 0, np.round(tot, 2), 0.0)

    def charge_exact(self, buy, price, qty, dn=None, bse=False):
        """Scalar, Decimal-rounded (SandboxCosts to the paisa) - used for parity checks."""
        tot = float(self.charge(buy, np.array([price]), np.array([qty]), None if dn is None else np.array([dn]), bse)[0])
        if self.mode == "flat":
            return tot
        value = price * abs(qty)
        if not value > 0:
            return 0.0
        b = value if buy else 0.0
        s = 0.0 if buy else value
        if self.mode == "app":
            stt, exch = 0.0015, (0.000325 if bse else 0.0003553)
        else:
            stt = float(_rate(_STT, np.array([dn]))[0])
            exch = float((_rate(_BSE, np.array([dn])) if bse else _rate(_NSE, np.array([dn])) + 0.000005)[0])
        txn = (b + s) * exch
        sebi = (b + s) * (10.0 / 1_00_00_000)
        t = self.brokerage + s * stt + txn + sebi + b * 0.00003 + (self.brokerage + txn + sebi) * 0.18
        return float(Decimal(t).quantize(CENT, rounding=ROUND_HALF_EVEN))

    def rt_per_unit(self, e, qty, dn=None, bse=False):
        """ProfitLock.roundTripPerUnit: charges per unit of a buy at e and a sell at its breakeven."""
        buy = self.charge(True, e, qty, dn, bse)
        q = np.asarray(qty, dtype=np.float64)
        first = (buy + self.charge(False, e, qty, dn, bse)) / q
        return (buy + self.charge(False, np.asarray(e) + first, qty, dn, bse)) / q
