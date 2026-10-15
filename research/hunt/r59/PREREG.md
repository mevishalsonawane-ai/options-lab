# R59 pre-registration: audit of "S2 Mean Reversion Dip Buy" on stock options

Written 15 Oct 2026, BEFORE any R59 trade or P&L was computed. Committed and pushed on its own.

## Why

Boss shared another AI's stock-option strategy on 15 Oct 2026. Its claim, summarised from their spec:

- Universe: RELIANCE, HDFCBANK, ICICIBANK, INFY, TCS, SBIN, AXISBANK, KOTAKBANK, ITC, TMPV (Tata Motors).
- Data: our Dhan lake `stock_options_rolling_1m`, 4 Nov 2024 - 14 Oct 2026, ATM only (offset 0), monthly expiry,
  resampled to 15-minute bars.
- Signal: `premium_change_pct = (close[t] - close[t-1]) / close[t-1] * 100` on the 15-minute ATM series; buy when the
  change is <= -20 and close >= 5. CE and PE separately.
- Entry at the signal bar's close; exit at `close[t+4]` (60 minutes). Rs 10,000 notional, contracts = 10000 / close
  (fractional, no lot size). Rs 95 a trade, flat. No stop, no slippage, overlapping trades allowed.
- Claimed: 6,945 trades, 73.4% win, +Rs 1,695 a trade, +Rs 1.177 crore total; every stock and side 66-80% win; TCS about
  +Rs 3,900 a trade on Rs 10k notional.

Why we doubt it: R45 (NIFTY premium scalp) and R46 amendment 3 (BTC 25% dip buy) showed that on a ROLLING ATM series a
"premium drop" is usually the series switching to another strike or to the next expiry, not the held option falling.
99.5% of the BTC version's profit came from switches and rolls. Also, Rs 10,000 cannot buy a stock-option lot, and a
flat Rs 95 ignores stock-option spreads.

**Signal source (CLAUDE.md):** their rule reads its signal from the OPTION's own price chart. That is NOT the default.
It is labelled non-default and reported separately; it could only ever go on paper with Boss's explicit yes. The
default-compatible comparison (B below) reads its signal from the STOCK.

## Data

- Drive lake `gdrive:IraAlgo-data-lake`, copied with rclone to a scratch folder, never committed, deleted after use:
  - `stock_options_rolling_1m/stkopt_<SYM>_MONTH.parquet` for the 10 stocks (rolling ATM-1..ATM+1, nearest monthly
    expiry, 1-minute; ATM is offset 0);
  - `equity_1m/equity_1m_{2024,2025,2026}.parquet` (the stocks' own 1-minute bars; corporate-action adjusted);
  - `nse_bhavcopy/*.csv.zip` 2024-2026 (weekly NSE F&O bhavcopies, UDiFF format): the real expiry dates
    (`XpryDt`) and lot sizes (`NewBrdLotQty`) of every stock-option contract.
- TMPV is Tata Motors: the bhavcopy calls it TATAMOTORS until 27 Oct 2025 and TMPV after; both names are read.
- Windows:
  - literal reproduction (step 1): 4 Nov 2024 - 14 Oct 2026, their claim's span;
  - honest tests: 20 Nov 2024 onwards only (Boss's start, after NSE's Nov 2024 lot revisions).
  - **Design:** 20 Nov 2024 - 31 Mar 2026. **Final test:** 1 Apr 2026 - latest (14 Oct 2026 options, 12 Oct 2026
    equity minutes), **read once** (a lock file `results/final_read.json`; the runner refuses a second read).
  - Nothing is tuned. Every setting is written here. The only number estimated from data (the per-stock Roll spread,
    below) is estimated on design sessions only, frozen in `results/spread.json` before the final read.

## Step 1: their number, literally

On each stock's rolling series, ATM (offset 0) rows of one side, 4 Nov 2024 - 14 Oct 2026:

- `resample("15min")` of the 1-minute rows (bins 09:15, 09:30, ... labelled by start), `close` = last close in the
  bin, empty bins dropped (as `dropna()`), so `t-1` of the day's first bar is the previous session's last bar.
- `pct = close.pct_change() * 100`; signal where `pct <= -20` and `close >= 5`.
- Entry at `close[t]`, exit at `close[t+4]` (the 4th next row; it can be the next session). Every signal trades
  (overlaps allowed). `pnl = 10000 / close[t] * (close[t+4] - close[t]) - 95`.
- Reported per stock and side and pooled: trades, win %, Rs a trade, total. "Close to their claim" means the same
  shape: thousands of trades, win rate well above 60%, strongly positive Rs a trade. A second reading (no `dropna`,
  `pct_change` with no fill) is reported if the first one is far off.

## Step 2: decomposition (where does their profit come from?)

For each literal trade, the contract at each 15-minute close is (expiry, strike, side) of the ATM row at the bin's last
minute; the expiry of a row is the first bhavcopy expiry of that stock on or after the row's date (the series holds the
nearest monthly expiry; it rolls the session after expiry). Two transitions are classified:

