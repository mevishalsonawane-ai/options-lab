# R206 pre-registration: how reliable is the arms' direction call, and is a call that other signals agree with more reliable?

Written 20 Nov 2026, **before any R206 number was computed** (only the coverage of the older index files was listed:
dates and sessions, no price summarised). Committed and pushed on `claude/trend-exit-study` before the code's first run.
Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 20 Nov: *"Can you find a way more reliable then current for direction prediction?"*

**Signal source: the index, only the index.** This study never opens an option file: it asks whether the index goes the
way a signal says, on the index's own 1-minute bars.

## 0. What is already known (so this is not the ninth copy of a failed idea)

Predicting direction has been tested many times here and never held up:
- R16 (12 Oct): next 15 / 30 / 60 minutes, AUC 0.51 - 0.54, "not predictable"; only the size of the move was.
- `DIRECTION_MODEL.md`: a walk-forward model on 35 features (momentum, VWAP, OI walls, PCR, skew, VIX, leaders) was right
  51 - 53% of the time; "follow the last 5 / 15 minutes" 48%.
- R41, R123, R187 (neural networks), R151 (the R72 / R51 pickers, 20 tests), R105B (the books' trend rules as filters,
  40 tests), R79 (Supertrend / waiting / other indices on Liquidity), R177, R183: none passed.
- R202 / R203 / R204 (today): 70 exits and skips on top of today's rules, none passed.

So R206 does **not** fit another model. It does two narrower things:
1. **Measure** how often the arms' own direction call is right, on the index, over 4.5 years.
2. **Test one new lead and three plain ones** as conditions on that call. The new lead came from today's diagnosis
   (`research/HUNT_R202_ARMS_DIAGNOSIS.md`): a trade two arms took at the same minute won more often (R51 with R72:
   66.0% of 1,137 against 59.5% of 1,685 for R51 alone; Liquidity with a Donchian arm: 54.9% of 355 against 46.5% of 853).
   That was seen on option trades of Nov 2024 - Oct 2026. Whether **agreement between signals makes the direction more
   reliable** is tested here on **older days that lead has never seen**.

## 1. Data

- The `dhan-data` branch of options-lab (Dhan v2, fetched 6 Oct 2026): 1-minute index candles of NIFTY, BANKNIFTY,
  FINNIFTY, MIDCPNIFTY and SENSEX, 8 Oct 2021 - 5 Oct 2026 (MIDCPNIFTY from 31 Jan 2022), written in the lake's layout by
  `r206data.py` (`~/.cache/iraalgo/r206/lake`), read with `python3 -I`. Nothing raw in git.
- R51's day stores built from those index files only (`~/.cache/iraalgo/r206/r51/clean`; warm-up from 8 Oct 2021), so
  both eras are treated exactly alike and nothing depends on option data.

## 2. The signals (the arms' own code, unchanged)

Every signal is (index, day, entry minute, side) with the entry minute in 09:20 - 15:10; it is known at the close of the
minute before the entry minute.

| source | what | code |
|---|---|---|
| **R72** | Breadth Donchian: a 5-minute Donchian-20 break with at least 4 of the 5 indices breaking (its count, 4 or 5, is kept) | `r101arms.r72_signals` |
| **R51** | Donchian 20 kept by Supertrend(10, 3) | `r112study.r51_st_signals` |
| **LIQ** | Liquidity 15+5's sweep signals of its six books (BANKNIFTY 15 / 5, FINNIFTY 30 / 5, MIDCPNIFTY 15 / 5) | `fam_liq._day_signals` on the same bars `fam_liq.signals` builds |

