"""Regime gates: where a signal is allowed to trade, not what it says.

DTE is the most important state variable here, and it is a gate rather than a
signal. For an ATM option, theta as a fraction of premium is 1/(2T) per year and
is VOLATILITY-INDEPENDENT - it depends only on time left. That single fact is
why near-expiry is a different economic regime rather than the same one faster:

    22 DTE  ->  2.3% of premium per day
     1 DTE  -> 50.0% of premium per day

The consequence that matters for a cost-bound strategy: a round-trip spread is
a fixed number of option points, but the time needed to earn it back collapses
into expiry. At 25 DTE a 4-point spread on a 170-point option exceeds an ENTIRE
session's theta; at 1 DTE it is about eighteen minutes of it.
"""
from __future__ import annotations

from datetime import date

import pandas as pd

SESSION_MINUTES = 375                 # 09:15-15:29 inclusive
LIQUID_THRESHOLD = 0.80               # share of session minutes with volume > 0

# Bucket edges sit where the economics change, not on round numbers.
_BUCKETS = ((0, "0"), (1, "1"), (3, "2-3"), (7, "4-7"), (21, "8-21"))


class Expired(ValueError):
    """The contract expired before this session; it cannot be traded."""


def dte(session: date, expiry: date) -> int:
    """Calendar days to expiry. Expiry day itself is 0 and is tradeable."""
    days = (expiry - session).days
    if days < 0:
        raise Expired(f"contract expired {expiry}, session is {session}")
    return days


def dte_bucket(days: int) -> str:
    """Bucket a DTE. A NEGATIVE dte is an EXPIRED contract and is refused -
    it used to return "0", silently classifying a dead contract as tradeable
    on expiry day, which is the dangerous direction to be wrong in."""
    if days < 0:
        raise Expired(f"dte {days} is negative; the contract already expired")
    for edge, label in _BUCKETS:
        if days <= edge:
            return label
    return "22+"


def atm_theta_share_per_day(*, dte: int) -> float:
    """Fraction of an ATM option's premium lost to time in one day.

    From the Black-76 ATM approximation price ~ 0.3989 * F * sigma * sqrt(T):
    d(price)/dT / price = 1/(2T), per year. Sigma cancels, so this is the same
    number in a quiet market and a violent one.
    """
    if dte <= 0:
        raise ValueError(
            "theta share is undefined on expiry day - T is 0 and the option "
            "settles rather than decays. Model settlement explicitly instead."
        )
    T = dte / 365.0
    return (1.0 / (2.0 * T)) / 365.0


def spread_in_theta_minutes(*, spread_pts: float, premium: float, dte: int) -> float:
    """How many minutes of theta a round-trip spread costs.

    This is the number that decides whether a premium-selling idea is even
    arithmetically possible in a session.
    """
    decay_per_day = atm_theta_share_per_day(dte=dte) * premium
    if decay_per_day <= 0:
        raise ValueError("non-positive decay")
    return spread_pts / (decay_per_day / SESSION_MINUTES)


def time_of_day_bucket(ts: pd.Timestamp) -> str:
    """Measured profile: 09:15 carries 1.95x the day's average 1-minute move,
    11:45 is the 0.81x trough, 15:00 rebounds to 1.17x. A clean U."""
    minutes = ts.hour * 60 + ts.minute
    if minutes < 10 * 60:
        return "open"
    if minutes < 14 * 60 + 30:
        return "midday"
    return "close"


def liquid(*, traded_fraction: float, threshold: float = LIQUID_THRESHOLD) -> bool:
    """37.7% of option rows are stale prints where O==H==L==C. Filling against
    those is how a harness manufactures edge that no one could have taken.

    A fraction outside [0, 1] or NaN means the caller computed it wrongly.
    NaN used to read as "illiquid", which is a silent pass rather than a fault.
    """
    if traded_fraction != traded_fraction:            # NaN
        raise ValueError("traded_fraction is NaN; compute it before gating")
    if not 0.0 <= traded_fraction <= 1.0:
        raise ValueError(
            f"traded_fraction {traded_fraction} is outside [0, 1]; a contract "
            f"cannot trade in more minutes than the session has"
        )
    return traded_fraction >= threshold
