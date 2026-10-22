# R98e pre-registration: the exhaustive hero search with new information, judged for data-snooping

Written 21 Oct 2026, before any number below was computed. Boss: "I want your best to find the details, run it loop
until you find it ... do everything in your power to find it" (after "I have seen it live").

## Why this design

- Searching until something looks good always finds something, because past data contains lucky patterns. And every
  year of the lake has already been looked at in R98-R98d, so there is no virgin holdout left.
- So R98e searches a large, fixed space and then asks the question that matters: **is the best rule better than the
  best that luck alone would produce from that many tries?**
  - Hansen's SPA and White's Reality Check, on daily P&L, stationary bootstrap;
  - the probability of backtest overfitting (CSCV);
  - an anchored walk-forward that picks the rule only from earlier years;
  - the rule's result without its best days.
- **Code reused:** the tests come from `research/obuy/overfit.py` (`spa`, `pbo`), already used by the OBUY study.

## 1. New information (not used by any earlier hero round)

Everything is point-in-time (data up to the decision minute t). The tickets and labels are R98's (`rows.parquet`): every
cheap OTM option (Rs 1-10) every 5 minutes, 11:00-14:55, Rs 5,000, R98 charges.

- **F1 option-chain structure:**
  - **Gamma exposure** GEX(t) = Σ OI·γ·S²·0.01 over the stored strikes. γ is Black-Scholes, from each strike's IV and
    the time to 15:30.
  - **gex_rel:** GEX(t) / GEX(11:00).
  - **gex_side:** the share of GEX on the option's side of spot (calls above, puts below).
  - **wall_em:** the distance from spot to the largest-OI strike on the option's side, in straddles.
  - **wall_oi30:** that wall strike's OI change over 30 minutes.
  - **pin_em:** the distance from spot to the largest total-OI strike, in straddles.
- **F2 demand on the strike:**
  - **resid15:** the option's 15-minute return minus what its Black-Scholes delta explains (buyers paying up).
  - **iv_rel:** the option's IV / the ATM IV.
  - **vshare15:** its share of the same side's chain volume over 15 minutes.
- **F3 breadth** (equity 1-minute data; the NIFTY 50 stocks, and the bank stocks for BANKNIFTY / FINNIFTY):
  - **br_up:** the share of stocks above their day's open;
  - **br_vwap:** the share above their VWAP;
  - **br_chg30:** the 30-minute change of br_up.
  - All three are signed to the option's side.
- **F4 cross-index:**
  - **x_r15 / x_r30:** the mean 15 / 30-minute return of the OTHER four indices, signed;
  - **x_rel30:** own minus others' 30-minute return, signed.
- **F5 events:** us_event (the session after a Fed decision or US CPI) and dom_event (RBI / Budget / election), from
  `events.csv`.

## 2. Part A: does the new information improve prediction?

- R98's walk-forward boosting model (same settings, test years 2023-26), on R98's 40 features against 40 + the new ones,
  for y5 and y10.
- **Reported:**
  - AUC;
  - top-1% precision;
  - the one-ticket-per-index-day mean net (hold, −20% stop);
  - permutation importance of the new features on the test years.

## 3. Part B: the rule search (fixed grid)

- **The variant:** one ticket per index-day, the FIRST row of the day (in time) meeting the condition and the filters.
- **Conditions:**
  - **singles:** 30 features (the 18 new ones and 12 of R98's: f_dist_em, f_r15, f_r30, f_rday, f_pos, f_brk15,
    f_orb, f_stx, f_vix, f_volb, f_oi15, f_iv15), each "≥ its 70th / 80th / 90th percentile" or "≤ its 10th / 20th /
    30th". The percentiles come from DESIGN rows (2021-2023) only;
  - **the control:** no condition;
  - **pairs:** the AND of every pair among the 20 singles with the best design-period mean (each in its best price ×
    window × exit), applied across the whole grid.
- **Filters:** the price at the decision minute (Rs 1-3, 3-6, 6-10) and the window (11:00-12:55, 13:00-14:55).
- **Exits:** hold to 15:20; a −20% stop; a −50% stop; a 10× target with a −20% stop.
- **The P&L:** the daily P&L matrix over every expiry date (0 when no ticket), summed across indices.

## 4. "Found" (all must hold)

1. SPA p < 0.05 (the SPA-best variant beats not trading after the search).
2. PBO < 0.25.
3. The anchored walk-forward (each test year 2023-26 trades the variant with the best total over all earlier years) is
   > 0 in total and in ≥ 3 of the 4 test years.
4. The SPA-best variant is > 0 without its 5 best days.

**If nothing passes:** the report says so, lists the best variants with their honest statistics, and states what a
forward test would need. Nothing goes in the app either way, until a forward paper test confirms it.
