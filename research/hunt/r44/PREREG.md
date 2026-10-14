# R44 pre-registration: independent re-test of Boss's 5-year sweep (7 strategies)

Written 14 Oct 2026, BEFORE any R44 profit or loss was computed. Committed and pushed on its own. Amendment 1 (same
day, still before any P&L): signal and entry in the same session; MCX morning entries from 09:05. Amendment 2 (same day, before any
P&L; a data rule): CRUDEOIL expiry thresholds, because its last-day straddle at 22:00 is 0.4-2.7% of spot, so the
NSE threshold found 5 of its 14 expiries.

Boss (14 Oct): "Check the above strategy and provide me results". Boss's own workspace ran a 5-year sweep (`sweep.py`,
not in this repo) and found 0 of 14 strategy-side combos positive in-sample (2021-2023, 98,761 trades) or
out-of-sample (2024-2026, 181,313 trades). Its "October champion" EMA8/21 + ADX15 made +Rs 12,192 on 46 trades in a
25-session Sep-Oct 2026 window but -Rs 40,109 on 288 trades in Jan-Aug 2026. R44 re-runs the 7 strategies with our own
code and data, exactly as specified, and says whether our numbers agree. Nothing is tuned: every setting below is
Boss's specification, fixed now.

## Signal source

**Every signal is computed on the underlying**: the 5-minute index spot candle (NSE/BSE) or the CRUDEOIL futures price
(MCX). The traded instrument is the ATM option chosen at entry. Stops, targets and locks are on the option premium.
No rule reads an option's own chart.

## Data (Drive `gdrive:IraAlgo-data-lake`, fetched with rclone; nothing raw in git)

- `index_1m/index_1m_<UND>_<YEAR>` for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX (NIFTY from 2021 for the study,
  the others from 4 Aug 2021, MIDCPNIFTY from 31 Jan 2022; data to 12 Oct 2026). 5-minute bars are built from the
  1-minute bars: bar b covers minutes 09:15+5b .. 09:19+5b (75 bars a day); open = first minute's open, high/low =
  max/min, close = last minute's close. A 5-minute bar with no minutes is missing (no signal from it; indicators skip it).
- Options: `options_rolling_1m/opt_<UND>_WEEK_<YEAR>` only (the nearest expiry). BANKNIFTY/FINNIFTY/MIDCPNIFTY WEEK and
  MONTH files are byte-identical in 2025-2026 and for MIDCPNIFTY 2022, so the MONTH files are never read (no double
  count). SENSEX options exist from 2023. MIDCPNIFTY 2022 options are nearly empty (13k rows).
- CRUDEOIL: options `opt_CRUDEOIL_MONTH_<YEAR>` (Aug 2025 - Oct 2026). The lake has no continuous 1-minute futures
  history (only contracts live on 13 Oct 2026, from 20 Oct 2025), so the **signal series is the `spot` column of the
  option file**: Dhan's underlying of the CRUDEOIL option at that minute, the near futures contract. At each option roll
  the underlying switches contract (a price step); this is kept as is and noted. GOLD/GOLDM/SILVER/SILVERM excluded
  (4-7% bad option ticks). CRUDEOIL is all out-of-sample (no 2021-2023 data).

## Instrument and execution (all strategies)

- **Strike**: the ATM strike (Dhan offset 0 = strike closest to spot) at the first minute of the entry bar (the last
  ATM within the 3 minutes before it if that minute has none). CE for long signals, PE for short signals. That one
  contract is held to the exit, priced from its own 1-minute rows aggregated to the same 5-minute bars.
- **Entry**: the option's open of the NEXT 5-minute bar after the signal bar closes (the open of the first traded
  minute of that bar). A bar with no traded minute for that contract: no fill (counted).
- **Stop** 25% below entry (bar low <= stop). **Target** +50% (bar high >= target). Both in one bar: the stop fills.
  The entry bar itself is checked. Stop fill = min(bar open, stop) (a gap through the stop fills at the open); target
  fill = the target price.
