R96 round 3: LEADS (continue)

Boss, in plain English:

- **Nothing passed this round, so nothing goes to paper.** I tested 10 new ideas, pre-registered before any number
  was seen. The locked holdout (15 Jul - 19 Oct 2026) has still not been read.
- **The best candidate so far is the NR7 breakout on crude oil.** NR7 means the narrowest day of the last 7. The rule
  buys a 1-ITM call or put when the crude future breaks that day's high or low, holds up to 3 sessions, and always has
  a stop.
  - Over 11 months of design data it made **+Rs 48,875 on 46 trades** (1 lot, after real MCX charges and spreads), and
    +Rs 38,655 with double spreads.
  - It won 76% of trades and was positive in all four quarters.
  - Worst month -Rs 9,718, worst drawdown -Rs 11,854, so it stayed inside your -Rs 13k month stop.
  - It beat random-day copies of itself (p 0.014).
  - It fails one gate: after correcting for all 29 ideas tried so far, its score is 0.135 against the 0.10 limit.
  - Caveat: I chose crude because round 2 pointed there, so its numbers are flattering. Almost all of the profit is on
    the big CRUDEOIL lot (23 trades), whose median risk a trade is about Rs 9,100, so 2% risk needs about Rs 4.6 lakh.
  - One CRUDEOIL winner (22 Oct 2025, +Rs 16,532) was priced by the model, not a real print; on real prints only
    CRUDEOIL made +Rs 28,639 on 22 trades. Full trade list: `research/hunt/r96_mcx_buy/results/NR7_CRUDE_DETAIL.md`.
- **The three new ideas from published research all lost after costs on MCX options:**
  - trading the EIA-day half-hour move (90 trades, -Rs 10,239);
  - last-half-hour momentum (223 trades, -Rs 79,100);
  - high-volatility opening-range breaks (14 trades).
  The moves are smaller than the option spread.
- **Natural gas spoils most crude results.** The signal works on gas futures, but gas options cost too much to trade.
- **The breadth-Donchian trailing stop and profit lock (your request) make the crude NR7 option trade worse.**
  - With a 2x ATR stop, a 3x ATR chandelier and up to 10 sessions, the option lost -Rs 45,115 on 36 trades.
    26 of them hit the -40% premium stop before the trail on the future could act.
  - On the crude future itself the same exits made +Rs 1.31 lakh on 32 trades. But random days made +Rs 85,773 too,
    and one month was -Rs 97,916. That profit is crude's swings, not the NR7 signal.

Two decisions only you can make:

1. **A fresh Dhan token would help most.** The data lake stores only 3 strikes either side of ATM. Winners get sold
   early at the edge of that window, which caps every result above. With ATM+/-10 strikes the lake could price the
   real exits (round-4 idea R4-0). Please don't paste the token in chat. Put it where the phone/lake fetcher reads it.
2. **Whether to read the holdout for the crude NR7 rule.** Under our rules it stays locked, because it missed the
   multiple-testing gate. Reading it now would be a rule change. That is your call, and it would be logged as such.
   Until then, round 4 continues with the pre-registered list in `research/HUNT_R96_ROUND3.md`.

Details: `research/HUNT_R96_ROUND3.md`, every number in `research/hunt/r96_mcx_buy/results/report_round3.md`, all 29
ideas in `research/hunt/r96_mcx_buy/LEDGER.md`.
