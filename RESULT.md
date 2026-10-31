# R139 result (31 Oct): Boss's multi-timeframe chart (4H trend, 1H level, 15m break, 5m FVG/OB, 3m entry), continuation version. 0 of 7 pass
1. Rule (pre-registered, signal on the index spot on every timeframe): 4H EMA20 trend; untaken 1H swing high; 15m close through it with the trend; pullback into the break's 5m FVG/OB; 3m close above the prior high; buy ATM option; stop under the pullback; 2R; out 15:20; 1 lot, real costs.
2. Full stack (C1), design Aug 2021 - Mar 2026: 358 trades, 37% won, -Rs 88 a trade, -Rs 31,611; no better than random entries (p 0.77).
3. Holdout Apr - 19 Oct 2026 (read once): C1 27 trades, 15% won, -Rs 917 each. Every variant lost there.
4. Layer test, 4H trend: adds nothing (-Rs 88 with it, -Rs 83 without); only halves the trades. 4H swing structure no better.
5. Layer test, 5m FVG/OB + 3m entry: makes it WORSE. Entering straight on the 15m break (C5) made +Rs 17 a trade (1,068 trades, p 0.02); waiting for the pullback and trigger turned that into -Rs 88.
6. C5 still fails: -Rs 5 at 2x slippage, q 0.14, drawdown -Rs 1.08 lakh, worst month -Rs 40k, 2025 -Rs 82k; holdout 131 trades -Rs 294 each.
7. 3R target (the chart's "high R:R") is breakeven in design (+Rs 2, 34% won) and lost Rs 944 a trade in the holdout. R123's network left too few trades (31, then 0).
8. None near Boss's 65% wins (11-43%). Matches R135/R137 (with-trend breaks continue slightly, too little to pay the option), R89/R90 (HTF filters cut trades, not losses), R131 (OB/FVG entries lose).
9. Write-up: research/HUNT_R139_MTF_CONTINUATION.md; code, 10 tests, PREREG: research/hunt/r139_mtf_continuation/; entries r139-c1..c7 (all rejected).
10. Research only, no app code; knowledge pack rebuilt. Verdict: rejected; nothing goes on paper or live.
