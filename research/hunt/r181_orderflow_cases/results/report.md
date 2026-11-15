# R181: do the guide's order-flow setups work on the phone's own recordings?

*15 Nov 2026. Research only; nothing in the app changes. Pre-registered (PREREG.md, committed before the run, with
Amendment 1 for the guide's own definitions, also before the run). Run once; a second run only added the post-hoc
sensitivity of Amendment 2 and reproduced the first run's numbers exactly (FINAL READ below).*

## For Boss, in plain words

**What the data is.** The phone's 1-second recordings of the NIFTY and BANKNIFTY near futures and their ATM options, 20
trading days, 15 Oct - 13 Nov 2026, with every second's buy and sell volume (estimated with the tick rule: Zerodha does not
say who was the aggressor) and a per-price footprint each minute. The first day only builds the profile, so **19 signal
days, 2 indices**. The OI walls for the A+ setup exist only in the first export (to 28 Oct), so A+ had 8 days.

**What was tested.** Each setup from the guide, translated into exact rules on the index futures (signal on the
underlying), trading 1 lot of the ATM option (CE for longs, PE for shorts), the guide's stop and target, 2:1 or skip,
out by 15:15 (15:05 on expiry). Real dated charges and the repo's liquidity fills; also at 2x slippage and at the recorded
bid / ask. Each setup was run twice: **with** its order-flow condition and **without** it (the price-only twin), and the
order-flow trades were compared with 200 random entries each.

| setup | price-only trades | win | net (2x slip) | order-flow trades | win | net (2x slip) | random entries, a trade | verdict |
|---|---|---|---|---|---|---|---|---|
| Case 1: failed breakdown + absorption at VAL | 16 | 19% | Rs 1,508 (1,093) | **0** | - | - | - | never fired |
| Case 2: failed breakout + exhaustion at VAH | 10 | 0% | Rs -10,487 (-10,831) | **0** | - | - | - | never fired |
| Case 3: stacked imbalances + retest | 59 | 31% | Rs -14,729 (-16,072) | 11 | 18% | Rs -6,182 (-6,408) | Rs -26 vs Rs -562 | no evidence |
| Case 4: double bottom / top at POC + CVD | 8 | 12% | Rs -4,297 (-4,516) | 3 | 0% | Rs -2,214 (-2,305) | Rs -736 vs Rs -738 | too few |
| Case 5: stop-run trap at prior-day low / high | 19 | 21% | Rs -7,711 (-8,332) | 7 | 29% | Rs -3,200 (-3,410) | Rs -824 vs Rs -457 | too few |
| Bonus A+: edge + OI wall + absorption (8 days) | 4 | 25% | Rs 5,725 (5,679) | **0** | - | - | - | never fired |

(1 lot, rupees summed over the sample; the A+ price-only trades are 4 of Case 1's 16.)

**What it means.**
- **No setup showed an edge, and the order-flow part did not help.** Where the order-flow condition fired at all (Cases
  3, 4, 5), its trades were no better than the same setups without it (p 0.52-0.73) and no better than random entries
  (p 0.19-0.90). Case 3's order-flow trades did worse than random entries. Nothing comes near the multiple-testing bar
  (BH q = 1.0 for all 15 tests, m = 4,066).
- **The guide's own absorption and exhaustion patterns almost never happen on 1-minute bars where the guide puts them.**
  Absorption bars (heavy selling, no new low) occurred 369 times in 13,870 bars, but never inside a Case 1 failed
  breakdown at VAL. The guide's exhaustion (5 candles of shrinking buying with higher highs) never occurred once on
  1-minute bars. The guide's pictures are probably bigger candles; on these days the strict rules simply did not trigger.
- **Most "trap" ideas lost even without order flow**, mostly because the 2:1 targets were rarely reached and the sample
  had a strong one-way rally (NIFTY about 22,400 to 25,500 from 28 Oct to 13 Nov): every short setup lost.
- **This sample is far too small to prove or disprove anything.** 116 trades in all, at most 11 order-flow trades in any
  setup. A real edge of a few hundred rupees a trade would need hundreds of trades to show. Read this as "no sign of an
  edge yet", not "proven useless".

**Data problems found** (detail below):
- The two exports **disagree** on the same minutes' futures volume and delta (15 Oct: the 1-second file's minute volume is
  lower in most minutes and the delta differs in about 97% of minutes). The footprint matches the 1-second file exactly.
  On 16-28 Oct the order-flow decisions still agreed on 100% of the Case 1, 3, 4 and A+ episodes but only 67% of Case 5's (6
  episodes).
