# R202 / R203 design results (21 Nov 2024 - 31 Mar 2026; the holdout is still locked)

Data: the dhan-data branch to 5 Oct 2026 (not R190's lake to 13 Nov); it reproduces R193's months within 2.5% (PREREG 1). 1 lot, after charges and slippage. "Twin p" = the share of 2,000 random twins that did at least as well (small is good).

## Baseline (what the arms run today)

| arm | window | trades | win % | net | average loss | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | design 21 Nov 2024 - 31 Mar 2026 | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 (2025-02) |
| R51 Donchian 20 + Supertrend | design 21 Nov 2024 - 31 Mar 2026 | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 (2025-03) |
| Liquidity 15+5 | design 21 Nov 2024 - 31 Mar 2026 | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 (2025-09) |

## R202 trend-turn exits: R72 Breadth Donchian, design

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 2 | 1,561 | 65.7% | Rs 234,102 (+Rs 1,333) | -Rs 1,627 | Rs 99,949 (-Rs 633) | -Rs 26,420 (+Rs 0) | 0.937 |
| X2: sell on an R72 breadth break the other way | 0 | 1,561 | 65.7% | Rs 232,769 (+Rs 0) | -Rs 1,629 | Rs 100,582 (+Rs 0) | -Rs 26,420 (+Rs 0) | 1.000 |
| X3: sell on an opposite signal from any of the three arms | 2 | 1,561 | 65.7% | Rs 234,102 (+Rs 1,333) | -Rs 1,627 | Rs 99,949 (-Rs 633) | -Rs 26,420 (+Rs 0) | 0.937 |
| X4: sell after two 5-min closes back through the entry level | 471 | 1,570 | 57.1% | Rs 186,831 (-Rs 45,938) | -Rs 1,171 | Rs 90,980 (-Rs 9,602) | -Rs 26,577 (-Rs 157) | 1.000 |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 38 | 1,561 | 65.7% | Rs 226,043 (-Rs 6,726) | -Rs 1,633 | Rs 96,122 (-Rs 4,460) | -Rs 25,974 (+Rs 446) | 1.000 |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 130 | 1,563 | 63.2% | Rs 176,321 (-Rs 56,448) | -Rs 1,539 | Rs 101,319 (+Rs 737) | -Rs 37,862 (-Rs 11,442) | 1.000 |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 9 | 1,561 | 65.7% | Rs 234,670 (+Rs 1,901) | -Rs 1,626 | Rs 95,954 (-Rs 4,628) | -Rs 24,839 (+Rs 1,581) | 0.995 |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 89 | 1,563 | 64.1% | Rs 184,711 (-Rs 48,058) | -Rs 1,586 | Rs 100,590 (+Rs 8) | -Rs 37,367 (-Rs 10,947) | 1.000 |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 128 | 1,570 | 64.0% | Rs 206,277 (-Rs 26,492) | -Rs 1,502 | Rs 104,698 (+Rs 4,116) | -Rs 35,718 (-Rs 9,298) | 0.996 |

Net change against B in the four walk-forward quarters (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026): X1 +Rs 524 / +Rs 175 / +Rs 0 / +Rs 0; X2 +Rs 0 / +Rs 0 / +Rs 0 / +Rs 0; X3 +Rs 524 / +Rs 175 / +Rs 0 / +Rs 0; X4 -Rs 30,905 / -Rs 101 / -Rs 390 / -Rs 14,589; X5 -Rs 3,327 / -Rs 4,536 / -Rs 1,595 / -Rs 1,427; X6 -Rs 9,708 / -Rs 70 / -Rs 14,051 / -Rs 12,519; X7 -Rs 454 / -Rs 1,560 / -Rs 910 / +Rs 0; X8 -Rs 9,238 / -Rs 3,226 / -Rs 12,736 / -Rs 7,309; X9 -Rs 17,047 / -Rs 5,698 / -Rs 6,150 / +Rs 18,361.

## R202 trend-turn exits: R51 Donchian 20 + Supertrend, design

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 3 | 2,170 | 62.5% | Rs 142,504 (-Rs 5,182) | -Rs 1,579 | Rs 91,770 (+Rs 5,833) | -Rs 36,129 (+Rs 633) | 0.994 |
| X2: sell on an R72 breadth break the other way | 1 | 2,170 | 62.5% | Rs 141,220 (-Rs 6,466) | -Rs 1,581 | Rs 92,403 (+Rs 6,466) | -Rs 36,762 (+Rs 0) | 1.000 |
| X3: sell on an opposite signal from any of the three arms | 3 | 2,170 | 62.5% | Rs 142,504 (-Rs 5,182) | -Rs 1,579 | Rs 91,770 (+Rs 5,833) | -Rs 36,129 (+Rs 633) | 0.997 |
| X4: sell after two 5-min closes back through the entry level | 721 | 2,192 | 54.8% | Rs 97,656 (-Rs 50,030) | -Rs 1,174 | Rs 124,387 (+Rs 38,450) | -Rs 41,121 (-Rs 4,359) | 1.000 |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 64 | 2,170 | 62.5% | Rs 130,554 (-Rs 17,132) | -Rs 1,592 | Rs 88,665 (+Rs 2,728) | -Rs 38,216 (-Rs 1,454) | 1.000 |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 231 | 2,172 | 60.3% | Rs 100,244 (-Rs 47,442) | -Rs 1,478 | Rs 108,363 (+Rs 22,426) | -Rs 32,838 (+Rs 3,924) | 1.000 |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 17 | 2,170 | 62.5% | Rs 138,727 (-Rs 8,959) | -Rs 1,582 | Rs 89,646 (+Rs 3,709) | -Rs 32,615 (+Rs 4,147) | 1.000 |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 192 | 2,173 | 61.0% | Rs 99,585 (-Rs 48,101) | -Rs 1,522 | Rs 105,565 (+Rs 19,628) | -Rs 33,041 (+Rs 3,721) | 1.000 |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 281 | 2,189 | 60.2% | Rs 102,052 (-Rs 45,634) | -Rs 1,438 | Rs 127,278 (+Rs 41,341) | -Rs 47,469 (-Rs 10,707) | 1.000 |

Net change against B in the four walk-forward quarters (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026): X1 -Rs 5,942 / +Rs 127 / +Rs 0 / +Rs 0; X2 -Rs 6,466 / +Rs 0 / +Rs 0 / +Rs 0; X3 -Rs 5,942 / +Rs 127 / +Rs 0 / +Rs 0; X4 -Rs 15,796 / +Rs 9,874 / -Rs 12,602 / -Rs 8,325; X5 -Rs 1,198 / -Rs 8,316 / -Rs 5,139 / +Rs 382; X6 +Rs 1,226 / -Rs 2,033 / -Rs 18,616 / -Rs 6,010; X7 -Rs 6,480 / -Rs 2,865 / -Rs 3,210 / +Rs 643; X8 -Rs 5,741 / -Rs 8,859 / -Rs 15,728 / -Rs 3,719; X9 -Rs 16,507 / -Rs 12,263 / -Rs 460 / +Rs 7,594.

## R202 trend-turn exits: Liquidity 15+5, design

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 | - |
| X1: sell on a Donchian-20 break the other way (R51's opposite signal) | 10 | 878 | 51.0% | Rs 214,915 (-Rs 16,665) | -Rs 910 | Rs 25,771 (+Rs 0) | -Rs 13,143 (-Rs 3,429) | 0.962 |
| X2: sell on an R72 breadth break the other way | 7 | 878 | 50.9% | Rs 217,000 (-Rs 14,580) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 13,390 (-Rs 3,676) | 0.915 |
| X3: sell on an opposite signal from any of the three arms | 10 | 878 | 51.0% | Rs 214,915 (-Rs 16,665) | -Rs 910 | Rs 25,771 (+Rs 0) | -Rs 13,143 (-Rs 3,429) | 0.961 |
| X4: sell after two 5-min closes back through the entry level | 110 | 878 | 49.4% | Rs 194,214 (-Rs 37,366) | -Rs 855 | Rs 25,710 (-Rs 61) | -Rs 8,177 (+Rs 1,537) | 1.000 |
| X5: sell after two 5-min closes on the wrong side of EMA20 | 22 | 878 | 51.1% | Rs 232,609 (+Rs 1,029) | -Rs 911 | Rs 25,771 (+Rs 0) | -Rs 12,351 (-Rs 2,637) | 0.490 |
| X6: sell after two 5-min closes on the wrong side of the day's average price | 35 | 878 | 50.8% | Rs 187,880 (-Rs 43,700) | -Rs 895 | Rs 24,506 (-Rs 1,265) | -Rs 13,078 (-Rs 3,364) | 1.000 |
| X7: sell when the 5-min Supertrend(10, 3) flips against the trade | 13 | 878 | 51.0% | Rs 214,816 (-Rs 16,764) | -Rs 910 | Rs 25,771 (+Rs 0) | -Rs 13,143 (-Rs 3,429) | 0.898 |
| X8: sell when breadth turns (4 of 5 indices on the wrong side of their day's average, two bars) | 31 | 878 | 51.0% | Rs 201,787 (-Rs 29,793) | -Rs 896 | Rs 24,506 (-Rs 1,265) | -Rs 13,390 (-Rs 3,676) | 0.998 |
| X9: sell when NIFTY's 15-min close is on the wrong side of its EMA20 | 27 | 879 | 50.5% | Rs 199,522 (-Rs 32,058) | -Rs 899 | Rs 27,825 (+Rs 2,054) | -Rs 9,714 (+Rs 0) | 0.999 |

Net change against B in the four walk-forward quarters (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026): X1 +Rs 3,642 / +Rs 2,166 / -Rs 1,790 / -Rs 13,596; X2 +Rs 3,642 / +Rs 0 / -Rs 1,790 / -Rs 9,098; X3 +Rs 3,642 / +Rs 2,166 / -Rs 1,790 / -Rs 13,596; X4 -Rs 31,003 / +Rs 4,838 / +Rs 6,503 / -Rs 17,898; X5 +Rs 8,228 / +Rs 3,382 / +Rs 3,214 / -Rs 2,766; X6 +Rs 3,560 / +Rs 7,068 / +Rs 1,747 / -Rs 38,224; X7 +Rs 6,192 / +Rs 4,207 / -Rs 1,790 / -Rs 11,530; X8 +Rs 1,122 / +Rs 6,045 / +Rs 236 / -Rs 18,137; X9 -Rs 12,473 / +Rs 3,852 / -Rs 2,278 / +Rs 861.

## R203 other loss-cutting rules: R72 Breadth Donchian, design

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 1,561 | 65.7% | Rs 232,769 | -Rs 1,629 | Rs 100,582 | -Rs 26,420 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 122 | 1,439 | 66.5% | Rs 234,977 (+Rs 2,208) | -Rs 1,656 | Rs 100,582 (+Rs 0) | -Rs 26,420 (+Rs 0) | 0.121 |
| W-vix: skip the worst VIX regime (walk-forward) | 401 | 1,160 | 63.8% | Rs 76,648 (-Rs 156,121) | -Rs 1,703 | Rs 94,929 (-Rs 5,653) | -Rs 26,420 (+Rs 0) | 1.000 |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 274 | 1,287 | 64.3% | Rs 120,422 (-Rs 112,347) | -Rs 1,610 | Rs 98,613 (-Rs 1,969) | -Rs 26,420 (+Rs 0) | 0.998 |
| W-dow: skip the worst weekday (walk-forward) | 184 | 1,377 | 64.6% | Rs 137,485 (-Rs 95,284) | -Rs 1,619 | Rs 106,633 (+Rs 6,051) | -Rs 26,420 (+Rs 0) | 1.000 |
| W-rv: skip the worst realised-volatility third (walk-forward) | 446 | 1,115 | 63.3% | Rs 49,821 (-Rs 182,948) | -Rs 1,675 | Rs 104,649 (+Rs 4,067) | -Rs 26,420 (+Rs 0) | 1.000 |
| W-und: skip the worst index (walk-forward) | 114 | 1,447 | 65.2% | Rs 190,578 (-Rs 42,191) | -Rs 1,637 | Rs 96,579 (-Rs 4,003) | -Rs 26,420 (+Rs 0) | 0.932 |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 254 | 1,307 | 66.0% | Rs 175,000 (-Rs 57,769) | -Rs 1,656 | Rs 97,880 (-Rs 2,702) | -Rs 26,420 (+Rs 0) | 0.802 |
| S10: entry stop at -10% instead of -15% | 358 | 1,563 | 61.5% | Rs 185,683 (-Rs 47,086) | -Rs 1,408 | Rs 90,579 (-Rs 10,003) | -Rs 25,299 (+Rs 1,121) | 1.000 |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 188 | 1,373 | 65.6% | Rs 197,865 (-Rs 34,904) | -Rs 1,632 | Rs 105,698 (+Rs 5,116) | -Rs 26,540 (-Rs 120) | 0.605 |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 134 | 1,427 | 65.5% | Rs 193,259 (-Rs 39,510) | -Rs 1,627 | Rs 102,413 (+Rs 1,831) | -Rs 24,122 (+Rs 2,298) | 0.864 |
| L2: no new entry on an index after two losers in a row on it that day | 25 | 1,536 | 65.9% | Rs 232,063 (-Rs 706) | -Rs 1,630 | Rs 96,317 (-Rs 4,265) | -Rs 25,734 (+Rs 686) | 0.340 |

Net change against B in the four walk-forward quarters (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026): W-hour -Rs 4,893 / -Rs 6,051 / +Rs 1,672 / +Rs 11,480; W-vix -Rs 82,144 / -Rs 73,978 / +Rs 0 / +Rs 0; W-gap -Rs 72,512 / -Rs 13,940 / +Rs 0 / -Rs 25,895; W-dow -Rs 20,451 / -Rs 7,715 / -Rs 25,785 / -Rs 41,334; W-rv -Rs 89,314 / -Rs 76,527 / -Rs 5,313 / -Rs 11,794; W-und -Rs 22,630 / -Rs 19,562 / +Rs 0 / +Rs 0; W-trend -Rs 19,052 / -Rs 13,975 / +Rs 6,265 / -Rs 31,008; S10 -Rs 21,191 / -Rs 4,317 / -Rs 5,075 / -Rs 30,921; DC3 -Rs 10,300 / -Rs 10,477 / -Rs 4,489 / -Rs 9,830; DC5 -Rs 14,057 / -Rs 10,477 / -Rs 2,831 / -Rs 16,724; L2 +Rs 5,070 / -Rs 4,091 / -Rs 506 / -Rs 21.

## R203 other loss-cutting rules: R51 Donchian 20 + Supertrend, design

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 2,170 | 62.6% | Rs 147,686 | -Rs 1,579 | Rs 85,937 | -Rs 36,762 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 163 | 2,007 | 62.7% | Rs 111,949 (-Rs 35,737) | -Rs 1,622 | Rs 85,937 (+Rs 0) | -Rs 36,762 (+Rs 0) | 0.889 |
| W-vix: skip the worst VIX regime (walk-forward) | 767 | 1,403 | 63.4% | Rs 113,057 (-Rs 34,629) | -Rs 1,681 | Rs 76,457 (-Rs 9,480) | -Rs 36,762 (+Rs 0) | 0.321 |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 308 | 1,862 | 62.4% | Rs 87,528 (-Rs 60,158) | -Rs 1,550 | Rs 90,849 (+Rs 4,912) | -Rs 36,762 (+Rs 0) | 0.919 |
| W-dow: skip the worst weekday (walk-forward) | 259 | 1,911 | 62.9% | Rs 144,406 (-Rs 3,280) | -Rs 1,565 | Rs 79,767 (-Rs 6,170) | -Rs 38,977 (-Rs 2,215) | 0.269 |
| W-rv: skip the worst realised-volatility third (walk-forward) | 593 | 1,577 | 63.4% | Rs 83,269 (-Rs 64,417) | -Rs 1,696 | Rs 86,586 (+Rs 649) | -Rs 36,762 (+Rs 0) | 0.772 |
| W-und: skip the worst index (walk-forward) | 281 | 1,889 | 62.7% | Rs 149,438 (+Rs 1,752) | -Rs 1,633 | Rs 82,542 (-Rs 3,395) | -Rs 36,762 (+Rs 0) | 0.199 |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 264 | 1,906 | 62.1% | Rs 86,548 (-Rs 61,138) | -Rs 1,598 | Rs 95,182 (+Rs 9,245) | -Rs 42,192 (-Rs 5,430) | 0.958 |
| S10: entry stop at -10% instead of -15% | 522 | 2,177 | 59.0% | Rs 107,800 (-Rs 39,886) | -Rs 1,399 | Rs 76,918 (-Rs 9,019) | -Rs 31,733 (+Rs 5,029) | 1.000 |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 319 | 1,851 | 61.8% | Rs 68,775 (-Rs 78,911) | -Rs 1,598 | Rs 109,343 (+Rs 23,406) | -Rs 39,701 (-Rs 2,939) | 0.977 |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 207 | 1,963 | 62.0% | Rs 88,455 (-Rs 59,231) | -Rs 1,580 | Rs 97,991 (+Rs 12,054) | -Rs 39,383 (-Rs 2,621) | 0.978 |
| L2: no new entry on an index after two losers in a row on it that day | 43 | 2,127 | 63.0% | Rs 163,789 (+Rs 16,103) | -Rs 1,583 | Rs 77,513 (-Rs 8,424) | -Rs 36,063 (+Rs 699) | 0.033 |

Net change against B in the four walk-forward quarters (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026): W-hour -Rs 16,802 / +Rs 1,721 / -Rs 5,530 / -Rs 15,125; W-vix +Rs 20,460 / -Rs 33,401 / -Rs 21,688 / +Rs 0; W-gap -Rs 3,490 / -Rs 19,378 / +Rs 0 / -Rs 37,289; W-dow +Rs 3,087 / -Rs 14,479 / -Rs 1,181 / +Rs 9,294; W-rv +Rs 19,574 / -Rs 50,836 / -Rs 16,734 / -Rs 16,421; W-und +Rs 16,022 / +Rs 162 / +Rs 4,307 / -Rs 18,738; W-trend -Rs 30,628 / +Rs 10,544 / -Rs 41,054 / +Rs 0; S10 -Rs 16,595 / -Rs 26,947 / -Rs 1,650 / -Rs 18,161; DC3 -Rs 35,450 / -Rs 9,917 / +Rs 1,487 / -Rs 42,559; DC5 -Rs 26,624 / -Rs 7,268 / -Rs 2,448 / -Rs 30,297; L2 -Rs 1,309 / +Rs 7,935 / +Rs 4,890 / -Rs 3,714.

## R203 other loss-cutting rules: Liquidity 15+5, design

| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | twin p |
|---|---|---|---|---|---|---|---|---|
| **B: today's rule** | - | 878 | 50.9% | Rs 231,580 | -Rs 909 | Rs 25,771 | -Rs 9,714 | - |
| W-hour: skip the worst entry-time bucket (walk-forward) | 47 | 831 | 51.7% | Rs 243,268 (+Rs 11,688) | -Rs 929 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 0.037 |
| W-vix: skip the worst VIX regime (walk-forward) | 0 | 878 | 50.9% | Rs 231,580 (+Rs 0) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 1.000 |
| W-gap: skip the worst opening-gap bucket (walk-forward) | 0 | 878 | 50.9% | Rs 231,580 (+Rs 0) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 1.000 |
| W-dow: skip the worst weekday (walk-forward) | 66 | 812 | 50.7% | Rs 224,842 (-Rs 6,738) | -Rs 906 | Rs 25,771 (+Rs 0) | -Rs 6,056 (+Rs 3,658) | 0.288 |
| W-rv: skip the worst realised-volatility third (walk-forward) | 0 | 878 | 50.9% | Rs 231,580 (+Rs 0) | -Rs 909 | Rs 25,771 (+Rs 0) | -Rs 9,714 (+Rs 0) | 1.000 |
| W-und: skip the worst index (walk-forward) | 227 | 651 | 49.9% | Rs 172,139 (-Rs 59,441) | -Rs 863 | Rs 25,771 (+Rs 0) | -Rs 6,056 (+Rs 3,658) | 0.503 |
| W-trend: skip the worst trend-strength bucket (walk-forward) | 38 | 840 | 51.0% | Rs 228,869 (-Rs 2,711) | -Rs 904 | Rs 25,771 (+Rs 0) | -Rs 6,056 (+Rs 3,658) | 0.318 |
| S10: entry stop at -10% instead of -15% | 46 | 878 | 49.7% | Rs 167,883 (-Rs 63,697) | -Rs 917 | Rs 26,443 (+Rs 672) | -Rs 10,727 (-Rs 1,013) | 1.000 |
| DC3: no new entry after the arm is down Rs 3,000 closed that day | 28 | 850 | 51.5% | Rs 238,552 (+Rs 6,972) | -Rs 910 | Rs 24,434 (-Rs 1,337) | -Rs 5,815 (+Rs 3,899) | 0.076 |
| DC5: no new entry after the arm is down Rs 5,000 closed that day | 4 | 874 | 51.0% | Rs 233,547 (+Rs 1,967) | -Rs 909 | Rs 26,637 (+Rs 866) | -Rs 8,621 (+Rs 1,093) | 0.203 |
| L2: no new entry on an index after two losers in a row on it that day | 15 | 863 | 50.8% | Rs 230,088 (-Rs 1,492) | -Rs 906 | Rs 27,841 (+Rs 2,070) | -Rs 8,133 (+Rs 1,581) | 0.432 |

Net change against B in the four walk-forward quarters (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, Jan-Mar 2026): W-hour +Rs 0 / +Rs 0 / +Rs 7,024 / +Rs 4,664; W-vix +Rs 0 / +Rs 0 / +Rs 0 / +Rs 0; W-gap +Rs 0 / +Rs 0 / +Rs 0 / +Rs 0; W-dow +Rs 0 / +Rs 3,718 / -Rs 10,456 / +Rs 0; W-rv +Rs 0 / +Rs 0 / +Rs 0 / +Rs 0; W-und -Rs 1,586 / -Rs 708 / -Rs 14,876 / -Rs 42,271; W-trend +Rs 0 / -Rs 2,711 / +Rs 0 / +Rs 0; S10 -Rs 35,456 / -Rs 591 / +Rs 372 / -Rs 23,809; DC3 -Rs 2,017 / +Rs 3,899 / +Rs 131 / +Rs 4,283; DC5 +Rs 0 / +Rs 1,093 / +Rs 0 / +Rs 980; L2 -Rs 1,595 / +Rs 46 / +Rs 342 / +Rs 1,785.
