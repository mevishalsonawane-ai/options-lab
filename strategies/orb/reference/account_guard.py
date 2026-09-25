"""Account-wide pre-trade limits: the gate a strategy order passes before dispatch.

Why this exists
---------------

``services/agent/safety/risk.py`` is the only real pre-trade guard in the
platform, and it runs for ``/agent`` tool calls only. Everything else --
``/api/v1/placeorder``, ``/api/v1/placesmartorder``, webhooks, Flow, and the
hosted Python strategies -- reaches the broker with no quantity cap, no notional
cap, no funds check and no kill switch. ``validate_smart_order`` checks that
fields are present and enums are spelled correctly, and that is all.

That is survivable when a human is typing each order. It is not survivable for a
fully automatic system, where the thing deciding to trade is a model that can be
wrong in novel ways and will keep being wrong at machine speed.

What this module does and does not do
-------------------------------------

It answers one question: *given the account as it stands right now, may this
order be sent?* It does not decide what to trade, does not size positions, and
does not manage stops -- ``services/risk/position.py`` and ``aggregate.py``
already own per-position and per-basket risk. This is the layer above both: the
limits that apply to the account as a whole, across every strategy at once.

No I/O, by invariant
--------------------

Like the rest of ``services/risk/``, this performs no I/O of any kind. No
database, no broker, no market data, no clock, no logging. The caller gathers
the account snapshot and passes it in; the guard returns a verdict. That is what
lets the whole thing be exercised with no platform running::

    verdict = check_account_order(
        intent=OrderIntent(symbol="INFY", exchange="NSE", action="BUY",
                           quantity=10, product="MIS",
                           price=Decimal("1500")),
        state=AccountState(open_positions={}, trades_today=0,
                           realised_pnl_today=Decimal("0"),
                           unrealised_pnl=Decimal("0"),
                           starting_capital=Decimal("100000")),
        limits=AccountRiskLimits(max_concurrent_positions=3, max_trades_today=10),
    )
    if not verdict.allowed:
        refuse(verdict.reason, verdict.detail)

Which way a check fails when it cannot decide
---------------------------------------------

The agent guard fails *open* on affordability and notional when no reference
price arrived, and its stated reason is that refusing a human-approved order
because a quote lookup hiccuped is worse than allowing it -- the broker performs
its own margin check anyway.

**That reasoning does not carry over here, because there is no human approval
step.** Nothing has looked at this order, and nothing will. A missing price on a
fully automatic path means the system is operating blind, and an order sized
against an unknown price is exactly the kind of mistake that empties an account
in one afternoon. Every check in this module that cannot decide **refuses**, and
says so in ``Verdict.detail``.

The one deliberate exception is risk-reducing orders. See ``reduces_exposure``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from decimal import Decimal
from pathlib import Path

from services.risk.options import bounding_quantity, parse_option

# Reasons a verdict can carry. Strings rather than an enum so a refusal survives
# a trip through JSON, a log line and an audit row without a decode step.
REASON_KILL_SWITCH = "kill_switch"
REASON_TRADING_DISABLED = "trading_disabled"
REASON_MAX_POSITIONS = "max_concurrent_positions"
REASON_MAX_TRADES_TODAY = "max_trades_today"
REASON_DAILY_LOSS = "daily_loss_limit"
REASON_MAX_DRAWDOWN = "max_drawdown"
REASON_ORDER_VALUE = "max_order_value"
REASON_SYMBOL_EXPOSURE = "max_symbol_exposure"
REASON_NO_PRICE = "no_reference_price"
REASON_SQUARE_OFF_WINDOW = "past_entry_cutoff"
REASON_UNBOUNDED_LOSS = "unbounded_loss"


@dataclass(frozen=True)
class OrderIntent:
    """One order somebody wants to send.

    ``price`` is the reference price used for notional checks: the limit price
    for a LIMIT order, otherwise the last traded price the caller looked up. It
    is ``None`` only when the caller could not obtain one, which this guard
    treats as a refusal rather than a warning.
    """

    symbol: str
    exchange: str
    action: str  # BUY or SELL
    quantity: int
    product: str
    price: Decimal | None = None


@dataclass(frozen=True)
class AccountState:
    """Everything about the account the limits are measured against.

    Assembled by the caller immediately before dispatch. Deliberately a plain
    snapshot: the guard never asks for more than it was given, so a stale field
    is the caller's bug and not a hidden fetch.

    Attributes:
        open_positions: Net quantity per ``"SYMBOL:EXCHANGE"``. Positive is
            long, negative is short, and a flat symbol is absent rather than
            present with a zero -- so ``len()`` is the concurrent position count.
        trades_today: Orders already sent today, across every strategy.
        realised_pnl_today: Booked profit and loss for the session, in rupees.
        unrealised_pnl: Mark to market on what is still open, in rupees.
        starting_capital: Account value at the start of the session, the
            denominator for drawdown.
        minutes_to_square_off: Minutes until the exchange auto-square-off for
            intraday products. ``None`` when the caller does not track it.
        equity: Account value now, in rupees.
        peak_equity: The highest ``equity`` seen since tracking began, in
            rupees.
    """

    open_positions: dict[str, int] = field(default_factory=dict)
    trades_today: int = 0
    realised_pnl_today: Decimal = Decimal("0")
    unrealised_pnl: Decimal = Decimal("0")
    starting_capital: Decimal = Decimal("0")
    minutes_to_square_off: int | None = None
    #: Account value now and its highest value since tracking began, both
    #: rupees. The daily leg measures today's P&L; these measure the drawdown
    #: across days, which is the limit the operator actually set. Zero means
    #: "not supplied" and the leg is not evaluated.
    equity: Decimal = Decimal("0")
    peak_equity: Decimal = Decimal("0")

    def net_position(self, symbol: str, exchange: str) -> int:
        """Signed net quantity held in one instrument. Zero when flat."""
        return self.open_positions.get(f"{symbol}:{exchange}", 0)

    @property
    def total_pnl(self) -> Decimal:
        """Booked plus mark to market.

        Drawdown is measured against this rather than realised alone: a position
        sitting on a large open loss has already lost the money, and waiting for
        it to be booked before counting it is how a limit gets discovered too
        late to matter.
        """
        return self.realised_pnl_today + self.unrealised_pnl


@dataclass(frozen=True)
class AccountRiskLimits:
    """An immutable snapshot of every account-wide limit the guard enforces.

    Passing an explicit instance is what keeps the guard free of configuration
    lookups. A caller that wants operator settings reads them and builds one.

    A limit of ``0`` means "not configured" for counts and money amounts, and is
    treated as no limit -- except ``max_concurrent_positions`` and
    ``max_trades_today``, where zero genuinely means "open nothing", which is a
    useful thing to be able to say.
    """

    trading_enabled: bool = True
    kill_switch_engaged: bool = False
    kill_switch_file: str = "kill_switch"

    max_concurrent_positions: int = 3
    max_trades_today: int = 10
    max_daily_loss: Decimal = Decimal("0")
    max_drawdown_pct: Decimal = Decimal("0")
    max_order_value: Decimal = Decimal("0")
    max_symbol_exposure: Decimal = Decimal("0")
    #: Minutes before the square-off after which no new risk may be taken on.
    #: The square-off itself is the caller's to determine -- it is a clock and an
    #: exchange calendar, neither of which belongs here -- and the caller is also
    #: where an option's expiry day makes that time earlier. So this stays one
    #: number and the rule stays monotone: an earlier square-off means an earlier
    #: cutoff, never a later one.
    entry_cutoff_minutes: int = 0

    #: Permit selling an option with no bounding long leg. Off by default and
    #: deliberately awkward to turn on: a naked short option is the one position
    #: in this platform whose worst case is not a number, and every other limit
    #: here is expressed in rupees that a naked short does not have.
    allow_unbounded_loss: bool = False

    @property
    def kill_switch_path(self) -> Path:
        return Path(self.kill_switch_file)


@dataclass(frozen=True)
class Verdict:
    """The answer. ``allowed`` is the whole decision; the rest is for the audit."""

    allowed: bool
    reason: str = ""
    detail: str = ""

    @property
    def refused(self) -> bool:
        return not self.allowed


ALLOWED = Verdict(allowed=True)


def reduces_exposure(intent: OrderIntent, state: AccountState) -> bool:
    """Whether this order can only make the account's position smaller.

    This is the single most consequential predicate in the module, because it
    decides what a breached limit is allowed to stop. A limit that blocks new
    entries protects the account. A limit that blocks a stop-loss traps it: the
    daily loss cap trips, the guard refuses everything, and the losing position
    that tripped it stays open with nothing able to close it. The limit would
    then cause the loss it exists to prevent.

    So risk-reducing orders bypass the limit checks entirely, and everything
    else must pass them.

    The subtlety is that "SELL" does not mean "reducing". A SELL against no
    position opens a short, and a SELL larger than a long closes it and opens a
    short with the remainder -- new exposure wearing the costume of an exit.

    Args:
        intent: The order being considered.
        state: The account as it stands, for the current net position.

    Returns:
        True only when the order strictly reduces the open position in this
        instrument and cannot leave new exposure behind.
    """
    return reducible_quantity(intent, state) == intent.quantity


def reducible_quantity(intent: OrderIntent, state: AccountState) -> int:
    """How many units of this order would strictly reduce the open position.

    ``reduces_exposure`` is the boolean form of this: an order is exempt only
    when *every* unit of it is closing something. This function exists because
    the interesting case is the one in between.

    A SELL of 150 against a long of 100 is 100 units of exit and 50 units of a
    brand-new short. Treating the whole order as an exit would let a reversal
    walk through a breached limit wearing an exit's clothes. Treating the whole
    order as an entry is worse: the daily loss cap trips, this order is refused
    outright, and the 100-lot long that tripped it now has nothing able to close
    it.

    So the guard refuses the oversized order but reports how much of it *was*
    legitimate, and the caller can re-send that part. A dead end becomes a
    recoverable one, and the audit says exactly what happened instead of blaming
    whichever unrelated limit happened to be checked first.

    Returns:
        Units of ``intent.quantity`` that close existing exposure. ``0`` when
        the order only opens or increases a position, and ``intent.quantity``
        when the whole order is an exit.
    """
    net = state.net_position(intent.symbol, intent.exchange)

    # Flat: nothing to reduce, so any order here is opening one.
    if net == 0:
        return 0

    closing = (net > 0 and intent.action == "SELL") or (net < 0 and intent.action == "BUY")
    if not closing:
        return 0

    # Capped at the position: units beyond it are a new position on the far side.
    return min(intent.quantity, abs(net))


def check_account_order(
    *,
    intent: OrderIntent,
    state: AccountState,
    limits: AccountRiskLimits,
    kill_switch_file_exists: bool = False,
) -> Verdict:
    """Decide whether one order may be dispatched.

    Order of checks is fixed and the order is the point. The kill switch is
    first because it must win over everything, including the exemption for
    risk-reducing orders: an operator who has hit the switch wants the system to
    stop touching the market, and will close positions by hand.

    Args:
        intent: The order being considered.
        state: Account snapshot gathered by the caller.
        limits: The limits in force.
        kill_switch_file_exists: Whether ``limits.kill_switch_file`` is present
            on disk. Passed in rather than checked here, because this module
            does no I/O; the caller stats the file.

    Returns:
        A ``Verdict``. ``allowed`` is the decision; ``reason`` is a stable
        machine-readable string and ``detail`` is for a human reading the audit.
    """
    # 1. Kill switch, from either source. Beats every exemption below.
    if limits.kill_switch_engaged or kill_switch_file_exists:
        source = "file" if kill_switch_file_exists else "setting"
        return Verdict(False, REASON_KILL_SWITCH, f"Kill switch engaged via {source}")

    # 2. Master switch.
    if not limits.trading_enabled:
        return Verdict(False, REASON_TRADING_DISABLED, "Trading is disabled")

    # 3. Getting out is always permitted. Everything below this line is a limit
    #    on taking on MORE risk, and none of them may stand between a position
    #    and its exit.
    if reduces_exposure(intent, state):
        return ALLOWED

    # 4. A short option with no wing. This sits above the rupee limits on
    #    purpose: every limit below is a number, and the whole point of this one
    #    is that a naked short option does not have a number. max_order_value
    #    sees the credit received -- a few thousand rupees -- and would wave
    #    through a position that can lose the account.
    verdict = _check_unbounded_loss(intent=intent, state=state, limits=limits)
    if verdict.refused:
        return verdict

    # 5. From here the order opens or increases exposure, so the limits apply.
    verdict = _check_exposure_limits(intent=intent, state=state, limits=limits)
    if verdict.refused:
        return _note_recoverable_exit(verdict, intent, state)
    return verdict


def _check_unbounded_loss(
    *,
    intent: OrderIntent,
    state: AccountState,
    limits: AccountRiskLimits,
) -> Verdict:
    """Refuse selling an option that nothing caps.

    Only reached for orders that increase exposure -- buying a short leg back is
    already permitted by the exit exemption above, and must stay permitted, or a
    naked position could not be closed.

    A short is allowed when the account already holds enough of a capping long
    leg: same underlying, same expiry, a lower strike for a put and a higher one
    for a call. That forces a spread to be legged wing-first, which is the safe
    order -- if the second leg never fills, the account is left holding
    protection rather than holding risk.
    """
    if limits.allow_unbounded_loss:
        return ALLOWED
    if intent.action.upper() != "SELL":
        return ALLOWED

    leg = parse_option(intent.symbol, intent.exchange)
    if leg is None:
        return ALLOWED

    # Selling down an existing long in the same contract is not a short.
    already_long = state.open_positions.get(f"{intent.symbol}:{intent.exchange}", 0)
    opening_short = intent.quantity - max(already_long, 0)
    if opening_short <= 0:
        return ALLOWED

    covered = bounding_quantity(leg, intent.exchange, state.open_positions)
    if covered >= opening_short:
        return ALLOWED

    side = "put" if leg.is_put else "call"
    direction = "below" if leg.is_put else "above"
    return Verdict(
        False,
        REASON_UNBOUNDED_LOSS,
        (
            f"Selling {opening_short} of {intent.symbol} opens a naked short {side}. "
            f"Its loss is unbounded, and no limit here is denominated in a way that "
            f"can cap it. Buy a {leg.underlying} {leg.expiry_code} {side} "
            f"{direction} {leg.strike} first, or set allow_unbounded_loss."
        ),
    )


def _note_recoverable_exit(verdict: Verdict, intent: OrderIntent, state: AccountState) -> Verdict:
    """Say so when a refused order contained a legitimate exit.

    An order that closes 100 and opens 50 is refused as an entry, which is
    correct, but a bare "daily loss limit reached" tells the operator nothing
    about the 100-lot position still sitting open underneath it. Naming the
    splittable quantity turns a dead end into an instruction.

    The reason code is left alone deliberately: the breached limit is still the
    reason this was refused, and callers keying on it must keep working.
    """
    splittable = reducible_quantity(intent, state)
    if splittable == 0:
        return verdict
    return Verdict(
        allowed=False,
        reason=verdict.reason,
        detail=(
            f"{verdict.detail}. Note: {splittable} of {intent.quantity} would have "
            f"closed existing exposure -- resend that quantity alone to exit"
        ),
    )


def _check_exposure_limits(
    *,
    intent: OrderIntent,
    state: AccountState,
    limits: AccountRiskLimits,
) -> Verdict:
    """The limits that apply only to orders taking on more risk.

    Split out from ``check_account_order`` so the exemption above it reads as
    one decision rather than being buried among the checks it skips.

    The order within is not arbitrary. ``check_order`` engages the kill switch on
    a ``max_drawdown`` verdict and on nothing else, so a limit that carries no
    consequence must never be allowed to answer in place of one that does. The
    entry cutoff is the clearest case -- it is a clock reading, it refuses every
    order after a certain minute, and it means only "not now" -- so it is
    evaluated last, after every limit that says something about the account's
    condition. Evaluated first, it reported "past_entry_cutoff" for a blown
    account and the switch was never thrown.
    """
    if limits.max_daily_loss > 0 and -state.total_pnl >= limits.max_daily_loss:
        return Verdict(
            False,
            REASON_DAILY_LOSS,
            f"Loss {-state.total_pnl} has reached the daily cap {limits.max_daily_loss}",
        )

    if limits.max_drawdown_pct > 0 and state.starting_capital > 0:
        drawdown_pct = (-state.total_pnl / state.starting_capital) * Decimal("100")
        if drawdown_pct >= limits.max_drawdown_pct:
            return Verdict(
                False,
                REASON_MAX_DRAWDOWN,
                f"Drawdown {drawdown_pct:.2f}% has reached {limits.max_drawdown_pct}%",
            )

    if limits.max_drawdown_pct > 0 and state.peak_equity > 0 and state.equity > 0:
        from_peak_pct = (state.peak_equity - state.equity) / state.peak_equity * Decimal("100")
        if from_peak_pct >= limits.max_drawdown_pct:
            return Verdict(
                False,
                REASON_MAX_DRAWDOWN,
                f"Equity {state.equity} is {from_peak_pct:.2f}% below its peak "
                f"{state.peak_equity}, limit {limits.max_drawdown_pct}%",
            )

    # A new instrument consumes a position slot; adding to one already held does
    # not, so the cap counts instruments rather than orders.
    already_held = state.net_position(intent.symbol, intent.exchange) != 0
    if not already_held and len(state.open_positions) >= limits.max_concurrent_positions:
        return Verdict(
            False,
            REASON_MAX_POSITIONS,
            f"{len(state.open_positions)} positions open, cap is {limits.max_concurrent_positions}",
        )

    if state.trades_today >= limits.max_trades_today:
        return Verdict(
            False,
            REASON_MAX_TRADES_TODAY,
            f"{state.trades_today} trades today, cap is {limits.max_trades_today}",
        )

    # Notional checks need a price. On an automatic path a missing price is not
    # a hiccup to shrug at, it means we are sizing blind.
    needs_price = limits.max_order_value > 0 or limits.max_symbol_exposure > 0
    if needs_price and intent.price is None:
        return Verdict(
            False,
            REASON_NO_PRICE,
            "No reference price available and a notional limit is configured",
        )

    if intent.price is not None:
        order_value = intent.price * intent.quantity

        if limits.max_order_value > 0 and order_value > limits.max_order_value:
            return Verdict(
                False,
                REASON_ORDER_VALUE,
                f"Order value {order_value} exceeds cap {limits.max_order_value}",
            )

        if limits.max_symbol_exposure > 0:
            held_value = abs(state.net_position(intent.symbol, intent.exchange)) * intent.price
            if held_value + order_value > limits.max_symbol_exposure:
                return Verdict(
                    False,
                    REASON_SYMBOL_EXPOSURE,
                    f"Exposure in {intent.symbol} would reach "
                    f"{held_value + order_value}, cap is {limits.max_symbol_exposure}",
                )

    # Last, per the docstring: "not now" must not answer in place of a limit
    # that says something about the account's condition. ``minutes_to_square_off``
    # is the caller's to compute, and is ``None`` for a product with no
    # square-off, which leaves this unevaluated rather than guessed at.
    if limits.entry_cutoff_minutes > 0 and state.minutes_to_square_off is not None:
        if state.minutes_to_square_off <= limits.entry_cutoff_minutes:
            return Verdict(
                False,
                REASON_SQUARE_OFF_WINDOW,
                f"{state.minutes_to_square_off} min to square-off, "
                f"cutoff is {limits.entry_cutoff_minutes}",
            )

    return ALLOWED
