# R177-EMA: the 9/21 EMA crossover with the PDF's three regime filters (report)

Pre-registration: `PREREG.md` (committed before any number). Decision: `decision.json`. Signal source for every rule: **the index spot** (15-minute and 1-hour bars of NIFTY 50 / NIFTY BANK); the ATM (or 1-ITM) option of the nearest expiry is bought. VWAP weights: the stored option chain's traded volume (the index and the BANKNIFTY futures in the reachable data have no volume). All money is after R17 slippage and dated Zerodha charges; index stops are sold as stop orders (x0.999 minus twice the half-spread).

## Outcome

**No test passed the gates: 0 of 270** (10 rule sets x 3 index scopes x 3 side scopes x 1-3 lots). The full rule with all three filters is **rejected**.

## Data and its limits

- The repo's `research-data` release (R171's loader): NIFTY and BANKNIFTY index minutes and real 1-minute option prices (one expiry a day, 20 strikes). The Drive lake is not reachable from this session, so **nothing after 23 Feb 2026 is used** (R171's data-hole rule), and FINNIFTY / SENSEX / MIDCPNIFTY (no options here) are not traded.
- Periods: full period (6 May 2024 - 23 Feb 2026); design (6 May 2024 - 22 Aug 2025); last 6 months = final (25 Aug 2025 - 23 Feb 2026), read once.
- The index's own expiry days are skipped. Lots: the lot in force on the trade date (NIFTY 25 -> 75 from 28 Nov 2024 -> 65 from 31 Dec 2025; BANKNIFTY 15 -> 30 -> 35 -> 30).
- Signals with no fill (strike not stored or no trade in 3 minutes), full period: PLAIN 2, ADX 2, TREND 0, ATR 2, ALL 0, ALL-H1B 0, ALL-TWAP 0, ALL-OR 0, ALL-2R 0, ALL-ITM 0.

## 1. Does stacking help? Both indices, calls + puts, 1 lot

**full period (6 May 2024 - 23 Feb 2026)**

| rule | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| plain 9/21 crossover (no filter) | 660 | 37% | **-Rs 1.06 L** | Rs 2,507 / -Rs 1,714 | -Rs 24,269 | Rs 1.59 L | -Rs 45,982 |
| filter 1 alone: ADX(14) > 25 | 163 | 39% | **Rs 13,438** | Rs 3,263 / -Rs 1,922 | -Rs 20,441 | Rs 36,414 | -Rs 22,704 |
| filter 2 alone: 1-hour trend + VWAP | 234 | 33% | **-Rs 97,633** | Rs 2,157 / -Rs 1,680 | -Rs 20,441 | Rs 1.22 L | -Rs 32,398 |
| filter 3 alone: 2 x ATR index stop | 660 | 36% | **-Rs 1.15 L** | Rs 2,492 / -Rs 1,668 | -Rs 21,590 | Rs 1.64 L | -Rs 46,169 |
| all three together (the PDF's full rule) | 40 | 30% | **-Rs 26,183** | Rs 2,584 / -Rs 2,043 | -Rs 15,972 | Rs 34,687 | -Rs 23,746 |
| all three, 1-hour trend as EMA 9 > 21 | 30 | 27% | **-Rs 23,576** | Rs 1,722 / -Rs 1,698 | -Rs 8,893 | Rs 23,576 | -Rs 13,311 |
| all three, TWAP instead of the volume-weighted VWAP | 40 | 30% | **-Rs 26,968** | Rs 2,584 / -Rs 2,071 | -Rs 15,972 | Rs 35,472 | -Rs 23,746 |
| all three + clearing the opening range | 17 | 35% | **-Rs 12,753** | Rs 3,215 / -Rs 2,913 | -Rs 13,241 | Rs 23,221 | -Rs 18,382 |
| all three + a 2R target | 40 | 32% | **-Rs 16,939** | Rs 3,018 / -Rs 2,080 | -Rs 15,972 | Rs 33,772 | -Rs 23,746 |
| all three, 1-ITM strike | 40 | 30% | **-Rs 27,719** | Rs 2,869 / -Rs 2,220 | -Rs 17,412 | Rs 38,253 | -Rs 26,222 |

**last 6 months = final (25 Aug 2025 - 23 Feb 2026)**

| rule | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| plain 9/21 crossover (no filter) | 174 | 40% | **-Rs 3,703** | Rs 2,576 / -Rs 1,728 | -Rs 20,302 | Rs 55,113 | -Rs 23,359 |
| filter 1 alone: ADX(14) > 25 | 47 | 38% | **-Rs 3,259** | Rs 2,947 / -Rs 1,941 | -Rs 10,629 | Rs 29,107 | -Rs 11,454 |
| filter 2 alone: 1-hour trend + VWAP | 58 | 33% | **-Rs 10,810** | Rs 2,553 / -Rs 1,521 | -Rs 8,513 | Rs 37,790 | -Rs 8,172 |
| filter 3 alone: 2 x ATR index stop | 174 | 40% | **-Rs 1,845** | Rs 2,576 / -Rs 1,710 | -Rs 17,763 | Rs 52,951 | -Rs 21,699 |
| all three together (the PDF's full rule) | 8 | 12% | **-Rs 6,892** | Rs 2,502 / -Rs 1,342 | -Rs 2,622 | Rs 8,867 | -Rs 4,514 |
| all three, 1-hour trend as EMA 9 > 21 | 9 | 11% | **-Rs 7,698** | Rs 2,502 / -Rs 1,275 | -Rs 2,622 | Rs 9,673 | -Rs 5,320 |
| all three, TWAP instead of the volume-weighted VWAP | 8 | 12% | **-Rs 6,892** | Rs 2,502 / -Rs 1,342 | -Rs 2,622 | Rs 8,867 | -Rs 4,514 |
| all three + clearing the opening range | 2 | 0% | **-Rs 1,941** | Rs 0 / -Rs 970 | -Rs 1,941 | Rs 1,941 | -Rs 1,941 |
| all three + a 2R target | 8 | 12% | **-Rs 6,892** | Rs 2,502 / -Rs 1,342 | -Rs 2,622 | Rs 8,867 | -Rs 4,514 |
| all three, 1-ITM strike | 8 | 12% | **-Rs 7,594** | Rs 2,982 / -Rs 1,511 | -Rs 2,981 | Rs 10,039 | -Rs 5,249 |

## 2. Net per index and lot (calls + puts)

### plain 9/21 crossover (no filter)

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 319 | 35% | **-Rs 49,008** | Rs 2,597 / -Rs 1,662 | -Rs 12,034 | Rs 83,502 | -Rs 35,461 |
| NIFTY | 2 | 319 | 35% | **-Rs 82,960** | Rs 5,241 / -Rs 3,278 | -Rs 23,927 | Rs 1.63 L | -Rs 70,119 |
| NIFTY | 3 | 319 | 35% | **-Rs 1.17 L** | Rs 7,885 / -Rs 4,893 | -Rs 35,820 | Rs 2.42 L | -Rs 1.05 L |
| BANKNIFTY | 1 | 341 | 38% | **-Rs 56,532** | Rs 2,428 / -Rs 1,764 | -Rs 12,742 | Rs 83,429 | -Rs 30,208 |
| BANKNIFTY | 2 | 341 | 38% | **-Rs 96,969** | Rs 4,903 / -Rs 3,481 | -Rs 25,247 | Rs 1.57 L | -Rs 59,331 |
| BANKNIFTY | 3 | 341 | 38% | **-Rs 1.37 L** | Rs 7,323 / -Rs 5,222 | -Rs 37,753 | Rs 2.30 L | -Rs 88,453 |
| both indices | 1 | 660 | 37% | **-Rs 1.06 L** | Rs 2,507 / -Rs 1,714 | -Rs 24,269 | Rs 1.59 L | -Rs 45,982 |
| both indices | 2 | 660 | 37% | **-Rs 1.80 L** | Rs 5,060 / -Rs 3,380 | -Rs 48,254 | Rs 3.00 L | -Rs 90,171 |
| both indices | 3 | 660 | 37% | **-Rs 2.54 L** | Rs 7,583 / -Rs 5,059 | -Rs 72,239 | Rs 4.41 L | -Rs 1.34 L |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 88 | 39% | **-Rs 5,466** | Rs 2,741 / -Rs 1,827 | -Rs 10,852 | Rs 34,078 | -Rs 13,915 |
| NIFTY | 2 | 88 | 39% | **-Rs 6,779** | Rs 5,528 / -Rs 3,606 | -Rs 21,468 | Rs 65,465 | -Rs 27,122 |
| NIFTY | 3 | 88 | 39% | **-Rs 8,091** | Rs 8,316 / -Rs 5,386 | -Rs 32,084 | Rs 96,853 | -Rs 40,328 |
| BANKNIFTY | 1 | 86 | 41% | **Rs 1,763** | Rs 2,416 / -Rs 1,623 | -Rs 12,742 | Rs 31,887 | -Rs 13,197 |
| BANKNIFTY | 2 | 86 | 41% | **Rs 7,586** | Rs 4,878 / -Rs 3,199 | -Rs 25,247 | Rs 62,311 | -Rs 25,497 |
| BANKNIFTY | 3 | 86 | 41% | **Rs 13,408** | Rs 7,341 / -Rs 4,775 | -Rs 37,753 | Rs 92,736 | -Rs 37,797 |
| both indices | 1 | 174 | 40% | **-Rs 3,703** | Rs 2,576 / -Rs 1,728 | -Rs 20,302 | Rs 55,113 | -Rs 23,359 |
| both indices | 2 | 174 | 40% | **Rs 807** | Rs 5,199 / -Rs 3,409 | -Rs 40,227 | Rs 1.04 L | -Rs 45,019 |
| both indices | 3 | 174 | 40% | **Rs 5,316** | Rs 7,822 / -Rs 5,089 | -Rs 60,152 | Rs 1.54 L | -Rs 66,678 |

### filter 1 alone: ADX(14) > 25

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 78 | 35% | **-Rs 3,174** | Rs 3,357 / -Rs 1,839 | -Rs 9,836 | Rs 26,763 | -Rs 18,284 |
| NIFTY | 2 | 78 | 35% | **-Rs 2,666** | Rs 6,760 / -Rs 3,631 | -Rs 19,578 | Rs 52,724 | -Rs 36,238 |
| NIFTY | 3 | 78 | 35% | **-Rs 2,158** | Rs 10,164 / -Rs 5,423 | -Rs 29,320 | Rs 78,685 | -Rs 54,192 |
| BANKNIFTY | 1 | 85 | 42% | **Rs 16,612** | Rs 3,193 / -Rs 2,007 | -Rs 10,605 | Rs 21,133 | -Rs 7,906 |
| BANKNIFTY | 2 | 85 | 42% | **Rs 37,236** | Rs 6,434 / -Rs 3,967 | -Rs 21,115 | Rs 41,841 | -Rs 15,576 |
| BANKNIFTY | 3 | 85 | 44% | **Rs 57,860** | Rs 9,414 / -Rs 6,051 | -Rs 31,625 | Rs 62,550 | -Rs 23,246 |
| both indices | 1 | 163 | 39% | **Rs 13,438** | Rs 3,263 / -Rs 1,922 | -Rs 20,441 | Rs 36,414 | -Rs 22,704 |
| both indices | 2 | 163 | 39% | **Rs 34,570** | Rs 6,574 / -Rs 3,796 | -Rs 40,693 | Rs 69,289 | -Rs 44,984 |
| both indices | 3 | 163 | 39% | **Rs 55,702** | Rs 9,730 / -Rs 5,728 | -Rs 60,945 | Rs 1.02 L | -Rs 67,263 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 21 | 24% | **-Rs 14,489** | Rs 3,196 / -Rs 1,904 | -Rs 7,645 | Rs 21,488 | -Rs 10,837 |
| NIFTY | 2 | 21 | 24% | **-Rs 27,987** | Rs 6,438 / -Rs 3,761 | -Rs 15,148 | Rs 42,268 | -Rs 21,437 |
| NIFTY | 3 | 21 | 24% | **-Rs 41,485** | Rs 9,681 / -Rs 5,618 | -Rs 22,651 | Rs 63,048 | -Rs 32,038 |
| BANKNIFTY | 1 | 26 | 50% | **Rs 11,230** | Rs 2,851 / -Rs 1,987 | -Rs 6,235 | Rs 14,716 | -Rs 7,906 |
| BANKNIFTY | 2 | 26 | 50% | **Rs 23,687** | Rs 5,749 / -Rs 3,927 | -Rs 12,422 | Rs 29,149 | -Rs 15,576 |
| BANKNIFTY | 3 | 26 | 50% | **Rs 36,144** | Rs 8,648 / -Rs 5,867 | -Rs 18,610 | Rs 43,582 | -Rs 23,246 |
| both indices | 1 | 47 | 38% | **-Rs 3,259** | Rs 2,947 / -Rs 1,941 | -Rs 10,629 | Rs 29,107 | -Rs 11,454 |
| both indices | 2 | 47 | 38% | **-Rs 4,300** | Rs 5,941 / -Rs 3,836 | -Rs 21,163 | Rs 56,326 | -Rs 22,530 |
| both indices | 3 | 47 | 38% | **-Rs 5,340** | Rs 8,935 / -Rs 5,730 | -Rs 31,697 | Rs 83,546 | -Rs 33,607 |

### filter 2 alone: 1-hour trend + VWAP

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 109 | 31% | **-Rs 39,779** | Rs 2,331 / -Rs 1,587 | -Rs 9,836 | Rs 65,397 | -Rs 23,737 |
| NIFTY | 2 | 109 | 31% | **-Rs 74,413** | Rs 4,710 / -Rs 3,127 | -Rs 19,578 | Rs 1.28 L | -Rs 47,002 |
| NIFTY | 3 | 109 | 31% | **-Rs 1.09 L** | Rs 7,089 / -Rs 4,668 | -Rs 29,320 | Rs 1.90 L | -Rs 70,267 |
| BANKNIFTY | 1 | 125 | 34% | **-Rs 57,854** | Rs 2,020 / -Rs 1,765 | -Rs 10,605 | Rs 64,254 | -Rs 18,475 |
| BANKNIFTY | 2 | 125 | 34% | **-Rs 1.10 L** | Rs 4,087 / -Rs 3,482 | -Rs 21,115 | Rs 1.24 L | -Rs 36,573 |
| BANKNIFTY | 3 | 125 | 34% | **-Rs 1.62 L** | Rs 6,154 / -Rs 5,200 | -Rs 31,625 | Rs 1.85 L | -Rs 54,670 |
| both indices | 1 | 234 | 33% | **-Rs 97,633** | Rs 2,157 / -Rs 1,680 | -Rs 20,441 | Rs 1.22 L | -Rs 32,398 |
| both indices | 2 | 234 | 33% | **-Rs 1.84 L** | Rs 4,362 / -Rs 3,313 | -Rs 40,693 | Rs 2.34 L | -Rs 64,041 |
| both indices | 3 | 234 | 33% | **-Rs 2.71 L** | Rs 6,567 / -Rs 4,945 | -Rs 60,945 | Rs 3.46 L | -Rs 95,684 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 30 | 33% | **Rs 5,467** | Rs 3,645 / -Rs 1,549 | -Rs 5,376 | Rs 18,131 | -Rs 4,347 |
| NIFTY | 2 | 30 | 33% | **Rs 12,351** | Rs 7,338 / -Rs 3,051 | -Rs 10,659 | Rs 35,271 | -Rs 8,458 |
| NIFTY | 3 | 30 | 33% | **Rs 19,234** | Rs 11,030 / -Rs 4,553 | -Rs 15,941 | Rs 52,411 | -Rs 12,570 |
| BANKNIFTY | 1 | 28 | 32% | **-Rs 16,278** | Rs 1,339 / -Rs 1,491 | -Rs 5,290 | Rs 23,094 | -Rs 11,853 |
| BANKNIFTY | 2 | 28 | 32% | **-Rs 31,234** | Rs 2,725 / -Rs 2,935 | -Rs 10,485 | Rs 45,480 | -Rs 23,328 |
| BANKNIFTY | 3 | 28 | 32% | **-Rs 46,190** | Rs 4,111 / -Rs 4,378 | -Rs 15,681 | Rs 67,866 | -Rs 34,803 |
| both indices | 1 | 58 | 33% | **-Rs 10,810** | Rs 2,553 / -Rs 1,521 | -Rs 8,513 | Rs 37,790 | -Rs 8,172 |
| both indices | 2 | 58 | 33% | **-Rs 18,883** | Rs 5,153 / -Rs 2,995 | -Rs 16,884 | Rs 73,740 | -Rs 15,873 |
| both indices | 3 | 58 | 33% | **-Rs 26,956** | Rs 7,753 / -Rs 4,468 | -Rs 25,256 | Rs 1.10 L | -Rs 23,573 |

### filter 3 alone: 2 x ATR index stop

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 319 | 34% | **-Rs 67,426** | Rs 2,563 / -Rs 1,672 | -Rs 11,018 | Rs 96,924 | -Rs 36,270 |
| NIFTY | 2 | 319 | 34% | **-Rs 1.20 L** | Rs 5,174 / -Rs 3,296 | -Rs 21,894 | Rs 1.85 L | -Rs 71,738 |
| NIFTY | 3 | 319 | 34% | **-Rs 1.72 L** | Rs 7,785 / -Rs 4,921 | -Rs 32,771 | Rs 2.73 L | -Rs 1.07 L |
| BANKNIFTY | 1 | 341 | 37% | **-Rs 47,505** | Rs 2,430 / -Rs 1,664 | -Rs 11,520 | Rs 69,094 | -Rs 23,332 |
| BANKNIFTY | 2 | 341 | 37% | **-Rs 78,916** | Rs 4,907 / -Rs 3,281 | -Rs 22,805 | Rs 1.28 L | -Rs 45,814 |
| BANKNIFTY | 3 | 341 | 38% | **-Rs 1.10 L** | Rs 7,327 / -Rs 4,921 | -Rs 34,089 | Rs 1.87 L | -Rs 68,296 |
| both indices | 1 | 660 | 36% | **-Rs 1.15 L** | Rs 2,492 / -Rs 1,668 | -Rs 21,590 | Rs 1.64 L | -Rs 46,169 |
| both indices | 2 | 660 | 36% | **-Rs 1.99 L** | Rs 5,031 / -Rs 3,289 | -Rs 42,896 | Rs 3.09 L | -Rs 90,544 |
| both indices | 3 | 660 | 36% | **-Rs 2.82 L** | Rs 7,539 / -Rs 4,921 | -Rs 64,203 | Rs 4.54 L | -Rs 1.35 L |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 88 | 39% | **-Rs 3,987** | Rs 2,741 / -Rs 1,799 | -Rs 10,008 | Rs 32,612 | -Rs 13,682 |
| NIFTY | 2 | 88 | 39% | **-Rs 3,821** | Rs 5,528 / -Rs 3,552 | -Rs 19,780 | Rs 62,533 | -Rs 26,656 |
| NIFTY | 3 | 88 | 39% | **-Rs 3,654** | Rs 8,316 / -Rs 5,304 | -Rs 29,551 | Rs 92,454 | -Rs 39,630 |
| BANKNIFTY | 1 | 86 | 41% | **Rs 2,142** | Rs 2,416 / -Rs 1,616 | -Rs 11,520 | Rs 29,558 | -Rs 11,639 |
| BANKNIFTY | 2 | 86 | 41% | **Rs 8,343** | Rs 4,878 / -Rs 3,184 | -Rs 22,805 | Rs 57,653 | -Rs 22,381 |
| BANKNIFTY | 3 | 86 | 41% | **Rs 14,544** | Rs 7,341 / -Rs 4,753 | -Rs 34,089 | Rs 85,748 | -Rs 33,123 |
| both indices | 1 | 174 | 40% | **-Rs 1,845** | Rs 2,576 / -Rs 1,710 | -Rs 17,763 | Rs 52,951 | -Rs 21,699 |
| both indices | 2 | 174 | 40% | **Rs 4,522** | Rs 5,199 / -Rs 3,373 | -Rs 35,149 | Rs 1.00 L | -Rs 41,699 |
| both indices | 3 | 174 | 40% | **Rs 10,889** | Rs 7,822 / -Rs 5,036 | -Rs 52,535 | Rs 1.47 L | -Rs 61,699 |

### all three together (the PDF's full rule)

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 21 | 24% | **-Rs 19,340** | Rs 2,341 / -Rs 1,940 | -Rs 7,665 | Rs 27,472 | -Rs 12,922 |
| NIFTY | 2 | 21 | 24% | **-Rs 37,688** | Rs 4,730 / -Rs 3,834 | -Rs 15,236 | Rs 54,189 | -Rs 25,607 |
| NIFTY | 3 | 21 | 24% | **-Rs 56,036** | Rs 7,119 / -Rs 5,727 | -Rs 22,806 | Rs 80,905 | -Rs 38,293 |
| BANKNIFTY | 1 | 19 | 37% | **-Rs 6,843** | Rs 2,758 / -Rs 2,179 | -Rs 8,307 | Rs 15,162 | -Rs 10,824 |
| BANKNIFTY | 2 | 19 | 37% | **-Rs 12,789** | Rs 5,563 / -Rs 4,311 | -Rs 16,519 | Rs 30,041 | -Rs 21,460 |
| BANKNIFTY | 3 | 19 | 37% | **-Rs 18,736** | Rs 8,368 / -Rs 6,443 | -Rs 24,731 | Rs 44,920 | -Rs 32,096 |
| both indices | 1 | 40 | 30% | **-Rs 26,183** | Rs 2,584 / -Rs 2,043 | -Rs 15,972 | Rs 34,687 | -Rs 23,746 |
| both indices | 2 | 40 | 30% | **-Rs 50,477** | Rs 5,216 / -Rs 4,038 | -Rs 31,754 | Rs 68,100 | -Rs 47,067 |
| both indices | 3 | 40 | 30% | **-Rs 74,772** | Rs 7,848 / -Rs 6,034 | -Rs 47,537 | Rs 1.02 L | -Rs 70,388 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 5 | 0% | **-Rs 6,891** | Rs 0 / -Rs 1,378 | -Rs 2,622 | Rs 6,891 | -Rs 3,742 |
| NIFTY | 2 | 5 | 0% | **-Rs 13,545** | Rs 0 / -Rs 2,709 | -Rs 5,197 | Rs 13,545 | -Rs 7,389 |
| NIFTY | 3 | 5 | 0% | **-Rs 20,200** | Rs 0 / -Rs 4,040 | -Rs 7,772 | Rs 20,200 | -Rs 11,037 |
| BANKNIFTY | 1 | 3 | 33% | **-Rs 1** | Rs 2,502 / -Rs 1,252 | -Rs 1,732 | Rs 2,503 | -Rs 1,732 |
| BANKNIFTY | 2 | 3 | 33% | **Rs 139** | Rs 5,051 / -Rs 2,456 | -Rs 3,416 | Rs 4,912 | -Rs 3,416 |
| BANKNIFTY | 3 | 3 | 33% | **Rs 279** | Rs 7,600 / -Rs 3,661 | -Rs 5,101 | Rs 7,321 | -Rs 5,101 |
| both indices | 1 | 8 | 12% | **-Rs 6,892** | Rs 2,502 / -Rs 1,342 | -Rs 2,622 | Rs 8,867 | -Rs 4,514 |
| both indices | 2 | 8 | 12% | **-Rs 13,406** | Rs 5,051 / -Rs 2,637 | -Rs 5,197 | Rs 17,499 | -Rs 8,885 |
| both indices | 3 | 8 | 12% | **-Rs 19,921** | Rs 7,600 / -Rs 3,932 | -Rs 7,772 | Rs 26,130 | -Rs 13,257 |

### all three, 1-hour trend as EMA 9 > 21

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 17 | 18% | **-Rs 17,519** | Rs 1,071 / -Rs 1,481 | -Rs 3,752 | Rs 18,059 | -Rs 7,146 |
| NIFTY | 2 | 17 | 18% | **-Rs 34,236** | Rs 2,189 / -Rs 2,914 | -Rs 7,457 | Rs 35,410 | -Rs 14,150 |
| NIFTY | 3 | 17 | 18% | **-Rs 50,952** | Rs 3,307 / -Rs 4,348 | -Rs 11,162 | Rs 52,761 | -Rs 21,154 |
| BANKNIFTY | 1 | 13 | 38% | **-Rs 6,057** | Rs 2,113 / -Rs 2,078 | -Rs 5,141 | Rs 9,647 | -Rs 6,165 |
| BANKNIFTY | 2 | 13 | 38% | **-Rs 11,501** | Rs 4,273 / -Rs 4,108 | -Rs 10,235 | Rs 18,869 | -Rs 12,189 |
| BANKNIFTY | 3 | 13 | 38% | **-Rs 16,945** | Rs 6,433 / -Rs 6,139 | -Rs 15,328 | Rs 28,091 | -Rs 18,212 |
| both indices | 1 | 30 | 27% | **-Rs 23,576** | Rs 1,722 / -Rs 1,698 | -Rs 8,893 | Rs 23,576 | -Rs 13,311 |
| both indices | 2 | 30 | 27% | **-Rs 45,737** | Rs 3,491 / -Rs 3,348 | -Rs 17,692 | Rs 45,737 | -Rs 26,338 |
| both indices | 3 | 30 | 27% | **-Rs 67,897** | Rs 5,260 / -Rs 4,999 | -Rs 26,490 | Rs 67,897 | -Rs 39,366 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 6 | 0% | **-Rs 7,697** | Rs 0 / -Rs 1,283 | -Rs 2,622 | Rs 7,697 | -Rs 4,548 |
| NIFTY | 2 | 6 | 0% | **-Rs 15,110** | Rs 0 / -Rs 2,518 | -Rs 5,197 | Rs 15,110 | -Rs 8,954 |
| NIFTY | 3 | 6 | 0% | **-Rs 22,524** | Rs 0 / -Rs 3,754 | -Rs 7,772 | Rs 22,524 | -Rs 13,361 |
| BANKNIFTY | 1 | 3 | 33% | **-Rs 1** | Rs 2,502 / -Rs 1,252 | -Rs 1,732 | Rs 2,503 | -Rs 1,732 |
| BANKNIFTY | 2 | 3 | 33% | **Rs 139** | Rs 5,051 / -Rs 2,456 | -Rs 3,416 | Rs 4,912 | -Rs 3,416 |
| BANKNIFTY | 3 | 3 | 33% | **Rs 279** | Rs 7,600 / -Rs 3,661 | -Rs 5,101 | Rs 7,321 | -Rs 5,101 |
| both indices | 1 | 9 | 11% | **-Rs 7,698** | Rs 2,502 / -Rs 1,275 | -Rs 2,622 | Rs 9,673 | -Rs 5,320 |
| both indices | 2 | 9 | 11% | **-Rs 14,971** | Rs 5,051 / -Rs 2,503 | -Rs 5,197 | Rs 19,064 | -Rs 10,450 |
| both indices | 3 | 9 | 11% | **-Rs 22,245** | Rs 7,600 / -Rs 3,731 | -Rs 7,772 | Rs 28,454 | -Rs 15,581 |

### all three, TWAP instead of the volume-weighted VWAP

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 20 | 25% | **-Rs 18,524** | Rs 2,341 / -Rs 2,015 | -Rs 7,665 | Rs 26,656 | -Rs 12,922 |
| NIFTY | 2 | 20 | 25% | **-Rs 36,104** | Rs 4,730 / -Rs 3,984 | -Rs 15,236 | Rs 52,604 | -Rs 25,607 |
| NIFTY | 3 | 20 | 25% | **-Rs 53,684** | Rs 7,119 / -Rs 5,952 | -Rs 22,806 | Rs 78,553 | -Rs 38,293 |
| BANKNIFTY | 1 | 20 | 35% | **-Rs 8,444** | Rs 2,758 / -Rs 2,135 | -Rs 8,307 | Rs 16,763 | -Rs 10,824 |
| BANKNIFTY | 2 | 20 | 35% | **-Rs 15,944** | Rs 5,563 / -Rs 4,222 | -Rs 16,519 | Rs 33,195 | -Rs 21,460 |
| BANKNIFTY | 3 | 20 | 35% | **-Rs 23,444** | Rs 8,368 / -Rs 6,309 | -Rs 24,731 | Rs 49,628 | -Rs 32,096 |
| both indices | 1 | 40 | 30% | **-Rs 26,968** | Rs 2,584 / -Rs 2,071 | -Rs 15,972 | Rs 35,472 | -Rs 23,746 |
| both indices | 2 | 40 | 30% | **-Rs 52,048** | Rs 5,216 / -Rs 4,094 | -Rs 31,754 | Rs 69,670 | -Rs 47,067 |
| both indices | 3 | 40 | 30% | **-Rs 77,127** | Rs 7,848 / -Rs 6,118 | -Rs 47,537 | Rs 1.04 L | -Rs 70,388 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 5 | 0% | **-Rs 6,891** | Rs 0 / -Rs 1,378 | -Rs 2,622 | Rs 6,891 | -Rs 3,742 |
| NIFTY | 2 | 5 | 0% | **-Rs 13,545** | Rs 0 / -Rs 2,709 | -Rs 5,197 | Rs 13,545 | -Rs 7,389 |
| NIFTY | 3 | 5 | 0% | **-Rs 20,200** | Rs 0 / -Rs 4,040 | -Rs 7,772 | Rs 20,200 | -Rs 11,037 |
| BANKNIFTY | 1 | 3 | 33% | **-Rs 1** | Rs 2,502 / -Rs 1,252 | -Rs 1,732 | Rs 2,503 | -Rs 1,732 |
| BANKNIFTY | 2 | 3 | 33% | **Rs 139** | Rs 5,051 / -Rs 2,456 | -Rs 3,416 | Rs 4,912 | -Rs 3,416 |
| BANKNIFTY | 3 | 3 | 33% | **Rs 279** | Rs 7,600 / -Rs 3,661 | -Rs 5,101 | Rs 7,321 | -Rs 5,101 |
| both indices | 1 | 8 | 12% | **-Rs 6,892** | Rs 2,502 / -Rs 1,342 | -Rs 2,622 | Rs 8,867 | -Rs 4,514 |
| both indices | 2 | 8 | 12% | **-Rs 13,406** | Rs 5,051 / -Rs 2,637 | -Rs 5,197 | Rs 17,499 | -Rs 8,885 |
| both indices | 3 | 8 | 12% | **-Rs 19,921** | Rs 7,600 / -Rs 3,932 | -Rs 7,772 | Rs 26,130 | -Rs 13,257 |

### all three + clearing the opening range

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 7 | 43% | **-Rs 2,547** | Rs 3,244 / -Rs 3,070 | -Rs 5,272 | Rs 10,206 | -Rs 5,272 |
| NIFTY | 2 | 7 | 43% | **-Rs 4,764** | Rs 6,535 / -Rs 6,092 | -Rs 10,496 | Rs 20,318 | -Rs 10,496 |
| NIFTY | 3 | 7 | 43% | **-Rs 6,981** | Rs 9,825 / -Rs 9,114 | -Rs 15,721 | Rs 30,430 | -Rs 15,721 |
| BANKNIFTY | 1 | 10 | 30% | **-Rs 10,206** | Rs 3,186 / -Rs 2,824 | -Rs 8,307 | Rs 17,439 | -Rs 13,447 |
| BANKNIFTY | 2 | 10 | 30% | **-Rs 19,940** | Rs 6,420 / -Rs 5,600 | -Rs 16,519 | Rs 34,642 | -Rs 26,753 |
| BANKNIFTY | 3 | 10 | 30% | **-Rs 29,674** | Rs 9,654 / -Rs 8,376 | -Rs 24,731 | Rs 51,844 | -Rs 40,059 |
| both indices | 1 | 17 | 35% | **-Rs 12,753** | Rs 3,215 / -Rs 2,913 | -Rs 13,241 | Rs 23,221 | -Rs 18,382 |
| both indices | 2 | 17 | 35% | **-Rs 24,704** | Rs 6,477 / -Rs 5,779 | -Rs 26,341 | Rs 45,971 | -Rs 36,575 |
| both indices | 3 | 17 | 35% | **-Rs 36,655** | Rs 9,740 / -Rs 8,645 | -Rs 39,440 | Rs 68,720 | -Rs 54,769 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 1 | 0% | **-Rs 1,169** | Rs 0 / -Rs 1,169 | -Rs 1,169 | Rs 1,169 | -Rs 1,169 |
| NIFTY | 2 | 1 | 0% | **-Rs 2,291** | Rs 0 / -Rs 2,291 | -Rs 2,291 | Rs 2,291 | -Rs 2,291 |
| NIFTY | 3 | 1 | 0% | **-Rs 3,413** | Rs 0 / -Rs 3,413 | -Rs 3,413 | Rs 3,413 | -Rs 3,413 |
| BANKNIFTY | 1 | 1 | 0% | **-Rs 772** | Rs 0 / -Rs 772 | -Rs 772 | Rs 772 | -Rs 772 |
| BANKNIFTY | 2 | 1 | 0% | **-Rs 1,496** | Rs 0 / -Rs 1,496 | -Rs 1,496 | Rs 1,496 | -Rs 1,496 |
| BANKNIFTY | 3 | 1 | 0% | **-Rs 2,221** | Rs 0 / -Rs 2,221 | -Rs 2,221 | Rs 2,221 | -Rs 2,221 |
| both indices | 1 | 2 | 0% | **-Rs 1,941** | Rs 0 / -Rs 970 | -Rs 1,941 | Rs 1,941 | -Rs 1,941 |
| both indices | 2 | 2 | 0% | **-Rs 3,787** | Rs 0 / -Rs 1,894 | -Rs 3,787 | Rs 3,787 | -Rs 3,787 |
| both indices | 3 | 2 | 0% | **-Rs 5,634** | Rs 0 / -Rs 2,817 | -Rs 5,634 | Rs 5,634 | -Rs 5,634 |

### all three + a 2R target

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 21 | 24% | **-Rs 16,794** | Rs 2,851 / -Rs 1,940 | -Rs 7,665 | Rs 27,472 | -Rs 12,922 |
| NIFTY | 2 | 21 | 24% | **-Rs 32,597** | Rs 5,748 / -Rs 3,834 | -Rs 15,236 | Rs 54,189 | -Rs 25,607 |
| NIFTY | 3 | 21 | 24% | **-Rs 48,399** | Rs 8,646 / -Rs 5,727 | -Rs 22,806 | Rs 80,905 | -Rs 38,293 |
| BANKNIFTY | 1 | 19 | 42% | **-Rs 145** | Rs 3,122 / -Rs 2,284 | -Rs 8,307 | Rs 15,162 | -Rs 10,824 |
| BANKNIFTY | 2 | 19 | 42% | **Rs 606** | Rs 6,291 / -Rs 4,520 | -Rs 16,519 | Rs 30,041 | -Rs 21,460 |
| BANKNIFTY | 3 | 19 | 42% | **Rs 1,358** | Rs 9,460 / -Rs 6,757 | -Rs 24,731 | Rs 44,920 | -Rs 32,096 |
| both indices | 1 | 40 | 32% | **-Rs 16,939** | Rs 3,018 / -Rs 2,080 | -Rs 15,972 | Rs 33,772 | -Rs 23,746 |
| both indices | 2 | 40 | 32% | **-Rs 31,990** | Rs 6,082 / -Rs 4,113 | -Rs 31,754 | Rs 66,365 | -Rs 47,067 |
| both indices | 3 | 40 | 32% | **-Rs 47,041** | Rs 9,147 / -Rs 6,146 | -Rs 47,537 | Rs 98,958 | -Rs 70,388 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 5 | 0% | **-Rs 6,891** | Rs 0 / -Rs 1,378 | -Rs 2,622 | Rs 6,891 | -Rs 3,742 |
| NIFTY | 2 | 5 | 0% | **-Rs 13,545** | Rs 0 / -Rs 2,709 | -Rs 5,197 | Rs 13,545 | -Rs 7,389 |
| NIFTY | 3 | 5 | 0% | **-Rs 20,200** | Rs 0 / -Rs 4,040 | -Rs 7,772 | Rs 20,200 | -Rs 11,037 |
| BANKNIFTY | 1 | 3 | 33% | **-Rs 1** | Rs 2,502 / -Rs 1,252 | -Rs 1,732 | Rs 2,503 | -Rs 1,732 |
| BANKNIFTY | 2 | 3 | 33% | **Rs 139** | Rs 5,051 / -Rs 2,456 | -Rs 3,416 | Rs 4,912 | -Rs 3,416 |
| BANKNIFTY | 3 | 3 | 33% | **Rs 279** | Rs 7,600 / -Rs 3,661 | -Rs 5,101 | Rs 7,321 | -Rs 5,101 |
| both indices | 1 | 8 | 12% | **-Rs 6,892** | Rs 2,502 / -Rs 1,342 | -Rs 2,622 | Rs 8,867 | -Rs 4,514 |
| both indices | 2 | 8 | 12% | **-Rs 13,406** | Rs 5,051 / -Rs 2,637 | -Rs 5,197 | Rs 17,499 | -Rs 8,885 |
| both indices | 3 | 8 | 12% | **-Rs 19,921** | Rs 7,600 / -Rs 3,932 | -Rs 7,772 | Rs 26,130 | -Rs 13,257 |

### all three, 1-ITM strike

*full period (6 May 2024 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 21 | 24% | **-Rs 21,655** | Rs 2,668 / -Rs 2,187 | -Rs 8,658 | Rs 31,059 | -Rs 14,795 |
| NIFTY | 2 | 21 | 24% | **-Rs 42,319** | Rs 5,383 / -Rs 4,327 | -Rs 17,221 | Rs 61,363 | -Rs 29,355 |
| NIFTY | 3 | 21 | 24% | **-Rs 62,984** | Rs 8,098 / -Rs 6,467 | -Rs 25,785 | Rs 91,666 | -Rs 43,914 |
| BANKNIFTY | 1 | 19 | 37% | **-Rs 6,064** | Rs 3,013 / -Rs 2,263 | -Rs 8,754 | Rs 15,855 | -Rs 11,426 |
| BANKNIFTY | 2 | 19 | 37% | **-Rs 11,231** | Rs 6,073 / -Rs 4,478 | -Rs 17,414 | Rs 31,427 | -Rs 22,663 |
| BANKNIFTY | 3 | 19 | 37% | **-Rs 16,398** | Rs 9,133 / -Rs 6,694 | -Rs 26,073 | Rs 46,998 | -Rs 33,901 |
| both indices | 1 | 40 | 30% | **-Rs 27,719** | Rs 2,869 / -Rs 2,220 | -Rs 17,412 | Rs 38,253 | -Rs 26,222 |
| both indices | 2 | 40 | 30% | **-Rs 53,550** | Rs 5,785 / -Rs 4,392 | -Rs 34,635 | Rs 75,232 | -Rs 52,018 |
| both indices | 3 | 40 | 30% | **-Rs 79,381** | Rs 8,702 / -Rs 6,564 | -Rs 51,858 | Rs 1.12 L | -Rs 77,815 |

*last 6 months = final (25 Aug 2025 - 23 Feb 2026)*

| index | lots | trades | win % | net | avg win / loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|---|
| NIFTY | 1 | 5 | 0% | **-Rs 7,886** | Rs 0 / -Rs 1,577 | -Rs 2,981 | Rs 7,886 | -Rs 4,368 |
| NIFTY | 2 | 5 | 0% | **-Rs 15,537** | Rs 0 / -Rs 3,107 | -Rs 5,916 | Rs 15,537 | -Rs 8,642 |
| NIFTY | 3 | 5 | 0% | **-Rs 23,187** | Rs 0 / -Rs 4,637 | -Rs 8,850 | Rs 23,187 | -Rs 12,916 |
| BANKNIFTY | 1 | 3 | 33% | **Rs 292** | Rs 2,982 / -Rs 1,345 | -Rs 1,809 | Rs 2,690 | -Rs 1,809 |
| BANKNIFTY | 2 | 3 | 33% | **Rs 725** | Rs 6,010 / -Rs 2,642 | -Rs 3,570 | Rs 5,285 | -Rs 3,570 |
| BANKNIFTY | 3 | 3 | 33% | **Rs 1,159** | Rs 9,039 / -Rs 3,940 | -Rs 5,331 | Rs 7,880 | -Rs 5,331 |
| both indices | 1 | 8 | 12% | **-Rs 7,594** | Rs 2,982 / -Rs 1,511 | -Rs 2,981 | Rs 10,039 | -Rs 5,249 |
| both indices | 2 | 8 | 12% | **-Rs 14,811** | Rs 6,010 / -Rs 2,975 | -Rs 5,916 | Rs 19,843 | -Rs 10,357 |
| both indices | 3 | 8 | 12% | **-Rs 22,028** | Rs 9,039 / -Rs 4,438 | -Rs 8,850 | Rs 29,646 | -Rs 15,465 |

## 3. Calls and puts as separate rows (1 lot)

| rule | index | side | full: net (trades, win %) | last 6 months: net (trades, win %) |
|---|---|---|---|---|
| PLAIN | NIFTY | calls | Rs 9,921 (167, 35%) | Rs 4,388 (45, 40%) |
| PLAIN | NIFTY | puts | -Rs 58,930 (152, 36%) | -Rs 9,854 (43, 37%) |
| PLAIN | BANKNIFTY | calls | -Rs 32,302 (170, 38%) | -Rs 9,877 (41, 39%) |
| PLAIN | BANKNIFTY | puts | -Rs 24,230 (171, 39%) | Rs 11,640 (45, 42%) |
| PLAIN | both indices | calls | -Rs 22,381 (337, 36%) | -Rs 5,489 (86, 40%) |
| PLAIN | both indices | puts | -Rs 83,159 (323, 37%) | Rs 1,786 (88, 40%) |
| ADX | NIFTY | calls | Rs 21,312 (44, 36%) | -Rs 2,215 (11, 27%) |
| ADX | NIFTY | puts | -Rs 24,486 (34, 32%) | -Rs 12,274 (10, 20%) |
| ADX | BANKNIFTY | calls | Rs 16,402 (42, 43%) | -Rs 2,456 (11, 36%) |
| ADX | BANKNIFTY | puts | Rs 210 (43, 42%) | Rs 13,686 (15, 60%) |
| ADX | both indices | calls | Rs 37,715 (86, 40%) | -Rs 4,671 (22, 32%) |
| ADX | both indices | puts | -Rs 24,277 (77, 38%) | Rs 1,412 (25, 44%) |
| TREND | NIFTY | calls | -Rs 26,189 (59, 25%) | -Rs 3,240 (20, 30%) |
| TREND | NIFTY | puts | -Rs 13,590 (50, 38%) | Rs 8,708 (10, 40%) |
| TREND | BANKNIFTY | calls | -Rs 49,685 (62, 29%) | -Rs 8,778 (17, 29%) |
| TREND | BANKNIFTY | puts | -Rs 8,169 (63, 40%) | -Rs 7,500 (11, 36%) |
| TREND | both indices | calls | -Rs 75,874 (121, 27%) | -Rs 12,018 (37, 30%) |
| TREND | both indices | puts | -Rs 21,759 (113, 39%) | Rs 1,207 (21, 38%) |
| ATR | NIFTY | calls | Rs 4,254 (167, 34%) | Rs 4,460 (45, 40%) |
| ATR | NIFTY | puts | -Rs 71,680 (152, 35%) | -Rs 8,447 (43, 37%) |
| ATR | BANKNIFTY | calls | -Rs 20,634 (170, 37%) | -Rs 7,927 (41, 39%) |
| ATR | BANKNIFTY | puts | -Rs 26,872 (171, 37%) | Rs 10,069 (45, 42%) |
| ATR | both indices | calls | -Rs 16,380 (337, 36%) | -Rs 3,467 (86, 40%) |
| ATR | both indices | puts | -Rs 98,552 (323, 36%) | Rs 1,622 (88, 40%) |
| ALL | NIFTY | calls | -Rs 12,625 (12, 8%) | -Rs 6,459 (4, 0%) |
| ALL | NIFTY | puts | -Rs 6,714 (9, 44%) | -Rs 432 (1, 0%) |
| ALL | BANKNIFTY | calls | -Rs 9,544 (8, 25%) | -Rs 2,503 (2, 0%) |
| ALL | BANKNIFTY | puts | Rs 2,701 (11, 45%) | Rs 2,502 (1, 100%) |
| ALL | both indices | calls | -Rs 22,169 (20, 15%) | -Rs 8,962 (6, 0%) |
| ALL | both indices | puts | -Rs 4,013 (20, 45%) | Rs 2,070 (2, 50%) |
| ALL-H1B | NIFTY | calls | -Rs 16,077 (10, 0%) | -Rs 7,265 (5, 0%) |
| ALL-H1B | NIFTY | puts | -Rs 1,442 (7, 43%) | -Rs 432 (1, 0%) |
| ALL-H1B | BANKNIFTY | calls | -Rs 9,269 (6, 17%) | -Rs 2,503 (2, 0%) |
| ALL-H1B | BANKNIFTY | puts | Rs 3,212 (7, 57%) | Rs 2,502 (1, 100%) |
| ALL-H1B | both indices | calls | -Rs 25,347 (16, 6%) | -Rs 9,768 (7, 0%) |
| ALL-H1B | both indices | puts | Rs 1,770 (14, 50%) | Rs 2,070 (2, 50%) |
| ALL-TWAP | NIFTY | calls | -Rs 11,810 (11, 9%) | -Rs 6,459 (4, 0%) |
| ALL-TWAP | NIFTY | puts | -Rs 6,714 (9, 44%) | -Rs 432 (1, 0%) |
| ALL-TWAP | BANKNIFTY | calls | -Rs 11,145 (9, 22%) | -Rs 2,503 (2, 0%) |
| ALL-TWAP | BANKNIFTY | puts | Rs 2,701 (11, 45%) | Rs 2,502 (1, 100%) |
| ALL-TWAP | both indices | calls | -Rs 22,954 (20, 15%) | -Rs 8,962 (6, 0%) |
| ALL-TWAP | both indices | puts | -Rs 4,013 (20, 45%) | Rs 2,070 (2, 50%) |
| ALL-OR | NIFTY | calls | Rs 2,318 (3, 33%) | -Rs 1,169 (1, 0%) |
| ALL-OR | NIFTY | puts | -Rs 4,865 (4, 50%) | Rs 0 (0, 0%) |
| ALL-OR | BANKNIFTY | calls | -Rs 7,165 (6, 33%) | -Rs 772 (1, 0%) |
| ALL-OR | BANKNIFTY | puts | -Rs 3,041 (4, 25%) | Rs 0 (0, 0%) |
| ALL-OR | both indices | calls | -Rs 4,848 (9, 33%) | -Rs 1,941 (2, 0%) |
| ALL-OR | both indices | puts | -Rs 7,905 (8, 38%) | Rs 0 (0, 0%) |
| ALL-2R | NIFTY | calls | -Rs 12,625 (12, 8%) | -Rs 6,459 (4, 0%) |
| ALL-2R | NIFTY | puts | -Rs 4,169 (9, 44%) | -Rs 432 (1, 0%) |
| ALL-2R | BANKNIFTY | calls | -Rs 8,902 (8, 25%) | -Rs 2,503 (2, 0%) |
| ALL-2R | BANKNIFTY | puts | Rs 8,757 (11, 55%) | Rs 2,502 (1, 100%) |
| ALL-2R | both indices | calls | -Rs 21,528 (20, 15%) | -Rs 8,962 (6, 0%) |
| ALL-2R | both indices | puts | Rs 4,589 (20, 50%) | Rs 2,070 (2, 50%) |
| ALL-ITM | NIFTY | calls | -Rs 14,255 (12, 8%) | -Rs 7,476 (4, 0%) |
| ALL-ITM | NIFTY | puts | -Rs 7,400 (9, 44%) | -Rs 410 (1, 0%) |
| ALL-ITM | BANKNIFTY | calls | -Rs 10,201 (8, 25%) | -Rs 2,690 (2, 0%) |
| ALL-ITM | BANKNIFTY | puts | Rs 4,137 (11, 45%) | Rs 2,982 (1, 100%) |
| ALL-ITM | both indices | calls | -Rs 24,456 (20, 15%) | -Rs 10,166 (6, 0%) |
| ALL-ITM | both indices | puts | -Rs 3,263 (20, 45%) | Rs 2,572 (2, 50%) |

## 4. Design walk-forward folds (both indices, calls + puts, 1 lot)

Nothing is fitted (every setting is the PDF's), so the walk-forward net is the design net; the four folds are shown so a single good stretch is visible.

| rule | 6 May - Aug 2024 | Sep - Dec 2024 | Jan - Apr 2025 | May - 22 Aug 2025 | design |
|---|---|---|---|---|---|
| PLAIN | -Rs 28,693 | -Rs 19,679 | -Rs 73,701 | Rs 20,236 | -Rs 1.02 L (486, 36%) |
| ADX | Rs 3,445 | Rs 8,440 | -Rs 11,277 | Rs 16,090 | Rs 16,697 (116, 39%) |
| TREND | -Rs 21,410 | -Rs 4,072 | -Rs 51,402 | -Rs 9,939 | -Rs 86,823 (176, 33%) |
| ATR | -Rs 42,624 | -Rs 3,413 | -Rs 91,238 | Rs 24,188 | -Rs 1.13 L (486, 35%) |
| ALL | -Rs 6,114 | Rs 14,618 | -Rs 7,090 | -Rs 20,705 | -Rs 19,291 (32, 34%) |
| ALL-H1B | -Rs 5,657 | Rs 1,886 | -Rs 2,956 | -Rs 9,152 | -Rs 15,878 (21, 33%) |
| ALL-TWAP | -Rs 6,114 | Rs 14,618 | -Rs 7,875 | -Rs 20,705 | -Rs 20,076 (32, 34%) |
| ALL-OR | -Rs 2,253 | Rs 12,721 | -Rs 4,134 | -Rs 17,146 | -Rs 10,812 (15, 40%) |
| ALL-2R | -Rs 322 | Rs 15,260 | -Rs 4,280 | -Rs 20,705 | -Rs 10,047 (32, 38%) |
| ALL-ITM | -Rs 5,898 | Rs 16,432 | -Rs 7,597 | -Rs 23,061 | -Rs 20,125 (32, 34%) |

## 5. Exit mix (full period, both indices, 1 lot)

| rule | opposite cross | index stop | 2R target | 15:10 square-off |
|---|---|---|---|---|
| PLAIN | 216 | 0 | 0 | 444 |
| ADX | 45 | 0 | 0 | 118 |
| TREND | 81 | 0 | 0 | 153 |
| ATR | 137 | 109 | 0 | 414 |
| ALL | 9 | 5 | 0 | 26 |
| ALL-H1B | 10 | 2 | 0 | 18 |
| ALL-TWAP | 9 | 5 | 0 | 26 |
| ALL-OR | 1 | 5 | 0 | 11 |
| ALL-2R | 9 | 5 | 4 | 22 |
| ALL-ITM | 9 | 5 | 0 | 26 |

## 6. Last 6 months, month by month (both indices, calls + puts, 1 lot)

| rule | 2025-08 | 2025-09 | 2025-10 | 2025-11 | 2025-12 | 2026-01 | 2026-02 |
|---|---|---|---|---|---|---|---|
| PLAIN | Rs 2,399 | Rs 10,068 | Rs 10,248 | -Rs 7,275 | -Rs 5,443 | -Rs 23,359 | Rs 9,658 |
| ADX | Rs 0 | Rs 2,900 | -Rs 3,212 | -Rs 7,436 | Rs 2,151 | -Rs 11,454 | Rs 13,791 |
| TREND | Rs 0 | -Rs 631 | -Rs 3,443 | -Rs 4,141 | -Rs 8,172 | -Rs 7,895 | Rs 13,471 |
| ATR | Rs 2,399 | Rs 9,764 | Rs 8,036 | -Rs 6,213 | -Rs 4,542 | -Rs 21,699 | Rs 10,409 |
| ALL | Rs 0 | Rs 1,975 | -Rs 4,514 | -Rs 2,622 | Rs 0 | -Rs 1,732 | Rs 0 |
| ALL-H1B | Rs 0 | Rs 1,975 | -Rs 5,320 | -Rs 2,622 | Rs 0 | -Rs 1,732 | Rs 0 |
| ALL-TWAP | Rs 0 | Rs 1,975 | -Rs 4,514 | -Rs 2,622 | Rs 0 | -Rs 1,732 | Rs 0 |
| ALL-OR | Rs 0 | Rs 0 | -Rs 1,941 | Rs 0 | Rs 0 | Rs 0 | Rs 0 |
| ALL-2R | Rs 0 | Rs 1,975 | -Rs 4,514 | -Rs 2,622 | Rs 0 | -Rs 1,732 | Rs 0 |
| ALL-ITM | Rs 0 | Rs 2,445 | -Rs 5,249 | -Rs 2,981 | Rs 0 | -Rs 1,809 | Rs 0 |

## 7. Gates (PREREG section 7)

Final net > 0; twin p < 0.05 (2,000 random-entry twins, seed 177); BH q <= 0.10 with m = 4,950 (4,680 earlier entries + 270 here); 40+ final trades; final net without the top 5 > 0; design net > 0; max drawdown <= Rs 40,000 and worst month >= -Rs 13,000 in design and final. Below: both indices, calls + puts; every one of the 270 cells is in `decision.json`.

| rule | lots | final net | twin mean | p (twins) | p (normal) | q (m=4,950) | q (R177 only) | failed gates |
|---|---|---|---|---|---|---|---|---|
| PLAIN | 1 | -Rs 3,703 | Rs 1.01 L | 1.0000 | 1 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| PLAIN | 2 | Rs 807 | Rs 2.09 L | 1.0000 | 1 | 1.00 | 1.00 | twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| PLAIN | 3 | Rs 5,316 | Rs 3.18 L | 1.0000 | 1 | 1.00 | 1.00 | twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ADX | 1 | -Rs 3,259 | Rs 43,637 | 1.0000 | 1 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ADX | 2 | -Rs 4,300 | Rs 89,491 | 1.0000 | 1 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ADX | 3 | -Rs 5,340 | Rs 1.35 L | 1.0000 | 1 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| TREND | 1 | -Rs 10,810 | Rs 32,782 | 0.9995 | 0.999 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| TREND | 2 | -Rs 18,883 | Rs 68,301 | 0.9995 | 0.999 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| TREND | 3 | -Rs 26,956 | Rs 1.04 L | 0.9995 | 0.999 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ATR | 1 | -Rs 1,845 | Rs 92,668 | 1.0000 | 1 | 1.00 | 1.00 | final net <= 0; twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ATR | 2 | Rs 4,522 | Rs 1.94 L | 1.0000 | 1 | 1.00 | 1.00 | twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ATR | 3 | Rs 10,889 | Rs 2.94 L | 1.0000 | 1 | 1.00 | 1.00 | twin p 1.000 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ALL | 1 | -Rs 6,892 | -Rs 1,595 | 0.9005 | 0.896 | 1.00 | 1.00 | final net <= 0; twin p 0.901 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL | 2 | -Rs 13,406 | -Rs 2,813 | 0.9005 | 0.896 | 1.00 | 1.00 | final net <= 0; twin p 0.901 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL | 3 | -Rs 19,921 | -Rs 4,030 | 0.9005 | 0.896 | 1.00 | 1.00 | final net <= 0; twin p 0.901 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ALL-H1B | 1 | -Rs 7,698 | -Rs 2,447 | 0.8816 | 0.88 | 1.00 | 1.00 | final net <= 0; twin p 0.882 >= 0.05; BH q 1.00 > 0.10; 9 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-H1B | 2 | -Rs 14,971 | -Rs 4,469 | 0.8816 | 0.88 | 1.00 | 1.00 | final net <= 0; twin p 0.882 >= 0.05; BH q 1.00 > 0.10; 9 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-H1B | 3 | -Rs 22,245 | -Rs 6,492 | 0.8816 | 0.88 | 1.00 | 1.00 | final net <= 0; twin p 0.882 >= 0.05; BH q 1.00 > 0.10; 9 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ALL-TWAP | 1 | -Rs 6,892 | -Rs 1,486 | 0.9015 | 0.898 | 1.00 | 1.00 | final net <= 0; twin p 0.902 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-TWAP | 2 | -Rs 13,406 | -Rs 2,594 | 0.9015 | 0.898 | 1.00 | 1.00 | final net <= 0; twin p 0.902 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-TWAP | 3 | -Rs 19,921 | -Rs 3,702 | 0.9015 | 0.898 | 1.00 | 1.00 | final net <= 0; twin p 0.902 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ALL-OR | 1 | -Rs 1,941 | -Rs 82 | 0.9650 | 0.97 | 1.00 | 1.00 | final net <= 0; twin p 0.965 >= 0.05; BH q 1.00 > 0.10; 2 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-OR | 2 | -Rs 3,787 | -Rs 70 | 0.9650 | 0.97 | 1.00 | 1.00 | final net <= 0; twin p 0.965 >= 0.05; BH q 1.00 > 0.10; 2 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-OR | 3 | -Rs 5,634 | -Rs 58 | 0.9650 | 0.97 | 1.00 | 1.00 | final net <= 0; twin p 0.965 >= 0.05; BH q 1.00 > 0.10; 2 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-2R | 1 | -Rs 6,892 | -Rs 1,382 | 0.9125 | 0.908 | 1.00 | 1.00 | final net <= 0; twin p 0.913 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-2R | 2 | -Rs 13,406 | -Rs 2,387 | 0.9125 | 0.908 | 1.00 | 1.00 | final net <= 0; twin p 0.913 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-2R | 3 | -Rs 19,921 | -Rs 3,392 | 0.9125 | 0.908 | 1.00 | 1.00 | final net <= 0; twin p 0.913 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ALL-ITM | 1 | -Rs 7,594 | -Rs 1,165 | 0.9100 | 0.908 | 1.00 | 1.00 | final net <= 0; twin p 0.910 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-ITM | 2 | -Rs 14,811 | -Rs 1,952 | 0.9100 | 0.908 | 1.00 | 1.00 | final net <= 0; twin p 0.910 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design |
| ALL-ITM | 3 | -Rs 22,028 | -Rs 2,739 | 0.9100 | 0.908 | 1.00 | 1.00 | final net <= 0; twin p 0.910 >= 0.05; BH q 1.00 > 0.10; 8 final trades < 40; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |

Cells with a positive final net: 87 of 270; lowest q (m = 4,950): 1.00; lowest q within R177-EMA alone: 1.00.

## 8. Earlier results this agrees or disagrees with

- R44 / R51: EMA 8/21 + ADX(14) > 15 + profit lock (5-minute bars): -Rs 50,947 on 3,129 design trades, -Rs 32,749 on 1,262 final trades.
- R104 book-porwal-08 (ADX + EMA + ATR): -Rs 1.23 L on 1,115 trades.
- H46: VWAP as a filter failed (2,400 variants). R17 / R20: ATR stops did not help. ADX: rejected in 77 of 91 tests.

## 9. Caveats

- About 21 months of data, two indices; the final (last 6 months) is short, and the filtered rules trade rarely, so several cells have too few trades to judge on their own.
- The VWAP is weighted by option-chain volume, not futures volume (none is available here); the TWAP variant shows how much the weighting matters.
- Crossovers are computed on the continuous 15-minute series (R44's convention), so a cross can come from the overnight gap and fire on the 09:15 bar (entry 09:30).
- Twins do not stop overlapping random entries (R44's design). Apr - Oct 2026 could not be read here.
- The twins enter on the same days as the rule, and a crossover day is a day that moved, so random entries on those days do well (the plain crossover's twins average about +Rs 1 L over the last 6 months at 1 lot, while random entries on every final day lost about Rs 100-175 a trade). This makes the twin bar harder to clear for the unfiltered rules. It does not change the verdict: the 29 cells in profit in both design and final had 1-3 final trades (the ALL family's BANKNIFTY puts) or under 40 (ADX on BANKNIFTY, 26), or lost once their best 5 trades are removed (the plain and ATR-stop NIFTY calls, 45 final trades), and the headline cells lose on their own.
- Lots: Zerodha's flat Rs 20 an order is the same at any size, so 2 and 3 lots lose less per lot than 1 lot.
