# R145: higher-high / lower-low structure, the structure shift and reversal on Liquidity 15+5. None helps

1 Nov 2026. Research only. **Nothing in the app or the arm has changed.** Boss: *"Also check when we use higher
high/lower low equity shift, reversal on liquidity, what are the results"*. Pre-registration:
`research/hunt/r145_liquidity_structure/PREREG.md`, pushed before any number.

## The answer: 0 of 5 pass. Liquidity is best left as it is

1. **Skipping trades against the higher-high / lower-low trend (R140's T15-L, T5-L) does not help.**
   - On the unseen Dec 2022 - Nov 2024 the 15-min version cut profit from Rs 83,307 to Rs 36,058. The 5-min version
     cut it to Rs 70,334.
   - In R140 they cut losses no more than random skipping (p 0.60 / 0.59).
2. **The structure-shift exit (sell when the index breaks the last swing against the trade)** barely ever fires before
   Liquidity's own exit. It changed only 0-41 trades.
   - The 15-min version is the same as today.
   - The 5-min version made Apr - Oct 2026 a loss (-Rs 482 vs +Rs 11,155) and the unseen years worse
     (Rs 67,279 vs Rs 83,307).
3. **Reversal loses money.**
   - **After a stop:** buying the opposite option lost in every period. In the unseen years the 150 reverse trades won
     13% and lost Rs 67,183.
   - **After a failed breakout:** Liquidity's own "back through the level" exit at a loss is the natural fake-breakout
     signal, but the reverse trade also lost in every period: 311 reverse trades, 18% won, -Rs 54,010 in the unseen
     years; 141 trades, -Rs 84,583 in Apr - Oct 2026.
   - Random entry times did as well or better (p 0.95-1.0).
4. **Flipping every Liquidity signal is a disaster:** -Rs 4.12 lakh in Nov 2024 - Mar 2026, against +Rs 2.57 lakh.
   **Liquidity's direction is right:** its edge is in picking the side, and reversing it gives the money away.
5. **Why reversal fails here.** On Indian indices, breaks and pokes tend to continue (R135). After Liquidity's stop or
   failed break, price usually chops rather than running the other way, and the option bought for the reverse loses
   its time value. R139's reversal study found the same on 11 other rules.

## Before → after (1 lot; "losing days" = the sum of all losing days)

### M15-L: structure-shift exit on 15-min bars (fails: design: losing days not smaller; final: losing days not smaller; test: drawdown worse ...)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst month |
|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 → 370 | 27% → 27% | Rs 2,755 → **Rs 2,755** | Rs -105,302 → **Rs -105,302** | Rs 34,398 → Rs 34,398 | Rs -9,834 → Rs -9,834 |
| Dec 2023 - Nov 2024 (unseen) | 524 → 524 | 34% → 34% | Rs 80,552 → **Rs 80,278** | Rs -165,208 → **Rs -164,933** | Rs 31,466 → Rs 31,380 | Rs -13,213 → Rs -13,213 |
| **both unseen years** | 894 → 894 | 31% → 31% | Rs 83,307 → **Rs 83,033** | Rs -270,509 → **Rs -270,234** | Rs 51,118 → Rs 51,581 | Rs -13,213 → Rs -13,213 |
| Nov 2024 - Mar 2026 | 881 → 881 | 44% → 43% | Rs 257,249 → **Rs 240,099** | Rs -289,743 → **Rs -290,252** | Rs 32,537 → Rs 32,537 | Rs -17,360 → Rs -16,475 |
| Apr 2025 - Mar 2026 | 657 → 657 | 44% → 44% | Rs 238,064 → **Rs 228,972** | Rs -187,995 → **Rs -187,533** | Rs 23,139 → Rs 22,254 | Rs -17,360 → Rs -16,475 |
| Apr - Oct 2026 | 357 → 357 | 37% → 37% | Rs 11,155 → **Rs 11,155** | Rs -144,353 → **Rs -144,353** | Rs 61,062 → Rs 61,062 | Rs -37,821 → Rs -37,821 |

### M5-L: structure-shift exit on 5-min bars (fails)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst month |
|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 → 370 | 27% → 28% | Rs 2,755 → **Rs -1,671** | Rs -105,302 → **Rs -104,651** | Rs 34,398 → Rs 39,394 | Rs -9,834 → Rs -9,834 |
| Dec 2023 - Nov 2024 (unseen) | 524 → 524 | 34% → 34% | Rs 80,552 → **Rs 68,950** | Rs -165,208 → **Rs -164,083** | Rs 31,466 → Rs 36,202 | Rs -13,213 → Rs -12,660 |
| **both unseen years** | 894 → 894 | 31% → 32% | Rs 83,307 → **Rs 67,279** | Rs -270,509 → **Rs -268,734** | Rs 51,118 → Rs 61,889 | Rs -13,213 → Rs -12,660 |
| Nov 2024 - Mar 2026 | 881 → 881 | 44% → 43% | Rs 257,249 → **Rs 260,083** | Rs -289,743 → **Rs -277,720** | Rs 32,537 → Rs 36,813 | Rs -17,360 → Rs -12,835 |
| Apr 2025 - Mar 2026 | 657 → 657 | 44% → 44% | Rs 238,064 → **Rs 246,554** | Rs -187,995 → **Rs -179,028** | Rs 23,139 → Rs 20,712 | Rs -17,360 → Rs -10,126 |
| Apr - Oct 2026 | 357 → 357 | 37% → 38% | Rs 11,155 → **Rs -482** | Rs -144,353 → **Rs -139,042** | Rs 61,062 → Rs 66,401 | Rs -37,821 → Rs -37,821 |

### REV-S: stop-and-reverse after a stop (fails)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst month |
|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 → 427 | 27% → 26% | Rs 2,755 → **Rs -8,290** | Rs -105,302 → **Rs -123,467** | Rs 34,398 → Rs 38,458 | Rs -9,834 → Rs -13,308 |
| Dec 2023 - Nov 2024 (unseen) | 524 → 617 | 34% → 30% | Rs 80,552 → **Rs 24,414** | Rs -165,208 → **Rs -212,919** | Rs 31,466 → Rs 57,480 | Rs -13,213 → Rs -24,565 |
| **both unseen years** | 894 → 1,044 | 31% → 28% | Rs 83,307 → **Rs 16,124** | Rs -270,509 → **Rs -336,385** | Rs 51,118 → Rs 84,853 | Rs -13,213 → Rs -24,565 |
| Nov 2024 - Mar 2026 | 881 → 910 | 44% → 43% | Rs 257,249 → **Rs 237,446** | Rs -289,743 → **Rs -308,194** | Rs 32,537 → Rs 33,353 | Rs -17,360 → Rs -19,124 |
| Apr 2025 - Mar 2026 | 657 → 679 | 44% → 43% | Rs 238,064 → **Rs 212,981** | Rs -187,995 → **Rs -204,782** | Rs 23,139 → Rs 25,700 | Rs -17,360 → Rs -19,124 |
| Apr - Oct 2026 | 357 → 368 | 37% → 36% | Rs 11,155 → **Rs -1,968** | Rs -144,353 → **Rs -154,206** | Rs 61,062 → Rs 64,306 | Rs -37,821 → Rs -41,065 |

The reverse trades alone:

| period | reverse trades | win rate | their net |
|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 57 | 19% | Rs -11,045 |
| Dec 2023 - Nov 2024 (unseen) | 93 | 10% | Rs -56,138 |
| **both unseen years** | 150 | 13% | Rs -67,183 |
| Nov 2024 - Mar 2026 | 29 | 24% | Rs -19,803 |
| Apr 2025 - Mar 2026 | 22 | 14% | Rs -25,083 |
| Apr - Oct 2026 | 11 | 9% | Rs -13,123 |

### REV-F: reverse after a failed breakout that lost (fails)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst month |
|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 → 502 | 27% → 27% | Rs 2,755 → **Rs -2,318** | Rs -105,302 → **Rs -139,553** | Rs 34,398 → Rs 38,787 | Rs -9,834 → Rs -10,028 |
| Dec 2023 - Nov 2024 (unseen) | 524 → 703 | 34% → 28% | Rs 80,552 → **Rs 31,616** | Rs -165,208 → **Rs -216,524** | Rs 31,466 → Rs 49,427 | Rs -13,213 → Rs -21,612 |
| **both unseen years** | 894 → 1,205 | 31% → 28% | Rs 83,307 → **Rs 29,297** | Rs -270,509 → **Rs -356,077** | Rs 51,118 → Rs 67,807 | Rs -13,213 → Rs -21,612 |
| Nov 2024 - Mar 2026 | 881 → 1,195 | 44% → 42% | Rs 257,249 → **Rs 179,555** | Rs -289,743 → **Rs -421,907** | Rs 32,537 → Rs 52,251 | Rs -17,360 → Rs -20,044 |
| Apr 2025 - Mar 2026 | 657 → 884 | 44% → 43% | Rs 238,064 → **Rs 156,784** | Rs -187,995 → **Rs -294,608** | Rs 23,139 → Rs 33,001 | Rs -17,360 → Rs -20,044 |
| Apr - Oct 2026 | 357 → 498 | 37% → 35% | Rs 11,155 → **Rs -73,428** | Rs -144,353 → **Rs -241,650** | Rs 61,062 → Rs 105,362 | Rs -37,821 → Rs -67,346 |

The reverse trades alone:

| period | reverse trades | win rate | their net |
|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 132 | 25% | Rs -5,073 |
| Dec 2023 - Nov 2024 (unseen) | 179 | 13% | Rs -48,936 |
| **both unseen years** | 311 | 18% | Rs -54,010 |
| Nov 2024 - Mar 2026 | 314 | 38% | Rs -77,694 |
| Apr 2025 - Mar 2026 | 227 | 39% | Rs -81,280 |
| Apr - Oct 2026 | 141 | 30% | Rs -84,583 |

### F-L: every Liquidity signal taken the other way (fails)

| period | trades | win rate | net profit | losing days (sum) | max drawdown | worst month |
|---|---|---|---|---|---|---|
| Dec 2022 - Nov 2023 (unseen) | 370 → 357 | 27% → 23% | Rs 2,755 → **Rs 15,302** | Rs -105,302 → **Rs -98,217** | Rs 34,398 → Rs 28,556 | Rs -9,834 → Rs -18,113 |
| Dec 2023 - Nov 2024 (unseen) | 524 → 506 | 34% → 17% | Rs 80,552 → **Rs -107,714** | Rs -165,208 → **Rs -230,093** | Rs 31,466 → Rs 126,480 | Rs -13,213 → Rs -31,188 |
| **both unseen years** | 894 → 863 | 31% → 19% | Rs 83,307 → **Rs -92,411** | Rs -270,509 → **Rs -328,309** | Rs 51,118 → Rs 127,773 | Rs -13,213 → Rs -31,188 |
| Nov 2024 - Mar 2026 | 881 → 839 | 44% → 34% | Rs 257,249 → **Rs -411,896** | Rs -289,743 → **Rs -724,268** | Rs 32,537 → Rs 417,838 | Rs -17,360 → Rs -83,305 |
| Apr 2025 - Mar 2026 | 657 → 625 | 44% → 34% | Rs 238,064 → **Rs -343,874** | Rs -187,995 → **Rs -549,180** | Rs 23,139 → Rs 343,874 | Rs -17,360 → Rs -83,305 |
| Apr - Oct 2026 | 357 → 336 | 37% → 33% | Rs 11,155 → **Rs -117,938** | Rs -144,353 → **Rs -220,267** | Rs 61,062 → Rs 127,750 | Rs -37,821 → Rs -45,136 |

### Reported: R140's higher-high / lower-low trend filters on the unseen years

| period | today | T15-L (skip against the 15-min trend) | T5-L (skip against the 5-min trend) |
|---|---|---|---|
| unseen Dec 2022 - Nov 2024: net | Rs 83,307 | Rs 36,058 | Rs 70,334 |
| unseen: losing days | Rs -270,509 | Rs -229,503 | Rs -231,104 |
| unseen: trades | 894 | 709 | 727 |

## What this means for Boss

- **Keep Liquidity 15+5 as it is.** Market structure, the structure shift and reversal all make it worse or change
  nothing.
- **Its strength is choosing the direction**, and it was the only arm that made money in both the unseen 2022-24
  years and 2024-26.
- **Its weak spot** is many small losses (only 31-44% of trades win). None of the tested add-ons fixes that without
  costing more.

## Method

- **Engine:** Liquidity's own engine and premium exits (R101's `E.run_day`, `fam_liq.EX`), checked to reproduce R101's
  prices in both stores. Structure: R139 / R140's wick swings on the index (5- and 15-min).
- **Periods:** the unseen years from R143's older store; Nov 2024 - Oct 2026 from the main store. 1 lot, dated
  charges, R17 fills.
- **Tests:** `test_r145.py`. **Results:** `results/recent.json`, `unseen.json`, `decision.json`.
- **Entries:** `research/experiments/entries/r145-*.yaml`.
