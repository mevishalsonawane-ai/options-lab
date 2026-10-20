# R90 pre-registration: "Almazov Trade: MTF Structure & BOS Entries" (written 20 Oct 2026)

Written and committed BEFORE any statistic of this study was computed. Up to this commit only data coverage was looked
at (which lake files exist and their first / last session). Any later change is an amendment, dated, with its reason,
in `NOTES.md`, made before the numbers it affects.

## 0. The indicator and the question

Boss (20 Oct) sent the Pine v6 source of the TradingView indicator "Almazov Trade: MTF Structure & BOS Entries"
(© Algorithms_ATS, MPL 2.0) and asked whether it works. Its rules, written out from that source, are in `SOURCE.md`.
The author warns the on-chart model has no fees and no slippage, so the honest test is with real option prices and
costs.

**Signal source of every rule: the UNDERLYING** (index spot 1-minute OHLC for NIFTY, BANKNIFTY, SENSEX, FINNIFTY,
MIDCPNIFTY; for MCX the near future itself, close-only). The swings, structure, trend, BOS levels, entry, stop and target
are all read on the underlying. The option is only what is bought (1-ITM, section 4). No rule reads an option's own
chart and no option-chain data are used.

## 1. Prior work (read first, not repeated)

- R89 / R89B (previous-day level break + a 1m candle, the closest cousin: a stop entry above a candle, a swing target):
  0 of 180 and 0 of 45 passed. The underlying moved +0.4 to +3.5 index points a trade in the rule's favour while the
  1-ITM option's round trip costs 1.1 (NIFTY) to 3.7 (SENSEX) points. About a third of trades were stopped within 3
  candles. Method lesson (R89B A1): random twins that keep the trade's side and day see the day's direction; with long
  holds that hindsight makes twins look good. R90 therefore uses the hindsight-free AFTER-TRIGGER twin as its gated twin.
- R52 R4-8 (Gann 2-bar swing-chart break, 15m, 30-minute exit): 242 trades +Rs 38,895, twin p 0.128 -> fail.
- R85 / R87 / R88: candle shapes and parameters predict the next candle's size, not its direction.
- R86 / R86b-d: stop runs beyond the day's high / low are followed by more of the same (continuation), not a reversal.
- ORB family (paused on paper, lost in both years), Liquidity BANKNIFTY 15+5 and R72 Breadth Donchian (the running
  index arms).
- What is new here: market structure (swing highs / lows, CHoCH, BOS) on two timeframes at once, entry only when the
  chart trend equals the higher-timeframe trend, a stop order at the chart swing, the stop at the opposite chart swing
  and the target at the higher-timeframe swing.

## 2. Data, windows, split (the same stores as R89)

| market | signal series | option prints | design | holdout (locked, read ONCE) |
|---|---|---|---|---|
| NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY | R51 day stores (lake `index_1m` spot 1m OHLC) | lake `options_rolling_1m` WEEK (R51 loader: held contracts, Amendment A clean + stale-print step) | 20 Nov 2024 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |
| GOLDM, SILVERM, CRUDEOIL (mini), NATURALGAS (mini) | near future = per-minute median `spot` of the option rows (R53 Market; close-only) | lake MCX MONTH books GOLDM, SILVERM, CRUDEOILM, NATGASMINI (R53 loader, R42 spike flags) | 5 Aug 2025 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |

- Index structure warms up from 1 Sep 2024 (the store's first day); no trade before 20 Nov 2024 (Boss, 15 Oct).
- CRUDEOIL and NATURALGAS: the mini future is the signal and the mini option is bought (labelled "(mini)"), as in R89.
- MCX bars are close-only (a minute's open is the previous minute's close; high / low the max / min of the two). Wicks
  are understated (R85: about 78% of the true 5m range). Stated as a limit.
- The holdout window was read by other studies for OTHER ideas; it is unseen for this idea.

## 3. The indicator, rebuilt (SOURCE.md; the implementation choices the source leaves open are fixed here)

**Bars.** Chart (LTF) bars of `tf` minutes and HTF bars of `mult x tf` minutes are built from 1-minute bars and aligned
to the epoch, `floor(unix_minutes / bar_minutes)` (the source's `time / (60000 * tf * chart_minutes)`). NSE 09:15 and MCX
09:00 are multiples of 3, 5 and 15 minutes, so chart bars start at the session open; HTF buckets that straddle the open
are partial (as on the chart). A bucket never spans two sessions. The "day" HTF (variant HD) is one bar per session.

**Swings (per timeframe).** A swing high at bar p: high[p] >= the high of each of the D bars on each side (D = 2 by
default; no bar on either side higher). A swing low mirrors it. The swing is CONFIRMED at the close of bar p + D and used
only from bar p + D + 1 (no look-ahead).

**Structure (per timeframe), processed at each bar's close in this order: events on the bar, then the swings confirmed
by that bar.**
- Start: once the first swing high and the first swing low are confirmed: uptrend if the low came first, else downtrend.
  Uptrend: active high H = that swing high, protected low L = that swing low. Downtrend: active low L, protected high H.
- Uptrend: a newly confirmed swing high above H (H broken or not) starts a new leg: H := the new swing high (unbroken),
  and the protected low L := the lowest confirmed swing low whose bar lies between the old H's bar and the new one (kept
  as it was if there is none). A lower swing high does not replace an unbroken H.
- BOS up: the bar's high > H while H is unbroken -> H is broken (it can be broken once).
- CHoCH down: the bar's low < L (the protected low) -> trend := down; the protected high H := the highest high since the
  protected low's bar (the top of the failed up-leg); the active low is empty until the next confirmed swing low, which
  becomes the active low L.
- Downtrend: the exact mirror (new swing low below L = new leg, the highest swing high between them = protected high;
  BOS down: low < L; CHoCH up: high > H).
- If both H and L are crossed in the same bar, the path open -> nearer extreme -> far extreme -> close orders them (the
  extreme nearer the open first; a tie goes to the high first); both events are applied in that order.
- MCX: the structure is reset on the first session of a new contract (the near future changes contract; R89 skip rule).

**Trade model ("BOS to BOS").** At the close of chart bar k (no position open):
- the chart (LTF) trend equals the HTF trend, read from the HTF bars that had CLOSED before bar k (an HTF bar is known
  closed when the next bucket's first chart bar arrives, as the Pine script sees it);
- long: entry E = the chart's active swing high, unbroken, and close[k] < E; stop = the chart's protected low - 1 tick;
  target T = the HTF active swing high, unbroken, not already used as a hit target, and T > E; RR = (T - E) / (E - stop)
  >= min RR (1.0 default). Short: the mirror (E = active low, close > E, stop = protected high + 1 tick, T = HTF low).
- A buy-stop (sell-stop) at E for bar k + 1 only; the setup is re-evaluated at every bar close.
- After a fill the chart level counts as broken; after a target hit the HTF level counts as broken. One trade at a time.

**Two ways to run it (both reported):**
1. **"As written" (the indicator's own model; descriptive, never gated):** fills and exits on the chart bars' OHLC with
   the path above (a gap through E fills at the open; then the stop and target along the path from the fill point; stop
   first when both lie in the same segment at the same distance; a later bar opening beyond the stop / target exits at
   that open). Continuous across sessions, positions may be held overnight, no fees, no slippage. Result in underlying
   points, and in rupees (points x lot) for scale.
2. **Session rules (the tested version):** the same setups, the order filled at 1-minute resolution inside bar k + 1
   (the first minute whose high >= E, at E, or at that minute's open if it opened beyond E; shorts mirrored). Bar k and
   bar k + 1 must be in the same session. Fills only 09:16 - 14:45 (NSE) / from the session's first minute to 22:30
   (MCX). Exit on the underlying at 1-minute resolution (R89 `underlying_exit`: on the fill minute only its close counts,
   later each minute's low / high, stop first), else flat at the open of 15:15 (15:05 on the index's expiry day, Boss);
   MCX at the R53 session cut (10 minutes before the close). No MCX option trade on an MCX option expiry day or on the
   first session of a contract. Reported in underlying points (no costs) and as the option trade (section 4).

## 4. Execution and costs (option, primary)

BUY 1 lot of the **1-ITM** call (long) / put (short), strike one step in the money from the ATM strike at the fill minute,
nearest expiry, that ONE contract held (R51 / R53 strike rules, as R89).
- Index (R51 engine, the app's slippage model R17 "liq", dated charges): bought at the contract's open of the minute after
  the fill minute (first print within 2 minutes, else no trade): +5 bps + a half-spread of 1 / 2 / 4 ticks by 5-minute
  volume. The underlying stop / target touched at minute m -> sold at the open of m + 1 (-5 bps - half-spread). Square-off
  at the open of 15:15 (15:05 on expiry). Charges `Costs('dated')`.
- MCX (R53 `sim_option`, R89's numba port): fill at the open of the first printed minute in (fill minute, +2]; exit on
  the future's minute close through the stop / target, sold at the option's close that minute (else the next print within
  10 minutes); R53 McxCosts + R85's strict half-spread on both legs.
- Lots in force on the date (R51 table; GOLDM 10, SILVERM 5, CRUDEOILM 10, NATGASMINI 250).
- Stops and targets are read on the underlying only (the option premium is not used for exits in this study).

## 5. Variants (declared now; nothing else will be run)

| id | chart (LTF) | HTF | swing D | min RR | session window | direction |
|---|---|---|---|---|---|---|
| A1 | 1m | 4m (4x, default) | 2 | 1.0 | base | both |
| A3 | 3m | 12m (4x) | 2 | 1.0 | base | both |
| A5 | 5m | 20m (4x) | 2 | 1.0 | base | both |
| A15 | 15m | 60m (4x) | 2 | 1.0 | base | both |
| H60 | 5m | 60m (12x, custom) | 2 | 1.0 | base | both |
| H75 | 15m | 75m (5x, custom) | 2 | 1.0 | base | both |
| HD | 15m | the session (one bar a day) | 2 | 1.0 | base | both |
| S3 | 5m | 20m | 3 | 1.0 | base | both |
| RR2 | 5m | 20m | 2 | 2.0 | base | both |
| W30 | 5m | 20m | 2 | 1.0 | no fill before 09:30 (MCX 09:15) | both |
| DT | 5m | 20m | 2 | 1.0 | base | only with the daily trend |

- "base" session window: section 3 (fills 09:16 - 14:45 NSE; flat 15:15 / 15:05 expiry; MCX to 22:30, session cut).
- DT: the daily trend = the same structure engine (swing 2) on daily bars of the same series, state at the previous
  session's close; a long only in a daily uptrend, a short only in a daily downtrend (no trade while the daily trend is
  unknown).
- 11 variants x 9 instruments = **99 cells**, one BH family. Pooled rows (all indices, all MCX) are shown, not tested.

## 6. Measures (every number with its n)

Per cell: option net (1 lot) after costs, trades, trades a day, win rate, average win / loss, expectancy, max drawdown
(daily), worst day, worst calendar month, net without the 5 best trades; share of trades stopped (underlying stop)
within 3 chart bars of the fill; exit reasons; underlying points a trade (session rules, no costs) and the option's
round-trip cost in points; the "as written" result (trades, points, win rate, rupees at 1 lot, no costs).

**Random-entry twins (gated, 500 per cell, seed 90): the AFTER-TRIGGER twin** (R89B A1): for each trade, the same day,
side, strike rule, exit rule and the same point distances to the underlying stop and target from the twin's own entry
price; the entry minute uniform between the trade's own fill minute and the window's last entry minute that day (the
twin enters only once the rule already knew the side). p = (1 + #twins >= rule) / 501, one-sided. The whole-window twin
of R89 (entry anywhere in the day's window) is reported beside it for the option, descriptively.

**Walk-forward:** no fitted parameters, so each cell is shown by fold (index: Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025,
Jan-Mar 2026; MCX: Nov-Dec 2025, Jan-Feb 2026, Mar 2026). Selection walk-forward: per instrument, at each fold pick the
variant with the best design net before the fold (>= 20 trades; ties -> A5), record its next-fold net, against A5's.

**Benjamini-Hochberg** over the 99 design cells' after-trigger twin p-values (q). Family `patterns-indicators`.

## 7. Gates (never relaxed; the same as R89)

A design cell PASSES only if all hold: (G1) >= 40 design trades; (G2) option net > 0; (G3) after-trigger twin p < 0.05;
(G4) BH q < 0.10 over the 99; (G5) net > 0 in >= 3 of 4 index folds (MCX >= 2 of 3); (G6) net > 0 without the 5 best
trades; (G7) worst calendar month >= -Rs 13,000 (Boss's account stop).

**Holdout:** read ONCE for all 99 cells by one command (`study.py final`, guarded by `results/FINAL_READ`), after the
design results and the experiment entries are committed. A design passer becomes a PAPER candidate only if in the
holdout: net > 0, >= 20 trades, after-trigger twin p < 0.10 with BH q < 0.10 over the design passers' holdout p-values,
worst month >= -Rs 13,000. Cells that fail design are reported in the holdout descriptively only.

Anything that passes gets an APP_SPEC for a PAPER arm only (the app's Pine engine or a native arm, logged in the
Autopilot timeline, undoable in one tap, behind a switch that is off by default). Nothing goes live. Anything that fails
stays a note, with a word on whether it is still useful as a chart overlay (structure and levels only).

## 8. Comparison with the running arms (descriptive, same days)

As R89 section 8, on the index days of both windows: R72 Breadth Donchian (C1), Liquidity BANKNIFTY 15+5 and the ORB
signal (R20 V0), plus R89's base rule (C-B) re-run on the same stores. Shown for A5 and the best design variant: the share
of R90 trades on a day the arm also trades (same side / opposite), the daily P&L correlation, R90's net on arm days and
on other days.

## 9. Tests (pytest, before any result)

Small hand-made cases: swing confirmation timing, start trend, new leg and protected level, BOS, CHoCH and its rebuild,
the path order in an outside bar, epoch-aligned HTF buckets, setup / stop / target / RR, gap fills, stop-first in one
segment, one trade at a time, the target marking the HTF level used. **Look-ahead guard:** for random synthetic minute
series and cut minutes, the structure state of every bar closed by the cut and every setup and fill at or before the cut
are identical when every minute after the cut is replaced by noise.
