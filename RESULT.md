# R125 result (28 Oct 2026): support/resistance, liquidity, order flow, volume profile, candles, VWAP, MAs, RSI, Supertrend in the neural network
1. Boss asked to add all of these (on candle wicks) to the network; 74 new inputs in 7 specialists, tested on 2 years (Oct 2024 - Oct 2026).
2. They did not help: direction of the next 30-point move stays a coin - holdout AUC 0.523 with them vs 0.524 without (25,652 moves).
3. The network nearly ignores them: shuffling the last 2 hours of price hurts its forecast ~500x more than shuffling any new group.
4. Why: wick levels, sweeps, candles, VWAP, EMAs, RSI and Supertrend are all summaries of the same past prices the network already reads.
5. "A move is coming" stays predictable (AUC ~0.83-0.84), as in R124; the new inputs add nothing there either.
6. No rule works: the network is almost never 60/40 sure of direction; the few trades lost (holdout M2: 17 trades, -Rs 272 each).
7. Six studies now agree: how MUCH the market moves is predictable; WHICH WAY over 15-30 minutes is a coin.
8. Nothing passes; nothing goes on paper; no app change.
9. The holdout (Apr-Oct 2026) has been read three times today (R123, R124, R125) - disclosed in each write-up.
10. Details: research/HUNT_R125_LEVELS_FLOW.md (and R123, R124); code + tests in research/hunt/r125_levels_flow/.
