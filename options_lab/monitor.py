"""Health checks against the expiry put's documented kill conditions.

THIS IS NOT A LEARNER, deliberately. It adapts nothing and fits nothing.

A bandit allocates among alternatives by measured performance. That needs
multiple candidates, tunable parameters, and data volume. This strategy has one
variant, no fitted parameter, and 52 trades a year. The old three-layer learner
in the parent repo had 49 symbols trading daily and still had to be seeded with
23 phantom prior wins because real data was too sparse to weight on - and every
strategy it weighted was negative out of sample, so it was allocating among
losers. Worse, online adaptation would make every future evaluation in-sample
by construction, which is exactly how this project produced a 98.8% win rate.

What actually ends this strategy is a precondition quietly ceasing to hold, and
until now nothing noticed that. These checks answer one question - are the
conditions it depends on still true? - and they are computed from a trade
ledger, so they run on live fills exactly as they run on backtested ones.

Every threshold below is a measured number, not a guess. Sources are named.
"""
from __future__ import annotations

from dataclasses import dataclass

import pandas as pd

# Below this the flat Rs 20/order brokerage is a quarter of gross and the trade
# stops being worth doing. Measured median credit is Rs 4.85/unit.
CREDIT_FLOOR = 2.00
CREDIT_WARN = 3.00

# Measured margin of safety over 170 sessions: median 224.6 index points,
# p5 78.0, p1 36.1, minimum 5.2 (2024-10-03). The settlement reconstruction
# itself has a standard deviation of about 6.3 points, so a minimum inside
# that band means the win is inside the measurement error.
MARGIN_WARN_PTS = 40.0
MARGIN_FAIL_PTS = 15.0

# Fraction of the strike distance a fall may reach before it is a warning.
REGIME_WARN_FRACTION = 0.80
REGIME_FAIL_FRACTION = 1.00

# The lot size the economics were measured at. Flat brokerage does not scale,
# so this IS the economics: at the lot of 25 that ruled most of 2024 the same
# trade won 94.1%, not 100%.
ASSUMED_LOT = 65

_RANK = {"pass": 0, "warn": 1, "fail": 2}


@dataclass(frozen=True)
class Check:
    name: str
    status: str          # pass | warn | fail
    measured: str
    threshold: str
    why: str

    def format(self) -> str:
        mark = {"pass": "PASS", "warn": "WARN", "fail": "FAIL"}[self.status]
        return f"[{mark}] {self.name:<22} {self.measured:<28} vs {self.threshold}"


def _require(trades: pd.DataFrame) -> None:
    if trades is None or len(trades) == 0:
        raise ValueError(
            "empty trade ledger; no trades is not the same as no problems"
        )


def check_credit(trades: pd.DataFrame) -> Check:
    _require(trades)
    med = float(trades["credit"].median())
    status = ("fail" if med < CREDIT_FLOOR
              else "warn" if med < CREDIT_WARN else "pass")
    return Check(
        name="credit level",
        status=status,
        measured=f"median Rs {med:.2f}/unit",
        threshold=f"floor Rs {CREDIT_FLOOR:.2f}, warn below Rs {CREDIT_WARN:.2f}",
        why="below the floor the flat Rs 20/order brokerage is a quarter of "
            "gross and the trade is not worth doing",
    )


def check_lot_size(current: int, *, assumed: int = ASSUMED_LOT) -> Check:
    return Check(
        name="lot size",
        status="pass" if current == assumed else "fail",
        measured=f"{current}",
        threshold=f"{assumed} (what the economics were measured at)",
        why="flat brokerage does not scale with lot size, so a lot change moves "
            "the whole cost fraction; at lot 25 this trade won 94.1%, not 100%",
    )


