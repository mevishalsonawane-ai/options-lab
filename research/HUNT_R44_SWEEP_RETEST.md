# R44: an independent re-test of Boss's 5-year sweep (7 strategies), 14 Oct 2026

Boss (14 Oct): "Check the above strategy and provide me results". Boss's own workspace ran a sweep (`sweep.py`, not in
this repo). It reported that 0 of 14 strategy-side combinations made money, either in-sample (2021-2023) or
out-of-sample (2024-2026). It also reported that the "October champion" EMA8/21 + ADX15 made +Rs 12,192 in Sep-Oct 2026
but lost Rs 40,109 in Jan-Aug 2026. R44 re-ran the same 7 strategies with **our own code and our own data**, exactly
as specified, to see whether we get the same answer.

**Signal source: every rule reads its signal from the underlying**, meaning the 5-minute index candle (NIFTY,
BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX) or the CRUDEOIL futures price. The rule then buys the ATM option. Stops,
targets and locks are set on the option premium. No rule reads an option's own chart.

Pre-registered in `research/hunt/r44/PREREG.md` (commit 65ce8f46) before any profit or loss was computed. Two
amendments, both also made before any P&L: the signal and the entry must fall in the same session, and the CRUDEOIL
expiry-day thresholds were adjusted. Code, tests and results are in `research/hunt/r44/`. All tables are generated in
`research/hunt/r44/results/report.md`.

## Verdict in one paragraph

**Our numbers agree with Boss's sweep: none of these strategies makes money after costs.** Out-of-sample (2024 to
12 Oct 2026), all 14 strategy-side combinations lose with Boss's Rs 95 cost, just as their sweep found. They also lose
with our real charges plus slippage. Not one of our 112 cells (strategy x index x side x session) passes the
pre-registered gates. In-sample (2021-2023) we found 2 combinations slightly positive where Boss found none: EMA9/21 +
ADX20 calls (+Rs 24,878 on 916 trades) and EMA9/26 + ADX20 calls (+Rs 17,134 on 751 trades). Both lose out-of-sample
(-Rs 1.69 lakh on 2,042 trades and -Rs 1.78 lakh on 1,745 trades). The "October champion" fits Boss's picture too: a
short good patch in Sep-Oct 2026 and losses over Jan-Aug 2026. Nothing goes to paper.

The one real difference: **Boss's sweep has about 2x our trades in every comparable cell, and about 4x in total.**
The most likely reason is that their sweep counted both the WEEK and the MONTH option series. Those lake files are
byte-identical for BANKNIFTY, FINNIFTY and MIDCPNIFTY from Nov 2024 on. Our per-session counts for WEEK + MONTH
together come within 7-17% of theirs on three of the four NSE cells we checked (details below). Their loss per trade is also
larger than ours. Without `sweep.py` we cannot pin down the rest of the gap, but it does not change any verdict.

## What was tested (Boss's specification, nothing tuned)

- 5-minute index bars, built from the lake's 1-minute index data. Indicators run continuously across days with no
  look-ahead. A test checks that cutting off the future does not change any past signal.
- Signals: EMA uses adjust=False, and ADX and RSI are Wilder's (14 bars). `crossover(a, b)` means a > b now and
  a <= b on the bar before.
  - (1) `ema821_adx15`: EMA8 crosses EMA21 with ADX > 15.
  - (1b) the same rule plus a stepped profit lock. When the peak reaches +25 / +50 / +75 / +100%, the lock moves to
    entry / +25 / +50 / +75%. The lock is pre-registered.
  - (1c) a sensitivity run: the same lock without the +50% target, so that every lock step can bind.
  - (2) `ema921_adx20`: EMA9/21 with ADX > 20.
  - (3) `ema926_adx20`: EMA9/26 with ADX > 20.
  - (4) `rsi_fade`: buy CE when RSI crosses below 30, PE when it crosses above 70.
  - (5) `rsi_momo`: buy CE when RSI crosses above 55, PE when it crosses below 45.
  - (6) `donchian20`: a close beyond the high or low of the prior 20 bars.
- Instrument: the ATM option at the entry bar (Dhan offset 0), from the nearest-expiry series only (WEEK). The MONTH
  twin files are never read, so nothing is counted twice.
- Entry at the next 5-minute bar's open.
- Exits:
  - stop at -25% (checked on the bar low) and target at +50% (bar high); if both are hit in one bar, the stop fills
    first;
  - time exit at the close of the 12th bar (60 minutes);
  - square-off at the open of the 15:15 bar (23:15 for CRUDEOIL).
