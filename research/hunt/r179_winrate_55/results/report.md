# R179: a win rate above 55% with profit kept

**Boss, in short (15 Nov 2026).** Yes, it can be done on **R51**, and very nearly on R72, with **today's exits + the R151
picker + a breakeven lock** (once the option is up 10%, the stop moves to the price where the trade nets Rs 0). On the
Rs 1,500 rule none of the combinations got above 50%. Last year (20 Oct 2025 - 19 Oct 2026), 1 lot, after charges and
slippage.

**The winner: V4 = today's rules + picker + breakeven lock**

| | today's rules | V4 | gates |
|---|---|---|---|
| **R51** whole year | 45.1% win, Rs 2.21 L (3,084 trades) | **55.5% win, Rs 2.50 L** (1,367 trades) | win and profit gates pass |
| **R51** final (Apr - Oct 2026, the clean test) | 44.8%, Rs 1.32 L | **55.1%, Rs 1.45 L** | pass |
| R72 whole year | 46.3%, Rs 2.98 L (2,281) | **56.6%, Rs 3.25 L** (1,069) | pass |
| R72 final | 45.8%, Rs 1.70 L | 53.5%, Rs 1.67 L | **fails** (win 53.5%, Rs 3k less) |

- R51 V4 also has far smaller swings: max drawdown Rs 36k vs Rs 94k; at 3 lots win 55.6%, Rs 8.88 L vs Rs 9.47 L
  (slightly less money at 3 lots).
- **It still fails two formal gates:** the random-skip check (p = 0.002, but with 4,038 tests counted BH q = 1.0) and the
  account stop rule (worst month -Rs 21k in the final at 1 lot; today's rules break it too, at -Rs 55k).

**Important: compare with the picker alone, which is already live in your app on R72 / R51.**

| 1 lot, whole year | picker alone (P) | picker + breakeven (V4) |
|---|---|---|
| R51 | 50.8% win, **Rs 2.93 L** | **55.5% win**, Rs 2.50 L |
| R72 | 51.9% win, **Rs 3.72 L** | **56.6% win**, Rs 3.25 L |

So the breakeven lock buys about **+5 points of win rate for about Rs 43k-47k less profit a year** compared with what the
app already does. Against today's plain rules it is better on both win rate and profit; against the live picker it is a
trade-off: higher win rate, a bit less money.

**The Rs 1,500 rule:** with the picker 43-44% win (more profit than the rule alone: R72 Rs 1.79 L -> Rs 2.43 L); with
the breakeven lock 45-46% (less profit); with both 49% (R72 Rs 2.11 L, R51 Rs 1.20 L). None reaches 55%, and all earn less
than today's rules + picker. **Liquidity** (no picker): breakeven lock 41% win, Rs 1.00 L vs today's 39%, Rs 1.18 L.

**Honest notes:** the picker was trained on trades up to Mar 2026, so the Oct 2025 - Mar 2026 part is in-sample for it;
the Apr - Oct 2026 final is the fair test, and R51 V4 passed there (55.1%, Rs 1.45 L vs Rs 1.32 L). Apr - Oct 2026 was also
read by many earlier studies, including R162's breakeven lock. Nothing in the app changes from R179; if you want it, the
safe path is a paper / shadow trial of "picker + breakeven lock" on R51 first.

## Gates (1 lot; BH m = 4,038)

| arm : variant | passed | passes except BH and account stop | win year / final | net year (base) | twin p | failed gates |
|---|---|---|---|---|---|---|
| R72:V1 | **no** | no | 44% / 45% | Rs 242,940 (Rs 178,703) | 0.002 | win rate below 55% (year or final); twin BH q 1.00 > 0.10; outside the account stop rule in early, final, six, year |
| R51:V1 | **no** | no | 43% / 44% | Rs 139,546 (Rs 100,137) | 0.0279 | win rate below 55% (year or final); twin BH q 1.00 > 0.10; outside the account stop rule in early, final, six, year |
| R72:V2 | **no** | no | 46% / 46% | Rs 128,225 (Rs 178,703) | 1.0 | win rate below 55% (year or final); net below its base (year or final); twin BH q 1.00 > 0.10; outside the account stop rule in early, final, six, year |
| R51:V2 | **no** | no | 45% / 45% | Rs 59,506 (Rs 100,137) | 1.0 | win rate below 55% (year or final); net below its base (year or final); twin BH q 1.00 > 0.10; outside the account stop rule in early, final, six, year |
| R72:V3 | **no** | no | 49% / 49% | Rs 211,018 (Rs 178,703) | 0.002 | win rate below 55% (year or final); twin BH q 1.00 > 0.10; outside the account stop rule in early, year |
| R51:V3 | **no** | no | 49% / 50% | Rs 119,562 (Rs 100,137) | 0.0359 | win rate below 55% (year or final); twin BH q 1.00 > 0.10; outside the account stop rule in early, final, year |
| R72:V4 | **no** | no | 57% / 53% | Rs 324,629 (Rs 298,253) | 0.002 | win rate below 55% (year or final); net below its base (year or final); twin BH q 1.00 > 0.10; outside the account stop rule in final, six, year |
| R51:V4 | **no** | **yes** | 55% / 55% | Rs 249,903 (Rs 221,238) | 0.002 | twin BH q 1.00 > 0.10; outside the account stop rule in final, six, year |

Parity: today's rules on the final = R172's check (LIQ 357 / Rs 11,155; R72 1200 / Rs 170,336; R51 1668 / Rs 131,546). Breakeven lock on the final vs R162's BE10 (13 Oct stores): R72 1201 / Rs 163,579 vs 1203 / Rs 164,609; R51 1671 / Rs 128,105 vs 1673 / Rs 127,044 (the gap is NIFTY's 13 Oct expiry day, which the 13 Oct stores could not see).

