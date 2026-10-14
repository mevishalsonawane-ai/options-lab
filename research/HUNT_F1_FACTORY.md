# F1 Strategy Factory: every indicator on the Indian indices, screened and turned into option-buying rules

Written 14 Oct 2026, for Boss's request: "Use gamma exposure too ... and all the other things which could help, like EMA,
VWAP, OI and all other things." This is TODO F1 (Strategy Factory), India part, on the full 2021-2026 Dhan lake.
Research only: nothing in the app changes, nothing is armed.

- **Pre-registration:** `research/hunt/f1/PREREG.md`.
  - Commit `33fc686`, written before any outcome: data, the locked holdout, targets, costs, the screen, rule-building
    and gates.
  - Amendment 1 (`0169252`) and amendment 2 (`2d93ccd`), both before any screen or rule result: they fill gaps, drop
    MIDCPNIFTY and add breadth.
  - The design results and the frozen rule list were committed (`57b704c`) before the holdout was read.
- **Code, tests and tables:** `research/hunt/f1/`. Every table is in `results/tables.md`; also `screen.csv.gz` (every
  test), `screen_by_feature.csv`, `design.json` and `holdout.json`.
- **Feature library:** 47 new point-in-time brain features (`research/brain/features/factory_f.py`; 201 in
  `research/brain/FEATURES.md`). Four breadth features are computed in F1 (`research/hunt/f1/breadth.py`).
- **Big tables on Drive:** `gdrive:IraAlgo-data-lake/derived/f1_factory/` holds the arm outcomes per decision minute,
  the feature rows and breadth (about 200 MB; not in git).
- **Experiment entries:** `research/experiments/entries/f1-*.yaml`, one for the screen and one per rule (21).

## Verdict for Boss

**No indicator rule makes money after costs. 0 of 20 rules pass.** Nothing goes to paper or live.

- **Size of the next move: real, strong information.**
  - The size of the next 30 minutes is predictable from recent volatility, on all four indices: ATR, the last 15-60
    minutes' range, realised vol, VIX, ATM IV and IV rank/percentile, straddle price, Bollinger width, the squeeze.
  - Rank correlation is 0.2-0.37, confirmed on a later split (R16 said the same).
  - It tells you **how much** the market will move, not **which way**.
- **Direction of the next 15/30/60 minutes: tiny information.** Most of it goes the opposite way to what the chart
  indicators "say".
  - After the index has run up (RSI high, MACD histogram up, above Supertrend, above EMA 9/20, top of the Bollinger
    band, last 15 minutes up), the next 15 minutes lean slightly **down**, and vice versa.
  - That is a rank correlation of about -0.05, on all four indices, and it is gone by 60 minutes. It is far too small
    to pay the Rs 100-200 a round trip that an option costs.
  - **The one direction clue that points the same way everywhere: IV skew.** When puts are dearer than calls
    (3 strikes out), the index tends to rise over the next 30-60 minutes.
    - The sign is the same on all four indices in both splits.
    - The size shrank: +0.03 to +0.13 on the fit split, +0.01 to +0.08 on the later selection split.
    - It clears BH only for SENSEX and the pooled 60-minute test.
    - It was never tested as its own rule here (see "Next"). It is a note, not a rule.
- **The things Boss named, one by one** (pooled over 4 indices; "noise" = no reliable direction information):

