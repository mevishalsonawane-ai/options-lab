## Open interest and volume checks before buying (research/oi_filter.py)

BANKNIFTY year A 2024-02-13 .. 2025-02-14, year B 2025-02-17 .. 2026-02-23. The arms as they trade now, real
minute option prices, volume and open interest, 1 lot of 30, after costs. Each check only decides whether a trade
is taken. Cells: trades | win | net (t). "Kept" = trades the check allows; "skipped" = the ones it blocks.
"Change" = what skipping the blocked trades would have done to the arm's net (positive = the check helped).

## Verdict (read first)

Judged on what matters: do the trades a check KEEPS make money in both years? (Every trade counted; a trade with no
OI read possible - an entry in the first minutes - is taken as today.)

- **Liquidity 15+5** (today +103.0k / +30.3k): no OI or volume check, alone or together, improves both years. The best,
  "our side busier 15m", is -6.5k / +3.9k; every other check costs year A 1.7k to 78k. Keep it as it is.
- **ORB / ORB Fresh** (today -77.0k / -69.8k and -16.8k / -6.4k): only "own volume surge 15m" (the bought option traded
  more than 1.5x its average 15 minutes so far today) leaves them positive in both years: ORB +8.0k / +5.9k on 79 / 68
  trades, ORB Fresh +4.8k / +4.7k on 43 / 34. It works by skipping 97% of trades, and no t reaches 2 (1.15 / 0.88,
  0.85 / 1.03): a candidate, not an edge.
- **ORB Sweep, Range Fade**: no check leaves 30+ kept trades a year profitable in both years.
- The "change" tables below flatter every check on the losing arms: skipping trades of an arm that loses always "helps".
  145 arm x check results were tried, so a few will look good by chance.

### ORB

| check | year A kept | year A skipped | year B kept | year B skipped | change A / B |
|---|---|---|---|---|---|
| today (no check) | 2558 | 44% | Rs -77,013 (-1.99) | | 1884 | 41% | Rs -67,649 (-2.10) | | |
| own OI falling 15m | 1977 | 45% | Rs -45,958 (-1.33) | 581 | 42% | Rs -31,055 (-1.75) | 1412 | 40% | Rs -45,878 (-1.63) | 472 | 43% | Rs -21,771 (-1.39) | Rs +31,055 / Rs +21,771 |
| own OI not piling 15m | 2367 | 45% | Rs -69,208 (-1.85) | 191 | 40% | Rs -7,805 (-0.80) | 1761 | 41% | Rs -55,784 (-1.79) | 123 | 42% | Rs -11,865 (-1.44) | Rs +7,805 / Rs +11,865 |
| writers lean our way | 2477 | 44% | Rs -69,935 (-1.84) | 81 | 40% | Rs -7,078 (-0.96) | 1777 | 41% | Rs -61,464 (-1.98) | 107 | 43% | Rs -6,185 (-0.73) | Rs +7,078 / Rs +6,185 |
| other side adding 15m | 1939 | 44% | Rs -64,340 (-1.91) | 619 | 46% | Rs -12,673 (-0.66) | 1474 | 41% | Rs -34,977 (-1.24) | 410 | 43% | Rs -32,672 (-2.10) | Rs +12,673 / Rs +32,672 |
| PCR moving our way | 2450 | 44% | Rs -69,345 (-1.84) | 108 | 44% | Rs -7,668 (-0.89) | 1737 | 41% | Rs -61,964 (-2.01) | 147 | 44% | Rs -5,685 (-0.59) | Rs +7,668 / Rs +5,685 |
| short covering 15m | 1389 | 45% | Rs -10,918 (-0.38) | 1169 | 43% | Rs -66,095 (-2.58) | 974 | 40% | Rs -29,192 (-1.26) | 910 | 42% | Rs -38,457 (-1.72) | Rs +66,095 / Rs +38,457 |
| own volume surge 15m | 79 | 54% | Rs +7,955 (+1.15) | 2479 | 44% | Rs -84,968 (-2.23) | 57 | 54% | Rs +7,965 (+1.41) | 1827 | 41% | Rs -75,614 (-2.38) | Rs +84,968 / Rs +75,614 |
| our side busier 15m | 819 | 44% | Rs -35,140 (-1.61) | 1739 | 44% | Rs -41,873 (-1.31) | 914 | 40% | Rs -16,336 (-0.76) | 970 | 42% | Rs -51,313 (-2.14) | Rs +41,873 / Rs +51,313 |
| own volume rising 5m | 746 | 46% | Rs -11,853 (-0.56) | 1812 | 44% | Rs -65,160 (-2.00) | 536 | 39% | Rs -28,121 (-1.65) | 1348 | 42% | Rs -39,528 (-1.44) | Rs +65,160 / Rs +39,528 |
| long buildup with volume | 10 | 50% | Rs -1,450 (-0.55) | 2548 | 44% | Rs -75,563 (-1.95) | 10 | 60% | Rs +2,150 (+0.99) | 1874 | 41% | Rs -69,798 (-2.17) | Rs +75,563 / Rs +69,798 |
| short covering with volume | 65 | 54% | Rs +9,325 (+1.50) | 2493 | 44% | Rs -86,338 (-2.26) | 45 | 53% | Rs +5,325 (+1.02) | 1839 | 41% | Rs -72,974 (-2.29) | Rs +86,338 / Rs +72,974 |

ORB, OI and volume together (take the trade only when both say yes):

