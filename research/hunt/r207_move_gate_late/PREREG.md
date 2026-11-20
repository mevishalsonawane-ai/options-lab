# R207 + R208 pre-registration: trade only when a big enough move is expected, and the late-day break

Written 20 Nov 2026, **before any R207 / R208 number was computed**. Committed and pushed on `claude/trend-exit-study`
before the code's first run. Research only: nothing in `android/**` changes. Owner: Boss (they/them).

Boss, 20 Nov, after R206 (`research/HUNT_R206_DIRECTION.md`) offered two next tests: *"Both"*.

- **R207**: a gate that skips a signal when the expected size of the index's next move is small (7 tests).
- **R208**: the late-day break (15:00 - 15:10), on the index first (2 tests on days never looked at), then as an option
  rule with real costs (6 tests).

**Signal source: the index** for every entry, gate and label. Options are only bought; stops and locks on the premium.

## 0. What is already known, and the honest status of each part

- **R183 (15 Nov)** already tested 16 "sideways" skips on R72 and R51 (ADX, efficiency ratio, crossings of the day's
  average, today's range so far against the 20-day range and against the usual range by this time of day, Choppiness):
  none helped R72, the one chosen for R51 failed its test year. **R142** tested a quiet signal bar; **R203** the daily
  realised-volatility third. So R207 does **not** repeat "is today quiet so far". It tests the one thing R16 found
  predictable - **the size of the next 30 minutes** - as a forecast, on all three arms, and R183's time-of-day range
  ratio on Liquidity, where it was never run.
- **The option data's holdout (1 Apr - 5 Oct 2026) is a second look for both studies**: the "quiet days lose" pattern and
  the late-hour pattern were both seen in today's slices of the whole span. Same gate as R202, same words as R204: a
  rule that clears it "clears the gate on a second look" and still needs new days.
- **R208's index test is fresh**: R206 measured 1 Apr 2022 - 5 Oct 2026. The days **1 Nov 2021 - 31 Mar 2022** were only
  warm-up there and no direction number was computed on them.

## 1. Data and engines

- Options part: exactly R202's build (the `dhan-data` branch to 5 Oct 2026, R51 clean stores, R186's engine, R152's
  picker), baseline **B = today's rules (V5)**, 1 lot, dated charges, R17 fills; design 21 Nov 2024 - 31 Mar 2026 (four
  walk-forward quarters), holdout 1 Apr - 5 Oct 2026, last year 24 Nov 2025 - 5 Oct 2026 (descriptive).
- Index part: exactly R206's build (index minutes 8 Oct 2021 - 5 Oct 2026, index-only stores, the arms' own signals).

## 2. R207: the expected-move gate