- Missing futures minutes on several days: 9 Nov (293 of 375), 6 Nov (319), 4 Nov (338), 11 Nov (339-340), 13 Nov (346),
  2 Nov (351), 23 Oct (356-357). Thin minutes (fewer than 20 recorded seconds) up to 91 a day (9 Nov).
- On 9 Nov the footprint has 281-282 minutes, below the 300 needed, so **11 Nov had no levels** and no signals.
- On 15 Oct the NIFTY index (spot) rows cover only 194 minutes (from 10:30).
- No recording on 20 Oct or 10 Nov (look like NSE holidays: the weekly expiries moved to 19 Oct and 9 Nov).
- The recorded basis (futures minus the index row) is noisy at the open and the close (often 50-150 BANKNIFTY points
  off its midday level), so the pre-registered level shift moved the levels by 100-160 BANKNIFTY points on some ordinary
  days. A post-hoc check without that shift (Amendment 2) gives the same picture: no order-flow version made money at 2x
  slippage except Case 5 (7 trades, Rs +455), which is noise at that size.
- The option watch-list re-centres through the day (8-20 strikes a day); 7 trades were dropped because no option near
  the ATM was recorded at the entry second.
- `research/obuy/config.py` still lists the BANKNIFTY lot as 35; the recordings and the app use 30 (NSE's later cut).
  R181 uses 30. The config is not changed here (out of scope); flagged for a separate fix.

**What to do next.**
- Nothing to switch on. No setup earns a forward test under the pre-registered bar (8+ order-flow trades, positive at 2x
  slippage, better than its twin, both p <= 0.20).
- **Re-run R181 unchanged each month as the phone keeps recording** (the code takes any number of days). With about 3
  months (60 days) Case 3 would have roughly 35 order-flow trades and Case 5 about 22: still small, but a first honest read.
- If Boss wants Cases 1 and 2 tested at all, they need a new pre-registered version on bigger candles (for example
  5-minute bars), counted as new tests.
- Ask the app side to look at why the two recorders disagree on futures volume and delta, and to keep the spot rows
  from the open; both limit any order-flow study.

## Cannot be tested here

- **Case 6 (naked POC):** needs many sessions of untouched POCs; 20 days give too few.
- Case 3 is tested from the footprint, with the tick rule standing in for the aggressor side (no bid / ask side per
  trade exists in the data).

## Implementation notes (made before the run, found by the tests)

- Case 4: when several first lows qualify, candidates are taken in signal-bar order (a new one only after the previous
  signal), not first-low order: the look-ahead test showed the first-low order could let a later-known signal suppress an
  earlier one.
- Case 3 diagonal: the comparison row must be inside the bar's traded range (the bottom traded row has no row below).
- Case 3: with no retest within 30 bars, the search continues from the next bar (it needs a fresh cross of the level).

## FINAL READ (run log)

1. Run 1 (the pre-registered run): 116 trades; the results above.
2. Run 2: the same code path for the primary result (cases and tests identical to run 1, checked) plus the Amendment 2
   sensitivity. No rule of the primary result changed.

Signal source for every rule: the index near futures' 1-minute price and tick-rule order flow (and the option chain's OI
for A+); stops and targets on the futures; the traded instrument is the ATM option. No rule reads an option's chart.


---

# Detail (generated by r181report.py from results.json)

