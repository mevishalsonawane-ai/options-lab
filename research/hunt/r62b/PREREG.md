# R62b pre-registration: "Market Structure Break & Order Block" (written 15 Oct 2026, before any P&L)

Boss, 15 Oct 2026, shared a second video:
- indicator "Market Structure Break & Order Block" on TradingView, settings: Fib Factor for breakout confirmation =
  **0.273**, Show Zigzag on;
- a bullish market-structure break (MSB) draws a green buy zone (order block, OB); a bearish break a red sell zone;
  enter when price pulls back into the zone, in the trend's direction;
- stop just outside the structural swing; target the next opposing structural level / liquidity pool with a favourable
  risk:reward;
- claimed: 75-80% backtested win rate "when strictly followed with proper trend alignment".

R62b ports the indicator's real code, fixes every subjective word as a mechanical rule (below), tests it honestly on
the five index options, and reports the win rate next to the money. Research only: nothing in the app changes because
of this file. The sibling study R62 (volume profile + OB/FVG/BOS, branch `claude/r62-volume-profile-smc`) is not
repeated: R62's BOS uses fractal swings and a close beyond them; R62b tests this indicator's own zigzag-and-fib rule.

## 1. The source: OBTAINED, ported line by line (not a reconstruction)

- Script: "Market Structure Break & Order Block" (short title "MSB-OB") by **EmreKb**, open-source, Pine v5, Mozilla
  Public License 2.0. Page: https://www.tradingview.com/script/DkE7UniD/ (published 19 Jun 2022, last release note
  8 May 2023). Source read on 15 Oct 2026 from TradingView's public script service (script id
  `PUB;3a1fb6197f314eb2912194d70934bf7e`, latest version). "Sith Trader" is the video's presenter; the published
  script is EmreKb's. The source file itself is not committed (it is EmreKb's; the port carries the MPL notice).
- Inputs: `zigzag_len` = 9 (default; the video does not change it), `show_zigzag` (drawing only), `fib_factor` (default
  0.33; the video sets **0.273**).
- The port (`r62bmsb.py`) follows the script's lines 52-127 and 147-176. Line numbers below refer to that source.

### 1a. Zigzag (lines 52-73)
On each bar t of the chosen timeframe (bars of all sessions joined in time order):
- `to_up[t]` = high[t] >= the highest high of bars t-8..t (9 bars, the current one included); `to_down[t]` = low[t] <=
  the lowest low of t-8..t. Before 9 bars exist both are false.
- `trend` starts at +1; if trend = +1 and to_down -> -1; if trend = -1 and to_up -> +1 (line 57).
- `up_since[t]` = bars since `to_up[t-1]` was last true (0 if to_up[t-1]); N = up_since if > 0, else 1 (also 1 when it
  never was). `low_val[t]` = the lowest low of the last N bars (t-N+1..t). `low_index[t]` = the latest bar j <= t at
  which low[j] == low_val[j] (Pine evaluates the condition bar by bar). Mirror: `high_val`, `high_index` from to_down.
- When trend changes on bar t: to +1 -> push (low_val[t], low_index[t]) onto the LOW pivots; to -1 -> push
  (high_val[t], high_index[t]) onto the HIGH pivots. Both lists start with 5 empty (NaN) entries.
- `h0, h0i` = the latest high pivot (value, bar), `h1, h1i` the one before; `l0, l0i`, `l1, l1i` for lows.
- **No repainting:** a pivot enters the lists only at the close of the bar where the trend flips, which is
  after the pivot bar itself. Everything below reads only the lists as they stood at each bar's close.

### 1b. Market-structure break (lines 104-109)
- `market` starts at +1. `last_l0`, `last_h0` = l0 and h0 as they stood on the latest bar where `ta.change(market)` (line
  107, called BEFORE line 109 updates market) was non-zero. Pine keeps a separate history for each `ta.*` call site, so
  that call sees market's value before line 109 on each bar: it is non-zero on the bar AFTER a flip. Before any flip,
  `last_l0`/`last_h0` are NaN (comparisons with NaN are false).
