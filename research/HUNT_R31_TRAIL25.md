# R31: Boss's 25% trailing exit on every hero study's entries

Written 14 Oct 2026, for Boss: "the trailing profit [lock] should be 25% of that highest". Clarified by Boss:
- give back 25% from the peak;
- running from the entry;
- applied to all earlier studies' entries.

- **Pre-registration:** `research/hunt/r31/PREREG.md`, commit `c4e7238`, pushed before any outcome was computed.
- **Code, tests and tables:** `research/hunt/r31/` (`results/results.json`).
- **Experiment entries:** `research/experiments/entries/r31-*.yaml` (26, all rejected).
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **The 25% trail is a much better exit than anything the studies used. But it does not turn any entry into a
  money-maker.**
  - **Losses shrink a lot.** On the same 26 entry sets, the total goes from **−Rs 12.08 lakh** with each study's own
    exit to **−Rs 4.75 lakh** with the 25% trail. 23 of the 26 improve.
  - **Why:** losers are cut near −25% instead of being held to zero, and quick spikes are banked before they fade.
- **Biggest improvements:**

  | entries | published exit | 25% trail |
  |---|---|---|
  | R28-S1 Dual-Supertrend | −76,771 | **−254** |
  | R30-G2 gamma flip | −34,878 | −1,895 |
  | R27-M2 ML ranker | −72,304 | −7,942 |
  | R30-G1 gamma flip | −40,003 | −5,842 |
  | R28-S3 | −1,16,661 | −16,599 |
  | Boss's late range breakout K1 | −1,17,776 | −56,595 |

- **Only one entry set ends positive:** R26-A3, the OI-wall blast without the price filter. It makes +Rs 2,545 on 35
  days, but the bootstrap p is 0.33 and it is −Rs 6,469 without its best day.
- **It hurts the one rule that was winning.** R27-F1, the fair-value rule, goes from +Rs 23,692 to **−Rs 20,206**:
  - F1's profit came from holding a few runners (17×, 13×, 10×) to 15:05;
  - a 25% trail sells those runners on their first normal pullback.
  - This is the classic trade-off: a tight trail stops the bleeding on losers, but it also stops the rare big winner
    that pays for them.
- **The real give-back is about 40-55%, not 25%.** The sale happens at the next minute's open after a close below the
  line, and these options often fall hard within one minute. That is real slippage, not a bug: a live stop order would
  meet the same gaps.
- **0 of 26 pass the gates.** The smallest BH q over the 78 tests (R21-R31) is 0.156.
- **What to do:** no app change. Two practical lessons for paper or manual trading:
  1. On these entries a trailing stop from the entry beats "hold to 15:05" or "4× or zero" almost everywhere. If Boss
     trades hero options by hand, a hard trail is the cheapest protection.
  2. A trail and a "let the runners run" rule pull against each other. No single exit captured both in this data.
     Mixing them (for example, sell half on the 25% trail and hold half to 15:05) would be a new pre-registered test.

## The exit (PREREG.md has every detail)

- **The peak** is the highest *confirmed* price since the fill: a minute's high counts only up to max(its close, the
  next open), so a freak print can't raise it. The peak starts at the fill price.
- **Stop = 75% of the peak**, from the minute after the fill.
- **A minute close at or below the stop** sells everything at the next minute's open, with spread and charges.
  Otherwise the position is sold at 15:05 (15:25 for the K rules, as they were tested).
- **Entries are each study's own published trades**: the same days, strikes, signal minutes and sizes.

## Results (26 entry sets; net Rs after all costs)

