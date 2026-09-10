"""Expiry-day short put — the one structure in this project that measured
positive on both train and a sealed holdout.

    On a NIFTY expiry day, at 11:00 IST, sell one put at the nearest listed
    strike to F x (1 - otm_pct), where F is the put-call-parity forward.
    Hold to cash settlement. Never buy it back.

Measured on 170 expiry sessions 2023-01-05..2026-04-13, lot 65, all costs:
0.75% OTM gives 97.65% win rate and +Rs 406/trade; 1.00% OTM gives 100.00%
and +Rs 270. The 0.75% variant pays more AND has actually shown its losses,
which is why it is the default here.

WHY THIS IS NOT PAYOFF GEOMETRY: every configuration that reached 100% is a
naked short PUT. Calls reach 100% at no distance out to 3.5% and no strangle
reaches it. The record is the statement "NIFTY did not fall 1% between 11:00
and the close on 170 consecutive expiries" - a property of a 2023-2026 bull
market, not of the payoff. The loss is unbounded; size accordingly.
"""
from __future__ import annotations

from datetime import date, time

import numpy as np
import pandas as pd

from options_lab import costs

DEFAULT_OTM_PCT = 0.0075        # 0.75%: pays more than the 100% cell at 1.00%
DEFAULT_N_STRIKES = 10          # pinned, so the forward is deterministic
SETTLE_FROM = time(15, 0)       # NSE settles on the last-30-minute average
SETTLE_TO = time(15, 30)


class ThinChain(ValueError):
    """Too few strikes quote both sides to compute a trustworthy forward."""


class NotASnapshot(ValueError):
    """The chain carries a strike more than once.

    The raw chain has one row per contract PER MINUTE, so passing it whole is
    the natural mistake. Before this check it surfaced as pandas' "The truth
    value of a Series is ambiguous" from deep inside the comparison, which
    tells the caller nothing. Take one timestamp's snapshot first, e.g.
    `chain[chain.ts <= entry].sort_values("ts").groupby(["strike","right"],
    as_index=False).last()`.
    """


def parity_forward(chain: pd.DataFrame, *, n_strikes: int = DEFAULT_N_STRIKES) -> float:
    """F = median over the n nearest strikes of (K + CE - PE).

    Read off the OPTIONS, never the future. The near-month future carries a
    median +48.5 point basis over spot (max +228), which is most of a 0.75%
    strike offset - using it would mis-set the strike every single day.
    """
    dupes = chain.duplicated(subset=["strike", "right"], keep=False)
    if dupes.any():
        offenders = sorted(chain.loc[dupes, "strike"].unique())[:3]
        raise NotASnapshot(
            f"chain is not a snapshot: strikes {offenders} appear more than "
            f"once ({int(dupes.sum())} duplicated rows). Reduce to one "
            f"timestamp per contract before computing the forward."
        )

    ce = chain[chain["right"] == "CE"].set_index("strike")["close"]
    pe = chain[chain["right"] == "PE"].set_index("strike")["close"]
    both = ce.index.intersection(pe.index)
    usable = [k for k in both if ce[k] > 0 and pe[k] > 0]

    if len(usable) < n_strikes:
        raise ThinChain(
            f"{len(usable)} strikes quote both sides; need {n_strikes}"
        )

    implied = pd.Series({k: k + float(ce[k]) - float(pe[k]) for k in usable})
    # Two passes: a rough centre from all strikes, then the n nearest to it.
    centre = float(implied.median())
    nearest = implied.reindex(
        sorted(implied.index, key=lambda k: abs(k - centre))[:n_strikes]
    )
    return float(nearest.median())


def select_strike(strikes, *, forward: float,
                  otm_pct: float = DEFAULT_OTM_PCT) -> float:
    """The NEAREST listed strike to the target - not the nearest at or below it.

    That distinction is worth Rs 81/trade, roughly a fifth of the strategy's
    return: flooring collects a smaller credit (Rs 239 vs Rs 320 median) for a
    marginally higher win rate. Ties break to the lower (further OTM) strike.
    """
    ladder = sorted(float(k) for k in strikes)
    if not ladder:
        raise ValueError("empty strike ladder")
    target = forward * (1.0 - otm_pct)
    return min(ladder, key=lambda k: (abs(k - target), k))


def settlement_price(spot: pd.Series, day: date) -> float:
    """NSE settles NIFTY options on the weighted average of spot over the last
    thirty minutes, NOT the 15:29 print. Using the print flips which sessions
    lose: 2026-01-20 drops out and 2024-04-18 drops in."""
    idx = pd.DatetimeIndex(spot.index)
    window = spot[(idx.date == day) & (idx.time >= SETTLE_FROM) & (idx.time < SETTLE_TO)]
    if window.empty:
        raise ValueError(f"no bars in the settlement window on {day}")
    return float(window.mean())


def settle_trade(*, strike: float, credit: float, settlement: float,
                 lot_size: int, lots: int = 1, regime: str = "quoted") -> dict:
    """P&L of one short put carried to cash settlement.

    The short leg pays no exercise STT - that 0.125%-of-intrinsic charge falls
    on the buyer. The loss is unbounded below and scales one-for-one with the
    move past the strike.
    """
    intrinsic = max(strike - settlement, 0.0)
    qty = lot_size * lots
    gross = (credit - intrinsic) * qty
    charges = costs.sell_to_settle(premium=credit, lot_size=lot_size, lots=lots,
                                   regime=regime)
    net = gross - charges.total
    return {
        "strike": strike, "credit": credit, "settlement": settlement,
        "intrinsic": intrinsic, "qty": qty,
        "gross_pnl": gross, "cost": charges.total, "net_pnl": net,
        "won": net > 0,
    }