| OI check + volume check | kept A | kept B | change A / B |
|---|---|---|---|
| own OI falling 15m + own volume surge 15m | 68 | 54% | Rs +8,860 (+1.39) | 46 | 54% | Rs +5,870 (+1.12) | Rs +85,874 / Rs +73,519 |
| own OI falling 15m + our side busier 15m | 691 | 45% | Rs -17,300 (-0.86) | 705 | 39% | Rs -21,630 (-1.12) | Rs +59,713 / Rs +46,019 |
| own OI falling 15m + own volume rising 5m | 556 | 46% | Rs -19,103 (-1.01) | 370 | 37% | Rs -19,591 (-1.38) | Rs +57,910 / Rs +48,058 |
| own OI not piling 15m + own volume surge 15m | 70 | 53% | Rs +7,550 (+1.16) | 47 | 55% | Rs +6,115 (+1.16) | Rs +84,564 / Rs +73,764 |
| own OI not piling 15m + our side busier 15m | 761 | 45% | Rs -24,750 (-1.17) | 843 | 40% | Rs -13,031 (-0.63) | Rs +52,263 / Rs +54,618 |
| own OI not piling 15m + own volume rising 5m | 664 | 46% | Rs -17,543 (-0.87) | 486 | 38% | Rs -24,171 (-1.50) | Rs +59,470 / Rs +43,478 |
| writers lean our way + own volume surge 15m | 61 | 57% | Rs +9,245 (+1.57) | 44 | 52% | Rs +2,380 (+0.48) | Rs +86,258 / Rs +70,028 |
| writers lean our way + our side busier 15m | 753 | 44% | Rs -29,415 (-1.41) | 834 | 40% | Rs -12,536 (-0.62) | Rs +47,598 / Rs +55,113 |
| writers lean our way + own volume rising 5m | 699 | 46% | Rs -9,645 (-0.47) | 487 | 38% | Rs -33,226 (-2.05) | Rs +67,368 / Rs +34,422 |
| other side adding 15m + own volume surge 15m | 75 | 56% | Rs +9,075 (+1.34) | 54 | 56% | Rs +7,530 (+1.34) | Rs +86,088 / Rs +75,179 |
| other side adding 15m + our side busier 15m | 753 | 44% | Rs -27,310 (-1.30) | 835 | 40% | Rs -6,590 (-0.32) | Rs +49,703 / Rs +61,058 |
| other side adding 15m + own volume rising 5m | 545 | 46% | Rs -13,170 (-0.73) | 396 | 40% | Rs -3,921 (-0.27) | Rs +63,843 / Rs +63,728 |
| PCR moving our way + own volume surge 15m | 63 | 57% | Rs +9,435 (+1.53) | 44 | 57% | Rs +3,580 (+0.72) | Rs +86,448 / Rs +71,228 |
| PCR moving our way + our side busier 15m | 766 | 44% | Rs -30,426 (-1.45) | 825 | 40% | Rs -13,840 (-0.68) | Rs +46,588 / Rs +53,808 |
| PCR moving our way + own volume rising 5m | 694 | 46% | Rs -9,665 (-0.48) | 476 | 38% | Rs -26,021 (-1.62) | Rs +67,348 / Rs +41,628 |
| short covering 15m + own volume surge 15m | 65 | 54% | Rs +9,325 (+1.50) | 45 | 53% | Rs +5,325 (+1.02) | Rs +86,338 / Rs +72,974 |
| short covering 15m + our side busier 15m | 595 | 44% | Rs -12,320 (-0.66) | 574 | 38% | Rs -13,870 (-0.81) | Rs +64,693 / Rs +53,779 |
| short covering 15m + own volume rising 5m | 439 | 46% | Rs -4,568 (-0.27) | 282 | 37% | Rs -12,210 (-1.00) | Rs +72,445 / Rs +55,438 |

### ORB Fresh

| check | year A kept | year A skipped | year B kept | year B skipped | change A / B |
|---|---|---|---|---|---|
| today (no check) | 553 | 46% | Rs -16,838 (-0.90) | | 474 | 43% | Rs -5,621 (-0.36) | | |
| own OI falling 15m | 380 | 44% | Rs -18,723 (-1.17) | 173 | 50% | Rs +1,885 (+0.20) | 317 | 42% | Rs -2,735 (-0.22) | 157 | 47% | Rs -2,886 (-0.32) | Rs -1,885 / Rs +2,886 |
| own OI not piling 15m | 456 | 45% | Rs -19,303 (-1.12) | 97 | 48% | Rs +2,465 (+0.35) | 418 | 42% | Rs -7,390 (-0.51) | 56 | 54% | Rs +1,769 (+0.30) | Rs -2,465 / Rs -1,769 |
| writers lean our way | 487 | 46% | Rs -12,685 (-0.73) | 66 | 42% | Rs -4,153 (-0.64) | 402 | 43% | Rs -9,161 (-0.65) | 72 | 44% | Rs +3,540 (+0.54) | Rs +4,153 / Rs -3,540 |
| other side adding 15m | 446 | 45% | Rs -17,025 (-1.01) | 107 | 48% | Rs +187 (+0.02) | 370 | 42% | Rs -4,150 (-0.31) | 104 | 50% | Rs -1,471 (-0.19) | Rs -187 / Rs +1,471 |
| PCR moving our way | 482 | 46% | Rs -8,505 (-0.49) | 71 | 44% | Rs -8,333 (-1.17) | 394 | 44% | Rs -6,621 (-0.47) | 80 | 42% | Rs +1,000 (+0.15) | Rs +8,333 / Rs -1,000 |
| short covering 15m | 332 | 45% | Rs -12,483 (-0.83) | 221 | 47% | Rs -4,355 (-0.40) | 271 | 40% | Rs -2,605 (-0.22) | 203 | 48% | Rs -3,016 (-0.30) | Rs +4,355 / Rs +3,016 |
| own volume surge 15m | 43 | 58% | Rs +4,835 (+0.85) | 510 | 45% | Rs -21,673 (-1.22) | 26 | 58% | Rs +5,470 (+1.52) | 448 | 43% | Rs -11,091 (-0.74) | Rs +21,673 / Rs +11,091 |
| our side busier 15m | 291 | 44% | Rs -13,300 (-0.99) | 262 | 47% | Rs -3,538 (-0.27) | 307 | 44% | Rs +8,064 (+0.68) | 167 | 43% | Rs -13,685 (-1.36) | Rs +3,538 / Rs +13,685 |
| own volume rising 5m | 296 | 47% | Rs -3 (-0.00) | 257 | 44% | Rs -16,835 (-1.30) | 245 | 43% | Rs +4,525 (+0.41) | 229 | 44% | Rs -10,146 (-0.93) | Rs +16,835 / Rs +10,146 |
| long buildup with volume | 9 | 56% | Rs -195 (-0.08) | 544 | 46% | Rs -16,643 (-0.90) | 7 | 43% | Rs +515 (+0.27) | 467 | 43% | Rs -6,136 (-0.40) | Rs +16,643 / Rs +6,136 |
| short covering with volume | 33 | 58% | Rs +4,485 (+0.86) | 520 | 45% | Rs -21,323 (-1.19) | 19 | 63% | Rs +4,955 (+1.60) | 455 | 43% | Rs -10,576 (-0.70) | Rs +21,323 / Rs +10,576 |

ORB Fresh, OI and volume together (take the trade only when both say yes):

