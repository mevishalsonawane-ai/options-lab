# R5 pre-registration: the uploaded guide's Pine strategy ("Best Gann Sq9 + Sq144 + Fib + Harmonic Strategy (Magic#)")

Written 9 Oct 2026, BEFORE any R5 trade or P&L was computed (the code review in the report is of the script text only).
Nothing below changes after results are seen; anything added later is labelled POST-HOC in the report.

Source: `Gann_Fib_Harmonic_MagicNumber_Complete_Guide.md` (uploaded by Boss), section 6 Pine script, section 8 settings.

## What is replicated (bar-by-bar, exactly as the script)

- Bars: NIFTY and BANKNIFTY index, 15m / 30m / 1h, anchored 09:15 (TradingView NSE session; the last bar of the day is
  the 15:15-15:30 stub for 30m and 1h). Continuous bar_index across days (no session reset), as on a TradingView chart.
  Built from local Dhan index minutes (h37 `sigs.bars`).
- `ta.atr(14)` = Wilder RMA of true range (seed = SMA of the first 14 TR).
- `ta.pivothigh(high, L, R)` / `ta.pivotlow`: reported at bar i for bar i-R when high[i-R] is strictly greater than
  the L bars to its left and greater-or-equal to the R bars to its right (ties are rare at these timeframes; this is
  the assumption). Confirmed R bars late; added with bar = bar_index - R.
- `f_add`: push if the list is empty or |price - last pushed price| > ATR(now) x minSwingATR; list capped at 15; NO
  high/low alternation check; if a pivot high and a pivot low are reported on the same bar, the high is processed first.
- Square-of-144 window: barsSince = bar_index - bar of the LAST list pivot; in window if |barsSince - d| <= 3 for d in
  {36, 48, 72, 96, 108, 144}.
- Harmonic block runs on EVERY bar with >= 5 list pivots, on the last five (X, A, B, C, D), ratios with abs(), tolerance
  0.045; gartley / bat / butterfly / crab exactly as coded; isBull = last pivot is a low. Signal if patternOK and timeOK
  and bar_index - lastSigBar > cooldown (lastSigBar starts at 0).
- Orders (process_orders_on_close = true): the entry is a market order filled at the signal bar's CLOSE. A signal in the
  direction of an open position does not add (pyramiding 0) but re-issues strategy.exit, i.e. the stop / limit are
  replaced by the new values. An opposite signal reverses at the close. Exit stop = X -/+ 0.25 ATR, limit = TP1 = D +/-
  1.8 x |D - stop| (TP2 is computed but never used). Exit orders are also checked at the close of the entry bar (if the
  close is already beyond the stop or the limit, the trade exits at that close). From the next bar: a gap through a
  level fills at the open; otherwise TradingView's broker-emulator path (open -> nearer extreme -> other extreme ->
  close) decides which level is touched first; the fill is at the level. Overnight holding allowed (as written).
- Sizing: the script's 8% of equity is REPLACED by 1 unit (index points) / 1 lot (options). No compounding.
- Magic Number: label/comment text only, no effect.

## Configurations (3, fixed)

- AS_WRITTEN: useSq144 = true (default script).
- PRAG: useSq144 = false.
- CONFL ("true confluence", what the guide claims but the code does not do): PRAG plus D (the last pivot) must lie
  within 0.1% of (a) a Square-of-9 level (sqrt(Cp) +/- n)^2, n in {0.25, 0.5, 1, 1.5, 2}, anchored on the previous
  pivot Cp, OR (b) a Fibonacci retracement 0.382 / 0.5 / 0.618 / 0.786 of the previous leg (B -> C): C - r (C - B).

## Settings grid (per timeframe, guide section 8; tolerance 0.045, SL buffer 0.25, TP1 1.8 fixed)

- 15m and 30m: pivot (L = R) {8, 10} x minSwingATR {0.9, 1.1} x cooldown {12, 15}, plus the script default (10, 1.0, 15).
- 1h: pivot {10, 12} x minSwingATR {1.0, 1.3} x cooldown {15, 20}, plus the script default (10, 1.0, 15).
- 9 settings x 3 timeframes x 2 indices x 3 configs = 162 strategy runs.
- PRIMARY (confirmatory) setting = script default (10, 1.0, 15), which lies inside the recommended range for all three
  timeframes. 18 primary runs (2 indices x 3 TF x 3 configs).

## Outputs per run

(A) Index points as the script trades (overnight allowed): trades, win%, points/trade gross and net of the script's
own 0.05% commission a side + 1 tick slippage on market/stop fills, worst drawdown in points. Random twins (A): 10 per
trade, same entry bar, random side, bracket at the same stop / target distances from the entry price (no reversals).

(B) As an option BUYER (Rs 1 lakh, 1 fixed lot: NIFTY 65, BANKNIFTY 30 as R4): every script entry that fills at a bar
close with signal minute 09:20-14:45 (later entries and the overnight carry cannot be done with intraday option buys)
-> long = 1-ITM CE, short = 1-ITM PE, nearest expiry, bought at the option open of the next minute (R1 engine).
- NAT exit: the index 1-minute high/low first touching the script's stop or TP1 (the levels at entry), or the bar close
  at which the script reverses (if earlier), leaves at the option open of the next minute; else 15:10.
- OPT exit (sensitivity): premium -25% / +50% on the option's 1-minute wicks (stop first on a tie), else 15:10.
- Costs: app charges + h24 half-spread 0.16% a side (max(half-spread, 5 bps)); stop exits +5 bps; the app's own +-5 bps
  fill is NOT stacked (X3 #2). One option position at a time per variant, max 3 a day (R4 non-overlap).
- Random twins: R4 (10 per trade, same day, random side, random minute within +-30 min, same holding time for index
  exits).
- 162 x 2 = 324 option variants. Also shown: index points of the same intraday window (entry minute -> exit minute).

## Statistics and gates

- DESIGN = trades entered before 1 Oct 2025 (NIFTY from Aug 2020, BANKNIFTY from Aug 2021). HOLDOUT = 1 Oct 2025 -
  6 Oct 2026, opened ONCE by `holdout.py` after `design.py` writes `frozen.json` (marker file blocks a second run).
  The bar simulation runs continuously through all data (as a chart would), but holdout trades are not computed or
  looked at until the holdout run.
- Per option variant: trades, win%, Rs/trade, Rs/day (all design sessions, 0 on no-trade days), max drawdown of daily
  equity, day-clustered t (p = 1 if < 30 trades), p_rand vs twins (Welch, one-sided). BH q over all 324 design
  option variants (t vs 0) and separately on p_rand. White Reality Check per index over its 162 design daily series.
- Holdout confirmatory set (frozen now): the 18 PRIMARY runs x NAT exit, plus per (index, TF, config, exit) the design
  variant with the highest daily t (frozen picks), 36 picks. All 324 are also run in the holdout but labelled
  descriptive.
- PASS = design Rs/day > 0 AND BH q < 0.10 AND p_rand < 0.05 AND RC p < 0.10 AND holdout Rs/day > 0 AND holdout
  p_rand < 0.10.
- The Rs 1 lakh check: worst drawdown must stay < Rs 1 lakh, and the option premium of 1 lot must be affordable.

## Known deviations / unavoidable choices

- The minute data replaces TradingView's own feed (small OHLC differences possible).
- Pine's na-handling of the first bars (ATR seed) cannot matter after the first 14 bars of 2020/2021.
- Twins for (A) use brackets, not the script's reversal logic.