- **drop leg** (t-1 -> t) and **recovery leg** (t -> t+4): `same` (one contract), `switch` (other strike, same
  expiry) or `roll` (other expiry).
- A trade is **one contract throughout** when both legs are `same`.
- Also flagged: t-1 in the previous session (overnight) and t+4 in the next session.

Reported: share of trades and share of total profit by category, and the average Rs a trade of the one-contract trades.

## Step 3: the honest tests

### Contracts and cleaning

- A contract is (expiry, strike, side). Its 1-minute rows are every lake row that carried it, at any offset
  (ATM-1, ATM, ATM+1), de-duplicated on minute.
- **Foreign / stray rows** (R47 amendment 2 pattern): an option row whose own `spot` is more than 3% away from the
  stock's price is dropped. The stock price is the `equity_1m` close at that minute times the session's median
  ratio of option `spot` to `equity_1m` close (this cancels the adjustment of `equity_1m` for the HDFCBANK bonus and the
  KOTAKBANK split). Sessions with no equity minutes use the session's median option `spot` as the reference.
- **Spikes:** minutes flagged by `research/data/spikes.py` `spike_flags` (a close more than 50% away from both
  neighbours of the same contract that reverts) are dropped before anything is computed.
- **Stray-print guard on fills** (R47 amendment 1): a buy fills at the worse of the minute's open and the median of the
  contract's closes on the rows before, at and after it when the open is more than 20% (and 2 ticks) away from that
  median; a sell takes the lower of the two.
- **Sessions:** a session is a date with at least 300 of the stock's `equity_1m` minutes and option rows (short
  special sessions such as Muhurat trading are left out). Weekends and holidays have no rows and are not sessions.
- **Corporate actions:** no trade from 5 sessions before to 5 sessions after a corporate-action date, found from the
  data: the session's median option-spot / equity ratio moves more than 3% from the previous session's (bonus, split),
  or the stock's median option `spot` moves more than 20% from the previous session's (the TMPV demerger, 14 Oct 2025).
- **Expiry cut-off:** stock options are physically settled, so no trade on the expiry session or the 2 sessions before
  it (sessions E-2, E-1, E; E = the bhavcopy expiry, a session of the data). Every trade is intraday, so nothing is ever
  held into those sessions.

### Bars

15-minute bars 09:15-09:29, ..., 15:15-15:29 (25 a session). A contract's close in a bar is its last 1-minute close in
that bar. The ATM contract of a side at bar t is the contract of the offset-0 row of that side at the latest minute of
bar t that has one.

### A. Their idea on the HELD contract (option-chart signal: NON-DEFAULT, labelled)

