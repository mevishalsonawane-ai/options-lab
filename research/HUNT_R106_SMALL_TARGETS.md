# R106: would small fixed profits ("5 trades x Rs 1,000 a day") work on the paper arms?

26 Oct 2026. Boss: *"I would like to check if small orders could also work: like 5 orders each give me Rs 1,000. That
will also work for me. I'm not expecting Rs 20,000-30,000 per day. Will some changes like this work?"*

Pre-registered in `research/hunt/r106_small_targets/PREREG.md` (commit 8e8eb6e, pushed before any number). The design
read was committed (5683612) before the one final read (`results/FINAL_READ`). Code, 25 tests and every table:
`research/hunt/r106_small_targets/` (`results/report.md`). Signal source for every rule: **the index**. The arms' entry
signals do not change; only the option's exit changes (a target and, in some rules, a stop on the premium).

## The answer: no

**No. None of the 36 tested rules (9 rules x 4 arms) passed, and on every arm a fixed small target made less money than
today's rules.** Most rules lost money outright.

- **Final period (1 Apr - 13 Oct 2026), today's rules made Rs 3,12,978 on the four arms together.** On the same signals:
  - "+Rs 1,000 or -Rs 1,000 a trade" (T1000S) lost Rs 3,43,492;
  - "+Rs 1,000 with the arm's own stop" (T1000) lost Rs 2,52,385.
- **Why: the arms make their money from a few big trades, and a fixed target cuts exactly those trades.**
  - A small target raises the win rate a lot (R72: 46% to 67% at +Rs 500).
  - But a capped win needs a much higher win rate to break even: 73% at +Rs 500 on R72, and the arm only reached 67%.
  - Wins are capped at Rs 500-1,500, but losses are not small. With the arm's own -15% stop, a loss is about Rs 1,500 on
    NIFTY and Rs 3,000-4,000 on BANKNIFTY, FINNIFTY and MIDCPNIFTY.
  - A 1:1 stop (lose at most what you aim to win) needs a 50% win rate after charges. In practice it needs 47-54%,
    because some losses jump past the stop. The arms win 36-48% of the time with it. The one exception is R52 at
    +Rs 1,500, which wins 52%; it still needed 52% and ended at -Rs 163.
- **Days get steadier, but steadily negative.** "5 trades x Rs 1,000, stop the day at +Rs 5,000" (DAY5K), all arms
  together, final period (133 sessions):
  - worst day -Rs 8,185 (today: -Rs 69,940);
  - median day -Rs 651 (today: Rs 41);
  - 13% of days reached +Rs 5,000 (today: 39% of days ended at or above Rs 5,000);
  - net -Rs 14,673 (today: +Rs 3,12,978).
  It caps the bad days, but it also gives up the big days that pay for everything else.
- **Nothing changes on paper or live.**

## The break-even maths (exact dated charges and slippage, 15 Oct 2026 lots)

These are the typical 1-ITM premiums the arms paid in design. "Move" is the rise in premium a target needs, after
entry slippage and the round trip's charges (Rs 68-108 a lot). "1:1 stop" is how far the premium may fall before the
loss reaches the target amount.

| index (lot, typical premium) | +Rs 500: move / 1:1 stop | +Rs 1,000: move / 1:1 stop | +Rs 1,500: move / 1:1 stop | loss at the arm's -15% stop | break-even win rate with the -15% stop (500 / 1,000 / 1,500) | break-even win rate 1:1 |
|---|---|---|---|---|---|---|
| NIFTY (65, Rs 150) | +6.0% / -4.1% | +11.1% / -9.2% | +16.2% / -14.4% | Rs 1,560 | 76% / 61% / 51% | 50% |
| BANKNIFTY (30, Rs 654) | +3.1% / -1.9% | +5.6% / -4.4% | +8.2% / -7.0% | Rs 3,067 | 86% / 75% / 67% | 50% |
| FINNIFTY (60, Rs 315) | +3.2% / -1.9% | +5.9% / -4.6% | +8.5% / -7.2% | Rs 2,963 | 86% / 75% / 66% | 50% |
| MIDCPNIFTY (120, Rs 215) | +2.4% / -1.2% | +4.4% / -3.2% | +6.3% / -5.1% | Rs 4,036 | 89% / 80% / 73% | 50% |
| SENSEX (20, Rs 463) | +6.2% / -4.5% | +11.6% / -9.9% | +17.0% / -15.3% | Rs 1,472 | 75% / 60% / 50% | 50% |

