# X2: combining all the studies. Connections, contradictions, and 5 combined rules

Written 8 Oct 2026 by X2, the cross-study synthesiser. Option BUYING only, fixed 1 lot, Rs 1,00,000. Real spreads
(h24), app fills and charges, exits on the option's 1-minute HIGH/LOW.

- Code: `research/hunt/x2/`
  - `PREREG.md`, written before any P&L
  - `d0_desc.py`, `d1_desc.py`: descriptive checks, PRE only, no P&L
  - `run.py`: `pre`, then `hold` once
  - `diag_pre.py`
- Logs: `scratchpad/hunt/x2/` (`pre.md/json`, `hold.md/json`, `d0_desc*.log`, `d1_desc.log`, `diag_pre.log`,
  `posthoc_daynight.log`, `HOLDOUT_RUN.flag`).

Sources read: OBUY_* (FINAL, SEARCH, VALIDATION, GA/GB/GC, LV05), OPTION_BUYING_CATALOG, SCALP17, SWING / SWING_DEEP /
SWING_EXITS, LIQUIDITY*, JARVIS_EXITS, MARKET_DRIVERS, MARKET_HOW (h41), HUNT_H1..H45, HUNT_FINAL, PROFIT_LOCK_8OCT,
h42 `answers.csv` (1,296 rows), and the fetched datasets through h38/h39/h40/h44's tables.

## Verdict for Boss (read this first)

**Rs 5,000/day from option buying with Rs 1 lakh and fixed lots: still NO. Combining the studies finds one real
connection, but it does not change the answer.**

1. **The real connection is overnight.** Three findings fit together:
   - the evening FII and positioning data predict the next morning's GAP, not the day after it (h40);
   - strong closes carry on overnight (h31);
   - the day's option OI build-up also leans toward the next gap (h26 data, read at 15:19).

   The FII files come out after the market shuts, so a buyer can never trade them. The 15:19 option OI and the
   close are visible before the close, so a buyer can.

   **Rule R3 (night stack)** buys 1-ITM at 15:20 when the close is strong and the OI agrees, then sells at 09:16.
   - Before the holdout it passed every test: +Rs 207/day net at 1 lot (NIFTY weekly + BANKNIFTY monthly), positive
     in all 6 years, coin-flip p < 0.001, BH q 0.008, SPA p 0.013 over the 5 rules.
   - **In the locked year it made only +Rs 150/day.** That is not significant (t 0.5, coin-flip p 0.051). BANKNIFTY
     made +46k and NIFTY lost -8.5k. Max drawdown was -Rs 84k on Rs 1 lakh.
2. **The calls-only night rule (R4) also passed before the holdout, then lost -Rs 224/day.** On a Rs 1 lakh walk it
   would have ruined the account (below Rs 25k).
3. **The three Liquidity combinations (R1, R2, R5) made money in the locked year** (+205, +415 and +235 Rs/day,
   against +167 for plain BANKNIFTY Liquidity). None of them passed its own pre-registered bar before the holdout, so
   none can be claimed.
4. **Honest Rs/day at Rs 1 lakh, 1 lot:**
   - BANKNIFTY Liquidity: Rs 65-167.
   - Adding the R3 night book (it runs at different hours, correlation about 0): about **Rs 300/day** in both periods.
     This combination is post-hoc.
   - **Rs 5,000/day needs about 16-33 lots of each.** That is roughly Rs 20-60 lakh of capital and drawdowns of
     Rs 6-30 lakh. Rs 1 lakh carries 1 lot.

## 1. The map: every small REAL effect found (and what it costs to harvest)

"Real" means it beat random or a coin flip, or passed BH somewhere. It does not mean it paid after costs.

The round-trip cost of a 1-ITM option is about 1.1% of premium (h45). In index terms:
- BANKNIFTY: about 10 index points, roughly 18 bp;
- NIFTY: about 2.6 points, roughly 10 bp at 2025-26 levels;
- overnight carry (coin-flip side): Rs 150-300 per trade (h31).

