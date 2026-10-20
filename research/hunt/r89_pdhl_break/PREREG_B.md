# R89B pre-registration: enhancing Boss's previous-day high / low break (written 20 Oct 2026)

Written and committed BEFORE any R89B statistic. Boss, 20 Oct, after R89 failed: "Can we enhance it and try something
more with it". Boss picked all four directions offered: bigger candles + wider stop, let winners run, breadth
confirmation, day-type filters. Boss also chose the unseen test data: **Aug 2021 - 19 Nov 2024**, which goes before the
Nov 2024 weekly-expiry change. That is an exception to Boss's 15 Oct rule, made by Boss for this test only.

## 0. What R89 taught (why these changes)

- At the exact levels the index moved only +0.4 to +3.5 points a trade in the rule's favour; the 1-ITM option's round
  trip costs 1.1-3.7 points. -> Make each trade bigger: longer candles, wider stops, winners held longer. The option cost
  is fixed per trade, so a bigger average move helps only if the edge in points grows with it.
- About a third of 1m trades were false breaks; the daily + 15m trend filter did not help. -> Try confirmation from the
  other indices (R72 Breadth Donchian, the arm that pays, uses it), the opening range and gap days instead.
- Both readings lost the same; "break and go" (C) is kept (the reclaim reading R is dropped to keep the count small).

**Disclosed before this plan:** R89's descriptive `old_futures.json` already showed the index-only (futures, with
slippage) results of R89's 20 variants on Aug 2021 - Nov 2024. No OPTION result of any rule on that window has been
computed, and none of the R89B variants below has been run on any window.

**Signal source of every rule: the UNDERLYING** (index spot 1-minute OHLC; breadth reads the other indices' spot). The
1-ITM option is only what is bought. No option chart, no option-chain data.

## 1. Data and split

- Indices: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX. MCX is dropped: its option prints start Aug 2025, so it has
  no unseen data.
- **Design: 20 Nov 2024 - 19 Oct 2026** (R89's design and its read holdout together; already seen by R89 for R89's
  variants, so this is honestly a design period, not a test).
- **Holdout (locked, read ONCE): 2 Aug 2021 - 19 Nov 2024.** Real held-contract prints from the lake's WEEK option files
  (R51 loader, Amendment A clean + stale-print step), weekly expiries as they traded then. Coverage: NIFTY, BANKNIFTY,
  FINNIFTY from Aug 2021; MIDCPNIFTY options from about Jan 2023 (2022 file nearly empty); SENSEX options from 15 May
  2023 (BSE relaunch). Days without option data trade nothing. Lots in force on each date (R51 table).
- Design walk-forward folds: Dec 2024 - May 2025, Jun - Nov 2025, Dec 2025 - May 2026, Jun - 19 Oct 2026.

## 2. Common rules (unchanged from R89 unless a variant says so)

Reading C on PDH / PDL with a close break; the signal candle = the first later same-colour candle closing beyond the
level (within 15 candles; a close back through cancels); entry when the next candle breaks its extreme (1-minute
resolution); triggers 09:16 - 14:45; at most 3 trades a day per index, one open at a time; square-off 15:10 (15:05 on
expiry). Option: 1-ITM, next-minute open, R51 engine fills (5 bps + 1/2/4 ticks), dated charges, sold one minute after
an underlying exit. 1 lot (fixed-risk view as in R89, not gated). Random-entry twins: 500, same days / sides / counts,
uniform entry minute in the variant's window, the same exit rule from the twin's own entry (same initial risk distance;
for the swing-target exit also the same reward distance).

## 3. The pieces

- **TF15:** break, signal candle, trigger and swings on 15-minute candles (09:15-anchored).
- **TF5:** the same on 5-minute candles.
- **Candle stop:** the signal candle's other end (R89). **ATR stop:** entry -/+ 1.0 x ATR14 of the 15-minute candles
  (mean true range of the last 14 completed 15m candles, continuous across sessions, before the trigger minute).
- **SWING exit (R89):** target = nearest confirmed fractal (N = 5) swing beyond entry with reward >= 1 x risk; none -> no
  trade.
- **RUN exit (let winners run):** no target. Once the underlying's minute high (put: low) reaches entry + 1R (R = the
  initial risk), the stop moves to the entry level from the next minute; from then on the stop trails at the best high
  since entry - 1R (put: best low + 1R), never loosening, updated from the next minute. The stop is checked on the
  minute's low (put: high) before the minute's high updates the trail; on the trigger minute only its close counts.
  Session end as usual.
- **BREADTH:** a long at minute t only if at least 4 of the 5 indices (this one included) have had a 1-minute CLOSE above
  their own PDH at some minute before t that day (puts: below their PDL). An index with no bars that day (or no PDH)
  counts as not broken (in 2021 MIDCPNIFTY has no index minutes: then 4 of the other 4 are needed).
- **OR:** opening range = high / low of 09:15-09:44. Triggers only from 09:45; a long only if its entry level is above the
  range high, a put only if below the range low.
- **GAP:** skip a day whose first open is more than 0.5% away from the previous close.

## 4. Variants (9, declared now; nothing else will be run)

| id | candles | stop | exit | filters |
|---|---|---|---|---|
| B0 | 1m | candle | SWING | none (= R89 C-B, the reference) |
| B1 | 15m | candle | SWING | none |
| B2 | 15m | candle | RUN | none |
| B3 | 15m | candle | RUN | BREADTH |
| B4 | 15m | candle | RUN | OR |
| B5 | 15m | candle | RUN | GAP |
| B6 | 5m | ATR | RUN | none |
| B7 | 5m | ATR | RUN | BREADTH |
| B8 | 15m | candle | RUN | BREADTH + OR + GAP |

9 variants x 5 indices = **45 cells**; Benjamini-Hochberg over all 45 design twin p-values. Pooled rows shown, not
tested. Family `patterns-indicators` (R89's 180 cells are listed beside it in the write-up).

## 5. Gates (never relaxed)

Design cell passes only if: >= 40 trades; option net > 0; twin p < 0.05; BH q < 0.10 over the 45; net > 0 in >= 3 of the
4 folds; net > 0 without the 5 best trades; worst calendar month >= -Rs 13,000.

Holdout (`study_b.py final`, guarded by `results_b/FINAL_READ`), read ONCE for all 45 cells after the design results and
experiment entries are committed. A design passer becomes a PAPER candidate only if in the holdout: >= 40 trades, net > 0,
twin p < 0.10 with BH q < 0.10 over the design passers' holdout p-values, worst month >= -Rs 13,000, and net > 0 in at
least 2 of the 3 holdout blocks (2 Aug 2021 - 31 Dec 2022, 2023, 1 Jan - 19 Nov 2024). A candidate gets an APP_SPEC for a
PAPER arm only (logged, undoable, behind a switch that is off by default), plus a forward check on new sessions before
any wider use. Nothing goes live. Failures stay notes.

## 6. Look-ahead guard (pytest)

For random synthetic days and cut minutes: every trigger at or before the cut, with its entry, initial stop, ATR, breadth
count, opening-range test and gap test, is identical when every minute after the cut (on every index) is replaced by
noise. The RUN exit is checked against a hand-worked path (breakeven move, trail, never loosening).
