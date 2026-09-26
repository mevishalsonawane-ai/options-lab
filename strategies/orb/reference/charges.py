"""What one sandbox fill would really have cost.

The problem this solves
-----------------------

The sandbox books realised P&L as a pure price difference:
``(close - avg) * qty * contract_value`` at ``sandbox/execution_engine.py:986``.
There is no brokerage, no STT, no exchange transaction fee, no SEBI turnover
fee, no stamp duty and no GST anywhere in the package -- a grep for any of them
across ``sandbox/`` returns nothing.

For a delivery strategy holding for weeks that is a rounding error. For an
intraday strategy it is the difference between a paper record that means
something and one that lies: a round trip costs about 0.106% of a small
position, so a strategy averaging a 0.1% gain per trade shows a profit on paper
and loses money live, consistently, on every single trade.

How it is charged
-----------------

Per leg, at fill time, exactly as a broker does it. Charges are not a haircut
applied to a closed position's P&L -- they are debited when each order
executes, so an entry that is still open has already paid its share.

Passing one leg's value as ``buy_value`` (or ``sell_value``) with ``orders=1``
makes the composable schedule in ``portfolio/costs.py`` do the right thing
without special-casing: buy-side stamp duty applies only to buys, sell-side STT
only to sells, and per-order brokerage is counted once. Two legs charged
separately sum to exactly the round-trip figure -- pinned by a test, because it
is the property that makes per-fill charging equivalent to per-trade charging.

Why it reuses portfolio/costs
-----------------------------

That module already models an itemised Indian contract note, already reproduces
a public brokerage calculator to the paisa, and already lets an operator
override a statutory rate that has changed. A second cost model beside it would
drift from it, and the one that drifts is always the one nobody is looking at.
"""

from __future__ import annotations

from decimal import Decimal

from portfolio.costs import CostSchedule, schedule_for_trade
from utils.logging import get_logger

logger = get_logger(__name__)


def schedule_for_order(exchange: str, product: str, symbol: str) -> CostSchedule:
    """The charge schedule this order is priced under."""
    return schedule_for_trade(exchange=exchange, product=product, symbol=symbol)


def charge_for_fill(
    *,
    symbol: str,
    exchange: str,
    product: str,
    action: str,
    quantity: int,
    price: Decimal | float | str,
    contract_value: Decimal | float | str = 1.0,
) -> Decimal:
    """Total statutory and brokerage cost of one executed leg, in rupees.

    Args:
        symbol: OpenAlgo symbol, used to tell an option from a future.
        exchange: OpenAlgo exchange code.
        product: ``MIS``, ``CNC`` or ``NRML``. Selects intraday or delivery on a
            cash venue, and is ignored on a derivatives venue.
        action: ``BUY`` or ``SELL``. Decides which single-sided charges apply.
        quantity: Filled quantity, always positive.
        price: Fill price.
        contract_value: Multiplier for instruments not quoted one-to-one, the
            same field the sandbox P&L uses.

    Returns:
        A non-negative ``Decimal``. Zero when the leg has no value, and zero
        rather than an exception if the schedule cannot be built -- a cost model
        that raises would turn a priced fill into a failed one, and an
        understated cost is a smaller error than a fill that never happened.
    """
    try:
        qty = abs(int(quantity))
        value = Decimal(str(price)) * qty * Decimal(str(contract_value))
    except (TypeError, ValueError, ArithmeticError):
        logger.warning(
            "Could not value a fill for charges: %s %s qty=%r price=%r",
            action,
            symbol,
            quantity,
            price,
        )
        return Decimal("0.00")

    if value <= 0:
        return Decimal("0.00")

    try:
        schedule = schedule_for_order(exchange, product, symbol)
    except Exception:
        logger.exception("Could not build a charge schedule for %s on %s", symbol, exchange)
        return Decimal("0.00")

    leg = float(value)
    is_buy = (action or "").upper() == "BUY"
    total = schedule.charge(
        buy_value=leg if is_buy else 0.0,
        sell_value=0.0 if is_buy else leg,
        orders=1,
    )
    return Decimal(str(round(total, 2)))


def breakdown_for_fill(
    *,
    symbol: str,
    exchange: str,
    product: str,
    action: str,
    quantity: int,
    price: Decimal | float | str,
    contract_value: Decimal | float | str = 1.0,
) -> dict[str, float]:
    """The itemised contract note for one leg, for logs and the UI.

    Same inputs as :func:`charge_for_fill`; returns every line rather than the
    total, so an operator can see which charge dominates instead of being told
    a number to trust.
    """
    qty = abs(int(quantity))
    value = float(Decimal(str(price)) * qty * Decimal(str(contract_value)))
    if value <= 0:
        return {}

    schedule = schedule_for_order(exchange, product, symbol)
    is_buy = (action or "").upper() == "BUY"
    out = schedule.breakdown(
        buy_value=value if is_buy else 0.0,
        sell_value=0.0 if is_buy else value,
        orders=1,
    )
    out["schedule"] = schedule.name
    return out