| OI check + volume check | kept A | kept B | change A / B |
|---|---|---|---|
| own OI falling 15m + own volume surge 15m | 34 | 59% | Rs +5,030 (+0.96) | 19 | 63% | Rs +4,955 (+1.60) | Rs +21,868 / Rs +10,576 |
| own OI falling 15m + our side busier 15m | 232 | 44% | Rs -10,355 (-0.84) | 219 | 41% | Rs +555 (+0.05) | Rs +6,483 / Rs +6,176 |
| own OI falling 15m + own volume rising 5m | 203 | 46% | Rs -8,388 (-0.72) | 163 | 37% | Rs +935 (+0.10) | Rs +8,450 / Rs +6,556 |
| own OI not piling 15m + own volume surge 15m | 36 | 56% | Rs +3,720 (+0.68) | 20 | 65% | Rs +5,200 (+1.68) | Rs +20,558 / Rs +10,821 |
| own OI not piling 15m + our side busier 15m | 250 | 46% | Rs -5,645 (-0.45) | 267 | 43% | Rs +6,015 (+0.55) | Rs +11,193 / Rs +11,636 |
| own OI not piling 15m + own volume rising 5m | 238 | 46% | Rs -9,413 (-0.76) | 213 | 40% | Rs +2,085 (+0.21) | Rs +7,425 / Rs +7,706 |
| writers lean our way + own volume surge 15m | 29 | 62% | Rs +5,005 (+1.08) | 17 | 59% | Rs +2,665 (+0.95) | Rs +21,843 / Rs +8,286 |
| writers lean our way + our side busier 15m | 238 | 45% | Rs -9,190 (-0.75) | 255 | 44% | Rs +7,624 (+0.75) | Rs +7,648 / Rs +13,245 |
| writers lean our way + own volume rising 5m | 253 | 48% | Rs +785 (+0.06) | 194 | 42% | Rs -1,070 (-0.11) | Rs +17,623 / Rs +4,551 |
| other side adding 15m + own volume surge 15m | 40 | 60% | Rs +5,900 (+1.06) | 24 | 58% | Rs +4,980 (+1.39) | Rs +22,738 / Rs +10,601 |
| other side adding 15m + our side busier 15m | 269 | 44% | Rs -13,890 (-1.08) | 268 | 43% | Rs +5,360 (+0.49) | Rs +2,948 / Rs +10,981 |
| other side adding 15m + own volume rising 5m | 242 | 47% | Rs -3,105 (-0.25) | 198 | 42% | Rs +7,110 (+0.73) | Rs +13,733 / Rs +12,730 |
| PCR moving our way + own volume surge 15m | 31 | 65% | Rs +5,495 (+1.11) | 18 | 67% | Rs +4,410 (+1.49) | Rs +22,333 / Rs +10,031 |
| PCR moving our way + our side busier 15m | 249 | 44% | Rs -10,690 (-0.86) | 250 | 44% | Rs +6,999 (+0.68) | Rs +6,148 / Rs +12,620 |
| PCR moving our way + own volume rising 5m | 248 | 47% | Rs -435 (-0.04) | 192 | 43% | Rs +2,040 (+0.21) | Rs +16,403 / Rs +7,661 |
| short covering 15m + own volume surge 15m | 33 | 58% | Rs +4,485 (+0.86) | 19 | 63% | Rs +4,955 (+1.60) | Rs +21,323 / Rs +10,576 |
| short covering 15m + our side busier 15m | 221 | 44% | Rs -8,250 (-0.69) | 200 | 40% | Rs -500 (-0.05) | Rs +8,588 / Rs +5,121 |
| short covering 15m + own volume rising 5m | 196 | 45% | Rs -10,104 (-0.87) | 157 | 36% | Rs -1,135 (-0.13) | Rs +6,735 / Rs +4,486 |

### ORB Sweep

| check | year A kept | year A skipped | year B kept | year B skipped | change A / B |
|---|---|---|---|---|---|
| today (no check) | 349 | 33% | Rs -24,046 (-1.18) | | 326 | 27% | Rs -76,025 (-4.19) | | |
| own OI falling 15m | 106 | 38% | Rs -5,269 (-0.45) | 243 | 31% | Rs -18,777 (-1.12) | 109 | 28% | Rs -17,700 (-1.84) | 217 | 26% | Rs -58,326 (-3.79) | Rs +18,777 / Rs +58,326 |
| own OI not piling 15m | 172 | 37% | Rs -4,416 (-0.29) | 177 | 29% | Rs -19,630 (-1.44) | 195 | 24% | Rs -50,720 (-3.95) | 131 | 31% | Rs -25,306 (-1.96) | Rs +19,630 / Rs +25,306 |
| writers lean our way | 36 | 31% | Rs -5,296 (-0.94) | 313 | 33% | Rs -18,750 (-0.96) | 50 | 18% | Rs -18,350 (-2.36) | 276 | 28% | Rs -57,675 (-3.51) | Rs +18,750 / Rs +57,675 |
| other side adding 15m | 160 | 34% | Rs -11,089 (-0.77) | 189 | 32% | Rs -12,957 (-0.89) | 105 | 26% | Rs -24,490 (-2.45) | 221 | 27% | Rs -51,534 (-3.39) | Rs +12,957 / Rs +51,534 |
| PCR moving our way | 41 | 32% | Rs -7,372 (-1.12) | 308 | 33% | Rs -16,674 (-0.87) | 55 | 27% | Rs -7,225 (-0.84) | 271 | 27% | Rs -68,800 (-4.30) | Rs +16,674 / Rs +68,800 |
| short covering 15m | 59 | 39% | Rs +3,091 (+0.32) | 290 | 32% | Rs -27,137 (-1.51) | 65 | 28% | Rs -11,951 (-1.57) | 261 | 26% | Rs -64,074 (-3.88) | Rs +27,137 / Rs +64,074 |
| own volume surge 15m | 16 | 56% | Rs +7,520 (+1.57) | 333 | 32% | Rs -31,566 (-1.60) | 12 | 42% | Rs -3,060 (-0.67) | 314 | 26% | Rs -72,965 (-4.14) | Rs +31,566 / Rs +72,965 |
| our side busier 15m | 151 | 35% | Rs -5,214 (-0.37) | 198 | 31% | Rs -18,832 (-1.27) | 118 | 28% | Rs -24,211 (-2.30) | 208 | 26% | Rs -51,814 (-3.49) | Rs +18,832 / Rs +51,814 |
| own volume rising 5m | 153 | 32% | Rs -18,118 (-1.34) | 196 | 34% | Rs -5,928 (-0.39) | 155 | 25% | Rs -34,840 (-2.80) | 171 | 28% | Rs -41,186 (-3.11) | Rs +5,928 / Rs +41,186 |
| long buildup with volume | 0 | |  | 349 | 33% | Rs -24,046 (-1.18) | 0 | |  | 326 | 27% | Rs -76,025 (-4.19) | Rs +24,046 / Rs +76,025 |
| short covering with volume | 3 | 67% | Rs +3,435 (+0.95) | 346 | 33% | Rs -27,481 (-1.37) | 3 | 67% | Rs +1,635 (+0.52) | 323 | 26% | Rs -77,660 (-4.33) | Rs +27,481 / Rs +77,660 |