Days in the 1-second export: 20 (2026-10-15 .. 2026-11-13); the first is a profile day only. 1 lot (NIFTY 65, BANKNIFTY 30); liquidity fills, dated charges; 2x = slippage doubled. Rupees are per trade summed over the sample.

## Per case: order-flow (OF) version vs its price-only twin, the OF-fail rest and random entries

### Case 1 failed breakdown + absorption at VAL (long)

| set | trades | win | net 1x | net 2x | mean 1x | avg win | avg loss |
|---|---|---|---|---|---|---|---|
| price-only twin (all episodes) | 16 | 19% | Rs 1,508 | Rs 1,093 | Rs 94 | Rs 4,036 | Rs -815 |
| **OF version** | 0 | - | Rs 0 | Rs 0 | - | - | - |
| OF-fail rest | 16 | 19% | Rs 1,508 | Rs 1,093 | Rs 94 | Rs 4,036 | Rs -815 |
| variant: app absorb formula | 0 | - | Rs 0 | Rs 0 | - | - | - |
| OF at recorded bid / ask (sensitivity) | 0 | - | Rs 0 | Rs 0 | - | - | - |

- Random-entry twins (200 a trade): mean of the 200 twin means - a trade (2x -; 10th-90th percentile - .. -; twin win -) vs OF -.
- T1 (OF vs random same-size picks of the price-only trades) p = 1.0; T2 (vs random entries) p = 1.0. Verdict: **too few trades**.
- OF by index: NIFTY 0 trades Rs 0, BANKNIFTY 0 trades Rs 0. OF by side: long 0 trades Rs 0, short 0 trades Rs 0. Price-only by side: long 16 trades Rs 1,508, short 0 trades Rs 0. Off-ATM strikes used: 0.
- Source cross-check (16-28 Oct, 15 episodes): OF condition agrees between the two exports on 100%; OF trades with this export 0 (Rs 0), with the first export's deltas 0 (Rs 0).

### Case 2 failed breakout + exhaustion at VAH (short)

| set | trades | win | net 1x | net 2x | mean 1x | avg win | avg loss |
|---|---|---|---|---|---|---|---|
| price-only twin (all episodes) | 10 | 0% | Rs -10,487 | Rs -10,831 | Rs -1,049 | - | Rs -1,049 |
| **OF version** | 0 | - | Rs 0 | Rs 0 | - | - | - |
| OF-fail rest | 10 | 0% | Rs -10,487 | Rs -10,831 | Rs -1,049 | - | Rs -1,049 |
| variant: app absorb formula | 0 | - | Rs 0 | Rs 0 | - | - | - |
| OF at recorded bid / ask (sensitivity) | 0 | - | Rs 0 | Rs 0 | - | - | - |

- Random-entry twins (200 a trade): mean of the 200 twin means - a trade (2x -; 10th-90th percentile - .. -; twin win -) vs OF -.
- T1 (OF vs random same-size picks of the price-only trades) p = 1.0; T2 (vs random entries) p = 1.0. Verdict: **too few trades**.
- OF by index: NIFTY 0 trades Rs 0, BANKNIFTY 0 trades Rs 0. OF by side: long 0 trades Rs 0, short 0 trades Rs 0. Price-only by side: long 0 trades Rs 0, short 10 trades Rs -10,487. Off-ATM strikes used: 0.

### Case 3 stacked imbalances across VAH / VAL + retest

| set | trades | win | net 1x | net 2x | mean 1x | avg win | avg loss |
|---|---|---|---|---|---|---|---|
| price-only twin (all episodes) | 59 | 30% | Rs -14,729 | Rs -16,072 | Rs -250 | Rs 2,082 | Rs -1,273 |
| **OF version** | 11 | 18% | Rs -6,182 | Rs -6,408 | Rs -562 | Rs 2,131 | Rs -1,160 |
| OF-fail rest | 48 | 33% | Rs -8,547 | Rs -9,664 | Rs -178 | Rs 2,076 | Rs -1,305 |
| OF at recorded bid / ask (sensitivity) | 11 | 18% | Rs -6,101 | Rs -6,101 | Rs -555 | Rs 2,178 | Rs -1,162 |