| # | effect (study) | size | horizon | index / time | cost to harvest vs size | status |
|---|---|---|---|---|---|---|
| 1 | Liquidity 15+5 breaks (h4/h13/h24/h36) | +Rs 65 (PRE) / +167 (holdout) per day, 1 BN lot; beats random p 0.0005 | 20 min - 15:10, runners | BN best; FIN/MIDCP thin | net is about 40% of gross | **the only durable edge** |
| 2 | Clean level breaks continue (h18, x2 D1) | +2.3 bp/30 min (t 4.9); not larger on active days | 15-60 min | all, all day | about 18 bp round trip, 8x the edge | real, unharvestable alone |
| 3 | Sweeps do NOT reverse (h18) | +0.5-1 bp further in the poke's direction | 30 min | all | n/a | kills "fade the sweep" |
| 4 | Option OI build-up / put-minus-call OI (h26) | IC 0.03 (t 4.5) at 15 min, 1-2 bp | 15 min | NIFTY, BN | 10x the edge | real; works as a **skip filter** (+28 Rs/day PRE, q 0.13) |
| 5 | Option synthetic forward leads the index (h29, h39) | IC +5-13% (index catches up to options); BN IVJ/SYNF 2-3 min IC 0.011 | 1-30 min | all | options have already moved; extreme-minute move is 46-58% of the round trip | real, already priced |
| 6 | VSPIKE: unusual option volume with premium rising (h26) | +197 gross / +102 net Rs/day BN holdout; FIN +200 gross / -134 net | arm exits | BN, FIN, NIFTY | the spread eats FIN | weak hint |
| 7 | Strong close carries overnight (h31) | +0.17% (t 8.1 NIFTY) after an up day >= 0.3%; R17 beats coin flip, q < 0.001 | 15:20 -> 09:15 | NIFTY, BN, MIDCP | Rs 150-300 carry | real; decaying in h31's exits, **alive at the 09:16 exit (x2)** |
| 8 | Evening FII / participant / bhavcopy positioning (h40) | rho 0.10-0.29 with the **next gap**; about 0 after the open; about 0 with the gap a day later (x2 D0) | the gap only | all | not tradable: published after the close | real, no entry for a buyer |
| 9 | Day-end option OI build-up at 15:19 (x2 D0, h26 data) | rho +0.10 / +0.15 with the overnight move; partial on close strength +0.06 (p 0.04) NIFTY, +0.04 BN | overnight | NIFTY, BN | as #7 | small, stackable |
| 10 | Post-FOMC gap (h28) | BN +0.36% vs +0.10% (q 0.03) | overnight | BN, NIFTY | as #7; only ~8 nights a year | real, rare |
| 11 | Intraday drift negative, gains come overnight (h28, h31) | NIFTY overnight +0.11% a night on average | day vs night | all | n/a | structural |
| 12 | Liquidity's edge is on the short side (h8, h12) | longs +2.0 bp (below random), shorts +5.3 bp; puts +80/+217 per lot-trade | intraday | BN, FIN, MIDCP | n/a | consistent with #11 |
| 13 | h12 per-trade lifts on Liquidity | room >= 6 stops +137, gap >= 0.3 ATR +89, VIX up +70 Rs/lot-trade (PRE, beat random) | trade | 3 indices | halves the trades; Rs/day flat | real per trade, not per day |
| 14 | Gaps > 0.5% rarely fill (h42, MARKET_HOW) | filled 34-40% vs 69-77%; close on the gap side 89-97% | day | all | already in the open price (h27) | fact, not a trade |
| 15 | Early extreme holds (h42, h41) | high/low set by 10:15 on 3 days in 4; holds 75-89% | day | all | coin-flip direction after the first hour (49-54%) | fact |
| 16 | Morning is wilder (h41, h42) | first 15 min about 2x; 09:20-10:00 60-min moves +24-45% | 09:15-10:00 | all | premium also prices it | fact |
| 17 | After a > 1% down day the range nearly doubles (h42) | BN 2.77% vs 1.79% (q 1e-53) | day | all | VIX rises too; q_opt = 1 | fact; untested lead (see §4) |
| 18 | Dealer gamma predicts SIZE, not side (h18) | 30-min range +10-30% in the most negative GEX quintile | 15-60 min | all | needs a cheap straddle | fact |
| 19 | BANKNIFTY has the cheapest real spread at size (h24, h10) | half-spread 0.16% vs FIN 0.42%; capacity 129-287 lots vs FIN 0 | always | BN | lowest | cost loophole (already used) |
| 20 | Strike / expiry choice (h13, h16, h29) | 1-ITM nearest is best; OTM is no better per rupee; lowest-IV strike +0.2 pt (q 0.24) | n/a | all | 2-ITM costs the most per gross | settled |
| 21 | Own exits beat points / brackets (h34, h45, LIQUIDITY_EXITS) | points turn Liquidity's +98k into -62k; +/-5% loses Rs 100-140 a trade | n/a | all | n/a | settled |
| 22 | Cost-avoidance on thin indices (h44, h23, h14) | the meta-label gain was all FIN/MIDCP skips (+210/day holdout); limit at +0.5% saves MIDCP | n/a | FIN, MIDCP | n/a | real, small |
| 23 | Limit entry +0.5% (h14) | +Rs 288/day PRE at plan size (from thin books) | entry | MIDCP, FIN | n/a | adopted |
| 24 | Heavyweight basket leads (h6) / gap-up stock ORB (h2) | in-sample good, holdout about 0 net / +218/day at Rs 5L per trade | 60 min / day | NIFTY, BN / stocks | impact | weak |

