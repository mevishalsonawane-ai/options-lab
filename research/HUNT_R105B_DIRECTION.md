# R105B: the books' "direction" rules as filters on Breadth Donchian and the other paper arms

26 Oct 2026. Research only (no `android/**`). Pre-registered in `research/hunt/r105b_direction/PREREG.md` (commit
b8461c6, pushed before any result); code and design results frozen in bcab026 before the one final read. Full tables:
`research/hunt/r105b_direction/results/report.md`.

Boss, 26 Oct: *"By all the pdf knowledge, try to find the direction and use it in breath donchain and other
stratergies."*

## The answer

**No book direction rule passed, so nothing changes.** We took the 10 untested direction rules the books stress most
and used each as a filter on the four paper arms (40 tests):
- weekly swing, weekly 89 MA, daily and 60-minute Dow structure (HH/HL);
- prior-day trend, 200-day SMA, daily ADX;
- the first 15-minute candle;
- trading the gap's side.

A filter means: on an up bias, take only the arm's calls; on a down bias, only its puts. None passed the
pre-registered gate.

What the data says, in plain words:

1. **The books' trend rules hurt these arms.** None of the weekly and daily trend rules (D2-D8) was better in the
   walk-forward year on any arm (28 of 28 worse or equal). They were worse in the final on both Donchian arms.
   Liquidity and R52 saw a few small final gains, after losses in design and walk-forward. On Breadth Donchian, final
   period (1,202 trades, Rs 1,71,366 today):
   - 200-day SMA: Rs 47,394;
   - prior-day trend: Rs 57,724;
   - weekly swing: Rs 66,537.

   These arms buy breakouts that are often against the bigger trend (the first turn of the day). Many of their best
   trades are counter-trend, and a trend filter throws them away. R104 found the same: the arms' trades taken with the
   daily trend lost money in its holdout.
2. **Breadth Donchian already has the best direction check: breadth itself.** Requiring 4 of the 5 indices to break
   together is a direction confirmation, and it is the one that passed (R72). Adding a book rule on top made it worse
   in the final every time: the best was -Rs 10,049 (the first 15-minute candle).
3. **One near miss: the first 15-minute candle on R51 (D9).** On Donchian 20 + Supertrend (R51 as paper runs it), after
   09:30 take only calls if the 09:15-09:29 candle closed green, only puts if it closed red.
   - Better in every period: design Rs 2,16,169 vs Rs 50,425; walk-forward +Rs 54,255 (3 of 4 quarters); final
     Rs 1,71,587 vs Rs 1,34,743 on 1,055 vs 1,670 trades.
   - The final drawdown fell from Rs 81,683 to Rs 63,061.
   - It beat random skips (p 0.002 design, 0.034 final).
   - It failed only the multiple-testing check: BH q 0.29 over 223 tests, and the gate needs 0.10.
   - The same rule on Breadth Donchian lost in the walk-forward and the final. In the 4 unseen sessions (14-19 Oct) it
     was +Rs 641 on R51 (34 trades) and -Rs 4,933 on R72.

   So it stays a **note, not a change**. If it is real, R51's paper record will show it. Watch R51's trades after
   09:30 against the first candle's colour.
4. **Trading the gap's side (D1, D10) helped in design only.** These two rules were built from what R104's holdout
   showed, so they were never independent evidence. On R72 they were better in design (+Rs 66k and +Rs 72k) but worse
   in the final (-Rs 17k and -Rs 18k), with a bigger drawdown. Not a rule.
5. **Liquidity and R52 Open outside value: nothing helps.** Every bias was worse in design and walk-forward. R52 trades
   at 09:20 in the direction of the open, so it already is a direction rule.

## Before / after (final period 1 Apr - 13 Oct 2026, read once; 1 lot; net after charges and slippage)

| bias (signal on the index) | R72 Breadth Donchian | R51 + Supertrend | Liquidity 15+5 | R52 OOV |
|---|---|---|---|---|
| **today (no bias)** | **1,202 / Rs 1,71,366** | 1,670 / Rs 1,34,743 | 357 / Rs 11,155 | 293 / Rs 48,612 |
| D1 gap beyond yesterday's high / low (post-hoc) | 974 / Rs 1,54,478 | 1,350 / Rs 1,01,792 | Rs 6,982 | no change |
| D2 weekly 3-bar swing (Trader Tom) | Rs 66,537 | Rs 32,526 | Rs 8,481 | Rs 22,408 |
| D3 weekly 89 MA (Trader Tom) | Rs 91,646 | Rs 51,775 | -Rs 17,179 | Rs 55,255 |
| D4 daily Dow structure HH/HL | Rs 1,04,940 | Rs 56,793 | Rs 22,286 | Rs 40,831 |
| D5 60-minute Dow structure (Woods) | Rs 1,18,238 | Rs 74,297 | Rs 29,040 | Rs 65,639 |
| D6 prior-day trend (Porwal) | Rs 57,724 | -Rs 21,905 | Rs 10,178 | Rs 46,052 |
| D7 200-day SMA, 1% band | Rs 47,394 | -Rs 18,225 | -Rs 1,180 | Rs 52,305 |
| D8 daily ADX > 30 with DI (Larry Swing) | Rs 1,38,745 | Rs 89,094 | Rs 13,969 | Rs 51,776 |
| D9 first 15-min candle, from 09:30 (Heitkoetter) | 771 / Rs 1,61,317 | **1,055 / Rs 1,71,587** (near miss) | Rs -13,412 | no change |
| D10 gap vs close +-0.25% (post-hoc) | 916 / Rs 1,53,582 | 1,275 / Rs 1,41,045 | Rs 20,778 | Rs 28,036 |