| indicator | direction (next 15-60 min) | option-buying outcome | move size |
|---|---|---|---|
| EMA 9/20/50/200, crosses, slopes; 5-min EMA 9/21 | noise; the 1-min ones lean slightly against (reversal) | noise | weak |
| VWAP (option-volume weighted; futures VWAP has no data before Jul 2026), bands, anchored VWAP | noise | noise | weak |
| Supertrend (1-min, 5-min), RSI, MACD, ADX/DI, Bollinger %B, Keltner | 15-min reversal only (IC ~ -0.05) | noise | weak (ADX, squeeze: some) |
| Squeeze on / squeeze release (5-min) | noise | noise | some (a squeeze comes before smaller moves) |
| ATR, range expansion, realised vol | noise | **yes, but only "less bad"** (below) | **strong** |
| CPR width / position, pivots, previous-day high/low/close, opening range, inside day, NR7 | noise (CPR, previous-day low: barely) | noise | some (narrow CPR, near yesterday's high: bigger moves) |
| OI build-up type (long/short build-up, short covering, long unwinding), per strike and aggregate | ATM put "long unwinding": barely | a little | weak |
| PCR (OI, volume) and its change | noise | a little | some |
| Max pain distance; call/put OI walls and their shift | noise | noise | some (walls far away = bigger moves) |
| ATM IV, IV rank / percentile, straddle and its change | noise | a little (high IV: options lose less) | **strong** |
| **IV skew** | **small, same sign everywhere (selection IC +0.01 to +0.08)** | a little | weak |
| Futures basis / futures OI change | no data before Jul 2026 | | |
| India VIX level, change, percentile | noise | a little | **strong** |
| Breadth (58 NIFTY/BANK stocks: advancing, above VWAP) | noise | noise | weak |
| Gap, time of day, day of week, days to expiry, event days | noise | expiry day / DTE: a little | time of day: strong |
| GEX proxy (net gamma x OI; gamma near the money) | noise | **BANKNIFTY: gamma near ATM helps a bought call** | some |

- **Why "option-buying outcome: yes" still loses money.**
  - Volatility features do rank the trades: on busy minutes a bought option does better than on quiet ones.
  - But it ranks trades that **mostly lose**. With Boss's exits the average loss is about twice the average win:
    - the lock keeps about Rs 500-1,000;
    - the -15% stop costs about Rs 1,500-2,100 a lot.
  - So "better" is still negative.
  - All 18 screen rules were built from these and all of them lost in design: 7,091 trades, net -Rs 9.9 lakh.
    None was positive in more than 2 of the 4 years.
- **Boss's two classic stacks lost the most.**
  - **C1 (5-min EMA 9/21 + Supertrend + VWAP):**
    - design 3,891 trades, -Rs 2.50 lakh;
    - holdout 2,256 trades, -Rs 2.99 lakh;
    - random entry minutes did better (holdout p 1.000).
  - **C2 (put-writing bias + VWAP):**
    - design -Rs 3.07 lakh;
    - holdout -Rs 3.33 lakh;
    - also worse than random.
  - Following the trend at the minute these line up is buying the top of a small move that tends to snap back
    (the 15-minute reversal above).
- **Holdout** (1 Jan 2023 - 30 Jun 2024, 368 sessions, read once):
  - Two rules were positive:
    - **S2**: BANKNIFTY call when 30-minute realised vol is high, +Rs 15,354 on 228 trades;
    - **S18**: BANKNIFTY call when IV percentile is high, +Rs 12,319 on 158 trades.
  - Both have p 0.02-0.03 against random entry minutes, but **BH q 0.279** across the 20, and both lost in design. Not
    an edge.
- **Boss's lock and trail.**
  - Of 24,290 simulated exits, 51% were the lock and 30% the -15% stop. Only 8 reached the 15:10 square-off.
  - The +Rs 1,000-then-keep-Rs 500 lock closes nearly every winner within the hour, even the "runner" exit with no
    time stop.
  - "Winners cut short" (the option later ran Rs 2,000 a lot further before 15:10) is about a third of trades. That is
    measured with hindsight; R18/R19 already found that no exit overlay keeps more of it.
- **What changes in the app:** nothing.
  - No rule passed, so nothing goes to paper and nothing to live.
  - The factory's paper slots stay empty for India.
- **Sample sizes:**
  - Design (walk-forward, 4 folds): 667 sessions; NIFTY, BANKNIFTY and FINNIFTY from Aug 2021, SENSEX from Jul 2024.
  - Screen: 11,672 tests on 771 / 768 / 760 / 432 sessions with a chain.
  - Rules: 15,508 design trades and 8,782 holdout trades across the 20.

## What was done

1. **Feature library** (Data Brain L4, `research/brain/features/factory_f.py`, tests in
   `research/brain/tests/test_f1_features.py`; `python -m research.brain.features catalog --check` passes).
   - **Indicators:**
     - EMA 9-20 / 20-50 / 50-200 spreads;
     - EMA 9/20 cross age;
     - EMA 20 and 50 slopes;
     - 5-minute EMA 9/21, Supertrend, RSI and MACD;
     - DI spread;
     - Keltner position;
     - squeeze on / run / 5-minute release;
     - ATR 5/50 range expansion;
     - the day's range vs the last 10 sessions.
   - **Structure:**
     - CPR width and position;
     - NR7.
   - **VWAP for an index with no volume:**
     - weighted by the option chain's traded volume, with sigma bands;
     - an equal-weight average anchored at the previous session's open.
   - **Options:**
     - OI build-up type per strike on the same strike: long build-up, short build-up, short covering, long unwinding;
       ATM per side, and shares of the OI change over ATM-5..+5;
     - put-vs-call writing bias;
     - PCR change over 15 minutes and over the day;
     - call/put OI wall distance and shift;
     - straddle change;
     - skew change;
     - a GEX proxy: net and near-the-money gamma x OI.
   - **Market:**
     - futures OI change and build-up type;
     - VIX percentile.
   - **Breadth** from the lake's equity_1m, in F1: share of 58 stocks advancing / above their VWAP, its 15-minute
     change, banks only.
   - Everything else Boss listed already existed in the brain and was reused: EMA levels, the futures VWAP and bands,
     anchored VWAPs, Supertrend, RSI, MACD, ADX, Bollinger, ATR, pivots, the previous day's levels, opening ranges,
     inside day, PCR, max pain, ATM IV, IV rank and percentile, skew, straddle, basis, VIX, gap and the calendar.
2. **A brain fix found on the way.**
   - The brain had no expiry calendar for FINNIFTY, MIDCPNIFTY or SENSEX, so their cubes had **no option chain at all**
     (COVERAGE.md said "with option chain 0").
   - `research/brain/expiry_infer.py` recovers FINNIFTY and SENSEX expiry days from the lake's option prices (R25's
     time-value method). FINNIFTY has 193 expiries; SENSEX has 176, with the weekday changes of 2024-25 visible.
   - MIDCPNIFTY's dates are not reliable: a Friday "expiry" comes 3-4 days before a Monday one. It stays index-only,
     and F1 covers four indices.
