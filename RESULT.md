# R107 result for Boss (26 Oct 2026): GEX meter + danger meter to trade MORE

1. **Answer: no.** 0 of 11 pre-registered tests passed. Nothing changes on paper or live. Full write-up: `research/HUNT_R107_METERS_MORE.md`.
2. Tested: 2 lots on negative-GEX trades (4 arms), 2 lots on danger-flagged days (4 arms), and on the paper book a 3rd same-direction place on negative-GEX or flagged days, plus 2 lots on the meter's normal days.
3. **Both meters say "bigger day", not "better day".** Flagged days often pay more per trade, but they also hold the worst days (R72 -Rs 58,613 and R51 -Rs 56,647 in design), so doubling up there was no better than doubling on random days.
4. The GEX edge flipped over time: worse for R72 / R51 in design (n = 1,308 / 1,812 trades), better in the walk-forward window and the final. Win rates never moved (46% vs 46% on R72 in the final).
5. The best result, 2 lots on R72's flagged days (final twin p 0.044, 99 trades changed), failed in design. With 584 tests counted, BH q = 1.0.
6. "Always 2 lots" beat every meter rule on net per unit of risk, only because brokerage is a flat Rs 20 an order. It doubles the drawdown, so it is not recommended without its own test.
7. Account rule: today's rules already break the Rs 40,000 drawdown at 1 lot in the final (R72 Rs 61,884, R51 Rs 91,626 alone), and most variants make it worse.
8. Keep the danger meter's "half size" switch off: it would cut size on the better days for Liquidity, R72 and R51.
9. **Lead (not a test):** in the model, the same-direction cap of 2 cut the four arms from Rs 7.75 lakh to -Rs 4.03 lakh in design, and from Rs 3.13 lakh to Rs 0.59 lakh in the final. It mostly refuses R52, Liquidity and R51's winning trades. This needs an app check and its own pre-registered study.
10. Checks: 19 R107 tests and 183 brain tests passed; feature catalog up to date; ORB-on-negative-GEX (S3) was not re-run, because it is R28 G1, which already failed.
