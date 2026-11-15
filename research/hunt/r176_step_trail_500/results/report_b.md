# R176 Amendment B: Rs 1,000 a lot initial stop, step trail unchanged (tables)

The plain-English summary for both rules is at the top of report.md. 'S' below = the Rs 1,000 entry stop + the step trail; the entry-stop exit column is this rule's Rs 1,000 stop.

## Window, data and checks

- Window (Amendment A, Boss 15 Nov: "Test on only last year of data"): **20 Oct 2025 - 19 Oct 2026**, 247 sessions; early part 110, final 133, last 6 months 126. The lake ends on 19 Oct 2026.
- Parity, today's rules at 1 lot, 1 Apr - 13 Oct 2026: Liquidity 15+5 357 / Rs 11,155 (R172 357 / Rs 11,155); R72 Breadth Donchian 1200 / Rs 170,336 (R172 1200 / Rs 170,336), picker 651 / Rs 220,118 (R172 651 / Rs 220,118); R51 Donchian 20 + Supertrend 1668 / Rs 131,546 (R172 1668 / Rs 131,546), picker 692 / Rs 160,101 (R172 692 / Rs 160,101). The engine with the rule off priced every candidate exactly as R101.
- Signal source: the index for every arm (Liquidity: the index's liquidity levels; R72: Donchian 20 breaks on 4 of the 5 indices' 5-minute bars; R51: Donchian 20 on the index's 5-minute bars, kept when Supertrend (10, 3) agrees). Only the option's stop changes.
- Costs: dated Zerodha charges on the whole quantity; R17 slippage (from the 5-minute volume per position). Every stop of the rule is sold as a stop (x0.999 minus twice the half-spread; a gap fills at the minute's open), not at its level.
- Honesty: Apr - Oct 2026 has been read by many studies (R51 - R173); the early part (Oct 2025 - Mar 2026) lies inside the years R51's design used. One year is a short sample. The pickers were fitted on trades up to Mar 2026, so picker numbers in the early part are in-sample.
- Variants: A = today's rules; S = + Rs 500 step trail (per lot); B = A + R151 picker; B+S = picker + step trail (Liquidity has no picker).

## Gates (Amendment A; S vs A at the same lots)

BH counts m = 3988 tests (3970 earlier + 18 R176).

| arm @ lots | passed | twin p | BH q (m = 3,988) | q within R176 | failed gates |
|---|---|---|---|---|---|
| Liquidity 15+5 @ 1 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, year |
| Liquidity 15+5 @ 2 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| Liquidity 15+5 @ 3 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R72 Breadth Donchian @ 1 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R72 Breadth Donchian @ 2 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R72 Breadth Donchian @ 3 | **no** | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51 Donchian 20 + Supertrend @ 1 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51 Donchian 20 + Supertrend @ 2 | **no** | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51 Donchian 20 + Supertrend @ 3 | **no** | 1.0 | 1.0 | 1.0 | net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |

Twins changed (trades the rule closed earlier than A): LIQ@1 359 (twin mean Rs 216,480), LIQ@2 360 (twin mean Rs 469,047), LIQ@3 360 (twin mean Rs 719,940), R72@1 1816 (twin mean Rs 433,332), R72@2 1816 (twin mean Rs 974,587), R72@3 1816 (twin mean Rs 1,516,783), R51@1 2430 (twin mean Rs 480,812), R51@2 2430 (twin mean Rs 1,112,412), R51@3 2430 (twin mean Rs 1,753,469).

## 1 lot

### early (20 Oct 2025 - 31 Mar 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 42% | **Rs 113,873** | Rs 2,142 / -Rs 935 | 0 / 0 / 316 (6) | -Rs 5,535 | Dec 25 -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | S | 319 | 47% | **Rs 36,139** | Rs 1,181 / -Rs 847 | 85 / 85 / 149 (0) | -Rs 5,355 | Mar 26 -Rs 7,544 | -Rs 19,385 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 128,541** | Rs 1,792 / -Rs 1,341 | 0 / 0 / 1056 (166) | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S | 1112 | 50% | **-Rs 33,053** | Rs 949 / -Rs 995 | 408 / 483 / 221 (23) | -Rs 12,611 | Dec 25 -Rs 69,609 | -Rs 73,591 |
| R72 Breadth Donchian | B | 452 | 55% | **Rs 200,162** | Rs 2,153 / -Rs 1,673 | 0 / 0 / 452 (70) | -Rs 25,837 | Dec 25 -Rs 1,721 | -Rs 34,940 |
| R72 Breadth Donchian | B+S | 460 | 57% | **Rs 65,871** | Rs 1,069 / -Rs 1,082 | 174 / 248 / 38 (2) | -Rs 9,963 | Mar 26 -Rs 7,707 | -Rs 27,564 |
| R51 Donchian 20 + Supertrend | A | 1370 | 45% | **Rs 86,280** | Rs 1,744 / -Rs 1,326 | 0 / 0 / 1370 (222) | -Rs 28,942 | Dec 25 -Rs 51,699 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S | 1431 | 50% | **-Rs 32,135** | Rs 958 / -Rs 993 | 535 / 616 / 280 (28) | -Rs 14,104 | Dec 25 -Rs 58,264 | -Rs 82,450 |
| R51 Donchian 20 + Supertrend | B | 691 | 51% | **Rs 156,237** | Rs 1,905 / -Rs 1,537 | 0 / 0 / 691 (112) | -Rs 24,757 | Dec 25 -Rs 904 | -Rs 29,931 |
| R51 Donchian 20 + Supertrend | B+S | 731 | 53% | **Rs 52,934** | Rs 1,058 / -Rs 1,061 | 279 / 346 / 106 (9) | -Rs 12,215 | Dec 25 -Rs 14,906 | -Rs 39,953 |
| All three arms | A | 2742 | 45% | **Rs 328,694** | Rs 1,805 / -Rs 1,284 | 0 / 0 / 2742 (394) | -Rs 51,650 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three arms | S | 2862 | 49% | **-Rs 29,049** | Rs 978 / -Rs 977 | 1028 / 1184 / 650 (51) | -Rs 26,543 | Dec 25 -Rs 130,878 | -Rs 149,330 |
| All three arms | B | 1459 | 51% | **Rs 470,271** | Rs 2,032 / -Rs 1,423 | 0 / 0 / 1459 (188) | -Rs 43,996 | Dec 25 -Rs 7,604 | -Rs 60,566 |
| All three arms | B+S | 1510 | 53% | **Rs 154,944** | Rs 1,085 / -Rs 1,016 | 538 / 679 / 293 (11) | -Rs 21,878 | Mar 26 -Rs 24,771 | -Rs 63,684 |

### final (1 Apr - 13 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 37% | **Rs 11,155** | Rs 1,762 / -Rs 997 | 0 / 0 / 357 (12) | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S | 358 | 43% | **-Rs 32,616** | Rs 891 / -Rs 824 | 100 / 92 / 166 (0) | -Rs 6,018 | Apr 26 -Rs 22,266 | -Rs 47,236 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 170,336** | Rs 1,748 / -Rs 1,217 | 0 / 0 / 1200 (173) | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S | 1242 | 47% | **Rs 8,611** | Rs 1,155 / -Rs 999 | 504 / 470 / 268 (10) | -Rs 13,754 | Apr 26 -Rs 35,083 | -Rs 66,263 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 170,735** | Rs 1,920 / -Rs 1,299 | 0 / 0 / 602 (78) | -Rs 21,301 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S | 625 | 49% | **Rs 63,650** | Rs 1,271 / -Rs 1,020 | 255 / 262 / 108 (5) | -Rs 14,843 | May 26 -Rs 16,448 | -Rs 31,702 |
| R51 Donchian 20 + Supertrend | A | 1668 | 45% | **Rs 131,546** | Rs 1,648 / -Rs 1,197 | 0 / 0 / 1668 (218) | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S | 1724 | 47% | **-Rs 21,897** | Rs 1,059 / -Rs 969 | 661 / 650 / 413 (13) | -Rs 17,041 | Jun 26 -Rs 35,397 | -Rs 68,497 |
| R51 Donchian 20 + Supertrend | B | 652 | 50% | **Rs 143,945** | Rs 1,733 / -Rs 1,320 | 0 / 0 / 652 (85) | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S | 697 | 50% | **-Rs 19,749** | Rs 992 / -Rs 1,028 | 275 / 287 / 135 (8) | -Rs 12,386 | Apr 26 -Rs 36,545 | -Rs 53,197 |
| All three arms | A | 3225 | 44% | **Rs 313,037** | Rs 1,697 / -Rs 1,179 | 0 / 0 / 3225 (403) | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three arms | S | 3324 | 47% | **-Rs 45,902** | Rs 1,079 / -Rs 964 | 1265 / 1212 / 847 (23) | -Rs 30,843 | Apr 26 -Rs 81,483 | -Rs 161,332 |
| All three arms | B | 1611 | 47% | **Rs 325,835** | Rs 1,811 / -Rs 1,228 | 0 / 0 / 1611 (175) | -Rs 39,870 | Apr 26 -Rs 29,430 | -Rs 68,921 |
| All three arms | B+S | 1680 | 48% | **Rs 11,284** | Rs 1,079 / -Rs 977 | 630 / 641 / 409 (13) | -Rs 27,277 | Apr 26 -Rs 70,074 | -Rs 103,409 |

### last 6 months (20 Apr - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | 0 / 0 / 346 (13) | -Rs 11,405 | Oct 26 -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | S | 347 | 44% | **-Rs 25,959** | Rs 862 / -Rs 814 | 93 / 93 / 161 (0) | -Rs 6,659 | May 26 -Rs 11,959 | -Rs 34,607 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | 0 / 0 / 1116 (165) | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S | 1158 | 48% | **Rs 32,623** | Rs 1,142 / -Rs 986 | 452 / 445 / 261 (11) | -Rs 13,727 | Jun 26 -Rs 23,823 | -Rs 49,955 |
| R72 Breadth Donchian | B | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | 0 / 0 / 537 (68) | -Rs 17,394 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S | 552 | 50% | **Rs 73,315** | Rs 1,281 / -Rs 999 | 216 / 234 / 102 (5) | -Rs 8,958 | May 26 -Rs 16,448 | -Rs 27,067 |
| R51 Donchian 20 + Supertrend | A | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | 0 / 0 / 1584 (214) | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S | 1645 | 48% | **Rs 13,940** | Rs 1,052 / -Rs 958 | 611 / 630 / 404 (15) | -Rs 13,130 | Jun 26 -Rs 35,397 | -Rs 68,497 |
| R51 Donchian 20 + Supertrend | B | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | 0 / 0 / 598 (79) | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S | 645 | 50% | **Rs 7,953** | Rs 1,004 / -Rs 995 | 243 / 270 / 132 (9) | -Rs 10,952 | Sep 26 -Rs 30,097 | -Rs 47,003 |
| All three arms | A | 3046 | 45% | **Rs 366,780** | Rs 1,664 / -Rs 1,141 | 0 / 0 / 3046 (392) | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three arms | S | 3150 | 47% | **Rs 20,604** | Rs 1,066 / -Rs 951 | 1156 / 1168 / 826 (26) | -Rs 33,517 | Jun 26 -Rs 67,524 | -Rs 119,677 |
| All three arms | B | 1481 | 48% | **Rs 357,730** | Rs 1,755 / -Rs 1,145 | 0 / 0 / 1481 (160) | -Rs 39,870 | Jun 26 -Rs 21,248 | -Rs 59,893 |
| All three arms | B+S | 1544 | 49% | **Rs 55,309** | Rs 1,076 / -Rs 952 | 552 / 597 / 395 (14) | -Rs 20,617 | May 26 -Rs 18,555 | -Rs 82,608 |

### whole year (20 Oct 2025 - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 39% | **Rs 117,923** | Rs 1,935 / -Rs 968 | 0 / 0 / 688 (20) | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S | 692 | 45% | **-Rs 3,176** | Rs 1,024 / -Rs 835 | 190 / 182 / 320 (0) | -Rs 6,659 | Apr 26 -Rs 22,266 | -Rs 65,836 |
| R72 Breadth Donchian | A | 2281 | 46% | **Rs 298,253** | Rs 1,763 / -Rs 1,279 | 0 / 0 / 2281 (345) | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S | 2383 | 48% | **-Rs 31,372** | Rs 1,049 / -Rs 1,000 | 923 / 967 / 493 (34) | -Rs 13,754 | Dec 25 -Rs 69,609 | -Rs 114,080 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 371,717** | Rs 2,019 / -Rs 1,449 | 0 / 0 / 1062 (150) | -Rs 25,837 | May 26 -Rs 20,632 | -Rs 34,940 |
| R72 Breadth Donchian | B+S | 1093 | 52% | **Rs 126,381** | Rs 1,176 / -Rs 1,044 | 434 / 513 / 146 (7) | -Rs 14,843 | May 26 -Rs 16,448 | -Rs 59,266 |
| R51 Donchian 20 + Supertrend | A | 3084 | 45% | **Rs 221,238** | Rs 1,692 / -Rs 1,259 | 0 / 0 / 3084 (452) | -Rs 28,942 | Jun 26 -Rs 54,587 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S | 3206 | 48% | **-Rs 60,516** | Rs 1,009 / -Rs 983 | 1217 / 1292 / 697 (43) | -Rs 17,041 | Dec 25 -Rs 58,264 | -Rs 115,806 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 293,395** | Rs 1,814 / -Rs 1,438 | 0 / 0 / 1361 (204) | -Rs 24,757 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S | 1448 | 51% | **Rs 24,379** | Rs 1,026 / -Rs 1,044 | 566 / 640 / 242 (18) | -Rs 12,386 | Apr 26 -Rs 36,545 | -Rs 79,834 |
| All three arms | A | 6053 | 45% | **Rs 637,414** | Rs 1,744 / -Rs 1,230 | 0 / 0 / 6053 (817) | -Rs 53,710 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three arms | S | 6281 | 48% | **-Rs 95,063** | Rs 1,026 / -Rs 972 | 2330 / 2441 / 1510 (77) | -Rs 33,517 | Dec 25 -Rs 130,878 | -Rs 252,185 |
| All three arms | B | 3111 | 49% | **Rs 783,034** | Rs 1,910 / -Rs 1,318 | 0 / 0 / 3111 (374) | -Rs 43,996 | Apr 26 -Rs 29,430 | -Rs 76,847 |
| All three arms | B+S | 3233 | 50% | **Rs 147,584** | Rs 1,078 / -Rs 994 | 1190 / 1335 / 708 (25) | -Rs 27,277 | Apr 26 -Rs 70,074 | -Rs 167,093 |

### Month by month, the whole year (1 lot)

| arm | variant | Oct 25 | Nov 25 | Dec 25 | Jan 26 | Feb 26 | Mar 26 | Apr 26 | May 26 | Jun 26 | Jul 26 | Aug 26 | Sep 26 | Oct 26 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 611 | Rs 2,638 | -Rs 4,980 | Rs 45,771 | Rs 43,708 | Rs 27,346 | -Rs 37,821 | -Rs 8,832 | -Rs 949 | Rs 33,022 | -Rs 1,605 | Rs 35,620 | -Rs 15,384 | Rs 117,923 |
| Liquidity 15+5 | S | Rs 1,603 | Rs 5,541 | -Rs 3,005 | Rs 8,132 | Rs 31,412 | -Rs 7,544 | -Rs 22,266 | -Rs 11,959 | -Rs 8,304 | Rs 8,074 | -Rs 2,480 | Rs 8,054 | -Rs 10,434 | -Rs 3,176 |
| R72 Breadth Donchian | A | Rs 22,318 | Rs 8,007 | -Rs 72,605 | Rs 57,857 | Rs 27,329 | Rs 85,635 | -Rs 15,245 | Rs 39,929 | -Rs 40,908 | Rs 64,441 | Rs 21,113 | Rs 29,606 | Rs 70,777 | Rs 298,253 |
| R72 Breadth Donchian | S | Rs 7,630 | Rs 3,846 | -Rs 69,609 | Rs 43,148 | Rs 1,698 | -Rs 19,766 | -Rs 35,083 | -Rs 3,975 | -Rs 23,823 | Rs 48,916 | Rs 5,598 | Rs 5,269 | Rs 4,779 | -Rs 31,372 |
| R72 Breadth Donchian | B | Rs 18,257 | Rs 12,398 | -Rs 1,721 | Rs 62,216 | Rs 29,854 | Rs 79,157 | Rs 690 | -Rs 20,632 | Rs 2,678 | Rs 91,938 | -Rs 2,750 | Rs 38,435 | Rs 61,196 | Rs 371,717 |
| R72 Breadth Donchian | B+S | Rs 6,498 | Rs 16,669 | -Rs 2,244 | Rs 40,938 | Rs 11,718 | -Rs 7,707 | -Rs 11,263 | -Rs 16,448 | Rs 10,941 | Rs 60,606 | -Rs 2,050 | Rs 7,846 | Rs 10,878 | Rs 126,381 |
| R51 Donchian 20 + Supertrend | A | Rs 1,267 | Rs 16,638 | -Rs 51,699 | Rs 75,958 | -Rs 28,345 | Rs 72,461 | Rs 16,172 | Rs 29,306 | -Rs 54,587 | Rs 68,207 | Rs 18,751 | -Rs 2,459 | Rs 59,567 | Rs 221,238 |
| R51 Donchian 20 + Supertrend | S | Rs 8,323 | -Rs 8,246 | -Rs 58,264 | Rs 65,738 | -Rs 28,395 | -Rs 11,291 | -Rs 24,134 | Rs 13,228 | -Rs 35,397 | Rs 48,719 | -Rs 3,952 | -Rs 27,107 | Rs 263 | -Rs 60,516 |
| R51 Donchian 20 + Supertrend | B | Rs 372 | Rs 10,322 | -Rs 904 | Rs 69,877 | Rs 41,666 | Rs 34,904 | Rs 7,701 | Rs 18,363 | -Rs 22,978 | Rs 64,996 | Rs 18,152 | Rs 3,509 | Rs 47,415 | Rs 293,395 |
| R51 Donchian 20 + Supertrend | B+S | Rs 6,441 | -Rs 4,001 | -Rs 14,906 | Rs 54,670 | Rs 20,250 | -Rs 9,521 | -Rs 36,545 | Rs 9,852 | Rs 1,661 | Rs 34,450 | -Rs 911 | -Rs 30,097 | -Rs 6,964 | Rs 24,379 |
| All three arms | A | Rs 22,974 | Rs 27,283 | -Rs 129,283 | Rs 179,585 | Rs 42,693 | Rs 185,442 | -Rs 36,894 | Rs 60,403 | -Rs 96,444 | Rs 165,669 | Rs 38,260 | Rs 62,766 | Rs 114,960 | Rs 637,414 |
| All three arms | S | Rs 17,556 | Rs 1,141 | -Rs 130,878 | Rs 117,018 | Rs 4,715 | -Rs 38,600 | -Rs 81,483 | -Rs 2,707 | -Rs 67,524 | Rs 105,709 | -Rs 834 | -Rs 13,783 | -Rs 5,392 | -Rs 95,063 |
| All three arms | B | Rs 18,018 | Rs 25,358 | -Rs 7,604 | Rs 177,864 | Rs 115,229 | Rs 141,407 | -Rs 29,430 | -Rs 11,101 | -Rs 21,248 | Rs 189,955 | Rs 13,797 | Rs 77,564 | Rs 93,227 | Rs 783,034 |
| All three arms | B+S | Rs 14,542 | Rs 18,208 | -Rs 20,155 | Rs 103,740 | Rs 63,380 | -Rs 24,771 | -Rs 70,074 | -Rs 18,555 | Rs 4,298 | Rs 103,130 | -Rs 5,441 | -Rs 14,197 | -Rs 6,520 | Rs 147,584 |

Share of trades where the Rs 500 entry stop was tighter than -15% (so it replaced it): Liquidity 15+5 100%, R72 Breadth Donchian 95%, R51 Donchian 20 + Supertrend 95%.

## 2 lots

### early (20 Oct 2025 - 31 Mar 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 43% | **Rs 242,161** | Rs 4,236 / -Rs 1,855 | 0 / 0 / 316 (6) | -Rs 10,759 | Dec 25 -Rs 7,052 | -Rs 31,338 |
| Liquidity 15+5 | S | 319 | 48% | **Rs 86,883** | Rs 2,362 / -Rs 1,678 | 85 / 85 / 149 (0) | -Rs 10,426 | Mar 26 -Rs 13,029 | -Rs 34,709 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 306,013** | Rs 3,602 / -Rs 2,655 | 0 / 0 / 1056 (166) | -Rs 51,084 | Dec 25 -Rs 135,116 | -Rs 164,515 |
| R72 Breadth Donchian | S | 1112 | 50% | **-Rs 14,607** | Rs 1,941 / -Rs 1,946 | 408 / 483 / 221 (23) | -Rs 24,781 | Dec 25 -Rs 128,993 | -Rs 137,510 |
| R72 Breadth Donchian | B | 452 | 56% | **Rs 421,187** | Rs 4,317 / -Rs 3,334 | 0 / 0 / 452 (70) | -Rs 51,084 | Dec 25 -Rs 1,952 | -Rs 67,680 |
| R72 Breadth Donchian | B+S | 460 | 57% | **Rs 153,009** | Rs 2,184 / -Rs 2,117 | 174 / 248 / 38 (2) | -Rs 19,519 | Mar 26 -Rs 7,753 | -Rs 48,589 |
| R51 Donchian 20 + Supertrend | A | 1370 | 46% | **Rs 235,755** | Rs 3,494 / -Rs 2,632 | 0 / 0 / 1370 (222) | -Rs 57,260 | Dec 25 -Rs 90,502 | -Rs 179,011 |
| R51 Donchian 20 + Supertrend | S | 1431 | 50% | **Rs 2,072** | Rs 1,957 / -Rs 1,946 | 535 / 616 / 280 (28) | -Rs 27,689 | Dec 25 -Rs 103,214 | -Rs 146,076 |
| R51 Donchian 20 + Supertrend | B | 691 | 52% | **Rs 344,237** | Rs 3,834 / -Rs 3,047 | 0 / 0 / 691 (112) | -Rs 49,156 | Dec 25 Rs 3,585 | -Rs 58,519 |
| R51 Donchian 20 + Supertrend | B+S | 731 | 54% | **Rs 139,639** | Rs 2,157 / -Rs 2,082 | 279 / 346 / 106 (9) | -Rs 24,171 | Dec 25 -Rs 24,143 | -Rs 70,272 |
| All three arms | A | 2742 | 46% | **Rs 783,929** | Rs 3,617 / -Rs 2,546 | 0 / 0 / 2742 (394) | -Rs 101,804 | Dec 25 -Rs 232,669 | -Rs 295,760 |
| All three arms | S | 2862 | 50% | **Rs 74,348** | Rs 1,995 / -Rs 1,915 | 1028 / 1184 / 650 (51) | -Rs 51,163 | Dec 25 -Rs 235,316 | -Rs 261,605 |
| All three arms | B | 1459 | 51% | **Rs 1,007,585** | Rs 4,071 / -Rs 2,827 | 0 / 0 / 1459 (188) | -Rs 87,161 | Dec 25 -Rs 5,419 | -Rs 116,132 |
| All three arms | B+S | 1510 | 54% | **Rs 379,531** | Rs 2,205 / -Rs 1,997 | 538 / 679 / 293 (11) | -Rs 42,735 | Mar 26 -Rs 32,616 | -Rs 112,542 |

### final (1 Apr - 13 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 38% | **Rs 38,639** | Rs 3,467 / -Rs 1,983 | 0 / 0 / 357 (12) | -Rs 22,450 | Apr 26 -Rs 73,218 | -Rs 117,966 |
| Liquidity 15+5 | S | 358 | 43% | **-Rs 47,510** | Rs 1,806 / -Rs 1,613 | 101 / 92 / 165 (0) | -Rs 11,665 | Apr 26 -Rs 40,703 | -Rs 86,956 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 396,251** | Rs 3,511 / -Rs 2,406 | 0 / 0 / 1200 (173) | -Rs 44,447 | Jun 26 -Rs 72,639 | -Rs 111,087 |
| R72 Breadth Donchian | S | 1242 | 47% | **Rs 74,581** | Rs 2,341 / -Rs 1,964 | 504 / 470 / 268 (10) | -Rs 26,717 | Apr 26 -Rs 60,646 | -Rs 103,253 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 369,298** | Rs 3,873 / -Rs 2,561 | 0 / 0 / 602 (78) | -Rs 41,948 | May 26 -Rs 34,292 | -Rs 60,657 |
| R72 Breadth Donchian | B+S | 625 | 49% | **Rs 156,105** | Rs 2,588 / -Rs 1,993 | 255 / 262 / 108 (5) | -Rs 29,026 | May 26 -Rs 25,471 | -Rs 56,276 |
| R51 Donchian 20 + Supertrend | A | 1668 | 46% | **Rs 340,059** | Rs 3,294 / -Rs 2,376 | 0 / 0 / 1668 (218) | -Rs 42,690 | Jun 26 -Rs 96,531 | -Rs 145,608 |
| R51 Donchian 20 + Supertrend | S | 1724 | 48% | **Rs 34,555** | Rs 2,145 / -Rs 1,907 | 661 / 650 / 413 (13) | -Rs 33,352 | Jun 26 -Rs 57,962 | -Rs 115,744 |
| R51 Donchian 20 + Supertrend | B | 652 | 51% | **Rs 317,879** | Rs 3,471 / -Rs 2,627 | 0 / 0 / 652 (85) | -Rs 28,984 | Jun 26 -Rs 42,877 | -Rs 73,811 |
| R51 Donchian 20 + Supertrend | B+S | 697 | 50% | **-Rs 7,452** | Rs 2,007 / -Rs 2,035 | 275 / 287 / 135 (8) | -Rs 24,205 | Apr 26 -Rs 67,594 | -Rs 102,101 |
| All three arms | A | 3225 | 45% | **Rs 774,949** | Rs 3,393 / -Rs 2,338 | 0 / 0 / 3225 (403) | -Rs 106,160 | Jun 26 -Rs 168,769 | -Rs 233,756 |
| All three arms | S | 3324 | 47% | **Rs 61,627** | Rs 2,185 / -Rs 1,895 | 1266 / 1212 / 846 (23) | -Rs 60,116 | Apr 26 -Rs 138,087 | -Rs 254,808 |
| All three arms | B | 1611 | 48% | **Rs 725,815** | Rs 3,626 / -Rs 2,435 | 0 / 0 / 1611 (175) | -Rs 78,828 | Apr 26 -Rs 45,679 | -Rs 122,299 |
| All three arms | B+S | 1680 | 48% | **Rs 101,143** | Rs 2,188 / -Rs 1,921 | 631 / 641 / 408 (13) | -Rs 53,279 | Apr 26 -Rs 124,919 | -Rs 187,644 |

### last 6 months (20 Apr - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 39% | **Rs 72,613** | Rs 3,442 / -Rs 1,858 | 0 / 0 / 346 (13) | -Rs 22,450 | Oct 26 -Rs 28,488 | -Rs 73,050 |
| Liquidity 15+5 | S | 347 | 45% | **-Rs 36,090** | Rs 1,748 / -Rs 1,599 | 93 / 93 / 161 (0) | -Rs 13,012 | May 26 -Rs 21,736 | -Rs 64,366 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 431,603** | Rs 3,448 / -Rs 2,333 | 0 / 0 / 1116 (165) | -Rs 44,447 | Jun 26 -Rs 72,639 | -Rs 111,087 |
| R72 Breadth Donchian | S | 1158 | 48% | **Rs 118,700** | Rs 2,313 / -Rs 1,939 | 452 / 445 / 261 (11) | -Rs 27,007 | Jun 26 -Rs 38,327 | -Rs 81,813 |
| R72 Breadth Donchian | B | 537 | 51% | **Rs 388,024** | Rs 3,757 / -Rs 2,392 | 0 / 0 / 537 (68) | -Rs 33,927 | May 26 -Rs 34,292 | -Rs 60,657 |
| R72 Breadth Donchian | B+S | 552 | 50% | **Rs 172,072** | Rs 2,608 / -Rs 1,952 | 216 / 234 / 102 (5) | -Rs 17,444 | May 26 -Rs 25,471 | -Rs 50,477 |
| R51 Donchian 20 + Supertrend | A | 1584 | 46% | **Rs 369,927** | Rs 3,216 / -Rs 2,316 | 0 / 0 / 1584 (214) | -Rs 42,690 | Jun 26 -Rs 96,531 | -Rs 145,608 |
| R51 Donchian 20 + Supertrend | S | 1645 | 49% | **Rs 102,626** | Rs 2,130 / -Rs 1,886 | 611 / 630 / 404 (15) | -Rs 25,647 | Jun 26 -Rs 57,962 | -Rs 115,744 |
| R51 Donchian 20 + Supertrend | B | 598 | 52% | **Rs 322,970** | Rs 3,327 / -Rs 2,460 | 0 / 0 / 598 (79) | -Rs 28,984 | Jun 26 -Rs 42,877 | -Rs 73,811 |
| R51 Donchian 20 + Supertrend | B+S | 645 | 51% | **Rs 45,584** | Rs 2,031 / -Rs 1,970 | 243 / 270 / 132 (9) | -Rs 21,131 | Sep 26 -Rs 53,471 | -Rs 83,574 |
| All three arms | A | 3046 | 46% | **Rs 874,143** | Rs 3,326 / -Rs 2,263 | 0 / 0 / 3046 (392) | -Rs 106,160 | Jun 26 -Rs 168,769 | -Rs 233,756 |
| All three arms | S | 3150 | 48% | **Rs 185,236** | Rs 2,158 / -Rs 1,872 | 1156 / 1168 / 826 (26) | -Rs 65,666 | Jun 26 -Rs 110,569 | -Rs 200,729 |
| All three arms | B | 1481 | 48% | **Rs 783,606** | Rs 3,512 / -Rs 2,270 | 0 / 0 / 1481 (160) | -Rs 78,828 | Jun 26 -Rs 33,503 | -Rs 101,301 |
| All three arms | B+S | 1544 | 49% | **Rs 181,566** | Rs 2,182 / -Rs 1,873 | 552 / 597 / 395 (14) | -Rs 39,860 | May 26 -Rs 22,335 | -Rs 147,341 |

### whole year (20 Oct 2025 - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 40% | **Rs 267,232** | Rs 3,817 / -Rs 1,922 | 0 / 0 / 688 (20) | -Rs 22,450 | Apr 26 -Rs 73,218 | -Rs 117,966 |
| Liquidity 15+5 | S | 692 | 45% | **Rs 26,606** | Rs 2,061 / -Rs 1,642 | 191 / 182 / 319 (0) | -Rs 13,012 | Apr 26 -Rs 40,703 | -Rs 119,670 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 702,143** | Rs 3,542 / -Rs 2,530 | 0 / 0 / 2281 (345) | -Rs 51,084 | Dec 25 -Rs 135,116 | -Rs 164,515 |
| R72 Breadth Donchian | S | 2383 | 48% | **Rs 47,439** | Rs 2,135 / -Rs 1,963 | 923 / 967 / 493 (34) | -Rs 27,007 | Dec 25 -Rs 128,993 | -Rs 174,608 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 792,484** | Rs 4,062 / -Rs 2,870 | 0 / 0 / 1062 (150) | -Rs 51,084 | May 26 -Rs 34,292 | -Rs 67,680 |
| R72 Breadth Donchian | B+S | 1093 | 52% | **Rs 303,210** | Rs 2,398 / -Rs 2,042 | 434 / 513 / 146 (7) | -Rs 29,026 | May 26 -Rs 25,471 | -Rs 104,864 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 584,762** | Rs 3,386 / -Rs 2,499 | 0 / 0 / 3084 (452) | -Rs 57,260 | Jun 26 -Rs 96,531 | -Rs 179,011 |
| R51 Donchian 20 + Supertrend | S | 3206 | 49% | **Rs 26,008** | Rs 2,052 / -Rs 1,931 | 1217 / 1292 / 697 (43) | -Rs 33,352 | Dec 25 -Rs 103,214 | -Rs 200,205 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 649,385** | Rs 3,642 / -Rs 2,855 | 0 / 0 / 1361 (204) | -Rs 49,156 | Jun 26 -Rs 42,877 | -Rs 73,811 |
| R51 Donchian 20 + Supertrend | B+S | 1448 | 52% | **Rs 115,522** | Rs 2,085 / -Rs 2,057 | 566 / 640 / 242 (18) | -Rs 24,205 | Apr 26 -Rs 67,594 | -Rs 149,053 |
| All three arms | A | 6053 | 45% | **Rs 1,554,136** | Rs 3,490 / -Rs 2,439 | 0 / 0 / 6053 (817) | -Rs 106,160 | Dec 25 -Rs 232,669 | -Rs 295,760 |
| All three arms | S | 6281 | 48% | **Rs 100,053** | Rs 2,084 / -Rs 1,909 | 2331 / 2441 / 1509 (77) | -Rs 65,666 | Dec 25 -Rs 235,316 | -Rs 392,456 |
| All three arms | B | 3111 | 49% | **Rs 1,709,101** | Rs 3,826 / -Rs 2,618 | 0 / 0 / 3111 (374) | -Rs 87,161 | Apr 26 -Rs 45,679 | -Rs 137,066 |
| All three arms | B+S | 3233 | 50% | **Rs 445,337** | Rs 2,190 / -Rs 1,954 | 1191 / 1335 / 707 (25) | -Rs 53,279 | Apr 26 -Rs 124,919 | -Rs 300,186 |

### Month by month, the whole year (2 lots)

| arm | variant | Oct 25 | Nov 25 | Dec 25 | Jan 26 | Feb 26 | Mar 26 | Apr 26 | May 26 | Jun 26 | Jul 26 | Aug 26 | Sep 26 | Oct 26 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 345 | Rs 7,446 | -Rs 7,052 | Rs 94,856 | Rs 90,547 | Rs 56,708 | -Rs 73,218 | -Rs 15,458 | Rs 402 | Rs 69,093 | -Rs 799 | Rs 73,539 | -Rs 28,488 | Rs 267,232 |
| Liquidity 15+5 | S | Rs 4,090 | Rs 13,252 | -Rs 3,109 | Rs 19,620 | Rs 66,060 | -Rs 13,029 | -Rs 40,703 | -Rs 21,736 | -Rs 14,279 | Rs 19,208 | -Rs 2,560 | Rs 18,403 | -Rs 18,610 | Rs 26,606 |
| R72 Breadth Donchian | A | Rs 47,974 | Rs 24,516 | -Rs 135,116 | Rs 125,335 | Rs 62,710 | Rs 180,594 | -Rs 21,319 | Rs 89,496 | -Rs 72,639 | Rs 137,605 | Rs 48,822 | Rs 67,730 | Rs 146,436 | Rs 702,143 |
| R72 Breadth Donchian | S | Rs 18,692 | Rs 16,467 | -Rs 128,993 | Rs 96,595 | Rs 12,262 | -Rs 29,630 | -Rs 60,646 | Rs 2,201 | -Rs 38,327 | Rs 106,992 | Rs 17,896 | Rs 19,297 | Rs 14,635 | Rs 47,439 |
| R72 Breadth Donchian | B | Rs 38,389 | Rs 28,901 | -Rs 1,952 | Rs 128,065 | Rs 61,678 | Rs 166,105 | Rs 6,924 | -Rs 34,292 | Rs 8,972 | Rs 187,816 | -Rs 3,419 | Rs 80,922 | Rs 124,372 | Rs 792,484 |
| R72 Breadth Donchian | B+S | Rs 14,965 | Rs 37,524 | -Rs 2,980 | Rs 85,669 | Rs 25,583 | -Rs 7,753 | -Rs 16,622 | -Rs 25,471 | Rs 25,409 | Rs 125,282 | -Rs 2,030 | Rs 19,849 | Rs 23,785 | Rs 303,210 |
| R51 Donchian 20 + Supertrend | A | Rs 7,117 | Rs 44,467 | -Rs 90,502 | Rs 163,865 | -Rs 44,481 | Rs 155,290 | Rs 43,598 | Rs 70,546 | -Rs 96,531 | Rs 149,137 | Rs 49,428 | Rs 7,005 | Rs 125,823 | Rs 584,762 |
| R51 Donchian 20 + Supertrend | S | Rs 21,248 | -Rs 4,914 | -Rs 103,214 | Rs 143,964 | -Rs 43,654 | -Rs 11,358 | -Rs 36,738 | Rs 37,875 | -Rs 57,962 | Rs 110,495 | Rs 4,593 | -Rs 41,764 | Rs 7,437 | Rs 26,008 |
| R51 Donchian 20 + Supertrend | B | Rs 3,925 | Rs 28,422 | Rs 3,585 | Rs 145,322 | Rs 86,393 | Rs 76,591 | Rs 20,615 | Rs 41,474 | -Rs 42,877 | Rs 134,014 | Rs 41,347 | Rs 13,109 | Rs 97,467 | Rs 649,385 |
| R51 Donchian 20 + Supertrend | B+S | Rs 16,069 | Rs 401 | -Rs 24,143 | Rs 115,452 | Rs 43,695 | -Rs 11,834 | -Rs 67,594 | Rs 24,872 | Rs 6,595 | Rs 72,980 | Rs 3,585 | -Rs 53,471 | -Rs 11,084 | Rs 115,522 |
| All three arms | A | Rs 54,746 | Rs 76,429 | -Rs 232,669 | Rs 384,057 | Rs 108,775 | Rs 392,592 | -Rs 50,940 | Rs 144,584 | -Rs 168,769 | Rs 355,834 | Rs 97,451 | Rs 148,274 | Rs 243,771 | Rs 1,554,136 |
| All three arms | S | Rs 44,031 | Rs 24,804 | -Rs 235,316 | Rs 260,179 | Rs 34,668 | -Rs 54,017 | -Rs 138,087 | Rs 18,339 | -Rs 110,569 | Rs 236,695 | Rs 19,930 | -Rs 4,065 | Rs 3,462 | Rs 100,053 |
| All three arms | B | Rs 41,969 | Rs 64,769 | -Rs 5,419 | Rs 368,243 | Rs 238,618 | Rs 299,404 | -Rs 45,679 | -Rs 8,275 | -Rs 33,503 | Rs 390,922 | Rs 37,129 | Rs 167,571 | Rs 193,352 | Rs 1,709,101 |
| All three arms | B+S | Rs 35,123 | Rs 51,177 | -Rs 30,232 | Rs 220,740 | Rs 135,339 | -Rs 32,616 | -Rs 124,919 | -Rs 22,335 | Rs 17,725 | Rs 217,470 | -Rs 1,005 | -Rs 15,219 | -Rs 5,910 | Rs 445,337 |

Share of trades where the Rs 500 entry stop was tighter than -15% (so it replaced it): Liquidity 15+5 100%, R72 Breadth Donchian 95%, R51 Donchian 20 + Supertrend 95%.

## 3 lots

### early (20 Oct 2025 - 31 Mar 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 44% | **Rs 370,179** | Rs 6,282 / -Rs 2,791 | 0 / 0 / 316 (6) | -Rs 15,991 | Dec 25 -Rs 9,170 | -Rs 45,836 |
| Liquidity 15+5 | S | 319 | 49% | **Rs 137,289** | Rs 3,541 / -Rs 2,510 | 85 / 85 / 149 (0) | -Rs 15,498 | Mar 26 -Rs 18,590 | -Rs 50,092 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 482,662** | Rs 5,403 / -Rs 3,974 | 0 / 0 / 1056 (166) | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | S | 1112 | 50% | **Rs 3,346** | Rs 2,924 / -Rs 2,908 | 408 / 483 / 221 (23) | -Rs 36,935 | Dec 25 -Rs 188,381 | -Rs 201,440 |
| R72 Breadth Donchian | B | 452 | 56% | **Rs 641,854** | Rs 6,447 / -Rs 5,029 | 0 / 0 / 452 (70) | -Rs 76,355 | Dec 25 -Rs 2,168 | -Rs 100,468 |
| R72 Breadth Donchian | B+S | 460 | 57% | **Rs 239,968** | Rs 3,286 / -Rs 3,169 | 174 / 248 / 38 (2) | -Rs 29,066 | Mar 26 -Rs 7,886 | -Rs 69,711 |
| R51 Donchian 20 + Supertrend | A | 1370 | 46% | **Rs 384,217** | Rs 5,256 / -Rs 3,931 | 0 / 0 / 1370 (222) | -Rs 85,577 | Dec 25 -Rs 129,380 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | S | 1431 | 50% | **Rs 35,226** | Rs 2,950 / -Rs 2,905 | 535 / 616 / 280 (28) | -Rs 41,273 | Dec 25 -Rs 148,232 | -Rs 209,843 |
| R51 Donchian 20 + Supertrend | B | 691 | 52% | **Rs 531,615** | Rs 5,758 / -Rs 4,563 | 0 / 0 / 691 (112) | -Rs 73,545 | Oct 25 Rs 7,448 | -Rs 87,097 |
| R51 Donchian 20 + Supertrend | B+S | 731 | 54% | **Rs 225,858** | Rs 3,249 / -Rs 3,109 | 279 / 346 / 106 (9) | -Rs 36,115 | Dec 25 -Rs 33,385 | -Rs 100,675 |
| All three arms | A | 2742 | 46% | **Rs 1,237,057** | Rs 5,426 / -Rs 3,810 | 0 / 0 / 2742 (394) | -Rs 151,955 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three arms | S | 2862 | 50% | **Rs 175,861** | Rs 3,004 / -Rs 2,861 | 1028 / 1184 / 650 (51) | -Rs 75,786 | Dec 25 -Rs 339,880 | -Rs 375,390 |
| All three arms | B | 1459 | 51% | **Rs 1,543,648** | Rs 6,088 / -Rs 4,249 | 0 / 0 / 1459 (188) | -Rs 130,335 | Dec 25 -Rs 3,317 | -Rs 171,782 |
| All three arms | B+S | 1510 | 54% | **Rs 603,116** | Rs 3,317 / -Rs 2,986 | 538 / 679 / 293 (11) | -Rs 63,593 | Mar 26 -Rs 40,815 | -Rs 161,753 |

### final (1 Apr - 13 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 39% | **Rs 65,610** | Rs 5,147 / -Rs 2,981 | 0 / 0 / 357 (12) | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | S | 358 | 44% | **-Rs 63,508** | Rs 2,712 / -Rs 2,409 | 101 / 92 / 165 (0) | -Rs 17,309 | Apr 26 -Rs 59,915 | -Rs 127,614 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 621,770** | Rs 5,260 / -Rs 3,603 | 0 / 0 / 1200 (173) | -Rs 66,325 | Jun 26 -Rs 104,488 | -Rs 160,424 |
| R72 Breadth Donchian | S | 1242 | 47% | **Rs 138,154** | Rs 3,513 / -Rs 2,937 | 505 / 470 / 267 (10) | -Rs 39,674 | Apr 26 -Rs 86,351 | -Rs 142,310 |
| R72 Breadth Donchian | B | 602 | 50% | **Rs 567,659** | Rs 5,794 / -Rs 3,844 | 0 / 0 / 602 (78) | -Rs 62,591 | May 26 -Rs 48,023 | -Rs 89,763 |
| R72 Breadth Donchian | B+S | 625 | 49% | **Rs 246,193** | Rs 3,886 / -Rs 2,977 | 256 / 262 / 107 (5) | -Rs 43,209 | May 26 -Rs 34,586 | -Rs 82,990 |
| R51 Donchian 20 + Supertrend | A | 1668 | 46% | **Rs 547,916** | Rs 4,911 / -Rs 3,573 | 0 / 0 / 1668 (218) | -Rs 63,776 | Jun 26 -Rs 138,454 | -Rs 209,599 |
| R51 Donchian 20 + Supertrend | S | 1724 | 48% | **Rs 90,160** | Rs 3,220 / -Rs 2,855 | 662 / 650 / 412 (13) | -Rs 49,650 | Jun 26 -Rs 80,779 | -Rs 163,465 |
| R51 Donchian 20 + Supertrend | B | 652 | 51% | **Rs 491,513** | Rs 5,213 / -Rs 3,930 | 0 / 0 / 652 (85) | -Rs 43,311 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S | 697 | 50% | **Rs 4,153** | Rs 3,016 / -Rs 3,047 | 276 / 287 / 134 (8) | -Rs 36,025 | Apr 26 -Rs 98,708 | -Rs 151,037 |
| All three arms | A | 3225 | 45% | **Rs 1,235,296** | Rs 5,067 / -Rs 3,511 | 0 / 0 / 3225 (403) | -Rs 158,555 | Jun 26 -Rs 241,234 | -Rs 330,026 |
| All three arms | S | 3324 | 47% | **Rs 164,805** | Rs 3,279 / -Rs 2,834 | 1268 / 1212 / 844 (23) | -Rs 89,390 | Apr 26 -Rs 195,683 | -Rs 351,391 |
| All three arms | B | 1611 | 48% | **Rs 1,124,783** | Rs 5,426 / -Rs 3,652 | 0 / 0 / 1611 (175) | -Rs 117,746 | Apr 26 -Rs 61,992 | -Rs 178,236 |
| All three arms | B+S | 1680 | 48% | **Rs 186,838** | Rs 3,286 / -Rs 2,873 | 633 / 641 / 406 (13) | -Rs 79,300 | Apr 26 -Rs 180,778 | -Rs 275,581 |

### last 6 months (20 Apr - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 40% | **Rs 116,365** | Rs 5,109 / -Rs 2,792 | 0 / 0 / 346 (13) | -Rs 33,486 | Oct 26 -Rs 41,618 | -Rs 108,209 |
| Liquidity 15+5 | S | 347 | 45% | **-Rs 46,639** | Rs 2,627 / -Rs 2,390 | 93 / 93 / 161 (0) | -Rs 19,380 | May 26 -Rs 31,558 | -Rs 94,340 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 672,861** | Rs 5,165 / -Rs 3,494 | 0 / 0 / 1116 (165) | -Rs 66,325 | Jun 26 -Rs 104,488 | -Rs 160,424 |
| R72 Breadth Donchian | S | 1158 | 48% | **Rs 202,422** | Rs 3,476 / -Rs 2,895 | 453 / 445 / 260 (11) | -Rs 40,283 | Jun 26 -Rs 54,670 | -Rs 115,619 |
| R72 Breadth Donchian | B | 537 | 51% | **Rs 594,241** | Rs 5,616 / -Rs 3,592 | 0 / 0 / 537 (68) | -Rs 50,460 | May 26 -Rs 48,023 | -Rs 89,763 |
| R72 Breadth Donchian | B+S | 552 | 50% | **Rs 268,575** | Rs 3,914 / -Rs 2,916 | 217 / 234 / 101 (5) | -Rs 25,930 | May 26 -Rs 34,586 | -Rs 74,008 |
| R51 Donchian 20 + Supertrend | A | 1584 | 47% | **Rs 590,807** | Rs 4,801 / -Rs 3,480 | 0 / 0 / 1584 (214) | -Rs 63,776 | Jun 26 -Rs 138,454 | -Rs 209,599 |
| R51 Donchian 20 + Supertrend | S | 1645 | 49% | **Rs 190,447** | Rs 3,197 / -Rs 2,823 | 612 / 630 / 403 (15) | -Rs 38,173 | Jun 26 -Rs 80,779 | -Rs 163,465 |
| R51 Donchian 20 + Supertrend | B | 598 | 52% | **Rs 497,903** | Rs 4,997 / -Rs 3,680 | 0 / 0 / 598 (79) | -Rs 43,311 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S | 645 | 51% | **Rs 82,515** | Rs 3,050 / -Rs 2,952 | 244 / 270 / 131 (9) | -Rs 31,332 | Sep 26 -Rs 77,193 | -Rs 120,453 |
| All three arms | A | 3046 | 46% | **Rs 1,380,033** | Rs 4,968 / -Rs 3,397 | 0 / 0 / 3046 (392) | -Rs 158,555 | Jun 26 -Rs 241,234 | -Rs 330,026 |
| All three arms | S | 3150 | 48% | **Rs 346,229** | Rs 3,241 / -Rs 2,799 | 1158 / 1168 / 824 (26) | -Rs 97,835 | Jun 26 -Rs 155,862 | -Rs 283,909 |
| All three arms | B | 1481 | 49% | **Rs 1,208,509** | Rs 5,253 / -Rs 3,405 | 0 / 0 / 1481 (160) | -Rs 117,746 | Jun 26 -Rs 45,890 | -Rs 143,087 |
| All three arms | B+S | 1544 | 49% | **Rs 304,451** | Rs 3,275 / -Rs 2,802 | 554 / 597 / 393 (14) | -Rs 59,118 | May 26 -Rs 26,400 | -Rs 212,554 |

### whole year (20 Oct 2025 - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 41% | **Rs 415,755** | Rs 5,645 / -Rs 2,897 | 0 / 0 / 688 (20) | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | S | 692 | 46% | **Rs 54,912** | Rs 3,094 / -Rs 2,454 | 191 / 182 / 319 (0) | -Rs 19,380 | Apr 26 -Rs 59,915 | -Rs 174,518 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 1,104,831** | Rs 5,312 / -Rs 3,788 | 0 / 0 / 2281 (345) | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | S | 2383 | 49% | **Rs 123,300** | Rs 3,210 / -Rs 2,933 | 924 / 967 / 492 (34) | -Rs 40,283 | Dec 25 -Rs 188,381 | -Rs 239,296 |
| R72 Breadth Donchian | B | 1062 | 53% | **Rs 1,212,701** | Rs 6,072 / -Rs 4,316 | 0 / 0 / 1062 (150) | -Rs 76,355 | May 26 -Rs 48,023 | -Rs 100,468 |
| R72 Breadth Donchian | B+S | 1093 | 52% | **Rs 477,495** | Rs 3,603 / -Rs 3,052 | 435 / 513 / 145 (7) | -Rs 43,209 | May 26 -Rs 34,586 | -Rs 152,702 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 946,603** | Rs 5,069 / -Rs 3,746 | 0 / 0 / 3084 (452) | -Rs 85,577 | Jun 26 -Rs 138,454 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | S | 3206 | 49% | **Rs 110,589** | Rs 3,086 / -Rs 2,887 | 1218 / 1292 / 696 (43) | -Rs 49,650 | Dec 25 -Rs 148,232 | -Rs 285,313 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 1,004,440** | Rs 5,470 / -Rs 4,274 | 0 / 0 / 1361 (204) | -Rs 73,545 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S | 1448 | 52% | **Rs 205,476** | Rs 3,137 / -Rs 3,077 | 567 / 640 / 241 (18) | -Rs 36,115 | Apr 26 -Rs 98,708 | -Rs 218,483 |
| All three arms | A | 6053 | 46% | **Rs 2,467,188** | Rs 5,221 / -Rs 3,657 | 0 / 0 / 6053 (817) | -Rs 158,555 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three arms | S | 6281 | 48% | **Rs 288,801** | Rs 3,134 / -Rs 2,854 | 2333 / 2441 / 1507 (77) | -Rs 97,835 | Dec 25 -Rs 339,880 | -Rs 536,817 |
| All three arms | B | 3111 | 50% | **Rs 2,632,895** | Rs 5,720 / -Rs 3,932 | 0 / 0 / 3111 (374) | -Rs 130,335 | Apr 26 -Rs 61,992 | -Rs 199,852 |
| All three arms | B+S | 3233 | 51% | **Rs 737,883** | Rs 3,292 / -Rs 2,922 | 1193 / 1335 / 705 (25) | -Rs 79,300 | Apr 26 -Rs 180,778 | -Rs 437,335 |

### Month by month, the whole year (3 lots)

| arm | variant | Oct 25 | Nov 25 | Dec 25 | Jan 26 | Feb 26 | Mar 26 | Apr 26 | May 26 | Jun 26 | Jul 26 | Aug 26 | Sep 26 | Oct 26 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 98 | Rs 12,178 | -Rs 9,170 | Rs 143,894 | Rs 137,377 | Rs 85,998 | -Rs 108,688 | -Rs 22,176 | Rs 1,707 | Rs 105,053 | -Rs 56 | Rs 111,354 | -Rs 41,618 | Rs 415,755 |
| Liquidity 15+5 | S | Rs 6,554 | Rs 20,844 | -Rs 3,267 | Rs 31,039 | Rs 100,708 | -Rs 18,590 | -Rs 59,915 | -Rs 31,558 | -Rs 20,413 | Rs 30,312 | -Rs 2,697 | Rs 28,730 | -Rs 26,837 | Rs 54,912 |
| R72 Breadth Donchian | A | Rs 73,588 | Rs 40,861 | -Rs 197,761 | Rs 192,576 | Rs 98,081 | Rs 275,316 | -Rs 27,377 | Rs 138,976 | -Rs 104,488 | Rs 210,691 | Rs 76,503 | Rs 105,793 | Rs 222,071 | Rs 1,104,831 |
| R72 Breadth Donchian | S | Rs 29,761 | Rs 28,948 | -Rs 188,381 | Rs 149,968 | Rs 22,684 | -Rs 39,635 | -Rs 86,351 | Rs 8,289 | -Rs 54,670 | Rs 164,842 | Rs 30,150 | Rs 33,258 | Rs 24,437 | Rs 123,300 |
| R72 Breadth Donchian | B | Rs 58,498 | Rs 45,276 | -Rs 2,168 | Rs 193,848 | Rs 93,532 | Rs 252,868 | Rs 13,173 | -Rs 48,023 | Rs 15,164 | Rs 283,676 | -Rs 4,111 | Rs 123,380 | Rs 187,588 | Rs 1,212,701 |
| R72 Breadth Donchian | B+S | Rs 23,438 | Rs 58,278 | -Rs 3,710 | Rs 130,406 | Rs 39,443 | -Rs 7,886 | -Rs 22,155 | -Rs 34,586 | Rs 38,047 | Rs 189,816 | -Rs 2,064 | Rs 31,772 | Rs 36,697 | Rs 477,495 |
| R51 Donchian 20 + Supertrend | A | Rs 12,923 | Rs 72,094 | -Rs 129,380 | Rs 251,504 | -Rs 60,702 | Rs 237,778 | Rs 70,983 | Rs 111,528 | -Rs 138,454 | Rs 229,910 | Rs 80,084 | Rs 16,311 | Rs 192,022 | Rs 946,603 |
| R51 Donchian 20 + Supertrend | S | Rs 34,162 | -Rs 1,812 | -Rs 148,232 | Rs 222,109 | -Rs 59,321 | -Rs 11,680 | -Rs 49,417 | Rs 62,735 | -Rs 80,779 | Rs 172,154 | Rs 13,115 | -Rs 56,907 | Rs 14,462 | Rs 110,589 |
| R51 Donchian 20 + Supertrend | B | Rs 7,448 | Rs 46,362 | Rs 8,021 | Rs 220,635 | Rs 131,126 | Rs 118,023 | Rs 33,522 | Rs 64,402 | -Rs 62,761 | Rs 203,002 | Rs 64,482 | Rs 22,659 | Rs 147,519 | Rs 1,004,440 |
| R51 Donchian 20 + Supertrend | B+S | Rs 25,727 | Rs 4,547 | -Rs 33,385 | Rs 176,188 | Rs 67,120 | -Rs 14,339 | -Rs 98,708 | Rs 39,744 | Rs 11,460 | Rs 111,482 | Rs 8,046 | -Rs 77,193 | -Rs 15,214 | Rs 205,476 |
| All three arms | A | Rs 86,413 | Rs 125,133 | -Rs 336,310 | Rs 587,974 | Rs 174,756 | Rs 599,092 | -Rs 65,082 | Rs 228,328 | -Rs 241,234 | Rs 545,654 | Rs 156,531 | Rs 233,458 | Rs 372,475 | Rs 2,467,188 |
| All three arms | S | Rs 70,478 | Rs 47,980 | -Rs 339,880 | Rs 403,115 | Rs 64,072 | -Rs 69,905 | -Rs 195,683 | Rs 39,466 | -Rs 155,862 | Rs 367,308 | Rs 40,568 | Rs 5,081 | Rs 12,062 | Rs 288,801 |
| All three arms | B | Rs 65,848 | Rs 103,816 | -Rs 3,317 | Rs 558,377 | Rs 362,035 | Rs 456,889 | -Rs 61,992 | -Rs 5,797 | -Rs 45,890 | Rs 591,731 | Rs 60,315 | Rs 257,392 | Rs 293,489 | Rs 2,632,895 |
| All three arms | B+S | Rs 55,719 | Rs 83,669 | -Rs 40,361 | Rs 337,633 | Rs 207,271 | -Rs 40,815 | -Rs 180,778 | -Rs 26,400 | Rs 29,095 | Rs 331,610 | Rs 3,285 | -Rs 16,690 | -Rs 5,354 | Rs 737,883 |

Share of trades where the Rs 500 entry stop was tighter than -15% (so it replaced it): Liquidity 15+5 100%, R72 Breadth Donchian 95%, R51 Donchian 20 + Supertrend 95%.

