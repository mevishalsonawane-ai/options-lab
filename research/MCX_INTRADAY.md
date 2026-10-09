# MCX intraday: do any simple rules pay an option buyer with Rs 1 lakh?

Written 9 Oct 2026 by M3. Boss: MCX via Zerodha, Rs 1 lakh, 1 lot, option buying first, mini futures studied.

- Plan written before any P&L: `research/hunt/m3/PREREG.md`, plus two amendments. Both are explained below.
- Code: `research/hunt/m3/`. Tables: `research/hunt/m3/results/`. Data and logs: `scratchpad/hunt/m3/`.
- Nothing committed. `android/` untouched. The Dhan token was never printed or saved.

## Verdict

**NO. None of the 8 rule families makes money reliably on any MCX commodity after costs.**

1. **I tested 90 rule x commodity x instrument cells.** None passed the design gates: net > 0, random p < 0.05,
   BH q < 0.10, 60% green months and 30+ trades.
   - Design SPA p = 0.93. Holdout SPA p = 1.00.
   - Trade-weighted average across all option cells: about -Rs 200 net per trade in design and -Rs 140 in the holdout.
   - Gross is about zero (-Rs 15 design, +Rs 63 holdout). So costs decide everything.
2. **The app's Liquidity 15+5 does not carry over to MCX.**
   - It loses on crude, natural gas, gold (GOLDM) and silver (SILVERM) options in design: -Rs 120 to -Rs 650 net per
     trade.
   - In the holdout crude and natural gas came close to zero (-Rs 4 and -Rs 30 per trade). Gold and silver lost.
3. **The US-open break, Indian-morning break, EIA/storage break, the 60-minute break (h18) and intraday momentum all
   lose.** The 60-minute break trades 5-13 times a day and loses Rs 1,400-3,600 a day per commodity.
4. **One cell is worth watching on paper only: NATURALGAS evening breakout (EVE).** Set the 17:00-19:00 IST range,
   buy the 1-ITM option on the first break up to 22:00, and be flat by 23:15.
   - Design: +Rs 47 a trade, +Rs 41 a day, 6/12 green months, random p 0.09.
   - Holdout: +Rs 93 a trade, +Rs 83 a day, 2/4 green months, random p 0.19.
   - Positive twice, but not significant (BH q 0.53-0.63) and small. Drawdown was Rs 40k in design. **Not a go.**
5. **The stale-quote trap.** GOLDM, SILVERM and COPPER options have no trade in 44-80% of minutes. Dhan fills those
   minutes with the last price.
   - With those stale prices, COPPER and GOLDM looked good (e.g. COPPER LIQ5 +Rs 368 a trade, p 0.00).
   - Priced on minutes with real trades, the edge disappears (COPPER LIQ5 +Rs 259, p 0.15; COPPER 60-min break
     +Rs 2 a trade).
   - I switched to printed minutes only (amendment 2) for everything. Earlier studies need the same check.
6. **Rs 1 lakh carries:**
   - 1 lot of CRUDEOIL, NATURALGAS, GOLDM or SILVERM options (premium Rs 13-35k);
   - 3 lots of CRUDEOILM futures or 8 lots of NATGASMINI futures (Zerodha margin Rs 31.5k / Rs 11.7k).
   - It does **not** carry GOLDM futures (Rs 1.57 lakh margin), SILVERM futures (Rs 2.7 lakh), GOLD options
     (Rs 1.9-2.3 lakh a lot) or SILVER options (Rs 1-1.6 lakh).
7. **Best hours.** The moves are in the US session (18:00-22:00 IST): mean 1-hour move 0.5-0.8% for crude and gas.
   - But a random option bought there loses more than in the Indian morning (-Rs 40 to -330 gross vs about 0).
   - Evening premiums are larger, and the move is priced in.
   - Futures and options volume is highest 19:00-23:00. The morning books are thin and wider.

**Short sample warning.** Option minutes exist only from 5 Aug 2025: 11 design months and 3 holdout months. Crude
and gas lived through a crisis (Mar 2026). One year is not enough to prove anything small. It is enough to show
there is nothing big.

## Honesty notes

- **Holdout = 8 Jul - 8 Oct 2026.** Run once (`work/prints/HOLDOUT_OPENED`), after the design results were logged.
  - NN-CRUDE and STRAD-CRUDE had already looked at crude for Apr-Oct 2026, with different rules.