ORB Sweep, OI and volume together (take the trade only when both say yes):

| OI check + volume check | kept A | kept B | change A / B |
|---|---|---|---|
| own OI falling 15m + own volume surge 15m | 4 | 50% | Rs +3,380 (+0.94) | 6 | 50% | Rs +270 (+0.07) | Rs +27,426 / Rs +76,295 |
| own OI falling 15m + our side busier 15m | 63 | 37% | Rs -4,665 (-0.50) | 63 | 22% | Rs -23,133 (-3.32) | Rs +19,381 / Rs +52,892 |
| own OI falling 15m + own volume rising 5m | 63 | 32% | Rs -11,865 (-1.32) | 58 | 22% | Rs -16,691 (-2.36) | Rs +12,181 / Rs +59,334 |
| own OI not piling 15m + own volume surge 15m | 5 | 60% | Rs +3,925 (+1.12) | 7 | 57% | Rs +1,415 (+0.37) | Rs +27,971 / Rs +77,440 |
| own OI not piling 15m + our side busier 15m | 101 | 36% | Rs -6,155 (-0.51) | 96 | 24% | Rs -29,397 (-3.42) | Rs +17,891 / Rs +46,628 |
| own OI not piling 15m + own volume rising 5m | 92 | 36% | Rs -7,776 (-0.70) | 91 | 21% | Rs -30,348 (-3.54) | Rs +16,270 / Rs +45,678 |
| writers lean our way + own volume surge 15m | 3 | 67% | Rs +1,035 (+1.73) | 1 | 100% | Rs +1,145 (+nan) | Rs +25,081 / Rs +77,170 |
| writers lean our way + our side busier 15m | 11 | 27% | Rs -3,005 (-1.05) | 14 | 7% | Rs -10,370 (-3.57) | Rs +21,041 / Rs +65,655 |
| writers lean our way + own volume rising 5m | 16 | 25% | Rs -7,196 (-2.01) | 30 | 13% | Rs -13,650 (-2.35) | Rs +16,850 / Rs +62,375 |
| other side adding 15m + own volume surge 15m | 6 | 67% | Rs +3,870 (+0.97) | 3 | 67% | Rs +435 (+0.20) | Rs +27,916 / Rs +76,460 |
| other side adding 15m + our side busier 15m | 88 | 36% | Rs -4,748 (-0.42) | 55 | 24% | Rs -14,454 (-2.12) | Rs +19,298 / Rs +61,572 |
| other side adding 15m + own volume rising 5m | 69 | 33% | Rs -8,912 (-0.89) | 64 | 30% | Rs -6,187 (-0.78) | Rs +15,134 / Rs +69,838 |
| PCR moving our way + own volume surge 15m | 3 | 33% | Rs -765 (-0.48) | 2 | 50% | Rs -110 (+nan) | Rs +23,281 / Rs +75,915 |
| PCR moving our way + our side busier 15m | 21 | 33% | Rs -3,555 (-0.67) | 21 | 29% | Rs -5,355 (-0.99) | Rs +20,491 / Rs +70,670 |
| PCR moving our way + own volume rising 5m | 19 | 26% | Rs -9,762 (-2.03) | 31 | 19% | Rs -8,305 (-1.30) | Rs +14,284 / Rs +67,720 |
| short covering 15m + own volume surge 15m | 3 | 67% | Rs +3,435 (+0.95) | 3 | 67% | Rs +1,635 (+0.52) | Rs +27,481 / Rs +77,660 |
| short covering 15m + our side busier 15m | 47 | 38% | Rs +1,015 (+0.12) | 45 | 18% | Rs -19,012 (-3.28) | Rs +25,061 / Rs +57,012 |
| short covering 15m + own volume rising 5m | 44 | 34% | Rs -1,820 (-0.22) | 41 | 22% | Rs -10,841 (-1.77) | Rs +22,226 / Rs +65,184 |

### Range Fade

| check | year A kept | year A skipped | year B kept | year B skipped | change A / B |
|---|---|---|---|---|---|
| today (no check) | 368 | 36% | Rs -47,158 (-3.37) | | 367 | 33% | Rs -72,340 (-5.23) | | |
| own OI falling 15m | 124 | 40% | Rs -16,573 (-2.01) | 244 | 34% | Rs -30,584 (-2.70) | 116 | 43% | Rs -11,032 (-1.38) | 251 | 28% | Rs -61,308 (-5.46) | Rs +30,584 / Rs +61,308 |
| own OI not piling 15m | 212 | 36% | Rs -29,213 (-2.68) | 156 | 35% | Rs -17,944 (-2.04) | 216 | 36% | Rs -29,942 (-2.86) | 151 | 28% | Rs -42,398 (-4.73) | Rs +17,944 / Rs +42,398 |
| writers lean our way | 44 | 39% | Rs -8,720 (-1.62) | 324 | 35% | Rs -38,438 (-2.97) | 61 | 36% | Rs -12,055 (-2.02) | 306 | 32% | Rs -60,285 (-4.83) | Rs +38,438 / Rs +60,285 |
| other side adding 15m | 182 | 35% | Rs -21,374 (-2.12) | 186 | 37% | Rs -25,784 (-2.65) | 157 | 36% | Rs -17,228 (-2.03) | 210 | 30% | Rs -55,112 (-5.08) | Rs +25,784 / Rs +55,112 |
| PCR moving our way | 53 | 40% | Rs -6,815 (-1.16) | 315 | 35% | Rs -40,342 (-3.17) | 65 | 42% | Rs -3,275 (-0.52) | 302 | 31% | Rs -69,065 (-5.63) | Rs +40,342 / Rs +69,065 |
| short covering 15m | 74 | 36% | Rs -13,034 (-2.02) | 294 | 36% | Rs -34,124 (-2.74) | 65 | 38% | Rs -10,026 (-1.78) | 302 | 31% | Rs -62,314 (-4.93) | Rs +34,124 / Rs +62,314 |
| own volume surge 15m | 11 | 55% | Rs +1,795 (+0.64) | 357 | 35% | Rs -48,952 (-3.57) | 10 | 30% | Rs -2,350 (-0.76) | 357 | 33% | Rs -69,990 (-5.18) | Rs +48,952 / Rs +69,990 |
| our side busier 15m | 189 | 36% | Rs -17,295 (-1.72) | 179 | 36% | Rs -29,862 (-3.07) | 153 | 35% | Rs -13,725 (-1.62) | 214 | 31% | Rs -58,615 (-5.42) | Rs +29,862 / Rs +58,615 |
| own volume rising 5m | 163 | 37% | Rs -26,318 (-2.71) | 205 | 35% | Rs -20,839 (-2.07) | 168 | 35% | Rs -32,550 (-3.47) | 199 | 31% | Rs -39,790 (-3.91) | Rs +20,839 / Rs +39,790 |
| long buildup with volume | 1 | 0% | Rs -55 (+nan) | 367 | 36% | Rs -47,102 (-3.36) | 1 | 100% | Rs +1,145 (+nan) | 366 | 33% | Rs -73,485 (-5.34) | Rs +47,102 / Rs +73,485 |
| short covering with volume | 3 | 67% | Rs +135 (+0.06) | 365 | 36% | Rs -47,292 (-3.40) | 5 | 40% | Rs +1,525 (+1.27) | 362 | 33% | Rs -73,865 (-5.38) | Rs +47,292 / Rs +73,865 |

