# R87B pre-registration: real option prices, bigger timeframes, candle sequences, candles as exits

Written 19 Oct 2026, committed BEFORE any R87B statistic or P&L. Boss, after R87: *"Can we enhance it"*; chosen: all four
of real option data, bigger timeframes, candle sequences, exit and stop use. Research only: nothing in `android/**` or
`.github/**` changes. Signal source of everything below: the index's own candle (NIFTY, BANKNIFTY, SENSEX spot).

## 0. What R87 already used (disclosed)

R87's locked holdout (15 Oct 2025 - 16 Oct 2026) was read once for the 1m / 5m / 15m single-candle cells and the
shape x context cells. R87B's cells (30m, 60m, daily candles; 3-candle sequences and clusters; exit overlays; R87B
rules) were never measured there, so the same window is their holdout, read once (`results/b/FINAL_READ`), with the
disclosure that the window's general character was seen in R87. R87's design-window tables were seen; R87B's
choices below are fixed from them only where stated (the matched baseline, the exit set of R87's rules).

## 1. Data

- Index minutes and the cleaning of R87 (A2). Daily index candles from Dhan's daily endpoint, 1 Jan 2015 -
  16 Oct 2026, weekdays only.
- **Real option minutes (B4):** the traded contracts only: offsets -1 call, +1 put and the ATM pair, nearest expiry,
  20 Nov 2024 - 16 Oct 2026, NIFTY / SENSEX weekly, BANKNIFTY monthly (`fetch.py` with `R87_ONLY_RULES=1`). The
  build folder is `R87_OUT=~/.cache/iraalgo/r87/built_b`; the option table is rebuilt once when the download ends
  (or at the cutoff in section 6) and that snapshot is used by every R87B P&L.
- **Pricing = 'hybrid' (`rules._HybridCon`):** the real minute where the held contract printed; where it did not (it
  moved beyond ATM+-1, or no trade that minute) the modelled premium (R87 A1) x real/model at the last minute both
  existed that day (1 before the first real print). Filled minutes carry no volume, so fills there pay 4 ticks. Every
  table reports the share of trades whose entry and exit were real prints.

## 2. B1: bigger timeframes (`batlas.run_b1`)

- 30m and 60m candles from 09:15 (half the minutes needed; the 60m day has 6 candles, 15:15 is dropped), and daily
  candles.
- Families: R87's 35 shapes and patterns (footprints are not tested: no activity data for most of the history).
- Primary outcome: 30m and 60m: the move over the next 3 candles of the same session (f_ret3, 90 / 180 minutes);
  daily: the next 5 days (f_ret5, sessions crossed).
- Baseline: R87's matched baseline (index, timeframe, time-of-day bucket, trend, volatility regime, colour); daily
  without time of day.
- Split: intraday design 4 Aug 2021 - 14 Oct 2025 (folds as R87); daily design 1 Jan 2015 - 14 Oct 2025, folds
  2015-17, 2018-20, 2021-22, 2023 - 14 Oct 2025.
- Test: one BH family over every B1 cell with n >= 50 (q < 0.10); a finding also needs the same sign in >= 3 of 4 folds.

## 3. B2: sequences (`batlas.run_b2`)

- Token per candle: D (body <= 10%) or G/R + S (body 10-50%) / B (> 50%), then wick tilt U / L / N (upper minus lower
  >= 20% of range, the reverse, else N): 15 tokens. A sequence = the last 3 candles of one session.
- B2seq: every 3-token sequence with n >= 200 in design, per index x timeframe (5m, 15m). One BH family.
- B2cl: k-means (k = 24, seed 87) on 12 numbers per sequence (each candle's colour x body %, upper %, lower %,
  log(range / ATR) clipped to +-2), fitted on design only, per timeframe (5m, 15m) pooled over the 3 indices; the
  holdout is assigned to the fixed design centres. One BH family over cluster x index x timeframe (n >= 50).
- Primary: f_ret5 (5 candles, same session); baseline, folds and finding rule as B1.

## 4. B3: candles as exits for breakout arms (`exits.py`)

- Two arms reconstructed from the index's 5m candles (labelled "reconstructed", not the app's exact arms): DON
  (Donchian-20 break, out after 30 min) and ORB (first break of the 09:15-09:29 range each side, out after 60 min); 1-ITM
  option, premium stop 15%, lock (+20% -> +10%, trail 10%), square-off; entries 09:30-14:30, one open per arm x index.
