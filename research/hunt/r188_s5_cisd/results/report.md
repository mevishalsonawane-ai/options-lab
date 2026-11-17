# R188: can S5 Trail and CISD + network make more money and win more often? - full tables

17 Nov 2026. Research only: nothing in the app changes. PREREG.md (pushed before any number). 1 lot at R123's lots, dated Zerodha charges, liquidity-aware fills (Fills('liq')), 2x slippage shown. Signal source: the index (S5: R123's network on the index, India VIX, breadth and option-chain context; CISD: the index's 5-minute bars); stops and locks on the option premium, IDX and CISD's stop on the index; FT3PO reads the bought option's price and OI as context.

Base = each arm as the app runs it today: R128 / R133's rules with the app's 15:15 square-off (S5: 15:05 on expiry day), one trade at a time per index (S5's index busy until its own exit; CISD's refused setups still hold the index). Every lever is on top of the base; whichever exit comes first wins.

## Parity (before any lever)

| check | R188 | published / saved | match |
|---|---|---|---|
| R123 S5 trade list rebuilt from the saved predictions | 596 | 596 | identical |
| S5 fillable picks (used for the new month) vs R123's candidates | 982 | 982 | identical |
| R128 T2-S5x1 walk-forward | 555 trades, 58.7%, Rs 240 a trade, worst month -Rs 18,596 (2024-09) | 555, 59%, +Rs 240, -Rs 18.6k | exact |
| R128 T2-S5x1 holdout | 41 trades, 82.9%, Rs 561 | 41, 83%, +Rs 561 | exact |
| R128 per-trade nets | 596 of 596 matched, max difference Rs 0.00 | R128 t2.parquet | exact |
| R133 S3-V2-E3 design | 413 kept of 3291, 68.5%, Rs 261 a trade | 413 of 3,291, 69%, +Rs 261 | exact |
| R133 S3-V2-E3 holdout (seen) | 35 kept, 71.4%, Rs 77 | 35, +Rs 77 | exact |
| R133 per-trade nets (all 3,871 simulated setups) | 3,871 matched, max difference Rs 0.00 | R133 trades_*.parquet | exact |
| R123 N1 rebuilt (R134 nnlive) on 1 - 19 Oct 2026 | 4,080 rows, max EV difference 0.0e+00, sign agreement 100% | R123's saved holdout predictions | exact |

## Gate verdicts (BH m = 4,925: 4,902 earlier + 23 here)

| arm | lever | design: net / win / risk | final: net / win / risk | final trades >= 30 | twin p | BH q | gates 1-3 | passed |
|---|---|---|---|---|---|---|---|---|
| S5 | BE10 | no / yes / yes | no / yes / no | yes (41) | 1.000 | 1.00 | no | no |
| S5 | L500 | no / yes / yes | yes / no / yes | yes (41) | 0.970 | 1.00 | no | no |
| S5 | LBE1000 | yes / yes / yes | no / no / no | yes (40) | 1.000 | 1.00 | no | no |
| S5 | IDX | no / no / yes | no / no / no | yes (40) | 0.996 | 1.00 | no | no |
| S5 | LK500 | no / yes / yes | no / yes / no | yes (40) | 1.000 | 1.00 | no | no |
| S5 | ST1500 | yes / no / no | no / no / no | yes (40) | 1.000 | 1.00 | no | no |
| S5 | FT3PO | yes / yes / no | yes / no / no | yes (40) | 0.090 | 1.00 | no | no |
| S5 | VIX313 | no / yes / yes | yes / yes / no | yes (31) | 0.437 | 1.00 | no | no |
| S5 | EXP | no / yes / yes | no / yes / no | no (28) | 0.100 | 1.00 | no | no |
| S5 | ADX20 | no / no / yes | no / yes / yes | no (25) | 0.996 | 1.00 | no | no |
| S5 | COMBO | not formed (fewer than 2 levers passed the design gates) | | | | 1.00 | no | no |
| CISD | BE10 | yes / yes / yes | no / no / no | yes (35) | 0.022 | 1.00 | no | no |
| CISD | L500 | yes / no / yes | yes / no / yes | yes (35) | 0.002 | 1.00 | no | no |
| CISD | LBE1000 | yes / no / no | yes / no / no | yes (35) | 1.000 | 1.00 | no | no |
| CISD | IDX | yes / no / no | yes / no / no | yes (35) | 1.000 | 1.00 | no | no |
| CISD | LK500 | no / yes / yes | no / no / no | yes (35) | 0.002 | 1.00 | no | no |
| CISD | ST1500 | no / no / yes | no / no / yes | yes (36) | 1.000 | 1.00 | no | no |
| CISD | FT3PO | no / no / no | yes / no / no | yes (35) | 0.808 | 1.00 | no | no |
| CISD | VIX313 | no / yes / yes | yes / yes / no | yes (31) | 0.060 | 1.00 | no | no |
| CISD | EXP | yes / yes / yes | yes / yes / no | yes (34) | 0.014 | 1.00 | no | no |
| CISD | ADX20 | no / yes / yes | no / no / yes | no (26) | 0.044 | 1.00 | no | no |
| CISD | NTHR | no / yes / yes | yes / yes / yes | no (10) | 0.008 | 1.00 | no | no |
| CISD | COMBO | yes / yes / yes | no / no / no | yes (34) | 0.004 | 1.00 | no | no |