Not every losing trade reaches the full -15% (time stops cut many earlier), so the arms' real break-even win rates are
lower than this worst case. The tables below show the real ones from each rule's own trades (average loss / (average win
+ average loss)).
- At +Rs 500, a 1:1 stop sits only 1-4% below the fill, so ordinary noise hits it.
- A stop cannot stop a price that jumps through it. R72 design with the 1:1 Rs 1,000 stop: 85 of 3,337 trades lost
  more than Rs 1,100, the worst Rs 9,603 (NIFTY, 7 Apr 2025, 09:20).

## Before / after, final period (1 Apr - 13 Oct 2026, read once), 1 lot, net of charges and slippage

n = trades. Break-even win is computed from the rule's own trades.

| arm | today's rules: n, win, net, worst day | best small-target rule in the final | +Rs 1,000 own stop (T1000) | +Rs 1,000 / -Rs 1,000 (T1000S) | 5 trades x Rs 1,000 (DAY5K) |
|---|---|---|---|---|---|
| Liquidity 15+5 | 357, 37%, Rs 11,155, -Rs 11,405 | T1500S: 358, 42% (needs 47%), -Rs 30,844 | 358, 48% (needs 55%), -Rs 43,169 | 358, 42% (needs 48%), -Rs 36,318 | 407, 40% (needs 49%), -Rs 57,989 |
| R72 Breadth Donchian | 1,202, 46%, Rs 1,71,366, -Rs 22,571 | DAY5K: 624, 49% (needs 51%), -Rs 15,853 | 1,266, 55% (needs 59%), -Rs 1,02,284 | 1,268, 45% (needs 50%), -Rs 1,18,553 | 624, 49% (needs 51%), -Rs 15,853 |
| R51 Donchian 20 | 1,781, 44%, Rs 81,845, -Rs 23,012 | DAY5K: 661, 49% (needs 52%), -Rs 39,842 | 1,857, 55% (needs 58%), -Rs 1,27,083 | 1,861, 45% (needs 50%), -Rs 1,58,421 | 661, 49% (needs 52%), -Rs 39,842 |
| R52 Open outside value | 293, 52%, Rs 48,612, -Rs 16,554 | T1500: 293, 63% (needs 60%), Rs 31,274 | 293, 71% (needs 69%), Rs 20,151 | 293, 48% (needs 53%), -Rs 30,200 | 391, 49% (needs 53%), -Rs 33,021 |

In design and the walk-forward window, too, no rule made as much as today's rules on any arm:
- Liquidity: best T1500, walk-forward Rs 70,035 vs Rs 2,38,064;
- R72: best T1500, Rs 1,42,418 vs Rs 3,35,882;
- R51: every rule negative, vs Rs 1,75,789;
- R52: best T1500, Rs 1,19,054 vs Rs 2,47,057.

R52 is the only arm where a target still made money in the final. Its +Rs 1,500 target made Rs 31,274 against today's
Rs 48,612: it won more often (63% vs 52%) and made 36% less.

## What "5 trades x Rs 1,000" would really have produced per day (all four arms together, 1 lot)

The rule: each arm's own signals, sell at +Rs 1,000, stop at -Rs 1,000. After a target, buy the same signal again. Stop
for the day at 5 trades, or once the closed trades reach +Rs 5,000. Every session counts, including days with no trade.

