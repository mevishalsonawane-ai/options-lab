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
from typing import Iterable, Sequence

import numpy as np
import pandas as pd

from options_lab import costs
from options_lab import lots as lot_table

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
        "intrinsic": intrinsic, "lot_size": lot_size, "lots": lots, "qty": qty,
        "gross_pnl": gross, "cost": charges.total, "net_pnl": net,
        "won": net > 0,
    }


DEFAULT_ENTRY = time(11, 0)
DEFAULT_HOLDOUT_SESSIONS = 30

# Pass as `lot_size` to look the lot up per session instead of pinning one.
# NIFTY ran 50 / 25 / 75 / 65 inside this sample, and because brokerage is a
# flat Rs 20/order the lot moves the cost fraction too, so a single pinned
# number is not even a clean rescale of the others.
DATED_LOT = "dated"


def _dated_lot(chain: pd.DataFrame, underlying: str, day: date) -> int:
    """This session's real lot: the chain first, the date table as fallback.

    The chain wins because NSE applies a lot change to contracts INTRODUCED
    after it, so a monthly listed before the change keeps the old lot until it
    expires. On 2025-01-30 every NIFTY contract expiring that day carried 25
    while the rest of the book was at 75; the date table says 75 and a position
    sized on that is three times too large.

    The chain also covers 2023, which the table cannot: NSE published no lot
    column before 2024-01-01, but the open interest was always there.
    """
    observed = lot_table.lot_from_chain(chain)
    if observed is not None:
        return observed
    return lot_table.lot_size_on(underlying, day)


class SessionSkipped(Exception):
    """This session could not be priced. Carries why, so it is never silent."""


def run_session(
    day: date,
    chain: pd.DataFrame,
    *,
    lot_size: int,
    otm_pct: float = DEFAULT_OTM_PCT,
    entry_time: time = DEFAULT_ENTRY,
    lots: int = 1,
    regime: str = "quoted",
) -> dict:
    """One expiry session -> one trade. Raises SessionSkipped with a reason.

    Settlement is derived from the SAME chain that is traded: at 0 DTE there is
    no carry left, so the put-call-parity forward converges to spot. That keeps
    entry, strike and settlement on one source, so a data error cannot shift
    only one of them.
    """
    ts = pd.to_datetime(chain["ts"])

    before = chain[ts.dt.time <= entry_time]
    if before.empty:
        raise SessionSkipped(f"no bars at or before {entry_time}")

    snapshot = (before.assign(ts=pd.to_datetime(before["ts"]))
                      .sort_values("ts")
                      .groupby(["strike", "right"], as_index=False).last())

    forward = parity_forward(snapshot)
    strike = select_strike(snapshot["strike"].unique(), forward=forward,
                           otm_pct=otm_pct)

    leg = snapshot[(snapshot["strike"] == strike) & (snapshot["right"] == "PE")]
    if leg.empty or float(leg["close"].iloc[0]) <= 0:
        raise SessionSkipped(f"no PE quote at strike {strike:g}")
    credit = float(leg["close"].iloc[0])

    window = chain[(ts.dt.time >= SETTLE_FROM) & (ts.dt.time < SETTLE_TO)]
    if window.empty:
        raise SessionSkipped("no bars in the settlement window")

    forwards = []
    for _, minute in window.assign(ts=pd.to_datetime(window["ts"])).groupby("ts"):
        try:
            forwards.append(parity_forward(minute))
        except (ThinChain, NotASnapshot):
            continue
    if not forwards:
        raise SessionSkipped("settlement window too thin to price")

    trade = settle_trade(strike=strike, credit=credit,
                         settlement=float(np.mean(forwards)),
                         lot_size=lot_size, lots=lots, regime=regime)
    trade.update(session=day, forward=forward,
                 otm_realised=(forward - strike) / forward)
    return trade


def run_backtest(
    sessions: Iterable[tuple[date, pd.DataFrame]],
    *,
    lot_size: int | str,
    otm_pct: float = DEFAULT_OTM_PCT,
    entry_time: time = DEFAULT_ENTRY,
    lots: int = 1,
    regime: str = "quoted",
    underlying: str = "NIFTY",
) -> tuple[pd.DataFrame, list[tuple[date, str]]]:
    """Run every session. Returns (trades, skipped) - skips are never silent.

    `lot_size` is either an explicit int, or DATED_LOT to take each session's
    lot from the verified NSE history. A session the history cannot cover is
    skipped WITH ITS REASON rather than run at a guessed lot, so the sample
    shrinks visibly instead of the rupee figures being quietly wrong.
    """
    trades, skipped = [], []
    for day, chain in sessions:
        try:
            lot = (_dated_lot(chain, underlying, day)
                   if lot_size == DATED_LOT else lot_size)
            trades.append(run_session(day, chain, lot_size=lot,
                                      otm_pct=otm_pct, entry_time=entry_time,
                                      lots=lots, regime=regime))
        except (SessionSkipped, ThinChain, NotASnapshot,
                lot_table.NoLotSize) as exc:
            skipped.append((day, str(exc)[:80]))
    return pd.DataFrame(trades), skipped


def split_sessions(
    days: Sequence[date], *, holdout: int = DEFAULT_HOLDOUT_SESSIONS
) -> tuple[list[date], list[date]]:
    """Seal the most recent `holdout` sessions. Chronological, never random.

    Honest here for one specific reason: this strategy has NO fitted parameter.
    Entry time and strike distance came from a prior sweep, not from this code,
    so holding sessions back costs nothing in sample and the holdout is
    genuinely untouched rather than merely unexamined.
    """
    ordered = sorted(set(days))
    if holdout >= len(ordered):
        raise ValueError(
            f"holdout of {holdout} leaves no training sessions out of "
            f"{len(ordered)}; a split needs data on both sides"
        )
    return ordered[:-holdout], ordered[-holdout:]
