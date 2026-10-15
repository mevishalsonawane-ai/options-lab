# R59b pre-registration: audit of "Bollinger Band Mean Reversion" on stock options

Written 15 Oct 2026, BEFORE any R59b trade or P&L was computed. Committed and pushed on its own. It reuses R59's code
(`research/hunt/r59/`), cleaning, fills and frozen per-stock spread estimates; only the signals, one exit variant and
the decomposition are new.

## Why

Boss shared a second outside AI's stock-option strategy on 15 Oct 2026. Its claim, from their spec:

- Universe: RELIANCE, HDFCBANK, ICICIBANK, INFY, TCS, SBIN, AXISBANK, KOTAKBANK, ITC, TMPV (Tata Motors).
- Data: our Dhan lake `stock_options_rolling_1m`, ATM monthly CE and PE, 1-minute resampled to 15-minute bars.
- Signal: Bollinger(20 bars, 2.0 sd) on the OPTION PREMIUM close; buy when `close <= lower band` and `close >= 5`.
- Entry at the signal bar's close; exit 4 bars (60 minutes) later at that bar's close. Rs 10,000 notional
  (contracts = 10000 / premium, fractional), Rs 95 a trade flat. No stop, no target, overlapping trades allowed.
- Claimed: 7,297 trades, 73.0% win, +Rs 967 a trade (median +Rs 626), +Rs 70.5 lakh total, max drawdown -Rs 31,485,
  every quarter 70-77% win, every stock 67-77% win, 15.5 trades a day.

Why we doubt it: R59 (same universe, same lake, same exit) found that 100% of a similar claim's profit came from the
rolling ATM series switching strike or rolling expiry. A Bollinger band on the rolling series mixes contracts inside
its 20-bar window: a switch to a cheaper strike drops the "premium" far under a band built from the dearer strike, and
a switch back "recovers" it. Nobody holding one option gets those moves.

**Signal source (CLAUDE.md):** their rule reads its signal from the OPTION's own price chart. That is NOT the default.
Family A below is labelled NON-DEFAULT and reported separately; it could only ever go on paper with Boss's explicit
yes. Family B reads its signal from the STOCK (default-compatible).

## Data (Boss's rule, 14-15 Oct: only 20 Nov 2024 onwards)

- Drive lake `gdrive:IraAlgo-data-lake` copied with rclone to a scratch folder, never committed, deleted after use:
  `stock_options_rolling_1m/stkopt_<SYM>_MONTH.parquet` (10 stocks only), `equity_1m/equity_1m_{2024,2025,2026}.parquet`,
  `nse_bhavcopy/*.csv.zip` (expiries `XpryDt` and lots `NewBrdLotQty`). TMPV = TATAMOTORS in the bhavcopy before 27 Oct
  2025 (as R59).
- Windows:
  - literal reproduction (step 1-2) only: 4 Nov 2024 - 14 Oct 2026, their claim's span, labelled "a check of their
    claim only";
  - everything else: 20 Nov 2024 onwards only. **Design:** 20 Nov 2024 - 31 Mar 2026. **Final test:** 1 Apr 2026 -
    the latest session in the lake, **read once** (lock file `results/final_read.json`; the runner refuses a second
    read). No older-period numbers are reported.
- Nothing is tuned. Every setting is written here. The per-stock spread is R59's design-only Roll estimate, frozen in
  `research/hunt/r59/results/spread.json` (commit 68e9ef6) and reused unchanged.

## Step 1: their number, literally

On each stock's rolling series, ATM (offset 0) rows of one side, 4 Nov 2024 - 14 Oct 2026 (R59's `bars15`):

- `resample("15min")`, close = last close in the bin, empty bins dropped (the series crosses sessions).
- `mid = close.rolling(20).mean()`, `sd = close.rolling(20).std()` (pandas default, ddof = 1; the window includes bar
  t), `lower = mid - 2 * sd`. Signal where `close <= lower` and `close >= 5`.
- Entry `close[t]`, exit `close[t+4]` (4th next row; it can be the next session). Every signal trades.
  `pnl = 10000 / close[t] * (close[t+4] - close[t]) - 95`.
- Reported per stock and side and pooled: trades, win %, Rs a trade, median, total, max drawdown (cumulative P&L in
  entry-time order), win % per calendar quarter and per stock, trades a day (trades / dates with rows). "Reproduced"
  means the same shape: thousands of trades, win well above 60%, strongly positive Rs a trade. If it is far off, a
  second reading with ddof = 0 is reported.

## Step 2: decomposition (where does their profit come from?)

For each literal trade the contract of each 15-minute row is (expiry, strike, side) of the bin's last ATM row (R59).
Flags:

