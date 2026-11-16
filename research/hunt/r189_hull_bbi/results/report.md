# R189 report: Boss's Hull line, 2 closed 1-minute candles beyond the line, buy the option

Pre-registration: `research/hunt/r189_hull_bbi/PREREG.md` (committed before any number). **1-min only, no 10-sec data** (PREREG section 1). Signal source of every rule: the index spot (1-minute candles); the option is only traded; premium stops / targets / locks on the option. Data: the `dhan-data` branch (Dhan v2, fetched 6 Oct 2026), read with research/obuy/data.py; NIFTY and SENSEX weekly, BANKNIFTY, FINNIFTY and MIDCPNIFTY monthly options (nearest expiry). Fills: R51's `simulate` with R17 slippage; dated Zerodha charges.

Tests: 1026 cells (19 rule sets x 6 index scopes x 3 sides x 3 lots); BH m = 5,712.

## 1. The exit choice (design only)

Rule: no exit had all 4 folds > 0: best design net. Order: X16, X07, X04, X11, X06, X01, X12, X15, X05, X14, X08, X02, X13, X10, X03, X09. Picked **X16**; next two: X07, X04. Exits with all four folds > 0: none.

| exit | fold 1 | fold 2 | fold 3 | fold 4 | design net | design trades | win | last 6 months net (secondary) | trades | full net |
|---|---|---|---|---|---|---|---|---|---|---|
| X16 time stop 30 minutes | -Rs 4,53,398 | -Rs 1,43,545 | -Rs 1,15,476 | -Rs 98,139 | -Rs 8,10,558 | 11628 | 39% | -Rs 6,32,841 | 4242 | -Rs 14,43,399 |
| X07 +30% premium target | -Rs 3,40,846 | -Rs 2,99,277 | -Rs 1,10,065 | -Rs 1,77,500 | -Rs 9,27,687 | 6566 | 35% | -Rs 6,75,480 | 2185 | -Rs 16,03,166 |
| X04 -15% premium stop alone | -Rs 6,27,265 | -Rs 1,73,428 | -Rs 1,95,403 | -Rs 61,937 | -Rs 10,58,032 | 4550 | 23% | -Rs 5,74,361 | 1645 | -Rs 16,32,393 |
| X11 step lock: keep 50% of the best gain from +20% | -Rs 5,73,357 | -Rs 1,86,359 | -Rs 1,99,501 | -Rs 1,52,231 | -Rs 11,11,448 | 6740 | 41% | -Rs 4,94,875 | 2238 | -Rs 16,06,323 |
| X06 +20% premium target | -Rs 4,40,274 | -Rs 3,39,770 | -Rs 1,97,281 | -Rs 1,69,211 | -Rs 11,46,535 | 7282 | 41% | -Rs 7,12,300 | 2426 | -Rs 18,58,835 |
| X01 1-min line flips against the trade | -Rs 6,11,430 | -Rs 74,250 | -Rs 1,61,671 | -Rs 3,15,550 | -Rs 11,62,901 | 11336 | 30% | -Rs 5,10,519 | 4167 | -Rs 16,73,420 |
| X12 step lock: keep 60% of the best gain from +10% | -Rs 5,43,538 | -Rs 2,02,230 | -Rs 2,00,590 | -Rs 2,45,034 | -Rs 11,91,392 | 8328 | 52% | -Rs 5,92,414 | 2802 | -Rs 17,83,805 |
| X15 time stop 15 minutes | -Rs 4,46,024 | -Rs 3,41,826 | -Rs 1,91,169 | -Rs 2,42,601 | -Rs 12,21,620 | 14727 | 40% | -Rs 7,46,056 | 5406 | -Rs 19,67,676 |
| X05 +10% premium target | -Rs 4,59,715 | -Rs 3,34,649 | -Rs 2,41,757 | -Rs 2,79,856 | -Rs 13,15,977 | 8728 | 55% | -Rs 7,18,755 | 2952 | -Rs 20,34,732 |
| X14 time stop 10 minutes | -Rs 4,49,306 | -Rs 3,81,112 | -Rs 2,17,976 | -Rs 2,95,843 | -Rs 13,44,237 | 16259 | 40% | -Rs 7,36,390 | 5994 | -Rs 20,80,627 |
| X08 index stop at the signal candle's low / high | -Rs 4,82,810 | -Rs 3,61,435 | -Rs 2,70,483 | -Rs 2,92,002 | -Rs 14,06,730 | 13010 | 17% | -Rs 2,75,626 | 4880 | -Rs 16,82,356 |
| X02 1-min candle closes back across the line | -Rs 7,26,233 | -Rs 1,41,328 | -Rs 3,05,956 | -Rs 3,64,930 | -Rs 15,38,448 | 17989 | 23% | -Rs 3,85,980 | 6721 | -Rs 19,24,428 |
| X13 time stop 5 minutes | -Rs 4,88,508 | -Rs 3,35,116 | -Rs 3,50,898 | -Rs 3,96,859 | -Rs 15,71,381 | 18057 | 39% | -Rs 7,59,663 | 6753 | -Rs 23,31,044 |
| X10 index stop + 2R index target | -Rs 4,59,477 | -Rs 4,69,497 | -Rs 3,65,699 | -Rs 3,74,801 | -Rs 16,69,474 | 18208 | 33% | -Rs 6,88,123 | 6797 | -Rs 23,57,597 |
| X03 1-min flip or close back across (the brief's first primary) | -Rs 7,89,371 | -Rs 1,61,800 | -Rs 3,25,200 | -Rs 4,10,991 | -Rs 16,87,362 | 18524 | 24% | -Rs 4,09,723 | 6911 | -Rs 20,97,085 |
| X09 index stop + 1.5R index target | -Rs 4,79,085 | -Rs 4,70,006 | -Rs 3,76,996 | -Rs 3,88,290 | -Rs 17,14,377 | 18327 | 34% | -Rs 7,14,062 | 6848 | -Rs 24,28,439 |

Selection walk-forward: -Rs 5,12,892, picks ['X07', 'X16', 'X16'] for folds 2-4. Not run (no data): X17 10-sec line flips against the trade; X18 10-sec candle closes back across the line.

## 2. Verdict cells (the picked exit and its three entry variants)

| rule | index | side | lots | final trades | final net | net w/o top 5 | design WF | twin p | BH q | q (R189 only) | gates failed |
|---|---|---|---|---|---|---|---|---|---|---|---|
| BOSS-X16 | NIFTY | C | 1 | 478 | -Rs 41,925 | -Rs 70,834 | -Rs 62,486 | 0.468 | 1.00 | 1.00 | final net <= 0; twin p 0.468 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | C | 2 | 478 | -Rs 61,289 | -Rs 1,19,343 | -Rs 84,427 | 0.468 | 1.00 | 1.00 | final net <= 0; twin p 0.468 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | C | 3 | 478 | -Rs 80,653 | -Rs 1,67,851 | -Rs 1,06,368 | 0.467 | 1.00 | 1.00 | final net <= 0; twin p 0.467 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | P | 1 | 435 | -Rs 32,138 | -Rs 65,916 | -Rs 15,234 | 0.374 | 1.00 | 1.00 | final net <= 0; twin p 0.374 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | P | 2 | 435 | -Rs 43,745 | -Rs 1,11,535 | Rs 6,868 | 0.374 | 1.00 | 1.00 | final net <= 0; twin p 0.374 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | P | 3 | 435 | -Rs 55,351 | -Rs 1,57,155 | Rs 28,969 | 0.374 | 1.00 | 1.00 | final net <= 0; twin p 0.374 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | both | 1 | 913 | -Rs 74,064 | -Rs 1,17,946 | -Rs 77,719 | 0.399 | 1.00 | 1.00 | final net <= 0; twin p 0.399 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | both | 2 | 913 | -Rs 1,05,034 | -Rs 1,93,036 | -Rs 77,559 | 0.398 | 1.00 | 1.00 | final net <= 0; twin p 0.398 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | NIFTY | both | 3 | 913 | -Rs 1,36,004 | -Rs 2,68,124 | -Rs 77,398 | 0.398 | 1.00 | 1.00 | final net <= 0; twin p 0.398 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | C | 1 | 420 | -Rs 1,09,025 | -Rs 1,39,049 | -Rs 25,163 | 0.991 | 1.00 | 1.00 | final net <= 0; twin p 0.991 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | C | 2 | 420 | -Rs 1,98,226 | -Rs 2,58,511 | -Rs 19,836 | 0.991 | 1.00 | 1.00 | final net <= 0; twin p 0.991 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | C | 3 | 420 | -Rs 2,87,427 | -Rs 3,77,972 | -Rs 14,508 | 0.991 | 1.00 | 1.00 | final net <= 0; twin p 0.991 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | P | 1 | 407 | -Rs 52,347 | -Rs 92,675 | -Rs 66,700 | 0.586 | 1.00 | 1.00 | final net <= 0; twin p 0.586 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | P | 2 | 407 | -Rs 85,483 | -Rs 1,66,376 | -Rs 1,03,003 | 0.585 | 1.00 | 1.00 | final net <= 0; twin p 0.585 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | P | 3 | 407 | -Rs 1,18,619 | -Rs 2,40,076 | -Rs 1,39,306 | 0.585 | 1.00 | 1.00 | final net <= 0; twin p 0.585 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | both | 1 | 827 | -Rs 1,61,372 | -Rs 2,06,518 | -Rs 91,863 | 0.974 | 1.00 | 1.00 | final net <= 0; twin p 0.974 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | both | 2 | 827 | -Rs 2,83,709 | -Rs 3,74,238 | -Rs 1,22,839 | 0.974 | 1.00 | 1.00 | final net <= 0; twin p 0.974 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | BANKNIFTY | both | 3 | 827 | -Rs 4,06,046 | -Rs 5,41,957 | -Rs 1,53,813 | 0.974 | 1.00 | 1.00 | final net <= 0; twin p 0.974 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | C | 1 | 409 | -Rs 1,12,496 | -Rs 1,40,849 | -Rs 45,346 | 0.933 | 1.00 | 1.00 | final net <= 0; twin p 0.933 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | C | 2 | 409 | -Rs 2,06,226 | -Rs 2,63,162 | -Rs 61,743 | 0.930 | 1.00 | 1.00 | final net <= 0; twin p 0.930 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | C | 3 | 409 | -Rs 3,00,001 | -Rs 3,85,524 | -Rs 78,531 | 0.930 | 1.00 | 1.00 | final net <= 0; twin p 0.930 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | P | 1 | 357 | -Rs 75,168 | -Rs 1,00,696 | -Rs 43,124 | 0.810 | 1.00 | 1.00 | final net <= 0; twin p 0.810 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | P | 2 | 357 | -Rs 1,33,899 | -Rs 1,85,179 | -Rs 58,522 | 0.805 | 1.00 | 1.00 | final net <= 0; twin p 0.805 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | P | 3 | 357 | -Rs 1,92,728 | -Rs 2,69,758 | -Rs 74,314 | 0.804 | 1.00 | 1.00 | final net <= 0; twin p 0.804 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | both | 1 | 766 | -Rs 1,87,663 | -Rs 2,20,533 | -Rs 88,470 | 0.903 | 1.00 | 1.00 | final net <= 0; twin p 0.903 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | both | 2 | 766 | -Rs 3,40,125 | -Rs 4,06,094 | -Rs 1,20,264 | 0.902 | 1.00 | 1.00 | final net <= 0; twin p 0.902 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | FINNIFTY | both | 3 | 766 | -Rs 4,92,730 | -Rs 5,91,802 | -Rs 1,52,846 | 0.902 | 1.00 | 1.00 | final net <= 0; twin p 0.902 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | C | 1 | 420 | -Rs 62,336 | -Rs 90,663 | -Rs 1,81,124 | 0.743 | 1.00 | 1.00 | final net <= 0; twin p 0.743 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | C | 2 | 420 | -Rs 1,06,791 | -Rs 1,63,680 | -Rs 3,32,273 | 0.745 | 1.00 | 1.00 | final net <= 0; twin p 0.745 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | C | 3 | 420 | -Rs 1,51,624 | -Rs 2,37,076 | -Rs 4,85,275 | 0.743 | 1.00 | 1.00 | final net <= 0; twin p 0.743 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | P | 1 | 382 | -Rs 61,968 | -Rs 1,00,121 | Rs 15,133 | 0.578 | 1.00 | 1.00 | final net <= 0; twin p 0.578 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | P | 2 | 382 | -Rs 1,07,380 | -Rs 1,83,910 | Rs 57,149 | 0.577 | 1.00 | 1.00 | final net <= 0; twin p 0.577 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | P | 3 | 382 | -Rs 1,53,529 | -Rs 2,68,424 | Rs 97,705 | 0.577 | 1.00 | 1.00 | final net <= 0; twin p 0.577 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | both | 1 | 802 | -Rs 1,24,304 | -Rs 1,63,520 | -Rs 1,65,991 | 0.734 | 1.00 | 1.00 | final net <= 0; twin p 0.734 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | both | 2 | 802 | -Rs 2,14,171 | -Rs 2,92,826 | -Rs 2,75,124 | 0.734 | 1.00 | 1.00 | final net <= 0; twin p 0.734 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | MIDCPNIFTY | both | 3 | 802 | -Rs 3,05,154 | -Rs 4,23,236 | -Rs 3,87,570 | 0.734 | 1.00 | 1.00 | final net <= 0; twin p 0.734 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | C | 1 | 483 | -Rs 36,866 | -Rs 63,909 | -Rs 53,949 | 0.723 | 1.00 | 1.00 | final net <= 0; twin p 0.723 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | C | 2 | 483 | -Rs 50,935 | -Rs 1,05,258 | -Rs 68,015 | 0.723 | 1.00 | 1.00 | final net <= 0; twin p 0.723 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | C | 3 | 483 | -Rs 65,004 | -Rs 1,46,606 | -Rs 82,081 | 0.723 | 1.00 | 1.00 | final net <= 0; twin p 0.723 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | P | 1 | 451 | -Rs 48,572 | -Rs 77,839 | -Rs 34,899 | 0.896 | 1.00 | 1.00 | final net <= 0; twin p 0.896 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | P | 2 | 451 | -Rs 75,856 | -Rs 1,34,625 | -Rs 30,008 | 0.896 | 1.00 | 1.00 | final net <= 0; twin p 0.896 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | P | 3 | 451 | -Rs 1,03,140 | -Rs 1,91,413 | -Rs 25,118 | 0.896 | 1.00 | 1.00 | final net <= 0; twin p 0.896 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | both | 1 | 934 | -Rs 85,438 | -Rs 1,20,834 | -Rs 88,848 | 0.895 | 1.00 | 1.00 | final net <= 0; twin p 0.895 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | both | 2 | 934 | -Rs 1,26,791 | -Rs 1,97,820 | -Rs 98,023 | 0.895 | 1.00 | 1.00 | final net <= 0; twin p 0.895 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | SENSEX | both | 3 | 934 | -Rs 1,68,144 | -Rs 2,74,805 | -Rs 1,07,198 | 0.895 | 1.00 | 1.00 | final net <= 0; twin p 0.895 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | C | 1 | 2210 | -Rs 3,62,649 | -Rs 4,03,479 | -Rs 3,68,069 | 0.993 | 1.00 | 1.00 | final net <= 0; twin p 0.993 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | C | 2 | 2210 | -Rs 6,23,468 | -Rs 7,05,364 | -Rs 5,66,293 | 0.993 | 1.00 | 1.00 | final net <= 0; twin p 0.993 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | C | 3 | 2210 | -Rs 8,84,710 | -Rs 10,07,671 | -Rs 7,66,762 | 0.993 | 1.00 | 1.00 | final net <= 0; twin p 0.993 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | P | 1 | 2032 | -Rs 2,70,192 | -Rs 3,31,794 | -Rs 1,44,823 | 0.811 | 1.00 | 1.00 | final net <= 0; twin p 0.811 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | P | 2 | 2032 | -Rs 4,46,362 | -Rs 5,69,790 | -Rs 1,27,516 | 0.808 | 1.00 | 1.00 | final net <= 0; twin p 0.808 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | P | 3 | 2032 | -Rs 6,23,368 | -Rs 8,08,628 | -Rs 1,12,064 | 0.807 | 1.00 | 1.00 | final net <= 0; twin p 0.807 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | both | 1 | 4242 | -Rs 6,32,841 | -Rs 6,96,340 | -Rs 5,12,892 | 0.978 | 1.00 | 1.00 | final net <= 0; twin p 0.978 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | both | 2 | 4242 | -Rs 10,69,830 | -Rs 11,97,052 | -Rs 6,93,809 | 0.978 | 1.00 | 1.00 | final net <= 0; twin p 0.978 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| BOSS-X16 | all | both | 3 | 4242 | -Rs 15,08,077 | -Rs 16,99,028 | -Rs 8,78,825 | 0.978 | 1.00 | 1.00 | final net <= 0; twin p 0.978 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | C | 1 | 722 | -Rs 1,21,154 | -Rs 1,50,922 | -Rs 2,08,230 | 0.961 | 1.00 | 1.00 | final net <= 0; twin p 0.961 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | C | 2 | 722 | -Rs 2,08,230 | -Rs 2,68,002 | -Rs 3,21,305 | 0.961 | 1.00 | 1.00 | final net <= 0; twin p 0.961 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | C | 3 | 722 | -Rs 2,95,306 | -Rs 3,85,082 | -Rs 4,34,380 | 0.961 | 1.00 | 1.00 | final net <= 0; twin p 0.961 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | P | 1 | 732 | -Rs 1,25,719 | -Rs 1,52,003 | -Rs 1,40,345 | 0.993 | 1.00 | 1.00 | final net <= 0; twin p 0.993 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | P | 2 | 732 | -Rs 2,16,887 | -Rs 2,69,692 | -Rs 1,86,903 | 0.993 | 1.00 | 1.00 | final net <= 0; twin p 0.993 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | P | 3 | 732 | -Rs 3,08,055 | -Rs 3,87,380 | -Rs 2,33,461 | 0.993 | 1.00 | 1.00 | final net <= 0; twin p 0.993 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | both | 1 | 1454 | -Rs 2,46,873 | -Rs 2,80,071 | -Rs 3,48,575 | 0.999 | 1.00 | 1.00 | final net <= 0; twin p 0.999 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | both | 2 | 1454 | -Rs 4,25,117 | -Rs 4,91,749 | -Rs 5,08,208 | 0.999 | 1.00 | 1.00 | final net <= 0; twin p 0.999 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | NIFTY | both | 3 | 1454 | -Rs 6,03,361 | -Rs 7,03,427 | -Rs 6,67,841 | 0.999 | 1.00 | 1.00 | final net <= 0; twin p 0.999 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | C | 1 | 604 | -Rs 92,175 | -Rs 1,38,863 | -Rs 2,13,341 | 0.805 | 1.00 | 1.00 | final net <= 0; twin p 0.805 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | C | 2 | 604 | -Rs 1,55,843 | -Rs 2,49,454 | -Rs 3,47,857 | 0.804 | 1.00 | 1.00 | final net <= 0; twin p 0.804 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | C | 3 | 604 | -Rs 2,19,510 | -Rs 3,60,045 | -Rs 4,82,374 | 0.804 | 1.00 | 1.00 | final net <= 0; twin p 0.804 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | P | 1 | 595 | -Rs 1,16,486 | -Rs 1,45,261 | -Rs 2,15,357 | 0.847 | 1.00 | 1.00 | final net <= 0; twin p 0.847 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | P | 2 | 595 | -Rs 2,04,887 | -Rs 2,62,673 | -Rs 3,53,778 | 0.847 | 1.00 | 1.00 | final net <= 0; twin p 0.847 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | P | 3 | 595 | -Rs 2,93,293 | -Rs 3,80,090 | -Rs 4,92,200 | 0.847 | 1.00 | 1.00 | final net <= 0; twin p 0.847 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | both | 1 | 1199 | -Rs 2,08,661 | -Rs 2,57,829 | -Rs 4,28,698 | 0.913 | 1.00 | 1.00 | final net <= 0; twin p 0.913 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | both | 2 | 1199 | -Rs 3,60,730 | -Rs 4,59,302 | -Rs 7,01,635 | 0.913 | 1.00 | 1.00 | final net <= 0; twin p 0.913 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | BANKNIFTY | both | 3 | 1199 | -Rs 5,12,803 | -Rs 6,60,779 | -Rs 9,74,573 | 0.913 | 1.00 | 1.00 | final net <= 0; twin p 0.913 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | C | 1 | 571 | -Rs 39,190 | -Rs 77,247 | -Rs 2,76,900 | 0.016 | 1.00 | 0.22 | final net <= 0; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | C | 2 | 571 | -Rs 51,999 | -Rs 1,28,342 | -Rs 4,78,695 | 0.016 | 1.00 | 0.22 | final net <= 0; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | C | 3 | 571 | -Rs 64,793 | -Rs 1,79,426 | -Rs 6,82,162 | 0.016 | 1.00 | 0.22 | final net <= 0; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | P | 1 | 596 | -Rs 58,354 | -Rs 87,191 | -Rs 1,77,385 | 0.205 | 1.00 | 1.00 | final net <= 0; twin p 0.205 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | P | 2 | 596 | -Rs 89,177 | -Rs 1,47,074 | -Rs 2,81,535 | 0.200 | 1.00 | 1.00 | final net <= 0; twin p 0.200 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | P | 3 | 596 | -Rs 1,20,114 | -Rs 2,07,078 | -Rs 3,87,256 | 0.199 | 1.00 | 1.00 | final net <= 0; twin p 0.199 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | both | 1 | 1167 | -Rs 97,545 | -Rs 1,36,789 | -Rs 4,54,285 | 0.017 | 1.00 | 0.73 | final net <= 0; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | both | 2 | 1167 | -Rs 1,41,177 | -Rs 2,19,883 | -Rs 7,60,230 | 0.016 | 1.00 | 0.73 | final net <= 0; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | FINNIFTY | both | 3 | 1167 | -Rs 1,84,907 | -Rs 3,03,084 | -Rs 10,69,418 | 0.016 | 1.00 | 0.73 | final net <= 0; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | C | 1 | 618 | -Rs 68,322 | -Rs 1,00,822 | -Rs 2,91,702 | 0.406 | 1.00 | 1.00 | final net <= 0; twin p 0.406 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | C | 2 | 618 | -Rs 1,09,802 | -Rs 1,75,036 | -Rs 5,10,460 | 0.405 | 1.00 | 1.00 | final net <= 0; twin p 0.405 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | C | 3 | 618 | -Rs 1,52,150 | -Rs 2,50,102 | -Rs 7,33,350 | 0.405 | 1.00 | 1.00 | final net <= 0; twin p 0.405 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | P | 1 | 599 | -Rs 38,767 | -Rs 82,432 | -Rs 2,33,870 | 0.083 | 1.00 | 0.78 | final net <= 0; twin p 0.083 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | P | 2 | 599 | -Rs 51,732 | -Rs 1,39,297 | -Rs 3,94,089 | 0.083 | 1.00 | 0.78 | final net <= 0; twin p 0.083 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | P | 3 | 599 | -Rs 65,655 | -Rs 1,97,068 | -Rs 5,58,449 | 0.083 | 1.00 | 0.78 | final net <= 0; twin p 0.083 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | both | 1 | 1217 | -Rs 1,07,090 | -Rs 1,52,522 | -Rs 5,25,571 | 0.129 | 1.00 | 0.85 | final net <= 0; twin p 0.129 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | both | 2 | 1217 | -Rs 1,61,533 | -Rs 2,52,633 | -Rs 9,04,550 | 0.129 | 1.00 | 0.85 | final net <= 0; twin p 0.129 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | MIDCPNIFTY | both | 3 | 1217 | -Rs 2,17,805 | -Rs 3,54,519 | -Rs 12,91,799 | 0.129 | 1.00 | 0.85 | final net <= 0; twin p 0.129 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | C | 1 | 705 | -Rs 44,678 | -Rs 75,269 | -Rs 1,88,599 | 0.342 | 1.00 | 1.00 | final net <= 0; twin p 0.342 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | C | 2 | 705 | -Rs 56,080 | -Rs 1,17,499 | -Rs 2,79,918 | 0.342 | 1.00 | 1.00 | final net <= 0; twin p 0.342 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | C | 3 | 705 | -Rs 67,482 | -Rs 1,59,728 | -Rs 3,71,237 | 0.342 | 1.00 | 1.00 | final net <= 0; twin p 0.342 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | P | 1 | 714 | -Rs 88,908 | -Rs 1,10,599 | -Rs 1,53,010 | 0.954 | 1.00 | 1.00 | final net <= 0; twin p 0.954 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | P | 2 | 714 | -Rs 1,44,115 | -Rs 1,87,734 | -Rs 2,12,894 | 0.954 | 1.00 | 1.00 | final net <= 0; twin p 0.954 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | P | 3 | 714 | -Rs 1,99,322 | -Rs 2,64,868 | -Rs 2,72,779 | 0.954 | 1.00 | 1.00 | final net <= 0; twin p 0.954 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | both | 1 | 1419 | -Rs 1,33,586 | -Rs 1,64,177 | -Rs 3,41,609 | 0.792 | 1.00 | 1.00 | final net <= 0; twin p 0.792 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | both | 2 | 1419 | -Rs 2,00,195 | -Rs 2,61,614 | -Rs 4,92,812 | 0.791 | 1.00 | 1.00 | final net <= 0; twin p 0.791 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | SENSEX | both | 3 | 1419 | -Rs 2,66,805 | -Rs 3,59,051 | -Rs 6,44,016 | 0.791 | 1.00 | 1.00 | final net <= 0; twin p 0.791 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | C | 1 | 3220 | -Rs 3,65,521 | -Rs 4,21,023 | -Rs 11,78,771 | 0.438 | 1.00 | 1.00 | final net <= 0; twin p 0.438 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | C | 2 | 3220 | -Rs 5,81,955 | -Rs 6,93,195 | -Rs 19,38,236 | 0.433 | 1.00 | 1.00 | final net <= 0; twin p 0.433 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | C | 3 | 3220 | -Rs 7,99,242 | -Rs 9,66,221 | -Rs 27,03,503 | 0.432 | 1.00 | 1.00 | final net <= 0; twin p 0.432 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | P | 1 | 3236 | -Rs 4,28,234 | -Rs 4,73,996 | -Rs 9,19,967 | 0.662 | 1.00 | 1.00 | final net <= 0; twin p 0.662 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | P | 2 | 3236 | -Rs 7,06,798 | -Rs 7,98,558 | -Rs 14,29,200 | 0.658 | 1.00 | 1.00 | final net <= 0; twin p 0.658 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | P | 3 | 3236 | -Rs 9,86,440 | -Rs 11,24,161 | -Rs 19,44,145 | 0.658 | 1.00 | 1.00 | final net <= 0; twin p 0.658 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | both | 1 | 6456 | -Rs 7,93,755 | -Rs 8,53,240 | -Rs 20,98,738 | 0.587 | 1.00 | 1.00 | final net <= 0; twin p 0.587 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | both | 2 | 6456 | -Rs 12,88,753 | -Rs 14,07,958 | -Rs 33,67,436 | 0.580 | 1.00 | 1.00 | final net <= 0; twin p 0.580 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| STD-X16 | all | both | 3 | 6456 | -Rs 17,85,682 | -Rs 19,64,608 | -Rs 46,47,648 | 0.578 | 1.00 | 1.00 | final net <= 0; twin p 0.578 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | C | 1 | 479 | -Rs 42,993 | -Rs 69,680 | -Rs 1,30,162 | 0.528 | 1.00 | 1.00 | final net <= 0; twin p 0.528 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | C | 2 | 479 | -Rs 63,377 | -Rs 1,16,987 | -Rs 1,98,257 | 0.526 | 1.00 | 1.00 | final net <= 0; twin p 0.526 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | C | 3 | 479 | -Rs 83,760 | -Rs 1,64,294 | -Rs 2,66,352 | 0.525 | 1.00 | 1.00 | final net <= 0; twin p 0.525 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | P | 1 | 460 | -Rs 31,793 | -Rs 66,639 | -Rs 1,33,920 | 0.479 | 1.00 | 1.00 | final net <= 0; twin p 0.479 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | P | 2 | 460 | -Rs 41,873 | -Rs 1,11,803 | -Rs 2,07,518 | 0.479 | 1.00 | 1.00 | final net <= 0; twin p 0.479 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | P | 3 | 460 | -Rs 51,954 | -Rs 1,56,966 | -Rs 2,81,117 | 0.479 | 1.00 | 1.00 | final net <= 0; twin p 0.479 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | both | 1 | 939 | -Rs 74,785 | -Rs 1,16,487 | -Rs 2,64,082 | 0.522 | 1.00 | 1.00 | final net <= 0; twin p 0.522 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | both | 2 | 939 | -Rs 1,05,250 | -Rs 1,88,889 | -Rs 4,05,775 | 0.522 | 1.00 | 1.00 | final net <= 0; twin p 0.522 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | NIFTY | both | 3 | 939 | -Rs 1,35,714 | -Rs 2,61,291 | -Rs 5,47,468 | 0.522 | 1.00 | 1.00 | final net <= 0; twin p 0.522 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | C | 1 | 420 | -Rs 97,588 | -Rs 1,31,572 | -Rs 90,789 | 0.981 | 1.00 | 1.00 | final net <= 0; twin p 0.981 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | C | 2 | 420 | -Rs 1,75,354 | -Rs 2,43,559 | -Rs 1,26,401 | 0.981 | 1.00 | 1.00 | final net <= 0; twin p 0.981 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | C | 3 | 420 | -Rs 2,53,119 | -Rs 3,55,544 | -Rs 1,62,013 | 0.981 | 1.00 | 1.00 | final net <= 0; twin p 0.981 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | P | 1 | 421 | -Rs 56,947 | -Rs 97,076 | -Rs 98,594 | 0.590 | 1.00 | 1.00 | final net <= 0; twin p 0.590 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | P | 2 | 421 | -Rs 94,023 | -Rs 1,74,517 | -Rs 1,43,994 | 0.589 | 1.00 | 1.00 | final net <= 0; twin p 0.589 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | P | 3 | 421 | -Rs 1,31,099 | -Rs 2,51,957 | -Rs 1,89,394 | 0.589 | 1.00 | 1.00 | final net <= 0; twin p 0.589 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | both | 1 | 841 | -Rs 1,54,535 | -Rs 2,00,234 | -Rs 1,89,383 | 0.963 | 1.00 | 1.00 | final net <= 0; twin p 0.963 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | both | 2 | 841 | -Rs 2,69,378 | -Rs 3,61,011 | -Rs 2,70,395 | 0.963 | 1.00 | 1.00 | final net <= 0; twin p 0.963 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | BANKNIFTY | both | 3 | 841 | -Rs 3,84,219 | -Rs 5,21,787 | -Rs 3,51,407 | 0.963 | 1.00 | 1.00 | final net <= 0; twin p 0.963 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | C | 1 | 439 | -Rs 1,21,661 | -Rs 1,51,769 | -Rs 92,847 | 0.949 | 1.00 | 1.00 | final net <= 0; twin p 0.949 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | C | 2 | 439 | -Rs 2,23,182 | -Rs 2,83,630 | -Rs 1,34,440 | 0.946 | 1.00 | 1.00 | final net <= 0; twin p 0.946 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | C | 3 | 439 | -Rs 3,24,782 | -Rs 4,15,571 | -Rs 1,76,993 | 0.945 | 1.00 | 1.00 | final net <= 0; twin p 0.945 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | P | 1 | 355 | -Rs 49,981 | -Rs 86,490 | -Rs 1,75,553 | 0.558 | 1.00 | 1.00 | final net <= 0; twin p 0.558 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | P | 2 | 355 | -Rs 83,607 | -Rs 1,56,855 | -Rs 3,00,697 | 0.553 | 1.00 | 1.00 | final net <= 0; twin p 0.553 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | P | 3 | 355 | -Rs 1,17,347 | -Rs 2,27,328 | -Rs 4,26,809 | 0.553 | 1.00 | 1.00 | final net <= 0; twin p 0.553 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | both | 1 | 794 | -Rs 1,71,641 | -Rs 2,14,023 | -Rs 2,68,400 | 0.862 | 1.00 | 1.00 | final net <= 0; twin p 0.862 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | both | 2 | 794 | -Rs 3,06,789 | -Rs 3,91,788 | -Rs 4,35,137 | 0.861 | 1.00 | 1.00 | final net <= 0; twin p 0.861 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | FINNIFTY | both | 3 | 794 | -Rs 4,42,129 | -Rs 5,69,745 | -Rs 6,03,802 | 0.859 | 1.00 | 1.00 | final net <= 0; twin p 0.859 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | C | 1 | 426 | -Rs 86,546 | -Rs 1,19,276 | -Rs 1,85,039 | 0.916 | 1.00 | 1.00 | final net <= 0; twin p 0.916 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | C | 2 | 426 | -Rs 1,54,928 | -Rs 2,20,611 | -Rs 3,20,210 | 0.916 | 1.00 | 1.00 | final net <= 0; twin p 0.916 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | C | 3 | 426 | -Rs 2,23,723 | -Rs 3,22,365 | -Rs 4,57,799 | 0.916 | 1.00 | 1.00 | final net <= 0; twin p 0.916 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | P | 1 | 390 | -Rs 55,187 | -Rs 92,455 | -Rs 61,541 | 0.547 | 1.00 | 1.00 | final net <= 0; twin p 0.547 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | P | 2 | 390 | -Rs 93,549 | -Rs 1,68,297 | -Rs 74,027 | 0.547 | 1.00 | 1.00 | final net <= 0; twin p 0.547 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | P | 3 | 390 | -Rs 1,32,630 | -Rs 2,44,852 | -Rs 89,302 | 0.547 | 1.00 | 1.00 | final net <= 0; twin p 0.547 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | both | 1 | 816 | -Rs 1,41,734 | -Rs 1,82,539 | -Rs 2,46,580 | 0.864 | 1.00 | 1.00 | final net <= 0; twin p 0.864 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | both | 2 | 816 | -Rs 2,48,477 | -Rs 3,30,312 | -Rs 3,94,237 | 0.864 | 1.00 | 1.00 | final net <= 0; twin p 0.864 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | MIDCPNIFTY | both | 3 | 816 | -Rs 3,56,353 | -Rs 4,79,205 | -Rs 5,47,101 | 0.864 | 1.00 | 1.00 | final net <= 0; twin p 0.864 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | C | 1 | 481 | -Rs 55,095 | -Rs 79,057 | -Rs 40,865 | 0.883 | 1.00 | 1.00 | final net <= 0; twin p 0.883 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | C | 2 | 481 | -Rs 87,487 | -Rs 1,35,648 | -Rs 22,967 | 0.883 | 1.00 | 1.00 | final net <= 0; twin p 0.883 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | C | 3 | 481 | -Rs 1,19,878 | -Rs 1,92,238 | -Rs 5,068 | 0.883 | 1.00 | 1.00 | final net <= 0; twin p 0.883 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | P | 1 | 438 | -Rs 38,406 | -Rs 67,201 | -Rs 1,14,787 | 0.802 | 1.00 | 1.00 | final net <= 0; twin p 0.802 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | P | 2 | 438 | -Rs 56,138 | -Rs 1,13,965 | -Rs 1,69,725 | 0.802 | 1.00 | 1.00 | final net <= 0; twin p 0.802 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | P | 3 | 438 | -Rs 73,871 | -Rs 1,60,728 | -Rs 2,24,662 | 0.802 | 1.00 | 1.00 | final net <= 0; twin p 0.802 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | both | 1 | 919 | -Rs 93,501 | -Rs 1,26,771 | -Rs 1,55,652 | 0.926 | 1.00 | 1.00 | final net <= 0; twin p 0.926 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | both | 2 | 919 | -Rs 1,43,625 | -Rs 2,10,401 | -Rs 1,92,691 | 0.926 | 1.00 | 1.00 | final net <= 0; twin p 0.926 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | SENSEX | both | 3 | 919 | -Rs 1,93,749 | -Rs 2,94,031 | -Rs 2,29,731 | 0.926 | 1.00 | 1.00 | final net <= 0; twin p 0.926 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | C | 1 | 2245 | -Rs 4,03,882 | -Rs 4,46,644 | -Rs 5,39,703 | 0.998 | 1.00 | 1.00 | final net <= 0; twin p 0.998 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | C | 2 | 2245 | -Rs 7,04,328 | -Rs 7,90,088 | -Rs 8,02,275 | 0.998 | 1.00 | 1.00 | final net <= 0; twin p 0.998 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | C | 3 | 2245 | -Rs 10,05,263 | -Rs 11,34,021 | -Rs 10,68,225 | 0.998 | 1.00 | 1.00 | final net <= 0; twin p 0.998 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | P | 1 | 2064 | -Rs 2,32,314 | -Rs 3,00,417 | -Rs 5,84,396 | 0.681 | 1.00 | 1.00 | final net <= 0; twin p 0.681 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | P | 2 | 2064 | -Rs 3,69,191 | -Rs 5,05,621 | -Rs 8,95,961 | 0.677 | 1.00 | 1.00 | final net <= 0; twin p 0.677 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | P | 3 | 2064 | -Rs 5,06,901 | -Rs 7,11,664 | -Rs 12,11,284 | 0.677 | 1.00 | 1.00 | final net <= 0; twin p 0.677 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | both | 1 | 4309 | -Rs 6,36,196 | -Rs 7,04,299 | -Rs 11,24,098 | 0.977 | 1.00 | 1.00 | final net <= 0; twin p 0.977 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | both | 2 | 4309 | -Rs 10,73,519 | -Rs 12,09,949 | -Rs 16,98,236 | 0.977 | 1.00 | 1.00 | final net <= 0; twin p 0.977 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| LOW-X16 | all | both | 3 | 4309 | -Rs 15,12,164 | -Rs 17,16,927 | -Rs 22,79,509 | 0.976 | 1.00 | 1.00 | final net <= 0; twin p 0.976 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | C | 1 | 461 | -Rs 41,959 | -Rs 76,294 | -Rs 50,972 | 0.437 | 1.00 | 1.00 | final net <= 0; twin p 0.437 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | C | 2 | 461 | -Rs 62,159 | -Rs 1,31,064 | -Rs 44,267 | 0.437 | 1.00 | 1.00 | final net <= 0; twin p 0.437 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | C | 3 | 461 | -Rs 82,358 | -Rs 1,85,834 | -Rs 37,561 | 0.437 | 1.00 | 1.00 | final net <= 0; twin p 0.437 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | P | 1 | 425 | -Rs 25,921 | -Rs 62,029 | -Rs 34,457 | 0.350 | 1.00 | 1.00 | final net <= 0; twin p 0.350 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | P | 2 | 425 | -Rs 31,782 | -Rs 1,04,234 | -Rs 13,360 | 0.350 | 1.00 | 1.00 | final net <= 0; twin p 0.350 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | P | 3 | 425 | -Rs 37,643 | -Rs 1,46,438 | Rs 7,737 | 0.350 | 1.00 | 1.00 | final net <= 0; twin p 0.350 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | both | 1 | 886 | -Rs 67,880 | -Rs 1,15,178 | -Rs 85,429 | 0.362 | 1.00 | 1.00 | final net <= 0; twin p 0.362 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | both | 2 | 886 | -Rs 93,941 | -Rs 1,88,773 | -Rs 57,627 | 0.362 | 1.00 | 1.00 | final net <= 0; twin p 0.362 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | NIFTY | both | 3 | 886 | -Rs 1,20,002 | -Rs 2,62,367 | -Rs 29,823 | 0.362 | 1.00 | 1.00 | final net <= 0; twin p 0.362 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | C | 1 | 420 | -Rs 1,15,286 | -Rs 1,45,927 | -Rs 65,125 | 0.991 | 1.00 | 1.00 | final net <= 0; twin p 0.991 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | C | 2 | 420 | -Rs 2,10,759 | -Rs 2,72,278 | -Rs 77,442 | 0.990 | 1.00 | 1.00 | final net <= 0; twin p 0.990 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | C | 3 | 420 | -Rs 3,06,241 | -Rs 3,98,636 | -Rs 89,761 | 0.990 | 1.00 | 1.00 | final net <= 0; twin p 0.990 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | P | 1 | 404 | -Rs 50,339 | -Rs 93,323 | -Rs 78,647 | 0.564 | 1.00 | 1.00 | final net <= 0; twin p 0.564 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | P | 2 | 404 | -Rs 81,609 | -Rs 1,67,813 | -Rs 1,05,800 | 0.564 | 1.00 | 1.00 | final net <= 0; twin p 0.564 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | P | 3 | 404 | -Rs 1,12,892 | -Rs 2,42,317 | -Rs 1,32,952 | 0.564 | 1.00 | 1.00 | final net <= 0; twin p 0.564 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | both | 1 | 824 | -Rs 1,65,625 | -Rs 2,12,475 | -Rs 1,43,773 | 0.972 | 1.00 | 1.00 | final net <= 0; twin p 0.972 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | both | 2 | 824 | -Rs 2,92,368 | -Rs 3,86,306 | -Rs 1,83,242 | 0.972 | 1.00 | 1.00 | final net <= 0; twin p 0.972 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | BANKNIFTY | both | 3 | 824 | -Rs 4,19,133 | -Rs 5,60,158 | -Rs 2,22,713 | 0.972 | 1.00 | 1.00 | final net <= 0; twin p 0.972 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | C | 1 | 407 | -Rs 80,662 | -Rs 1,11,630 | -Rs 31,081 | 0.358 | 1.00 | 1.00 | final net <= 0; twin p 0.358 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | C | 2 | 407 | -Rs 1,42,455 | -Rs 2,04,628 | -Rs 12,230 | 0.349 | 1.00 | 1.00 | final net <= 0; twin p 0.349 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | C | 3 | 407 | -Rs 2,04,347 | -Rs 2,97,724 | Rs 5,970 | 0.348 | 1.00 | 1.00 | final net <= 0; twin p 0.348 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | P | 1 | 349 | -Rs 42,960 | -Rs 67,309 | -Rs 1,04,323 | 0.368 | 1.00 | 1.00 | final net <= 0; twin p 0.368 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | P | 2 | 349 | -Rs 69,818 | -Rs 1,18,753 | -Rs 1,60,432 | 0.360 | 1.00 | 1.00 | final net <= 0; twin p 0.360 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | P | 3 | 349 | -Rs 96,779 | -Rs 1,70,299 | -Rs 2,17,496 | 0.357 | 1.00 | 1.00 | final net <= 0; twin p 0.357 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | both | 1 | 756 | -Rs 1,23,622 | -Rs 1,57,524 | -Rs 1,35,404 | 0.324 | 1.00 | 1.00 | final net <= 0; twin p 0.324 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | both | 2 | 756 | -Rs 2,12,273 | -Rs 2,80,315 | -Rs 1,72,663 | 0.311 | 1.00 | 1.00 | final net <= 0; twin p 0.311 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | FINNIFTY | both | 3 | 756 | -Rs 3,01,126 | -Rs 4,03,306 | -Rs 2,11,527 | 0.307 | 1.00 | 1.00 | final net <= 0; twin p 0.307 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | C | 1 | 418 | -Rs 49,872 | -Rs 78,841 | -Rs 1,68,908 | 0.400 | 1.00 | 1.00 | final net <= 0; twin p 0.400 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | C | 2 | 418 | -Rs 81,645 | -Rs 1,39,820 | -Rs 2,89,970 | 0.399 | 1.00 | 1.00 | final net <= 0; twin p 0.399 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | C | 3 | 418 | -Rs 1,14,006 | -Rs 2,01,350 | -Rs 4,13,104 | 0.399 | 1.00 | 1.00 | final net <= 0; twin p 0.399 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | P | 1 | 376 | -Rs 47,977 | -Rs 88,076 | -Rs 48,753 | 0.352 | 1.00 | 1.00 | final net <= 0; twin p 0.352 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | P | 2 | 376 | -Rs 79,502 | -Rs 1,59,935 | -Rs 51,358 | 0.351 | 1.00 | 1.00 | final net <= 0; twin p 0.351 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | P | 3 | 376 | -Rs 1,11,674 | -Rs 2,32,443 | -Rs 56,345 | 0.351 | 1.00 | 1.00 | final net <= 0; twin p 0.351 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | both | 1 | 794 | -Rs 97,849 | -Rs 1,39,192 | -Rs 2,17,661 | 0.342 | 1.00 | 1.00 | final net <= 0; twin p 0.342 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | both | 2 | 794 | -Rs 1,61,147 | -Rs 2,44,069 | -Rs 3,41,328 | 0.342 | 1.00 | 1.00 | final net <= 0; twin p 0.342 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | MIDCPNIFTY | both | 3 | 794 | -Rs 2,25,680 | -Rs 3,50,181 | -Rs 4,69,449 | 0.343 | 1.00 | 1.00 | final net <= 0; twin p 0.343 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | C | 1 | 472 | -Rs 44,170 | -Rs 72,866 | -Rs 29,806 | 0.774 | 1.00 | 1.00 | final net <= 0; twin p 0.774 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | C | 2 | 472 | -Rs 66,061 | -Rs 1,23,690 | -Rs 1,556 | 0.774 | 1.00 | 1.00 | final net <= 0; twin p 0.774 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | C | 3 | 472 | -Rs 87,952 | -Rs 1,74,514 | Rs 26,695 | 0.774 | 1.00 | 1.00 | final net <= 0; twin p 0.774 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | P | 1 | 444 | -Rs 53,027 | -Rs 84,696 | -Rs 72,448 | 0.924 | 1.00 | 1.00 | final net <= 0; twin p 0.924 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | P | 2 | 444 | -Rs 85,097 | -Rs 1,48,671 | -Rs 86,556 | 0.924 | 1.00 | 1.00 | final net <= 0; twin p 0.924 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | P | 3 | 444 | -Rs 1,17,167 | -Rs 2,12,646 | -Rs 1,00,665 | 0.924 | 1.00 | 1.00 | final net <= 0; twin p 0.924 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | both | 1 | 916 | -Rs 97,197 | -Rs 1,34,755 | -Rs 1,02,254 | 0.935 | 1.00 | 1.00 | final net <= 0; twin p 0.935 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | both | 2 | 916 | -Rs 1,51,158 | -Rs 2,26,512 | -Rs 88,112 | 0.935 | 1.00 | 1.00 | final net <= 0; twin p 0.935 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | SENSEX | both | 3 | 916 | -Rs 2,05,119 | -Rs 3,18,268 | -Rs 73,970 | 0.935 | 1.00 | 1.00 | final net <= 0; twin p 0.935 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | C | 1 | 2178 | -Rs 3,31,948 | -Rs 3,74,823 | -Rs 3,45,892 | 0.835 | 1.00 | 1.00 | final net <= 0; twin p 0.835 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | C | 2 | 2178 | -Rs 5,63,080 | -Rs 6,49,065 | -Rs 4,25,465 | 0.832 | 1.00 | 1.00 | final net <= 0; twin p 0.832 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | C | 3 | 2178 | -Rs 7,94,905 | -Rs 9,24,001 | -Rs 5,07,761 | 0.831 | 1.00 | 1.00 | final net <= 0; twin p 0.831 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | P | 1 | 1998 | -Rs 2,20,223 | -Rs 2,84,763 | -Rs 3,38,628 | 0.570 | 1.00 | 1.00 | final net <= 0; twin p 0.570 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | P | 2 | 1998 | -Rs 3,47,808 | -Rs 4,77,123 | -Rs 4,17,506 | 0.564 | 1.00 | 1.00 | final net <= 0; twin p 0.564 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | P | 3 | 1998 | -Rs 4,76,155 | -Rs 6,70,245 | -Rs 4,99,721 | 0.563 | 1.00 | 1.00 | final net <= 0; twin p 0.563 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | both | 1 | 4176 | -Rs 5,52,172 | -Rs 6,18,323 | -Rs 6,84,521 | 0.794 | 1.00 | 1.00 | final net <= 0; twin p 0.794 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | both | 2 | 4176 | -Rs 9,10,888 | -Rs 10,43,426 | -Rs 8,42,971 | 0.788 | 1.00 | 1.00 | final net <= 0; twin p 0.788 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |
| ITM-X16 | all | both | 3 | 4176 | -Rs 12,71,059 | -Rs 14,69,985 | -Rs 10,07,482 | 0.788 | 1.00 | 1.00 | final net <= 0; twin p 0.788 >= 0.05; BH q 1.00 > 0.10; final net without the top 5 <= 0; design walk-forward net <= 0; outside the account stop rule (Rs 40k drawdown / -Rs 13k month) in design, final |

## 3. The picked rule (BOSS-X16) by index, side and lots

### full period (20 Nov 2024 - 6 Oct 2026)

| | trades | win | net | avg win / avg loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| NIFTY, calls, 1 lot | 1749 | 35% | -Rs 1,22,170 | Rs 1,235 / -Rs 770 | -Rs 6,411 | Rs 1,38,881 | -Rs 26,032 |
| NIFTY, calls, 2 lots | 1749 | 36% | -Rs 1,61,788 | Rs 2,473 / -Rs 1,508 | -Rs 12,302 | Rs 2,00,732 | -Rs 48,666 |
| NIFTY, calls, 3 lots | 1749 | 36% | -Rs 2,01,406 | Rs 3,697 / -Rs 2,251 | -Rs 18,193 | Rs 2,67,860 | -Rs 71,300 |
| NIFTY, puts, 1 lot | 1661 | 35% | -Rs 95,294 | Rs 1,189 / -Rs 740 | -Rs 6,609 | Rs 1,18,727 | -Rs 21,955 |
| NIFTY, puts, 2 lots | 1661 | 36% | -Rs 1,12,188 | Rs 2,385 / -Rs 1,447 | -Rs 13,030 | Rs 1,69,856 | -Rs 40,229 |
| NIFTY, puts, 3 lots | 1661 | 36% | -Rs 1,29,083 | Rs 3,584 / -Rs 2,154 | -Rs 19,450 | Rs 2,21,013 | -Rs 58,502 |
| NIFTY, calls + puts, 1 lot | 3410 | 35% | -Rs 2,17,464 | Rs 1,212 / -Rs 756 | -Rs 11,027 | Rs 2,35,943 | -Rs 44,879 |
| NIFTY, calls + puts, 2 lots | 3410 | 36% | -Rs 2,73,977 | Rs 2,430 / -Rs 1,479 | -Rs 21,723 | Rs 3,16,286 | -Rs 81,830 |
| NIFTY, calls + puts, 3 lots | 3410 | 36% | -Rs 3,30,489 | Rs 3,642 / -Rs 2,204 | -Rs 32,419 | Rs 4,05,989 | -Rs 1,18,779 |
| BANKNIFTY, calls, 1 lot | 1544 | 40% | -Rs 1,67,919 | Rs 1,272 / -Rs 1,028 | -Rs 9,915 | Rs 1,77,884 | -Rs 30,725 |
| BANKNIFTY, calls, 2 lots | 1544 | 41% | -Rs 2,62,962 | Rs 2,531 / -Rs 2,042 | -Rs 19,642 | Rs 2,85,063 | -Rs 58,335 |
| BANKNIFTY, calls, 3 lots | 1544 | 41% | -Rs 3,58,004 | Rs 3,801 / -Rs 3,049 | -Rs 29,368 | Rs 3,92,242 | -Rs 85,944 |
| BANKNIFTY, puts, 1 lot | 1510 | 41% | -Rs 1,32,082 | Rs 1,168 / -Rs 964 | -Rs 7,828 | Rs 1,60,021 | -Rs 36,050 |
| BANKNIFTY, puts, 2 lots | 1510 | 42% | -Rs 1,92,893 | Rs 2,342 / -Rs 1,905 | -Rs 15,466 | Rs 2,60,476 | -Rs 68,795 |
| BANKNIFTY, puts, 3 lots | 1510 | 42% | -Rs 2,53,704 | Rs 3,514 / -Rs 2,848 | -Rs 23,105 | Rs 3,61,473 | -Rs 1,01,541 |
| BANKNIFTY, calls + puts, 1 lot | 3054 | 41% | -Rs 3,00,002 | Rs 1,220 / -Rs 997 | -Rs 15,296 | Rs 3,16,056 | -Rs 66,775 |
| BANKNIFTY, calls + puts, 2 lots | 3054 | 41% | -Rs 4,55,855 | Rs 2,436 / -Rs 1,975 | -Rs 30,262 | Rs 5,02,123 | -Rs 1,27,130 |
| BANKNIFTY, calls + puts, 3 lots | 3054 | 42% | -Rs 6,11,708 | Rs 3,658 / -Rs 2,951 | -Rs 45,227 | Rs 6,88,190 | -Rs 1,87,485 |
| FINNIFTY, calls, 1 lot | 1527 | 41% | -Rs 1,70,968 | Rs 1,244 / -Rs 1,044 | -Rs 14,828 | Rs 1,74,637 | -Rs 29,175 |
| FINNIFTY, calls, 2 lots | 1527 | 42% | -Rs 2,72,756 | Rs 2,485 / -Rs 2,070 | -Rs 29,426 | Rs 2,81,509 | -Rs 55,088 |
| FINNIFTY, calls, 3 lots | 1527 | 42% | -Rs 3,75,507 | Rs 3,738 / -Rs 3,090 | -Rs 44,070 | Rs 3,89,345 | -Rs 80,993 |
| FINNIFTY, puts, 1 lot | 1434 | 42% | -Rs 1,89,659 | Rs 1,040 / -Rs 981 | -Rs 21,747 | Rs 1,97,393 | -Rs 40,323 |
| FINNIFTY, puts, 2 lots | 1434 | 43% | -Rs 3,14,299 | Rs 2,091 / -Rs 1,939 | -Rs 43,164 | Rs 3,30,759 | -Rs 77,247 |
| FINNIFTY, puts, 3 lots | 1434 | 43% | -Rs 4,39,995 | Rs 3,143 / -Rs 2,898 | -Rs 64,581 | Rs 4,65,170 | -Rs 1,14,245 |
| FINNIFTY, calls + puts, 1 lot | 2961 | 41% | -Rs 3,60,627 | Rs 1,144 / -Rs 1,014 | -Rs 26,182 | Rs 3,68,626 | -Rs 62,445 |
| FINNIFTY, calls + puts, 2 lots | 2961 | 42% | -Rs 5,87,055 | Rs 2,292 / -Rs 2,007 | -Rs 51,946 | Rs 6,05,413 | -Rs 1,18,459 |
| FINNIFTY, calls + puts, 3 lots | 2961 | 42% | -Rs 8,15,502 | Rs 3,446 / -Rs 2,998 | -Rs 77,775 | Rs 8,44,219 | -Rs 1,74,672 |
| MIDCPNIFTY, calls, 1 lot | 1533 | 41% | -Rs 2,42,125 | Rs 1,579 / -Rs 1,383 | -Rs 14,815 | Rs 2,51,477 | -Rs 36,908 |
| MIDCPNIFTY, calls, 2 lots | 1533 | 42% | -Rs 4,17,046 | Rs 3,158 / -Rs 2,750 | -Rs 29,465 | Rs 4,41,991 | -Rs 70,777 |
| MIDCPNIFTY, calls, 3 lots | 1533 | 42% | -Rs 5,95,170 | Rs 4,749 / -Rs 4,110 | -Rs 44,121 | Rs 6,35,553 | -Rs 1,04,700 |
| MIDCPNIFTY, puts, 1 lot | 1436 | 42% | -Rs 1,25,036 | Rs 1,706 / -Rs 1,396 | -Rs 14,720 | Rs 1,46,687 | -Rs 45,375 |
| MIDCPNIFTY, puts, 2 lots | 1436 | 43% | -Rs 1,86,176 | Rs 3,400 / -Rs 2,782 | -Rs 29,181 | Rs 2,40,465 | -Rs 88,288 |
| MIDCPNIFTY, puts, 3 lots | 1436 | 43% | -Rs 2,50,530 | Rs 5,104 / -Rs 4,162 | -Rs 43,684 | Rs 3,37,811 | -Rs 1,31,154 |
| MIDCPNIFTY, calls + puts, 1 lot | 2969 | 42% | -Rs 3,67,161 | Rs 1,641 / -Rs 1,389 | -Rs 20,760 | Rs 3,85,906 | -Rs 52,966 |
| MIDCPNIFTY, calls + puts, 2 lots | 2969 | 42% | -Rs 6,03,221 | Rs 3,276 / -Rs 2,765 | -Rs 41,214 | Rs 6,64,970 | -Rs 99,741 |
| MIDCPNIFTY, calls + puts, 3 lots | 2969 | 43% | -Rs 8,45,700 | Rs 4,923 / -Rs 4,135 | -Rs 61,692 | Rs 9,50,014 | -Rs 1,46,762 |
| SENSEX, calls, 1 lot | 1749 | 36% | -Rs 63,447 | Rs 1,143 / -Rs 698 | -Rs 5,944 | Rs 85,744 | -Rs 15,766 |
| SENSEX, calls, 2 lots | 1749 | 37% | -Rs 44,341 | Rs 2,289 / -Rs 1,364 | -Rs 11,702 | Rs 1,12,725 | -Rs 28,133 |
| SENSEX, calls, 3 lots | 1749 | 37% | -Rs 25,235 | Rs 3,441 / -Rs 2,028 | -Rs 17,482 | Rs 1,42,344 | -Rs 40,500 |
| SENSEX, puts, 1 lot | 1727 | 33% | -Rs 1,34,698 | Rs 1,062 / -Rs 647 | -Rs 7,793 | Rs 1,41,798 | -Rs 26,313 |
| SENSEX, puts, 2 lots | 1727 | 34% | -Rs 1,87,882 | Rs 2,102 / -Rs 1,268 | -Rs 15,445 | Rs 2,12,890 | -Rs 49,511 |
| SENSEX, puts, 3 lots | 1727 | 35% | -Rs 2,41,067 | Rs 3,144 / -Rs 1,888 | -Rs 23,096 | Rs 2,83,982 | -Rs 72,709 |
| SENSEX, calls + puts, 1 lot | 3476 | 35% | -Rs 1,98,145 | Rs 1,104 / -Rs 672 | -Rs 11,977 | Rs 2,06,122 | -Rs 27,171 |
| SENSEX, calls + puts, 2 lots | 3476 | 36% | -Rs 2,32,223 | Rs 2,199 / -Rs 1,315 | -Rs 23,577 | Rs 2,48,555 | -Rs 47,215 |
| SENSEX, calls + puts, 3 lots | 3476 | 36% | -Rs 2,66,301 | Rs 3,298 / -Rs 1,957 | -Rs 35,176 | Rs 2,90,988 | -Rs 67,258 |
| all, calls, 1 lot | 8102 | 38% | -Rs 7,66,629 | Rs 1,295 / -Rs 962 | -Rs 29,024 | Rs 8,00,144 | -Rs 92,118 |
| all, calls, 2 lots | 8102 | 39% | -Rs 11,58,893 | Rs 2,588 / -Rs 1,901 | -Rs 57,201 | Rs 12,36,981 | -Rs 1,68,517 |
| all, calls, 3 lots | 8102 | 39% | -Rs 15,55,322 | Rs 3,887 / -Rs 2,837 | -Rs 85,400 | Rs 16,79,254 | -Rs 2,44,995 |
| all, puts, 1 lot | 7768 | 39% | -Rs 6,76,770 | Rs 1,235 / -Rs 915 | -Rs 28,712 | Rs 7,07,026 | -Rs 1,19,501 |
| all, puts, 2 lots | 7768 | 39% | -Rs 9,93,439 | Rs 2,467 / -Rs 1,807 | -Rs 56,339 | Rs 10,70,194 | -Rs 2,21,600 |
| all, puts, 3 lots | 7768 | 40% | -Rs 13,14,378 | Rs 3,701 / -Rs 2,698 | -Rs 83,985 | Rs 14,38,811 | -Rs 3,23,973 |
| all, calls + puts, 1 lot | 15870 | 38% | -Rs 14,43,399 | Rs 1,266 / -Rs 939 | -Rs 51,697 | Rs 14,74,293 | -Rs 1,75,398 |
| all, calls + puts, 2 lots | 15870 | 39% | -Rs 21,52,332 | Rs 2,529 / -Rs 1,855 | -Rs 1,01,323 | Rs 22,23,694 | -Rs 3,20,666 |
| all, calls + puts, 3 lots | 15870 | 39% | -Rs 28,69,700 | Rs 3,796 / -Rs 2,769 | -Rs 1,51,015 | Rs 29,81,474 | -Rs 4,66,123 |

### design (20 Nov 2024 - 31 Mar 2026)

| | trades | win | net | avg win / avg loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| NIFTY, calls, 1 lot | 1271 | 35% | -Rs 80,245 | Rs 1,310 / -Rs 798 | -Rs 6,102 | Rs 1,09,468 | -Rs 26,032 |
| NIFTY, calls, 2 lots | 1271 | 35% | -Rs 1,00,499 | Rs 2,626 / -Rs 1,562 | -Rs 12,014 | Rs 1,70,319 | -Rs 48,666 |
| NIFTY, calls, 3 lots | 1271 | 36% | -Rs 1,20,753 | Rs 3,936 / -Rs 2,327 | -Rs 17,927 | Rs 2,36,449 | -Rs 71,300 |
| NIFTY, puts, 1 lot | 1226 | 35% | -Rs 63,156 | Rs 1,259 / -Rs 757 | -Rs 6,219 | Rs 84,130 | -Rs 21,779 |
| NIFTY, puts, 2 lots | 1226 | 36% | -Rs 68,444 | Rs 2,518 / -Rs 1,482 | -Rs 12,202 | Rs 1,26,964 | -Rs 39,451 |
| NIFTY, puts, 3 lots | 1226 | 36% | -Rs 73,732 | Rs 3,775 / -Rs 2,207 | -Rs 18,185 | Rs 1,74,847 | -Rs 57,123 |
| NIFTY, calls + puts, 1 lot | 2497 | 35% | -Rs 1,43,401 | Rs 1,285 / -Rs 778 | -Rs 11,027 | Rs 1,84,532 | -Rs 44,879 |
| NIFTY, calls + puts, 2 lots | 2497 | 36% | -Rs 1,68,943 | Rs 2,573 / -Rs 1,522 | -Rs 21,723 | Rs 2,63,431 | -Rs 81,830 |
| NIFTY, calls + puts, 3 lots | 2497 | 36% | -Rs 1,94,485 | Rs 3,857 / -Rs 2,269 | -Rs 32,419 | Rs 3,42,330 | -Rs 1,18,779 |
| BANKNIFTY, calls, 1 lot | 1124 | 41% | -Rs 58,894 | Rs 1,274 / -Rs 981 | -Rs 7,867 | Rs 92,000 | -Rs 25,865 |
| BANKNIFTY, calls, 2 lots | 1124 | 42% | -Rs 64,736 | Rs 2,524 / -Rs 1,954 | -Rs 15,545 | Rs 1,48,129 | -Rs 48,520 |
| BANKNIFTY, calls, 3 lots | 1124 | 43% | -Rs 70,577 | Rs 3,786 / -Rs 2,921 | -Rs 23,224 | Rs 2,05,651 | -Rs 71,175 |
| BANKNIFTY, puts, 1 lot | 1103 | 42% | -Rs 79,736 | Rs 1,112 / -Rs 913 | -Rs 7,828 | Rs 1,19,688 | -Rs 27,580 |
| BANKNIFTY, puts, 2 lots | 1103 | 42% | -Rs 1,07,410 | Rs 2,233 / -Rs 1,802 | -Rs 15,466 | Rs 2,02,040 | -Rs 52,044 |
| BANKNIFTY, puts, 3 lots | 1103 | 43% | -Rs 1,35,085 | Rs 3,344 / -Rs 2,697 | -Rs 23,105 | Rs 2,84,393 | -Rs 76,509 |
| BANKNIFTY, calls + puts, 1 lot | 2227 | 41% | -Rs 1,38,630 | Rs 1,193 / -Rs 948 | -Rs 10,400 | Rs 1,86,415 | -Rs 41,210 |
| BANKNIFTY, calls + puts, 2 lots | 2227 | 42% | -Rs 1,72,146 | Rs 2,380 / -Rs 1,879 | -Rs 20,376 | Rs 2,98,631 | -Rs 75,246 |
| BANKNIFTY, calls + puts, 3 lots | 2227 | 43% | -Rs 2,05,662 | Rs 3,567 / -Rs 2,810 | -Rs 30,352 | Rs 4,25,122 | -Rs 1,09,282 |
| FINNIFTY, calls, 1 lot | 1118 | 42% | -Rs 58,472 | Rs 1,264 / -Rs 1,011 | -Rs 14,828 | Rs 88,935 | -Rs 22,122 |
| FINNIFTY, calls, 2 lots | 1118 | 43% | -Rs 66,530 | Rs 2,521 / -Rs 2,008 | -Rs 29,426 | Rs 1,33,352 | -Rs 41,213 |
| FINNIFTY, calls, 3 lots | 1118 | 43% | -Rs 75,505 | Rs 3,787 / -Rs 2,999 | -Rs 44,070 | Rs 1,78,649 | -Rs 60,427 |
| FINNIFTY, puts, 1 lot | 1077 | 43% | -Rs 1,14,492 | Rs 1,036 / -Rs 958 | -Rs 21,747 | Rs 1,36,490 | -Rs 40,323 |
| FINNIFTY, puts, 2 lots | 1077 | 43% | -Rs 1,80,401 | Rs 2,081 / -Rs 1,895 | -Rs 43,164 | Rs 2,36,595 | -Rs 77,247 |
| FINNIFTY, puts, 3 lots | 1077 | 44% | -Rs 2,47,267 | Rs 3,136 / -Rs 2,826 | -Rs 64,581 | Rs 3,37,578 | -Rs 1,14,245 |
| FINNIFTY, calls + puts, 1 lot | 2195 | 42% | -Rs 1,72,964 | Rs 1,151 / -Rs 985 | -Rs 26,182 | Rs 2,15,047 | -Rs 62,445 |
| FINNIFTY, calls + puts, 2 lots | 2195 | 43% | -Rs 2,46,931 | Rs 2,304 / -Rs 1,953 | -Rs 51,946 | Rs 3,42,749 | -Rs 1,18,459 |
| FINNIFTY, calls + puts, 3 lots | 2195 | 43% | -Rs 3,22,772 | Rs 3,466 / -Rs 2,914 | -Rs 77,775 | Rs 4,72,284 | -Rs 1,74,672 |
| MIDCPNIFTY, calls, 1 lot | 1113 | 41% | -Rs 1,79,789 | Rs 1,656 / -Rs 1,400 | -Rs 14,815 | Rs 1,90,229 | -Rs 36,658 |
| MIDCPNIFTY, calls, 2 lots | 1113 | 41% | -Rs 3,10,254 | Rs 3,320 / -Rs 2,776 | -Rs 29,465 | Rs 3,38,508 | -Rs 70,266 |
| MIDCPNIFTY, calls, 3 lots | 1113 | 41% | -Rs 4,43,546 | Rs 4,988 / -Rs 4,151 | -Rs 44,121 | Rs 4,89,421 | -Rs 1,04,051 |
| MIDCPNIFTY, puts, 1 lot | 1054 | 43% | -Rs 63,068 | Rs 1,783 / -Rs 1,433 | -Rs 14,720 | Rs 1,17,235 | -Rs 44,549 |
| MIDCPNIFTY, puts, 2 lots | 1054 | 43% | -Rs 78,796 | Rs 3,563 / -Rs 2,849 | -Rs 29,181 | Rs 2,24,111 | -Rs 85,584 |
| MIDCPNIFTY, puts, 3 lots | 1054 | 43% | -Rs 97,001 | Rs 5,353 / -Rs 4,260 | -Rs 43,684 | Rs 3,32,474 | -Rs 1,26,739 |
| MIDCPNIFTY, calls + puts, 1 lot | 2167 | 42% | -Rs 2,42,857 | Rs 1,719 / -Rs 1,415 | -Rs 20,760 | Rs 2,76,304 | -Rs 52,966 |
| MIDCPNIFTY, calls + puts, 2 lots | 2167 | 42% | -Rs 3,89,050 | Rs 3,441 / -Rs 2,811 | -Rs 41,214 | Rs 4,71,686 | -Rs 99,741 |
| MIDCPNIFTY, calls + puts, 3 lots | 2167 | 42% | -Rs 5,40,546 | Rs 5,171 / -Rs 4,203 | -Rs 61,692 | Rs 6,72,278 | -Rs 1,46,762 |
| SENSEX, calls, 1 lot | 1266 | 36% | -Rs 26,580 | Rs 1,171 / -Rs 699 | -Rs 5,944 | Rs 54,875 | -Rs 13,406 |
| SENSEX, calls, 2 lots | 1266 | 37% | Rs 6,595 | Rs 2,353 / -Rs 1,362 | -Rs 11,702 | Rs 75,955 | -Rs 22,754 |
| SENSEX, calls, 3 lots | 1266 | 37% | Rs 39,769 | Rs 3,538 / -Rs 2,025 | -Rs 17,482 | Rs 97,789 | -Rs 32,101 |
| SENSEX, puts, 1 lot | 1276 | 33% | -Rs 86,127 | Rs 1,053 / -Rs 629 | -Rs 6,793 | Rs 1,08,676 | -Rs 23,873 |
| SENSEX, puts, 2 lots | 1276 | 34% | -Rs 1,12,027 | Rs 2,099 / -Rs 1,227 | -Rs 13,398 | Rs 1,62,039 | -Rs 44,206 |
| SENSEX, puts, 3 lots | 1276 | 34% | -Rs 1,37,926 | Rs 3,151 / -Rs 1,823 | -Rs 20,002 | Rs 2,16,083 | -Rs 64,538 |
| SENSEX, calls + puts, 1 lot | 2542 | 35% | -Rs 1,12,707 | Rs 1,114 / -Rs 663 | -Rs 11,977 | Rs 1,45,773 | -Rs 19,903 |
| SENSEX, calls + puts, 2 lots | 2542 | 36% | -Rs 1,05,432 | Rs 2,230 / -Rs 1,293 | -Rs 23,577 | Rs 1,81,902 | -Rs 31,640 |
| SENSEX, calls + puts, 3 lots | 2542 | 36% | -Rs 98,157 | Rs 3,350 / -Rs 1,922 | -Rs 35,176 | Rs 2,18,691 | -Rs 43,376 |
| all, calls, 1 lot | 5892 | 39% | -Rs 4,03,981 | Rs 1,333 / -Rs 958 | -Rs 29,024 | Rs 4,67,509 | -Rs 90,404 |
| all, calls, 2 lots | 5892 | 40% | -Rs 5,35,425 | Rs 2,665 / -Rs 1,892 | -Rs 57,201 | Rs 6,84,505 | -Rs 1,62,770 |
| all, calls, 3 lots | 5892 | 40% | -Rs 6,70,612 | Rs 4,000 / -Rs 2,824 | -Rs 85,400 | Rs 9,12,064 | -Rs 2,35,342 |
| all, puts, 1 lot | 5736 | 39% | -Rs 4,06,578 | Rs 1,249 / -Rs 906 | -Rs 28,712 | Rs 4,94,622 | -Rs 1,19,501 |
| all, puts, 2 lots | 5736 | 39% | -Rs 5,47,077 | Rs 2,499 / -Rs 1,787 | -Rs 56,339 | Rs 7,43,644 | -Rs 2,21,600 |
| all, puts, 3 lots | 5736 | 40% | -Rs 6,91,011 | Rs 3,751 / -Rs 2,667 | -Rs 83,985 | Rs 10,29,175 | -Rs 3,23,973 |
| all, calls + puts, 1 lot | 11628 | 39% | -Rs 8,10,558 | Rs 1,292 / -Rs 932 | -Rs 51,697 | Rs 9,16,742 | -Rs 1,27,709 |
| all, calls + puts, 2 lots | 11628 | 39% | -Rs 10,82,502 | Rs 2,583 / -Rs 1,840 | -Rs 1,01,323 | Rs 13,50,721 | -Rs 2,21,693 |
| all, calls + puts, 3 lots | 11628 | 40% | -Rs 13,61,623 | Rs 3,877 / -Rs 2,747 | -Rs 1,51,015 | Rs 17,91,374 | -Rs 3,16,193 |

### last 6 months = final (1 Apr - 6 Oct 2026)

| | trades | win | net | avg win / avg loss | worst day | max drawdown | worst month |
|---|---|---|---|---|---|---|---|
| NIFTY, calls, 1 lot | 478 | 35% | -Rs 41,925 | Rs 1,036 / -Rs 697 | -Rs 6,411 | Rs 60,965 | -Rs 16,141 |
| NIFTY, calls, 2 lots | 478 | 36% | -Rs 61,289 | Rs 2,072 / -Rs 1,365 | -Rs 12,302 | Rs 1,00,596 | -Rs 28,931 |
| NIFTY, calls, 3 lots | 478 | 37% | -Rs 80,653 | Rs 3,078 / -Rs 2,044 | -Rs 18,193 | Rs 1,40,226 | -Rs 41,720 |
| NIFTY, puts, 1 lot | 435 | 37% | -Rs 32,138 | Rs 1,001 / -Rs 693 | -Rs 6,609 | Rs 59,138 | -Rs 21,955 |
| NIFTY, puts, 2 lots | 435 | 37% | -Rs 43,745 | Rs 2,024 / -Rs 1,349 | -Rs 13,030 | Rs 1,08,640 | -Rs 40,229 |
| NIFTY, puts, 3 lots | 435 | 37% | -Rs 55,351 | Rs 3,059 / -Rs 2,000 | -Rs 19,450 | Rs 1,58,169 | -Rs 58,502 |
| NIFTY, calls + puts, 1 lot | 913 | 36% | -Rs 74,064 | Rs 1,019 / -Rs 695 | -Rs 10,218 | Rs 92,431 | -Rs 38,096 |
| NIFTY, calls + puts, 2 lots | 913 | 36% | -Rs 1,05,034 | Rs 2,049 / -Rs 1,357 | -Rs 19,681 | Rs 1,47,120 | -Rs 69,160 |
| NIFTY, calls + puts, 3 lots | 913 | 37% | -Rs 1,36,004 | Rs 3,069 / -Rs 2,023 | -Rs 29,144 | Rs 2,11,170 | -Rs 1,00,223 |
| BANKNIFTY, calls, 1 lot | 420 | 37% | -Rs 1,09,025 | Rs 1,268 / -Rs 1,144 | -Rs 9,915 | Rs 1,34,658 | -Rs 30,725 |
| BANKNIFTY, calls, 2 lots | 420 | 37% | -Rs 1,98,226 | Rs 2,550 / -Rs 2,257 | -Rs 19,642 | Rs 2,51,049 | -Rs 58,335 |
| BANKNIFTY, calls, 3 lots | 420 | 37% | -Rs 2,87,427 | Rs 3,848 / -Rs 3,363 | -Rs 29,368 | Rs 3,67,440 | -Rs 85,944 |
| BANKNIFTY, puts, 1 lot | 407 | 40% | -Rs 52,347 | Rs 1,324 / -Rs 1,099 | -Rs 7,042 | Rs 66,765 | -Rs 36,050 |
| BANKNIFTY, puts, 2 lots | 407 | 41% | -Rs 85,483 | Rs 2,648 / -Rs 2,179 | -Rs 13,990 | Rs 1,20,550 | -Rs 68,795 |
| BANKNIFTY, puts, 3 lots | 407 | 41% | -Rs 1,18,619 | Rs 3,995 / -Rs 3,244 | -Rs 20,937 | Rs 1,74,878 | -Rs 1,01,541 |
| BANKNIFTY, calls + puts, 1 lot | 827 | 38% | -Rs 1,61,372 | Rs 1,297 / -Rs 1,122 | -Rs 15,296 | Rs 1,83,478 | -Rs 66,775 |
| BANKNIFTY, calls + puts, 2 lots | 827 | 39% | -Rs 2,83,709 | Rs 2,600 / -Rs 2,220 | -Rs 30,262 | Rs 3,31,509 | -Rs 1,27,130 |
| BANKNIFTY, calls + puts, 3 lots | 827 | 39% | -Rs 4,06,046 | Rs 3,924 / -Rs 3,306 | -Rs 45,227 | Rs 4,79,539 | -Rs 1,87,485 |
| FINNIFTY, calls, 1 lot | 409 | 37% | -Rs 1,12,496 | Rs 1,180 / -Rs 1,127 | -Rs 7,827 | Rs 1,17,215 | -Rs 29,175 |
| FINNIFTY, calls, 2 lots | 409 | 37% | -Rs 2,06,226 | Rs 2,375 / -Rs 2,225 | -Rs 15,513 | Rs 2,15,854 | -Rs 55,088 |
| FINNIFTY, calls, 3 lots | 409 | 37% | -Rs 3,00,001 | Rs 3,586 / -Rs 3,315 | -Rs 23,199 | Rs 3,14,529 | -Rs 80,993 |
| FINNIFTY, puts, 1 lot | 357 | 40% | -Rs 75,168 | Rs 1,054 / -Rs 1,046 | -Rs 12,598 | Rs 83,374 | -Rs 24,268 |
| FINNIFTY, puts, 2 lots | 357 | 40% | -Rs 1,33,899 | Rs 2,125 / -Rs 2,065 | -Rs 25,101 | Rs 1,50,967 | -Rs 45,505 |
| FINNIFTY, puts, 3 lots | 357 | 41% | -Rs 1,92,728 | Rs 3,166 / -Rs 3,104 | -Rs 37,604 | Rs 2,18,653 | -Rs 66,784 |
| FINNIFTY, calls + puts, 1 lot | 766 | 38% | -Rs 1,87,663 | Rs 1,119 / -Rs 1,090 | -Rs 15,824 | Rs 1,92,399 | -Rs 53,443 |
| FINNIFTY, calls + puts, 2 lots | 766 | 39% | -Rs 3,40,125 | Rs 2,254 / -Rs 2,152 | -Rs 31,366 | Rs 3,51,000 | -Rs 1,00,593 |
| FINNIFTY, calls + puts, 3 lots | 766 | 39% | -Rs 4,92,730 | Rs 3,381 / -Rs 3,220 | -Rs 46,907 | Rs 5,09,742 | -Rs 1,47,777 |
| MIDCPNIFTY, calls, 1 lot | 420 | 44% | -Rs 62,336 | Rs 1,390 / -Rs 1,336 | -Rs 11,169 | Rs 84,208 | -Rs 36,908 |
| MIDCPNIFTY, calls, 2 lots | 420 | 45% | -Rs 1,06,791 | Rs 2,762 / -Rs 2,675 | -Rs 22,150 | Rs 1,51,406 | -Rs 70,777 |
| MIDCPNIFTY, calls, 3 lots | 420 | 45% | -Rs 1,51,624 | Rs 4,163 / -Rs 3,992 | -Rs 33,106 | Rs 2,18,982 | -Rs 1,04,700 |
| MIDCPNIFTY, puts, 1 lot | 382 | 41% | -Rs 61,968 | Rs 1,485 / -Rs 1,299 | -Rs 8,664 | Rs 82,320 | -Rs 45,375 |
| MIDCPNIFTY, puts, 2 lots | 382 | 42% | -Rs 1,07,380 | Rs 2,938 / -Rs 2,601 | -Rs 17,233 | Rs 1,52,098 | -Rs 88,288 |
| MIDCPNIFTY, puts, 3 lots | 382 | 42% | -Rs 1,53,529 | Rs 4,398 / -Rs 3,899 | -Rs 25,815 | Rs 2,23,141 | -Rs 1,31,154 |
| MIDCPNIFTY, calls + puts, 1 lot | 802 | 42% | -Rs 1,24,304 | Rs 1,434 / -Rs 1,318 | -Rs 14,144 | Rs 1,44,173 | -Rs 41,278 |
| MIDCPNIFTY, calls + puts, 2 lots | 802 | 43% | -Rs 2,14,171 | Rs 2,843 / -Rs 2,639 | -Rs 27,971 | Rs 2,66,021 | -Rs 76,334 |
| MIDCPNIFTY, calls + puts, 3 lots | 802 | 43% | -Rs 3,05,154 | Rs 4,272 / -Rs 3,947 | -Rs 41,858 | Rs 3,88,535 | -Rs 1,11,774 |
| SENSEX, calls, 1 lot | 483 | 35% | -Rs 36,866 | Rs 1,067 / -Rs 697 | -Rs 4,349 | Rs 61,073 | -Rs 15,766 |
| SENSEX, calls, 2 lots | 483 | 36% | -Rs 50,935 | Rs 2,119 / -Rs 1,369 | -Rs 8,462 | Rs 1,01,708 | -Rs 28,133 |
| SENSEX, calls, 3 lots | 483 | 36% | -Rs 65,004 | Rs 3,184 / -Rs 2,037 | -Rs 12,575 | Rs 1,42,344 | -Rs 40,500 |
| SENSEX, puts, 1 lot | 451 | 33% | -Rs 48,572 | Rs 1,087 / -Rs 697 | -Rs 7,793 | Rs 57,704 | -Rs 26,313 |
| SENSEX, puts, 2 lots | 451 | 35% | -Rs 75,856 | Rs 2,109 / -Rs 1,384 | -Rs 15,445 | Rs 1,05,072 | -Rs 49,511 |
| SENSEX, puts, 3 lots | 451 | 35% | -Rs 1,03,140 | Rs 3,127 / -Rs 2,074 | -Rs 23,096 | Rs 1,52,439 | -Rs 72,709 |
| SENSEX, calls + puts, 1 lot | 934 | 34% | -Rs 85,438 | Rs 1,076 / -Rs 697 | -Rs 7,566 | Rs 98,947 | -Rs 27,171 |
| SENSEX, calls + puts, 2 lots | 934 | 36% | -Rs 1,26,791 | Rs 2,114 / -Rs 1,377 | -Rs 14,753 | Rs 1,55,272 | -Rs 47,215 |
| SENSEX, calls + puts, 3 lots | 934 | 36% | -Rs 1,68,144 | Rs 3,157 / -Rs 2,055 | -Rs 21,941 | Rs 2,11,598 | -Rs 67,258 |
| all, calls, 1 lot | 2210 | 37% | -Rs 3,62,649 | Rs 1,190 / -Rs 972 | -Rs 20,537 | Rs 4,32,838 | -Rs 92,118 |
| all, calls, 2 lots | 2210 | 38% | -Rs 6,23,468 | Rs 2,378 / -Rs 1,923 | -Rs 40,414 | Rs 7,71,688 | -Rs 1,68,517 |
| all, calls, 3 lots | 2210 | 38% | -Rs 8,84,710 | Rs 3,573 / -Rs 2,870 | -Rs 60,291 | Rs 11,12,273 | -Rs 2,44,995 |
| all, puts, 1 lot | 2032 | 38% | -Rs 2,70,192 | Rs 1,194 / -Rs 941 | -Rs 23,363 | Rs 3,09,275 | -Rs 94,639 |
| all, puts, 2 lots | 2032 | 39% | -Rs 4,46,362 | Rs 2,376 / -Rs 1,864 | -Rs 46,124 | Rs 5,41,196 | -Rs 1,76,103 |
| all, puts, 3 lots | 2032 | 39% | -Rs 6,23,368 | Rs 3,560 / -Rs 2,787 | -Rs 68,879 | Rs 7,75,130 | -Rs 2,57,540 |
| all, calls + puts, 1 lot | 4242 | 38% | -Rs 6,32,841 | Rs 1,192 / -Rs 957 | -Rs 40,723 | Rs 6,84,495 | -Rs 1,75,398 |
| all, calls + puts, 2 lots | 4242 | 38% | -Rs 10,69,830 | Rs 2,377 / -Rs 1,895 | -Rs 79,489 | Rs 11,83,030 | -Rs 3,20,666 |
| all, calls + puts, 3 lots | 4242 | 39% | -Rs 15,08,077 | Rs 3,567 / -Rs 2,830 | -Rs 1,18,308 | Rs 16,82,770 | -Rs 4,66,123 |

### Design folds and exit mix (1 lot, calls + puts)

| index | fold 1 | fold 2 | fold 3 | fold 4 | exit mix (full) |
|---|---|---|---|---|---|
| NIFTY | -Rs 89,567 | -Rs 16,403 | -Rs 18,901 | -Rs 18,529 | time 55%, stop15 42%, squareoff 4%, missing 0% |
| BANKNIFTY | -Rs 58,754 | -Rs 88,995 | -Rs 2,505 | Rs 11,623 | time 82%, stop15 13%, squareoff 5% |
| FINNIFTY | -Rs 1,25,980 | -Rs 9,292 | -Rs 43,569 | Rs 5,878 | time 81%, stop15 14%, squareoff 6%, missing 0% |
| MIDCPNIFTY | -Rs 1,29,019 | Rs 8,097 | -Rs 37,712 | -Rs 84,222 | time 81%, stop15 15%, squareoff 5% |
| SENSEX | -Rs 50,078 | -Rs 36,952 | -Rs 12,789 | -Rs 12,889 | time 56%, stop15 40%, squareoff 3%, missing 0% |
| all | -Rs 4,53,398 | -Rs 1,43,545 | -Rs 1,15,476 | -Rs 98,139 | time 70%, stop15 26%, squareoff 4%, missing 0% |

## 4. Entry variants with the picked exit (all five, 1 lot)

| rule | side | full trades | full win | full net | design net | last 6 months trades | win | net |
|---|---|---|---|---|---|---|---|---|
| BOSS-X16 | calls | 8102 | 38% | -Rs 7,66,629 | -Rs 4,03,981 | 2210 | 37% | -Rs 3,62,649 |
| BOSS-X16 | puts | 7768 | 39% | -Rs 6,76,770 | -Rs 4,06,578 | 2032 | 38% | -Rs 2,70,192 |
| BOSS-X16 | calls + puts | 15870 | 38% | -Rs 14,43,399 | -Rs 8,10,558 | 4242 | 38% | -Rs 6,32,841 |
| STD-X16 | calls | 12259 | 36% | -Rs 15,44,292 | -Rs 11,78,771 | 3220 | 36% | -Rs 3,65,521 |
| STD-X16 | puts | 12083 | 36% | -Rs 13,48,201 | -Rs 9,19,967 | 3236 | 37% | -Rs 4,28,234 |
| STD-X16 | calls + puts | 24342 | 36% | -Rs 28,92,493 | -Rs 20,98,738 | 6456 | 36% | -Rs 7,93,755 |
| LOW-X16 | calls | 8232 | 38% | -Rs 9,43,585 | -Rs 5,39,703 | 2245 | 37% | -Rs 4,03,882 |
| LOW-X16 | puts | 7944 | 38% | -Rs 8,16,709 | -Rs 5,84,396 | 2064 | 38% | -Rs 2,32,314 |
| LOW-X16 | calls + puts | 16176 | 38% | -Rs 17,60,294 | -Rs 11,24,098 | 4309 | 37% | -Rs 6,36,196 |
| ITM-X16 | calls | 7952 | 40% | -Rs 6,77,841 | -Rs 3,45,892 | 2178 | 39% | -Rs 3,31,948 |
| ITM-X16 | puts | 7611 | 41% | -Rs 5,58,851 | -Rs 3,38,628 | 1998 | 40% | -Rs 2,20,223 |
| ITM-X16 | calls + puts | 15563 | 41% | -Rs 12,36,692 | -Rs 6,84,521 | 4176 | 40% | -Rs 5,52,172 |

## 5. Every exit candidate on the last 6 months (secondary, not gated), all five, 1 lot

| exit | calls: trades / win / net | puts: trades / win / net | together: trades / win / net | twin p | worst month | max DD | exit mix |
|---|---|---|---|---|---|---|---|
| X01 1-min line flips against the trade | 2123 / 29% / -Rs 2,71,706 | 2044 / 30% / -Rs 2,38,813 | 4167 / 30% / -Rs 5,10,519 | 0.084 | -Rs 2,24,146 | Rs 6,24,658 | line 65%, stop15 25%, squareoff 10%, missing 0% |
| X02 1-min candle closes back across the line | 3451 / 22% / -Rs 2,28,311 | 3270 / 23% / -Rs 1,57,669 | 6721 / 22% / -Rs 3,85,980 | 0.001 | -Rs 1,18,359 | Rs 5,35,383 | line 89%, stop15 8%, squareoff 3%, missing 0% |
| X03 1-min flip or close back across (the brief's first primary) | 3545 / 23% / -Rs 2,46,556 | 3366 / 24% / -Rs 1,63,166 | 6911 / 24% / -Rs 4,09,723 | 0.000 | -Rs 1,25,025 | Rs 5,51,887 | line 89%, stop15 8%, squareoff 3%, missing 0% |
| X04 -15% premium stop alone | 884 / 22% / -Rs 3,03,744 | 761 / 21% / -Rs 2,70,617 | 1645 / 22% / -Rs 5,74,361 | 1.000 | -Rs 1,83,450 | Rs 6,04,942 | stop15 68%, squareoff 31%, missing 1% |
| X05 +10% premium target | 1526 / 52% / -Rs 3,64,557 | 1426 / 55% / -Rs 3,54,198 | 2952 / 53% / -Rs 7,18,755 | 1.000 | -Rs 2,08,530 | Rs 7,55,041 | target 51%, stop15 39%, squareoff 9%, missing 0% |
| X06 +20% premium target | 1265 / 37% / -Rs 3,67,170 | 1161 / 40% / -Rs 3,45,130 | 2426 / 38% / -Rs 7,12,300 | 1.000 | -Rs 2,17,805 | Rs 7,35,351 | stop15 53%, target 32%, squareoff 15%, missing 0% |
| X07 +30% premium target | 1156 / 31% / -Rs 3,46,550 | 1029 / 32% / -Rs 3,28,930 | 2185 / 32% / -Rs 6,75,480 | 1.000 | -Rs 1,66,791 | Rs 7,16,327 | stop15 59%, target 23%, squareoff 18%, missing 0% |
| X08 index stop at the signal candle's low / high | 2519 / 17% / -Rs 1,58,506 | 2361 / 17% / -Rs 1,17,120 | 4880 / 17% / -Rs 2,75,626 | 0.004 | -Rs 1,21,895 | Rs 3,63,640 | index_stop 89%, squareoff 6%, stop15 5%, missing 0% |
| X09 index stop + 1.5R index target | 3508 / 31% / -Rs 3,88,878 | 3340 / 33% / -Rs 3,25,184 | 6848 / 32% / -Rs 7,14,062 | 0.078 | -Rs 1,41,514 | Rs 7,14,062 | index_stop 63%, index_target 35%, stop15 2%, squareoff 0%, missing 0% |
| X10 index stop + 2R index target | 3483 / 30% / -Rs 3,81,208 | 3314 / 32% / -Rs 3,06,915 | 6797 / 31% / -Rs 6,88,123 | 0.035 | -Rs 1,39,548 | Rs 6,88,123 | index_stop 68%, index_target 30%, stop15 2%, squareoff 0%, missing 0% |
| X11 step lock: keep 50% of the best gain from +20% | 1175 / 38% / -Rs 2,34,627 | 1063 / 39% / -Rs 2,60,248 | 2238 / 38% / -Rs 4,94,875 | 1.000 | -Rs 1,57,720 | Rs 5,10,781 | stop15 53%, lock 28%, squareoff 18%, missing 0% |
| X12 step lock: keep 60% of the best gain from +10% | 1450 / 51% / -Rs 3,20,456 | 1352 / 52% / -Rs 2,71,958 | 2802 / 51% / -Rs 5,92,414 | 1.000 | -Rs 1,84,915 | Rs 6,41,311 | lock 49%, stop15 40%, squareoff 11%, missing 0% |
| X13 time stop 5 minutes | 3460 / 37% / -Rs 3,69,055 | 3293 / 35% / -Rs 3,90,609 | 6753 / 36% / -Rs 7,59,663 | 0.784 | -Rs 1,42,726 | Rs 7,71,265 | time 94%, stop15 6%, missing 0% |
| X14 time stop 10 minutes | 3077 / 38% / -Rs 3,89,664 | 2917 / 39% / -Rs 3,46,726 | 5994 / 38% / -Rs 7,36,390 | 0.905 | -Rs 1,55,090 | Rs 7,47,587 | time 90%, stop15 10%, squareoff 0%, missing 0% |
| X15 time stop 15 minutes | 2775 / 38% / -Rs 3,69,535 | 2631 / 38% / -Rs 3,76,521 | 5406 / 38% / -Rs 7,46,056 | 0.950 | -Rs 1,82,968 | Rs 7,68,270 | time 85%, stop15 14%, squareoff 1%, missing 0% |
| X16 time stop 30 minutes | 2210 / 37% / -Rs 3,62,649 | 2032 / 38% / -Rs 2,70,192 | 4242 / 38% / -Rs 6,32,841 | 0.978 | -Rs 1,75,398 | Rs 6,84,495 | time 72%, stop15 23%, squareoff 4%, missing 0% |

## 6. Cells that passed every gate

None.

## 7. No-fills (signals with no option trade in the 3-minute slack or no stored strike)

| rule | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY | SENSEX |
|---|---|---|---|---|---|
| BOSS-X16 | 6 | 3 | 88 | 3 | 2 |
| STD-X16 | 11 | 4 | 160 | 8 | 9 |
| LOW-X16 | 8 | 3 | 89 | 3 | 6 |
| ITM-X16 | 6 | 3 | 109 | 9 | 2 |

## 8. Caveats

- 1-min only: Boss's 10-second confirmation was not tested (no data). The full rule is stricter.
- The last 6 months (Apr - Oct 2026) were read before by other studies for other rules; this rule never.
- Dhan's rolling option data holds ATM +/- 10 strikes of the nearest expiry; a strike outside it is a no-fill. FINNIFTY and MIDCPNIFTY options are thin (more no-fills, wider spreads in the slippage model).
- Twins share their random entry draws across rule sets (common random numbers) and may overlap.
- The index has no volume; Pine's line is on the index close, as Boss's chart would draw it.
