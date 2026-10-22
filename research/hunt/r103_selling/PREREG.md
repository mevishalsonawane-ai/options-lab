# R103 pre-registration: the standard option-SELLING strategies on NIFTY and BANKNIFTY, research only

Written 22 Oct 2026 (about 12:35 UTC), before any strategy statistic was computed. Boss (22 Oct): "understand deeply how
options selling work". This is the honest-test half (Part B) of `research/DEEP_OPTIONS_SELLING.md`. Nothing here goes
to paper or live: option selling is not in the app and may not start without Boss's explicit yes and new safety rails
(DEEP_OPTIONS_SELLING.md, section 6).

**Not the same study as `research/hunt/r103_sell/`.** Another session, answering a different request of Boss's ("find
strategies with best win rate and more profit"), pre-registered a defined-risk study (iron fly / wide iron fly / iron
condor, 24 tests) under the same R103 number and branch. The two are separate: separate code, separate BH families,
separate log entries (this study's are `r103-deep-*`). Both use the same locked holdout window; this study reads it only
for a cell that passes every design gate (section 7), so it does not spend that window for the other study otherwise.

**Restoration note (see Amendment 2).** The container was reset at about 14:40 UTC before this file was committed. The
text below is restored word for word from the session record, with Amendment 1 as it stood at the reset.

Earlier work this builds on: NONDIRECTIONAL.md (Feb 2024 - Feb 2026, BANKNIFTY, one fill model, no holdout, no BH:
the naked straddle looked positive, the hedged versions lost, and the far-strike fill model was judged too pessimistic),
EXPIRY_PLAYBOOK.md X1-X4 (selling the straddle into the close is not shown to pay), R98 (97% of cheap options end
below their price, 1% end >= 10x, one 178x; selling was not tested), R94 (MCX selling cells, design only).

## 1. Data

- Lake on Drive (`gdrive:IraAlgo-data-lake`, copied 22 Oct 2026, plain `rclone copy`): `options_rolling_1m`
  (`opt_NIFTY_WEEK_2021..2026`, `opt_BANKNIFTY_WEEK_2021..2024`, `opt_BANKNIFTY_MONTH_2025..2026`; Dhan rolling nearest
  expiry, ATM-10..+10, 1-minute), `index_1m` (NIFTY, BANKNIFTY, INDIAVIX), `index_daily`.
- BANKNIFTY after 13 Nov 2024 has only monthly expiries; the WEEK and MONTH files hold the same contracts. One file per
  year is used, never both (README "Same contract in two files").
- Grid: row t = the 1-minute bar that starts at 09:15 + t minutes (375 rows). Nothing is filled: a missing minute is NaN.
- Expiry days are found from the data with R98's rule (ATM time value at 15:15 < 0.6% of spot and the next session's
  > 2x it), cross-checked against the NSE weekday rules in research/brain/calendar.py. The expiry date of a contract is
  known weeks in advance, so using it on the day is not look-ahead. Days to expiry (DTE) = sessions to the next expiry.
- Bad prints: R25/R98's same-minute cap (an option is capped at 1.25x + Rs 0.10 the high of the nearest nearer strike of
  the same side in the same minute) and rows whose strike is more than 12 steps from the index close are dropped.
- Lot sizes in force on each date (research/hunt/r51/r51cfg.py LOTS): NIFTY 50 -> 25 (26 Apr 2024) -> 75 (28 Nov 2024)
  -> 65 (31 Dec 2025); BANKNIFTY 25 -> 15 (21 Jul 2023) -> 30 (28 Nov 2024) -> 35 (27 Jun 2025) -> 30 (31 Dec 2025).

## 2. Windows (fixed)

