# R89 pre-registration: Boss's previous-day high / low break on the 1-minute chart (written 20 Oct 2026)

Written and committed BEFORE any statistic of this study was computed. Up to this commit only data coverage was looked
at (which files exist, their first and last minute). Any later change is an amendment, dated, with its reason, in
`NOTES.md`, made before the numbers it affects.

## 0. Boss's rule (20 Oct, verbatim summary)

- BUY: mark the previous day's high and low. On the 1-minute chart wait for price to break a previous-day level. Wait
  for a green candle after the break. Enter when the next candle breaks that green candle's high. Stop: the green
  candle's low. Target: the previous swing high.
- SELL (= buy a put): break of a previous-day level, a red candle after it, enter when the next candle breaks the red
  candle's low. Stop: the red candle's high. Target: the previous swing low.
- Boss's own risks: whipsaws / false breaks on 1m, slippage and charges, no higher-timeframe context.

**Signal source of every rule: the UNDERLYING** (index spot 1-minute OHLC for NIFTY, BANKNIFTY, SENSEX, FINNIFTY,
MIDCPNIFTY; for MCX the near future itself). The option is only what is bought (1-ITM, below). No rule reads an option's
own price chart. Option-chain data are not used at all.

## 1. Prior work (read first, not repeated)

- R20c (2-close PDH/PDL and other level breaks, 5m / 15m): a coin toss, random levels as good; the 2nd close costs as
  much as it saves. R20b: levels no better than random lines as an ORB range. R22 / R23: ORB range breaks lose.
- R86: stop runs beyond yesterday's high / low are followed by more of the same, not a reversal (fading lost on 6 of 6).
- R87 / R87B: no candle shape predicts direction; a stop under a signal candle was too tight for an option buyer
  (R87B-1: 117 of 162 trades closed by the underlying stop).
- R85: MCX "first break of the previous day's high on a green 1h candle continued 56% vs 49%", not significant: a lead.
- Crypto R8 (previous-day high/low break) was positive in design and failed out of sample.
- What is new here: Boss's exact entry (a same-colour candle after the break, then a break of that candle's extreme),
  the candle-extreme stop and the swing target, on 1m / 3m / 5m, with REAL held-contract option prints on five indices
  and four MCX books, against random-entry twins, and the "reclaim" (failed-break) reading.

## 2. Data, windows, split

| market | signal series | option prints | design | holdout (locked, read ONCE) |
|---|---|---|---|---|
| NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY | lake `index_1m` (spot 1m OHLC) | lake `options_rolling_1m` WEEK files (R51 loader: held contracts by strike; Amendment A clean + 18 Oct stale-print step) | 20 Nov 2024 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |
| GOLDM, SILVERM, CRUDEOIL (mini), NATURALGAS (mini) | near future = per-minute median `spot` of the option rows (R53 Market; close-only) | lake MCX MONTH files GOLDM, SILVERM, CRUDEOILM, NATGASMINI (ATM-3..+3, R53 loader with R42 spike flags) | 5 Aug 2025 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |

- Index option rules start 20 Nov 2024 (Boss, 15 Oct: nothing before NSE's weekly-expiry change). Index days from 1 Sep
  2024 only warm up indicators. Underlying-only (futures) results are ALSO shown, descriptively and never gated, on the
  older index history 2 Aug 2021 - 19 Nov 2024 (lake `index_1m`).
- CRUDEOIL and NATURALGAS: the signal is the mini future (CRUDEOILM / NATGASMINI, the same commodity and month) and the
  mini option is bought, so the signal and the option share one minute grid and one contract month. Labelled
  "CRUDEOIL (mini)" / "NATURALGAS (mini)".
- MCX candles are close-only (the lake has no true MCX futures OHLC before May 2026): a 1m candle's open is the previous
  minute's close, high / low = max / min of open and close. Wicks are therefore understated (R85: about 78% of the true
  5m range). Stops and triggers are read on minute closes. Stated as a limit.
- The holdout was already read by other studies (R51, R80, R85 ...) for OTHER ideas; it is unseen for this idea.
  The 14-19 Oct sessions are new to every study.

## 3. The rule, mechanically (one candle timeframe TF = 1, 3 or 5 minutes, anchored at 09:15 NSE / 09:00 MCX)

Levels of day d (known before d opens): **PDH / PDL** = high / low of the previous session (NSE 09:15-15:29 1m bars;
MCX the whole previous session of the close-only series). MCX: the first session after a contract roll (segment
change) is skipped (its PDH / PDL belong to another contract). Variant SR adds two more levels (section 5).

"prev close" of the day's first candle = the previous session's last close.