3. **Arm outcomes** (`sim.py`, `data.py`).
   - For every 5-minute decision 09:30-14:30, on every design session: buy CE/PE, ATM or 1-ITM, at the next minute's
     open. Exits:
     - **X1**: -15% stop, Boss's lock (from +Rs 1,000 a lot a stop rests Rs 500 under the best price), 30-minute time
       stop unless +5%, out 15:10 (15:05 on expiry day);
     - **X2**: the same without the time stop.
   - Fills and charges:
     - fills 5 bps + 1/2/4 ticks by volume (stops 10 bps, double ticks);
     - the app's charges at today's rates;
     - today's lot sizes (NIFTY 65, BANKNIFTY 35, FINNIFTY 60, SENSEX 20).
   - Inside a bar the stop comes first and the lock uses the best price before the bar.
4. **Screen** (`screen.py`, design only).
   - Rank correlation with the next 15/30/60-minute index return, the 30-minute move size, and the four ATM arm
     outcomes.
   - Direction comes from the fit split (2021-22 + 2024H2); the test is on the selection split (2025 - 13 Apr 2026),
     day-clustered.
   - Few-valued features are tested level by level, plus pairs of the 12 strongest per target.
   - BH over all 11,672 tests.
5. **Rules** (`rules.py`):
   - the 2 classic stacks Boss named, plus 18 taken mechanically from the screen's survivors;
   - walk-forward by year (thresholds from earlier data only);
   - 500 random-entry twins each;
   - then the one holdout read.

## The screen

- 11,672 tests (5 scopes x 8 targets x about 250 features and levels, plus capped pairs), with 2,045 survivors at BH
  q <= 0.10.
- By target:
  - **move size: 825 survivors** (median IC 0.20, best 0.37);
  - 15-minute direction: 378 (median |IC| 0.032);
  - 30-minute: 115 (0.037);
  - 60-minute: 59 (0.052);
  - arm outcomes: 668 (median 0.05).