- Random-entry twins (200 a trade): mean of the 200 twin means Rs -26 a trade (2x Rs -45; 10th-90th percentile Rs -547 .. Rs 591; twin win 34%) vs OF Rs -562.
- T1 (OF vs random same-size picks of the price-only trades) p = 0.7307; T2 (vs random entries) p = 0.9005. Verdict: **no evidence**.
- OF by index: NIFTY 10 trades Rs -4,928, BANKNIFTY 1 trades Rs -1,254. OF by side: long 6 trades Rs 468, short 5 trades Rs -6,649. Price-only by side: long 26 trades Rs -7,568, short 33 trades Rs -7,161. Off-ATM strikes used: 0.
- Source cross-check (16-28 Oct, 40 episodes): OF condition agrees between the two exports on 100%; OF trades with this export 7 (Rs -5,875), with the first export's deltas 7 (Rs -5,875).

### Case 4 double bottom / top at POC + CVD divergence

| set | trades | win | net 1x | net 2x | mean 1x | avg win | avg loss |
|---|---|---|---|---|---|---|---|
| price-only twin (all episodes) | 8 | 12% | Rs -4,297 | Rs -4,516 | Rs -537 | Rs 1,991 | Rs -898 |
| **OF version** | 3 | 0% | Rs -2,214 | Rs -2,305 | Rs -738 | - | Rs -738 |
| OF-fail rest | 5 | 20% | Rs -2,083 | Rs -2,211 | Rs -417 | Rs 1,991 | Rs -1,019 |
| OF at recorded bid / ask (sensitivity) | 3 | 0% | Rs -2,132 | Rs -2,132 | Rs -710 | - | Rs -711 |

- Random-entry twins (200 a trade): mean of the 200 twin means Rs -736 a trade (2x Rs -769; 10th-90th percentile Rs -1,159 .. Rs -16; twin win 13%) vs OF Rs -738.
- T1 (OF vs random same-size picks of the price-only trades) p = 0.5242; T2 (vs random entries) p = 0.3682. Verdict: **too few trades**.
- OF by index: NIFTY 0 trades Rs 0, BANKNIFTY 3 trades Rs -2,214. OF by side: long 0 trades Rs 0, short 3 trades Rs -2,214. Price-only by side: long 2 trades Rs 672, short 6 trades Rs -4,969. Off-ATM strikes used: 0.
- Source cross-check (16-28 Oct, 6 episodes): OF condition agrees between the two exports on 100%; OF trades with this export 3 (Rs -2,214), with the first export's deltas 3 (Rs -2,214).

### Case 5 stop-run trap beyond the prior-day low / high

| set | trades | win | net 1x | net 2x | mean 1x | avg win | avg loss |
|---|---|---|---|---|---|---|---|
| price-only twin (all episodes) | 19 | 21% | Rs -7,711 | Rs -8,332 | Rs -406 | Rs 2,176 | Rs -1,094 |
| **OF version** | 7 | 29% | Rs -3,200 | Rs -3,410 | Rs -457 | Rs 2,085 | Rs -1,474 |
| OF-fail rest | 12 | 17% | Rs -4,512 | Rs -4,922 | Rs -376 | Rs 2,267 | Rs -905 |
| OF at recorded bid / ask (sensitivity) | 7 | 29% | Rs -3,443 | Rs -3,443 | Rs -492 | Rs 2,036 | Rs -1,503 |

