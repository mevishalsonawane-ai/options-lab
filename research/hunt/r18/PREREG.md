# R18 pre-registration: better exits for every arm (TODO I9)

Written 13 Oct 2026 and committed BEFORE any exit variant below was simulated. The only code that had run was
`data.py`, which builds the per-session grids (index, VIX, the option chain); no entry, exit or P&L had been computed.
Nothing here was chosen from results.

Boss (TODO I9): "Better exits: test exits by VWAP band, the next support/resistance level, an order-flow turn, and the
expected range. Measure how much of the profit each one keeps." Order-flow turns need the app's live recording (from 12
Oct, a few days): they are NOT tested here (ARCHITECTURE.md change 5: the order-flow family is shadow-only until 60
recorded days).

## Data (`config.py`, one switch)
- `R18_SOURCE=release` (this run): repo release `research-data` through `research/brain/adapters/release.py`.
  BANKNIFTY 13 Feb 2024 - 23 Feb 2026 (498 sessions), NIFTY 1 Apr 2024 - 13 Apr 2026; 1-minute index + nearest-expiry
  option chain with volume and OI; the expiring chain on expiry days from `expiry_days.parquet` (BANKNIFTY from Feb
  2025, NIFTY from Apr 2025); India VIX 1-minute from `extra_series.parquet`.