### whole year (20 Oct 2025 - 19 Oct 2026), 1 lot

| arm | variant | trades | win | net | avg win / avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | today's rules | 688 | 39% | **Rs 117,923** | Rs 1,935 / -Rs 968 | -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | today + breakeven lock | 688 | 41% | **Rs 100,222** | Rs 1,718 / -Rs 960 | -Rs 37,773 | -Rs 67,497 |
| Liquidity 15+5 | Rs 1,500 rule | 688 | 37% | **Rs 77,921** | Rs 1,796 / -Rs 890 | -Rs 27,066 | -Rs 75,202 |
| Liquidity 15+5 | Rs 1,500 + breakeven | 688 | 40% | **Rs 68,821** | Rs 1,612 / -Rs 894 | -Rs 27,018 | -Rs 81,341 |
| R72 Breadth Donchian | today's rules | 2281 | 46% | **Rs 298,253** | Rs 1,763 / -Rs 1,279 | -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | today + picker | 1062 | 52% | **Rs 371,717** | Rs 2,019 / -Rs 1,449 | -Rs 20,632 | -Rs 34,940 |
| R72 Breadth Donchian | today + breakeven lock | 2290 | 51% | **Rs 243,383** | Rs 1,463 / -Rs 1,287 | -Rs 69,537 | -Rs 81,947 |
| R72 Breadth Donchian | today + picker + breakeven | 1069 | 57% | **Rs 324,629** | Rs 1,675 / -Rs 1,484 | -Rs 18,110 | -Rs 39,246 |
| R72 Breadth Donchian | Rs 1,500 rule | 2283 | 42% | **Rs 178,703** | Rs 1,757 / -Rs 1,122 | -Rs 63,000 | -Rs 71,862 |
| R72 Breadth Donchian | Rs 1,500 + picker | 1063 | 44% | **Rs 242,940** | Rs 2,029 / -Rs 1,198 | -Rs 17,422 | -Rs 66,516 |
| R72 Breadth Donchian | Rs 1,500 + breakeven | 2292 | 46% | **Rs 128,225** | Rs 1,456 / -Rs 1,149 | -Rs 59,814 | -Rs 78,225 |
| R72 Breadth Donchian | Rs 1,500 + picker + breakeven | 1070 | 49% | **Rs 211,018** | Rs 1,684 / -Rs 1,246 | -Rs 24,771 | -Rs 73,226 |
| R51 Donchian 20 + Supertrend | today's rules | 3084 | 45% | **Rs 221,238** | Rs 1,692 / -Rs 1,259 | -Rs 54,587 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | today + picker | 1361 | 51% | **Rs 293,395** | Rs 1,814 / -Rs 1,438 | -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | today + breakeven lock | 3092 | 49% | **Rs 165,424** | Rs 1,416 / -Rs 1,273 | -Rs 64,710 | -Rs 124,471 |
| R51 Donchian 20 + Supertrend | today + picker + breakeven | 1367 | 55% | **Rs 249,903** | Rs 1,494 / -Rs 1,450 | -Rs 20,990 | -Rs 36,021 |
| R51 Donchian 20 + Supertrend | Rs 1,500 rule | 3098 | 41% | **Rs 100,137** | Rs 1,678 / -Rs 1,100 | -Rs 46,350 | -Rs 124,370 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker | 1374 | 43% | **Rs 139,546** | Rs 1,777 / -Rs 1,186 | -Rs 22,906 | -Rs 74,854 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + breakeven | 3105 | 45% | **Rs 59,506** | Rs 1,405 / -Rs 1,132 | -Rs 57,418 | -Rs 145,304 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker + breakeven | 1379 | 49% | **Rs 119,562** | Rs 1,457 / -Rs 1,231 | -Rs 28,113 | -Rs 83,939 |

