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