Range Fade, OI and volume together (take the trade only when both say yes):

| OI check + volume check | kept A | kept B | change A / B |
|---|---|---|---|
| own OI falling 15m + own volume surge 15m | 3 | 67% | Rs +135 (+0.06) | 5 | 40% | Rs +1,525 (+1.27) | Rs +47,292 / Rs +73,865 |
| own OI falling 15m + our side busier 15m | 83 | 37% | Rs -9,965 (-1.49) | 69 | 38% | Rs -10,695 (-1.68) | Rs +37,192 / Rs +61,645 |
| own OI falling 15m + own volume rising 5m | 63 | 46% | Rs -7,554 (-1.18) | 55 | 44% | Rs -4,825 (-0.93) | Rs +39,604 / Rs +67,515 |
| own OI not piling 15m + own volume surge 15m | 3 | 67% | Rs +135 (+0.06) | 5 | 40% | Rs +1,525 (+1.27) | Rs +47,292 / Rs +73,865 |
| own OI not piling 15m + our side busier 15m | 137 | 35% | Rs -19,235 (-2.16) | 114 | 37% | Rs -9,180 (-1.18) | Rs +27,922 / Rs +63,160 |
| own OI not piling 15m + own volume rising 5m | 99 | 39% | Rs -16,734 (-2.09) | 104 | 39% | Rs -14,030 (-1.94) | Rs +30,424 / Rs +58,310 |
| writers lean our way + own volume surge 15m | 2 | 100% | Rs +790 (+nan) | 1 | 0% | Rs -1,255 (+nan) | Rs +47,948 / Rs +71,085 |
| writers lean our way + our side busier 15m | 9 | 67% | Rs +2,505 (+2.19) | 19 | 37% | Rs -3,445 (-1.09) | Rs +49,662 / Rs +68,895 |
| writers lean our way + own volume rising 5m | 20 | 25% | Rs -8,600 (-2.69) | 38 | 39% | Rs -10,490 (-2.08) | Rs +38,558 / Rs +61,850 |
| other side adding 15m + own volume surge 15m | 3 | 33% | Rs -165 (-0.08) | 5 | 40% | Rs +1,525 (+1.27) | Rs +46,992 / Rs +73,865 |
| other side adding 15m + our side busier 15m | 124 | 36% | Rs -8,320 (-0.99) | 89 | 35% | Rs -3,095 (-0.49) | Rs +38,838 / Rs +69,245 |
| other side adding 15m + own volume rising 5m | 71 | 37% | Rs -10,505 (-1.64) | 77 | 38% | Rs -6,935 (-1.15) | Rs +36,652 / Rs +65,405 |
| PCR moving our way + own volume surge 15m | 2 | 100% | Rs +790 (+nan) | 1 | 0% | Rs -1,255 (+nan) | Rs +47,948 / Rs +71,085 |
| PCR moving our way + our side busier 15m | 21 | 52% | Rs +4,245 (+1.41) | 28 | 39% | Rs -640 (-0.15) | Rs +51,402 / Rs +71,700 |
| PCR moving our way + own volume rising 5m | 26 | 31% | Rs -6,230 (-1.60) | 36 | 47% | Rs -3,180 (-0.64) | Rs +40,928 / Rs +69,160 |
| short covering 15m + own volume surge 15m | 3 | 67% | Rs +135 (+0.06) | 5 | 40% | Rs +1,525 (+1.27) | Rs +47,292 / Rs +73,865 |
| short covering 15m + our side busier 15m | 64 | 36% | Rs -9,520 (-1.57) | 43 | 33% | Rs -10,165 (-2.10) | Rs +37,638 / Rs +62,175 |
| short covering 15m + own volume rising 5m | 44 | 45% | Rs -6,920 (-1.25) | 39 | 44% | Rs -5,745 (-1.28) | Rs +40,238 / Rs +66,595 |

### Liquidity 15+5

