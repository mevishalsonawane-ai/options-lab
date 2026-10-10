## Liquidity 15+5: which exits? Current exits vs 30/60, 40/80, 40/40 and the profit-lock ladder (research/liquidity_exits_3060.py)

### Verdict

**Keep the current exits.** On real Dhan option prices, Aug 2021 - 5 Oct 2026, after the app's charges and fills, the arm's own exits make **Rs +46,948 a year per lot** (Rs +242,421 in all, 1651 trades), max drawdown **Rs -37,402**, positive in **4 of 6 years**. None of the 12 other exit sets beats it after costs in most years with a drawdown no worse, and the year-by-year walk-forward did not beat it. The best alternative, E80, made Rs +12,382 a year (drawdown Rs -42,162). Walk-forward 2023-2026 (pick the best on earlier years, trade it the next): Rs +202,109 (drawdown Rs -29,990) against Rs +257,203 (drawdown Rs -29,408) for the current exits. Why: the arm earns on a minority of breaks that run on to the next liquidity level; a fixed premium target or a ladder sells those early, and a fixed point stop is much tighter than the -15% stop on BANKNIFTY and looser on FINNIFTY.

**Is 30/60 workable for Liquidity?** No: with the 30-point stop and +60 target (and its ladder) in place of the -15% stop and the next-level target, the arm makes Rs +1,169 a year keeping the index and time stops (B1), Rs -420 dropping them (B2) and Rs +1,032 as Jarvis's bare rule (B3), against Rs +46,948 now. Points are the wrong unit for this arm: 30 points is about 7% of the BANKNIFTY option it buys but about 16% of the FINNIFTY one (median entry premiums Rs 444 / Rs 192; the -15% stop is about 67 / 29 points), so the same rule is half the arm's stop on BANKNIFTY and wider than it on FINNIFTY; on NIFTY (one-ITM weekly about Rs 125-145) 30 / 60 points would be about 20% / 40% of the premium.

Tried: 13 exit sets in all (A + 12 alternatives: B1-B3, C1-C3, D1-D3, E on 60/40/80), fixed before any result was read; no parameter was tuned. With that many tries, one alternative beating A in a single year means little.

Reproduces the arm's known backtest: Kotlin replay (wf_room1_itm1): 1651 trades, net Rs 242,420.76. This port: 1651 trades, net Rs 242,420.76. Matched by book/day/entry minute: 1651; identical net (to the paisa): 1651; only in Kotlin: 0; only here: 0.

### Set-up

