# R95 pre-registration: the "Mo3ty CCI Signals" indicator (written 21 Oct 2026)

Written and committed BEFORE any statistic of this study was computed. Up to this commit no lake file was opened for
this study (the lake could not even be copied in this session). Any later change is an amendment, dated, with its
reason, in `NOTES.md`, made before the numbers it affects.

## 0. The indicator and the question

Boss, 21 Oct: "Deep understand the indicator below, find the connection between the indicator data and actual data, and
find out the strategy's win rate." The indicator is the Pine v6 "Mo3ty CCI Signals" (source in `SOURCE.md`):

- CCI(25) of hlc3; lines at +200 / 0 / -200.
- SELL when the CCI was at or above +200 on the previous bar and closes below +200 on this bar; BUY when it was at or
  below -200 and closes above -200. Only on a confirmed (closed) bar.
- Optional "Volatility Pulse" filter (off by default): ATR(14) above its 20-bar SMA AND the candle's body >= 0.25 x
  ATR(14).
- Divergence labels (drawn only): confirmed price pivots (left 5, right 5); BULL when a new pivot low is below the
  previous pivot low while the CCI at it is above the CCI at the previous one, and either of the two CCI values is
  <= -200; BEAR mirrored (>= +200). A label is drawn AT the pivot bar but known only 5 bars later.

Questions:
1. What does the CCI measure in price terms, and what does price do after the signal (descriptive, section 6)?
2. After real option prices and costs, does buying the call on BUY (the put on SELL) beat random entry times, on any of
   9 instruments, 4 timeframes and 8 declared variants (288 cells)?
3. How much does entering at the divergence label's bar (what the chart shows) inflate the result versus entering when
   the label is actually known (5 bars later)?

**Signal source of every rule: the UNDERLYING.** Index spot 1-minute OHLC for NIFTY, BANKNIFTY, SENSEX, FINNIFTY,
MIDCPNIFTY; for MCX the near future itself (close-only). The CCI, ATR, pulse, pivots, divergences, the entry bar, the
stop, the target, the CCI-zero exit and the time exit are all read on the underlying. A SELL signal = BUY the put; a
BUY signal = BUY the call (1-ITM, section 4). No rule reads an option's own chart; no option-chain data are used.

## 1. Prior work (read first, not repeated)

- **R85 / R87 / R88:** candle shapes and 22 parameters predict the next candle's SIZE (225 of 305 hold) but not its
  DIRECTION (0 of 8 rules, 0 of 225 shapes). A CCI cross is a function of the last 25 candles, so the prior is that it
  will not predict direction either.
- **R90 (Almazov MTF structure & BOS indicator, 0 of 99) and R91 (ICT liquidity sweep, 0 of 63):** indicator
  strategies failed after costs; the underlying moved too little per trade to pay the option round trip and time decay.
- **R89 / R89B:** method lesson used here: the gated twin is the hindsight-free AFTER-TRIGGER twin.
- **R86 / R87:** fades of stretched moves (stop runs) lost; stretches tend to continue. CCI +-200 is a stretch fade.
- What is new here: an oscillator mean-reversion trigger (the stretch starting to relax), on 4 timeframes, with
  three exit styles, and an explicit measurement of the divergence labels' repaint in time.

## 2. Data, windows, split (the same stores as R89 / R90 / R91)

| market | signal series | option prints | design | holdout (locked, read ONCE) |
|---|---|---|---|---|
| NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY | R51 day stores (lake `index_1m` spot 1m OHLC) | lake `options_rolling_1m` WEEK (R51 loader, Amendment A clean + stale-print step), the held contracts ATM +-10 | 20 Nov 2024 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |
| GOLDM, SILVERM, CRUDEOIL (mini), NATURALGAS (mini) | near future = per-minute median `spot` of the option rows (R53 Market; close-only) | lake MCX MONTH books GOLDM, SILVERM, CRUDEOILM, NATGASMINI (R53 loader, R42 spike flags) | 5 Aug 2025 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |

- Copied from Drive `gdrive:IraAlgo-data-lake`; nothing raw is committed.
- Indicators warm up from the store's first day (1 Sep 2024 for indices); no trade before 20 Nov 2024.
- CRUDEOIL and NATURALGAS: the mini future is the signal and the mini option is bought, labelled "(mini)" (R89-R91).
- MCX bars are close-only (a minute's open is the previous minute's close); MCX wicks, ATR and bodies are therefore
  understated. Stated as a limit.
- The holdout window was read by other studies for OTHER ideas; it is unseen for this idea.

## 3. The indicator, exactly as Pine computes it (code: `cci.py`)