- With about 6,600 selection-split decision rows per index (26,500 pooled), a correlation of 0.03 is "significant". **Significant is not tradable**:
  option costs need far more.
- Most survivors are the same few facts seen through many correlated features:
  - volatility now -> volatility next;
  - 15-minute reversal after a 1-minute run;
  - IV skew (weak).

Survivors by target and scope (counts):

| target | BANKNIFTY | FINNIFTY | NIFTY | POOLED | SENSEX |
|---|---|---|---|---|---|
| move size (30 min) | 174 | 155 | 163 | 176 | 157 |
| ATM call, X1 | 102 | 7 | 13 | 34 | 37 |
| ATM call, X2 | 62 | 10 | 31 | 21 | 20 |
| ATM put, X1 | 55 | 3 | 54 | 23 | 86 |
| ATM put, X2 | 12 | 5 | 67 | 6 | 20 |
| 15 min direction | 89 | 21 | 78 | 91 | 99 |
| 30 min direction | 5 | 6 | 32 | 43 | 29 |
| 60 min direction | 5 | 2 | 17 | 15 | 20 |

How the chart indicators point for the next move. This is the fit-split rank correlation; it is negative = reversal.

| feature | 15 min, pooled | 30 min | 60 min | share negative (5 scopes x 3 horizons) |
|---|---|---|---|---|
| RSI(14), 1-min | -0.048 | -0.017 | +0.010 | 73% |
| MACD histogram | -0.056 | -0.034 | -0.002 | 93% |
| Supertrend distance | -0.052 | -0.026 | +0.007 | 73% |
| Bollinger %B | -0.056 | -0.031 | +0.001 | 87% |
| close vs EMA 20 | -0.049 | -0.017 | +0.010 | 73% |
| last 15 minutes' return | -0.049 | -0.032 | -0.007 | 93% |
| IV skew (put - call, 3 strikes) | +0.045 | +0.070 | +0.091 | 0% |

The full screen by feature (every feature, every target, pooled IC on the selection split in the fit split's direction,
best q anywhere) is in `research/hunt/f1/results/tables.md` and `screen_by_feature.csv`. Every single test is in
`screen.csv.gz`.

## Every rule: design (walk-forward) and holdout

- Rs per lot, net of charges and slippage; 1-ITM; one entry a side a day; decisions every 5 minutes 09:30-14:30.
- "Cut short": exited by the lock or the time stop, then the option later offered at least Rs 2,000 a lot more before
  the square-off.
- p: against 500 random-entry twins (same days, side, strike and exit; random minute).

Design (walk-forward folds 2022 / 2024H2 / 2025 / 2026 together; 667 sessions):

