# R89 pre-registration: a strategy from R88's findings (buy a straddle when a big candle is expected), 20 Oct 2026

Boss, 20 Oct 2026: "If we create a strategy based on the above findings, what will the profit and win rate be?"

Written and pushed before any P&L of this study was computed. Research only: nothing in `android/**` or `.github/**`
changes. Even a pass only makes the idea eligible for PAPER (CLAUDE.md research gates); live stays untouched.

## 1. Why this strategy and no other

R88 found that the SIZE of the next candle is predictable and its DIRECTION is not. A call or a put needs direction,
so the only option-BUYING strategy that uses R88's finding alone is a **long straddle**: buy the ATM call AND the ATM
put of the nearest expiry when the model expects a big candle, and sell both after the expected move. It wins when the
underlying moves enough, either way, to beat the premium decay over the holding time plus spreads and charges.
Signal source: the underlying (R88's size model reads the underlying's candles; the option chain is context only).

## 2. Rule (frozen)

- Instruments: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (index options of the R88 books: NIFTY and SENSEX
  nearest weekly, the others nearest monthly); MCX GOLDM, SILVERM, CRUDEOIL, NATURALGAS (their own option books).
- Candles: 15 minutes (R88's timeframe with real option liquidity per entry).
- Signal: R88's Part D size model (HistGradientBoosting regressor on R88's factors, same settings), refitted every
  calendar month on all earlier months (walk-forward), predicts S (log size vs the slot's usual size) for each 15-minute
  candle at its open. ENTER when the prediction is in the top 10% of that model's predictions on its own training
  months (the threshold is fixed per refit, before the month is traded).
- Entry window: index candles starting 09:30 to 14:30; MCX candles starting 09:15 to 22:00 IST. No new entry while a
  straddle is open (non-overlapping), at most one entry per candle.
- Trade: BUY 1 lot of the ATM call and 1 lot of the ATM put (offset 0 of the book at the entry minute; the strike is
  then fixed), at the entry minute's option open.
- Exit (two pre-registered holding times, both reported): **H1 = 15 minutes** (the predicted candle), **H4 = 60
  minutes** (R16: 30-60 minute size is predictable). Sell both legs at the open of the exit minute; index square-off at
  15:10 (15:05 on the instrument's expiry day) if earlier; MCX square-off 15 minutes before the session close.
  No stops (a straddle has no direction to be wrong about).
- So 9 instruments x 2 holds = **18 cells**.

## 3. Prices, fills and costs

- Option prices: the lake's 1-minute option bars (`opt_<UND>_<FLAG>_<YEAR>`), found by strike and side at each minute
  (the rolling book carries the fixed strike at whatever offset it sits). A minute with no print takes the next print
  within 3 minutes; an entry with no print in 3 minutes is skipped (counted); an exit with none uses the last close
  ("stale", counted).
- Index fills and charges: `research/obuy/costs.py` Fills("liq") (5 bps + half-spread ticks by the last 5 minutes'
  volume) and Costs("dated") (STT, exchange, SEBI, stamp, GST, Rs 20 brokerage a side, rates in force that day), each
  leg separately; lots in force (`research/hunt/r51/r51cfg.py` LOTS).
- MCX: R85's units and half-spreads (`research/hunt/r85/strategy.py` OPTION_BOOK, HALF: 0.30/0.60% gold/silver and
  crude, 0.45/0.90% natural gas, from/before 17:00) and Zerodha MCX option charges (`research/hunt/r53` common).

## 4. Periods, baselines, gates

- Design: R88's design periods (index 20 Nov 2024 - 31 May 2026, MCX 5 Aug 2025 - 31 May 2026); the walk-forward
  trades start in month 7 (index: Jun 2025; MCX: Feb 2026).
- Out of sample: 1 Jun - 19 Oct 2026, run once. **Not untouched:** R88 already scored its size model there, so it is
  out of sample for this strategy's P&L but its size behaviour has been seen.
- Random-entry twin: for each cell, 500 sets of the same number of straddles per day at random 15-minute candle starts
  of the same entry window (non-overlapping), priced the same way. p = share of twins with net >= the rule's net.
- Reported per cell: trades, win rate (net per straddle > 0), average win, average loss, net Rs, net per trade, gross
  vs costs, the share of trades where the underlying moved more than the straddle's breakeven, month-by-month net, max
  drawdown, twin p.
- **Gate (eligible for paper):** design walk-forward net > 0 AND twin p passes BH q < 0.10 over the 18 cells AND the
  out-of-sample net > 0 on the same cell. Anything else stays a note.

## 5. Not allowed

No change of the rule, threshold, holds, window or costs after any P&L is seen, except by a dated amendment below,
before the out-of-sample run.

## Amendments

(none yet)
