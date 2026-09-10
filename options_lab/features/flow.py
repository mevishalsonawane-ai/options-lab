"""Signed option order flow.

    flow(t) = sum over the liquid chain of  sign(close_t - close_{t-1}) * volume_t
              with calls contributing +1 and puts -1

Buying calls is bullish and buying puts is bearish, so a put's contribution is
negated. This is the only option-derived feature measured to LEAD rather than
coincide: IC +0.109 at 1 minute, +0.049 at 5 and +0.034 at 15, after
residualising the index's contemporaneous return and intraday mean reversion.
Its edge/cost ratio is 0.42x, so it does not clear friction standalone.

Two properties are structural, not incidental:

  - It is built from the WHOLE liquid chain, never from the one contract a rule
    is about to trade. Building it from the fill contract is how look-ahead
    enters an options backtest.
  - An unchanged price contributes ZERO however large the volume. 37.7% of
    option rows are stale prints where open==high==low==close; letting those
    create flow would manufacture signal out of an absent quote.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

RIGHT_SIGN = {"CE": 1.0, "PE": -1.0}


def signed_flow(chain: pd.DataFrame, *, normalise: bool = False) -> pd.Series:
    """Per-minute signed flow, summed across the chain.

    `chain` is one session's option bars: contract_id, right, ts, close, volume.
    Index rows (right == "IX") are excluded - they are the underlying, not flow.
    """
    if chain.empty:
        return pd.Series(dtype="float64")

    opts = chain[chain["right"].isin(RIGHT_SIGN)].copy()
    if opts.empty:
        return pd.Series(dtype="float64")

    opts = opts.sort_values(["contract_id", "ts"])

    # Tick rule, per contract. The first bar of a contract has no prior price,
    # so its diff is NaN and contributes nothing.
    delta = opts.groupby("contract_id", sort=False)["close"].diff()
    tick = np.sign(delta).fillna(0.0)

    side = opts["right"].map(RIGHT_SIGN).astype("float64")
    opts["contribution"] = tick * side * opts["volume"].astype("float64")

    flow = opts.groupby("ts", sort=True)["contribution"].sum()

    if not normalise:
        return flow

    traded = opts.groupby("ts", sort=True)["volume"].sum().astype("float64")
    return (flow / traded.replace(0.0, np.nan)).fillna(0.0)
