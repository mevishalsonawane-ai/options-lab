## Matrix Premium v2.2 TP / SL framework, BANKNIFTY, buying ATM options (research/matrix_v22.py)

Entry = a close through the last swing high / low (BOS or CHoCH); SL = pivot +/- buf x ATR; TP1 half out + breakeven; rest fixed 2R / lock at 2R to TP3 4R / ATR ribbon; CHoCH against = exit; 15:10 square-off. 2 lots, real option minutes, Rs 40 per lot round trip.

### 2025-02-17 .. 2026-02-23 (249 days)

| version | trades | win (Rs) | TP1 hit | avg R (index) | per trade (2 lots) | t | net (2 lots) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|---|
| 5-min, SL pivot 1.5xATR, TP1 1.0R, fixed | 608 (2.4/day) | 36% | 15% | -0.03 | Rs -380 | -2.02 | Rs -230,966 | -125,470 / -105,496 | 4/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.0R, lock | 605 (2.4/day) | 36% | 15% | -0.03 | Rs -376 | -2.01 | Rs -227,650 | -118,720 / -108,930 | 4/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.0R, ribbon | 612 (2.5/day) | 36% | 15% | -0.04 | Rs -377 | -2.03 | Rs -230,688 | -120,451 / -110,237 | 4/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.5R, fixed | 608 (2.4/day) | 35% | 6% | -0.03 | Rs -444 | -2.35 | Rs -270,030 | -148,700 / -121,330 | 4/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.5R, lock | 604 (2.4/day) | 35% | 6% | -0.03 | Rs -430 | -2.26 | Rs -259,511 | -135,644 / -123,868 | 4/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.5R, ribbon | 607 (2.4/day) | 35% | 6% | -0.04 | Rs -444 | -2.36 | Rs -269,608 | -140,349 / -129,259 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.0R, fixed | 606 (2.4/day) | 35% | 11% | -0.03 | Rs -411 | -2.19 | Rs -249,118 | -138,041 / -111,077 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.0R, lock | 605 (2.4/day) | 35% | 11% | -0.03 | Rs -404 | -2.15 | Rs -244,240 | -135,665 / -108,575 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.0R, ribbon | 610 (2.4/day) | 35% | 11% | -0.03 | Rs -404 | -2.17 | Rs -246,224 | -136,980 / -109,244 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.5R, fixed | 606 (2.4/day) | 35% | 5% | -0.03 | Rs -444 | -2.35 | Rs -269,022 | -147,868 / -121,154 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.5R, lock | 605 (2.4/day) | 35% | 5% | -0.03 | Rs -440 | -2.32 | Rs -266,084 | -144,492 / -121,592 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.5R, ribbon | 607 (2.4/day) | 35% | 5% | -0.03 | Rs -445 | -2.37 | Rs -270,338 | -146,256 / -124,082 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.0R, fixed | 605 (2.4/day) | 35% | 9% | -0.03 | Rs -394 | -2.06 | Rs -238,450 | -139,565 / -98,885 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.0R, lock | 604 (2.4/day) | 35% | 9% | -0.03 | Rs -392 | -2.04 | Rs -236,577 | -138,621 / -97,956 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.0R, ribbon | 608 (2.4/day) | 35% | 9% | -0.03 | Rs -392 | -2.07 | Rs -238,135 | -139,863 / -98,272 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.5R, fixed | 605 (2.4/day) | 35% | 3% | -0.03 | Rs -448 | -2.36 | Rs -270,894 | -145,804 / -125,090 | 5/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.5R, lock | 604 (2.4/day) | 35% | 3% | -0.03 | Rs -450 | -2.38 | Rs -271,835 | -144,910 / -126,924 | 5/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.5R, ribbon | 607 (2.4/day) | 35% | 3% | -0.03 | Rs -459 | -2.45 | Rs -278,630 | -149,254 / -129,376 | 4/13 |
| 5-min, CHoCH only, SL 2xATR, TP1 1R, lock | 481 (1.9/day) | 36% | 13% | -0.02 | Rs -278 | -1.36 | Rs -133,955 | -91,755 / -42,200 | 5/13 |
| 5-min, tight SL pivot 0.5xATR (outside the spec), TP1 1R, lock | 610 (2.4/day) | 38% | 22% | -0.05 | Rs -395 | -2.20 | Rs -241,104 | -138,485 / -102,619 | 3/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.0R, fixed | 285 (1.1/day) | 42% | 4% | -0.01 | Rs -459 | -1.26 | Rs -130,802 | -95,566 / -35,236 | 5/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.0R, lock | 285 (1.1/day) | 42% | 4% | -0.01 | Rs -459 | -1.26 | Rs -130,802 | -95,566 / -35,236 | 5/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.0R, ribbon | 285 (1.1/day) | 42% | 4% | -0.01 | Rs -456 | -1.25 | Rs -129,990 | -95,313 / -34,678 | 5/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.5R, fixed | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -488 | -1.35 | Rs -139,011 | -97,891 / -41,120 | 5/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.5R, lock | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -488 | -1.35 | Rs -139,011 | -97,891 / -41,120 | 5/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.5R, ribbon | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -487 | -1.34 | Rs -138,758 | -97,638 / -41,120 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.0R, fixed | 285 (1.1/day) | 42% | 2% | -0.01 | Rs -478 | -1.31 | Rs -136,358 | -98,451 / -37,907 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.0R, lock | 285 (1.1/day) | 42% | 2% | -0.01 | Rs -478 | -1.31 | Rs -136,358 | -98,451 / -37,907 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.0R, ribbon | 285 (1.1/day) | 42% | 2% | -0.01 | Rs -478 | -1.31 | Rs -136,104 | -98,197 / -37,907 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.5R, fixed | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -503 | -1.39 | Rs -143,451 | -102,037 / -41,414 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.5R, lock | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -503 | -1.39 | Rs -143,451 | -102,037 / -41,414 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.5R, ribbon | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -503 | -1.39 | Rs -143,451 | -102,037 / -41,414 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.0R, fixed | 285 (1.1/day) | 42% | 1% | -0.01 | Rs -497 | -1.37 | Rs -141,525 | -100,858 / -40,667 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.0R, lock | 285 (1.1/day) | 42% | 1% | -0.01 | Rs -497 | -1.37 | Rs -141,525 | -100,858 / -40,667 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.0R, ribbon | 285 (1.1/day) | 42% | 1% | -0.01 | Rs -496 | -1.36 | Rs -141,272 | -100,604 / -40,667 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.5R, fixed | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -507 | -1.40 | Rs -144,603 | -103,189 / -41,414 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.5R, lock | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -507 | -1.40 | Rs -144,603 | -103,189 / -41,414 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.5R, ribbon | 285 (1.1/day) | 42% | 0% | -0.01 | Rs -507 | -1.40 | Rs -144,603 | -103,189 / -41,414 | 5/13 |
| 15-min, CHoCH only, SL 2xATR, TP1 1R, lock | 177 (0.7/day) | 41% | 3% | -0.01 | Rs -314 | -0.65 | Rs -55,538 | -38,499 / -17,039 | 5/13 |
| 15-min, tight SL pivot 0.5xATR (outside the spec), TP1 1R, lock | 284 (1.1/day) | 43% | 12% | -0.01 | Rs -368 | -1.02 | Rs -104,443 | -71,995 / -32,448 | 6/13 |