def check_margin_of_safety(trades: pd.DataFrame) -> Check:
    """How close the winning trades actually came to losing.

    Break-even settlement for a short put is strike minus the credit per unit.
    A 100% win rate says nothing about whether it cleared by 5 points or 500.
    """
    _require(trades)
    breakeven = trades["strike"] - trades["credit"]
    margin = trades["settlement"] - breakeven
    worst = float(margin.min())
    status = ("fail" if worst < MARGIN_FAIL_PTS
              else "warn" if worst < MARGIN_WARN_PTS else "pass")
    return Check(
        name="margin of safety",
        status=status,
        measured=f"min {worst:,.1f} pts, median {float(margin.median()):,.1f}",
        threshold=f"warn below {MARGIN_WARN_PTS:.0f}, fail below "
                  f"{MARGIN_FAIL_PTS:.0f} pts",
        why="the settlement reconstruction has a ~6.3 point standard deviation, "
            "so a margin inside that band means the win is inside the noise",
    )


def check_regime(trades: pd.DataFrame, *, otm_pct: float) -> Check:
    """Are expiry-day falls creeping toward the strike?

    The 170-session record rests on NIFTY never falling 1% from the 11:00 mark.
    Falls approaching the strike distance are the leading indicator of the loss
    that the sample does not contain.
    """
    _require(trades)
    fall = (trades["forward"] - trades["settlement"]) / trades["forward"]
    # Compare each fall to the strike THAT session actually had, not to the
    # nominal parameter. Strikes land on a 50-point grid, so realised distance
    # ranges 0.65%-0.85% against a nominal 0.75%; using the nominal overstates
    # the worst session and understates others.
    distance = (trades["otm_realised"] if "otm_realised" in trades
                else pd.Series(otm_pct, index=trades.index))
    ratios = (fall / distance.where(distance > 0)).dropna()
    worst = float(fall.max())
    ratio = float(ratios.max()) if len(ratios) else float("inf")
    status = ("fail" if ratio >= REGIME_FAIL_FRACTION
              else "warn" if ratio >= REGIME_WARN_FRACTION else "pass")
    return Check(
        name="regime",
        status=status,
        measured=f"worst fall {100*worst:+.3f}% = {ratio:.0%} of its own strike",
        threshold=f"warn at {REGIME_WARN_FRACTION:.0%}, fail at "
                  f"{REGIME_FAIL_FRACTION:.0%} of {100*otm_pct:.2f}%",
        why="the win record is a property of a bull market, not of the payoff; "
            "falls reaching the strike are what turns it over",
    )


def check_variance_premium(trades: pd.DataFrame) -> Check:
    """Is the credit still covering what the index actually does?

    This is the edge itself. Premium selling pays because options are priced
    above realised movement; if that stops, there is nothing left to collect.
    """
    _require(trades)
    # The credit insures only the part of a fall BEYOND the strike. Comparing
    # it to the whole move made a holdout with ZERO in-the-money finishes read
    # as a failure, which is the opposite of the truth.
    paid_out = (trades["intrinsic"] if "intrinsic" in trades
                else (trades["strike"] - trades["settlement"]).clip(lower=0.0))
    realised = float(paid_out.mean())
    credit = float(trades["credit"].mean())
    status = ("fail" if realised > credit
              else "warn" if realised > 0.5 * credit else "pass")
    return Check(
        name="variance premium",
        status=status,
        measured=f"credit Rs {credit:.2f} vs mean payout {realised:.2f} pts",
        threshold="credit must exceed the average realised payout",
        why="premium selling pays because options are dearer than the movement "
            "they insure; when that inverts the edge is gone, not smaller",
    )


def run_checks(trades: pd.DataFrame, *, otm_pct: float,
               lot_size: int) -> list[Check]:
    _require(trades)
    return [
        check_credit(trades),
        check_lot_size(lot_size),
        check_margin_of_safety(trades),
        check_regime(trades, otm_pct=otm_pct),
        check_variance_premium(trades),
    ]


def verdict(checks: list[Check]) -> str:
    """The overall verdict is the WORST individual check, never an average.

    Averaging would let four healthy readings hide one broken precondition,
    and it only takes one to end the strategy.
    """
    if not checks:
        raise ValueError("no checks to summarise")
    return max(checks, key=lambda c: _RANK[c.status]).status