| check | year A kept | year A skipped | year B kept | year B skipped | change A / B |
|---|---|---|---|---|---|
| today (no check) | 404 | 46% | Rs +66,810 (+1.81) | | 425 | 49% | Rs -3,113 (-0.18) | | |
| own OI falling 15m | 137 | 47% | Rs +19,088 (+0.74) | 267 | 45% | Rs +47,723 (+1.82) | 174 | 52% | Rs -6,979 (-0.68) | 251 | 47% | Rs +3,867 (+0.27) | Rs -47,723 / Rs -3,867 |
| own OI not piling 15m | 198 | 47% | Rs +53,338 (+1.68) | 206 | 44% | Rs +13,473 (+0.72) | 257 | 49% | Rs -11,365 (-0.97) | 168 | 48% | Rs +8,252 (+0.63) | Rs -13,473 / Rs -8,252 |
| writers lean our way | 286 | 46% | Rs +48,981 (+1.47) | 118 | 45% | Rs +17,829 (+1.12) | 330 | 48% | Rs -2,953 (-0.21) | 95 | 49% | Rs -160 (-0.01) | Rs -17,829 / Rs +160 |
| other side adding 15m | 387 | 46% | Rs +60,916 (+1.75) | 17 | 41% | Rs +5,894 (+0.47) | 413 | 48% | Rs -8,799 (-0.51) | 12 | 58% | Rs +5,687 (+1.53) | Rs -5,894 / Rs -5,687 |
| PCR moving our way | 363 | 47% | Rs +65,071 (+1.80) | 41 | 39% | Rs +1,739 (+0.23) | 374 | 49% | Rs -5,364 (-0.37) | 51 | 43% | Rs +2,251 (+0.23) | Rs -1,739 / Rs -2,251 |
| short covering 15m | 137 | 47% | Rs +19,088 (+0.74) | 267 | 45% | Rs +47,723 (+1.82) | 174 | 52% | Rs -6,979 (-0.68) | 251 | 47% | Rs +3,867 (+0.27) | Rs -47,723 / Rs -3,867 |
| own volume surge 15m | 171 | 42% | Rs +3,963 (+0.18) | 233 | 48% | Rs +62,847 (+2.11) | 126 | 49% | Rs -2,673 (-0.26) | 299 | 48% | Rs -440 (-0.03) | Rs -62,847 / Rs +440 |
| our side busier 15m | 389 | 45% | Rs +60,273 (+1.84) | 15 | 60% | Rs +6,538 (+0.38) | 407 | 50% | Rs +790 (+0.05) | 18 | 28% | Rs -3,903 (-2.61) | Rs -6,538 / Rs +3,903 |
| own volume rising 5m | 288 | 45% | Rs +24,677 (+0.91) | 116 | 48% | Rs +42,134 (+1.69) | 286 | 49% | Rs -6,405 (-0.41) | 139 | 47% | Rs +3,292 (+0.40) | Rs -42,134 / Rs -3,292 |
| long buildup with volume | 105 | 42% | Rs +15,261 (+0.85) | 299 | 47% | Rs +51,550 (+1.60) | 65 | 38% | Rs -160 (-0.02) | 360 | 51% | Rs -2,952 (-0.18) | Rs -51,550 / Rs +2,952 |
| short covering with volume | 66 | 42% | Rs -11,298 (-0.93) | 338 | 46% | Rs +78,108 (+2.25) | 61 | 61% | Rs -2,512 (-0.35) | 364 | 47% | Rs -600 (-0.04) | Rs -78,108 / Rs +600 |

Liquidity 15+5, OI and volume together (take the trade only when both say yes):

| OI check + volume check | kept A | kept B | change A / B |
|---|---|---|---|
| own OI falling 15m + own volume surge 15m | 66 | 42% | Rs -11,298 (-0.93) | 61 | 61% | Rs -2,512 (-0.35) | Rs -78,108 / Rs +600 |
| own OI falling 15m + our side busier 15m | 126 | 45% | Rs +13,458 (+0.68) | 164 | 54% | Rs -4,199 (-0.42) | Rs -53,352 / Rs -1,086 |
| own OI falling 15m + own volume rising 5m | 105 | 41% | Rs -7,643 (-0.45) | 137 | 50% | Rs -14,742 (-1.74) | Rs -74,453 / Rs -11,630 |
| own OI not piling 15m + own volume surge 15m | 84 | 45% | Rs +10,506 (+0.59) | 82 | 54% | Rs -4,476 (-0.55) | Rs -56,304 / Rs -1,363 |
| own OI not piling 15m + our side busier 15m | 186 | 46% | Rs +46,244 (+1.71) | 242 | 50% | Rs -7,949 (-0.68) | Rs -20,567 / Rs -4,837 |
| own OI not piling 15m + own volume rising 5m | 154 | 43% | Rs +13,945 (+0.60) | 183 | 49% | Rs -16,476 (-1.66) | Rs -52,865 / Rs -13,364 |
| writers lean our way + own volume surge 15m | 118 | 42% | Rs +6,135 (+0.33) | 101 | 49% | Rs +537 (+0.07) | Rs -60,675 / Rs +3,650 |
| writers lean our way + our side busier 15m | 273 | 45% | Rs +43,490 (+1.51) | 315 | 49% | Rs -343 (-0.02) | Rs -23,320 / Rs +2,770 |
| writers lean our way + own volume rising 5m | 198 | 45% | Rs +8,111 (+0.36) | 215 | 49% | Rs -5,714 (-0.48) | Rs -58,699 / Rs -2,601 |
| other side adding 15m + own volume surge 15m | 167 | 43% | Rs +6,205 (+0.29) | 121 | 47% | Rs -10,118 (-1.08) | Rs -60,605 / Rs -7,005 |
| other side adding 15m + our side busier 15m | 378 | 46% | Rs +61,724 (+1.89) | 395 | 49% | Rs -4,896 (-0.29) | Rs -5,087 / Rs -1,784 |
| other side adding 15m + own volume rising 5m | 277 | 46% | Rs +34,292 (+1.31) | 275 | 49% | Rs -12,538 (-0.83) | Rs -32,518 / Rs -9,425 |
| PCR moving our way + own volume surge 15m | 159 | 42% | Rs +8,593 (+0.40) | 109 | 48% | Rs -2,822 (-0.33) | Rs -58,217 / Rs +291 |
| PCR moving our way + our side busier 15m | 352 | 46% | Rs +60,425 (+1.89) | 358 | 50% | Rs -2,604 (-0.18) | Rs -6,385 / Rs +509 |
| PCR moving our way + own volume rising 5m | 255 | 45% | Rs +21,288 (+0.81) | 248 | 51% | Rs -7,083 (-0.57) | Rs -45,522 / Rs -3,970 |
| short covering 15m + own volume surge 15m | 66 | 42% | Rs -11,298 (-0.93) | 61 | 61% | Rs -2,512 (-0.35) | Rs -78,108 / Rs +600 |
| short covering 15m + our side busier 15m | 126 | 45% | Rs +13,458 (+0.68) | 164 | 54% | Rs -4,199 (-0.42) | Rs -53,352 / Rs -1,086 |
| short covering 15m + own volume rising 5m | 105 | 41% | Rs -7,643 (-0.45) | 137 | 50% | Rs -14,742 (-1.74) | Rs -74,453 / Rs -11,630 |

### Checks that helped in BOTH years

