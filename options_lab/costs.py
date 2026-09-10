"""Options transaction costs. Rates verified 2026-09-07.

This module imports NOTHING from `nifty_trading_bot`. That is a hard rule with
a specific cause: `strategy_lab/costs.py:13` calls `set_cost_mode()` at IMPORT
TIME, mutating a module-level global in the shared `engine.costs`, so two cost
regimes alive in one interpreter race silently - no exception, just wrong
numbers. Here the regime is an explicit argument and there is no module global.

There is deliberately NO zero-cost regime. `nifty_trading_bot/fo/` has no cost
model at all (grep for slippage|brokerage|stt|commission|spread returns
nothing), and that single omission makes 12-48x errors on its futures results.
Once a zero-cost path exists, some run uses it and gets reported.

Two facts that decide everything downstream:

  - Brokerage is FLAT Rs 20/order, so the explicit fraction is premium-dependent:
    0.437% on a Rs 785 BANKNIFTY option, 0.600% on a Rs 200 NIFTY one, 2.05% on
    a Rs 40 one. Calibrating on an expensive option flatters a NIFTY project.
  - The SPREAD is 70-89% of the total and is an assumption, not data. No free
    source carries intraday quotes. Every expectancy number inherits that error,
    and it is larger than every other modelling choice combined.
"""
from __future__ import annotations

from dataclasses import dataclass

# --- statutory rates, each dated ------------------------------------------
BROKERAGE_PER_ORDER = 20.0      # flat, typical discount broker
ORDERS_PER_ROUND_TRIP = 2
STT_SELL_PCT = 0.0015           # 0.15% of premium, SELL leg only
EXCHANGE_PCT = 0.0003553        # 0.03553% of premium, both legs
SEBI_PER_CRORE = 10.0
STAMP_BUY_PCT = 0.00003         # 0.003% of premium, BUY leg only
GST_PCT = 0.18                  # on brokerage + exchange + SEBI

# --- spread regimes, as a fraction of premium crossed ONCE -----------------
# roll   : in-session Roll effective-spread estimator, ATM, 1.5-3.5 option pts
# quoted : NSE top-of-book median for the 20 most active strikes (0.85-1.00%)
# stress : sizing beyond top-of-book depth, which was ONE lot at the ATM
SPREAD_PCT = {"roll": 0.0030, "quoted": 0.0090, "stress": 0.0200}
REGIMES = tuple(SPREAD_PCT)

# --- the spread is NOT a flat percentage; it has a fixed floor -------------
# Fitted on a real book across a 96x premium range:
#     spread per unit = 0.162 + 0.00292 x premium      (R-squared 0.974)
# The intercept is what a percentage model misses. On a Rs 785 option the
# proportional term dominates and 0.90% is a fine approximation; on the Rs 5
# expiry-day put that this project actually trades, 0.90% is Rs 0.044 - BELOW
# the Rs 0.05 minimum tick, i.e. narrower than the market can quote.
#
# The fit is IN-SESSION, so it corresponds to the `roll` regime. The wider
# regimes scale it by the existing SPREAD_PCT ratios so nothing else moves.
#
# CAVEAT: fitted on BANKNIFTY quotes between Rs 10 and Rs 960 and extrapolated
# DOWN to Rs 5. No free source carries intraday quotes, so this is the best
# estimate available - but it is an assumption, and it is the single largest
# input to the expiry-put strategy's reported profit.
TICK_SIZE = 0.05
SPREAD_LAW_INTERCEPT = 0.162
SPREAD_LAW_SLOPE = 0.00292
REGIME_SCALE = {r: SPREAD_PCT[r] / SPREAD_PCT["roll"] for r in SPREAD_PCT}


def spread_per_unit(*, premium: float, regime: str = "quoted") -> float:
    """Absolute bid-ask spread per unit, floored at one tick."""
    if regime not in SPREAD_PCT:
        raise ValueError(
            f"unknown cost regime {regime!r}; expected one of {REGIMES}."
        )
    law = SPREAD_LAW_INTERCEPT + SPREAD_LAW_SLOPE * max(premium, 0.0)
    return max(TICK_SIZE, REGIME_SCALE[regime] * law)


@dataclass(frozen=True)
class Charges:
    brokerage: float
    stt: float
    exchange: float
    sebi: float
    stamp: float
    gst: float
    spread: float
    premium_notional: float

    @property
    def total(self) -> float:
        return (self.brokerage + self.stt + self.exchange + self.sebi
                + self.stamp + self.gst + self.spread)

    @property
    def fraction_of_premium(self) -> float:
        return self.total / self.premium_notional if self.premium_notional else 0.0