Two honest differences from the arms' trades, the same in both eras: **expiry days are not left out** and Liquidity is
not limited to days with option data (both need option files); and these are **signals, not trades** (no picker, no "one
position a side", no day caps). So the counts are larger than the trade counts in R202.

## 3. What "right" means

For a signal with entry minute e: p0 = the index's last known close at or before minute e - 1; p15 = its last known close
at or before minute e + 14; p30 = the same at e + 29. A signal whose horizon runs past 15:29 has no value for that
horizon. The move its way = (pH / p0 - 1) for a call, the negative for a put.
- **Right at 15 minutes** (primary): the move its way at p15 is above 0.
- Right at 30 minutes, and the average move its way at 15 minutes (in hundredths of a percent): secondary, reported.

## 4. Eras

| era | dates | role |
|---|---|---|
| **OLD** | 1 Apr 2022 - 19 Nov 2024 | **the test**: never seen by the lead (R143 / R190 priced R72 trades there and they lost money; the direction by agreement was never looked at) |
| RECENT | 21 Nov 2024 - 5 Oct 2026 | where the lead was seen (on option trades); reported, and must agree in sign |

Calendar years 2022 (from April), 2023 and 2024 (to 19 Nov) are the OLD era's three stability blocks.

## 5. Part 1: how reliable is the call today (descriptive)

Per source, era and year: signals, days, right at 15 and 30 minutes with a 95% interval (day-block bootstrap), the average
move its way. Also per index, and by entry hour. No gate: this is the "current" Boss asked to beat.

## 6. Part 2: the tests (8), each "group A is right more often than group B at 15 minutes"

| id | signals | A (more reliable?) | B |
|---|---|---|---|
| **H1** | R51 | an R72 signal on the same index, minute and side | no such R72 signal |
| **H2** | LIQ | an R51 or R72 signal on the same index, minute and side | none |
| **H3** | R72 | all 5 indices broke | 4 of 5 |
| **H4** | R72 | an R51 signal on the same index, minute and side | none |
| **H5** | R72 | **with the day**: the index is above its day's first open for a call, below for a put, at p0 | against the day |
| **H6** | LIQ | with the day (same definition) | against the day |
| **H7** | R72 | **with the market**: NIFTY's last completed 15-minute close before the entry minute is above NIFTY's EMA20 of 15-minute closes for a call, below for a put (EMA carried across sessions) | against the market |
| **H8** | LIQ | with the market (same definition) | against the market |

H1 - H4 are the agreement lead; H5 - H8 are the two plainest "trade with the trend" conditions, on the index only (R105B
tested daily and weekly trend rules as trade filters; these intraday ones were only tested as P&L skips, in R203).

**Statistic:** d = (share right in A) - (share right in B), in the OLD era. **p** = one-sided, from a day-block bootstrap
(whole days resampled with replacement, 5,000 draws, seed 206): the share of draws with d <= 0. For BH, as R190 / R193,
the normal-tail p of d against the bootstrap's standard error.

**A condition counts as "more reliable" only if ALL hold:**
1. OLD era: d > 0 with bootstrap p < 0.05;
2. BH q <= 0.10 with m = 4,880 (the entry files on this branch before R206) + 8 = **4,888**;
3. d > 0 in at least 2 of the 3 OLD-era years;
4. d > 0 in the RECENT era too;
5. at least 200 signals in A and in B in the OLD era;
6. **it is worth something**: A's share right in the OLD era is at least 2 points above the source's own share right
   (all its signals), so it beats "current" by more than noise.

One that fails any item is "not more reliable", with the failed items listed. **What a pass would mean:** only that the
index went that way more often. It is not a trading rule: whether skipping or sizing by it makes money on options is a
separate, later test (and on Nov 2024 - Oct 2026 that test would be a second look). Nothing is built in the app.

## 7. Order of work and honesty

1. This file is committed and pushed before any number.
2. `r206study.py recent` -> `results/recent.json` (RECENT era only: Part 1 and the eight differences); committed with
   the code. Then `r206study.py old` reads the OLD era once (`results/OLD_READ` written first) -> `results/old.json`;
   `decide` -> `results/decision.json`; `r206report.py` -> `results/report.md` and 9 log entries (8 tests + Part 1).
3. No definition, horizon or gate item changes after the RECENT numbers; an amendment is dated and written here first.

## 8. Tests (pytest, synthetic, no data)

The look-ahead guard (p0, p15, p30, the with-the-day and with-the-market labels are unchanged when minutes from the
entry minute on - for the labels - or after the horizon - for the prices - are replaced by noise), missing minutes and a
market closed mid-day, a signal too late for its horizon, a holiday gap in the EMA carry, the matching of signals across
sources, the day-block bootstrap (a known difference is found; no difference is not), BH and the gate (each item fails
alone).