| window | dates | use |
|---|---|---|
| **Design A (primary)** | 20 Nov 2024 - 14 Jul 2026 | the current rule set (one weekly expiry per exchange, big lots, expiry-day ELM). Every gate and the BH family are on this window. |
| Design B (earlier regime) | 5 Aug 2021 - 19 Nov 2024 | the same cells reported as context (both indices had weeklies, small lots); no gate, no selection. |
| **Locked holdout** | 15 Jul - 19 Oct 2026 | read ONCE, only for cells that pass every design gate (section 7). If no cell passes, it is not read. |

Walk-forward inside Design A: four blocks of equal length (about 5 months each); a cell must be net positive in at least
3 of the 4 blocks.

## 3. Structures (fixed; strikes chosen at the entry minute from that minute's spot and ATM straddle)

Step = 50 (NIFTY) / 100 (BANKNIFTY). K0 = the strike nearest spot at the entry minute. EM = ATM straddle price (CE + PE
at K0) at the entry minute. w = min(max(round(EM / step), 1), 6) steps (the strangle distance, about one expected
move).

| code | structure | legs |
|---|---|---|
| STR | short straddle (naked) | sell CE K0, sell PE K0 |
| STG | short strangle (naked) | sell CE K0 + w, sell PE K0 - w |
| IC | iron condor (hedged strangle) | STG plus buy CE K0 + w + 4, buy PE K0 - w - 4 |
| IF | iron fly (hedged straddle) | STR plus buy CE K0 + max(2, min(2w, 10)), buy PE K0 - the same |

Every leg must have a traded price in the entry minute (volume > 0 in the last 3 minutes and a close), else the day is
skipped for that structure. One lot per leg (the dated lot size). All legs are placed together at the entry minute; in
the app the long wings would be placed first (DEEP_OPTIONS_SELLING.md section 6), which the simulation does not need.

## 4. Entries, exits, stops, targets, blackout (fixed)

- **Entry times:** 09:20, 09:45, 10:15 (decision at that bar's close; fills at the NEXT minute's open).
- **Close:** at the 15:10 bar's open (decision at 15:09) on ordinary days; at the 15:05 bar's open on expiry days
  (CLAUDE.md: expiry square-off 15:05). Nothing is held overnight.