- Random-entry twins (200 a trade): mean of the 200 twin means Rs -824 a trade (2x Rs -855; 10th-90th percentile Rs -1,296 .. Rs -212; twin win 14%) vs OF Rs -457.
- T1 (OF vs random same-size picks of the price-only trades) p = 0.5339; T2 (vs random entries) p = 0.194. Verdict: **too few trades**.
- OF by index: NIFTY 1 trades Rs 1,905, BANKNIFTY 6 trades Rs -5,105. OF by side: long 3 trades Rs -4,056, short 4 trades Rs 856. Price-only by side: long 9 trades Rs -6,195, short 10 trades Rs -1,516. Off-ATM strikes used: 0.
- Source cross-check (16-28 Oct, 6 episodes): OF condition agrees between the two exports on 67%; OF trades with this export 1 (Rs -610), with the first export's deltas 3 (Rs -2,292).

### Bonus A+ edge + OI wall + order flow (16-28 Oct)

| set | trades | win | net 1x | net 2x | mean 1x | avg win | avg loss |
|---|---|---|---|---|---|---|---|
| price-only twin (all episodes) | 4 | 25% | Rs 5,725 | Rs 5,679 | Rs 1,431 | Rs 7,513 | Rs -596 |
| **OF version** | 0 | - | Rs 0 | Rs 0 | - | - | - |
| OF-fail rest | 4 | 25% | Rs 5,725 | Rs 5,679 | Rs 1,431 | Rs 7,513 | Rs -596 |
| variant: app absorb formula | 0 | - | Rs 0 | Rs 0 | - | - | - |
| OF at recorded bid / ask (sensitivity) | 0 | - | Rs 0 | Rs 0 | - | - | - |

- Random-entry twins (200 a trade): mean of the 200 twin means - a trade (2x -; 10th-90th percentile - .. -; twin win -) vs OF -.
- T1 (OF vs random same-size picks of the price-only trades) p = 1.0; T2 (vs random entries) p = 1.0. Verdict: **too few trades**.
- OF by index: NIFTY 0 trades Rs 0, BANKNIFTY 0 trades Rs 0. OF by side: long 0 trades Rs 0, short 0 trades Rs 0. Price-only by side: long 4 trades Rs 5,725, short 0 trades Rs 0. Off-ATM strikes used: 0.
- Source cross-check (16-28 Oct, 4 episodes): OF condition agrees between the two exports on 100%; OF trades with this export 0 (Rs 0), with the first export's deltas 0 (Rs 0).

## Post-hoc sensitivity (Amendment 2): levels without the noisy basis shift

No shift on the same futures contract; the roll day shifted by median basis. Descriptive only, no p value.

| case | price-only trades | price-only net 1x / 2x | OF trades | OF win | OF net 1x / 2x |
|---|---|---|---|---|---|
| C1 | 16 | Rs 2,577 / Rs 2,172 | 1 | 0% | Rs -534 / Rs -565 |
| C2 | 13 | Rs -13,578 / Rs -13,985 | 0 | - | Rs 0 / Rs 0 |
| C3 | 66 | Rs -16,806 / Rs -18,250 | 10 | 0% | Rs -10,000 / Rs -10,251 |
| C4 | 12 | Rs -6,285 / Rs -6,611 | 4 | 0% | Rs -4,339 / Rs -4,458 |
| C5 | 16 | Rs 4,958 / Rs 4,506 | 7 | 29% | Rs 667 / Rs 455 |
| A+ | 4 | Rs -4,813 / Rs -4,907 | 0 | - | Rs 0 / Rs 0 |

## Tests and BH (m = 4,066)

| test | p | BH q (all m) | q within R181 |
|---|---|---|---|
| C1:T1 | 1.0 | 1.0 | 1.0 |
| C1:T2 | 1.0 | 1.0 | 1.0 |
| C1:T1v | 1.0 | 1.0 | 1.0 |
| C2:T1 | 1.0 | 1.0 | 1.0 |
| C2:T2 | 1.0 | 1.0 | 1.0 |
| C2:T1v | 1.0 | 1.0 | 1.0 |
| C3:T1 | 0.7307 | 1.0 | 1.0 |
| C3:T2 | 0.9005 | 1.0 | 1.0 |
| C4:T1 | 0.5242 | 1.0 | 1.0 |
| C4:T2 | 0.3682 | 1.0 | 1.0 |
| C5:T1 | 0.5339 | 1.0 | 1.0 |
| C5:T2 | 0.194 | 1.0 | 1.0 |
| A+:T1 | 1.0 | 1.0 | 1.0 |
| A+:T2 | 1.0 | 1.0 | 1.0 |
| A+:T1v | 1.0 | 1.0 | 1.0 |