**Reading C, "break and go" (continuation; Boss's rule read as written):**
- Long setup on level L: armed by an UP-BREAK of L. Close variant: a candle closes > L and the candle before closed <= L.
  Wick variant: a candle's high > L and the candle before closed <= L.
- While armed: a candle closing <= L cancels the setup (the break failed; a new up-break may re-arm). The first GREEN
  candle (close > open) with close > L, strictly AFTER the arming candle, is the signal candle.
- Trigger: the very next candle trades above the signal candle's high (index: a 1m high > it, at 1-minute resolution
  inside the TF candle; MCX: a minute close > it). If the next candle does not, the signal candle is dropped and the
  next green candle closing > L (setup still armed, within 15 TF candles of the break) becomes the signal candle.
- Entry consumes the setup; it re-arms only after a close <= L and a new up-break.
- Puts mirror it on DOWN-breaks of L (close < L after a close >= L; wick: low < L), a RED candle with close < L, trigger
  below its low, cancel on a close >= L.
- Levels: every active level (PDH and PDL; SR adds R and S). A long can follow an up-break of PDL (after a gap below it).

**Reading R, "break and reclaim" (the literal "a long after a PDL reclaim"; a failed break; labelled):**
- Long setup on level L: armed by a DOWN-break of L (close < L after a close >= L; wick: low < L after a close >= L).
- Within 15 TF candles of the arming candle, the first GREEN candle with close > L (back above, the reclaim) is the
  signal candle; trigger as above (the next candle breaks its high); if not triggered, the next green candle closing > L
  within the 15-candle window becomes the signal candle. Entry consumes the setup.
- Put: armed by an UP-break, a RED candle closing back < L, trigger below its low.

**Both readings:**
- Stop (on the underlying): the signal candle's low (long) / high (put).
- Target (on the underlying): the nearest confirmed swing high (long) / swing low (put) beyond the entry level whose
  distance from entry is at least MINRR x risk (risk = entry - stop). Swings are fractals of the TF candles: N = 5
  (a high above the 2 candles each side) or N = 3 (1 each side); a swing is usable only once its right-side candles have
  CLOSED before the trigger candle starts. Candidates: today's and the previous 5 sessions' TF candles. No such swing ->
  no trade (counted as "no target").
- Risk <= 0 (or < 1 tick) -> no trade.
- Entry window: trigger candle starts 09:16 - 14:45 (NSE); MCX: from the session's first minute to 22:30.
- At most 3 entries a day per instrument per variant, one open position at a time (a trigger while a position is open
  is skipped).