- Overlays (exit at the next minute's open after a 5m candle that closes against the position after the entry):
  X1 big wick against, X2 marubozu against, X3 engulfing against. Same entries re-exited.
- Window: 20 Nov 2024 - 14 Oct 2025 (folds Feb-Apr, May-Jul, Aug-Oct 2025); hybrid pricing.
- Pass (all): paired difference > 0; day-clustered p with BH over the 6 arm x overlay pairs q < 0.10; > 0 in >= 2 of 3
  folds; the variant's worst day and max drawdown no worse than the base. Holdout once for passes only.
- A pass is a PAPER-only candidate (CLAUDE.md: a risk-lowering exit that passed the gates may act on paper arms,
  logged in the Autopilot timeline, undoable, never carried to live by itself). Nothing is built in this study.

## 5. B4: at most 3 R87B rules + R87's rules on real prices (`brules.py`)

- Selection exactly R87 section 8.1 over B1 (30m, 60m) + B2seq + B2cl (5m, 15m) design findings; the cost screen uses
  the hybrid 1-ITM premium; one rule per family; empty slots filled from cells failing only the cost screen
  (labelled); none eligible = no rule. Hold = the primary horizon (5m: 25 min, 15m: 75, 30m: 90, 60m: 180), square-off
  15:10 / 15:05. Exits, variants, walk-forward, 500 twins and gates exactly as R87; hybrid pricing.
- R87's three rules are re-priced on hybrid prices over the same design window (descriptive; they failed in R87, the
  holdout is not read for them).
- Holdout once for R87B rules that pass design.

## 6. Order and cutoff

1. This file committed. 2. B1 / B2 design (no option data needed). 3. Option download ends (cutoff: 19 Oct 2026
   23:30 UTC; whatever has landed then is the snapshot, coverage reported). 4. B3 design. 5. `brules select`, the
   frozen rules committed as amendment B-A1 BEFORE any P&L. 6. B4 design. 7. One holdout read (`bfinal.py`).
8. Write-up section in `research/HUNT_R87_CANDLE_ANATOMY.md`, experiments entries, pack update.

## Amendment B-A1 (19 Oct 2026, 23:40 UTC, after the B1/B2 design atlas and the B3 exit design, BEFORE any R87B rule P&L)

**Option data at the cutoff.** Dhan's option endpoint stayed degraded (80 s - 5.5 min a request, HTTP 504) and the
serial fetcher stalled after 21:37 UTC; SENSEX contracts had been put first at 21:30 (a coverage choice: R87's near miss
and the only R87B candidate are SENSEX; it changes which minutes are real, not which trades are taken). The snapshot
(107 files) holds, from 20 Nov 2024: NIFTY ATM pair 199 design / 155 holdout days; BANKNIFTY ATM 0 / 59; SENSEX -1 C,
ATM, +1 P 0 / 51 (29 for +1 P) holdout days; NIFTY and BANKNIFTY -1 / +1: none. **So the design window is priced by the
calibrated model almost everywhere (real entry prints: 0.7-0.8% of B3 trades), and only SENSEX's holdout months carry
real 1-ITM prices.** Every R87B P&L reports its real-entry and real-exit shares; a design pass is therefore "on modelled
premiums" like R87, and the SENSEX holdout is the first real-price check.

**B3 result (design, recorded before any rule P&L):** 1 of 6 overlays passes: ORB + X1 (exit on a 5m big wick against
the position): +Rs 47,606 over 813 trades (base -Rs 50,071 -> -Rs 2,465), z 2.21, q 0.092, folds +16,961 / +29,348 /
+5,877, worst day -13,527 -> -12,743, max DD 89,452 -> 53,202. It goes to the holdout. DON X1-X3 and ORB X2-X3 fail
(X2 "marubozu against" HURTS both arms: -Rs 14,257 DON, -Rs 32,888 ORB).

**The frozen R87B rule (`results/b/brules_selected.json`, mechanical selection, section 5):**

| rule | signal (index candle at its close) | design n / effect / q / folds | rules-window move vs cost | trade |
|---|---|---|---|---|
| R87B-1 | SENSEX 15m **cluster15:13**: three medium candles (~0.8 ATR, bodies ~55%), green, green, then red (a pullback after two up candles) | 840 / +0.184 ATR / 0.034 / 4 of 4 | +15.4 pts (148) vs 6.41 | BUY SENSEX 1-ITM call, out after 75 min |

Only one cell was eligible, so only one rule is tested (BH over 1). Exits, variants (15% / 25% stop), walk-forward,
500 twins and gates as R87; hybrid pricing. R87's three rules are re-priced the same way (descriptive).
