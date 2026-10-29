# R131 result: Boss's ten ICT / SMC video strategies. 0 of 39 variants pass; nothing goes on paper
1. Tested as stated: Silver Bullet, Cameron, CISD, CRT, AMD, IFVG, Turtle Soup, Order Block, Breaker, OTE (3 variants each plus 9 wick-only).
2. Signal on the index spot (wicks), buy the ATM option; five indices, Aug 2021 - 19 Oct 2026; real charges and slippage; 1 lot.
3. Design (1,144 sessions, 189,025 trades): 35 of 39 variants lose after costs; the 30 together lose Rs 1.31 crore on 1 lot.
4. Before costs every setup is a coin flip on the index (-3 to +5 points a trade); the option's costs make it a loss.
5. Nearest miss S3-V3 (CISD as stated, 2R): design +Rs 71 a trade on 2,871 trades, all 4 folds up; holdout 407 trades, -Rs 165 each.
6. Holdout (read once, 137 sessions): 24 of the 30 lose; the winners with 50+ trades (S2-V2, S4-W) had lost in design.
7. Every variant with 50+ trades breaks the account stop (-Rs 40k drawdown / -Rs 13k month). BH q over all 39.
8. Boss's notes applied: no 65% rule for his own strategies (N3); wick-only confirmations added (N4) and they lose too.
9. Never tested before: CISD, CRT, AMD, IFVG, Breaker, OTE, Silver Bullet. Re-tests (Turtle Soup, Order Block, sweeps) agree with R62/R62b/R90/R91/R130.
10. Write-up: research/HUNT_R131_ICT_TEN.md; code, tests (32) and PREREG: research/hunt/r131_ict_ten/; entries r131-*.yaml (all rejected). Research only, no app change.

# R132 (same day): stop-losses, profit locks and R123's network on the R131 entries. 0 of 60 pass; one lead
1. Exits (-Rs 1k / -Rs 2k premium stop + 60% lock after +Rs 1k; index stop + lock; breakeven + 1R trail): 35 of 40 lose in design; none pass.
2. R123 network filter (take only trades its out-of-sample view expects to pay): CISD + filter passes every design gate (305 trades, 64% won, +Rs 276 a trade, q 0.04).
3. But the holdout period gave only 21 such trades (+Rs 205 each), under the 50 needed, and that period was already seen. A lead for a forward test, not a pass.
4. Write-up: research/HUNT_R132_ICT_EXITS.md; code and 15 tests: research/hunt/r132_ict_exits/; entries r132-*.yaml (all rejected).

# R133 (same day): all 39 R131 entries x 5 exits with R123's network filter. 0 of 185 pass; the filter helps
1. With the network filter 114 of 185 combinations make money in design (2023 - Mar 2026); without it nearly all lost.
2. 6 distinct combinations pass every design gate (4 are CISD), but each had only 22-35 trades in the holdout period (50 needed); 4 of 6 were positive there.
3. Profits lean on 2023-24, and ~285 rules have now been tried on the same data: a forward test from today is the only fair check (Boss's call).
4. Write-up: research/HUNT_R133_ICT_NN_EXITS.md; code: research/hunt/r133_ict_nn_exits/; entries r133-*.yaml (all rejected).

# R135-R137 (29 Oct): Boss's three ICT books, studied, measured, put in the network, and turned into a strategy. 0 pass
1. Study: research/ICT_BOOKS_STUDY.md. On Indian indices the books' "sweep then reverse" fails: sweeps of PDH/PDL, PMH/PML and the opening range continue more than normal (R135).
2. R136: the books' 35 concepts made R123's network worse (cell forecasts, and its CISD filter: 229 kept -Rs 4k vs 428 kept +Rs 94.5k). R123's network stays.
3. R137: continuation after a strong-swing break made +Rs 156-176 a trade in design (797 trades) but lost in the holdout (130 trades, -Rs 373 each); with R123's filter +Rs 1,130 a trade on only 83 trades.
4. The one lead is still R123's network filter; the R134 forward test is how it gets judged on fresh data.
