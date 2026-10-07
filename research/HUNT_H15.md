# HUNT h15: Liquidity 15+5 starting from Rs 1,00,000, with compounding

Code: `research/hunt/h15/`
- `PREREG.md`: the variants, the capital-walk rules, the bootstrap and the choice rule, written before any capital-path P&L.
- `tab.py`: builds per-trade tables of net, gross, premium and fills for every lot count from 1 to 100. It uses h14's
  `exe.simulate`.
  - It reproduces h10 and h14 exactly at BN 26 / FIN 3 / MIDCP 11:
    - E0 4,928 / 4,830 Rs/day;
    - E2 5,162 / 5,368 Rs/day;
    - E2 at κ 0.04: 2,163 / 362 Rs/day.
- `cap1l.py`:
  - the capital walk, vectorised over paths, plus a scalar replay. The two agree to the rupee.
  - two stages: `pre` (the choice, made on data before 1 Oct 2025) and `hold` (run once).

Logs are in `scratchpad/hunt/h15/` (`pre.log`, `hold.log`, `post.log`). `post.log` holds post-hoc reporting only.

Everything here is option BUYING:
- the signals, the strike (1-ITM, nearest = monthly expiry), the books and the exits are the app's Liquidity 15+5,
  unchanged;
- execution is h14's E2: a limit buy at the entry print +0.5%, left resting for 3 minutes and then cancelled, with
  exits worked as in h10;
- the fill model is M2 with h10 impact, at κ 0.02 (central), 0.04 (pessimistic) and 0.01;
- costs are the app cost model plus spread, charged per order slice.

## Verdict (plain language)

**With Rs 1 lakh the edge is tradable, but only by betting big on each trade (half-Kelly). Expect Rs 400-1,500/day at
the start. Reaching a size that averages Rs 5,000/day takes about 10-12 months if things go like the backtest. The
cost is 30-50% drawdowns along the way.**

1. **The pre-registered choice is A-K2.** That means all 3 books (BN 15+5, FIN 30+5, MIDCP 15+5) with half-Kelly
   sizing.
   - Each trade puts `x · f* · capital` into premium. The share of capital per trade is:
     - BN 0.65;
     - FIN 0.53;
     - MIDCP 0.17.
   - It won the 12-month median capital. No variant came within 5% of it.
2. **Holdout (1 Oct 2025 to 5 Oct 2026, run once, starting from Rs 1 L):**

   | | κ 0.02 (central) | κ 0.04 | κ 0.01 | κ 0.02, h10 market entry |
   |---|---|---|---|---|
   | ending capital | **Rs 7.41 L** | Rs 2.69 L | Rs 10.2 L | 6.58 L |
   | net per day | **2,572** | 677 | 3,678 | 2,241 |
   | gross per day | 4,105 | 1,940 | 5,127 | 3,894 |

   - Lowest capital at κ 0.02: Rs 91.5k.
   - **Max drawdown: Rs 3.03 L, which is -44%.** Capital fell from 3.48 L in Feb to 1.95 L in Jun. 5 of 13 months lost.
   - **Monthly path (κ 0.02):**

     | month | capital at month end (Rs L) | Rs/day |
     |---|---|---|
     | Oct | 1.07 | 356 |
     | Nov | 1.15 | 416 |
     | Dec | 1.23 | 361 |
     | Jan | 2.96 | 8,626 |
     | Feb | 3.48 | 2,474 |
     | Mar | 3.17 | -1,618 |
     | Apr | 2.26 | -4,558 |
     | May | 2.08 | -962 |
     | Jun | 1.95 | -590 |
     | Jul | 2.53 | 2,530 |
     | Aug | 3.56 | 4,884 |
     | Sep | 7.84 | 20,405 |
     | Oct (to date) | 7.41 | -21,988 |

   - **Signals:** 578 of 588 were taken, and only 5 were skipped because capital was tied up.
   - **Random entries** with the same exits, sizing and capital walk, over 2,000 replays: median ending capital Rs 27k,
     p95 Rs 50k, P(ruin) 49%, p = 0.0005.
     - **The same aggressive sizing without the edge is ruin.**
   - The holdout path sits at the 29th / 69th / 71st percentile of the pre-holdout bootstrap at 3 / 6 / 12 months. It
     is typical, not lucky.