T1 = selection twin; T2 = random-entry twin; T1v = the app-absorb-formula variant's selection twin.

## Dropped episodes

A+:rr 1, C1:rr 16, C2:no_strike 1, C2:rr 15, C3:no_price 1, C3:no_strike 2, C4:rr 5, C5:no_strike 3, C5:rr 29

(rr = failed the 2:1 rule, a price rule applied to every version; no_strike / no_price = no recorded option near the ATM at the entry second.)

## Per day (all cases)

| day | price-only trades | price-only net | OF trades | OF net |
|---|---|---|---|---|
| 2026-10-16 | 7 | Rs 2,035 | 1 | Rs -1,196 |
| 2026-10-19 | 4 | Rs -5,024 | 2 | Rs -2,760 |
| 2026-10-21 | 5 | Rs 4,703 | 1 | Rs 1,770 |
| 2026-10-22 | 6 | Rs 612 | 1 | Rs -1,254 |
| 2026-10-23 | 8 | Rs -5,114 | 1 | Rs -610 |
| 2026-10-26 | 11 | Rs 8,818 | 1 | Rs -1,835 |
| 2026-10-27 | 13 | Rs -9,293 | 1 | Rs -996 |
| 2026-10-28 | 17 | Rs -11,932 | 3 | Rs -1,819 |
| 2026-10-29 | 2 | Rs -1,246 | 1 | Rs -975 |
| 2026-10-30 | 3 | Rs 66 | 2 | Rs 1,647 |
| 2026-11-02 | 3 | Rs 4,273 | 0 | Rs 0 |
| 2026-11-04 | 14 | Rs -8,876 | 2 | Rs 960 |
| 2026-11-06 | 3 | Rs -4,417 | 1 | Rs -2,338 |
| 2026-11-09 | 2 | Rs 5,561 | 0 | Rs 0 |
| 2026-11-12 | 14 | Rs -6,776 | 2 | Rs 1,255 |
| 2026-11-13 | 4 | Rs -3,381 | 2 | Rs -3,446 |

## Data checks (per day, NIFTY / BANKNIFTY)