| id | rule (1-ITM, one entry a side a day) | index | trades / sessions | a day | win | avg win / loss | P&L a day | green days | worst day | max DD | cut short | net | folds 2022 / 24H2 / 25 / 26 | p twins |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | Trend stack: 5-min EMA9>EMA21, 5-min Supertrend up, index above option-volume VWAP (mirror for puts) | all 4 | 3891 / 667 | 5.83 | 52% | 1,013 / -1,233 | -374 | 46% | -21,709 | -281,954 | 1538 | **-249,644** | -87,949 / -162,547 / 16,649 / -15,797 | 0.587 |
| C2 | OI + VWAP: put-writing bias > 0 and index above option-volume VWAP (mirror for puts) | all 4 | 4526 / 667 | 6.79 | 53% | 977 / -1,256 | -460 | 46% | -18,093 | -351,455 | 1884 | **-306,768** | -13,249 / -109,862 / -128,860 / -54,796 | 0.042 |
| S1 | atr_14m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 463 / 667 | 0.69 | 63% | 968 / -2,048 | -110 | 63% | -6,826 | -77,286 | 260 | **-73,478** | -23,528 / -14,282 / -13,744 / -21,924 | 0.639 |
| S2 | rv_30m top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 361 / 667 | 0.54 | 61% | 1,003 / -1,917 | -66 | 61% | -6,237 | -56,751 | 199 | **-43,795** | -224 / -10,145 / -10,522 / -22,904 | 0.453 |
| S3 | atr14_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 462 / 667 | 0.69 | 63% | 982 / -2,051 | -102 | 63% | -6,826 | -71,757 | 247 | **-67,949** | -28,941 / -6,041 / -9,495 / -23,473 | 0.563 |
| S4 | range_15m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 518 / 667 | 0.78 | 63% | 936 / -1,926 | -88 | 63% | -6,826 | -62,207 | 280 | **-58,862** | -32,378 / -8,978 / 7,352 / -24,857 | 0.355 |
| S5 | donchian20_width_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 598 / 667 | 0.90 | 61% | 933 / -1,869 | -143 | 61% | -6,826 | -98,882 | 320 | **-95,074** | -35,150 / -15,775 / -21,034 / -23,115 | 0.629 |
| S6 | range_30m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 422 / 667 | 0.63 | 64% | 956 / -1,806 | -21 | 64% | -6,096 | -33,062 | 231 | **-13,731** | 1,937 / -8,293 / 5,748 / -13,123 | 0.082 |
| S7 | range_60m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 332 / 667 | 0.50 | 58% | 914 / -1,701 | -86 | 58% | -5,701 | -63,380 | 191 | **-57,302** | -35,085 / 2,366 / -24,426 / -157 | 0.669 |
| S8 | gex_near_share + bu_pe_long_unwinding_share top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 547 / 667 | 0.82 | 61% | 910 / -1,978 | -167 | 61% | -6,826 | -117,584 | 311 | **-111,612** | -20,920 / -23,024 / -43,564 / -24,103 | 0.800 |
| S9 | gex_near_share + day_range_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 345 / 667 | 0.52 | 61% | 888 / -2,018 | -129 | 61% | -6,264 | -112,595 | 197 | **-85,873** | 12,789 / -30,832 / -42,444 / -25,387 | 0.902 |
| S10 | bu_pe_long_unwinding_share + day_range_pct top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 339 / 667 | 0.51 | 64% | 867 / -1,577 | -6 | 64% | -4,687 | -22,459 | 188 | **-4,192** | -2,956 / -2,355 / 3,164 / -2,045 | 0.064 |
| S11 | iv_skew_3 + ema20 top quintile (high side) -> CE (net_ce_atm_x1, x1) | all 4 | 341 / 667 | 0.51 | 53% | 1,016 / -1,195 | -8 | 53% | -5,156 | -28,736 | 143 | **-5,184** | -12,038 / 0 / 8,901 / -2,047 | 0.216 |
| S12 | iv_skew_3 + ema50 top quintile (high side) -> CE (net_ce_atm_x1, x1) | all 4 | 336 / 667 | 0.50 | 55% | 1,018 / -1,190 | 10 | 54% | -5,156 | -26,673 | 143 | **6,542** | -5,496 / 0 / 12,112 / -74 | 0.072 |
| S13 | greek_atmm2_delta_c top quintile (low side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 441 / 667 | 0.66 | 62% | 906 / -2,047 | -136 | 62% | -6,826 | -100,781 | 253 | **-90,515** | -32,944 / -25,905 / -9,828 / -21,839 | 0.848 |
| S14 | greek_atmm2_delta_p top quintile (low side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 441 / 667 | 0.66 | 62% | 906 / -2,047 | -136 | 62% | -6,826 | -100,781 | 253 | **-90,515** | -32,944 / -25,905 / -9,828 / -21,839 | 0.868 |
| S15 | lvl_near_count top quintile (low side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 347 / 667 | 0.52 | 63% | 899 / -1,747 | -40 | 63% | -6,207 | -39,307 | 174 | **-26,710** | -12,155 / 6,895 / 2,148 / -23,599 | 0.244 |
| S16 | tod_logrange_60m top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 263 / 667 | 0.39 | 60% | 925 / -2,046 | -103 | 60% | -6,826 | -72,516 | 132 | **-68,708** | -23,430 / -14,951 / -25,456 / -4,871 | 0.922 |
| S17 | range_30m_pct top quintile (high side) -> PE (net_pe_atm_x1, x1) | BANKNIFTY | 422 / 667 | 0.63 | 55% | 982 / -1,661 | -124 | 55% | -4,768 | -90,695 | 212 | **-82,507** | -70,949 / -10,438 / 5,163 / -6,283 | 0.758 |
| S18 | iv_pct_252 top quintile (high side) -> CE (net_ce_atm_x1, x1) | BANKNIFTY | 113 / 667 | 0.17 | 61% | 993 / -2,129 | -38 | 61% | -6,207 | -29,994 | 61 | **-25,129** | 0 / -9,262 / -492 / -15,375 | 0.705 |

Holdout (1 Jan 2023 - 30 Jun 2024; 368 sessions, SENSEX 280; read once, all 20 together; BH over the 20):

| id | trades / sessions | a day | win | avg win / loss | P&L a day | green days | worst day | max DD | cut short | net | p twins | BH q | pass |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| C1 | 2256 / 368 | 6.13 | 45% | 959 / -1,025 | -811 | 41% | -23,829 | -321,333 | 748 | **-298,505** | 1.000 | 1.000 | no |
| C2 | 2224 / 368 | 6.04 | 46% | 956 / -1,073 | -904 | 38% | -19,655 | -350,940 | 789 | **-332,564** | 1.000 | 1.000 | no |
| S1 | 271 / 368 | 0.74 | 57% | 1,103 / -1,573 | -39 | 57% | -4,039 | -51,364 | 116 | **-14,215** | 0.244 | 0.754 | no |
| S2 | 228 / 368 | 0.62 | 64% | 936 / -1,450 | 42 | 64% | -5,246 | -19,564 | 111 | **15,354** | 0.028 | 0.279 | no |
| S3 | 266 / 368 | 0.72 | 56% | 1,089 / -1,567 | -64 | 56% | -4,039 | -55,162 | 114 | **-23,662** | 0.399 | 0.769 | no |
| S4 | 304 / 368 | 0.83 | 55% | 1,156 / -1,523 | -50 | 55% | -4,039 | -50,869 | 121 | **-18,243** | 0.283 | 0.754 | no |
| S5 | 343 / 368 | 0.93 | 52% | 1,078 / -1,432 | -114 | 52% | -4,039 | -77,544 | 143 | **-41,828** | 0.641 | 0.935 | no |
| S6 | 255 / 368 | 0.69 | 56% | 1,085 / -1,494 | -26 | 56% | -3,847 | -45,569 | 113 | **-9,607** | 0.192 | 0.754 | no |
| S7 | 198 / 368 | 0.54 | 52% | 1,006 / -1,515 | -110 | 52% | -4,958 | -44,678 | 89 | **-40,312** | 0.846 | 0.940 | no |
| S8 | 83 / 368 | 0.23 | 52% | 1,040 / -1,946 | -90 | 52% | -4,039 | -34,351 | 45 | **-33,091** | 0.790 | 0.935 | no |
| S9 | 97 / 368 | 0.26 | 55% | 986 / -1,724 | -64 | 55% | -4,039 | -30,188 | 47 | **-23,623** | 0.715 | 0.935 | no |
| S10 | 151 / 368 | 0.41 | 54% | 1,008 / -1,436 | -45 | 54% | -4,039 | -20,959 | 61 | **-16,407** | 0.423 | 0.769 | no |
| S11 | 604 / 368 | 1.64 | 44% | 1,041 / -913 | -96 | 42% | -4,243 | -58,157 | 193 | **-35,474** | 0.277 | 0.754 | no |
| S12 | 603 / 368 | 1.64 | 43% | 1,040 / -906 | -99 | 42% | -7,032 | -59,039 | 193 | **-36,355** | 0.321 | 0.754 | no |
| S13 | 75 / 368 | 0.20 | 57% | 944 / -1,992 | -63 | 57% | -4,039 | -27,169 | 43 | **-23,161** | 0.794 | 0.935 | no |
| S14 | 75 / 368 | 0.20 | 57% | 944 / -1,992 | -63 | 57% | -4,039 | -27,169 | 43 | **-23,161** | 0.743 | 0.935 | no |
| S15 | 189 / 368 | 0.51 | 54% | 1,045 / -1,347 | -29 | 54% | -5,484 | -27,578 | 88 | **-10,575** | 0.339 | 0.754 | no |
| S16 | 147 / 368 | 0.40 | 58% | 1,187 / -1,760 | -22 | 58% | -4,039 | -41,610 | 72 | **-8,269** | 0.317 | 0.754 | no |
| S17 | 255 / 368 | 0.69 | 54% | 977 / -1,409 | -81 | 54% | -5,156 | -37,800 | 101 | **-29,928** | 0.629 | 0.935 | no |
| S18 | 158 / 368 | 0.43 | 59% | 1,207 / -1,538 | 33 | 59% | -4,039 | -22,151 | 75 | **12,319** | 0.022 | 0.279 | no |

Gates (PREREG 6).
- Design needs all of:
  - net > 0;
  - positive in 3 of 4 folds;
  - p < 0.05;
  - at least 40 trades.
- The holdout needs all of:
  - net > 0;
  - p < 0.05;
  - BH q <= 0.10;
  - max drawdown no worse than -Rs 20,000;
  - at least 40 trades.
- **0 of 20 pass the design gates and 0 of 20 pass the holdout gates.**

## Honest notes

1. **The holdout was not virgin data.**
   - 2023 - Jun 2024 is the only block no earlier study used as a locked holdout.
   - But the H-series, OBUY, R24's mining and R25's design saw it as design data.
   - That could only have helped a rule pass. None did.
   - The F1 holdout is now spent. A new F1 test needs new data, which means the sessions from 13 Oct 2026 on.
2. **The mechanical rule picker was narrow.**
   - Ordering survivors by t-statistic filled the 18 slots almost entirely with BANKNIFTY call-side volatility rules,
     because those had the largest t.
   - The cleaner direction clue (IV skew alone) never got a slot of its own.
   - S11/S12 paired skew with a raw EMA level in index points. A level is not stationary, so those pairs are partly
     spurious. The PREREG said "every feature", so they stayed.
   - S13 and S14 are the same rule: the call and put delta of one strike move together.
3. **Thin contracts.** FINNIFTY's 1-ITM option fills on only 60% of decision minutes (BANKNIFTY 99%, NIFTY 100%).
   After Nov 2024 FINNIFTY and BANKNIFTY have monthly expiries only, so their nearest contract is a monthly.
4. **Missing or limited data.**
   - Futures VWAP, basis and futures OI exist only from Jul 2026 (the lake's live futures), so they are untested.
   - Breadth uses today's 58 stocks (survivorship).
   - GEX is the brain's proxy, not R28's feature (R28 had not pushed it; R29's GEX pre-registration is on
     `claude/r26-oi-wall-blast`).
5. **A crash during the one holdout read.**
   - The holdout step crashed in the reporting code after the tables were built, before any result was computed into a
     file or shown.
   - It was fixed (a date-filter bug; `test_period_days_with_and_without_bounds`) and resumed with the same frozen
     computation. No holdout number was seen twice.
6. **Effect sizes against costs.**
   - A 15-minute rank correlation of 0.05 means the top fifth of signals moves the index about 1-2 basis points more
     than average.
   - An ATM option round trip costs Rs 100-200 a lot: about 3-6% of the expected 30-minute move (R17), and much more
     than 1-2 bp.

## Next (not done; each needs its own pre-registration)

- **IV skew as a direction lean.** It is the only direction clue with the same sign on every index; it was weaker on the
  later split.
  - It can only be tested on new data now: run it in SHADOW from 13 Oct 2026, behind a switch that is off by default.
  - It never touches paper or live orders until it passes a fresh holdout.
- **The size forecast is the brain's real edge** (R16, here again). It belongs in sizing and in choosing between
  "sit out" and "trade" (risk-lowering), not in picking direction.
- **Brain follow-ups:**
  - breadth into the brain as an adapter (the pending features);
  - a MIDCPNIFTY expiry source;
  - R28's GEX feature when it is pushed.

## Crypto

*Placeholder: the data watchdog adds the crypto part of F1 here (C2 brain, Boss's rule: every crypto trade has a
stop-loss and a profit lock).*