- Line 109: if last_l0 == l0 or last_h0 == h0, market is unchanged (both a new low pivot and a new high pivot are needed
  after a break before the next one). Else:
  - market = +1 and l0 < l1 and **l0 < l1 - |h0 - l1| x fib_factor** -> -1 (**bearish MSB**);
  - market = -1 and h0 > h1 and **h0 > h1 + |h1 - l0| x fib_factor** -> +1 (**bullish MSB**).
- The MSB is the bar where market changes (line 147). Note: the script's live rule compares confirmed pivots, not a
  close; the "close beyond the swing by fib x the leg" wording matches only a commented-out line (106). R62b tests the
  live rule (line 109) only.

### 1c. Order block (lines 111-127, 151, 158)
- Bullish OB bar: loop i from `h1i` to `l0i[9]` (l0i as it stood 9 bars ago); Pine steps -1 when the start is above the
  end. Each i whose candle is bearish (open > close) overwrites the OB bar; the last one in loop order wins. If either
  bound is NaN the loop does not run. When nothing is found the previous bar's OB bar is kept (line 112).
- Bearish OB bar: loop i from `l1i` to `h0i[9]`, bullish candles (open < close).
- At a bullish MSB the green zone is the bullish OB bar's FULL range [low, high]; at a bearish MSB the red zone is the
  bearish OB bar's [low, high].
- Not used: breaker / mitigation blocks (lines 129-145; not in the video) and the drawings. The script's own box
  deletion (lines 163-171) removes the OLDEST box in its list when any box is closed through (a bug); R62b uses the
  intended rule instead: a zone dies when a bar CLOSES beyond its far side.

## 2. Data (Drive lake `gdrive:IraAlgo-data-lake`, rclone, 15 Oct 2026; raw files never go to git, deleted after use)

- `index_1m/index_1m_<U>_2024..2026.parquet` (every signal) and `options_rolling_1m/opt_<U>_WEEK_2024..2026.parquet`
  (the held option), U = NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX.
- Option rows cleaned with R51 Amendment A (foreign rows and stray prints removed, `research/hunt/lakeclean.py`, the
  R47 filter) and `research/data/spikes.py`'s spike rule on contract-minutes (as R62).
- Window (Boss, 14-15 Oct): trades on sessions from **20 Nov 2024** only. Bars from 1 Sep 2024 only warm up the
  zigzag / market state (causal, so not look-ahead); nothing before 20 Nov 2024 is traded or reported.
- **Design (walk-forward by quarter):** 20 Nov 2024 - 31 Mar 2026. **Final test:** 1 Apr 2026 - the lake's last
  session, read ONCE by `r62bstudy.py final` (writes `results/FINAL_READ` first, refuses a second run). Earlier
  studies have seen these sessions, never with these rules.
- Design candidates are computed on bars cut at 31 Mar 2026. The final book starts flat: no final trade comes from a
  zone that became active before 1 Apr 2026.
- Sessions with < 300 one-minute bars are dropped (Muhurat hours).

## 3. Bars and the signal source

- **Signal on the underlying, trade the option** (CLAUDE.md): every MSB, zone, touch, stop, target and filter is read
  from the INDEX spot 1-minute bars; the 1-ITM nearest-expiry option is only what is bought.
- Signal timeframes (both pre-listed, both gated): **5-minute** and **15-minute** index bars aligned to 09:15; the
  trend filter's higher timeframe is **1-hour** bars aligned to 09:15 (the 15:15 bar has 15 minutes). Built as R62:
  O = first open, H = max, L = min, C = last close of the minutes present; a slot with no minute is no bar. A bar is
  known only at its last minute; anything that uses it acts from the next minute.

## 4. The trade

**Zone.** At a bullish MSB on signal bar t, the green zone [zlo, zhi] = the bullish OB candle's [low, high], with the
swing values as they stood at t: stop level S = min(l0, zlo) x (1 - 0.0002) ("just outside the swing low"), target
level T = h0 (the swing high: the next opposing structural level / liquidity above). Bearish mirror: red zone, S =
max(h0, zhi) x (1 + 0.0002), T = l0.
- **Fresh:** tradeable only if bar t closed above zhi (bullish; below zlo for bearish), so price must come back to it.
- **Life:** from the close of bar t until the first of: its first touch (one try per zone), a signal-bar close beyond its
  far side (bullish: close < zlo), the opposite MSB, the end of the session after the one it became active in.