- **Amendment 1** (the lead's request, ideas from M1): EVE, MOMA, MOMB were added before any of their results were
  seen. Before that, a pipeline test had printed the crude design results of rules 1-5. Nothing was changed.
- **Amendment 2** (printed minutes only) was made **after** I had seen the provisional design table. The positive
  cells were on thin books, which is what made me check.
  - It is a data fix, applied to every cell and to the random baseline alike.
  - Both versions are in `results/` (`*_allrows.csv`). Neither has a design candidate.
- **Spreads (important):**
  - Measured live only for crude options: 0.30% evening (STRAD-CRUDE's 204 snapshots).
  - All option books use 0.29% after 17:00 IST and 0.58% before 17:00, as STRAD-CRUDE pre-registered.
  - The GOLDM, SILVERM, NATURALGAS and COPPER option spreads were **not measured**. The market was closed when the
    data was ready.
  - Snapshot jobs (`snap_mcx.py`, `quote_fut.py`) are scheduled for 9 Oct 09:01-10:22 IST, before the token expires.
    `spreads.py` turns them into `work/spreads.json`. Then run
    `M3_PRINTS=1 python3 analyze.py design` and `... analyze.py holdout --recost`. That re-prices costs only.
  - Sensitivity: at 0.5x or 2x the spread there are still **0 candidates**. The number of net-positive cells (of 77
    with 30+ trades) is 9 at 0.5x, 7 at 1x and 4 at 2x.
- **Futures:** the `spot` series has one price per minute, not OHLC. So futures stops and targets are checked on
  minute closes.
  - Futures spread: max(1 tick, 0.01% of price), crude Rs 2/bbl. GOLDM's live top-of-book was Rs 8 per 10 g, below
    the Rs 15 assumed.
- **Expiries** were found from the jump in the ATM straddle. Option trades skip expiry days. Levels reset at each roll.
- 13% of option exits had no print in the exit minute. They were priced at the next print within 10 minutes, else
  the last print.
- **Variant count: 90 design cells and 85 holdout cells** (5 tiny GOLD/SILVER cells had no holdout trades), plus
  the all-rows and spread-sensitivity re-runs. BH and SPA were done across all cells.

## What was tested (pre-registered, 1 lot, 1-ITM near-month option bought at the next minute's open)

| rule | signal | exits |
|---|---|---|
| LIQ15 / LIQ5 | the app's Liquidity rules unchanged (pool on swing, lookback 20, confirm 10, room filter), 15- and 5-min books from 09:00, entries 09:05-22:30 | option -15%; 1 stop unit back through the level (stop unit = 0.4 x median 15-min range of the last 10 sessions, the indices' ratio); 20-min +5% time stop; next level; failed break; new level; session end |
| USORB | first 15 min after NYMEX 09:00 ET (crude, gas) / COMEX 08:20 ET (gold, silver) / 08:10 ET (copper), DST-exact; break within 2 h | range mid (stop), edge + 1 width (target), 60 min, option -15% |
| INORB | 09:00-09:30 IST range, break by 12:00 | same |
| EVT | crude EIA Wed (official times), gas storage Thu 10:30 ET; 15-min pre-release range, break within 30 min | same, 30 min |
| H60 | 5-min close beyond the previous 60-min high/low (h18) | Liquidity exits, 60-min cap |
| EVE | 17:00-19:00 IST range, break 19:00-22:00 | mid / 1 width / 120 min, flat 23:15 |
| MOMA | sign of prev close -> 19:30 return; buy at 22:30 | 23:30 or session end |
| MOMB | crude EIA days: sign of the first 30 min after release; buy 60 min before close | session end |

- **API (Tue 16:30 ET)** lands at 02:00-03:00 IST, after MCX closes. It cannot be traded on MCX.
- **Mini futures** used the same signals: CRUDEOILM, NATGASMINI, GOLDM and SILVERM, both directions.
- **Costs (Zerodha):** Rs 20 an order, CTT 0.05% (options) / 0.01% (futures) on the sell side, MCX 0.0418% / 0.0021%,
  stamp, SEBI and GST. Plus half the spread on each side.
- **Random baseline:** 200 random entries for each trade, in the same month and within ±30 min of the same time of
  day. Random side, the same exit distances.

## Liquidity 15+5 on MCX (options, both books together, net per lot)

| commodity | design trades/day | design gross/trade | design net/trade | design net/day | holdout trades/day | holdout gross/trade | holdout net/trade | holdout net/day |
|---|---|---|---|---|---|---|---|---|
| CRUDEOIL | 1.4 | -166 | -408 | -583 | 1.4 | +278 | -4 | -6 |
| NATURALGAS | 1.6 | +66 | -119 | -193 | 1.4 | +116 | -30 | -42 |
| GOLDM | 0.6 | -144 | -337 | -199 | 1.7 | +43 | -167 | -280 |
| SILVERM | 0.3 | -422 | -649 | -206 | 1.1 | -110 | -330 | -350 |

- **The Liquidity rules need a cost-to-move ratio that MCX options do not give.**
  - Crude: Rs 230-300 a round trip on a Rs 20-35k premium.
  - The 20-minute time stop cuts most trades before a move.
  - Gross is near zero in design. The holdout's crude gross (+278) is the Jul-Oct trend months, and it still nets
    zero.

## Full tables (net = after Zerodha charges and spread; Rs per lot)


#### Options you can buy, DESIGN Aug 2025 - 7 Jul 2026

| commodity | inst | rule | trades | /day | win | gross/trade | net/trade | gross/day | net/day | max DD | green months | random net/trade | random p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| COPPER | OPT | EVE | 75 | 0.34 | 43% | -591 | -880 | -202 | -301 | 120,618 | 4/12 | -1,283 | 0.33 | 0.81 |
| COPPER | OPT | H60 | 742 | 3.39 | 36% | +358 | +2 | +1,214 | +6 | 175,062 | 5/12 | -334 | 0.05 | 0.56 |
| COPPER | OPT | INORB | 39 | 0.18 | 49% | -247 | -641 | -44 | -114 | 45,441 | 6/11 | -665 | 0.48 | 0.83 |
| COPPER | OPT | LIQ15 | 39 | 0.18 | 36% | -611 | -912 | -109 | -162 | 37,309 | 2/12 | -460 | 0.70 | 0.86 |
| COPPER | OPT | LIQ5 | 72 | 0.33 | 42% | +606 | +259 | +199 | +85 | 28,278 | 4/11 | -451 | 0.15 | 0.73 |
| COPPER | OPT | MOMA | 40 | 0.18 | 35% | -533 | -812 | -97 | -148 | 33,426 | 2/11 | -940 | 0.44 | 0.82 |
| COPPER | OPT | USORB | 65 | 0.30 | 45% | +156 | -126 | +46 | -37 | 22,669 | 4/12 | -319 | 0.36 | 0.82 |
| CRUDEOIL | OPT | EVE | 203 | 0.86 | 41% | -439 | -633 | -376 | -542 | 144,982 | 2/12 | -504 | 0.72 | 0.86 |
| CRUDEOIL | OPT | EVT | 41 | 0.17 | 51% | -178 | -361 | -31 | -62 | 18,424 | 3/12 | -214 | 0.73 | 0.86 |
| CRUDEOIL | OPT | H60 | 2979 | 12.57 | 28% | -35 | -285 | -436 | -3,576 | 849,546 | 0/12 | -297 | 0.30 | 0.80 |
| CRUDEOIL | OPT | INORB | 202 | 0.85 | 35% | -12 | -301 | -10 | -256 | 68,784 | 2/12 | -274 | 0.65 | 0.86 |
| CRUDEOIL | OPT | LIQ15 | 115 | 0.49 | 28% | -326 | -554 | -158 | -269 | 64,165 | 0/12 | -297 | 0.99 | 0.99 |
| CRUDEOIL | OPT | LIQ5 | 224 | 0.95 | 31% | -83 | -332 | -79 | -314 | 80,930 | 1/12 | -310 | 0.58 | 0.86 |
| CRUDEOIL | OPT | MOMA | 208 | 0.88 | 38% | -243 | -435 | -213 | -381 | 90,395 | 1/12 | -545 | 0.28 | 0.80 |
| CRUDEOIL | OPT | MOMB | 39 | 0.16 | 26% | -646 | -839 | -106 | -138 | 32,799 | 1/12 | -633 | 0.69 | 0.86 |
| CRUDEOIL | OPT | USORB | 217 | 0.92 | 42% | -65 | -259 | -59 | -237 | 83,190 | 2/12 | -280 | 0.41 | 0.82 |
| GOLDM | OPT | EVE | 78 | 0.33 | 37% | -146 | -312 | -48 | -103 | 30,759 | 5/12 | -289 | 0.52 | 0.84 |
| GOLDM | OPT | H60 | 1197 | 5.05 | 25% | -67 | -283 | -338 | -1,428 | 340,048 | 0/12 | -257 | 0.73 | 0.86 |
| GOLDM | OPT | INORB | 68 | 0.29 | 38% | +358 | +110 | +103 | +31 | 17,881 | 4/10 | -263 | 0.04 | 0.56 |
| GOLDM | OPT | LIQ15 | 39 | 0.16 | 28% | +5 | -201 | +1 | -33 | 15,330 | 3/9 | -272 | 0.42 | 0.82 |
| GOLDM | OPT | LIQ5 | 101 | 0.43 | 32% | -202 | -390 | -86 | -166 | 40,350 | 1/11 | -273 | 0.80 | 0.90 |
| GOLDM | OPT | MOMA | 88 | 0.37 | 34% | +60 | -96 | +22 | -36 | 18,354 | 3/12 | -83 | 0.49 | 0.83 |
| GOLDM | OPT | USORB | 82 | 0.35 | 38% | -12 | -178 | -4 | -62 | 23,609 | 2/12 | -245 | 0.38 | 0.82 |
| NATURALGAS | OPT | EVE | 197 | 0.87 | 43% | +202 | +47 | +176 | +41 | 39,542 | 6/12 | -226 | 0.09 | 0.63 |
| NATURALGAS | OPT | EVT | 43 | 0.19 | 49% | -70 | -213 | -13 | -41 | 11,632 | 3/12 | -161 | 0.65 | 0.86 |
| NATURALGAS | OPT | H60 | 2922 | 12.93 | 30% | +44 | -144 | +569 | -1,864 | 429,170 | 0/12 | -187 | 0.01 | 0.45 |
| NATURALGAS | OPT | INORB | 199 | 0.88 | 40% | +95 | -122 | +84 | -107 | 26,596 | 3/12 | -209 | 0.04 | 0.56 |
| NATURALGAS | OPT | LIQ15 | 121 | 0.54 | 35% | +136 | -48 | +73 | -26 | 19,774 | 5/12 | -219 | 0.11 | 0.73 |
| NATURALGAS | OPT | LIQ5 | 248 | 1.10 | 34% | +32 | -153 | +35 | -168 | 38,699 | 1/12 | -195 | 0.30 | 0.80 |
| NATURALGAS | OPT | MOMA | 209 | 0.92 | 44% | -14 | -160 | -13 | -148 | 49,792 | 4/12 | -264 | 0.23 | 0.76 |
| NATURALGAS | OPT | USORB | 220 | 0.97 | 40% | -144 | -293 | -141 | -285 | 67,125 | 1/12 | -218 | 0.81 | 0.90 |
| SILVERM | OPT | EVE | 58 | 0.25 | 38% | -589 | -749 | -145 | -184 | 43,442 | 1/10 | -524 | 0.68 | 0.86 |
| SILVERM | OPT | H60 | 686 | 2.91 | 25% | -96 | -319 | -280 | -927 | 243,453 | 0/12 | -331 | 0.45 | 0.82 |
| SILVERM | OPT | INORB | 30 | 0.13 | 40% | +33 | -220 | +4 | -28 | 9,004 | 2/8 | -206 | 0.49 | 0.83 |
| SILVERM | OPT | LIQ15 | 23 | 0.10 | 17% | -665 | -918 | -65 | -89 | 23,292 | 1/6 | -410 | 0.92 | 0.94 |
| SILVERM | OPT | LIQ5 | 52 | 0.22 | 27% | -314 | -530 | -69 | -117 | 29,670 | 1/9 | -377 | 0.68 | 0.86 |
| SILVERM | OPT | MOMA | 55 | 0.23 | 38% | +4 | -179 | +1 | -42 | 22,137 | 2/9 | -598 | 0.22 | 0.76 |
| SILVERM | OPT | USORB | 53 | 0.22 | 25% | -543 | -726 | -122 | -163 | 38,932 | 1/10 | -342 | 0.84 | 0.91 |

#### Mini futures (flagged), DESIGN Aug 2025 - 7 Jul 2026

| commodity | inst | rule | trades | /day | win | gross/trade | net/trade | gross/day | net/day | max DD | green months | random net/trade | random p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CRUDEOILM | FUT | EVE | 218 | 0.92 | 42% | -63 | -136 | -58 | -125 | 33,551 | 3/12 | -76 | 0.91 | 0.94 |
| CRUDEOILM | FUT | EVT | 45 | 0.19 | 27% | -28 | -101 | -5 | -19 | 5,041 | 2/12 | -69 | 0.79 | 0.90 |
| CRUDEOILM | FUT | H60 | 2850 | 12.03 | 18% | -8 | -82 | -94 | -981 | 232,767 | 0/12 | -75 | 0.87 | 0.93 |
| CRUDEOILM | FUT | INORB | 216 | 0.91 | 26% | -16 | -89 | -14 | -81 | 19,691 | 0/12 | -74 | 0.78 | 0.90 |
| CRUDEOILM | FUT | LIQ15 | 125 | 0.53 | 22% | -34 | -107 | -18 | -57 | 15,564 | 1/12 | -81 | 0.70 | 0.86 |
| CRUDEOILM | FUT | LIQ5 | 238 | 1.00 | 24% | +38 | -35 | +38 | -36 | 12,794 | 3/12 | -70 | 0.16 | 0.73 |
| CRUDEOILM | FUT | MOMA | 220 | 0.93 | 38% | +23 | -51 | +21 | -47 | 14,536 | 3/12 | -77 | 0.31 | 0.81 |
| CRUDEOILM | FUT | MOMB | 43 | 0.18 | 26% | -40 | -113 | -7 | -21 | 5,062 | 3/12 | -79 | 0.60 | 0.86 |
| CRUDEOILM | FUT | USORB | 232 | 0.98 | 37% | +1 | -73 | +1 | -71 | 21,882 | 2/12 | -74 | 0.45 | 0.82 |
| GOLDM | FUT | EVE | 192 | 0.81 | 45% | +726 | +313 | +588 | +253 | 76,194 | 7/12 | -407 | 0.01 | 0.45 |
| GOLDM | FUT | H60 | 2465 | 10.40 | 18% | -26 | -444 | -276 | -4,613 | 1,097,471 | 1/12 | -438 | 0.51 | 0.83 |
| GOLDM | FUT | INORB | 151 | 0.64 | 40% | +203 | -204 | +129 | -130 | 51,774 | 3/11 | -397 | 0.19 | 0.73 |
| GOLDM | FUT | LIQ15 | 95 | 0.40 | 24% | -194 | -603 | -78 | -242 | 81,629 | 4/12 | -427 | 0.56 | 0.86 |
| GOLDM | FUT | LIQ5 | 229 | 0.97 | 26% | -65 | -478 | -62 | -462 | 123,523 | 3/12 | -412 | 0.58 | 0.86 |
| GOLDM | FUT | MOMA | 199 | 0.84 | 39% | +93 | -323 | +78 | -271 | 84,548 | 5/12 | -438 | 0.33 | 0.81 |
| GOLDM | FUT | USORB | 197 | 0.83 | 38% | -212 | -627 | -176 | -521 | 130,851 | 1/12 | -404 | 0.88 | 0.93 |
| NATGASMINI | FUT | EVE | 211 | 0.89 | 47% | +112 | +27 | +99 | +24 | 12,970 | 6/12 | -86 | 0.06 | 0.57 |
| NATGASMINI | FUT | EVT | 45 | 0.19 | 16% | -39 | -123 | -7 | -23 | 5,647 | 2/12 | -83 | 0.84 | 0.91 |
| NATGASMINI | FUT | H60 | 2801 | 11.82 | 20% | +13 | -72 | +158 | -846 | 202,689 | 1/12 | -87 | 0.07 | 0.57 |
| NATGASMINI | FUT | INORB | 216 | 0.91 | 39% | +45 | -40 | +41 | -36 | 11,907 | 4/12 | -90 | 0.07 | 0.57 |
| NATGASMINI | FUT | LIQ15 | 138 | 0.58 | 30% | +212 | +128 | +124 | +74 | 6,506 | 5/12 | -81 | 0.01 | 0.45 |
| NATGASMINI | FUT | LIQ5 | 229 | 0.97 | 24% | +50 | -35 | +48 | -34 | 16,457 | 5/12 | -75 | 0.20 | 0.73 |
| NATGASMINI | FUT | MOMA | 221 | 0.93 | 44% | +41 | -44 | +38 | -41 | 19,341 | 4/12 | -86 | 0.28 | 0.80 |
| NATGASMINI | FUT | USORB | 233 | 0.98 | 39% | -17 | -102 | -17 | -101 | 24,241 | 2/12 | -82 | 0.66 | 0.86 |
| SILVERM | FUT | EVE | 138 | 0.58 | 47% | -377 | -685 | -221 | -401 | 161,020 | 3/11 | -321 | 0.67 | 0.86 |
| SILVERM | FUT | H60 | 2123 | 9.00 | 19% | +138 | -188 | +1,238 | -1,690 | 625,362 | 3/12 | -317 | 0.19 | 0.73 |
| SILVERM | FUT | INORB | 114 | 0.48 | 35% | -29 | -316 | -14 | -153 | 43,876 | 2/11 | -224 | 0.61 | 0.86 |
| SILVERM | FUT | LIQ15 | 94 | 0.40 | 26% | +303 | -12 | +121 | -5 | 102,517 | 5/12 | -83 | 0.43 | 0.82 |
| SILVERM | FUT | LIQ5 | 185 | 0.78 | 27% | +199 | -111 | +156 | -87 | 123,503 | 4/12 | -396 | 0.29 | 0.80 |
| SILVERM | FUT | MOMA | 180 | 0.76 | 47% | +16 | -309 | +12 | -236 | 106,234 | 4/12 | -284 | 0.53 | 0.84 |
| SILVERM | FUT | USORB | 162 | 0.69 | 35% | -398 | -711 | -273 | -488 | 119,846 | 4/12 | -287 | 0.93 | 0.94 |

#### Options you can buy, HOLDOUT 8 Jul - 8 Oct 2026 (run once)

| commodity | inst | rule | trades | /day | win | gross/trade | net/trade | gross/day | net/day | max DD | green months | random net/trade | random p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| COPPER | OPT | EVE | 34 | 0.56 | 35% | -557 | -864 | -311 | -482 | 36,643 | 1/4 | -829 | 0.48 | 0.69 |
| COPPER | OPT | H60 | 430 | 7.05 | 34% | +404 | +1 | +2,846 | +7 | 61,615 | 2/4 | -399 | 0.03 | 0.48 |
| COPPER | OPT | INORB | 21 | 0.34 | 29% | -440 | -909 | -152 | -313 | 21,175 | 0/3 | -588 | 0.61 | 0.75 |
| COPPER | OPT | LIQ15 | 20 | 0.33 | 40% | +712 | +294 | +233 | +96 | 12,822 | 2/4 | -511 | 0.15 | 0.53 |
| COPPER | OPT | LIQ5 | 39 | 0.64 | 46% | +578 | +176 | +369 | +113 | 8,981 | 2/4 | -419 | 0.13 | 0.53 |
| COPPER | OPT | MOMA | 27 | 0.44 | 52% | +576 | +278 | +255 | +123 | 7,384 | 1/4 | -494 | 0.17 | 0.53 |
| COPPER | OPT | USORB | 35 | 0.57 | 40% | +62 | -245 | +36 | -140 | 16,124 | 2/4 | -327 | 0.44 | 0.66 |
| CRUDEOIL | OPT | EVE | 60 | 0.91 | 45% | +520 | +303 | +473 | +275 | 21,355 | 3/4 | -319 | 0.10 | 0.53 |
| CRUDEOIL | OPT | EVT | 14 | 0.21 | 71% | +858 | +645 | +182 | +137 | 3,141 | 3/4 | -252 | 0.03 | 0.48 |
| CRUDEOIL | OPT | H60 | 890 | 13.48 | 35% | +115 | -175 | +1,554 | -2,365 | 188,391 | 1/4 | -308 | 0.01 | 0.42 |
| CRUDEOIL | OPT | INORB | 62 | 0.94 | 45% | +251 | -85 | +236 | -80 | 11,972 | 1/4 | -277 | 0.09 | 0.53 |
| CRUDEOIL | OPT | LIQ15 | 35 | 0.53 | 31% | +237 | -55 | +125 | -29 | 16,322 | 2/4 | -350 | 0.15 | 0.53 |
| CRUDEOIL | OPT | LIQ5 | 56 | 0.85 | 45% | +304 | +27 | +258 | +23 | 13,715 | 1/4 | -314 | 0.04 | 0.48 |
| CRUDEOIL | OPT | MOMA | 58 | 0.88 | 43% | +53 | -164 | +47 | -144 | 13,716 | 0/4 | -462 | 0.20 | 0.53 |
| CRUDEOIL | OPT | MOMB | 14 | 0.21 | 43% | +599 | +389 | +127 | +83 | 6,689 | 3/4 | -462 | 0.08 | 0.53 |
| CRUDEOIL | OPT | USORB | 63 | 0.95 | 40% | -299 | -518 | -285 | -494 | 34,119 | 1/4 | -274 | 0.87 | 0.92 |
| GOLDM | OPT | EVE | 50 | 0.77 | 42% | +99 | -64 | +76 | -49 | 21,937 | 1/4 | -307 | 0.27 | 0.63 |
| GOLDM | OPT | H60 | 810 | 12.46 | 29% | +37 | -178 | +460 | -2,214 | 151,970 | 0/4 | -241 | 0.06 | 0.53 |
| GOLDM | OPT | INORB | 58 | 0.89 | 40% | +179 | -67 | +159 | -60 | 15,282 | 2/4 | -229 | 0.17 | 0.53 |
| GOLDM | OPT | LIQ15 | 42 | 0.65 | 36% | +40 | -170 | +26 | -110 | 14,497 | 1/4 | -231 | 0.37 | 0.63 |
| GOLDM | OPT | LIQ5 | 67 | 1.03 | 34% | +45 | -165 | +46 | -170 | 20,441 | 2/4 | -247 | 0.31 | 0.63 |
| GOLDM | OPT | MOMA | 58 | 0.89 | 43% | -81 | -248 | -72 | -221 | 15,031 | 1/4 | -535 | 0.07 | 0.53 |
| GOLDM | OPT | USORB | 60 | 0.92 | 42% | -108 | -276 | -100 | -255 | 16,818 | 1/4 | -273 | 0.49 | 0.69 |
| NATURALGAS | OPT | EVE | 55 | 0.89 | 49% | +215 | +93 | +191 | +83 | 12,531 | 2/4 | -140 | 0.19 | 0.53 |
| NATURALGAS | OPT | EVT | 13 | 0.21 | 54% | +67 | -48 | +14 | -10 | 4,192 | 3/4 | -135 | 0.29 | 0.63 |
| NATURALGAS | OPT | H60 | 821 | 13.24 | 27% | +11 | -138 | +142 | -1,822 | 122,842 | 0/4 | -153 | 0.26 | 0.63 |
| NATURALGAS | OPT | INORB | 61 | 0.98 | 26% | -64 | -233 | -62 | -229 | 14,215 | 0/4 | -158 | 0.94 | 0.95 |
| NATURALGAS | OPT | LIQ15 | 28 | 0.45 | 36% | -125 | -265 | -56 | -120 | 8,012 | 0/4 | -172 | 0.73 | 0.81 |
| NATURALGAS | OPT | LIQ5 | 59 | 0.95 | 27% | +231 | +82 | +220 | +78 | 7,826 | 1/4 | -170 | 0.02 | 0.48 |
| NATURALGAS | OPT | MOMA | 57 | 0.92 | 32% | -107 | -224 | -98 | -206 | 15,736 | 1/4 | -171 | 0.61 | 0.75 |
| NATURALGAS | OPT | USORB | 62 | 1.00 | 44% | +24 | -94 | +24 | -94 | 11,314 | 1/4 | -134 | 0.38 | 0.63 |
| SILVERM | OPT | EVE | 30 | 0.46 | 47% | -184 | -374 | -85 | -172 | 26,179 | 2/4 | -328 | 0.51 | 0.70 |
| SILVERM | OPT | H60 | 409 | 6.29 | 30% | +18 | -238 | +112 | -1,497 | 103,215 | 0/4 | -310 | 0.21 | 0.53 |
| SILVERM | OPT | INORB | 30 | 0.46 | 37% | +191 | -102 | +88 | -47 | 8,371 | 1/4 | -261 | 0.34 | 0.63 |
| SILVERM | OPT | LIQ15 | 23 | 0.35 | 26% | -599 | -820 | -212 | -290 | 19,268 | 0/4 | -354 | 0.84 | 0.90 |
| SILVERM | OPT | LIQ5 | 46 | 0.71 | 41% | +135 | -85 | +96 | -60 | 11,444 | 2/4 | -285 | 0.21 | 0.53 |
| SILVERM | OPT | MOMA | 25 | 0.38 | 36% | -227 | -423 | -87 | -163 | 13,589 | 1/4 | -578 | 0.34 | 0.63 |
| SILVERM | OPT | USORB | 27 | 0.42 | 26% | -464 | -666 | -193 | -277 | 21,476 | 1/4 | -327 | 0.73 | 0.81 |

#### Mini futures (flagged), HOLDOUT 8 Jul - 8 Oct 2026 (run once)

| commodity | inst | rule | trades | /day | win | gross/trade | net/trade | gross/day | net/day | max DD | green months | random net/trade | random p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CRUDEOILM | FUT | EVE | 63 | 0.95 | 48% | +48 | -34 | +46 | -32 | 5,608 | 2/4 | -71 | 0.35 | 0.63 |
| CRUDEOILM | FUT | EVT | 14 | 0.21 | 57% | +159 | +78 | +34 | +16 | 490 | 2/4 | -83 | 0.01 | 0.42 |
| CRUDEOILM | FUT | H60 | 827 | 12.53 | 24% | +19 | -63 | +232 | -789 | 55,480 | 0/4 | -78 | 0.13 | 0.53 |
| CRUDEOILM | FUT | INORB | 65 | 0.98 | 45% | +28 | -53 | +28 | -52 | 3,502 | 0/4 | -81 | 0.13 | 0.53 |
| CRUDEOILM | FUT | LIQ15 | 38 | 0.58 | 29% | +144 | +62 | +83 | +36 | 6,152 | 2/4 | -86 | 0.05 | 0.53 |
| CRUDEOILM | FUT | LIQ5 | 59 | 0.89 | 31% | +26 | -56 | +23 | -50 | 5,720 | 0/4 | -72 | 0.37 | 0.63 |
| CRUDEOILM | FUT | MOMA | 61 | 0.92 | 48% | +52 | -30 | +48 | -27 | 3,505 | 1/4 | -83 | 0.21 | 0.53 |
| CRUDEOILM | FUT | MOMB | 14 | 0.21 | 57% | +115 | +33 | +24 | +7 | 1,235 | 3/4 | -70 | 0.21 | 0.53 |
| CRUDEOILM | FUT | USORB | 66 | 1.00 | 35% | -23 | -105 | -23 | -105 | 7,031 | 0/4 | -83 | 0.66 | 0.75 |
| GOLDM | FUT | EVE | 53 | 0.82 | 45% | +597 | +143 | +486 | +116 | 30,375 | 1/4 | -421 | 0.17 | 0.53 |
| GOLDM | FUT | H60 | 790 | 12.15 | 19% | +6 | -447 | +78 | -5,437 | 367,148 | 0/4 | -515 | 0.21 | 0.53 |
| GOLDM | FUT | INORB | 64 | 0.98 | 33% | -112 | -566 | -111 | -557 | 43,320 | 1/4 | -474 | 0.66 | 0.75 |
| GOLDM | FUT | LIQ15 | 47 | 0.72 | 30% | +879 | +423 | +636 | +306 | 24,614 | 1/4 | -389 | 0.11 | 0.53 |
| GOLDM | FUT | LIQ5 | 73 | 1.12 | 29% | +332 | -124 | +372 | -139 | 39,989 | 2/4 | -475 | 0.21 | 0.53 |
| GOLDM | FUT | MOMA | 60 | 0.92 | 50% | +386 | -69 | +356 | -63 | 18,878 | 3/4 | -450 | 0.14 | 0.53 |
| GOLDM | FUT | USORB | 64 | 0.98 | 38% | -147 | -600 | -144 | -591 | 39,579 | 0/4 | -449 | 0.64 | 0.75 |
| NATGASMINI | FUT | EVE | 56 | 0.86 | 45% | +29 | -54 | +25 | -47 | 6,036 | 2/4 | -79 | 0.35 | 0.63 |
| NATGASMINI | FUT | EVT | 13 | 0.20 | 23% | -4 | -88 | -1 | -18 | 1,693 | 2/4 | -77 | 0.60 | 0.75 |
| NATGASMINI | FUT | H60 | 784 | 12.06 | 20% | -1 | -85 | -13 | -1,024 | 68,082 | 0/4 | -87 | 0.42 | 0.65 |
| NATGASMINI | FUT | INORB | 64 | 0.98 | 23% | -23 | -107 | -23 | -106 | 6,864 | 0/4 | -85 | 0.88 | 0.92 |
| NATGASMINI | FUT | LIQ15 | 35 | 0.54 | 23% | -14 | -98 | -8 | -53 | 3,517 | 1/4 | -97 | 0.51 | 0.70 |
| NATGASMINI | FUT | LIQ5 | 68 | 1.05 | 21% | +77 | -7 | +81 | -7 | 5,884 | 1/4 | -95 | 0.02 | 0.48 |
| NATGASMINI | FUT | MOMA | 58 | 0.89 | 29% | +16 | -68 | +14 | -61 | 4,376 | 0/4 | -83 | 0.41 | 0.64 |
| NATGASMINI | FUT | USORB | 65 | 1.00 | 40% | -7 | -91 | -7 | -91 | 7,183 | 0/4 | -89 | 0.54 | 0.71 |
| SILVERM | FUT | EVE | 56 | 0.86 | 46% | +287 | -78 | +247 | -67 | 41,901 | 2/4 | -431 | 0.33 | 0.63 |
| SILVERM | FUT | H60 | 809 | 12.45 | 18% | -224 | -588 | -2,793 | -7,320 | 503,348 | 0/4 | -465 | 0.91 | 0.93 |
| SILVERM | FUT | INORB | 64 | 0.98 | 41% | +123 | -241 | +121 | -237 | 35,310 | 2/4 | -371 | 0.39 | 0.63 |
| SILVERM | FUT | LIQ15 | 48 | 0.74 | 27% | -259 | -622 | -191 | -460 | 29,873 | 1/4 | -431 | 0.60 | 0.75 |
| SILVERM | FUT | LIQ5 | 78 | 1.20 | 29% | +162 | -201 | +195 | -241 | 27,921 | 1/4 | -390 | 0.28 | 0.63 |
| SILVERM | FUT | MOMA | 61 | 0.94 | 57% | +195 | -169 | +183 | -159 | 21,679 | 2/4 | -365 | 0.31 | 0.63 |
| SILVERM | FUT | USORB | 62 | 0.95 | 35% | -31 | -395 | -29 | -377 | 26,521 | 0/4 | -398 | 0.52 | 0.70 |


GOLD (1 kg), SILVER (30 kg) and NATGASMINI option cells are in `results/cells_*.csv`.
- GOLD and SILVER trade rarely on printed minutes and mostly cost more than Rs 1 lakh.
- NATGASMINI options lose Rs 26-94 a trade in design on every rule, because brokerage is about 2% of the premium.

### Walk-forward by month (design; the rules are fixed, so every month is out-of-sample for its rule)

- **NATURALGAS EVE options**, net Rs by month: Aug -10.2k, Sep +10.8k, Oct +10.4k, Nov +4.7k, Dec +11.5k,
  Jan +15.0k, Feb -9.2k, Mar -8.2k, Apr -7.0k, May +6.1k, Jun -8.2k, Jul (to 7th) -6.4k.
  - Holdout: Jul +11.5k, Aug -6.9k, Sep +8.7k, Oct (to 8th) -8.2k.
  - It worked in the calm winter and failed in the spring crisis.
- **GOLDM EVE futures** (cannot be carried at Rs 1 lakh): Sep +22.8k, Oct +14.4k, Nov -12.0k, Dec -6.5k, Jan +60.6k,
  Feb +20.9k, Mar +11.3k, Apr +15.8k, May -24.1k, Jun -45.3k.
  - Holdout: Jul -18.8k, Aug +43.7k, Sep -14.0k.
  - Scaled to GOLDTEN (1/10 the size), the gross of about Rs 60-70 a trade is below the Rs 74 round-trip cost.
- **COPPER 60-min break, options:** +23k, +48k, +83k (Aug-Oct 2025), then -45k, -53k, -17k (Feb-Jun 2026). The book
  is thin: 77% of minutes have no trade.

## Best hours to trade each commodity


#### Mean absolute 1-hour futures move (%) by IST hour, design period; option volume share in brackets

| commodity | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CRUDEOIL | 0.32 (0%) | 0.21 (1%) | 0.30 (1%) | 0.40 (1%) | 0.43 (2%) | 0.50 (2%) | 0.42 (3%) | 0.45 (5%) | 0.46 (6%) | 0.48 (9%) | 0.54 (11%) | 0.58 (14%) | 0.52 (16%) | 0.51 (18%) | 0.28 (11%) |
| NATURALGAS | 0.46 (0%) | 0.39 (1%) | 0.57 (1%) | 0.44 (1%) | 0.38 (2%) | 0.36 (2%) | 0.43 (3%) | 0.55 (4%) | 0.75 (6%) | 0.83 (9%) | 0.82 (12%) | 0.73 (14%) | 0.75 (16%) | 0.52 (18%) | 0.59 (11%) |
| GOLDM | 0.27 (1%) | 0.16 (1%) | 0.24 (2%) | 0.18 (2%) | 0.19 (3%) | 0.16 (3%) | 0.14 (4%) | 0.14 (5%) | 0.19 (7%) | 0.18 (8%) | 0.28 (10%) | 0.27 (13%) | 0.23 (13%) | 0.15 (17%) | 0.14 (11%) |
| SILVERM | 0.39 (0%) | 0.28 (1%) | 0.40 (1%) | 0.30 (2%) | 0.29 (2%) | 0.27 (3%) | 0.23 (4%) | 0.32 (5%) | 0.37 (7%) | 0.43 (8%) | 0.52 (10%) | 0.58 (12%) | 0.45 (15%) | 0.34 (17%) | 0.27 (12%) |
| COPPER | 0.21 (1%) | 0.15 (2%) | 0.24 (3%) | 0.16 (3%) | 0.16 (4%) | 0.17 (4%) | 0.16 (5%) | 0.16 (6%) | 0.16 (7%) | 0.22 (10%) | 0.23 (12%) | 0.23 (12%) | 0.17 (11%) | 0.15 (12%) | 0.13 (9%) |

#### Random-entry 1-ITM option buy, gross Rs per trade by entry hour (design; the 'any time' baseline)

| commodity | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| CRUDEOIL | +7 | -22 | -43 | -32 | -18 | +36 | -4 | -26 | -103 | -141 | -151 | -92 | -116 | -292 | - |
| NATURALGAS | +12 | +12 | +37 | +7 | -10 | +19 | +21 | -9 | +21 | -44 | -47 | -40 | -46 | -78 | - |
| GOLDM | -39 | +22 | -30 | -10 | -1 | -4 | -46 | -69 | -11 | -99 | -106 | -96 | -62 | +29 | - |
| SILVERM | -16 | +28 | -82 | -45 | -66 | -21 | -25 | -29 | -256 | -81 | -187 | -331 | -305 | -317 | - |
| COPPER | -415 | +427 | +123 | -40 | +253 | +130 | +236 | -81 | +47 | -18 | -509 | -222 | -497 | -710 | - |

#### What Rs 1 lakh can carry (ATM call premium x lot at 15:00, Rs)

| option | median Aug25-Jul26 | median Jul-Oct 26 | max | days > Rs 1 lakh | lots at the recent median |
|---|---|---|---|---|---|
| COPPER | 42,125 | 55,912 | 188,037 | 3% | 1 |
| CRUDEOIL | 19,188 | 34,845 | 106,700 | 1% | 2 |
| GOLD | 191,500 | 230,375 | 878,225 | 84% | 0 |
| GOLDM | 22,875 | 26,435 | 116,595 | 0% | 3 |
| NATGASMINI | 3,787 | 2,606 | 19,094 | 0% | 38 |
| NATURALGAS | 18,859 | 12,891 | 98,094 | 0% | 7 |
| SILVER | 102,878 | 164,265 | 467,640 | 58% | 0 |
| SILVERM | 22,864 | 33,830 | 177,689 | 1% | 2 |

How to read the hours tables:
- **Where the moves are:**
  - Crude and gas move most from 17:00 to 22:00 IST (US session; EIA at 20:00/21:00).
  - Gold, silver and copper move most at 09:00 (the Asian open) and at 19:00-21:00 (US data and the COMEX open).
- **Where the volume is:** 70-80% of option volume trades after 17:00. The Indian morning is thin. Its spreads are
  probably about 2x wider (assumed, not measured).
- **The trap:** a random 1-ITM buy made about Rs 0 gross in the morning but lost Rs 40-330 gross per trade after
  18:00. Evening premiums carry the expected move. The evening is busy, but busy is not cheap.
- **If Boss trades MCX at all:** crude and natural gas options, 19:00-22:30 IST, CRUDEOIL or NATURALGAS 1 lot. That is
  the deepest book with the narrowest spread (crude 0.19-0.30%). Expect to pay about Rs 150-300 per round trip.

## Bottom line for Boss

- **Rs 1 lakh, 1 lot, option buying on MCX: no tested rule earns money after costs.**
  - Best honest case: NATURALGAS evening breakout, about +Rs 40-80 a day, not significant, with a Rs 40k drawdown.
  - Paper-trade it for 2-3 months with real bid/ask logged before risking money.
- **Liquidity 15+5 should stay on the NSE indices.** On MCX it loses or breaks even.
- **Mini futures are not better.**
  - NATGASMINI Liquidity 15-min futures made +Rs 128 a trade in design (p 0.01, q 0.45, 5/12 green). It lost -Rs 98 a
    trade in the holdout.
  - The other CRUDEOILM and NATGASMINI cells lose, apart from tiny holdout cells (14-38 trades).
  - The gold mini futures that looked best cannot be carried at Rs 1 lakh.

## Files

- `research/hunt/m3/PREREG.md`: plan and amendments.
- `research/hunt/m3/fetch_mcx.py`: Dhan rollingoption fetch, all commodities.
- `research/hunt/m3/fetch_fut.py`: live futures 1-min.
- `research/hunt/m3/snap_mcx.py`, `quote_fut.py`: live bid/ask snapshots.
- `research/hunt/m3/spreads.py`: builds `spreads.json` from the snapshots.
- `research/hunt/m3/m3lib.py`: data, Liquidity port, signals, simulator, costs.
- `research/hunt/m3/run.py`: trades and random baselines per underlying.
- `research/hunt/m3/analyze.py`: stats, BH, SPA, holdout (run once).
- `research/hunt/m3/tables.py`: tables for this report.
- `research/hunt/m3/results/`:
  - `cells_{design,holdout}[_allrows|_x0.5|_x2].csv`, `months_*.csv`, `trades_*.csv.gz`;
  - `hours_trades_*.csv`, `spa_*.json`.
- `scratchpad/hunt/m3/`: about 400 MB in all.
  - `raw/` holds the option minutes, spot and live futures (256 MB, zstd). `work/` holds trades and random draws.
  - Logs: `fetch_*.log`, `runp_*.log`, `design_*.log`, `holdout*.log`.
  - Crude was reused from `scratchpad/hunt/strad_crude/cache`.

## Re-cost with measured evening spreads (9 Oct 2026)

Live MCX option chains were snapshotted 19:24-19:27 IST on 9 Oct (5 rounds; the recorder was stopped early by a machine restart).
Median full bid/ask spread of near-ATM (±3%) options, evening: NATURALGAS 0.43%, NATGASMINI 0.60%, CRUDEOIL 0.31%,
CRUDEOILM 0.23%, GOLDM 0.39%, SILVERM 0.47% (morning not measured; assumed 2× evening). NATURALGAS was costed at 0.30%
before, so its real evening spread is wider.

Holdout re-priced with these spreads (costs only, no rule change; `analyze.py holdout --recost`):

| rule | trades | win | net/trade | net/day | before (0.30%) |
|---|---|---|---|---|---|
| NATURALGAS EVE (evening breakout) | 55 | 49% | +Rs 70 | +Rs 62 | +Rs 93 / +Rs 83 |

Still positive after the real spread, still not significant (random p 0.19, BH q 0.55). It stays a paper-only bot.
Caveat: one evening of snapshots; spreads vary by day and strike.