| arm | check | change A | change B | trades kept |
|---|---|---|---|---|
| ORB | own volume surge 15m | Rs +84,968 | Rs +75,614 | 136 of 4442 |
| ORB | other side adding 15m + own volume surge 15m | Rs +86,088 | Rs +75,179 | 129 of 4442 |
| ORB | own OI not piling 15m + own volume surge 15m | Rs +84,564 | Rs +73,764 | 117 of 4442 |
| ORB | own OI falling 15m + own volume surge 15m | Rs +85,874 | Rs +73,519 | 114 of 4442 |
| ORB | short covering with volume | Rs +86,338 | Rs +72,974 | 110 of 4442 |
| ORB | short covering 15m + own volume surge 15m | Rs +86,338 | Rs +72,974 | 110 of 4442 |
| ORB | PCR moving our way + own volume surge 15m | Rs +86,448 | Rs +71,228 | 107 of 4442 |
| ORB | writers lean our way + own volume surge 15m | Rs +86,258 | Rs +70,028 | 105 of 4442 |
| ORB | long buildup with volume | Rs +75,563 | Rs +69,798 | 20 of 4442 |
| ORB | other side adding 15m + own volume rising 5m | Rs +63,843 | Rs +63,728 | 941 of 4442 |
| ORB | short covering 15m + own volume rising 5m | Rs +72,445 | Rs +55,438 | 721 of 4442 |
| ORB | short covering 15m + our side busier 15m | Rs +64,693 | Rs +53,779 | 1169 of 4442 |
| ORB | own OI not piling 15m + our side busier 15m | Rs +52,263 | Rs +54,618 | 1604 of 4442 |
| Range Fade | PCR moving our way + our side busier 15m | Rs +51,402 | Rs +71,700 | 49 of 735 |
| ORB | other side adding 15m + our side busier 15m | Rs +49,703 | Rs +61,058 | 1588 of 4442 |
| Range Fade | writers lean our way + our side busier 15m | Rs +49,662 | Rs +68,895 | 28 of 735 |
| Range Fade | own volume surge 15m | Rs +48,952 | Rs +69,990 | 21 of 735 |
| ORB | own OI falling 15m + own volume rising 5m | Rs +57,910 | Rs +48,058 | 926 of 4442 |
| Range Fade | writers lean our way + own volume surge 15m | Rs +47,948 | Rs +71,085 | 3 of 735 |
| Range Fade | PCR moving our way + own volume surge 15m | Rs +47,948 | Rs +71,085 | 3 of 735 |
| ORB | writers lean our way + our side busier 15m | Rs +47,598 | Rs +55,113 | 1587 of 4442 |
| Range Fade | short covering with volume | Rs +47,292 | Rs +73,865 | 8 of 735 |
| Range Fade | own OI falling 15m + own volume surge 15m | Rs +47,292 | Rs +73,865 | 8 of 735 |
| Range Fade | own OI not piling 15m + own volume surge 15m | Rs +47,292 | Rs +73,865 | 8 of 735 |
| Range Fade | short covering 15m + own volume surge 15m | Rs +47,292 | Rs +73,865 | 8 of 735 |
| Range Fade | long buildup with volume | Rs +47,102 | Rs +73,485 | 2 of 735 |
| Range Fade | other side adding 15m + own volume surge 15m | Rs +46,992 | Rs +73,865 | 8 of 735 |
| ORB | PCR moving our way + our side busier 15m | Rs +46,588 | Rs +53,808 | 1591 of 4442 |
| ORB | own OI falling 15m + our side busier 15m | Rs +59,713 | Rs +46,019 | 1396 of 4442 |
| ORB | own OI not piling 15m + own volume rising 5m | Rs +59,470 | Rs +43,478 | 1150 of 4442 |
| ORB | our side busier 15m | Rs +41,873 | Rs +51,313 | 1733 of 4442 |
| ORB | PCR moving our way + own volume rising 5m | Rs +67,348 | Rs +41,628 | 1170 of 4442 |
| Range Fade | PCR moving our way + own volume rising 5m | Rs +40,928 | Rs +69,160 | 62 of 735 |
| Range Fade | PCR moving our way | Rs +40,342 | Rs +69,065 | 118 of 735 |
| Range Fade | short covering 15m + own volume rising 5m | Rs +40,238 | Rs +66,595 | 83 of 735 |
| Range Fade | own OI falling 15m + own volume rising 5m | Rs +39,604 | Rs +67,515 | 118 of 735 |
| ORB | own volume rising 5m | Rs +65,160 | Rs +39,528 | 1282 of 4442 |
| Range Fade | other side adding 15m + our side busier 15m | Rs +38,838 | Rs +69,245 | 213 of 735 |
| Range Fade | writers lean our way + own volume rising 5m | Rs +38,558 | Rs +61,850 | 58 of 735 |
| ORB | short covering 15m | Rs +66,095 | Rs +38,457 | 2363 of 4442 |
| Range Fade | writers lean our way | Rs +38,438 | Rs +60,285 | 105 of 735 |
| Range Fade | short covering 15m + our side busier 15m | Rs +37,638 | Rs +62,175 | 107 of 735 |
| Range Fade | own OI falling 15m + our side busier 15m | Rs +37,192 | Rs +61,645 | 152 of 735 |
| Range Fade | other side adding 15m + own volume rising 5m | Rs +36,652 | Rs +65,405 | 148 of 735 |
| ORB | writers lean our way + own volume rising 5m | Rs +67,368 | Rs +34,422 | 1186 of 4442 |
| Range Fade | short covering 15m | Rs +34,124 | Rs +62,314 | 139 of 735 |
| ORB Sweep | own volume surge 15m | Rs +31,566 | Rs +72,965 | 28 of 675 |
| Range Fade | own OI falling 15m | Rs +30,584 | Rs +61,308 | 240 of 735 |
| Range Fade | own OI not piling 15m + own volume rising 5m | Rs +30,424 | Rs +58,310 | 203 of 735 |
| Range Fade | our side busier 15m | Rs +29,862 | Rs +58,615 | 342 of 735 |
| ORB Sweep | own OI not piling 15m + own volume surge 15m | Rs +27,971 | Rs +77,440 | 12 of 675 |
| Range Fade | own OI not piling 15m + our side busier 15m | Rs +27,922 | Rs +63,160 | 251 of 735 |
| ORB Sweep | other side adding 15m + own volume surge 15m | Rs +27,916 | Rs +76,460 | 9 of 675 |
| ORB Sweep | short covering with volume | Rs +27,481 | Rs +77,660 | 6 of 675 |
| ORB Sweep | short covering 15m + own volume surge 15m | Rs +27,481 | Rs +77,660 | 6 of 675 |
| ORB Sweep | own OI falling 15m + own volume surge 15m | Rs +27,426 | Rs +76,295 | 10 of 675 |
| ORB Sweep | short covering 15m | Rs +27,137 | Rs +64,074 | 124 of 675 |
| Range Fade | other side adding 15m | Rs +25,784 | Rs +55,112 | 339 of 735 |
| ORB Sweep | writers lean our way + own volume surge 15m | Rs +25,081 | Rs +77,170 | 4 of 675 |
| ORB Sweep | short covering 15m + our side busier 15m | Rs +25,061 | Rs +57,012 | 92 of 675 |
| ORB Sweep | long buildup with volume | Rs +24,046 | Rs +76,025 | 0 of 675 |
| ORB Sweep | PCR moving our way + own volume surge 15m | Rs +23,281 | Rs +75,915 | 5 of 675 |
| ORB Sweep | short covering 15m + own volume rising 5m | Rs +22,226 | Rs +65,184 | 85 of 675 |
| ORB | own OI falling 15m | Rs +31,055 | Rs +21,771 | 3389 of 4442 |
| ORB Sweep | writers lean our way + our side busier 15m | Rs +21,041 | Rs +65,655 | 25 of 675 |
| Range Fade | own volume rising 5m | Rs +20,839 | Rs +39,790 | 331 of 735 |
| ORB Sweep | PCR moving our way + our side busier 15m | Rs +20,491 | Rs +70,670 | 42 of 675 |
| ORB Sweep | own OI not piling 15m | Rs +19,630 | Rs +25,306 | 367 of 675 |
| ORB Sweep | own OI falling 15m + our side busier 15m | Rs +19,381 | Rs +52,892 | 126 of 675 |
| ORB Sweep | other side adding 15m + our side busier 15m | Rs +19,298 | Rs +61,572 | 143 of 675 |
| ORB Sweep | our side busier 15m | Rs +18,832 | Rs +51,814 | 269 of 675 |
| ORB Sweep | own OI falling 15m | Rs +18,777 | Rs +58,326 | 215 of 675 |
| ORB Sweep | writers lean our way | Rs +18,750 | Rs +57,675 | 86 of 675 |
| Range Fade | own OI not piling 15m | Rs +17,944 | Rs +42,398 | 428 of 735 |
| ORB Sweep | own OI not piling 15m + our side busier 15m | Rs +17,891 | Rs +46,628 | 197 of 675 |
| ORB Sweep | writers lean our way + own volume rising 5m | Rs +16,850 | Rs +62,375 | 46 of 675 |
| ORB Sweep | PCR moving our way | Rs +16,674 | Rs +68,800 | 96 of 675 |
| ORB Sweep | own OI not piling 15m + own volume rising 5m | Rs +16,270 | Rs +45,678 | 183 of 675 |
| ORB Sweep | other side adding 15m + own volume rising 5m | Rs +15,134 | Rs +69,838 | 133 of 675 |
| ORB Sweep | PCR moving our way + own volume rising 5m | Rs +14,284 | Rs +67,720 | 50 of 675 |
| ORB Sweep | other side adding 15m | Rs +12,957 | Rs +51,534 | 265 of 675 |
| ORB Fresh | other side adding 15m + own volume rising 5m | Rs +13,733 | Rs +12,730 | 440 of 1027 |
| ORB | other side adding 15m | Rs +12,673 | Rs +32,672 | 3413 of 4442 |
| ORB Sweep | own OI falling 15m + own volume rising 5m | Rs +12,181 | Rs +59,334 | 121 of 675 |
| ORB Fresh | own OI not piling 15m + our side busier 15m | Rs +11,193 | Rs +11,636 | 517 of 1027 |
| ORB Fresh | own volume surge 15m | Rs +21,673 | Rs +11,091 | 69 of 1027 |
| ORB Fresh | own OI not piling 15m + own volume surge 15m | Rs +20,558 | Rs +10,821 | 56 of 1027 |
| ORB Fresh | other side adding 15m + own volume surge 15m | Rs +22,738 | Rs +10,601 | 64 of 1027 |
| ORB Fresh | short covering with volume | Rs +21,323 | Rs +10,576 | 52 of 1027 |
| ORB Fresh | own OI falling 15m + own volume surge 15m | Rs +21,868 | Rs +10,576 | 53 of 1027 |
| ORB Fresh | short covering 15m + own volume surge 15m | Rs +21,323 | Rs +10,576 | 52 of 1027 |
| ORB Fresh | own volume rising 5m | Rs +16,835 | Rs +10,146 | 541 of 1027 |
| ORB Fresh | PCR moving our way + own volume surge 15m | Rs +22,333 | Rs +10,031 | 49 of 1027 |
| ORB Fresh | writers lean our way + own volume surge 15m | Rs +21,843 | Rs +8,286 | 46 of 1027 |
| ORB | own OI not piling 15m | Rs +7,805 | Rs +11,865 | 4128 of 4442 |
| ORB Fresh | PCR moving our way + own volume rising 5m | Rs +16,403 | Rs +7,661 | 440 of 1027 |
| ORB Fresh | writers lean our way + our side busier 15m | Rs +7,648 | Rs +13,245 | 493 of 1027 |
| ORB Fresh | own OI not piling 15m + own volume rising 5m | Rs +7,425 | Rs +7,706 | 451 of 1027 |
| ORB Fresh | own OI falling 15m + own volume rising 5m | Rs +8,450 | Rs +6,556 | 366 of 1027 |
| ORB | writers lean our way | Rs +7,078 | Rs +6,185 | 4254 of 4442 |
| ORB Fresh | own OI falling 15m + our side busier 15m | Rs +6,483 | Rs +6,176 | 451 of 1027 |
| ORB Fresh | PCR moving our way + our side busier 15m | Rs +6,148 | Rs +12,620 | 499 of 1027 |
| ORB Fresh | long buildup with volume | Rs +16,643 | Rs +6,136 | 16 of 1027 |
| ORB Sweep | own volume rising 5m | Rs +5,928 | Rs +41,186 | 308 of 675 |
| ORB | PCR moving our way | Rs +7,668 | Rs +5,685 | 4187 of 4442 |
| ORB Fresh | short covering 15m + our side busier 15m | Rs +8,588 | Rs +5,121 | 421 of 1027 |
| ORB Fresh | writers lean our way + own volume rising 5m | Rs +17,623 | Rs +4,551 | 447 of 1027 |
| ORB Fresh | short covering 15m + own volume rising 5m | Rs +6,735 | Rs +4,486 | 353 of 1027 |
| ORB Fresh | our side busier 15m | Rs +3,538 | Rs +13,685 | 598 of 1027 |
| ORB Fresh | short covering 15m | Rs +4,355 | Rs +3,016 | 603 of 1027 |
| ORB Fresh | other side adding 15m + our side busier 15m | Rs +2,948 | Rs +10,981 | 537 of 1027 |

111 of 145 arm x check results helped in both years.