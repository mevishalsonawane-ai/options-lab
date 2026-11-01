# R144: the other arms. Liquidity made money in the unseen years; the wide-day skip cuts losses on every arm, but misses one gate on each

1 Nov 2026. Research only. **Nothing in the app or any arm has changed.** Boss: *"And what about other strategies?
Liquidity and other strategies, have to tested it"*. Pre-registration: `research/hunt/r144_other_arms/PREREG.md`,
pushed before any number.

## Where every strategy stands now

| strategy | tested | what we know |
|---|---|---|
| R72 Breadth Donchian | R140-R143 | lost Rs 3.07 lakh in Dec 2022 - Nov 2024. **The wide-day skip held up as a loss-cutter in every period** |
| R51 Donchian 20 + Supertrend | R140-R144 | lost Rs 4.00 lakh in Dec 2022 - Nov 2024. The wide-day skip halves that loss, but has one worse month in Apr 2025 - Mar 2026 |
| R52 Open outside value | R142-R144 | lost Rs 1.52 lakh in Dec 2022 - Nov 2024. **The 09:25 exit held up.** The wide-day skip helps there but makes Apr - Oct 2026 worse |
| **Liquidity 15+5** | R140-R142, R144 | **made money in Dec 2022 - Nov 2024** (+Rs 83k on 894 trades, 31% won). Wide-day skip: fewer losses, a bit less profit. The 5-min exit hurts it |
| S5 Trail (R128), S3-V2-E3 (R133) | their own studies | already tested on 2023 - Oct 2026: **most of their money was in 2023-24, weaker since 2025**. Not re-run |
| ORB, Night (R3), Solo, MCX / gold arms | not yet | each needs its own backtest engine; a separate study if Boss wants it |

## The answer

1. **Liquidity 15+5 is the only arm that made money in the unseen two years:** +Rs 83,307 on 894 trades.
   - It wins only 31% of trades; its winners are big.
   - Its worst month there was -Rs 13,213, at Boss's -Rs 13k monthly stop.
2. **The wide-day skip works on every arm in the unseen years.** On days after a very wide day, breakouts fail more
   often. Skipping those days cut the losing days on R51, Liquidity and R52 (and R72 in R143), each better than random
   skipping (p 0.0005-0.012).
   - **But each version misses exactly one pre-registered gate:**
     - **W-51 (R51):** passes everything except its worst month in Apr 2025 - Mar 2026 (-Rs 58,186 vs -Rs 53,654);
     - **W-L (Liquidity):** losses down in every period, but in the unseen years profit fell a little (Rs 72,813 vs
       Rs 83,307);
     - **W-52 (R52):** helps in the unseen years and in 2024-26, but in Apr - Oct 2026 the drawdown and worst month
       got worse.
   - So formally **0 of 4 pass**. The pattern is real across arms, but on these three it is not clean enough to switch
     on.
