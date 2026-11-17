# R187: one multi-layer network over the lake, used to skip trades and pick exits - full tables

16 Nov 2026. Research only: nothing in the app changes. PREREG.md (pushed before any number). 1 lot, dated Zerodha charges, R17 fills. Signal source: the index (entries unchanged); exits on the option premium; option-chain data only as network inputs.

## The network in each fold

| fold | training trades | stacked rows | classifier (hidden, alpha) | regressor (hidden, alpha) |
|---|---|---|---|---|
| F1 | 4,337 | 32,673 | (64, 32, 16), 0.001 | (64, 32, 16), 0.001 |
| F2 | 5,928 | 45,273 | (64, 32, 16), 0.001 | (64, 32, 16), 0.001 |
| F3 | 7,574 | 58,295 | (64, 32, 16), 0.01 | (64, 32, 16), 0.001 |
| F4 | 9,284 | 71,831 | (64, 32, 16), 0.01 | (64, 32, 16), 0.01 |
| F5 | 11,018 | 85,564 | (64, 32, 16), 0.01 | (64, 32, 16), 0.01 |

Choices per fold (theta: skip below this P(win); delta: rupees the chosen exit must beat V5 / B0 by; None = no skip; inf = never switch):

| fold | arm | theta1 | delta | theta3 | theta4 |
|---|---|---|---|---|---|
| F1 | R72 Breadth Donchian | 0.45 | 0.0 | 0.4 | 0.65 |
| F1 | R51 Donchian 20 + Supertrend | None | inf | None | None |
| F1 | Liquidity 15+5 | 0.35 | inf | 0.35 | - |
| F1 | ORB | None | 0.0 | None | - |
| F1 | ORB Fresh | None | 0.0 | None | - |
| F1 | ORB Sweep | None | inf | None | - |
| F2 | R72 Breadth Donchian | 0.425 | 0.0 | 0.425 | 0.425 |
| F2 | R51 Donchian 20 + Supertrend | 0.4 | 100.0 | 0.4 | 0.625 |
| F2 | Liquidity 15+5 | None | inf | None | - |
| F2 | ORB | 0.3 | inf | 0.3 | - |
| F2 | ORB Fresh | 0.3 | inf | 0.3 | - |
| F2 | ORB Sweep | None | 0.0 | None | - |
| F3 | R72 Breadth Donchian | None | 0.0 | None | 0.425 |
| F3 | R51 Donchian 20 + Supertrend | 0.55 | 0.0 | 0.5 | 0.525 |
| F3 | Liquidity 15+5 | 0.5 | 0.0 | None | - |
| F3 | ORB | 0.375 | inf | 0.375 | - |
| F3 | ORB Fresh | 0.375 | inf | 0.375 | - |
| F3 | ORB Sweep | 0.3 | 0.0 | 0.3 | - |
| F4 | R72 Breadth Donchian | None | 100.0 | None | 0.5 |
| F4 | R51 Donchian 20 + Supertrend | None | 100.0 | None | 0.425 |
| F4 | Liquidity 15+5 | 0.375 | inf | 0.375 | - |
| F4 | ORB | 0.4 | 0.0 | 0.4 | - |
| F4 | ORB Fresh | 0.4 | 0.0 | 0.4 | - |
| F4 | ORB Sweep | None | 0.0 | None | - |
| F5 | R72 Breadth Donchian | None | 100.0 | None | 0.575 |
| F5 | R51 Donchian 20 + Supertrend | None | 0.0 | None | 0.575 |
| F5 | Liquidity 15+5 | 0.4 | inf | 0.4 | - |
| F5 | ORB | 0.4 | 0.0 | 0.375 | - |
| F5 | ORB Fresh | 0.4 | 0.0 | 0.375 | - |
| F5 | ORB Sweep | None | 100.0 | None | - |

## Gate verdicts (BH m = 4,902)