5-min default exits: choch 70%, 15:10 28%, stop 1%, breakeven 1%; median risk 265 index pts, median hold 95 min
### 2024-02-13 .. 2025-02-14 (249 days)

| version | trades | win (Rs) | TP1 hit | avg R (index) | per trade (2 lots) | t | net (2 lots) | 1st / 2nd half | green months |
|---|---|---|---|---|---|---|---|---|---|
| 5-min, SL pivot 1.5xATR, TP1 1.0R, fixed | 583 (2.3/day) | 30% | 16% | -0.01 | Rs -217 | -0.64 | Rs -126,302 | -4,282 / -122,020 | 5/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.0R, lock | 580 (2.3/day) | 30% | 17% | -0.02 | Rs -331 | -1.06 | Rs -192,154 | -60,329 / -131,825 | 4/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.0R, ribbon | 587 (2.4/day) | 30% | 17% | -0.01 | Rs -366 | -1.22 | Rs -214,753 | -89,274 / -125,479 | 4/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.5R, fixed | 583 (2.3/day) | 29% | 7% | -0.02 | Rs -298 | -0.83 | Rs -173,872 | -33,106 / -140,766 | 5/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.5R, lock | 580 (2.3/day) | 29% | 7% | -0.02 | Rs -353 | -1.03 | Rs -204,821 | -62,420 / -142,401 | 5/13 |
| 5-min, SL pivot 1.5xATR, TP1 1.5R, ribbon | 583 (2.3/day) | 29% | 7% | -0.02 | Rs -380 | -1.12 | Rs -221,458 | -70,056 / -151,401 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.0R, fixed | 581 (2.3/day) | 29% | 14% | -0.02 | Rs -386 | -1.23 | Rs -224,157 | -76,490 / -147,667 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.0R, lock | 580 (2.3/day) | 29% | 14% | -0.02 | Rs -400 | -1.28 | Rs -231,901 | -90,823 / -141,078 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.0R, ribbon | 585 (2.3/day) | 30% | 14% | -0.02 | Rs -349 | -1.09 | Rs -204,278 | -78,156 / -126,122 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.5R, fixed | 581 (2.3/day) | 28% | 6% | -0.02 | Rs -382 | -1.12 | Rs -222,114 | -61,885 / -160,229 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.5R, lock | 580 (2.3/day) | 28% | 6% | -0.02 | Rs -385 | -1.13 | Rs -223,589 | -70,288 / -153,302 | 4/13 |
| 5-min, SL pivot 2.0xATR, TP1 1.5R, ribbon | 582 (2.3/day) | 29% | 6% | -0.02 | Rs -347 | -0.99 | Rs -201,825 | -52,322 / -149,503 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.0R, fixed | 581 (2.3/day) | 29% | 11% | -0.02 | Rs -399 | -1.25 | Rs -231,556 | -70,970 / -160,586 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.0R, lock | 580 (2.3/day) | 29% | 11% | -0.02 | Rs -406 | -1.28 | Rs -235,535 | -81,045 / -154,490 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.0R, ribbon | 584 (2.3/day) | 29% | 11% | -0.02 | Rs -370 | -1.14 | Rs -215,818 | -72,310 / -143,508 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.5R, fixed | 581 (2.3/day) | 28% | 4% | -0.02 | Rs -351 | -1.00 | Rs -203,782 | -54,301 / -149,482 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.5R, lock | 580 (2.3/day) | 28% | 4% | -0.02 | Rs -347 | -1.00 | Rs -201,236 | -61,669 / -139,568 | 4/13 |
| 5-min, SL pivot 2.5xATR, TP1 1.5R, ribbon | 581 (2.3/day) | 29% | 4% | -0.02 | Rs -300 | -0.83 | Rs -174,198 | -46,037 / -128,161 | 5/13 |
| 5-min, CHoCH only, SL 2xATR, TP1 1R, lock | 441 (1.8/day) | 30% | 14% | -0.03 | Rs -495 | -1.55 | Rs -218,301 | -120,236 / -98,066 | 4/13 |
| 5-min, tight SL pivot 0.5xATR (outside the spec), TP1 1R, lock | 586 (2.4/day) | 32% | 24% | -0.03 | Rs -462 | -1.62 | Rs -270,749 | -122,108 / -148,641 | 4/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.0R, fixed | 285 (1.1/day) | 37% | 9% | +0.00 | Rs -573 | -0.94 | Rs -163,329 | -158,215 / -5,114 | 6/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.0R, lock | 285 (1.1/day) | 37% | 9% | +0.00 | Rs -589 | -0.97 | Rs -167,988 | -158,215 / -9,773 | 6/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.0R, ribbon | 286 (1.1/day) | 37% | 9% | +0.00 | Rs -518 | -0.83 | Rs -148,110 | -149,819 / +1,709 | 6/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.5R, fixed | 285 (1.1/day) | 36% | 3% | +0.00 | Rs -636 | -1.02 | Rs -181,363 | -169,298 / -12,065 | 5/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.5R, lock | 285 (1.1/day) | 36% | 3% | -0.00 | Rs -652 | -1.05 | Rs -185,815 | -169,298 / -16,517 | 5/13 |
| 15-min, SL pivot 1.5xATR, TP1 1.5R, ribbon | 285 (1.1/day) | 36% | 3% | -0.00 | Rs -651 | -1.05 | Rs -185,397 | -169,298 / -16,098 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.0R, fixed | 285 (1.1/day) | 36% | 7% | +0.00 | Rs -559 | -0.91 | Rs -159,432 | -168,553 / +9,121 | 6/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.0R, lock | 285 (1.1/day) | 36% | 7% | +0.00 | Rs -583 | -0.95 | Rs -166,102 | -168,553 / +2,450 | 6/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.0R, ribbon | 286 (1.1/day) | 36% | 7% | +0.00 | Rs -520 | -0.83 | Rs -148,652 | -162,584 / +13,932 | 6/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.5R, fixed | 285 (1.1/day) | 36% | 2% | +0.00 | Rs -624 | -1.00 | Rs -177,846 | -166,139 / -11,706 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.5R, lock | 285 (1.1/day) | 36% | 2% | -0.00 | Rs -646 | -1.04 | Rs -184,140 | -166,139 / -18,000 | 5/13 |
| 15-min, SL pivot 2.0xATR, TP1 1.5R, ribbon | 285 (1.1/day) | 36% | 2% | -0.00 | Rs -645 | -1.03 | Rs -183,891 | -166,139 / -17,752 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.0R, fixed | 285 (1.1/day) | 36% | 5% | +0.00 | Rs -576 | -0.93 | Rs -164,295 | -165,604 / +1,309 | 6/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.0R, lock | 285 (1.1/day) | 36% | 5% | +0.00 | Rs -595 | -0.96 | Rs -169,662 | -165,604 / -4,058 | 6/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.0R, ribbon | 286 (1.1/day) | 36% | 5% | +0.00 | Rs -532 | -0.84 | Rs -152,211 | -159,635 / +7,424 | 6/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.5R, fixed | 285 (1.1/day) | 36% | 1% | -0.00 | Rs -622 | -0.99 | Rs -177,301 | -166,810 / -10,491 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.5R, lock | 285 (1.1/day) | 36% | 1% | -0.00 | Rs -641 | -1.03 | Rs -182,668 | -166,810 / -15,858 | 5/13 |
| 15-min, SL pivot 2.5xATR, TP1 1.5R, ribbon | 285 (1.1/day) | 36% | 1% | -0.00 | Rs -639 | -1.02 | Rs -182,043 | -166,810 / -15,233 | 5/13 |
| 15-min, CHoCH only, SL 2xATR, TP1 1R, lock | 176 (0.7/day) | 40% | 8% | +0.02 | Rs +127 | 0.15 | Rs +22,267 | -14,870 / +37,137 | 6/13 |
| 15-min, tight SL pivot 0.5xATR (outside the spec), TP1 1R, lock | 288 (1.2/day) | 37% | 15% | -0.01 | Rs -721 | -1.26 | Rs -207,564 | -173,742 / -33,822 | 7/13 |

5-min default exits: choch 67%, 15:10 29%, stop 2%, breakeven 1%, trail 0%; median risk 332 index pts, median hold 100 min