**Entry (two pre-listed confirmations).**
- `tch` (touch): the first index MINUTE after activation whose low <= zhi (bullish; high >= zlo bearish) is the touch;
  buy at the next minute.
- `rej` (rejection): the first signal bar j whose low <= zhi is the touch bar. Signal at bar j's close if it closed back
  above zhi; else, if it closed inside the zone (>= zlo), at bar j+1's close if j+1 is green (C > O) and closes above
  zhi; else the zone is used up. Mirror for bearish.
- The signal minute must fall in 09:24 - 14:29 (fill 09:25 - 14:30); a first touch outside it uses the zone up.
- Fill: buy the **1-ITM** CE (bullish) / PE (bearish) of the nearest expiry chosen at the signal minute, at the next
  minute (R51: the contract's first print in that minute or the next 2), 1 lot in force on the date (R51 table), one
  HELD contract (never the rolling ATM series). At most one open position per index per variant.

**Exits** (all on the index; the option is sold at the next minute's open after the index minute that triggers):
- stop: the first minute whose low <= S (long) / high >= S (short);
- target: the first minute whose high >= T (long) / low <= T (short); a minute reaching both counts as the stop;
- time exit: 12 signal bars after the fill (60 minutes on 5-minute, 180 minutes on 15-minute);
- square-off 15:15, 15:05 on expiry days (R51's expiry flag). Nothing overnight.

**Target variants.** `st`: as above. `rr`: the same, but the trade is taken only if (T - entry) / (entry - S) >= 1.5,
using the index open of the fill minute ("favourable risk:reward"; mirror for shorts).

**Trend filter (higher-timeframe structure).** `x` off; `h` on: the port's `market` state (fib = the variant's fib) on
the last CLOSED 1-hour bar at the signal minute must equal the trade's direction.

## 5. Variants (16, one Benjamini-Hochberg family)

timeframe {`5m`, `15m`} x confirmation {`tch`, `rej`} x trend filter {`x`, `h`} x target {`st`, `rr`}, all at
**fib_factor = 0.273** (the video), zigzag 9, one book of the five indices pooled. Names e.g. `15m-rej-h-rr`.
**Sensitivity (information only, never gated, not in the BH family):** the same 16 at fib_factor **0.33** (the script's
default) and **0.50**.

## 6. Measurement and costs (as R62)

- R51's engine (`research/hunt/r51/engine.py`) on the cleaned store; R17 'liq' fills (buy +5 bps + 1/2/4 ticks by the
  contract's 5-minute volume; every exit a market sell -5 bps, since it is a next-minute order on an index trigger);
  charges `obuy.costs.Costs('dated')` (STT from 1 Apr 2026; BSE rates for SENSEX). **2x:** R51 SLIP2 and twice the
  charges.

## 7. Statistics, gates and what is reported

- Random-entry twins (500 per variant, seed 621): each real trade is re-drawn on the same index, session and side at a
  random fill minute in 09:25-14:30 (1-ITM at the minute before), with the same stop and target distances in % of the
  index, the same time exit and square-off. A draw that cannot fill is redrawn up to 5 times; never filling adds 0.
  p = (1 + #twin totals >= real net) / 501.
- BH across the 16 final p-values.
- **PASS** needs all of: final net > 0 at 1x; p < 0.05; q <= 0.10; 40+ final trades; top-5 trades < 100% of final net;
  positive in design (design net > 0 AND net > 0 in >= 3 of the 5 design quarters 2025Q1 (with 20 Nov - 31 Dec 2024),
  2025Q2, Q3, Q4, 2026Q1).
- Reported for every variant, design and final, 1x and 2x: trades, **win rate (vs the claimed 75-80%)**, average win,
  average loss, realised R (index move to the exit / entry-to-stop distance), net, worst day, max drawdown, months
  green, exit reasons; per index trades, win rate and net.

## 8. What would change the app

Nothing directly. A PASS is at most a paper candidate for Boss, labelled "signal on the underlying", never live without
unseen paper first (CLAUDE.md safety rules).