| arm | use | passed | twin p | BH q | failed on |
|---|---|---|---|---|---|
| R72 Breadth Donchian | U1 | no | 1.000 | 1.00 | win rate not above the base (year); neither drawdown nor worst month better (year); win rate not above the base (final); neither drawdown nor worst month better (final); twin BH q 1.00 > 0.10 |
| R72 Breadth Donchian | U2 | no | 0.709 | 1.00 | win rate not above the base (year); neither drawdown nor worst month better (year); net below the base (final); win rate not above the base (final); neither drawdown nor worst month better (final); twin BH q 1.00 > 0.10 |
| R72 Breadth Donchian | U3 | no | 0.709 | 1.00 | win rate not above the base (year); neither drawdown nor worst month better (year); net below the base (final); win rate not above the base (final); neither drawdown nor worst month better (final); twin BH q 1.00 > 0.10 |
| R51 Donchian 20 + Supertrend | U1 | no | 0.669 | 1.00 | net below the base (year); win rate not above the base (year); neither drawdown nor worst month better (year); win rate not above the base (final); neither drawdown nor worst month better (final); twin BH q 1.00 > 0.10 |
| R51 Donchian 20 + Supertrend | U2 | no | 0.303 | 1.00 | win rate not above the base (year); win rate not above the base (final); twin BH q 1.00 > 0.10 |
| R51 Donchian 20 + Supertrend | U3 | no | 0.150 | 1.00 | win rate not above the base (year); win rate not above the base (final); twin BH q 1.00 > 0.10 |
| Liquidity 15+5 | U1 | no | 0.024 | 1.00 | twin BH q 1.00 > 0.10 |
| Liquidity 15+5 | U2 | no | 0.914 | 1.00 | net below the base (year); win rate not above the base (year); neither drawdown nor worst month better (year); win rate not above the base (final); neither drawdown nor worst month better (final); twin BH q 1.00 > 0.10 |
| Liquidity 15+5 | U3 | no | 0.042 | 1.00 | twin BH q 1.00 > 0.10 |
| ORB | U1 | no | 0.719 | 1.00 | net below the base (final); win rate not above the base (final); not positive in every period; twin BH q 1.00 > 0.10; < 40 final trades |
| ORB | U2 | no | 0.637 | 1.00 | net below the base (final); neither drawdown nor worst month better (final); not positive in every period; twin BH q 1.00 > 0.10 |
| ORB | U3 | no | 0.707 | 1.00 | net below the base (final); not positive in every period; twin BH q 1.00 > 0.10 |
| ORB Fresh | U1 | no | 0.713 | 1.00 | net below the base (final); win rate not above the base (final); not positive in every period; twin BH q 1.00 > 0.10; < 40 final trades |
| ORB Fresh | U2 | no | 0.665 | 1.00 | net below the base (final); neither drawdown nor worst month better (final); not positive in every period; twin BH q 1.00 > 0.10 |
| ORB Fresh | U3 | no | 0.701 | 1.00 | net below the base (final); not positive in every period; twin BH q 1.00 > 0.10 |
| ORB Sweep | U1 | no | 0.828 | 1.00 | win rate not above the base (final); neither drawdown nor worst month better (final); not positive in every period; twin BH q 1.00 > 0.10 |
| ORB Sweep | U2 | no | 0.571 | 1.00 | net below the base (year); win rate not above the base (final); not positive in every period; twin BH q 1.00 > 0.10 |
| ORB Sweep | U3 | no | 0.872 | 1.00 | win rate not above the base (year); win rate not above the base (final); not positive in every period; twin BH q 1.00 > 0.10 |
| R72 Breadth Donchian | U4 | no | 0.002 | 1.00 | net below the base (year); win rate not above the base (year); neither drawdown nor worst month better (year); net below the base (final); neither drawdown nor worst month better (final); twin BH q 1.00 > 0.10 |
| R51 Donchian 20 + Supertrend | U4 | no | 0.054 | 1.00 | net below the base (year); win rate not above the base (year); neither drawdown nor worst month better (year); win rate not above the base (final); twin BH q 1.00 > 0.10 |

## Parity

V5 at 1 lot on the year (R186 Amendment C): R72 1097 trades / Rs 213,110 (published 1097 / Rs 213,110); R51 1430 trades / Rs 162,959 (published 1430 / Rs 162,959); LIQ 688 trades / Rs 98,239 (published 688 / Rs 98,239). ORB store: R144b's design figures and R169's WF B0 reproduced exactly (ORB 320 / -Rs 41,427; Sweep 454 / -Rs 1,12,523; WF ORB 231 / -Rs 25,965; Sweep 326 / -Rs 98,449).

## R72 Breadth Donchian

