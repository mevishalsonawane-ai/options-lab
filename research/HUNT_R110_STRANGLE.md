# R110: can IraThinker's BANKNIFTY Monday strangle be made safe enough for Boss's account? (26 Oct 2026)

Boss, after R109: "yes, run R110 on the strangle". R109 found the strangle profitable on real prints but outside Boss's
account rule at 1 lot (monthly stop -Rs 13,000, drawdown limit Rs 40,000). R110 tested 8 pre-set changes aimed at its
bad weeks.

- **Pre-registration:** `research/hunt/r110_strangle/PREREG.md`, pushed before any R110 number. Code and 14 tests
  (incl. parity with R109 and look-ahead guards) were pushed before the first run.
- **Code and tables:** `research/hunt/r110_strangle/` (`results/report.md` has every column). The final window was read
  once (`results/FINAL_READ`).
- **Research only.** The app does not sell options; nothing changes on paper or live.

## Answer first

**No. 0 of 8 changes pass. None keeps the strangle both profitable and inside the account rule in every period.**

The changes split into two groups:

1. **Changes that protect the account make almost no money.**
   - **Wings** (buying further-out options as insurance) keep every month above -Rs 13,000 in design and the final
     (W2 had one -Rs 14,037 month in 2021-24), but they cost most of the profit. Wings at +-700 made +Rs 10,899 in design (69 weeks), -Rs 13,724 in the final (26 weeks) and -Rs 70,130 in
     2021-24 (152 weeks). B0 made +Rs 1,67,686 on the same design weeks.
   - The best hedged version, wings at +-1000 (W2), made +Rs 38,531 in design (63 weeks) and +Rs 52 in the final
     (23 weeks).
   - On a ~Rs 1,000-point strangle, insurance takes a third to half of the premium every week, and the insured weeks
     are too rare to pay for it.
2. **Changes that keep the money do not protect the account.**
   - **Index stop at the breakevens (S2)** is the best trade-off: the same design profit (+Rs 1,68,645 vs B0's
     +Rs 1,67,686, 69 weeks) and a smaller design drawdown (Rs 24,912 vs Rs 34,925). But its worst month was still
     -Rs 18,247 in design and -Rs 29,072 in the final. Its worst week was -Rs 34,490 (final): the index jumped past the
     stop between sessions, and a stop cannot fill inside a gap.
   - **Selling further out (+-500)**, a **premium stop**, and **skipping weeks after a 2-sigma day** all kept most of
     the profit, but each broke the -Rs 13,000 month at least once.
   - **Selling only when options look expensive (F1)** kept too few weeks (35 design, 11 final) and did worse than
     skipping the same number of weeks at random (twin p 0.84).

**What this means for Boss:** at Boss's account size, one BANKNIFTY strangle lot cannot be made to fit the -Rs 13,000
monthly stop. The rule would need either more capital behind it or a much smaller contract. Nothing goes on paper.

## Results (1 lot, after charges and slippage; weeks = n)