- Signal at the close of bar t (t >= the session's 2nd bar; t-1 is the previous bar of the SAME session): take the ATM
  contract at bar t; its own close in bar t-1 (from any offset's rows) and in bar t must both exist, and its last trade
  in bar t must be in the bar's last 5 minutes (no stale prices). Signal when
  `(close_t - close_{t-1}) / close_{t-1} <= -20%` and `close_t >= 5`. CE and PE separately.

### B. The same idea with the signal on the STOCK (default-compatible)

- Signal at the close of bar t (same session, t >= 2nd bar), from the stock's `equity_1m` 15-minute closes (last close
  in the bar, which must be in the bar's last 3 minutes): stock return `close_t / close_{t-1} - 1`.
  - **CE:** buy the ATM call when the stock FELL 1.0% or more in the bar (a sharp move against the call, bought for the
    reversion).
  - **PE:** buy the ATM put when the stock ROSE 1.0% or more in the bar.
- The option bought is the ATM contract of that side at bar t, which must have a row in the bar's last 5 minutes. The
  1.0% is fixed here, not tuned.

### Trading (A and B alike)

- **Entry:** the held contract's first 1-minute row in the 5 minutes after the bar closes (bar t ends at minute
  15t+14; rows at minutes 15t+15 ... 15t+19, same session), at that row's open with the stray guard, plus the spread
  model. No row: no trade (counted).
- **Exit:** 60 minutes after the entry minute (or 15:25, whichever is first), at the open of the contract's first row at
  or after that minute within 5 minutes (same session, by 15:29), minus the spread. No such row: the contract's last
  close before the exit minute, with the 4-tick thin-market spread (counted and flagged).
- **Variants:** `NS` = no stop (their exit, kept for comparison only); `S25` = a premium stop 25% below the fill price
  (CLAUDE.md / Boss: stops on the option premium): checked on every 1-minute row of the held contract from the entry
  row up to the exit minute; a row that OPENS at or below the stop fills at that open (gap), else a low at or below the
  stop fills at the stop; stop fills pay the stop spread (twice the normal spread part, 10 bps).
- **No overlap:** one position per stock and side; signals while it is open are ignored.
- **Size:** exactly 1 lot, the lot in force for that contract on the trade date (the bhavcopy row of that stock and
  expiry dated on or before the trade date; if none, the earliest one after it). Capital per trade = fill x lot.
- **Charges:** `research/obuy/costs.py` `Costs("dated")`, the rates in force on the trade date (NSE), on both orders.
- **Spread / slippage (per stock):** R17's "liq" model as template: 5 bps plus a half-spread of 1 / 2 / 4 ticks
  (Rs 0.05) when the contract traded >= 20 lots / 1-20 lots / nothing in the 5 minutes before the order. Because stock
  options are wider than index options, the half-spread is the LARGER of that and a per-stock, per-side Roll (1984)
  estimate: on design sessions only, for every ATM contract-day with at least 30 pairs of consecutive-minute closes,
  `2 * sqrt(-cov(dlog c_t, dlog c_{t-1}))` (0 when the covariance is not negative); the median across contract-days is
  the stock's relative spread, and half of it is the relative half-spread. Frozen in `results/spread.json`.
- **2x costs:** the spread part (ticks / Roll) and the charges doubled. Reported for every cell.

### Random-entry twins (the p-value)

For each cell, 500 twin sets. In each set every real trade is replaced by a trade on the same stock, side and session,
entered at a random bar close of that session (bars 2-24 whose entry fits; the ATM contract of that side at that bar),
with the same fill, exit, stop, cut-offs and costs. A draw with no fill is drawn again (up to 10 times, else left out).
p = (1 + number of sets whose total net >= the cell's net) / 501. Pooled cells draw per trade the same way.

### Cells, gates, multiple testing

- Cells: {A, B} x {NS, S25} x {10 stocks x 2 sides} = 80 cells, plus 4 pooled cells (A/B x NS/S25, all stocks and
  both sides) = 84.
- **Benjamini-Hochberg** across all 84 final-test p-values (q).
- A cell PASSES only if, in the final test, at 1x costs: net > 0; p < 0.05; q <= 0.10; at least 40 trades; the net
  without its top-5 trades > 0 (top 5 < 100% of net); and its design net > 0.
- Per cell and period: trades, win %, Rs a trade, median, total net at 1x and 2x costs, worst trade, net without top 5,
  p, q, median and maximum capital per trade (premium of one lot), and how many exits used the fallback.
- `NS` cells are reported but cannot be adopted (no stop). Nothing in the app changes in R59. An A cell that passed
  could only go to paper with Boss's explicit yes (non-default signal source); a B cell that passed would still need
  Boss's yes for paper (R59 is an audit).

## Tests (pytest, `research/hunt/r59/test_r59.py`)

Switch / roll detection, the held-contract signal (a strike switch is not a drop), lot sizing by date and expiry,
the look-ahead guard (signals and trades computed on data cut at any minute are identical up to that minute; design
loading refuses final rows), the expiry cut-off, holidays and special sessions, missing minutes and stale bars, fills,
stops and the literal reproduction's arithmetic.
