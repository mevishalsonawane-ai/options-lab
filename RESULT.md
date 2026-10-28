# R126 result (28 Oct 2026): every indicator at many settings (+ Bollinger Bands, MACD) and 54 rule settings - nothing helps
1. Tried 118 settings of 15 indicator/data families (359 inputs, all on candle wicks): Bollinger, MACD, RSI, Supertrend, EMAs, crosses, VWAP, volume, volume profile, S/R, sweeps, pools, candles, option flow, participant OI.
2. None improves the forecast of which way the next 30-point move goes: 0 of 236 tests pass; fewer than half improve at all (chance).
3. The best setting (Bollinger 50 / 2.5, 5-min) improves the forecast by 0.00025 - a quarter of the minimum worth having.
4. One network with all 359 inputs is no better than without them: holdout direction AUC 0.519 vs 0.518 (25,319 moves).
5. 54 rule settings (confidence, move threshold, 15/30 min, exits 20/30/45 points): none had a positive average after costs.
6. Bollinger bandwidth adds a little to "a move is coming" (volatility), nothing to direction.
7. Seven studies agree: HOW MUCH NIFTY moves in 15-30 min is predictable; WHICH WAY is a coin, whatever the indicator or setting.
8. Nothing passes; nothing goes on paper; no app change.
9. The Apr-Oct 2026 holdout has now been read 4 times today; any new idea needs new sessions or a forward test.
10. Details: research/HUNT_R126_PARAM_SWEEP.md (also R123-R125); code + tests in research/hunt/r126_param_sweep/.
