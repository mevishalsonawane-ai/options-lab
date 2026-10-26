# R114 for Boss: Donchian arms mornings only, everything closed at 10:00, in the full paper book

1. **Did not pass. Nothing changes on paper or live.** (First written as "R113"; renumbered before any number.)
2. The rule: R72 and R51 + Supertrend buy only until 09:55, and anything still open is sold at 10:00. Liquidity and R52 stay as today. All four arms share the cap of 2 open trades in one direction, as the app does.
3. It beat today's book in all three periods: design Rs 1,45,385 vs -Rs 1,33,339 (1,340 vs 3,271 trades); walk-forward Rs 1,32,920 vs Rs 21,247 (979 vs 2,387); final Rs 55,912 vs Rs 40,786 (521 vs 1,310).
4. It was also safer: final worst day -Rs 12,204 vs -Rs 14,149, max drawdown Rs 29,835 vs Rs 57,479, no month at or below -Rs 13,000 (today: 2).
5. Why it fails: random books do as well. Dropping the same number of Donchian trades at random gives Rs 39,599 on average, and 26% of 100,000 random books did as well as the rule. Selling at a random minute instead of 10:00 gives the same Rs 55,940 (p 0.50). The gain comes from fewer trades, not from the clock.
6. Counted against all 5,841 tests so far (R112's 10 and R113's 11 included): q = 1.0. The gate needs 0.10.
7. The places it frees cost money in the final: Liquidity and R52 took 71 extra trades and lost Rs 14,100 on them.
8. Other clocks, final: 09:45 Rs 57,970, 10:15 Rs 41,803, 10:30 Rs 31,265, 11:00 Rs 3,489. Earlier is better, but none was tested for a pass.
9. Weak evidence: the final months have been read many times. 14-19 Oct (4 days): the rule made Rs 987 vs today's Rs 7,325, too few to judge. Days after 19 Oct are not in the data yet (Dhan token expired).
10. Full write-up: `research/HUNT_R114_DONCHIAN_MORNING_CLOSE10.md`.
