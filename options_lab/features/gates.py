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

import math
from datetime import date

import numpy as np
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


# ============================================================================================================================
# R171 (13 Nov 2026, research/hunt/r171_liquidity_gates/PREREG.md): pre-entry liquidity and execution-cost gates.
#
# Every function below is SKIP-ONLY: it answers "may this trade be taken", never "trade more". A value that cannot be
# computed (too few minutes, no history) returns None from the measuring helpers, and the `*_ok` gates treat None as
# "cannot judge -> take", the repo's convention for filters. Nothing here changes `liquid()` or LIQUID_THRESHOLD.
# median_lots_per_minute divides by `lot_size`: pass the lot for a feed in UNITS (Upstox, Dhan, the research-data release),
# and lot_size=1 for volume already in contracts (the options_lab store's convention, docs/design.md).
# ============================================================================================================================
PRE_ENTRY_LAST_MINUTES = 5           # "traded in at least one of the last 5 minutes before the fill"
ROLL_MIN_CHANGES = 10                # Roll needs at least this many 1-minute price changes
ROLL_NORMAL_MIN_WINDOWS = 6          # windows needed before a contract's "normal" Roll spread exists
DEPTH_STRESS_SHARE = 0.10            # an order above 10% of the median lots a minute is costed as the 'stress' regime
# G4b's caps (minutes of theta a round-trip spread may cost), tightening with DTE. DTE 0-1: no cap (theta recoups fast;
# at DTE 0 theta is undefined, see atm_theta_share_per_day).
DTE_SPREAD_CAPS_TIGHT = {"2-3": 60.0, "4-7": 45.0, "8-21": 30.0, "22+": 20.0}


def pre_entry_window(n_minutes: int, entry_row: int, minutes: int) -> slice:
    """Rows [entry_row - minutes, entry_row) of a 375-row session grid, clipped at 09:15 (row 0). The fill minute itself is
    never inside: its volume is not known when the order is sent."""
    if minutes <= 0:
        raise ValueError("the window needs at least one minute")
    if not 0 <= entry_row <= n_minutes:
        raise ValueError(f"entry row {entry_row} is outside the session's {n_minutes} rows")
    return slice(max(0, entry_row - minutes), entry_row)


def traded_share(volume) -> float | None:
    """Share of the window's minutes with volume > 0. A missing minute (NaN) did not trade. None for an empty window."""
    v = np.asarray(volume, dtype=float)
    if v.size == 0:
        return None
    return float(np.mean(np.nan_to_num(v, nan=0.0) > 0))


def pre_entry_active(volume, *, min_share: float, last_minutes: int = PRE_ENTRY_LAST_MINUTES) -> bool:
    """R171 G1: the chosen contract traded in >= min_share of the pre-entry window's minutes AND in at least one of the
    last `last_minutes`. An empty window (cannot judge) passes."""
    if not 0.0 <= min_share <= 1.0:
        raise ValueError(f"min_share {min_share} is outside [0, 1]")
    v = np.nan_to_num(np.asarray(volume, dtype=float), nan=0.0)
    if v.size == 0:
        return True
    return bool(np.mean(v > 0) >= min_share and (v[-last_minutes:] > 0).any())


def coverage_threshold_for_dte(days: int, *, near: float = 0.60, base: float = 0.80) -> float:
    """R171 G4c: a looser same-day coverage threshold only on 0-1 DTE, where theta earns the spread back fast."""
    return near if dte_bucket(days) in ("0", "1") else base


def round_trip_cost_share(*, premium: float, lot_size: int, lots: int = 1, regime: str = "quoted") -> float:
    """Brokerage + statutory + a crossed spread (options_lab.costs, the named regime) as a share of the premium paid.
    The spread is an ASSUMPTION (costs.py): a gate built on it inherits that error."""
    from options_lab import costs as _costs
    if not premium > 0:
        raise ValueError(f"premium {premium} must be positive")
    return _costs.round_trip(premium=premium, lot_size=lot_size, lots=lots, regime=regime).fraction_of_premium


def cost_cap_ok(*, premium: float, lot_size: int, lots: int = 1, cap: float | None = None, floor: float | None = None,
                regime: str = "quoted") -> bool:
    """R171 G2: skip when the estimated round trip exceeds `cap` of the premium, or the premium is below `floor` (tick-floor
    effects: one Rs 0.05 tick is >= 0.5% of any premium under Rs 10). A non-positive premium is refused (skipped)."""
    if not premium > 0:
        return False
    if floor is not None and premium < floor:
        return False
    if cap is not None and round_trip_cost_share(premium=premium, lot_size=lot_size, lots=lots, regime=regime) > cap:
        return False
    return True


def median_lots_per_minute(volume, lot_size: int) -> float | None:
    """Median lots traded a minute over the window (missing minutes count 0). None for an empty window."""
    if lot_size <= 0:
        raise ValueError("lot size must be positive")
    v = np.nan_to_num(np.asarray(volume, dtype=float), nan=0.0)
    if v.size == 0:
        return None
    return float(np.median(v)) / lot_size


def depth_ok(median_lots: float | None, *, order_lots: int, multiple: float) -> bool:
    """R171 G3a/b: the window's median lots a minute is at least `multiple` x the order. None (cannot judge) passes."""
    if order_lots <= 0:
        raise ValueError("order lots must be positive")
    if median_lots is None:
        return True
    return median_lots >= multiple * order_lots


def depth_regime(median_lots: float | None, *, order_lots: int, share: float = DEPTH_STRESS_SHARE) -> str:
    """'stress' when the order is more than `share` of the median lots traded a minute (the order is beyond typical depth,
    costs.py's stress regime), else 'quoted'. A minute median of 0 with any order is 'stress'. None -> 'quoted'."""
    if order_lots <= 0:
        raise ValueError("order lots must be positive")
    if median_lots is None:
        return "quoted"
    return "stress" if order_lots > share * median_lots else "quoted"


