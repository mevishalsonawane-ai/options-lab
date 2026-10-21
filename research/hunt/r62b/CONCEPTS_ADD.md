# Concepts to add to Solo's concept book: market structure, zigzag, order blocks (from R62b)

R62b (2026-10-15). Written in the same format as `research/hunt/r61/CONCEPTS.md` (branch `claude/r61-solo-3`) so the
sections can be merged into `r61concepts.py` and `concepts.json`. Plain English, one section per concept.

**House rule:** every entry and exit signal is read from the underlying (the index), and the option is then chosen by
the strike rule. Every concept below is a price-chart concept read from the INDEX's own bars, so it is a default-allowed
signal source; R62b found no version of it worth trading.

**The short version:** the TradingView indicator "Market Structure Break & Order Block" (EmreKb, the one in Boss's
15 Oct video, fib factor 0.273) was ported line by line and traded on the five index options from Nov 2024. It won
21-54% of its trades (design and final, every gated variant), not the 75-80% the video claims, and no variant passed:
the versions that made money in the final test had lost the most in design, and most of their profit came from five
trades.

## Zigzag

Also called: zigzag swings, swing points, pivots.

**What it is.** A line joining the chart's alternating swing highs and swing lows, ignoring the smaller wiggles in
between. It turns a price chart into a list of "the last high, the last low, the high before it, the low before it".

**How it is computed.** EmreKb's script (length 9): the trend turns DOWN when a bar's low is the lowest of the last 9
bars and UP when a bar's high is the highest of the last 9. When the trend turns up, the lowest low since the previous
up-turn is recorded as a swing low (and its bar); when it turns down, the highest high is recorded as a swing high.
R62b port: `research/hunt/r62b/r62bmsb.py` `msb()`, lines 52-73 of the script.

**On the phone.** not built in. The app's Pine interpreter (android/engine) has the pieces the script uses (arrays,
ta.barssince, ta.valuewhen, per-call history), but R62b did not run the script there.

**How traders use it.** To see the trend at a glance (higher highs and higher lows = up) and to place stops beyond the
last swing.

**Pitfalls.**

- A swing is only known AFTER price has moved away from it (here: 9 bars making a new high or low). A chart that draws
  the zigzag back at the swing bar looks perfect in hindsight; that is repainting. R62b only lets a rule see a swing at
  the bar where the zigzag turned (tested in `test_r62b.py`).
- The length decides everything: a short one finds many small swings, a long one few late ones.
- On an intraday chart the zigzag runs across the overnight gap.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R62b (`research/HUNT_R62B_MSB_ORDER_BLOCK.md`) | 5 indices, 5- and 15-minute bars, 20 Nov 2024 - 14 Oct 2026 | Used as the swing source for the MSB and order-block rule below; no variant passed. | failed |

**Role in Solo.** context only: a way to describe the recent swings to Boss; never a trade rule on its own.

**Solo's inputs from it.** none (not an R61 input).

**R62b result.** See "Market structure break" below: the zigzag swings did not lead to a profitable rule.

## Market structure break (MSB)

Also called: MSB, structure shift, market structure shift.

**What it is.** The moment the chart's swing pattern turns: in a downtrend (lower highs, lower lows), price makes a high
that clears the last swing high by a clear margin, so the downtrend is "broken" (bullish MSB); the mirror for an
uptrend (bearish MSB).

**How it is computed.** EmreKb's live rule (script line 109) compares CONFIRMED zigzag swings, not a close: a bearish
MSB when the newest swing low l0 is below the previous swing low l1 by more than fib x the leg (l0 < l1 - |h0 - l1| x
fib); a bullish MSB when the newest swing high h0 is above the previous swing high h1 by more than fib x the leg (h0 >
h1 + |h1 - l0| x fib). After a break, both a new swing high and a new swing low are needed before the next break. The
"price closes beyond the swing" version that many write-ups describe is only a commented-out line (106) in the script.

**On the phone.** not built in (see Zigzag).

**How traders use it.** As the signal that the trend has changed side, and as the trigger that creates an order block
to trade on the pullback.

**Pitfalls.**

- Because the break is read from confirmed swings, it is known late: a bearish MSB is only confirmed after price has
  already bounced 9 bars up from the new low.
- The same word means different things in different scripts (pivot-based here, close-based in others); always check
  the code.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R62b (`research/HUNT_R62B_MSB_ORDER_BLOCK.md`) | 5 indices, 16 variants, design 20 Nov 2024 - 31 Mar 2026, final 1 Apr - 14 Oct 2026 | MSB + order-block pullback: design win rate 21-44% (28-415 trades a variant), every variant failed the design gate; final 30-54%, 4 variants positive but each fails top-5 and design. Claimed 75-80%. | failed |
| MV-1 Matrix v2.2 (`research/MATRIX_V22.md`, R52) | BANKNIFTY Feb 2024 - Feb 2026, 38 versions | close beyond the last 3-bar swing pivot: all 38 lost in both years; R52 re-test 602 trades, Rs -86,873 | failed |

**Role in Solo.** not a setup. Notes only.

**Solo's inputs from it.** none.

**R62b result.** R62b (15 Oct 2026; signal on the index, 1-ITM nearest-expiry option, held contract, real fills and
dated charges; fib 0.273, zigzag 9): 0 of 16 pass. The best design variant (5m-rej-h-rr: 95 trades, win 41%, net
Rs +2,560) was positive in only 2 of 5 quarters; in the final it lost Rs 896 on 46 trades. Sensitivity at fib 0.33 /
0.50 (information only) does not change the picture.

## BOS and ChoCH (break of structure, change of character)

Also called: BOS, CHoCH, ChoCH, structure break.