- Max 2 entries a day per side, with one position open per side at a time.
- Expiry days are skipped (the contract dies that day). A version that keeps them is reported separately.
- Lots: NIFTY 75, BANKNIFTY 30, FINNIFTY 40, MIDCPNIFTY 75, SENSEX 20, CRUDEOIL 100. Lot sizes in force on each date
  were also run as a check.
- Costs:
  - **Boss's**: Rs 95 a round trip (NSE/BSE) or Rs 200 (MCX), on raw fills.
  - **Real**: our slippage model (+-5 bps, plus a half-spread of 1, 2 or 4 ticks depending on the contract's volume,
    doubled on stops) and the charges in force on the trade date. CRUDEOIL uses Zerodha MCX charges.
  - Real costs averaged Rs 22 a trade cheaper than Rs 95 in-sample and Rs 9 cheaper in OOS design. In the 2026
    holdout they were Rs 4 dearer, after the STT rise. Neither cost model turns a losing rule into a winner.
- CRUDEOIL: the lake has no continuous futures minutes, so the signal comes from the futures price carried in the
  option file (the option's underlying). The data covers Aug 2025 to Oct 2026, so CRUDEOIL is out-of-sample only. Two
  variants were run: morning (entries 09:05-14:55) and full session.
- Statistics:
  - For each cell, 2,000 random-entry "twins": the same days, the same number of trades a day, random 5-minute
    entry bars, and the same exits.
  - p is the share of twins that did at least as well as the rule.
  - Benjamini-Hochberg is applied across all 112 cells.
- Periods:
  - In-sample 2021-2023 and out-of-sample 2024-2026, as in Boss's sweep.
  - Our locked holdout (27 Apr - 12 Oct 2026) was read once, after the design numbers were committed. Opening it did
    not move any design number.

## The headline numbers

Sample sizes are in trades. NSE/BSE totals cover all 5 indices, with expiry days skipped and current lots.

| period | NSE/BSE trades (7 strategies) | net, Boss costs | net, real costs | cells positive (real) |
|---|---|---|---|---|
| In-sample 2021-2023 | 22,443 | -Rs 31.66 lakh | -Rs 26.61 lakh | 11 of 70 |
| Out-of-sample design, 1 Jan 2024 - 13 Apr 2026 | 33,801 | -Rs 36.75 lakh | -Rs 33.59 lakh | 7 of 70 |
| Locked holdout, 27 Apr - 12 Oct 2026 | 7,453 | -Rs 1.99 lakh | -Rs 2.27 lakh | 35 of 70 |
| Boss's OOS, 2024 - 12 Oct 2026 | 41,766 | -Rs 39.24 lakh | -Rs 36.42 lakh | 10 of 70 |
| CRUDEOIL, Aug 2025 - 12 Oct 2026 (both session variants) | 10,203 | -Rs 20.40 lakh | -Rs 16.92 lakh | 2 of 28 |

Strategy-side combinations, summed over the 5 indices (and CRUDEOIL morning in the OOS period), with Boss's costs:

| combination | in-sample 2021-23 | out-of-sample 2024-26 |
|---|---|---|
| ema821_adx15 CE / PE | -66,317 (1,670) / -3,52,302 (1,624) | -2,90,049 (3,389) / -3,00,219 (3,338) |
| ema821_adx15_plock CE / PE | -71,816 (1,670) / -3,59,270 (1,624) | -3,06,673 (3,389) / -2,98,871 (3,338) |
| ema921_adx20 CE / PE | **+24,878 (916)** / -2,31,359 (875) | -1,68,897 (2,042) / -1,12,124 (1,917) |
| ema926_adx20 CE / PE | **+17,134 (751)** / -1,97,882 (737) | -1,77,765 (1,745) / -38,743 (1,623) |
| rsi_fade CE / PE | -1,85,247 (1,075) / -2,71,086 (1,282) | -6,53,553 (2,350) / -5,22,164 (2,476) |
| rsi_momo CE / PE | -3,51,935 (2,777) / -5,11,401 (2,585) | -5,14,989 (5,367) / -4,51,521 (5,247) |
| donchian20 CE / PE | -2,38,032 (2,571) / -3,71,044 (2,286) | -3,83,895 (4,891) / -2,60,752 (4,604) |
| **positive** | **2 of 14** (Boss: 0) | **0 of 14** (Boss: 0) |

The stepped lock (1b) does not help. With the +50% target kept, only its first step (lock at entry after +25%) can
bind, and it turns a few winners into scratches: -Rs 4.91 lakh vs -Rs 4.72 lakh without it (OOS design, real costs,
4,982 trades). Without the target (1c) it comes out about even with the plain rule: -Rs 4.76 lakh in OOS design.

## Side by side with Boss's numbers (out-of-sample 2024 - 12 Oct 2026, Boss's costs)

| cell | Boss: trades / net | ours: trades / net (Boss costs) | ours, real costs | ours incl. expiry days |
|---|---|---|---|---|
| BANKNIFTY ema821_adx15_plock CE | 1,330 / -52,483 | 634 / -6,156 | -13,774 | 716 / -20,794 |
| BANKNIFTY ema921_adx20 CE | 710 / -34,313 | 360 / **+21,140** | +16,098 | 406 / +7,944 |
| FINNIFTY ema821_adx15_plock CE (weekly) | 451 / -33,714 | 659 / -30,532 | -26,241 | 740 / -46,344 |
| NIFTY ema921_adx20 CE | 768 / -58,515 | 363 / -56,103 | -52,954 | 459 / -75,190 |
| CRUDEOIL rsi_fade PE morning | 497 / -2,750 | 221 / -1,15,590 | -1,09,056 | 227 / -1,18,460 |
| Champion family (ema821 + plock), all NSE, 2021-2026 | 42,347 / -86,03,171 | 18,910 / -19,20,807 | -16,84,048 | |

**How they compare.**
- **The verdict agrees on every cell except one.** BANKNIFTY ema921_adx20 CE is positive for us over Boss's full
  OOS window, but it is negative in the pre-holdout part: -Rs 4,659 on 283 trades (Boss costs) and -Rs 6,982 (real).
  Its whole profit came in the holdout months (+Rs 22,521 on 72 trades, real costs). It fails every significance
  gate (q 0.96) and its drawdown is Rs 42,530. Its in-sample result is +Rs 51,863 on 253 trades, but that came from a
  random-twin p of 0.03 with q 0.33. This is not a hidden winner.
- **We have about half Boss's trades** on the BANKNIFTY and NIFTY cells, and also about half on the champion family
  (18,910 vs 42,347). A diagnostic run counted both option series on the pre-holdout period. Boss's trades per
  session:
  - BANKNIFTY ema821_adx15_plock CE: 1.93 vs ours WEEK + MONTH (incl. expiry days) 2.07;
  - BANKNIFTY ema921_adx20 CE: 1.03 vs 1.15;
  - NIFTY ema921_adx20 CE: 1.11 vs 1.30.

  Our single-series rates are about half of those (0.50-1.04). So the likely cause is that Boss's sweep traded both
  the WEEK and the MONTH series. Those files are byte-identical for BANKNIFTY, FINNIFTY and MIDCPNIFTY from Nov 2024,
  which double-counts the same trade. The diagnostic is `research/hunt/r44/diag_series.py`, with results in
  `results/diag_series.json`.
- **Boss's FINNIFTY "weekly" cell has fewer trades than ours** (451 vs 659). FINNIFTY weeklies stopped in Nov 2024.
  If their weekly run only covered the months when weeklies existed, their rate would again be about 2x ours.
- **Total counts:** Boss has 98,761 in-sample and 181,313 out-of-sample trades. We have 22,443 in-sample, and 41,766
  NSE/BSE plus 10,203 CRUDEOIL out-of-sample. The difference is about 4x. Double series explain about 2x of that. The
  rest probably comes from more instruments or session variants in their sweep, which we cannot see.
- **Loss per trade:** Boss's champion family loses about Rs 203 a trade, against our Rs 102. Boss's costs are the same
  Rs 95 in both, so their fills or premiums must be worse than ours. Our fills are the raw next-bar open. A stop that
  gaps through fills at the bar's open (worse than the stop price), and the target fills at its price.
- **CRUDEOIL rsi_fade PE morning:**
  - Boss loses only Rs 5.5 a trade after their Rs 200 cost, which means about +Rs 1.94 a barrel before costs. We lose
    Rs 3.35 a barrel before costs (125 OOS-design trades, mostly 60-minute time exits; median entry premium Rs 184).
  - Boss's trade count is 2.2x ours. The same signal on a different data source (their futures minutes, against our
    option file's underlying, which switches contract at each roll) or a different lot multiplier could explain the
    gap. We cannot check either without `sweep.py`.
  - Both versions lose money, so the verdict is the same.

## The "October champion" EMA8/21 + ADX15

| window | ours, all 5 NSE indices CE + PE | ours, BANKNIFTY CE + PE | Boss |
|---|---|---|---|
| Sep-Oct 2026: the last 25 sessions, 4 Sep - 12 Oct | 232 trades, +Rs 603 (Boss costs), +Rs 923 real | **46 trades, +Rs 6,410 (Boss costs)**, +Rs 5,646 real | 46 trades, +Rs 12,192 |
| Jan-Aug 2026 | 1,515 trades, -Rs 31,512 (Boss costs), -Rs 39,051 real | 306 trades, -Rs 1,612 (Boss costs), -Rs 9,801 real | 288 trades, -Rs 40,109 |

Our BANKNIFTY CE + PE trade counts match Boss's champion counts (46 and 306 vs 46 and 288), so their champion was
probably BANKNIFTY on both sides. The shape is the same: a good recent patch, and losses over the eight months before
it. In Sep-Oct only 5 of our 10 index-side cells were positive. CRUDEOIL lost Rs 32,412 on 131 trades in the same
window. 46 trades in 25 sessions is far too small a sample to call the rule good. Over 2021 - 12 Oct 2026, the plain
ema821_adx15 rule lost Rs 8.27 lakh (real costs) on all NSE indices.

## Gates (pre-registered; a cell needs all of them)

The gates are: 100+ trades, a real-cost net above zero in-sample, in OOS design and in the holdout, a Boss-cost net
above zero, BH q < 0.05, max drawdown under Rs 40k, and still positive without its best day.

- **0 of 112 cells pass.**
- Only CRUDEOIL ema926_adx20 CE morning is positive in both OOS design and the holdout (+Rs 12,933 on 65 trades and
  +Rs 12,996 on 54 trades, real costs). It has too few trades and q 0.96. It also has no in-sample years.
- The lowest q values in the family are 0.019, for rsi_fade on NIFTY PE, FINNIFTY CE and MIDCPNIFTY PE. Their entry
  timing beats random bars on the same days, but they still lose money (-Rs 23,513, -Rs 35,832 and -Rs 50,262 in OOS
  design). On those days a random entry loses even more.
- Historical lot sizes do not change any verdict. Six cells would be positive in all three periods with them, but
  each of those fails q (0.83-0.96), the trade count or the drawdown gate.
- Keeping expiry days makes almost every cell worse (for example, BANKNIFTY plock CE goes from -6,156 to -20,794).

## What this means for Boss

1. **Boss's sweep is right in its verdict**: these 7 textbook rules (EMA cross + ADX, RSI fade or momentum, Donchian
   breakout) buying ATM options do not pay after costs on the Indian indices, on any index or side, in any period with
   enough trades. They also fail on CRUDEOIL.