- **Stops** (one of):
  - X0 none (the baseline Boss's rules forbid; shown so the price of the stop can be seen);
  - X1 per-leg hard stop at 1.5x the sold price; X2 at 2.0x. A short leg is stopped when its minute HIGH reaches the
    level; the fill is max(level, that minute's close) plus stop slippage (a stop-market order in a fast market). When a
    short leg is stopped its wing (IC / IF) is sold in the same minute; the other side stays until its own stop or the
    close.
  - X3 combined stop: the structure's mark-to-market loss (on minute closes) reaches 100% of the net credit received;
    every leg exits at the next minute's open with stop slippage.
  - X4 combined stop at 3% of the margin estimate (section 5), the same way.
- **Profit target** (one of): T0 none; T1 close every leg when the structure's value on minute closes is at or below 50%
  of the credit (half the credit kept), fills at the next minute's open.
- **Event blackout:** always on in every cell: no entry on Union Budget days, RBI MPC decision days and general-election
  result days (research/hunt/r103_selling/events.csv, scheduled events only). The no-blackout result is a sensitivity
  line, not a cell.
- **Day sets:** ALL sessions, and EXPIRY days only, as separate cells. DTE buckets are context tables only.

Cells = 2 indices x 4 structures x 3 entry times x 5 stops x 2 targets x 2 day sets = **480**. That is the BH family.

## 5. Money (fixed)

- **Charges** (research/obuy/costs.py "dated" rates, per order per leg): brokerage Rs 20; STT on the SELL premium
  0.0625% (to 30 Sep 2024), 0.1% (1 Oct 2024 - 31 Mar 2026), 0.15% (from 1 Apr 2026); NSE exchange 0.053% (to 2022),
  0.0495% (2023 - Sep 2024), 0.03503% + IPFT 0.0005% (from Oct 2024); SEBI Rs 10 a crore; stamp 0.003% of the buy
  value; GST 18% on brokerage + exchange + SEBI. A seller pays STT on the entry (sale) and stamp on the buy-back.
- **Slippage from measured spreads** (h24, 6 Oct 2026: real half-spread 0.16% of the premium at ATM on NIFTY and
  BANKNIFTY; the lake has no bid/ask): half-spread h = max(0.16% x price x (1 + 0.5 x |strike - K0| / step), 1 tick).
  Market fills at the open +- h (sells receive open - h, buys pay open + h). Stop fills carry 2h.
- **Margin estimate** (stated, not measured; Zerodha quotes Rs 1.62 lakh for a 1-lot NIFTY ATM straddle on 26 Jun 2026,
  spot 24,056, lot 65 = 10.4% of notional, and Rs 1.93 lakh on 24 Sep 2026): naked straddle 11% of notional (lot x
  spot), naked strangle 10%, hedged IC / IF = max(1.25 x maximum loss, 2% of notional); on expiry days + 2% of notional
  (SEBI's expiry-day ELM on short options). Return on margin = net P&L / mean margin used. The margin only sets X4 and
  the return-on-margin column; a 30% error in it changes neither the P&L nor the gates on P&L.
- **Account stop (Boss):** cells are also judged against -Rs 40k drawdown and -Rs 13k in a month, on one lot.

## 6. Statistics (fixed)

Per cell: trades, win rate (net > 0), net P&L, average win, average loss, worst day, max drawdown (on the running daily
sum), return on margin, days with a loss worse than Rs 13k, months below -Rs 13k, the 4 walk-forward blocks.
- **p vs zero:** two-sided sign-flip test on the daily net P&L (10,000 draws, seed 103): share of draws whose mean is at
  least the observed mean in absolute value.
- **Random-entry twin:** the same structure, exits and days entered at a random minute between 09:20 and 13:30 (uniform,
  300 twins, seed 103 + cell index). Twin p = share of twins whose net is >= the cell's net. It says whether the chosen
  entry time matters; it is not the gate against zero.
- **BH:** Benjamini-Hochberg over the 480 p-vs-zero values, q <= 0.10.
- **The BUY side** of every structure x entry x day set (the mirror trade, with its own costs) is reported for
  comparison with no stop and with a combined 50%-of-debit stop plus a 100%-of-debit target. These are comparison rows,
  not candidates: they are listed in the write-up but outside the BH family (the family is the selling cells; the buy
  rows are not selected from).

## 7. Gates (never relaxed)

A cell passes the design only if ALL hold on Design A:
1. >= 40 trades;
2. net P&L > 0 after costs, p vs zero <= 0.05, BH q <= 0.10 over the 480 cells;
3. net positive in >= 3 of the 4 walk-forward blocks;
4. max drawdown >= -Rs 40,000 and no month below -Rs 13,000 (Boss's account stop, one lot);
5. a stop is on (X1-X4): Boss's rules require a stop-loss on every trade; X0 cells can never pass.
Then the holdout is read once for the passing cells; a cell holds if its holdout net is > 0 with the same stops and the
same account-stop rule. Whatever the result, nothing goes to paper or live from this study.

## 8. Descriptive parts (not tests; Design A and B, never the holdout)

- Theta by hour: the ATM straddle's value at 10:15, 11:15, 12:15, 13:15, 14:15, 15:05 as a share of its 09:20 value, by
  DTE (expiry day, 1, 2, 3+), median and quartiles; the expiry-day last hour in 5-minute steps.
- Tail days: the 20 biggest absolute daily index moves (close to close) since 2017 (index_daily) and, where option data
  exists (from Aug 2021), what the naked 09:20 ATM straddle, one lot, lost or made by 15:10 with no stop and with the
  X2 stop; also the 20 worst straddle days by premium loss.
- The straddle's own P&L distribution: skew, the share of the total made on the best 10% of days, the share lost on the
  worst 2%.
- Expiry-day spike check: how often an expiry-day straddle sold at 09:20 moved to 1.5x and 2x of its credit intraday.

## 9. What is NOT in this study

Overnight selling, calendars, ratio spreads, covered calls, stock options, crypto options, any IV filter, any
directional lean, any ML model: described in DEEP_OPTIONS_SELLING.md from public sources only, not tested. The
signal source of every cell is "none" (non-directional, time-of-day entry), which CLAUDE.md requires to be stated.

## Amendment 1 (22 Oct 2026, 13:10 UTC): valuing a leg the lake has no print for

Found on the first design run (2 twins, a shake-out run): a leg whose strike has left the lake's rolling ATM+-10 window
(the lake holds, each minute, only the contracts within 10 strikes of that minute's spot) had no price at the close, and
the code fell back to its ENTRY price. For a long wing that drifted out of the window that invented a profit: BANKNIFTY
monthly iron flies showed +Rs 5.6 lakh in 20 months on one lot with a Rs 11k drawdown, which is not believable (the
wings cost Rs 400-600 and left the window on 101 of 123 days checked). Those numbers were discarded.

Rule from now on (r103sim.model_fill): a leg's close is forward-filled for at most 3 minutes; any minute still without
a print (strike outside the window, or no trade) is valued by Black-Scholes at that minute's index close, time to the
15:30 expiry close in sessions / 252, risk-free 6.5%, and an implied volatility = the leg's own IV at its last print
x (the nearest same-side printed strike's IV now / that strike's IV at the leg's last print), every IV solved from a
print under the same convention; with no own print at all, the ATM IV at entry. A missing high is the modelled close
(so a stop can fire on a crash day when the ATM strike has left the window). Trades touched by the model are counted
(`modelled_share`). The model was calibrated before the final run on 2025 prints (every 5th day, OTM strikes 8-10 steps
out, priced from an IV solved 30 / 120 minutes earlier): median model / actual 0.99 / 0.98 on NIFTY and 1.00 / 1.00 on
BANKNIFTY, quartiles within +-5%. Two simpler models were tried first and rejected by the same check: Dhan's `iv`
column (over-prices far strikes by 4-12% at the median: a different time convention) and the leg's own IV carried
unchanged (0.89 / 0.61 on NIFTY at 30 / 120 minutes: the far smile steepens as spot moves away).

Two consequences stated for honesty: (1) the shake-out run had already executed the holdout branch for the 27 cells
that passed under the wrong valuation (all BANKNIFTY / NIFTY iron flies and one NIFTY strangle); those holdout numbers
were wrong and are discarded with the rest, but the holdout CAN no longer be called unseen for iron-fly cells, and the
write-up says so. The runner now reads the holdout only when called with `--holdout`. (2) The lake's window is narrow
for BANKNIFTY monthlies early in the cycle (the ATM straddle is 1,500-1,900 points, the window +-1,000): the strangle
distance is capped at 6 strikes and the wings at 10, so the BANKNIFTY hedged cells are narrower than a standard monthly
condor. A labelled sensitivity (BANKNIFTY cells on DTE <= 5 only) is reported outside the BH family. No other rule
changed.

## Amendment 2 (22 Oct 2026, 15:10 UTC): container reset, what had been seen

The session's container was reset at about 14:40 UTC, before anything was committed; the data copy, this file and the
code were lost and are rebuilt from the session record with no rule changed. Before the reset these design results had
been seen (Design A only; the holdout was not read again):
- the shake-out run (Amendment 1, entry-price fallback): discarded;
- one full run with Dhan's `iv` column in the model (rejected by the calibration above). Its broad picture: no cell
  passed every gate; the naked straddle was positive in most stop / target cells but not significant after BH; the
  hedged structures lost, BANKNIFTY's heavily. Because a rejected model's run was seen, the final run's cells cannot be
  called blind to that broad picture; the rules, cells and gates above were fixed before it and are not changed after it.
The final run (the calibrated model) is the one reported.
