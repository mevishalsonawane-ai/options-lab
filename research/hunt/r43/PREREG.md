# R43 pre-registration: Boss's 7 sweep signals as zero-to-hero expiry-day trades

Written 14 Oct 2026, BEFORE any R43 trade, P&L or random twin was computed. Only the expiry calendar (day counts) had
been read. It is committed and pushed on its own. After that commit nothing here changes; a change needs a new entry, a
new pre-registration and a fresh holdout.

Boss's request (14 Oct 2026, "for zero to hero"): Boss's own 5-year sweep tested 7 signal families on the 5-minute INDEX
close, bought the ATM option at the next bar's open with a 25% stop, 50% target and 60-minute time exit, and SKIPPED
expiry days. 0 of 14 were positive in-sample (98,761 trades) or out-of-sample (181,313). R43 asks whether the same
signals catch zero-to-hero trades: expiry day, cheap OTM options, held for a big multiple.

## Signal source (CLAUDE.md "signal on the underlying")

Every R43 signal is computed on the **index spot** 5-minute bars (built from the lake's 1-minute index data). The option
is only what is bought. No rule reads an option's own chart for its signal. Stops, trails and locks are on the premium.

## Data

- Lake on Drive `IraAlgo-data-lake` (rclone): `options_rolling_1m/opt_<UND>_WEEK_<YEAR>` (Dhan rolling option, nearest
  expiry, ATM-10 .. ATM+10, 1-minute) and `index_1m/index_1m_<UND>_<YEAR>` for NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and
  SENSEX. The lake ends 12 Oct 2026.
- **Expiry days come from the data**, with R25's rule (ATM time value at 15:15 < 0.6% of spot and the next session's more
  than twice it). Only the WEEK files are read. Since 20 Nov 2024 BANKNIFTY, FINNIFTY and MIDCPNIFTY have only monthlies
  and their WEEK and MONTH files are the same file, so each expiry is counted once. Their monthly expiries after Nov 2024
  are kept (they are the index's own expiry days). Bad prints are removed with R25's same-minute rule (`r25data.clean`).
- Expiry days found (counts only were read):

  | index | design (to 31 Dec 2024) | holdout (1 Jan 2025 - 12 Oct 2026) | first - last |
  |---|---|---|---|
  | NIFTY | 178 | 93 | 5 Aug 2021 - 6 Oct 2026 |
  | BANKNIFTY | 174 | 21 | 5 Aug 2021 - 29 Sep 2026 |
  | FINNIFTY | 154 | 19 | 26 Aug 2021 - 29 Sep 2026 |
  | MIDCPNIFTY | 80 | 21 | 7 Jun 2023 - 29 Sep 2026 |
  | SENSEX | 84 | 91 | 19 May 2023 - 8 Oct 2026 |
  | **all** | **670** | **245** | 915 index-expiry-days |

- Known data limits (as R25): only ATM±10 strikes are stored each minute; a held strike that leaves the range is priced
  by R25's proxy (the outermost stored strike: an upper bound; or intrinsic when deep ITM). Every rule's trades are also
  re-run with out-of-range prices set to 0 and the count of affected trades is reported.

## Data split (locked holdout read ONCE)

- **Design: every expiry day up to 31 Dec 2024 (670 index-days). Holdout: 1 Jan 2025 - 12 Oct 2026 (245).**
- Nothing in R43 is fitted: every value below is fixed now. So the "walk-forward" inside design is the per-year
  result of each fixed rule (2021, 2022, 2023, 2024), reported for every rule.
- The holdout is read by one command (`study.py holdout`), which writes a flag file and refuses a second read.
- Honest caveat: earlier hero studies (R25, R26, R34, R35, R39) read the 2025-26 expiry days for OTHER rules. None of them
  ran these 7 index signals as hero trades, and Boss's sweep skipped expiry days. So the holdout is unseen for these
  signals, not for the market period.

## Signals (5-minute index bars; signal on the bar's close; enter at the NEXT 5-minute bar's open)

- 5-minute bar j of a session covers 09:15 + 5j to 09:15 + 5j + 5 (bar 0 = 09:15-09:20). O = the first minute's open,
  H/L = max/min, C = the last minute's close. Bars of every session (not only expiry days) form one continuous series,
  so indicators warm up on earlier days. No reset at the open.
- Indicators as TradingView Pine: EMA seeded with the first n-bar average; Wilder's RSI(14) and ADX(14) (`ta.dmi(14, 14)`)
  with Wilder's average (alpha 1/14) seeded the same way. crossover(a, b) = a > b and a[1] <= b[1]; crossunder the
  mirror.

| id | family | CE (buy call) | PE (buy put) |
|---|---|---|---|
| 1 | ema821_adx15 | crossover(EMA8, EMA21) and ADX(14) > 15 | crossunder(EMA8, EMA21) and ADX > 15 |
| 1b | ema821_adx15_lock | as 1 | as 1 (its own exit: the stepped lock below) |
| 2 | ema921_adx20 | crossover(EMA9, EMA21) and ADX > 20 | crossunder, ADX > 20 |
| 3 | ema926_adx20 | crossover(EMA9, EMA26) and ADX > 20 | crossunder, ADX > 20 |
| 4 | rsi_fade | RSI(14) crosses below 30 | RSI crosses above 70 |
| 5 | rsi_momo | RSI crosses above 55 | RSI crosses below 45 |
| 6 | donchian20 | close > highest high of the prior 20 bars | close < lowest low of the prior 20 bars |

- **Entry:** the signal at the close of bar j is read at the bar's last minute (grid row 5j + 4). The order is R25's
  app-style entry: a LIMIT at the ask, capped at the signal-time option price × 1.2 + Rs 0.10, worked over the next
  bar's first 3 minutes (rows 5j + 5 .. 5j + 7), never chased. No fill = no trade.
- **Windows** (signal bar CLOSE times): `all` = 09:20 - 14:45 (bars 0-65); `hero` = 12:30 - 14:45 (bars 38-65).
- **At most 1 trade per index per expiry day:** the FIRST signal in the window (either side) that has an eligible strike
  and a fill. If the first signal has no eligible strike or no fill, the next signal may trade.

## Strike rules (in the signal's direction)

- `cheap`: the nearest-the-money OTM strike whose last traded price is **Rs 5-30**, traded in the last 5 minutes, passing
  the app's stale-slack check (R25 `eligible`: no further-OTM strike priced above it by more than max(0.10, 25%)).
- `otm2`: the second OTM strike (ATM+2 CE / ATM-2 PE: the second stored strike beyond spot), traded in the last 5
  minutes. No price band.

## Exits (every exit has a stop; NO 50% target)

- `hold`: a minute close <= 50% of the fill sells at the next minute's open (the -50% stop); else the 15:05 expiry
  square-off.
- `trail`: the stop, plus once the best minute close >= 3× the fill, a close <= 50% of the best close sells.
- `time`: the stop and the trail, plus: if no minute close >= 2× the fill by the close 60 minutes after the fill minute,
  sell.
- `step` (variant 1b only): the stop, plus a ratchet on the best close: 1.25× locks 1.00×, 1.5× locks 1.25×, 1.75× locks
  1.5×, 2× locks 1.75×; from 3× up each whole multiple m locks (m − 1)×. A close <= the lock sells.
- Locks and trails move on a minute's close and act from the next minute's close; every sale is at the next minute's open
  (R25 sell fill). Stops read closes of bars closing <= 15:04. Peaks use the best close (one-print spikes in thin strikes
  cannot arm anything).

## Size, costs, fills

- **Budget Rs 5,000 a trade** (the app's Hero arm). Lots = floor(5,000 / (fill × lot)), lot in force that day
  (`research/obuy/config.py` LOT_SCHEDULE). 0 lots = no trade.
- Charges: the app's model at today's rates (`obuy.costs.Costs("app")`: Rs 20 an order, STT 0.15% of the sell, exchange,
  SEBI, stamp, GST; BSE rates for SENSEX). Fills: R25's (`Fills("liq")`: the open ± 5 bps ± a half-spread of 1 / 2 / 4
  ticks by the last 5 minutes' volume). Expiring worthless: no sell order, no charges.
- **Stress:** every rule's trades re-run at 2× the half-spread (reported).

## The rule grid and the BH family (88 tests, all counted)

- Families 1, 2, 3, 4, 5, 6: 2 strike rules × 2 windows × 3 exits (hold, trail, time) = 12 each = 72.
- 1b: 2 strike rules × 2 windows × the step exit = 4.
- Baselines "same cheap option at the same time every expiry day, no signal": at the bar closing 09:45 (`all`) or 12:30
  (`hero`), buy BOTH the CE and the PE by the strike rule (2 trades a day, Rs 5,000 each), × 2 strike rules × 3 exits = 12.
- **BH (Benjamini-Hochberg) over all 88 holdout p-values.** A design BH over the 88 design p-values is also reported.

## Random-entry twins (p)

- 500 twin sets per rule (seed 43). Each rule trade is re-entered at a uniformly random signal-bar close of the SAME
  window on the same index-day, same side, same strike rule, budget and exit, among the bars at which that strike rule
  had an eligible strike and a fill.
- p = (1 + number of twin sets with total net >= the rule's net) / 501.

## Report per rule (design and holdout)

Trades (CE / PE), win rate, number of 5× and 10× heroes caught (peak high while held >= 5× / 10× the fill) and banked
(average sale >= 5× / 10×), net, net per index-expiry-day, net per Rs 1,000 risked, worst day, max drawdown, top-5
trades' share of net, net without the top 5, per-year and per-index nets, 2× spread net, out-of-range-at-0 net, p, q.

## Gate (a rule passes only if ALL hold)

1. Design net > 0.
2. Holdout net > 0.
3. Holdout twin p < 0.05.
4. Holdout BH q <= 0.10 (88 tests).
5. Holdout trades >= 40.
6. Holdout top-5 trades' share < 100% of net (net is still > 0 without the 5 best trades).

## If a rule passes

Paper only (CLAUDE.md): a labelled, logged, one-tap-undoable paper arm change shown to Boss, never live by itself. R43
changes no app code. If nothing passes: no app change, said plainly.
