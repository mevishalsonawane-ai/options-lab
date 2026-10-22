# R102 pre-registration: "Deep learn NIFTY and BANKNIFTY" (22 Oct 2026)

Boss (22 Oct 2026): "Deep learn nifty and banknifty." A study of how the two indices behave, written for Boss and for
Jarvis, from the Drive lake (`index_1m`, `index_daily`, `options_rolling_1m`, `participants`, the F&O bhavcopy rows)
plus public contract facts verified online. Research only: nothing in `android/**` or `.github/**` changes.

Written and committed BEFORE any statistic in parts B and C below was computed. Part A (descriptive) has no holdout:
every table there is labelled "descriptive, not a trading rule". Parts B and C are the predictive claims; they follow
the repo's gates (research/data/ARCHITECTURE.md L6): design period, walk-forward (year by year), random twins,
Benjamini-Hochberg across everything tried, one read of the locked holdout.

## Data

* `index_daily.parquet` (NIFTY and BANKNIFTY 2006 -> 19 Oct 2026, INDIAVIX 2007 ->, FINNIFTY / SENSEX / MIDCPNIFTY).
* `index_1m_<SYM>_<YEAR>.parquet`: NIFTY 2017 ->, BANKNIFTY / FINNIFTY / SENSEX / INDIAVIX 2021 ->, MIDCPNIFTY 2022 ->.
* `opt_<UND>_WEEK_<YEAR>.parquet` (ATM-10..+10, 1-minute, nearest expiry; BANKNIFTY's WEEK file is the monthly after
  13 Nov 2024): straddles, skew, IV, theta by hour, OI.
* `participants/oi/oi_<YEAR>.zip` (NSE participant-wise OI, Client / DII / FII / Pro, 2012 ->), `participants/fpi/fpi_daily.csv`
  (NSDL FPI cash flows), `participants/bhav_index/bhav_index_<YEAR>.parquet` (F&O bhavcopy index rows: the expiry
  calendar, OI by strike, PCR, max pain).
* Events: `research/hunt/r98/events.csv` (RBI, FOMC, US CPI, Budget, election; 2021 -> 2026) as R98 listed them.

## Periods

* **Design**: everything up to and including **14 Jul 2026**.
* **Holdout**: **15 Jul 2026 -> 19 Oct 2026**, read once, by `r102pred.py holdout`, which writes
  `results/HOLDOUT_READ` and refuses a second read. The descriptive tables of part A are computed on the design period
  first; the full-lake versions (to 19 Oct 2026) are written only after the holdout read, and they are descriptive.
* Walk-forward: the rules below have no fitted parameters, so walk-forward means the effect is measured in each
  calendar year of the design period separately (a rule passes only if its sign holds in at least 4 of 5 recent years
  2021-2025 for minute horizons, or in at least 70% of the years for daily horizons). Where a threshold is needed
  (participant extremes), it is a trailing-250-day percentile computed from past days only.

## Part A: descriptive (no holdout, "not a trading rule")

Per index (NIFTY, BANKNIFTY) and compared: daily return distribution by year (mean, sd, skew, kurtosis, 1st/99th
percentiles, worst and best days); realised volatility (close-to-close and Parkinson) vs India VIX and vs the 09:20
ATM straddle; the day's range and when it is made (first 15 min, 09:20-10:30, 10:30-13:00, 13:00-14:30, last hour);
the minute of the day's high and low; gap size distribution and gap-fill rates; day-of-week, expiry-day and event-day
ranges; option chain (skew of IV ATM-5 vs ATM+5, PCR of OI, max pain vs close, OI walls, theta by hour on expiry day,
premium path in the first 15 minutes and the last hour); participant positioning levels. Every number carries a
sample size.

## Part B: predictive claims (pre-registered)

Each is a sign rule with no fitted parameter. Effect = mean next return in bps of the index, with its 95% CI, t-stat
(HAC / block bootstrap where returns overlap), the fraction of walk-forward years with the same sign, and 1,000 random
twins (the same rule on a shuffled signal; p = share of twins at least as good). BH across ALL tests of part B and C
(one family, "market-facts"), q <= 0.10 to pass design. A rule that passes design is then read once on the holdout;
it is "confirmed" only if the holdout effect has the same sign and the holdout p (same twin test) <= 0.10.