**Gate 4 cannot be met (said in PREREG.md):** with 500 twins the smallest p is 1/501, so q >= 0.43 for every test. Gates 1-3 are what can show a lead.

## Design choices (design window only)

- Levers passing every design gate: S5 LBE1000; CISD BE10, EXP.
- COMBO: S5 not formed (one lever); CISD BE10 + EXP.
- NTHR (CISD): T = +50 (the highest design net among thresholds whose design win rate beat the base's). The grid on the design window:

| T | trades | win rate | net |
|---|---|---|---|
| -100 | 1403 | 64.3% | Rs 115,124 |
| -50 | 769 | 65.7% | Rs 112,415 |
| +50 | 158 | 77.2% | Rs 69,711 |
| +100 | 82 | 82.9% | Rs 62,721 |
| +150 | 52 | 84.6% | Rs 49,635 |
| +200 | 33 | 87.9% | Rs 35,999 |
| 0 (base) | 413 | 67.6% | Rs 105,090 |

## Tables per arm

### S5: Design (Jan 2023 - 15 Mar 2026)

| rule | trades | win rate | net | avg win | avg loss | worst month | max drawdown | net at 2x slippage / account stop |
|---|---|---|---|---|---|---|---|---|
| base (the app today) | 562 | 58.5% | Rs 128,731 | Rs 1,701 | -Rs 1,850 | -Rs 19,306 (2024-04) | -Rs 49,469 | Rs 119,476 / BREACHED |
| BE10 breakeven at +10% | 600 | 66.3% | Rs 99,014 | Rs 1,028 | -Rs 1,536 | -Rs 12,556 (2024-04) | -Rs 37,434 | Rs 88,442 / ok |
| L500 step lock | 584 | 66.4% | Rs 42,251 | Rs 1,071 | -Rs 1,904 | -Rs 17,178 (2024-04) | -Rs 44,191 | Rs 29,988 / BREACHED |
| LBE1000 breakeven at +Rs 1,000 | 573 | 64.7% | Rs 132,518 | Rs 1,352 | -Rs 1,826 | -Rs 18,649 (2024-04) | -Rs 43,368 | Rs 122,499 / BREACHED |
| IDX index lock | 570 | 56.0% | Rs 124,760 | Rs 1,640 | -Rs 1,587 | -Rs 20,198 (2024-04) | -Rs 47,014 | Rs 115,080 / BREACHED |
| LK500 60% lock from +Rs 500 | 591 | 63.8% | Rs 102,704 | Rs 1,175 | -Rs 1,590 | -Rs 14,524 (2024-06) | -Rs 40,785 | Rs 93,407 / BREACHED |
| ST1500 stop -Rs 1,500 | 579 | 52.8% | Rs 134,375 | Rs 1,726 | -Rs 1,443 | -Rs 22,219 (2024-09) | -Rs 50,906 | Rs 124,562 / BREACHED |
| FT3PO price and OI fell skip | 560 | 58.8% | Rs 132,725 | Rs 1,701 | -Rs 1,848 | -Rs 19,306 (2024-04) | -Rs 49,469 | Rs 123,519 / BREACHED |
| VIX313 VIX down >3.13% skip | 511 | 59.1% | Rs 117,405 | Rs 1,678 | -Rs 1,863 | -Rs 17,510 (2024-09) | -Rs 36,079 | Rs 109,228 / BREACHED |
| EXP expiry-day skip | 389 | 62.0% | Rs 119,376 | Rs 1,659 | -Rs 1,894 | -Rs 11,956 (2024-04) | -Rs 28,872 | Rs 112,144 / ok |
| ADX20 sideways skip | 370 | 54.1% | -Rs 7,740 | Rs 1,507 | -Rs 1,819 | -Rs 19,269 (2024-05) | -Rs 82,618 | -Rs 13,884 / BREACHED |

### S5: Final (1 Apr - 19 Oct 2026, base seen before)

| rule | trades | win rate | net | avg win | avg loss | worst month | max drawdown | net at 2x slippage / account stop |
|---|---|---|---|---|---|---|---|---|
| base (the app today) | 40 | 85.0% | Rs 24,988 | Rs 1,070 | -Rs 1,897 | -Rs 2,328 (2026-04) | -Rs 5,112 | Rs 24,333 / ok |
| BE10 breakeven at +10% | 41 | 85.4% | Rs 16,319 | Rs 679 | -Rs 1,243 | -Rs 2,328 (2026-04) | -Rs 5,112 | Rs 15,575 / ok |
| L500 step lock | 41 | 82.9% | Rs 32,646 | Rs 1,354 | -Rs 1,913 | -Rs 2,078 (2026-04) | -Rs 5,112 | Rs 31,747 / ok |
| LBE1000 breakeven at +Rs 1,000 | 40 | 85.0% | Rs 22,656 | Rs 1,001 | -Rs 1,899 | -Rs 2,328 (2026-04) | -Rs 5,112 | Rs 21,966 / ok |
| IDX index lock | 40 | 85.0% | Rs 23,554 | Rs 1,027 | -Rs 1,897 | -Rs 2,328 (2026-04) | -Rs 5,112 | Rs 22,893 / ok |
| LK500 60% lock from +Rs 500 | 40 | 87.5% | Rs 19,281 | Rs 826 | -Rs 1,928 | -Rs 2,825 (2026-04) | -Rs 5,112 | Rs 18,635 / ok |
| ST1500 stop -Rs 1,500 | 40 | 80.0% | Rs 22,461 | Rs 1,101 | -Rs 1,597 | -Rs 2,328 (2026-04) | -Rs 5,112 | Rs 21,797 / ok |
| FT3PO price and OI fell skip | 40 | 85.0% | Rs 24,988 | Rs 1,070 | -Rs 1,897 | -Rs 2,328 (2026-04) | -Rs 5,112 | Rs 24,333 / ok |
| VIX313 VIX down >3.13% skip | 31 | 90.3% | Rs 25,497 | Rs 1,100 | -Rs 1,764 | -Rs 3,080 (2026-04) | -Rs 5,112 | Rs 24,999 / ok |
| EXP expiry-day skip | 28 | 85.7% | Rs 22,112 | Rs 1,225 | -Rs 1,822 | -Rs 2,328 (2026-04) | -Rs 5,112 | Rs 21,570 / ok |
| ADX20 sideways skip | 25 | 88.0% | Rs 18,847 | Rs 1,100 | -Rs 1,784 | -Rs 2,328 (2026-04) | -Rs 3,080 | Rs 18,433 / ok |

### S5: New month (21 Oct - 13 Nov 2026, descriptive)

| rule | trades | win rate | net | avg win | avg loss | worst month | max drawdown | net at 2x slippage / account stop |
|---|---|---|---|---|---|---|---|---|
| base (the app today) | 3 | 66.7% | Rs 3,849 | Rs 3,163 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | Rs 3,744 / ok |
| BE10 breakeven at +10% | 3 | 66.7% | -Rs 1,914 | Rs 281 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | -Rs 2,022 / ok |
| L500 step lock | 3 | 66.7% | -Rs 621 | Rs 927 | -Rs 2,476 | -Rs 1,123 (2026-11) | -Rs 2,476 | -Rs 744 / ok |
| LBE1000 breakeven at +Rs 1,000 | 3 | 66.7% | -Rs 1,914 | Rs 281 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | -Rs 2,022 / ok |
| IDX index lock | 3 | 33.3% | -Rs 2,429 | Rs 560 | -Rs 1,495 | -Rs 1,915 (2026-11) | -Rs 2,476 | -Rs 2,535 / ok |
| LK500 60% lock from +Rs 500 | 3 | 33.3% | -Rs 1,927 | Rs 560 | -Rs 1,244 | -Rs 1,915 (2026-11) | -Rs 2,476 | -Rs 2,026 / ok |
| ST1500 stop -Rs 1,500 | 3 | 66.7% | Rs 3,849 | Rs 3,163 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | Rs 3,744 / ok |
| FT3PO price and OI fell skip | 3 | 66.7% | Rs 3,849 | Rs 3,163 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | Rs 3,744 / ok |
| VIX313 VIX down >3.13% skip | 3 | 66.7% | Rs 3,849 | Rs 3,163 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | Rs 3,744 / ok |
| EXP expiry-day skip | 3 | 66.7% | Rs 3,849 | Rs 3,163 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | Rs 3,744 / ok |
| ADX20 sideways skip | 3 | 66.7% | Rs 3,849 | Rs 3,163 | -Rs 2,476 | -Rs 1,915 (2026-11) | -Rs 2,476 | Rs 3,744 / ok |

### CISD: Design (Jan 2023 - 15 Mar 2026)

| rule | trades | win rate | net | avg win | avg loss | worst month | max drawdown | net at 2x slippage / account stop |
|---|---|---|---|---|---|---|---|---|
| base (the app today) | 413 | 67.6% | Rs 105,090 | Rs 1,004 | -Rs 1,307 | -Rs 7,491 (2023-12) | -Rs 15,168 | Rs 96,346 / ok |
| BE10 breakeven at +10% | 427 | 73.8% | Rs 111,105 | Rs 787 | -Rs 1,223 | -Rs 5,112 (2023-12) | -Rs 7,478 | Rs 101,933 / ok |
| L500 step lock | 413 | 67.6% | Rs 120,729 | Rs 1,060 | -Rs 1,307 | -Rs 7,254 (2023-12) | -Rs 14,511 | Rs 111,969 / ok |
| LBE1000 breakeven at +Rs 1,000 | 413 | 67.6% | Rs 105,090 | Rs 1,004 | -Rs 1,307 | -Rs 7,491 (2023-12) | -Rs 15,168 | Rs 96,346 / ok |
| IDX index lock | 413 | 67.6% | Rs 105,090 | Rs 1,004 | -Rs 1,307 | -Rs 7,491 (2023-12) | -Rs 15,168 | Rs 96,346 / ok |
| LK500 60% lock from +Rs 500 | 437 | 76.0% | Rs 83,178 | Rs 610 | -Rs 1,137 | -Rs 5,163 (2024-04) | -Rs 6,857 | Rs 73,731 / ok |
| ST1500 stop -Rs 1,500 | 431 | 59.9% | Rs 66,568 | Rs 1,042 | -Rs 1,169 | -Rs 6,490 (2023-12) | -Rs 16,532 | Rs 57,298 / ok |
| FT3PO price and OI fell skip | 405 | 67.4% | Rs 100,018 | Rs 1,002 | -Rs 1,314 | -Rs 7,491 (2023-12) | -Rs 15,826 | Rs 91,473 / ok |
| VIX313 VIX down >3.13% skip | 350 | 68.3% | Rs 103,395 | Rs 1,037 | -Rs 1,300 | -Rs 6,145 (2023-12) | -Rs 13,154 | Rs 95,926 / ok |
| EXP expiry-day skip | 366 | 69.1% | Rs 114,080 | Rs 1,016 | -Rs 1,264 | -Rs 5,260 (2023-12) | -Rs 12,937 | Rs 106,427 / ok |
| ADX20 sideways skip | 290 | 71.4% | Rs 98,614 | Rs 1,068 | -Rs 1,476 | -Rs 5,906 (2023-12) | -Rs 14,374 | Rs 92,258 / ok |
| NTHR network threshold (T = +50) | 158 | 77.2% | Rs 69,711 | Rs 1,045 | -Rs 1,605 | -Rs 3,430 (2026-02) | -Rs 7,041 | Rs 66,145 / ok |
| COMBO (BE10 + EXP) | 379 | 76.0% | Rs 115,837 | Rs 784 | -Rs 1,207 | -Rs 4,228 (2023-12) | -Rs 6,552 | Rs 107,789 / ok |

### CISD: Final (1 Apr - 19 Oct 2026, base seen before)

| rule | trades | win rate | net | avg win | avg loss | worst month | max drawdown | net at 2x slippage / account stop |
|---|---|---|---|---|---|---|---|---|
| base (the app today) | 35 | 71.4% | Rs 3,150 | Rs 937 | -Rs 2,028 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 2,198 / ok |
| BE10 breakeven at +10% | 35 | 68.6% | Rs 2,072 | Rs 951 | -Rs 1,887 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 1,120 / ok |
| L500 step lock | 35 | 71.4% | Rs 6,156 | Rs 1,057 | -Rs 2,028 | -Rs 3,949 (2026-10) | -Rs 9,886 | Rs 5,201 / ok |
| LBE1000 breakeven at +Rs 1,000 | 35 | 71.4% | Rs 3,150 | Rs 937 | -Rs 2,028 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 2,198 / ok |
| IDX index lock | 35 | 71.4% | Rs 3,150 | Rs 937 | -Rs 2,028 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 2,198 / ok |
| LK500 60% lock from +Rs 500 | 35 | 71.4% | -Rs 4,936 | Rs 528 | -Rs 1,814 | -Rs 3,949 (2026-10) | -Rs 10,246 | -Rs 5,908 / ok |
| ST1500 stop -Rs 1,500 | 36 | 58.3% | -Rs 2,738 | Rs 967 | -Rs 1,536 | -Rs 2,446 (2026-09) | -Rs 7,351 | -Rs 3,791 / ok |
| FT3PO price and OI fell skip | 35 | 71.4% | Rs 3,150 | Rs 937 | -Rs 2,028 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 2,198 / ok |
| VIX313 VIX down >3.13% skip | 31 | 74.2% | Rs 5,168 | Rs 928 | -Rs 2,021 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 4,290 / ok |
| EXP expiry-day skip | 34 | 73.5% | Rs 3,420 | Rs 937 | -Rs 2,223 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 2,486 / ok |
| ADX20 sideways skip | 26 | 69.2% | Rs 143 | Rs 958 | -Rs 2,137 | -Rs 3,590 (2026-09) | -Rs 7,087 | -Rs 578 / ok |
| NTHR network threshold (T = +50) | 10 | 90.0% | Rs 7,757 | Rs 975 | -Rs 1,015 | -Rs 453 (2026-07) | -Rs 1,015 | Rs 7,503 / ok |
| COMBO (BE10 + EXP) | 34 | 70.6% | Rs 2,341 | Rs 951 | -Rs 2,049 | -Rs 3,949 (2026-10) | -Rs 10,002 | Rs 1,408 / ok |

### CISD: New month (21 Oct - 13 Nov 2026, descriptive)

| rule | trades | win rate | net | avg win | avg loss | worst month | max drawdown | net at 2x slippage / account stop |
|---|---|---|---|---|---|---|---|---|
| base (the app today) | 8 | 87.5% | Rs 6,585 | Rs 1,124 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 6,312 / ok |
| BE10 breakeven at +10% | 8 | 87.5% | Rs 6,585 | Rs 1,124 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 6,312 / ok |
| L500 step lock | 8 | 87.5% | Rs 7,631 | Rs 1,273 | -Rs 1,279 | Rs 1,138 (2026-10) | -Rs 1,279 | Rs 7,356 / ok |
| LBE1000 breakeven at +Rs 1,000 | 8 | 87.5% | Rs 6,585 | Rs 1,124 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 6,312 / ok |
| IDX index lock | 8 | 87.5% | Rs 6,585 | Rs 1,124 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 6,312 / ok |
| LK500 60% lock from +Rs 500 | 8 | 62.5% | Rs 80 | Rs 308 | -Rs 487 | -Rs 811 (2026-10) | -Rs 1,279 | -Rs 192 / ok |
| ST1500 stop -Rs 1,500 | 8 | 75.0% | Rs 3,694 | Rs 1,227 | -Rs 1,834 | Rs 251 (2026-10) | -Rs 3,158 | Rs 3,411 / ok |
| FT3PO price and OI fell skip | 8 | 87.5% | Rs 6,585 | Rs 1,124 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 6,312 / ok |
| VIX313 VIX down >3.13% skip | 3 | 66.7% | Rs 1,006 | Rs 1,143 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 943 / ok |
| EXP expiry-day skip | 8 | 87.5% | Rs 6,585 | Rs 1,124 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 6,312 / ok |
| ADX20 sideways skip | 4 | 75.0% | Rs 4,093 | Rs 1,791 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 4,006 / ok |
| NTHR network threshold (T = +50) | 4 | 100.0% | Rs 2,243 | Rs 561 | Rs 0 | Rs 510 (2026-10) | Rs 0 | Rs 2,082 / ok |
| COMBO (BE10 + EXP) | 8 | 87.5% | Rs 6,585 | Rs 1,124 | -Rs 1,279 | Rs 1,006 (2026-10) | -Rs 1,279 | Rs 6,312 / ok |

## The new month's base trades (21 Oct - 13 Nov 2026)

| arm | index | day | entry minute | side | net | exit |
|---|---|---|---|---|---|---|
| S5 | FINNIFTY | 2026-10-23 | 09:20 | put | Rs 5,765 | trail |
| S5 | FINNIFTY | 2026-11-06 | 09:20 | put | Rs 560 | trail |
| S5 | FINNIFTY | 2026-11-11 | 09:25 | put | -Rs 2,476 | stop |
| CISD | BANKNIFTY | 2026-11-09 | 11:15 | call | Rs 502 | lock |
| CISD | BANKNIFTY | 2026-11-09 | 13:50 | call | Rs 760 | lock |
| CISD | FINNIFTY | 2026-10-21 | 10:00 | put | Rs 1,775 | lock |
| CISD | FINNIFTY | 2026-10-22 | 13:30 | put | -Rs 1,279 | time |
| CISD | FINNIFTY | 2026-11-09 | 11:15 | call | Rs 583 | lock |
| CISD | FINNIFTY | 2026-11-09 | 14:15 | call | Rs 647 | lock |
| CISD | NIFTY | 2026-10-23 | 12:05 | put | Rs 510 | lock |
| CISD | SENSEX | 2026-11-09 | 11:30 | call | Rs 3,087 | lock |

Not priced in the new month: a CISD setup on a weekly expiry day needs the monthly contract, and the 16 Nov gap files have no monthly series.

## Exit reasons (design)

| arm | rule | exits |
|---|---|---|
| S5 | base | trail 345, stop 204, close 13 |
| S5 | BE10 | trail 229, be10 223, stop 140, close 8 |
| S5 | L500 | l500 384, stop 178, trail 17, close 5 |
| S5 | LBE1000 | trail 275, stop 174, lbe1000 113, close 11 |
| S5 | IDX | trail 312, stop 176, idx 70, close 12 |
| S5 | LK500 | trail 428, stop 154, close 9 |
| S5 | ST1500 | trail 321, stop 251, close 7 |
| S5 | FT3PO | trail 345, stop 202, close 13 |
| S5 | VIX313 | trail 316, stop 185, close 10 |
| S5 | EXP | trail 246, stop 134, close 9 |
| S5 | ADX20 | trail 215, stop 144, close 11 |
| CISD | base | lock 269, time 83, istop 61 |
| CISD | BE10 | lock 231, be10 92, time 58, istop 46 |
| CISD | L500 | lock 172, l500 98, time 82, istop 61 |
| CISD | LBE1000 | lock 269, time 83, istop 61 |
| CISD | IDX | lock 266, time 83, istop 61, idx 3 |
| CISD | LK500 | lock 347, time 46, istop 44 |
| CISD | ST1500 | lock 245, pstop 91, time 67, istop 28 |
| CISD | FT3PO | lock 263, time 81, istop 61 |
| CISD | VIX313 | lock 234, time 65, istop 51 |
| CISD | EXP | lock 242, time 72, istop 52 |
| CISD | ADX20 | lock 204, time 46, istop 40 |
| CISD | NTHR | lock 120, time 22, istop 16 |

Note (17 Nov): the first final pass compared drawdowns as raw floats, so CISD EXP's final drawdown, equal to the base's to the paisa, counted as smaller; gates were re-derived from the stored numbers comparing to the paisa (`r188study.py regate`; no data re-read).