### final (1 Apr - 13 Oct 2026), 1 lot

| arm | variant | trades | win | net | avg win / avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | today's rules | 357 | 37% | **Rs 11,155** | Rs 1,762 / -Rs 997 | -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | today + breakeven lock | 357 | 41% | **Rs 6,432** | Rs 1,442 / -Rs 979 | -Rs 37,773 | -Rs 67,497 |
| Liquidity 15+5 | Rs 1,500 rule | 357 | 36% | **Rs 17,022** | Rs 1,734 / -Rs 907 | -Rs 27,066 | -Rs 53,006 |
| Liquidity 15+5 | Rs 1,500 + breakeven | 357 | 40% | **Rs 11,035** | Rs 1,472 / -Rs 910 | -Rs 27,018 | -Rs 59,683 |
| R72 Breadth Donchian | today's rules | 1200 | 46% | **Rs 170,336** | Rs 1,748 / -Rs 1,217 | -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | today + picker | 602 | 49% | **Rs 170,735** | Rs 1,920 / -Rs 1,299 | -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | today + breakeven lock | 1201 | 49% | **Rs 163,579** | Rs 1,532 / -Rs 1,230 | -Rs 43,733 | -Rs 64,980 |
| R72 Breadth Donchian | today + picker + breakeven | 604 | 53% | **Rs 167,233** | Rs 1,686 / -Rs 1,343 | -Rs 18,110 | -Rs 28,832 |
| R72 Breadth Donchian | Rs 1,500 rule | 1202 | 43% | **Rs 110,694** | Rs 1,766 / -Rs 1,150 | -Rs 39,230 | -Rs 62,180 |
| R72 Breadth Donchian | Rs 1,500 + picker | 604 | 45% | **Rs 136,322** | Rs 1,952 / -Rs 1,189 | -Rs 15,304 | -Rs 36,395 |
| R72 Breadth Donchian | Rs 1,500 + breakeven | 1203 | 46% | **Rs 101,102** | Rs 1,538 / -Rs 1,170 | -Rs 40,590 | -Rs 63,456 |
| R72 Breadth Donchian | Rs 1,500 + picker + breakeven | 606 | 49% | **Rs 128,840** | Rs 1,704 / -Rs 1,230 | -Rs 12,989 | -Rs 37,924 |
| R51 Donchian 20 + Supertrend | today's rules | 1668 | 45% | **Rs 131,546** | Rs 1,648 / -Rs 1,197 | -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | today + picker | 652 | 50% | **Rs 143,945** | Rs 1,733 / -Rs 1,320 | -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | today + breakeven lock | 1671 | 49% | **Rs 128,105** | Rs 1,448 / -Rs 1,223 | -Rs 57,354 | -Rs 85,153 |
| R51 Donchian 20 + Supertrend | today + picker + breakeven | 655 | 55% | **Rs 144,802** | Rs 1,514 / -Rs 1,367 | -Rs 20,990 | -Rs 36,021 |
| R51 Donchian 20 + Supertrend | Rs 1,500 rule | 1675 | 41% | **Rs 68,417** | Rs 1,665 / -Rs 1,102 | -Rs 42,227 | -Rs 65,146 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker | 661 | 44% | **Rs 79,153** | Rs 1,762 / -Rs 1,180 | -Rs 18,216 | -Rs 40,830 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + breakeven | 1677 | 45% | **Rs 67,873** | Rs 1,453 / -Rs 1,133 | -Rs 46,460 | -Rs 65,384 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker + breakeven | 664 | 50% | **Rs 83,936** | Rs 1,511 / -Rs 1,234 | -Rs 21,001 | -Rs 43,511 |