- Session-end fallback: NSE square-off 15:10 (15:05 on the index's expiry day: R51 expiry test); MCX 10 minutes before
  the session close (R53 `day_cut`). MCX option trades are never taken on an MCX option expiry day (devolvement; R53/R85).

## 4. Execution and costs

**Option (primary; the arms' way):** BUY 1 lot of the **1-ITM** call (long) / put (put), strike = 1 step in the money
from the ATM strike at the trigger minute (index: the rolling offset-0 strike, R51 `strike_for(money=1)`; MCX: offset
-1 call / +1 put, R53), nearest expiry, that ONE contract held.
- Index (R51 engine, the app's slippage model R17 "liq"): fill at the contract's open of the minute after the trigger
  minute (first print within 2 minutes, else no trade): +5 bps + a half-spread of 1 / 2 / 4 ticks by the contract's
  5-minute volume. Underlying stop / target touched at minute m (at the trigger minute itself only on its close; later on
  the minute's low / high) -> the option is sold at the open of minute m+1 (market sell: -5 bps - half-spread). Square-off
  at the open of 15:10 / 15:05. Charges: `Costs('dated')` (STT, exchange, SEBI, stamp, GST, Rs 20 brokerage an order)
  in force on the trade date.
- MCX (R53 `sim_option`): fill at the open of the first printed minute in (trigger, trigger + 2]; exits on the future's
  minute close through the stop / target, sold at the option's close that minute (else the next print within 10
  minutes); R53 McxCosts option charges + R85's strict half-spread (CRUDEOIL / GOLDM / SILVERM 0.30% after 17:00, 0.60%
  before; NATURALGAS 0.45% / 0.90%) on both legs.
- Lots: the app's lots in force on the date (R51 table; MCX minis GOLDM 10, SILVERM 5, CRUDEOILM 10, NATGASMINI 250).
- **Fixed-risk view (reported, not gated):** lots = clamp(floor(Rs 2,000 / (risk points x 0.6 x lot size)), 1, 3) per
  trade (the app's Rs 2,000 risk-per-trade and a 3-lot guard; 0.6 = a 1-ITM delta); the option trade re-priced with that
  quantity (charges and spread on the real quantity).

**Futures-only (reported, not gated):** the same trades on the underlying itself (index spot as the futures proxy, basis
ignored; MCX the mini near future), 1 lot: entry at the signal candle's extreme (a buy-stop; at the minute's open if it
opened beyond), + 1 bps; stop at the level - 2 bps (at the open if it gapped through); target at the level; square-off
at the open of the square-off minute - 1 bps. Index futures charges: Rs 20 an order (or 0.03%), STT on the sell 0.0125%
(to 30 Sep 2024) / 0.02% (to 31 Mar 2026) / 0.05% (from 1 Apr 2026), exchange 0.00173% (the NSE rate, used for SENSEX
futures too), SEBI Rs 10 / crore, stamp 0.002% of the buy, GST 18%. MCX: R53
`mcx_fut_charges` + `fut_half_spread`.

## 5. Variants (declared now; nothing else will be run)

B = base: PDH / PDL; close break; TF 1m; no higher-timeframe filter; fractal N = 5; MINRR 1.0; window as section 3
(09:16-14:45, expiry day traded with the 15:05 square-off); all signals, max 3 a day.

Each variant changes ONE thing from B:

| id | change |
|---|---|
| B | base |
| SR | levels add R = nearest confirmed 15m fractal (N = 5) swing high of the previous 5 sessions above the day's first open, and S = nearest such swing low below it (dropped if within 1 tick of PDH / PDL) |
| WK | wick break (section 3) instead of close break |
| T3 | TF = 3m candles (break, signal candle, trigger and swings on 3m) |
| T5 | TF = 5m candles |
| HTF | higher-timeframe filter ON: a long only if the previous session's close > its 20-session average close AND the last completed 15m candle (09:15 / 09:00 anchored, continuous across sessions) closed above the EMA20 of 15m closes; a put only if both are below |
| N3 | fractal N = 3 |
| RR2 | MINRR 2.0 |
| WIN | no trigger before 09:30 (MCX: before 09:15) and no trade on the index's expiry day (MCX: already never) |
| F1 | only the first trigger of the day (per instrument) |

10 variants x 2 readings (C, R) = **20 variants**, each run on 9 instruments = **180 cells**. Pooled rows (all indices,
all MCX) are shown but not tested.

## 6. Measures (every number with its n)

Option net (1 lot) and fixed-risk net, futures-only net; trades, trades a day, win rate, average win / loss, expectancy
per trade after costs, max drawdown (daily), worst day, worst calendar month, net without the 5 best trades; false-break
share = trades closed by the underlying stop within 3 TF candles of entry; exit reasons; the "no target" skip count.

**Random-entry twins (500 per cell, seed 89):** same days, same number of entries a day and side, entry minute uniform in
the variant's entry window that day, same strike rule, and the SAME point distances to the underlying stop and target
from the twin's own entry price (so the twin keeps the trade's risk and reward and differs only in WHEN it enters).
p = (1 + #twins >= rule) / 501, one-sided.

**Walk-forward:** the rules have no fitted parameters, so each cell is shown by fold (index folds: Apr-Jun 2025, Jul-Sep
2025, Oct-Dec 2025, Jan-Mar 2026; MCX: Nov-Dec 2025, Jan-Feb 2026, Mar 2026). Selection walk-forward: per instrument, at
each fold pick the variant with the best design net before the fold (>= 20 trades; ties -> B) and record its next-fold
net, against B's.

**Benjamini-Hochberg:** over all 180 design cells' twin p-values (q). The experiments family is
`patterns-indicators`; its earlier tries are listed in the write-up.

## 7. Gates (never relaxed)

A design cell PASSES only if all hold: (G1) >= 40 design trades; (G2) option net > 0; (G3) twin p < 0.05; (G4) BH q <
0.10 over the 180; (G5) net > 0 in >= 3 of 4 index folds (MCX: >= 2 of 3); (G6) net > 0 without the 5 best trades;
(G7) worst calendar month >= -Rs 13,000 (Boss's account stop).

**Holdout:** read ONCE for all 180 cells by one command (`study.py final`, guarded by `results/FINAL_READ`), after the
design results and the experiment entries are committed. A design passer becomes a PAPER candidate only if in the holdout:
net > 0, >= 20 trades, twin p < 0.10 with BH q < 0.10 over the design passers' holdout p-values, worst month >= -Rs
13,000. Cells that fail design are reported in the holdout descriptively only.

Anything that passes gets an APP_SPEC for a PAPER arm only (logged in the Autopilot timeline, undoable in one tap, behind
a switch that is off by default). Nothing goes live. Anything that fails stays a note.

## 8. Comparison with the running arms (descriptive, same days)

On the index days of both windows: the R72 Breadth Donchian (C1) and Liquidity BANKNIFTY 15+5 trades from their own
research code on the same stores, and the ORB signal (R20 V0: 09:15-09:59 range, first 5-minute close beyond it from
10:00, last decision bar 13:55). Shown: share of R89 trade-days (per index) that also carry an arm trade / signal, same
side or opposite; the daily net correlation with Breadth Donchian and Liquidity where both traded; R89's net on days
with and without an arm signal.

## 9. Look-ahead guard (pytest)

For random synthetic days and cut minutes: every signal (setup, signal candle, trigger, stop, target, HTF state, levels)
whose trigger minute is at or before the cut is identical when every minute after the cut is replaced by noise.