- **window mixed:** any of rows t-19 .. t-1 (the band's window) is on another contract than row t;
- **break leg switch / roll:** row t-1 -> t is another strike (switch) or another expiry (roll);
- **recovery switch / roll:** row t -> t+4 is another strike or expiry;
- **one contract throughout:** rows t-19 .. t+4 all on one contract.

Reported: share of signals and of total profit for each flag, Rs a trade and win % of the one-contract trades, and
R59's held-contract diagnostic (their trades valued on the contract held at the signal bar, Rs 10k, Rs 95, no spread).

## Step 3: the honest tests (R59's engine)

Unchanged from R59 (`research/hunt/r59/PREREG.md`, step 3): held contracts (expiry, strike, side) followed across
ATM-1..ATM+1 rows; lots from the bhavcopies (1 lot in force on the trade date); foreign / stray rows (3% off the stock)
and `spikes.py` ticks dropped; sessions need 300+ stock minutes; no trade from 5 sessions before to 5 after a
corporate action found from the data; no trade on the expiry session E or E-1, E-2; entry at the open of the held
contract's first 1-minute row in the 5 minutes after the signal bar closes (stray-print guard); exit 60 minutes later
(or 15:25) at the open of its first row within 5 minutes, else the last close with the 4-tick spread (counted);
spread = 5 bps + the larger of R17's 1/2/4 ticks and the stock's frozen Roll half-spread (stops pay double);
`Costs("dated")` charges on both orders; 1x and 2x costs; one position per stock and side (no overlap). Signal bars:
bars 2-24 of the session (b = 1 .. 23, as R59 and its twins).

### A. Their band on the HELD contract (option-chart signal: NON-DEFAULT, labelled)

- At bar t, take the ATM contract of the side (offset-0 row at the bar's latest minute that has one, within its last
  5 minutes). Its own 15-minute closes: in every bar where that contract has any row (any offset), the last close.
  Its last trade in bar t must be in the bar's last 5 minutes.
- The band uses the contract's own **20 most recent bars with a close, up to and including bar t** (they may reach
  into earlier sessions of the same contract; bars where it had no row are simply absent). **Fewer than 20 such bars:
  no signal.** No other contract ever enters the window. mid = mean, sd = sample sd (ddof = 1), lower = mid - 2 sd.
- Signal: `close_t <= lower` and `close_t >= 5`. CE and PE separately.

### B. The band on the STOCK (default-compatible)

- The stock's own 15-minute closes from `equity_1m` (last close in each bar of the book's sessions). Bar t's close must
  be in its last 3 minutes. Band on the 20 most recent bars with a close up to and including t (may reach the previous
  session); fewer than 20: no signal. Same mean / ddof = 1 / 2 sd.
- **CE:** stock close <= lower band -> buy the ATM call of bar t. **PE:** stock close >= upper band (mid + 2 sd) -> buy
  the ATM put of bar t. The option must have an ATM row in the bar's last 5 minutes.

### Exit variants (all three in both families)

- `NS`: no stop, their 60-minute exit (comparison only; cannot be adopted).
- `S25`: R59's premium stop 25% below the fill, on 1-minute rows (a row opening at or below it fills at its open).
- `S25L` (profit lock): S25, and once a 1-minute row's high reaches 1.20 x the fill, the stop is raised to the fill
  price (breakeven on the premium) from the NEXT row on; a row opening at or below the stop fills at its open, else a
  low at or below it fills at the stop. Lock and stop exits pay the stop spread. Time exit as above.

### Random-entry twins, cells, gates

- Twins (R59): 500 sets; every trade re-entered at a random bar close (bars 2-24 whose entry fills) of the same
  session, stock and side, on that bar's ATM contract, same variant, fills and costs. p = (1 + #sets with total >= the
  cell's net) / 501.
- Cells: {A, B} x {NS, S25, S25L} x {10 stocks x 2 sides} = 120, plus 6 pooled (all stocks, both sides) = 126.
  **Benjamini-Hochberg** across all 126 final p-values (cells with no trade count as p = 1).
- A cell PASSES only if, in the final test at 1x costs: net > 0; p < 0.05; q <= 0.10; 40+ trades; net without its top-5
  trades > 0; and its design net > 0.
- Per cell and period: trades, win %, Rs a trade, median, net at 1x and 2x costs, worst, net without top 5, p, q,
  capital per trade (premium x 1 lot, median / max), trades per eligible session, fallback exits, stop / lock exits.

### Before costs (Boss: is a later tuning round worth it?)

- **Gross** per trade = (raw exit price - raw entry price) x 1 lot, on the same exits as the 1x trades but with no
  spread, no bps and no charges (raw = the stray-guarded open, the stop / lock level or gap open, or the fallback
  close). Reported per family and variant, pooled, design and final, with a t-statistic (mean / (sd / sqrt n)).
- A later tuning round (band length / width, hold, stop / lock levels) is called **worth trying** for a family only
  if its pooled gross per trade is > 0 in BOTH design and final with t >= 2 in each, AND the final gross per trade is
  at least the family's median 1x cost per trade (spread + charges). Otherwise: not worth it. **No tuning grid is run
  in R59b.**

## What can change

Nothing in the app (research only). An A cell that passed could only go to paper with Boss's explicit yes
(non-default signal source); a B cell that passed would also wait for Boss.

## Tests (pytest, `research/hunt/r59b/test_r59b.py`)

Band on the held contract only (a strike switch in the window is not a band break; fewer than 20 bars: no signal),
switch / roll / window-mixed detection on the literal series, the literal arithmetic, the stock band (lower -> CE,
upper -> PE), the profit lock, gross before costs, lot sizing, the expiry cut-off over holidays, missing minutes and
stale bars, and the look-ahead guard (signals on data cut at a day or a minute are identical up to the cut; the design
loader and the final lock refuse).