- `R18_SOURCE=lake`: the Dhan lake (6 years) through `adapters/lake.py` when it is published. Same code; a NEW experiment
  with its own log entry and its own locked holdout (the lake's last 12 months).
- No Dhan API call is made by anything in R18. Raw data stays in the scratch folder, never in git.

## Periods
- Locked holdout (R16's, last ~6 months of each set): BANKNIFTY from **2025-08-25**, NIFTY from **2025-10-13**. Read ONCE
  by `holdout.py` (a flag file refuses a second run).
- Design = everything before. It is cut into **4 consecutive folds of equal sessions**. No setting is fitted in R18 (every
  variant's numbers are fixed below), so "walk-forward" means: the improvement must hold fold after fold, and nothing
  about the holdout is known when the candidates are chosen.

## Arms (entries as the app trades them today; the TRADE SET is fixed by the arm's own exits)
Every variant manages exactly the trades the arm takes with its own exits (as R12): a variant can only change how a trade
ends, never add or remove trades (an earlier exit does not create a re-entry).

| arm | index | entries (summary of the engine rules) | today's exits (the baseline V0) |
|---|---|---|---|
| ORB | BANKNIFTY | 5-min bar 10:05-13:55 closes beyond the 09:15-10:04 range: above -> ATM CE, below -> ATM PE; strike from the 09:19 close, held all day; premium > 40; at most 1 entry per side a day (paper cap since 13 Oct); re-entry from the bar after the exit | resting stop entry - 40, target + 40, ladder (+10 -> breakeven after charges, +20 -> +10, +30 -> +20), 15:10 |
| ORB Fresh | BANKNIFTY | as ORB, and the previous 5-min bar was not already beyond the range the same way | as ORB |
| ORB Sweep | BANKNIFTY | bar 10:05-14:25: high > range high and close < it -> PE; low < range low and close > it -> CE; at most 2 a day | -40 / +80, ladder on 80, 15:10 |
| Range Fade | BANKNIFTY | bar 10:30-13:55, w = range width: high >= high - 0.1w and close < high -> PE; low <= low + 0.1w and close > low -> CE; at most 2 a day | -40 / +40 with the ladder, 15:10 |
| VIX div NIFTY | NIFTY | checks 10:30/11:30/12:30/13:30 on the minute before: index vs 09:15 open > +0.2% and VIX vs its first minute > +2% -> 1-ITM PE (mirror -> CE); first signal a day | stop floor(0.85 x fill) on the minute low; at the close of the 20th minute exit if under +5%; 15:10 |
| VIX div BANKNIFTY | BANKNIFTY | as above | 15:10 only |
| Hero | NIFTY expiry days | from 12:00 the ATM straddle's low; bars closing 13:30-14:45: straddle >= 1.15 x its low and the 15-min index move >= +0.25% -> CE (<= -0.25% -> PE); nearest OTM priced Rs 1-5; Rs 5,000 ticket; 1 a day | half at 5x, the rest at 20x, close <= 0.4 x entry -> out, 15:05 |
| Pine EMA 9/21 (NIFTY, BANKNIFTY) | both | built-in sample "EMA crossover" on 5-min bars: EMA9 crosses over EMA21 -> ATM CE, under -> ATM PE (switching); its strategy.exit index stop 60 / target 120 points ends the position; entries 09:20-15:00 | Pine auto guard: premium stop 30, target +60, ladder on 60, % trail (breakeven after charges from +5%; keep 50% of the best gain from +8%, 65% from +20%, 75% from +40%; the higher of ladder and trail), opposite signal, 15:15 |
| Pine Supertrend (NIFTY, BANKNIFTY) | both | built-in sample "Supertrend buy/sell" (10, 3) on 5-min bars: direction change up -> CE, down -> PE (switching); entries 09:20-15:00 | as Pine EMA |
| Liquidity BANKNIFTY (STUDY ONLY) | BANKNIFTY | the arm exactly (15-min + 5-min books, pool-on-swing break, 09:20-14:00, room filter, 1-ITM, expiry days skipped; port of `research/liquidity_exits_3060.py`) | index stop level -+ 30, 20-min time stop unless +5%, next liquidity, failed break, new liquidity, SL-M at 0.85 x fill, 15:10 |

Not covered, with the reason: Night R3 (an overnight hold: its exit is the 09:16 open, no intraday path to manage);
Solo midday (needs FINNIFTY's chain and the daily ATR across three indices; the release has no FINNIFTY options);
the shadow rules (they do not trade); Liquidity FINNIFTY / MIDCPNIFTY (parked or no data).

**Liquidity BANKNIFTY is frozen until 26 Oct.** It is studied for information only; whatever the result, nothing in the
app changes for it, not even in shadow. A passing result is written as a proposal for after 26 Oct.

## Exit variants (all fixed now; one setting each)
Every variant KEEPS the arm's own stop, target, ladder, time stops and square-off, and ADDS one exit (or raises the
stop). So each can only exit earlier or lose less: that is the only kind of automatic change the app may make.
Close-based decisions are taken on a minute's close and filled at the next printed minute's open.

| id | name | rule |
|---|---|---|
| V0 | fixed (today's) | the arm's own exits |
| V1 | VWAP band trail | session VWAP of the index's typical price with bands at -2, -1, 0, +1, +2 SD (volume-weighted SD), from 09:30. For a CE: k* = the highest band the index has closed at or above since the entry (k* >= -2; below every band -> -3); exit when a minute closes below band k* - 1 (the band one under the best one reached; -3 SD for k* = -2). PE: the mirror. Volume: the near-month future's (lake); on the release (futures have no volume) the summed volume of the ATM +-2 calls and puts of that minute (a proxy, labelled) |
| V2 | next support / resistance | at entry, the levels: previous day high, low, close; classic pivots P, R1, R2, S1, S2 from the previous day; today's 09:15-10:04 range high and low (after 10:05); today's high and low so far; round numbers (BANKNIFTY every 500, NIFTY every 100). The target is the nearest level in the trade's direction at least 0.25 x E30 (below) beyond the entry's index price. Exit when a minute's index high (CE) / low (PE) reaches it. No level -> no extra exit |
| V3 | time stop | exit at the close of the 45th minute after the entry |
| V4 | expected range used up | E30 = R16's expected next-30-min index range at the entry (the frozen 4-input formula: time-of-day mean log range of the same window over the previous 20 sessions, log range of the last 30 and 60 minutes, log VIX previous close; R16 coefficients). Exit when the index's favourable move since the entry (best high - entry index price for a CE) reaches E30 |
| V5 | breakeven after +15% | once the option's high reaches entry x 1.15, the resting stop rises to the price that nets zero after charges and the spread (from the next minute; it never falls) |

## Fills and costs (as R12)
App paper fills: buy +5 bps, market sell -5 bps, resting stop -10 bps (at the stop or the gap open), plus the h24 real
half-spread 0.16% on the entry and on the exit (NIFTY and BANKNIFTY); Hero: 1 tick a side instead of 0.16% (its Rs 1-5
options). Charges: the app's SandboxCosts (`research/obuy/costs.py`, mode app: Rs 20 an order, STT 0.15% on the sell,
exchange, SEBI, stamp, GST). Stress: 2x the half-spread. 1 lot (Hero: its ticket), the exchange lot of the day.
Within a minute the stop (and any raised stop) is checked first, then the target, then close-based exits.

## Measures (per arm x variant; design and holdout)
Trades, net after charges (Rs), delta vs V0, win %, **% of the maximum favourable excursion captured** (sum of gross
points kept / sum of the best option high reachable between the entry and the arm's square-off, minus entry),
**worst day**, **max drawdown** of the daily equity, charges, and how many trades the variant changed. Every number
carries its sample size.

## Tests and gates (fixed now)
- Test per (arm, variant): the per-day difference variant - V0 over design days with a trade; one-sided (H1: better)
  day-block bootstrap, 10,000 draws. **Benjamini-Hochberg over every arm x variant (12 arm books x 5 = 60 tests: the Pine scripts count once per index; Liquidity
  included)**.
- **Design candidate** (all required): BH q < 0.10; delta > 0 in at least 3 of the 4 design folds; delta still > 0 at 2x
  spread; beats random twins: the trades the variant changed are exited instead at a random minute between the entry
  and the V0 exit, 1,000 sets, twin p < 0.10.
- **Holdout (read once, every arm x variant computed in the one read; only candidates are tests):** a candidate PASSES if
  its holdout delta > 0 and the one-sided bootstrap p, BH-adjusted over the candidates read, is < 0.10. The other rows are
  reported as description only.
- At most one variant per arm can pass: if several candidates pass, the one with the larger design delta.
- A PASS for a non-frozen arm becomes a SHADOW line in that arm's trade log ("variant X would exit at ..."), behind a
  switch that is OFF by default, and changes no exit. Liquidity: a proposal for after 26 Oct. No PASS: today's exits stay.

## Amendment 1 (13 Oct 2026, after a code review; written alongside the re-run it describes)
The text above is unchanged except for the renumbering R17 -> R18 (`research/hunt/r17` is the pre-trade checks study;
this study's files, the switch `R18_SOURCE` and the scratch folder were renamed). Nothing about the variants, arms,
measures or gates changes.

1. **The release holdout is a REUSED holdout.** "Locked holdout (R16's ...)" above: R16 had already read these sessions
   (BANKNIFTY from 2025-08-25, NIFTY from 2025-10-13) before R18 began, and R16's results (its expected-range formula,
   used by V2 and V4) informed this study. So the release holdout is not a clean out-of-sample test; its numbers are
   descriptive and cannot by themselves justify a change. With 0 design candidates no holdout test was run anyway. No
   variant may be favoured for a later study because of how it did in this holdout (that includes V2).
2. **Two replay bugs are fixed, and the design and holdout are re-run once with the same 60 rows:** (a) the ORB family
   and VIX divergence square off at 15:05, not 15:10, when the contract they trade expires that day (as the app does);
   (b) no fill lands after the square-off (`lib.next_open` caps at the arm's square-off; nothing printed by then: the
   last printed price, at the square-off minute). This is a second read of the release holdout, made only to correct the
   bugs; no variant, setting or gate is added or changed.
3. **The lake run gets a FRESH holdout** (`config.py`): every release session up to **2026-04-13** (NIFTY's last;
   BANKNIFTY's ends 2026-02-23) has been examined by R16 and R18, design and holdout alike. For `R18_SOURCE=lake`:
   - design = lake sessions up to 2026-04-13 (4 consecutive folds, as above);
   - embargo = 2026-04-14 .. 2026-04-26 (about 8 sessions), used by neither;
   - holdout = **2026-04-27 onwards**, both indices, read once (its own flag file), and never examined by any study
     before that read. It replaces "the lake's last 12 months" above, which would have overlapped the examined dates.
   If the lake does not reach at least 100 holdout sessions per index when the run is made, the holdout read waits.