| period | sessions | median day | days reaching +Rs 5,000 | green days | worst day | net | today's rules: median day / days >= Rs 5,000 / worst day / net |
|---|---|---|---|---|---|---|---|
| design (20 Nov 2024 - 31 Mar 2026) | 337 | -Rs 991 | 13% | 44% | -Rs 11,598 | -Rs 1,73,993 (1,678 trades) | -Rs 1,285 / 34% / -Rs 1,21,774 / +Rs 7,74,750 (9,156 trades) |
| final (1 Apr - 13 Oct 2026) | 133 | -Rs 651 | 13% | 48% | -Rs 8,185 | -Rs 14,673 (665 trades) | Rs 41 / 39% / -Rs 69,940 / +Rs 3,12,978 (3,633 trades) |
| after 13 Oct (14, 15, 16, 19 Oct) | 4 | -Rs 3,994 | 0% | 0% | -Rs 5,251 | -Rs 14,232 (20 trades) | -Rs 4,419 / 25% / -Rs 43,216 / -Rs 13,092 (95 trades) |

So the plan does make the days calmer: the worst day falls from about -Rs 70k-1.2 lakh to about -Rs 8k-12k. But the
typical day is a small loss, and only about 1 day in 8 reaches Rs 5,000. The ~5 trades a day are mostly the early
breaks. Their wins are capped at Rs 1,000, and each loss costs about Rs 1,000 (more when the price jumps through the stop).
At a 45-51% win rate, that adds up to a slow loss.

## What can go on paper

**Nothing.** No rule passed the gates, so no rule may act on paper, and nothing goes live. The arms keep today's exits.
If Boss still wants "small and steady", the honest options are:
- trade fewer lots: the same edge at less money per day;
- or keep the arms' exits and use the account-level day and week limits already on paper (decision 6 of 25 Oct) to cap
  the bad days.
Capping the wins is the one thing the data says not to do.

## Gates (PREREG section 5) and the multiple-testing count

A rule had to clear all of these:
- positive net in design, in the walk-forward window and in the final;
- above today's rules in the walk-forward window and the final (or within 5% of them with a worst day and drawdown at
  least 20% smaller);
- beat its random-exit twin at BH q <= 0.10, counted against R106's 36 tests plus the 513 earlier target / lock /
  filter tests (R64-R75, R101, R104), 549 in all;
- at least 40 final trades.

**0 of 36 pass.** Not one rule cleared even the first two gates on any arm.
- The best exit-twin q is 0.27, for R51 T1000, T1500 and T1000R, and for R72 T1500. These rules' fixed exits did beat
  random early exits (twin p 0.002), but all four lost money.
- Within R106 alone, their q would be 0.018; they still fail on net.

## What was tested (9 rules, each on Liquidity 15+5, R72, R51 and R52)

| rule | target (net, per lot) | stop | more entries |
|---|---|---|---|
| T500 / T1000 / T1500 | Rs 500 / 1,000 / 1,500 | the arm's own | - |
| T500S / T1000S / T1500S | Rs 500 / 1,000 / 1,500 | 1:1 (loss = the target, after charges) | - |
| T1000R | Rs 1,000 | own | after a target, buy the same signal again (the index still beyond the signal bar's close; Liquidity: its index exits not yet fired), up to 5 an index a day |
| T1000SR | Rs 1,000 | 1:1 | as T1000R |
| DAY5K | Rs 1,000 | 1:1 | as T1000R, and stop the arm's day at 5 trades or +Rs 5,000 closed |

How it was measured:
- **Engine:** R101's (the same candidates, the same R64 held-contract simulator, R51 clean stores, R17 fills, dated
  Zerodha charges, 1 lot).
- **Today's rules reproduced R101 to the rupee:**
  - Liquidity 881 / Rs 2,57,249 and 357 / Rs 11,155;
  - R72 3,071 / Rs 2,16,929 and 1,202 / Rs 1,71,366;
  - R51 4,498 / Rs 9,998 and 1,781 / Rs 81,845;
  - R52 Rs 2,90,573 and Rs 48,612.
- **Engine check:** with no rupee levels, the R106 engine prices every candidate exactly as R101 did (asserted).
- **Target:** a resting limit at the premium that nets exactly +T after that trade's own charges.
- **1:1 stop:** a resting stop that nets -T after the stop-sale slippage. A gap through it fills at the minute's open.

## Solo 2.0 and the other paper arms