3. **Rs 5,000/day.**
   - The model reaches an average of Rs 5,000/day at capital E* of about **Rs 4.2 L at κ 0.02, or Rs 5.9 L at κ 0.04**.
     At that size the lots are BN about 17 (up to 49), FIN 3 and MIDCP about 3, well inside h10's capacity.
   - E* is far below h10's Rs 35-40 lakh. h10 used flat lots plus a cushion; here the drawdowns are taken as percentages
     of capital instead.
   - Bootstrap (κ 0.02): P(reaching E* within 12 / 24 months) is about 50% / 97%, with a median of 10 months.
   - The holdout reached it in month 12. At κ 0.04, P(within 60 months) is 99.5%, and it is slower.
   - **Above about Rs 20 L the curve flattens because of impact.** The model gives about Rs 15k/day at κ 0.02. At κ 0.04
     it falls from 8.7k/day at Rs 20 L to 4.7k at Rs 50 L.
4. **Ruin (capital < Rs 25k) is not the real risk; deep drawdowns are.**
   - Bootstrap P(ruin within 24 months) is about 0 for every compounding rule. Fixed 1 lot gives 1.1% (4.1% at κ 0.04).
   - For A-K2, P(drawdown ≥ 30%) is 63% and P(drawdown ≥ 50%) is 14%. At κ 0.04 these are 84% and 33%.
   - Quarter-Kelly (A-K4) gives P(drawdown ≥ 50%) of 1% at κ 0.02 and 5% at κ 0.04, but grows half as fast.
5. **Honesty flags.**
   - **The Kelly fraction is unstable.** It was estimated on the same 10 regime months the bootstrap resamples. On other
     windows f* for BN is 0.48 (Jun 2023 to Nov 2024) and 0.14 (2020 to May 2023), against 1.29 here.
   - **The bootstrap therefore flatters K2.** It has only 10 month-blocks, all from a good stretch.
   - The holdout did confirm it: K2 > R4 > K4 > fixed.
   - The SPA over the 18 variants' daily P&L is p = 0.14. The choice of sizing is not statistically separable.
   - What is significant is the edge itself: every variant beats random entries, BH q ≤ 0.006.

**YES/NO on Rs 5,000/day from Rs 1 lakh: a conditional YES.**
- If κ ≈ 0.02 and the edge holds as in the holdout, it takes about 1 year, with drawdowns of up to 40-50% on the way.
- At κ 0.04 it takes 1.5-2+ years.
- It is NOT Rs 5,000/day now: now is about Rs 400-1,500/day.

## The rule (a few lines)

1. **What to trade.** Trade the app's Liquidity 15+5 on BANKNIFTY, FINNIFTY and MIDCPNIFTY, unchanged. Buy the 1-ITM
   option of the nearest (monthly) expiry with a limit order at the entry print +0.5%. Cancel what has not filled after
   3 minutes. Exits are the app's own.
2. **Lots.** Lots = floor(k × capital ÷ premium of 1 lot), with:
   - **k = 0.65** for BN;
   - **k = 0.53** for FIN (never more than 3 lots);
   - **k = 0.17** for MIDCP (never more than 11 lots);
   - BN capped at 100 lots.

   Capital means realised capital. Recompute at every signal, so the lots grow after wins and shrink after losses.
3. **When the rule says 0 lots.** If the result is 0, take 1 lot when it costs no more than 35% of capital. Otherwise
   skip the signal.
4. **When capital is tied up.** Never pay more premium than the cash that is free (capital minus the premium of open
   positions). Cut the lots to fit. If not even 1 lot fits, skip the signal.
5. **A safer setting (not the pre-registered choice).** Use half these k (quarter-Kelly). It grows about half as fast,
   with P(50% drawdown) at 1-5%.

**Starting lots at Rs 1 L:**
- BN 3 lots (about Rs 60k of premium);
- FIN up to 3 lots (about Rs 50k), cut by free cash when BN is open;
- MIDCP 1 lot (single-lot allowance).

In the holdout the BN lots per month went 2, 3, 4, 8, 15, 7, 6, 5, 6, 7, 11, 14, 18.

## Pre-holdout bootstrap

The bootstrap uses month-blocks from Dec 2024 to Sep 2025, 5,000 paths, κ 0.02, starting from Rs 1 L. Capital is in
Rs lakh.