Everything else in the catalog (patterns, indicators, ORB family, news, calendar, levels, Fibonacci/Gann, 26M-rule
search, SCALP17, swing, ML) is equal to random or worse. It is not on the map.

## 2. Connections and contradictions

### Connections found

- **A. Timing is the loophole, and it is only open at night.**
  - h40's positioning data predicts the gap after its own publication (rho up to 0.29). It predicts nothing once the
    market is open (h27, h40), and nothing for the gap a day later (x2 D0, all |rho| <= 0.04).
  - The same information is mostly visible in the market before the close: the close location (h31), breadth (h31)
    and the day's option OI build-up (h26 data, x2 D0).
  - So the one place a buyer can stand in front of the "evening information" is 15:20 -> 09:16, selling at the first
    minute. That is when the information has been priced (h27, h40 Part A) and before the negative intraday drift
    (h28) starts.
  - This is why R3 uses the X0916 exit. h31's walk-forward picked 10:15 exits and decayed. At 09:16 the R17 signal
    alone was positive in every PRE year (diag: NIFTY +137k, BN +153k).
- **B. Stacking independent weak edges.** R17 (close + breadth) and the 15:19 OI build-up each beat the coin flip on
  their own (PRE). Together they raised BN per-trade net from +366 to +457. NIFTY stayed flat (+308 -> +287). The
  stack is partly redundant: the partial correlation is small.
- **C. Day book + night book.** Liquidity (intraday, short-side-tilted) and R3 (overnight, long-tilted) have daily
  correlation of about 0 (-0.03 PRE, +0.03 holdout). They use the same Rs 1 lakh at different hours.
