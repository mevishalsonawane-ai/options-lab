# R189: Boss's Hull line ("BBI"), 2 closed candles above the rising line: summary for Boss

**1-min only, no 10-sec data.** The 10-second half of the rule could not be tested: no 10-second index data is reachable (the phone's per-second recordings are sealed with Boss's passphrase, which this study does not have and never tried to open). Nothing was faked from 1-minute bars.

**Verdict: FAILED.** No cell of the picked rule passed every gate.

- **The exit research picked X16** (time stop 30 minutes): no exit had all 4 folds > 0: best design net, on the design months only (20 Nov 2024 - 31 Mar 2026).
- **Primary rule (Boss's line, candle close above / below, ATM option, the X16 exit), all five indices, 1 lot:**
  - last 6 months (1 Apr - 6 Oct 2026, the locked holdout, read once): calls 2210 trades, 37% won, -Rs 3,62,649; puts 2032 trades, 38% won, -Rs 2,70,192; together 4242 trades, 38% won, -Rs 6,32,841.
  - full period (20 Nov 2024 - 6 Oct 2026): calls 8102 trades, 38% won, -Rs 7,66,629; puts 7768 trades, 39% won, -Rs 6,76,770; together 15870 trades, 38% won, -Rs 14,43,399.
- **Standard Hull line instead of Boss's script** (same exit): last 6 months 6456 trades, 36% won, -Rs 7,93,755; full period 24342 trades, -Rs 28,92,493. The difference in one line: the standard Hull doubles the fast (27) average and subtracts the slow (55) one to cut lag; Boss's script does the reverse, so its line lags MORE than a plain 55-minute average (about 29 minutes on a steady move, against about 1 for the standard one).
- Twin test on the last 6 months (random entry times, same exits): p = 0.978; BH q = 1.00 across 5,712 tests.
- Gates failed by the headline (all five, calls + puts, 1 lot): final net <= 0; twin p 0.978 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final.

## Net per index and lot, the picked exit (X16), calls + puts

### Full period (20 Nov 2024 - 6 Oct 2026)

| | trades | win | net | avg win / avg loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| NIFTY, 1 lot | 3410 | 35% | -Rs 2,17,464 | Rs 1,212 / -Rs 756 | -Rs 11,027 | Rs 2,35,943 | -Rs 44,879 |
| NIFTY, 2 lots | 3410 | 36% | -Rs 2,73,977 | Rs 2,430 / -Rs 1,479 | -Rs 21,723 | Rs 3,16,286 | -Rs 81,830 |
| NIFTY, 3 lots | 3410 | 36% | -Rs 3,30,489 | Rs 3,642 / -Rs 2,204 | -Rs 32,419 | Rs 4,05,989 | -Rs 1,18,779 |
| BANKNIFTY, 1 lot | 3054 | 41% | -Rs 3,00,002 | Rs 1,220 / -Rs 997 | -Rs 15,296 | Rs 3,16,056 | -Rs 66,775 |
| BANKNIFTY, 2 lots | 3054 | 41% | -Rs 4,55,855 | Rs 2,436 / -Rs 1,975 | -Rs 30,262 | Rs 5,02,123 | -Rs 1,27,130 |
| BANKNIFTY, 3 lots | 3054 | 42% | -Rs 6,11,708 | Rs 3,658 / -Rs 2,951 | -Rs 45,227 | Rs 6,88,190 | -Rs 1,87,485 |
| FINNIFTY, 1 lot | 2961 | 41% | -Rs 3,60,627 | Rs 1,144 / -Rs 1,014 | -Rs 26,182 | Rs 3,68,626 | -Rs 62,445 |
| FINNIFTY, 2 lots | 2961 | 42% | -Rs 5,87,055 | Rs 2,292 / -Rs 2,007 | -Rs 51,946 | Rs 6,05,413 | -Rs 1,18,459 |
| FINNIFTY, 3 lots | 2961 | 42% | -Rs 8,15,502 | Rs 3,446 / -Rs 2,998 | -Rs 77,775 | Rs 8,44,219 | -Rs 1,74,672 |
| MIDCPNIFTY, 1 lot | 2969 | 42% | -Rs 3,67,161 | Rs 1,641 / -Rs 1,389 | -Rs 20,760 | Rs 3,85,906 | -Rs 52,966 |
| MIDCPNIFTY, 2 lots | 2969 | 42% | -Rs 6,03,221 | Rs 3,276 / -Rs 2,765 | -Rs 41,214 | Rs 6,64,970 | -Rs 99,741 |
| MIDCPNIFTY, 3 lots | 2969 | 43% | -Rs 8,45,700 | Rs 4,923 / -Rs 4,135 | -Rs 61,692 | Rs 9,50,014 | -Rs 1,46,762 |
| SENSEX, 1 lot | 3476 | 35% | -Rs 1,98,145 | Rs 1,104 / -Rs 672 | -Rs 11,977 | Rs 2,06,122 | -Rs 27,171 |
| SENSEX, 2 lots | 3476 | 36% | -Rs 2,32,223 | Rs 2,199 / -Rs 1,315 | -Rs 23,577 | Rs 2,48,555 | -Rs 47,215 |
| SENSEX, 3 lots | 3476 | 36% | -Rs 2,66,301 | Rs 3,298 / -Rs 1,957 | -Rs 35,176 | Rs 2,90,988 | -Rs 67,258 |
| all five, 1 lot | 15870 | 38% | -Rs 14,43,399 | Rs 1,266 / -Rs 939 | -Rs 51,697 | Rs 14,74,293 | -Rs 1,75,398 |
| all five, 2 lots | 15870 | 39% | -Rs 21,52,332 | Rs 2,529 / -Rs 1,855 | -Rs 1,01,323 | Rs 22,23,694 | -Rs 3,20,666 |
| all five, 3 lots | 15870 | 39% | -Rs 28,69,700 | Rs 3,796 / -Rs 2,769 | -Rs 1,51,015 | Rs 29,81,474 | -Rs 4,66,123 |

### Last 6 months (1 Apr - 6 Oct 2026)

| | trades | win | net | avg win / avg loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| NIFTY, 1 lot | 913 | 36% | -Rs 74,064 | Rs 1,019 / -Rs 695 | -Rs 10,218 | Rs 92,431 | -Rs 38,096 |
| NIFTY, 2 lots | 913 | 36% | -Rs 1,05,034 | Rs 2,049 / -Rs 1,357 | -Rs 19,681 | Rs 1,47,120 | -Rs 69,160 |
| NIFTY, 3 lots | 913 | 37% | -Rs 1,36,004 | Rs 3,069 / -Rs 2,023 | -Rs 29,144 | Rs 2,11,170 | -Rs 1,00,223 |
| BANKNIFTY, 1 lot | 827 | 38% | -Rs 1,61,372 | Rs 1,297 / -Rs 1,122 | -Rs 15,296 | Rs 1,83,478 | -Rs 66,775 |
| BANKNIFTY, 2 lots | 827 | 39% | -Rs 2,83,709 | Rs 2,600 / -Rs 2,220 | -Rs 30,262 | Rs 3,31,509 | -Rs 1,27,130 |
| BANKNIFTY, 3 lots | 827 | 39% | -Rs 4,06,046 | Rs 3,924 / -Rs 3,306 | -Rs 45,227 | Rs 4,79,539 | -Rs 1,87,485 |
| FINNIFTY, 1 lot | 766 | 38% | -Rs 1,87,663 | Rs 1,119 / -Rs 1,090 | -Rs 15,824 | Rs 1,92,399 | -Rs 53,443 |
| FINNIFTY, 2 lots | 766 | 39% | -Rs 3,40,125 | Rs 2,254 / -Rs 2,152 | -Rs 31,366 | Rs 3,51,000 | -Rs 1,00,593 |
| FINNIFTY, 3 lots | 766 | 39% | -Rs 4,92,730 | Rs 3,381 / -Rs 3,220 | -Rs 46,907 | Rs 5,09,742 | -Rs 1,47,777 |
| MIDCPNIFTY, 1 lot | 802 | 42% | -Rs 1,24,304 | Rs 1,434 / -Rs 1,318 | -Rs 14,144 | Rs 1,44,173 | -Rs 41,278 |
| MIDCPNIFTY, 2 lots | 802 | 43% | -Rs 2,14,171 | Rs 2,843 / -Rs 2,639 | -Rs 27,971 | Rs 2,66,021 | -Rs 76,334 |
| MIDCPNIFTY, 3 lots | 802 | 43% | -Rs 3,05,154 | Rs 4,272 / -Rs 3,947 | -Rs 41,858 | Rs 3,88,535 | -Rs 1,11,774 |
| SENSEX, 1 lot | 934 | 34% | -Rs 85,438 | Rs 1,076 / -Rs 697 | -Rs 7,566 | Rs 98,947 | -Rs 27,171 |
| SENSEX, 2 lots | 934 | 36% | -Rs 1,26,791 | Rs 2,114 / -Rs 1,377 | -Rs 14,753 | Rs 1,55,272 | -Rs 47,215 |
| SENSEX, 3 lots | 934 | 36% | -Rs 1,68,144 | Rs 3,157 / -Rs 2,055 | -Rs 21,941 | Rs 2,11,598 | -Rs 67,258 |
| all five, 1 lot | 4242 | 38% | -Rs 6,32,841 | Rs 1,192 / -Rs 957 | -Rs 40,723 | Rs 6,84,495 | -Rs 1,75,398 |
| all five, 2 lots | 4242 | 38% | -Rs 10,69,830 | Rs 2,377 / -Rs 1,895 | -Rs 79,489 | Rs 11,83,030 | -Rs 3,20,666 |
| all five, 3 lots | 4242 | 39% | -Rs 15,08,077 | Rs 3,567 / -Rs 2,830 | -Rs 1,18,308 | Rs 16,82,770 | -Rs 4,66,123 |

## Which exit research picked, and how it held up

| exit | design folds (1 lot, all five) | design net | last 6 months: trades | win | net |
|---|---|---|---|---|---|
| X16 time stop 30 minutes (picked) | -Rs 4,53,398, -Rs 1,43,545, -Rs 1,15,476, -Rs 98,139 | -Rs 8,10,558 | 4242 | 38% | -Rs 6,32,841 |
| X07 +30% premium target | -Rs 3,40,846, -Rs 2,99,277, -Rs 1,10,065, -Rs 1,77,500 | -Rs 9,27,688 | 2185 | 32% | -Rs 6,75,480 |
| X04 -15% premium stop alone | -Rs 6,27,265, -Rs 1,73,428, -Rs 1,95,403, -Rs 61,937 | -Rs 10,58,033 | 1645 | 22% | -Rs 5,74,361 |

The walk-forward of the choice itself (pick on earlier folds, score the next): -Rs 5,12,892 (picks X07, X16, X16 for folds 2-4).

## What it means

- Of all 16 exits on the same entries, the best last-6-months result was X08 (index stop at the signal candle's low / high): 4880 trades, -Rs 2,75,626 (a secondary number, not gated; picking it after seeing the holdout would be hindsight).
- Where the money went (picked rule, full period, 1 lot, 15870 trades): before any cost the option moves made Rs 1,19,488; slippage and charges took Rs 15,62,887 (about Rs 98 a trade). The line's signal is close to a coin toss on 1-minute candles, and it trades about 6.8 times a day per index, so costs decide the result.
- Exits with a positive design net (all five, 1 lot): 0 of 16; with a positive last 6 months: 0 of 16.
- To test Boss's full rule (1-minute AND 10-second), the 10-second candles are needed: about a year of 10-second index (or near-futures) bars. The phone's tick zone records per-second NIFTY and BANKNIFTY futures since 12 Oct; Boss could export them with `research/data/decrypt_live.py` on their own PC, but 23 sessions is too few to judge. The full rule can only take a subset of these 1-minute entries.
- Data: Dhan's 1-minute index candles and 1-minute option prices (nearest expiry, ATM +/- 10), real fills (R17 slippage; stops sold as stop orders) and dated Zerodha charges. Signal source: the index spot.