Design (20 Nov 2024 - 31 Mar 2026), walk-forward quarters, win rates, random-skip p and BH q for every cell are in
`results/report.md`. Sample sizes (final, today's rules): R72 1,202 trades, R51 1,670, Liquidity 357, R52 293.

**Gate (all needed):**
- better in design, walk-forward (3 of 4 quarters) and final;
- beats random skips in design and final (p < 0.05);
- BH q <= 0.10 over the 40 tests plus 183 earlier filter tests on these arms;
- at least 40 final trades;
- better without its 5 best trades;
- drawdown not worse by more than 10%.

R51 + D9 failed only BH. Every other test failed 4-7 parts.

## Which book rules, and what was not repeated

| rule | book | why chosen |
|---|---|---|
| D2 weekly 3-bar swing, D3 weekly 89 MA | Trader Tom (First Trading Manual) | "trade with the higher timeframe"; only the daily swing had been tested (R104, failed) |
| D4 daily Dow HH/HL | Trader Tom ch. 3, Mark Douglas, Heitkoetter | the books' basic trend definition, never used as a filter |
| D5 60-minute structure | Al Woods "market bias first" | R73 tested only a 1-hour EMA slope |
| D6 prior-day trend | Nikhil Porwal | never run on the arms |
| D7 200-day SMA | Fabozzi, Aziz | only run as a daily strategy |
| D8 daily ADX > 30 | Larry Swing | only intraday ADX had been tested (R73) |
| D9 first 15-minute candle | Markus Heitkoetter | only tested as an opening-range strategy |
| D1 / D10 gap side | Larry Swing (inverse of Carli) | the one bias R104's holdout favoured (post-hoc) |

Already tested on these arms, so not repeated:
- R73: EMA 20/50, 15-minute / 1-hour EMA slope, ADX, Supertrend (T7 is the only pass, already on R51), day VWAP;
- R79: Supertrend, 10-candle waits, other indices;
- R101: VIX regime, gap size, opening bar, AM only, breadth, VIX direction;
- R104: daily 3-bar swing, SMA50 + DMI, 52-week high / low, Carli gap, Wyckoff streaks.

Not testable here:
- Larry Swing's Force Index and Wyckoff's volume bias (the index has no volume; futures minutes start only on 29 Jul
  2026);
- Solo 2.0 (not in R101's arm set; never filtered).

## What can go on paper

**Nothing.** No bias passed. Under the research rules, failed ideas stay notes, and nothing changes live.

The one to watch is **R51 + first 15-minute candle (D9)**. It needs no switch: the paper record can be split later
into R51 trades after 09:30 with and against the first candle's colour.

## Honest limits

- **The final window has been read by about 25 studies.** It is weak evidence, which is why BH counts 183 earlier
  tests.
- **Unseen days are few.** 14-19 Oct is only 4 sessions (25 R72 trades, 46 R51 trades, 15 Liquidity, 6 R52). Paper
  after 19 Oct cannot be replayed (the lake ends 19 Oct).
- **D1 and D10 are post-hoc** (built from R104's holdout). They are disclosed and counted in BH.
- **D2's outside-week tie-break was not in the PREREG.** When a week breaks both the 3-week high and low, its close
  against the 3-week midpoint decides. It is written in `r105bbias.py`, and was set before any result.
- **R51 here is Donchian 20 + Supertrend, as paper runs it** (R75's B, reproduced: 4,208 / Rs 50,425 and 1,670 /
  Rs 1,34,743). Liquidity is R101's arm (881 / Rs 2,57,249; 357 / Rs 11,155) with its research exits, not any later
  paper lock.

## How it was measured

- **Signal source.** Each arm's own signal is on the index. Each bias is on the index's daily bars (lake
  `index_daily`), weekly bars built from them, 60-minute and 15-minute bars from its 1-minute bars, or today's open.
  - Daily and weekly inputs end at the previous session.
  - Intraday bars are used only after they close, before the entry minute.
  - The option is only what is bought.
- **Trades.** R101's arm candidates (R64 engine, R51 Amendment A clean stores, each arm's own exits, R17 liquidity
  fills, dated charges, 1 lot). A bias removes signals before the arm's own walk, so a removed signal frees the book
  for a later one.
  - All four baselines reproduce their published figures to the rupee.
  - The twins drop the same number of signals at random, 500 times.
- **Tests.** `research/hunt/r105b_direction/tests/test_r105b.py` (22 tests). They cover:
  - every bias on hand-made bars: up, down and neutral, ties, warm-up, missing data;
  - the weekly build (partial weeks, current week excluded);
  - the 60-minute bars;
  - the look-ahead guard: later bars never change a bias;
  - the stacking with T7, the twins, the gate, BH with the earlier family, and the read-once final / OOS.
- **Logged.** `research/experiments/entries/r105b-*.yaml` (40 entries, all rejected).

To re-run (after R105's lake download and stores):

```
python research/hunt/r105b_direction/r105bstudy.py design R72      # and R51T, LIQ, R52 (one process each)
python research/hunt/r105b_direction/r105bstudy.py final           # refuses a second read
R105_EXT=1 python research/hunt/r105_donchian_giveback/r105study.py build_ext
R105B_EXT=1 python research/hunt/r105b_direction/r105bstudy.py oos
python research/hunt/r105b_direction/r105bout.py                   # report.md + 40 entries
```
