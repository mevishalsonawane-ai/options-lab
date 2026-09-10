"""How many lots to trade, and how much capital to hold behind them.

This is the file that decides whether the expiry-day put is a business or an
accident. The strategy's own numbers barely move with any modelling choice:
across every spread regime it returns 10.2%-11.1% a year and wins 97.65% of the
time. What moves by an order of magnitude is what happens on the day it loses.

THE TAIL, computed not sampled. A short put's loss is unbounded and scales
one-for-one with the move past the strike. Per lot of 65, strike 0.75% below a
24,000 forward, against a median credit of Rs 315:

    index move      loss per lot     = this many median wins
      -1.11%           Rs 2,507              9.9      <- worst in 170 sessions
      -3%             Rs 31,343            124
      -6%             Rs 76,131            301
     -13%            Rs 181,982            719       <- a 2020-03-23 repeat

Exchange initial margin is only about Rs 107,000/lot. So margin is NOT the
binding constraint - survival is. Posting bare margin means one bad day is a
forced close plus a debit call.

WHY THIS IS YOURS TO DECIDE. The 170-session sample contains ZERO days that
fell more than 1.11% at this horizon, so every number above is arithmetic on
the payoff, not an observed outcome. No amount of further backtesting can price
it. What it comes down to is a question only the person whose money it is can
answer: what is the worst day you intend to still be solvent after?
"""
from __future__ import annotations

from dataclasses import dataclass

# Measured inputs, all from the 170-session ledger unless noted.
MEDIAN_CREDIT_RS = 315.0        # per lot, 0.75% OTM at 11:00
MEAN_NET_RS = 394.0             # per lot per expiry, quoted spread regime
EXPIRIES_PER_YEAR = 52
EXCHANGE_MARGIN_RS = 107_264    # SPAN + 2% exposure + 2% expiry-day add-on
LOT_SIZE = 65
MAX_LOTS_ON_DEPTH = 2           # median near-ATM top-of-book is 2.0 lots


@dataclass(frozen=True)
class Plan:
    lots: int
    capital_per_lot: float
    total_capital: float
    expected_annual_rs: float
    expected_annual_pct: float
    survives_move_pct: float
    worst_case_rs: float


def loss_at_move(*, move_pct: float, forward: float = 24_000.0,
                 otm_pct: float = 0.0075, credit_rs: float = MEDIAN_CREDIT_RS,
                 lot_size: int = LOT_SIZE, lots: int = 1) -> float:
    """Rupee loss on one position if the index settles `move_pct` lower.

    Positive return value means a loss. Pure payoff arithmetic - no sampling,
    because the sample contains no such day.
    """
    strike = forward * (1.0 - otm_pct)
    settlement = forward * (1.0 + move_pct)
    intrinsic = max(strike - settlement, 0.0)
    return max(0.0, (intrinsic * lot_size * lots) - (credit_rs * lots))


def capital_needed(*, survive_move_pct: float, lots: int = 1) -> float:
    """Capital to hold one position through a `survive_move_pct` day intact:
    exchange margin, plus enough free cash to absorb the loss itself."""
    return (EXCHANGE_MARGIN_RS * lots
            + loss_at_move(move_pct=survive_move_pct, lots=lots))


# --- the policy ------------------------------------------------------------
#
# DEFAULT: size so the position survives a -6% expiry-day settlement intact.
#
# Why -6% and not something else. It is the point where the two considerations
# cross. Below it you are sizing against ordinary bad days: the worst move in
# 170 sessions was -1.11%, and -3% days are common enough that surviving them
# is table stakes, not a policy. Above it you are sizing against 2008 and
# 2020-03-23 - real events, but ones that cost more than half the return to
# insure against, and which a -13% reserve still does not fully cover (a -20%
# day breaks that too). -6% buys the full depth-ceiling position of 2 lots on
# Rs 4 lakh while keeping the loss recoverable in about 21 weeks of trading.
#
# This is a CHOICE, not a derivation, and it is one keyword away from changing:
# plan_position(capital, survive_move_pct=-0.13) is the conservative variant.
# Neither is safe in the sense of bounded - only the bought 2.5%-OTM wing makes
# the loss finite, and that costs ~27% of the edge.
#
# WHAT SIZING CANNOT DO: it does not shrink the loss. It only decides whether
# the loss is terminal. Every rupee of extra reserve lowers the return
# one-for-one, and past 2 lots it buys no extra position at all, because the
# median near-ATM top of book is 2.0 lots.

DEFAULT_SURVIVE_MOVE_PCT = -0.06


class InsufficientCapital(ValueError):
    """Not enough capital to hold even one lot through the target move."""


def plan_position(total_capital: float, *,
                  survive_move_pct: float = DEFAULT_SURVIVE_MOVE_PCT,
                  max_lots: int = MAX_LOTS_ON_DEPTH) -> Plan:
    """Lots to trade and the tail that leaves you exposed to.

    `survive_move_pct` is negative - a short put is hurt by a FALL. Passing a
    positive value is a sign error and is refused rather than silently sizing
    against a move that cannot hurt this position.
    """
    if survive_move_pct >= 0:
        raise ValueError(
            f"survive_move_pct must be negative (a short put is hurt by a "
            f"fall); got {survive_move_pct:+.3f}"
        )

    per_lot = capital_needed(survive_move_pct=survive_move_pct, lots=1)
    affordable = int(total_capital // per_lot)
    if affordable < 1:
        raise InsufficientCapital(
            f"Rs {total_capital:,.0f} is not enough to hold one lot through a "
            f"{100*survive_move_pct:.1f}% day: that needs Rs {per_lot:,.0f} "
            f"(Rs {EXCHANGE_MARGIN_RS:,.0f} margin plus "
            f"Rs {loss_at_move(move_pct=survive_move_pct):,.0f} of loss)."
        )

    lots = min(affordable, max_lots)
    annual = MEAN_NET_RS * EXPIRIES_PER_YEAR * lots

    return Plan(
        lots=lots,
        capital_per_lot=per_lot,
        total_capital=total_capital,
        expected_annual_rs=annual,
        expected_annual_pct=annual / total_capital,
        survives_move_pct=survive_move_pct,
        worst_case_rs=loss_at_move(move_pct=survive_move_pct, lots=lots),
    )