**What it is.** Smart-money-concepts words for structure breaks: a BOS is a break in the SAME direction as the trend
(a new higher high in an uptrend), a ChoCH is the FIRST break against it (the first higher high after a downtrend),
read as the trend changing character.

**How it is computed.** R62 (sibling study): fractal swings (2 bars each side, confirmed 2 bars later); a close above
the latest unbroken swing high is a bullish break, a ChoCH when the state was bearish, else a BOS. EmreKb's MSB (above)
is in effect a ChoCH with a fib margin: it only fires against the current state.

**On the phone.** not built in.

**How traders use it.** BOS to stay with a trend, ChoCH as the first warning that it is turning; the higher-timeframe
structure is often used as a filter ("trade only with the 1-hour structure").

**Pitfalls.**

- A ChoCH on a short timeframe is often just noise inside a bigger trend.
- Different swing definitions give different breaks on the same chart.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R62 (`research/HUNT_R62_VOLUME_PROFILE_SMC.md`, branch claude/r62-volume-profile-smc) | 5 indices + 10 stocks, Nov 2024 onwards, 32 primary tests | 1-hour BOS / ChoCH filter: fewer trades, no better per trade; 0 of 32 pass | failed |
| R62b (`research/HUNT_R62B_MSB_ORDER_BLOCK.md`) | 5 indices, 8 filter-on vs 8 filter-off variants | the 1-hour MSB filter halved the trades; per trade it lost less on 5-minute bars and more on 15-minute bars in design; no variant passed | failed |
| H25 (`research/HUNT_H25.md`) | index screen of 9 chart patterns | break of structure (higher low / lower high) among the patterns screened; none survived | failed |

**Role in Solo.** context only (trend description); never a trade rule or a filter on the arms.

**Solo's inputs from it.** none.

**R62b result.** As a filter (`h` variants): design net Rs -13,395 to -84,702 at fib 0.273 (except 5m-rej-h-rr, Rs
+2,560 on 95 trades), so it did not rescue the rule.

## Order block (OB)

Also called: OB, buy zone / sell zone, supply and demand zone, Bu-OB / Be-OB.

**What it is.** The last opposite-colour candle before the impulse that broke the structure: for a bullish break, the
last red candle before the rally; for a bearish break, the last green candle before the drop. Its high-low range is
drawn as a zone, and traders expect price to come back to it and turn.

**How it is computed.** EmreKb (lines 111-127): bullish OB = the last bearish candle (open > close) in the bars from the
previous swing high to the latest swing low (as it stood 9 bars earlier); bearish OB = the last bullish candle from the
previous swing low to the latest swing high. The zone is that candle's full range. The script's own box deletion has a
bug (it removes the oldest box, not the broken one); R62b kills a zone when a bar closes beyond its far side.

**On the phone.** not built in.

**How traders use it.** Wait for price to pull back into the zone, enter in the direction of the break (often after a
rejection candle), stop beyond the swing, target the opposite swing.

**Pitfalls.**

- Most zones are never revisited in a usable way: of 343-355 fresh 5-minute zones per index in design, only 92-106
  gave a first touch inside the trading window.
- A zone that "worked" is easy to see afterwards; the ones that failed are deleted from the chart.
- The stop beyond the swing is tight on 5-minute bars (median 0.15% of the index), so a lot of trades stop out
  on noise, and the option's costs (about Rs 80 a lot plus the spread) eat the small winners.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R62b (`research/HUNT_R62B_MSB_ORDER_BLOCK.md`) | 5 indices, 16 variants | first touch (`tch`) and rejection (`rej`) entries both failed; rejection lost less on 5-minute bars | failed |
| R62 (`research/HUNT_R62_VOLUME_PROFILE_SMC.md`) | 5 indices + 10 stocks | POC on a clean OB roughly halved the design loss but was as bad as the base in the final | failed (not robust) |

**Role in Solo.** not a setup. Notes only.

**Solo's inputs from it.** none.

**R62b result.** Win rate 21-54% across the 16 gated variants (design and final), average win Rs 963-3,814 against
average loss Rs 1,208-2,750 a lot. Realised R per trade -0.48 to +0.40.

## Fib breakout confirmation (fib factor)

Also called: fib factor, Fibonacci confirmation, breakout margin.

**What it is.** A margin a break must clear before it counts: a new swing must go beyond the old one by a fraction
(the fib factor) of the last swing leg, so small pokes beyond a level are ignored.

**How it is computed.** EmreKb: bullish MSB needs h0 > h1 + |h1 - l0| x fib; bearish needs l0 < l1 - |h0 - l1| x fib.
The script's default is 0.33; the video uses 0.273. R62b tested 0.273 (gated) and 0.33 / 0.50 (information only).

**On the phone.** not built in.

**How traders use it.** A larger factor means fewer, "cleaner" breaks; a smaller one means more, earlier breaks.

**Pitfalls.**

- 0.273 and 0.33 are not magic numbers: nothing in the data prefers them.
- Tuning the factor on past charts until the win rate looks good is curve-fitting.

**What our research found.**

| study | sample | finding | verdict |
|---|---|---|---|
| R62b (`research/HUNT_R62B_MSB_ORDER_BLOCK.md`) | 16 variants x 3 fib values | fib 0.33: 0 of 16 positive in design; fib 0.50: 3 of 16 positive in design (one, 5m-rej-h-rr, +Rs 6,856 on 89 trades in 3 of 5 quarters, then +Rs 1,226 on 46 final trades), not gated | no effect worth using |

**Role in Solo.** none.

**Solo's inputs from it.** none.

**R62b result.** Changing the factor moves trade counts by about 10-20% and does not turn the rule profitable in
design; the information-only runs are in `research/hunt/r62b/results/report.md`.