For a signal with entry minute e on index u and day d (everything reads minutes before e only):
- **activity so far** A(d, e) = the sum of (high - low) of the day's complete 5-minute bars that end before minute e;
- **today against usual** ratio = A(d, e) / the mean of A(d', e) over the previous 20 sessions d' (at least 10 known);
- **the usual next 30 minutes** F(e) = the mean over those sessions of (highest high - lowest low) of minutes e .. e + 29
  (to 15:29 if the session ends sooner);
- **expected move** X = ratio x F(e) / p0, where p0 is the index's last close before e (a share of the index).
- **range so far against usual** RT = (highest high - lowest low of minutes before e) / the mean of the same over the
  previous 20 sessions (R183's time-of-day range ratio; needs all 20).
An unknown value never skips.

| id | arms | skip the signal when |
|---|---|---|
| **G-E33** | R72, R51, LIQ | X is below the **lowest third** of the arm's own X over its baseline trades of the previous 60 sessions that had a trade (at least 100 such trades; else no skip) |
| **G-E50** | R72, R51, LIQ | the same with the **median** |
| **G-RT85** | LIQ | RT < 0.85 (R183's RT85, never run on Liquidity) |

7 tests. Skips are applied as the app applies a skip: after the walk and after the picker, so a skipped trade still holds
its side and counts in the day's caps. The thresholds are walk-forward (only earlier sessions) or fixed (0.85).

**Part 1 (descriptive, index-only, R206's signals, both of its eras):** does X say anything? The rank correlation of X
with the range the index really made in the next 30 minutes, and per third of X: the real range, the size of the 15-minute
move, and how often the signal was right.

**Gate (R202's, section 6, unchanged):** better than B in design and in the holdout (net above B's, or within 5% with
drawdown and worst month each 20% smaller); better in at least 3 of 4 design quarters; drawdown not above B's; at least
40 trades removed in each window; holdout random-twin p < 0.05 (the same number of trades removed at random, 2,000 twins,
seed 207); BH (section 4).

## 3. R208: the late-day break

R206 saw, in both of its eras, that Donchian breaks with an entry minute of 15:00 - 15:10 were right more often after 15
minutes (R72 61.4% of 953 and 57.4% of 545; R51 60.4% of 1,843 and 55.2% of 1,104). That was a slice, not a test.

### 3a. On the index, on fresh days (2 tests)

Era **1 Nov 2021 - 31 Mar 2022** (never measured). Signals as R206 (the arms' own code; entry minute 15:00 - 15:10 = rows
345 - 355). "Right" as R206: the index's close 15 minutes later is beyond its close at the signal, the signal's way.

| id | signals | claim |
|---|---|---|
| **L-I51** | R51 late signals | right more than 50% of the time |
| **L-I72** | R72 late signals (R72 needs MIDCPNIFTY, whose minutes start 31 Jan 2022, so its sample is short) | right more than 50% |

p = one-sided day-block bootstrap (5,000 draws, seed 208): the share of draws with the share right <= 50%. **Holds** only
if the share right is above 50% with p < 0.05, BH q <= 0.10 (section 4) and at least 100 signals; fewer than 100 is "too
few to judge". Also reported: the same source's signals before 15:00 on those days, and the move to the 15:25 close.

### 3b. As an option rule with real costs (6 tests)

**LATE** = a standalone rule: on a break of the source with an entry minute of 15:00 - 15:10, buy the arm's own option
(1-ITM, the arm's strike rule), 1 lot, with today's exits (the -15% stop, the ladder, breakeven at +10%, the Rs 500 step
lock) and a square-off at T. No picker. Its own caps (one position a side, at most 2 fresh fills a side an index a day,
counted on late breaks only), never on the index's expiry day (as the arms).

| source | T = 15:15 (the arms' square-off today) | T = 15:20 | T = 15:25 |
|---|---|---|---|
| R72 breaks | LATE-72-15 | LATE-72-20 | LATE-72-25 |
| R51 breaks | LATE-51-15 | LATE-51-20 | LATE-51-25 |

**Gate for a standalone rule:** net above Rs 0 in design and in the holdout; above Rs 0 in at least 3 of the 4 design
quarters; at least 40 trades in each window; holdout day-block bootstrap p < 0.05 that the mean daily net is above 0
(5,000 draws, seed 208); BH (section 4). Reported with it: win %, average win / loss, max drawdown, worst month.

Two things a pass would NOT settle, said now: holding past 15:15 **adds** exposure (it is not a risk-lowering change, so
on live it would start in shadow), and 15:20 / 15:25 sit close to the broker's own intraday square-off, which must be
checked before either is used. Also descriptive: what today's R72 / R51 baseline trades with a late entry made.

## 4. Multiple testing

One BH family of 15 tests (7 + 2 + 6) on the normal-tail p of each test's own statistic (R207: the skip twins; 3a: the
share right against 50%; 3b: the mean daily net against 0), with m = 4,889 (the entry files on this branch before R207)
+ 15 = **4,904**; q <= 0.10. A rule that removed no holdout trade enters with p = 1.

## 5. Order of work

1. This file is committed and pushed before any number.
2. `r207opt.py design` -> `results/design.json` (the design window only) and `r207idx.py part1` ->
   `results/index_part1.json` (R206's two eras, already read: descriptive); committed with the code.
3. `r207opt.py final` (the holdout, a second look; `results/HOLDOUT_READ` first) and `r207idx.py fresh` (the fresh index
   era, read once; `results/FRESH_READ` first); `decide`; `r207report.py` -> `results/report.md` and 15 log entries.
4. No definition, threshold or gate item changes after the design numbers; an amendment is dated and written here first.

## 6. Tests (pytest, synthetic, no data)

The look-ahead guard (A, ratio, F, X and RT are unchanged when today's minutes from the entry minute on are replaced by
noise; the walk-forward threshold of a trade is unchanged when trades of its own day or later change), missing minutes,
too few earlier sessions (no skip), an entry too late for a full 30 minutes, the quantile gate with too few earlier
trades, the late-entry filter, the day-block bootstraps (a real edge is found, none is not), and the standalone gate
(each item fails alone).