2. **Boss's trade counts are inflated**, most likely by trading both the WEEK and MONTH series (identical files after
   Nov 2024). Their per-trade losses are also bigger than ours. Neither point changes the verdict, but their total rupee
   loss (-Rs 86 lakh for the champion family) is about 4.5x ours (-Rs 19 lakh with the same costs).
3. **The "October champion" was a short lucky patch.** 46 trades in 25 sessions, after 288-306 losing trades over
   eight months, is the typical pattern of noise.
4. **Nothing goes to paper.** No change to the app.

## Files

- `research/hunt/r44/`:
  - `PREREG.md`;
  - `r44cfg.py`, `ind.py`, `r44data.py`, `sim.py`, `engine.py`, `run.py`, `report.py`, `diag_series.py`;
  - `test_r44.py` (25 tests): Wilder RSI and ADX against hand-computed values on tiny series, crossover edges,
    next-bar-open entry, stop before target in one bar, the 60-minute exit, the 15:15 square-off, expiry skip, empty
    and gap days, the lock, the holdout guard.
- `results/`: `design.json`, `holdout.json`, `report.md` (every cell and period), `diag_series.json`, `HOLDOUT_READ`.
- Trades are kept in the cache (`<cache>/r44/trades_*.parquet`), not in git.
- Experiment entries: `research/experiments/entries/r44-*.yaml` (one per strategy id, plus the diagnostic).
