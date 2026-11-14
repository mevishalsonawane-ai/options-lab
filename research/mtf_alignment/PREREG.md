# Candle alignment — registered before results, 14 November 2026

User hypothesis: buy CE when the latest completed underlying 5-minute,
1-minute and 10-second candles are all bullish; buy PE when all bearish.
Bullish means close > open; bearish close < open; doji/missing means no signal.
No EMA or neural probability is substituted for candle colour.

Data availability: available historical NIFTY/option files are one-minute.
The exact three-frame arm is BLOCKED unless genuine timestamped sub-minute
index and option data are found. Never synthesize ten-second OHLC from minutes.
Run two explicitly partial ablations: one-minute alone, one plus five-minute.
Five-minute bars anchor at 09:15 IST; require all five consecutive underlying
minutes. Join only completed higher bars at their closing/availability time.
Require current minute continuity; no previous-session bar carryover.

Entry: first eligible alignment while flat, not only on direction transitions;
after exit a later completed aligned candle may initiate another trade. Decisions
09:20–13:45 IST, earliest common five-minute availability, underlying signal.
Each decision fills the next minute option open, same real fixed strike held
until exit. Buy one strike ITM nearest scheduled NIFTY weekly, 1–7 days to expiry;
no expiry-day entries. Do not apply the old breakout/BankNifty/VIX filters.

Use existing neural_v2.strategy cash/fill/risk engine unchanged: Rs100000 start,
one lot, maximum two trades daily; initial stop -15%, target+30%, existing profit
lock after+15%, 20-minute failure exit, max45min and14:50 square-off. Original
cash/risk budgets, dated charges and modeled spread/slippage. Normal and double
friction accounts replay separately. Same exits in each arm; no parameter search.

Report full-period and chronological three calendar-window restarts (start
through2026-03-31, Apr–Jun2026, Jul2026 onward). Full account remains primary;
window restarts each receive100000 and are diagnostic, not concatenated account
returns. Prior research already used these dates; no pristine holdout.
Report trades, net wins, net P&L, drawdown, costs and monthly ledger. A higher
win rate without positive robust net is no pass. These two ablations do not test
the incremental value of ten-second confirmation. No real trades or promotion.
