# R91 pre-registration: Boss's ICT / Smart Money "liquidity sweep" (written 20 Oct 2026)

Written and committed BEFORE any statistic of this study was computed. Up to this commit only data coverage was looked
at (which lake files exist). Any later change is an amendment, dated, with its reason, in `NOTES.md`, made before the
numbers it affects.

## 0. The strategy and the question

Boss (20 Oct) sent a video strategy (ICT / Smart Money Concepts, "liquidity sweep"):
1. On the 1-hour chart, mark the previous swing high (for shorts) or the previous swing low (for longs).
2. Wait for the next day. If price breaks above the previous swing high but the 1-hour candle CLOSES back below the
   level, that is a fake breakout / "smart money trap" (the buyers' stops above the high were taken).
3. On the 5-minute chart: short at the low of the strong bearish candle (enter when it breaks); stop at the sweep's
   high; target the support zone. Longs are the mirror at a swing low.
(The video's advice to study named traders is ignored.)

Question: after real option prices and costs, does buying the put after a swept 1h swing high (the call after a swept
1h swing low), entered on the break of a strong 5m candle, beat random entry times, on any of 9 instruments?

**Signal source of every rule: the UNDERLYING** (index spot 1-minute OHLC for NIFTY, BANKNIFTY, SENSEX, FINNIFTY,
MIDCPNIFTY; for MCX the near future itself, close-only). The 1h swings, the sweep, the 5m strong candle, the entry
level, the stop and the target are all read on the underlying. A short setup = BUY the put; a long setup = BUY the
call (1-ITM, section 4). No rule reads an option's own chart; no option-chain data are used.

## 1. Prior work (read first, not repeated)

- **The Liquidity arm (Liquidity 15+5, LIQUIDITY.md, R19, R79-R81):** levels are LuxAlgo-style swing zones (pivot 20)
  plus liquidity pools on the 15m and 5m charts; the arm BUYS IN THE DIRECTION OF THE BREAK when a candle closes
  through a "both" level (a pool where a swing zone sits), stops on a failed break, and targets the next level. It
  trades the break; R91 FADES the failed break. Same family of levels (swing highs / lows where stops rest), opposite
  trade, different chart (1h swing, fractal 2 / 3, vs 15m / 5m pivot 20), different trigger (a 1h close back inside
  plus a 5m strong candle, vs a 15m / 5m close through). A day can carry both: the Liquidity arm buys the break, its
  failed-break stop is hit, and R91 then sells the reversal. Section 8 measures how often they trade the same day and
  side, and the correlation of their daily P&L.
- **R86 S1-S3 (stop runs, fade):** a 1-minute poke beyond yesterday's high / low, a round number or the opening range
  that closed back within 10 minutes; fading it lost on all 6 level x index tests (295-804 trades each, 2022-24), no
  better than random minutes. R87: stop runs above the day's high are followed by MORE upside. Prior evidence is
  against fades. R91 differs: the level is a 1h fractal swing from an earlier session, the failure must hold to the 1h
  close, and the entry waits for a strong 5m candle and its break (Boss's confirmation), with a structural target.
- **R89 / R89B (previous-day high / low break + a 1m candle):** 0 of 180 and 0 of 45; the underlying moved +0.4 to +3.5
  points a trade, below the option round trip of 1.1 (NIFTY) - 3.7 (SENSEX) points; a third of trades stopped within
  3 candles. Method lesson used here: the gated twin is the hindsight-free AFTER-TRIGGER twin.
- **R90 / R90B (Almazov structure & BOS):** 0 of 99, 0 of 189; sweeps were only marked there, never traded. Far
  higher-timeframe targets are rarely reached intraday, and the option bleeds time value meanwhile.
- **R62 / R62B (Volume Profile + SMC, MSB & order block):** 0 of 32, 0 of 16; the setups were a coin flip before costs.
- **R85 / R87 / R88:** candle shapes and parameters predict the next candle's SIZE, not its direction.
- What is new here: the failed 1h break of a previous-session 1h swing (the "sweep"), confirmed by a strong 5m candle,
  traded as the reversal with the stop at the sweep's extreme.

## 2. Data, windows, split (the same stores as R89 / R90)

| market | signal series | option prints | design | holdout (locked, read ONCE) |
|---|---|---|---|---|
| NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY | R51 day stores (lake `index_1m` spot 1m OHLC) | lake `options_rolling_1m` WEEK (R51 loader: held contracts, Amendment A clean + stale-print step) | 20 Nov 2024 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |
| GOLDM, SILVERM, CRUDEOIL (mini), NATURALGAS (mini) | near future = per-minute median `spot` of the option rows (R53 Market; close-only) | lake MCX MONTH books GOLDM, SILVERM, CRUDEOILM, NATGASMINI (R53 loader, R42 spike flags) | 5 Aug 2025 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |

- Copied from Drive `gdrive:IraAlgo-data-lake` on 20 Oct 2026; nothing raw is committed.
- Index levels warm up from the store's first day (1 Sep 2024); no trade before 20 Nov 2024 (Boss, 15 Oct).
- CRUDEOIL and NATURALGAS: the mini future is the signal and the mini option is bought (labelled "(mini)"), as in R89 /
  R90 (one lot of the full contract is 10x / 5x the risk).
- MCX bars are close-only (a minute's open is the previous minute's close; high / low the max / min of the two), so MCX
  wicks, and therefore sweeps, are understated. Stated as a limit.
- The holdout window was read by other studies for OTHER ideas; it is unseen for this idea.

## 3. The rule (mechanical; every choice fixed here)

**Bars.** 1h, 15m and 5m bars are built from 1-minute bars, anchored at the session open (NSE 09:15, MCX 09:00), never
across sessions (NSE 1h: 09:15, 10:15, ..., 14:15, plus the partial 15:15 bar, as TradingView draws it). A bar is known
at its close (its last minute's close).

**1h swing (fractal N, N = 2 base, N = 3 variant).** 1h bar p is a swing high if its high is strictly above the high of
each of the N bars before and the N bars after it (bars of earlier sessions count; the series is continuous). Swing
lows mirror it. The swing is CONFIRMED at the close of bar p + N and usable only after that close (look-ahead guard).
Its level is the swing bar's high (low); its session is bar p's session.

**Intact levels.** A confirmed swing high stays intact until a 1h bar CLOSES above it (a true break: removed) or it is
swept (section below: consumed, whether traded or not). Swings older than 5 sessions (bar p's session index < the
current session index - 5) expire. MCX: every level is cleared on the first session of a new contract (R89 skip rule).

**The "previous" swing (the candidate).** At each 1h bar close, the candidate for a short is the intact swing high
whose bar p is the LATEST among the eligible ones. Eligible: base "next day" = bar p's session is EARLIER than the
current bar's session (Boss: "wait for the next day"); variant ANY = any confirmed intact swing high. Longs mirror it
(the latest eligible intact swing low). A non-eligible bar touching a level does not consume it.

**Processing order at each 1h bar close:** (1) the sweep test on the current candidates (formed from the state before
this bar); (2) breaks: every intact level closed beyond by this bar is removed; (3) swings confirmed by this bar are
added.

**The sweep (short side).** A 1h bar k with high >= L + 1 tick and close < L, where L is the candidate swing high. Known
at the close of bar k. The level is consumed. The sweep high X = bar k's high. Long side mirrored (low <= L - 1 tick,
close > L, sweep low X = bar k's low). If one bar sweeps both candidates (an outside bar closing inside both), neither
side is set up (ambiguous). Variant S15: the sweep bar is a 15-minute bar (15m high >= L + 1 tick and 15m close < L,
known at the 15m close), and a 15m close beyond an intact level is the break that removes it; levels are still 1h swings.

**The strong 5m candle.** After the sweep bar closes, look at the next 6 5-minute bars of the same session (30
minutes). The first bar that is bearish (close < open), has body |close - open| >= 60% of its range (high - low), and
range >= 1.0 x ATR(14) of the 5m bars (Wilder, continuous across sessions, the value at the PREVIOUS 5m bar's close)
is the strong candle. If any 5m bar's high before that (from the sweep bar's close) reaches X + 1 tick or more, the
setup is cancelled (the sweep already failed). No strong candle within 6 bars -> no trade. Only the first strong candle
counts (one order per sweep). Long side mirrored (bullish candle).

**Order, stop, target (decided at the strong candle's close).**
- Entry E = the strong candle's low (a sell-stop on the underlying; the put is bought when it breaks). Long: its high.
- Stop S = X + 1 tick (the sweep's high, underlying). Long: X - 1 tick. Risk R = S - E (> 0).
- Target T, three declared ways:
  - **T-SW (base)**: the nearest intact confirmed 1h swing low below E (any session within the age limit, today
    included; the highest one below E). Long: the nearest intact 1h swing high above E (the lowest one above E).
  - **T-PD**: the previous session's low (long: high), if below E (long: above E).
  - **T-2R**: E - 2R (long: E + 2R).
- Minimum reward:risk: (E - T) / R >= 1.0 (variant RR2: >= 2.0) for T-SW and T-PD; no target available or RR too small
  -> no trade (the setup is consumed).
- The sell-stop is live from the minute after the strong candle closes for the next 6 5m bars (30 minutes), only inside
  the fill window; it is cancelled if the underlying reaches S first. Filled at 1-minute resolution: the first minute
  whose low <= E, at E, or at that minute's open if it opened below E.

**Session window.** NSE: fills only 09:30 - 14:45 (the 09:15 - 09:30 open is skipped), flat at the open of 15:15
(15:05 on the index's expiry day). MCX: fills from 09:15 to 22:30, flat at the R53 session cut; no MCX trade on an
option expiry day or on the first session of a contract (R89 / R90 rules). One position at a time per instrument; one
trade per sweep; at most 2 trades a day per instrument.

**Exit (underlying, 1-minute resolution, R89 `underlying_exit`).** On the fill minute only its close counts; later
each minute's high / low; the stop first when both are touched in one minute; else flat at 15:15 / 15:05 (MCX cut).

## 4. Execution and costs (option, primary)

BUY 1 lot of the **1-ITM** put (short setup) / call (long setup): the strike one step in the money from the ATM strike
at the fill minute, nearest expiry, that ONE contract held to the exit (R51 / R53 strike rules, as R89 / R90). ATM is
not tested.
- Index (R51 engine; the app's slippage model R17 "liq", dated charges): bought at the contract's open of the minute
  after the fill minute (first print within 2 minutes, else no trade): +5 bps + a half-spread of 1 / 2 / 4 ticks by
  5-minute volume. The underlying stop / target touched at minute m -> sold at the open of m + 1 (-5 bps - half-spread).
  Square-off at the open of 15:15 (15:05 on expiry). Charges `Costs('dated')`. (R90 `price.idx_option`.)
- MCX (R53 `sim_option`, R89's numba port): fill at the open of the first printed minute in (fill minute, +2]; exit on
  the future's minute close through the stop / target, sold at the option's close that minute (else the next print
  within 10 minutes); R53 McxCosts + R85's strict half-spread on both legs. (R90 `price.option`.)
- Lots in force on the date (R51 table; GOLDM 10, SILVERM 5, CRUDEOILM 10, NATGASMINI 250).
- Stops and targets are read on the underlying only (no premium stop in this study).
- Also reported: the same trades in underlying points (no costs) and in rupees (points x lot).

## 5. Variants (declared now; nothing else will be run)

| id | swing N | sweep | eligible swing | target | min RR |
|---|---|---|---|---|---|
| B | 2 | 1h close back inside | next day (earlier session) | T-SW nearest 1h swing | 1.0 |
| N3 | 3 | 1h | next day | T-SW | 1.0 |
| ANY | 2 | 1h | any time | T-SW | 1.0 |
| S15 | 2 | 15m close back inside | next day | T-SW | 1.0 |
| TPD | 2 | 1h | next day | T-PD previous day's low / high | 1.0 |
| T2R | 2 | 1h | next day | T-2R fixed 2R | (2.0 by construction) |
| RR2 | 2 | 1h | next day | T-SW | 2.0 |

Fixed in all: strong candle body >= 60%, range >= 1.0 x ATR(14), within 6 5m bars; order live 6 5m bars; age limit 5
sessions; window and flat times of section 3; 2 trades a day; 1-ITM; 1 lot.

7 variants x 9 instruments = **63 cells**, one BH family (family `patterns-indicators`). Pooled rows (all indices, all
MCX) are shown, not tested.

## 6. Measures (every number with its n)

Per cell: option net (1 lot) after costs, trades, trades a day and a week, win rate, average win / loss, expectancy,
max drawdown (daily), worst day, worst calendar month, green months, net without the 5 best trades; exit reasons;
**how often the sweep fails**: the share of trades stopped (the underlying goes back through the sweep's extreme), the
share stopped within 3 5m bars (15 minutes) of the fill, and the share of sweeps cancelled before entry because price
retook the sweep's extreme; the funnel (sweeps -> strong candle -> RR ok -> filled -> option printed); underlying points a
trade (no costs) and the option's round-trip cost in points.

**Random-entry twins (gated, 500 per cell, seed 91): the AFTER-TRIGGER twin** (R89B A1, R90): for each trade, the same
day, side, strike rule and exit rule, the same point distances to the underlying stop and target from the twin's own
entry price; the entry minute uniform between the trade's own fill minute and the window's last entry minute that day.
p = (1 + #twins >= rule) / 501, one-sided. The whole-window twin (entry anywhere in the day's window) is shown beside it,
descriptively.

**Walk-forward:** no fitted parameters, so each cell is shown by fold (index: Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025,
Jan-Mar 2026; MCX: Nov-Dec 2025, Jan-Feb 2026, Mar 2026). Selection walk-forward: per instrument, at each fold pick the
variant with the best design net before the fold (>= 20 trades; ties -> B), record its next-fold net, against B's.

**Benjamini-Hochberg** over the 63 design cells' after-trigger twin p-values (q).

## 7. Gates (never relaxed; the same as R89 / R90)

A design cell PASSES only if all hold: (G1) >= 40 design trades; (G2) option net > 0; (G3) after-trigger twin p < 0.05;
(G4) BH q < 0.10 over the 63; (G5) net > 0 in >= 3 of 4 index folds (MCX >= 2 of 3); (G6) net > 0 without the 5 best
trades; (G7) worst calendar month >= -Rs 13,000 (Boss's account stop).

**Holdout:** read ONCE for all 63 cells by one command (`study.py final`, guarded by `results/FINAL_READ`), after the
design results and the experiment entries are committed. A design passer becomes a PAPER candidate only if in the
holdout: net > 0, >= 20 trades, after-trigger twin p < 0.10 with BH q < 0.10 over the design passers' holdout p-values,
worst month >= -Rs 13,000. Cells that fail design are reported in the holdout descriptively only.

Anything that passes gets an APP_SPEC for a PAPER arm only (logged in the Autopilot timeline, undoable in one tap,
behind a switch that is off by default). Nothing goes live. Anything that fails stays a note.

## 8. Comparison with the running arms (descriptive, same days)

On the index days of both windows (the holdout only after R91's own holdout read): R72 Breadth Donchian (C1), the
Liquidity 15+5 arm (R51 `fam_liq`, BANKNIFTY, plus the other four indices descriptively) and the ORB signal (R20 V0),
with R89 / R90's code on the same stores. Shown for B and the best design variant: the share of R91 trades on a day the
arm also trades (same side / opposite side), the daily P&L correlation, R91's net on the arm's days and on other days.

## 9. Tests (pytest, before any result)

Small hand-made cases: fractal confirmation timing (N = 2, 3), intact / broken / expired / consumed levels, the latest
eligible candidate (next day vs any), the sweep (wick above, close below; close above = a break, not a sweep), the
ambiguous two-sided bar, the 15m sweep, ATR(14) at the previous bar, the strong-candle test and its 6-bar window, the
pre-entry cancel, the three targets and the RR filter, the sell-stop fill and gap fill, the order's 6-bar life and its
cancel at the stop, the fill window and the daily cap. **Look-ahead guard:** for random synthetic minute series and cut
minutes, every swing confirmed, every sweep and every order / fill at or before the cut is identical when every minute
after the cut is replaced by noise (a swing is known only after its right-hand bars close; a sweep only after its 1h
candle closes).