- Entries: the app's Liquidity 15+5 as it runs now (commit 1bd37ad): BANKNIFTY 15-min + 5-min, FINNIFTY 30-min + 5-min, one position per book, entries 09:20-14:00, room filter (>= 1 index stop to the next level), one strike in the money, expiry days skipped, days with a real index OHLC only (as the liq / liq2 replays). 1 lot of the day's size (BANKNIFTY 25/15/30, FINNIFTY 40/25/65 over the years).
- Prices: real option minute bars; MARKET fills +-5 bps on the price, resting stops -10 bps (the app's paper account); the app's F&O charges per leg (SandboxCosts).
- Within a minute: premium stop first, then the profit lock, then the premium target (fills: stop at the trigger or the open if it gapped; lock at the rung or the open, MARKET; target at entry + target or the open, MARKET); then 15:10, the index stop, the 20-min time stop, the next-level target, the failed break and the new level (MARKET at the next minute's open).
- Ladder (ProfitLock.LADDER on a reference R): from +R/4 the stop moves to breakeven after charges, from +R/2 to +R/4, from +3R/4 to +R/2. For R = 60: +15 -> breakeven, +30 -> lock +15, +45 -> lock +30 (JarvisTrades). R = 40 is the retired ORB arms' (+10 / +20 / +30). A point stop that would sit at or below 0.05 is not placed (cheap options).
- 'Keep index + time stop' (x1) keeps the arm's 30/15-point index stop and the 20-min +5% time stop; 'drop' (x2) removes them; both keep the failed break, the new level and 15:10. x3 is the stop / target / ladder alone with 15:10 (how Jarvis's own trades and the retired ORB arms exit).

### All variants, Aug 2021 - Oct 2026 (1007 trading days with a signal-ready real index; per year = net / 5.16 years)

| variant | trades | win | avg Rs / trade | net Rs / year | net total | PF | max DD | worst month | longest losing run | years + | years better than A | BANKNIFTY | FINNIFTY |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A  current: -15% stop, index stop, 20-min time stop, next level, failed break, new level, 15:10 | 1651 | 36% | +147 | **+46,948** | +242,421 | 1.33 | -37,402 | -12,850 (2024-07) | 14 | 4/6 | - | +146,811 | +95,610 |
| B1 30/60 + ladder60, keep index + time stop | 1651 | 35% | +4 | **+1,169** | +6,034 | 1.01 | -40,644 | -8,120 (2026-06) | 17 | 1/6 | 1 of 6 | -33,836 | +39,870 |
| B2 30/60 + ladder60, drop index + time stop | 1651 | 34% | -1 | **-420** | -2,167 | 1.00 | -48,365 | -14,228 (2026-06) | 17 | 1/6 | 1 of 6 | -33,473 | +31,306 |
| B3 30/60 + ladder60 only (Jarvis's rule as is: + 15:10) | 1612 | 35% | +3 | **+1,032** | +5,330 | 1.01 | -59,486 | -17,883 (2026-06) | 17 | 1/6 | 1 of 6 | -40,971 | +46,301 |
| C1 40/80 + ladder80, keep index + time stop | 1651 | 35% | +16 | **+5,178** | +26,739 | 1.05 | -39,341 | -12,843 (2026-05) | 26 | 2/6 | 1 of 6 | -29,520 | +56,259 |
| C2 40/80 + ladder80, drop index + time stop | 1650 | 35% | +17 | **+5,534** | +28,575 | 1.05 | -40,321 | -11,026 (2026-06) | 26 | 3/6 | 1 of 6 | -33,953 | +62,528 |
| C3 40/80 + ladder80 only (+ 15:10) | 1579 | 35% | +35 | **+10,593** | +54,700 | 1.07 | -42,393 | -23,421 (2025-12) | 17 | 3/6 | 2 of 6 | -44,818 | +99,518 |
| D1 40/40 + ORB ladder40, keep index + time stop | 1652 | 37% | -17 | **-5,481** | -28,300 | 0.93 | -70,937 | -8,810 (2026-06) | 18 | 1/6 | 1 of 6 | -51,419 | +23,119 |
| D2 40/40 + ORB ladder40, drop index + time stop | 1652 | 37% | -31 | **-10,062** | -51,956 | 0.89 | -86,194 | -12,557 (2026-06) | 18 | 1/6 | 1 of 6 | -53,775 | +1,819 |
| D3 40/40 + ORB ladder40 only (the retired ORB arms' exits) | 1621 | 42% | -23 | **-7,289** | -37,636 | 0.93 | -74,991 | -13,362 (2025-12) | 14 | 2/6 | 1 of 6 | -57,620 | +19,984 |
| E  current + ladder on 60 (+15 BE, +30 lock 15, +45 lock 30) | 1651 | 36% | +14 | **+4,448** | +22,969 | 1.05 | -59,277 | -12,731 (2026-06) | 17 | 2/6 | 1 of 6 | -9,442 | +32,411 |
| E' current + ladder on 40 (+10 BE, +20 lock 10, +30 lock 20) | 1652 | 37% | +14 | **+4,402** | +22,729 | 1.05 | -45,606 | -10,979 (2026-06) | 18 | 3/6 | 2 of 6 | +594 | +22,135 |
| E'' current + ladder on 80 (+20 BE, +40 lock 20, +60 lock 40) | 1651 | 36% | +39 | **+12,382** | +63,934 | 1.12 | -42,162 | -13,398 (2026-05) | 26 | 3/6 | 1 of 6 | +10,716 | +53,218 |

### Net by year (Rs, 1 lot, after costs)

| variant | 2021 (Aug-Dec) | 2022 | 2023 | 2024 | 2025 | 2026 (to 5 Oct) |
|---|---|---|---|---|---|---|
| A | -9,474 | -5,309 | +19,384 | +44,444 | +105,870 | +87,504 |
| B1 | **-5,167** | -7,779 | -12,786 | -2,844 | +39,046 | -4,435 |
| B2 | **-5,091** | -6,395 | -11,344 | -10,557 | +42,581 | -11,361 |
| B3 | **-6,282** | -5,774 | -7,706 | -5,477 | +40,709 | -10,141 |
| C1 | **-4,795** | -8,364 | -4,207 | -2,188 | +42,355 | +3,938 |
| C2 | **-4,376** | -9,195 | +3,200 | -11,318 | +48,032 | +2,231 |
| C3 | **-3,669** | **-1,245** | +14,189 | -5,454 | +23,306 | +27,573 |
| D1 | **-5,345** | -15,677 | -21,867 | -20,135 | +49,193 | -14,470 |
| D2 | **-4,586** | -19,698 | -27,611 | -26,072 | +44,797 | -18,787 |
| D3 | **-2,717** | -19,329 | -17,210 | -20,706 | +20,918 | +1,408 |
| E60 | **-8,879** | -7,520 | -23,721 | -7,075 | +54,951 | +15,212 |
| E40 | **-9,296** | **+2,486** | -25,560 | -1,949 | +47,555 | +9,492 |
| E80 | **-7,990** | -14,840 | +3,621 | -11,434 | +54,669 | +39,908 |

Bold: better than A that year.

### Per book (net Rs, all years)

| variant | BN 15-min | BN 5-min | FIN 30-min | FIN 5-min |
|---|---|---|---|---|
| A | +46,754 | +100,057 | +10,599 | +85,011 |
| B1 | -9,571 | -24,265 | +8,691 | +31,179 |
| B2 | -6,236 | -27,237 | +7,606 | +23,700 |
| B3 | -8,524 | -32,447 | +6,237 | +40,064 |
| C1 | -407 | -29,113 | +12,186 | +44,073 |
| C2 | +2,727 | -36,680 | +13,003 | +49,524 |
| C3 | +2,339 | -47,158 | +5,980 | +93,539 |
| D1 | -1,575 | -49,844 | +7,194 | +15,925 |
| D2 | -2,263 | -51,512 | -4,855 | +6,674 |
| D3 | -3,171 | -54,450 | -5,618 | +25,602 |
| E60 | -5,097 | -4,346 | +13 | +32,399 |
| E40 | +9,362 | -8,768 | -2,408 | +24,543 |
| E80 | +904 | +9,812 | +6,635 | +46,583 |

### How the trades end (share of trades)

| variant | exits |
|---|---|
| A | time_stop 24%, next_liquidity 20%, index_stop 20%, failed_break 16%, premium_stop 11%, new_liquidity 5%, session_end 4% |
| B1 | profit_lock 33%, premium_stop 16%, failed_break 11%, next_liquidity 11%, index_stop 10%, time_stop 10%, premium_target 8%, new_liquidity 0% |
| B2 | profit_lock 37%, failed_break 22%, premium_stop 20%, next_liquidity 12%, premium_target 9%, new_liquidity 0%, session_end 0% |
| B3 | profit_lock 51%, premium_stop 34%, premium_target 14%, session_end 1% |
| C1 | profit_lock 29%, index_stop 14%, next_liquidity 14%, failed_break 14%, time_stop 13%, premium_stop 11%, premium_target 6%, new_liquidity 0%, session_end 0% |
| C2 | profit_lock 32%, failed_break 29%, next_liquidity 16%, premium_stop 15%, premium_target 6%, new_liquidity 1%, session_end 0% |
| C3 | profit_lock 53%, premium_stop 31%, premium_target 11%, session_end 5% |
| D1 | profit_lock 43%, premium_target 12%, index_stop 11%, failed_break 10%, premium_stop 8%, time_stop 8%, next_liquidity 8%, new_liquidity 0% |
| D2 | profit_lock 47%, failed_break 22%, premium_target 12%, premium_stop 11%, next_liquidity 8%, new_liquidity 0% |
| D3 | profit_lock 61%, premium_stop 21%, premium_target 16%, session_end 2% |
| E60 | profit_lock 40%, index_stop 15%, next_liquidity 14%, failed_break 13%, time_stop 12%, premium_stop 6%, new_liquidity 1%, session_end 0% |
| E40 | profit_lock 51%, index_stop 13%, next_liquidity 11%, failed_break 11%, time_stop 8%, premium_stop 6%, new_liquidity 1%, session_end 0% |
| E80 | profit_lock 33%, index_stop 16%, next_liquidity 16%, time_stop 14%, failed_break 14%, premium_stop 7%, session_end 1%, new_liquidity 1% |

### Walk-forward (anchored, yearly)

Each year, the variant with the highest net over ALL earlier years (from Aug 2021) is traded in that year; the first two calendar years only train.

| test year | picked | its net on earlier years | A on earlier years | picked, test year | A, test year |
|---|---|---|---|---|---|
| 2023 | C3 | -4,914 | -14,782 | +14,189 | +19,384 |
| 2024 | C3 | +9,275 | +4,602 | -5,454 | +44,444 |
| 2025 | A | +49,046 | +49,046 | +105,870 | +105,870 |
| 2026 | A | +154,916 | +154,916 | +87,504 | +87,504 |

Walk-forward total over 2023-2026: Rs +202,109 (max DD Rs -29,990, PF 1.33) vs A Rs +257,203 (max DD Rs -29,408, PF 1.43).

With A taken off the menu (does the best alternative, chosen on the past, hold up?): 2023: C3 +14,189 vs A +19,384; 2024: C3 -5,454 vs A +44,444; 2025: C3 +23,306 vs A +105,870; 2026: C3 +27,573 vs A +87,504 -> total +59,614 vs A +257,203.

### Windows the arm's rules were not tuned on

The arm's current exits were chosen on 13 Feb 2024 - 23 Feb 2026 (research/LIQUIDITY_*.md), which flatters A inside that window. Outside it:

| variant | before 13 Feb 2024: trades | net | after 23 Feb 2026: trades | net |
|---|---|---|---|---|
| A | 609 | +3,156 | 238 | +40,706 |
| B1 | 609 | -23,249 | 238 | -9,485 |
| B2 | 609 | -22,511 | 238 | -16,291 |
| B3 | 596 | -21,415 | 233 | -32,267 |
| C1 | 609 | -16,604 | 238 | -2,025 |
| C2 | 608 | -9,240 | 238 | -4,652 |
| C3 | 585 | +7,201 | 229 | -10,159 |
| D1 | 609 | -46,876 | 238 | -9,087 |
| D2 | 609 | -58,022 | 238 | -12,851 |
| D3 | 598 | -46,906 | 234 | -18,646 |
| E60 | 609 | -44,039 | 238 | -9,291 |
| E40 | 609 | -38,827 | 238 | -9,708 |
| E80 | 609 | -22,130 | 238 | +15,138 |

### What the point rules mean in % of the premium

Median price paid by the arm (one strike ITM, at its entries):

| index | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 | all | 30 pts | 40 pts | 60 pts | 80 pts | -15% stop in points |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | Rs 379 | Rs 405 | Rs 298 | Rs 409 | Rs 653 | Rs 744 | Rs 444 | 7% | 9% | 14% | 18% | 67 pts |
| FINNIFTY | - | Rs 135 | Rs 126 | Rs 171 | Rs 314 | Rs 321 | Rs 192 | 16% | 21% | 31% | 42% | 29 pts |

For comparison, the median one-strike-ITM weekly (else monthly) option at 11:00 on Dhan's data (NIFTY and SENSEX are not traded by this arm):

| index | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 | 30 pts on the latest | 60 pts on the latest |
|---|---|---|---|---|---|---|---|---|
| NIFTY | Rs 114 | Rs 129 | Rs 97 | Rs 137 | Rs 124 | Rs 146 | 21% | 41% |
| BANKNIFTY | Rs 317 | Rs 336 | Rs 261 | Rs 349 | Rs 606 | Rs 744 | 4% | 8% |
| FINNIFTY | Rs 131 | Rs 153 | Rs 115 | Rs 148 | Rs 293 | Rs 358 | 8% | 17% |
| SENSEX | - | - | Rs 300 | Rs 427 | Rs 386 | Rs 429 | 7% | 14% |

### Reading it

- A (the arm now): Rs +242,421 over the sample, Rs +46,948 a year, PF 1.33, max DD Rs -37,402, worst month Rs -12,850, 4/6 years positive.
- B (30/60): B1 Rs +6,034 (DD -40,644, better than A in 1/6 years); B2 Rs -2,167 (DD -48,365, better than A in 1/6 years); B3 Rs +5,330 (DD -59,486, better than A in 1/6 years).
- C (40/80): C1 Rs +26,739 (DD -39,341, better than A in 1/6 years); C2 Rs +28,575 (DD -40,321, better than A in 1/6 years); C3 Rs +54,700 (DD -42,393, better than A in 2/6 years).
- D (40/40 ORB): D1 Rs -28,300 (DD -70,937, better than A in 1/6 years); D2 Rs -51,956 (DD -86,194, better than A in 1/6 years); D3 Rs -37,636 (DD -74,991, better than A in 1/6 years).
- E (ladder on top): E60 Rs +22,969 (DD -59,277, better than A in 1/6 years); E40 Rs +22,729 (DD -45,606, better than A in 2/6 years); E80 Rs +63,934 (DD -42,162, better than A in 1/6 years).

## POST-HOC (added at Boss's request, 07 Oct): no stop loss, no target - hold to the liquidity level (research/liquidity_nostop.py)

Not part of the pre-set comparison above; these were run after its results were known. Every stop is removed (no -15% premium stop, no index stop, no 20-minute time stop) and there is no premium target. 15:10 square-off stays (an intraday option arm must be flat by then).

'Wait till the next liquidity' has two readings in LiquidityRules:
- **next liquidity** (`target`, exit `next_liquidity`): the nearest level that ALREADY exists beyond the entry; out when the index touches it. With no level ahead, the trade holds to 15:10. -> **N1**
- **new liquidity** (exit `new_liquidity`): a NEW swing or pool level forming on the trade's side after the entry. -> **N2**
They differ, so both are run, plus N12 (whichever comes first) and **N3** = N1 + the failed break (bar closes back through the broken level).

### (a) as the app would run it: one position per book, so longer holds skip later breaks

| variant | trades | win | Rs / trade | Rs / year / lot | max DD | worst trade (Rs, % of premium) | worst trade % | trades losing >30% / >50% of premium | worst day | worst month | longest losing run | years + | BANKNIFTY | FINNIFTY | avg hold |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A current exits | 1651 | 36% | +147 | **+46,948** | -37,402 | -4,753 (-15%) | -23% | 0.0% / 0.0% | -7,000 (2026-04-15) | -12,850 (2024-07) | 14 | 4/6 | +146,811 | +95,610 | 29 min |
| N1 next liquidity only (+15:10) | 1511 | 58% | +41 | **+12,015** | -101,607 | -12,067 (-39%) | -100% | 14.1% / 5.9% | -33,466 (2026-06-03) | -63,461 (2025-12) | 9 | 3/6 | -29,251 | +91,294 | 173 min |
| N2 new liquidity only (+15:10) | 1473 | 41% | +105 | **+29,869** | -103,023 | -12,573 (-40%) | -100% | 11.3% / 3.4% | -25,829 (2026-06-03) | -42,546 (2025-12) | 18 | 4/6 | +43,444 | +110,786 | 151 min |
| N12 next or new liquidity (+15:10) | 1623 | 53% | +117 | **+36,654** | -53,688 | -12,573 (-40%) | -100% | 8.7% / 2.6% | -25,829 (2026-06-03) | -33,166 (2025-12) | 9 | 4/6 | +86,653 | +102,612 | 101 min |
| N3 next liquidity + failed break (+15:10) | 1619 | 38% | +169 | **+53,077** | -48,948 | -7,923 (-42%) | -72% | 3.6% / 0.4% | -22,214 (2025-08-18) | -27,991 (2024-07) | 14 | 5/6 | +147,556 | +126,514 | 71 min |

| per year | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|
| A | -9,474 | -5,309 | +19,384 | +44,444 | +105,870 | +87,504 |
| N1 | -8,320 | -59,317 | +34,340 | +40,145 | -32,709 | +87,903 |
| N2 | -2,878 | -1,978 | +28,596 | +9,849 | +44,588 | +76,054 |
| N12 | -1,480 | -13,569 | +25,898 | +40,451 | +80,724 | +57,243 |
| N3 | -3,760 | +14,503 | +57,126 | +35,873 | +58,154 | +112,173 |

### (b) exactly the current arm's 1,651 entries, each held on its own

| variant | trades | win | Rs / trade | Rs / year / lot | max DD | worst trade (Rs, % of premium) | worst trade % | trades losing >30% / >50% of premium | worst day | worst month | longest losing run | years + | BANKNIFTY | FINNIFTY | avg hold |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A current exits | 1651 | 36% | +147 | **+46,948** | -37,402 | -4,753 (-15%) | -23% | 0.0% / 0.0% | -7,000 (2026-04-15) | -12,850 (2024-07) | 14 | 4/6 | +146,811 | +95,610 | 29 min |
| N1 next liquidity only (+15:10) | 1651 | 58% | +33 | **+10,410** | -108,049 | -12,067 (-39%) | -100% | 13.7% / 5.5% | -33,466 (2026-06-03) | -53,891 (2025-12) | 9 | 3/6 | -12,120 | +65,872 | 167 min |
| N2 new liquidity only (+15:10) | 1651 | 41% | +112 | **+35,827** | -126,081 | -12,573 (-40%) | -100% | 10.8% / 3.1% | -25,829 (2026-06-03) | -49,508 (2025-12) | 19 | 5/6 | +34,958 | +150,037 | 148 min |
| N12 next or new liquidity (+15:10) | 1651 | 53% | +104 | **+33,130** | -55,789 | -12,573 (-40%) | -100% | 8.7% / 2.5% | -25,829 (2026-06-03) | -33,166 (2025-12) | 10 | 4/6 | +71,253 | +99,816 | 100 min |
| N3 next liquidity + failed break (+15:10) | 1651 | 38% | +170 | **+54,470** | -54,576 | -7,923 (-42%) | -72% | 3.6% / 0.4% | -22,214 (2025-08-18) | -27,991 (2024-07) | 14 | 5/6 | +160,924 | +120,338 | 70 min |

| per year | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|
| A | -9,474 | -5,309 | +19,384 | +44,444 | +105,870 | +87,504 |
| N1 | -4,502 | -53,317 | +32,491 | +33,454 | -47,828 | +93,454 |
| N2 | +2,379 | -4,094 | +30,799 | +12,591 | +58,272 | +85,048 |
| N12 | -5,240 | -13,569 | +23,584 | +34,495 | +74,861 | +56,939 |
| N3 | -5,847 | +15,049 | +63,484 | +34,517 | +54,217 | +119,842 |

How they end: N1: session_end 57%, next_liquidity 43%; N2: new_liquidity 67%, session_end 33%; N12: new_liquidity 45%, next_liquidity 37%, session_end 18%; N3: failed_break 58%, next_liquidity 27%, session_end 15%.

Notes: 'worst trade %' is the exit price against the price paid; with no stop the only cap on a loss is the premium itself (100%) and the 15:10 square-off. Per year = net / the sample's span in years (as above). Charges and fills as above.

### Reading the post-hoc runs

- **Taking every stop off does not pay on its own.** With "next liquidity" only (N1), net falls to about Rs +10-12k a year (A: +47k). The max drawdown is about 2.7x A's (-102k to -108k vs -37k), and the worst month is -54k to -63k. 57% of trades are still open at 15:10, because the level is often never reached that day.
- **N2 (new level only) and N12 (either level) also lose to A**, in net and in drawdown (-53k to -126k).
- **Worst case without stops.** Single trades lost up to Rs 12.6k per lot (about 40% of the premium). Measured as a share of the premium, the worst exits were 87-100% losses, all at 15:10 on contracts close to expiry. 9-14% of N1/N2 trades lost more than 30% of the premium, and 3-6% lost more than 50%. Under A, no trade lost more than 23%.
- One of these, the FINNIFTY 30-min book's trade on 20 Sep 2022, falls on a Tuesday, when FINNIFTY options expired. The option ended at 0.05, so the data's expiry-day flag probably missed that day.
- **N3 (next level + failed break, no stops) is the only one that out-earns A:** Rs +53-54k a year against +47k. It is better than A in 4 of 6 years (2021, 2022, 2023, 2026). But its max drawdown is worse (-49k to -55k vs -37k), its worst trade is worse (-7.9k vs -4.8k), and its worst day is worse (-22k vs -7k). By Boss's rule (a plan may only lower risk) it does not pass.
- N3 is also post-hoc: it was chosen after the main results were seen, it would need its own walk-forward or paper test, and it is not recommended as a change.
