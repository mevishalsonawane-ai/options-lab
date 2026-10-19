# R86 pre-registration: big players' footprints (participant OI, CFTC COT, stop runs, VWAP algos, dealer gamma, rolls)

Written 19 Oct 2026, committed BEFORE any outcome is computed. Research and knowledge only: nothing in `android/**` or
`.github/**`. Understanding only: nothing here is about moving or manipulating a market.

Boss, 19 Oct: *"Find a way, how the big traders who have the ability to move the market up and down, how do they think
or work, means we will understand how actually the market works, it will be very helpful for our bot."*

Part A (the cited guide) is `research/BIG_PLAYERS_GUIDE.md`. This file fixes Part B: which footprints are tested, on
which data, with which gates. Part C (what the bot should do) is decided only from what passes here.

## 0. What is NOT repeated (already failed; see FINDINGS.md)

- OF1 (14 Oct): z of the ONE-day change of each group's index-futures net, long share, option stance and put writing,
  and FPI cash, as next-day direction signals (36 tests; 15 passed design BH, 0 held on the locked last 12 months).
- OI walls, PCR, max pain, breadth, the GEX proxy as direction (F1, R16, R28, OF1); 75 order-flow rules; move direction
  from any indicator (R16, R30, R41). Move SIZE is predictable (R16, R41); GEX sign splits the rest-of-day range (R28).
- The Liquidity arm trades the BREAK of LuxAlgo-style swing/pool levels on 15-minute bars (R79-R81: entry filters,
  exits). R78 tested a previous-day high/low reversal on XAUUSD (failed). R8 saw "stop hunts" only as anecdotes.
- MCX crude rollover-week trade (R83 E12, failed).

R86 tests only the new angles below: positioning LEVELS and multi-day SHIFTS (not the one-day change), positioning as a
RANGE input, COT managed money on MCX, FAILED pokes (stop runs) at obvious levels, VWAP reversion conditioned on
institutional volume, GEX as an INCREMENTAL range forecast (beyond VIX and the range so far), NSE rollover and MCX
roll-window size.

## 1. Data (point in time; nothing raw is committed)

| source | what | use |
|---|---|---|
| Drive lake `participants/oi` (NSE `fao_participant_oi_DDMMYYYY.csv`, nsearchives.nseindia.com/content/nsccl/) | contracts by Client/DII/FII/Pro, 2 Jan 2012 - 13 Oct 2026 | family P; known the evening of t |
| Drive lake `participants/bhav_index` (NSE F&O bhavcopy, index rows) | NIFTY futures OI per expiry | family E1 |
| Drive lake `index_daily.parquet` | NIFTY, INDIAVIX daily OHLC to 14 Oct 2026 | P, E1 targets |
| Drive lake `index_1m` | NIFTY (2017-), BANKNIFTY (Aug 2021-), INDIAVIX 1-minute to 14 Oct 2026 | S, V, G |
| Drive lake `equity_1m` | 1-minute OHLCV of the 59 current NIFTY 50 + NIFTY BANK stocks, 2019 - 12 Oct 2026 | V (volume proxy) |
| Drive lake `derived/r28_gex` | R28's per-minute dealer GEX, 5 indices, Aug 2021 - 12 Oct 2026 | G |
| Drive lake `fut_daily_continuous.parquet` | MCX GOLDM, SILVERM, CRUDEOIL, GOLD, SILVER near-month continuous daily | C, E2 |
| CFTC Disaggregated Futures-Only history (cftc.gov/files/dea/history/fut_disagg_txt_*.zip), downloaded 19 Oct 2026 | weekly positions by category | family C |

Look-ahead rules: a participant file of date t is used for a decision at the open of t+1 or later. A COT report with
"as of" Tuesday d is used only from the open of the first MCX session on or after d + 7 calendar days (the Friday
15:30 ET release is Saturday about 01:00 IST; +7 also covers a one-day US-holiday delay). COT reports dated inside the
US government shutdowns and their catch-up periods are not used at all: 24 Sep - 29 Oct 2013, 18 Dec 2018 - 5 Mar
2019, 23 Sep 2025 - 31 Jan 2026. Minute rules decide on a completed minute and fill on the next minute's open.