- **Solo 2.0 (R57 A2):** its design picks reproduce R57 exactly (166 trades, Rs 55,910 in Jul 2025 - Mar 2026).
  - On those picks, every rule made less than Rs 55,910: best T1000 Rs 29,887, T1500 Rs 27,819, T1500S Rs 23,966. So
    every Solo rule already fails gate 2.
  - Its final picks did not reproduce R57's published figure: 128 trades as published, but Rs 1,00,737 against
    Rs 99,172. One day's pick moved from NIFTY to MIDCPNIFTY. It was the same with the pinned scikit-learn 1.5.2, so the
    likely cause is a revised lake file.
  - As pre-registered, Solo's final is therefore **not run**, and Solo is not in the 36 tests. Its design result is in
    the table below.
- **Not tested, with the reasons:**
  - the Hero Rs 1,000 ticket: its design is the rare multi-x winner;
  - FT3: a frozen forward test;
  - MCX paper arms: another lake;
  - hidden arms.

## Honest limits

- **The final window is not fresh.** It has been read by R51-R104. Every rule already failed in design and the
  walk-forward window, so the final only confirms that.
- **The window after 13 Oct is tiny:** 4 sessions, 14-19 Oct (the lake ends on 19 Oct). It is never gated.
- **Each arm is 1 lot,** and arms are added together. The paper account's own rules are not modelled (the account stop,
  the 2% cap, no opposite bets, the weekly stop). In the pooled view, two arms may hold the same index at once.
- **Re-entry definition:** a re-entry buys the 1-ITM strike of the minute before. For Liquidity this comes from the
  rolling ATM, not the app's index-close ATM.
- **Brain tests:** `research/brain/tests` gives 6 failures and 3 errors in this container (library versions; the same
  count the previous session saw), and R106 does not touch `research/brain/`. `features catalog --check` passes.

## Every rule, every period (1 lot; post = 14-19 Oct)


### Liquidity 15+5

| rule | design net (n) | walk-forward net | final net (n) | final win / break-even win | final worst day | final max drawdown | final green days | post 14-19 Oct net (n) | twin p / q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **today (B0)** | Rs 257,249 (881) | Rs 238,064 | Rs 11,155 (357) | 37% / 36% | -Rs 11,405 | -Rs 61,062 | 41% | -Rs 7,105 (15) |  |  |
| T500 (sell at +Rs 500, own stop) | Rs 4,829 (886) | Rs 16,847 | -Rs 48,904 (358) | 58% / 67% | -Rs 8,044 | -Rs 50,623 | 46% | -Rs 4,432 (15) | 1.0 / 1.0 | fail |
| T1000 (sell at +Rs 1,000, own stop) | Rs 45,003 (886) | Rs 40,730 | -Rs 43,169 (358) | 48% / 55% | -Rs 8,917 | -Rs 51,236 | 46% | -Rs 5,788 (15) | 0.998 / 1.0 | fail |
| T1500 (sell at +Rs 1,500, own stop) | Rs 83,523 (885) | Rs 70,035 | -Rs 40,324 (358) | 44% / 49% | -Rs 11,405 | -Rs 58,946 | 44% | -Rs 5,569 (15) | 0.988 / 1.0 | fail |
| T500S (+Rs 500 / -Rs 500 (1:1)) | -Rs 50,878 (887) | -Rs 35,476 | -Rs 52,506 (358) | 36% / 51% | -Rs 4,049 | -Rs 52,516 | 34% | -Rs 2,295 (15) | 1.0 / 1.0 | fail |
| T1000S (+Rs 1,000 / -Rs 1,000 (1:1)) | Rs 17,385 (887) | Rs 24,208 | -Rs 36,318 (358) | 42% / 48% | -Rs 5,273 | -Rs 38,715 | 45% | -Rs 4,892 (15) | 1.0 / 1.0 | fail |
| T1500S (+Rs 1,500 / -Rs 1,500 (1:1)) | Rs 63,986 (886) | Rs 48,366 | -Rs 30,844 (358) | 42% / 47% | -Rs 7,734 | -Rs 48,003 | 44% | -Rs 3,914 (15) | 1.0 / 1.0 | fail |
| T1000R (+Rs 1,000, own stop, re-enter after a target (<= 5 an index a day)) | -Rs 9,122 (1320) | -Rs 2,704 | -Rs 59,379 (493) | 49% / 55% | -Rs 11,992 | -Rs 72,005 | 39% | -Rs 5,360 (20) | 1.0 / 1.0 | fail |
| T1000SR (+Rs 1,000 / -Rs 1,000, re-enter after a target (<= 5 an index a day)) | -Rs 39,295 (1240) | -Rs 25,677 | -Rs 50,014 (474) | 42% / 49% | -Rs 5,273 | -Rs 53,204 | 37% | -Rs 7,937 (20) | 1.0 / 1.0 | fail |
| DAY5K (+Rs 1,000 / -Rs 1,000, re-enter, stop the day at 5 trades or +Rs 5,000) | -Rs 15,392 (975) | Rs 14 | -Rs 57,989 (407) | 40% / 49% | -Rs 5,032 | -Rs 57,989 | 35% | -Rs 5,428 (16) | 1.0 / 1.0 | fail |