- **D. Horizon vs cost.** Every intraday directional footprint (#2, #4, #5) is 1-3 bp against a 10-18 bp round trip.
  Only two kinds of trade beat that:
  - holds that capture a whole overnight gap (#7, average size 0.4%);
  - Liquidity's rare runners to the next level.

  x2 D1 checked whether breaks continue more on high-range days. They do not: the directional part stayed at
  1.5-2 bp. So "trade breaks only on big days" was dropped before any P&L.
- **E. Cost loopholes** that are real and already in the plan:
  - trade BN, the cheapest spread with the most capacity;
  - limit entry at +0.5%;
  - skip thin-index trades unless they are high quality (R2's idea);
  - trade fewer, larger-move trades.

  Mid or bid entries cannot be tested: there is no quote history. Recording it live is the only route (HUNT_FINAL).

### Contradictions, and what they hide

| contradiction | explanation | loophole? |
|---|---|---|
| ORB loses over 5 years, but Boss saw +5k paper days | h19/h22: the green came from Liquidity's first-day rules (+8k on 1 Oct) and from untracked arms. ORB lost those days. Short streaks are what a 10-16% winning-month arm shows by chance | no |
| Liquidity positive in backtest, -6k forward on paper | h36: the paper account ran many arms at once and swung 2x Plan A. A 7-day sample is inside Plan A's noise. h23/h24: the spread model matters (h17 +308 vs h23 -223/day), and MIDCP fills decide it | no; log bid/ask live |
| Overnight edge (h31) vs negative intraday drift (h28) | the same fact seen twice: returns accrue at night | **yes**: buy at the close and sell at the open (R3). Calls intraday are the weak side (h8/h12) |
| h40: FII "predicts the next day", h27: "everything is priced at the open" | both true: it predicts the gap, which is already priced at 09:15 | only through same-day proxies before 15:20 (R3) |
| h26: skip when OI is against helps; h39: skip when OI/IV/forward is against HURTS | h26 uses index-option OI, z <= -1; h39 uses stock-option OI and IV at 1 sigma | unresolved; R1 used h26's |
| h44 meta-label: helps the 3-index book, hurts BN | it learned "skip expensive indices", not "read the trade" | R2 tried this explicitly; failed PRE |
| LV-05 / BTST strong in 2020-23, dead after (OBUY_GA/GC, h31) | regime: a bull market plus weeklies | R3's holdout: BN leg alive, NIFTY leg not |

## 3. The 5 pre-registered rules and results

Fully specified in `research/hunt/x2/PREREG.md`, written before any P&L. Common to all: 1 lot, Rs 1 lakh free-cash
check, real spread, app costs, kappa 0.02 on the Liquidity legs.

- **R1 LIQ-BN-OIVETO:** BN Liquidity 15+5. Skip a signal if BU15 <= -1 or dOIpc5 <= -1 against the trade.
- **R2 LIQ-5IX-GATE:** BN Liquidity, all trades. On NIFTY / FIN / MIDCP / SENSEX take a trade only if it scores >= 2
  of {PE, room >= 6 stops, |gap| >= 0.3 ATR, VIX up} and the OI is not against it.
- **R3 NIGHT-STACK:** NIFTY weekly and BN monthly, 1-ITM.
  - At the 15:19 close, take side = h31 R17 "all agree", only if the 15:19 option OI build-up agrees (z >= 0.5).
  - Buy at the 15:20 open, sell at 09:16.
- **R4 NIGHT-CE-VOTE:** same contracts and exit as R3, calls only. Buy if >= 2 of {close location >= 0.75,
  breadth >= 0.6, OI build-up z >= 0.5, FOMC/US-CPI night}.
- **R5 LIQ-BN-ASYM:** BN Liquidity. Take all puts. Take a call only if BU15 >= +1.
- **Dropped before any P&L:** "60-min break continuation on active days" (x2 D1, no directional lift).

Baselines:
- R1/R5: random skipping of the same number of trades.
- R2: a random same-size subset per thin index.
- R3: coin-flip side on the same nights.
- R4: calls on random nights.

PASS needs all of:
- BH q <= 0.10;
- baseline p <= 0.05;
- net > 0 at 1.5x spread;
- >= 60% of years positive;
- for the filters, also a positive excess over the unfiltered base.

### PRE (data start to 30 Sep 2025), 1 lot

| rule | trades | net/day | gross/day | net at 1.5x spread | vs unfiltered BN | t | BH q | baseline p | years + | max DD | worst month | P(losing month) | lots for 5k | PASS |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R1 | 699 | 75.9 | 156 | 63.5 | +10.3 | 1.40 | 0.102 | 0.128 | 4/5 | -29.7k | -8.6k | 0.50 | 66 | no |
| R2 | 1,886 | 116.5 | 287 | 88.9 | +65.7 | 1.14 | 0.127 | 0.002 | 3/6 | -62.0k | -14.0k | 0.54 | 43 sets | no |
| **R3** | 725 | **206.8** | 275 | 196.7 | n/a | 2.75 | **0.008** | **< 0.001** | **6/6** | -40.8k | -31.7k | 0.37 | 24 | **YES** |
| **R4** | 559 | **156.9** | 213 | 148.4 | n/a | 2.74 | **0.008** | **< 0.001** | **6/6** | -34.3k | -20.2k | 0.37 | 32 | **YES** |
| R5 | 535 | 83.0 | 144 | 73.8 | +17.5 | 1.52 | 0.102 | 0.031 | 4/5 | -29.9k | -7.7k | 0.52 | 60 | no |

- Hansen SPA over the 5: p 0.013 (White RC 0.025). Best: R3.
- Anchored walk-forward (trade year Y only if all earlier years are positive): R3 +2.53 lakh over 2021-25,
  R4 +1.81 lakh.
- R3 by year: 2020 +11k, 2021 +48k, 2022 +41k, 2023 +46k, 2024 +39k, 2025 (Jan-Sep) +78k. By index: NIFTY +113k on
  393 trades, BN +152k on 332.
- Rs 1 lakh walk: R3 ended at Rs 3.64 L and never went below its start (low Rs 98k). No signal was skipped for cash.

### LOCKED HOLDOUT (1 Oct 2025 to latest), run once (`HOLDOUT_RUN.flag`)

| rule | trades | net/day | gross/day | net at 1.5x spread | vs unfiltered BN (167/day) | t | BH q | baseline p | max DD | worst month | P(losing month) | lots for 5k | Rs 1 L walk end |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R1 | 234 | 204.8 | 388 | 171.3 | +37.4 | 1.27 | 0.23 | 0.054 | -22.9k | -9.2k | 0.44 | 24 | 1.51 L |
| R2 | 615 | 414.9 | 931 | 322.0 | +248.1 | 1.09 | 0.23 | 0.004 | -69.7k | -39.8k | 0.43 | 12 sets | 2.06 L |
| **R3** | 155 | **149.8** | 248 | 133.1 | n/a | 0.50 | 0.385 | 0.051 | **-83.7k** | -41.6k | 0.46 | 33 | 1.37 L (low **47k**) |
| **R4** | 116 | **-224.2** | -143 | -238.7 | n/a | -0.89 | 0.813 | 0.088 | -102.6k | -42.8k | 0.63 | never | **ruin (24.8k)** |
| R5 | 179 | 234.5 | 365 | 210.9 | +67.1 | 1.58 | 0.23 | 0.233 | -14.4k | -6.3k | 0.40 | 21 | 1.58 L |

- SPA over the 5 in the holdout: p 0.175.
- **The two PRE passes did not hold up.**
  - R3 stayed positive but became noise: t 0.5, coin-flip p 0.051. The NIFTY leg lost -8.5k; the BN leg made
    +45.9k.
  - R4 lost and would have ruined Rs 1 lakh.
- **R1, R2 and R5 were positive, but they are information only** (they failed PRE).
  - R2's +415/day comes from MIDCP (+43.8k) and BN (+41.7k). MIDCP depends on fills at a <= 0.3% spread (h24), and
    FIN/MIDCP cannot take size (h10).
  - R5 (BN calls only with OI confirming) beat plain BN by +67/day, but its random-skip p was 0.23.

### Post-hoc (information only): day book + night book

BN Liquidity 1 lot + R3, on BN's calendar:

| period | Rs/day | correlation | max DD | P(losing month) |
|---|---|---|---|---|
| PRE | **317** | -0.03 | -40.7k | 0.33 |
| holdout | **317** | +0.03 | -93.4k | 0.43 |

Premium tied up is at most about Rs 21k intraday plus about Rs 7-21k overnight, which fits in Rs 1 lakh. This is the
most defensible "combined" number, but R3's holdout part is not significant.

## 4. Honesty notes

- **The holdout was not pristine for every ingredient.** Earlier studies had already read, for information only:
  - BANKNIFTY Liquidity (+167/day);
  - the h26 BU15 filter (+81/day);
  - h31's R17 family (2026 negative summed over all indices and exits; best variant +245/day).

  x2 justified its rules only with pre-holdout numbers, but it could not unknow those. The combined rules (OI stack,
  X0916, NIFTY-W/BN-M contracts, votes) had never been run on the holdout.
- Breadth before Oct 2024 uses the 15:30 daily close in h31's table: a 10-minute look-ahead. The R17 side matches the
  minute version on 98% of days. 2025 used minute breadth, with no look-ahead.
- x2 D0's first run read the 13:19 OI column by mistake. It was corrected to 15:19 (checked: the panel spot at
  column 364 equals h31's 15:19 price to 2e-8) before the PREREG was final. Both logs are kept.
- PRE strength is partly selection. R17 was the best family of h31's 4,080-variant search. The holdout is the honest
  number, and it says "small, not proven".
- **Untested lead:** after a > 1% down day the range nearly doubles (h42 Q0413/Q0427). Option buys with the arm's
  exits beat same-minute random entries there (raw p 0.01-0.04, q_opt = 1). It is the only "size" fact with a
  positive buyer sign. It would need its own pre-registration, with VIX/IV repricing as the obvious killer.

## 5. Plain answer

- **Rs/day at 1 lot (net, real costs):**
  - BN Liquidity: 65 (PRE) / 167 (holdout).
  - Night stack R3: 207 / 150 (holdout not significant).
  - Both together (post-hoc): about 317/day, which is about Rs 6-7k a month, with 33-43% losing months and an
    Rs 41-93k drawdown on a Rs 1 lakh account.
- **Lots for Rs 5,000/day:** about 16 lots of each book (BN Liquidity + R3) at the combined rate. That is about Rs 7-8
  lakh of premium in play and Rs 20-30 lakh of capital for the drawdowns (16 x Rs 41-93k = Rs 6.5-15 lakh). At the
  separate PRE rates it is 30-77 BN lots for Liquidity alone (h36) and 24-33 lots for R3 alone.
- **With Rs 1 lakh: NO.**
- **What to do:**
  - Keep BN Liquidity 1 lot (h36 Plan A).
  - Put R3 on paper at 1 lot (15:20 entry, 09:16 exit) for about 100 nights. It is the one new combined rule that
    passed every PRE gate and stayed positive, though not significant, in the locked year.
  - Do not trade R4.
  - Log bid/ask live so the limit-at-mid loophole can be tested later.