MCX roll: the continuous series switches contract at expiry. A roll day is a day whose open interest is at least 1.5x the
previous day's (the new near month carries the old next month's OI) and that is at least 15 sessions after the last
roll (checked on OI only before any outcome: about 11.6 rolls a year GOLDM, 5.0 SILVERM, 11.2 CRUDEOIL). On a roll day the overnight gap (previous close -> open) mixes two contracts, so the adjusted daily return is the
roll day's open -> close only. Every MCX price path and P&L uses this back-adjusted series. Stated loss: the true
overnight move on roll days (about 6-12 days a year) is dropped.

## 2. Periods

| family | design (walk-forward folds inside it) | final (read once, only for design passers) |
|---|---|---|
| P, C, E1, E2 (daily/weekly) | 2012 - 31 Dec 2022; folds = years 2015..2022, each fitted on 2012..Y-1 | 1 Jan 2023 - 14 Oct 2026 |
| S, V (minutes) | data start - 31 Dec 2024; folds = years 2022, 2023, 2024 | 1 Jan 2025 - 14 Oct 2026 |
| G (GEX) | Aug 2021 - 31 Dec 2023; folds = 2022, 2023 (fitted on everything earlier) | 1 Jan 2024 - 12 Oct 2026 |

Caveats stated now: OF1 read the participant data's last 12 months (Oct 2025 - Oct 2026) for different signals; R28
described the rest-of-day range by GEX sign on 2023 and Apr 2025 - Oct 2026. So those finals are weak for these
families. The final for a family is read by `r86study.py final` ONCE (guard file `results/FINAL_READ`), only for the
tests that pass section 5's design gates. If none pass, no final is read.

## 3. The 23 tests (one BH family)

Signal source of every rule: the underlying (NIFTY / BANKNIFTY index, MCX future) or exchange/regulator positioning
data about the underlying. No rule reads an option's own price chart. Trades are on the underlying's future
(CLAUDE.md allows "or trades the future"); an option version is built only for a pass.

### Family P: NSE participant-wise OI (NIFTY)

FII long share `L_t = fut_idx_long / (fut_idx_long + fut_idx_short)` (FII row, the evening of t). Percentile `pct(x)`
= rank of today's value among the last 250 values including today (at least 200, else no signal).

| id | signal (evening of t) | trigger | target / rule |
|---|---|---|---|
| P1 | `pct(L_t)` (FII positioning extreme) | pct <= 0.10 or >= 0.90 | NIFTY 5-day trade (below) |
| P2 | z of `L_t - L_{t-5}` over its last 250 values (sharp 5-day shift) | abs z >= 1.5 | NIFTY 5-day trade |
| P3 | `pct(O_t)`, FII option stance `O = (callL - callS - putL + putS) / (callL + callS + putL + putS)`, index options | pct <= 0.10 or >= 0.90 | NIFTY 5-day trade |
| P4 | `pct(W_t)`, Client net index-option writing `W = (callS + putS - callL - putL) / (sum)` | every day | NIFTY next-5-day range (forecast test) |
| P5 | `abs(pct(L_t) - 0.5)` (FII crowding either way) | every day | NIFTY next-5-day range (forecast test) |

**5-day trade (P1-P3).** Side = sign(signal - centre) x the walk-forward sign, where the sign is that of the Spearman
correlation between the centred signal and the open(t+1) -> close(t+5) NIFTY return on all days of the fitting years
(centre 0.5 for percentiles, 0 for z). Entry: NIFTY open of t+1; exit: close of t+5; stop 1.5 x ATR14 (daily, known at
t) from the entry, on the daily low/high, filled at the stop or at the open if gapped through. One position at a time.
Instrument: the NIFTY near-month future, priced as the index plus the futures cost model: brokerage Rs 40 a round trip,
STT on the sell leg (0.01% to 31 Mar 2023, 0.0125% to 30 Sep 2024, 0.02% after), exchange 0.00173%, SEBI Rs 10/crore,
stamp 0.002% on the buy leg, GST 18%, slippage 0.02% a side (stops 0.04%), carry 5.5% a year of the lot value paid by
longs and received by shorts; 1 lot in force (LOT_SCHEDULE).

