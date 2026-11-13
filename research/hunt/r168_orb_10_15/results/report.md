# R168: the app's own ORB arms with 10-15 point exits

## For Boss (short)

- **Verdict: ORB with 10-15 points does not work. 0 of 360 versions passed the gates** (ORB, ORB Fresh and ORB Sweep x 5 indices x 24 exits, Aug 2021 - Oct 2026).
- **ORB on BANKNIFTY (the app's arm), 10-15 points on the option price, all 12 exit versions together, 1 lot:** 14,604 trades, win 51%, needed 66%, -Rs 105 a trade. Final period alone: win 58% (needed 69%), -Rs 97 a trade.
- **The same with 10-15 points on the index:** 14,604 trades, win 41%, needed 49%, -Rs 53 a trade (final: win 53%, Rs 19 a trade).
- **Against ORB's own exits (-40 / +40 with the lock), same entries, 1 lot:** own exits 1,217 trades, win 38%, -Rs 96 a trade (-Rs 1,17,160 in all). Option-point scalps lose MORE (-Rs 105 a trade); index-point scalps lose LESS (-Rs 53 a trade) but still lose.
- **171 of 360 versions beat their arm's own exits in both P2 and P3** (mostly index-point exits, and on ORB Sweep, whose own +80 exits lose the most). That is a smaller loss, not a profit.
- **One example** (ORB on BANKNIFTY, 10 option points, stop 10, out after 15 minutes): 233 trades, win 48% (needed 65%), -Rs 27,876 in Apr 2025 - Mar 2026; 130 trades, win 51%, -Rs 15,859 in Apr - Oct 2026. With 10 index points: 233 trades, win 39% (needed 49%), -Rs 15,480; then 130 trades, win 52%, Rs 3,305.
- **ORB Sweep on BANKNIFTY with 10-15 points:** option points win 48% (needed 64%), -Rs 98 a trade; index points win 38% (needed 57%), -Rs 64 a trade. ORB Fresh takes almost the same trades as ORB (1 entry a side a day).
- **Why it fails:** a BANKNIFTY ORB trade pays about Rs 74 of charges at 1 lot, plus slippage on both sides, so a 10-point win nets much less than a 10-point loss costs. The win rate needed is above the win rate the entries give, on every index.
- **None of the 360 versions made money in all three periods; 32 made money in the final period alone.** None beat its random-exit twins after the multiple-testing correction (smallest twin p 0.002 for ORB:NIFTY:B12s12t30; m = 3,058).
- **Honest limits:** the final window (Apr - Oct 2026) was already read by R14 / H20 (the ORB arms' holdout) and many other studies, so it is weak evidence; the ORB family is parked in the app since 13 Oct anyway. All three arms use one 50-minute opening range (09:15 - 10:05); the app has no 5-, 15- or 30-minute ORB arm (R154 already tested the 15-minute break, E1, and it lost).
- Nothing changes in the app. Every version is logged in `research/experiments/entries/r168-*.yaml`.

Note on totals: the pooled lines add up every exit rule of a reading, which are alternative versions of the same entries. Read them for win rates and per-trade figures, not as one account's P&L.

Signal source: the index for every arm (5-minute index bars built from 1-minute bars; one 09:15 - 10:00 opening range). The ATM option of that index (strike from the 09:20 bar's close, same-day expiry on an expiry day) is bought. Reading A puts the target / stop on the option premium, reading B on the index.

## How it was tested

Pre-registered in `PREREG.md` before any number (hashes and times in `results/PREREG_HASH`; Amendment 1, before any number, only says the -40 / lock / target levels are measured from the real fill, as h20 / R14 and the app). The arms' entries are the app's `OrbRules` / `SweepRules` as h20 / R14 ported them: one opening range from the 5-minute bars 09:15 - 10:00; ORB and ORB Fresh decide on the 10:05 - 13:55 bars (Fresh only on a fresh break), at most 1 entry a side a day (R14's cap, the paper default since 13 Oct); ORB Sweep decides on 10:05 - 14:25 (a wick beyond an edge that closes back inside, reversed), at most 2 entries a day; one position at a time and the next decision only on a bar starting after the exit's bar; a real fill of Rs 40 or less refused. Only the exits change: R154's 24 (10 / 12 / 15 points on the premium (A) or the index (B), stop 1:1 or 1:1.5, out after 15 or 30 minutes, 15:15 square-off, 15:05 on expiry days). Each arm's own exits (B0: -40 stop, the profit lock ladder, +40 target, Sweep +80; 15:10 square-off) are the baseline.

Priced on the one contract bought with R154's stores (R51's clean stores from 20 Nov 2024, the early store before), R17 fills (buy +5 bps + half-spread ticks; stop sales -10 bps and twice the ticks), dated Zerodha charges and dated lot sizes. Periods: P1 design (Aug 2021 - Mar 2025; MIDCPNIFTY from Jan 2023, SENSEX from May 2023), P2 walk-forward (Apr 2025 - Mar 2026), P3 final (1 Apr - 13 Oct 2026, read once). Gates (1 lot): net > 0 in P1, P2 and P3; beats 500 random-exit twins with BH q <= 0.10 (m = 3,058: 2,698 earlier tests at p = 1 + R168's 360); >= 40 final trades; above the arm's own exits in P2 and P3. P4 (14 - 19 Oct, 4 sessions) was not run, as pre-registered.

Environment, stated: Python 3.13 with the pinned numpy 2.1.3 / pandas 2.2.3 / scipy 1.14.1 / scikit-learn 1.5.2 and pyarrow 18.1 (17.0 has no 3.13 wheel); numba 0.68. The committed results JSON are slimmed (the priced trades stay in $R168_OUT). Exact figures for ORB and ORB Fresh on BANKNIFTY coincide because, with 1 entry a side a day, the first break on a side is always a fresh one.

## Parity with the published figures (B0 = the arm's own exits, BANKNIFTY, 1 lot)

| arm | window | published (R14 / H20) | R168, parity mode (app charges at today's rates) | trades within 5%? | net within 15%? | R168, own mode (dated charges) |
|---|---|---|---|---|---|---|
| ORB | 4 Aug 2021 - 30 Sep 2025 | 962 tr, -Rs 96,740 | 966 tr, -Rs 99,248 | yes | yes | 966 tr, -Rs 98,029 |
| ORB | 1 Oct 2025 - 5 Oct 2026 | 246 tr, -Rs 18,439 | 246 tr, -Rs 18,439 | yes | yes | 246 tr, -Rs 19,750 |
| ORB Fresh | 4 Aug 2021 - 30 Sep 2025 | 962 tr, -Rs 96,740 | 966 tr, -Rs 99,248 | yes | yes | 966 tr, -Rs 98,029 |
| ORB Fresh | 1 Oct 2025 - 5 Oct 2026 | 246 tr, -Rs 18,439 | 246 tr, -Rs 18,439 | yes | yes | 246 tr, -Rs 19,750 |
| ORB Sweep | 4 Aug 2021 - 30 Sep 2025 | 1,330 tr, -Rs 1,94,184 | 1,336 tr, -Rs 2,06,564 | yes | yes | 1,336 tr, -Rs 2,05,046 |
| ORB Sweep | 1 Oct 2025 - 5 Oct 2026 | 331 tr, -Rs 78,962 | 330 tr, -Rs 79,258 | yes | yes | 330 tr, -Rs 80,474 |

Notes: the ORB / ORB Fresh holdout matches R14 exactly (246 trades, -Rs 18,439). The pre window has 4 more ORB trades and 6 more Sweep trades than published because the lake has 1,028 BANKNIFTY sessions there against h20's 996 (days obuy's Dhan pull lacked); the nets differ by 2.6% (ORB) and 6.4% (Sweep), inside the pre-registered 15%. With 1 entry a side a day ORB and ORB Fresh take the same BANKNIFTY trades (R14 found the same); on other indices they differ slightly.

## Charges per trade and the break-even win rate

Dated Zerodha charges of a round trip, averaged over every R168 trade on the index (all arms and exits, P1-P3); premium points eaten = charges / quantity. R17 slippage comes on top. Break-even win = the win rate the pooled scalp trades needed (their own average win and loss).

| index | lots | trades | avg charges a trade | premium points eaten | win (A) / needed | win (B) / needed |
|---|---|---|---|---|---|---|
| NIFTY | 1 | 97,785 | Rs 60 | 1.2 | 45% / 54% | 47% / 57% |
| NIFTY | 2 | 97,785 | Rs 73 | 0.7 | 46% / 53% | 49% / 56% |
| NIFTY | 3 | 97,785 | Rs 87 | 0.6 | 47% / 53% | 49% / 55% |
| BANKNIFTY | 1 | 102,767 | Rs 71 | 3.0 | 50% / 65% | 40% / 51% |
| BANKNIFTY | 2 | 102,767 | Rs 94 | 1.9 | 50% / 62% | 43% / 49% |
| BANKNIFTY | 3 | 102,767 | Rs 117 | 1.6 | 50% / 61% | 44% / 49% |
| FINNIFTY | 1 | 81,761 | Rs 70 | 1.5 | 47% / 59% | 42% / 50% |
| FINNIFTY | 2 | 81,761 | Rs 94 | 0.9 | 48% / 58% | 43% / 48% |
| FINNIFTY | 3 | 81,761 | Rs 117 | 0.8 | 48% / 57% | 44% / 48% |
| MIDCPNIFTY | 1 | 56,750 | Rs 86 | 0.8 | 45% / 52% | 47% / 53% |
| MIDCPNIFTY | 2 | 56,750 | Rs 124 | 0.6 | 45% / 52% | 47% / 52% |
| MIDCPNIFTY | 3 | 56,750 | Rs 163 | 0.5 | 46% / 52% | 48% / 52% |
| SENSEX | 1 | 67,813 | Rs 59 | 4.2 | 48% / 68% | 33% / 45% |
| SENSEX | 2 | 67,813 | Rs 72 | 2.5 | 48% / 63% | 39% / 45% |
| SENSEX | 3 | 67,813 | Rs 84 | 1.9 | 48% / 61% | 41% / 46% |

## Each arm x index: own exits vs the scalps (1 lot; the scalps pooled over the 12 rules of a reading)

Trades, net, worst day, max DD and the net without the 5 best add up the 12 rules (alternative versions of the same entries); per session is one rule's average.

| arm | index | exits | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ORB | NIFTY | own (B0) | P1 | 918 | 31% | 45% | -Rs 2,07,760 | -Rs 229 | -Rs 6,139 | -Rs 2,21,975 | -Rs 2,24,711 |
| ORB | NIFTY | own (B0) | P2 | 232 | 35% | 38% | -Rs 20,133 | -Rs 82 | -Rs 4,977 | -Rs 46,326 | -Rs 36,792 |
| ORB | NIFTY | own (B0) | P3 | 129 | 29% | 45% | -Rs 44,059 | -Rs 331 | -Rs 5,379 | -Rs 54,974 | -Rs 58,781 |
| ORB | NIFTY | A: premium pts | P1 | 11112 | 47% | 56% | -Rs 10,53,806 | -Rs 97 | -Rs 30,495 | -Rs 10,77,911 | -Rs 10,59,134 |
| ORB | NIFTY | A: premium pts | P2 | 2784 | 53% | 56% | -Rs 1,79,571 | -Rs 61 | -Rs 59,727 | -Rs 2,84,955 | -Rs 1,84,928 |
| ORB | NIFTY | A: premium pts | P3 | 1548 | 47% | 57% | -Rs 2,24,696 | -Rs 141 | -Rs 26,608 | -Rs 2,57,702 | -Rs 2,29,285 |
| ORB | NIFTY | B: index pts | P1 | 11112 | 47% | 58% | -Rs 10,02,250 | -Rs 92 | -Rs 66,699 | -Rs 10,13,165 | -Rs 10,15,457 |
| ORB | NIFTY | B: index pts | P2 | 2784 | 53% | 56% | -Rs 1,25,977 | -Rs 43 | -Rs 59,603 | -Rs 2,69,399 | -Rs 1,39,613 |
| ORB | NIFTY | B: index pts | P3 | 1548 | 50% | 58% | -Rs 1,43,453 | -Rs 90 | -Rs 25,734 | -Rs 1,78,605 | -Rs 1,54,261 |
| ORB | BANKNIFTY | own (B0) | P1 | 854 | 37% | 50% | -Rs 94,603 | -Rs 105 | -Rs 2,652 | -Rs 97,208 | -Rs 1,03,856 |
| ORB | BANKNIFTY | own (B0) | P2 | 233 | 36% | 46% | -Rs 24,348 | -Rs 99 | -Rs 1,919 | -Rs 33,272 | -Rs 33,641 |
| ORB | BANKNIFTY | own (B0) | P3 | 130 | 43% | 42% | Rs 1,791 | Rs 13 | -Rs 2,178 | -Rs 6,544 | -Rs 7,042 |
| ORB | BANKNIFTY | A: premium pts | P1 | 10248 | 49% | 66% | -Rs 10,46,025 | -Rs 97 | -Rs 14,551 | -Rs 10,68,426 | -Rs 10,47,977 |
| ORB | BANKNIFTY | A: premium pts | P2 | 2796 | 53% | 66% | -Rs 3,33,649 | -Rs 113 | -Rs 12,790 | -Rs 3,33,649 | -Rs 3,35,981 |
| ORB | BANKNIFTY | A: premium pts | P3 | 1560 | 58% | 69% | -Rs 1,50,775 | -Rs 94 | -Rs 13,147 | -Rs 1,50,775 | -Rs 1,52,727 |
| ORB | BANKNIFTY | B: index pts | P1 | 10248 | 39% | 48% | -Rs 6,00,250 | -Rs 55 | -Rs 34,212 | -Rs 6,58,657 | -Rs 6,10,783 |
| ORB | BANKNIFTY | B: index pts | P2 | 2796 | 40% | 50% | -Rs 1,99,888 | -Rs 67 | -Rs 16,548 | -Rs 2,22,041 | -Rs 2,11,700 |
| ORB | BANKNIFTY | B: index pts | P3 | 1560 | 53% | 51% | Rs 29,532 | Rs 19 | -Rs 19,131 | -Rs 40,513 | Rs 23,102 |
| ORB | FINNIFTY | own (B0) | P1 | 675 | 40% | 43% | -Rs 38,651 | -Rs 43 | -Rs 20,326 | -Rs 81,316 | -Rs 1,00,385 |
| ORB | FINNIFTY | own (B0) | P2 | 225 | 42% | 41% | Rs 5,741 | Rs 23 | -Rs 5,456 | -Rs 31,378 | -Rs 19,858 |
| ORB | FINNIFTY | own (B0) | P3 | 121 | 38% | 38% | -Rs 811 | -Rs 6 | -Rs 5,639 | -Rs 45,282 | -Rs 39,058 |
| ORB | FINNIFTY | A: premium pts | P1 | 8124 | 48% | 62% | -Rs 13,11,886 | -Rs 122 | -Rs 2,63,187 | -Rs 13,21,813 | -Rs 13,16,480 |
| ORB | FINNIFTY | A: premium pts | P2 | 2700 | 55% | 58% | -Rs 1,35,057 | -Rs 46 | -Rs 36,980 | -Rs 2,12,591 | -Rs 1,39,659 |
| ORB | FINNIFTY | A: premium pts | P3 | 1452 | 46% | 58% | -Rs 2,35,269 | -Rs 147 | -Rs 57,122 | -Rs 2,40,505 | -Rs 2,39,457 |
| ORB | FINNIFTY | B: index pts | P1 | 8124 | 43% | 51% | -Rs 4,88,426 | -Rs 46 | -Rs 62,487 | -Rs 5,64,456 | -Rs 5,12,062 |
| ORB | FINNIFTY | B: index pts | P2 | 2700 | 51% | 45% | Rs 1,63,125 | Rs 55 | -Rs 26,027 | -Rs 51,988 | Rs 1,51,359 |
| ORB | FINNIFTY | B: index pts | P3 | 1452 | 30% | 39% | -Rs 1,02,787 | -Rs 64 | -Rs 19,771 | -Rs 1,02,787 | -Rs 1,15,133 |
| ORB | MIDCPNIFTY | own (B0) | P1 | 365 | 41% | 45% | -Rs 39,561 | -Rs 80 | -Rs 10,267 | -Rs 62,316 | -Rs 65,439 |
| ORB | MIDCPNIFTY | own (B0) | P2 | 226 | 34% | 41% | -Rs 68,869 | -Rs 279 | -Rs 11,373 | -Rs 90,108 | -Rs 1,00,657 |
| ORB | MIDCPNIFTY | own (B0) | P3 | 135 | 32% | 52% | -Rs 1,11,122 | -Rs 836 | -Rs 12,639 | -Rs 1,14,317 | -Rs 1,36,430 |
| ORB | MIDCPNIFTY | A: premium pts | P1 | 4452 | 48% | 54% | -Rs 4,96,544 | -Rs 84 | -Rs 49,142 | -Rs 5,42,004 | -Rs 5,05,197 |
| ORB | MIDCPNIFTY | A: premium pts | P2 | 2736 | 47% | 52% | -Rs 3,92,596 | -Rs 132 | -Rs 52,346 | -Rs 4,89,871 | -Rs 4,02,767 |
| ORB | MIDCPNIFTY | A: premium pts | P3 | 1644 | 48% | 54% | -Rs 2,24,756 | -Rs 141 | -Rs 43,393 | -Rs 3,06,375 | -Rs 2,33,451 |
| ORB | MIDCPNIFTY | B: index pts | P1 | 4452 | 48% | 53% | -Rs 3,66,775 | -Rs 62 | -Rs 52,756 | -Rs 4,28,574 | -Rs 3,89,396 |
| ORB | MIDCPNIFTY | B: index pts | P2 | 2736 | 50% | 55% | -Rs 2,75,368 | -Rs 93 | -Rs 44,188 | -Rs 4,14,714 | -Rs 2,95,393 |
| ORB | MIDCPNIFTY | B: index pts | P3 | 1644 | 46% | 49% | -Rs 63,963 | -Rs 40 | -Rs 29,147 | -Rs 1,51,917 | -Rs 81,031 |
| ORB | SENSEX | own (B0) | P1 | 447 | 35% | 44% | -Rs 21,910 | -Rs 47 | -Rs 1,765 | -Rs 26,516 | -Rs 32,537 |
| ORB | SENSEX | own (B0) | P2 | 234 | 42% | 47% | -Rs 11,300 | -Rs 46 | -Rs 1,756 | -Rs 13,274 | -Rs 20,227 |
| ORB | SENSEX | own (B0) | P3 | 127 | 37% | 42% | -Rs 5,123 | -Rs 39 | -Rs 1,759 | -Rs 8,119 | -Rs 11,400 |
| ORB | SENSEX | A: premium pts | P1 | 5364 | 47% | 72% | -Rs 4,60,933 | -Rs 82 | -Rs 9,411 | -Rs 4,60,933 | -Rs 4,62,159 |
| ORB | SENSEX | A: premium pts | P2 | 2808 | 47% | 67% | -Rs 3,12,508 | -Rs 105 | -Rs 9,761 | -Rs 3,12,508 | -Rs 3,13,752 |
| ORB | SENSEX | A: premium pts | P3 | 1524 | 52% | 68% | -Rs 1,33,179 | -Rs 83 | -Rs 9,347 | -Rs 1,37,299 | -Rs 1,34,405 |
| ORB | SENSEX | B: index pts | P1 | 5364 | 34% | 43% | -Rs 2,09,706 | -Rs 37 | -Rs 32,635 | -Rs 2,46,876 | -Rs 2,18,640 |
| ORB | SENSEX | B: index pts | P2 | 2808 | 38% | 42% | -Rs 78,346 | -Rs 26 | -Rs 32,870 | -Rs 86,557 | -Rs 91,270 |
| ORB | SENSEX | B: index pts | P3 | 1524 | 45% | 55% | -Rs 87,130 | -Rs 55 | -Rs 12,014 | -Rs 89,962 | -Rs 93,322 |
| ORB Fresh | NIFTY | own (B0) | P1 | 905 | 31% | 45% | -Rs 2,06,581 | -Rs 228 | -Rs 6,139 | -Rs 2,21,564 | -Rs 2,23,532 |
| ORB Fresh | NIFTY | own (B0) | P2 | 231 | 35% | 39% | -Rs 19,683 | -Rs 80 | -Rs 4,977 | -Rs 46,326 | -Rs 36,343 |
| ORB Fresh | NIFTY | own (B0) | P3 | 129 | 29% | 45% | -Rs 44,059 | -Rs 331 | -Rs 5,379 | -Rs 54,974 | -Rs 58,781 |
| ORB Fresh | NIFTY | A: premium pts | P1 | 11040 | 47% | 56% | -Rs 10,37,454 | -Rs 95 | -Rs 30,495 | -Rs 10,63,398 | -Rs 10,42,782 |
| ORB Fresh | NIFTY | A: premium pts | P2 | 2772 | 52% | 56% | -Rs 1,90,022 | -Rs 64 | -Rs 59,727 | -Rs 2,84,955 | -Rs 1,95,378 |
| ORB Fresh | NIFTY | A: premium pts | P3 | 1548 | 47% | 57% | -Rs 2,24,696 | -Rs 141 | -Rs 26,608 | -Rs 2,57,702 | -Rs 2,29,285 |
| ORB Fresh | NIFTY | B: index pts | P1 | 11040 | 47% | 58% | -Rs 9,86,012 | -Rs 91 | -Rs 66,699 | -Rs 9,96,927 | -Rs 9,99,218 |
| ORB Fresh | NIFTY | B: index pts | P2 | 2772 | 53% | 56% | -Rs 1,29,301 | -Rs 44 | -Rs 59,603 | -Rs 2,69,399 | -Rs 1,42,938 |
| ORB Fresh | NIFTY | B: index pts | P3 | 1548 | 50% | 58% | -Rs 1,43,453 | -Rs 90 | -Rs 25,734 | -Rs 1,78,605 | -Rs 1,54,261 |
| ORB Fresh | BANKNIFTY | own (B0) | P1 | 854 | 37% | 50% | -Rs 94,603 | -Rs 105 | -Rs 2,652 | -Rs 97,208 | -Rs 1,03,856 |
| ORB Fresh | BANKNIFTY | own (B0) | P2 | 233 | 36% | 46% | -Rs 24,348 | -Rs 99 | -Rs 1,919 | -Rs 33,272 | -Rs 33,641 |
| ORB Fresh | BANKNIFTY | own (B0) | P3 | 130 | 43% | 42% | Rs 1,791 | Rs 13 | -Rs 2,178 | -Rs 6,544 | -Rs 7,042 |
| ORB Fresh | BANKNIFTY | A: premium pts | P1 | 10248 | 49% | 66% | -Rs 10,46,025 | -Rs 97 | -Rs 14,551 | -Rs 10,68,426 | -Rs 10,47,977 |
| ORB Fresh | BANKNIFTY | A: premium pts | P2 | 2796 | 53% | 66% | -Rs 3,33,649 | -Rs 113 | -Rs 12,790 | -Rs 3,33,649 | -Rs 3,35,981 |
| ORB Fresh | BANKNIFTY | A: premium pts | P3 | 1560 | 58% | 69% | -Rs 1,50,775 | -Rs 94 | -Rs 13,147 | -Rs 1,50,775 | -Rs 1,52,727 |
| ORB Fresh | BANKNIFTY | B: index pts | P1 | 10248 | 39% | 48% | -Rs 6,00,250 | -Rs 55 | -Rs 34,212 | -Rs 6,58,657 | -Rs 6,10,783 |
| ORB Fresh | BANKNIFTY | B: index pts | P2 | 2796 | 40% | 50% | -Rs 1,99,888 | -Rs 67 | -Rs 16,548 | -Rs 2,22,041 | -Rs 2,11,700 |
| ORB Fresh | BANKNIFTY | B: index pts | P3 | 1560 | 53% | 51% | Rs 29,532 | Rs 19 | -Rs 19,131 | -Rs 40,513 | Rs 23,102 |
| ORB Fresh | FINNIFTY | own (B0) | P1 | 656 | 39% | 45% | -Rs 68,273 | -Rs 76 | -Rs 20,326 | -Rs 93,889 | -Rs 1,21,024 |
| ORB Fresh | FINNIFTY | own (B0) | P2 | 224 | 42% | 41% | Rs 5,765 | Rs 23 | -Rs 5,456 | -Rs 31,378 | -Rs 19,833 |
| ORB Fresh | FINNIFTY | own (B0) | P3 | 121 | 38% | 38% | -Rs 811 | -Rs 6 | -Rs 5,639 | -Rs 45,282 | -Rs 39,058 |
| ORB Fresh | FINNIFTY | A: premium pts | P1 | 7932 | 48% | 62% | -Rs 12,25,979 | -Rs 114 | -Rs 2,63,187 | -Rs 12,36,854 | -Rs 12,30,572 |
| ORB Fresh | FINNIFTY | A: premium pts | P2 | 2700 | 55% | 58% | -Rs 1,35,057 | -Rs 46 | -Rs 36,980 | -Rs 2,12,591 | -Rs 1,39,659 |
| ORB Fresh | FINNIFTY | A: premium pts | P3 | 1452 | 46% | 58% | -Rs 2,35,269 | -Rs 147 | -Rs 57,122 | -Rs 2,40,505 | -Rs 2,39,457 |
| ORB Fresh | FINNIFTY | B: index pts | P1 | 7932 | 43% | 53% | -Rs 6,11,399 | -Rs 57 | -Rs 92,437 | -Rs 6,47,991 | -Rs 6,30,569 |
| ORB Fresh | FINNIFTY | B: index pts | P2 | 2700 | 51% | 45% | Rs 1,63,125 | Rs 55 | -Rs 26,027 | -Rs 51,988 | Rs 1,51,359 |
| ORB Fresh | FINNIFTY | B: index pts | P3 | 1452 | 30% | 39% | -Rs 1,02,787 | -Rs 64 | -Rs 19,771 | -Rs 1,02,787 | -Rs 1,15,133 |
| ORB Fresh | MIDCPNIFTY | own (B0) | P1 | 361 | 41% | 46% | -Rs 44,953 | -Rs 91 | -Rs 10,267 | -Rs 62,316 | -Rs 70,831 |
| ORB Fresh | MIDCPNIFTY | own (B0) | P2 | 225 | 34% | 41% | -Rs 68,823 | -Rs 279 | -Rs 11,373 | -Rs 90,062 | -Rs 1,00,611 |
| ORB Fresh | MIDCPNIFTY | own (B0) | P3 | 135 | 32% | 52% | -Rs 1,11,122 | -Rs 836 | -Rs 12,639 | -Rs 1,14,317 | -Rs 1,36,430 |
| ORB Fresh | MIDCPNIFTY | A: premium pts | P1 | 4404 | 48% | 54% | -Rs 5,18,101 | -Rs 88 | -Rs 49,142 | -Rs 5,47,065 | -Rs 5,26,754 |
| ORB Fresh | MIDCPNIFTY | A: premium pts | P2 | 2724 | 47% | 52% | -Rs 3,80,034 | -Rs 128 | -Rs 52,346 | -Rs 4,77,309 | -Rs 3,90,205 |
| ORB Fresh | MIDCPNIFTY | A: premium pts | P3 | 1644 | 48% | 54% | -Rs 2,24,756 | -Rs 141 | -Rs 43,393 | -Rs 3,06,375 | -Rs 2,33,451 |
| ORB Fresh | MIDCPNIFTY | B: index pts | P1 | 4404 | 48% | 53% | -Rs 3,82,230 | -Rs 65 | -Rs 52,756 | -Rs 4,28,574 | -Rs 4,04,851 |
| ORB Fresh | MIDCPNIFTY | B: index pts | P2 | 2724 | 50% | 55% | -Rs 2,63,130 | -Rs 89 | -Rs 44,188 | -Rs 4,02,476 | -Rs 2,83,155 |
| ORB Fresh | MIDCPNIFTY | B: index pts | P3 | 1644 | 46% | 49% | -Rs 63,963 | -Rs 40 | -Rs 29,147 | -Rs 1,51,917 | -Rs 81,031 |
| ORB Fresh | SENSEX | own (B0) | P1 | 446 | 35% | 43% | -Rs 20,620 | -Rs 44 | -Rs 1,765 | -Rs 25,338 | -Rs 31,248 |
| ORB Fresh | SENSEX | own (B0) | P2 | 234 | 42% | 47% | -Rs 11,300 | -Rs 46 | -Rs 1,756 | -Rs 13,274 | -Rs 20,227 |
| ORB Fresh | SENSEX | own (B0) | P3 | 127 | 37% | 42% | -Rs 5,123 | -Rs 39 | -Rs 1,759 | -Rs 8,119 | -Rs 11,400 |
| ORB Fresh | SENSEX | A: premium pts | P1 | 5352 | 47% | 73% | -Rs 4,62,412 | -Rs 83 | -Rs 9,411 | -Rs 4,62,412 | -Rs 4,63,637 |
| ORB Fresh | SENSEX | A: premium pts | P2 | 2808 | 47% | 67% | -Rs 3,12,508 | -Rs 105 | -Rs 9,761 | -Rs 3,12,508 | -Rs 3,13,752 |
| ORB Fresh | SENSEX | A: premium pts | P3 | 1524 | 52% | 68% | -Rs 1,33,179 | -Rs 83 | -Rs 9,347 | -Rs 1,37,299 | -Rs 1,34,405 |
| ORB Fresh | SENSEX | B: index pts | P1 | 5352 | 34% | 43% | -Rs 2,09,905 | -Rs 37 | -Rs 32,635 | -Rs 2,47,075 | -Rs 2,18,839 |
| ORB Fresh | SENSEX | B: index pts | P2 | 2808 | 38% | 42% | -Rs 78,346 | -Rs 26 | -Rs 32,870 | -Rs 86,557 | -Rs 91,270 |
| ORB Fresh | SENSEX | B: index pts | P3 | 1524 | 45% | 55% | -Rs 87,130 | -Rs 55 | -Rs 12,014 | -Rs 89,962 | -Rs 93,322 |
| ORB Sweep | NIFTY | own (B0) | P1 | 768 | 30% | 36% | -Rs 1,25,559 | -Rs 139 | -Rs 6,170 | -Rs 1,25,873 | -Rs 1,55,537 |
| ORB Sweep | NIFTY | own (B0) | P2 | 189 | 30% | 39% | -Rs 56,728 | -Rs 230 | -Rs 5,374 | -Rs 67,423 | -Rs 86,581 |
| ORB Sweep | NIFTY | own (B0) | P3 | 125 | 31% | 30% | Rs 6,256 | Rs 47 | -Rs 5,337 | -Rs 14,948 | -Rs 20,998 |
| ORB Sweep | NIFTY | A: premium pts | P1 | 12696 | 39% | 53% | -Rs 14,14,437 | -Rs 130 | -Rs 30,505 | -Rs 14,14,437 | -Rs 14,19,795 |
| ORB Sweep | NIFTY | A: premium pts | P2 | 3191 | 44% | 52% | -Rs 2,87,171 | -Rs 97 | -Rs 26,217 | -Rs 3,64,292 | -Rs 2,92,530 |
| ORB Sweep | NIFTY | A: premium pts | P3 | 1989 | 42% | 54% | -Rs 2,60,954 | -Rs 164 | -Rs 23,841 | -Rs 2,60,954 | -Rs 2,65,560 |
| ORB Sweep | NIFTY | B: index pts | P1 | 12996 | 43% | 62% | -Rs 12,33,737 | -Rs 113 | -Rs 56,587 | -Rs 12,38,337 | -Rs 12,42,937 |
| ORB Sweep | NIFTY | B: index pts | P2 | 3281 | 47% | 55% | -Rs 2,25,242 | -Rs 76 | -Rs 41,172 | -Rs 2,57,931 | -Rs 2,32,387 |
| ORB Sweep | NIFTY | B: index pts | P3 | 2024 | 49% | 60% | -Rs 1,39,790 | -Rs 88 | -Rs 11,214 | -Rs 1,39,790 | -Rs 1,44,982 |
| ORB Sweep | BANKNIFTY | own (B0) | P1 | 1173 | 28% | 37% | -Rs 1,45,612 | -Rs 161 | -Rs 2,638 | -Rs 1,48,873 | -Rs 1,61,989 |
| ORB Sweep | BANKNIFTY | own (B0) | P2 | 326 | 25% | 40% | -Rs 95,660 | -Rs 387 | -Rs 3,089 | -Rs 1,00,998 | -Rs 1,10,704 |
| ORB Sweep | BANKNIFTY | own (B0) | P3 | 176 | 28% | 43% | -Rs 48,019 | -Rs 361 | -Rs 2,820 | -Rs 48,019 | -Rs 62,232 |
| ORB Sweep | BANKNIFTY | A: premium pts | P1 | 15558 | 48% | 63% | -Rs 12,69,403 | -Rs 117 | -Rs 14,013 | -Rs 12,70,191 | -Rs 12,71,388 |
| ORB Sweep | BANKNIFTY | A: premium pts | P2 | 4212 | 50% | 65% | -Rs 5,37,513 | -Rs 181 | -Rs 15,863 | -Rs 5,71,121 | -Rs 5,39,888 |
| ORB Sweep | BANKNIFTY | A: premium pts | P3 | 2243 | 47% | 67% | -Rs 3,60,408 | -Rs 226 | -Rs 16,222 | -Rs 3,63,885 | -Rs 3,62,379 |
| ORB Sweep | BANKNIFTY | B: index pts | P1 | 15846 | 38% | 55% | -Rs 7,35,245 | -Rs 68 | -Rs 10,975 | -Rs 7,39,117 | -Rs 7,43,240 |
| ORB Sweep | BANKNIFTY | B: index pts | P2 | 4248 | 37% | 58% | -Rs 4,35,348 | -Rs 147 | -Rs 14,161 | -Rs 4,58,821 | -Rs 4,42,531 |
| ORB Sweep | BANKNIFTY | B: index pts | P3 | 2244 | 36% | 61% | -Rs 2,66,152 | -Rs 167 | -Rs 14,518 | -Rs 2,66,152 | -Rs 2,69,567 |
| ORB Sweep | FINNIFTY | own (B0) | P1 | 644 | 29% | 44% | -Rs 2,27,272 | -Rs 254 | -Rs 16,054 | -Rs 2,31,457 | -Rs 2,55,085 |
| ORB Sweep | FINNIFTY | own (B0) | P2 | 265 | 31% | 38% | -Rs 62,190 | -Rs 252 | -Rs 19,686 | -Rs 62,747 | -Rs 90,740 |
| ORB Sweep | FINNIFTY | own (B0) | P3 | 135 | 39% | 44% | -Rs 27,950 | -Rs 210 | -Rs 6,114 | -Rs 45,962 | -Rs 54,513 |
| ORB Sweep | FINNIFTY | A: premium pts | P1 | 10003 | 42% | 58% | -Rs 12,41,638 | -Rs 116 | -Rs 1,92,539 | -Rs 12,50,118 | -Rs 12,46,240 |
| ORB Sweep | FINNIFTY | A: premium pts | P2 | 4212 | 46% | 60% | -Rs 7,72,776 | -Rs 261 | -Rs 2,11,295 | -Rs 7,89,106 | -Rs 7,77,367 |
| ORB Sweep | FINNIFTY | A: premium pts | P3 | 2102 | 42% | 56% | -Rs 3,52,878 | -Rs 221 | -Rs 52,677 | -Rs 3,52,878 | -Rs 3,57,065 |
| ORB Sweep | FINNIFTY | B: index pts | P1 | 10258 | 43% | 60% | -Rs 8,68,029 | -Rs 81 | -Rs 1,28,709 | -Rs 8,71,563 | -Rs 8,78,609 |
| ORB Sweep | FINNIFTY | B: index pts | P2 | 4307 | 40% | 53% | -Rs 3,58,669 | -Rs 121 | -Rs 21,472 | -Rs 3,62,765 | -Rs 3,68,561 |
| ORB Sweep | FINNIFTY | B: index pts | P3 | 2159 | 25% | 43% | -Rs 2,60,110 | -Rs 163 | -Rs 19,093 | -Rs 2,60,110 | -Rs 2,67,129 |
| ORB Sweep | MIDCPNIFTY | own (B0) | P1 | 273 | 29% | 43% | -Rs 1,42,726 | -Rs 290 | -Rs 4,985 | -Rs 1,51,887 | -Rs 1,83,190 |
| ORB Sweep | MIDCPNIFTY | own (B0) | P2 | 209 | 34% | 40% | -Rs 82,176 | -Rs 333 | -Rs 9,924 | -Rs 1,15,056 | -Rs 1,39,054 |
| ORB Sweep | MIDCPNIFTY | own (B0) | P3 | 131 | 37% | 35% | Rs 14,897 | Rs 112 | -Rs 5,564 | -Rs 45,511 | -Rs 36,289 |
| ORB Sweep | MIDCPNIFTY | A: premium pts | P1 | 4728 | 35% | 50% | -Rs 9,76,756 | -Rs 165 | -Rs 44,766 | -Rs 9,76,756 | -Rs 9,85,425 |
| ORB Sweep | MIDCPNIFTY | A: premium pts | P2 | 3596 | 42% | 54% | -Rs 9,44,100 | -Rs 319 | -Rs 46,287 | -Rs 9,44,100 | -Rs 9,54,301 |
| ORB Sweep | MIDCPNIFTY | A: premium pts | P3 | 2338 | 47% | 50% | -Rs 1,10,223 | -Rs 69 | -Rs 26,849 | -Rs 1,98,558 | -Rs 1,18,873 |
| ORB Sweep | MIDCPNIFTY | B: index pts | P1 | 4856 | 41% | 57% | -Rs 7,50,554 | -Rs 127 | -Rs 43,084 | -Rs 7,50,554 | -Rs 7,65,049 |
| ORB Sweep | MIDCPNIFTY | B: index pts | P2 | 3640 | 46% | 56% | -Rs 5,10,825 | -Rs 172 | -Rs 37,138 | -Rs 5,20,435 | -Rs 5,21,363 |
| ORB Sweep | MIDCPNIFTY | B: index pts | P3 | 2384 | 45% | 51% | -Rs 2,03,046 | -Rs 127 | -Rs 26,246 | -Rs 2,41,264 | -Rs 2,14,507 |
| ORB Sweep | SENSEX | own (B0) | P1 | 602 | 26% | 38% | -Rs 57,866 | -Rs 124 | -Rs 4,352 | -Rs 59,128 | -Rs 71,990 |
| ORB Sweep | SENSEX | own (B0) | P2 | 320 | 26% | 41% | -Rs 52,235 | -Rs 211 | -Rs 1,781 | -Rs 53,177 | -Rs 61,598 |
| ORB Sweep | SENSEX | own (B0) | P3 | 190 | 29% | 37% | -Rs 19,466 | -Rs 146 | -Rs 1,764 | -Rs 19,547 | -Rs 31,669 |
| ORB Sweep | SENSEX | A: premium pts | P1 | 7829 | 45% | 69% | -Rs 5,75,878 | -Rs 103 | -Rs 9,495 | -Rs 5,75,878 | -Rs 5,77,134 |
| ORB Sweep | SENSEX | A: premium pts | P2 | 4125 | 50% | 65% | -Rs 3,09,254 | -Rs 104 | -Rs 9,816 | -Rs 3,09,254 | -Rs 3,10,509 |
| ORB Sweep | SENSEX | A: premium pts | P3 | 2479 | 49% | 66% | -Rs 2,23,670 | -Rs 140 | -Rs 9,574 | -Rs 2,23,670 | -Rs 2,24,921 |
| ORB Sweep | SENSEX | B: index pts | P1 | 7956 | 25% | 50% | -Rs 4,93,686 | -Rs 88 | -Rs 52,220 | -Rs 4,93,686 | -Rs 5,04,339 |
| ORB Sweep | SENSEX | B: index pts | P2 | 4168 | 33% | 51% | -Rs 2,09,583 | -Rs 71 | -Rs 12,791 | -Rs 2,12,923 | -Rs 2,15,534 |
| ORB Sweep | SENSEX | B: index pts | P3 | 2496 | 33% | 62% | -Rs 2,01,101 | -Rs 126 | -Rs 10,207 | -Rs 2,01,893 | -Rs 2,04,204 |

## ORB on BANKNIFTY (the app's arm): every exit rule, 1 lot

| exit | P1: trades / win / BE win / net | P2: trades / win / BE win / net | P3: trades / win / BE win / net | P3 per session | P3 worst day | P3 max DD | P3 without 5 best | P3 avg charges | twin p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| own exits (B0) | 854 / 37% / 50% / -Rs 94,603 | 233 / 36% / 46% / -Rs 24,348 | 130 / 43% / 42% / Rs 1,791 | Rs 13 | -Rs 2,178 | -Rs 6,544 | -Rs 7,042 | Rs 112 | - | - | baseline |
| A10s10t15 | 854 / 41% / 66% / -Rs 93,700 | 233 / 48% / 65% / -Rs 27,876 | 130 / 51% / 70% / -Rs 15,859 | -Rs 119 | -Rs 1,097 | -Rs 16,162 | -Rs 17,029 | Rs 111 | 0.9641 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A10s10t30 | 854 / 41% / 66% / -Rs 94,509 | 233 / 48% / 65% / -Rs 27,876 | 130 / 51% / 70% / -Rs 15,859 | -Rs 119 | -Rs 1,097 | -Rs 16,162 | -Rs 17,029 | Rs 111 | 0.9162 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A10s15t15 | 854 / 53% / 72% / -Rs 89,218 | 233 / 58% / 72% / -Rs 28,645 | 130 / 61% / 76% / -Rs 15,035 | -Rs 113 | -Rs 1,359 | -Rs 15,236 | -Rs 16,206 | Rs 111 | 0.9561 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A10s15t30 | 854 / 53% / 72% / -Rs 90,634 | 233 / 58% / 72% / -Rs 29,165 | 130 / 61% / 76% / -Rs 15,541 | -Rs 117 | -Rs 1,359 | -Rs 15,695 | -Rs 16,712 | Rs 111 | 0.9202 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s12t15 | 854 / 44% / 63% / -Rs 83,895 | 233 / 48% / 63% / -Rs 27,534 | 130 / 52% / 67% / -Rs 15,269 | -Rs 115 | -Rs 1,217 | -Rs 15,752 | -Rs 16,740 | Rs 111 | 0.9641 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s12t30 | 854 / 44% / 63% / -Rs 85,112 | 233 / 48% / 63% / -Rs 28,112 | 130 / 52% / 67% / -Rs 15,269 | -Rs 115 | -Rs 1,217 | -Rs 15,752 | -Rs 16,740 | Rs 111 | 0.9062 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s18t15 | 854 / 54% / 70% / -Rs 86,134 | 233 / 59% / 70% / -Rs 25,691 | 130 / 65% / 73% / -Rs 8,356 | -Rs 63 | -Rs 1,446 | -Rs 10,941 | -Rs 9,827 | Rs 111 | 0.7864 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s18t30 | 854 / 55% / 70% / -Rs 88,563 | 233 / 59% / 70% / -Rs 25,791 | 130 / 66% / 73% / -Rs 8,642 | -Rs 65 | -Rs 1,446 | -Rs 11,226 | -Rs 10,112 | Rs 111 | 0.7804 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s15t15 | 854 / 46% / 60% / -Rs 80,657 | 233 / 48% / 61% / -Rs 29,895 | 130 / 53% / 63% / -Rs 11,868 | -Rs 89 | -Rs 1,359 | -Rs 11,868 | -Rs 13,788 | Rs 111 | 0.9002 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s15t30 | 854 / 46% / 61% / -Rs 81,904 | 233 / 48% / 60% / -Rs 30,091 | 130 / 53% / 64% / -Rs 12,998 | -Rs 98 | -Rs 1,359 | -Rs 12,998 | -Rs 14,918 | Rs 111 | 0.8802 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s22.5t15 | 854 / 55% / 68% / -Rs 85,551 | 233 / 56% / 66% / -Rs 26,832 | 130 / 64% / 69% / -Rs 7,595 | -Rs 57 | -Rs 877 | -Rs 8,147 | -Rs 9,515 | Rs 111 | 0.7844 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s22.5t30 | 854 / 56% / 68% / -Rs 86,147 | 233 / 58% / 68% / -Rs 26,143 | 130 / 65% / 70% / -Rs 8,483 | -Rs 64 | -Rs 877 | -Rs 9,035 | -Rs 10,403 | Rs 111 | 0.7565 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| B10s10t15 | 854 / 37% / 47% / -Rs 47,413 | 233 / 39% / 49% / -Rs 15,480 | 130 / 52% / 49% / Rs 3,305 | Rs 25 | -Rs 1,594 | -Rs 3,175 | -Rs 2,795 | Rs 112 | 0.2954 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s10t30 | 854 / 37% / 47% / -Rs 47,413 | 233 / 39% / 49% / -Rs 15,480 | 130 / 52% / 49% / Rs 3,305 | Rs 25 | -Rs 1,594 | -Rs 3,175 | -Rs 2,795 | Rs 112 | 0.4032 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s15t15 | 854 / 39% / 48% / -Rs 46,795 | 233 / 39% / 49% / -Rs 15,698 | 130 / 52% / 49% / Rs 2,245 | Rs 17 | -Rs 1,594 | -Rs 3,890 | -Rs 3,854 | Rs 112 | 0.3313 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s15t30 | 854 / 39% / 48% / -Rs 46,795 | 233 / 39% / 49% / -Rs 15,698 | 130 / 52% / 49% / Rs 2,245 | Rs 17 | -Rs 1,594 | -Rs 3,890 | -Rs 3,854 | Rs 112 | 0.4471 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s12t15 | 854 / 38% / 47% / -Rs 47,868 | 233 / 39% / 49% / -Rs 16,454 | 130 / 52% / 49% / Rs 3,144 | Rs 24 | -Rs 1,594 | -Rs 3,558 | -Rs 2,956 | Rs 112 | 0.3174 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s12t30 | 854 / 38% / 47% / -Rs 47,868 | 233 / 39% / 49% / -Rs 16,454 | 130 / 52% / 49% / Rs 3,144 | Rs 24 | -Rs 1,594 | -Rs 3,558 | -Rs 2,956 | Rs 112 | 0.3673 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s18t15 | 854 / 40% / 49% / -Rs 46,792 | 233 / 40% / 50% / -Rs 16,330 | 130 / 53% / 51% / Rs 1,849 | Rs 14 | -Rs 1,594 | -Rs 3,330 | -Rs 4,250 | Rs 112 | 0.3553 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s18t30 | 854 / 40% / 49% / -Rs 46,792 | 233 / 40% / 50% / -Rs 16,330 | 130 / 53% / 51% / Rs 1,849 | Rs 14 | -Rs 1,594 | -Rs 3,330 | -Rs 4,250 | Rs 112 | 0.4471 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s15t15 | 854 / 38% / 48% / -Rs 54,921 | 233 / 41% / 51% / -Rs 18,031 | 130 / 53% / 50% / Rs 2,833 | Rs 21 | -Rs 1,594 | -Rs 3,963 | -Rs 3,267 | Rs 112 | 0.3134 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s15t30 | 854 / 38% / 48% / -Rs 55,141 | 233 / 41% / 51% / -Rs 18,031 | 130 / 53% / 50% / Rs 2,833 | Rs 21 | -Rs 1,594 | -Rs 3,963 | -Rs 3,267 | Rs 112 | 0.4092 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s22.5t15 | 854 / 43% / 52% / -Rs 56,228 | 233 / 44% / 54% / -Rs 17,951 | 130 / 55% / 54% / Rs 1,390 | Rs 10 | -Rs 1,594 | -Rs 4,236 | -Rs 4,709 | Rs 112 | 0.3852 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| B15s22.5t30 | 854 / 43% / 52% / -Rs 56,223 | 233 / 44% / 54% / -Rs 17,951 | 130 / 55% / 54% / Rs 1,390 | Rs 10 | -Rs 1,594 | -Rs 4,236 | -Rs 4,709 | Rs 112 | 0.4451 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |

## ORB Fresh on BANKNIFTY (the app's arm): every exit rule, 1 lot

| exit | P1: trades / win / BE win / net | P2: trades / win / BE win / net | P3: trades / win / BE win / net | P3 per session | P3 worst day | P3 max DD | P3 without 5 best | P3 avg charges | twin p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| own exits (B0) | 854 / 37% / 50% / -Rs 94,603 | 233 / 36% / 46% / -Rs 24,348 | 130 / 43% / 42% / Rs 1,791 | Rs 13 | -Rs 2,178 | -Rs 6,544 | -Rs 7,042 | Rs 112 | - | - | baseline |
| A10s10t15 | 854 / 41% / 66% / -Rs 93,700 | 233 / 48% / 65% / -Rs 27,876 | 130 / 51% / 70% / -Rs 15,859 | -Rs 119 | -Rs 1,097 | -Rs 16,162 | -Rs 17,029 | Rs 111 | 0.9601 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A10s10t30 | 854 / 41% / 66% / -Rs 94,509 | 233 / 48% / 65% / -Rs 27,876 | 130 / 51% / 70% / -Rs 15,859 | -Rs 119 | -Rs 1,097 | -Rs 16,162 | -Rs 17,029 | Rs 111 | 0.9122 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A10s15t15 | 854 / 53% / 72% / -Rs 89,218 | 233 / 58% / 72% / -Rs 28,645 | 130 / 61% / 76% / -Rs 15,035 | -Rs 113 | -Rs 1,359 | -Rs 15,236 | -Rs 16,206 | Rs 111 | 0.9701 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A10s15t30 | 854 / 53% / 72% / -Rs 90,634 | 233 / 58% / 72% / -Rs 29,165 | 130 / 61% / 76% / -Rs 15,541 | -Rs 117 | -Rs 1,359 | -Rs 15,695 | -Rs 16,712 | Rs 111 | 0.9102 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s12t15 | 854 / 44% / 63% / -Rs 83,895 | 233 / 48% / 63% / -Rs 27,534 | 130 / 52% / 67% / -Rs 15,269 | -Rs 115 | -Rs 1,217 | -Rs 15,752 | -Rs 16,740 | Rs 111 | 0.9641 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s12t30 | 854 / 44% / 63% / -Rs 85,112 | 233 / 48% / 63% / -Rs 28,112 | 130 / 52% / 67% / -Rs 15,269 | -Rs 115 | -Rs 1,217 | -Rs 15,752 | -Rs 16,740 | Rs 111 | 0.9042 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s18t15 | 854 / 54% / 70% / -Rs 86,134 | 233 / 59% / 70% / -Rs 25,691 | 130 / 65% / 73% / -Rs 8,356 | -Rs 63 | -Rs 1,446 | -Rs 10,941 | -Rs 9,827 | Rs 111 | 0.8403 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A12s18t30 | 854 / 55% / 70% / -Rs 88,563 | 233 / 59% / 70% / -Rs 25,791 | 130 / 66% / 73% / -Rs 8,642 | -Rs 65 | -Rs 1,446 | -Rs 11,226 | -Rs 10,112 | Rs 111 | 0.7685 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s15t15 | 854 / 46% / 60% / -Rs 80,657 | 233 / 48% / 61% / -Rs 29,895 | 130 / 53% / 63% / -Rs 11,868 | -Rs 89 | -Rs 1,359 | -Rs 11,868 | -Rs 13,788 | Rs 111 | 0.8982 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s15t30 | 854 / 46% / 61% / -Rs 81,904 | 233 / 48% / 60% / -Rs 30,091 | 130 / 53% / 64% / -Rs 12,998 | -Rs 98 | -Rs 1,359 | -Rs 12,998 | -Rs 14,918 | Rs 111 | 0.8782 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s22.5t15 | 854 / 55% / 68% / -Rs 85,551 | 233 / 56% / 66% / -Rs 26,832 | 130 / 64% / 69% / -Rs 7,595 | -Rs 57 | -Rs 877 | -Rs 8,147 | -Rs 9,515 | Rs 111 | 0.7705 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| A15s22.5t30 | 854 / 56% / 68% / -Rs 86,147 | 233 / 58% / 68% / -Rs 26,143 | 130 / 65% / 70% / -Rs 8,483 | -Rs 64 | -Rs 877 | -Rs 9,035 | -Rs 10,403 | Rs 111 | 0.7764 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| B10s10t15 | 854 / 37% / 47% / -Rs 47,413 | 233 / 39% / 49% / -Rs 15,480 | 130 / 52% / 49% / Rs 3,305 | Rs 25 | -Rs 1,594 | -Rs 3,175 | -Rs 2,795 | Rs 112 | 0.2754 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s10t30 | 854 / 37% / 47% / -Rs 47,413 | 233 / 39% / 49% / -Rs 15,480 | 130 / 52% / 49% / Rs 3,305 | Rs 25 | -Rs 1,594 | -Rs 3,175 | -Rs 2,795 | Rs 112 | 0.3972 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s15t15 | 854 / 39% / 48% / -Rs 46,795 | 233 / 39% / 49% / -Rs 15,698 | 130 / 52% / 49% / Rs 2,245 | Rs 17 | -Rs 1,594 | -Rs 3,890 | -Rs 3,854 | Rs 112 | 0.3453 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s15t30 | 854 / 39% / 48% / -Rs 46,795 | 233 / 39% / 49% / -Rs 15,698 | 130 / 52% / 49% / Rs 2,245 | Rs 17 | -Rs 1,594 | -Rs 3,890 | -Rs 3,854 | Rs 112 | 0.4331 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s12t15 | 854 / 38% / 47% / -Rs 47,868 | 233 / 39% / 49% / -Rs 16,454 | 130 / 52% / 49% / Rs 3,144 | Rs 24 | -Rs 1,594 | -Rs 3,558 | -Rs 2,956 | Rs 112 | 0.3014 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s12t30 | 854 / 38% / 47% / -Rs 47,868 | 233 / 39% / 49% / -Rs 16,454 | 130 / 52% / 49% / Rs 3,144 | Rs 24 | -Rs 1,594 | -Rs 3,558 | -Rs 2,956 | Rs 112 | 0.3553 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s18t15 | 854 / 40% / 49% / -Rs 46,792 | 233 / 40% / 50% / -Rs 16,330 | 130 / 53% / 51% / Rs 1,849 | Rs 14 | -Rs 1,594 | -Rs 3,330 | -Rs 4,250 | Rs 112 | 0.3513 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s18t30 | 854 / 40% / 49% / -Rs 46,792 | 233 / 40% / 50% / -Rs 16,330 | 130 / 53% / 51% / Rs 1,849 | Rs 14 | -Rs 1,594 | -Rs 3,330 | -Rs 4,250 | Rs 112 | 0.4271 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s15t15 | 854 / 38% / 48% / -Rs 54,921 | 233 / 41% / 51% / -Rs 18,031 | 130 / 53% / 50% / Rs 2,833 | Rs 21 | -Rs 1,594 | -Rs 3,963 | -Rs 3,267 | Rs 112 | 0.3273 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s15t30 | 854 / 38% / 48% / -Rs 55,141 | 233 / 41% / 51% / -Rs 18,031 | 130 / 53% / 50% / Rs 2,833 | Rs 21 | -Rs 1,594 | -Rs 3,963 | -Rs 3,267 | Rs 112 | 0.4072 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s22.5t15 | 854 / 43% / 52% / -Rs 56,228 | 233 / 44% / 54% / -Rs 17,951 | 130 / 55% / 54% / Rs 1,390 | Rs 10 | -Rs 1,594 | -Rs 4,236 | -Rs 4,709 | Rs 112 | 0.3752 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |
| B15s22.5t30 | 854 / 43% / 52% / -Rs 56,223 | 233 / 44% / 54% / -Rs 17,951 | 130 / 55% / 54% / Rs 1,390 | Rs 10 | -Rs 1,594 | -Rs 4,236 | -Rs 4,709 | Rs 112 | 0.4691 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1; not above the arm's own exits in P2 and P3 |

## ORB Sweep on BANKNIFTY (the app's arm): every exit rule, 1 lot

| exit | P1: trades / win / BE win / net | P2: trades / win / BE win / net | P3: trades / win / BE win / net | P3 per session | P3 worst day | P3 max DD | P3 without 5 best | P3 avg charges | twin p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| own exits (B0) | 1173 / 28% / 37% / -Rs 1,45,612 | 326 / 25% / 40% / -Rs 95,660 | 176 / 28% / 43% / -Rs 48,019 | -Rs 361 | -Rs 2,820 | -Rs 48,019 | -Rs 62,232 | Rs 96 | - | - | baseline |
| A10s10t15 | 1309 / 44% / 63% / -Rs 1,06,112 | 353 / 47% / 64% / -Rs 38,876 | 187 / 43% / 67% / -Rs 26,966 | -Rs 203 | -Rs 1,026 | -Rs 26,978 | -Rs 28,166 | Rs 95 | 0.0958 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A10s10t30 | 1306 / 45% / 64% / -Rs 1,06,645 | 353 / 47% / 63% / -Rs 39,517 | 187 / 45% / 67% / -Rs 26,101 | -Rs 196 | -Rs 1,026 | -Rs 26,112 | -Rs 27,300 | Rs 95 | 0.0898 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A10s15t15 | 1304 / 53% / 68% / -Rs 94,796 | 352 / 56% / 70% / -Rs 40,037 | 187 / 53% / 73% / -Rs 27,081 | -Rs 204 | -Rs 1,331 | -Rs 27,333 | -Rs 28,282 | Rs 95 | 0.0978 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A10s15t30 | 1297 / 55% / 70% / -Rs 1,02,012 | 352 / 56% / 70% / -Rs 41,624 | 187 / 56% / 74% / -Rs 25,452 | -Rs 191 | -Rs 1,331 | -Rs 25,704 | -Rs 26,653 | Rs 95 | 0.0818 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A12s12t15 | 1304 / 44% / 60% / -Rs 1,01,793 | 352 / 46% / 62% / -Rs 43,757 | 187 / 40% / 64% / -Rs 32,252 | -Rs 242 | -Rs 1,145 | -Rs 32,323 | -Rs 33,718 | Rs 95 | 0.4112 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A12s12t30 | 1299 / 46% / 61% / -Rs 1,05,013 | 352 / 45% / 61% / -Rs 44,461 | 187 / 40% / 64% / -Rs 33,297 | -Rs 250 | -Rs 1,145 | -Rs 33,368 | -Rs 34,777 | Rs 95 | 0.2874 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A12s18t15 | 1299 / 51% / 65% / -Rs 1,00,418 | 350 / 54% / 69% / -Rs 45,997 | 187 / 50% / 70% / -Rs 30,211 | -Rs 227 | -Rs 1,504 | -Rs 30,612 | -Rs 31,706 | Rs 95 | 0.2575 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A12s18t30 | 1286 / 54% / 67% / -Rs 1,07,910 | 350 / 54% / 68% / -Rs 48,363 | 187 / 52% / 71% / -Rs 30,824 | -Rs 232 | -Rs 1,504 | -Rs 31,225 | -Rs 32,325 | Rs 95 | 0.2655 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A15s15t15 | 1298 / 43% / 57% / -Rs 1,02,407 | 351 / 46% / 60% / -Rs 45,403 | 187 / 41% / 61% / -Rs 32,166 | -Rs 242 | -Rs 1,331 | -Rs 33,017 | -Rs 34,099 | Rs 95 | 0.4291 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A15s15t30 | 1287 / 45% / 59% / -Rs 1,15,393 | 350 / 45% / 59% / -Rs 47,722 | 187 / 42% / 61% / -Rs 31,954 | -Rs 240 | -Rs 1,331 | -Rs 32,805 | -Rs 33,898 | Rs 95 | 0.2575 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A15s22.5t15 | 1294 / 48% / 62% / -Rs 1,08,760 | 349 / 52% / 65% / -Rs 46,854 | 187 / 49% / 66% / -Rs 32,111 | -Rs 241 | -Rs 1,774 | -Rs 32,737 | -Rs 34,055 | Rs 95 | 0.4152 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| A15s22.5t30 | 1275 / 51% / 64% / -Rs 1,18,143 | 348 / 52% / 66% / -Rs 54,903 | 186 / 52% / 68% / -Rs 31,994 | -Rs 241 | -Rs 1,774 | -Rs 32,621 | -Rs 33,945 | Rs 95 | 0.3673 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s10t15 | 1322 / 35% / 53% / -Rs 63,328 | 354 / 32% / 55% / -Rs 35,459 | 187 / 35% / 62% / -Rs 21,404 | -Rs 161 | -Rs 1,210 | -Rs 21,404 | -Rs 24,097 | Rs 95 | 0.0319 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s10t30 | 1322 / 35% / 53% / -Rs 63,328 | 354 / 32% / 55% / -Rs 35,459 | 187 / 35% / 62% / -Rs 21,404 | -Rs 161 | -Rs 1,210 | -Rs 21,404 | -Rs 24,097 | Rs 95 | 0.0399 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s15t15 | 1322 / 37% / 55% / -Rs 63,479 | 354 / 36% / 58% / -Rs 35,180 | 187 / 34% / 61% / -Rs 22,496 | -Rs 169 | -Rs 1,210 | -Rs 22,496 | -Rs 25,318 | Rs 95 | 0.0499 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B10s15t30 | 1322 / 37% / 55% / -Rs 63,479 | 354 / 36% / 58% / -Rs 35,180 | 187 / 34% / 61% / -Rs 22,496 | -Rs 169 | -Rs 1,210 | -Rs 22,496 | -Rs 25,318 | Rs 95 | 0.0299 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s12t15 | 1321 / 36% / 54% / -Rs 62,696 | 354 / 33% / 56% / -Rs 36,534 | 187 / 36% / 62% / -Rs 20,557 | -Rs 155 | -Rs 1,210 | -Rs 20,557 | -Rs 23,250 | Rs 95 | 0.016 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s12t30 | 1321 / 36% / 54% / -Rs 62,697 | 354 / 33% / 56% / -Rs 36,534 | 187 / 36% / 62% / -Rs 20,557 | -Rs 155 | -Rs 1,210 | -Rs 20,557 | -Rs 23,250 | Rs 95 | 0.022 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s18t15 | 1320 / 40% / 55% / -Rs 57,653 | 354 / 38% / 59% / -Rs 36,939 | 187 / 37% / 62% / -Rs 21,950 | -Rs 165 | -Rs 1,210 | -Rs 21,950 | -Rs 24,772 | Rs 95 | 0.0379 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B12s18t30 | 1320 / 40% / 55% / -Rs 57,661 | 354 / 38% / 59% / -Rs 36,939 | 187 / 37% / 62% / -Rs 21,950 | -Rs 165 | -Rs 1,210 | -Rs 21,950 | -Rs 24,772 | Rs 95 | 0.0359 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s15t15 | 1320 / 38% / 54% / -Rs 63,488 | 354 / 38% / 59% / -Rs 36,986 | 187 / 35% / 59% / -Rs 22,439 | -Rs 169 | -Rs 1,210 | -Rs 22,439 | -Rs 25,564 | Rs 95 | 0.0279 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s15t30 | 1320 / 38% / 54% / -Rs 63,470 | 354 / 38% / 59% / -Rs 36,986 | 187 / 35% / 59% / -Rs 22,439 | -Rs 169 | -Rs 1,210 | -Rs 22,439 | -Rs 25,564 | Rs 95 | 0.0319 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s22.5t15 | 1318 / 44% / 57% / -Rs 57,391 | 354 / 43% / 62% / -Rs 36,593 | 187 / 37% / 62% / -Rs 24,362 | -Rs 183 | -Rs 1,210 | -Rs 24,582 | -Rs 27,487 | Rs 95 | 0.0699 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |
| B15s22.5t30 | 1318 / 44% / 57% / -Rs 56,575 | 354 / 43% / 62% / -Rs 36,559 | 187 / 38% / 62% / -Rs 24,100 | -Rs 181 | -Rs 1,210 | -Rs 24,320 | -Rs 27,225 | Rs 95 | 0.0679 | 1.0 | fail: not positive in P1, P2 and P3; exit-twin BH q 1.00 > 0.1 |

## 1, 2 and 3 lots (ORB on BANKNIFTY; net a lot a trade, all periods)

| exits | 1 lot: win / needed / net a trade | 2 lots: per lot a trade | 3 lots: per lot a trade |
|---|---|---|---|
| B0 | 38% / 48% / -Rs 96 | -Rs 72 | -Rs 64 |
| A10s10t15 | 43% / 65% / -Rs 113 | -Rs 90 | -Rs 81 |
| A10s10t30 | 43% / 66% / -Rs 114 | -Rs 90 | -Rs 82 |
| A10s15t15 | 55% / 72% / -Rs 109 | -Rs 86 | -Rs 78 |
| A10s15t30 | 55% / 72% / -Rs 111 | -Rs 88 | -Rs 80 |
| A12s12t15 | 46% / 63% / -Rs 104 | -Rs 80 | -Rs 73 |
| A12s12t30 | 46% / 63% / -Rs 106 | -Rs 82 | -Rs 74 |
| A12s18t15 | 56% / 70% / -Rs 99 | -Rs 75 | -Rs 67 |
| A12s18t30 | 57% / 70% / -Rs 101 | -Rs 78 | -Rs 70 |
| A15s15t15 | 47% / 60% / -Rs 101 | -Rs 77 | -Rs 69 |
| A15s15t30 | 47% / 61% / -Rs 103 | -Rs 79 | -Rs 71 |
| A15s22.5t15 | 56% / 67% / -Rs 99 | -Rs 75 | -Rs 67 |
| A15s22.5t30 | 57% / 68% / -Rs 99 | -Rs 76 | -Rs 68 |
| B10s10t15 | 39% / 47% / -Rs 49 | -Rs 26 | -Rs 17 |
| B10s10t30 | 39% / 47% / -Rs 49 | -Rs 26 | -Rs 17 |
| B10s15t15 | 40% / 48% / -Rs 50 | -Rs 26 | -Rs 18 |
| B10s15t30 | 40% / 48% / -Rs 50 | -Rs 26 | -Rs 18 |
| B12s12t15 | 40% / 47% / -Rs 50 | -Rs 26 | -Rs 19 |
| B12s12t30 | 40% / 47% / -Rs 50 | -Rs 26 | -Rs 19 |
| B12s18t15 | 41% / 49% / -Rs 50 | -Rs 26 | -Rs 19 |
| B12s18t30 | 41% / 49% / -Rs 50 | -Rs 26 | -Rs 19 |
| B15s15t15 | 40% / 49% / -Rs 58 | -Rs 34 | -Rs 26 |
| B15s15t30 | 40% / 49% / -Rs 58 | -Rs 34 | -Rs 26 |
| B15s22.5t15 | 44% / 53% / -Rs 60 | -Rs 36 | -Rs 28 |
| B15s22.5t30 | 44% / 53% / -Rs 60 | -Rs 36 | -Rs 28 |

## Walk-forward pick (descriptive, never gated)

Each quarter of P2 and the final take the exit with the best 1-lot net on all earlier data.

| arm | index | P2 picks | P2 net | P3 pick | P3 net |
|---|---|---|---|---|---|
| ORB | NIFTY | B10s15t15, B12s12t15, B10s15t15, B12s18t15 | -Rs 12,485 | B10s15t15 | -Rs 10,525 |
| ORB | BANKNIFTY | B12s18t15, B10s15t15, B10s15t15, B10s15t15 | -Rs 15,831 | B10s15t15 | Rs 2,245 |
| ORB | FINNIFTY | B10s10t30, B15s22.5t15, B10s10t30, B10s10t30 | Rs 12,267 | B10s10t30 | -Rs 10,552 |
| ORB | MIDCPNIFTY | B15s22.5t30, B15s15t30, B15s15t30, B15s15t15 | -Rs 38,907 | B10s10t15 | Rs 861 |
| ORB | SENSEX | B15s22.5t15, B15s22.5t15, B15s22.5t15, B10s15t15 | -Rs 8,211 | B15s22.5t15 | -Rs 7,217 |
| ORB Fresh | NIFTY | B12s12t15, B12s12t15, B10s15t15, B12s18t15 | -Rs 11,402 | B10s15t15 | -Rs 10,525 |
| ORB Fresh | BANKNIFTY | B12s18t15, B10s15t15, B10s15t15, B10s15t15 | -Rs 15,831 | B10s15t15 | Rs 2,245 |
| ORB Fresh | FINNIFTY | B15s22.5t15, B15s22.5t15, B15s22.5t15, B10s10t30 | Rs 11,522 | B15s22.5t15 | -Rs 142 |
| ORB Fresh | MIDCPNIFTY | B15s22.5t30, B15s15t30, B15s15t30, B10s10t15 | -Rs 35,743 | B10s10t15 | Rs 861 |
| ORB Fresh | SENSEX | B15s22.5t15, B10s15t15, B10s15t15, B10s15t15 | -Rs 6,891 | B15s22.5t15 | -Rs 7,217 |
| ORB Sweep | NIFTY | B10s10t30, B10s10t30, B10s10t30, B10s10t30 | -Rs 17,613 | B10s10t30 | -Rs 13,119 |
| ORB Sweep | BANKNIFTY | B15s22.5t30, B12s18t15, B15s22.5t30, B15s22.5t30 | -Rs 38,080 | B15s22.5t30 | -Rs 24,100 |
| ORB Sweep | FINNIFTY | B15s22.5t30, B15s15t15, B15s15t15, B15s22.5t30 | -Rs 37,763 | B15s15t15 | -Rs 24,438 |
| ORB Sweep | MIDCPNIFTY | B10s10t15, B10s10t15, B10s10t15, B10s10t15 | -Rs 25,056 | B10s10t15 | -Rs 16,381 |
| ORB Sweep | SENSEX | B12s12t15, B12s12t15, B12s12t15, B12s18t15 | -Rs 17,502 | B12s18t15 | -Rs 17,887 |

## Gates

Tests: 360; m = 3,058; passed: 0. Failures by gate: not positive in P1, P2 and P3: 360; exit-twin BH: 360; not above the arm's own exits in P2 and P3: 189. Even counting R168's own 360 tests alone, the smallest BH q is 0.4114. Every arm x index x exit with its gate result is in `results/tables.md`, `results/decision.json` and `results/raw_pvalues.csv`.

