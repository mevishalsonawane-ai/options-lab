# R154: 10-15 point scalping on the index options

## For Boss (short)

- **Verdict: no version of 10-15 point scalping works.** 0 of 792 versions passed the gates (7 entries x 5 indices x 24 exit rules, Aug 2021 - Oct 2026).
- **Win rate vs the win rate needed (1 lot, all versions, Aug 2021 - Oct 2026):** points on the option's price: win 49%, needed 58%; points on the index: win 43%, needed 51%. After charges and slippage a 10-15 point loss is bigger than a 10-15 point win, so even a 50% win rate loses.
- **Profit / loss a trade (1 lot):** option points -Rs 101 a trade (final period -Rs 119); index points -Rs 72 (final -Rs 79). Charges alone are about Rs 71 a trade.
- **At 3 lots** the loss per lot is smaller (brokerage is per order) but still a loss: option points -Rs 70 a lot a trade, index points -Rs 41.
- **One example** (opening-range break on NIFTY, 10 option points, stop 10, 15 minutes): 331 trades, win 49% (needed 55%), -Rs 27,381 in Apr 2025 - Mar 2026; 177 trades, win 41%, -Rs 28,181 in Apr - Oct 2026. The same with 10 index points: 331 trades, win 48% (needed 57%), -Rs 30,937 in Apr 2025 - Mar 2026; 177 trades, win 45%, -Rs 14,312 in Apr - Oct 2026.
- **Entries vs coin flips:** the four scalping entries lose a little less than random entries with the same exits (-Rs 105 vs -Rs 118 a trade on option points, -Rs 76 vs -Rs 90 on index points), but they still lose.
- **Closest call:** LIQ on MIDCPNIFTY with A15s22.5t30 made -Rs 8,936 / Rs 29,620 / -Rs 318 in the three periods. 13 of 792 versions made money in the final period, 0 in all three periods; none beat its random-exit twins after the multiple-testing correction (smallest twin p 0.008, smallest q 1.0).
- **The arms:** replacing the R72, R51 and Liquidity exits with 10-15 point scalps made less than their own exits on every index (0 of 312). Small targets cut the few big winners these arms live on, as R106 and R152 found.
- **Same answer as before:** SCALP17 (+15-20 option points) and R45 also found scalps lose after costs. Scalping is not different here.
- Nothing changes in the app. Every version is logged in `research/experiments/entries/r154-*.yaml`.

Note on totals: the tables below add up every exit rule of a reading, which are alternative versions of the same trades. Read them for win rates and per-trade figures; a rupee total over several rules is not one account's P&L.