**Bars.** 3m, 5m, 15m and 60m bars built from 1-minute bars, anchored at the session open (NSE 09:15, MCX 09:00),
never across sessions (NSE 60m: 09:15, 10:15, ..., 14:15 and the partial 15:15 bar, as TradingView draws it). A bar is
known at its close (its last minute's close). Every indicator runs continuously across sessions, as on TradingView.

- `tp = (high + low + close) / 3` (hlc3).
- `CCI_n = (tp - SMA_n(tp)) / (0.015 x MD_n)`, with `MD_n` = the mean of `|tp_i - SMA_n(tp)|` over the same n bars
  (the current SMA, as Pine's `ta.dev`). Undefined (no signal) until n bars exist or when `MD_n = 0`.
- `ATR14 = RMA_14(TR)` (Pine `ta.atr`: TR = max(h - l, |h - c[1]|, |l - c[1]|), the first TR = h - l; the RMA is seeded
  with the SMA of the first 14 TRs). `ATRavg = SMA_20(ATR14)`.
- `pulseOK = ATR14 > ATRavg AND |close - open| >= 0.25 x ATR14` (with "Require ATR Expansion" on, as the default).
- `SELL[i] = CCI[i-1] >= U AND CCI[i] < U`; `BUY[i] = CCI[i-1] <= L AND CCI[i] > L` (U = +200, L = -200 by default).
- Pivots (the app's Pine engine's `ta.pivotlow / ta.pivothigh`): bar p is a pivot low if `low[p]` is strictly below
  each of the 5 bars after it and at or below each of the 5 bars before it; pivot high mirrored. It is CONFIRMED at the
  close of bar p + 5 and usable only from then on.
- Bullish divergence, known at bar p + 5: p is a pivot low, the previous confirmed pivot low p' exists,
  `low[p] < low[p']`, `CCI[p] > CCI[p']`, and `CCI[p] <= -200 OR CCI[p'] <= -200`. Bearish mirrored (pivot highs,
  `high[p] > high[p']`, `CCI[p] < CCI[p']`, either `>= +200`).

## 4. The trade rule (mechanical; every choice fixed here)

**Signal and entry.** A BUY / SELL signal on bar i is known at its close. The underlying entry is the next minute's open
(the first minute of bar i + 1; the "close of the signal bar" and the "next bar's open" are one minute apart here, so
the study uses one convention: the next minute's open). The option is bought at the next minute's open too (R51 engine,
first print at or after that minute). If bar i's close is the session's last minute, there is no trade.

**Session window.** NSE: the entry minute must be 09:30 - 14:45 (signals of 09:15 - 09:30 are skipped; Boss's skip of
the open); flat at the open of 15:15 (15:05 on the index's expiry day). MCX: entries 09:15 - 22:30, flat at the R53
session cut; no MCX trade on an option expiry day or on the first session of a contract (R89 - R91 rules).
One position at a time per instrument and cell; a signal while a trade is open is skipped; at most 4 trades a day.

**Stop "beyond the signal's swing".** The stretch episode = the consecutive bars ending at bar i - 1 with CCI >= U
(SELL) or <= L (BUY). SELL stop = the highest high from the episode's first bar to bar i, + 1 tick. BUY stop = the
lowest low - 1 tick. Risk R = |entry - stop|. If the entry is already at or beyond the stop, no trade. Underlying
stops are checked every minute from the entry minute (its high / low count), filled at the stop or at the minute's open
if it gapped past.

**Exits (three declared styles; whichever comes first, the stop always protects):**
- **Z (base): the CCI crossing 0.** Exit at the close of the first bar k > i with CCI[k] >= 0 (BUY) / <= 0 (SELL).
- **R: fixed reward:risk 2.** Target = entry +- 2R, checked every minute (stop first within a minute), filled at the
  target or at a better open.
- **T: time exit.** Exit at the close of bar i + 10.
- Every style: flat at the session's flat time. A bar-close exit sells the option at the next minute's open; a stop /
  target exit at the next minute's open after the minute it was hit; a flat exit at the flat minute.
  The underlying's exit price: the bar's close (bar exits), the stop / target level or a worse / better open (stop /
  target), the flat minute's open (flat).

**The 8 declared variants** (every one on 4 timeframes x 9 instruments = 288 cells):

| variant | CCI length | levels | pulse | divergence | exit |
|---|---|---|---|---|---|
| B (as given) | 25 | +-200 | off | all signals | Z |
| XR | 25 | +-200 | off | all | R (2R) |
| XT | 25 | +-200 | off | all | T (10 bars) |
| PU | 25 | +-200 | ON | all | Z |
| DV | 25 | +-200 | off | divergence-confirmed only | Z |
| L20 | 20 | +-200 | off | all | Z |
| L50 | 50 | +-200 | off | all | Z |
| K100 | 25 | +-100 | off | all | Z |

**DV (divergence-confirmed).** A BUY counts only if a bullish divergence is CONFIRMED (known, bar p + 5) within 10 bars
of the BUY bar, before or after it. The trade is entered at the LATER of the two bars (if the divergence confirms after
the CCI signal, the entry waits for it; no trade if by then the CCI has already crossed 0 or the stop's level was
touched). The stop is the lower of the episode's low and the pivot low, - 1 tick. SELL mirrored.

**Timeframes.** 3m, 5m, 15m, 60m. On 60m a 10-bar time exit and most CCI-zero exits are cut by the session flat; that
is part of the result.

## 5. Pricing and costs

- **Option:** the 1-ITM call (BUY) / put (SELL), nearest expiry, real 1-minute prints. Index: R51 engine via R90's
  pricing (the app's slippage model: 5 bps + 1 / 2 / 4 ticks by volume; dated charges), 1 lot in force on the date.
  MCX: R89's port of R53's simulator and R85's strict half-spread, 1 lot (GOLDM 10, SILVERM 5, CRUDEOILM 10,
  NATGASMINI 250), exit at the underlying exit minute's option print.
- **Underlying:** points per trade (exit - entry, signed) and the same x the lot in Rs, BEFORE any cost; reported as
  "the underlying-points win rate".
- A trade whose option never printed (no fill) is reported as unfilled and excluded from the option stats.

## 6. Descriptive "indicator vs actual price" (no gate; design first, holdout in the one final read)

For the B signals (CCI 25, +-200) on each instrument and timeframe:
1. The price meaning of the levels: at the signal bar and the bar before it, `tp - SMA25(tp)` in points, in ATR14 units
   and in MD units (by definition 0.015 x CCI); `close - SMA25(tp)`; the ratio MD / standard deviation of the window.
2. How often: signals per session, BUY and SELL, by timeframe; the share inside the trade window.
3. What price does next: from the entry (the next minute's open), the signed underlying return at the close of 5, 15,
   30 and 60 bars later, and the MFE / MAE over those bars (in points and in ATR14 units). Two views: on the continuous
   series (horizons may cross sessions; the share that does is reported) and within the session only (cut at the flat
   time). Compared with the same statistics from every bar of the window taken as a "signal" with a random side
   (the unconditional base rate).
4. The divergence repaint: for every confirmed divergence, the same forward statistics measured from the pivot bar's
   close (where the label is drawn, hindsight) and from the confirmation bar's close (5 bars later, when it is known),
   plus the option trade (1-ITM, Z exit, stop beyond the pivot) entered both ways; the difference in the underlying
   win rate, mean points and option Rs is "the inflation".

## 7. Twins, gates, walk-forward, holdout

**Twins (1000 per cell, seed 95).** Per trade, a random entry minute from the trade's own entry minute to the window's
last minute that day ('after' twin, gated); the twin keeps the trade's side, strike rule and stop distance. XR twins
keep the target distance too; Z, T and DV twins hold for the trade's own number of minutes (capped at the flat time)
unless stopped. Each twin is priced on the real option prints with the same costs. The twin total is the sum over the
cell's trades.
- `p_emp` = (1 + #twin totals >= the cell's net) / 1001.
- `p_norm` = the one-sided normal tail of the cell's net against the twin totals' mean and standard deviation.
- Why two: with 288 cells a single passer needs p <= 0.1 / 288 = 0.00035, below what 1000 twins can resolve (0.001).
  The BH step uses `max(p_norm, 1e-6)`; the twin gate uses `p_emp`.

**Design gates (all must hold):**
- G1 at least 40 filled option trades;
- G2 net (option, after costs) > 0;
- G3 `p_emp` < 0.05;
- G4 BH q < 0.10 over all 288 cells;
- G5 positive in 3 of 4 index folds (Apr-Jun 2025, Jul-Sep, Oct-Dec, Jan-Mar 2026); MCX 2 of 3 (Nov-Dec 2025,
  Jan-Feb 2026, Mar 2026);
- G6 positive without its 5 best trades;
- G7 worst month >= -Rs 13,000 (Boss's account stop).

**Walk-forward selection (descriptive):** per instrument and timeframe, before each fold pick the variant with the best
design net so far (>= 20 trades; ties to B) and record its next-fold net, against B's.

**Holdout (1 Apr - 19 Oct 2026), read ONCE** by `study.py final`, which refuses a second run (`results/FINAL_READ`).
All 288 cells are run there for description. A design passer becomes a PAPER candidate only if in the holdout: net
> 0, >= 20 trades, `p_emp` < 0.10, BH q < 0.10 among the design passers, worst month >= -Rs 13,000.

## 8. What is reported per cell (Boss's questions)

THE WIN RATE on the underlying (points > 0, no costs) and on the option after costs; average win / average loss and
the payoff ratio (|avg win / avg loss|); expectancy per trade; trades a day; net; max drawdown (daily); worst day and
worst month; green months; twin mean, `p_emp`, `p_norm`, q; exit reasons; average holding minutes; option gross and
cost per trade in points. Pooled books for the indices and MCX per variant and timeframe. Every number with its trade
count.

## 9. The app

Whether the Pine source runs unchanged in the app's Pine engine (`android/engine/.../pine`), checked against its
function list and its `ta.cci` / `ta.atr` / `ta.pivot*` code. If any cell becomes a paper candidate: an APP_SPEC for a
PAPER-only arm behind a switch that is off by default, logged in the Autopilot change timeline and undoable; nothing
goes live. If nothing passes: whether it still has a use as a chart overlay.