**Range forecast (P4, P5; also G1, G2).** Target `y = log(range %)`. Baseline model B: OLS on log VIX close of t,
log mean daily range (high-low / close) of t-4..t, log range of day t. Model B+X adds the feature. Walk-forward: fit on
the fitting years, predict the fold year. Statistic: mean of `e_B^2 - e_BX^2` (> 0 = the feature helps), Diebold-Mariano
t with HAC (lag 5 for 5-day targets), one-sided p. Target P4/P5: `log((max high - min low of t+1..t+5) / open(t+1))`.

### Family C: CFTC managed money on MCX

Markets: COMEX gold (CFTC code 088691) -> MCX GOLDM; COMEX silver (084691) -> SILVERM; NYMEX WTI light sweet (067651) ->
MCX CRUDEOIL price, traded as CRUDEOILM. Managed-money net share `N_w = (MM long - MM short) / open interest`.

| id | signal | trigger | trade |
|---|---|---|---|
| C1-GOLD, C1-SILVER, C1-CRUDE | COT index = `pct(N_w)` over the last 156 reports (at least 104) | <= 0.10 or >= 0.90 | 20-session hold, stop 2 x ATR14 |
| C2-GOLD, C2-SILVER, C2-CRUDE | z of `N_w - N_{w-1}` over the last 156 changes | abs z >= 1.5 | 5-session hold, stop 1.5 x ATR14 |

Side: the same walk-forward sign rule as P (Spearman of the centred signal vs the forward return of the hold, fitting
years). Entry at the open of the first MCX session on/after report date + 7 days. One position at a time per market.
Lots: GOLDM 100 g (quote per 10 g, x10), SILVERM 5 kg (x5), CRUDEOILM 10 bbl (x10). Costs: McxCosts (research/hunt/r82
r82lib.leg_charge) on both legs, slippage 0.03% a fill, 0.10% on stops. ATR on the roll-adjusted series.

### Family S: stop-run footprint (failed pokes at obvious levels), NIFTY and BANKNIFTY 1-minute

Levels each day: S1 = previous session's high and low; S2 = round numbers (NIFTY multiples of 100, BANKNIFTY multiples of
500); S3 = the opening range 09:15-09:29 high and low. Poke above an upper level L: a minute between 09:30 and 14:30
whose high >= L x (1 + 0.0003) while the previous minute closed below L. It FAILS if a minute closes back below L within
10 minutes of the poke minute (the poke minute included); the event is that close. Mirror for lower levels. At most one
event per level per day. Fade trade: entry the next minute's open, opposite to the poke; stop = the poke's extreme
(highest high since the poke for an upper level) +- 0.02% of price; target = 2x that risk; exit at 60 minutes or 15:15.
One open position per index at a time. Futures-equivalent P&L in index points x the lot in force, with the futures
cost model above (BANKNIFTY lot from LOT_SCHEDULE). Tests: S1-NIFTY, S1-BANKNIFTY, S2-NIFTY, S2-BANKNIFTY, S3-NIFTY,
S3-BANKNIFTY (6). Reported beside each: the next-30/60-minute signed move after events vs after random minutes, and
the share of failed vs held pokes.

### Family V: VWAP-algo footprint, NIFTY