def roll_spread(prices) -> float | None:
    """Roll (1984) effective spread from consecutive 1-minute price changes: 2 sqrt(-cov(dp_t, dp_t-1)); 0 when the
    autocovariance is not negative. NaN prices are dropped first. None with fewer than ROLL_MIN_CHANGES changes."""
    p = np.asarray(prices, dtype=float)
    p = p[np.isfinite(p)]
    dp = np.diff(p)
    if dp.size < ROLL_MIN_CHANGES:
        return None
    a, b = dp[1:], dp[:-1]
    cov = float(np.mean((a - a.mean()) * (b - b.mean())))
    return 2.0 * math.sqrt(-cov) if cov < 0 else 0.0


def roll_relative(prices) -> float | None:
    """Roll spread divided by the window's mean price; None when it cannot be computed."""
    s = roll_spread(prices)
    p = np.asarray(prices, dtype=float)
    p = p[np.isfinite(p)]
    if s is None or p.size == 0 or not p.mean() > 0:
        return None
    return s / float(p.mean())


def roll_normal(window_values, *, floor: float, min_windows: int = ROLL_NORMAL_MIN_WINDOWS) -> float | None:
    """A contract's normal relative Roll spread: the median over its earlier windows (None values dropped), floored at
    `floor` (one tick / price). None with fewer than `min_windows` windows."""
    vals = [float(x) for x in window_values if x is not None and np.isfinite(x)]
    if len(vals) < min_windows:
        return None
    return max(float(np.median(vals)), floor)


def roll_spike_ok(current: float | None, normal: float | None, *, factor: float) -> bool:
    """R171 G3c/d: skip when the pre-entry relative Roll spread is above `factor` x the contract's normal. Either value
    missing (cannot judge) passes."""
    if factor <= 0:
        raise ValueError("factor must be positive")
    if current is None or normal is None:
        return True
    return current <= factor * normal


def dte_spread_ok(*, spread_pts: float, premium: float, days: int, caps: dict) -> bool:
    """R171 G4: the round-trip spread may cost at most caps[bucket] minutes of ATM theta. Buckets without a cap (DTE 0-1
    by default) pass; DTE 0 always passes because theta is undefined on expiry day."""
    bucket = dte_bucket(days)
    cap = caps.get(bucket)
    if cap is None or days <= 0:
        return True
    if not premium > 0:
        return False
    return spread_in_theta_minutes(spread_pts=spread_pts, premium=premium, dte=days) <= cap


# R171 Amendment A (13 Nov 2026): two more effective-spread estimators from 1-minute bars, so a spread can be MEASURED
# from real prices instead of assumed (costs.py's regimes). Both return a share of the price, or None when fewer than
# SPREAD_MIN_BARS usable bars exist. They read trade prices, not quotes: an estimate of the effective spread, not the book.
SPREAD_MIN_BARS = 10


def _hlc(high, low, close=None):
    h = np.asarray(high, dtype=float)
    lo = np.asarray(low, dtype=float)
    ok = np.isfinite(h) & np.isfinite(lo) & (h > 0) & (lo > 0) & (h >= lo)
    if close is not None:
        c = np.asarray(close, dtype=float)
        ok &= np.isfinite(c) & (c > 0)
        return h[ok], lo[ok], c[ok]
    return h[ok], lo[ok], None


def abdi_ranaldo_spread(high, low, close) -> float | None:
    """Abdi & Ranaldo (2017) close-high-low estimator: s^2 = 4 E[(c_t - eta_t)(c_t - eta_t+1)], with c the log close and
    eta the mid of the log high and low. Negative s^2 -> 0. Robust to a trending price, unlike Roll."""
    h, lo, c = _hlc(high, low, close)
    if c.size < SPREAD_MIN_BARS:
        return None
    lc = np.log(c)
    eta = (np.log(h) + np.log(lo)) / 2.0
    s2 = 4.0 * float(np.mean((lc[:-1] - eta[:-1]) * (lc[:-1] - eta[1:])))
    return math.sqrt(s2) if s2 > 0 else 0.0


def corwin_schultz_spread(high, low) -> float | None:
    """Corwin & Schultz (2012) high-low estimator, one estimate per pair of consecutive bars (negative ones set to 0),
    averaged. Reads part of a bar's real move as spread, so it is the WIDE bound on 1-minute option bars."""
    h, lo, _ = _hlc(high, low)
    if h.size < SPREAD_MIN_BARS:
        return None
    k = 3.0 - 2.0 * math.sqrt(2.0)
    r = np.log(h / lo) ** 2
    beta = r[:-1] + r[1:]
    gamma = np.log(np.maximum(h[:-1], h[1:]) / np.minimum(lo[:-1], lo[1:])) ** 2
    alpha = (np.sqrt(2.0 * beta) - np.sqrt(beta)) / k - np.sqrt(gamma / k)
    s = 2.0 * (np.exp(alpha) - 1.0) / (1.0 + np.exp(alpha))
    return float(np.mean(np.maximum(s, 0.0)))


def measured_spread_pts(estimates, premium: float, tick: float = 0.05) -> float | None:
    """The median of the relative estimates that exist (None dropped) times the premium, floored at one tick. None when no
    estimate exists or the premium is not positive."""
    vals = [float(x) for x in estimates if x is not None and np.isfinite(x)]
    if not vals or not premium > 0:
        return None
    return max(float(np.median(vals)) * premium, tick)
