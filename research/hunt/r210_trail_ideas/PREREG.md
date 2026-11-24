# R210 pre-registration: hybrid trail, Chandelier exit and a higher-timeframe filter on R72, R51 and Liquidity 15+5

Written 24 Nov 2026, **before any R210 number was computed** (no reproduction, no new trade, no score). Committed and
pushed on `claude/research-trail-ideas` before the code's first run on data. Research only: nothing in `android/**`
changes, and no live or paper behaviour changes. Owner: Boss (they/them).

Boss asked (24 Nov 2026), after a review of a third-party prompt-only skill pack (`Bhala-Srinivash/nse-trading-skills`:
equity swing-trading rules of thumb, no backtests), for three of its ideas to be tested properly on the three main arms.
**R210** was free when this was written: R195 - R201 are the option-selling work (`claude/r195-option-selling`), R202 -
R209 are taken on `claude/research-price-action-25` and `claude/trend-exit-study`; no `r210` folder, entry or branch.

**Signal source of every rule.** The arms' own entries are unchanged (5-minute index breaks; Liquidity's 15 / 5-minute
index bars). The hybrid trail's index variants (HU, HUNT), the Chandelier exit (CH) and the higher-timeframe filter
(F15, F60) read **index** bars only. The H-P variant reads **the option's own 5-minute premium bars**: it is an
option-chart rule, not the default (CLAUDE.md), labelled as such and reported separately. Options are only bought; the
initial stop and the breakeven sit on the premium.

## 0. What was seen before (honest context)