Volume proxy: each minute's rupee turnover summed over the equity_1m stocks (close x volume; current constituents, so
survivorship is stated). VWAP_t = sum(typical index price x turnover) / sum(turnover) from 09:15 to t. Early volume
ratio EVR = turnover 09:15-10:14 / median of the same over the previous 20 sessions; a day is HIGH if EVR >= the 67th
percentile of the previous 250 sessions' EVR (at least 120). Stretch at 12:00: `s = (close_11:59 - VWAP_11:59) /
close_11:59`.

- V1 (gated): on HIGH days with abs s >= 0.25%: fade towards VWAP at the 12:00 open, target = VWAP_11:59, stop =
  entry -+ abs(close - VWAP) (1:1), exit 15:15. NIFTY futures-equivalent, cost model above.
- Reported (not gated): the slope of the 12:00 -> 15:15 return on s by EVR tercile; whether EVR at 10:15 predicts the
  full day's turnover ratio (correlation).

### Family G: dealer gamma and the day's range

R28's GEX at the 09:59 minute (`near_net_cr`, `near_norm`). Target: `log(high - low of 10:00-15:29 / close of 09:59)`.
Baseline: log VIX at 09:59, log range 09:15-09:59, log mean rest-of-day range of the previous 20 sessions, an expiry-day
dummy (dte = 0) and a dte = 1 dummy. B+X adds `1[near_net_cr < 0]` and `near_norm` clipped to +-5 (0 when missing).
G1-NIFTY, G1-BANKNIFTY (DM, HAC lag 1). Reported (not gated): a +-0.5 x forecast range bracket from 10:00 and its hit
rate by forecast fifth (stop/target calibration) with and without GEX.

### Family E: expiry and roll flows

- E1 (NIFTY rollover): on the session before each monthly NIFTY futures expiry, rollover `R = OI(next + far) / OI(all
  three)` from the bhavcopy (contracts). Signal = R minus the mean R of the previous 12 expiries (at least 9); trade when
  abs >= 1 standard deviation of those 12. Side: walk-forward sign as P (fitting years). Entry: open of the expiry day;
  exit: close of the next monthly expiry; stop 2 x ATR14. NIFTY future, cost model above.
- E2-GOLD, E2-SILVER (MCX roll window SIZE): the 5 sessions before each detected roll day vs all other sessions; the
  statistic is mean abs adjusted daily return inside / outside. Permutation p: the same number of 5-session windows a
  year placed at random (500). Gated as a size fact: ratio > 1 with p < 0.10, or ratio < 1 with p < 0.10 (two-sided,
  the sign fixed by design), and the same side in the final.

## 4. Random twins (seed 86, 500 each)

- P, C, E1 trades: the same number of entries on random days of the same fold years (one position at a time kept), the
  rule's sides in a random order, the same hold and stop rules.
- S: the same day, a random minute 09:31-14:40, the same side and the same stop distance in % and 2R target.
- V1: the same number of days drawn from all days with abs s >= 0.25% (any EVR), the same fade rule.
- P4, P5, G1, G2 forecasts: the added feature permuted across days within each year; the statistic re-computed.
- E2: as in section 3.
- p = (1 + #twins >= the test's statistic) / 501, statistic = walk-forward net (trades) or MSE gain (forecasts).

## 5. Pass rule (as R84/R85; ALL must hold)

Design (decides whether the final is read):
1. walk-forward pooled folds: net > 0 AND net > the twins' mean (forecasts: MSE gain > 0);
2. twin p < 0.10 and BH q < 0.10 over all 23 tests (p = twin p for trades; for forecasts the larger of the DM p and
   the twin p; for E2 the permutation p);
3. not carried by the top 5: walk-forward net without its 5 best trades > 0 (forecasts: gain without its 5 best days > 0).

Final (read once, only for design passers): net > 0 and > the final twins' mean, net without its top 5 > 0 (forecasts:
gain > 0 and gain without its 5 best days > 0; E2: ratio on the same side as design).

Every number is given with its sample size. Labels in Part C: **proven** = passes design and final; **not proven** =
anything else (including design-only passes).

## 6. Tick recordings (idea 7)

The phone's recordings live in the private repo `mevishalsonawane-ai/IraAlgo`. If they are not reachable from this
session, idea 7 is not run and the write-up says so (OF1 found 0 releases there on 14 Oct).

## 7. Tests and logging

`research/hunt/r86/test_r86.py` (pytest): participant parsing and features on hand-made rows; percentile and z with too
little history (no signal); COT availability (>= report + 7 days; shutdown windows dropped); roll detection and the
adjusted return on hand-made series; the stop-run detector on hand-made minutes (a poke that fails, one that holds, a gap
day, a level touched at 14:31 ignored); VWAP and EVR with missing minutes; the trade simulators (stop first, gap through
the stop, time exit, costs); BH; a look-ahead guard for every signal (rows after the decision time deleted or perturbed
leave the decision unchanged). One `research/experiments/entries/r86-*.yaml` per idea, failures included; one FINDINGS
section.
