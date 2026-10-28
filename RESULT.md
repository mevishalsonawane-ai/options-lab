# R124 result (28 Oct 2026): every NIFTY 30-point move (on candle highs/lows), traced back, with a neural network
1. NIFTY makes about 20 moves of 30+ points a day (23,606 in design, 1,142 sessions; typical 40 points in 6 minutes), half up, half down.
2. Before a move the market is usually already busy: high recent range (2.4x), high VIX (1.7x), expensive straddle (2.0x), price far from the open; these held on unseen data.
3. Time of day: 09:15-09:45 has 2.8x the move starts; 11:15-13:15 is quietest. Round-100 levels, expiry day and PCR made no difference.
4. Nothing before a move tells its direction; "price went the other way just before" is built into how swings are defined.
5. The multi-level network sees a 30-minute move coming better than trees on unseen data (AUC 0.853 vs 0.842, q 0.002), using mostly the last 2 hours of price.
6. Direction is still a coin (holdout AUC 0.51) - the fifth study to find this (R16, B1, R41, R123, R124).
7. No option rule made money: the straddle on "move coming" lost Rs 211 a trade (516 holdout trades); call/put rules almost never fired.
8. A 30-point NIFTY move is too small to pay for a straddle's two sets of charges, spreads and time decay.
9. Nothing passes, nothing goes on paper. Earlier today R123 (70% win / Rs 1,000 target) also failed: research/HUNT_R123_NEURAL_70.md.
10. Details: research/HUNT_R124_BIG_MOVES.md; code + 15 tests in research/hunt/r124_big_moves/; holdout read once (11:02-11:17 UTC).