Signal source: the index for every entry (1- and 5-minute index bars; the arms' own index signals); the 1-ITM nearest-expiry option is bought. Reading A puts the target / stop on the option premium, reading B on the index.

## How it was tested

Pre-registered in `PREREG.md` (committed before any number). 7 entries (R72, R51 + Supertrend, Liquidity 15+5, and four plain scalping entries: opening-range break, VWAP stand-in cross, 9/21 EMA cross, previous-bar break with trend) x the 5 indices (Liquidity: 3) x 24 exits (10 / 12 / 15 points, stop 1:1 or 1:1.5, time stop 15 or 30 minutes, 15:15 square-off, 15:05 on expiry days) = 792 tests at 1 lot; 2 and 3 lots shown. Every trade is priced on the one contract bought with R51's clean stores, R17 fills, dated Zerodha charges and dated lot sizes. If a stop and a target fall in the same minute, the stop counts. Periods: P1 design (Aug 2021 - Mar 2025; the arms from 20 Nov 2024), P2 walk-forward (Apr 2025 - Mar 2026), P3 final (1 Apr - 13 Oct 2026, read once: **already read by R51-R153 and by SCALP17, so weak evidence**), P4 post (14-19 Oct). Gates: net > 0 in P1, P2 and P3; beats 500 random-exit twins with BH q <= 0.10 (m = 1,682: 890 earlier tests + 792); >= 40 final trades; the arms also above their own exits in P2 and P3.

Environment, stated: this machine has Python 3.13; R154 ran on numpy 2.5 / pandas 3.0. All three arms reproduce their published B0 exactly (design and final), and the tests and the brain checks pass under the pinned versions (numpy 2.1.3, pandas 2.2.3, pyarrow 18.1 for 3.13). The committed results JSON are slimmed (full ones stay in $R154_OUT).

## Charges per trade (dated Zerodha charges, R17 slippage not included)

| index | lots | trades | avg charges a trade | premium points eaten | qty (avg) |
|---|---|---|---|---|---|
| NIFTY | 1 | 17,871 | Rs 62 | 1.2 | 56 |
| NIFTY | 2 | 17,871 | Rs 77 | 0.7 | 113 |
| NIFTY | 3 | 17,871 | Rs 92 | 0.6 | 169 |
| BANKNIFTY | 1 | 19,053 | Rs 74 | 3.0 | 26 |
| BANKNIFTY | 2 | 19,053 | Rs 101 | 2.0 | 51 |
| BANKNIFTY | 3 | 19,053 | Rs 128 | 1.6 | 77 |
| FINNIFTY | 1 | 15,082 | Rs 74 | 1.5 | 52 |
| FINNIFTY | 2 | 15,082 | Rs 101 | 1.0 | 103 |
| FINNIFTY | 3 | 15,082 | Rs 128 | 0.8 | 155 |
| MIDCPNIFTY | 1 | 12,137 | Rs 87 | 0.8 | 109 |
| MIDCPNIFTY | 2 | 12,136 | Rs 126 | 0.6 | 218 |
| MIDCPNIFTY | 3 | 12,136 | Rs 166 | 0.5 | 327 |
| SENSEX | 1 | 12,748 | Rs 61 | 4.1 | 16 |
| SENSEX | 2 | 12,748 | Rs 74 | 2.4 | 32 |
| SENSEX | 3 | 12,748 | Rs 87 | 1.9 | 49 |

Premium points eaten = charges / quantity: the points a trade must make just to pay its charges. Slippage (R17) comes on top in the fills.

## Every entry pooled (all its indices and 12 exit rules of a reading), 1 lot

| entry | reading | P1 design (to Mar 2025) | P2 walk-forward (Apr 2025 - Mar 2026) | P3 final (Apr - 13 Oct 2026, read once) | P1-P3 win / break-even |
|---|---|---|---|---|---|
| E1 OR15: first-15-minute range break (1-min close) | A | 52,727 tr, 50% win, -Rs 36,96,569 | 19,125 tr, 52% win, -Rs 17,36,412 | 10,176 tr, 50% win, -Rs 12,15,870 | 51% / 58% |
| E1 OR15: first-15-minute range break (1-min close) | B | 52,739 tr, 43% win, -Rs 32,69,275 | 19,140 tr, 47% win, -Rs 13,39,195 | 10,176 tr, 44% win, -Rs 5,37,045 | 44% / 51% |
| E2 TWAPX: 5-min close crosses the session average price (VWAP stand-in; the index has no volume) | A | 137,224 tr, 46% win, -Rs 1,54,20,940 | 51,404 tr, 50% win, -Rs 56,46,723 | 27,872 tr, 49% win, -Rs 34,81,838 | 47% / 59% |
| E2 TWAPX: 5-min close crosses the session average price (VWAP stand-in; the index has no volume) | B | 142,784 tr, 41% win, -Rs 1,09,78,004 | 53,980 tr, 44% win, -Rs 45,12,584 | 29,409 tr, 41% win, -Rs 25,75,460 | 42% / 52% |
| E3 EMA921: 9 / 21 EMA cross on 5-min closes | A | 98,014 tr, 47% win, -Rs 94,53,833 | 36,035 tr, 51% win, -Rs 31,55,551 | 18,269 tr, 51% win, -Rs 19,01,923 | 48% / 57% |
| E3 EMA921: 9 / 21 EMA cross on 5-min closes | B | 99,551 tr, 41% win, -Rs 74,73,564 | 36,725 tr, 44% win, -Rs 20,85,638 | 18,644 tr, 43% win, -Rs 13,81,365 | 42% / 51% |
| E4 PBB: 1-min close beyond the previous 1-min high / low, with the 5-min 21 EMA trend | A | 197,892 tr, 47% win, -Rs 2,13,80,772 | 74,011 tr, 52% win, -Rs 77,89,328 | 39,833 tr, 51% win, -Rs 48,68,825 | 49% / 59% |
| E4 PBB: 1-min close beyond the previous 1-min high / low, with the 5-min 21 EMA trend | B | 198,137 tr, 42% win, -Rs 1,57,78,482 | 74,031 tr, 46% win, -Rs 58,94,044 | 39,840 tr, 44% win, -Rs 33,03,711 | 43% / 52% |
| R72 Breadth Donchian (4 of 5) entries | A | 11,101 tr, 49% win, -Rs 13,76,901 | 29,548 tr, 54% win, -Rs 16,00,170 | 15,372 tr, 50% win, -Rs 19,41,995 | 52% / 58% |
| R72 Breadth Donchian (4 of 5) entries | B | 11,148 tr, 43% win, -Rs 9,12,738 | 29,908 tr, 48% win, -Rs 6,88,169 | 15,516 tr, 43% win, -Rs 12,35,034 | 46% / 50% |
| R51 Donchian 20 + Supertrend entries | A | 15,039 tr, 52% win, -Rs 12,08,774 | 38,956 tr, 53% win, -Rs 30,67,520 | 21,213 tr, 52% win, -Rs 22,48,761 | 53% / 58% |
| R51 Donchian 20 + Supertrend entries | B | 15,134 tr, 46% win, -Rs 7,03,392 | 39,282 tr, 48% win, -Rs 18,80,168 | 21,376 tr, 44% win, -Rs 14,80,238 | 46% / 51% |
| Liquidity 15+5 entries | A | 2,712 tr, 54% win, -Rs 2,26,152 | 7,920 tr, 56% win, -Rs 2,23,954 | 4,278 tr, 50% win, -Rs 5,85,404 | 54% / 58% |
| Liquidity 15+5 entries | B | 2,712 tr, 45% win, -Rs 2,70,675 | 7,928 tr, 52% win, Rs 3,06,311 | 4,292 tr, 42% win, -Rs 4,62,969 | 48% / 50% |

## Every exit rule pooled (all entries and indices), 1 lot

| exit rule | P1-P3 trades | win | break-even win | net | a trade | avg charges | final trades | final win | final net | exits target / stop / time / sq-off (P1-P3) |
|---|---|---|---|---|---|---|---|---|---|---|
| A10s10t15 | 76,891 | 45% | 56% | -Rs 78,75,363 | -Rs 102 | Rs 71 | 11,563 | 46% | -Rs 14,51,811 | 31833 / 35653 / 9235 / 170 |
| A10s10t30 | 76,295 | 46% | 57% | -Rs 80,54,005 | -Rs 106 | Rs 71 | 11,465 | 47% | -Rs 14,81,140 | 34048 / 38217 / 3857 / 173 |
| A10s15t15 | 76,356 | 52% | 62% | -Rs 77,10,796 | -Rs 101 | Rs 71 | 11,500 | 53% | -Rs 14,20,537 | 36509 / 25533 / 14105 / 209 |
| A10s15t30 | 75,377 | 54% | 64% | -Rs 81,66,835 | -Rs 108 | Rs 71 | 11,361 | 55% | -Rs 15,08,683 | 39466 / 28865 / 6817 / 229 |
| A12s12t15 | 76,532 | 45% | 55% | -Rs 74,99,765 | -Rs 98 | Rs 71 | 11,525 | 47% | -Rs 13,47,213 | 30313 / 32606 / 13411 / 202 |
| A12s12t30 | 75,710 | 47% | 56% | -Rs 77,54,210 | -Rs 102 | Rs 71 | 11,397 | 48% | -Rs 14,02,900 | 33209 / 36137 / 6147 / 217 |
| A12s18t15 | 76,031 | 52% | 60% | -Rs 73,95,673 | -Rs 97 | Rs 71 | 11,468 | 53% | -Rs 12,45,816 | 34214 / 22572 / 18997 / 248 |
| A12s18t30 | 74,769 | 54% | 62% | -Rs 77,67,234 | -Rs 104 | Rs 71 | 11,300 | 56% | -Rs 13,25,704 | 37965 / 26385 / 10133 / 286 |
| A15s15t15 | 76,186 | 45% | 53% | -Rs 73,26,616 | -Rs 96 | Rs 71 | 11,485 | 46% | -Rs 12,96,054 | 27607 / 29071 / 19260 / 248 |
| A15s15t30 | 75,006 | 46% | 54% | -Rs 77,34,939 | -Rs 103 | Rs 71 | 11,314 | 48% | -Rs 13,83,378 | 31120 / 33395 / 10204 / 287 |
| A15s22.5t15 | 75,635 | 50% | 58% | -Rs 72,18,070 | -Rs 95 | Rs 71 | 11,430 | 52% | -Rs 11,39,526 | 30663 / 18989 / 25682 / 301 |
| A15s22.5t30 | 73,933 | 53% | 60% | -Rs 77,24,708 | -Rs 104 | Rs 71 | 11,205 | 55% | -Rs 12,41,855 | 35035 / 23184 / 15346 / 368 |
| B10s10t15 | 77,583 | 40% | 50% | -Rs 56,28,425 | -Rs 73 | Rs 71 | 11,694 | 40% | -Rs 9,73,885 | 36071 / 39330 / 2092 / 90 |
| B10s10t30 | 77,449 | 40% | 50% | -Rs 55,87,760 | -Rs 72 | Rs 71 | 11,678 | 40% | -Rs 9,55,200 | 36791 / 40046 / 524 / 88 |
| B10s15t15 | 77,191 | 43% | 53% | -Rs 55,58,951 | -Rs 72 | Rs 71 | 11,641 | 43% | -Rs 9,38,631 | 42183 / 30617 / 4271 / 120 |
| B10s15t30 | 76,860 | 44% | 53% | -Rs 54,86,265 | -Rs 71 | Rs 71 | 11,605 | 44% | -Rs 9,14,792 | 43509 / 31884 / 1343 / 124 |
| B12s12t15 | 77,350 | 41% | 50% | -Rs 55,36,230 | -Rs 72 | Rs 71 | 11,657 | 41% | -Rs 9,21,710 | 35880 / 37475 / 3887 / 108 |
| B12s12t30 | 77,081 | 42% | 50% | -Rs 55,09,536 | -Rs 71 | Rs 71 | 11,626 | 41% | -Rs 9,15,102 | 37071 / 38743 / 1158 / 109 |
| B12s18t15 | 76,888 | 44% | 53% | -Rs 55,23,345 | -Rs 72 | Rs 71 | 11,607 | 44% | -Rs 9,71,191 | 41431 / 28501 / 6821 / 135 |
| B12s18t30 | 76,363 | 46% | 54% | -Rs 54,44,769 | -Rs 71 | Rs 71 | 11,549 | 45% | -Rs 9,30,969 | 43329 / 30363 / 2530 / 141 |
| B15s15t15 | 77,017 | 42% | 50% | -Rs 55,55,789 | -Rs 72 | Rs 71 | 11,619 | 43% | -Rs 8,72,752 | 34711 / 35025 / 7138 / 143 |
| B15s15t30 | 76,534 | 43% | 50% | -Rs 55,56,175 | -Rs 73 | Rs 71 | 11,564 | 43% | -Rs 8,31,774 | 36674 / 37088 / 2619 / 153 |
| B15s22.5t15 | 76,485 | 46% | 53% | -Rs 55,08,443 | -Rs 72 | Rs 71 | 11,555 | 46% | -Rs 8,76,915 | 39541 / 25439 / 11315 / 190 |
| B15s22.5t30 | 75,651 | 47% | 54% | -Rs 55,59,747 | -Rs 73 | Rs 71 | 11,458 | 47% | -Rs 8,72,898 | 42350 / 28162 / 4942 / 197 |

## Lots (all entries, indices and exit rules of a reading)

| reading | lots | P1-P3 trades | win | break-even win | net | a trade | avg charges | final net |
|---|---|---|---|---|---|---|---|---|
| A | 1 | 908,721 | 49% | 58% | -Rs 9,22,28,214 | -Rs 101 | Rs 71 | -Rs 1,62,44,616 |
| A | 2 | 908,720 | 50% | 56% | -Rs 14,20,23,054 | -Rs 156 | Rs 95 | -Rs 2,61,77,056 |
| A | 3 | 908,723 | 50% | 56% | -Rs 19,19,61,906 | -Rs 211 | Rs 119 | -Rs 3,61,73,941 |
| B | 1 | 922,452 | 43% | 51% | -Rs 6,64,55,437 | -Rs 72 | Rs 71 | -Rs 1,09,75,820 |
| B | 2 | 922,452 | 45% | 51% | -Rs 8,97,44,721 | -Rs 97 | Rs 95 | -Rs 1,55,18,970 |
| B | 3 | 922,452 | 46% | 51% | -Rs 11,32,72,153 | -Rs 123 | Rs 119 | -Rs 2,01,25,953 |

## Do the entries beat random entries? (same exits, 1 lot)

| reading | random entries P1-P3 | signal entries (E1-E4) P1-P3 | random final | E1-E4 final |
|---|---|---|---|---|
| A | 280,939 tr, win 48%, -Rs 3,30,41,344 (-Rs 118 a trade) | 762,582 tr, win 49%, -Rs 7,97,48,583 (-Rs 105 a trade) | 35,089 tr, win 48%, -Rs 63,35,571 (-Rs 181 a trade) | 96,150 tr, win 50%, -Rs 1,14,68,456 (-Rs 119 a trade) |
| B | 291,269 tr, win 42%, -Rs 2,63,31,821 (-Rs 90 a trade) | 775,156 tr, win 43%, -Rs 5,91,28,366 (-Rs 76 a trade) | 36,673 tr, win 39%, -Rs 48,37,033 (-Rs 132 a trade) | 98,069 tr, win 43%, -Rs 77,97,580 (-Rs 80 a trade) |

## The arms: scalp exits against today's exits (1 lot)

| arm | index | today's exits (B0) P2 / P3 | best scalp P2 (rule: net) | that rule in P3 | scalps above B0 in P2 and P3 |
|---|---|---|---|---|---|
| R72 | NIFTY | Rs 76,415 / Rs 9,850 | A10s15t15: Rs 9,636 | -Rs 26,831 | 0 of 24 |
| R72 | BANKNIFTY | Rs 92,752 / Rs 55,106 | B15s22.5t15: -Rs 29,195 | -Rs 21,532 | 0 of 24 |
| R72 | FINNIFTY | Rs 1,02,258 / Rs 69,469 | B15s22.5t15: Rs 19,494 | -Rs 4,422 | 0 of 24 |
| R72 | MIDCPNIFTY | Rs 17,204 / Rs 24,247 | A15s22.5t30: Rs 37,359 | -Rs 39,286 | 0 of 24 |
| R72 | SENSEX | Rs 47,252 / Rs 12,694 | B12s12t15: -Rs 10,366 | -Rs 16,386 | 0 of 24 |
| R51 | NIFTY | Rs 26,972 / Rs 1,350 | A12s12t15: Rs 1,373 | -Rs 45,844 | 0 of 24 |
| R51 | BANKNIFTY | Rs 1,02,754 / Rs 41,043 | B15s22.5t15: -Rs 25,009 | -Rs 24,853 | 0 of 24 |
| R51 | FINNIFTY | Rs 1,16,683 / Rs 53,357 | B15s22.5t15: Rs 739 | -Rs 18,153 | 0 of 24 |
| R51 | MIDCPNIFTY | -Rs 44,640 / Rs 4,652 | B10s15t15: -Rs 47,563 | -Rs 56,152 | 0 of 24 |
| R51 | SENSEX | Rs 11,980 / Rs 34,341 | B10s10t15: -Rs 30,597 | -Rs 7,956 | 0 of 24 |
| LIQ | BANKNIFTY | Rs 63,994 / Rs 10,978 | B12s12t15: Rs 3,110 | -Rs 14,637 | 0 of 24 |
| LIQ | FINNIFTY | Rs 85,442 / Rs 4,852 | B12s12t15: Rs 13,975 | -Rs 7,711 | 0 of 24 |
| LIQ | MIDCPNIFTY | Rs 88,628 / -Rs 4,674 | A15s22.5t15: Rs 37,819 | -Rs 20,457 | 0 of 24 |

## Walk-forward pick (descriptive): the best exit on all earlier data, run on the next quarter

| entry | index | P2 picks (quarterly) | P2 net | final pick | P3 net |
|---|---|---|---|---|---|
| E1 | NIFTY | B12s18t15, A15s15t15, B15s15t15, B12s18t15 | -Rs 39,499 | B12s18t15 | -Rs 24,647 |
| E1 | BANKNIFTY | B12s18t15, B10s10t15, B10s10t15, B12s18t15 | -Rs 29,686 | B12s12t15 | -Rs 5,275 |
| E1 | FINNIFTY | A15s22.5t15, A15s15t30, A15s22.5t15, A15s22.5t15 | -Rs 12,658 | A15s22.5t15 | -Rs 1,540 |
| E1 | MIDCPNIFTY | A12s12t30, A12s18t30, A15s15t30, B15s22.5t30 | -Rs 22,459 | B15s22.5t30 | -Rs 9,046 |
| E1 | SENSEX | B15s22.5t15, B15s22.5t15, B15s22.5t15, B15s22.5t15 | -Rs 16,832 | B15s22.5t15 | -Rs 13,996 |
| E2 | NIFTY | B10s15t30, B10s15t30, B10s15t30, B10s15t30 | -Rs 69,024 | B10s15t30 | -Rs 29,767 |
| E2 | BANKNIFTY | B15s22.5t30, B15s22.5t30, B15s22.5t30, B15s22.5t30 | -Rs 78,615 | B15s22.5t30 | -Rs 69,395 |
| E2 | FINNIFTY | B10s10t30, B10s10t30, B10s10t30, B10s10t30 | -Rs 59,284 | B10s10t30 | -Rs 39,424 |
| E2 | MIDCPNIFTY | B12s18t30, B12s18t30, B12s18t30, B12s18t30 | -Rs 70,722 | B12s18t30 | -Rs 69,363 |
| E2 | SENSEX | B10s10t15, B10s10t15, B10s10t15, B15s22.5t15 | -Rs 71,202 | B10s10t15 | -Rs 29,145 |
| E3 | NIFTY | B12s18t15, B12s18t15, B12s18t15, B12s18t15 | -Rs 13,256 | B12s18t15 | -Rs 1,219 |
| E3 | BANKNIFTY | B10s10t15, B10s10t15, B12s18t30, B12s18t30 | -Rs 40,304 | B12s18t30 | -Rs 33,128 |
| E3 | FINNIFTY | B10s10t15, B10s10t15, B10s10t15, B10s10t15 | -Rs 44,090 | B10s10t15 | -Rs 32,045 |
| E3 | MIDCPNIFTY | A15s22.5t15, A15s22.5t30, A15s22.5t30, A15s22.5t30 | -Rs 16,585 | A15s22.5t30 | -Rs 3,876 |
| E3 | SENSEX | B10s10t15, B10s10t15, B10s10t15, B10s10t15 | -Rs 30,320 | B10s10t15 | -Rs 9,968 |
| E4 | NIFTY | B10s15t30, B10s15t30, B10s15t30, B10s15t30 | -Rs 85,790 | B10s15t30 | -Rs 72,957 |
| E4 | BANKNIFTY | B10s15t15, B10s15t15, B10s10t15, B10s15t15 | -Rs 1,01,896 | B10s10t15 | -Rs 67,620 |
| E4 | FINNIFTY | B10s10t30, B10s10t30, B10s10t30, B10s10t30 | -Rs 84,301 | B10s10t30 | -Rs 27,344 |
| E4 | MIDCPNIFTY | B12s18t30, B12s18t30, B12s18t30, B12s18t30 | -Rs 1,05,321 | B12s18t30 | -Rs 77,961 |
| E4 | SENSEX | B10s15t15, B10s15t15, B10s15t15, B12s12t15 | -Rs 86,701 | B15s15t15 | -Rs 48,758 |
| R72 | NIFTY | B10s10t15, B12s12t30, A10s15t15, B12s18t15 | -Rs 8,957 | A10s15t15 | -Rs 26,831 |
| R72 | BANKNIFTY | B15s22.5t15, B10s10t15, B10s10t15, B10s10t15 | -Rs 34,106 | B15s22.5t15 | -Rs 21,532 |
| R72 | FINNIFTY | A15s22.5t30, A15s22.5t30, A15s22.5t15, A15s22.5t15 | -Rs 20,186 | B12s18t15 | -Rs 12,600 |
| R72 | MIDCPNIFTY | A15s22.5t15, A15s22.5t15, A15s22.5t15, A15s22.5t15 | Rs 25,467 | A15s22.5t15 | -Rs 42,200 |
| R72 | SENSEX | B15s22.5t15, B15s22.5t15, B15s22.5t15, B15s22.5t15 | -Rs 12,197 | B12s12t15 | -Rs 16,386 |
| R51 | NIFTY | B12s12t30, B12s12t30, B12s18t15, B12s18t15 | -Rs 10,739 | B12s18t15 | -Rs 15,726 |
| R51 | BANKNIFTY | B12s18t15, B10s15t15, B12s18t15, B15s22.5t15 | -Rs 34,868 | B15s22.5t15 | -Rs 24,853 |
| R51 | FINNIFTY | A15s22.5t30, B15s22.5t15, B15s22.5t15, B15s22.5t15 | -Rs 19,605 | B15s22.5t15 | -Rs 18,153 |
| R51 | MIDCPNIFTY | B15s22.5t30, B12s18t30, B15s22.5t30, A15s22.5t15 | -Rs 83,499 | B15s22.5t15 | -Rs 42,023 |
| R51 | SENSEX | B15s22.5t15, B15s22.5t15, B15s22.5t15, B12s18t15 | -Rs 34,045 | B12s18t15 | -Rs 2,141 |
| LIQ | BANKNIFTY | A15s15t15, B12s18t15, B12s18t15, B12s18t15 | -Rs 4,581 | B12s18t15 | -Rs 15,311 |
| LIQ | FINNIFTY | A15s22.5t15, A15s22.5t15, A15s22.5t30, B12s12t15 | -Rs 12,472 | B12s12t15 | -Rs 7,711 |
| LIQ | MIDCPNIFTY | B10s10t15, B12s12t15, A15s15t30, B12s12t15 | -Rs 3,093 | A15s22.5t15 | -Rs 20,457 |

## Post window (14-19 Oct 2026, 4 sessions; never gated)

| reading | lots | post trades | win | net |
|---|---|---|---|---|
| A | 1 | 4,077 | 49% | -Rs 6,57,032 |
| A | 3 | 4,077 | 49% | -Rs 15,94,900 |
| B | 1 | 4,122 | 45% | -Rs 6,14,629 |
| B | 3 | 4,122 | 45% | -Rs 14,69,046 |

## Earlier findings, honestly

- R106 (26 Oct): Rs 500 / 1,000 / 1,500 targets on the Liquidity, R72, R51 and OOV arms: 0 of 36 passed; small targets cut the arms' few big winners. R152 (7 Nov): +/- Rs 2,000 on R72 / R51 at 1-3 lots: 0 of 18 passed.
- SCALP17: +15-20 premium points, 1,100,700 combos screened, 0 passed BH; every shortlisted rule lost net in 2025 - Oct 2026.
- R154 adds the index-point reading, 10-12 point sizes, the arms' own entries and four common scalping entries, on every index and all the option data (Aug 2021 - Oct 2026). The answer does not change: after charges and slippage a fixed 10-15 point scalp needs a win rate it does not reach.

Full table of all 792 tests: `results/tables.md`. Gate decisions: `results/decision.json`.