### early (to 31 Mar 2026; picker in-sample), 1 lot

| arm | variant | trades | win | net | avg win / avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | today's rules | 316 | 42% | **Rs 113,873** | Rs 2,142 / -Rs 935 | -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | today + breakeven lock | 316 | 42% | **Rs 100,895** | Rs 2,053 / -Rs 940 | -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | Rs 1,500 rule | 316 | 39% | **Rs 66,452** | Rs 1,892 / -Rs 876 | -Rs 11,191 | -Rs 25,803 |
| Liquidity 15+5 | Rs 1,500 + breakeven | 316 | 41% | **Rs 63,340** | Rs 1,792 / -Rs 883 | -Rs 11,191 | -Rs 25,266 |
| R72 Breadth Donchian | today's rules | 1056 | 47% | **Rs 128,541** | Rs 1,792 / -Rs 1,341 | -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | today + picker | 452 | 55% | **Rs 200,162** | Rs 2,153 / -Rs 1,673 | -Rs 1,721 | -Rs 34,940 |
| R72 Breadth Donchian | today + breakeven lock | 1064 | 52% | **Rs 80,427** | Rs 1,392 / -Rs 1,344 | -Rs 69,537 | -Rs 81,947 |
| R72 Breadth Donchian | today + picker + breakeven | 457 | 61% | **Rs 156,576** | Rs 1,671 / -Rs 1,701 | Rs 1,507 | -Rs 39,246 |
| R72 Breadth Donchian | Rs 1,500 rule | 1056 | 41% | **Rs 75,123** | Rs 1,756 / -Rs 1,081 | -Rs 63,000 | -Rs 71,543 |
| R72 Breadth Donchian | Rs 1,500 + picker | 451 | 43% | **Rs 114,346** | Rs 2,143 / -Rs 1,199 | -Rs 17,422 | -Rs 33,779 |
| R72 Breadth Donchian | Rs 1,500 + breakeven | 1064 | 46% | **Rs 34,237** | Rs 1,365 / -Rs 1,114 | -Rs 59,814 | -Rs 70,534 |
| R72 Breadth Donchian | Rs 1,500 + picker + breakeven | 456 | 50% | **Rs 89,905** | Rs 1,662 / -Rs 1,255 | -Rs 24,771 | -Rs 35,303 |
| R51 Donchian 20 + Supertrend | today's rules | 1370 | 45% | **Rs 86,280** | Rs 1,744 / -Rs 1,326 | -Rs 51,699 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | today + picker | 691 | 51% | **Rs 156,237** | Rs 1,905 / -Rs 1,537 | -Rs 904 | -Rs 29,931 |
| R51 Donchian 20 + Supertrend | today + breakeven lock | 1375 | 50% | **Rs 42,500** | Rs 1,373 / -Rs 1,321 | -Rs 64,710 | -Rs 124,471 |
| R51 Donchian 20 + Supertrend | today + picker + breakeven | 694 | 56% | **Rs 113,329** | Rs 1,483 / -Rs 1,520 | -Rs 3,546 | -Rs 35,297 |
| R51 Donchian 20 + Supertrend | Rs 1,500 rule | 1377 | 40% | **Rs 35,380** | Rs 1,689 / -Rs 1,091 | -Rs 46,350 | -Rs 98,375 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker | 695 | 43% | **Rs 75,374** | Rs 1,802 / -Rs 1,178 | -Rs 22,906 | -Rs 34,024 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + breakeven | 1382 | 46% | **Rs 2,123** | Rs 1,340 / -Rs 1,120 | -Rs 57,418 | -Rs 109,857 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker + breakeven | 697 | 49% | **Rs 52,050** | Rs 1,411 / -Rs 1,220 | -Rs 28,113 | -Rs 40,428 |