3. **The 5-minute "no progress" exit is bad for Liquidity** (as R81's index version was). Liquidity's slow starters are
   its big winners. Fails.

## Before → after, every period (1 lot)

### Liquidity 15+5 as it runs today

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 | 27% | Rs 2,755 | Rs -105,302 | Rs 34,398 | Rs -2,909 | Rs -9,834 |
| Dec 2023 - Nov 2024 (unseen) | 524 | 34% | Rs 80,552 | Rs -165,208 | Rs 31,466 | Rs -5,742 | Rs -13,213 |
| **both unseen years** | 894 | 31% | Rs 83,307 | Rs -270,509 | Rs 51,118 | Rs -5,742 | Rs -13,213 |
| Nov 2024 - Mar 2026 | 881 | 44% | Rs 257,249 | Rs -289,743 | Rs 32,537 | Rs -8,416 | Rs -17,360 |
| Apr 2025 - Mar 2026 | 657 | 44% | Rs 238,064 | Rs -187,995 | Rs 23,139 | Rs -6,363 | Rs -17,360 |
| Apr - Oct 2026 | 357 | 37% | Rs 11,155 | Rs -144,353 | Rs 61,062 | Rs -11,405 | Rs -37,821 |

### W-51: R51, skip after a very wide day (fails: wf: worst month worse)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 2,151 → 1,702 | 41% → 41% | Rs -166,303 → **Rs -87,899** | Rs -402,354 → **Rs -282,685** | Rs 166,303 → Rs 93,565 | Rs -12,862 → Rs -11,083 | Rs -34,577 → Rs -28,466 |
| Dec 2023 - Nov 2024 (unseen) | 2,664 → 2,026 | 38% → 40% | Rs -234,060 → **Rs -103,114** | Rs -609,766 → **Rs -410,738** | Rs 275,160 → Rs 159,256 | Rs -17,346 → Rs -11,118 | Rs -51,440 → Rs -34,627 |
| **both unseen years** | 4,815 → 3,728 | 39% → 40% | Rs -400,363 → **Rs -191,013** | Rs -1,012,120 → **Rs -693,423** | Rs 423,510 → Rs 231,803 | Rs -17,346 → Rs -11,118 | Rs -51,440 → Rs -34,627 |
| Nov 2024 - Mar 2026 | 4,208 → 3,370 | 44% → 46% | Rs 50,425 → **Rs 166,051** | Rs -1,346,619 → **Rs -1,001,569** | Rs 214,239 → Rs 188,403 | Rs -49,738 → Rs -49,738 | Rs -69,428 → Rs -60,483 |
| Apr 2025 - Mar 2026 | 3,044 → 2,420 | 46% → 47% | Rs 213,748 → **Rs 298,350** | Rs -851,416 → **Rs -606,212** | Rs 94,176 → Rs 73,810 | Rs -28,942 → Rs -28,144 | Rs -53,654 → Rs -58,186 |
| Apr - Oct 2026 | 1,670 → 1,394 | 45% → 46% | Rs 134,743 → **Rs 157,384** | Rs -395,932 → **Rs -316,936** | Rs 81,683 → Rs 70,047 | Rs -21,578 → Rs -21,578 | Rs -54,587 → Rs -48,813 |

### W-L: Liquidity, skip after a very wide day (fails: unseen: net lower than today's)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 → 302 | 27% → 28% | Rs 2,755 → **Rs 14,272** | Rs -105,302 → **Rs -85,922** | Rs 34,398 → Rs 24,043 | Rs -2,909 → Rs -2,909 | Rs -9,834 → Rs -9,077 |
| Dec 2023 - Nov 2024 (unseen) | 524 → 407 | 34% → 35% | Rs 80,552 → **Rs 58,542** | Rs -165,208 → **Rs -121,276** | Rs 31,466 → Rs 20,581 | Rs -5,742 → Rs -5,742 | Rs -13,213 → Rs -6,199 |
| **both unseen years** | 894 → 709 | 31% → 32% | Rs 83,307 → **Rs 72,813** | Rs -270,509 → **Rs -207,198** | Rs 51,118 → Rs 37,926 | Rs -5,742 → Rs -5,742 | Rs -13,213 → Rs -9,077 |
| Nov 2024 - Mar 2026 | 881 → 704 | 44% → 46% | Rs 257,249 → **Rs 255,653** | Rs -289,743 → **Rs -220,922** | Rs 32,537 → Rs 24,971 | Rs -8,416 → Rs -8,416 | Rs -17,360 → Rs -7,794 |
| Apr 2025 - Mar 2026 | 657 → 511 | 44% → 47% | Rs 238,064 → **Rs 233,299** | Rs -187,995 → **Rs -132,985** | Rs 23,139 → Rs 16,165 | Rs -6,363 → Rs -6,363 | Rs -17,360 → Rs -3,147 |
| Apr - Oct 2026 | 357 → 299 | 37% → 38% | Rs 11,155 → **Rs 45,894** | Rs -144,353 → **Rs -104,079** | Rs 61,062 → Rs 41,839 | Rs -11,405 → Rs -11,405 | Rs -37,821 → Rs -24,036 |

### W-52: R52, skip after a very wide day (fails: final: drawdown worse; final: worst month worse)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 441 → 351 | 50% → 50% | Rs -43,627 → **Rs -29,096** | Rs -199,025 → **Rs -149,341** | Rs 53,532 → Rs 35,653 | Rs -6,036 → Rs -6,036 | Rs -17,298 → Rs -13,599 |
| Dec 2023 - Nov 2024 (unseen) | 532 → 394 | 42% → 44% | Rs -108,621 → **Rs -24,054** | Rs -343,497 → **Rs -227,207** | Rs 163,584 → Rs 87,015 | Rs -11,727 → Rs -10,267 | Rs -42,892 → Rs -24,658 |
| **both unseen years** | 973 → 745 | 46% → 47% | Rs -152,248 → **Rs -53,151** | Rs -542,522 → **Rs -376,548** | Rs 180,912 → Rs 96,324 | Rs -11,727 → Rs -10,267 | Rs -42,892 → Rs -24,658 |
| Nov 2024 - Mar 2026 | 706 → 565 | 56% → 57% | Rs 290,573 → **Rs 273,148** | Rs -626,170 → **Rs -479,995** | Rs 82,713 → Rs 74,427 | Rs -25,140 → Rs -16,928 | Rs -49,847 → Rs -40,773 |
| Apr 2025 - Mar 2026 | 529 → 421 | 57% → 59% | Rs 247,057 → **Rs 263,634** | Rs -427,746 → **Rs -304,793** | Rs 49,847 → Rs 38,700 | Rs -25,140 → Rs -15,014 | Rs -49,847 → Rs -9,908 |
| Apr - Oct 2026 | 293 → 244 | 52% → 53% | Rs 48,612 → **Rs 37,064** | Rs -223,320 → **Rs -183,266** | Rs 47,194 → Rs 66,221 | Rs -16,554 → Rs -16,554 | Rs -17,113 → Rs -35,724 |

### NP5-L: Liquidity, sell if the option is not up 5% in 5 minutes (fails: design: worst month worse; wf: worst month worse; unseen: net lower than today's; twin p 1; q 1)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst day | worst month |
|---|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 → 370 | 27% → 24% | Rs 2,755 → **Rs -585** | Rs -105,302 → **Rs -88,437** | Rs 34,398 → Rs 30,372 | Rs -2,909 → Rs -2,875 | Rs -9,834 → Rs -9,535 |
| Dec 2023 - Nov 2024 (unseen) | 524 → 524 | 34% → 32% | Rs 80,552 → **Rs 70,244** | Rs -165,208 → **Rs -139,212** | Rs 31,466 → Rs 31,534 | Rs -5,742 → Rs -4,879 | Rs -13,213 → Rs -10,284 |
| **both unseen years** | 894 → 894 | 31% → 29% | Rs 83,307 → **Rs 69,658** | Rs -270,509 → **Rs -227,648** | Rs 51,118 → Rs 40,820 | Rs -5,742 → Rs -4,879 | Rs -13,213 → Rs -10,284 |
| Nov 2024 - Mar 2026 | 881 → 883 | 44% → 43% | Rs 257,249 → **Rs 117,970** | Rs -289,743 → **Rs -225,326** | Rs 32,537 → Rs 31,638 | Rs -8,416 → Rs -5,919 | Rs -17,360 → Rs -20,414 |
| Apr 2025 - Mar 2026 | 657 → 658 | 44% → 45% | Rs 238,064 → **Rs 132,766** | Rs -187,995 → **Rs -150,504** | Rs 23,139 → Rs 20,414 | Rs -6,363 → Rs -5,241 | Rs -17,360 → Rs -20,414 |
| Apr - Oct 2026 | 357 → 357 | 37% → 36% | Rs 11,155 → **Rs 16,378** | Rs -144,353 → **Rs -115,556** | Rs 61,062 → Rs 46,674 | Rs -11,405 → Rs -6,046 | Rs -37,821 → Rs -26,844 |

## What this means for Boss

- **Still the two candidates:** R72's wide-day skip and R52's 09:25 exit (R143). Nothing here changes that.
- **The wide-day skip on R51** is close (one month in one period). It could be watched on paper beside R72's, if Boss
  wants. **On Liquidity and R52 it is not worth switching on.**
- **Do not add a no-progress exit to Liquidity.**
- **Liquidity's record is the steadiest** across both the old and the new years. The Donchian arms' profits come only
  from Nov 2024 on.
- **Not yet tested anywhere:** ORB, Night, Solo and the MCX / gold arms. Each needs its own replay engine; that would
  be a separate study.

## Method

- **Engine:** R115's (R101 / R106) for every arm. Liquidity is re-priced with its own engine and exits (R101's
  `E.run_day` with `fam_liq.EX`), checked to reproduce R101's prices before use.
- **Unseen years:** R143's older store (its engine check passed for all four arms, Liquidity included).
- **Cuts:** R51 and Liquidity from R140A; R52's computed from its own Nov 2024 - Mar 2026 trades before any test number
  (1.3208).
- **Costs:** 1 lot, dated charges, R17 fills.
- **Tests:** `test_r144.py`. **Results:** `results/recent.json`, `unseen.json`, `decision.json`.
- **Entries:** `research/experiments/entries/r144-*.yaml`.