- **Time exit**: after 12 bars (the entry bar is bar 1) at the close of the 12th bar.
- **Square-off**: NSE/BSE at the open of the 15:15 bar; MCX at the open of the 23:15 bar. Entries only on bars that
  start before the square-off bar.
- **The signal bar and the entry bar are in the same session** (a signal on a session's last bars that would enter
  at or after the square-off bar, or on the next day, is dropped).
- **Max 2 entries a day per side** (per strategy x index). One open position per strategy x index x side; a signal while
  that side's position is open is ignored. CE and PE are separate books.
- **Expiry days skipped** (primary): a day is an expiry day of the nearest contract if the ATM straddle's time value
  at 15:15 is < 0.6% of spot and the next session's is > 2x it (R25/R29 rule; the contract dies that day). A version
  that keeps expiry days is reported separately, labelled "incl. expiry". CRUDEOIL: the straddle is read at 22:00 IST
  (its session runs to 23:30/23:55) with thresholds < 3.0% and a next-session jump > 3x (amendment 2). The straddle is
  read at the last ATM minute at or before the read time (within 30 minutes), for every market.
- **Stepped profit lock (1b)**: the peak premium is the highest bar high since entry. Peak >= +25% -> lock at entry;
  +50% -> lock +25%; +75% -> lock +50%; +100% -> lock +75%. The lock set by a bar's high applies from the NEXT bar
  (the order inside a bar is unknown). Exit when a bar's low <= max(stop, lock), filled at min(open, that level).
  Literally "same + lock", so the +50% target stays and only the first step can bind. **Sensitivity 1c**
  (`ema821_adx15_plock_nt`): the same lock with NO +50% target, so all four steps can bind. 1c counts in the BH family.

## Signals (5-minute index close; EMA adjust=False; Wilder ADX(14) and RSI(14); indicators run continuously across days)

`cross_up(a, b)` = a > b and a[1] <= b[1]; `cross_dn(a, b)` = a < b and a[1] >= b[1]. Warm-up: the first 100 bars of
each series give no signal.

| id | CE when | PE when |
|---|---|---|
| ema821_adx15 | cross_up(EMA8, EMA21) and ADX14 > 15 | cross_dn(EMA8, EMA21) and ADX14 > 15 |
| ema821_adx15_plock | as ema821_adx15, with the stepped lock (1b) | |
| ema821_adx15_plock_nt | as 1b without the target (1c, sensitivity) | |
| ema921_adx20 | cross_up(EMA9, EMA21) and ADX14 > 20 | cross_dn and ADX14 > 20 |
| ema926_adx20 | cross_up(EMA9, EMA26) and ADX14 > 20 | cross_dn and ADX14 > 20 |
| rsi_fade | RSI14 crosses below 30 (cross_dn(RSI, 30)) | RSI14 crosses above 70 |
| rsi_momo | RSI14 crosses above 55 | RSI14 crosses below 45 |
| donchian20 | close > highest high of the prior 20 bars | close < lowest low of the prior 20 bars |

Wilder: RSI average gain/loss seeded with the simple mean of the first 14 changes, then avg = (prev*13 + x)/14.
ADX: +DM/-DM and TR (true range with the previous close), Wilder-smoothed (seed = sum of first 14, then
s = s - s/14 + x), DX = 100|+DI - -DI|/(+DI + -DI), ADX = mean of the first 14 DX, then Wilder average.

MCX CRUDEOIL: the same signals on 5-minute bars of the futures price from 09:00. Two variants: **morning** (entries on
bars starting 09:05-14:55) and **full** (entries on bars starting 09:05-23:10); both square off at 23:15.

## Sizes and costs

