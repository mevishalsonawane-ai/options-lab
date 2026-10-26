# R4 for Boss: before and after, and the live decision (26 Oct 2026)

Page (private): https://claude.ai/artifact/8woVgxj1mSw4XNouHmFgL9

1. **Not ready for live as paper runs it.** All four arms with today's rules and the cap of 2: Rs 40,786 in the backtest final period (1 Apr - 13 Oct 2026, 1,310 trades, 133 sessions), max drawdown Rs 57,479, 2 of 7 months at or below -Rs 13,000; it lost money in design (R108, R114).
2. **The account rule (-Rs 40k / -Rs 13k a month) is broken at 1 lot** by every set of the four arms in both periods (R108), and by each arm alone in the final (drawdowns Rs 47k-82k).
3. **Before vs after, final period, 1 lot:** R72 Rs 1,47,595 -> Rs 1,71,366; R51 Rs 55,439 -> Rs 1,34,743 (Supertrend); R52 Rs 48,955 -> Rs 48,612; Liquidity unchanged (Rs 11,155). The cap takes the book from Rs 1,68,769 to Rs 40,786 and its worst day from -Rs 38,529 to -Rs 14,149.
4. Only the Supertrend filter (R73) and h14's limit passed research; the 30-60-100 ladder and L25 are Boss's choices; the cap and Boss's 25 Oct rules are rules, not tested edges.
5. **Paper vs backtest (R105 method):** R72 and R52 inside their range; R51 at the low end; Liquidity outside (2nd percentile on 37 trades since 1 Oct): check it before any live use.
6. The phone's uploads cover 19-26 Oct only (no 13-18 Oct files) and carry no rupees; paper rupees on the page are rebuilt from order prices, estimates, and are not in git.
7. Every backtest row reproduces its published figure to the rupee (R101, R73, R108); an independent reviewer recomputed every table and percentile.
8. R104-R114: nothing passed (R114 finished, rejected; R113 and R114 are on their branches).
9. Recommendation, not an order: if Boss wants a live step, 1 lot of R72 only with broker stops and the account stop on; otherwise keep paper and re-read after 30 more sessions. Nothing live changes by itself.
10. Code: `research/reports/r4/` (generator, 18 tests); summary numbers: `research/reports/r4/results/backtest.json`.