A rule is reported in bps; it is "tradeable" only if the effect per trade is larger than the cost of expressing it in
an option: the ATM round trip of R16 (NIFTY Rs 98, BANKNIFTY Rs 133 per lot, research/knowledge/baselines.yaml)
plus the 0.16% half-spread of h24, converted to index bps through the ATM delta (about 0.5).

B1. **Trend vs mean reversion by horizon** (12 tests): for h in {1m, 5m, 15m, 1h, 1d, 1w} and each index, the mean
next-h return conditional on the sign of the previous h return, signed to "follow" (momentum). A negative effect means
reversal. Intraday horizons use the 1-minute lake (NIFTY 2017 ->, BANKNIFTY 2021 ->), non-overlapping bars inside the
session only (no bar spans the open); 1d and 1w use `index_daily` 2006 ->.

B2. **Participants predicting the next day** (32 tests): signals from the NSE participant file of day t (published after
the close of t, so usable from the open of t+1): FII net index futures (long - short, as a share of the group's total
index-futures contracts), FII net index options (call long + put short - call short - put long, same share), Client net
index futures, Pro net index futures; each as (a) its level versus the trailing 250-day median (past days only) and
(b) its 1-day change; for NIFTY and BANKNIFTY; horizons next 1 day and next 5 days (close to close). Also NSDL FPI net
cash flow of the latest report (sign) -> next-day return, 2 tests, counted inside the 32 (so 4 x 2 x 2 x 2 = 32 minus the
4 slots the FPI tests replace... no: 36 tests in all: 32 participant tests + 4 FPI tests).

B3. **Calendar rules** (14 tests): next-day sign by weekday (5 per index, 10), expiry day vs other days (2), and the
session after a US CPI release or an FOMC decision taken together as "US event session" (2).

B4. **Gap fade** (8 tests): on a gap at the open (|open / previous close - 1| >= 0.3% and >= 0.6%), the open-to-close
return signed against the gap (positive = the gap fills), per index.

Total pre-registered tests: 12 + 36 + 14 + 8 = **70**, all in one BH family.

## Part C: straddle versus the day (descriptive link to R98, 2 numbers kept)

The share of days whose close-to-open move exceeds the 09:20 ATM straddle price (per index, by year, expiry vs not):
descriptive. No trading rule is derived here; R98 / R89 own the straddle-size question.

## What is NOT claimed

No new arm, no parameter search, no option-price-chart signal. Signals are on the underlying (CLAUDE.md). Nothing here
changes paper or live trading. Numbers for Jarvis's facts file are taken from part A and the confirmed rows of part B
only, each with its sample size.

## Checks

`tests/test_r102.py`: loaders on synthetic data, the look-ahead guard (every signal at day t is unchanged when the data
after t is removed), the holdout lock, BH, and the facts-file schema. `python -m pytest research/brain/tests -q`,
`python -m research.brain.features catalog --check`, `python -m research.experiments.validate`, the secret grep.

## Amendment 1 (22 Oct 2026, after the first design run, before the holdout)

Disclosed, not hidden:

1. **B4 count.** The text above describes 4 gap tests (2 thresholds x 2 indices) but counts 8. The first design run
   computed the pooled 4 (fade any gap): NIFTY +4.7 bps (>= 0.3%, n 1,863, twin p 0.067) and +8.9 bps (>= 0.6%, n 743,
   p 0.038), BANKNIFTY -0.1 and -0.8 bps (p 0.96 / 0.85); none passed BH. The 8 tests are now gap UP and gap DOWN
   separately (2 thresholds x 2 sides x 2 indices), which is what the count meant. The pooled numbers stay recorded
   here so the split is not mistaken for a search.
2. **B1 twins.** The first run shuffled the previous-bar sign inside each day. With only 5 pairs a day at the 1h
   horizon a twin can receive the sign of the bar's OWN return (look-ahead), which made every 1h twin "better" than
   the real rule (twin p 1.00 at a t of 2.9). All B1 twins now use the same circular shift as B2 (at least 10 rows),
   which keeps both series' autocorrelation and breaks only the link. The B1 effects themselves are unchanged.
3. The B2 wording "32 minus the 4 slots ... no: 36" means 36 tests: 4 participant signals x level / change x 2 indices
   x 2 horizons = 32, plus the FPI cash sign x 2 indices x 2 horizons = 4. Total stays 70.