### R72 Breadth Donchian

| rule | design net (n) | walk-forward net | final net (n) | final win / break-even win | final worst day | final max drawdown | final green days | post 14-19 Oct net (n) | twin p / q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **today (B0)** | Rs 216,929 (3071) | Rs 335,882 | Rs 171,366 (1202) | 46% / 41% | -Rs 22,571 | -Rs 61,884 | 52% | -Rs 624 (25) |  |  |
| T500 (sell at +Rs 500, own stop) | -Rs 202,118 (3408) | -Rs 37,377 | -Rs 137,073 (1285) | 67% / 73% | -Rs 29,151 | -Rs 156,508 | 50% | Rs 2,944 (29) | 0.996 / 1.0 | fail |
| T1000 (sell at +Rs 1,000, own stop) | -Rs 160,150 (3329) | Rs 10,727 | -Rs 102,284 (1266) | 55% / 59% | -Rs 26,057 | -Rs 157,294 | 49% | Rs 1,096 (29) | 0.0279 / 1.0 | fail |
| T1500 (sell at +Rs 1,500, own stop) | -Rs 6,095 (3255) | Rs 142,418 | -Rs 61,743 (1248) | 49% / 51% | -Rs 23,060 | -Rs 152,344 | 49% | -Rs 6,706 (29) | 0.002 / 0.2745 | fail |
| T500S (+Rs 500 / -Rs 500 (1:1)) | -Rs 317,428 (3423) | -Rs 221,478 | -Rs 193,060 (1289) | 37% / 52% | -Rs 7,000 | -Rs 193,060 | 29% | -Rs 4,460 (29) | 1.0 / 1.0 | fail |
| T1000S (+Rs 1,000 / -Rs 1,000 (1:1)) | -Rs 278,367 (3337) | -Rs 119,221 | -Rs 118,553 (1268) | 45% / 50% | -Rs 11,204 | -Rs 132,234 | 43% | -Rs 1,866 (29) | 1.0 / 1.0 | fail |
| T1500S (+Rs 1,500 / -Rs 1,500 (1:1)) | -Rs 79,869 (3255) | Rs 25,093 | -Rs 74,033 (1250) | 46% / 49% | -Rs 20,072 | -Rs 118,248 | 46% | -Rs 11,603 (29) | 1.0 / 1.0 | fail |
| T1000R (+Rs 1,000, own stop, re-enter after a target (<= 5 an index a day)) | -Rs 163,243 (4910) | Rs 53,161 | -Rs 35,971 (1860) | 58% / 58% | -Rs 24,754 | -Rs 112,691 | 47% | Rs 65 (46) | 0.008 / 0.8784 | fail |
| T1000SR (+Rs 1,000 / -Rs 1,000, re-enter after a target (<= 5 an index a day)) | -Rs 373,100 (4563) | -Rs 180,295 | -Rs 150,827 (1765) | 46% / 50% | -Rs 12,410 | -Rs 180,851 | 36% | -Rs 3,765 (43) | 1.0 / 1.0 | fail |
| DAY5K (+Rs 1,000 / -Rs 1,000, re-enter, stop the day at 5 trades or +Rs 5,000) | -Rs 168,520 (1561) | -Rs 76,632 | -Rs 15,853 (624) | 49% / 51% | -Rs 7,676 | -Rs 39,965 | 47% | -Rs 4,492 (15) | 1.0 / 1.0 | fail |

