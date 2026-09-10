# The hedged variant, and what measuring it says

`expiry_put`'s own docstring says the loss is unbounded and to size
accordingly. `sizing.py` exists entirely to answer "how much capital survives a
−6% day". But there was no way to actually *run* the bounded version, so the
trade-off could only be asserted, never measured.

    python -m options_lab.cli expiry-put --lot dated --wing 0.0075

Buying a put `--wing` further OTM caps the loss at the width of the spread plus
the cost of both legs. `settle_trade` reports `max_loss` per trade.

## The measurement

170 sessions, dated lots, quoted spread, 0.75% OTM short, 0.75%-wide wing:

|  | naked | hedged |
|---|---|---|
| win rate | 97.65% | 79.41% |
| mean/trade | +Rs 320 | +Rs 165 |
| median/trade | +Rs 225 | +Rs 77 |
| worst loss | −Rs 979 | −Rs 1,049 |
| cost as % of credit | 13.9% | **44.1%** |
| total | +Rs 54,455 | +Rs 28,120 |

## The wing helped on 0 of 170 sessions

Not "rarely". Zero. It cost **Rs 26,335** across the sample — close to half the
naked total — and paid out nothing, because it was never in the money.

The worst four sessions, hedged alongside:

| session | fall from 11:00 | naked | hedged |
|---|---|---|---|
| 2024-10-03 | 0.90% | −Rs 979 | −Rs 1,049 |
| 2023-06-08 | 0.75% | −Rs 679 | −Rs 788 |
| 2024-11-28 | 0.87% | −Rs 506 | −Rs 659 |
| 2024-04-18 | 0.79% | −Rs 127 | −Rs 236 |

The hedged trade is worse on every one of them. That is not a paradox: on those
sessions the short put finished barely in the money and the wing, 150 points
further out, finished worthless. The premium paid for it is a pure subtraction.

## Where the crossover is

The naked position only loses more than the hedged floor once the index closes
about 200 points below the short strike — a fall of roughly **1.60%** from the
11:00 mark.

The largest fall in 170 sessions is **0.95%**.

So the wing would need a move 68% larger than anything on record before it saved
a rupee.

## What that does and does not settle

It settles that **the sample cannot price this hedge**. The data contains no
event the wing would have caught, so every rupee of measured difference is cost
and none of it is benefit. Any backtest comparison will therefore favour the
naked trade, and will keep doing so right up until the session that ends it.

It does not settle whether to hedge. Three things the table cannot show:

- **The naked loss is unbounded and the account is not.** The measured worst
  case is −Rs 979 on a 0.90% fall. The loss scales one-for-one past the strike,
  so a 6% gap opens a hole roughly forty times deeper, and the position is
  liquidated by the broker before it can recover.
- **The record is a property of a bull market.** The strategy's own docstring
  says so: 170 consecutive expiries without a 1% intraday fall is a statement
  about 2023-2026, not about the payoff. `monitor.check_regime` already reads
  WARN at 99% of the strike distance.
- **Margin.** A naked short put ties up roughly Rs 107,000 per lot; a spread
  ties up the width. Per rupee of margin the hedged trade is not obviously
  behind, and `sizing.plan_position` sizes the naked one for a −6% day it has
  never seen.

The honest summary: on everything that has happened, the hedge costs half the
edge and buys nothing. Its entire value is insurance against the event that has
not happened yet — which is the same event that ends the naked strategy. That
is a decision about ruin, not about expectancy, and the backtest is the wrong
instrument for it.
