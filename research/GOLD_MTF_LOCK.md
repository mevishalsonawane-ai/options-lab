## The 1h + 30m candidate with profit locks and stops (research/gold_mtf_lock.py)

105 versions; 22 positive in both fitting years. Baseline (no stop, no lock, out after 4 hours or at the break):

| version | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | worst trade | deepest drawdown | fitting profit / drawdown |
|---|---|---|---|---|---|---|---|---|---|---|
| stop none, lock none, max 4 h | +1,075 | +49,156 | +118,519 | +168,751 | 1080 | 50% | 2.74 | -12,864 | -52,464 | 3.67 |

Top 25 by the fitting years' profit / drawdown (12 beat the baseline there; of those 0 also made more in the held-out year and 8 fell less in it):

| version | Oct 2023 - Sep 2024 | Oct 2024 - Sep 2025 | Oct 2025 - Sep 2026 | 3 years | trades | win | t | worst trade | deepest drawdown | fitting profit / drawdown |
|---|---|---|---|---|---|---|---|---|---|---|
| stop none, lock breakeven at 1 ATR, max 8 h | +19,257 | +57,305 | +49,414 | +125,976 | 965 | 50% | 1.96 | -15,565 | -58,108 | 7.57 |
| stop none, lock giveback 2 ATR, max 8 h | +11,810 | +44,519 | +74,649 | +130,978 | 961 | 48% | 2.09 | -15,565 | -42,285 | 6.28 |
| stop none, lock giveback 3 ATR, max 8 h | +8,394 | +48,454 | +72,980 | +129,828 | 932 | 46% | 1.92 | -15,565 | -37,885 | 6.26 |
| stop none, lock keep half at 2 ATR, max 8 h | +23,814 | +47,648 | +82,875 | +154,337 | 947 | 56% | 2.40 | -15,565 | -51,334 | 6.04 |
| stop none, lock giveback 1.5 ATR, max 8 h | +14,289 | +40,267 | +67,929 | +122,485 | 991 | 51% | 2.04 | -15,565 | -45,808 | 5.74 |
| stop none, lock none, max 8 h | +20,288 | +50,382 | +75,209 | +145,879 | 898 | 51% | 2.04 | -15,565 | -47,389 | 5.28 |
| stop none, lock none, max 16 h | +23,815 | +74,317 | -8,823 | +89,309 | 697 | 50% | 0.93 | -29,319 | -112,414 | 4.79 |
| stop none, lock giveback 1 ATR, max 8 h | +11,578 | +30,576 | +42,116 | +84,270 | 1054 | 63% | 1.44 | -15,565 | -75,523 | 4.74 |
| stop 2 ATR, lock none, max 16 h | +17,510 | +44,512 | +79,294 | +141,315 | 834 | 38% | 1.92 | -9,518 | -37,259 | 4.41 |
| stop 1.5 ATR, lock none, max 16 h | +9,447 | +41,697 | +45,489 | +96,633 | 879 | 33% | 1.38 | -10,112 | -40,909 | 4.10 |
| stop none, lock keep half at 2 ATR, max 16 h | +7,935 | +58,625 | +10,176 | +76,736 | 819 | 60% | 0.91 | -29,319 | -104,429 | 3.82 |
| stop none, lock giveback 3 ATR, max 16 h | +6,862 | +57,057 | +71,787 | +135,707 | 790 | 45% | 1.83 | -15,418 | -49,990 | 3.78 |
| stop none, lock none, max 4 h | +1,075 | +49,156 | +118,519 | +168,751 | 1080 | 50% | 2.74 | -12,864 | -52,464 | 3.67 |
| stop none, lock breakeven at 1 ATR, max 16 h | +8,595 | +68,545 | +41,685 | +118,825 | 837 | 50% | 1.68 | -16,928 | -59,073 | 3.61 |
| stop none, lock giveback 1.5 ATR, max 16 h | +7,313 | +47,535 | +96,165 | +151,014 | 897 | 54% | 2.38 | -15,418 | -39,859 | 3.25 |
| stop 2 ATR, lock breakeven at 1 ATR, max 16 h | +2,726 | +40,836 | +62,987 | +106,549 | 941 | 43% | 1.70 | -9,518 | -34,817 | 3.09 |
| stop none, lock giveback 1 ATR, max 16 h | +6,502 | +37,369 | +34,841 | +78,712 | 969 | 68% | 1.23 | -16,928 | -72,757 | 2.96 |
| stop 1.5 ATR, lock breakeven at 1 ATR, max 16 h | +1,273 | +37,633 | +34,829 | +73,735 | 976 | 39% | 1.22 | -10,112 | -43,704 | 2.89 |
| stop none, lock giveback 2 ATR, max 16 h | +1,852 | +51,755 | +88,440 | +142,047 | 855 | 50% | 2.10 | -15,418 | -48,331 | 2.60 |
| stop 2 ATR, lock giveback 3 ATR, max 16 h | +3,720 | +31,785 | +64,587 | +100,092 | 886 | 38% | 1.47 | -9,518 | -30,251 | 2.40 |
| stop pullback low, lock breakeven at 1 ATR, max 16 h | +466 | +31,991 | +41,638 | +74,094 | 1058 | 31% | 1.33 | -8,042 | -29,838 | 2.32 |
| stop 1.5 ATR, lock giveback 3 ATR, max 16 h | +2,452 | +25,862 | +22,727 | +51,041 | 923 | 35% | 0.78 | -10,112 | -47,500 | 2.11 |
### Verdict

- The locks do what they did on the Trend arm: they even out the years and cut the falls, but they cut the profit
  too. The family that holds up is a giveback lock with an 8-hour limit: 1.5 / 2 / 3 ATRs give +$122k to +$131k over
  three years, positive in every year (first year +$8k to +$14k instead of +$1k), deepest drawdown -$38k to -$46k
  (baseline -$52k). "Keep half at 2 ATR, 8 hours" makes the most of the locked versions (+$154k, t 2.40, 56% won,
  -$51k).
- None of the 12 versions that beat the baseline in the fitting years also made more in the held-out year (the
  baseline made +$118.5k there); 8 of them fell less in it. So the lock buys steadiness, not more profit.
- Fixed stops (1-2 ATR, or under the pullback) lose more often than they save: win rates fall to 31-43%.
- Still weaker than the Trend 4h arm (+$321.9k, -$39.2k): with the 2-ATR giveback and 8 hours it is about +$36 a
  month at 0.01 lot with a -$423 deepest fall. A paper candidate at best.
