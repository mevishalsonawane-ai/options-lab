# R109 result for Boss (26 Oct 2026): what can IraAlgo learn from IraThinker?

1. **Answer: not a new strategy.** 0 of 8 pre-registered tests passed on our real option prints. Nothing goes on paper or live. Full write-up: `research/HUNT_R109_IRATHINKER_RULES.md`.
2. **mom60 lost before it won.** BANKNIFTY, buying after a 0.50% 60-minute move: -Rs 1.14 lakh in 2021-24 (n = 1,087 trades), -Rs 47,521 from 20 Nov 2024 to Mar 2026 (n = 307), then +Rs 63,263 from Apr to 13 Oct 2026 (n = 146). It was -Rs 4,181 in the 4 sessions since (n = 4).
3. FINNIFTY (+Rs 8,326 design, n = 314) and SENSEX (-Rs 24,954, n = 225) look similar, and so do NIFTY and MIDCPNIFTY. No version ever beat random entry minutes on the same days.
4. **The strangle earns, but breaks your account rule.** BANKNIFTY Monday strangle, 1 lot: +Rs 1.68 lakh in 69 weeks of design (72% win) and +Rs 61,920 in 26 final weeks. But the worst months were -Rs 24,307 and -Rs 32,436 against your -Rs 13k stop, worst week -Rs 31,696, margin about Rs 2.2 lakh.
5. **IraThinker's Black-Scholes paper log is not evidence.** It runs 0.5-3.7 points per trade too high for mom60 (before costs) and 11-31 points too low for the strangle.
6. **IraThinker's claims do not hold on real fills.** Its mom60 footers (+2.0% to +2.3% a trade) come out at -0.9% to -2.5% a trade on 2021-24 real fills with costs. The strangle's "+16.42% of premium, 77.8% win" comes out at +3% to +7.5% with a 70-74% win.
7. **mom60 duplicates our arms.** 90%+ of its BANKNIFTY trades fall on days R72 Breadth Donchian signalled the same way, 75% within an hour. It would mostly double our existing bets.
8. **What IraThinker should change:** label mom60 as "not confirmed on real prints", say which way its BS premium errs, and add the -Rs 32k month to the strangle's footer (see the write-up).
9. **Leads only, not tests:** mom60 at 3-7 days to expiry (BANKNIFTY +Rs 20,235, n = 58) and mom60's 0-2 day cell being the worst (as IraThinker already warns).
10. **Checks:** 27 R109 tests pass, incl. look-ahead guards. The final was read once. BH counted 14,253 tests (R1-R109). Feature catalog up to date. Brain tests: 174 pass and 9 fail or error, because this sandbox has pandas 3.0 instead of the pinned 2.2.3; `research/brain` is untouched.

# R110 result for Boss (26 Oct 2026): making the strangle fit your account

1. **Answer: no.** 0 of 8 changes pass. Nothing goes on paper or live. Full write-up: `research/HUNT_R110_STRANGLE.md`.
2. **The base, at 1 lot:** IraThinker's BANKNIFTY Monday strangle made +Rs 1.68 lakh in design (69 weeks) and +Rs 61,920 in the final (26 weeks), but had months of -Rs 24,307 and -Rs 32,436.
3. **Insurance wings keep every design and final month above -Rs 13k, but kill the profit.** Wings at +-700: +Rs 10,899 in design and -Rs 13,724 in the final. Wings at +-1000: +Rs 38,531 and +Rs 52.
4. **Best trade-off: the index stop at the breakevens.** Same design profit (+Rs 1,68,645) with a smaller drawdown (Rs 24,912 vs Rs 34,925). Still -Rs 18,247 and -Rs 29,072 months, and an overnight gap gave a -Rs 34,490 week.
5. **Selling further out, a premium stop, and skipping after 2-sigma days** keep most of the money, but all break the -Rs 13k month.
6. **"Only sell when options are expensive"** did worse than skipping the same number of weeks at random.
7. **Bottom line:** one BANKNIFTY strangle lot is too big for a -Rs 13,000 monthly stop. It needs more capital, or a much smaller contract than any index lot today.
8. **Caveats:** the wing results rely on Black-Scholes prices, because the lake keeps only +-10 strikes. The hedged margins (Rs 15-25k) are estimates, not broker quotes. The final window had been seen by R109.
9. **Checks:** 14 R110 tests pass, and B0 matches R109 week for week. The final was read once. BH counted 14,261 tests.
