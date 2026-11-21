# R202 pre-registration: the 25 common price-action concepts as arm candidates

Written 21 Nov 2026, **before any R202 number was computed**: no pattern has been detected on data, no trade priced, no
score read. The data download had started (sizes only seen). Research only: nothing in `android/**` changes and nothing
is merged into `main`. Owner: Boss (they/them).

Boss asked (21 Nov 2026): test the 25 price-action concepts from a chart he shared (support, resistance, trend, trendline,
breakout, retest, consolidation, range, HH/HL, LH/LL, double top / bottom, head and shoulders and its inverse, the three
triangles, flag, wedge, liquidity sweep, order block, fair value gap, market structure shift, equal highs / lows,
candlestick rejection) as possible strategies for the arms. Report before anything is built.
R202 was the next free number (R195 - R201 are on `claude/r195-option-selling`; no `r202` folder, entry, branch or commit).

**Signal source of every rule: the index.** Every pattern is detected on the index's own 5-minute bars (spot index
minutes from the lake, NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), built from 1-minute bars known at the decision
minute. The option is only bought (1 strike in the money, the arms' strike rule). Stops, targets, breakevens and locks sit
on the option premium. No rule reads an option's own chart; no option-chain filter is used; no option is sold.

## 0. Not rebuilt (already in the app; compared, not duplicated)

| # | concept | covered by | what R202 does |
|---|---|---|---|
| 5 | Breakout | R51 Donchian 20, R72 Breadth Donchian | cites R193's record; no new breakout rule |
| 8 | Range | Range Fade | cites its record |
| 20 | Liquidity sweep | Liquidity 15+5 | cites R193's record |
| 23 | Market structure shift | CISD (R133 / R188, failed the gates) | cites R133 / R188; no new MSS rule |

Concept 7 (consolidation) is tested as a *tight-box* breakout (section 3), which overlaps R51's Donchian idea; that is said
in the report. Concepts 3, 9 and 10 share the same pivots and are not independent tests (BH counts all of them anyway).

## 1. Data and engines (nothing raw in git)

- Drive lake (`gdrive:IraAlgo-data-lake`), copied on 21 Nov 2026 with rclone to `~/.cache/iraalgo/r202/raw`:
  `index_1m` of the five indices 2021 - 2026 with their 20 Oct - 13 Nov 2026 fill / gap parts, and `options_rolling_1m` WEEK
  files of the five indices 2021 - 2026 with the `gap1113` parts. `r202data.py lake` builds one flat folder
  `~/.cache/iraalgo/r202/lake` the way R190's `r190data.py` did (2026 files joined with their parts, first print of a
  minute kept; its `join` is imported). Data files are read with `python3 -I`, never from inside the data folder.
- R51's day stores (`r51data.build`, Amendment A cleaning, `R51_CLEAN=1`) under `~/.cache/iraalgo/r202/r51/clean`, window:
  warm-up 1 Oct 2021, first option day 1 Dec 2021, last day 13 Nov 2026.
- Pricing, unchanged: R51's engine (`engine.run_day` / `simulate_many`: next-minute entry, R17 fills, dated Zerodha
  charges, `engine.twin_totals` for the random twins) and R186's engine (`r186engine.price`, `lock_levels`) for V5.
  **1 lot** for every number. Lot sizes: `r51cfg.LOTS` (dated).

## 2. Periods

| period | dates | role |
|---|---|---|
| **design (walk-forward)** | 1 Dec 2021 - 31 Mar 2026 | five folds, each read in time order: F1 Dec 2021 - Dec 2022, F2 2023, F3 2024, F4 2025, F5 Jan - Mar 2026 |
| **holdout** | 1 Apr - 13 Nov 2026 | **LOCKED; read ONCE** by `r202study.py holdout` (guarded by `results/HOLDOUT_READ`) after the design results are written and committed |

Walk-forward: **nothing is fitted**. Every parameter below is fixed now, so every fold is out of sample for the rule and
the walk-forward is the fold-by-fold record in time order (no refit, no parameter choice between folds).
Honest note: the holdout months have been read by earlier studies of the arms (R151 - R201), never with these patterns.
Index coverage: MIDCPNIFTY options exist from 2023 and SENSEX from 2023 in the lake; an index trades only where its
options exist.

## 3. Common definitions (fixed)

- **Bars**: 5-minute index bars on the 09:15 grid (bar b = 1-minute rows 5b .. 5b+4; 75 a session): open = first finite
  open, high = max, low = min, close = last finite close; a bar with no finite minute is missing (it never signals and a
  pattern needing it is not formed).
- **Series**: each session's bars are preceded by the previous **2** sessions' bars (one continuous series) for pivots,
  trendlines and pattern shapes; S/R levels read the previous **5** sessions and today.