| test | period | weeks | win | net | worst week | worst month | max drawdown | margin (est.) |
|---|---|---|---|---|---|---|---|---|
| **B0** R109 strangle (+-200) | design | 69 | 72% | Rs 1,67,686 | -Rs 31,283 | -Rs 24,307 | Rs 34,925 | Rs 2.26 lakh |
| | final | 26 | 69% | Rs 61,920 | -Rs 31,696 | -Rs 32,436 | Rs 37,926 | Rs 2.19 lakh |
| W1 wings +-700 | design | 69 | 48% | Rs 10,899 | -Rs 9,969 | -Rs 8,203 | Rs 20,284 | Rs 15,761 |
| | final | 26 | 42% | -Rs 13,724 | -Rs 6,925 | -Rs 12,765 | Rs 14,244 | Rs 15,000 |
| W2 wings +-1000 | design | 63 | 60% | Rs 38,531 | -Rs 18,796 | -Rs 12,661 | Rs 31,462 | Rs 25,333 |
| | final | 23 | 52% | Rs 52 | -Rs 10,080 | -Rs 12,081 | Rs 13,508 | Rs 24,000 |
| X1 shorts +-500 | design | 69 | 75% | Rs 1,45,994 | -Rs 26,386 | -Rs 26,790 | Rs 33,943 | Rs 2.26 lakh |
| | final | 26 | 77% | Rs 65,995 | -Rs 30,561 | -Rs 25,125 | Rs 33,359 | Rs 2.19 lakh |
| X1W shorts +-500, wings +-1000 | design | 63 | 65% | Rs 12,096 | -Rs 13,900 | -Rs 9,268 | Rs 22,488 | Rs 15,833 |
| | final | 23 | 43% | Rs 3,942 | -Rs 6,762 | -Rs 5,904 | Rs 8,495 | Rs 15,000 |
| S1 premium stop at 1x credit | design | 69 | 71% | Rs 1,64,235 | -Rs 29,557 | -Rs 31,620 | Rs 42,583 | Rs 2.26 lakh |
| | final | 26 | 69% | Rs 52,602 | -Rs 31,696 | -Rs 32,436 | Rs 37,926 | Rs 2.19 lakh |
| **S2 index breakeven stop** | design | 69 | 65% | Rs 1,68,645 | -Rs 19,800 | -Rs 18,247 | Rs 24,912 | Rs 2.26 lakh |
| | final | 26 | 65% | Rs 59,572 | -Rs 34,490 | -Rs 29,072 | Rs 40,720 | Rs 2.19 lakh |
| F1 IV/RV >= its 52-week median | design | 35 | 66% | Rs 45,015 | -Rs 31,283 | -Rs 31,283 | Rs 35,530 | Rs 2.19 lakh |
| | final | 11 | 73% | Rs 46,993 | -Rs 2,946 | -Rs 4,747 | Rs 5,666 | Rs 2.18 lakh |
| F2 skip after a 2-sigma day | design | 62 | 74% | Rs 1,64,064 | -Rs 31,283 | -Rs 24,307 | Rs 43,283 | Rs 2.25 lakh |
| | final | 24 | 71% | Rs 53,364 | -Rs 31,696 | -Rs 32,436 | Rs 37,926 | Rs 2.19 lakh |

**History (2021 to Nov 2024, descriptive):** B0 +Rs 1,01,345 (155 weeks); W1 -Rs 70,130 (152); W2 -Rs 43,364 (136); X1
+Rs 1,06,639 (154); X1W -Rs 41,129 (136); S1 +Rs 97,922 (155); S2 +Rs 1,22,083 (155, worst week -Rs 14,347); F1
+Rs 59,138 (67); F2 +Rs 83,179 (135). The walk-forward window and the 2 post-13 Oct weeks are in `results/report.md`.

**Gates** (all must hold; never relaxed):
- net > 0 in design, walk-forward and final;
- worst month >= -Rs 13,000 and drawdown < Rs 40,000 in all three;
- design bootstrap p < 0.05 (the filters also had to beat random skipping);
- BH q <= 0.10 over 14,261 tests;
- >= 40 design / 15 final weeks.

What each test failed:
- W1, W2, X1W: significance (design bootstrap p 0.17-0.35); W1 also lost in the final.
- X1, S1, S2, F2: the -Rs 13,000 month. S1 and F2 also passed Rs 40,000 of drawdown in design, S2 in the final.
- F1: everything above, plus too few weeks.

BH q = 1.0 for all 8 (over R110 alone: 0.045-0.84).

## Limits (read before trusting the hedged rows)

- **The lake keeps only strikes within 10 steps of the money** (+-1,000 points on BANKNIFTY).
  - Wings at +-1,000 had no print near the exit in nearly every week (W2: 63 stale legs in 63 design weeks), so their
    exits were priced by Black-Scholes at the leg's last implied vol (R103's rule).
  - 22-25 weeks per period were skipped when a +-1,000 wing had no print at entry.
- **The hedged margins (Rs 15-25k) are the defined maximum loss, not a broker quote.** Brokers usually ask more for an
  iron condor.
- The final window is weak evidence: R109 had already read B0 there.
- S1 / S2 are checked at every minute's close up to 15:28. Overnight gaps fill at the next session's prices, which is
  why S2's final worst week (-Rs 34,490) is larger than its stop distance.
- An event filter (RBI, Budget) was considered and dropped: our event calendar ends in Mar 2026, so it could not be
  judged in the final.

## What would be worth trying next (leads, not tests)

- **A smaller contract.** NIFTY or FINNIFTY strangles have roughly the same rupee risk per lot as BANKNIFTY today, so
  this does not help by itself. Boss's -Rs 13,000 month needs risk per lot at about a third of today's. Nothing in the
  F&O lots on hand gets there.
- **S2's stop on more history.** S2 helped most in 2021-24 (worst week -Rs 14,347 vs -Rs 30,147) and in design, but not
  in the final. A real test needs fresh weeks: forward paper data from 20 Oct on.
- **Wider data.** A lake with +-20 strikes would let wings and wider shorts be priced on real fills instead of
  Black-Scholes.