### last 6 months, 1 lot

| arm | variant | trades | win | net | avg win / avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | today's rules | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | today + breakeven lock | 346 | 42% | **Rs 23,670** | Rs 1,427 / -Rs 911 | -Rs 18,290 | -Rs 44,444 |
| Liquidity 15+5 | Rs 1,500 rule | 346 | 37% | **Rs 28,975** | Rs 1,712 / -Rs 872 | -Rs 13,794 | -Rs 37,533 |
| Liquidity 15+5 | Rs 1,500 + breakeven | 346 | 40% | **Rs 22,987** | Rs 1,450 / -Rs 874 | -Rs 20,518 | -Rs 44,210 |
| R72 Breadth Donchian | today's rules | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | today + picker | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | today + breakeven lock | 1117 | 50% | **Rs 183,569** | Rs 1,500 / -Rs 1,193 | -Rs 43,733 | -Rs 64,980 |
| R72 Breadth Donchian | today + picker + breakeven | 539 | 55% | **Rs 178,459** | Rs 1,628 / -Rs 1,261 | -Rs 18,110 | -Rs 28,832 |
| R72 Breadth Donchian | Rs 1,500 rule | 1117 | 44% | **Rs 145,124** | Rs 1,731 / -Rs 1,117 | -Rs 39,230 | -Rs 62,180 |
| R72 Breadth Donchian | Rs 1,500 + picker | 537 | 47% | **Rs 148,820** | Rs 1,882 / -Rs 1,142 | -Rs 15,304 | -Rs 25,172 |
| R72 Breadth Donchian | Rs 1,500 + breakeven | 1118 | 48% | **Rs 137,060** | Rs 1,504 / -Rs 1,136 | -Rs 40,590 | -Rs 63,456 |
| R72 Breadth Donchian | Rs 1,500 + picker + breakeven | 539 | 51% | **Rs 142,867** | Rs 1,638 / -Rs 1,187 | -Rs 12,989 | -Rs 22,733 |
| R51 Donchian 20 + Supertrend | today's rules | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | today + picker | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | today + breakeven lock | 1587 | 49% | **Rs 136,176** | Rs 1,409 / -Rs 1,196 | -Rs 57,354 | -Rs 85,153 |
| R51 Donchian 20 + Supertrend | today + picker + breakeven | 601 | 56% | **Rs 149,820** | Rs 1,443 / -Rs 1,274 | -Rs 20,990 | -Rs 36,021 |
| R51 Donchian 20 + Supertrend | Rs 1,500 rule | 1591 | 42% | **Rs 115,514** | Rs 1,634 / -Rs 1,075 | -Rs 42,227 | -Rs 65,146 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker | 607 | 46% | **Rs 101,356** | Rs 1,690 / -Rs 1,128 | -Rs 14,128 | -Rs 37,043 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + breakeven | 1593 | 47% | **Rs 109,123** | Rs 1,422 / -Rs 1,108 | -Rs 46,460 | -Rs 65,384 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker + breakeven | 610 | 52% | **Rs 107,379** | Rs 1,445 / -Rs 1,179 | -Rs 10,452 | -Rs 35,078 |

