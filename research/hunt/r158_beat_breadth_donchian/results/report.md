# R158: can anything beat Breadth Donchian on win rate and profit?

## For Boss (short)

- **Verdict: nothing found beats Breadth Donchian on both win rate and profit in every period.** 0 of 5 rules passed the gates.
- **The bar (Breadth Donchian, 1 lot, 5 indices):** Apr 2025 - Mar 2026: 2,238 trades, win 49% (needed 44%), Rs 3,35,882 (Rs 150 a trade); Apr - Oct 2026: 1,202 trades, win 46% (needed 41%), Rs 1,71,366 (Rs 143 a trade). With R151's picker (already in the app): 652 trades, win 48.9%, Rs 2,19,057 in Apr - Oct 2026.
- **What was tried:** a network trained on the result of Breadth Donchian's own exit (-15% stop, profit ladder, 30 minutes unless in profit) at every 5-minute moment since Aug 2021, reading the last hour of all five indices. It was asked for its own entries, and to pick among Breadth Donchian's entries.
- **NET-EV (network's own entries, forecast net > 0):** 2023 - Mar 2025: 256 trades, win 39% (needed 56%), -Rs 32,231 (-Rs 126 a trade); Apr 2025 - Mar 2026: 34 trades, win 59% (needed 58%), Rs 838 (Rs 25 a trade); Apr - Oct 2026: 26 trades, win 62% (needed 60%), Rs 1,253 (Rs 48 a trade). Fails: not positive in O1, P2, P3; does not beat Breadth Donchian on win rate and net in P2 and P3; twin BH q 1.00 > 0.1; < 40 final trades.
- **NET-TOP5 (network's own entries, top 5% of forecasts):** 2023 - Mar 2025: 2,077 trades, win 42% (needed 48%), -Rs 1,19,427 (-Rs 57 a trade); Apr 2025 - Mar 2026: 1,404 trades, win 40% (needed 47%), -Rs 1,60,847 (-Rs 115 a trade); Apr - Oct 2026: 481 trades, win 43% (needed 51%), -Rs 63,048 (-Rs 131 a trade). Fails: not positive in O1, P2, P3; does not beat Breadth Donchian on win rate and net in P2 and P3; twin BH q 1.00 > 0.1.
- **NET-TOP1 (network's own entries, top 1% of forecasts):** 2023 - Mar 2025: 637 trades, win 41% (needed 51%), -Rs 55,693 (-Rs 87 a trade); Apr 2025 - Mar 2026: 505 trades, win 41% (needed 51%), -Rs 81,468 (-Rs 161 a trade); Apr - Oct 2026: 89 trades, win 40% (needed 41%), -Rs 1,667 (-Rs 19 a trade). Fails: not positive in O1, P2, P3; does not beat Breadth Donchian on win rate and net in P2 and P3; twin BH q 1.00 > 0.1.
- **PICK (Breadth Donchian's entries the network likes (forecast net > 0)):** Apr 2025 - Mar 2026: 273 trades, win 51% (needed 49%), Rs 12,657 (Rs 46 a trade); Apr - Oct 2026: 173 trades, win 32% (needed 51%), -Rs 60,753 (-Rs 351 a trade). Fails: not positive in P2, P3; does not beat Breadth Donchian on win rate and net in P2 and P3; twin BH q 1.00 > 0.1.
- **PICK50 (Breadth Donchian's entries in the network's better half):** Apr 2025 - Mar 2026: 1,163 trades, win 47% (needed 46%), Rs 9,710 (Rs 8 a trade); Apr - Oct 2026: 505 trades, win 43% (needed 41%), Rs 21,183 (Rs 42 a trade). Fails: does not beat Breadth Donchian on win rate and net in P2 and P3; twin BH q 1.00 > 0.1.
- **Above Breadth Donchian on both win rate and net in the final period:** none; in both the walk-forward year and the final: none.
- **Can the network forecast this exit's result?** Score (0.5 = coin): 0.5236 / 0.5084 / 0.5082 (O1 / P2 / P3). An average moment bought with this exit: win 42%, needed 49%, -Rs 165 a trade (final period); its top tenth by forecast net: win 41%, needed 48%, -Rs 99 a trade.
- **What this shows about Breadth Donchian:** its exit alone is not the edge. Bought at an average moment with the same exit, the option loses -Rs 165 a trade; bought on Breadth Donchian's break it makes Rs 143. The edge is the entry (4 of 5 indices breaking together), and the network, which can barely forecast this exit's result, throws that edge away when it is asked to choose.
- **What it leans on:** support / resistance 63%; candle wicks 19%; OI 11%; VIX, scale, day so far, premium 7%.
- Nothing changes in the app. Every rule is logged in `research/experiments/entries/r158-*.yaml`.

Signal source: the index and the other four indices (1-minute bars), index-derived context; option-chain OI and India VIX as context; the 1-ITM option is bought and managed with Breadth Donchian's premium exits. **Breadth Donchian's own trades in Apr - Oct 2026 were read by R72 - R153: weak evidence.**

## How it was tested

Pre-registered in `PREREG.md` before any label or model. Examples: R156 / R157's 733,260 moments (every 5-minute close 09:19 - 14:44, both sides, 5 indices, Aug 2021 - 13 Oct 2026), expiry days without a label (the arm never trades them). Labels are R106's engine with exits XL, equal to the arm's own pricing (tested). Network: R157's (13 sequence channels + the 43-number table), one output pair, 3 seeds averaged, expanding walk-forward (2023, 2024, 2025, Jan - Mar 2026, final). Standalone rules use R72's taking rule (one open a side, 2 fills a side a day per index). R72's own results reproduce first: design 3,071 trades / Rs 2,16,929, final 1,202 / Rs 1,71,366. Gates: net > 0 in P2 and P3 (standalone also O1); a higher win rate AND a higher net than R72 in P2 and P3; twins (random entries for standalone rules, random subsets of R72's trades for the picks) with BH q <= 0.10 (m = 2,132); >= 40 final trades.

## Every rule against Breadth Donchian (1 lot)

Cells: trades / win / net / a trade / worst day / max drawdown / net without the 5 best trades.

| rule | O1 (2023 - Mar 2025) | P2 (Apr 2025 - Mar 2026) | P3 final (Apr - 13 Oct 2026) | twins' mean | p | q |
|---|---|---|---|---|---|---|
| **Breadth Donchian (B0)** | (arm starts 20 Nov 2024) | 2,238 / 49% / Rs 3,35,882 / Rs 150 / -Rs 25,837 / -Rs 86,529 / Rs 2,79,428 | 1,202 / 46% / Rs 1,71,366 / Rs 143 / -Rs 22,571 / -Rs 61,884 / Rs 1,12,623 | | | |
| NET-EV | 256 / 39% / -Rs 32,231 / -Rs 126 / -Rs 2,251 / -Rs 32,231 / -Rs 37,849 | 34 / 59% / Rs 838 / Rs 25 / -Rs 8,881 / -Rs 8,881 / -Rs 12,290 | 26 / 62% / Rs 1,253 / Rs 48 / -Rs 5,145 / -Rs 6,903 / -Rs 9,800 | -Rs 3,591 | 0.3303 | 1.0 |
| NET-TOP5 | 2,077 / 42% / -Rs 1,19,427 / -Rs 57 / -Rs 7,662 / -Rs 1,32,990 / -Rs 1,46,478 | 1,404 / 40% / -Rs 1,60,847 / -Rs 115 / -Rs 11,504 / -Rs 1,91,660 / -Rs 1,99,107 | 481 / 43% / -Rs 63,048 / -Rs 131 / -Rs 11,730 / -Rs 63,048 / -Rs 85,977 | -Rs 66,328 | 0.5067 | 1.0 |
| NET-TOP1 | 637 / 41% / -Rs 55,693 / -Rs 87 / -Rs 4,174 / -Rs 62,733 / -Rs 68,696 | 505 / 41% / -Rs 81,468 / -Rs 161 / -Rs 10,440 / -Rs 91,195 / -Rs 1,03,510 | 89 / 40% / -Rs 1,667 / -Rs 19 / -Rs 3,890 / -Rs 15,484 / -Rs 24,178 | -Rs 14,883 | 0.2189 | 1.0 |
| PICK | - | 273 / 51% / Rs 12,657 / Rs 46 / -Rs 16,650 / -Rs 28,790 / -Rs 7,048 | 173 / 32% / -Rs 60,753 / -Rs 351 / -Rs 7,762 / -Rs 61,273 / -Rs 75,748 | Rs 24,611 | 1.0 | 1.0 |
| PICK50 | - | 1,163 / 47% / Rs 9,710 / Rs 8 / -Rs 16,650 / -Rs 69,621 / -Rs 22,889 | 505 / 43% / Rs 21,183 / Rs 42 / -Rs 9,411 / -Rs 66,586 / -Rs 14,454 | Rs 72,157 | 0.9225 | 1.0 |

## The network's own entries: where they are (final period)

| rule | by index (trades / net) | calls | by hour (trades / net) | also Breadth Donchian entries |
|---|---|---|---|---|
| NET-EV | NIFTY 2 / Rs 1,171; BANKNIFTY 16 / -Rs 7,574; FINNIFTY 1 / Rs 1,542; MIDCPNIFTY 4 / Rs 4,217; SENSEX 3 / Rs 1,897 | 92% | 10h 9 / -Rs 5,745; 11h 7 / Rs 9,731; 12h 4 / -Rs 1,822; 13h 5 / -Rs 2,380; 14h 1 / Rs 1,469 | 0 of 26 |
| NET-TOP5 | NIFTY 110 / -Rs 27,280; BANKNIFTY 111 / -Rs 12,286; FINNIFTY 84 / -Rs 5,239; MIDCPNIFTY 20 / -Rs 7,443; SENSEX 156 / -Rs 10,800 | 67% | 9h 4 / Rs 3,123; 10h 180 / -Rs 24,632; 11h 183 / -Rs 21,063; 12h 74 / -Rs 22,535; 13h 29 / -Rs 2,453; 14h 11 / Rs 4,513 | 4 of 481 |
| NET-TOP1 | NIFTY 12 / -Rs 4,845; BANKNIFTY 30 / -Rs 946; FINNIFTY 17 / -Rs 1,121; MIDCPNIFTY 9 / -Rs 661; SENSEX 21 / Rs 5,905 | 81% | 10h 29 / -Rs 8,746; 11h 40 / Rs 2,816; 12h 13 / -Rs 3,213; 13h 5 / Rs 816; 14h 2 / Rs 6,660 | 0 of 89 |

Breadth Donchian by index, final: NIFTY 236 / Rs 9,850; BANKNIFTY 264 / Rs 55,106; FINNIFTY 249 / Rs 69,469; MIDCPNIFTY 218 / Rs 24,247; SENSEX 235 / Rs 12,694.

## The forecast in tenths (by forecast net), every labelled moment

**P2 (Apr 2025 - Mar 2026)** (score 0.5084):

| tenth (1 = lowest) | moments | real win | win needed | net a trade |
|---|---|---|---|---|
| 1 | 14,309 | 45% | 48% | -Rs 141 |
| 2 | 14,309 | 42% | 48% | -Rs 181 |
| 3 | 14,308 | 43% | 48% | -Rs 154 |
| 4 | 14,309 | 42% | 48% | -Rs 164 |
| 5 | 14,308 | 43% | 48% | -Rs 106 |
| 6 | 14,309 | 43% | 48% | -Rs 98 |
| 7 | 14,309 | 41% | 48% | -Rs 132 |
| 8 | 14,308 | 41% | 49% | -Rs 136 |
| 9 | 14,309 | 42% | 49% | -Rs 121 |
| 10 | 14,309 | 43% | 50% | -Rs 115 |

**P3 final (Apr - 13 Oct 2026)** (score 0.5082):

| tenth (1 = lowest) | moments | real win | win needed | net a trade |
|---|---|---|---|---|
| 1 | 7,621 | 40% | 52% | -Rs 399 |
| 2 | 7,621 | 42% | 49% | -Rs 215 |
| 3 | 7,621 | 44% | 49% | -Rs 156 |
| 4 | 7,621 | 41% | 48% | -Rs 164 |
| 5 | 7,621 | 43% | 48% | -Rs 118 |
| 6 | 7,621 | 41% | 47% | -Rs 136 |
| 7 | 7,621 | 42% | 48% | -Rs 126 |
| 8 | 7,621 | 42% | 47% | -Rs 93 |
| 9 | 7,621 | 40% | 49% | -Rs 140 |
| 10 | 7,621 | 41% | 48% | -Rs 99 |

## What the network leans on (shuffle test, Apr - Dec 2025)

| input group | loss increase | share |
|---|---|---|
| support / resistance | 0.00242 | 63% |
| candle wicks | 0.00072 | 19% |
| OI | 0.00042 | 11% |
| VIX, scale, day so far, premium | 0.00027 | 7% |
| time and calendar | 2e-05 | 1% |
| trend (EMA, Supertrend, average price) | -0.00013 | 0% |
| index and side | -0.00037 | 0% |
| the traded index's last hour of 1-minute bars | -0.00265 | 0% |
| the other four indices' last hour | -0.00274 | 0% |

## Post window (14-19 Oct 2026, never gated)

NET-EV: 1 trades, win 0% (needed 100%), -Rs 1,781 (-Rs 1,781 a trade); NET-TOP5: 5 trades, win 40% (needed 76%), -Rs 2,937 (-Rs 587 a trade); NET-TOP1: 2 trades, win 0% (needed 100%), -Rs 2,788 (-Rs 1,394 a trade).