- Lots (Boss's "current"): NIFTY 75, BANKNIFTY 30, FINNIFTY 40, MIDCPNIFTY 75, SENSEX 20, CRUDEOIL 100. One lot a trade.
- **Boss costs**: Rs 95 a round-trip trade NSE/BSE, Rs 200 MCX, on raw fills (entry at the open, exits as above).
- **Real costs** (primary for the verdict): fills of research/obuy/costs.py Fills("liq") (market orders +-5 bps, stop
  sells -10 bps, plus a half-spread of 1 / 2 / 4 ticks for >= 20 lots / some / no volume in the contract's 5 minutes
  before the order; stops 2x), charges Costs("dated") (the STT/exchange rates in force on the trade date, BSE rates for
  SENSEX). CRUDEOIL: Zerodha MCX option charges (Rs 20/order, CTT 0.05% sell premium, exchange 0.0418%, SEBI, stamp
  0.003% buy, GST 18%), tick Rs 0.10, same slippage rule.
- **Historical lots** (sensitivity): the lot size in force on the trade date (research/hunt/r29 LOTS); reported as
  totals per strategy, and whether any verdict changes.

## Periods

- **IS** 2021-2023 and **OOS** 2024-2026 as Boss's sweep (for the side-by-side table).
- Our locked holdout (research/brain/holdout.py): sessions from **27 Apr 2026**; embargo 14-26 Apr 2026. So:
  - Step 1 (design read, `run.py design`): IS 2021-2023 and **OOS-design** 1 Jan 2024 - 13 Apr 2026.
  - Step 2 (the ONE holdout read, `run.py holdout`, writes `results/HOLDOUT_READ` and refuses a second run): the
    holdout 27 Apr - 12 Oct 2026, Boss's full OOS 2024-2026 (all sessions), the "October champion" windows
    (**Sep-Oct 2026 = the last 25 NSE sessions up to 12 Oct 2026**, and **Jan-Aug 2026** = 1 Jan - 31 Aug 2026),
    and the full 2021-2026 champion-family total. CRUDEOIL OOS-design is Aug 2025 - 13 Apr 2026.
  - No setting is chosen after Step 1; Step 2 only confirms.

## Statistics

- **Random-entry twins** per cell (strategy x index x side x session-variant), 2,000 twins: the same days and the
  same number of entries per day, entry bars drawn uniformly (without replacement within a day) from that day's
  allowed entry bars, the same side and the same exits. p = (1 + #twins with net >= the rule's net) / 2,001, one-sided,
  real costs. Computed for IS, OOS-design and (Step 2) the holdout.
- **BH** across every cell of R44 (8 strategy ids incl. 1c x 5 indices x 2 sides = 80 NSE cells, + 8 x 2 sides x 2
  CRUDEOIL sessions = 32 = **112 cells**), on the OOS-design p.
- Reported per cell and period: trades, win rate, net (Boss costs and real costs), expectancy a trade, net a session,
  worst day, max drawdown (on the daily real-cost equity), p, q; and the incl.-expiry version.

## Gates (a cell must pass ALL to be offered for paper)

1. >= 100 trades in OOS-design.
2. Real-cost net > 0 in IS (NSE), OOS-design and the holdout.
3. Boss-cost net > 0 in OOS-design.
4. BH q < 0.05 (OOS-design twins p).
5. Max drawdown (real costs, OOS-design) smaller than Rs 40,000.
6. OOS-design real-cost net > 0 without its best day.

If a cell passes: paper only, logged in the Autopilot timeline, after Boss's go-ahead. Nothing live. No app change in R44.

## Comparison with Boss's numbers

Side by side where comparable (OOS 2024-2026, Boss costs, current lots): BANKNIFTY ema821_adx15_plock CE (Boss 1,330
trades, -Rs 52,483); BANKNIFTY ema921_adx20 CE (710, -34,313); FINNIFTY ema821_adx15_plock CE weekly (451, -33,714);
NIFTY ema921_adx20 CE (768, -58,515); CRUDEOIL rsi_fade PE morning (497, -2,750); full NSE 2021-2026 ema821 family
(42,347 trades, -Rs 86,03,171). Gaps larger than 25% in trades or a different sign are explained.