- **ATR** `A(b)`: the mean true range of the 14 bars ending at b (true range uses the previous bar's close; across a
  session break the gap counts). `tol = 0.25 x A`.
- **Pivots** (strength 3): bar i is a pivot high when high[i] > each of the 3 highs before it and >= each of the 3 after
  it (pivot low mirrored). A pivot is **known only at the close of bar i+3** (look-ahead wall).
- **Levels** (concepts 1, 2, 6): every known pivot high and low of the last 5 sessions and today, sorted by price and grouped
  greedily: a pivot joins the group if within 0.10% of the group's first price; the level = the group's mean; a level
  needs **3 or more** pivots.
- **Signal** at the close of bar b (row 5b+4); **entry** at the next minute's option open (row 5b+5; up to 2 minutes later if
  no print, else no fill). Signals only when the entry row is within 09:45 - 14:30.
- **Strike**: 1 strike in the money from the ATM strike at the minute before the entry (`engine.strike_for(..., money=1)`),
  call for a bullish signal, put for a bearish one. **Never on the index's expiry day** (the arms' rule).
- **Book**: one position at a time per (pattern, index): a signal is taken only if its entry row is after the previous
  trade's exit row that day; at most 3 trades a (pattern, index, day).

## 4. The 21 patterns (fixed; b = the signal bar, A = A(b), tol = 0.25 A; "known" = known at b's close)

| id | concept | bullish rule (call) | bearish rule (put) |
|---|---|---|---|
| P01 SUP | 1 Support | a level L with close[b-1] > L, low[b] <= L + tol, close[b] > L, close[b] > open[b]; the highest such level | - |
| P02 RES | 2 Resistance | - | mirror: close[b-1] < L, high[b] >= L - tol, close[b] < L, close[b] < open[b]; the lowest such level |
| P03 TREND | 3 Trend | structure up: the last two known pivot highs rising and the last two known pivot lows rising; low[b] <= EMA20 + tol, close[b] > EMA20 and close[b] > open[b] (with-trend pullback to the 20-bar EMA of closes) | mirror (both falling, high[b] >= EMA20 - tol, close below EMA20, bearish bar) |
| P04 TLINE | 4 Trendline | the line through the last two known pivot lows (later one higher, 5+ bars apart, both in the series) projected to b; low[b] <= line + tol, close[b] > line, close[b] > open[b], and no close below the line from the second pivot to b-1 | mirror on the last two known pivot highs (later one lower) |
| P06 RETEST | 6 Retest | a level L broken up at bar j (close[j-1] <= L < close[j] - tol); within the 12 bars after j, the first bar b with low[b] <= L + tol and close[b] > L | mirror (broken down, retest from below holds) |
| P07 CONSOL | 7 Consolidation | the 12 bars b-12 .. b-1 span (max high - min low) <= 1.5 A(b-1); close[b] > their max high | close[b] < their min low |
| P09 HHHL | 9 Higher high / higher low | a pivot low becomes known at b (pivot bar b-3), above the previous pivot low, the last two known pivot highs rising, close[b] > that pivot low | - |
| P10 LHLL | 10 Lower high / lower low | - | mirror: a pivot high becomes known at b, below the previous pivot high, the last two pivot lows falling |
| P11 DTOP | 11 Double top | - | the last two known pivot highs P1, P2 are 5 - 40 bars apart, abs(P2 - P1) <= tol, the lowest low between them N <= min(P1, P2) - 1.0 A; the first close below N within 20 bars after P2, with no close above max(P1, P2) + tol in between |
| P12 DBOT | 12 Double bottom | mirror of P11 on pivot lows | - |
| P13 HS | 13 Head and shoulders | - | the last three known pivot highs LS, H, RS (in order; 5 - 60 bars from LS to RS): H >= max(LS, RS) + 0.5 A, abs(LS - RS) <= 0.5 A; troughs T1 = min low LS..H, T2 = min low H..RS; neckline through T1, T2 projected to b; the first close below it within 20 bars after RS |
| P14 IHS | 14 Inverse H&S | mirror of P13 on pivot lows (close above the neckline through the two peaks) | - |
| P15 ASC | 15 Ascending triangle | the last two known pivot highs within tol (flat top), the last two known pivot lows rising by >= tol, all four inside the last 40 bars; close[b] > max of the two highs (first close only) | - |
| P16 DESC | 16 Descending triangle | - | mirror: flat bottom, falling highs by >= tol, close[b] < min of the two lows |
| P17 SYM | 17 Symmetrical triangle | the last two known pivot highs falling by >= tol and the last two known pivot lows rising by >= tol, inside the last 40 bars; close[b] > the upper line (through the two highs) projected to b | close[b] < the lower line (through the two lows) |
| P18 FLAG | 18 Flag | a pole ending at bar p (b-13 <= p <= b-4): close[p] - low of bars p-5 .. p >= 3 A, high[p] the pole's top; the flag = bars p+1 .. b-1 with range <= 0.5 x pole and min low >= top - 0.5 x pole; close[b] > the flag's max high | mirror (pole down) |
| P19 WEDGE | 19 Wedge | falling wedge: the last two known pivot highs and lows all falling, the highs' line falling faster than the lows' (converging), inside the last 40 bars; close[b] > the upper line projected to b | rising wedge: all rising, the lows' line rising faster; close[b] < the lower line |
| P21 OB | 21 Order block | displacement: close[d+2] - open[d] >= 2 A over bars d .. d+2, with close[d+2] above the last known pivot high; the order block = the last bearish bar (close < open) among the 5 bars before d, zone [its low, its high]; within the 30 bars after d+2, the first bar b with low[b] <= zone high, close[b] >= zone low and close[b] > open[b] (a close below the zone low first kills it) | mirror |
| P22 FVG | 22 Fair value gap | bars i-2, i-1, i with low[i] - high[i-2] >= 0.3 A; zone [high[i-2], low[i]]; within the 30 bars after i, the first bar b with low[b] <= low[i], close[b] >= high[i-2] and close[b] > open[b] (a close below high[i-2] first kills it) | mirror |
| P24 EQHL | 24 Equal highs / lows | equal lows: two known pivot lows within 0.2 A, 5+ bars apart, inside the last 60 bars; pool = the lower; the first bar b with low[b] < pool and close[b] > pool (swept and back) | equal highs mirrored (high above the pool, close back below) |
| P25 PIN | 25 Candlestick rejection | lower wick >= 2 x body and >= 60% of the bar's range, range >= 0.8 A, low[b] <= the lowest low of the 12 bars before | mirror (upper wick, at the 12-bar high) |

