# R89 notes: implementation clarifications and amendments (dated, each before the numbers it affects)

## N1 (20 Oct 2026, before any P&L, twin or gate number was computed)

Clarifications of PREREG section 3 / 5 where the text left a choice open. Written after one smoke run that only counted
NIFTY base triggers and trades on the whole panel (2,010 raw triggers, 910 trades incl. warm-up and holdout days; no
price, P&L or outcome was looked at).

1. **A trigger consumes its setup** whether or not it becomes a trade (no qualifying swing target, the HTF filter, the
   window, a position already open, the daily cap). So every variant that shares a candle timeframe, level set and break
   type sees the SAME triggers, and the filters only remove trades.
2. **F1 = the first TRADE of the day**: triggers that cannot trade (no target, outside the window) do not use up the
   day's one trade.
3. **One open position at a time** is judged on the underlying exit minute (the option is sold one minute later at the
   index; MCX at that minute), and a new trigger must come after that minute.
4. **MCX trigger, stop and target** use the close-only minute bars: with open = the previous close, "a minute high above
   X" is "a minute close above X", and the stop is a minute close at or through it (R53's rule).
5. **Twins enter at the close of a random minute** (the futures-only twin also fills at that close; there is no stop
   order level for a random entry), with the trade's own risk and reward distances measured from that close.
6. **SR levels R / S** are read from 15m candles of the previous 5 sessions only, with the confirming candle also before
   today's open; the MCX 15m candles are close-only like the 1m ones.