### R51 Donchian 20

| rule | design net (n) | walk-forward net | final net (n) | final win / break-even win | final worst day | final max drawdown | final green days | post 14-19 Oct net (n) | twin p / q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **today (B0)** | Rs 9,998 (4498) | Rs 175,789 | Rs 81,845 (1781) | 44% / 42% | -Rs 23,012 | -Rs 91,626 | 47% | Rs 1,927 (49) |  |  |
| T500 (sell at +Rs 500, own stop) | -Rs 346,770 (4782) | -Rs 157,683 | -Rs 199,218 (1880) | 66% / 72% | -Rs 18,819 | -Rs 199,218 | 35% | -Rs 3,301 (53) | 0.9481 / 1.0 | fail |
| T1000 (sell at +Rs 1,000, own stop) | -Rs 311,891 (4699) | -Rs 137,881 | -Rs 127,083 (1857) | 55% / 58% | -Rs 23,104 | -Rs 155,022 | 44% | -Rs 9,483 (53) | 0.002 / 0.2745 | fail |
| T1500 (sell at +Rs 1,500, own stop) | -Rs 282,254 (4633) | -Rs 84,154 | -Rs 115,126 (1826) | 48% / 51% | -Rs 23,012 | -Rs 149,111 | 47% | -Rs 15,382 (53) | 0.002 / 0.2745 | fail |
| T500S (+Rs 500 / -Rs 500 (1:1)) | -Rs 446,099 (4808) | -Rs 304,341 | -Rs 228,566 (1891) | 40% / 52% | -Rs 7,772 | -Rs 228,566 | 25% | -Rs 7,948 (53) | 1.0 / 1.0 | fail |
| T1000S (+Rs 1,000 / -Rs 1,000 (1:1)) | -Rs 390,085 (4712) | -Rs 221,743 | -Rs 158,421 (1861) | 45% / 50% | -Rs 11,202 | -Rs 158,973 | 37% | -Rs 4,170 (53) | 1.0 / 1.0 | fail |
| T1500S (+Rs 1,500 / -Rs 1,500 (1:1)) | -Rs 270,410 (4642) | -Rs 142,682 | -Rs 128,452 (1830) | 45% / 48% | -Rs 19,525 | -Rs 142,406 | 41% | -Rs 16,632 (53) | 1.0 / 1.0 | fail |
| T1000R (+Rs 1,000, own stop, re-enter after a target (<= 5 an index a day)) | -Rs 367,315 (6386) | -Rs 146,672 | -Rs 228,908 (2471) | 54% / 59% | -Rs 23,088 | -Rs 252,714 | 40% | -Rs 9,731 (77) | 0.002 / 0.2745 | fail |
| T1000SR (+Rs 1,000 / -Rs 1,000, re-enter after a target (<= 5 an index a day)) | -Rs 503,371 (6128) | -Rs 301,320 | -Rs 255,863 (2415) | 44% / 50% | -Rs 12,199 | -Rs 262,355 | 30% | -Rs 8,127 (72) | 1.0 / 1.0 | fail |
| DAY5K (+Rs 1,000 / -Rs 1,000, re-enter, stop the day at 5 trades or +Rs 5,000) | -Rs 162,663 (1670) | -Rs 97,080 | -Rs 39,842 (661) | 49% / 52% | -Rs 6,668 | -Rs 56,893 | 50% | -Rs 8,264 (20) | 1.0 / 1.0 | fail |

### R52 Open outside value

