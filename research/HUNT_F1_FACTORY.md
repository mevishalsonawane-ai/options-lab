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

Written 14 Oct 2026 (TODO F1, crypto part). Research only: no app code, no orders, no exchange accounts; nothing in
the app trades crypto. Pre-registration `research/hunt/f1c/PREREG.md` (part A `47c42ff` before any outcome, part B
with the 20 rules `1dc43ba` before any rule P&L, design results `0cc36cf` before the holdout read). Code and tests
`research/hunt/f1c/`; every table `research/hunt/f1c/results/report.md`; experiment entries
`research/experiments/entries/f1c-*.yaml` (20 rules, the screen, the brain refresh). Not financial advice.

### Verdict for Boss

**No. None of the 20 crypto rules makes money after Delta India's fees, slippage and India's crypto tax, in the design
years or in the locked holdout. Nothing is proposed for paper.** Every trade had a stop-loss and a profit lock.

1. **What was tried.** 15 perpetual-futures rules on BTC, ETH, SOL, BNB and XRP, and 5 rules that buy Delta Exchange
   India's rupee BTC/ETH daily options, all traded only inside your 00:00-09:00 IST window and flat by 09:00 IST, each
   with 4 stop/lock variants (80 tests). Account Rs 1,00,000 (also Rs 25,000), 1% risk a trade on perps, at most 2% of
   the account in premium on options.
2. **Design (Nov 2023 - Mar 2025 and Nov 2025 - Mar 2026, 670 window days): all 20 lose.** The least bad: options
   O19 (buy the call/put after a sharp 15-minute burst) lost Rs 16,848 on 911 trades after tax (Rs 25 a day); the worst
   perp rule lost Rs 9.26 lakh on a Rs 1 lakh account (it would have wiped the account out). Every walk-forward is
   negative and every stress run is negative.
3. **Holdout (1 Apr - 30 Sep 2025, read once): all 20 lose too**, from Rs 26,192 (O19, 277 trades) to Rs 2,87,415
   (P15, 1,174 trades). Over the same six months simply holding ETH made about +Rs 87,857 after tax on Rs 1 lakh and
   BTC +Rs 26,312: the market went up, and trading in and out still lost.