**early (20 Oct 2025 - 31 Mar 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 465 | 69.7% | Rs 102,980 | Rs 1,084 | -Rs 1,761 | -Rs 1,971 (2025-10) | Rs 15,180 |
| U1 network skip | 465 | 69.7% | Rs 102,980 | Rs 1,084 | -Rs 1,761 | -Rs 1,971 (2025-10) | Rs 15,180 |
| U2 network exit choice | 463 | 67.6% | Rs 108,085 | Rs 1,170 | -Rs 1,720 | -Rs 3,338 (2025-12) | Rs 19,117 |
| U3 skip + exit choice | 463 | 67.6% | Rs 108,085 | Rs 1,170 | -Rs 1,720 | -Rs 3,338 (2025-12) | Rs 19,117 |
| base without R151's picker | 1111 | 58.4% | -Rs 20,954 | Rs 946 | -Rs 1,375 | -Rs 68,895 (2025-12) | Rs 75,738 |
| U4 network picker (no R151 picker) | 804 | 61.8% | Rs 60,220 | Rs 971 | -Rs 1,375 | -Rs 32,202 (2025-12) | Rs 37,938 |

**final (1 Apr - 13 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 624 | 60.4% | Rs 111,117 | Rs 1,242 | -Rs 1,446 | -Rs 13,690 (2026-04) | Rs 36,477 |
| U1 network skip | 624 | 60.4% | Rs 111,117 | Rs 1,242 | -Rs 1,446 | -Rs 13,690 (2026-04) | Rs 36,477 |
| U2 network exit choice | 623 | 59.9% | Rs 109,696 | Rs 1,261 | -Rs 1,442 | -Rs 16,594 (2026-04) | Rs 36,477 |
| U3 skip + exit choice | 623 | 59.9% | Rs 109,696 | Rs 1,261 | -Rs 1,442 | -Rs 16,594 (2026-04) | Rs 36,477 |
| base without R151's picker | 1241 | 54.7% | Rs 56,357 | Rs 1,146 | -Rs 1,285 | -Rs 50,223 (2026-06) | Rs 85,320 |
| U4 network picker (no R151 picker) | 531 | 61.8% | Rs 107,549 | Rs 1,231 | -Rs 1,459 | -Rs 45,320 (2026-04) | Rs 50,818 |

**last 6 months (20 Apr - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 552 | 61.2% | Rs 132,431 | Rs 1,256 | -Rs 1,365 | -Rs 12,387 (2026-05) | Rs 31,092 |
| U1 network skip | 552 | 61.2% | Rs 132,431 | Rs 1,256 | -Rs 1,365 | -Rs 12,387 (2026-05) | Rs 31,092 |
| U2 network exit choice | 551 | 60.6% | Rs 131,010 | Rs 1,277 | -Rs 1,361 | -Rs 12,804 (2026-05) | Rs 32,678 |
| U3 skip + exit choice | 551 | 60.6% | Rs 131,010 | Rs 1,277 | -Rs 1,361 | -Rs 12,804 (2026-05) | Rs 32,678 |
| base without R151's picker | 1157 | 55.2% | Rs 86,002 | Rs 1,146 | -Rs 1,248 | -Rs 50,223 (2026-06) | Rs 85,320 |
| U4 network picker (no R151 picker) | 496 | 64.3% | Rs 145,951 | Rs 1,229 | -Rs 1,391 | -Rs 9,637 (2026-04) | Rs 20,266 |

**year (20 Oct 2025 - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 1097 | 64.4% | Rs 213,110 | Rs 1,166 | -Rs 1,560 | -Rs 13,690 (2026-04) | Rs 42,286 |
| U1 network skip | 1097 | 64.4% | Rs 213,110 | Rs 1,166 | -Rs 1,560 | -Rs 13,690 (2026-04) | Rs 42,286 |
| U2 network exit choice | 1094 | 63.2% | Rs 216,794 | Rs 1,215 | -Rs 1,546 | -Rs 16,594 (2026-04) | Rs 42,286 |
| U3 skip + exit choice | 1094 | 63.2% | Rs 216,794 | Rs 1,215 | -Rs 1,546 | -Rs 16,594 (2026-04) | Rs 42,286 |
| base without R151's picker | 2380 | 56.5% | Rs 29,557 | Rs 1,043 | -Rs 1,330 | -Rs 68,895 (2025-12) | Rs 119,279 |
| U4 network picker (no R151 picker) | 1351 | 62.0% | Rs 170,488 | Rs 1,069 | -Rs 1,414 | -Rs 45,320 (2026-04) | Rs 62,904 |

Twins (year): U1 p 1.000 (twin mean Rs 213,110); U2 p 0.709 (twin mean Rs 221,769); U3 p 0.709 (twin mean Rs 221,769); U4 p 0.002 (twin mean Rs 17,077)

## R51 Donchian 20 + Supertrend

**early (20 Oct 2025 - 31 Mar 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 728 | 65.0% | Rs 111,993 | Rs 1,075 | -Rs 1,554 | -Rs 2,632 (2025-10) | Rs 18,639 |
| U1 network skip | 581 | 64.9% | Rs 87,042 | Rs 1,104 | -Rs 1,613 | -Rs 11,759 (2025-10) | Rs 22,793 |
| U2 network exit choice | 721 | 62.5% | Rs 109,463 | Rs 1,141 | -Rs 1,500 | -Rs 8,190 (2025-10) | Rs 21,659 |
| U3 skip + exit choice | 634 | 63.6% | Rs 109,879 | Rs 1,163 | -Rs 1,554 | -Rs 10,047 (2025-10) | Rs 22,351 |
| base without R151's picker | 1427 | 58.9% | Rs 5,678 | Rs 965 | -Rs 1,372 | -Rs 46,159 (2025-12) | Rs 75,795 |
| U4 network picker (no R151 picker) | 1051 | 61.7% | Rs 34,354 | Rs 973 | -Rs 1,479 | -Rs 16,328 (2025-12) | Rs 38,654 |

**final (1 Apr - 13 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 684 | 60.7% | Rs 57,450 | Rs 1,055 | -Rs 1,414 | -Rs 29,871 (2026-04) | Rs 45,825 |
| U1 network skip | 684 | 60.7% | Rs 57,450 | Rs 1,055 | -Rs 1,414 | -Rs 29,871 (2026-04) | Rs 45,825 |
| U2 network exit choice | 671 | 58.3% | Rs 112,679 | Rs 1,271 | -Rs 1,372 | -Rs 18,547 (2026-06) | Rs 33,497 |
| U3 skip + exit choice | 671 | 58.3% | Rs 112,679 | Rs 1,271 | -Rs 1,372 | -Rs 18,547 (2026-06) | Rs 33,497 |
| base without R151's picker | 1714 | 54.5% | Rs 55,481 | Rs 1,087 | -Rs 1,231 | -Rs 53,048 (2026-06) | Rs 70,064 |
| U4 network picker (no R151 picker) | 677 | 59.8% | Rs 62,390 | Rs 1,122 | -Rs 1,441 | -Rs 33,687 (2026-04) | Rs 43,709 |

**last 6 months (20 Apr - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 630 | 61.4% | Rs 84,902 | Rs 1,063 | -Rs 1,344 | -Rs 5,338 (2026-06) | Rs 26,837 |
| U1 network skip | 630 | 61.4% | Rs 84,902 | Rs 1,063 | -Rs 1,344 | -Rs 5,338 (2026-06) | Rs 26,837 |
| U2 network exit choice | 618 | 59.2% | Rs 119,875 | Rs 1,225 | -Rs 1,303 | -Rs 18,547 (2026-06) | Rs 33,497 |
| U3 skip + exit choice | 618 | 59.2% | Rs 119,875 | Rs 1,225 | -Rs 1,303 | -Rs 18,547 (2026-06) | Rs 33,497 |
| base without R151's picker | 1634 | 54.8% | Rs 74,257 | Rs 1,081 | -Rs 1,212 | -Rs 53,048 (2026-06) | Rs 70,064 |
| U4 network picker (no R151 picker) | 652 | 61.7% | Rs 103,193 | Rs 1,126 | -Rs 1,397 | -Rs 19,363 (2026-06) | Rs 30,040 |

**year (20 Oct 2025 - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 1430 | 62.7% | Rs 162,959 | Rs 1,067 | -Rs 1,489 | -Rs 29,871 (2026-04) | Rs 54,101 |
| U1 network skip | 1283 | 62.4% | Rs 138,009 | Rs 1,079 | -Rs 1,507 | -Rs 29,871 (2026-04) | Rs 54,101 |
| U2 network exit choice | 1410 | 60.4% | Rs 217,180 | Rs 1,201 | -Rs 1,445 | -Rs 18,547 (2026-06) | Rs 33,497 |
| U3 skip + exit choice | 1323 | 60.8% | Rs 217,596 | Rs 1,216 | -Rs 1,464 | -Rs 18,547 (2026-06) | Rs 33,497 |
| base without R151's picker | 3191 | 56.5% | Rs 53,568 | Rs 1,028 | -Rs 1,297 | -Rs 53,048 (2026-06) | Rs 75,795 |
| U4 network picker (no R151 picker) | 1757 | 61.0% | Rs 99,475 | Rs 1,029 | -Rs 1,465 | -Rs 33,687 (2026-04) | Rs 56,679 |

Twins (year): U1 p 0.669 (twin mean Rs 145,815); U2 p 0.303 (twin mean Rs 207,879); U3 p 0.150 (twin mean Rs 193,703); U4 p 0.054 (twin mean Rs 31,809)

## Liquidity 15+5

**early (20 Oct 2025 - 31 Mar 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 316 | 46.2% | Rs 100,556 | Rs 1,741 | -Rs 904 | -Rs 3,743 (2025-10) | Rs 14,072 |
| U1 network skip | 245 | 49.4% | Rs 112,155 | Rs 1,913 | -Rs 963 | -Rs 2,611 (2025-12) | Rs 12,170 |
| U2 network exit choice | 316 | 44.9% | Rs 88,313 | Rs 1,754 | -Rs 923 | -Rs 13,616 (2025-12) | Rs 24,026 |
| U3 skip + exit choice | 292 | 46.2% | Rs 101,185 | Rs 1,809 | -Rs 911 | -Rs 13,616 (2025-12) | Rs 24,026 |

**final (1 Apr - 13 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 357 | 43.7% | Rs 6,424 | Rs 1,249 | -Rs 937 | -Rs 32,670 (2026-04) | Rs 59,441 |
| U1 network skip | 305 | 45.6% | Rs 18,590 | Rs 1,308 | -Rs 983 | -Rs 24,346 (2026-04) | Rs 47,124 |
| U2 network exit choice | 357 | 43.7% | Rs 6,424 | Rs 1,249 | -Rs 937 | -Rs 32,670 (2026-04) | Rs 59,441 |
| U3 skip + exit choice | 305 | 45.6% | Rs 18,590 | Rs 1,308 | -Rs 983 | -Rs 24,346 (2026-04) | Rs 47,124 |

**last 6 months (20 Apr - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 346 | 44.2% | Rs 18,547 | Rs 1,236 | -Rs 884 | -Rs 14,746 (2026-10) | Rs 39,867 |
| U1 network skip | 298 | 45.6% | Rs 23,488 | Rs 1,295 | -Rs 942 | -Rs 15,382 (2026-05) | Rs 35,105 |
| U2 network exit choice | 346 | 44.2% | Rs 18,547 | Rs 1,236 | -Rs 884 | -Rs 14,746 (2026-10) | Rs 39,867 |
| U3 skip + exit choice | 298 | 45.6% | Rs 23,488 | Rs 1,295 | -Rs 942 | -Rs 15,382 (2026-05) | Rs 35,105 |

**year (20 Oct 2025 - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 688 | 44.5% | Rs 98,239 | Rs 1,471 | -Rs 921 | -Rs 32,670 (2026-04) | Rs 60,281 |
| U1 network skip | 564 | 46.8% | Rs 122,326 | Rs 1,570 | -Rs 974 | -Rs 24,346 (2026-04) | Rs 48,322 |
| U2 network exit choice | 688 | 43.9% | Rs 85,996 | Rs 1,473 | -Rs 930 | -Rs 32,670 (2026-04) | Rs 60,281 |
| U3 skip + exit choice | 611 | 45.5% | Rs 111,356 | Rs 1,537 | -Rs 948 | -Rs 24,346 (2026-04) | Rs 48,322 |

Twins (year): U1 p 0.024 (twin mean Rs 81,516); U2 p 0.914 (twin mean Rs 92,499); U3 p 0.042 (twin mean Rs 81,454)

## ORB

**early (20 Oct 2025 - 31 Mar 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 107 | 33.6% | -Rs 24,619 | Rs 530 | -Rs 615 | -Rs 6,704 (2026-03) | Rs 24,619 |
| U1 network skip | 47 | 38.3% | -Rs 10,325 | Rs 487 | -Rs 658 | -Rs 3,422 (2026-03) | Rs 11,549 |
| U2 network exit choice | 107 | 33.6% | -Rs 24,015 | Rs 530 | -Rs 607 | -Rs 6,493 (2026-03) | Rs 24,015 |
| U3 skip + exit choice | 46 | 37.0% | -Rs 10,702 | Rs 501 | -Rs 663 | -Rs 3,422 (2026-03) | Rs 11,926 |

**final (1 Apr - 13 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 128 | 43.8% | Rs 2,876 | Rs 761 | -Rs 552 | -Rs 3,333 (2026-08) | Rs 7,493 |
| U1 network skip | 39 | 43.6% | -Rs 669 | Rs 909 | -Rs 733 | -Rs 2,850 (2026-04) | Rs 6,982 |
| U2 network exit choice | 128 | 44.5% | Rs 2,725 | Rs 752 | -Rs 565 | -Rs 3,333 (2026-08) | Rs 7,493 |
| U3 skip + exit choice | 54 | 46.3% | -Rs 1,032 | Rs 798 | -Rs 723 | -Rs 2,945 (2026-04) | Rs 7,367 |

**last 6 months (20 Apr - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 117 | 46.2% | Rs 2,964 | Rs 732 | -Rs 580 | -Rs 3,333 (2026-08) | Rs 6,681 |
| U1 network skip | 35 | 48.6% | Rs 863 | Rs 918 | -Rs 819 | -Rs 2,200 (2026-05) | Rs 5,624 |
| U2 network exit choice | 117 | 47.0% | Rs 2,812 | Rs 724 | -Rs 597 | -Rs 3,333 (2026-08) | Rs 6,906 |
| U3 skip + exit choice | 49 | 51.0% | Rs 2,103 | Rs 804 | -Rs 750 | -Rs 1,715 (2026-05) | Rs 5,902 |

**year (20 Oct 2025 - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 237 | 39.2% | -Rs 22,716 | Rs 669 | -Rs 590 | -Rs 6,704 (2026-03) | Rs 30,782 |
| U1 network skip | 88 | 40.9% | -Rs 11,967 | Rs 688 | -Rs 706 | -Rs 3,422 (2026-03) | Rs 18,532 |
| U2 network exit choice | 237 | 39.7% | -Rs 22,263 | Rs 665 | -Rs 593 | -Rs 6,493 (2026-03) | Rs 30,178 |
| U3 skip + exit choice | 102 | 42.2% | -Rs 12,707 | Rs 675 | -Rs 707 | -Rs 3,422 (2026-03) | Rs 19,294 |

Twins (year): U1 p 0.719 (twin mean -Rs 8,316); U2 p 0.637 (twin mean -Rs 21,713); U3 p 0.707 (twin mean -Rs 9,173)

## ORB Fresh

**early (20 Oct 2025 - 31 Mar 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 107 | 33.6% | -Rs 24,619 | Rs 530 | -Rs 615 | -Rs 6,704 (2026-03) | Rs 24,619 |
| U1 network skip | 47 | 38.3% | -Rs 10,325 | Rs 487 | -Rs 658 | -Rs 3,422 (2026-03) | Rs 11,549 |
| U2 network exit choice | 107 | 33.6% | -Rs 24,015 | Rs 530 | -Rs 607 | -Rs 6,493 (2026-03) | Rs 24,015 |
| U3 skip + exit choice | 46 | 37.0% | -Rs 10,702 | Rs 501 | -Rs 663 | -Rs 3,422 (2026-03) | Rs 11,926 |

**final (1 Apr - 13 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 128 | 43.8% | Rs 2,876 | Rs 761 | -Rs 552 | -Rs 3,333 (2026-08) | Rs 7,493 |
| U1 network skip | 39 | 43.6% | -Rs 669 | Rs 909 | -Rs 733 | -Rs 2,850 (2026-04) | Rs 6,982 |
| U2 network exit choice | 128 | 44.5% | Rs 2,725 | Rs 752 | -Rs 565 | -Rs 3,333 (2026-08) | Rs 7,493 |
| U3 skip + exit choice | 54 | 46.3% | -Rs 1,032 | Rs 798 | -Rs 723 | -Rs 2,945 (2026-04) | Rs 7,367 |

**last 6 months (20 Apr - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 117 | 46.2% | Rs 2,964 | Rs 732 | -Rs 580 | -Rs 3,333 (2026-08) | Rs 6,681 |
| U1 network skip | 35 | 48.6% | Rs 863 | Rs 918 | -Rs 819 | -Rs 2,200 (2026-05) | Rs 5,624 |
| U2 network exit choice | 117 | 47.0% | Rs 2,812 | Rs 724 | -Rs 597 | -Rs 3,333 (2026-08) | Rs 6,906 |
| U3 skip + exit choice | 49 | 51.0% | Rs 2,103 | Rs 804 | -Rs 750 | -Rs 1,715 (2026-05) | Rs 5,902 |

**year (20 Oct 2025 - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 237 | 39.2% | -Rs 22,716 | Rs 669 | -Rs 590 | -Rs 6,704 (2026-03) | Rs 30,782 |
| U1 network skip | 88 | 40.9% | -Rs 11,967 | Rs 688 | -Rs 706 | -Rs 3,422 (2026-03) | Rs 18,532 |
| U2 network exit choice | 237 | 39.7% | -Rs 22,263 | Rs 665 | -Rs 593 | -Rs 6,493 (2026-03) | Rs 30,178 |
| U3 skip + exit choice | 102 | 42.2% | -Rs 12,707 | Rs 675 | -Rs 707 | -Rs 3,422 (2026-03) | Rs 19,294 |

Twins (year): U1 p 0.713 (twin mean -Rs 7,856); U2 p 0.665 (twin mean -Rs 21,639); U3 p 0.701 (twin mean -Rs 9,353)

## ORB Sweep

**early (20 Oct 2025 - 31 Mar 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 147 | 28.6% | -Rs 25,921 | Rs 1,222 | -Rs 736 | -Rs 13,593 (2025-12) | Rs 36,041 |
| U1 network skip | 97 | 29.9% | -Rs 20,668 | Rs 1,185 | -Rs 809 | -Rs 10,752 (2026-01) | Rs 26,244 |
| U2 network exit choice | 152 | 28.9% | -Rs 29,125 | Rs 935 | -Rs 651 | -Rs 11,037 (2026-01) | Rs 38,109 |
| U3 skip + exit choice | 100 | 28.0% | -Rs 24,492 | Rs 923 | -Rs 699 | -Rs 11,037 (2026-01) | Rs 30,828 |

**final (1 Apr - 13 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 176 | 28.4% | -Rs 54,421 | Rs 1,186 | -Rs 903 | -Rs 15,900 (2026-04) | Rs 54,421 |
| U1 network skip | 176 | 28.4% | -Rs 54,421 | Rs 1,186 | -Rs 903 | -Rs 15,900 (2026-04) | Rs 54,421 |
| U2 network exit choice | 177 | 28.2% | -Rs 53,271 | Rs 1,211 | -Rs 896 | -Rs 15,900 (2026-04) | Rs 53,271 |
| U3 skip + exit choice | 177 | 28.2% | -Rs 53,271 | Rs 1,211 | -Rs 896 | -Rs 15,900 (2026-04) | Rs 53,271 |

**last 6 months (20 Apr - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 164 | 29.3% | -Rs 43,283 | Rs 1,204 | -Rs 871 | -Rs 13,634 (2026-05) | Rs 43,283 |
| U1 network skip | 164 | 29.3% | -Rs 43,283 | Rs 1,204 | -Rs 871 | -Rs 13,634 (2026-05) | Rs 43,283 |
| U2 network exit choice | 165 | 29.1% | -Rs 42,133 | Rs 1,229 | -Rs 864 | -Rs 13,634 (2026-05) | Rs 42,133 |
| U3 skip + exit choice | 165 | 29.1% | -Rs 42,133 | Rs 1,229 | -Rs 864 | -Rs 13,634 (2026-05) | Rs 42,133 |

**year (20 Oct 2025 - 19 Oct 2026)**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 326 | 28.5% | -Rs 81,308 | Rs 1,196 | -Rs 826 | -Rs 15,900 (2026-04) | Rs 86,350 |
| U1 network skip | 276 | 29.0% | -Rs 76,056 | Rs 1,178 | -Rs 869 | -Rs 15,900 (2026-04) | Rs 76,553 |
| U2 network exit choice | 332 | 28.6% | -Rs 83,361 | Rs 1,076 | -Rs 783 | -Rs 15,900 (2026-04) | Rs 86,009 |
| U3 skip + exit choice | 280 | 28.2% | -Rs 78,728 | Rs 1,101 | -Rs 824 | -Rs 15,900 (2026-04) | Rs 78,728 |

Twins (year): U1 p 0.828 (twin mean -Rs 68,912); U2 p 0.571 (twin mean -Rs 82,772); U3 p 0.872 (twin mean -Rs 69,161)

## Walk-forward window (Apr 2025 - Mar 2026, folds F1-F4; design data)

The ORB rows were run before the final read; the first run crashed on an import before writing its file, so the R72 / R51 / Liquidity rows were re-run (same frozen models, design data only) while the final ran. Nothing in the final run depends on them.

**R72 Breadth Donchian**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 1062 | 69.1% | Rs 275,735 | Rs 1,092 | -Rs 1,604 | -Rs 12,387 (2025-06) | Rs 36,339 |
| U1 network skip | 928 | 70.6% | Rs 266,174 | Rs 1,098 | -Rs 1,660 | -Rs 5,220 (2025-06) | Rs 36,081 |
| U2 network exit choice | 1048 | 67.1% | Rs 327,702 | Rs 1,239 | -Rs 1,575 | -Rs 10,807 (2025-06) | Rs 33,641 |
| U3 skip + exit choice | 936 | 68.3% | Rs 322,356 | Rs 1,252 | -Rs 1,607 | -Rs 3,338 (2025-12) | Rs 41,237 |
| base without R151's picker | 2353 | 58.9% | Rs 104,495 | Rs 982 | -Rs 1,300 | -Rs 68,895 (2025-12) | Rs 75,738 |
| U4 network picker (no R151 picker) | 1261 | 63.6% | Rs 177,910 | Rs 986 | -Rs 1,335 | -Rs 32,202 (2025-12) | Rs 37,938 |

**R51 Donchian 20 + Supertrend**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 1588 | 63.6% | Rs 186,716 | Rs 1,047 | -Rs 1,507 | -Rs 30,645 (2025-06) | Rs 59,441 |
| U1 network skip | 1347 | 64.1% | Rs 162,936 | Rs 1,058 | -Rs 1,555 | -Rs 30,645 (2025-06) | Rs 47,861 |
| U2 network exit choice | 1576 | 62.4% | Rs 211,075 | Rs 1,108 | -Rs 1,486 | -Rs 30,645 (2025-06) | Rs 57,139 |
| U3 skip + exit choice | 1409 | 63.4% | Rs 207,427 | Rs 1,116 | -Rs 1,535 | -Rs 30,645 (2025-06) | Rs 45,104 |
| base without R151's picker | 3138 | 58.4% | Rs 5,823 | Rs 973 | -Rs 1,364 | -Rs 67,810 (2025-04) | Rs 75,795 |
| U4 network picker (no R151 picker) | 1940 | 61.0% | Rs 26,736 | Rs 1,012 | -Rs 1,550 | -Rs 67,810 (2025-04) | Rs 69,165 |

**Liquidity 15+5**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 657 | 50.4% | Rs 194,985 | Rs 1,449 | -Rs 873 | -Rs 9,714 (2025-09) | Rs 15,493 |
| U1 network skip | 559 | 52.2% | Rs 180,326 | Rs 1,440 | -Rs 899 | -Rs 9,714 (2025-09) | Rs 13,663 |
| U2 network exit choice | 657 | 49.8% | Rs 179,403 | Rs 1,441 | -Rs 884 | -Rs 13,616 (2025-12) | Rs 24,026 |
| U3 skip + exit choice | 618 | 50.3% | Rs 174,233 | Rs 1,428 | -Rs 879 | -Rs 13,616 (2025-12) | Rs 24,026 |

**ORB**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 231 | 35.9% | -Rs 25,965 | Rs 610 | -Rs 517 | -Rs 6,704 (2026-03) | Rs 34,729 |
| U1 network skip | 150 | 40.0% | -Rs 6,058 | Rs 621 | -Rs 481 | -Rs 4,416 (2025-08) | Rs 15,641 |
| U2 network exit choice | 231 | 35.9% | -Rs 25,433 | Rs 610 | -Rs 514 | -Rs 6,493 (2026-03) | Rs 34,125 |
| U3 skip + exit choice | 149 | 39.6% | -Rs 6,507 | Rs 627 | -Rs 484 | -Rs 4,416 (2025-08) | Rs 16,018 |

**ORB Fresh**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 231 | 35.9% | -Rs 25,965 | Rs 610 | -Rs 517 | -Rs 6,704 (2026-03) | Rs 34,729 |
| U1 network skip | 150 | 40.0% | -Rs 6,058 | Rs 621 | -Rs 481 | -Rs 4,416 (2025-08) | Rs 15,641 |
| U2 network exit choice | 231 | 35.9% | -Rs 25,433 | Rs 610 | -Rs 514 | -Rs 6,493 (2026-03) | Rs 34,125 |
| U3 skip + exit choice | 149 | 39.6% | -Rs 6,507 | Rs 627 | -Rs 484 | -Rs 4,416 (2025-08) | Rs 16,018 |

**ORB Sweep**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 326 | 24.9% | -Rs 98,449 | Rs 1,176 | -Rs 791 | -Rs 15,748 (2025-08) | Rs 104,593 |
| U1 network skip | 265 | 25.7% | -Rs 81,206 | Rs 1,151 | -Rs 809 | -Rs 15,748 (2025-08) | Rs 87,350 |
| U2 network exit choice | 338 | 24.3% | -Rs 93,910 | Rs 976 | -Rs 680 | -Rs 16,211 (2025-07) | Rs 101,312 |
| U3 skip + exit choice | 273 | 23.4% | -Rs 82,236 | Rs 986 | -Rs 696 | -Rs 16,211 (2025-07) | Rs 89,638 |

## New month (21 Oct - 13 Nov 2026), descriptive, frozen F5 models

**R72 Breadth Donchian**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 126 | 61.9% | Rs 9,481 | Rs 1,075 | -Rs 1,549 | -Rs 6,294 (2026-11) | Rs 24,005 |
| U1 network skip | 126 | 61.9% | Rs 9,481 | Rs 1,075 | -Rs 1,549 | -Rs 6,294 (2026-11) | Rs 24,005 |
| U2 network exit choice | 126 | 61.9% | Rs 7,493 | Rs 1,050 | -Rs 1,549 | -Rs 6,294 (2026-11) | Rs 24,005 |
| U3 skip + exit choice | 126 | 61.9% | Rs 7,493 | Rs 1,050 | -Rs 1,549 | -Rs 6,294 (2026-11) | Rs 24,005 |
| base without R151's picker | 186 | 60.8% | Rs 7,869 | Rs 1,065 | -Rs 1,541 | -Rs 9,399 (2026-11) | Rs 30,358 |
| U4 network picker (no R151 picker) | 112 | 63.4% | Rs 10,937 | Rs 1,129 | -Rs 1,688 | -Rs 2,755 (2026-11) | Rs 24,899 |

**R51 Donchian 20 + Supertrend**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 142 | 69.0% | Rs 25,448 | Rs 1,121 | -Rs 1,917 | Rs 12,216 (2026-10) | Rs 15,820 |
| U1 network skip | 142 | 69.0% | Rs 25,448 | Rs 1,121 | -Rs 1,917 | Rs 12,216 (2026-10) | Rs 15,820 |
| U2 network exit choice | 131 | 64.9% | Rs 28,793 | Rs 1,343 | -Rs 1,855 | Rs 14,338 (2026-10) | Rs 19,726 |
| U3 skip + exit choice | 131 | 64.9% | Rs 28,793 | Rs 1,343 | -Rs 1,855 | Rs 14,338 (2026-10) | Rs 19,726 |
| base without R151's picker | 236 | 64.0% | Rs 6,824 | Rs 1,037 | -Rs 1,761 | -Rs 5,753 (2026-10) | Rs 22,760 |
| U4 network picker (no R151 picker) | 136 | 66.2% | Rs 16,040 | Rs 1,162 | -Rs 1,925 | Rs 4,807 (2026-10) | Rs 16,766 |

**Liquidity 15+5**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 43 | 62.8% | Rs 4,575 | Rs 901 | -Rs 1,235 | Rs 2,014 (2026-10) | Rs 7,345 |
| U1 network skip | 40 | 62.5% | Rs 4,658 | Rs 922 | -Rs 1,226 | Rs 2,096 (2026-10) | Rs 7,263 |
| U2 network exit choice | 43 | 62.8% | Rs 4,575 | Rs 901 | -Rs 1,235 | Rs 2,014 (2026-10) | Rs 7,345 |
| U3 skip + exit choice | 40 | 62.5% | Rs 4,658 | Rs 922 | -Rs 1,226 | Rs 2,096 (2026-10) | Rs 7,263 |

**ORB**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 18 | 50.0% | Rs 2,430 | Rs 1,112 | -Rs 842 | -Rs 1,764 (2026-11) | Rs 2,318 |
| U1 network skip | 6 | 50.0% | Rs 5,397 | Rs 2,521 | -Rs 722 | Rs 1,481 (2026-11) | Rs 1,577 |
| U2 network exit choice | 18 | 50.0% | Rs 2,430 | Rs 1,112 | -Rs 842 | -Rs 1,764 (2026-11) | Rs 2,318 |
| U3 skip + exit choice | 12 | 41.7% | Rs 1,960 | Rs 1,589 | -Rs 855 | -Rs 414 (2026-11) | Rs 3,347 |

**ORB Fresh**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 18 | 50.0% | Rs 2,430 | Rs 1,112 | -Rs 842 | -Rs 1,764 (2026-11) | Rs 2,318 |
| U1 network skip | 6 | 50.0% | Rs 5,397 | Rs 2,521 | -Rs 722 | Rs 1,481 (2026-11) | Rs 1,577 |
| U2 network exit choice | 18 | 50.0% | Rs 2,430 | Rs 1,112 | -Rs 842 | -Rs 1,764 (2026-11) | Rs 2,318 |
| U3 skip + exit choice | 12 | 41.7% | Rs 1,960 | Rs 1,589 | -Rs 855 | -Rs 414 (2026-11) | Rs 3,347 |

**ORB Sweep**

| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |
|---|---|---|---|---|---|---|---|
| base (the app today) | 25 | 24.0% | -Rs 9,033 | Rs 1,116 | -Rs 828 | -Rs 6,340 (2026-10) | Rs 12,163 |
| U1 network skip | 25 | 24.0% | -Rs 9,033 | Rs 1,116 | -Rs 828 | -Rs 6,340 (2026-10) | Rs 12,163 |
| U2 network exit choice | 25 | 24.0% | -Rs 9,033 | Rs 1,116 | -Rs 828 | -Rs 6,340 (2026-10) | Rs 12,163 |
| U3 skip + exit choice | 25 | 24.0% | -Rs 9,033 | Rs 1,116 | -Rs 828 | -Rs 6,340 (2026-10) | Rs 12,163 |