| day | futures minutes (of 375) | thin minutes (<20 s) | gap rows >= 5 s | stale | footprint minutes | spot minutes | option strikes |
|---|---|---|---|---|---|---|---|
| 2026-10-15 | 375 / 375 | 1 / 1 | 3 / 3 | 0 / 0 | 374 / 374 | 194 / 375 | 13 / 11 |
| 2026-10-16 | 373 / 373 | 10 / 9 | 8 / 8 | 0 / 0 | 367 / 357 | 357 / 374 | 10 / 9 |
| 2026-10-19 | 372 / 372 | 11 / 8 | 24 / 22 | 0 / 0 | 371 / 369 | 357 / 375 | 11 / 9 |
| 2026-10-21 | 373 / 373 | 5 / 3 | 15 / 16 | 0 / 0 | 373 / 373 | 375 / 375 | 14 / 17 |
| 2026-10-22 | 375 / 375 | 1 / 1 | 1 / 1 | 0 / 0 | 374 / 371 | 375 / 375 | 9 / 11 |
| 2026-10-23 | 356 / 357 | 24 / 23 | 22 / 20 | 0 / 0 | 354 / 355 | 359 / 359 | 13 / 15 |
| 2026-10-26 | 371 / 371 | 7 / 7 | 28 / 23 | 0 / 0 | 371 / 371 | 374 / 374 | 12 / 11 |
| 2026-10-27 | 372 / 372 | 9 / 7 | 15 / 15 | 0 / 0 | 371 / 370 | 374 / 374 | 8 / 11 |
| 2026-10-28 | 374 / 373 | 8 / 8 | 21 / 20 | 0 / 0 | 372 / 370 | 375 / 375 | 10 / 12 |
| 2026-10-29 | 369 / 369 | 9 / 9 | 9 / 11 | 0 / 0 | 367 / 366 | 370 / 370 | 11 / 13 |
| 2026-10-30 | 375 / 375 | 0 / 0 | 1 / 1 | 0 / 0 | 375 / 375 | 375 / 375 | 9 / 9 |
| 2026-11-02 | 351 / 351 | 33 / 33 | 24 / 29 | 1 / 0 | 342 / 342 | 356 / 356 | 8 / 9 |
| 2026-11-03 | 374 / 374 | 4 / 4 | 16 / 16 | 0 / 0 | 373 / 373 | 375 / 375 | 10 / 10 |
| 2026-11-04 | 338 / 338 | 42 / 41 | 19 / 16 | 0 / 0 | 333 / 330 | 344 / 344 | 9 / 9 |
| 2026-11-05 | 371 / 371 | 10 / 9 | 9 / 9 | 0 / 0 | 370 / 370 | 375 / 375 | 9 / 15 |
| 2026-11-06 | 319 / 319 | 62 / 61 | 11 / 11 | 0 / 0 | 315 / 313 | 324 / 324 | 16 / 20 |
| 2026-11-09 | 293 / 293 | 91 / 88 | 12 / 8 | 0 / 0 | 281 / 282 | 302 / 302 | 9 / 12 |
| 2026-11-11 | 340 / 339 | 49 / 47 | 23 / 20 | 0 / 0 | 334 / 332 | 346 / 346 | 11 / 14 |
| 2026-11-12 | 375 / 375 | 1 / 1 | 0 / 0 | 0 / 0 | 375 / 375 | 375 / 375 | 9 / 10 |
| 2026-11-13 | 346 / 346 | 35 / 35 | 18 / 20 | 0 / 0 | 344 / 342 | 351 / 351 | 9 / 9 |

## Levels used (prior day's futures volume profile, shifted by the change in basis)