def _regime_for(spread_pct: float) -> str:
    """Recover the regime name from its legacy percentage, so the internal
    plumbing keeps working while the spread itself uses the floored law."""
    for name, pct in SPREAD_PCT.items():
        if pct == spread_pct:
            return name
    raise ValueError(f"no regime matches spread_pct {spread_pct}")


def _charges(premium: float, lot_size: int, lots: int, spread_pct: float) -> Charges:
    qty = lot_size * lots
    notional = premium * qty

    brokerage = BROKERAGE_PER_ORDER * ORDERS_PER_ROUND_TRIP
    stt = STT_SELL_PCT * notional
    exchange = EXCHANGE_PCT * notional * 2
    sebi = SEBI_PER_CRORE * (notional * 2) / 1e7
    stamp = STAMP_BUY_PCT * notional
    gst = GST_PCT * (brokerage + exchange + sebi)
    # Crossed on entry and again on exit: half-spread each way = one full
    # spread. `spread_pct` of 0 means "explicit charges only".
    spread = 0.0 if spread_pct == 0.0 else spread_per_unit(
        premium=premium, regime=_regime_for(spread_pct)) * qty

    return Charges(brokerage=brokerage, stt=stt, exchange=exchange, sebi=sebi,
                   stamp=stamp, gst=gst, spread=spread, premium_notional=notional)


def explicit(*, premium: float, lot_size: int, lots: int = 1) -> Charges:
    """Statutory and brokerage charges only, no spread."""
    return _charges(premium, lot_size, lots, spread_pct=0.0)


def round_trip(*, premium: float, lot_size: int, lots: int = 1,
               regime: str) -> Charges:
    """Full round-trip cost including a crossed spread under a named regime."""
    if regime not in SPREAD_PCT:
        raise ValueError(
            f"unknown cost regime {regime!r}; expected one of {REGIMES}. "
            "There is no zero-cost regime by design."
        )
    return _charges(premium, lot_size, lots, SPREAD_PCT[regime])


def breakeven_index_points(*, premium: float, lot_size: int, lots: int = 1,
                           regime: str, delta: float) -> float:
    """Round-trip cost expressed as a move in the UNDERLYING, in index points.

    An ATM option moves ~0.5 points per index point, so cost roughly doubles in
    index terms. Against BANKNIFTY's 12.5-point mean absolute 1-minute move,
    this is the number that decides whether any intraday edge is reachable.
    """
    if not delta:
        raise ValueError("delta must be non-zero to express cost in index points")
    rt = round_trip(premium=premium, lot_size=lot_size, lots=lots, regime=regime)
    return rt.total / (lot_size * lots * abs(delta))


def sell_to_settle(*, premium: float, lot_size: int, lots: int = 1,
                   regime: str) -> Charges:
    """Cost of SELLING an option and holding it to cash settlement.

    Materially cheaper than a round trip, and the difference is the entire
    margin on the expiry-day put: squaring off at 15:29 instead of settling
    drops that strategy's win rate from 100% to 95.9%, because a second
    Rs 20 order plus a second crossed spread is more than the edge.

    Charged here: ONE brokerage order, STT on the sell side, exchange and
    SEBI on one side only, GST on those, and HALF a spread (crossed once, on
    entry). Not charged: stamp duty (buy side only), and settlement STT -
    the 0.125%-of-intrinsic exercise charge falls on the option BUYER, not
    the writer.
    """
    if regime not in SPREAD_PCT:
        raise ValueError(
            f"unknown cost regime {regime!r}; expected one of {REGIMES}. "
            "There is no zero-cost regime by design."
        )
    qty = lot_size * lots
    notional = premium * qty

    brokerage = BROKERAGE_PER_ORDER              # one order, not two
    stt = STT_SELL_PCT * notional
    exchange = EXCHANGE_PCT * notional           # one side
    sebi = SEBI_PER_CRORE * notional / 1e7
    stamp = 0.0                                  # buy side only
    gst = GST_PCT * (brokerage + exchange + sebi)
    spread = spread_per_unit(premium=premium, regime=regime) / 2.0 * qty

    return Charges(brokerage=brokerage, stt=stt, exchange=exchange, sebi=sebi,
                   stamp=stamp, gst=gst, spread=spread,
                   premium_notional=notional)