| rule | design net (n) | walk-forward net | final net (n) | final win / break-even win | final worst day | final max drawdown | final green days | post 14-19 Oct net (n) | twin p / q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| **today (B0)** | Rs 290,573 (706) | Rs 247,057 | Rs 48,612 (293) | 52% / 47% | -Rs 16,554 | -Rs 47,194 | 55% | -Rs 7,291 (6) |  |  |
| T500 (sell at +Rs 500, own stop) | Rs 9,206 (706) | Rs 66,134 | -Rs 8,903 (293) | 82% / 83% | -Rs 16,554 | -Rs 31,467 | 73% | -Rs 2,463 (6) | 0.998 / 1.0 | fail |
| T1000 (sell at +Rs 1,000, own stop) | Rs 56,613 (706) | Rs 82,814 | Rs 20,151 (293) | 71% / 69% | -Rs 16,554 | -Rs 41,522 | 65% | -Rs 463 (6) | 0.7186 / 1.0 | fail |
| T1500 (sell at +Rs 1,500, own stop) | Rs 102,322 (706) | Rs 119,054 | Rs 31,274 (293) | 63% / 60% | -Rs 16,554 | -Rs 44,118 | 63% | Rs 1,537 (6) | 0.2375 / 1.0 | fail |
| T500S (+Rs 500 / -Rs 500 (1:1)) | -Rs 55,627 (706) | -Rs 39,256 | -Rs 37,206 (293) | 43% / 54% | -Rs 7,096 | -Rs 37,725 | 52% | -Rs 2,989 (6) | 1.0 / 1.0 | fail |
| T1000S (+Rs 1,000 / -Rs 1,000 (1:1)) | -Rs 76,037 (706) | -Rs 37,643 | -Rs 30,200 (293) | 48% / 53% | -Rs 5,656 | -Rs 35,229 | 53% | -Rs 1,994 (6) | 1.0 / 1.0 | fail |
| T1500S (+Rs 1,500 / -Rs 1,500 (1:1)) | -Rs 51,478 (706) | -Rs 11,433 | -Rs 163 (293) | 52% / 52% | -Rs 8,296 | -Rs 34,857 | 53% | -Rs 2,985 (6) | 1.0 / 1.0 | fail |
| T1000R (+Rs 1,000, own stop, re-enter after a target (<= 5 an index a day)) | Rs 62,421 (1935) | Rs 59,112 | -Rs 68,974 (690) | 67% / 70% | -Rs 16,554 | -Rs 93,466 | 50% | -Rs 2,591 (16) | 0.3054 / 1.0 | fail |
| T1000SR (+Rs 1,000 / -Rs 1,000, re-enter after a target (<= 5 an index a day)) | -Rs 107,454 (1272) | -Rs 89,640 | -Rs 54,425 (509) | 47% / 52% | -Rs 6,094 | -Rs 59,644 | 42% | -Rs 3,991 (8) | 1.0 / 1.0 | fail |
| DAY5K (+Rs 1,000 / -Rs 1,000, re-enter, stop the day at 5 trades or +Rs 5,000) | -Rs 82,667 (900) | -Rs 50,132 | -Rs 33,021 (391) | 49% / 53% | -Rs 5,656 | -Rs 42,688 | 44% | -Rs 3,991 (8) | 1.0 / 1.0 | fail |

### Solo 2.0 (A2 picks; design = its walk-forward months Jul 2025 - Mar 2026)

| rule | net (n) | win / break-even win | worst day | max drawdown |
|---|---|---|---|---|
| B0 | Rs 55,910 (166) | 57% / 50% | -Rs 10,370 | -Rs 25,843 |
| T500 | Rs 10,482 (166) | 87% / 86% | -Rs 7,890 | -Rs 11,904 |
| T1000 | Rs 29,887 (166) | 78% / 73% | -Rs 7,890 | -Rs 13,025 |
| T1500 | Rs 27,819 (166) | 67% / 63% | -Rs 7,890 | -Rs 14,743 |
| T500S | -Rs 15,015 (166) | 42% / 51% | -Rs 1,015 | -Rs 16,036 |
| T1000S | Rs 4,159 (166) | 52% / 51% | -Rs 1,237 | -Rs 11,358 |
| T1500S | Rs 23,966 (166) | 54% / 49% | -Rs 1,982 | -Rs 12,798 |
| T1000R | -Rs 15,763 (442) | 70% / 71% | -Rs 10,863 | -Rs 39,793 |
| T1000SR | -Rs 786 (314) | 51% / 51% | -Rs 1,237 | -Rs 16,179 |
| DAY5K | -Rs 786 (314) | 51% / 51% | -Rs 1,237 | -Rs 16,179 |
