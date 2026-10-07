# HUNT h9: more Liquidity trades from extra level timeframes (rules unchanged)

Code: `research/hunt/h9/`
- `PREREG.md`: the grid and the selection rule, written before any result.
- `run_h9.py`: one data pass, three executions, and random pools.
- `analyze_h9.py`: `pre` freezes the selection on data before 2025-10-01; `hold` then runs the one holdout test.

Caches are in `scratchpad/hunt/h9/cache/h9/`.

Data: real Dhan option minute bars from Oct 2021 to 5 Oct 2026, 1 lot at the lot size of each date, and only option
BUYING. The h4 re-implementation reproduces the validated port exactly:
- The same trades on BN15, BN5, FIN30 and FIN5, to the rupee.
- App totals on BANKNIFTY/FINNIFTY of Rs 242,421, as validated.

## Verdict (plain language)

**Extra timeframes do not add a usable edge. They mostly copy trades the existing books already take.**

12 extra books were tested: 3, 10, 30 and 60-min levels on BANKNIFTY and MIDCPNIFTY, and 3, 10, 15 and 60-min on
FINNIFTY. The rule chosen beforehand kept 2 of them on pre-holdout data: **BANKNIFTY 10-min and MIDCPNIFTY 30-min**.

On the holdout the two books added only **about Rs 17/day at 1 lot (net)**:
- Existing 6 books: Rs 551/day. Existing 6 plus the 2 extras: Rs 568/day.
- On their own, the 2 extras did **not** beat random entries with the same exits (p = 0.15).
- About 30% of their trades enter in the same minute and direction as an existing book. About 65% are taken while an
  existing book already holds the same side.

So "more trades" here mostly means doubling up on the same bet. Per rupee of risk, adding the extras is no better than
adding lots to the existing 6 books, which is simpler.

**Rs 5,000/day realistically: NO, not as a dependable daily income.** It is reachable on paper with about 16 lots per
book. Even so:
- Half the months lose (P(losing month) is about 40%).
- The drawdown is about Rs 6-9.5 lakh.
- On the holdout, FINNIFTY monthly options trade so thinly that 16 lots would be a large share of the contract's
  5-minute volume.

## What was tested (declared in PREREG.md before any result)

- **Rules:** identical to the app's Liquidity 15+5 arm, as in h4. Only the level timeframe of a book changes.
- **Grid:** 12 extra books, as listed above.
- **Keep rule**, applied to real-execution trades before 2025-10-01. A book was kept only if all three held:
  - K1: net > 0;
  - K2: the Benjamini-Hochberg q of its same-exit random-entry p < 0.10, across the 12 books;
  - K3: net > 0 on its non-duplicate trades.
- **Walk-forward:** the same rule was applied to the data before each test year.
- **Executions:** gross (bar prints, no charges), app (the app's fills and charges), and real (app plus a 1-4 tick
  spread).
- **Protocol note:** the data-pass log printed full-sample per-book totals, holdout included, before the selection ran.
  The selection is the mechanical rule above, so this could not change the choice. It is disclosed anyway.

### Pre-holdout (Oct 2021 to Sep 2025), real execution, 1 lot

| book | trades | net | gross | per trade vs random | p_rand | BH q | same minute & side as existing | overlaps an existing position | net on non-duplicates | daily corr with same-index books | kept |
|---|---|---|---|---|---|---|---|---|---|---|---|
| BN3 | 624 | -22,370 | +31,126 | -36 vs +1 | 0.83 | 0.87 | 9% | 18% | -19,948 | 0.34 | no |
| **BN10** | 310 | +22,189 | +48,834 | +72 vs -124 | 0.000 | 0.006 | 26% | 54% | +7,057 | 0.44 | **yes** |
| BN30 | 79 | +12,565 | +19,578 | +159 vs +54 | 0.27 | 0.53 | 22% | 58% | +6,489 | 0.10 | no |
| BN60 | 13 | +1,820 | +2,906 | - | 0.34 | 0.58 | 0% | 38% | +1,820 | -0.08 | no |
| FIN3 | 452 | +6,170 | +44,859 | +14 vs -42 | 0.10 | 0.30 | 8% | 16% | +2,117 | 0.20 | no |
| FIN10 | 235 | +373 | +19,723 | +2 vs -121 | 0.03 | 0.10 | 33% | 60% | -13,524 | 0.24 | no |
| FIN15 | 182 | +2,124 | +20,276 | +12 vs -70 | 0.16 | 0.39 | 21% | 49% | -2,078 | 0.63 | no |
| FIN60 | 9 | -391 | +342 | - | 0.84 | 0.87 | 22% | 33% | +836 | 0.04 | no |
| MIDCP3 | 364 | -37,506 | -1,055 | -103 vs -118 | 0.39 | 0.58 | 6% | 15% | -32,561 | 0.11 | no |
| MIDCP10 | 171 | -27,963 | -9,687 | -164 vs -29 | 0.87 | 0.87 | 30% | 56% | -31,639 | 0.55 | no |
| **MIDCP30** | 46 | +28,902 | +33,795 | +628 vs -88 | 0.008 | 0.049 | 22% | 57% | +26,351 | 0.45 | **yes** |
| MIDCP60 | 8 | -2,103 | -1,157 | - | 0.53 | 0.70 | 13% | 50% | +608 | 0.06 | no |

For comparison, the existing books before the holdout, net:

| BN15 | BN5 | FIN30 | FIN5 | MIDCP15 | MIDCP5 |
|---|---|---|---|---|---|
| +31.5k (p_rand 0.04) | +53.6k (0.001) | +9.5k (0.008) | +57.9k (0.000) | +16.9k (0.04) | +31.9k (0.000) |

Other pre-holdout results:
- **Walk-forward picks:** none for 2023 or 2024 (no book qualified on the earlier data), and BN10 for 2025.
  - Walk-forward 2023 to Sep 2025: existing Rs 318/day against existing plus walk-forward extras Rs 335/day. The extras
    added Rs 11k in 2025 and nothing in the other years.
- **Multiple testing (2022 to Sep 2025, real, daily):**
  - Over the 12 extras alone: SPA p = 0.84, White RC p = 0.66. No extra book is significant once all 12 are counted.
  - Over all 21 series (18 books + 3 sets): RC p = 0.03, SPA p = 0.23.

### Holdout (1 Oct 2025 to 5 Oct 2026, 249 days): tested once

| set (1 lot per book) | net/day (real) | app/day | gross/day | trades/day | beats random entries (same exits) |
|---|---|---|---|---|---|
| (a) existing 6 books | **Rs 551** | 575 | 905 | 2.6 | p 0.003 |
| (b) existing 6 + BN10 + MIDCP30 | **Rs 568** | 594 | 989 | 3.1 | p 0.004 |
| the 2 extras alone | Rs 17 (BN10 +3.6k, MIDCP30 +0.6k in total) | | Rs 84 | 0.5 | **p 0.15 (no)** |

- The extras' daily P&L correlates 0.46 with the existing books.
- On the holdout, 30-32% of the extras' trades enter in the same minute and side as an existing book, and 64-70%
  overlap an existing same-side position.
- Post-hoc, not chosen: BN3 (+18k) and FIN3 (+27k) made money on the holdout after losing or doing nothing before it.
  A pre-holdout winner, BN30, lost on the holdout. That is the noise you expect across 12 unvalidated books, and it
  is not a reason to add them.

### At the Rs 5,000/day size (lots fixed on Jun 2023 to Sep 2025, when all three indices traded)

| | (a) existing 6 | (b) existing + 2 extras |
|---|---|---|
| lots per book for Rs 5,000/day net (pre-holdout) | **16.0** (96 book-lots) | **12.5** (100 book-lots) |
| pre-holdout at that size: gross/day, max drawdown | 8,360; -6.2 lakh | 8,100; -6.1 lakh |
| pre-holdout worst day / worst month / losing months | -1.36 lakh / -3.1 lakh / 14 of 28 | -1.13 lakh / -2.6 lakh / 13 of 28 |
| P(losing month), bootstrap, pre-holdout | 42% | 43% |
| **holdout net/day (real)** | **Rs 8,830** | **Rs 7,115** |
| holdout gross/day | Rs 14,490 | Rs 12,390 |
| holdout max drawdown (real) | -9.5 lakh | -9.1 lakh |
| holdout worst day / worst month | -1.84 lakh / -6.1 lakh | -1.61 lakh / -5.1 lakh |
| holdout losing months; P(losing month), bootstrap | 6 of 13; 38% | 6 of 13; 41% |
| premium tied up at once (holdout, p95 / max) | Rs 19 lakh / 26 lakh | Rs 18 lakh / 27 lakh |
| capital needed (premium plus drawdown buffer) | about Rs 30-40 lakh | about Rs 30-40 lakh |

Option buying needs no margin beyond the premium.

**Fill realism** (our lots against the contract's traded volume in the 5 minutes before entry):
- **Pre-holdout:** median share 0.3%; 8% of entries are above 20%.
- **Holdout at 16 lots:** the median share is 4% overall, but **77% on FINNIFTY** and 12% on MIDCPNIFTY. 32% of entries
  and 39% of exits would be above 20% of the 5-minute volume.
- FINNIFTY lost its weekly options in Nov 2024, and the monthly 1-ITM option is thin, so the FINNIFTY books cannot
  realistically be filled at 16 lots. The modelled 1-4 tick spread understates that slippage.
- BANKNIFTY is fine (well under 1%).

## Caveats

- **Partly in-sample:**
  - The Liquidity exits were tuned on Feb 2024 to Feb 2026, and FINNIFTY's 30-min book was itself chosen from a
    timeframe comparison (LIQUIDITY_FINNIFTY_PLUS.md).
  - The (b) lot size uses the in-sample selection.
- **Lumpy results:** roughly 1 day in 3 is green, and the best day is bigger than the worst month.

## Answer for Boss

| | |
|---|---|
| Best simple rule | Unchanged: the app's Liquidity 15+5 on BANKNIFTY (15m+5m), FINNIFTY (30m+5m) and MIDCPNIFTY (15m+5m), 1-ITM, -15% stop and structural exits. **Do not add extra-timeframe books.** |
| Holdout, 1 lot per book | net Rs 551/day, gross Rs 905/day (the extras would add Rs 17/day net) |
| Size for Rs 5,000/day | 16 lots per book (about 96 lots across 6 books) |
| Holdout at that size | net Rs 8,830/day, gross Rs 14,490/day |
| Worst drawdown / month | -9.5 lakh / -6.1 lakh; P(losing month) about 40% |
| Capital | about Rs 30-40 lakh (Rs 19 lakh of premium tied up on a busy day) |
| Rs 5,000/day realistically? | **NO, not dependably.** Half the months are red, and the edge does not survive SPA. FINNIFTY monthly liquidity cannot absorb 16 lots, and part of the history is in-sample. |
