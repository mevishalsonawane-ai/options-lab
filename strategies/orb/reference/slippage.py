"""Adverse execution slippage for sandbox fills.

What the sandbox already gets right
-----------------------------------

Not everything here is optimistic, and a blanket percentage applied to every
fill would make the model worse rather than better:

* **MARKET fills already cross the spread.** ``execution_engine`` fills a BUY at
  the ask and a SELL at the bid, which is the single largest real execution cost
  and is modelled correctly. Adding a further haircut on top would double-count
  it.
* **LIMIT fills at the limit price are correct by definition.** A limit order
  fills at its limit or better, never worse. Slippage on a limit fill would
  model something that cannot happen.

Where it is optimistic, and why it matters
------------------------------------------

Two paths fill at exactly the last traded price, for free:

1. **SL and SL-M orders.** A stop triggers precisely when price is moving fast
   against the position -- a gap, a news candle, a liquidation cascade. That is
   the moment when the book is thinnest and the real fill is worst, and the
   sandbox gives it the touched price to the paisa. A backtest whose stops
   always fill exactly at the trigger overstates every strategy that uses
   them, and it overstates them most in exactly the sessions that hurt.
2. **MARKET when the quote carries no bid or ask.** The engine falls back to
   LTP, which hands the fill a free spread crossing.

Direction
---------

Slippage here is **always adverse** and never favourable. Real fills go both
ways, but a paper engine that lets them go both ways cancels the cost out over
a large sample and reports a number that was true on average and useless for
deciding whether a strategy survives. The purpose of this model is to stop
paper results flattering a strategy, so it only ever moves the price the wrong
way: a BUY pays more, a SELL receives less.

Configuration
-------------

``SANDBOX_STOP_SLIPPAGE_BPS`` (default 10, i.e. 0.10%) and
``SANDBOX_SPREAD_FALLBACK_BPS`` (default 5, i.e. 0.05%). Both are basis points
of the fill price. Ten basis points on a stop is deliberately not a
conservative-sounding small number: measured slippage on retail stop-market
orders in fast Indian equity moves is routinely larger, and a model that
understates the cost of stops is the specific failure this module exists to
prevent. An operator who has measured their own fills should set these from
that evidence rather than from the default.

Setting either to ``0`` disables that path, which is how the existing sandbox
tests keep asserting exact fill prices.
"""

from __future__ import annotations

import os
from decimal import Decimal

from utils.logging import get_logger

logger = get_logger(__name__)

#: Price types that reach the market as a stop, and therefore fill in a fast move.
STOP_PRICE_TYPES = frozenset({"SL", "SL-M"})

_BPS = Decimal("10000")


def _bps_env(name: str, default: str) -> Decimal:
    """Read a basis-point setting, falling back rather than raising.

    A malformed slippage setting must not stop the engine, but it must not
    silently become zero either -- zero is "no slippage", which is the
    optimistic answer this module exists to avoid. It falls back to the
    default, which is non-zero.
    """
    raw = os.getenv(name, default).strip()
    try:
        value = Decimal(raw)
    except Exception:
        logger.warning("Slippage setting %s is unreadable (%r); using %s", name, raw, default)
        return Decimal(default)
    if value < 0:
        logger.warning("Slippage setting %s is negative (%s); using 0", name, value)
        return Decimal("0")
    return value


def stop_slippage_bps() -> Decimal:
    return _bps_env("SANDBOX_STOP_SLIPPAGE_BPS", "10")


def spread_fallback_bps() -> Decimal:
    return _bps_env("SANDBOX_SPREAD_FALLBACK_BPS", "5")


def apply_adverse(price: Decimal, action: str, bps: Decimal) -> Decimal:
    """Move ``price`` against the trader by ``bps`` basis points.

    Args:
        price: The optimistic fill price the engine arrived at.
        action: ``BUY`` or ``SELL``.
        bps: Basis points to slip. Zero returns the price unchanged.

    Returns:
        A BUY price raised and a SELL price lowered, rounded to two decimals.
        Never returns a non-positive price: a slip large enough to take a
        SELL to zero would invent a fill nobody could receive, so the price
        floors just above zero instead.
    """
    if bps <= 0 or price <= 0:
        return price

    delta = price * bps / _BPS
    slipped = price + delta if (action or "").upper() == "BUY" else price - delta

    if slipped <= 0:
        return Decimal("0.01")
    return slipped.quantize(Decimal("0.01"))


def clamp_to_limit(price: Decimal, action: str, limit_price: Decimal | None) -> Decimal:
    """Cap a slipped price at the limit the order carries.

    A stop-loss *limit* order has price protection that a stop-loss *market*
    order does not: it will simply not fill below (SELL) or above (BUY) its
    limit. Slipping it past that limit would model a fill the exchange cannot
    give, and would make SL look worse than SL-M -- the opposite of the truth,
    and a conclusion someone might act on.
    """
    if limit_price is None or limit_price <= 0:
        return price
    if (action or "").upper() == "BUY":
        return min(price, limit_price)
    return max(price, limit_price)


def fill_price(
    *,
    price: Decimal,
    action: str,
    price_type: str,
    used_quote_book: bool,
    limit_price: Decimal | None = None,
) -> Decimal:
    """The fill price after modelling execution quality.

    Args:
        price: What the engine decided the fill was worth.
        action: ``BUY`` or ``SELL``.
        price_type: ``MARKET``, ``LIMIT``, ``SL`` or ``SL-M``.
        used_quote_book: Whether a real bid or ask produced ``price``. False
            means the engine fell back to the last traded price and the spread
            was never crossed.
        limit_price: The order's limit, for an SL. Slippage is applied and then
            capped at it, because that protection is the whole difference
            between SL and SL-M.

    Returns:
        The adjusted price. Unchanged for a LIMIT fill and for a MARKET fill
        that already crossed a real book.
    """
    kind = (price_type or "").upper()

    if kind in STOP_PRICE_TYPES:
        slipped = apply_adverse(price, action, stop_slippage_bps())
        # SL-M has no limit and takes the slip in full; SL is capped by its own.
        return clamp_to_limit(slipped, action, limit_price) if kind == "SL" else slipped

    if kind == "MARKET" and not used_quote_book:
        return apply_adverse(price, action, spread_fallback_bps())

    # LIMIT fills at its limit or better, and a MARKET fill that crossed a real
    # book has already paid the spread. Neither is optimistic.
    return price