| variant | expected Rs/day at 1 L | 3 m median | 6 m median | 12 m p05 / median / p95 | 24 m median | P(double) 12 / 24 m | median Rs/day in month 1 / 6 / 12 | E* | P(reach E*) 24 / 60 m |
|---|---|---|---|---|---|---|---|---|---|
| A-F1 (1 lot each) | 327 | 1.11 | 1.28 | 0.69 / 1.62 / 2.66 | 2.24 | 26% / 61% | 29 / 29 / 59 | never | 0 / 0 |
| A-FL (1 lot per lakh) | 327 | 1.13 | 1.32 | 0.95 / 1.66 / 3.07 | 2.33 | 24% / 59% | 52 / 217 / 251 | 26 L | 0 / 5% |
| A-R2 | 356 | 1.10 | 1.33 | 0.93 / 1.74 / 3.48 | 2.99 | 38% / 76% | 66 / 109 / 217 | 26 L | 0 / 16% |
| A-R4 | 650 | 1.32 | 1.70 | 1.07 / 2.85 / 7.56 | 6.92 | 72% / 95% | 145 / 467 / 618 | 12.8 L | 19% / 95% |
| A-K4 | 701 | 1.31 | 1.71 | 1.20 / 3.02 / 6.69 | 8.21 | 76% / 98% | 160 / 650 / 1,119 | 8.2 L | 53% / 100% |
| **A-K2 (choice)** | **1,530** | **1.57** | **2.43** | **1.48 / 5.26 / 16.6** | **19.7** | **89% / 99%** | **765 / 1,635 / 3,289** | **4.2 L** | **97% / 100%** |
| B-K2 | 1,044 | 1.42 | 1.94 | 1.15 / 3.69 / 11.4 | 13.1 | 81% / 97% | 705 / 1,085 / 2,229 | 4.6 L | 90% / 100% |
| C-K2 (BN only) | 998 | 1.39 | 1.97 | 1.37 / 3.82 / 9.76 | 13.5 | 86% / 99% | 943 / 1,442 / 2,572 | 5.1 L | 91% / 100% |

A-K2 under other assumptions:

| | 12 m median capital | 24 m median capital | P(double) at 12 m | Rs/day in month 1 / 12 | P(ruin) at 24 m |
|---|---|---|---|---|---|
| κ 0.04 | 3.36 L | 8.16 L | 75% | 649 / 1,647 | 0.3% |
| long window (Jun 2023 to Sep 2025 blocks) | 4.06 L | 17.0 L | | | 2.8% |
| random entries | 0.30 L | | | | 89% |

- P(ruin) is 0 to 1% in every compounding variant. All other rows are in `pre.log`.
- B (dropping FIN) and C (BN only) were never better than A before the holdout, or in it, apart from B-K2's 8.4 L.
- Fixed lots do not compound, so they never reach Rs 5,000/day.

## Holdout, all variants (information only, starting from Rs 1 L)

Ending capital in Rs lakh:

| variant | κ 0.02 | κ 0.04 |
|---|---|---|
| A-F1 | 1.32 | 0.67 |
| A-FL | 1.39 | 0.88 |
| A-R2 | 1.67 | 1.03 |
| A-R4 | 3.64 | 2.15 |
| A-K4 | 2.59 | 1.86 |
| **A-K2** | **7.41** | **2.69** |
| B-K2 | 8.39 | 5.37 |
| C-K2 | 6.65 | 5.02 |
| C-F1 | 1.36 | 1.31 |

At κ 0.04 the FIN/MIDCP books drag. BN-heavy variants hold up better, but this is post hoc and not adopted.

## Honesty notes

- **18 variants, all counted.** The choice was mechanical on pre-holdout data, and the holdout ran once.
  - Before writing PREREG, the table validation printed 1-lot per-trade means for the regime window AND the holdout
    (FIN -71 and MIDCP +99 per trade in the holdout). This is disclosed in PREREG.md. The choice rule did not use them,
    and it picked all 3 books anyway.
  - The first `pre` run crashed in the summary code (an index on a 24-month array) before printing any variant, and was
    rerun unchanged.
- **The bootstrap is optimistic** for the reasons in Verdict 5: the in-sample Kelly fractions and the 10 good blocks.
  The holdout (a fresh 13 months) and the κ 0.04 runs are the honest checks.
- **Concurrency.**
  - Up to 3 positions can be open, one per book.
  - Lots are cut to fit free cash.
  - Charges are buffered at Rs 100.
  - Capital counts realised P&L only.
  - Positions are intraday, and the month-end release is exact.
- **Random-entry walk.** Each real trade is replaced by one of its 5 random alternatives, kept in the real trade's slot
  order. If the same book is still open, the trade is skipped.
- **Inherited caveats:**
  - the exits were tuned through Feb 2026 (h4);
  - gross is at bar prints;
  - κ is unknown until it is measured live, so log every fill against the signal-minute print;
  - exchange freeze limits are ignored.
