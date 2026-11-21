# R202: the 25 common price-action concepts as arm candidates - result

21 Nov 2026. Research only: nothing in the app changes, nothing is merged. Owner: Boss (they/them).
Pre-registration: `PREREG.md` (pushed before any number, commit 30a507a7). Design results were committed before the
holdout was read (commit d6fe3d6f); the holdout was read once (`results/HOLDOUT_READ`).
Full tables: `results/report.md`; numbers: `results/design.json`, `results/holdout.json`, `results/decision.json`.

## Verdict in one line

**None of the 21 patterns passes, with either exit (0 of 42 tests).** 39 of the 42 lose money over the 4.3-year design
period after charges. Not one is fit to be a paper arm. These are not lost near-misses either: every pattern repeats an
earlier failed test in the log (section 5).

## 1. What was tested (signal source: the index)

- **Signal:** each pattern is detected on the index's own 5-minute bars (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX).
  The bars are built from 1-minute index minutes. A pivot (3 bars each side) counts only once its 3 later bars have
  closed. Tolerances scale with a 14-bar ATR. Exact rules: `PREREG.md` section 4 and `r202pat.py`.
- **Trade:** buy the 1-ITM option (call bullish, put bearish) at the next minute's open. Entries 09:45 - 14:30 only,
  never on the index's expiry day. One position at a time per pattern and index, at most 3 a day. 1 lot. Dated Zerodha
  charges and R17 fills (R51's engine).
- **Exits:**
  - **X (plain):** -15% / +30% / 30 minutes.
  - **V (V5 as the arms use it):** the arms' own exits (XL: -15%, ladder L25, 30 minutes) plus breakeven at +10% and
    R186's L500 step lock (`r186engine.price`).
- **Periods:**
  - **Design:** 1 Dec 2021 - 31 Mar 2026. This is the walk-forward part: 5 folds read in time order (F1 Dec 2021 - 2022,
    F2 2023, F3 2024, F4 2025, F5 Q1 2026). Nothing is fitted, so every fold is out of sample.
  - **Holdout:** 1 Apr - 13 Nov 2026, read once.
- **Gate** (all must hold):
  - design: >= 100 trades, net > 0, net without its 5 best trades > 0, at least 3 of 5 folds positive;
  - better than 200 random-entry twins (same day, index, side and count): twin p < 0.05;
  - BH q <= 0.10 over m = 4,850 (the log's 4,808 entries plus these 42);
  - account stop in both periods: worst month >= -Rs 13,000 and max drawdown <= Rs 40,000;
  - holdout: >= 40 trades and net > 0.

## 2. Results per pattern (1 lot; net after charges; Rs)

Worst month and DD are for the design period. "folds +" counts positive design folds. q is over the whole log
(m = 4,850).

| # | pattern | exit | design trades | win % | net | worst month | max DD | folds + | holdout trades | win % | net | holdout worst month | twin p | q | verdict |
|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| 1 | Support bounce | X | 7,310 | 41.7 | -10,09,786 | -80,161 | 10,11,288 | 0/5 | 1,330 | 42.7 | -1,55,273 | -42,932 | 0.975 | 1 | fail |
| 1 | Support bounce | V | 7,400 | 48.5 | -10,45,491 | -62,735 | 10,45,491 | 0/5 | 1,350 | 48.2 | -1,92,651 | -62,031 | 0.975 | 1 | fail |
| 2 | Resistance rejection | X | 7,170 | 39.9 | -5,54,907 | -62,214 | 6,04,838 | 1/5 | 1,368 | 43.3 | -1,61,837 | -63,800 | 0.269 | 1 | fail |
| 2 | Resistance rejection | V | 7,260 | 48.9 | -5,99,301 | -76,299 | 6,22,003 | 0/5 | 1,380 | 52.2 | -1,43,833 | -51,322 | 0.224 | 1 | fail |
| 3 | Trend (pullback to EMA20 in HH/HL or LH/LL) | X | 8,091 | 40.8 | -8,59,473 | -1,05,151 | 8,72,842 | 0/5 | 1,429 | 42.8 | -1,30,714 | -63,562 | 1.000 | 1 | fail |
| 3 | Trend | V | 8,164 | 47.9 | -8,63,727 | -86,053 | 8,72,319 | 0/5 | 1,443 | 48.5 | -1,62,156 | -53,471 | 1.000 | 1 | fail |
| 4 | Trendline bounce | X | 6,710 | 38.9 | -9,54,141 | -68,949 | 9,58,427 | 0/5 | 1,144 | 41.6 | -1,64,067 | -55,244 | 1.000 | 1 | fail |
| 4 | Trendline bounce | V | 6,764 | 46.5 | -9,06,276 | -55,689 | 9,27,004 | 0/5 | 1,152 | 47.5 | -1,65,559 | -50,675 | 1.000 | 1 | fail |
| 6 | Retest of a broken level | X | 8,375 | 41.3 | -8,03,257 | -99,309 | 8,67,308 | 0/5 | 1,539 | 43.1 | -1,75,847 | -85,122 | 0.990 | 1 | fail |
| 6 | Retest | V | 8,440 | 49.2 | -8,30,770 | -60,003 | 8,41,465 | 0/5 | 1,557 | 50.3 | -2,12,723 | -58,219 | 0.985 | 1 | fail |
| 7 | Consolidation (tight box) breakout | X | 20 | 35.0 | -6,636 | -2,657 | 6,771 | 1/5 | 5 | 20.0 | -3,692 | -3,738 | 0.985 | 1 | fail (too few) |
| 7 | Consolidation | V | 20 | 40.0 | -3,632 | -2,657 | 4,751 | 1/5 | 5 | 20.0 | -3,692 | -3,738 | 0.925 | 1 | fail (too few) |
| 9 | Higher high / higher low | X | 5,854 | 42.0 | -5,84,483 | -47,756 | 5,91,545 | 0/5 | 1,083 | 38.1 | -1,91,186 | -59,994 | 1.000 | 1 | fail |
| 9 | Higher high / higher low | V | 5,879 | 48.2 | -6,50,269 | -46,215 | 6,50,269 | 0/5 | 1,092 | 43.6 | -2,22,094 | -50,123 | 1.000 | 1 | fail |
| 10 | Lower high / lower low | X | 5,183 | 39.4 | -6,43,381 | -72,782 | 6,96,036 | 0/5 | 962 | 44.8 | -83,008 | -31,268 | 1.000 | 1 | fail |
| 10 | Lower high / lower low | V | 5,218 | 48.3 | -6,51,818 | -65,305 | 6,74,694 | 0/5 | 966 | 51.1 | -81,352 | -33,881 | 1.000 | 1 | fail |
| 11 | Double top | X | 591 | 38.1 | -71,116 | -18,078 | 87,838 | 1/5 | 133 | 39.1 | -29,887 | -20,217 | 0.985 | 1 | fail |
| 11 | Double top | V | 591 | 46.4 | -82,487 | -13,482 | 91,232 | 1/5 | 133 | 47.4 | -30,580 | -18,470 | 0.995 | 1 | fail |
| 12 | Double bottom | X | 553 | 42.7 | -30,523 | -15,056 | 62,872 | 2/5 | 106 | 40.6 | -4,058 | -8,097 | 0.706 | 1 | fail |
| 12 | Double bottom | V | 553 | 48.6 | -36,142 | -10,862 | 55,311 | 2/5 | 107 | 55.1 | +5,444 | -8,538 | 0.766 | 1 | fail |
| 13 | Head and shoulders | X | 163 | 52.1 | **+34,086** | -5,479 | 10,396 | 4/5 | 28 | 57.1 | -4,063 | -7,566 | 0.010 | 1 | fail |
| 13 | Head and shoulders | V | 163 | 60.1 | +4,464 | -3,773 | 8,541 | 2/5 | 28 | 60.7 | -1,892 | -5,494 | 0.199 | 1 | fail |
| 14 | Inverse head and shoulders | X | 147 | 43.5 | -3,881 | -2,981 | 20,282 | 3/5 | 20 | 65.0 | +3,932 | -438 | 0.269 | 1 | fail |
| 14 | Inverse head and shoulders | V | 147 | 49.0 | -15,470 | -3,116 | 19,559 | 1/5 | 20 | 65.0 | +6,031 | -521 | 0.587 | 1 | fail |
| 15 | Ascending triangle | X | 571 | 40.6 | -65,952 | -11,504 | 79,744 | 0/5 | 78 | 47.4 | -20,350 | -9,988 | 1.000 | 1 | fail |
| 15 | Ascending triangle | V | 572 | 46.3 | -76,972 | -10,031 | 78,473 | 0/5 | 78 | 55.1 | -12,059 | -8,121 | 1.000 | 1 | fail |
| 16 | Descending triangle | X | 422 | 41.0 | -3,749 | -15,894 | 28,782 | 3/5 | 106 | 46.2 | -8,493 | -11,238 | 0.801 | 1 | fail |
| 16 | Descending triangle | V | 425 | 53.2 | +6,648 | -12,125 | 24,886 | 3/5 | 106 | 57.5 | +1,097 | -6,491 | 0.463 | 1 | fail |
| 17 | Symmetrical triangle | X | 3,197 | 41.8 | -2,78,851 | -50,368 | 2,91,080 | 0/5 | 623 | 42.1 | -83,102 | -34,818 | 0.726 | 1 | fail |
| 17 | Symmetrical triangle | V | 3,208 | 49.6 | -2,84,013 | -38,202 | 2,97,415 | 0/5 | 624 | 47.8 | -73,970 | -19,410 | 0.711 | 1 | fail |
| 18 | Flag | X | 3,778 | 39.8 | -5,81,356 | -1,01,811 | 6,45,359 | 0/5 | 823 | 45.2 | -21,227 | -29,148 | 1.000 | 1 | fail |
| 18 | Flag | V | 3,913 | 50.3 | -4,74,588 | -77,629 | 5,14,045 | 0/5 | 845 | 53.3 | -52,930 | -24,888 | 1.000 | 1 | fail |
| 19 | Wedge (rising -> put, falling -> call) | X | 4,652 | 39.2 | -5,24,678 | -51,995 | 5,57,605 | 0/5 | 852 | 43.0 | -41,248 | -35,649 | 0.005 | 6.0e-06 | fail |
| 19 | Wedge | V | 4,656 | 46.6 | -5,65,530 | -48,019 | 5,86,225 | 0/5 | 856 | 50.2 | -29,121 | -24,072 | 0.005 | 3.7e-04 | fail |
| 21 | Order block | X | 2,385 | 38.3 | -4,17,194 | -37,475 | 4,24,546 | 0/5 | 407 | 40.3 | -1,06,945 | -33,767 | 1.000 | 1 | fail |
| 21 | Order block | V | 2,419 | 47.2 | -3,76,311 | -36,243 | 3,88,966 | 0/5 | 412 | 47.6 | -1,16,197 | -38,129 | 1.000 | 1 | fail |
| 22 | Fair value gap | X | 8,952 | 40.5 | -10,42,169 | -86,534 | 10,61,932 | 0/5 | 1,612 | 42.5 | -1,88,743 | -49,304 | 1.000 | 1 | fail |
| 22 | Fair value gap | V | 9,033 | 48.7 | -9,94,235 | -72,522 | 10,22,324 | 0/5 | 1,629 | 48.7 | -2,48,448 | -63,597 | 1.000 | 1 | fail |
| 24 | Equal highs / lows sweep | X | 1,910 | 40.2 | -2,80,788 | -23,142 | 2,86,933 | 0/5 | 341 | 37.2 | -78,667 | -24,559 | 0.025 | 1 | fail |
| 24 | Equal highs / lows sweep | V | 1,927 | 44.6 | -3,53,583 | -25,286 | 3,53,583 | 0/5 | 345 | 42.6 | -80,711 | -21,772 | 0.512 | 1 | fail |
| 25 | Candlestick rejection (long wick) | X | 4,154 | 41.9 | -3,59,779 | -59,751 | 4,29,904 | 1/5 | 793 | 38.0 | -2,04,593 | -72,237 | 0.005 | 4.1e-18 | fail |
| 25 | Candlestick rejection | V | 4,170 | 47.5 | -4,46,788 | -57,860 | 4,75,054 | 0/5 | 801 | 42.8 | -1,78,173 | -70,754 | 0.005 | 1.8e-10 | fail |

Every failed item for each test is listed in `results/report.md` ("Why each failed"). The design net per index is there
too. Fold-by-fold nets are in `results/design.json`.

## 3. How to read the few bright spots (honestly)

- **Head and shoulders, plain exit (P13-X)** is the only pattern that made money in the design period:
  - 163 trades over 4.3 years, 52.1% won, +Rs 34,086; 4 of 5 folds positive; inside the account stop; twin p 0.010.
  - It fails BH (q 1.0 over the log; even inside R202's own 42 tests it is only q 0.033).
  - It lost on the unseen holdout: 28 trades, -Rs 4,063, too few trades to count.
  - That is about 38 trades a year across five indices: too rare to carry an arm, and indistinguishable from luck at
    this sample.
  - With V5 the same entries made only +Rs 4,464.
- **Descending triangle with V5 (P16-V)** made +Rs 6,648 on 425 trades (+Rs 1,097 on 106 holdout trades). Without its 5
  best trades it loses -Rs 11,054, and it is no better than random entries (twin p 0.46).
- **Inverse H&S and the double bottom** made small profits in the holdout (+Rs 3,900 to +Rs 6,000) on 20 and 107 trades.
  Both lost over the design period.
- **Wedge (P19) and candlestick rejection (P25)** have tiny q values, but they **lose heavily** (-Rs 3.6 lakh to
  -Rs 5.7 lakh). The twins keep each trade's day and side. These are reversal patterns that buy calls on falling days,
  and random calls on those same days lose even more. So "better than random" here means "a slightly better minute to
  lose on". It is not an edge.
- **V5 versus the plain exit:** V5 raises the win rate by 4-12 points on every pattern (e.g. support 41.7% -> 48.5%,
  flag 39.8% -> 50.3%). It does not turn any of them profitable, which is the same finding as R179 / R186 / R193.

## 4. Why these lose (plain words)

Buying an option pays the spread, charges and time decay on every trade (random entries on the same days lost about
Rs 30-240 a trade here). A pattern has to call the next 30 minutes of the index well enough to beat that.
On 5-minute index bars, these textbook shapes are frequent, and their direction is close to a coin flip (39-43% wins on a
2:1 plain exit). The common ones (support, trendline, retest, fair value gap, trend pullback) fire 1,500-2,000 times a
year and bleed Rs 1.5 - 2.5 lakh a year each. The clean shapes (H&S, double tops, triangles) are rare and not reliably
directional.

## 5. Earlier tests of the same ideas (all failed; R202 agrees)

- **S/R:**
  - R20B (`r20sr-d`, bounce at pivot / OI levels: design -Rs 30,003 on 387 trades);
  - R89 (`r89-c-sr`, -Rs 4.2 lakh);
  - R150 (3+-turn levels on R51 / R72);
  - R194 (S/R filters, targets and picker features: "does not help");
  - `book-woods-19` (broken-level flip, -Rs 7.3 lakh).
- **Trend / HH-HL / LH-LL / MSS:** R71 P8 (final -Rs 40,109), R139 (0 of 43, incl. BOS, CHoCH, MSS), R140 (structure as
  skips / exits, 0 of 12), R90 (Almazov BOS, 0 of 99).
- **Trendline:** R104 books `atb-06`, `heitkoetter-01` (-Rs 2.1 lakh), `tom-08` (-Rs 4.3 lakh), `woods-20`.
- **Retest:** `r22-retest` (-Rs 20,547 design), `book-atb-06/07/08/11`.
- **Consolidation / box / NR:** R71 P1-P3 (inside bar, NR-N, squeeze; all lose in the final), R115 N51 / N72, `book-atb-11/17`.
- **Double top / bottom, triple top:** R71 P5 (design -Rs 94,311), `book-atb-13` (-Rs 1.1 lakh), R138 (0 of 18).
- **H&S:** `book-atb-14` (-Rs 17,408), `book-woods-16` (-Rs 46,244; holdout -Rs 75,273).
- **Triangles:** `book-atb-07`, `atb-09`, `woods-18`, `atb-16` (pennant): all lose.
- **Flags:** R71 P4 (32 final trades, about flat), `book-atb-10`, `atb-12`, `aziz-06`.
- **Wedges:** `book-atb-08`, `atb-15` (-Rs 95,017).
- **Order block / FVG:** R62 / R62B (0 of 16), R130 (sweep + MSS + FVG, 0 of 6), R131 S6 IFVG (-Rs 12.2 lakh), S8 order
  block (-Rs 5.1 lakh).
- **Equal highs / lows:** never tested as an entry before. R202 is the first test (-Rs 2.8 lakh, plain exit).
- **Candlestick rejection:** R87 ("a long wick is not a defended level", 0 of 4,771 cells), `book-woods-06`,
  `book-atb-01`.

The four concepts not rebuilt (as asked), from R193 (1 Nov 2024 - 13 Nov 2026, 1 lot) and the log:

| concept | already in the app as | record |
|---|---|---|
| 5 Breakout | R51 Donchian 20, R72 Breadth Donchian | R72 + picker: 2,256 trades, 52.3% win, Rs 6,52,008. R51 + picker: 2,890 trades, 49.5%, Rs 4,88,964. Both made money only from Nov 2024; they lost on older periods (R143 / R144a). |
| 8 Range | Range Fade (paused) | R3 rebuild: 3,512 trades, 30% win, -Rs 5,10,978, 0 of 6 years positive |
| 20 Liquidity sweep | Liquidity 15+5 | 1,323 trades, 41.5% win, Rs 2,55,293 (W2 only Rs 5,717). Note: it buys the break of a pool, not a sweep fade; true sweep fades (R91, R130, R131 S1 / S7) all lost. |
| 23 MSS | CISD (R133 S3-V2-E3, paper arm, failed the gates) | design 413 trades, 69% win, Rs 1,07,697; only 35 holdout trades (R188: 0 of 23 variants pass) |

So the price-action ideas that do make money in this account's data are the breakout ones the arms already trade (R51,
R72, Liquidity's pool breaks). The reversal and continuation shapes from the chart do not, on any period.

## 6. Which could be paper arms?

**None.** No pattern passed the gates, so none qualifies under CLAUDE.md's PAPER rule. The only paths to a paper arm would
be Boss's explicit exception (as with S5 Trail / CISD), and the data give no reason to make one:
- the best candidate (H&S, plain exit) has about 38 trades a year and lost in the holdout;
- the rest lose lakhs.

## 7. Honest notes on definitions

- **Not objective by nature.** Trendlines, wedges, flags, H&S and order blocks depend on which swings a trader chooses to
  draw through. R202 fixed one mechanical reading of each:
  - pivots of strength 3 on 5-minute bars;
  - the last two or three pivots;
  - ATR-scaled tolerances.
  A different reading (15-minute bars, other pivots, other tolerances) would give different trades. But the log has
  tried most other readings (section 5) with the same result. No grid was searched here, on purpose (a grid would only
  find noise to fit).
- **Consolidation (P07)** traded only 25 times in 5 years. The pre-registered box (12 bars spanning <= 1.5 ATR, where the
  ATR is mostly the box's own bars) turned out almost impossible to meet. That is a definition that did not work, not
  evidence about consolidations. The looser readings (NR-N, inside bars, squeezes in R71 / R115) all lost.
- **Support / resistance as an entry** is defined here as a bounce or rejection at a 3+-touch level. S/R as a filter or
  target on the arms was R194's question (no help).
- **Trend (3), HH/HL (9) and LH/LL (10)** share the same pivots, so they are not independent tests (BH counts all of
  them anyway).
- **Not in the pre-registration, fixed in code before any run:** if one pattern fires a call and a put on the same bar,
  both are dropped as ambiguous (`r202pat.day_signals`). This is rare (only the two-sided patterns can do it).
- **Order block and FVG** follow common ICT definitions (3-bar displacement, last opposite candle; 3-bar gap). ICT
  readers disagree on details; R131 / R62B tested other versions.

## 8. Checks

- `test_r202.py`: 111 tests, all passing. They cover:
  - every detector on hand-made shapes, with near-misses;
  - the look-ahead guard: prefix invariance for all 21 patterns on 4 random walks, and a future shock never moves a
    past signal;
  - pivots known only 3 bars late;
  - the one-position book;
  - the holdout read-once lock;
  - the gate.
- The cached random twins equal R51's `engine.twin_totals` exactly (X), and pricing each twin through R186's `price`
  (V, difference 9e-13).
- `python -m research.brain.features catalog --check`: up to date.
- `python -m pytest research/brain/tests`: **not green, for reasons outside R202** (R202 changes nothing in
  `research/brain`):
  - the container came with pandas 3.0.5: 7 failed, 3 errors;
  - under pandas 2.3.3: 2 failed (`test_dictionary` volume-profile, `test_lookahead`), 3 errors (`test_mcx_levels`:
    "window shape cannot be larger than input array shape", "Expected numeric dtype").
  - R202's own data are unaffected: a store rebuilt under pandas 2 equals the pandas 3 one on 1,185 of 1,185 days.
- Data: the Drive lake copy of 21 Nov 2026 (index_1m and options WEEK 2021 - 2026 with the 13 Nov fill), R51 clean
  stores under `~/.cache/iraalgo/r202` (never in git). Coverage:
  - NIFTY, BANKNIFTY and FINNIFTY options from 1 Dec 2021;
  - MIDCPNIFTY from 31 Jan 2022;
  - SENSEX from 15 May 2023.
- Holdout honesty: Apr - Nov 2026 had been read by earlier arm studies (R151 - R201), never with these patterns.
- Logged: `research/experiments/entries/r202-p01-x.yaml` ... `r202-p25-v.yaml` (42 entries, all "rejected").