| day index | POC | VAH | VAL | PDH | PDL | shift |
|---|---|---|---|---|---|---|
| 2026-10-16 NIFTY | 22954.3 | 23059.3 | 22879.3 | 23074.3 | 22654.3 | -20.65 |
| 2026-10-16 BANKNIFTY | 55932.0 | 56202.0 | 55762.0 | 56208.0 | 55482.0 | 22.0 |
| 2026-10-19 NIFTY | 23129.0 | 23154.0 | 23074.0 | 23154.0 | 22915.2 | 4.0 |
| 2026-10-19 BANKNIFTY | 56026.2 | 56056.2 | 55896.2 | 56136.2 | 55817.4 | 116.25 |
| 2026-10-21 NIFTY | 22893.0 | 23068.0 | 22868.0 | 23178.4 | 22864.2 | -2.05 |
| 2026-10-21 BANKNIFTY | 55553.7 | 55703.7 | 55503.7 | 55942.7 | 55446.7 | -56.35 |
| 2026-10-22 NIFTY | 22823.5 | 23108.5 | 22758.5 | 23213.5 | 22741.0 | 78.5 |
| 2026-10-22 BANKNIFTY | 55044.6 | 55614.6 | 54934.6 | 56082.6 | 54944.8 | 134.6 |
| 2026-10-23 NIFTY | 22760.2 | 22785.2 | 22645.2 | 22839.5 | 22580.2 | 25.15 |
| 2026-10-23 BANKNIFTY | 55010.7 | 55220.7 | 54880.7 | 55379.7 | 54729.7 | 100.65 |
| 2026-10-26 NIFTY | 22704.7 | 22709.7 | 22549.7 | 22920.9 | 22505.7 | 69.7 |
| 2026-10-26 BANKNIFTY | 54673.9 | 54703.9 | 54383.9 | 55243.7 | 54288.7 | 163.9 |
| 2026-10-27 NIFTY | 22751.9 | 22766.9 | 22616.9 | 22776.9 | 22431.5 | -13.05 |
| 2026-10-27 BANKNIFTY | 54330.6 | 54420.6 | 54100.6 | 54435.6 | 53872.2 | -59.35 |
| 2026-10-28 NIFTY | 22836.2 | 22871.2 | 22771.2 | 22912.9 | 22706.2 | 71.2 |
| 2026-10-28 BANKNIFTY | 54581.8 | 54891.8 | 54391.8 | 55111.8 | 54391.8 | 431.85 |
| 2026-10-29 NIFTY | 22610.3 | 22725.3 | 22545.3 | 22846.1 | 22542.4 | -24.65 |
| 2026-10-29 BANKNIFTY | 54419.2 | 54489.2 | 54029.2 | 54710.2 | 53969.2 | 9.2 |
| 2026-10-30 NIFTY | 22985.6 | 23000.6 | 22840.6 | 22999.6 | 22652.8 | -59.35 |
| 2026-10-30 BANKNIFTY | 55128.9 | 55218.9 | 54698.9 | 55200.7 | 54393.9 | -81.15 |
| 2026-11-02 NIFTY | 23052.3 | 23117.3 | 22977.3 | 23170.2 | 22952.3 | -2.7 |
| 2026-11-02 BANKNIFTY | 54890.4 | 55040.4 | 54800.4 | 55270.4 | 54755.4 | 20.4 |
| 2026-11-03 NIFTY | 23389.6 | 23404.6 | 23304.6 | 23410.8 | 23225.6 | -15.4 |
| 2026-11-03 BANKNIFTY | 55084.8 | 55194.8 | 54934.8 | 55182.7 | 54737.7 | -65.15 |
| 2026-11-04 NIFTY | 23704.2 | 23819.2 | 23679.2 | 23839.0 | 23566.2 | 39.2 |
| 2026-11-04 BANKNIFTY | 55529.8 | 55739.8 | 55479.8 | 55914.8 | 55340.8 | 39.85 |
| 2026-11-05 NIFTY | 23949.6 | 23984.6 | 23854.6 | 23992.6 | 23709.6 | 4.65 |
| 2026-11-05 BANKNIFTY | 55839.9 | 55869.9 | 55589.9 | 56010.3 | 55352.1 | -50.1 |
| 2026-11-06 NIFTY | 24524.6 | 24569.6 | 24319.6 | 24570.7 | 24250.6 | -70.4 |
| 2026-11-06 BANKNIFTY | 56799.6 | 57429.6 | 56629.6 | 57851.0 | 56530.2 | -110.4 |
| 2026-11-09 NIFTY | 24490.9 | 24875.9 | 24435.9 | 25039.6 | 24371.0 | -14.1 |
| 2026-11-09 BANKNIFTY | 57542.4 | 58532.4 | 57332.4 | 59014.0 | 57327.6 | -7.55 |
| 2026-11-11 NIFTY | no levels (prior day < 300 footprint minutes) | | | | | |
| 2026-11-11 BANKNIFTY | no levels (prior day < 300 footprint minutes) | | | | | |
| 2026-11-12 NIFTY | 25334.4 | 25389.4 | 25279.4 | 25398.6 | 25124.3 | -60.6 |
| 2026-11-12 BANKNIFTY | 60551.4 | 61061.4 | 60401.4 | 61245.4 | 60112.2 | -198.6 |
| 2026-11-13 NIFTY | 25327.7 | 25372.7 | 25262.7 | 25432.7 | 25242.7 | 12.7 |
| 2026-11-13 BANKNIFTY | 61378.9 | 61448.9 | 61168.9 | 61485.5 | 60910.7 | 48.9 |

## Run log

- 2026-11-15T09:03:21Z: 116 trades; PREREG sha256 d311378b3cf6
- 2026-11-15T09:05:16Z: 116 trades; PREREG sha256 06826bc3aa0e