Clarifications fixed now: "the last two known pivot highs" are the two most recent pivot highs known at b's close (no
others between them). P13's shoulder rule is abs(LS - RS) <= 0.5 A. A pattern instance signals once (its first qualifying
bar); a new instance needs a new pivot / zone / level break. Triangle, wedge and flag lines are straight lines through
(bar index, price) points in the continuous series.

## 5. Exits (both fixed; each pattern is tested with each = 42 tests)

| id | exit |
|---|---|
| X (plain) | R71's **XB**: option stop -15%, target +30% (limit), time stop 30 minutes, square-off 15:15 (15:05 on expiry; never reached, expiry days are skipped) |
| V (V5, as the arms) | R72 / R51's own exits **XL** (-15%, ladder L25, 30 minutes, 15:15) + breakeven at +10% + R186's **L500** step lock (+Rs 500 at +Rs 1,000 a lot net, then Rs 500 under each further Rs 500 step), through `r186engine.price(lock="L500")` - R193's V5 without the picker |

## 6. Statistics and the gate (fixed)

Per test, per period (design total, each fold, holdout): trades, win %, net after charges, average win / loss, max
drawdown (daily closed-trade equity), worst month, net without the 5 best trades.

- **Random twins**: 200 random-entry twins on the design period: the same (index, day, side, number of trades), entry
  rows uniform in 09:45 - 14:30, the same strike rule and exits (`engine.twin_totals` for X; R186's `price` for V). Twin p =
  (1 + twins >= actual) / 201. BH input: the one-sided normal-tail p of the actual design net against the twins (R190 /
  R193's rule).
- **BH**: q over m = **4,850** (the 4,808 entry files at the branch base, which count as p = 1, plus R202's 42), the log's
  convention since R190. The q within R202's 42 alone is reported too (descriptive).

A pattern-exit **passes** only if every item holds:

1. design: >= 100 trades, net > 0, net without the 5 best trades > 0, and net > 0 in at least 3 of the 5 folds;
2. twin p < 0.05 and BH q (m = 4,850) <= 0.10;
3. account stop at 1 lot in the design period and in the holdout: worst month >= -Rs 13,000 and max drawdown <= Rs 40,000;
4. holdout (read once): >= 40 trades and net > 0.

Anything that passes could be offered to Boss as a **paper** arm only (CLAUDE.md "PAPER"). Anything that fails stays a
note. Nothing here goes live or changes the app.

## 7. Tests and look-ahead guard

`test_r202.py` (pytest): each detector on hand-made bar shapes (it fires where it should and not on near-misses); the
**look-ahead guard**: for every pattern, the signals found on a series cut at bar b equal the signals of the full series
up to b (prefix invariance) on random walks and on real-shaped data; pivots known only 3 bars later; the book's one-at-a-
time rule; the holdout guard refuses a second read; BH and the stats helpers.