### whole year (20 Oct 2025 - 19 Oct 2026), 3 lots

| arm | variant | trades | win | net | avg win / avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | today's rules | 688 | 41% | **Rs 415,755** | Rs 5,645 / -Rs 2,897 | -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | today + breakeven lock | 688 | 42% | **Rs 358,180** | Rs 5,092 / -Rs 2,850 | -Rs 108,636 | -Rs 192,193 |
| Liquidity 15+5 | Rs 1,500 rule | 688 | 39% | **Rs 294,249** | Rs 5,273 / -Rs 2,645 | -Rs 76,396 | -Rs 207,153 |
| Liquidity 15+5 | Rs 1,500 + breakeven | 688 | 41% | **Rs 267,464** | Rs 4,793 / -Rs 2,652 | -Rs 76,344 | -Rs 226,066 |
| R72 Breadth Donchian | today's rules | 2281 | 47% | **Rs 1,104,831** | Rs 5,312 / -Rs 3,788 | -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | today + picker | 1062 | 53% | **Rs 1,212,701** | Rs 6,072 / -Rs 4,316 | -Rs 48,023 | -Rs 100,468 |
| R72 Breadth Donchian | today + breakeven lock | 2290 | 51% | **Rs 941,570** | Rs 4,469 / -Rs 3,784 | -Rs 189,548 | -Rs 229,586 |
| R72 Breadth Donchian | today + picker + breakeven | 1069 | 57% | **Rs 1,080,773** | Rs 5,116 / -Rs 4,383 | -Rs 41,504 | -Rs 113,728 |
| R72 Breadth Donchian | Rs 1,500 rule | 2283 | 42% | **Rs 745,984** | Rs 5,286 / -Rs 3,311 | -Rs 169,025 | -Rs 204,486 |
| R72 Breadth Donchian | Rs 1,500 + picker | 1063 | 45% | **Rs 826,492** | Rs 6,075 / -Rs 3,551 | -Rs 36,970 | -Rs 180,799 |
| R72 Breadth Donchian | Rs 1,500 + breakeven | 2292 | 47% | **Rs 597,209** | Rs 4,438 / -Rs 3,372 | -Rs 160,499 | -Rs 215,082 |
| R72 Breadth Donchian | Rs 1,500 + picker + breakeven | 1070 | 50% | **Rs 741,060** | Rs 5,126 / -Rs 3,675 | -Rs 56,329 | -Rs 198,150 |
| R51 Donchian 20 + Supertrend | today's rules | 3084 | 46% | **Rs 946,603** | Rs 5,069 / -Rs 3,746 | -Rs 138,454 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | today + picker | 1361 | 51% | **Rs 1,004,440** | Rs 5,470 / -Rs 4,274 | -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | today + breakeven lock | 3091 | 50% | **Rs 788,604** | Rs 4,306 / -Rs 3,767 | -Rs 172,700 | -Rs 354,912 |
| R51 Donchian 20 + Supertrend | today + picker + breakeven | 1366 | 56% | **Rs 887,796** | Rs 4,590 / -Rs 4,276 | -Rs 57,548 | -Rs 101,336 |
| R51 Donchian 20 + Supertrend | Rs 1,500 rule | 3098 | 42% | **Rs 583,206** | Rs 5,024 / -Rs 3,257 | -Rs 114,785 | -Rs 314,291 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker | 1374 | 44% | **Rs 544,131** | Rs 5,342 / -Rs 3,507 | -Rs 55,283 | -Rs 206,548 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + breakeven | 3104 | 46% | **Rs 473,172** | Rs 4,271 / -Rs 3,338 | -Rs 150,524 | -Rs 367,825 |
| R51 Donchian 20 + Supertrend | Rs 1,500 + picker + breakeven | 1378 | 49% | **Rs 499,862** | Rs 4,468 / -Rs 3,626 | -Rs 58,259 | -Rs 221,069 |

Signal source: the index (entries; the picker's features). Costs: dated Zerodha charges, R17 slippage.
