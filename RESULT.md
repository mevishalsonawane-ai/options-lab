1. R86 + R86b (19 Oct), how the big players work and trade: guide research/BIG_PLAYERS_GUIDE.md, results in HUNT_R86_BIG_PLAYERS.md and HUNT_R86B_HOW_BIG_PLAYERS_TRADE.md.
2. Their trading IS visible in the index: big selling is fast (FII-sell days make ~61% of NIFTY's move by 10:15), big buying is slow (spread over the day, finished at the close).
3. Funds trade at the closing price: at month-end the last 30 minutes carry 27% of the day's turnover (16% normally); expiry days 23%.
4. "Whole-index" orders (80% of the stocks moving together on 3x volume) follow the day's direction, but nothing extra happens after them.
5. "Absorption at the top" (heavy volume, no move) almost never shows up on index data; it is mostly folklore.
6. Out of the box (R86c, HUNT_R86C_CATCH_THEM.md): a seller can be recognised by 09:45 (gap down 0.5%+, no recovery, VIX +5% mark FII selling days 2-3.5x more often), but 0 of 12 ways to trade it passed (and 0 of 31 earlier footprint rules in R86 and R86b).
7. For the bot: nothing new to switch on; treat heavy-selling openings and month-end/expiry closes as the riskiest windows (wider swings, slippage).
8. Nearest misses, not proven: the BANKNIFTY 09:45 seller detector (+Rs 36k on 13 shorts, carried by 5 trades), the noon FII-buyer detector (AUC 0.75-0.90), crowded COMEX silver, gold before a GOLDM roll.