- **R193** (17 Nov) rebuilt the three arms on 1 Nov 2024 - 13 Nov 2026 with the current exits: V5 (picker + breakeven at
  +10% + R186's Rs 500 step lock) on R72 made Rs 2,37,089 in W1 (1,595 trades) and Rs 1,20,989 in W2; R51-V5 Rs 1,24,786 /
  Rs 78,794; Liquidity's live rule Rs 2,18,210 / Rs 2,259. V5 made less than the arms' own exits; that is known here.
- **R162 / R167 / R176 / R179 / R186** tested breakevens, step trails and locks on these arms (most cut profit). **R194**
  tested an index S/R target. **R202's trend-exit** work (`claude/trend-exit-study`) and the 14 Nov
  **mtf-alignment** study (`research/mtf-alignment-20261114`) exist. The mtf-alignment study is a different question: a
  stand-alone NIFTY rule that enters when the last completed 1-minute and 5-minute candles share a colour (its 1-minute
  arm lost Rs 31,108 on 210 trades, its 1+5 arm Rs 38,326 on 144 trades, Oct 2025 - Oct 2026). R210's filter is a
  15 / 60-minute EMA trend gate on the arms' own breaks; nothing is reused from it except the lesson that candle
  alignment alone did not make money.
- The arms hold briefly: R72 / R51 have a 30-minute time stop and the L25 ladder (lock +5% at +25%, breakeven at +30%,
  +30% at +60%, +60% at +100%); Liquidity has a 20-minute time stop unless up 5%. **Expected before any number:** the
  hybrid trail's stages 2 and 3 (+30% / +45% on the premium) and a 5-minute ATR(14) trail will rarely act inside 30
  minutes, so HU and HP will mostly differ from V5 by having no step lock and a breakeven at +15% instead of +10%. HUNT
  (no time stop) is the variant that lets the trail do what the source intends.

## 1. Data and engines (nothing raw in git)

Exactly R193's (its PREREG section 1): R190's lake copy `~/.cache/iraalgo/r190/lake` (the Drive lake
`gdrive:IraAlgo-data-lake` copied with rclone, with the 20 Oct - 13 Nov 2026 fill) and R190's R51 clean stores
(3 Aug 2020 - 13 Nov 2026) linked under `~/.cache/iraalgo/r193/r51/clean`, read with `python3 -I`. R210's caches go to
`~/.cache/iraalgo/r210`. R210 imports R193's modules (`r193study`: units, walk, V5 pricing) and R186's engine
(`r186engine.price`, `lock_levels`) unchanged. Dated Zerodha charges and R17 slippage (stop sales at the R17 stop fill;
an index exit is a market sale at the next minute's open). **1 lot** for every number.

## 2. Windows

| window | dates | role |
|---|---|---|
| **design** | 1 Nov 2024 - 31 Mar 2026 (R193's W1) | every variant is judged here first |
| F1 / F2 / F3 / F4 | Nov 2024 - Feb 2025 / Mar - Jun 2025 / Jul - Oct 2025 / Nov 2025 - Mar 2026 | walk-forward folds |
| **holdout** | 1 Apr - 13 Nov 2026 (R193's W2) | LOCKED for every R210 variant; read once, only for design survivors |

No R210 parameter is fitted: every number below is fixed now, from the source's rules, so walk-forward here means the
rule must win **fold after fold** in time order, not be re-tuned. Honest limits: the holdout is not fresh for the
baseline (R151 - R194 read V5 and the picker on it); it is unseen for these exits and filters. R151's picker is
in-sample on the design years for both V5 and every variant alike.

## 3. The variants (18 tests: 6 per arm)

Baseline of every arm: **V5 = the arm's current exits** - R72 / R51: R151's picker + the arm's own exits (-15% stop,
L25 ladder, 30-minute time stop, index exits, square-off 15:15 / 15:05 on expiry) + breakeven at +10% + R186's step lock
L500 (`r186engine.lock_levels("L500")`); Liquidity: its own exits (-15% stop, 20-minute time stop unless +5%, index
stop, square-offs) + breakeven at +10% + breakeven at +Rs 1,000 a lot (`"LBE"`). This is R193's R72-V5 / R51-V5 / LIQ-V5.

**1R** = the arm's own initial stop: 15% of the raw fill premium (all three arms). Stage times are read on the premium
(the first minute whose high reaches fill x (1 + 2 x 0.15) / (1 + 3 x 0.15)); every level acts from the next minute.

| id | rule |
|---|---|
| **HU** (hybrid on the index) | V5's breakeven and lock are REPLACED by the hybrid: the -15% stop until +1R; at +1R the stop goes to breakeven (fill plus the round trip's charges, R186's `be_x` lock at 0.15); from the first 5-minute **index** bar that ends after +2R is reached, a trail at the highest 5-minute index close since the fill minus 1.5 x ATR(14) (puts: lowest close + 1.5 x ATR); from +3R also the most recent confirmed 5-minute swing low (swing high for puts; a bar whose low is below the 2 bars before and the 2 after, known once the 2nd later bar has closed), whichever is tighter. The trail never loosens. A 5-minute index close beyond it sells the option at the next minute's open (as the arms' index exits). The arm's other own exits (ladder / L25, time stop, index exits, square-offs) and the picker stay; the first exit wins. |
| **HUNT** | HU without the arm's time stop (R72 / R51: 30 min; Liquidity: 20 min / +5%). Everything else as HU. |
| **HP** (hybrid on the premium; option-chart rule) | as HU, but stages 2 and 3 read the option's own 5-minute premium bars of that session (ATR(14) over at least 5 of its bars, else not active yet); a premium 5-minute close below the trail sells at the next minute's open. |
| **CH** (Chandelier) | V5 unchanged, plus an extra exit: at each 5-minute index bar ending at or after the fill, highest index minute high since the fill minus 3 x ATR(14) (puts: lowest low + 3 x ATR); never loosens; a 5-minute close beyond it sells at the next minute's open. First exit wins. |
| **F15** | V5, and a break (Liquidity: an entry) is taken only if the last 15-minute index bar completed before the entry (anchored 09:15) closes above EMA(20) of 15-minute closes for a call, below for a put. |
| **F60** | the same with 60-minute bars (09:15 - 10:14, ..., 14:15 - 15:14, then the short 15:15 - 15:29 bar). |

ATR(14) = the simple mean of the last 14 true ranges of 5-minute bars (index: a continuous series over sessions, so it
warms up on the previous session; the first bar of a session's true range includes the overnight gap). EMA(20) is seeded
with the mean of its first 20 bars and runs across sessions. With fewer than 20 bars known the filter is unknown and the
break is taken (as R151's picker does). F15 / F60 are applied **after the walk** (a skipped break still holds its side and
counts in the day's caps, the way the app applies the picker), on top of the picker for R72 / R51.

Bars: anchored at 09:15 on the minute grid; a minute counts when its high, low and close are known; a bar with no such
minute is left out; a bar's close is its last known minute close; a bar is used only after its last minute has passed.

## 4. Metric (per variant, design and folds; holdout for survivors)

Trades, win %, net Rs after charges at 1 lot, average win / loss, worst month, max drawdown (closed-trade daily equity),
net without the top 5 (`r193lib.stats`), how many trades the new exit sold, how many breaks the filter skipped.

## 5. Significance

Paired against V5 on the design years: the daily difference (variant net - V5 net, on every session either traded).
One-sided sign-flip test (10,000 flips, seed 210): p = (1 + flips with a sum >= the actual) / 10,001. The BH input is the
normal tail of the flip distribution (z = sum / sqrt(sum of squared differences)), as R190 / R193 used a normal tail.
**BH across m = 4,808 + 18 = 4,826** (the entry files in `research/experiments/entries` at this branch's base, origin/main
f5cc79b8, count as p = 1, R193's method). A within-study q (m = 18) is reported too, descriptive only.

## 6. The design gate (fixed now; never loosened after seeing numbers)

A variant **survives** only if ALL hold on the design years, vs its arm's V5:

1. at least 40 trades;
2. higher net;
3. higher net in at least 3 of the 4 folds;
4. worst month not worse than V5's;
5. BH q <= 0.10 (m = 4,826).

## 7. The holdout (read once, survivors only)

Only design survivors are priced on 1 Apr - 13 Nov 2026 (`results/READ_holdout` is written before the run). A survivor
is a **candidate** only if its holdout net is above V5's and its holdout worst month is not worse. If nothing survives,
the holdout is not read for any R210 variant. A candidate is only a candidate: **nothing goes into the app without Boss's
explicit choice**, and anything that could resize or skip live trades would start in shadow (CLAUDE.md).

## 8. Order of work and honesty

1. This file, the code, the tests and the 18 entries (`verdict: proposed`) are committed and pushed before any number.
2. `r210study.py design` first reproduces R193's V5 on the design years (R72 1,595 / Rs 2,37,089; R51 2,240 /
   Rs 1,24,786; Liquidity 908 / Rs 2,18,210). R210 walks the design years alone (R193 walked the whole span and split
   it), so a small gap at the 31 Mar boundary is possible; any gap is reported with its size, never tuned away.
3. Then `decide` (BH + gate), `holdout` (survivors only, once), `report`. A crash before a results file is written may
   be re-run unchanged; a finished read is never re-run with a changed rule.
4. The 18 entries are then updated with the results and verdicts (failures included).

## 9. Tests (pytest, synthetic, no data)

Bars on the grid (gaps, the short last 60-minute bar), ATR / EMA, swings (known only after 2 later bars), the hybrid's
stages (no trail before +2R; structure trail from +3R; never loosens; puts mirrored; nothing at or after the square-off),
the Chandelier (highs before the fill ignored; unknown ATR acts on nothing), the filter (unknown -> taken; previous
session across a holiday gap), the **look-ahead guard** (minutes after the decision replaced by noise change nothing),
the sign-flip test, BH with the earlier entries, the gate (each item fails alone), the holdout verdict, and engine edge
cases through `r186engine.simulate` (the moved exit sells at the next minute's open; an exit row at or after the
square-off or the 15:05 expiry row changes nothing; breakeven at +1R; missing minutes).