4. **Why.** The data does hold a real pattern, and it is the same one C1 found: after a stretched move (far from the
   24-hour VWAP, at the day's high or low, far from the EMA ribbon, after one-sided taker buying) price tends to come
   back a little, more so inside your window. But the rules caught only -13 to +8 bps a trade with it, while a Delta India perp round
   trip costs about 16 bps (BTC/ETH) to 22 bps (alts). The tax then takes 31.2% of every winning trade and gives
   nothing back for the losers, which turns even the two option rules that made money before tax into losses.
5. **Options came closest, and even that is mostly an artefact.** O19 (buy the call/put after a sharp 15-minute burst)
   and O18 (previous-day high/low break) are the only rules that beat random entry times (BH q 0.002), and they made
   money before tax (O19 +Rs 1.42 lakh at design, +Rs 12,245 in the holdout). But a review found the simulator bought
   at the signal minute's last trade price, which on Delta's thin option book is often minutes old (5% of entries more
   than 15 minutes). Re-run on design data with the fill one minute later and no stale prices, **O19 loses Rs 36,230
   before tax and Rs 1.43 lakh after tax (845 trades); O18 loses Rs 11,272 / Rs 62,141 (380 trades)**. After the VDA
   tax both lose either way, their walk-forwards are negative, and at slightly worse costs O19 loses Rs 1.18 lakh. So
   there is nothing here even under the business-income tax reading. (A CA's view on the tax reading still matters for
   any future crypto F&O idea: under reading A, 31.2% of every winner is lost with no set-off.)
6. **TDS makes perps impossible on a small account.** If the 1% TDS applies to every perp sale (as the brief assumes),
   a Rs 1 lakh account trading these rules would have half its money locked as TDS within 10-60 days and Rs 2.4-17.8
   lakh locked at the peak. Options are fine on this point (Rs 3,900-14,100 locked at the peak).
7. **What not to trust.** Funding, long/short ratios, DVOL, option skew, put/call and the time of day carry no
   information about where crypto goes next in this data; basis and open interest showed some on one coin of five only
   (basis on SOL, OI change on BTC and XRP), which is not something to trade. Size, not direction, is predictable.
8. **Honest advice.** For crypto exposure, buy-and-hold or DCA (RC1) remains the baseline nothing here beat. No rule
   here is worth even a watch-only paper log: the closest one (O19) does not survive a realistic entry price.

### Data and the brain refresh

- **Crypto brain refreshed from the Drive lake** (`gdrive:IraAlgo-data-crypto`, its own incremental commands; see
  `research/brain/crypto/README.md`, "F1 refresh"): BTC, ETH, SOL, BNB, XRP, 1 Oct 2023 - 31 Mar 2026, 913 cube days
  each, no gap minutes. Cube v3 adds DVOL for ETH and six option-market columns built from every Deribit option trade
  (ATM IV, put-call skew, 24-hour put/call and taker flow). 14 new features (78 in all): 15-minute and 1-hour EMA
  ribbons, and seven option-market features. Labels add a 15-minute horizon. Look-ahead guard: PASS on synthetic data
  and on the real cube (BTC, ETH, SOL, 40 days, 6 cuts x 2 modes).
- Already present: VWAP (24-hour and day), funding, OI, basis, premium, DVOL, liquidation proxies, time of day, weekday,
  Boss's window. **No liquidation prints exist** in the archive or the lake, so liquidations stay proxies. The lake's
  5-minute OI has a few zero prints (OI change -100% / inf); P13 bounds it.
- Options: Delta Exchange India 1-minute trade candles (options lake), 791 BTC and 755 ETH expiry days prepared for the
  window (strikes within 6% of spot). Perps: Binance USD-M 1-minute bars stand in for Delta India's INR perps.
- Not used: anything from 1 Apr 2026 on (the brain's own lock).

### The holdout choice

| study | holdout it read |
|---|---|
| RC1 | 13 Oct 2025 - 12 Oct 2026 |
| RC2 | 13 Apr - 13 Oct 2026 |
| NN_CRYPTO, STRAD_CRYPTO | 1 Apr - 7 Oct 2026 |
| crypto brain C1 / C2 | 1 Apr 2026 on, locked, not read |

The most recent six months were read, so F1C's holdout is the clean earlier block **1 Apr - 30 Sep 2025** (183 days),
which no earlier study read as a holdout (it was design data for all of them, and their published findings were learned
partly on it). It was committed in PREREG part A before any outcome and read once (`<cache>/f1c/HOLDOUT_READ.json`,
14 Oct 2026 07:23 UTC). Design = Nov 2023 - Mar 2025 and Nov 2025 - Mar 2026 (October 2025 is a 31-day embargo).

### Step 2: the feature screen (design data only)

Spearman correlation of each feature with the next 15 / 60 / 240-minute move, on non-overlapping rows; screen split
Nov 2023 - Sep 2024 (32,160 / 8,040 / 2,010 rows per coin and horizon), selection split Oct 2024 - Mar 2025 and Nov 2025
- Mar 2026 (31,936 / 7,984 / 1,996); BH per family at q < 0.05 (`results/screen.csv`).

| family | direction tests | pass BH | confirmed on the selection split |
|---|---|---|---|
| price (returns, ranges, day position) | 150 | 94 | 69 |
| trend (EMA ribbons) | 105 | 55 | 39 |
| volume (VWAP, taker share, volume) | 90 | 47 | 39 |
| profile (24-hour volume profile) | 60 | 44 | 37 |
| levels (day / previous-day / round levels) | 135 | 47 | 34 |
| derivs (funding, basis, premium, OI, long/short, taker ratio) | 270 | 35 | 16 |
| vol (realised vol, ATR, DVOL) | 93 | 23 | 11 |
| options (skew, put/call, ATM IV, flow; BTC/ETH) | 42 | 1 | **0** |
| calendar (hour, weekday, sessions, Boss's window) | 135 | 10 | **0** |
| **all** | **1,080** | **356** | **245** |

- **Direction: real but tiny, and almost all of it is reversal.** 89% of the 245 confirmed are negative correlations:
  stretched prices come back a little. The same features are about twice as strong inside Boss's window (median
  selection correlation at 60 minutes -0.067 in the window vs -0.034 at all hours; e.g. BTC distance from the 15-minute
  ribbon -0.131, ETH distance from the day's VWAP -0.124, BTC position in the day's range -0.119, ~2,989 window rows
  each). The money in it is small: the median half top-minus-bottom-decile spread is 0.7 bps against a 15.8-21.8 bps
  round trip; 2 of 245 "pay" on paper (XRP over 240 minutes: distance from the day's high, and the last hour's realised
  vol).
- **Noise for direction (never confirmed, any coin or horizon): 38 of 78 features**: funding (bps, annualised, z),
  basis 60-minute mean, z and change, premium, mark premium, long/short ratios, taker long/short ratio, DVOL and its
  change, IV minus RV, every option feature (ATM IV, IV minus RV, skew, skew change, put/call, put/call z, option flow),
  1-day ATR, weekly levels, big round numbers, relative volume, the 1-minute return, the 1-hour ribbon order and all
  calendar features. Confirmed on one coin only (not a pattern to trade): basis level (SOL), OI change 24 h (BTC) and
  60 min (XRP), the liquidation proxies (XRP), minutes to funding (XRP). Only the 60-minute perp taker imbalance among
  the derivatives features is confirmed on all five coins.
- **Size is predictable** (550 of 1,080 confirmed): the last hour's realised vol (median correlation with the next
  move's size +0.39), the last hour's range (+0.35), volume (+0.33), ATR (+0.32), Deribit ATM IV (+0.28). As C1 found.

### Step 3: the 20 pre-registered rules

Perps (all five coins pooled): P01 VWAP-24h stretch fade; P02 day-extreme fade; P03 EMA-ribbon stretch fade; P04 big
hour reversal; P05 taker-exhaustion fade; P06 window-open fade (C2's W4 as a rule); P07 previous-day high/low break;
P08 Asia opening-range (00:00-01:00 IST) break; P09 ribbon-squeeze break; P10 ribbon trend; P11 liquidation-flush fade;
P12 day-extreme 4-hour fade; P13 OI-divergence fade; P14 ribbon-slope fade; P15 consensus reversal. Options (BTC + ETH,
the ATM call or put of the day's 17:30 IST expiry): O16 = P01, O17 = P04, O18 = P07, O19 volatility burst (RC2's R4 in
the window), O20 = P08. Exact thresholds: PREREG section 8 and `rules.py`.

Exits (every trade has both): perps stop 1.0 or 1.5 x the 14-hour ATR with a step lock (+1R locks +0.25R, +2R locks
+1R, ...) or a trailing lock (keep 50% of the open profit from +1R); options stop -40% or -25% with RC2's step or
trailing lock. Costs: Delta India 0.05% taker + 18% GST and 0.02% (BTC/ETH) / 0.05% (alts) slippage per side plus
Binance funding; options min(0.01% of notional, 3.5% of premium) + GST, RC2's spread model, exits less 1 tick + 1%.
Tax reading A (the main number): 31.2% of every winning trade, losers never set off.

### Step 4: every candidate (Rs 1,00,000; P&L after fees, slippage, funding and tax A)

Design, the pick of each rule (its best design exit), walk-forward by quarter (each quarter uses the exit with the best
earlier record; 7 quarters), holdout of the same pick, and the random-twin test (1,500 random same-clock entries per
coin and exit, coin-flip side, same exits; BH across all 80):

| rule | exit (pick) | trades (a window day) | win | avg win / avg loss | P&L a day after fees + tax A | green days | worst day | max drawdown | lock exits (big winners cut short) | design net A | walk-forward net A | holdout trades | holdout net A | twin p | BH q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| P01 | s15_step | 3,105 (4.63) | 42% | +Rs 362 / −Rs 553 | −Rs 1,070 | 32% of 585 | −Rs 12,458 | −Rs 717,290 | 30 (5) | −Rs 717,175 | −Rs 645,379 (0% green) | 886 | −Rs 231,865 | 1.000 | 1.000 |
| P02 | s15_step | 2,809 (4.19) | 46% | +Rs 453 / −Rs 637 | −Rs 911 | 33% of 602 | −Rs 12,778 | −Rs 610,607 | 74 (5) | −Rs 610,193 | −Rs 549,278 (0% green) | 827 | −Rs 229,716 | 0.954 | 1.000 |
| P03 | s15_step | 2,371 (3.54) | 45% | +Rs 405 / −Rs 610 | −Rs 780 | 39% of 507 | −Rs 13,038 | −Rs 523,382 | 28 (5) | −Rs 522,803 | −Rs 469,482 (0% green) | 616 | −Rs 180,279 | 1.000 | 1.000 |
| P04 | s15_trail | 847 (1.26) | 44% | +Rs 336 / −Rs 581 | −Rs 303 | 36% of 298 | −Rs 8,615 | −Rs 203,131 | 22 (0) | −Rs 202,792 | −Rs 185,854 (0% green) | 231 | −Rs 64,100 | 1.000 | 1.000 |
| P05 | s15_trail | 2,535 (3.78) | 33% | +Rs 263 / −Rs 396 | −Rs 832 | 18% of 602 | −Rs 7,662 | −Rs 557,344 | 22 (2) | −Rs 557,344 | −Rs 528,075 (0% green) | 944 | −Rs 228,960 | 1.000 | 1.000 |
| P06 | s15_step | 1,659 (2.48) | 44% | +Rs 668 / −Rs 896 | −Rs 763 | 34% of 570 | −Rs 6,621 | −Rs 519,916 | 271 (18) | −Rs 511,127 | −Rs 458,407 (0% green) | 461 | −Rs 131,998 | 0.991 | 1.000 |
| P07 | s15_trail | 1,595 (2.38) | 41% | +Rs 620 / −Rs 596 | −Rs 457 | 26% of 574 | −Rs 4,901 | −Rs 313,124 | 316 (39) | −Rs 305,949 | −Rs 287,136 (0% green) | 483 | −Rs 150,024 | 0.058 | 0.252 |
| P08 | s15_trail | 3,256 (4.86) | 44% | +Rs 637 / −Rs 830 | −Rs 1,382 | 26% of 666 | −Rs 6,533 | −Rs 927,320 | 874 (109) | −Rs 926,126 | −Rs 842,352 (0% green) | 898 | −Rs 264,622 | 0.983 | 1.000 |
| P09 | s15_step | 1,888 (2.82) | 32% | +Rs 552 / −Rs 518 | −Rs 691 | 24% of 531 | −Rs 8,513 | −Rs 463,548 | 103 (12) | −Rs 462,717 | −Rs 416,972 (0% green) | 485 | −Rs 112,145 | 1.000 | 1.000 |
| P10 | s15_step | 1,766 (2.64) | 37% | +Rs 604 / −Rs 518 | −Rs 480 | 26% of 578 | −Rs 6,325 | −Rs 321,270 | 145 (13) | −Rs 321,270 | −Rs 292,317 (0% green) | 463 | −Rs 110,015 | 0.047 | 0.223 |
| P11 | s15_trail | 653 (0.97) | 48% | +Rs 481 / −Rs 624 | −Rs 170 | 41% of 292 | −Rs 5,045 | −Rs 116,586 | 34 (1) | −Rs 114,124 | −Rs 101,126 (0% green) | 122 | −Rs 38,206 | 0.080 | 0.322 |
| P12 | s15_step | 3,319 (4.95) | 43% | +Rs 535 / −Rs 687 | −Rs 1,207 | 27% of 668 | −Rs 6,846 | −Rs 808,710 | 206 (13) | −Rs 808,710 | −Rs 719,939 (0% green) | 913 | −Rs 272,596 | 0.917 | 1.000 |
| P13 | s15_step | 2,299 (3.43) | 46% | +Rs 470 / −Rs 589 | −Rs 636 | 38% of 572 | −Rs 11,166 | −Rs 426,625 | 49 (4) | −Rs 425,978 | −Rs 375,652 (0% green) | 541 | −Rs 127,074 | 0.007 | 0.053 |
| P14 | s15_step | 1,895 (2.83) | 43% | +Rs 356 / −Rs 503 | −Rs 557 | 32% of 447 | −Rs 11,538 | −Rs 374,299 | 14 (0) | −Rs 373,522 | −Rs 343,093 (0% green) | 515 | −Rs 140,589 | 0.961 | 1.000 |
| P15 | s15_trail | 3,686 (5.50) | 42% | +Rs 301 / −Rs 501 | −Rs 1,208 | 25% of 652 | −Rs 12,604 | −Rs 809,253 | 54 (3) | −Rs 809,132 | −Rs 737,986 (0% green) | 1,174 | −Rs 287,415 | 1.000 | 1.000 |
| O16 | s40_step | 954 (1.42) | 30% | +Rs 689 / −Rs 611 | −Rs 414 | 27% of 366 | −Rs 4,667 | −Rs 278,619 | 102 (5) | −Rs 277,243 | −Rs 283,322 (0% green) | 338 | −Rs 79,920 | 0.912 | 1.000 |
| O17 | s25_trail | 284 (0.42) | 26% | +Rs 753 / −Rs 532 | −Rs 114 | 25% of 155 | −Rs 2,983 | −Rs 76,459 | 34 (0) | −Rs 76,072 | −Rs 83,022 (0% green) | 97 | −Rs 27,264 | 0.350 | 1.000 |
| O18 | s40_trail | 489 (0.73) | 39% | +Rs 1,303 / −Rs 706 | −Rs 61 | 34% of 325 | −Rs 3,014 | −Rs 46,681 | 238 (37) | −Rs 40,915 | −Rs 58,755 (29% green) | 187 | −Rs 53,141 | 0.000 | 0.002 |
| O19 | s25_step | 911 (1.36) | 34% | +Rs 1,566 / −Rs 575 | −Rs 25 | 34% of 345 | −Rs 3,377 | −Rs 82,466 | 357 (37) | −Rs 16,848 | −Rs 26,178 (14% green) | 277 | −Rs 26,192 | 0.000 | 0.002 |
| O20 | s25_trail | 994 (1.48) | 28% | +Rs 944 / −Rs 609 | −Rs 395 | 25% of 526 | −Rs 2,662 | −Rs 265,056 | 463 (118) | −Rs 264,541 | −Rs 266,619 (0% green) | 358 | −Rs 75,740 | 0.230 | 0.709 |

- "Trades a window day" counts all coins (perps: 5 coins, at most 1-2 a coin a day). The 3% day loss cap is per coin,
  so a perp rule's worst day can reach about 15%. Fixed size, no compounding: the perp rules' losses are larger than
  the account, i.e. each would have wiped a Rs 1 lakh account out, several times over.
- Lock exits that later ran 2R further (perps) or doubled (options) before the time exit: P08 109 of 874, O20 118 of
  463, O18 37 of 238, O19 37 of 357. Of the perp picks' 33,683 design trades 74.5% ended on time, 18.9% at the stop
  and 6.7% at the lock: the moves are too small for the lock to bind often.
- Twins: only O18 and O19 beat random entries after BH (q 0.002, all four exits each); P10, P11, P13 and O20 have some
  variants under q 0.10, all losing money. Most reversal rules did **worse** than random entries at the same clock times
  (twin p 0.9-1.0): fading the most stretched moves is where the reversal is weakest.

Holdout, the same picks:

| rule | holdout: win | avg win / loss | P&L a day after tax A | green days | worst day | max DD | lock exits (cut short) | pre-tax | business reading B | stress |
|---|---|---|---|---|---|---|---|---|---|---|
| P01 | 38% | +Rs 335 / −Rs 539 | −Rs 1,260 | 27% of 161 | −Rs 9,855 | −Rs 231,865 | 6 (0) | −Rs 184,521 | −Rs 184,521 | −Rs 287,430 |
| P02 | 39% | +Rs 415 / −Rs 614 | −Rs 1,248 | 28% of 166 | −Rs 9,325 | −Rs 230,672 | 24 (1) | −Rs 175,585 | −Rs 175,585 | −Rs 281,941 |
| P03 | 38% | +Rs 399 / −Rs 615 | −Rs 980 | 29% of 138 | −Rs 8,846 | −Rs 183,183 | 7 (0) | −Rs 142,743 | −Rs 142,743 | −Rs 218,273 |
| P04 | 41% | +Rs 339 / −Rs 611 | −Rs 348 | 30% of 82 | −Rs 5,708 | −Rs 64,591 | 4 (0) | −Rs 50,829 | −Rs 50,829 | −Rs 77,046 |
| P05 | 31% | +Rs 279 / −Rs 416 | −Rs 1,244 | 13% of 181 | −Rs 6,995 | −Rs 228,960 | 7 (0) | −Rs 189,342 | −Rs 189,342 | −Rs 297,512 |
| P06 | 48% | +Rs 710 / −Rs 965 | −Rs 717 | 38% of 159 | −Rs 6,388 | −Rs 132,727 | 72 (4) | −Rs 74,764 | −Rs 74,764 | −Rs 159,695 |
| P07 | 36% | +Rs 596 / −Rs 690 | −Rs 815 | 19% of 165 | −Rs 4,981 | −Rs 150,024 | 72 (4) | −Rs 111,907 | −Rs 111,907 | −Rs 178,928 |
| P08 | 46% | +Rs 647 / −Rs 889 | −Rs 1,438 | 26% of 182 | −Rs 6,214 | −Rs 268,460 | 258 (28) | −Rs 166,918 | −Rs 166,918 | −Rs 318,826 |
| P09 | 39% | +Rs 536 / −Rs 588 | −Rs 609 | 30% of 141 | −Rs 8,337 | −Rs 114,044 | 22 (0) | −Rs 72,623 | −Rs 72,623 | −Rs 144,207 |
| P10 | 34% | +Rs 637 / −Rs 562 | −Rs 598 | 21% of 154 | −Rs 4,050 | −Rs 112,517 | 36 (1) | −Rs 73,252 | −Rs 73,252 | −Rs 139,192 |
| P11 | 37% | +Rs 512 / −Rs 689 | −Rs 208 | 39% of 57 | −Rs 5,657 | −Rs 38,800 | 5 (0) | −Rs 30,020 | −Rs 30,020 | −Rs 43,009 |
| P12 | 41% | +Rs 531 / −Rs 726 | −Rs 1,482 | 25% of 183 | −Rs 6,377 | −Rs 272,596 | 50 (4) | −Rs 196,276 | −Rs 196,276 | −Rs 328,356 |
| P13 | 42% | +Rs 430 / −Rs 595 | −Rs 691 | 33% of 153 | −Rs 9,289 | −Rs 127,074 | 19 (0) | −Rs 89,317 | −Rs 89,317 | −Rs 153,735 |
| P14 | 36% | +Rs 334 / −Rs 536 | −Rs 764 | 26% of 120 | −Rs 10,815 | −Rs 140,589 | 6 (0) | −Rs 114,073 | −Rs 114,073 | −Rs 170,849 |
| P15 | 36% | +Rs 295 / −Rs 479 | −Rs 1,562 | 20% of 183 | −Rs 8,036 | −Rs 288,854 | 15 (0) | −Rs 231,592 | −Rs 231,592 | −Rs 361,680 |
| O16 | 33% | +Rs 718 / −Rs 585 | −Rs 434 | 35% of 127 | −Rs 3,985 | −Rs 80,489 | 35 (1) | −Rs 51,719 | −Rs 51,719 | −Rs 113,220 |
| O17 | 28% | +Rs 520 / −Rs 519 | −Rs 148 | 25% of 53 | −Rs 2,183 | −Rs 27,264 | 16 (0) | −Rs 22,274 | −Rs 22,274 | −Rs 36,665 |
| O18 | 27% | +Rs 1,338 / −Rs 712 | −Rs 289 | 23% of 126 | −Rs 2,507 | −Rs 53,270 | 81 (13) | −Rs 30,691 | −Rs 30,691 | −Rs 70,555 |
| O19 | 34% | +Rs 1,235 / −Rs 568 | −Rs 142 | 34% of 110 | −Rs 2,699 | −Rs 28,721 | 109 (22) | +Rs 12,245 | +Rs 8,425 | −Rs 55,940 |
| O20 | 29% | +Rs 1,027 / −Rs 582 | −Rs 412 | 24% of 182 | −Rs 1,989 | −Rs 80,431 | 170 (38) | −Rs 39,400 | −Rs 39,400 | −Rs 110,606 |

Where the money goes (design, picks). "Move caught" is before any cost (perps: price; options: the exit fill against
the entry mid, so it already includes the exit's half spread):

| rule (pick) | move caught a trade | gross on fills | fees + GST | funding | pre-tax | tax A | after tax A | after reading B | TDS locked at the peak |
|---|---|---|---|---|---|---|---|---|---|
| P01 (s15_step) | +1.1 bps of price | −Rs 224,383 | −Rs 305,447 | −Rs 190 | −Rs 530,020 | −Rs 187,155 | −Rs 717,175 | −Rs 530,020 | Rs 1,467,637 |
| P02 (s15_step) | +2.8 bps of price | −Rs 118,670 | −Rs 270,507 | +Rs 550 | −Rs 388,628 | −Rs 221,565 | −Rs 610,193 | −Rs 388,628 | Rs 1,317,531 |
| P03 (s15_step) | +1.0 bps of price | −Rs 126,593 | −Rs 227,133 | +Rs 119 | −Rs 353,608 | −Rs 169,195 | −Rs 522,803 | −Rs 353,608 | Rs 1,084,119 |
| P04 (s15_trail) | -7.9 bps of price | −Rs 73,242 | −Rs 79,604 | +Rs 123 | −Rs 152,723 | −Rs 50,068 | −Rs 202,792 | −Rs 152,723 | Rs 386,930 |
| P05 (s15_trail) | +1.3 bps of price | −Rs 155,228 | −Rs 299,012 | −Rs 846 | −Rs 455,086 | −Rs 102,257 | −Rs 557,344 | −Rs 455,086 | Rs 1,504,029 |
| P06 (s15_step) | -12.8 bps of price | −Rs 177,904 | −Rs 157,685 | +Rs 584 | −Rs 335,005 | −Rs 176,122 | −Rs 511,127 | −Rs 335,005 | Rs 763,511 |
| P07 (s15_trail) | +2.3 bps of price | −Rs 8,596 | −Rs 149,725 | −Rs 710 | −Rs 159,031 | −Rs 146,917 | −Rs 305,949 | −Rs 159,031 | Rs 718,557 |
| P08 (s15_trail) | -7.8 bps of price | −Rs 284,852 | −Rs 309,768 | −Rs 1,143 | −Rs 595,763 | −Rs 330,363 | −Rs 926,126 | −Rs 595,763 | Rs 1,500,898 |
| P09 (s15_step) | -2.8 bps of price | −Rs 136,747 | −Rs 201,647 | +Rs 116 | −Rs 338,278 | −Rs 124,440 | −Rs 462,717 | −Rs 338,278 | Rs 984,314 |
| P10 (s15_step) | +4.0 bps of price | −Rs 4,659 | −Rs 169,616 | −Rs 1,231 | −Rs 175,507 | −Rs 145,763 | −Rs 321,270 | −Rs 175,507 | Rs 827,177 |
| P11 (s15_trail) | +8.1 bps of price | −Rs 17,471 | −Rs 42,991 | −Rs 207 | −Rs 60,668 | −Rs 53,455 | −Rs 114,124 | −Rs 60,668 | Rs 236,048 |
| P12 (s15_step) | +3.4 bps of price | −Rs 208,803 | −Rs 317,255 | +Rs 627 | −Rs 525,431 | −Rs 283,279 | −Rs 808,710 | −Rs 525,431 | Rs 1,534,229 |
| P13 (s15_step) | +5.9 bps of price | −Rs 47,928 | −Rs 195,328 | −Rs 453 | −Rs 243,709 | −Rs 182,269 | −Rs 425,978 | −Rs 243,709 | Rs 1,061,566 |
| P14 (s15_step) | +2.0 bps of price | −Rs 91,098 | −Rs 169,444 | +Rs 78 | −Rs 260,464 | −Rs 113,058 | −Rs 373,522 | −Rs 260,464 | Rs 783,945 |
| P15 (s15_trail) | +0.2 bps of price | −Rs 238,134 | −Rs 375,464 | −Rs 677 | −Rs 614,275 | −Rs 194,857 | −Rs 809,132 | −Rs 614,275 | Rs 1,783,267 |
| O16 (s40_step) | -582.1 bps of premium | −Rs 142,126 | −Rs 66,154 | Rs 0 | −Rs 208,280 | −Rs 68,963 | −Rs 277,243 | −Rs 208,280 | Rs 12,342 |
| O17 (s25_trail) | -546.6 bps of premium | −Rs 39,649 | −Rs 17,719 | Rs 0 | −Rs 57,368 | −Rs 18,704 | −Rs 76,072 | −Rs 57,368 | Rs 3,875 |
| O18 (s40_trail) | +1003.8 bps of premium | +Rs 77,287 | −Rs 34,809 | Rs 0 | +Rs 42,478 | −Rs 83,393 | −Rs 40,915 | +Rs 26,882 | Rs 7,892 |
| O19 (s25_step) | +1313.1 bps of premium | +Rs 199,423 | −Rs 57,315 | Rs 0 | +Rs 142,108 | −Rs 158,955 | −Rs 16,848 | +Rs 91,808 | Rs 14,105 |
| O20 (s25_trail) | -371.6 bps of premium | −Rs 107,479 | −Rs 69,160 | Rs 0 | −Rs 176,639 | −Rs 87,901 | −Rs 264,541 | −Rs 176,639 | Rs 13,400 |

Rs 25,000 account (the picks; perps round to whole Delta lots, so a few trades become too small):

| rule | Rs 25,000: design trades | design net A | holdout trades | holdout net A |
|---|---|---|---|---|
| P01 | 3,105 | −Rs 172,936 | 886 | −Rs 55,773 |
| P02 | 2,809 | −Rs 147,408 | 827 | −Rs 55,396 |
| P03 | 2,371 | −Rs 125,889 | 616 | −Rs 43,328 |
| P04 | 847 | −Rs 48,418 | 231 | −Rs 15,130 |
| P05 | 2,535 | −Rs 134,510 | 944 | −Rs 54,836 |
| P06 | 1,659 | −Rs 122,509 | 461 | −Rs 31,493 |
| P07 | 1,595 | −Rs 72,829 | 483 | −Rs 36,049 |
| P08 | 3,255 | −Rs 223,178 | 898 | −Rs 63,513 |
| P09 | 1,888 | −Rs 111,578 | 485 | −Rs 27,019 |
| P10 | 1,766 | −Rs 77,098 | 463 | −Rs 26,279 |
| P11 | 652 | −Rs 26,587 | 122 | −Rs 8,991 |
| P12 | 3,318 | −Rs 196,018 | 913 | −Rs 65,330 |
| P13 | 2,298 | −Rs 101,658 | 541 | −Rs 30,412 |
| P14 | 1,895 | −Rs 89,234 | 515 | −Rs 33,588 |
| P15 | 3,685 | −Rs 195,103 | 1,174 | −Rs 68,646 |
| O16 | 954 | −Rs 67,281 | 338 | −Rs 19,329 |
| O17 | 284 | −Rs 18,410 | 97 | −Rs 6,633 |
| O18 | 489 | −Rs 9,952 | 187 | −Rs 13,005 |
| O19 | 911 | −Rs 3,542 | 277 | −Rs 6,159 |
| O20 | 994 | −Rs 64,477 | 358 | −Rs 18,494 |

Baselines (Rs 1 lakh put in at the start, sold at the end, after tax A): design span 1 (Nov 2023 - Mar 2025, 517 days)
BTC +Rs 95,087 held / +Rs 23,734 weekly DCA, ETH +Rs 278 / -Rs 33,261, SOL +Rs 1,53,581 / -Rs 519, BNB +Rs 1,15,156 /
+Rs 21,264, XRP +Rs 1,71,483 / +Rs 1,39,042; design span 2 (Nov 2025 - Mar 2026, 151 days, a falling market) every
coin lost (BTC -Rs 37,706 held / -Rs 16,359 DCA ... SOL -Rs 55,544 / -Rs 25,574); holdout (183 days) BTC +Rs 26,312 /
+Rs 6,159, ETH +Rs 87,857 / +Rs 37,727, SOL +Rs 46,630 / +Rs 19,069, BNB +Rs 45,957 / +Rs 29,139, XRP +Rs 25,088 /
+Rs 9,450. Holding beat every rule in design span 1 and in the holdout. In the falling span 2 holding one coin lost
Rs 37,706-55,544 and the rules' picks lost Rs 20,159 (O17) to Rs 2,12,548 (P08); three (P11, O17, O18) lost less than
holding, by trading little.

### Gates (PREREG section 7)

| gate | rules passing |
|---|---|
| G1 design net after tax A > 0 on >= 100 trades | 0 of 20 (0 of 80 variants; 8 variants positive before tax: O18 and O19, all exits) |
| G2 walk-forward by quarter > 0 with half the quarters green | 0 (best: O19 -Rs 26,178, 1 green quarter of 7) |
| G3 twin p < 0.05 and BH q < 0.10 | 2 (O18, O19) |
| G4 stress net > 0 | 0 |
| G5 holdout net after tax A > 0 | 0 (O19 positive before tax, +Rs 12,245, all four exits) |
| **all five** | **0** |

### What went wrong on the way, and deviations (all of them)

- Three rules first drafted on funding, option skew and put/call were replaced (P12, P14, P15) after the screen called
  those features noise and before any rule P&L; decision counts (not P&L) were looked at first. Written in PREREG part B.
- A design run was stopped and resumed after the option jobs proved too slow (the per-day option cache was too small);
  the code change only enlarged the cache and split jobs by venue. The perp trade lists of SOL, BNB and XRP from the
  first run were kept.
- The Rs 25,000 run first showed 0 trades for P08 and O20: the signal was called without the minute bars there (the
  main run was not affected, it passed the decisions in). Fixed and re-run; no choice was made on it.
- The holdout run may resume after a crash before writing any result (no result had been seen); it ran once, without a
  crash.
- Random twins are independent trades (no one-at-a-time or day cap), compared on mean net per trade; the rules get
  both, and the day cap trims their losing days, so twin p-values lean towards the rules (O18 / O19's G3 pass is
  generous).
- **Independent review (14 Oct, after the holdout read), all disclosed, none changes a verdict:**
  - Option entries filled at the signal minute's candle close (not the next minute, as PREREG section 8 says), and
    that close can be stale (in a 400-trade sample 16% had no trade for 5+ minutes, 5% for 15+). A design-only
    re-run with the next minute's close and no print older than 2 minutes (`run.py strict`,
    `results/strict_entry.json`, post hoc, not gated): every option variant loses before tax, O19 -Rs 11,638 to
    -Rs 36,230, O18 -Rs 2,092 to -Rs 16,774, O16 / O17 / O20 still lose (O16 -Rs 85,652 to -Rs 1,16,008, fewer trades).  The holdout was not re-read.
  - P12 fires mostly just after the UTC day rolls over (05:30 IST), when "the day's low" is minutes old: 50% of its
    3,319 trades enter at 05:30-05:35 IST, 63% of those long. As run it is mostly a 05:30 IST long, not the fade it
    describes. It loses either way.
  - One at a time: a new perp trade may enter at the open of the bar in which the previous one was stopped mid-bar
    (1,337 of 135,962 design rows).
  - The option strike band is set from spot at 23:00 UTC for the whole window (a later price for entries before
    23:00 UTC; the ATM strike must still be within 1% of the entry spot). Funding uses the rate known at entry, not
    the next settled one. Reading B carries losses forward (not stated in PREREG section 4).
  - The Rs 25,000 run's "TDS passes half the account" was measured against Rs 50,000 instead of Rs 12,500 (not quoted
    here); fixed in code. Holdout readers now refuse to run before the one read is logged (`data.keep`).
- Spreads: no Delta order-book history exists, so option fills are the last trade price (the candle close) plus RC2's
  modelled half spread; minutes without trades repeat the last price (Delta's convention), so stops on illiquid
  strikes may fill later than modelled. Perp slippage is an assumption (no bid/ask). INR fixed at Rs 85 per USD.
- Tax and TDS are modelling assumptions (30% + 4% cess; TDS 1% of each sale, refunded after about a year). A CA must
  confirm both, above all for INR futures and options.

### Files

- Brain: `research/brain/crypto/` (adapters, cube v3, `features/trend_f.py`, `features/options_f.py`, README "F1 refresh");
  tests `research/brain/tests/test_crypto_*.py`.
- F1C: `research/hunt/f1c/` (`f1ccfg.py` periods and costs, `screen.py`, `rules.py`, `perp.py`, `options.py`, `book.py`,
  `engine.py`, `run.py`, `report.py`, `entries.py`, `test_f1c.py`); results `research/hunt/f1c/results/`.
- Big tables (trade lists, twin pools, per-coin screens): Drive `gdrive:IraAlgo-data-crypto/derived/f1c_factory`.
- Re-run: `python -m research.hunt.f1c.screen`, `... .run design`, `... .run gates`, `... .report`; the holdout refuses a
  second read.
