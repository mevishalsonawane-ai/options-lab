# R173 pre-registration: check Boss's credit-spread files, then re-test the idea on real option prices

Written 14 Nov 2026, **before any R173 trade or P&L number was computed** (committed on `claude/r173-credit-spread-check`
before the code's first run). Research only: nothing under `android/**` changes. Owner: Boss (they/them).

Boss, 14 Nov, sending five files made with another tool: *"First check these files"*. The files
(`inputs/`, untouched): `R72_Breadth_Strategy_Spec.md` + `Reproduce_R72_Breadth_Backtest.py`,
`R52_Liquidity_Strategy_Spec.md` + `Reproduce_R52_Liquidity_Backtest.py`, `R72__R52_60_Win-Rate_Explorer.html`.

Their claim: replace the arms' bought 1-ITM (R72) / ATM (Liquidity) option with a SHORT OTM CREDIT SPREAD in the break
direction (sell an OTM option, buy a wing further out), exit on the INDEX (target +3.2 x IDX_STOP_EXT, catastrophe stop
-4.5 x, else hold to 15:10) and get ~60-65% wins, R72 +Rs 2,36,036 (5 indices, 1 Nov 2024 - 19 Oct 2026, baseline
-Rs 1,12,428) and Liquidity "LIQ-2" +Rs 1,11,157.

## 0. What was read before this was written (no R173 result)

The two scripts were read in full before anything was run (they only read parquet files and print; no network, no file
writes). The audit items found by reading are listed in the report; the ones that decide the design here:

1. **Options are not priced, they are modelled.** `option_path_1m` / `option_params`: sigma = India VIX at the open,
   a fixed 3 days to expiry for every index and day, ATM premium = 0.3989 S sigma sqrt(3/365), the short leg = 0.55 x
   ATM with a FIXED delta 0.28, gamma 0.0002 and theta 22% of its premium per session (x 1.35 more in R72). The wing is
   never priced (the spread's delta, theta and cost are the short leg's alone). P&L = 0.28 x index points + theta.
2. Since 20 Nov 2024 BANKNIFTY, FINNIFTY and MIDCPNIFTY have only MONTHLY options (up to ~30 days to expiry), NIFTY's
   weekly moved from Thursday to Tuesday (Sep 2025): the 3-day, 22%-a-day theta is wrong for most of the trades.
3. Costs: two orders (Rs 40), STT 0.125% on one price, the spread law on one leg. A spread is FOUR orders.
4. No margin anywhere. A short spread blocks margin; Boss's account is about Rs 56k.
5. Selection: the HTML shows five target / stop presets (2.2x - 3.2x target, 4.5x / 4.8x stop) and the recommended
   one is the best and the edge of the sweep, picked on the whole window including the "holdout"; nothing corrects for
   the variants tried, and no random-entry control is run.
6. Small look-aheads: R72's session TWAP includes the CLOSE of the entry minute (the fill is at its open); LIQ's
   "new level" exit happens at the FIRST minute of the bar that confirms the new level (it is known at the bar's close).

## 1. The data this can run on (the honest limit, stated first)

- The Dhan lake (`gdrive:IraAlgo-data-lake`) the files name (and R106 / R152's stores, built from it, which hold the
  real Apr - Oct 2026 option prices) is on Google Drive and is **NOT reachable from this session** (as R171 found). So
  **Apr - Oct 2026 cannot be read here at all**, real or modelled. (It has been read by R51 - R171 anyway; it is no longer
  a clean holdout for anything.)
- Reachable: the public `research-data` GitHub release (as R171): real 1-minute OHLCV + OI option prices, ONE expiry a
  day (the nearest after the day; on an expiry day the next one), 20 strikes a day, for **NIFTY** (`nifty_prev`,
  `nifty_year`) and **BANKNIFTY** (`banknifty_prev_year_wide`, `banknifty_year_wide`); FINNIFTY / SENSEX index minutes
  (breadth only); India VIX and NIFTY minutes in `extra_series.parquet` (to 27 Feb 2026).
- **Nothing on or after 2026-02-24 is used** (docs/design.md:83, the 2026-02-24 .. 2026-09-05 hole; `r171data.usable`).
- **MIDCPNIFTY has no data at all** here: for R72's breadth it "counts as not breaking" (APP_SPEC 1.3, as R171), so all
  four of NIFTY, BANKNIFTY, FINNIFTY, SENSEX must break. FINNIFTY, MIDCPNIFTY and SENSEX options are not in the release,
  so **the real-price test covers NIFTY and BANKNIFTY only** (their claimed share: R72 +Rs 1,08,934 of the 2,36,036;
  LIQ +Rs 51,228 of the 1,11,157).

## 2. Part A: audit and parity (their own scripts on our data)

- Their scripts are run UNCHANGED (imported by path from `inputs/`, functions called, nothing edited) on index files
  rebuilt from the release in their format (`index_1m_<SYM>_<YEAR>.parquet`; INDIAVIX from `extra_series`), window
  **1 Nov 2024 - 23 Feb 2026** (all our data allows), with two forced, stated changes: R72 runs without MIDCPNIFTY
  (`UNDS` minus MIDCPNIFTY, the 4-of-4 rule above) and without `fut_expiries.json` (not in the release; their weekday
  rule still skips days); LIQ runs on the four indices we have. Parity = same code on a shorter, partly different
  dataset: the numbers are compared in sign and size, not to the rupee.
- **Model diagnostics (descriptive, never gated)**, all under THEIR model on the same days:
  (a) the share of their credit-spread P&L that is the theta term; (b) the same model with theta set to 0;
  (c) **random entries under their model**: every day / index, random side and random entry minute (09:20 - 14:30), their
  exits, 200 draws: if random trades also make money, the profit comes from the model, not the signal.

## 3. Part B: the same rules on REAL option prices (the test)

**Signal source: the index** (CLAUDE.md default): entries and the target / stop are on the index; the options are only
traded. Rules as their specs, with the look-aheads above removed (stated deviations):

| id | entry (index) | caps |
|---|---|---|
| R72-CS | R72's 5-minute Donchian 20 break with >= 4 of 5 indices breaking the same way on the same bar (their `build_panel_signals`, warm 60 bars); FRESH only (the index's previous same-side signal was not the bar before); next-minute entry 09:20 - 14:30; the entry index open on the break side of the session TWAP of the 1-minute closes BEFORE the entry minute; not on the index's own expiry day (from the data, as R171) | 1 open and 1 entry per side per day per index |
| LIQ2-CS | 15-minute Liquidity "both": a confirmed pool zone breaks on a bar's close where an active same-side swing zone overlaps it (their port of `indicator/liquidity.py`); entry at the next minute (bar start 09:20 - 14:30); target = nearest active same-side zone edge >= 0.8 x IDX_STOP_EXT ahead, else entry +- 2 x IDX_STOP_EXT | one position at a time per index |

**Structure (both):** break up -> sell the PUT at ATM - 2 strike steps and buy the put 3 steps further down; break down
-> sell the CALL at ATM + 2 steps, buy the call 3 steps further up (their `options_rolling_1m` rule: NIFTY 100 / 150
points, BANKNIFTY 200 / 300). ATM from the index's last close before the entry minute. The contract = the release's
expiry for that day (NIFTY: the nearest weekly; BANKNIFTY: the nearest monthly; on an expiry day the next one). 1 lot at
the dated lot (`r51cfg.lot`).

**Fills:** both legs at the entry minute's open, or within 2 more minutes if a leg has no print; a leg with no print in
those 3 minutes, or a strike not in the data -> no trade (counted). **Exits on the index** as specified (R72: index
+3.2 x / -4.5 x IDX_STOP_EXT from the entry index price; LIQ: target, -4.5 x beyond the broken level, a new same-side
level CONFIRMED (at that bar's close), 15:10). The index touch is seen in minute m; both legs are closed at the open of
minute m + 1 (first print within 3 minutes, else the leg's last close, counted as stale). Neither leg is ever held to
expiry.

**Costs (primary):** the R17 liquidity fills (`obuy.costs.Fills("liq")`: 5 bps + a half-spread of 1 / 2 / 4 ticks from
the leg's 5-minute volume before the order; the catastrophe exit is costed as a stop: 10 bps and twice the ticks) on
EACH of the four orders, and the dated Zerodha charges (`obuy.costs.Costs("dated")`: STT on each SELL order (the short
at entry, the wing at exit), exchange, SEBI, stamp on each BUY, GST, Rs 20 an order: Rs 80 brokerage a trade).
**Sensitivity:** the `options_lab/costs.py` spread law (roll / quoted / stress) crossed half each way on each leg.

**Margin (a stated approximation, not a broker number):** per spread, SPAN ~ the spread's maximum loss
((wing width - net credit) x qty: the long wing caps the scenario loss) PLUS exposure margin 2% of the short leg's
notional (index x qty); no position here ever holds a contract on its expiry day, so the 2% expiry-day ELM never
applies. R103's rule (2.5% of short notional) is shown beside it. **Account:** Rs 56,000; trades are walked in time order
across both indices; a trade is taken only if the margin of the spreads still open plus its own fits; the rest are
"not taken for margin" (counted). Both the ALL-trades result and the Rs 56k-account result are reported; **the gates are
judged on the Rs 56k-account trades** (what Boss could actually run).

**Comparison (same days, same data):** the arms' current bought-option rules as R171 rebuilt them on this release
(R72: 1-ITM, XL exits; Liquidity 15+5: ATM), re-costed with the same R17 fills and dated charges.

## 4. Periods (nothing is fitted in R173: their rules are taken as given)

| period | dates | role |
|---|---|---|
| warm-up | from each series' start | indicators / zones only |
| DESIGN | 2024-11-01 - 2025-03-31 | |
| WALK-FORWARD | 2025-04-01 - 2025-09-30 (two quarters) | |
| FINAL | 2025-10-01 - 2026-02-23 | read ONCE by `r173study.py final` (`results/FINAL_READ`) |

Honesty: their rules were chosen on 1 Nov 2024 - 19 Oct 2026, which CONTAINS every period here; and R171's out-of-sample
(Apr 2025 - Feb 2026) and other studies have read these months. So nothing here is a clean holdout for THEIR rules: the
question R173 can answer is narrower and still decisive: **do their rules make money on these days when the options are
priced from real trades instead of a model?** A rule that fails on real prices in-sample cannot be rescued by a holdout.

## 5. Gates (all needed; FINAL on the Rs 56k-account trades, R17 + dated costs)

1. net > 0 in DESIGN, in WALK-FORWARD and in FINAL;
2. beats random entries of the same structure: each FINAL trade gets random twins on the same day, index and side with a
   random entry minute (09:20 - 14:30, 5-minute grid) and the same exits (LIQ: the twin keeps the trade's target and stop
   distances from its entry, and its new-level exits); 2,000 random sets; one-sided p for the real total from the normal
   approximation of the random totals (the Monte-Carlo share printed beside; a Monte-Carlo p > 0.01 fails);
3. Benjamini-Hochberg q <= 0.10 over m = 3,913 earlier tests (R171's count, every earlier study) + R173's 2
   (R72-CS, LIQ2-CS) = **3,915**, earlier tests at p = 1;
4. >= 40 FINAL trades;
5. within the account limits over DESIGN + WF + FINAL: max drawdown better than -Rs 40,000 and no calendar month worse
   than -Rs 13,000;
6. the result keeps its sign under the quoted spread law too (reported; a pass that only holds under R17 is flagged).

**Even if a rule passed, nothing changes in the app**: switching an arm from buying to selling options is a strategy
change that needs Boss's explicit choice (CLAUDE.md), plus a margin check, broker-resting stops on the short leg and
the leg order (wing first in, short first out) the app does not have for arms (research/DEEP_OPTIONS_SELLING.md 10).

## 6. Reported

For every rule, index and period: trades (and not taken: no strike / no fill / margin), win %, net, net a trade, worst
day, max drawdown, worst month, exit mix, the real short-leg premium, days to expiry and implied delta (descriptive), the
margin per spread and the Rs 56k concurrency; the same for the bought-option arms; the parity table; the model
diagnostics. Sample sizes beside every number.