| entries | expiry days | hit % | 25% trail net | own exit net | per Rs 1,000 | avg give-back from peak | worst day | max DD | w/o best day | 2× spread | raw-high peak | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R26-P | 15 | 40.0 | −3,846 | −3,851 | −154 | 50.8% | −1,016 | −4,159 | −4,253 | −3,970 | −2,311 | 1.00 |
| R26-A1 | 6 | 33.3 | −1,559 | −3,763 | −171 | 50.7% | −897 | −1,559 | −2,570 | −1,601 | −1,856 | 1.00 |
| R26-A2 | 13 | 38.5 | −3,886 | −814 | −182 | 53.4% | −1,016 | −4,016 | −4,293 | −3,990 | −2,352 | 1.00 |
| **R26-A3** | 35 | 37.1 | **+2,545** | −10,338 | +42 | 48.6% | −996 | −4,738 | −6,469 | +2,166 | +2,795 | 1.00 |
| R26-A7 | 7 | 28.6 | −1,585 | −7,130 | −135 | 53.5% | −723 | −2,118 | −2,021 | −1,638 | −1,496 | 1.00 |
| R26-B1 | 18 | 38.9 | −4,160 | −7,572 | −141 | 50.1% | −1,016 | −4,666 | −4,904 | −4,307 | −2,305 | 1.00 |
| R27-M1 | 167 | 18.6 | −33,334 | −73,266 | −118 | 45.6% | −944 | −33,867 | −36,773 | −36,638 | −33,644 | 1.00 |
| R27-M2 | 141 | 25.5 | −7,942 | −72,304 | −33 | 46.1% | −930 | −14,762 | −16,579 | −10,416 | +7,282 | 1.00 |
| R27-F1 | 154 | 21.4 | −20,206 | **+23,692** | −79 | 46.7% | −1,115 | −21,961 | −25,619 | −23,199 | −20,369 | 1.00 |
| R27-G1 | 48 | 22.9 | −9,415 | −6,273 | −131 | 51.7% | −1,208 | −10,691 | −11,037 | −9,745 | −7,494 | 1.00 |
| R27-Q1 | 35 | 25.7 | −9,026 | −13,175 | −169 | 45.4% | −1,008 | −9,568 | −10,604 | −9,282 | −6,075 | 1.00 |
| R27-Q2 | 39 | 23.1 | −4,693 | −20,441 | −78 | 43.1% | −913 | −5,798 | −9,098 | −4,978 | −7,046 | 1.00 |
| R27-K1 | 194 | 13.9 | −56,595 | −1,17,776 | −153 | 52.2% | −1,512 | −68,555 | −69,382 | −73,332 | −70,068 | 1.00 |
| R27-K2 | 226 | 8.4 | −92,808 | −2,32,804 | −208 | 51.9% | −2,020 | −98,629 | −1,66,917 | −1,37,830 | −1,57,832 | 1.00 |
| R27-K3 | 104 | 0.0 | −1,27,399 | −1,64,852 | −607 | 55.4% | −2,024 | −1,27,399 | −1,27,347 | −1,71,443 | −1,26,236 | 1.00 |
| R28-S1 | 114 | 16.7 | −254 | −76,771 | −1 | 44.7% | −1,167 | −15,474 | −25,407 | −3,690 | +1,371 | 1.00 |
| R28-S3 | 163 | 13.5 | −16,599 | −1,16,661 | −57 | 45.6% | −1,167 | −24,474 | −41,752 | −21,178 | −11,186 | 1.00 |
| R28-S4 | 35 | 11.4 | −14,236 | −31,637 | −235 | 45.5% | −1,167 | −15,095 | −16,326 | −14,803 | −12,816 | 1.00 |
| R28-S5 | 70 | 14.3 | −9,200 | −56,251 | −75 | 45.9% | −1,107 | −17,836 | −23,013 | −11,839 | −21,410 | 1.00 |
| R29-X1 | 66 | 18.2 | −11,391 | −32,226 | −139 | 41.9% | −677 | −11,391 | −14,206 | −11,604 | −8,532 | 1.00 |
| R29-X2 | 98 | 14.3 | −21,015 | −42,909 | −134 | 47.4% | −819 | −21,811 | −24,534 | −21,579 | −16,707 | 1.00 |
| R29-X3 | 24 | 12.5 | −3,947 | −15,385 | −129 | 42.9% | −677 | −5,322 | −6,762 | −3,940 | −2,857 | 1.00 |
| R29-X4 | 26 | 11.5 | −6,316 | −12,807 | −207 | 44.4% | −677 | −6,316 | −6,869 | −6,457 | −4,876 | 1.00 |
| R30-G1 | 42 | 26.2 | −5,842 | −40,003 | −80 | 40.2% | −1,167 | −11,788 | −11,375 | −7,006 | −9,206 | 1.00 |
| R30-G2 | 34 | 29.4 | −1,895 | −34,878 | −32 | 39.2% | −1,167 | −8,596 | −7,429 | −2,840 | −5,614 | 1.00 |
| R30-G4 | 65 | 26.2 | −10,564 | −37,924 | −92 | 44.5% | −1,167 | −15,473 | −16,098 | −12,135 | −11,767 | 1.00 |

- **Totals:** own exits −Rs 12,08,119; 25% trail −Rs 4,75,168.
- **Worse with the trail:** R26-A2, R27-F1 and R27-G1. The other 23 improve (R26-P only by Rs 5).
- **Raw-high peak (sensitivity):** the peak from raw highs instead of confirmed prices moves results both ways, with no
  sign change except R27-M2 (+Rs 7,282) and R28-S1 (+Rs 1,371). Both are positive only in that unregistered variant.
- **K3 never wins** (0 of 104). Its breaks come after 15:00, with minutes left.

## Honest notes

- **These entries were all chosen by earlier rules** that already failed. A better exit on bad entries reduces the
  damage; it cannot create an edge that the entries don't have.
- **The exit fills at the next minute's open.** Fast options often gap, so the average give-back is 40-55% of the peak,
  not 25%. A resting stop-limit at the broker could do a little better or a lot worse (no fill on a gap).
- **The bootstrap p tests whether the mean per-day net is above 0.** It is not a random-timing control, because the
  entries are fixed from earlier studies.

## Checks run

- `python -m pytest research/hunt/r31/test_r31.py -q` passes 5 tests. They cover:
  - the stop from the entry cutting a loser at −25%;
  - the trail following the confirmed peak and selling at the next open;
  - a freak print not raising the stop (but raising it in raw mode);
  - the stop never acting in the bar where it is raised;
  - the 15:05 and 15:25 ends, the over-budget skip and 1-lot rules, and R25's entry limit restored afterwards.
- R26-R30 tests, the exit audit, the brain tests and the catalog check still pass. `python research/experiments/validate.py`,
  and `python3 research/knowledge/build_pack.py` then `--check`.
